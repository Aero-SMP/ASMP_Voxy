package me.cortex.voxy.client.lod;

import me.cortex.voxy.client.VoxyClient;
import me.cortex.voxy.client.core.model.ModelFactory;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.ScreenshotEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.TagsUpdatedEvent;
import org.lwjgl.system.MemoryStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.lwjgl.opengl.GL42C.GL_COMMAND_BARRIER_BIT;
import static org.lwjgl.opengl.GL42C.glMemoryBarrier;
import static org.lwjgl.opengl.GL43C.GL_SHADER_STORAGE_BARRIER_BIT;
import static org.lwjgl.opengl.GL45C.glGetNamedBufferSubData;

/** Regional-pipeline diagnostics compiled only into debug client JARs. */
public final class ClientLodDebug {
    private static final Logger LOGGER = LoggerFactory.getLogger("Voxy Client Debug");
    private static final Path LOG = Path.of("logs", "voxy-client-debug.log").toAbsolutePath();
    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().daemon().name("Voxy client debug log writer").factory());
    private static final long SAMPLE_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(1);
    private static final int UNLIMITED_FRAMERATE = 260;

    private static boolean initialized;
    private static boolean dynamicFpsHandled;
    private static long nextSampleNanos;
    private static int sampledRenderFrame = Integer.MIN_VALUE;
    private static int sampledRenderPasses;
    private static long nextRenderSampleNanos;
    private static volatile int renderFrame = Integer.MIN_VALUE;
    private static volatile int geometrySections;
    private static volatile int conservativeSelected;
    private static volatile int refinedSelected;
    private static volatile int conservativeDraws;
    private static volatile int refinedDraws;
    private static final Path TRANSPORT_HOLD = Path.of(".voxy", "debug-hold-regional-transport");
    private static volatile boolean transportHeld = Files.exists(TRANSPORT_HOLD);

    static boolean connectionAllowed() { return !transportHeld; }

    /** Debug-only persistent hold survives a whole-game restart; Minecraft traffic is untouched. */
    static void holdTransport(boolean held) {
        transportHeld = held;
        WRITER.execute(() -> {
            try {
                Files.createDirectories(TRANSPORT_HOLD.getParent());
                if (held) Files.writeString(TRANSPORT_HOLD, "Debug cache-start test: resume with resume_quic.\n");
                else Files.deleteIfExists(TRANSPORT_HOLD);
                emit("VOXY_CACHE_START transportHeld=" + held + " persisted=true");
            } catch (IOException failure) { LOGGER.error("Could not persist debug transport hold", failure); }
        });
    }

    static void startupEvent(ClientSession.Session session, String event, long bytes) {
        SessionDebugTelemetry.event(session, event, bytes);
    }

    static void admissionReleased(ClientSession.Session session, long meshCompletedNanos) {
        SessionDebugTelemetry.admissionReleased(session, meshCompletedNanos);
    }

    static void captureSession(ClientSession.Session session) {
        SessionDebugTelemetry.capture(session, System.nanoTime(), transportHeld);
    }

    static String sessionSnapshot(ClientSession.Session session) {
        return SessionDebugTelemetry.read(session, System.nanoTime());
    }

    static Object workerCreated(ClientSession.Session session, int slot, Thread thread) {
        return new WorkerDebugTelemetry.Work(session.id, slot, thread.threadId());
    }
    static void workerBegin(Object state, ClientSession.Session.WorkerTask task, WorkerResource.Lease lease) {
        WorkerDebugTelemetry.begin((WorkerDebugTelemetry.Work) state, task, lease);
    }
    static void workerStage(Object state, String stage) { ((WorkerDebugTelemetry.Work) state).stage(stage); }
    static void workerOutcome(Object state, String outcome, long bytes) { ((WorkerDebugTelemetry.Work) state).outcome(outcome, bytes); }
    static void workerEnd(Object state) { ((WorkerDebugTelemetry.Work) state).end(); }
    static void workerClosing(Object state) { ((WorkerDebugTelemetry.Work) state).closing(); }
    static void workerEvidence(String message) { emit(message); }
    public static void shaderBegin(me.cortex.voxy.client.core.VoxyRenderSystem renderer, Object pipeline, long oldResources, long newResources) {
        ShaderDebugTelemetry.begin(renderer, pipeline, oldResources, newResources);
    }
    public static void shaderClassification(me.cortex.voxy.client.core.VoxyRenderSystem renderer,
            me.cortex.voxy.client.core.model.ModelFactory models, java.util.Map<?, ?> before, java.util.Map<?, ?> after, Object pipeline) {
        ShaderDebugTelemetry.classification(renderer, models, before, after, pipeline);
    }
    public static void shaderEnd(me.cortex.voxy.client.core.VoxyRenderSystem renderer, String outcome, String reason) {
        ShaderDebugTelemetry.end(renderer, outcome, reason);
    }
    public static void materialDecision(me.cortex.voxy.client.core.model.ModelFactory models,
            it.unimi.dsi.fastutil.objects.Object2IntMap<net.minecraft.world.level.block.state.BlockState> mapping, String outcome) {
        ShaderDebugTelemetry.material(models, mapping, outcome);
    }

    // Coalesce full reload diffs to one pending immutable artifact; do not queue model objects
    // or retain a growing history. The ordinary log retains reload identities and counts.
    private static final java.util.concurrent.atomic.AtomicReference<String> SHADER_ARTIFACT = new java.util.concurrent.atomic.AtomicReference<>();
    private static final java.util.concurrent.atomic.AtomicBoolean SHADER_WRITE_QUEUED = new java.util.concurrent.atomic.AtomicBoolean();
    static void shaderArtifact(String text) {
        SHADER_ARTIFACT.set(text);
        if (!SHADER_WRITE_QUEUED.compareAndSet(false, true)) return;
        WRITER.execute(ClientLodDebug::writeShaderArtifact);
    }
    private static void writeShaderArtifact() {
        try {
            String text = SHADER_ARTIFACT.getAndSet(null);
            if (text != null) Files.writeString(LOG.resolveSibling("voxy-shader-reload.log"), text,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException failure) { LOGGER.error("Could not write shader reload evidence", failure); }
        finally {
            SHADER_WRITE_QUEUED.set(false);
            if (SHADER_ARTIFACT.get() != null && SHADER_WRITE_QUEUED.compareAndSet(false, true)) {
                WRITER.execute(ClientLodDebug::writeShaderArtifact);
            }
        }
    }

    static {
        String version = "Voxy version " + VoxyClient.MOD_VERSION;
        LOGGER.info(version);
        WRITER.execute(() -> initializeLog(version));
        ClientAutoUpdater.start();
    }

    private ClientLodDebug() {}

    static void init(IEventBus modBus) {
        if (initialized) return;
        initialized = true;
        LiveClientTestHarness.register(modBus);
        NeoForge.EVENT_BUS.addListener(ClientLodDebug::waterTagsUpdated);
        NeoForge.EVENT_BUS.addListener(ClientLodDebug::forceFullSpeed);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, RenderFrameEvent.Post.class,
                LiveClientTestHarness::renderFrame);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, ScreenshotEvent.class,
                ClientLodDebug::uploadScreenshot);
    }

    /** Observe the production predicate after real registry tags are installed, not mocked tags. */
    private static void waterTagsUpdated(TagsUpdatedEvent event) {
        var water = Blocks.WATER.defaultBlockState();
        var slab = Blocks.OAK_SLAB.defaultBlockState();
        boolean source = ModelFactory.isWaterState(water);
        boolean flowing = ModelFactory.isWaterState(water.setValue(LiquidBlock.LEVEL, 1));
        boolean logged = ModelFactory.isWaterState(slab.setValue(BlockStateProperties.WATERLOGGED, true));
        boolean dry = ModelFactory.isWaterState(slab);
        boolean lava = ModelFactory.isWaterState(Blocks.LAVA.defaultBlockState());
        boolean glass = ModelFactory.isWaterState(Blocks.GLASS.defaultBlockState());
        boolean stone = ModelFactory.isWaterState(Blocks.STONE.defaultBlockState());
        updaterEvent("state=BOUNDARY_WATER_TAG_CHECK result="
                + (source && flowing && logged && !dry && !lava && !glass && !stone ? "PASS" : "FAIL")
                + " source=" + source + " flowing=" + flowing + " waterlogged=" + logged
                + " dry=" + dry + " lava=" + lava + " glass=" + glass + " stone=" + stone);
    }

    private static void uploadScreenshot(ScreenshotEvent event) {
        if (!event.isCanceled()) {
            if (!LiveClientTestHarness.claimScreenshot(event.getScreenshotFile().toPath())) {
                ClientAutoUpdater.queueScreenshot(event.getScreenshotFile().toPath());
            }
        }
    }

    private static void forceFullSpeed(RenderFrameEvent.Pre event) {
        disableDynamicFps();
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.options.pauseOnLostFocus = false;
        if (minecraft.options.framerateLimit().get() != UNLIMITED_FRAMERATE) {
            minecraft.options.framerateLimit().set(UNLIMITED_FRAMERATE);
        }
        if (minecraft.options.enableVsync().get()) minecraft.options.enableVsync().set(false);
        minecraft.getWindow().setFramerateLimit(UNLIMITED_FRAMERATE);
    }

    private static void disableDynamicFps() {
        if (dynamicFpsHandled) return;
        dynamicFpsHandled = true;
        try {
            Class<?> configClass = Class.forName("dynamic_fps.impl.config.DynamicFPSConfig");
            Object config = configClass.getField("INSTANCE").get(null);
            configClass.getMethod("setEnabled", boolean.class).invoke(config, false);
            Class<?> modClass = Class.forName("dynamic_fps.impl.DynamicFPSMod");
            modClass.getMethod("onStatusChanged", boolean.class).invoke(null, true);
            emit("VOXY_DEBUG state=FULL_SPEED dynamicFps=DISABLED");
        } catch (ClassNotFoundException ignored) {
            emit("VOXY_DEBUG state=FULL_SPEED dynamicFps=ABSENT");
        } catch (ReflectiveOperationException | RuntimeException failure) {
            emit("VOXY_DEBUG state=FULL_SPEED dynamicFps=FAILED type="
                    + failure.getClass().getSimpleName() + " message="
                    + oneLine(failure.getMessage()));
        }
    }

    static void tick() {
        ClientAutoUpdater.tick();
        LiveClientTestHarness.tick();
        long now = System.nanoTime();
        if (now < nextSampleNanos) return;
        nextSampleNanos = now + SAMPLE_INTERVAL_NANOS;
        emit("VOXY_PIPELINE " + ClientSession.debugSnapshot() + ' ' + renderSnapshot());
    }

    /** Samples existing renderer counters once per second for both HZB passes. */
    public static void captureRender(int frameId, int sectionCount,
                                     int renderListBuffer, int drawCountBuffer) {
        long now = System.nanoTime();
        if (frameId != sampledRenderFrame) {
            if (now < nextRenderSampleNanos) return;
            sampledRenderFrame = frameId;
            sampledRenderPasses = 0;
            nextRenderSampleNanos = now + SAMPLE_INTERVAL_NANOS;
        }
        if (sampledRenderPasses >= 2) return;
        int pass = sampledRenderPasses++;
        int selected = 0;
        int draws = 0;
        if (sectionCount > 0) {
            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT | GL_COMMAND_BARRIER_BIT);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer renderList = stack.mallocInt(1);
                IntBuffer counts = stack.mallocInt(6);
                glGetNamedBufferSubData(renderListBuffer, 0, renderList);
                glGetNamedBufferSubData(drawCountBuffer, 0, counts);
                selected = renderList.get(0);
                draws = counts.get(3) + counts.get(4) + counts.get(5);
            }
        }
        renderFrame = frameId;
        geometrySections = sectionCount;
        if (pass == 0) {
            conservativeSelected = selected;
            conservativeDraws = draws;
        } else {
            refinedSelected = selected;
            refinedDraws = draws;
        }
    }

    private static String renderSnapshot() {
        return "renderFrame=" + renderFrame + " geometrySections=" + geometrySections
                + " selected=" + conservativeSelected + '/' + refinedSelected
                + " draws=" + conservativeDraws + '/' + refinedDraws;
    }

    static RenderCounters renderCounters() {
        return new RenderCounters(renderFrame, geometrySections,
                conservativeSelected, refinedSelected, conservativeDraws, refinedDraws,
                Math.max(0, System.nanoTime() - nextRenderSampleNanos
                        + SAMPLE_INTERVAL_NANOS));
    }

    record RenderCounters(int frame, int geometrySections, int conservativeSelected,
                          int refinedSelected, int conservativeDraws, int refinedDraws,
                          long ageNanos) {}

    private static void initializeLog(String version) {
        try {
            Files.createDirectories(LOG.getParent());
            Files.writeString(LOG, version + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException failure) {
            LOGGER.error("Could not initialize Voxy client debug log", failure);
        }
    }

    private static void emit(String message) {
        LOGGER.info(message);
        WRITER.execute(() -> {
            try {
                Files.writeString(LOG, Instant.now() + " " + message + System.lineSeparator(),
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException failure) {
                LOGGER.error("Could not write Voxy client debug log", failure);
            }
        });
    }

    static void updaterEvent(String message) { emit("VOXY_UPDATER " + message); }

    static boolean snapshotLog(Path destination) throws IOException, InterruptedException {
        try {
            return WRITER.submit(() -> {
                if (!Files.isRegularFile(LOG)) return false;
                Files.copy(LOG, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                return true;
            }).get(10, TimeUnit.SECONDS);
        } catch (TimeoutException failure) {
            throw new IOException("timed out waiting for Voxy debug-log snapshot", failure);
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof IOException io) throw io;
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IOException("Voxy debug-log snapshot failed", cause);
        }
    }

    private static String oneLine(String value) {
        return String.valueOf(value).replace('\n', ' ').replace('\r', ' ');
    }
}
