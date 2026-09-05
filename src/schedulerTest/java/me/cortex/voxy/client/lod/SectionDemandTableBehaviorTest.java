package me.cortex.voxy.client.lod;

import me.cortex.voxy.client.core.rendering.section.BasicAsyncGeometryManager;
import me.cortex.voxy.common.util.AllocationArena;

import java.util.HashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Dependency-free executable behavior tests; never packaged in either client artifact. */
public final class SectionDemandTableBehaviorTest {
    private SectionDemandTableBehaviorTest() {}

    public static void main(String[] arguments) throws Exception {
        me.cortex.voxy.client.config.GeometryMemoryOptionsBehaviorTest.run();
        me.cortex.voxy.client.config.LodPixelSizeBehaviorTest.run();
        DebugSnapshotShutdownBehaviorTest.run();
        retainsOneHundredThousandDetailTransitions();
        coalescesNewestUnsignedEpoch();
        priorityMovesOneMembership();
        coveragePrecedesFairRegionalRefinement();
        readyRegionOrdering();
        mixedReadyRegionOrdering();
        finalSectionReleasesRegion();
        staleTicketCannotMutateReplacement();
        allocatorUsesExactOneKiBUnits();
        allocatorReportsFragmentation();
        RegionalSectionCodecBehaviorTest.run();
        RegionalControlFlowBehaviorTest.run();
        CacheStartupBehaviorTest.run();
        ZoomRangeBehaviorTest.run();
        me.cortex.voxy.client.core.rendering.hierarchical.PublicationShutdownBehaviorTest.run();
        PublicationRepairBehaviorTest.run();
        me.cortex.voxy.client.core.ShaderReloadBehaviorTest.run();
        me.cortex.voxy.client.core.rendering.building.BoundaryWaterMesherBehaviorTest.run();
        me.cortex.voxy.client.core.rendering.hierarchical.PublicationTopologyBehaviorTest.run();
        System.out.println("coalesced demand-table behavior tests passed");
    }

    private static void retainsOneHundredThousandDetailTransitions() {
        SectionDemandTable<SectionDemandTable.Demand> table = new SectionDemandTable<>(16);
        for (long key = 0; key < 100_000; key++) table.offerDetail(key, 1, (int) key & 15, 1);
        check(table.pendingInputCount() == 100_000, "distinct detail facts were dropped");
        Map<Long, SectionDemandTable.DetailUpdate> drained = new HashMap<>();
        table.drainDetail(drained::put);
        check(drained.size() == 100_000 && table.pendingInputCount() == 0,
                "detail mailbox did not drain exactly once");
    }

    private static void coalescesNewestUnsignedEpoch() {
        SectionDemandTable<SectionDemandTable.Demand> table = new SectionDemandTable<>(8);
        table.offerDetail(7, 1, 1, Integer.MAX_VALUE);
        table.offerDetail(7, 2, 2, Integer.MIN_VALUE);
        table.offerDetail(7, 3, 3, Integer.MAX_VALUE - 1);
        Map<Long, SectionDemandTable.DetailUpdate> drained = new HashMap<>();
        table.drainDetail(drained::put);
        SectionDemandTable.DetailUpdate update = drained.get(7L);
        check(drained.size() == 1 && update.action() == 2 && update.bucket() == 2,
                "newest unsigned detail epoch was not retained");
        check(table.overwrittenInputCount() == 1, "coalescing was not accounted");
    }

    private static void priorityMovesOneMembership() {
        SectionDemandTable<SectionDemandTable.Demand> table = new SectionDemandTable<>(8);
        SectionDemandTable.Demand demand = table.adopt(demand(1, 4, 9, false, 1));
        table.ready(demand, SectionDemandTable.ReadyKind.SOURCE);
        table.setPriority(demand, 7);
        check(table.readyCount(SectionDemandTable.ReadyKind.SOURCE) == 1,
                "reprioritising duplicated ready work");
        check(table.poll(SectionDemandTable.ReadyKind.SOURCE) == demand,
                "reprioritising replaced the demand record");
        check(table.readyCount(SectionDemandTable.ReadyKind.SOURCE) == 0,
                "polled demand remained indexed");
        table.checkInvariants();
    }

