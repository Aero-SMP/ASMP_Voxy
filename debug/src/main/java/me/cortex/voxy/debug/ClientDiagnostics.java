package me.cortex.voxy.debug;

import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.lod.ClientLodNetwork;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.lod.LodGenerationService;
import me.cortex.voxy.commonImpl.lod.LodStreamingService;
import net.minecraft.ChatFormatting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.lwjgl.opengl.ARBTimerQuery.GL_TIMESTAMP;
import static org.lwjgl.opengl.ARBTimerQuery.glQueryCounter;
import static org.lwjgl.opengl.GL15.glDeleteQueries;
import static org.lwjgl.opengl.GL15.glGenQueries;
import static org.lwjgl.opengl.GL15C.GL_QUERY_RESULT;
import static org.lwjgl.opengl.GL15C.GL_QUERY_RESULT_AVAILABLE;
import static org.lwjgl.opengl.GL15C.GL_TRUE;
import static org.lwjgl.opengl.GL15C.glGetQueryObjecti;
import static org.lwjgl.opengl.GL33.glGetQueryObjecti64;

public final class ClientDiagnostics {
    private static final ThreadLocal<Long> FRAME_START = new ThreadLocal<>();
    private static volatile double frameMilliseconds;

    private static final ArrayDeque<int[]> GPU_QUERIES = new ArrayDeque<>();
    private static int gpuStartQuery;
    private static double gpuMilliseconds;

    private static final AtomicInteger BUFFERS = new AtomicInteger();
    private static final AtomicLong BUFFER_BYTES = new AtomicLong();
    private static final AtomicInteger TEXTURES = new AtomicInteger();
    private static final AtomicLong TEXTURE_BYTES = new AtomicLong();

    private static long chunks;
    private static long bytes;
    private static long previousChunks;
    private static long previousBytes;
    private static long networkSampledAt = System.nanoTime();
    private static double chunksPerSecond;
    private static double bytesPerSecond;

    private ClientDiagnostics() {}

    static void register(RegisterGuiLayersEvent event) {
        event.registerAboveAll(ResourceLocation.fromNamespaceAndPath("voxy_debug", "lod_streaming"),
                ClientDiagnostics::renderOverlay);
    }

    public static void appendF3(List<String> lines) {
        if (!VoxyCommon.isAvailable()) {
            lines.add(ChatFormatting.RED + "voxy-" + VoxyCommon.MOD_VERSION);
            return;
        }

        Object instance = VoxyCommon.getInstance();
        if (instance == null) {
            lines.add(ChatFormatting.YELLOW + "voxy-" + VoxyCommon.MOD_VERSION);
            return;
        }

        Object renderer = null;
        var levelRenderer = Minecraft.getInstance().levelRenderer;
        if (levelRenderer != null) {
            renderer = ((IGetVoxyRenderSystem) levelRenderer).voxy$getRenderSystem();
        }
        lines.add((renderer == null ? ChatFormatting.DARK_GREEN : ChatFormatting.GREEN)
                + "voxy-" + VoxyCommon.MOD_VERSION);

        Object worlds = Reflection.field(instance, "activeWorlds");
        int worldCount = worlds instanceof Map<?, ?> map ? map.size() : 0;
        long ingest = Reflection.number(Reflection.invoke(Reflection.field(instance, "ingestService"), "getTaskCount"));
        long saves = Reflection.number(Reflection.invoke(Reflection.field(instance, "savingService"), "getTaskCount"));
        lines.add("I/S/W: " + ingest + "/" + saves + "/" + worldCount);

        if (renderer == null) return;
        lines.add("Buf/Tex [#/MB]: [" + BUFFERS.get() + "/" + BUFFER_BYTES.get() / 1_000_000
                + "],[" + TEXTURES.get() + "/" + TEXTURE_BYTES.get() / 1_000_000 + "]");
        lines.add(String.format("Voxy frame: %.3f ms", frameMilliseconds));
        lines.add(String.format("Voxy GPU: %.3f ms", gpuMilliseconds));

        Object geometry = Reflection.field(renderer, "geometryData");
        long sections = Reflection.number(Reflection.invoke(geometry, "getSectionCount"));
        long used = Reflection.number(Reflection.invoke(geometry, "getGeometryUsedBytes"));
        long capacity = Reflection.number(Reflection.invoke(geometry, "getGeometryCapacity"));
        lines.add("Geometry: " + sections + " sections, " + used / (1 << 20) + "/"
                + capacity / (1 << 20) + " MiB");

        Object renderGeneration = Reflection.field(renderer, "renderGen");
        long renderTasks = Reflection.number(Reflection.invoke(renderGeneration, "getTaskCount"));
        Object nodeManager = Reflection.field(renderer, "nodeManager");
        long nodes = Reflection.number(Reflection.invoke(nodeManager, "getCurrentMaxNodeId"));
        Object pipeline = Reflection.field(renderer, "pipeline");
        lines.add("Render tasks/nodes: " + renderTasks + "/" + nodes);
        if (pipeline != null) lines.add("Pipeline: " + pipeline.getClass().getSimpleName());
    }

