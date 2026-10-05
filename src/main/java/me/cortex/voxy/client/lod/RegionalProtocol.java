package me.cortex.voxy.client.lod;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.ArrayList;
import java.util.Objects;
import java.util.zip.CRC32C;

/** Current spatial-key protocol. Storage indexes stay on the server; local journals stay independent. */
final class RegionalProtocol {
    static final String ALPN = "voxy-region-cache-start";
    static final int STREAM_CONTROL = 0, STREAM_SECTION_LANE = 1, STREAM_DISCOVERY = 2;
    static final int MAX_DIMENSION_BYTES = 1024, MAX_CATALOG_BYTES = 64 * 1024 * 1024;
    static final int MAX_CATALOG_COMPRESSED_BYTES = Math.toIntExact(org.lwjgl.util.zstd.Zstd.ZSTD_compressBound(MAX_CATALOG_BYTES));
    static final int MAX_CATALOG_FRAME_BYTES = Math.addExact(76, MAX_CATALOG_COMPRESSED_BYTES);
    static final int MAX_CONTROL_BYTES = MAX_CATALOG_FRAME_BYTES, MAX_SECTION_REQUESTS = 0xffff;
    static final int CONTROL_LIST_HEADER_BYTES = 7, SCOPED_DROP_BYTES = 12;
    static final int MAX_SECTION_BYTES = 4 * 1024 * 1024;
    static final int SECTION_FLAG_EMPTY = 1, SECTION_FLAG_PRESENT = 1 << 15;
    static final int C_OPEN = 0x01, C_DESIRE = 0x02, C_DROP = 0x04, C_SETTINGS = 0x05;
    static final int S_HELLO = 0x81, S_MANIFEST = 0x82, S_CATALOG = 0x83, S_INVENTORY = 0x84, S_RECORD = 0x85;
    static final int S_ERROR = 0xfe, S_SHUTDOWN = 0xff;
    private static final int RECORD_BYTES = 88;
    private RegionalProtocol() {}
    enum Lane { COVERAGE(0), REFINEMENT(1); final int id; Lane(int id) { this.id = id; } }
    enum Status {
        NOT_READY, ABSENT, EMPTY, DATA, REUSE;
        static Status from(int value) throws IOException {
            if (value < 0 || value >= values().length) throw new IOException("invalid section status");
            return values()[value];
        }
    }
    record Fingerprint(long low, long high) {
        static final Fingerprint ZERO = new Fingerprint(0, 0);
        static Fingerprint read(ByteBuffer input) {
            return new Fingerprint(input.getLong(), input.getLong());
        }
        void write(ByteArrayOutputStream output) {
            putLong(output, this.low);
            putLong(output, this.high);
        }
        boolean isZero() { return this.low == 0 && this.high == 0; }
        byte[] bytes() {
            return ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
                    .putLong(this.low).putLong(this.high).array();
        }
    }

    record Hash32(long a, long b, long c, long d) {
        static final Hash32 ZERO = new Hash32(0, 0, 0, 0);
        static Hash32 read(ByteBuffer input) {
            return new Hash32(input.getLong(), input.getLong(), input.getLong(), input.getLong());
        }
        void write(ByteArrayOutputStream output) {
            putLong(output, this.a); putLong(output, this.b);
            putLong(output, this.c); putLong(output, this.d);
        }
        boolean isZero() { return this.equals(ZERO); }
        byte[] bytes() {
            return ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
                    .putLong(this.a).putLong(this.b).putLong(this.c).putLong(this.d).array();
        }
    }


