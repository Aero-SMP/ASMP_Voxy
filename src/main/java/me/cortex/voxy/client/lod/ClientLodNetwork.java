package me.cortex.voxy.client.lod;

import me.cortex.voxy.commonImpl.lod.LodStreamingService;
import me.cortex.voxy.commonImpl.lod.LodNetwork;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class ClientLodNetwork {

    // drained nearest-first each tick, sorted once per tick instead of scanned per poll
    private static final ArrayDeque<LodNetwork.LODDataPayload> INGEST_QUEUE = new ArrayDeque<>();
    private static final Object QUEUE_LOCK = new Object();
    // max sections to ingest per client tick
    private static final int MAX_SECTIONS_PER_TICK = 96;
    // cap on the queue so a huge backfill can't grow it without bound
    private static final int MAX_QUEUE_SIZE = 8192;

    private static boolean serverConnected;

    public static void register(PayloadRegistrar registrar) {
        registrar.playToClient(LodNetwork.HandshakePayload.TYPE, LodNetwork.HandshakePayload.CODEC,
                ClientLodNetwork::handleHandshake);
        registrar.playToClient(LodNetwork.LODDataPayload.TYPE, LodNetwork.LODDataPayload.CODEC,
                ClientLodNetwork::handleLODData);
    }

    public static void handleHandshake(LodNetwork.HandshakePayload payload, IPayloadContext context) {
        boolean serverHasMod = payload.serverHasMod();
        context.enqueueWork(() -> {
            // only connected if the server matches our protocol
            boolean compatible = serverHasMod
                    && payload.protocolVersion() == LodStreamingService.PROTOCOL_VERSION;
            if (serverHasMod && !compatible) {
                LodStreamingService.LOGGER.warn("server LOD protocol {} != ours {}, LOD sync disabled",
                        payload.protocolVersion(), LodStreamingService.PROTOCOL_VERSION);
            }
            setServerConnected(compatible);
            // reply so the server knows this client can receive lod data
            PacketDistributor.sendToServer(new LodNetwork.HandshakeAckPayload(true, LodStreamingService.PROTOCOL_VERSION));
        });
    }

    public static void handleLODData(LodNetwork.LODDataPayload payload, IPayloadContext context) {
        synchronized (QUEUE_LOCK) {
            INGEST_QUEUE.addLast(payload);
            // memory backstop, drain trims by distance, this only fires on a burst
            // between ticks where dropping oldest is fine
            if (INGEST_QUEUE.size() > MAX_QUEUE_SIZE) {
                INGEST_QUEUE.pollFirst();
            }
        }
    }

    private static long distSq(ChunkPos a, ChunkPos b) {
        long dx = a.x - b.x;
        long dz = a.z - b.z;
        return dx * dx + dz * dz;
    }

    // drains a bounded number of sections per tick, nearest first
    // sort once per tick, ingest outside the lock so the netty thread isn't blocked
    public static void drainIngestQueue() {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            // not in a world, drop anything queued so it doesn't leak into the next session
            synchronized (QUEUE_LOCK) {
                INGEST_QUEUE.clear();
            }
            return;
        }

        var player = Minecraft.getInstance().player;
        ChunkPos center = player == null ? null : player.chunkPosition();

        // grab everything under the lock, work on it after
        List<LodNetwork.LODDataPayload> snapshot;
        synchronized (QUEUE_LOCK) {
            if (INGEST_QUEUE.isEmpty()) return;
            snapshot = new ArrayList<>(INGEST_QUEUE);
            INGEST_QUEUE.clear();
        }

        if (center != null) {
            final ChunkPos c = center;
            snapshot.sort(Comparator.comparingLong(p -> distSq(c, p.pos())));
        }

        int sectionsThisTick = 0;
        int i = 0;
        for (; i < snapshot.size() && sectionsThisTick < MAX_SECTIONS_PER_TICK; i++) {
            LodNetwork.LODDataPayload payload = snapshot.get(i);
            sectionsThisTick += payload.sections().size();
            processLODData(level, payload);
        }

        // put the rest back, dropping the farthest if we're over the cap
        if (i < snapshot.size()) {
            synchronized (QUEUE_LOCK) {
                int kept = 0;
                for (; i < snapshot.size() && kept < MAX_QUEUE_SIZE; i++, kept++) {
                    INGEST_QUEUE.addLast(snapshot.get(i));
                }
                // concurrent adds sit at the back, drop oldest to hold the cap
                while (INGEST_QUEUE.size() > MAX_QUEUE_SIZE) {
                    INGEST_QUEUE.pollFirst();
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void processLODData(ClientLevel level, LodNetwork.LODDataPayload payload) {
        // drop lod data from another dimension to avoid cross-dimension artifacts
        if (!level.dimension().equals(payload.dimension())) return;

        Registry<Biome> biomeRegistry = level.registryAccess().registryOrThrow(Registries.BIOME);

        for (LodNetwork.LODDataPayload.SectionData sectionData : payload.sections()) {
            ByteBuf statesRaw = Unpooled.wrappedBuffer(sectionData.states());
            ByteBuf biomesRaw = Unpooled.wrappedBuffer(sectionData.biomes());
            try {
                // recreate the section using the biome registry
                LevelChunkSection section = new LevelChunkSection(biomeRegistry);

                // read states+biomes back with RegistryFriendlyByteBuf for palette consistency
                RegistryFriendlyByteBuf statesBuf = new RegistryFriendlyByteBuf(
                    new FriendlyByteBuf(statesRaw),
                    level.registryAccess()
                );
                ((PalettedContainer<BlockState>) section.getStates()).read(statesBuf);

                RegistryFriendlyByteBuf biomesBuf = new RegistryFriendlyByteBuf(
                    new FriendlyByteBuf(biomesRaw),
                    level.registryAccess()
                );
                ((PalettedContainer<Holder<Biome>>) section.getBiomes()).read(biomesBuf);

                // Submit the reconstructed section to the ingestion service.
                DataLayer bl = sectionData.blockLight() != null ? new DataLayer(sectionData.blockLight()) : null;
                DataLayer sl = sectionData.skyLight() != null ? new DataLayer(sectionData.skyLight()) : null;

                LodStreamingService.rawIngest(level, section, payload.pos().x, sectionData.y(), payload.pos().z, bl, sl);

            } catch (Exception e) {
                LodStreamingService.LOGGER.error("failed to handle LOD data for chunk " + payload.pos(), e);
            } finally {
                statesRaw.release();
                biomesRaw.release();
            }
        }
    }

    public static void tick() {
        drainIngestQueue();
    }

    public static void disconnect() {
        setServerConnected(false);
    }

    public static boolean isServerConnected() { return serverConnected; }

    private static void setServerConnected(boolean connected) {
        serverConnected = connected;
    }
}
