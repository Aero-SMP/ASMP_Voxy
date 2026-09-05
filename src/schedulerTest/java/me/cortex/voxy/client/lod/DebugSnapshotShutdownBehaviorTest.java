package me.cortex.voxy.client.lod;

import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.VoxyRenderSystem.*;
import me.cortex.voxy.client.core.rendering.building.BuiltSection;
import me.cortex.voxy.client.core.rendering.hierarchical.AsyncNodeManager;
import me.cortex.voxy.client.core.rendering.hierarchical.PublicationHandoff;
import me.cortex.voxy.client.core.rendering.hierarchical.SectionPublicationState;
import me.cortex.voxy.client.core.rendering.section.BasicAsyncGeometryManager;
import sun.misc.Unsafe;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Real owner summary, publisher and release methods; only GL construction is bypassed. */
final class DebugSnapshotShutdownBehaviorTest {
    static void run() throws Exception {
        ownerPublishesImmutableSamples();
        cacheMonitorDoesNotBlockOwner();
        for (boolean stopFirst : new boolean[]{false, true}) publisherSurvivesTeardown(stopFirst);
        sectionCallbacksKeepIdentityAndOwner();
        normalFacadeDoesNothing();
        System.out.println("debug owner snapshot cadence/concurrency and publisher shutdown race tests passed");
    }

    private static final class Renderer extends VoxyRenderSystem {
        AtomicInteger captures;
        CountDownLatch entered, resume;
        Renderer() { super(null); }
        @Override public String regionalPublicationLatencySnapshot() {
            captures.incrementAndGet();
            if (entered != null) {
                entered.countDown();
                try { check(resume.await(5, TimeUnit.SECONDS), "reader blocked by capture"); }
                catch (InterruptedException e) { throw new AssertionError(e); }
            }
            return "latency=test";
        }
    }

    private static final class Surface extends SectionPublicationState {
        int retirements;
        @Override protected void requestRetirement() { retirements++; markRetired(); }
        @Override protected void stateChanged() {}
    }

    static void cacheMonitorDoesNotBlockOwner() throws Exception {
        var root = Files.createTempDirectory("voxy-debug-cache-monitor-");
        try (var store = new RegionalMetadataStore(root)) {
            CacheStartupBehaviorTest.awaitInventory(store.budget);
            Renderer renderer = allocate(Renderer.class); renderer.captures = new AtomicInteger();
            var session = new ClientSession.Session(106, "test", renderer, null, null, 0);
            session.metadata = store;
            RuntimeCachePressureBehaviorTest.whileBudgetHeld(store.budget, () -> {
                RuntimeCachePressureBehaviorTest.setOwner(session);
                SessionDebugTelemetry.capture(session, System.nanoTime(), false);
                check(SessionDebugTelemetry.latest(session).text().contains("cacheInventory=READY cacheDiskBytes=0"),
                        "owner capture did not observe cache without its monitor");
            });
        } finally { CacheStartupBehaviorTest.cleanup(root); }
    }

