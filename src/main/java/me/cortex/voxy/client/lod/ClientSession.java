package me.cortex.voxy.client.lod;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.rendering.hierarchical.AsyncNodeManager;
import me.cortex.voxy.client.core.model.CatalogMapper;
import me.cortex.voxy.client.core.rendering.SectionKey;
import me.cortex.voxy.client.core.rendering.RenderDistanceTracker;
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
public final class ClientSession {
    private static final long RETRY_DELAY_NANOS = TimeUnit.SECONDS.toNanos(1);

    private static final Object LIFECYCLE = new Object();
    private static final LinkedHashSet<Long> TOP_LEVEL = new LinkedHashSet<>();
    // LIFECYCLE owns callback authority and ordered roots, including pre-connect population.
    // Cleared on owner replacement/reset/stop; a stopped owner cannot mutate its successor.
    private static VoxyRenderSystem topRenderer;
    private static final AtomicLong SESSION_IDS = new AtomicLong();
    private static volatile Session active;
    private static volatile String activeDimension;
    private static volatile VoxyRenderSystem activeRenderer;
    private static volatile long retryAfter;

    private ClientSession() {}

    static void subscriptionWindowChanged(VoxyRenderSystem renderer, RenderDistanceTracker.Window window) {
        synchronized (LIFECYCLE) {
            Session current = active;
            if (current != null && current.renderer == renderer) current.offerWindow(window);
        }
    }

    static void attachRenderer(VoxyRenderSystem renderer) {
        synchronized (LIFECYCLE) {
            if (topRenderer == renderer) return;
            TOP_LEVEL.clear();
            topRenderer = java.util.Objects.requireNonNull(renderer);
        }
    }

    static boolean sectionEntered(VoxyRenderSystem renderer, long key) {
        requireTop(key);
        synchronized (LIFECYCLE) {
            if (topRenderer != renderer || renderer == null) return false;
            if (!TOP_LEVEL.add(key)) return false;
            Session current = active;
            if (current != null && current.renderer == renderer) {
                current.demands.offerTop(key, true);
                current.signal();
            }
            return true;
        }
    }

    static void sectionLeft(VoxyRenderSystem renderer, long key) {
        requireTop(key);
        synchronized (LIFECYCLE) {
            if (topRenderer != renderer || renderer == null) return;
            TOP_LEVEL.remove(key);
            Session current = active;
            if (current != null && current.renderer == renderer) {
                current.demands.offerTop(key, false);
                current.signal();
            }
        }
    }

    static void detailAction(long key, int action, int bucket, int epoch) {
        Session current = active;
        if (current != null) current.acceptDetailAction(key, action, bucket, epoch);
    }

    static void resetDemand() {
        synchronized (LIFECYCLE) {
            TOP_LEVEL.clear();
            topRenderer = null;
            Session current = active;
            if (current != null) current.resetRequested.set(true);
        }
    }

    static void rendererLifecycleChanged() { disconnect(); }

    static String debugSnapshot() {
        Session current = active;
        return current == null ? "regional=DISCONNECTED" : ClientLodDebug.sessionSnapshot(current);
    }

    static long debugSessionIdentity() { Session current = active; return current == null ? 0 : current.id; }
    static long debugOpenSessionIdentity() { Session current = active; return current == null || !current.open.get() ? 0 : current.id; }

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

    /** First registry/mapper access stays on the render thread. Pending names belong to the
     * existing worker slots; successful spelling-to-ID entries share this renderer epoch. */
    public static void streamingSettingsChanged() {
        var session = active; if (session != null) session.signal();
    }
    public static void frameRendered(VoxyRenderSystem renderer) {
        var session = active;
        if (session != null && session.renderer == renderer && session.open.get()) {
            session.renderedFrames++; session.signal();
        }
    }

    static void frame() {
        Session session = active;
        if (session == null || !session.open.get() || session.renderer != activeRenderer) return;
        session.resolveNames((name, biome) -> {
            var mapper = session.renderer.getMapper();
            int available = biome ? Minecraft.getInstance().level.registryAccess().registryOrThrow(Registries.BIOME).size()
                    : Math.addExact(Block.BLOCK_STATE_REGISTRY.size(), BuiltInRegistries.BLOCK.size());
            if ((biome ? session.biomeNames.size() : session.blockNames.size()) >= available)
                throw new IOException("canonical names exceed the current registry capacity");
            return biome ? mapper.getIdForBiome(requireCanonicalBiome(name))
                    : mapper.getIdForBlockState(parseCanonicalState(name));
        });
    }

    /** Detach under LIFECYCLE, but never join while holding it. The renderer keeps the waiter. */
    static void stopRenderer(VoxyRenderSystem renderer) {
        synchronized (LIFECYCLE) {
            if (topRenderer == renderer) {
                topRenderer = null;
                TOP_LEVEL.clear();
            }
            Session current = active;
            if (current == null || current.renderer != renderer) return;
            stopLocked(current);
            activeDimension = null;
            activeRenderer = null;
        }
    }

    private static void stopLocked(Session session) {
        if (active == session) active = null;
        session.close();
    }

    private static void requireTop(long key) {
        if (SectionKey.level(key) != SectionKey.MAX_LOD_LAYER) {
            throw new IllegalArgumentException("regional renderer root is not LOD 4");
        }
    }

    private static List<Long> topSnapshot(VoxyRenderSystem renderer) {
        // TOP_LEVEL is populated nearest-first by RenderDistanceTracker. Preserve that order so
        // a cold session requests the player's coverage before distant regions.
        synchronized (LIFECYCLE) {
            return renderer != null && topRenderer == renderer ? List.copyOf(TOP_LEVEL) : List.of();
        }
    }

    static final class Demand extends SectionDemandTable.Demand {
        LocalSection content;
        LocalSection activeContent;
        boolean candidateCacheHit;
        RegionalSectionCodec.BoundCatalog catalog;
        VoxyRenderSystem.SectionPublication publication;
        VoxyRenderSystem.SectionPublication previousPublication;
        long wireTicket;
        long cacheActivatedFrame = -1;
        boolean networkWanted;
        boolean cachedCover;
        Set<Long> cachedCutPending;
        long cachedCoverActivatedFrame = -1;
        long gatingCoverKey, gatingCoverFrame = -1;
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
        WorkerResource.Lease workLease;
        VoxyRenderSystem.AllocationStatus blockedReason;
        AsyncNodeManager.PublicationProgress blockedAt;
        long prerequisite;
        long blockedRequiredBytes;

