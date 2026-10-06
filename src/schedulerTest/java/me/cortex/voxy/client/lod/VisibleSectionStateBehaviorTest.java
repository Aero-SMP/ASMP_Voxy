package me.cortex.voxy.client.lod;

import com.sun.management.ThreadMXBean;
import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.longs.LongSet;
import me.cortex.voxy.client.core.rendering.SectionKey;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

/** Offline causal/delta oracle and an opt-in comparison with the former Session rebuild. */
public final class VisibleSectionStateBehaviorTest {
    private static volatile long benchmarkSink;

    public static void main(String[] args) {
        run();
        if (Arrays.asList(args).contains("--benchmark") && !Boolean.getBoolean("voxy.visibleStateBench"))
            benchmark();
    }

    public static void run() {
        handWrittenTransitions();
        coordinateBoundaries();
        readOnlyLiveViews();
        largeShrink();
        repeatedShrinkGrow();
        randomizedTransitions();
        System.out.println("visible state memberships, exact deltas, shared references, causal epochs and randomized oracle tests passed");
        if (Boolean.getBoolean("voxy.visibleStateBench")) benchmark();
    }

    private static void handWrittenTransitions() {
        Harness h = new Harness("hand-written");
        h.assertState();
        h.step(-1, new long[]{0}); // Rejected even before the first accepted report.
        h.step(0, new long[]{0, 0, 0});
        check(h.actual.keys().size() == 1 && h.actual.watchKeys().size() == 5
                && h.actual.regions().size() == 1, "zero key or duplicate reference handling");
        h.step(1, new long[]{0}); // Accepted, but generation must remain unchanged.
        h.step(2, new long[]{0, 0});
        h.step(2, new long[]{SectionKey.pack(0, 100, 0, 100)});
        h.step(1, new long[0]); // Stale reports must not clear the preceding accepted deltas.

        long a = SectionKey.pack(0, -1, -1, -1);
        long b = SectionKey.pack(0, -2, -1, -2);
        long parent = SectionKey.pack(1, -1, -1, -1);
        long otherRegion = SectionKey.pack(0, 16, 0, -17);
        h.step(10, new long[]{a, b, parent, otherRegion, a});
        h.step(11, new long[]{otherRegion, parent, b, a}); // Reorder only.
        h.step(12, new long[]{a, parent, otherRegion}); // Shared ancestor survives b removal.
        h.step(13, new long[]{parent, otherRegion}); // Parent/child swap retains shared watches.
        h.step(14, new long[]{a, b, otherRegion}); // Remove an explicitly visible parent.
        h.step(15, new long[]{b, otherRegion}); // Shared region survives a removal.
        h.step(16, new long[]{otherRegion}); // Last reference removes the negative region.
        h.step(17, new long[0]);
        h.step(18, new long[0]);
        h.step(19, new long[]{SectionKey.pack(4, -1, -1, -1)});
        check(h.actual.watchKeys().size() == 1, "top-level key acquired a nonexistent ancestor");
        h.step(1L << 40, new long[]{0, a}); // Epochs are long, not truncated renderer ints.
        h.step(Long.MAX_VALUE, new long[]{b});
        h.step(Long.MAX_VALUE, new long[0]);
        h.step(Long.MAX_VALUE - 1, new long[]{0});
    }

    private static void coordinateBoundaries() {
        Harness h = new Harness("coordinate-boundaries");
        int[] coordinates = {-8_388_608, -33, -17, -16, -15, -2, -1, 0, 1, 15, 16, 17, 8_388_607};
        List<Long> values = new ArrayList<>();
        for (int level = 0; level <= SectionKey.MAX_LOD_LAYER; level++)
            for (int x : coordinates) for (int z : coordinates)
                for (int y : new int[]{-128, -1, 0, 127}) values.add(SectionKey.pack(level, x, y, z));
        long[] keys = array(values);
        h.step(0, keys);
        shuffle(keys, new Random(42));
        h.step(1, keys);
        h.step(2, Arrays.copyOfRange(keys, keys.length / 2, keys.length));
        h.step(3, new long[0]);
    }