    record Desire(int dimensionId, Hash32 worldIdentity, long ticket, long key, int purpose,
                  LocalSection have, long rank) {}
    record ScopedKey(int dimensionId, long key) {}
    record DimensionAnchor(int dimensionId, int x, int z) {}
    record DimensionInfo(int id, String name, Hash32 worldIdentity, int minSectionY, int sectionCount,
                         boolean customBorder, double centerX, double centerZ, double borderSize,
                         long catalogId, Hash32 catalogFingerprint) {}
    enum InventoryState {
        SNAPSHOT_BEGIN, SAVED_PUBLISHED, UNREADABLE_REGION, REMOVED_REGION,
        SNAPSHOT_COMPLETE, FAILURE, SAVED_NOT_PUBLISHED;
        boolean saved() { return this == SAVED_PUBLISHED || this == SAVED_NOT_PUBLISHED; }
    }
    record DimensionExclusion(String name, String reason) {}
    record Manifest(List<DimensionInfo> dimensions, List<DimensionExclusion> excluded) implements Control {
        Manifest { dimensions = List.copyOf(dimensions); excluded = List.copyOf(excluded); }
    }
    record RegionInventory(int dimensionId, long revision, InventoryState state,
                           int regionX, int regionZ, long[] savedSlots) implements Control {
        RegionInventory { savedSlots = savedSlots.clone(); }
        @Override public long[] savedSlots() { return this.savedSlots.clone(); }
        boolean savedChunk(int slot) { return (this.savedSlots[slot >>> 6] & 1L << (slot & 63)) != 0; }
    }
    sealed interface Control permits ServerHello, Manifest, RegionInventory, CatalogMessage,
            SectionReply, ServerError, ServerShutdown {}
    record ServerHello(long serverInstance, int activeDimensionId, Hash32 worldIdentity, long catalogId,
                       Hash32 catalogFingerprint) implements Control {}
    record CatalogMessage(int dimensionId, Hash32 worldIdentity, Hash32 fingerprint,
                          int canonicalLength, byte[] compressed) implements Control {}
    record SectionReply(int dimensionId, Hash32 worldIdentity, long ticket, long key, long generation, Status status,
                        LocalSection content, byte[] compressed) implements Control {}
    record ServerError(int code, String message) implements Control {}
    record ServerShutdown(String message) implements Control {}

    static byte[] open(String dimension, Hash32 expectedWorld, Hash32 heldCatalogue, long intervalMillis,
                       long bandwidthKbps, boolean refreshAllowed, int anchorX, int anchorZ,
                       List<Desire> desires) throws IOException {
        for (var desire : desires) if (desire.have() != null || desire.purpose() > 1)
            throw new IOException("initial terrain request must contain only visible missing coverage or detail");
        var payload = new ByteArrayOutputStream();
        putString(payload, dimension, MAX_DIMENSION_BYTES);
        (expectedWorld == null ? Hash32.ZERO : expectedWorld).write(payload);
        (heldCatalogue == null ? Hash32.ZERO : heldCatalogue).write(payload);
        settings(payload, intervalMillis, bandwidthKbps); payload.write(refreshAllowed ? 1 : 0);
        anchor(payload, anchorX, anchorZ); desires(payload, desires, false);
        return control(C_OPEN, payload.toByteArray());
    }
    static int openHeaderBytes(String dimension) { return 98 + dimension.getBytes(StandardCharsets.UTF_8).length; }
    static int desireBytes(Desire desire, boolean scoped) { return 26 + (scoped ? 36 : 0) + (desire.have() == null ? 0 : 63); }
    static byte[] desire(List<Desire> desires) throws IOException {
        var payload = new ByteArrayOutputStream(); desires(payload, desires, true);
        return control(C_DESIRE, payload.toByteArray());
    }
    static byte[] drop(List<ScopedKey> keys) throws IOException {
        if (keys.isEmpty() || keys.size() > MAX_SECTION_REQUESTS) throw new IOException("invalid drop count");
        var payload = new ByteArrayOutputStream(); putShort(payload, keys.size());
        for (var key : keys) {
            if (key.dimensionId() < 0 || (key.key() & 15) != 0) throw new IOException("invalid scoped drop");
            putInt(payload, key.dimensionId()); putLong(payload, key.key());
        }
        return control(C_DROP, payload.toByteArray());
    }
    static byte[] settings(long intervalMillis, long bandwidthKbps, boolean refreshAllowed,
                           int activeDimensionId, List<DimensionAnchor> anchors) throws IOException {
        if (activeDimensionId < 0 || anchors.size() > 0xffff) throw new IOException("invalid dimension settings");
        var payload = new ByteArrayOutputStream(); settings(payload, intervalMillis, bandwidthKbps);
        payload.write(refreshAllowed ? 1 : 0); putInt(payload, activeDimensionId); putShort(payload, anchors.size());
        for (var entry : anchors) {
            if (entry.dimensionId() < 0) throw new IOException("invalid dimension anchor");
            putInt(payload, entry.dimensionId()); anchor(payload, entry.x(), entry.z());
        }
        return control(C_SETTINGS, payload.toByteArray());
    }
    private static void settings(ByteArrayOutputStream payload, long intervalMillis, long bandwidthKbps) throws IOException {
        if (intervalMillis < 1000 || bandwidthKbps < 100 || bandwidthKbps > 10_000) throw new IOException("invalid download settings");
        Math.multiplyExact(bandwidthKbps, 125L);
        putLong(payload, intervalMillis); putLong(payload, bandwidthKbps);
    }
    private static void anchor(ByteArrayOutputStream payload, int x, int z) throws IOException {
        if (Math.abs((long) x) > 30_000_000 || Math.abs((long) z) > 30_000_000) throw new IOException("invalid player anchor");
        putInt(payload, x); putInt(payload, z);
    }
    private static void desires(ByteArrayOutputStream payload, List<Desire> desires, boolean scoped) throws IOException {
        if (desires.size() > MAX_SECTION_REQUESTS) throw new IOException("oversized desire frame");
        putShort(payload, desires.size());
        for (var desire : desires) {
            if (desire.dimensionId() < 0 || desire.ticket() == 0 || (desire.key() & 15) != 0
                    || desire.purpose() < 0 || desire.purpose() > 4 || desire.rank() < 512L * 512)
                throw new IOException("invalid desire entry");
            if (scoped) {
                putInt(payload, desire.dimensionId());
                Objects.requireNonNull(desire.worldIdentity(), "desire world").write(payload);
            }
            putLong(payload, desire.ticket()); putLong(payload, desire.key()); payload.write(desire.purpose());
            var have = desire.have(); payload.write(have == null ? 0 : 1);
            if (have != null) {
                putShort(payload, have.kind() == LocalSection.ABSENT ? 0 : SECTION_FLAG_PRESENT
                        | (have.kind() == LocalSection.EMPTY ? SECTION_FLAG_EMPTY : 0));
                payload.write(have.children()); have.catalog().write(payload); have.fingerprint().write(payload);
                putInt(payload, have.compressedBytes()); putInt(payload, have.canonicalBytes()); putInt(payload, have.crc());
            }
            putLong(payload, desire.rank());
        }
    }

