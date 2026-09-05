package me.cortex.voxy.server;

import com.electronwill.nightconfig.toml.TomlParser;
import me.cortex.voxy.network.QuicEndpointPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.HandlerThread;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;

/**
 * Supervises the native backend and advertises its current QUIC endpoint to authenticated
 * players.
 */
@Mod("voxy_server")
public final class VoxyServer {
    private static final AdvertisedAddress ADDRESS_CONFIG = loadAdvertisedAddress();
    private static volatile boolean accepting;

    public VoxyServer(IEventBus modBus) {
        ServerDebug.initialize(modBus);
        modBus.addListener(VoxyServer::registerPayload);
        NeoForge.EVENT_BUS.addListener(VoxyServer::serverStarting);
        NeoForge.EVENT_BUS.addListener(VoxyServer::serverStopping);
        NeoForge.EVENT_BUS.addListener(VoxyServer::serverStopped);
    }

    private static void serverStarting(ServerStartingEvent event) {
        accepting = false;
        RustBackend.start();
        accepting = true;
    }

    private static void serverStopping(ServerStoppingEvent event) {
        accepting = false;
        RustBackend.stop();
    }

    private static void serverStopped(ServerStoppedEvent event) {
        // NeoForge skips ServerStoppingEvent when the tick loop throws, but always posts
        // ServerStoppedEvent from finally. Do not keep serving/restarting Rust after a crash.
        accepting = false;
        RustBackend.stop();
    }

    private static void registerPayload(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar(QuicEndpointPayload.REGISTRATION_VERSION)
                .optional().executesOn(HandlerThread.NETWORK);
        registrar.playBidirectional(QuicEndpointPayload.TYPE, QuicEndpointPayload.CODEC,
                (payload, context) -> advertise((ServerPlayer) context.player(), payload));
    }

    private static void advertise(ServerPlayer player, QuicEndpointPayload request) {
        RustBackend.ReadyRecord ready = RustBackend.ready();
        if (!request.isRequest() || !accepting || ready == null
                || !player.connection.isAcceptingMessages()
                || !player.connection.hasChannel(QuicEndpointPayload.TYPE)) return;
        int port = ADDRESS_CONFIG.udpPortOverride() == 0
                ? ready.udpPort() : ADDRESS_CONFIG.udpPortOverride();
        player.connection.send(QuicEndpointPayload.endpoint(ADDRESS_CONFIG.host(), port,
                ready.alpn(), ready.certificateSha256()));
        ServerDebug.endpointAdvertised(player.getGameProfile().getName(), ADDRESS_CONFIG.host(),
                port, ready.alpn());
    }

    private static AdvertisedAddress loadAdvertisedAddress() {
        try (Reader input = Files.newBufferedReader(RustBackend.CONFIG)) {
            var config = new TomlParser().parse(input);
            String host = config.getOrElse("quic.advertise_host", "");
            return new AdvertisedAddress(QuicEndpointPayload.canonicalHost(host),
                    advertisedPort(config.get("quic.advertise_port")));
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("Cannot read " + RustBackend.CONFIG, exception);
        }
    }

    private static int advertisedPort(Object configured) {
        if (configured == null) return 0;
        if (!(configured instanceof Byte || configured instanceof Short
                || configured instanceof Integer || configured instanceof Long)) {
            throw new IllegalArgumentException("quic.advertise_port must be an integer");
        }
        long port = ((Number) configured).longValue();
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("quic.advertise_port must be zero or valid");
        }
        return (int) port;
    }

    private record AdvertisedAddress(String host, int udpPortOverride) {}
}
