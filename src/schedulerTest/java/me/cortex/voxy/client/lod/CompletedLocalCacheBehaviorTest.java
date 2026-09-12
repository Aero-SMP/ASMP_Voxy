package me.cortex.voxy.client.lod;

import java.nio.file.Files;
import me.cortex.voxy.client.core.rendering.SectionKey;
import me.cortex.voxy.client.core.rendering.building.SectionMesher;
import me.cortex.voxy.client.core.VoxyRenderSystem.*;
import me.cortex.voxy.client.core.rendering.hierarchical.SectionPublicationState;
import me.cortex.voxy.client.core.rendering.hierarchical.AsyncNodeManager.PublicationProgress;
import static me.cortex.voxy.client.lod.CacheStartupBehaviorTest.*;

/** Real cache/codec/worker/mesher/publication paths with transport bodies deliberately absent. */
public final class CompletedLocalCacheBehaviorTest {
    public static void main(String[] args) throws Exception { run(); }
    static void run() throws Exception {
        for (boolean serverFirst : new boolean[]{false, true}) staleFineAndRejoin(serverFirst);
        unchangedQueuedWork();
        for (int phase = 0; phase < 3; phase++) metadataDuringOwnedWork(phase);
        for (boolean mappingFailure : new boolean[]{false, true}) missingCatalogFallsBack(mappingFailure);
        deletionSurvivesRejoin();
        System.out.println("completed local cache: metadata-first races, all-LOD stale detail/rejoin and unchanged queued work passed");
    }

    private static final class DeferredPublisher extends Publisher {
        boolean busy, hold;
        long progress;
        Deferred waiting;
        final class Deferred extends SectionPublicationState {
            final SectionSubmission submission;
            Deferred(SectionSubmission submission) { this.submission = submission; }
            void activate() {
                this.submission.geometry().free();
                completeUpload(new UploadOutcome(UploadStatus.ACTIVATED, null, null));
                progress++;
            }
            @Override protected void requestRetirement() { markRetired(); }
            @Override protected void stateChanged() {}
        }
        @Override public SubmissionAttempt tryPublishBatch(java.util.List<SectionSubmission> submissions) {
            if (busy) return new SubmissionAttempt(SubmissionStatus.BUSY, java.util.List.of());
            if (!hold) return super.tryPublishBatch(submissions);
            check(submissions.size() == 1, "held publication fixture expected one section");
            waiting = new Deferred(submissions.getFirst());
            return new SubmissionAttempt(SubmissionStatus.ACCEPTED, java.util.List.of(waiting));
        }
        @Override public PublicationProgress progress() { return new PublicationProgress(progress, 0, 0, 0, busy, null); }
    }

    private static void metadataDuringOwnedWork(int phase) throws Exception {
        var root = Files.createTempDirectory("voxy-completed-owned-");
        var a = fixture(1, 0, 240, 1); var b = fixture(2, 0, 224, 1);
        var ready = new java.util.concurrent.atomic.AtomicBoolean(phase != 0);
        var models = new SectionMesher.Models() {
            public int getModelId(int block) { return 1; }
            public long getModelMetadataFromClientId(int model) { return 0; }
            public int getFluidClientStateId(int model) { throw new AssertionError(); }
            public boolean isModelReadyForBlockId(int block) { return ready.get(); }
            public boolean isWaterState(int block) { return false; }
        };
        var constructor = SectionMesher.class.getDeclaredConstructor(SectionMesher.Models.class, java.util.function.IntConsumer.class);
        constructor.setAccessible(true);
        var mesher = constructor.newInstance(models, (java.util.function.IntConsumer) ignored -> {});
        var publisher = new DeferredPublisher(); publisher.busy = phase == 1; publisher.hold = phase == 2;
        try (var legacy = new RegionalMetadataStore(root)) {
            persist(legacy, a, true);
            try (var driver = new Driver(root, publisher, mesher)) {
                var s = driver.session;
                var demand = s.demands.get(KEY);
                driver.until(() -> phase == 0 ? demand.candidate == SectionDemandTable.CandidateState.WAIT_MODELS
                        : phase == 1 ? demand.completedGeometry != null : publisher.waiting != null);
                long revision = demand.revision;
                var lease = demand.workLease;
                s.demands.region(0).announcedGeneration = b.index().generation();
                s.installIndex(b.index(), demand.catalog, false);
                check(demand.revision == revision && demand.content.fingerprint().equals(a.index().sectionFingerprint(340))
                        && java.util.Objects.equals(lease, demand.workLease) && demand.pendingIndex == b.index(),
                        "metadata revoked useful owned A operation at phase " + phase);
                ready.set(true); publisher.busy = false; publisher.progress++;
                if (publisher.waiting != null) { publisher.waiting.activate(); publisher.hold = false; }
                driver.until(() -> demand.installed);
                check(demand.activeContent.fingerprint().equals(a.index().sectionFingerprint(340))
                        && s.uploadedSections == 1 && s.meshedSections == 1 && s.receivedBytes == 0,
                        "owned A did not activate once before B bodies at phase " + phase);
                check(s.completedGeometryBytes == 0 && s.publishingGeometryBytes == 0,
                        "old A allocation remained owned after activation at phase " + phase);
            }
        } finally { cleanup(root); }
    }

