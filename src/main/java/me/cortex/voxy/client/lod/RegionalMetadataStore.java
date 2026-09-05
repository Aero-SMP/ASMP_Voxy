package me.cortex.voxy.client.lod;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.HexFormat;
import java.util.Set;
import java.util.function.BooleanSupplier;

/** Saved wire inputs only. No transient renderer IDs, meshes, subscriptions or worker state. */
final class RegionalMetadataStore implements AutoCloseable {
    private static final long MAGIC = 0x3154524154535856L; // VXSTART1, little endian
    private static final int HEADER = 24, VERSION = 1, ASSOCIATION = 1, CATALOG = 2, REGION = 3;
    private static final int REGION_FIXED = 100;
    final RegionalDiskBudget budget;
    private boolean closed;

    RegionalMetadataStore(Path root) throws IOException { this.budget = RegionalDiskBudget.acquire(root); }
    RegionalMetadataStore(RegionalDiskBudget budget) { this.budget = budget; budget.retain(); }

    @Override public void close() { synchronized (this.budget) {
        if (this.closed) return;
        this.closed = true;
        this.budget.release();
    } }

    RegionalDiskBudget.Pin pinRegion(RegionalProtocol.Hash32 world, String dimension, int x, int z) {
        return this.budget.pin(descriptor(world, dimension, x, z));
    }

    Path namespace(RegionalProtocol.Hash32 world, String dimension) {
        return this.budget.root.resolve(hex(world)).resolve(identifier(dimension));
    }
    Path descriptor(RegionalProtocol.Hash32 world, String dimension, int x, int z) {
        return namespace(world, dimension).resolve("r." + x + '.' + z + ".vxmeta");
    }
    private Path association(String server, String dimension) {
        return this.budget.root.resolve("servers").resolve(identifier(server + '\0' + dimension) + ".vxlink");
    }
    private Path catalogPath(RegionalProtocol.Hash32 world, String dimension, RegionalProtocol.Hash32 hash) {
        return namespace(world, dimension).resolve(hex(hash) + ".vxcat");
    }

    RegionalProtocol.Hash32 world(String server, String dimension) throws IOException {
        if (server == null) return null;
        byte[] bytes = read(association(server, dimension), ASSOCIATION, 32);
        if (bytes == null || bytes.length != 32) return null;
        return RegionalProtocol.Hash32.read(buffer(bytes));
    }

    void associate(String server, String dimension, RegionalProtocol.Hash32 world,
                   long stamp, BooleanSupplier current) throws IOException {
        if (server != null) write(association(server, dimension), ASSOCIATION, world.bytes(),
                stamp, current, Set.of());
    }

    byte[] readCatalog(RegionalProtocol.Hash32 world, String dimension,
                       RegionalProtocol.Hash32 hash) throws IOException {
        byte[] bytes = read(catalogPath(world, dimension, hash), CATALOG, RegionalProtocol.MAX_CATALOG_BYTES);
        if (bytes == null || !hash(bytes).equals(hash)) return null;
        return bytes;
    }

    void saveCatalog(RegionalProtocol.Hash32 world, String dimension,
                     RegionalProtocol.CatalogMessage message, long stamp,
                     BooleanSupplier current) throws IOException {
        if (!this.budget.writable()) return;
        if (!hash(message.canonical()).equals(message.fingerprint())) throw new IOException("catalog hash mismatch");
        Path path = catalogPath(world, dimension, message.fingerprint());
        write(path, CATALOG, message.canonical(), stamp, current, Set.of(path));
    }

    record SavedRegion(RegionalProtocol.RegionMessage message, boolean absent) {}

    SavedRegion region(RegionalProtocol.Hash32 world, String dimension, int x, int z) throws IOException {
        byte[] bytes = read(descriptor(world, dimension, x, z), REGION,
                REGION_FIXED + RegionalProtocol.MAX_INDEX_BYTES);
        if (bytes == null || bytes.length < REGION_FIXED) return null;
        ByteBuffer input = buffer(bytes);
        if (input.getInt() != x || input.getInt() != z || !RegionalProtocol.Hash32.read(input).equals(world)) return null;
        long generation = input.getLong();
        var fingerprint = RegionalProtocol.Fingerprint.read(input);
        var catalog = RegionalProtocol.Hash32.read(input);
        int length = input.getInt();
        if (length < 0 || input.remaining() != length) return null;
        if (generation == 0) return length == 0 && fingerprint.isZero() && catalog.equals(RegionalProtocol.Hash32.ZERO)
                ? new SavedRegion(null, true) : null;
        if (length == 0 || fingerprint.isZero() || catalog.equals(RegionalProtocol.Hash32.ZERO)) return null;
        byte[] compressed = new byte[length]; input.get(compressed);
        return new SavedRegion(new RegionalProtocol.RegionMessage(x, z, generation, fingerprint, catalog, compressed), false);
    }

