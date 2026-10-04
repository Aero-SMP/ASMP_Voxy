package com.aerosmp.voxy.client;

import com.aerosmp.voxy.mixin.VoxyMixins;
import com.aerosmp.voxy.Common;
import com.aerosmp.voxy.Common.*;
import com.aerosmp.voxy.update.AutoUpdater;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.*;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.caffeinemc.mods.sodium.api.config.*;
import net.caffeinemc.mods.sodium.api.config.option.*;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.HandlerThread;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.SimpleBakedModel;
import net.minecraft.client.resources.model.WeightedBakedModel;
import net.minecraft.core.*;
import net.minecraft.core.registries.*;
import net.minecraft.nbt.*;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.*;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.*;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.*;
import net.neoforged.neoforge.client.model.BakedModelWrapper;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.joml.Matrix4f;
import org.joml.FrustumIntersection;
import java.util.concurrent.*;
import java.util.function.Consumer;
import static org.lwjgl.opengl.GL43C.*;
import static org.lwjgl.glfw.GLFW.*;

@Mod(value = "voxy", dist = Dist.CLIENT)
public final class VoxyClient {
    private AutoUpdater updater;
    private volatile TerrainSession terrain;
    private net.minecraft.client.multiplayer.ClientLevel level;
    private long nextJoin, nextDiscovery, nextControl, modified = -1;
    private boolean network = true, render = true, clipping = true;
    private float pixels = Float.NaN;
    private Integer originalFov;
    public VoxyClient(IEventBus modBus) {
        AutoUpdater.starting(net.neoforged.fml.loading.FMLPaths.GAMEDIR.get());
        modBus.addListener(this::registerPayload); modBus.addListener(TerrainRenderer::registerShaders);
        NeoForge.EVENT_BUS.addListener(this::tick);
    }
    private void registerPayload(RegisterPayloadHandlersEvent event) {
        event.registrar("voxy").optional().executesOn(HandlerThread.NETWORK).playBidirectional(Endpoint.TYPE, Endpoint.CODEC,
                (value, context) -> {
                    TerrainSession session = terrain; if (session == null || value.port() == 0) return;
                    Endpoint previous = session.endpoint;
                    if (previous == null || previous.port() != value.port() || !previous.host().equals(value.host())
                            || !Arrays.equals(previous.certificate(), value.certificate())) session.endpoint = value;
                });
    }
    private void tick(ClientTickEvent.Post event) {
        Minecraft client = Minecraft.getInstance(); boolean managed = client.getUser() != null && client.getUser().getName().equals("MGengine");
        if (managed) control(client);
        if (client.level != level || !ClientSettings.rendering && terrain != null) reset();
        if (ClientSettings.rendering && client.level != null && client.player != null && terrain == null) {
            level = client.level;
            String address = client.getCurrentServer() == null ? "local" : client.getCurrentServer().ip;
            try { terrain = new TerrainSession(client.gameDirectory.toPath().resolve(".voxy/terrain"), address, level.dimension().location().toString(), this::reset); }
            catch (IOException failure) { System.err.println("Voxy cache: " + failure); }
        }
        if (terrain != null) {
            terrain.network(network); terrain.renderer.enabled = render; terrain.renderer.clipping = clipping; terrain.renderer.pixelOverride = pixels;
            terrain.tick();
            if (network && client.getConnection() != null && client.getConnection().hasChannel(Endpoint.TYPE) && System.nanoTime() >= nextDiscovery) {
                nextDiscovery = System.nanoTime() + 5_000_000_000L; client.getConnection().send(Endpoint.request());
            }
        }
        if (!managed) return;
        if (updater == null) {
            updater = new AutoUpdater(client.gameDirectory.toPath(), "client", true, update -> {
                AutoUpdater.prepare(update); client.execute(() -> { client.disconnect(); client.stop(); });
            }); updater.start();
        }
        if (client.isGameLoadFinished()) updater.loaded();
        if (updater.restartPending() || !client.isGameLoadFinished() || client.level != null || client.getConnection() != null
                || !(client.screen instanceof TitleScreen || client.screen instanceof JoinMultiplayerScreen || client.screen instanceof DisconnectedScreen)
                || System.nanoTime() < nextJoin) return;
        nextJoin = System.nanoTime() + 5_000_000_000L;
        String address = "play.aerosmp.com:25587";
        ConnectScreen.startConnecting(client.screen, client, ServerAddress.parseString(address), new ServerData("Voxy Testing", address, ServerData.Type.OTHER), false, null);
    }
    private void reset() { TerrainSession previous = terrain; terrain = null; level = null; if (previous != null) previous.close(); }
    private void control(Minecraft client) {
        if (System.nanoTime() < nextControl) return; nextControl = System.nanoTime() + 1_000_000_000L;
        Path path = client.gameDirectory.toPath().resolve(".voxy/terrain/control.properties");
        try {
            if (!Files.isRegularFile(path)) return;
            long time = Files.getLastModifiedTime(path).toMillis(); if (time == modified) return;
            Properties values = new Properties(); try (var input = Files.newInputStream(path)) { values.load(input); }
            network = Boolean.parseBoolean(values.getProperty("network", "true")); render = Boolean.parseBoolean(values.getProperty("render", "true"));
            clipping = Boolean.parseBoolean(values.getProperty("clip", "true")); pixels = values.containsKey("pixels") ? ClientSettings.validate(Float.parseFloat(values.getProperty("pixels"))) : Float.NaN;
            if (values.containsKey("fov")) {
                if (originalFov == null) originalFov = client.options.fov().get();
                client.options.fov().set(Math.clamp(Integer.parseInt(values.getProperty("fov")), 30, 110));
            } else if (originalFov != null) { client.options.fov().set(originalFov); originalFov = null; }
            if (modified != -1 && values.containsKey("screenshot")) net.minecraft.client.Screenshot.grab(client.gameDirectory, client.getMainRenderTarget(), result -> System.out.println("[Voxy live] " + result.getString()));
            if (modified != -1 && values.containsKey("reload")) client.reloadResourcePacks();
            if (modified != -1 && values.containsKey("profile")) client.debugClientMetricsStart(result -> System.out.println("[Voxy profiling] " + result.getString()));
            modified = time;
        } catch (IOException | IllegalArgumentException failure) { System.err.println("Voxy live control: " + failure); }
    }
    static boolean vanilla(BlockPos pos) {
        RenderSectionManager manager = vanillaManager();
        if (manager == null) return Minecraft.getInstance().levelRenderer.isSectionCompiled(pos);
        return manager.isSectionBuilt(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4)
                && manager.isSectionVisible(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4);
    }
    static RenderSectionManager vanillaManager() {
        SodiumWorldRenderer sodium = SodiumWorldRenderer.instanceNullable(); return sodium == null ? null : ((VoxyMixins.SodiumCoverage)(Object)sodium).sections();
    }
    public interface SharedVertices { void adopt(int vertices, int elements, MeshData.DrawState state); }
    public static int viewDistance() { return TerrainRenderer.viewDistance(); }
    @ConfigEntryPointForge("voxy")
    public static final class Menu implements ConfigEntryPoint {
        public void registerConfigLate(ConfigBuilder builder) {
            var enabled = builder.createBooleanOption(id("enabled")); enabled.setName(text("enabled")); enabled.setTooltip(text("enabled.tooltip"));
            enabled.setBinding(value -> ClientSettings.rendering = value, () -> ClientSettings.rendering); enabled.setDefaultValue(true).setStorageHandler(ClientSettings::save);
            var distance = builder.createIntegerOption(id("render_distance")); distance.setName(text("renderDistance")); distance.setTooltip(text("renderDistance.tooltip"));
            distance.setBinding(value -> ClientSettings.distance = value * 16, () -> Math.round(ClientSettings.distance / 16f)); distance.setDefaultValue(128).setStorageHandler(ClientSettings::save);
            distance.setRange(new Range(2, 512, 2)).setValueFormatter(value -> Component.literal(Integer.toString(value)));
            var pixels = builder.createIntegerOption(id("subdivsize")); pixels.setName(text("subDivisionSize")); pixels.setTooltip(text("subDivisionSize.tooltip"));
            pixels.setBinding(value -> ClientSettings.pixels = ClientSettings.fromSlider(value), () -> ClientSettings.toSlider(ClientSettings.pixels));
            pixels.setDefaultValue(ClientSettings.toSlider(64)).setStorageHandler(ClientSettings::save); pixels.setRange(new Range(0, 100, 1));
            pixels.setValueFormatter(value -> Component.literal(Math.round(ClientSettings.fromSlider(value)) + " px"));
            builder.registerModOptions("voxy", "Voxy", "0").setIcon(id("icon.png"))
                    .addPage(builder.createOptionPage().setName(Component.translatable("voxy.config.general")).addOptionGroup(builder.createOptionGroup().addOption(enabled)))
                    .addPage(builder.createOptionPage().setName(Component.translatable("voxy.config.rendering")).addOptionGroup(builder.createOptionGroup().addOption(distance).addOption(pixels)));
        }
        private static ResourceLocation id(String value) { return ResourceLocation.fromNamespaceAndPath("voxy", value); }
        private static Component text(String value) { return Component.translatable("voxy.config.general." + value); }
    }
}

