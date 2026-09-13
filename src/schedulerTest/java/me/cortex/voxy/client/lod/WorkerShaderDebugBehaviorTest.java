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
        autoConnectOnlyFromIdleMenus(updater);
        cacheResetSafety();
        stageAccountingAndCpuUnavailable();
        transportCounters();
        transportHoldLease();
        cacheTestProfileSafety();
        overlappingRestartCopies();
        DebugSnapshotShutdownBehaviorTest.cacheMonitorDoesNotBlockOwner();
        actualWorkerStalls();
        shaderDiffAndAliases();
        HarnessTerminalBehaviorTest.run();
        System.out.println("actual debug worker boundaries, lock-owner evidence, CPU availability and shader diff tests passed");
    }

    private static void cacheTestProfileSafety() throws Exception {
        var type = Class.forName("me.cortex.voxy.client.lod.DebugCacheTestProfile");
        var parse = type.getDeclaredMethod("parse", String.class, long.class); parse.setAccessible(true);
        var install = type.getDeclaredMethod("install", java.nio.file.Path.class, String.class); install.setAccessible(true);
        long now = System.currentTimeMillis();
        String request = UUID.randomUUID().toString(), namespace = UUID.randomUUID().toString();
        String valid = request + " " + namespace + " " + (now + 60_000) + " 2000";
        check(parse.invoke(null, valid, now) != null, "valid scoped profile rejected");
        for (String invalid : List.of(request + " ../escape " + (now + 60_000) + " 0",
                request + " " + namespace + " " + (now - 1) + " 0",
                request + " " + namespace + " " + (now + 31 * 60_000) + " 0",
                request + " " + namespace + " " + (now + 60_000) + " 60001")) {
            try { parse.invoke(null, invalid, now); throw new AssertionError("unsafe profile accepted"); }
            catch (InvocationTargetException expected) { check(expected.getCause() instanceof IllegalArgumentException, "unexpected parse failure"); }
        }
        var root = Files.createTempDirectory("voxy-cache-profile-");
        try {
            check((boolean) install.invoke(null, root, valid), "new profile not installed");
            check(!(boolean) install.invoke(null, root, valid), "consumed profile would restart repeatedly");
            check(!Files.exists(root.resolve(".voxy")), "profile touched the cache");
        } finally { CacheStartupBehaviorTest.cleanup(root); }
    }

    private static void overlappingRestartCopies() throws Exception {
        var type = Class.forName("me.cortex.voxy.client.lod.ClientUpdateRestart");
        var stabilize = type.getDeclaredMethod("stabilizeLaunchFiles", List.class, java.nio.file.Path.class, java.nio.file.Path.class);
        var cleanup = type.getDeclaredMethod("deleteLaunchCopies", java.nio.file.Path.class, java.nio.file.Path.class);
        stabilize.setAccessible(true); cleanup.setAccessible(true);
        var root = Files.createTempDirectory("voxy-overlap-restart-");
        try {
            var source = root.resolve("agent.jar"); Files.write(source, new byte[]{1, 2, 3});
            var first = Files.createTempDirectory(root, "launch-");
            var second = Files.createTempDirectory(root, "launch-");
            @SuppressWarnings("unchecked") var firstCommand = (List<String>) stabilize.invoke(null,
                    List.of("java", "-javaagent:" + source, "Main"), first, root);
            @SuppressWarnings("unchecked") var secondCommand = (List<String>) stabilize.invoke(null, firstCommand, second, root);
            var firstAgent = java.nio.file.Path.of(firstCommand.get(1).substring("-javaagent:".length()));
            var secondAgent = java.nio.file.Path.of(secondCommand.get(1).substring("-javaagent:".length()));
            check(!firstAgent.equals(secondAgent), "restart helpers shared a launch copy");
            cleanup.invoke(null, first, root.resolve("restart.log"));
            check(!Files.exists(firstAgent) && Arrays.equals(Files.readAllBytes(secondAgent), new byte[]{1, 2, 3}),
                    "older helper cleanup removed successor's agent");
        } finally { CacheStartupBehaviorTest.cleanup(root); }
    }

    private static void transportHoldLease() throws Exception {
        var directory = Files.createTempDirectory("voxy-hold-lease-");
        var marker = directory.resolve("hold");
        var method = Class.forName("me.cortex.voxy.client.lod.ClientLodDebug")
                .getDeclaredMethod("holdDeadline", java.nio.file.Path.class, long.class);
        method.setAccessible(true);
        long now = 2000000000000L, duration = TimeUnit.MINUTES.toMillis(5);
        try {
            check((long) method.invoke(null, marker, now) == 0, "missing marker enabled hold");
            Files.writeString(marker, "legacy marker contents");
            Files.setLastModifiedTime(marker, java.nio.file.attribute.FileTime.fromMillis(now - 10000));
            long expected = now - 10000 + duration;
            check((long) method.invoke(null, marker, now) == expected
                    && (long) method.invoke(null, marker, now + 20000) == expected, "restart renewed transport hold");
            check((long) method.invoke(null, marker, expected) == 0, "expired lease blocked connection");
            Files.setLastModifiedTime(marker, java.nio.file.attribute.FileTime.fromMillis(now + duration));
            check((long) method.invoke(null, marker, now) == now + duration, "future timestamp extended maximum hold");
        } finally { CacheStartupBehaviorTest.cleanup(directory); }
    }

    private static void transportCounters() throws Exception {
        long sent = TransportDebugTelemetry.sent.get(), received = TransportDebugTelemetry.received.get();
        long opened = TransportDebugTelemetry.opened.get(), closed = TransportDebugTelemetry.closed.get();
        try (var tracked = new TransportDebugTelemetry.Socket(); var peer = new java.net.DatagramSocket()) {
            tracked.setSoTimeout(2000); peer.setSoTimeout(2000);
            var address = java.net.InetAddress.getLoopbackAddress();
            tracked.send(new java.net.DatagramPacket(new byte[31], 31, address, peer.getLocalPort()));
            peer.receive(new java.net.DatagramPacket(new byte[100], 100));
            peer.send(new java.net.DatagramPacket(new byte[17], 17, address, tracked.getLocalPort()));
            tracked.receive(new java.net.DatagramPacket(new byte[100], 100));
            check(TransportDebugTelemetry.sent.get() - sent == 31
                    && TransportDebugTelemetry.received.get() - received == 17, "wire counter counted buffer capacity or peer traffic");
            tracked.close(); tracked.close();
            try { tracked.send(new java.net.DatagramPacket(new byte[3], 3, address, peer.getLocalPort()));
                throw new AssertionError("closed UDP socket accepted send");
            } catch (java.io.IOException expected) { }
            check(TransportDebugTelemetry.sent.get() - sent == 31, "failed send counted as transmitted bytes");
        }
        check(TransportDebugTelemetry.opened.get() - opened == 1 && TransportDebugTelemetry.closed.get() - closed == 1,
                "socket ownership counters leaked or double-closed");
    }

    private static void cacheResetSafety() throws Exception {
        Class<?> restart = Class.forName("me.cortex.voxy.client.lod.ClientUpdateRestart");
        var reset = restart.getDeclaredMethod("resetCache", java.nio.file.Path.class, String.class, long.class);
        reset.setAccessible(true);
        var child = new ProcessBuilder(java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-version").redirectError(ProcessBuilder.Redirect.DISCARD).start();
        child.waitFor();
        long deadPid = child.pid();
        var game = Files.createTempDirectory("voxy-reset-safety-");
        var cache = Files.createDirectories(game.resolve(".voxy/nested"));
        Files.writeString(cache.resolve("payload"), "cached");
        Files.writeString(game.resolve("options.txt"), "preserve");
        Files.createDirectories(game.resolve("mods"));
        Files.writeString(game.resolve("mods/keep.jar"), "preserve");
        String request = UUID.randomUUID().toString();
        expectResetFailure(reset, game, request, ProcessHandle.current().pid());
        check(Files.exists(cache.resolve("payload")), "active-client refusal deleted data");
        expectResetFailure(reset, game, "../../outside", deadPid);
        check(Files.exists(cache.resolve("payload")), "invalid reset ID deleted data");
        check((Long) reset.invoke(null, game, request, deadPid) == 6, "reset byte accounting");
        check(!Files.exists(game.resolve(".voxy")), "cache remains after reset");
        check(Files.readString(game.resolve("options.txt")).equals("preserve")
                && Files.exists(game.resolve("mods/keep.jar")), "reset escaped cache");
        Files.createDirectories(cache);
        Files.writeString(cache.resolve("new-cache"), "new");
        check((Long) reset.invoke(null, game, request, deadPid) == 0
                && Files.exists(cache.resolve("new-cache")), "request replay cleared new data");
        reset.invoke(null, game, UUID.randomUUID().toString(), deadPid);
        reset.invoke(null, game, UUID.randomUUID().toString(), deadPid); // Already absent.
        var outside = Files.createTempDirectory("voxy-reset-outside-");
        Files.writeString(outside.resolve("keep"), "preserve");
        Files.createSymbolicLink(game.resolve(".voxy"), outside);
        expectResetFailure(reset, game, UUID.randomUUID().toString(), deadPid);
        check(Files.exists(outside.resolve("keep")), "linked root escaped cache");
        Files.delete(game.resolve(".voxy"));
        Files.createDirectory(game.resolve(".voxy"));
        Files.createSymbolicLink(game.resolve(".voxy/outside"), outside);
        reset.invoke(null, game, UUID.randomUUID().toString(), deadPid);
        check(Files.exists(outside.resolve("keep")), "nested directory symlink was followed");
        System.out.println("cache reset: stopped-process gate, exact scope, replay, missing cache and symlink tests passed");
    }

    private static void expectResetFailure(Method reset, java.nio.file.Path game, String request, long pid)
            throws Exception {
        try { reset.invoke(null, game, request, pid); }
        catch (InvocationTargetException failure) {
            check(failure.getCause() instanceof java.io.IOException, "unexpected reset failure");
            return;
        }
        throw new AssertionError("unsafe cache reset accepted");
    }

    private static void autoConnectOnlyFromIdleMenus(Class<?> updater) throws Exception {
        var predicate = updater.getDeclaredMethod("autoConnectScreen",
                net.minecraft.client.gui.screens.Screen.class);
        predicate.setAccessible(true);
        var destination = updater.getDeclaredMethod("serverAddress", String.class); destination.setAccessible(true);
        check(destination.invoke(null, "MGengine").equals("ssh.aerosmp.com:25586"), "debug restart target is not Mod_Testing");
        check(destination.invoke(null, "AnotherPlayer").equals("ssh.aerosmp.com:25565"), "bootstrap moved another player");
        var channel = updater.getDeclaredMethod("updateDirectory", String.class); channel.setAccessible(true);
        check(channel.invoke(null, "MGengine").equals("/home/aerosmp/Desktop/ASMP_Voxy/build/libs/debug-clients/MGengine")
                && channel.invoke(null, "AnotherPlayer").equals("/home/aerosmp/Desktop/ASMP_Voxy/build/libs"),
                "bootstrap channel is not scoped");
        check(!(Boolean) predicate.invoke(null, new Object[]{null}), "null screen must not connect");
        Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        var unsafe = (sun.misc.Unsafe) unsafeField.get(null);
        for (String name : new String[]{"TitleScreen", "multiplayer.JoinMultiplayerScreen",
                "DisconnectedScreen", "ConnectScreen", "ReceivingLevelScreen", "ProgressScreen"}) {
            Class<?> screen = Class.forName("net.minecraft.client.gui.screens." + name);
            Object instance = unsafe.allocateInstance(screen); // No window/game initialization.
            boolean allowed = name.equals("TitleScreen") || name.equals("DisconnectedScreen")
                    || name.equals("multiplayer.JoinMultiplayerScreen");
            check((Boolean) predicate.invoke(null, instance) == allowed,
                    "incorrect auto-connect screen: " + name);
        }
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
        var cache = CacheStartupBehaviorTest.completedCache(Files.createTempDirectory("voxy-instrumentation-cache-"), fixture);
        CacheStartupBehaviorTest.awaitInventory((RegionalDiskBudget) field(cache, "budget"));
        int ordinal = fixture.index().ordinal(CacheStartupBehaviorTest.KEY);
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
        session.blockNames.put("minecraft:stone", 15);
        session.biomeNames.put("minecraft:plains", 0);
        session.metadata = cache.metadata;
        session.worldIdentity = CacheStartupBehaviorTest.WORLD;
        session.cacheOpened = true;
        session.metadataWorker.start();
        check(((WorkerDebugTelemetry.Work) session.metadataWorker.debugWork).session == 88,
                "metadata worker captured the uninitialized session identity");
        var worker = session.sectionWorkers[0]; worker.start();
        var work = (WorkerDebugTelemetry.Work) worker.debugWork;
        var demand = session.demands.adopt(new ClientSession.Demand(CacheStartupBehaviorTest.KEY));
        var ticket = demand.ticket(session.id, 0);
        var content = LocalSection.from(fixture.index(), ordinal, fixture.catalog().fingerprint());
        var task = new ClientSession.Session.SectionWorkerTask(ticket, content,
                ClientSession.Session.WorkerSource.CACHE, null, CacheStartupBehaviorTest.MAPPINGS, cache, () -> true);
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
            check(work.copy().counts()[WorkerDebugTelemetry.Stage.CACHE_READ.ordinal()] == 1
                    && work.copy().outcomes()[WorkerDebugTelemetry.Outcome.CACHE_HIT.ordinal()] == 1,
                    "streaming local decode was not counted");
            modelsReady.set(false);
            demand.workLease = worker.assign(task);
            until(() -> work.copy().stage() == WorkerDebugTelemetry.Stage.WAIT_MODELS
                    && session.retainedModelBytes() == 32768L * 8 + 4);
            check(work.copy().jobs() == 1 && worker.resource.claim() == null && worker.resource.acquire() == null,
                    "unready models released or completed their owning worker");
            check(work.copy().counts()[WorkerDebugTelemetry.Stage.CACHE_READ.ordinal()] == 2,
                    "model wait did not retain one ordinary decode");
            modelsReady.set(true); session.processWaitingModels();
            until(() -> work.copy().jobs() == 2);
            completed = worker.resource.claim();
            check(completed.value() instanceof ClientSession.Session.WorkerGeometry, "model readiness did not resume meshing");
            ((ClientSession.Session.WorkerGeometry) completed.value()).geometry().free();
            worker.releaseCompletion(completed.lease());
            check(work.copy().counts()[WorkerDebugTelemetry.Stage.CACHE_READ.ordinal()] == 2
                    && session.retainedModelBytes() == 0, "model readiness repeated decode or retained cells");
            check(work.copy().outcomes()[WorkerDebugTelemetry.Outcome.MODEL_WAIT.ordinal()] == 1
                    && work.copy().repeats() == 1, "model retry identity missing");
            // An invalid compressed body still leaves a terminal diagnostic state and unchanged failure handling.
            worker.assign(new ClientSession.Session.SectionWorkerTask(ticket, content,
                    ClientSession.Session.WorkerSource.NETWORK, new byte[]{1}, CacheStartupBehaviorTest.MAPPINGS, cache, () -> true));
            until(() -> work.copy().jobs() == 3);
            check(work.copy().stage() == WorkerDebugTelemetry.Stage.IDLE
                    && work.copy().outcomes()[WorkerDebugTelemetry.Outcome.FAILURE.ordinal()] == 1, "exception left running stage");
            completed = worker.resource.claim(); worker.releaseCompletion(completed.lease());
            synchronized (budget) {
                worker.assign(new ClientSession.Session.SectionWorkerTask(ticket, content,
                        ClientSession.Session.WorkerSource.NETWORK, fixture.payload(),
                        new RegionalSectionCodec.Mappings(CatalogCodec.decode(fixture.catalog().canonical())), cache, () -> true));
                until(() -> worker.resource.pendingResult() != null);
                completed = worker.resource.claim();
                check(completed.value() instanceof ClientSession.Session.WorkerGeometry,
                        "disk lock prevented mesh handoff");
                ((ClientSession.Session.WorkerGeometry) completed.value()).geometry().free();
                worker.releaseCompletion(completed.lease());
                check(session.retainedSaveBytes() > 0 && worker.resource.acquire() == null,
                        "mesh admission dropped its pending save obligation");
                until(() -> worker.workerThread.getState() == Thread.State.BLOCKED);
                check(work.copy().jobs() == 3 && worker.resource.acquire() == null,
                        "blocked save completed twice or released section ownership");
            }
            until(() -> {
                try { session.drainWorkers(); } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
                session.processMetadata(); return worker.idle();
            });
            check(session.retainedSaveBytes() == 0 && session.lastPersistenceFailure == null,
                    "save acknowledgement leaked its slot or failed");
            check(((RegionalDiskBudget) budget).delete(cache.path(content.region())), "unleased shard not evicted");
            worker.assign(task); until(() -> work.copy().jobs() == 5);
            completed = worker.resource.claim();
            check(completed.value().getClass().getSimpleName().equals("WorkerMiss")
                    && work.copy().outcomes()[WorkerDebugTelemetry.Outcome.CACHE_MISS.ordinal()] == 1, "real cache miss missing");
            worker.releaseCompletion(completed.lease());
            CacheStartupBehaviorTest.storeSection(cache, fixture, ordinal);
            try (var file = new java.io.RandomAccessFile(cache.path(content.region()).toFile(), "rw")) {
                file.seek(CompletedSectionJournal.HEADER_BYTES + CompletedSectionJournal.FRAME_BYTES
                        + CompletedSectionJournal.PAYLOAD_METADATA_BYTES);
                file.write(0); // Actual file corruption, not a bypass of the cache write validator.
            }
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
            modelResume.countDown(); meshResume.countDown(); worker.close(); worker.workerThread.join(5000);
            session.metadataWorker.close(); session.metadataWorker.workerThread.join(5000); cache.close();
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
