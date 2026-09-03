package me.cortex.voxy.client.lod;

import me.cortex.voxy.client.core.rendering.SectionKey;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.zip.CRC32C;

/** The sole current client/server protocol: spatial regional indexes and complete sections. */
final class RegionalProtocol {
    static final int STREAM_CONTROL = 0;
    static final int STREAM_SECTION_LANE = 1;
    static final int MAX_DIMENSION_BYTES = 1024;
    static final int MAX_CONTROL_BYTES = 64 * 1024 * 1024;
    static final int MAX_INDEX_BYTES = 4 * 1024 * 1024;
    static final int MAX_CATALOG_BYTES = 64 * 1024 * 1024;
    static final int MAX_SECTION_REQUESTS = 512;
    static final int MAX_SECTION_BYTES = 4 * 1024 * 1024;
    static final int MAX_SECTION_BATCH_BYTES = 64 * 1024 * 1024;
    static final int SECTION_FLAG_EMPTY = 1;
    static final int SECTION_FLAG_PRESENT = 1 << 15;

    static final int C_HELLO = 0x01;
    static final int C_REGION_REQUEST = 0x02;
    static final int C_CATALOG_REQUEST = 0x03;
    static final int C_REGION_RELEASE = 0x04;
    static final int S_HELLO = 0x81;
    static final int S_REGION = 0x82;
    static final int S_CATALOG = 0x83;
    static final int S_REGION_CHANGED = 0x84;
    static final int S_ERROR = 0xfe;
    static final int S_SHUTDOWN = 0xff;

    private static final int INDEX_HEADER_BYTES = 36;
    private static final int INDEX_ENTRY_BYTES = 48;
    private static final byte[] INDEX_MAGIC = "VXYRIDX\0".getBytes(StandardCharsets.US_ASCII);

    private RegionalProtocol() {}

    enum Lane {
        COVERAGE(0), REFINEMENT(1);
        final int id;
        Lane(int id) { this.id = id; }
    }

    enum Status {
        STALE, ABSENT, EMPTY, DATA;

        static Status from(int value) throws IOException {
            if (value < 0 || value >= values().length) {
                throw new IOException("invalid regional section status");
            }
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
    }

    static final class RegionIndex {
        private final int regionX;
        private final int regionZ;
        private final long generation;
        private final Fingerprint fingerprint;
        private final int minBaseY;
        private final int baseYCount;
        private final int levels;
        private final int entryCount;
        private final byte[] packed;

        private RegionIndex(int regionX, int regionZ, long generation,
                            Fingerprint fingerprint, int minBaseY, int baseYCount,
                            int levels, int entryCount, byte[] packed) {
            this.regionX = regionX;
            this.regionZ = regionZ;
            this.generation = generation;
            this.fingerprint = fingerprint;
            this.minBaseY = minBaseY;
            this.baseYCount = baseYCount;
            this.levels = levels;
            this.entryCount = entryCount;
            this.packed = packed;
        }

        int regionX() { return this.regionX; }
        int regionZ() { return this.regionZ; }
        long generation() { return this.generation; }
        Fingerprint fingerprint() { return this.fingerprint; }
        int entryCount() { return this.entryCount; }

        int ordinal(long key) {
            int level = SectionKey.level(key);
            if (level < 0 || level >= this.levels) return -1;
            int side = 16 >> level;
            int x = SectionKey.x(key), y = SectionKey.y(key), z = SectionKey.z(key);
            int localX = x - this.regionX * side;
            int localZ = z - this.regionZ * side;
            int minY = Math.floorDiv(this.minBaseY, 1 << level);
            int maxBase = this.minBaseY + this.baseYCount - 1;
            int yCount = Math.floorDiv(maxBase, 1 << level) - minY + 1;
            int localY = y - minY;
            if (localX < 0 || localX >= side || localZ < 0 || localZ >= side
                    || localY < 0 || localY >= yCount) return -1;
            int offset = 0;
            for (int previous = 0; previous < level; previous++) {
                int previousSide = 16 >> previous;
                int previousMinY = Math.floorDiv(this.minBaseY, 1 << previous);
                int previousY = Math.floorDiv(maxBase, 1 << previous) - previousMinY + 1;
                offset += previousSide * previousSide * previousY;
            }
            return offset + (localY * side + localZ) * side + localX;
        }