    static Control readControl(InputStream input) throws IOException {
        int kind = input.read();
        if (kind < 0) throw new EOFException("regional stream ended");
        long length = readU32(input);
        if (length > MAX_CONTROL_BYTES) throw new IOException("oversized regional frame");
        try {
            if (kind == S_RECORD) {
                if (length < RECORD_BYTES + 36) throw new IOException("truncated section descriptor");
                var scope = ByteBuffer.wrap(readExact(input, 36)).order(ByteOrder.LITTLE_ENDIAN);
                int dimensionId = scope.getInt(); Hash32 world = Hash32.read(scope);
                if (dimensionId < 0 || world.isZero()) throw new IOException("invalid section scope");
                var descriptor = ByteBuffer.wrap(readExact(input, RECORD_BYTES)).order(ByteOrder.LITTLE_ENDIAN);
                long ticket = descriptor.getLong(), key = descriptor.getLong(), generation = descriptor.getLong();
                Status status = Status.from(Byte.toUnsignedInt(descriptor.get()));
                int flags = Short.toUnsignedInt(descriptor.getShort()), children = Byte.toUnsignedInt(descriptor.get());
                Hash32 catalog = Hash32.read(descriptor); Fingerprint fingerprint = Fingerprint.read(descriptor);
                int compressed = descriptor.getInt(), canonical = descriptor.getInt(), crc = descriptor.getInt();
                int body = status == Status.DATA ? compressed : 0;
                if (ticket == 0 || (key & 15) != 0 || body < 0 || body > MAX_SECTION_BYTES
                        || length != RECORD_BYTES + 36L + body || (flags & ~(SECTION_FLAG_PRESENT | SECTION_FLAG_EMPTY)) != 0
                        || status != Status.NOT_READY && status != Status.ABSENT && generation == 0)
                    throw new IOException("invalid section descriptor");
                int localKind = (flags & SECTION_FLAG_PRESENT) == 0 ? LocalSection.ABSENT
                        : (flags & SECTION_FLAG_EMPTY) != 0 ? LocalSection.EMPTY : LocalSection.DATA;
                if (status == Status.DATA && localKind != LocalSection.DATA
                        || status == Status.EMPTY && localKind != LocalSection.EMPTY
                        || status == Status.ABSENT && localKind != LocalSection.ABSENT)
                    throw new IOException("section status disagrees with descriptor");
                var content = new LocalSection(key, localKind, children, compressed, canonical, crc, fingerprint, catalog);
                byte[] bytes = readExact(input, body);
                if (body != 0 && crc32c(bytes) != crc) throw new IOException("section body CRC mismatch");
                return new SectionReply(dimensionId, world, ticket, key, generation, status, content, bytes);
            }
            if (kind == S_CATALOG) {
                if (length < 76) throw new IOException("truncated catalog scope");
                var scope = ByteBuffer.wrap(readExact(input, 36)).order(ByteOrder.LITTLE_ENDIAN);
                int dimensionId = scope.getInt(); Hash32 world = Hash32.read(scope);
                if (dimensionId < 0 || world.isZero()) throw new IOException("invalid catalogue scope");
                return readCatalogueBody(input, length - 36, dimensionId, world);
            }
            var payload = ByteBuffer.wrap(readExact(input, (int) length)).order(ByteOrder.LITTLE_ENDIAN);
            Control result = switch (kind) {
                case S_HELLO -> new ServerHello(payload.getLong(), payload.getInt(), Hash32.read(payload),
                        payload.getLong(), Hash32.read(payload));
                case S_MANIFEST -> readManifest(payload);
                case S_INVENTORY -> readInventory(payload);
                case S_ERROR -> new ServerError(Short.toUnsignedInt(payload.getShort()), readString(payload, 4096));
                case S_SHUTDOWN -> new ServerShutdown(readString(payload, 4096));
                default -> throw new IOException("unknown regional frame " + kind);
            };
            if (payload.hasRemaining()) throw new IOException("trailing regional control bytes");
            if (result instanceof ServerHello hello && (hello.serverInstance() == 0 || hello.activeDimensionId() < 0 || hello.worldIdentity().isZero()
                    || hello.catalogId() == 0 || hello.catalogFingerprint().isZero()))
                throw new IOException("invalid regional server identity");
            return result;
        } catch (java.nio.BufferUnderflowException | IllegalArgumentException failure) {
            throw new IOException("malformed regional frame", failure);
        }
    }
    private static Manifest readManifest(ByteBuffer payload) throws IOException {
        int count = Short.toUnsignedInt(payload.getShort());
        var dimensions = new ArrayList<DimensionInfo>(count);
        var ids = new java.util.HashSet<Integer>();
        var names = new java.util.HashSet<String>();
        for (int i = 0; i < count; i++) {
            int id = payload.getInt(); String name = readString(payload, MAX_DIMENSION_BYTES);
            Hash32 world = Hash32.read(payload); int minY = payload.getInt(), height = payload.getInt();
            int custom = Byte.toUnsignedInt(payload.get());
            double x = payload.getDouble(), z = payload.getDouble(), size = payload.getDouble();
            long catalogId = payload.getLong(); Hash32 catalog = Hash32.read(payload);
            if (id < 0 || !ids.add(id) || !names.add(name) || world.isZero() || height < 1
                    || minY < -128 || (long) minY + height - 1 > 127 || custom > 1
                    || !Double.isFinite(x) || !Double.isFinite(z) || Math.abs(x) > 30_000_000 || Math.abs(z) > 30_000_000
                    || !Double.isFinite(size) || size <= 0 || size > 60_000_000 || catalogId == 0 || catalog.isZero())
                throw new IOException("invalid dimension manifest");
            dimensions.add(new DimensionInfo(id, name, world, minY, height, custom != 0, x, z, size, catalogId, catalog));
        }
        int excludedCount = Short.toUnsignedInt(payload.getShort());
        var excluded = new ArrayList<DimensionExclusion>(excludedCount);
        for (int i = 0; i < excludedCount; i++) {
            String name = readString(payload, MAX_DIMENSION_BYTES), reason = readString(payload, 4096);
            if (name.isBlank() || reason.isBlank() || !names.add(name)) throw new IOException("invalid excluded dimension");
            excluded.add(new DimensionExclusion(name, reason));
        }
        return new Manifest(dimensions, excluded);
    }