        Demand(long key) {
            super(key, regionFor(key),
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
        private boolean hasTop(long top) {
            synchronized (LIFECYCLE) { return topRenderer == this.renderer && TOP_LEVEL.contains(top); }
        }
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
        final WorkerSlot metadataWorker;
        final int sectionWorkerCount;
        final ConcurrentLinkedQueue<NetworkReply> networkReplies =
                new ConcurrentLinkedQueue<>();

        final Map<Long, LinkedHashSet<Long>> demandsByTop = new HashMap<>();
        final Set<Long> missingCoverage = new HashSet<>();
        final Set<Long> coarseningRoots = new HashSet<>();
        final Set<Long> rendererBlocked = new LinkedHashSet<>();
        final Set<Long> interestChanges = new LinkedHashSet<>();
        final Set<Long> interestDrops = new LinkedHashSet<>();
        final Set<Long> frameInterests = new LinkedHashSet<>();
        final Map<Long, Set<Long>> coverDependents = new HashMap<>();
        long checkedFrame;
        final ConcurrentLinkedQueue<NetworkCatalog> networkCatalogs = new ConcurrentLinkedQueue<>();
        CatalogCodec.SharedNames catalogueNames = new CatalogCodec.SharedNames();
        java.util.concurrent.FutureTask<CachedCatalogue> catalogueProbe;
        Thread catalogueProbeThread;
        long catalogueProbeView = -1;
        volatile CatalogueSave catalogueIntent;
        long catalogueFrames, catalogueCompressedBytes, catalogueCacheHits, catalogueValidationNanos;
        private volatile RenderDistanceTracker.Window targetWindow;
        private RenderDistanceTracker.Window reconciledWindow;
        long windowReconciliations;

        synchronized void offerWindow(RenderDistanceTracker.Window window) {
            this.targetWindow = window;
            this.signal();
        }

        boolean inSubscriptionWindow(long region) {
            var window = this.targetWindow;
            return window != null && window.contains(region);
        }

        RenderDistanceTracker.Window subscriptionWindow() { return this.reconciledWindow; }

        void reconcileWindow() {
            var window = this.targetWindow;
            if (Objects.equals(window, this.reconciledWindow)) return;
            long started = System.nanoTime();
            for (var state : this.demands.regions()) {
                if (window == null || !window.contains(state.key)) this.releaseRegion(state.key, state);
                else { this.queueRegion(state.key); this.interestChanges.addAll(state.members.keySet()); }
            }
            this.reconciledWindow = window;
            this.windowReconciliations++;
            ClientLodDebug.startupEvent(this, "subscriptionWindow", System.nanoTime() - started);
        }
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
        boolean openSent, bootstrapComplete;
        volatile long renderedFrames;
        long sentIntervalMillis = -1, sentBandwidthKbps = -1;
        RegionalProtocol.ServerHello welcome;
        RegionalQuicClient backgroundQuic;
        RegionalConnectionAttempt backgroundAttempt;
        long nextBackgroundAttempt, backgroundEpoch;
        Path cacheRoot;
        String serverKey;
        RegionalMetadataStore metadata;
        volatile long viewRevision;
        final java.util.concurrent.ConcurrentHashMap<String, Integer> blockNames = new java.util.concurrent.ConcurrentHashMap<>();
        final java.util.concurrent.ConcurrentHashMap<String, Integer> biomeNames = new java.util.concurrent.ConcurrentHashMap<>();
        volatile long resolvedNameCharacters;
        final java.util.concurrent.ConcurrentHashMap<RegionalProtocol.Hash32,
                RegionalSectionCodec.BoundCatalog> savedMappings =
                new java.util.concurrent.ConcurrentHashMap<>();
        volatile AssociationTask associationIntent;
        PersistTask deferredPersistence;
        final long[] persistenceOutcomes = new long[RegionalMetadataStore.Persistence.values().length];
        long associationPersisted, regionPersisted;
        String lastPersistenceFailure;
        boolean associationPending;
        boolean cacheOpened;
        boolean metadataUnavailable;
        CompletedSectionCache cache;
        RegionalProtocol.Hash32 worldIdentity;
        RegionalProtocol.Hash32 catalogFingerprint = RegionalProtocol.Hash32.ZERO;
        RegionalSectionCodec.Mappings mappings;
        RegionalSectionCodec.BoundCatalog currentCatalog;
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

        private static class NetworkHandoff {
            final Semaphore transfer = new Semaphore(0);
            final AtomicBoolean released = new AtomicBoolean();
            void awaitTransfer() throws InterruptedException { this.transfer.acquire(); }
            void transferred() { if (this.released.compareAndSet(false, true)) this.transfer.release(); }
        }
        static final class NetworkReply extends NetworkHandoff {
            final long connectionEpoch, backgroundEpoch;
            final RegionalProtocol.SectionReply reply;
            final RegionalSectionCodec.BoundCatalog catalog;
            NetworkReply(long connection, long background, RegionalProtocol.SectionReply reply, RegionalSectionCodec.BoundCatalog catalog) {
                this.connectionEpoch = connection; this.backgroundEpoch = background; this.reply = reply; this.catalog = catalog;
            }
        }
        private static final class NetworkCatalog extends NetworkHandoff {
            final long connection, background;
            final RegionalProtocol.CatalogMessage message;
            final CatalogCodec.Catalog decoded;
            final long validationNanos;
            volatile RegionalSectionCodec.BoundCatalog binding;
            NetworkCatalog(long connection, long background, RegionalProtocol.CatalogMessage message,
                           CatalogCodec.Catalog decoded, long validationNanos) {
                this.connection = connection; this.background = background; this.message = message;
                this.decoded = decoded; this.validationNanos = validationNanos;
            }
        }
        private record CachedCatalogue(long view, RegionalProtocol.Hash32 world,
                                       RegionalProtocol.CatalogMessage message, CatalogCodec.Catalog decoded,
                                       long validationNanos) {}
        private record CatalogueSave(long view, RegionalProtocol.Hash32 world,
                                     RegionalProtocol.CatalogMessage message) {}

        enum WorkerSource { CACHE, NETWORK }
        sealed interface WorkerTask permits SectionWorkerTask, EmptyWorkerTask,
                BootstrapTask, OpenWorldTask, LoadMetadataTask, PersistTask {}
        record EmptyWorkerTask(SectionDemandTable.Ticket ticket, byte children, LocalSection content,
                               CompletedSectionCache cache, java.util.function.BooleanSupplier current)
                implements WorkerTask {
            EmptyWorkerTask(SectionDemandTable.Ticket ticket, byte children) { this(ticket, children, null, null, () -> false); }
        }
        record SectionWorkerTask(SectionDemandTable.Ticket ticket,
                                         LocalSection content,
                                         WorkerSource source, byte[] compressed,
                                         RegionalSectionCodec.Mappings mappings, CompletedSectionCache cache,
                                         java.util.function.BooleanSupplier current)
                implements WorkerTask {}
        sealed interface WorkerResult permits WorkerMiss,
                WorkerGeometry, WorkerFailure, WorkerBootstrap, WorkerWorld,
                WorkerMetadata, WorkerSaved {}
        record BootstrapTask(Path root, String server, String dimension) implements WorkerTask {}
        private record OpenWorldTask(long view, RegionalProtocol.Hash32 world) implements WorkerTask {}
        private record AssociationTask(long view, RegionalProtocol.Hash32 world) {}
        private record LoadMetadataTask(long view, long region, long revision,
                                        RegionalProtocol.Hash32 world, CompletedSectionCache cache) implements WorkerTask {}
        private record PersistTask(Object intent, CompletedSectionCache cache, long stamp,
                                   java.util.function.BooleanSupplier current) implements WorkerTask {}
        private record WorkerBootstrap(RegionalMetadataStore metadata, RegionalProtocol.Hash32 hint)
                implements WorkerResult {}
        private record WorkerWorld(long view, CompletedSectionCache cache) implements WorkerResult {}
        private record WorkerMetadata(LoadMetadataTask task, Map<Long, LocalSection> sections) implements WorkerResult {}
        private record WorkerSaved(PersistTask task, RegionalMetadataStore.Persistence outcome, String reason) implements WorkerResult {}
        private record WorkerMiss(SectionDemandTable.Ticket ticket, boolean corrupt, LocalSection fallback)
                implements WorkerResult {}
        private record SaveInput(WorkerResource.Lease lease, SectionDemandTable.Ticket ticket,
                                 LocalSection content, CompletedSectionCache cache, byte[] canonical,
                                 CatalogCodec.Source source, java.util.function.BooleanSupplier current) {}
        private record SaveOutcome(WorkerResource.Lease lease, SectionDemandTable.Ticket ticket,
                                   LocalSection content, boolean committed, Throwable failure) {}
        private static final class NameWait {
            final String canonical; final boolean biome;
            boolean done; int id; IOException failure;
            NameWait(String canonical, boolean biome) { this.canonical = canonical; this.biome = biome; }
        }
        private record ModelWait(SectionWorkerTask task, RegionalSectionCodec.SectionData section) {}
        record WorkerGeometry(SectionDemandTable.Ticket ticket, BuiltSection geometry,
                                      long completedNanos, boolean cacheHit,
                                      int compressedBytes) implements WorkerResult {}
        private record WorkerFailure(WorkerTask task, int slot, Throwable failure)
                implements WorkerResult {}

        /** A persistent resource slot owns exactly one task or completion and has no backlog. */
        final class WorkerSlot {
            final int index;
            final Thread workerThread;
            final Object debugWork;
            final RegionalSectionCodec codec = new RegionalSectionCodec();
            final LocalSectionCodec localCodec = new LocalSectionCodec();
            private volatile NameWait nameWait;
            private volatile SaveInput saveInput;
            private volatile SaveOutcome saveOutcome;
            private long geometryPublishedNanos;
            final WorkerResource<WorkerResult> resource;
            private WorkerTask task;
            private WorkerResource.Lease taskLease;
            private long operationKey;
            private boolean sectionOperation;
            private volatile ModelWait modelWait;

            WorkerSlot(int index) {
                this.index = index;
                this.resource = new WorkerResource<>(index, Session::freeWorkerResult);
                this.workerThread = new Thread(this::run, "Voxy regional section worker-" + index);
                this.workerThread.setDaemon(true);
                this.debugWork = ClientLodDebug.workerCreated(Session.this, index, this.workerThread);
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
                if (this.resource.release(lease)) this.reusable();
            }
            private void reusable() {
                if (this.geometryPublishedNanos != 0) ClientLodDebug.startupEvent(Session.this,
                        "workerReusable", Math.max(0, System.nanoTime() - this.geometryPublishedNanos));
                this.geometryPublishedNanos = 0;
                signal();
            }
            private synchronized void cancelObsoleteSave() {
                var input = this.saveInput;
                if (input != null && !input.current().getAsBoolean()) this.workerThread.interrupt();
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
                            Thread.interrupted(); // Cancellation belongs only to the preceding operation.
                            claimed = this.task;
                            lease = this.taskLease;
                            this.task = null;
                        }
                        WorkerResult completion;
                        ClientLodDebug.workerBegin(this.debugWork, claimed, lease);
                        try {
                            completion = switch (claimed) {
                                case SectionWorkerTask section -> this.section(section);
                                case EmptyWorkerTask empty -> {
                                    if (empty.cache() != null && empty.current().getAsBoolean()) {
                                        this.resource.retainSave(lease);
                                        this.saveInput = new SaveInput(lease, empty.ticket(), empty.content(),
                                                empty.cache(), null, null, empty.current());
                                    }
                                    yield new WorkerGeometry(empty.ticket(), BuiltSection.emptyWithChildren(empty.ticket().key(),
                                            empty.ticket().demandRevision(), empty.children()), System.nanoTime(), true, 0);
                                }
                                case BootstrapTask bootstrap -> {
                                    var store = new RegionalMetadataStore(bootstrap.root());
                                    RegionalProtocol.Hash32 hint;
                                    try { hint = store.world(bootstrap.server(), bootstrap.dimension()); }
                                    catch (IOException invalid) { hint = null; }
                                    yield new WorkerBootstrap(store, hint);
                                }
                                case OpenWorldTask world -> {
                                    var cache = new CompletedSectionCache(metadata, world.world(), dimension);
                                    yield new WorkerWorld(world.view(), cache);
                                }
                                case LoadMetadataTask load -> this.loadMetadata(load);
                                case PersistTask save -> this.persist(save);
                            };
                        } catch (Throwable failure) {
                            ClientLodDebug.workerOutcome(this.debugWork, "FAILURE", 0);
                            completion = new WorkerFailure(claimed, this.index, failure);
                        }
                        try {
                            ClientLodDebug.workerStage(this.debugWork, "RESULT_READY");
                            if (completion instanceof WorkerGeometry) this.geometryPublishedNanos = System.nanoTime();
                            this.resource.complete(lease, completion);
                            // The mesh and original compressed task leave this scope BEFORE saving.
                            completion = null;
                            claimed = null;
                            signal();
                            if (this.saveInput != null) this.save();
                        } finally {
                            ClientLodDebug.workerEnd(this.debugWork, this.localCodec);
                        }
                        signal();
                    }
                } finally {
                    this.saveInput = null;
                    this.saveOutcome = null;
                    this.localCodec.close();
                    this.codec.close();
                    if (this.index == -1 && metadata != null) metadata.close();
                }
            }

