package com.aerosmp.voxy.client.render;

import com.aerosmp.voxy.terrain.SectionKey;
import com.aerosmp.voxy.client.ClientSettings;
import com.aerosmp.voxy.terrain.TerrainData;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.FogRenderer;
import com.mojang.blaze3d.shaders.FogShape;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.common.NeoForge;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL32C;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.io.IOException;
import java.io.UncheckedIOException;

/** Worker-owned vanilla model meshing; render-thread-owned, fence-published geometry. */
public final class TerrainRenderer implements AutoCloseable {
    private static ShaderInstance shader;
    private static volatile long modelRevision;
    private static TerrainRenderer active;
    private final Minecraft minecraft = Minecraft.getInstance();
    private final Consumer<RenderLevelStageEvent> listener = this::render;
    private final Consumer<ViewportEvent.RenderFog> fogListener = this::fog;
    private final Object meshLock = new Object();
    private final Scratch scratch = new Scratch();
    private final Map<SectionKey, Geometry> published = new HashMap<>();
    private final List<Pending> uploading = new ArrayList<>();
    private final Set<CompletableFuture<Void>> handoffs = ConcurrentHashMap.newKeySet();
    private final Set<SectionKey> failedPublications = ConcurrentHashMap.newKeySet();
    private int radius = ClientSettings.distance;
    private volatile float pixelOverride = Float.NaN;
    private final DepthVisibility depth = new DepthVisibility();
    private final Set<SectionKey> refined = new HashSet<>();
    private Set<SectionKey> wantedKeys = Set.of(), drawnCoverage = Set.of();
    private ViewDemand.Camera cameraView;
    private long frustumCandidates, depthFailures;
    private String depthError = "";
    private volatile List<SectionKey> demand = List.of();
    private volatile boolean closed;
    private volatile boolean enabled = true;
    private volatile boolean clipping = true;
    private volatile long submissions, drawn, vertices, publicationFailures, gpuReady;
    private volatile long meshNanos, meshes, handoffNanos, drawNanos;
    private int visibilityQuery;
    private boolean queryPending;
    private volatile long visibleSamples;
    private volatile long disabledDepthDraws;
    private DynamicTexture vanillaCoverage;
    private int clipX, clipY, clipZ, clipSide, clipHeight;
    private final ByteBufferBuilder sortedIndices = new ByteBufferBuilder(4096);
    // One largest append-stable catalog, owned by meshLock; never a catalog history.
    private String[] blockCatalog, biomeCatalog;
    private BlockState[] resolvedStates;
    private BakedModel[] resolvedModels;
    private Biome[] resolvedBiomes;
    private long catalogModelRevision = -1;

    public static void registerShaders(RegisterShadersEvent event) {
        try {
            event.registerShader(new ShaderInstance(event.getResourceProvider(),
                    ResourceLocation.fromNamespaceAndPath("voxy", "terrain"), DefaultVertexFormat.BLOCK), loaded -> {
                        shader = loaded; modelRevision++;
                    });
        } catch (IOException failure) { throw new UncheckedIOException(failure); }
    }

