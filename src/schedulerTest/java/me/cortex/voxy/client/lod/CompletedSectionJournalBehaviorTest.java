package me.cortex.voxy.client.lod;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import me.cortex.voxy.client.core.rendering.SectionKey;
import static me.cortex.voxy.client.lod.CacheStartupBehaviorTest.*;

/** Faults target actual journal bytes and worker-owned streaming operations. */
public final class CompletedSectionJournalBehaviorTest {
    public static void main(String[] args) throws Exception { run(); }
    static final class Space implements CompletedSectionJournal.Space {
        long bytes, peak, limit = CompletedSectionJournal.MAX_BYTES;
        Space(long bytes) { this.bytes = bytes; }
        public void reserve(long n) throws IOException {
            if (this.bytes + n > this.limit) throw new IOException("injected disk capacity");
            this.bytes += n; this.peak = Math.max(this.peak, this.bytes);
        }
        public void resized(long delta) { this.bytes += delta; }
    }
    static void append(CompletedSectionJournal journal, LocalSection section, Fixture fixture, Space space) throws Exception {
        try (var wire = new RegionalSectionCodec(); var codec = new LocalSectionCodec()) {
            byte[] canonical = section.kind() == LocalSection.DATA ? wire.decompress(fixture.payload(), section.canonicalBytes()) : null;
            if (canonical != null) wire.decode(section.key(), section.children(), canonical, section.fingerprint(), MAPPINGS);
            try (var encoder = canonical == null ? null : codec.encode(canonical, CatalogCodec.decode(fixture.catalog().canonical()));
                 var append = journal.begin(section, encoder, space, () -> true)) {
                while (!append.step()) {}
            }
        }
    }
    static void run() throws Exception {
        var root = Files.createTempDirectory(Path.of("project_audit"), "local-journal-tests-");
        try {
            framing(root.resolve("framing.vxlocal"));
            sharedDirectoryAndReaders(root.resolve("cache"));
            abortedReservation(root.resolve("budget.vxlocal"));
            associationAndPressure(root.resolve("pressure"));
            System.out.println("local journal: truncation, integrity, catalog identity, absence, leases and disk accounting passed");
        } finally { cleanup(root); }
    }
    private static void framing(Path path) throws Exception {
        var a = fixture(1, 1, 240, 1); var b = fixture(2, 1, 224, 2);
        var sa = LocalSection.from(a.index(), 340, a.catalog().fingerprint());
        var sb = LocalSection.from(b.index(), 340, b.catalog().fingerprint());
        byte[] prefix, complete;
        try (var journal = CompletedSectionJournal.open(path, WORLD, 0, true)) {
            var space = new Space(journal.bytes());
            append(journal, sa, a, space);
            prefix = Files.readAllBytes(path);
            append(journal, sa, a, space);
            check(journal.bytes() == prefix.length, "identical content appended again");
            for (int ordinal : new int[]{0, 256, 320, 336})
                append(journal, LocalSection.from(a.index(), ordinal, a.catalog().fingerprint()), a, space);
            check(journal.directory().size() == 5 && journal.payloadCount() == 1, "all-LOD dedup changed");
            append(journal, sb, b, space);
            check(journal.previous(sb).equals(sa), "predecessor lost");
            check(space.bytes == Files.size(path), "committed append accounting differs");
            complete = Files.readAllBytes(path);
        }
        for (int end = prefix.length; end <= complete.length; end++) {
            Files.write(path, Arrays.copyOf(complete, end));
            try (var journal = CompletedSectionJournal.open(path, WORLD, 0, true); var codec = new LocalSectionCodec()) {
                var current = journal.directory().get(KEY);
                check(current.equals(end == complete.length ? sb : sa), "torn binding at " + end);
                var decoded = journal.get(current, codec, (name, biome) -> biome ? 0 : 15);
                check(decoded != null && (decoded.cells()[0] >>> 56) == (end == complete.length ? 224 : 240), "wrong recovered cells");
                check(journal.bytes() == Files.size(path), "recovery did not truncate uncommitted suffix");
            }
        }
        byte[] corrupt = complete.clone(); corrupt[corrupt.length - 20] ^= 1;
        Files.write(path, corrupt);
        try (var journal = CompletedSectionJournal.open(path, WORLD, 0, true)) {
            check(journal.directory().get(KEY).equals(sa), "corrupt binding replaced A");
        }
        Files.write(path, complete);
        try (var journal = CompletedSectionJournal.open(path, WORLD, 0, true)) {
            var space = new Space(journal.bytes());
            var absent = new LocalSection(KEY, LocalSection.ABSENT, 0, 0, 0, 0,
                    new RegionalProtocol.Fingerprint(0, 0), RegionalProtocol.Hash32.ZERO);
            append(journal, absent, null, space);
            check(journal.previous(sb) == null, "fallback crossed absence barrier");
        }
        try (var journal = CompletedSectionJournal.open(path, WORLD, 0, true)) {
            check(journal.directory().get(KEY).kind() == LocalSection.ABSENT, "absence lost after reopen");
            var space = new Space(journal.bytes());
            append(journal, sb, b, space);
            check(journal.directory().get(KEY).equals(sb), "recreation did not replace tombstone");
            var otherCatalog = fixture(3, 1, 224, 3);
            append(journal, LocalSection.from(otherCatalog.index(), 340, otherCatalog.catalog().fingerprint()), otherCatalog, space);
            check(journal.payloadCount() == 3, "numeric wire hash deduplicated across source catalogs");
        }
    }
    private static void abortedReservation(Path path) throws Exception {
        var a = fixture(1, 0, 240, 1); var b = fixture(2, 0, 224, 1);
        var sa = LocalSection.from(a.index(), 340, a.catalog().fingerprint());
        var sb = LocalSection.from(b.index(), 340, b.catalog().fingerprint());
        try (var journal = CompletedSectionJournal.open(path, WORLD, 0, true)) {
            var space = new Space(journal.bytes());
            append(journal, sa, a, space);
            long before = space.bytes;
            for (long allowance : new long[]{0, 92, 110, 160}) {
                space.limit = before + allowance;
                try { append(journal, sb, b, space); throw new AssertionError("partial capacity accepted record"); }
                catch (IOException expected) {}
                check(space.bytes == before && Files.size(path) == before && journal.directory().get(KEY).equals(sa),
                        "failed reservation changed committed predecessor/accounting");
                check(space.peak <= space.limit, "uncommitted bytes exceeded disk allowance");
            }
            space.limit = CompletedSectionJournal.MAX_BYTES;
            append(journal, sb, b, space);
            check(journal.directory().get(KEY).equals(sb), "capacity recovery permanently refused region");
        }
    }
    private static void sharedDirectoryAndReaders(Path root) throws Exception {
        var a = fixture(1, 0, 240, 1); var b = fixture(2, 0, 224, 1);
        try (var metadata = new RegionalMetadataStore(root);
             var first = new CompletedSectionCache(metadata, WORLD, DIMENSION);
             var second = new CompletedSectionCache(metadata, WORLD, DIMENSION)) {
            awaitInventory(metadata.budget);
            storeSection(first, a, 340);
            var sa = LocalSection.from(a.index(), 340, a.catalog().fingerprint());
            var entered = new CountDownLatch(1); var resume = new CountDownLatch(1);
            var executor = Executors.newSingleThreadExecutor();
            try {
                Future<RegionalSectionCodec.SectionData> reading = executor.submit(() -> {
                    try (var codec = new LocalSectionCodec()) {
                        return first.get(sa, codec, (name, biome) -> {
                            entered.countDown();
                            try { if (!resume.await(5, TimeUnit.SECONDS)) throw new IOException("reader release timeout"); }
                            catch (InterruptedException stopped) { throw new IOException(stopped); }
                            return biome ? 0 : 15;
                        });
                    }
                });
                check(entered.await(5, TimeUnit.SECONDS), "reader did not enter decoder");
                check(!metadata.budget.delete(first.path(0)), "eviction ignored reader pin");
                var original = metadata.budget.journal(first.path(0), WORLD, 0, false);
                check(original.closeHandle(), "reader unnecessarily pinned writer handle");
                storeSection(second, b, 340);
                check(metadata.budget.journal(first.path(0), WORLD, 0, false) == original, "handle reopen replayed directory");
                check(first.directory(0).get(KEY).fingerprint().equals(b.index().sectionFingerprint(340)), "shared cache owner missed append");
                first.close(); // Delayed release must not close the reader's file/root ownership.
                resume.countDown();
                check(reading.get(5, TimeUnit.SECONDS).cells()[0] >>> 56 == 240, "concurrent append changed old leased payload");
                check(metadata.budget.delete(second.path(0)), "unleased file could not be evicted");
                check(second.directory(0).isEmpty(), "evicted incarnation stayed discoverable");
            } finally { resume.countDown(); executor.shutdownNow(); check(executor.awaitTermination(5, TimeUnit.SECONDS), "reader leaked"); }
        }
    }
    private static void associationAndPressure(Path root) throws Exception {
        var budget = new RegionalDiskBudget(root, 1200);
        try (var metadata = new RegionalMetadataStore(budget)) {
            awaitInventory(budget);
            metadata.associate(SERVER, DIMENSION, WORLD, budget.stamp(), () -> true);
            check(metadata.world(SERVER, DIMENSION).equals(WORLD), "world hint did not roundtrip");
            check(metadata.world(SERVER, "minecraft:the_end") == null, "dimension identity leaked");
            var a = fixture(1, 0, 240, 1);
            try (var cache = new CompletedSectionCache(metadata, WORLD, DIMENSION)) {
                for (int region = 0; region < 12; region++) {
                    storeSection(cache, fixture(1, 0, 240, 1, region, 0), 340);
                    check(budget.bytes <= budget.limit, "disk limit exceeded");
                }
                long actual;
                try (var files = Files.walk(root)) { actual = files.filter(Files::isRegularFile).mapToLong(RegionalDiskBudget::size).sum(); }
                check(actual == budget.bytes && budget.eviction > 0, "eviction accounting mismatch");
            }
        }
        try (var reopened = new RegionalMetadataStore(root)) {
            awaitInventory(reopened.budget);
            check(reopened.budget.bytes <= 1200, "compatible startup lost accounting");
        }
    }
}
