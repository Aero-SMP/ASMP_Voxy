package me.cortex.voxy.client.lod;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.rendering.hierarchical.AsyncNodeManager;
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
        return requestDebugSession(session -> receiver.accept(session.pipelineSnapshot()));
    }

    /** Existing owner-event boundary; test actions themselves live only in the debug artifact. */
    static boolean requestDebugSession(Consumer<Session> receiver) {
        Objects.requireNonNull(receiver, "session observer");
        Session current = active;
        if (current == null || !current.open.get()) return false;
        boolean accepted = current.events.offer(new SessionObservation(receiver));
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
            long largestFreeGeometryUnits, int usedGeometrySections,
            long handoffGeneration, long handoffOccupied, long publicationActivated, long publicationReturned, long publicationCancelled, long publicationFailed, long outstandingLeases, long pendingCoverageReplies, long pendingRefinementReplies, long blockedGeometry, long blockedSectionId, long blockedTopology, long blockedStale, long impossible, long topologyGeneration, long allocationReleaseGeneration, long sectionIdReleaseGeneration, long handoffBusy, long dormancyTransitions, long wakes, long instantWakes, long dormantEvictions) {}

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
                task.owner().putEvent(new CatalogReady(task, null, failure));
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

    static final class Demand extends SectionDemandTable.Demand {
        RegionalProtocol.RegionIndex index;
        RegionalSectionCodec.BoundCatalog catalog;
        int ordinal = -1;
        VoxyRenderSystem.SectionPublication publication;
        VoxyRenderSystem.SectionPublication previousPublication;
        RegionalProtocol.RegionIndex pendingIndex;
        RegionalSectionCodec.BoundCatalog pendingCatalog;
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
        WorkerResource.Lease workLease;
        VoxyRenderSystem.AllocationStatus blockedReason;
        AsyncNodeManager.PublicationProgress blockedAt;
        long prerequisite;
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

    static final class Session implements AutoCloseable {
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
        final WorkerSlot metadataWorker = new WorkerSlot(-1);
        final int sectionWorkerCount;
        final Set<Long> waitingModels = new LinkedHashSet<>();
        final ConcurrentLinkedQueue<NetworkReply> networkReplies =
                new ConcurrentLinkedQueue<>();

        final Map<Long, LinkedHashSet<Long>> demandsByTop = new HashMap<>();
        final Set<Long> missingCoverage = new HashSet<>();
        final Set<Long> coarseningRoots = new HashSet<>();
        final Set<Long> rendererBlocked = new LinkedHashSet<>();
        final Set<Long> regionReleases = new LinkedHashSet<>();
        final Long2ObjectOpenHashMap<DormantRoot> dormantRoots =
                new Long2ObjectOpenHashMap<>();
        final Long2LongOpenHashMap pendingDormantEvictions = new Long2LongOpenHashMap();
        final Object publicationLock = new Object();
        final ArrayDeque<PublicationRef> publicationQueue = new ArrayDeque<>();
        final Runnable rendererWake = this::signal;
        long busyHandoff = -1;
        long handoffBusy;
        final long[] publicationOutcomes = new long[VoxyRenderSystem.UploadStatus.values().length];

        RegionalQuicClient quic;
        RegionalConnectionAttempt.Connector connector;
        RegionalConnectionAttempt connectionAttempt;
        long nextConnectionAttempt;
        boolean helloAccepted;
        Path cacheRoot;
        String serverKey;
        RegionalMetadataStore metadata;
        volatile long viewRevision;
        CatalogTask pendingCatalogTask;
        boolean pendingCatalogSubmitted;
        volatile RegionalProtocol.Hash32 rejectedCatalog;
        final java.util.concurrent.ConcurrentHashMap<RegionalProtocol.Hash32,
                java.lang.ref.WeakReference<RegionalSectionCodec.BoundCatalog>> savedMappings =
                new java.util.concurrent.ConcurrentHashMap<>();
        final LinkedHashMap<Long, SaveMetadataTask> metadataWrites = new LinkedHashMap<>();
        boolean associationPending;
        boolean cacheOpened;
        boolean metadataUnavailable;
        RegionalProtocol.Hash32 catalogProbed;
        RegionalProtocol.Control pendingControl;
        RegionalCache cache;
        RegionalProtocol.Hash32 worldIdentity;
        RegionalProtocol.Hash32 catalogFingerprint = RegionalProtocol.Hash32.ZERO;
        RegionalProtocol.Hash32 requiredCatalogFingerprint = RegionalProtocol.Hash32.ZERO;
        RegionalSectionCodec.Mappings mappings;
        RegionalSectionCodec.BoundCatalog currentCatalog;
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

        static final class NetworkReply {
            final long connectionEpoch;
            final RegionalProtocol.SectionReply reply;
            final SectionDemandTable.Ticket ticket;
            final Semaphore transferred = new Semaphore(0);
            final AtomicBoolean released = new AtomicBoolean();

            NetworkReply(long connectionEpoch, RegionalProtocol.SectionReply reply,
                         SectionDemandTable.Ticket ticket) {
                this.connectionEpoch = connectionEpoch;
                this.reply = reply;
                this.ticket = ticket;
            }

            void awaitTransfer() throws InterruptedException { this.transferred.acquire(); }
            void transferred() { if (this.released.compareAndSet(false, true)) this.transferred.release(); }
        }

        private enum WorkerSource { CACHE, NETWORK }
        sealed interface WorkerTask permits SectionWorkerTask, IndexWorkerTask, EmptyWorkerTask,
                BootstrapTask, OpenWorldTask, LoadMetadataTask, SaveMetadataTask, CatalogWorkerTask, AssociationTask, ProbeCatalogTask {}
        record EmptyWorkerTask(SectionDemandTable.Ticket ticket, byte children)
                implements WorkerTask {}
        private record SectionWorkerTask(SectionDemandTable.Ticket ticket,
                                         RegionalProtocol.RegionIndex index, int ordinal,
                                         WorkerSource source, byte[] compressed,
                                         RegionalSectionCodec.Mappings mappings, RegionalCache cache)
                implements WorkerTask {}
        private record IndexWorkerTask(long session, long connection, long view, long revision, long region, long generation,
                                       int slot, RegionalProtocol.RegionMessage message)
                implements WorkerTask {}
        sealed interface WorkerResult permits WorkerMiss, WorkerModels,
                WorkerGeometry, WorkerIndex, WorkerFailure, WorkerBootstrap, WorkerWorld,
                WorkerMetadata, WorkerSaved, WorkerCatalog, WorkerCatalogProbe {}
        private record ProbeCatalogTask(long view, RegionalProtocol.Hash32 world,
                                        RegionalProtocol.Hash32 fingerprint) implements WorkerTask {}
        private record WorkerCatalogProbe(ProbeCatalogTask task, CatalogCodec.Catalog catalog) implements WorkerResult {}
        record BootstrapTask(Path root, String server, String dimension) implements WorkerTask {}
        private record OpenWorldTask(long view, RegionalProtocol.Hash32 world) implements WorkerTask {}
        private record AssociationTask(long view, RegionalProtocol.Hash32 world, long stamp) implements WorkerTask {}
        private record LoadMetadataTask(long view, long region, long revision,
                                        RegionalProtocol.Hash32 world) implements WorkerTask {}
        private record SaveMetadataTask(long view, long region, RegionalProtocol.Hash32 world,
                                        RegionalProtocol.RegionMessage message, long stamp,
                                        java.util.function.BooleanSupplier current) implements WorkerTask {}
        private record CatalogWorkerTask(long view, long connection, long requirement, RegionalProtocol.Hash32 world,
                                          RegionalProtocol.CatalogMessage message, long stamp) implements WorkerTask {}
        private record WorkerBootstrap(RegionalMetadataStore metadata, RegionalProtocol.Hash32 hint)
                implements WorkerResult {}
        private record WorkerWorld(long view, RegionalCache cache) implements WorkerResult {}
        private record WorkerMetadata(LoadMetadataTask task, RegionalProtocol.RegionIndex index,
                                      RegionalSectionCodec.BoundCatalog binding,
                                      RegionalProtocol.Hash32 fingerprint, CatalogCodec.Catalog catalog)
                implements WorkerResult {}
        private record WorkerSaved() implements WorkerResult {}
        private record WorkerCatalog(CatalogWorkerTask task, CatalogCodec.Catalog catalog)
                implements WorkerResult {}
        private record WorkerMiss(SectionDemandTable.Ticket ticket, boolean corrupt)
                implements WorkerResult {}
        private record WorkerModels(SectionDemandTable.Ticket ticket, int[] blocks,
                                    boolean cacheHit, int compressedBytes)
                implements WorkerResult {}
        record WorkerGeometry(SectionDemandTable.Ticket ticket, BuiltSection geometry,
                                      long completedNanos, boolean cacheHit,
                                      int compressedBytes) implements WorkerResult {}
        private record WorkerIndex(IndexWorkerTask task, RegionalProtocol.RegionIndex index)
                implements WorkerResult {}
        private record WorkerFailure(WorkerTask task, int slot, Throwable failure)
                implements WorkerResult {}

        /** A persistent resource slot owns exactly one task or completion and has no backlog. */
        final class WorkerSlot {
            final int index;
            final Thread workerThread;
            final RegionalSectionCodec codec = new RegionalSectionCodec();
            final WorkerResource<WorkerResult> resource;
            private WorkerTask task;
            private WorkerResource.Lease taskLease;
            private long operationKey;
            private boolean sectionOperation;

            WorkerSlot(int index) {
                this.index = index;
                this.resource = new WorkerResource<>(index, Session::freeWorkerResult);
                this.workerThread = new Thread(this::run, "Voxy regional section worker-" + index);
                this.workerThread.setDaemon(true);
            }

            void start() { this.workerThread.start(); }
            synchronized WorkerResource.Lease assign(WorkerTask task) {
                WorkerResource.Lease lease = this.resource.acquire();
                if (lease == null) return null;
                this.task = Objects.requireNonNull(task);
                this.sectionOperation = task instanceof SectionWorkerTask || task instanceof EmptyWorkerTask;
                this.operationKey = switch (task) {
                    case SectionWorkerTask section -> section.ticket().key();
                    case EmptyWorkerTask empty -> empty.ticket().key();
                    default -> 0;
                };
                this.taskLease = lease;
                this.notifyAll();
                return lease;
            }
            boolean idle() { return this.resource.state() == WorkerResource.State.IDLE; }
            void releaseCompletion(WorkerResource.Lease lease) {
                if (this.resource.release(lease)) signal();
            }

            private void run() {
                try {
                    while (true) {
                        WorkerTask claimed;
                        WorkerResource.Lease lease;
                        synchronized (this) {
                            while (this.task == null
                                    && this.resource.state() != WorkerResource.State.CLOSED) {
                                try { this.wait(); }
                                catch (InterruptedException interrupted) {
                                    if (this.resource.state() == WorkerResource.State.CLOSED) return;
                                }
                            }
                            if (this.resource.state() == WorkerResource.State.CLOSED) return;
                            claimed = this.task;
                            lease = this.taskLease;
                            this.task = null;
                        }
                        WorkerResult completion;
                        try {
                            completion = switch (claimed) {
                                case SectionWorkerTask section -> this.section(section);
                                case IndexWorkerTask index -> this.index(index);
                                case EmptyWorkerTask empty -> new WorkerGeometry(empty.ticket(),
                                        BuiltSection.emptyWithChildren(empty.ticket().key(),
                                                empty.ticket().demandRevision(), empty.children()),
                                        System.nanoTime(), true, 0);
                                case BootstrapTask bootstrap -> {
                                    var store = new RegionalMetadataStore(bootstrap.root());
                                    RegionalProtocol.Hash32 hint;
                                    try { hint = store.world(bootstrap.server(), bootstrap.dimension()); }
                                    catch (IOException invalid) { hint = null; }
                                    yield new WorkerBootstrap(store, hint);
                                }
                                case OpenWorldTask world -> {
                                    var cache = new RegionalCache(metadata.namespace(world.world(), dimension),
                                            world.world(), metadata.budget);
                                    yield new WorkerWorld(world.view(), cache);
                                }
                                case LoadMetadataTask load -> this.loadMetadata(load);
                                case ProbeCatalogTask probe -> {
                                    CatalogCodec.Catalog decoded = null;
                                    try {
                                        byte[] bytes = metadata.readCatalog(probe.world(), dimension, probe.fingerprint());
                                        if (bytes != null) decoded = CatalogCodec.decode(bytes);
                                    } catch (IOException invalid) { /* A broken saved catalog is a miss. */ }
                                    yield new WorkerCatalogProbe(probe, decoded);
                                }
                                case SaveMetadataTask save -> {
                                    metadata.saveRegion(save.world(), dimension, (int) save.region(),
                                            (int) (save.region() >>> 32), save.message(), save.stamp(), save.current());
                                    yield new WorkerSaved();
                                }
                                case CatalogWorkerTask catalog -> {
                                    if (!hash32(catalog.message().canonical()).equals(catalog.message().fingerprint())) {
                                        throw new IOException("regional catalog fingerprint mismatch");
                                    }
                                    CatalogCodec.Catalog decoded = CatalogCodec.decode(catalog.message().canonical());
                                    try { if (metadata != null) metadata.saveCatalog(catalog.world(), dimension, catalog.message(), catalog.stamp(),
                                            () -> open.get() && viewRevision == catalog.view()); }
                                    catch (IOException cacheFailure) { /* Metadata persistence is optional. */ }
                                    yield new WorkerCatalog(catalog, decoded);
                                }
                                case AssociationTask association -> {
                                    metadata.associate(serverKey, dimension, association.world(), association.stamp(),
                                            () -> open.get() && viewRevision == association.view());
                                    yield new WorkerSaved();
                                }
                            };
                        } catch (Throwable failure) {
                            completion = new WorkerFailure(claimed, this.index, failure);
                        }
                        this.resource.complete(lease, completion);
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
                    try { compressed = task.cache() == null ? null : task.cache().get(task.index(), task.ordinal()); }
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
                        try { if (task.cache() != null) task.cache().put(task.index(), task.ordinal(), compressed); }
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
                    if (task.cache() != null) task.cache().quarantine(task.index(), task.ordinal());
                    return new WorkerMiss(task.ticket(), true);
                }
            }

            private WorkerResult index(IndexWorkerTask task) throws Exception {
                return new WorkerIndex(task, this.decodeIndex(task.message()));
            }

            private RegionalProtocol.RegionIndex decodeIndex(RegionalProtocol.RegionMessage message) throws Exception {
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
                return index;
            }

            private WorkerResult loadMetadata(LoadMetadataTask task) {
                try {
                    var saved = metadata.region(task.world(), dimension, (int) task.region(), (int) (task.region() >>> 32));
                    if (saved != null && !saved.absent()) {
                        var message = saved.message();
                        if (message.catalogFingerprint().equals(rejectedCatalog)) {
                            return new WorkerMetadata(task, null, null, null, null);
                        }
                        var index = this.decodeIndex(message);
                        var reference = savedMappings.get(message.catalogFingerprint());
                        var binding = reference == null ? null : reference.get();
                        if (binding != null) return new WorkerMetadata(task, index, binding, binding.fingerprint(), null);
                        byte[] canonical = metadata.readCatalog(task.world(), dimension, message.catalogFingerprint());
                        if (canonical != null) return new WorkerMetadata(task, index, null,
                                message.catalogFingerprint(), CatalogCodec.decode(canonical));
                    }
                } catch (Exception invalid) { /* Corrupt/missing metadata is a local miss. */ }
                return new WorkerMetadata(task, null, null, null, null);
            }

            synchronized void close() {
                this.resource.close();
                this.task = null;
                this.notifyAll();
                this.workerThread.interrupt();
            }
        }

        Session(long id, String dimension, VoxyRenderSystem renderer) {
            this(id, dimension, renderer, renderer.regionalSectionPublisher(),
                    renderer.regionalSectionMesher(), Math.max(2, Math.min(16,
                            Runtime.getRuntime().availableProcessors() - 2)));
            Minecraft minecraft = Minecraft.getInstance();
            var listener = minecraft.getConnection();
            this.connector = () -> QuicEndpointDiscovery.connect(listener);
            this.cacheRoot = minecraft.gameDirectory.toPath().resolve(".voxy").resolve("regional");
            var server = minecraft.getCurrentServer();
            // Integrated worlds without a persistent logical identity do not use optimistic lookup.
            this.serverKey = server == null ? null : server.ip;
        }

        Session(long id, String dimension, VoxyRenderSystem renderer,
                VoxyRenderSystem.SectionPublisher publisher, SectionMesher mesher, int workers) {
            this.id = id;
            this.demands = new SectionDemandTable<>(
                    HierarchicalOcclusionTraverser.DETAIL_BUCKET_COUNT, id);
            this.dimension = dimension;
            this.renderer = renderer;
            this.publisher = publisher;
            this.mesher = mesher;
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
            ClientLodDebug.startupEvent(this, "start", 0);
            this.publisher.setProgressListener(this.rendererWake);
            this.metadataWorker.start();
            if (this.cacheRoot != null) this.metadataWorker.assign(new BootstrapTask(this.cacheRoot, this.serverKey, this.dimension));
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
                    try {
                        this.connect();
                        this.drainWorkers();
                        this.drainNetworkReplies();
                        if (this.quic != null) this.drainControls();
                        this.drainEvents();
                        this.drainDemand();
                        this.processMetadata();
                        this.processRegions();
                        this.pollPublications();
                        this.processStages();
                        if (this.quic != null && !this.quic.isOpen()) {
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
            if (!ClientLodDebug.connectionAllowed()) return;
            if (this.quic != null || this.connector == null) return;
            if (this.connectionAttempt == null) {
                if (System.nanoTime() - this.nextConnectionAttempt < 0) return;
                this.connectionAttempt = new RegionalConnectionAttempt(this.connector);
            }
            var outcome = this.connectionAttempt.poll();
            if (outcome == null) return;
            this.connectionAttempt.close();
            this.connectionAttempt = null;
            this.nextConnectionAttempt = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            if (outcome.failure() != null) {
                this.lastConnectionFailure = outcome.failure();
                return;
            }
            RegionalQuicClient connected = outcome.connection();
            if (!this.open.get()) { connected.close(); return; }
            try {
                connected.setActivityListener(this::signal);
                connected.hello(this.dimension);
            } catch (Throwable failure) {
                connected.close();
                if (failure instanceof IOException io) throw io;
                throw new IOException("could not initialize regional QUIC", failure);
            }
            this.quic = connected;
            this.helloAccepted = false;
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
            this.helloAccepted = false;
            this.nextConnectionAttempt = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            if (previous != null) previous.close();
            this.pendingControl = null;
            this.catalogRequested = false;
            this.regionReleases.clear();
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
                region.validated = false;
                region.retryAfter = 0;
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
                    case RegionalProtocol.RegionUnavailable unavailable -> this.acceptRegionUnavailable(unavailable);
                    case RegionalProtocol.CatalogMessage catalog -> {
                        if (!this.acceptCatalog(catalog)) {
                            this.pendingControl = catalog;
                            return;
                        }
                    }
                    case RegionalProtocol.RegionChanged changed -> this.regionChanged(changed);
                    case RegionalProtocol.ServerError error -> throw new IOException(
                            "regional server error " + error.code() + ": " + error.message());
                    case RegionalProtocol.ServerShutdown shutdown -> throw new IOException(
                            "regional server shutdown: " + shutdown.message());
                }
            }
        }

        void acceptHello(RegionalProtocol.ServerHello hello) throws IOException {
            this.helloAccepted = true;
            ClientLodDebug.startupEvent(this, "hello", 0);
            if (!hello.worldIdentity().equals(this.worldIdentity)) this.changeWorld(hello.worldIdentity());
            this.associationPending = true;
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
            if (fingerprint.equals(this.rejectedCatalog)) return;
            var existing = this.mapping(fingerprint);
            if (existing != null) {
                this.catalogFingerprint = existing.fingerprint();
                this.mappings = existing.mappings();
                this.currentCatalog = existing;
                this.applyPendingIndexes(existing);
                return;
            }
            // Probe the exact saved catalog before requesting it, without blocking local jobs.
            if (this.cacheRoot != null && !this.metadataUnavailable
                    && (this.metadata == null || !fingerprint.equals(this.catalogProbed))) return;
            if (this.quic != null && this.helloAccepted && !this.catalogRequested && this.quic.requestCatalog()) {
                this.catalogRequested = true;
                this.catalogRequestedRevision = this.catalogRequirementRevision;
            }
        }

        boolean acceptCatalog(RegionalProtocol.CatalogMessage message) {
            if (!this.metadataWorker.idle()) return false;
            ClientLodDebug.startupEvent(this, "metadata", message.canonical().length);
            return this.metadataWorker.assign(new CatalogWorkerTask(this.viewRevision, this.connectionEpoch,
                    this.catalogRequirementRevision, this.worldIdentity,
                    message, this.metadata == null ? 0 : this.metadata.budget.stamp())) != null;
        }

        boolean acceptRegion(RegionalProtocol.RegionMessage message) throws IOException {
            long region = regionKey(message.regionX(), message.regionZ());
            SectionDemandTable.RegionDemand state = this.demands.region(region);
            if (state == null || !state.subscribed) return true;
            long expected = state.announcedGeneration;
            if (expected != 0
                    && Long.compareUnsigned(message.generation(), expected) < 0) {
                state.requested = false;
                state.validated = false;
                this.queueRegion(region);
                return true;
            }
            this.ensureCatalog(message.catalogFingerprint());
            WorkerSlot worker = this.idleWorker(state.coverageUsers > 0);
            if (worker == null) return false;
            // Receiving a record is not validation. Keep requests parked until its owned
            // decoder verifies the index; the installed index may still be provisional.
            state.requested = true;
            state.validated = false;
            state.localTried = true;
            state.announcedGeneration = message.generation();
            state.absent = false;
            long revision = ++state.metadataRevision;
            WorkerResource.Lease lease = worker.assign(new IndexWorkerTask(this.id,
                    this.connectionEpoch, this.viewRevision, revision, region, message.generation(), worker.index, message));
            if (lease == null) return false;
            ClientLodDebug.startupEvent(this, "metadata", message.compressed().length);
            state.resourceSlot = worker.index;
            state.resourceLease = lease;
            return true;
        }

        void acceptRegionUnavailable(RegionalProtocol.RegionUnavailable message) {
            long region = regionKey(message.regionX(), message.regionZ());
            SectionDemandTable.RegionDemand state = this.demands.region(region);
            if (state == null || !state.subscribed) return;
            state.requested = false;
            state.validated = message.confirmedAbsent();
            if (!message.confirmedAbsent()) {
                state.retryAfter = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                this.queueRegion(region);
                return;
            }
            this.invalidateSavedRegion(state);
            state.announcedGeneration = 0;
            state.installedGeneration = 0;
            state.absent = true;
            state.index = null;
            state.catalog = null;
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
            state.validated = false;
            state.retryAfter = 0;
            state.metadataRevision++;
            state.pendingIndex = null;
            state.pendingCatalog = null;
            state.absent = false;
            // The arriving index binds new work. Already-owned operations finish with their
            // exact revision and keep the old surface available until a replacement is ready.
            if (generation == 0) {
                state.index = null;
                state.catalog = null;
                state.installedGeneration = 0;
                this.invalidateSavedRegion(state);
                state.validated = true;
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
                        CatalogTask task = ready.task();
                        if (task.view() == this.viewRevision && this.open.get()) {
                            if (ready.binding() != null) {
                                this.savedMappings.entrySet().removeIf(entry -> entry.getValue().get() == null);
                                this.savedMappings.put(ready.binding().fingerprint(), new java.lang.ref.WeakReference<>(ready.binding()));
                            } else {
                                this.rejectedCatalog = task.fingerprint();
                                Logger.warn("Cannot map regional catalog; retaining other cached terrain", ready.failure());
                            }
                            task.complete().accept(ready.binding());
                        }
                        if (this.pendingCatalogTask == task) {
                            this.pendingCatalogTask = null;
                            this.pendingCatalogSubmitted = false;
                        }
                        this.metadataWorker.releaseCompletion(task.lease());
                    }
                    case Coarsened result -> {
                        if (result.view == this.viewRevision) this.finishCoarsening(result.parent, true);
                    }
                    case CoarsenFailed failed -> {
                        if (failed.view != this.viewRevision) continue;
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
                    case SessionObservation request -> request.receiver.accept(this);
                }
            }
        }

        PipelineSnapshot pipelineSnapshot() {
            long downloading = 0, cacheReading = 0, decoding = 0, meshing = 0;
            long ready = 0, publishing = 0;
            long waitRegion = 0, sourceReady = 0, networkOwned = 0, workerOwned = 0;
            long waitModels = 0, rendererOwned = 0;
            long[] blocked = new long[AsyncNodeManager.RegionalAllocationStatus.values().length];
            for (Demand demand : this.demands.values()) {
                if (demand.blockedReason != null) blocked[demand.blockedReason.ordinal()]++;
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
                    switch (worker.resource.state()) {
                        case IDLE -> idleWorkers++;
                        case RUNNING -> runningWorkers++;
                        case COMPLETED -> {
                            completedWorkers++;
                            if (worker.resource.pendingResult() instanceof WorkerGeometry geometry
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
            var progress = this.publisher.progress();
            long coverageReplies = 0, refinementReplies = 0;
            for (NetworkReply reply : this.networkReplies) {
                Demand demand = this.replyDemand(reply);
                if (demand != null) {
                    if (demand.coverage) coverageReplies++;
                    else refinementReplies++;
                }
            }
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
                    this.renderer.regionalGeometrySectionCount(), progress.handoff(), progress.occupied() ? 1 : 0,
                    this.publicationOutcomes[VoxyRenderSystem.UploadStatus.ACTIVATED.ordinal()],
                    this.publicationOutcomes[VoxyRenderSystem.UploadStatus.RETURNED.ordinal()],
                    this.publicationOutcomes[VoxyRenderSystem.UploadStatus.CANCELLED.ordinal()],
                    this.publicationOutcomes[VoxyRenderSystem.UploadStatus.FAILED.ordinal()],
                    runningWorkers + completedWorkers, coverageReplies, refinementReplies,
                    blocked[AsyncNodeManager.RegionalAllocationStatus.NO_CONTIGUOUS_GEOMETRY_SPACE.ordinal()],
                    blocked[AsyncNodeManager.RegionalAllocationStatus.NO_SECTION_ID.ordinal()],
                    blocked[AsyncNodeManager.RegionalAllocationStatus.TOPOLOGY_NOT_READY.ordinal()],
                    blocked[AsyncNodeManager.RegionalAllocationStatus.STALE.ordinal()],
                    blocked[AsyncNodeManager.RegionalAllocationStatus.IMPOSSIBLE.ordinal()],
                    progress.topology(), progress.allocation(), progress.sectionIds(), this.handoffBusy, this.dormancyTransitions, this.wakes, this.instantWakes, this.capEvictions + this.admissionEvictions);
        }

        void drainDemand() {
            if (this.resetRequested.getAndSet(false)) {
                for (long key : List.copyOf(this.demands.keySet())) this.retireDemand(key);
                this.dormantRoots.clear();
                this.dormantGeometryBytes = 0;
                this.demands.clear();
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
            // Retirement can synchronously remove several of these keys through coarsen().
            for (Long blockedKey : List.copyOf(this.rendererBlocked)) {
                Demand blocked = this.demands.get(blockedKey);
                if (blocked != null && blocked.blockedReason != null
                        && this.rendererBlocked.contains(blockedKey)) this.requestBlockedRetirement(blocked);
            }
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
                long view = this.viewRevision;
                this.publisher.coarsen(parent,
                        () -> this.putEvent(new Coarsened(parent, view)),
                        failure -> this.putEvent(new CoarsenFailed(parent, view, failure)));
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
            demand.pendingCatalog = null;
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
            while (true) {
                if (this.coarseningRoots.contains(key)) return true;
                if (SectionKey.level(key) == SectionKey.MAX_LOD_LAYER) return false;
                key = parent(key);
            }
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
            AsyncNodeManager.PublicationProgress now = this.publisher.progress();
            for (long key : List.copyOf(this.rendererBlocked)) {
                Demand demand = this.demands.get(key);
                if (demand == null || demand.blockedReason == null) {
                    this.rendererBlocked.remove(key);
                    continue;
                }
                if (!RendererWait.progressed(demand.blockedReason, demand.blockedAt, now)) continue;
                if (demand.blockedReason == VoxyRenderSystem.AllocationStatus.STALE) {
                    if (!hasTop(topAncestor(key))) continue;
                    this.rendererBlocked.remove(key);
                    demand.blockedReason = null;
                    if (demand.index != null) this.queueBound(demand);
                    else this.ensureRegion(key);
                    continue;
                }
                demand.blockedReason = null;
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
            var state = this.demands.region(regionKey(index.regionX(), index.regionZ()));
            this.installIndex(index, state != null && state.catalog != null ? state.catalog
                    : new RegionalSectionCodec.BoundCatalog(this.catalogFingerprint, this.mappings), false);
        }

        void installIndex(RegionalProtocol.RegionIndex index, RegionalSectionCodec.BoundCatalog catalog, boolean local) {
            long region = regionKey(index.regionX(), index.regionZ());
            SectionDemandTable.RegionDemand state = this.demands.region(region);
            if (state == null || !local && state.announcedGeneration != index.generation()) return;
            state.index = index;
            state.catalog = catalog;
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
                demand.pendingCatalog = null;
                demand.pendingOrdinal = -1;
                demand.index = null;
                demand.catalog = null;
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
            var write = this.metadataWrites.get(region);
            if (state != null && state.users == 0 && write != null && write.message() != null) {
                this.metadataWrites.remove(region);
            }
            if (state == null || !state.subscribed) return;
            state.subscribed = false;
            if (this.quic == null) return;
            this.regionReleases.add(region);
        }

        void bind(Demand demand, RegionalProtocol.RegionIndex index, int ordinal) {
            var state = this.demands.region(demand.regionKey);
            var catalog = state != null && state.catalog != null ? state.catalog
                    : new RegionalSectionCodec.BoundCatalog(this.catalogFingerprint, this.mappings);
            this.bind(demand, index, ordinal, catalog);
        }

        void bind(Demand demand, RegionalProtocol.RegionIndex index, int ordinal,
                  RegionalSectionCodec.BoundCatalog catalog) {
            if (ordinal < 0 || !index.isPresent(ordinal)) return;
            if (demand.workLease != null && demand.completedGeometry == null
                    || demand.candidate == SectionDemandTable.CandidateState.RENDERER_OWNED) {
                demand.pendingIndex = index;
                demand.pendingCatalog = catalog;
                demand.pendingOrdinal = ordinal;
                return;
            }
            if (demand.index != null && demand.ordinal >= 0 && (demand.completedGeometry != null || demand.installed
                    && demand.candidate == SectionDemandTable.CandidateState.NONE
                    || demand.blockedReason == VoxyRenderSystem.AllocationStatus.IMPOSSIBLE)
                    && (demand.catalog == null ? this.catalogFingerprint : demand.catalog.fingerprint())
                            .equals(catalog.fingerprint())
                    && demand.index.sectionFingerprint(demand.ordinal)
                            .equals(index.sectionFingerprint(ordinal))
                    && demand.index.childMask(demand.ordinal) == index.childMask(ordinal)) {
                demand.index = index;
                demand.catalog = catalog;
                demand.ordinal = ordinal;
                demand.regionGeneration = index.generation();
                return;
            }
            demand.pendingIndex = null;
            demand.pendingCatalog = null;
            demand.pendingOrdinal = -1;
            this.rendererBlocked.remove(demand.key);
            if (demand.installed) ClientLodDebug.startupEvent(this, "replacement", 0);
            demand.blockedReason = null;
            this.demands.unlinkReady(demand);
            this.discardCompletedGeometry(demand);
            this.demands.revise(demand);
            demand.index = index;
            demand.catalog = catalog;
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
            if (this.quic == null || !this.helloAccepted) return;
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
            if (this.worldIdentity == null || this.quic == null || !this.helloAccepted) return;
            if (this.requiredCatalogFingerprint != null) {
                this.ensureCatalog(this.requiredCatalogFingerprint);
            }
            // Releases precede new requests, so camera churn cannot accumulate an outgoing
            // backlog beyond the subscriptions already issued on this connection.
            var releases = this.regionReleases.iterator();
            while (releases.hasNext()) {
                long key = releases.next();
                if (!this.quic.releaseRegion((int) key, (int) (key >>> 32))) return;
                releases.remove();
            }
            SectionDemandTable.RegionDemand region;
            long now = System.nanoTime();
            while ((region = this.demands.pollRegion(candidate -> !candidate.requested
                    && !candidate.validated && now - candidate.retryAfter >= 0)) != null) {
                if (region.users == 0) continue;
                if (!this.quic.requestRegion((int) region.key, (int) (region.key >>> 32))) {
                    this.demands.readyRegion(region);
                    return;
                }
                region.requested = true;
                region.subscribed = true;
                this.demands.readyRegion(region);
            }
        }

        void changeWorld(RegionalProtocol.Hash32 world) {
            ++this.viewRevision;
            if (this.worldIdentity != null) {
                ClientLodDebug.startupEvent(this, "worldCorrection", 0);
                for (long key : List.copyOf(this.demands.keySet())) this.retireDemand(key);
            }
            if (this.cache != null) this.cache.close();
            this.cache = null;
            this.cacheOpened = false;
            this.worldIdentity = world;
            this.catalogFingerprint = RegionalProtocol.Hash32.ZERO;
            this.mappings = null;
            this.currentCatalog = null;
            this.savedMappings.clear();
            this.catalogProbed = null;
            this.coarseningRoots.clear();
            this.pendingDormantEvictions.clear();
            this.pendingDormantEvictionBytes = 0;
            this.rejectedCatalog = null;
            this.metadataWrites.clear();
            for (SectionDemandTable.RegionDemand region : this.demands.regions()) {
                region.index = null;
                region.catalog = null;
                region.pendingIndex = null;
                region.pendingCatalog = null;
                region.installedGeneration = 0;
                region.announcedGeneration = 0;
                region.metadataRevision++;
                region.localTried = false;
                region.validated = false;
                region.absent = false;
                region.requested = false;
                region.retryAfter = 0;
                this.queueRegion(region.key);
            }
        }

        RegionalSectionCodec.BoundCatalog mapping(RegionalProtocol.Hash32 fingerprint) {
            var reference = this.savedMappings.get(fingerprint);
            return reference == null ? null : reference.get();
        }

        void processMetadata() {
            if (this.pendingCatalogTask != null) {
                if (!this.pendingCatalogSubmitted) {
                    this.pendingCatalogSubmitted = CATALOG_TASK.compareAndSet(null, this.pendingCatalogTask);
                }
                return;
            }
            if (this.metadata == null || this.worldIdentity == null || !this.metadataWorker.idle()) return;
            if (!this.cacheOpened) {
                this.cacheOpened = true;
                this.metadataWorker.assign(new OpenWorldTask(this.viewRevision, this.worldIdentity));
                return;
            }
            if (this.associationPending) {
                this.associationPending = false;
                this.metadataWorker.assign(new AssociationTask(this.viewRevision, this.worldIdentity, this.metadata.budget.stamp()));
                return;
            }
            if (this.helloAccepted && !this.requiredCatalogFingerprint.equals(this.catalogProbed)
                    && this.mapping(this.requiredCatalogFingerprint) == null) {
                this.metadataWorker.assign(new ProbeCatalogTask(this.viewRevision, this.worldIdentity,
                        this.requiredCatalogFingerprint));
                return;
            }
            // Persist accepted updates promptly; otherwise a long cold-cache sweep can starve
            // deletion tombstones and the metadata needed by the next launch.
            var writes = this.metadataWrites.entrySet().iterator();
            while (writes.hasNext()) {
                var write = writes.next().getValue();
                if (write.message() != null && this.mapping(write.message().catalogFingerprint()) == null) continue;
                writes.remove();
                this.metadataWorker.assign(write);
                return;
            }
            var local = this.demands.pollRegion(region -> !region.localTried);
            if (local != null) {
                local.localTried = true;
                this.metadataWorker.assign(new LoadMetadataTask(this.viewRevision, local.key,
                        local.metadataRevision, this.worldIdentity));
                this.demands.readyRegion(local);
                return;
            }
        }

        void invalidateSavedRegion(SectionDemandTable.RegionDemand state) {
            state.metadataRevision++;
            state.localTried = true;
            state.pendingIndex = null;
            state.pendingCatalog = null;
            ClientLodDebug.startupEvent(this, "invalidation", 0);
            this.saveRegion(state, null);
        }

        void saveRegion(SectionDemandTable.RegionDemand state, RegionalProtocol.RegionMessage message) {
            if (this.metadata == null) return;
            long view = this.viewRevision, revision = state.metadataRevision;
            this.metadataWrites.put(state.key, new SaveMetadataTask(view, state.key, this.worldIdentity,
                    message, this.metadata.budget.stamp(),
                    () -> this.open.get() && this.viewRevision == view && state.metadataRevision == revision));
        }

        void applyPendingIndexes(RegionalSectionCodec.BoundCatalog binding) {
            for (var state : List.copyOf(this.demands.regions())) {
                if (state.pendingIndex == null || !binding.fingerprint().equals(state.pendingCatalog)) continue;
                var index = state.pendingIndex;
                state.pendingIndex = null;
                state.pendingCatalog = null;
                this.installIndex(index, binding, false);
            }
        }

        void applyLocalIndex(WorkerMetadata ready, RegionalSectionCodec.BoundCatalog binding) {
            if (binding == null) return;
            var task = ready.task();
            var state = this.demands.region(task.region());
            if (task.view() != this.viewRevision || state == null || state.validated
                    || state.metadataRevision != task.revision() || ready.index() == null) return;
            ClientLodDebug.startupEvent(this, "localView", 0);
            this.installIndex(ready.index(), binding, true);
        }

        WorkerSlot idleWorker() {
            for (WorkerSlot worker : this.sectionWorkers) if (worker.idle()) return worker;
            return null;
        }

        WorkerSlot idleWorker(boolean coverage) { return this.idleWorker(coverage, null); }

        WorkerSlot idleWorker(Demand prerequisite) {
            return this.idleWorker(prerequisite.coverage, prerequisite);
        }

        WorkerSlot idleWorker(boolean coverage, Demand prerequisite) {
            WorkerSlot idle = this.idleWorker();
            if (idle != null) return idle;
            Demand selected = null;
            for (WorkerSlot holder : this.sectionWorkers) {
                if (!holder.sectionOperation) continue;
                Demand demand = this.demands.get(holder.operationKey);
                if (demand == null || !holder.resource.matches(demand.workLease)) continue;
                boolean dependency = prerequisite != null
                        && demand.blockedReason == VoxyRenderSystem.AllocationStatus.TOPOLOGY_NOT_READY
                        && (demand.prerequisite == prerequisite.key
                                || contains(prerequisite.key, demand.key));
                if ((!coverage && !dependency) || demand.coverage || demand.completedGeometry == null
                        || demand.workLease == null
                        || demand.candidate
                        != SectionDemandTable.CandidateState.WORKER_OWNED) continue;
                if (selected == null || demand.pixelBucket < selected.pixelBucket) {
                    selected = demand;
                }
            }
            if (selected == null) return null;
            WorkerSlot reclaimed = this.sectionWorkers[selected.workLease.slot()];
            this.rendererBlocked.remove(selected.key);
            selected.blockedReason = null;
            this.discardCompletedGeometry(selected);
            selected.candidate = SectionDemandTable.CandidateState.READY_SOURCE;
            this.demands.ready(selected, SectionDemandTable.ReadyKind.SOURCE);
            return reclaimed.idle() ? reclaimed : null;
        }

        void drainWorkers() throws IOException {
            this.drainWorker(this.metadataWorker);
            for (WorkerSlot worker : this.sectionWorkers) {
                this.drainWorker(worker);
            }
        }

        void drainWorker(WorkerSlot worker) throws IOException {
                WorkerResource.Completion<WorkerResult> completion = worker.resource.claim();
                if (completion == null) return;
                WorkerResource.Lease lease = completion.lease();
                WorkerResult result = completion.value();
                switch (result) {
                    case WorkerBootstrap boot -> {
                        this.metadata = boot.metadata();
                        if (this.worldIdentity == null && boot.hint() != null) this.changeWorld(boot.hint());
                        worker.releaseCompletion(lease);
                    }
                    case WorkerWorld world -> {
                        if (world.view() == this.viewRevision) this.cache = world.cache();
                        else world.cache().close();
                        worker.releaseCompletion(lease);
                    }
                    case WorkerSaved ignored -> worker.releaseCompletion(lease);
                    case WorkerCatalogProbe probe -> {
                        if (probe.task().view() != this.viewRevision) {
                            worker.releaseCompletion(lease);
                        } else if (probe.catalog() == null) {
                            this.catalogProbed = probe.task().fingerprint();
                            worker.releaseCompletion(lease);
                        } else {
                            this.pendingCatalogTask = new CatalogTask(this, probe.catalog(), probe.task().fingerprint(),
                                    probe.task().view(), lease, binding -> {
                                this.catalogProbed = probe.task().fingerprint();
                                if (binding != null) {
                                    if (binding.fingerprint().equals(this.requiredCatalogFingerprint)) {
                                        this.catalogFingerprint = binding.fingerprint();
                                        this.mappings = binding.mappings();
                                        this.currentCatalog = binding;
                                    }
                                    this.applyPendingIndexes(binding);
                                }
                            });
                        }
                    }
                    case WorkerMetadata saved -> {
                        if (saved.catalog() == null || saved.task().view() != this.viewRevision) {
                            if (saved.binding() != null) this.applyLocalIndex(saved, saved.binding());
                            worker.releaseCompletion(lease);
                        } else {
                            this.pendingCatalogTask = new CatalogTask(this, saved.catalog(), saved.fingerprint(),
                                    saved.task().view(), lease, binding -> this.applyLocalIndex(saved, binding));
                        }
                    }
                    case WorkerCatalog saved -> {
                        var task = saved.task();
                        if (task.view() != this.viewRevision) {
                            worker.releaseCompletion(lease);
                            break;
                        }
                        this.pendingCatalogTask = new CatalogTask(this, saved.catalog(), task.message().fingerprint(),
                                task.view(), lease, binding -> {
                            if (task.connection() == this.connectionEpoch) this.catalogRequested = false;
                            if (binding == null) return;
                            if (task.requirement() == this.catalogRequirementRevision) {
                                this.requiredCatalogFingerprint = binding.fingerprint();
                            }
                            if (binding.fingerprint().equals(this.requiredCatalogFingerprint)) {
                                this.catalogFingerprint = binding.fingerprint();
                                this.mappings = binding.mappings();
                                this.currentCatalog = binding;
                            }
                            this.applyPendingIndexes(binding);
                            for (var state : this.demands.regions()) {
                                if (state.pendingCatalog != null && this.mapping(state.pendingCatalog) == null) {
                                    state.validated = false;
                                    this.queueRegion(state.key);
                                }
                            }
                        });
                    }
                    case WorkerIndex ready -> {
                        var task = ready.task();
                        SectionDemandTable.RegionDemand region = this.demands.region(task.region());
                        if (task.session() == this.id && task.view() == this.viewRevision
                                && task.connection() == this.connectionEpoch
                                && task.slot() == worker.index
                                && region != null && lease.equals(region.resourceLease)
                                && region.metadataRevision == task.revision()
                                && region.announcedGeneration == task.generation()) {
                            region.resourceSlot = -1;
                            region.resourceLease = null;
                            region.requested = false;
                            region.validated = true;
                            ClientLodDebug.startupEvent(this, "validated", 0);
                            var binding = this.mapping(task.message().catalogFingerprint());
                            if (binding != null) this.installIndex(ready.index(), binding, false);
                            else {
                                region.pendingIndex = ready.index();
                                region.pendingCatalog = task.message().catalogFingerprint();
                            }
                            this.saveRegion(region, task.message());
                        } else if (region != null && lease.equals(region.resourceLease)) {
                            region.resourceSlot = -1;
                            region.resourceLease = null;
                            region.requested = false;
                            region.validated = false;
                            this.queueRegion(region.key);
                        }
                        worker.releaseCompletion(lease);
                    }
                    case WorkerMiss miss -> {
                        Demand demand = this.currentWorkerDemand(miss.ticket(), worker, lease);
                        if (demand != null) {
                            demand.workLease = null;
                            this.cacheReads++;
                            this.cacheMisses++;
                            this.waitForNetwork(demand);
                        } else {
                            this.finishStaleWorker(miss.ticket(), lease);
                        }
                        worker.releaseCompletion(lease);
                    }
                    case WorkerModels models -> {
                        Demand demand = this.currentWorkerDemand(models.ticket(), worker, lease);
                        if (demand != null) {
                            demand.workLease = null;
                            demand.waitingModels = models.blocks();
                            demand.candidate = SectionDemandTable.CandidateState.WAIT_MODELS;
                            this.waitingModels.add(demand.key);
                            this.recordWorkerSource(models.cacheHit(), models.compressedBytes(),
                                    false);
                        } else {
                            this.finishStaleWorker(models.ticket(), lease);
                        }
                        worker.releaseCompletion(lease);
                    }
                    case WorkerGeometry geometry -> {
                        Demand demand = this.currentWorkerDemand(geometry.ticket(), worker, lease);
                        if (demand == null) {
                            geometry.geometry().free();
                            this.finishStaleWorker(geometry.ticket(), lease);
                            worker.releaseCompletion(lease);
                            return;
                        }
                        this.recordWorkerSource(geometry.cacheHit(), geometry.compressedBytes(),
                                true);
                        // Once observed, the demand is the sole owner of the mesh buffer. The
                        // worker remains reserved until renderer admission completes, but must
                        // neither redeliver nor free a buffer that may already be uploading.
                        demand.workLease = lease;
                        this.completeGeometry(demand, geometry.geometry(),
                                geometry.completedNanos());
                        // The slot remains COMPLETED as the exact backpressure resource.
                    }
                    case WorkerFailure failed -> {
                        if (failed.task() instanceof BootstrapTask) this.metadataUnavailable = true;
                        if (failed.task() instanceof ProbeCatalogTask probe && probe.view() == this.viewRevision) {
                            this.catalogProbed = probe.fingerprint();
                        }
                        if (failed.task() instanceof CatalogWorkerTask catalog
                                && catalog.connection() == this.connectionEpoch) {
                            this.catalogRequested = false;
                        }
                        Demand currentDemand = null;
                        SectionDemandTable.RegionDemand currentRegion = null;
                        boolean stale = switch (failed.task()) {
                            case SectionWorkerTask section -> {
                                currentDemand = this.currentWorkerDemand(section.ticket(), worker, lease);
                                yield currentDemand == null;
                            }
                            case EmptyWorkerTask empty -> {
                                currentDemand = this.currentWorkerDemand(empty.ticket(), worker, lease);
                                yield currentDemand == null;
                            }
                            case IndexWorkerTask index -> {
                                currentRegion = this.demands.region(index.region());
                                yield index.session() != this.id
                                        || index.view() != this.viewRevision
                                        || index.connection() != this.connectionEpoch
                                        || currentRegion == null
                                        || !lease.equals(currentRegion.resourceLease)
                                        || currentRegion.announcedGeneration != index.generation();
                            }
                            default -> true;
                        };
                        if (failed.task() instanceof SectionWorkerTask section && stale) {
                            this.finishStaleWorker(section.ticket(), lease);
                        }
                        if (!stale && failed.task() instanceof EmptyWorkerTask) {
                            currentDemand.workLease = null;
                            currentDemand.candidate = SectionDemandTable.CandidateState.READY_SOURCE;
                            this.demands.ready(currentDemand, SectionDemandTable.ReadyKind.SOURCE);
                        } else if (!stale && failed.task() instanceof SectionWorkerTask section) {
                            currentDemand.workLease = null;
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
                        worker.releaseCompletion(lease);
                        if (!stale) Logger.warn("Regional worker " + failed.slot()
                                + " failed; its last-known-good geometry remains active",
                                failed.failure());
                    }
                }
        }

        boolean validated(Demand demand) {
            var region = this.demands.region(demand.regionKey);
            return this.helloAccepted && region != null && region.validated && region.subscribed
                    && region.pendingIndex == null && region.index == demand.index;
        }

        void waitForNetwork(Demand demand) {
            demand.candidate = this.validated(demand) ? SectionDemandTable.CandidateState.READY_SOURCE
                    : SectionDemandTable.CandidateState.WAIT_REGION;
            if (demand.candidate == SectionDemandTable.CandidateState.READY_SOURCE) {
                this.demands.ready(demand, SectionDemandTable.ReadyKind.NETWORK);
            }
        }

        Demand currentWorkerDemand(SectionDemandTable.Ticket ticket, WorkerSlot worker,
                                   WorkerResource.Lease lease) {
            if (!this.demands.current(ticket) || ticket.resourceSlot() != worker.index) return null;
            Demand demand = this.demands.get(ticket.key());
            return demand != null && lease.equals(demand.workLease)
                    && worker.resource.matches(lease) ? demand : null;
        }

        void finishStaleWorker(SectionDemandTable.Ticket ticket, WorkerResource.Lease lease) {
            Demand demand = this.demands.get(ticket.key());
            if (demand == null || !lease.equals(demand.workLease)) return;
            demand.workLease = null;
            if (demand.pendingIndex == null || demand.pendingOrdinal < 0) return;
            RegionalProtocol.RegionIndex index = demand.pendingIndex;
            var catalog = demand.pendingCatalog;
            int ordinal = demand.pendingOrdinal;
            demand.pendingIndex = null;
            demand.pendingCatalog = null;
            demand.pendingOrdinal = -1;
            this.bind(demand, index, ordinal, catalog);
        }

        void recordWorkerSource(boolean cacheHit, int compressedBytes, boolean meshed) {
            if (compressedBytes == 0) return; // Empty sections use a lease, but no I/O.
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
            if (result instanceof WorkerWorld world) world.cache().close();
        }

        void scheduleSourceWork() {
            int remaining = this.demands.readyCount(SectionDemandTable.ReadyKind.SOURCE);
            while (remaining-- > 0) {
                Demand demand = this.demands.poll(SectionDemandTable.ReadyKind.SOURCE);
                if (demand == null) return;
                if (demand.candidate != SectionDemandTable.CandidateState.READY_SOURCE) continue;
                if (demand.index.isEmpty(demand.ordinal)) {
                    if (!this.publishEmpty(demand)) {
                        this.demands.ready(demand, SectionDemandTable.ReadyKind.SOURCE);
                        return;
                    }
                    continue;
                }
                if (demand.catalog == null || demand.catalog.mappings() == null) {
                    this.demands.ready(demand, SectionDemandTable.ReadyKind.SOURCE);
                    continue;
                }
                WorkerSlot worker = this.idleWorker(demand);
                if (worker == null) {
                    this.demands.ready(demand, SectionDemandTable.ReadyKind.SOURCE);
                    return;
                }
                WorkerSource source = WorkerSource.CACHE;
                SectionDemandTable.Ticket ticket = demand.ticket(this.id, worker.index);
                SectionWorkerTask task = new SectionWorkerTask(ticket, demand.index,
                        demand.ordinal, source, null, demand.catalog.mappings(), this.cache);
                demand.workLease = worker.assign(task);
                this.demands.owned(demand, SectionDemandTable.CandidateState.WORKER_OWNED);
                if (demand.workLease == null) {
                    demand.workLease = null;
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
            if (!this.validated(first)) {
                first.candidate = SectionDemandTable.CandidateState.WAIT_REGION;
                return result;
            }
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
            Map<Long, SectionDemandTable.Ticket> tickets = new HashMap<>();
            for (Demand demand : selected) {
                if (demand.index != index) {
                    throw new IOException("regional request batch spans multiple indexes");
                }
                ordinals.add(demand.ordinal);
                tickets.put(demand.key, demand.ticket(this.id, -1));
            }
            RegionalProtocol.Lane lane = selected.getFirst().coverage
                    ? RegionalProtocol.Lane.COVERAGE : RegionalProtocol.Lane.REFINEMENT;
            boolean accepted = this.quic.requestSections(lane, epoch, index, ordinals,
                    new RegionalQuicClient.BatchReceiver() {
                        @Override public void reply(RegionalProtocol.SectionReply reply)
                                throws InterruptedException {
                            NetworkReply handoff = new NetworkReply(batchConnectionEpoch, reply,
                                    tickets.get(reply.key()));
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

        Demand replyDemand(NetworkReply handoff) {
            if (handoff.connectionEpoch != this.connectionEpoch || handoff.ticket == null
                    || !this.demands.current(handoff.ticket)) return null;
            Demand demand = this.demands.get(handoff.reply.key());
            return demand != null
                    && demand.candidate == SectionDemandTable.CandidateState.NETWORK_OWNED
                    && demand.index != null && demand.index.generation() == handoff.reply.generation()
                    && demand.ordinal == handoff.reply.ordinal() ? demand : null;
        }

        void finishReply(NetworkReply handoff) {
            this.networkReplies.remove(handoff);
            handoff.transferred();
        }

        void drainNetworkReplies() throws IOException {
            Set<NetworkReply> unadmittable = new HashSet<>();
            for (NetworkReply handoff : this.networkReplies) {
                if (this.replyDemand(handoff) == null) this.finishReply(handoff);
            }
            while (true) {
                NetworkReply handoff = ReplyAdmission.select(this.networkReplies, body -> {
                    Demand demand = this.replyDemand(body);
                    if (demand == null || unadmittable.contains(body)) return ReplyAdmission.INELIGIBLE;
                    if (body.reply.status() == RegionalProtocol.Status.DATA
                            && (demand.catalog == null || demand.catalog.mappings() == null)) return ReplyAdmission.INELIGIBLE;
                    return demand.coverage ? Integer.MAX_VALUE : demand.pixelBucket;
                });
                if (handoff == null) return;
                Demand demand = this.replyDemand(handoff);
                RegionalProtocol.SectionReply reply = handoff.reply;
                switch (reply.status()) {
                    case DATA -> {
                        if (reply.compressed().length != demand.index.compressedLength(demand.ordinal)) {
                            throw new IOException("regional section reply disagrees with its index");
                        }
                        WorkerSlot worker = this.idleWorker(demand);
                        if (worker == null) {
                            unadmittable.add(handoff);
                            continue;
                        }
                        SectionWorkerTask task = new SectionWorkerTask(demand.ticket(this.id, worker.index),
                                demand.index, demand.ordinal, WorkerSource.NETWORK,
                                reply.compressed(), demand.catalog.mappings(), this.cache);
                        demand.workLease = worker.assign(task);
                        if (demand.workLease == null) return;
                        this.demands.owned(demand, SectionDemandTable.CandidateState.WORKER_OWNED);
                    }
                    case EMPTY -> {
                        if (!demand.index.isEmpty(demand.ordinal)) throw new IOException("unexpected empty section");
                        if (!this.publishEmpty(demand)) {
                            unadmittable.add(handoff);
                            continue;
                        }
                    }
                    case STALE -> {
                        SectionDemandTable.RegionDemand state = this.demands.region(demand.regionKey);
                        if (state != null) {
                            state.index = null;
                            state.installedGeneration = 0;
                            state.requested = false;
                        }
                        demand.candidate = SectionDemandTable.CandidateState.WAIT_REGION;
                        this.queueRegion(demand.regionKey);
                    }
                    case ABSENT -> throw new IOException("indexed regional section became absent");
                }
                this.finishReply(handoff);
            }
        }

        boolean publishEmpty(Demand demand) {
            WorkerSlot worker = this.idleWorker(demand);
            if (worker == null) return false;
            demand.workLease = worker.assign(new EmptyWorkerTask(demand.ticket(this.id, worker.index),
                    (byte) demand.index.childMask(demand.ordinal)));
            if (demand.workLease == null) return false;
            this.demands.owned(demand, SectionDemandTable.CandidateState.WORKER_OWNED);
            return true;
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
            AsyncNodeManager.PublicationProgress progress = this.publisher.progress();
            if (progress.failure() != null) throw new IllegalStateException("renderer stopped", progress.failure());
            if (this.busyHandoff == progress.handoff()) return;
            this.busyHandoff = -1;
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
                                    && this.open.get()));
                }
                if (prepared.isEmpty()) return;
                for (PreparedPublication item : prepared) {
                    item.demand().completedGeometry = null;
                    this.demands.owned(item.demand(),
                            SectionDemandTable.CandidateState.RENDERER_OWNED);
                }
                AsyncNodeManager.PublicationProgress observed = this.publisher.progress();
                VoxyRenderSystem.SubmissionAttempt attempt;
                try {
                    attempt = this.publisher.tryPublishBatch(submissions);
                } catch (RuntimeException | Error failure) {
                    this.restorePrepared(prepared);
                    throw failure;
                }
                if (attempt.status() == VoxyRenderSystem.SubmissionStatus.BUSY) {
                    this.restorePrepared(prepared);
                    this.busyHandoff = observed.handoff();
                    this.handoffBusy++;
                    return;
                }
                for (int index = 0; index < prepared.size(); index++) {
                    PreparedPublication item = prepared.get(index);
                    Demand demand = item.demand();
                    this.transferGeometryAccounting(demand);
                    demand.previousPublication = item.previous();
                    demand.publication = attempt.publications().get(index);
                    this.publicationQueue.addLast(new PublicationRef(demand, item.revision(),
                            demand.publication, item.previous(), demand.workLease, demand.geometryBytes));
                }
            }
        }

        void restorePrepared(List<PreparedPublication> prepared) {
            for (PreparedPublication item : prepared) {
                Demand demand = item.demand();
                demand.completedGeometry = item.geometry();
                demand.candidate = SectionDemandTable.CandidateState.WORKER_OWNED;
                this.demands.ready(demand, SectionDemandTable.ReadyKind.RENDERER);
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
            WorkerResource.Lease lease = demand.workLease;
            // Renderer-owned buffers belong to the publication. Its detachable lease stays
            // with PublicationRef until real nonempty admission or a terminal outcome.
            if (geometry != null) {
                geometry.free();
                this.releaseRendererSlot(lease);
            }
            demand.workLease = null;
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

        }

        void releaseAllGeometryAccounting(Demand demand) {
            this.releaseGeometryAccounting(demand);
            this.releasePublishingGeometryAccounting(demand);
        }

        void pollPublications() {
            int remaining = this.publicationQueue.size();
            while (remaining-- > 0) {
                PublicationRef ref = this.publicationQueue.pollFirst();
                if (ref == null) break;
                Demand demand = this.demands.get(ref.demand().key);
                boolean current = demand == ref.demand() && demand.revision == ref.revision()
                        && demand.candidate == SectionDemandTable.CandidateState.RENDERER_OWNED
                        && demand.publication == ref.publication();
                if (!current) ref.publication().close();
                if (ref.bytes() > 0 && ref.publication().rendererAdmitted()) {
                    WorkerResource.Lease lease = this.detachPublicationLease(ref);
                    if (lease != null) {
                        this.releaseRendererSlot(lease);
                        ClientLodDebug.admissionReleased(this, ref.demand().meshCompletedNanos);
                    }
                }
                Optional<VoxyRenderSystem.UploadOutcome> outcome = ref.publication().takeUploadOutcome();
                if (outcome.isEmpty()) {
                    this.publicationQueue.addLast(ref);
                    continue;
                }
                VoxyRenderSystem.UploadOutcome result = outcome.orElseThrow();
                this.publicationOutcomes[result.status().ordinal()]++;
                this.publishingGeometryBytes -= ref.bytes();
                if (!current) {
                    if (result.block() != null) result.block().geometry().free();
                    this.releaseRendererSlot(this.detachPublicationLease(ref));
                    continue;
                }
                demand.publishingGeometryOwned = false;
                demand.previousPublication = null;
                switch (result.status()) {
                    case RETURNED -> {
                        demand.publication = ref.previous();
                        demand.completedGeometry = result.block().geometry();
                        demand.completedGeometryOwned = true;
                        this.completedGeometryBytes += ref.bytes();
                        demand.candidate = SectionDemandTable.CandidateState.WORKER_OWNED;
                        this.blockPublication(demand, result.block());
                    }
                    case FAILED, CANCELLED -> {
                        demand.publication = ref.previous();
                        this.releaseRendererSlot(this.detachPublicationLease(ref));
                        demand.candidate = SectionDemandTable.CandidateState.READY_SOURCE;
                        this.demands.ready(demand, SectionDemandTable.ReadyKind.SOURCE);
                        if (result.failure() != null) Logger.warn(
                                "Regional upload failed after rollback; retaining fallback", result.failure());
                    }
                    case ACTIVATED -> {
                        demand.candidate = SectionDemandTable.CandidateState.NONE;
                        this.setActiveGeometryBytes(demand, ref.bytes());
                        if (!demand.installed) { demand.installed = true; this.activeCount++; }
                        if (demand.coverage) this.missingCoverage.remove(demand.key);
                        this.activated++;
                        ClientLodDebug.startupEvent(this, this.helloAccepted ? "activation" : "localActivation", 0);
                        this.uploadedSections++;
                        this.releaseRendererSlot(this.detachPublicationLease(ref));
                    }
                }
                if (demand.pendingIndex != null && (demand.workLease == null
                        || demand.completedGeometry != null)) {
                    RegionalProtocol.RegionIndex index = demand.pendingIndex;
                    var catalog = demand.pendingCatalog;
                    int ordinal = demand.pendingOrdinal;
                    demand.pendingIndex = null;
                    demand.pendingCatalog = null;
                    demand.pendingOrdinal = -1;
                    this.bind(demand, index, ordinal, catalog);
                }
            }
            this.retryRendererBlocked();
        }

        void blockPublication(Demand demand, VoxyRenderSystem.AllocationBlock block) {
            demand.blockedReason = block.status();
            demand.blockedAt = block.observed();
            demand.prerequisite = block.prerequisite();
            demand.blockedRequiredBytes = block.requiredUnits() * 8L;
            switch (block.status()) {
                case NO_CONTIGUOUS_GEOMETRY_SPACE, NO_SECTION_ID -> {
                    this.rendererBlocked.add(demand.key);
                    this.requestBlockedRetirement(demand);
                }
                case TOPOLOGY_NOT_READY -> {
                    this.rendererBlocked.add(demand.key);
                    this.ensurePrerequisite(demand);
                }
                case STALE -> {
                    this.discardCompletedGeometry(demand);
                    demand.candidate = SectionDemandTable.CandidateState.WAIT_REGION;
                    this.rendererBlocked.add(demand.key);
                }
                case IMPOSSIBLE -> {
                    this.discardCompletedGeometry(demand);
                    demand.candidate = SectionDemandTable.CandidateState.NONE;
                    Logger.warn("Regional mesh cannot fit configured arena; retaining fallback: key="
                            + demand.key + " bytes=" + demand.blockedRequiredBytes);
                }
            }
        }

        void requestBlockedRetirement(Demand demand) {
            if (RendererWait.needsRetirement(demand.blockedReason,
                    !this.pendingDormantEvictions.isEmpty() || !this.coarseningRoots.isEmpty())) {
                this.evictDormant(demand.blockedReason == VoxyRenderSystem.AllocationStatus.NO_SECTION_ID
                        ? 1 : demand.blockedRequiredBytes, true);
            }
        }

        void ensurePrerequisite(Demand dependent) {
            if (dependent.prerequisite == dependent.key || !hasTop(topAncestor(dependent.key))) return;
            this.addDemand(dependent.prerequisite, dependent.pixelBucket);
            Demand prerequisite = this.demands.get(dependent.prerequisite);
            if (prerequisite == null) return;
            if (prerequisite.candidate == SectionDemandTable.CandidateState.NONE
                    && !prerequisite.installed && prerequisite.blockedReason == null
                    && prerequisite.index != null) this.queueBound(prerequisite);
        }

        void releaseRendererSlot(WorkerResource.Lease lease) {
            if (lease != null) this.sectionWorkers[lease.slot()].releaseCompletion(lease);
        }

        /** Owner-thread-only detach; asynchronous cleanup receives only the exact identity. */
        private WorkerResource.Lease detachPublicationLease(PublicationRef ref) {
            WorkerResource.Lease lease = ref.lease;
            ref.lease = null;
            if (lease != null && this.demands.get(ref.demand().key) == ref.demand()
                    && lease.equals(ref.demand().workLease)) {
                ref.demand().workLease = null;
            }
            return lease;
        }

        RegionalProtocol.RegionIndex indexFor(long key) {
            SectionDemandTable.RegionDemand region = this.demands.region(regionFor(key));
            return region == null ? null : (RegionalProtocol.RegionIndex) region.index;
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
                switch (worker.resource.state()) {
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
                    + ClientLodDebug.startupSnapshot(this)
                    + " connectionFailure=" + String.valueOf(this.lastConnectionFailure)
                    + " failure=" + String.valueOf(this.failure);
        }

        @Override public void close() {
            if (!this.open.getAndSet(false)) return;
            ++this.viewRevision;
            if (this.connectionAttempt != null) this.connectionAttempt.close();
            signal();
            this.thread.interrupt();
            if (this.quic != null) this.quic.close();
        }

        void release() {
            if (this.connectionAttempt != null) this.connectionAttempt.close();
            this.metadataWorker.close();
            if (this.pendingCatalogTask != null) CATALOG_TASK.compareAndSet(this.pendingCatalogTask, null);
            this.metadataWrites.clear();
            if (this.quic != null) this.quic.close();
            NetworkReply reply;
            while ((reply = this.networkReplies.poll()) != null) reply.transferred();
            this.publisher.clearProgressListener(this.rendererWake);
            for (PublicationRef ref : this.publicationQueue) {
                WorkerResource.Lease lease = this.detachPublicationLease(ref);
                ref.publication().abandon(() -> this.releaseRendererSlot(lease));
            }
            this.publicationQueue.clear();
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
        }
    }

    private record DetailEvent(long key, int action, int epoch) {}
    private record ReadyPublication(Demand demand, long revision) {}
    private record PreparedPublication(Demand demand, long revision, BuiltSection geometry,
                                       VoxyRenderSystem.SectionPublication previous) {}
    private static final class PublicationRef {
        private final Demand demand;
        private final long revision;
        private final VoxyRenderSystem.SectionPublication publication, previous;
        private final long bytes;
        private WorkerResource.Lease lease;

        PublicationRef(Demand demand, long revision,
                       VoxyRenderSystem.SectionPublication publication,
                       VoxyRenderSystem.SectionPublication previous,
                       WorkerResource.Lease lease, long bytes) {
            this.demand = demand; this.revision = revision;
            this.publication = publication; this.previous = previous;
            this.lease = lease; this.bytes = bytes;
        }

        Demand demand() { return this.demand; }
        long revision() { return this.revision; }
        VoxyRenderSystem.SectionPublication publication() { return this.publication; }
        VoxyRenderSystem.SectionPublication previous() { return this.previous; }
        long bytes() { return this.bytes; }
    }

    private sealed interface Event permits CatalogReady, Coarsened,
            CoarsenFailed, BatchComplete, BatchFailed, SessionObservation {}
    private record CatalogReady(CatalogTask task, RegionalSectionCodec.BoundCatalog binding,
                                Throwable failure) implements Event {}
    private record Coarsened(long parent, long view) implements Event {}
    private record CoarsenFailed(long parent, long view, Throwable failure) implements Event {}
    private record BatchComplete(long connectionEpoch, long reservedBytes,
                                 int sectionCount) implements Event {}
    private record BatchFailed(long connectionEpoch, Throwable failure) implements Event {}
    private record SessionObservation(Consumer<Session> receiver) implements Event {}

    record CatalogTask(Session owner, CatalogCodec.Catalog catalog,
                               RegionalProtocol.Hash32 fingerprint,
                               long view, WorkerResource.Lease lease,
                               Consumer<RegionalSectionCodec.BoundCatalog> complete) {
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
            this.mapped(new RegionalSectionCodec.Mappings(blocks, biomes));
        }
        void mapped(RegionalSectionCodec.Mappings mappings) {
            CATALOG_TASK.compareAndSet(this, null);
            this.owner.putEvent(new CatalogReady(this,
                    new RegionalSectionCodec.BoundCatalog(this.fingerprint, mappings), null));
        }
    }

    private static void discardEvent(Event event) {
        // Remaining events carry no native or heavyweight resource.
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
