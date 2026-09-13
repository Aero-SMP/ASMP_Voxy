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
        long subscriptions, subscriptionPeak, subscriptionRequests, subscriptionReleases, subscriptionStale;
        long windowNanos;
        final LocalSection[] lastActivated = new LocalSection[5];
        final long[] cachedActivations = new long[5], freshActivations = new long[5], firstCachedNanos = new long[5];
        final long[] discoveredBindings = new long[5];
        long discoveredRoots;
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
            case "subscriptionRequest" -> {
                stats.subscriptionRequests++;
                stats.subscriptions += bytes;
                stats.subscriptionPeak = Math.max(stats.subscriptionPeak, stats.subscriptions);
            }
            case "subscriptionRelease" -> { stats.subscriptionReleases++; stats.subscriptions--; }
            case "subscriptionReset" -> stats.subscriptions = 0;
            case "subscriptionStale" -> stats.subscriptionStale++;
            case "subscriptionWindow" -> stats.windowNanos += bytes;
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

    static void discovered(ClientSession.Session session, java.util.Map<Long, LocalSection> sections) {
        var stats = state(session);
        for (var section : sections.values()) {
            if (section.kind() == LocalSection.ABSENT) continue;
            int level = me.cortex.voxy.client.core.rendering.SectionKey.level(section.key());
            stats.discoveredBindings[level]++;
            if (level == 4 && session.demands.get(section.key()) != null) stats.discoveredRoots++;
        }
    }

    static void admissionReleased(ClientSession.Session session, long completedNanos) {
        var stats = state(session);
        long elapsed = Math.max(0, System.nanoTime() - completedNanos);
        stats.admissionReleases++;
        stats.meshToLeaseReleaseNanos += elapsed;
        stats.maxMeshToLeaseReleaseNanos = Math.max(stats.maxMeshToLeaseReleaseNanos, elapsed);
    }

    static void activated(ClientSession.Session session, LocalSection section, boolean cacheHit) {
        if (section == null || section.kind() != LocalSection.DATA) return;
        var stats = state(session);
        int lod = me.cortex.voxy.client.core.rendering.SectionKey.level(section.key());
        stats.lastActivated[lod] = section;
        if (cacheHit) {
            stats.cachedActivations[lod]++;
            if (stats.firstCachedNanos[lod] == 0) stats.firstCachedNanos[lod] = System.nanoTime() - stats.start;
        } else stats.freshActivations[lod]++;
    }

    private static String localSummary(Stats stats) {
        var text = new StringBuilder();
        for (int lod = 0; lod < 5; lod++) {
            text.append(" cachedActivatedLod").append(lod).append('=').append(stats.cachedActivations[lod])
                    .append(" freshActivatedLod").append(lod).append('=').append(stats.freshActivations[lod])
                    .append(" firstCachedNanosLod").append(lod).append('=').append(stats.firstCachedNanos[lod]);
            var section = stats.lastActivated[lod];
            if (section != null) text.append(" lastActivatedLod").append(lod).append('=')
                    .append(Long.toUnsignedString(section.key(), 16)).append(':')
                    .append(java.util.HexFormat.of().formatHex(section.fingerprint().bytes())).append(':')
                    .append(java.util.HexFormat.of().formatHex(section.catalog().bytes()));
        }
        return text.toString();
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
        long admittedPending = 0, pendingRefresh = 0;
        for (var demand : session.demands.values()) {
            if (demand.pendingIndex != null) pendingRefresh++;
            if (demand.candidate == SectionDemandTable.CandidateState.RENDERER_OWNED
                    && demand.geometryBytes > 0 && demand.workLease == null
                    && demand.publication != null && demand.publication.rendererAdmitted()) admittedPending++;
        }
        String startup = " transportHeld=" + transportHeld + " localViews=" + stats.localViews
                + " usableBindingDiscoveriesLod=" + java.util.Arrays.toString(stats.discoveredBindings)
                + " usableDemandedRootDiscoveries=" + stats.discoveredRoots
                + " metadataPendingRegions=" + session.metadataWrites.size()
                + " retainedSaveInputBytes=" + session.retainedSaveBytes()
                + " resolverBlockNames=" + session.blockNames.size()
                + " resolverBiomeNames=" + session.biomeNames.size()
                + " resolverNameChars=" + session.resolvedNameCharacters
                + " metadataPendingAssociation=" + session.associationPending
                + " metadataOutcomes=" + java.util.Arrays.toString(session.persistenceOutcomes)
                + " metadataOutcomeOrder=PERSISTED,DEFERRED_INVENTORY,OBSOLETE,UNAVAILABLE"
                + " associationPersisted=" + session.associationPersisted
                + " regionPersisted=" + session.regionPersisted
                + " metadataFailure=" + (session.lastPersistenceFailure == null ? "none" : session.lastPersistenceFailure.replace(' ', '_'))
                + " subscriptionWindow=" + session.subscriptionWindow()
                + " acceptedSubscriptions=" + stats.subscriptions + " acceptedSubscriptionPeak=" + stats.subscriptionPeak
                + " subscriptionRequests=" + stats.subscriptionRequests + " subscriptionReleases=" + stats.subscriptionReleases
                + " pendingReleases=" + session.regionReleases.size() + " staleRegionResponses=" + stats.subscriptionStale
                + " windowReconciliations=" + session.windowReconciliations + " windowReconcileNanos=" + stats.windowNanos
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
        String summary = session.snapshot(startup) + workers + " pendingRefresh=" + pendingRefresh
                + " retainedModelArrayBytes=" + session.retainedModelBytes()
                + localSummary(stats) + TransportDebugTelemetry.snapshot()
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
