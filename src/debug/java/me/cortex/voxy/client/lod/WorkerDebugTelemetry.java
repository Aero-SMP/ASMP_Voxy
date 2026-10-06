package me.cortex.voxy.client.lod;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.Arrays;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** One bounded record per actual worker. No task, session, Thread or buffer references retained. */
final class WorkerDebugTelemetry {
    enum Stage { IDLE, TASK, METADATA, INDEX_DECODE, CACHE_READ, DECOMPRESS, DECODE_VALIDATE,
        REQUEST_MODELS, CACHE_WRITE, CHECK_MODELS, MESH, CACHE_QUARANTINE, RESULT_READY, WAIT_MODELS,
        SAVE_ENCODE_WRITE, WAIT_REGION_WRITER, CACHE_ONLY_VALIDATE, CACHE_ONLY_COMMIT,
        ASSIGN_TO_BEGIN, CACHE_PIN, CACHE_JOURNAL, CACHE_FILE_OPEN, CACHE_FILE_READ,
        CACHE_DECOMPRESS, CACHE_NAMES, CACHE_DECODE, CACHE_INTEGRITY, NAME_RESOLUTION_WAIT, SLOT_LEASE, JOB_TOTAL,
        BEGIN_TO_COMPLETE, COMPLETE_TO_CLAIM, CLAIM_TO_REUSE, SLOT_LEASE_INCOMPLETE }
    enum Source { CACHE, NETWORK, CACHE_ONLY_NETWORK, EMPTY, METADATA }
    enum Outcome { CACHE_HIT, CACHE_MISS, CACHE_CORRUPT, MODEL_WAIT, FAILURE, COMPRESSED_BYTES, CANONICAL_BYTES, MESH_BYTES, MODEL_RECLAIM,
        SAVE_SUCCESS, SAVE_FAILURE, SAVE_CANCELLED, CACHE_FILE_BYTES, CACHE_CANONICAL_BYTES }
    private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();
    // Fixed logarithmic bucket upper bounds, in microseconds. Percentiles are bucket
    // bounds, never interpolated estimates or exact section-latency percentiles.
    static final long[] BUCKET_US = {1, 4, 16, 64, 256, 1_024, 4_096, 16_384, 65_536, 262_144, 1_048_576, 4_194_304, Long.MAX_VALUE};
    private static final Stage[] STAGE_VALUES = Stage.values();
    private static final Source[] SOURCE_VALUES = Source.values();
    private static final int STAGES = STAGE_VALUES.length, SOURCES = SOURCE_VALUES.length;
    private static final int CELLS = STAGES * SOURCES, BUCKETS = BUCKET_US.length;