    private static RegionInventory readInventory(ByteBuffer payload) throws IOException {
        if (payload.remaining() != 149) throw new IOException("invalid inventory extent");
        int id = payload.getInt(); long revision = payload.getLong(); int state = Byte.toUnsignedInt(payload.get());
        int x = payload.getInt(), z = payload.getInt(); long[] slots = new long[16];
        for (int i = 0; i < slots.length; i++) slots[i] = payload.getLong();
        if (id < 0 || revision == 0 || state >= InventoryState.values().length
                || Math.abs((long) x) > 58_594 || Math.abs((long) z) > 58_594)
            throw new IOException("invalid region inventory");
        return new RegionInventory(id, revision, InventoryState.values()[state], x, z, slots);
    }

    /** The persisted catalogue has one unchanged local format; network scope comes from its owning namespace. */
    static CatalogMessage readStoredCatalogue(InputStream input, Hash32 world) throws IOException {
        if (input.read() != S_CATALOG) throw new IOException("invalid stored catalogue record");
        long length = readU32(input);
        if (length > MAX_CATALOG_FRAME_BYTES - 36L) throw new IOException("oversized stored catalogue");
        return readCatalogueBody(input, length, 0, world);
    }

    private static CatalogMessage readCatalogueBody(InputStream input, long length, int dimensionId, Hash32 world) throws IOException {
        if (length < 40) throw new IOException("truncated catalog descriptor");
        var descriptor = ByteBuffer.wrap(readExact(input, 40)).order(ByteOrder.LITTLE_ENDIAN);
        Hash32 fingerprint = Hash32.read(descriptor);
        int canonical = descriptor.getInt(), bytes = descriptor.getInt();
        if (fingerprint.isZero() || canonical < 40 || canonical > MAX_CATALOG_BYTES
                || bytes < 1 || bytes > MAX_CATALOG_COMPRESSED_BYTES || length != 40L + bytes)
            throw new IOException("invalid catalog frame");
        return new CatalogMessage(dimensionId, world, fingerprint, canonical, readExact(input, bytes));
    }
    static int crc32c(byte[] bytes) { var crc = new CRC32C(); crc.update(bytes, 0, bytes.length); return (int) crc.getValue(); }