    private static void missingCatalogFallsBack(boolean mappingFailure) throws Exception {
        var root = Files.createTempDirectory("voxy-completed-catalog-");
        var a = fixture(1, 0, 240, 1);
        var b = fixture(2, 0, 224, 2);
        try (var metadata = new RegionalMetadataStore(root, true)) {
            awaitInventory(metadata.budget);
            metadata.associate(SERVER, DIMENSION, WORLD, metadata.budget.stamp(), () -> true);
            metadata.saveCatalog(WORLD, DIMENSION, a.catalog(), metadata.budget.stamp(), () -> true);
            metadata.saveCatalog(WORLD, DIMENSION, b.catalog(), metadata.budget.stamp(), () -> true);
            try (var cache = new CompletedSectionCache(metadata, WORLD, DIMENSION)) {
                check(cache.put(LocalSection.from(a.index(), 340, a.catalog().fingerprint()), a.payload(), () -> true), "A not committed");
                check(cache.put(LocalSection.from(b.index(), 340, b.catalog().fingerprint()), b.payload(), () -> true), "B not committed");
            }
            // Exact-path fault injection, accounting retained: B's catalog disappears after commit.
            if (!mappingFailure) synchronized (metadata.budget) {
                check(metadata.budget.delete(metadata.catalogPath(WORLD, DIMENSION, b.catalog().fingerprint())), "catalog fault not injected");
            }
            try (var driver = new Driver(root)) {
                if (mappingFailure) {
                    driver.mapCatalog = false;
                    driver.until(() -> driver.session.pendingCatalogTask != null);
                    var task = driver.session.pendingCatalogTask;
                    check(task.fingerprint().equals(b.catalog().fingerprint()), "fixture did not try B mapping first");
                    // Inject precisely the event emitted by the Minecraft mapping boundary.
                    Class<?> event = Class.forName(ClientSession.class.getName() + "$Event");
                    Class<?> ready = Class.forName(ClientSession.class.getName() + "$CatalogReady");
                    var constructor = ready.getDeclaredConstructors()[0]; constructor.setAccessible(true);
                    var put = ClientSession.Session.class.getDeclaredMethod("putEvent", event); put.setAccessible(true);
                    var handoff = ClientSession.class.getDeclaredField("CATALOG_TASK"); handoff.setAccessible(true);
                    ((java.util.concurrent.atomic.AtomicReference<?>) handoff.get(null)).getAndSet(null);
                    put.invoke(driver.session, constructor.newInstance(task, null,
                            new IllegalArgumentException("injected unavailable client block mapping")));
                    driver.session.pendingCatalogSubmitted = true;
                    driver.session.drainEvents();
                    driver.mapCatalog = true;
                }
                driver.until(() -> driver.session.activeCount == 1);
                check(driver.session.demands.get(KEY).activeContent.catalog().equals(a.catalog().fingerprint())
                        && driver.session.receivedBytes == 0, "missing B catalog lost usable A or reinterpreted its IDs");
                for (int i = 0; i < 50; i++) driver.step();
                check(driver.session.meshedSections == 1 && driver.session.pendingCatalogTask == null,
                        "rejected catalog triggered repeated mapping or duplicate geometry");
            }
        } finally { cleanup(root); }
    }

    private static void deletionSurvivesRejoin() throws Exception {
        var root = Files.createTempDirectory("voxy-completed-absence-");
        var a = fixture(1, 1, 240, 1);
        try (var legacy = new RegionalMetadataStore(root)) {
            persist(legacy, a, true);
            try (var driver = new Driver(root)) {
                var s = driver.session;
                driver.until(() -> s.activeCount == 1);
                s.demands.region(0).subscribed = true;
                s.acceptRegionUnavailable(new RegionalProtocol.RegionUnavailable(0, 0, true));
                driver.until(() -> s.metadataWrites.isEmpty() && s.metadataWorker.idle());
                check(s.cache.legacySealed(0) && s.cache.directory(0).isEmpty(), "absence did not seal legacy fallback");
            }
            try (var driver = new Driver(root)) {
                driver.until(() -> driver.session.demands.region(0).localLoaded);
                for (int i = 0; i < 100; i++) driver.step();
                check(driver.session.activeCount == 0 && driver.session.cacheReads == 0,
                        "deleted legacy terrain resurrected without a fresh recreation");
            }
        } finally { cleanup(root); }
    }

