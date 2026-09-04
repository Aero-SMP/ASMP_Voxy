package me.cortex.voxy.client.lod;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.model.CatalogMapper;
import me.cortex.voxy.client.core.rendering.SectionKey;
import me.cortex.voxy.client.core.rendering.hierarchical.HierarchicalOcclusionTraverser;
import me.cortex.voxy.client.core.rendering.building.BuiltSection;
import me.cortex.voxy.client.core.rendering.building.SectionMesher;
import me.cortex.voxy.common.Logger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Current regional client. Every entry is one spatial section and moves monotonically through
 * cache/network, decode, mesh, upload, and active states. Renderer refinement adds exact child
 * keys; nothing scans or retains historical object identities.
 */
final class ClientSession {
    private static final int MAX_MAIN_TASKS = 8_192;
    private static final int MAX_MAIN_PER_TICK = 2_048;
    private static final int MAX_EVENTS = 16_384;
    private static final int MAX_IN_FLIGHT_BATCHES = 16;
    private static final long MAX_IN_FLIGHT_BYTES = 128L * 1024 * 1024;
    private static final int MAX_IN_FLIGHT_INDEXES = 128;
    private static final int MAX_SECTION_TASKS = 64;
    private static final int MAX_STAGE_QUEUE = 32_768;
    private static final int MAX_RENDER_ADMISSIONS = 1_024;
    private static final int COVERAGE_RENDER_RESERVE = 64;
    private static final int REQUEST_BATCH = 256;
    private static final int MAX_DORMANT_EVICTIONS_PER_BATCH = 64;
    private static final long MIN_DORMANT_EVICTION_BATCH_BYTES = 16L << 20;
    private static final long INITIAL_DETAIL_GEOMETRY_ESTIMATE_BYTES = 64L << 10;
    private static final long RETRY_DELAY_NANOS = TimeUnit.SECONDS.toNanos(1);

    private static final Object LIFECYCLE = new Object();
    private static final LinkedHashSet<Long> TOP_LEVEL = new LinkedHashSet<>();
    private static final ArrayBlockingQueue<MainTask> MAIN =
            new ArrayBlockingQueue<>(MAX_MAIN_TASKS);
    private static final AtomicLong SESSION_IDS = new AtomicLong();
    private static volatile Session active;
    private static volatile String activeDimension;
    private static volatile VoxyRenderSystem activeRenderer;
    private static volatile long retryAfter;

    private ClientSession() {}

    static boolean sectionEntered(long key) {
        requireTop(key);
        synchronized (TOP_LEVEL) {
            if (!TOP_LEVEL.add(key)) return false;
        }
        Session current = active;
        if (current != null) current.demandEvents.add(new DemandEvent(true, key));
        return true;
    }

    static void sectionLeft(long key) {
        requireTop(key);
        synchronized (TOP_LEVEL) { TOP_LEVEL.remove(key); }
        Session current = active;
        if (current != null) current.demandEvents.add(new DemandEvent(false, key));
    }

    static void detailAction(long key, int action, int bucket, int epoch) {
        Session current = active;
        if (current != null) current.acceptDetailAction(key, action, bucket, epoch);
    }

    static void resetDemand() {
        synchronized (TOP_LEVEL) { TOP_LEVEL.clear(); }
        Session current = active;
        if (current != null) current.resetRequested.set(true);
    }

    static void rendererLifecycleChanged() { disconnect(); }

    static String debugSnapshot() {
        Session current = active;
        return current == null ? "regional=DISCONNECTED" : current.snapshot();
    }

    static void tick() {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        VoxyRenderSystem renderer = level == null ? null : IGetVoxyRenderSystem.getNullable();
        if (level == null || minecraft.player == null || renderer == null) {
            disconnect();
            return;
        }
        String dimension = level.dimension().location().toString();
        Session current = active;
        if (current == null || !dimension.equals(activeDimension) || renderer != activeRenderer) {
            synchronized (LIFECYCLE) {
                current = active;
                if (current != null
                        && (!dimension.equals(activeDimension) || renderer != activeRenderer)) {
                    stopLocked(current);
                    current = null;
                }
                if (current == null && System.nanoTime() - retryAfter >= 0) {
                    current = new Session(SESSION_IDS.incrementAndGet(), dimension, renderer);
                    active = current;
                    activeDimension = dimension;
                    activeRenderer = renderer;
                    current.start();
                }
            }
        }
        if (current != null) {
            current.updateCamera((int) Math.floor(minecraft.player.getX()) >> 5,
                    (int) Math.floor(minecraft.player.getZ()) >> 5);
        }

        current = active;
        for (int count = 0; count < MAX_MAIN_PER_TICK; count++) {
            MainTask task = MAIN.poll();
            if (task == null) break;
            if (task.owner() != current || current == null || !current.open.get()) {
                task.cancel();
                continue;
            }
            try {
                task.run(renderer);
            } catch (Throwable failure) {
                task.cancel();
                current.fail(failure);
            }
        }
        if (current != null && !current.thread.isAlive()) {
            synchronized (LIFECYCLE) {
                if (active == current) {
                    stopLocked(current);
                    retryAfter = System.nanoTime() + RETRY_DELAY_NANOS;
                }
            }
        }
    }

    static void disconnect() {
        synchronized (LIFECYCLE) {
            Session current = active;
            if (current != null) stopLocked(current);
            activeDimension = null;
            activeRenderer = null;
        }
    }

    private static void stopLocked(Session session) {
        if (active == session) active = null;
        session.close();
        MainTask task;
        ArrayDeque<MainTask> retained = new ArrayDeque<>();
        while ((task = MAIN.poll()) != null) {
            if (task.owner() == session) task.cancel(); else retained.add(task);
        }
        MAIN.addAll(retained);
    }

    private static void requireTop(long key) {
        if (SectionKey.level(key) != SectionKey.MAX_LOD_LAYER) {
            throw new IllegalArgumentException("regional renderer root is not LOD 4");
        }
    }

    private static List<Long> topSnapshot() {
        // TOP_LEVEL is populated nearest-first by RenderDistanceTracker. Preserve that order so
        // a cold session requests the player's coverage before distant regions.
        synchronized (TOP_LEVEL) { return List.copyOf(TOP_LEVEL); }
    }

    private enum Phase {
        NEW, WAITING, CACHE, NETWORK, REQUESTED, RECEIVED, DECODING, DECODED, MESHING,
        READY, PUBLISHING, ACTIVE
    }

    private static final class Demand {
        final long key;
        final boolean coverage;
        volatile int token;
        volatile Phase phase = Phase.NEW;
        RegionalProtocol.RegionIndex index;
        int ordinal = -1;
        RegionalSectionCodec.SectionData decoded;
        VoxyRenderSystem.SectionPublication publication;
        RegionalProtocol.RegionIndex pendingIndex;
        int pendingOrdinal = -1;
        byte[] received;
        boolean receivedFromCache;
        RenderAdmission renderAdmission;
        BuiltSection completedGeometry;
        long geometryBytes, activeGeometryBytes;
        volatile int geometryAccountToken;
        final AtomicBoolean geometryAccounted = new AtomicBoolean();
        volatile int publishingAccountToken;
        final AtomicBoolean publishingGeometryAccounted = new AtomicBoolean();
        int latestRefinementEpoch = -1;
        int latestDormancyEpoch = -1;
        boolean dormant;
        int dormantBucket;
        long lastSelectedSequence;
        boolean detailRejected;
        long budgetBlockedSinceNanos;

        Demand(long key) {
            this.key = key;
            this.coverage = SectionKey.level(key) == SectionKey.MAX_LOD_LAYER;
        }
    }

    private static final class DormantRoot {
        final long key;
        long bytes;
        int bucket;
        long lastSelectedSequence;

        DormantRoot(long key, long bytes, int bucket, long lastSelectedSequence) {
            this.key = key;
            this.bytes = bytes;
            this.bucket = bucket;
            this.lastSelectedSequence = lastSelectedSequence;
        }
    }

    /** One allocation-free, tagged handoff shared by both detail directions. */
    private static final class DetailFrontier {
        private static final int BUCKETS = HierarchicalOcclusionTraverser.DETAIL_BUCKET_COUNT;
        private static final int CAPACITY = 32_768;
        private static final int HASH_BUCKETS = 65_536;
        private final long[] keys = new long[CAPACITY];
        private final byte[] actions = new byte[CAPACITY];
        private final byte[] buckets = new byte[CAPACITY];
        private final int[] epochs = new int[CAPACITY];
        private final int[] hashNext = new int[CAPACITY];
        private final int[] priorityPrevious = new int[CAPACITY];
        private final int[] priorityNext = new int[CAPACITY];
        private final int[] freeNext = new int[CAPACITY];
        private final int[] hashHeads = new int[HASH_BUCKETS];
        private final int[] priorityHeads = new int[BUCKETS];
        private final int[] priorityTails = new int[BUCKETS];
        private final int[] prioritySizes = new int[BUCKETS];
        private int nextUnused;
        private int freeHead = -1;
        private int total;
        private long dropped;
        long key;
        int action;
        int epoch;

        DetailFrontier() { this.resetHeads(); }

        synchronized boolean offer(long key, int action, int bucket, int epoch) {
            if (action != HierarchicalOcclusionTraverser.ACTION_REFINE
                    && action != HierarchicalOcclusionTraverser.ACTION_DORMANT
                    && action != HierarchicalOcclusionTraverser.ACTION_WAKE) {
                return false;
            }
            bucket = Math.max(0, Math.min(BUCKETS - 1, bucket));
            int hash = hash(key);
            for (int entry = this.hashHeads[hash]; entry != -1;
                 entry = this.hashNext[entry]) {
                if (this.keys[entry] != key) continue;
                if (Integer.compareUnsigned(epoch, this.epochs[entry]) <= 0) return false;
                int oldBucket = Byte.toUnsignedInt(this.buckets[entry]);
                if (oldBucket != bucket) {
                    this.unlinkPriority(entry, oldBucket);
                    this.linkPriority(entry, bucket);
                }
                this.actions[entry] = (byte) action;
                this.buckets[entry] = (byte) bucket;
                this.epochs[entry] = epoch;
                return true;
            }
            if (this.total == CAPACITY) {
                this.dropped++;
                return false;
            }
            int entry;
            if (this.freeHead != -1) {
                entry = this.freeHead;
                this.freeHead = this.freeNext[entry];
            } else {
                entry = this.nextUnused++;
            }
            this.keys[entry] = key;
            this.actions[entry] = (byte) action;
            this.buckets[entry] = (byte) bucket;
            this.epochs[entry] = epoch;
            this.hashNext[entry] = this.hashHeads[hash];
            this.hashHeads[hash] = entry;
            this.linkPriority(entry, bucket);
            this.total++;
            return true;
        }

