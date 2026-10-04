package com.aerosmp.voxy.client;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL43C.*;

import com.aerosmp.voxy.Common.*;
import com.aerosmp.voxy.mixin.VoxyMixins;
import com.google.gson.stream.JsonWriter;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;

import net.caffeinemc.mods.sodium.api.config.*;
import net.caffeinemc.mods.sodium.api.config.option.*;
import net.caffeinemc.mods.sodium.api.texture.SpriteUtil;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.data.SectionRenderDataUnsafe;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.*;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.SimpleBakedModel;
import net.minecraft.client.resources.model.WeightedBakedModel;
import net.minecraft.core.*;
import net.minecraft.core.registries.*;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.*;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.*;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.*;
import net.neoforged.neoforge.client.model.BakedModelWrapper;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.common.NeoForge;

import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.io.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** One owner per section; local meshing and fence publication preserve the preceding coverage. */
public final class TerrainRenderer implements AutoCloseable {
    public static final class Section {
        final Key key;
        Section parent;
        Section[] branch;
        Rank rank;
        int queued = -1, interestChildren = -1, publishedChildren = -1, mode, drawn;
        boolean dirty, shown, complete, detailWanted;
        long view;
        long envelopeRevision, rankedEnvelopeRevision;
        volatile long purpose;
        long parked = -1;
        boolean parkedNeeded;

        Section(Key key) {
            this.key = key;
            envelope = keyBounds(key);
        }

        volatile byte[] cacheHash;
        volatile byte[] serverVerifiedHash;
        volatile Loaded local;
        volatile Fallback fallback;
        volatile int children = -1;
        volatile boolean active, needed, unavailable, corrupt, checked, incomplete;
        volatile boolean coveredByChildren;
        volatile long requiredBytes;
        volatile Revision failure, deniedInput;
        boolean covering, refined;
        volatile boolean occluded;
        volatile Geometry current;
        volatile Pending pending;
        int query;
        volatile boolean querying;
        boolean hidden;
        volatile Camera queryCamera;
        volatile long queryScene, boundsRevision, queryBoundsRevision;
        AABB bounds;
        AABB envelope;
        boolean envelopeUnknown, retainedEnvelope;
        double diameter, distance;
    }

    record Fallback(byte[] hash, byte[] payload) {}

    record Revision(byte[] hash, long models, long purpose) {}

    private record Rank(int level, double benefit, int x, int y, int z)
            implements Comparable<Rank> {
        public int compareTo(Rank other) {
            int result = Integer.compare(other.level, level);
            if (result == 0) result = Double.compare(other.benefit, benefit);
            if (result == 0) result = Integer.compare(x, other.x);
            if (result == 0) result = Integer.compare(y, other.y);
            return result == 0 ? Integer.compare(z, other.z) : result;
        }
    }

    private record Layer(RenderType type, MeshData mesh) {}

    private static final class GpuLayer {
        int vertices, elements;
        final MeshData.DrawState state;
        final MeshData.SortState sort;
        VertexBuffer buffer;
        double x = Double.NaN, y, z;

        GpuLayer(MeshData.DrawState state, MeshData.SortState sort) {
            this.state = state;
            this.sort = sort;
        }

        void upload(MeshData mesh) {
            vertices = upload(mesh.vertexBuffer());
            try {
                elements = mesh.indexBuffer() == null ? 0 : upload(mesh.indexBuffer());
            } catch (Throwable failure) {
                glDeleteBuffers(vertices);
                vertices = 0;
                throw failure;
            }
        }

        private static int upload(java.nio.ByteBuffer bytes) {
            int id = glGenBuffers();
            glBindBuffer(GL_ARRAY_BUFFER, id);
            glBufferData(GL_ARRAY_BUFFER, bytes, GL_STATIC_DRAW);
            int error = glGetError();
            if (error != GL_NO_ERROR) {
                glDeleteBuffers(id);
                throw new IllegalStateException("Terrain GPU upload failed: " + error);
            }
            return id;
        }

        void adopt() {
            if (!glIsBuffer(vertices) || elements != 0 && !glIsBuffer(elements))
                throw new IllegalStateException("Shared terrain buffers unavailable");
            buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
            buffer.bind();
            try {
                ((VoxyClient.SharedVertices) (Object) buffer).adopt(vertices, elements, state);
            } finally {
                VertexBuffer.unbind();
            }
        }

        void close() {
            if (buffer != null) buffer.close();
            else {
                glDeleteBuffers(vertices);
                if (elements != 0) glDeleteBuffers(elements);
            }
        }
    }

    private record Geometry(
            Key key,
            byte[] hash,
            int children,
            Map<RenderType, GpuLayer> layers,
            Draw[] translucent,
            List<Entity> entities,
            TextureAtlasSprite[] sprites,
            AABB bounds,
            boolean boundsKnown,
            long bytes,
            long models) {
        void close() {
            layers.values().forEach(GpuLayer::close);
        }
    }

    private record Pending(Geometry geometry, long fence) {}

    private record Draw(RenderType type, GpuLayer layer) {}

    private record Model(BlockState state, BakedModel baked, boolean enclosedSafe) {}

    private record Entity(
            BlockEntity block, int light, BlockEntityRenderer<BlockEntity> renderer, AABB scope) {}

    private record PreparedEntity(BlockEntity block, ModelData data) {}

    private record Inspection(int x, int y, Path output) {}

    private record InspectionHit(Section owner, Geometry geometry, double distance) {}

    private enum DepthChange {
        SELECTED_OPAQUE,
        SELECTION_CLEARED,
        NATIVE_PUBLICATION,
        NATIVE_MEMBERSHIP,
        SHADER_STATE,
        CLIPPING,
        RESOURCE_RELOAD
    }

    private enum QueryDiscard {
        SCENE,
        CAMERA_POSITION,
        CAMERA_MATRIX,
        TARGET,
        BOUNDS_REVISION,
        UNKNOWN_BOUNDS,
        SHADER_STATE,
        UNCLASSIFIED
    }

    private static ShaderInstance shader;
    private static volatile long models;
    private static TerrainRenderer active;
    private static final ThreadLocal<LevelAccessor> entityWorld = new ThreadLocal<>();
    private static final Direction[] SIDES = Direction.values();
    private static final int[] BOX = {
        0, 1, 3, 2, 4, 6, 7, 5, 0, 4, 5, 1, 2, 3, 7, 6, 0, 2, 6, 4, 1, 5, 7, 3
    };
    final ConcurrentMap<Key, Section> sections = new ConcurrentHashMap<>();
    private final LinkedHashMap<Key, Geometry> inactive = new LinkedHashMap<>();
    private final LinkedHashMap<Key, Geometry> redundant = new LinkedHashMap<>();
    private final NavigableMap<Long, Set<Section>> neededDenied = new TreeMap<>();
    private final NavigableMap<Long, Set<Section>> preparedDenied = new TreeMap<>();
    private final LinkedHashMap<Key, Geometry> prepared = new LinkedHashMap<>(16, .75f, true);
    private final List<Section> reclaimVictims =
            new ArrayList<>(); // Render-thread selection, cleared after each admission.
    private final ConcurrentMap<Key, Section> pending = new ConcurrentHashMap<>();
    private final Set<Section> queries = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<RenderSection> nativeOpaque =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<RenderSection> nextNativeOpaque =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<RenderSection> nativeOwners =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<RenderSection> nextNativeOwners =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<RenderType, Map<Section, GpuLayer>> opaque = new LinkedHashMap<>();
    private final NavigableMap<Section, Geometry> selected =
            new TreeMap<>(
                    Comparator.comparingDouble((Section section) -> section.distance)
                            .reversed()
                            .thenComparingInt(section -> section.key.level())
                            .thenComparingInt(section -> section.key.x())
                            .thenComparingInt(section -> section.key.y())
                            .thenComparingInt(section -> section.key.z()));
    private final NavigableMap<Section, Geometry> transparentOwners =
            new TreeMap<>(selected.comparator());
    private final NavigableMap<Section, Geometry> entityOwners =
            new TreeMap<>(selected.comparator());
    private final it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap<TextureAtlasSprite>
            visibleSprites = new it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap<>();
    private final Map<BlockState, Model> modelCache = new IdentityHashMap<>();
    private final Map<String, Biome> biomeCache = new HashMap<>();
    private final Scratch scratch = new Scratch();
    private Snapshot meshing;
    private final ByteBufferBuilder indices = new ByteBufferBuilder(4096);
    private final Consumer<RenderLevelStageEvent> renderListener = this::render;
    private final Consumer<ViewportEvent.RenderFog> fogListener = this::fog;
    private final Minecraft client = Minecraft.getInstance();
    private final Object meshLock = new Object();
    private final long uploadWindow;
    private org.lwjgl.opengl.GLCapabilities uploadCaps;
    private volatile CompletableFuture<Long> eviction;
    private volatile CompletableFuture<Void> preparation;
    private final java.util.concurrent.atomic.AtomicLong gpuBytes =
            new java.util.concurrent.atomic.AtomicLong();
    private final ConcurrentNavigableMap<Rank, Section> demand = new ConcurrentSkipListMap<>();
    private final List<LinkedHashMap<Key, Section>> jobs = new ArrayList<>();
    private final Queue<Section> changes = new ConcurrentLinkedQueue<>();
    private final Set<Section> hidden = Collections.newSetFromMap(new IdentityHashMap<>());
    private volatile List<Key> roots = List.of();
    private volatile Camera camera;
    private final java.util.concurrent.atomic.AtomicReference<Inspection> inspection =
            new java.util.concurrent.atomic.AtomicReference<>();
    private boolean stableCamera, queriesDirty = true, nativeChanged, queryClipping = true;
    private MethodHandle shaderPackGetter;
    private volatile boolean shaderPackKnown = true, shaderPackInUse;
    private String shaderPackError = "";
    private long queryFence;
    private volatile long reclaimBytes;
    private long inactiveBytes, preparedBytes, redundantBytes, currentGpuBytes, selectedBytes;
    private long deniedBytes, neededDeniedBytes, deniedCount, positiveDenials;
    private long pressureAdmissions, pressureDenials, reclaimedBytes, redundantEvictions;
    private long redundantEvictedBytes;
    private volatile long coveredMeshSkips;
    private volatile long view;
    private final java.util.concurrent.atomic.AtomicLong requestRevision =
            new java.util.concurrent.atomic.AtomicLong();
    private long demandDepth;
    private boolean rebuild = true;
    private int demandDistance;
    private float demandPixels;
    private volatile boolean closed;
    boolean enabled = true, clipping = true;
    float pixelOverride = Float.NaN;
    private long revision,
            gpuRevision,
            meshNanos,
            meshCount,
            drawNanos,
            uploads,
            evictions,
            failures,
            queryCount,
            rejected;
    private volatile long scene;
    private final long[] depthChanges = new long[DepthChange.values().length];
    private final long[] queryDiscards = new long[QueryDiscard.values().length];
    private long issuedQueries, issuedBatches, rawZero, rawNonzero;
    private long acceptedZero, acceptedNonzero, discardedQueries, boundsChanges, pendingFenceFrames;
    private Key lastDiscardKey;
    private int lastDiscardMask;
    private Camera solidCamera;
    private long solidScene = -1, solidCaptures, solidCaptureMisses;
    private String solidMiss = "";
    private volatile long freeGpu = Long.MAX_VALUE;
    private DynamicTexture coverage;
    private final NativeCoverage nativeCoverage = new NativeCoverage();
    private final NativeCoverage queryDepth = new NativeCoverage();
    private final Matrix4f nativeInverse = new Matrix4f();
    private Object vanillaLists;
    private VertexBuffer box;
    private int clipX, clipY, clipZ, clipSide, clipHeight;
    private final Matrix4f transform = new Matrix4f();
    private final BlockPos.MutableBlockPos clipPos = new BlockPos.MutableBlockPos();

    public TerrainRenderer() {
        for (int i = 0; i < 10; i++) jobs.add(new LinkedHashMap<>());
        long mainWindow = client.getWindow().getWindow();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(
                GLFW_CONTEXT_VERSION_MAJOR,
                glfwGetWindowAttrib(mainWindow, GLFW_CONTEXT_VERSION_MAJOR));
        glfwWindowHint(
                GLFW_CONTEXT_VERSION_MINOR,
                glfwGetWindowAttrib(mainWindow, GLFW_CONTEXT_VERSION_MINOR));
        glfwWindowHint(GLFW_OPENGL_PROFILE, glfwGetWindowAttrib(mainWindow, GLFW_OPENGL_PROFILE));
        uploadWindow = glfwCreateWindow(1, 1, "Voxy terrain uploads", 0, mainWindow);
        glfwDefaultWindowHints();
        if (uploadWindow == 0)
            throw new IllegalStateException("Cannot create shared terrain upload context");
        var caps = org.lwjgl.opengl.GL.getCapabilities();
        freeGpu = freeGpuMemory();
        System.out.println(
                "[Voxy GPU] renderer="
                        + glGetString(GL_RENDERER)
                        + " NVX="
                        + caps.GL_NVX_gpu_memory_info
                        + " ATI="
                        + caps.GL_ATI_meminfo
                        + " freeBytes="
                        + freeGpu);
        active = this;
        initializeShaderPackState();
        NeoForge.EVENT_BUS.addListener(RenderLevelStageEvent.class, renderListener);
        NeoForge.EVENT_BUS.addListener(ViewportEvent.RenderFog.class, fogListener);
    }

