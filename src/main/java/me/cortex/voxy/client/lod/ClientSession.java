package me.cortex.voxy.client.lod;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.config.ServerDownloadSettings;
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
    private static volatile ConnectionOwner connectionOwner;
    /** Network/cache ownership follows the Minecraft connection, independently of the renderer. */
    private static final class ConnectionOwner implements AutoCloseable {
        final Object minecraftConnection;
        final ServerDownloadSettings policy;
        final RegionalConnectionAttempt.Connector connector;
        final AtomicLong tickets = new AtomicLong();
        RegionalConnectionAttempt attempt;
        volatile RegionalQuicClient quic;
        volatile long epoch;
        long retry;
        volatile boolean closed;
        private volatile Session attached;
        final Set<RegionalProtocol.ScopedKey> detachedDrops = new HashSet<>();
        boolean opened;
        RegionalProtocol.ServerHello hello;
        RegionalProtocol.Manifest manifest;
        RegionalProtocol.RegionInventory pendingInventory;
        WorldCacheDownloads downloads;

        ConnectionOwner(Session session) {
            this.minecraftConnection = session.minecraftConnection;
            this.policy = session.policy;
            this.connector = session.connector;
        }
        Session view() { var view = this.attached; return view != null && view.open.get() ? view : null; }
        synchronized Session awaitView(long epoch) throws InterruptedException {
            while (!this.closed && this.epoch == epoch) {
                var target = view();
                if (target != null) return target;
                this.wait();
            }
            return null;
        }
        synchronized boolean handoff(Session target, long epoch, Session.NetworkHandoff record) {
            if (this.closed || this.epoch != epoch || this.attached != target || !target.open.get()) return false;
            if (record instanceof Session.NetworkReply reply) target.networkReplies.add(reply);
            else target.networkCatalogs.add((Session.NetworkCatalog) record);
            target.signal(); return true;
        }
        synchronized boolean attach(Session view) {
            if (this.closed || this.attached != null && this.attached != view) return false;
            this.attached = view; this.notifyAll(); return true;
        }
        synchronized void detach(Session view) {
            if (this.attached != view) return;
            if (this.downloads != null) this.downloads.detach();
            if (view.dimensionId >= 0) for (var demand : view.demands.values())
                if (demand.wireTicket != 0) this.detachedDrops.add(new RegionalProtocol.ScopedKey(view.dimensionId, demand.key));
            this.attached = null; this.notifyAll();
        }
        void signal() { var view = view(); if (view != null) view.signal(); }
        boolean foregroundOwns(int dimensionId, long key) {
            var view = view();
            var demand = view != null && view.dimensionId == dimensionId ? view.demands.get(key) : null;
            return demand != null && (demand.networkWanted || demand.wireTicket != 0 || demand.content != null);
        }
        long ticket() { long result = this.tickets.incrementAndGet(); return result == 0 ? this.tickets.incrementAndGet() : result; }
        void connect(Session view) throws IOException {
            if (!attach(view)) return;
            if (this.quic == null) {
                if (!this.policy.available()) return;
                if (!ClientLodDebug.connectionAllowed() || this.connector == null) return;
                if (this.attempt == null) {
                    if (System.nanoTime() - this.retry < 0) return;
                    this.attempt = new RegionalConnectionAttempt(this.connector);
                }
                var outcome = this.attempt.poll();
                if (outcome == null) return;
                this.attempt.close(); this.attempt = null;
                this.retry = System.nanoTime() + RETRY_DELAY_NANOS;
                if (outcome.failure() != null) { view.lastConnectionFailure = outcome.failure(); return; }
                view.lastConnectionFailure = null;
                this.quic = outcome.connection(); if (this.epoch == 0) this.epoch = 1;
                long epoch = this.epoch;
                this.quic.setActivityListener(this::signal);
                this.quic.listen(new RegionalQuicClient.RecordReceiver() {
                    public void record(RegionalProtocol.SectionReply reply, RegionalSectionCodec.BoundCatalog catalog) throws InterruptedException {
                        var handoff = new Session.NetworkReply(epoch, reply, catalog);
                        while (true) {
                            var target = awaitView(epoch);
                            if (target == null) return;
                            if (handoff(target, epoch, handoff)) break;
                        }
                        handoff.awaitTransfer();
                    }
                    public RegionalSectionCodec.BoundCatalog catalog(RegionalProtocol.CatalogMessage message) throws Exception {
                        long start = System.nanoTime();
                        var decoded = Session.decodeCatalogue(message);
                        long elapsed = System.nanoTime() - start;
                        while (true) {
                            var target = awaitView(epoch);
                            if (target == null) throw new IOException("catalogue connection ended");
                            var handoff = new Session.NetworkCatalog(epoch, message, decoded, elapsed);
                            if (!handoff(target, epoch, handoff)) continue;
                            handoff.awaitTransfer();
                            if (handoff.binding != null) return handoff.binding;
                        }
                    }
                });
            }
            if (view.quic == this.quic) return;
            view.quic = this.quic; view.connectionEpoch = this.epoch; view.openSent = this.opened;
            if (view.currentCatalog != null) this.quic.remember(view.currentCatalog);
            if (this.hello != null && this.manifest != null) {
                var info = this.manifest.dimensions().stream().filter(d -> d.name().equals(view.dimension)).findFirst().orElse(null);
                if (info != null) view.acceptHello(new RegionalProtocol.ServerHello(this.hello.serverInstance(), info.id(), info.worldIdentity(), info.catalogId(), info.catalogFingerprint()));
            }
        }
        synchronized void interruptTransport() {
            if (this.attempt != null) this.attempt.close(); this.attempt = null;
            if (this.quic != null) this.quic.close();
        }
        synchronized void reset() {
            this.epoch++; this.notifyAll();
            if (this.attempt != null) this.attempt.close(); this.attempt = null;
            if (this.quic != null) this.quic.close(); this.quic = null;
            this.opened = false; this.hello = null; this.manifest = null;
            this.pendingInventory = null; this.detachedDrops.clear();
            if (this.downloads != null) this.downloads.reconnect();
            this.retry = System.nanoTime() + RETRY_DELAY_NANOS;
        }
        @Override public synchronized void close() {
            this.closed = true; this.epoch++; this.notifyAll();
            if (this.attempt != null) this.attempt.close(); this.attempt = null;
            if (this.quic != null) this.quic.close(); this.quic = null;
            if (this.downloads != null) this.downloads.close(); this.downloads = null;
        }
    }

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
            if (Minecraft.getInstance().getConnection() == null && connectionOwner != null) {
                connectionOwner.close(); connectionOwner = null;
            }
        }
    }

    /** Actual bytes across this server's dimensions, or -1 while ownership/inventory is unknown. */
    public static long cacheStorageUsedBytes(String serverId) {
        var session = active;
        if (session == null || !session.open.get() || session.policy == null
                || !session.policy.serverId().equals(serverId)) return -1;
        var metadata = session.metadata;
        return metadata == null ? -1 : metadata.budget.usedBytes(serverId);
    }

    public static void streamingSettingsChanged() {
        var session = active; if (session != null) session.signal();
    }
    private record VisibleCut(long epoch, long[] keys, float[] areas, int[] buckets) {}
    private record DownloadFrustum(float[] planes, double cameraX, double cameraY, double cameraZ) {}
    public static void visibleSections(VoxyRenderSystem renderer, long epoch, long[] keys, float[] areas, int[] buckets) {
        var session = active;
        if (session != null && session.renderer == renderer && session.open.get()) {
            session.visibleInput = new VisibleCut(epoch, keys, areas, buckets); session.signal();
        }
    }
    public static void downloadFrustum(VoxyRenderSystem renderer, float[] planes) {
        var session = active;
        if (session != null && session.renderer == renderer && session.open.get()) {
            var viewport = renderer.getViewport();
            session.downloadFrustum = new DownloadFrustum(planes, viewport.cameraX, viewport.cameraY, viewport.cameraZ);
        }
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
        VoxyRenderSystem.SectionPublication preservedPublication;
        LocalSection preservedContent;
        boolean networkWork;
        long wireTicket;
        int sentPurpose = -1;
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
        boolean childrenRequired;
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
        final Set<Long> missingInterests = new HashSet<>();
        final Set<Long> unactivatedRequired = new HashSet<>();
        final Set<Long> unavailableSourceRegions = new HashSet<>();
        Object debugOwnerTiming;
        boolean sourceSnapshotStarted;
        final Set<Long> emptyTopologyKeys = new HashSet<>();
        final Map<Long, LinkedHashSet<Long>> emptyTopologyDependents = new HashMap<>();
        final PendingInterests interestChanges = new PendingInterests();
        /** O(1) membership and reprioritization; no rescanning cached watches per control packet. */
        final class PendingInterests extends java.util.AbstractSet<Long> {
            final List<LinkedHashSet<Long>> buckets = List.of(new LinkedHashSet<>(), new LinkedHashSet<>(), new LinkedHashSet<>());
            final Map<Long, Integer> membership = new HashMap<>();
            @Override public boolean add(Long key) {
                var demand = demands.get(key);
                int bucket = demand != null && demand.networkWanted && downloadVisible(key) ? demand.coverage ? 0 : 1 : 2;
                Integer previous = membership.put(key, bucket);
                if (previous != null && previous == bucket) return false;
                if (previous != null) buckets.get(previous).remove(key);
                buckets.get(bucket).add(key); return previous == null;
            }
            @Override public boolean remove(Object key) {
                Integer bucket = membership.remove(key);
                return bucket != null && buckets.get(bucket).remove(key);
            }
            @Override public int size() { return membership.size(); }
            @Override public boolean contains(Object key) { return membership.containsKey(key); }
            @Override public void clear() { membership.clear(); buckets.forEach(Set::clear); }
            @Override public java.util.Iterator<Long> iterator() { return iterator(false); }
            java.util.Iterator<Long> iterator(boolean urgentOnly) {
                return new java.util.Iterator<>() {
                    int bucket; long key; java.util.Iterator<Long> current = buckets.get(0).iterator();
                    public boolean hasNext() {
                        while (!current.hasNext() && bucket < (urgentOnly ? 1 : 2)) current = buckets.get(++bucket).iterator();
                        return current.hasNext();
                    }
                    public Long next() { if (!hasNext()) throw new java.util.NoSuchElementException(); return key = current.next(); }
                    public void remove() { current.remove(); membership.remove(key); }
                };
            }
        }
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
        ConnectionOwner networkOwner;
        Object minecraftConnection;
        ServerDownloadSettings policy;
        int dimensionId = -1;
        RegionalConnectionAttempt.Connector connector;
        boolean helloAccepted;
        boolean openSent, bootstrapComplete;
        volatile long renderedFrames;
        long sentBandwidthKbps = -1;
        RegionalProtocol.ServerHello welcome;
        boolean sentRefreshAllowed;
        int sentDimensionId = -1;
        int sentAnchorX = Integer.MIN_VALUE, sentAnchorZ = Integer.MIN_VALUE;
        long sentStorageBytes = -1;
        volatile int cameraBlockX, cameraBlockZ;
        volatile VisibleCut visibleInput;
        volatile DownloadFrustum downloadFrustum;
        DownloadFrustum classifiedFrustum;
        long visibleEpoch = -1;
        final VisibleSectionState visibility = new VisibleSectionState();
        final Set<Long> visibleWatchKeys = this.visibility.watchKeys();
        final Set<Long> visibleRegions = this.visibility.regions();
        int retentionX = Integer.MIN_VALUE, retentionZ = Integer.MIN_VALUE;
        long retentionEpoch = -1, retentionView = -1;
        float[] visibleAreas = new float[0];
        long[] visibleCutKeys = new long[0];
        Path cacheRoot;
        String serverKey;
        volatile RegionalMetadataStore metadata;
        volatile long viewRevision;
        final java.util.concurrent.ConcurrentHashMap<String, Integer> blockNames = new java.util.concurrent.ConcurrentHashMap<>();
        final java.util.concurrent.ConcurrentHashMap<String, Integer> biomeNames = new java.util.concurrent.ConcurrentHashMap<>();
        volatile long resolvedNameCharacters;
        final java.util.concurrent.ConcurrentHashMap<RegionalProtocol.Hash32,
                RegionalSectionCodec.BoundCatalog> savedMappings =
                new java.util.concurrent.ConcurrentHashMap<>();
        volatile AssociationTask associationIntent;
        Object blockedMetadataGoal;
        long blockedMetadataGeneration = Long.MIN_VALUE;
        boolean metadataRetryOnAdmission;
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
            final long connectionEpoch;
            final RegionalProtocol.SectionReply reply;
            final RegionalSectionCodec.BoundCatalog catalog;
            NetworkReply(long connection, RegionalProtocol.SectionReply reply, RegionalSectionCodec.BoundCatalog catalog) {
                this.connectionEpoch = connection; this.reply = reply; this.catalog = catalog;
            }
        }
        private static final class NetworkCatalog extends NetworkHandoff {
            final long connection;
            final RegionalProtocol.CatalogMessage message;
            final CatalogCodec.Catalog decoded;
            final long validationNanos;
            volatile RegionalSectionCodec.BoundCatalog binding;
            NetworkCatalog(long connection, RegionalProtocol.CatalogMessage message,
                           CatalogCodec.Catalog decoded, long validationNanos) {
                this.connection = connection; this.message = message;
                this.decoded = decoded; this.validationNanos = validationNanos;
            }
        }
        private record CachedCatalogue(long view, RegionalProtocol.Hash32 world,
                                       RegionalProtocol.CatalogMessage message, CatalogCodec.Catalog decoded,
                                       long validationNanos) {}
        private record CatalogueSave(long view, RegionalProtocol.Hash32 world,
                                     RegionalProtocol.CatalogMessage message) {}

        enum WorkerSource { CACHE, NETWORK }
        sealed interface WorkerTask permits SectionWorkerTask, EmptyWorkerTask, CacheOnlyTask,
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
                WorkerMetadata, WorkerSaved, WorkerCached {}
        record CacheOnlyTask(WorldCacheDownloads owner, WorldCacheDownloads.Job job,
                                     RegionalProtocol.SectionReply reply, RegionalSectionCodec.BoundCatalog catalog) implements WorkerTask {}
        private record WorkerCached(CacheOnlyTask task, long incarnation) implements WorkerResult {}
        record BootstrapTask(Path root, String server, String dimension) implements WorkerTask {}
        private record OpenWorldTask(long view, RegionalProtocol.Hash32 world) implements WorkerTask {}
        private record AssociationTask(long view, RegionalProtocol.Hash32 world) {}
        private record LoadMetadataTask(long view, long region, long revision,
                                        RegionalProtocol.Hash32 world, CompletedSectionCache cache,
                                        SectionDemandTable.RegionDemand owner) implements WorkerTask {}
        private record PersistTask(Object intent, CompletedSectionCache cache, long stamp, long admissionGeneration,
                                   java.util.function.BooleanSupplier current) implements WorkerTask {}
        private record WorkerBootstrap(RegionalMetadataStore metadata, RegionalProtocol.Hash32 hint)
                implements WorkerResult {}
        private record WorkerWorld(long view, CompletedSectionCache cache) implements WorkerResult {}
        private record WorkerMetadata(LoadMetadataTask task, Map<Long, LocalSection> sections, long incarnation) implements WorkerResult {}
        private record WorkerSaved(PersistTask task, RegionalMetadataStore.Persistence outcome, String reason,
                                   boolean retryOnAdmission) implements WorkerResult {}
        private record WorkerMiss(SectionDemandTable.Ticket ticket, boolean corrupt, LocalSection fallback, long incarnation)
                implements WorkerResult {}
        private record SaveInput(WorkerResource.Lease lease, SectionDemandTable.Ticket ticket,
                                 LocalSection content, CompletedSectionCache cache, byte[] canonical,
                                 CatalogCodec.Source source, java.util.function.BooleanSupplier current) {}
        private record SaveOutcome(WorkerResource.Lease lease, SectionDemandTable.Ticket ticket,
                                   LocalSection content, boolean committed, long incarnation, Throwable failure) {}
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
            // Diagnostic timestamps belong to this slot's current exact lease.
            private long ownerClaimedNanos, admissionObservedNanos;
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
                this.ownerClaimedNanos = this.admissionObservedNanos = 0;
                this.task = Objects.requireNonNull(task);
                this.sectionOperation = task instanceof SectionWorkerTask || task instanceof EmptyWorkerTask;
                this.operationKey = switch (task) {
                    case SectionWorkerTask section -> section.ticket().key();
                    case EmptyWorkerTask empty -> empty.ticket().key();
                    default -> 0;
                };
                this.taskLease = lease;
                ClientLodDebug.workerAssigned(this.debugWork, task, lease);
                this.notifyAll();
                return lease;
            }
            boolean idle() { return this.resource.state() == WorkerResource.State.IDLE; }
            /** Debug caller is the owner. State is a point observation, not elapsed occupancy. */
            int diagnosticSlotKind() {
                synchronized (this.resource) {
                    var state = this.resource.state();
                    if (state == WorkerResource.State.IDLE) return 0;
                    if (state == WorkerResource.State.CLOSED) return 9;
                    if (state == WorkerResource.State.RUNNING)
                        return this.nameWait != null ? 2 : this.modelWait != null ? 3 : 1;
                    if (this.resource.pendingResult() != null) return 4;
                    if (this.resource.releaseRequested() && this.resource.savePending()) return 7;
                    Demand demand = demands.get(this.operationKey);
                    if (demand != null && this.resource.matches(demand.workLease)) {
                        if (demand.completedGeometry != null) return 5;
                        if (demand.candidate == SectionDemandTable.CandidateState.RENDERER_OWNED) return 6;
                    }
                    return 8;
                }
            }
            void releaseCompletion(WorkerResource.Lease lease) {
                if (this.resource.release(lease)) this.reusable(lease);
            }
            private void reusable(WorkerResource.Lease lease) {
                ClientLodDebug.workerReusable(this.debugWork, lease);
                if (this.admissionObservedNanos != 0 && Thread.currentThread() == Session.this.thread) {
                    ClientLodDebug.handoff(Session.this, 4,
                            ClientLodDebug.publicationClock() - this.admissionObservedNanos);
                }
                this.ownerClaimedNanos = this.admissionObservedNanos = 0;
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
                                case CacheOnlyTask task -> {
                                    var content = task.reply().content();
                                    var cache = task.job().dimension.cache;
                                    java.util.function.BooleanSupplier current = () -> task.owner().current(task.job());
                                    ClientLodDebug.workerStage(this.debugWork, "CACHE_ONLY_VALIDATE");
                                    byte[] canonical = null;
                                    CatalogCodec.Source source = null;
                                    if (content.kind() == LocalSection.DATA) {
                                        if (task.reply().status() == RegionalProtocol.Status.REUSE || task.catalog() == null)
                                            throw new IOException("missing canonical cache-only section payload");
                                        if (RegionalProtocol.crc32c(task.reply().compressed()) != content.crc()) throw new IOException("prefetch CRC mismatch");
                                        canonical = this.codec.decompress(task.reply().compressed(), content.canonicalBytes());
                                        this.codec.validate(content.key(), content.children(), canonical, content.fingerprint(), task.catalog().mappings());
                                        source = task.catalog().mappings().source();
                                    }
                                    ClientLodDebug.workerStage(this.debugWork, "CACHE_ONLY_COMMIT");
                                    long incarnation = cache.save(content, this.localCodec, canonical, source, current, this.debugWork);
                                    yield new WorkerCached(task, incarnation);
                                }
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
                                    try {
                                        if (policy != null) store.bindServer(policy, bootstrap.server());
                                        hint = store.world(bootstrap.server(), bootstrap.dimension());
                                    }
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
                            ClientLodDebug.workerCompleted(this.debugWork, lease);
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
                    try { section = task.cache() == null ? null : task.cache().get(task.content(), this.localCodec, names, this.debugWork); }
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
                int prior = ClientLodDebug.workerPush(this.debugWork, "NAME_RESOLUTION_WAIT");
                try {
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
                } finally { ClientLodDebug.workerPop(this.debugWork, prior); }
            }

            private void save() {
                var input = this.saveInput;
                boolean committed = false;
                long incarnation = 0;
                Throwable failure = null;
                try {
                    incarnation = input.cache().save(input.content(), this.localCodec, input.canonical(), input.source(), input.current(), this.debugWork);
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
                    this.saveOutcome = new SaveOutcome(input.lease(), input.ticket(), input.content(), committed, incarnation, failure);
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
                long incarnation = 0;
                if (corrupt) ClientLodDebug.workerStage(this.debugWork, "CACHE_QUARANTINE");
                ClientLodDebug.workerOutcome(this.debugWork, corrupt ? "CACHE_CORRUPT" : "CACHE_MISS", 0);
                if (task.cache() != null) try {
                    var previous = task.cache().previous(task.content());
                    fallback = previous.section(); incarnation = previous.incarnation();
                }
                catch (IOException invalid) { }
                return new WorkerMiss(task.ticket(), corrupt, fallback, incarnation);
            }

            private WorkerResult loadMetadata(LoadMetadataTask task) {
                try {
                    task.cache().retain(task.region());
                    var directory = task.cache().snapshotDirectory(task.region());
                    return new WorkerMetadata(task, directory.sections(), directory.incarnation());
                } catch (IOException invalid) { return new WorkerMetadata(task, new HashMap<>(), task.cache().incarnation(task.region())); }
            }

            private WorkerSaved persist(PersistTask task) {
                var outcome = RegionalMetadataStore.Persistence.OBSOLETE;
                String reason = task.intent().getClass().getSimpleName() + "-refused";
                boolean retryOnAdmission = true;
                try {
                    var current = task.current();
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
                    retryOnAdmission = optional instanceof RegionalDiskBudget.Capacity || RegionalDiskBudget.outOfSpace(optional);
                }
                return new WorkerSaved(task, outcome, reason, retryOnAdmission);
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
            this.cacheRoot = ClientLodDebug.cacheRoot(minecraft.gameDirectory.toPath().resolve(".voxy").resolve("regional"));
            var server = minecraft.getCurrentServer();
            // Integrated worlds without a persistent logical identity do not use optimistic lookup.
            this.serverKey = server == null ? null : server.ip;
            this.minecraftConnection = listener == null ? null : listener.getConnection();
            if (this.serverKey != null && (connectionOwner == null
                    || connectionOwner.minecraftConnection != this.minecraftConnection))
                ServerDownloadSettings.reloadUnavailable();
            this.policy = this.serverKey == null ? null : ServerDownloadSettings.forServer(this.serverKey);
            this.connector = () -> QuicEndpointDiscovery.connect(listener, this.policy == null ? ServerDownloadSettings.DEFAULT_KBPS : this.policy.downloadKbps());
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
            var player = Minecraft.getInstance().player;
            if (player != null) {
                this.cameraBlockX = (int) Math.floor(player.getX()); this.cameraBlockZ = (int) Math.floor(player.getZ());
            }
        }

        void acceptDetailAction(long key, int action, int bucket, int epoch) {
            if (action != HierarchicalOcclusionTraverser.ACTION_REFINE
                    && action != HierarchicalOcclusionTraverser.ACTION_DORMANT
                    && action != HierarchicalOcclusionTraverser.ACTION_WAKE) return;
            this.demands.offerDetail(key, action, bucket, epoch);
            this.signal();
        }

        void run() {
            Object ownerTiming = null;
            try {
                ownerTiming = ClientLodDebug.ownerCreated(this);
                this.debugOwnerTiming = ownerTiming;
                while (this.open.get()) {
                    ClientLodDebug.ownerTurn(ownerTiming);
                    try {
                        ClientLodDebug.ownerPhase(ownerTiming, 0); // CONNECT
                        this.connect();
                        ClientLodDebug.ownerPhase(ownerTiming, 1); // WINDOW
                        this.reconcileWindow();
                        ClientLodDebug.ownerPhase(ownerTiming, 2); // WORKERS
                        this.drainWorkers();
                        ClientLodDebug.ownerPhase(ownerTiming, 3); // CONTROLS
                        if (this.quic != null) this.drainControls();
                        ClientLodDebug.ownerPhase(ownerTiming, 4); // NETWORK_REPLIES
                        this.drainNetworkReplies();
                        ClientLodDebug.ownerPhase(ownerTiming, 5); // EVENTS
                        this.drainEvents();
                        ClientLodDebug.ownerPhase(ownerTiming, 6); // DEMAND
                        this.drainDemand();
                        ClientLodDebug.ownerPhase(ownerTiming, 7); // METADATA
                        this.processMetadata();
                        ClientLodDebug.ownerPhase(ownerTiming, 8); // CATALOGS
                        this.drainNetworkCatalogs();
                        ClientLodDebug.ownerPhase(ownerTiming, 9); // REGIONS
                        this.processRegions();
                        ClientLodDebug.ownerPhase(ownerTiming, 10); // DOWNLOADS
                        this.processCacheDownloads();
                        ClientLodDebug.ownerPhase(ownerTiming, 11); // PUBLICATIONS
                        this.pollPublications();
                        ClientLodDebug.ownerPhase(ownerTiming, 12); // STAGES
                        this.processStages();
                        ClientLodDebug.ownerPhase(ownerTiming, 13); // DEBUG_SAMPLE
                        ClientLodDebug.captureSession(this);
                        ClientLodDebug.ownerPhase(ownerTiming, 14); // HEALTH
                        if (this.quic != null && !this.quic.isOpen()) {
                            throw new IOException("regional QUIC connection ended",
                                    this.quic.failure());
                        }
                    } catch (IOException failure) {
                        ClientLodDebug.ownerPhase(ownerTiming, 15); // RESET
                        if (this.open.get()) this.resetConnection(failure);
                    }
                    ClientLodDebug.ownerPhase(ownerTiming, 16); // WAIT
                    this.awaitWake(10);
                }
            } catch (InterruptedException interrupted) {
                ClientLodDebug.ownerPhase(ownerTiming, 15); // RESET
                Thread.currentThread().interrupt();
            } catch (Throwable failure) {
                ClientLodDebug.ownerPhase(ownerTiming, 15); // RESET
                if (this.open.get()) {
                    this.failure = failure;
                    Logger.warn("Regional Voxy session stopped", failure);
                }
            } finally {
                ClientLodDebug.ownerFinished(ownerTiming);
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
            if (this.policy == null) return;
            synchronized (LIFECYCLE) {
                if (active != this || !this.open.get()) return;
                if (connectionOwner == null || connectionOwner.minecraftConnection != this.minecraftConnection) {
                    if (connectionOwner != null) connectionOwner.close();
                    connectionOwner = new ConnectionOwner(this);
                }
                this.networkOwner = connectionOwner;
            }
            this.networkOwner.connect(this);
            if (this.networkOwner.view() != this) return;
            if (this.metadata != null && this.networkOwner.downloads == null) {
                this.networkOwner.downloads = new WorldCacheDownloads(this.policy, this.metadata, this.networkOwner::signal, this.networkOwner::foregroundOwns);
                if (this.networkOwner.manifest != null) this.networkOwner.downloads.manifest(this.networkOwner.manifest);
            }
        }

        void interruptTransport() {
            if (this.networkOwner != null) this.networkOwner.interruptTransport();
            this.signal();
        }
        void resetConnection(Throwable cause) {
            this.lastConnectionFailure = cause;
            this.reconnects++;
            Logger.warn("Regional Voxy connection failed; preserving installed geometry and "
                    + "reconnecting", cause);
            RegionalQuicClient previous = this.quic;
            this.quic = null;
            this.helloAccepted = false;
            if (this.networkOwner != null) this.networkOwner.reset();
            else if (previous != null) previous.close();
            this.openSent = false;
            this.welcome = null;
            this.sourceSnapshotStarted = false;
            this.unavailableSourceRegions.clear();
            this.savedMappings.clear();
            if (this.currentCatalog != null) this.savedMappings.put(this.currentCatalog.fingerprint(), this.currentCatalog);
            this.interestDrops.clear();
            this.interestChanges.clear();
            this.frameInterests.clear(); this.coverDependents.clear();
            for (Demand demand : this.demands.values()) {
                demand.wireTicket = 0; demand.regionGeneration = 0;
                this.invalidateNetworkCandidate(demand); this.interestChanges.add(demand.key);
                if (!demand.installed && demand.content == null && demand.preservedPublication == null) this.bindAvailable(demand);
                if (demand.cachedCover && demand.cachedCutPending != null) {
                    var pending = demand.cachedCutPending.iterator();
                    while (pending.hasNext()) {
                        long key = pending.next(); var source = this.demands.get(key);
                        if (source != null && source.installed) {
                            pending.remove(); demand.cachedCoverActivatedFrame = Math.max(demand.cachedCoverActivatedFrame, source.cacheActivatedFrame);
                        } else this.coverDependents.computeIfAbsent(key, ignored -> new LinkedHashSet<>()).add(demand.key);
                    }
                }
            }
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
            if (this.networkOwner.pendingInventory != null) {
                if (this.networkOwner.downloads == null) return;
                this.applyInventory(this.networkOwner.pendingInventory); this.networkOwner.pendingInventory = null;
            }
            while (true) {
                var control = this.quic.pollControl();
                if (control == null) return;
                switch (control) {
                    case RegionalProtocol.ServerHello hello -> this.acceptHello(hello);
                    case RegionalProtocol.Manifest manifest -> {
                        this.networkOwner.manifest = manifest;
                        if (this.networkOwner.downloads != null) this.networkOwner.downloads.manifest(manifest);
                    }
                    case RegionalProtocol.RegionInventory inventory -> {
                        if (this.networkOwner.downloads == null) { this.networkOwner.pendingInventory = inventory; return; }
                        this.applyInventory(inventory);
                    }
                    case RegionalProtocol.ServerError error -> throw new IOException("Voxy server error " + error.code() + ": " + error.message());
                    case RegionalProtocol.ServerShutdown shutdown -> throw new IOException(shutdown.message());
                    default -> throw new IOException("unexpected primary control frame");
                }
            }
        }
        private void applyInventory(RegionalProtocol.RegionInventory inventory) {
            this.networkOwner.downloads.inventory(inventory);
            if (inventory.dimensionId() != this.dimensionId) return;
            if (inventory.state() == RegionalProtocol.InventoryState.SNAPSHOT_BEGIN) {
                if (this.sourceSnapshotStarted) {
                    this.unavailableSourceRegions.clear();
                    for (var region : List.copyOf(this.demands.regions())) this.invalidateRegionWire(region.key);
                }
                this.sourceSnapshotStarted = true;
            } else if (inventory.state() == RegionalProtocol.InventoryState.REMOVED_REGION) {
                long region = regionKey(inventory.regionX(), inventory.regionZ());
                this.unavailableSourceRegions.add(region); this.invalidateRegionWire(region);
            } else if (inventory.state().saved()) {
                long key = regionKey(inventory.regionX(), inventory.regionZ());
                boolean reappeared = this.unavailableSourceRegions.remove(key);
                var region = this.demands.region(key);
                if (region != null) for (long member : region.members.keySet()) {
                    Demand demand = this.demands.get(member);
                    if (!demand.installed && (reappeared || demand.content == null && !demand.cachedCover)) {
                        this.unactivatedRequired.add(demand.key);
                        if (demand.content == null && !this.bindAvailable(demand)) this.ensureRegion(demand.key);
                    }
                    if (reappeared) this.interestChanges.add(demand.key);
                }
            } else if (inventory.state() == RegionalProtocol.InventoryState.SNAPSHOT_COMPLETE
                    && this.networkOwner.downloads.inventoryKnown(this.dimensionId)) {
                for (var region : List.copyOf(this.demands.regions()))
                    if (this.networkOwner.downloads.absentRegion(this.dimensionId, region.key)
                            && this.unavailableSourceRegions.add(region.key)) this.invalidateRegionWire(region.key);
            }
        }
        private void invalidateRegionWire(long key) {
            var region = this.demands.region(key); if (region == null) return;
            for (long member : region.members.keySet()) {
                Demand demand = this.demands.get(member);
                this.dropInterest(demand); demand.regionGeneration = 0;
                this.invalidateNetworkCandidate(demand);
                if (this.unavailableSourceRegions.contains(key)) {
                    this.networkWanted(demand, false); this.unactivatedRequired.remove(demand.key);
                } else {
                    if (!demand.installed && demand.content == null && demand.preservedPublication == null) this.bindAvailable(demand);
                    this.interestChanges.add(demand.key);
                }
            }
        }
        private void invalidateNetworkCandidate(Demand demand) {
            if (!demand.networkWork) {
                if (demand.candidate == SectionDemandTable.CandidateState.NETWORK_OWNED) {
                    this.demands.revise(demand); demand.candidate = SectionDemandTable.CandidateState.WAIT_REGION;
                }
                return;
            }
            if (demand.candidate == SectionDemandTable.CandidateState.RENDERER_OWNED) {
                demand.preservedPublication = demand.publication; demand.preservedContent = demand.content;
                demand.publication = demand.previousPublication; demand.previousPublication = null;
            }
            this.demands.revise(demand); this.discardCompletedGeometry(demand);
            this.releasePublishingGeometryAccounting(demand); this.rendererBlocked.remove(demand.key);
            demand.networkWork = false; demand.content = demand.activeContent; demand.catalog = null;
            demand.candidate = demand.content == null ? SectionDemandTable.CandidateState.WAIT_REGION : SectionDemandTable.CandidateState.NONE;
            if (demand.preservedPublication == null && !demand.installed && !this.bindAvailable(demand)) this.ensureRegion(demand.key);
        }
        void acceptHello(RegionalProtocol.ServerHello hello) {
            this.helloAccepted = true;
            this.dimensionId = hello.activeDimensionId();
            if (!hello.worldIdentity().equals(this.worldIdentity)) this.changeWorld(hello.worldIdentity());
            this.welcome = hello;
            if (this.networkOwner != null && this.networkOwner.hello == null) this.networkOwner.hello = hello;
            this.associationPending = true;
            this.associationIntent = new AssociationTask(this.viewRevision, this.worldIdentity);
            ClientLodDebug.startupEvent(this, "hello", 0);
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
            if (!cached && (message.dimensionId() != this.dimensionId || !message.worldIdentity().equals(this.worldIdentity))) {
                if (this.networkOwner == null || this.networkOwner.downloads == null) throw new IOException("catalogue has no scoped cache owner");
                var binding = this.networkOwner.downloads.catalogue(message, decoded);
                this.savedMappings.put(binding.fingerprint(), binding);
                if (this.quic != null) this.quic.remember(binding);
                return binding;
            }
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
                if (handoff.connection != this.connectionEpoch) {
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
            ClientLodDebug.ownerDetail(this.debugOwnerTiming, 0);
            if (this.resetRequested.getAndSet(false)) {
                for (long key : List.copyOf(this.demands.keySet())) this.retireDemand(key);
                this.dormantRoots.clear();
                this.dormantGeometryBytes = 0;
                this.demands.clear();
            }
            ClientLodDebug.ownerDetail(this.debugOwnerTiming, 1);
            this.demands.drainTop((key, add) -> {
                ClientLodDebug.ownerEvent(this.debugOwnerTiming, add ? 0 : 1, 1);
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
            ClientLodDebug.ownerDetail(this.debugOwnerTiming, 2);
            @SuppressWarnings("unchecked")
            ArrayDeque<DetailEvent>[] buckets = new ArrayDeque[
                    HierarchicalOcclusionTraverser.DETAIL_BUCKET_COUNT];
            for (int bucket = 0; bucket < buckets.length; bucket++) {
                buckets[bucket] = new ArrayDeque<>();
            }
            this.demands.drainDetail((key, update) -> buckets[update.bucket()].addLast(
                    new DetailEvent(key, update.action(), update.epoch())));
            ClientLodDebug.ownerDetail(this.debugOwnerTiming, 3);
            for (int bucket = 0; bucket < buckets.length; bucket++) {
                int retained = buckets[bucket].size();
                while (retained-- > 0) {
                    DetailEvent event = buckets[bucket].removeFirst();
                    if (event.action == HierarchicalOcclusionTraverser.ACTION_DORMANT) {
                        ClientLodDebug.ownerEvent(this.debugOwnerTiming, 2, 1);
                        this.markDormant(event.key, bucket, event.epoch);
                    } else if (event.action == HierarchicalOcclusionTraverser.ACTION_WAKE) {
                        ClientLodDebug.ownerEvent(this.debugOwnerTiming, 3, 1);
                        this.wakeDormant(event.key, event.epoch);
                    } else {
                        buckets[bucket].addLast(event);
                    }
                }
            }
            ClientLodDebug.ownerDetail(this.debugOwnerTiming, 4);
            this.evictDormant(this.dormantGeometryBytes - this.dormantCapBytes(), false);
            ClientLodDebug.ownerDetail(this.debugOwnerTiming, 5);
            for (int bucket = HierarchicalOcclusionTraverser.DETAIL_BUCKET_COUNT - 1;
                 bucket >= 0; bucket--) {
                ArrayDeque<DetailEvent> pending = buckets[bucket];
                while (!pending.isEmpty()) {
                    DetailEvent event = pending.removeFirst();
                    ClientLodDebug.ownerEvent(this.debugOwnerTiming, 4, 1);
                    long parent = event.key;
                    int epoch = event.epoch;
                    Demand demand = this.demands.get(parent);
                    if (demand == null || !demand.installed
                            || SectionKey.level(parent) == 0 || this.isCoarsening(parent)
                            || !newerEpoch(epoch, demand.latestRefinementEpoch)) {
                        ClientLodDebug.ownerEvent(this.debugOwnerTiming, 5, 1); continue;
                    }
                    if (demand.activeContent == null) {
                        ClientLodDebug.ownerEvent(this.debugOwnerTiming, 6, 1);
                        this.ensureRegion(parent);
                        this.demands.offerDetail(parent,
                                HierarchicalOcclusionTraverser.ACTION_REFINE, bucket, epoch);
                        continue;
                    }
                    this.demands.setPriority(demand, bucket);
                    if (!this.addChildren(parent, bucket)) {
                        ClientLodDebug.ownerEvent(this.debugOwnerTiming, 7, 1);
                        this.demands.offerDetail(parent,
                                HierarchicalOcclusionTraverser.ACTION_REFINE, bucket, epoch);
                        break;
                    }
                    demand.latestRefinementEpoch = epoch;
                    ClientLodDebug.ownerEvent(this.debugOwnerTiming, 8, 1);
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
            ClientLodDebug.ownerEvent(this.debugOwnerTiming, 19, owned == null ? 0 : owned.size());
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
            ClientLodDebug.ownerEvent(this.debugOwnerTiming, 20, this.dormantRoots.size());
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
            ClientLodDebug.ownerEvent(this.debugOwnerTiming, 21, owned == null ? 0 : owned.size());
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
            var parentDemand = this.demands.get(parent);
            if (parentDemand != null) {
                parentDemand.childrenRequired = false;
                this.topologyChanged(parentDemand);
            }
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
            demand.childrenRequired = false;
            this.topologyChanged(demand);
            this.emptyTopology(demand, false);
            this.missingInterests.remove(key);
            this.unactivatedRequired.remove(key);
            demand.preservedPublication = null; demand.preservedContent = null;
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
            if (this.networkOwner != null && this.networkOwner.downloads != null
                    && this.networkOwner.downloads.absentRegion(this.dimensionId, regionFor(key)))
                this.unavailableSourceRegions.add(regionFor(key));
            Demand existing = this.demands.get(key);
            if (existing != null) {
                if (!existing.installed && !this.unavailableSourceRegions.contains(existing.regionKey)) this.unactivatedRequired.add(key);
                this.demands.setPriority(existing, bucket);
                return;
            }
            Demand demand = this.demands.adopt(new Demand(key));
            if (!this.unavailableSourceRegions.contains(demand.regionKey)) this.unactivatedRequired.add(key);
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
            demand.childrenRequired = true;
            int childMask = (demand.activeContent == null ? demand.content : demand.activeContent).children();
            for (int child = 0; child < 8; child++) {
                if ((childMask & 1 << child) == 0) continue;
                long key = child(parent, child);
                this.addDemand(key, bucket);
            }
            this.topologyChanged(demand);
            return true;
        }

        /** Empty meshes still own required terrain; drawing no faces must not drop their updates. */
        private void emptyTopology(Demand demand, boolean empty) {
            if (empty ? !this.emptyTopologyKeys.add(demand.key) : !this.emptyTopologyKeys.remove(demand.key)) return;
            long ancestor = demand.key;
            while (SectionKey.level(ancestor) < SectionKey.MAX_LOD_LAYER) {
                ancestor = parent(ancestor);
                if (empty) this.emptyTopologyDependents.computeIfAbsent(ancestor, ignored -> new LinkedHashSet<>()).add(demand.key);
                else removeOwned(this.emptyTopologyDependents, ancestor, demand.key);
            }
        }
        private void topologyChanged(Demand demand) {
            var empty = this.emptyTopologyDependents.get(demand.key);
            if (empty != null) this.interestChanges.addAll(empty);
        }
        private boolean requiredEmptyWatch(Demand demand) {
            if (!this.emptyTopologyKeys.contains(demand.key) || !this.downloadVisible(demand.key) || this.isCoarsening(demand.key)) return false;
            var child = demand;
            while (!child.coverage) {
                var owner = this.demands.get(parent(child.key));
                int index = (SectionKey.x(child.key) & 1) | (SectionKey.z(child.key) & 1) << 1 | (SectionKey.y(child.key) & 1) << 2;
                if (owner == null || !owner.childrenRequired || owner.activeContent == null
                        || (owner.activeContent.children() & 1 << index) == 0) return false;
                child = owner;
            }
            return this.hasTop(child.key);
        }

        /** Local bindings are complete identities, independent of the connection or server index. */
        boolean bindAvailable(Demand demand) {
            var region = this.demands.region(demand.regionKey);
            if (region == null) return false;
            this.refreshLocalIncarnation(region);
            if (this.worldIdentity != null && !region.localLoaded && !this.metadataUnavailable && this.metadata != null) return false;
            var local = region.localSections.get(demand.key);
            if (demand.content == null && local != null && local.kind() != LocalSection.ABSENT) {
                this.bindLocal(demand, local); return true;
            }
            if (demand.content != null) return true;
            if (!demand.cachedCover && localCovered(region, demand.key)) {
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
            this.networkWanted(demand, !demand.cachedCover);
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
                demand.cachedCover = false; demand.cachedCutPending = null; this.networkWanted(demand, true);
                this.interestChanges.add(key);
            }
        }
        void bindLocal(Demand demand, LocalSection content) {
            if (demand.content != null) return;
            demand.content = content; demand.catalog = null; this.networkWanted(demand, false);
            this.demands.revise(demand); this.queueBound(demand);
        }
        private void dropInterest(Demand demand) {
            if (this.networkOwner != null && this.networkOwner.downloads != null)
                this.networkOwner.downloads.foregroundReleased(this.dimensionId, demand.key);
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
            this.unactivatedRequired.remove(key);
            demand.preservedPublication = null; demand.preservedContent = null;
            demand.childrenRequired = false;
            this.topologyChanged(demand);
            this.emptyTopology(demand, false);
            this.networkWanted(demand, false);
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
                demand.cachedCover = false; demand.cachedCutPending = null;
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
            if (state.members.isEmpty()) this.unavailableSourceRegions.remove(region);
            for (long key : state.members.keySet()) {
                var demand = this.demands.get(key); if (demand != null) this.dropInterest(demand);
            }
        }

        void queueBound(Demand demand) {
            demand.candidate = SectionDemandTable.CandidateState.READY_SOURCE;
            this.demands.ready(demand, SectionDemandTable.ReadyKind.SOURCE);
        }

        void processStages() throws Exception {
            ClientLodDebug.ownerDetail(this.debugOwnerTiming, 6);
            this.scheduleReadyPublications();
            ClientLodDebug.ownerDetail(this.debugOwnerTiming, 7);
            this.processWaitingModels();
            ClientLodDebug.ownerDetail(this.debugOwnerTiming, 8);
            this.scheduleSourceWork();
        }
        void ensureRegion(long key) { this.queueRegion(regionFor(key)); this.interestChanges.add(key); }
        void queueRegion(long region) { this.demands.readyRegion(this.demands.region(region)); }
        private boolean localProbed(Demand demand) {
            var region = this.demands.region(demand.regionKey);
            return this.bootstrapComplete && (this.metadataUnavailable || this.cacheRoot == null
                    || this.worldIdentity == null || region != null && region.localLoaded);
        }
        private boolean networkEligible(Demand demand) {
            if (this.unavailableSourceRegions.contains(demand.regionKey)) return false;
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
        boolean downloadVisible(long key) {
            return this.downloadVisible(key, this.downloadFrustum);
        }
        private boolean downloadVisible(long key, DownloadFrustum frustum) {
            if (frustum == null) return true;
            float[] planes = frustum.planes();
            int level = SectionKey.level(key); double size = 32L << level;
            double x = SectionKey.x(key) * size - frustum.cameraX();
            double y = SectionKey.y(key) * size - frustum.cameraY();
            double z = SectionKey.z(key) * size - frustum.cameraZ();
            for (int plane = 0; plane < 24; plane += 4) {
                double px = planes[plane] >= 0 ? x + size : x;
                double py = planes[plane + 1] >= 0 ? y + size : y;
                double pz = planes[plane + 2] >= 0 ? z + size : z;
                if (planes[plane] * px + planes[plane + 1] * py + planes[plane + 2] * pz + planes[plane + 3] < 0) return false;
            }
            return true;
        }
        private void networkWanted(Demand demand, boolean wanted) {
            wanted &= !this.unavailableSourceRegions.contains(demand.regionKey);
            demand.networkWanted = wanted;
            if (wanted) this.missingInterests.add(demand.key); else this.missingInterests.remove(demand.key);
        }
        private void reclassifyMissingInterests() {
            var frustum = this.downloadFrustum;
            var previous = this.classifiedFrustum;
            if (frustum == previous || frustum != null && previous != null
                    && Double.compare(frustum.cameraX(), previous.cameraX()) == 0
                    && Double.compare(frustum.cameraY(), previous.cameraY()) == 0
                    && Double.compare(frustum.cameraZ(), previous.cameraZ()) == 0
                    && java.util.Arrays.equals(frustum.planes(), previous.planes())) return;
            this.classifiedFrustum = frustum;
            for (long key : this.emptyTopologyKeys) {
                var demand = this.demands.get(key);
                if (demand != null && (this.visibleWatchKeys.contains(key) || this.requiredEmptyWatch(demand)) != (demand.wireTicket != 0))
                    this.interestChanges.add(key);
            }
            for (long key : this.missingInterests) {
                var demand = this.demands.get(key);
                if (demand == null) continue;
                int purpose = this.downloadVisible(key, frustum) ? demand.coverage ? 0 : 1 : 4;
                if (demand.wireTicket == 0 || purpose != demand.sentPurpose || this.interestChanges.contains(key))
                    this.interestChanges.add(key);
            }
        }
        private boolean refreshAllowed() throws IOException {
            var required = this.unactivatedRequired.iterator();
            while (required.hasNext()) {
                var demand = this.demands.get(required.next());
                if (demand == null || demand.installed || demand.cachedCover
                        && demand.cachedCutPending.isEmpty() && demand.cachedCoverActivatedFrame < this.renderedFrames) {
                    required.remove(); continue;
                }
                if (this.downloadVisible(demand.key) && !this.isCoarsening(demand.key)) return false;
            }
            for (long key : this.missingInterests) {
                var demand = this.demands.get(key);
                if (demand != null && this.downloadVisible(key) && !demand.cachedCover) return false;
            }
            if (this.networkOwner == null || this.networkOwner.downloads == null) return false;
            double total = 0, full = 0;
            for (int i = 0; i < this.visibleCutKeys.length; i++) {
                long key = this.visibleCutKeys[i];
                if (SectionKey.level(key) == 4) continue;
                double area = this.visibleAreas[i]; total += area;
                if (this.networkOwner.downloads.allCached(this.dimensionId, key)) full += area;
            }
            return total == 0 || full > total * 0.5;
        }
        void processRegions() throws IOException {
            if (this.quic == null || this.policy == null) return;
            ClientLodDebug.ownerDetail(this.debugOwnerTiming, 9);
            this.reclassifyMissingInterests();
            if (this.checkedFrame != this.renderedFrames) {
                this.checkedFrame = this.renderedFrames; this.interestChanges.addAll(this.frameInterests); this.frameInterests.clear();
            }
            long interval = RegionalProtocol.UPDATE_INTERVAL_MILLIS;
            long bandwidth = this.policy.available() ? this.policy.downloadKbps() : this.sentBandwidthKbps;
            ClientLodDebug.ownerDetail(this.debugOwnerTiming, 10);
            boolean refresh = this.helloAccepted && (this.metadata == null || this.metadata.canDownload()) && this.refreshAllowed();
            ClientLodDebug.ownerDetail(this.debugOwnerTiming, 11);
            if (this.openSent && this.dimensionId >= 0 && (bandwidth != this.sentBandwidthKbps
                    || refresh != this.sentRefreshAllowed || this.dimensionId != this.sentDimensionId || this.cameraBlockX != this.sentAnchorX || this.cameraBlockZ != this.sentAnchorZ)) {
                var anchors = this.networkOwner.downloads == null ? List.of(new RegionalProtocol.DimensionAnchor(this.dimensionId, this.cameraBlockX, this.cameraBlockZ))
                        : this.networkOwner.downloads.anchors();
                if (!this.quic.settings(interval, bandwidth, refresh, this.dimensionId, anchors)) return;
                this.sentBandwidthKbps = bandwidth; this.sentRefreshAllowed = refresh;
                this.sentAnchorX = this.cameraBlockX; this.sentAnchorZ = this.cameraBlockZ; this.sentDimensionId = this.dimensionId;
            }
            if (this.openSent && !this.helloAccepted) return;
            int packetBytes = this.quic.controlBatchBytes();
            int dropCount = (packetBytes - RegionalProtocol.CONTROL_LIST_HEADER_BYTES) / RegionalProtocol.SCOPED_DROP_BYTES;
            if (this.openSent && !this.networkOwner.detachedDrops.isEmpty()) {
                var drops = this.networkOwner.detachedDrops.stream().limit(dropCount).toList();
                if (this.quic.drop(drops)) this.networkOwner.detachedDrops.removeAll(drops);
                return;
            }
            if (this.openSent && this.dimensionId >= 0 && !this.interestDrops.isEmpty()) {
                var keys = this.interestDrops.stream().limit(dropCount).map(k -> new RegionalProtocol.ScopedKey(this.dimensionId, k)).toList();
                if (!this.quic.drop(keys)) return;
                for (var key : keys) this.interestDrops.remove(key.key());
                return;
            }
            if (this.openSent && this.metadata != null && !this.metadata.canDownload()) return;
            var changes = new ArrayList<RegionalProtocol.Desire>();
            int frameBytes = this.openSent ? RegionalProtocol.CONTROL_LIST_HEADER_BYTES : RegionalProtocol.openHeaderBytes(this.dimension);
            var pendingChanges = this.interestChanges.iterator(!this.openSent);
            while (pendingChanges.hasNext()) {
                long key = pendingChanges.next(); var demand = this.demands.get(key);
                if (demand == null || this.targetWindow != null && !this.inSubscriptionWindow(demand.regionKey)
                        || !this.networkEligible(demand)) { pendingChanges.remove(); continue; }
                boolean visible = demand.networkWanted ? this.downloadVisible(key)
                        : this.visibleWatchKeys.contains(key) || this.requiredEmptyWatch(demand);
                if (!demand.networkWanted && !visible) { pendingChanges.remove(); this.dropInterest(demand); continue; }
                int purpose = demand.networkWanted ? visible ? demand.coverage ? 0 : 1 : 4 : 2;
                if (!this.openSent && (!demand.networkWanted || purpose > 1)) continue;
                if (this.metadata != null && !this.metadata.canDownload()) continue;
                var prefetch = this.networkOwner.downloads == null ? null : this.networkOwner.downloads.pending(this.dimensionId, key);
                if (prefetch != null && prefetch.processing) continue;
                long ticket = prefetch != null ? prefetch.ticket : demand.candidate == SectionDemandTable.CandidateState.NETWORK_OWNED && demand.wireTicket != 0
                        ? demand.wireTicket : this.networkOwner.ticket();
                if (prefetch != null) prefetch.purpose = purpose;
                LocalSection have = demand.networkWanted ? null : demand.activeContent;
                var desire = new RegionalProtocol.Desire(Math.max(0, this.dimensionId), this.worldIdentity == null ? RegionalProtocol.Hash32.ZERO : this.worldIdentity,
                        ticket, key, purpose, have, WorldCacheDownloads.rank(key, this.cameraBlockX, this.cameraBlockZ));
                int bytes = RegionalProtocol.desireBytes(desire, this.openSent);
                if (frameBytes + bytes > packetBytes) break;
                changes.add(desire); frameBytes += bytes;
            }
            if (changes.isEmpty() && (this.openSent || !this.bootstrapComplete)) return;
            boolean accepted = this.openSent ? this.quic.desire(changes)
                    : this.quic.open(this.dimension, this.worldIdentity, this.currentCatalog, interval, bandwidth, refresh, this.cameraBlockX, this.cameraBlockZ, changes);
            if (!accepted) return;
            this.openSent = true; this.networkOwner.opened = true;
            this.sentBandwidthKbps = bandwidth; this.sentRefreshAllowed = refresh;
            for (var change : changes) {
                var demand = this.demands.get(change.key());
                ClientLodDebug.streamingDesire(this, demand, change);
                demand.wireTicket = change.ticket(); demand.sentPurpose = change.purpose(); this.interestChanges.remove(change.key());
                if (this.networkOwner.downloads != null) {
                    this.networkOwner.downloads.foreground(change.dimensionId(), change.key());
                    var job = this.networkOwner.downloads.job(change.ticket());
                    if (job != null) this.networkOwner.downloads.promote(job);
                }
                if (demand.networkWanted) demand.candidate = SectionDemandTable.CandidateState.NETWORK_OWNED;
            }
        }
        void processCacheDownloads() throws IOException {
            if (this.metadata == null || this.policy == null) return;
            var downloads = this.networkOwner == null ? null : this.networkOwner.downloads;
            if (this.sentStorageBytes != this.policy.storageBytes()) {
                this.metadata.bindServer(this.policy, this.serverKey);
                this.resetMetadataRetry();
                if (downloads != null) downloads.policyChanged();
                this.sentStorageBytes = this.policy.storageBytes();
            }
            ClientLodDebug.ownerDetail(this.debugOwnerTiming, 12);
            var cut = this.visibleInput;
            if (cut != null && this.visibility.update(cut.epoch(), cut.keys())) {
                this.visibleEpoch = cut.epoch();
                var added = this.visibility.addedWatchKeys();
                for (int i = 0; i < added.size(); i++) this.interestChanges.add(added.getLong(i));
                var removed = this.visibility.removedWatchKeys();
                for (int i = 0; i < removed.size(); i++) this.interestChanges.add(removed.getLong(i));
                // Membership can stay unchanged while the renderer reports fresh areas/order.
                this.visibleCutKeys = cut.keys(); this.visibleAreas = cut.areas();
            }
            ClientLodDebug.ownerDetail(this.debugOwnerTiming, 13);
            if (this.retentionView != this.viewRevision || this.retentionEpoch != this.visibleEpoch
                    || this.retentionX != this.cameraBlockX || this.retentionZ != this.cameraBlockZ) {
                this.metadata.updateRetention(this.dimension, this.cameraBlockX, this.cameraBlockZ, this.visibleRegions);
                this.retentionView = this.viewRevision; this.retentionEpoch = this.visibleEpoch;
                this.retentionX = this.cameraBlockX; this.retentionZ = this.cameraBlockZ;
            }
            if (downloads == null || !this.helloAccepted) return;
            ClientLodDebug.ownerDetail(this.debugOwnerTiming, 14);
            downloads.view(this.dimensionId, this.cameraBlockX, this.cameraBlockZ, this.visibility);
            ClientLodDebug.ownerDetail(this.debugOwnerTiming, 15);
            if (this.quic == null || !this.openSent) return;
            // A key-only cancellation must reach the writer before its replacement desire.
            if (!this.interestDrops.isEmpty() || !this.networkOwner.detachedDrops.isEmpty()) return;
            var drops = downloads.drops();
            if (!drops.isEmpty()) {
                int count = (this.quic.controlBatchBytes() - RegionalProtocol.CONTROL_LIST_HEADER_BYTES) / RegionalProtocol.SCOPED_DROP_BYTES;
                var batch = drops.subList(0, Math.min(drops.size(), count));
                if (this.quic.drop(batch)) downloads.dropped(batch);
                return;
            }
            var changes = downloads.changes();
            if (!changes.isEmpty()) {
                int bytes = RegionalProtocol.CONTROL_LIST_HEADER_BYTES, count = 0;
                for (var change : changes) {
                    int entry = RegionalProtocol.desireBytes(change, true);
                    if (bytes + entry > this.quic.controlBatchBytes()) break;
                    bytes += entry; count++;
                }
                var batch = changes.subList(0, count);
                if (this.quic.desire(batch)) downloads.changed(batch);
                return;
            }
            if (!this.interestChanges.isEmpty() || this.demands.readyCount(SectionDemandTable.ReadyKind.SOURCE) != 0) return;
            int available = 0; for (var worker : this.sectionWorkers) if (worker.idle()) available++;
            if (downloads.waiting() >= available) return;
            var next = downloads.next(this.networkOwner.ticket(), this.connectionEpoch);
            if (next != null && !this.quic.desire(List.of(next))) downloads.unsent(next.ticket());
        }

        void changeWorld(RegionalProtocol.Hash32 world) {
            ++this.viewRevision;
            this.unavailableSourceRegions.clear();
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
                region.localIncarnation = 0;
                region.localSections = Map.of();
                region.localCoverage.clear();
                region.localCommits.clear();
                this.queueRegion(region.key);
                this.interestChanges.addAll(region.members.keySet());
            }
        }

        RegionalSectionCodec.BoundCatalog mapping(RegionalProtocol.Hash32 fingerprint) {
            return this.savedMappings.get(fingerprint);
        }

        /** Render-thread seam; each unresolved name stays in its existing worker, never a queue. */
        void resolveNames(LocalSectionCodec.Names resolver) {
            Object timing = ClientLodDebug.renderLoadingBegin(0);
            try {
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
                                ClientLodDebug.renderLoadingEvent(timing, 0, 1);
                                if (id == null) {
                                    id = resolver.resolve(wait.canonical, wait.biome);
                                    names.put(wait.canonical, id);
                                    ClientLodDebug.renderLoadingEvent(timing, 1, 1);
                                    this.resolvedNameCharacters += wait.canonical.length();
                                }
                                else ClientLodDebug.renderLoadingEvent(timing, 2, 1);
                                wait.id = id;
                            } catch (IOException | RuntimeException failure) { ClientLodDebug.renderLoadingEvent(timing, 4, 1); wait.failure = new IOException("Cannot resolve canonical name", failure); }
                            wait.done = true; worker.notifyAll(); progress = true;
                        }
                        if (System.nanoTime() >= deadline) {
                            ClientLodDebug.renderLoadingEvent(timing, 3, 1); return;
                        }
                    }
                } while (progress && System.nanoTime() < deadline);
                if (progress && System.nanoTime() >= deadline) ClientLodDebug.renderLoadingEvent(timing, 3, 1);
            } finally { ClientLodDebug.renderLoadingEnd(timing); }
        }

        long retainedSaveBytes() {
            long bytes = 0;
            for (var worker : this.sectionWorkers) {
                var input = worker.saveInput;
                if (input != null && input.canonical() != null) bytes += input.canonical().length;
            }
            return bytes;
        }

        void processMetadata() throws IOException {
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

        private boolean loadLocalMetadata() throws IOException {
            if (this.cache == null) return false;
            var local = this.demands.pollRegion();
            if (local != null) {
                local.localTried = true;
                var task = new LoadMetadataTask(this.viewRevision, local.key,
                        local.metadataRevision, this.worldIdentity, this.cache, local);
                if (this.cache.knownAbsent(local.key)) {
                    ClientLodDebug.startupEvent(this, "localAbsent", 0);
                    this.applyLocalIndex(new WorkerMetadata(task, new HashMap<>(), this.cache.incarnation(local.key)));
                } else this.metadataWorker.assign(task);
                return true;
            }
            return false;
        }

        private boolean persistMetadata() {
            var unavailable = this.metadata.budget.persistenceUnavailable();
            if (unavailable != null || !this.metadata.canDownload()) return false;
            long generation = this.metadata.admissionGeneration();
            Object goal = this.associationPending ? this.associationIntent : this.catalogueIntent;
            if (goal == null || goal == this.blockedMetadataGoal
                    && (!this.metadataRetryOnAdmission || generation == this.blockedMetadataGeneration)) return false;
            long stamp = this.metadata.budget.stamp();
            if (this.associationPending) {
                var intent = this.associationIntent;
                this.metadataWorker.assign(new PersistTask(intent, this.cache, stamp, generation,
                        () -> this.open.get() && this.viewRevision == intent.view() && this.associationIntent == intent));
                return true;
            }
            if (this.catalogueIntent != null) {
                var intent = this.catalogueIntent;
                this.metadataWorker.assign(new PersistTask(intent, this.cache, stamp, generation,
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
            if (outcome != RegionalMetadataStore.Persistence.PERSISTED) {
                if (saved.task().current().getAsBoolean()) {
                    this.blockedMetadataGoal = saved.task().intent();
                    this.blockedMetadataGeneration = saved.task().admissionGeneration();
                    this.metadataRetryOnAdmission = saved.retryOnAdmission();
                }
                return;
            }
            if (this.blockedMetadataGoal == saved.task().intent()) this.blockedMetadataGoal = null;
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
            this.blockedMetadataGoal = null;
            this.catalogueIntent = null;
        }
        private void resetMetadataRetry() {
            this.blockedMetadataGoal = null;
            var association = this.associationIntent;
            if (association != null) this.associationIntent = new AssociationTask(association.view(), association.world());
            var catalogue = this.catalogueIntent;
            if (catalogue != null) this.catalogueIntent = new CatalogueSave(catalogue.view(), catalogue.world(), catalogue.message());
        }

        void applyLocalIndex(WorkerMetadata ready) {
            var task = ready.task();
            var state = this.demands.region(task.region());
            if (state == null) task.cache().forget(task.region());
            if (task.view() != this.viewRevision || task.cache() != this.cache || state != task.owner()) return;
            if (task.revision() != state.metadataRevision) {
                if (!state.localLoaded) { state.localTried = false; this.queueRegion(state.key); }
                return;
            }
            this.refreshLocalIncarnation(state);
            if (ready.incarnation() != this.cache.incarnation(state.key)) {
                if (!state.localLoaded) { state.localTried = false; this.queueRegion(state.key); }
                return;
            }
            if (task.revision() != state.metadataRevision) return;
            if (state.localIncarnation != 0 && state.localIncarnation != ready.incarnation()) state.localCommits.clear();
            state.localIncarnation = ready.incarnation();
            ready.sections().putAll(state.localCommits);
            state.localCommits.clear();
            ClientLodDebug.ownerEvent(this.debugOwnerTiming, 22, 1);
            ClientLodDebug.ownerEvent(this.debugOwnerTiming, 23, ready.sections().size());
            state.localLoaded = true;
            state.localSections = ready.sections();
            state.localCoverage.clear();
            for (var section : state.localSections.values()) updateLocalCoverage(state, section.key(), section.kind() != LocalSection.ABSENT);
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
                    var cover = state.localSections.get(ancestor);
                    if (cover != null && cover.kind() != LocalSection.ABSENT) { covered = true; break; }
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
                        if (demand != null && demand.revision == outcome.ticket().demandRevision()) this.recordCommitted(outcome.content(), outcome.incarnation());
                    }
                    if (worker.resource.finishSave(outcome.lease())) worker.reusable(outcome.lease());
                    if (outcome.failure() instanceof IOException io) this.lastPersistenceFailure = io.toString();
                    else if (outcome.failure() != null && !(outcome.failure() instanceof java.util.concurrent.CancellationException)) {
                        throw new IllegalStateException("Section persistence invariant failed after geometry handoff", outcome.failure());
                    }
                }
                WorkerResource.Completion<WorkerResult> completion = worker.resource.claim();
                if (completion == null) return;
                WorkerResource.Lease lease = completion.lease();
                ClientLodDebug.workerClaimed(worker.debugWork, lease);
                WorkerResult result = completion.value();
                long ownerClaimedNanos = result instanceof WorkerGeometry
                        ? ClientLodDebug.publicationClock() : 0;
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
                    case WorkerCached cached -> {
                        var task = cached.task();
                        boolean committed = task.owner().current(task.job());
                        if (committed) {
                            task.owner().committed(task.reply().dimensionId(), task.reply().content(), cached.incarnation());
                            task.owner().complete(task.job(), task.reply().content(), task.reply().compressed().length);
                        }
                        worker.releaseCompletion(lease);
                        if (committed && task.reply().dimensionId() == this.dimensionId
                                && task.reply().worldIdentity().equals(this.worldIdentity)) {
                            this.recordLocalCommitted(task.reply().content(), cached.incarnation());
                        }
                    }
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
                            boolean discarded = region != null && Objects.equals(region.localSections.get(demand.key), demand.content);
                            if (region != null) {
                                this.refreshLocalIncarnation(region);
                                var local = region.localSections.get(demand.key);
                                if (miss.incarnation() == this.cache.incarnation(region.key)
                                        && (local == null || Objects.equals(local, demand.content))) {
                                    if (miss.fallback() != null && !region.localLoaded) {
                                        region.localIncarnation = miss.incarnation();
                                        region.localCommits.put(demand.key, miss.fallback());
                                    } else if (region.localLoaded) {
                                        if (miss.fallback() == null) region.localSections.remove(demand.key);
                                        else region.localSections.put(demand.key, miss.fallback());
                                        updateLocalCoverage(region, demand.key, miss.fallback() != null && miss.fallback().kind() != LocalSection.ABSENT);
                                    }
                                }
                            }
                            demand.content = null; this.networkWanted(demand, false);
                            demand.candidate = SectionDemandTable.CandidateState.WAIT_REGION;
                            if (discarded && miss.fallback() == null) this.invalidateCachedCover(demand.key);
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
                        worker.ownerClaimedNanos = ownerClaimedNanos;
                        if (ownerClaimedNanos != 0) ClientLodDebug.handoff(this, 0,
                                ownerClaimedNanos - geometry.completedNanos());
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
                        if (failed.task() instanceof CacheOnlyTask task) task.owner().failed(task.job(), failed.failure());
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
            this.networkWanted(demand, true); demand.candidate = SectionDemandTable.CandidateState.WAIT_REGION;
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

        void recordCommitted(LocalSection content, long incarnation) {
            if (content == null) return;
            this.recordLocalCommitted(content, incarnation);
            if (this.networkOwner != null && this.networkOwner.downloads != null) {
                var downloads = this.networkOwner.downloads;
                downloads.committed(this.dimensionId, content, incarnation);
                var job = downloads.pending(this.dimensionId, content.key());
                if (job != null) downloads.complete(job, content, content.compressedBytes());
            }
        }

        private static boolean localCovered(SectionDemandTable.RegionDemand region, long key) {
            int bits = region.localCoverage.get(key);
            return (bits & 256) != 0 || (bits & 255) == 255;
        }

        /** Direct availability plus eight complete-child bits; propagation stops when coverage is unchanged. */
        private static void updateLocalCoverage(SectionDemandTable.RegionDemand region, long key, boolean available) {
            int before = region.localCoverage.get(key);
            int after = (before & 255) | (available ? 256 : 0);
            while (true) {
                if (after == 0) region.localCoverage.remove(key); else region.localCoverage.put(key, after);
                boolean wasCovered = (before & 256) != 0 || (before & 255) == 255;
                boolean covered = (after & 256) != 0 || (after & 255) == 255;
                if (wasCovered == covered || SectionKey.level(key) == SectionKey.MAX_LOD_LAYER) return;
                int childBit = 1 << ((SectionKey.x(key) & 1) | (SectionKey.z(key) & 1) << 1 | (SectionKey.y(key) & 1) << 2);
                key = parent(key);
                before = region.localCoverage.get(key);
                after = covered ? before | childBit : before & ~childBit;
            }
        }

        /** Invalidate only the evicted region; installed geometry and worker leases stay owned. */
        private void refreshLocalIncarnation(SectionDemandTable.RegionDemand region) {
            if (this.cache == null || region.localIncarnation == 0) return;
            long incarnation = this.cache.incarnation(region.key);
            if (incarnation == region.localIncarnation) return;
            region.localIncarnation = incarnation;
            region.metadataRevision++;
            region.localTried = false;
            region.localLoaded = false;
            region.localSections = Map.of();
            region.localCoverage.clear();
            region.localCommits.clear();
            for (long key : region.members.keySet()) {
                var demand = this.demands.get(key);
                if (!demand.cachedCover) continue;
                this.forgetDependencies(demand);
                demand.cachedCover = false;
                demand.cachedCutPending = null;
                this.interestChanges.add(demand.key);
            }
            this.queueRegion(region.key);
        }

        private void recordLocalCommitted(LocalSection content, long incarnation) {
            var region = this.demands.region(content.region());
            if (region == null) return;
            this.refreshLocalIncarnation(region);
            if (this.cache == null || this.cache.incarnation(region.key) != incarnation) return;
            region.localIncarnation = incarnation;
            ClientLodDebug.startupEvent(this, "localPatch", 0);
            if (!region.localLoaded) { region.localCommits.put(content.key(), content); return; }
            if (region.localSections.isEmpty()) region.localSections = new HashMap<>();
            region.localSections.put(content.key(), content);
            boolean available = content.kind() != LocalSection.ABSENT;
            updateLocalCoverage(region, content.key(), available);
            if (!available) this.invalidateCachedCover(content.key());
            if (available && this.demands.get(content.key()) == null && SectionKey.level(content.key()) != SectionKey.MAX_LOD_LAYER
                    && this.demands.get(topAncestor(content.key())) != null) {
                long ancestor = content.key(); boolean covered = false;
                while (SectionKey.level(ancestor) < SectionKey.MAX_LOD_LAYER) {
                    ancestor = parent(ancestor);
                    var section = region.localSections.get(ancestor);
                    if (section != null && section.kind() != LocalSection.ABSENT) { covered = true; break; }
                }
                if (!covered) this.addDemand(content.key());
            }
            long key = content.key();
            while (true) {
                var demand = this.demands.get(key);
                if (demand != null) this.bindAvailable(demand);
                if (SectionKey.level(key) == SectionKey.MAX_LOD_LAYER) break;
                key = parent(key);
            }
        }

        void processWaitingModels() {
            // At most one retained cell array per real worker, no detached model-wait queue.
            for (WorkerSlot worker : this.sectionWorkers) {
                ModelWait wait = worker.modelWait;
                if (wait == null) continue;
                ClientLodDebug.ownerEvent(this.debugOwnerTiming, 16, 1);
                boolean current = this.demands.current(wait.task().ticket());
                boolean ready = current && this.mesher.modelsReady(wait.section());
                if (current) this.demands.get(wait.task().ticket().key()).candidate = ready
                        ? SectionDemandTable.CandidateState.WORKER_OWNED : SectionDemandTable.CandidateState.WAIT_MODELS;
                if (!current || ready) {
                    ClientLodDebug.ownerEvent(this.debugOwnerTiming, current ? 17 : 18, 1);
                    synchronized (worker) { worker.notifyAll(); }
                }
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
                ClientLodDebug.ownerEvent(this.debugOwnerTiming, 9, 1);
                if (demand.candidate != SectionDemandTable.CandidateState.READY_SOURCE) {
                    ClientLodDebug.ownerEvent(this.debugOwnerTiming, 10, 1); continue;
                }
                if (demand.preservedPublication != null) {
                    ClientLodDebug.ownerEvent(this.debugOwnerTiming, 11, 1);
                    this.demands.ready(demand, SectionDemandTable.ReadyKind.SOURCE); continue;
                }
                if (demand.content.kind() == LocalSection.EMPTY) {
                    if (!this.publishEmpty(demand)) {
                        ClientLodDebug.ownerEvent(this.debugOwnerTiming, 14, 1);
                        ClientLodDebug.ownerNoSlot(this.debugOwnerTiming, this);
                        this.demands.ready(demand, SectionDemandTable.ReadyKind.SOURCE);
                        return;
                    }
                    ClientLodDebug.ownerEvent(this.debugOwnerTiming, 12, 1);
                    continue;
                }
                WorkerSlot worker = this.idleWorker(demand);
                if (worker == null) {
                    ClientLodDebug.ownerEvent(this.debugOwnerTiming, 14, 1);
                    ClientLodDebug.ownerNoSlot(this.debugOwnerTiming, this);
                    this.demands.ready(demand, SectionDemandTable.ReadyKind.SOURCE);
                    return;
                }
                WorkerSource source = WorkerSource.CACHE;
                demand.networkWork = false;
                SectionDemandTable.Ticket ticket = demand.ticket(this.id, worker.index);
                SectionWorkerTask task = new SectionWorkerTask(ticket, demand.content,
                        source, null, demand.catalog == null ? null : demand.catalog.mappings(), this.cache,
                        () -> this.open.get() && demand.revision == ticket.demandRevision());
                demand.workLease = worker.assign(task);
                this.demands.owned(demand, SectionDemandTable.CandidateState.WORKER_OWNED);
                if (demand.workLease == null) {
                    ClientLodDebug.ownerEvent(this.debugOwnerTiming, 15, 1);
                    demand.workLease = null;
                    demand.candidate = SectionDemandTable.CandidateState.READY_SOURCE;
                    this.demands.ready(demand, SectionDemandTable.ReadyKind.SOURCE);
                    return;
                }
                ClientLodDebug.ownerEvent(this.debugOwnerTiming, 13, 1);
            }
        }

        Demand replyDemand(NetworkReply handoff) {
            if (!this.helloAccepted || handoff.connectionEpoch != this.connectionEpoch
                    || handoff.reply.dimensionId() != this.dimensionId || !handoff.reply.worldIdentity().equals(this.worldIdentity)) return null;
            var demand = this.demands.get(handoff.reply.key());
            return demand != null && demand.wireTicket == handoff.reply.ticket() ? demand : null;
        }
        void finishReply(NetworkReply handoff) { this.networkReplies.remove(handoff); handoff.transferred(); }
        void drainNetworkReplies() throws IOException {
            for (var handoff : this.networkReplies) {
                if (!this.helloAccepted && handoff.connectionEpoch == this.connectionEpoch) continue;
                var demand = this.replyDemand(handoff);
                if (demand == null) {
                    var downloads = this.networkOwner == null ? null : this.networkOwner.downloads;
                    var job = downloads == null ? null : downloads.job(handoff.reply.ticket());
                    if (job == null || !downloads.current(job) || job.connection != this.connectionEpoch
                            || job.dimension.info.id() != handoff.reply.dimensionId()
                            || !job.dimension.info.worldIdentity().equals(handoff.reply.worldIdentity())) { this.finishReply(handoff); continue; }
                    if (handoff.reply.status() == RegionalProtocol.Status.NOT_READY) {
                        downloads.notReady(job); this.finishReply(handoff); continue;
                    }
                    if (job.processing || this.demands.readyCount(SectionDemandTable.ReadyKind.SOURCE) != 0) continue;
                    var worker = this.idleWorker(); if (worker == null) continue;
                    job.processing = true;
                    if (worker.assign(new CacheOnlyTask(downloads, job, handoff.reply, handoff.catalog)) == null) throw new IllegalStateException("cache worker rejected record");
                    this.finishReply(handoff); continue;
                }
                var reply = handoff.reply;
                if (reply.status() == RegionalProtocol.Status.NOT_READY) { this.finishReply(handoff); continue; }
                if (Long.compareUnsigned(reply.generation(), demand.regionGeneration) < 0) { this.finishReply(handoff); continue; }
                // The lane owns its one record while cache work/publication finishes. Never cancel cached work for refresh.
                if (demand.workLease != null || demand.completedGeometry != null
                        || demand.preservedPublication != null
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
                WorkerSlot worker = this.idleWorker(demand);
                if (worker == null) continue;
                this.demands.unlinkReady(demand); this.demands.revise(demand);
                demand.content = content; demand.catalog = binding; demand.regionGeneration = reply.generation(); this.networkWanted(demand, false);
                var ticket = demand.ticket(this.id, worker.index);
                if (content.kind() != LocalSection.DATA) {
                    demand.networkWork = true;
                    demand.workLease = worker.assign(new EmptyWorkerTask(ticket, (byte) content.children(), content, this.cache,
                            () -> this.open.get() && demand.revision == ticket.demandRevision()));
                } else {
                    boolean reuse = reply.status() == RegionalProtocol.Status.REUSE;
                    demand.networkWork = !reuse;
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
            demand.networkWork = false;
            // This binding already came from cache; only a network reply needs a save.
            demand.workLease = worker.assign(new EmptyWorkerTask(ticket, (byte) demand.content.children()));
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
            ClientLodDebug.ownerEvent(this.debugOwnerTiming, 24, 1);
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
                // Admission can happen before tryPublishBatch returns. Freeze the attempt's
                // start, retaining it only if accepted; BUSY attempts contribute no sample.
                long submittedNanos = ClientLodDebug.publicationClock();
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
                    ClientLodDebug.ownerEvent(this.debugOwnerTiming, 26, 1);
                    return;
                }
                ClientLodDebug.ownerEvent(this.debugOwnerTiming, 25, prepared.size());
                for (int index = 0; index < prepared.size(); index++) {
                    PreparedPublication item = prepared.get(index);
                    Demand demand = item.demand();
                    this.transferGeometryAccounting(demand);
                    demand.previousPublication = item.previous();
                    demand.publication = attempt.publications().get(index);
                    if (demand.workLease != null && submittedNanos != 0) {
                        long claimed = this.sectionWorkers[demand.workLease.slot()].ownerClaimedNanos;
                        if (claimed != 0) ClientLodDebug.handoff(this, 1, submittedNanos - claimed);
                    }
                    this.publicationQueue.addLast(new PublicationRef(demand, item.revision(),
                            demand.publication, item.previous(), demand.workLease, demand.geometryBytes,
                            demand.meshCompletedNanos, submittedNanos));
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
                ClientLodDebug.ownerEvent(this.debugOwnerTiming, 27, 1);
                Demand demand = this.demands.get(ref.demand().key);
                boolean current = demand == ref.demand() && demand.revision == ref.revision()
                        && demand.candidate == SectionDemandTable.CandidateState.RENDERER_OWNED
                        && demand.publication == ref.publication();
                boolean preserving = demand == ref.demand() && demand.preservedPublication == ref.publication();
                if (!current && !preserving) ref.publication().close();
                if (ref.bytes() > 0 && ref.publication().rendererAdmitted()) {
                    WorkerResource.Lease lease = this.detachPublicationLease(ref);
                    if (lease != null) {
                        ClientLodDebug.ownerEvent(this.debugOwnerTiming, 29, 1);
                        long observedNanos = ClientLodDebug.publicationClock();
                        if (observedNanos != 0) {
                            long admittedNanos = ref.publication().rendererAdmittedNanos();
                            if (admittedNanos != 0 && ref.submittedNanos != 0) {
                                ClientLodDebug.handoff(this, 2, admittedNanos - ref.submittedNanos);
                                ClientLodDebug.handoff(this, 3, observedNanos - admittedNanos);
                            }
                            WorkerSlot worker = this.sectionWorkers[lease.slot()];
                            if (worker.resource.matches(lease)) worker.admissionObservedNanos = observedNanos;
                        }
                        this.releaseRendererSlot(lease);
                        ClientLodDebug.admissionReleased(this, ref.meshCompletedNanos);
                    }
                }
                Optional<VoxyRenderSystem.UploadOutcome> outcome = ref.publication().takeUploadOutcome();
                if (outcome.isEmpty()) {
                    ClientLodDebug.ownerEvent(this.debugOwnerTiming, 28, 1);
                    this.publicationQueue.addLast(ref);
                    continue;
                }
                VoxyRenderSystem.UploadOutcome result = outcome.orElseThrow();
                this.publicationOutcomes[result.status().ordinal()]++;
                this.publishingGeometryBytes -= ref.bytes();
                if (preserving) {
                    var content = demand.preservedContent;
                    demand.preservedPublication = null; demand.preservedContent = null;
                    if (result.status() == VoxyRenderSystem.UploadStatus.ACTIVATED) {
                        // Activation may have won the race with source removal.
                        // Its validated geometry is now the installed stale fallback.
                        demand.publication = ref.publication(); demand.content = content;
                        this.activated(demand, content, ref.bytes(), false);
                    } else {
                        if (result.block() != null) result.block().geometry().free();
                        if (!demand.installed && !this.bindAvailable(demand)) this.ensureRegion(demand.key);
                    }
                    this.releaseRendererSlot(this.detachPublicationLease(ref));
                    continue;
                }
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
                        this.activated(demand, demand.content, ref.bytes(), demand.candidateCacheHit);
                        this.releaseRendererSlot(this.detachPublicationLease(ref));
                    }
                }
            }
            this.retryRendererBlocked();
        }
        private void activated(Demand demand, LocalSection content, long bytes, boolean cacheHit) {
            var previous = demand.activeContent;
            demand.candidate = SectionDemandTable.CandidateState.NONE; demand.networkWork = false;
            this.setActiveGeometryBytes(demand, bytes);
            if (!demand.installed) { demand.installed = true; this.activeCount++; }
            this.unactivatedRequired.remove(demand.key); demand.activeContent = content;
            this.emptyTopology(demand, bytes == 0);
            if (demand.childrenRequired && SectionKey.level(demand.key) != 0 && !this.isCoarsening(demand.key)) {
                int previousChildren = previous == null ? 0 : previous.children();
                int added = content.children() & ~previousChildren;
                for (int child = 0; child < 8; child++) if ((added & 1 << child) != 0)
                    this.addDemand(child(demand.key, child), demand.pixelBucket);
                if (previousChildren != content.children()) this.topologyChanged(demand);
            }
            demand.cacheActivatedFrame = cacheHit ? this.renderedFrames : -1;
            this.interestChanges.add(demand.key); this.coverageActivated(demand);
            ClientLodDebug.sectionActivated(this, content, cacheHit);
            if (demand.coverage) this.missingCoverage.remove(demand.key);
            this.activated++;
            ClientLodDebug.startupEvent(this, this.helloAccepted ? "activation" : "localActivation", 0);
            this.uploadedSections++;
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
            return "regional=ACTIVE dimension=" + this.dimension + " openSent=" + this.openSent + " helloAccepted=" + this.helloAccepted
                    + " transport=" + (this.quic == null ? "none" : this.quic.description()) + " desired=" + this.demands.size()
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
                    + (this.networkOwner != null && this.networkOwner.downloads != null ? " " + this.networkOwner.downloads.snapshot() : "")
                    + " connectionFailure=" + String.valueOf(this.lastConnectionFailure)
                    + " failure=" + String.valueOf(this.failure);
        }

        @Override public void close() {
            if (!this.open.getAndSet(false)) return;
            ++this.viewRevision;
            var cleanup = new me.cortex.voxy.common.util.Cleanup();
            signal();
            this.thread.interrupt();
            this.quic = null;
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
            if (this.networkOwner != null) cleanup.run(() -> this.networkOwner.detach(this));
            // Stop every renderer-using producer before callbacks can throw during cleanup.
            for (WorkerSlot worker : this.sectionWorkers) cleanup.run(worker::close);
            for (WorkerSlot worker : this.sectionWorkers) {
                me.cortex.voxy.common.util.Cleanup.join(worker.workerThread);
            }
            cleanup.run(this.metadataWorker::close);
            this.clearPersistence();
            this.quic = null;
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
            if (this.metadata != null) cleanup.run(this.metadata::close);
            for (Demand demand : this.demands.values()) {
                cleanup.run(() -> this.discardCompletedGeometry(demand));
                if (demand.publication != null) cleanup.run(demand.publication::close);
                if (demand.previousPublication != null) cleanup.run(demand.previousPublication::close);
            }
            Event event;
            while ((event = this.events.poll()) != null) discardEvent(event);
            this.demands.clear();
            this.missingInterests.clear();
            this.unactivatedRequired.clear();
            this.unavailableSourceRegions.clear();
            this.emptyTopologyKeys.clear(); this.emptyTopologyDependents.clear();
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
        private final long meshCompletedNanos, submittedNanos;
        private WorkerResource.Lease lease;

        PublicationRef(Demand demand, long revision,
                       VoxyRenderSystem.SectionPublication publication,
                       VoxyRenderSystem.SectionPublication previous,
                       WorkerResource.Lease lease, long bytes, long meshCompletedNanos,
                       long submittedNanos) {
            this.demand = demand; this.revision = revision;
            this.publication = publication; this.previous = previous;
            this.lease = lease; this.bytes = bytes;
            this.meshCompletedNanos = meshCompletedNanos; this.submittedNanos = submittedNanos;
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