    public TerrainRenderer() {
        if (radius < 32 || radius > 8192) throw new IllegalArgumentException("voxy.viewDistance must be 32..8192 blocks");
        NeoForge.EVENT_BUS.addListener(RenderLevelStageEvent.class, listener);
        NeoForge.EVENT_BUS.addListener(ViewportEvent.RenderFog.class, fogListener);
        active = this;
    }
    public static int viewDistance() { return active == null ? 0 : active.radius; }
    private void fog(ViewportEvent.RenderFog event) {
        if (closed || event.getMode() != FogRenderer.FogMode.FOG_TERRAIN
                || event.getType() != FogType.NONE || event.getFarPlaneDistance() <= 32) return;
        event.setNearPlaneDistance(radius * .6f); event.setFarPlaneDistance(radius);
        event.setFogShape(FogShape.SPHERE); event.setCanceled(true);
    }
    public List<SectionKey> wanted() { return demand; }
    private void updateDemand(ViewDemand.Camera camera) {
        cameraView = camera; radius = ClientSettings.distance;
        Map<SectionKey, Integer> masks = new HashMap<>();
        published.forEach((key, geometry) -> masks.put(key, geometry.children));
        uploading.forEach(pending -> masks.merge(pending.geometry.key, pending.geometry.children, (old, next) -> old | next));
        try { depth.poll(camera); } catch (RuntimeException failure) { depthFailures++; depthError = failure.toString(); }
        List<SectionKey> next = ViewDemand.sections(camera, minecraft.level.getMinBuildHeight(),
                minecraft.level.getMaxBuildHeight(), radius, pixels(), masks::get,
                key -> !coveredByParent(key) && depth.hidden(key, camera), refined);
        frustumCandidates = next.size();
        if (!next.equals(demand)) { demand = next; wantedKeys = Set.copyOf(next); }
    }
    private boolean coveredByParent(SectionKey key) {
        for (SectionKey parent = key.parent(); parent != null; parent = parent.parent())
            if (drawnCoverage.contains(parent)) return true;
        return false;
    }
    public float pixels() { return Float.isNaN(pixelOverride) ? ClientSettings.pixels : pixelOverride; }
    public void pixels(float value) { pixelOverride = Float.isNaN(value) ? value : ClientSettings.validate(value); }
    public String depthError() { return depthError; }
    public String stats() { return "submitted=" + submissions + " drawn=" + drawn + " vertices=" + vertices; }
    public long modelRevision() { return modelRevision; }
    public void enabled(boolean enabled) { this.enabled = enabled; }
    public void clipping(boolean clipping) { this.clipping = clipping; }
    public Set<SectionKey> drainFailedPublications() {
        Set<SectionKey> failed = Set.copyOf(failedPublications);
        failedPublications.removeAll(failed);
        return failed;
    }
    public Map<String, Long> status() {
        Map<String, Long> result = new LinkedHashMap<>(Map.of("wanted", (long)demand.size(), "submitted", submissions,
                "GPUready", gpuReady, "selected", drawn, "vertices", vertices, "publicationFailures", publicationFailures,
                "meshNanos", meshNanos, "meshes", meshes, "handoffNanos", handoffNanos, "lastDrawNanos", drawNanos));
        result.put("visibleOpaqueSamples", visibleSamples);
        result.put("depthDisabledBeforeDraw", disabledDepthDraws);
        result.put("depthPasses", depth.completed()); result.put("depthRejected", depth.rejected());
        result.put("depthFailures", depthFailures); result.put("frustumCandidates", frustumCandidates);
        result.put("pixelSize", (long)pixels()); result.put("fov", (long)minecraft.options.fov().get());
        result.put("refined", (long)refined.size());
        if (cameraView != null) {
            result.put("projectionYMillionths", (long)(cameraView.matrix.m11() * 1_000_000));
            result.put("viewportWidth", (long)cameraView.width); result.put("viewportHeight", (long)cameraView.height);
        }
        return result;
    }

    /** Called from a local worker. Its sole wait is the render-thread upload handoff. */
    public void submit(TerrainData section) {
        synchronized (meshLock) {
            if (closed) return;
            long start = System.nanoTime();
            List<Layer> layers = mesh(section);
            meshNanos += System.nanoTime() - start; meshes++;
            CompletableFuture<Void> done = new CompletableFuture<>();
            handoffs.add(done);
            RenderSystem.recordRenderCall(() -> {
                try {
                    if (closed || !wantedKeys.contains(section.key())) { layers.forEach(Layer::discard); return; }
                    Map<RenderType, GpuLayer> buffers = new LinkedHashMap<>();
                    int count = 0;
                    try {
                        for (Layer layer : layers) {
                            VertexBuffer buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
                            MeshData.SortState sort = translucent(layer.type)
                                    ? layer.mesh.sortQuads(sortedIndices, VertexSorting.DISTANCE_TO_ORIGIN) : null;
                            buffers.put(layer.type, new GpuLayer(buffer, sort));
                            count += layer.mesh.drawState().vertexCount();
                            buffer.bind();
                            buffer.upload(layer.mesh);
                        }
                    } catch (Throwable failure) {
                        buffers.values().forEach(value -> value.buffer.close());
                        layers.forEach(Layer::discard);
                        throw failure;
                    } finally { VertexBuffer.unbind(); }
                    Geometry geometry = new Geometry(section.key(), section.children(), buffers, count);
                    // At most the latest replacement is retained for this coordinate.
                    uploading.removeIf(old -> {
                        if (!old.geometry.key.equals(section.key())) return false;
                        GL32C.glDeleteSync(old.fence); old.geometry.close(); return true;
                    });
                    uploading.add(new Pending(geometry, GL32C.glFenceSync(GL32C.GL_SYNC_GPU_COMMANDS_COMPLETE, 0)));
                    GL32C.glFlush();
                    submissions++;
                } catch (Throwable failure) {
                    publicationFailures++; failedPublications.add(section.key());
                    done.completeExceptionally(failure);
                }
                finally { done.complete(null); handoffs.remove(done); }
            });
            long handoffStart = System.nanoTime();
            try { done.join(); }
            finally {
                handoffNanos += System.nanoTime() - handoffStart;
                // Results have either been uploaded or abandoned. No worker native storage survives closure.
                if (closed) scratch.close();
            }
        }
    }

