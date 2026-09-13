package me.cortex.voxy.client.lod;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.BooleanSupplier;

/** Worker-only cache facade. A save/read holds a disk pin, not the budget monitor. */
final class CompletedSectionCache implements AutoCloseable {
    final RegionalMetadataStore metadata;
    final RegionalProtocol.Hash32 world;
    final String dimension;
    private final RegionalDiskBudget budget;
    private final Path root;
    private volatile boolean closed;
    private int operations;
    private final java.util.Set<Long> retained = new java.util.HashSet<>();
    CompletedSectionCache(RegionalMetadataStore metadata, RegionalProtocol.Hash32 world, String dimension) {
        this.metadata = metadata; this.budget = metadata.budget; this.world = world; this.dimension = dimension;
        this.root = metadata.namespace(world, dimension); this.budget.retain();
    }
    Path path(long region) { return this.root.resolve("r." + (int) region + "." + (int) (region >>> 32) + ".vxlocal"); }
    private synchronized RegionalDiskBudget.Pin acquire(long region) throws IOException {
        if (this.closed) throw new IOException("closed section cache");
        var pin = this.budget.pin(path(region)); this.operations++; return pin;
    }
    private synchronized void released() {
        if (--this.operations < 0) throw new IllegalStateException("cache operation underflow");
        if (this.closed && this.operations == 0) this.budget.release();
    }
    Map<Long, LocalSection> directory(long region) throws IOException {
        synchronized (this) {
            if (this.closed) throw new IOException("closed section cache");
            if (this.retained.add(region)) this.budget.retainDirectory(path(region));
        }
        return directorySnapshot(region);
    }
    synchronized void forget(long region) {
        if (this.retained.remove(region)) this.budget.releaseDirectory(path(region));
    }
    Map<Long, LocalSection> directorySnapshot(long region) throws IOException {
        var acquired = acquire(region);
        try (var pin = acquired) {
            var journal = this.budget.journal(path(region), this.world, region, false);
            return journal == null ? Map.of() : journal.directory();
        } finally { released(); }
    }
    LocalSection previous(LocalSection section) throws IOException {
        var acquired = acquire(section.region());
        try (var pin = acquired) {
            var journal = this.budget.journal(path(section.region()), this.world, section.region(), false);
            return journal == null ? null : journal.previous(section);
        } finally { released(); }
    }
    RegionalSectionCodec.SectionData get(LocalSection section, LocalSectionCodec codec, LocalSectionCodec.Names names) throws IOException {
        var acquired = acquire(section.region());
        try (var pin = acquired) {
            var journal = this.budget.journal(path(section.region()), this.world, section.region(), false);
            if (journal == null) return null;
            return journal.get(section, codec, names);
        } finally { released(); }
    }
    Save begin(LocalSection section, LocalSectionCodec codec, byte[] canonical, CatalogCodec.Catalog source,
               BooleanSupplier current) throws IOException {
        var writer = writer(section.region(), current);
        try { return beginOwned(section, codec, canonical, source, current, writer); }
        catch (Throwable failure) { writer.close(); throw failure; }
    }
    private Writer writer(long region, BooleanSupplier current) throws IOException {
        synchronized (this) {
            if (this.closed) throw new IOException("closed section cache");
            this.operations++;
        }
        try { return new Writer(this.budget.writer(path(region), () -> !this.closed && current.getAsBoolean())); }
        catch (Throwable failure) { released(); throw failure; }
    }
    private final class Writer implements AutoCloseable {
        final RegionalDiskBudget.Writer owned;
        Writer(RegionalDiskBudget.Writer owned) { this.owned = owned; }
        public void close() { this.owned.close(); released(); }
    }
    private Save beginOwned(LocalSection section, LocalSectionCodec codec, byte[] canonical, CatalogCodec.Catalog source,
                            BooleanSupplier current, Writer releaseWriter) throws IOException {
        RegionalDiskBudget.checkCurrent(current);
        long region = section.region();
        var pin = acquire(region);
        LocalSectionCodec.Encoder encoder = null;
        try {
            if (section.kind() == LocalSection.DATA) encoder = codec.encode(canonical, source);
            var journal = this.budget.journal(path(region), this.world, region, true);
            // A full shard is rotated only when its actual next record cannot fit, never using
            // the maximum possible extent for every small record. Save retries own conversion.
            return new Save(section, encoder, journal.begin(section, encoder, space(region), current), pin, releaseWriter);
        } catch (Throwable failure) {
            if (encoder != null) encoder.close();
            pin.close(); released(); throw failure;
        }
    }

