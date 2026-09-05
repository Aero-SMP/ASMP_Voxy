package me.cortex.voxy.client.lod;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.TimeUnit;

/** Debug-only owner-published summaries. Values never retain their weak session keys. */
final class SessionDebugTelemetry {
    static final long INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(1);
    private static final Map<ClientSession.Session, Stats> SESSIONS = new WeakHashMap<>();

    private static final class Stats {
        final long start = System.nanoTime();
        long firstLocal, hello, localViews, localActivations, validated, replacements, invalidations, metadataBytes;
        long admissionReleases, meshToLeaseReleaseNanos, maxMeshToLeaseReleaseNanos;
        long nextSample;
        volatile Summary latest;
    }

    record Summary(long sessionId, long capturedNanos, String text) {
        String aged(long now) {
            return text + " sessionId=" + sessionId + " sessionSampleNanos=" + capturedNanos
                    + " sessionSampleAgeNanos=" + Math.max(0, now - capturedNanos);
        }
    }

    private static synchronized Stats state(ClientSession.Session session) {
        return SESSIONS.computeIfAbsent(session, ignored -> new Stats());
    }

    static void event(ClientSession.Session session, String event, long bytes) {
        var stats = state(session);
        switch (event) {
            case "hello" -> { if (stats.hello == 0) stats.hello = System.nanoTime() - stats.start; }
            case "localView" -> stats.localViews++;
            case "localActivation" -> {
                stats.localActivations++;
                if (stats.firstLocal == 0) stats.firstLocal = System.nanoTime() - stats.start;
            }
            case "validated" -> stats.validated++;
            case "replacement" -> stats.replacements++;
            case "invalidation", "worldCorrection" -> stats.invalidations++;
            case "metadata" -> stats.metadataBytes += bytes;
        }
    }

    static void admissionReleased(ClientSession.Session session, long completedNanos) {
        var stats = state(session);
        long elapsed = Math.max(0, System.nanoTime() - completedNanos);
        stats.admissionReleases++;
        stats.meshToLeaseReleaseNanos += elapsed;
        stats.maxMeshToLeaseReleaseNanos = Math.max(stats.maxMeshToLeaseReleaseNanos, elapsed);
    }

    static void capture(ClientSession.Session session, long now, boolean transportHeld) {
        if (Thread.currentThread() != session.thread) {
            throw new IllegalStateException("debug capture must run on the session owner");
        }
        var stats = state(session);
        if (stats.latest != null && now - stats.nextSample < 0) return;
        stats.nextSample = now + INTERVAL_NANOS;
        long started = System.nanoTime(), allocated = WorkerDebugTelemetry.allocatedBytes();
        long cpuStarted = WorkerDebugTelemetry.samplerCpuTime();
        long admittedPending = 0;
        for (var demand : session.demands.values()) {
            if (demand.candidate == SectionDemandTable.CandidateState.RENDERER_OWNED
                    && demand.geometryBytes > 0 && demand.workLease == null
                    && demand.publication != null && demand.publication.rendererAdmitted()) admittedPending++;
        }
        String startup = " transportHeld=" + transportHeld + " localViews=" + stats.localViews
                + " localActivations=" + stats.localActivations + " firstLocalNanos=" + stats.firstLocal
                + " firstHelloNanos=" + stats.hello + " validatedViews=" + stats.validated
                + " replacements=" + stats.replacements + " invalidations=" + stats.invalidations
                + " metadataNetworkBytes=" + stats.metadataBytes
                + " admissionReleases=" + stats.admissionReleases
                + " admittedPending=" + admittedPending
                + " meshToLeaseReleaseNanos=" + stats.meshToLeaseReleaseNanos
                + " maxMeshToLeaseReleaseNanos=" + stats.maxMeshToLeaseReleaseNanos;
        // No shared debug monitor is held while scanning, reading renderer counters or formatting.
        String workers = WorkerDebugTelemetry.sample(session, now);
        String summary = session.snapshot(startup) + workers
                + (session.metadata == null ? " cacheInventory=NOT_OPEN" : session.metadata.budget.snapshot());
        long afterAllocated = WorkerDebugTelemetry.allocatedBytes();
        long cpuEnded = WorkerDebugTelemetry.samplerCpuTime();
        stats.latest = new Summary(session.id, now, summary
                + " debugSampleBuildNs=" + (System.nanoTime() - started)
                + " debugSampleCpuNs=" + (cpuStarted < 0 || cpuEnded < 0 ? -1 : cpuEnded - cpuStarted)
                + " debugSampleAllocatedBytes=" + (allocated < 0 || afterAllocated < 0 ? -1 : afterAllocated - allocated)
                + " debugSampleChars=" + summary.length());
    }

    static synchronized Summary latest(ClientSession.Session session) {
        var stats = SESSIONS.get(session);
        return stats == null ? null : stats.latest;
    }

    static String read(ClientSession.Session session, long now) {
        var summary = latest(session);
        return summary == null ? "regional=SAMPLING sessionId=" + session.id
                : summary.aged(now);
    }
}
