package me.cortex.voxy.client.lod;

import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/** Stable no-op facade; the debug JAR replaces this class at packaging time. */
final class ClientLodDebug {
    private ClientLodDebug() {}

    static void register(PayloadRegistrar registrar) {}
    static void tick() {}
    static long timer() { return 0; }
    static void transportResponse(long startedNanos, byte mode) {}
    static void bridgeInputOverflow(int queued) {}
    static void bridgeIn(int bytes) {}
    static void bridgeOut(int bytes) {}

    static void sessionStarted(long session, String dimension) {}
    static void sessionFailed(Throwable failure) {}
    static void transportOpened(boolean direct, String description) {}
    static void frame(int type, int bytes) {}
    static void hello(long serverInstance) {}
    static void credit(long bytes) {}
    static void rootAnnounced(long generation) {}
    static void objectDecoded(int kind, int compressedBytes, boolean cacheHit) {}
    static void selection(long generation, long snapshot, int frontier, int requests,
                            boolean complete) {}
    static void activationPass(int candidates) {}
    static void activationNoBinding() {}
    static void activationEmptyCut() {}
    static void activationMissing(int requestable, int neighbors) {}
    static void activationPressure() {}
    static void activationCompiled(long key, long generation) {}
    static void activationQueued(long key, long generation) {}
    static void activationVisible(long key, long generation) {}
    static void activationRetired(long key, long generation) {}
    static void rootActivated(long generation) {}
    static void failure(String reason) {}
}