final class ClientSettings {
    private static final Path FILE = Minecraft.getInstance().gameDirectory.toPath().resolve("config/voxy.properties");
    static volatile boolean rendering = true;
    static volatile int distance = Integer.getInteger("voxy.viewDistance", 2048);
    static volatile float pixels = 64;
    static {
        try {
            Properties values = new Properties(); if (Files.isRegularFile(FILE)) try (var input = Files.newInputStream(FILE)) { values.load(input); }
            rendering = Boolean.parseBoolean(values.getProperty("rendering", "true")); distance = Math.clamp(Integer.parseInt(values.getProperty("distance", Integer.toString(distance))), 32, 8192);
            pixels = validate(Float.parseFloat(values.getProperty("pixels", "64")));
        } catch (Exception failure) { System.err.println("Voxy settings: " + failure); }
    }
    static float validate(float value) { return Float.isFinite(value) ? Math.clamp(value, 28, 256) : 64; }
    static float fromSlider(int value) { return (float)(28 * Math.exp(Math.log(256.0 / 28) * value / 100)); }
    static int toSlider(float value) { return Math.clamp((int)Math.round(Math.log(validate(value) / 28) / Math.log(256.0 / 28) * 100), 0, 100); }
    static void save() {
        try { Common.atomic(FILE, ("rendering=" + rendering + "\ndistance=" + distance + "\npixels=" + pixels + "\n").getBytes(StandardCharsets.UTF_8)); }
        catch (IOException failure) { throw new UncheckedIOException(failure); }
    }
}

/** Cache loading runs before endpoint discovery; connection state never gates local visibility. */
final class TerrainSession implements AutoCloseable {
    final TerrainRenderer renderer;
    private final Cache cache;
    private final String address, dimension;
    private final Path statusFile;
    private final Thread local, remote;
    volatile Endpoint endpoint;
    private volatile Link link;
    private volatile boolean closed, network = true, connected;
    private volatile long downloads, cacheLoads, receivedBytes, networkFailures, localFailures;
    private volatile String error = "";
    private long nextStatus;
    private volatile byte[] status;
    TerrainSession(Path root, String address, String dimension, Runnable identityChanged) throws IOException {
        this.address = address; this.dimension = dimension; cache = new Cache(root, address, dimension); statusFile = root.resolve("status.json");
        renderer = new TerrainRenderer();
        local = worker("Voxy cache", this::local);
        remote = worker("Voxy connection", () -> remote(identityChanged));
    }
    private Thread worker(String name, Runnable action) { Thread thread = new Thread(action, name); thread.setDaemon(true); thread.start(); return thread; }
    void network(boolean enabled) { network = enabled; Link connection = link; if (!enabled && connection != null) connection.close(); }
    void tick() {
        if (System.nanoTime() < nextStatus) return; nextStatus = System.nanoTime() + 1_000_000_000L;
        Map<String, Object> value = renderer.status(); value.put("world", cache.identity()); value.put("dimension", dimension); value.put("connected", connected);
        value.put("downloads", downloads); value.put("cacheLoads", cacheLoads); value.put("receivedBytes", receivedBytes); value.put("networkFailures", networkFailures);
        value.put("localFailures", localFailures); value.put("lastError", error); value.put("timeMillis", System.currentTimeMillis());
        var player = Minecraft.getInstance().player;
        if (player != null) { value.put("position", List.of(player.getX(), player.getY(), player.getZ())); value.put("rotation", List.of(player.getYRot(), player.getXRot())); }
        status = new com.google.gson.Gson().toJson(value).getBytes(StandardCharsets.UTF_8); renderer.wakeup();
    }
    private void local() {
        List<Key> retained = List.of();
        while (!closed) {
            TerrainRenderer.Section section = null; TerrainRenderer.Revision attempt = null; boolean validated = false;
            try {
                section = renderer.job(); flushStatus();
                List<Key> roots = renderer.roots();
                if (roots != retained) { cache.retain(roots); retained = roots; for (Key root : roots) { var owner = renderer.sections.get(root); if (owner != null && owner.children < 0) renderer.enqueue(owner); } }
                if (section == null || !section.active || !renderer.retry(section)) continue;
                Key key = section.key; Loaded loaded;
                synchronized (section) { loaded = section.local; section.local = null; }
                if (loaded == null && !renderer.loaded(section, section.cacheHash) && !section.checked) {
                    var fallback = section.fallback;
                    loaded = fallback == null ? cache.load(key) : new Loaded(fallback.hash(), Common.decode(key, fallback.payload()));
                    if (loaded != null) synchronized (section) {
                        if (section.cacheHash != null && !Arrays.equals(section.cacheHash, loaded.hash())) continue;
                        section.cacheHash = loaded.hash();
                    }
                    section.checked = true;
                    if (loaded == null) renderer.requestChanged();
                }
                if (loaded != null) {
                    validated = true; section.corrupt = false;
                    synchronized (section) { if (!Arrays.equals(section.cacheHash, loaded.hash())) continue; renderer.available(key, loaded.data().children()); }
                    attempt = renderer.attempt(section, loaded.hash()); renderer.submit(loaded.data(), loaded.hash()); cacheLoads++;
                } else if (section.children < 0 && key.level() > 0) {
                    int children = cache.children(key); if (children > 0) renderer.available(key, children);
                }
            } catch (InterruptedException stopped) { return; }
            catch (IOException failure) { if (section != null) { if (validated) renderer.failed(section, attempt); else { section.corrupt = true; section.checked = true; renderer.requestChanged(); } } localFailures++; error = failure.toString(); }
            catch (Exception failure) { if (section != null) renderer.failed(section, attempt); localFailures++; error = failure.toString(); }
        }
    }
    private void flushStatus() {
        byte[] snapshot = status; status = null;
        if (snapshot != null) try { Common.atomic(statusFile, snapshot); } catch (IOException failure) { error = failure.toString(); }
    }
    private void remote(Runnable identityChanged) {
        while (!closed) {
            Endpoint ready = endpoint; if (ready == null || !network) { pause(100); continue; }
            String host = ready.host().isEmpty() ? ServerAddress.parseString(address).getHost() : ready.host();
            try (Link connection = new Link(host, ready.port(), ready.certificate(), dimension)) {
                link = connection; connected = true;
                if (cache.associate(connection.world())) { Minecraft.getInstance().execute(identityChanged); return; }
                long nextRefresh = 0;
                while (!closed && network && endpoint == ready) {
                    long revision = renderer.view(); List<Request> requests = new ArrayList<>(); long now = System.nanoTime();
                    for (TerrainRenderer.Section section : renderer.wanted()) {
                        Key key = section.key;
                        if (section != null && section.needed && (section.checked && section.cacheHash == null || section.corrupt)
                                && (!section.unavailable || now >= nextRefresh)) requests.add(new Request(key, null));
                    }
                    if (!requests.isEmpty()) connection.sections(requests, (key, reply) -> receive(key, reply));
                    else if (now >= nextRefresh) for (TerrainRenderer.Section section : renderer.wanted()) {
                        if (closed || !network || renderer.view() != revision) break;
                        Key key = section.key;
                        if (section != null && section.needed && section.cacheHash != null) receive(key, connection.get(key, section.cacheHash));
                    }
                    if (now >= nextRefresh) nextRefresh = System.nanoTime() + 5_000_000_000L;
                    pause(100);
                }
            } catch (Exception failure) { if (!closed && network && endpoint == ready) { networkFailures++; error = failure.toString(); pause(1000); } }
            finally { connected = false; link = null; }
        }
    }
    private void receive(Key key, Reply reply) throws IOException {
        TerrainRenderer.Section section = renderer.sections.computeIfAbsent(key, TerrainRenderer.Section::new);
        section.unavailable = reply.status() == 0;
        if (reply.status() != 2 || closed) return;
        if (cache.save(key, reply.payload(), loaded -> {
            synchronized (section) { section.cacheHash = loaded.hash(); section.local = section.active ? loaded : null; section.fallback = new TerrainRenderer.Fallback(loaded.hash(), reply.payload()); section.corrupt = false; section.checked = false; }
            renderer.available(key, loaded.data().children()); renderer.enqueue(section);
        })) synchronized (section) { if (section.fallback != null && section.fallback.payload() == reply.payload()) section.fallback = null; }
        downloads++; receivedBytes += reply.payload().length;
    }
    private void pause(long millis) { try { Thread.sleep(millis); } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); } }
    public void close() { closed = true; Link connection = link; if (connection != null) connection.close(); local.interrupt(); remote.interrupt(); renderer.close(); }
}

