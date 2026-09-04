package me.cortex.voxy.client.lod;

import net.neoforged.bus.api.IEventBus;

/** No-op facade replaced by the compile-time debug implementation in debug client JARs. */
public final class ClientLodDebug {
    private ClientLodDebug() {}

    static void init(IEventBus modBus) {}
    static void tick() {}
    public static void captureRender(int frameId, int geometrySections,
                                     int renderListBuffer, int drawCountBuffer) {}
}