        long key(int ordinal) throws IOException {
            return coordinateFor(this.regionX, this.regionZ, this.minBaseY,
                    this.baseYCount, this.levels, ordinal);
        }

        boolean isPresent(int ordinal) {
            return (flags(ordinal) & SECTION_FLAG_PRESENT) != 0;
        }
        boolean isEmpty(int ordinal) {
            return (flags(ordinal) & SECTION_FLAG_EMPTY) != 0;
        }
        int childMask(int ordinal) { return Byte.toUnsignedInt(this.packed[offset(ordinal) + 2]); }
        int compressedLength(int ordinal) { return readInt(this.packed, offset(ordinal) + 16); }
        int canonicalLength(int ordinal) { return readInt(this.packed, offset(ordinal) + 20); }
        int compressedCrc(int ordinal) { return readInt(this.packed, offset(ordinal) + 24); }
        Fingerprint sectionFingerprint(int ordinal) {
            int offset = offset(ordinal) + 28;
            return new Fingerprint(readLong(this.packed, offset), readLong(this.packed, offset + 8));
        }

        private int flags(int ordinal) {
            int offset = offset(ordinal);
            return Byte.toUnsignedInt(this.packed[offset])
                    | Byte.toUnsignedInt(this.packed[offset + 1]) << 8;
        }
        private int offset(int ordinal) {
            if (ordinal < 0 || ordinal >= this.entryCount) {
                throw new IndexOutOfBoundsException("regional section ordinal " + ordinal);
            }
            return INDEX_HEADER_BYTES + ordinal * INDEX_ENTRY_BYTES;
        }
    }

    sealed interface Control permits ServerHello, RegionMessage, RegionAbsent,
            CatalogMessage, RegionChanged, ServerError, ServerShutdown {}
    record ServerHello(long serverInstance, Hash32 worldIdentity, long catalogId,
                       Hash32 catalogFingerprint) implements Control {}
    record RegionMessage(int regionX, int regionZ, long generation,
                              Fingerprint fingerprint, Hash32 catalogFingerprint,
                              byte[] compressed) implements Control {}
    record RegionAbsent(int regionX, int regionZ) implements Control {}
    record CatalogMessage(Hash32 fingerprint, byte[] canonical) implements Control {}
    record RegionChanged(int regionX, int regionZ, long generation) implements Control {}
    record ServerError(int code, String message) implements Control {}
    record ServerShutdown(String message) implements Control {}

    record SectionReply(long generation, int ordinal, long key, Status status, byte[] compressed) {
        SectionReply {
            if (generation == 0 || ordinal < 0) {
                throw new IllegalArgumentException("invalid local section reply identity");
            }
            Objects.requireNonNull(status, "status");
            compressed = Objects.requireNonNull(compressed, "compressed");
        }
    }

    static byte[] hello(String dimension) throws IOException {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        putString(payload, dimension, MAX_DIMENSION_BYTES);
        return control(C_HELLO, payload.toByteArray());
    }

    static byte[] regionRequest(int regionX, int regionZ) {
        ByteArrayOutputStream payload = new ByteArrayOutputStream(8);
        putInt(payload, regionX); putInt(payload, regionZ);
        return control(C_REGION_REQUEST, payload.toByteArray());
    }

    static byte[] regionRelease(int regionX, int regionZ) {
        ByteArrayOutputStream payload = new ByteArrayOutputStream(8);
        putInt(payload, regionX); putInt(payload, regionZ);
        return control(C_REGION_RELEASE, payload.toByteArray());
    }

    static byte[] catalogRequest() {
        return control(C_CATALOG_REQUEST, new byte[0]);
    }

