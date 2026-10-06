package me.cortex.voxy.client.lod;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.TimeUnit;

/** Debug-only owner-published summaries. Values never retain their weak session keys. */
final class SessionDebugTelemetry {
    static final long INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(1);
    private static final Map<ClientSession.Session, Stats> SESSIONS = new WeakHashMap<>();
    private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();
    private static final int WAIT_PHASE = 16;
    private static final String OWNER_PHASE_ORDER = "CONNECT,WINDOW,WORKERS,CONTROLS,NETWORK_REPLIES,EVENTS,DEMAND,METADATA,CATALOGS,REGIONS,DOWNLOADS,PUBLICATIONS,STAGES,DEBUG_SAMPLE,HEALTH,RESET,WAIT";
    private static final String HANDOFF_ORDER = "MESH_TO_CLAIM,CLAIM_TO_SUBMIT,SUBMIT_TO_ADMISSION,ADMISSION_TO_OBSERVE,OBSERVE_TO_REUSE";

    /** Mutated only by the owner. No session, worker, task or buffer reference is retained. */
    private static final class OwnerTiming {
        final long thread = Thread.currentThread().threadId(), start = System.nanoTime();
        final long[] counts = new long[17], totals = new long[17], maxima = new long[17];
        final OwnerLoadingTelemetry loading = new OwnerLoadingTelemetry();
        long loops, busy, phaseStart, priorCpu = -1, priorCpuSample;
        int phase = -1;

        void transition(int next, long now) {
            this.loading.phase(next, now);
            if (this.phase >= 0) {
                long elapsed = Math.max(0, now - this.phaseStart);
                this.counts[this.phase]++;
                this.totals[this.phase] += elapsed;
                this.maxima[this.phase] = Math.max(this.maxima[this.phase], elapsed);
                if (this.phase != WAIT_PHASE) this.busy += elapsed;
            }
            this.phase = next;
            this.phaseStart = now;
        }

        String sample(long now) {
            long cpu = -1;
            long cpuSampleNanos = System.nanoTime();
            String status = "UNAVAILABLE";
            try {
                if (!THREADS.isThreadCpuTimeSupported()) status = "UNSUPPORTED";
                else if (!THREADS.isThreadCpuTimeEnabled()) status = "DISABLED";
                else {
                    cpu = THREADS.getThreadCpuTime(this.thread);
                    status = cpu < 0 ? "UNAVAILABLE" : "AVAILABLE";
                }
            } catch (UnsupportedOperationException | SecurityException ignored) { }
            boolean deltaValid = cpu >= 0 && this.priorCpu >= 0 && cpu >= this.priorCpu
                    && cpuSampleNanos > this.priorCpuSample;
            StringBuilder text = new StringBuilder(" ownerThread=").append(this.thread)
                    .append(" ownerLoops=").append(this.loops)
                    .append(" ownerBusyNanos=").append(this.busy)
                    .append(" ownerWallNanos=").append(Math.max(0, now - this.start))
                    .append(" ownerPhaseOrder=").append(OWNER_PHASE_ORDER)
                    .append(" ownerPhaseCount=").append(Arrays.toString(this.counts))
                    .append(" ownerPhaseNanos=").append(Arrays.toString(this.totals))
                    .append(" ownerPhaseMaxNanos=").append(Arrays.toString(this.maxima))
                    .append(" ownerActivePhase=").append(this.phase)
                    .append(" ownerActivePhaseAgeNanos=").append(this.phase < 0 ? 0 : Math.max(0, now - this.phaseStart))
                    .append(" ownerCpu=").append(status)
                    // Thread CPU time is cumulative from this dedicated thread's start.
                    .append(" ownerCpuNanos=").append(cpu)
                    .append(" ownerCpuSample=").append(cpu < 0 ? "UNAVAILABLE" : deltaValid ? "DELTA" : "BASELINE");
            if (deltaValid) {
                long elapsed = cpuSampleNanos - this.priorCpuSample, delta = cpu - this.priorCpu;
                text.append(" ownerCpuDeltaNanos=").append(delta)
                        .append(" ownerCpuSampleWallNanos=").append(elapsed)
                        .append(" ownerOneCorePercent=").append(String.format(Locale.ROOT, "%.3f", 100.0 * delta / elapsed));
            }
            this.priorCpu = cpu;
            this.priorCpuSample = cpu < 0 ? 0 : cpuSampleNanos;
            return text.toString();
        }
    }

    private static final class Stats {
        final long start = System.nanoTime();
        long firstLocal, hello, localViews, localActivations, validated, replacements, invalidations, metadataBytes;
        long localAbsent, localPatches;
        long admissionReleases, meshToLeaseReleaseNanos, maxMeshToLeaseReleaseNanos;
        long workerReuses, publishedToReusableNanos, maxPublishedToReusableNanos;
        long nextSample;
        long subscriptions, subscriptionPeak, subscriptionRequests, subscriptionReleases, subscriptionStale;
        long windowNanos;
        final LocalSection[] lastActivated = new LocalSection[5];
        final long[] cachedActivations = new long[5], freshActivations = new long[5], firstCachedNanos = new long[5];
        final long[] discoveredBindings = new long[5];
        long discoveredRoots;
        OwnerTiming owner;
        final long[] handoffCounts = new long[5], handoffNanos = new long[5], handoffMaxNanos = new long[5];
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

