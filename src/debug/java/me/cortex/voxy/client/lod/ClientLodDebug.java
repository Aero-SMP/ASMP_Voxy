package me.cortex.voxy.client.lod;

import me.cortex.voxy.client.VoxyClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.lwjgl.system.MemoryStack;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.nio.IntBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.lwjgl.opengl.GL42C.GL_COMMAND_BARRIER_BIT;
import static org.lwjgl.opengl.GL42C.glMemoryBarrier;
import static org.lwjgl.opengl.GL43C.GL_SHADER_STORAGE_BARRIER_BIT;
import static org.lwjgl.opengl.GL45C.glGetNamedBufferSubData;

/** Pipeline diagnostics compiled only into the debug client JAR. */
public final class ClientLodDebug {
    private static final Logger LOGGER = LoggerFactory.getLogger("Voxy Client Debug");
    private static final String VERSION = VoxyClient.MOD_VERSION;
    private static final Path LOG = Path.of("logs", "voxy-client-debug.log").toAbsolutePath();
    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().daemon().name("Voxy client debug log writer").factory());
    private static final long SAMPLE_INTERVAL_NANOS = 1_000_000_000L;
    private static final AtomicReference<String> SNAPSHOT = new AtomicReference<>(
            "blocker=CONNECTING snapshot=not-ready");
    private static final AtomicLong SNAPSHOT_NANOS = new AtomicLong();

    private static long nextSampleNanos;
    private static int candidates;
    private static int busy;
    private static int missingBinding;
    private static int noCompatibleContent;
    private static int missingContent;
    private static int missingNeighbors;
    private static int modelsPending;
    private static int pendingModelId = -1;
    private static int stageBlocked;
    private static int pinBlocked;
    private static int workerSaturated;
    private static int alreadyActive;
    private static int submitted;
    private static ManifestCodec.SpatialNode sampleNode;
    private static WireMessage.Hash256 sampleHash;
    private static final long RENDER_SAMPLE_INTERVAL_NANOS = 1_000_000_000L;
    private static int sampledRenderFrame = Integer.MIN_VALUE;
    private static int sampledRenderPasses;
    private static long nextRenderSampleNanos;
    private static volatile int renderFrame = Integer.MIN_VALUE;
    private static volatile int geometrySections;
    private static volatile int conservativeSelected;
    private static volatile int refinedSelected;
    private static volatile int conservativeOpaque;
    private static volatile int conservativeTranslucent;
    private static volatile int conservativeTemporal;
    private static volatile int refinedOpaque;
    private static volatile int refinedTranslucent;
    private static volatile int refinedTemporal;

    static {
        String version = "Voxy version " + VERSION;
        LOGGER.info(version);
        WRITER.execute(() -> initializeLog(version));
        ClientAutoUpdater.start();
    }

    private ClientLodDebug() {}

    static boolean diagnosticsEnabled() { return true; }

    static void tick() {
        ClientAutoUpdater.tick();
        long now = System.nanoTime();
        if (now < nextSampleNanos) return;
        nextSampleNanos = now + SAMPLE_INTERVAL_NANOS;
        try {
            emit("VOXY_PIPELINE " + ClientSession.debugSnapshot());
        } catch (RuntimeException failure) {
            emit("VOXY_PIPELINE state=DEBUG_SNAPSHOT_FAILED type="
                    + failure.getClass().getSimpleName() + " message="
                    + oneLine(failure.getMessage()));
        }
    }

    static void sessionStarted(long session, String dimension) {
        nextSampleNanos = 0;
        emit("VOXY_SESSION state=START session=" + session
                + " dimension=" + oneLine(dimension));
    }

    static void sessionFailed(Throwable failure) {
        emit("VOXY_SESSION state=FAILED type=" + failure.getClass().getSimpleName()
                + " message=" + oneLine(failure.getMessage()));
    }

    static String latestSnapshot() {
        long captured = SNAPSHOT_NANOS.get();
        long ageMillis = captured == 0 ? -1
                : TimeUnit.NANOSECONDS.toMillis(Math.max(0, System.nanoTime() - captured));
        return "sampleAgeMs=" + ageMillis + ' ' + SNAPSHOT.get();
    }

    static void snapshotCaptured(String snapshot) {
        SNAPSHOT.set(snapshot);
        SNAPSHOT_NANOS.set(System.nanoTime());
    }

    static void activationPass(int candidateCount, int busyCount, int missingBindingCount,
                               int noCompatibleContentCount, int missingContentCount,
                               int missingNeighborCount, int modelsPendingCount,
                               int modelId, int stageBlockedCount, int pinBlockedCount,
                               int workerSaturatedCount, int alreadyActiveCount,
                               int submittedCount, ManifestCodec.SpatialNode node,
                               WireMessage.Hash256 hash) {
        candidates = candidateCount;
        busy = busyCount;
        missingBinding = missingBindingCount;
        noCompatibleContent = noCompatibleContentCount;
        missingContent = missingContentCount;
        missingNeighbors = missingNeighborCount;
        modelsPending = modelsPendingCount;
        pendingModelId = modelId;
        stageBlocked = stageBlockedCount;
        pinBlocked = pinBlockedCount;
        workerSaturated = workerSaturatedCount;
        alreadyActive = alreadyActiveCount;
        submitted = submittedCount;
        sampleNode = node;
        sampleHash = hash;
    }

    static String activationSummary() {
        return "candidates=" + candidates
                + ",busy=" + busy
                + ",missingBinding=" + missingBinding
                + ",noCompatibleContent=" + noCompatibleContent
                + ",missingContent=" + missingContent
                + ",missingNeighbors=" + missingNeighbors
                + ",modelsPending=" + modelsPending
                + ",pendingModelId=" + pendingModelId
                + ",stageBlocked=" + stageBlocked
                + ",pinBlocked=" + pinBlocked
                + ",workerSaturated=" + workerSaturated
                + ",alreadyActive=" + alreadyActive
                + ",submitted=" + submitted
                + ",sampleNode=" + String.valueOf(sampleNode)
                + ",sampleHash=" + String.valueOf(sampleHash);
    }

    static String activationBlocker() {
        if (missingBinding > 0) return "MISSING_NODE_DESCRIPTOR";
        if (noCompatibleContent > 0) return "NO_COMPATIBLE_SELECTED_CONTENT";
        if (missingContent > 0) return "WAITING_FOR_CONTENT_OBJECTS";
        if (missingNeighbors > 0) return "WAITING_FOR_NEIGHBOR_DEPENDENCIES";
        if (modelsPending > 0) return "WAITING_FOR_BLOCK_MODELS";
        if (pinBlocked > 0) return "RESIDENCY_PIN_FAILED";
        if (stageBlocked > 0) return "ACTIVATION_STAGE_BLOCKED";
        if (workerSaturated > 0) return "MESH_WORKER_BUSY";
        if (busy > 0) return "ACTIVATION_BUSY";
        return null;
    }

    /** Samples existing renderer counters at most once per second, twice for the two HZB passes. */
    public static void captureRender(int frameId, int sectionCount,
                                     int renderListBuffer, int drawCountBuffer) {
        long now = System.nanoTime();
        if (frameId != sampledRenderFrame) {
            if (now < nextRenderSampleNanos) return;
            sampledRenderFrame = frameId;
            sampledRenderPasses = 0;
            nextRenderSampleNanos = now + RENDER_SAMPLE_INTERVAL_NANOS;
        }
        if (frameId != sampledRenderFrame || sampledRenderPasses >= 2) return;
        int pass = sampledRenderPasses++;
        int selected = 0;
        int opaque = 0;
        int translucent = 0;
        int temporal = 0;
        if (sectionCount > 0) {
            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT | GL_COMMAND_BARRIER_BIT);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer renderList = stack.mallocInt(1);
                IntBuffer counts = stack.mallocInt(6);
                glGetNamedBufferSubData(renderListBuffer, 0, renderList);
                glGetNamedBufferSubData(drawCountBuffer, 0, counts);
                selected = renderList.get(0);
                opaque = counts.get(3);
                translucent = counts.get(4);
                temporal = counts.get(5);
            }
        }
        renderFrame = frameId;
        geometrySections = sectionCount;
        if (pass == 0) {
            conservativeSelected = selected;
            conservativeOpaque = opaque;
            conservativeTranslucent = translucent;
            conservativeTemporal = temporal;
        } else {
            refinedSelected = selected;
            refinedOpaque = opaque;
            refinedTranslucent = translucent;
            refinedTemporal = temporal;
        }
    }

    static String renderSummary() {
        return "frame=" + renderFrame
                + ",geometrySections=" + geometrySections
                + ",conservativeSelected=" + conservativeSelected
                + ",refinedSelected=" + refinedSelected
                + ",conservativeDraws=" + conservativeOpaque + '/'
                + conservativeTranslucent + '/' + conservativeTemporal
                + ",refinedDraws=" + refinedOpaque + '/'
                + refinedTranslucent + '/' + refinedTemporal;
    }

    static String renderBlocker(int activePublications) {
        if (activePublications == 0) return null;
        if (renderFrame == Integer.MIN_VALUE) return "WAITING_FOR_RENDER_COUNTER_SAMPLE";
        if (geometrySections == 0) return "ACTIVE_PUBLICATION_HAS_NO_GEOMETRY_ALLOCATION";
        if (conservativeSelected == 0 && refinedSelected == 0) {
            return "GPU_SELECTED_ZERO_ACTIVE_SECTIONS";
        }
        if (conservativeOpaque + conservativeTranslucent + conservativeTemporal
                + refinedOpaque + refinedTranslucent + refinedTemporal == 0) {
            return "GPU_GENERATED_ZERO_DRAW_COMMANDS";
        }
        return null;
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

    static void updaterEvent(String message) {
        emit("VOXY_UPDATER " + message);
    }

    private static String oneLine(String value) {
        return String.valueOf(value).replace('\n', ' ').replace('\r', ' ');
    }
}
