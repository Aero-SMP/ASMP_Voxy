package me.cortex.voxy.client.lod;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Deterministic accounting oracle; batch concurrency checks make no real CPU assumptions. */
public final class OwnerLoadingTelemetryBehaviorTest {
    static final class Clock implements OwnerLoadingTelemetry.Clock {
        long wall, cpu;
        public long wall() { return wall; }
        public long cpu() { return cpu; }
        void at(long wall, long cpu) { this.wall = wall; this.cpu = cpu; }
    }
    static void eq(long expected, long actual) {
        if (expected != actual) throw new AssertionError(expected + " != " + actual);
    }
    public static void main(String[] args) {
        Clock clock = new Clock();
        OwnerLoadingTelemetry t = new OwnerLoadingTelemetry(clock);
        eq(t.detailCount.length, OwnerLoadingTelemetry.DETAILS.split(",").length);
        eq(t.events.length, OwnerLoadingTelemetry.EVENTS.split(",").length);
        eq(t.pressure.length, OwnerLoadingTelemetry.SLOT_PRESSURE.split(",").length);
        clock.at(10, 2); t.phase(6, clock.wall());
        clock.at(12, 3); t.detail(2);
        clock.at(22, 7); t.detail(3);
        clock.at(37, 12); t.phase(7, clock.wall()); // Phase exit closes active detail on any return/failure.
        eq(27, 37 - 10); eq(10, t.phaseCpu[6]); eq(1, t.phaseCpuCount[6]);
        eq(10, t.detailWall[2]); eq(15, t.detailWall[3]);
        eq(4, t.detailCpu[2]); eq(5, t.detailCpu[3]);
        eq(1, t.detailCount[2]); eq(1, t.detailCount[3]);
        clock.at(40, -1); t.detail(8);
        clock.at(50, -1); t.phase(-1, clock.wall());
        eq(10, t.detailWall[8]); eq(0, t.detailCpuCount[8]); eq(0, t.phaseCpuCount[7]);
        // Unsupported/disabled and decreasing CPU readings must not fabricate measurements.
        clock.at(60, 100); t.phase(1, clock.wall()); t.detail(0);
        clock.at(70, 90); t.phase(-1, clock.wall());
        eq(0, t.phaseCpuCount[1]); eq(0, t.detailCpuCount[0]); eq(10, t.detailWall[0]);
        t.event(13, 8); t.event(13, 3); t.event(13, -1); t.event(-1, 99); t.event(99, 99);
        eq(11, t.events[13]);
        String[] events = OwnerLoadingTelemetry.EVENTS.split(",");
        String[] additions = {"TOPOLOGY_NOTIFIED", "TOPOLOGY_UNCHANGED", "PUBLICATION_SCAN",
                "PUBLICATION_SCAN_SKIPPED", "PUBLICATION_DIRTY"};
        for (int i = 0; i < additions.length; i++) {
            if (!events[30 + i].equals(additions[i])) throw new AssertionError("event index moved");
            t.event(30 + i, i + 1); eq(i + 1, t.events[30 + i]);
        }
        long before = t.detailCount[0]; t.detail(-1); eq(before, t.detailCount[0]);
        detailBatchAccounting();
        concurrentDetailBatches();
        System.out.println("OwnerLoadingTelemetryBehaviorTest PASS");
    }

    private static void detailBatchAccounting() {
        OwnerLoadingTelemetry telemetry = new OwnerLoadingTelemetry(new Clock());
        telemetry.detailBatchFinished(0, 0, false, 10);
        telemetry.detailBatchFinished(4, 0, false, 20);
        telemetry.detailBatchFinished(8, 5, true, 30);
        telemetry.detailBatchFinished(2, 1, true, 25); // Accepted prefix before a decode failure.
        telemetry.detailBatchFinished(-1, 0, false, 0);
        telemetry.detailBatchFinished(1, 2, true, 0);
        telemetry.detailBatchFinished(0, 0, true, 0);
        telemetry.detailBatchFinished(1, 1, true, -1);
        var sample = telemetry.detailBatchSnapshot();
        eq(4, sample.count()); eq(14, sample.inspected()); eq(6, sample.accepted());
        eq(2, sample.signals()); eq(85, sample.nanos()); eq(30, sample.maxNanos());
        String[] fields = {"detailBatchCount=4", "detailBatchInspected=14", "detailBatchAccepted=6",
                "detailBatchSignals=2", "detailBatchNanos=85", "detailBatchMaxNanos=30"};
        for (String field : fields) if (!sample.summary().contains(" " + field))
            throw new AssertionError("batch summary missing " + field);
        for (long event : telemetry.events) eq(0, event);
        for (long detail : telemetry.detailCount) eq(0, detail);
    }

    private static void concurrentDetailBatches() {
        OwnerLoadingTelemetry telemetry = new OwnerLoadingTelemetry(new Clock());
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger finished = new AtomicInteger();
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread[] threads = new Thread[4];
        for (int i = 0; i < threads.length; i++) {
            threads[i] = Thread.ofPlatform().daemon().unstarted(() -> {
                try {
                    start.await();
                    for (int record = 0; record < 2_000; record++)
                        telemetry.detailBatchFinished(3, 2, true, 7);
                } catch (Throwable failure) { error.compareAndSet(null, failure); }
                finally { finished.incrementAndGet(); }
            });
            threads[i].start();
        }
        start.countDown();
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        do {
            var sample = telemetry.detailBatchSnapshot();
            eq(3 * sample.count(), sample.inspected()); eq(2 * sample.count(), sample.accepted());
            eq(sample.count(), sample.signals()); eq(7 * sample.count(), sample.nanos());
            eq(sample.count() == 0 ? 0 : 7, sample.maxNanos());
            if (System.nanoTime() - deadline >= 0) throw new AssertionError("batch telemetry timed out");
            Thread.yield();
        } while (finished.get() != threads.length);
        for (Thread thread : threads) {
            try { thread.join(5_000); }
            catch (InterruptedException failure) {
                Thread.currentThread().interrupt(); throw new AssertionError(failure);
            }
            if (thread.isAlive()) throw new AssertionError("batch telemetry deadlocked");
        }
        if (error.get() != null) throw new AssertionError(error.get());
        eq(8_000, telemetry.detailBatchSnapshot().count());
        for (long event : telemetry.events) eq(0, event);
        for (long detail : telemetry.detailCount) eq(0, detail);
    }
}