        synchronized boolean poll(int bucket) {
            int entry = this.priorityHeads[bucket];
            if (entry == -1) return false;
            this.unlinkPriority(entry, bucket);
            int hash = hash(this.keys[entry]);
            int previous = -1;
            for (int current = this.hashHeads[hash]; current != -1;
                 current = this.hashNext[current]) {
                if (current != entry) {
                    previous = current;
                    continue;
                }
                if (previous == -1) this.hashHeads[hash] = this.hashNext[current];
                else this.hashNext[previous] = this.hashNext[current];
                break;
            }
            this.key = this.keys[entry];
            this.action = Byte.toUnsignedInt(this.actions[entry]);
            this.epoch = this.epochs[entry];
            this.freeNext[entry] = this.freeHead;
            this.freeHead = entry;
            this.total--;
            return true;
        }

        private void linkPriority(int entry, int bucket) {
            int tail = this.priorityTails[bucket];
            this.priorityPrevious[entry] = tail;
            this.priorityNext[entry] = -1;
            if (tail == -1) this.priorityHeads[bucket] = entry;
            else this.priorityNext[tail] = entry;
            this.priorityTails[bucket] = entry;
            this.prioritySizes[bucket]++;
        }

        private void unlinkPriority(int entry, int bucket) {
            int previous = this.priorityPrevious[entry];
            int next = this.priorityNext[entry];
            if (previous == -1) this.priorityHeads[bucket] = next;
            else this.priorityNext[previous] = next;
            if (next == -1) this.priorityTails[bucket] = previous;
            else this.priorityPrevious[next] = previous;
            this.prioritySizes[bucket]--;
        }

        private static int hash(long key) {
            long mixed = key ^ key >>> 33;
            mixed *= 0xff51afd7ed558ccdl;
            mixed ^= mixed >>> 33;
            return (int) mixed & (HASH_BUCKETS - 1);
        }

        synchronized int size(int bucket) { return this.prioritySizes[bucket]; }
        synchronized int size() { return this.total; }
        synchronized long dropped() { return this.dropped; }
        synchronized void clear() {
            this.resetHeads();
            java.util.Arrays.fill(this.prioritySizes, 0);
            this.nextUnused = 0;
            this.freeHead = -1;
            this.total = 0;
        }

        private void resetHeads() {
            java.util.Arrays.fill(this.hashHeads, -1);
            java.util.Arrays.fill(this.priorityHeads, -1);
            java.util.Arrays.fill(this.priorityTails, -1);
        }
    }

    private static final class RenderAdmission {
        private final Session owner;
        private final long geometryReservationBytes;
        private final AtomicBoolean released = new AtomicBoolean();
        private final AtomicBoolean geometryReservationReleased = new AtomicBoolean();

        private RenderAdmission(Session owner, long geometryReservationBytes) {
            this.owner = owner;
            this.geometryReservationBytes = geometryReservationBytes;
        }

        private void transferGeometryReservation() {
            if (this.geometryReservationReleased.compareAndSet(false, true)) {
                this.owner.meshingReservationBytes.addAndGet(-this.geometryReservationBytes);
            }
        }

        private void release() {
            if (!this.released.compareAndSet(false, true)) return;
            this.transferGeometryReservation();
            this.owner.renderAdmissions.remove(this);
            this.owner.renderAdmissionSlots.release();
            this.owner.signal();
        }
    }

    private static final class Session implements AutoCloseable {
        final long id;
        final String dimension;
        final VoxyRenderSystem renderer;
        final VoxyRenderSystem.SectionPublisher publisher;
        final SectionMesher mesher;
        final Thread thread;
        final AtomicBoolean open = new AtomicBoolean(true);
        final AtomicBoolean resetRequested = new AtomicBoolean();
        final ConcurrentLinkedQueue<DemandEvent> demandEvents = new ConcurrentLinkedQueue<>();
        final DetailFrontier frontier = new DetailFrontier();
        final ArrayBlockingQueue<Event> events = new ArrayBlockingQueue<>(MAX_EVENTS);
        final Semaphore wakeup = new Semaphore(0);
        final Semaphore sectionTaskSlots = new Semaphore(MAX_SECTION_TASKS);
        final Semaphore renderAdmissionSlots = new Semaphore(MAX_RENDER_ADMISSIONS);
        final Set<RenderAdmission> renderAdmissions = ConcurrentHashMap.newKeySet();
        final ExecutorService sectionWorkers;
        final RegionalSectionCodec codec = new RegionalSectionCodec();

        final LinkedHashMap<Long, Demand> demands = new LinkedHashMap<>();
        final Map<Long, RegionalProtocol.RegionIndex> indexes = new HashMap<>();
        final Map<Long, Long> regionGenerations = new HashMap<>();
        final Set<Long> requestedRegions = new HashSet<>();
        final Set<Long> subscribedRegions = new HashSet<>();
        final Set<Long> absentRegions = new HashSet<>();
        final Map<Long, LinkedHashSet<Long>> demandsByRegion = new HashMap<>();
        final Map<Long, LinkedHashSet<Long>> demandsByTop = new HashMap<>();
        final Set<Long> missingCoverage = new HashSet<>();
        final Set<Long> coarseningRoots = new HashSet<>();
        final Long2ObjectOpenHashMap<DormantRoot> dormantRoots =
                new Long2ObjectOpenHashMap<>();
        final Long2LongOpenHashMap pendingDormantEvictions = new Long2LongOpenHashMap();
        final Object publicationLock = new Object();
        final ArrayDeque<Long> coverageBindQueue = new ArrayDeque<>();
        final ArrayDeque<Long> refinementBindQueue = new ArrayDeque<>();
        final Set<Long> pendingBindSet = new HashSet<>();
        final ArrayDeque<Long> regionQueue = new ArrayDeque<>();
        final Set<Long> queuedRegions = new HashSet<>();
        final ArrayDeque<StageRef> cacheQueue = new ArrayDeque<>();
        final ArrayDeque<StageRef> coverageNetworkQueue = new ArrayDeque<>();
        final ArrayDeque<StageRef> refinementNetworkQueue = new ArrayDeque<>();
        final ArrayDeque<StageRef> decodeQueue = new ArrayDeque<>();
        final ArrayDeque<StageRef> meshQueue = new ArrayDeque<>();
        final ArrayDeque<StageRef> readyPublicationQueue = new ArrayDeque<>();
        final ArrayDeque<PublicationRef> publicationQueue = new ArrayDeque<>();

        RegionalQuicClient quic;
        RegionalCache cache;
        RegionalProtocol.Hash32 worldIdentity;
        RegionalProtocol.Hash32 catalogFingerprint = RegionalProtocol.Hash32.ZERO;
        RegionalProtocol.Hash32 requiredCatalogFingerprint = RegionalProtocol.Hash32.ZERO;
        RegionalSectionCodec.Mappings mappings;
        boolean catalogRequested;
        int inFlightBatches;
        int inFlightSections;
        int cacheLookupsInFlight;
        int decodesInFlight;
        long inFlightBytes;
        long requestEpoch = 1;
        long receivedBytes;
        long activated;
        long activeGeometryBytes;
        long dormantGeometryBytes;
        long pendingDormantEvictionBytes;
        long selectionSequence;
        long dormancyTransitions;
        long wakes;
        long instantWakes;
        long capEvictions;
        long admissionEvictions;
        long dormantBytesFreedAfterFences;
        long correctiveAccountingRebuilds;
        long lastEvictionDistanceSquared;
        long lastEvictionAge;
        int lastEvictionBucket = -1;
        int activeCount;
        volatile int cameraSectionX;
        volatile int cameraSectionZ;
        final AtomicLong completedGeometryBytes = new AtomicLong();
        final AtomicLong publishingGeometryBytes = new AtomicLong();
        final AtomicLong meshingReservationBytes = new AtomicLong();
        final long[] detailGeometryEstimateBytes = new long[SectionKey.MAX_LOD_LAYER + 1];
        volatile Throwable failure;

        Session(long id, String dimension, VoxyRenderSystem renderer) {
            this.id = id;
            this.dimension = dimension;
            this.renderer = renderer;
            this.publisher = renderer.regionalSectionPublisher();
            this.mesher = renderer.regionalSectionMesher();
            int workers = Math.max(2, Math.min(16,
                    Runtime.getRuntime().availableProcessors() - 2));
            this.sectionWorkers = Executors.newFixedThreadPool(workers, task -> {
                Thread thread = new Thread(task, "Voxy regional section worker");
                thread.setDaemon(true);
                return thread;
            });
            this.thread = new Thread(this::run, "Voxy regional owner");
            this.thread.setDaemon(true);
            java.util.Arrays.fill(this.detailGeometryEstimateBytes,
                    INITIAL_DETAIL_GEOMETRY_ESTIMATE_BYTES);
            for (long key : topSnapshot()) this.demandEvents.add(new DemandEvent(true, key));
        }

        void start() { this.thread.start(); }

        void updateCamera(int sectionX, int sectionZ) {
            this.cameraSectionX = sectionX;
            this.cameraSectionZ = sectionZ;
        }

        void acceptDetailAction(long key, int action, int bucket, int epoch) {
            if (this.frontier.offer(key, action, bucket, epoch)) this.signal();
        }

