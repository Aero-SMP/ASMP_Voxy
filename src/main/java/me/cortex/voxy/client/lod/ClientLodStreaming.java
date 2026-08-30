package me.cortex.voxy.client.lod;

import me.cortex.voxy.commonImpl.lod.LodGenerationService;
import me.cortex.voxy.commonImpl.lod.LodStreamingConfig;
import me.cortex.voxy.commonImpl.lod.LodStreamingService;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

public class ClientLodStreaming {

    public ClientLodStreaming(IEventBus modEventBus) {
        LodStreamingService.LOGGER.info("Client LOD streaming initializing");

        modEventBus.addListener(ClientLodStreaming::registerGuiLayers);

        LodGenerationService.getInstance().setPauseCheck(() -> {
            Minecraft mc = Minecraft.getInstance();
            return mc != null && mc.isPaused();
        });

        NeoForge.EVENT_BUS.register(ClientLodStreaming.class);
    }

    private static void registerGuiLayers(RegisterGuiLayersEvent event) {
        event.registerAboveAll(
                ResourceLocation.fromNamespaceAndPath(LodStreamingService.NAMESPACE, "lod_streaming_debug"),
                ClientLodStreaming::render
        );
    }

    @SubscribeEvent
    public static void onDisconnect(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientLodNetwork.disconnect();
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        ClientLodNetwork.tick();
    }

    private static void render(GuiGraphics graphics, DeltaTracker tickDelta) {
        if (!LodStreamingConfig.DATA.showF3MenuStats) return;

        Minecraft mc = Minecraft.getInstance();
        if (!mc.getDebugOverlay().showDebugScreen()) return;

        Font font = mc.font;
        int screenWidth = mc.getWindow().getGuiScaledWidth();
        int screenHeight = mc.getWindow().getGuiScaledHeight();
        int lineHeight = font.lineHeight + 2;

        LodGenerationService manager = LodGenerationService.getInstance();
        double rate = manager.getChunksPerSecond();
        int remaining = manager.getRemainingInRadius();
        String eta = "--";
        if (rate > 0.1 && remaining > 0) {
            int seconds = (int) (remaining / rate);
            if (seconds < 60) {
                eta = seconds + "s";
            } else if (seconds < 3600) {
                eta = (seconds / 60) + "m " + (seconds % 60) + "s";
            } else {
                eta = (seconds / 3600) + "h " + ((seconds % 3600) / 60) + "m";
            }
        } else if (remaining == 0) {
            eta = "done";
        }

        String status = manager.isThrottled() ? "§cthrottled (low tps)"
                : remaining == 0 ? "§adone" : "§arunning";

        boolean isLocal = mc.getSingleplayerServer() != null;
        boolean isVoxyServer = ClientLodNetwork.isServerConnected();
        List<String> lines = new ArrayList<>();
        String voxyStatus = LodStreamingService.isIngestionAvailable() ? "§aenabled" : "§cdisabled";

        if (isLocal) {
            lines.add("§6[voxy lod streaming] " + status);
            lines.add("§7completed: §a" + formatNumber(manager.getCompleted()));
            lines.add("§7skipped: §f" + formatNumber(manager.getSkipped()));
            lines.add("§7remaining: §e" + formatNumber(remaining) + " §8(" + eta + ")");
            lines.add("§7active: §b" + manager.getActiveTaskCount());
            lines.add("§7rate: §f" + String.format("%.1f", rate) + " c/s");
            lines.add("§7voxy: " + voxyStatus);
        } else if (isVoxyServer) {
            lines.add("§6[voxy lod streaming] §aconnected");
            lines.add("§7rate: §f" + String.format("%.1f", ClientLodNetwork.getReceiveRate()) + " c/s");
            lines.add("§7bandwidth: §f" + formatBytes((long) ClientLodNetwork.getBandwidthRate()) + "/s");
            lines.add("§7received: §b" + formatNumber(ClientLodNetwork.getChunksReceived()) + " §8(" + formatBytes(ClientLodNetwork.getBytesReceived()) + ")");
            lines.add("§7voxy: " + voxyStatus);
        } else {
            lines.add("§6[voxy lod streaming] §7server: §coffline");
            lines.add("§7voxy: " + voxyStatus);
        }

        int y = screenHeight - (lines.size() * lineHeight) - 4;
        int maxWidth = 0;
        for (String line : lines) maxWidth = Math.max(maxWidth, font.width(line));

        for (String line : lines) {
            int x = screenWidth - font.width(line) - 4;
            int bgX = screenWidth - maxWidth - 6;
            graphics.fill(bgX, y - 1, screenWidth - 2, y + font.lineHeight, 0x90505050);
            graphics.drawString(font, line, x, y, 0xFFFFFFFF, false);
            y += lineHeight;
        }
    }

    private static String formatNumber(long number) {
        if (number >= 1_000_000) return String.format("%.1fM", number / 1_000_000.0);
        if (number >= 1_000) return String.format("%.1fK", number / 1_000.0);
        return String.valueOf(number);
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String pre = "KMGTPE".charAt(exp - 1) + "B";
        return String.format("%.1f %s", bytes / Math.pow(1024, exp), pre);
    }
}