    /** Bounded source x exclusive-stage measurements; no per-key event history. */
    static final class Metrics {
        final long[] count = new long[CELLS], wall = new long[CELLS], max = new long[CELLS];
        final long[] cpu = new long[CELLS], cpuCount = new long[CELLS], allocated = new long[CELLS], allocationCount = new long[CELLS];
        final long[] histogram = new long[CELLS * BUCKETS];
        void add(Source source, Stage stage, long wall, long cpu, long allocated) {
            int cell = source.ordinal() * STAGES + stage.ordinal();
            this.count[cell]++; this.wall[cell] += wall; this.max[cell] = Math.max(this.max[cell], wall);
            if (cpu >= 0) { this.cpu[cell] += cpu; this.cpuCount[cell]++; }
            if (allocated >= 0) { this.allocated[cell] += allocated; this.allocationCount[cell]++; }
            this.histogram[cell * BUCKETS + bucket(wall)]++;
        }
        Metrics copy() {
            var copy = new Metrics(); copy.add(this); return copy;
        }
        void add(Metrics from) {
            for (int i = 0; i < CELLS; i++) {
                count[i] += from.count[i]; wall[i] += from.wall[i]; max[i] = Math.max(max[i], from.max[i]);
                cpu[i] += from.cpu[i]; cpuCount[i] += from.cpuCount[i]; allocated[i] += from.allocated[i];
                allocationCount[i] += from.allocationCount[i];
            }
            for (int i = 0; i < histogram.length; i++) histogram[i] += from.histogram[i];
        }
        void clear() {
            Arrays.fill(count, 0); Arrays.fill(wall, 0); Arrays.fill(max, 0); Arrays.fill(cpu, 0);
            Arrays.fill(cpuCount, 0); Arrays.fill(allocated, 0); Arrays.fill(allocationCount, 0); Arrays.fill(histogram, 0);
        }
        String text() {
            var result = new StringBuilder("{");
            for (Source source : SOURCE_VALUES) {
                boolean first = true;
                for (Stage stage : STAGE_VALUES) {
                    int cell = source.ordinal() * STAGES + stage.ordinal();
                    if (count[cell] == 0) continue;
                    if (first) { result.append(source).append("={"); first = false; }
                    result.append(stage).append("={n=").append(count[cell]).append(",wallNs=").append(wall[cell])
                            .append(",cpuNs=").append(cpu[cell]).append(",cpuN=").append(cpuCount[cell])
                            .append(",allocBytes=").append(allocated[cell]).append(",allocN=").append(allocationCount[cell])
                            .append(",maxNs=").append(max[cell]).append(",p50UpperUs=").append(percentile(histogram, cell, 50))
                            .append(",p95UpperUs=").append(percentile(histogram, cell, 95)).append("};");
                }
                if (!first) result.append("};");
            }
            return result.append('}').toString();
        }
    }
    static int bucket(long nanos) {
        for (int i = 0; i < BUCKETS - 1; i++) if (nanos <= BUCKET_US[i] * 1_000) return i;
        return BUCKETS - 1;
    }
    static long percentile(long[] histogram, int cell, int percent) {
        long count = 0;
        for (int i = 0; i < BUCKETS; i++) count += histogram[cell * BUCKETS + i];
        if (count == 0) return 0;
        long rank = (count / 100) * percent + ((count % 100) * percent + 99) / 100;
        long seen = 0;
        for (int i = 0; i < BUCKETS; i++) {
            seen += histogram[cell * BUCKETS + i];
            if (seen >= rank) return BUCKET_US[i];
        }
        throw new IllegalStateException("invalid worker histogram");
    }
    private static final java.util.concurrent.atomic.AtomicLongArray FRAMES = new java.util.concurrent.atomic.AtomicLongArray(7);
    private static long previousFrame;
    static void frame() {
        if (net.minecraft.client.Minecraft.getInstance().level == null) { previousFrame = 0; return; }
        long now = System.nanoTime(), elapsed = now - previousFrame;
        if (previousFrame != 0) {
            int bucket = elapsed < 4_000_000 ? 0 : elapsed < 8_000_000 ? 1 : elapsed < 16_000_000 ? 2
                    : elapsed < 33_000_000 ? 3 : elapsed < 50_000_000 ? 4 : elapsed < 100_000_000 ? 5 : 6;
            FRAMES.incrementAndGet(bucket);
        }
        previousFrame = now;
    }
    private static String runtimeSample() {
        long collections = 0, millis = 0;
        for (var gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            collections += Math.max(0, gc.getCollectionCount()); millis += Math.max(0, gc.getCollectionTime());
        }
        long[] frames = new long[FRAMES.length()];
        for (int i = 0; i < frames.length; i++) frames[i] = FRAMES.get(i);
        return " heapUsedBytes=" + ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed()
                + " gcCollections=" + collections + " gcMillis=" + millis
                + " frameGapBucketsMs=4,8,16,33,50,100,inf frameGapCounts=" + Arrays.toString(frames);
    }