    static Control readControl(InputStream input) throws IOException {
        int kind = input.read();
        if (kind < 0) throw new EOFException("regional control stream ended");
        long rawLength = readU32(input);
        if (rawLength > MAX_CONTROL_BYTES) throw new IOException("oversized regional control");
        ByteBuffer payload = ByteBuffer.wrap(readExact(input, (int) rawLength))
                .order(ByteOrder.LITTLE_ENDIAN);
        try {
            Control result = switch (kind) {
                case S_HELLO -> new ServerHello(payload.getLong(), Hash32.read(payload),
                        payload.getLong(), Hash32.read(payload));
                case S_REGION -> decodeRegionMessage(payload);
                case S_CATALOG -> decodeCatalogMessage(payload);
                case S_REGION_CHANGED -> new RegionChanged(payload.getInt(), payload.getInt(),
                        payload.getLong());
                case S_ERROR -> new ServerError(Short.toUnsignedInt(payload.getShort()),
                        readString(payload, 4096));
                case S_SHUTDOWN -> new ServerShutdown(readString(payload, 4096));
                default -> throw new IOException("unknown regional control record " + kind);
            };
            if (payload.hasRemaining()) throw new IOException("trailing regional control bytes");
            validateControl(result);
            return result;
        } catch (java.nio.BufferUnderflowException | IllegalArgumentException failure) {
            throw new IOException("malformed regional control record", failure);
        }
    }

    static byte[] sectionRequest(long epoch, RegionIndex index, List<Integer> ordinals)
            throws IOException {
        if (epoch == 0 || ordinals.isEmpty() || ordinals.size() > MAX_SECTION_REQUESTS) {
            throw new IOException("invalid regional request batch");
        }
        ByteArrayOutputStream payload = new ByteArrayOutputStream(26 + ordinals.size() * 4);
        putLong(payload, epoch); putInt(payload, index.regionX()); putInt(payload, index.regionZ());
        putLong(payload, index.generation()); putShort(payload, ordinals.size());
        HashSet<Integer> unique = new HashSet<>();
        for (int ordinal : ordinals) {
            if (ordinal < 0 || ordinal >= index.entryCount() || !unique.add(ordinal)) {
                throw new IOException("duplicate regional section request");
            }
            putInt(payload, ordinal);
        }
        byte[] body = payload.toByteArray();
        ByteArrayOutputStream frame = new ByteArrayOutputStream(body.length + 4);
        putInt(frame, body.length); frame.writeBytes(body);
        return frame.toByteArray();
    }

    static List<SectionReply> readReplyBatch(InputStream input, long expectedEpoch,
                                              RegionIndex index, List<Integer> ordinals,
                                              int expectedStart) throws IOException {
        long rawLength = readU32(input);
        if (rawLength < 12 || rawLength > MAX_SECTION_BATCH_BYTES) {
            throw new IOException("invalid regional reply frame length");
        }
        ByteBuffer body = ByteBuffer.wrap(readExact(input, (int) rawLength))
                .order(ByteOrder.LITTLE_ENDIAN);
        long epoch = body.getLong();
        int start = Short.toUnsignedInt(body.getShort());
        int count = Short.toUnsignedInt(body.getShort());
        if (epoch != expectedEpoch || start != expectedStart || count < 1
                || count > MAX_SECTION_REQUESTS || start + count > ordinals.size()
                || body.remaining() < count) {
            throw new IOException("invalid regional reply batch header");
        }
        List<Status> statuses = new ArrayList<>(count);
        for (int position = 0; position < count; position++) {
            statuses.add(Status.from(Byte.toUnsignedInt(body.get())));
        }
        List<SectionReply> replies = new ArrayList<>(count);
        for (int position = 0; position < count; position++) {
            int ordinal = ordinals.get(start + position);
            Status status = statuses.get(position);
            int length = status == Status.DATA ? index.compressedLength(ordinal) : 0;
            if (length < 0 || length > MAX_SECTION_BYTES || body.remaining() < length) {
                throw new IOException("truncated or oversized regional section body");
            }
            byte[] compressed = new byte[length];
            body.get(compressed);
            validateReply(index, ordinal, status, compressed);
            SectionReply reply = new SectionReply(index.generation(), ordinal,
                    index.key(ordinal), status, compressed);
            replies.add(reply);
        }
        if (body.hasRemaining()) throw new IOException("trailing regional reply bytes");
        return replies;
    }