    private static void largeShrink() {
        Harness h = new Harness("large-shrink");
        long[] keys = grid(20_000);
        h.step(0, keys);
        h.step(1, Arrays.copyOf(keys, 7));
        h.step(1, keys); // Rejection leaves the large removal lists intact.
        h.step(2, Arrays.copyOf(keys, 7)); // An accepted no-op clears all delta lists.
        h.step(3, new long[]{0, 0});
        h.step(4, new long[0]);
    }

    private static void readOnlyLiveViews() {
        Harness h = new Harness("live-views");
        LongSet keys = h.actual.keys(), watches = h.actual.watchKeys(), regions = h.actual.regions();
        LongList added = h.actual.addedKeys(), removed = h.actual.removedKeys();
        h.step(0, new long[]{0});
        check(keys.contains(0) && watches.contains(0) && regions.contains(0) && added.contains(0),
                "previously obtained views did not observe an accepted report");
        expectReadOnly(() -> keys.add(17));
        expectReadOnly(() -> watches.remove(0));
        expectReadOnly(() -> regions.clear());
        expectReadOnly(() -> added.add(17));
        expectReadOnly(() -> removed.clear());
        h.assertState();
        h.step(1, new long[0]);
        check(keys.isEmpty() && watches.isEmpty() && regions.isEmpty()
                && added.isEmpty() && removed.contains(0), "live views did not observe a later report");
    }

    private static void repeatedShrinkGrow() {
        Harness h = new Harness("repeated-shrink-grow");
        long[] grid = grid(2_048);
        long negative = SectionKey.pack(0, -17, -1, -33);
        long sibling = SectionKey.pack(0, -18, -1, -34);
        long parent = SectionKey.pack(1, -9, -1, -17);
        long epoch = 0;
        for (int cycle = 0; cycle < 30; cycle++) {
            long[] large = new long[grid.length + 4];
            int offset = 1024 * (cycle % 4 + 1);
            for (int i = 0; i < grid.length; i++) large[i] = SectionKey.pack(0,
                    SectionKey.x(grid[i]) - offset, SectionKey.y(grid[i]), SectionKey.z(grid[i]) - offset);
            large[grid.length] = 0; large[grid.length + 1] = 0;
            large[grid.length + 2] = negative; large[grid.length + 3] = sibling;
            h.step(epoch++, large);
            long[] tiny = cycle % 2 == 0 ? new long[]{0, negative, parent, 0} : new long[]{negative};
            h.step(epoch++, tiny);
            h.step(epoch++, tiny.clone());
            h.step(epoch - 2, large);
            if (cycle % 3 == 0) h.step(epoch++, new long[0]);
        }
        h.step(epoch, new long[0]);
    }

    private static void randomizedTransitions() {
        for (long seed : new long[]{1, 0x264L, 918273645L}) {
            Random random = new Random(seed);
            Harness h = new Harness("random-" + seed);
            long epoch = 0;
            long[] current = new long[0];
            for (int turn = 0; turn < 1_000; turn++) {
                List<Long> proposed = new ArrayList<>();
                int mode = random.nextInt(8);
                if (mode < 6) for (long key : current) proposed.add(key);
                if (mode == 0) proposed.clear();
                if (mode == 1 && !proposed.isEmpty())
                    proposed.subList(random.nextInt(proposed.size()), proposed.size()).clear();
                if (mode > 2 || proposed.isEmpty()) {
                    for (int i = 0, edits = 1 + random.nextInt(20); i < edits; i++) {
                        if (!proposed.isEmpty() && random.nextBoolean()) proposed.remove(random.nextInt(proposed.size()));
                        else proposed.add(randomKey(random));
                    }
                }
                if (proposed.size() > 384) proposed.subList(384, proposed.size()).clear();
                if (!proposed.isEmpty()) for (int i = 0, copies = random.nextInt(8); i < copies; i++)
                    proposed.add(proposed.get(random.nextInt(proposed.size())));
                if (turn % 23 == 0) proposed.add(0L);
                long[] input = array(proposed);
                shuffle(input, random);
                if (turn % 7 == 0 && epoch > 0) {
                    h.step(h.oracle.reportEpoch, input);
                    h.step(h.oracle.reportEpoch - 1, input);
                } else {
                    epoch += 1 + random.nextInt(17); // Skipped reports need no consecutive-epoch assumption.
                    h.step(epoch, input);
                    current = input;
                }
            }
            h.step(epoch + 100, new long[0]);
        }
    }

