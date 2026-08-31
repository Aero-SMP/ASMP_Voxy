package me.cortex.voxy.bridge;

import com.electronwill.nightconfig.toml.TomlParser;
import me.cortex.voxy.network.BridgePayload;
import me.cortex.voxy.network.TransportPayload;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Mod("voxy_minecraft_bridge")
public final class VoxyMinecraftBridge {
    private static final Settings SETTINGS = Settings.load();
    private static final byte TRANSPORT = SETTINGS.transport;
    private static final String DIRECT_HOST = SETTINGS.directHost;
    private static final int DIRECT_PORT = SETTINGS.directPort;
    private static final Path SOCKET = SETTINGS.socket;
    private static final ConcurrentHashMap<UUID, Session> SESSIONS = new ConcurrentHashMap<>();

    public VoxyMinecraftBridge(IEventBus modBus) {
        modBus.addListener(VoxyMinecraftBridge::registerPayload);
        NeoForge.EVENT_BUS.addListener(VoxyMinecraftBridge::logout);
        NeoForge.EVENT_BUS.addListener(VoxyMinecraftBridge::serverStarting);
        NeoForge.EVENT_BUS.addListener(VoxyMinecraftBridge::serverStopping);
    }

    private static void serverStarting(ServerStartingEvent event) {
        ServerDebug.serverStart(TRANSPORT);
        RustBackend.start();
    }

    private static void serverStopping(ServerStoppingEvent event) {
        SESSIONS.values().forEach(session -> session.close(false));
        RustBackend.stop();
    }

    private static void registerPayload(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("1").optional().executesOn(HandlerThread.NETWORK);
        registrar
                .playBidirectional(TransportPayload.TYPE, TransportPayload.CODEC,
                        (payload, context) -> advertise((ServerPlayer) context.player(), payload))
                .playBidirectional(BridgePayload.TYPE, BridgePayload.CODEC,
                        (payload, context) -> receive((ServerPlayer) context.player(), payload.data()));
        ServerDebug.register(registrar);
    }

    private static void advertise(ServerPlayer player, TransportPayload request) {
        if (request.mode() != TransportPayload.REQUEST || !request.host().isEmpty() || request.port() != 0
                || !player.connection.isAcceptingMessages()
                || !player.connection.hasChannel(TransportPayload.TYPE)) return;
        player.connection.send(new TransportPayload(TransportPayload.PROTOCOL_VERSION, TRANSPORT,
                TRANSPORT == TransportPayload.DIRECT ? DIRECT_HOST : "",
                TRANSPORT == TransportPayload.DIRECT ? DIRECT_PORT : 0));
    }

    private static void receive(ServerPlayer player, byte[] data) {
        if (TRANSPORT != TransportPayload.MINECRAFT) {
            if (data.length != 0 && player.connection.isAcceptingMessages()
                    && player.connection.hasChannel(BridgePayload.TYPE)) {
                player.connection.send(new BridgePayload(new byte[0]));
            }
            return;
        }
        UUID id = player.getUUID();
        if (data.length == 0) {
            Session session = SESSIONS.remove(id);
            if (session != null) session.close(false);
            return;
        }
        try {
            Session session = SESSIONS.get(id);
            if (session == null || !session.open.get()) {
                Session replacement = new Session(player);
                Session previous = SESSIONS.put(id, replacement);
                if (previous != null) previous.close(false);
                session = replacement;
            }
            session.send(data);
        } catch (IOException exception) {
            Session session = SESSIONS.remove(id);
            if (session != null) {
                session.close(true);
            } else if (player.connection.isAcceptingMessages()
                    && player.connection.hasChannel(BridgePayload.TYPE)) {
                player.connection.send(new BridgePayload(new byte[0]));
            }
        }
    }

