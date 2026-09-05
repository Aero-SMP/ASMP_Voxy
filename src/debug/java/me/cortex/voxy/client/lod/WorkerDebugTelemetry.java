package me.cortex.voxy.client.lod;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.Arrays;
import java.util.function.Consumer;

/** One bounded record per actual worker. No task, session, Thread or buffer references retained. */
final class WorkerDebugTelemetry {
    enum Stage { IDLE, TASK, METADATA, INDEX_DECODE, CACHE_READ, DECOMPRESS, DECODE_VALIDATE,
        REQUEST_MODELS, CACHE_WRITE, CHECK_MODELS, MESH, CACHE_QUARANTINE, RESULT_READY }
    enum Outcome { CACHE_HIT, CACHE_MISS, CACHE_CORRUPT, MODEL_WAIT, FAILURE, COMPRESSED_BYTES, CANONICAL_BYTES, MESH_BYTES }
    private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();

    static final class Work {
        final long session, thread;
        final int slot;
        long lease, key, revision, regionVersion, jobStart, stageStart, lastEnd, jobs, sequence, repeats;
        String kind = "NONE", source = "NONE";
        Stage stage = Stage.IDLE;
        boolean closing;
        final long[] counts = new long[Stage.values().length], totals = counts.clone(), maxima = counts.clone();
        final long[] outcomes = new long[Outcome.values().length];
        // Owner-sampler-only state: one previous observation, never an event/section history.
        long observedLease = -1, observedSequence = -1, priorCpu = -1, priorJobs, priorSample;
        String signature;
        Work(long session, int slot, long thread) { this.session = session; this.slot = slot; this.thread = thread; }
        synchronized void begin(long lease, String kind, long key, long revision, long regionVersion, String source) {
            if (kind.equals("SectionWorkerTask") && this.jobs > 0 && this.key == key
                    && this.revision == revision && this.kind.equals(kind)) repeats++;
            else repeats = 0;
            this.lease = lease; this.kind = kind; this.key = key; this.revision = revision;
            this.regionVersion = regionVersion; this.source = source;
            this.jobStart = System.nanoTime(); this.stageStart = jobStart;
            this.stage = kind.contains("Section") ? Stage.TASK : Stage.METADATA;
            this.sequence++;
        }
        synchronized void stage(String name) {
            long now = System.nanoTime(); finishStage(now);
            this.stage = Stage.valueOf(name); this.stageStart = now; this.sequence++;
        }
        private void finishStage(long now) {
            if (stage == Stage.IDLE) return;
            int index = stage.ordinal(); long elapsed = Math.max(0, now - stageStart);
            counts[index]++; totals[index] += elapsed; maxima[index] = Math.max(maxima[index], elapsed);
        }
        synchronized void outcome(String name, long bytes) {
            Outcome outcome = Outcome.valueOf(name);
            outcomes[outcome.ordinal()] += name.endsWith("_BYTES") ? bytes : 1;
        }
        synchronized void end() {
            long now = System.nanoTime(); finishStage(now); lastEnd = now; jobs++;
            stage = Stage.IDLE; stageStart = now; sequence++;
        }
        synchronized void closing() { closing = true; }
        synchronized boolean current(long lease, long sequence) { return this.lease == lease && this.sequence == sequence; }
        synchronized Copy copy() {
            return new Copy(lease, key, revision, regionVersion, jobStart, stageStart, lastEnd, jobs,
                    sequence, repeats, kind, source, stage, closing, counts.clone(), totals.clone(), maxima.clone(), outcomes.clone());
        }
    }
    record Copy(long lease, long key, long revision, long regionVersion, long jobStart, long stageStart,
                long lastEnd, long jobs, long sequence, long repeats, String kind, String source, Stage stage,
                boolean closing, long[] counts, long[] totals, long[] maxima, long[] outcomes) {}

    static void begin(Work work, ClientSession.Session.WorkerTask task, WorkerResource.Lease lease) {
        long key = 0, revision = 0, version = 0; String source = "METADATA";
        if (task instanceof ClientSession.Session.SectionWorkerTask section) {
            key = section.ticket().key(); revision = section.ticket().demandRevision();
            version = section.index().generation(); source = section.source().name();
        } else if (task instanceof ClientSession.Session.EmptyWorkerTask empty) {
            key = empty.ticket().key(); revision = empty.ticket().demandRevision(); source = "EMPTY";
        } else if (task instanceof ClientSession.Session.IndexWorkerTask index) {
            key = index.region(); revision = index.revision(); version = index.generation(); source = "INDEX";
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
        return " worker[" + work.slot + "]={thread=" + work.thread + " lease=" + copy.lease
                + " task=" + copy.kind + " key=" + copy.key + " revision=" + copy.revision
                + " regionVersion=" + copy.regionVersion + " source=" + copy.source + " stage=" + copy.stage
                + " closing=" + copy.closing + " jobAgeNs=" + (copy.stage == Stage.IDLE ? 0 : Math.max(0, now - copy.jobStart))
                + " stageAgeNs=" + (copy.stage == Stage.IDLE ? 0 : Math.max(0, now - copy.stageStart))
                + " lastCompletionNs=" + copy.lastEnd + " completedTotal=" + copy.jobs + " completedDelta=" + completedDelta
                + " repeatedTask=" + copy.repeats + " cpu=" + cpuStatus + " cpuDeltaNs=" + cpuDelta
                + " cpuSameStage=" + sameStage + " sampleWallNs=" + wallDelta
                + "}";
    }

    static String sample(ClientSession.Session session, long now) {
        StringBuilder result = new StringBuilder(" workerStages=" + Arrays.toString(Stage.values())
                + " workerOutcomes=" + Arrays.toString(Outcome.values()));
        long[] counts = new long[Stage.values().length], totals = counts.clone(), maxima = counts.clone();
        long[] outcomes = new long[Outcome.values().length];
        if (session.metadataWorker.debugWork instanceof Work work) {
            Copy copy = work.copy();
            result.append(sample(work, copy, now, THREADS, ClientLodDebug::workerEvidence));
            aggregate(copy, counts, totals, maxima, outcomes);
        }
        for (var worker : session.sectionWorkers) {
            if (worker.debugWork instanceof Work work) {
                Copy copy = work.copy();
                result.append(sample(work, copy, now, THREADS, ClientLodDebug::workerEvidence));
                aggregate(copy, counts, totals, maxima, outcomes);
            }
        }
        result.append(" completedStageCounts=").append(Arrays.toString(counts))
                .append(" completedStageTotalNs=").append(Arrays.toString(totals))
                .append(" completedStageMaxNs=").append(Arrays.toString(maxima))
                .append(" workerOutcomeTotals=").append(Arrays.toString(outcomes));
        return result.toString();
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