    static final class Work {
        final long session, thread;
        final int slot;
        long lease, key, revision, regionVersion, jobStart, stageStart, lastEnd, jobs, sequence, repeats;
        long assignedLease = -1, assignedStart, stageCpuStart = -1, stageAllocatedStart = -1;
        long assignedBegin = -1, assignedCompleted = -1, assignedClaimed = -1;
        long assignedCpu = -1, assignedAllocated = -1;
        long jobCpuStart = -1, jobAllocatedStart = -1;
        Source assignedSource = Source.METADATA, cohort = Source.METADATA;
        final LongSupplier wallClock, cpuClock, allocationClock;
        final Metrics lifetime = new Metrics(), window = new Metrics();
        long windowStart;
        volatile long nativeHighWater;
        String kind = "NONE", source = "NONE";
        Stage stage = Stage.IDLE;
        boolean closing;
        final long[] counts = new long[STAGES], totals = counts.clone(), maxima = counts.clone();
        final long[] outcomes = new long[Outcome.values().length];
        // Owner-sampler-only state: one previous observation, never an event/section history.
        long observedLease = -1, observedSequence = -1, priorCpu = -1, priorJobs, priorSample;
        String signature;
        Work(long session, int slot, long thread) {
            this(session, slot, thread, System::nanoTime, WorkerDebugTelemetry::samplerCpuTime,
                    WorkerDebugTelemetry::allocatedBytes);
        }
        Work(long session, int slot, long thread, LongSupplier wallClock, LongSupplier cpuClock, LongSupplier allocationClock) {
            this.session = session; this.slot = slot; this.thread = thread;
            this.wallClock = wallClock; this.cpuClock = cpuClock; this.allocationClock = allocationClock;
            this.windowStart = wallClock.getAsLong();
        }
        synchronized void assigned(long lease, Source source) {
            this.assignedLease = lease; this.assignedStart = wallClock.getAsLong(); this.assignedSource = source;
            this.assignedBegin = this.assignedCompleted = this.assignedClaimed = -1;
            this.assignedCpu = this.assignedAllocated = -1;
        }
        synchronized void completed(long lease) {
            if (assignedLease != lease || assignedBegin < 0 || assignedCompleted >= 0) return;
            assignedCompleted = wallClock.getAsLong();
            assignedCpu = delta(cpuClock.getAsLong(), jobCpuStart);
            assignedAllocated = delta(allocationClock.getAsLong(), jobAllocatedStart);
        }
        synchronized void claimed(long lease) {
            if (assignedLease != lease || assignedCompleted < 0 || assignedClaimed >= 0) return;
            assignedClaimed = wallClock.getAsLong();
        }
        synchronized void reusable(long lease) {
            if (assignedLease != lease) return;
            long now = wallClock.getAsLong();
            if (assignedBegin >= 0 && assignedCompleted >= 0 && assignedClaimed >= 0) {
                // Commit the four disjoint intervals together, using one exact completed
                // lease cohort. A late worker end cannot change these retained stamps.
                add(assignedSource, Stage.ASSIGN_TO_BEGIN, Math.max(0, assignedBegin - assignedStart), -1, -1);
                add(assignedSource, Stage.BEGIN_TO_COMPLETE, Math.max(0, assignedCompleted - assignedBegin), assignedCpu, assignedAllocated);
                add(assignedSource, Stage.COMPLETE_TO_CLAIM, Math.max(0, assignedClaimed - assignedCompleted), -1, -1);
                add(assignedSource, Stage.CLAIM_TO_REUSE, Math.max(0, now - assignedClaimed), -1, -1);
                add(assignedSource, Stage.SLOT_LEASE, Math.max(0, now - assignedStart), -1, -1);
            } else add(assignedSource, Stage.SLOT_LEASE_INCOMPLETE, Math.max(0, now - assignedStart), -1, -1);
            this.assignedLease = -1;
        }
        synchronized void begin(long lease, String kind, long key, long revision, long regionVersion, String source) {
            if (kind.equals("SectionWorkerTask") && this.jobs > 0 && this.key == key
                    && this.revision == revision && this.kind.equals(kind)) repeats++;
            else repeats = 0;
            this.lease = lease; this.kind = kind; this.key = key; this.revision = revision;
            this.regionVersion = regionVersion; this.source = source;
            this.cohort = Source.valueOf(source);
            this.jobStart = wallClock.getAsLong(); this.stageStart = jobStart;
            if (this.assignedLease == lease) {
                this.assignedBegin = jobStart;
            }
            this.stageCpuStart = cpuClock.getAsLong(); this.stageAllocatedStart = allocationClock.getAsLong();
            this.jobCpuStart = stageCpuStart; this.jobAllocatedStart = stageAllocatedStart;
            this.stage = kind.contains("Section") ? Stage.TASK : Stage.METADATA;
            this.sequence++;
        }
        synchronized void stage(String name) {
            transition(Stage.valueOf(name));
        }
        private void transition(Stage next) {
            long now = wallClock.getAsLong(); finishStage(now);
            this.stage = next; this.stageStart = now; this.sequence++;
        }
        synchronized int push(String name) {
            int previous = this.stage.ordinal(); stage(name); return previous;
        }
        synchronized void pop(int previous) {
            if (previous >= 0 && previous < STAGES) transition(STAGE_VALUES[previous]);
        }
        private void finishStage(long now) {
            long cpu = cpuClock.getAsLong(), allocated = allocationClock.getAsLong();
            long cpuDelta = delta(cpu, stageCpuStart), allocatedDelta = delta(allocated, stageAllocatedStart);
            this.stageCpuStart = cpu; this.stageAllocatedStart = allocated;
            if (stage == Stage.IDLE) return;
            int index = stage.ordinal(); long elapsed = Math.max(0, now - stageStart);
            counts[index]++; totals[index] += elapsed; maxima[index] = Math.max(maxima[index], elapsed);
            add(cohort, stage, elapsed, cpuDelta, allocatedDelta);
        }
        private void add(Source source, Stage stage, long wall, long cpu, long allocated) {
            this.lifetime.add(source, stage, wall, cpu, allocated); this.window.add(source, stage, wall, cpu, allocated);
        }
        synchronized void outcome(String name, long bytes) {
            Outcome outcome = Outcome.valueOf(name);
            outcomes[outcome.ordinal()] += name.endsWith("_BYTES") ? bytes : 1;
        }
        synchronized void end() {
            long now = wallClock.getAsLong(); finishStage(now); lastEnd = now; jobs++;
            add(cohort, Stage.JOB_TOTAL, Math.max(0, now - jobStart), delta(stageCpuStart, jobCpuStart), delta(stageAllocatedStart, jobAllocatedStart));
            stage = Stage.IDLE; stageStart = now; sequence++;
        }
        synchronized void closing() { closing = true; }
        synchronized boolean current(long lease, long sequence) { return this.lease == lease && this.sequence == sequence; }
        synchronized Copy copy() {
            long now = wallClock.getAsLong();
            var copy = new Copy(lease, key, revision, regionVersion, jobStart, stageStart, lastEnd, jobs,
                    sequence, repeats, kind, source, stage, closing, counts.clone(), totals.clone(), maxima.clone(), outcomes.clone(),
                    lifetime.copy(), window.copy(), Math.max(0, now - windowStart), assignedLease < 0 ? 0 : Math.max(0, now - assignedStart));
            window.clear(); windowStart = now; return copy;
        }
    }
    private static long delta(long end, long start) { return end >= 0 && start >= 0 && end >= start ? end - start : -1; }
    record Copy(long lease, long key, long revision, long regionVersion, long jobStart, long stageStart,
                long lastEnd, long jobs, long sequence, long repeats, String kind, String source, Stage stage,
                boolean closing, long[] counts, long[] totals, long[] maxima, long[] outcomes,
                Metrics lifetime, Metrics window, long windowNanos, long assignedAgeNanos) {}

