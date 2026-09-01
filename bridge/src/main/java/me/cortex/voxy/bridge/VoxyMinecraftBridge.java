package me.cortex.voxy.bridge;

import com.electronwill.nightconfig.toml.TomlParser;
import io.netty.util.NetUtil;
import me.cortex.voxy.network.BridgePayload;
import me.cortex.voxy.network.TransportPayload;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.HandlerThread;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Mod("voxy_minecraft_bridge")
public final class VoxyMinecraftBridge {
    private static final Settings SETTINGS = Settings.load();
    private static final ConcurrentHashMap<UUID, Session> SESSIONS = new ConcurrentHashMap<>();
    private static final long MAX_QUEUED_BRIDGE_BYTES = 64L << 20;
    private static final AtomicLong QUEUED_BRIDGE_BYTES = new AtomicLong();
    private static volatile boolean accepting;

    public VoxyMinecraftBridge(IEventBus modBus) {
        modBus.addListener(VoxyMinecraftBridge::registerPayload);
        NeoForge.EVENT_BUS.addListener(VoxyMinecraftBridge::logout);
        NeoForge.EVENT_BUS.addListener(VoxyMinecraftBridge::serverStarting);
        NeoForge.EVENT_BUS.addListener(VoxyMinecraftBridge::serverStopping);
    }

    private static void serverStarting(ServerStartingEvent event) {
        accepting = false;
        RustBackend.start(SETTINGS.transport);
        ServerDebug.serverStart(SETTINGS.transport);
        accepting = true;
    }

    private static void serverStopping(ServerStoppingEvent event) {
        accepting = false;
        SESSIONS.values().forEach(session -> session.close(true));
        SESSIONS.clear();
        ServerDebug.serverStop();
        RustBackend.stop();
    }

    private static void registerPayload(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar(TransportPayload.CHANNEL)
                .optional().executesOn(HandlerThread.NETWORK);
        registrar.playBidirectional(TransportPayload.TYPE, TransportPayload.CODEC,
                (payload, context) -> advertise((ServerPlayer) context.player(), payload));
        if (SETTINGS.transport == TransportPayload.MINECRAFT) {
            registrar.playBidirectional(BridgePayload.TYPE, BridgePayload.CODEC,
                    (payload, context) -> receive((ServerPlayer) context.player(), payload));
        }
        ServerDebug.register(registrar);
    }

    private static void advertise(ServerPlayer player, TransportPayload request) {
        if (request.mode() != TransportPayload.REQUEST || !accepting
                || !RustBackend.isReady()
                || !player.connection.isAcceptingMessages()
                || !player.connection.hasChannel(TransportPayload.TYPE)) return;
        player.connection.send(SETTINGS.advertisement);
    }

    private static void receive(ServerPlayer player, BridgePayload payload) {
        if (!accepting || !RustBackend.isReady()) {
            if (payload.action() != BridgePayload.CLOSE) closeStream(player, payload.streamId());
            return;
        }
        UUID id = player.getUUID();
        switch (payload.action()) {
            case BridgePayload.OPEN -> {
                Session current = SESSIONS.get(id);
                if (current != null && current.player == player
                        && current.streamId == payload.streamId()
                        && current.open.get()) return;
                try {
                    Session replacement = new Session(player, payload.streamId());
                    Session previous = SESSIONS.put(id, replacement);
                    if (previous != null) previous.close(true);
                    replacement.start();
                } catch (IOException exception) {
                    closeStream(player, payload.streamId());
                }
            }
            case BridgePayload.DATA -> {
                Session session = SESSIONS.get(id);
                if (session == null || session.player != player
                        || session.streamId != payload.streamId()
                        || !session.open.get()) {
                    closeStream(player, payload.streamId());
                    return;
                }
                try {
                    session.send(payload.data());
                } catch (IOException ignored) {
                    // send() closes the matching stream and reports its terminal state.
                }
            }
            case BridgePayload.CLOSE -> {
                Session session = SESSIONS.get(id);
                if (session != null && session.player == player
                        && session.streamId == payload.streamId()) {
                    session.close(false);
                }
            }
            default -> throw new IllegalStateException("decoded invalid Voxy bridge action");
        }
    }

    private static void closeStream(ServerPlayer player, long streamId) {
        if (player.connection.isAcceptingMessages()
                && player.connection.hasChannel(BridgePayload.TYPE)) {
            player.connection.send(BridgePayload.close(streamId));
        }
    }

