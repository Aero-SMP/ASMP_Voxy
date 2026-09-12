package me.cortex.voxy.client.lod;

import tech.kwik.core.QuicClientConnection;
import tech.kwik.core.QuicStream;
import me.cortex.voxy.client.core.rendering.SectionKey;
import java.io.*;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

final class RegionalControlFlowBehaviorTest {
    static void run() throws Exception {
        controlBeforeTerrain();
        repeatedCompetition();
        noControlAndParallelIndexes();
        authoritativeControls();
        indexAuthorityAndCatalogWait();
        blockedPublicationStillProgresses();
        blockedDuplexStillDrainsResponses();
        ownerRetainsUnsentWorkAndReleases();
        writerFailureAndClose();
        System.out.println("regional control backpressure and owner retry tests passed");
    }

    /** Pauses only the fake renderer boundary; the actual Session.run() drives every phase. */
    private static final class Owner implements AutoCloseable {
        final Semaphore reached = new Semaphore(0), resume = new Semaphore(0);
        final ClientSession.Session session;
        boolean paused;
        boolean holdPublication;
        final java.util.List<HeldPublication> held = new java.util.ArrayList<>();
        final CacheStartupBehaviorTest.Publisher publisher = new CacheStartupBehaviorTest.Publisher() {
            @Override public me.cortex.voxy.client.core.VoxyRenderSystem.SubmissionAttempt tryPublishBatch(
                    java.util.List<me.cortex.voxy.client.core.VoxyRenderSystem.SectionSubmission> submissions) {
                if (!holdPublication) return super.tryPublishBatch(submissions);
                var accepted = submissions.stream().map(HeldPublication::new).toList();
                held.addAll(accepted);
                return new me.cortex.voxy.client.core.VoxyRenderSystem.SubmissionAttempt(
                        me.cortex.voxy.client.core.VoxyRenderSystem.SubmissionStatus.ACCEPTED,
                        new java.util.ArrayList<>(accepted));
            }
            @Override public me.cortex.voxy.client.core.rendering.hierarchical.AsyncNodeManager.PublicationProgress progress() {
                if (Thread.currentThread() == session.thread && session.open.get()) {
                    reached.release();
                    try { resume.acquire(); }
                    catch (InterruptedException stop) { Thread.currentThread().interrupt(); }
                }
                return super.progress();
            }
        };

        Owner(int workers) throws Exception {
            var renderer = DebugSnapshotShutdownBehaviorTest.allocate(me.cortex.voxy.client.core.VoxyRenderSystem.class);
            session = new ClientSession.Session(501, "minecraft:overworld", renderer, publisher,
                    CacheStartupBehaviorTest.Driver.mesher(), workers);
            session.quic = connection();
            session.worldIdentity = CacheStartupBehaviorTest.WORLD;
            // Keep outbound requests disabled; inbound production admission remains real.
            session.quic.setActivityListener(session::signal);
        }

