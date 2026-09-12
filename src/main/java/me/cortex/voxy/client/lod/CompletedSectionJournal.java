package me.cortex.voxy.client.lod;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Experimental per-region append journal. Its caller owns disk-budget pins and append exclusion.
 * Recovery reads framing/metadata only, never payloads. Payload integrity is checked on use.
 * A footer commits a record; a binding references an earlier complete payload. No per-section
 * fsync: close/flush is the durability boundary. A crash may lose recent writes. Catalogs must
 * be durably saved before append. A torn suffix cannot replace an earlier binding.
 */
final class CompletedSectionJournal implements AutoCloseable {
    static final long MAX_BYTES = 256L * 1024 * 1024;
    static final int HEADER_BYTES = 64, FRAME_BYTES = 16, FOOTER_BYTES = 8;
    static final int BINDING_BYTES = 96, PAYLOAD_METADATA_BYTES = 24;
    private static final long MAGIC = 0x31434f4c595856L; // VXYLOC1
    private static final int FRAME_MAGIC = 0x434f4c56, PAYLOAD = 1, BINDING = 2, RESET = 3, DROP_PAYLOAD = 4;
    private final RandomAccessFile file;
    private final FileChannel channel;
    private final long region;
    private final boolean writable;
    private final Long2LongOpenHashMap bindings = new Long2LongOpenHashMap();
    private final Map<Blob, Long> payloads = new HashMap<>();
    // Includes recovery predecessors until this bounded shard is evicted, not all global catalogs.
    private final Set<RegionalProtocol.Hash32> catalogs = new HashSet<>();
    private long end;
    private boolean closed;
    private boolean legacySealed;

    record Blob(RegionalProtocol.Fingerprint fingerprint, int length, int crc) {}
    record Binding(LocalSection section, long previous, long payload) {}

    static CompletedSectionJournal open(Path path, RegionalProtocol.Hash32 world, long region,
                                        boolean writable) throws IOException {
        var file = new RandomAccessFile(path.toFile(), writable ? "rw" : "r");
        try {
            if (file.length() == 0 && writable) {
                var header = buffer(HEADER_BYTES);
                header.putLong(MAGIC).put(world.bytes()).putLong(region).putLong(0);
                header.putInt(RegionalProtocol.crc32c(java.util.Arrays.copyOf(header.array(), 56))).putInt(0).flip();
                write(file.getChannel(), 0, header);
            }
            if (file.length() < HEADER_BYTES || file.length() > MAX_BYTES)
                throw new IOException("invalid completed journal extent");
            var header = read(file.getChannel(), 0, HEADER_BYTES);
            if (header.getLong() != MAGIC || !RegionalProtocol.Hash32.read(header).equals(world)
                    || header.getLong() != region || header.getLong() != 0
                    || header.getInt() != RegionalProtocol.crc32c(java.util.Arrays.copyOf(header.array(), 56))
                    || header.getInt() != 0) throw new IOException("invalid completed journal identity");
            var journal = new CompletedSectionJournal(file, region, writable);
            journal.recover();
            return journal;
        } catch (Throwable failure) {
            try { file.close(); } catch (IOException close) { failure.addSuppressed(close); }
            if (failure instanceof IOException io) throw io;
            throw failure;
        }
    }

    private CompletedSectionJournal(RandomAccessFile file, long region, boolean writable) {
        this.file = file;
        this.channel = file.getChannel();
        this.region = region;
        this.writable = writable;
    }