    private static Source source(ClientSession.Session.WorkerTask task) {
        if (task instanceof ClientSession.Session.SectionWorkerTask section) return Source.valueOf(section.source().name());
        if (task instanceof ClientSession.Session.CacheOnlyTask) return Source.CACHE_ONLY_NETWORK;
        if (task instanceof ClientSession.Session.EmptyWorkerTask) return Source.EMPTY;
        return Source.METADATA;
    }
    static void assigned(Work work, ClientSession.Session.WorkerTask task, WorkerResource.Lease lease) {
        work.assigned(lease.generation(), source(task));
    }

    static void begin(Work work, ClientSession.Session.WorkerTask task, WorkerResource.Lease lease) {
        long key = 0, revision = 0, version = 0; String source = "METADATA";
        if (task instanceof ClientSession.Session.SectionWorkerTask section) {
            key = section.ticket().key(); revision = section.ticket().demandRevision();
            version = section.ticket().regionGeneration(); source = section.source().name();
        } else if (task instanceof ClientSession.Session.CacheOnlyTask cache) {
            key = cache.job().key; revision = cache.job().ticket;
            version = cache.reply().generation(); source = "CACHE_ONLY_NETWORK";
        } else if (task instanceof ClientSession.Session.EmptyWorkerTask empty) {
            key = empty.ticket().key(); revision = empty.ticket().demandRevision(); source = "EMPTY";

        }
        work.begin(lease.generation(), task.getClass().getSimpleName(), key, revision, version, source);
    }

