package me.cortex.voxy.client.lod;

import io.airlift.compress.zstd.ZstdDecompressor;
import me.cortex.voxy.client.VoxyClient;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.runtime.VoxyRuntime;
import me.cortex.voxy.client.world.WorldIdentifier;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.other.Mapper;
import me.cortex.voxy.network.TransportPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.io.DataInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.CRC32C;

/** Shared Voxy protocol client over automatic direct TCP or the negotiated Minecraft bridge. */
public final class ClientLodNetwork {
    private static final int MAGIC = 0x32595856; // ASCII VXY2 in little-endian
    private static final short VERSION = TransportPayload.PROTOCOL_VERSION;
    private static final int MAX_PAYLOAD = 16 * 1024 * 1024;

    private static final short C_HELLO = 0x0001;
    private static final short C_SUBSCRIBE = 0x0002;
    private static final short C_PING = 0x0003;
    private static final short C_BLOCK_PROPERTIES = 0x0004;
    private static final short C_CREDIT = 0x0005;
    private static final short S_HELLO = (short) 0x8001;
    private static final short S_MAPPING_DELTA = (short) 0x8002;
    private static final short S_SECTION = (short) 0x8003;
    private static final short S_INVALIDATE = (short) 0x8004;
    private static final short S_PONG = (short) 0x8005;
    private static final short S_RESOLUTION = (short) 0x8006;
    private static final short S_ERROR = (short) 0x80ff;
    private static final byte RESOLUTION_NO_UPDATE = 1;

    private static final int CAP_BLOCK_PROPERTIES = 1;
    private static final int MAX_INBOUND_FRAMES = 512;
    private static final int MAX_INBOUND_KIB = 128 * 1024;
    private static final int MAX_UPDATES_PER_TICK = 96;
    private static final int MAX_BLOCK_ID = 1 << 20;
    private static final int MAX_BIOME_ID = 1 << 9;
    private static final int MAX_CANONICAL_LENGTH = 4096;
    private static final int MAX_SECTION_ENCODING = 512 * 1024;
    private static final int MAX_MAPPINGS_PER_FRAME = 256;
    private static final int SUBSCRIPTION_BATCH = 256;
    private static final int PREPARED_SECTION_KIB =
            ((WorldSection.SECTION_VOLUME * Long.BYTES + 1023) >>> 10) + 1;
    private static final long BRIDGE_CREDIT = 512L * 1024;
    private static final long DIRECT_CREDIT = 32L * 1024 * 1024;

    private static final ArrayBlockingQueue<QueuedInbound> INBOUND = new ArrayBlockingQueue<>(MAX_INBOUND_FRAMES);
    private static final Semaphore INBOUND_MEMORY = new Semaphore(MAX_INBOUND_KIB);
    private static final Set<Long> DESIRED_SECTIONS = ConcurrentHashMap.newKeySet();
    private static final Set<Long> RESOLVED_SECTIONS = ConcurrentHashMap.newKeySet();
    private static final Set<Long> PENDING_SECTIONS = ConcurrentHashMap.newKeySet();
    private static final ConcurrentLinkedQueue<Long> SUBSCRIPTION_CHANGES = new ConcurrentLinkedQueue<>();
    private static final ConcurrentHashMap<Long, DemandPriority> DEMAND_PRIORITIES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Long, Long> REQUESTED_REVISIONS = new ConcurrentHashMap<>();
    private static final ConcurrentLinkedQueue<Long> RETRY_SECTIONS = new ConcurrentLinkedQueue<>();
    private static final AtomicLong DEMAND_SEQUENCE = new AtomicLong();
    private static final AtomicLong SESSION = new AtomicLong();
    private static final AtomicLong CONNECTION_SEQUENCE = new AtomicLong();
    private static final Object LIFECYCLE_LOCK = new Object();

    private static final ArrayBlockingQueue<long[]> SECTION_BUFFERS = new ArrayBlockingQueue<>(16);
    private static final ThreadLocal<ZstdScratch> ZSTD = ThreadLocal.withInitial(ZstdScratch::new);
    /** Latest revisions observed in this client process; durable revisions live on WorldSection. */
    private static final ConcurrentHashMap<Long, Long> SECTION_REVISIONS = new ConcurrentHashMap<>();
    private static int[] blockTranslations = new int[256];
    private static int[] biomeTranslations = new int[64];
    private static String[] blockNames = new String[256];
    private static String[] biomeNames = new String[64];

    private static volatile Thread networkThread;
    private static volatile Connection connection;
    private static volatile boolean serverConnected;
    private static boolean authorityPrepared;
    private static boolean recoveryNeeded;
    private static volatile String activeDimension;
    private static long helloServerInstance = Long.MIN_VALUE;
    private static int helloBlockEpoch;
    private static int helloBiomeEpoch;
    private static volatile WorldEngine activeWorld;
    private static volatile double cameraX;
    private static volatile double cameraZ;

    static {
        Arrays.fill(blockTranslations, -1);
        Arrays.fill(biomeTranslations, -1);
    }

    private ClientLodNetwork() {}

    public static void init(IEventBus modBus) {
        Logger.info("Automatic Rust LOD client initializing");
        ClientLodTransport.register(modBus);
        NeoForge.EVENT_BUS.register(ClientLodNetwork.class);
    }

    @SubscribeEvent
    public static void onDisconnect(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientLodDebug.minecraftDisconnect();
        disconnect();
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        tick();
    }

    public static void tick() {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        ClientLodDebug.tick();
        if (level != null && minecraft.player != null && recoveryNeeded) {
            try {
                IGetVoxyRenderSystem renderer = (IGetVoxyRenderSystem) minecraft.levelRenderer;
                renderer.voxy$shutdownRenderer();
                if (VoxyClient.getRuntime() == null) VoxyClient.createRuntime();
                renderer.voxy$createRenderer();
                recoveryNeeded = false;
            } catch (RuntimeException exception) {
                Logger.error("Retrying Voxy recovery after a failed cache reset", exception);
                return;
            }
        }
        if (level == null || minecraft.player == null || VoxyClient.getRuntime() == null) {
            if (networkThread != null) disconnect();
            return;
        }
        cameraX = minecraft.gameRenderer.getMainCamera().getPosition().x;
        cameraZ = minecraft.gameRenderer.getMainCamera().getPosition().z;

        String dimension = level.dimension().location().toString();
        if (networkThread == null || !networkThread.isAlive()) {
            start(dimension);
        }

        drainInbound(level);
    }