    private void recover() throws IOException {
        long extent = this.file.length();
        this.end = HEADER_BYTES;
        while (this.end + FRAME_BYTES + FOOTER_BYTES <= extent) {
            long at = this.end;
            var frame = read(this.channel, at, FRAME_BYTES);
            if (frame.getInt() != FRAME_MAGIC) break;
            int kind = frame.getInt(), length = frame.getInt(), crc = frame.getInt();
            int metadataBytes = kind == PAYLOAD || kind == DROP_PAYLOAD ? PAYLOAD_METADATA_BYTES
                    : kind == BINDING ? BINDING_BYTES : kind == RESET ? 8 : -1;
            if (metadataBytes < 0 || length < metadataBytes || length > RegionalProtocol.MAX_SECTION_BYTES + BINDING_BYTES
                    || kind == BINDING && length != BINDING_BYTES
                    || kind == RESET && length != 8
                    || kind == DROP_PAYLOAD && length != PAYLOAD_METADATA_BYTES
                    || at + FRAME_BYTES + (long) length + FOOTER_BYTES > extent) break;
            var metadata = read(this.channel, at + FRAME_BYTES, metadataBytes);
            if (RegionalProtocol.crc32c(metadata.array()) != crc
                    || read(this.channel, at + FRAME_BYTES + length, FOOTER_BYTES).getLong()
                    != commit(kind, length, crc)) break;
            try {
                if (kind == PAYLOAD || kind == DROP_PAYLOAD) {
                    var blob = new Blob(RegionalProtocol.Fingerprint.read(metadata), metadata.getInt(), metadata.getInt());
                    if (blob.length() < 1 || blob.length() > RegionalProtocol.MAX_SECTION_BYTES
                            || blob.fingerprint().isZero()
                            || kind == PAYLOAD && length != PAYLOAD_METADATA_BYTES + blob.length()) break;
                    if (kind == DROP_PAYLOAD) this.payloads.remove(blob);
                    else this.payloads.put(blob, at + FRAME_BYTES + PAYLOAD_METADATA_BYTES);
                } else if (kind == RESET) {
                    if (metadata.getLong() != 0) break;
                    this.bindings.clear(); this.catalogs.clear(); this.legacySealed = true;
                } else {
                    Binding binding = binding(metadata);
                    if (binding.section().region() != this.region
                            || binding.previous() != this.bindings.get(binding.section().key())) break;
                    if (binding.section().kind() == LocalSection.DATA
                            && !java.util.Objects.equals(this.payloads.get(blob(binding.section())), binding.payload())) break;
                    if (binding.section().kind() != LocalSection.DATA && binding.payload() != 0) break;
                    this.bindings.put(binding.section().key(), at);
                    if (!binding.section().catalog().equals(RegionalProtocol.Hash32.ZERO))
                        this.catalogs.add(binding.section().catalog());
                }
            } catch (IllegalArgumentException | IOException corrupt) { break; }
            this.end += FRAME_BYTES + (long) length + FOOTER_BYTES;
        }
        if (this.writable && this.end != extent) this.file.setLength(this.end);
    }

    Map<Long, LocalSection> directory() throws IOException {
        checkOpen();
        var directory = new HashMap<Long, LocalSection>(this.bindings.size());
        for (var entry : this.bindings.long2LongEntrySet())
            directory.put(entry.getLongKey(), readBinding(entry.getLongValue()).section());
        return directory;
    }

    Set<RegionalProtocol.Hash32> catalogs() { return Set.copyOf(this.catalogs); }
    long bytes() { return this.end; }
    boolean writable() { return this.writable; }
    boolean legacySealed() { return this.legacySealed; }
    int payloadCount() { return this.payloads.size(); }

    void absentRegion() throws IOException {
        checkOpen();
        if (this.legacySealed && this.bindings.isEmpty()) return;
        if (!this.writable) throw new IOException("read-only completed journal");
        if (this.end + FRAME_BYTES + 8 + FOOTER_BYTES > MAX_BYTES) throw new IOException("completed journal full");
        appendFrame(RESET, new byte[8], null);
        this.bindings.clear(); this.catalogs.clear(); this.legacySealed = true;
    }

    /** Lazy recovery candidate. Never crosses an authoritative absence barrier. */
    LocalSection previous(LocalSection invalid) throws IOException {
        var found = resolve(invalid.key(), candidate -> !candidate.equals(invalid)
                && (candidate.kind() != LocalSection.DATA || !blob(candidate).equals(blob(invalid))));
        return found == null || found.kind() == LocalSection.ABSENT ? null : found;
    }