            private WorkerResult section(SectionWorkerTask task) throws Exception {
                boolean cacheHit = task.source() == WorkerSource.CACHE;
                byte[] canonical = null;
                RegionalSectionCodec.SectionData section;
                var names = (LocalSectionCodec.Names) (name, biome) -> this.resolveName(name, biome, task.current());
                if (cacheHit) {
                    ClientLodDebug.workerStage(this.debugWork, "CACHE_READ");
                    try { section = task.cache() == null ? null : task.cache().get(task.content(), this.localCodec, names); }
                    catch (IOException corrupt) { return this.cacheMiss(task, true); }
                    if (section == null) return this.cacheMiss(task, false);
                    ClientLodDebug.workerOutcome(this.debugWork, "CACHE_HIT", 0);
                } else {
                    ClientLodDebug.workerOutcome(this.debugWork, "COMPRESSED_BYTES", task.compressed().length);
                    if (RegionalProtocol.crc32c(task.compressed()) != task.content().crc()) throw new IOException("wire section CRC mismatch");
                    ClientLodDebug.workerStage(this.debugWork, "DECOMPRESS");
                    canonical = this.codec.decompress(task.compressed(), task.content().canonicalBytes());
                    ClientLodDebug.workerOutcome(this.debugWork, "CANONICAL_BYTES", canonical.length);
                    ClientLodDebug.workerStage(this.debugWork, "DECODE_VALIDATE");
                    section = this.codec.decode(task.ticket().key(), task.content().children(), canonical,
                            task.content().fingerprint(), task.mappings(), names);
                }
                ClientLodDebug.workerStage(this.debugWork, "REQUEST_MODELS");
                mesher.requestModels(section);
                ClientLodDebug.workerStage(this.debugWork, "CHECK_MODELS");
                if (!mesher.modelsReady(section)) {
                    ClientLodDebug.workerOutcome(this.debugWork, "MODEL_WAIT", 0);
                    ClientLodDebug.workerStage(this.debugWork, "WAIT_MODELS");
                    this.awaitModels(task, section);
                }
                ClientLodDebug.workerStage(this.debugWork, "MESH");
                BuiltSection geometry = mesher.mesh(section, task.ticket().demandRevision());
                try {
                    if (!cacheHit && task.cache() != null && task.current().getAsBoolean()) {
                        if (task.mappings().source() == null) throw new IOException("save has no validated source names");
                        var save = new SaveInput(this.taskLease, task.ticket(), task.content(), task.cache(), canonical,
                                task.mappings().source(), task.current());
                        this.resource.retainSave(save.lease());
                        this.saveInput = save;
                    }
                    ClientLodDebug.workerOutcome(this.debugWork, "MESH_BYTES",
                            geometry.geometryBuffer == null ? 0 : geometry.geometryBuffer.size);
                    return new WorkerGeometry(task.ticket(), geometry, System.nanoTime(), cacheHit,
                            cacheHit ? Math.toIntExact(this.localCodec.decodedBytes()) : task.compressed().length);
                } catch (Throwable failure) { geometry.free(); throw failure; }
            }

            private int resolveName(String name, boolean biome, java.util.function.BooleanSupplier current) throws IOException {
                var cache = biome ? biomeNames : blockNames;
                Integer known = cache.get(name);
                if (known != null) return known;
                synchronized (this) {
                    var wait = new NameWait(name, biome);
                    this.nameWait = wait;
                    signal();
                    try {
                        while (!wait.done && current.getAsBoolean() && this.resource.state() != WorkerResource.State.CLOSED) this.wait();
                        if (!current.getAsBoolean() || this.resource.state() == WorkerResource.State.CLOSED)
                            throw new java.util.concurrent.CancellationException("name resolution superseded");
                        if (wait.failure != null) throw wait.failure;
                        return wait.id;
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new java.util.concurrent.CancellationException("name resolution interrupted");
                    } finally { this.nameWait = null; }
                }
            }

            private void save() {
                var input = this.saveInput;
                boolean committed = false;
                Throwable failure = null;
                try {
                    input.cache().save(input.content(), this.localCodec, input.canonical(), input.source(), input.current(), this.debugWork);
                    committed = true;
                    ClientLodDebug.workerOutcome(this.debugWork, "SAVE_SUCCESS", 0);
                } catch (Throwable problem) {
                    if (problem instanceof IOException && (Thread.currentThread().isInterrupted() || !input.current().getAsBoolean())) {
                        var cancelled = new java.util.concurrent.CancellationException("section save cancelled");
                        cancelled.initCause(problem); problem = cancelled;
                    }
                    failure = problem;
                    ClientLodDebug.workerOutcome(this.debugWork,
                            problem instanceof java.util.concurrent.CancellationException
                                    || problem instanceof java.io.InterruptedIOException ? "SAVE_CANCELLED" : "SAVE_FAILURE", 0);
                }
                synchronized (this) {
                    this.saveInput = null;
                    this.saveOutcome = new SaveOutcome(input.lease(), input.ticket(), input.content(), committed, failure);
                }
                signal();
            }

            private void awaitModels(SectionWorkerTask task, RegionalSectionCodec.SectionData section)
                    throws InterruptedException {
                synchronized (this) {
                    this.modelWait = new ModelWait(task, section);
                    signal();
                    try {
                        while (task.current().getAsBoolean() && !mesher.modelsReady(section)) this.wait();
                        if (!task.current().getAsBoolean()) throw new java.util.concurrent.CancellationException("model wait superseded");
                    } finally { this.modelWait = null; }
                }
            }

            private WorkerMiss cacheMiss(SectionWorkerTask task, boolean corrupt) {
                LocalSection fallback = null;
                if (corrupt) ClientLodDebug.workerStage(this.debugWork, "CACHE_QUARANTINE");
                ClientLodDebug.workerOutcome(this.debugWork, corrupt ? "CACHE_CORRUPT" : "CACHE_MISS", 0);
                if (task.cache() != null) try { fallback = task.cache().previous(task.content()); }
                catch (IOException invalid) { }
                return new WorkerMiss(task.ticket(), corrupt, fallback);
            }

            private WorkerResult loadMetadata(LoadMetadataTask task) {
                try { return new WorkerMetadata(task, task.cache().directory(task.region())); }
                catch (IOException invalid) { return new WorkerMetadata(task, new HashMap<>()); }
            }

            private WorkerSaved persist(PersistTask task) {
                var outcome = RegionalMetadataStore.Persistence.OBSOLETE;
                String reason = task.intent().getClass().getSimpleName() + "-refused";
                try {
                    java.util.function.BooleanSupplier current = () -> task.current().getAsBoolean()
                            && task.stamp() == metadata.budget.stamp();
                    if (current.getAsBoolean()) {
                        outcome = switch (task.intent()) {
                            case AssociationTask association -> metadata.associate(serverKey, dimension, association.world(), task.stamp(), current);
                            case CatalogueSave catalogue -> metadata.persistCatalogue(catalogue.world(), dimension,
                                    catalogue.message(), task.stamp(), current);
                            default -> throw new IllegalStateException("unknown metadata intent");
                        };
                    }
                } catch (Exception optional) {
                    outcome = RegionalMetadataStore.Persistence.UNAVAILABLE;
                    reason = optional.toString();
                }
                return new WorkerSaved(task, outcome, reason);
            }

            synchronized void close() {
                ClientLodDebug.workerClosing(this.debugWork);
                try { this.resource.close(); }
                finally {
                    this.task = null;
                    this.notifyAll();
                    this.workerThread.interrupt();
                }
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
            this.metadataWorker = new WorkerSlot(-1);
            this.demands = new SectionDemandTable<>(
                    HierarchicalOcclusionTraverser.DETAIL_BUCKET_COUNT, id);
            this.dimension = dimension;
            this.renderer = renderer;
            this.targetWindow = renderer == null ? null : renderer.subscriptionWindow();
            this.publisher = publisher;
            this.mesher = mesher;
            this.sectionWorkerCount = workers;
            this.sectionWorkers = new WorkerSlot[workers];
            for (int index = 0; index < workers; index++) {
                this.sectionWorkers[index] = new WorkerSlot(index);
            }
            this.thread = new Thread(this::run, "Voxy regional owner");
            this.thread.setDaemon(true);
            for (long key : topSnapshot(renderer)) this.demands.offerTop(key, true);
        }

        void start() {
            if (this.renderer != null) this.renderer.setRegionalQuiescence(this::awaitRendererQuiescence);
            ClientLodDebug.startupEvent(this, "start", 0);
            this.publisher.setProgressListener(this.rendererWake);
            this.metadataWorker.start();
            if (this.cacheRoot != null) this.metadataWorker.assign(new BootstrapTask(this.cacheRoot, this.serverKey, this.dimension));
            else this.bootstrapComplete = true;
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
                        this.reconcileWindow();
                        this.drainWorkers();
                        if (this.quic != null) this.drainControls();
                        this.drainNetworkReplies();
                        this.drainEvents();
                        this.drainDemand();
                        this.processMetadata();
                        this.drainNetworkCatalogs();
                        this.processRegions();
                        this.connectBackground();
                        this.pollPublications();
                        this.processStages();
                        ClientLodDebug.captureSession(this);
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
                try { this.release(); }
                catch (RuntimeException | Error cleanup) {
                    if (this.failure == null) this.failure = cleanup;
                    else if (this.failure != cleanup) this.failure.addSuppressed(cleanup);
                    Logger.error("Regional Voxy session cleanup failed", cleanup);
                }
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
                connected.listen(this.receiver(this.connectionEpoch + 1, 0));
            } catch (Throwable failure) {
                connected.close();
                if (failure instanceof IOException io) throw io;
                throw new IOException("could not initialize regional QUIC", failure);
            }
            this.quic = connected;
            if (this.currentCatalog != null) connected.remember(this.currentCatalog);
            this.helloAccepted = false;
            this.openSent = false;
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
            this.openSent = false;
            this.welcome = null;
            this.closeBackground();
            this.savedMappings.clear();
            if (this.currentCatalog != null) this.savedMappings.put(this.currentCatalog.fingerprint(), this.currentCatalog);
            this.interestDrops.clear();
            this.interestChanges.clear();
            this.frameInterests.clear(); this.coverDependents.clear();
            for (Demand demand : this.demands.values()) { demand.wireTicket = 0; this.interestChanges.add(demand.key); }
            ClientLodDebug.startupEvent(this, "subscriptionReset", 0);
            NetworkReply reply;
            while ((reply = this.networkReplies.poll()) != null) reply.transferred();
            NetworkCatalog incomingCatalog;
            while ((incomingCatalog = this.networkCatalogs.poll()) != null) incomingCatalog.transferred();
            for (Demand demand : this.demands.values()) {
                if (demand.candidate
                        != SectionDemandTable.CandidateState.NETWORK_OWNED) continue;
                this.demands.revise(demand);
                demand.candidate = SectionDemandTable.CandidateState.WAIT_REGION;
            }
            for (SectionDemandTable.RegionDemand region : List.copyOf(this.demands.regions())) {
                region.metadataRevision++;
                this.demands.forgetUnusedRegion(region);
                this.queueRegion(region.key);
            }
        }

