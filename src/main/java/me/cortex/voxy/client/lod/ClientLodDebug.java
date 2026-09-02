package me.cortex.voxy.client.lod;

/** No-op facade replaced by the compile-time debug implementation in debug client JARs. */
public final class ClientLodDebug {
    private ClientLodDebug() {}

    static boolean diagnosticsEnabled() { return false; }
    static void tick() {}
    static void sessionStarted(long session, String dimension) {}
    static void sessionFailed(Throwable failure) {}
    static String latestSnapshot() { return "blocker=DEBUG_DISABLED"; }
    static void snapshotCaptured(String snapshot) {}
    static void activationPass(int candidates, int busy, int missingBinding,
                               int noCompatibleContent, int missingContent,
                               int missingNeighbors, int modelsPending,
                               int pendingModelId, int stageBlocked, int pinBlocked,
                               int workerSaturated, int alreadyActive, int submitted,
                               ManifestCodec.SpatialNode sampleNode,
                               WireMessage.Hash256 sampleHash) {}
    static String activationSummary() { return "disabled"; }
    static String activationBlocker() { return null; }
    public static void captureRender(int frameId, int geometrySections,
                                     int renderListBuffer, int drawCountBuffer) {}
    static String renderSummary() { return "disabled"; }
    static String renderBlocker(int activePublications) { return null; }
}