    LocalSection resolve(long key, java.util.function.Predicate<LocalSection> usable) throws IOException {
        checkOpen();
        long at = this.bindings.get(key);
        while (at != 0) {
            var binding = readBinding(at);
            if (binding.section().kind() == LocalSection.ABSENT) return binding.section();
            if ((binding.section().kind() != LocalSection.DATA || this.payloads.containsKey(blob(binding.section())))
                    && usable.test(binding.section())) return binding.section();
            if (binding.previous() >= at) throw new IOException("cyclic completed binding chain");
            at = binding.previous();
        }
        return null;
    }

    /** Charge this exact worst-case append size before calling append, including dedup races. */
    long appendBytes(LocalSection section) throws IOException {
        checkOpen();
        long previous = this.bindings.get(section.key());
        if (previous != 0 && readBinding(previous).section().equals(section)
                && (section.kind() != LocalSection.DATA || this.payloads.containsKey(blob(section)))) return 0;
        return FRAME_BYTES + BINDING_BYTES + FOOTER_BYTES
                + (section.kind() == LocalSection.DATA && !this.payloads.containsKey(blob(section))
                ? FRAME_BYTES + PAYLOAD_METADATA_BYTES + (long) section.compressedBytes() + FOOTER_BYTES : 0);
    }

    /** Caller validates canonical bytes/catalog and checks immutable task authority before commit. */
    void append(LocalSection section, byte[] compressed) throws IOException {
        checkOpen();
        if (!this.writable) throw new IOException("read-only completed journal");
        if (section.region() != this.region) throw new IOException("section outside journal region");
        long added = appendBytes(section);
        if (added == 0) return;
        if (this.end + added > MAX_BYTES) throw new IOException("completed journal rotation required");
        if (section.kind() == LocalSection.DATA && (compressed == null
                || compressed.length != section.compressedBytes() || RegionalProtocol.crc32c(compressed) != section.crc()))
            throw new IOException("completed payload CRC or extent mismatch");
        long payload = 0;
        if (section.kind() == LocalSection.DATA) {
            Blob blob = blob(section);
            Long existing = this.payloads.get(blob);
            if (existing == null) {
                var metadata = buffer(PAYLOAD_METADATA_BYTES).put(blob.fingerprint().bytes())
                        .putInt(blob.length()).putInt(blob.crc());
                payload = this.end + FRAME_BYTES + PAYLOAD_METADATA_BYTES;
                appendFrame(PAYLOAD, metadata.array(), compressed);
                this.payloads.put(blob, payload);
            } else payload = existing;
        }
        long offset = this.end;
        var metadata = buffer(BINDING_BYTES).putLong(section.key()).putInt(section.kind()).putInt(section.children())
                .putInt(section.compressedBytes()).putInt(section.canonicalBytes()).putInt(section.crc()).putInt(0)
                .put(section.fingerprint().bytes()).put(section.catalog().bytes())
                .putLong(this.bindings.get(section.key())).putLong(payload);
        appendFrame(BINDING, metadata.array(), null);
        this.bindings.put(section.key(), offset);
        if (!section.catalog().equals(RegionalProtocol.Hash32.ZERO)) this.catalogs.add(section.catalog());
    }

    /** Exact content read; old immutable workers can still consume their own committed payload. */
    byte[] get(LocalSection section) throws IOException {
        checkOpen();
        Long offset = this.payloads.get(blob(section));
        if (offset == null) return null;
        byte[] bytes = read(this.channel, offset, section.compressedBytes()).array();
        if (RegionalProtocol.crc32c(bytes) != section.crc()) throw new IOException("completed payload CRC mismatch");
        return bytes;
    }

    void quarantine(LocalSection section, boolean persist) throws IOException {
        checkOpen();
        if (!this.payloads.containsKey(blob(section))) return;
        if (!persist || !this.writable || this.end + FRAME_BYTES + PAYLOAD_METADATA_BYTES + FOOTER_BYTES > MAX_BYTES) {
            this.payloads.remove(blob(section));
            return;
        }
        var metadata = buffer(PAYLOAD_METADATA_BYTES).put(section.fingerprint().bytes())
                .putInt(section.compressedBytes()).putInt(section.crc());
        try { appendFrame(DROP_PAYLOAD, metadata.array(), null); }
        finally { this.payloads.remove(blob(section)); }
    }