        void drainControls() throws Exception {
            while (true) {
                var control = this.quic.pollControl();
                if (control == null) return;
                switch (control) {
                    case RegionalProtocol.ServerHello hello -> this.acceptHello(hello);
                    case RegionalProtocol.ServerError error -> throw new IOException("Voxy server error " + error.code() + ": " + error.message());
                    case RegionalProtocol.ServerShutdown shutdown -> throw new IOException(shutdown.message());
                    default -> throw new IOException("unexpected primary control frame");
                }
            }
        }
        void acceptHello(RegionalProtocol.ServerHello hello) {
            this.helloAccepted = true;
            if (!hello.worldIdentity().equals(this.worldIdentity)) this.changeWorld(hello.worldIdentity());
            this.welcome = hello;
            this.associationPending = true;
            this.associationIntent = new AssociationTask(this.viewRevision, this.worldIdentity);
            ClientLodDebug.startupEvent(this, "hello", 0);
        }
        private RegionalQuicClient.RecordReceiver receiver(long connection, long background) {
            return new RegionalQuicClient.RecordReceiver() {
                public void record(RegionalProtocol.SectionReply reply, boolean bg, RegionalSectionCodec.BoundCatalog catalog) throws InterruptedException {
                    var handoff = new NetworkReply(connection, bg ? background : 0, reply, catalog);
                    networkReplies.add(handoff); signal(); handoff.awaitTransfer();
                    if (!open.get()) throw new InterruptedException("session closed");
                }
                public RegionalSectionCodec.BoundCatalog catalog(RegionalProtocol.CatalogMessage message, boolean bg) throws Exception {
                    long started = System.nanoTime();
                    var decoded = decodeCatalogue(message);
                    var handoff = new NetworkCatalog(connection, bg ? background : 0, message, decoded, System.nanoTime() - started);
                    networkCatalogs.add(handoff); signal(); handoff.awaitTransfer();
                    if (!open.get()) throw new InterruptedException("session closed");
                    if (handoff.binding == null) throw new IOException("catalog was not accepted");
                    return handoff.binding;
                }
            };
        }
        private static CatalogCodec.Catalog decodeCatalogue(RegionalProtocol.CatalogMessage message) throws IOException {
            try (var codec = new RegionalSectionCodec()) {
                byte[] canonical = codec.decompressCatalogue(message.compressed(), message.canonicalLength());
                if (!hash32(canonical).equals(message.fingerprint())) throw new IOException("regional catalog fingerprint mismatch");
                return CatalogCodec.decode(canonical);
            }
        }
        private RegionalSectionCodec.BoundCatalog installCatalogue(RegionalProtocol.CatalogMessage message,
                                                                   CatalogCodec.Catalog decoded, boolean cached) throws IOException {
            var existing = this.mapping(message.fingerprint());
            if (existing != null) return existing;
            CatalogCodec.Source source;
            try { source = this.catalogueNames.bind(decoded); }
            catch (CatalogCodec.DecodeException mismatch) {
                if (cached && this.currentCatalog != null) return null;
                this.catalogueNames = new CatalogCodec.SharedNames();
                source = this.catalogueNames.bind(decoded);
                this.currentCatalog = null; // Never advertise the rejected discovery hint again.
                this.catalogueIntent = null;
            }
            var binding = new RegionalSectionCodec.BoundCatalog(message.fingerprint(), new RegionalSectionCodec.Mappings(source));
            this.savedMappings.put(binding.fingerprint(), binding);
            if (this.quic != null) this.quic.remember(binding);
            var previous = this.currentCatalog == null ? null : this.currentCatalog.mappings().source();
            if (previous == null || previous.catalogId() != source.catalogId() || source.generation() >= previous.generation()) {
                this.currentCatalog = binding; this.catalogFingerprint = binding.fingerprint(); this.mappings = binding.mappings();
                if (!cached && this.metadata != null && this.worldIdentity != null)
                    this.catalogueIntent = new CatalogueSave(this.viewRevision, this.worldIdentity, message);
            }
            return binding;
        }
        private void drainNetworkCatalogs() throws IOException {
            if (this.catalogueProbe != null && this.catalogueProbe.isDone()) {
                var finished = this.catalogueProbe; this.catalogueProbe = null;
                try {
                    var hit = finished.get();
                    if (hit != null && hit.view() == this.viewRevision && hit.world().equals(this.worldIdentity)) {
                        if (this.installCatalogue(hit.message(), hit.decoded(), true) != null) this.catalogueCacheHits++;
                        this.catalogueValidationNanos += hit.validationNanos();
                    }
                } catch (Exception optional) { this.lastPersistenceFailure = "catalogue-probe: " + optional; }
            }
            for (var handoff : this.networkCatalogs) {
                if (handoff.connection != this.connectionEpoch || handoff.background != 0 && handoff.background != this.backgroundEpoch) {
                    this.networkCatalogs.remove(handoff); handoff.transferred(); continue;
                }
                if (!this.helloAccepted) return;
                this.catalogueFrames++; this.catalogueCompressedBytes += handoff.message.compressed().length;
                this.catalogueValidationNanos += handoff.validationNanos;
                try {
                    handoff.binding = this.installCatalogue(handoff.message, handoff.decoded, false);
                    ClientLodDebug.startupEvent(this, "metadata", handoff.message.canonicalLength());
                } finally { this.networkCatalogs.remove(handoff); handoff.transferred(); }
            }
        }
        private void connectBackground() throws IOException {
            if (!this.helloAccepted || this.welcome == null || this.quic == null) return;
            if (this.backgroundQuic != null && !this.backgroundQuic.isOpen()) this.closeBackground();
            if (this.backgroundQuic != null) return;
            if (this.backgroundAttempt == null) {
                if (System.nanoTime() - this.nextBackgroundAttempt < 0) return;
                var primary = this.quic; var hello = this.welcome;
                var held = this.currentCatalog;
                long connection = this.connectionEpoch;
                long epoch = ++this.backgroundEpoch;
                this.backgroundAttempt = new RegionalConnectionAttempt(() -> RegionalQuicClient.connectBackground(primary,
                        hello.backgroundToken(), held, this.receiver(connection, epoch)));
            }
            var outcome = this.backgroundAttempt.poll(); if (outcome == null) return;
            this.backgroundAttempt.close(); this.backgroundAttempt = null;
            this.nextBackgroundAttempt = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            if (outcome.failure() == null) { this.backgroundQuic = outcome.connection(); this.backgroundQuic.setActivityListener(this::signal); }
        }
        private void closeBackground() {
            ++this.backgroundEpoch;
            if (this.backgroundAttempt != null) { this.backgroundAttempt.close(); this.backgroundAttempt = null; }
            if (this.backgroundQuic != null) { this.backgroundQuic.close(); this.backgroundQuic = null; }
        }