    static String sample(Work work, long now, ThreadMXBean threads, Consumer<String> emit) {
        return sample(work, work.copy(), now, threads, emit);
    }
    private static String sample(Work work, Copy copy, long now, ThreadMXBean threads, Consumer<String> emit) {
        long cpu = -1; String cpuStatus = "UNAVAILABLE";
        try {
            if (!threads.isThreadCpuTimeSupported()) cpuStatus = "UNSUPPORTED";
            else if (!threads.isThreadCpuTimeEnabled()) cpuStatus = "DISABLED";
            else { cpu = threads.getThreadCpuTime(work.thread); cpuStatus = cpu < 0 ? "UNAVAILABLE" : "AVAILABLE"; }
        } catch (UnsupportedOperationException | SecurityException ignored) { cpuStatus = "UNAVAILABLE"; }
        long cpuDelta = cpu >= 0 && work.priorCpu >= 0 ? cpu - work.priorCpu : -1;
        long wallDelta = work.priorSample == 0 ? -1 : now - work.priorSample;
        long completedDelta = copy.jobs - work.priorJobs;
        boolean sameStage = work.observedLease == copy.lease && work.observedSequence == copy.sequence;
        if (copy.stage != Stage.IDLE && sameStage) {
            try {
                var info = threads.getThreadInfo(work.thread, 24);
                if (info != null) {
                    String stack = info.getThreadState() + " lock=" + info.getLockInfo() + " owner=" + info.getLockOwnerId()
                            + " at=" + Arrays.toString(info.getStackTrace());
                    String signature = copy.lease + ":" + copy.sequence + ":" + stack;
                    if (info.getLockOwnerId() > 0) {
                        var holder = threads.getThreadInfo(info.getLockOwnerId(), 16);
                        if (holder != null) stack += " holderName=" + holder.getThreadName()
                                + " holderAt=" + Arrays.toString(holder.getStackTrace());
                    }
                    if (!signature.equals(work.signature) && work.current(copy.lease, copy.sequence)) {
                        emit.accept("VOXY_WORKER_STALL session=" + work.session + " slot=" + work.slot
                                + " thread=" + work.thread + " lease=" + copy.lease + " stage=" + copy.stage
                                + " stageAgeNs=" + Math.max(0, now - copy.stageStart) + " " + stack
                                + " completedStageCounts=" + Arrays.toString(copy.counts)
                                + " completedStageTotalNs=" + Arrays.toString(copy.totals)
                                + " completedStageMaxNs=" + Arrays.toString(copy.maxima));
                        work.signature = signature;
                    }
                }
            } catch (UnsupportedOperationException | SecurityException ignored) { /* Explicit CPU availability remains in summary. */ }
        } else work.signature = null;
        work.observedLease = copy.lease; work.observedSequence = copy.sequence;
        work.priorCpu = cpu; work.priorSample = now; work.priorJobs = copy.jobs;
        long allocated = -1;
        try {
            if (threads instanceof com.sun.management.ThreadMXBean bean && bean.isThreadAllocatedMemorySupported()
                    && bean.isThreadAllocatedMemoryEnabled()) allocated = bean.getThreadAllocatedBytes(work.thread);
        } catch (UnsupportedOperationException | SecurityException ignored) { }
        return " worker[" + work.slot + "]={thread=" + work.thread + " lease=" + copy.lease
                + " task=" + copy.kind + " key=" + copy.key + " revision=" + copy.revision
                + " regionVersion=" + copy.regionVersion + " source=" + copy.source + " stage=" + copy.stage
                + " closing=" + copy.closing + " jobAgeNs=" + (copy.stage == Stage.IDLE ? 0 : Math.max(0, now - copy.jobStart))
                + " stageAgeNs=" + (copy.stage == Stage.IDLE ? 0 : Math.max(0, now - copy.stageStart))
                + " assignedAgeNs=" + copy.assignedAgeNanos
                + " lastCompletionNs=" + copy.lastEnd + " completedTotal=" + copy.jobs + " completedDelta=" + completedDelta
                + " repeatedTask=" + copy.repeats + " cpu=" + cpuStatus + " cpuDeltaNs=" + cpuDelta
                + " cpuSameStage=" + sameStage + " sampleWallNs=" + wallDelta
                + " localCodecNativeHighWaterBytes=" + work.nativeHighWater
                + " threadAllocatedBytes=" + allocated
                + "}";
    }

