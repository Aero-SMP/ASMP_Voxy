package me.cortex.voxy.client.lod;

import java.nio.file.*;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Random;
import me.cortex.voxy.client.core.rendering.SectionKey;
import static me.cortex.voxy.client.lod.CacheStartupBehaviorTest.*;

/** Real journal/codec fixture; faults alter actual bytes, never a substitute recovery algorithm. */
public final class CompletedSectionJournalBehaviorTest {
    public static void main(String[] args) throws Exception { run(); }
    static void run() throws Exception {
        var root = Files.createTempDirectory("voxy-completed-journal-");
        var path = root.resolve("r.0.0.vxlocal");
        try {
            var a = fixture(1, 1, 240, 1);
            var b = fixture(2, 1, 224, 2);
            var sa = LocalSection.from(a.index(), 340, a.catalog().fingerprint());
            var sb = LocalSection.from(b.index(), 340, b.catalog().fingerprint());
            byte[] prefix, complete;
            try (var journal = CompletedSectionJournal.open(path, WORLD, 0, true)) {
                journal.append(sa, a.payload());
                journal.flush(); prefix = Files.readAllBytes(path);
                check(journal.appendBytes(sa) == 0, "same content appended duplicate binding");
                for (int ordinal : new int[]{0, 256, 320, 336})
                    journal.append(LocalSection.from(a.index(), ordinal, a.catalog().fingerprint()), a.payload());
                check(journal.directory().size() == 5 && journal.payloadCount() == 1,
                        "all LOD positions did not deduplicate payload");
                journal.append(sb, b.payload());
                check(journal.previous(sb).equals(sa), "replacement lost predecessor");
                check(journal.catalogs().size() == 2, "recovery catalog dependency lost");
                complete = Files.readAllBytes(path);
            }
            // Every byte boundary of the replacement suffix, including payload-before-binding.
            for (int end = prefix.length; end <= complete.length; end++) {
                Files.write(path, Arrays.copyOf(complete, end));
                try (var journal = CompletedSectionJournal.open(path, WORLD, 0, true)) {
                    var current = journal.directory().get(KEY);
                    check(current.equals(end == complete.length ? sb : sa), "torn binding selected at " + end);
                    check(Arrays.equals(journal.get(current), end == complete.length ? b.payload() : a.payload()),
                            "recovered binding lacks payload at " + end);
                    check(journal.bytes() == Files.size(path), "tail repair did not charge actual extent");
                }
            }
            Files.write(path, complete);
            // Corrupt the newest binding: recover the preceding usable position directory.
            byte[] corrupt = complete.clone(); corrupt[corrupt.length - 20] ^= 1; Files.write(path, corrupt);
            try (var journal = CompletedSectionJournal.open(path, WORLD, 0, true)) {
                check(journal.directory().get(KEY).equals(sa), "bad binding CRC replaced predecessor");
            }
            Files.write(path, complete);
            try (var journal = CompletedSectionJournal.open(path, WORLD, 0, true)) {
                var absent = new LocalSection(KEY, LocalSection.ABSENT, 0, 0, 0, 0,
                        new RegionalProtocol.Fingerprint(0, 0), RegionalProtocol.Hash32.ZERO);
                journal.append(absent, null);
                check(journal.previous(sb) == null, "fallback crossed authoritative deletion");
            }
            try (var journal = CompletedSectionJournal.open(path, WORLD, 0, true)) {
                check(journal.directory().get(KEY).kind() == LocalSection.ABSENT, "deletion lost on restart");
                journal.append(sb, b.payload());
                check(journal.directory().get(KEY).equals(sb), "recreation did not supersede absence");
            }
            seeded(path, a, b);
            quarantineAndAba(root.resolve("aba.vxlocal"), a, b);
            boundedOptionalWrites(root.resolve("budget"), a, b);
            System.out.println("completed journal: all-LOD dedup, every truncation boundary, CRC, deletion, recreation and seeded replay passed");
        } finally { cleanup(root); }
    }

    private static void quarantineAndAba(Path path, Fixture a, Fixture b) throws Exception {
        var sa = LocalSection.from(a.index(), 340, a.catalog().fingerprint());
        var sb = LocalSection.from(b.index(), 340, b.catalog().fingerprint());
        try (var journal = CompletedSectionJournal.open(path, WORLD, 0, true)) {
            journal.append(sa, a.payload()); journal.append(sb, b.payload()); journal.append(sa, a.payload());
            check(journal.resolve(KEY, candidate -> candidate.equals(sb)).equals(sb), "ABA fallback cycled");
            long extent = journal.bytes();
            journal.quarantine(sa, false);
            check(journal.bytes() == extent && journal.previous(sa).equals(sb), "nonpersistent quarantine lost B");
            journal.quarantine(sb, false);
            check(journal.previous(sb) == null && journal.resolve(KEY, candidate -> true) == null,
                    "two bad ABA payloads remained eligible at full allowance");
        }
    }

