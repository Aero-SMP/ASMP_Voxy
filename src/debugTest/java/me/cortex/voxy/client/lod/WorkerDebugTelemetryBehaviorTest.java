package me.cortex.voxy.client.lod;

/** Deterministic checks of exclusive streaming stages and exact lease cohorts. */
public final class WorkerDebugTelemetryBehaviorTest {
    private static final class Clock {
        long wall = 100, cpu = 1_000, allocated = 2_000;
        WorkerDebugTelemetry.Work work() {
            return new WorkerDebugTelemetry.Work(7, 0, 9, () -> wall, () -> cpu, () -> allocated);
        }
        void at(long wall, long cpu, long allocated) { this.wall = wall; this.cpu = cpu; this.allocated = allocated; }
    }
    private static int cell(WorkerDebugTelemetry.Source source, WorkerDebugTelemetry.Stage stage) {
        return source.ordinal() * WorkerDebugTelemetry.Stage.values().length + stage.ordinal();
    }
    private static void equal(long expected, long actual, String message) {
        if (expected != actual) throw new AssertionError(message + ": expected " + expected + ", got " + actual);
    }
    private static long wall(WorkerDebugTelemetry.Metrics metrics, WorkerDebugTelemetry.Source source, WorkerDebugTelemetry.Stage stage) {
        return metrics.wall[cell(source, stage)];
    }
    public static void main(String[] args) {
        exclusiveAndPaired(); unavailableCounters(); histogramBounds(); noHotRestoreAllocation();
        System.out.println("WorkerDebugTelemetryBehaviorTest PASS");
    }
    private static void exclusiveAndPaired() {
        var clock = new Clock(); var work = clock.work();
        var cache = WorkerDebugTelemetry.Source.CACHE; var network = WorkerDebugTelemetry.Source.NETWORK;
        work.assigned(1, cache);
        clock.at(110, 1_000, 2_000); work.begin(1, "SectionWorkerTask", 2, 3, 4, "CACHE");
        clock.at(120, 1_005, 2_010); work.stage("CACHE_READ");
        clock.at(130, 1_009, 2_020); int outer = work.push("CACHE_DECODE");
        clock.at(140, 1_014, 2_030); int inner = work.push("CACHE_FILE_READ");
        clock.at(180, 1_018, 2_040); work.pop(inner);
        clock.at(190, 1_025, 2_050); work.pop(outer);
        clock.at(200, 1_028, 2_055); work.stage("MESH");
        clock.at(230, 1_048, 2_080); work.completed(1);
        clock.at(235, 1_049, 2_081); work.stage("RESULT_READY");
        clock.at(250, 1_049, 2_081); work.claimed(1);
        var partial = work.copy();
        equal(0, partial.lifetime().count[cell(cache, WorkerDebugTelemetry.Stage.SLOT_LEASE)], "open lease does not pollute completed cohort");
        equal(150, partial.assignedAgeNanos(), "open assignment age");
        clock.at(280, 1_049, 2_081); work.reusable(1);
        // The owner can assign the next lease before the old worker's finally/end.
        clock.at(290, 1_049, 2_081); work.assigned(2, network);
        clock.at(300, 1_055, 2_090); work.end();
        var snapshot = work.copy(); var m = snapshot.lifetime();
        equal(20, wall(m, cache, WorkerDebugTelemetry.Stage.CACHE_READ), "outer read is exclusive");
        equal(20, wall(m, cache, WorkerDebugTelemetry.Stage.CACHE_DECODE), "decode resumes after nested read");
        equal(40, wall(m, cache, WorkerDebugTelemetry.Stage.CACHE_FILE_READ), "nested file read");
        equal(4, m.cpu[cell(cache, WorkerDebugTelemetry.Stage.CACHE_FILE_READ)], "file read CPU differs from wall");
        equal(190, wall(m, cache, WorkerDebugTelemetry.Stage.JOB_TOTAL), "job includes post-completion worker work");
        long exclusive = 0, exclusiveCpu = 0;
        for (var stage : new WorkerDebugTelemetry.Stage[] {WorkerDebugTelemetry.Stage.TASK, WorkerDebugTelemetry.Stage.CACHE_READ,
                WorkerDebugTelemetry.Stage.CACHE_DECODE, WorkerDebugTelemetry.Stage.CACHE_FILE_READ,
                WorkerDebugTelemetry.Stage.MESH, WorkerDebugTelemetry.Stage.RESULT_READY}) {
            exclusive += wall(m, cache, stage); exclusiveCpu += m.cpu[cell(cache, stage)];
        }
        equal(190, exclusive, "nested stages sum exactly to job wall");
        equal(55, exclusiveCpu, "nested stages sum exactly to job CPU");
        long paired = 0;
        for (var stage : new WorkerDebugTelemetry.Stage[] {WorkerDebugTelemetry.Stage.ASSIGN_TO_BEGIN,
                WorkerDebugTelemetry.Stage.BEGIN_TO_COMPLETE, WorkerDebugTelemetry.Stage.COMPLETE_TO_CLAIM,
                WorkerDebugTelemetry.Stage.CLAIM_TO_REUSE}) {
            equal(1, m.count[cell(cache, stage)], "same completed lease count " + stage);
            paired += wall(m, cache, stage);
        }
        equal(180, paired, "paired exact lease durations");
        equal(paired, wall(m, cache, WorkerDebugTelemetry.Stage.SLOT_LEASE), "paired stages match complete lease residence");
        equal(48, m.cpu[cell(cache, WorkerDebugTelemetry.Stage.BEGIN_TO_COMPLETE)], "pre-result CPU independent of late end");
        equal(80, m.allocated[cell(cache, WorkerDebugTelemetry.Stage.BEGIN_TO_COMPLETE)], "pre-result allocations");
        equal(1, work.assignedLease == 2 ? 1 : 0, "late end preserves new lease");
        equal(0, m.count[cell(network, WorkerDebugTelemetry.Stage.JOB_TOTAL)], "old end keeps original source");
        var emptyWindow = work.copy();
        for (long n : emptyWindow.window().count) equal(0, n, "second snapshot window reset");
        equal(190, wall(emptyWindow.lifetime(), cache, WorkerDebugTelemetry.Stage.JOB_TOTAL), "lifetime survives window reset");

        clock.at(310, 1_055, 2_090); work.begin(2, "SectionWorkerTask", 5, 6, 7, "NETWORK");
        clock.at(320, 1_060, 2_100); work.completed(1); work.claimed(1); work.reusable(1);
        equal(2, work.assignedLease, "stale callbacks preserve exact new generation");
        clock.at(330, 1_070, 2_110); work.completed(2); work.completed(2);
        clock.at(340, 1_070, 2_110); work.claimed(2); work.claimed(2);
        clock.at(350, 1_072, 2_115); work.end();
        clock.at(370, 1_072, 2_115); work.reusable(2); work.reusable(2);
        var networkMetrics = work.copy().lifetime();
        equal(1, networkMetrics.count[cell(network, WorkerDebugTelemetry.Stage.SLOT_LEASE)], "duplicate callbacks do not double count");
        equal(80, wall(networkMetrics, network, WorkerDebugTelemetry.Stage.SLOT_LEASE), "network source kept separate");
        equal(180, wall(networkMetrics, cache, WorkerDebugTelemetry.Stage.SLOT_LEASE), "new source does not alter cache cohort");
    }
    private static void unavailableCounters() {
        var clock = new Clock(); clock.cpu = clock.allocated = -1; var work = clock.work();
        work.begin(1, "SectionWorkerTask", 0, 0, 0, "CACHE");
        clock.wall = 200; work.stage("CACHE_DECODE");
        clock.wall = 300; work.end();
        var metrics = work.copy().lifetime(); int cell = cell(WorkerDebugTelemetry.Source.CACHE, WorkerDebugTelemetry.Stage.CACHE_DECODE);
        equal(100, metrics.wall[cell], "wall remains available when CPU/allocation unavailable");
        equal(0, metrics.cpuCount[cell], "unavailable CPU is not zero observed CPU");
        equal(0, metrics.allocationCount[cell], "unavailable allocation is not zero observed allocation");
        // A counter turning on mid-stage cannot establish that stage's delta.
        clock.at(400, -1, -1); work.begin(2, "SectionWorkerTask", 0, 0, 0, "CACHE");
        clock.at(500, 10, 20); work.stage("MESH");
        clock.at(600, 15, 40); work.end();
        metrics = work.copy().lifetime();
        equal(0, metrics.cpuCount[cell(WorkerDebugTelemetry.Source.CACHE, WorkerDebugTelemetry.Stage.TASK)], "CPU baseline requires two endpoints");
        equal(5, metrics.cpu[cell(WorkerDebugTelemetry.Source.CACHE, WorkerDebugTelemetry.Stage.MESH)], "CPU restored with valid endpoints");
    }
    private static void histogramBounds() {
        equal(0, WorkerDebugTelemetry.bucket(0), "zero bucket");
        equal(0, WorkerDebugTelemetry.bucket(1_000), "inclusive bucket bound");
        equal(1, WorkerDebugTelemetry.bucket(1_001), "next bucket");
        var metrics = new WorkerDebugTelemetry.Metrics(); var source = WorkerDebugTelemetry.Source.CACHE;
        var stage = WorkerDebugTelemetry.Stage.MESH; int cell = cell(source, stage);
        for (int i = 0; i < 94; i++) metrics.add(source, stage, 1_000, 0, 0);
        for (int i = 0; i < 6; i++) metrics.add(source, stage, 4_001, 0, 0);
        equal(1, WorkerDebugTelemetry.percentile(metrics.histogram, cell, 50), "p50 upper bound");
        equal(16, WorkerDebugTelemetry.percentile(metrics.histogram, cell, 95), "nearest rank p95 upper bound");
        metrics.add(source, stage, Long.MAX_VALUE, -1, -1);
        equal(Long.MAX_VALUE, WorkerDebugTelemetry.percentile(metrics.histogram, cell, 100), "unbounded final bucket");
        var copy = metrics.copy(); metrics.clear();
        equal(101, copy.count[cell], "copy independent from reset");
        equal(0, metrics.count[cell], "reset count");
    }
    private static void noHotRestoreAllocation() {
        var threads = java.lang.management.ManagementFactory.getThreadMXBean();
        if (!(threads instanceof com.sun.management.ThreadMXBean bean) || !bean.isThreadAllocatedMemorySupported()
                || !bean.isThreadAllocatedMemoryEnabled()) return;
        var clock = new Clock(); var work = clock.work();
        work.begin(1, "SectionWorkerTask", 0, 0, 0, "CACHE"); work.stage("CACHE_DECODE");
        for (int i = 0; i < 10_000; i++) { int previous = work.push("CACHE_FILE_READ"); work.pop(previous); }
        long thread = Thread.currentThread().threadId(), before = bean.getThreadAllocatedBytes(thread);
        for (int i = 0; i < 10_000; i++) { int previous = work.push("CACHE_FILE_READ"); work.pop(previous); }
        long after = bean.getThreadAllocatedBytes(thread);
        equal(0, after - before, "hot nested timing restore must not clone enums or allocate records");
    }
}