    private static void coveragePrecedesFairRegionalRefinement() {
        SectionDemandTable<SectionDemandTable.Demand> table = new SectionDemandTable<>(8);
        SectionDemandTable.Demand lowA = table.adopt(demand(1, 10, 100, false, 7));
        SectionDemandTable.Demand lowA2 = table.adopt(demand(2, 10, 100, false, 7));
        SectionDemandTable.Demand lowB = table.adopt(demand(3, 20, 100, false, 7));
        SectionDemandTable.Demand coverage = table.adopt(demand(4, 30, 100, true, 0));
        table.ready(lowA, SectionDemandTable.ReadyKind.NETWORK);
        table.ready(lowA2, SectionDemandTable.ReadyKind.NETWORK);
        table.ready(lowB, SectionDemandTable.ReadyKind.NETWORK);
        table.ready(coverage, SectionDemandTable.ReadyKind.NETWORK);
        check(table.poll(SectionDemandTable.ReadyKind.NETWORK) == coverage,
                "coverage did not precede refinement");
        check(table.poll(SectionDemandTable.ReadyKind.NETWORK) == lowA,
                "first regional refinement changed order");
        check(table.poll(SectionDemandTable.ReadyKind.NETWORK) == lowB,
                "equal-priority region did not rotate fairly");
        check(table.poll(SectionDemandTable.ReadyKind.NETWORK) == lowA2,
                "regional rotation lost a section");
        table.checkInvariants();
    }

    private static void finalSectionReleasesRegion() {
        SectionDemandTable<SectionDemandTable.Demand> table = new SectionDemandTable<>(4);
        table.adopt(demand(1, 77, 1, false, 0));
        table.adopt(demand(2, 77, 1, false, 0));
        table.remove(1);
        check(table.regionCount() == 1 && table.region(77).users == 1,
                "region was released too early");
        table.remove(2);
        check(table.regionCount() == 0, "final section did not release its region");
    }

    private static void readyRegionOrdering() {
        var table = new SectionDemandTable<SectionDemandTable.Demand>(8);
        var kind = SectionDemandTable.ReadyKind.NETWORK;
        var a = table.adopt(demand(1, 10, 100, false, 7));
        var a2 = table.adopt(demand(2, 10, 100, false, 7));
        var a3 = table.adopt(demand(3, 10, 100, false, 7));
        var b = table.adopt(demand(4, 20, 100, false, 7));
        var b2 = table.adopt(demand(5, 20, 100, false, 7));
        var c = table.adopt(demand(6, 30, 100, false, 7));
        var c2 = table.adopt(demand(7, 30, 100, false, 7));
        for (var d : List.of(a, b, c, a2, b2, c2, a3)) { table.ready(d, kind); table.checkInvariants(); }
        check(table.pollSameRegion(kind, 999, false, 7) == null, "missing region produced work");
        table.remove(a2.key); table.checkInvariants(); // Nonfinal removal must not rotate A.
        check(table.poll(kind) == a, "adding/removing a region member changed its turn");
        table.checkInvariants();
        check(table.pollSameRegion(kind, 10, false, 7) == a3, "batch did not preserve regional FIFO");
        table.checkInvariants();
        table.ready(a, kind); // A was empty; it must rejoin behind B and C.
        check(table.pollSameRegion(kind, 20, false, 7) == b, "explicit batch chose wrong region");
        table.checkInvariants(); // B moved behind C and A, but retained b2.
        check(table.poll(kind) == c && table.poll(kind) == a && table.poll(kind) == b2
                && table.poll(kind) == c2 && table.poll(kind) == null, "three-region round robin changed");
        table.checkInvariants();
        table.ready(b, kind); table.ready(c, kind); table.ready(a, kind);
        table.setPriority(a, 1); // lower pixel priority cannot steal a turn
        table.ready(c, SectionDemandTable.ReadyKind.RENDERER);
        table.owned(b, SectionDemandTable.CandidateState.WORKER_OWNED);
        table.checkInvariants();
        check(table.readyCount(kind) == 1 && table.readyCount(SectionDemandTable.ReadyKind.RENDERER) == 1,
                "transition duplicated membership");
        check(table.poll(kind) == a && table.poll(SectionDemandTable.ReadyKind.RENDERER) == c,
                "priority/kind transition lost work");
        table.checkInvariants();
        table.ready(a, kind); table.ready(b, kind);
        check(table.poll(kind) == b && table.poll(kind) == a, "pixel priority ordering changed");
        table.checkInvariants();
    }

