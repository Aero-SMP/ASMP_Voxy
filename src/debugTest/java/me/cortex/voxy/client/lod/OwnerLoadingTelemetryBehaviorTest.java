package me.cortex.voxy.client.lod;

/** Deterministic accounting oracle: no sleeps, thread scheduling or real CPU assumptions. */
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
        long before = t.detailCount[0]; t.detail(-1); eq(before, t.detailCount[0]);
        System.out.println("OwnerLoadingTelemetryBehaviorTest PASS");
    }
}
