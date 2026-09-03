package me.cortex.voxy.client.lod;

import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.model.CatalogModelCompatibility;
import me.cortex.voxy.client.core.rendering.building.GpuMicrotileMesher;
import me.cortex.voxy.client.core.rendering.selection.SelectionBatch;
import me.cortex.voxy.client.core.rendering.selection.SelectionManifest;
import me.cortex.voxy.client.core.rendering.selection.PredictionTiming;
import me.cortex.voxy.client.lod.ContentPipeline.CompatibilityState;
import me.cortex.voxy.client.lod.ContentPipeline.SelectionCut;
import me.cortex.voxy.client.lod.ManifestCodec.ContentClass;
import me.cortex.voxy.client.lod.ManifestCodec.ManifestSubtree;
import me.cortex.voxy.client.lod.ManifestCodec.DescriptorPage;
import me.cortex.voxy.client.lod.ManifestCodec.RootDirectory;
import me.cortex.voxy.client.lod.ManifestCodec.SpatialNode;
import me.cortex.voxy.client.lod.RootDemandPlan.Binding;
import me.cortex.voxy.client.lod.RootDemandPlan.ContentPriority;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.client.core.model.CatalogMapper;
import me.cortex.voxy.client.lod.WireMessage.EncodedObject;
import me.cortex.voxy.client.lod.WireMessage.Hash256;
import me.cortex.voxy.client.lod.WireMessage.ObjectKind;
import me.cortex.voxy.client.lod.WireMessage.RootAnnounce;
import me.cortex.voxy.client.lod.WireMessage.RootToken;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Virtual Surface client. Manifest discovery authorizes metadata only. Terrain is requested exclusively from exact
 * object handles returned by the renderer's screen-space cut. Network connections are disposable;
 * bounded object residency and activated renderer publications live for the dimension session.</p>
 */
final class ClientSession {
    private static final int MAX_MAIN_TASKS = 512;
    private static final int MAX_MAIN_PER_TICK = 96;
    private static final int MAX_MESHING_JOBS = GpuMicrotileMesher.MAX_CONCURRENT_JOBS;
    private static final int MAX_BLOCK_ID = 1 << 20;
    private static final int MAX_BIOME_ID = 1 << 9;
    private static final long ESTIMATED_CONTENT_REQUEST_BYTES = 8L << 10;
    private static final long ESTIMATED_SUBTREE_REQUEST_BYTES = 64L << 10;
    private static final int MAX_ACTIVE_OBJECT_STREAMS = 8;
    private static final int MAX_DECODER_JOBS = MAX_ACTIVE_OBJECT_STREAMS;
    private static final int MAX_COVERAGE_OBJECT_STREAMS = 4;
    private static final int MAX_CURRENT_OBJECT_STREAMS = 3;
    private static final int MAX_PREDICTED_OBJECT_STREAMS = 1;
    private static final int MAX_OBJECT_STREAM_FAILURE_RETRIES = 3;
    private static final int MAX_MICROTILE_REQUEST_BATCH = WireMessage.MAX_REQUEST_ENTRIES;
    private static final long CAMERA_DOMAIN_QUERY_INTERVAL_NANOS =
            TimeUnit.MILLISECONDS.toNanos(100);
    private static final long CAMERA_DOMAIN_QUERY_TIMEOUT_NANOS =
            TimeUnit.SECONDS.toNanos(2);
    private static final long OBJECT_STREAM_PROGRESS_TIMEOUT_NANOS =
            TimeUnit.SECONDS.toNanos(15);
    private static final long THROUGHPUT_WINDOW_NANOS = TimeUnit.MILLISECONDS.toNanos(250);
    private static final int MAX_RETAINED_OUTSIDE_VIEW = 256;
    private static final int MAX_RETIREMENTS_PER_PASS = 16;
    private static final int MAX_RESIDENT_OBJECTS = 131_072;
    private static final long SHUTDOWN_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(5);

    /** One bounded authoritative renderer window; the planner copies it only on actual changes. */
    private static final Set<SpatialNode> METADATA_ROOTS = new LinkedHashSet<>();
    private static final ArrayBlockingQueue<MainTask> MAIN =
            new ArrayBlockingQueue<>(MAX_MAIN_TASKS);
    /** Serializes cache open/close so adjacent dimension sessions never share pack files. */
    private static final ExecutorService CACHE_LIFECYCLE =
            Executors.newSingleThreadExecutor(task -> {
                Thread thread = new Thread(task, "Voxy cache lifecycle");
                thread.setDaemon(true);
                return thread;
            });
    private static final AtomicLong SESSION = new AtomicLong();
    /** Distinguishes renderer readbacks produced by adjacent automatic QUIC connections. */
    private static final AtomicLong SELECTION_AUTHORITIES = new AtomicLong();
    private static final Object LIFECYCLE = new Object();
    private static final java.util.concurrent.ConcurrentLinkedQueue<RetiredSession> RETIRED =
            new java.util.concurrent.ConcurrentLinkedQueue<>();

    private static volatile Thread networkThread;
    private static volatile Connection connection;
    private static volatile SessionResources resources;
    private static volatile String activeDimension;
    private static volatile VoxyRenderSystem activeRenderer;

    private ClientSession() {}

    /**
     * Requests an owner-thread snapshot of the complete client pipeline. The production debug
     * facade never calls this method; the debug JAR samples it once per second.
     */
    static String debugSnapshot() {
        Connection current = connection;
        if (current != null) return current.requestDebugSnapshot();
        int metadataRoots;
        synchronized (METADATA_ROOTS) {
            metadataRoots = METADATA_ROOTS.size();
        }
        return "blocker=NO_CONNECTION dimension=" + String.valueOf(activeDimension)
                + " renderer=" + (activeRenderer != null)
                + " metadataRoots=" + metadataRoots
                + " mainQueue=" + MAIN.size();
    }

    static boolean metadataRootEntered(long key) {
        SpatialNode root = RootDemandPlan.spatial(key);
        if (root.lod() != ManifestCodec.MAX_LOD) {
            throw new IllegalArgumentException("metadata root is not top-level");
        }
        synchronized (METADATA_ROOTS) {
            if (METADATA_ROOTS.contains(root)) return false;
            boolean added = METADATA_ROOTS.add(root);
            Connection current = connection;
            if (added && current != null) current.requestMetadataResync();
            return added;
        }
    }

    static void metadataRootLeft(long key) {
        SpatialNode root = RootDemandPlan.spatial(key);
        if (root.lod() != ManifestCodec.MAX_LOD) {
            throw new IllegalArgumentException("metadata root is not top-level");
        }
        synchronized (METADATA_ROOTS) {
            if (METADATA_ROOTS.remove(root)) {
                Connection current = connection;
                if (current != null) current.requestMetadataResync();
            }
        }
    }

    static void resetDemand() {
        clearMetadataRoots();
        Connection current = connection;
        if (current != null) current.requestMetadataResync();
    }

    static void rendererLifecycleChanged() {
        disconnect(true);
    }

    static void disconnect() {
        disconnect(true);
    }

    /**
     * Stops the connection and dimension-owned resources.  A renderer-only reconnect keeps its
     * already published metadata window; a renderer/session boundary drops it because
     * {@link me.cortex.voxy.client.core.rendering.RenderDistanceTracker} does not emit removal
     * callbacks while the old renderer shuts down.
     */
    private static void disconnect(boolean clearRendererDemand) {
        synchronized (LIFECYCLE) {
            if (clearRendererDemand) clearMetadataRoots();
            SESSION.incrementAndGet();
            Connection old = connection;
            connection = null;
            Thread thread = networkThread;
            networkThread = null;
            if (thread != null) thread.interrupt();
            if (old != null) old.close();
            activeDimension = null;
            activeRenderer = null;
            MainTask task;
            while ((task = MAIN.poll()) != null) task.cancel();
            SessionResources owned = resources;
            resources = null;
            if (owned != null) {
                // Activations own render-thread GL state and must be closed before the renderer
                // that created them. Interrupting the network owner and deferring this close to
                // a later tick allowed renderer shutdown to win the race on logout.
                if (thread != null && thread != Thread.currentThread()) awaitThreadExit(thread);
                boolean resetSelection = !clearRendererDemand;
                if ((thread == null || !thread.isAlive())
                        && (old == null || old.workersTerminated())) {
                    if (old != null) old.releaseOwnedState();
                    closeResources(owned, resetSelection);
                } else {
                    owned.retireCacheAfter(thread, old);
                    RETIRED.offer(new RetiredSession(thread, old, owned, resetSelection));
                }
            }
        }
    }

    private static void awaitThreadExit(Thread thread) {
        boolean interrupted = false;
        long deadline = System.nanoTime() + SHUTDOWN_TIMEOUT_NANOS;
        while (thread.isAlive() && deadline - System.nanoTime() > 0) {
            try {
                long remaining = deadline - System.nanoTime();
                thread.join(Math.max(1, Math.min(100,
                        TimeUnit.NANOSECONDS.toMillis(Math.max(1, remaining)))));
            } catch (InterruptedException ignored) {
                interrupted = true;
                break;
            }
        }
        if (thread.isAlive()) Logger.warn("Voxy connection owner exceeded shutdown deadline: "
                + thread.getName());
        if (interrupted) Thread.currentThread().interrupt();
    }

    private static void closeResources(SessionResources owned, boolean resetSelection) {
        try {
            owned.close(resetSelection);
        } catch (RuntimeException failure) {
            // Logout and renderer replacement must remain recoverable even when one optional
            // cache/mesher cleanup reports a failure after releasing its other resources.
            Logger.warn("Failed to close Virtual Surface resources cleanly", failure);
        }
    }

    static void tick() {
        reapRetiredSessions();
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        VoxyRenderSystem renderer = IGetVoxyRenderSystem.getNullable();
        if (level == null || minecraft.player == null || renderer == null) {
            if (networkThread != null || resources != null) disconnect(true);
            return;
        }
        String dimension = level.dimension().location().toString();
        if (!dimension.equals(activeDimension) || renderer != activeRenderer
                || resources == null) {
            synchronized (LIFECYCLE) {
                // Renderer construction/destruction calls rendererLifecycleChanged(), which
                // clears the old window before a new tracker can publish roots.  Preserve any
                // roots the current renderer has already emitted before this first client tick.
                disconnect(false);
                activeDimension = dimension;
                activeRenderer = renderer;
                resources = new SessionResources(dimension, renderer);
                startLocked(dimension, resources);
            }
        } else if (networkThread == null || !networkThread.isAlive()) {
            synchronized (LIFECYCLE) {
                if (resources != null) startLocked(dimension, resources);
            }
        }

        SessionResources retained = resources;
        if (retained == null) return;
        Connection cameraConnection = connection;
        if (cameraConnection != null) {
            cameraConnection.updateCameraPosition(minecraft.player.getBlockX(),
                    minecraft.player.getBlockY(), minecraft.player.getBlockZ());
        }
        for (int count = 0; count < MAX_MAIN_PER_TICK; count++) {
            MainTask task = MAIN.poll();
            if (task == null) break;
            try {
                if (task.connection() == connection) task.apply(level, renderer, retained);
                else task.cancel();
            } catch (RuntimeException failure) {
                task.fail(failure);
                task.connection().fail("main-thread Virtual Surface task failed", failure);
            }
        }

        retained.activations.retireFenceComplete();
        retained.releaseUnretainedRoots();
        Connection current = connection;
        if (current == null) return;
        current.scheduleCachePins();
        renderer.updatePredictionTiming(current.predictionTiming());
        // Consume the completed cut for the renderer's currently active manifest before
        // announcing the next pending snapshot. Otherwise continuous residency updates keep
        // every refined result one snapshot behind and permanently strip its cancellation and
        // full-frontier authority.
        SelectionBatch selection;
        while ((selection = renderer.pollSelectionBatch()) != null) {
            current.offer(new SelectionEvent(selection));
        }
        SelectionManifest manifest = current.pendingManifest.getAndSet(null);
        if (manifest != null) {
            try {
                renderer.publishSelectionManifest(manifest);
            } catch (RuntimeException | Error failure) {
                manifest.close();
                throw failure;
            }
            current.offer(new ManifestPublishedEvent(manifest.generation(),
                    manifest.snapshotId(), manifest.authorityId()));
        }
        current.pollActivationFences();
    }

    private static void startLocked(String dimension, SessionResources retained) {
        if (networkThread != null && networkThread.isAlive()) return;
        long generation = SESSION.incrementAndGet();
        ClientLodDebug.sessionStarted(generation, dimension);
        Thread thread = new Thread(() -> runNetwork(generation, dimension, retained),
                "Voxy Virtual Surface");
        thread.setDaemon(true);
        networkThread = thread;
        thread.start();
    }