    static byte[] catalogFrame(CatalogMessage catalog) throws IOException {
        if (catalog.fingerprint().isZero() || catalog.canonicalLength() < 40
                || catalog.canonicalLength() > MAX_CATALOG_BYTES || catalog.compressed().length < 1
                || catalog.compressed().length > MAX_CATALOG_COMPRESSED_BYTES)
            throw new IOException("invalid catalog frame");
        return ByteBuffer.allocate(Math.addExact(45, catalog.compressed().length)).order(ByteOrder.LITTLE_ENDIAN)
                .put((byte) S_CATALOG).putInt(40 + catalog.compressed().length).put(catalog.fingerprint().bytes())
                .putInt(catalog.canonicalLength()).putInt(catalog.compressed().length).put(catalog.compressed()).array();
    }

    private static byte[] control(int kind, byte[] payload) {
        ByteArrayOutputStream output = new ByteArrayOutputStream(payload.length + 5);
        output.write(kind); putInt(output, payload.length); output.writeBytes(payload);
        return output.toByteArray();
    }

    private static String readString(ByteBuffer input, int maximum) throws IOException {
        int length = Short.toUnsignedInt(input.getShort());
        if (length < 1 || length > maximum || input.remaining() < length) {
            throw new IOException("invalid regional string length");
        }
        ByteBuffer bytes = input.slice(input.position(), length);
        input.position(input.position() + length);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(bytes).toString();
        } catch (CharacterCodingException failure) {
            throw new IOException("invalid regional UTF-8", failure);
        }
    }

    private static void putString(ByteArrayOutputStream output, String value, int maximum)
            throws IOException {
        byte[] bytes = Objects.requireNonNull(value, "value").getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 1 || bytes.length > maximum || bytes.length > 0xffff) {
            throw new IOException("invalid regional string length");
        }
        putShort(output, bytes.length); output.writeBytes(bytes);
    }

    private static byte[] readExact(InputStream input, int length) throws IOException {
        byte[] bytes = new byte[length]; int offset = 0;
        while (offset < length) {
            int read = input.read(bytes, offset, length - offset);
            if (read < 0) throw new EOFException("truncated regional stream");
            if (read != 0) offset += read;
        }
        return bytes;
    }

    private static long readU32(InputStream input) throws IOException {
        int b0 = input.read(), b1 = input.read(), b2 = input.read(), b3 = input.read();
        if ((b0 | b1 | b2 | b3) < 0) throw new EOFException("truncated regional integer");
        return Integer.toUnsignedLong(b0 | b1 << 8 | b2 << 16 | b3 << 24);
    }

    private static void putShort(ByteArrayOutputStream output, int value) {
        output.write(value); output.write(value >>> 8);
    }
    private static void putInt(ByteArrayOutputStream output, int value) {
        for (int shift = 0; shift < 32; shift += 8) output.write(value >>> shift);
    }
    private static void putLong(ByteArrayOutputStream output, long value) {
        for (int shift = 0; shift < 64; shift += 8) output.write((int) (value >>> shift));
    }
}