    private static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        ServerPlayer player = (ServerPlayer) event.getEntity();
        ServerDebug.playerLogout(player);
        Session session = SESSIONS.get(player.getUUID());
        if (session != null && session.player == player
                && SESSIONS.remove(player.getUUID(), session)) {
            session.close(false);
        }
    }

    private record Settings(byte transport, Path socket, TransportPayload advertisement) {
        private static Settings load() {
            try (Reader input = Files.newBufferedReader(RustBackend.CONFIG)) {
                var config = new TomlParser().parse(input);
                String mode = config.get("transport");
                if ("minecraft".equals(mode)) {
                    String socket = config.getOrElse("minecraft.socket", "voxy-rust.sock");
                    if (socket.isBlank()) {
                        throw new IllegalArgumentException("minecraft.socket cannot be blank");
                    }
                    return new Settings(TransportPayload.MINECRAFT,
                            Path.of(socket).toAbsolutePath(), TransportPayload.minecraft());
                }
                if (!"direct".equals(mode)) {
                    throw new IllegalArgumentException("transport must be minecraft or direct");
                }
                String configuredHost = config.getOrElse("direct.advertise_host", "");
                String listen = config.getOrElse("direct.listen",
                        "127.0.0.1:" + TransportPayload.DEFAULT_DIRECT_PORT);
                int listenPort = directListenPort(listen);
                return new Settings(TransportPayload.DIRECT, null, TransportPayload.direct(
                        configuredHost,
                        directAdvertisePort(config.get("direct.advertise_port"), listenPort)));
            } catch (IOException | RuntimeException exception) {
                throw new IllegalStateException("Cannot read " + RustBackend.CONFIG, exception);
            }
        }

        private static int directListenPort(String listen) {
            if (listen == null || !listen.equals(listen.trim())) {
                throw new IllegalArgumentException("direct.listen contains whitespace");
            }
            int separator = listen.lastIndexOf(':');
            if (separator <= 0 || separator == listen.length() - 1) {
                throw new IllegalArgumentException("direct.listen must be a numeric socket address");
            }
            String host = listen.substring(0, separator);
            boolean bracketed = host.startsWith("[") && host.endsWith("]");
            if (bracketed) {
                host = host.substring(1, host.length() - 1);
            } else if (host.indexOf(':') >= 0) {
                throw new IllegalArgumentException("direct.listen IPv6 must use brackets");
            }
            byte[] address = NetUtil.createByteArrayFromIpAddressString(host);
            if (host.indexOf('%') >= 0 || address == null
                    || bracketed && address.length != 16) {
                throw new IllegalArgumentException("direct.listen host must be a numeric IP address");
            }
            String portText = listen.substring(separator + 1);
            for (int index = 0; index < portText.length(); index++) {
                if (!Character.isDigit(portText.charAt(index))) {
                    throw new IllegalArgumentException("direct.listen port must be decimal");
                }
            }
            int port = Integer.parseInt(portText);
            if (port <= 0 || port > 65535) {
                throw new IllegalArgumentException("direct.listen must contain a valid port");
            }
            return port;
        }

        private static int directAdvertisePort(Object configured, int listenPort) {
            if (configured == null) return listenPort;
            if (!(configured instanceof Byte || configured instanceof Short
                    || configured instanceof Integer || configured instanceof Long)) {
                throw new IllegalArgumentException("direct.advertise_port must be an integer");
            }
            long port = ((Number) configured).longValue();
            if (port == 0) return listenPort;
            if (port < 0 || port > 65535) {
                throw new IllegalArgumentException(
                        "direct.advertise_port must be zero or a valid port");
            }
            return (int) port;
        }

    }

    private static final class Session {
        private static final long MAX_SESSION_QUEUED_BYTES = 2L << 20;

        private final ServerPlayer player;
        private final long streamId;
        private final SocketChannel socket;
        private final InputStream input;
        private final OutputStream output;
        private final ArrayBlockingQueue<byte[]> outgoing = new ArrayBlockingQueue<>(64);
        private final AtomicLong queuedBytes = new AtomicLong();
        private final AtomicBoolean open = new AtomicBoolean(true);
        private final Thread reader;
        private final Thread writer;

        private Session(ServerPlayer player, long streamId) throws IOException {
            this.player = player;
            this.streamId = streamId;
            this.socket = SocketChannel.open(StandardProtocolFamily.UNIX);
            try {
                this.socket.connect(UnixDomainSocketAddress.of(SETTINGS.socket));
                this.input = Channels.newInputStream(this.socket);
                this.output = Channels.newOutputStream(this.socket);
                this.reader = Thread.ofPlatform().daemon()
                        .name("Voxy bridge reader " + player.getScoreboardName())
                        .unstarted(this::readLoop);
                this.writer = Thread.ofPlatform().daemon()
                        .name("Voxy bridge writer " + player.getScoreboardName())
                        .unstarted(this::writeLoop);
            } catch (IOException | RuntimeException exception) {
                try { this.socket.close(); } catch (IOException ignored) {}
                throw exception;
            }
        }

        private void start() {
            ServerDebug.sessionOpened(this.player, this);
            this.reader.start();
            this.writer.start();
        }

        private synchronized void send(byte[] data) throws IOException {
            if (!this.open.get() || !reserve(data.length)) {
                close(true);
                throw new IOException("Voxy bridge output queue is full or closed");
            }
            if (!this.outgoing.offer(data)) {
                release(data.length);
                close(true);
                throw new IOException("Voxy bridge output queue is full or closed");
            }
            ServerDebug.fromClient(this.player, this, data.length);
        }

        private boolean reserve(int bytes) {
            long session = this.queuedBytes.addAndGet(bytes);
            if (session > MAX_SESSION_QUEUED_BYTES) {
                this.queuedBytes.addAndGet(-bytes);
                return false;
            }
            long global = QUEUED_BRIDGE_BYTES.addAndGet(bytes);
            if (global > MAX_QUEUED_BRIDGE_BYTES) {
                QUEUED_BRIDGE_BYTES.addAndGet(-bytes);
                this.queuedBytes.addAndGet(-bytes);
                return false;
            }
            return true;
        }

        private void release(int bytes) {
            long session = this.queuedBytes.addAndGet(-bytes);
            long global = QUEUED_BRIDGE_BYTES.addAndGet(-bytes);
            if (session < 0 || global < 0) {
                throw new IllegalStateException("Voxy bridge byte accounting underflow");
            }
        }

        private void readLoop() {
            byte[] buffer = new byte[BridgePayload.MAX_CHUNK];
            try {
                int length;
                while (this.open.get()) {
                    // Propagate Minecraft/Netty backpressure all the way to the Unix socket.
                    // Stopping this reader lets the kernel socket buffer, and ultimately
                    // Wire credit bounds the complete relay pipeline.
                    awaitMinecraftWritable();
                    if (!this.open.get() || (length = this.input.read(buffer)) < 0) break;
                    if (length > 0 && this.player.connection.isAcceptingMessages()
                            && this.player.connection.hasChannel(BridgePayload.TYPE)) {
                        ServerDebug.toClient(this.player, this, length);
                        relayToClient(Arrays.copyOf(buffer, length));
                    }
                }
            } catch (IOException ignored) {
            } finally {
                close(true);
            }
        }

        /**
         * Waits until Netty has completed this write before reading another Unix-socket chunk.
         * Merely checking {@code channel.isWritable()} before calling {@code send()} is not
         * sufficient from this platform thread: {@code send()} first queues work onto Netty's
         * event loop, so an unconstrained reader can otherwise fill that task queue before the
         * channel's write watermark changes. One completed packet at a time propagates TCP
         * backpressure through Minecraft, the Unix socket, and finally wire credit.
         */
        private void relayToClient(byte[] data) throws IOException {
            CountDownLatch completed = new CountDownLatch(1);
            this.player.connection.send(
                    new ClientboundCustomPayloadPacket(BridgePayload.data(this.streamId, data)),
                    PacketSendListener.thenRun(completed::countDown));
            try {
                if (!completed.await(30, TimeUnit.SECONDS)) {
                    throw new IOException("Minecraft Voxy bridge write timed out");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("Voxy bridge relay interrupted", exception);
            }
            if (!this.open.get() || !this.player.connection.isAcceptingMessages()) {
                throw new IOException("Minecraft connection closed while relaying Voxy data");
            }
        }

        private void awaitMinecraftWritable() throws IOException {
            var channel = this.player.connection.getConnection().channel();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (this.open.get() && this.player.connection.isAcceptingMessages()
                    && !channel.isWritable()) {
                if (!channel.isOpen() || !channel.isActive()
                        || System.nanoTime() - deadline >= 0) {
                    throw new IOException("Minecraft Voxy bridge backpressure timed out");
                }
                try {
                    Thread.sleep(1);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Voxy bridge relay interrupted", exception);
                }
            }
            if (!this.open.get() || !this.player.connection.isAcceptingMessages()
                    || !channel.isOpen() || !channel.isActive()) {
                throw new IOException("Minecraft connection closed while relaying Voxy data");
            }
        }

        private void writeLoop() {
            try {
                while (this.open.get()) {
                    byte[] data = this.outgoing.take();
                    ServerDebug.dequeued(this.player, this);
                    try {
                        this.output.write(data);
                    } finally {
                        release(data.length);
                    }
                }
            } catch (IOException ignored) {
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } finally {
                close(true);
            }
        }

        private synchronized void close(boolean notifyClient) {
            if (!this.open.compareAndSet(true, false)) return;
            SESSIONS.remove(this.player.getUUID(), this);
            ServerDebug.sessionClosed(this.player, this);
            byte[] queued;
            while ((queued = this.outgoing.poll()) != null) release(queued.length);
            try { this.socket.close(); } catch (IOException ignored) {}
            this.reader.interrupt();
            this.writer.interrupt();
            if (notifyClient && this.player.connection.isAcceptingMessages()
                    && this.player.connection.hasChannel(BridgePayload.TYPE)) {
                this.player.connection.send(BridgePayload.close(this.streamId));
            }
        }

    }
}