    public static void registerShaders(RegisterShadersEvent event) {
        try {
            event.registerShader(
                    new ShaderInstance(
                            event.getResourceProvider(),
                            ResourceLocation.fromNamespaceAndPath("voxy", "terrain"),
                            VoxelVertices.FORMAT),
                    loaded -> {
                        shader = loaded;
                        models++;
                    });
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    public static int viewDistance() {
        return active == null ? 0 : ClientSettings.distance;
    }

    private void initializeShaderPackState() {
        if (!net.neoforged.fml.ModList.get().isLoaded("iris")) return;
        shaderPackKnown = false;
        try {
            Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            Object instance = api.getMethod("getInstance").invoke(null);
            shaderPackGetter =
                    MethodHandles.publicLookup()
                            .findVirtual(
                                    api, "isShaderPackInUse", MethodType.methodType(boolean.class))
                            .bindTo(instance);
        } catch (ReflectiveOperationException | LinkageError failure) {
            shaderPackFailure(failure);
        }
    }

    /** A dynamic shader can change native depth without publishing new geometry. */
    private void updateShaderPackState() {
        if (shaderPackGetter == null) return;
        try {
            boolean inUse = (boolean) shaderPackGetter.invokeExact();
            if (!shaderPackKnown || shaderPackInUse != inUse) {
                shaderPackKnown = true;
                shaderPackInUse = inUse;
                depthChanged(DepthChange.SHADER_STATE);
            }
        } catch (Throwable failure) {
            if (failure instanceof VirtualMachineError fatal) throw fatal;
            if (failure instanceof ThreadDeath stopped) throw stopped;
            shaderPackFailure(failure);
            depthChanged(DepthChange.SHADER_STATE);
        }
    }

    private void shaderPackFailure(Throwable failure) {
        shaderPackKnown = false;
        shaderPackGetter = null;
        shaderPackError = failure.toString();
        System.err.println("Voxy shader-pack state unavailable: " + failure);
    }

    private void fog(ViewportEvent.RenderFog event) {
        if (event.getMode() != FogRenderer.FogMode.FOG_TERRAIN
                || event.getType() != FogType.NONE
                || event.getFarPlaneDistance() <= 32) return;
        event.setNearPlaneDistance(ClientSettings.distance * .6f);
        event.setFarPlaneDistance(ClientSettings.distance);
        event.setFogShape(com.mojang.blaze3d.shaders.FogShape.SPHERE);
        event.setCanceled(true);
    }

    Collection<Section> wanted() {
        return demand.values();
    }

    List<Key> roots() {
        return roots;
    }

    long view() {
        return requestRevision.get();
    }

    void requestChanged() {
        requestRevision.incrementAndGet();
    }

    void wakeup() {
        synchronized (jobs) {
            jobs.notifyAll();
        }
    }

    Section job() throws InterruptedException {
        synchronized (jobs) {
            for (var queue : jobs)
                if (!queue.isEmpty()) {
                    Section section = queue.pollFirstEntry().getValue();
                    section.queued = -1;
                    return section;
                }
            jobs.wait();
            return null;
        }
    }

    void enqueue(Section section) {
        if (!workVisible(section)) section.local = null;
        synchronized (jobs) {
            int next =
                    section.active && workVisible(section) && retry(section)
                            ? (section.needed ? 0 : 5) + 4 - section.key.level()
                            : -1;
            if (next == section.queued) return;
            if (section.queued >= 0) jobs.get(section.queued).remove(section.key);
            section.queued = next;
            if (next >= 0) jobs.get(next).put(section.key, section);
            jobs.notifyAll();
        }
    }

    private void changed(Section section) {
        synchronized (section) {
            if (!section.dirty) {
                section.dirty = true;
                changes.add(section);
            }
        }
    }

    void deferred(Section section) {
        changed(section);
    }

    boolean retry(Section section) {
        Revision failure = section.failure, denial = section.deniedInput;
        return (failure == null
                        || failure.models != models
                        || failure.purpose != section.purpose
                        || !Arrays.equals(failure.hash, section.cacheHash))
                && (denial == null
                        || denial.models != models
                        || !Arrays.equals(denial.hash, section.cacheHash)
                        || !ownMeshRequired(section)
                        || capacity(section));
    }

    private boolean capacity(Section section) {
        return freeGpu >= section.requiredBytes
                || section.needed && reclaimBytes >= section.requiredBytes - freeGpu;
    }

    /** Hierarchy/source interest can continue while resident finer terrain owns the coverage. */
    private boolean ownMeshRequired(Section section) {
        return !section.coveredByChildren;
    }

    Revision attempt(Section section, byte[] hash) {
        return new Revision(hash, models, section.purpose);
    }

    boolean valid(Section section, Revision attempt) {
        return !closed
                && sections.get(section.key) == section
                && section.active
                && workVisible(section)
                && section.purpose == attempt.purpose
                && models == attempt.models
                && Arrays.equals(section.cacheHash, attempt.hash);
    }

    /** Visibility pauses new work; existing cached coverage keeps its render ownership. */
    boolean workVisible(Section section) {
        if (section.incomplete) return true;
        Camera current = camera;
        for (Section owner = section; owner != null; owner = owner.parent)
            if (owner.occluded && !owner.querying && validQuery(owner, current)) return false;
        return true;
    }

    private void workChanged(Section section) {
        enqueue(section);
        if (section.branch != null)
            for (Section child : section.branch)
                if (child != null && child.active) workChanged(child);
    }

    void failed(Section section, Revision attempt) {
        section.failure = attempt == null ? attempt(section, section.cacheHash) : attempt;
        section.checked = false;
    }

    boolean loaded(Section section, byte[] hash) {
        Geometry current = section.current;
        Pending pending = section.pending;
        return current != null && current.models == models && Arrays.equals(current.hash, hash)
                || pending != null
                        && pending.geometry.models == models
                        && Arrays.equals(pending.geometry.hash, hash);
    }

    void available(Key key, int children) {
        available(sections.computeIfAbsent(key, Section::new), children);
    }

    void available(Section section, int children) {
        synchronized (section) {
            if (sections.get(section.key) == section && section.children != children) {
                section.children = children;
                changed(section);
            }
        }
    }

    Map<String, Object> status() {
        Map<String, Object> result =
                new LinkedHashMap<>(
                        Map.of(
                                "wanted",
                                demand.size(),
                                "GPUready",
                                sections.values().stream()
                                        .filter(section -> section.current != null)
                                        .count(),
                                "selected",
                                selected.size(),
                                "gpuBytes",
                                gpuBytes.get(),
                                "meshes",
                                meshCount,
                                "meshNanos",
                                meshNanos,
                                "lastDrawNanos",
                                drawNanos,
                                "uploads",
                                uploads,
                                "evictions",
                                evictions,
                                "publicationFailures",
                                failures));
        result.put("depthPasses", queryCount);
        result.put("depthRejected", rejected);
        result.put("depth", depthStatus());
        result.put("cachedInactive", inactive.size());
        result.put("gpuFreeBytes", freeGpu);
        result.put("preparedGPU", prepared.size());
        result.put("gpuAdmission", admissionStatus());
        result.put("animatedSprites", visibleSprites.size());
        result.put("transparentOwners", transparentOwners.size());
        result.put("entityOwners", entityOwners.size());
        result.put("shaderPackKnown", shaderPackKnown);
        result.put("shaderPackInUse", shaderPackInUse);
        result.put("shaderPackError", shaderPackError);
        result.put("nativeDepthFailures", nativeCoverage.failures());
        result.put("nativeDepthError", nativeCoverage.error());
        result.put("fov", client.options.fov().get());
        if (camera != null)
            result.put("projectionYMillionths", (long) (camera.matrix.m11() * 1_000_000));
        return result;
    }

    private Map<String, Object> admissionStatus() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("currentBytes", currentGpuBytes);
        result.put("pendingBytes", gpuBytes.get() - currentGpuBytes);
        result.put("selectedBytes", selectedBytes);
        result.put("pinnedBytes", currentGpuBytes - reclaimBytes);
        result.put("reclaimableBytes", reclaimBytes);
        result.put("inactiveBytes", inactiveBytes);
        result.put("preparedBytes", preparedBytes);
        result.put("redundantAncestorBytes", redundantBytes);
        result.put("redundantAncestors", redundant.size());
        result.put("waiting", deniedCount);
        result.put("waitingPositiveCount", positiveDenials);
        result.put("waitingBytes", deniedBytes);
        result.put("waitingNeededBytes", neededDeniedBytes);
        result.put("pressureAdmissions", pressureAdmissions);
        result.put("pressureDenials", pressureDenials);
        result.put("reclaimedBytes", reclaimedBytes);
        result.put("redundantEvictions", redundantEvictions);
        result.put("redundantEvictedBytes", redundantEvictedBytes);
        result.put("coveredMeshSkips", coveredMeshSkips);
        return result;
    }

    private Map<String, Object> depthStatus() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("epoch", scene);
        result.put("issued", issuedQueries);
        result.put("batches", issuedBatches);
        result.put("retired", queryCount);
        result.put("rawZero", rawZero);
        result.put("rawNonzero", rawNonzero);
        result.put("acceptedOccluded", acceptedZero);
        result.put("acceptedVisible", acceptedNonzero);
        result.put("discarded", discardedQueries);
        result.put("occludedNow", rejected);
        result.put("boundsChanges", boundsChanges);
        result.put("pendingFenceFrames", pendingFenceFrames);
        result.put("solidCaptures", solidCaptures);
        result.put("solidCaptureMisses", solidCaptureMisses);
        result.put("solidDepthFailures", queryDepth.failures());
        result.put("solidDepthError", queryDepth.error());
        result.put("solidCaptureScene", solidScene);
        result.put("solidCaptureMiss", solidMiss);
        if (solidCamera != null)
            result.put("solidCameraMatrix", solidCamera.matrix.get(new float[16]));
        Map<String, Long> epochs = new LinkedHashMap<>();
        for (DepthChange reason : DepthChange.values())
            epochs.put(reason.name(), depthChanges[reason.ordinal()]);
        result.put("epochReasons", epochs);
        // Causes overlap: one discarded ticket can have both a new scene and a new camera.
        Map<String, Long> discards = new LinkedHashMap<>();
        for (QueryDiscard reason : QueryDiscard.values())
            discards.put(reason.name(), queryDiscards[reason.ordinal()]);
        result.put("discardReasons", discards);
        if (lastDiscardKey != null) {
            result.put("lastDiscardKey", lastDiscardKey.filename());
            result.put("lastDiscardMask", lastDiscardMask);
        }
        Camera current = camera;
        if (current != null) {
            result.put(
                    "cameraPosition",
                    List.of(current.position.x, current.position.y, current.position.z));
            result.put("cameraMatrix", current.matrix.get(new float[16]));
            result.put("target", List.of(current.width, current.height, current.depth));
        }
        return result;
    }

    void submit(Section section, Frame frame, Revision attempt) {
        synchronized (meshLock) {
            if (!valid(section, attempt) || loaded(section, attempt.hash)) return;
            if (!ownMeshRequired(section)) {
                coveredMeshSkips++;
                return;
            }
            if (revision != models) {
                modelCache.clear();
                biomeCache.clear();
                revision = models;
            }
            long generation = attempt.models, start = System.nanoTime();
            Snapshot world = new Snapshot(frame);
            List<Layer> layers =
                    mesh(world, () -> valid(section, attempt) && ownMeshRequired(section));
            meshNanos += System.nanoTime() - start;
            meshCount++;
            glfwMakeContextCurrent(uploadWindow);
            Map<RenderType, GpuLayer> gpu = new LinkedHashMap<>();
            try {
                if (uploadCaps == null) uploadCaps = org.lwjgl.opengl.GL.createCapabilities();
                else org.lwjgl.opengl.GL.setCapabilities(uploadCaps);
                synchronized (section) {
                    if (!valid(section, attempt) || !ownMeshRequired(section)) return;
                    section.incomplete = world.incomplete;
                }
                long bytes = 0;
                for (Layer layer : layers) {
                    MeshData.SortState sort =
                            transparent(layer.type)
                                    ? layer.mesh.sortQuads(
                                            scratch.indices, VertexSorting.DISTANCE_TO_ORIGIN)
                                    : null;
                    gpu.put(layer.type, new GpuLayer(layer.mesh.drawState(), sort));
                    bytes +=
                            layer.mesh.vertexBuffer().remaining()
                                    + (layer.mesh.indexBuffer() == null
                                            ? 0
                                            : layer.mesh.indexBuffer().remaining());
                }
                section.requiredBytes = bytes;
                if (freeGpuMemory() < bytes) {
                    CompletableFuture<Long> space = new CompletableFuture<>();
                    eviction = space;
                    if (closed) space.complete(0L);
                    RenderSystem.recordRenderCall(
                            () -> {
                                try {
                                    long fence = 0;
                                    if (makeRoom(section, attempt)) {
                                        fence = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
                                        if (fence == 0)
                                            throw new IllegalStateException(
                                                    "Terrain reclamation fence failed: "
                                                            + glGetError());
                                    }
                                    glFlush();
                                    if (!space.complete(fence) && fence != 0) glDeleteSync(fence);
                                } catch (Throwable failure) {
                                    space.completeExceptionally(failure);
                                }
                            });
                    long retired = space.join();
                    eviction = null;
                    if (retired == 0) return;
                    try {
                        int result = glClientWaitSync(retired, 0, Long.MAX_VALUE);
                        if (result != GL_ALREADY_SIGNALED && result != GL_CONDITION_SATISFIED)
                            throw new IllegalStateException(
                                    "Terrain reclamation failed: " + result);
                    } finally {
                        glDeleteSync(retired);
                    }
                }
                if (!valid(section, attempt) || !ownMeshRequired(section)) return;
                for (Layer layer : layers) gpu.get(layer.type).upload(layer.mesh);
                AABB measured = scratch.bounds.world(frame.key());
                boolean boundsKnown = measured != null && !world.incomplete;
                for (Entity entity : world.coreEntities) {
                    if (entity.scope == null) boundsKnown = false;
                    else measured = measured == null ? entity.scope : measured.minmax(entity.scope);
                }
                Geometry geometry =
                        new Geometry(
                                frame.key(),
                                attempt.hash,
                                frame.children(),
                                gpu,
                                gpu.entrySet().stream()
                                        .filter(entry -> transparent(entry.getKey()))
                                        .map(entry -> new Draw(entry.getKey(), entry.getValue()))
                                        .toArray(Draw[]::new),
                                world.coreEntities,
                                scratch.sprites.toArray(TextureAtlasSprite[]::new),
                                measured,
                                boundsKnown,
                                bytes,
                                generation);
                long fence = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
                glFlush();
                if (fence == 0)
                    throw new IllegalStateException("Terrain upload fence failed: " + glGetError());
                synchronized (section) {
                    if (!valid(section, attempt) || !ownMeshRequired(section)) {
                        geometry.close();
                        glDeleteSync(fence);
                        return;
                    }
                    if (section.pending != null) release(section);
                    section.pending = new Pending(geometry, fence);
                    pending.put(frame.key(), section);
                    section.children = frame.children();
                    section.deniedInput = null;
                    section.failure = null;
                    gpuBytes.addAndGet(bytes);
                    uploads++;
                    changed(section);
                }
                freeGpu = freeGpuMemory();
            } catch (Throwable failure) {
                gpu.values().forEach(GpuLayer::close);
                failures++;
                throw failure;
            } finally {
                layers.forEach(layer -> layer.mesh.close());
                glBindBuffer(GL_ARRAY_BUFFER, 0);
                glfwMakeContextCurrent(0);
                org.lwjgl.opengl.GL.setCapabilities(null);
            }
        }
    }