    public static void disconnect() {
        synchronized (LIFECYCLE_LOCK) {
            SESSION.incrementAndGet();
            Connection old = connection;
            connection = null;
            if (old != null) old.close();
            Thread oldThread = networkThread;
            networkThread = null;
            if (oldThread != null) oldThread.interrupt();
            resetClientState(null);
        }
    }

    public static void subscribe(long key) {
        if (DESIRED_SECTIONS.add(key)) {
            DEMAND_PRIORITIES.putIfAbsent(key,
                    demandPriority(key, 2, DEMAND_SEQUENCE.getAndIncrement()));
            RESOLVED_SECTIONS.remove(key);
            SUBSCRIPTION_CHANGES.add(key);
        }
    }

    /** A missing coarse root blocks all coverage below it. */
    public static void prioritizeCoverage(long key) {
        prioritize(key, 0);
    }

    /** A GPU traversal requested this child because its current fallback is visibly too coarse. */
    public static void prioritizeVisible(long key) {
        prioritize(key, 1);
    }

    private static void prioritize(long key, int priorityClass) {
        DEMAND_PRIORITIES.compute(key, (ignored, current) -> {
            long age = current == null ? DEMAND_SEQUENCE.getAndIncrement() : current.age;
            DemandPriority next = demandPriority(key, priorityClass, age);
            if (current != null && current.priorityClass <= priorityClass) return current;
            if (DESIRED_SECTIONS.contains(key)) SUBSCRIPTION_CHANGES.add(key);
            return next;
        });
    }

    public static void unsubscribe(long key) {
        RESOLVED_SECTIONS.remove(key);
        PENDING_SECTIONS.remove(key);
        REQUESTED_REVISIONS.remove(key);
        DEMAND_PRIORITIES.remove(key);
        if (DESIRED_SECTIONS.remove(key)) SUBSCRIPTION_CHANGES.add(key);
    }

    public static void resetDemand() {
        DESIRED_SECTIONS.clear();
        RESOLVED_SECTIONS.clear();
        PENDING_SECTIONS.clear();
        SUBSCRIPTION_CHANGES.clear();
        DEMAND_PRIORITIES.clear();
        REQUESTED_REVISIONS.clear();
        RETRY_SECTIONS.clear();
    }

    /** True once the authoritative server has answered the current subscription. */
    public static boolean isSectionResolved(long key) {
        return RESOLVED_SECTIONS.contains(key);
    }

    static int debugInboundFrames() { return INBOUND.size(); }
    static int debugInboundKiB() { return MAX_INBOUND_KIB - INBOUND_MEMORY.availablePermits(); }
    static int debugDesiredSections() { return DESIRED_SECTIONS.size(); }
    static int debugPendingSections() { return PENDING_SECTIONS.size(); }

    private static void start(String dimension) {
        synchronized (LIFECYCLE_LOCK) {
            disconnect();
            activeDimension = dimension;
            long session = SESSION.incrementAndGet();
            ClientLodDebug.networkStart(session, dimension);
            Thread thread = new Thread(() -> runNetwork(session), "Voxy Rust LOD client");
            thread.setDaemon(true);
            networkThread = thread;
            thread.start();
        }
    }

    private static void runNetwork(long session) {
        long retryMillis = 500;
        boolean loggedFailure = false;
        while (SESSION.get() == session) {
            Connection active = null;
            try {
                Connection next = new Connection(session);
                active = next;
                if (SESSION.get() != session) {
                    next.close();
                    return;
                }
                connection = next;
                retryMillis = 500;
                loggedFailure = false;
                next.readLoop();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                return;
            } catch (IOException | ProtocolException exception) {
                if (SESSION.get() != session) return;
                ClientLodDebug.networkFailure(exception);
                if (!loggedFailure) {
                    Logger.warn("Rust LOD service unavailable (will retry): " + exception.getMessage());
                    loggedFailure = true;
                }
            } finally {
                if (active != null && connection == active) {
                    active.close();
                    connection = null;
                    serverConnected = false;
                }
            }

            try {
                Thread.sleep(retryMillis);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                return;
            }
            retryMillis = Math.min(10_000, retryMillis * 2);
        }
    }

    private static void drainInbound(ClientLevel level) {
        WorldIdentifier identifier = WorldIdentifier.of(level);
        VoxyRuntime runtime = VoxyClient.getRuntime();
        WorldEngine world = identifier == null || runtime == null ? null : runtime.getOrCreate(identifier);
        if (world == null) return;
        activeWorld = world;
        activeDimension = level.dimension().location().toString();

        int updates = 0;
        while (updates < MAX_UPDATES_PER_TICK) {
            QueuedInbound queued = INBOUND.poll();
            if (queued == null) break;
            try {
                Inbound message = queued.message;
                if (message instanceof ServerError error) {
                    Logger.warn("Rust LOD service error " + error.code + ": " + error.text);
                    continue;
                }
                Connection current = connection;
                if (current == null || message.session() != current.generation) continue;
                if (message instanceof ServerHello hello) {
                    if (applyHello(identifier, hello)) return;
                } else if (message instanceof MappingDelta mappings) {
                    applyMappings(world.getMapper(), mappings);
                    // Mapping registration touches Minecraft registries and the durable local
                    // catalog. Keep it to one bounded server batch per client tick.
                    return;
                } else if (message instanceof PreparedSection section) {
                    applySection(world, section);
                    updates++;
                } else if (message instanceof Invalidation invalidation) {
                    applyInvalidation(world, invalidation);
                    updates++;
                } else if (message instanceof ResolutionBatch resolution) {
                    applyResolution(world, resolution);
                    updates += resolution.keys.length;
                }
            } catch (RuntimeException exception) {
                Logger.error("Failed to apply Rust LOD message", exception);
                // A partially applied mapping batch cannot be recovered on this connection:
                // the server has already advanced its mapping cursor. Reconnect so HELLO and
                // every mapping are replayed before any more sections can be accepted.
                closeCurrentConnection();
                return;
            } finally {
                releaseInbound(queued);
            }
        }
    }

