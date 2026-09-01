package me.cortex.voxy.client.lod;

import me.cortex.voxy.network.DebugPayload;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.StringJoiner;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/** Diagnostics compiled only into the debug client JAR. */
final class ClientLodDebug {
    private static final Logger LOGGER = LoggerFactory.getLogger("Voxy Client Debug");
    private static final String VERSION =
            ClientLodDebug.class.getPackage().getImplementationVersion();
    private static final Path LOG = Path.of("logs", "voxy-client-debug.log").toAbsolutePath();
    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().daemon().name("Voxy client debug log writer").factory());
    private static final int MAX_OBJECT_KIND = 16;
    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static final AtomicLong FRAMES = new AtomicLong();
    private static final AtomicLong FRAME_BYTES = new AtomicLong();
    private static final AtomicLong BRIDGE_IN_PACKETS = new AtomicLong();
    private static final AtomicLong BRIDGE_IN_BYTES = new AtomicLong();
    private static final AtomicLong BRIDGE_OUT_PACKETS = new AtomicLong();
    private static final AtomicLong BRIDGE_OUT_BYTES = new AtomicLong();
    private static final AtomicLong OBJECTS = new AtomicLong();
    private static final AtomicLong OBJECT_BYTES = new AtomicLong();
    private static final AtomicLong CACHE_HITS = new AtomicLong();
    private static final AtomicLongArray OBJECT_KINDS =
            new AtomicLongArray(MAX_OBJECT_KIND);
    private static final AtomicLong CREDIT_BYTES = new AtomicLong();
    private static final AtomicLong SELECTIONS = new AtomicLong();
    private static final AtomicLong FRONTIER_NODES = new AtomicLong();
    private static final AtomicLong REQUEST_NODES = new AtomicLong();
    private static final AtomicLong COMPLETE_SELECTIONS = new AtomicLong();
    private static final AtomicLong ACTIVATION_CANDIDATES = new AtomicLong();
    private static final AtomicLong ACTIVATION_NO_BINDING = new AtomicLong();
    private static final AtomicLong ACTIVATION_EMPTY_CUT = new AtomicLong();
    private static final AtomicLong ACTIVATION_MISSING = new AtomicLong();
    private static final AtomicLong ACTIVATION_MISSING_NEIGHBORS = new AtomicLong();
    private static final AtomicLong ACTIVATION_PRESSURE = new AtomicLong();
    private static final AtomicLong COMPILED = new AtomicLong();
    private static final AtomicLong QUEUED = new AtomicLong();
    private static final AtomicLong VISIBLE = new AtomicLong();
    private static final AtomicLong RETIRED = new AtomicLong();
    private static final AtomicLong CAMERA_DOMAIN_RESPONSES = new AtomicLong();

    private static long lastSample;
    private static final AtomicBoolean ANNOUNCED = new AtomicBoolean();
    private static volatile String transport = "closed";

    static {
        WRITER.execute(() -> {
            try {
                Files.createDirectories(LOG.getParent());
                Files.writeString(LOG, "Voxy version " + VERSION + System.lineSeparator(),
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            } catch (IOException exception) {
                LOGGER.error("Could not initialize Voxy client debug log", exception);
            }
        });
    }

    private ClientLodDebug() {}

    static void register(PayloadRegistrar registrar) {
        registrar.playToServer(DebugPayload.TYPE, DebugPayload.CODEC,
                (payload, context) -> {});
    }

    static void tick() {
        long now = System.nanoTime();
        if (lastSample != 0 && now - lastSample < 1_000_000_000L) return;
        lastSample = now;
        var connection = Minecraft.getInstance().getConnection();
        if (connection == null || !connection.hasChannel(DebugPayload.TYPE)) return;
        send("sample"
                + " transport=" + transport
                + " bridgeInPackets=" + BRIDGE_IN_PACKETS.getAndSet(0)
                + " bridgeInKiB=" + kib(BRIDGE_IN_BYTES.getAndSet(0))
                + " bridgeOutPackets=" + BRIDGE_OUT_PACKETS.getAndSet(0)
                + " bridgeOutKiB=" + kib(BRIDGE_OUT_BYTES.getAndSet(0))
                + " frames=" + FRAMES.getAndSet(0)
                + " frameKiB=" + kib(FRAME_BYTES.getAndSet(0))
                + " objects=" + OBJECTS.getAndSet(0)
                + " objectKiB=" + kib(OBJECT_BYTES.getAndSet(0))
                + " cacheHits=" + CACHE_HITS.getAndSet(0)
                + " objectKinds=" + objectKinds()
                + " creditKiB=" + kib(CREDIT_BYTES.getAndSet(0))
                + " selections=" + SELECTIONS.getAndSet(0)
                + " frontierNodes=" + FRONTIER_NODES.getAndSet(0)
                + " requestNodes=" + REQUEST_NODES.getAndSet(0)
                + " completeSelections=" + COMPLETE_SELECTIONS.getAndSet(0)
                + " activationCandidates=" + ACTIVATION_CANDIDATES.getAndSet(0)
                + " activationNoBinding=" + ACTIVATION_NO_BINDING.getAndSet(0)
                + " activationEmptyCut=" + ACTIVATION_EMPTY_CUT.getAndSet(0)
                + " activationMissing=" + ACTIVATION_MISSING.getAndSet(0)
                + " activationMissingNeighbors="
                + ACTIVATION_MISSING_NEIGHBORS.getAndSet(0)
                + " activationPressure=" + ACTIVATION_PRESSURE.getAndSet(0)
                + " memoryUsedMiB=" + ClientSession.debugMemoryUsedMiB()
                + " memoryAvailableMiB=" + ClientSession.debugMemoryAvailableMiB()
                + " compiled=" + COMPILED.getAndSet(0)
                + " queued=" + QUEUED.getAndSet(0)
                + " visible=" + VISIBLE.getAndSet(0)
                + " retired=" + RETIRED.getAndSet(0)
                + " cameraDomainResponses="
                + CAMERA_DOMAIN_RESPONSES.getAndSet(0));
    }

    static long timer() {
        return System.nanoTime();
    }

    static void transportResponse(long startedNanos, byte mode) {
        event("transport-response elapsedMs=" + millis(System.nanoTime() - startedNanos)
                + " mode=" + mode);
    }

    static void bridgeInputOverflow(int queued) {
        event("transport-overflow queued=" + queued);
    }

    static void bridgeIn(int bytes) {
        BRIDGE_IN_PACKETS.incrementAndGet();
        BRIDGE_IN_BYTES.addAndGet(bytes);
    }

    static void bridgeOut(int bytes) {
        BRIDGE_OUT_PACKETS.incrementAndGet();
        BRIDGE_OUT_BYTES.addAndGet(bytes);
    }

    static void sessionStarted(long session, String dimension) {
        ANNOUNCED.set(false);
        lastSample = 0;
        transport = "connecting";
        resetCounters();
        event("session-start session=" + session + " dimension=" + dimension);
    }

    static void sessionFailed(Throwable failure) {
        transport = "closed";
        event("session-failure type=" + failure.getClass().getSimpleName()
                + " message=" + String.valueOf(failure.getMessage()));
    }

    static void transportOpened(boolean direct, String description) {
        resetCounters();
        transport = direct ? "direct" : "minecraft";
        event("transport-open direct=" + direct + " description=" + description);
    }

    static void frame(int type, int bytes) {
        FRAMES.incrementAndGet();
        FRAME_BYTES.addAndGet(bytes);
        if (type == FrameCodec.S_CAMERA_DOMAIN) {
            CAMERA_DOMAIN_RESPONSES.incrementAndGet();
        }
    }

    static void hello(long serverInstance) {
        event("hello serverInstance=" + serverInstance);
    }

    static void credit(long bytes) {
        CREDIT_BYTES.addAndGet(bytes);
    }

    static void rootAnnounced(long generation) {
        event("root-announced generation=" + generation);
    }

    static void objectDecoded(int kind, int compressedBytes, boolean cacheHit) {
        OBJECTS.incrementAndGet();
        OBJECT_BYTES.addAndGet(compressedBytes);
        if (cacheHit) CACHE_HITS.incrementAndGet();
        if (kind >= 0 && kind < MAX_OBJECT_KIND) OBJECT_KINDS.incrementAndGet(kind);
    }

    static void selection(long generation, long snapshot, int frontier, int requests,
                          boolean complete) {
        SELECTIONS.incrementAndGet();
        FRONTIER_NODES.addAndGet(frontier);
        REQUEST_NODES.addAndGet(requests);
        if (complete) COMPLETE_SELECTIONS.incrementAndGet();
    }

    static void activationPass(int candidates) {
        ACTIVATION_CANDIDATES.addAndGet(candidates);
    }

    static void activationNoBinding() {
        ACTIVATION_NO_BINDING.incrementAndGet();
    }

    static void activationEmptyCut() {
        ACTIVATION_EMPTY_CUT.incrementAndGet();
    }

    static void activationMissing(int requestable, int neighbors) {
        ACTIVATION_MISSING.addAndGet(requestable);
        ACTIVATION_MISSING_NEIGHBORS.addAndGet(neighbors);
    }

    static void activationPressure() {
        ACTIVATION_PRESSURE.incrementAndGet();
    }

    static void activationCompiled(long key, long generation) {
        COMPILED.incrementAndGet();
    }

    static void activationQueued(long key, long generation) {
        QUEUED.incrementAndGet();
    }

    static void activationVisible(long key, long generation) {
        VISIBLE.incrementAndGet();
    }

    static void activationRetired(long key, long generation) {
        RETIRED.incrementAndGet();
    }

    static void rootActivated(long generation) {
        event("root-activated generation=" + generation);
    }

    static void failure(String reason) {
        event("failure reason=" + String.valueOf(reason));
    }

    private static String objectKinds() {
        StringJoiner kinds = new StringJoiner(",", "[", "]");
        for (int kind = 0; kind < MAX_OBJECT_KIND; kind++) {
            long count = OBJECT_KINDS.getAndSet(kind, 0);
            if (count != 0) kinds.add(kind + ":" + count);
        }
        return kinds.toString();
    }

    private static void resetCounters() {
        FRAMES.set(0);
        FRAME_BYTES.set(0);
        BRIDGE_IN_PACKETS.set(0);
        BRIDGE_IN_BYTES.set(0);
        BRIDGE_OUT_PACKETS.set(0);
        BRIDGE_OUT_BYTES.set(0);
        OBJECTS.set(0);
        OBJECT_BYTES.set(0);
        CACHE_HITS.set(0);
        for (int kind = 0; kind < MAX_OBJECT_KIND; kind++) OBJECT_KINDS.set(kind, 0);
        CREDIT_BYTES.set(0);
        SELECTIONS.set(0);
        FRONTIER_NODES.set(0);
        REQUEST_NODES.set(0);
        COMPLETE_SELECTIONS.set(0);
        ACTIVATION_CANDIDATES.set(0);
        ACTIVATION_NO_BINDING.set(0);
        ACTIVATION_EMPTY_CUT.set(0);
        ACTIVATION_MISSING.set(0);
        ACTIVATION_MISSING_NEIGHBORS.set(0);
        ACTIVATION_PRESSURE.set(0);
        COMPILED.set(0);
        QUEUED.set(0);
        VISIBLE.set(0);
        RETIRED.set(0);
        CAMERA_DOMAIN_RESPONSES.set(0);
    }

    private static void event(String message) {
        send("event " + message);
    }

    private static void send(String message) {
        WRITER.execute(() -> {
            try {
                Files.writeString(LOG, Instant.now() + " " + message + System.lineSeparator(),
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException exception) {
                LOGGER.error("Could not write Voxy client debug log", exception);
            }
        });
        var connection = Minecraft.getInstance().getConnection();
        if (connection == null || !connection.hasChannel(DebugPayload.TYPE)) return;
        if (ANNOUNCED.compareAndSet(false, true)) {
            sendRaw(connection, "Voxy version " + VERSION);
        }
        sendRaw(connection, message);
    }

    private static void sendRaw(net.minecraft.client.multiplayer.ClientPacketListener connection,
                                String message) {
        String safe = message.replace('\n', ' ').replace('\r', ' ');
        if (safe.length() > DebugPayload.MAX_MESSAGE_LENGTH) {
            safe = safe.substring(0, DebugPayload.MAX_MESSAGE_LENGTH);
        }
        connection.send(new DebugPayload(SEQUENCE.incrementAndGet(), safe));
    }

    private static long millis(long nanos) {
        return (nanos + 500_000L) / 1_000_000L;
    }

    private static long kib(long bytes) {
        return (bytes + 512L) / 1024L;
    }
}
