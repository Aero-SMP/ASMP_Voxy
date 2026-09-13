package me.cortex.voxy.client.lod;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.zip.CRC32C;

/** Recovered directory for one retained region, independent of its open writer handle.
 * Footer-committed payloads and predecessor bindings; no per-append fsync guarantee.
 * Every reader owns its channel so interruption cannot close another reader's channel.
 */
final class CompletedSectionJournal implements AutoCloseable {
    static final long MAX_BYTES = 256L * 1024 * 1024;
    static final int HEADER_BYTES = 64, FRAME_BYTES = 16, FOOTER_BYTES = 8;
    static final int BINDING_BYTES = 96, PAYLOAD_METADATA_BYTES = 76;
    private static final long MAGIC = 0x314d414e595856L; // VXYNAM1
    private static final int FRAME_MAGIC = 0x434f4c56, PAYLOAD = 1, BINDING = 2, RESET = 3;
    private final Path path;
    private final long region;
    private final Map<Long, Binding> bindings = new HashMap<>();
    private final Map<Token, Payload> payloads = new HashMap<>();
    private FileChannel writer;
    private long end;
    private boolean closed, appending;
    private int readers;
    record Token(RegionalProtocol.Hash32 catalog, RegionalProtocol.Fingerprint source) {}
    record Payload(long offset, int compressed, int canonical, int crc, RegionalProtocol.Fingerprint local) {}
    record Binding(LocalSection section, long offset, long previous, long payload) {}
    /** Charge BEFORE writing, reconcile aborted tails including failed truncation. */
    interface Space { void reserve(long bytes) throws IOException; void resized(long delta); }
    interface Source extends AutoCloseable {
        boolean step(OutputStream sink) throws IOException;
        long canonicalBytes();
        @Override void close() throws IOException;
    }
    static final class RotationRequired extends IOException {
        RotationRequired() { super("local journal rotation required"); }
    }

