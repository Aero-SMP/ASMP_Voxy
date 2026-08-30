package me.cortex.voxy.client.lod;

import me.cortex.voxy.commonImpl.lod.LodGenerationService;
import me.cortex.voxy.commonImpl.lod.LodStreamingService;
import net.minecraft.client.Minecraft;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

public class ClientLodStreaming {

    public ClientLodStreaming() {
        LodStreamingService.LOGGER.info("Client LOD streaming initializing");

        LodGenerationService.getInstance().setPauseCheck(() -> {
            Minecraft mc = Minecraft.getInstance();
            return mc != null && mc.isPaused();
        });

        NeoForge.EVENT_BUS.register(ClientLodStreaming.class);
    }

    @SubscribeEvent
    public static void onDisconnect(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientLodNetwork.disconnect();
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        ClientLodNetwork.tick();
    }
}