    private static boolean applyHello(WorldIdentifier identifier, ServerHello hello) {
        serverConnected = false;
        boolean restartInstance = !authorityPrepared;
        boolean resetSections = false;
        if (hello.serverInstance != helloServerInstance) {
            if (VoxyRuntime.serverCacheNeedsReset(identifier, hello.serverInstance)) {
                restartInstance = true;
                resetSections = true;
            } else {
                VoxyRuntime.recordServerIdentity(identifier, hello.serverInstance);
            }
        }
        if (restartInstance) {
            Minecraft minecraft = Minecraft.getInstance();
            IGetVoxyRenderSystem renderer = (IGetVoxyRenderSystem) minecraft.levelRenderer;
            recoveryNeeded = true;
            try {
                renderer.voxy$shutdownRenderer();
                VoxyClient.shutdownRuntime();
                if (resetSections) {
                    VoxyRuntime.resetServerCache(identifier, hello.serverInstance);
                    Logger.warn("Reset stale Voxy sections for a new Rust server catalog");
                }
                authorityPrepared = true;
            } finally {
                // Always restore the client even if cache cleanup fails.
                if (VoxyClient.getRuntime() == null) VoxyClient.createRuntime();
                renderer.voxy$createRenderer();
                recoveryNeeded = false;
            }
        }
        boolean identityChanged = hello.serverInstance != helloServerInstance
                || hello.blockEpoch != helloBlockEpoch || hello.biomeEpoch != helloBiomeEpoch;
        if (identityChanged) {
            SECTION_REVISIONS.clear();
            resetMappings();
            helloServerInstance = hello.serverInstance;
            helloBlockEpoch = hello.blockEpoch;
            helloBiomeEpoch = hello.biomeEpoch;
        }
        Logger.info("Connected to Rust LOD service " + Long.toUnsignedString(hello.serverInstance));
        ClientLodDebug.serverHello(hello.serverInstance, restartInstance, resetSections,
                hello.blockEpoch, hello.biomeEpoch);
        serverConnected = true;
        return restartInstance;
    }

    private static void applyMappings(Mapper mapper, MappingDelta delta) {
        long started = ClientLodDebug.timer();
        List<BlockProperty> properties = new ArrayList<>(delta.blocks.size());
        for (RemoteBlock block : delta.blocks) {
            ensureBlockTranslationCapacity(block.id);
            if (blockNames[block.id] != null && !blockNames[block.id].equals(block.canonical)) {
                rejectConnection("Server changed block mapping " + block.id + " without changing its epoch");
            }
            BlockState state = parseCanonicalState(block.canonical);
            int localId = mapper.getIdForBlockState(state, false);
            if (localId < 0 || localId >= MAX_BLOCK_ID) {
                rejectConnection("Local Voxy block catalog exceeds its 20-bit format");
            }
            blockTranslations[block.id] = localId;
            blockNames[block.id] = block.canonical;
            int actualOpacity = mapper.getBlockStateOpacity(localId);
            properties.add(new BlockProperty(block.id, (byte) actualOpacity));
            if (actualOpacity != Byte.toUnsignedInt(block.opacity)) {
                Logger.warn("Correcting Rust opacity for " + block.canonical + " from "
                        + Byte.toUnsignedInt(block.opacity) + " to " + actualOpacity);
            }
        }

        for (RemoteBiome biome : delta.biomes) {
            ensureBiomeTranslationCapacity(biome.id);
            if (biomeNames[biome.id] != null && !biomeNames[biome.id].equals(biome.name)) {
                rejectConnection("Server changed biome mapping " + biome.id + " without changing its epoch");
            }
            String name = validBiomeOrFallback(biome.name);
            int localId = mapper.getIdForBiome(name, false);
            if (localId < 0 || localId >= MAX_BIOME_ID) {
                rejectConnection("Local Voxy biome catalog exceeds its 9-bit format");
            }
            biomeTranslations[biome.id] = localId;
            biomeNames[biome.id] = biome.name;
        }

        // One durability barrier for the complete delta prevents both main-thread fsync storms
        // and sections becoming visible before the mappings needed to decode them are durable.
        if (!delta.blocks.isEmpty() || !delta.biomes.isEmpty()) mapper.flushMappings();

        Connection current = connection;
        if (current != null && current.generation == delta.session && !properties.isEmpty()) {
            current.blockProperties.addAll(properties);
        }
        ClientLodDebug.mappingDelta(delta.blocks.size(), delta.biomes.size(), started);
    }

    private static void applySection(WorldEngine world, PreparedSection update) {
        PENDING_SECTIONS.remove(update.key);
        REQUESTED_REVISIONS.remove(update.key);
        if (!validWorldKey(update.key) || !DESIRED_SECTIONS.contains(update.key)) {
            ClientLodDebug.droppedUnsubscribed();
            return;
        }
        if (isOlderRevision(update.key, update.revision)) {
            ClientLodDebug.droppedRevision();
            return;
        }

        long started = ClientLodDebug.timer();
        try {
            RESOLVED_SECTIONS.add(update.key);
            world.replaceRemoteSection(update.key, update.revision, update.data,
                    update.nonEmptyChildren, update.nonEmptyBlockCount);
            SECTION_REVISIONS.put(update.key, update.revision);
            ClientLodDebug.sectionApplied();
        } finally { ClientLodDebug.sectionInstalled(started); }
    }

    private static void applyInvalidation(WorldEngine world, Invalidation invalidation) {
        PENDING_SECTIONS.remove(invalidation.key);
        REQUESTED_REVISIONS.remove(invalidation.key);
        if (!validWorldKey(invalidation.key) || !DESIRED_SECTIONS.contains(invalidation.key)) {
            ClientLodDebug.droppedUnsubscribed();
            return;
        }
        if (isOlderRevision(invalidation.key, invalidation.revision)) {
            ClientLodDebug.droppedRevision();
            return;
        }
        RESOLVED_SECTIONS.add(invalidation.key);
        world.invalidateRemoteSection(invalidation.key, invalidation.revision);
        SECTION_REVISIONS.put(invalidation.key, invalidation.revision);
        ClientLodDebug.invalidationApplied();
    }

    private static void applyResolution(WorldEngine world, ResolutionBatch resolution) {
        int applied = 0;
        for (long key : resolution.keys) {
            PENDING_SECTIONS.remove(key);
            long requestedRevision = REQUESTED_REVISIONS.getOrDefault(key, -1L);
            REQUESTED_REVISIONS.remove(key);
            if (!validWorldKey(key) || !DESIRED_SECTIONS.contains(key)) {
                ClientLodDebug.droppedUnsubscribed();
                continue;
            }
            if (!publishResolution(world, key, requestedRevision != -1)) {
                // The metadata index was current but the demanded payload failed its checksum.
                // Re-resolve only this subscription with an unknown revision.
                SECTION_REVISIONS.remove(key);
                RETRY_SECTIONS.add(key);
                continue;
            }
            applied++;
        }
        ClientLodDebug.resolutionApplied(applied);
    }