/** One owner per section; local meshing and fence publication preserve the preceding coverage. */
final class TerrainRenderer implements AutoCloseable {
    public static final class Section {
        final Key key;
        Section parent;
        Section[] branch;
        Rank rank;
        int queued = -1, interestChildren = -1, mode, drawn;
        boolean dirty, shown, complete;
        long view;
        volatile long purpose;
        long parked = -1;
        Section(Key key) { this.key = key; }
        volatile byte[] cacheHash;
        volatile Loaded local;
        volatile Fallback fallback;
        volatile int children = -1;
        volatile boolean active, needed, unavailable, corrupt, checked, deniedNeeded;
        volatile long requiredBytes;
        volatile Revision failure, deniedInput;
        boolean covering, refined, occluded;
        volatile Geometry current;
        volatile Pending pending;
        int query;
        boolean querying, hidden;
        Camera queryCamera;
        long queryScene;
        double diameter, distance;
    }
    record Fallback(byte[] hash, byte[] payload) {}
    record Revision(byte[] hash, long models, long purpose) {}
    private record Rank(int level, double benefit, int x, int y, int z) implements Comparable<Rank> {
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
        GpuLayer(MeshData.DrawState state, MeshData.SortState sort) { this.state = state; this.sort = sort; }
        void upload(MeshData mesh) {
            vertices = upload(mesh.vertexBuffer());
            try { elements = mesh.indexBuffer() == null ? 0 : upload(mesh.indexBuffer()); }
            catch (Throwable failure) { glDeleteBuffers(vertices); vertices = 0; throw failure; }
        }
        private static int upload(java.nio.ByteBuffer bytes) {
            int id = glGenBuffers(); glBindBuffer(GL_ARRAY_BUFFER, id); glBufferData(GL_ARRAY_BUFFER, bytes, GL_STATIC_DRAW);
            int error = glGetError();
            if (error != GL_NO_ERROR) { glDeleteBuffers(id); throw new IllegalStateException("Terrain GPU upload failed: " + error); }
            return id;
        }
        void adopt() {
            if (!glIsBuffer(vertices) || elements != 0 && !glIsBuffer(elements)) throw new IllegalStateException("Shared terrain buffers unavailable");
            buffer = new VertexBuffer(VertexBuffer.Usage.STATIC); buffer.bind();
            try { ((VoxyClient.SharedVertices)(Object)buffer).adopt(vertices, elements, state); }
            finally { VertexBuffer.unbind(); }
        }
        void close() { if (buffer != null) buffer.close(); else { glDeleteBuffers(vertices); if (elements != 0) glDeleteBuffers(elements); } }
    }
    private record Geometry(Key key, byte[] hash, int children, Map<RenderType, GpuLayer> layers, List<Entity> entities, long bytes, long models) {
        void close() { layers.values().forEach(GpuLayer::close); }
    }
    private record Pending(Geometry geometry, long fence) {}
    private record Model(BlockState state, BakedModel baked, boolean enclosedSafe) {}
    private record Entity(BlockEntity block, int light) {}
    private static ShaderInstance shader;
    private static volatile long models;
    private static TerrainRenderer active;
    private static final Direction[] SIDES = Direction.values();
    private static final int[] BOX = {0,1,3,2, 4,6,7,5, 0,4,5,1, 2,3,7,6, 0,2,6,4, 1,5,7,3};
    final ConcurrentMap<Key, Section> sections = new ConcurrentHashMap<>();
    private final LinkedHashMap<Key, Section> inactive = new LinkedHashMap<>();
    private final NavigableMap<Long, Set<Section>> denied = new TreeMap<>();
    private final LinkedHashMap<Key, Section> prepared = new LinkedHashMap<>(16, .75f, true);
    private final ConcurrentMap<Key, Section> pending = new ConcurrentHashMap<>();
    private final Set<Section> queries = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<RenderType, Map<Section, GpuLayer>> opaque = new LinkedHashMap<>();
    private final NavigableMap<Section, Geometry> selected = new TreeMap<>(Comparator
            .comparingDouble((Section section) -> section.distance).reversed().thenComparingInt(section -> section.key.level())
            .thenComparingInt(section -> section.key.x()).thenComparingInt(section -> section.key.y()).thenComparingInt(section -> section.key.z()));
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
    private final java.util.concurrent.atomic.AtomicLong gpuBytes = new java.util.concurrent.atomic.AtomicLong();
    private final ConcurrentNavigableMap<Rank, Section> demand = new ConcurrentSkipListMap<>();
    private final List<LinkedHashMap<Key, Section>> jobs = new ArrayList<>();
    private final Queue<Section> changes = new ConcurrentLinkedQueue<>();
    private final Set<Section> hidden = Collections.newSetFromMap(new IdentityHashMap<>());
    private volatile List<Key> roots = List.of();
    private volatile Camera camera;
    private boolean stableCamera;
    private volatile boolean reclaimable;
    private volatile long view;
    private final java.util.concurrent.atomic.AtomicLong requestRevision = new java.util.concurrent.atomic.AtomicLong();
    private long demandDepth;
    private boolean rebuild = true;
    private int demandDistance;
    private float demandPixels;
    private volatile boolean closed;
    boolean enabled = true, clipping = true;
    float pixelOverride = Float.NaN;
    private long revision, gpuRevision, scene, meshNanos, meshCount, drawNanos, uploads, evictions, failures, queryCount, rejected;
    private volatile long freeGpu = Long.MAX_VALUE;
    private DynamicTexture coverage;
    private Object vanillaLists;
    private VertexBuffer box;
    private int clipX, clipY, clipZ, clipSide, clipHeight;
    private final Matrix4f transform = new Matrix4f();
    private final BlockPos.MutableBlockPos clipPos = new BlockPos.MutableBlockPos();