    private static void ownerPublishesImmutableSamples() throws Exception {
        Renderer renderer = allocate(Renderer.class);
        renderer.captures = new AtomicInteger();
        ClientSession.Session session = new ClientSession.Session(101, "test", renderer, null, null, 0);
        ClientSession.Session successor = new ClientSession.Session(102, "test", renderer, null, null, 0);
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch first = new CountDownLatch(1), mutate = new CountDownLatch(1);
        renderer.entered = new CountDownLatch(1); renderer.resume = new CountDownLatch(1);
        Thread owner = new Thread(() -> {
            try {
                var surface = new Surface(); surface.markRendererAdmitted();
                var demand = session.demands.adopt(new ClientSession.Demand(16));
                demand.geometryBytes = 1024; demand.publication = surface;
                demand.candidate = SectionDemandTable.CandidateState.RENDERER_OWNED;
                SessionDebugTelemetry.event(session, "validated", 0);
                SessionDebugTelemetry.admissionReleased(session, System.nanoTime());
                // First capture is not blocked; the second intentionally pauses during formatting.
                var block = renderer.entered; renderer.entered = null;
                SessionDebugTelemetry.capture(session, 1, false);
                renderer.entered = block;
                first.countDown(); check(mutate.await(5, TimeUnit.SECONDS), "reader did not start");
                for (int i = 0; i < 20_000; i++) {
                    session.demands.adopt(new ClientSession.Demand(((long) i << 8) | 16));
                    SessionDebugTelemetry.capture(session, 2 + i, false);
                }
                surface.completeUpload(new UploadOutcome(UploadStatus.ACTIVATED, null, null));
                surface.close(); session.demands.clear(); session.activeCount = 1;
                SessionDebugTelemetry.capture(session, 1 + SessionDebugTelemetry.INTERVAL_NANOS, false);
            } catch (Throwable failure) { error.set(failure); first.countDown(); }
        }, "test regional owner");
        set(session, "thread", owner);
        owner.start(); check(first.await(5, TimeUnit.SECONDS), "initial sample missing");
        var initial = SessionDebugTelemetry.latest(session);
        check(initial != null && initial.text().contains("admittedPending=1"), "admission not captured");
        check(initial.text().contains("admissionReleases=1"), "release count lost");
        mutate.countDown();
        for (int i = 0; i < 20_000; i++) {
            check(SessionDebugTelemetry.latest(session) == initial, "reader constructed or mutated summary");
            SessionDebugTelemetry.read(session, 10 * SessionDebugTelemetry.INTERVAL_NANOS);
        }
        check(renderer.entered.await(5, TimeUnit.SECONDS), "due capture did not resume");
        check(renderer.captures.get() == 2, "per-iteration formatting escaped cadence");
        for (int i = 0; i < 100; i++) {
            check(SessionDebugTelemetry.latest(session) == initial, "partial capture escaped");
            check(SessionDebugTelemetry.read(session, 10_000_000_001L).contains("sessionSampleAgeNanos=10000000000"),
                    "busy owner sample does not age");
        }
        check(SessionDebugTelemetry.latest(successor) == null
                && SessionDebugTelemetry.read(successor, 1).equals("regional=SAMPLING sessionId=102"),
                "new session inherited old summary");
        renderer.resume.countDown(); owner.join(5_000);
        check(!owner.isAlive(), "owner did not finish");
        if (error.get() != null) throw new AssertionError(error.get());
        check(initial.text().contains("desired=1 ") && initial.text().contains("admittedPending=1"),
                "later mutations modified immutable summary");
        var latest = SessionDebugTelemetry.latest(session);
        check(latest != initial && latest.text().contains("admittedPending=0"), "activation/retirement not sampled");
        boolean rejected = false;
        try { session.snapshot(""); } catch (IllegalStateException expected) { rejected = true; }
        check(rejected, "off-owner snapshot accepted");
        // No strong value-to-key back-reference, even while the summary is retained by a reader.
        WeakReference<ClientSession.Session> weak = expiredSession(renderer);
        for (int i = 0; i < 30 && weak.get() != null; i++) { System.gc(); Thread.sleep(10); }
        check(weak.get() == null, "telemetry retained closed session");
    }

    private static WeakReference<ClientSession.Session> expiredSession(Renderer renderer) throws Exception {
        renderer.entered = null;
        var session = new ClientSession.Session(103, "test", renderer, null, null, 0);
        set(session, "thread", Thread.currentThread());
        SessionDebugTelemetry.capture(session, 1, false);
        session.open.set(false);
        return new WeakReference<>(session);
    }