    private static final class Harness {
        final String name;
        final VisibleSectionState actual = new VisibleSectionState();
        final Oracle oracle = new Oracle();
        int reports;

        Harness(String name) { this.name = name; }

        void step(long epoch, long[] input) {
            long[] original = input.clone();
            boolean accepted = oracle.update(epoch, input);
            check(actual.update(epoch, input) == accepted, label() + " acceptance");
            check(Arrays.equals(input, original), label() + " mutated report input");
            reports++;
            assertState();
        }

        String label() { return name + " report " + reports; }

        void assertState() {
            check(actual.reportEpoch() == oracle.reportEpoch, label() + " report epoch");
            check(actual.baseGeneration() == oracle.baseGeneration, label() + " base generation");
            check(actual.generation() == oracle.generation, label() + " membership generation");
            same(actual.keys(), oracle.keys, label() + " keys");
            same(actual.watchKeys(), oracle.watches, label() + " watches");
            same(actual.regions(), oracle.regions, label() + " regions");
            same(actual.addedKeys(), oracle.addedKeys, label() + " added keys");
            same(actual.removedKeys(), oracle.removedKeys, label() + " removed keys");
            same(actual.addedWatchKeys(), oracle.addedWatches, label() + " added watches");
            same(actual.removedWatchKeys(), oracle.removedWatches, label() + " removed watches");
            same(actual.addedRegions(), oracle.addedRegions, label() + " added regions");
            same(actual.removedRegions(), oracle.removedRegions, label() + " removed regions");
        }
    }

    /** Independent full rebuild: no helper parent/refcount/delta implementation is reused. */
    private static final class Oracle {
        long reportEpoch = -1, baseGeneration, generation;
        Set<Long> keys = new HashSet<>(), watches = new HashSet<>(), regions = new HashSet<>();
        Set<Long> addedKeys = new HashSet<>(), removedKeys = new HashSet<>();
        Set<Long> addedWatches = new HashSet<>(), removedWatches = new HashSet<>();
        Set<Long> addedRegions = new HashSet<>(), removedRegions = new HashSet<>();

        boolean update(long epoch, long[] report) {
            if (epoch <= reportEpoch) return false;
            Set<Long> nextKeys = new HashSet<>(), nextWatches = new HashSet<>(), nextRegions = new HashSet<>();
            for (long key : report) {
                nextKeys.add(key);
                int level = SectionKey.level(key);
                int x = SectionKey.x(key), y = SectionKey.y(key), z = SectionKey.z(key);
                for (int ancestor = level; ancestor <= SectionKey.MAX_LOD_LAYER; ancestor++) {
                    int divisor = 1 << (ancestor - level);
                    nextWatches.add(SectionKey.pack(ancestor, Math.floorDiv(x, divisor),
                            Math.floorDiv(y, divisor), Math.floorDiv(z, divisor)));
                }
                int divisor = 1 << (SectionKey.MAX_LOD_LAYER - level);
                nextRegions.add(Integer.toUnsignedLong(Math.floorDiv(x, divisor))
                        | Integer.toUnsignedLong(Math.floorDiv(z, divisor)) << 32);
            }
            baseGeneration = generation;
            if (!keys.equals(nextKeys)) generation++;
            addedKeys = difference(nextKeys, keys); removedKeys = difference(keys, nextKeys);
            addedWatches = difference(nextWatches, watches); removedWatches = difference(watches, nextWatches);
            addedRegions = difference(nextRegions, regions); removedRegions = difference(regions, nextRegions);
            keys = nextKeys; watches = nextWatches; regions = nextRegions; reportEpoch = epoch;
            return true;
        }
    }

    /** Actual former ClientSession algorithm, including boxed HashSets and the raw duplicate loop. */
    private static final class Legacy {
        final Set<Long> keys = new HashSet<>(), watches = new HashSet<>(), regions = new HashSet<>();
        final Set<Long> interests = new HashSet<>();