    private static void mixedReadyRegionOrdering() {
        var table = new SectionDemandTable<SectionDemandTable.Demand>(8);
        var kind = SectionDemandTable.ReadyKind.SOURCE;
        // Small list-of-FIFO-batches oracle, deliberately independent of intrusive/map storage.
        List<List<SectionDemandTable.Demand>> turns = new ArrayList<>();
        Random random = new Random(0x52ea7);
        for (int step = 0; step < 4000; step++) {
            long id = random.nextInt(48);
            var d = table.get(id);
            int op = random.nextInt(5);
            if (op < 3) {
                if (d == null) d = table.adopt(demand(id, id % 3, 100, false, 7));
                for (var batch : turns) batch.remove(d);
                turns.removeIf(List::isEmpty);
                if (op == 0) {
                    table.ready(d, kind);
                    List<SectionDemandTable.Demand> batch = null;
                    for (var candidate : turns) if (candidate.getFirst().regionKey == d.regionKey) batch = candidate;
                    if (batch == null) { batch = new ArrayList<>(); turns.add(batch); }
                    batch.add(d);
                } else if (op == 1) table.remove(id);
                else table.owned(d, SectionDemandTable.CandidateState.WORKER_OWNED);
            } else {
                long region = random.nextInt(5); // includes missing-region polls
                int turn = -1;
                if (op == 3 && !turns.isEmpty()) turn = 0;
                else if (op == 4) for (int i = 0; i < turns.size(); i++) {
                    if (turns.get(i).getFirst().regionKey == region) turn = i;
                }
                SectionDemandTable.Demand expected = null;
                if (turn >= 0) {
                    var batch = turns.remove(turn); expected = batch.removeFirst();
                    if (!batch.isEmpty()) turns.add(batch);
                }
                var actual = op == 3 ? table.poll(kind) : table.pollSameRegion(kind, region, false, 7);
                check(actual == expected, "mixed ready order differs at step " + step);
            }
            table.checkInvariants();
            check(table.readyCount(kind) == turns.stream().mapToInt(List::size).sum(), "mixed count mismatch");
        }
        table.clear(); table.checkInvariants();
    }

    private static void staleTicketCannotMutateReplacement() {
        SectionDemandTable<SectionDemandTable.Demand> table = new SectionDemandTable<>(4, 9);
        SectionDemandTable.Demand first = table.adopt(demand(1, 2, 3, false, 0));
        first.regionGeneration = 5;
        SectionDemandTable.Ticket ticket = first.ticket(9, 0);
        check(table.current(ticket), "fresh ticket was rejected");
        table.remove(1);
        SectionDemandTable.Demand replacement = table.adopt(demand(1, 2, 3, false, 0));
        replacement.regionGeneration = 5;
        check(!table.current(ticket), "stale ticket matched replacement demand");
    }

    private static void allocatorUsesExactOneKiBUnits() {
        check(BasicAsyncGeometryManager.requiredGeometryUnits(8) == 128,
                "small geometry did not round to one 1024-byte unit");
        check(BasicAsyncGeometryManager.requiredGeometryUnits(1024) == 128,
                "aligned geometry changed allocator units");
        check(BasicAsyncGeometryManager.requiredGeometryUnits(1032) == 256,
                "geometry did not round at the allocator boundary");
    }

    private static void allocatorReportsFragmentation() {
        AllocationArena arena = new AllocationArena();
        arena.setLimit(120);
        long first = arena.alloc(30);
        long middle = arena.alloc(30);
        arena.alloc(30);
        arena.alloc(30);
        arena.free(middle);
        arena.free(90);
        check(arena.getLargestFreeSize() == 30,
                "largest contiguous extent ignored fragmentation");
        check(arena.alloc(40) == AllocationArena.SIZE_LIMIT,
                "fragmented allocator admitted a non-contiguous request");
        arena.free(first);
    }

    private static SectionDemandTable.Demand demand(long key, long region, long top,
                                                     boolean coverage, int bucket) {
        return new SectionDemandTable.Demand(key, region, top, coverage, bucket);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