    static Object ownerCreated(ClientSession.Session session) {
        if (Thread.currentThread() != session.thread) {
            throw new IllegalStateException("debug owner timing must start on the session owner");
        }
        var timing = new OwnerTiming();
        state(session).owner = timing;
        return timing;
    }

    static void ownerTurn(Object state) {
        if (state instanceof OwnerTiming timing) timing.loops++;
    }

    static void ownerPhase(Object state, int nextPhase) {
        if (state instanceof OwnerTiming timing && nextPhase >= 0 && nextPhase <= WAIT_PHASE)
            timing.transition(nextPhase, System.nanoTime());
    }

    static void ownerDetail(Object state, int detail) {
        if (state instanceof OwnerTiming timing) timing.loading.detail(detail);
    }
    static void ownerEvent(Object state, int event, long count) {
        if (state instanceof OwnerTiming timing) timing.loading.event(event, count);
    }
    static void ownerNoSlot(Object state, ClientSession.Session session) {
        if (state instanceof OwnerTiming timing) timing.loading.noSlot(session);
    }

    static void ownerFinished(Object state) {
        if (state instanceof OwnerTiming timing) timing.transition(-1, System.nanoTime());
    }

    static void handoff(ClientSession.Session session, int stage, long nanos) {
        // Terminal cleanup may release a resource outside the owner; omit that cohort.
        if (Thread.currentThread() != session.thread || stage < 0 || stage >= 5 || nanos < 0) return;
        var stats = state(session);
        stats.handoffCounts[stage]++;
        stats.handoffNanos[stage] += nanos;
        stats.handoffMaxNanos[stage] = Math.max(stats.handoffMaxNanos[stage], nanos);
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
            case "localAbsent" -> stats.localAbsent++;
            case "localPatch" -> stats.localPatches++;
            case "localActivation" -> {
                stats.localActivations++;
                if (stats.firstLocal == 0) stats.firstLocal = System.nanoTime() - stats.start;
            }
            case "validated" -> stats.validated++;
            case "replacement" -> stats.replacements++;
            case "invalidation", "worldCorrection" -> stats.invalidations++;
            case "metadata" -> stats.metadataBytes += bytes;
            case "workerReusable" -> {
                stats.workerReuses++; stats.publishedToReusableNanos += bytes;
                stats.maxPublishedToReusableNanos = Math.max(stats.maxPublishedToReusableNanos, bytes);
            }
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
            if (demand.networkWanted) pendingRefresh++;
            if (demand.candidate == SectionDemandTable.CandidateState.RENDERER_OWNED
                    && demand.geometryBytes > 0 && demand.workLease == null
                    && demand.publication != null && demand.publication.rendererAdmitted()) admittedPending++;
        }
        String startup = " transportHeld=" + transportHeld + " localViews=" + stats.localViews
                + " localAbsent=" + stats.localAbsent + " localPatches=" + stats.localPatches
                + " usableBindingDiscoveriesLod=" + java.util.Arrays.toString(stats.discoveredBindings)
                + " usableDemandedRootDiscoveries=" + stats.discoveredRoots
                + " metadataPendingRegions=" + (session.associationPending ? 1 : 0)
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
                + " pendingReleases=" + session.interestDrops.size() + " staleRegionResponses=" + stats.subscriptionStale
                + " windowReconciliations=" + session.windowReconciliations + " windowReconcileNanos=" + stats.windowNanos
                + " localActivations=" + stats.localActivations + " firstLocalNanos=" + stats.firstLocal
                + " firstHelloNanos=" + stats.hello + " validatedViews=" + stats.validated
                + " replacements=" + stats.replacements + " invalidations=" + stats.invalidations
                + " metadataNetworkBytes=" + stats.metadataBytes
                + " catalogueFrames=" + session.catalogueFrames
                + " catalogueCompressedBytes=" + session.catalogueCompressedBytes
                + " catalogueCacheHits=" + session.catalogueCacheHits
                + " catalogueValidationNanos=" + session.catalogueValidationNanos
                + " admissionReleases=" + stats.admissionReleases
                + " workerReuses=" + stats.workerReuses
                + " publishedToReusableNanos=" + stats.publishedToReusableNanos
                + " maxPublishedToReusableNanos=" + stats.maxPublishedToReusableNanos
                + " admittedPending=" + admittedPending
                + " meshToLeaseReleaseNanos=" + stats.meshToLeaseReleaseNanos
                + " maxMeshToLeaseReleaseNanos=" + stats.maxMeshToLeaseReleaseNanos
                + " handoffOrder=" + HANDOFF_ORDER
                + " handoffCount=" + Arrays.toString(stats.handoffCounts)
                + " handoffNanos=" + Arrays.toString(stats.handoffNanos)
                + " handoffMaxNanos=" + Arrays.toString(stats.handoffMaxNanos)
                + (stats.owner == null ? " ownerTiming=NOT_STARTED" : stats.owner.sample(now) + stats.owner.loading.summary(session));
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