        void update(long[] report) {
            interests.clear(); // Model consumption between reports; both implementations use this same queue.
            Set<Long> previous = new HashSet<>(watches);
            keys.clear(); watches.clear(); regions.clear();
            for (long key : report) {
                keys.add(key); regions.add(legacyRegion(key));
                long ancestor = key;
                while (true) {
                    watches.add(ancestor);
                    if (SectionKey.level(ancestor) == SectionKey.MAX_LOD_LAYER) break;
                    ancestor = legacyParent(ancestor);
                }
            }
            for (long key : watches) if (!previous.remove(key)) interests.add(key);
            for (long key : previous) interests.add(key);
        }
    }

    private static final class Incremental {
        final VisibleSectionState state = new VisibleSectionState();
        final Set<Long> interests = new HashSet<>();

        void update(long epoch, long[] report) {
            interests.clear();
            check(state.update(epoch, report), "benchmark reports must have increasing epochs");
            for (int i = 0; i < state.addedWatchKeys().size(); i++) interests.add(state.addedWatchKeys().getLong(i));
            for (int i = 0; i < state.removedWatchKeys().size(); i++) interests.add(state.removedWatchKeys().getLong(i));
        }
    }

    private record Scenario(String name, long[] initial, long[][] reports) {}
    private record Measurement(long nanos, long allocated, int reports, long[] stageNanos, long[] stageAllocated) {}

    private static void benchmark() {
        int size = Integer.getInteger("voxy.visibleStateBenchSize", 32_768);
        int warmup = Integer.getInteger("voxy.visibleStateBenchWarmup", 6);
        int samples = Integer.getInteger("voxy.visibleStateBenchSamples", 5);
        int repetitions = Integer.getInteger("voxy.visibleStateBenchRepetitions", 3);
        check(size >= 100 && size <= 262_144 && warmup >= 1 && warmup <= 100
                && samples >= 1 && samples <= 100 && repetitions >= 1 && repetitions <= 100,
                "benchmark settings outside supported bounds");
        var management = ManagementFactory.getThreadMXBean();
        ThreadMXBean allocation = management instanceof ThreadMXBean bean ? bean : null;
        if (allocation != null && allocation.isThreadAllocatedMemorySupported()
                && !allocation.isThreadAllocatedMemoryEnabled()) {
            try { allocation.setThreadAllocatedMemoryEnabled(true); }
            catch (UnsupportedOperationException | SecurityException unavailable) { allocation = null; }
        }
        if (allocation != null && (!allocation.isThreadAllocatedMemorySupported()
                || !allocation.isThreadAllocatedMemoryEnabled())) allocation = null;
        if (allocation != null && allocated(allocation) < 0) allocation = null;
        List<Scenario> scenarios = scenarios(size);
        for (Scenario scenario : scenarios) verifyScenario(scenario);
        System.out.println("VISIBLE_BENCH configuration uniqueInput=" + size + " warmupRounds=" + warmup
                + " samples=" + samples + " repetitions=" + repetitions
                + " allocation=" + (allocation == null ? "unsupported" : "ThreadMXBean")
                + " baseline=actual-java-HashSet-rebuild enqueue=java-HashSet both");
        System.out.println("VISIBLE_BENCH allocation is thread-allocated heap, not retained heap; initialization, oracle checks and fixture generation are outside timings");
        System.out.println("VISIBLE_BENCH retained-state bound is O(peak keys + peak watches + peak regions + peak delta entries); reusable list capacity can remain after shrink, not grow with unchanged report count");
        for (Scenario scenario : scenarios) {
            System.out.println("VISIBLE_BENCH trace scenario=" + scenario.name + " initialized_keys=" + scenario.initial.length
                    + " timed_reports=" + scenario.reports.length + " raw_report_lengths="
                    + Arrays.toString(Arrays.stream(scenario.reports).mapToInt(report -> report.length).toArray()));
            for (int i = 0; i < warmup; i++) {
                measureLegacy(scenario, null); measureIncremental(scenario, null);
            }
            for (int sample = 0; sample < samples; sample++) {
                long oldNanos = 0, newNanos = 0, oldBytes = 0, newBytes = 0;
                long[] oldStages = new long[scenario.reports.length], newStages = new long[scenario.reports.length];
                long[] oldStageBytes = new long[scenario.reports.length], newStageBytes = new long[scenario.reports.length];
                int count = 0;
                for (int repetition = 0; repetition < repetitions; repetition++) {
                    Measurement old, next;
                    if ((sample + repetition) % 2 == 0) {
                        old = measureLegacy(scenario, allocation); next = measureIncremental(scenario, allocation);
                    } else {
                        next = measureIncremental(scenario, allocation); old = measureLegacy(scenario, allocation);
                    }
                    oldNanos += old.nanos; newNanos += next.nanos;
                    oldBytes += old.allocated; newBytes += next.allocated; count += old.reports;
                    if (old.stageNanos != null) for (int stage = 0; stage < old.stageNanos.length; stage++) {
                        oldStages[stage] += old.stageNanos[stage]; newStages[stage] += next.stageNanos[stage];
                        oldStageBytes[stage] += old.stageAllocated[stage]; newStageBytes[stage] += next.stageAllocated[stage];
                    }
                }
                System.out.printf(Locale.ROOT,
                        "VISIBLE_BENCH scenario=%s sample=%d reports=%d legacy_ns/report=%.1f incremental_ns/report=%.1f legacy_bytes/report=%.1f incremental_bytes/report=%.1f%n",
                        scenario.name, sample, count, (double) oldNanos / count, (double) newNanos / count,
                        allocation == null ? -1.0 : (double) oldBytes / count,
                        allocation == null ? -1.0 : (double) newBytes / count);
                // Separately expose first-load and replacement costs rather than averaging them into a claim.
                if (scenario.initial.length == 0) for (int stage = 0; stage < scenario.reports.length; stage++)
                    System.out.printf(Locale.ROOT,
                            "VISIBLE_BENCH stage scenario=%s sample=%d report_index=%d legacy_ns/report=%.1f incremental_ns/report=%.1f legacy_bytes/report=%.1f incremental_bytes/report=%.1f%n",
                            scenario.name, sample, stage, (double) oldStages[stage] / repetitions,
                            (double) newStages[stage] / repetitions,
                            allocation == null ? -1.0 : (double) oldStageBytes[stage] / repetitions,
                            allocation == null ? -1.0 : (double) newStageBytes[stage] / repetitions);
            }
            reportLogicalState(scenario);
        }
    }

