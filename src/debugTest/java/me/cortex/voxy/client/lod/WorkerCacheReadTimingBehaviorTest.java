package me.cortex.voxy.client.lod;

import me.cortex.voxy.client.core.model.CatalogMapper;
import me.cortex.voxy.client.core.rendering.SectionKey;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.List;

/** Actual local journal/streaming codec behavior with debug timing enabled. */
public final class WorkerCacheReadTimingBehaviorTest {
    public static void main(String[] args) throws Exception {
        Path parent = Path.of("build", "worker-cache-read-timing-test").toAbsolutePath();
        Files.createDirectories(parent);
        Path scratch = Files.createTempDirectory(parent, "journal-");
        boolean passed = false;
        try {
            journalReadAndQuarantine(scratch); passed = true;
            System.out.println("WorkerCacheReadTimingBehaviorTest PASS");
        } finally {
            // Preserve a failed fixture for review. Never touch the user's cache.
            if (passed) try (var files = Files.walk(scratch)) {
                for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static int cell(WorkerDebugTelemetry.Stage stage) {
        return WorkerDebugTelemetry.Source.CACHE.ordinal() * WorkerDebugTelemetry.Stage.values().length + stage.ordinal();
    }
    private static void journalReadAndQuarantine(Path scratch) throws Exception {
        byte[] canonical = ByteBuffer.allocate(11).order(ByteOrder.LITTLE_ENDIAN)
                .putShort((short) 1).putInt(0).putInt(0).put((byte) 0xab).array();
        var catalog = new CatalogCodec.Catalog(1, 2, 3,
                List.of(new CatalogCodec.Block("minecraft:stone", 15, true)), List.of("minecraft:plains"));
        var hash = RegionalProtocol.Fingerprint.read(ByteBuffer.wrap(new Blake3.Hasher().update(canonical).digest()).order(ByteOrder.LITTLE_ENDIAN));
        long key = SectionKey.pack(0, 0, 0, 0);
        var section = new LocalSection(key, LocalSection.DATA, 0x65, 1, canonical.length, 0, hash,
                new RegionalProtocol.Hash32(5, 6, 7, 8));
        Path file = scratch.resolve("r.0.0.vxlocal");
        var space = new CompletedSectionJournal.Space() {
            @Override public void reserve(long bytes) { check(bytes >= 0, "negative append accounting"); }
            @Override public void resized(long delta) {}
            @Override public void commit(CompletedSectionJournal.IOAction action) throws IOException { action.run(); }
            @Override public void failed(IOException failure) {}
        };
        try (var journal = CompletedSectionJournal.open(file, new RegionalProtocol.Hash32(1, 2, 3, 4), 0, true);
             var codec = new LocalSectionCodec()) {
            try (var encoder = codec.encode(canonical, catalog); var append = journal.begin(section, encoder, space, () -> true)) {
                int steps = 0;
                while (!append.step()) check(++steps < 100, "fixture encoder did not finish");
            }
            var work = new WorkerDebugTelemetry.Work(1, 0, Thread.currentThread().threadId());
            work.begin(1, "SectionWorkerTask", key, 1, 1, "CACHE"); work.stage("CACHE_READ");
            var decoded = journal.get(section, codec, (name, biome) -> {
                check(name.equals(biome ? "minecraft:plains" : "minecraft:stone"), "decoded name changed");
                int previous = ClientLodDebug.workerPush(work, "NAME_RESOLUTION_WAIT");
                try { return biome ? 9 : 7; }
                finally { ClientLodDebug.workerPop(work, previous); }
            }, work);
            check(decoded != null && decoded.key() == key && decoded.childMask() == 0x65, "journal decoding context changed");
            check(decoded.usedBlocks().length == 1 && decoded.usedBlocks()[0] == 7, "decoded block set changed");
            long expected = CatalogMapper.composeMappingId((byte) 0xab, 7, 9);
            for (long value : decoded.cells()) check(value == expected, "cell changed by timing instrumentation");
            check(work.stage == WorkerDebugTelemetry.Stage.CACHE_READ, "nested success did not restore outer cache stage");
            work.end();
            var success = work.copy();
            for (var stage : new WorkerDebugTelemetry.Stage[] {WorkerDebugTelemetry.Stage.CACHE_FILE_OPEN,
                    WorkerDebugTelemetry.Stage.CACHE_FILE_READ, WorkerDebugTelemetry.Stage.CACHE_DECOMPRESS,
                    WorkerDebugTelemetry.Stage.CACHE_NAMES, WorkerDebugTelemetry.Stage.CACHE_DECODE,
                    WorkerDebugTelemetry.Stage.CACHE_INTEGRITY, WorkerDebugTelemetry.Stage.NAME_RESOLUTION_WAIT}) {
                check(success.lifetime().count[cell(stage)] > 0, "actual cache path missing timing " + stage);
            }
            check(success.outcomes()[WorkerDebugTelemetry.Outcome.CACHE_FILE_BYTES.ordinal()] == codec.decodedBytes(),
                    "logical file read byte counter differs from decoded compressed extent");
            check(success.outcomes()[WorkerDebugTelemetry.Outcome.CACHE_CANONICAL_BYTES.ordinal()] > canonical.length,
                    "local canonical extent was not counted");

            // Damage the actual compressed frame. The original rejection/quarantine and
            // codec busy-guard cleanup must survive nested timing exceptional exits.
            long offset = CompletedSectionJournal.HEADER_BYTES + CompletedSectionJournal.FRAME_BYTES
                    + CompletedSectionJournal.PAYLOAD_METADATA_BYTES;
            try (var channel = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
                var byteBuffer = ByteBuffer.allocate(1);
                check(channel.read(byteBuffer, offset) == 1, "fixture compressed byte missing");
                byteBuffer.flip(); byteBuffer.put(0, (byte) (byteBuffer.get(0) ^ 1));
                check(channel.write(byteBuffer, offset) == 1, "fixture compressed byte not changed");
            }
            work.begin(2, "SectionWorkerTask", key, 2, 1, "CACHE"); work.stage("CACHE_READ");
            try { journal.get(section, codec, (name, biome) -> biome ? 9 : 7, work); throw new AssertionError("corrupt local frame accepted"); }
            catch (IOException expectedFailure) {
                check(work.stage == WorkerDebugTelemetry.Stage.CACHE_READ, "nested failure did not restore outer cache stage");
            }
            check(!journal.hasPayload(section), "corrupt payload was not quarantined");
            check(journal.get(section, codec, (name, biome) -> biome ? 9 : 7, work) == null,
                    "quarantined payload remained readable");
            codec.readHash(); // Busy guard restored after decompression failure.
            work.end();
        }
    }
}