        void drainEvents() throws Exception {
            Event event;
            while ((event = this.events.poll()) != null) {
                switch (event) {
                    case Coarsened result -> {
                        if (result.view == this.viewRevision) this.finishCoarsening(result.parent, true);
                    }
                    case CoarsenFailed failed -> {
                        if (failed.view != this.viewRevision) continue;
                        this.finishCoarsening(failed.parent, false);
                        Logger.warn("Regional subtree coarsening failed; retained geometry "
                                + "remains authoritative", failed.failure);
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
                        cacheReading++;
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
            for (Demand demand : this.demands.values()) newestRoot = Math.max(newestRoot, demand.regionGeneration);
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
                            || SectionKey.level(parent) == 0 || this.isCoarsening(parent)
                            || !newerEpoch(epoch, demand.latestRefinementEpoch)) continue;
                    if (demand.activeContent == null) {
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
            this.dropInterest(demand);
            this.forgetDependencies(demand);
            if (demand.installed) this.activeCount--;
            demand.installed = false;
            this.setActiveGeometryBytes(demand, 0);
            // One fenced renderer operation owns the whole subtree; do not close each child.
            demand.publication = null;
            demand.previousPublication = null;
            removeOwned(this.demandsByTop, topAncestor(key), key);
            if (regionState != null && regionState.members.isEmpty()) this.releaseRegion(region, regionState);
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
                    if (demand.content != null) this.queueBound(demand);
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
            this.interestChanges.add(key);
            this.demands.setPriority(demand, bucket);
            if (demand.coverage) this.missingCoverage.add(key);
            this.demandsByTop.computeIfAbsent(topAncestor(key), ignored -> new LinkedHashSet<>())
                    .add(key);
            if (!this.bindAvailable(demand)) {
                demand.candidate = SectionDemandTable.CandidateState.WAIT_REGION;
                this.ensureRegion(key);
            }
        }

        boolean addChildren(long parent, int bucket) {
            Demand demand = this.demands.get(parent);
            if (demand == null || demand.activeContent == null && demand.content == null) return false;
            if (SectionKey.level(parent) == 0) return true;
            int childMask = (demand.activeContent == null ? demand.content : demand.activeContent).children();
            for (int child = 0; child < 8; child++) {
                if ((childMask & 1 << child) == 0) continue;
                long key = child(parent, child);
                this.addDemand(key, bucket);
            }
            return true;
        }

        /** Local bindings are complete identities, independent of the connection or server index. */
        boolean bindAvailable(Demand demand) {
            var region = this.demands.region(demand.regionKey);
            if (region == null) return false;
            if (this.worldIdentity != null && !region.localLoaded && !this.metadataUnavailable && this.metadata != null) return false;
            var local = region.localSections.get(demand.key);
            if (demand.content == null && local != null && local.kind() != LocalSection.ABSENT) {
                this.bindLocal(demand, local); return true;
            }
            if (demand.content != null) return true;
            if (!demand.cachedCover) {
                var cut = new ArrayList<Long>();
                demand.cachedCover = this.collectCachedCut(region.localSections, demand.key, cut);
                if (demand.cachedCover) {
                    demand.cachedCutPending = new HashSet<>(); demand.cachedCoverActivatedFrame = -1;
                    for (long key : cut) {
                        var source = this.demands.get(key);
                        if (source != null && source.installed) {
                            demand.cachedCoverActivatedFrame = Math.max(demand.cachedCoverActivatedFrame, source.cacheActivatedFrame);
                        } else {
                            demand.cachedCutPending.add(key);
                            this.coverDependents.computeIfAbsent(key, ignored -> new LinkedHashSet<>()).add(demand.key);
                        }
                    }
                }
            }
            if (demand.cachedCover) this.missingCoverage.remove(demand.key);
            demand.networkWanted = !demand.cachedCover;
            this.interestChanges.add(demand.key);
            return false;
        }
        private boolean collectCachedCut(Map<Long, LocalSection> local, long key, List<Long> cut) {
            var section = local.get(key);
            if (section != null && section.kind() != LocalSection.ABSENT) { cut.add(key); return true; }
            if (SectionKey.level(key) == 0) return false;
            for (int i = 0; i < 8; i++) if (!this.collectCachedCut(local, child(key, i), cut)) return false;
            return true;
        }
        private void coverageActivated(Demand source) {
            var waiting = this.coverDependents.remove(source.key); if (waiting == null) return;
            for (long key : waiting) {
                var demand = this.demands.get(key); if (demand == null) continue;
                if (demand.cachedCover && demand.cachedCutPending != null) {
                    demand.cachedCutPending.remove(source.key);
                    demand.cachedCoverActivatedFrame = Math.max(demand.cachedCoverActivatedFrame, source.cacheActivatedFrame);
                    if (!demand.cachedCutPending.isEmpty()) continue;
                }
                this.interestChanges.add(key);
            }
        }
        private void invalidateCachedCover(long sourceKey) {
            var waiting = this.coverDependents.remove(sourceKey); if (waiting == null) return;
            for (long key : waiting) {
                var demand = this.demands.get(key); if (demand == null || !demand.cachedCover) continue;
                demand.cachedCover = false; demand.cachedCutPending = null; demand.networkWanted = true;
                this.interestChanges.add(key);
            }
        }
        void bindLocal(Demand demand, LocalSection content) {
            if (demand.content != null) return;
            demand.content = content; demand.catalog = null; demand.networkWanted = false;
            this.demands.revise(demand); this.queueBound(demand);
        }
        private void dropInterest(Demand demand) {
            this.interestChanges.remove(demand.key);
            this.frameInterests.remove(demand.key);
            if (demand.wireTicket != 0) this.interestDrops.add(demand.key);
            demand.wireTicket = 0;
        }
        private void forgetDependencies(Demand demand) {
            this.invalidateCachedCover(demand.key);
            if (demand.cachedCutPending != null) for (long source : demand.cachedCutPending) {
                var waiting = this.coverDependents.get(source);
                if (waiting != null) { waiting.remove(demand.key); if (waiting.isEmpty()) this.coverDependents.remove(source); }
            }
            long ancestor = demand.key;
            while (SectionKey.level(ancestor) < SectionKey.MAX_LOD_LAYER) {
                ancestor = parent(ancestor); var waiting = this.coverDependents.get(ancestor);
                if (waiting != null) { waiting.remove(demand.key); if (waiting.isEmpty()) this.coverDependents.remove(ancestor); }
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
            this.dropInterest(demand);
            this.forgetDependencies(demand);
            this.demands.unlinkReady(demand);
            this.rendererBlocked.remove(key);
            this.forgetDormancyForSubtree(key);
            this.discardCompletedGeometry(demand);
            this.releasePublishingGeometryAccounting(demand);
            this.demands.revise(demand);
            if (demand.installed) this.activeCount--;
            demand.installed = false;
            demand.activeContent = null;
            this.setActiveGeometryBytes(demand, 0);
            if (demand.publication != null) demand.publication.close();
            if (demand.previousPublication != null) demand.previousPublication.close();
            demand.previousPublication = null;
            if (demand.coverage && hasTop(key)) {
                // An authoritative absent section represents air, not coverage work waiting for
                // renderer admission. A later region generation will add it back when it binds.
                this.missingCoverage.remove(key);
                demand.publication = null;
                demand.content = null;
                demand.catalog = null;
                demand.networkWanted = false; demand.cachedCover = false; demand.cachedCutPending = null;
                demand.regionGeneration = 0; demand.cacheActivatedFrame = -1;
                demand.candidate = SectionDemandTable.CandidateState.WAIT_REGION;
            } else {
                this.missingCoverage.remove(key);
                long region = regionFor(key);
                SectionDemandTable.RegionDemand regionState = this.demands.region(region);
                this.demands.remove(key);
                removeOwned(this.demandsByTop, topAncestor(key), key);
                if (regionState != null && regionState.members.isEmpty()) {
                    this.releaseRegion(region, regionState);
                }
            }
        }

        void releaseRegion(long region, SectionDemandTable.RegionDemand state) {
            for (long key : state.members.keySet()) {
                var demand = this.demands.get(key); if (demand != null) this.dropInterest(demand);
            }
        }

        void queueBound(Demand demand) {
            demand.candidate = SectionDemandTable.CandidateState.READY_SOURCE;
            this.demands.ready(demand, SectionDemandTable.ReadyKind.SOURCE);
        }

        void processStages() throws Exception {
            this.scheduleReadyPublications(); this.processWaitingModels(); this.scheduleSourceWork();
        }
        void ensureRegion(long key) { this.queueRegion(regionFor(key)); this.interestChanges.add(key); }
        void queueRegion(long region) { this.demands.readyRegion(this.demands.region(region)); }
        private boolean localProbed(Demand demand) {
            var region = this.demands.region(demand.regionKey);
            return this.bootstrapComplete && (this.metadataUnavailable || this.cacheRoot == null
                    || this.worldIdentity == null || region != null && region.localLoaded);
        }
        private boolean networkEligible(Demand demand) {
            if (!this.localProbed(demand)) return false;
            demand.gatingCoverKey = 0; demand.gatingCoverFrame = -1;
            if (demand.cachedCover) {
                if (!demand.cachedCutPending.isEmpty()) return false;
                if (demand.cachedCoverActivatedFrame < this.renderedFrames) return true;
                this.frameInterests.add(demand.key); return false;
            }
            if (!demand.networkWanted) {
                if (demand.installed && (demand.content.kind() == LocalSection.EMPTY || demand.cacheActivatedFrame < this.renderedFrames)) return true;
                if (demand.installed) this.frameInterests.add(demand.key);
                return false;
            }
            long ancestor = demand.key;
            while (SectionKey.level(ancestor) < SectionKey.MAX_LOD_LAYER) {
                ancestor = parent(ancestor); var cover = this.demands.get(ancestor);
                if (cover == null || cover.content == null || cover.content.kind() == LocalSection.ABSENT) continue;
                demand.gatingCoverKey = ancestor; demand.gatingCoverFrame = cover.cacheActivatedFrame;
                if (cover.content.kind() == LocalSection.EMPTY) break;
                // Only a real covering parent delays fine misses. Partial cached siblings cannot block one another.
                if (!cover.installed && !cover.networkWanted) {
                    this.coverDependents.computeIfAbsent(ancestor, ignored -> new LinkedHashSet<>()).add(demand.key); return false;
                }
                if (cover.installed && cover.cacheActivatedFrame >= this.renderedFrames
                        && cover.content.kind() != LocalSection.EMPTY) { this.frameInterests.add(demand.key); return false; }
                break;
            }
            return true;
        }
        void processRegions() throws IOException {
            if (this.quic == null) return;
            if (this.checkedFrame != this.renderedFrames) {
                this.checkedFrame = this.renderedFrames; this.interestChanges.addAll(this.frameInterests); this.frameInterests.clear();
            }
            long interval = Math.multiplyExact((long) VoxyConfig.CONFIG.getBackgroundUpdateIntervalSeconds(), 1000L);
            long bandwidth = VoxyConfig.CONFIG.getBackgroundDownloadKbps();
            if (this.openSent && (interval != this.sentIntervalMillis || bandwidth != this.sentBandwidthKbps)) {
                if (!this.quic.settings(interval, bandwidth)) return;
                this.sentIntervalMillis = interval; this.sentBandwidthKbps = bandwidth; return;
            }
            if (this.openSent && !this.interestDrops.isEmpty()) {
                var keys = this.interestDrops.stream().limit(RegionalProtocol.MAX_SECTION_REQUESTS).toList();
                if (!this.quic.drop(keys)) return; this.interestDrops.removeAll(keys); return;
            }
            var changes = new ArrayList<RegionalProtocol.Desire>();
            var pendingChanges = this.interestChanges.iterator();
            while (pendingChanges.hasNext()) {
                long key = pendingChanges.next(); var demand = this.demands.get(key);
                if (demand == null || this.targetWindow != null && !this.inSubscriptionWindow(demand.regionKey)
                        || !this.networkEligible(demand)) { pendingChanges.remove(); continue; }
                long ticket = ++this.requestEpoch; if (ticket == 0) ticket = ++this.requestEpoch;
                LocalSection have = demand.networkWanted ? null : demand.activeContent;
                changes.add(new RegionalProtocol.Desire(ticket, key, demand.networkWanted ? demand.coverage ? 0 : 1 : 2, have));
                if (changes.size() == RegionalProtocol.MAX_SECTION_REQUESTS) break;
            }
            if (changes.isEmpty()) return;
            boolean accepted = this.openSent ? this.quic.desire(changes)
                    : this.quic.open(this.dimension, this.worldIdentity, this.currentCatalog, interval, bandwidth, changes);
            if (!accepted) return;
            this.openSent = true; this.sentIntervalMillis = interval; this.sentBandwidthKbps = bandwidth;
            for (var change : changes) {
                var demand = this.demands.get(change.key());
                ClientLodDebug.streamingDesire(this, demand, change);
                demand.wireTicket = change.ticket(); this.interestChanges.remove(change.key());
                if (demand.networkWanted) demand.candidate = SectionDemandTable.CandidateState.NETWORK_OWNED;
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
            this.catalogueNames = new CatalogCodec.SharedNames();
            if (this.catalogueProbe != null) this.catalogueProbe.cancel(true);
            this.catalogueProbe = null;
            this.catalogueProbeView = -1;
            this.frameInterests.clear(); this.coverDependents.clear();
            this.coarseningRoots.clear();
            this.pendingDormantEvictions.clear();
            this.pendingDormantEvictionBytes = 0;
            this.clearPersistence();
            for (SectionDemandTable.RegionDemand region : this.demands.regions()) {
                region.metadataRevision++;
                region.localTried = false;
                region.localLoaded = false;
                region.localSections = Map.of();
                this.queueRegion(region.key);
                this.interestChanges.addAll(region.members.keySet());
            }
        }

        RegionalSectionCodec.BoundCatalog mapping(RegionalProtocol.Hash32 fingerprint) {
            return this.savedMappings.get(fingerprint);
        }

        /** Render-thread seam; each unresolved name stays in its existing worker, never a queue. */
        void resolveNames(LocalSectionCodec.Names resolver) {
            long deadline = System.nanoTime() + 2_000_000;
            boolean progress;
            do {
                progress = false;
                for (var worker : this.sectionWorkers) {
                    var wait = worker.nameWait;
                    if (wait == null) continue;
                    synchronized (worker) {
                        if (worker.nameWait != wait || wait.done) continue;
                        try {
                            var names = wait.biome ? this.biomeNames : this.blockNames;
                            Integer id = names.get(wait.canonical);
                            if (id == null) {
                                id = resolver.resolve(wait.canonical, wait.biome);
                                names.put(wait.canonical, id);
                                this.resolvedNameCharacters += wait.canonical.length();
                            }
                            wait.id = id;
                        } catch (IOException | RuntimeException failure) { wait.failure = new IOException("Cannot resolve canonical name", failure); }
                        wait.done = true; worker.notifyAll(); progress = true;
                    }
                    if (System.nanoTime() >= deadline) return;
                }
            } while (progress && System.nanoTime() < deadline);
        }

        long retainedSaveBytes() {
            long bytes = 0;
            for (var worker : this.sectionWorkers) {
                var input = worker.saveInput;
                if (input != null && input.canonical() != null) bytes += input.canonical().length;
            }
            return bytes;
        }

        void processMetadata() {
            this.probeCatalogue();
            if (this.metadataUnavailable && this.cache == null) {
                long count = (this.associationPending ? 1 : 0);
                this.persistenceOutcomes[RegionalMetadataStore.Persistence.UNAVAILABLE.ordinal()] += count;
                if (count != 0) this.lastPersistenceFailure = "cache-unavailable";
                this.clearPersistence();
            }
            if (this.metadata == null || this.worldIdentity == null || !this.metadataWorker.idle()) return;
            if (!this.cacheOpened) {
                this.cacheOpened = true;
                this.metadataWorker.assign(new OpenWorldTask(this.viewRevision, this.worldIdentity));
                return;
            }
            if (!this.loadLocalMetadata()) this.persistMetadata();
        }

        private void probeCatalogue() {
            if (this.metadata == null || this.worldIdentity == null || this.catalogueProbeView == this.viewRevision) return;
            this.catalogueProbeView = this.viewRevision;
            var store = this.metadata; var world = this.worldIdentity; long view = this.viewRevision;
            var task = new java.util.concurrent.FutureTask<CachedCatalogue>(() -> {
                long started = System.nanoTime();
                var message = store.readCatalogue(world, this.dimension);
                return message == null ? null : new CachedCatalogue(view, world, message, decodeCatalogue(message), System.nanoTime() - started);
            });
            this.catalogueProbe = task;
            this.catalogueProbeThread = Thread.ofVirtual().name("Voxy catalogue cache probe").start(() -> { task.run(); this.signal(); });
        }

        private boolean loadLocalMetadata() {
            if (this.cache == null) return false;
            var local = this.demands.pollRegion(region -> !region.localTried);
            if (local != null) {
                local.localTried = true;
                this.metadataWorker.assign(new LoadMetadataTask(this.viewRevision, local.key,
                        local.metadataRevision, this.worldIdentity, this.cache));
                this.demands.readyRegion(local);
                return true;
            }
            return false;
        }

        private boolean persistMetadata() {
            var unavailable = this.metadata.budget.persistenceUnavailable();
            if (unavailable == RegionalMetadataStore.Persistence.DEFERRED_INVENTORY) return false;
            if (unavailable != null) {
                long count = (this.associationPending ? 1 : 0);
                this.persistenceOutcomes[unavailable.ordinal()] += count;
                if (count != 0) this.lastPersistenceFailure = "inventory-" + unavailable;
                this.clearPersistence();
                return false;
            }
            if (this.deferredPersistence != null) {
                var attempt = this.deferredPersistence;
                this.deferredPersistence = null;
                this.metadataWorker.assign(attempt); // Preserve the original stamp after an attempted write.
                return true;
            }
            long stamp = this.metadata.budget.stamp();
            if (this.associationPending) {
                var intent = this.associationIntent;
                this.metadataWorker.assign(new PersistTask(intent, this.cache, stamp,
                        () -> this.open.get() && this.viewRevision == intent.view() && this.associationIntent == intent));
                return true;
            }
            if (this.catalogueIntent != null) {
                var intent = this.catalogueIntent;
                this.metadataWorker.assign(new PersistTask(intent, this.cache, stamp,
                        () -> this.open.get() && this.viewRevision == intent.view() && this.catalogueIntent == intent));
                return true;
            }
            return false;
        }

        private void savedMetadata(WorkerSaved saved) {
            var outcome = saved.outcome();
            this.persistenceOutcomes[outcome.ordinal()]++;
            if (outcome == RegionalMetadataStore.Persistence.PERSISTED) {
                if (saved.task().intent() instanceof AssociationTask) this.associationPersisted++;
                else this.regionPersisted++;
            }
            if (outcome == RegionalMetadataStore.Persistence.UNAVAILABLE) this.lastPersistenceFailure = saved.reason();
            if (outcome == RegionalMetadataStore.Persistence.DEFERRED_INVENTORY && saved.task().current().getAsBoolean()) {
                this.deferredPersistence = saved.task();
                return;
            }
            switch (saved.task().intent()) {
                case AssociationTask association -> {
                    if (this.associationIntent == association) { this.associationPending = false; this.associationIntent = null; }
                }
                case CatalogueSave catalogue -> { if (this.catalogueIntent == catalogue) this.catalogueIntent = null; }
                default -> throw new IllegalStateException("unknown metadata completion");
            }
        }

        private void clearPersistence() {
            this.associationPending = false;
            this.associationIntent = null;
            this.deferredPersistence = null;
            this.catalogueIntent = null;
        }

        void applyLocalIndex(WorkerMetadata ready) {
            var task = ready.task();
            var state = this.demands.region(task.region());
            if (state == null) task.cache().forget(task.region());
            if (task.view() != this.viewRevision || state == null) return;
            state.localLoaded = true;
            state.localSections = ready.sections();
            ClientLodDebug.discovered(this, state.localSections);
            // Seed only the highest available cached nodes of branches with no cached
            // ancestors. At most four probes per section; normal GPU refinement takes over
            // below real cached parents. No fabricated empty parent or sibling payloads.
            for (var section : state.localSections.values()) {
                if (section.kind() == LocalSection.ABSENT || SectionKey.level(section.key()) == SectionKey.MAX_LOD_LAYER
                        || this.demands.get(topAncestor(section.key())) == null) continue;
                long ancestor = section.key();
                boolean covered = false;
                while (SectionKey.level(ancestor) < SectionKey.MAX_LOD_LAYER) {
                    ancestor = parent(ancestor);
                    if (state.localSections.containsKey(ancestor)) { covered = true; break; }
                }
                if (!covered) this.addDemand(section.key());
            }
            ClientLodDebug.startupEvent(this, "localView", 0);
            for (long key : List.copyOf(state.members.keySet())) {
                Demand demand = this.demands.get(key);
                if (demand != null && !this.bindAvailable(demand)) demand.candidate = SectionDemandTable.CandidateState.WAIT_REGION;
            }
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
                if ((!coverage && !dependency) || demand.coverage
                        || demand.completedGeometry == null && holder.modelWait == null && holder.nameWait == null
                        || demand.workLease == null
                        || demand.candidate != SectionDemandTable.CandidateState.WORKER_OWNED
                        && demand.candidate != SectionDemandTable.CandidateState.WAIT_MODELS) continue;
                if (selected == null || demand.pixelBucket < selected.pixelBucket) {
                    selected = demand;
                }
            }
            if (selected == null) return null;
            WorkerSlot reclaimed = this.sectionWorkers[selected.workLease.slot()];
            // The worker can leave its wait concurrently after selection. Only an owner-
            // observed mesh is synchronously reclaimable; never clear a still-running lease.
            if (selected.completedGeometry == null) {
                ClientLodDebug.workerOutcome(reclaimed.debugWork, "MODEL_RECLAIM", 0);
                this.demands.revise(selected);
                selected.candidate = SectionDemandTable.CandidateState.READY_SOURCE;
                synchronized (reclaimed) { reclaimed.notifyAll(); }
                // The running worker still owns its cells. Its stale completion releases the
                // slot before the prerequisite can acquire it; never revoke live ownership.
                return null;
            }
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
                worker.cancelObsoleteSave();
                SaveOutcome outcome;
                synchronized (worker) { outcome = worker.saveOutcome; worker.saveOutcome = null; }
                if (outcome != null && worker.resource.matches(outcome.lease())) {
                    if (outcome.committed()) {
                        var demand = this.demands.get(outcome.ticket().key());
                        if (demand != null && demand.revision == outcome.ticket().demandRevision()) this.recordCommitted(demand, outcome.content());
                    }
                    if (worker.resource.finishSave(outcome.lease())) worker.reusable();
                    if (outcome.failure() instanceof IOException io) this.lastPersistenceFailure = io.toString();
                    else if (outcome.failure() != null && !(outcome.failure() instanceof java.util.concurrent.CancellationException)) {
                        throw new IllegalStateException("Section persistence invariant failed after geometry handoff", outcome.failure());
                    }
                }
                WorkerResource.Completion<WorkerResult> completion = worker.resource.claim();
                if (completion == null) return;
                WorkerResource.Lease lease = completion.lease();
                WorkerResult result = completion.value();
                switch (result) {
                    case WorkerBootstrap boot -> {
                        this.metadata = boot.metadata();
                        this.bootstrapComplete = true;
                        this.interestChanges.addAll(this.demands.keySet());
                        if (this.worldIdentity == null && boot.hint() != null) this.changeWorld(boot.hint());
                        worker.releaseCompletion(lease);
                    }
                    case WorkerWorld world -> {
                        if (world.view() == this.viewRevision) this.cache = world.cache();
                        else world.cache().close();
                        worker.releaseCompletion(lease);
                    }
                    case WorkerSaved saved -> { this.savedMetadata(saved); worker.releaseCompletion(lease); }
                    case WorkerMetadata saved -> {
                        this.applyLocalIndex(saved);
                        worker.releaseCompletion(lease);
                    }
                    case WorkerMiss miss -> {
                        Demand demand = this.currentWorkerDemand(miss.ticket(), worker, lease);
                        if (demand != null) {
                            demand.workLease = null;
                            this.cacheReads++;
                            this.cacheMisses++;
                            var region = this.demands.region(demand.regionKey);
                            if (region != null) {
                                if (miss.fallback() == null) region.localSections.remove(demand.key);
                                else region.localSections.put(demand.key, miss.fallback());
                            }
                            demand.content = null; demand.networkWanted = false;
                            demand.candidate = SectionDemandTable.CandidateState.WAIT_REGION;
                            if (miss.fallback() == null) this.invalidateCachedCover(demand.key);
                            if (!this.bindAvailable(demand)) this.waitForNetwork(demand);
                            var top = this.demands.get(topAncestor(demand.key));
                            if (top != null && top.cachedCover) { top.cachedCover = false; this.bindAvailable(top); }
                        } else {
                            this.finishStaleWorker(miss.ticket(), worker, lease);
                        }
                        worker.releaseCompletion(lease);
                    }
                    case WorkerGeometry geometry -> {
                        Demand demand = this.currentWorkerDemand(geometry.ticket(), worker, lease);
                        if (demand == null) {
                            geometry.geometry().free();
                            this.finishStaleWorker(geometry.ticket(), worker, lease);
                            worker.releaseCompletion(lease);
                            return;
                        }
                        this.recordWorkerSource(geometry.cacheHit(), geometry.compressedBytes(),
                                true);
                        demand.candidateCacheHit = geometry.cacheHit();
                        // Once observed, the demand is the sole owner of the mesh buffer. The
                        // worker remains reserved until renderer admission completes, but must
                        // neither redeliver nor free a buffer that may already be uploading.
                        demand.workLease = lease;
                        this.completeGeometry(demand, geometry.geometry(),
                                geometry.completedNanos());
                        // The slot remains COMPLETED as the exact backpressure resource.
                    }
                    case WorkerFailure failed -> {
                        if (failed.task() instanceof BootstrapTask) {
                            this.metadataUnavailable = true; this.bootstrapComplete = true; this.interestChanges.addAll(this.demands.keySet());
                        }
                        if (failed.task() instanceof OpenWorldTask world && world.view() == this.viewRevision) {
                            this.metadataUnavailable = true;
                            for (var demand : List.copyOf(this.demands.values())) this.bindAvailable(demand);
                        }
                        Demand currentDemand = null;
                        SectionDemandTable.Ticket sectionTicket = null;
                        boolean stale = switch (failed.task()) {
                            case SectionWorkerTask section -> {
                                sectionTicket = section.ticket();
                                currentDemand = this.currentWorkerDemand(sectionTicket, worker, lease);
                                yield currentDemand == null;
                            }
                            case EmptyWorkerTask empty -> {
                                sectionTicket = empty.ticket();
                                currentDemand = this.currentWorkerDemand(sectionTicket, worker, lease);
                                yield currentDemand == null;
                            }
                            default -> true;
                        };
                        if (sectionTicket != null && stale) {
                            this.finishStaleWorker(sectionTicket, worker, lease);
                        }
                        if (!stale && failed.task() instanceof EmptyWorkerTask) {
                            currentDemand.workLease = null;
                            this.queueBound(currentDemand);
                        } else if (!stale && failed.task() instanceof SectionWorkerTask section) {
                            currentDemand.workLease = null;
                            this.demands.revise(currentDemand);
                            currentDemand.candidate =
                                    SectionDemandTable.CandidateState.READY_SOURCE;
                            if (section.source() == WorkerSource.CACHE) this.demands.ready(currentDemand, SectionDemandTable.ReadyKind.SOURCE);
                            else this.waitForNetwork(currentDemand);
                        }
                        worker.releaseCompletion(lease);
                        if (!stale) Logger.warn("Regional worker " + failed.slot()
                                + " failed; its last-known-good geometry remains active",
                                failed.failure());
                    }
                }
        }

        void waitForNetwork(Demand demand) {
            demand.networkWanted = true; demand.candidate = SectionDemandTable.CandidateState.WAIT_REGION;
            this.interestChanges.add(demand.key);
        }

        Demand currentWorkerDemand(SectionDemandTable.Ticket ticket, WorkerSlot worker,
                                   WorkerResource.Lease lease) {
            if (!this.open.get() || !this.demands.current(ticket) || ticket.resourceSlot() != worker.index) return null;
            Demand demand = this.demands.get(ticket.key());
            return demand != null && lease.equals(demand.workLease)
                    && worker.resource.matches(lease) ? demand : null;
        }

        void finishStaleWorker(SectionDemandTable.Ticket ticket, WorkerSlot worker, WorkerResource.Lease lease) {
            if (!this.open.get() || ticket.sessionEpoch() != this.id || ticket.resourceSlot() != worker.index
                    || !worker.resource.matches(lease)) return;
            Demand demand = this.demands.get(ticket.key());
            if (demand == null || !lease.equals(demand.workLease)) return;
            demand.workLease = null;
            if (demand.candidate != SectionDemandTable.CandidateState.READY_SOURCE) return;
            // Cancellation returns ownership, not authority to replace unfinished cached work.
            // Its deferred server binding is consumed after the retried operation activates.
            if (demand.content != null && demand.content.kind() != LocalSection.ABSENT) {
                this.queueBound(demand);
            } else {
                demand.content = null;
                demand.candidate = SectionDemandTable.CandidateState.WAIT_REGION;
                if (!this.bindAvailable(demand)) this.ensureRegion(demand.key);
            }
            ClientLodDebug.sectionRecovery(this, ticket, demand, lease);
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

        void recordCommitted(Demand demand, LocalSection content) {
            if (content == null) return;
            var region = this.demands.region(demand.regionKey);
            if (region == null) return;
            if (region.localSections.isEmpty()) region.localSections = new HashMap<>();
            region.localSections.put(content.key(), content);
        }

        void processWaitingModels() {
            // At most one retained cell array per real worker, no detached model-wait queue.
            for (WorkerSlot worker : this.sectionWorkers) {
                ModelWait wait = worker.modelWait;
                if (wait == null) continue;
                boolean current = this.demands.current(wait.task().ticket());
                boolean ready = current && this.mesher.modelsReady(wait.section());
                if (current) this.demands.get(wait.task().ticket().key()).candidate = ready
                        ? SectionDemandTable.CandidateState.WORKER_OWNED : SectionDemandTable.CandidateState.WAIT_MODELS;
                if (!current || ready) synchronized (worker) { worker.notifyAll(); }
            }
        }

        long retainedModelBytes() {
            long bytes = 0;
            for (WorkerSlot worker : this.sectionWorkers) {
                ModelWait wait = worker.modelWait;
                if (wait != null) bytes += (long) wait.section().cells().length * Long.BYTES
                        + (long) wait.section().usedBlocks().length * Integer.BYTES;
            }
            return bytes; // Array payload bytes only; object headers and codec/input scratch are separate.
        }

        static void freeWorkerResult(WorkerResult result) {
            if (result instanceof WorkerBootstrap boot) boot.metadata().close();
            if (result instanceof WorkerGeometry geometry) geometry.geometry().free();
            if (result instanceof WorkerWorld world) world.cache().close();
        }

        void scheduleSourceWork() {
            int remaining = this.demands.readyCount(SectionDemandTable.ReadyKind.SOURCE);
            while (remaining-- > 0) {
                Demand demand = this.demands.poll(SectionDemandTable.ReadyKind.SOURCE);
                if (demand == null) return;
                if (demand.candidate != SectionDemandTable.CandidateState.READY_SOURCE) continue;
                if (demand.content.kind() == LocalSection.EMPTY) {
                    if (!this.publishEmpty(demand)) {
                        this.demands.ready(demand, SectionDemandTable.ReadyKind.SOURCE);
                        return;
                    }
                    continue;
                }
                WorkerSlot worker = this.idleWorker(demand);
                if (worker == null) {
                    this.demands.ready(demand, SectionDemandTable.ReadyKind.SOURCE);
                    return;
                }
                WorkerSource source = WorkerSource.CACHE;
                SectionDemandTable.Ticket ticket = demand.ticket(this.id, worker.index);
                SectionWorkerTask task = new SectionWorkerTask(ticket, demand.content,
                        source, null, demand.catalog == null ? null : demand.catalog.mappings(), this.cache,
                        () -> this.open.get() && demand.revision == ticket.demandRevision());
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

        Demand replyDemand(NetworkReply handoff) {
            if (!this.helloAccepted || handoff.connectionEpoch != this.connectionEpoch
                    || handoff.backgroundEpoch != 0 && handoff.backgroundEpoch != this.backgroundEpoch) return null;
            var demand = this.demands.get(handoff.reply.key());
            return demand != null && demand.wireTicket == handoff.reply.ticket() ? demand : null;
        }
        void finishReply(NetworkReply handoff) { this.networkReplies.remove(handoff); handoff.transferred(); }
        void drainNetworkReplies() throws IOException {
            for (var handoff : this.networkReplies) {
                if (!this.helloAccepted && handoff.connectionEpoch == this.connectionEpoch) continue;
                var demand = this.replyDemand(handoff);
                if (demand == null) { this.finishReply(handoff); continue; }
                var reply = handoff.reply;
                if (handoff.backgroundEpoch != 0 && this.demands.readyCount(SectionDemandTable.ReadyKind.SOURCE) != 0) continue;
                if (reply.status() == RegionalProtocol.Status.NOT_READY) { this.finishReply(handoff); continue; }
                if (Long.compareUnsigned(reply.generation(), demand.regionGeneration) < 0) { this.finishReply(handoff); continue; }
                // The lane owns its one record while cache work/publication finishes. Never cancel cached work for refresh.
                if (demand.workLease != null || demand.completedGeometry != null
                        || demand.candidate == SectionDemandTable.CandidateState.RENDERER_OWNED
                        || demand.installed && demand.cacheActivatedFrame >= this.renderedFrames
                        && demand.activeContent.kind() != LocalSection.EMPTY) continue;
                var content = reply.content();
                if (!demand.networkWanted && content.sameContent(demand.activeContent)) {
                    demand.regionGeneration = reply.generation(); demand.content = content; demand.activeContent = content;
                    this.finishReply(handoff); continue;
                }
                var binding = content.kind() == LocalSection.DATA ? handoff.catalog : null;
                if (content.kind() == LocalSection.DATA && binding == null) continue;
                WorkerSlot worker = handoff.backgroundEpoch == 0 ? this.idleWorker(demand) : this.idleWorker();
                if (worker == null) continue;
                this.demands.unlinkReady(demand); this.demands.revise(demand);
                demand.content = content; demand.catalog = binding; demand.regionGeneration = reply.generation(); demand.networkWanted = false;
                var ticket = demand.ticket(this.id, worker.index);
                if (content.kind() != LocalSection.DATA) {
                    demand.workLease = worker.assign(new EmptyWorkerTask(ticket, (byte) content.children(), content, this.cache,
                            () -> this.open.get() && demand.revision == ticket.demandRevision()));
                } else {
                    boolean reuse = reply.status() == RegionalProtocol.Status.REUSE;
                    demand.workLease = worker.assign(new SectionWorkerTask(ticket, content,
                            reuse ? WorkerSource.CACHE : WorkerSource.NETWORK, reuse ? null : reply.compressed(), binding.mappings(), this.cache,
                            () -> this.open.get() && demand.revision == ticket.demandRevision()));
                }
                if (demand.workLease == null) throw new IllegalStateException("idle section worker rejected record");
                this.demands.owned(demand, SectionDemandTable.CandidateState.WORKER_OWNED);
                this.finishReply(handoff);
            }
        }

        boolean publishEmpty(Demand demand) {
            WorkerSlot worker = this.idleWorker(demand);
            if (worker == null) return false;
            var ticket = demand.ticket(this.id, worker.index);
            demand.workLease = worker.assign(new EmptyWorkerTask(ticket,
                    (byte) demand.content.children(), demand.content, this.cache,
                    () -> this.open.get() && demand.revision == ticket.demandRevision()));
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
                        demand.activeContent = demand.content;
                        demand.cacheActivatedFrame = demand.candidateCacheHit ? this.renderedFrames : -1;
                        this.interestChanges.add(demand.key);
                        this.coverageActivated(demand);
                        ClientLodDebug.sectionActivated(this, demand.activeContent, demand.candidateCacheHit);
                        if (demand.coverage) this.missingCoverage.remove(demand.key);
                        this.activated++;
                        ClientLodDebug.startupEvent(this, this.helloAccepted ? "activation" : "localActivation", 0);
                        this.uploadedSections++;
                        this.releaseRendererSlot(this.detachPublicationLease(ref));
                    }
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
                    && prerequisite.content != null) this.queueBound(prerequisite);
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

        String snapshot(String startupSummary) {
            if (Thread.currentThread() != this.thread) {
                throw new IllegalStateException("session summary must be captured by its owner");
            }
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
                    + " ownedNetworkRecords=" + this.networkReplies.size()
                    + " coalescedInputs=" + this.demands.pendingInputCount()
                    + " coalescedOverwritten=" + this.demands.overwrittenInputCount()
                    + " coverageMissing=" + this.missingCoverage.size()
                    + " sourceReady=" + this.demands.readyCount(
                            SectionDemandTable.ReadyKind.SOURCE)
                    + " interestChanges=" + this.interestChanges.size()
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
                    + startupSummary
                    + " connectionFailure=" + String.valueOf(this.lastConnectionFailure)
                    + " failure=" + String.valueOf(this.failure);
        }

        @Override public void close() {
            if (!this.open.getAndSet(false)) return;
            ++this.viewRevision;
            var cleanup = new me.cortex.voxy.common.util.Cleanup();
            if (this.connectionAttempt != null) cleanup.run(this.connectionAttempt::close);
            signal();
            this.thread.interrupt();
            cleanup.run(this::closeBackground);
            if (this.quic != null) cleanup.run(this.quic::close);
            if (this.catalogueProbe != null) this.catalogueProbe.cancel(true);
            if (this.catalogueProbeThread != null) cleanup.run(() -> me.cortex.voxy.common.util.Cleanup.join(this.catalogueProbeThread));
            cleanup.rethrow();
        }

        void awaitRendererQuiescence() {
            var cleanup = new me.cortex.voxy.common.util.Cleanup();
            cleanup.run(this::close);
            me.cortex.voxy.common.util.Cleanup.join(this.thread);
            // WorkerResource.CLOSED rejects/disposes results; only thread exit proves that
            // a cache/mesh/model operation has stopped using the renderer's dependencies.
            for (WorkerSlot worker : this.sectionWorkers) cleanup.run(worker::close);
            for (WorkerSlot worker : this.sectionWorkers) {
                me.cortex.voxy.common.util.Cleanup.join(worker.workerThread);
            }
            cleanup.rethrow();
        }

        void release() {
            var cleanup = new me.cortex.voxy.common.util.Cleanup();
            // Stop every renderer-using producer before callbacks can throw during cleanup.
            for (WorkerSlot worker : this.sectionWorkers) cleanup.run(worker::close);
            for (WorkerSlot worker : this.sectionWorkers) {
                me.cortex.voxy.common.util.Cleanup.join(worker.workerThread);
            }
            if (this.connectionAttempt != null) cleanup.run(this.connectionAttempt::close);
            cleanup.run(this.metadataWorker::close);
            this.clearPersistence();
            cleanup.run(this::closeBackground);
            if (this.quic != null) cleanup.run(this.quic::close);
            NetworkReply reply;
            while ((reply = this.networkReplies.poll()) != null) reply.transferred();
            NetworkCatalog incomingCatalog;
            while ((incomingCatalog = this.networkCatalogs.poll()) != null) incomingCatalog.transferred();
            cleanup.run(() -> this.publisher.clearProgressListener(this.rendererWake));
            for (PublicationRef ref : this.publicationQueue) {
                WorkerResource.Lease lease = this.detachPublicationLease(ref);
                cleanup.run(() -> ref.publication().abandon(() -> this.releaseRendererSlot(lease)));
            }
            this.publicationQueue.clear();
            if (this.cache != null) cleanup.run(this.cache::close);
            for (Demand demand : this.demands.values()) {
                cleanup.run(() -> this.discardCompletedGeometry(demand));
                if (demand.publication != null) cleanup.run(demand.publication::close);
                if (demand.previousPublication != null) cleanup.run(demand.previousPublication::close);
            }
            Event event;
            while ((event = this.events.poll()) != null) discardEvent(event);
            this.demands.clear();
            this.blockNames.clear();
            this.biomeNames.clear();
            this.resolvedNameCharacters = 0;
            this.interestChanges.clear(); this.interestDrops.clear(); this.frameInterests.clear(); this.coverDependents.clear();
            this.demandsByTop.clear();
            this.missingCoverage.clear();
            this.coarseningRoots.clear();
            this.rendererBlocked.clear();
            this.dormantRoots.clear();
            this.pendingDormantEvictions.clear();
            this.activeGeometryBytes = 0;
            this.dormantGeometryBytes = 0;
            this.pendingDormantEvictionBytes = 0;
            this.completedGeometryBytes = 0;
            this.publishingGeometryBytes = 0;
            cleanup.rethrow();
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

    private sealed interface Event permits Coarsened,
            CoarsenFailed, SessionObservation {}
    private record Coarsened(long parent, long view) implements Event {}
    private record CoarsenFailed(long parent, long view, Throwable failure) implements Event {}
    private record SessionObservation(Consumer<Session> receiver) implements Event {}

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
        if (id == null || !id.toString().equals(blockName) || !BuiltInRegistries.BLOCK.containsKey(id)) {
            throw new IllegalArgumentException("server catalog names an unavailable block: "
                    + blockName);
        }
        Block block = BuiltInRegistries.BLOCK.get(id);
        BlockState state = Objects.requireNonNull(block, "catalog block").defaultBlockState();
        if (bracket < 0) return state;
        String properties = canonical.substring(bracket + 1, canonical.length() - 1);
        if (properties.isEmpty()) throw new IllegalArgumentException("empty canonical property list");
        String previousProperty = "";
        int propertyCount = 0;
        for (String assignment : properties.split(",", -1)) {
            int equals = assignment.indexOf('=');
            if (equals <= 0 || equals == assignment.length() - 1) {
                throw new IllegalArgumentException("malformed canonical block property");
            }
            String propertyName = assignment.substring(0, equals);
            if (propertyName.compareTo(previousProperty) <= 0) throw new IllegalArgumentException("noncanonical property order");
            previousProperty = propertyName; propertyCount++;
            Property<?> property = state.getBlock().getStateDefinition()
                    .getProperty(assignment.substring(0, equals));
            if (property == null) throw new IllegalArgumentException(
                    "server catalog names an unavailable property: " + assignment);
            state = setProperty(state, property, assignment.substring(equals + 1));
        }
        if (propertyCount != block.getStateDefinition().getProperties().size())
            throw new IllegalArgumentException("incomplete canonical property list");
        return state;
    }

    private static <T extends Comparable<T>> BlockState setProperty(
            BlockState state, Property<T> property, String value) {
        return property.getValue(value).filter(parsed -> property.getName(parsed).equals(value)).map(parsed -> state.setValue(property, parsed))
                .orElseThrow(() -> new IllegalArgumentException(
                        "server catalog names an unavailable property value: " + value));
    }

    private static String requireCanonicalBiome(String name) {
        ResourceLocation id = ResourceLocation.tryParse(name);
        ClientLevel level = Minecraft.getInstance().level;
        if (id == null || !id.toString().equals(name) || level == null
                || !level.registryAccess().registryOrThrow(Registries.BIOME).containsKey(id)) {
            throw new IllegalArgumentException("server catalog names an unavailable biome: " + name);
        }
        return id.toString();
    }
}