    private static List<Scenario> scenarios(int size) {
        long[] base = grid(size);
        long[][] unchanged = new long[8][], reordered = new long[8][], churn = new long[8][];
        long[] changed = base.clone();
        long[] replacement = base.clone();
        int edits = Math.max(1, size / 100);
        for (int i = 0; i < edits; i++) changed[i] = SectionKey.pack(0,
                SectionKey.x(base[i]) + 8192, SectionKey.y(base[i]), SectionKey.z(base[i]) - 8192);
        for (int i = 0; i < replacement.length; i++) replacement[i] = SectionKey.pack(0,
                SectionKey.x(base[i]) + 16384, SectionKey.y(base[i]), SectionKey.z(base[i]) - 16384);
        Random random = new Random(0x264BEEFL);
        for (int i = 0; i < 8; i++) {
            unchanged[i] = base;
            reordered[i] = base.clone(); shuffle(reordered[i], random);
            churn[i] = (i % 2 == 0 ? changed : base).clone(); shuffle(churn[i], random);
        }
        return List.of(new Scenario("unchanged", base, unchanged), new Scenario("reorder", base, reordered),
                new Scenario("one-percent-churn-" + edits + "-removed-and-added", base, churn),
                new Scenario("shrink-to-one-percent", base, new long[][]{Arrays.copyOf(base, edits)}),
                new Scenario("initial-empty-to-large", new long[0], new long[][]{base}),
                new Scenario("full-replacement-from-empty", new long[0], new long[][]{base, replacement}));
    }

