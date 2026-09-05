package me.cortex.voxy.client.lod;

import me.cortex.voxy.client.core.rendering.building.SectionMesher;
import me.cortex.voxy.client.core.model.ModelFactory;
import me.cortex.voxy.client.core.model.MaterialCompatibility;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.lang.management.*;
import java.lang.reflect.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Runs the actual debug facade and worker methods, without starting its network updater. */
public final class WorkerShaderDebugBehaviorTest {
    public static void main(String[] args) throws Exception {
        Class<?> updater = Class.forName("me.cortex.voxy.client.lod.ClientAutoUpdater");
        Field started = updater.getDeclaredField("STARTED"); started.setAccessible(true);
        ((AtomicBoolean) started.get(null)).set(true);
        stageAccountingAndCpuUnavailable();
        actualWorkerStalls();
        shaderDiffAndAliases();
        System.out.println("actual debug worker boundaries, lock-owner evidence, CPU availability and shader diff tests passed");
    }

    private static void stageAccountingAndCpuUnavailable() throws Exception {
        var work = new WorkerDebugTelemetry.Work(1, 0, Thread.currentThread().threadId());
        var unavailable = (ThreadMXBean) Proxy.newProxyInstance(ThreadMXBean.class.getClassLoader(),
                new Class<?>[]{ThreadMXBean.class}, (proxy, method, args) -> {
                    if (method.getName().equals("isThreadCpuTimeSupported")) return false;
                    throw new AssertionError("unsupported CPU timing was queried");
                });
        for (String stage : new String[]{"CACHE_READ", "CACHE_WRITE", "DECOMPRESS", "DECODE_VALIDATE", "REQUEST_MODELS", "MESH"}) {
            work.begin(2, "SectionWorkerTask", 16, 1, 1, "CACHE"); work.stage(stage);
            var before = work.copy(); Thread.sleep(2);
            String sample = WorkerDebugTelemetry.sample(work, System.nanoTime(), unavailable, ignored -> {});
            check(sample.contains("cpu=UNSUPPORTED") && sample.contains("cpuDeltaNs=-1"), "invented zero CPU");
            check(work.copy().counts()[WorkerDebugTelemetry.Stage.valueOf(stage).ordinal()]
                    == before.counts()[WorkerDebugTelemetry.Stage.valueOf(stage).ordinal()], "unfinished duration counted");
            work.end();
            check(work.copy().stage() == WorkerDebugTelemetry.Stage.IDLE, "stage did not finish");
        }
        long jobs = work.copy().jobs(); work.begin(3, "SectionWorkerTask", 17, 2, 2, "NETWORK");
        check(work.copy().lease() == 3 && work.copy().key() == 17 && work.copy().repeats() == 0, "lease reused stale identity");
        work.outcome("MODEL_WAIT", 0); work.outcome("FAILURE", 0); work.end();
        check(work.copy().jobs() == jobs + 1 && work.copy().outcomes()[3] == 1, "outcome accounting changed");
    }