        void run() {
            try {
                this.quic = QuicEndpointDiscovery.connect();
                this.quic.setActivityListener(this::signal);
                this.quic.hello(this.dimension);
                Logger.info("Using regional Voxy over QUIC " + this.quic.description());
                while (this.open.get()) {
                    this.drainControls();
                    this.drainEvents();
                    this.drainDemand();
                    this.processRegions();
                    this.pollPublications();
                    this.processStages();
                    if (!this.quic.isOpen()) {
                        throw new IOException("regional QUIC connection ended", this.quic.failure());
                    }
                    this.wakeup.tryAcquire(10, TimeUnit.MILLISECONDS);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (Throwable failure) {
                if (this.open.get()) {
                    this.failure = failure;
                    Logger.warn("Regional Voxy session stopped", failure);
                }
            } finally {
                this.open.set(false);
                this.release();
            }
        }

        void drainControls() throws Exception {
            RegionalProtocol.Control control;
            while ((control = this.quic.pollControl()) != null) {
                switch (control) {
                    case RegionalProtocol.ServerHello hello -> this.acceptHello(hello);
                    case RegionalProtocol.RegionMessage region -> this.acceptRegion(region);
                    case RegionalProtocol.RegionAbsent absent -> this.acceptRegionAbsent(absent);
                    case RegionalProtocol.CatalogMessage catalog -> this.acceptCatalog(catalog);
                    case RegionalProtocol.RegionChanged changed -> this.regionChanged(changed);
                    case RegionalProtocol.ServerError error -> throw new IOException(
                            "regional server error " + error.code() + ": " + error.message());
                    case RegionalProtocol.ServerShutdown shutdown -> throw new IOException(
                            "regional server shutdown: " + shutdown.message());
                }
            }
        }

        void acceptHello(RegionalProtocol.ServerHello hello) throws IOException {
            if (this.worldIdentity != null) throw new IOException("duplicate regional HELLO");
            this.worldIdentity = hello.worldIdentity();
            Path root = Minecraft.getInstance().gameDirectory.toPath()
                    .resolve(".voxy").resolve("regional");
            this.cache = new RegionalCache(root, this.worldIdentity);
            this.ensureCatalog(hello.catalogFingerprint());
            for (long region : this.demandsByRegion.keySet()) this.queueRegion(region);
        }

        void ensureCatalog(RegionalProtocol.Hash32 fingerprint) throws IOException {
            this.requiredCatalogFingerprint = fingerprint;
            if (fingerprint.equals(this.catalogFingerprint) && this.mappings != null) return;
            if (!this.catalogRequested) {
                this.catalogRequested = true;
                this.quic.requestCatalog();
            }
        }

        void acceptCatalog(RegionalProtocol.CatalogMessage message) throws Exception {
            this.catalogRequested = false;
            if (!hash32(message.canonical()).equals(message.fingerprint())) {
                throw new IOException("regional catalog fingerprint mismatch");
            }
            // Catalogs are append-only; the authoritative response may be newer than the region
            // response which triggered it and is therefore the best requirement to retain.
            this.requiredCatalogFingerprint = message.fingerprint();
            CatalogCodec.Catalog catalog = CatalogCodec.decode(message.canonical());
            enqueueMain(new CatalogTask(this, catalog, message.fingerprint()));
        }

        void acceptRegion(RegionalProtocol.RegionMessage message) throws IOException {
            long region = regionKey(message.regionX(), message.regionZ());
            this.requestedRegions.remove(region);
            if (!this.subscribedRegions.contains(region)) return;
            Long expected = this.regionGenerations.get(region);
            if (expected != null && expected != 0 && expected != message.generation()) {
                this.queueRegion(region);
                return;
            }
            this.regionGenerations.put(region, message.generation());
            this.absentRegions.remove(region);
            this.ensureCatalog(message.catalogFingerprint());
            byte[] compressed = message.compressed();
            this.sectionWorkers.execute(() -> {
                try {
                    byte[] canonical = this.codec.decompressFramed(compressed,
                            RegionalProtocol.MAX_INDEX_BYTES);
                    byte[] hash = new Blake3.Hasher().update(canonical).digest();
                    RegionalProtocol.Fingerprint actual = new RegionalProtocol.Fingerprint(
                            leLong(hash, 0), leLong(hash, 8));
                    if (!actual.equals(message.fingerprint())) {
                        throw new IOException("regional index fingerprint mismatch");
                    }
                    RegionalProtocol.RegionIndex index = RegionalProtocol.decodeIndex(
                            canonical, message.fingerprint());
                    if (index.regionX() != message.regionX() || index.regionZ() != message.regionZ()
                            || index.generation() != message.generation()) {
                        throw new IOException("regional response disagrees with its index");
                    }
                    putEvent(new IndexReady(message, index));
                } catch (Throwable failure) {
                    putEvent(new WorkerFailed(failure));
                }
            });
        }

        void acceptRegionAbsent(RegionalProtocol.RegionAbsent message) {
            long region = regionKey(message.regionX(), message.regionZ());
            this.requestedRegions.remove(region);
            if (!this.subscribedRegions.contains(region)) return;
            this.regionGenerations.put(region, 0L);
            this.absentRegions.add(region);
            this.indexes.remove(region);
            this.retireRegion(region);
        }

        void regionChanged(RegionalProtocol.RegionChanged message) {
            long region = regionKey(message.regionX(), message.regionZ());
            if (!this.demandsByRegion.containsKey(region) && !this.indexes.containsKey(region)
                    && !this.requestedRegions.contains(region)) return;
            this.regionGenerations.put(region, message.generation());
            this.requestedRegions.remove(region);
            this.absentRegions.remove(region);
            this.indexes.remove(region);
            if (message.generation() == 0) {
                this.absentRegions.add(region);
                this.retireRegion(region);
            } else if (this.demandsByRegion.containsKey(region)) {
                this.queueRegion(region);
            }
        }

        void drainEvents() throws Exception {
            Event event;
            while ((event = this.events.poll()) != null) {
                switch (event) {
                    case CatalogReady ready -> {
                        this.catalogFingerprint = ready.fingerprint;
                        this.mappings = ready.mappings;
                        this.ensureCatalog(this.requiredCatalogFingerprint);
                    }
                    case IndexReady ready -> this.installIndex(ready.index);
                    case CacheResult result -> {
                        this.cacheLookupsInFlight = Math.max(0,
                                this.cacheLookupsInFlight - 1);
                        this.acceptCache(result);
                    }
                    case CacheCorrupt corrupt -> {
                        this.decodesInFlight = Math.max(0, this.decodesInFlight - 1);
                        this.acceptCacheCorrupt(corrupt);
                    }
                    case SectionResult result -> this.acceptSection(result.reply);
                    case DecodedResult result -> {
                        this.decodesInFlight = Math.max(0, this.decodesInFlight - 1);
                        this.acceptDecoded(result);
                    }
                    case MeshedResult result -> this.acceptMeshed(result);
                    case PublicationQueued result -> this.acceptPublication(result);
                    case Coarsened result -> this.finishCoarsening(result.parent, true);
                    case CoarsenFailed failed -> {
                        this.finishCoarsening(failed.parent, false);
                        throw new IOException("regional subtree coarsening failed", failed.failure);
                    }
                    case BatchComplete complete -> {
                        this.inFlightBatches = Math.max(0, this.inFlightBatches - 1);
                        this.inFlightSections = Math.max(0,
                                this.inFlightSections - complete.sectionCount);
                        this.inFlightBytes = Math.max(0,
                                this.inFlightBytes - complete.reservedBytes);
                    }
                    case WorkerFailed failed -> throw new IOException(
                            "regional section worker failed", failed.failure);
                }
            }
        }

        void drainDemand() {
            if (this.resetRequested.getAndSet(false)) {
                for (long key : List.copyOf(this.demands.keySet())) this.retireDemand(key);
                this.dormantRoots.clear();
                this.dormantGeometryBytes = 0;
                this.frontier.clear();
                this.clearStageQueues();
            }
            DemandEvent event;
            while ((event = this.demandEvents.poll()) != null) {
                if (event.add) {
                    this.addDemand(event.key);
                } else {
                    long top = event.key;
                    Set<Long> owned = this.demandsByTop.get(top);
                    if (owned != null) {
                        for (long key : List.copyOf(owned)) this.retireDemand(key);
                    }
                }
            }
            this.drainDetailFrontier();
        }

        void drainDetailFrontier() {
            this.drainDormancyTransitions();
            this.evictDormant(this.dormantGeometryBytes - this.dormantCapBytes(), false);
            // Coverage retains its independent reserve; detail consumes only the remaining
            // pipeline and the steady-state portion of the geometry arena.
            for (int bucket = HierarchicalOcclusionTraverser.DETAIL_BUCKET_COUNT - 1;
                 bucket >= 0; bucket--) {
                int remaining = this.frontier.size(bucket);
                while (remaining-- > 0 && this.frontier.poll(bucket)) {
                    long parent = this.frontier.key;
                    int action = this.frontier.action;
                    int epoch = this.frontier.epoch;
                    if (action != HierarchicalOcclusionTraverser.ACTION_REFINE) {
                        this.frontier.offer(parent, action, bucket, epoch);
                        continue;
                    }
                    Demand demand = this.demands.get(parent);
                    if (demand == null || demand.phase != Phase.ACTIVE
                            || SectionKey.level(parent) == 0 || this.isCoarsening(parent)
                            || demand.detailRejected
                            || !newerEpoch(epoch, demand.latestRefinementEpoch)) continue;
                    if (this.geometryDeficitBytes() >= 0) {
                        this.frontier.offer(parent, action, bucket, epoch);
                        break;
                    }
                    if (!this.hasPipelineHeadroom(false)
                            || !this.hasRenderAdmissionHeadroom(false)) {
                        this.frontier.offer(parent, action, bucket, epoch);
                        break;
                    }
                    if (this.indexFor(parent) == null) {
                        this.ensureRegion(parent);
                        this.frontier.offer(parent, action, bucket, epoch);
                        continue;
                    }
                    if (!this.addChildren(parent)) {
                        this.frontier.offer(parent, action, bucket, epoch);
                        break;
                    }
                    demand.latestRefinementEpoch = epoch;
                }
            }
        }

        void drainDormancyTransitions() {
            for (int bucket = 0;
                 bucket < HierarchicalOcclusionTraverser.DETAIL_BUCKET_COUNT; bucket++) {
                int remaining = this.frontier.size(bucket);
                while (remaining-- > 0 && this.frontier.poll(bucket)) {
                    long key = this.frontier.key;
                    int action = this.frontier.action;
                    int epoch = this.frontier.epoch;
                    if (action == HierarchicalOcclusionTraverser.ACTION_REFINE) {
                        this.frontier.offer(key, action, bucket, epoch);
                    } else if (action == HierarchicalOcclusionTraverser.ACTION_DORMANT) {
                        this.markDormant(key, bucket, epoch);
                    } else if (action == HierarchicalOcclusionTraverser.ACTION_WAKE) {
                        this.wakeDormant(key, epoch);
                    }
                }
            }
        }

        void markDormant(long key, int bucket, int epoch) {
            Demand demand = this.demands.get(key);
            if (demand == null || demand.publication == null || this.isCoarsening(key)
                    || !newerEpoch(epoch, demand.latestDormancyEpoch)) return;
            demand.latestDormancyEpoch = epoch;
            demand.dormantBucket = bucket;
            demand.lastSelectedSequence = ++this.selectionSequence;
            if (!demand.dormant) {
                demand.dormant = true;
                this.dormancyTransitions++;
            }
            DormantRoot ancestor = this.dormantAncestor(key);
            if (ancestor != null) return;
            this.removeDormantDescendants(key);
            long bytes = this.descendantActiveBytes(key);
            if (bytes == 0) return;
            DormantRoot previous = this.dormantRoots.put(key,
                    new DormantRoot(key, bytes, bucket, demand.lastSelectedSequence));
            if (previous != null) this.dormantGeometryBytes -= previous.bytes;
            this.dormantGeometryBytes += bytes;
            this.validateGeometryAccounting();
        }

        void wakeDormant(long key, int epoch) {
            Demand demand = this.demands.get(key);
            if (demand == null || !newerEpoch(epoch, demand.latestDormancyEpoch)) return;
            demand.latestDormancyEpoch = epoch;
            demand.lastSelectedSequence = ++this.selectionSequence;
            boolean transitioned = demand.dormant;
            demand.dormant = false;
            DormantRoot root = this.removeDormantRoot(key);
            if (transitioned) this.wakes++;
            if (root != null) {
                this.instantWakes++;
                this.restoreNestedDormantRoots(key);
            }
            this.validateGeometryAccounting();
        }

        void restoreNestedDormantRoots(long parent) {
            Set<Long> owned = this.demandsByTop.get(topAncestor(parent));
            if (owned == null) return;
            for (int level = SectionKey.level(parent) - 1; level >= 0; level--) {
                for (long key : owned) {
                    Demand child = this.demands.get(key);
                    if (child == null || !child.dormant || SectionKey.level(key) != level
                            || !contains(parent, key) || child.publication == null
                            || this.dormantAncestor(key) != null) continue;
                    long bytes = this.descendantActiveBytes(key);
                    if (bytes == 0) continue;
                    this.dormantRoots.put(key, new DormantRoot(key, bytes,
                            child.dormantBucket, child.lastSelectedSequence));
                    this.dormantGeometryBytes += bytes;
                }
            }
        }

        DormantRoot dormantAncestor(long key) {
            while (SectionKey.level(key) < SectionKey.MAX_LOD_LAYER) {
                key = parent(key);
                DormantRoot root = this.dormantRoots.get(key);
                if (root != null) return root;
            }
            return null;
        }

        long descendantActiveBytes(long parent) {
            Set<Long> owned = this.demandsByTop.get(topAncestor(parent));
            long bytes = 0;
            if (owned != null) for (long key : owned) {
                Demand child = this.demands.get(key);
                if (key != parent && child != null && contains(parent, key)) {
                    bytes += child.activeGeometryBytes;
                }
            }
            return bytes;
        }

        DormantRoot removeDormantRoot(long key) {
            DormantRoot root = this.dormantRoots.remove(key);
            if (root != null) this.dormantGeometryBytes -= root.bytes;
            return root;
        }

        void removeDormantDescendants(long parent) {
            var iterator = this.dormantRoots.long2ObjectEntrySet().fastIterator();
            while (iterator.hasNext()) {
                DormantRoot root = iterator.next().getValue();
                if (root.key != parent && contains(parent, root.key)) {
                    this.dormantGeometryBytes -= root.bytes;
                    iterator.remove();
                }
            }
        }

        void forgetDormancyForSubtree(long parent) {
            var iterator = this.dormantRoots.long2ObjectEntrySet().fastIterator();
            while (iterator.hasNext()) {
                DormantRoot root = iterator.next().getValue();
                if (contains(parent, root.key)) {
                    this.dormantGeometryBytes -= root.bytes;
                    iterator.remove();
                }
            }
            Demand demand = this.demands.get(parent);
            if (demand != null) demand.dormant = false;
        }

        void evictDormant(long requiredBytes, boolean forAdmission) {
            if (requiredBytes <= 0 || this.dormantRoots.isEmpty()) return;
            long target = Math.max(requiredBytes, MIN_DORMANT_EVICTION_BATCH_BYTES);
            long scheduled = 0;
            int evictions = 0;
            while (scheduled < target && evictions < MAX_DORMANT_EVICTIONS_PER_BATCH
                    && !this.dormantRoots.isEmpty()) {
                DormantRoot root = this.selectDormantEviction();
                if (root == null) break;
                Demand demand = this.demands.get(root.key);
                boolean valid = demand != null && demand.dormant
                        && demand.publication != null && !this.overlapsCoarsening(root.key);
                this.removeDormantRoot(root.key);
                if (!valid) {
                    this.correctiveAccountingRebuilds++;
                    continue;
                }
                demand.dormant = false;
                long age = Math.max(0, this.selectionSequence - root.lastSelectedSequence);
                long distance = this.distanceSquared(root.key);
                long bytes = this.coarsen(root.key);
                if (bytes == 0) continue;
                this.pendingDormantEvictions.put(root.key, bytes);
                this.pendingDormantEvictionBytes += bytes;
                scheduled += bytes;
                evictions++;
                if (forAdmission) this.admissionEvictions++;
                else this.capEvictions++;
                this.lastEvictionDistanceSquared = distance;
                this.lastEvictionBucket = root.bucket;
                this.lastEvictionAge = age;
            }
            this.validateGeometryAccounting();
        }

        DormantRoot selectDormantEviction() {
            DormantRoot selected = null;
            boolean selectedOutside = false;
            long selectedDistance = 0;
            for (DormantRoot candidate : this.dormantRoots.values()) {
                Demand demand = this.demands.get(candidate.key);
                if (demand == null || !demand.dormant || demand.publication == null) {
                    return candidate;
                }
                if (this.overlapsCoarsening(candidate.key)) continue;
                boolean outside = !hasTop(topAncestor(candidate.key));
                long distance = this.distanceSquared(candidate.key);
                if (selected == null || outside && !selectedOutside
                        || outside == selectedOutside && (distance > selectedDistance
                        || distance == selectedDistance && (candidate.bucket < selected.bucket
                        || candidate.bucket == selected.bucket
                        && candidate.lastSelectedSequence < selected.lastSelectedSequence))) {
                    selected = candidate;
                    selectedOutside = outside;
                    selectedDistance = distance;
                }
            }
            return selected;
        }

        long distanceSquared(long key) {
            int level = SectionKey.level(key);
            long centerX2 = ((long) SectionKey.x(key) << (level + 1)) + (1L << level);
            long centerZ2 = ((long) SectionKey.z(key) << (level + 1)) + (1L << level);
            long dx = centerX2 - ((long) this.cameraSectionX * 2L + 1L);
            long dz = centerZ2 - ((long) this.cameraSectionZ * 2L + 1L);
            return dx * dx + dz * dz;
        }

        void setActiveGeometryBytes(Demand demand, long bytes) {
            long delta = bytes - demand.activeGeometryBytes;
            if (delta == 0) return;
            demand.activeGeometryBytes = bytes;
            this.activeGeometryBytes += delta;
            DormantRoot root = this.dormantAncestor(demand.key);
            if (root != null) {
                root.bytes += delta;
                this.dormantGeometryBytes += delta;
            }
            this.validateGeometryAccounting();
        }

        void validateGeometryAccounting() {
            if (this.activeGeometryBytes >= 0 && this.dormantGeometryBytes >= 0
                    && this.dormantGeometryBytes <= this.activeGeometryBytes) return;
            this.correctiveAccountingRebuilds++;
            long active = 0;
            for (Demand demand : this.demands.values()) active += demand.activeGeometryBytes;
            long dormant = 0;
            var iterator = this.dormantRoots.long2ObjectEntrySet().fastIterator();
            while (iterator.hasNext()) {
                DormantRoot root = iterator.next().getValue();
                Demand demand = this.demands.get(root.key);
                if (demand == null || !demand.dormant || demand.publication == null) {
                    iterator.remove();
                    continue;
                }
                root.bytes = this.descendantActiveBytes(root.key);
                if (root.bytes == 0) iterator.remove();
                else dormant += root.bytes;
            }
            this.activeGeometryBytes = Math.max(0, active);
            this.dormantGeometryBytes = Math.max(0, Math.min(dormant,
                    this.activeGeometryBytes));
        }

        long coarsen(long parent) {
            Set<Long> owned = this.demandsByTop.get(topAncestor(parent));
            long bytes = 0;
            boolean hasWork = false;
            if (owned != null) for (long key : owned) {
                Demand child = this.demands.get(key);
                if (key != parent && contains(parent, key) && child != null) {
                    bytes += child.activeGeometryBytes;
                    hasWork |= child.activeGeometryBytes != 0 || child.geometryAccounted.get()
                            || child.publishingGeometryAccounted.get();
                }
            }
            if (!hasWork) return 0;
            this.forgetDormancyForSubtree(parent);
            this.coarseningRoots.add(parent);
            synchronized (this.publicationLock) {
                for (long key : List.copyOf(owned)) {
                    if (key != parent && contains(parent, key)) {
                        this.retireDetailDemand(key);
                    }
                }
                this.publisher.coarsen(parent,
                        () -> this.putEvent(new Coarsened(parent)),
                        failure -> this.putEvent(new CoarsenFailed(parent, failure)));
            }
            // A completed CPU-side replacement is canceled above but was never physical GPU
            // occupancy. Return only bytes the fence will actually release; one is a success
            // sentinel for an all-empty/candidate-only subtree.
            return Math.max(1, bytes);
        }

        static boolean newerEpoch(int candidate, int previous) {
            return previous == -1 || Integer.compareUnsigned(candidate, previous) > 0;
        }

        void retireDetailDemand(long key) {
            Demand demand = this.demands.remove(key);
            if (demand == null || demand.coverage) return;
            this.forgetDormancyForSubtree(key);
            this.discardCompletedGeometry(demand);
            this.releasePublishingGeometryAccounting(demand,
                    demand.publishingAccountToken);
            demand.token++;
            this.releaseAdmission(demand, demand.renderAdmission);
            demand.received = null;
            demand.decoded = null;
            demand.pendingIndex = null;
            demand.pendingOrdinal = -1;
            if (demand.phase == Phase.ACTIVE) this.activeCount--;
            this.setActiveGeometryBytes(demand, 0);
            // One fenced renderer operation owns the whole subtree; do not close each child.
            demand.publication = null;
            long region = regionFor(key);
            removeOwned(this.demandsByRegion, region, key);
            removeOwned(this.demandsByTop, topAncestor(key), key);
            this.pendingBindSet.remove(key);
            if (!this.demandsByRegion.containsKey(region)) this.releaseRegion(region);
        }

        boolean isCoarsening(long key) {
            for (long root : this.coarseningRoots) {
                if (contains(root, key)) return true;
            }
            return false;
        }

        boolean overlapsCoarsening(long key) {
            for (long root : this.coarseningRoots) {
                if (contains(root, key) || contains(key, root)) return true;
            }
            return false;
        }

        void finishCoarsening(long parent, boolean succeeded) {
            this.coarseningRoots.remove(parent);
            long bytes = this.pendingDormantEvictions.remove(parent);
            if (bytes != 0) {
                this.pendingDormantEvictionBytes = Math.max(0,
                        this.pendingDormantEvictionBytes - bytes);
                if (succeeded) this.dormantBytesFreedAfterFences += bytes;
            }
        }

        void addDemand(long key) {
            if (this.demands.containsKey(key)) return;
            Demand demand = new Demand(key);
            this.demands.put(key, demand);
            if (demand.coverage) this.missingCoverage.add(key);
            this.demandsByRegion.computeIfAbsent(regionFor(key), ignored -> new LinkedHashSet<>())
                    .add(key);
            this.demandsByTop.computeIfAbsent(topAncestor(key), ignored -> new LinkedHashSet<>())
                    .add(key);
            RegionalProtocol.RegionIndex index = this.indexFor(key);
            if (index == null) this.ensureRegion(key);
            else {
                int ordinal = index.ordinal(key);
                if (ordinal < 0 || !index.isPresent(ordinal)) this.retireDemand(key);
                else this.bind(demand, index, ordinal);
            }
        }

        boolean addChildren(long parent) {
            RegionalProtocol.RegionIndex index = this.indexFor(parent);
            if (index == null) return false;
            int parentOrdinal = index.ordinal(parent);
            if (parentOrdinal < 0 || !index.isPresent(parentOrdinal)
                    || SectionKey.level(parent) == 0) return true;
            if (!this.coverageBindQueue.isEmpty()) return false;
            int childMask = index.childMask(parentOrdinal);
            int requiredCacheSlots = 0;
            for (int child = 0; child < 8; child++) {
                if ((childMask & 1 << child) == 0) continue;
                long key = child(parent, child);
                if (this.demands.containsKey(key)) continue;
                int childOrdinal = index.ordinal(key);
                if (childOrdinal >= 0 && index.isPresent(childOrdinal)
                        && !index.isEmpty(childOrdinal)) requiredCacheSlots++;
            }
            if (requiredCacheSlots > MAX_STAGE_QUEUE - this.cacheQueue.size()) return false;
            for (int child = 0; child < 8; child++) {
                if ((childMask & 1 << child) == 0) continue;
                long key = child(parent, child);
                this.addDemand(key);
            }
            return true;
        }

        void installIndex(RegionalProtocol.RegionIndex index) {
            long region = regionKey(index.regionX(), index.regionZ());
            if (!Objects.equals(this.regionGenerations.get(region), index.generation())) return;
            this.indexes.put(region, index);
            Set<Long> regional = this.demandsByRegion.get(region);
            if (regional != null) for (long key : List.copyOf(regional)) {
                Demand demand = this.demands.get(key);
                if (demand == null) continue;
                int ordinal = index.ordinal(demand.key);
                if (ordinal < 0 || !index.isPresent(ordinal)) this.retireDemand(demand.key);
                else this.bind(demand, index, ordinal);
            }
        }

        void retireRegion(long region) {
            Set<Long> regional = this.demandsByRegion.get(region);
            if (regional == null) return;
            for (long key : List.copyOf(regional)) this.retireDemand(key);
        }

        void retireDemand(long key) {
            Demand demand = this.demands.get(key);
            if (demand == null) return;
            this.forgetDormancyForSubtree(key);
            this.discardCompletedGeometry(demand);
            this.releasePublishingGeometryAccounting(demand,
                    demand.publishingAccountToken);
            demand.token++;
            this.releaseAdmission(demand, demand.renderAdmission);
            demand.received = null;
            demand.decoded = null;
            if (demand.phase == Phase.ACTIVE) this.activeCount--;
            this.setActiveGeometryBytes(demand, 0);
            if (demand.publication != null) demand.publication.close();
            if (demand.coverage && hasTop(key)) {
                // An authoritative absent section represents air, not coverage work waiting for
                // renderer admission. A later region generation will add it back when it binds.
                this.missingCoverage.remove(key);
                demand.publication = null;
                demand.pendingIndex = null;
                demand.pendingOrdinal = -1;
                demand.index = null;
                demand.ordinal = -1;
                demand.phase = Phase.NEW;
            } else {
                this.missingCoverage.remove(key);
                this.demands.remove(key);
                long region = regionFor(key);
                removeOwned(this.demandsByRegion, region, key);
                removeOwned(this.demandsByTop, topAncestor(key), key);
                if (!this.demandsByRegion.containsKey(region)) this.releaseRegion(region);
            }
        }

        void releaseRegion(long region) {
            this.indexes.remove(region);
            this.regionGenerations.remove(region);
            this.requestedRegions.remove(region);
            this.absentRegions.remove(region);
            if (!this.subscribedRegions.remove(region) || this.quic == null) return;
            try {
                this.quic.releaseRegion((int) region, (int) (region >>> 32));
            } catch (IOException failure) {
                throw new IllegalStateException("cannot release obsolete regional subscription",
                        failure);
            }
        }

        void bind(Demand demand, RegionalProtocol.RegionIndex index, int ordinal) {
            if (ordinal < 0 || !index.isPresent(ordinal)) return;
            if (demand.phase == Phase.READY || demand.phase == Phase.PUBLISHING) {
                demand.pendingIndex = index;
                demand.pendingOrdinal = ordinal;
                return;
            }
            if (demand.index != null && demand.ordinal >= 0 && demand.phase == Phase.ACTIVE
                    && demand.index.sectionFingerprint(demand.ordinal)
                            .equals(index.sectionFingerprint(ordinal))
                    && demand.index.childMask(demand.ordinal) == index.childMask(ordinal)) {
                demand.index = index;
                demand.ordinal = ordinal;
                return;
            }
            if (!index.isEmpty(ordinal) && this.cacheQueue.size() >= MAX_STAGE_QUEUE) {
                if (demand.phase != Phase.ACTIVE) {
                    this.discardCompletedGeometry(demand);
                    demand.token++;
                    this.releaseAdmission(demand, demand.renderAdmission);
                    demand.received = null;
                    demand.decoded = null;
                    demand.phase = Phase.WAITING;
                }
                demand.pendingIndex = index;
                demand.pendingOrdinal = ordinal;
                this.deferBind(demand);
                return;
            }
            demand.pendingIndex = null;
            demand.pendingOrdinal = -1;
            this.pendingBindSet.remove(demand.key);
            this.discardCompletedGeometry(demand);
            demand.token++;
            this.releaseAdmission(demand, demand.renderAdmission);
            if (demand.phase == Phase.ACTIVE) {
                this.activeCount--;
                if (demand.coverage) this.missingCoverage.add(demand.key);
            }
            demand.index = index;
            demand.ordinal = ordinal;
            demand.detailRejected = false;
            demand.decoded = null;
            demand.received = null;
            if (demand.coverage) this.missingCoverage.add(demand.key);
            this.queueBound(demand);
        }

        void queueBound(Demand demand) {
            demand.phase = Phase.NEW;
            if (demand.index.isEmpty(demand.ordinal)) {
                demand.phase = Phase.DECODED;
                this.enqueueMesh(demand);
            } else {
                demand.phase = Phase.CACHE;
                this.enqueueStage(this.cacheQueue, demand);
            }
        }

        void processStages() throws Exception {
            this.scheduleReadyPublications();
            this.scheduleMeshing();
            this.scheduleDecoding();
            this.scheduleCache();
            this.admitPendingBinds();
            while (this.inFlightBatches < MAX_IN_FLIGHT_BATCHES) {
                List<Demand> selected = this.selectNetworkBatch();
                if (selected.isEmpty()) break;
                if (!this.sendBatch(selected)) break;
            }
        }

        void admitPendingBinds() {
            while (this.cacheQueue.size() < MAX_STAGE_QUEUE) {
                ArrayDeque<Long> queue = this.coverageBindQueue.isEmpty()
                        ? this.refinementBindQueue : this.coverageBindQueue;
                Long key = queue.pollFirst();
                if (key == null) return;
                if (!this.pendingBindSet.remove(key)) continue;
                Demand demand = this.demands.get(key);
                if (demand == null || demand.pendingIndex == null
                        || demand.pendingOrdinal < 0) continue;
                RegionalProtocol.RegionIndex index = demand.pendingIndex;
                int ordinal = demand.pendingOrdinal;
                demand.pendingIndex = null;
                demand.pendingOrdinal = -1;
                this.bind(demand, index, ordinal);
            }
        }

        void deferBind(Demand demand) {
            if (!this.pendingBindSet.add(demand.key)) return;
            (demand.coverage ? this.coverageBindQueue : this.refinementBindQueue)
                    .addLast(demand.key);
        }

        void ensureRegion(long key) { this.queueRegion(regionFor(key)); }

        void queueRegion(long region) {
            if (this.indexes.containsKey(region) || this.absentRegions.contains(region)
                    || this.requestedRegions.contains(region) || !this.queuedRegions.add(region)) return;
            if (this.regionQueue.size() >= MAX_STAGE_QUEUE) {
                throw new IllegalStateException("regional request queue exceeded its safety bound");
            }
            this.regionQueue.addLast(region);
        }

        void processRegions() throws IOException {
            if (this.worldIdentity == null) return;
            while (this.requestedRegions.size() < MAX_IN_FLIGHT_INDEXES) {
                Long region = this.regionQueue.pollFirst();
                if (region == null) return;
                this.queuedRegions.remove(region);
                if (this.indexes.containsKey(region) || this.absentRegions.contains(region)
                        || !this.demandsByRegion.containsKey(region)
                        || !this.requestedRegions.add(region)) continue;
                this.quic.requestRegion((int) region.longValue(), (int) (region >>> 32));
                this.subscribedRegions.add(region);
            }
        }

        void lookupCache(Demand demand) {
            int token = demand.token;
            long key = demand.key;
            RegionalProtocol.RegionIndex index = demand.index;
            int ordinal = demand.ordinal;
            this.sectionWorkers.execute(() -> {
                try {
                    putEvent(new CacheResult(key, token, this.cache.get(index, ordinal)));
                } catch (Throwable failure) {
                    putEvent(new CacheResult(key, token, null));
                } finally {
                    this.sectionTaskSlots.release();
                    signal();
                }
            });
        }

        void acceptCache(CacheResult result) {
            Demand demand = this.demands.get(result.key);
            if (!current(demand, result.token, Phase.CACHE)) return;
            if (result.compressed == null) {
                demand.phase = Phase.NETWORK;
                this.enqueueStage(demand.coverage
                        ? this.coverageNetworkQueue : this.refinementNetworkQueue, demand);
            } else {
                demand.received = result.compressed;
                demand.receivedFromCache = true;
                demand.phase = Phase.RECEIVED;
                this.enqueueStage(this.decodeQueue, demand);
            }
        }

        void acceptCacheCorrupt(CacheCorrupt result) {
            Demand demand = this.demands.get(result.key);
            if (current(demand, result.token, Phase.DECODING)) {
                demand.phase = Phase.NETWORK;
                this.enqueueStage(demand.coverage
                        ? this.coverageNetworkQueue : this.refinementNetworkQueue, demand);
            }
        }

        void scheduleCache() {
            int remaining = this.cacheQueue.size();
            while (remaining-- > 0 && this.sectionTaskSlots.tryAcquire()) {
                StageRef ref = this.cacheQueue.pollFirst();
                if (ref == null) {
                    this.sectionTaskSlots.release();
                    return;
                }
                Demand demand = this.demands.get(ref.key);
                if (!current(demand, ref.token, Phase.CACHE)) {
                    this.sectionTaskSlots.release();
                    continue;
                }
                if (!this.hasPipelineHeadroom(demand.coverage)) {
                    this.sectionTaskSlots.release();
                    this.cacheQueue.addLast(ref);
                    continue;
                }
                this.cacheLookupsInFlight++;
                this.lookupCache(demand);
            }
        }

        List<Demand> selectNetworkBatch() {
            List<Demand> result = new ArrayList<>(REQUEST_BATCH);
            long available = MAX_IN_FLIGHT_BYTES - this.inFlightBytes;
            if (available <= 0) return result;
            ArrayDeque<StageRef> queue = this.coverageNetworkQueue.isEmpty()
                    ? this.refinementNetworkQueue : this.coverageNetworkQueue;
            boolean coverage = queue == this.coverageNetworkQueue;
            int sectionHeadroom = this.pipelineHeadroom(coverage);
            if (sectionHeadroom <= 0) return result;
            long bytes = 0;
            RegionalProtocol.RegionIndex batchIndex = null;
            while (result.size() < Math.min(REQUEST_BATCH, sectionHeadroom)) {
                StageRef ref = queue.peekFirst();
                if (ref == null) break;
                Demand demand = this.demands.get(ref.key);
                if (!current(demand, ref.token, Phase.NETWORK)) {
                    queue.removeFirst();
                    continue;
                }
                if (batchIndex != null && demand.index != batchIndex) break;
                long next = demand.index.compressedLength(demand.ordinal);
                if (next > MAX_IN_FLIGHT_BYTES) {
                    throw new IllegalStateException(
                            "one indexed section exceeds the network byte ceiling");
                }
                if (bytes + next > available) break;
                queue.removeFirst();
                if (batchIndex == null) batchIndex = demand.index;
                result.add(demand);
                bytes += next;
            }
            return result;
        }

        boolean sendBatch(List<Demand> selected) throws IOException {
            long epoch = ++this.requestEpoch;
            if (epoch == 0) epoch = ++this.requestEpoch;
            long reservedBytes = selected.stream()
                    .mapToLong(demand -> demand.index.compressedLength(demand.ordinal)).sum();
            RegionalProtocol.RegionIndex index = selected.getFirst().index;
            List<Integer> ordinals = new ArrayList<>(selected.size());
            for (Demand demand : selected) {
                if (demand.index != index) {
                    throw new IOException("regional request batch spans multiple indexes");
                }
                ordinals.add(demand.ordinal);
            }
            RegionalProtocol.Lane lane = selected.getFirst().coverage
                    ? RegionalProtocol.Lane.COVERAGE : RegionalProtocol.Lane.REFINEMENT;
            boolean accepted = this.quic.requestSections(lane, epoch, index, ordinals,
                    new RegionalQuicClient.BatchReceiver() {
                        @Override public void batch(List<RegionalProtocol.SectionReply> replies) {
                            for (RegionalProtocol.SectionReply reply : replies) {
                                putEvent(new SectionResult(reply));
                            }
                        }
                        @Override public void complete() {
                            putEvent(new BatchComplete(reservedBytes, selected.size()));
                        }
                        @Override public void failed(Throwable failure) {
                            putEvent(new WorkerFailed(failure));
                        }
                    });
            if (!accepted) {
                ArrayDeque<StageRef> queue = selected.getFirst().coverage
                        ? this.coverageNetworkQueue : this.refinementNetworkQueue;
                for (Demand demand : selected) this.enqueueStage(queue, demand);
                return false;
            }
            for (Demand demand : selected) demand.phase = Phase.REQUESTED;
            this.inFlightBatches++;
            this.inFlightSections += selected.size();
            this.inFlightBytes += reservedBytes;
            return true;
        }

        void acceptSection(RegionalProtocol.SectionReply reply) throws IOException {
            Demand demand = this.demands.get(reply.key());
            if (demand == null || demand.phase != Phase.REQUESTED
                    || demand.index.generation() != reply.generation()) return;
            if (demand.ordinal != reply.ordinal()) return;
            switch (reply.status()) {
                case DATA -> {
                    if (reply.compressed().length
                            != demand.index.compressedLength(demand.ordinal)) {
                        throw new IOException("regional section reply disagrees with its index");
                    }
                    this.receivedBytes += reply.compressed().length;
                    demand.received = reply.compressed();
                    demand.receivedFromCache = false;
                    demand.phase = Phase.RECEIVED;
                    this.enqueueStage(this.decodeQueue, demand);
                }
                case EMPTY -> {
                    if (!demand.index.isEmpty(demand.ordinal)) {
                        throw new IOException("unexpected empty regional section");
                    }
                    demand.phase = Phase.DECODED;
                    this.enqueueMesh(demand);
                }
                case STALE -> {
                    long region = regionFor(demand.key);
                    this.indexes.remove(region);
                    this.requestedRegions.remove(region);
                    this.queueRegion(region);
                }
                case ABSENT -> throw new IOException("indexed regional section became absent");
            }
        }

        void scheduleDecoding() {
            if (this.mappings == null
                    || !this.catalogFingerprint.equals(this.requiredCatalogFingerprint)) return;
            int remaining = this.decodeQueue.size();
            while (remaining-- > 0 && this.sectionTaskSlots.tryAcquire()) {
                StageRef ref = this.decodeQueue.pollFirst();
                if (ref == null) {
                    this.sectionTaskSlots.release();
                    return;
                }
                Demand demand = this.demands.get(ref.key);
                if (!current(demand, ref.token, Phase.RECEIVED)) {
                    this.sectionTaskSlots.release();
                    continue;
                }
                if (!this.hasRenderAdmissionHeadroom(demand.coverage)) {
                    this.sectionTaskSlots.release();
                    this.decodeQueue.addLast(ref);
                    continue;
                }
                this.decodesInFlight++;
                this.decode(demand);
            }
        }

        void decode(Demand demand) {
            demand.phase = Phase.DECODING;
            long key = demand.key;
            int token = demand.token;
            RegionalProtocol.RegionIndex index = demand.index;
            int ordinal = demand.ordinal;
            RegionalSectionCodec.Mappings mappings = this.mappings;
            byte[] compressed = demand.received;
            boolean cached = demand.receivedFromCache;
            demand.received = null;
            this.sectionWorkers.execute(() -> {
                try {
                    byte[] canonical = this.codec.decompress(
                            compressed, index.canonicalLength(ordinal));
                    RegionalSectionCodec.SectionData section = this.codec.decode(
                            key, index.childMask(ordinal), canonical,
                            index.sectionFingerprint(ordinal), mappings);
                    putEvent(new DecodedResult(key, token, section));
                    if (!cached) {
                        try { this.cache.put(index, ordinal, compressed); }
                        catch (IOException ignored) {}
                    }
                } catch (Throwable failure) {
                    if (cached) this.cache.quarantine(index, ordinal);
                    putEvent(cached ? new CacheCorrupt(key, token) : new WorkerFailed(failure));
                } finally {
                    this.sectionTaskSlots.release();
                    signal();
                }
            });
        }

        void acceptDecoded(DecodedResult result) {
            Demand demand = this.demands.get(result.key);
            if (!current(demand, result.token, Phase.DECODING)) return;
            demand.decoded = result.section;
            this.mesher.requestModels(result.section);
            demand.phase = Phase.DECODED;
            this.enqueueMesh(demand);
        }

        void scheduleMeshing() {
            int remaining = this.meshQueue.size();
            while (remaining-- > 0) {
                if (this.renderAdmissionSlots.availablePermits() == 0) return;
                StageRef ref = this.meshQueue.pollFirst();
                if (ref == null) return;
                Demand demand = this.demands.get(ref.key);
                if (!current(demand, ref.token, Phase.DECODED)) continue;
                boolean empty = demand.index.isEmpty(demand.ordinal);
                if (!empty && !this.mesher.modelsReady(demand.decoded)) {
                    this.meshQueue.addLast(ref);
                    continue;
                }
                if (!empty && !this.sectionTaskSlots.tryAcquire()) {
                    this.meshQueue.addFirst(ref);
                    return;
                }
                RenderAdmission admission = this.tryRenderAdmission(demand);
                if (admission == null) {
                    if (!empty) this.sectionTaskSlots.release();
                    this.meshQueue.addFirst(ref);
                    return;
                }
                demand.renderAdmission = admission;
                if (empty) {
                    this.publishEmpty(demand, admission);
                    continue;
                }
                demand.phase = Phase.MESHING;
                long key = demand.key; int token = demand.token;
                RegionalSectionCodec.SectionData decoded = demand.decoded;
                try {
                    this.sectionWorkers.execute(() -> {
                        try {
                            putEvent(new MeshedResult(key, token,
                                    this.mesher.mesh(decoded, token + 1L), admission));
                        } catch (Throwable failure) {
                            admission.release();
                            putEvent(new WorkerFailed(failure));
                        } finally {
                            this.sectionTaskSlots.release();
                            signal();
                        }
                    });
                } catch (RuntimeException failure) {
                    this.sectionTaskSlots.release();
                    this.releaseAdmission(demand, admission);
                    throw failure;
                }
            }
        }

        void acceptMeshed(MeshedResult result) {
            Demand demand = this.demands.get(result.key);
            if (!current(demand, result.token, Phase.MESHING)
                    || demand.renderAdmission != result.admission) {
                result.geometry.free();
                result.admission.release();
                return;
            }
            this.completeGeometry(demand, result.geometry);
        }

        void publishEmpty(Demand demand, RenderAdmission admission) {
            BuiltSection empty = BuiltSection.emptyWithChildren(demand.key, demand.token + 1L,
                    (byte) demand.index.childMask(demand.ordinal));
            this.completeGeometry(demand, empty);
        }

        void completeGeometry(Demand demand, BuiltSection geometry) {
            demand.renderAdmission.transferGeometryReservation();
            if (!demand.geometryAccounted.compareAndSet(false, true)) {
                geometry.free();
                throw new IllegalStateException("regional geometry was completed twice");
            }
            demand.completedGeometry = geometry;
            demand.geometryBytes = geometry.geometryBuffer == null ? 0
                    : (geometry.geometryBuffer.size + 1023L) & ~1023L;
            int level = SectionKey.level(demand.key);
            this.detailGeometryEstimateBytes[level] = Math.max(
                    this.detailGeometryEstimateBytes[level], demand.geometryBytes);
            demand.geometryAccountToken = demand.token;
            this.completedGeometryBytes.addAndGet(demand.geometryBytes);
            demand.phase = Phase.READY;
            if (this.readyPublicationQueue.size() >= MAX_STAGE_QUEUE) {
                this.discardCompletedGeometry(demand);
                this.releaseAdmission(demand, demand.renderAdmission);
                throw new IllegalStateException(
                        "regional ready-publication queue exceeded its safety bound");
            }
            StageRef ref = new StageRef(demand.key, demand.token);
            if (demand.coverage) this.readyPublicationQueue.addFirst(ref);
            else this.readyPublicationQueue.addLast(ref);
        }

        void scheduleReadyPublications() throws InterruptedException {
            int remaining = this.readyPublicationQueue.size();
            while (remaining-- > 0) {
                StageRef ref = this.readyPublicationQueue.pollFirst();
                if (ref == null) return;
                Demand demand = this.demands.get(ref.key);
                if (!current(demand, ref.token, Phase.READY)
                        || demand.completedGeometry == null) continue;
                if (!demand.coverage && demand.geometryBytes > this.geometryTargetBytes()) {
                    this.rejectDetailGroup(demand, true);
                    continue;
                }
                if (!demand.coverage && this.geometryDeficitBytes() > 0) {
                    long now = System.nanoTime();
                    if (demand.budgetBlockedSinceNanos == 0) {
                        demand.budgetBlockedSinceNanos = now;
                    } else if (now - demand.budgetBlockedSinceNanos
                            >= TimeUnit.SECONDS.toNanos(5) && this.coarseningRoots.isEmpty()) {
                        this.rejectDetailGroup(demand, false);
                        continue;
                    }
                    this.readyPublicationQueue.addLast(ref);
                    continue;
                }
                long physicalDeficit = this.physicalGeometryDeficitBytes();
                if (physicalDeficit > 0) {
                    this.evictDormant(physicalDeficit, true);
                    if (this.physicalGeometryDeficitBytes() > 0) {
                        this.readyPublicationQueue.addLast(ref);
                        continue;
                    }
                }
                demand.budgetBlockedSinceNanos = 0;
                BuiltSection geometry = demand.completedGeometry;
                demand.completedGeometry = null;
                demand.phase = Phase.PUBLISHING;
                try {
                    enqueueMain(new PublishTask(this, demand, ref.token, geometry,
                            demand.publication, demand.renderAdmission));
                } catch (InterruptedException interrupted) {
                    geometry.free();
                    this.releaseGeometryAccounting(demand, ref.token);
                    this.releaseAdmission(demand, demand.renderAdmission);
                    throw interrupted;
                }
            }
        }

        void rejectDetailGroup(Demand demand, boolean oversize) {
            Logger.warn(oversize
                    ? "Skipping regional detail section requiring " + demand.geometryBytes
                            + " bytes; target is " + this.geometryTargetBytes()
                    : "Skipping regional detail section after no subtree made its "
                            + demand.geometryBytes + " bytes fit");
            long parent = parent(demand.key);
            Demand retained = this.demands.get(parent);
            if (oversize && retained != null) retained.detailRejected = true;
            if (retained != null && retained.phase == Phase.ACTIVE
                    && !this.overlapsCoarsening(parent)
                    && this.coarsen(parent) > 0) return;
            this.retireDetailDemand(demand.key);
        }

        long geometryTargetBytes() {
            return this.renderer.regionalGeometryCapacityBytes() * 825L / 1_000L;
        }

        long dormantCapBytes() {
            return this.renderer.regionalGeometryCapacityBytes() / 3L;
        }

        long selectedGeometryBytes() {
            return Math.max(0, this.activeGeometryBytes - this.dormantGeometryBytes);
        }

        long geometryDeficitBytes() {
            return this.selectedGeometryBytes()
                    + this.completedGeometryBytes.get() + this.publishingGeometryBytes.get()
                    + this.meshingReservationBytes.get()
                    - this.geometryTargetBytes();
        }

        long physicalGeometryDeficitBytes() {
            return this.renderer.regionalGeometryUsedBytes()
                    + this.completedGeometryBytes.get() - this.pendingDormantEvictionBytes
                    - this.renderer.regionalGeometryPublicationLimitBytes();
        }

        void discardCompletedGeometry(Demand demand) {
            BuiltSection geometry = demand.completedGeometry;
            demand.completedGeometry = null;
            if (geometry != null) geometry.free();
            this.releaseGeometryAccounting(demand, demand.geometryAccountToken);
        }

        boolean releaseGeometryAccounting(Demand demand, int token) {
            synchronized (demand) {
                if (demand.geometryAccountToken != token
                        || !demand.geometryAccounted.compareAndSet(true, false)) return false;
                this.completedGeometryBytes.addAndGet(-demand.geometryBytes);
                return true;
            }
        }

        void transferGeometryAccounting(Demand demand, int token) {
            synchronized (demand) {
                if (!this.releaseGeometryAccounting(demand, token)) return;
                demand.publishingAccountToken = token;
                if (!demand.publishingGeometryAccounted.compareAndSet(false, true)) {
                    throw new IllegalStateException(
                            "regional publishing geometry was accounted twice");
                }
                this.publishingGeometryBytes.addAndGet(demand.geometryBytes);
            }
        }

        void releasePublishingGeometryAccounting(Demand demand, int token) {
            synchronized (demand) {
                if (demand.publishingAccountToken != token
                        || !demand.publishingGeometryAccounted.compareAndSet(true, false)) return;
                this.publishingGeometryBytes.addAndGet(-demand.geometryBytes);
            }
        }

        void releaseAllGeometryAccounting(Demand demand, int token) {
            this.releaseGeometryAccounting(demand, token);
            this.releasePublishingGeometryAccounting(demand, token);
        }

        void acceptPublication(PublicationQueued result) throws IOException {
            Demand demand = this.demands.get(result.key);
            if (demand != result.demand || !current(demand, result.token, Phase.PUBLISHING)
                    || demand.renderAdmission != result.admission) {
                if (!this.isCoarsening(result.key)) result.publication.close();
                this.releaseAllGeometryAccounting(result.demand, result.token);
                result.admission.release();
                return;
            }
            demand.publication = result.publication;
            if (this.publicationQueue.size() >= MAX_STAGE_QUEUE) {
                throw new IllegalStateException(
                        "regional publication queue exceeded its safety bound");
            }
            this.publicationQueue.addLast(new PublicationRef(demand.key, demand.token,
                    result.admission));
        }

        void pollPublications() throws IOException {
            int remaining = this.publicationQueue.size();
            while (remaining-- > 0) {
                PublicationRef ref = this.publicationQueue.pollFirst();
                if (ref == null) return;
                Demand demand = this.demands.get(ref.key);
                if (!current(demand, ref.token, Phase.PUBLISHING)
                        || demand.publication == null
                        || demand.renderAdmission != ref.admission) {
                    ref.admission.release();
                    continue;
                }
                Optional<Throwable> failure = demand.publication.activationFailure();
                if (failure.isPresent()) {
                    this.releaseAllGeometryAccounting(demand, ref.token);
                    this.releaseAdmission(demand, ref.admission);
                    throw new IOException("regional renderer publication failed",
                            failure.orElseThrow());
                }
                if (!demand.publication.activationFencePassed()) {
                    this.publicationQueue.addLast(ref);
                    continue;
                }
                demand.phase = Phase.ACTIVE;
                this.setActiveGeometryBytes(demand, demand.geometryBytes);
                this.activeCount++;
                if (demand.coverage) this.missingCoverage.remove(demand.key);
                demand.decoded = null;
                this.activated++;
                this.releaseAllGeometryAccounting(demand, ref.token);
                this.releaseAdmission(demand, ref.admission);
                if (demand.pendingIndex != null) {
                    RegionalProtocol.RegionIndex index = demand.pendingIndex;
                    int ordinal = demand.pendingOrdinal;
                    demand.pendingIndex = null; demand.pendingOrdinal = -1;
                    this.bind(demand, index, ordinal);
                }
            }
        }

        RenderAdmission tryRenderAdmission(Demand demand) {
            int available = this.renderAdmissionSlots.availablePermits();
            if (available == 0 || (!demand.coverage
                    && available <= this.renderAdmissionReserve())) return null;
            long reservation = demand.coverage || demand.index.isEmpty(demand.ordinal) ? 0
                    : this.detailGeometryEstimateBytes[SectionKey.level(demand.key)];
            if (!demand.coverage && reservation != 0
                    && this.geometryDeficitBytes() + reservation > 0) return null;
            if (!this.renderAdmissionSlots.tryAcquire()) return null;
            this.meshingReservationBytes.addAndGet(reservation);
            RenderAdmission admission = new RenderAdmission(this, reservation);
            this.renderAdmissions.add(admission);
            return admission;
        }

        boolean hasPipelineHeadroom(boolean coverage) {
            return this.pipelineHeadroom(coverage) > 0;
        }

        boolean hasRenderAdmissionHeadroom(boolean coverage) {
            int reserve = coverage ? 0 : this.renderAdmissionReserve();
            return this.renderAdmissionSlots.availablePermits() > reserve;
        }

        int renderAdmissionReserve() {
            for (long key : this.missingCoverage) {
                if (!this.absentRegions.contains(regionFor(key))) {
                    return COVERAGE_RENDER_RESERVE;
                }
            }
            return 0;
        }

        int pipelineHeadroom(boolean coverage) {
            int reserved = coverage ? 0 : this.renderAdmissionReserve();
            int committed = this.inFlightSections + this.cacheLookupsInFlight
                    + this.decodeQueue.size() + this.decodesInFlight + this.meshQueue.size();
            return Math.max(0, this.renderAdmissionSlots.availablePermits()
                    - reserved - committed);
        }

        void releaseAdmission(Demand demand, RenderAdmission admission) {
            if (admission == null) return;
            if (demand != null && demand.renderAdmission == admission) {
                demand.renderAdmission = null;
            }
            admission.release();
        }

        RegionalProtocol.RegionIndex indexFor(long key) {
            return this.indexes.get(regionFor(key));
        }

        void enqueueStage(ArrayDeque<StageRef> queue, Demand demand) {
            if (queue.size() >= MAX_STAGE_QUEUE) {
                throw new IllegalStateException("regional stage queue exceeded its safety bound");
            }
            queue.addLast(new StageRef(demand.key, demand.token));
        }

        void enqueueMesh(Demand demand) {
            if (this.meshQueue.size() >= MAX_STAGE_QUEUE) {
                throw new IllegalStateException("regional stage queue exceeded its safety bound");
            }
            StageRef ref = new StageRef(demand.key, demand.token);
            if (demand.coverage) this.meshQueue.addFirst(ref);
            else this.meshQueue.addLast(ref);
        }

        void clearStageQueues() {
            this.regionQueue.clear();
            this.queuedRegions.clear();
            this.cacheQueue.clear();
            this.coverageNetworkQueue.clear();
            this.refinementNetworkQueue.clear();
            this.decodeQueue.clear();
            this.meshQueue.clear();
            this.readyPublicationQueue.clear();
            PublicationRef publication;
            while ((publication = this.publicationQueue.pollFirst()) != null) {
                publication.admission.release();
            }
            this.coverageBindQueue.clear();
            this.refinementBindQueue.clear();
            this.pendingBindSet.clear();
        }

        void putEvent(Event event) {
            while (this.open.get()) {
                try {
                    if (this.events.offer(event, 100, TimeUnit.MILLISECONDS)) {
                        signal();
                        return;
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            discardEvent(event);
        }

        void signal() { this.wakeup.release(); }
        void fail(Throwable failure) {
            this.failure = failure;
            this.open.set(false);
            signal();
            if (this.quic != null) this.quic.close();
        }

        String snapshot() {
            return "regional=ACTIVE dimension=" + this.dimension + " desired=" + this.demands.size()
                    + " active=" + this.activeCount + " regions=" + this.indexes.size()
                    + " coarsening=" + this.coarseningRoots.size()
                    + " batches=" + this.inFlightBatches + " inFlightBytes=" + this.inFlightBytes
                    + " renderPending=" + this.renderAdmissions.size()
                    + " detailFrontier=" + this.frontier.size()
                    + " detailDropped=" + this.frontier.dropped()
                    + " coverageMissing=" + this.missingCoverage.size()
                    + " meshQueue=" + this.meshQueue.size()
                    + " readyQueue=" + this.readyPublicationQueue.size()
                    + " publishQueue=" + this.publicationQueue.size()
                    + " geometryUsed=" + this.renderer.regionalGeometryUsedBytes()
                    + " geometryPhysicalLimit="
                    + this.renderer.regionalGeometryPublicationLimitBytes()
                    + " geometryActive=" + this.activeGeometryBytes
                    + " geometrySelected=" + this.selectedGeometryBytes()
                    + " geometryDormant=" + this.dormantGeometryBytes
                    + " geometryDormantCap=" + this.dormantCapBytes()
                    + " dormantRoots=" + this.dormantRoots.size()
                    + " dormantPendingFree=" + this.pendingDormantEvictionBytes
                    + " geometryCompleted=" + this.completedGeometryBytes.get()
                    + " geometryPublishing=" + this.publishingGeometryBytes.get()
                    + " geometryMeshingReserved=" + this.meshingReservationBytes.get()
                    + " geometryTarget=" + this.geometryTargetBytes()
                    + " dormancyTransitions=" + this.dormancyTransitions
                    + " wakes=" + this.wakes + " instantWakes=" + this.instantWakes
                    + " dormantCapEvictions=" + this.capEvictions
                    + " dormantAdmissionEvictions=" + this.admissionEvictions
                    + " dormantFreed=" + this.dormantBytesFreedAfterFences
                    + " dormantLastEvictionDistance2=" + this.lastEvictionDistanceSquared
                    + " dormantLastEvictionBucket=" + this.lastEvictionBucket
                    + " dormantLastEvictionAge=" + this.lastEvictionAge
                    + " geometryAccountingCorrections=" + this.correctiveAccountingRebuilds
                    + " received=" + this.receivedBytes
                    + " failure=" + String.valueOf(this.failure);
        }

        @Override public void close() {
            if (!this.open.getAndSet(false)) return;
            signal();
            this.thread.interrupt();
            if (this.quic != null) this.quic.close();
        }

        void release() {
            if (this.quic != null) this.quic.close();
            this.sectionWorkers.shutdownNow();
            try { this.sectionWorkers.awaitTermination(5, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            this.codec.close();
            if (this.cache != null) this.cache.close();
            for (Demand demand : this.demands.values()) {
                this.discardCompletedGeometry(demand);
                if (demand.publication != null) demand.publication.close();
            }
            Event event;
            while ((event = this.events.poll()) != null) discardEvent(event);
            for (RenderAdmission admission : List.copyOf(this.renderAdmissions)) {
                admission.release();
            }
            this.demands.clear();
            this.demandsByRegion.clear();
            this.demandsByTop.clear();
            this.missingCoverage.clear();
            this.subscribedRegions.clear();
            this.frontier.clear();
            this.coarseningRoots.clear();
            this.dormantRoots.clear();
            this.pendingDormantEvictions.clear();
            this.activeGeometryBytes = 0;
            this.dormantGeometryBytes = 0;
            this.pendingDormantEvictionBytes = 0;
            this.publishingGeometryBytes.set(0);
            this.meshingReservationBytes.set(0);
            this.clearStageQueues();
        }
    }

    private record DemandEvent(boolean add, long key) {}
    private record StageRef(long key, int token) {}
    private record PublicationRef(long key, int token, RenderAdmission admission) {}

    private sealed interface Event permits CatalogReady, IndexReady, CacheResult, CacheCorrupt,
            SectionResult, DecodedResult, MeshedResult, PublicationQueued, Coarsened,
            CoarsenFailed, BatchComplete, WorkerFailed {}
    private record CatalogReady(RegionalProtocol.Hash32 fingerprint,
                                RegionalSectionCodec.Mappings mappings) implements Event {}
    private record IndexReady(RegionalProtocol.RegionMessage message,
                              RegionalProtocol.RegionIndex index) implements Event {}
    private record CacheResult(long key, int token, byte[] compressed) implements Event {}
    private record CacheCorrupt(long key, int token) implements Event {}
    private record SectionResult(RegionalProtocol.SectionReply reply) implements Event {}
    private record DecodedResult(long key, int token,
                                 RegionalSectionCodec.SectionData section) implements Event {}
    private record MeshedResult(long key, int token, BuiltSection geometry,
                                RenderAdmission admission) implements Event {}
    private record PublicationQueued(Session owner, Demand demand, long key, int token,
                                     VoxyRenderSystem.SectionPublication publication,
                                     RenderAdmission admission)
            implements Event {}
    private record Coarsened(long parent) implements Event {}
    private record CoarsenFailed(long parent, Throwable failure) implements Event {}
    private record BatchComplete(long reservedBytes, int sectionCount) implements Event {}
    private record WorkerFailed(Throwable failure) implements Event {}

    private interface MainTask {
        Session owner();
        void run(VoxyRenderSystem renderer) throws Exception;
        default void cancel() {}
    }

    private record CatalogTask(Session owner, CatalogCodec.Catalog catalog,
                               RegionalProtocol.Hash32 fingerprint) implements MainTask {
        @Override public void run(VoxyRenderSystem renderer) {
            CatalogMapper mapper = renderer.getMapper();
            int[] blocks = new int[this.catalog.blocks().size()];
            int[] biomes = new int[this.catalog.biomes().size()];
            for (int index = 0; index < blocks.length; index++) {
                blocks[index] = mapper.getIdForBlockState(
                        parseCanonicalState(this.catalog.blocks().get(index).canonical()));
            }
            for (int index = 0; index < biomes.length; index++) {
                biomes[index] = mapper.getIdForBiome(
                        requireCanonicalBiome(this.catalog.biomes().get(index)));
            }
            this.owner.putEvent(new CatalogReady(this.fingerprint,
                    new RegionalSectionCodec.Mappings(blocks, biomes)));
        }
    }

    private record PublishTask(Session owner, Demand demand, int token, BuiltSection geometry,
                               VoxyRenderSystem.SectionPublication previous,
                               RenderAdmission admission) implements MainTask {
        @Override public void run(VoxyRenderSystem renderer) {
            VoxyRenderSystem.SectionPublication publication;
            synchronized (this.owner.publicationLock) {
                if (this.demand.token != this.token
                        || this.demand.phase != Phase.PUBLISHING
                        || this.demand.renderAdmission != this.admission) {
                    this.geometry.free();
                    this.owner.releaseAllGeometryAccounting(this.demand, this.token);
                    this.admission.release();
                    return;
                }
                publication = this.owner.publisher.publish(this.demand.key, this.geometry,
                        Optional.ofNullable(this.previous), () -> this.demand.token == this.token
                                && this.demand.phase == Phase.PUBLISHING
                                && this.demand.renderAdmission == this.admission
                                && this.owner.open.get(),
                        () -> this.owner.transferGeometryAccounting(this.demand, this.token));
            }
            this.owner.putEvent(new PublicationQueued(this.owner, this.demand, this.demand.key,
                    this.token, publication, this.admission));
        }
        @Override public void cancel() {
            this.geometry.free();
            this.owner.releaseAllGeometryAccounting(this.demand, this.token);
            this.admission.release();
        }
    }

    private static void discardEvent(Event event) {
        if (event instanceof MeshedResult meshed) {
            meshed.geometry.free();
            meshed.admission.release();
        } else if (event instanceof PublicationQueued queued) {
            queued.publication.close();
            queued.owner.releaseAllGeometryAccounting(queued.demand, queued.token);
            queued.admission.release();
        }
    }

    private static void enqueueMain(MainTask task) throws InterruptedException {
        while (task.owner().open.get()) {
            if (MAIN.offer(task, 100, TimeUnit.MILLISECONDS)) return;
        }
        task.cancel();
    }

    private static boolean current(Demand demand, int token, Phase phase) {
        return demand != null && demand.token == token && demand.phase == phase;
    }

    private static void removeOwned(Map<Long, LinkedHashSet<Long>> ownership,
                                    long owner, long key) {
        LinkedHashSet<Long> values = ownership.get(owner);
        if (values == null) return;
        values.remove(key);
        if (values.isEmpty()) ownership.remove(owner);
    }

    private static boolean hasTop(long top) {
        synchronized (TOP_LEVEL) { return TOP_LEVEL.contains(top); }
    }

    private static long topAncestor(long key) {
        int shift = SectionKey.MAX_LOD_LAYER - SectionKey.level(key);
        return SectionKey.pack(SectionKey.MAX_LOD_LAYER, SectionKey.x(key) >> shift,
                SectionKey.y(key) >> shift, SectionKey.z(key) >> shift);
    }

    private static long child(long parent, int child) {
        int level = SectionKey.level(parent) - 1;
        return SectionKey.pack(level, SectionKey.x(parent) * 2 + (child & 1),
                SectionKey.y(parent) * 2 + (child >>> 2 & 1),
                SectionKey.z(parent) * 2 + (child >>> 1 & 1));
    }

    private static long parent(long child) {
        int level = SectionKey.level(child) + 1;
        return SectionKey.pack(level, SectionKey.x(child) >> 1,
                SectionKey.y(child) >> 1, SectionKey.z(child) >> 1);
    }

    private static boolean contains(long ancestor, long descendant) {
        int shift = SectionKey.level(ancestor) - SectionKey.level(descendant);
        return shift >= 0
                && SectionKey.x(ancestor) == SectionKey.x(descendant) >> shift
                && SectionKey.y(ancestor) == SectionKey.y(descendant) >> shift
                && SectionKey.z(ancestor) == SectionKey.z(descendant) >> shift;
    }

    private static int regionX(long key) {
        return Math.floorDiv(SectionKey.x(key), 16 >> SectionKey.level(key));
    }
    private static int regionZ(long key) {
        return Math.floorDiv(SectionKey.z(key), 16 >> SectionKey.level(key));
    }
    private static long regionFor(long key) { return regionKey(regionX(key), regionZ(key)); }
    private static long regionKey(int x, int z) {
        return Integer.toUnsignedLong(x) | Integer.toUnsignedLong(z) << 32;
    }
    private static RegionalProtocol.Hash32 hash32(byte[] bytes) {
        byte[] hash = new Blake3.Hasher().update(bytes).digest();
        return new RegionalProtocol.Hash32(leLong(hash, 0), leLong(hash, 8),
                leLong(hash, 16), leLong(hash, 24));
    }
    private static long leLong(byte[] bytes, int offset) {
        return ByteBuffer.wrap(bytes, offset, 8).order(ByteOrder.LITTLE_ENDIAN).getLong();
    }

    private static BlockState parseCanonicalState(String canonical) {
        int bracket = canonical.indexOf('[');
        if (bracket == 0 || bracket >= 0 && !canonical.endsWith("]")) {
            throw new IllegalArgumentException("malformed canonical block state");
        }
        String blockName = bracket < 0 ? canonical : canonical.substring(0, bracket);
        ResourceLocation id = ResourceLocation.tryParse(blockName);
        if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) {
            throw new IllegalArgumentException("server catalog names an unavailable block: "
                    + blockName);
        }
        Block block = BuiltInRegistries.BLOCK.get(id);
        BlockState state = Objects.requireNonNull(block, "catalog block").defaultBlockState();
        if (bracket < 0) return state;
        String properties = canonical.substring(bracket + 1, canonical.length() - 1);
        if (properties.isEmpty()) return state;
        for (String assignment : properties.split(",")) {
            int equals = assignment.indexOf('=');
            if (equals <= 0 || equals == assignment.length() - 1) {
                throw new IllegalArgumentException("malformed canonical block property");
            }
            Property<?> property = state.getBlock().getStateDefinition()
                    .getProperty(assignment.substring(0, equals));
            if (property == null) throw new IllegalArgumentException(
                    "server catalog names an unavailable property: " + assignment);
            state = setProperty(state, property, assignment.substring(equals + 1));
        }
        return state;
    }

    private static <T extends Comparable<T>> BlockState setProperty(
            BlockState state, Property<T> property, String value) {
        return property.getValue(value).map(parsed -> state.setValue(property, parsed))
                .orElseThrow(() -> new IllegalArgumentException(
                        "server catalog names an unavailable property value: " + value));
    }

    private static String requireCanonicalBiome(String name) {
        ResourceLocation id = ResourceLocation.tryParse(name);
        ClientLevel level = Minecraft.getInstance().level;
        if (id == null || level == null
                || !level.registryAccess().registryOrThrow(Registries.BIOME).containsKey(id)) {
            throw new IllegalArgumentException("server catalog names an unavailable biome: " + name);
        }
        return id.toString();
    }
}
