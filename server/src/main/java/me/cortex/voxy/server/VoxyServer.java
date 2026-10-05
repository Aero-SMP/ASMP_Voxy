package me.cortex.voxy.server;

import com.electronwill.nightconfig.toml.TomlParser;
import me.cortex.voxy.network.QuicEndpointPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
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
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("Voxy Server");
    private static final AdvertisedAddress ADDRESS_CONFIG = loadAdvertisedAddress();
    private static volatile boolean accepting;
    private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();
    private static final java.util.concurrent.ConcurrentHashMap<Object, byte[]> ROUTES = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Set<ResourceKey<Level>> UNSUPPORTED_DIMENSIONS = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static volatile MinecraftServer minecraft;

    public VoxyServer(IEventBus modBus) {
        ServerDebug.initialize(modBus);
        modBus.addListener(VoxyServer::registerPayload);
        NeoForge.EVENT_BUS.addListener(VoxyServer::serverStarting);
        NeoForge.EVENT_BUS.addListener(VoxyServer::serverStopping);
        NeoForge.EVENT_BUS.addListener(VoxyServer::serverStopped);
        NeoForge.EVENT_BUS.addListener(VoxyServer::serverTick);
        NeoForge.EVENT_BUS.addListener(VoxyServer::playerLeft);
    }

    private static void serverStarting(ServerStartingEvent event) {
        accepting = false;
        UNSUPPORTED_DIMENSIONS.clear();
        RustBackend.start();
        minecraft = event.getServer();
        publishDimensions(minecraft);
        accepting = true;
    }

    public static void completedTerrainSave(ResourceKey<Level> dimension, int chunkX, int chunkZ) {
        if (!UNSUPPORTED_DIMENSIONS.contains(dimension)) RustBackend.savedChunk(dimension, chunkX, chunkZ);
    }

    private static void serverStopping(ServerStoppingEvent event) {
        accepting = false;
        minecraft = null;
        ROUTES.clear();
        RustBackend.stop();
    }

    private static void serverStopped(ServerStoppedEvent event) {
        // NeoForge skips ServerStoppingEvent when the tick loop throws, but always posts
        // ServerStoppedEvent from finally. Do not keep serving/restarting Rust after a crash.
        accepting = false;
        minecraft = null;
        ROUTES.clear();
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
        Object connection = player.connection;
        byte[] token = ROUTES.computeIfAbsent(connection, ignored -> {
            byte[] bytes = new byte[32]; RANDOM.nextBytes(bytes); return bytes;
        });
        RustBackend.register(token, request.bandwidthKbps()).whenComplete((ignored, failure) -> {
            if (failure != null || !accepting || ready != RustBackend.ready()
                    || ROUTES.get(connection) != token || !player.connection.isAcceptingMessages()) return;
            int port = ADDRESS_CONFIG.udpPortOverride() == 0 ? ready.udpPort() : ADDRESS_CONFIG.udpPortOverride();
            player.connection.send(QuicEndpointPayload.endpoint(ADDRESS_CONFIG.host(), port,
                    ready.alpn(), ready.certificateSha256(), token, request.bandwidthKbps()));
            ServerDebug.endpointAdvertised(player.getGameProfile().getName(), ADDRESS_CONFIG.host(), port, ready.alpn());
        });
    }

    private static void playerLeft(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            byte[] token = ROUTES.remove(player.connection);
            if (token != null) RustBackend.revoke(token);
        }
    }

    private static void serverTick(ServerTickEvent.Post event) {
        MinecraftServer server = minecraft;
        if (accepting && server != null) publishDimensions(server);
    }

    /** Read live metadata only; this does not load chunks or ask Minecraft for generation. */
    private static void publishDimensions(MinecraftServer server) {
        java.nio.file.Path root = server.getWorldPath(LevelResource.ROOT).toAbsolutePath();
        for (var level : server.getAllLevels()) {
            int minY = Math.floorDiv(level.getMinBuildHeight(), 32);
            int maxY = Math.floorDiv(level.getMaxBuildHeight() - 1, 32) + 1;
            if (minY < -128 || maxY > 128 || minY >= maxY) {
                if (UNSUPPORTED_DIMENSIONS.add(level.dimension())) LOGGER.error(
                        "Voxy omits dimension {}: build height [{}, {}) exceeds the supported block range [-4096, 4096). "
                                + "This dimension cannot be downloaded or rendered by Voxy; Minecraft continues normally.",
                        level.dimension().location(), level.getMinBuildHeight(), level.getMaxBuildHeight());
                continue;
            }
            UNSUPPORTED_DIMENSIONS.remove(level.dimension());
            var border = level.getWorldBorder();
            double size = border.getSize();
            boolean custom = size != 59_999_968.0 || border.getCenterX() != 0.0 || border.getCenterZ() != 0.0;
            RustBackend.dimension(new ChunkSaveNotifications.Dimension(level.dimension().location().toString(),
                    DimensionType.getStorageFolder(level.dimension(), root).toString(), minY, maxY - minY,
                    custom, border.getCenterX(), border.getCenterZ(), size));
        }
    }

    private static AdvertisedAddress loadAdvertisedAddress() {
        try {
            RustBackend.ensureConfig(RustBackend.CONFIG);
            try (Reader input = Files.newBufferedReader(RustBackend.CONFIG)) {
                var config = new TomlParser().parse(input);
                if (config.contains("quic.advertise_host") || config.contains("quic.advertise_port")) {
                    throw new IllegalArgumentException("Replace quic.advertise_host/advertise_port with quic.advertise");
                }
                return AdvertisedAddress.parse(config.getOrElse("quic.advertise", ""));
            }
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("Cannot read " + RustBackend.CONFIG, exception);
        }
    }

}