    private static void verifyScenario(Scenario scenario) {
        Harness oracle = new Harness("benchmark-" + scenario.name);
        Legacy legacy = new Legacy();
        Incremental incremental = new Incremental();
        oracle.step(0, scenario.initial); legacy.update(scenario.initial); incremental.update(0, scenario.initial);
        compare(legacy, incremental, oracle.oracle, scenario.name + " initial");
        for (int i = 0; i < scenario.reports.length; i++) {
            oracle.step(i + 1, scenario.reports[i]);
            legacy.update(scenario.reports[i]); incremental.update(i + 1, scenario.reports[i]);
            compare(legacy, incremental, oracle.oracle, scenario.name + " report " + i);
        }
    }

    private static void compare(Legacy legacy, Incremental incremental, Oracle oracle, String label) {
        check(legacy.keys.equals(oracle.keys) && legacy.watches.equals(oracle.watches)
                && legacy.regions.equals(oracle.regions), label + " legacy oracle mismatch");
        same(incremental.state.keys(), legacy.keys, label + " candidate keys");
        same(incremental.state.watchKeys(), legacy.watches, label + " candidate watches");
        same(incremental.state.regions(), legacy.regions, label + " candidate regions");
        Set<Long> expectedInterests = new HashSet<>(oracle.addedWatches); expectedInterests.addAll(oracle.removedWatches);
        check(legacy.interests.equals(expectedInterests) && incremental.interests.equals(expectedInterests),
                label + " interest transition mismatch");
    }

    private static Measurement measureLegacy(Scenario scenario, ThreadMXBean bean) {
        Legacy state = new Legacy(); state.update(scenario.initial);
        if (scenario.initial.length == 0) {
            long[] stages = new long[scenario.reports.length], stageBytes = new long[scenario.reports.length];
            long nanos = 0, bytes = 0;
            for (int i = 0; i < scenario.reports.length; i++) {
                long before = allocated(bean), start = System.nanoTime();
                state.update(scenario.reports[i]);
                stages[i] = System.nanoTime() - start; stageBytes[i] = allocationDelta(bean, before);
                nanos += stages[i]; bytes += stageBytes[i];
            }
            benchmarkSink ^= state.keys.size() + 31L * state.watches.size() + 7L * state.regions.size() + state.interests.size();
            return new Measurement(nanos, bytes, scenario.reports.length, stages, stageBytes);
        }
        long allocated = allocated(bean), started = System.nanoTime();
        for (long[] report : scenario.reports) state.update(report);
        long nanos = System.nanoTime() - started, bytes = allocationDelta(bean, allocated);
        benchmarkSink ^= state.keys.size() + 31L * state.watches.size() + 7L * state.regions.size() + state.interests.size();
        return new Measurement(nanos, bytes, scenario.reports.length, null, null);
    }

    private static Measurement measureIncremental(Scenario scenario, ThreadMXBean bean) {
        Incremental state = new Incremental(); state.update(0, scenario.initial);
        if (scenario.initial.length == 0) {
            long[] stages = new long[scenario.reports.length], stageBytes = new long[scenario.reports.length];
            long nanos = 0, bytes = 0;
            for (int i = 0; i < scenario.reports.length; i++) {
                long before = allocated(bean), start = System.nanoTime();
                state.update(i + 1, scenario.reports[i]);
                stages[i] = System.nanoTime() - start; stageBytes[i] = allocationDelta(bean, before);
                nanos += stages[i]; bytes += stageBytes[i];
            }
            benchmarkSink ^= state.state.keys().size() + 31L * state.state.watchKeys().size()
                    + 7L * state.state.regions().size() + state.interests.size();
            return new Measurement(nanos, bytes, scenario.reports.length, stages, stageBytes);
        }
        long allocated = allocated(bean), started = System.nanoTime();
        for (int i = 0; i < scenario.reports.length; i++) state.update(i + 1, scenario.reports[i]);
        long nanos = System.nanoTime() - started, bytes = allocationDelta(bean, allocated);
        benchmarkSink ^= state.state.keys().size() + 31L * state.state.watchKeys().size()
                + 7L * state.state.regions().size() + state.interests.size();
        return new Measurement(nanos, bytes, scenario.reports.length, null, null);
    }