    void cancelPreparation() {
        CompletableFuture<Void> waiting = preparation;
        if (waiting != null) {
            waiting.completeExceptionally(
                    new CancellationException("Terrain preparation cancelled"));
        }
    }

    private void prepare(Snapshot world, java.util.function.BooleanSupplier valid) {
        if (world.frame.entities().isEmpty()) {
            return;
        }
        CompletableFuture<Void> ready = new CompletableFuture<>();
        preparation = ready;
        if (closed || !valid.getAsBoolean()) {
            ready.completeExceptionally(new CancellationException("Obsolete terrain preparation"));
        }
        client.execute(
                () -> {
                    if (ready.isDone()) {
                        return;
                    }
                    try {
                        if (closed
                                || client.level != world.level
                                || models != world.modelGeneration
                                || !valid.getAsBoolean()) {
                            throw new CancellationException("Obsolete terrain preparation");
                        }
                        world.prepareEntities(valid);
                        ready.complete(null);
                    } catch (Throwable failure) {
                        ready.completeExceptionally(failure);
                    }
                });
        try {
            ready.join();
        } finally {
            if (preparation == ready) {
                preparation = null;
            }
        }
    }

    private List<Layer> mesh(Snapshot world, java.util.function.BooleanSupplier valid) {
        prepare(world, valid);
        scratch.begin();
        Frame frame = world.frame;
        Key key = frame.key();
        int scale = 1 << key.level();
        var dispatcher = client.getBlockRenderer();
        // Coarse anchored neighborhoods can resolve one actual query position to different cells.
        // Minecraft's position-only light cache is valid only for the unscaled view.
        net.minecraft.client.renderer.block.ModelBlockRenderer.clearCache();
        if (scale == 1) {
            net.minecraft.client.renderer.block.ModelBlockRenderer.enableCaching();
        }
        meshing = world;
        try {
            for (int y = 0; y < 32 && valid.getAsBoolean(); y++) {
                for (int z = 0; z < 32; z++) {
                    for (int x = 0; x < 32; x++) {
                        int index = frame.at(x, y, z);
                        Model model = world.palette[index];
                        BlockState state = model.state;
                        if (state.isAir()) {
                            continue;
                        }
                        scratch.actual.set(
                                (key.x() * 32 + x) * scale,
                                (key.y() * 32 + y) * scale,
                                (key.z() * 32 + z) * scale);
                        PreparedEntity entity =
                                state.hasBlockEntity()
                                        ? world.cellEntities.get(Snapshot.cell(x, y, z))
                                        : null;
                        if (state.hasBlockEntity()
                                && (entity == null || entity.block.getBlockState() != state)) {
                            entity = null;
                            world.incomplete = true;
                        }
                        scratch.pos.set(
                                entity == null ? scratch.actual : entity.block.getBlockPos());
                        world.anchor(x, y, z, scratch.pos);
                        boolean boundary =
                                x == 0 || x == 31 || y == 0 || y == 31 || z == 0 || z == 31;
                        boolean enclosed = model.enclosedSafe && !boundary;
                        for (Direction side : SIDES) {
                            if (!enclosed) {
                                break;
                            }
                            scratch.neighbor.set(scratch.pos).move(side);
                            if (!world.getBlockState(scratch.neighbor)
                                    .isSolidRender(world, scratch.neighbor)) {
                                enclosed = false;
                                break;
                            }
                        }
                        if (enclosed) {
                            continue;
                        }
                        if (state.getRenderShape() == RenderShape.MODEL) {
                            ModelData input = entity == null ? ModelData.EMPTY : entity.data;
                            ModelData data =
                                    model.baked.getModelData(world, scratch.pos, state, input);
                            long seed = state.getSeed(scratch.pos);
                            scratch.random.setSeed(seed);
                            for (RenderType type :
                                    model.baked.getRenderTypes(state, scratch.random, data)) {
                                scratch.pose.setIdentity();
                                scratch.pose.translate(x * scale, y * scale, z * scale);
                                scratch.pose.scale(scale, scale, scale);
                                world.forceBoundary =
                                        boundary && model.enclosedSafe && !transparent(type);
                                dispatcher
                                        .getModelRenderer()
                                        .tesselateBlock(
                                                world,
                                                model.baked,
                                                state,
                                                scratch.pos,
                                                scratch.pose,
                                                scratch.consumer(type, x, y, z),
                                                !model.enclosedSafe,
                                                scratch.random,
                                                seed,
                                                0,
                                                data,
                                                type);
                            }
                            world.forceBoundary = false;
                        }
                        FluidState fluid = state.getFluidState();
                        if (!fluid.isEmpty()) {
                            RenderType type = ItemBlockRenderTypes.getRenderLayer(fluid);
                            scratch.fluid.bind(scratch.buffer(type), type, x, y, z);
                            scratch.fluid.scale = scale;
                            scratch.fluid.offsetX = (x - (scratch.pos.getX() & 15)) * scale;
                            scratch.fluid.offsetY = (y - (scratch.pos.getY() & 15)) * scale;
                            scratch.fluid.offsetZ = (z - (scratch.pos.getZ() & 15)) * scale;
                            dispatcher.renderLiquid(
                                    scratch.pos, world, scratch.fluid, state, fluid);
                        }
                    }
                }
            }
            return scratch.finish();
        } catch (Throwable failure) {
            scratch.discard();
            throw failure;
        } finally {
            world.forceBoundary = false;
            meshing = null;
            net.minecraft.client.renderer.block.ModelBlockRenderer.clearCache();
        }
    }