    static CompletedSectionJournal open(Path path, RegionalProtocol.Hash32 world, long region, boolean writable) throws IOException {
        LocalCacheOwnership.rejectLinks(path);
        var channel = FileChannel.open(path, writable
                ? Set.of(StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
                : Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
        try {
            if (channel.size() == 0 && writable) {
                var header = buffer(HEADER_BYTES).putLong(MAGIC).put(world.bytes()).putLong(region).putLong(0);
                header.putInt(RegionalProtocol.crc32c(Arrays.copyOf(header.array(), 56))).putInt(0).flip();
                write(channel, 0, header);
            }
            if (channel.size() < HEADER_BYTES || channel.size() > MAX_BYTES) throw new IOException("invalid local journal extent");
            var header = read(channel, 0, HEADER_BYTES);
            if (header.getLong() != MAGIC || !RegionalProtocol.Hash32.read(header).equals(world)
                    || header.getLong() != region || header.getLong() != 0
                    || header.getInt() != RegionalProtocol.crc32c(Arrays.copyOf(header.array(), 56))
                    || header.getInt() != 0) throw new IOException("invalid local journal identity");
            var journal = new CompletedSectionJournal(path, region);
            journal.recover(channel);
            if (writable) { channel.truncate(journal.end); journal.writer = channel; }
            else channel.close();
            return journal;
        } catch (Throwable failure) {
            try { channel.close(); } catch (IOException close) { failure.addSuppressed(close); }
            throw failure;
        }
    }
    private CompletedSectionJournal(Path path, long region) { this.path = path; this.region = region; }

    private void recover(FileChannel file) throws IOException {
        this.end = HEADER_BYTES;
        long extent = file.size();
        while (this.end + FRAME_BYTES + FOOTER_BYTES <= extent) {
            long at = this.end;
            var frame = read(file, at, FRAME_BYTES);
            if (frame.getInt() != FRAME_MAGIC) break;
            int kind = frame.getInt(), length = frame.getInt(), crc = frame.getInt();
            int size = kind == PAYLOAD ? PAYLOAD_METADATA_BYTES : kind == BINDING ? BINDING_BYTES
                    : kind == RESET ? 8 : -1;
            if (size < 0 || length < size || length > LocalSectionCodec.MAX_COMPRESSED_BYTES + PAYLOAD_METADATA_BYTES
                    || kind != PAYLOAD && length != size
                    || at + FRAME_BYTES + (long) length + FOOTER_BYTES > extent) break;
            var metadata = read(file, at + FRAME_BYTES, size);
            if (RegionalProtocol.crc32c(metadata.array()) != crc
                    || read(file, at + FRAME_BYTES + length, FOOTER_BYTES).getLong() != commit(kind, length, crc)) break;
            try {
                if (kind == PAYLOAD) {
                    var token = new Token(RegionalProtocol.Hash32.read(metadata), RegionalProtocol.Fingerprint.read(metadata));
                    if (token.catalog().equals(RegionalProtocol.Hash32.ZERO) || token.source().isZero()) break;
                        int compressed = metadata.getInt(), canonical = metadata.getInt(), bodyCrc = metadata.getInt();
                        var hash = RegionalProtocol.Fingerprint.read(metadata);
                        if (canonical < LocalSectionCodec.HEADER_BYTES || canonical > LocalSectionCodec.MAX_CANONICAL_BYTES
                                || compressed < 1 || compressed > LocalSectionCodec.compressedBound(canonical)
                                || length != PAYLOAD_METADATA_BYTES + compressed || hash.isZero()) break;
                        this.payloads.put(token, new Payload(at + FRAME_BYTES + PAYLOAD_METADATA_BYTES,
                                compressed, canonical, bodyCrc, hash));
                } else if (kind == RESET) {
                    if (metadata.getLong() != 0) break;
                    this.bindings.clear();
                } else {
                    var binding = binding(metadata, at);
                    var previous = this.bindings.get(binding.section().key());
                    if (binding.section().region() != this.region || binding.previous() != (previous == null ? 0 : previous.offset())) break;
                    var payload = this.payloads.get(token(binding.section()));
                    if (binding.section().kind() == LocalSection.DATA
                            ? payload == null || payload.offset() != binding.payload() : binding.payload() != 0) break;
                    this.bindings.put(binding.section().key(), binding);
                }
            } catch (IllegalArgumentException | IOException corrupt) { break; }
            this.end += FRAME_BYTES + (long) length + FOOTER_BYTES;
        }
    }
    synchronized boolean hasBindings() { return !this.bindings.isEmpty(); }
    synchronized boolean closed() { return this.closed; }
    synchronized Map<Long, LocalSection> directory() throws IOException {
        checkOpen();
        var result = new HashMap<Long, LocalSection>(this.bindings.size());
        this.bindings.forEach((key, value) -> result.put(key, value.section()));
        return result;
    }
    synchronized long bytes() { return this.end; }
    synchronized int payloadCount() { return this.payloads.size(); }
    synchronized boolean busy() { return this.appending || this.readers != 0; }
    synchronized boolean closeHandle() throws IOException {
        if (this.appending) return false;
        if (this.writer != null) { this.writer.close(); this.writer = null; }
        return true;
    }
    LocalSection previous(LocalSection invalid) throws IOException {
        Binding value;
        synchronized (this) { checkOpen(); value = this.bindings.get(invalid.key()); this.readers++; }
        try (var file = FileChannel.open(this.path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            while (value != null) {
                var section = value.section();
                if (section.kind() == LocalSection.ABSENT) return null;
                synchronized (this) {
                    if (!section.equals(invalid) && !token(section).equals(token(invalid))
                            && (section.kind() != LocalSection.DATA || this.payloads.containsKey(token(section)))) return section;
                }
                if (value.previous() == 0) break;
                if (value.previous() >= value.offset()) throw new IOException("cyclic local predecessor");
                value = readBinding(file, value.previous());
            }
            return null;
        } finally { synchronized (this) { this.readers--; } }
    }
    RegionalSectionCodec.SectionData get(LocalSection section, LocalSectionCodec codec, LocalSectionCodec.Names names) throws IOException {
        Payload payload;
        synchronized (this) {
            checkOpen(); payload = this.payloads.get(token(section));
            if (payload == null) return null;
            this.readers++;
        }
        try (var file = FileChannel.open(this.path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            var crc = new CRC32C(); var hash = new Blake3.Hasher();
            var input = new InputStream() {
                long consumed;
                @Override public int read() throws IOException { throw new IOException("buffered local read required"); }
                @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                    if (length == 0) return 0;
                    if (this.consumed == payload.compressed()) return -1;
                    int count = file.read(ByteBuffer.wrap(bytes, offset, (int) Math.min(length, payload.compressed() - this.consumed)),
                            payload.offset() + this.consumed);
                    if (count <= 0) throw new IOException("truncated local payload");
                    this.consumed += count; crc.update(bytes, offset, count); hash.update(bytes, offset, count);
                    return count;
                }
            };
            var decoded = codec.decode(section.key(), section.children(), input, payload.compressed(), payload.canonical(), names);
            if ((int) crc.getValue() != payload.crc() || !fingerprint(hash).equals(payload.local()))
                throw new IOException("local payload integrity mismatch");
            return decoded; // Journal integrity AND format validation before publication.
        } catch (IOException invalid) {
            synchronized (this) { this.payloads.remove(token(section), payload); }
            throw invalid; // A late reader cannot quarantine a concurrently repaired payload.
        } finally { synchronized (this) { this.readers--; } }
    }
    synchronized Append begin(LocalSection section, Source encoder, Space space,
                              BooleanSupplier current) throws IOException {
        checkOpen();
        if (this.appending) throw new IOException("local append already owned");
        if (section != null && section.region() != this.region) throw new IOException("section outside local region");
        if (this.writer == null) {
            this.writer = FileChannel.open(this.path, StandardOpenOption.READ, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            long before = this.writer.size();
            this.writer.truncate(this.end);
            space.resized(this.writer.size() - before);
        }
        this.appending = true;
        return new Append(section, encoder, space, current);
    }
    /** One append can remain suspended between steps; no monitor is held during encoding or I/O. */
    final class Append extends OutputStream {
        private final LocalSection section;
        private final Source encoder;
        private final Space space;
        private final BooleanSupplier current;
        private final long start = end;
        private final CRC32C crc = new CRC32C();
        private final Blake3.Hasher hash = new Blake3.Hasher();
        private long charged, output;
        private boolean initialized, committed, released;
        private Payload payload;
        private Append(LocalSection section, Source encoder, Space space, BooleanSupplier current) {
            this.section = section; this.encoder = encoder; this.space = space; this.current = current;
            this.payload = section == null ? null : payloads.get(token(section));
        }
        boolean step() throws IOException {
            if (this.released) throw new IOException("released local append");
            if (this.committed) return true;
            if (!this.current.getAsBoolean()) throw new IOException("obsolete local append");
            if (!this.initialized) {
                this.initialized = true;
                if (this.section != null) synchronized (CompletedSectionJournal.this) {
                    var old = bindings.get(this.section.key());
                    if (old != null && old.section().equals(this.section)
                            && (this.section.kind() != LocalSection.DATA || this.payload != null)) { this.committed = true; return true; }
                }
                if (this.section != null && this.section.kind() == LocalSection.DATA && this.payload == null) {
                    if (this.encoder == null) throw new IOException("missing conversion input");
                    reserve(FRAME_BYTES + PAYLOAD_METADATA_BYTES);
                    CompletedSectionJournal.write(writer, this.start, buffer(FRAME_BYTES + PAYLOAD_METADATA_BYTES));
                }
            }
            if (this.section != null && this.section.kind() == LocalSection.DATA && this.payload == null) {
                if (!this.encoder.step(this)) return false;
                var local = fingerprint(this.hash);
                this.payload = new Payload(this.start + FRAME_BYTES + PAYLOAD_METADATA_BYTES,
                        Math.toIntExact(this.output), Math.toIntExact(this.encoder.canonicalBytes()), (int) this.crc.getValue(), local);
                var token = token(this.section);
                byte[] metadata = buffer(PAYLOAD_METADATA_BYTES).put(token.catalog().bytes()).put(token.source().bytes())
                        .putInt(this.payload.compressed()).putInt(this.payload.canonical()).putInt(this.payload.crc()).put(local.bytes()).array();
                reserve(FOOTER_BYTES);
                finishFrame(this.start, PAYLOAD, metadata, this.payload.compressed());
            }
            if (!this.current.getAsBoolean()) throw new IOException("obsolete local commit");
            long at = this.start + this.charged;
            if (this.section == null) {
                reserve(FRAME_BYTES + 8 + FOOTER_BYTES);
                finishFrame(at, RESET, new byte[8], 0);
            } else {
                long previous;
                synchronized (CompletedSectionJournal.this) { var old = bindings.get(this.section.key()); previous = old == null ? 0 : old.offset(); }
                var s = this.section;
                byte[] metadata = buffer(BINDING_BYTES).putLong(s.key()).putInt(s.kind()).putInt(s.children())
                        .putInt(s.compressedBytes()).putInt(s.canonicalBytes()).putInt(s.crc()).putInt(0)
                        .put(s.fingerprint().bytes()).put(s.catalog().bytes()).putLong(previous)
                        .putLong(s.kind() == LocalSection.DATA ? this.payload.offset() : 0).array();
                reserve(FRAME_BYTES + BINDING_BYTES + FOOTER_BYTES);
                finishFrame(at, BINDING, metadata, 0);
                synchronized (CompletedSectionJournal.this) {
                    if (s.kind() == LocalSection.DATA) payloads.put(token(s), this.payload);
                    bindings.put(s.key(), binding(buffer(metadata), at));
                }
            }
            synchronized (CompletedSectionJournal.this) {
                if (this.section == null) bindings.clear();
                end = this.start + this.charged;
            }
            this.committed = true;
            return true;
        }
        private void reserve(long bytes) throws IOException {
            if (this.start + this.charged + bytes > MAX_BYTES) throw new RotationRequired();
            this.space.reserve(bytes); this.charged += bytes;
        }
        @Override public void write(int value) throws IOException { throw new IOException("buffered local write required"); }
        @Override public void write(byte[] bytes, int offset, int count) throws IOException {
            if (!this.current.getAsBoolean()) throw new IOException("obsolete local output");
            if (this.output + count > LocalSectionCodec.MAX_COMPRESSED_BYTES) throw new IOException("local output exceeds bound");
            reserve(count);
            CompletedSectionJournal.write(writer, this.start + FRAME_BYTES + PAYLOAD_METADATA_BYTES + this.output, ByteBuffer.wrap(bytes, offset, count));
            this.output += count; this.crc.update(bytes, offset, count); this.hash.update(bytes, offset, count);
        }
        @Override public void close() throws IOException {
            if (this.released) return;
            this.released = true;
            try {
                if (!this.committed) {
                    try { writer.truncate(this.start); }
                    catch (IOException failure) {
                        synchronized (CompletedSectionJournal.this) { closed = true; }
                        throw failure;
                    }
                    finally {
                        // An interrupt can close this writer's channel. Charge the surviving
                        // tail until the next owner recovers/truncates it, never another append.
                        long actual = java.nio.file.Files.size(path);
                        this.space.resized(actual - this.start - this.charged);
                    }
                }
            } finally {
                synchronized (CompletedSectionJournal.this) { appending = false; closeHandle(); }
            }
        }
    }
    private void finishFrame(long at, int kind, byte[] metadata, int payloadBytes) throws IOException {
        int length = metadata.length + payloadBytes, crc = RegionalProtocol.crc32c(metadata);
        write(this.writer, at + FRAME_BYTES, ByteBuffer.wrap(metadata));
        write(this.writer, at, buffer(FRAME_BYTES).putInt(FRAME_MAGIC).putInt(kind).putInt(length).putInt(crc).flip());
        write(this.writer, at + FRAME_BYTES + length, buffer(FOOTER_BYTES).putLong(commit(kind, length, crc)).flip());
    }
    private static Binding readBinding(FileChannel file, long at) throws IOException {
        if (at < HEADER_BYTES || at + FRAME_BYTES + BINDING_BYTES + FOOTER_BYTES > file.size()) throw new IOException("invalid binding offset");
        var frame = read(file, at, FRAME_BYTES);
        if (frame.getInt() != FRAME_MAGIC || frame.getInt() != BINDING || frame.getInt() != BINDING_BYTES) throw new IOException("invalid binding frame");
        int crc = frame.getInt(); var metadata = read(file, at + FRAME_BYTES, BINDING_BYTES);
        if (RegionalProtocol.crc32c(metadata.array()) != crc
                || read(file, at + FRAME_BYTES + BINDING_BYTES, FOOTER_BYTES).getLong() != commit(BINDING, BINDING_BYTES, crc))
            throw new IOException("corrupt binding");
        return binding(metadata, at);
    }
    private static Binding binding(ByteBuffer bytes, long at) throws IOException {
        long key = bytes.getLong(); int kind = bytes.getInt(), children = bytes.getInt();
        int compressed = bytes.getInt(), canonical = bytes.getInt(), crc = bytes.getInt();
        if (bytes.getInt() != 0) throw new IOException("nonzero binding reserved field");
        var section = new LocalSection(key, kind, children, compressed, canonical, crc,
                RegionalProtocol.Fingerprint.read(bytes), RegionalProtocol.Hash32.read(bytes));
        return new Binding(section, at, bytes.getLong(), bytes.getLong());
    }
    private static Token token(LocalSection section) { return new Token(section.catalog(), section.fingerprint()); }
    private static RegionalProtocol.Fingerprint fingerprint(Blake3.Hasher hash) { return RegionalProtocol.Fingerprint.read(buffer(hash.digest())); }
    private static long commit(int kind, int length, int crc) { return MAGIC ^ Integer.toUnsignedLong(crc) ^ (long) length << 32 ^ kind; }
    private static ByteBuffer buffer(int bytes) { return ByteBuffer.allocate(bytes).order(ByteOrder.LITTLE_ENDIAN); }
    private static ByteBuffer buffer(byte[] bytes) { return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN); }
    private static ByteBuffer read(FileChannel file, long at, int bytes) throws IOException {
        var result = buffer(bytes);
        while (result.hasRemaining()) { int n = file.read(result, at); if (n <= 0) throw new IOException("truncated journal"); at += n; }
        return result.flip();
    }
    private static void write(FileChannel file, long at, ByteBuffer bytes) throws IOException {
        while (bytes.hasRemaining()) { int n = file.write(bytes, at); if (n <= 0) throw new IOException("short journal write"); at += n; }
    }
    private void checkOpen() throws IOException { if (this.closed) throw new IOException("closed local journal"); }
    @Override public synchronized void close() throws IOException {
        if (busy()) throw new IOException("local journal still leased");
        closeHandle(); this.closed = true;
    }
}
