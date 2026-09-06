package me.cortex.voxy.client.lod;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.rendering.SectionKey;
import me.cortex.voxy.client.core.rendering.building.BuiltSection;
import me.cortex.voxy.client.core.rendering.hierarchical.AsyncNodeManager;
import me.cortex.voxy.client.core.rendering.hierarchical.NodeManager;
import me.cortex.voxy.client.core.rendering.hierarchical.TerminalNodeBehaviorTest;
import me.cortex.voxy.client.core.rendering.hierarchical.SectionPublicationState;
import me.cortex.voxy.client.core.rendering.section.BasicAsyncGeometryManager;
import me.cortex.voxy.common.util.Cleanup;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.StampedLock;

import static me.cortex.voxy.client.lod.DebugSnapshotShutdownBehaviorTest.*;

/** Actual publication/submission/consumer/worker ownership, with GL construction controlled. */
final class TerminalRendererTeardownBehaviorTest {
    static void run() throws Exception {
        baselineRetirementVisits();
        for (int sample = 0; sample < 7; sample++) {
            for (int n : new int[]{1000, 2000, 4000, 8000, 100000}) terminalPublications(n, sample);
        }
        activeTransactionStopsBacklog();
        interruptedJoinAndLateResult();
        actualSessionWaitsForMesher();
        cleanupFailureStillDisposes();
        pendingAndCachedResultsDisposeOnce();
        System.out.println("terminal renderer teardown production behavior tests passed");
    }

    private static final class CountMap extends HashMap<Object, Object> {
        long visits;
        @Override public Collection<Object> values() {
            var values = super.values();
            return new AbstractCollection<>() {
                public int size() { return values.size(); }
                public Iterator<Object> iterator() {
                    var iterator = values.iterator();
                    return new Iterator<>() {
                        public boolean hasNext() { return iterator.hasNext(); }
                        public Object next() { visits++; return iterator.next(); }
                    };
                }
            };
        }
    }

    private static void baselineRetirementVisits() throws Exception {
        for (int n : new int[]{1000, 2000, 4000, 8000}) {
            var nodes = new TerminalNodeBehaviorTest(16384, new BasicAsyncGeometryManager(16, 16384));
            var entries = new CountMap();
            var field = NodeManager.class.getDeclaredField("committedPositions");
            field.setAccessible(true); field.set(nodes, entries);
            long[] keys = new long[n];
            for (int i = 0; i < n; i++) {
                long key = keys[i] = SectionKey.pack(4, i, 0, 0);
                nodes.insertTopLevelNode(key);
                check(nodes.stage(BuiltSection.emptyWithChildren(key, i + 1, (byte) 0)),
                        "baseline staging failed");
                nodes.commitSection(i + 1, key);
                check(nodes.finalizeSection(i + 1, key), "baseline finalize failed");
            }
            long start = System.nanoTime();
            for (int i = 0; i < n; i++) check(nodes.retirePublication(n + i + 1, i + 1, keys[i]), "baseline retire failed");
            check(entries.visits == (long) n * (n - 1) / 2, "baseline scan changed");
            System.out.printf("terminal baseline n=%d visits=%d wallNanos=%d%n", n, entries.visits, System.nanoTime() - start);
        }
    }