    private static void boundedOptionalWrites(Path root, Fixture a, Fixture b) throws Exception {
        try (var metadata = new RegionalMetadataStore(root, true)) {
            awaitInventory(metadata.budget);
            metadata.saveCatalog(WORLD, DIMENSION, a.catalog(), metadata.budget.stamp(), () -> true);
            metadata.saveCatalog(WORLD, DIMENSION, b.catalog(), metadata.budget.stamp(), () -> true);
            var sa = LocalSection.from(a.index(), 340, a.catalog().fingerprint());
            var sb = LocalSection.from(b.index(), 340, b.catalog().fingerprint());
            try (var first = new CompletedSectionCache(metadata, WORLD, DIMENSION);
                 var second = new CompletedSectionCache(metadata, WORLD, DIMENSION)) {
                check(first.put(sa, a.payload(), () -> true), "initial cache commit failed");
                check(second.directory(0).get(KEY).equals(sa), "second handle did not recover A");
                check(second.put(sb, b.payload(), () -> true), "second handle could not replace A");
                check(first.directory(0).get(KEY).equals(sb), "other handle retained obsolete append state");
                check(!first.put(sa, a.payload(), () -> false) && first.directory(0).get(KEY).equals(sb),
                        "revoked completion overwrote newer binding");
                // Sparse fixture claims the allowance without allocating a 2 GiB test buffer.
                var reserved = root.resolve("legacy-reservation.vxcache");
                synchronized (metadata.budget) {
                    long remaining = metadata.budget.limit - metadata.budget.bytes;
                    try (var file = new java.io.RandomAccessFile(reserved.toFile(), "rw")) { file.setLength(remaining); }
                    metadata.budget.bytes += remaining;
                }
                long extent = Files.size(first.path(0));
                check(!first.put(sa, a.payload(), () -> true), "full disk allowance accepted optional append");
                check(Files.size(first.path(0)) == extent && first.directory(0).get(KEY).equals(sb)
                        && Arrays.equals(first.get(sb), b.payload()), "refused append destroyed completed content");
                check(Files.exists(reserved) && metadata.budget.bytes == metadata.budget.limit,
                        "prototype evicted legacy data or raised its disk ceiling");
                // Corrupt the actual B blob while the allowance is full. The in-memory
                // rejection must still allow A recovery without adding a tombstone.
                byte[] bytes = Files.readAllBytes(first.path(0));
                int bOffset = CompletedSectionJournal.HEADER_BYTES
                        + CompletedSectionJournal.FRAME_BYTES + CompletedSectionJournal.PAYLOAD_METADATA_BYTES
                        + a.payload().length + CompletedSectionJournal.FOOTER_BYTES
                        + CompletedSectionJournal.FRAME_BYTES + CompletedSectionJournal.BINDING_BYTES
                        + CompletedSectionJournal.FOOTER_BYTES
                        + CompletedSectionJournal.FRAME_BYTES + CompletedSectionJournal.PAYLOAD_METADATA_BYTES;
                bytes[bOffset] ^= 1;
                Files.write(first.path(0), bytes);
                first.quarantine(sb, null);
                check(first.previous(sb).equals(sa) && Files.size(first.path(0)) == extent,
                        "full-budget CRC failure could not recover A without disk writes");
                // A late failure must not remove a blob repaired before quarantine.
                synchronized (metadata.budget) {
                    long reservedBytes = Files.size(reserved);
                    try (var file = new java.io.RandomAccessFile(reserved.toFile(), "rw")) { file.setLength(0); }
                    metadata.budget.bytes -= reservedBytes;
                }
                check(first.put(sb, b.payload(), () -> true), "repair append was refused");
                first.quarantine(sb, null);
                check(Arrays.equals(first.get(sb), b.payload()), "late CRC failure removed repaired blob");
            }
        }
    }

    private static void seeded(Path path, Fixture a, Fixture b) throws Exception {
        for (long seed : new long[]{17, 8192, 65537}) {
            // Scoped fixture reset only: every seed has its own independent reference history.
            Files.delete(path);
            var expected = new HashMap<Long, LocalSection>();
            var random = new Random(seed);
            for (int batch = 0; batch < 10; batch++) {
                try (var journal = CompletedSectionJournal.open(path, WORLD, 0, true)) {
                    check(journal.directory().equals(expected), "reopen differs seed=" + seed + " batch=" + batch);
                    for (int i = 0; i < 100; i++) {
                        var f = random.nextBoolean() ? a : b;
                        int level = random.nextInt(5), side = 16 >> level;
                        long key = SectionKey.pack(level, random.nextInt(side), random.nextInt(4) - 2, random.nextInt(side));
                        var source = LocalSection.from(f.index(), 340, f.catalog().fingerprint());
                        var next = new LocalSection(key, source.kind(), source.children(), source.compressedBytes(),
                                source.canonicalBytes(), source.crc(), source.fingerprint(), source.catalog());
                        journal.append(next, f.payload()); expected.put(key, next);
                    }
                    check(journal.directory().equals(expected) && journal.payloadCount() <= 2,
                            "seeded dedup/position authority differs seed=" + seed + " batch=" + batch);
                }
            }
        }
    }
}
