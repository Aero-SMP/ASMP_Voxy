package me.cortex.voxy.client.lod;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.other.Mapper;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.VoxyInstance;
import me.cortex.voxy.commonImpl.WorldIdentifier;
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
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentHashMap;
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
    private static final short S_ERROR = (short) 0x80ff;

    private static final int CAP_BLOCK_PROPERTIES = 1;
    private static final int MAX_INBOUND_FRAMES = 512;
    private static final int MAX_INBOUND_KIB = 128 * 1024;
    private static final int MAX_UPDATES_PER_TICK = 96;
    private static final int MAX_BLOCK_ID = 1 << 20;
    private static final int MAX_BIOME_ID = 1 << 9;
    private static final int MAX_CANONICAL_LENGTH = 4096;
    private static final int MAX_MAPPINGS_PER_FRAME = 256;
    private static final int SUBSCRIPTION_BATCH = 256;
    private static final long BRIDGE_CREDIT = 512L * 1024;
    private static final long DIRECT_CREDIT = 32L * 1024 * 1024;

    private static final ArrayBlockingQueue<QueuedInbound> INBOUND = new ArrayBlockingQueue<>(MAX_INBOUND_FRAMES);
    private static final Semaphore INBOUND_MEMORY = new Semaphore(MAX_INBOUND_KIB);
    private static final Set<Long> DESIRED_SECTIONS = ConcurrentHashMap.newKeySet();
    private static final ConcurrentLinkedQueue<Long> SUBSCRIPTION_CHANGES = new ConcurrentLinkedQueue<>();
    private static final AtomicLong SESSION = new AtomicLong();
    private static final AtomicLong CONNECTION_SEQUENCE = new AtomicLong();
    private static final Object LIFECYCLE_LOCK = new Object();

    private static final ArrayDeque<long[]> SECTION_SCRATCH = new ArrayDeque<>();
    /** Latest revisions observed in this client process; durable revisions live on WorldSection. */
    private static final Long2LongOpenHashMap SECTION_REVISIONS = new Long2LongOpenHashMap();
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
                if (VoxyCommon.getInstance() == null) VoxyCommon.createInstance();
                renderer.voxy$createRenderer();
                recoveryNeeded = false;
            } catch (RuntimeException exception) {
                Logger.error("Retrying Voxy recovery after a failed cache reset", exception);
                return;
            }
        }
        if (level == null || minecraft.player == null || VoxyCommon.getInstance() == null) {
            if (networkThread != null) disconnect();
            return;
        }

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

    public static boolean isServerConnected() {
        return serverConnected;
    }

    public static void subscribe(long key) {
        if (DESIRED_SECTIONS.add(key)) SUBSCRIPTION_CHANGES.add(key);
    }

    public static void unsubscribe(long key) {
        if (DESIRED_SECTIONS.remove(key)) SUBSCRIPTION_CHANGES.add(key);
    }

    public static void resetDemand() {
        DESIRED_SECTIONS.clear();
        SUBSCRIPTION_CHANGES.clear();
    }

    static int debugInboundFrames() { return INBOUND.size(); }
    static int debugInboundKiB() { return MAX_INBOUND_KIB - INBOUND_MEMORY.availablePermits(); }
    static int debugDesiredSections() { return DESIRED_SECTIONS.size(); }

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
        WorldEngine world = identifier == null ? null : identifier.getOrCreateEngine();
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
                } else if (message instanceof SectionUpdate section) {
                    applySection(world, section);
                    updates++;
                } else if (message instanceof Invalidation invalidation) {
                    applyInvalidation(world, invalidation);
                    updates++;
                }
            } catch (RuntimeException exception) {
                Logger.error("Failed to apply Rust LOD message", exception);
                // A partially applied mapping batch cannot be recovered on this connection:
                // the server has already advanced its mapping cursor. Reconnect so HELLO and
                // every mapping are replayed before any more sections can be accepted.
                closeCurrentConnection();
                return;
            } finally {
                INBOUND_MEMORY.release(queued.memoryKib);
                Connection current = connection;
                if (queued.creditBytes != 0 && current != null
                        && queued.message.session() == current.generation) {
                    current.grantCredit(queued.creditBytes);
                    ClientLodDebug.credit(queued.creditBytes);
                }
            }
        }
    }

    private static boolean applyHello(WorldIdentifier identifier, ServerHello hello) {
        serverConnected = false;
        boolean restartInstance = !authorityPrepared;
        boolean resetSections = false;
        if (hello.serverInstance != helloServerInstance) {
            if (VoxyInstance.serverCacheNeedsReset(identifier, hello.serverInstance)) {
                restartInstance = true;
                resetSections = true;
            } else {
                VoxyInstance.recordServerIdentity(identifier, hello.serverInstance);
            }
        }
        if (restartInstance) {
            Minecraft minecraft = Minecraft.getInstance();
            IGetVoxyRenderSystem renderer = (IGetVoxyRenderSystem) minecraft.levelRenderer;
            recoveryNeeded = true;
            try {
                renderer.voxy$shutdownRenderer();
                VoxyCommon.shutdownInstance();
                if (resetSections) {
                    VoxyInstance.resetServerCache(identifier, hello.serverInstance);
                    Logger.warn("Reset stale Voxy sections for a new Rust server catalog");
                }
                authorityPrepared = true;
            } finally {
                // Always restore the client even if cache cleanup fails.
                if (VoxyCommon.getInstance() == null) VoxyCommon.createInstance();
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

    private static void applySection(WorldEngine world, SectionUpdate update) {
        if (!validWorldKey(update.key) || !DESIRED_SECTIONS.contains(update.key)) {
            ClientLodDebug.droppedUnsubscribed();
            return;
        }
        if (isOlderRevision(update.key, update.revision)) {
            ClientLodDebug.droppedRevision();
            return;
        }

        long[] palette = new long[update.palette.length];
        for (int i = 0; i < palette.length; i++) {
            PaletteEntry entry = update.palette[i];
            int blockId = translated(blockTranslations, entry.blockId);
            int biomeId = translated(biomeTranslations, entry.biomeId);
            if (blockId < 0 || biomeId < 0) {
                rejectConnection("LOD section references an unknown server mapping: "
                        + WorldEngine.pprintPos(update.key));
            }
            palette[i] = Mapper.composeMappingId(entry.light, blockId, biomeId);
        }

        long[] data = SECTION_SCRATCH.pollFirst();
        if (data == null) data = new long[WorldSection.SECTION_VOLUME];
        try {
            if (update.bitsPerIndex == 0) {
                Arrays.fill(data, palette[0]);
            } else {
                long mask = (1L << update.bitsPerIndex) - 1;
                for (int i = 0; i < data.length; i++) {
                    long bit = (long) i * update.bitsPerIndex;
                    int word = (int) (bit >>> 6);
                    int shift = (int) (bit & 63);
                    long index = update.words[word] >>> shift;
                    if (shift + update.bitsPerIndex > 64) {
                        index |= update.words[word + 1] << (64 - shift);
                    }
                    int paletteIndex = (int) (index & mask);
                    if (paletteIndex >= palette.length) {
                        rejectConnection("LOD section contains an invalid packed palette index");
                    }
                    data[i] = palette[paletteIndex];
                }
            }

            world.replaceRemoteSection(update.key, update.revision, data, update.nonEmptyChildren);
            SECTION_REVISIONS.put(update.key, update.revision);
            ClientLodDebug.sectionApplied();
        } finally {
            // WorldEngine copies the values; one main-thread scratch array avoids hundreds of
            // megabytes per second of allocation during a large initial stream.
            SECTION_SCRATCH.addFirst(data);
        }
    }

    private static void applyInvalidation(WorldEngine world, Invalidation invalidation) {
        if (!validWorldKey(invalidation.key) || !DESIRED_SECTIONS.contains(invalidation.key)) {
            ClientLodDebug.droppedUnsubscribed();
            return;
        }
        if (isOlderRevision(invalidation.key, invalidation.revision)) {
            ClientLodDebug.droppedRevision();
            return;
        }
        world.invalidateRemoteSection(invalidation.key, invalidation.revision);
        SECTION_REVISIONS.put(invalidation.key, invalidation.revision);
        ClientLodDebug.invalidationApplied();
    }

    private static boolean isOlderRevision(long key, long revision) {
        return SECTION_REVISIONS.containsKey(key)
                && Long.compareUnsigned(revision, SECTION_REVISIONS.get(key)) < 0;
    }

    private static int translated(int[] translations, int serverId) {
        return serverId >= 0 && serverId < translations.length ? translations[serverId] : -1;
    }

    private static boolean validWorldKey(long key) {
        return (key & 0xf) == 0 && WorldEngine.getLevel(key) <= WorldEngine.MAX_LOD_LAYER;
    }

    private static long knownRevision(long key) {
        WorldEngine world = activeWorld;
        if (world == null || !world.isLive()) return -1;
        try {
            WorldSection section = world.acquireIfExists(key);
            if (section == null) return -1;
            try {
                return section.getRemoteRevision();
            } finally {
                section.release();
            }
        } catch (RuntimeException exception) {
            return -1;
        }
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
        QueuedInbound queued;
        while ((queued = INBOUND.poll()) != null) INBOUND_MEMORY.release(queued.memoryKib);
        resetMappings();
        ClientLodDebug.reset();
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
                Inbound decoded = decode(this.generation, frame);
                if (decoded instanceof ServerHello) receivedHello = true;
                if (decoded != null) {
                    int memoryKib = Math.min(MAX_INBOUND_KIB,
                            Math.max(1, (frame.payload.length * 2 + 1023) >>> 10));
                    INBOUND_MEMORY.acquire(memoryKib);
                    boolean queued = false;
                    try {
                        int creditBytes = frame.type == S_SECTION || frame.type == S_INVALIDATE
                                ? frame.payload.length + 16 : 0;
                        INBOUND.put(new QueuedInbound(decoded, memoryKib, creditBytes));
                        queued = true;
                    } finally {
                        if (!queued) INBOUND_MEMORY.release(memoryKib);
                    }
                }
            }
        }

        private void writeLoop() {
            HashSet<Long> sent = new HashSet<>();
            ArrayDeque<Long> initial = new ArrayDeque<>();
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
                        initial.clear();
                        List<Long> ordered = new ArrayList<>(DESIRED_SECTIONS);
                        ordered.sort((left, right) -> Integer.compare(
                                WorldEngine.getLevel(right), WorldEngine.getLevel(left)));
                        initial.addAll(ordered);
                        sentDimension = dimension;
                    }

                    if (serverConnected && sentDimension != null) {
                        List<Subscription> additions = new ArrayList<>(SUBSCRIPTION_BATCH);
                        List<Long> removals = new ArrayList<>(SUBSCRIPTION_BATCH);
                        while (additions.size() + removals.size() < SUBSCRIPTION_BATCH) {
                            Long key = SUBSCRIPTION_CHANGES.poll();
                            if (key == null) key = initial.pollFirst();
                            if (key == null) break;
                            if (DESIRED_SECTIONS.contains(key)) {
                                if (sent.add(key)) additions.add(new Subscription(key, knownRevision(key)));
                            } else if (sent.remove(key)) {
                                removals.add(key);
                            }
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

    private static Inbound decode(long session, Frame frame) throws ProtocolException {
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
                result = new ServerHello(session, server, flags, blockEpoch, biomeEpoch);
            }
            case S_MAPPING_DELTA -> result = decodeMappings(session, input);
            case S_SECTION -> result = decodeSection(session, input);
            case S_INVALIDATE -> {
                requireRemaining(input, 24);
                long key = input.getLong();
                long revision = input.getLong();
                int reason = Byte.toUnsignedInt(input.get());
                requireZero(input.get(), input.get(), input.get(), input.get(), input.get(), input.get(), input.get());
                if (reason < 1 || reason > 3) throw new ProtocolException("Unknown invalidation reason");
                result = new Invalidation(session, key, revision, (byte) reason);
            }
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
        return new MappingDelta(session, blocks, biomes);
    }

    private static SectionUpdate decodeSection(long session, ByteBuffer input) throws ProtocolException {
        requireRemaining(input, 24);
        long key = input.getLong();
        long revision = input.getLong();
        byte nonEmptyChildren = input.get();
        int bits = Byte.toUnsignedInt(input.get());
        int paletteLength = Short.toUnsignedInt(input.getShort());
        long rawWordCount = Integer.toUnsignedLong(input.getInt());

        if (!validWorldKey(key) || paletteLength < 1 || paletteLength > WorldSection.SECTION_VOLUME) {
            throw new ProtocolException("Invalid section metadata");
        }
        int expectedBits = paletteLength == 1 ? 0 : 32 - Integer.numberOfLeadingZeros(paletteLength - 1);
        long expectedWords = ((long) WorldSection.SECTION_VOLUME * expectedBits + 63) >>> 6;
        if (bits != expectedBits || rawWordCount != expectedWords) throw new ProtocolException("Invalid packed palette dimensions");
        long expectedRemaining = (long) paletteLength * 12 + rawWordCount * 8;
        if (expectedRemaining != input.remaining()) throw new ProtocolException("Invalid section payload length");

        PaletteEntry[] palette = new PaletteEntry[paletteLength];
        for (int i = 0; i < palette.length; i++) {
            long block = Integer.toUnsignedLong(input.getInt());
            long biome = Integer.toUnsignedLong(input.getInt());
            byte light = input.get();
            requireZero(input.get(), input.get(), input.get());
            if (block >= MAX_BLOCK_ID || biome >= MAX_BIOME_ID) throw new ProtocolException("Palette mapping ID out of range");
            palette[i] = new PaletteEntry((int) block, (int) biome, light);
        }
        long[] words = new long[(int) rawWordCount];
        for (int i = 0; i < words.length; i++) words[i] = input.getLong();
        return new SectionUpdate(session, key, revision,
                nonEmptyChildren, bits, palette, words);
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

    private sealed interface Inbound permits ServerHello, MappingDelta, SectionUpdate, Invalidation, ServerError {
        long session();
    }

    private record ServerHello(long session, long serverInstance, int flags, int blockEpoch,
                               int biomeEpoch) implements Inbound {}
    private record MappingDelta(long session, List<RemoteBlock> blocks,
                                List<RemoteBiome> biomes) implements Inbound {}
    private record SectionUpdate(long session, long key, long revision,
                                 byte nonEmptyChildren, int bitsPerIndex, PaletteEntry[] palette,
                                 long[] words) implements Inbound {}
    private record Invalidation(long session, long key, long revision,
                                byte reason) implements Inbound {}
    private record ServerError(long session, int code, String text) implements Inbound {}
    private record RemoteBlock(int id, byte opacity, String canonical) {}
    private record RemoteBiome(int id, String name) {}
    private record PaletteEntry(int blockId, int biomeId, byte light) {}
    private record BlockProperty(int id, byte opacity) {}
    private record Subscription(long key, long knownRevision) {}
    private record Frame(short type, byte[] payload) {}
    private record QueuedInbound(Inbound message, int memoryKib, int creditBytes) {}

    private static final class ProtocolException extends Exception {
        private ProtocolException(String message) { super(message); }
    }
}
