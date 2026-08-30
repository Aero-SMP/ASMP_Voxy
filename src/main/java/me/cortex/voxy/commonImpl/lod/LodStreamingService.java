package me.cortex.voxy.commonImpl.lod;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class LodStreamingService {
    public static final String NAMESPACE = "voxy";
    public static final Logger LOGGER = LoggerFactory.getLogger(NAMESPACE);
    public static final int PROTOCOL_VERSION = 1;

    public LodStreamingService(IEventBus modEventBus) {
        LOGGER.info("LOD streaming initializing");
        LodStreamingConfig.load();
        modEventBus.addListener(LodNetwork::registerPayloads);
        NeoForge.EVENT_BUS.register(LodStreamingService.class);
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        LOGGER.info("server started, initializing manager");
        LodGenerationService.getInstance().initialize(event.getServer());
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        LOGGER.info("server stopping, shutting down manager");
        LodGenerationService.getInstance().shutdown();
        LodNetwork.shutdown();
        LodGenerationService.getInstance().clearPlayers();
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        LodGenerationService.getInstance().tick();
    }

    @SubscribeEvent
    public static void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            LodGenerationService.getInstance().addPlayer(player);
            LodNetwork.sendHandshake(player);
        }
    }

    @SubscribeEvent
    public static void onPlayerDisconnect(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            LodGenerationService.getInstance().removePlayer(player);
        }
    }

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel && event.getChunk() instanceof LevelChunk chunk) {
            LodGenerationService manager = LodGenerationService.getInstance();
            if (!manager.anyModded()) return;

            long packed = chunk.getPos().toLong();
            for (ServerPlayer player : manager.getPlayers()) {
                if (!manager.isModded(player.getUUID())) continue;
                if (manager.isSynced(player.getUUID(), packed)) continue;
                LodNetwork.sendLODData(player, chunk);
            }
        }
    }

    private static boolean initialized;
    private static boolean enabled;
    private static MethodHandle ingestMethod, rawIngestMethod, worldIdentifierOfMethod, voxyEnabledMethod;

    private static void initialize() {
        if (initialized) return;
        initialized = true;

        try {
            Class<?> ingestServiceClass = Class.forName("me.cortex.voxy.common.world.service.VoxelIngestService");
            Class<?> worldIdentifierClass = Class.forName("me.cortex.voxy.commonImpl.WorldIdentifier");

            Object serviceInstance = null;
            try {
                Field instanceField = ingestServiceClass.getDeclaredField("INSTANCE");
                serviceInstance = instanceField.get(null);
            } catch (Exception ignored) {
            }

            MethodHandles.Lookup lookup = MethodHandles.lookup();

            // find main ingest method
            String[] commonMethods = {"ingestChunk", "tryAutoIngestChunk", "enqueueIngest", "ingest"};
            Method targetMethod = null;
            for (String methodName : commonMethods) {
                try {
                    targetMethod = ingestServiceClass.getMethod(methodName, LevelChunk.class);
                    if (targetMethod != null) break;
                } catch (NoSuchMethodException ignored) {}
            }

            if (targetMethod != null) {
                ingestMethod = lookup.unreflect(targetMethod);
                if (serviceInstance != null && !Modifier.isStatic(targetMethod.getModifiers())) {
                    ingestMethod = ingestMethod.bindTo(serviceInstance);
                }
                enabled = true;
            }

            // find rawIngest method
            try {
                Method rawIngest = ingestServiceClass.getMethod("rawIngest",
                    worldIdentifierClass,
                    LevelChunkSection.class,
                    int.class, int.class, int.class,
                    DataLayer.class,
                    DataLayer.class);
                rawIngestMethod = lookup.unreflect(rawIngest);
            } catch (NoSuchMethodException ignored) {}

            // find WorldIdentifier of method
            try {
                Method ofMethod = worldIdentifierClass.getMethod("of", Level.class);
                worldIdentifierOfMethod = lookup.unreflect(ofMethod);
            } catch (NoSuchMethodException ignored) {}

            // Client configuration is unavailable on a dedicated server. Loading it
            // there also initializes client-only LWJGL classes and kills this worker.
            if (FMLEnvironment.dist == Dist.CLIENT) {
                try {
                    Class<?> voxyConfigClass = Class.forName("me.cortex.voxy.client.config.VoxyConfig");
                    try {
                        Method isEnabledMethod = voxyConfigClass.getMethod("isEnabled");
                        voxyEnabledMethod = lookup.unreflect(isEnabledMethod);
                    } catch (NoSuchMethodException ignored) {
                        // try field-based approach
                        try {
                            Field enabledField = voxyConfigClass.getDeclaredField("enabled");
                            enabledField.setAccessible(true);
                            voxyEnabledMethod = lookup.unreflectGetter(enabledField);
                        } catch (Exception ignored2) {}
                    }
                } catch (ClassNotFoundException ignored) {
                    // try alternate class names
                    try {
                        Class<?> voxyClientClass = Class.forName("me.cortex.voxy.client.VoxyClient");
                        try {
                            Method isEnabledMethod = voxyClientClass.getMethod("isEnabled");
                            voxyEnabledMethod = lookup.unreflect(isEnabledMethod);
                        } catch (NoSuchMethodException ignored2) {}
                    } catch (ClassNotFoundException ignored3) {}
                }
            }

            LodStreamingService.LOGGER.info("LOD ingestion bridge initialized (enabled: {}, raw: {}, voxyEnabled: {})", enabled, rawIngestMethod != null, voxyEnabledMethod != null);

        } catch (ClassNotFoundException e) {
            LodStreamingService.LOGGER.info("LOD ingestion bridge unavailable");
            enabled = false;
        } catch (Exception e) {
            LodStreamingService.LOGGER.error("failed to initialize LOD ingestion bridge", e);
            enabled = false;
        }
    }

    public static void ingestChunk(LevelChunk chunk) {
        if (!initialized) initialize();
        if (!enabled || ingestMethod == null) return;

        try {
            ingestMethod.invoke(chunk);
        } catch (Throwable e) {
            LodStreamingService.LOGGER.error("failed to ingest chunk", e);
        }
    }

    public static void rawIngest(LevelChunk chunk, DataLayer skyLight) {
        if (!initialized) initialize();
        if (rawIngestMethod == null || worldIdentifierOfMethod == null) return;

        try {
            LevelChunkSection[] sections = chunk.getSections();
            int cx = chunk.getPos().x;
            int cz = chunk.getPos().z;
            int minY = chunk.getMinSection();

            // get worldid once per chunk
            Object worldId = worldIdentifierOfMethod.invoke(chunk.getLevel());
            if (worldId == null) return;

            for (int i = 0; i < sections.length; i++) {
                LevelChunkSection section = sections[i];
                if (section == null || section.hasOnlyAir()) continue;

                rawIngestMethod.invoke(worldId, section, cx, minY + i, cz, null, skyLight);
            }
        } catch (Throwable e) {
            LodStreamingService.LOGGER.error("failed to raw ingest chunk", e);
        }
    }

    public static void rawIngest(Level level, LevelChunkSection section, int cx, int cy, int cz,
                                 DataLayer blockLight, DataLayer skyLight) {
        if (!initialized) initialize();
        if (rawIngestMethod == null || worldIdentifierOfMethod == null) return;

        try {
            Object worldId = worldIdentifierOfMethod.invoke(level);
            if (worldId == null) return;

            rawIngestMethod.invoke(worldId, section, cx, cy, cz, blockLight, skyLight);
        } catch (Throwable e) {
            LodStreamingService.LOGGER.error("failed to raw ingest section", e);
        }
    }

    public static void rawIngest(Level level, LevelChunkSection section, int cx, int cy, int cz, DataLayer skyLight) {
        rawIngest(level, section, cx, cy, cz, null, skyLight);
    }

    public static boolean isIngestionAvailable() {
        if (!initialized) initialize();
        return enabled;
    }

    public static boolean isRenderingEnabled() {
        if (!initialized) initialize();
        if (!enabled) return true; // voxy not present, don't suppress generation
        if (voxyEnabledMethod == null) return true; // can't determine state, assume enabled
        try {
            Object result = voxyEnabledMethod.invoke();
            if (result instanceof Boolean b) return b;
        } catch (Throwable ignored) {}
        return true;
    }
}