    static RegionIndex decodeIndex(byte[] canonical, Fingerprint fingerprint) throws IOException {
        if (fingerprint.isZero()) throw new IOException("zero regional index fingerprint");
        if (canonical.length < INDEX_HEADER_BYTES || canonical.length > MAX_INDEX_BYTES) {
            throw new IOException("invalid regional index length");
        }
        ByteBuffer input = ByteBuffer.wrap(canonical).order(ByteOrder.LITTLE_ENDIAN);
        byte[] magic = new byte[8]; input.get(magic);
        if (!Arrays.equals(magic, INDEX_MAGIC)) throw new IOException("invalid regional index magic");
        int regionX = input.getInt(); int regionZ = input.getInt(); long generation = input.getLong();
        int minBaseY = input.getInt(); int baseYCount = Short.toUnsignedInt(input.getShort());
        int levels = Byte.toUnsignedInt(input.get());
        if (input.get() != 0) throw new IOException("nonzero regional index reserved byte");
        long rawCount = Integer.toUnsignedLong(input.getInt());
        int expectedCount = entryCount(baseYCount, levels, minBaseY);
        if (generation == 0 || rawCount != expectedCount
                || input.remaining() != expectedCount * INDEX_ENTRY_BYTES) {
            throw new IOException("regional index layout disagrees with its entries");
        }
        for (int index = 0; index < expectedCount; index++) {
            int flags = Short.toUnsignedInt(input.getShort());
            int childMask = Byte.toUnsignedInt(input.get());
            for (int reserved = 0; reserved < 5; reserved++) {
                if (input.get() != 0) throw new IOException("nonzero regional index reserved byte");
            }
            long payloadOffset = input.getLong();
            int compressed = input.getInt(); int canonicalLength = input.getInt();
            int compressedCrc = input.getInt();
            Fingerprint entryFingerprint = Fingerprint.read(input);
            if (input.getInt() != 0) throw new IOException("nonzero regional index reserved bytes");
            if ((flags & SECTION_FLAG_PRESENT) == 0) {
                if (flags != 0 || childMask != 0 || compressed != 0
                        || payloadOffset != 0 || canonicalLength != 0 || compressedCrc != 0
                        || !entryFingerprint.isZero()) {
                    throw new IOException("absent regional index entry contains metadata");
                }
                continue;
            }
            if ((flags & ~(SECTION_FLAG_PRESENT | SECTION_FLAG_EMPTY)) != 0
                    || compressed < 0 || compressed > MAX_SECTION_BYTES
                    || canonicalLength < 0 || canonicalLength > MAX_SECTION_BYTES) {
                throw new IOException("invalid regional index entry metadata");
            }
            boolean empty = (flags & SECTION_FLAG_EMPTY) != 0;
            if (empty != (payloadOffset == 0 && compressed == 0 && canonicalLength == 0
                    && compressedCrc == 0 && entryFingerprint.isZero())) {
                throw new IOException("regional empty-section metadata disagrees");
            }
            if (!empty && (payloadOffset <= 0 || compressed == 0 || canonicalLength == 0
                    || entryFingerprint.isZero())) {
                throw new IOException("regional section payload metadata is invalid");
            }
        }
        return new RegionIndex(regionX, regionZ, generation, fingerprint, minBaseY,
                baseYCount, levels, expectedCount, canonical);
    }

    static int crc32c(byte[] bytes) {
        CRC32C crc = new CRC32C(); crc.update(bytes, 0, bytes.length); return (int) crc.getValue();
    }

    private static Control decodeRegionMessage(ByteBuffer input) throws IOException {
        int x = input.getInt(); int z = input.getInt(); long generation = input.getLong();
        if (generation == 0) return new RegionAbsent(x, z);
        Fingerprint fingerprint = Fingerprint.read(input);
        Hash32 catalogFingerprint = Hash32.read(input);
        int length = input.getInt();
        if (length < 0 || length > MAX_INDEX_BYTES || input.remaining() != length) {
            throw new IOException("invalid compressed regional index length");
        }
        byte[] compressed = new byte[length]; input.get(compressed);
        return new RegionMessage(x, z, generation, fingerprint, catalogFingerprint, compressed);
    }