    static String sample(ClientSession.Session session, long now) {
        StringBuilder result = new StringBuilder(" workerStages=" + Arrays.toString(STAGE_VALUES)
                + " workerOutcomes=" + Arrays.toString(Outcome.values()));
        long[] counts = new long[STAGES], totals = counts.clone(), maxima = counts.clone();
        long[] outcomes = new long[Outcome.values().length];
        Metrics lifetime = new Metrics(), window = new Metrics();
        long windowMinNanos = Long.MAX_VALUE, windowMaxNanos = 0;
        if (session.metadataWorker.debugWork instanceof Work work) {
            Copy copy = work.copy();
            result.append(sample(work, copy, now, THREADS, ClientLodDebug::workerEvidence));
            aggregate(copy, counts, totals, maxima, outcomes);
            lifetime.add(copy.lifetime); window.add(copy.window);
            windowMinNanos = Math.min(windowMinNanos, copy.windowNanos); windowMaxNanos = Math.max(windowMaxNanos, copy.windowNanos);
        }
        for (var worker : session.sectionWorkers) {
            if (worker.debugWork instanceof Work work) {
                Copy copy = work.copy();
                result.append(sample(work, copy, now, THREADS, ClientLodDebug::workerEvidence));
                result.append(" saveSlot[").append(worker.index).append("]={pending=")
                        .append(worker.resource.savePending()).append(" admissionReleased=")
                        .append(worker.resource.releaseRequested()).append('}');
                aggregate(copy, counts, totals, maxima, outcomes);
                lifetime.add(copy.lifetime); window.add(copy.window);
                windowMinNanos = Math.min(windowMinNanos, copy.windowNanos); windowMaxNanos = Math.max(windowMaxNanos, copy.windowNanos);
            }
        }
        result.append(" completedStageCounts=").append(Arrays.toString(counts))
                .append(" completedStageTotalNs=").append(Arrays.toString(totals))
                .append(" completedStageMaxNs=").append(Arrays.toString(maxima))
                .append(" workerOutcomeTotals=").append(Arrays.toString(outcomes))
                .append(" workerTimingSchema=2 workerTimingMode=EXCLUSIVE_COMPLETED_SEGMENTS")
                .append(" workerTimingOverlappingStages=ASSIGN_TO_BEGIN,BEGIN_TO_COMPLETE,COMPLETE_TO_CLAIM,CLAIM_TO_REUSE,SLOT_LEASE,SLOT_LEASE_INCOMPLETE,JOB_TOTAL")
                .append(" workerTimingLeaseCohort=COMMITTED_AT_REUSE")
                .append(" workerTimingWindowMinNs=").append(windowMinNanos == Long.MAX_VALUE ? 0 : windowMinNanos)
                .append(" workerTimingWindowMaxNs=").append(windowMaxNanos)
                .append(" workerTimingLifetime=").append(lifetime.text())
                .append(" workerTimingWindow=").append(window.text());
        return result.append(runtimeSample()).toString();
    }
    private static void aggregate(Copy copy, long[] counts, long[] totals, long[] maxima, long[] outcomes) {
        for (int i = 0; i < counts.length; i++) {
            counts[i] += copy.counts[i]; totals[i] += copy.totals[i]; maxima[i] = Math.max(maxima[i], copy.maxima[i]);
        }
        for (int i = 0; i < outcomes.length; i++) outcomes[i] += copy.outcomes[i];
    }
    static long allocatedBytes() {
        try {
            if (THREADS instanceof com.sun.management.ThreadMXBean bean && bean.isThreadAllocatedMemorySupported()
                    && bean.isThreadAllocatedMemoryEnabled()) return bean.getThreadAllocatedBytes(Thread.currentThread().threadId());
        } catch (UnsupportedOperationException | SecurityException ignored) {}
        return -1;
    }
    static long samplerCpuTime() {
        try {
            if (THREADS.isCurrentThreadCpuTimeSupported() && THREADS.isThreadCpuTimeEnabled())
                return THREADS.getCurrentThreadCpuTime();
        } catch (UnsupportedOperationException | SecurityException ignored) {}
        return -1;
    }
}