    /** Publishes authority before the renderer callback can serialize the node's terminal bit. */
    static boolean publishResolution(WorldEngine world, long key, boolean requireCached) {
        RESOLVED_SECTIONS.add(key);
        boolean published = false;
        try {
            published = world.refreshResolvedRemoteSection(key, requireCached);
            return published;
        } finally {
            if (!published) RESOLVED_SECTIONS.remove(key);
        }
    }

    private static boolean isOlderRevision(long key, long revision) {
        Long current = SECTION_REVISIONS.get(key);
        return current != null && Long.compareUnsigned(revision, current) < 0;
    }

    private static int translated(int[] translations, int serverId) {
        return serverId >= 0 && serverId < translations.length ? translations[serverId] : -1;
    }

    private static boolean validWorldKey(long key) {
        return (key & 0xf) == 0 && WorldEngine.getLevel(key) <= WorldEngine.MAX_LOD_LAYER;
    }

    private static long knownRevision(long key) {
        Long observed = SECTION_REVISIONS.get(key);
        if (observed != null) return observed;
        WorldEngine world = activeWorld;
        if (world == null || !world.isLive()) return -1;
        try {
            return world.storage.getRemoteRevision(key);
        } catch (RuntimeException exception) {
            return -1;
        }
    }

    private static DemandPriority demandPriority(long key, int priorityClass, long age) {
        int level = WorldEngine.getLevel(key);
        double width = 32L << level;
        double centerX = (WorldEngine.getX(key) + 0.5) * width;
        double centerZ = (WorldEngine.getZ(key) + 0.5) * width;
        double dx = centerX - cameraX;
        double dz = centerZ - cameraZ;
        double distanceSquared = dx * dx + dz * dz;
        double projected = width * width / Math.max(1.0, distanceSquared);
        return new DemandPriority(priorityClass, projected, distanceSquared, age);
    }

    private static void rejectConnection(String reason) {
        closeCurrentConnection();
        throw new IllegalStateException(reason);
    }

    private static void closeCurrentConnection() {
        Connection current = connection;
        if (current != null) {
            current.close();
            if (connection == current) connection = null;
        }
        serverConnected = false;
    }

