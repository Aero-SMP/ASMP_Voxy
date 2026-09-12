package me.cortex.voxy.client.lod;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Budget-owned experimental local directory; all methods are worker-only, never render-thread I/O. */
final class CompletedSectionCache implements AutoCloseable {
    private final RegionalMetadataStore metadata;
    private final RegionalDiskBudget budget;
    private final RegionalProtocol.Hash32 world;
    private final String dimension;
    private final Path root;
    private final RegionalCache legacy;
    private final LinkedHashMap<Long, CompletedSectionJournal> journals = new LinkedHashMap<>(64, .75f, true);
    private boolean closed;

    CompletedSectionCache(RegionalMetadataStore metadata, RegionalProtocol.Hash32 world, String dimension) throws IOException {
        this.metadata = metadata; this.budget = metadata.budget; this.world = world; this.dimension = dimension;
        this.root = metadata.namespace(world, dimension);
        this.legacy = RegionalCache.legacyReader(metadata.legacyNamespace(world, dimension), world, this.budget);
        this.budget.retain();
    }

    Path path(long region) { return this.root.resolve("r." + (int) region + "." + (int) (region >>> 32) + ".vxlocal"); }

    Map<Long, LocalSection> directory(long region) throws IOException {
        synchronized (this.budget) {
            var journal = journal(region, false);
            return journal == null ? Map.of() : journal.directory();
        }
    }

    LocalSection previous(LocalSection invalid) throws IOException {
        synchronized (this.budget) {
            var journal = journal(invalid.region(), false);
            return journal == null ? null : journal.previous(invalid);
        }
    }

    LocalSection resolve(long key, long region, java.util.function.Predicate<LocalSection> usable) throws IOException {
        synchronized (this.budget) {
            try (var pin = this.budget.pin(path(region))) {
                var journal = journal(region, false);
                return journal == null ? null : journal.resolve(key, usable);
            }
        }
    }

    void quarantine(LocalSection invalid, byte[] failedBytes) throws IOException {
        synchronized (this.budget) {
            if (this.closed) return;
            Path path = path(invalid.region());
            if (!this.budget.closeOtherOwners(path, this)) return;
            var journal = journal(invalid.region(), false);
            if (journal == null) return;
            // A concurrent worker may already have repaired this shared blob. A failed
            // read without bytes only authorizes dropping data that still fails CRC.
            try {
                byte[] now = journal.get(invalid);
                if (now == null || failedBytes == null || !Arrays.equals(now, failedBytes)) return;
            } catch (IOException stillCorrupt) { /* The currently installed blob is damaged. */ }
            boolean persist = this.budget.writable() && this.budget.ensure(48, Set.of(path));
            long before = journal.bytes();
            try { journal.quarantine(invalid, persist); }
            finally { if (this.budget.ready()) this.budget.bytes += RegionalDiskBudget.size(path) - before; }
        }
    }

    byte[] get(LocalSection section) throws IOException {
        synchronized (this.budget) {
            var journal = journal(section.region(), false);
            byte[] bytes = journal == null ? null : journal.get(section);
            return bytes == null ? this.legacy.get(section) : bytes;
        }
    }

    boolean legacyContains(LocalSection section) throws IOException {
        return section.kind() != LocalSection.DATA || this.legacy.contains(section);
    }

    boolean legacySealed(long region) throws IOException {
        synchronized (this.budget) {
            var journal = journal(region, false);
            return journal != null && journal.legacySealed();
        }
    }

    void absentRegion(long region, BooleanSupplier current) throws IOException {
        synchronized (this.budget) {
            if (this.closed || !this.budget.writable() || !current.getAsBoolean()) return;
            Path path = path(region);
            if (!this.budget.closeOtherOwners(path, this)) return;
            var journal = journal(region, true);
            if (journal == null || !this.budget.ensure(32, Set.of(path)) || !current.getAsBoolean()) return;
            long before = journal.bytes();
            try { journal.absentRegion(); }
            finally {
                this.budget.bytes += RegionalDiskBudget.size(path) - before;
                this.budget.references(path, catalogPaths(path, journal.catalogs()));
            }
        }
    }