    /** Called by the section's worker after its sole geometry completion was handed off. */
    void save(LocalSection section, LocalSectionCodec codec, byte[] canonical, CatalogCodec.Catalog source,
              BooleanSupplier current, Object debugWork) throws IOException {
        ClientLodDebug.workerStage(debugWork, "WAIT_REGION_WRITER");
        try (var writer = writer(section.region(), current)) {
            this.budget.awaitReady(current);
            ClientLodDebug.workerStage(debugWork, "SAVE_ENCODE_WRITE");
            while (true) {
                RegionalDiskBudget.checkCurrent(current);
                try (var save = beginOwned(section, codec, canonical, source, current, null)) {
                    while (!save.step()) RegionalDiskBudget.checkCurrent(current);
                    RegionalDiskBudget.checkCurrent(current);
                    return;
                } catch (CompletedSectionJournal.RotationRequired full) {
                    // beginOwned/Save have released this writer's old-file pin and partial tail.
                    writer.owned.rotate(current);
                }
            }
        }
    }
    private CompletedSectionJournal.Space space(long region) {
        Path path = path(region);
        return new CompletedSectionJournal.Space() {
            public void reserve(long bytes) throws IOException { budget.reserve(path, bytes); }
            public void resized(long delta) { budget.resized(path, delta); }
        };
    }

    final class Save implements AutoCloseable {
        private final LocalSection section;
        private final LocalSectionCodec.Encoder encoder;
        private final CompletedSectionJournal.Append append;
        private final RegionalDiskBudget.Pin pin;
        private final Writer writer;
        private boolean closed;
        private Save(LocalSection section, LocalSectionCodec.Encoder encoder,
                     CompletedSectionJournal.Append append, RegionalDiskBudget.Pin pin, Writer writer) {
            this.section = section; this.encoder = encoder; this.append = append; this.pin = pin; this.writer = writer;
        }
        boolean step() throws IOException {
            boolean done = this.append.step();
            if (done && this.section != null) ClientLodDebug.cacheCommitted(CompletedSectionCache.this, this.section);
            return done;
        }
        @Override public void close() throws IOException {
            if (this.closed) return;
            this.closed = true;
            try { this.append.close(); }
            finally {
                if (this.encoder != null) this.encoder.close();
                this.pin.close(); released();
                if (this.writer != null) this.writer.close();
            }
        }
    }
    RegionalMetadataStore.Persistence putMetadata(LocalSection section, byte[] ignored, BooleanSupplier current) throws IOException {
        if (!current.getAsBoolean()) return RegionalMetadataStore.Persistence.OBSOLETE;
        var unavailable = this.budget.persistenceUnavailable(); if (unavailable != null) return unavailable;
        if (section.kind() == LocalSection.DATA) throw new IOException("data requires self-contained conversion");
        save(section, null, null, null, current, null);
        return RegionalMetadataStore.Persistence.PERSISTED;
    }
    boolean put(LocalSection section, byte[] ignored, BooleanSupplier current) throws IOException {
        return putMetadata(section, ignored, current) == RegionalMetadataStore.Persistence.PERSISTED;
    }
    RegionalMetadataStore.Persistence absentRegion(long region, BooleanSupplier current) throws IOException {
        var unavailable = this.budget.persistenceUnavailable(); if (unavailable != null) return unavailable;
        try (var writer = writer(region, current)) {
            RegionalDiskBudget.checkCurrent(current);
            var acquired = acquire(region);
            try (var pin = acquired) {
                var journal = this.budget.journal(path(region), this.world, region, false);
                if (journal == null || !journal.hasBindings()) return RegionalMetadataStore.Persistence.PERSISTED;
                try (var append = journal.begin(null, null, space(region), current)) { while (!append.step()) {} }
            } catch (CompletedSectionJournal.RotationRequired full) {
                // The try-with-resources pin has drained before exact-incarnation retirement.
                writer.owned.rotate(current);
            } finally { released(); }
        }
        return RegionalMetadataStore.Persistence.PERSISTED;
    }
    @Override public synchronized void close() {
        if (this.closed) return;
        this.closed = true;
        for (long region : this.retained) this.budget.releaseDirectory(path(region));
        this.retained.clear();
        if (this.operations == 0) this.budget.release();
    }
}