    void saveRegion(RegionalProtocol.Hash32 world, String dimension, int x, int z,
                    RegionalProtocol.RegionMessage message, long stamp,
                    BooleanSupplier current) throws IOException {
        if (!this.budget.writable()) return;
        Path target = descriptor(world, dimension, x, z);
        if (message != null && (message.regionX() != x || message.regionZ() != z
                || message.generation() == 0 || message.fingerprint().isZero()
                || message.catalogFingerprint().equals(RegionalProtocol.Hash32.ZERO))) {
            throw new IOException("invalid saved region identity");
        }
        Path catalog = message == null ? null : catalogPath(world, dimension, message.catalogFingerprint());
        byte[] compressed = message == null ? new byte[0] : message.compressed();
        if (compressed.length > RegionalProtocol.MAX_INDEX_BYTES) throw new IOException("index exceeds bounds");
        ByteBuffer body = buffer(new byte[REGION_FIXED + compressed.length]);
        body.putInt(x).putInt(z).put(world.bytes()).putLong(message == null ? 0 : message.generation());
        body.put(message == null ? new byte[16] : message.fingerprint().bytes());
        body.put(message == null ? new byte[32] : message.catalogFingerprint().bytes());
        body.putInt(compressed.length).put(compressed);
        synchronized (this.budget) {
            if (catalog != null && !Files.isRegularFile(catalog)) return;
            // A deletion must replace a still-existing old descriptor even if unrelated cache
            // pressure advanced the stamp. It must not recreate an already evicted descriptor.
            long writeStamp = message == null && Files.isRegularFile(target) ? this.budget.stamp() : stamp;
            if (write(target, REGION, body.array(), writeStamp, current,
                    catalog == null ? Set.of(target) : Set.of(target, catalog))) {
                this.budget.reference(target, catalog);
            }
        }
    }

    private boolean write(Path target, int kind, byte[] body, long stamp,
                          BooleanSupplier current, Set<Path> protectedPaths) throws IOException {
        synchronized (this.budget) {
            if (this.closed || !this.budget.writable() || !current.getAsBoolean() || stamp != this.budget.eviction) return false;
            if (!this.budget.ensure(HEADER + (long) body.length, protectedPaths)) return false;
            Files.createDirectories(target.getParent());
            Path temporary = target.resolveSibling(target.getFileName() + ".pending");
            if (Files.exists(temporary) && !Files.isRegularFile(temporary))
                throw new IOException("cache temporary is not a regular file");
            if (Files.exists(temporary) && !this.budget.delete(temporary)) return false;
            long old = RegionalDiskBudget.size(target);
            long reserved = HEADER + (long) body.length;
            this.budget.bytes += reserved;
            boolean installed = false;
            try {
                ByteBuffer header = buffer(new byte[HEADER]);
                header.putLong(MAGIC).putInt(VERSION).putInt(kind).putInt(body.length)
                        .putInt(RegionalProtocol.crc32c(body)).flip();
                try (FileChannel out = FileChannel.open(temporary, StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                    while (header.hasRemaining()) out.write(header);
                    ByteBuffer data = ByteBuffer.wrap(body);
                    while (data.hasRemaining()) out.write(data);
                    out.force(true);
                }
                if (!current.getAsBoolean()) return false;
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                this.budget.bytes -= old;
                installed = true;
                return true;
            } finally {
                if (!installed) {
                    long actual = RegionalDiskBudget.size(temporary);
                    this.budget.bytes -= reserved - actual;
                    this.budget.delete(temporary); // A busy temporary remains charged, not forgotten.
                }
            }
        }
    }

    private byte[] read(Path path, int kind, int maximum) throws IOException {
        try (var pin = this.budget.pin(path)) {
        if (!Files.isRegularFile(path)) return null;
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            if (channel.size() < HEADER || channel.size() > HEADER + (long) maximum) return null;
            ByteBuffer header = buffer(new byte[HEADER]);
            readFully(channel, header); header.flip();
            if (header.getLong() != MAGIC || header.getInt() != VERSION || header.getInt() != kind) return null;
            int length = header.getInt(), crc = header.getInt();
            if (length < 0 || length > maximum || channel.size() != HEADER + (long) length) return null;
            byte[] body = new byte[length]; readFully(channel, ByteBuffer.wrap(body));
            return RegionalProtocol.crc32c(body) == crc ? body : null;
        }
        }
    }

    // Startup catalog-reference accounting reads fixed prefixes only, never eager region indexes.
    static Path referencedCatalog(Path descriptor) throws IOException {
        try (FileChannel channel = FileChannel.open(descriptor, StandardOpenOption.READ)) {
            if (channel.size() < HEADER + REGION_FIXED) return null;
            ByteBuffer prefix = buffer(new byte[HEADER + REGION_FIXED]);
            readFully(channel, prefix); prefix.flip();
            if (prefix.getLong() != MAGIC || prefix.getInt() != VERSION || prefix.getInt() != REGION) return null;
            int length = prefix.getInt();
            if (length < REGION_FIXED || length > REGION_FIXED + RegionalProtocol.MAX_INDEX_BYTES
                    || channel.size() != HEADER + (long) length) return null;
            prefix.position(HEADER + 64);
            var hash = RegionalProtocol.Hash32.read(prefix);
            return hash.equals(RegionalProtocol.Hash32.ZERO) ? null
                    : descriptor.resolveSibling(hex(hash) + ".vxcat");
        } catch (RuntimeException invalid) { return null; }
    }

    private static void readFully(FileChannel channel, ByteBuffer bytes) throws IOException {
        while (bytes.hasRemaining()) if (channel.read(bytes) < 0) throw new IOException("truncated startup metadata");
    }
    static RegionalProtocol.Hash32 hash(byte[] bytes) {
        return RegionalProtocol.Hash32.read(buffer(new Blake3.Hasher().update(bytes).digest()));
    }
    static String identifier(String value) { return hex(hash(value.getBytes(StandardCharsets.UTF_8))); }
    private static String hex(RegionalProtocol.Hash32 hash) { return HexFormat.of().formatHex(hash.bytes()); }
    private static ByteBuffer buffer(byte[] bytes) { return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN); }
}
