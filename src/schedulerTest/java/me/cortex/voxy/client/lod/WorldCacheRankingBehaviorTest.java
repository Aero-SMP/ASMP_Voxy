package me.cortex.voxy.client.lod;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import me.cortex.voxy.client.core.rendering.SectionKey;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/** Exact-order proof and a bounded, GL-free replay of the production frontier. */
final class WorldCacheRankingBehaviorTest {
    private static final Access ACCESS = new Access();
    private static long pairs;

    static void run() throws Exception {
        arithmeticAndOrder();
        dirtyOffersAndFailures();
        replayOrder();
        System.out.println("world-cache independent live-order proof passed: " + pairs + " actual Node polls");
    }

    static void assertFrontier(WorldCacheDownloads.Dimension dimension, String phase) throws Exception {
        check(!dimension.orderingDirty, phase + ": acknowledged heap is dirty");
        List<Expected> expected = new ArrayList<>();
        Set<Coordinates> unique = new HashSet<>();
        Set<Long> roots = new HashSet<>(dimension.visibleRoots);
        for (Object node : dimension.queued.values()) {
            Coordinates position = ACCESS.coordinates(node);
            check(unique.add(position), phase + ": duplicate queued position");
            boolean visible = visible(position, roots);
            long rank = rank(position, dimension.x, dimension.z);
            check(ACCESS.cachedVisible.getBoolean(node) == visible, phase + ": stale visibility");
            check(ACCESS.cachedRank.getLong(node) == rank, phase + ": stale rank");
            expected.add(new Expected(node, position, visible, rank));
        }
        expected.sort(ORDER);
        PriorityQueue<?> actual = new PriorityQueue<>(dimension.frontier);
        check(actual.size() == expected.size(), phase + ": frontier/queued membership differs");
        for (Expected item : expected) {
            check(actual.remove() == item.node, phase + ": actual poll differs from independent live ordering");
            pairs++;
        }
        check(actual.isEmpty(), phase + ": unaccounted heap node");
    }

    private record Coordinates(int level, int x, int y, int z) {}
    private record Expected(Object node, Coordinates position, boolean visible, long rank) {}
    private static final Comparator<Expected> ORDER = (a, b) -> {
        int order = Boolean.compare(b.visible, a.visible);
        if (order == 0) order = Long.compare(a.rank, b.rank);
        if (order == 0) order = Integer.compare(b.position.level, a.position.level);
        if (order == 0) order = Integer.compare(a.position.x, b.position.x);
        if (order == 0) order = Integer.compare(a.position.z, b.position.z);
        if (order == 0) order = Integer.compare(a.position.y, b.position.y);
        return order;
    };

    // The oracle uses floor division and separate axis distances, not production
    // visible()/rank()/compareTo(). Long overflow is intentionally preserved.
    private static boolean visible(Coordinates node, Set<Long> roots) {
        if (node.level > 4) return false;
        for (int ancestor = node.level; ancestor <= 4; ancestor++) {
            int divisor = 1 << (ancestor - node.level);
            if (roots.contains(SectionKey.pack(ancestor, Math.floorDiv(node.x, divisor),
                    Math.floorDiv(node.y, divisor), Math.floorDiv(node.z, divisor)))) return true;
        }
        return false;
    }
    private static long distance(int cell, long side, int anchor) {
        long lower = (long) cell * side, upper = (cell + 1L) * side;
        if (anchor < lower) return lower - anchor;
        if (anchor > upper) return anchor - upper;
        return 0;
    }
    private static long rank(Coordinates node, int x, int z) {
        long side = 32L << node.level;
        long dx = distance(node.x, side, x), dz = distance(node.z, side, z);
        return 262144L + ((dx * dx + dz * dz) << (2 * Math.max(0, 4 - node.level)));
    }

    private static WorldCacheDownloads.Dimension dimension() {
        var info = new RegionalProtocol.DimensionInfo(77, "ranking:world", RegionalProtocol.Hash32.ZERO,
                -128, 256, true, 0, 0, 60_000_000, 0, RegionalProtocol.Hash32.ZERO);
        return new WorldCacheDownloads.Dimension(info, null);
    }
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Object insert(WorldCacheDownloads.Dimension dimension, Coordinates position) throws Exception {
        Object node = ACCESS.constructor.newInstance(dimension, position.level, position.x, position.y, position.z);
        ((Map) dimension.queued).put(ACCESS.position.invoke(node), node);
        return node;
    }

