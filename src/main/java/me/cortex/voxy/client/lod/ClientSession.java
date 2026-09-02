package me.cortex.voxy.client.lod;

import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.model.CatalogModelCompatibility;
import me.cortex.voxy.client.core.rendering.selection.SelectionBatch;
import me.cortex.voxy.client.core.rendering.selection.SelectionManifest;
import me.cortex.voxy.client.core.rendering.selection.SelectionTelemetry;
import me.cortex.voxy.client.lod.ContentPipeline.CompatibilityState;
import me.cortex.voxy.client.lod.ContentPipeline.SelectionCut;
import me.cortex.voxy.client.lod.ManifestCodec.ContentClass;
import me.cortex.voxy.client.lod.ManifestCodec.ManifestSubtree;
import me.cortex.voxy.client.lod.ManifestCodec.DescriptorPage;
import me.cortex.voxy.client.lod.ManifestCodec.RootDirectory;
import me.cortex.voxy.client.lod.ManifestCodec.SpatialNode;
import me.cortex.voxy.client.lod.RootDemandPlan.Binding;
import me.cortex.voxy.client.lod.RootDemandPlan.ContentObject;
import me.cortex.voxy.client.lod.RootDemandPlan.ContentPriority;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.client.core.model.CatalogMapper;
import me.cortex.voxy.client.lod.WireMessage.EncodedObject;
import me.cortex.voxy.client.lod.WireMessage.Hash256;
import me.cortex.voxy.client.lod.WireMessage.Message;
import me.cortex.voxy.client.lod.WireMessage.ObjectBundle;
import me.cortex.voxy.client.lod.WireMessage.ObjectKind;
import me.cortex.voxy.client.lod.WireMessage.ObjectRequest;
import me.cortex.voxy.client.lod.WireMessage.RootAnnounce;
import me.cortex.voxy.client.lod.WireMessage.RootReady;
import me.cortex.voxy.client.lod.WireMessage.RootToken;
import me.cortex.voxy.client.lod.WireMessage.SubtreeData;
import me.cortex.voxy.client.lod.WireMessage.SubtreeRequest;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
    private static final long DIRECT_CREDIT = 32L << 20;
    private static final long BRIDGE_CREDIT = (16L << 20) + (256L << 10);
    private static final int MAX_MAIN_TASKS = 512;
    private static final int MAX_MAIN_PER_TICK = 96;
    private static final long MESHING_SCRATCH = 5L << 20;
    private static final long MAX_NODE_GEOMETRY = 8L << 20;
    private static final long ACTIVATION_IN_FLIGHT = 512L << 10;
    private static final int MAX_MESHING_JOBS = 1;
    private static final int MAX_BLOCK_ID = 1 << 20;
    private static final int MAX_BIOME_ID = 1 << 9;
    private static final int MAX_NAME = 4096;
    private static final long ESTIMATED_CONTENT_REQUEST_BYTES = 8L << 10;
    private static final long ESTIMATED_SUBTREE_REQUEST_BYTES = 64L << 10;
    private static final int MAX_OUTSTANDING_OBJECTS = 8 * 1024;
    private static final long REQUEST_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(20);
    private static final long CREDIT_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(20);
    private static final long TELEMETRY_WINDOW_NANOS = TimeUnit.MILLISECONDS.toNanos(250);
    private static final int CAMERA_DOMAIN_REQUEST_BYTES = 92;
    private static final int CAMERA_DOMAIN_RESPONSE_BYTES = 113;
    private static final int MAX_RETAINED_OUTSIDE_VIEW = 256;
    private static final int MAX_RETIREMENTS_PER_PASS = 16;
    /** Radius 16 needs only a few thousand LOD-4 roots including vertical layers. */
    private static final int MAX_METADATA_ROOTS = 8_192;
    /** Authoritative set, one change-only handoff snapshot, and planner-owned spatial values. */
    private static final long METADATA_ROOT_ACCOUNTING_BYTES = MAX_METADATA_ROOTS * 320L;
    /** Planner maps, connection cut state, and bounded immutable Java snapshot ownership. */
    private static final long CONTROL_BYTES_PER_OBJECT = 768L;
    private static final long CONTROL_BYTES_PER_NODE = 640L;

    /** One bounded authoritative renderer window; the planner copies it only on actual changes. */
    private static final Set<SpatialNode> METADATA_ROOTS = new LinkedHashSet<>();
    private static final ArrayBlockingQueue<MainTask> MAIN =
            new ArrayBlockingQueue<>(MAX_MAIN_TASKS);
    private static final AtomicLong SESSION = new AtomicLong();
    private static final Object LIFECYCLE = new Object();

    private static volatile Thread networkThread;
    private static volatile Connection connection;
    private static volatile SessionResources resources;
    private static volatile String activeDimension;
    private static volatile VoxyRenderSystem activeRenderer;

    private ClientSession() {}

    static boolean metadataRootEntered(long key) {
        SpatialNode root = RootDemandPlan.spatial(key);
        if (root.lod() != ManifestCodec.MAX_LOD) {
            throw new IllegalArgumentException("metadata root is not top-level");
        }
        synchronized (METADATA_ROOTS) {
            if (METADATA_ROOTS.contains(root)) return false;
            if (METADATA_ROOTS.size() >= MAX_METADATA_ROOTS) {
                Logger.warn("Voxy manifest-root window reached its bounded limit");
                return false;
            }
            return METADATA_ROOTS.add(root);
        }
    }

    static void metadataRootLeft(long key) {
        SpatialNode root = RootDemandPlan.spatial(key);
        if (root.lod() != ManifestCodec.MAX_LOD) {
            throw new IllegalArgumentException("metadata root is not top-level");
        }
        synchronized (METADATA_ROOTS) {
            METADATA_ROOTS.remove(root);
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

    static int debugDesiredSections() {
        synchronized (METADATA_ROOTS) {
            return METADATA_ROOTS.size();
        }
    }

    static int maximumMetadataRoots() {
        return MAX_METADATA_ROOTS;
    }

    static int debugPendingSections() {
        Connection current = connection;
        return current == null ? 0 : current.pendingCount();
    }

    static int debugInboundFrames() {
        Connection current = connection;
        return MAIN.size() + (current == null ? 0 : current.state.size());
    }

    static long debugInboundKiB() {
        SessionResources current = resources;
        return current == null ? 0
                : (current.memory.used(MemoryBudget.Pool.IN_FLIGHT) + 1023) >>> 10;
    }

    static long debugMemoryUsedMiB() {
        SessionResources current = resources;
        return current == null ? 0 : (current.memory.used() + (1L << 19)) >>> 20;
    }

    static long debugMemoryAvailableMiB() {
        SessionResources current = resources;
        return current == null ? 0 : (current.memory.available() + (1L << 19)) >>> 20;
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
            Connection.awaitThread(thread);
            activeDimension = null;
            activeRenderer = null;
            MainTask task;
            while ((task = MAIN.poll()) != null) task.cancel();
            SessionResources owned = resources;
            resources = null;
            if (owned != null) owned.close();
        }
    }

    static void tick() {
        ClientLodDebug.tick();
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
        current.pollCatalogBakeOnMain(renderer);
        renderer.updateSelectionTelemetry(current.selectionTelemetry());
        SelectionManifest manifest = current.pendingManifest.getAndSet(null);
        if (manifest != null) {
            renderer.publishSelectionManifest(manifest);
            current.offer(new ManifestPublishedEvent(manifest.generation(),
                    manifest.snapshotId()));
        }
        SelectionBatch selection;
        while ((selection = renderer.pollSelectionBatch()) != null) {
            current.offer(new SelectionEvent(selection));
        }
        current.pollActivationFences();
    }

    private static void startLocked(String dimension, SessionResources retained) {
        if (networkThread != null && networkThread.isAlive()) return;
        long generation = SESSION.incrementAndGet();
        Thread thread = new Thread(() -> runNetwork(generation, dimension, retained),
                "Voxy Virtual Surface");
        thread.setDaemon(true);
        networkThread = thread;
        ClientLodDebug.sessionStarted(generation, dimension);
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
                active.readLoop();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception failure) {
                if (SESSION.get() != session || resources != retained) return;
                ClientLodDebug.sessionFailed(failure);
                Logger.warn("Virtual Surface unavailable; retrying: " + failure.getMessage());
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

    private static final class SessionResources implements AutoCloseable {
        private final MemoryBudget memory = new MemoryBudget(
                VoxyConfig.CONFIG.virtualSurfaceMemoryBytes());
        private final MemoryBudget.Reservation metadataRootsMemory;
        private final MemoryBudget.Reservation controlTablesMemory;
        private final RootDemandPlan.Limits planLimits;
        private final ObjectCache cache;
        private final ResidencyManager residency;
        private final MicrotileActivationManager activations;
        private final VoxyRenderSystem renderer;
        private final Set<RootToken> pinnedRoots = ConcurrentHashMap.newKeySet();
        private final AtomicBoolean cachePinsDirty = new AtomicBoolean();
        private volatile RootToken authoritativeRoot;

        private SessionResources(String dimension, VoxyRenderSystem renderer) {
            this.renderer = Objects.requireNonNull(renderer, "renderer");
            this.metadataRootsMemory = this.memory.tryReserve(MemoryBudget.Allocation.of(
                    MemoryBudget.Pool.OBJECT_TABLE, METADATA_ROOT_ACCOUNTING_BYTES))
                    .orElseThrow(() -> new IllegalStateException(
                            "Virtual Surface memory budget cannot admit metadata-root state"));
            Path root = Minecraft.getInstance().gameDirectory.toPath()
                    .resolve(".voxy").resolve("virtual-surface");
            int objectLimit = (int) Math.max(16_384L, Math.min(131_072L,
                    this.memory.limit() >>> 13));
            this.planLimits = new RootDemandPlan.Limits(objectLimit,
                    RootDemandPlan.MAX_STRUCTURAL_NODES);
            long controlBytes = Math.addExact(
                    Math.multiplyExact((long) objectLimit, CONTROL_BYTES_PER_OBJECT),
                    Math.multiplyExact((long) this.planLimits.maxNodes(),
                            CONTROL_BYTES_PER_NODE));
            MemoryBudget.Reservation control = this.memory.tryReserve(
                    MemoryBudget.Allocation.of(MemoryBudget.Pool.OBJECT_TABLE, controlBytes))
                    .orElse(null);
            if (control == null) {
                this.metadataRootsMemory.close();
                throw new IllegalStateException(
                        "Virtual Surface memory budget cannot admit bounded control tables");
            }
            this.controlTablesMemory = control;
            ObjectCache createdCache = null;
            ResidencyManager createdResidency = null;
            MicrotileActivationManager createdActivations = null;
            try {
                createdResidency = new ResidencyManager(dimension, this.memory,
                        new ResidencyManager.Limits(objectLimit, objectLimit / 2));
                createdActivations = renderer.createVirtualSurfaceActivationManager(this.memory);
                // Persistent caching is disposable and is admitted only after every live
                // production table and renderer-owned fixed buffer has capacity.
                createdCache = ObjectCache.openBestEffort(root,
                        new ObjectCache.Limits(objectLimit, 4L << 30,
                                WireMessage.MAX_COMPRESSED_OBJECT_BYTES), this.memory);
            } catch (RuntimeException | Error failure) {
                if (createdActivations != null) createdActivations.close();
                if (createdResidency != null) createdResidency.close();
                if (createdCache != null) createdCache.close();
                this.controlTablesMemory.close();
                this.metadataRootsMemory.close();
                throw failure;
            }
            this.cache = createdCache;
            this.residency = createdResidency;
            this.activations = createdActivations;
        }

        private void setAuthoritativeRoot(RootToken root) {
            this.authoritativeRoot = Objects.requireNonNull(root, "root");
        }

        private void pinRootObject(RootToken root, Hash256 hash) {
            if (this.residency.pinRootObject(root, hash)) this.cachePinsDirty.set(true);
            this.pinnedRoots.add(root);
        }

        private void pinRootObjects(RootToken root, Collection<Hash256> hashes) {
            if (this.residency.pinRootObjects(root, hashes)) this.cachePinsDirty.set(true);
            this.pinnedRoots.add(root);
        }

        private void reconcileRootPins(RootToken root, Collection<Hash256> hashes) {
            if (this.residency.reconcileRootPins(root, hashes)) this.cachePinsDirty.set(true);
            this.pinnedRoots.add(root);
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
            syncCachePins();
        }

        private void syncCachePins() {
            if (!this.cachePinsDirty.getAndSet(false)) return;
            try {
                this.cache.replacePins(this.residency::forEachProtectedHash);
            } catch (RuntimeException failure) {
                this.cachePinsDirty.set(true);
                throw failure;
            }
        }

        @Override
        public void close() {
            RuntimeException failure = null;
            try { this.activations.close(); } catch (RuntimeException closeFailure) {
                failure = closeFailure;
            }
            try { this.residency.close(); } catch (RuntimeException closeFailure) {
                if (failure == null) failure = closeFailure; else failure.addSuppressed(closeFailure);
            }
            try { this.cache.close(); } catch (RuntimeException closeFailure) {
                if (failure == null) failure = closeFailure; else failure.addSuppressed(closeFailure);
            }
            try { this.renderer.releaseVirtualSurfaceMemory(this.memory); }
            catch (RuntimeException closeFailure) {
                if (failure == null) failure = closeFailure; else failure.addSuppressed(closeFailure);
            } finally {
                this.controlTablesMemory.close();
                this.metadataRootsMemory.close();
                this.pinnedRoots.clear();
            }
            if (failure != null) throw failure;
        }
    }

    private static final class Connection implements AutoCloseable {
        private final long session;
        private final String dimension;
        private final SessionResources resources;
        private final ClientLodTransport transport;
        private final DataInputStream input;
        private final OutputStream output;
        private final ExecutorService decoderWorker;
        private final ExecutorService mesherWorker;
        private final AtomicReference<Thread> decoderThread = new AtomicReference<>();
        private final AtomicReference<Thread> mesherThread = new AtomicReference<>();
        private final ObjectDecoder decoder;
        private final ArrayBlockingQueue<StateEvent> state = new ArrayBlockingQueue<>(1024);
        private final AtomicLong credit = new AtomicLong();
        private final AtomicLong outstandingBytes = new AtomicLong();
        private final AtomicLong roundTripMicros = new AtomicLong(
                SelectionTelemetry.DEFAULT.roundTripMicros());
        private final AtomicLong throughputBytesPerSecond = new AtomicLong(
                SelectionTelemetry.DEFAULT.throughputBytesPerSecond());
        private final AtomicLong meshingMicros = new AtomicLong(
                SelectionTelemetry.DEFAULT.meshingMicros());
        private final AtomicReference<SelectionManifest> pendingManifest = new AtomicReference<>();
        private final AtomicReference<CameraPosition> cameraPosition = new AtomicReference<>();
        private final Map<Hash256, Long> outstandingObjects = new ConcurrentHashMap<>();
        private final Map<Integer, DictionaryCodec.Dictionary> dictionaries =
                new ConcurrentHashMap<>();
        private final Map<Hash256, Integer> dictionaryIds = new HashMap<>();
        private final Map<SpatialNode, CompatibilityState> compatibility = new HashMap<>();
        private final AtomicReference<PendingCatalogBake> pendingCatalogBake =
                new AtomicReference<>();
        private final java.util.concurrent.atomic.AtomicBoolean closing =
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
        private volatile Thread writer;
        private volatile long serverInstance;
        private volatile boolean metadataResync;
        private volatile long publishedSnapshot;
        private volatile long issuedSnapshot;
        private volatile RootToken authoritativeRoot;
        private RootDemandPlan plan;
        private CatalogCodec.Catalog catalog;
        private ContentPipeline.CatalogMappings mappings;
        private CatalogModelCompatibility modelCompatibility;
        private long nextSnapshot = 1;
        private boolean manifestDirty;
        private volatile boolean completeFrontier;
        private boolean rootReadySent;
        private long lastPing;
        private volatile CameraPosition queriedCameraPosition;
        private CameraDomainLease cameraDomainLease;
        private long nextCameraDomainSequence = 1;
        private long pendingCameraDomainSequence;
        private long cameraVisibilityDomain;
        private volatile long selectionEpoch;
        private long nextRequestFrame;
        private long lastCreditGrant;
        private long throughputWindowStart = System.nanoTime();
        private long throughputWindowBytes;
        /** Number of objects in the wire bundle currently being decoded and admitted. */
        private int responseBundleRemaining;

        private Connection(long session, String dimension, SessionResources resources)
                throws IOException {
            this.session = session;
            this.dimension = dimension;
            this.resources = resources;
            this.transport = ClientLodTransport.open(this.resources.memory);
            this.input = new DataInputStream(this.transport.input());
            this.output = this.transport.output();
            this.decoderWorker = Executors.newSingleThreadExecutor(task -> {
                Thread thread = new Thread(task, "Voxy object decoder");
                thread.setDaemon(true);
                this.decoderThread.set(thread);
                return thread;
            });
            this.mesherWorker = Executors.newSingleThreadExecutor(task -> {
                Thread thread = new Thread(task, "Voxy hybrid mesher");
                thread.setDaemon(true);
                this.mesherThread.set(thread);
                return thread;
            });
            this.decoder = ObjectDecoder.withNativeZstd(this.decoderWorker,
                    id -> Optional.ofNullable(this.dictionaries.get(id)));
            writeFrame(FrameCodec.C_HELLO, WireMessage.encodeHello(dimension));
            long initialCredit = Math.min(this.transport.direct()
                            ? DIRECT_CREDIT : BRIDGE_CREDIT,
                    Math.max(1, this.resources.memory.available()));
            writeFrame(FrameCodec.C_CREDIT,
                    little(Long.BYTES).putLong(initialCredit).array());
            this.output.flush();
            ClientLodDebug.transportOpened(this.transport.direct(),
                    this.transport.description());
        }

        private int pendingCount() {
            return this.compiling.size() + this.awaitingFence.size();
        }

        private SelectionTelemetry selectionTelemetry() {
            return new SelectionTelemetry(this.roundTripMicros.get(),
                    this.throughputBytesPerSecond.get(), this.outstandingBytes.get(),
                    this.meshingMicros.get());
        }

        private void updateCameraPosition(int blockX, int blockY, int blockZ) {
            this.cameraPosition.set(new CameraPosition(blockX, blockY, blockZ));
        }

        private void requestMetadataResync() {
            this.metadataResync = true;
        }

        private void readLoop() throws Exception {
            try (FrameCodec.Frame hello = FrameCodec.readServer(
                    this.input, this::reserveTransient)) {
                ClientLodDebug.frame(hello.type(), hello.payload().length);
                if (hello.type() != FrameCodec.S_HELLO) {
                    throw new FrameCodec.FrameException(
                            "terrain server did not begin with HELLO");
                }
                decodeHello(hello.payload());
            }
            ClientLodDebug.hello(this.serverInstance);
            Thread writeThread = new Thread(this::writeLoop, "Voxy request writer");
            writeThread.setDaemon(true);
            this.writer = writeThread;
            writeThread.start();
            Logger.info("Using Virtual Surface over " + this.transport.description());

            while (this.open && SESSION.get() == this.session) {
                try (FrameCodec.Frame frame = FrameCodec.readServer(
                        this.input, this::reserveTransient)) {
                    ClientLodDebug.frame(frame.type(), frame.payload().length);
                    if (frame.type() == WireMessage.S_ROOT_ANNOUNCE) {
                        RootAnnounce root = (RootAnnounce) WireMessage.decode(
                                frame.type(), frame.payload());
                        if (!root.dimension().equals(this.dimension)
                                || !root.root().dimensionHash().equals(
                                ObjectHash.dimension(this.dimension))) {
                            throw new FrameCodec.FrameException(
                                    "root announcement belongs to another dimension");
                        }
                        ClientLodDebug.rootAnnounced(root.root().generation());
                        offer(new RootEvent(root));
                    } else if (frame.type() == WireMessage.S_SUBTREE_DATA
                            || frame.type() == WireMessage.S_OBJECT_BUNDLE) {
                        receiveObjects(frame);
                        this.credit.addAndGet(frame.payload().length + FrameCodec.HEADER_BYTES);
                        ClientLodDebug.credit(frame.payload().length
                                + FrameCodec.HEADER_BYTES);
                    } else if (frame.type() == FrameCodec.S_PONG) {
                        requireLength(frame.payload(), Long.BYTES, "PONG");
                        long echoed = ByteBuffer.wrap(frame.payload())
                                .order(ByteOrder.LITTLE_ENDIAN).getLong();
                        long elapsed = System.nanoTime() - echoed;
                        if (elapsed > 0 && elapsed <= TimeUnit.MINUTES.toNanos(1)) {
                            updateEwma(this.roundTripMicros,
                                    TimeUnit.NANOSECONDS.toMicros(elapsed));
                        }
                    } else if (frame.type() == FrameCodec.S_CAMERA_DOMAIN) {
                        offer(decodeCameraDomain(frame.payload()));
                    } else if (frame.type() == FrameCodec.S_ERROR) {
                        ServerError error = decodeError(frame.payload());
                        throw new FrameCodec.FrameException(
                                "terrain server error " + error.code + ": " + error.text);
                    } else {
                        throw new FrameCodec.FrameException(
                                "unknown server frame 0x"
                                        + Integer.toHexString(frame.type()));
                    }
                }
            }
        }

        private void receiveObjects(FrameCodec.Frame frame) throws Exception {
            // The frame owns one payload reservation. Decoding creates one retained
            // compressed array per EncodedObject and briefly holds the bounded reader copy while
            // the immutable envelope takes ownership, so account two additional payloads.
            long retainedEnvelopeBytes = Math.addExact(4L << 10,
                    Math.multiplyExact(2L, frame.payload().length));
            try (MemoryBudget.Reservation envelopeMemory =
                         reserveTransient(retainedEnvelopeBytes)) {
                Message message = WireMessage.decode(frame.type(), frame.payload());
                RootToken root;
                List<EncodedObject> entries;
                boolean subtree;
                if (message instanceof SubtreeData data) {
                    root = data.root();
                    entries = data.entries();
                    subtree = true;
                } else {
                    ObjectBundle bundle = (ObjectBundle) message;
                    root = bundle.root();
                    entries = bundle.entries();
                    subtree = false;
                }
                this.recordInboundBytes(frame.payload().length + FrameCodec.HEADER_BYTES);
                for (EncodedObject entry : entries) this.completeOutstanding(entry.hash());
                EnvelopeEvent envelope = new EnvelopeEvent(root, subtree, entries);
                offer(envelope);
                envelope.completion.join();

                // Decode and admit one object at a time.  A bundle can contain hundreds of
                // independently addressable objects; accumulating every canonical byte array
                // before residency admission would make the wire batch an accidental memory
                // budget of its own.
                for (EncodedObject encoded : entries) {
                    try (MemoryBudget.Reservation decodeMemory =
                                 reserveTransient(decodeScratchBytes(encoded))) {
                        CanonicalObject canonical = this.decoder
                                .decodeAndStore(encoded, this.resources.cache).join();
                        ClientLodDebug.objectDecoded(encoded.kind().wireId(),
                                encoded.compressedLength(), false);
                        DecodedBatchEvent batch = new DecodedBatchEvent(root, subtree,
                                List.of(new DecodedObject(encoded, canonical)));
                        offer(batch);
                        batch.completion.join();
                    }
                }
            }
        }

        private void writeLoop() {
            this.lastPing = this.lastCreditGrant = System.nanoTime();
            try {
                while (this.open && SESSION.get() == this.session) {
                    StateEvent event;
                    while ((event = this.state.poll()) != null) handle(event);
                    if (this.responseBundleRemaining == 0) {
                        reconcileMetadata();
                        updateCameraDomainQuery();
                        pumpRequests();
                        installMicrotiles();
                        scheduleRetirements();
                        prepareActivations();
                        reconcileResidencyPins();
                        publishManifest();
                        maybeSendRootReady();
                    } else {
                        // The reader deliberately admits one canonical object at a time so a wire
                        // bundle cannot become an unbudgeted canonical-byte batch. Wait briefly for
                        // its next object instead of rebuilding the complete manifest and pin set
                        // between every entry. The final entry releases one coherent reconciliation.
                        StateEvent continuation = this.state.poll(5, TimeUnit.MILLISECONDS);
                        if (continuation != null) {
                            handle(continuation);
                            continue;
                        }
                    }

                    long now = System.nanoTime();
                    long grant = now - this.lastCreditGrant >= CREDIT_INTERVAL_NANOS
                            ? Math.min(this.credit.get(), this.resources.memory.available()) : 0;
                    if (grant != 0) {
                        this.credit.addAndGet(-grant);
                        writeFrame(FrameCodec.C_CREDIT,
                                little(Long.BYTES).putLong(grant).array());
                        this.lastCreditGrant = now;
                    }
                    if (now - this.lastPing >= TimeUnit.SECONDS.toNanos(10)) {
                        writeFrame(FrameCodec.C_PING,
                                little(Long.BYTES).putLong(now).array());
                        this.lastPing = now;
                    }
                    this.output.flush();
                    if (this.state.isEmpty() && this.responseBundleRemaining == 0) Thread.sleep(1);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (Exception failure) {
                if (this.open) Logger.warn("Virtual Surface writer failed", failure);
                close();
            }
        }

        private void handle(StateEvent event) throws Exception {
            if (event instanceof RootEvent root) {
                acceptRoot(root.root);
            } else if (event instanceof EnvelopeEvent envelope) {
                complete(envelope.completion, () -> {
                    if (this.responseBundleRemaining != 0) {
                        throw new IllegalStateException("overlapping object response bundles");
                    }
                    RootDemandPlan current = requirePlan(envelope.root);
                    for (EncodedObject object : envelope.objects) {
                        current.requireInFlightResponse(object.hash(), object.kind(),
                                envelope.subtree);
                    }
                    this.responseBundleRemaining = envelope.objects.size();
                });
            } else if (event instanceof DecodedBatchEvent batch) {
                complete(batch.completion, () -> {
                    if (batch.objects.size() > this.responseBundleRemaining) {
                        throw new IllegalStateException(
                                "object response exceeds its declared bundle");
                    }
                    for (DecodedObject object : batch.objects) {
                        acceptObject(batch.root, batch.subtree,
                                object.encoded, object.canonical);
                    }
                    this.responseBundleRemaining -= batch.objects.size();
                });
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
                        && published.snapshot == this.issuedSnapshot) {
                    this.publishedSnapshot = published.snapshot;
                }
            } else if (event instanceof CompileEvent compiled) {
                this.compiling.remove(compiled.node);
                if (!isCurrent(compiled.root)) {
                    this.resources.activations.cancelCandidate(compiled.node, compiled.root);
                } else if (compiled.failure != null) {
                    this.resources.activations.cancelCandidate(compiled.node, compiled.root);
                    throw new IOException("hybrid microtile meshing failed", compiled.failure);
                } else {
                    ClientLodDebug.activationCompiled(
                            RootDemandPlan.sectionKey(compiled.node),
                            compiled.root.generation());
                    putMain(new PublishTask(this, compiled.root, compiled.node));
                }
            } else if (event instanceof PublishEvent published) {
                if (published.failure != null) {
                    this.resources.activations.cancelCandidate(published.node, published.root);
                    throw new IOException("atomic renderer publication failed", published.failure);
                }
                if (published.queued) {
                    this.awaitingFence.put(published.node, published.root);
                    ClientLodDebug.activationQueued(
                            RootDemandPlan.sectionKey(published.node),
                            published.root.generation());
                }
            } else if (event instanceof RetireEvent retired) {
                acceptRetire(retired);
            } else if (event instanceof FenceEvent fence) {
                acceptFence(fence);
            } else if (event instanceof CameraDomainEvent cameraDomain) {
                acceptCameraDomain(cameraDomain);
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
                    return;
                }
            }
            this.resources.setAuthoritativeRoot(announced.root());
            this.authoritativeRoot = announced.root();
            synchronized (METADATA_ROOTS) {
                this.plan = new RootDemandPlan(announced, METADATA_ROOTS, List.of(),
                        this.resources.planLimits);
            }
            this.catalog = null;
            this.mappings = null;
            this.modelCompatibility = null;
            this.pendingCatalogBake.set(null);
            this.dictionaries.clear();
            this.dictionaryIds.clear();
            this.compatibility.clear();
            this.outstandingObjects.clear();
            this.outstandingBytes.set(0);
            this.desiredCuts.clear();
            this.renderableCuts.clear();
            this.activationCuts.clear();
            this.descriptorDemands.clear();
            this.coverageCuts.clear();
            invalidateSelectionAuthority();
            this.rootReadySent = false;
            this.publishedSnapshot = 0;
            this.issuedSnapshot = 0;
            this.nextSnapshot = 1;
            this.cameraVisibilityDomain = 0;
            this.cameraDomainLease = null;
            this.pendingCameraDomainSequence = 0;
            this.nextCameraDomainSequence = 1;
            this.queriedCameraPosition = null;
            this.manifestDirty = true;
            this.metadataResync = false;
        }

        private void updateCameraDomainQuery() throws IOException {
            if (this.plan == null) return;
            CameraPosition position = this.cameraPosition.get();
            if (position == null) return;
            CameraDomainLease lease = this.cameraDomainLease;
            if (lease != null && lease.root.equals(this.plan.root().root())
                    && lease.contains(position)) return;
            if (this.pendingCameraDomainSequence != 0) return;
            long now = System.nanoTime();
            if (now - this.nextRequestFrame < 0) return;
            long sequence = this.nextCameraDomainSequence++;
            if (sequence == 0 || this.nextCameraDomainSequence == 0) {
                throw new IllegalStateException("camera-domain sequence exhausted");
            }
            writeFrame(FrameCodec.C_CAMERA_DOMAIN, encodeCameraDomainRequest(
                    this.plan.root().root(), sequence, position));
            this.queriedCameraPosition = position;
            this.pendingCameraDomainSequence = sequence;
            this.nextRequestFrame = now + REQUEST_INTERVAL_NANOS;
            this.cameraDomainLease = null;
            this.cameraVisibilityDomain = 0;
            // A fresh snapshot is also the async GPU authority barrier: results captured before
            // this camera position cannot retire coverage for the new view.
            invalidateSelectionAuthority();
            this.publishedSnapshot = 0;
            this.issuedSnapshot = 0;
            this.manifestDirty = true;
        }

        private void acceptCameraDomain(CameraDomainEvent response) {
            if (this.plan == null || !response.root.equals(this.plan.root().root())) return;
            if (this.pendingCameraDomainSequence == 0
                    || response.sequence != this.pendingCameraDomainSequence) {
                throw new IllegalArgumentException(
                        "camera-domain response does not match the outstanding request");
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
            this.publishedSnapshot = 0;
            this.issuedSnapshot = 0;
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
        }

        private void pumpRequests() throws Exception {
            if (this.plan == null) return;
            long now = System.nanoTime();
            if (now - this.nextRequestFrame < 0) return;
            int capacity = Math.min(WireMessage.MAX_REQUEST_ENTRIES,
                    MAX_OUTSTANDING_OBJECTS - this.outstandingObjects.size());
            if (capacity <= 0) return;

            List<Hash256> bootstrap = this.plan.takeBootstrapObjectRequests(capacity);
            boolean sent = requestObjects(bootstrap);
            if (!bootstrap.isEmpty()) this.manifestDirty = true;
            if (sent) {
                this.nextRequestFrame = now + REQUEST_INTERVAL_NANOS;
                return;
            }
            if (!this.plan.bootstrapObjectsProcessed()) return;

            capacity = Math.min(WireMessage.MAX_REQUEST_ENTRIES,
                    MAX_OUTSTANDING_OBJECTS - this.outstandingObjects.size());
            if (capacity <= 0) return;
            sent = requestSubtrees(this.plan.takeSubtreeRequests(capacity));
            if (sent) {
                this.nextRequestFrame = now + REQUEST_INTERVAL_NANOS;
                return;
            }

            capacity = Math.min(WireMessage.MAX_REQUEST_ENTRIES,
                    MAX_OUTSTANDING_OBJECTS - this.outstandingObjects.size());
            if (capacity <= 0) return;
            List<Hash256> content = this.plan.takeContentObjectRequests(capacity);
            sent = requestObjects(content);
            if (!content.isEmpty()) this.manifestDirty = true;
            if (sent) this.nextRequestFrame = now + REQUEST_INTERVAL_NANOS;
            if (this.plan.discoveryComplete()) this.plan.sealDiscovery();
        }

        private boolean requestSubtrees(List<Hash256> hashes) throws Exception {
            if (hashes.isEmpty()) return false;
            List<Hash256> missing = loadCached(hashes, true);
            if (!missing.isEmpty()) {
                send(new SubtreeRequest(this.plan.root().root(), missing));
                this.markOutstanding(missing, ESTIMATED_SUBTREE_REQUEST_BYTES);
                return true;
            }
            return false;
        }

        private boolean requestObjects(List<Hash256> hashes) throws Exception {
            if (hashes.isEmpty()) return false;
            List<Hash256> missing = loadCached(hashes, false);
            if (!missing.isEmpty()) {
                send(new ObjectRequest(this.plan.root().root(), missing));
                this.markOutstanding(missing, ESTIMATED_CONTENT_REQUEST_BYTES);
                return true;
            }
            return false;
        }

        private List<Hash256> loadCached(List<Hash256> hashes, boolean subtree) throws Exception {
            ArrayList<Hash256> missing = new ArrayList<>();
            for (Hash256 hash : hashes) {
                Optional<EncodedObject> cached = this.resources.cache.getEncoded(hash);
                if (cached.isEmpty()) {
                    missing.add(hash);
                    continue;
                }
                try (MemoryBudget.Reservation decodeMemory =
                             reserveTransient(decodeScratchBytes(cached.orElseThrow()))) {
                    CanonicalObject canonical;
                    try {
                        canonical = this.decoder.decode(cached.orElseThrow()).join();
                    } catch (RuntimeException failure) {
                        this.resources.cache.quarantine(hash);
                        missing.add(hash);
                        continue;
                    }
                    ClientLodDebug.objectDecoded(cached.orElseThrow().kind().wireId(),
                            cached.orElseThrow().compressedLength(), true);
                    if (!acceptObject(this.plan.root().root(), subtree,
                            cached.orElseThrow(), canonical)) {
                        this.plan.deferInFlightResponse(hash, subtree);
                    }
                }
            }
            return List.copyOf(missing);
        }

        private MemoryBudget.Reservation reserveTransient(long bytes) throws IOException {
            if (bytes < 0 || bytes > this.resources.memory.limit()) {
                throw new IOException("Virtual Surface allocation exceeds the global budget");
            }
            MemoryBudget.Allocation request = MemoryBudget.Allocation.of(
                    MemoryBudget.Pool.IN_FLIGHT, bytes);
            boolean cacheYielded = false;
            while (this.open && SESSION.get() == this.session) {
                Optional<MemoryBudget.Reservation> reservation =
                        this.resources.memory.tryReserve(request);
                if (reservation.isPresent()) return reservation.orElseThrow();
                this.resources.residency.reclaimUnreferenced();
                reservation = this.resources.memory.tryReserve(request);
                if (reservation.isPresent()) return reservation.orElseThrow();
                if (!cacheYielded) {
                    // The persistent cache is optional. Disabling it releases its admitted index
                    // and read buffer; all later operations remain valid best-effort no-ops.
                    this.resources.cache.disableForPressure();
                    cacheYielded = true;
                    continue;
                }
                try {
                    Thread.sleep(1);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException(
                            "Interrupted waiting for Virtual Surface memory pressure", interrupted);
                }
            }
            throw new IOException("Virtual Surface session closed during memory pressure");
        }

        private static long decodeScratchBytes(EncodedObject encoded) {
            // Java source+destination copies, native Zstd source+destination, immutable canonical
            // ownership, dictionary staging, and a small object/header allowance.
            return Math.addExact(64L << 10, Math.addExact(
                    Math.multiplyExact(2L, encoded.compressedLength()),
                    Math.multiplyExact(3L, encoded.canonicalLength())));
        }

        private boolean acceptObject(RootToken root, boolean subtree, EncodedObject encoded,
                                     CanonicalObject canonical) throws Exception {
            RootDemandPlan current = requirePlan(root);
            current.requireInFlightResponse(canonical.hash(), canonical.kind(), subtree);
            if (!this.resources.residency.admitVerifiedObject(encoded, canonical)) {
                current.deferInFlightResponse(canonical.hash(), subtree);
                return false;
            }
            if (subtree) {
                this.resources.residency.installManifestObject(canonical);
                boolean parsed = switch (canonical.kind()) {
                    case ROOT_DIRECTORY -> this.resources.residency
                            .rootDirectory(canonical.hash()).isPresent();
                    case MANIFEST_SUBTREE -> this.resources.residency
                            .manifestSubtree(canonical.hash()).isPresent();
                    case MANIFEST_DESCRIPTOR_PAGE -> this.resources.residency
                            .descriptorPage(canonical.hash()).isPresent();
                    default -> false;
                };
                if (!parsed) {
                    // Hitting the one global memory cap pauses metadata refinement. Do not pin a
                    // canonical envelope which could otherwise prevent the reclaim needed by the
                    // retried page.
                    current.deferInFlightResponse(canonical.hash(), true);
                    this.resources.residency.reclaimUnreferenced();
                    return false;
                }
                this.resources.pinRootObject(root, canonical.hash());
                if (canonical.kind() == ObjectKind.ROOT_DIRECTORY) {
                    RootDirectory directory = this.resources.residency
                            .rootDirectory(canonical.hash()).orElseThrow();
                    current.acceptDirectory(canonical.hash(), directory);
                } else if (canonical.kind() == ObjectKind.MANIFEST_SUBTREE) {
                    ManifestSubtree manifest = this.resources.residency
                            .manifestSubtree(canonical.hash()).orElseThrow();
                    current.acceptManifest(canonical.hash(), manifest);
                } else if (canonical.kind() == ObjectKind.MANIFEST_DESCRIPTOR_PAGE) {
                    DescriptorPage page = this.resources.residency
                            .descriptorPage(canonical.hash()).orElseThrow();
                    current.acceptDescriptorPage(canonical.hash(), page);
                } else {
                    throw new IllegalArgumentException("subtree response contains content");
                }
                this.manifestDirty = true;
                return true;
            }

            this.resources.pinRootObject(root, canonical.hash());
            current.acceptObject(canonical.hash(), canonical.kind());
            if (canonical.kind() == ObjectKind.CATALOG) {
                CatalogCodec.Catalog decoded = CatalogCodec.decode(
                        canonical.canonicalBytes());
                putMain(new CatalogTask(this, root, decoded));
            } else if (canonical.kind() == ObjectKind.DICTIONARY_SET) {
                List<Hash256> hashes = DictionaryCodec.decodeSet(canonical.canonicalBytes());
                current.expectCompressionDictionaries(hashes);
                this.dictionaryIds.clear();
                for (int index = 0; index < hashes.size(); index++) {
                    this.dictionaryIds.put(hashes.get(index), index + 1);
                }
            } else if (canonical.kind() == ObjectKind.COMPRESSION_DICTIONARY) {
                Integer id = this.dictionaryIds.get(canonical.hash());
                if (id == null) {
                    throw new IllegalArgumentException("dictionary is outside its announced set");
                }
                this.dictionaries.put(id,
                        DictionaryCodec.decodeDictionary(canonical.canonicalBytes()));
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
                Optional<CanonicalObject> canonical =
                        this.resources.residency.verifiedCanonical(object.hash());
                if (canonical.isEmpty()) {
                    this.plan.retryProcessedObject(object.hash());
                    continue;
                }
                if (!this.resources.residency.installMicrotile(canonical.orElseThrow(),
                        this.mappings.catalogId(), this.mappings.blocks(),
                        this.mappings.biomes())) {
                    break;
                }
                this.manifestDirty = true;
            }
        }

        private void acceptSelection(SelectionBatch selection) {
            if (this.plan == null
                    || selection.generation() != this.plan.root().root().generation()) return;
            boolean currentSnapshot = selection.snapshotId() == this.publishedSnapshot;
            if (!currentSnapshot) {
                // Handles and nodes are append-only for one immutable root. A readback from an
                // older residency snapshot therefore remains exact request authority, but cannot
                // cancel newer demand or retire fallback coverage.
                if (selection.snapshotId() == 0 || this.issuedSnapshot == 0
                        || Long.compareUnsigned(selection.snapshotId(), this.issuedSnapshot) > 0) {
                    return;
                }
                selection.disableCancellation();
            }
            ClientLodDebug.selection(selection.generation(), selection.snapshotId(),
                    selection.count(SelectionBatch.Segment.DESIRED),
                    selection.count(SelectionBatch.Segment.REQUESTS),
                    selection.permitsCancellation());
            long manifestRevision = this.plan.manifestRevision();
            int objectCapacity = selection.manifest().objectHandleCapacity();
            HandlePriorities selectedContent = this.selectedContentScratch.begin(objectCapacity);
            HandlePriorities requestedContent = this.requestedContentScratch.begin(objectCapacity);
            HandlePriorities selectedNeighbors = this.selectedNeighborScratch.begin(objectCapacity);
            boolean replace = selection.permitsCancellation();
            int nodeCapacity = selection.manifest().nodeCount();
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
                mergeCut(this.activationCuts, selection, desiredSegment, row);
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
                }
                if (selection.priority(requestSegment, row) == SelectionBatch.Priority.COVERAGE) {
                    this.coverageCuts.add(selection.nodeHandle(requestSegment, row),
                            selection.sectionKey(requestSegment, row));
                }
                collectHandles(requestedContent, selection, requestSegment, row, true);
                collectSelectedNeighborHandles(selectedNeighbors, selection, requestSegment, row);
            }

            if (selection.permitsCancellation()) {
                this.plan.reconcileDemand(nodeKeys.keys, nodeKeys.count);
                this.plan.reconcileSelectedContent(selectedContent.handles,
                        selectedContent.priorities, selectedContent.count);
                this.plan.reconcileSelectedNeighborContent(selectedNeighbors.handles,
                        selectedNeighbors.priorities, selectedNeighbors.count);
                this.plan.reconcileContentRequests(requestedContent.handles,
                        requestedContent.priorities, requestedContent.count);
                this.selectionEpoch = nextEpoch(this.selectionEpoch);
                this.completeFrontier = true;
            } else {
                for (int index = 0; index < nodeKeys.count; index++) {
                    this.plan.addContentDemand(nodeKeys.keys[index]);
                }
                this.plan.retainSelectedContent(selectedContent.handles,
                        selectedContent.priorities, selectedContent.count);
                this.plan.retainSelectedNeighborContent(selectedNeighbors.handles,
                        selectedNeighbors.priorities, selectedNeighbors.count);
                this.plan.requestObjectsByHandle(requestedContent.handles,
                        requestedContent.priorities, requestedContent.count);
            }
            markRelevantActiveNodes();
            if (this.plan.discoveryComplete()) this.plan.sealDiscovery();
            // Only object-table/topology/residency changes require a new GPU snapshot. Repeated
            // identical refined cuts must preserve their complete-frontier authority so they can
            // finish activation and ROOT_READY instead of continuously invalidating themselves.
            if (this.plan.manifestRevision() != manifestRevision) this.manifestDirty = true;
        }

        private void prepareActivations() throws Exception {
            if (this.plan == null || this.mappings == null || this.modelCompatibility == null) {
                return;
            }
            ClientLodDebug.activationPass(this.activationCuts.size());
            for (int cutIndex = 0; cutIndex < this.activationCuts.size(); cutIndex++) {
                if (this.compiling.size() >= MAX_MESHING_JOBS) break;
                SpatialNode node = this.activationCuts.nodeAt(cutIndex);
                SelectionCut desired = this.activationCuts.cutAt(cutIndex);
                if (this.compiling.contains(node) || this.awaitingFence.containsKey(node)
                        || this.retiring.contains(node)) continue;
                Binding binding = this.plan.binding(RootDemandPlan.sectionKey(node))
                        .orElse(null);
                if (binding == null) {
                    ClientLodDebug.activationNoBinding();
                    continue;
                }

                CompatibilityState state = this.content.resolveCompatibility(binding,
                        this.modelCompatibility, this.resources.residency::decodedMicrotile);
                CompatibilityState old = this.compatibility.put(node, state);
                if (!state.equals(old)) this.manifestDirty = true;
                if (state.complexRequiredMask() != 0) {
                    this.plan.requestObjectsByHash(complexCompanions(binding,
                            state.complexRequiredMask()), this.coverageCuts.containsNode(node)
                            ? ContentPriority.COVERAGE : ContentPriority.CURRENT_VIEW);
                }
                SelectionCut effective = effectiveCut(desired, state);
                if (effective == null) {
                    ClientLodDebug.activationEmptyCut();
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
                    ClientLodDebug.activationMissing(missing.requestable().size(),
                            missing.neighborDependencies().size());
                    if (!missing.requestable().isEmpty()) {
                        this.plan.requestObjectsByHash(missing.requestable(),
                                this.coverageCuts.containsNode(node)
                                        ? ContentPriority.COVERAGE
                                        : ContentPriority.CURRENT_VIEW);
                    }
                    // Neighbor misses are intentionally not widened into CPU-side demand here.
                    // A subsequent GPU selection snapshot is the sole authority that may emit
                    // their exact missing handles for the selected source microtiles.
                    continue;
                }
                Optional<MicrotileActivationManager.ActiveGroup> active =
                        this.resources.activations.active(node);
                if (active.isPresent()
                        && active.orElseThrow().content().terrainIdentity()
                        .equals(group.terrainIdentity())
                        && active.orElseThrow().publication().activationFencePassed()) {
                    continue;
                }
                if (!stageActivation(group)) {
                    ClientLodDebug.activationPressure();
                    continue;
                }
                try {
                    this.resources.pinRootObjects(group.root(), group.requiredHashes());
                } catch (RuntimeException | Error failure) {
                    this.resources.activations.cancelCandidate(group.node(), group.root());
                    throw failure;
                }
                this.compiling.add(node);
                RootToken root = this.plan.root().root();
                this.mesherWorker.execute(() -> {
                    long meshingStart = System.nanoTime();
                    Throwable failure = null;
                    try {
                        this.resources.activations.compile(node, root,
                                RootDemandPlan.sectionKey(node), root.generation());
                    } catch (Throwable cause) {
                        failure = cause;
                    } finally {
                        updateEwma(this.meshingMicros,
                                Math.max(1, TimeUnit.NANOSECONDS.toMicros(
                                        System.nanoTime() - meshingStart)));
                    }
                    offer(new CompileEvent(root, node, failure));
                });
            }
        }

        /** Live renderable coverage outranks disposable cache and unpinned decoded content. */
        private boolean stageActivation(ContentPipeline.ActivationGroup group) {
            if (this.resources.activations.stage(group, MESHING_SCRATCH,
                    MAX_NODE_GEOMETRY, ACTIVATION_IN_FLIGHT)) return true;
            this.resources.cache.disableForPressure();
            return this.resources.activations.stage(group, MESHING_SCRATCH,
                    MAX_NODE_GEOMETRY, ACTIVATION_IN_FLIGHT);
        }

        private void scheduleRetirements() throws InterruptedException {
            if (this.plan == null || !this.completeFrontier) return;
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
            putMain(new RetireTask(this, this.plan.root().root(), node, this.selectionEpoch));
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
            long references = saturatingAdd(this.plan.pinReferenceCount(),
                    this.resources.activations.retainedHashReferenceCount());
            long temporaryBytes;
            try {
                temporaryBytes = Math.addExact(4_096L,
                        Math.multiplyExact(references, 384L));
            } catch (ArithmeticException overflow) {
                return;
            }
            Optional<MemoryBudget.Reservation> temporary = this.resources.memory.tryReserve(
                    MemoryBudget.Allocation.of(MemoryBudget.Pool.OBJECT_TABLE,
                            temporaryBytes));
            if (temporary.isEmpty()) return;
            try (MemoryBudget.Reservation ignored = temporary.orElseThrow()) {
                LinkedHashSet<Hash256> retained = new LinkedHashSet<>();
                this.plan.forEachMetadataPin(retained::add);
                this.plan.forEachContentPin(retained::add);
                this.resources.activations.forEachRetainedHash(retained::add);
                this.resources.reconcileRootPins(this.plan.root().root(), retained);
                this.resources.syncCachePins();
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
            RootDemandPlan.ManifestView view = this.plan.manifestView();
            long snapshot = this.nextSnapshot++;
            SelectionManifest manifest = SelectionManifestBuilder.build(view,
                    this.resources.residency, this.resources.activations, snapshot,
                    this.cameraVisibilityDomain,
                    this.compatibility);
            this.completeFrontier = false;
            this.issuedSnapshot = snapshot;
            this.pendingManifest.set(manifest);
            this.manifestDirty = false;
        }

        private void maybeSendRootReady() throws IOException {
            if (this.rootReadySent || !this.completeFrontier || this.plan == null
                    || !this.plan.bootstrapObjectsProcessed()
                    || !this.plan.discoveryComplete()) return;
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
            send(new RootReady(this.dimension, this.plan.root().root()));
            this.rootReadySent = true;
            ClientLodDebug.rootActivated(this.plan.root().root().generation());
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
                throw new IllegalStateException("renderer activation fence failed", fence.failure);
            }
            if (this.retiring.remove(fence.node)) {
                this.lastRelevantSelectionEpoch.remove(fence.node);
                ClientLodDebug.activationRetired(
                        RootDemandPlan.sectionKey(fence.node), fence.root.generation());
                this.manifestDirty = true;
                return;
            }
            ClientLodDebug.activationVisible(
                    RootDemandPlan.sectionKey(fence.node), fence.root.generation());
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
            PendingCatalogBake pending = new PendingCatalogBake(root, catalog, mapped, blocks);
            this.pendingCatalogBake.set(pending);
            pollCatalogBakeOnMain(renderer);
        }

        /** Publishes model compatibility only after every catalog model has finished baking. */
        private void pollCatalogBakeOnMain(VoxyRenderSystem renderer) {
            PendingCatalogBake pending = this.pendingCatalogBake.get();
            if (pending == null) return;
            if (!isCurrent(pending.root())) {
                this.pendingCatalogBake.compareAndSet(pending, null);
                return;
            }
            if (!renderer.virtualSurfaceModelsReady(pending.localBlocksInternal())) return;
            CatalogModelCompatibility compatibility = renderer.createVirtualSurfaceModelCompatibility(
                    pending.catalog(), pending.mappings());
            if (this.pendingCatalogBake.compareAndSet(pending, null)) {
                offer(new CatalogMappedEvent(pending.root(), pending.catalog(),
                        pending.mappings(), compatibility));
            }
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

        private void retireOnMain(RootToken root, SpatialNode node, long epoch) {
            if (!isCurrent(root) || epoch != this.selectionEpoch || !this.completeFrontier
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

        private RootDemandPlan requirePlan(RootToken root) {
            if (!isCurrent(root)) {
                throw new IllegalArgumentException("response names an unretained root");
            }
            return this.plan;
        }

        private boolean isCurrent(RootToken root) {
            return Objects.equals(this.authoritativeRoot, root);
        }

        private void send(Message message) throws IOException {
            writeFrame(message.frameType(), WireMessage.encode(message));
        }

        private void writeFrame(int type, byte[] payload) throws IOException {
            FrameCodec.write(this.output, type, payload);
        }

        private static byte[] encodeCameraDomainRequest(RootToken root, long sequence,
                                                        CameraPosition position) {
            if (sequence == 0) throw new IllegalArgumentException(
                    "camera-domain sequence must be nonzero");
            return ByteBuffer.allocate(CAMERA_DOMAIN_REQUEST_BYTES)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .putLong(root.generation())
                    .put(root.dimensionHash().toBytes())
                    .put(root.rootHash().toBytes())
                    .putLong(sequence)
                    .putInt(position.blockX)
                    .putInt(position.blockY)
                    .putInt(position.blockZ)
                    .array();
        }

        private static CameraDomainEvent decodeCameraDomain(byte[] payload)
                throws FrameCodec.FrameException {
            requireLength(payload, CAMERA_DOMAIN_RESPONSE_BYTES, "CAMERA_DOMAIN");
            try {
                ByteBuffer input = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
                RootToken root = new RootToken(input.getLong(), readHash(input), readHash(input));
                long sequence = input.getLong();
                int state = Byte.toUnsignedInt(input.get());
                long domain = input.getLong();
                int minX = input.getInt();
                int minY = input.getInt();
                int minZ = input.getInt();
                int maxX = input.getInt();
                int maxY = input.getInt();
                int maxZ = input.getInt();
                boolean paired = sequence != 0 && switch (state) {
                    case 0 -> domain == 0;
                    case 1 -> domain == 1;
                    case 2 -> Long.compareUnsigned(domain, 2) >= 0;
                    default -> false;
                };
                if (!paired) throw new FrameCodec.FrameException(
                        "CAMERA_DOMAIN state/domain pairing is invalid");
                if (minX > maxX || minY > maxY || minZ > maxZ) {
                    throw new FrameCodec.FrameException(
                            "CAMERA_DOMAIN lease bounds are inverted");
                }
                return new CameraDomainEvent(root, sequence, domain,
                        minX, minY, minZ, maxX, maxY, maxZ);
            } catch (IllegalArgumentException failure) {
                throw new FrameCodec.FrameException(
                        "CAMERA_DOMAIN contains an invalid root token");
            }
        }

        private static Hash256 readHash(ByteBuffer input) {
            return new Hash256(input.getLong(), input.getLong(), input.getLong(), input.getLong());
        }

        private void markOutstanding(List<Hash256> hashes, long estimatedBytes) {
            for (Hash256 hash : hashes) {
                if (this.outstandingObjects.putIfAbsent(hash, estimatedBytes) == null) {
                    this.outstandingBytes.addAndGet(estimatedBytes);
                }
            }
        }

        private void completeOutstanding(Hash256 hash) {
            Long estimated = this.outstandingObjects.remove(hash);
            if (estimated != null) this.outstandingBytes.addAndGet(-estimated);
        }

        private void recordInboundBytes(long bytes) {
            long now = System.nanoTime();
            this.throughputWindowBytes = saturatingAdd(this.throughputWindowBytes, bytes);
            long elapsed = now - this.throughputWindowStart;
            if (elapsed < TELEMETRY_WINDOW_NANOS) return;
            long rate = (long) Math.min(Long.MAX_VALUE,
                    this.throughputWindowBytes * (1_000_000_000.0 / elapsed));
            updateEwma(this.throughputBytesPerSecond, Math.max(1, rate));
            this.throughputWindowStart = now;
            this.throughputWindowBytes = 0;
        }

        private void decodeHello(byte[] payload) throws FrameCodec.FrameException {
            requireLength(payload, Long.BYTES, "HELLO");
            ByteBuffer input = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
            this.serverInstance = input.getLong();
        }

        private void offer(StateEvent event) {
            if (!this.open || !this.state.offer(event)) {
                reject(event, new IOException("state queue is closed or full"));
                close();
            }
        }

        private void fail(String message, Throwable failure) {
            ClientLodDebug.failure(message);
            if (failure == null) Logger.warn(message); else Logger.warn(message, failure);
            close();
        }

        @Override
        public void close() {
            boolean initiator = this.closing.compareAndSet(false, true);
            if (initiator) {
                this.open = false;
                this.pendingCatalogBake.set(null);
                try {
                    this.transport.close();
                } catch (IOException ignored) {}
                Thread thread = this.writer;
                if (thread != null && thread != Thread.currentThread()) thread.interrupt();
                try {
                    this.decoder.close();
                } catch (RuntimeException failure) {
                    Logger.warn("Failed to close the object decoder cleanly", failure);
                } finally {
                    this.decoderWorker.shutdownNow();
                    this.mesherWorker.shutdownNow();
                    this.shutdownStarted.countDown();
                }
            } else {
                awaitLatch(this.shutdownStarted);
            }

            Thread current = Thread.currentThread();
            boolean worker = current == this.decoderThread.get() || current == this.mesherThread.get();
            if (!worker) {
                awaitTermination(this.decoderWorker);
                awaitTermination(this.mesherWorker);
                awaitThread(this.writer);
            } else if (initiator) {
                ExecutorService other = current == this.decoderThread.get()
                        ? this.mesherWorker : this.decoderWorker;
                awaitTermination(other);
            }

            if (initiator) {
                this.outstandingObjects.clear();
                this.outstandingBytes.set(0);
                StateEvent event;
                while ((event = this.state.poll()) != null) {
                    reject(event, new IOException("session closed"));
                }
            }
        }

        private static void awaitLatch(java.util.concurrent.CountDownLatch latch) {
            boolean interrupted = false;
            while (true) {
                try {
                    latch.await();
                    break;
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }

        private static void awaitTermination(ExecutorService executor) {
            boolean interrupted = false;
            while (!executor.isTerminated()) {
                try {
                    executor.awaitTermination(100, TimeUnit.MILLISECONDS);
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }

        private static void awaitThread(Thread thread) {
            if (thread == null || thread == Thread.currentThread()) return;
            boolean interrupted = false;
            while (thread.isAlive()) {
                try {
                    thread.join();
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

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
                              SpatialNode node, long selectionEpoch) implements MainTask {
        @Override
        public void apply(ClientLevel level, VoxyRenderSystem renderer,
                          SessionResources resources) {
            this.connection.retireOnMain(this.root, this.node, this.selectionEpoch);
        }

        @Override
        public void cancel() {
            this.connection.offer(new RetireEvent(this.root, this.node,
                    MicrotileActivationManager.RemovalStatus.BLOCKED, null));
        }
    }

    private sealed interface StateEvent permits RootEvent, EnvelopeEvent, DecodedBatchEvent,
            CatalogMappedEvent, SelectionEvent, ManifestPublishedEvent, CompileEvent,
            PublishEvent, RetireEvent, FenceEvent, CameraDomainEvent {}

    private record RootEvent(RootAnnounce root) implements StateEvent {}
    private record EnvelopeEvent(RootToken root, boolean subtree, List<EncodedObject> objects,
                                 CompletableFuture<Void> completion) implements StateEvent {
        private EnvelopeEvent(RootToken root, boolean subtree, List<EncodedObject> objects) {
            this(root, subtree, List.copyOf(objects), new CompletableFuture<>());
        }
    }
    private record DecodedObject(EncodedObject encoded, CanonicalObject canonical) {}
    private record PendingCatalogBake(RootToken root, CatalogCodec.Catalog catalog,
                                      ContentPipeline.CatalogMappings mappings,
                                      int[] localBlocks) {
        private PendingCatalogBake {
            Objects.requireNonNull(root, "root");
            Objects.requireNonNull(catalog, "catalog");
            Objects.requireNonNull(mappings, "mappings");
            localBlocks = Objects.requireNonNull(localBlocks, "localBlocks").clone();
        }

        @Override
        public int[] localBlocks() {
            return this.localBlocks.clone();
        }

        private int[] localBlocksInternal() {
            return this.localBlocks;
        }
    }
    private record DecodedBatchEvent(RootToken root, boolean subtree,
                                     List<DecodedObject> objects,
                                     CompletableFuture<Void> completion) implements StateEvent {
        private DecodedBatchEvent(RootToken root, boolean subtree, List<DecodedObject> objects) {
            this(root, subtree, List.copyOf(objects), new CompletableFuture<>());
        }
    }
    private record CatalogMappedEvent(RootToken root, CatalogCodec.Catalog catalog,
                                      ContentPipeline.CatalogMappings mappings,
                                      CatalogModelCompatibility compatibility) implements StateEvent {}
    private record SelectionEvent(SelectionBatch selection) implements StateEvent {}
    private record ManifestPublishedEvent(long generation, long snapshot) implements StateEvent {}
    private record CompileEvent(RootToken root, SpatialNode node,
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
                                     int maxX, int maxY, int maxZ) implements StateEvent {
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
    private record ServerError(int code, String text) {}

    @FunctionalInterface
    private interface CheckedAction {
        void run() throws Exception;
    }

    private static void complete(CompletableFuture<Void> completion, CheckedAction action)
            throws Exception {
        try {
            action.run();
            completion.complete(null);
        } catch (Throwable failure) {
            completion.completeExceptionally(failure);
            if (failure instanceof Exception exception) throw exception;
            throw (Error) failure;
        }
    }

    private static void reject(StateEvent event, Throwable failure) {
        if (event instanceof EnvelopeEvent envelope) {
            envelope.completion.completeExceptionally(failure);
        } else if (event instanceof DecodedBatchEvent batch) {
            batch.completion.completeExceptionally(failure);
        } else if (event instanceof SelectionEvent selection) {
            selection.selection.close();
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
        for (int ordinal = 0; ordinal < 3; ordinal++) {
            var contentClass = contentClass(ordinal);
            long selected = batch.selectedMask(segment, row, contentClass);
            if (selected == 0) continue;
            var state = batch.contentState(segment, row, contentClass);
            addSelectedObjects(result, state, selected, priority, requestsOnly);
            addDependencies(result, state.dependencyHandlesInternal(),
                    state.residentDependenciesInternal(), state.inFlightDependenciesInternal(),
                    priority, requestsOnly);
            if (requestsOnly) addSelectedNeighbors(result, state, selected, priority, true);
        }
    }

    /** Retains selected neighbor context without granting request authority to the CPU. */
    private static void collectSelectedNeighborHandles(
            HandlePriorities result, SelectionBatch batch,
            SelectionBatch.Segment segment, int row) {
        ContentPriority priority = contentPriority(batch.priority(segment, row));
        for (int ordinal = 0; ordinal < 3; ordinal++) {
            var contentClass = contentClass(ordinal);
            long selected = batch.selectedMask(segment, row, contentClass);
            if (selected != 0) addSelectedNeighbors(result,
                    batch.contentState(segment, row, contentClass), selected, priority, false);
        }
    }

    private static void addSelectedObjects(HandlePriorities target,
                                           me.cortex.voxy.client.core.rendering.selection.SelectionManifest.ContentState state,
                                           long selected, ContentPriority priority,
                                           boolean missingOnly) {
        long accepted = missingOnly
                ? selected & ~(state.residentMask() | state.inFlightMask()) : selected;
        int dense = 0;
        int[] handles = state.objectHandlesInternal();
        for (int microtile = 0; microtile < Long.SIZE; microtile++) {
            long bit = 1L << microtile;
            if ((state.availableMask() & bit) == 0) continue;
            int handle = handles[dense++];
            if ((accepted & bit) != 0) mergePriority(target, handle, priority);
        }
    }

    private static void addDependencies(HandlePriorities target, int[] handles,
                                        java.util.BitSet resident, java.util.BitSet inFlight,
                                        ContentPriority priority, boolean missingOnly) {
        for (int index = 0; index < handles.length; index++) {
            if (!missingOnly || !resident.get(index) && !inFlight.get(index)) {
                mergePriority(target, handles[index], priority);
            }
        }
    }

    private static void addSelectedNeighbors(HandlePriorities target,
                                             me.cortex.voxy.client.core.rendering.selection.SelectionManifest.ContentState state,
                                             long selected, ContentPriority priority,
                                             boolean missingOnly) {
        int[] handles = state.neighborDependencyHandlesInternal();
        int[] sources = state.neighborDependencySourcesInternal();
        for (int index = 0; index < handles.length; index++) {
            if ((selected & 1L << sources[index]) == 0) continue;
            if (missingOnly && (state.residentNeighborDependenciesInternal().get(index)
                    || state.inFlightNeighborDependenciesInternal().get(index))) continue;
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
                insertKey(key);
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

        private void insertKey(long key) {
            int mask = this.keyTable.length - 1;
            int slot = mix(key) & mask;
            while (this.keyStates[slot] != 0) {
                if (this.keyTable[slot] == key) return;
                slot = slot + 1 & mask;
            }
            this.keyStates[slot] = 1;
            this.keyTable[slot] = key;
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
            this.keyStates = new byte[tableCapacity];
            for (int index = 0; index < this.count; index++) {
                insertKey(this.keysByHandle[this.activeHandles[index]]);
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

    private static List<Hash256> complexCompanions(Binding binding, long mask) {
        ArrayList<Hash256> hashes = new ArrayList<>();
        for (RootDemandPlan.ContentLayer layer : binding.layers()) {
            if (layer.contentClass() != ContentClass.COMPLEX) continue;
            for (ContentObject object : layer.objects()) {
                if ((mask & 1L << object.microtileIndex()) != 0) hashes.add(object.hash());
            }
        }
        return List.copyOf(hashes);
    }

    private static boolean isMicrotile(ObjectKind kind) {
        return kind == ObjectKind.EXTERIOR_MICROTILE
                || kind == ObjectKind.INTERIOR_MICROTILE
                || kind == ObjectKind.COMPLEX_MICROTILE;
    }

    private static ServerError decodeError(byte[] payload)
            throws FrameCodec.FrameException {
        if (payload.length < Integer.BYTES || payload.length > Integer.BYTES + MAX_NAME) {
            throw new FrameCodec.FrameException("invalid ERROR payload");
        }
        ByteBuffer input = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        int code = input.getInt();
        byte[] text = new byte[input.remaining()];
        input.get(text);
        try {
            return new ServerError(code, StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(text)).toString());
        } catch (CharacterCodingException failure) {
            throw new FrameCodec.FrameException("ERROR text is not UTF-8");
        }
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

    private static void requireLength(byte[] payload, int expected, String label)
            throws FrameCodec.FrameException {
        if (payload.length != expected) {
            throw new FrameCodec.FrameException(label + " has an invalid length");
        }
    }

    private static ByteBuffer little(int bytes) {
        return ByteBuffer.allocate(bytes).order(ByteOrder.LITTLE_ENDIAN);
    }
}
