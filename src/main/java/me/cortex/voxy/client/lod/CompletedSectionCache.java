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
    private final Map<Long, RegionalDiskBudget.Region> retained = new java.util.HashMap<>();
    CompletedSectionCache(RegionalMetadataStore metadata, RegionalProtocol.Hash32 world, String dimension) {
        this.metadata = metadata; this.budget = metadata.budget; this.world = world; this.dimension = dimension;
        this.root = metadata.namespace(world, dimension); this.budget.retain();
    }
    Path path(long region) { return this.root.resolve("r." + (int) region + "." + (int) (region >>> 32) + ".vxlocal"); }
    RegionalDiskBudget.Admission canAdmit(long key, long estimatedBytes) {
        long binding = CompletedSectionJournal.FRAME_BYTES + CompletedSectionJournal.BINDING_BYTES + CompletedSectionJournal.FOOTER_BYTES;
        return this.budget.admission(path(region(key)), Math.max(binding, estimatedBytes));
    }
    boolean canDownload() { return this.metadata.canDownload(); }
    private static long region(long key) {
        int shift = me.cortex.voxy.client.core.rendering.SectionKey.MAX_LOD_LAYER
                - me.cortex.voxy.client.core.rendering.SectionKey.level(key);
        return Integer.toUnsignedLong(me.cortex.voxy.client.core.rendering.SectionKey.x(key) >> shift)
                | (long) (me.cortex.voxy.client.core.rendering.SectionKey.z(key) >> shift) << 32;
    }
    LocalSection binding(long key) throws IOException {
        long region = region(key);
        var acquired = acquire(region);
        try (var pin = acquired) {
            var journal = this.budget.journal(path(region), this.world, region, false);
            return journal == null ? null : journal.binding(key);
        } finally { released(); }
    }
    private RegionalDiskBudget.Pin acquire(long region) throws IOException {
        synchronized (this) {
            if (this.closed) throw new IOException("closed section cache");
            this.operations++;
        }
        // Accepted operations retain the budget through close, including a draining-pin wait.
        try { return this.budget.pin(path(region)); }
        catch (Throwable failure) { released(); throw failure; }
    }
    private synchronized void released() {
        if (--this.operations < 0) throw new IllegalStateException("cache operation underflow");
        if (this.closed && this.operations == 0) this.budget.release();
    }
    Map<Long, LocalSection> directory(long region) throws IOException {
        retain(region);
        return directorySnapshot(region);
    }
    boolean knownAbsent(long region) throws IOException {
        retain(region);
        return this.budget.knownAbsent(path(region));
    }
    /** No file access; the retained hot path also avoids path allocation. */
    synchronized long incarnation(long region) {
        var owner = this.retained.get(region);
        return owner == null ? this.budget.incarnation(path(region)) : owner.incarnation;
    }
    /** Retains only the recovered index, including a not-yet-created journal. */
    synchronized void retain(long region) throws IOException {
        if (this.closed) throw new IOException("closed section cache");
        if (!this.retained.containsKey(region)) this.retained.put(region, this.budget.retainDirectory(path(region)));
    }
    synchronized void forget(long region) {
        if (this.retained.remove(region) != null) this.budget.releaseDirectory(path(region));
    }
    Map<Long, LocalSection> directorySnapshot(long region) throws IOException {
        return snapshotDirectory(region).sections();
    }
    record Directory(Map<Long, LocalSection> sections, long namedBytes, long incarnation) {}
    /** Foreground snapshots do not need the downloader's deduplicated byte estimate. */
    Directory snapshotDirectory(long region) throws IOException { return inspectDirectory(region, false); }
    Directory inspectDirectory(long region) throws IOException {
        return inspectDirectory(region, true);
    }
    private Directory inspectDirectory(long region, boolean accounting) throws IOException {
        var acquired = acquire(region);
        try (var pin = acquired) {
            var journal = this.budget.journal(path(region), this.world, region, false);
            if (journal == null) return new Directory(new java.util.HashMap<>(), 0, pin.incarnation());
            synchronized (journal) {
                return new Directory(journal.directory(), accounting ? journal.currentNamedBytes() : 0, pin.incarnation());
            }
        } finally { released(); }
    }
    record Fallback(LocalSection section, long incarnation) {}
    Fallback previous(LocalSection section) throws IOException {
        var acquired = acquire(section.region());
        try (var pin = acquired) {
            var journal = this.budget.journal(path(section.region()), this.world, section.region(), false);
            return new Fallback(journal == null ? null : journal.previous(section), pin.incarnation());
        } finally { released(); }
    }
    RegionalSectionCodec.SectionData get(LocalSection section, LocalSectionCodec codec, LocalSectionCodec.Names names) throws IOException {
        var acquired = acquire(section.region());
        try (var pin = acquired) {
            var journal = this.budget.journal(path(section.region()), this.world, section.region(), false);
            if (journal == null) return null;
            try { return journal.get(section, codec, names); }
            catch (IOException invalid) {
                if (!journal.hasPayload(section)) this.budget.invalidateDirectory(path(section.region()));
                throw invalid;
            }
        } finally { released(); }
    }
    Save begin(LocalSection section, LocalSectionCodec codec, byte[] canonical, CatalogCodec.Source source,
               BooleanSupplier current) throws IOException {
        var writer = writer(section.region(), current);
        try { return beginOwned(section, codec, canonical, source, current, writer, true); }
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
    private Save beginOwned(LocalSection section, LocalSectionCodec codec, byte[] canonical, CatalogCodec.Source source,
                            BooleanSupplier current, Writer writer, boolean releaseWriter) throws IOException {
        RegionalDiskBudget.checkCurrent(current);
        long region = section.region();
        var pin = acquire(region);
        LocalSectionCodec.Encoder encoder = null;
        try {
            var journal = this.budget.journal(path(region), this.world, region, false);
            long growth = CompletedSectionJournal.FRAME_BYTES + CompletedSectionJournal.BINDING_BYTES + CompletedSectionJournal.FOOTER_BYTES;
            if (journal == null) growth += CompletedSectionJournal.HEADER_BYTES;
            if (journal != null && section.equals(journal.binding(section.key()))) growth = 0;
            else if (section.kind() == LocalSection.DATA && (journal == null || !journal.hasPayload(section))) {
                encoder = codec.encode(canonical, source);
                long overhead = growth + CompletedSectionJournal.FRAME_BYTES + CompletedSectionJournal.PAYLOAD_METADATA_BYTES + CompletedSectionJournal.FOOTER_BYTES;
                growth = Math.addExact(overhead, LocalSectionCodec.compressedBound(encoder.canonicalBytes()));
                if (!writer.owned.fits(growth)) {
                    // Only the refusal path counts exact output; normal writes stay one-pass.
                    while (!encoder.step(java.io.OutputStream.nullOutputStream())) RegionalDiskBudget.checkCurrent(current);
                    growth = Math.addExact(overhead, encoder.compressedBytes());
                    encoder.close(); encoder = null;
                    writer.owned.expect(growth);
                    encoder = codec.encode(canonical, source);
                }
            }
            writer.owned.expect(growth);
            if (journal == null) journal = this.budget.journal(path(region), this.world, region, true);
            return new Save(section, encoder, journal.begin(section, encoder, space(region, writer.owned), current), pin, writer, releaseWriter);
        } catch (Throwable failure) {
            if (failure instanceof IOException io) writer.owned.failed(io);
            if (encoder != null) encoder.close();
            pin.close(); released(); throw failure;
        }
    }

    /** Called by the section's worker after its sole geometry completion was handed off. */
    long save(LocalSection section, LocalSectionCodec codec, byte[] canonical, CatalogCodec.Source source,
              BooleanSupplier current, Object debugWork) throws IOException {
        ClientLodDebug.workerStage(debugWork, "WAIT_REGION_WRITER");
        try (var writer = writer(section.region(), current)) {
            this.budget.awaitReady(current);
            ClientLodDebug.workerStage(debugWork, "SAVE_ENCODE_WRITE");
            boolean compacted = false;
            while (true) {
                RegionalDiskBudget.checkCurrent(current);
                try (var save = beginOwned(section, codec, canonical, source, current, writer, false)) {
                    while (!save.step()) RegionalDiskBudget.checkCurrent(current);
                    return save.pin.incarnation();
                } catch (RegionalDiskBudget.Capacity full) {
                    if (full.reason != RegionalDiskBudget.Admission.QUOTA || compacted
                            || !writer.owned.compact(this.world, section.region(), current)) throw full;
                    compacted = true;
                }
            }
        }
    }
    private CompletedSectionJournal.Space space(long region, RegionalDiskBudget.Writer writer) {
        Path path = path(region);
        return new CompletedSectionJournal.Space() {
            public void reserve(long bytes) throws IOException { budget.reserve(path, bytes); }
            public void resized(long delta) { budget.resized(path, delta); }
            public void commit(CompletedSectionJournal.IOAction action) throws IOException { budget.commit(path, action); }
            public void failed(IOException failure) { writer.failed(failure); }
        };
    }

    final class Save implements AutoCloseable {
        private final LocalSection section;
        private final LocalSectionCodec.Encoder encoder;
        private final CompletedSectionJournal.Append append;
        private final RegionalDiskBudget.Pin pin;
        private final Writer writer;
        private final boolean releaseWriter;
        private boolean closed;
        private Save(LocalSection section, LocalSectionCodec.Encoder encoder,
                     CompletedSectionJournal.Append append, RegionalDiskBudget.Pin pin, Writer writer, boolean releaseWriter) {
            this.section = section; this.encoder = encoder; this.append = append; this.pin = pin; this.writer = writer;
            this.releaseWriter = releaseWriter;
        }
        boolean step() throws IOException {
            boolean done = this.append.step();
            if (done) this.writer.owned.completed();
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
                if (this.releaseWriter) this.writer.close();
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
                writer.owned.expect(CompletedSectionJournal.FRAME_BYTES + 8 + CompletedSectionJournal.FOOTER_BYTES);
                try (var append = journal.begin(null, null, space(region, writer.owned), current)) { while (!append.step()) {} }
                this.budget.invalidateDirectory(path(region));
                writer.owned.completed();
            } finally { released(); }
        }
        return RegionalMetadataStore.Persistence.PERSISTED;
    }
    @Override public synchronized void close() {
        if (this.closed) return;
        this.closed = true;
        for (long region : this.retained.keySet()) this.budget.releaseDirectory(path(region));
        this.retained.clear();
        if (this.operations == 0) this.budget.release();
    }
}