    private static BlockState parseCanonicalState(String canonical) {
        try {
            if (canonical.isEmpty() || canonical.length() > MAX_CANONICAL_LENGTH) return Blocks.BARRIER.defaultBlockState();
            int bracket = canonical.indexOf('[');
            String blockName = bracket < 0 ? canonical : canonical.substring(0, bracket);
            if (bracket >= 0 && (!canonical.endsWith("]") || canonical.indexOf('[', bracket + 1) >= 0)) {
                return Blocks.BARRIER.defaultBlockState();
            }

            ResourceLocation id = ResourceLocation.tryParse(blockName);
            if (id == null) return Blocks.BARRIER.defaultBlockState();
            Optional<Block> block = BuiltInRegistries.BLOCK.getOptional(id);
            if (block.isEmpty()) {
                Logger.warn("Unknown server block " + blockName + "; rendering it as barrier");
                return Blocks.BARRIER.defaultBlockState();
            }

            BlockState state = block.get().defaultBlockState();
            if (bracket < 0 || bracket == canonical.length() - 2) return state;
            String body = canonical.substring(bracket + 1, canonical.length() - 1);
            HashSet<String> seen = new HashSet<>();
            for (String assignment : body.split(",", -1)) {
                int equals = assignment.indexOf('=');
                if (equals <= 0 || equals == assignment.length() - 1 || assignment.indexOf('=', equals + 1) >= 0) {
                    return Blocks.BARRIER.defaultBlockState();
                }
                String propertyName = assignment.substring(0, equals);
                if (!seen.add(propertyName)) return Blocks.BARRIER.defaultBlockState();
                Property<?> property = state.getBlock().getStateDefinition().getProperty(propertyName);
                if (property == null) return Blocks.BARRIER.defaultBlockState();
                Optional<?> value = property.getValue(assignment.substring(equals + 1));
                if (value.isEmpty()) return Blocks.BARRIER.defaultBlockState();
                state = setProperty(state, property, value.get());
            }
            return state;
        } catch (RuntimeException exception) {
            Logger.warn("Invalid server block state " + canonical + "; rendering it as barrier");
            return Blocks.BARRIER.defaultBlockState();
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static BlockState setProperty(BlockState state, Property property, Object value) {
        return state.setValue(property, (Comparable) value);
    }

    private static String validBiomeOrFallback(String name) {
        ResourceLocation id = ResourceLocation.tryParse(name);
        ClientLevel level = Minecraft.getInstance().level;
        if (id != null && level != null
                && level.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.BIOME)
                .getOptional(id).isPresent()) {
            return id.toString();
        }
        Logger.warn("Unknown server biome " + name + "; using minecraft:plains");
        return "minecraft:plains";
    }

    private static void ensureBlockTranslationCapacity(int id) {
        if (id < 0 || id >= MAX_BLOCK_ID) throw new IllegalArgumentException("Server block ID out of range");
        if (id < blockTranslations.length) return;
        int oldLength = blockTranslations.length;
        blockTranslations = Arrays.copyOf(blockTranslations, Math.min(MAX_BLOCK_ID,
                Math.max(id + 1, oldLength * 2)));
        blockNames = Arrays.copyOf(blockNames, blockTranslations.length);
        Arrays.fill(blockTranslations, oldLength, blockTranslations.length, -1);
    }

    private static void ensureBiomeTranslationCapacity(int id) {
        if (id < 0 || id >= MAX_BIOME_ID) throw new IllegalArgumentException("Server biome ID out of range");
        if (id < biomeTranslations.length) return;
        int oldLength = biomeTranslations.length;
        biomeTranslations = Arrays.copyOf(biomeTranslations, Math.min(MAX_BIOME_ID,
                Math.max(id + 1, oldLength * 2)));
        biomeNames = Arrays.copyOf(biomeNames, biomeTranslations.length);
        Arrays.fill(biomeTranslations, oldLength, biomeTranslations.length, -1);
    }

    private static void resetClientState(String dimension) {
        serverConnected = false;
        authorityPrepared = false;
        recoveryNeeded = false;
        activeDimension = dimension;
        helloServerInstance = Long.MIN_VALUE;
        helloBlockEpoch = 0;
        helloBiomeEpoch = 0;
        activeWorld = null;
        SECTION_REVISIONS.clear();
        RESOLVED_SECTIONS.clear();
        PENDING_SECTIONS.clear();
        REQUESTED_REVISIONS.clear();
        RETRY_SECTIONS.clear();
        QueuedInbound queued;
        while ((queued = INBOUND.poll()) != null) releaseInbound(queued);
        resetMappings();
        ClientLodDebug.reset();
    }

    private static long[] borrowSectionBuffer() {
        long[] data = SECTION_BUFFERS.poll();
        return data == null ? new long[WorldSection.SECTION_VOLUME] : data;
    }

    private static void recycleSectionBuffer(long[] data) {
        if (data.length == WorldSection.SECTION_VOLUME) SECTION_BUFFERS.offer(data);
    }

    private static void releaseInbound(QueuedInbound queued) {
        Inbound message = queued.message;
        if (message instanceof PreparedSection section) recycleSectionBuffer(section.data);
        INBOUND_MEMORY.release(queued.memoryKib);

        if (message instanceof ServerHello hello) hello.completion.countDown();
        else if (message instanceof MappingDelta mappings) mappings.completion.countDown();

        Connection current = connection;
        if (queued.creditBytes != 0 && current != null && message.session() == current.generation) {
            current.grantCredit(queued.creditBytes);
            ClientLodDebug.credit(queued.creditBytes);
        }
    }

    private static void resetMappings() {
        blockTranslations = new int[256];
        biomeTranslations = new int[64];
        blockNames = new String[256];
        biomeNames = new String[64];
        Arrays.fill(blockTranslations, -1);
        Arrays.fill(biomeTranslations, -1);
    }

    private static final class Connection {
        private final long session;
        private final long generation;
        private final ClientLodTransport transport;
        private final DataInputStream input;
        private final BufferedOutputStream output;
        private final ConcurrentLinkedQueue<BlockProperty> blockProperties = new ConcurrentLinkedQueue<>();
        private final AtomicLong creditToGrant = new AtomicLong();
        private final Thread writer;
        private volatile boolean open = true;

        private Connection(long session) throws IOException {
            this.session = session;
            this.generation = CONNECTION_SEQUENCE.incrementAndGet();
            this.transport = ClientLodTransport.open();
            try {
                this.input = new DataInputStream(this.transport.input());
                this.output = new BufferedOutputStream(this.transport.output(), 256 * 1024);
                writeFrame(C_HELLO, encodeHello());
                writeFrame(C_CREDIT, littleBuffer(8)
                        .putLong(this.transport.direct() ? DIRECT_CREDIT : BRIDGE_CREDIT).array());
            } catch (IOException | RuntimeException exception) {
                try { this.transport.close(); } catch (IOException ignored) {}
                throw exception;
            }

            this.writer = new Thread(this::writeLoop, "Voxy Rust LOD writer");
            this.writer.setDaemon(true);
            this.writer.start();
            Logger.info("Using Voxy " + (this.transport.direct() ? "direct transport at " : "Minecraft transport over ")
                    + this.transport.description());
            ClientLodDebug.transportOpen(this.transport.direct(), this.transport.description());
        }

        private static byte[] encodeHello() {
            return littleBuffer(4).putInt(CAP_BLOCK_PROPERTIES).array();
        }

        private void readLoop() throws IOException, ProtocolException, InterruptedException {
            boolean receivedHello = false;
            while (this.open && SESSION.get() == this.session) {
                Frame frame = readFrame(this.input);
                ClientLodDebug.rustFrame(frame.type, frame.payload.length);
                if (!receivedHello && frame.type != S_HELLO && frame.type != S_ERROR) {
                    throw new ProtocolException("Server did not begin with HELLO");
                }
                boolean sectionFrame = frame.type == S_SECTION;
                int memoryKib = 0;
                long[] sectionData = null;
                boolean memoryReserved = false;
                boolean transferred = false;
                try {
                    if (sectionFrame) {
                        memoryKib = PREPARED_SECTION_KIB;
                        INBOUND_MEMORY.acquire(memoryKib);
                        memoryReserved = true;
                        sectionData = borrowSectionBuffer();
                    }

                    int[] blocks = sectionFrame ? blockTranslations : null;
                    int[] biomes = sectionFrame ? biomeTranslations : null;
                    long started = sectionFrame ? ClientLodDebug.timer() : 0;
                    Inbound decoded = decode(this.generation, frame, blocks, biomes, sectionData);
                    if (sectionFrame) ClientLodDebug.sectionPrepared(started);
                    if (decoded == null) continue;
                    if (decoded instanceof ServerHello) receivedHello = true;

                    if (!sectionFrame) {
                        memoryKib = Math.min(MAX_INBOUND_KIB,
                                Math.max(1, (frame.payload.length * 2 + 1023) >>> 10));
                        INBOUND_MEMORY.acquire(memoryKib);
                        memoryReserved = true;
                    }
                    int creditBytes = frame.type == S_SECTION || frame.type == S_INVALIDATE
                            || frame.type == S_RESOLUTION
                            ? frame.payload.length + 16 : 0;
                    INBOUND.put(new QueuedInbound(decoded, memoryKib, creditBytes));
                    transferred = true;

                    if (decoded instanceof ServerHello hello) hello.completion.await();
                    else if (decoded instanceof MappingDelta mappings) mappings.completion.await();
                } finally {
                    if (!transferred && memoryReserved) {
                        if (sectionData != null) recycleSectionBuffer(sectionData);
                        INBOUND_MEMORY.release(memoryKib);
                    }
                }
            }
        }

        private void writeLoop() {
            HashSet<Long> sent = new HashSet<>();
            PriorityQueue<QueuedDemand> queued = new PriorityQueue<>();
            ArrayDeque<Long> queuedRemovals = new ArrayDeque<>();
            String sentDimension = null;
            long lastPing = System.nanoTime();
            try {
                while (this.open && SESSION.get() == this.session) {
                    long credit = this.creditToGrant.getAndSet(0);
                    if (credit != 0) {
                        writeFrame(C_CREDIT, littleBuffer(8).putLong(credit).array());
                    }

                    String dimension = activeDimension;
                    if (serverConnected && dimension != null && !dimension.equals(sentDimension)) {
                        sent.clear();
                        queued.clear();
                        queuedRemovals.clear();
                        for (long key : DESIRED_SECTIONS) {
                            DemandPriority priority = DEMAND_PRIORITIES.get(key);
                            if (priority != null) queued.add(new QueuedDemand(key, priority));
                        }
                        sentDimension = dimension;
                    }

                    if (serverConnected && sentDimension != null) {
                        Long changed;
                        while ((changed = SUBSCRIPTION_CHANGES.poll()) != null) {
                            DemandPriority priority = DEMAND_PRIORITIES.get(changed);
                            if (DESIRED_SECTIONS.contains(changed) && !sent.contains(changed)
                                    && priority != null) {
                                queued.add(new QueuedDemand(changed, priority));
                            } else if (!DESIRED_SECTIONS.contains(changed) && sent.contains(changed)) {
                                queuedRemovals.add(changed);
                            }
                        }
                        List<Subscription> additions = new ArrayList<>(SUBSCRIPTION_BATCH);
                        List<Long> removals = new ArrayList<>(SUBSCRIPTION_BATCH);
                        while (additions.size() + removals.size() <= SUBSCRIPTION_BATCH - 2) {
                            Long key = RETRY_SECTIONS.poll();
                            if (key == null) break;
                            if (!DESIRED_SECTIONS.contains(key)) continue;
                            if (!sent.contains(key)) {
                                DemandPriority priority = DEMAND_PRIORITIES.get(key);
                                if (priority != null) queued.add(new QueuedDemand(key, priority));
                                continue;
                            }
                            removals.add(key);
                            additions.add(new Subscription(key, -1));
                            REQUESTED_REVISIONS.put(key, -1L);
                            PENDING_SECTIONS.add(key);
                        }
                        while (!queuedRemovals.isEmpty() && removals.size() < SUBSCRIPTION_BATCH) {
                            long key = queuedRemovals.removeFirst();
                            if (sent.remove(key)) {
                                PENDING_SECTIONS.remove(key);
                                removals.add(key);
                            }
                        }
                        while (additions.size() + removals.size() < SUBSCRIPTION_BATCH
                                && !queued.isEmpty()) {
                            QueuedDemand candidate = queued.remove();
                            DemandPriority current = DEMAND_PRIORITIES.get(candidate.key);
                            if (!candidate.priority.equals(current)
                                    || !DESIRED_SECTIONS.contains(candidate.key)
                                    || !sent.add(candidate.key)) continue;
                            PENDING_SECTIONS.add(candidate.key);
                            long revision = knownRevision(candidate.key);
                            REQUESTED_REVISIONS.put(candidate.key, revision);
                            additions.add(new Subscription(candidate.key, revision));
                        }
                        if (!additions.isEmpty() || !removals.isEmpty()) {
                            writeFrame(C_SUBSCRIBE, encodeSubscriptions(sentDimension, additions, removals));
                            ClientLodDebug.subscriptionBatch(additions.size(), removals.size());
                        }
                    }

                    List<BlockProperty> batch = new ArrayList<>(2048);
                    BlockProperty property;
                    while (batch.size() < 2048 && (property = this.blockProperties.poll()) != null) batch.add(property);
                    if (!batch.isEmpty()) writeFrame(C_BLOCK_PROPERTIES, encodeBlockProperties(batch));

                    long now = System.nanoTime();
                    if (now - lastPing >= TimeUnit.SECONDS.toNanos(10)) {
                        ByteBuffer ping = littleBuffer(8).putLong(now);
                        writeFrame(C_PING, ping.array());
                        lastPing = now;
                    }
                    Thread.sleep(25);
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } catch (IOException exception) {
                this.close();
            }
        }

        private void grantCredit(long bytes) {
            this.creditToGrant.addAndGet(bytes);
        }

        private synchronized void writeFrame(short type, byte[] payload) throws IOException {
            if (payload.length > MAX_PAYLOAD) throw new IOException("Frame too large");
            CRC32C crc = new CRC32C();
            crc.update(payload, 0, payload.length);
            ByteBuffer header = littleBuffer(16);
            header.putInt(MAGIC).putShort(VERSION).putShort(type).putInt(payload.length).putInt((int) crc.getValue());
            this.output.write(header.array());
            this.output.write(payload);
            this.output.flush();
        }

        private void close() {
            if (!this.open) return;
            this.open = false;
            try { this.transport.close(); } catch (IOException ignored) {}
            if (Thread.currentThread() != this.writer) this.writer.interrupt();
        }
    }

    private static Frame readFrame(DataInputStream input) throws IOException, ProtocolException {
        byte[] rawHeader = new byte[16];
        try {
            input.readFully(rawHeader);
        } catch (EOFException exception) {
            throw new EOFException("Rust LOD service closed the connection");
        }
        ByteBuffer header = littleBuffer(rawHeader);
        if (header.getInt() != MAGIC) throw new ProtocolException("Bad frame magic");
        if (header.getShort() != VERSION) throw new ProtocolException("Unsupported frame version");
        short type = header.getShort();
        int length = header.getInt();
        int expectedCrc = header.getInt();
        if (length < 0 || length > MAX_PAYLOAD) throw new ProtocolException("Invalid frame length");
        byte[] payload = new byte[length];
        input.readFully(payload);
        CRC32C crc = new CRC32C();
        crc.update(payload, 0, payload.length);
        if ((int) crc.getValue() != expectedCrc) throw new ProtocolException("Frame CRC32C mismatch");
        return new Frame(type, payload);
    }

    private static Inbound decode(long session, Frame frame, int[] blocks, int[] biomes,
                                  long[] sectionData) throws ProtocolException {
        ByteBuffer input = littleBuffer(frame.payload);
        Inbound result;
        switch (frame.type) {
            case S_HELLO -> {
                requireRemaining(input, 24);
                long server = input.getLong();
                int flags = input.getInt();
                int maxLod = Byte.toUnsignedInt(input.get());
                requireZero(input.get(), input.get(), input.get());
                int blockEpoch = input.getInt();
                int biomeEpoch = input.getInt();
                if (maxLod != WorldEngine.MAX_LOD_LAYER) throw new ProtocolException("Server max LOD is incompatible");
                result = new ServerHello(session, server, flags, blockEpoch, biomeEpoch,
                        new CountDownLatch(1));
            }
            case S_MAPPING_DELTA -> result = decodeMappings(session, input);
            case S_SECTION -> {
                if (blocks == null || biomes == null || sectionData == null) {
                    throw new IllegalStateException("Section decoder has no prepared output buffer");
                }
                result = decodeSection(session, input, blocks, biomes, sectionData);
            }
            case S_INVALIDATE -> {
                requireRemaining(input, 24);
                long key = input.getLong();
                long revision = input.getLong();
                int reason = Byte.toUnsignedInt(input.get());
                requireZero(input.get(), input.get(), input.get(), input.get(), input.get(), input.get(), input.get());
                if (reason < 1 || reason > 3) throw new ProtocolException("Unknown invalidation reason");
                result = new Invalidation(session, key, revision, (byte) reason);
            }
            case S_RESOLUTION -> result = decodeResolution(session, input);
            case S_PONG -> {
                requireRemaining(input, 8);
                input.getLong();
                result = null;
            }
            case S_ERROR -> {
                requireRemaining(input, 4);
                int code = Short.toUnsignedInt(input.getShort());
                int length = Short.toUnsignedInt(input.getShort());
                result = new ServerError(session, code, getString(input, length, MAX_CANONICAL_LENGTH));
            }
            default -> throw new ProtocolException("Unknown server frame type " + Integer.toHexString(Short.toUnsignedInt(frame.type)));
        }
        if (input.hasRemaining()) throw new ProtocolException("Trailing bytes in frame");
        return result;
    }

    static ResolutionBatch decodeResolution(long session, ByteBuffer input) throws ProtocolException {
        requireRemaining(input, 4);
        byte status = input.get();
        requireZero(input.get());
        int count = Short.toUnsignedInt(input.getShort());
        // Status 2 was emitted by the first protocol-5 server and also means no payload. It
        // must never regain its old, destructive client interpretation.
        if ((status != RESOLUTION_NO_UPDATE && status != 2) || count == 0 || count > SUBSCRIPTION_BATCH
                || input.remaining() != count * Long.BYTES) {
            throw new ProtocolException("Invalid subscription resolution");
        }
        long[] keys = new long[count];
        for (int i = 0; i < count; i++) {
            long key = input.getLong();
            if (!validWorldKey(key)) throw new ProtocolException("Invalid resolved section key");
            keys[i] = key;
        }
        return new ResolutionBatch(session, keys);
    }

    private static MappingDelta decodeMappings(long session, ByteBuffer input) throws ProtocolException {
        requireRemaining(input, 4);
        long rawBlockCount = Integer.toUnsignedLong(input.getInt());
        if (rawBlockCount > MAX_MAPPINGS_PER_FRAME || rawBlockCount > input.remaining() / 8L) {
            throw new ProtocolException("Invalid mapping block count");
        }
        List<RemoteBlock> blocks = new ArrayList<>((int) rawBlockCount);
        for (int i = 0; i < rawBlockCount; i++) {
            requireRemaining(input, 8);
            long rawId = Integer.toUnsignedLong(input.getInt());
            int opacity = Byte.toUnsignedInt(input.get());
            byte reserved = input.get();
            int length = Short.toUnsignedInt(input.getShort());
            requireZero(reserved);
            if (rawId >= MAX_BLOCK_ID || opacity > 15) throw new ProtocolException("Invalid block mapping");
            blocks.add(new RemoteBlock((int) rawId, (byte) opacity,
                    getString(input, length, MAX_CANONICAL_LENGTH)));
        }

        requireRemaining(input, 4);
        long rawBiomeCount = Integer.toUnsignedLong(input.getInt());
        if (rawBiomeCount > MAX_MAPPINGS_PER_FRAME - rawBlockCount || rawBiomeCount > input.remaining() / 6L) {
            throw new ProtocolException("Invalid mapping biome count");
        }
        List<RemoteBiome> biomes = new ArrayList<>((int) rawBiomeCount);
        for (int i = 0; i < rawBiomeCount; i++) {
            requireRemaining(input, 6);
            long rawId = Integer.toUnsignedLong(input.getInt());
            int length = Short.toUnsignedInt(input.getShort());
            if (rawId >= MAX_BIOME_ID) throw new ProtocolException("Invalid biome mapping");
            biomes.add(new RemoteBiome((int) rawId, getString(input, length, MAX_CANONICAL_LENGTH)));
        }
        return new MappingDelta(session, blocks, biomes, new CountDownLatch(1));
    }

    static PreparedSection decodeSection(long session, ByteBuffer input, int[] blockMap,
                                         int[] biomeMap, long[] data) throws ProtocolException {
        requireRemaining(input, 24);
        long key = input.getLong();
        long revision = input.getLong();
        long rawLength = Integer.toUnsignedLong(input.getInt());
        int codec = Byte.toUnsignedInt(input.get());
        byte reserved0 = input.get();
        byte reserved1 = input.get();
        byte reserved2 = input.get();
        if (!validWorldKey(key) || rawLength < 12 || rawLength > MAX_SECTION_ENCODING
                || codec != 1 || input.remaining() == 0) {
            throw new ProtocolException("Invalid compressed section metadata");
        }
        requireZero(reserved0, reserved1, reserved2);

        ZstdScratch scratch = ZSTD.get();
        int compressedLength = input.remaining();
        int written;
        try {
            written = scratch.decompressor.decompress(input.array(),
                    input.arrayOffset() + input.position(), compressedLength,
                    scratch.bytes, 0, (int) rawLength);
        } catch (RuntimeException exception) {
            throw new ProtocolException("Invalid Zstd section payload", exception);
        }
        input.position(input.limit());
        if (written != rawLength) throw new ProtocolException("Wrong decompressed section length");

        ByteBuffer encoded = ByteBuffer.wrap(scratch.bytes, 0, written).order(ByteOrder.LITTLE_ENDIAN);
        requireRemaining(encoded, 12);
        int schema = Byte.toUnsignedInt(encoded.get());
        byte nonEmptyChildren = encoded.get();
        int bits = Byte.toUnsignedInt(encoded.get());
        byte encodingReserved = encoded.get();
        int paletteLength = Short.toUnsignedInt(encoded.getShort());
        short paletteReserved = encoded.getShort();
        long rawWordCount = Integer.toUnsignedLong(encoded.getInt());

        if (schema != 1 || encodingReserved != 0 || paletteReserved != 0
                || paletteLength < 1 || paletteLength > WorldSection.SECTION_VOLUME) {
            throw new ProtocolException("Invalid section metadata");
        }
        int expectedBits = paletteLength == 1 ? 0 : 32 - Integer.numberOfLeadingZeros(paletteLength - 1);
        long expectedWords = ((long) WorldSection.SECTION_VOLUME * expectedBits + 63) >>> 6;
        if (bits != expectedBits || rawWordCount != expectedWords) throw new ProtocolException("Invalid packed palette dimensions");
        long expectedRemaining = (long) paletteLength * 12 + rawWordCount * 8;
        if (expectedRemaining != encoded.remaining()) throw new ProtocolException("Invalid section payload length");
        if (data.length != WorldSection.SECTION_VOLUME) throw new IllegalArgumentException("Invalid section output buffer");

        long[] palette = new long[paletteLength];
        for (int i = 0; i < palette.length; i++) {
            long block = Integer.toUnsignedLong(encoded.getInt());
            long biome = Integer.toUnsignedLong(encoded.getInt());
            byte light = encoded.get();
            requireZero(encoded.get(), encoded.get(), encoded.get());
            if (block >= MAX_BLOCK_ID || biome >= MAX_BIOME_ID) throw new ProtocolException("Palette mapping ID out of range");
            int localBlock = translated(blockMap, (int) block);
            int localBiome = translated(biomeMap, (int) biome);
            if (localBlock < 0 || localBiome < 0) {
                throw new ProtocolException("LOD section references an unknown server mapping: "
                        + WorldEngine.pprintPos(key));
            }
            palette[i] = Mapper.composeMappingId(light, localBlock, localBiome);
        }

        boolean countNonAir = WorldEngine.getLevel(key) == 0;
        int nonEmptyBlockCount = 0;
        if (bits == 0) {
            long value = palette[0];
            Arrays.fill(data, value);
            if (countNonAir && !Mapper.isAir(value)) nonEmptyBlockCount = WorldSection.SECTION_VOLUME;
        } else {
            int wordsOffset = encoded.position();
            long mask = (1L << bits) - 1;
            for (int i = 0; i < data.length; i++) {
                long bit = (long) i * bits;
                int word = (int) (bit >>> 6);
                int shift = (int) (bit & 63);
                long index = encoded.getLong(wordsOffset + word * Long.BYTES) >>> shift;
                if (shift + bits > 64) {
                    index |= encoded.getLong(wordsOffset + (word + 1) * Long.BYTES) << (64 - shift);
                }
                int paletteIndex = (int) (index & mask);
                if (paletteIndex >= palette.length) {
                    throw new ProtocolException("LOD section contains an invalid packed palette index");
                }
                long value = palette[paletteIndex];
                data[i] = value;
                if (countNonAir && !Mapper.isAir(value)) nonEmptyBlockCount++;
            }
        }
        encoded.position(encoded.limit());
        return new PreparedSection(session, key, revision, nonEmptyChildren, nonEmptyBlockCount, data);
    }

    private static byte[] encodeSubscriptions(String name, List<Subscription> additions,
                                              List<Long> removals) throws IOException {
        byte[] dimension = name.getBytes(StandardCharsets.UTF_8);
        if (dimension.length > 1024) throw new IOException("Dimension name is too long");
        ByteBuffer output = littleBuffer(6 + dimension.length
                + additions.size() * 16 + removals.size() * 8);
        output.putShort((short) dimension.length).put(dimension)
                .putShort((short) additions.size()).putShort((short) removals.size());
        for (Subscription addition : additions) {
            output.putLong(addition.key).putLong(addition.knownRevision);
        }
        for (long removal : removals) output.putLong(removal);
        return output.array();
    }

    private static byte[] encodeBlockProperties(List<BlockProperty> properties) {
        ByteBuffer output = littleBuffer(4 + properties.size() * 8);
        output.putInt(properties.size());
        for (BlockProperty property : properties) {
            output.putInt(property.id).put(property.opacity).put((byte) 0).put((byte) 0).put((byte) 0);
        }
        return output.array();
    }

    private static String getString(ByteBuffer input, int length, int maxLength) throws ProtocolException {
        if (length < 0 || length > maxLength || length > input.remaining()) throw new ProtocolException("Invalid string length");
        ByteBuffer bytes = input.slice();
        bytes.limit(length);
        input.position(input.position() + length);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(bytes).toString();
        } catch (CharacterCodingException exception) {
            throw new ProtocolException("Invalid UTF-8");
        }
    }

    private static void requireRemaining(ByteBuffer input, int bytes) throws ProtocolException {
        if (bytes < 0 || input.remaining() < bytes) throw new ProtocolException("Truncated frame");
    }

    private static void requireZero(byte... bytes) throws ProtocolException {
        for (byte value : bytes) if (value != 0) throw new ProtocolException("Reserved protocol field is not zero");
    }

    private static ByteBuffer littleBuffer(int capacity) {
        return ByteBuffer.allocate(capacity).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static ByteBuffer littleBuffer(byte[] bytes) {
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    }

    private sealed interface Inbound permits ServerHello, MappingDelta, PreparedSection, Invalidation,
            ResolutionBatch, ServerError {
        long session();
    }

    private record ServerHello(long session, long serverInstance, int flags, int blockEpoch,
                               int biomeEpoch, CountDownLatch completion) implements Inbound {}
    private record MappingDelta(long session, List<RemoteBlock> blocks,
                                List<RemoteBiome> biomes, CountDownLatch completion) implements Inbound {}
    record PreparedSection(long session, long key, long revision, byte nonEmptyChildren,
                           int nonEmptyBlockCount, long[] data) implements Inbound {}
    private record Invalidation(long session, long key, long revision,
                                byte reason) implements Inbound {}
    record ResolutionBatch(long session, long[] keys) implements Inbound {}
    private record ServerError(long session, int code, String text) implements Inbound {}
    private record RemoteBlock(int id, byte opacity, String canonical) {}
    private record RemoteBiome(int id, String name) {}
    private record BlockProperty(int id, byte opacity) {}
    private record Subscription(long key, long knownRevision) {}
    private record DemandPriority(int priorityClass, double projectedSize,
                                  double distanceSquared, long age) {}
    private record QueuedDemand(long key, DemandPriority priority)
            implements Comparable<QueuedDemand> {
        @Override
        public int compareTo(QueuedDemand other) {
            int value = Integer.compare(this.priority.priorityClass, other.priority.priorityClass);
            if (value == 0) value = Double.compare(other.priority.projectedSize,
                    this.priority.projectedSize);
            if (value == 0) value = Double.compare(this.priority.distanceSquared,
                    other.priority.distanceSquared);
            if (value == 0) value = Long.compare(this.priority.age, other.priority.age);
            return value == 0 ? Long.compareUnsigned(this.key, other.key) : value;
        }
    }

    private static final class ZstdScratch {
        final ZstdDecompressor decompressor = new ZstdDecompressor();
        final byte[] bytes = new byte[MAX_SECTION_ENCODING];
    }
    private record Frame(short type, byte[] payload) {}
    private record QueuedInbound(Inbound message, int memoryKib, int creditBytes) {}

    static final class ProtocolException extends Exception {
        private ProtocolException(String message) { super(message); }
        private ProtocolException(String message, Throwable cause) { super(message, cause); }
    }
}
