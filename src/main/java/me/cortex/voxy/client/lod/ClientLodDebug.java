package me.cortex.voxy.client.lod;

import net.neoforged.bus.api.IEventBus;

/** No-op facade replaced by the compile-time debug implementation in debug client JARs. */
public final class ClientLodDebug {
    private ClientLodDebug() {}

    static void init(IEventBus modBus) {}
    static void tick() {}
    static boolean connectionAllowed() { return true; }
    static void startupEvent(ClientSession.Session session, String event, long bytes) {}
    static void admissionReleased(ClientSession.Session session, long meshCompletedNanos) {}
    static String startupSnapshot(ClientSession.Session session) { return ""; }
    public static void captureRender(int frameId, int geometrySections,
                                     int renderListBuffer, int drawCountBuffer) {}
}