    private static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        Session session = SESSIONS.remove(event.getEntity().getUUID());
        if (session != null) session.close(false);
    }

    private record Settings(byte transport, String directHost, int directPort, Path socket) {
        private static Settings load() {
            try (Reader input = Files.newBufferedReader(RustBackend.CONFIG)) {
                var config = new TomlParser().parse(input);
                String mode = config.get("transport");
                byte transport = switch (mode) {
                    case "minecraft" -> TransportPayload.MINECRAFT;
                    case "direct" -> TransportPayload.DIRECT;
                    case null, default -> throw new IllegalArgumentException(
                            "transport must be minecraft or direct");
                };
                String host = config.getOrElse("direct.advertise_host", "").trim();
                if (host.length() > TransportPayload.MAX_HOST_LENGTH) {
                    throw new IllegalArgumentException("direct.advertise_host is too long");
                }
                String listen = config.getOrElse("direct.listen", "127.0.0.1:25587");
                int separator = listen.lastIndexOf(':');
                int port = separator < 0 ? -1 : Integer.parseInt(listen.substring(separator + 1));
                if (port <= 0 || port > 65535) {
                    throw new IllegalArgumentException("direct.listen must contain a valid port");
                }
                String socket = config.getOrElse("minecraft.socket", "voxy-rust.sock");
                return new Settings(transport, host, port, Path.of(socket).toAbsolutePath());
            } catch (IOException | RuntimeException exception) {
                throw new IllegalStateException("Cannot read " + RustBackend.CONFIG, exception);
            }
        }
    }

    private static final class Session {
        private final ServerPlayer player;
        private final SocketChannel socket;
        private final InputStream input;
        private final OutputStream output;
        private final ArrayBlockingQueue<byte[]> outgoing = new ArrayBlockingQueue<>(512);
        private final AtomicBoolean open = new AtomicBoolean(true);
        private final Thread reader;
        private final Thread writer;

        private Session(ServerPlayer player) throws IOException {
            this.player = player;
            this.socket = SocketChannel.open(StandardProtocolFamily.UNIX);
            this.socket.connect(UnixDomainSocketAddress.of(SOCKET));
            this.input = Channels.newInputStream(this.socket);
            this.output = Channels.newOutputStream(this.socket);
            this.reader = daemon(this::readLoop, "Voxy bridge reader " + player.getScoreboardName());
            this.writer = daemon(this::writeLoop, "Voxy bridge writer " + player.getScoreboardName());
            ServerDebug.sessionOpened(player, this);
            this.reader.start();
            this.writer.start();
        }

        private static Thread daemon(Runnable action, String name) {
            Thread thread = new Thread(action, name);
            thread.setDaemon(true);
            return thread;
        }

        private void send(byte[] data) throws IOException {
            if (!this.open.get() || !this.outgoing.offer(data)) {
                close(true);
                throw new IOException("Voxy bridge output queue is full or closed");
            }
            ServerDebug.fromClient(this.player, this, data.length);
        }

        private void readLoop() {
            byte[] buffer = new byte[BridgePayload.MAX_CHUNK];
            try {
                int length;
                while (this.open.get() && (length = this.input.read(buffer)) >= 0) {
                    if (length > 0 && this.player.connection.isAcceptingMessages()
                            && this.player.connection.hasChannel(BridgePayload.TYPE)) {
                        ServerDebug.toClient(this.player, this, length);
                        this.player.connection.send(new BridgePayload(Arrays.copyOf(buffer, length)));
                    }
                }
            } catch (IOException ignored) {
            } finally {
                close(true);
            }
        }

        private void writeLoop() {
            try {
                while (this.open.get()) {
                    byte[] data = this.outgoing.take();
                    ServerDebug.dequeued(this.player, this);
                    this.output.write(data);
                    this.output.flush();
                }
            } catch (IOException ignored) {
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } finally {
                close(true);
            }
        }

        private void close(boolean notifyClient) {
            if (!this.open.compareAndSet(true, false)) return;
            SESSIONS.remove(this.player.getUUID(), this);
            ServerDebug.sessionClosed(this.player, this);
            try { this.socket.close(); } catch (IOException ignored) {}
            this.reader.interrupt();
            this.writer.interrupt();
            if (notifyClient && this.player.connection.isAcceptingMessages()
                    && this.player.connection.hasChannel(BridgePayload.TYPE)) {
                this.player.connection.send(new BridgePayload(new byte[0]));
            }
        }

    }
}
