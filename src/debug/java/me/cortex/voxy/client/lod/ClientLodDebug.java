package me.cortex.voxy.client.lod;

import me.cortex.voxy.client.VoxyClient;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;
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

    static {
        String version = "Voxy version " + VoxyClient.MOD_VERSION;
        LOGGER.info(version);
        WRITER.execute(() -> initializeLog(version));
        ClientAutoUpdater.start();
    }

    private ClientLodDebug() {}

    static void init() {
        if (initialized) return;
        initialized = true;
        NeoForge.EVENT_BUS.addListener(ClientLodDebug::forceFullSpeed);
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
