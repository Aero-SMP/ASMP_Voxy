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
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Current regional client. Every entry is one spatial section and moves monotonically through
 * cache/network, decode, mesh, upload, and active states. Renderer refinement adds exact child
 * keys; nothing scans or retains historical object identities.
 */
final class ClientSession {
    private static final long RETRY_DELAY_NANOS = TimeUnit.SECONDS.toNanos(1);

    private static final Object LIFECYCLE = new Object();
    private static final LinkedHashSet<Long> TOP_LEVEL = new LinkedHashSet<>();
    private static final java.util.concurrent.atomic.AtomicReference<CatalogTask> CATALOG_TASK =
            new java.util.concurrent.atomic.AtomicReference<>();
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
        if (current != null) {
            current.demands.offerTop(key, true);
            current.signal();
        }
        return true;
    }

    static void sectionLeft(long key) {
        requireTop(key);
        synchronized (TOP_LEVEL) { TOP_LEVEL.remove(key); }
        Session current = active;
        if (current != null) {
            current.demands.offerTop(key, false);
            current.signal();
        }
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

    /** Debug harness handoff. The real owner thread creates the observation without blocking. */
    static boolean requestDebugSnapshot(Consumer<PipelineSnapshot> receiver) {
        Objects.requireNonNull(receiver, "snapshot receiver");
        Session current = active;
        if (current == null || !current.open.get()) return false;
        boolean accepted = current.events.offer(new SnapshotRequest(receiver));
        if (accepted) current.signal();
        return accepted;
    }

    record PipelineSnapshot(
            long sessionGeneration, long connectionEpoch, long rootGeneration,
            boolean failed, long retryNanos, long coverageMissing, long requested,
            long downloading, long cacheReading, long decoding, long meshing,
            long ready, long publishing, long active, long networkBytes,
            long completedBatches, long cacheHits, long cacheMisses, long cacheReads,
            long cacheBytes, long decodedTotal, long meshedTotal, long uploadedTotal,
            long activatedTotal, long retiredTotal, long selectedBytes,
            long warmBytes, long coldBytes, long pendingRetirementBytes,
            long physicalGeometryBytes, long rendererTargetBytes,
            long rendererAllocatedBytes, long waitRegion, long sourceReady,
            long networkOwned, long workerOwned, long waitModels, long rendererOwned,
            int idleWorkers, int runningWorkers, int completedWorkers,
            long completedWorkerBytes, int idleLanes, int activeLanes,
            int activeLaneSections, long laneBodyBytes, long reconnects,
            long largestFreeGeometryUnits, int usedGeometrySections) {}

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
        CatalogTask task = CATALOG_TASK.getAndSet(null);
        if (task != null && task.owner() == current && current != null
                && current.open.get()) {
            try {
                task.run(renderer);
            } catch (Throwable failure) {
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
        CatalogTask task = CATALOG_TASK.get();
        if (task != null && task.owner() == session) CATALOG_TASK.compareAndSet(task, null);
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

    private static final class Demand extends SectionDemandTable.Demand {
        RegionalProtocol.RegionIndex index;
        int ordinal = -1;
        VoxyRenderSystem.SectionPublication publication;
        VoxyRenderSystem.SectionPublication previousPublication;
        RegionalProtocol.RegionIndex pendingIndex;
        int pendingOrdinal = -1;
        BuiltSection completedGeometry;
        long meshCompletedNanos;
        long geometryBytes, activeGeometryBytes;
        boolean completedGeometryOwned;
        boolean publishingGeometryOwned;
        boolean installed;
        int latestRefinementEpoch = -1;
        int latestDormancyEpoch = -1;
        boolean dormant;
        int dormantBucket;
        long lastSelectedSequence;
        int[] waitingModels;
        Session.WorkerSlot completedSlot;
        int activeWorkerSlot = -1;
        long blockedRequiredBytes;

        Demand(long key) {
            super(key, regionFor(key), topAncestor(key),
                    SectionKey.level(key) == SectionKey.MAX_LOD_LAYER, 0);
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

    private static final class Session implements AutoCloseable {
        final long id;
        final String dimension;
        final VoxyRenderSystem renderer;
        final VoxyRenderSystem.SectionPublisher publisher;
        final SectionMesher mesher;
        final Thread thread;
        final AtomicBoolean open = new AtomicBoolean(true);
        final AtomicBoolean resetRequested = new AtomicBoolean();
        final SectionDemandTable<Demand> demands;
        final ConcurrentLinkedQueue<Event> events = new ConcurrentLinkedQueue<>();
        final Object wakeupLock = new Object();
        boolean wakePending;
        final WorkerSlot[] sectionWorkers;
        final int sectionWorkerCount;
        final Set<Long> waitingModels = new LinkedHashSet<>();
        final ConcurrentLinkedQueue<NetworkReply> networkReplies =
                new ConcurrentLinkedQueue<>();

        final Map<Long, LinkedHashSet<Long>> demandsByTop = new HashMap<>();
        final Set<Long> missingCoverage = new HashSet<>();
        final Set<Long> coarseningRoots = new HashSet<>();
        final Set<Long> rendererBlocked = new LinkedHashSet<>();
        final Long2ObjectOpenHashMap<DormantRoot> dormantRoots =
                new Long2ObjectOpenHashMap<>();
        final Long2LongOpenHashMap pendingDormantEvictions = new Long2LongOpenHashMap();
        final Object publicationLock = new Object();
        final ArrayDeque<PublicationRef> publicationQueue = new ArrayDeque<>();

        RegionalQuicClient quic;
        RegionalProtocol.Control pendingControl;
        RegionalCache cache;
        RegionalProtocol.Hash32 worldIdentity;
        RegionalProtocol.Hash32 catalogFingerprint = RegionalProtocol.Hash32.ZERO;
        RegionalProtocol.Hash32 requiredCatalogFingerprint = RegionalProtocol.Hash32.ZERO;
        RegionalSectionCodec.Mappings mappings;
        boolean catalogRequested;
        long catalogRequirementRevision;
        long catalogRequestedRevision;
        int inFlightBatches;
        int inFlightSections;
        long inFlightBytes;
        long connectionEpoch;
        long requestEpoch = 1;
        long receivedBytes;
        long activated;
        long completedBatches;
        long cacheHits, cacheMisses, cacheReads, cacheBytes;
        long decodedSections, meshedSections, uploadedSections, retiredSections;
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
        long lastEvictionDistanceSquared;
        long lastEvictionAge;
        int lastEvictionBucket = -1;
        int activeCount;
        volatile int cameraSectionX;
        volatile int cameraSectionZ;
        long completedGeometryBytes;
        long publishingGeometryBytes;
        volatile Throwable failure;
        volatile Throwable lastConnectionFailure;
        long reconnects;

        private static final class NetworkReply {
            final long connectionEpoch;
            final RegionalProtocol.SectionReply reply;
            final Semaphore transferred = new Semaphore(0);

            NetworkReply(long connectionEpoch, RegionalProtocol.SectionReply reply) {
                this.connectionEpoch = connectionEpoch;
                this.reply = reply;
            }

            void awaitTransfer() throws InterruptedException { this.transferred.acquire(); }
            void transferred() { this.transferred.release(); }
        }

        private enum WorkerSource { CACHE, NETWORK }
        private sealed interface WorkerTask permits SectionWorkerTask, IndexWorkerTask {}
        private record SectionWorkerTask(SectionDemandTable.Ticket ticket,
                                         RegionalProtocol.RegionIndex index, int ordinal,
                                         WorkerSource source, byte[] compressed,
                                         RegionalSectionCodec.Mappings mappings)
                implements WorkerTask {}
        private record IndexWorkerTask(long session, long connection, long region, long generation,
                                       int slot, RegionalProtocol.RegionMessage message)
                implements WorkerTask {}
        private sealed interface WorkerResult permits WorkerMiss, WorkerModels,
                WorkerGeometry, WorkerIndex, WorkerFailure {}
        private record WorkerMiss(SectionDemandTable.Ticket ticket, boolean corrupt)
                implements WorkerResult {}
        private record WorkerModels(SectionDemandTable.Ticket ticket, int[] blocks,
                                    boolean cacheHit, int compressedBytes)
                implements WorkerResult {}
        private record WorkerGeometry(SectionDemandTable.Ticket ticket, BuiltSection geometry,
                                      long completedNanos, boolean cacheHit,
                                      int compressedBytes) implements WorkerResult {}
        private record WorkerIndex(long session, long connection, long region, long generation,
                                   int slot, RegionalProtocol.RegionIndex index)
                implements WorkerResult {}
        private record WorkerFailure(WorkerTask task, int slot, Throwable failure)
                implements WorkerResult {}

        /** A persistent resource slot owns exactly one task or completion and has no backlog. */
        private final class WorkerSlot {
            private enum State { IDLE, RUNNING, COMPLETED, CLOSED }
            final int index;
            final Thread workerThread;
            final RegionalSectionCodec codec = new RegionalSectionCodec();
            private State state = State.IDLE;
            private WorkerTask task;
            private WorkerResult result;

            WorkerSlot(int index) {
                this.index = index;
                this.workerThread = new Thread(this::run,
                        "Voxy regional section worker-" + index);
                this.workerThread.setDaemon(true);
            }

            void start() { this.workerThread.start(); }

            synchronized boolean assign(WorkerTask task) {
                if (this.state != State.IDLE) return false;
                this.task = Objects.requireNonNull(task, "task");
                this.state = State.RUNNING;
                this.notifyAll();
                return true;
            }

            synchronized boolean idle() { return this.state == State.IDLE; }
            synchronized WorkerResult completion() {
                return this.state == State.COMPLETED ? this.result : null;
            }

            synchronized void releaseCompletion() {
                if (this.state != State.COMPLETED) {
                    throw new IllegalStateException("worker has no completed resource");
                }
                this.result = null;
                this.state = State.IDLE;
                this.notifyAll();
            }

            private void run() {
                try {
                    while (true) {
                    WorkerTask claimed;
                    synchronized (this) {
                        while (this.state != State.RUNNING && this.state != State.CLOSED) {
                            try { this.wait(); }
                            catch (InterruptedException interrupted) {
                                if (this.state == State.CLOSED) return;
                            }
                        }
                        if (this.state == State.CLOSED) return;
                        claimed = this.task;
                        this.task = null;
                    }
                    WorkerResult completion;
                    try {
                        completion = switch (claimed) {
                            case SectionWorkerTask section -> this.section(section);
                            case IndexWorkerTask index -> this.index(index);
                        };
                    } catch (Throwable failure) {
                        completion = new WorkerFailure(claimed, this.index, failure);
                    }
                    synchronized (this) {
                        if (this.state == State.CLOSED) {
                            freeWorkerResult(completion);
                            return;
                        }
                        this.result = completion;
                        this.state = State.COMPLETED;
                    }
                    signal();
                    }
                } finally {
                    this.codec.close();
                }
            }

            private WorkerResult section(SectionWorkerTask task) throws Exception {
                byte[] compressed = task.compressed();
                boolean cacheHit = task.source() == WorkerSource.CACHE;
                if (cacheHit) {
                    try { compressed = cache.get(task.index(), task.ordinal()); }
                    catch (IOException ignored) { compressed = null; }
                    if (compressed == null) return new WorkerMiss(task.ticket(), false);
                }
                try {
                    byte[] canonical = this.codec.decompress(compressed,
                            task.index().canonicalLength(task.ordinal()));
                    RegionalSectionCodec.SectionData section = this.codec.decode(
                            task.ticket().key(), task.index().childMask(task.ordinal()), canonical,
                            task.index().sectionFingerprint(task.ordinal()), task.mappings());
                    mesher.requestModels(section);
                    if (!cacheHit) {
                        try { cache.put(task.index(), task.ordinal(), compressed); }
                        catch (IOException ignored) { }
                    }
                    if (!mesher.modelsReady(section)) {
                        return new WorkerModels(task.ticket(), section.usedBlocks().clone(),
                                cacheHit, compressed.length);
                    }
                    BuiltSection geometry = mesher.mesh(section, task.ticket().demandRevision());
                    return new WorkerGeometry(task.ticket(), geometry, System.nanoTime(), cacheHit,
                            compressed.length);
                } catch (Throwable failure) {
                    if (!cacheHit) throw failure;
                    cache.quarantine(task.index(), task.ordinal());
                    return new WorkerMiss(task.ticket(), true);
                }
            }

            private WorkerResult index(IndexWorkerTask task) throws Exception {
                RegionalProtocol.RegionMessage message = task.message();
                byte[] canonical = this.codec.decompressFramed(message.compressed(),
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
                return new WorkerIndex(task.session(), task.connection(), task.region(),
                        task.generation(), task.slot(), index);
            }

            synchronized void close() {
                if (this.state == State.CLOSED) return;
                if (this.result != null) freeWorkerResult(this.result);
                this.result = null;
                this.task = null;
                this.state = State.CLOSED;
                this.notifyAll();
                this.workerThread.interrupt();
            }
        }

        Session(long id, String dimension, VoxyRenderSystem renderer) {
            this.id = id;
            this.demands = new SectionDemandTable<>(
                    HierarchicalOcclusionTraverser.DETAIL_BUCKET_COUNT, id);
            this.dimension = dimension;
            this.renderer = renderer;
            this.publisher = renderer.regionalSectionPublisher();
            this.mesher = renderer.regionalSectionMesher();
            int workers = Math.max(2, Math.min(16,
                    Runtime.getRuntime().availableProcessors() - 2));
            this.sectionWorkerCount = workers;
            this.sectionWorkers = new WorkerSlot[workers];
            for (int index = 0; index < workers; index++) {
                this.sectionWorkers[index] = new WorkerSlot(index);
            }
            this.thread = new Thread(this::run, "Voxy regional owner");
            this.thread.setDaemon(true);
            for (long key : topSnapshot()) this.demands.offerTop(key, true);
        }

        void start() {
            for (WorkerSlot worker : this.sectionWorkers) worker.start();
            this.thread.start();
        }

        void updateCamera(int sectionX, int sectionZ) {
            this.cameraSectionX = sectionX;
            this.cameraSectionZ = sectionZ;
        }

        void acceptDetailAction(long key, int action, int bucket, int epoch) {
            if (action != HierarchicalOcclusionTraverser.ACTION_REFINE
                    && action != HierarchicalOcclusionTraverser.ACTION_DORMANT
                    && action != HierarchicalOcclusionTraverser.ACTION_WAKE) return;
            this.demands.offerDetail(key, action, bucket, epoch);
            this.signal();
        }

        void run() {
            try {
                while (this.open.get()) {
                    if (this.quic == null) {
                        try {
                            this.connect();
                        } catch (IOException failure) {
                            this.lastConnectionFailure = failure;
                            Logger.warn("Regional Voxy reconnect is waiting for its endpoint",
                                    failure);
                            this.awaitWake(1_000);
                            continue;
                        }
                    }
                    try {
                        this.drainWorkers();
                        this.drainNetworkReplies();
                        this.drainControls();
                        this.drainEvents();
                        this.drainDemand();
                        this.processRegions();
                        this.pollPublications();
                        this.processStages();
                        if (!this.quic.isOpen()) {
                            throw new IOException("regional QUIC connection ended",
                                    this.quic.failure());
                        }
                    } catch (IOException failure) {
                        if (this.open.get()) this.resetConnection(failure);
                    }
                    this.awaitWake(10);
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

        void connect() throws IOException {
            RegionalQuicClient connected = QuicEndpointDiscovery.connect();
            try {
                connected.setActivityListener(this::signal);
                connected.hello(this.dimension);
            } catch (Throwable failure) {
                connected.close();
                if (failure instanceof IOException io) throw io;
                throw new IOException("could not initialize regional QUIC", failure);
            }
            this.quic = connected;
            if (++this.connectionEpoch == 0) ++this.connectionEpoch;
            this.lastConnectionFailure = null;
            Logger.info("Using regional Voxy over QUIC " + connected.description()
                    + " connectionEpoch=" + this.connectionEpoch);
        }

        void resetConnection(Throwable cause) {
            this.lastConnectionFailure = cause;
            this.reconnects++;
            Logger.warn("Regional Voxy connection failed; preserving installed geometry and "
                    + "reconnecting", cause);
            RegionalQuicClient previous = this.quic;
            this.quic = null;
            if (previous != null) previous.close();
            this.pendingControl = null;
            this.catalogRequested = false;
            this.inFlightBatches = 0;
            this.inFlightSections = 0;
            this.inFlightBytes = 0;
            NetworkReply reply;
            while ((reply = this.networkReplies.poll()) != null) reply.transferred();
            for (Demand demand : this.demands.values()) {
                if (demand.candidate
                        != SectionDemandTable.CandidateState.NETWORK_OWNED) continue;
                this.demands.revise(demand);
                demand.candidate = SectionDemandTable.CandidateState.READY_SOURCE;
                this.demands.ready(demand, SectionDemandTable.ReadyKind.NETWORK);
            }
            for (SectionDemandTable.RegionDemand region : this.demands.regions()) {
                region.requested = false;
                region.subscribed = false;
                this.queueRegion(region.key);
            }
        }

        void drainControls() throws Exception {
            while (true) {
                RegionalProtocol.Control control = this.pendingControl;
                if (control == null) control = this.quic.pollControl();
                if (control == null) return;
                this.pendingControl = null;
                switch (control) {
                    case RegionalProtocol.ServerHello hello -> this.acceptHello(hello);
                    case RegionalProtocol.RegionMessage region -> {
                        if (!this.acceptRegion(region)) {
                            this.pendingControl = region;
                            return;
                        }
                    }
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
            if (this.worldIdentity == null) {
                this.worldIdentity = hello.worldIdentity();
                Path root = Minecraft.getInstance().gameDirectory.toPath()
                        .resolve(".voxy").resolve("regional");
                this.cache = new RegionalCache(root, this.worldIdentity);
            } else if (!this.worldIdentity.equals(hello.worldIdentity())) {
                throw new IllegalStateException(
                        "regional endpoint changed world identity during one dimension session");
            }
            this.ensureCatalog(hello.catalogFingerprint());
            for (SectionDemandTable.RegionDemand region : this.demands.regions()) {
                this.queueRegion(region.key);
            }
        }

        void ensureCatalog(RegionalProtocol.Hash32 fingerprint) throws IOException {
            if (!fingerprint.equals(this.requiredCatalogFingerprint)) {
                this.requiredCatalogFingerprint = fingerprint;
                this.catalogRequirementRevision++;
            }
            if (fingerprint.equals(this.catalogFingerprint) && this.mappings != null) return;
            if (!this.catalogRequested) {
                this.catalogRequested = true;
                this.catalogRequestedRevision = this.catalogRequirementRevision;
                this.quic.requestCatalog();
            }
        }

        void acceptCatalog(RegionalProtocol.CatalogMessage message) throws Exception {
            this.catalogRequested = false;
            if (!hash32(message.canonical()).equals(message.fingerprint())) {
                throw new IOException("regional catalog fingerprint mismatch");
            }
            CatalogCodec.Catalog catalog = CatalogCodec.decode(message.canonical());
            enqueueCatalog(new CatalogTask(this, catalog, message.fingerprint(),
                    this.catalogRequestedRevision, this.connectionEpoch));
        }

        boolean acceptRegion(RegionalProtocol.RegionMessage message) throws IOException {
            long region = regionKey(message.regionX(), message.regionZ());
            SectionDemandTable.RegionDemand state = this.demands.region(region);
            if (state == null || !state.subscribed) return true;
            state.requested = false;
            long expected = state.announcedGeneration;
            if (expected != 0
                    && Long.compareUnsigned(message.generation(), expected) < 0) {
                state.index = null;
                state.installedGeneration = 0;
                this.queueRegion(region);
                return true;
            }
            state.announcedGeneration = message.generation();
            state.absent = false;
            this.ensureCatalog(message.catalogFingerprint());
            WorkerSlot worker = this.idleWorker(state.coverageUsers > 0);
            if (worker == null) return false;
            if (!worker.assign(new IndexWorkerTask(this.id, this.connectionEpoch, region,
                    message.generation(), worker.index, message))) return false;
            state.resourceSlot = worker.index;
            return true;
        }

        void acceptRegionAbsent(RegionalProtocol.RegionAbsent message) {
            long region = regionKey(message.regionX(), message.regionZ());
            SectionDemandTable.RegionDemand state = this.demands.region(region);
            if (state == null || !state.subscribed) return;
            state.requested = false;
            state.announcedGeneration = 0;
            state.installedGeneration = 0;
            state.absent = true;
            state.index = null;
            this.retireRegion(region);
        }

        void regionChanged(RegionalProtocol.RegionChanged message) {
            long region = regionKey(message.regionX(), message.regionZ());
            if (this.demands.region(region) == null) return;
            this.demands.offerRegion(region, message.generation());
        }

        void applyRegionChanged(long region, long generation) {
            SectionDemandTable.RegionDemand state = this.demands.region(region);
            if (state == null) return;
            state.announcedGeneration = generation;
            state.requested = false;
            state.absent = false;
            state.index = null;
            state.installedGeneration = 0;
            for (SectionDemandTable.Demand base : state.members.values()) {
                Demand demand = (Demand) base;
                if (demand.installed
                        && demand.candidate == SectionDemandTable.CandidateState.NONE) continue;
                this.demands.unlinkReady(demand);
                this.discardCompletedGeometry(demand);
                this.demands.revise(demand);
                demand.pendingIndex = null;
                demand.pendingOrdinal = -1;
                demand.candidate = SectionDemandTable.CandidateState.WAIT_REGION;
            }
            if (generation == 0) {
                state.absent = true;
                this.retireRegion(region);
            } else {
                this.queueRegion(region);
            }
        }

        void drainEvents() throws Exception {
            Event event;
            while ((event = this.events.poll()) != null) {
                switch (event) {
                    case CatalogReady ready -> {
                        if (ready.connectionEpoch != this.connectionEpoch) {
                            this.ensureCatalog(this.requiredCatalogFingerprint);
                            continue;
                        }
                        this.catalogFingerprint = ready.fingerprint;
                        this.mappings = ready.mappings;
                        this.ensureCatalog(this.requiredCatalogFingerprint);
                    }
                    case Coarsened result -> this.finishCoarsening(result.parent, true);
                    case CoarsenFailed failed -> {
                        this.finishCoarsening(failed.parent, false);
                        Logger.warn("Regional subtree coarsening failed; retained geometry "
                                + "remains authoritative", failed.failure);
                    }
                    case BatchComplete complete -> {
                        if (complete.connectionEpoch != this.connectionEpoch) continue;
                        this.completedBatches++;
                        this.inFlightBatches = Math.max(0, this.inFlightBatches - 1);
                        this.inFlightSections = Math.max(0,
                                this.inFlightSections - complete.sectionCount);
                        this.inFlightBytes = Math.max(0,
                                this.inFlightBytes - complete.reservedBytes);
                    }
                    case BatchFailed failed -> {
                        if (failed.connectionEpoch == this.connectionEpoch) {
                            throw new IOException("regional section lane failed", failed.failure);
                        }
                    }
                    case SnapshotRequest request -> request.receiver.accept(this.pipelineSnapshot());
                }
            }
        }

        PipelineSnapshot pipelineSnapshot() {
            long downloading = 0, cacheReading = 0, decoding = 0, meshing = 0;
            long ready = 0, publishing = 0;
            long waitRegion = 0, sourceReady = 0, networkOwned = 0, workerOwned = 0;
            long waitModels = 0, rendererOwned = 0;
            for (Demand demand : this.demands.values()) {
                switch (demand.candidate) {
                    case WAIT_REGION -> waitRegion++;
                    case NETWORK_OWNED -> { downloading++; networkOwned++; }
                    case READY_SOURCE -> {
                        sourceReady++;
                        if (demand.readyKind == SectionDemandTable.ReadyKind.NETWORK) downloading++;
                        else cacheReading++;
                    }
                    case WORKER_OWNED -> {
                        workerOwned++;
                        if (demand.completedGeometry != null) ready++;
                        else meshing++;
                    }
                    case WAIT_MODELS -> { decoding++; waitModels++; }
                    case RENDERER_OWNED -> { publishing++; rendererOwned++; }
                    default -> {}
                }
            }
            int idleWorkers = 0, runningWorkers = 0, completedWorkers = 0;
            long completedWorkerBytes = 0;
            for (WorkerSlot worker : this.sectionWorkers) {
                synchronized (worker) {
                    switch (worker.state) {
                        case IDLE -> idleWorkers++;
                        case RUNNING -> runningWorkers++;
                        case COMPLETED -> {
                            completedWorkers++;
                            if (worker.result instanceof WorkerGeometry geometry
                                    && geometry.geometry().geometryBuffer != null) {
                                completedWorkerBytes += geometry.geometry().geometryBuffer.size;
                            }
                        }
                        case CLOSED -> {}
                    }
                }
            }
            RegionalQuicClient.LaneSnapshot lanes = this.quic == null
                    ? new RegionalQuicClient.LaneSnapshot(0, 0, 0, 0)
                    : this.quic.laneSnapshot();
            long newestRoot = 0;
            for (SectionDemandTable.RegionDemand region : this.demands.regions()) {
                newestRoot = Math.max(newestRoot, region.announcedGeneration);
            }
            long retry = Math.max(0, retryAfter - System.nanoTime());
            return new PipelineSnapshot(this.id, this.connectionEpoch, newestRoot,
                    this.failure != null, retry, this.missingCoverage.size(), this.demands.size(),
                    downloading, cacheReading, decoding, meshing, ready, publishing,
                    this.activeCount, this.receivedBytes, this.completedBatches,
                    this.cacheHits, this.cacheMisses, this.cacheReads, this.cacheBytes,
                    this.decodedSections, this.meshedSections, this.uploadedSections,
                    this.activated, this.retiredSections, this.selectedGeometryBytes(),
                    this.dormantGeometryBytes, 0, this.pendingDormantEvictionBytes,
                    this.renderer.regionalGeometryUsedBytes(),
                    this.renderer.regionalGeometryPublicationLimitBytes(),
                    this.renderer.regionalGeometryUsedBytes(), waitRegion, sourceReady,
                    networkOwned, workerOwned, waitModels, rendererOwned, idleWorkers,
                    runningWorkers, completedWorkers, completedWorkerBytes, lanes.idle(),
                    lanes.active(), lanes.activeSections(), lanes.bodyBytes(), this.reconnects,
                    this.renderer.regionalLargestFreeGeometryUnits(),
                    this.renderer.regionalGeometrySectionCount());
        }

        void drainDemand() {
            if (this.resetRequested.getAndSet(false)) {
                for (long key : List.copyOf(this.demands.keySet())) this.retireDemand(key);
                this.dormantRoots.clear();
                this.dormantGeometryBytes = 0;
                this.demands.clear();
                this.clearStageQueues();
            }
            this.demands.drainTop((key, add) -> {
                if (add) {
                    this.addDemand(key);
                } else {
                    long top = key;
                    Set<Long> owned = this.demandsByTop.get(top);
                    if (owned != null) {
                        for (long ownedKey : List.copyOf(owned)) {
                            this.retireDemand(ownedKey);
                        }
                    }
                }
            });
            this.demands.drainRegions(this::applyRegionChanged);
            this.drainDetailMailbox();
        }

        void drainDetailMailbox() {
            @SuppressWarnings("unchecked")
            ArrayDeque<DetailEvent>[] buckets = new ArrayDeque[
                    HierarchicalOcclusionTraverser.DETAIL_BUCKET_COUNT];
            for (int bucket = 0; bucket < buckets.length; bucket++) {
                buckets[bucket] = new ArrayDeque<>();
            }
            this.demands.drainDetail((key, update) -> buckets[update.bucket()].addLast(
                    new DetailEvent(key, update.action(), update.epoch())));
            for (int bucket = 0; bucket < buckets.length; bucket++) {
                int retained = buckets[bucket].size();
                while (retained-- > 0) {
                    DetailEvent event = buckets[bucket].removeFirst();
                    if (event.action == HierarchicalOcclusionTraverser.ACTION_DORMANT) {
                        this.markDormant(event.key, bucket, event.epoch);
                    } else if (event.action == HierarchicalOcclusionTraverser.ACTION_WAKE) {
                        this.wakeDormant(event.key, event.epoch);
                    } else {
                        buckets[bucket].addLast(event);
                    }
                }
            }
            this.evictDormant(this.dormantGeometryBytes - this.dormantCapBytes(), false);
            for (int bucket = HierarchicalOcclusionTraverser.DETAIL_BUCKET_COUNT - 1;
                 bucket >= 0; bucket--) {
                ArrayDeque<DetailEvent> pending = buckets[bucket];
                while (!pending.isEmpty()) {
                    DetailEvent event = pending.removeFirst();
                    long parent = event.key;
                    int epoch = event.epoch;
                    Demand demand = this.demands.get(parent);
                    if (demand == null || !demand.installed
                            || demand.candidate != SectionDemandTable.CandidateState.NONE
                            || SectionKey.level(parent) == 0 || this.isCoarsening(parent)
                            || !newerEpoch(epoch, demand.latestRefinementEpoch)) continue;
                    if (this.indexFor(parent) == null) {
                        this.ensureRegion(parent);
                        this.demands.offerDetail(parent,
                                HierarchicalOcclusionTraverser.ACTION_REFINE, bucket, epoch);
                        continue;
                    }
                    this.demands.setPriority(demand, bucket);
                    if (!this.addChildren(parent, bucket)) {
                        this.demands.offerDetail(parent,
                                HierarchicalOcclusionTraverser.ACTION_REFINE, bucket, epoch);
                        break;
                    }
                    demand.latestRefinementEpoch = epoch;
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
                demand.retention = SectionDemandTable.Retention.WARM;
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
            long blockedBytes = 0;
            for (long blockedKey : this.rendererBlocked) {
                Demand blocked = this.demands.get(blockedKey);
                if (blocked != null) blockedBytes = Math.max(blockedBytes,
                        blocked.blockedRequiredBytes);
            }
            this.evictDormant(blockedBytes, true);
        }

        void wakeDormant(long key, int epoch) {
            Demand demand = this.demands.get(key);
            if (demand == null || !newerEpoch(epoch, demand.latestDormancyEpoch)) return;
            demand.latestDormancyEpoch = epoch;
            demand.lastSelectedSequence = ++this.selectionSequence;
            boolean transitioned = demand.dormant;
            demand.dormant = false;
            demand.retention = SectionDemandTable.Retention.SELECTED;
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

        long evictDormant(long requiredBytes, boolean forAdmission) {
            if (requiredBytes <= 0 || this.dormantRoots.isEmpty()) return 0;
            long scheduled = 0;
            while (scheduled < requiredBytes && !this.dormantRoots.isEmpty()) {
                DormantRoot root = this.selectDormantEviction();
                if (root == null) break;
                Demand demand = this.demands.get(root.key);
                boolean valid = demand != null && demand.dormant
                        && demand.publication != null && !this.overlapsCoarsening(root.key);
                this.removeDormantRoot(root.key);
                if (!valid) {
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
                if (forAdmission) this.admissionEvictions++;
                else this.capEvictions++;
                this.lastEvictionDistanceSquared = distance;
                this.lastEvictionBucket = root.bucket;
                this.lastEvictionAge = age;
            }
            this.validateGeometryAccounting();
            return scheduled;
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
            if (this.activeGeometryBytes < 0 || this.dormantGeometryBytes < 0
                    || this.dormantGeometryBytes > this.activeGeometryBytes) {
                throw new IllegalStateException("regional geometry ownership underflow");
            }
        }

        long coarsen(long parent) {
            Set<Long> owned = this.demandsByTop.get(topAncestor(parent));
            long bytes = 0;
            boolean hasWork = false;
            if (owned != null) for (long key : owned) {
                Demand child = this.demands.get(key);
                if (key != parent && contains(parent, key) && child != null) {
                    bytes += child.activeGeometryBytes;
                    hasWork |= child.installed || child.completedGeometryOwned
                            || child.publishingGeometryOwned;
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
            long region = regionFor(key);
            SectionDemandTable.RegionDemand regionState = this.demands.region(region);
            Demand demand = this.demands.remove(key);
            if (demand == null || demand.coverage) return;
            this.rendererBlocked.remove(key);
            this.forgetDormancyForSubtree(key);
            this.discardCompletedGeometry(demand);
            this.releasePublishingGeometryAccounting(demand);
            demand.pendingIndex = null;
            demand.pendingOrdinal = -1;
            if (demand.installed) this.activeCount--;
            demand.installed = false;
            this.setActiveGeometryBytes(demand, 0);
            // One fenced renderer operation owns the whole subtree; do not close each child.
            demand.publication = null;
            demand.previousPublication = null;
            removeOwned(this.demandsByTop, topAncestor(key), key);
            if (this.demands.region(region) == null) this.releaseRegion(region, regionState);
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
            this.retryRendererBlocked();
        }

        void retryRendererBlocked() {
            if (!this.coarseningRoots.isEmpty()) return;
            for (long key : List.copyOf(this.rendererBlocked)) {
                Demand demand = this.demands.get(key);
                if (demand == null || demand.completedGeometry == null
                        || demand.candidate
                        != SectionDemandTable.CandidateState.WORKER_OWNED) {
                    this.rendererBlocked.remove(key);
                    continue;
                }
                demand.blockedRequiredBytes = 0;
                this.rendererBlocked.remove(key);
                this.demands.ready(demand, SectionDemandTable.ReadyKind.RENDERER);
            }
        }

        void addDemand(long key) { this.addDemand(key, 0); }

        void addDemand(long key, int bucket) {
            Demand existing = this.demands.get(key);
            if (existing != null) {
                this.demands.setPriority(existing, bucket);
                return;
            }
            Demand demand = this.demands.adopt(new Demand(key));
            this.demands.setPriority(demand, bucket);
            if (demand.coverage) this.missingCoverage.add(key);
            this.demandsByTop.computeIfAbsent(topAncestor(key), ignored -> new LinkedHashSet<>())
                    .add(key);
            RegionalProtocol.RegionIndex index = this.indexFor(key);
            if (index == null) {
                demand.candidate = SectionDemandTable.CandidateState.WAIT_REGION;
                this.ensureRegion(key);
            }
            else {
                int ordinal = index.ordinal(key);
                if (ordinal < 0 || !index.isPresent(ordinal)) this.retireDemand(key);
                else this.bind(demand, index, ordinal);
            }
        }

        boolean addChildren(long parent, int bucket) {
            RegionalProtocol.RegionIndex index = this.indexFor(parent);
            if (index == null) return false;
            int parentOrdinal = index.ordinal(parent);
            if (parentOrdinal < 0 || !index.isPresent(parentOrdinal)
                    || SectionKey.level(parent) == 0) return true;
            int childMask = index.childMask(parentOrdinal);
            for (int child = 0; child < 8; child++) {
                if ((childMask & 1 << child) == 0) continue;
                long key = child(parent, child);
                this.addDemand(key, bucket);
            }
            return true;
        }

        void installIndex(RegionalProtocol.RegionIndex index) {
            long region = regionKey(index.regionX(), index.regionZ());
            SectionDemandTable.RegionDemand state = this.demands.region(region);
            if (state == null || state.announcedGeneration != index.generation()) return;
            state.index = index;
            state.installedGeneration = index.generation();
            if (!state.members.isEmpty()) for (long key : List.copyOf(state.members.keySet())) {
                Demand demand = this.demands.get(key);
                if (demand == null) continue;
                int ordinal = index.ordinal(demand.key);
                if (ordinal < 0 || !index.isPresent(ordinal)) this.retireDemand(demand.key);
                else this.bind(demand, index, ordinal);
            }
        }

        void retireRegion(long region) {
            SectionDemandTable.RegionDemand state = this.demands.region(region);
            if (state == null) return;
            for (long key : List.copyOf(state.members.keySet())) this.retireDemand(key);
        }

        void retireDemand(long key) {
            Demand demand = this.demands.get(key);
            if (demand == null) return;
            this.demands.unlinkReady(demand);
            this.rendererBlocked.remove(key);
            this.forgetDormancyForSubtree(key);
            this.discardCompletedGeometry(demand);
            this.releasePublishingGeometryAccounting(demand);
            this.demands.revise(demand);
            if (demand.installed) this.activeCount--;
            demand.installed = false;
            this.setActiveGeometryBytes(demand, 0);
            if (demand.publication != null) demand.publication.close();
            if (demand.previousPublication != null) demand.previousPublication.close();
            demand.previousPublication = null;
            if (demand.coverage && hasTop(key)) {
                // An authoritative absent section represents air, not coverage work waiting for
                // renderer admission. A later region generation will add it back when it binds.
                this.missingCoverage.remove(key);
                demand.publication = null;
                demand.pendingIndex = null;
                demand.pendingOrdinal = -1;
                demand.index = null;
                demand.ordinal = -1;
                demand.candidate = SectionDemandTable.CandidateState.WAIT_REGION;
            } else {
                this.missingCoverage.remove(key);
                long region = regionFor(key);
                SectionDemandTable.RegionDemand regionState = this.demands.region(region);
                this.demands.remove(key);
                removeOwned(this.demandsByTop, topAncestor(key), key);
                if (this.demands.region(region) == null) {
                    this.releaseRegion(region, regionState);
                }
            }
        }

        void releaseRegion(long region, SectionDemandTable.RegionDemand state) {
            if (state == null || !state.subscribed) return;
            state.subscribed = false;
            if (this.quic == null) return;
            try {
                this.quic.releaseRegion((int) region, (int) (region >>> 32));
            } catch (IOException failure) {
                Logger.warn("Could not release obsolete regional subscription; the connection "
                        + "will discard it", failure);
            }
        }

        void bind(Demand demand, RegionalProtocol.RegionIndex index, int ordinal) {
            if (ordinal < 0 || !index.isPresent(ordinal)) return;
            if (demand.activeWorkerSlot != -1 || demand.completedGeometry != null
                    || demand.candidate == SectionDemandTable.CandidateState.RENDERER_OWNED) {
                demand.pendingIndex = index;
                demand.pendingOrdinal = ordinal;
                return;
            }
            if (demand.index != null && demand.ordinal >= 0 && demand.installed
                    && demand.index.sectionFingerprint(demand.ordinal)
                            .equals(index.sectionFingerprint(ordinal))
                    && demand.index.childMask(demand.ordinal) == index.childMask(ordinal)) {
                demand.index = index;
                demand.ordinal = ordinal;
                return;
            }
            demand.pendingIndex = null;
            demand.pendingOrdinal = -1;
            this.demands.unlinkReady(demand);
            this.discardCompletedGeometry(demand);
            this.demands.revise(demand);
            demand.index = index;
            demand.regionGeneration = index.generation();
            demand.ordinal = ordinal;
            if (demand.coverage) this.missingCoverage.add(demand.key);
            this.queueBound(demand);
        }

        void queueBound(Demand demand) {
            demand.candidate = SectionDemandTable.CandidateState.READY_SOURCE;
            this.demands.ready(demand, SectionDemandTable.ReadyKind.SOURCE);
        }

        void processStages() throws Exception {
            this.scheduleReadyPublications();
            this.processWaitingModels();
            this.scheduleSourceWork();
            while (true) {
                List<Demand> selected = this.selectNetworkBatch();
                if (selected.isEmpty()) break;
                if (!this.sendBatch(selected)) break;
            }
        }

        void ensureRegion(long key) { this.queueRegion(regionFor(key)); }

        void queueRegion(long region) {
            this.demands.readyRegion(this.demands.region(region));
        }

        void processRegions() throws IOException {
            if (this.worldIdentity == null) return;
            SectionDemandTable.RegionDemand region;
            while ((region = this.demands.pollRegion()) != null) {
                if (region.requested || region.users == 0
                        || region.subscribed && (region.index != null || region.absent)) continue;
                this.quic.requestRegion((int) region.key, (int) (region.key >>> 32));
                region.requested = true;
                region.subscribed = true;
            }
        }

        WorkerSlot idleWorker() {
            for (WorkerSlot worker : this.sectionWorkers) if (worker.idle()) return worker;
            return null;
        }

        WorkerSlot idleWorker(boolean coverage) {
            WorkerSlot idle = this.idleWorker();
            if (idle != null || !coverage) return idle;
            Demand selected = null;
            for (Demand demand : this.demands.values()) {
                if (demand.coverage || demand.completedGeometry == null
                        || demand.completedSlot == null
                        || demand.candidate
                        != SectionDemandTable.CandidateState.WORKER_OWNED) continue;
                if (selected == null || demand.pixelBucket < selected.pixelBucket) {
                    selected = demand;
                }
            }
            if (selected == null) return null;
            WorkerSlot reclaimed = selected.completedSlot;
            this.rendererBlocked.remove(selected.key);
            this.discardCompletedGeometry(selected);
            selected.candidate = SectionDemandTable.CandidateState.READY_SOURCE;
            this.demands.ready(selected, SectionDemandTable.ReadyKind.SOURCE);
            return reclaimed.idle() ? reclaimed : null;
        }

        void drainWorkers() throws IOException {
            for (WorkerSlot worker : this.sectionWorkers) {
                WorkerResult result = worker.completion();
                if (result == null) continue;
                switch (result) {
                    case WorkerIndex ready -> {
                        SectionDemandTable.RegionDemand region = this.demands.region(ready.region());
                        if (ready.session() == this.id
                                && ready.connection() == this.connectionEpoch
                                && ready.slot() == worker.index
                                && region != null && region.resourceSlot == worker.index
                                && region.announcedGeneration == ready.generation()) {
                            region.resourceSlot = -1;
                            this.installIndex(ready.index());
                        } else if (region != null && region.resourceSlot == worker.index) {
                            region.resourceSlot = -1;
                            this.queueRegion(region.key);
                        }
                        worker.releaseCompletion();
                    }
                    case WorkerMiss miss -> {
                        Demand demand = this.currentWorkerDemand(miss.ticket(), worker);
                        if (demand != null) {
                            demand.activeWorkerSlot = -1;
                            demand.candidate = SectionDemandTable.CandidateState.READY_SOURCE;
                            this.cacheReads++;
                            this.cacheMisses++;
                            this.demands.ready(demand, SectionDemandTable.ReadyKind.NETWORK);
                        } else {
                            this.finishStaleWorker(miss.ticket(), worker);
                        }
                        worker.releaseCompletion();
                    }
                    case WorkerModels models -> {
                        Demand demand = this.currentWorkerDemand(models.ticket(), worker);
                        if (demand != null) {
                            demand.activeWorkerSlot = -1;
                            demand.waitingModels = models.blocks();
                            demand.candidate = SectionDemandTable.CandidateState.WAIT_MODELS;
                            this.waitingModels.add(demand.key);
                            this.recordWorkerSource(models.cacheHit(), models.compressedBytes(),
                                    false);
                        } else {
                            this.finishStaleWorker(models.ticket(), worker);
                        }
                        worker.releaseCompletion();
                    }
                    case WorkerGeometry geometry -> {
                        Demand demand = this.currentWorkerDemand(geometry.ticket(), worker);
                        if (demand == null) {
                            geometry.geometry().free();
                            this.finishStaleWorker(geometry.ticket(), worker);
                            worker.releaseCompletion();
                            continue;
                        }
                        this.recordWorkerSource(geometry.cacheHit(), geometry.compressedBytes(),
                                true);
                        demand.completedSlot = worker;
                        this.completeGeometry(demand, geometry.geometry(),
                                geometry.completedNanos());
                        // The slot remains COMPLETED and owns the mesh until renderer transfer.
                    }
                    case WorkerFailure failed -> {
                        Demand currentDemand = null;
                        SectionDemandTable.RegionDemand currentRegion = null;
                        boolean stale = switch (failed.task()) {
                            case SectionWorkerTask section -> {
                                currentDemand = this.currentWorkerDemand(section.ticket(), worker);
                                yield currentDemand == null;
                            }
                            case IndexWorkerTask index -> {
                                currentRegion = this.demands.region(index.region());
                                yield index.session() != this.id
                                        || index.connection() != this.connectionEpoch
                                        || currentRegion == null
                                        || currentRegion.resourceSlot != worker.index
                                        || currentRegion.announcedGeneration != index.generation();
                            }
                        };
                        if (failed.task() instanceof SectionWorkerTask section && stale) {
                            this.finishStaleWorker(section.ticket(), worker);
                        }
                        if (!stale && failed.task() instanceof SectionWorkerTask section) {
                            currentDemand.activeWorkerSlot = -1;
                            this.demands.revise(currentDemand);
                            currentDemand.candidate =
                                    SectionDemandTable.CandidateState.READY_SOURCE;
                            this.demands.ready(currentDemand,
                                    section.source() == WorkerSource.CACHE
                                            ? SectionDemandTable.ReadyKind.SOURCE
                                            : SectionDemandTable.ReadyKind.NETWORK);
                        } else if (!stale && currentRegion != null) {
                            currentRegion.resourceSlot = -1;
                            currentRegion.requested = false;
                            currentRegion.index = null;
                            this.queueRegion(currentRegion.key);
                        }
                        worker.releaseCompletion();
                        if (!stale) Logger.warn("Regional worker " + failed.slot()
                                + " failed; its last-known-good geometry remains active",
                                failed.failure());
                    }
                }
            }
        }

        Demand currentWorkerDemand(SectionDemandTable.Ticket ticket, WorkerSlot worker) {
            if (!this.demands.current(ticket) || ticket.resourceSlot() != worker.index) return null;
            Demand demand = this.demands.get(ticket.key());
            return demand != null && demand.activeWorkerSlot == worker.index ? demand : null;
        }

        void finishStaleWorker(SectionDemandTable.Ticket ticket, WorkerSlot worker) {
            Demand demand = this.demands.get(ticket.key());
            if (demand == null || demand.activeWorkerSlot != worker.index) return;
            demand.activeWorkerSlot = -1;
            if (demand.pendingIndex == null || demand.pendingOrdinal < 0) return;
            RegionalProtocol.RegionIndex index = demand.pendingIndex;
            int ordinal = demand.pendingOrdinal;
            demand.pendingIndex = null;
            demand.pendingOrdinal = -1;
            this.bind(demand, index, ordinal);
        }

        void recordWorkerSource(boolean cacheHit, int compressedBytes, boolean meshed) {
            if (cacheHit) {
                this.cacheReads++;
                this.cacheHits++;
                this.cacheBytes += compressedBytes;
            } else {
                this.receivedBytes += compressedBytes;
            }
            this.decodedSections++;
            if (meshed) this.meshedSections++;
        }

        void processWaitingModels() {
            var iterator = this.waitingModels.iterator();
            while (iterator.hasNext()) {
                Demand demand = this.demands.get(iterator.next());
                if (demand == null
                        || demand.candidate != SectionDemandTable.CandidateState.WAIT_MODELS
                        || demand.waitingModels == null) {
                    iterator.remove();
                    continue;
                }
                if (!this.mesher.modelsReady(demand.waitingModels)) continue;
                demand.waitingModels = null;
                demand.candidate = SectionDemandTable.CandidateState.READY_SOURCE;
                iterator.remove();
                this.demands.ready(demand, SectionDemandTable.ReadyKind.SOURCE);
            }
        }

        static void freeWorkerResult(WorkerResult result) {
            if (result instanceof WorkerGeometry geometry) geometry.geometry().free();
        }

        void scheduleSourceWork() {
            int remaining = this.demands.readyCount(SectionDemandTable.ReadyKind.SOURCE);
            while (remaining-- > 0) {
                Demand demand = this.demands.poll(SectionDemandTable.ReadyKind.SOURCE);
                if (demand == null) return;
                if (demand.candidate != SectionDemandTable.CandidateState.READY_SOURCE) continue;
                if (demand.index.isEmpty(demand.ordinal)) {
                    this.publishEmpty(demand);
                    continue;
                }
                if (this.mappings == null || !this.catalogFingerprint.equals(
                        this.requiredCatalogFingerprint)) {
                    this.demands.ready(demand, SectionDemandTable.ReadyKind.SOURCE);
                    continue;
                }
                WorkerSlot worker = this.idleWorker(demand.coverage);
                if (worker == null) {
                    this.demands.ready(demand, SectionDemandTable.ReadyKind.SOURCE);
                    return;
                }
                WorkerSource source = WorkerSource.CACHE;
                SectionDemandTable.Ticket ticket = demand.ticket(this.id, worker.index);
                SectionWorkerTask task = new SectionWorkerTask(ticket, demand.index,
                        demand.ordinal, source, null, this.mappings);
                demand.activeWorkerSlot = worker.index;
                this.demands.owned(demand, SectionDemandTable.CandidateState.WORKER_OWNED);
                if (!worker.assign(task)) {
                    demand.activeWorkerSlot = -1;
                    demand.candidate = SectionDemandTable.CandidateState.READY_SOURCE;
                    this.demands.ready(demand, SectionDemandTable.ReadyKind.SOURCE);
                    return;
                }
            }
        }

        List<Demand> selectNetworkBatch() {
            List<Demand> result = new ArrayList<>(RegionalProtocol.MAX_SECTION_REQUESTS);
            Demand first = this.demands.poll(SectionDemandTable.ReadyKind.NETWORK);
            if (first == null) return result;
            if (first.candidate != SectionDemandTable.CandidateState.READY_SOURCE) return result;
            result.add(first);
            while (result.size() < RegionalProtocol.MAX_SECTION_REQUESTS) {
                Demand demand = this.demands.pollSameRegion(
                        SectionDemandTable.ReadyKind.NETWORK, first.regionKey,
                        first.coverage, first.pixelBucket);
                if (demand == null) break;
                if (demand.candidate != SectionDemandTable.CandidateState.READY_SOURCE
                        || demand.index != first.index) continue;
                result.add(demand);
            }
            return result;
        }

        boolean sendBatch(List<Demand> selected) throws IOException {
            long batchConnectionEpoch = this.connectionEpoch;
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
                        @Override public void reply(RegionalProtocol.SectionReply reply)
                                throws InterruptedException {
                            NetworkReply handoff = new NetworkReply(batchConnectionEpoch, reply);
                            networkReplies.add(handoff);
                            signal();
                            handoff.awaitTransfer();
                            if (!open.get()) throw new InterruptedException("session closed");
                        }
                        @Override public void complete() {
                            putEvent(new BatchComplete(batchConnectionEpoch, reservedBytes,
                                    selected.size()));
                        }
                        @Override public void failed(Throwable failure) {
                            putEvent(new BatchFailed(batchConnectionEpoch, failure));
                        }
                    });
            if (!accepted) {
                for (Demand demand : selected) {
                    this.demands.ready(demand, SectionDemandTable.ReadyKind.NETWORK);
                }
                return false;
            }
            for (Demand demand : selected) {
                demand.candidate = SectionDemandTable.CandidateState.NETWORK_OWNED;
            }
            this.inFlightBatches++;
            this.inFlightSections += selected.size();
            this.inFlightBytes += reservedBytes;
            return true;
        }

        void drainNetworkReplies() throws IOException {
            while (true) {
                NetworkReply handoff = this.networkReplies.peek();
                if (handoff == null) return;
                if (handoff.connectionEpoch != this.connectionEpoch) {
                    this.networkReplies.remove(handoff);
                    handoff.transferred();
                    continue;
                }
                RegionalProtocol.SectionReply reply = handoff.reply;
                Demand demand = this.demands.get(reply.key());
                if (demand == null
                        || demand.candidate != SectionDemandTable.CandidateState.NETWORK_OWNED
                        || demand.index.generation() != reply.generation()
                        || demand.ordinal != reply.ordinal()) {
                    this.networkReplies.remove(handoff);
                    handoff.transferred();
                    continue;
                }
                switch (reply.status()) {
                case DATA -> {
                    if (reply.compressed().length
                            != demand.index.compressedLength(demand.ordinal)) {
                        throw new IOException("regional section reply disagrees with its index");
                    }
                    if (this.mappings == null || !this.catalogFingerprint.equals(
                            this.requiredCatalogFingerprint)) return;
                    WorkerSlot worker = this.idleWorker(demand.coverage);
                    if (worker == null) return;
                    SectionDemandTable.Ticket ticket = demand.ticket(this.id, worker.index);
                    SectionWorkerTask task = new SectionWorkerTask(ticket, demand.index,
                            demand.ordinal, WorkerSource.NETWORK, reply.compressed(), this.mappings);
                    demand.activeWorkerSlot = worker.index;
                    this.demands.owned(demand,
                            SectionDemandTable.CandidateState.WORKER_OWNED);
                    if (!worker.assign(task)) {
                        demand.activeWorkerSlot = -1;
                        demand.candidate = SectionDemandTable.CandidateState.NETWORK_OWNED;
                        return;
                    }
                }
                case EMPTY -> {
                    if (!demand.index.isEmpty(demand.ordinal)) {
                        throw new IOException("unexpected empty regional section");
                    }
                    this.publishEmpty(demand);
                }
                case STALE -> {
                    long region = regionFor(demand.key);
                    SectionDemandTable.RegionDemand state = this.demands.region(region);
                    if (state != null) {
                        state.index = null;
                        state.installedGeneration = 0;
                        state.requested = false;
                    }
                    this.queueRegion(region);
                }
                case ABSENT -> throw new IOException("indexed regional section became absent");
                }
                this.networkReplies.remove(handoff);
                handoff.transferred();
            }
        }

        void publishEmpty(Demand demand) {
            BuiltSection empty = BuiltSection.emptyWithChildren(demand.key, demand.revision + 1L,
                    (byte) demand.index.childMask(demand.ordinal));
            this.completeGeometry(demand, empty, System.nanoTime());
        }

        void completeGeometry(Demand demand, BuiltSection geometry, long completedNanos) {
            if (demand.completedGeometryOwned || demand.publishingGeometryOwned
                    || demand.completedGeometry != null) {
                geometry.free();
                throw new IllegalStateException("regional geometry was completed twice");
            }
            demand.completedGeometry = geometry;
            demand.meshCompletedNanos = completedNanos;
            demand.geometryBytes = geometry.geometryBuffer == null ? 0
                    : (geometry.geometryBuffer.size + 1023L) & ~1023L;
            demand.completedGeometryOwned = true;
            this.completedGeometryBytes += demand.geometryBytes;
            demand.candidate = SectionDemandTable.CandidateState.WORKER_OWNED;
            this.demands.ready(demand, SectionDemandTable.ReadyKind.RENDERER);
        }

        void scheduleReadyPublications() {
            // One explicit renderer handoff at a time. Its maximum is the number of real
            // section workers, not another arbitrary queue capacity.
            if (!this.publicationQueue.isEmpty()) return;
            int remaining = Math.min(this.sectionWorkerCount,
                    this.demands.readyCount(SectionDemandTable.ReadyKind.RENDERER));
            ArrayList<ReadyPublication> ready = new ArrayList<>(remaining);
            while (remaining-- > 0) {
                Demand demand = this.demands.poll(SectionDemandTable.ReadyKind.RENDERER);
                if (demand == null) break;
                if (!current(demand, demand.revision,
                        SectionDemandTable.CandidateState.WORKER_OWNED)
                        || demand.completedGeometry == null) continue;
                ready.add(new ReadyPublication(demand, demand.revision));
            }
            this.publishReadyBatch(ready);
        }

        void publishReadyBatch(List<ReadyPublication> ready) {
            if (ready.isEmpty()) return;
            synchronized (this.publicationLock) {
                ArrayList<PreparedPublication> prepared = new ArrayList<>(ready.size());
                ArrayList<VoxyRenderSystem.SectionSubmission> submissions =
                        new ArrayList<>(ready.size());
                for (ReadyPublication candidate : ready) {
                    Demand demand = candidate.demand();
                    long revision = candidate.revision();
                    BuiltSection geometry = demand.completedGeometry;
                    if (!this.open.get() || !current(demand, revision,
                            SectionDemandTable.CandidateState.WORKER_OWNED)
                            || geometry == null || demand.completedGeometry != geometry) {
                        if (demand.completedGeometry == geometry && geometry != null) {
                            this.discardCompletedGeometry(demand);
                        }
                        continue;
                    }
                    VoxyRenderSystem.SectionPublication previous = demand.publication;
                    PreparedPublication item = new PreparedPublication(demand, revision, geometry,
                            previous);
                    prepared.add(item);
                    submissions.add(new VoxyRenderSystem.SectionSubmission(demand.key, geometry,
                            demand.coverage, demand.meshCompletedNanos,
                            Optional.ofNullable(previous),
                            () -> demand.revision == revision
                                    && demand.candidate
                                    == SectionDemandTable.CandidateState.RENDERER_OWNED
                                    && this.open.get(),
                            () -> {}));
                }
                if (prepared.isEmpty()) return;
                for (PreparedPublication item : prepared) {
                    item.demand().completedGeometry = null;
                    this.demands.owned(item.demand(),
                            SectionDemandTable.CandidateState.RENDERER_OWNED);
                }
                List<VoxyRenderSystem.SectionPublication> publications;
                try {
                    publications = this.publisher.publishBatch(submissions);
                } catch (RuntimeException | Error failure) {
                    // publishBatch guarantees a throw occurs before ownership transfer. Restore
                    // the complete owner-side batch so normal session teardown releases it once.
                    for (PreparedPublication item : prepared) {
                        Demand demand = item.demand();
                        if (demand.candidate
                                == SectionDemandTable.CandidateState.RENDERER_OWNED
                                && demand.completedGeometry == null
                                && demand.publication == item.previous()) {
                            demand.completedGeometry = item.geometry();
                            demand.candidate = SectionDemandTable.CandidateState.WORKER_OWNED;
                            this.demands.ready(demand,
                                    SectionDemandTable.ReadyKind.RENDERER);
                        }
                    }
                    throw failure;
                }
                for (int index = 0; index < prepared.size(); index++) {
                    PreparedPublication item = prepared.get(index);
                    this.transferGeometryAccounting(item.demand());
                    item.demand().previousPublication = item.previous();
                    item.demand().publication = publications.get(index);
                    this.publicationQueue.addLast(new PublicationRef(item.demand().key,
                            item.revision(), item.previous(), item.demand().completedSlot));
                }
            }
        }

        long dormantCapBytes() {
            return this.renderer.regionalGeometryCapacityBytes() / 3L;
        }

        long selectedGeometryBytes() {
            return Math.max(0, this.activeGeometryBytes - this.dormantGeometryBytes);
        }

        void discardCompletedGeometry(Demand demand) {
            BuiltSection geometry = demand.completedGeometry;
            demand.completedGeometry = null;
            WorkerSlot slot = demand.completedSlot;
            demand.completedSlot = null;
            demand.activeWorkerSlot = -1;
            if (geometry != null) geometry.free();
            if (slot != null && slot.completion() != null) slot.releaseCompletion();
            this.releaseGeometryAccounting(demand);
        }

        boolean releaseGeometryAccounting(Demand demand) {
            if (!demand.completedGeometryOwned) return false;
            demand.completedGeometryOwned = false;
            this.completedGeometryBytes -= demand.geometryBytes;
            return true;
        }

        void transferGeometryAccounting(Demand demand) {
            if (!this.releaseGeometryAccounting(demand)
                    || demand.publishingGeometryOwned) {
                throw new IllegalStateException(
                        "regional publishing geometry ownership is invalid");
            }
            demand.publishingGeometryOwned = true;
            this.publishingGeometryBytes += demand.geometryBytes;
        }

        void releasePublishingGeometryAccounting(Demand demand) {
            if (!demand.publishingGeometryOwned) return;
            demand.publishingGeometryOwned = false;
            this.publishingGeometryBytes -= demand.geometryBytes;
        }

        void releaseAllGeometryAccounting(Demand demand) {
            this.releaseGeometryAccounting(demand);
            this.releasePublishingGeometryAccounting(demand);
        }

        void pollPublications() throws IOException {
            int remaining = this.publicationQueue.size();
            while (remaining-- > 0) {
                PublicationRef ref = this.publicationQueue.pollFirst();
                if (ref == null) return;
                Demand demand = this.demands.get(ref.key());
                if (!current(demand, ref.revision(),
                        SectionDemandTable.CandidateState.RENDERER_OWNED)
                        || demand.publication == null) {
                    this.releaseRendererSlot(ref.slot());
                    if (demand != null && demand.completedSlot == ref.slot()) {
                        demand.completedSlot = null;
                        demand.activeWorkerSlot = -1;
                    }
                    continue;
                }
                Optional<VoxyRenderSystem.AllocationBlock> blocked =
                        demand.publication.takeAllocationBlock();
                if (blocked.isPresent()) {
                    VoxyRenderSystem.AllocationBlock result = blocked.orElseThrow();
                    this.releasePublishingGeometryAccounting(demand);
                    demand.publication = ref.previous();
                    demand.previousPublication = null;
                    demand.completedGeometry = result.geometry();
                    demand.completedGeometryOwned = true;
                    this.completedGeometryBytes += demand.geometryBytes;
                    demand.candidate = SectionDemandTable.CandidateState.WORKER_OWNED;
                    if (result.status() == VoxyRenderSystem.AllocationStatus.IMPOSSIBLE) {
                        Logger.warn("Regional detail geometry cannot fit the configured arena: "
                                + result.requiredUnits() * 8L + " bytes");
                        if (demand.coverage) {
                            this.discardCompletedGeometry(demand);
                        } else {
                            this.retireDetailDemand(demand.key);
                        }
                    } else {
                        long requiredBytes = Math.max(1, result.requiredUnits()) * 8L;
                        this.evictDormant(requiredBytes, true);
                        demand.blockedRequiredBytes = requiredBytes;
                        this.rendererBlocked.add(demand.key);
                    }
                    continue;
                }
                Optional<Throwable> failure = demand.publication.activationFailure();
                if (failure.isPresent()) {
                    this.releaseAllGeometryAccounting(demand);
                    Logger.warn("Regional renderer publication failed; retaining its fallback",
                            failure.orElseThrow());
                    demand.publication = ref.previous();
                    demand.previousPublication = null;
                    demand.completedSlot = null;
                    demand.activeWorkerSlot = -1;
                    this.releaseRendererSlot(ref.slot());
                    demand.candidate = SectionDemandTable.CandidateState.READY_SOURCE;
                    this.demands.ready(demand, SectionDemandTable.ReadyKind.SOURCE);
                    continue;
                }
                if (!demand.publication.activationFencePassed()) {
                    this.publicationQueue.addLast(ref);
                    continue;
                }
                demand.candidate = SectionDemandTable.CandidateState.NONE;
                demand.previousPublication = null;
                this.setActiveGeometryBytes(demand, demand.geometryBytes);
                if (!demand.installed) {
                    demand.installed = true;
                    this.activeCount++;
                }
                if (demand.coverage) this.missingCoverage.remove(demand.key);
                this.activated++;
                this.uploadedSections++;
                this.releaseAllGeometryAccounting(demand);
                demand.completedSlot = null;
                demand.activeWorkerSlot = -1;
                this.releaseRendererSlot(ref.slot());
                if (demand.pendingIndex != null) {
                    RegionalProtocol.RegionIndex index = demand.pendingIndex;
                    int ordinal = demand.pendingOrdinal;
                    demand.pendingIndex = null; demand.pendingOrdinal = -1;
                    this.bind(demand, index, ordinal);
                }
                this.retryRendererBlocked();
            }
        }

        void releaseRendererSlot(WorkerSlot slot) {
            if (slot != null && slot.completion() != null) slot.releaseCompletion();
        }

        RegionalProtocol.RegionIndex indexFor(long key) {
            SectionDemandTable.RegionDemand region = this.demands.region(regionFor(key));
            return region == null ? null : (RegionalProtocol.RegionIndex) region.index;
        }

        void clearStageQueues() {
            this.publicationQueue.clear();
        }

        void putEvent(Event event) {
            if (this.open.get()) {
                this.events.add(event);
                signal();
            } else {
                discardEvent(event);
            }
        }

        void signal() {
            synchronized (this.wakeupLock) {
                this.wakePending = true;
                this.wakeupLock.notify();
            }
        }

        void awaitWake(long timeoutMillis) throws InterruptedException {
            synchronized (this.wakeupLock) {
                if (!this.wakePending) this.wakeupLock.wait(timeoutMillis);
                this.wakePending = false;
            }
        }
        void fail(Throwable failure) {
            this.failure = failure;
            this.open.set(false);
            signal();
            if (this.quic != null) this.quic.close();
        }

        String snapshot() {
            int idleWorkers = 0, runningWorkers = 0, completedWorkers = 0;
            for (WorkerSlot worker : this.sectionWorkers) synchronized (worker) {
                switch (worker.state) {
                    case IDLE -> idleWorkers++;
                    case RUNNING -> runningWorkers++;
                    case COMPLETED -> completedWorkers++;
                    case CLOSED -> {}
                }
            }
            RegionalQuicClient.LaneSnapshot lanes = this.quic == null
                    ? new RegionalQuicClient.LaneSnapshot(0, 0, 0, 0)
                    : this.quic.laneSnapshot();
            return "regional=ACTIVE dimension=" + this.dimension + " desired=" + this.demands.size()
                    + " active=" + this.activeCount + " regions=" + this.demands.regionCount()
                    + " coarsening=" + this.coarseningRoots.size()
                    + " batches=" + this.inFlightBatches + " inFlightBytes=" + this.inFlightBytes
                    + " coalescedInputs=" + this.demands.pendingInputCount()
                    + " coalescedOverwritten=" + this.demands.overwrittenInputCount()
                    + " coverageMissing=" + this.missingCoverage.size()
                    + " sourceReady=" + this.demands.readyCount(
                            SectionDemandTable.ReadyKind.SOURCE)
                    + " networkReady=" + this.demands.readyCount(
                            SectionDemandTable.ReadyKind.NETWORK)
                    + " rendererReady=" + this.demands.readyCount(
                            SectionDemandTable.ReadyKind.RENDERER)
                    + " workers=" + idleWorkers + '/' + runningWorkers + '/'
                    + completedWorkers
                    + " lanes=" + lanes.idle() + '/' + lanes.active()
                    + " laneSections=" + lanes.activeSections()
                    + " laneBodyBytes=" + lanes.bodyBytes()
                    + " connectionEpoch=" + this.connectionEpoch
                    + " reconnects=" + this.reconnects
                    + " publishQueue=" + this.publicationQueue.size()
                    + " geometryUsed=" + this.renderer.regionalGeometryUsedBytes()
                    + " geometryPhysicalLimit="
                    + this.renderer.regionalGeometryPublicationLimitBytes()
                    + " geometryLargestFreeUnits="
                    + this.renderer.regionalLargestFreeGeometryUnits()
                    + " geometrySections=" + this.renderer.regionalGeometrySectionCount()
                    + " geometryActive=" + this.activeGeometryBytes
                    + " geometrySelected=" + this.selectedGeometryBytes()
                    + " geometryDormant=" + this.dormantGeometryBytes
                    + " geometryDormantCap=" + this.dormantCapBytes()
                    + " dormantRoots=" + this.dormantRoots.size()
                    + " dormantPendingFree=" + this.pendingDormantEvictionBytes
                    + " geometryCompleted=" + this.completedGeometryBytes
                    + " geometryPublishing=" + this.publishingGeometryBytes
                    + " dormancyTransitions=" + this.dormancyTransitions
                    + " wakes=" + this.wakes + " instantWakes=" + this.instantWakes
                    + " dormantCapEvictions=" + this.capEvictions
                    + " dormantAdmissionEvictions=" + this.admissionEvictions
                    + " dormantFreed=" + this.dormantBytesFreedAfterFences
                    + " dormantLastEvictionDistance2=" + this.lastEvictionDistanceSquared
                    + " dormantLastEvictionBucket=" + this.lastEvictionBucket
                    + " dormantLastEvictionAge=" + this.lastEvictionAge
                    + ' ' + this.renderer.regionalPublicationLatencySnapshot()
                    + " received=" + this.receivedBytes
                    + " connectionFailure=" + String.valueOf(this.lastConnectionFailure)
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
            NetworkReply reply;
            while ((reply = this.networkReplies.poll()) != null) reply.transferred();
            for (WorkerSlot worker : this.sectionWorkers) worker.close();
            if (this.cache != null) this.cache.close();
            for (Demand demand : this.demands.values()) {
                this.discardCompletedGeometry(demand);
                if (demand.publication != null) demand.publication.close();
                if (demand.previousPublication != null) demand.previousPublication.close();
            }
            Event event;
            while ((event = this.events.poll()) != null) discardEvent(event);
            this.demands.clear();
            this.demandsByTop.clear();
            this.missingCoverage.clear();
            this.demands.clear();
            this.coarseningRoots.clear();
            this.rendererBlocked.clear();
            this.dormantRoots.clear();
            this.pendingDormantEvictions.clear();
            this.activeGeometryBytes = 0;
            this.dormantGeometryBytes = 0;
            this.pendingDormantEvictionBytes = 0;
            this.completedGeometryBytes = 0;
            this.publishingGeometryBytes = 0;
            this.clearStageQueues();
        }
    }

    private record DetailEvent(long key, int action, int epoch) {}
    private record ReadyPublication(Demand demand, long revision) {}
    private record PreparedPublication(Demand demand, long revision, BuiltSection geometry,
                                       VoxyRenderSystem.SectionPublication previous) {}
    private record PublicationRef(long key, long revision,
                                  VoxyRenderSystem.SectionPublication previous,
                                  Session.WorkerSlot slot) {}

    private sealed interface Event permits CatalogReady, Coarsened,
            CoarsenFailed, BatchComplete, BatchFailed, SnapshotRequest {}
    private record CatalogReady(RegionalProtocol.Hash32 fingerprint,
                                RegionalSectionCodec.Mappings mappings,
                                long connectionEpoch) implements Event {}
    private record Coarsened(long parent) implements Event {}
    private record CoarsenFailed(long parent, Throwable failure) implements Event {}
    private record BatchComplete(long connectionEpoch, long reservedBytes,
                                 int sectionCount) implements Event {}
    private record BatchFailed(long connectionEpoch, Throwable failure) implements Event {}
    private record SnapshotRequest(Consumer<PipelineSnapshot> receiver) implements Event {}

    private record CatalogTask(Session owner, CatalogCodec.Catalog catalog,
                               RegionalProtocol.Hash32 fingerprint,
                               long requirementRevision, long connectionEpoch) {
        void run(VoxyRenderSystem renderer) {
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
                    new RegionalSectionCodec.Mappings(blocks, biomes), this.connectionEpoch));
        }
    }

    private static void discardEvent(Event event) {
        // Remaining events carry no native or heavyweight resource.
    }

    private static void enqueueCatalog(CatalogTask task) {
        if (task.owner().open.get()) CATALOG_TASK.set(task);
    }

    private static boolean current(Demand demand, long revision,
                                   SectionDemandTable.CandidateState candidate) {
        return demand != null && demand.revision == revision
                && demand.candidate == candidate;
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
