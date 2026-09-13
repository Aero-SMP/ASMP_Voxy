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
        fineOnlyWithoutParents();
        concurrentWorkerSaves();
        for (int mode = 0; mode < 4; mode++) publicationBeforeOwnedSave(mode);
        SectionDemandTableBehaviorTest.saveAndGeometryOwnOneWorkerLease();
        parkedModelsReleaseForCoverage();
        for (int update = 0; update < 3; update++) reclaimedCacheCompletes(update);
        for (int terminal = 0; terminal < 5; terminal++)
            for (int ownership = 0; ownership < 6; ownership++) cancelledCompletion(terminal, ownership);
        cancelledCompletion(2, 6); // Save and admission remain separate obligations.
        cancelledCompletion(1, 7); // Lost binding returns to discovery, not a retry spin.
        cancelledCompletion(1, 8); // Authoritative absence does not resurrect work.
        cancelledCompletion(1, 9); // An unusable local binding cannot suppress discovery.
        cancelledRetryMiss();
        for (boolean serverFirst : new boolean[]{false, true}) staleFineAndRejoin(serverFirst);
        unchangedQueuedWork();
        for (int phase = 0; phase < 3; phase++) metadataDuringOwnedWork(phase);
        deletionSurvivesRejoin();
        System.out.println("completed local cache: metadata-first races, all-LOD stale detail/rejoin and unchanged queued work passed");
    }

    private static void publicationBeforeOwnedSave(int mode) throws Exception {
        var root = Files.createTempDirectory(java.nio.file.Path.of("project_audit"), "owned-publication-");
        var f = fixture(1, 0, 240, 1);
        var budget = new RegionalDiskBudget(root, mode == 3 ? 32 : RegionalDiskBudget.LIMIT);
        try (var metadata = new RegionalMetadataStore(budget);
             var cache = new CompletedSectionCache(metadata, WORLD, DIMENSION)) {
            awaitInventory(budget);
            var publisher = new Publisher();
            var s = new ClientSession.Session(92, DIMENSION, null, publisher, Driver.mesher(), 1);
            s.metadata = metadata; s.worldIdentity = WORLD; s.cacheOpened = true;
            s.blockNames.put("minecraft:stone", 15); s.biomeNames.put("minecraft:plains", 0);
            var worker = s.sectionWorkers[0];
            try {
                var demand = s.demands.adopt(new ClientSession.Demand(KEY));
                demand.content = LocalSection.from(f.index(), 340, f.catalog().fingerprint());
                var ticket = demand.ticket(s.id, 0);
                var task = new ClientSession.Session.SectionWorkerTask(ticket, demand.content,
                        ClientSession.Session.WorkerSource.NETWORK, f.payload(),
                        new RegionalSectionCodec.Mappings(CatalogCodec.decode(f.catalog().canonical())), cache,
                        () -> s.open.get() && demand.revision == ticket.demandRevision());
                try (var held = budget.writer(cache.path(0), () -> true)) {
                    worker.start(); demand.workLease = worker.assign(task);
                    s.demands.owned(demand, SectionDemandTable.CandidateState.WORKER_OWNED);
                    long end = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                    while (s.activeCount == 0 && System.nanoTime() < end) {
                        s.drainWorkers(); s.processStages(); s.pollPublications(); s.awaitWake(1);
                    }
                    check(s.activeCount == 1 && publisher.publications.size() == 1 && !worker.idle(),
                            "real publication waited for disk or reused a pending save slot");
                    if (mode == 1) {
                        s.retireDemand(KEY);
                        while (!worker.idle() && System.nanoTime() < end) { s.drainWorkers(); s.awaitWake(1); }
                        check(worker.idle() && s.retainedSaveBytes() == 0, "obsolete waiter did not wake/cancel under ownership");
                    } else if (mode == 2) {
                        worker.close(); worker.workerThread.join(5000);
                        check(!worker.workerThread.isAlive() && s.retainedSaveBytes() == 0 && !worker.resource.savePending(),
                                "shutdown remained blocked by a different regional writer");
                    }
                }
                if (mode == 0 || mode == 3) {
                    long end = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                    while (!worker.idle() && System.nanoTime() < end) { s.drainWorkers(); s.awaitWake(1); }
                    check(worker.idle() && s.activeCount == 1 && s.failure == null && s.retainedSaveBytes() == 0,
                            "save completion/failure destroyed active geometry or leaked its lease");
                    check((s.lastPersistenceFailure != null) == (mode == 3), "optional save outcome misclassified");
                }
            } finally { s.open.set(false); s.release(); }
        } finally { cleanup(root); }
    }

    private static void concurrentWorkerSaves() throws Exception {
        var root = Files.createTempDirectory(java.nio.file.Path.of("project_audit"), "fair-cache-saves-");
        var fixture = fixture(1, 0, 240, 1);
        try (var metadata = new RegionalMetadataStore(root);
             var cache = new CompletedSectionCache(metadata, WORLD, DIMENSION)) {
            awaitInventory(metadata.budget);
            var s = new ClientSession.Session(91, DIMENSION, null, new Publisher(), Driver.mesher(), 3);
            s.metadata = metadata; s.worldIdentity = WORLD; s.cacheOpened = true;
            s.blockNames.put("minecraft:stone", 15); s.biomeNames.put("minecraft:plains", 0);
            var mappings = new RegionalSectionCodec.Mappings(CatalogCodec.decode(fixture.catalog().canonical()));
            var tasks = new ClientSession.Session.SectionWorkerTask[3];
            var content = LocalSection.from(fixture.index(), 0, fixture.catalog().fingerprint());
            try {
                s.metadataWorker.start();
                try (var held = metadata.budget.writer(cache.path(0), () -> true)) {
                for (int i = 0; i < 3; i++) {
                    var worker = s.sectionWorkers[i]; worker.start();
                    var demand = s.demands.adopt(new ClientSession.Demand(SectionKey.pack(0, i == 2 ? 16 : i, 0, 0)));
                    demand.content = new LocalSection(demand.key, content.kind(), content.children(), content.compressedBytes(),
                            content.canonicalBytes(), content.crc(), content.fingerprint(), content.catalog());
                    tasks[i] = new ClientSession.Session.SectionWorkerTask(demand.ticket(s.id, i), demand.content,
                            ClientSession.Session.WorkerSource.NETWORK, fixture.payload(), mappings, cache, () -> s.open.get());
                    admitOwnedMesh(worker, tasks[i]);
                }
                long end = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                while (!s.sectionWorkers[2].idle() && System.nanoTime() < end) { s.drainWorkers(); s.awaitWake(1); }
                check(s.sectionWorkers[2].idle() && !s.sectionWorkers[0].idle() && !s.sectionWorkers[1].idle(),
                        "another region did not finish independently of waiting writers");
                check(s.acceptCatalog(fixture.catalog()), "saving slots blocked catalog admission");
                while (s.currentCatalog == null && System.nanoTime() < end) { s.drainWorkers(); s.awaitWake(1); }
                check(s.currentCatalog != null && s.metadataWorker.idle(), "metadata did not progress beside saving slots");
                }
                long end = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                while (java.util.Arrays.stream(s.sectionWorkers).anyMatch(w -> !w.idle()) && System.nanoTime() < end) { s.drainWorkers(); s.awaitWake(1); }
                check(java.util.Arrays.stream(s.sectionWorkers).allMatch(w -> w.idle()) && s.lastPersistenceFailure == null,
                        "same-region contention failed saves or retained leases");
                check(cache.directory(0).size() == 2 && cache.directory(1).size() == 1,
                        "concurrent saves did not replay complete bindings");
            } finally {
                s.open.set(false); s.release();
                s.metadataWorker.workerThread.join(5000);
                check(s.retainedSaveBytes() == 0, "shutdown retained pending save input");
            }
        } finally { cleanup(root); }
    }

    private static void admitOwnedMesh(ClientSession.Session.WorkerSlot worker,
                                       ClientSession.Session.SectionWorkerTask task) throws Exception {
        check(worker.assign(task) != null, "test worker not idle");
        long end = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (worker.resource.pendingResult() == null && System.nanoTime() < end) Thread.sleep(1);
        var result = worker.resource.claim();
        check(result != null && result.value() instanceof ClientSession.Session.WorkerGeometry,
                "real worker did not prepare a mesh with a save obligation");
        ((ClientSession.Session.WorkerGeometry) result.value()).geometry().free();
        worker.releaseCompletion(result.lease());
        check(!worker.idle(), "admission discarded the save obligation");
    }

    private static void fineOnlyWithoutParents() throws Exception {
        var root = Files.createTempDirectory(java.nio.file.Path.of("project_audit"), "fine-only-cache-");
        var fixture = fixture(1, 1, 240, 1);
        long fine = SectionKey.pack(0, 0, 0, 0);
        try (var metadata = new RegionalMetadataStore(root)) {
            awaitInventory(metadata.budget);
            metadata.associate(SERVER, DIMENSION, WORLD, metadata.budget.stamp(), () -> true);
            try (var cache = new CompletedSectionCache(metadata, WORLD, DIMENSION)) {
                storeSection(cache, fixture, 0);
                check(cache.directory(0).keySet().equals(java.util.Set.of(fine)), "fixture includes a parent or sibling");
            }
            for (int rejoin = 0; rejoin < 2; rejoin++) try (var driver = new Driver(root)) {
                var s = driver.session;
                driver.until(() -> s.demands.get(fine) != null && s.demands.get(fine).installed);
                check(s.activeCount == 1 && !s.demands.get(KEY).installed && s.receivedBytes == 0
                                && !s.helloAccepted && s.quic == null && s.retainedSaveBytes() == 0,
                        "fine-only publication depended on absent ancestors, network or a hit-side save");
                check(s.blockNames.size() == 1 && s.biomeNames.size() == 1,
                        "shared canonical resolver retained duplicate names");
                s.retireDemand(fine);
                s.retireDemand(KEY);
                check(s.demands.regionCount() == 0, "retired sparse demand retained its region");
            }
        } finally { cleanup(root); }
    }

    private static void parkedModelsReleaseForCoverage() throws Exception {
        var f = fixture(1, 0, 240, 1);
        var models = new SectionMesher.Models() {
            public int getModelId(int block) { throw new AssertionError("unready model meshed"); }
            public long getModelMetadataFromClientId(int model) { throw new AssertionError(); }
            public int getFluidClientStateId(int model) { throw new AssertionError(); }
            public boolean isModelReadyForBlockId(int block) { return false; }
            public boolean isWaterState(int block) { return false; }
        };
        var constructor = SectionMesher.class.getDeclaredConstructor(SectionMesher.Models.class, java.util.function.IntConsumer.class);
        constructor.setAccessible(true);
        var requests = new java.util.concurrent.atomic.AtomicInteger();
        var mesher = constructor.newInstance(models, (java.util.function.IntConsumer) b -> requests.incrementAndGet());
        var s = new ClientSession.Session(91, DIMENSION, null, new Publisher(), mesher, 2);
        try {
            s.metadataWorker.start();
            for (int i = 0; i < 2; i++) {
                var demand = s.demands.adopt(new ClientSession.Demand(SectionKey.pack(0, i, 0, 0)));
                var content = LocalSection.from(f.index(), 0, f.catalog().fingerprint());
                demand.content = new LocalSection(demand.key, LocalSection.DATA, 0, content.compressedBytes(),
                        content.canonicalBytes(), content.crc(), content.fingerprint(), content.catalog());
                var worker = s.sectionWorkers[i]; worker.start();
                var ticket = demand.ticket(s.id, i);
                demand.workLease = worker.assign(new ClientSession.Session.SectionWorkerTask(ticket, demand.content,
                        ClientSession.Session.WorkerSource.NETWORK, f.payload(), MAPPINGS, null,
                        () -> s.open.get() && demand.revision == ticket.demandRevision()));
                s.demands.owned(demand, SectionDemandTable.CandidateState.WORKER_OWNED);
            }
            long until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while (s.retainedModelBytes() != 2 * (32768L * 8 + 4) && System.nanoTime() < until) s.awaitWake(1);
            check(s.retainedModelBytes() == 2 * (32768L * 8 + 4), "model slots did not retain exactly two results");
            s.processWaitingModels();
            check(requests.get() == 2, "ordinary model wait repeated decode requests");
            check(s.acceptCatalog(f.catalog()), "parked sections blocked metadata admission");
            while (s.currentCatalog == null && System.nanoTime() < until) { s.drainWorkers(); s.awaitWake(1); }
            check(s.currentCatalog != null && s.metadataWorker.idle(), "metadata did not progress with parked section workers");
            check(s.idleWorker(true) == null, "coverage stole a running worker before ownership returned");
            while (s.idleWorker() == null && System.nanoTime() < until) { s.drainWorkers(); s.awaitWake(1); }
            check(s.idleWorker() != null && s.retainedModelBytes() == 32768L * 8 + 4,
                    "coverage did not reclaim one parked result");
            check(requests.get() == 2, "reclamation silently ran another decode");
        } finally {
            s.open.set(false); s.release();
            s.metadataWorker.workerThread.join(5000);
            for (var worker : s.sectionWorkers) check(!worker.workerThread.isAlive(), "parked worker leaked on shutdown");
            check(s.retainedModelBytes() == 0, "shutdown retained model cells");
        }
    }

    private static void reclaimedCacheCompletes(int update) throws Exception {
        var root = Files.createTempDirectory(java.nio.file.Path.of("project_audit"), "cancelled-cache-");
        var a = fixture(1, 1, 240, 1);
        var b = fixture(2, 1, update == 2 ? 128 : 240, 1);
        var ready = new java.util.concurrent.atomic.AtomicBoolean(true);
        var models = new SectionMesher.Models() {
            public int getModelId(int block) { check(ready.get(), "unready model meshed"); return 1; }
            public long getModelMetadataFromClientId(int model) { return 0; }
            public int getFluidClientStateId(int model) { throw new AssertionError(); }
            public boolean isModelReadyForBlockId(int block) { return ready.get(); }
            public boolean isWaterState(int block) { return false; }
        };
        var constructor = SectionMesher.class.getDeclaredConstructor(SectionMesher.Models.class, java.util.function.IntConsumer.class);
        constructor.setAccessible(true);
        try (var metadata = new RegionalMetadataStore(root)) {
            persist(metadata, a, true);
            try (var driver = new Driver(root, new Publisher(), constructor.newInstance(models,
                    (java.util.function.IntConsumer) ignored -> {}))) {
                var s = driver.session;
                driver.until(() -> s.activeCount == 1);
                ready.set(false);
                check(s.addChildren(KEY, 15), "cached child unavailable");
                var fine = s.demands.get(SectionKey.pack(3, 0, 0, 0));
                driver.until(() -> s.retainedModelBytes() != 0);
                var worker = s.sectionWorkers[fine.workLease.slot()];
                var other = s.sectionWorkers[1 - worker.index];
                var held = other.resource.acquire();
                check(held != null, "control slot unavailable");
                if (update != 0) {
                    s.demands.region(0).announcedGeneration = 2;
                    s.installIndex(b.index(), new RegionalSectionCodec.BoundCatalog(b.catalog().fingerprint(),
                            new RegionalSectionCodec.Mappings(CatalogCodec.decode(b.catalog().canonical()))), false);
                    check(fine.pendingIndex == b.index(), "running cache refresh not deferred");
                }
                check(s.idleWorker(true) == null, "reclaim revoked running ownership");
                int readyBeforeReturn = s.demands.readyCount(SectionDemandTable.ReadyKind.SOURCE);
                long returnedBy = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                while (worker.resource.pendingResult() == null && System.nanoTime() < returnedBy) s.awaitWake(1);
                check(worker.resource.pendingResult() != null, "reclaimed worker did not return cancellation");
                s.drainWorker(worker);
                check(fine.workLease == null && fine.readyKind == SectionDemandTable.ReadyKind.SOURCE
                        && s.demands.readyCount(SectionDemandTable.ReadyKind.SOURCE) == readyBeforeReturn + 1,
                        "cancelled cache demand stranded with pending update=" + update);
                check(update == 0 || fine.pendingIndex == b.index(), "recovery consumed deferred refresh before local activation");
                other.resource.complete(held, new ClientSession.Session.WorkerGeometry(fine.ticket(s.id, other.index),
                        me.cortex.voxy.client.core.rendering.building.BuiltSection.emptyWithChildren(fine.key, 0, (byte) 0), 0, true, 0));
                ClientSession.Session.freeWorkerResult(other.resource.claim().value());
                other.releaseCompletion(held);
                ready.set(true);
                driver.until(() -> fine.installed);
                check(fine.activeContent.fingerprint().equals(a.index().sectionFingerprint(336))
                        && s.receivedBytes == 0 && s.uploadedSections == 2,
                        "reclaimed cached content failed to activate before refresh");
                if (update == 2) {
                    driver.until(() -> fine.index == b.index());
                    s.demands.owned(fine, SectionDemandTable.CandidateState.NETWORK_OWNED);
                    var reply = new ClientSession.Session.NetworkReply(s.connectionEpoch,
                            new RegionalProtocol.SectionReply(2, fine.ordinal, fine.key,
                                    RegionalProtocol.Status.DATA, b.payload()), fine.ticket(s.id, -1));
                    s.networkReplies.add(reply); s.drainNetworkReplies();
                    driver.until(() -> fine.activeContent.fingerprint().equals(b.index().sectionFingerprint(336)));
                    check(reply.released.get() && s.uploadedSections == 3, "fresh replacement lost ownership");
                }
                driver.until(() -> fine.pendingIndex == null && fine.workLease == null && s.retainedSaveBytes() == 0);
                check(fine.readyKind == null && fine.candidate == SectionDemandTable.CandidateState.NONE,
                        "settled retry retained ready/deferred state");
                check(s.uploadedSections == (update == 2 ? 3 : 2), "identical refresh republished geometry");
            }
        } finally { cleanup(root); }
    }

    private static ClientSession.Session.WorkerResult controlledResult(String type, Object... arguments) throws Exception {
        var constructor = Class.forName(ClientSession.Session.class.getName() + "$" + type).getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        return (ClientSession.Session.WorkerResult) constructor.newInstance(arguments);
    }

    private static void cancelledCompletion(int terminal, int ownership) throws Exception {
        var s = new ClientSession.Session(93, DIMENSION, null, new Publisher(), Driver.mesher(), 2);
        var f = fixture(1, 0, 240, 1);
        var d = s.demands.adopt(new ClientSession.Demand(0));
        d.content = LocalSection.from(f.index(), 0, f.catalog().fingerprint());
        var w = s.sectionWorkers[0];
        var lease = w.resource.acquire(); d.workLease = lease;
        var ticket = d.ticket(ownership == 4 ? s.id - 1 : s.id, 0);
        var buffer = terminal == 2 ? new PublicationRepairBehaviorTest.CountedBuffer() : null;
        var mesh = terminal == 2
                ? new me.cortex.voxy.client.core.rendering.building.BuiltSection(d.key, d.revision, (byte) 0, 0, buffer, new int[8])
                : me.cortex.voxy.client.core.rendering.building.BuiltSection.emptyWithChildren(d.key, d.revision, (byte) 0);
        var task = terminal == 3
                ? new ClientSession.Session.EmptyWorkerTask(ticket, (byte) 0)
                : new ClientSession.Session.SectionWorkerTask(ticket, d.content, ClientSession.Session.WorkerSource.CACHE,
                        null, null, null, () -> false);
        ClientSession.Session.WorkerResult result = switch (terminal) {
            case 0, 3 -> controlledResult("WorkerFailure", task, 0, new java.util.concurrent.CancellationException());
            case 1 -> controlledResult("WorkerMiss", ticket, false, null);
            default -> new ClientSession.Session.WorkerGeometry(ticket, mesh, 0, true, 0);
        };
        try {
            s.demands.revise(d); d.candidate = SectionDemandTable.CandidateState.READY_SOURCE;
            if (ownership >= 7) d.content = null;
            if (ownership == 8) s.demands.region(d.regionKey).absent = true;
            if (ownership == 9) d.content = new LocalSection(d.key, LocalSection.ABSENT, 0, 0, 0, 0,
                    new RegionalProtocol.Fingerprint(0, 0), RegionalProtocol.Hash32.ZERO);
            if (ownership == 1 || ownership == 2) s.retireDemand(d.key);
            ClientSession.Demand replacement = null;
            WorkerResource.Lease newer = null;
            if (ownership == 2) {
                replacement = s.demands.adopt(new ClientSession.Demand(d.key));
                newer = s.sectionWorkers[1].resource.acquire(); replacement.workLease = newer;
                s.demands.owned(replacement, SectionDemandTable.CandidateState.WORKER_OWNED);
            }
            if (ownership == 3) s.open.set(false);
            if (ownership == 5) {
                w.resource.complete(lease, new ClientSession.Session.WorkerGeometry(ticket,
                        me.cortex.voxy.client.core.rendering.building.BuiltSection.empty(d.key), 0, true, 0));
                ClientSession.Session.freeWorkerResult(w.resource.claim().value()); w.releaseCompletion(lease);
                newer = w.resource.acquire(); d.workLease = newer;
                s.demands.owned(d, SectionDemandTable.CandidateState.WORKER_OWNED);
            }
            if (ownership == 6) w.resource.retainSave(lease);
            w.resource.complete(lease, result);
            s.drainWorker(w); s.drainWorker(w); // A consumed completion cannot enqueue/dispose twice.
            if (buffer != null) check(buffer.frees == 1, "stale mesh not disposed exactly once");
            if (ownership == 0 || ownership == 6) {
                check(d.workLease == null && d.readyKind == SectionDemandTable.ReadyKind.SOURCE
                        && s.demands.readyCount(SectionDemandTable.ReadyKind.SOURCE) == 1,
                        "terminal path stranded retry: " + terminal);
            } else {
                check(s.demands.readyCount(SectionDemandTable.ReadyKind.SOURCE) == 0, "obsolete completion resurrected work");
                if (replacement != null) check(newer.equals(replacement.workLease), "old result detached replacement lease");
                if (ownership == 4) check(lease.equals(d.workLease), "foreign session mutated demand");
                if (ownership == 7 || ownership == 9) check(d.candidate == SectionDemandTable.CandidateState.WAIT_REGION
                        && d.workLease == null && s.demands.readyRegionCount() == 1,
                        "missing binding did not return to discovery");
                if (ownership == 8) check(s.demands.get(d.key) == null, "absent binding was requeued");
            }
            if (ownership == 5) check(w.resource.matches(newer), "stale result released reused slot");
            else if (ownership == 6) {
                check(!w.idle() && w.resource.savePending() && w.resource.releaseRequested(), "cancelled mesh bypassed pending save");
                check(w.resource.finishSave(lease) && w.idle(), "save acknowledgement did not finish old ownership");
            } else check(w.idle(), "cancelled completion leaked worker lease");
        } finally { s.open.set(false); s.release(); }
    }

    private static void cancelledRetryMiss() throws Exception {
        var s = new ClientSession.Session(94, DIMENSION, null, new Publisher(), Driver.mesher(), 1);
        try {
            var f = fixture(1, 0, 240, 1);
            var d = s.demands.adopt(new ClientSession.Demand(0));
            d.content = LocalSection.from(f.index(), 0, f.catalog().fingerprint());
            var region = s.demands.region(0);
            region.localLoaded = true; region.localSections = new java.util.LinkedHashMap<>();
            region.localSections.put(d.key, d.content);
            region.index = f.index(); region.catalog = new RegionalSectionCodec.BoundCatalog(f.catalog().fingerprint(), MAPPINGS);
            region.validated = region.subscribed = s.helloAccepted = true;
            var w = s.sectionWorkers[0]; var lease = w.resource.acquire(); d.workLease = lease;
            var old = d.ticket(s.id, 0); s.demands.revise(d); d.candidate = SectionDemandTable.CandidateState.READY_SOURCE;
            w.resource.complete(lease, controlledResult("WorkerMiss", old, false, null)); s.drainWorker(w);
            check(d.readyKind == SectionDemandTable.ReadyKind.SOURCE, "stale miss did not retry existing source");
            lease = w.resource.acquire(); d.workLease = lease;
            s.demands.owned(d, SectionDemandTable.CandidateState.WORKER_OWNED);
            w.resource.complete(lease, controlledResult("WorkerMiss", d.ticket(s.id, 0), false, null)); s.drainWorker(w);
            check(d.index == f.index() && d.readyKind == SectionDemandTable.ReadyKind.NETWORK
                    && s.cacheMisses == 1 && w.idle(), "missing retry failed to reach validated network work");
            for (int i = 0; i < 3; i++) s.processStages();
            check(s.cacheMisses == 1 && s.demands.readyCount(SectionDemandTable.ReadyKind.NETWORK) == 1,
                    "missing cached retry spun without a network response");
        } finally { s.open.set(false); s.release(); }
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
        var bakeRequests = new java.util.concurrent.atomic.AtomicInteger();
        var models = new SectionMesher.Models() {
            public int getModelId(int block) { return 1; }
            public long getModelMetadataFromClientId(int model) { return 0; }
            public int getFluidClientStateId(int model) { throw new AssertionError(); }
            public boolean isModelReadyForBlockId(int block) { return ready.get(); }
            public boolean isWaterState(int block) { return false; }
        };
        var constructor = SectionMesher.class.getDeclaredConstructor(SectionMesher.Models.class, java.util.function.IntConsumer.class);
        constructor.setAccessible(true);
        var mesher = constructor.newInstance(models, (java.util.function.IntConsumer) ignored -> bakeRequests.incrementAndGet());
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
                if (phase == 0) {
                    check(lease != null, "model wait discarded the owned worker");
                    for (int i = 0; i < 20; i++) driver.step();
                    check(bakeRequests.get() == 1 && lease.equals(demand.workLease),
                            "unready models caused ordinary read/decode retry");
                }
                s.demands.region(0).announcedGeneration = b.index().generation();
                s.installIndex(b.index(), new RegionalSectionCodec.BoundCatalog(b.catalog().fingerprint(), MAPPINGS), false);
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
                check(bakeRequests.get() == 1, "model readiness repeated decode/model requests");
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
                check(s.cache.directory(0).isEmpty(), "absence did not seal legacy fallback");
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
            try (var driver = new Driver(root)) {
                var s = driver.session;
                if (!serverFirst) driver.until(() -> s.activeCount == 1);
                s.acceptHello(new RegionalProtocol.ServerHello(1, WORLD, 1, b.catalog().fingerprint()));
                s.demands.region(0).subscribed = true;
                check(s.acceptRegion(b.message()), "B metadata not admitted");
                driver.catalog(b.catalog());
                driver.until(() -> s.activeCount == 1);
                for (int level = 4; level > 0; level--) {
                    check(s.addChildren(SectionKey.pack(level, 0, 0, 0), 15), "cached fine descendants undiscoverable");
                    int expected = 6 - level;
                    driver.until(() -> s.activeCount == expected);
                }
                check(s.receivedBytes == 0 && s.meshedSections == 5 && s.uploadedSections == 5,
                        "stale fine geometry required B bodies or duplicate work: serverFirst=" + serverFirst
                                + " received=" + s.receivedBytes + " meshed=" + s.meshedSections + " uploaded=" + s.uploadedSections);
                for (var demand : s.demands.values()) check(demand.activeContent.fingerprint().equals(
                        a.index().sectionFingerprint(340)), "unreceived B became visible");
                check(s.cache.directory(0).size() == 5, "completed fine bindings were not committed");
            }
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
                driver.catalog(b.catalog());
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
                driver.until(() -> s.retainedSaveBytes() == 0);
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
                s.installIndex(b.index(), new RegionalSectionCodec.BoundCatalog(b.catalog().fingerprint(), MAPPINGS), false);
                check(fine.revision == revision, "identical queued content was superseded by generation alone");
                driver.until(() -> fine.installed && fine.pendingIndex == null);
                check(fine.revision == revision && s.meshedSections == 2 && s.uploadedSections == 2,
                        "generation-only update duplicated decoding/meshing/publication");
            }
        } finally { cleanup(root); }
    }
}