    private static void arithmeticAndOrder() throws Exception {
        var dimension = dimension();
        int[] coordinates = {Integer.MIN_VALUE, -65537, -17, -1, 0, 1, 17, 65537, Integer.MAX_VALUE};
        for (int level = 0; level <= 21; level++) {
            for (int x : coordinates) for (int z : coordinates)
                insert(dimension, new Coordinates(level, x, (x ^ z) & 15, z));
            insert(dimension, new Coordinates(level, 0, -3, 0));
            insert(dimension, new Coordinates(level, 0, 3, 0));
        }
        dimension.visibleRoots.add(SectionKey.pack(4, -1, 0, -1));
        dimension.visibleRoots.add(SectionKey.pack(0, 0, 0, 0));
        int[] anchors = {Integer.MIN_VALUE, -1025, -1, 0, 1, 1025, Integer.MAX_VALUE};
        for (int x : anchors) for (int z : anchors) {
            dimension.orderingDirty = true; dimension.x = x; dimension.z = z;
            dimension.normalizeOrdering();
            assertFrontier(dimension, "all LOD/search levels, overflow and ties at " + x + "," + z);
        }
    }

    private static void dirtyOffersAndFailures() throws Exception {
        var dimension = dimension();
        var roots = new CountedRoots();
        ACCESS.visibleRoots.set(dimension, roots);
        long[] slots = new long[16]; Arrays.fill(slots, -1L);
        dimension.regions.put(WorldCacheDownloads.region(0, 0), slots);
        long first = SectionKey.pack(0, 0, 0, 0), second = SectionKey.pack(0, 1, 0, 0);
        dimension.offer(dimension.node(first));
        assertFrontier(dimension, "clean insertion");
        Object old = dimension.frontier.peek();
        long oldRank = ACCESS.cachedRank.getLong(old);
        dimension.anchor(1000, -1000);
        dimension.offer(dimension.node(second));
        check(dimension.orderingDirty && dimension.queued.size() == 2 && dimension.frontier.size() == 1,
                "dirty offer inserted into old heap or forced a rebuild");
        check(ACCESS.cachedRank.getLong(old) == oldRank, "dirty anchor changed an in-heap key");
        dimension.normalizeOrdering();
        assertFrontier(dimension, "dirty insertion normalization");

        // Failing refresh empties the obsolete heap while preserving authoritative queued state.
        dimension.anchor(1001, -1000);
        roots.failContainsAt = roots.lookups + 1;
        expectFailure(dimension::normalizeOrdering);
        check(dimension.orderingDirty && dimension.frontier.isEmpty() && dimension.queued.size() == 2,
                "failed rebuild exposed old heap or lost authoritative nodes");
        roots.failContainsAt = -1;
        dimension.normalizeOrdering();
        assertFrontier(dimension, "failed rebuild recovery");

        // inside() uses five lookups for a nonvisible LOD0; the next lookup is refreshOrdering().
        long third = SectionKey.pack(0, 2, 0, 0);
        roots.failContainsAt = roots.lookups + 6;
        expectFailure(() -> dimension.offer(dimension.node(third)));
        check(dimension.orderingDirty && dimension.queued.size() == 3 && dimension.frontier.size() == 2,
                "accepted clean-offer failure was not retained as dirty queued state");
        roots.failContainsAt = -1;
        dimension.normalizeOrdering();
        assertFrontier(dimension, "accepted offer failure recovery");
        dimension.resetFrontier();
        check(!dimension.orderingDirty && dimension.queued.isEmpty() && dimension.frontier.isEmpty(),
                "reset did not clear map, heap and dirty together");
    }