    private static void actualWorkerStalls() throws Exception {
        var fixture = CacheStartupBehaviorTest.fixture(1, 1, 255, 1);
        var cache = new RegionalCache(Files.createTempDirectory("voxy-instrumentation-cache-"), CacheStartupBehaviorTest.WORLD);
        int ordinal = fixture.index().ordinal(CacheStartupBehaviorTest.KEY);
        cache.put(fixture.index(), ordinal, fixture.payload());
        Object budget = field(cache, "budget");
        AtomicReference<String> blockAt = new AtomicReference<>("REQUEST_MODELS");
        CountDownLatch modelEntered = new CountDownLatch(1), modelResume = new CountDownLatch(1);
        CountDownLatch meshEntered = new CountDownLatch(1), meshResume = new CountDownLatch(1);
        AtomicBoolean modelsReady = new AtomicBoolean(true);
        var models = new SectionMesher.Models() {
            public int getModelId(int block) { if (blockAt.compareAndSet("MESH", "NONE")) await(meshEntered, meshResume); return 1; }
            public long getModelMetadataFromClientId(int id) { return 0; }
            public int getFluidClientStateId(int id) { return 0; }
            public boolean isModelReadyForBlockId(int block) { return modelsReady.get(); }
            public boolean isWaterState(int block) { return false; }
        };
        var ctor = SectionMesher.class.getDeclaredConstructor(SectionMesher.Models.class, java.util.function.IntConsumer.class);
        ctor.setAccessible(true);
        var mesher = ctor.newInstance(models, (java.util.function.IntConsumer) ignored -> {
            if (blockAt.compareAndSet("REQUEST_MODELS", "NONE")) await(modelEntered, modelResume);
        });
        var session = new ClientSession.Session(88, "test", null, new CacheStartupBehaviorTest.Publisher(), mesher, 1);
        check(((WorkerDebugTelemetry.Work) session.metadataWorker.debugWork).session == 88,
                "metadata worker captured the uninitialized session identity");
        var worker = session.sectionWorkers[0]; worker.start();
        var work = (WorkerDebugTelemetry.Work) worker.debugWork;
        var demand = session.demands.adopt(new ClientSession.Demand(CacheStartupBehaviorTest.KEY));
        var ticket = demand.ticket(session.id, 0);
        var task = new ClientSession.Session.SectionWorkerTask(ticket, fixture.index(), ordinal,
                ClientSession.Session.WorkerSource.CACHE, null, CacheStartupBehaviorTest.MAPPINGS, cache);
        List<String> evidence = new ArrayList<>();
        var bean = ManagementFactory.getThreadMXBean();
        try {
            synchronized (budget) {
                worker.assign(task);
                until(() -> work.copy().stage() == WorkerDebugTelemetry.Stage.CACHE_READ);
                until(() -> worker.workerThread.getState() == Thread.State.BLOCKED);
                WorkerDebugTelemetry.sample(work, System.nanoTime(), bean, evidence::add);
                WorkerDebugTelemetry.sample(work, System.nanoTime(), bean, evidence::add);
                WorkerDebugTelemetry.sample(work, System.nanoTime(), bean, evidence::add);
                check(evidence.size() == 1 && evidence.getFirst().contains("BLOCKED")
                        && evidence.getFirst().contains("owner=" + Thread.currentThread().threadId())
                        && evidence.getFirst().contains("holderAt="), "cache lock holder not identified or duplicate stack flood");
            }
            check(modelEntered.await(5, TimeUnit.SECONDS), "actual request-model boundary not reached");
            check(work.copy().stage() == WorkerDebugTelemetry.Stage.REQUEST_MODELS, "models mislabeled meshing");
            blockAt.set("MESH"); modelResume.countDown();
            check(meshEntered.await(5, TimeUnit.SECONDS), "actual mesh boundary not reached");
            check(work.copy().stage() == WorkerDebugTelemetry.Stage.MESH, "mesh stage incorrect");
            meshResume.countDown();
            until(() -> work.copy().jobs() == 1);
            var completed = worker.resource.claim(); check(completed != null, "completion lost");
            if (completed.value() instanceof ClientSession.Session.WorkerGeometry geometry) geometry.geometry().free();
            worker.releaseCompletion(completed.lease());
            check(work.copy().counts()[WorkerDebugTelemetry.Stage.DECOMPRESS.ordinal()] == 1
                    && work.copy().counts()[WorkerDebugTelemetry.Stage.DECODE_VALIDATE.ordinal()] == 1, "real decode stages missing");
            modelsReady.set(false);
            worker.assign(task); until(() -> work.copy().jobs() == 2);
            completed = worker.resource.claim(); worker.releaseCompletion(completed.lease());
            check(work.copy().outcomes()[WorkerDebugTelemetry.Outcome.MODEL_WAIT.ordinal()] == 1
                    && work.copy().repeats() == 1, "model retry identity missing");
            // An invalid compressed body still leaves a terminal diagnostic state and unchanged failure handling.
            worker.assign(new ClientSession.Session.SectionWorkerTask(ticket, fixture.index(), ordinal,
                    ClientSession.Session.WorkerSource.NETWORK, new byte[]{1}, CacheStartupBehaviorTest.MAPPINGS, cache));
            until(() -> work.copy().jobs() == 3);
            check(work.copy().stage() == WorkerDebugTelemetry.Stage.IDLE
                    && work.copy().outcomes()[WorkerDebugTelemetry.Outcome.FAILURE.ordinal()] == 1, "exception left running stage");
            completed = worker.resource.claim(); worker.releaseCompletion(completed.lease());
            synchronized (budget) {
                worker.assign(new ClientSession.Session.SectionWorkerTask(ticket, fixture.index(), ordinal,
                        ClientSession.Session.WorkerSource.NETWORK, fixture.payload(), CacheStartupBehaviorTest.MAPPINGS, cache));
                until(() -> work.copy().stage() == WorkerDebugTelemetry.Stage.CACHE_WRITE);
                until(() -> worker.workerThread.getState() == Thread.State.BLOCKED);
                var before = work.copy(); Thread.sleep(3);
                check(work.copy().stageStart() == before.stageStart()
                        && work.copy().jobs() == 3, "cache write invented completion");
            }
            until(() -> work.copy().jobs() == 4);
            completed = worker.resource.claim(); worker.releaseCompletion(completed.lease());
            cache.quarantine(fixture.index(), ordinal);
            worker.assign(task); until(() -> work.copy().jobs() == 5);
            completed = worker.resource.claim();
            check(completed.value().getClass().getSimpleName().equals("WorkerMiss")
                    && work.copy().outcomes()[WorkerDebugTelemetry.Outcome.CACHE_MISS.ordinal()] == 1, "real cache miss missing");
            worker.releaseCompletion(completed.lease());
            cache.put(fixture.index(), ordinal, new byte[fixture.payload().length]);
            worker.assign(task); until(() -> work.copy().jobs() == 6);
            completed = worker.resource.claim();
            check(completed.value().getClass().getSimpleName().equals("WorkerMiss")
                    && work.copy().outcomes()[WorkerDebugTelemetry.Outcome.CACHE_CORRUPT.ordinal()] == 1
                    && work.copy().counts()[WorkerDebugTelemetry.Stage.CACHE_QUARANTINE.ordinal()] == 1,
                    "real corrupt cache did not reach quarantine");
            worker.releaseCompletion(completed.lease());
            // Closing a leased worker must still finish its diagnostic record and dispose completion.
            synchronized (budget) {
                worker.assign(task);
                until(() -> work.copy().stage() == WorkerDebugTelemetry.Stage.CACHE_READ);
                worker.close();
                check(work.copy().closing(), "retiring worker not identified");
            }
            worker.workerThread.join(5000);
            check(!worker.workerThread.isAlive() && work.copy().stage() == WorkerDebugTelemetry.Stage.IDLE,
                    "retirement retained a running diagnostic record");
        } finally {
            modelResume.countDown(); meshResume.countDown(); worker.close(); worker.workerThread.join(5000); cache.close();
        }
    }

