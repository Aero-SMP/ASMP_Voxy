package me.cortex.voxy.commonImpl.lod;

import me.cortex.voxy.client.lod.ClientLodNetwork;
import io.netty.buffer.*;
import it.unimi.dsi.fastutil.ints.IntSet;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.*;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.*;
import net.minecraft.world.level.chunk.*;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

public class LodNetwork {
    public static final ResourceLocation HANDSHAKE_ID = id("lod_handshake");
    public static final ResourceLocation HANDSHAKE_ACK_ID = id("lod_handshake_ack");
    public static final ResourceLocation LOD_DATA_ID = id("lod_data");

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(LodStreamingService.NAMESPACE, path);
    }

    // keep packets well under netty 2mb limit so servers don't reset the connection
    private static final int MAX_PACKET_BYTES = 32_768;
    private static final int SECTION_OVERHEAD_BYTES = 32;
    private static final int PACKET_OVERHEAD_BYTES = 256;

    public record HandshakePayload(boolean serverHasMod, int protocolVersion) implements CustomPacketPayload {
        public static final Type<HandshakePayload> TYPE = new Type<>(HANDSHAKE_ID);
        public static final StreamCodec<FriendlyByteBuf, HandshakePayload> CODEC = CustomPacketPayload.codec(HandshakePayload::write, HandshakePayload::new);

        public HandshakePayload(FriendlyByteBuf buf) {
            this(buf.readBoolean(), buf.readVarInt());
        }

        public void write(FriendlyByteBuf buf) {
            buf.writeBoolean(this.serverHasMod);
            buf.writeVarInt(this.protocolVersion);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // client -> server ack so the server knows this client can receive lod data
    public record HandshakeAckPayload(boolean clientHasMod, int protocolVersion) implements CustomPacketPayload {
        public static final Type<HandshakeAckPayload> TYPE = new Type<>(HANDSHAKE_ACK_ID);
        public static final StreamCodec<FriendlyByteBuf, HandshakeAckPayload> CODEC = CustomPacketPayload.codec(HandshakeAckPayload::write, HandshakeAckPayload::new);

        public HandshakeAckPayload(FriendlyByteBuf buf) {
            this(buf.readBoolean(), buf.readVarInt());
        }

        public void write(FriendlyByteBuf buf) {
            buf.writeBoolean(this.clientHasMod);
            buf.writeVarInt(this.protocolVersion);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record LODDataPayload(ResourceKey<Level> dimension, ChunkPos pos, int minY, List<SectionData> sections) implements CustomPacketPayload {
        public static final Type<LODDataPayload> TYPE = new Type<>(LOD_DATA_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, LODDataPayload> CODEC = CustomPacketPayload.codec(LODDataPayload::write, LODDataPayload::new);

        public record SectionData(int y, byte[] states, byte[] biomes, byte[] blockLight, byte[] skyLight) {
            public void write(RegistryFriendlyByteBuf buf) {
                buf.writeInt(y);
                buf.writeByteArray(states);
                buf.writeByteArray(biomes);
                buf.writeNullable(blockLight, (b, a) -> b.writeByteArray(a));
                buf.writeNullable(skyLight, (b, a) -> b.writeByteArray(a));
            }

            public static SectionData read(RegistryFriendlyByteBuf buf) {
                return new SectionData(
                    buf.readInt(),
                    buf.readByteArray(),
                    buf.readByteArray(),
                    buf.readNullable(b -> b.readByteArray()),
                    buf.readNullable(b -> b.readByteArray())
                );
            }

            // approx serialized size incl framing, used for batch splitting
            int sizeBytes() {
                return states.length + biomes.length
                    + (blockLight != null ? blockLight.length : 0)
                    + (skyLight != null ? skyLight.length : 0)
                    + SECTION_OVERHEAD_BYTES;
            }
        }

        public LODDataPayload(RegistryFriendlyByteBuf buf) {
            this(
                ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(buf.readUtf())),
                buf.readChunkPos(),
                buf.readInt(),
                buf.readCollection(ArrayList::new, b -> SectionData.read((RegistryFriendlyByteBuf) b))
            );
        }

        public void write(RegistryFriendlyByteBuf buf) {
            buf.writeUtf(dimension.location().toString());
            buf.writeChunkPos(pos);
            buf.writeInt(minY);
            // cast to avoid ambiguous writeCollection / BiConsumer type issues
            buf.writeCollection(sections, (b, s) -> s.write((RegistryFriendlyByteBuf) b));
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // one sync radius shared by the broadcast, catch-up and chunk-load paths
    public static double syncRadiusSq() {
        long radiusBlocks = (long) LodStreamingConfig.DATA.generationRadius * 16L;
        double r = radiusBlocks;
        return r * r;
    }

    public static boolean inSyncRange(ServerPlayer player, LevelChunk chunk) {
        ChunkPos pos = chunk.getPos();
        if (player.level() != chunk.getLevel()) return false;
        double dx = player.getX() - pos.getMiddleBlockX();
        double dz = player.getZ() - pos.getMiddleBlockZ();
        return dx * dx + dz * dz <= syncRadiusSq();
    }

    // coarse range/dim check for the send pool, pos is read racily but worst case
    // is one extra chunk
    private static boolean stillRelevant(ServerPlayer player, ResourceKey<Level> dim, ChunkPos pos) {
        if (!player.level().dimension().equals(dim)) return false;
        double dx = player.getX() - pos.getMiddleBlockX();
        double dz = player.getZ() - pos.getMiddleBlockZ();
        return dx * dx + dz * dz <= syncRadiusSq();
    }

    // registers payload types client receive logic is in ClientLodNetwork, the ack is here
    public static void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(Integer.toString(LodStreamingService.PROTOCOL_VERSION))
                .optional(); // tolerate clients/servers without the mod

        if (FMLEnvironment.dist == Dist.CLIENT) {
            ClientLodNetwork.register(registrar);
        } else {
            registrar.playToClient(HandshakePayload.TYPE, HandshakePayload.CODEC, LodNetwork::ignoreClientbound);
            registrar.playToClient(LODDataPayload.TYPE, LODDataPayload.CODEC, LodNetwork::ignoreClientbound);
        }

        registrar.playToServer(
                HandshakeAckPayload.TYPE,
                HandshakeAckPayload.CODEC,
                LodNetwork::handleHandshakeAck
        );
        LodStreamingService.LOGGER.info("LOD networking initialized");
    }

    // server-side no-op the server never receives clientbound payloads
    private static <T extends CustomPacketPayload> void ignoreClientbound(T payload, IPayloadContext context) {
    }

    private static void handleHandshakeAck(HandshakeAckPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            context.enqueueWork(() -> {
                // only modded if it acks and matches our protocol, else packets would
                // mis-parse so leave it unmodded and send nothing
                boolean compatible = payload.clientHasMod()
                        && payload.protocolVersion() == LodStreamingService.PROTOCOL_VERSION;
                if (payload.clientHasMod() && !compatible) {
                    LodStreamingService.LOGGER.warn("client {} has an incompatible LOD protocol (theirs={}, ours={}), not syncing LOD data",
                            player.getGameProfile().getName(), payload.protocolVersion(), LodStreamingService.PROTOCOL_VERSION);
                }
                LodGenerationService.getInstance().setModded(player.getUUID(), compatible);
            });
        }
    }

    private static void setSyncedState(ServerPlayer player, ChunkPos pos, boolean isSynced) {
        var synced = LodGenerationService.getInstance().getSyncedChunks(player.getUUID());
        if (synced != null) {
            if (isSynced) {
                synced.add(pos.toLong());
            } else {
                synced.remove(pos.toLong());
            }
        }
    }

    // background pool for the safe part of sync serialization stays on the
    // server thread because the palette write is not safe to read off-thread
    private static final ExecutorService SEND_POOL = createSendPool();

    // cap the send queue, a storm pushes back on the server thread instead of
    // growing memory forever
    private static final int SEND_QUEUE_CAPACITY = 4096;

    private static ExecutorService createSendPool() {
        int threads = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
        ThreadPoolExecutor ex = new ThreadPoolExecutor(
            threads, threads, 30L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(SEND_QUEUE_CAPACITY),
            r -> {
                Thread t = new Thread(r, "Voxy-LOD-Send");
                t.setDaemon(true);
                t.setPriority(Thread.NORM_PRIORITY - 1);
                return t;
            },
            new ThreadPoolExecutor.CallerRunsPolicy());
        ex.allowCoreThreadTimeOut(true);
        return ex;
    }

    public static void shutdown() {
        SEND_POOL.shutdownNow();
    }

    public static void broadcastLODData(LevelChunk chunk) {
        broadcastLODData(chunk, null);
    }

    // if onlySectionYs is non-null, only those section y-levels are sent (used for block edits)
    public static void broadcastLODData(LevelChunk chunk, IntSet onlySectionYs) {
        ChunkPos pos = chunk.getPos();
        int minY = chunk.getMinSection();
        ResourceKey<Level> dim = chunk.getLevel().dimension();

        List<ServerPlayer> recipients = new ArrayList<>();
        LodGenerationService manager = LodGenerationService.getInstance();
        for (ServerPlayer player : manager.getPlayers()) {
            if (!manager.isModded(player.getUUID())) continue;
            if (!inSyncRange(player, chunk)) {
                if (onlySectionYs == null) setSyncedState(player, pos, false);
                continue;
            }
            recipients.add(player);
        }

        if (recipients.isEmpty()) return;

        List<LODDataPayload.SectionData> sections = buildSections(chunk, onlySectionYs);
        if (sections.isEmpty()) {
            if (onlySectionYs == null) {
                for (ServerPlayer player : recipients) setSyncedState(player, pos, false);
            }
            return;
        }
        if (onlySectionYs == null) {
            for (ServerPlayer player : recipients) setSyncedState(player, pos, true);
        }
        sendAsync(dim, pos, minY, sections, recipients);
    }

    public static void sendLODData(ServerPlayer player, LevelChunk chunk) {
        ChunkPos pos = chunk.getPos();
        int minY = chunk.getMinSection();
        ResourceKey<Level> dim = chunk.getLevel().dimension();

        if (!LodGenerationService.getInstance().isModded(player.getUUID())) return;
        if (!inSyncRange(player, chunk)) {
            setSyncedState(player, pos, false);
            return;
        }

        List<LODDataPayload.SectionData> sections = buildSections(chunk, null);
        if (sections.isEmpty()) {
            setSyncedState(player, pos, false);
            return;
        }
        setSyncedState(player, pos, true);
        sendAsync(dim, pos, minY, sections, List.of(player));
    }

    private static void sendAsync(ResourceKey<Level> dim, ChunkPos pos, int minY, List<LODDataPayload.SectionData> sections, List<ServerPlayer> recipients) {
        // can race that shutdown drop the send instead of crashing the server tick loop
        if (SEND_POOL.isShutdown()) return;
        try {
            SEND_POOL.execute(() -> {
                try {
                    for (ServerPlayer player : recipients) {
                        // by now the player may have left or moved off, skip stale sends
                        if (player.hasDisconnected()) continue;
                        if (!stillRelevant(player, dim, pos)) continue;
                        sendSectionsInBatches(player, dim, pos, minY, sections);
                    }
                } catch (Throwable t) {
                    LodStreamingService.LOGGER.error("failed to send LOD data for chunk " + pos, t);
                }
            });
        } catch (RejectedExecutionException ignored) {
            // pool was shut down between the check above and submit, the chunk should just re-syncs on next join
        }
    }

    private static List<LODDataPayload.SectionData> buildSections(LevelChunk chunk, IntSet onlySectionYs) {
        ChunkPos pos = chunk.getPos();
        int minY = chunk.getMinSection();
        List<LODDataPayload.SectionData> sections = new ArrayList<>();
        var lightEngine = chunk.getLevel().getLightEngine();

        LevelChunkSection[] sectionArray = chunk.getSections();
        for (int i = 0; i < sectionArray.length; i++) {
            int sectionY = minY + i;
            if (onlySectionYs != null && !onlySectionYs.contains(sectionY)) continue;

            LevelChunkSection section = sectionArray[i];
            if (section == null || section.hasOnlyAir()) continue;

            ByteBuf statesRaw = Unpooled.buffer();
            ByteBuf biomesRaw = Unpooled.buffer();
            try {
                byte[] states, biomes;
                RegistryFriendlyByteBuf statesBuf = new RegistryFriendlyByteBuf(new FriendlyByteBuf(statesRaw), chunk.getLevel().registryAccess());
                section.getStates().write(statesBuf);
                states = new byte[statesBuf.readableBytes()];
                statesBuf.readBytes(states);

                RegistryFriendlyByteBuf biomesBuf = new RegistryFriendlyByteBuf(new FriendlyByteBuf(biomesRaw), chunk.getLevel().registryAccess());
                section.getBiomes().write(biomesBuf);
                biomes = new byte[biomesBuf.readableBytes()];
                biomesBuf.readBytes(biomes);

                SectionPos sectionPos = SectionPos.of(pos, sectionY);
                DataLayer bl = lightEngine.getLayerListener(LightLayer.BLOCK).getDataLayerData(sectionPos);
                DataLayer sl = lightEngine.getLayerListener(LightLayer.SKY).getDataLayerData(sectionPos);

                // empty block light is meaningless, voxy ingests null block light anyway
                // so send null instead of a dead 2048-byte array
                byte[] blData = (bl != null && !isAllZero(bl.getData())) ? bl.getData().clone() : null;

                sections.add(new LODDataPayload.SectionData(
                    sectionY,
                    states,
                    biomes,
                    blData,
                    sl != null ? sl.getData().clone() : null
                ));
            } catch (Throwable ignored) {
            } finally {
                statesRaw.release();
                biomesRaw.release();
            }
        }

        return sections;
    }

    // true if every byte is zero, used to drop empty block-light arrays
    private static boolean isAllZero(byte[] data) {
        if (data == null) return true;
        for (byte b : data) {
            if (b != 0) return false;
        }
        return true;
    }

    private static void sendSectionsInBatches(ServerPlayer player, ResourceKey<Level> dimension, ChunkPos pos, int minY, List<LODDataPayload.SectionData> sections) {
        List<LODDataPayload.SectionData> batch = new ArrayList<>();
        int batchBytes = PACKET_OVERHEAD_BYTES;

        for (LODDataPayload.SectionData sd : sections) {
            int sectionBytes = sd.sizeBytes();

            if (!batch.isEmpty() && batchBytes + sectionBytes > MAX_PACKET_BYTES) {
                PacketDistributor.sendToPlayer(player, new LODDataPayload(dimension, pos, minY, batch));
                batch = new ArrayList<>();
                batchBytes = PACKET_OVERHEAD_BYTES;
            }

            batch.add(sd);
            batchBytes += sectionBytes;
        }

        if (!batch.isEmpty()) {
            PacketDistributor.sendToPlayer(player, new LODDataPayload(dimension, pos, minY, batch));
        }
    }

    public static void sendHandshake(ServerPlayer player) {
        PacketDistributor.sendToPlayer(player, new HandshakePayload(true, LodStreamingService.PROTOCOL_VERSION));
    }
}