    /** Test-only instrumentation: compareTo must never consult this set. */
    static final class CountedRoots extends LongOpenHashSet {
        long lookups;
        long failContainsAt = -1;
        boolean failAfterAdd;
        @Override public boolean contains(long key) {
            if (++this.lookups == this.failContainsAt) throw new InjectedFailure();
            return super.contains(key);
        }
        @Override public boolean add(long key) {
            boolean added = super.add(key);
            if (this.failAfterAdd) { this.failAfterAdd = false; throw new InjectedFailure(); }
            return added;
        }
    }
    static final class InjectedFailure extends RuntimeException {}
    interface Operation { void run() throws Exception; }
    static void expectFailure(Operation operation) throws Exception {
        try { operation.run(); } catch (InjectedFailure expected) { return; }
        throw new AssertionError("injected failure was not exercised");
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    static final class TrackingHeap extends PriorityQueue {
        int clears;
        final CountedRoots roots;
        long lookupsAtClear;
        TrackingHeap(WorldCacheDownloads.Dimension dimension, CountedRoots roots) {
            super(dimension.frontier); this.roots = roots;
        }
        @Override public void clear() { this.clears++; this.lookupsAtClear = this.roots.lookups; super.clear(); }
    }
    static CountedRoots instrument(WorldCacheDownloads.Dimension dimension) throws Exception {
        var roots = new CountedRoots(); roots.addAll(dimension.visibleRoots);
        ACCESS.visibleRoots.set(dimension, roots);
        return roots;
    }
    @SuppressWarnings("unchecked")
    static TrackingHeap track(WorldCacheDownloads.Dimension dimension, CountedRoots roots) {
        var heap = new TrackingHeap(dimension, roots); dimension.frontier = heap; return heap;
    }

    private static final class Access {
        final Constructor<?> constructor;
        final Method position;
        final Field level, x, y, z, cachedVisible, cachedRank, visibleRoots;
        Access() {
            try {
                Class<?> node = Class.forName(WorldCacheDownloads.class.getName() + "$Node");
                this.constructor = node.getDeclaredConstructor(WorldCacheDownloads.Dimension.class,
                        int.class, int.class, int.class, int.class);
                this.constructor.setAccessible(true);
                this.position = node.getDeclaredMethod("position"); this.position.setAccessible(true);
                this.level = field(node, "level"); this.x = field(node, "x");
                this.y = field(node, "y"); this.z = field(node, "z");
                this.cachedVisible = field(node, "orderingVisible"); this.cachedRank = field(node, "orderingRank");
                this.visibleRoots = field(WorldCacheDownloads.Dimension.class, "visibleRoots");
            } catch (ReflectiveOperationException failure) { throw new ExceptionInInitializerError(failure); }
        }
        Coordinates coordinates(Object node) throws IllegalAccessException {
            return new Coordinates(this.level.getInt(node), this.x.getInt(node), this.y.getInt(node), this.z.getInt(node));
        }
    }

    // Directly typed former algorithm: no reflection is executed in its comparator.
    private static final class LegacyNode implements Comparable<LegacyNode> {
        final Object actual;
        final Legacy owner;
        final int level, x, y, z;
        LegacyNode(Object actual, Legacy owner, Coordinates position) {
            this.actual = actual; this.owner = owner;
            this.level = position.level; this.x = position.x; this.y = position.y; this.z = position.z;
        }
        boolean visible() {
            this.owner.visibilityCalculations++;
            if (this.level > 4) return false;
            for (int level = this.level; level <= 4; level++) {
                int shift = level - this.level;
                if (this.owner.roots.contains(SectionKey.pack(level, this.x >> shift, this.y >> shift, this.z >> shift))) return true;
            }
            return false;
        }
        long rank() {
            this.owner.rankCalculations++;
            long size = 32L << this.level;
            long dx = Math.max(0, Math.max((long) this.x * size - this.owner.x, (long) this.owner.x - (this.x + 1L) * size));
            long dz = Math.max(0, Math.max((long) this.z * size - this.owner.z, (long) this.owner.z - (this.z + 1L) * size));
            return 512L * 512 + ((dx * dx + dz * dz) << (2 * Math.max(0, 4 - this.level)));
        }
        @Override public int compareTo(LegacyNode other) {
            int result = Boolean.compare(other.visible(), visible());
            if (result == 0) result = Long.compare(rank(), other.rank());
            if (result == 0) result = Integer.compare(other.level, this.level);
            if (result == 0) result = Integer.compare(this.x, other.x);
            if (result == 0) result = Integer.compare(this.z, other.z);
            if (result == 0) result = Integer.compare(this.y, other.y);
            return result;
        }
    }
    private static final class Legacy {
        final CountedRoots roots = new CountedRoots();
        final Map<Object, LegacyNode> queued = new HashMap<>();
        PriorityQueue<LegacyNode> frontier;
        int x, z, rebuilds;
        long visibilityCalculations, rankCalculations;
        void rebuild() { this.frontier = new PriorityQueue<>(this.queued.values()); this.rebuilds++; }
        void apply(Event event) {
            if (this.x != event.x || this.z != event.z) { this.x = event.x; this.z = event.z; rebuild(); }
            if (!this.roots.equals(event.roots)) { this.roots.clear(); this.roots.addAll(event.roots); rebuild(); }
        }
    }
    private record Event(int x, int z, LongOpenHashSet roots) {}
    private record Replay(WorldCacheDownloads.Dimension candidate, CountedRoots roots, Legacy legacy, List<Event> events) {}
    private static Replay replay(int count) throws Exception { return replay(count, "mixed"); }
    private static Replay replay(int count, String workload) throws Exception {
        var dimension = dimension(); var roots = instrument(dimension); var legacy = new Legacy();
        for (int i = 0; i < count; i++) {
            int level = i % 22, x = (i * 37 % 129) - 64, z = (i * 53 % 131) - 65, y = (i / 22) % 7 - 3;
            var coordinates = new Coordinates(level, x, y, z);
            Object node = insert(dimension, coordinates);
            legacy.queued.put(ACCESS.position.invoke(node), new LegacyNode(node, legacy, coordinates));
        }
        dimension.orderingDirty = true; dimension.normalizeOrdering(); legacy.rebuild();
        var events = new ArrayList<Event>();
        for (int i = 0; i < 32; i++) {
            var membership = new LongOpenHashSet();
            for (int j = 0; j < 24; j++) membership.add(SectionKey.pack(j % 5, (j * 3 + i) % 9 - 4, 0, (j + i / 2) % 9 - 4));
            switch (workload) {
                case "combined" -> events.add(new Event((i + 1) * 93, -(i + 1) * 57, membership));
                case "membership" -> events.add(new Event(0, 0, membership));
                case "movement" -> events.add(new Event((i + 1) * 93, -(i + 1) * 57, new LongOpenHashSet()));
                case "stable" -> events.add(new Event(0, 0, new LongOpenHashSet()));
                default -> {
                    events.add(new Event(i * 93 - 1024, 1024 - i * 57, membership));
                    events.add(new Event(i * 93 - 1024, 1024 - i * 57, membership));
                    events.add(new Event(i * 93 - 1023, 1024 - i * 57, membership));
                    events.add(new Event(i * 93 - 1023, 1024 - i * 57, new LongOpenHashSet()));
                }
            }
        }
        return new Replay(dimension, roots, legacy, events);
    }
    private static void apply(Replay replay, Event event) {
        var dimension = replay.candidate;
        // The replay isolates ordering maintenance: both algorithms exclude seed,
        // eligibility, directory and retention work. Real view/anchor lifecycle
        // and seed timing are exercised by WorldCacheVisibilityBehaviorTest.
        if (dimension.x != event.x || dimension.z != event.z) {
            dimension.orderingDirty = true; dimension.x = event.x; dimension.z = event.z;
        }
        if (!dimension.visibleRoots.equals(event.roots)) {
            dimension.orderingDirty = true; dimension.visibleRoots.clear(); dimension.visibleRoots.addAll(event.roots);
        }
        dimension.normalizeOrdering();
    }
    private static void replayOrder() throws Exception { replayOrder(1024, "mixed"); }
    private static void replayOrder(int count, String workload) throws Exception {
        var replay = replay(count, workload);
        for (Event event : replay.events) {
            apply(replay, event); replay.legacy.apply(event);
            assertFrontier(replay.candidate, "event replay");
            var current = new PriorityQueue<>(replay.candidate.frontier);
            var previous = new PriorityQueue<>(replay.legacy.frontier);
            check(current.size() == previous.size(), "old/candidate replay membership differs");
            while (!previous.isEmpty()) check(current.remove() == previous.remove().actual, "old/candidate replay poll differs");
        }
    }

    static void benchmark() throws Exception {
        replayOrder();
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        check(bean.isCurrentThreadCpuTimeSupported() && bean.isThreadAllocatedMemorySupported(), "CPU/allocation counters unavailable");
        bean.setThreadCpuTimeEnabled(true); bean.setThreadAllocatedMemoryEnabled(true);
        for (String workload : List.of("mixed", "combined", "membership", "movement", "stable")) {
            replayOrder(2048, workload); // Same inventory/events; outside every timed sample.
            for (int warmup = 0; warmup < 4; warmup++) measure(replay(2048, workload), bean, false);
            long[][] old = new long[7][], current = new long[7][];
            for (int trial = 0; trial < 7; trial++) {
                var replay = replay(2048, workload);
                long[][] result = measure(replay, bean, (trial & 1) == 0);
                old[trial] = result[0]; current[trial] = result[1];
            }
            System.out.println("ranking workload=" + workload + " nodes=2048 events=" + (workload.equals("mixed") ? 128 : 32)
                    + " warmups=4 trials=7 (medians)");
            System.out.println("former wallNs=" + median(old, 0) + " cpuNs=" + median(old, 1) + " allocatedBytes=" + median(old, 2)
                    + " visibilityCalculations=" + median(old, 3) + " rankCalculations=" + median(old, 4) + " rebuilds=" + median(old, 5));
            System.out.println("candidate wallNs=" + median(current, 0) + " cpuNs=" + median(current, 1) + " allocatedBytes=" + median(current, 2)
                    + " visibilityLookups=" + median(current, 3) + " derivedRefreshes=" + median(current, 4) + " rebuilds=" + median(current, 5));
        }
        System.out.println("both peak queued/heap nodes=2048; derived peak non-null heap references during rebuild: former=4096 candidate=2048;");
        System.out.println("reference counts exclude queue backing/constructor temporary arrays and are not measured peak JVM bytes;");
        System.out.println("CPU/allocation replay isolates ordering maintenance (setup/oracle/seed/eligibility/IO excluded); lookup/calculation counters are test-only;");
        System.out.println("actual shallow object layout is measured separately by external size agent; no renderer/network/disk throughput claim");
    }
    private static long[][] measure(Replay replay, com.sun.management.ThreadMXBean bean, boolean candidateFirst) {
        long[] old, candidate;
        if (candidateFirst) { candidate = timed(replay, bean, false); old = timed(replay, bean, true); }
        else { old = timed(replay, bean, true); candidate = timed(replay, bean, false); }
        return new long[][]{old, candidate};
    }
    private static long[] timed(Replay replay, com.sun.management.ThreadMXBean bean, boolean old) {
        long thread = Thread.currentThread().threadId();
        long allocations = bean.getThreadAllocatedBytes(thread), cpu = bean.getCurrentThreadCpuTime(), wall = System.nanoTime();
        long visibility = replay.legacy.visibilityCalculations, rank = replay.legacy.rankCalculations, lookups = replay.roots.lookups;
        int rebuilds = 0;
        for (Event event : replay.events) {
            if (old) replay.legacy.apply(event);
            else {
                boolean changed = replay.candidate.x != event.x || replay.candidate.z != event.z
                        || !replay.candidate.visibleRoots.equals(event.roots);
                apply(replay, event); if (changed) rebuilds++;
            }
        }
        long elapsed = System.nanoTime() - wall, cpuUsed = bean.getCurrentThreadCpuTime() - cpu;
        long allocated = bean.getThreadAllocatedBytes(thread) - allocations;
        if (old) return new long[]{elapsed, cpuUsed, allocated, replay.legacy.visibilityCalculations - visibility,
                replay.legacy.rankCalculations - rank, replay.legacy.rebuilds - 1};
        return new long[]{elapsed, cpuUsed, allocated, replay.roots.lookups - lookups, (long) rebuilds * replay.candidate.queued.size(), rebuilds};
    }
    private static long median(long[][] values, int column) {
        long[] ordered = new long[values.length]; for (int i = 0; i < values.length; i++) ordered[i] = values[i][column];
        Arrays.sort(ordered); return ordered[ordered.length / 2];
    }
    private static Field field(Class<?> type, String name) throws ReflectiveOperationException {
        var field = type.getDeclaredField(name); field.setAccessible(true); return field;
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