    private enum Layer { SOLID, CUTOUT }
    private static void shaderDiffAndAliases() {
        net.neoforged.fml.loading.LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap();
        BlockState stone = Blocks.STONE.defaultBlockState(), dirt = Blocks.DIRT.defaultBlockState();
        var models = new ModelFactory.DebugModels(new BlockState[]{stone, dirt}, new BlockState[]{stone, dirt},
                new int[]{0, 0}, new boolean[]{false, true}, 0);
        var same = ShaderDebugTelemetry.diff(Map.of(Blocks.STONE, Layer.SOLID), Map.of(Blocks.STONE, Layer.SOLID), models);
        check(same.changed() == 0, "identical maps changed");
        var unused = ShaderDebugTelemetry.diff(Map.of(), Map.of(Blocks.GLASS, Layer.CUTOUT), models);
        check(unused.changed() == 1 && unused.mapped() == 0 && unused.summary().contains("fallbackEffectiveUnknown=1"), "unused/fallback misclassified");
        var used = ShaderDebugTelemetry.diff(Map.of(Blocks.DIRT, Layer.SOLID), Map.of(Blocks.DIRT, Layer.CUTOUT), models);
        check(used.mapped() == 1 && used.baked() == 1 && used.pending() == 1, "baked/pending overlap lost");
        var map = new Object2IntOpenHashMap<BlockState>(); map.put(stone, 3); map.put(dirt, 4);
        String conflict = ShaderDebugTelemetry.aliasConflict(models, map);
        check(conflict.contains("minecraft:stone") && conflict.contains("minecraft:dirt") && conflict.contains("material=4"), "alias conflict lacks canonical states");
        boolean rejected = false;
        try { MaterialCompatibility.resolve(2, 1, i -> 0, i -> i + 3); }
        catch (me.cortex.voxy.client.core.ShaderReloadCoordinator.Incompatible expected) { rejected = true; }
        check(rejected && MaterialCompatibility.resolve(2, 1, i -> 0, i -> 3)[0] == 3, "compatibility guard changed");
    }

    private static Object field(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object);
    }
    private static void await(CountDownLatch entered, CountDownLatch resume) {
        entered.countDown(); try { if (!resume.await(5, TimeUnit.SECONDS)) throw new AssertionError("boundary timeout"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }
    private static void until(java.util.function.BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(1);
        check(condition.getAsBoolean(), "worker timed out");
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