    public static void startFrame() {
        FRAME_START.set(System.nanoTime());
        collectGpuQueries();
        gpuStartQuery = glGenQueries();
        glQueryCounter(gpuStartQuery, GL_TIMESTAMP);
    }

    public static void finishFrame() {
        Long start = FRAME_START.get();
        if (start != null) {
            FRAME_START.remove();
            double elapsed = (System.nanoTime() - start) / 1_000_000.0;
            frameMilliseconds = Math.max(frameMilliseconds * 0.96 + elapsed * 0.04, elapsed);
        }
        if (gpuStartQuery == 0) return;
        int endQuery = glGenQueries();
        glQueryCounter(endQuery, GL_TIMESTAMP);
        GPU_QUERIES.addLast(new int[]{gpuStartQuery, endQuery});
        gpuStartQuery = 0;
    }

    public static void clearGpuQueries() {
        if (gpuStartQuery != 0) {
            glDeleteQueries(gpuStartQuery);
            gpuStartQuery = 0;
        }
        while (!GPU_QUERIES.isEmpty()) {
            int[] pair = GPU_QUERIES.removeFirst();
            glDeleteQueries(pair[0]);
            glDeleteQueries(pair[1]);
        }
    }

    private static void collectGpuQueries() {
        while (!GPU_QUERIES.isEmpty()) {
            int[] pair = GPU_QUERIES.peekFirst();
            if (glGetQueryObjecti(pair[1], GL_QUERY_RESULT_AVAILABLE) != GL_TRUE) return;
            GPU_QUERIES.removeFirst();
            double elapsed = (glGetQueryObjecti64(pair[1], GL_QUERY_RESULT)
                    - glGetQueryObjecti64(pair[0], GL_QUERY_RESULT)) / 1_000_000.0;
            gpuMilliseconds = Math.max(gpuMilliseconds * 0.96 + elapsed * 0.04, elapsed);
            glDeleteQueries(pair[0]);
            glDeleteQueries(pair[1]);
        }
    }

    public static void addBuffer(long size) {
        BUFFERS.incrementAndGet();
        BUFFER_BYTES.addAndGet(size);
    }

    public static void removeBuffer(long size) {
        BUFFERS.decrementAndGet();
        BUFFER_BYTES.addAndGet(-size);
    }

    public static void addTexture() {
        TEXTURES.incrementAndGet();
    }

    public static void allocateTexture(long size) {
        TEXTURE_BYTES.addAndGet(size);
    }

    public static void removeTexture(long size) {
        TEXTURES.decrementAndGet();
        TEXTURE_BYTES.addAndGet(-size);
    }

    public static synchronized void received(long payloadBytes) {
        chunks++;
        bytes += payloadBytes;
        sampleNetwork();
    }