    private static void staleFineAndRejoin(boolean serverFirst) throws Exception {
        var root = Files.createTempDirectory("voxy-completed-pipeline-");
        var a = fixture(1, 1, 240, 1);
        var b = fixture(2, 1, 224, 1);
        try (var legacy = new RegionalMetadataStore(root)) {
            persist(legacy, a, true);
            byte[] oldDescriptor = Files.readAllBytes(legacy.descriptor(WORLD, DIMENSION, 0, 0));
            try (var driver = new Driver(root)) {
                var s = driver.session;
                if (!serverFirst) driver.until(() -> s.activeCount == 1);
                s.acceptHello(new RegionalProtocol.ServerHello(1, WORLD, 1, b.catalog().fingerprint()));
                s.demands.region(0).subscribed = true;
                check(s.acceptRegion(b.message()), "B metadata not admitted");
                driver.until(() -> s.activeCount == 1);
                for (int level = 4; level > 0; level--) {
                    check(s.addChildren(SectionKey.pack(level, 0, 0, 0), 15), "cached fine descendants undiscoverable");
                    int expected = 6 - level;
                    driver.until(() -> s.activeCount == expected);
                }
                check(s.receivedBytes == 0 && s.meshedSections == 5 && s.uploadedSections == 5,
                        "stale fine geometry required B bodies or duplicate work");
                for (var demand : s.demands.values()) check(demand.activeContent.fingerprint().equals(
                        a.index().sectionFingerprint(340)), "unreceived B became visible");
                check(s.cache.directory(0).size() == 5, "completed fine bindings were not committed");
                check(java.util.Arrays.equals(oldDescriptor, Files.readAllBytes(legacy.descriptor(WORLD, DIMENSION, 0, 0))),
                        "candidate overwrote legacy server descriptor");
            }
            // Simulate the old implementation having lost its positional mapping after the
            // prototype completed A. The new journal must stand on its own, at every LOD.
            legacy.saveRegion(WORLD, DIMENSION, 0, 0, b.message(), legacy.budget.stamp(), () -> true);
            try (var driver = new Driver(root)) {
                var s = driver.session;
                driver.until(() -> s.activeCount == 1);
                for (int level = 4; level > 0; level--) {
                    check(s.addChildren(SectionKey.pack(level, 0, 0, 0), 15), "reopened fine directory missing");
                    int expected = 6 - level;
                    driver.until(() -> s.activeCount == expected);
                }
                check(!s.helloAccepted && s.quic == null && s.receivedBytes == 0 && s.meshedSections == 5,
                        "completed cached detail required network on rejoin");
                s.acceptHello(new RegionalProtocol.ServerHello(1, WORLD, 1, b.catalog().fingerprint()));
                s.demands.region(0).subscribed = true;
                check(s.acceptRegion(b.message()), "refresh index not admitted on rejoin");
                for (var demand : java.util.List.copyOf(s.demands.values())) {
                    driver.until(() -> demand.activeContent.fingerprint().equals(b.index().sectionFingerprint(340))
                            || demand.index != null && demand.index.generation() == 2
                            && demand.readyKind == SectionDemandTable.ReadyKind.NETWORK);
                    if (demand.activeContent.fingerprint().equals(b.index().sectionFingerprint(340))) continue;
                    s.demands.owned(demand, SectionDemandTable.CandidateState.NETWORK_OWNED);
                    var reply = new ClientSession.Session.NetworkReply(s.connectionEpoch,
                            new RegionalProtocol.SectionReply(2, demand.ordinal, demand.key,
                                    RegionalProtocol.Status.DATA, b.payload()), demand.ticket(s.id, -1));
                    s.networkReplies.add(reply);
                    s.drainNetworkReplies();
                    driver.until(() -> demand.activeContent.fingerprint().equals(b.index().sectionFingerprint(340)));
                    check(reply.released.get(), "refresh reply retained transport ownership");
                }
                check(s.activeCount == 5 && s.meshedSections == 10 && s.uploadedSections == 10,
                        "wanted stale content did not converge once B bodies were released");
                for (var section : s.cache.directory(0).values()) check(section.fingerprint().equals(b.index().sectionFingerprint(340)),
                        "completed refresh did not replace persisted A binding");
            }
        } finally { cleanup(root); }
    }

    private static void unchangedQueuedWork() throws Exception {
        var root = Files.createTempDirectory("voxy-completed-unchanged-");
        var a = fixture(1, 1, 240, 1);
        var b = fixture(99, 1, 240, 1);
        try (var legacy = new RegionalMetadataStore(root)) {
            persist(legacy, a, true);
            try (var driver = new Driver(root)) {
                var s = driver.session;
                driver.until(() -> s.activeCount == 1);
                s.addChildren(KEY, 15);
                var fine = s.demands.get(SectionKey.pack(3, 0, 0, 0));
                long revision = fine.revision;
                s.demands.region(0).announcedGeneration = 99;
                s.installIndex(b.index(), s.demands.get(KEY).catalog, false);
                check(fine.revision == revision, "identical queued content was superseded by generation alone");
                driver.until(() -> fine.installed && fine.pendingIndex == null);
                check(fine.revision == revision && s.meshedSections == 2 && s.uploadedSections == 2,
                        "generation-only update duplicated decoding/meshing/publication");
            }
        } finally { cleanup(root); }
    }
}
