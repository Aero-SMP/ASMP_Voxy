package me.cortex.voxy.client.lod;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.HexFormat;
import java.util.function.BooleanSupplier;

/** Only the server/dimension-to-world hint is external to self-contained section journals. */
final class RegionalMetadataStore implements AutoCloseable {
    enum Persistence { PERSISTED, DEFERRED_INVENTORY, OBSOLETE, UNAVAILABLE }
    private static final long MAGIC = 0x314b4e4c595856L; // VXYLNK1
    private static final int BYTES = 44;
    final RegionalDiskBudget budget;
    private volatile boolean closed;
    RegionalMetadataStore(Path root) throws IOException { this.budget = RegionalDiskBudget.acquire(root); }
    RegionalMetadataStore(RegionalDiskBudget budget) { this.budget = budget; budget.retain(); }
    Path namespace(RegionalProtocol.Hash32 world, String dimension) {
        return ClientLodDebug.cacheNamespace(this.budget.root.resolve(hex(world)).resolve(identifier(dimension)));
    }
    private Path association(String server, String dimension) {
        return ClientLodDebug.cacheNamespace(this.budget.root).resolve("servers").resolve(identifier(server + '\0' + dimension) + ".vxlink");
    }
    RegionalProtocol.Hash32 world(String server, String dimension) throws IOException {
        if (server == null) return null;
        Path path = association(server, dimension);
        try (var pin = this.budget.pin(path)) {
            LocalCacheOwnership.rejectLinks(path);
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) != BYTES) return null;
            byte[] bytes = new byte[BYTES];
            try (var file = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                if (file.size() != BYTES) return null;
                var target = ByteBuffer.wrap(bytes);
                while (target.hasRemaining()) if (file.read(target) <= 0) return null;
            }
            var input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            if (input.getLong() != MAGIC || input.getInt(40) != RegionalProtocol.crc32c(java.util.Arrays.copyOf(bytes, 40))) return null;
            return RegionalProtocol.Hash32.read(input);
        }
    }
    Persistence associate(String server, String dimension, RegionalProtocol.Hash32 world,
                          long stamp, BooleanSupplier current) throws IOException {
        if (server == null || this.closed || !current.getAsBoolean()) return Persistence.OBSOLETE;
        var unavailable = this.budget.persistenceUnavailable();
        if (unavailable != null) return unavailable;
        Path path = association(server, dimension), temporary = path.resolveSibling(path.getFileName() + ".pending");
        try (var pin = this.budget.pin(path); var pending = this.budget.pin(temporary)) {
            LocalCacheOwnership.rejectLinks(path); LocalCacheOwnership.rejectLinks(temporary);
            if (world.equals(world(server, dimension))) return Persistence.PERSISTED;
            Files.createDirectories(path.getParent());
            long oldTemporary = RegionalDiskBudget.size(temporary);
            this.budget.reserve(temporary, Math.max(0, BYTES - oldTemporary));
            long before = RegionalDiskBudget.size(path);
            boolean installed = false;
            try {
                var bytes = ByteBuffer.allocate(BYTES).order(ByteOrder.LITTLE_ENDIAN).putLong(MAGIC).put(world.bytes());
                bytes.putInt(RegionalProtocol.crc32c(java.util.Arrays.copyOf(bytes.array(), 40))).flip();
                try (var file = FileChannel.open(temporary, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS)) {
                    while (bytes.hasRemaining()) if (file.write(bytes) <= 0) throw new IOException("short world hint write");
                    file.force(true);
                }
                if (this.closed || !current.getAsBoolean()) return Persistence.OBSOLETE;
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                this.budget.resized(temporary, -Math.max(BYTES, oldTemporary));
                this.budget.resized(path, BYTES - before);
                installed = true;
                return Persistence.PERSISTED;
            } finally {
                if (!installed) {
                    long reserved = Math.max(BYTES, oldTemporary);
                    try { Files.deleteIfExists(temporary); }
                    finally { this.budget.resized(temporary, RegionalDiskBudget.size(temporary) - reserved); }
                }
            }
        }
    }
    static RegionalProtocol.Hash32 hash(byte[] bytes) {
        return RegionalProtocol.Hash32.read(ByteBuffer.wrap(new Blake3.Hasher().update(bytes).digest()).order(ByteOrder.LITTLE_ENDIAN));
    }
    static String identifier(String value) { return hex(hash(value.getBytes(StandardCharsets.UTF_8))); }
    private static String hex(RegionalProtocol.Hash32 hash) { return HexFormat.of().formatHex(hash.bytes()); }
    @Override public void close() { if (!this.closed) { this.closed = true; this.budget.release(); } }
}
