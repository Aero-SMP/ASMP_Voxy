package me.cortex.voxy.client.lod;

import me.cortex.voxy.client.config.ServerDownloadSettings;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.rendering.RenderDistanceTracker;
import me.cortex.voxy.client.core.rendering.SectionKey;
import me.cortex.voxy.client.core.rendering.building.BuiltSection;
import me.cortex.voxy.client.core.rendering.hierarchical.AsyncNodeManager.PublicationProgress;
import me.cortex.voxy.client.core.rendering.hierarchical.SectionPublicationState;
import me.cortex.voxy.common.util.MemoryBuffer;
import sun.misc.Unsafe;
import tech.kwik.core.QuicClientConnection;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Invokes actual owner scheduling, worker leases, planner admission and lane handoffs.
 * Scheduling cases inspect nonstarted workers and supply explicit completions; cache-only
 * integrity/persistence cases start the actual GL-free worker. No socket, Minecraft
 * instance or GL context is used. */
public final class CacheDownloadAdmissionBehaviorTest {
    private static final int DIMENSION = 11;
    private static final String NAME = "admission:world";
    private static final RegionalProtocol.Hash32 WORLD = new RegionalProtocol.Hash32(1, 2, 3, 4);
    private static long ids;

    public static void main(String[] args) throws Exception {
        sourcePass();
        rejectedSourceAssignments();
        reclaimedSourcesDeferBackground();
        try (TestPolicies policies = new TestPolicies()) {
            sourceBeforeBackground(policies);
            foregroundBeforeSource(policies);
            failedForegroundDefersBackground(policies);
            lateIdleDoesNotOverrideFailure(policies);
            handoffValidationAndPromotion(policies);
            cacheOnlyValidationAndCompletion(policies);
            foregroundReplyPrerequisites(policies);
            urgentInterestAndScope(policies);
            controlDeferralAndWriterRefusal(policies);
            pauseResetAndShutdown(policies);
            writerOrderingAndUnsent(policies);
        }
        System.out.println("production cache download admission and handoff tests passed");
    }

    private static long key(int level, int x) { return SectionKey.pack(level, x, 0, 0); }
    private static LocalSection empty(long key) {
        return new LocalSection(key, LocalSection.EMPTY, 0, 0, 0, 0,
                RegionalProtocol.Fingerprint.ZERO, RegionalProtocol.Hash32.ZERO);
    }
    private static LocalSection data(long key) {
        return new LocalSection(key, LocalSection.DATA, 0, 1, 2, 0,
                new RegionalProtocol.Fingerprint(1, 2), WORLD);
    }

    private static final class Publication extends SectionPublicationState {
        @Override protected void requestRetirement() {}
        @Override protected void stateChanged() {}
    }
    private static final class Publisher implements VoxyRenderSystem.SectionPublisher {
        Runnable listener;
        @Override public VoxyRenderSystem.SubmissionAttempt tryPublishBatch(List<VoxyRenderSystem.SectionSubmission> batch) {
            return new VoxyRenderSystem.SubmissionAttempt(VoxyRenderSystem.SubmissionStatus.BUSY, List.of());
        }
        @Override public PublicationProgress progress() { return new PublicationProgress(0, 0, 0, 0, false, null); }
        @Override public void setProgressListener(Runnable listener) { this.listener = listener; }
        @Override public void clearProgressListener(Runnable listener) {
            check(this.listener == listener, "different progress listener cleared"); this.listener = null;
        }
        @Override public void coarsen(long parent, Runnable success, Consumer<Throwable> failure) { success.run(); }
    }
    private static final class Buffer extends MemoryBuffer {
        int frees;
        Buffer() { super(64); }
        @Override public void free() { super.free(); this.frees++; }
    }

    private static class Fixture implements AutoCloseable {
        final Publisher publisher = new Publisher();
        final ClientSession.Session session;
        final List<Buffer> buffers = new ArrayList<>();
        boolean released;
        Fixture(int workers) {
            this.session = new ClientSession.Session(++ids, NAME, null, this.publisher, null, workers);
            this.publisher.setProgressListener(this.session.rendererWake);
            this.session.helloAccepted = true; this.session.openSent = true;
            this.session.bootstrapComplete = true; this.session.connectionEpoch = 1;
            this.session.dimensionId = DIMENSION; this.session.worldIdentity = WORLD;
        }
        ClientSession.Demand demand(long key, LocalSection content, boolean source) {
            var demand = new ClientSession.Demand(key); this.session.demands.adopt(demand);
            demand.content = content;
            if (source) this.session.queueBound(demand);
            return demand;
        }
        ClientSession.Demand source(long key, boolean nonempty) { return demand(key, nonempty ? data(key) : empty(key), true); }
        ClientSession.Demand foreground(long key, long ticket) throws Exception {
            var demand = demand(key, null, false); demand.wireTicket = ticket;
            invoke(this.session, "networkWanted", demand, true); return demand;
        }
        ClientSession.Session.NetworkReply reply(long epoch, long ticket, long key, RegionalProtocol.Status status,
                                                 LocalSection content, RegionalSectionCodec.BoundCatalog catalogue) {
            return reply(epoch, DIMENSION, WORLD, ticket, key, 1, status, content, catalogue);
        }
        ClientSession.Session.NetworkReply reply(long epoch, int dimension, RegionalProtocol.Hash32 world,
                long ticket, long key, long generation, RegionalProtocol.Status status,
                LocalSection content, RegionalSectionCodec.BoundCatalog catalogue) {
            var handoff = new ClientSession.Session.NetworkReply(epoch,
                    new RegionalProtocol.SectionReply(dimension, world, ticket, key, generation, status, content,
                            content != null && content.kind() == LocalSection.DATA ? new byte[]{1} : new byte[0]), catalogue);
            this.session.networkReplies.add(handoff); return handoff;
        }
        ClientSession.Demand completedRefinement(long key, int slot) throws Exception {
            var demand = source(key, true); this.session.demands.unlinkReady(demand);
            var worker = this.session.sectionWorkers[slot];
            var task = new ClientSession.Session.SectionWorkerTask(demand.ticket(this.session.id, slot), demand.content,
                    ClientSession.Session.WorkerSource.CACHE, null, null, null, () -> true);
            demand.workLease = worker.assign(task);
            var buffer = new Buffer(); this.buffers.add(buffer);
            var geometry = new BuiltSection(key, demand.revision, (byte) 0, 0, buffer, new int[8]);
            worker.resource.complete(demand.workLease, new ClientSession.Session.WorkerGeometry(
                    task.ticket(), geometry, 0, true, 0));
            this.session.drainWorkers();
            check(demand.completedGeometry == geometry && worker.resource.state() == WorkerResource.State.COMPLETED,
                    "production completion did not retain geometry/lease");
            return demand;
        }
        @Override public void close() {
            if (this.released) return;
            this.released = true;
            this.session.open.set(false); this.session.release();
            for (Buffer buffer : this.buffers) check(buffer.isFreed() && buffer.frees == 1, "mesh buffer leaked or freed twice");
        }
    }