    public static synchronized void resetNetwork() {
        chunks = bytes = previousChunks = previousBytes = 0;
        chunksPerSecond = bytesPerSecond = 0;
        networkSampledAt = System.nanoTime();
    }

    private static void sampleNetwork() {
        long now = System.nanoTime();
        double seconds = (now - networkSampledAt) / 1_000_000_000.0;
        if (seconds < 1.0) return;
        chunksPerSecond = (chunks - previousChunks) / seconds;
        bytesPerSecond = (bytes - previousBytes) / seconds;
        previousChunks = chunks;
        previousBytes = bytes;
        networkSampledAt = now;
    }

    private static synchronized long chunks() { return chunks; }
    private static synchronized long bytes() { return bytes; }
    private static synchronized double chunksPerSecond() { sampleNetwork(); return chunksPerSecond; }
    private static synchronized double bytesPerSecond() { sampleNetwork(); return bytesPerSecond; }

    private static void renderOverlay(GuiGraphics graphics, DeltaTracker tickDelta) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.getDebugOverlay().showDebugScreen()) return;

        List<String> lines = new ArrayList<>();
        String ingestion = LodStreamingService.isIngestionAvailable() ? "§aenabled" : "§cdisabled";
        if (minecraft.getSingleplayerServer() != null) {
            Object service = LodGenerationService.getInstance();
            long active = Reflection.number(Reflection.field(service, "activeTaskCount"));
            double load = Reflection.field(service, "loadFactor") instanceof Number number
                    ? number.doubleValue() : 1.0;
            String status = load <= 0 ? "§cthrottled" : "§arunning";
            lines.add("§6[voxy generation] " + status);
            lines.add("§7processed: §a" + formatNumber(ServerDiagnostics.count()));
            lines.add("§7failed: §c" + formatNumber(ServerDiagnostics.failures()));
            lines.add("§7active: §b" + active);
            lines.add("§7rate: §f" + String.format("%.1f", ServerDiagnostics.perSecond()) + " c/s");
        } else if (ClientLodNetwork.isServerConnected()) {
            lines.add("§6[voxy streaming] §aconnected");
            lines.add("§7rate: §f" + String.format("%.1f", chunksPerSecond()) + " c/s");
            lines.add("§7bandwidth: §f" + formatBytes((long) bytesPerSecond()) + "/s");
            lines.add("§7received: §b" + formatNumber(chunks()) + " §8("
                    + formatBytes(bytes()) + ")");
        } else {
            lines.add("§6[voxy streaming] §7server: §coffline");
        }
        lines.add("§7voxy: " + ingestion);

        var font = minecraft.font;
        int screenWidth = minecraft.getWindow().getGuiScaledWidth();
        int lineHeight = font.lineHeight + 2;
        int y = minecraft.getWindow().getGuiScaledHeight() - lines.size() * lineHeight - 4;
        int maxWidth = lines.stream().mapToInt(font::width).max().orElse(0);
        for (String line : lines) {
            int x = screenWidth - font.width(line) - 4;
            graphics.fill(screenWidth - maxWidth - 6, y - 1, screenWidth - 2,
                    y + font.lineHeight, 0x90505050);
            graphics.drawString(font, line, x, y, 0xFFFFFFFF, false);
            y += lineHeight;
        }
    }

    private static String formatNumber(long number) {
        if (number >= 1_000_000) return String.format("%.1fM", number / 1_000_000.0);
        if (number >= 1_000) return String.format("%.1fK", number / 1_000.0);
        return Long.toString(number);
    }

    private static String formatBytes(long value) {
        if (value < 1024) return value + " B";
        int exponent = Math.min((int) (Math.log(value) / Math.log(1024)), 6);
        return String.format("%.1f %sB", value / Math.pow(1024, exponent),
                "KMGTPE".charAt(exponent - 1));
    }
}
