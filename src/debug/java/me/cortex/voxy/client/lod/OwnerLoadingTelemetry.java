package me.cortex.voxy.client.lod;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.Arrays;

/** Owner-confined counters. Parent CPU and detail CPU are inclusive/exclusive alternatives. */
final class OwnerLoadingTelemetry {
    interface Clock { long wall(); long cpu(); }
    private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();
    private static final Clock CLOCK = new Clock() {
        public long wall() { return System.nanoTime(); }
        public long cpu() {
            try { return THREADS.isCurrentThreadCpuTimeSupported() && THREADS.isThreadCpuTimeEnabled()
                    ? THREADS.getCurrentThreadCpuTime() : -1; }
            catch (UnsupportedOperationException | SecurityException ignored) { return -1; }
        }
    };
    static final String DETAILS = "DEMAND_RESET,TOP_MAILBOX,DETAIL_COLLECT,DETAIL_DORMANCY,DORMANT_EVICT,DETAIL_REFINE,PUBLICATION_SCHEDULE,MODEL_CHECK,SOURCE_SCHEDULE,REGION_CLASSIFY,REFRESH_ELIGIBILITY,REGION_CONTROL,VISIBILITY_MEMBERSHIP,RETENTION,DOWNLOAD_VIEW,DOWNLOAD_CONTROL";
    static final String EVENTS = "TOP_ADD,TOP_DROP,DETAIL_DORMANT,DETAIL_WAKE,DETAIL_REFINE,REFINE_REJECTED,REFINE_NO_CONTENT,REFINE_NO_CHILDREN,REFINE_SUCCEEDED,SOURCE_POLLED,SOURCE_STALE,SOURCE_PRESERVED,SOURCE_EMPTY_ASSIGNED,SOURCE_CACHE_ASSIGNED,SOURCE_NO_SLOT,SOURCE_ASSIGN_REJECTED,MODEL_WAIT_CHECK,MODEL_READY_NOTICE,MODEL_OBSOLETE_NOTICE,DESCENDANT_BYTES_VISITS,DORMANT_DESCENDANT_VISITS,COARSEN_ACCOUNT_VISITS,LOCAL_METADATA_APPLIED,LOCAL_METADATA_ENTRIES,PUBLICATION_BATCH,PUBLICATION_SUBMITTED,PUBLICATION_BUSY,PUBLICATION_POLLED,PUBLICATION_PENDING,PUBLICATION_ADMISSION";
    static final String SLOT_PRESSURE = "IDLE,RUNNING_COMPUTE,RUNNING_NAMES,RUNNING_MODELS,COMPLETED_UNCLAIMED,READY_PUBLICATION,SUBMITTED_PUBLICATION,SAVE_AFTER_RELEASE,OTHER,CLOSED";
    final long[] phaseCpu = new long[17], phaseCpuCount = new long[17];
    final long[] detailCount = new long[16], detailWall = new long[16], detailMax = new long[16];
    final long[] detailCpu = new long[16], detailCpuCount = new long[16];
    final long[] events = new long[30], pressure = new long[10];
    private final Clock clock;
    private int phase = -1, detail = -1;
    private long phaseCpuStart = -1, detailStart, detailCpuStart = -1;
    OwnerLoadingTelemetry() { this(CLOCK); }
    OwnerLoadingTelemetry(Clock clock) { this.clock = clock; }
    void phase(int next, long now) {
        long cpu = this.clock.cpu();
        this.closeDetail(now, cpu);
        if (this.phase >= 0 && cpu >= 0 && this.phaseCpuStart >= 0 && cpu >= this.phaseCpuStart) {
            this.phaseCpu[this.phase] += cpu - this.phaseCpuStart;
            this.phaseCpuCount[this.phase]++;
        }
        this.phase = next;
        this.phaseCpuStart = cpu;
    }
    void detail(int next) {
        long now = this.clock.wall(), cpu = this.clock.cpu();
        this.closeDetail(now, cpu);
        if (next >= 0 && next < this.detailCount.length) {
            this.detail = next; this.detailStart = now; this.detailCpuStart = cpu;
        }
    }
    private void closeDetail(long now, long cpu) {
        if (this.detail < 0) return;
        int d = this.detail;
        long elapsed = Math.max(0, now - this.detailStart);
        this.detailCount[d]++; this.detailWall[d] += elapsed;
        this.detailMax[d] = Math.max(this.detailMax[d], elapsed);
        if (cpu >= 0 && this.detailCpuStart >= 0 && cpu >= this.detailCpuStart) {
            this.detailCpu[d] += cpu - this.detailCpuStart; this.detailCpuCount[d]++;
        }
        this.detail = -1;
    }
    void event(int event, long count) {
        if (event >= 0 && event < this.events.length && count >= 0) this.events[event] += count;
    }
    void noSlot(ClientSession.Session session) {
        // Point observations at a failed admission, not duration or a utilization estimate.
        for (var worker : session.sectionWorkers) this.pressure[worker.diagnosticSlotKind()]++;
    }
    String summary(ClientSession.Session session) {
        return " ownerPhaseCpuNanos=" + Arrays.toString(this.phaseCpu)
                + " ownerPhaseCpuCount=" + Arrays.toString(this.phaseCpuCount)
                + " ownerDetailOrder=" + DETAILS + " ownerDetailCount=" + Arrays.toString(this.detailCount)
                + " ownerDetailNanos=" + Arrays.toString(this.detailWall)
                + " ownerDetailMaxNanos=" + Arrays.toString(this.detailMax)
                + " ownerDetailCpuNanos=" + Arrays.toString(this.detailCpu)
                + " ownerDetailCpuCount=" + Arrays.toString(this.detailCpuCount)
                + " loadingEventOrder=" + EVENTS + " loadingEventCount=" + Arrays.toString(this.events)
                + " slotPressureOrder=" + SLOT_PRESSURE + " slotPressureCount=" + Arrays.toString(this.pressure)
                + " localReadyRegions=" + session.demands.readyRegionCount()
                + " localMetadataWorkerState=" + session.metadataWorker.resource.state();
    }
}