    private static void sourcePass() throws Exception {
        try (Fixture f = new Fixture(2)) {
            check(f.session.scheduleSourceWork(), "empty source pass failed");
            var stale = f.source(key(0, 0), false); stale.candidate = SectionDemandTable.CandidateState.NONE;
            check(f.session.scheduleSourceWork() && ready(f) == 0, "stale source was not discarded");
            var a = f.source(key(4, 0), false); a.preservedPublication = new Publication();
            var b = f.source(key(4, 1), false); b.preservedPublication = new Publication();
            check(f.session.scheduleSourceWork() && ready(f) == 2, "distinct observed blocked sources vetoed spare capacity");
            check(f.session.scheduleSourceWork() && ready(f) == 2, "repeated blocked pass lost its unique membership");
            check(f.session.sectionWorkers[0].idle() && f.session.sectionWorkers[1].idle(), "blocked source allocated a worker");
            f.session.demands.checkInvariants();
        }
        try (Fixture f = new Fixture(2)) {
            var high = f.source(key(4, 0), false); high.preservedPublication = new Publication();
            var unseen = f.source(key(0, 0), false);
            check(!f.session.scheduleSourceWork(), "repeated highest blocked poll concealed unseen source");
            check(ready(f) == 2 && unseen.workLease == null, "conservative pass changed source priority/order");
            f.session.demands.checkInvariants();
        }
        for (boolean nonempty : new boolean[]{false, true}) {
            try (Fixture f = new Fixture(1)) {
                var demand = f.source(key(0, 0), nonempty);
                check(f.session.scheduleSourceWork() && ready(f) == 0 && demand.workLease != null,
                        "real source assignment failed");
                Object task = value(f.session.sectionWorkers[0], "task");
                check(nonempty ? task instanceof ClientSession.Session.SectionWorkerTask
                        : task instanceof ClientSession.Session.EmptyWorkerTask, "source took the wrong actual task path");
            }
            try (Fixture f = new Fixture(0)) {
                var demand = f.source(key(0, 0), nonempty);
                check(!f.session.scheduleSourceWork() && ready(f) == 1 && demand.workLease == null,
                        "source allocation failure was treated as success or lost its reoffer");
            }
        }
    }
    private static int ready(Fixture f) { return f.session.demands.readyCount(SectionDemandTable.ReadyKind.SOURCE); }