    private static Control decodeCatalogMessage(ByteBuffer input) throws IOException {
        Hash32 fingerprint = Hash32.read(input);
        int length = input.getInt();
        if (length < 0 || length > MAX_CATALOG_BYTES || input.remaining() != length) {
            throw new IOException("invalid regional catalog length");
        }
        byte[] canonical = new byte[length]; input.get(canonical);
        return new CatalogMessage(fingerprint, canonical);
    }

    private static void validateControl(Control control) throws IOException {
        switch (control) {
            case ServerHello hello -> {
                if (hello.serverInstance == 0 || hello.worldIdentity.isZero()
                        || hello.catalogId == 0 || hello.catalogFingerprint.isZero()) {
                    throw new IOException("invalid regional server identity");
                }
            }
            case RegionMessage region -> {
                if (region.generation == 0 || region.fingerprint.isZero()
                        || region.catalogFingerprint.isZero() || region.compressed.length == 0) {
                    throw new IOException("invalid regional response");
                }
            }
            case CatalogMessage catalog -> {
                if (catalog.fingerprint.isZero() || catalog.canonical.length == 0) {
                    throw new IOException("invalid regional catalog response");
                }
            }
            case ServerError error -> {
                if (error.code == 0) throw new IOException("zero regional error code");
            }
            default -> {}
        }
    }

    private static void validateReply(RegionIndex index, int ordinal, Status status,
                                      byte[] compressed) throws IOException {
        boolean noBody = compressed.length == 0;
        switch (status) {
            case DATA -> {
                if (!index.isPresent(ordinal) || index.isEmpty(ordinal) || noBody
                        || compressed.length != index.compressedLength(ordinal)
                        || crc32c(compressed) != index.compressedCrc(ordinal)) {
                    throw new IOException("invalid regional data reply");
                }
            }
            case EMPTY -> {
                if (!noBody || !index.isPresent(ordinal) || !index.isEmpty(ordinal)) {
                    throw new IOException("invalid regional empty reply");
                }
            }
            case STALE, ABSENT -> {
                if (!noBody) {
                    throw new IOException("terminal regional reply contains metadata");
                }
            }
        }
    }

    private static long coordinateFor(int regionX, int regionZ, int minBaseY,
                                      int baseYCount, int levels, int index) throws IOException {
        int remaining = index;
        for (int level = 0; level < levels; level++) {
            int side = 16 >> level;
            int minY = Math.floorDiv(minBaseY, 1 << level);
            int maxBase = Math.addExact(minBaseY, baseYCount - 1);
            int yCount = Math.floorDiv(maxBase, 1 << level) - minY + 1;
            int count = Math.multiplyExact(Math.multiplyExact(side, side), yCount);
            if (remaining >= count) { remaining -= count; continue; }
            int x = remaining % side; remaining /= side;
            int z = remaining % side; int y = remaining / side;
            return SectionKey.pack(level, regionX * side + x, minY + y, regionZ * side + z);
        }
        throw new IOException("regional index coordinate overflow");
    }

    private static int entryCount(int baseYCount, int levels, int minBaseY) throws IOException {
        if (baseYCount < 1 || levels < 1 || levels > 5) throw new IOException("invalid layout");
        int total = 0;
        int maxBase = Math.addExact(minBaseY, baseYCount - 1);
        for (int level = 0; level < levels; level++) {
            int side = 16 >> level;
            int yCount = Math.floorDiv(maxBase, 1 << level)
                    - Math.floorDiv(minBaseY, 1 << level) + 1;
            total = Math.addExact(total, Math.multiplyExact(side * side, yCount));
        }
        return total;
    }


    private static int readInt(byte[] bytes, int offset) {
        return Byte.toUnsignedInt(bytes[offset])
                | Byte.toUnsignedInt(bytes[offset + 1]) << 8
                | Byte.toUnsignedInt(bytes[offset + 2]) << 16
                | bytes[offset + 3] << 24;
    }

    private static long readLong(byte[] bytes, int offset) {
        return Integer.toUnsignedLong(readInt(bytes, offset))
                | (long) readInt(bytes, offset + 4) << 32;
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