    private static void terminalPublications(int n, int sample) throws Exception {
        var renderer = allocate(VoxyRenderSystem.class);
        var nodes = manager();
        set(renderer, "regionalSectionRevision", new AtomicLong(1));
        set(renderer, "nodeManager", nodes);
        var prepare = Arrays.stream(VoxyRenderSystem.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("prepareRegionalSection")).findFirst().orElseThrow();
        prepare.setAccessible(true);
        var owned = new SectionPublicationState[n];
        for (int i = 0; i < n; i++) {
            Object prepared = prepare.invoke(renderer, nodes, (long) i,
                    BuiltSection.emptyWithChildren(i, i + 1, (byte) 0), null, true, 0L,
                    (java.util.function.BooleanSupplier) () -> true);
            owned[i] = (SectionPublicationState) get(prepared, "publication");
            owned[i].markRendererAdmitted();
            owned[i].completeUpload(new VoxyRenderSystem.UploadOutcome(VoxyRenderSystem.UploadStatus.ACTIVATED, null, null));
        }
        var queue = (Deque<?>) get(nodes, "rendererTransactionQueue");
        var cpu = java.lang.management.ManagementFactory.getThreadMXBean();
        var allocation = (com.sun.management.ThreadMXBean) cpu;
        long allocated = allocation.getThreadAllocatedBytes(Thread.currentThread().threadId());
        long cpuStart = cpu.getCurrentThreadCpuTime(), start = System.nanoTime();
        nodes.beginStopping(); nodes.beginStopping();
        for (var publication : owned) {
            publication.close(); publication.close();
            check(publication.retirementFencePassed(), "terminal retirement missing");
            check(publication.takeUploadOutcome().orElseThrow().status() == VoxyRenderSystem.UploadStatus.ACTIVATED,
                    "terminal resolution rewrote activation");
            check(publication.takeUploadOutcome().isEmpty(), "duplicate outcome");
        }
        check(queue.isEmpty(), "ordinary retirement submitted after stop");
        nodes.start(); // stop-before-start cannot resurrect worker
        nodes.stop(); nodes.stop();
        System.out.printf("terminal candidate sample=%d n=%d retirements=0 resolved=%d wallNanos=%d cpuNanos=%d allocatedBytes=%d%n",
                sample, n, n, System.nanoTime() - start, cpu.getCurrentThreadCpuTime() - cpuStart,
                allocation.getThreadAllocatedBytes(Thread.currentThread().threadId()) - allocated);
    }

    private static final class ControlledNodes extends TerminalNodeBehaviorTest {
        final CountDownLatch entered = new CountDownLatch(1), resume = new CountDownLatch(1);
        int calls;
        ControlledNodes() { super(16, new BasicAsyncGeometryManager(8, 8192)); }
        @Override public boolean retirePublication(long revision, long expected, long key) {
            calls++; entered.countDown(); await(resume); return true;
        }
    }

