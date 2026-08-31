package me.cortex.voxy.bridge;

import me.cortex.voxy.network.DebugPayload;
import me.cortex.voxy.network.TransportPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Server-side telemetry compiled only into the debug bridge JAR. */
final class ServerDebug {
    private static final Logger LOGGER = LoggerFactory.getLogger("Voxy Minecraft Bridge");
    private static final Path LOG = Path.of("logs", "voxy-debug.log").toAbsolutePath();
    private static final ConcurrentHashMap<UUID, Stats> SESSIONS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, ClientHeartbeat> CLIENTS = new ConcurrentHashMap<>();
    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(action -> {
        Thread thread = new Thread(action, "Voxy debug log writer");
        thread.setDaemon(true);
        return thread;
    });
    private static final ScheduledExecutorService WATCHER =
            Executors.newSingleThreadScheduledExecutor(action -> {
                Thread thread = new Thread(action, "Voxy debug client watchdog");
                thread.setDaemon(true);
                return thread;
            });
    private static volatile boolean serverRunning;

    static {
        WATCHER.scheduleAtFixedRate(ServerDebug::checkClients, 1, 1, TimeUnit.SECONDS);
    }

    private ServerDebug() {}

    static void register(PayloadRegistrar registrar) {
        registrar.playBidirectional(DebugPayload.TYPE, DebugPayload.CODEC,
                (payload, context) -> receive((ServerPlayer) context.player(), payload));
    }

    static void serverStart(byte transport) {
        CLIENTS.clear();
        serverRunning = true;
        append("server-start transport="
                + (transport == TransportPayload.MINECRAFT ? "minecraft" : "direct"));
    }

    static void serverStop() {
        serverRunning = false;
        CLIENTS.clear();
    }

    static void playerLogout(ServerPlayer player) {
        CLIENTS.remove(player.getUUID());
    }

    static void sessionOpened(ServerPlayer player, Object session) {
        SESSIONS.put(player.getUUID(), new Stats(session));
    }

    static void sessionClosed(ServerPlayer player, Object session) {
        Stats stats = current(player.getUUID(), session);
        if (stats != null) SESSIONS.remove(player.getUUID(), stats);
    }

    static void fromClient(ServerPlayer player, Object session, int bytes) {
        Stats stats = current(player.getUUID(), session);
        if (stats == null) return;
        stats.fromClientBytes.addAndGet(bytes);
        stats.fromClientPackets.incrementAndGet();
    }

    static void dequeued(ServerPlayer player, Object session) {
        Stats stats = current(player.getUUID(), session);
        if (stats != null) stats.dequeuedPackets.incrementAndGet();
    }

    static void toClient(ServerPlayer player, Object session, int bytes) {
        Stats stats = current(player.getUUID(), session);
        if (stats == null) return;
        stats.toClientBytes.addAndGet(bytes);
        stats.toClientPackets.incrementAndGet();
    }

    private static Stats current(UUID player, Object session) {
        Stats stats = SESSIONS.get(player);
        return stats != null && stats.session == session ? stats : null;
    }

    private static void receive(ServerPlayer player, DebugPayload payload) {
        if (payload.message().isEmpty()) return;
        long now = System.nanoTime();
        ClientHeartbeat heartbeat = CLIENTS.computeIfAbsent(player.getUUID(),
                ignored -> new ClientHeartbeat(player, now));
        long silenceMillis = TimeUnit.NANOSECONDS.toMillis(now - heartbeat.lastSeenNanos);
        heartbeat.lastSeenNanos = now;
        heartbeat.lastSequence = payload.sequence();
        heartbeat.nextWarningNanos = now + TimeUnit.SECONDS.toNanos(3);
        Stats stats = SESSIONS.get(player.getUUID());
        var channel = player.connection.getConnection().channel();
        append("player=" + player.getScoreboardName()
                + " uuid=" + player.getUUID()
                + " sequence=" + payload.sequence()
                + " clientSilenceMs=" + silenceMillis
                + " mcLatencyMs=" + player.connection.latency()
                + " mcWritable=" + channel.isWritable()
                + " mcBytesBeforeWritable=" + channel.bytesBeforeWritable()
                + " mcBytesBeforeUnwritable=" + channel.bytesBeforeUnwritable()
                + (stats == null ? " bridge=closed" : stats.describe())
                + " client={" + payload.message().replace('\n', ' ').replace('\r', ' ') + '}');
        if (player.connection.isAcceptingMessages()
                && player.connection.hasChannel(DebugPayload.TYPE)) {
            player.connection.send(new DebugPayload(payload.sequence(), payload.sentNanos(), ""));
        }
    }

    private static void checkClients() {
        if (!serverRunning) return;
        long now = System.nanoTime();
        for (var entry : CLIENTS.entrySet()) {
            ClientHeartbeat heartbeat = entry.getValue();
            if (!heartbeat.player.connection.isAcceptingMessages()) {
                CLIENTS.remove(entry.getKey(), heartbeat);
                continue;
            }
            if (now < heartbeat.nextWarningNanos) continue;
            long silenceMillis = TimeUnit.NANOSECONDS.toMillis(now - heartbeat.lastSeenNanos);
            append("client-telemetry-stalled player=" + heartbeat.player.getScoreboardName()
                    + " uuid=" + entry.getKey()
                    + " lastSequence=" + heartbeat.lastSequence
                    + " silenceMs=" + silenceMillis);
            heartbeat.nextWarningNanos = now + TimeUnit.SECONDS.toNanos(5);
        }
    }

    private static void append(String message) {
        WRITER.execute(() -> {
            try {
                Files.createDirectories(LOG.getParent());
                Files.writeString(LOG, Instant.now() + " " + message + System.lineSeparator(),
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException exception) {
                LOGGER.error("Could not write Voxy debug log", exception);
            }
        });
    }

    private static final class Stats {
        private final Object session;
        private final AtomicLong toClientBytes = new AtomicLong();
        private final AtomicLong toClientPackets = new AtomicLong();
        private final AtomicLong fromClientBytes = new AtomicLong();
        private final AtomicLong fromClientPackets = new AtomicLong();
        private final AtomicLong dequeuedPackets = new AtomicLong();

        private Stats(Object session) {
            this.session = session;
        }

        private String describe() {
            long queued = Math.max(0, this.fromClientPackets.get() - this.dequeuedPackets.get());
            return " bridgeOpen=true"
                    + " bridgeQueue=" + queued
                    + " bridgeToClientPackets=" + this.toClientPackets.get()
                    + " bridgeToClientBytes=" + this.toClientBytes.get()
                    + " bridgeFromClientPackets=" + this.fromClientPackets.get()
                    + " bridgeFromClientBytes=" + this.fromClientBytes.get();
        }
    }

    private static final class ClientHeartbeat {
        private final ServerPlayer player;
        private volatile long lastSeenNanos;
        private volatile long nextWarningNanos;
        private volatile int lastSequence;

        private ClientHeartbeat(ServerPlayer player, long now) {
            this.player = player;
            this.lastSeenNanos = now;
            this.nextWarningNanos = now + TimeUnit.SECONDS.toNanos(3);
        }
    }
}
