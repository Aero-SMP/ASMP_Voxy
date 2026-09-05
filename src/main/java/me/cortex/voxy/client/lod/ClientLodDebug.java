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
    static void captureSession(ClientSession.Session session) {}
    static String sessionSnapshot(ClientSession.Session session) { return ""; }
    static Object workerCreated(ClientSession.Session session, int slot, Thread thread) { return null; }
    static void workerBegin(Object state, ClientSession.Session.WorkerTask task, WorkerResource.Lease lease) {}
    static void workerStage(Object state, String stage) {}
    static void workerOutcome(Object state, String outcome, long bytes) {}
    static void workerEnd(Object state) {}
    static void workerClosing(Object state) {}
    static void workerEvidence(String message) {}
    static void shaderArtifact(String text) {}
    public static void shaderBegin(me.cortex.voxy.client.core.VoxyRenderSystem renderer, Object pipeline, long oldResources, long newResources) {}
    public static void shaderClassification(me.cortex.voxy.client.core.VoxyRenderSystem renderer,
            me.cortex.voxy.client.core.model.ModelFactory models, java.util.Map<?, ?> oldMap, java.util.Map<?, ?> newMap, Object pipeline) {}
    public static void shaderEnd(me.cortex.voxy.client.core.VoxyRenderSystem renderer, String outcome, String reason) {}
    public static void materialDecision(me.cortex.voxy.client.core.model.ModelFactory models,
            it.unimi.dsi.fastutil.objects.Object2IntMap<net.minecraft.world.level.block.state.BlockState> mapping, String outcome) {}
    public static void captureRender(int frameId, int geometrySections,
                                     int renderListBuffer, int drawCountBuffer) {}
}
