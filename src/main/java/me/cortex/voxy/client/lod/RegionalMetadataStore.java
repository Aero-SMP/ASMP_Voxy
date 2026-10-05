package me.cortex.voxy.client.lod;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.HexFormat;
import java.util.function.BooleanSupplier;

/** Optional world hint/catalogue metadata; named section journals remain self-contained. */
final class RegionalMetadataStore implements AutoCloseable {
    enum Persistence { PERSISTED, DEFERRED_INVENTORY, OBSOLETE, UNAVAILABLE }
    private static final long MAGIC = 0x314b4e4c595856L; // VXYLNK1
    private static final int BYTES = 44;
    final RegionalDiskBudget budget;
    private volatile boolean closed;
    private volatile RegionalDiskBudget.Account account;
    private volatile String rawAddress;
    RegionalMetadataStore(Path root) throws IOException { this.budget = RegionalDiskBudget.acquire(root); }
    RegionalMetadataStore(RegionalDiskBudget budget) { this.budget = budget; budget.retain(); }
    void bindServer(me.cortex.voxy.client.config.ServerDownloadSettings policy, String rawAssociationAddress) throws IOException {
        this.account = this.budget.configure(policy);
        this.rawAddress = rawAssociationAddress;
    }
    void updateRetention(String dimension, long blockX, long blockZ, java.util.Set<Long> protectedVisibleRegions) {
        this.budget.retention(this.account, dimension, blockX, blockZ, protectedVisibleRegions);
    }
    RegionalDiskBudget.StorageState namespaceBudget() { return this.budget.storage(this.account); }
    long admissionGeneration() { var owner = this.account; return owner == null ? 0 : this.budget.admissionGeneration(owner.key); }
    boolean canDownload() { return !namespaceBudget().downloadPaused(); }
    void remember(String dimension, RegionalProtocol.Hash32 world) throws IOException {
        this.budget.claim(this.account, namespace(world, dimension), dimension,
                this.rawAddress == null ? null : association(this.rawAddress, dimension));
    }
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
            var world = RegionalProtocol.Hash32.read(input);
            if (this.account != null) this.budget.claim(this.account, namespace(world, dimension), dimension, path);
            return world;
        }
    }
    RegionalProtocol.CatalogMessage readCatalogue(RegionalProtocol.Hash32 world, String dimension) throws IOException {
        Path path = namespace(world, dimension).resolve("catalogue.vxcat");
        synchronized (this) { if (this.closed) return null; this.budget.retain(); }
        try (var pin = this.budget.pin(path)) {
            LocalCacheOwnership.rejectLinks(path);
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return null;
            try (var file = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                if (file.size() < 46 || file.size() > 5L + RegionalProtocol.MAX_CATALOG_FRAME_BYTES)
                    throw new IOException("cached catalogue extent is invalid");
                var input = Channels.newInputStream(file);
                var message = RegionalProtocol.readStoredCatalogue(input, world);
                if (!(message instanceof RegionalProtocol.CatalogMessage catalog) || input.read() != -1)
                    throw new IOException("cached catalogue contains an invalid frame");
                return catalog;
            } catch (NoSuchFileException missing) { return null; }
        } finally { this.budget.release(); }
    }
    Persistence persistCatalogue(RegionalProtocol.Hash32 world, String dimension,
                                 RegionalProtocol.CatalogMessage catalog, long stamp,
                                 BooleanSupplier current) throws IOException {
        synchronized (this) { if (this.closed || !current.getAsBoolean()) return Persistence.OBSOLETE; this.budget.retain(); }
        try {
            var unavailable = this.budget.persistenceUnavailable();
            if (unavailable != null) return unavailable;
            remember(dimension, world);
            Path path = namespace(world, dimension).resolve("catalogue.vxcat");
            Path temporary = path.resolveSibling(path.getFileName() + ".pending");
            try (var writer = this.budget.writer(path, current);
                 var pin = this.budget.pin(path); var pending = this.budget.pin(temporary)) {
                LocalCacheOwnership.rejectLinks(path); LocalCacheOwnership.rejectLinks(temporary);
                return install(path, temporary, ByteBuffer.wrap(RegionalProtocol.catalogFrame(catalog)), current, writer);
            }
        } finally { this.budget.release(); }
    }
    Persistence associate(String server, String dimension, RegionalProtocol.Hash32 world,
                          long stamp, BooleanSupplier current) throws IOException {
        synchronized (this) {
            if (server == null || this.closed || !current.getAsBoolean()) return Persistence.OBSOLETE;
            this.budget.retain();
        }
        try { return associateOwned(server, dimension, world, current); }
        finally { this.budget.release(); }
    }
    private Persistence associateOwned(String server, String dimension, RegionalProtocol.Hash32 world,
                                       BooleanSupplier current) throws IOException {
        var unavailable = this.budget.persistenceUnavailable();
        if (unavailable != null) return unavailable;
        remember(dimension, world);
        Path path = association(server, dimension), temporary = path.resolveSibling(path.getFileName() + ".pending");
        try (var writer = this.budget.writer(path, current);
             var pin = this.budget.pin(path); var pending = this.budget.pin(temporary)) {
            LocalCacheOwnership.rejectLinks(path); LocalCacheOwnership.rejectLinks(temporary);
            if (world.equals(world(server, dimension))) return Persistence.PERSISTED;
            var bytes = ByteBuffer.allocate(BYTES).order(ByteOrder.LITTLE_ENDIAN).putLong(MAGIC).put(world.bytes());
            bytes.putInt(RegionalProtocol.crc32c(java.util.Arrays.copyOf(bytes.array(), 40))).flip();
            return install(path, temporary, bytes, current, writer);
        }
    }
    /** Caller owns the writer and both pins. Partial records never replace committed metadata. */
    private Persistence install(Path path, Path temporary, ByteBuffer bytes, BooleanSupplier current,
                                RegionalDiskBudget.Writer writer) throws IOException {
        if (this.closed || !current.getAsBoolean()) return Persistence.OBSOLETE;
        int length = bytes.remaining();
        long oldTemporary = RegionalDiskBudget.size(temporary);
        long charged = oldTemporary;
        long before = RegionalDiskBudget.size(path);
        boolean installed = false, touched = false;
        try {
            writer.expect(length);
            Files.createDirectories(path.getParent());
            this.budget.reserve(temporary, Math.max(0, length - oldTemporary)); charged = Math.max(length, oldTemporary);
            touched = true;
            try (var file = FileChannel.open(temporary, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS)) {
                while (bytes.hasRemaining()) {
                    RegionalDiskBudget.checkCurrent(current);
                    if (file.write(bytes) <= 0) throw new IOException("short cache metadata write");
                }
                file.force(true);
            }
            if (this.closed || !current.getAsBoolean()) return Persistence.OBSOLETE;
            this.budget.replace(temporary, path, length, before, charged, () -> !this.closed && current.getAsBoolean());
            installed = true; writer.completed();
            return Persistence.PERSISTED;
        } catch (IOException failure) {
            writer.failed(failure); throw failure;
        } finally {
            if (!installed && touched) {
                try { LocalCacheOwnership.rejectLinks(temporary); Files.deleteIfExists(temporary); }
                catch (IOException failure) { writer.failed(failure); throw failure; }
                finally { this.budget.resized(temporary, RegionalDiskBudget.size(temporary) - charged); }
            }
        }
    }
    static RegionalProtocol.Hash32 hash(byte[] bytes) {
        return RegionalProtocol.Hash32.read(ByteBuffer.wrap(new Blake3.Hasher().update(bytes).digest()).order(ByteOrder.LITTLE_ENDIAN));
    }
    static String identifier(String value) { return hex(hash(value.getBytes(StandardCharsets.UTF_8))); }
    private static String hex(RegionalProtocol.Hash32 hash) { return HexFormat.of().formatHex(hash.bytes()); }
    @Override public synchronized void close() {
        if (!this.closed) { this.closed = true; this.budget.flushAnchors(this.account); this.budget.release(); }
    }
}