    private long freeGpuMemory() {
        var caps = org.lwjgl.opengl.GL.getCapabilities();
        if (caps.GL_NVX_gpu_memory_info)
            return Math.max(
                            0L,
                            glGetInteger(
                                    org.lwjgl.opengl.NVXGPUMemoryInfo
                                            .GL_GPU_MEMORY_INFO_CURRENT_AVAILABLE_VIDMEM_NVX))
                    * 1024;
        if (caps.GL_ATI_meminfo)
            try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
                var values = stack.mallocInt(4);
                glGetIntegerv(org.lwjgl.opengl.ATIMeminfo.GL_VBO_FREE_MEMORY_ATI, values);
                return Math.max(0L, values.get(0)) * 1024;
            }
        return Long.MAX_VALUE;
    }

    private boolean makeRoom(Section candidate, Revision attempt) {
        synchronized (candidate) {
            try {
                if (!valid(candidate, attempt) || !ownMeshRequired(candidate)) return false;
                long missing = candidate.requiredBytes - freeGpuMemory();
                if (missing <= 0) return true;
                if (!candidate.needed || reclaimBytes < missing) {
                    deny(candidate, attempt);
                    return false;
                }
                // Redundant parent meshes fund detail before the useful revisit cache.
                for (var cache : List.of(redundant, inactive, prepared.reversed())) {
                    for (Geometry geometry : cache.values()) {
                        Section section = sections.get(geometry.key);
                        if (section == null
                                || section == candidate
                                || section.current != geometry
                                || section.needed && ownMeshRequired(section)
                                || section.covering) continue;
                        reclaimVictims.add(section);
                        missing -= geometry.bytes;
                        if (missing <= 0) break;
                    }
                    if (missing <= 0) break;
                }
                if (missing > 0) {
                    // No victim has been removed: incomplete admission must preserve the revisit
                    // cache.
                    deny(candidate, attempt);
                    return false;
                }
                if (!valid(candidate, attempt) || !ownMeshRequired(candidate)) return false;
                pressureAdmissions++;
                for (Section section : reclaimVictims) {
                    Geometry victim = section.current;
                    reclaimedBytes += victim.bytes;
                    if (section.coveredByChildren) {
                        redundantEvictions++;
                        redundantEvictedBytes += victim.bytes;
                    } else {
                        section.deniedInput =
                                new Revision(victim.hash, victim.models, section.purpose);
                        section.checked = false;
                    }
                    removeCurrent(section);
                    cache(section);
                    evictions++;
                    changed(section);
                    if (!section.active) discard(section);
                }
                return true;
            } finally {
                reclaimVictims.clear();
            }
        }
    }

    private void cache(Section section) {
        cache(section, section.current);
    }

    private void cache(Section section, Geometry geometry) {
        Geometry oldInactive = inactive.remove(section.key);
        Geometry oldPrepared = prepared.remove(section.key);
        Geometry oldRedundant = redundant.remove(section.key);
        if (oldInactive != null) inactiveBytes -= oldInactive.bytes;
        if (oldPrepared != null) preparedBytes -= oldPrepared.bytes;
        if (oldRedundant != null) redundantBytes -= oldRedundant.bytes;
        if (geometry != null
                && geometry.bytes > 0
                && !section.covering
                && (!section.needed || section.coveredByChildren)) {
            if (!section.active) {
                inactive.put(section.key, geometry);
                inactiveBytes += geometry.bytes;
            } else if (section.needed) {
                redundant.put(section.key, geometry);
                redundantBytes += geometry.bytes;
            } else {
                prepared.put(section.key, geometry);
                preparedBytes += geometry.bytes;
            }
        }
        reclaimBytes = inactiveBytes + preparedBytes + redundantBytes;
        Revision denial = section.deniedInput;
        if (section.active
                && ownMeshRequired(section)
                && denial != null
                && denial.models == models
                && Arrays.equals(denial.hash, section.cacheHash)
                && !loaded(section, section.cacheHash)) {
            if (capacity(section)) {
                unpark(section);
                enqueue(section);
            } else park(section);
        } else unpark(section);
    }

    private void removeCurrent(Section section) {
        Geometry geometry = section.current;
        if (geometry == null) return;
        cache(section, null);
        geometry.close();
        gpuBytes.addAndGet(-geometry.bytes);
        currentGpuBytes -= geometry.bytes;
        section.current = null;
        updateEnvelopes(section);
    }

    private void deny(Section section, Revision attempt) {
        pressureDenials++;
        section.deniedInput = attempt;
        section.checked = false;
        park(section);
    }

    private void park(Section section) {
        if (!section.active || !ownMeshRequired(section)) {
            unpark(section);
            return;
        }
        if (section.parked == section.requiredBytes && section.parkedNeeded == section.needed)
            return;
        unpark(section);
        section.parked = section.requiredBytes;
        section.parkedNeeded = section.needed;
        deniedCount++;
        deniedBytes += section.parked;
        if (section.parked > 0) positiveDenials++;
        if (section.parkedNeeded) neededDeniedBytes += section.parked;
        var queue = section.parkedNeeded ? neededDenied : preparedDenied;
        queue.computeIfAbsent(section.parked, ignored -> new HashSet<>()).add(section);
    }

    private void unpark(Section section) {
        if (section.parked < 0) return;
        deniedCount--;
        deniedBytes -= section.parked;
        if (section.parked > 0) positiveDenials--;
        if (section.parkedNeeded) neededDeniedBytes -= section.parked;
        var queue = section.parkedNeeded ? neededDenied : preparedDenied;
        Set<Section> waiting = queue.get(section.parked);
        if (waiting != null) {
            waiting.remove(section);
            if (waiting.isEmpty()) queue.remove(section.parked);
        }
        section.parked = -1;
    }

    private void wakeDenied() {
        long neededBytes =
                freeGpu > Long.MAX_VALUE - reclaimBytes ? Long.MAX_VALUE : freeGpu + reclaimBytes;
        wakeDenied(neededDenied, neededBytes);
        wakeDenied(preparedDenied, freeGpu);
    }

    private void wakeDenied(NavigableMap<Long, Set<Section>> queue, long available) {
        while (!queue.isEmpty() && queue.firstKey() <= available) {
            for (Section section : queue.pollFirstEntry().getValue()) {
                unpark(section);
                section.checked = false;
                enqueue(section);
            }
        }
    }

    private void clearDenials() {
        neededDenied.clear();
        preparedDenied.clear();
        deniedCount = deniedBytes = neededDeniedBytes = positiveDenials = 0;
    }

    private void release(Section section) {
        Pending upload = section.pending;
        glDeleteSync(upload.fence);
        upload.geometry.close();
        gpuBytes.addAndGet(-upload.geometry.bytes);
        section.pending = null;
        pending.remove(upload.geometry.key, section);
        changed(section);
    }

    private void publish() {
        for (Section section : pending.values())
            synchronized (section) {
                Pending ready = section.pending;
                if (ready == null) continue;
                int result = glClientWaitSync(ready.fence, 0, 0);
                if (result == GL_TIMEOUT_EXPIRED) continue;
                if (result != GL_WAIT_FAILED
                        && section.current != null
                        && !mayReplace(section, ready.geometry)) continue;
                glDeleteSync(ready.fence);
                section.pending = null;
                pending.remove(ready.geometry.key, section);
                changed(section);
                if (result == GL_WAIT_FAILED || ready.geometry.models != models) {
                    ready.geometry.close();
                    gpuBytes.addAndGet(-ready.geometry.bytes);
                    section.checked = false;
                    if (result == GL_WAIT_FAILED)
                        failed(
                                section,
                                new Revision(
                                        ready.geometry.hash,
                                        ready.geometry.models,
                                        section.purpose));
                    failures++;
                    continue;
                }
                try {
                    ready.geometry.layers.values().forEach(GpuLayer::adopt);
                } catch (RuntimeException failure) {
                    ready.geometry.close();
                    gpuBytes.addAndGet(-ready.geometry.bytes);
                    failed(
                            section,
                            new Revision(
                                    ready.geometry.hash, ready.geometry.models, section.purpose));
                    failures++;
                    System.err.println("Voxy GPU publication: " + failure);
                    continue;
                }
                removeCurrent(section);
                section.current = ready.geometry;
                currentGpuBytes += ready.geometry.bytes;
                section.publishedChildren = ready.geometry.children;
                unpark(section);
                cache(section);
                changed(section);
            }
    }

    private boolean mayReplace(Section section, Geometry next) {
        Geometry previous = section.current;
        if (section.branch == null) return true;
        for (int i = 0; i < 8; i++) {
            Section child = section.branch[i];
            if (child != null
                    && child.needed
                    && (next.children & (1 << i)) != 0
                    && !child.complete
                    && (previous.children & (1 << i)) == 0) return false;
        }
        return true;
    }

    private void updateDemand(Camera next) {
        Camera old = camera;
        camera = next;
        stableCamera = next.same(old);
        if (!stableCamera) queriesDirty = true;
        retireQueries(next);
        float pixels = Float.isNaN(pixelOverride) ? ClientSettings.pixels : pixelOverride;
        long depth = depthScene();
        if (depth != demandDepth)
            for (Section section : hidden) if (section.occluded) changed(section);
        demandDepth = depth;
        if (rebuild
                || !stableCamera
                || ClientSettings.distance != demandDistance
                || Float.compare(pixels, demandPixels) != 0) {
            rebuild = false;
            view++;
            demandDistance = ClientSettings.distance;
            demandPixels = pixels;
            Collection<Section> previous = new ArrayList<>(demand.values());
            demand.clear();
            clearSelected();
            rejected = 0;
            for (Section section : previous) {
                section.active = false;
                section.needed = false;
                section.coveredByChildren = false;
                section.shown = false;
                section.drawn = 0;
                section.covering = false;
                section.occluded = false;
                cache(section);
                enqueue(section);
            }
            List<Key> visibleRoots = new ArrayList<>();
            Vec3 pos = next.position;
            int radius = ClientSettings.distance;
            for (int x = Math.floorDiv((int) Math.floor(pos.x - radius), 512);
                    x <= Math.floorDiv((int) Math.floor(pos.x + radius), 512);
                    x++)
                for (int z = Math.floorDiv((int) Math.floor(pos.z - radius), 512);
                        z <= Math.floorDiv((int) Math.floor(pos.z + radius), 512);
                        z++)
                    for (int y = Math.floorDiv(client.level.getMinBuildHeight(), 512);
                            y <= Math.floorDiv(client.level.getMaxBuildHeight() - 1, 512);
                            y++) {
                        Section root = sections.computeIfAbsent(new Key(4, x, y, z), Section::new);
                        interest(root, true, next);
                        if (root.active) {
                            visibleRoots.add(root.key);
                            refresh(root);
                        }
                    }
            roots = List.copyOf(visibleRoots);
            wakeup();
            for (Section section : previous) if (!section.active) discard(section);
        }
        Section section;
        while ((section = changes.poll()) != null) {
            synchronized (section) {
                section.dirty = false;
            }
            if (sections.get(section.key) != section) continue;
            updateEnvelopes(section);
            boolean wasActive = section.active;
            boolean root = section.key.level() == 4 && section.parent == null;
            if (section.active || root && section.retainedEnvelope) {
                interest(section, root || section.needed, next);
                refresh(section);
            }
            if (root && section.active != wasActive) {
                Set<Key> currentRoots = new LinkedHashSet<>(roots);
                if (section.active) currentRoots.add(section.key);
                else currentRoots.remove(section.key);
                roots = List.copyOf(currentRoots);
                wakeup();
            }
        }
    }

    private void discard(Section section) {
        updateEnvelopes(section);
        if (section.current == null
                && section.pending == null
                && section.fallback == null
                && !section.retainedEnvelope) {
            sections.remove(section.key, section);
            section.publishedChildren = -1;
            forgetQuery(section);
            unpark(section);
            section.local = null;
            if (section.parent != null && section.parent.branch != null) {
                int i =
                        (section.key.x() & 1)
                                | (section.key.z() & 1) << 1
                                | (section.key.y() & 1) << 2;
                if (section.parent.branch[i] == section) {
                    section.parent.branch[i] = null;
                    updateEnvelopes(section.parent);
                }
            }
        }
    }

    private void deactivate(Section section) {
        if (!section.active) return;
        section.active = false;
        section.needed = false;
        section.coveredByChildren = false;
        section.purpose++;
        requestChanged();
        demand.remove(section.rank);
        enqueue(section);
        show(section, false);
        cache(section);
        if (section.branch != null)
            for (Section child : section.branch) if (child != null) deactivate(child);
        discard(section);
    }

    private void interest(Section section, boolean needed, Camera next) {
        Key key = section.key;
        if (!eligible(key, next)) {
            deactivate(section);
            return;
        }
        boolean entering = !section.active, promoted = section.needed != needed;
        if (section.view != view || section.rankedEnvelopeRevision != section.envelopeRevision) {
            if (section.rank != null) demand.remove(section.rank);
            // These trees compare mutable distance: remove the owner before changing it.
            Geometry drawn = selected.remove(section);
            Geometry translucent = transparentOwners.remove(section);
            Geometry entities = entityOwners.remove(section);
            section.view = view;
            section.rankedEnvelopeRevision = section.envelopeRevision;
            // Unknown BER scope preserves its coarse owner. An offscreen measured envelope does
            // not justify refining every descendant merely because projection crosses the camera.
            section.diameter =
                    section.envelopeUnknown && !next.visible(section.envelope)
                            ? 0
                            : next.project(section.envelope);
            section.distance = next.distance(section.envelope);
            section.rank = new Rank(key.level(), section.diameter, key.x(), key.y(), key.z());
            if (drawn != null) selected.put(section, drawn);
            if (translucent != null) transparentOwners.put(section, translucent);
            if (entities != null) entityOwners.put(section, entities);
        }
        section.active = true;
        section.needed = needed;
        demand.put(section.rank, section);
        cache(section);
        if (entering || promoted) {
            queriesDirty = true;
            section.purpose++;
            section.checked = false;
            requestChanged();
            enqueue(section);
        }
        boolean occluded =
                needed && section.hidden && !section.querying && validQuery(section, next);
        if (section.occluded != occluded) {
            rejected += occluded ? 1 : -1;
            section.occluded = occluded;
            requestChanged();
            workChanged(section);
        }
        float pixels = Float.isNaN(pixelOverride) ? ClientSettings.pixels : pixelOverride;
        section.detailWanted = section.diameter >= pixels * (section.refined ? .9 : 1);
        boolean refine = needed && (section.current == null || section.detailWanted);
        int children = coverageChildren(section);
        if (!entering
                && !promoted
                && section.refined == refine
                && section.interestChildren == children) return;
        section.refined = refine;
        section.interestChildren = children;
        if (key.level() == 0) return;
        if (section.branch == null) section.branch = new Section[8];
        for (int i = 0; i < 8; i++) {
            if (needed && (children & (1 << i)) != 0) {
                Section child = section.branch[i];
                if (child == null) {
                    child = sections.computeIfAbsent(key.child(i), Section::new);
                    section.branch[i] = child;
                    child.parent = section;
                }
                interest(child, refine, next);
                refresh(child);
            } else if (section.branch[i] != null) deactivate(section.branch[i]);
        }
    }

    private boolean eligible(Key key, Camera view) {
        long low = (long) key.y() * key.size();
        Section section = sections.get(key);
        AABB envelope = section == null ? keyBounds(key) : section.envelope;
        // Unseen sources retain spatial-cube admission; no finite bound can be invented for an
        // unknown custom model outside the existing source-root search. Resident unknown scopes
        // bypass frustum rejection, but measured size still controls refinement.
        return (section != null && section.envelopeUnknown || view.visible(envelope))
                && low < client.level.getMaxBuildHeight()
                && low + key.size() > client.level.getMinBuildHeight()
                && view.horizontalDistance(envelope)
                        <= (double) ClientSettings.distance * ClientSettings.distance;
    }

    private static AABB keyBounds(Key key) {
        int size = key.size();
        long x = (long) key.x() * size, y = (long) key.y() * size, z = (long) key.z() * size;
        return new AABB(x, y, z, x + size, y + size, z + size);
    }

    /** Retained geometry remains part of admission even when its owner is inactive. */
    private void updateEnvelopes(Section section) {
        for (Section owner = section; owner != null; owner = owner.parent) {
            AABB envelope = keyBounds(owner.key);
            boolean unknown = false, retained = false;
            Geometry geometry = owner.current;
            Pending upload = owner.pending;
            if (geometry != null && geometry.models == models) {
                retained = true;
                unknown = !geometry.boundsKnown;
                if (geometry.bounds != null) envelope = envelope.minmax(geometry.bounds);
            }
            if (upload != null && upload.geometry.models == models) {
                retained = true;
                unknown |= !upload.geometry.boundsKnown;
                if (upload.geometry.bounds != null)
                    envelope = envelope.minmax(upload.geometry.bounds);
            }
            if (owner.branch != null)
                for (Section child : owner.branch) {
                    if (child == null || !child.retainedEnvelope) continue;
                    retained = true;
                    unknown |= child.envelopeUnknown;
                    envelope = envelope.minmax(child.envelope);
                }
            if (Objects.equals(owner.envelope, envelope)
                    && owner.envelopeUnknown == unknown
                    && owner.retainedEnvelope == retained) break;
            owner.envelope = envelope;
            owner.envelopeUnknown = unknown;
            owner.retainedEnvelope = retained;
            owner.envelopeRevision++;
            if (owner != section) changed(owner);
        }
    }

    /**
     * Unknown model/entity bounds cannot justify suspending work for a possibly visible extension.
     */
    private void updateBounds(Section section) {
        AABB bounds = keyBounds(section.key);
        Geometry geometry = section.current;
        boolean known = geometry != null;
        boolean unsafe = section.incomplete || geometry != null && !geometry.boundsKnown;
        if (geometry != null && geometry.bounds != null) bounds = bounds.minmax(geometry.bounds);
        if (section.branch != null)
            for (Section child : section.branch) {
                if (child == null || !child.active || !child.needed && child.current == null)
                    continue;
                known = true;
                if (child.incomplete || child.bounds == null) unsafe = true;
                else bounds = bounds.minmax(child.bounds);
            }
        if (!known || unsafe) bounds = null;
        if (!Objects.equals(section.bounds, bounds)) {
            section.bounds = bounds;
            section.boundsRevision++;
            boundsChanges++;
            queriesDirty = true;
            if (section.occluded) changed(section);
        }
    }

    /** Pressure deletion retains published topology until a replacement is actually ready. */
    private int coverageChildren(Section section) {
        int source = section.children;
        int published = section.publishedChildren;
        return (source < 0 ? 0 : source) | (published < 0 ? 0 : published);
    }

    private void refresh(Section section) {
        Geometry geometry = section.current;
        updateBounds(section);
        boolean finer = false, complete = true;
        int children = coverageChildren(section);
        if (section.key.level() > 0 && section.needed && section.refined)
            for (int i = 0; i < 8; i++) {
                if ((children & (1 << i)) == 0) continue;
                Section child = section.branch == null ? null : section.branch[i];
                boolean missing = child == null || !child.active || !child.needed;
                if (missing && !eligible(child == null ? section.key.child(i) : child.key, camera))
                    continue;
                finer = true;
                if (missing || !child.complete) complete = false;
            }
        section.complete =
                geometry != null
                        || finer
                                && complete
                                && (section.cacheHash != null || section.children == 255);
        boolean covered =
                section.needed && section.detailWanted && finer && complete && section.complete;
        boolean meshDemandChanged = section.coveredByChildren != covered;
        if (meshDemandChanged) {
            section.coveredByChildren = covered;
            // Cancel the preceding purpose before a worker can install a now-redundant mesh.
            section.purpose++;
            section.checked = false;
            requestChanged();
        }
        section.mode =
                !section.active || !section.needed
                        ? 0
                        : geometry != null && (!finer || !complete) ? 1 : 2;
        if (section.parent != null && section.parent.active) refresh(section.parent);
        show(section, section.parent == null || section.parent.shown && section.parent.mode == 2);
        cache(section);
        if (meshDemandChanged) enqueue(section);
    }

    private void show(Section section, boolean shown) {
        int next = shown ? section.mode : 0;
        section.shown = shown;
        if (section.drawn == next && (next != 1 || selected.get(section) == section.current))
            return;
        Geometry previous = selected.get(section);
        Geometry following = next == 1 ? section.current : null;
        if (previous != following && (hasSolid(previous) || hasSolid(following)))
            depthChanged(DepthChange.SELECTED_OPAQUE);
        if (section.drawn == 1) {
            selectedBytes -= previous.bytes;
            selected.remove(section);
            transparentOwners.remove(section);
            entityOwners.remove(section);
            spriteUse(previous, -1);
            for (RenderType type : previous.layers.keySet())
                if (!transparent(type)) {
                    var group = opaque.get(type);
                    group.remove(section);
                    if (group.isEmpty()) opaque.remove(type);
                }
        }
        if (section.drawn == 2 && next != 2 && section.branch != null)
            for (Section child : section.branch) if (child != null) show(child, false);
        section.drawn = next;
        section.covering = next == 1;
        if (next == 1) {
            selectedBytes += section.current.bytes;
            selected.put(section, section.current);
            if (section.current.translucent.length != 0)
                transparentOwners.put(section, section.current);
            if (!section.current.entities.isEmpty()) entityOwners.put(section, section.current);
            spriteUse(section.current, 1);
            section.current.layers.forEach(
                    (type, layer) -> {
                        if (!transparent(type))
                            opaque.computeIfAbsent(type, ignored -> new LinkedHashMap<>())
                                    .put(section, layer);
                    });
        } else if (next == 2 && section.branch != null)
            for (Section child : section.branch)
                if (child != null) show(child, child.active && child.needed);
        cache(section);
    }

    private void spriteUse(Geometry geometry, int change) {
        for (TextureAtlasSprite sprite : geometry.sprites) {
            int count = visibleSprites.addTo(sprite, change) + change;
            if (count == 0) visibleSprites.removeInt(sprite);
        }
    }

    private void clearSelected() {
        if (opaque.containsKey(RenderType.solid())) depthChanged(DepthChange.SELECTION_CLEARED);
        for (Geometry geometry : selected.values()) spriteUse(geometry, -1);
        selected.clear();
        selectedBytes = 0;
        transparentOwners.clear();
        entityOwners.clear();
        visibleSprites.clear();
        opaque.clear();
    }

    /** Native fluid sprite resolution is observed only for this renderer's active mesh. */
    public static void recordFluidSprites(BlockAndTintGetter world, TextureAtlasSprite[] sprites) {
        TerrainRenderer renderer = active;
        if (renderer == null || renderer.meshing != world) return;
        for (TextureAtlasSprite sprite : sprites) renderer.scratch.sprite(sprite);
        renderer.scratch.fluid.sourceSprites = sprites;
    }

    private void render(RenderLevelStageEvent event) {
        if (closed || client.level == null) return;
        var stage = event.getStage();
        boolean opaque = stage == RenderLevelStageEvent.Stage.AFTER_SOLID_BLOCKS;
        boolean transparent = stage == RenderLevelStageEvent.Stage.AFTER_PARTICLES;
        if (!opaque && !transparent && stage != RenderLevelStageEvent.Stage.AFTER_ENTITIES) return;
        if (shader == null) {
            if (transparent) inspect(event);
            return;
        }
        if (opaque) {
            updateShaderPackState();
            if (queryClipping != clipping) {
                queryClipping = clipping;
                depthChanged(DepthChange.CLIPPING);
            }
            if (enabled && ClientSettings.rendering && clipping) nativeCoverage.capture();
            if (gpuRevision != models) {
                for (Section section : sections.values())
                    synchronized (section) {
                        if (section.pending != null && section.pending.geometry.models != models)
                            release(section);
                        if (section.current != null && section.current.models != models) {
                            removeCurrent(section);
                        }
                        section.publishedChildren = -1;
                        section.coveredByChildren = false;
                        if (section.pending == null) {
                            section.checked = false;
                        }
                        cache(section);
                        if (!section.active) discard(section);
                    }
                clearSelected();
                gpuRevision = models;
                depthChanged(DepthChange.RESOURCE_RELOAD);
                rebuild = true;
            }
            freeGpu = freeGpuMemory();
            wakeDenied();
            publish();
            updateCoverage();
            updateDemand(
                    new Camera(
                            event.getCamera().getPosition(),
                            event.getModelViewMatrix(),
                            event.getProjectionMatrix()));
        }
        if (!enabled || !ClientSettings.rendering) {
            if (transparent) inspect(event);
            return;
        }
        if (opaque) {
            for (TextureAtlasSprite sprite : visibleSprites.keySet()) {
                SpriteUtil.INSTANCE.markSpriteActive(sprite);
            }
        }
        if (!opaque && !transparent) {
            renderEntities(event);
            return;
        }
        long start = System.nanoTime();
        int oldCoverage = RenderSystem.getShaderTexture(1),
                oldDepth = RenderSystem.getShaderTexture(3);
        RenderSystem.setShaderTexture(1, coverage.getId());
        shader.getUniform("ClipSize")
                .set(
                        clipping ? clipSide : 0f,
                        clipping ? clipHeight : 0f,
                        clipping ? clipSide : 0f);
        RenderSystem.setShaderTexture(3, nativeCoverage.texture());
        shader.getUniform("OpaquePass").set(opaque ? nativeCoverage.valid() ? 1 : 2 : 0);
        if (opaque && nativeCoverage.valid()) {
            nativeInverse.set(event.getProjectionMatrix()).mul(event.getModelViewMatrix()).invert();
            shader.getUniform("NativeToWorld").set(nativeInverse);
            shader.getUniform("NativeViewport").set(nativeCoverage.viewport());
            Vec3 position = event.getCamera().getPosition();
            shader.getUniform("NativeOrigin")
                    .set(
                            (float) (position.x - clipX * 16L),
                            (float) (position.y - clipY * 16L),
                            (float) (position.z - clipZ * 16L));
        }
        try {
            if (opaque)
                for (var group : this.opaque.entrySet()) {
                    RenderType type = group.getKey();
                    type.setupRenderState();
                    try {
                        RenderSystem.enableDepthTest();
                        RenderSystem.depthFunc(GL_LEQUAL);
                        RenderSystem.setShaderTexture(1, coverage.getId());
                        RenderSystem.setShaderTexture(3, nativeCoverage.texture());
                        shader.setDefaultUniforms(
                                type.mode(),
                                event.getModelViewMatrix(),
                                event.getProjectionMatrix(),
                                client.getWindow());
                        shader.getUniform("AlphaCutoff")
                                .set(
                                        type == RenderType.solid()
                                                ? -1f
                                                : type == RenderType.cutoutMipped() ? .5f : .1f);
                        shader.apply();
                        for (var draw : group.getValue().entrySet())
                            draw(event, draw.getKey().current, draw.getValue());
                    } finally {
                        VertexBuffer.unbind();
                        shader.clear();
                        type.clearRenderState();
                    }
                }
            else drawTransparent(event);
        } finally {
            RenderSystem.setShaderTexture(1, oldCoverage);
            RenderSystem.setShaderTexture(3, oldDepth);
        }
        if (opaque) {
            drawNanos = System.nanoTime() - start;
            queryBounds(event);
        }
        if (transparent) inspect(event);
    }

    /** Preserve far-to-near ordering; only consecutive identical states share a shader binding. */
    private void drawTransparent(RenderLevelStageEvent event) {
        RenderType current = null;
        try {
            for (Geometry geometry : transparentOwners.values()) {
                for (Draw draw : geometry.translucent) {
                    RenderType type = draw.type;
                    if (type != current) {
                        if (current != null) endTransparent(current);
                        current = type;
                        type.setupRenderState();
                        RenderSystem.enableDepthTest();
                        RenderSystem.depthFunc(GL_LEQUAL);
                        RenderSystem.setShaderTexture(1, coverage.getId());
                        RenderSystem.setShaderTexture(3, nativeCoverage.texture());
                        shader.setDefaultUniforms(
                                type.mode(), event.getModelViewMatrix(),
                                event.getProjectionMatrix(), client.getWindow());
                        shader.getUniform("AlphaCutoff").set(0f);
                        shader.apply();
                    }
                    draw(event, geometry, draw.layer);
                }
            }
        } finally {
            if (current != null) endTransparent(current);
        }
    }

    private void endTransparent(RenderType type) {
        VertexBuffer.unbind();
        shader.clear();
        type.clearRenderState();
    }

    /**
     * Screenshot coordinates use the render target's physical pixels, with the origin at top left.
     */
    void inspect(int pixelX, int pixelY, Path output) {
        var target = client.getMainRenderTarget();
        if (pixelX < 0 || pixelY < 0 || pixelX >= target.width || pixelY >= target.height)
            throw new IllegalArgumentException("Inspection pixel outside screenshot dimensions");
        inspection.set(new Inspection(pixelX, pixelY, output));
    }

    /** Runs once after the requested frame's terrain drawing, including when Voxy is disabled. */
    private void inspect(RenderLevelStageEvent event) {
        Inspection request = inspection.getAndSet(null);
        if (request == null) return;
        Path output = request.output;
        Path temporary = output.resolveSibling(output.getFileName() + ".part");
        try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
            var target = client.getMainRenderTarget();
            if (request.x >= target.width || request.y >= target.height)
                throw new IllegalArgumentException("Render target resized before inspection");
            var viewport = stack.mallocInt(4);
            glGetIntegerv(GL_VIEWPORT, viewport);
            float screenX = request.x + .5f;
            float screenY = target.height - request.y - .5f;
            if (viewport.get(2) <= 0
                    || viewport.get(3) <= 0
                    || screenX < viewport.get(0)
                    || screenY < viewport.get(1)
                    || screenX >= viewport.get(0) + viewport.get(2)
                    || screenY >= viewport.get(1) + viewport.get(3))
                throw new IllegalArgumentException("Inspection pixel outside active viewport");
            float x = (screenX - viewport.get(0)) / viewport.get(2) * 2 - 1;
            float y = (screenY - viewport.get(1)) / viewport.get(3) * 2 - 1;
            Matrix4f matrix =
                    new Matrix4f(event.getProjectionMatrix()).mul(event.getModelViewMatrix());
            Matrix4f inverse = new Matrix4f(matrix).invert();
            Vec3 camera = event.getCamera().getPosition();
            Vec3 start = unproject(inverse, x, y, -1).add(camera);
            Vec3 end = unproject(inverse, x, y, 1).add(camera);
            List<InspectionHit> hits = new ArrayList<>();
            for (var entry : selected.entrySet()) {
                Geometry geometry = entry.getValue();
                Key key = geometry.key;
                int size = key.size();
                double lowX = (long) key.x() * size;
                double lowY = (long) key.y() * size;
                double lowZ = (long) key.z() * size;
                AABB bounds = new AABB(lowX, lowY, lowZ, lowX + size, lowY + size, lowZ + size);
                Vec3 hit = bounds.contains(start) ? start : bounds.clip(start, end).orElse(null);
                if (hit != null)
                    hits.add(new InspectionHit(entry.getKey(), geometry, start.distanceTo(hit)));
            }
            hits.sort(Comparator.comparingDouble(InspectionHit::distance));
            Files.createDirectories(output.toAbsolutePath().getParent());
            try (JsonWriter json = new JsonWriter(Files.newBufferedWriter(temporary))) {
                json.beginObject();
                json.name("timeMillis").value(System.currentTimeMillis());
                json.name("stage").value("AFTER_PARTICLES");
                json.name("targetWidth").value(target.width);
                json.name("targetHeight").value(target.height);
                json.name("pixel").beginArray().value(request.x).value(request.y).endArray();
                json.name("viewport").beginArray();
                for (int i = 0; i < 4; i++) json.value(viewport.get(i));
                json.endArray();
                json.name("enabled").value(enabled);
                json.name("rendering").value(ClientSettings.rendering);
                json.name("clipping").value(clipping);
                json.name("shaderReady").value(shader != null);
                json.name("modelGeneration").value(models);
                json.name("selectedCount").value(selected.size());
                json.name("camera");
                writeVector(json, camera);
                json.name("rayStart");
                writeVector(json, start);
                json.name("rayEnd");
                writeVector(json, end);
                json.name("projectionViewMatrix").beginArray();
                for (float value : matrix.get(new float[16])) json.value(value);
                json.endArray();
                json.name("hits").beginArray();
                for (InspectionHit hit : hits) {
                    json.beginObject();
                    json.name("distance").value(hit.distance);
                    writeOwner(json, hit.owner, hit.geometry);
                    json.name("parents").beginArray();
                    for (Section parent = hit.owner.parent;
                            parent != null;
                            parent = parent.parent) {
                        json.beginObject();
                        writeOwner(json, parent, parent.current);
                        json.endObject();
                    }
                    json.endArray().endObject();
                }
                json.endArray().endObject();
            }
            try {
                Files.move(
                        temporary,
                        output,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING);
            }
            System.out.println(
                    "Voxy inspection: " + hits.size() + " intersecting sections -> " + output);
        } catch (IOException | RuntimeException failure) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException cleanup) {
                failure.addSuppressed(cleanup);
            }
            System.err.println("Voxy inspection failed: " + failure);
        }
    }

    private static Vec3 unproject(Matrix4f inverse, float x, float y, float z) {
        Vector4f point = inverse.transform(new Vector4f(x, y, z, 1));
        if (!Float.isFinite(point.w) || point.w == 0)
            throw new IllegalArgumentException("Inspection projection has no finite ray");
        Vec3 value = new Vec3(point.x / point.w, point.y / point.w, point.z / point.w);
        if (!Double.isFinite(value.x) || !Double.isFinite(value.y) || !Double.isFinite(value.z))
            throw new IllegalArgumentException("Inspection projection produced an invalid point");
        return value;
    }

    private static void writeVector(JsonWriter json, Vec3 value) throws IOException {
        json.beginArray().value(value.x).value(value.y).value(value.z).endArray();
    }

    private void writeOwner(JsonWriter json, Section owner, Geometry geometry) throws IOException {
        Key key = owner.key;
        json.name("key")
                .beginArray()
                .value(key.level())
                .value(key.x())
                .value(key.y())
                .value(key.z())
                .endArray();
        json.name("geometryHash")
                .value(geometry == null ? null : HexFormat.of().formatHex(geometry.hash));
        byte[] cached = owner.cacheHash;
        json.name("cacheHash").value(cached == null ? null : HexFormat.of().formatHex(cached));
        json.name("geometryChildren").value(geometry == null ? -1 : geometry.children);
        json.name("sourceChildren").value(owner.children);
        json.name("publishedChildren").value(owner.publishedChildren);
        json.name("coverageChildren").value(coverageChildren(owner));
        json.name("coveredByChildren").value(owner.coveredByChildren);
        json.name("needed").value(owner.needed);
        json.name("active").value(owner.active);
        json.name("complete").value(owner.complete);
        json.name("covering").value(owner.covering);
        json.name("refined").value(owner.refined);
        json.name("occluded").value(owner.occluded);
        json.name("mode").value(owner.mode);
        json.name("drawn").value(owner.drawn);
    }

    private void draw(RenderLevelStageEvent event, Geometry geometry, GpuLayer layer) {
        Vec3 pos = event.getCamera().getPosition();
        Key key = geometry.key;
        int size = key.size();
        double x = (long) key.x() * size, y = (long) key.y() * size, z = (long) key.z() * size;
        transform
                .set(event.getModelViewMatrix())
                .translate((float) (x - pos.x), (float) (y - pos.y), (float) (z - pos.z));
        var offset = shader.getUniform("SectionOffset");
        offset.set((float) (x - clipX * 16L), (float) (y - clipY * 16L), (float) (z - clipZ * 16L));
        var lodScale = shader.getUniform("LodScale");
        lodScale.set((float) (1 << key.level()));
        layer.buffer.bind();
        double sx = pos.x - x, sy = pos.y - y, sz = pos.z - z;
        if (layer.sort != null
                && (Double.isNaN(layer.x)
                        || (sx - layer.x) * (sx - layer.x)
                                        + (sy - layer.y) * (sy - layer.y)
                                        + (sz - layer.z) * (sz - layer.z)
                                > 1)) {
            layer.buffer.uploadIndexBuffer(
                    layer.sort.buildSortedIndexBuffer(
                            indices, VertexSorting.byDistance((float) sx, (float) sy, (float) sz)));
            layer.x = sx;
            layer.y = sy;
            layer.z = sz;
        }
        if (shader.MODEL_VIEW_MATRIX != null) {
            shader.MODEL_VIEW_MATRIX.set(transform);
            shader.MODEL_VIEW_MATRIX.upload();
        }
        offset.upload();
        lodScale.upload();
        layer.buffer.draw();
    }

    /** Detached cached entities have a native fallback renderer, but no Flywheel visual owner. */
    public static boolean ownsBlockEntity(LevelAccessor level) {
        return entityWorld.get() == level;
    }

    private void renderEntities(RenderLevelStageEvent event) {
        LevelAccessor preceding = entityWorld.get();
        entityWorld.set(client.level);
        try {
            drawEntities(event);
        } finally {
            if (preceding == null) entityWorld.remove();
            else entityWorld.set(preceding);
        }
    }

    private void drawEntities(RenderLevelStageEvent event) {
        Vec3 pos = event.getCamera().getPosition();
        PoseStack pose = event.getPoseStack();
        var buffers = client.renderBuffers().bufferSource();
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        for (Geometry geometry : entityOwners.values())
            for (int i = 0; i < geometry.entities.size(); i++) {
                Entity item = geometry.entities.get(i);
                BlockEntity entity = item.block;
                BlockPos at = entity.getBlockPos();
                if (clipping && VoxyClient.vanilla(at)) continue;
                AABB scope =
                        item.scope == null
                                ? item.renderer.getRenderBoundingBox(entity)
                                : item.scope;
                if (!event.getFrustum().isVisible(scope)) continue;
                pose.pushPose();
                pose.translate(at.getX() - pos.x, at.getY() - pos.y, at.getZ() - pos.z);
                try {
                    item.renderer.render(
                            entity,
                            partialTick,
                            pose,
                            buffers,
                            item.light,
                            net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY);
                } finally {
                    pose.popPose();
                }
            }
        // Submission precedes native opaque-sheet flushing. Minecraft/Iris owns the shared batch.
    }

    private void updateCoverage() {
        int view = client.options.getEffectiveRenderDistance(),
                side = view * 2 + 3,
                height = client.level.getSectionsCount();
        boolean resized = coverage == null || side != clipSide || height != clipHeight;
        if (resized) {
            if (coverage != null) coverage.close();
            coverage = new DynamicTexture(side, side * height, false);
            coverage.setFilter(false, false);
            clipSide = side;
            clipHeight = height;
        }
        Vec3 pos = client.gameRenderer.getMainCamera().getPosition();
        int x0 = Math.floorDiv((int) Math.floor(pos.x), 16) - view - 1,
                z0 = Math.floorDiv((int) Math.floor(pos.z), 16) - view - 1;
        var manager = VoxyClient.vanillaManager();
        Object lists = manager == null ? null : manager.getRenderLists();
        if (!resized
                && !nativeChanged
                && lists != null
                && lists == vanillaLists
                && x0 == clipX
                && z0 == clipZ) return;
        nativeChanged = false;
        nextNativeOpaque.clear();
        nextNativeOwners.clear();
        boolean moved = resized || x0 != clipX || z0 != clipZ;
        clipX = x0;
        clipY = client.level.getMinSection();
        clipZ = z0;
        vanillaLists = lists;
        org.lwjgl.system.MemoryUtil.memSet(
                ((VoxyMixins.CoveragePixels) (Object) coverage.getPixels()).pixels(),
                0,
                side * (long) side * height * 4);
        if (manager != null) {
            var regions = manager.getRenderLists().iterator(false);
            while (regions.hasNext()) {
                var list = regions.next();
                var region = list.getRegion();
                var indices = list.sectionsWithGeometryIterator(false);
                if (indices == null) continue;
                while (indices.hasNext()) {
                    var section = region.getSection(indices.nextByteAsInt());
                    nextNativeOwners.add(section);
                    if (nativeMesh(section, DefaultTerrainRenderPasses.SOLID))
                        nextNativeOpaque.add(section);
                    int x = section.getChunkX() - clipX,
                            y = section.getChunkY() - clipY,
                            z = section.getChunkZ() - clipZ;
                    if (x >= 0 && x < side && y >= 0 && y < height && z >= 0 && z < side)
                        coverage.getPixels().setPixelRGBA(x, y * side + z, -1);
                }
            }
        } else
            for (int x = 0; x < side; x++)
                for (int z = 0; z < side; z++)
                    for (int y = 0; y < height; y++) {
                        clipPos.set((clipX + x) * 16, (clipY + y) * 16, (clipZ + z) * 16);
                        if (VoxyClient.vanilla(clipPos))
                            coverage.getPixels().setPixelRGBA(x, y * side + z, -1);
                    }
        coverage.upload();
        if (moved
                || !nativeOwners.equals(nextNativeOwners)
                || !nativeOpaque.equals(nextNativeOpaque)) {
            nativeOwners.clear();
            nativeOwners.addAll(nextNativeOwners);
            nativeOpaque.clear();
            nativeOpaque.addAll(nextNativeOpaque);
            depthChanged(DepthChange.NATIVE_MEMBERSHIP);
        }
    }

    private long depthScene() {
        return scene;
    }

    private boolean validQuery(Section section, Camera view) {
        return shaderPackKnown
                && !shaderPackInUse
                && view != null
                && !section.incomplete
                && section.bounds != null
                && section.queryScene == depthScene()
                && section.queryBoundsRevision == section.boundsRevision
                && view.same(section.queryCamera);
    }

    private void depthChanged(DepthChange reason) {
        depthChanges[reason.ordinal()]++;
        scene++;
        queriesDirty = true;
    }

    private static boolean nativeMesh(RenderSection section, TerrainRenderPass pass) {
        var storage = section.getRegion().getStorage(pass);
        return storage != null
                && SectionRenderDataUnsafe.getSliceMask(
                                storage.getDataPointer(section.getSectionIndex()))
                        != 0;
    }

    /**
     * Sodium calls this after accepted mesh publication and after removing a section's geometry.
     */
    public static void nativeGeometryChanged(RenderSection section) {
        TerrainRenderer renderer = active;
        if (renderer == null || renderer.closed) return;
        renderer.nativeChanged = true;
        if (renderer.nativeOpaque.contains(section)
                || nativeMesh(section, DefaultTerrainRenderPasses.SOLID))
            renderer.depthChanged(DepthChange.NATIVE_PUBLICATION);
    }

    private void forgetQuery(Section section) {
        queries.remove(section);
        hidden.remove(section);
        if (section.query != 0) glDeleteQueries(section.query);
    }

    private void retireQueries(Camera view) {
        if (queryFence == 0) return;
        int ready = glClientWaitSync(queryFence, 0, 0);
        if (ready == GL_TIMEOUT_EXPIRED) {
            pendingFenceFrames++;
            return;
        }
        if (ready != GL_ALREADY_SIGNALED && ready != GL_CONDITION_SATISFIED)
            throw new IllegalStateException("Terrain depth fence failed: " + ready);
        glDeleteSync(queryFence);
        queryFence = 0;
        long epoch = depthScene();
        boolean shaderAllowed = shaderPackKnown && !shaderPackInUse;
        for (var iterator = queries.iterator(); iterator.hasNext(); ) {
            Section section = iterator.next();
            boolean valid = validQuery(section, view);
            boolean zero = glGetQueryObjecti(section.query, GL_QUERY_RESULT) == 0;
            if (zero) rawZero++;
            else rawNonzero++;
            if (!valid) countDiscard(section, view, epoch, shaderAllowed);
            else if (zero) acceptedZero++;
            else acceptedNonzero++;
            section.hidden = zero && valid;
            section.querying = false;
            queryCount++;
            iterator.remove();
            if (section.hidden) hidden.add(section);
            else hidden.remove(section);
            if (section.occluded || section.hidden) changed(section);
            if (!valid) queriesDirty = true;
        }
    }

    private void countDiscard(Section section, Camera view, long epoch, boolean shaderAllowed) {
        int mask = 0;
        if (section.queryScene != epoch) mask |= 1 << QueryDiscard.SCENE.ordinal();
        Camera submitted = section.queryCamera;
        if (view != null && submitted != null) {
            if (!view.position.equals(submitted.position))
                mask |= 1 << QueryDiscard.CAMERA_POSITION.ordinal();
            if (!view.matrix.equals(submitted.matrix))
                mask |= 1 << QueryDiscard.CAMERA_MATRIX.ordinal();
            if (view.width != submitted.width
                    || view.height != submitted.height
                    || view.depth != submitted.depth) mask |= 1 << QueryDiscard.TARGET.ordinal();
        }
        if (section.queryBoundsRevision != section.boundsRevision)
            mask |= 1 << QueryDiscard.BOUNDS_REVISION.ordinal();
        if (view == null || submitted == null || section.incomplete || section.bounds == null)
            mask |= 1 << QueryDiscard.UNKNOWN_BOUNDS.ordinal();
        if (!shaderAllowed) mask |= 1 << QueryDiscard.SHADER_STATE.ordinal();
        // A worker can change quality between validQuery() and this diagnostic snapshot.
        if (mask == 0) mask = 1 << QueryDiscard.UNCLASSIFIED.ordinal();
        for (int reason = 0; reason < queryDiscards.length; reason++)
            if ((mask & (1 << reason)) != 0) queryDiscards[reason]++;
        discardedQueries++;
        lastDiscardKey = section.key;
        lastDiscardMask = mask;
    }

    /** Capture only stable native SOLID, before Sodium adds animated CUTOUT depth. */
    public static void captureSolidDepth(
            ChunkRenderMatrices matrices, double x, double y, double z) {
        TerrainRenderer renderer = active;
        if (renderer == null || renderer.closed) return;
        renderer.queryDepth.invalidate();
        if (!renderer.enabled
                || !ClientSettings.rendering
                || !renderer.shaderPackKnown
                || renderer.shaderPackInUse
                || renderer.queryFence != 0) return;
        Camera current =
                renderer.new Camera(new Vec3(x, y, z), matrices.modelView(), matrices.projection());
        if (!renderer.queriesDirty && current.same(renderer.camera)) return;
        renderer.queryDepth.capture();
        renderer.solidCamera = current;
        renderer.solidScene = renderer.depthScene();
        renderer.solidCaptures++;
    }

    private boolean currentSolidDepth() {
        if (!queryDepth.valid()) solidMiss = "NO_CAPTURE";
        else if (solidScene != depthScene()) solidMiss = "SCENE";
        else if (!camera.same(solidCamera)) solidMiss = "CAMERA";
        else if (glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING) != queryDepth.sourceFramebuffer()
                || glGetFramebufferAttachmentParameteri(
                                GL_DRAW_FRAMEBUFFER,
                                GL_DEPTH_ATTACHMENT,
                                GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME)
                        != queryDepth.sourceDepth()
                || glGetFramebufferAttachmentParameteri(
                                GL_DRAW_FRAMEBUFFER,
                                GL_DEPTH_ATTACHMENT,
                                GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE)
                        != queryDepth.sourceDepthType()) solidMiss = "TARGET";
        else {
            try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
                var viewport = stack.mallocInt(4);
                glGetIntegerv(GL_VIEWPORT, viewport);
                var saved = queryDepth.viewport();
                solidMiss =
                        saved.x == viewport.get(0)
                                        && saved.y == viewport.get(1)
                                        && saved.z == viewport.get(2)
                                        && saved.w == viewport.get(3)
                                ? ""
                                : "VIEWPORT";
            }
        }
        if (solidMiss.isEmpty()) return true;
        solidCaptureMisses++;
        return false;
    }

    private void queryBounds(RenderLevelStageEvent event) {
        if (!shaderPackKnown
                || shaderPackInUse
                || !stableCamera
                || !queriesDirty
                || queryFence != 0
                || !currentSolidDepth()) return;
        queriesDirty = false;
        if (box == null)
            try (ByteBufferBuilder bytes = new ByteBufferBuilder(1024)) {
                BufferBuilder builder =
                        new BufferBuilder(
                                bytes, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
                for (int corner : BOX)
                    builder.addVertex(corner & 1, corner >> 2, (corner >> 1) & 1);
                box = new VertexBuffer(VertexBuffer.Usage.STATIC);
                box.bind();
                box.upload(builder.buildOrThrow());
                VertexBuffer.unbind();
            }
        boolean depth = glGetBoolean(GL_DEPTH_WRITEMASK),
                cull = glIsEnabled(GL_CULL_FACE),
                test = glIsEnabled(GL_DEPTH_TEST),
                stencil = glIsEnabled(GL_STENCIL_TEST),
                blend = glIsEnabled(GL_BLEND);
        int function = glGetInteger(GL_DEPTH_FUNC);
        int srcRgb = glGetInteger(GL_BLEND_SRC_RGB),
                dstRgb = glGetInteger(GL_BLEND_DST_RGB),
                srcAlpha = glGetInteger(GL_BLEND_SRC_ALPHA),
                dstAlpha = glGetInteger(GL_BLEND_DST_ALPHA);
        int program = glGetInteger(GL_CURRENT_PROGRAM),
                vao = glGetInteger(GL_VERTEX_ARRAY_BINDING),
                arrayBuffer = glGetInteger(GL_ARRAY_BUFFER_BINDING),
                activeTexture = glGetInteger(GL_ACTIVE_TEXTURE);
        ShaderInstance oldShader = RenderSystem.getShader();
        int oldAtlas = RenderSystem.getShaderTexture(0),
                oldCoverage = RenderSystem.getShaderTexture(1),
                oldLight = RenderSystem.getShaderTexture(2),
                oldDepth = RenderSystem.getShaderTexture(3);
        try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
            var color = stack.malloc(4);
            glGetBooleanv(GL_COLOR_WRITEMASK, color);
            var textures = stack.mallocInt(4);
            for (int unit = 0; unit < 4; unit++) {
                RenderSystem.activeTexture(GL_TEXTURE0 + unit);
                textures.put(unit, glGetInteger(GL_TEXTURE_BINDING_2D));
            }
            RenderSystem.activeTexture(activeTexture);
            ShaderInstance bounds = GameRenderer.getPositionShader();
            try (var target = queryDepth.bind()) {
                glDisable(GL_STENCIL_TEST);
                var solid = opaque.get(RenderType.solid());
                if (solid != null) {
                    RenderType.solid().setupRenderState();
                    try {
                        RenderSystem.colorMask(false, false, false, false);
                        RenderSystem.depthMask(true);
                        RenderSystem.enableDepthTest();
                        RenderSystem.depthFunc(GL_LEQUAL);
                        RenderSystem.setShaderTexture(1, coverage.getId());
                        shader.setDefaultUniforms(
                                RenderType.solid().mode(),
                                event.getModelViewMatrix(),
                                event.getProjectionMatrix(),
                                client.getWindow());
                        shader.getUniform("OpaquePass").set(0);
                        shader.getUniform("AlphaCutoff").set(-1f);
                        shader.apply();
                        for (var draw : solid.entrySet())
                            draw(event, draw.getKey().current, draw.getValue());
                    } finally {
                        VertexBuffer.unbind();
                        shader.clear();
                        RenderType.solid().clearRenderState();
                    }
                }
                RenderSystem.colorMask(false, false, false, false);
                RenderSystem.depthMask(false);
                RenderSystem.disableCull();
                RenderSystem.enableDepthTest();
                RenderSystem.depthFunc(GL_LEQUAL);
                box.bind();
                bounds.setDefaultUniforms(
                        VertexFormat.Mode.QUADS,
                        transform.identity(),
                        camera.matrix,
                        client.getWindow());
                bounds.apply();
                for (Section section : demand.values()) {
                    Key key = section.key;
                    if (!section.needed
                            || section.bounds == null
                            || section.bounds.contains(camera.position)
                            || !camera.queryable(section.bounds)
                            || !Double.isFinite(section.diameter)
                            || key.level() == 0
                            || coverageChildren(section) <= 0
                            || section.querying
                            || validQuery(section, camera)) continue;
                    if (section.query == 0) section.query = glGenQueries();
                    AABB volume = section.bounds;
                    transform
                            .translation(
                                    (float) (volume.minX - camera.position.x),
                                    (float) (volume.minY - camera.position.y),
                                    (float) (volume.minZ - camera.position.z))
                            .scale(
                                    (float) volume.getXsize(),
                                    (float) volume.getYsize(),
                                    (float) volume.getZsize());
                    if (bounds.MODEL_VIEW_MATRIX != null) {
                        bounds.MODEL_VIEW_MATRIX.set(transform);
                        bounds.MODEL_VIEW_MATRIX.upload();
                    }
                    section.querying = true;
                    section.queryCamera = camera;
                    section.queryScene = depthScene();
                    section.queryBoundsRevision = section.boundsRevision;
                    queries.add(section);
                    glBeginQuery(GL_ANY_SAMPLES_PASSED_CONSERVATIVE, section.query);
                    box.draw();
                    glEndQuery(GL_ANY_SAMPLES_PASSED_CONSERVATIVE);
                    issuedQueries++;
                }
                if (!queries.isEmpty()) {
                    queryFence = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
                    if (queryFence == 0)
                        throw new IllegalStateException(
                                "Terrain depth fence creation failed: " + glGetError());
                    issuedBatches++;
                }
            } finally {
                VertexBuffer.unbind();
                bounds.clear();
                RenderSystem.setShader(() -> oldShader);
                com.mojang.blaze3d.platform.GlStateManager._glUseProgram(program);
                RenderSystem.glBindVertexArray(vao);
                glBindBuffer(GL_ARRAY_BUFFER, arrayBuffer);
                RenderSystem.setShaderTexture(0, oldAtlas);
                RenderSystem.setShaderTexture(1, oldCoverage);
                RenderSystem.setShaderTexture(2, oldLight);
                RenderSystem.setShaderTexture(3, oldDepth);
                for (int unit = 0; unit < 4; unit++) {
                    RenderSystem.activeTexture(GL_TEXTURE0 + unit);
                    RenderSystem.bindTexture(textures.get(unit));
                }
                RenderSystem.activeTexture(activeTexture);
                RenderSystem.blendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha);
                if (blend) RenderSystem.enableBlend();
                else RenderSystem.disableBlend();
                if (stencil) glEnable(GL_STENCIL_TEST);
                else glDisable(GL_STENCIL_TEST);
                RenderSystem.depthMask(depth);
                RenderSystem.depthFunc(function);
                if (cull) RenderSystem.enableCull();
                else RenderSystem.disableCull();
                if (test) RenderSystem.enableDepthTest();
                if (!test) RenderSystem.disableDepthTest();
                RenderSystem.colorMask(
                        color.get(0) != 0, color.get(1) != 0, color.get(2) != 0, color.get(3) != 0);
            }
        }
    }

    private static boolean transparent(RenderType type) {
        return type.sortOnUpload();
    }

    private static boolean hasSolid(Geometry geometry) {
        return geometry != null && geometry.layers.containsKey(RenderType.solid());
    }

    public void close() {
        closed = true;
        if (active == this) active = null;
        cancelPreparation();
        NeoForge.EVENT_BUS.unregister(renderListener);
        NeoForge.EVENT_BUS.unregister(fogListener);
        var waiting = eviction;
        if (waiting != null) waiting.complete(0L);
        Thread.ofVirtual()
                .start(
                        () -> {
                            synchronized (meshLock) {
                                scratch.close();
                            }
                            client.execute(
                                    () -> {
                                        for (Section section : sections.values()) {
                                            if (section.pending != null) release(section);
                                            if (section.current != null) section.current.close();
                                            forgetQuery(section);
                                        }
                                        sections.clear();
                                        pending.clear();
                                        inactive.clear();
                                        redundant.clear();
                                        prepared.clear();
                                        currentGpuBytes = reclaimBytes = 0;
                                        inactiveBytes = preparedBytes = redundantBytes = 0;
                                        clearSelected();
                                        demand.clear();
                                        changes.clear();
                                        hidden.clear();
                                        clearDenials();
                                        indices.close();
                                        if (coverage != null) coverage.close();
                                        nativeCoverage.close();
                                        if (queryFence != 0) glDeleteSync(queryFence);
                                        nativeOpaque.clear();
                                        nextNativeOpaque.clear();
                                        nativeOwners.clear();
                                        nextNativeOwners.clear();
                                        queryDepth.close();
                                        if (box != null) box.close();
                                        glfwDestroyWindow(uploadWindow);
                                    });
                        });
    }

    private final class Snapshot implements BlockAndTintGetter {
        final Frame frame;
        final net.minecraft.client.multiplayer.ClientLevel level;
        final long modelGeneration;
        boolean forceBoundary, incomplete;
        final Model[] palette;
        final Biome[] biomes;
        final it.unimi.dsi.fastutil.ints.Int2ObjectMap<PreparedEntity> cellEntities;
        final List<Entity> coreEntities;
        private int cellX, cellY, cellZ, anchorX, anchorY, anchorZ;

        Snapshot(Frame frame) {
            this.frame = frame;
            level = client.level;
            modelGeneration = models;
            palette = new Model[frame.states().length];
            biomes = new Biome[palette.length];
            for (int i = 0; i < palette.length; i++) {
                palette[i] =
                        modelCache.computeIfAbsent(
                                frame.states()[i],
                                state -> {
                                    BakedModel baked =
                                            client.getBlockRenderer().getBlockModel(state);
                                    boolean safe = enclosedSafe(state, baked);
                                    return new Model(
                                            state, safe ? new CulledModel(baked) : baked, safe);
                                });
                biomes[i] =
                        biomeCache.computeIfAbsent(
                                frame.biomes()[i],
                                name ->
                                        level.registryAccess()
                                                .registryOrThrow(Registries.BIOME)
                                                .get(ResourceLocation.parse(name)));
                if (biomes[i] == null)
                    throw new IllegalArgumentException(
                            "Unknown terrain biome: " + frame.biomes()[i]);
            }
            cellEntities =
                    frame.entities().isEmpty()
                            ? it.unimi.dsi.fastutil.ints.Int2ObjectMaps.emptyMap()
                            : new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<>();
            coreEntities = new ArrayList<>();
        }

        /** Runs only on the client thread; detached entities never enter the live level. */
        void prepareEntities(java.util.function.BooleanSupplier valid) {
            Key key = frame.key();
            int scale = 1 << key.level();
            for (int i = 0; i < frame.entities().size(); i++) {
                if (closed || !valid.getAsBoolean()) {
                    throw new CancellationException("Obsolete terrain entity preparation");
                }
                CompoundTag tag = frame.entities().getCompound(i).copy();
                BlockPos pos = new BlockPos(tag.getInt("x"), tag.getInt("y"), tag.getInt("z"));
                int x = Math.floorDiv(pos.getX(), scale) - key.x() * 32;
                int y = Math.floorDiv(pos.getY(), scale) - key.y() * 32;
                int z = Math.floorDiv(pos.getZ(), scale) - key.z() * 32;
                int index = frame.at(x, y, z);
                if (index < 0) {
                    throw new IllegalArgumentException("Block entity outside terrain halo: " + pos);
                }
                BlockState state =
                        NbtUtils.readBlockState(
                                BuiltInRegistries.BLOCK.asLookup(), tag.getCompound("voxy_state"));
                BlockEntity entity =
                        "DUMMY".equals(tag.getString("id"))
                                ? state.getBlock() instanceof EntityBlock factory
                                        ? factory.newBlockEntity(pos, state)
                                        : null
                                : BlockEntity.loadStatic(pos, state, tag, level.registryAccess());
                if (entity == null) {
                    incomplete = true;
                    continue;
                }
                entity.setLevel(level);
                ModelData input;
                var chunk =
                        level.getChunkSource()
                                .getChunk(
                                        pos.getX() >> 4,
                                        pos.getZ() >> 4,
                                        net.minecraft.world.level.chunk.status.ChunkStatus.FULL,
                                        false);
                BlockEntity live = chunk == null ? null : chunk.getBlockEntities().get(pos);
                if (live != null
                        && live.getType() == entity.getType()
                        && live.getBlockState() == state) {
                    input = live.getModelData();
                } else {
                    input = entity.getModelData();
                }
                if (palette[index].state == state) {
                    int cell = cell(x, y, z);
                    PreparedEntity preceding = cellEntities.get(cell);
                    if (preceding == null
                            || distance(pos, key, x, y, z, scale)
                                    < distance(
                                            preceding.block.getBlockPos(), key, x, y, z, scale)) {
                        cellEntities.put(cell, new PreparedEntity(entity, input));
                    }
                }
                if (x >= 0 && x < 32 && y >= 0 && y < 32 && z >= 0 && z < 32) {
                    int light = frame.light()[index] & 255;
                    var renderer = client.getBlockEntityRenderDispatcher().getRenderer(entity);
                    if (renderer != null)
                        coreEntities.add(
                                new Entity(
                                        entity,
                                        LightTexture.pack(light & 15, light >> 4),
                                        renderer,
                                        defaultEntityScope(renderer, entity)));
                }
            }
        }

        private AABB defaultEntityScope(
                BlockEntityRenderer<BlockEntity> renderer, BlockEntity entity) {
            try {
                if (renderer.getClass()
                                .getMethod("getRenderBoundingBox", BlockEntity.class)
                                .getDeclaringClass()
                        == net.neoforged.neoforge.client.extensions.IBlockEntityRendererExtension
                                .class) return renderer.getRenderBoundingBox(entity);
            } catch (ReflectiveOperationException | SecurityException unavailable) {
                // A custom or inaccessible method has no proven immutable scope.
            }
            return null;
        }

        private static long distance(BlockPos pos, Key key, int x, int y, int z, int scale) {
            return Math.abs((long) pos.getX() - ((long) key.x() * 32 + x) * scale)
                    + Math.abs((long) pos.getY() - ((long) key.y() * 32 + y) * scale)
                    + Math.abs((long) pos.getZ() - ((long) key.z() * 32 + z) * scale);
        }

        private static int cell(int x, int y, int z) {
            return x + 1 + 34 * (z + 1) + 34 * 34 * (y + 1);
        }

        /**
         * Native +/-1 AO and fluid queries address adjacent LOD cells around this source position.
         */
        void anchor(int x, int y, int z, BlockPos pos) {
            cellX = x;
            cellY = y;
            cellZ = z;
            anchorX = pos.getX();
            anchorY = pos.getY();
            anchorZ = pos.getZ();
        }

        private int x(BlockPos pos) {
            return cellX + pos.getX() - anchorX;
        }

        private int y(BlockPos pos) {
            return cellY + pos.getY() - anchorY;
        }

        private int z(BlockPos pos) {
            return cellZ + pos.getZ() - anchorZ;
        }

        private int index(BlockPos pos) {
            return frame.at(x(pos), y(pos), z(pos));
        }

        boolean forceFace(BlockPos neighbor) {
            int x = x(neighbor), y = y(neighbor), z = z(neighbor);
            return forceBoundary && (x < 0 || x > 31 || y < 0 || y > 31 || z < 0 || z > 31);
        }

        public BlockState getBlockState(BlockPos pos) {
            int i = index(pos);
            return i < 0 ? Blocks.AIR.defaultBlockState() : palette[i].state;
        }

        public FluidState getFluidState(BlockPos pos) {
            return getBlockState(pos).getFluidState();
        }

        public BlockEntity getBlockEntity(BlockPos pos) {
            if (index(pos) < 0) {
                return null;
            }
            PreparedEntity entity = cellEntities.get(cell(x(pos), y(pos), z(pos)));
            return entity == null ? null : entity.block;
        }

        public int getHeight() {
            return level.getHeight();
        }

        public int getMinBuildHeight() {
            return level.getMinBuildHeight();
        }

        public float getShade(Direction side, boolean shade) {
            return level.getShade(side, shade);
        }

        public LevelLightEngine getLightEngine() {
            return level.getLightEngine();
        }

        public int getBrightness(LightLayer layer, BlockPos pos) {
            int i = index(pos), light = i < 0 ? 240 : frame.light()[i] & 255;
            return layer == LightLayer.SKY ? light >> 4 : light & 15;
        }

        public int getRawBrightness(BlockPos pos, int subtraction) {
            return Math.max(
                    getBrightness(LightLayer.BLOCK, pos),
                    getBrightness(LightLayer.SKY, pos) - subtraction);
        }

        public int getBlockTint(BlockPos pos, ColorResolver resolver) {
            int i = index(pos);
            return resolver.getColor(biomes[i < 0 ? frame.at(0, 0, 0) : i], pos.getX(), pos.getZ());
        }
    }

    private final class CulledModel extends BakedModelWrapper<BakedModel> {
        CulledModel(BakedModel model) {
            super(model);
        }

        @Override
        public List<BakedQuad> getQuads(
                BlockState state,
                Direction side,
                RandomSource random,
                ModelData data,
                RenderType type) {
            List<BakedQuad> quads = super.getQuads(state, side, random, data, type);
            Snapshot world = meshing;
            if (side == null || quads.isEmpty() || world == null) return quads;
            scratch.neighbor.set(scratch.pos).move(side);
            return world.forceFace(scratch.neighbor)
                            || Block.shouldRenderFace(
                                    state, world, scratch.pos, side, scratch.neighbor)
                    ? quads
                    : List.of();
        }
    }

    private boolean enclosedSafe(BlockState state, BakedModel model) {
        if (state.hasBlockEntity() || state.hasOffsetFunction()) return false;
        if (model.getClass() == WeightedBakedModel.class) {
            for (var entry : ((VoxyMixins.WeightedModels) (Object) model).models())
                if (!unitModel(state, entry.data())) return false;
            return true;
        }
        return unitModel(state, model);
    }

    private boolean unitModel(BlockState state, BakedModel model) {
        if (model.getClass() != SimpleBakedModel.class) return false;
        Direction[] faces = Arrays.copyOf(SIDES, 7);
        for (Direction face : faces)
            for (var quad : model.getQuads(state, face, scratch.random)) {
                int[] vertices = quad.getVertices();
                int stride = vertices.length / 4;
                for (int i = 0; i < 4; i++)
                    for (int axis = 0; axis < 3; axis++) {
                        float value = Float.intBitsToFloat(vertices[i * stride + axis]);
                        if (!Float.isFinite(value) || value < 0 || value > 1) return false;
                    }
            }
        return true;
    }

    private static final class Bounds {
        float minX, minY, minZ, maxX, maxY, maxZ;
        boolean invalid, empty;

        void clear() {
            minX = minY = minZ = Float.POSITIVE_INFINITY;
            maxX = maxY = maxZ = Float.NEGATIVE_INFINITY;
            invalid = false;
            empty = true;
        }

        void add(float x, float y, float z) {
            invalid |= !Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z);
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
            maxZ = Math.max(maxZ, z);
            empty = false;
        }

        AABB world(Key key) {
            if (invalid) return null;
            if (empty) return keyBounds(key);
            int size = key.size();
            long x = (long) key.x() * size, y = (long) key.y() * size, z = (long) key.z() * size;
            return new AABB(
                    x + (double) minX,
                    y + (double) minY,
                    z + (double) minZ,
                    x + (double) maxX,
                    y + (double) maxY,
                    z + (double) maxZ);
        }
    }

    private static final class Scratch implements AutoCloseable {
        final ByteBufferBuilder indices = new ByteBufferBuilder(4096);
        final PoseStack pose = new PoseStack();
        final RandomSource random = RandomSource.create(0);
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(),
                actual = new BlockPos.MutableBlockPos(),
                neighbor = new BlockPos.MutableBlockPos();
        final Set<TextureAtlasSprite> sprites = Collections.newSetFromMap(new IdentityHashMap<>());
        final Bounds bounds = new Bounds();
        final OwnedConsumer owned = new OwnedConsumer(sprites, bounds);
        final FluidConsumer fluid = new FluidConsumer(sprites, bounds);
        final Map<RenderType, ByteBufferBuilder> storage = new HashMap<>();
        final Map<RenderType, BufferBuilder> buffers = new LinkedHashMap<>();

        void begin() {
            buffers.clear();
            sprites.clear();
            bounds.clear();
        }

        void sprite(TextureAtlasSprite sprite) {
            if (sprite != null && SpriteUtil.INSTANCE.hasAnimation(sprite)) sprites.add(sprite);
        }

        BufferBuilder buffer(RenderType type) {
            return buffers.computeIfAbsent(
                    type,
                    key ->
                            new BufferBuilder(
                                    storage.computeIfAbsent(
                                            key, ignored -> new ByteBufferBuilder(65536)),
                                    key.mode(),
                                    VoxelVertices.FORMAT));
        }

        VertexConsumer consumer(RenderType type, int x, int y, int z) {
            owned.bind(buffer(type), type, x, y, z);
            return owned;
        }

        List<Layer> finish() {
            List<Layer> result = new ArrayList<>();
            buffers.forEach(
                    (type, builder) -> {
                        MeshData mesh = builder.build();
                        if (mesh != null) result.add(new Layer(type, mesh));
                    });
            return result;
        }

        void discard() {
            buffers.forEach(
                    (type, builder) -> {
                        MeshData mesh = builder.build();
                        if (mesh != null) mesh.close();
                    });
        }

        public void close() {
            storage.values().forEach(ByteBufferBuilder::close);
            storage.clear();
            sprites.clear();
            indices.close();
        }
    }

    /** Adds explicit ownership without changing any native surface attributes. */
    private static class OwnedConsumer implements VertexConsumer {
        BufferBuilder output;
        int cellX, cellY, cellZ;
        final Set<TextureAtlasSprite> sprites;
        final Bounds bounds;

        OwnedConsumer(Set<TextureAtlasSprite> sprites, Bounds bounds) {
            this.sprites = sprites;
            this.bounds = bounds;
        }

        @Override
        public void putBulkData(
                PoseStack.Pose pose,
                BakedQuad quad,
                float[] brightness,
                float red,
                float green,
                float blue,
                float alpha,
                int[] light,
                int overlay,
                boolean readColor) {
            TextureAtlasSprite sprite = quad.getSprite();
            if (sprite != null && SpriteUtil.INSTANCE.hasAnimation(sprite)) {
                sprites.add(sprite);
            }
            VertexConsumer.super.putBulkData(
                    pose, quad, brightness, red, green, blue, alpha, light, overlay, readColor);
        }

        void bind(BufferBuilder output, RenderType type, int x, int y, int z) {
            this.output = output;
            cellX = x;
            cellY = y;
            cellZ = z;
        }

        public VertexConsumer addVertex(float x, float y, float z) {
            bounds.add(x, y, z);
            output.addVertex(x, y, z);
            VoxelVertices.cell(output, cellX, cellY, cellZ);
            return this;
        }

        public VertexConsumer setColor(int r, int g, int b, int a) {
            output.setColor(r, g, b, a);
            return this;
        }

        public VertexConsumer setUv(float u, float v) {
            output.setUv(u, v);
            return this;
        }

        public VertexConsumer setUv1(int u, int v) {
            output.setUv1(u, v);
            return this;
        }

        public VertexConsumer setUv2(int u, int v) {
            output.setUv2(u, v);
            return this;
        }

        public VertexConsumer setNormal(float x, float y, float z) {
            output.setNormal(x, y, z);
            return this;
        }
    }

    private static final class FluidConsumer extends OwnedConsumer {
        int offsetX, offsetY, offsetZ, scale;
        TextureAtlasSprite[] sourceSprites;

        FluidConsumer(Set<TextureAtlasSprite> sprites, Bounds bounds) {
            super(sprites, bounds);
        }

        @Override
        void bind(BufferBuilder output, RenderType type, int x, int y, int z) {
            super.bind(output, type, x, y, z);
            sourceSprites = null;
        }

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            if (sourceSprites != null) {
                for (TextureAtlasSprite sprite : sourceSprites)
                    if (sprite != null && SpriteUtil.INSTANCE.hasAnimation(sprite))
                        sprites.add(sprite);
                sourceSprites = null;
            }
            return super.addVertex(x * scale + offsetX, y * scale + offsetY, z * scale + offsetZ);
        }
    }

    private final class Camera {
        final Vec3 position;
        final Matrix4f matrix;
        final FrustumIntersection frustum;
        final int width = client.getMainRenderTarget().viewWidth,
                height = client.getMainRenderTarget().viewHeight;
        final int depth = client.getMainRenderTarget().getDepthTextureId();
        final float[] px = new float[8], py = new float[8];

        Camera(Vec3 position, org.joml.Matrix4fc view, org.joml.Matrix4fc projection) {
            this.position = position;
            matrix = new Matrix4f(projection).mul(view);
            frustum = new FrustumIntersection(matrix);
        }

        boolean same(Camera other) {
            return other != null
                    && position.equals(other.position)
                    && matrix.equals(other.matrix)
                    && width == other.width
                    && height == other.height
                    && depth == other.depth;
        }

        boolean visible(AABB bounds) {
            return frustum.testAab(
                    (float) (bounds.minX - position.x), (float) (bounds.minY - position.y),
                    (float) (bounds.minZ - position.z), (float) (bounds.maxX - position.x),
                    (float) (bounds.maxY - position.y), (float) (bounds.maxZ - position.z));
        }

        boolean queryable(AABB bounds) {
            for (int corner = 0; corner < 8; corner++) {
                float x = (float) (((corner & 1) == 0 ? bounds.minX : bounds.maxX) - position.x);
                float y = (float) (((corner & 4) == 0 ? bounds.minY : bounds.maxY) - position.y);
                float z = (float) (((corner & 2) == 0 ? bounds.minZ : bounds.maxZ) - position.z);
                float w = matrix.m03() * x + matrix.m13() * y + matrix.m23() * z + matrix.m33();
                float depth = matrix.m02() * x + matrix.m12() * y + matrix.m22() * z + matrix.m32();
                if (w <= 0 || depth <= -w) return false;
            }
            return true;
        }

        private double project(AABB bounds) {
            float minX = 1, minY = 1, maxX = 0, maxY = 0;
            for (int i = 0; i < 8; i++) {
                float x = (float) (((i & 1) == 0 ? bounds.minX : bounds.maxX) - position.x);
                float y = (float) (((i & 4) == 0 ? bounds.minY : bounds.maxY) - position.y);
                float z = (float) (((i & 2) == 0 ? bounds.minZ : bounds.maxZ) - position.z);
                float w = matrix.m03() * x + matrix.m13() * y + matrix.m23() * z + matrix.m33();
                if (w <= 0
                        || matrix.m02() * x + matrix.m12() * y + matrix.m22() * z + matrix.m32()
                                <= -w) return Double.POSITIVE_INFINITY;
                px[i] =
                        (matrix.m00() * x + matrix.m10() * y + matrix.m20() * z + matrix.m30())
                                        / w
                                        * .5f
                                + .5f;
                py[i] =
                        (matrix.m01() * x + matrix.m11() * y + matrix.m21() * z + matrix.m31())
                                        / w
                                        * .5f
                                + .5f;
                minX = Math.min(minX, px[i]);
                minY = Math.min(minY, py[i]);
                maxX = Math.max(maxX, px[i]);
                maxY = Math.max(maxY, py[i]);
            }
            double area =
                    cross(0, 1, 4)
                            + cross(0, 1, 2)
                            + cross(0, 4, 2)
                            + cross(7, 6, 3)
                            + cross(7, 6, 5)
                            + cross(7, 3, 5);
            double center =
                    1
                            - Math.min(
                                    1,
                                    Math.hypot(
                                                    (Math.clamp(minX, 0, 1)
                                                                    + Math.clamp(maxX, 0, 1)
                                                                    - 1)
                                                            * .5,
                                                    (Math.clamp(minY, 0, 1)
                                                                    + Math.clamp(maxY, 0, 1)
                                                                    - 1)
                                                            * .5)
                                            * Math.sqrt(2));
            return Math.sqrt(area * .5 * width * height) * (1 + .25 * center);
        }

        private double cross(int a, int b, int c) {
            return Math.abs((px[b] - px[a]) * (py[c] - py[a]) - (px[c] - px[a]) * (py[b] - py[a]));
        }

        double horizontalDistance(AABB bounds) {
            return axis(position.x, bounds.minX, bounds.maxX)
                    + axis(position.z, bounds.minZ, bounds.maxZ);
        }

        double distance(AABB bounds) {
            return horizontalDistance(bounds) + axis(position.y, bounds.minY, bounds.maxY);
        }

        private double axis(double value, double low, double high) {
            double distance = Math.max(Math.max(low - value, value - high), 0);
            return distance * distance;
        }
    }
}