    private void appendFrame(int kind, byte[] metadata, byte[] payload) throws IOException {
        int length = metadata.length + (payload == null ? 0 : payload.length);
        int crc = RegionalProtocol.crc32c(metadata);
        long start = this.end;
        try {
            write(this.channel, start, buffer(FRAME_BYTES).putInt(FRAME_MAGIC).putInt(kind).putInt(length).putInt(crc).flip());
            write(this.channel, start + FRAME_BYTES, ByteBuffer.wrap(metadata));
            if (payload != null) write(this.channel, start + FRAME_BYTES + metadata.length, ByteBuffer.wrap(payload));
            write(this.channel, start + FRAME_BYTES + length, buffer(FOOTER_BYTES).putLong(commit(kind, length, crc)).flip());
            this.end += FRAME_BYTES + (long) length + FOOTER_BYTES;
        } catch (IOException failure) {
            try { this.file.setLength(start); } catch (IOException rollback) { failure.addSuppressed(rollback); this.closed = true; }
            throw failure;
        }
    }

    private Binding readBinding(long at) throws IOException {
        if (at < HEADER_BYTES || at + FRAME_BYTES + BINDING_BYTES + FOOTER_BYTES > this.end)
            throw new IOException("invalid completed binding offset");
        var frame = read(this.channel, at, FRAME_BYTES);
        if (frame.getInt() != FRAME_MAGIC || frame.getInt() != BINDING || frame.getInt() != BINDING_BYTES)
            throw new IOException("invalid completed binding frame");
        int crc = frame.getInt();
        var metadata = read(this.channel, at + FRAME_BYTES, BINDING_BYTES);
        if (RegionalProtocol.crc32c(metadata.array()) != crc
                || read(this.channel, at + FRAME_BYTES + BINDING_BYTES, FOOTER_BYTES).getLong()
                    != commit(BINDING, BINDING_BYTES, crc)) throw new IOException("corrupt completed binding");
        return binding(metadata);
    }
    private static Binding binding(ByteBuffer bytes) throws IOException {
        long key = bytes.getLong(); int kind = bytes.getInt(), children = bytes.getInt();
        int compressed = bytes.getInt(), canonical = bytes.getInt(), crc = bytes.getInt();
        if (bytes.getInt() != 0) throw new IOException("nonzero local binding reserved field");
        var section = new LocalSection(key, kind, children, compressed, canonical, crc,
                RegionalProtocol.Fingerprint.read(bytes), RegionalProtocol.Hash32.read(bytes));
        return new Binding(section, bytes.getLong(), bytes.getLong());
    }
    private static Blob blob(LocalSection section) { return new Blob(section.fingerprint(), section.compressedBytes(), section.crc()); }
    private static long commit(int kind, int length, int crc) {
        return MAGIC ^ Integer.toUnsignedLong(crc) ^ (long) length << 32 ^ kind;
    }
    private static ByteBuffer buffer(int bytes) { return ByteBuffer.allocate(bytes).order(ByteOrder.LITTLE_ENDIAN); }
    private static ByteBuffer read(FileChannel file, long offset, int bytes) throws IOException {
        ByteBuffer result = buffer(bytes);
        while (result.hasRemaining()) {
            int count = file.read(result, offset);
            if (count <= 0) throw new IOException("truncated completed journal");
            offset += count;
        }
        return result.flip();
    }
    private static void write(FileChannel file, long offset, ByteBuffer bytes) throws IOException {
        while (bytes.hasRemaining()) {
            int count = file.write(bytes, offset);
            if (count <= 0) throw new IOException("short completed journal write");
            offset += count;
        }
    }
    void flush() throws IOException { checkOpen(); if (this.writable) this.channel.force(true); }
    private void checkOpen() throws IOException { if (this.closed) throw new IOException("completed journal closed"); }
    @Override public void close() throws IOException {
        try { if (!this.closed) flush(); }
        finally { this.closed = true; this.file.close(); }
    }
}
