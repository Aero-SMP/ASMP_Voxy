package me.cortex.voxy.client.lod;

import me.cortex.voxy.client.core.rendering.SectionKey;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import static me.cortex.voxy.client.lod.CacheStartupBehaviorTest.check;

/** Isolated feasibility gate, not a second production pipeline or a QUIC/renderer test. */
public final class UnifiedPayloadPrototypeTest {
    private static final RegionalProtocol.Hash32 WORLD = CacheStartupBehaviorTest.WORLD;
    private static final String DIMENSION = CacheStartupBehaviorTest.DIMENSION;
    private static Path fixtures;
    private record Fixture(String id, int wireRaw, long raw, long compressed, int children) {
        Path named() { return fixtures.resolve(id + ".named.zst"); }
        Path numeric() { return fixtures.resolve(id + ".wire.zst"); }
        CatalogCodec.Catalog catalog() throws Exception {
            return CatalogCodec.decode(Files.readAllBytes(fixtures.resolve((id.startsWith("sample_") ? "sample" : id) + ".catalog")));
        }
    }
    public static void main(String[] args) throws Exception {
        fixtures = Path.of(args[0]);
        var all = new ArrayList<Fixture>();
        for (String line : Files.readAllLines(fixtures.resolve("fixtures.tsv"))) {
            String[] f = line.split("\t");
            all.add(new Fixture(f[0], Integer.parseInt(f[1]), Long.parseLong(f[2]), Long.parseLong(f[3]), Integer.parseInt(f[4])));
        }
        for (var f : all.subList(0, 3)) correctness(f);
        failureCases(all.get(0), all.get(1));
        var sample = all.stream().filter(f -> f.id.startsWith("sample_")).toList();
        if (!sample.isEmpty()) {
            // Alternate order; all source bytes are OS-cached. Every run gets a fresh cache.
            for (int round = 0; round < 4; round++) {
                boolean staged = (round & 1) != 0;
                benchmark(sample, staged, round);
                benchmark(sample, !staged, round);
            }
            for (int mib : new int[]{32, 128}) for (int round = 0; round < 2; round++) {
                benchmark(sample, (round & 1) != 0, round, mib);
                benchmark(sample, (round & 1) == 0, round, mib);
            }
        }
        System.out.println("UNIFIED_GATE passed; excludes QUIC, registry handoff, mesh, GPU and durable fsync");
    }