    private static void reportLogicalState(Scenario scenario) {
        VisibleSectionState state = new VisibleSectionState(); state.update(0, scenario.initial);
        int peakKeys = state.keys().size(), peakWatches = state.watchKeys().size(), peakRegions = state.regions().size();
        int peakDeltaEntries = deltaEntries(state);
        for (int i = 0; i < scenario.reports.length; i++) {
            state.update(i + 1, scenario.reports[i]);
            peakKeys = Math.max(peakKeys, state.keys().size());
            peakWatches = Math.max(peakWatches, state.watchKeys().size());
            peakRegions = Math.max(peakRegions, state.regions().size());
            peakDeltaEntries = Math.max(peakDeltaEntries, deltaEntries(state));
        }
        System.out.println("VISIBLE_BENCH logical_state scenario=" + scenario.name + " final_keys=" + state.keys().size()
                + " final_watches=" + state.watchKeys().size() + " final_regions=" + state.regions().size()
                + " peak_keys=" + peakKeys + " peak_watches=" + peakWatches + " peak_regions=" + peakRegions
                + " peak_delta_entries=" + peakDeltaEntries
                + " bounds=watch_membership<=5*unique_keys,region_membership<=unique_keys;backing_capacity_not_measured");
    }

    private static int deltaEntries(VisibleSectionState state) {
        return state.addedKeys().size() + state.removedKeys().size() + state.addedWatchKeys().size()
                + state.removedWatchKeys().size() + state.addedRegions().size() + state.removedRegions().size();
    }

    private static long allocated(ThreadMXBean bean) {
        return bean == null ? -1 : bean.getThreadAllocatedBytes(Thread.currentThread().threadId());
    }

    private static long allocationDelta(ThreadMXBean bean, long before) {
        if (bean == null || before < 0) return -1;
        long after = allocated(bean);
        return after < 0 ? -1 : after - before;
    }

    private static long[] grid(int count) {
        long[] keys = new long[count];
        for (int i = 0; i < count; i++) keys[i] = SectionKey.pack(0, i % 256 - 128, i % 7 - 3, i / 256 - 64);
        return keys;
    }

    private static long randomKey(Random random) {
        return SectionKey.pack(random.nextInt(5), random.nextInt(4096) - 2048,
                random.nextInt(256) - 128, random.nextInt(4096) - 2048);
    }

    private static long legacyParent(long child) {
        return SectionKey.pack(SectionKey.level(child) + 1, SectionKey.x(child) >> 1,
                SectionKey.y(child) >> 1, SectionKey.z(child) >> 1);
    }

    private static long legacyRegion(long key) {
        int x = Math.floorDiv(SectionKey.x(key), 16 >> SectionKey.level(key));
        int z = Math.floorDiv(SectionKey.z(key), 16 >> SectionKey.level(key));
        return Integer.toUnsignedLong(x) | Integer.toUnsignedLong(z) << 32;
    }

    private static long[] array(List<Long> values) {
        long[] result = new long[values.size()];
        for (int i = 0; i < result.length; i++) result[i] = values.get(i);
        return result;
    }

    private static void shuffle(long[] values, Random random) {
        for (int i = values.length - 1; i > 0; i--) {
            int other = random.nextInt(i + 1); long value = values[i]; values[i] = values[other]; values[other] = value;
        }
    }

    private static Set<Long> difference(Set<Long> left, Set<Long> right) {
        Set<Long> result = new HashSet<>(left); result.removeAll(right); return result;
    }

    private static void same(LongSet actual, Set<Long> expected, String label) {
        check(actual.size() == expected.size(), label + " size expected " + expected.size() + " actual " + actual.size());
        for (long key : expected) check(actual.contains(key), label + " missing " + SectionKey.describe(key));
    }

    private static void same(LongList actual, Set<Long> expected, String label) {
        Set<Long> unique = new HashSet<>();
        for (int i = 0; i < actual.size(); i++) check(unique.add(actual.getLong(i)), label + " duplicate delta entry");
        check(unique.equals(expected), label + " transition mismatch expected size " + expected.size() + " actual " + actual.size());
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void expectReadOnly(Runnable mutation) {
        try { mutation.run(); }
        catch (UnsupportedOperationException expected) { return; }
        throw new AssertionError("visible state view allowed external mutation");
    }
}
