package me.cortex.voxy.client.lod;

import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/** Stable no-op API used by production builds; the debug JAR replaces this class. */
final class ClientLodDebug {
    private ClientLodDebug() {}

    static void register(PayloadRegistrar registrar) {}
    static void tick() {}
    static long timer() { return 0; }
    static void minecraftDisconnect() {}
    static void networkStart(long session, String dimension) {}
    static void networkFailure(Throwable failure) {}
    static void serverHello(long id, boolean restart, boolean resetSections, int blockEpoch, int biomeEpoch) {}
    static void mappingDelta(int blocks, int biomes, long startedNanos) {}
    static void transportResponse(long startedNanos, byte mode, int protocol) {}
    static void transportOpen(boolean direct, String description) {}
    static void bridgeInputOverflow(int queued) {}
    static void bridgeIn(int bytes) {}
    static void bridgeOut(int bytes) {}
    static void rustFrame(short type, int bytes) {}
    static void sectionApplied() {}
    static void invalidationApplied() {}
    static void droppedUnsubscribed() {}
    static void droppedRevision() {}
    static void subscriptionBatch(int additions, int removals) {}
    static void credit(long bytes) {}
    static void reset() {}
}