    private List<Layer> mesh(TerrainData data) {
        Snapshot world = new Snapshot(data);
        var dispatcher = minecraft.getBlockRenderer();
        RandomSource random = RandomSource.create(0);
        PoseStack pose = new PoseStack();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos actual = new BlockPos.MutableBlockPos();
        int scale = 1 << data.key().level();
        scratch.begin();
        try {
            for (int i = 0; i < TerrainData.CELLS && !closed; i++) {
                BlockState state = world.states[data.blocks()[i]];
                if (state.isAir()) continue;
                int x = i & 31, z = (i >> 5) & 31, y = i >> 10;
                pos.set(x, y, z);
                actual.set((data.key().x() * 32 + x) * scale, (data.key().y() * 32 + y) * scale,
                        (data.key().z() * 32 + z) * scale);
                long seed = state.getSeed(actual);
                if (state.getRenderShape() == RenderShape.MODEL) {
                    var model = world.models[data.blocks()[i]];
                    ModelData modelData = model.getModelData(world, pos, state, ModelData.EMPTY);
                    random.setSeed(seed);
                    pose.pushPose();
                    pose.translate(x * scale, y * scale, z * scale);
                    pose.scale(scale, scale, scale);
                    for (RenderType type : model.getRenderTypes(state, random, modelData))
                        dispatcher.getModelRenderer().tesselateBlock(world, model, state, pos, pose,
                                scratch.buffer(type), true, random, seed, 0, modelData, type);
                    pose.popPose();
                } else if (state.getRenderShape() == RenderShape.ENTITYBLOCK_ANIMATED) {
                    // NBT-dependent entity renderers are unavailable in saved voxel snapshots.
                    // Keep their distant silhouette with the model's actual particle texture.
                    var sprite = world.models[data.blocks()[i]].getParticleIcon(ModelData.EMPTY);
                    BufferBuilder buffer = scratch.buffer(RenderType.solid());
                    int light = LightTexture.pack(world.getBrightness(LightLayer.BLOCK, pos), world.getBrightness(LightLayer.SKY, pos));
                    for (Direction direction : Direction.values()) {
                        if (world.getBlockState(pos.relative(direction)).canOcclude()) continue;
                        float shade = world.getShade(direction, true);
                        int[] face = CUBE_FACES[direction.ordinal()];
                        for (int vertex = 0; vertex < 4; vertex++) buffer.addVertex(
                                (x + face[vertex * 3]) * scale, (y + face[vertex * 3 + 1]) * scale,
                                (z + face[vertex * 3 + 2]) * scale).setColor(shade, shade, shade, 1)
                                .setUv(vertex < 2 ? sprite.getU0() : sprite.getU1(), vertex == 0 || vertex == 3 ? sprite.getV1() : sprite.getV0())
                                .setLight(light).setNormal(direction.getStepX(), direction.getStepY(), direction.getStepZ());
                    }
                }
                FluidState fluid = state.getFluidState();
                if (!fluid.isEmpty()) {
                    // The vanilla fluid mesher emits chunk-local (coordinate & 15) positions.
                    Matrix4f transform = new Matrix4f().scaling(scale).translate(x & ~15, y & ~15, z & ~15);
                    dispatcher.renderLiquid(pos, world, new TransformedConsumer(scratch.buffer(
                            ItemBlockRenderTypes.getRenderLayer(fluid)), transform), state, fluid);
                }
            }
            return scratch.finish();
        } catch (Throwable failure) { scratch.discard(); throw failure; }
    }