    public TerrainRenderer() {
        for (int i = 0; i < 10; i++) jobs.add(new LinkedHashMap<>());
        long mainWindow = client.getWindow().getWindow();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE); glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, glfwGetWindowAttrib(mainWindow, GLFW_CONTEXT_VERSION_MAJOR));
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, glfwGetWindowAttrib(mainWindow, GLFW_CONTEXT_VERSION_MINOR));
        glfwWindowHint(GLFW_OPENGL_PROFILE, glfwGetWindowAttrib(mainWindow, GLFW_OPENGL_PROFILE));
        uploadWindow = glfwCreateWindow(1, 1, "Voxy terrain uploads", 0, mainWindow); glfwDefaultWindowHints();
        if (uploadWindow == 0) throw new IllegalStateException("Cannot create shared terrain upload context");
        var caps = org.lwjgl.opengl.GL.getCapabilities(); freeGpu = freeGpuMemory();
        System.out.println("[Voxy GPU] renderer=" + glGetString(GL_RENDERER) + " NVX=" + caps.GL_NVX_gpu_memory_info
                + " ATI=" + caps.GL_ATI_meminfo + " freeBytes=" + freeGpu);
        active = this;
        NeoForge.EVENT_BUS.addListener(RenderLevelStageEvent.class, renderListener);
        NeoForge.EVENT_BUS.addListener(ViewportEvent.RenderFog.class, fogListener);
    }
    public static void registerShaders(RegisterShadersEvent event) {
        try { event.registerShader(new ShaderInstance(event.getResourceProvider(), ResourceLocation.fromNamespaceAndPath("voxy", "terrain"),
                DefaultVertexFormat.BLOCK), loaded -> { shader = loaded; models++; }); }
        catch (IOException failure) { throw new UncheckedIOException(failure); }
    }
    public static int viewDistance() { return active == null ? 0 : ClientSettings.distance; }
    private void fog(ViewportEvent.RenderFog event) {
        if (event.getMode() != FogRenderer.FogMode.FOG_TERRAIN || event.getType() != FogType.NONE || event.getFarPlaneDistance() <= 32) return;
        event.setNearPlaneDistance(ClientSettings.distance * .6f); event.setFarPlaneDistance(ClientSettings.distance);
        event.setFogShape(com.mojang.blaze3d.shaders.FogShape.SPHERE); event.setCanceled(true);
    }
    Collection<Section> wanted() { return demand.values(); }
    List<Key> roots() { return roots; }
    long view() { return requestRevision.get(); }
    void requestChanged() { requestRevision.incrementAndGet(); }
    void wakeup() { synchronized (jobs) { jobs.notifyAll(); } }
    Section job() throws InterruptedException {
        synchronized (jobs) {
            for (var queue : jobs) if (!queue.isEmpty()) { Section section = queue.pollFirstEntry().getValue(); section.queued = -1; return section; }
            jobs.wait(); return null;
        }
    }
    void enqueue(Section section) {
        synchronized (jobs) {
            int next = section.active && retry(section) ? (section.needed ? 0 : 5) + 4 - section.key.level() : -1;
            if (next == section.queued) return;
            if (section.queued >= 0) jobs.get(section.queued).remove(section.key);
            section.queued = next;
            if (next >= 0) jobs.get(next).put(section.key, section);
            jobs.notifyAll();
        }
    }
    private void changed(Section section) {
        synchronized (section) { if (!section.dirty) { section.dirty = true; changes.add(section); } }
    }
    boolean retry(Section section) {
        Revision failure = section.failure, denial = section.deniedInput;
        return (failure == null || failure.models != models || failure.purpose != section.purpose || !Arrays.equals(failure.hash, section.cacheHash))
                && (denial == null || denial.models != models || !Arrays.equals(denial.hash, section.cacheHash)
                || freeGpu >= section.requiredBytes || section.needed && (!section.deniedNeeded || reclaimable));
    }
    Revision attempt(Section section, byte[] hash) { return new Revision(hash, models, section.purpose); }
    void failed(Section section, Revision attempt) { section.failure = attempt == null ? attempt(section, section.cacheHash) : attempt; section.checked = false; }
    boolean loaded(Section section, byte[] hash) {
        Geometry current = section.current; Pending pending = section.pending;
        return current != null && current.models == models && Arrays.equals(current.hash, hash)
                || pending != null && pending.geometry.models == models && Arrays.equals(pending.geometry.hash, hash);
    }
    void available(Key key, int children) {
        Section section = sections.computeIfAbsent(key, Section::new);
        synchronized (section) { if (section.children != children) { section.children = children; changed(section); } }
    }
    Map<String, Object> status() {
        Map<String, Object> result = new LinkedHashMap<>(Map.of("wanted", demand.size(), "GPUready", sections.values().stream().filter(section -> section.current != null).count(), "selected", selected.size(),
                "gpuBytes", gpuBytes.get(), "meshes", meshCount, "meshNanos", meshNanos, "lastDrawNanos", drawNanos,
                "uploads", uploads, "evictions", evictions, "publicationFailures", failures));
        result.put("depthPasses", queryCount); result.put("depthRejected", rejected); result.put("cachedInactive", inactive.size());
        result.put("gpuFreeBytes", freeGpu); result.put("preparedGPU", prepared.size());
        result.put("fov", client.options.fov().get()); if (camera != null) result.put("projectionYMillionths", (long)(camera.matrix.m11() * 1_000_000));
        return result;
    }
    void submit(Frame frame, byte[] hash) {
        synchronized (meshLock) {
            if (closed || loaded(sections.computeIfAbsent(frame.key(), Section::new), hash)) return;
            if (revision != models) { modelCache.clear(); biomeCache.clear(); revision = models; }
            long generation = models, start = System.nanoTime(); Snapshot world = new Snapshot(frame, false, null);
            List<Layer> layers = mesh(world); meshNanos += System.nanoTime() - start; meshCount++;
            glfwMakeContextCurrent(uploadWindow);
            Map<RenderType, GpuLayer> gpu = new LinkedHashMap<>();
            try {
                if (uploadCaps == null) uploadCaps = org.lwjgl.opengl.GL.createCapabilities(); else org.lwjgl.opengl.GL.setCapabilities(uploadCaps);
                long bytes = 0;
                for (Layer layer : layers) {
                    MeshData.SortState sort = transparent(layer.type) ? layer.mesh.sortQuads(scratch.indices, VertexSorting.DISTANCE_TO_ORIGIN) : null;
                    gpu.put(layer.type, new GpuLayer(layer.mesh.drawState(), sort));
                    bytes += layer.mesh.vertexBuffer().remaining() + (layer.mesh.indexBuffer() == null ? 0 : layer.mesh.indexBuffer().remaining());
                }
                Section section = sections.computeIfAbsent(frame.key(), Section::new); section.requiredBytes = bytes;
                if (freeGpuMemory() < bytes) {
                    CompletableFuture<Long> space = new CompletableFuture<>(); eviction = space; if (closed) space.complete(0L);
                    RenderSystem.recordRenderCall(() -> {
                        try {
                            long fence = 0;
                            if (!closed && makeRoom(section)) {
                                fence = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
                                if (fence == 0) throw new IllegalStateException("Terrain reclamation fence failed: " + glGetError());
                            }
                            glFlush(); if (!space.complete(fence) && fence != 0) glDeleteSync(fence);
                        } catch (Throwable failure) { space.completeExceptionally(failure); }
                    });
                    long retired = space.join(); eviction = null;
                    if (retired == 0) { section.deniedInput = new Revision(hash, generation, section.purpose); section.deniedNeeded = section.needed; section.checked = false; return; }
                    try {
                        int result = glClientWaitSync(retired, 0, Long.MAX_VALUE);
                        if (result != GL_ALREADY_SIGNALED && result != GL_CONDITION_SATISFIED) throw new IllegalStateException("Terrain reclamation failed: " + result);
                    } finally { glDeleteSync(retired); }
                }
                for (Layer layer : layers) gpu.get(layer.type).upload(layer.mesh);
                Geometry geometry = new Geometry(frame.key(), hash, frame.children(), gpu, world.coreEntities, bytes, generation);
                long fence = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0); glFlush();
                synchronized (section) {
                    if (closed || generation != models || !Arrays.equals(section.cacheHash, hash)) { geometry.close(); glDeleteSync(fence); return; }
                    if (section.pending != null) release(section);
                    section.pending = new Pending(geometry, fence); pending.put(frame.key(), section);
                    section.children = frame.children(); section.deniedInput = null; section.failure = null;
                    gpuBytes.addAndGet(bytes); uploads++;
                }
                freeGpu = freeGpuMemory();
            } catch (Throwable failure) { gpu.values().forEach(GpuLayer::close); failures++; throw failure; }
            finally { layers.forEach(layer -> layer.mesh.close()); glBindBuffer(GL_ARRAY_BUFFER, 0); glfwMakeContextCurrent(0); org.lwjgl.opengl.GL.setCapabilities(null); }
        }
    }
    private List<Layer> mesh(Snapshot world) {
        scratch.begin(); Frame frame = world.frame; Key key = frame.key(); int scale = 1 << key.level();
        Snapshot actualWorld = new Snapshot(frame, true, world);
        var dispatcher = client.getBlockRenderer();
        net.minecraft.client.renderer.block.ModelBlockRenderer.enableCaching();
        meshing = world;
        try {
            for (int y = 0; y < 32 && !closed; y++) for (int z = 0; z < 32; z++) for (int x = 0; x < 32; x++) {
                int index = frame.at(x, y, z); Model model = world.palette[index]; BlockState state = model.state;
                if (state.isAir()) continue;
                scratch.pos.set(key.x() * 32 + x, key.y() * 32 + y, key.z() * 32 + z);
                scratch.actual.set(scratch.pos.getX() * scale, scratch.pos.getY() * scale, scratch.pos.getZ() * scale);
                boolean boundary = x == 0 || x == 31 || y == 0 || y == 31 || z == 0 || z == 31;
                boolean enclosed = model.enclosedSafe && !boundary;
                for (Direction side : SIDES) {
                    if (!enclosed) break;
                    scratch.neighbor.set(scratch.pos).move(side);
                    if (!world.getBlockState(scratch.neighbor).isSolidRender(world, scratch.neighbor)) { enclosed = false; break; }
                }
                if (enclosed) continue;
                if (state.getRenderShape() == RenderShape.MODEL) {
                    BlockEntity entity = world.entities.get(scratch.actual);
                    ModelData input = entity == null ? ModelData.EMPTY : entity.getModelData();
                    ModelData data = model.baked.getModelData(actualWorld, scratch.actual, state, input);
                    long seed = state.getSeed(scratch.actual); scratch.random.setSeed(seed);
                    for (RenderType type : model.baked.getRenderTypes(state, scratch.random, data)) {
                        scratch.pose.setIdentity(); scratch.pose.translate(x * scale, y * scale, z * scale); scratch.pose.scale(scale, scale, scale);
                        world.forceBoundary = boundary && model.enclosedSafe && !transparent(type);
                        dispatcher.getModelRenderer().tesselateBlock(world, model.baked, state, scratch.pos, scratch.pose,
                                scratch.buffer(type), !model.enclosedSafe, scratch.random, seed, 0, data, type);
                    }
                    world.forceBoundary = false;
                }
                FluidState fluid = state.getFluidState();
                if (!fluid.isEmpty()) {
                    scratch.fluid.output = scratch.buffer(ItemBlockRenderTypes.getRenderLayer(fluid)); scratch.fluid.scale = scale;
                    scratch.fluid.x = x & ~15; scratch.fluid.y = y & ~15; scratch.fluid.z = z & ~15;
                    dispatcher.renderLiquid(scratch.pos, world, scratch.fluid, state, fluid);
                }
            }
            return scratch.finish();
        } catch (Throwable failure) { scratch.discard(); throw failure; }
        finally { world.forceBoundary = false; meshing = null; net.minecraft.client.renderer.block.ModelBlockRenderer.clearCache(); }
    }
    private long freeGpuMemory() {
        var caps = org.lwjgl.opengl.GL.getCapabilities();
        if (caps.GL_NVX_gpu_memory_info) return Math.max(0L, glGetInteger(org.lwjgl.opengl.NVXGPUMemoryInfo.GL_GPU_MEMORY_INFO_CURRENT_AVAILABLE_VIDMEM_NVX)) * 1024;
        if (caps.GL_ATI_meminfo) try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
            var values = stack.mallocInt(4); glGetIntegerv(org.lwjgl.opengl.ATIMeminfo.GL_VBO_FREE_MEMORY_ATI, values);
            return Math.max(0L, values.get(0)) * 1024;
        }
        return Long.MAX_VALUE;
    }
    private boolean makeRoom(Section candidate) {
        try {
            long missing = candidate.requiredBytes - freeGpuMemory();
            while (missing > 0) {
                var entry = inactive.pollFirstEntry();
                if (entry == null && candidate.needed) entry = prepared.pollLastEntry();
                if (entry == null) { unpark(candidate); candidate.parked = candidate.requiredBytes; denied.computeIfAbsent(candidate.parked, ignored -> new HashSet<>()).add(candidate); return false; }
                Section section = entry.getValue(); section.deniedInput = new Revision(section.current.hash, section.current.models, section.purpose);
                missing -= section.current.bytes; section.current.close(); gpuBytes.addAndGet(-section.current.bytes);
                section.current = null; section.checked = false; section.deniedNeeded = section.needed; evictions++; scene++;
                changed(section); if (!section.active) discard(section);
            }
            return true;
        } finally {
            reclaimable = !inactive.isEmpty() || !prepared.isEmpty();
        }
    }
    private void unpark(Section section) {
        Set<Section> waiting = denied.get(section.parked);
        if (waiting != null) { waiting.remove(section); if (waiting.isEmpty()) denied.remove(section.parked); } section.parked = -1;
    }
    private void release(Section section) {
        Pending upload = section.pending; glDeleteSync(upload.fence); upload.geometry.close(); gpuBytes.addAndGet(-upload.geometry.bytes);
        section.pending = null; pending.remove(upload.geometry.key, section);
    }
    private void publish() {
        for (Section section : pending.values()) synchronized (section) {
            Pending ready = section.pending; if (ready == null) continue;
            int result = glClientWaitSync(ready.fence, 0, 0);
            if (result == GL_TIMEOUT_EXPIRED) continue;
            if (result != GL_WAIT_FAILED && section.current != null && !mayReplace(section, ready.geometry)) continue;
            glDeleteSync(ready.fence); section.pending = null; pending.remove(ready.geometry.key, section);
            if (result == GL_WAIT_FAILED || ready.geometry.models != models) {
                ready.geometry.close(); gpuBytes.addAndGet(-ready.geometry.bytes); section.checked = false;
                if (result == GL_WAIT_FAILED) failed(section, new Revision(ready.geometry.hash, ready.geometry.models, section.purpose)); failures++; continue;
            }
            try { ready.geometry.layers.values().forEach(GpuLayer::adopt); }
            catch (RuntimeException failure) {
                ready.geometry.close(); gpuBytes.addAndGet(-ready.geometry.bytes); failed(section, new Revision(ready.geometry.hash, ready.geometry.models, section.purpose)); failures++;
                System.err.println("Voxy GPU publication: " + failure); continue;
            }
            if (section.current != null) { section.current.close(); gpuBytes.addAndGet(-section.current.bytes); }
            section.current = ready.geometry; unpark(section); scene++;
            if (!section.needed) { (section.active ? prepared : inactive).put(ready.geometry.key, section); reclaimable = true; }
            changed(section);
        }
    }
    private boolean mayReplace(Section section, Geometry next) {
        Geometry previous = section.current;
        if (section.branch == null) return true;
        for (int i = 0; i < 8; i++) {
            Section child = section.branch[i];
            if (child != null && child.needed && (next.children & (1 << i)) != 0 && !child.complete
                    && (previous.children & (1 << i)) == 0) return false;
        }
        return true;
    }
    private void updateDemand(Camera next) {
        Camera old = camera; camera = next; stableCamera = next.same(old); retireQueries(next);
        float pixels = Float.isNaN(pixelOverride) ? ClientSettings.pixels : pixelOverride;
        long depth = depthScene();
        if (depth != demandDepth) for (Section section : hidden) if (section.occluded) changed(section);
        demandDepth = depth;
        if (rebuild || !stableCamera || ClientSettings.distance != demandDistance || Float.compare(pixels, demandPixels) != 0) {
            rebuild = false; view++; demandDistance = ClientSettings.distance; demandPixels = pixels;
            Collection<Section> previous = new ArrayList<>(demand.values()); demand.clear(); selected.clear(); opaque.clear(); prepared.clear(); rejected = 0;
            for (Section section : previous) {
                section.active = false; section.needed = false; section.shown = false; section.drawn = 0; section.covering = false; section.occluded = false;
                if (section.current != null) inactive.put(section.key, section); enqueue(section);
            }
            reclaimable = !inactive.isEmpty();
            List<Key> visibleRoots = new ArrayList<>(); Vec3 pos = next.position; int radius = ClientSettings.distance;
            for (int x = Math.floorDiv((int)Math.floor(pos.x - radius), 512); x <= Math.floorDiv((int)Math.floor(pos.x + radius), 512); x++)
                for (int z = Math.floorDiv((int)Math.floor(pos.z - radius), 512); z <= Math.floorDiv((int)Math.floor(pos.z + radius), 512); z++)
                    for (int y = Math.floorDiv(client.level.getMinBuildHeight(), 512); y <= Math.floorDiv(client.level.getMaxBuildHeight() - 1, 512); y++) {
                        Section root = sections.computeIfAbsent(new Key(4, x, y, z), Section::new);
                        interest(root, true, next); if (root.active) { visibleRoots.add(root.key); refresh(root); }
                    }
            roots = List.copyOf(visibleRoots); wakeup();
            for (Section section : previous) if (!section.active) discard(section);
        }
        Section section;
        while ((section = changes.poll()) != null) {
            synchronized (section) { section.dirty = false; }
            if (section.active) { interest(section, section.needed, next); refresh(section); }
        }
        reclaimable = !inactive.isEmpty() || !prepared.isEmpty();
    }
    private void discard(Section section) {
        if (section.current == null && section.pending == null && section.fallback == null) {
            sections.remove(section.key, section); forgetQuery(section); unpark(section); section.local = null;
            if (section.parent != null && section.parent.branch != null) {
                int i = (section.key.x() & 1) | (section.key.z() & 1) << 1 | (section.key.y() & 1) << 2;
                if (section.parent.branch[i] == section) section.parent.branch[i] = null;
            }
        }
    }
    private void deactivate(Section section) {
        if (!section.active) return;
        section.active = false; section.needed = false; section.purpose++; requestChanged(); unpark(section); demand.remove(section.rank); prepared.remove(section.key); enqueue(section); show(section, false);
        if (section.current != null) inactive.put(section.key, section);
        if (section.branch != null) for (Section child : section.branch) if (child != null) deactivate(child);
        discard(section);
    }
    private void interest(Section section, boolean needed, Camera next) {
        Key key = section.key;
        if (!next.visible(key) || (long)key.y() * key.size() >= client.level.getMaxBuildHeight()
                || (long)(key.y() + 1) * key.size() <= client.level.getMinBuildHeight()
                || next.horizontalDistance(key) > (double)ClientSettings.distance * ClientSettings.distance) { deactivate(section); return; }
        boolean entering = !section.active, promoted = section.needed != needed;
        if (section.view != view) {
            if (section.rank != null) demand.remove(section.rank);
            section.view = view; section.diameter = next.project(key); section.distance = next.distance(key);
            section.rank = new Rank(key.level(), section.diameter, key.x(), key.y(), key.z());
        }
        section.active = true; section.needed = needed; demand.put(section.rank, section); inactive.remove(key);
        if (needed) prepared.remove(key); else if (section.current != null) prepared.put(key, section);
        if (entering || promoted) { section.purpose++; section.checked = false; requestChanged(); enqueue(section); }
        boolean covered = section.covering || next.contains(key) || !Double.isFinite(section.diameter);
        for (Section parent = section.parent; parent != null; parent = parent.parent) if (parent.covering) covered = true;
        boolean occluded = needed && !covered && section.hidden && !section.querying && section.queryScene == depthScene() && next.same(section.queryCamera);
        if (section.occluded != occluded) { rejected += occluded ? 1 : -1; section.occluded = occluded; }
        float pixels = Float.isNaN(pixelOverride) ? ClientSettings.pixels : pixelOverride;
        boolean refine = needed && !occluded && (section.current == null || section.diameter >= pixels * (section.refined ? .9 : 1));
        if (!entering && !promoted && section.refined == refine && section.interestChildren == section.children) return;
        section.refined = refine; section.interestChildren = section.children;
        if (key.level() == 0) return;
        if (section.branch == null) section.branch = new Section[8];
        for (int i = 0; i < 8; i++) {
            if (needed && (section.children & (1 << i)) != 0 && section.children >= 0) {
                Section child = section.branch[i];
                if (child == null) { child = sections.computeIfAbsent(key.child(i), Section::new); section.branch[i] = child; child.parent = section; }
                interest(child, refine, next); refresh(child);
            } else if (section.branch[i] != null) deactivate(section.branch[i]);
        }
    }
    private void refresh(Section section) {
        Geometry geometry = section.current; boolean finer = false, complete = true;
        int children = (section.children < 0 ? 0 : section.children) | (geometry == null ? 0 : geometry.children);
        if (section.branch != null) for (int i = 0; i < 8; i++) {
            Section child = section.branch[i]; if (child == null || !child.active || !child.needed) continue;
            finer = true;
            if ((children & (1 << i)) != 0 && !child.complete) complete = false;
        }
        section.complete = geometry != null || finer && complete && (section.cacheHash != null || section.children == 255);
        section.mode = !section.active || !section.needed ? 0 : geometry != null && (!finer || !complete) ? 1 : 2;
        if (section.parent != null && section.parent.active) refresh(section.parent);
        show(section, section.parent == null || section.parent.shown && section.parent.mode == 2);
    }
    private void show(Section section, boolean shown) {
        int next = shown ? section.mode : 0; section.shown = shown;
        if (section.drawn == next && (next != 1 || selected.get(section) == section.current)) return;
        scene++;
        if (section.drawn == 1) for (RenderType type : selected.remove(section).layers.keySet()) if (!transparent(type)) {
            var group = opaque.get(type); group.remove(section); if (group.isEmpty()) opaque.remove(type);
        }
        if (section.drawn == 2 && next != 2 && section.branch != null) for (Section child : section.branch) if (child != null) show(child, false);
        section.drawn = next; section.covering = next == 1;
        if (section.covering && section.occluded) changed(section);
        if (next == 1) {
            selected.put(section, section.current);
            section.current.layers.forEach((type, layer) -> { if (!transparent(type)) opaque.computeIfAbsent(type, ignored -> new LinkedHashMap<>()).put(section, layer); });
        }
        else if (next == 2 && section.branch != null) for (Section child : section.branch) if (child != null) show(child, child.active && child.needed);
    }
    private void render(RenderLevelStageEvent event) {
        if (closed || shader == null || client.level == null) return;
        var stage = event.getStage(); boolean opaque = stage == RenderLevelStageEvent.Stage.AFTER_SOLID_BLOCKS;
        boolean transparent = stage == RenderLevelStageEvent.Stage.AFTER_PARTICLES;
        if (!opaque && !transparent && stage != RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) return;
        if (opaque) {
            if (gpuRevision != models) {
                for (Section section : sections.values()) synchronized (section) {
                    if (section.pending != null && section.pending.geometry.models != models) release(section);
                    if (section.current != null && section.current.models != models) { section.current.close(); gpuBytes.addAndGet(-section.current.bytes); section.current = null; }
                    if (section.pending == null) { section.checked = false; }
                    if (!section.active) discard(section);
                }
                inactive.clear(); prepared.clear(); reclaimable = false; selected.clear(); this.opaque.clear(); gpuRevision = models; scene++; rebuild = true;
            }
            freeGpu = freeGpuMemory();
            while (!denied.isEmpty() && denied.firstKey() <= freeGpu) for (Section waiting : denied.pollFirstEntry().getValue()) {
                waiting.parked = -1; waiting.checked = false; enqueue(waiting);
            }
            publish(); updateCoverage();
            updateDemand(new Camera(event.getCamera().getPosition(), event.getModelViewMatrix(), event.getProjectionMatrix()));
        }
        if (!enabled || !ClientSettings.rendering) return;
        if (!opaque && !transparent) { renderEntities(event); return; }
        long start = System.nanoTime(); int oldCoverage = RenderSystem.getShaderTexture(1);
        RenderSystem.setShaderTexture(1, coverage.getId()); shader.getUniform("ClipSize").set(clipping ? clipSide : 0f, clipping ? clipHeight : 0f, clipping ? clipSide : 0f);
        try {
            if (opaque) for (var group : this.opaque.entrySet()) {
                RenderType type = group.getKey(); type.setupRenderState();
                try {
                    RenderSystem.enableDepthTest(); RenderSystem.depthFunc(GL_LEQUAL);
                    shader.setDefaultUniforms(type.mode(), event.getModelViewMatrix(), event.getProjectionMatrix(), client.getWindow());
                    shader.getUniform("AlphaCutoff").set(type == RenderType.solid() ? 0f : type == RenderType.cutoutMipped() ? .5f : .1f); shader.apply();
                    for (var draw : group.getValue().entrySet()) draw(event, draw.getKey().current, draw.getValue(), true);
                } finally { VertexBuffer.unbind(); shader.clear(); type.clearRenderState(); }
            } else for (Geometry geometry : selected.values()) for (var entry : geometry.layers.entrySet()) {
                RenderType type = entry.getKey(); if (!transparent(type)) continue; type.setupRenderState();
                try { RenderSystem.enableDepthTest(); RenderSystem.depthFunc(GL_LEQUAL); shader.getUniform("AlphaCutoff").set(0f); draw(event, geometry, entry.getValue(), false); }
                finally { VertexBuffer.unbind(); type.clearRenderState(); }
            }
        } finally { RenderSystem.setShaderTexture(1, oldCoverage); }
        if (opaque) { drawNanos = System.nanoTime() - start; queryBounds(); }
    }
    private void draw(RenderLevelStageEvent event, Geometry geometry, GpuLayer layer, boolean bound) {
        Vec3 pos = event.getCamera().getPosition(); Key key = geometry.key; int size = key.size();
        double x = (long)key.x() * size, y = (long)key.y() * size, z = (long)key.z() * size;
        transform.set(event.getModelViewMatrix()).translate((float)(x - pos.x), (float)(y - pos.y), (float)(z - pos.z));
        var offset = shader.getUniform("SectionOffset"); offset.set((float)(x - clipX * 16L), (float)(y - clipY * 16L), (float)(z - clipZ * 16L));
        layer.buffer.bind(); double sx = pos.x - x, sy = pos.y - y, sz = pos.z - z;
        if (layer.sort != null && (Double.isNaN(layer.x) || (sx - layer.x) * (sx - layer.x) + (sy - layer.y) * (sy - layer.y) + (sz - layer.z) * (sz - layer.z) > 1)) {
            layer.buffer.uploadIndexBuffer(layer.sort.buildSortedIndexBuffer(indices, VertexSorting.byDistance((float)sx, (float)sy, (float)sz)));
            layer.x = sx; layer.y = sy; layer.z = sz;
        }
        if (bound) {
            if (shader.MODEL_VIEW_MATRIX != null) { shader.MODEL_VIEW_MATRIX.set(transform); shader.MODEL_VIEW_MATRIX.upload(); }
            offset.upload(); layer.buffer.draw();
        } else layer.buffer.drawWithShader(transform, event.getProjectionMatrix(), shader);
    }
    private void renderEntities(RenderLevelStageEvent event) {
        Vec3 pos = event.getCamera().getPosition(); PoseStack pose = event.getPoseStack(); var buffers = client.renderBuffers().bufferSource();
        for (Geometry geometry : selected.values()) for (Entity item : geometry.entities) {
            BlockEntity entity = item.block;
            BlockPos at = entity.getBlockPos(); if (clipping && VoxyClient.vanilla(at)) continue;
            pose.pushPose(); pose.translate(at.getX() - pos.x, at.getY() - pos.y, at.getZ() - pos.z);
            try {
                var renderer = client.getBlockEntityRenderDispatcher().getRenderer(entity);
                if (renderer != null) renderer.render(entity, event.getPartialTick().getGameTimeDeltaPartialTick(false), pose, buffers,
                        item.light, net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY);
            }
            finally { pose.popPose(); }
        }
        buffers.endBatch();
    }
    private void updateCoverage() {
        int view = client.options.getEffectiveRenderDistance(), side = view * 2 + 3, height = client.level.getSectionsCount();
        boolean resized = coverage == null || side != clipSide || height != clipHeight;
        if (resized) {
            if (coverage != null) coverage.close(); coverage = new DynamicTexture(side, side * height, false);
            coverage.setFilter(false, false); clipSide = side; clipHeight = height;
        }
        Vec3 pos = client.gameRenderer.getMainCamera().getPosition();
        int x0 = Math.floorDiv((int)Math.floor(pos.x), 16) - view - 1, z0 = Math.floorDiv((int)Math.floor(pos.z), 16) - view - 1;
        var manager = VoxyClient.vanillaManager(); Object lists = manager == null ? null : manager.getRenderLists();
        if (!resized && lists != null && lists == vanillaLists && x0 == clipX && z0 == clipZ) return;
        clipX = x0; clipY = client.level.getMinSection(); clipZ = z0; vanillaLists = lists;
        org.lwjgl.system.MemoryUtil.memSet(((VoxyMixins.CoveragePixels)(Object)coverage.getPixels()).pixels(), 0, side * (long)side * height * 4);
        if (manager != null) {
            var regions = manager.getRenderLists().iterator(false);
            while (regions.hasNext()) {
                var region = regions.next().getRegion();
                for (int i = 0; i < net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion.REGION_SIZE; i++) {
                    var section = region.getSection(i); if (section == null || !section.isBuilt()) continue;
                    int x = section.getChunkX() - clipX, y = section.getChunkY() - clipY, z = section.getChunkZ() - clipZ;
                    if (x >= 0 && x < side && y >= 0 && y < height && z >= 0 && z < side
                            && manager.isSectionVisible(section.getChunkX(), section.getChunkY(), section.getChunkZ())) coverage.getPixels().setPixelRGBA(x, y * side + z, -1);
                }
            }
        } else for (int x = 0; x < side; x++) for (int z = 0; z < side; z++) for (int y = 0; y < height; y++) {
            clipPos.set((clipX + x) * 16, (clipY + y) * 16, (clipZ + z) * 16);
            if (VoxyClient.vanilla(clipPos)) coverage.getPixels().setPixelRGBA(x, y * side + z, -1);
        }
        coverage.upload(); scene++;
    }
    private long depthScene() { return scene * 31 + client.level.getGameTime(); }
    private void forgetQuery(Section section) { queries.remove(section); hidden.remove(section); if (section.query != 0) glDeleteQueries(section.query); }
    private void retireQueries(Camera view) {
        for (var iterator = queries.iterator(); iterator.hasNext();) {
            Section section = iterator.next();
            if (glGetQueryObjecti(section.query, GL_QUERY_RESULT_AVAILABLE) == 0) continue;
            section.hidden = glGetQueryObjecti(section.query, GL_QUERY_RESULT) == 0; section.querying = false; queryCount++; iterator.remove();
            if (section.hidden) hidden.add(section); else hidden.remove(section);
            if (section.occluded || section.hidden && section.queryScene == depthScene() && view.same(section.queryCamera)) changed(section);
        }
    }
    private void queryBounds() {
        if (!stableCamera) return;
        if (box == null) try (ByteBufferBuilder bytes = new ByteBufferBuilder(1024)) {
            BufferBuilder builder = new BufferBuilder(bytes, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
            for (int corner : BOX) builder.addVertex(corner & 1, corner >> 2, (corner >> 1) & 1);
            box = new VertexBuffer(VertexBuffer.Usage.STATIC); box.bind(); box.upload(builder.buildOrThrow()); VertexBuffer.unbind();
        }
        boolean depth = glGetBoolean(GL_DEPTH_WRITEMASK), cull = glIsEnabled(GL_CULL_FACE), test = glIsEnabled(GL_DEPTH_TEST);
        int function = glGetInteger(GL_DEPTH_FUNC);
        try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
            var color = stack.malloc(4); glGetBooleanv(GL_COLOR_WRITEMASK, color);
            ShaderInstance bounds = GameRenderer.getPositionShader();
            RenderSystem.colorMask(false, false, false, false); RenderSystem.depthMask(false); RenderSystem.disableCull(); RenderSystem.enableDepthTest(); RenderSystem.depthFunc(GL_LEQUAL);
            try {
                box.bind();
                bounds.setDefaultUniforms(VertexFormat.Mode.QUADS, transform.identity(), camera.matrix, client.getWindow()); bounds.apply();
                for (Section section : demand.values()) {
                    Key key = section.key;
                    if (!section.needed || section.covering || camera.contains(key) || !Double.isFinite(section.diameter) || key.level() == 0 || section.children <= 0 || section.querying
                            || section.queryScene == depthScene() && camera.same(section.queryCamera)) continue;
                    if (section.query == 0) section.query = glGenQueries();
                    int size = key.size(); transform.translation((float)((long)key.x() * size - camera.position.x),
                            (float)((long)key.y() * size - camera.position.y), (float)((long)key.z() * size - camera.position.z)).scale(size);
                    if (bounds.MODEL_VIEW_MATRIX != null) { bounds.MODEL_VIEW_MATRIX.set(transform); bounds.MODEL_VIEW_MATRIX.upload(); }
                    section.querying = true; section.queryCamera = camera; section.queryScene = depthScene(); queries.add(section);
                    glBeginQuery(GL_ANY_SAMPLES_PASSED_CONSERVATIVE, section.query);
                    box.draw(); glEndQuery(GL_ANY_SAMPLES_PASSED_CONSERVATIVE);
                }
            } finally {
                VertexBuffer.unbind(); bounds.clear(); RenderSystem.depthMask(depth); RenderSystem.depthFunc(function);
                if (cull) RenderSystem.enableCull(); if (!test) RenderSystem.disableDepthTest();
                RenderSystem.colorMask(color.get(0) != 0, color.get(1) != 0, color.get(2) != 0, color.get(3) != 0);
            }
        }
    }
    private static boolean transparent(RenderType type) { return type.sortOnUpload(); }
    public void close() {
        closed = true; if (active == this) active = null;
        NeoForge.EVENT_BUS.unregister(renderListener); NeoForge.EVENT_BUS.unregister(fogListener); var waiting = eviction; if (waiting != null) waiting.complete(0L);
        Thread.ofVirtual().start(() -> {
            synchronized (meshLock) { scratch.close(); }
            client.execute(() -> {
                for (Section section : sections.values()) {
                    if (section.pending != null) release(section);
                    if (section.current != null) section.current.close(); forgetQuery(section);
                }
                sections.clear(); pending.clear(); inactive.clear(); prepared.clear(); selected.clear(); opaque.clear(); demand.clear(); changes.clear(); hidden.clear(); denied.clear(); indices.close();
                if (coverage != null) coverage.close(); if (box != null) box.close(); glfwDestroyWindow(uploadWindow);
            });
        });
    }
    private final class Snapshot implements BlockAndTintGetter {
        final Frame frame;
        final boolean actual;
        boolean forceBoundary;
        final Model[] palette;
        final Biome[] biomes;
        final Map<BlockPos, BlockEntity> entities;
        final List<Entity> coreEntities;
        Snapshot(Frame frame, boolean actual, Snapshot shared) {
            this.frame = frame; this.actual = actual;
            if (shared != null) { palette = shared.palette; biomes = shared.biomes; entities = shared.entities; coreEntities = shared.coreEntities; return; }
            palette = new Model[frame.states().length]; biomes = new Biome[palette.length];
            for (int i = 0; i < palette.length; i++) {
                palette[i] = modelCache.computeIfAbsent(frame.states()[i], state -> {
                    BakedModel baked = client.getBlockRenderer().getBlockModel(state);
                    boolean safe = enclosedSafe(state, baked);
                    return new Model(state, safe ? new CulledModel(baked) : baked, safe);
                });
                biomes[i] = biomeCache.computeIfAbsent(frame.biomes()[i], name -> client.level.registryAccess().registryOrThrow(Registries.BIOME).get(ResourceLocation.parse(name)));
                if (biomes[i] == null) throw new IllegalArgumentException("Unknown terrain biome: " + frame.biomes()[i]);
            }
            entities = new HashMap<>(); coreEntities = new ArrayList<>(); Key key = frame.key(); int size = key.size();
            for (int i = 0; i < frame.entities().size(); i++) {
                CompoundTag tag = frame.entities().getCompound(i).copy(); BlockPos pos = new BlockPos(tag.getInt("x"), tag.getInt("y"), tag.getInt("z"));
                BlockState state = NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), tag.getCompound("voxy_state"));
                BlockEntity entity = "DUMMY".equals(tag.getString("id"))
                        ? state.getBlock() instanceof EntityBlock factory ? factory.newBlockEntity(pos, state) : null
                        : BlockEntity.loadStatic(pos, state, tag, client.level.registryAccess());
                if (entity == null) continue;
                entity.setLevel(client.level); entities.put(pos, entity);
                if (Math.floorDiv(pos.getX(), size) == key.x() && Math.floorDiv(pos.getY(), size) == key.y() && Math.floorDiv(pos.getZ(), size) == key.z()) {
                    int light = frame.light()[frame.at(Math.floorDiv(pos.getX(), 1 << key.level()) - key.x() * 32,
                            Math.floorDiv(pos.getY(), 1 << key.level()) - key.y() * 32, Math.floorDiv(pos.getZ(), 1 << key.level()) - key.z() * 32)] & 255;
                    coreEntities.add(new Entity(entity, LightTexture.pack(light & 15, light >> 4)));
                }
            }
        }
        private int index(BlockPos pos) {
            Key key = frame.key(); int scale = actual ? 1 << key.level() : 1;
            int x = Math.floorDiv(pos.getX(), scale) - key.x() * 32, y = Math.floorDiv(pos.getY(), scale) - key.y() * 32, z = Math.floorDiv(pos.getZ(), scale) - key.z() * 32;
            return x < -1 || x > 32 || y < -1 || y > 32 || z < -1 || z > 32 ? -1 : frame.at(x, y, z);
        }
        boolean forceFace(BlockPos neighbor) {
            Key key = frame.key();
            return forceBoundary && ((neighbor.getX() >> 5) != key.x() || (neighbor.getY() >> 5) != key.y() || (neighbor.getZ() >> 5) != key.z());
        }
        public BlockState getBlockState(BlockPos pos) { int i = index(pos); return i < 0 ? Blocks.AIR.defaultBlockState() : palette[i].state; }
        public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
        public BlockEntity getBlockEntity(BlockPos pos) {
            if (actual) return entities.get(pos);
            int scale = 1 << frame.key().level(); return entities.get(new BlockPos(pos.getX() * scale, pos.getY() * scale, pos.getZ() * scale));
        }
        public int getHeight() { return Math.floorDiv(client.level.getHeight(), actual ? 1 : 1 << frame.key().level()); }
        public int getMinBuildHeight() { return Math.floorDiv(client.level.getMinBuildHeight(), actual ? 1 : 1 << frame.key().level()); }
        public float getShade(Direction side, boolean shade) { return client.level.getShade(side, shade); }
        public LevelLightEngine getLightEngine() { return client.level.getLightEngine(); }
        public int getBrightness(LightLayer layer, BlockPos pos) { int i = index(pos), light = i < 0 ? 240 : frame.light()[i] & 255; return layer == LightLayer.SKY ? light >> 4 : light & 15; }
        public int getRawBrightness(BlockPos pos, int subtraction) { return Math.max(getBrightness(LightLayer.BLOCK, pos), getBrightness(LightLayer.SKY, pos) - subtraction); }
        public int getBlockTint(BlockPos pos, ColorResolver resolver) {
            int i = index(pos), scale = actual ? 1 : 1 << frame.key().level();
            return resolver.getColor(biomes[i < 0 ? frame.at(0, 0, 0) : i], (long)pos.getX() * scale, (long)pos.getZ() * scale);
        }
    }
    private final class CulledModel extends BakedModelWrapper<BakedModel> {
        CulledModel(BakedModel model) { super(model); }
        @Override
        public List<BakedQuad> getQuads(BlockState state, Direction side, RandomSource random, ModelData data, RenderType type) {
            List<BakedQuad> quads = super.getQuads(state, side, random, data, type);
            Snapshot world = meshing;
            if (side == null || quads.isEmpty() || world == null) return quads;
            scratch.neighbor.set(scratch.pos).move(side);
            return world.forceFace(scratch.neighbor) || Block.shouldRenderFace(state, world, scratch.pos, side, scratch.neighbor) ? quads : List.of();
        }
    }
    private boolean enclosedSafe(BlockState state, BakedModel model) {
        if (state.hasBlockEntity() || state.hasOffsetFunction()) return false;
        if (model.getClass() == WeightedBakedModel.class) {
            for (var entry : ((VoxyMixins.WeightedModels)(Object)model).models()) if (!unitModel(state, entry.data())) return false;
            return true;
        }
        return unitModel(state, model);
    }
    private boolean unitModel(BlockState state, BakedModel model) {
        if (model.getClass() != SimpleBakedModel.class) return false;
        Direction[] faces = Arrays.copyOf(SIDES, 7);
        for (Direction face : faces) for (var quad : model.getQuads(state, face, scratch.random)) {
            int[] vertices = quad.getVertices(); int stride = vertices.length / 4;
            for (int i = 0; i < 4; i++) for (int axis = 0; axis < 3; axis++) {
                float value = Float.intBitsToFloat(vertices[i * stride + axis]);
                if (!Float.isFinite(value) || value < 0 || value > 1) return false;
            }
        }
        return true;
    }
    private static final class Scratch implements AutoCloseable {
        final ByteBufferBuilder indices = new ByteBufferBuilder(4096);
        final PoseStack pose = new PoseStack();
        final RandomSource random = RandomSource.create(0);
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(), actual = new BlockPos.MutableBlockPos(), neighbor = new BlockPos.MutableBlockPos();
        final FluidConsumer fluid = new FluidConsumer();
        final Map<RenderType, ByteBufferBuilder> storage = new HashMap<>();
        final Map<RenderType, BufferBuilder> buffers = new LinkedHashMap<>();
        void begin() { buffers.clear(); }
        BufferBuilder buffer(RenderType type) { return buffers.computeIfAbsent(type, key -> new BufferBuilder(storage.computeIfAbsent(key, ignored -> new ByteBufferBuilder(65536)), key.mode(), key.format())); }
        List<Layer> finish() { List<Layer> result = new ArrayList<>(); buffers.forEach((type, builder) -> { MeshData mesh = builder.build(); if (mesh != null) result.add(new Layer(type, mesh)); }); return result; }
        void discard() { buffers.forEach((type, builder) -> { MeshData mesh = builder.build(); if (mesh != null) mesh.close(); }); }
        public void close() { storage.values().forEach(ByteBufferBuilder::close); storage.clear(); indices.close(); }
    }
    private static final class FluidConsumer implements VertexConsumer {
        VertexConsumer output; int x, y, z, scale;
        public VertexConsumer addVertex(float vx, float vy, float vz) { output.addVertex((vx + x) * scale, (vy + y) * scale, (vz + z) * scale); return this; }
        public VertexConsumer setColor(int r, int g, int b, int a) { output.setColor(r, g, b, a); return this; }
        public VertexConsumer setUv(float u, float v) { output.setUv(u, v); return this; }
        public VertexConsumer setUv1(int u, int v) { output.setUv1(u, v); return this; }
        public VertexConsumer setUv2(int u, int v) { output.setUv2(u, v); return this; }
        public VertexConsumer setNormal(float x, float y, float z) { output.setNormal(x, y, z); return this; }
    }
    private final class Camera {
        final Vec3 position;
        final Matrix4f matrix;
        final FrustumIntersection frustum;
        final int width = client.getMainRenderTarget().viewWidth, height = client.getMainRenderTarget().viewHeight;
        final int depth = client.getMainRenderTarget().getDepthTextureId();
        final float[] px = new float[8], py = new float[8];
        Camera(Vec3 position, Matrix4f view, Matrix4f projection) { this.position = position; matrix = new Matrix4f(projection).mul(view); frustum = new FrustumIntersection(matrix); }
        boolean same(Camera other) { return other != null && position.equals(other.position) && matrix.equals(other.matrix) && width == other.width && height == other.height && depth == other.depth; }
        boolean visible(Key key) {
            int size = key.size(); float x = (float)((long)key.x() * size - position.x), y = (float)((long)key.y() * size - position.y), z = (float)((long)key.z() * size - position.z);
            return frustum.testAab(x, y, z, x + size, y + size, z + size);
        }
        private double project(Key key) {
            float minX = 1, minY = 1, maxX = 0, maxY = 0; int size = key.size();
            for (int i = 0; i < 8; i++) {
                float x = (float)((long)key.x() * size - position.x) + (i & 1) * size;
                float y = (float)((long)key.y() * size - position.y) + ((i >> 2) & 1) * size;
                float z = (float)((long)key.z() * size - position.z) + ((i >> 1) & 1) * size;
                float w = matrix.m03() * x + matrix.m13() * y + matrix.m23() * z + matrix.m33();
                if (w <= 0 || matrix.m02() * x + matrix.m12() * y + matrix.m22() * z + matrix.m32() <= -w) return Double.POSITIVE_INFINITY;
                px[i] = (matrix.m00() * x + matrix.m10() * y + matrix.m20() * z + matrix.m30()) / w * .5f + .5f;
                py[i] = (matrix.m01() * x + matrix.m11() * y + matrix.m21() * z + matrix.m31()) / w * .5f + .5f;
                minX = Math.min(minX, px[i]); minY = Math.min(minY, py[i]); maxX = Math.max(maxX, px[i]); maxY = Math.max(maxY, py[i]);
            }
            double area = cross(0, 1, 4) + cross(0, 1, 2) + cross(0, 4, 2) + cross(7, 6, 3) + cross(7, 6, 5) + cross(7, 3, 5);
            double center = 1 - Math.min(1, Math.hypot((Math.clamp(minX, 0, 1) + Math.clamp(maxX, 0, 1) - 1) * .5,
                    (Math.clamp(minY, 0, 1) + Math.clamp(maxY, 0, 1) - 1) * .5) * Math.sqrt(2));
            return Math.sqrt(area * .5 * width * height) * (1 + .25 * center);
        }
        private double cross(int a, int b, int c) { return Math.abs((px[b] - px[a]) * (py[c] - py[a]) - (px[c] - px[a]) * (py[b] - py[a])); }
        double horizontalDistance(Key key) { return axis(position.x, (long)key.x() * key.size(), key.size()) + axis(position.z, (long)key.z() * key.size(), key.size()); }
        double distance(Key key) { return horizontalDistance(key) + axis(position.y, (long)key.y() * key.size(), key.size()); }
        boolean contains(Key key) { return distance(key) == 0; }
        private double axis(double value, long start, int size) { double distance = Math.max(Math.max(start - value, value - start - size), 0); return distance * distance; }
    }
}