    /** The pipeline has one immutable worker ticket per position. Check that ticket under
     * append exclusion: a revoked worker cannot overwrite a successor's committed binding.
     * Server generation numbers are deliberately not used as cross-session cache authority. */
    boolean put(LocalSection section, byte[] compressed, BooleanSupplier current) throws IOException {
        synchronized (this.budget) {
            if (this.closed || !this.budget.writable() || !current.getAsBoolean()) return false;
            Path path = path(section.region());
            Path catalog = section.catalog().equals(RegionalProtocol.Hash32.ZERO) ? null
                    : this.metadata.catalogPath(this.world, this.dimension, section.catalog());
            // Catalog save validates and forces content before this worker can publish a binding.
            if (catalog != null && !Files.isRegularFile(catalog)) return false;
            Set<Path> protect = catalog == null ? Set.of(path) : Set.of(path, catalog);
            if (!this.budget.closeOtherOwners(path, this)) return false;
            // Catalog pins cover initial shard creation as well as the actual append reservation.
            try (var pin = this.budget.pin(catalog)) {
            var journal = journal(section.region(), true);
            if (journal == null) return false;
            long added = journal.appendBytes(section);
            if (journal.bytes() + added > CompletedSectionJournal.MAX_BYTES) return false;
            if (!this.budget.ensure(added, protect) || !current.getAsBoolean()) return false;
            long before = journal.bytes();
            try { journal.append(section, compressed); }
            finally {
                this.budget.bytes += RegionalDiskBudget.size(path) - before;
                this.budget.references(path, catalogPaths(path, journal.catalogs()));
            }
            return true;
            }
        }
    }

    private CompletedSectionJournal journal(long region, boolean create) throws IOException {
        if (this.closed) return null;
        var existing = this.journals.get(region);
        if (existing != null && create && !existing.writable()) {
            if (!closeRegion(region)) return null;
            existing = null;
        }
        if (existing != null) return existing;
        Path path = path(region);
        boolean present = Files.isRegularFile(path);
        long before = RegionalDiskBudget.size(path);
        if ((!present || before == 0) && !create) return null;
        if ((!present || before == 0) && !this.budget.ensure(CompletedSectionJournal.HEADER_BYTES, Set.of(path))) return null;
        if (!present) Files.createDirectories(this.root);
        CompletedSectionJournal opened;
        try { opened = CompletedSectionJournal.open(path, this.world, region, this.budget.ready()); }
        finally { if (this.budget.ready()) this.budget.bytes += RegionalDiskBudget.size(path) - before; }
        this.journals.put(region, opened);
        this.budget.register(path, this, () -> closeRegion(region));
        while (this.journals.size() > 64) closeRegion(this.journals.firstEntry().getKey());
        return opened;
    }

    private boolean closeRegion(long region) {
        var journal = this.journals.remove(region);
        if (journal == null) return true;
        this.budget.unregister(path(region), this);
        try { journal.close(); return true; } catch (IOException failure) { return false; }
    }

    static Set<Path> referencedCatalogs(Path path) throws IOException {
        // Fixed header identifies the namespace; recovery skips compressed payload bodies.
        try (var input = Files.newInputStream(path)) {
            byte[] header = input.readNBytes(CompletedSectionJournal.HEADER_BYTES);
            if (header.length != CompletedSectionJournal.HEADER_BYTES) return Set.of();
            var bytes = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN); bytes.position(8);
            var world = RegionalProtocol.Hash32.read(bytes); long region = bytes.getLong();
            try (var journal = CompletedSectionJournal.open(path, world, region, false)) {
                return catalogPaths(path, journal.catalogs());
            } catch (IOException invalid) { return Set.of(); }
        }
    }

    private static Set<Path> catalogPaths(Path path, Set<RegionalProtocol.Hash32> catalogs) {
        var result = new HashSet<Path>();
        for (var catalog : catalogs) result.add(path.resolveSibling(HexFormat.of().formatHex(catalog.bytes()) + ".vxcat"));
        return result;
    }

    @Override public void close() {
        synchronized (this.budget) {
            if (this.closed) return;
            this.closed = true;
            for (long region : List.copyOf(this.journals.keySet())) closeRegion(region);
            this.legacy.close();
            this.budget.release();
        }
    }
}