    private void publish() {
        for (Iterator<Pending> iterator = uploading.iterator(); iterator.hasNext();) {
            Pending pending = iterator.next();
            int state = GL32C.glClientWaitSync(pending.fence, 0, 0);
            if (state == GL32C.GL_TIMEOUT_EXPIRED) continue;
            Geometry previous = published.get(pending.geometry.key);
            if (state != GL32C.GL_WAIT_FAILED && previous != null && !SectionCoverage.mayReplace(previous.key,
                    previous.children, pending.geometry.children, wantedKeys, published::containsKey)) continue;
            GL32C.glDeleteSync(pending.fence);
            iterator.remove();
            if (state == GL32C.GL_WAIT_FAILED || !wantedKeys.contains(pending.geometry.key)) {
                if (state == GL32C.GL_WAIT_FAILED) {
                    publicationFailures++; failedPublications.add(pending.geometry.key);
                }
                pending.geometry.close();
                continue;
            }
            previous = published.put(pending.geometry.key, pending.geometry);
            if (previous != null) previous.close();
        }
        Set<SectionKey> current = wantedKeys;
        published.values().removeIf(geometry -> {
            if (current.contains(geometry.key)) return false;
            geometry.close(); return true;
        });
        gpuReady = published.size();
    }

    private void render(RenderLevelStageEvent event) {
        if (closed || shader == null || minecraft.level == null || event.getStage() != RenderLevelStageEvent.Stage.AFTER_SOLID_BLOCKS
                && event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        long drawStart = System.nanoTime();
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_SOLID_BLOCKS) {
            int[] viewport = new int[4]; GL32C.glGetIntegerv(GL32C.GL_VIEWPORT, viewport);
            updateDemand(new ViewDemand.Camera(event.getCamera().getPosition(), event.getModelViewMatrix(),
                    event.getProjectionMatrix(), viewport[2], viewport[3]));
            publish();
        }
        if (!enabled || !ClientSettings.rendering) { drawn = 0; visibleSamples = 0; return; }
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_SOLID_BLOCKS) updateVanillaCoverage();
        Vec3 camera = event.getCamera().getPosition();
        Matrix4f projection = new Matrix4f(event.getProjectionMatrix());
        Frustum frustum = new Frustum(event.getModelViewMatrix(), projection);
        frustum.prepare(camera.x, camera.y, camera.z);
        List<SectionKey> selected = SectionCoverage.select(demand,
                key -> published.containsKey(key) ? published.get(key).children : null);
        selected.sort(Comparator.comparingDouble((SectionKey key) -> ViewDemand.distance(key, camera.x, camera.y, camera.z)).reversed());
        boolean transparent = event.getStage() == RenderLevelStageEvent.Stage.AFTER_PARTICLES;
        if (!transparent && visibilityQuery == 0) visibilityQuery = GL32C.glGenQueries();
        if (!transparent && queryPending && GL32C.glGetQueryObjecti(visibilityQuery, GL32C.GL_QUERY_RESULT_AVAILABLE) != 0) {
            visibleSamples = Integer.toUnsignedLong(GL32C.glGetQueryObjecti(visibilityQuery, GL32C.GL_QUERY_RESULT));
            queryPending = false;
        }
        boolean measuring = !transparent && !queryPending;
        if (measuring) GL32C.glBeginQuery(GL32C.GL_SAMPLES_PASSED, visibilityQuery);
        int oldCoverage = RenderSystem.getShaderTexture(1);
        boolean oldDepthWrite = GL32C.glGetBoolean(GL32C.GL_DEPTH_WRITEMASK);
        RenderSystem.setShaderTexture(1, vanillaCoverage.getId());
        shader.getUniform("ClipSize").set(clipping ? (float)clipSide : 0f, clipping ? (float)clipHeight : 0f,
                clipping ? (float)clipSide : 0f);
        long rendered = 0, vertexCount = 0;
        try {
        for (SectionKey key : selected) {
            Geometry geometry = published.get(key);
            int span = key.size();
            double x = (long)key.x() * span, y = (long)key.y() * span, z = (long)key.z() * span;
            if (!frustum.isVisible(new AABB(x, y, z, x + span, y + span, z + span))) continue;
            Matrix4f matrix = new Matrix4f(event.getModelViewMatrix()).translate(
                    (float)(x - camera.x), (float)(y - camera.y), (float)(z - camera.z));
            shader.getUniform("SectionOffset").set((float)(x - clipX * 16L), (float)(y - clipY * 16L), (float)(z - clipZ * 16L));
            for (var layer : geometry.buffers.entrySet()) {
                RenderType type = layer.getKey();
                boolean translucent = translucent(type);
                if (transparent != translucent) continue;
                type.setupRenderState();
                try {
                    if (!GL32C.glIsEnabled(GL32C.GL_DEPTH_TEST)) disabledDepthDraws++;
                    GL32C.glEnable(GL32C.GL_DEPTH_TEST);
                    GL32C.glDepthFunc(GL32C.GL_LEQUAL);
                    RenderSystem.depthMask(true);
                    GL32C.glDepthMask(true);
                    GpuLayer gpu = layer.getValue();
                    gpu.buffer.bind();
                    if (gpu.sort != null) {
                        Vec3 relative = new Vec3(camera.x - x, camera.y - y, camera.z - z);
                        if (gpu.sortedFrom == null || relative.distanceToSqr(gpu.sortedFrom) > 1) {
                            gpu.buffer.uploadIndexBuffer(gpu.sort.buildSortedIndexBuffer(sortedIndices,
                                    VertexSorting.byDistance((float)relative.x, (float)relative.y, (float)relative.z)));
                            gpu.sortedFrom = relative;
                        }
                    }
                    shader.getUniform("AlphaCutoff").set(type == RenderType.solid() ? 0f : .1f);
                    gpu.buffer.drawWithShader(matrix, projection, shader);
                } finally { VertexBuffer.unbind(); type.clearRenderState(); }
            }
            if (!geometry.buffers.isEmpty()) { rendered++; vertexCount += geometry.vertices; }
        }
        } finally {
            if (measuring) { GL32C.glEndQuery(GL32C.GL_SAMPLES_PASSED); queryPending = true; }
            RenderSystem.setShaderTexture(1, oldCoverage);
            RenderSystem.depthMask(!oldDepthWrite); RenderSystem.depthMask(oldDepthWrite);
        }
        if (!transparent) {
            drawnCoverage = Set.copyOf(selected);
            try { depth.submit(minecraft.getMainRenderTarget().getDepthTextureId(), cameraView, demand); }
            catch (RuntimeException failure) { depthFailures++; depthError = failure.toString(); }
            drawn = rendered; vertices = vertexCount; drawNanos = System.nanoTime() - drawStart;
        }
    }

    private void updateVanillaCoverage() {
        int view = minecraft.options.getEffectiveRenderDistance();
        int side = view * 2 + 3, height = minecraft.level.getSectionsCount();
        if (vanillaCoverage == null || side != clipSide || height != clipHeight) {
            if (vanillaCoverage != null) vanillaCoverage.close();
            vanillaCoverage = new DynamicTexture(side, side * height, false);
            vanillaCoverage.setFilter(false, false); clipSide = side; clipHeight = height;
        }
        Vec3 camera = minecraft.gameRenderer.getMainCamera().getPosition();
        clipX = Math.floorDiv((int)Math.floor(camera.x), 16) - view - 1;
        clipY = minecraft.level.getMinSection();
        clipZ = Math.floorDiv((int)Math.floor(camera.z), 16) - view - 1;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = 0; x < side; x++) for (int z = 0; z < side; z++) {
            boolean loaded = minecraft.level.getChunkSource().hasChunk(clipX + x, clipZ + z);
            for (int y = 0; y < height; y++) {
                pos.set((clipX + x) * 16, (clipY + y) * 16, (clipZ + z) * 16);
                boolean compiled = loaded && minecraft.levelRenderer.isSectionCompiled(pos);
                vanillaCoverage.getPixels().setPixelRGBA(x, y * side + z, compiled ? -1 : 0);
            }
        }
        vanillaCoverage.upload();
    }
    private static boolean translucent(RenderType type) { return type == RenderType.translucent() || type == RenderType.tripwire(); }
    private static final int[][] CUBE_FACES = {
            {0,0,1, 0,0,0, 1,0,0, 1,0,1}, {0,1,0, 0,1,1, 1,1,1, 1,1,0},
            {1,0,0, 0,0,0, 0,1,0, 1,1,0}, {0,0,1, 1,0,1, 1,1,1, 0,1,1},
            {0,0,0, 0,0,1, 0,1,1, 0,1,0}, {1,0,1, 1,0,0, 1,1,0, 1,1,1}
    };

    @Override public void close() {
        closed = true;
        if (active == this) active = null;
        NeoForge.EVENT_BUS.unregister(listener);
        NeoForge.EVENT_BUS.unregister(fogListener);
        handoffs.forEach(done -> done.complete(null));
        RenderSystem.recordRenderCall(() -> {
            uploading.forEach(pending -> { GL32C.glDeleteSync(pending.fence); pending.geometry.close(); });
            uploading.clear(); published.values().forEach(Geometry::close); published.clear();
            gpuReady = 0;
            if (vanillaCoverage != null) vanillaCoverage.close(); sortedIndices.close();
            if (visibilityQuery != 0) GL32C.glDeleteQueries(visibilityQuery);
            depth.close();
        });
        // Never acquire meshLock on the render thread: a worker may be awaiting that thread.
        Thread.ofVirtual().start(() -> { synchronized (meshLock) { scratch.close(); } });
    }

    private record Layer(RenderType type, MeshData mesh) { void discard() { mesh.close(); } }
    private static final class GpuLayer {
        final VertexBuffer buffer;
        final MeshData.SortState sort;
        Vec3 sortedFrom;
        GpuLayer(VertexBuffer buffer, MeshData.SortState sort) { this.buffer = buffer; this.sort = sort; }
    }
    private record Geometry(SectionKey key, int children, Map<RenderType, GpuLayer> buffers, int vertices) {
        void close() { buffers.values().forEach(value -> value.buffer.close()); }
    }
    private record Pending(Geometry geometry, long fence) {}
    private static final class Scratch implements AutoCloseable {
        private final Map<RenderType, ByteBufferBuilder> storage = new LinkedHashMap<>();
        private final Map<RenderType, BufferBuilder> builders = new LinkedHashMap<>();
        void begin() { builders.clear(); }
        BufferBuilder buffer(RenderType type) {
            return builders.computeIfAbsent(type, key -> new BufferBuilder(storage.computeIfAbsent(key,
                    ignored -> new ByteBufferBuilder(4096)), VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK));
        }
        List<Layer> finish() {
            List<Layer> layers = new ArrayList<>();
            builders.forEach((type, buffer) -> { MeshData mesh = buffer.build(); if (mesh != null) layers.add(new Layer(type, mesh)); });
            return layers;
        }
        void discard() { storage.values().forEach(ByteBufferBuilder::discard); }
        public void close() { storage.values().forEach(ByteBufferBuilder::close); storage.clear(); builders.clear(); }
    }

    private final class Snapshot implements BlockAndTintGetter {
        final TerrainData data;
        final BlockState[] states;
        final BakedModel[] models;
        final Biome[] biomes;
        Snapshot(TerrainData data) {
            this.data = data;
            long revision = modelRevision;
            if (catalogModelRevision != revision) { blockCatalog = null; biomeCatalog = null; }
            int reused = prefix(blockCatalog, data.blockNames());
            if (reused < data.blockNames().length) {
                resolvedStates = reused == 0 ? new BlockState[data.blockNames().length]
                        : Arrays.copyOf(resolvedStates, data.blockNames().length);
                resolvedModels = reused == 0 ? new BakedModel[data.blockNames().length]
                        : Arrays.copyOf(resolvedModels, data.blockNames().length);
                blockCatalog = data.blockNames();
                for (int i = reused; i < blockCatalog.length; i++) {
                    resolvedStates[i] = parseState(blockCatalog[i]);
                    resolvedModels[i] = minecraft.getBlockRenderer().getBlockModel(resolvedStates[i]);
                }
            }
            reused = prefix(biomeCatalog, data.biomeNames());
            if (reused < data.biomeNames().length) {
                resolvedBiomes = reused == 0 ? new Biome[data.biomeNames().length]
                        : Arrays.copyOf(resolvedBiomes, data.biomeNames().length);
                biomeCatalog = data.biomeNames();
                var registry = minecraft.level.registryAccess().registryOrThrow(Registries.BIOME);
                Biome fallback = registry.get(ResourceLocation.withDefaultNamespace("plains"));
                for (int i = reused; i < biomeCatalog.length; i++) {
                    Biome biome = registry.get(ResourceLocation.tryParse(biomeCatalog[i]));
                    resolvedBiomes[i] = biome == null ? fallback : biome;
                }
            }
            catalogModelRevision = revision;
            states = resolvedStates; models = resolvedModels; biomes = resolvedBiomes;
        }
        int index(BlockPos pos) {
            if ((pos.getX() | pos.getY() | pos.getZ()) < 0 || pos.getX() > 31 || pos.getY() > 31 || pos.getZ() > 31) return -1;
            return pos.getX() | pos.getZ() << 5 | pos.getY() << 10;
        }
        @Override public BlockState getBlockState(BlockPos pos) { int i = index(pos); return i < 0 ? Blocks.AIR.defaultBlockState() : states[data.blocks()[i]]; }
        @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
        @Override public BlockEntity getBlockEntity(BlockPos pos) { return null; }
        @Override public int getHeight() { return 32; }
        @Override public int getMinBuildHeight() { return 0; }
        @Override public float getShade(Direction direction, boolean shade) {
            return shade ? switch (direction) { case DOWN -> .5f; case UP -> 1f; case NORTH, SOUTH -> .8f; case WEST, EAST -> .6f; } : 1f;
        }
        @Override public LevelLightEngine getLightEngine() { return minecraft.level.getLightEngine(); }
        @Override public int getBrightness(LightLayer layer, BlockPos pos) {
            int i = index(pos); if (i < 0) return layer == LightLayer.SKY ? 15 : 0;
            int value = data.light()[i] & 255; return layer == LightLayer.SKY ? value >> 4 : value & 15;
        }
        @Override public int getRawBrightness(BlockPos pos, int subtraction) {
            return Math.max(getBrightness(LightLayer.BLOCK, pos), getBrightness(LightLayer.SKY, pos) - subtraction);
        }
        @Override public int getBlockTint(BlockPos pos, ColorResolver resolver) {
            int i = index(pos); if (i < 0) i = 0;
            int scale = 1 << data.key().level();
            return resolver.getColor(biomes[data.biomes()[i]],
                    ((long)data.key().x() * 32 + pos.getX()) * scale, ((long)data.key().z() * 32 + pos.getZ()) * scale);
        }
    }
    private static int prefix(String[] resolved, String[] incoming) {
        if (resolved == null) return 0;
        int count = Math.min(resolved.length, incoming.length);
        return Arrays.equals(resolved, 0, count, incoming, 0, count) ? count : 0;
    }
    private static BlockState parseState(String name) {
        int bracket = name.indexOf('[');
        ResourceLocation id = ResourceLocation.tryParse(bracket < 0 ? name : name.substring(0, bracket));
        if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) return Blocks.MAGENTA_CONCRETE.defaultBlockState();
        BlockState state = BuiltInRegistries.BLOCK.get(id).defaultBlockState();
        if (bracket >= 0 && name.endsWith("]")) for (String assignment : name.substring(bracket + 1, name.length() - 1).split(",")) {
            String[] property = assignment.split("=", 2);
            if (property.length != 2) continue;
            Property<?> key = state.getBlock().getStateDefinition().getProperty(property[0]);
            if (key != null) state = setProperty(state, key, property[1]);
        }
        return state;
    }
    private static <T extends Comparable<T>> BlockState setProperty(BlockState state, Property<T> property, String value) {
        return property.getValue(value).map(parsed -> state.setValue(property, parsed)).orElse(state);
    }
    private record TransformedConsumer(VertexConsumer output, Matrix4f transform) implements VertexConsumer {
        public VertexConsumer addVertex(float x, float y, float z) { output.addVertex(transform, x, y, z); return this; }
        public VertexConsumer setColor(int r, int g, int b, int a) { output.setColor(r, g, b, a); return this; }
        public VertexConsumer setUv(float u, float v) { output.setUv(u, v); return this; }
        public VertexConsumer setUv1(int u, int v) { output.setUv1(u, v); return this; }
        public VertexConsumer setUv2(int u, int v) { output.setUv2(u, v); return this; }
        public VertexConsumer setNormal(float x, float y, float z) { output.setNormal(x, y, z); return this; }
    }
}