    private static void activeTransactionStopsBacklog() throws Exception {
        var nodes = manager(); var hierarchy = new ControlledNodes();
        set(nodes, "manager", hierarchy);
        set(nodes, "tlnLock", new StampedLock());
        set(nodes, "tlnAdd", new LongOpenHashSet()); set(nodes, "tlnRem", new LongOpenHashSet());
        var run = AsyncNodeManager.class.getDeclaredMethod("run"); run.setAccessible(true);
        AtomicReference<Throwable> workerFailure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try { run.invoke(nodes); } catch (Throwable error) { workerFailure.set(error); }
        }, "terminal-test-hierarchy");
        set(nodes, "thread", worker);
        AtomicInteger failed = new AtomicInteger();
        for (int i = 0; i < 8000; i++) nodes.retirePublication(i + 1, i, i,
                () -> { throw new AssertionError("obsolete transaction succeeded"); }, error -> failed.incrementAndGet());
        worker.start(); await(hierarchy.entered);
        nodes.beginStopping(); hierarchy.resume.countDown(); join(worker);
        check(workerFailure.get() == null, "hierarchy worker failed: " + workerFailure.get());
        check(hierarchy.calls == 1, "stop processed obsolete backlog");
        check(get(nodes, "results") == null && get(nodes, "assemblingResult") == null, "stop packed GPU updates");
        nodes.stop(); nodes.stop();
        check(failed.get() == 8000, "queued/completed transactions not resolved exactly once");
        check(((Deque<?>) get(nodes, "rendererTransactionQueue")).isEmpty(), "terminal queue retained work");
    }

    private static void interruptedJoinAndLateResult() throws Exception {
        AtomicInteger disposed = new AtomicInteger();
        AtomicBoolean dependencyFreed = new AtomicBoolean();
        var resource = new WorkerResource<Integer>(0, value -> {
            check(!dependencyFreed.get(), "late result touched freed dependency"); disposed.incrementAndGet();
        });
        var lease = resource.acquire();
        CountDownLatch entered = new CountDownLatch(1), resume = new CountDownLatch(1), waiting = new CountDownLatch(1);
        Thread worker = new Thread(() -> { entered.countDown(); await(resume); resource.complete(lease, 1); });
        worker.start(); await(entered); resource.close();
        AtomicBoolean interruptPreserved = new AtomicBoolean();
        Thread closer = new Thread(() -> {
            Thread.currentThread().interrupt(); waiting.countDown(); Cleanup.join(worker);
            interruptPreserved.set(Thread.currentThread().isInterrupted()); dependencyFreed.set(true);
        });
        closer.start(); await(waiting);
        check(!dependencyFreed.get(), "close-requested mistaken for worker exit");
        resume.countDown(); join(worker); join(closer);
        check(disposed.get() == 1 && interruptPreserved.get(), "late result/interrupt ownership lost");
        resource.close(); check(!resource.release(lease), "closed worker lease revived");
    }

    private static void cleanupFailureStillDisposes() throws Exception {
        var nodes = manager(); AtomicInteger callbacks = new AtomicInteger();
        RuntimeException injected = new RuntimeException("injected terminal callback");
        for (int i = 0; i < 10; i++) nodes.retirePublication(i + 1, i, i, () -> {}, error -> {
            callbacks.incrementAndGet(); throw injected;
        });
        boolean reported = false;
        try { nodes.stop(); } catch (RuntimeException error) { reported = error == injected; }
        nodes.stop();
        check(reported && callbacks.get() == 10, "throwing callback skipped independent cleanup");
        check(((me.cortex.voxy.client.core.gl.shader.ShutdownShaderBehaviorTest) get(nodes, "scatterWrite")).frees == 1,
                "throwing callback skipped program disposal");
        AtomicInteger frees = new AtomicInteger();
        var resource = new WorkerResource<Integer>(0, value -> { frees.incrementAndGet(); throw injected; });
        resource.complete(resource.acquire(), 1);
        try { resource.close(); } catch (RuntimeException expected) { check(expected == injected, "wrong failure"); }
        resource.close(); check(frees.get() == 1, "throwing disposer retained worker result");
    }

    private static void actualSessionWaitsForMesher() throws Exception {
        var fixture = CacheStartupBehaviorTest.fixture(1, 1, 255, 1);
        var root = java.nio.file.Files.createTempDirectory("voxy-terminal-worker-");
        var cache = new RegionalCache(root, CacheStartupBehaviorTest.WORLD);
        CacheStartupBehaviorTest.awaitInventory((RegionalDiskBudget) get(cache, "budget"));
        int ordinal = fixture.index().ordinal(CacheStartupBehaviorTest.KEY);
        cache.put(fixture.index(), ordinal, fixture.payload());
        CountDownLatch entered = new CountDownLatch(1), stopRequested = new CountDownLatch(1), resume = new CountDownLatch(1);
        AtomicBoolean dependenciesFreed = new AtomicBoolean(), blocked = new AtomicBoolean();
        var models = new me.cortex.voxy.client.core.rendering.building.SectionMesher.Models() {
            public int getModelId(int block) {
                check(!dependenciesFreed.get(), "mesher touched freed models");
                if (blocked.compareAndSet(false, true)) {
                    entered.countDown();
                    boolean interrupted = false;
                    for (;;) {
                        try { check(resume.await(10, TimeUnit.SECONDS), "late mesher timeout"); break; }
                        catch (InterruptedException expected) { interrupted = true; stopRequested.countDown(); }
                    }
                    if (interrupted) Thread.currentThread().interrupt();
                }
                return 1;
            }
            public long getModelMetadataFromClientId(int id) { return 0; }
            public int getFluidClientStateId(int id) { return 0; }
            public boolean isModelReadyForBlockId(int block) { return true; }
            public boolean isWaterState(int block) { return false; }
        };
        var constructor = me.cortex.voxy.client.core.rendering.building.SectionMesher.class.getDeclaredConstructor(
                me.cortex.voxy.client.core.rendering.building.SectionMesher.Models.class, java.util.function.IntConsumer.class);
        constructor.setAccessible(true);
        var mesher = constructor.newInstance(models, (java.util.function.IntConsumer) ignored -> {});
        var session = new ClientSession.Session(211, "test", null, new CacheStartupBehaviorTest.Publisher(), mesher, 1);
        session.cache = cache;
        var worker = session.sectionWorkers[0];
        var demand = session.demands.adopt(new ClientSession.Demand(CacheStartupBehaviorTest.KEY));
        worker.start();
        worker.assign(new ClientSession.Session.SectionWorkerTask(demand.ticket(session.id, 0), fixture.index(), ordinal,
                ClientSession.Session.WorkerSource.CACHE, null, CacheStartupBehaviorTest.MAPPINGS, cache));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread release = new Thread(() -> {
            try { session.release(); dependenciesFreed.set(true); }
            catch (Throwable error) { failure.set(error); }
        }, "terminal-test-session-release");
        try {
            await(entered); release.start(); await(stopRequested);
            check(worker.resource.state() == WorkerResource.State.CLOSED && !dependenciesFreed.get(),
                    "session released dependencies while closed worker was still meshing");
            resume.countDown(); join(release); join(worker.workerThread);
            check(failure.get() == null && dependenciesFreed.get(), "session release failed: " + failure.get());
            check(worker.resource.pendingResult() == null && session.demands.isEmpty(), "late mesh retained ownership");
        } finally {
            resume.countDown(); worker.close(); join(worker.workerThread);
            if (release.isAlive()) join(release);
            cache.close(); CacheStartupBehaviorTest.cleanup(root);
        }
    }

    private static final class Fence extends me.cortex.voxy.client.core.gl.GlFence {
        int frees;
        @Override public void free() { check(++frees == 1, "fence freed twice"); }
    }

    @SuppressWarnings("unchecked")
    private static void pendingAndCachedResultsDisposeOnce() throws Exception {
        var nodes = manager();
        var queue = (Deque<Object>) get(nodes, "rendererTransactionQueue");
        AtomicInteger failures = new AtomicInteger();
        var resultType = Arrays.stream(AsyncNodeManager.class.getDeclaredClasses())
                .filter(type -> type.getSimpleName().equals("SyncResults")).findFirst().orElseThrow();
        var ctor = resultType.getDeclaredConstructor(); ctor.setAccessible(true);
        var buffers = new ArrayList<me.cortex.voxy.common.util.MemoryBuffer>();
        var gpuTransactions = new ArrayList<>();
        int revision = 0;
        for (String field : new String[]{"results", "assemblingResult", "resultCache1", "resultCache2"}) {
            Object result = ctor.newInstance(); set(nodes, field, result);
            buffers.add((me.cortex.voxy.common.util.MemoryBuffer) get(result, "scatterWriteBuffer"));
            Object copies = get(result, "geometryUpload");
            buffers.add((me.cortex.voxy.common.util.MemoryBuffer) get(copies, "scratchHeaderBuffer"));
            buffers.add((me.cortex.voxy.common.util.MemoryBuffer) get(copies, "scratchDataBuffer"));
            nodes.retirePublication(++revision, 0, 0, () -> { throw new AssertionError("terminal activation"); },
                    error -> failures.incrementAndGet());
            Object transaction = queue.remove();
            ((ArrayList<Object>) get(result, "rendererTransactions")).add(transaction);
            // Uploaded cache copies are not callback owners; the GPU completion owns these.
            if (field.startsWith("resultCache")) gpuTransactions.add(transaction);
        }
        var completionType = Arrays.stream(AsyncNodeManager.class.getDeclaredClasses())
                .filter(type -> type.getSimpleName().equals("GpuCompletion")).findFirst().orElseThrow();
        var completionCtor = completionType.getDeclaredConstructors()[0]; completionCtor.setAccessible(true);
        var fence = allocate(Fence.class);
        ((Deque<Object>) get(nodes, "gpuCompletions")).add(completionCtor.newInstance(fence, gpuTransactions, new ArrayList<>()));
        nodes.stop(); nodes.stop();
        check(failures.get() == 4 && fence.frees == 1, "pending/cached/GPU completion ownership duplicated or lost");
        for (var buffer : buffers) check(buffer.isFreed(), "terminal result retained native buffer");
    }

    private static void await(CountDownLatch latch) {
        try { check(latch.await(10, TimeUnit.SECONDS), "barrier timeout: " + Thread.getAllStackTraces().keySet()); }
        catch (InterruptedException error) { throw new AssertionError(error); }
    }
    private static void join(Thread thread) throws Exception {
        thread.join(10000); check(!thread.isAlive(), "thread timeout: " + Arrays.toString(thread.getStackTrace()));
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