        void start() throws Exception { session.thread.start(); awaitBoundary(); }
        void awaitBoundary() throws Exception {
            check(reached.tryAcquire(5, TimeUnit.SECONDS), "owner failed to reach renderer boundary: " + session.failure);
            paused = true;
        }
        void next() throws Exception {
            check(paused, "owner is not paused");
            paused = false;
            session.signal();
            resume.release();
            awaitBoundary();
        }
        void until(java.util.function.BooleanSupplier condition) throws Exception {
            for (int step = 0; !condition.getAsBoolean() && step < 40; step++) next();
            check(condition.getAsBoolean(), "owner made no expected progress");
        }
        void workersComplete() throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (java.util.Arrays.stream(session.sectionWorkers).anyMatch(w ->
                    w.resource.state() == WorkerResource.State.RUNNING)) {
                check(System.nanoTime() < deadline, "worker failed to complete");
                session.awaitWake(100); // Actual worker completion signal, not a scheduling sleep.
            }
        }
        @Override public void close() throws Exception {
            session.open.set(false);
            resume.release();
            session.signal();
            if (session.thread.getState() == Thread.State.NEW) session.release();
            else session.thread.join(5000);
            check(!session.thread.isAlive(), "owner leaked");
            for (var worker : session.sectionWorkers) {
                check(!worker.workerThread.isAlive() && worker.resource.state() == WorkerResource.State.CLOSED,
                        "section worker/lease leaked");
            }
            session.metadataWorker.workerThread.join(5000);
            check(!session.metadataWorker.workerThread.isAlive()
                            && session.metadataWorker.resource.state() == WorkerResource.State.CLOSED,
                    "metadata worker/lease leaked");
            check(session.failure == null, "owner failed: " + session.failure);
        }
    }

    private static final class HeldPublication extends me.cortex.voxy.client.core.rendering.hierarchical.SectionPublicationState {
        final me.cortex.voxy.client.core.VoxyRenderSystem.SectionSubmission submission;
        HeldPublication(me.cortex.voxy.client.core.VoxyRenderSystem.SectionSubmission submission) { this.submission = submission; }
        void activate() {
            submission.geometry().free();
            completeUpload(new me.cortex.voxy.client.core.VoxyRenderSystem.UploadOutcome(
                    me.cortex.voxy.client.core.VoxyRenderSystem.UploadStatus.ACTIVATED, null, null));
        }
        @Override protected void requestRetirement() { markRetired(); }
        @Override protected void stateChanged() {}
    }

    private static RegionalQuicClient connection() throws Exception {
        var connection = (QuicClientConnection) Proxy.newProxyInstance(
                QuicClientConnection.class.getClassLoader(), new Class[]{QuicClientConnection.class},
                (proxy, method, args) -> method.getName().equals("isConnected") ? true : null);
        var stream = (QuicStream) Proxy.newProxyInstance(QuicStream.class.getClassLoader(),
                new Class[]{QuicStream.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getInputStream" -> InputStream.nullInputStream();
                    case "getOutputStream" -> OutputStream.nullOutputStream();
                    default -> null;
                });
        var constructor = RegionalQuicClient.class.getDeclaredConstructor(
                QuicClientConnection.class, QuicStream.class, String.class);
        constructor.setAccessible(true);
        return constructor.newInstance(connection, stream, "controlled owner fixture");
    }

    private static SectionDemandTable.RegionDemand index(Owner owner,
            CacheStartupBehaviorTest.Fixture fixture) {
        long key = SectionKey.pack(0, fixture.message().regionX() * 16, 0, fixture.message().regionZ() * 16);
        var demand = owner.session.demands.adopt(new ClientSession.Demand(key));
        var region = owner.session.demands.region(demand.regionKey);
        region.subscribed = true;
        return region;
    }

    private static ClientSession.Session.NetworkReply terrain(Owner owner, int x, boolean coverage, boolean data) throws Exception {
        var fixture = CacheStartupBehaviorTest.fixture(1, 0, 15, 1, x, 0);
        var s = owner.session;
        long key = SectionKey.pack(coverage ? 4 : 0, coverage ? x : x * 16, 0, 0);
        var demand = s.demands.adopt(new ClientSession.Demand(key));
        var idx = fixture.index();
        if (!data) {
            // Real index codec, but canonical EMPTY entries instead of payload references.
            var bytes = java.nio.ByteBuffer.allocate(36 + 341 * 48).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            bytes.put("VXYRIDX\0".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            bytes.putInt(x).putInt(0).putLong(1).putInt(0).putShort((short) 1).put((byte) 5).put((byte) 0).putInt(341);
            for (int i = 0; i < 341; i++) bytes.putShort(36 + i * 48, (short) 0x8001);
            idx = RegionalProtocol.decodeIndex(bytes.array(), CacheStartupBehaviorTest.fingerprint(bytes.array()));
        }
        demand.index = idx; demand.ordinal = idx.ordinal(key); demand.regionGeneration = 1;
        demand.catalog = new RegionalSectionCodec.BoundCatalog(fixture.catalog().fingerprint(), CacheStartupBehaviorTest.MAPPINGS);
        s.demands.setPriority(demand, x % 16);
        s.demands.owned(demand, SectionDemandTable.CandidateState.NETWORK_OWNED);
        var reply = new ClientSession.Session.NetworkReply(s.connectionEpoch,
                new RegionalProtocol.SectionReply(1, demand.ordinal, key,
                        data ? RegionalProtocol.Status.DATA : RegionalProtocol.Status.EMPTY,
                        data ? fixture.payload() : new byte[0]), demand.ticket(s.id, -1));
        s.networkReplies.add(reply);
        return reply;
    }

    private static void controlBeforeTerrain() throws Exception {
        for (int workers : new int[]{1, 2, 8}) for (boolean data : new boolean[]{false, true})
            for (boolean coverage : new boolean[]{false, true}) {
                try (var owner = new Owner(workers)) {
                    var metadata = CacheStartupBehaviorTest.fixture(1, 0, 15, 1, 20, 0);
                    var region = index(owner, metadata);
                    var replies = new java.util.ArrayList<ClientSession.Session.NetworkReply>();
                    for (int x = 1; x <= 8; x++) replies.add(terrain(owner, x, coverage, data));
                    owner.session.pendingControl = metadata.message();
                    owner.start();
                    check(region.resourceLease != null && owner.session.pendingControl == null,
                            "arrived index lost free worker to terrain: workers=" + workers + " data=" + data + " coverage=" + coverage);
                    check(replies.stream().filter(r -> r.released.get()).count() == workers - 1,
                            "index reserved extra capacity or changed terrain refill count");
                    check(owner.session.sectionWorkers[region.resourceSlot].resource.state() == WorkerResource.State.RUNNING,
                            "index not owned by a real worker");
                }
            }
        System.out.println("actual owner control-before-terrain: 1/2/8 workers, DATA/EMPTY, coverage/refinement passed");
    }

    private static void repeatedCompetition() throws Exception {
        for (boolean data : new boolean[]{false, true}) for (boolean coverage : new boolean[]{false, true}) {
            try (var owner = new Owner(1)) {
                var s = owner.session;
                var worker = s.sectionWorkers[0];
                var busy = worker.resource.acquire(); // Controlled already-running operation.
                var first = CacheStartupBehaviorTest.fixture(1, 0, 15, 1, 20, 0);
                var region = index(owner, first);
                var replies = new java.util.ArrayList<ClientSession.Session.NetworkReply>();
                for (int x = 1; x <= 8; x++) replies.add(terrain(owner, x, coverage, data));
                s.pendingControl = first.message();
                owner.start();
                for (int i = 0; i < 6; i++) owner.next();
                check(s.pendingControl == first.message() && region.resourceLease == null
                        && replies.stream().noneMatch(r -> r.released.get()), "full pool lost/duplicated pending work");
                var buffer = new PublicationRepairBehaviorTest.CountedBuffer();
                var staleTicket = new ClientSession.Demand(SectionKey.pack(0, -1, 0, 0)).ticket(s.id, 0);
                worker.resource.complete(busy, new ClientSession.Session.WorkerGeometry(staleTicket,
                        new me.cortex.voxy.client.core.rendering.building.BuiltSection(staleTicket.key(), 1,
                                (byte) 0, 0, buffer, new int[8]), 1, false, 0));
                owner.until(() -> region.resourceLease != null);
                check(buffer.frees == 1 && replies.stream().noneMatch(r -> r.released.get()),
                        "metadata did not get first released lease or stale mesh leaked");
                worker.start();
                for (int burst = 0; burst < 3; burst++) {
                    var next = CacheStartupBehaviorTest.fixture(1, 0, 15, 1, 21 + burst, 0);
                    var nextRegion = index(owner, next);
                    s.pendingControl = next.message();
                    owner.workersComplete(); // Real INDEX_DECODE completion before the next owner pass.
                    owner.until(() -> nextRegion.resourceLease != null);
                    check(replies.stream().noneMatch(r -> r.released.get()), "terrain passed a finite queued metadata burst");
                }
                for (int turn = 0; turn < 80 && s.activeCount < 8; turn++) {
                    owner.workersComplete();
                    owner.next();
                }
                check(s.activeCount == 8 && s.networkReplies.isEmpty() && s.reconnects == 0,
                        "terrain did not resume after finite metadata burst: active=" + s.activeCount);
                for (var reply : replies) check(reply.transferred.availablePermits() == 1, "handoff released twice");
                check(buffer.frees == 1, "stale mesh freed twice");
                check(region.validated && region.pendingIndex != null, "real index decoding/unknown catalog parking failed");
            }
        }
        System.out.println("busy workers, finite metadata bursts, real index/terrain decode and eventual activation passed");
    }

    private static void noControlAndParallelIndexes() throws Exception {
        for (int workers : new int[]{1, 2, 8}) try (var owner = new Owner(workers)) {
            var replies = new java.util.ArrayList<ClientSession.Session.NetworkReply>();
            for (int x = 1; x <= 8; x++) replies.add(terrain(owner, x, false, false));
            owner.start();
            for (int i = 0; i < 8; i++) check(replies.get(i).released.get() == (i >= 8 - workers),
                    "no-control terrain priority/capacity changed");
        }
        try (var owner = new Owner(8)) {
            var first = CacheStartupBehaviorTest.fixture(1, 0, 15, 1, 20, 0);
            var second = CacheStartupBehaviorTest.fixture(1, 0, 15, 1, 21, 0);
            var a = index(owner, first); var b = index(owner, second);
            owner.session.pendingControl = first.message();
            DebugSnapshotShutdownBehaviorTest.set(owner.session.quic, "controlHandoff", second.message());
            owner.start();
            check(a.resourceLease != null && b.resourceLease != null && a.resourceSlot != b.resourceSlot,
                    "index admissions were serialized");
            for (var worker : owner.session.sectionWorkers) worker.start();
            owner.workersComplete();
            owner.until(() -> a.validated && b.validated);
            check(a.pendingIndex != null && b.pendingIndex != null, "parallel real index decodes did not complete");
        }
    }

    private static void authoritativeControls() throws Exception {
        for (int mode = 0; mode < 4; mode++) try (var owner = new Owner(1)) {
            var s = owner.session;
            var retained = s.demands.adopt(new ClientSession.Demand(SectionKey.pack(0, 0, 0, 0)));
            var publication = new HeldPublication(new me.cortex.voxy.client.core.VoxyRenderSystem.SectionSubmission(
                    retained.key, me.cortex.voxy.client.core.rendering.building.BuiltSection.empty(retained.key),
                    false, 1, java.util.Optional.empty(), () -> true));
            publication.activate();
            retained.publication = publication; retained.installed = true; s.activeCount++;
            var reply = terrain(owner, 1, false, false);
            s.demands.region(s.demands.get(reply.reply.key()).regionKey).subscribed = true;
            s.pendingControl = switch (mode) {
                case 0 -> new RegionalProtocol.RegionUnavailable(1, 0, true);
                case 1 -> new RegionalProtocol.ServerHello(1, RegionalProtocol.Hash32.ZERO, 1, RegionalProtocol.Hash32.ZERO);
                case 2 -> new RegionalProtocol.ServerError(1, "injected error");
                default -> new RegionalProtocol.ServerShutdown("injected shutdown");
            };
            owner.start();
            check(reply.transferred.availablePermits() == 1 && s.sectionWorkers[0].idle(),
                    "obsolete terrain consumed worker or handoff leaked: mode=" + mode);
            check(s.activeCount == (mode == 1 ? 0 : 1) && s.networkReplies.isEmpty(), "invalid terrain installed");
            if (mode >= 2) check(s.reconnects == 1 && s.quic == null && s.open.get(),
                    "server control killed owner instead of reconnecting");
            if (mode >= 2) check(retained.publication == publication && !publication.retirementFencePassed(),
                    "reconnect discarded active geometry");
            owner.next(); owner.next();
            check(reply.transferred.availablePermits() == 1, "invalidated handoff released twice");
        }
    }

    private static void indexAuthorityAndCatalogWait() throws Exception {
        for (int mode = 0; mode < 7; mode++) try (var owner = new Owner(1)) {
            var s = owner.session;
            var metadata = CacheStartupBehaviorTest.fixture(1, 0, 15, 1, 20, 0);
            var region = index(owner, metadata);
            if (mode == 0) region.subscribed = false;
            if (mode == 1) region.announcedGeneration = 2;
            s.pendingControl = metadata.message();
            owner.start();
            if (mode <= 1) {
                check(region.resourceLease == null && s.pendingControl == null, "obsolete/unsubscribed index admitted");
                continue;
            }
            check(region.resourceLease != null, "index not admitted");
            if (mode == 2) { s.connectionEpoch++; s.resetConnection(new IOException("decode disconnect")); s.quic = connection(); }
            if (mode == 3) region.metadataRevision++;
            if (mode == 4) s.viewRevision++;
            if (mode >= 5) s.releaseRegion(region.key, region);
            if (mode == 6) region.subscribed = true; // Reentry cannot revive the pre-release decoder lease.
            s.sectionWorkers[0].start();
            owner.workersComplete();
            owner.until(() -> region.resourceLease == null);
            check(!region.validated && region.pendingIndex == null && s.sectionWorkers[0].idle(),
                    "stale index completion changed authority/leaked lease");
        }
        try (var owner = new Owner(1)) {
            var s = owner.session;
            var busy = s.metadataWorker.resource.acquire();
            var catalog = CacheStartupBehaviorTest.fixture(1, 0, 15, 1).catalog();
            var reply = terrain(owner, 1, false, false);
            s.pendingControl = catalog;
            owner.start();
            for (int step = 0; step < 6; step++) owner.next();
            check(s.pendingControl == catalog && s.metadataWorker.resource.matches(busy) && reply.released.get(),
                    "catalog wait lost pending record or stopped terrain/renderer processing");
            var ticket = new ClientSession.Demand(SectionKey.pack(0, -1, 0, 0)).ticket(s.id, -1);
            s.metadataWorker.resource.complete(busy, new ClientSession.Session.WorkerGeometry(ticket,
                    me.cortex.voxy.client.core.rendering.building.BuiltSection.empty(ticket.key()), 1, false, 0));
            owner.until(() -> s.pendingControl == null);
            s.metadataWorker.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (s.metadataWorker.resource.state() == WorkerResource.State.RUNNING) {
                check(System.nanoTime() < deadline, "catalog decoder did not complete");
                s.awaitWake(100);
            }
            owner.until(() -> s.pendingCatalogTask != null);
            // Substitute only registry mapping, as in the existing cache-startup fixture.
            s.pendingCatalogTask.mapped(CacheStartupBehaviorTest.MAPPINGS);
            owner.until(() -> s.currentCatalog != null);
            check(s.currentCatalog.fingerprint().equals(catalog.fingerprint()) && s.metadataWorker.idle(),
                    "catalog completion/event was lost or retained metadata worker");
        }
        try (var owner = new Owner(1)) {
            var s = owner.session;
            var fixture = CacheStartupBehaviorTest.fixture(1, 0, 15, 1, 20, 0);
            var region = index(owner, fixture);
            s.pendingControl = new RegionalProtocol.RegionMessage(20, 0, 1,
                    new RegionalProtocol.Fingerprint(3, 4), fixture.catalog().fingerprint(), fixture.message().compressed());
            owner.start();
            s.sectionWorkers[0].start(); owner.workersComplete();
            // Existing recovery retries the region, not the whole connection.
            owner.until(() -> s.sectionWorkers[0].idle());
            check(!region.validated && region.pendingIndex == null && !region.requested
                            && region.resourceSlot == -1 && s.reconnects == 0,
                    "corrupt index installed or leaked its decoder lease");
        }
        System.out.println("authority, absence/world/error/shutdown, stale index completions and catalog wait passed");
    }

    private static void blockedPublicationStillProgresses() throws Exception {
        try (var owner = new Owner(1)) {
            var s = owner.session;
            owner.holdPublication = true;
            var reply = terrain(owner, 1, false, false);
            owner.start();
            var demand = s.demands.get(reply.reply.key());
            var worker = s.sectionWorkers[0];
            var buffer = new PublicationRepairBehaviorTest.CountedBuffer();
            worker.resource.complete(demand.workLease, new ClientSession.Session.WorkerGeometry(demand.ticket(s.id, 0),
                    new me.cortex.voxy.client.core.rendering.building.BuiltSection(demand.key, demand.revision,
                            (byte) 0, 0, buffer, new int[8]), 1, true, 0));
            owner.until(() -> !owner.held.isEmpty());
            var metadata = CacheStartupBehaviorTest.fixture(1, 0, 15, 1, 20, 0);
            var region = index(owner, metadata);
            s.pendingControl = metadata.message();
            for (int step = 0; step < 6; step++) owner.next();
            check(s.pendingControl == metadata.message() && region.resourceLease == null && buffer.frees == 0,
                    "pending renderer ownership was reclaimed prematurely");
            owner.held.getFirst().activate();
            owner.until(() -> region.resourceLease != null);
            check(s.activeCount == 1 && buffer.frees == 1 && s.pendingControl == null,
                    "pending control blocked publication polling or completion did not free worker");
        }
    }

    private static void blockedDuplexStillDrainsResponses() throws Exception {
        try (var requests = new PipedInputStream(64);
             var sending = new PipedOutputStream(requests);
             var responses = new PipedInputStream(64);
             var replying = new PipedOutputStream(responses);
             var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            byte[] request = new byte[4096];
            Arrays.fill(request, (byte) 7);
            byte[] response = new byte[4096];
            Arrays.fill(response, (byte) 9);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Semaphore activity = new Semaphore(0);
            try (var writer = new RegionalQuicClient.ControlWriter(sending, activity::release, failure::set)) {
                var writing = threads.submit(writer);
                var server = threads.submit(() -> {
                    replying.write(response); // Must finish the response before reading requests.
                    check(Arrays.equals(requests.readNBytes(request.length), request), "request changed");
                    return null;
                });
                check(writer.offer(request), "initial write rejected");
                check(!writer.offer(new byte[]{1}), "blocked record was overwritten or queued");
                // The owner remains available to read: both bounded directions make progress.
                var owner = threads.submit(() -> responses.readNBytes(response.length));
                check(Arrays.equals(owner.get(5, TimeUnit.SECONDS), response), "response changed");
                server.get(5, TimeUnit.SECONDS);
                check(activity.tryAcquire(5, TimeUnit.SECONDS), "write completion did not wake owner");
                check(failure.get() == null, "duplex exchange failed: " + failure.get());
                writer.close();
                writing.get(5, TimeUnit.SECONDS);
                check(!writer.offer(request), "closed writer accepted work");
            }
        }
    }

    private static void ownerRetainsUnsentWorkAndReleases() throws Exception {
        ByteArrayOutputStream written = new ByteArrayOutputStream();
        var connection = (QuicClientConnection) Proxy.newProxyInstance(
                QuicClientConnection.class.getClassLoader(), new Class[]{QuicClientConnection.class},
                (proxy, method, args) -> method.getName().equals("isConnected") ? true : null);
        var stream = (QuicStream) Proxy.newProxyInstance(QuicStream.class.getClassLoader(),
                new Class[]{QuicStream.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getInputStream" -> InputStream.nullInputStream();
                    case "getOutputStream" -> written;
                    default -> null;
                });
        var constructor = RegionalQuicClient.class.getDeclaredConstructor(
                QuicClientConnection.class, QuicStream.class, String.class);
        constructor.setAccessible(true);
        try (var client = constructor.newInstance(connection, stream, "test")) {
            var field = RegionalQuicClient.class.getDeclaredField("controlWriter");
            field.setAccessible(true);
            var writer = (RegionalQuicClient.ControlWriter) field.get(client);
            var session = new ClientSession.Session(1, "minecraft:overworld", null, null, null, 0);
            session.quic = client;
            session.worldIdentity = RegionalProtocol.Hash32.ZERO;
            session.helloAccepted = true; // This test exercises post-handshake control backpressure.
            session.offerWindow(new me.cortex.voxy.client.core.rendering.RenderDistanceTracker.Window(0, 0, 1));
            client.setActivityListener(session::signal);
            var demand = new ClientSession.Demand(SectionKey.pack(4, 0, 0, 0));
            session.demands.adopt(demand);
            session.queueRegion(demand.regionKey);
            client.hello("minecraft:overworld"); // Occupy the writer without running it yet.
            session.processRegions();
            var region = session.demands.region(demand.regionKey);
            check(!region.requested && !region.subscribed, "unsent region marked requested");
            session.ensureCatalog(RegionalProtocol.Hash32.ZERO);
            check(!session.catalogRequested, "unsent catalog marked requested");
            var writing = Thread.ofVirtual().start(writer);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!region.requested && System.nanoTime() < deadline) {
                session.processRegions();
                session.awaitWake(10);
            }
            check(region.requested && session.catalogRequested, "deferred work lost");
            // Releasing the last user is retained even if the writer still owns a request.
            session.demands.remove(demand.key);
            session.releaseRegion(demand.regionKey, region);
            check(session.regionReleases.contains(demand.regionKey), "release lost");
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!session.regionReleases.isEmpty() && System.nanoTime() < deadline) {
                session.processRegions();
                session.awaitWake(10);
            }
            check(session.regionReleases.isEmpty(), "release never retried");
            // Wait for the last write before checking exact ordering, with no extra catalog requests.
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            ByteArrayOutputStream expected = new ByteArrayOutputStream();
            expected.write(RegionalProtocol.hello("minecraft:overworld"));
            expected.write(RegionalProtocol.catalogRequest());
            expected.write(RegionalProtocol.regionRequest(0, 0));
            expected.write(RegionalProtocol.regionRelease(0, 0));
            while (written.size() < expected.size() && System.nanoTime() < deadline) session.awaitWake(10);
            check(Arrays.equals(written.toByteArray(), expected.toByteArray()), "control ordering/duplication");
            writer.close();
            writing.join(5000);
            check(!writing.isAlive(), "idle writer did not close");
        }
    }

    private static void writerFailureAndClose() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var broken = new OutputStream() {
            @Override public void write(int ignored) throws IOException { throw new IOException("injected reset"); }
        };
        try (var writer = new RegionalQuicClient.ControlWriter(broken, () -> {}, failure::set)) {
            writer.offer(new byte[]{1});
            var thread = Thread.ofVirtual().start(writer);
            thread.join(5000);
            check(!thread.isAlive() && failure.get() instanceof IOException, "write failure lost");
            check(!writer.offer(new byte[]{2}), "failed writer accepted another record");
        }
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
