package me.cortex.voxy.client.lod;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.bus.api.SubscribeEvent;

/** Client facade for renderer demand and immutable-object residency. */
public final class ClientLodClient {
    private ClientLodClient() {}

    public static void init(IEventBus modBus) {
        ClientLodTransport.register(modBus);
        NeoForge.EVENT_BUS.register(ClientLodClient.class);
    }

    @SubscribeEvent
    public static void onDisconnect(ClientPlayerNetworkEvent.LoggingOut event) {
        disconnect();
    }

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post event) {
        ClientSession.tick();
    }

    public static void disconnect() {
        ClientSession.disconnect();
    }

    /** Supplies only the bounded LOD4 metadata window; terrain requests still come from the GPU. */
    public static void metadataRootEntered(long key) {
        ClientSession.metadataRootEntered(key);
    }

    public static void metadataRootLeft(long key) {
        ClientSession.metadataRootLeft(key);
    }

    public static void resetDemand() {
        ClientSession.resetDemand();
    }

    /** Invalidates geometry identity when resource/model/renderer state is recreated. */
    public static void rendererLifecycleChanged() {
        ClientSession.rendererLifecycleChanged();
    }

    static int debugDesiredSections() {
        return ClientSession.debugDesiredSections();
    }

    static int debugPendingSections() {
        return ClientSession.debugPendingSections();
    }

    static int debugInboundFrames() {
        return ClientSession.debugInboundFrames();
    }

    static long debugInboundKiB() {
        return ClientSession.debugInboundKiB();
    }

    static int debugMasterDesiredSections() { return ClientSession.debugDesiredSections(); }
    static int debugMaximumDemand() { return ClientSession.maximumMetadataRoots(); }

}