    /** Only test-local staging; production has no body-stream handoff yet. */
    private static final class Staged extends InputStream implements CompletedSectionJournal.Source {
        final InputStream input;
        final RegionalDiskBudget budget;
        final Path path;
        final long length, raw;
        final BooleanSupplier current;
        Pacer pacer = new Pacer(0);
        final Blake3.Hasher received = new Blake3.Hasher(), copied = new Blake3.Hasher();
        RegionalDiskBudget.Pin pin;
        OutputStream output;
        InputStream replay;
        byte[] copyBuffer;
        byte[] receivedHash;
        long consumed, charged, stagedBytes, copiedBytes, peakBytes;
        int failures;
        boolean validated, closed, available;
        Staged(InputStream input, RegionalDiskBudget budget, Path path, Fixture f, BooleanSupplier current) {
            this.input = input; this.budget = budget; this.path = path;
            this.length = f.compressed; this.raw = f.raw; this.current = current;
            check(length > 0 && length <= LocalSectionCodec.compressedBound(raw), "invalid body extent");
            try {
                this.pin = budget.pin(path);
                this.output = Files.newOutputStream(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                this.available = true;
            } catch (IOException unavailable) { disable(unavailable); }
        }
        private void disable(IOException failure) {
            failures++;
            available = false;
            try { cleanup(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            System.out.println("UNIFIED_STAGE persistenceUnavailable=" + failure);
        }
        private void checkCurrent() throws IOException {
            if (!current.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("body owner cancelled");
        }
        @Override public int read() throws IOException { byte[] one = new byte[1]; return read(one, 0, 1) == -1 ? -1 : one[0] & 255; }
        @Override public int read(byte[] bytes, int offset, int count) throws IOException {
            checkCurrent();
            if (count == 0) return 0;
            if (consumed == length) return -1;
            int n = input.read(bytes, offset, (int) Math.min(count, length - consumed));
            if (n <= 0) throw new EOFException("truncated body");
            received.update(bytes, offset, n); consumed += n;
            if (available) try {
                budget.reserve(path, n); charged += n;
                peakBytes = Math.max(peakBytes, budget.bytes);
                pacer.bytes(n);
                output.write(bytes, offset, n); stagedBytes += n;
            } catch (IOException noSpace) { disable(noSpace); }
            return n;
        }
        void validated() throws IOException {
            check(consumed == length, "publication before complete body consumption");
            if (available) try { output.close(); output = null; }
            catch (IOException noSpace) { disable(noSpace); }
            receivedHash = received.digest();
            validated = true;
        }
        @Override public boolean step(OutputStream sink) throws IOException {
            checkCurrent();
            check(validated && available, "journal append before validated body");
            if (replay == null) {
                replay = Files.newInputStream(path);
                if (copyBuffer == null) copyBuffer = new byte[LocalSectionCodec.CHUNK_BYTES];
            }
            int n = replay.read(copyBuffer);
            if (n == -1) {
                check(copiedBytes == length && Arrays.equals(receivedHash, copied.digest()), "staged byte identity changed");
                return true;
            }
            sink.write(copyBuffer, 0, n); copied.update(copyBuffer, 0, n); copiedBytes += n;
            return false;
        }
        @Override public long canonicalBytes() { return raw; }
        private void cleanup() throws IOException {
            try {
                if (output != null) { try { output.close(); } finally { output = null; } }
            } finally {
                try { if (replay != null) { try { replay.close(); } finally { replay = null; } } }
                finally {
                    if (pin != null) {
                        pin.close(); pin = null;
                        // Remove tracked partial output and reconcile the pre-write charge.
                        check(budget.delete(path), "staging pin survived release");
                        charged = 0;
                    }
                }
            }
        }
        @Override public void close() throws IOException {
            if (closed) return;
            closed = true;
            try { input.close(); } finally { cleanup(); }
        }
    }
    private static InputStream split(Path path, int maximum) throws IOException {
        return new FilterInputStream(Files.newInputStream(path)) {
            int reads;
            @Override public int read(byte[] b, int o, int n) throws IOException {
                return super.read(b, o, Math.min(n, 1 + (reads++ % maximum)));
            }
        };
    }
    private static RegionalSectionCodec.Mappings mappings(CatalogCodec.Catalog catalog) {
        return new RegionalSectionCodec.Mappings(java.util.stream.IntStream.range(0, catalog.blocks().size()).toArray(),
                java.util.stream.IntStream.range(0, catalog.biomes().size()).toArray());
    }
    private static LocalSectionCodec.Names names(CatalogCodec.Catalog catalog) {
        var blocks = new HashMap<String, Integer>(); var biomes = new HashMap<String, Integer>();
        for (int i = 0; i < catalog.blocks().size(); i++) blocks.put(catalog.blocks().get(i).canonical(), i);
        for (int i = 0; i < catalog.biomes().size(); i++) biomes.put(catalog.biomes().get(i), i);
        return (name, biome) -> {
            Integer value = (biome ? biomes : blocks).get(name);
            if (value == null) throw new IOException("unknown fixture name");
            return value;
        };
    }
    private static LocalSection identity(int ordinal, byte[] compressed, byte[] wire, int children) {
        var fingerprint = RegionalProtocol.Fingerprint.read(ByteBuffer.wrap(Blake3.hash(wire)).order(ByteOrder.LITTLE_ENDIAN));
        // Each logical input is new; the benchmark must not skip same-token payload saves.
        return new LocalSection(SectionKey.pack(0, ordinal & 15, ordinal >>> 8, (ordinal >>> 4) & 15), LocalSection.DATA, children,
                compressed.length, wire.length, RegionalProtocol.crc32c(compressed), fingerprint,
                new RegionalProtocol.Hash32(ordinal + 1, 2, 3, 4));
    }
    private static void append(RegionalDiskBudget budget, CompletedSectionCache cache, LocalSection section,
                               Staged source) throws Exception {
        append(budget, cache, section, () -> source, source.pacer);
    }
    private static void append(RegionalDiskBudget budget, CompletedSectionCache cache, LocalSection section,
                               Callable<CompletedSectionJournal.Source> source, Pacer pacer) throws Exception {
        Path path = cache.path(section.region());
        try (var writer = budget.writer(path, () -> true); var pin = budget.pin(path)) {
            var journal = budget.journal(path, WORLD, section.region(), true);
            try (var owned = source.call(); var append = journal.begin(section, owned, new CompletedSectionJournal.Space() {
                public void reserve(long bytes) throws IOException { budget.reserve(path, bytes); pacer.bytes(bytes); }
                public void resized(long delta) { budget.resized(path, delta); }
            }, () -> true)) {
                while (!append.step()) {}
                if (owned instanceof Staged staged) staged.peakBytes = Math.max(staged.peakBytes, budget.bytes);
            }
        }
    }
    private static void correctness(Fixture f) throws Exception {
        Path root = Files.createTempDirectory(Path.of("project_audit"), "unified-correctness-");
        try (var metadata = new RegionalMetadataStore(root); var cache = new CompletedSectionCache(metadata, WORLD, DIMENSION);
             var codec = new LocalSectionCodec(); var numeric = new RegionalSectionCodec()) {
            CacheStartupBehaviorTest.awaitInventory(metadata.budget);
            var catalog = f.catalog(); var names = names(catalog);
            byte[] compressed = Files.readAllBytes(f.numeric()), wire = numeric.decompress(compressed, f.wireRaw);
            var section = identity(0, compressed, wire, f.children);
            var expected = numeric.decode(0, f.children, wire, section.fingerprint(), mappings(catalog));
            // Compression versions need not reproduce each other's bytes. The actual Rust
            // stored payload must survive transport/staging/journal unchanged.
            var generated = new Blake3.Hasher();
            try (var stored = Files.newInputStream(f.named())) {
                byte[] chunk = new byte[LocalSectionCodec.CHUNK_BYTES];
                for (int n; (n = stored.read(chunk)) != -1;) generated.update(chunk, 0, n);
            }
            long start = System.nanoTime();
            try (var staged = new Staged(split(f.named(), 8191), metadata.budget, root.resolve("worker.stage"), f, () -> true)) {
                var result = codec.decode(0, f.children, staged, f.compressed, f.raw, names);
                check(Arrays.equals(expected.cells(), result.cells()), "Rust/Java cell mismatch " + f.id);
                check(result.childMask() == f.children, "child mask mismatch");
                staged.validated();
                check(Arrays.equals(generated.digest(), staged.receivedHash), "stored/received compressed bytes mismatch " + f.id);
                append(metadata.budget, cache, section, staged);
                var cached = cache.get(section, codec, names);
                check(cached != null && Arrays.equals(expected.cells(), cached.cells()), "raw staged journal round trip");
                check(staged.failures == 0 && staged.stagedBytes == f.compressed && staged.copiedBytes == f.compressed,
                        "persistence byte accounting");
                System.out.println("UNIFIED_ROUNDTRIP id=" + f.id + " bytes=" + f.compressed + " ns=" + (System.nanoTime() - start)
                        + " stagedWrites=" + staged.stagedBytes + " stagedReads=" + staged.copiedBytes
                        + " journalPayloadWrites=" + staged.copiedBytes + " peakDisk=" + staged.peakBytes
                        + " codecNative=" + codec.nativeContextBytes());
            }
            check(!Files.exists(root.resolve("worker.stage")), "staging leaked");
            if (f.id.equals("ordinary")) for (int mask = 0; mask < 256; mask++) {
                try (var input = Files.newInputStream(f.named())) {
                    check(codec.decode(0, mask, input, f.compressed, f.raw, names).childMask() == mask,
                            "child topology changed for mask " + mask);
                }
            }
        } finally { CacheStartupBehaviorTest.cleanup(root); }
    }
    private static void failureCases(Fixture small, Fixture large) throws Exception {
        for (String mode : List.of("full", "read_only", "cancel", "truncate")) {
            var f = mode.equals("full") ? large : small;
            Path root = Files.createTempDirectory(Path.of("project_audit"), "unified-failure-");
            var budget = new RegionalDiskBudget(root, mode.equals("full") ? 256 * 1024 : RegionalDiskBudget.LIMIT);
            budget.retain();
            var dir = root.resolve("staging"); Files.createDirectory(dir);
            var permissions = Files.getPosixFilePermissions(dir);
            try (var codec = new LocalSectionCodec()) {
                CacheStartupBehaviorTest.awaitInventory(budget);
                if (mode.equals("read_only")) Files.setPosixFilePermissions(dir, java.nio.file.attribute.PosixFilePermissions.fromString("r-x------"));
                var live = new java.util.concurrent.atomic.AtomicBoolean(true);
                InputStream input = split(f.named(), 4093);
                if (mode.equals("truncate")) input = new ByteArrayInputStream(new byte[]{0x28, (byte)0xb5});
                try (var stage = new Staged(input, budget, dir.resolve("worker.stage"), f, live::get)) {
                    if (mode.equals("cancel")) { stage.read(new byte[17]); live.set(false); }
                    try {
                        var decoded = codec.decode(0, f.children, stage, f.compressed, f.raw, names(f.catalog()));
                        check(!mode.equals("cancel") && !mode.equals("truncate"), "invalid body decoded");
                        check(decoded.cells().length == 32768 && stage.failures == 1 && !stage.available, "optional save failure blocked load");
                        stage.validated();
                    } catch (IOException invalid) {
                        check(mode.equals("cancel") || mode.equals("truncate"), "cache failure blocked valid decode: " + invalid);
                        check(!stage.validated, "invalid input became publishable");
                    }
                }
                check(budget.bytes == Files.size(root.resolve("cache-format")), "failed stage escaped accounting");
                check(!Files.exists(dir.resolve("worker.stage")), "terminal staging leak");
                System.out.println("UNIFIED_FAILURE mode=" + mode + " passed");
            } finally {
                Files.setPosixFilePermissions(dir, permissions); budget.release(); CacheStartupBehaviorTest.cleanup(root);
            }
        }
    }
    private static void benchmark(List<Fixture> sample, boolean staged, int round) throws Exception {
        benchmark(sample, staged, round, 0);
    }
    /** Write-bandwidth sensitivity model, NOT a measurement of a remote client's disk.
     * One shared byte clock, 128 KiB burst; no per-file latency or read penalty is injected.
     */
    private static final class Pacer {
        final long rate;
        long deadline, charged;
        Pacer(int mib) { rate = mib * 1024L * 1024; }
        void bytes(long count) throws IOException {
            if (rate == 0) return;
            long until;
            synchronized (this) {
                long now = System.nanoTime(), burst = LocalSectionCodec.CHUNK_BYTES * 1_000_000_000L / rate;
                deadline = Math.max(deadline, now - burst) + count * 1_000_000_000L / rate;
                charged += count; until = deadline;
            }
            while (until > System.nanoTime()) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("paced write cancelled");
                java.util.concurrent.locks.LockSupport.parkNanos(until - System.nanoTime());
            }
        }
    }
    private static void benchmark(List<Fixture> sample, boolean staged, int round, int writeMiB) throws Exception {
        Path root = Files.createTempDirectory(Path.of("project_audit"), "unified-benchmark-");
        var catalog = sample.get(0).catalog(); var names = names(catalog); var mappings = mappings(catalog);
        var sections = new LocalSection[sample.size()];
        try (var numeric = new RegionalSectionCodec()) {
            for (int i = 0; i < sample.size(); i++) {
                var f = sample.get(i); byte[] compressed = Files.readAllBytes(f.numeric());
                sections[i] = identity(i, compressed, numeric.decompress(compressed, f.wireRaw), f.children);
            }
        }
        int workers = 14; var next = new AtomicInteger();
        var pacer = new Pacer(writeMiB);
        var pool = Executors.newFixedThreadPool(workers);
        var os = (com.sun.management.OperatingSystemMXBean) java.lang.management.ManagementFactory.getOperatingSystemMXBean();
        var allocations = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
        long cpuStart = os.getProcessCpuTime();
        long start = System.nanoTime();
        try (var metadata = new RegionalMetadataStore(root); var cache = new CompletedSectionCache(metadata, WORLD, DIMENSION)) {
            CacheStartupBehaviorTest.awaitInventory(metadata.budget); cache.directory(0);
            var tasks = new ArrayList<Future<long[]>>();
            for (int w = 0; w < workers; w++) {
                final int worker = w;
                tasks.add(pool.submit(() -> {
                    long transferred = 0, stageBytes = 0, waitNs = 0;
                    long allocatedStart = allocations.getThreadAllocatedBytes(Thread.currentThread().threadId());
                    byte[] copyBuffer = staged ? new byte[LocalSectionCodec.CHUNK_BYTES] : null;
                    try (var codec = new LocalSectionCodec(); var numeric = new RegionalSectionCodec()) {
                        for (int i; (i = next.getAndIncrement()) < sample.size();) {
                            var f = sample.get(i);
                            var section = sections[i];
                            if (staged) {
                                try (var body = new Staged(Files.newInputStream(f.named()), metadata.budget,
                                        root.resolve("worker-" + worker + ".stage"), f, () -> true)) {
                                    body.pacer = pacer;
                                    body.copyBuffer = copyBuffer;
                                    codec.decode(section.key(), section.children(), body, f.compressed, f.raw, names);
                                    body.validated();
                                    check(body.available, "benchmark persistence unexpectedly unavailable");
                                    long begin = System.nanoTime(); append(metadata.budget, cache, section, body); waitNs += System.nanoTime() - begin;
                                    transferred += f.compressed; stageBytes += body.stagedBytes;
                                }
                            } else {
                                byte[] compressed = Files.readAllBytes(f.numeric()), wire = numeric.decompress(compressed, f.wireRaw);
                                numeric.decode(section.key(), section.children(), wire, section.fingerprint(), mappings);
                                long begin = System.nanoTime();
                                if (writeMiB == 0) cache.save(section, codec, wire, catalog, () -> true, null);
                                else append(metadata.budget, cache, section, () -> codec.encode(wire, catalog), pacer);
                                waitNs += System.nanoTime() - begin;
                                transferred += compressed.length;
                            }
                        }
                    }
                    return new long[]{transferred, stageBytes, waitNs,
                            allocations.getThreadAllocatedBytes(Thread.currentThread().threadId()) - allocatedStart};
                }));
            }
            long transferred = 0, stageBytes = 0, waitNs = 0, allocated = 0;
            for (var task : tasks) {
                var values = task.get(90, TimeUnit.SECONDS); transferred += values[0]; stageBytes += values[1]; waitNs += values[2];
                allocated += values[3];
            }
            System.out.println("UNIFIED_BENCH staged=" + staged + " round=" + round + " workers=" + workers + " sections=" + sample.size()
                    + " simulatedWriteMiB=" + writeMiB + " pacedBytes=" + pacer.charged
                    + " wallNs=" + (System.nanoTime() - start) + " transferred=" + transferred + " stageWrites=" + stageBytes
                    + " processCpuNs=" + (os.getProcessCpuTime() - cpuStart) + " workerAllocatedBytes=" + allocated
                    + " journalBytes=" + Files.size(cache.path(0)) + " aggregateSaveNs=" + waitNs);
        } finally {
            pool.shutdownNow(); check(pool.awaitTermination(10, TimeUnit.SECONDS), "benchmark worker leak");
            CacheStartupBehaviorTest.cleanup(root);
        }
    }
}
