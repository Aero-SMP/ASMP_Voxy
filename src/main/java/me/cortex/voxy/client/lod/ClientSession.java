package me.cortex.voxy.client.lod;

import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.model.CatalogMapper;
import me.cortex.voxy.client.core.rendering.SectionKey;
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
    private static final int REQUEST_BATCH = 256;
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

    static void refinementRequested(long parent) {
        Session current = active;
        if (current != null) current.refinementEvents.add(parent);
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
        NEW, CACHE, NETWORK, REQUESTED, RECEIVED, DECODING, DECODED, MESHING,
        PUBLISHING, ACTIVE
    }

    private static final class Demand {
        final long key;
        final boolean coverage;
        int token;
        Phase phase = Phase.NEW;
        RegionalProtocol.RegionIndex index;
        RegionalProtocol.SectionMeta meta;
        RegionalSectionCodec.SectionData decoded;
        VoxyRenderSystem.SectionPublication publication;
        RegionalProtocol.RegionIndex pendingIndex;
        RegionalProtocol.SectionMeta pendingMeta;
        byte[] received;
        boolean receivedFromCache;

        Demand(long key) {
            this.key = key;
            this.coverage = SectionKey.level(key) == SectionKey.MAX_LOD_LAYER;
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
        final ConcurrentLinkedQueue<Long> refinementEvents = new ConcurrentLinkedQueue<>();
        final ArrayBlockingQueue<Event> events = new ArrayBlockingQueue<>(MAX_EVENTS);
        final Semaphore wakeup = new Semaphore(0);
        final Semaphore sectionTaskSlots = new Semaphore(MAX_SECTION_TASKS);
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
        final Set<Long> deferredRefinements = new LinkedHashSet<>();
        final ArrayDeque<Long> regionQueue = new ArrayDeque<>();
        final Set<Long> queuedRegions = new HashSet<>();
        final ArrayDeque<StageRef> cacheQueue = new ArrayDeque<>();
        final ArrayDeque<StageRef> coverageNetworkQueue = new ArrayDeque<>();
        final ArrayDeque<StageRef> refinementNetworkQueue = new ArrayDeque<>();
        final ArrayDeque<StageRef> decodeQueue = new ArrayDeque<>();
        final ArrayDeque<StageRef> meshQueue = new ArrayDeque<>();
        final ArrayDeque<StageRef> publicationQueue = new ArrayDeque<>();

        RegionalQuicClient quic;
        RegionalCache cache;
        RegionalProtocol.Hash32 worldIdentity;
        RegionalProtocol.Hash32 catalogFingerprint = RegionalProtocol.Hash32.ZERO;
        RegionalProtocol.Hash32 requiredCatalogFingerprint = RegionalProtocol.Hash32.ZERO;
        RegionalSectionCodec.Mappings mappings;
        boolean catalogRequested;
        int inFlightBatches;
        long inFlightBytes;
        long requestEpoch = 1;
        long receivedBytes;
        long activated;
        int activeCount;
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
            for (long key : topSnapshot()) this.demandEvents.add(new DemandEvent(true, key));
        }

        void start() { this.thread.start(); }

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
                    case CacheResult result -> this.acceptCache(result);
                    case CacheCorrupt corrupt -> this.acceptCacheCorrupt(corrupt);
                    case SectionResult result -> this.acceptSection(result.reply);
                    case DecodedResult result -> this.acceptDecoded(result);
                    case MeshedResult result -> this.acceptMeshed(result);
                    case PublicationQueued result -> this.acceptPublication(result);
                    case BatchComplete complete -> {
                        this.inFlightBatches = Math.max(0, this.inFlightBatches - 1);
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
                this.deferredRefinements.clear();
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
                    this.deferredRefinements.removeIf(parent -> topAncestor(parent) == top);
                }
            }
            Long parent;
            while ((parent = this.refinementEvents.poll()) != null) {
                if (!hasTop(topAncestor(parent))) continue;
                if (!this.addChildren(parent)) this.deferredRefinements.add(parent);
            }
        }

        void addDemand(long key) {
            if (this.demands.containsKey(key)) return;
            Demand demand = new Demand(key);
            this.demands.put(key, demand);
            this.demandsByRegion.computeIfAbsent(regionFor(key), ignored -> new LinkedHashSet<>())
                    .add(key);
            this.demandsByTop.computeIfAbsent(topAncestor(key), ignored -> new LinkedHashSet<>())
                    .add(key);
            RegionalProtocol.RegionIndex index = this.indexFor(key);
            if (index == null) this.ensureRegion(key);
            else {
                RegionalProtocol.SectionMeta meta = index.section(key);
                if (meta == null) this.retireDemand(key); else this.bind(demand, index, meta);
            }
        }

        boolean addChildren(long parent) {
            RegionalProtocol.RegionIndex index = this.indexFor(parent);
            if (index == null) return false;
            RegionalProtocol.SectionMeta parentMeta = index.section(parent);
            if (parentMeta == null || SectionKey.level(parent) == 0) return true;
            int childMask = parentMeta.childMask();
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
                RegionalProtocol.SectionMeta meta = index.section(demand.key);
                if (meta == null) this.retireDemand(demand.key);
                else this.bind(demand, index, meta);
            }
            this.deferredRefinements.removeIf(parent ->
                    regionFor(parent) == region && this.addChildren(parent));
        }

        void retireRegion(long region) {
            Set<Long> regional = this.demandsByRegion.get(region);
            if (regional == null) return;
            for (long key : List.copyOf(regional)) this.retireDemand(key);
        }

        void retireDemand(long key) {
            Demand demand = this.demands.get(key);
            if (demand == null) return;
            demand.token++;
            demand.received = null;
            demand.decoded = null;
            if (demand.phase == Phase.ACTIVE) this.activeCount--;
            if (demand.publication != null) demand.publication.close();
            if (demand.coverage && hasTop(key)) {
                demand.publication = null;
                demand.pendingIndex = null;
                demand.pendingMeta = null;
                demand.index = null;
                demand.meta = null;
                demand.phase = Phase.NEW;
            } else {
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

        void bind(Demand demand, RegionalProtocol.RegionIndex index,
                  RegionalProtocol.SectionMeta meta) {
            if (meta == null) return;
            if (demand.phase == Phase.PUBLISHING) {
                demand.pendingIndex = index;
                demand.pendingMeta = meta;
                return;
            }
            if (demand.meta != null && demand.phase == Phase.ACTIVE
                    && demand.meta.fingerprint().equals(meta.fingerprint())
                    && demand.meta.childMask() == meta.childMask()) {
                demand.index = index;
                demand.meta = meta;
                return;
            }
            demand.token++;
            if (demand.phase == Phase.ACTIVE) this.activeCount--;
            demand.index = index;
            demand.meta = meta;
            demand.decoded = null;
            demand.received = null;
            this.queueBound(demand);
        }

        void queueBound(Demand demand) {
            demand.phase = Phase.NEW;
            if (demand.meta.empty()) this.publishEmpty(demand);
            else {
                demand.phase = Phase.CACHE;
                this.enqueueStage(this.cacheQueue, demand);
            }
        }

        void processStages() throws IOException {
            this.scheduleMeshing();
            this.scheduleDecoding();
            this.scheduleCache();
            while (this.inFlightBatches < MAX_IN_FLIGHT_BATCHES) {
                List<Demand> selected = this.selectNetworkBatch();
                if (selected.isEmpty()) break;
                if (!this.sendBatch(selected)) break;
            }
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
            RegionalProtocol.SectionMeta meta = demand.meta;
            this.sectionWorkers.execute(() -> {
                try {
                    putEvent(new CacheResult(key, token, this.cache.get(index, meta)));
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
            while (this.sectionTaskSlots.tryAcquire()) {
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
                this.lookupCache(demand);
            }
        }

        List<Demand> selectNetworkBatch() {
            List<Demand> result = new ArrayList<>(REQUEST_BATCH);
            long available = MAX_IN_FLIGHT_BYTES - this.inFlightBytes;
            if (available <= 0) return result;
            ArrayDeque<StageRef> queue = this.coverageNetworkQueue.isEmpty()
                    ? this.refinementNetworkQueue : this.coverageNetworkQueue;
            long bytes = 0;
            while (result.size() < REQUEST_BATCH) {
                StageRef ref = queue.peekFirst();
                if (ref == null) break;
                Demand demand = this.demands.get(ref.key);
                if (!current(demand, ref.token, Phase.NETWORK)) {
                    queue.removeFirst();
                    continue;
                }
                long next = demand.meta.compressedLength();
                if (next > MAX_IN_FLIGHT_BYTES) {
                    throw new IllegalStateException(
                            "one indexed section exceeds the network byte ceiling");
                }
                if (bytes + next > available) break;
                queue.removeFirst();
                result.add(demand);
                bytes += next;
            }
            return result;
        }

        boolean sendBatch(List<Demand> selected) throws IOException {
            long epoch = ++this.requestEpoch;
            if (epoch == 0) epoch = ++this.requestEpoch;
            long reservedBytes = selected.stream()
                    .mapToLong(demand -> demand.meta.compressedLength()).sum();
            List<RegionalProtocol.SectionRequest> requests = new ArrayList<>(selected.size());
            for (Demand demand : selected) {
                requests.add(new RegionalProtocol.SectionRequest(
                        demand.index.generation(), demand.key));
            }
            RegionalProtocol.Lane lane = selected.getFirst().coverage
                    ? RegionalProtocol.Lane.COVERAGE : RegionalProtocol.Lane.REFINEMENT;
            boolean accepted = this.quic.requestSections(lane, epoch, requests,
                    new RegionalQuicClient.BatchReceiver() {
                        @Override public void batch(List<RegionalProtocol.SectionReply> replies) {
                            for (RegionalProtocol.SectionReply reply : replies) {
                                putEvent(new SectionResult(reply));
                            }
                        }
                        @Override public void complete() {
                            putEvent(new BatchComplete(reservedBytes));
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
            this.inFlightBytes += reservedBytes;
            return true;
        }

        void acceptSection(RegionalProtocol.SectionReply reply) throws IOException {
            Demand demand = this.demands.get(reply.key());
            if (demand == null || demand.phase != Phase.REQUESTED
                    || demand.index.generation() != reply.generation()) return;
            RegionalProtocol.SectionMeta meta = demand.meta;
            switch (reply.status()) {
                case DATA -> {
                    if (!reply.fingerprint().equals(meta.fingerprint())
                            || reply.canonicalLength() != meta.canonicalLength()
                            || reply.compressed().length != meta.compressedLength()) {
                        throw new IOException("regional section reply disagrees with its index");
                    }
                    this.receivedBytes += reply.compressed().length;
                    demand.received = reply.compressed();
                    demand.receivedFromCache = false;
                    demand.phase = Phase.RECEIVED;
                    this.enqueueStage(this.decodeQueue, demand);
                }
                case EMPTY -> {
                    if (!meta.empty()) throw new IOException("unexpected empty regional section");
                    this.publishEmpty(demand);
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
            while (this.sectionTaskSlots.tryAcquire()) {
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
                this.decode(demand);
            }
        }

        void decode(Demand demand) {
            demand.phase = Phase.DECODING;
            long key = demand.key;
            int token = demand.token;
            RegionalProtocol.SectionMeta meta = demand.meta;
            RegionalProtocol.RegionIndex index = demand.index;
            RegionalSectionCodec.Mappings mappings = this.mappings;
            byte[] compressed = demand.received;
            boolean cached = demand.receivedFromCache;
            demand.received = null;
            this.sectionWorkers.execute(() -> {
                try {
                    byte[] canonical = this.codec.decompress(compressed, meta.canonicalLength());
                    RegionalSectionCodec.SectionData section = this.codec.decode(
                            key, meta.childMask(), canonical, meta.fingerprint(), mappings);
                    putEvent(new DecodedResult(key, token, section));
                    if (!cached) {
                        try { this.cache.put(index, meta, compressed); }
                        catch (IOException ignored) {}
                    }
                } catch (Throwable failure) {
                    if (cached) this.cache.quarantine(index, meta);
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
            this.enqueueStage(this.meshQueue, demand);
        }

        void scheduleMeshing() {
            int remaining = this.meshQueue.size();
            while (remaining-- > 0 && this.sectionTaskSlots.tryAcquire()) {
                StageRef ref = this.meshQueue.pollFirst();
                if (ref == null) {
                    this.sectionTaskSlots.release();
                    return;
                }
                Demand demand = this.demands.get(ref.key);
                if (!current(demand, ref.token, Phase.DECODED)) {
                    this.sectionTaskSlots.release();
                    continue;
                }
                if (!this.mesher.modelsReady(demand.decoded)) {
                    this.sectionTaskSlots.release();
                    this.meshQueue.addLast(ref);
                    continue;
                }
                demand.phase = Phase.MESHING;
                long key = demand.key; int token = demand.token;
                RegionalSectionCodec.SectionData decoded = demand.decoded;
                this.sectionWorkers.execute(() -> {
                    try {
                        putEvent(new MeshedResult(key, token,
                                this.mesher.mesh(decoded, token + 1L)));
                    } catch (Throwable failure) {
                        putEvent(new WorkerFailed(failure));
                    } finally {
                        this.sectionTaskSlots.release();
                        signal();
                    }
                });
            }
        }

        void acceptMeshed(MeshedResult result) throws InterruptedException {
            Demand demand = this.demands.get(result.key);
            if (!current(demand, result.token, Phase.MESHING)) {
                result.geometry.free();
                return;
            }
            demand.phase = Phase.PUBLISHING;
            enqueueMain(new PublishTask(this, demand.key, result.token, result.geometry,
                    demand.publication));
        }

        void publishEmpty(Demand demand) {
            demand.phase = Phase.PUBLISHING;
            BuiltSection empty = BuiltSection.emptyWithChildren(demand.key, demand.token + 1L,
                    (byte) demand.meta.childMask());
            try {
                enqueueMain(new PublishTask(this, demand.key, demand.token, empty,
                        demand.publication));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                empty.free();
                this.fail(interrupted);
            }
        }

        void acceptPublication(PublicationQueued result) throws IOException {
            Demand demand = this.demands.get(result.key);
            if (!current(demand, result.token, Phase.PUBLISHING)) {
                result.publication.close();
                return;
            }
            demand.publication = result.publication;
            this.enqueueStage(this.publicationQueue, demand);
        }

        void pollPublications() throws IOException {
            int remaining = this.publicationQueue.size();
            while (remaining-- > 0) {
                StageRef ref = this.publicationQueue.pollFirst();
                if (ref == null) return;
                Demand demand = this.demands.get(ref.key);
                if (!current(demand, ref.token, Phase.PUBLISHING)
                        || demand.publication == null) continue;
                Optional<Throwable> failure = demand.publication.activationFailure();
                if (failure.isPresent()) throw new IOException(
                        "regional renderer publication failed", failure.orElseThrow());
                if (!demand.publication.activationFencePassed()) {
                    this.publicationQueue.addLast(ref);
                    continue;
                }
                demand.phase = Phase.ACTIVE;
                this.activeCount++;
                demand.decoded = null;
                this.activated++;
                if (demand.pendingIndex != null) {
                    RegionalProtocol.RegionIndex index = demand.pendingIndex;
                    RegionalProtocol.SectionMeta meta = demand.pendingMeta;
                    demand.pendingIndex = null; demand.pendingMeta = null;
                    this.bind(demand, index, meta);
                }
            }
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

        void clearStageQueues() {
            this.regionQueue.clear();
            this.queuedRegions.clear();
            this.cacheQueue.clear();
            this.coverageNetworkQueue.clear();
            this.refinementNetworkQueue.clear();
            this.decodeQueue.clear();
            this.meshQueue.clear();
            this.publicationQueue.clear();
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
                    return;
                }
            }
            if (event instanceof MeshedResult meshed) meshed.geometry.free();
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
                    + " batches=" + this.inFlightBatches + " inFlightBytes=" + this.inFlightBytes
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
                if (demand.publication != null) demand.publication.close();
            }
            this.demands.clear();
            this.demandsByRegion.clear();
            this.demandsByTop.clear();
            this.subscribedRegions.clear();
            this.clearStageQueues();
        }
    }

    private record DemandEvent(boolean add, long key) {}
    private record StageRef(long key, int token) {}

    private sealed interface Event permits CatalogReady, IndexReady, CacheResult, CacheCorrupt,
            SectionResult, DecodedResult, MeshedResult, PublicationQueued, BatchComplete,
            WorkerFailed {}
    private record CatalogReady(RegionalProtocol.Hash32 fingerprint,
                                RegionalSectionCodec.Mappings mappings) implements Event {}
    private record IndexReady(RegionalProtocol.RegionMessage message,
                              RegionalProtocol.RegionIndex index) implements Event {}
    private record CacheResult(long key, int token, byte[] compressed) implements Event {}
    private record CacheCorrupt(long key, int token) implements Event {}
    private record SectionResult(RegionalProtocol.SectionReply reply) implements Event {}
    private record DecodedResult(long key, int token,
                                 RegionalSectionCodec.SectionData section) implements Event {}
    private record MeshedResult(long key, int token, BuiltSection geometry) implements Event {}
    private record PublicationQueued(long key, int token,
                                     VoxyRenderSystem.SectionPublication publication)
            implements Event {}
    private record BatchComplete(long reservedBytes) implements Event {}
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

    private record PublishTask(Session owner, long key, int token, BuiltSection geometry,
                               VoxyRenderSystem.SectionPublication previous) implements MainTask {
        @Override public void run(VoxyRenderSystem renderer) {
            VoxyRenderSystem.SectionPublication publication = this.owner.publisher.publish(
                    this.key, this.geometry, Optional.ofNullable(this.previous));
            this.owner.putEvent(new PublicationQueued(this.key, this.token, publication));
        }
        @Override public void cancel() { this.geometry.free(); }
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
