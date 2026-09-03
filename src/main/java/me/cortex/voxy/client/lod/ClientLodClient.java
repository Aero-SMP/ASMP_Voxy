package me.cortex.voxy.client.lod;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.bus.api.SubscribeEvent;

/** Client facade for renderer-driven regional section demand. */
public final class ClientLodClient {
    private ClientLodClient() {}

    public static void init(IEventBus modBus) {
        ClientLodDebug.init();
        QuicEndpointDiscovery.register(modBus);
        NeoForge.EVENT_BUS.register(ClientLodClient.class);
    }

    @SubscribeEvent
    public static void onDisconnect(ClientPlayerNetworkEvent.LoggingOut event) {
        disconnect();
    }

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post event) {
        ClientSession.tick();
        ClientLodDebug.tick();
    }

    public static void disconnect() {
        ClientSession.disconnect();
    }

    /** Supplies the bounded LOD4 coverage window; finer demand still comes from the GPU. */
    public static void sectionEntered(long key) {
        ClientSession.sectionEntered(key);
    }

    public static void sectionLeft(long key) {
        ClientSession.sectionLeft(key);
    }

    /** Accepts projected-size refinement requests from the renderer's normal hierarchy. */
    public static void refinementRequested(long parent) {
        ClientSession.refinementRequested(parent);
    }

    /** Collapses renderer-selected detail after the final HZB pass grace period. */
    public static void coarseningRequested(long parent) {
        ClientSession.coarseningRequested(parent);
    }

    public static void resetDemand() {
        ClientSession.resetDemand();
    }

    /** Invalidates geometry identity when resource/model/renderer state is recreated. */
    public static void rendererLifecycleChanged() {
        ClientSession.rendererLifecycleChanged();
    }
}