    private static AsyncNodeManager manager() throws Exception {
        var nodes = allocate(AsyncNodeManager.class);
        set(nodes, "submissionLock", new Object());
        set(nodes, "regionalBatchHandoff", new PublicationHandoff<>());
        set(nodes, "topologyGeneration", new AtomicLong());
        set(nodes, "geometryManager", new BasicAsyncGeometryManager(8, 8192));
        set(nodes, "running", true);
        set(nodes, "workPending", new AtomicBoolean());
        set(nodes, "thread", new Thread(() -> {}));
        set(nodes, "progressListener", (Runnable) () -> {});
        set(nodes, "completedRegionalSectionPublications", new ArrayList<>());
        set(nodes, "completedRendererTransactions", new ArrayList<>());
        set(nodes, "rendererTransactionQueue", new ConcurrentLinkedDeque<>());
        set(nodes, "coarsenQueue", new ConcurrentLinkedDeque<>());
        set(nodes, "gpuCompletions", new ArrayDeque<>());
        set(nodes, "scatterWrite", new me.cortex.voxy.client.core.gl.shader.ShutdownShaderBehaviorTest());
        set(nodes, "multiMemcpy", new me.cortex.voxy.client.core.gl.shader.ShutdownShaderBehaviorTest());
        return nodes;
    }

    private static void publisherSurvivesTeardown(boolean stopFirst) throws Exception {
        var renderer = allocate(VoxyRenderSystem.class);
        var old = manager(); var next = manager();
        set(renderer, "regionalSectionRevision", new AtomicLong(1));
        set(renderer, "nodeManager", old);
        var publisher = renderer.regionalSectionPublisher();
        AtomicInteger wakes = new AtomicInteger(); Runnable listener = wakes::incrementAndGet;
        publisher.setProgressListener(listener);
        if (stopFirst) old.stop();
        set(renderer, "nodeManager", null);
        publisher.clearProgressListener(listener); publisher.clearProgressListener(listener);
        if (!stopFirst) old.stop();
        check(publisher.progress().failure() != null, "stopped progress appears live");
        set(renderer, "nodeManager", next);
        var successor = renderer.regionalSectionPublisher();
        AtomicInteger successorWakes = new AtomicInteger(); Runnable successorListener = successorWakes::incrementAndGet;
        successor.setProgressListener(successorListener);
        publisher.clearProgressListener(successorListener);
        next.notifyPublicationProgress();
        check(successorWakes.get() == 2, "old publisher removed successor listener");
        // Same manager, competing old cleanup and newer registration must preserve new listener.
        for (int i = 0; i < 100; i++) {
            next.setPublicationProgressListener(listener);
            Thread cleanup = new Thread(() -> next.clearPublicationProgressListener(listener));
            cleanup.start(); next.setPublicationProgressListener(successorListener); cleanup.join();
            int before = successorWakes.get(); next.notifyPublicationProgress();
            check(successorWakes.get() == before + 1, "concurrent cleanup erased newer listener");
        }
        AtomicInteger failed = new AtomicInteger();
        publisher.coarsen(16, () -> { throw new AssertionError("stopped coarsen succeeded"); }, error -> failed.incrementAndGet());
        check(failed.get() == 1, "late coarsening failure not delivered");
        boolean rejected = false;
        try { publisher.tryPublishBatch(List.of(new SectionSubmission(16,
                BuiltSection.emptyWithChildren(16, 1, (byte) 0), true, 0, Optional.empty(), () -> true))); }
        catch (IllegalStateException expected) { rejected = true; }
        check(rejected, "late submission reached replacement manager");
        var session = new ClientSession.Session(104, "test", renderer, publisher, null, 1);
        var surface = new Surface(); surface.markRendererAdmitted();
        surface.completeUpload(new UploadOutcome(UploadStatus.ACTIVATED, null, null));
        var demand = session.demands.adopt(new ClientSession.Demand(16)); demand.publication = surface;
        var cacheRoot = Files.createTempDirectory("voxy-shutdown-race-");
        session.cache = new RegionalCache(cacheRoot, RegionalProtocol.Hash32.ZERO);
        session.release();
        check(session.demands.isEmpty() && surface.retirementFencePassed(), "release skipped demands/publications");
        check(session.sectionWorkers[0].resource.state() == WorkerResource.State.CLOSED
                && session.metadataWorker.resource.state() == WorkerResource.State.CLOSED, "release skipped workers");
        check((boolean) get(session.cache, "closed"), "release skipped cache");
        next.stop();
    }