    private static void runNetwork(long session, String dimension, SessionResources retained) {
        long retryMillis = 500;
        while (SESSION.get() == session && resources == retained) {
            Connection active = null;
            try {
                active = new Connection(session, dimension, retained);
                if (SESSION.get() != session || resources != retained) {
                    active.close();
                    return;
                }
                connection = active;
                active.runLoop();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception failure) {
                if (SESSION.get() != session || resources != retained) return;
                ClientLodDebug.sessionFailed(failure);
                Logger.warn("Virtual Surface unavailable; retrying", failure);
            } finally {
                if (active != null) active.close();
                if (connection == active) connection = null;
            }
            try {
                Thread.sleep(retryMillis);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
            retryMillis = Math.min(10_000, retryMillis * 2);
        }
    }

    private static void putMain(MainTask task) throws InterruptedException {
        MAIN.put(task);
    }

    /** Completes GPU/resource teardown on the render thread after its network owner exits. */
    private static void reapRetiredSessions() {
        int count = RETIRED.size();
        for (int index = 0; index < count; index++) {
            RetiredSession retired = RETIRED.poll();
            if (retired == null) return;
            if (retired.owner != null && retired.owner.isAlive()
                    || retired.connection != null && !retired.connection.workersTerminated()) {
                RETIRED.offer(retired);
                continue;
            }
            if (retired.connection != null) retired.connection.releaseOwnedState();
            closeResources(retired.resources, retired.resetSelection);
        }
    }

    private static final class SessionResources implements AutoCloseable {
        private final RootDemandPlan.Limits planLimits;
        private final AtomicReference<ObjectCache> cache =
                new AtomicReference<>(ObjectCache.disabled());
        private final ResidencyManager residency;
        private final MicrotileActivationManager activations;
        private final VoxyRenderSystem renderer;
        private final Set<RootToken> pinnedRoots = ConcurrentHashMap.newKeySet();
        private final AtomicBoolean cachePinsDirty = new AtomicBoolean();
        private final AtomicBoolean cacheRetiring = new AtomicBoolean();
        private final AtomicBoolean closed = new AtomicBoolean();
        private volatile RootToken authoritativeRoot;

        private SessionResources(String dimension, VoxyRenderSystem renderer) {
            this.renderer = Objects.requireNonNull(renderer, "renderer");
            Path root = Minecraft.getInstance().gameDirectory.toPath()
                    .resolve(".voxy").resolve("virtual-surface");
            int objectLimit = MAX_RESIDENT_OBJECTS;
            // Manifest reachability and physical residency are independent bounds.  Capping
            // the planner's immutable object-handle namespace at the resident-object count
            // allowed a handful of descriptor pages to prevent discovery of every later root.
            this.planLimits = new RootDemandPlan.Limits(
                    ManifestCodec.MAX_OBJECT_REFERENCES,
                    RootDemandPlan.MAX_STRUCTURAL_NODES);
            ResidencyManager createdResidency = null;
            MicrotileActivationManager createdActivations = null;
            try {
                createdResidency = new ResidencyManager(dimension,
                        new ResidencyManager.Limits(objectLimit, objectLimit / 2));
                createdActivations = renderer.createVirtualSurfaceActivationManager();
            } catch (RuntimeException | Error failure) {
                if (createdActivations != null) createdActivations.close();
                if (createdResidency != null) createdResidency.close();
                throw failure;
            }
            this.residency = createdResidency;
            this.activations = createdActivations;
            ObjectCache.Limits cacheLimits = new ObjectCache.Limits(objectLimit, 4L << 30,
                    WireMessage.MAX_COMPRESSED_OBJECT_BYTES);
            CACHE_LIFECYCLE.execute(() -> installCache(
                    ObjectCache.openBestEffort(root, cacheLimits)));
        }

        private ObjectCache cache() {
            return this.cache.get();
        }

        private void installCache(ObjectCache opened) {
            if (this.closed.get()) {
                opened.close();
                return;
            }
            ObjectCache previous = this.cache.getAndSet(opened);
            if (this.closed.get() && this.cache.compareAndSet(opened, ObjectCache.disabled())) {
                opened.close();
            }
            previous.close();
            this.cachePinsDirty.set(true);
        }

        private void retireCacheAfter(Thread owner, Connection connection) {
            if (!this.cacheRetiring.compareAndSet(false, true)) return;
            CACHE_LIFECYCLE.execute(() -> {
                boolean interrupted = false;
                while (owner != null && owner.isAlive()
                        || connection != null && !connection.workersTerminated()) {
                    try {
                        if (owner != null && owner.isAlive()) owner.join(100);
                        else Thread.sleep(100);
                    } catch (InterruptedException ignored) {
                        interrupted = true;
                    }
                }
                this.cache.getAndSet(ObjectCache.disabled()).close();
                if (interrupted) Thread.currentThread().interrupt();
            });
        }

        private void setAuthoritativeRoot(RootToken root) {
            this.authoritativeRoot = Objects.requireNonNull(root, "root");
        }

        private boolean pinRootObject(RootToken root, Hash256 hash) {
            ResidencyManager.PinResult result = this.residency.pinRootObject(root, hash);
            if (result == ResidencyManager.PinResult.MISSING) return false;
            if (result == ResidencyManager.PinResult.CHANGED) this.cachePinsDirty.set(true);
            this.pinnedRoots.add(root);
            return true;
        }

        private boolean pinRootObjects(RootToken root, Collection<Hash256> hashes) {
            ResidencyManager.PinResult result = this.residency.pinRootObjects(root, hashes);
            if (result == ResidencyManager.PinResult.MISSING) return false;
            if (result == ResidencyManager.PinResult.CHANGED) this.cachePinsDirty.set(true);
            this.pinnedRoots.add(root);
            return true;
        }

        private boolean reconcileRootPins(RootToken root, Collection<Hash256> hashes) {
            ResidencyManager.PinResult result = this.residency.reconcileRootPins(root, hashes);
            if (result == ResidencyManager.PinResult.MISSING) return false;
            if (result == ResidencyManager.PinResult.CHANGED) this.cachePinsDirty.set(true);
            this.pinnedRoots.add(root);
            return true;
        }

        private void releaseUnretainedRoots() {
            RootToken current = this.authoritativeRoot;
            for (RootToken root : List.copyOf(this.pinnedRoots)) {
                if (root.equals(current) || this.activations.retainsRoot(root)) continue;
                if (this.residency.releaseRootPins(root)) {
                    this.cachePinsDirty.set(true);
                }
                this.pinnedRoots.remove(root);
            }
            this.residency.reclaimUnreferenced();
        }

        @Override
        public void close() {
            close(true);
        }

        private void close(boolean resetSelection) {
            if (!this.closed.compareAndSet(false, true)) return;
            RuntimeException failure = null;
            try { this.activations.close(); } catch (RuntimeException closeFailure) {
                failure = closeFailure;
            }
            try { this.residency.close(); } catch (RuntimeException closeFailure) {
                if (failure == null) failure = closeFailure; else failure.addSuppressed(closeFailure);
            }
            if (!this.cacheRetiring.get()) {
                ObjectCache closingCache = this.cache.getAndSet(ObjectCache.disabled());
                CACHE_LIFECYCLE.execute(closingCache::close);
            }
            if (resetSelection) {
                try { this.renderer.resetVirtualSurfaceSelection(); }
                catch (RuntimeException closeFailure) {
                    if (failure == null) failure = closeFailure;
                    else failure.addSuppressed(closeFailure);
                }
            }
            this.pinnedRoots.clear();
            if (failure != null) throw failure;
        }
    }

    private static final class Connection implements AutoCloseable {
        private final long session;
        private final long selectionAuthority;
        private final Thread ownerThread;
        private final String dimension;
        private final SessionResources resources;
        private final QuicClient quic;
        private final ExecutorService decoderWorker;
        private final ExecutorService mesherWorker;
        private final ExecutorService cacheReadWorker;
        private final ExecutorService cacheWriteWorker;
        private final Set<Thread> decoderThreads = ConcurrentHashMap.newKeySet();
        private final Set<Thread> mesherThreads = ConcurrentHashMap.newKeySet();
        private final ObjectDecoder decoder;
        private final ArrayBlockingQueue<StateEvent> state = new ArrayBlockingQueue<>(1024);
        /**
         * At most one signal per active object stream.  Object bodies never enter the general
         * state queue: a full renderer/control queue must only pause that QUIC stream, not tear
         * down unrelated streams or the connection.
         */
        private final ConcurrentLinkedQueue<ActiveRequest> readyRequests =
                new ConcurrentLinkedQueue<>();
        private final AtomicLong outstandingBytes = new AtomicLong();
        private final AtomicLong roundTripMicros = new AtomicLong(
                PredictionTiming.DEFAULT.roundTripMicros());
        private final AtomicLong throughputBytesPerSecond = new AtomicLong(
                PredictionTiming.DEFAULT.throughputBytesPerSecond());
        private final AtomicLong meshingMicros = new AtomicLong(
                PredictionTiming.DEFAULT.meshingMicros());
        private final AtomicReference<SelectionManifest> pendingManifest = new AtomicReference<>();
        private final SelectionManifestBuilder selectionManifestBuilder =
                new SelectionManifestBuilder();
        private final AtomicReference<CameraPosition> cameraPosition = new AtomicReference<>();
        private final AtomicReference<Throwable> asynchronousFailure = new AtomicReference<>();
        private final java.util.concurrent.Semaphore wakeup =
                new java.util.concurrent.Semaphore(0);
        private final Map<Hash256, ActiveRequest> outstandingObjects = new HashMap<>();
        private final Map<Hash256, Integer> objectStreamFailures = new HashMap<>();
        private final Map<Hash256, DelayedRetry> delayedObjectRetries = new HashMap<>();
        private final Set<ActiveRequest> activeRequests = new HashSet<>();
        private final Map<Integer, DictionaryCodec.Dictionary> dictionaries =
                new ConcurrentHashMap<>();
        private final Map<Hash256, Integer> dictionaryIds = new HashMap<>();
        private final Map<SpatialNode, CompatibilityState> compatibility = new HashMap<>();
        private final java.util.concurrent.atomic.AtomicBoolean closing =
                new java.util.concurrent.atomic.AtomicBoolean();
        private final java.util.concurrent.atomic.AtomicBoolean finalized =
                new java.util.concurrent.atomic.AtomicBoolean();
        private final java.util.concurrent.atomic.AtomicBoolean ownedStateReleased =
                new java.util.concurrent.atomic.AtomicBoolean();
        private final java.util.concurrent.CountDownLatch shutdownStarted =
                new java.util.concurrent.CountDownLatch(1);
        private final CutTable desiredCuts = new CutTable();
        private final CutTable renderableCuts = new CutTable();
        private final CutTable activationCuts = new CutTable();
        /** Exact structural nodes waiting for their bounded descriptor page. */
        private final CutTable descriptorDemands = new CutTable();
        private final CutTable coverageCuts = new CutTable();
        private final HandlePriorities selectedContentScratch = new HandlePriorities();
        private final HandlePriorities requestedContentScratch = new HandlePriorities();
        private final HandlePriorities selectedNeighborScratch = new HandlePriorities();
        private final NodeKeys nodeKeyScratch = new NodeKeys();
        private final Map<SpatialNode, Long> lastRelevantSelectionEpoch = new HashMap<>();
        private final Set<SpatialNode> compiling = new HashSet<>();
        private final Map<SpatialNode, RootToken> awaitingFence = new HashMap<>();
        private final Set<SpatialNode> retiring = new HashSet<>();
        private final ContentPipeline content = new ContentPipeline();
        private volatile boolean open = true;
        private volatile boolean metadataResync;
        private volatile long publishedSnapshot;
        private volatile long issuedSnapshot;
        /** Oldest snapshot allowed to cross the current camera-authority barrier. */
        private long minimumValidSnapshot = 1;
        private volatile RootToken authoritativeRoot;
        private RootDemandPlan plan;
        private CatalogCodec.Catalog catalog;
        private ContentPipeline.CatalogMappings mappings;
        private CatalogModelCompatibility modelCompatibility;
        private long nextSnapshot = 1;
        /** Handle namespace currently represented by the five persistent cut tables. */
        private volatile long cutPlanRevision = Long.MIN_VALUE;
        private boolean manifestDirty;
        private volatile boolean completeFrontier;
        private boolean rootReadySent;
        private boolean serverHello;
        private volatile CameraPosition queriedCameraPosition;
        private CameraDomainLease cameraDomainLease;
        private long nextCameraDomainSequence = 1;
        private long pendingCameraDomainSequence;
        private long lastCameraDomainQueryNanos;
        private long cameraVisibilityDomain;
        private volatile long selectionEpoch;
        private long throughputWindowStart = System.nanoTime();
        private long throughputWindowBytes;
        private int requestBatchSize = MAX_MICROTILE_REQUEST_BATCH;
        /** Owner-thread epoch for plan selections and resident metadata that affect root pins. */
        private long pinInputsRevision;
        private long reconciledPinInputsRevision = Long.MIN_VALUE;
        private long reconciledActivationRetentionRevision = Long.MIN_VALUE;

        private Connection(long session, String dimension, SessionResources resources)
                throws IOException {
            this.session = session;
            this.selectionAuthority = SELECTION_AUTHORITIES.incrementAndGet();
            if (this.selectionAuthority == 0) {
                throw new IllegalStateException("selection authority exhausted");
            }
            this.ownerThread = Thread.currentThread();
            this.dimension = dimension;
            this.resources = resources;
            QuicClient openedQuic = null;
            ExecutorService openedDecoderWorker = null;
            ExecutorService openedMesherWorker = null;
            ExecutorService openedCacheReader = null;
            ExecutorService openedCacheWriter = null;
            ObjectDecoder openedDecoder = null;
            try {
                openedQuic = QuicEndpointDiscovery.connect();
                openedDecoderWorker = Executors.newFixedThreadPool(MAX_DECODER_JOBS, task -> {
                    Thread thread = new Thread(task, "Voxy object decoder");
                    thread.setDaemon(true);
                    this.decoderThreads.add(thread);
                    return thread;
                });
                openedMesherWorker = Executors.newFixedThreadPool(MAX_MESHING_JOBS, task -> {
                    Thread thread = new Thread(task, "Voxy hybrid mesher");
                    thread.setDaemon(true);
                    this.mesherThreads.add(thread);
                    return thread;
                });
                openedCacheReader = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                        new ArrayBlockingQueue<>(16), task -> {
                    Thread thread = new Thread(task, "Voxy cache reader");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
                openedCacheWriter = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                        new ArrayBlockingQueue<>(16), task -> {
                    Thread thread = new Thread(task, "Voxy cache writer");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
                openedDecoder = ObjectDecoder.withNativeZstd(openedDecoderWorker,
                        id -> Optional.ofNullable(this.dictionaries.get(id)));
                openedQuic.sendHello(dimension);
            } catch (Throwable failure) {
                if (openedDecoder != null) openedDecoder.close();
                if (openedDecoderWorker != null) openedDecoderWorker.shutdownNow();
                if (openedMesherWorker != null) openedMesherWorker.shutdownNow();
                if (openedCacheReader != null) openedCacheReader.shutdownNow();
                if (openedCacheWriter != null) openedCacheWriter.shutdownNow();
                if (openedQuic != null) openedQuic.close();
                if (failure instanceof IOException io) throw io;
                if (failure instanceof RuntimeException runtime) throw runtime;
                if (failure instanceof Error error) throw error;
                throw new IOException("could not initialize Voxy client connection", failure);
            }
            this.quic = openedQuic;
            this.decoderWorker = openedDecoderWorker;
            this.mesherWorker = openedMesherWorker;
            this.cacheReadWorker = openedCacheReader;
            this.cacheWriteWorker = openedCacheWriter;
            this.decoder = openedDecoder;
            this.quic.setActivityListener(this::signalActivity);
        }

        private PredictionTiming predictionTiming() {
            return new PredictionTiming(this.roundTripMicros.get(),
                    this.throughputBytesPerSecond.get(), this.outstandingBytes.get(),
                    this.meshingMicros.get());
        }

        private void updateCameraPosition(int blockX, int blockY, int blockZ) {
            CameraPosition next = new CameraPosition(blockX, blockY, blockZ);
            if (!next.equals(this.cameraPosition.getAndSet(next))) signalActivity();
        }

        private void requestMetadataResync() {
            this.metadataResync = true;
            signalActivity();
        }

        private String requestDebugSnapshot() {
            if (this.open) offer(DebugSnapshotEvent.INSTANCE);
            return ClientLodDebug.latestSnapshot();
        }

        private void runLoop() throws Exception {
            Logger.info("Using Virtual Surface over QUIC " + this.quic.description());
            while (this.open && SESSION.get() == this.session) {
                // Signals arriving during the previous pass only need one follow-up pass. Drain
                // them here, before processing their authoritative queues, so a response burst
                // cannot leave thousands of stale permits that keep the planner spinning.
                this.wakeup.drainPermits();
                drainObjectHandoffs();
                QuicClient.ControlMessage control;
                while ((control = this.quic.pollControl()) != null) handleControl(control);
                if (!this.quic.isOpen()) {
                    throw new IOException("Voxy QUIC connection closed", this.quic.failure());
                }

                StateEvent event = this.state.poll();
                if (event != null) {
                    handle(event);
                    while ((event = this.state.poll()) != null) handle(event);
                }
                drainObjectHandoffs();
                if (!this.serverHello) {
                    awaitActivity();
                    continue;
                }

                reconcileMetadata();
                updateCameraDomainQuery();
                expireStalledStreams();
                cancelObsoleteStreams();
                releaseDelayedRetries(false);
                pumpRequests();
                installMicrotiles();
                scheduleRetirements();
                prepareActivations();
                reconcileResidencyPins();
                publishManifest();
                maybeSendRootReady();
                awaitActivity();
            }
            Throwable failure = this.asynchronousFailure.get();
            if (failure != null) throw new IOException(
                    "asynchronous Voxy QUIC client failure", failure);
        }

        private void handleControl(QuicClient.ControlMessage message) throws Exception {
            if (!this.serverHello) {
                if (!(message instanceof QuicClient.ServerHello)) {
                    throw new IOException("terrain server did not begin with HELLO");
                }
                this.serverHello = true;
                return;
            }
            if (message instanceof QuicClient.ServerHello) {
                throw new IOException("terrain server repeated HELLO");
            } else if (message instanceof QuicClient.RootAnnounceMessage announced) {
                RootAnnounce root = announced.value();
                if (!root.dimension().equals(this.dimension)
                        || !root.root().dimensionHash().equals(ObjectHash.dimension(this.dimension))) {
                    throw new IOException("root announcement belongs to another dimension");
                }
                acceptRoot(root);
            } else if (message instanceof QuicClient.CameraDomain camera) {
                acceptCameraDomain(cameraDomain(camera));
            } else if (message instanceof QuicClient.ServerError error) {
                throw new IOException("terrain server error " + error.code() + ": "
                        + error.message());
            } else if (message instanceof QuicClient.ServerShutdown shutdown) {
                throw new IOException("terrain server shutdown: " + shutdown.message());
            }
        }

        private void handle(StateEvent event) throws Exception {
            if (event instanceof CacheLookupFinishedEvent cached) {
                finishCacheLookup(cached);
            } else if (event instanceof CatalogMappedEvent mapped) {
                if (isCurrent(mapped.root)) {
                    this.catalog = mapped.catalog;
                    this.mappings = mapped.mappings;
                    this.modelCompatibility = mapped.compatibility;
                    this.manifestDirty = true;
                }
            } else if (event instanceof SelectionEvent selected) {
                try {
                    acceptSelection(selected.selection);
                } finally {
                    selected.selection.close();
                }
            } else if (event instanceof ManifestPublishedEvent published) {
                if (this.plan != null
                        && this.plan.root().root().generation() == published.generation
                        && published.authority == this.selectionAuthority
                        && this.issuedSnapshot != 0
                        && Long.compareUnsigned(published.snapshot,
                        this.minimumValidSnapshot) >= 0
                        && Long.compareUnsigned(published.snapshot,
                        this.issuedSnapshot) <= 0) {
                    this.publishedSnapshot = published.snapshot;
                }
            } else if (event instanceof CompileEvent compiled) {
                this.compiling.remove(compiled.node);
                if (!isCurrent(compiled.root)) {
                    this.resources.activations.cancelCandidate(compiled.node, compiled.root);
                } else if (compiled.failure != null) {
                    this.resources.activations.cancelCandidate(compiled.node, compiled.root);
                    throw new IOException("hybrid microtile meshing failed", compiled.failure);
                } else if (compiled.status != MicrotileActivationManager.CompileStatus.NO_CANDIDATE) {
                    putMain(new PublishTask(this, compiled.root, compiled.node));
                }
            } else if (event instanceof PublishEvent published) {
                if (published.failure != null) {
                    this.resources.activations.cancelCandidate(published.node, published.root);
                    throw new IOException("atomic renderer publication failed", published.failure);
                }
                if (published.queued) {
                    this.awaitingFence.put(published.node, published.root);
                }
            } else if (event instanceof RetireEvent retired) {
                acceptRetire(retired);
            } else if (event instanceof FenceEvent fence) {
                acceptFence(fence);
            } else if (event instanceof DebugSnapshotEvent) {
                ClientLodDebug.snapshotCaptured(captureDebugSnapshot());
            }
        }

        private String captureDebugSnapshot() {
            CameraPosition camera = this.cameraPosition.get();
            SpatialNode cameraRoot = camera == null ? null : new SpatialNode(
                    ManifestCodec.MAX_LOD,
                    Math.floorDiv(camera.blockX, 32 << ManifestCodec.MAX_LOD),
                    Math.floorDiv(camera.blockY, 32 << ManifestCodec.MAX_LOD),
                    Math.floorDiv(camera.blockZ, 32 << ManifestCodec.MAX_LOD));
            RootDemandPlan.Diagnostics planState = this.plan == null || cameraRoot == null
                    ? null : this.plan.diagnostics(cameraRoot);
            ResidencyManager.Diagnostics residency = this.resources.residency.diagnostics();
            MicrotileActivationManager.Diagnostics activations =
                    this.resources.activations.diagnostics();

            String blocker = pipelineBlocker(planState, camera, activations);
            String root = this.plan == null ? "none"
                    : Long.toUnsignedString(this.plan.root().root().generation());
            String cameraText = camera == null ? "none"
                    : camera.blockX + "," + camera.blockY + "," + camera.blockZ;
            String requiredRoot = cameraRoot == null ? "none"
                    : cameraRoot.x() + "," + cameraRoot.y() + "," + cameraRoot.z();
            String coverage = planState == null ? "UNKNOWN"
                    : planState.cameraCoverage().name();
            String loadedManifestBounds = planState == null ? "none"
                    : "x[" + planState.minRootX() + ',' + planState.maxRootX()
                    + "]y[" + planState.minRootY() + ',' + planState.maxRootY()
                    + "]z[" + planState.minRootZ() + ',' + planState.maxRootZ() + ']';
            String metadata = planState == null ? "none"
                    : "roots=" + planState.metadataRoots()
                    + ",directories=" + planState.loadedDirectories() + '/'
                    + planState.expectedDirectories()
                    + ",manifests=" + planState.loadedManifests() + '/'
                    + planState.expectedManifests()
                    + ",pages=" + planState.loadedDescriptorPages() + '/'
                    + planState.expectedDescriptorPages()
                    + ",queued=" + planState.queuedMetadata()
                    + ",inFlight=" + planState.inFlightMetadata()
                    + ",complete=" + planState.discoveryComplete()
                    + ",capacityBlocked=" + planState.metadataCapacityBlocked()
                    + ",windowAvailable=" + planState.availableWindowRoots()
                    + ",windowPending=" + planState.pendingWindowRoots()
                    + ",windowUnadvertised=" + planState.absentWindowRoots()
                    + ",sampleAbsent=" + spatialText(planState.sampleAbsentWindowRoot());
            String objects = planState == null ? "none"
                    : "processed=" + planState.processedObjects() + '/'
                    + planState.expectedObjects()
                    + ",queued=" + planState.queuedCoverage() + '/'
                    + planState.queuedCurrent() + '/' + planState.queuedPredicted()
                    + ",inFlight=" + planState.inFlightObjects();
            String activationBlockersText = ClientLodDebug.activationSummary();
            String renderText = ClientLodDebug.renderSummary();
            return "blocker=" + blocker
                    + " session=" + this.session
                    + " dimension=" + this.dimension
                    + " transport=QUIC"
                    + " hello=" + this.serverHello
                    + " rootGeneration=" + root
                    + " camera=" + cameraText
                    + " requiredLod4=" + requiredRoot
                    + " cameraCoverage=" + coverage
                    + " loadedManifestBounds=" + loadedManifestBounds
                    + " metadata={" + metadata + '}'
                    + " objects={" + objects + '}'
                    + " cuts={desired=" + this.desiredCuts.size()
                    + ",renderable=" + this.renderableCuts.size()
                    + ",activation=" + this.activationCuts.size()
                    + ",descriptors=" + this.descriptorDemands.size()
                    + ",coverage=" + this.coverageCuts.size() + '}'
                    + " selection={issued=" + this.issuedSnapshot
                    + ",published=" + this.publishedSnapshot
                    + ",completeFrontier=" + this.completeFrontier + '}'
                    + " requests={streams=" + this.activeRequests.size()
                    + ",objects=" + this.outstandingObjects.size()
                    + ",bytes=" + this.outstandingBytes.get()
                    + ",stateQueue=" + this.state.size()
                    + ",mainQueue=" + MAIN.size() + '}'
                    + " residency={objects=" + residency.objects() + '/'
                    + residency.objectLimit() + ",decoded=" + residency.decodedObjects()
                    + ",microtiles=" + residency.preparedMicrotiles()
                    + ",manifests=" + residency.manifestObjects() + '/'
                    + residency.manifestLimit() + ",pinnedRoots=" + residency.pinnedRoots()
                    + ",pins=" + residency.pinnedObjects() + '}'
                    + " activation={slots=" + activations.slots()
                    + ",candidates=" + activations.candidates()
                    + ",compiling=" + activations.compiling()
                    + ",pending=" + activations.pendingPublications()
                    + ",active=" + activations.active()
                    + ",removing=" + activations.pendingRemovals()
                    + ",retired=" + activations.retiredGroups()
                    + ",activeBytes=" + activations.activeGeometryBytes()
                    + ",pendingBytes=" + activations.pendingGeometryBytes() + '}'
                    + " activationBlockers={" + activationBlockersText + '}'
                    + " render={" + renderText + '}';
        }

        private String pipelineBlocker(RootDemandPlan.Diagnostics planState,
                                       CameraPosition camera,
                                       MicrotileActivationManager.Diagnostics activations) {
            if (!this.serverHello) return "WAITING_FOR_SERVER_HELLO";
            if (this.plan == null) return "WAITING_FOR_ROOT_ANNOUNCEMENT";
            if (camera == null) return "WAITING_FOR_CAMERA";
            if (planState == null) return "WAITING_FOR_METADATA_DIAGNOSTICS";
            return switch (planState.cameraCoverage()) {
                case OUTSIDE_CLIENT_WINDOW -> "CAMERA_ROOT_OUTSIDE_CLIENT_WINDOW";
                case DISCOVERING -> "DISCOVERING_CAMERA_ROOT";
                case MANIFEST_PENDING -> "DOWNLOADING_CAMERA_MANIFEST";
                case ABSENT_FROM_PUBLISHED_ROOT -> "SERVER_ROOT_NOT_PUBLISHED";
                case AVAILABLE -> laterPipelineBlocker(planState, activations);
            };
        }

        private String laterPipelineBlocker(RootDemandPlan.Diagnostics planState,
                                            MicrotileActivationManager.Diagnostics activations) {
            if (!this.plan.bootstrapObjectsProcessed()) return "WAITING_FOR_CATALOG_OR_DICTIONARY";
            if (this.mappings == null || this.modelCompatibility == null) {
                return "WAITING_FOR_CATALOG_MAPPING";
            }
            if (!planState.discoveryComplete()) return "DISCOVERING_SELECTED_METADATA";
            if (this.issuedSnapshot == 0) return "WAITING_TO_PUBLISH_SELECTION_MANIFEST";
            if (this.publishedSnapshot != this.issuedSnapshot) {
                return "WAITING_FOR_RENDERER_MANIFEST_HANDOFF";
            }
            if (this.desiredCuts.size() == 0) {
                return this.completeFrontier ? "GPU_SELECTION_EMPTY" : "WAITING_FOR_GPU_SELECTION";
            }
            String activationBlocker = ClientLodDebug.activationBlocker();
            if (activationBlocker != null) return activationBlocker;
            if (!this.compiling.isEmpty()) return "MESHING";
            if (!this.awaitingFence.isEmpty()) return "WAITING_FOR_GPU_ACTIVATION_FENCE";
            if (activations.active() == 0) return "NO_ACTIVE_GEOMETRY";
            String renderBlocker = ClientLodDebug.renderBlocker(activations.active());
            if (renderBlocker != null) return renderBlocker;
            return this.rootReadySent ? "READY" : "VISIBLE_COVERAGE_NOT_ROOT_READY";
        }

        private static String spatialText(SpatialNode node) {
            return node == null ? "none"
                    : node.x() + "," + node.y() + "," + node.z();
        }

        private void drainObjectHandoffs() throws Exception {
            ActiveRequest request;
            while ((request = this.readyRequests.poll()) != null) {
                request.beginOwnerDrain();
                NetworkHandoff input = request.beginNetworkDecode();
                if (input != null) {
                    decodeObject(request, input.encoded, input.release, false);
                }
                DecodedObjectEvent decoded = request.takeNetworkDecode();
                if (decoded != null) acceptDecodedObject(decoded);
                while ((decoded = request.takeCachedDecode()) != null) {
                    acceptDecodedObject(decoded);
                }
                StreamFinishedEvent terminal = request.takeTerminal();
                if (terminal != null) finishStream(request, terminal.failure);
            }
        }

        private void acceptRoot(RootAnnounce announced) {
            if (this.plan != null) {
                RootAnnounce present = this.plan.root();
                int order = Long.compareUnsigned(announced.root().generation(),
                        present.root().generation());
                if (order < 0) return;
                if (order == 0) {
                    if (!present.equals(announced)) {
                        throw new IllegalArgumentException("conflicting root generation");
                    }
                    this.objectStreamFailures.clear();
                    if (!this.delayedObjectRetries.isEmpty()) releaseDelayedRetries(true);
                    return;
                }
            }
            cancelAllRequests(false);
            this.resources.setAuthoritativeRoot(announced.root());
            this.authoritativeRoot = announced.root();
            synchronized (METADATA_ROOTS) {
                this.plan = new RootDemandPlan(announced, METADATA_ROOTS, List.of(),
                        this.resources.planLimits);
            }
            this.catalog = null;
            this.mappings = null;
            this.modelCompatibility = null;
            this.dictionaries.clear();
            this.dictionaryIds.clear();
            this.objectStreamFailures.clear();
            this.delayedObjectRetries.clear();
            this.compatibility.clear();
            this.desiredCuts.clear();
            this.renderableCuts.clear();
            this.activationCuts.clear();
            this.descriptorDemands.clear();
            this.coverageCuts.clear();
            invalidateSelectionAuthority();
            clearPendingManifest();
            this.rootReadySent = false;
            this.publishedSnapshot = 0;
            this.issuedSnapshot = 0;
            this.nextSnapshot = 1;
            this.minimumValidSnapshot = 1;
            this.cutPlanRevision = Long.MIN_VALUE;
            this.cameraVisibilityDomain = 0;
            this.cameraDomainLease = null;
            this.pendingCameraDomainSequence = 0;
            this.lastCameraDomainQueryNanos = 0;
            this.queriedCameraPosition = null;
            this.manifestDirty = true;
            this.metadataResync = false;
            this.pinInputsRevision++;
        }

        private void updateCameraDomainQuery() throws IOException {
            if (this.plan == null) return;
            CameraPosition position = this.cameraPosition.get();
            if (position == null) return;
            CameraDomainLease lease = this.cameraDomainLease;
            if (lease != null && lease.root.equals(this.plan.root().root())
                    && lease.contains(position)) return;
            long now = System.nanoTime();
            if (this.pendingCameraDomainSequence != 0
                    && position.equals(this.queriedCameraPosition)
                    && now - this.lastCameraDomainQueryNanos
                    < CAMERA_DOMAIN_QUERY_TIMEOUT_NANOS) return;
            if (now - this.lastCameraDomainQueryNanos < CAMERA_DOMAIN_QUERY_INTERVAL_NANOS) {
                return;
            }
            long sequence = this.nextCameraDomainSequence++;
            if (sequence == 0 || this.nextCameraDomainSequence == 0) {
                throw new IllegalStateException("camera-domain sequence exhausted");
            }
            this.quic.sendCameraDomain(this.plan.root().root(), sequence,
                    position.blockX, position.blockY, position.blockZ);
            this.queriedCameraPosition = position;
            this.pendingCameraDomainSequence = sequence;
            this.lastCameraDomainQueryNanos = now;
            this.cameraDomainLease = null;
            this.cameraVisibilityDomain = 0;
            // A fresh snapshot is also the async GPU authority barrier: results captured before
            // this camera position cannot retire coverage for the new view.
            invalidateSelectionAuthority();
            clearPendingManifest();
            this.publishedSnapshot = 0;
            this.issuedSnapshot = 0;
            this.minimumValidSnapshot = this.nextSnapshot;
            this.manifestDirty = true;
        }

        private static CameraDomainEvent cameraDomain(QuicClient.CameraDomain response) {
            boolean paired = response.sequence() != 0 && switch (response.state()) {
                case 0 -> response.domain() == 0;
                case 1 -> response.domain() == 1;
                case 2 -> Long.compareUnsigned(response.domain(), 2) >= 0;
                default -> false;
            };
            if (!paired) throw new IllegalArgumentException(
                    "CAMERA_DOMAIN state/domain pairing is invalid");
            if (response.minX() > response.maxX() || response.minY() > response.maxY()
                    || response.minZ() > response.maxZ()) {
                throw new IllegalArgumentException("CAMERA_DOMAIN lease bounds are inverted");
            }
            return new CameraDomainEvent(response.root(), response.sequence(), response.domain(),
                    response.minX(), response.minY(), response.minZ(),
                    response.maxX(), response.maxY(), response.maxZ());
        }

        private void acceptCameraDomain(CameraDomainEvent response) {
            if (this.plan == null || !response.root.equals(this.plan.root().root())) return;
            if (this.pendingCameraDomainSequence == 0) return;
            int sequenceOrder = Long.compareUnsigned(response.sequence,
                    this.pendingCameraDomainSequence);
            if (sequenceOrder < 0) return;
            if (sequenceOrder > 0) {
                throw new IllegalArgumentException(
                        "camera-domain response is newer than every outstanding request");
            }
            CameraPosition requested = this.queriedCameraPosition;
            if (requested == null || !response.contains(requested)) {
                throw new IllegalArgumentException(
                        "camera-domain lease does not contain its requested position");
            }
            this.pendingCameraDomainSequence = 0;
            this.cameraDomainLease = response.lease();
            if (this.cameraVisibilityDomain == response.domain) return;
            this.cameraVisibilityDomain = response.domain;
            invalidateSelectionAuthority();
            clearPendingManifest();
            this.publishedSnapshot = 0;
            this.issuedSnapshot = 0;
            this.minimumValidSnapshot = this.nextSnapshot;
            this.manifestDirty = true;
        }

        private boolean hasCurrentCameraDomainLease() {
            if (this.plan == null) return false;
            CameraPosition position = this.cameraPosition.get();
            CameraDomainLease lease = this.cameraDomainLease;
            return position != null && lease != null
                    && lease.root.equals(this.plan.root().root())
                    && lease.domain == this.cameraVisibilityDomain
                    && lease.contains(position);
        }

        private void reconcileMetadata() {
            if (this.plan == null) return;
            List<SpatialNode> desired = null;
            synchronized (METADATA_ROOTS) {
                if (this.metadataResync || !this.plan.hasMetadataRoots(METADATA_ROOTS)) {
                    desired = List.copyOf(METADATA_ROOTS);
                }
            }
            if (desired == null) return;
            this.plan.reconcileMetadataRoots(desired);
            this.metadataResync = false;
            this.manifestDirty = true;
            this.pinInputsRevision++;
        }

        private void pumpRequests() throws Exception {
            if (this.plan == null) return;
            int attempts = 0;
            while (this.activeRequests.size() < MAX_ACTIVE_OBJECT_STREAMS && attempts++ < 32) {
                List<Hash256> hashes = laneStreamCount(QuicClient.Lane.COVERAGE)
                        < MAX_COVERAGE_OBJECT_STREAMS
                        ? this.plan.takeBootstrapObjectRequests(this.requestBatchSize)
                        : List.of();
                if (!hashes.isEmpty()) {
                    startRequest(hashes, false, QuicClient.Lane.COVERAGE);
                    this.manifestDirty = true;
                    continue;
                }
                if (!this.plan.bootstrapObjectsProcessed()) break;

                hashes = laneStreamCount(QuicClient.Lane.COVERAGE)
                        < MAX_COVERAGE_OBJECT_STREAMS
                        ? this.plan.takeSubtreeRequests(1) : List.of();
                if (!hashes.isEmpty()) {
                    startRequest(hashes, true, QuicClient.Lane.COVERAGE);
                    continue;
                }

                QuicClient.Lane lane;
                if (laneStreamCount(QuicClient.Lane.COVERAGE)
                        < MAX_COVERAGE_OBJECT_STREAMS
                        && !(hashes = this.plan.takeContentObjectRequests(
                        ContentPriority.COVERAGE, this.requestBatchSize)).isEmpty()) {
                    lane = QuicClient.Lane.COVERAGE;
                } else if (laneStreamCount(QuicClient.Lane.CURRENT_VIEW)
                        < MAX_CURRENT_OBJECT_STREAMS
                        && !(hashes = this.plan.takeContentObjectRequests(
                        ContentPriority.CURRENT_VIEW, this.requestBatchSize)).isEmpty()) {
                    lane = QuicClient.Lane.CURRENT_VIEW;
                } else if (laneStreamCount(QuicClient.Lane.PREDICTED)
                        < MAX_PREDICTED_OBJECT_STREAMS
                        && !(hashes = this.plan.takeContentObjectRequests(
                        ContentPriority.PREDICTED, this.requestBatchSize)).isEmpty()) {
                    lane = QuicClient.Lane.PREDICTED;
                } else {
                    break;
                }
                startRequest(hashes, false, lane);
                this.manifestDirty = true;
            }
            if (this.plan.discoveryComplete()) this.plan.sealDiscovery();
        }

        private int laneStreamCount(QuicClient.Lane lane) {
            int count = 0;
            for (ActiveRequest request : this.activeRequests) {
                if (request.lane == lane && !request.networkFinished()) count++;
            }
            return count;
        }

        private void startRequest(List<Hash256> hashes, boolean subtree,
                                  QuicClient.Lane lane) throws Exception {
            RootToken root = this.plan.root().root();
            ActiveRequest request = new ActiveRequest(this, root, subtree, lane, hashes);
            this.activeRequests.add(request);
            try {
                this.cacheReadWorker.execute(() -> {
                    Throwable failure = null;
                    ArrayList<CachedObject> cachedObjects = new ArrayList<>();
                    try {
                        for (Hash256 hash : hashes) {
                            Optional<EncodedObject> cached =
                                    this.resources.cache().getEncoded(hash);
                            cached.ifPresent(object -> cachedObjects.add(
                                    new CachedObject(hash, object)));
                        }
                    } catch (Throwable cause) {
                        failure = cause;
                    } finally {
                        offer(new CacheLookupFinishedEvent(request,
                                List.copyOf(cachedObjects), failure));
                    }
                });
            } catch (RejectedExecutionException failure) {
                if (request.finishCacheLookup() && isCurrent(request.root)) {
                    openNetworkRequest(request);
                }
            }
        }

        private void acceptCacheLookup(ActiveRequest request, CachedObject entry) {
            EncodedObject cached = entry.encoded;
            if (!isCurrent(request.root) || request.cancelled()) {
                cached.close();
                return;
            }
            try {
                requirePlan(request.root).requireInFlightResponse(cached.hash(),
                        cached.kind(), request.subtree);
            } catch (IllegalArgumentException malformed) {
                persistCacheAction(() -> this.resources.cache().quarantine(entry.hash));
                cached.close();
                return;
            }
            if (!request.beginCached(entry.hash)) {
                cached.close();
                return;
            }
            decodeObject(request, cached, () -> {}, true);
        }

        private void finishCacheLookup(CacheLookupFinishedEvent event) throws IOException {
            ActiveRequest request = event.request;
            int accepted = 0;
            try {
                for (; accepted < event.cached.size(); accepted++) {
                    acceptCacheLookup(request, event.cached.get(accepted));
                }
            } finally {
                for (; accepted < event.cached.size(); accepted++) {
                    event.cached.get(accepted).encoded.close();
                }
            }
            if (!request.finishCacheLookup() || !isCurrent(request.root)) return;
            if (event.failure != null) {
                Logger.warn("Voxy cache read failed; fetching objects over QUIC: "
                        + event.failure.getMessage());
            }
            openNetworkRequest(request);
        }

        private void openNetworkRequest(ActiveRequest request) throws IOException {
            List<Hash256> missing = request.remainingNetwork();
            if (missing.isEmpty()) {
                request.finishNetwork();
                finishRequestIfDone(request);
                return;
            }
            long estimate = request.subtree ? ESTIMATED_SUBTREE_REQUEST_BYTES
                    : ESTIMATED_CONTENT_REQUEST_BYTES;
            for (Hash256 hash : missing) {
                if (this.outstandingObjects.putIfAbsent(hash, request) != null) {
                    cancelRequest(request, true);
                    throw new IllegalStateException(
                            "object already belongs to an active QUIC stream");
                }
                this.outstandingBytes.addAndGet(estimate);
            }
            try {
                request.beginNetwork(missing.size());
                request.setHandle(this.quic.requestObjects(request.root,
                        request.lane, missing, request));
            } catch (IOException failure) {
                cancelRequest(request, true);
                throw failure;
            } catch (RuntimeException | Error failure) {
                cancelRequest(request, true);
                throw failure;
            }
        }

        private void decodeObject(ActiveRequest request, EncodedObject encoded,
                                  Runnable release, boolean fromCache) {
            if (isCurrent(request.root)) {
                requirePlan(request.root).requireInFlightResponse(encoded.hash(),
                        encoded.kind(), request.subtree);
            }
            if (!fromCache) {
                request.recordResponseLatency();
                recordInboundBytes(WireMessage.HASH_BYTES + 20L
                        + encoded.compressedLength());
            }
            try {
                this.decoder.decode(encoded).whenComplete((decoded, failure) -> {
                    // Take cache ownership before publishing the completion: publication may
                    // immediately release the stream's original body reference.
                    if (!fromCache && failure == null) {
                        persistVerifiedObject(encoded);
                    }
                    if (fromCache) {
                        request.completeCachedDecode(encoded, decoded, failure, release);
                    } else {
                        request.completeNetworkDecode(encoded, decoded, failure, release);
                    }
                });
            } catch (Throwable failure) {
                if (fromCache) {
                    request.completeCachedDecode(encoded, null, failure, release);
                } else {
                    request.completeNetworkDecode(encoded, null, failure, release);
                }
            }
        }

        private void acceptDecodedObject(DecodedObjectEvent event) throws Exception {
            ActiveRequest request = event.request;
            try {
                if (event.fromCache && event.failure != null) {
                    persistCacheAction(() -> this.resources.cache().quarantine(
                            event.encoded.hash()));
                }
                if (isCurrent(request.root)) {
                    RootDemandPlan current = requirePlan(request.root);
                    if (event.failure != null
                            || !current.inFlightResponseRelevant(event.encoded.hash(),
                            request.subtree)) {
                        current.deferInFlightResponse(event.encoded.hash(), request.subtree);
                    } else {
                        acceptObject(request.root, request.subtree,
                                event.encoded, event.decoded);
                        if (!event.fromCache) {
                            this.objectStreamFailures.remove(event.encoded.hash());
                        }
                    }
                }
            } finally {
                completeOutstanding(event.encoded.hash(), request);
                request.processingCompleted();
                finishRequestIfDone(request);
                try {
                    event.encoded.close();
                } finally {
                    event.release.run();
                }
            }
        }

        private void persistVerifiedObject(EncodedObject encoded) {
            PersistObjectTask task = new PersistObjectTask(encoded.retain());
            try {
                this.cacheWriteWorker.execute(task);
            } catch (RejectedExecutionException ignored) {
                task.discard();
            }
        }

        private final class PersistObjectTask implements Runnable {
            private final EncodedObject encoded;
            private final AtomicBoolean owned = new AtomicBoolean(true);

            private PersistObjectTask(EncodedObject encoded) {
                this.encoded = encoded;
            }

            @Override
            public void run() {
                if (!this.owned.compareAndSet(true, false)) return;
                try {
                    resources.cache().putVerified(this.encoded);
                } catch (IOException ignored) {
                    // The cache is disposable; live verified content remains authoritative.
                } finally {
                    this.encoded.close();
                }
            }

            private void discard() {
                if (this.owned.compareAndSet(true, false)) this.encoded.close();
            }
        }

        private void persistCacheAction(CacheAction action) {
            try {
                this.cacheWriteWorker.execute(() -> {
                    try {
                        action.run();
                    } catch (IOException ignored) {
                        // The cache is disposable; live verified content remains authoritative.
                    }
                });
            } catch (RejectedExecutionException ignored) {
                // A full cache queue drops reuse work, never live terrain.
            }
        }

        /** Snapshots pins without taking the cache's disk-I/O monitor on this thread. */
        private void scheduleCachePins() {
            if (!this.resources.cachePinsDirty.getAndSet(false)) return;
            List<Hash256> protectedHashes = new ArrayList<>();
            try {
                this.resources.residency.forEachProtectedHash(protectedHashes::add);
                this.cacheWriteWorker.execute(() -> {
                    try {
                        this.resources.cache().replacePins(protectedHashes::forEach);
                    } catch (RuntimeException failure) {
                        this.resources.cachePinsDirty.set(true);
                    }
                });
            } catch (RejectedExecutionException failure) {
                this.resources.cachePinsDirty.set(true);
            } catch (RuntimeException failure) {
                this.resources.cachePinsDirty.set(true);
                throw failure;
            }
        }

        private void finishStream(ActiveRequest request, Throwable failure) throws IOException {
            request.recordResponseLatency();
            List<Hash256> remaining = request.finishNetwork();
            boolean currentRoot = isCurrent(request.root);
            boolean retry = currentRoot;
            QuicClient.ObjectStreamException streamFailure = failure
                    instanceof QuicClient.ObjectStreamException objectFailure
                    ? objectFailure : null;
            if (streamFailure != null && streamFailure.code() == 1) {
                retry = false;
            } else if (streamFailure != null && streamFailure.code() == 2 && currentRoot) {
                for (Hash256 hash : remaining) scheduleObjectRetry(hash, request.subtree);
                retry = false;
            } else if (streamFailure != null && request.networkRequestCount() == 1
                    && currentRoot && !remaining.isEmpty()) {
                scheduleObjectRetry(remaining.get(0), request.subtree);
                retry = false;
            }
            for (Hash256 hash : remaining) {
                completeOutstanding(hash, request);
                if (retry) {
                    requirePlan(request.root).deferInFlightResponse(hash, request.subtree);
                }
            }
            finishRequestIfDone(request);
            if (streamFailure != null && streamFailure.code() == 1 && this.open) {
                Logger.warn("Voxy QUIC object request was rejected as malformed: "
                        + failure.getMessage());
                throw new IOException("server rejected malformed QUIC object request", failure);
            }
            if (failure != null && streamFailure != null && streamFailure.code() != 2
                    && request.networkRequestCount() > 1) {
                this.requestBatchSize = Math.min(this.requestBatchSize,
                        Math.max(1, request.networkRequestCount() / 2));
                Logger.warn("Voxy QUIC object batch failed; retrying smaller batches: "
                        + failure.getMessage());
                return;
            }
            if (failure != null && this.open) {
                Logger.warn("Voxy QUIC object stream ended; only its current demand will retry: "
                        + failure.getMessage());
            }
        }

        private void scheduleObjectRetry(Hash256 hash, boolean subtree) {
            int failures = this.objectStreamFailures.merge(hash, 1, Integer::sum);
            int exponent = Math.min(failures - 1, MAX_OBJECT_STREAM_FAILURE_RETRIES + 3);
            long delay = TimeUnit.MILLISECONDS.toNanos(100L << exponent);
            this.delayedObjectRetries.put(hash,
                    new DelayedRetry(subtree, System.nanoTime() + delay));
        }

        private void releaseDelayedRetries(boolean all) {
            if (this.plan == null || this.delayedObjectRetries.isEmpty()) return;
            long now = System.nanoTime();
            var iterator = this.delayedObjectRetries.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<Hash256, DelayedRetry> entry = iterator.next();
                if (!all && now - entry.getValue().readyNanos < 0) continue;
                requirePlan(this.plan.root().root()).deferInFlightResponse(
                        entry.getKey(), entry.getValue().subtree);
                iterator.remove();
            }
        }

        private void cancelObsoleteStreams() {
            if (this.plan == null) return;
            for (ActiveRequest request : List.copyOf(this.activeRequests)) {
                if (!isCurrent(request.root) || request.networkFinished()) continue;
                List<Hash256> remaining = request.remainingNetwork();
                if (remaining.isEmpty()) continue;
                boolean required = false;
                for (Hash256 hash : remaining) {
                    if (this.plan.inFlightResponseRelevant(hash, request.subtree)) {
                        required = true;
                        break;
                    }
                }
                if (!required) cancelRequest(request, true);
            }
        }

        private void expireStalledStreams() {
            long now = System.nanoTime();
            for (ActiveRequest request : List.copyOf(this.activeRequests)) {
                long deadline = request.progressDeadlineNanos();
                if (deadline == Long.MAX_VALUE || deadline - now > 0) continue;
                Logger.warn("Voxy QUIC " + request.lane
                        + " object stream made no progress for "
                        + TimeUnit.NANOSECONDS.toSeconds(OBJECT_STREAM_PROGRESS_TIMEOUT_NANOS)
                        + " seconds; resetting and retrying current demand");
                cancelRequest(request, true);
            }
        }

        private void cancelAllRequests(boolean requeue) {
            for (ActiveRequest request : List.copyOf(this.activeRequests)) {
                cancelRequest(request, requeue);
            }
        }

        private void cancelRequest(ActiveRequest request, boolean requeue) {
            request.cancel();
            for (Hash256 hash : request.finishNetwork()) {
                completeOutstanding(hash, request);
                if (requeue && isCurrent(request.root)) {
                    requirePlan(request.root).deferInFlightResponse(hash, request.subtree);
                }
            }
            if (!requeue) {
                var iterator = this.outstandingObjects.entrySet().iterator();
                while (iterator.hasNext()) {
                    Map.Entry<Hash256, ActiveRequest> entry = iterator.next();
                    if (entry.getValue() != request) continue;
                    iterator.remove();
                    this.outstandingBytes.addAndGet(request.subtree
                            ? -ESTIMATED_SUBTREE_REQUEST_BYTES
                            : -ESTIMATED_CONTENT_REQUEST_BYTES);
                }
            }
            finishRequestIfDone(request);
        }

        private void finishRequestIfDone(ActiveRequest request) {
            if (request.done()) this.activeRequests.remove(request);
        }

        private boolean acceptObject(RootToken root, boolean subtree, EncodedObject encoded,
                                     DecodedObject decoded) throws Exception {
            RootDemandPlan current = requirePlan(root);
            if (!this.resources.residency.admitVerifiedObject(encoded, decoded)) {
                current.deferInFlightResponse(decoded.hash(), subtree);
                return false;
            }
            if (subtree) {
                this.resources.residency.installManifestObject(decoded);
                boolean parsed = switch (decoded.kind()) {
                    case ROOT_DIRECTORY -> this.resources.residency
                            .rootDirectory(decoded.hash()).isPresent();
                    case MANIFEST_SUBTREE -> this.resources.residency
                            .manifestSubtree(decoded.hash()).isPresent();
                    case MANIFEST_DESCRIPTOR_PAGE -> this.resources.residency
                            .descriptorPage(decoded.hash()).isPresent();
                    default -> false;
                };
                if (!parsed) {
                    // A parsed metadata object may be reclaimed before this event runs. Retry it
                    // without pinning an unusable canonical envelope.
                    current.deferInFlightResponse(decoded.hash(), true);
                    this.resources.residency.reclaimUnreferenced();
                    return false;
                }
                if (!this.resources.pinRootObject(root, decoded.hash())) {
                    current.deferInFlightResponse(decoded.hash(), true);
                    return false;
                }
                if (decoded.kind() == ObjectKind.ROOT_DIRECTORY) {
                    RootDirectory directory = this.resources.residency
                            .rootDirectory(decoded.hash()).orElseThrow();
                    current.acceptDirectory(decoded.hash(), directory);
                } else if (decoded.kind() == ObjectKind.MANIFEST_SUBTREE) {
                    ManifestSubtree manifest = this.resources.residency
                            .manifestSubtree(decoded.hash()).orElseThrow();
                    current.acceptManifest(decoded.hash(), manifest);
                } else if (decoded.kind() == ObjectKind.MANIFEST_DESCRIPTOR_PAGE) {
                    DescriptorPage page = this.resources.residency
                            .descriptorPage(decoded.hash()).orElseThrow();
                    current.acceptDescriptorPage(decoded.hash(), page);
                } else {
                    throw new IllegalArgumentException("subtree response contains content");
                }
                this.manifestDirty = true;
                this.pinInputsRevision++;
                return true;
            }

            if (!this.resources.pinRootObject(root, decoded.hash())) {
                current.deferInFlightResponse(decoded.hash(), false);
                return false;
            }
            current.acceptObject(decoded.hash(), decoded.kind());
            this.pinInputsRevision++;
            if (decoded.kind() == ObjectKind.CATALOG) {
                putMain(new CatalogTask(this, root, decoded.catalog()));
            } else if (decoded.kind() == ObjectKind.DICTIONARY_SET) {
                List<Hash256> hashes = decoded.dictionarySet().hashes();
                current.expectCompressionDictionaries(hashes);
                this.dictionaryIds.clear();
                for (int index = 0; index < hashes.size(); index++) {
                    this.dictionaryIds.put(hashes.get(index), index + 1);
                }
            } else if (decoded.kind() == ObjectKind.COMPRESSION_DICTIONARY) {
                Integer id = this.dictionaryIds.get(decoded.hash());
                if (id == null) {
                    throw new IllegalArgumentException("dictionary is outside its announced set");
                }
                this.dictionaries.put(id, decoded.dictionary());
            }
            this.manifestDirty = true;
            return true;
        }

        private void installMicrotiles() throws Exception {
            if (this.plan == null || this.mappings == null) return;
            for (int handle = 0; handle < this.plan.objectHandleCount(); handle++) {
                RootDemandPlan.ObjectView object = this.plan.objectView(handle);
                if (!object.processed() || !isMicrotile(object.expected().kind())
                        || this.resources.residency.decodedMicrotile(object.hash()).isPresent()) {
                    continue;
                }
                Optional<DecodedObject> decoded =
                        this.resources.residency.decodedObject(object.hash());
                if (decoded.isEmpty()) {
                    if (this.plan.retryMissingResidentObject(object.hash())) {
                        this.manifestDirty = true;
                    }
                    continue;
                }
                if (!this.resources.residency.installMicrotile(decoded.orElseThrow(),
                        this.mappings.catalogId(), this.mappings.blocks(),
                        this.mappings.biomes())) {
                    break;
                }
                this.manifestDirty = true;
            }
        }

        private void acceptSelection(SelectionBatch selection) {
            SelectionManifest source = selection.manifest();
            if (this.plan == null
                    || selection.generation() != this.plan.root().root().generation()
                    || source.authorityId() != this.selectionAuthority) return;
            long manifestRevision = this.plan.manifestRevision();
            // Object and node handles belong to the exact plan revision captured by their
            // immutable renderer manifest. Metadata pruning may compact both tables without
            // changing the published root generation.
            if (source.planRevision() != manifestRevision) {
                if (this.completeFrontier) invalidateSelectionAuthority();
                this.manifestDirty = true;
                return;
            }
            boolean currentSnapshot = selection.snapshotId() == this.publishedSnapshot;
            if (!currentSnapshot) {
                // An older readback remains valid additions-only authority when its handle
                // namespace is unchanged. It may not cancel newer demand or retire coverage.
                if (selection.snapshotId() == 0 || this.issuedSnapshot == 0
                        || Long.compareUnsigned(selection.snapshotId(),
                        this.minimumValidSnapshot) < 0
                        || Long.compareUnsigned(selection.snapshotId(), this.issuedSnapshot) > 0) {
                    return;
                }
                selection.disableCancellation();
            }
            int objectCapacity = selection.manifest().objectHandleCapacity();
            HandlePriorities selectedContent = this.selectedContentScratch.begin(objectCapacity);
            HandlePriorities requestedContent = this.requestedContentScratch.begin(objectCapacity);
            HandlePriorities selectedNeighbors = this.selectedNeighborScratch.begin(objectCapacity);
            boolean newHandleNamespace = this.cutPlanRevision != manifestRevision;
            if (newHandleNamespace && this.completeFrontier) invalidateSelectionAuthority();
            boolean replace = selection.permitsCancellation() || newHandleNamespace;
            int nodeCapacity = selection.manifest().nodeHandleCapacity();
            this.desiredCuts.begin(replace, nodeCapacity);
            this.renderableCuts.begin(replace, nodeCapacity);
            this.activationCuts.begin(replace, nodeCapacity);
            this.descriptorDemands.begin(replace, nodeCapacity);
            this.coverageCuts.begin(replace, nodeCapacity);
            NodeKeys nodeKeys = this.nodeKeyScratch.begin(
                    selection.manifest().nodeHandleCapacity());
            SelectionBatch.Segment desiredSegment = SelectionBatch.Segment.DESIRED;
            for (int row = 0; row < selection.count(desiredSegment); row++) {
                long key = selection.sectionKey(desiredSegment, row);
                nodeKeys.add(selection.nodeHandle(desiredSegment, row), key);
                if (selectedMasks(selection, desiredSegment, row) == 0) {
                    this.descriptorDemands.add(
                            selection.nodeHandle(desiredSegment, row), key);
                }
                mergeCut(this.desiredCuts, selection, desiredSegment, row);
                collectHandles(selectedContent, selection, desiredSegment, row, false);
                collectSelectedNeighborHandles(selectedNeighbors, selection, desiredSegment, row);
            }
            SelectionBatch.Segment renderableSegment = SelectionBatch.Segment.RENDERABLE;
            for (int row = 0; row < selection.count(renderableSegment); row++) {
                nodeKeys.add(selection.nodeHandle(renderableSegment, row),
                        selection.sectionKey(renderableSegment, row));
                mergeCut(this.renderableCuts, selection, renderableSegment, row);
                collectHandles(selectedContent, selection, renderableSegment, row, false);
                collectSelectedNeighborHandles(selectedNeighbors, selection,
                        renderableSegment, row);
            }
            SelectionBatch.Segment requestSegment = SelectionBatch.Segment.REQUESTS;
            for (int row = 0; row < selection.count(requestSegment); row++) {
                if (selection.priority(requestSegment, row) != SelectionBatch.Priority.PREDICTED) {
                    nodeKeys.add(selection.nodeHandle(requestSegment, row),
                            selection.sectionKey(requestSegment, row));
                    mergeCut(this.activationCuts, selection, requestSegment, row);
                    if (selectedMasks(selection, requestSegment, row) == 0) {
                        this.descriptorDemands.add(selection.nodeHandle(requestSegment, row),
                                selection.sectionKey(requestSegment, row));
                    }
                }
                if (selection.priority(requestSegment, row) == SelectionBatch.Priority.COVERAGE) {
                    this.coverageCuts.add(selection.nodeHandle(requestSegment, row),
                            selection.sectionKey(requestSegment, row));
                }
                collectHandles(requestedContent, selection, requestSegment, row, true);
                collectSelectedNeighborHandles(selectedNeighbors, selection, requestSegment, row);
            }
            // Coverage requests are emitted first and must get the first activation slots.
            // Resident fine-detail selections follow only after their hierarchy prerequisites.
            for (int row = 0; row < selection.count(desiredSegment); row++) {
                mergeCut(this.activationCuts, selection, desiredSegment, row);
            }

            if (selection.permitsCancellation()) {
                // Resolve every manifest-local object handle before demand reconciliation can
                // prune metadata and compact the plan's handle tables.
                this.plan.reconcileSelectedContent(selectedContent.handles,
                        selectedContent.priorities, selectedContent.count);
                this.plan.reconcileSelectedNeighborContent(selectedNeighbors.handles,
                        selectedNeighbors.priorities, selectedNeighbors.count);
                this.plan.reconcileContentRequests(requestedContent.handles,
                        requestedContent.priorities, requestedContent.count);
                this.plan.reconcileDemand(nodeKeys.keys, nodeKeys.count);
            } else {
                this.plan.retainSelectedContent(selectedContent.handles,
                        selectedContent.priorities, selectedContent.count);
                this.plan.retainSelectedNeighborContent(selectedNeighbors.handles,
                        selectedNeighbors.priorities, selectedNeighbors.count);
                this.plan.requestObjectsByHandle(requestedContent.handles,
                        requestedContent.priorities, requestedContent.count);
                for (int index = 0; index < nodeKeys.count; index++) {
                    this.plan.addContentDemand(nodeKeys.keys[index]);
                }
            }
            this.cutPlanRevision = manifestRevision;
            if (this.plan.discoveryComplete()) this.plan.sealDiscovery();
            boolean planChanged = this.plan.manifestRevision() != manifestRevision;
            if (planChanged) {
                this.manifestDirty = true;
                if (this.completeFrontier) invalidateSelectionAuthority();
            } else if (selection.permitsCancellation()) {
                this.selectionEpoch = nextEpoch(this.selectionEpoch);
                this.completeFrontier = true;
            }
            reconcileActivationCandidates();
            markRelevantActiveNodes();
            this.pinInputsRevision++;
        }

        private void prepareActivations() throws Exception {
            boolean diagnose = ClientLodDebug.diagnosticsEnabled();
            int debugBusy = 0;
            int debugMissingBinding = 0;
            int debugNoCompatibleContent = 0;
            int debugMissingContent = 0;
            int debugMissingNeighbors = 0;
            int debugModelsPending = 0;
            int debugPendingModelId = -1;
            int debugStageBlocked = 0;
            int debugPinBlocked = 0;
            int debugWorkerSaturated = 0;
            int debugAlreadyActive = 0;
            int debugSubmitted = 0;
            SpatialNode debugSampleNode = null;
            Hash256 debugSampleHash = null;
            if (this.plan == null || this.mappings == null || this.modelCompatibility == null) {
                if (diagnose) ClientLodDebug.activationPass(this.activationCuts.size(), 0, 0,
                        0, 0, 0, 0, -1, 0, 0, 0, 0, 0, null, null);
                return;
            }
            for (int cutIndex = 0; cutIndex < this.activationCuts.size(); cutIndex++) {
                if (this.compiling.size() >= MAX_MESHING_JOBS) {
                    if (diagnose) debugWorkerSaturated += this.activationCuts.size() - cutIndex;
                    break;
                }
                SpatialNode node = this.activationCuts.nodeAt(cutIndex);
                SelectionCut desired = this.activationCuts.cutAt(cutIndex);
                if (this.compiling.contains(node) || this.awaitingFence.containsKey(node)
                        || this.retiring.contains(node)) {
                    if (diagnose) debugBusy++;
                    continue;
                }
                Binding binding = this.plan.binding(RootDemandPlan.sectionKey(node))
                        .orElse(null);
                if (binding == null) {
                    if (diagnose) {
                        debugMissingBinding++;
                        if (debugSampleNode == null) debugSampleNode = node;
                    }
                    continue;
                }

                CompatibilityState state = this.content.resolveCompatibility(binding,
                        this.modelCompatibility, this.resources.residency::decodedMicrotile);
                CompatibilityState old = this.compatibility.put(node, state);
                if (!state.equals(old)) this.manifestDirty = true;
                Optional<MicrotileActivationManager.ActiveGroup> active =
                        this.resources.activations.active(node);
                SelectionCut retained = active
                        .filter(group -> group.content().root().equals(this.plan.root().root()))
                        .map(group -> union(desired, group.content().selectionCut()))
                        .orElse(desired);
                SelectionCut effective = effectiveCut(retained, state);
                if (effective == null) {
                    if (diagnose) {
                        debugNoCompatibleContent++;
                        if (debugSampleNode == null) debugSampleNode = node;
                    }
                    continue;
                }
                ContentPipeline.ActivationGroup group;
                try {
                    group = this.content.prepareResident(this.plan.root().root(), binding, effective,
                            this.modelCompatibility.rendererIdentity(), this.modelCompatibility,
                            this.resources.residency::decodedMicrotile,
                            hash -> this.resources.residency.objectStatus(hash)
                                    .map(ResidencyManager.ObjectStatus::decoded)
                                    .orElse(false));
                } catch (ContentPipeline.MissingObjectsException missing) {
                    if (diagnose) {
                        debugMissingContent += missing.requestable().size();
                        debugMissingNeighbors += missing.neighborDependencies().size();
                        if (debugSampleNode == null) debugSampleNode = node;
                        if (debugSampleHash == null) {
                            debugSampleHash = !missing.requestable().isEmpty()
                                    ? missing.requestable().getFirst()
                                    : missing.neighborDependencies().isEmpty() ? null
                                    : missing.neighborDependencies().getFirst();
                        }
                    }
                    ContentPriority priority = this.coverageCuts.containsNode(node)
                            ? ContentPriority.COVERAGE : ContentPriority.CURRENT_VIEW;
                    if (!missing.requestable().isEmpty()) {
                        this.plan.requestObjectsByHash(missing.requestable(), priority);
                    }
                    // These are exact dependencies of the selected source slots, not a
                    // widening to unrelated content in the structural node.
                    if (!missing.neighborDependencies().isEmpty()) {
                        this.plan.requestObjectsByHash(
                                missing.neighborDependencies(), priority);
                    }
                    continue;
                } catch (ContentPipeline.ModelsNotReadyException pending) {
                    if (diagnose) {
                        debugModelsPending++;
                        debugPendingModelId = pending.localBlockId();
                        if (debugSampleNode == null) debugSampleNode = node;
                    }
                    // Catalog baking continues on the renderer threads. Other nodes whose actual
                    // terrain models are ready may activate without waiting for this one.
                    continue;
                }
                if (active.isPresent()
                        && active.orElseThrow().content().terrainIdentity()
                        .equals(group.terrainIdentity())
                        && active.orElseThrow().publication().activationFencePassed()) {
                    if (diagnose) debugAlreadyActive++;
                    continue;
                }
                if (!stageActivation(group)) {
                    if (diagnose) {
                        debugStageBlocked++;
                        if (debugSampleNode == null) debugSampleNode = node;
                    }
                    continue;
                }
                List<Hash256> requiredHashes = group.requiredHashes();
                if (!this.resources.pinRootObjects(group.root(), requiredHashes)) {
                    if (diagnose) {
                        debugPinBlocked++;
                        if (debugSampleNode == null) debugSampleNode = node;
                    }
                    this.resources.activations.cancelCandidate(group.node(), group.root());
                    ArrayList<Hash256> missing = new ArrayList<>();
                    for (Hash256 hash : requiredHashes) {
                        if (!this.resources.residency.contains(hash)) missing.add(hash);
                    }
                    if (!missing.isEmpty()) {
                        this.plan.requestObjectsByHash(missing,
                                this.coverageCuts.containsNode(node)
                                        ? ContentPriority.COVERAGE
                                        : ContentPriority.CURRENT_VIEW);
                        this.manifestDirty = true;
                    }
                    continue;
                }
                if (diagnose) debugSubmitted++;
                this.compiling.add(node);
                RootToken root = this.plan.root().root();
                this.mesherWorker.execute(() -> {
                    long meshingStart = System.nanoTime();
                    Throwable failure = null;
                    MicrotileActivationManager.CompileStatus status =
                            MicrotileActivationManager.CompileStatus.NO_CANDIDATE;
                    try {
                        status = this.resources.activations.compile(node, root,
                                RootDemandPlan.sectionKey(node), root.generation());
                    } catch (Throwable cause) {
                        failure = cause;
                    } finally {
                        updateEwma(this.meshingMicros,
                                Math.max(1, TimeUnit.NANOSECONDS.toMicros(
                                        System.nanoTime() - meshingStart)));
                    }
                    offer(new CompileEvent(root, node, status, failure));
                });
            }
            if (diagnose) ClientLodDebug.activationPass(this.activationCuts.size(), debugBusy,
                    debugMissingBinding, debugNoCompatibleContent, debugMissingContent,
                    debugMissingNeighbors, debugModelsPending, debugPendingModelId,
                    debugStageBlocked, debugPinBlocked, debugWorkerSaturated,
                    debugAlreadyActive, debugSubmitted, debugSampleNode, debugSampleHash);
        }

        /** Live renderable coverage outranks disposable cache and unpinned decoded content. */
        private boolean stageActivation(ContentPipeline.ActivationGroup group) {
            return this.resources.activations.stage(group);
        }

        /** Prevents an asynchronous mesh for an older, narrower cut from replacing coverage. */
        private void reconcileActivationCandidates() {
            if (this.plan == null) return;
            RootToken root = this.plan.root().root();
            this.resources.activations.retainCandidates(root, this::requiredActivationCut);
        }

        private SelectionCut requiredActivationCut(SpatialNode node) {
            SelectionCut desired = this.activationCuts.cutForNode(node);
            CompatibilityState state = this.compatibility.get(node);
            if (desired == null || state == null) return null;
            Optional<MicrotileActivationManager.ActiveGroup> active =
                    this.resources.activations.active(node);
            SelectionCut retained = active
                    .filter(group -> group.content().root().equals(this.plan.root().root()))
                    .map(group -> union(desired, group.content().selectionCut()))
                    .orElse(desired);
            return effectiveCut(retained, state);
        }

        private void scheduleRetirements() throws InterruptedException {
            if (this.plan == null || !this.completeFrontier) return;
            if (this.cutPlanRevision != this.plan.manifestRevision()) {
                invalidateSelectionAuthority();
                this.manifestDirty = true;
                return;
            }
            Set<Long> resolved = this.plan.resolvedKeys();
            ArrayList<SpatialNode> immediate = new ArrayList<>();
            ArrayList<SpatialNode> outsideCandidates = new ArrayList<>();
            int outsideCount = 0;
            this.lastRelevantSelectionEpoch.keySet().removeIf(
                    node -> !this.resources.activations.isActive(node));
            int[] outside = {0};
            this.resources.activations.forEachActive(node -> {
                long key = RootDemandPlan.sectionKey(node);
                boolean unselected = !this.desiredCuts.containsNode(node)
                        && !this.renderableCuts.containsNode(node);
                boolean structuralFallback = retainsStructuralCoverage(
                        node, this.descriptorDemands);
                boolean absent = !structuralFallback && resolved.contains(key)
                        && this.plan.binding(key).isEmpty();
                boolean hidden = unselected && !structuralFallback
                        && activeEntirelyHidden(node);
                boolean overlapsDemand = structuralFallback
                        || hierarchyOverlaps(node, this.desiredCuts);
                if (overlapsDemand) {
                    this.lastRelevantSelectionEpoch.put(node, this.selectionEpoch);
                } else {
                    outside[0]++;
                }
                if (this.retiring.contains(node) || this.awaitingFence.containsKey(node)
                        || this.compiling.contains(node)) return;
                if (absent || hidden || unselected && (selectedAncestorFenceActive(node)
                        || currentDescendantCutsFenceActive(node))) {
                    immediate.add(node);
                } else if (unselected && !overlapsDemand) {
                    outsideCandidates.add(node);
                }
            });
            outsideCount = outside[0];

            Comparator<SpatialNode> retirementOrder = Comparator
                    .comparingLong((SpatialNode node) ->
                            this.lastRelevantSelectionEpoch.getOrDefault(node, 0L))
                    .thenComparingInt(SpatialNode::lod)
                    .thenComparingInt(SpatialNode::x)
                    .thenComparingInt(SpatialNode::y)
                    .thenComparingInt(SpatialNode::z);
            immediate.sort(retirementOrder);
            outsideCandidates.sort(retirementOrder);
            int queued = 0;
            for (SpatialNode node : immediate) {
                if (queued >= MAX_RETIREMENTS_PER_PASS) return;
                queueRetirement(node);
                queued++;
            }
            int outsideExcess = Math.max(0, outsideCount - MAX_RETAINED_OUTSIDE_VIEW);
            for (SpatialNode node : outsideCandidates) {
                if (queued >= MAX_RETIREMENTS_PER_PASS || outsideExcess-- <= 0) return;
                queueRetirement(node);
                queued++;
            }
        }

        private void queueRetirement(SpatialNode node) throws InterruptedException {
            this.retiring.add(node);
            putMain(new RetireTask(this, this.plan.root().root(), node, this.selectionEpoch,
                    this.cutPlanRevision));
        }

        private void invalidateSelectionAuthority() {
            this.completeFrontier = false;
            this.selectionEpoch = nextEpoch(this.selectionEpoch);
            this.resources.activations.forEachActive(node ->
                    this.lastRelevantSelectionEpoch.put(node, this.selectionEpoch));
        }

        private void markRelevantActiveNodes() {
            this.lastRelevantSelectionEpoch.keySet().removeIf(
                    node -> !this.resources.activations.isActive(node));
            this.resources.activations.forEachActive(node -> {
                if (hierarchyOverlaps(node, this.desiredCuts)
                        || retainsStructuralCoverage(node, this.descriptorDemands)) {
                    this.lastRelevantSelectionEpoch.put(node, this.selectionEpoch);
                }
            });
        }

        private boolean selectedAncestorFenceActive(SpatialNode node) {
            for (int index = 0; index < this.desiredCuts.size(); index++) {
                SpatialNode selected = this.desiredCuts.nodeAt(index);
                SelectionCut cut = this.desiredCuts.cutAt(index);
                if (isAncestor(selected, node)
                        && ancestorCutCoversNode(selected, cut, node)
                        && exactCutFenceActive(selected, cut)) return true;
            }
            return false;
        }

        private boolean currentDescendantCutsFenceActive(SpatialNode fallback) {
            boolean hasDescendant = false;
            for (int index = 0; index < this.desiredCuts.size(); index++) {
                SpatialNode selected = this.desiredCuts.nodeAt(index);
                SelectionCut cut = this.desiredCuts.cutAt(index);
                if (!isAncestor(fallback, selected)) continue;
                hasDescendant = true;
                // Exact microtile masks, current root, and the renderer activation fence are all
                // required. Merely finding an active node is never coverage evidence.
                if (!exactCutFenceActive(selected, cut)) return false;
            }
            return hasDescendant;
        }

        private boolean exactCutFenceActive(SpatialNode node, SelectionCut selected) {
            CompatibilityState state = this.compatibility.get(node);
            if (state == null) return false;
            SelectionCut required = effectiveCut(selected, state);
            if (required == null) return false;
            Optional<MicrotileActivationManager.ActiveGroup> active =
                    this.resources.activations.active(node);
            return active.isPresent()
                    && active.orElseThrow().content().root().equals(this.plan.root().root())
                    && covers(active.orElseThrow().content().selectionCut(), required)
                    && active.orElseThrow().publication().activationFencePassed();
        }

        private boolean activeEntirelyHidden(SpatialNode node) {
            if (this.cameraVisibilityDomain == 0) return false;
            Optional<MicrotileActivationManager.ActiveGroup> active =
                    this.resources.activations.active(node);
            if (active.isEmpty()
                    || !active.orElseThrow().content().root().equals(this.plan.root().root())) {
                return false;
            }
            SelectionCut cut = active.orElseThrow().content().selectionCut();
            for (ContentPipeline.LayerMetadata layer
                    : active.orElseThrow().content().layerMetadata()) {
                long eligible = layer.exteriorVisibilityMask() | layer.unknownVisibilityMask();
                if (this.cameraVisibilityDomain != 1) {
                    for (ManifestCodec.VisibilityMembership membership
                            : layer.visibilityMemberships()) {
                        if (membership.domain() == this.cameraVisibilityDomain) {
                            eligible |= membership.microtileMask();
                            break;
                        }
                    }
                }
                long activeMask = switch (layer.contentClass()) {
                    case EXTERIOR -> cut.exteriorMask();
                    case INTERIOR -> cut.interiorMask();
                    case COMPLEX -> cut.complexMask();
                };
                if ((activeMask & eligible) != 0) return false;
            }
            return true;
        }

        private void reconcileResidencyPins() {
            if (this.plan == null) return;
            long pinRevision = this.pinInputsRevision;
            long activationRevision = this.resources.activations.retentionRevision();
            if (pinRevision == this.reconciledPinInputsRevision
                    && activationRevision == this.reconciledActivationRetentionRevision) return;
            LinkedHashSet<Hash256> retained = new LinkedHashSet<>();
            this.plan.forEachMetadataPin(retained::add);
            this.plan.forEachContentPin(retained::add);
            this.resources.activations.forEachRetainedHash(retained::add);
            var iterator = retained.iterator();
            while (iterator.hasNext()) {
                Hash256 hash = iterator.next();
                if (!this.resources.residency.contains(hash)) {
                    iterator.remove();
                    if (!this.plan.retryMissingResidentObject(hash)) continue;
                    this.manifestDirty = true;
                    this.pinInputsRevision++;
                }
            }
            if (!this.resources.reconcileRootPins(this.plan.root().root(), retained)) return;
            if (pinRevision == this.pinInputsRevision
                    && activationRevision == this.resources.activations.retentionRevision()) {
                this.reconciledPinInputsRevision = pinRevision;
                this.reconciledActivationRetentionRevision = activationRevision;
            }
        }

        private void acceptRetire(RetireEvent retired) throws IOException {
            if (retired.failure != null) {
                this.retiring.remove(retired.node);
                throw new IOException("atomic renderer retirement failed", retired.failure);
            }
            switch (retired.status) {
                case QUEUED -> this.awaitingFence.put(retired.node, retired.root);
                case ALREADY_ABSENT -> {
                    this.retiring.remove(retired.node);
                    this.lastRelevantSelectionEpoch.remove(retired.node);
                    this.manifestDirty = true;
                }
                case BLOCKED -> this.retiring.remove(retired.node);
            }
        }

        private void publishManifest() {
            if (!this.manifestDirty || this.plan == null || this.modelCompatibility == null) return;
            // The render thread consumes at most one immutable snapshot per tick. Keep folding
            // owner-thread changes into dirty state instead of allocating and discarding complete
            // object graphs faster than that consumer can publish them.
            if (this.pendingManifest.get() != null) return;
            long planRevision = this.plan.manifestRevision();
            long snapshot = this.nextSnapshot++;
            if (snapshot == 0 || this.nextSnapshot == 0) {
                throw new IllegalStateException("selection snapshot sequence exhausted");
            }
            SelectionManifest manifest = this.selectionManifestBuilder.build(this.plan,
                    this.resources.residency, this.resources.activations, snapshot,
                    this.selectionAuthority, planRevision,
                    this.plan.selectionTopologyRevision(), this.cameraVisibilityDomain,
                    this.compatibility);
            if (manifest == null) return;
            this.completeFrontier = false;
            this.issuedSnapshot = snapshot;
            this.pendingManifest.set(manifest);
            this.manifestDirty = false;
        }

        private void clearPendingManifest() {
            SelectionManifest pending = this.pendingManifest.getAndSet(null);
            if (pending != null) pending.close();
        }

        private void maybeSendRootReady() throws IOException {
            if (this.rootReadySent || !this.completeFrontier || this.plan == null
                    || !this.plan.bootstrapObjectsProcessed()) return;
            for (int index = 0; index < this.desiredCuts.size(); index++) {
                SpatialNode node = this.desiredCuts.nodeAt(index);
                SelectionCut desired = this.desiredCuts.cutAt(index);
                CompatibilityState compatibility = this.compatibility.get(node);
                if (compatibility == null) return;
                SelectionCut required = effectiveCut(desired, compatibility);
                if (required == null) return;
                Optional<MicrotileActivationManager.ActiveGroup> active =
                        this.resources.activations.active(node);
                if (active.isEmpty()
                        || !active.orElseThrow().content().root().equals(this.plan.root().root())
                        || !covers(active.orElseThrow().content().selectionCut(), required)
                        || !active.orElseThrow().publication().activationFencePassed()) return;
            }
            this.quic.sendRootReady(this.dimension, this.plan.root().root());
            this.rootReadySent = true;
        }

        private void pollActivationFences() {
            for (Map.Entry<SpatialNode, RootToken> entry
                    : List.copyOf(this.awaitingFence.entrySet())) {
                SpatialNode node = entry.getKey();
                Optional<Throwable> failure = this.resources.activations.activationFailure(node);
                if (failure.isPresent()) {
                    offer(new FenceEvent(entry.getValue(), node, failure.orElseThrow()));
                } else if (this.resources.activations.activationFencePassed(node)) {
                    offer(new FenceEvent(entry.getValue(), node, null));
                }
            }
        }

        private void acceptFence(FenceEvent fence) {
            if (!this.awaitingFence.remove(fence.node, fence.root)) return;
            if (fence.failure != null) {
                this.resources.activations.discardFailedPublication(fence.node);
                if (fence.failure
                        instanceof MicrotileActivationManager.PublicationCancelledException) {
                    this.manifestDirty = true;
                    return;
                }
                throw new IllegalStateException("renderer activation fence failed", fence.failure);
            }
            if (this.retiring.remove(fence.node)) {
                this.lastRelevantSelectionEpoch.remove(fence.node);
                this.manifestDirty = true;
                return;
            }
            this.manifestDirty = true;
        }

        private void acceptCatalogOnMain(RootToken root, CatalogCodec.Catalog catalog,
                                         VoxyRenderSystem renderer) {
            if (!isCurrent(root)) return;
            CatalogMapper mapper = renderer.getMapper();
            int[] blocks = new int[catalog.blocks().size()];
            int[] biomes = new int[catalog.biomes().size()];
            for (int id = 0; id < blocks.length; id++) {
                int local = mapper.getIdForBlockState(
                        parseCanonicalState(catalog.blocks().get(id).canonical()));
                if (local < 0 || local >= MAX_BLOCK_ID) {
                    throw new IllegalStateException("local block catalog exceeds 20 bits");
                }
                blocks[id] = local;
            }
            for (int id = 0; id < biomes.length; id++) {
                int local = mapper.getIdForBiome(
                        requireCanonicalBiome(catalog.biomes().get(id)));
                if (local < 0 || local >= MAX_BIOME_ID) {
                    throw new IllegalStateException("local biome catalog exceeds 9 bits");
                }
                biomes[id] = local;
            }
            ContentPipeline.CatalogMappings mapped =
                    new ContentPipeline.CatalogMappings(catalog.catalogId(), blocks, biomes);
            renderer.requestVirtualSurfaceModelBakes(blocks);
            CatalogModelCompatibility compatibility = renderer.createVirtualSurfaceModelCompatibility(
                    catalog, mapped);
            offer(new CatalogMappedEvent(root, catalog, mapped, compatibility));
        }

        private void publishOnMain(RootToken root, SpatialNode node) {
            if (!isCurrent(root)) {
                this.resources.activations.cancelCandidate(node, root);
                return;
            }
            try {
                boolean queued = this.resources.activations.publish(node, root);
                offer(new PublishEvent(root, node, queued, null));
            } catch (Throwable failure) {
                offer(new PublishEvent(root, node, false, failure));
            }
        }

        private void retireOnMain(RootToken root, SpatialNode node, long epoch,
                                  long planRevision) {
            if (!isCurrent(root) || epoch != this.selectionEpoch || !this.completeFrontier
                    || this.plan == null || planRevision != this.cutPlanRevision
                    || planRevision != this.plan.manifestRevision()
                    || !hasCurrentCameraDomainLease()) {
                offer(new RetireEvent(root, node,
                        MicrotileActivationManager.RemovalStatus.BLOCKED, null));
                return;
            }
            try {
                MicrotileActivationManager.RemovalStatus status =
                        this.resources.activations.retire(node, root);
                offer(new RetireEvent(root, node, status, null));
            } catch (Throwable failure) {
                offer(new RetireEvent(root, node,
                        MicrotileActivationManager.RemovalStatus.BLOCKED, failure));
            }
        }

        /** Owns one short-lived QUIC object stream until FIN and every decode complete. */
        private static final class ActiveRequest implements QuicClient.ObjectReceiver {
            private final Connection owner;
            private final RootToken root;
            private final boolean subtree;
            private final QuicClient.Lane lane;
            private final LinkedHashSet<Hash256> pendingNetwork;
            private final long startedNanos = System.nanoTime();
            private QuicClient.Request handle;
            private int processing;
            private int networkRequestCount;
            private boolean cacheLookupFinished;
            private boolean networkFinished;
            private boolean terminalQueued;
            private boolean terminalTaken;
            private boolean cancelled;
            private boolean latencyRecorded;
            private boolean ownerQueued;
            private long lastProgressNanos = this.startedNanos;
            private NetworkHandoff networkHandoff;
            private final ArrayDeque<DecodedObjectEvent> cachedDecodeHandoffs =
                    new ArrayDeque<>();

            private ActiveRequest(Connection owner, RootToken root, boolean subtree,
                                  QuicClient.Lane lane, List<Hash256> hashes) {
                this.owner = owner;
                this.root = root;
                this.subtree = subtree;
                this.lane = lane;
                this.pendingNetwork = new LinkedHashSet<>(hashes);
                if (this.pendingNetwork.size() != hashes.size()) {
                    throw new IllegalArgumentException("duplicate object request hash");
                }
            }

            private synchronized void setHandle(QuicClient.Request handle) {
                this.handle = Objects.requireNonNull(handle, "handle");
                if (this.cancelled) handle.cancel();
            }

            private synchronized void beginNetwork(int count) {
                if (count < 1 || this.networkRequestCount != 0 || this.networkFinished) {
                    throw new IllegalStateException("invalid QUIC network request transition");
                }
                this.networkRequestCount = count;
                this.lastProgressNanos = System.nanoTime();
            }

            private synchronized int networkRequestCount() {
                return this.networkRequestCount;
            }

            private synchronized boolean beginCached(Hash256 hash) {
                if (this.cancelled || this.cacheLookupFinished
                        || !this.pendingNetwork.remove(hash)) return false;
                this.processing++;
                return true;
            }

            private synchronized boolean finishCacheLookup() {
                if (this.cancelled || this.cacheLookupFinished) return false;
                this.cacheLookupFinished = true;
                return true;
            }

            private synchronized boolean cancelled() {
                return this.cancelled;
            }

            @Override
            public void object(EncodedObject object, Runnable release) {
                Throwable malformed = null;
                synchronized (this) {
                    if (this.cancelled || this.networkFinished) {
                        object.close();
                        release.run();
                        return;
                    }
                    if (this.networkHandoff != null) {
                        malformed = new IOException(
                                "QUIC provider delivered an object before releasing its predecessor");
                    } else if (!this.pendingNetwork.remove(object.hash())) {
                        malformed = new IOException(
                                "QUIC stream returned an unrequested object");
                    } else {
                        this.processing++;
                        this.networkHandoff = new NetworkHandoff(object, release);
                    }
                }
                if (malformed != null) {
                    queueTerminal(malformed);
                    object.close();
                    release.run();
                } else {
                    signalOwner();
                }
            }

            @Override
            public synchronized void progress() {
                if (!this.cancelled && !this.networkFinished) {
                    this.lastProgressNanos = System.nanoTime();
                }
            }

            @Override
            public synchronized void complete() {
                queueTerminal(null);
            }

            @Override
            public synchronized void failed(Throwable failure) {
                queueTerminal(failure == null
                        ? new IOException("QUIC object stream failed") : failure);
            }

            private void queueTerminal(Throwable failure) {
                synchronized (this) {
                    if (this.terminalQueued || this.cancelled) return;
                    this.terminalQueued = true;
                    this.terminalFailure = failure;
                }
                signalOwner();
            }

            private Throwable terminalFailure;

            private void signalOwner() {
                synchronized (this) {
                    if (this.ownerQueued) return;
                    this.ownerQueued = true;
                }
                this.owner.readyRequests.offer(this);
                this.owner.signalActivity();
            }

            private synchronized void beginOwnerDrain() {
                this.ownerQueued = false;
            }

            private synchronized NetworkHandoff beginNetworkDecode() {
                if (this.networkHandoff == null || this.networkHandoff.stage != 0) return null;
                this.networkHandoff.stage = 1;
                return this.networkHandoff;
            }

            private void completeNetworkDecode(EncodedObject encoded, DecodedObject decoded,
                                               Throwable failure, Runnable release) {
                synchronized (this) {
                    NetworkHandoff handoff = this.networkHandoff;
                    if (handoff == null || handoff.stage != 1
                            || handoff.encoded != encoded || handoff.release != release) {
                        encoded.close();
                        release.run();
                        return;
                    }
                    handoff.decoded = decoded;
                    handoff.failure = failure;
                    handoff.stage = 2;
                }
                signalOwner();
            }

            private void completeCachedDecode(EncodedObject encoded, DecodedObject decoded,
                                              Throwable failure, Runnable release) {
                boolean discard;
                synchronized (this) {
                    discard = this.cancelled;
                    if (discard) {
                        if (this.processing > 0) this.processing--;
                    } else {
                        this.cachedDecodeHandoffs.addLast(new DecodedObjectEvent(
                                this, encoded, decoded, failure, release, true));
                    }
                }
                if (discard) {
                    try {
                        encoded.close();
                    } finally {
                        release.run();
                    }
                } else {
                    signalOwner();
                }
            }

            private synchronized DecodedObjectEvent takeNetworkDecode() {
                NetworkHandoff handoff = this.networkHandoff;
                if (handoff == null || handoff.stage != 2) return null;
                this.networkHandoff = null;
                return new DecodedObjectEvent(this, handoff.encoded, handoff.decoded,
                        handoff.failure, handoff.release, false);
            }

            private synchronized DecodedObjectEvent takeCachedDecode() {
                return this.cachedDecodeHandoffs.pollFirst();
            }

            private synchronized StreamFinishedEvent takeTerminal() {
                if (!this.terminalQueued || this.terminalTaken) return null;
                this.terminalTaken = true;
                return new StreamFinishedEvent(this, this.terminalFailure);
            }

            private void releaseOwnedHandoff() {
                Runnable release = null;
                ArrayList<DecodedObjectEvent> cached = new ArrayList<>();
                synchronized (this) {
                    if (this.networkHandoff != null) {
                        release = this.networkHandoff.release;
                        this.networkHandoff.encoded.close();
                        this.networkHandoff = null;
                        if (this.processing > 0) this.processing--;
                    }
                    DecodedObjectEvent event;
                    while ((event = this.cachedDecodeHandoffs.pollFirst()) != null) {
                        cached.add(event);
                        if (this.processing > 0) this.processing--;
                    }
                }
                if (release != null) release.run();
                for (DecodedObjectEvent event : cached) {
                    try {
                        event.encoded.close();
                    } finally {
                        event.release.run();
                    }
                }
            }

            private synchronized List<Hash256> finishNetwork() {
                if (this.networkFinished) return List.of();
                this.networkFinished = true;
                List<Hash256> remaining = List.copyOf(this.pendingNetwork);
                this.pendingNetwork.clear();
                return remaining;
            }

            private synchronized List<Hash256> remainingNetwork() {
                return List.copyOf(this.pendingNetwork);
            }

            private synchronized boolean networkFinished() {
                return this.networkFinished;
            }

            private synchronized long progressDeadlineNanos() {
                if (this.cancelled || this.networkRequestCount == 0 || this.networkFinished) {
                    return Long.MAX_VALUE;
                }
                return saturatingAdd(this.lastProgressNanos,
                        OBJECT_STREAM_PROGRESS_TIMEOUT_NANOS);
            }

            private synchronized void processingCompleted() {
                if (this.processing <= 0) throw new IllegalStateException(
                        "object decode completion has no owned input");
                this.processing--;
            }

            private synchronized boolean done() {
                return this.networkFinished && this.processing == 0;
            }

            private synchronized void cancel() {
                if (this.cancelled) return;
                this.cancelled = true;
                if (this.handle != null) this.handle.cancel();
            }

            private synchronized void recordResponseLatency() {
                if (this.latencyRecorded) return;
                this.latencyRecorded = true;
                updateEwma(this.owner.roundTripMicros, Math.max(1,
                        TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - this.startedNanos)));
            }
        }

        private RootDemandPlan requirePlan(RootToken root) {
            if (!isCurrent(root)) {
                throw new IllegalArgumentException("response names an unretained root");
            }
            return this.plan;
        }

        private boolean isCurrent(RootToken root) {
            return Objects.equals(this.authoritativeRoot, root);
        }

        private void completeOutstanding(Hash256 hash, ActiveRequest request) {
            if (this.outstandingObjects.remove(hash, request)) {
                this.outstandingBytes.addAndGet(request.subtree
                    ? -ESTIMATED_SUBTREE_REQUEST_BYTES : -ESTIMATED_CONTENT_REQUEST_BYTES);
            }
        }

        private void recordInboundBytes(long bytes) {
            long now = System.nanoTime();
            this.throughputWindowBytes = saturatingAdd(this.throughputWindowBytes, bytes);
            long elapsed = now - this.throughputWindowStart;
            if (elapsed < THROUGHPUT_WINDOW_NANOS) return;
            long rate = (long) Math.min(Long.MAX_VALUE,
                    this.throughputWindowBytes * (1_000_000_000.0 / elapsed));
            updateEwma(this.throughputBytesPerSecond, Math.max(1, rate));
            this.throughputWindowStart = now;
            this.throughputWindowBytes = 0;
        }

        private boolean offer(StateEvent event) {
            if (this.open && this.state.offer(event)) {
                signalActivity();
                return true;
            }
            IOException failure = new IOException("state queue is closed or full");
            reject(event, failure);
            requestClose(failure);
            return false;
        }

        private void fail(String message, Throwable failure) {
            if (failure == null) Logger.warn(message); else Logger.warn(message, failure);
            requestClose(failure == null ? new IOException(message) : failure);
        }

        private void requestClose(Throwable failure) {
            this.asynchronousFailure.compareAndSet(null, failure);
            this.open = false;
            this.quic.close();
            signalActivity();
        }

        private void signalActivity() {
            this.wakeup.release();
        }

        private void awaitActivity() throws InterruptedException {
            long deadline = nextDeadlineNanos();
            if (deadline == Long.MAX_VALUE) {
                this.wakeup.acquire();
                return;
            }
            long remaining = deadline - System.nanoTime();
            if (remaining > 0) this.wakeup.tryAcquire(remaining, TimeUnit.NANOSECONDS);
        }

        private long nextDeadlineNanos() {
            long deadline = Long.MAX_VALUE;
            if (this.plan != null && this.cameraPosition.get() != null
                    && !hasCurrentCameraDomainLease()) {
                long cameraDeadline = this.lastCameraDomainQueryNanos
                        + (this.pendingCameraDomainSequence == 0
                        ? CAMERA_DOMAIN_QUERY_INTERVAL_NANOS
                        : CAMERA_DOMAIN_QUERY_TIMEOUT_NANOS);
                deadline = Math.min(deadline, cameraDeadline);
            }
            for (DelayedRetry retry : this.delayedObjectRetries.values()) {
                deadline = Math.min(deadline, retry.readyNanos);
            }
            for (ActiveRequest request : this.activeRequests) {
                deadline = Math.min(deadline, request.progressDeadlineNanos());
            }
            return deadline;
        }

        @Override
        public void close() {
            boolean initiator = this.closing.compareAndSet(false, true);
            if (initiator) {
                this.open = false;
                this.quic.close();
                signalActivity();
                this.shutdownStarted.countDown();
            } else {
                awaitLatch(this.shutdownStarted);
            }

            // Lifecycle calls on Minecraft's thread only signal shutdown. The connection owner
            // performs potentially unbounded worker/disk drainage before its resources are
            // reaped on a later render tick.
            if (Thread.currentThread() != this.ownerThread
                    || !this.finalized.compareAndSet(false, true)) return;

            cancelAllRequests(false);
            try {
                this.decoder.close();
            } catch (RuntimeException failure) {
                Logger.warn("Failed to close the object decoder cleanly", failure);
            } finally {
                this.decoderWorker.shutdownNow();
                this.mesherWorker.shutdownNow();
                this.cacheReadWorker.shutdownNow();
                for (Runnable abandoned : this.cacheWriteWorker.shutdownNow()) {
                    if (abandoned instanceof PersistObjectTask persisted) persisted.discard();
                }
            }

            Thread current = Thread.currentThread();
            boolean decoderWorker = this.decoderThreads.contains(current);
            boolean worker = decoderWorker || this.mesherThreads.contains(current);
            long shutdownDeadline = System.nanoTime() + SHUTDOWN_TIMEOUT_NANOS;
            if (!worker) {
                awaitTermination(this.decoderWorker, shutdownDeadline, "object decoder");
                awaitTermination(this.mesherWorker, shutdownDeadline, "hybrid mesher");
                awaitTermination(this.cacheReadWorker, shutdownDeadline, "cache reader");
                awaitTermination(this.cacheWriteWorker, shutdownDeadline, "cache writer");
            } else if (initiator) {
                ExecutorService other = decoderWorker ? this.mesherWorker : this.decoderWorker;
                awaitTermination(other, shutdownDeadline, "other Voxy worker");
                awaitTermination(this.cacheReadWorker, shutdownDeadline, "cache reader");
                awaitTermination(this.cacheWriteWorker, shutdownDeadline, "cache writer");
            }

            if (workersTerminated()) releaseOwnedState();
            else Logger.warn("Voxy workers remain owned after the shutdown deadline; "
                    + "resources will be reaped after they terminate");
        }

        private boolean workersTerminated() {
            return this.quic.isTerminated()
                    && this.decoderWorker.isTerminated() && this.mesherWorker.isTerminated()
                    && this.cacheReadWorker.isTerminated() && this.cacheWriteWorker.isTerminated();
        }

        /** Releases stream bodies only after no worker can still read or publish them. */
        private void releaseOwnedState() {
            if (!this.ownedStateReleased.compareAndSet(false, true)) return;
            clearPendingManifest();
            for (ActiveRequest request : List.copyOf(this.activeRequests)) {
                request.releaseOwnedHandoff();
            }
            this.activeRequests.clear();
            this.readyRequests.clear();
            this.outstandingObjects.clear();
            this.outstandingBytes.set(0);
            StateEvent event;
            while ((event = this.state.poll()) != null) {
                reject(event, new IOException("session closed"));
            }
        }

        private static void awaitLatch(java.util.concurrent.CountDownLatch latch) {
            boolean interrupted = false;
            long deadline = System.nanoTime() + SHUTDOWN_TIMEOUT_NANOS;
            while (latch.getCount() != 0 && deadline - System.nanoTime() > 0) {
                try {
                    latch.await(Math.max(1, deadline - System.nanoTime()),
                            TimeUnit.NANOSECONDS);
                } catch (InterruptedException ignored) {
                    interrupted = true;
                    break;
                }
            }
            if (latch.getCount() != 0) Logger.warn("Voxy shutdown coordination timed out");
            if (interrupted) Thread.currentThread().interrupt();
        }

        private static void awaitTermination(ExecutorService executor, long deadline,
                                             String label) {
            boolean interrupted = false;
            while (!executor.isTerminated() && deadline - System.nanoTime() > 0) {
                try {
                    executor.awaitTermination(Math.max(1, deadline - System.nanoTime()),
                            TimeUnit.NANOSECONDS);
                } catch (InterruptedException ignored) {
                    interrupted = true;
                    break;
                }
            }
            if (!executor.isTerminated()) Logger.warn("Voxy " + label
                    + " exceeded shutdown deadline");
            if (interrupted) Thread.currentThread().interrupt();
        }

    }

    private record RetiredSession(Thread owner, Connection connection, SessionResources resources,
                                  boolean resetSelection) {}
    private record DelayedRetry(boolean subtree, long readyNanos) {}

    private sealed interface MainTask permits CatalogTask, PublishTask, RetireTask {
        Connection connection();
        void apply(ClientLevel level, VoxyRenderSystem renderer, SessionResources resources);
        default void cancel() {}
        default void fail(RuntimeException failure) { cancel(); }
    }

    private record CatalogTask(Connection connection, RootToken root,
                               CatalogCodec.Catalog catalog) implements MainTask {
        @Override
        public void apply(ClientLevel level, VoxyRenderSystem renderer,
                          SessionResources resources) {
            this.connection.acceptCatalogOnMain(this.root, this.catalog, renderer);
        }
    }

    private record PublishTask(Connection connection, RootToken root,
                               SpatialNode node) implements MainTask {
        @Override
        public void apply(ClientLevel level, VoxyRenderSystem renderer,
                          SessionResources resources) {
            this.connection.publishOnMain(this.root, this.node);
        }

        @Override
        public void cancel() {
            SessionResources current = ClientSession.resources;
            if (current != null) current.activations.cancelCandidate(this.node, this.root);
        }
    }

    private record RetireTask(Connection connection, RootToken root,
                              SpatialNode node, long selectionEpoch,
                              long planRevision) implements MainTask {
        @Override
        public void apply(ClientLevel level, VoxyRenderSystem renderer,
                          SessionResources resources) {
            this.connection.retireOnMain(this.root, this.node, this.selectionEpoch,
                    this.planRevision);
        }

        @Override
        public void cancel() {
            this.connection.offer(new RetireEvent(this.root, this.node,
                    MicrotileActivationManager.RemovalStatus.BLOCKED, null));
        }
    }

    private sealed interface StateEvent permits CacheLookupFinishedEvent,
            CatalogMappedEvent, SelectionEvent, ManifestPublishedEvent, CompileEvent,
            PublishEvent, RetireEvent, FenceEvent, DebugSnapshotEvent {}

    private enum DebugSnapshotEvent implements StateEvent { INSTANCE }

    private record CachedObject(Hash256 hash, EncodedObject encoded) {}
    private record CacheLookupFinishedEvent(Connection.ActiveRequest request,
                                            List<CachedObject> cached,
                                            Throwable failure) implements StateEvent {}
    private record DecodedObjectEvent(Connection.ActiveRequest request, EncodedObject encoded,
                                      DecodedObject decoded, Throwable failure,
                                      Runnable release, boolean fromCache) {}
    private record StreamFinishedEvent(Connection.ActiveRequest request, Throwable failure) {}
    private static final class NetworkHandoff {
        private final EncodedObject encoded;
        private final Runnable release;
        private DecodedObject decoded;
        private Throwable failure;
        /** 0 = awaiting owner, 1 = decoding, 2 = decoded. */
        private int stage;

        private NetworkHandoff(EncodedObject encoded, Runnable release) {
            this.encoded = Objects.requireNonNull(encoded, "encoded");
            this.release = Objects.requireNonNull(release, "release");
        }
    }
    private record CatalogMappedEvent(RootToken root, CatalogCodec.Catalog catalog,
                                      ContentPipeline.CatalogMappings mappings,
                                      CatalogModelCompatibility compatibility) implements StateEvent {}
    private record SelectionEvent(SelectionBatch selection) implements StateEvent {}
    private record ManifestPublishedEvent(long generation, long snapshot, long authority)
            implements StateEvent {}
    private record CompileEvent(RootToken root, SpatialNode node,
                                MicrotileActivationManager.CompileStatus status,
                                Throwable failure) implements StateEvent {}
    private record PublishEvent(RootToken root, SpatialNode node, boolean queued,
                                Throwable failure) implements StateEvent {}
    private record RetireEvent(RootToken root, SpatialNode node,
                               MicrotileActivationManager.RemovalStatus status,
                               Throwable failure) implements StateEvent {}
    private record FenceEvent(RootToken root, SpatialNode node,
                              Throwable failure) implements StateEvent {}
    private record CameraDomainEvent(RootToken root, long sequence, long domain,
                                     int minX, int minY, int minZ,
                                     int maxX, int maxY, int maxZ) {
        private boolean contains(CameraPosition position) {
            return position.blockX >= this.minX && position.blockX <= this.maxX
                    && position.blockY >= this.minY && position.blockY <= this.maxY
                    && position.blockZ >= this.minZ && position.blockZ <= this.maxZ;
        }

        private CameraDomainLease lease() {
            return new CameraDomainLease(this.root, this.domain,
                    this.minX, this.minY, this.minZ, this.maxX, this.maxY, this.maxZ);
        }
    }
    private record CameraDomainLease(RootToken root, long domain,
                                     int minX, int minY, int minZ,
                                     int maxX, int maxY, int maxZ) {
        private CameraDomainLease {
            Objects.requireNonNull(root, "root");
            if (minX > maxX || minY > maxY || minZ > maxZ) {
                throw new IllegalArgumentException("inverted camera-domain lease");
            }
        }

        private boolean contains(CameraPosition position) {
            return position.blockX >= this.minX && position.blockX <= this.maxX
                    && position.blockY >= this.minY && position.blockY <= this.maxY
                    && position.blockZ >= this.minZ && position.blockZ <= this.maxZ;
        }
    }
    private record CameraPosition(int blockX, int blockY, int blockZ) {}

    @FunctionalInterface
    private interface CacheAction {
        void run() throws IOException;
    }

    private static void reject(StateEvent event, Throwable failure) {
        if (event instanceof SelectionEvent selection) {
            selection.selection.close();
        } else if (event instanceof CacheLookupFinishedEvent cached) {
            for (CachedObject object : cached.cached) {
                object.encoded.close();
            }
        }
    }

    private static void clearMetadataRoots() {
        synchronized (METADATA_ROOTS) {
            METADATA_ROOTS.clear();
        }
    }

    private static void mergeCut(CutTable cuts, SelectionBatch batch,
                                 SelectionBatch.Segment segment, int row) {
        long exterior = batch.selectedMask(segment, row,
                me.cortex.voxy.client.core.rendering.selection.SelectionManifest.ContentClass.EXTERIOR);
        long interior = batch.selectedMask(segment, row,
                me.cortex.voxy.client.core.rendering.selection.SelectionManifest.ContentClass.INTERIOR);
        long complex = batch.selectedMask(segment, row,
                me.cortex.voxy.client.core.rendering.selection.SelectionManifest.ContentClass.COMPLEX);
        if ((exterior | interior | complex) == 0) return;
        cuts.merge(batch.nodeHandle(segment, row), batch.sectionKey(segment, row),
                exterior, interior, complex);
    }

    private static void collectHandles(HandlePriorities result,
                                       SelectionBatch batch, SelectionBatch.Segment segment,
                                       int row, boolean requestsOnly) {
        ContentPriority priority = contentPriority(batch.priority(segment, row));
        int nodeIndex = batch.nodeIndex(segment, row);
        SelectionManifest manifest = batch.manifest();
        for (int ordinal = 0; ordinal < 3; ordinal++) {
            var contentClass = contentClass(ordinal);
            long selected = batch.selectedMask(segment, row, contentClass);
            if (selected == 0) continue;
            var layout = batch.contentLayout(segment, row, contentClass);
            addSelectedObjects(result, manifest, nodeIndex, contentClass, layout,
                    selected, priority, requestsOnly);
            addDependencies(result, manifest, nodeIndex, contentClass, layout,
                    priority, requestsOnly);
            if (requestsOnly) addSelectedNeighbors(result, manifest, nodeIndex,
                    contentClass, layout, selected, priority, true);
        }
    }

    /** Retains selected neighbor context without granting request authority to the CPU. */
    private static void collectSelectedNeighborHandles(
            HandlePriorities result, SelectionBatch batch,
            SelectionBatch.Segment segment, int row) {
        ContentPriority priority = contentPriority(batch.priority(segment, row));
        int nodeIndex = batch.nodeIndex(segment, row);
        SelectionManifest manifest = batch.manifest();
        for (int ordinal = 0; ordinal < 3; ordinal++) {
            var contentClass = contentClass(ordinal);
            long selected = batch.selectedMask(segment, row, contentClass);
            if (selected != 0) addSelectedNeighbors(result, manifest, nodeIndex,
                    contentClass, batch.contentLayout(segment, row, contentClass),
                    selected, priority, false);
        }
    }

    private static void addSelectedObjects(HandlePriorities target,
                                           SelectionManifest manifest, int nodeIndex,
                                           SelectionManifest.ContentClass contentClass,
                                           SelectionManifest.ContentLayout state,
                                           long selected, ContentPriority priority,
                                           boolean missingOnly) {
        long accepted = missingOnly
                ? selected & ~(manifest.residentMask(nodeIndex, contentClass)
                | manifest.inFlightMask(nodeIndex, contentClass)) : selected;
        int dense = 0;
        int[] handles = state.objectHandlesInternal();
        for (int microtile = 0; microtile < Long.SIZE; microtile++) {
            long bit = 1L << microtile;
            if ((state.declaredMask() & bit) == 0) continue;
            int handle = handles[dense++];
            if ((accepted & bit) != 0) mergePriority(target, handle, priority);
        }
    }

    private static void addDependencies(HandlePriorities target,
                                        SelectionManifest manifest, int nodeIndex,
                                        SelectionManifest.ContentClass contentClass,
                                        SelectionManifest.ContentLayout state,
                                        ContentPriority priority, boolean missingOnly) {
        int[] handles = state.dependencyHandlesInternal();
        for (int index = 0; index < handles.length; index++) {
            if (!missingOnly
                    || !manifest.dependencyResident(nodeIndex, contentClass, index)
                    && !manifest.dependencyInFlight(nodeIndex, contentClass, index)) {
                mergePriority(target, handles[index], priority);
            }
        }
    }

    private static void addSelectedNeighbors(HandlePriorities target,
                                             SelectionManifest manifest, int nodeIndex,
                                             SelectionManifest.ContentClass contentClass,
                                             SelectionManifest.ContentLayout state,
                                             long selected, ContentPriority priority,
                                             boolean missingOnly) {
        int[] handles = state.neighborDependencyHandlesInternal();
        int[] sources = state.neighborDependencySourcesInternal();
        for (int index = 0; index < handles.length; index++) {
            if ((selected & 1L << sources[index]) == 0) continue;
            if (missingOnly && (manifest.neighborResident(nodeIndex, contentClass, index)
                    || manifest.neighborInFlight(nodeIndex, contentClass, index))) continue;
            mergePriority(target, handles[index], priority);
        }
    }

    private static void mergePriority(HandlePriorities target, int handle,
                                      ContentPriority priority) {
        target.add(handle, priority);
    }

    private static long selectedMasks(SelectionBatch batch, SelectionBatch.Segment segment,
                                      int row) {
        return batch.selectedMask(segment, row,
                me.cortex.voxy.client.core.rendering.selection.SelectionManifest.ContentClass.EXTERIOR)
                | batch.selectedMask(segment, row,
                me.cortex.voxy.client.core.rendering.selection.SelectionManifest.ContentClass.INTERIOR)
                | batch.selectedMask(segment, row,
                me.cortex.voxy.client.core.rendering.selection.SelectionManifest.ContentClass.COMPLEX);
    }

    private static me.cortex.voxy.client.core.rendering.selection.SelectionManifest.ContentClass
    contentClass(int ordinal) {
        return switch (ordinal) {
            case 0 -> me.cortex.voxy.client.core.rendering.selection.SelectionManifest.ContentClass.EXTERIOR;
            case 1 -> me.cortex.voxy.client.core.rendering.selection.SelectionManifest.ContentClass.INTERIOR;
            case 2 -> me.cortex.voxy.client.core.rendering.selection.SelectionManifest.ContentClass.COMPLEX;
            default -> throw new IllegalArgumentException("invalid content class");
        };
    }

    /** Bounded primitive section-key table retaining decoded nodes and unchanged cuts. */
    private static final class CutTable {
        private long[] keysByHandle = new long[0];
        private SpatialNode[] nodesByHandle = new SpatialNode[0];
        private SelectionCut[] cutsByHandle = new SelectionCut[0];
        private int[] activeEpochs = new int[0];
        private int[] activeHandles = new int[0];
        private long[] keyTable = new long[0];
        private int[] keyHandles = new int[0];
        private byte[] keyStates = new byte[0];
        private int epoch = 1;
        private int count;

        private void begin(boolean replace, int handleCapacity) {
            ensureCapacity(handleCapacity);
            if (!replace) return;
            if (++this.epoch == 0) {
                java.util.Arrays.fill(this.activeEpochs, 0);
                this.epoch = 1;
            }
            java.util.Arrays.fill(this.keyStates, (byte) 0);
            this.count = 0;
        }

        private void clear() { begin(true, 0); }

        private void add(int handle, long key) { merge(handle, key, 1, 0, 0); }

        private void merge(int handle, long key, long exterior, long interior, long complex) {
            if ((exterior | interior | complex) == 0) return;
            if (handle < 0 || handle >= this.activeEpochs.length) {
                throw new IllegalArgumentException("cut node handle is outside its manifest");
            }
            boolean active = this.activeEpochs[handle] == this.epoch;
            long mergedExterior = exterior;
            long mergedInterior = interior;
            long mergedComplex = complex;
            if (active) {
                SelectionCut current = this.cutsByHandle[handle];
                mergedExterior |= current.exteriorMask();
                mergedInterior |= current.interiorMask();
                mergedComplex |= current.complexMask();
            } else {
                this.activeEpochs[handle] = this.epoch;
                this.activeHandles[this.count++] = handle;
                insertKey(key, handle);
            }
            if (this.nodesByHandle[handle] == null || this.keysByHandle[handle] != key) {
                this.keysByHandle[handle] = key;
                this.nodesByHandle[handle] = RootDemandPlan.spatial(key);
                this.cutsByHandle[handle] = null;
            }
            SelectionCut old = this.cutsByHandle[handle];
            if (old == null || old.exteriorMask() != mergedExterior
                    || old.interiorMask() != mergedInterior
                    || old.complexMask() != mergedComplex) {
                this.cutsByHandle[handle] = new SelectionCut(
                        mergedExterior, mergedInterior, mergedComplex);
            }
        }

        private int size() { return this.count; }
        private SpatialNode nodeAt(int index) { return this.nodesByHandle[this.activeHandles[index]]; }
        private SelectionCut cutAt(int index) { return this.cutsByHandle[this.activeHandles[index]]; }

        private boolean containsNode(SpatialNode node) {
            return containsKey(RootDemandPlan.sectionKey(node));
        }

        private boolean containsKey(long key) {
            if (this.keyTable.length == 0) return false;
            int mask = this.keyTable.length - 1;
            int slot = mix(key) & mask;
            while (this.keyStates[slot] != 0) {
                if (this.keyTable[slot] == key) return true;
                slot = slot + 1 & mask;
            }
            return false;
        }

        private SelectionCut cutForNode(SpatialNode node) {
            if (this.keyTable.length == 0) return null;
            long key = RootDemandPlan.sectionKey(node);
            int mask = this.keyTable.length - 1;
            int slot = mix(key) & mask;
            while (this.keyStates[slot] != 0) {
                if (this.keyTable[slot] == key) {
                    int handle = this.keyHandles[slot];
                    return this.activeEpochs[handle] == this.epoch
                            ? this.cutsByHandle[handle] : null;
                }
                slot = slot + 1 & mask;
            }
            return null;
        }

        private void insertKey(long key, int handle) {
            int mask = this.keyTable.length - 1;
            int slot = mix(key) & mask;
            while (this.keyStates[slot] != 0) {
                if (this.keyTable[slot] == key) {
                    if (this.keyHandles[slot] != handle) {
                        throw new IllegalArgumentException(
                                "one section key has multiple selection handles");
                    }
                    return;
                }
                slot = slot + 1 & mask;
            }
            this.keyStates[slot] = 1;
            this.keyTable[slot] = key;
            this.keyHandles[slot] = handle;
        }

        private void ensureCapacity(int handleCapacity) {
            if (handleCapacity <= this.activeEpochs.length) return;
            int handles = HandlePriorities.grow(this.activeEpochs.length, handleCapacity);
            this.keysByHandle = java.util.Arrays.copyOf(this.keysByHandle, handles);
            this.nodesByHandle = java.util.Arrays.copyOf(this.nodesByHandle, handles);
            this.cutsByHandle = java.util.Arrays.copyOf(this.cutsByHandle, handles);
            this.activeEpochs = java.util.Arrays.copyOf(this.activeEpochs, handles);
            this.activeHandles = java.util.Arrays.copyOf(this.activeHandles, handles);
            int tableCapacity = 16;
            while (tableCapacity < Math.multiplyExact(handles, 2)) {
                tableCapacity = Math.multiplyExact(tableCapacity, 2);
            }
            this.keyTable = new long[tableCapacity];
            this.keyHandles = new int[tableCapacity];
            this.keyStates = new byte[tableCapacity];
            for (int index = 0; index < this.count; index++) {
                int handle = this.activeHandles[index];
                insertKey(this.keysByHandle[handle], handle);
            }
        }

        private static int mix(long key) {
            key ^= key >>> 33;
            key *= 0xff51afd7ed558ccdL;
            key ^= key >>> 33;
            return (int) key;
        }
    }

    /** Reusable primitive handle set with maximum-priority merging and epoch-stamped clearing. */
    private static final class HandlePriorities {
        private int[] epochs = new int[0];
        private int[] slotByHandle = new int[0];
        private byte[] priorityByHandle = new byte[0];
        private int[] handles = new int[0];
        private byte[] priorities = new byte[0];
        private int epoch;
        private int count;

        private HandlePriorities begin(int capacity) {
            if (capacity > this.epochs.length) {
                this.epochs = new int[grow(this.epochs.length, capacity)];
                this.slotByHandle = new int[this.epochs.length];
                this.priorityByHandle = new byte[this.epochs.length];
                this.epoch = 0;
            }
            if (capacity > this.handles.length) {
                this.handles = new int[grow(this.handles.length, capacity)];
                this.priorities = new byte[this.handles.length];
            }
            if (++this.epoch == 0) {
                java.util.Arrays.fill(this.epochs, 0);
                this.epoch = 1;
            }
            this.count = 0;
            return this;
        }

        private void add(int handle, ContentPriority priority) {
            if (handle < 0 || handle >= this.epochs.length) {
                throw new IllegalArgumentException("selection object handle is outside its manifest");
            }
            byte value = (byte) priority.ordinal();
            if (this.epochs[handle] == this.epoch) {
                if (value > this.priorityByHandle[handle]) {
                    this.priorityByHandle[handle] = value;
                    this.priorities[this.slotByHandle[handle]] = value;
                }
                return;
            }
            this.epochs[handle] = this.epoch;
            this.priorityByHandle[handle] = value;
            this.slotByHandle[handle] = this.count;
            this.handles[this.count] = handle;
            this.priorities[this.count] = value;
            this.count++;
        }

        private static int grow(int current, int required) {
            int capacity = Math.max(16, current);
            while (capacity < required) capacity = Math.multiplyExact(capacity, 2);
            return capacity;
        }
    }

    /** Reusable node-handle keyed section-key set. */
    private static final class NodeKeys {
        private int[] epochs = new int[0];
        private long[] keys = new long[0];
        private int epoch;
        private int count;

        private NodeKeys begin(int handleCapacity) {
            if (handleCapacity > this.epochs.length) {
                int capacity = HandlePriorities.grow(this.epochs.length, handleCapacity);
                this.epochs = new int[capacity];
                this.keys = new long[capacity];
                this.epoch = 0;
            }
            if (++this.epoch == 0) {
                java.util.Arrays.fill(this.epochs, 0);
                this.epoch = 1;
            }
            this.count = 0;
            return this;
        }

        private void add(int handle, long key) {
            if (handle < 0 || handle >= this.epochs.length) {
                throw new IllegalArgumentException("selection node handle is outside its manifest");
            }
            if (this.epochs[handle] == this.epoch) return;
            this.epochs[handle] = this.epoch;
            this.keys[this.count++] = key;
        }
    }

    private static ContentPriority contentPriority(SelectionBatch.Priority priority) {
        return switch (priority) {
            case COVERAGE -> ContentPriority.COVERAGE;
            case CURRENT_VIEW -> ContentPriority.CURRENT_VIEW;
            case PREDICTED -> ContentPriority.PREDICTED;
        };
    }

    private static SelectionCut union(SelectionCut left, SelectionCut right) {
        return new SelectionCut(left.exteriorMask() | right.exteriorMask(),
                left.interiorMask() | right.interiorMask(),
                left.complexMask() | right.complexMask());
    }

    private static boolean hierarchyOverlaps(SpatialNode node,
                                             Collection<SpatialNode> selected) {
        for (SpatialNode candidate : selected) {
            if (isAncestor(node, candidate) || isAncestor(candidate, node)) return true;
        }
        return false;
    }

    private static boolean hierarchyOverlaps(SpatialNode node, CutTable selected) {
        for (int index = 0; index < selected.size(); index++) {
            SpatialNode candidate = selected.nodeAt(index);
            if (isAncestor(node, candidate) || isAncestor(candidate, node)) return true;
        }
        return false;
    }

    /** Keeps an active parent/child fallback alive while exact node descriptors are unresolved. */
    static boolean retainsStructuralCoverage(SpatialNode active,
                                             Collection<SpatialNode> descriptorDemand) {
        return hierarchyOverlaps(active, descriptorDemand);
    }

    private static boolean retainsStructuralCoverage(SpatialNode active,
                                                     CutTable descriptorDemand) {
        return hierarchyOverlaps(active, descriptorDemand);
    }

    /** Removes cancellation authority while preserving an exact same-generation GPU cut. */
    static SelectionBatch additionsOnly(SelectionBatch selection) {
        Objects.requireNonNull(selection, "selection");
        selection.disableCancellation();
        return selection;
    }

    private static boolean isAncestor(SpatialNode ancestor, SpatialNode descendant) {
        int shift = ancestor.lod() - descendant.lod();
        return shift >= 0
                && descendant.x() >> shift == ancestor.x()
                && descendant.y() >> shift == ancestor.y()
                && descendant.z() >> shift == ancestor.z();
    }

    private static boolean ancestorCutCoversNode(SpatialNode ancestor, SelectionCut cut,
                                                 SpatialNode descendant) {
        int shift = ancestor.lod() - descendant.lod();
        if (shift < 0) return false;
        if (shift == 0) return ancestor.equals(descendant);
        long regionMask = ancestorMicrotilesFor(descendant, ancestor, shift);
        long selectedMask = cut.exteriorMask() | cut.interiorMask() | cut.complexMask();
        return (selectedMask & regionMask) == regionMask;
    }

    private static long ancestorMicrotilesFor(SpatialNode descendant, SpatialNode ancestor,
                                              int shift) {
        long scale = 1L << shift;
        long relativeX = descendant.x() - ((long) ancestor.x() << shift);
        long relativeY = descendant.y() - ((long) ancestor.y() << shift);
        long relativeZ = descendant.z() - ((long) ancestor.z() << shift);
        int minX = (int) (relativeX * 4 / scale);
        int minY = (int) (relativeY * 4 / scale);
        int minZ = (int) (relativeZ * 4 / scale);
        int maxX = (int) (((relativeX + 1) * 4 - 1) / scale);
        int maxY = (int) (((relativeY + 1) * 4 - 1) / scale);
        int maxZ = (int) (((relativeZ + 1) * 4 - 1) / scale);
        long result = 0;
        for (int z = minZ; z <= maxZ; z++) {
            for (int y = minY; y <= maxY; y++) {
                for (int x = minX; x <= maxX; x++) {
                    int morton = (x & 1) | (y & 1) << 1 | (z & 1) << 2
                            | (x & 2) << 2 | (y & 2) << 3 | (z & 2) << 4;
                    result |= 1L << morton;
                }
            }
        }
        return result;
    }

    private static long nextEpoch(long epoch) {
        long next = epoch + 1;
        return next == 0 ? 1 : next;
    }

    private static void updateEwma(AtomicLong target, long sample) {
        long bounded = Math.max(0, sample);
        while (true) {
            long previous = target.get();
            long delta = bounded - previous;
            long next = previous + delta / 8;
            if (next == previous && next != bounded) next += delta > 0 ? 1 : -1;
            if (target.compareAndSet(previous, Math.max(0, next))) return;
        }
    }

    private static long saturatingAdd(long left, long right) {
        if (right > 0 && left > Long.MAX_VALUE - right) return Long.MAX_VALUE;
        return left + right;
    }

    private static boolean covers(SelectionCut available, SelectionCut required) {
        return (available.exteriorMask() & required.exteriorMask()) == required.exteriorMask()
                && (available.interiorMask() & required.interiorMask()) == required.interiorMask()
                && (available.complexMask() & required.complexMask()) == required.complexMask();
    }

    private static SelectionCut effectiveCut(SelectionCut selected,
                                             CompatibilityState compatibility) {
        long ordinarySelected = selected.exteriorMask() | selected.interiorMask();
        long exterior = selected.exteriorMask() & compatibility.exteriorAvailableMask();
        long interior = selected.interiorMask() & compatibility.interiorAvailableMask();
        long complex = selected.complexMask() & compatibility.complexAvailableMask()
                | ordinarySelected & compatibility.complexRequiredMask();
        return (exterior | interior | complex) == 0
                ? null : new SelectionCut(exterior, interior, complex);
    }

    private static boolean isMicrotile(ObjectKind kind) {
        return kind == ObjectKind.EXTERIOR_MICROTILE
                || kind == ObjectKind.INTERIOR_MICROTILE
                || kind == ObjectKind.COMPLEX_MICROTILE;
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
            if (property == null) {
                throw new IllegalArgumentException("server catalog names an unavailable property: "
                        + assignment);
            }
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