    private static void rejectedSourceAssignments() throws Exception {
        for (boolean nonempty : new boolean[]{false, true}) try (Fixture f = new Fixture(1)) {
            var demand = f.source(key(0, 0), nonempty); var worker = f.session.sectionWorkers[0];
            var outcome = new AtomicReference<Object>();
            Thread owner = new Thread(() -> {
                try { outcome.set(f.session.scheduleSourceWork()); } catch (Throwable failure) { outcome.set(failure); }
            }, "fixture rejected source owner");
            synchronized (worker) {
                owner.start(); awaitBlocked(owner); worker.resource.close();
            }
            owner.join(5000); check(!owner.isAlive(), "rejected-source owner did not terminate");
            check(Boolean.FALSE.equals(outcome.get()) && ready(f) == 1
                    && demand.candidate == SectionDemandTable.CandidateState.READY_SOURCE && demand.workLease == null,
                    "actual worker assignment rejection did not reoffer and fail the pass: " + outcome.get());
        }
    }
    private static void awaitBlocked(Thread thread) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (thread.getState() != Thread.State.BLOCKED && thread.isAlive() && System.nanoTime() < deadline) Thread.onSpinWait();
        check(thread.getState() == Thread.State.BLOCKED, "fixture owner did not reach synchronized assignment");
    }

    private static void reclaimedSourcesDeferBackground() throws Exception {
        for (boolean nonempty : new boolean[]{false, true}) try (Fixture f = new Fixture(1)) {
            var reclaimed = f.completedRefinement(key(0, 0), 0);
            var coverage = f.source(key(4, 0), nonempty);
            check(!f.session.scheduleSourceWork(), "synchronously enqueued source was hidden by successful allocation");
            check(coverage.workLease != null && reclaimed.workLease == null && ready(f) == 1
                    && reclaimed.candidate == SectionDemandTable.CandidateState.READY_SOURCE,
                    "actual reclamation/reoffer/coverage ownership changed");
            check(f.buffers.getFirst().frees == 1, "reclaimed geometry was not freed exactly once");
        }
    }

    private static final class DownloadFixture extends Fixture {
        final TestPolicies policies;
        final Path scratch;
        final RegionalMetadataStore metadata;
        final WorldCacheDownloads planner;
        final Object owner;
        final AtomicBoolean connected = new AtomicBoolean(true);
        final RegionalQuicClient quic;
        DownloadFixture(TestPolicies policies, int workers) throws Exception {
            super(workers); this.policies = policies;
            Path parent = Path.of("build", "admission-behavior-scratch").toAbsolutePath(); Files.createDirectories(parent);
            this.scratch = Files.createTempDirectory(parent, "owner-");
            this.metadata = new RegionalMetadataStore(this.scratch.resolve("cache"));
            this.metadata.bindServer(policies.settings, policies.settings.rawAddress());
            this.metadata.budget.awaitReady(() -> true);
            this.session.policy = policies.settings; this.session.metadata = this.metadata;
            this.session.serverKey = policies.settings.rawAddress();
            var ownerType = Class.forName(ClientSession.class.getName() + "$ConnectionOwner");
            Constructor<?> constructor = ownerType.getDeclaredConstructor(ClientSession.Session.class); constructor.setAccessible(true);
            this.owner = constructor.newInstance(this.session); set(this.session, "networkOwner", this.owner);
            this.planner = new WorldCacheDownloads(policies.settings, this.metadata, () -> {}, (dimension, key) -> {
                try { return (boolean) invoke(this.owner, "foregroundOwns", dimension, key); }
                catch (Exception failure) { throw new AssertionError(failure); }
            });
            set(this.owner, "downloads", this.planner); set(this.owner, "epoch", 1L);
            check((boolean) invoke(this.owner, "attach", this.session), "production owner attach failed");
            this.quic = allocate(RegionalQuicClient.class);
            set(this.quic, "closed", new AtomicBoolean()); set(this.quic, "failure", new AtomicReference<Throwable>());
            var connection = Proxy.newProxyInstance(QuicClientConnection.class.getClassLoader(),
                    new Class<?>[]{QuicClientConnection.class}, (proxy, method, args) -> {
                        if (method.getName().equals("isConnected")) return this.connected.get();
                        throw new AssertionError("fixture must not use a network endpoint: " + method);
                    });
            set(this.quic, "connection", connection); this.session.quic = this.quic; set(this.owner, "quic", this.quic);
            this.planner.manifest(new RegionalProtocol.Manifest(List.of(new RegionalProtocol.DimensionInfo(
                    DIMENSION, NAME, WORLD, 0, 16, true, 0, 0, 10000, 0, RegionalProtocol.Hash32.ZERO)), List.of()));
            dimension().associationPersisted = true;
            this.planner.inventory(new RegionalProtocol.RegionInventory(DIMENSION, 1, RegionalProtocol.InventoryState.SNAPSHOT_BEGIN, 0, 0, new long[16]));
            long[] saved = new long[16]; Arrays.fill(saved, -1);
            this.planner.inventory(new RegionalProtocol.RegionInventory(DIMENSION, 1, RegionalProtocol.InventoryState.SAVED_PUBLISHED, 0, 0, saved));
            this.planner.inventory(new RegionalProtocol.RegionInventory(DIMENSION, 1, RegionalProtocol.InventoryState.SNAPSHOT_COMPLETE, 0, 0, new long[16]));
            var visibility = new VisibleSectionState(); visibility.update(1, new long[0]);
            this.planner.view(DIMENSION, 0, 0, visibility);
            var catalogue = dimension().catalogueTask;
            if (catalogue != null) catalogue.get(5, TimeUnit.SECONDS);
            invoke(this.planner, "drainDirectory");
            prepareCoverage();
        }
        @SuppressWarnings("unchecked") WorldCacheDownloads.Dimension dimension() throws Exception {
            return ((Map<Integer, WorldCacheDownloads.Dimension>) value(this.planner, "dimensions")).get(DIMENSION);
        }
        @SuppressWarnings("unchecked") void prepareCoverage() throws Exception {
            var d = dimension(); var type = Class.forName(WorldCacheDownloads.class.getName() + "$Coverage");
            Constructor<?> constructor = type.getDeclaredConstructor(Map.class, long.class); constructor.setAccessible(true);
            ((Map<Long, Object>) value(d, "coverage")).put(0L, constructor.newInstance(new HashMap<Long, LocalSection>(), d.cache.incarnation(0)));
            if (d.retainedRegions.add(0L)) d.cache.retain(0);
            d.probeRegion = 0;
        }
        @SuppressWarnings("unchecked") WorldCacheDownloads.Job job(long ticket, long key, long epoch, boolean processing) throws Exception {
            var d = dimension();
            var job = new WorldCacheDownloads.Job(d, key, ticket, epoch, 4,
                    this.metadata.budget.recoveryGeneration(this.policies.settings.serverId()), this.metadata.admissionGeneration());
            job.processing = processing;
            ((Map<Long, WorldCacheDownloads.Job>) value(this.planner, "jobs")).put(ticket, job);
            ((Map<RegionalProtocol.ScopedKey, WorldCacheDownloads.Job>) value(this.planner, "requested")).put(job.scope(), job);
            if (d.retainedRegions.add(region(key))) d.cache.retain(region(key));
            d.pendingJobs.addTo(region(key), 1); return job;
        }
        @Override public void close() {
            try {
                this.policies.suppressSave(); super.close(); this.planner.close(); this.metadata.close();
                var disposed = (CountDownLatch) value(this.metadata.budget, "disposed");
                check(disposed.await(5, TimeUnit.SECONDS), "fixture cache ownership did not close");
                try (var paths = Files.walk(this.scratch)) {
                    for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
                }
                this.policies.restoreFixtureStore();
            } catch (Exception failure) { throw new AssertionError("download fixture cleanup failed", failure); }
        }
    }
    private static long region(long key) { return empty(key).region(); }

    /** A real first owner turn may start a directory read after a metadata mutation.
     * Retry actual turns until that asynchronous prerequisite settles, never fabricate
     * a desire or replace an admission refusal with fixture selection logic. */
    private static RegionalProtocol.Desire awaitAdmission(DownloadFixture f) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        RegionalProtocol.Desire result;
        while ((result = f.session.selectCacheDownload(true)) == null && System.nanoTime() < deadline) Thread.sleep(1);
        return result;
    }

    private static void awaitCompletion(ClientSession.Session.WorkerSlot worker) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (worker.resource.state() != WorkerResource.State.COMPLETED && System.nanoTime() < deadline) Thread.sleep(1);
        check(worker.resource.state() == WorkerResource.State.COMPLETED, "actual cache-only worker did not complete");
    }

    private static void sourceBeforeBackground(TestPolicies policies) throws Exception {
        try (DownloadFixture f = new DownloadFixture(policies, 2)) {
            var source = f.source(key(0, 0), true); var job = f.job(100, key(0, 1), 1, false);
            var handoff = f.reply(1, job.ticket, job.key, RegionalProtocol.Status.EMPTY, empty(job.key), null);
            check(f.session.drainNetworkReplies(false) && f.session.sectionWorkers[0].idle() && !released(handoff),
                    "early foreground pass stole background work or released the lane");
            f.session.processStages(false);
            check(value(f.session.sectionWorkers[0], "task") instanceof ClientSession.Session.SectionWorkerTask
                    && source.workLease != null, "source did not receive the first real slot");
            check(value(f.session.sectionWorkers[1], "task") instanceof ClientSession.Session.CacheOnlyTask
                    && job.processing && released(handoff) && transferPermits(handoff) == 1,
                    "spare slot did not receive the background handoff with controls deferred");
            check(f.planner.pending() == 1, "control-ready false admitted a new download");
            f.session.finishReply(handoff); check(transferPermits(handoff) == 1, "handoff release was duplicated");
            var worker = f.session.sectionWorkers[1]; var task = value(worker, "task");
            var failed = construct(Class.forName(ClientSession.Session.class.getName() + "$WorkerFailure"),
                    new Class<?>[]{ClientSession.Session.WorkerTask.class, int.class, Throwable.class}, task, 1, new IOException("fixture cache-only failure"));
            @SuppressWarnings("unchecked") var result = (ClientSession.Session.WorkerResult) failed;
            worker.resource.complete((WorkerResource.Lease) value(worker, "taskLease"), result); f.session.drainWorkers();
            check(worker.idle() && !f.planner.current(job) && f.planner.failures == 1,
                    "real background failure did not release planner/worker ownership");
        }
        try (DownloadFixture f = new DownloadFixture(policies, 1)) {
            var source = f.source(key(0, 0), false); var job = f.job(101, key(0, 1), 1, false);
            var handoff = f.reply(1, job.ticket, job.key, RegionalProtocol.Status.EMPTY, empty(job.key), null);
            f.session.processStages(false);
            check(source.workLease != null && !job.processing && !released(handoff), "background used capacity already assigned to source");
        }
        try (DownloadFixture f = new DownloadFixture(policies, 1)) {
            var blocked = f.source(key(4, 0), false); blocked.preservedPublication = new Publication();
            var job = f.job(102, key(0, 1), 1, false);
            var handoff = f.reply(1, job.ticket, job.key, RegionalProtocol.Status.EMPTY, empty(job.key), null);
            f.session.processStages(false);
            check(job.processing && released(handoff) && ready(f) == 1,
                    "observed publication-blocked source globally vetoed unrelated spare capacity");
        }
    }

    private static void foregroundBeforeSource(TestPolicies policies) throws Exception {
        try (DownloadFixture f = new DownloadFixture(policies, 1)) {
            var holder = f.completedRefinement(key(0, 0), 0);
            var foreground = f.foreground(key(4, 0), 110);
            var urgent = f.reply(1, 110, foreground.key, RegionalProtocol.Status.EMPTY, empty(foreground.key), null);
            var job = f.job(111, key(0, 1), 1, false);
            var background = f.reply(1, job.ticket, job.key, RegionalProtocol.Status.EMPTY, empty(job.key), null);
            f.session.processStages(false);
            check(released(urgent) && foreground.workLease != null && holder.workLease == null && ready(f) == 1,
                    "late foreground reclamation was not followed by the actual source pass");
            check(!job.processing && !released(background), "background bypassed the newly requeued source");
        }
    }

    private static void failedForegroundDefersBackground(TestPolicies policies) throws Exception {
        try (DownloadFixture f = new DownloadFixture(policies, 1)) {
            var worker = f.session.sectionWorkers[0]; var lease = worker.resource.acquire();
            var foreground = f.foreground(key(0, 0), 120);
            var urgent = f.reply(1, 120, foreground.key, RegionalProtocol.Status.EMPTY, empty(foreground.key), null);
            var job = f.job(121, key(0, 1), 1, false);
            var background = f.reply(1, job.ticket, job.key, RegionalProtocol.Status.EMPTY, empty(job.key), null);
            check(!f.session.drainNetworkReplies(false), "actual runnable foreground allocation failure was hidden");
            f.session.processStages(false);
            check(!released(urgent) && !released(background) && !job.processing, "failed foreground pass allowed background transfer");
            // Completion makes the real slot idle only for the next normal scheduling turn.
            worker.resource.complete(lease, new ClientSession.Session.WorkerGeometry(
                    new SectionDemandTable.Ticket(key(0, 2), f.session.id, 1, 0, 0),
                    BuiltSection.emptyWithChildren(key(0, 2), 1, (byte) 0), 0, true, 0));
            var completion = worker.resource.claim(); worker.releaseCompletion(completion.lease());
            check(worker.idle() && !job.processing && !released(background), "later idleness retroactively transferred background");
            f.session.processStages(false);
            check(released(urgent) && !released(background) && !job.processing, "next turn did not prioritize foreground");
        }
    }

    @SuppressWarnings("unchecked")
    private static void onDemandLookup(Fixture f, long watched, Runnable action) throws Exception {
        var previous = (Map<Long, ClientSession.Demand>) value(f.session.demands, "demands");
        var observed = new LinkedHashMap<Long, ClientSession.Demand>(previous) {
            boolean fired;
            @Override public ClientSession.Demand get(Object key) {
                if (!this.fired && Long.valueOf(watched).equals(key)) { this.fired = true; action.run(); }
                return super.get(key);
            }
        };
        set(f.session.demands, "demands", observed);
    }
    private static WorkerResource.Lease occupy(Fixture f, int slot, long key) {
        var d = f.demand(key, empty(key), false); var worker = f.session.sectionWorkers[slot];
        d.workLease = worker.assign(new ClientSession.Session.EmptyWorkerTask(d.ticket(f.session.id, slot), (byte) 0));
        d.candidate = SectionDemandTable.CandidateState.WORKER_OWNED; return d.workLease;
    }
    private static void releaseOccupied(Fixture f, int slot, WorkerResource.Lease lease) {
        var worker = f.session.sectionWorkers[slot];
        var task = (ClientSession.Session.EmptyWorkerTask) uncheckedValue(worker, "task");
        worker.resource.complete(lease, new ClientSession.Session.WorkerGeometry(task.ticket(),
                BuiltSection.emptyWithChildren(task.ticket().key(), task.ticket().demandRevision(), (byte) 0), 0, true, 0));
        var completion = worker.resource.claim(); ClientSession.Session.freeWorkerResult(completion.value());
        worker.releaseCompletion(completion.lease());
    }
    private static Object uncheckedValue(Object target, String name) {
        try { return value(target, name); } catch (Exception failure) { throw new AssertionError(failure); }
    }

    private static void lateIdleDoesNotOverrideFailure(TestPolicies policies) throws Exception {
        for (boolean nonempty : new boolean[]{false, true})
            try (DownloadFixture f = new DownloadFixture(policies, 2)) {
                occupy(f, 0, key(0, 7)); var lease = occupy(f, 1, key(0, 8));
                var source = f.source(key(4, 0), nonempty);
                var job = f.job(122, key(0, 9), 1, false);
                var background = f.reply(1, job.ticket, job.key, RegionalProtocol.Status.EMPTY, empty(job.key), null);
                // requireCurrent() observes this key during the actual reoffer AFTER
                // the allocation failure. Release a real occupied lease at that boundary.
                onDemandLookup(f, source.key, () -> releaseOccupied(f, 1, lease));
                f.session.processStages(false);
                check(f.session.sectionWorkers[1].idle() && source.workLease == null && ready(f) == 1
                        && !job.processing && !released(background), "idle-after-source-failure bypassed the latched pass result");
            }
        try (DownloadFixture f = new DownloadFixture(policies, 2)) {
            var holder = f.completedRefinement(key(0, 7), 0); var lease = occupy(f, 1, key(0, 8));
            var source = f.source(key(4, 0), false);
            var job = f.job(123, key(0, 9), 1, false);
            var background = f.reply(1, job.ticket, job.key, RegionalProtocol.Status.EMPTY, empty(job.key), null);
            // owned() performs the lookup only after successful EMPTY assignment and
            // synchronous holder requeue, within the final bounded source iteration.
            onDemandLookup(f, source.key, () -> releaseOccupied(f, 1, lease));
            f.session.processStages(false);
            check(source.workLease != null && holder.workLease == null && ready(f) == 1
                    && f.session.sectionWorkers[1].idle() && !job.processing && !released(background),
                    "later idleness bypassed synchronous source-enqueue deferral");
        }
        try (DownloadFixture f = new DownloadFixture(policies, 1)) {
            var lease = occupy(f, 0, key(0, 8)); var foreground = f.foreground(key(0, 0), 124);
            var urgent = f.reply(1, 124, foreground.key, RegionalProtocol.Status.EMPTY, empty(foreground.key), null);
            var job = f.job(125, key(0, 9), 1, false);
            var background = f.reply(1, job.ticket, job.key, RegionalProtocol.Status.EMPTY, empty(job.key), null);
            var replies = new ConcurrentLinkedQueue<ClientSession.Session.NetworkReply>(f.session.networkReplies) {
                @Override public Iterator<ClientSession.Session.NetworkReply> iterator() {
                    var original = super.iterator();
                    return new Iterator<>() {
                        boolean fired;
                        @Override public boolean hasNext() {
                            boolean next = original.hasNext();
                            if (!next && !this.fired) { this.fired = true; releaseOccupied(f, 0, lease); }
                            return next;
                        }
                        @Override public ClientSession.Session.NetworkReply next() { return original.next(); }
                    };
                }
            };
            set(f.session, "networkReplies", replies);
            f.session.processStages(false);
            check(f.session.sectionWorkers[0].idle() && foreground.workLease == null && !released(urgent)
                    && !job.processing && !released(background), "idle-after-late-foreground-failure bypassed the latched pass result");
        }
    }

    private static void handoffValidationAndPromotion(TestPolicies policies) throws Exception {
        try (DownloadFixture f = new DownloadFixture(policies, 2)) {
            var job = f.job(130, key(0, 1), 1, false);
            var staleEpoch = f.reply(0, job.ticket, job.key, RegionalProtocol.Status.EMPTY, empty(job.key), null);
            var wrongDimension = f.reply(1, DIMENSION + 1, WORLD, job.ticket, job.key, 1, RegionalProtocol.Status.EMPTY, empty(job.key), null);
            var wrongWorld = f.reply(1, DIMENSION, RegionalProtocol.Hash32.ZERO, job.ticket, job.key, 1, RegionalProtocol.Status.EMPTY, empty(job.key), null);
            var missing = f.reply(1, 999, key(0, 2), RegionalProtocol.Status.EMPTY, empty(key(0, 2)), null);
            f.session.drainNetworkReplies(false);
            for (var reply : List.of(staleEpoch, wrongDimension, wrongWorld, missing))
                check(released(reply) && transferPermits(reply) == 1, "stale handoff did not release its lane exactly once");
            check(!job.processing && f.session.sectionWorkers[0].idle(), "stale epoch borrowed a current ticket/worker");
            var valid = f.reply(1, job.ticket, job.key, RegionalProtocol.Status.EMPTY, empty(job.key), null);
            f.session.drainNetworkReplies(false);
            var demand = f.foreground(job.key, job.ticket); check(f.planner.promote(job), "real shared job promotion failed");
            f.session.drainNetworkReplies(true);
            check(!released(valid) && demand.workLease == null, "promotion between passes was converted to cache-only work");
            f.session.drainNetworkReplies(false);
            check(released(valid) && value(f.session.sectionWorkers[0], "task") instanceof ClientSession.Session.EmptyWorkerTask,
                    "promoted handoff did not take the actual foreground path");
        }
        try (DownloadFixture f = new DownloadFixture(policies, 1)) {
            var notReadyJob = f.job(140, key(0, 1), 1, false);
            var notReady = f.reply(1, 140, notReadyJob.key, RegionalProtocol.Status.NOT_READY, null, null);
            var oldConnection = f.job(141, key(0, 2), 0, false);
            var old = f.reply(1, 141, oldConnection.key, RegionalProtocol.Status.EMPTY, empty(oldConnection.key), null);
            f.session.drainNetworkReplies(false);
            check(released(notReady) && !f.planner.current(notReadyJob) && dimensionParked(f, notReadyJob.key),
                    "early background NOT_READY handling changed");
            check(released(old) && !oldConnection.processing && f.session.sectionWorkers[0].idle(), "job epoch mismatch transferred work");
        }
        try (DownloadFixture f = new DownloadFixture(policies, 1)) {
            var holder = f.completedRefinement(key(0, 0), 0);
            var job = f.job(142, key(0, 1), 1, false);
            var handoff = f.reply(1, 142, job.key, RegionalProtocol.Status.EMPTY, empty(job.key), null);
            f.session.drainNetworkReplies(true);
            check(holder.completedGeometry != null && holder.workLease != null && !job.processing && !released(handoff),
                    "cache-only assignment reclaimed a foreground worker");
        }
    }
    private static boolean dimensionParked(DownloadFixture f, long key) throws Exception { return f.dimension().parkedRegions.contains(region(key)); }

    private static void cacheOnlyValidationAndCompletion(TestPolicies policies) throws Exception {
        for (int kind : new int[]{LocalSection.EMPTY, LocalSection.ABSENT, LocalSection.DATA})
            try (DownloadFixture f = new DownloadFixture(policies, 1)) {
                var job = f.job(145, key(0, 1), 1, false);
                LocalSection content;
                RegionalSectionCodec.BoundCatalog catalogue = null;
                byte[] compressed = new byte[0];
                if (kind == LocalSection.DATA) {
                    byte[] canonical = ByteBuffer.allocate(11).order(ByteOrder.LITTLE_ENDIAN)
                            .putShort((short) 1).putInt(0).putInt(0).put((byte) 0).array();
                    var input = org.lwjgl.system.MemoryUtil.memAlloc(canonical.length);
                    var output = org.lwjgl.system.MemoryUtil.memAlloc(Math.toIntExact(org.lwjgl.util.zstd.Zstd.ZSTD_compressBound(canonical.length)));
                    try {
                        input.put(canonical).flip();
                        long count = org.lwjgl.util.zstd.Zstd.ZSTD_compress(output, input, 3);
                        check(!org.lwjgl.util.zstd.Zstd.ZSTD_isError(count), "fixture compression failed");
                        compressed = new byte[Math.toIntExact(count)]; output.get(compressed);
                    } finally { org.lwjgl.system.MemoryUtil.memFree(input); org.lwjgl.system.MemoryUtil.memFree(output); }
                    var hash = RegionalProtocol.Fingerprint.read(ByteBuffer.wrap(Blake3.hash(canonical)).order(ByteOrder.LITTLE_ENDIAN));
                    content = new LocalSection(job.key, kind, 0, compressed.length, canonical.length,
                            RegionalProtocol.crc32c(compressed), hash, WORLD);
                    var source = new CatalogCodec.Catalog(1, 1, 1,
                            List.of(new CatalogCodec.Block("minecraft:stone", 15, true)), List.of("minecraft:plains"));
                    catalogue = new RegionalSectionCodec.BoundCatalog(WORLD, new RegionalSectionCodec.Mappings(source));
                } else content = new LocalSection(job.key, kind, 0, 0, 0, 0,
                        RegionalProtocol.Fingerprint.ZERO, RegionalProtocol.Hash32.ZERO);
                var status = switch (kind) {
                    case LocalSection.DATA -> RegionalProtocol.Status.DATA;
                    case LocalSection.ABSENT -> RegionalProtocol.Status.ABSENT;
                    default -> RegionalProtocol.Status.EMPTY;
                };
                var record = new RegionalProtocol.SectionReply(DIMENSION, WORLD, job.ticket, job.key, 1, status, content, compressed);
                var handoff = new ClientSession.Session.NetworkReply(1, record, catalogue);
                var duplicate = new ClientSession.Session.NetworkReply(1, record, catalogue);
                f.session.networkReplies.add(handoff); f.session.networkReplies.add(duplicate);
                f.session.drainNetworkReplies(false);
                check(!released(handoff) && !job.processing, "early pass assigned cache-only payload");
                f.session.drainNetworkReplies(true);
                check(released(handoff) && !released(duplicate) && job.processing,
                        "processing/duplicate record transferred twice");
                var worker = f.session.sectionWorkers[0]; worker.start(); awaitCompletion(worker); f.session.drainWorkers();
                check(worker.idle() && !f.planner.current(job) && f.planner.committed == 1 && f.planner.failures == 0,
                        "actual cache-only payload did not validate/save/complete once");
                var saved = f.dimension().cache.directorySnapshot(region(job.key)).get(job.key);
                check(content.sameContent(saved), "actual worker saved a different cache binding");
                f.session.drainNetworkReplies(false);
                check(released(duplicate) && transferPermits(duplicate) == 1 && f.planner.committed == 1,
                        "duplicate after completion retained lane or committed twice");
            }
        try (DownloadFixture f = new DownloadFixture(policies, 1)) {
            var job = f.job(146, key(0, 1), 1, false);
            var handoff = f.reply(1, 146, job.key, RegionalProtocol.Status.DATA, data(job.key), null);
            f.session.drainNetworkReplies(true);
            var worker = f.session.sectionWorkers[0]; worker.start(); awaitCompletion(worker); f.session.drainWorkers();
            check(released(handoff) && worker.idle() && f.planner.committed == 0 && f.planner.failures == 1
                    && f.planner.lastFailure.contains("missing canonical cache-only section payload"),
                    "actual missing-catalogue cache-only validation/refusal changed");
        }
        try (DownloadFixture f = new DownloadFixture(policies, 1)) {
            var job = f.job(147, key(0, 1), 1, false);
            var handoff = f.reply(1, 147, job.key, RegionalProtocol.Status.EMPTY, empty(job.key), null);
            f.session.drainNetworkReplies(true);
            var worker = f.session.sectionWorkers[0]; var task = value(worker, "task");
            f.planner.detach();
            Object cached = construct(Class.forName(ClientSession.Session.class.getName() + "$WorkerCached"),
                    new Class<?>[]{ClientSession.Session.CacheOnlyTask.class, long.class}, task, 0L);
            worker.resource.complete((WorkerResource.Lease) value(worker, "taskLease"), (ClientSession.Session.WorkerResult) cached);
            f.session.drainWorkers();
            check(released(handoff) && worker.idle() && f.planner.committed == 0 && !f.planner.current(job),
                    "cancelled job completion committed stale owner data or retained worker");
        }
    }

    private static void foregroundReplyPrerequisites(TestPolicies policies) throws Exception {
        try (DownloadFixture f = new DownloadFixture(policies, 1)) {
            var d = f.foreground(key(0, 0), 150);
            var handoff = f.reply(1, 150, d.key, RegionalProtocol.Status.DATA, data(d.key), null);
            check(f.session.drainNetworkReplies(false) && !released(handoff) && d.workLease == null, "missing foreground catalogue became runnable");
            f.session.finishReply(handoff);
            d.preservedPublication = new Publication();
            var gated = f.reply(1, 150, d.key, RegionalProtocol.Status.EMPTY, empty(d.key), null);
            check(f.session.drainNetworkReplies(false) && !released(gated), "preserved publication lost foreground gate");
            d.preservedPublication = null; f.session.finishReply(gated);
            d.regionGeneration = 3;
            var old = f.reply(1, DIMENSION, WORLD, 150, d.key, 2, RegionalProtocol.Status.EMPTY, empty(d.key), null);
            f.session.drainNetworkReplies(false); check(released(old) && d.workLease == null, "stale foreground generation was assigned");
            var notReady = f.reply(1, 150, d.key, RegionalProtocol.Status.NOT_READY, null, null);
            f.session.drainNetworkReplies(false); check(released(notReady) && d.workLease == null, "foreground NOT_READY changed");
            invoke(f.session, "networkWanted", d, false); d.activeContent = empty(d.key);
            var unchanged = f.reply(1, DIMENSION, WORLD, 150, d.key, 4, RegionalProtocol.Status.EMPTY, empty(d.key), null);
            f.session.drainNetworkReplies(false);
            check(released(unchanged) && d.regionGeneration == 4 && d.workLease == null, "unchanged-content shortcut assigned work");
        }
        try (DownloadFixture f = new DownloadFixture(policies, 1)) {
            var d = f.foreground(key(0, 0), 151);
            var binding = new RegionalSectionCodec.BoundCatalog(WORLD, new RegionalSectionCodec.Mappings(new int[]{0}, new int[]{0}));
            var handoff = f.reply(1, 151, d.key, RegionalProtocol.Status.DATA, data(d.key), binding);
            f.session.drainNetworkReplies(false);
            var task = (ClientSession.Session.SectionWorkerTask) value(f.session.sectionWorkers[0], "task");
            check(released(handoff) && task.source() == ClientSession.Session.WorkerSource.NETWORK
                    && task.mappings() == binding.mappings() && d.catalog == binding && !d.networkWanted,
                    "foreground catalogue/record ownership path changed");
        }
    }

    private static void urgentInterestAndScope(TestPolicies policies) throws Exception {
        try (DownloadFixture f = new DownloadFixture(policies, 2)) {
            var s = f.session;
            check(s.selectCacheDownload(false) == null && f.planner.pending() == 0, "control-ready false admitted prefetch");
            s.open.set(false); check(s.selectCacheDownload(true) == null, "closed session gate ignored"); s.open.set(true);
            s.openSent = false; check(s.selectCacheDownload(true) == null, "OPEN gate ignored"); s.openSent = true;
            s.helloAccepted = false; check(s.selectCacheDownload(true) == null, "HELLO gate ignored"); s.helloAccepted = true;
            f.connected.set(false); check(s.selectCacheDownload(true) == null, "disconnected QUIC gate ignored"); f.connected.set(true);
            set(f.owner, "epoch", 2L); check(s.selectCacheDownload(true) == null, "owner epoch gate ignored"); set(f.owner, "epoch", 1L);
            set(f.owner, "closed", true); check(s.selectCacheDownload(true) == null, "closed owner gate ignored"); set(f.owner, "closed", false);
            set(f.owner, "attached", null); check(s.selectCacheDownload(true) == null, "detached owner gate ignored"); set(f.owner, "attached", s);
            set(f.owner, "quic", null); check(s.selectCacheDownload(true) == null, "QUIC owner identity gate ignored"); set(f.owner, "quic", f.quic);
            s.interestDrops.add(key(0, 4)); check(s.selectCacheDownload(true) == null, "late foreground DROP ignored"); s.interestDrops.clear();
            @SuppressWarnings("unchecked") var detached = (Set<RegionalProtocol.ScopedKey>) value(f.owner, "detachedDrops");
            detached.add(new RegionalProtocol.ScopedKey(DIMENSION, key(0, 4)));
            check(s.selectCacheDownload(true) == null, "late detached DROP ignored"); detached.clear();
            var cancelled = f.job(160, key(0, 5), 1, false); f.planner.notReady(cancelled);
            check(s.selectCacheDownload(true) == null, "planner DROP ignored"); f.planner.dropped(f.planner.drops());
            f.dimension().parkedRegions.clear(); f.prepareCoverage();
            var urgent = f.foreground(key(0, 0), 161); s.interestChanges.add(urgent.key);
            check(s.selectCacheDownload(true) == null, "genuine eligible visible miss did not prevent ordinary admission");
            // The bucket remains urgent while current demand truth changes; classification
            // is deliberately stale to exercise the production predicate's validation.
            invoke(s, "networkWanted", urgent, false);
            check(!(boolean) invoke(s, "hasUrgentDownloadInterest"), "HAVE entry in urgent bucket vetoed background");
            invoke(s, "networkWanted", urgent, true); urgent.preservedPublication = new Publication();
            check(!(boolean) invoke(s, "hasUrgentDownloadInterest"), "publication-gated replacement vetoed background");
            urgent.preservedPublication = null;
            s.offerWindow(new RenderDistanceTracker.Window(10, 10, 0));
            check(!(boolean) invoke(s, "hasUrgentDownloadInterest"), "out-of-window stale urgent entry vetoed background");
            s.offerWindow(new RenderDistanceTracker.Window(0, 0, 1));
            float[] planes = new float[24]; planes[3] = -1;
            set(s, "downloadFrustum", construct(Class.forName(ClientSession.class.getName() + "$DownloadFrustum"),
                    new Class<?>[]{float[].class, double.class, double.class, double.class}, planes, 0d, 0d, 0d));
            check(!(boolean) invoke(s, "hasUrgentDownloadInterest"), "offscreen stale urgent entry vetoed background");
            set(s, "downloadFrustum", null);
            var cover = f.demand(key(1, 0), data(key(1, 0)), false);
            check(!(boolean) invoke(s, "hasUrgentDownloadInterest") && s.coverDependents.get(cover.key).contains(urgent.key),
                    "urgent predicate did not preserve actual cache-cover dependency bookkeeping");
            cover.installed = true; cover.activeContent = cover.content; cover.cacheActivatedFrame = s.renderedFrames;
            check(!(boolean) invoke(s, "hasUrgentDownloadInterest") && s.frameInterests.contains(urgent.key),
                    "urgent predicate did not preserve actual frame bookkeeping");
            cover.cacheActivatedFrame = -1;
            var shared = f.job(162, urgent.key, 1, true);
            check(!(boolean) invoke(s, "hasUrgentDownloadInterest"), "already-processing shared job vetoed unrelated capacity");
            f.planner.complete(shared, empty(urgent.key), 0); f.planner.dropped(f.planner.drops());
            s.interestChanges.remove(urgent.key);
            var stale = f.foreground(key(0, 9), 163); s.interestChanges.add(stale.key); s.demands.remove(stale.key);
            check(!(boolean) invoke(s, "hasUrgentDownloadInterest"), "missing/stale watch identity vetoed capacity");
            check(s.interestChanges.membership.get(stale.key) == 1, "missing identity was not retained in the stale urgent bucket");
            var reprioritized = f.demand(key(0, 10), null, false); s.interestChanges.add(reprioritized.key);
            check(s.interestChanges.membership.get(reprioritized.key) == 2, "ordinary fixture interest began urgent");
            invoke(s, "networkWanted", reprioritized, true); s.interestChanges.add(reprioritized.key);
            check(s.interestChanges.membership.get(reprioritized.key) == 1
                    && (boolean) invoke(s, "hasUrgentDownloadInterest"), "ordinary-to-urgent insertion was not observed");
            s.interestChanges.remove(reprioritized.key);
            f.prepareCoverage();
            var admitted = awaitAdmission(f);
            check(admitted != null && f.planner.pending() == 1 && admitted.ticket() != 0
                    && admitted.dimensionId() == DIMENSION && admitted.worldIdentity().equals(WORLD),
                    "actual planner did not admit one scoped request with spare capacity");
            check(s.interestChanges.contains(stale.key), "unrelated pending watch was consumed to admit prefetch");
            check(f.planner.waiting() == 1, "selector admitted more than one request");
            var occupied = s.sectionWorkers[0].resource.acquire();
            check(s.selectCacheDownload(true) == null && f.planner.pending() == 1, "waiting-versus-idle bound changed");
            check(occupied != null, "fixture failed to occupy idle capacity");
        }
    }

    /** Uses actual packet sizing, sendControl and ControlWriter with an offline stream.
     * Constructor-free Kwik state supplies only the already-connected packet descriptor;
     * no handshake, UDP socket or generic transport replacement is involved. */
    private static RegionalQuicClient.ControlWriter offlineWriter(DownloadFixture f) throws Exception {
        var endpoint = allocate(tech.kwik.core.impl.QuicClientConnectionImpl.class);
        var transport = new tech.kwik.core.impl.TransportParameters(); transport.setMaxUdpPayloadSize(1200);
        set(endpoint, "transportParams", transport); set(endpoint, "peerTransportParams", transport);
        set(endpoint, "connectionIdManager", allocate(tech.kwik.core.cid.ConnectionIdManager.class));
        Class<?> status = Class.forName("tech.kwik.core.impl.QuicConnectionImpl$Status");
        @SuppressWarnings({"rawtypes", "unchecked"}) Object connected = Enum.valueOf((Class) status, "Connected");
        field(endpoint.getClass().getSuperclass(), "connectionState").set(endpoint, connected);
        set(f.quic, "connection", endpoint);
        var writer = new RegionalQuicClient.ControlWriter(new ByteArrayOutputStream(), () -> {}, failure -> { throw new AssertionError(failure); });
        set(f.quic, "controlWriter", writer); return writer;
    }

    private static void controlDeferralAndWriterRefusal(TestPolicies policies) throws Exception {
        try (DownloadFixture f = new DownloadFixture(policies, 2);
             var writer = offlineWriter(f)) {
            set(f.session, "sentStorageBytes", policies.settings.storageBytes());
            var dropped = f.job(170, key(0, 1), 1, false); f.planner.notReady(dropped);
            check(!f.session.processCacheDownloads() && f.planner.drops().isEmpty() && value(writer, "record") != null,
                    "actual DROP controls did not retain the one-turn deferral");
            f.session.processStages(false); check(f.planner.pending() == 0, "DROP control turn admitted a new job");
            f.dimension().parkedRegions.clear(); f.prepareCoverage();
            int[] transfers = new int[2];
            var jobs = new ConcurrentHashMap<Long, WorldCacheDownloads.Job>() {
                @Override public WorldCacheDownloads.Job put(Long ticket, WorldCacheDownloads.Job job) {
                    transfers[0]++; return super.put(ticket, job);
                }
                @Override public boolean remove(Object ticket, Object job) {
                    boolean removed = super.remove(ticket, job); if (removed) transfers[1]++; return removed;
                }
            };
            set(f.planner, "jobs", jobs);
            // On the next turn the earlier DROP still occupies the actual writer.
            // The production tail must call unsent after the real sendControl refusal.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            do {
                f.session.processStages(true);
                check(f.planner.pending() == 0 && f.planner.waiting() == 0, "actual refused send leaked a ticket/job");
                if (transfers[0] > 0) break;
                Thread.sleep(1);
            } while (System.nanoTime() < deadline);
            check(transfers[0] == 1 && transfers[1] == 1,
                    "production tail did not create and unsent-roll-back the actual refused job");
        }
        try (DownloadFixture f = new DownloadFixture(policies, 2);
             var writer = offlineWriter(f)) {
            set(f.session, "sentStorageBytes", policies.settings.storageBytes());
            var changed = f.job(171, key(0, 1), 1, false);
            f.session.visibility.update(2, new long[]{changed.key});
            check(!f.session.processCacheDownloads() && changed.purpose == 3 && value(writer, "record") != null,
                    "actual changed-desire controls did not retain the one-turn deferral");
            f.session.processStages(false);
            check(f.planner.pending() == 1, "changed-desire control turn admitted another job");
        }
    }

    private static void pauseResetAndShutdown(TestPolicies policies) throws Exception {
        try (DownloadFixture f = new DownloadFixture(policies, 1)) {
            var budget = f.metadata.budget;
            synchronized (budget) { set(budget, "policyAvailable", false); }
            check(!f.metadata.canDownload() && f.session.selectCacheDownload(true) == null, "policy persistence pause admitted new work");
            synchronized (budget) { set(budget, "policyAvailable", true); set(budget, "diskPaused", true); set(budget, "requiredGrowth", Long.MAX_VALUE); }
            check(!f.metadata.canDownload() && f.session.selectCacheDownload(true) == null, "physical disk-full pause admitted new work");
            synchronized (budget) { set(budget, "diskPaused", false); set(budget, "requiredGrowth", 1L); }
            var job = f.job(180, key(0, 1), 1, false);
            var handoff = f.reply(1, 180, job.key, RegionalProtocol.Status.EMPTY, empty(job.key), null);
            // Avoid closing the constructor-free QUIC descriptor; reset's real owner,
            // planner epoch and handoff invalidation still run unchanged.
            set(f.owner, "quic", null); f.session.quic = null;
            f.session.resetConnection(new IOException("fixture connection reset"));
            check(released(handoff) && transferPermits(handoff) == 1 && !f.planner.current(job)
                    && !f.session.helloAccepted && !f.session.openSent
                    && f.session.selectCacheDownload(true) == null, "real reset retained scope/job/lane admission");
        }
        try (DownloadFixture f = new DownloadFixture(policies, 1)) {
            policies.settings.setStorageBytes(ServerDownloadSettings.MIN_STORAGE_BYTES);
            f.metadata.bindServer(policies.settings, policies.settings.rawAddress());
            Object account = value(f.metadata, "account");
            long previousBytes = (long) value(account, "bytes");
            // Seed a full, pinned account without writing 100 MB of disposable data.
            // Actual canAdmit/eviction sees the finite allowance and no evictable region.
            synchronized (f.metadata.budget) { set(account, "bytes", ServerDownloadSettings.MIN_STORAGE_BYTES); }
            try {
                check(f.dimension().cache.canAdmit(key(4, 0), 1) == RegionalDiskBudget.Admission.QUOTA,
                        "fixture did not establish an actual finite-allowance refusal");
                check(f.session.selectCacheDownload(true) == null && f.planner.pending() == 0,
                        "actual finite allowance admitted growth into a full pinned account");
            } finally {
                synchronized (f.metadata.budget) { set(account, "bytes", previousBytes); }
                policies.settings.setStorageBytes(Long.MAX_VALUE);
            }
        }
        try (DownloadFixture f = new DownloadFixture(policies, 1)) {
            var job = f.job(181, key(0, 1), 1, false);
            var handoff = f.reply(1, 181, job.key, RegionalProtocol.Status.EMPTY, empty(job.key), null);
            f.session.close(); f.session.release(); f.released = true;
            check(released(handoff) && transferPermits(handoff) == 1 && !f.planner.current(job)
                    && f.session.sectionWorkers[0].resource.state() == WorkerResource.State.CLOSED
                    && f.session.selectCacheDownload(true) == null, "real shutdown leaked lane/worker or admitted work");
            f.session.finishReply(handoff); check(transferPermits(handoff) == 1, "shutdown lane released twice");
        }
    }

    private static void writerOrderingAndUnsent(TestPolicies policies) throws Exception {
        try (DownloadFixture f = new DownloadFixture(policies, 1)) {
            var next = awaitAdmission(f); check(next != null, "writer fixture did not admit a real job");
            byte[] drop = RegionalProtocol.drop(List.of(new RegionalProtocol.ScopedKey(DIMENSION, key(0, 7))));
            byte[] desire = RegionalProtocol.desire(List.of(next));
            var output = new ByteArrayOutputStream(); var writes = new Semaphore(0); var failure = new AtomicReference<Throwable>();
            var writer = new RegionalQuicClient.ControlWriter(output, writes::release, failure::set);
            check(writer.offer(drop) && !writer.offer(desire), "real writer accepted replacement before pending DROP");
            // This is the real planner rollback called by the production failed-send
            // branch, tested independently of a socket-backed QUIC instance.
            f.planner.unsent(next.ticket());
            check(f.planner.job(next.ticket()) == null && f.planner.waiting() == 0 && f.planner.drops().isEmpty(),
                    "unsent rollback retained ticket or manufactured cancellation");
            Thread thread = new Thread(writer, "fixture control writer"); thread.start();
            try {
                check(writes.tryAcquire(5, TimeUnit.SECONDS) && writer.offer(desire), "writer did not progress after DROP");
                check(writes.tryAcquire(5, TimeUnit.SECONDS), "writer did not send subsequent DESIRE");
            } finally { writer.close(); thread.join(5000); }
            check(!thread.isAlive() && failure.get() == null, "control writer failed or leaked its thread");
            var expected = new ByteArrayOutputStream(); expected.write(drop); expected.write(desire);
            check(Arrays.equals(output.toByteArray(), expected.toByteArray()), "DROP/DESIRE writer ordering changed");
        }
    }

    private static boolean released(Object handoff) throws Exception { return ((AtomicBoolean) inherited(handoff, "released")).get(); }
    private static int transferPermits(Object handoff) throws Exception { return ((Semaphore) inherited(handoff, "transfer")).availablePermits(); }

    /** In-memory policy state prevents the actual planner from touching normal settings. */
    private static final class TestPolicies implements AutoCloseable {
        final Object lock, previousStore, previousLoaded, previousFailure, isolatedStore;
        final Field store, loaded, failure;
        final ServerDownloadSettings settings;
        @SuppressWarnings("unchecked") TestPolicies() throws Exception {
            this.lock = field(ServerDownloadSettings.class, "LOCK").get(null);
            this.store = field(ServerDownloadSettings.class, "store"); this.loaded = field(ServerDownloadSettings.class, "loaded");
            this.failure = field(ServerDownloadSettings.class, "failureReason");
            synchronized (this.lock) {
                this.previousStore = this.store.get(null); this.previousLoaded = this.loaded.get(null); this.previousFailure = this.failure.get(null);
                this.isolatedStore = construct(Class.forName(ServerDownloadSettings.class.getName() + "$Store"), new Class<?>[0]);
                Object policy = construct(Class.forName(ServerDownloadSettings.class.getName() + "$Policy"), new Class<?>[0]);
                field(policy.getClass(), "storageBytes").setLong(policy, Long.MAX_VALUE); field(policy.getClass(), "storageSelected").setBoolean(policy, true);
                String key = "admission-fixture:25565"; ((Map<String, Object>) value(this.isolatedStore, "servers")).put(key, policy);
                this.store.set(null, this.isolatedStore); this.loaded.setBoolean(null, true); this.failure.set(null, "");
                Constructor<ServerDownloadSettings> constructor = ServerDownloadSettings.class.getDeclaredConstructor(String.class, String.class);
                constructor.setAccessible(true); this.settings = constructor.newInstance(key, key);
            }
        }
        void suppressSave() throws Exception { synchronized (this.lock) { this.store.set(null, null); } }
        void restoreFixtureStore() throws Exception { synchronized (this.lock) { this.store.set(null, this.isolatedStore); } }
        @Override public void close() throws Exception {
            synchronized (this.lock) { this.store.set(null, this.previousStore); this.loaded.set(null, this.previousLoaded); this.failure.set(null, this.previousFailure); }
        }
    }

    private static Field field(Class<?> type, String name) throws Exception { var field = type.getDeclaredField(name); field.setAccessible(true); return field; }
    private static Object value(Object target, String name) throws Exception { return field(target.getClass(), name).get(target); }
    private static Object inherited(Object target, String name) throws Exception { return field(target.getClass().getSuperclass(), name).get(target); }
    private static void set(Object target, String name, Object value) throws Exception { field(target.getClass(), name).set(target, value); }
    private static Object construct(Class<?> type, Class<?>[] parameters, Object... arguments) throws Exception {
        var constructor = type.getDeclaredConstructor(parameters); constructor.setAccessible(true); return constructor.newInstance(arguments);
    }
    private static Object invoke(Object target, String name, Object... arguments) throws Exception {
        Method match = null;
        for (var method : target.getClass().getDeclaredMethods())
            if (method.getName().equals(name) && method.getParameterCount() == arguments.length) { match = method; break; }
        if (match == null) throw new NoSuchMethodException(name);
        match.setAccessible(true);
        try { return match.invoke(target, arguments); }
        catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception cause) throw cause;
            if (failure.getCause() instanceof Error cause) throw cause;
            throw failure;
        }
    }
    @SuppressWarnings("unchecked") private static <T> T allocate(Class<T> type) throws Exception {
        return (T) ((Unsafe) field(Unsafe.class, "theUnsafe").get(null)).allocateInstance(type);
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