    private static void normalFacadeDoesNothing() throws Exception {
        // The headless test classpath deliberately uses the normal facade.
        var session = new ClientSession.Session(105, "test", null, null, null, 0);
        for (int i = 0; i < 1000; i++) ClientLodDebug.captureSession(session);
        check(SessionDebugTelemetry.latest(session) == null, "normal facade sampled");
    }

    @SuppressWarnings("unchecked")
    private static void sectionCallbacksKeepIdentityAndOwner() throws Exception {
        var renderer = allocate(VoxyRenderSystem.class);
        var old = manager(); var successor = manager();
        set(renderer, "regionalSectionRevision", new AtomicLong(41));
        set(renderer, "nodeManager", old);
        var prepare = Arrays.stream(VoxyRenderSystem.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("prepareRegionalSection")).findFirst().orElseThrow();
        prepare.setAccessible(true);
        Object prepared = prepare.invoke(renderer, old, 16L,
                BuiltSection.emptyWithChildren(16, 1, (byte) 0), null, true, 0L,
                (java.util.function.BooleanSupplier) () -> true);
        var submission = (AsyncNodeManager.RegionalSectionSubmission) get(prepared, "submission");
        var publication = (SectionPublicationState) get(prepared, "publication");
        var queue = (Deque<Object>) get(old, "rendererTransactionQueue");
        var nextQueue = (Deque<Object>) get(successor, "rendererTransactionQueue");
        // Replacement/shutdown can change the outer renderer while old GPU callbacks remain.
        set(renderer, "nodeManager", successor);
        submission.success().run();
        Object finalize = queue.removeFirst();
        checkTransaction(finalize, "FINALIZE", 41, 16);
        check(nextQueue.isEmpty(), "upload callback reached successor renderer");
        ((Runnable) get(finalize, "success")).run(); // explicit fake completion boundary
        publication.close();
        Object retirement = queue.removeFirst();
        checkTransaction(retirement, "RETIRE", 42, 16);
        check((long) get(retirement, "expectedRevision") == 41, "retirement lost expected publication revision");
        ((java.util.function.Consumer<Throwable>) get(retirement, "failure"))
                .accept(new IllegalStateException("injected retirement failure"));
        Object rollback = queue.removeFirst();
        checkTransaction(rollback, "ROLLBACK", 42, 16);
        ((Runnable) get(rollback, "success")).run();
        Object completion = queue.removeFirst();
        checkTransaction(completion, "COMPLETE_ROLLBACK", 42, 16);
        check(!publication.retirementFencePassed(), "rollback skipped completion fence");
        ((Runnable) get(completion, "success")).run();
        check(publication.retirementFencePassed() && queue.isEmpty() && nextQueue.isEmpty(),
                "rollback completion reached new owner or lost retirement");
        old.stop();
        submission.failure().accept(new IllegalStateException("late stopped callback"));
        check(nextQueue.isEmpty(), "stopped failure callback used successor renderer");
        successor.stop();
    }

    private static void checkTransaction(Object transaction, String operation, long revision, long position)
            throws Exception {
        check(get(transaction, "operation").toString().equals(operation)
                && (long) get(transaction, "sourceRevision") == revision
                && (long) get(transaction, "position") == position, "transaction lost exact section identity");
    }

    private static <T> T allocate(Class<T> type) throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe"); field.setAccessible(true);
        return type.cast(((Unsafe) field.get(null)).allocateInstance(type));
    }
    private static void set(Object object, String name, Object value) throws Exception {
        Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); field.set(object, value);
    }
    private static Object get(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object);
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
