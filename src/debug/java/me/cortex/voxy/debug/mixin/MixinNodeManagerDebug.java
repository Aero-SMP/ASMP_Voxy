package me.cortex.voxy.debug.mixin;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import me.cortex.voxy.client.core.rendering.building.BuiltSection;
import me.cortex.voxy.client.core.rendering.SectionUpdateRouter;
import me.cortex.voxy.client.core.rendering.hierachical.NodeManager;
import me.cortex.voxy.client.core.rendering.hierachical.NodeStore;
import me.cortex.voxy.client.core.util.ExpandingObjectAllocationList;
import me.cortex.voxy.client.lod.ClientLodNetwork;
import me.cortex.voxy.debug.LodAudit;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Debug-only CPU root and completed-geometry lifecycle hooks. */
@Mixin(value = NodeManager.class, remap = false)
abstract class MixinNodeManagerDebug {
    @Shadow @Final private Long2IntOpenHashMap activeSectionMap;
    @Shadow @Final private NodeStore nodeData;
    @Shadow @Final private SectionUpdateRouter watcher;
    @Shadow @Final private LongOpenHashSet topLevelNodes;
    @Shadow @Final private ExpandingObjectAllocationList<?> topLevelRequests;
    @Shadow @Final private ExpandingObjectAllocationList<?> childRequests;

    @Inject(method = "insertTopLevelNode", at = @At("RETURN"))
    private void voxy$debugTopLevelRequest(long position, CallbackInfo ci) {
        voxy$snapshot(position, "top-level-request");
    }

    @Inject(method = "finishTopLevelRequest", at = @At("RETURN"))
    private void voxy$debugRootReady(int requestId, @Coerce Object request, CallbackInfo ci) {
        long position = ((NodeRequestAccess) request).voxy$position();
        int encoded = activeSectionMap.get(position);
        LodAudit.rootCpuReady(position, encoded & NodeManager.NODE_ID_MSK);
        voxy$snapshot(position, "top-level-ready");
    }

    @Inject(method = "processGeometryResult", at = @At("RETURN"))
    private void voxy$debugGeometryBuilt(BuiltSection section, CallbackInfo ci) {
        LodAudit.geometryBuilt(section.position, section.isEmpty());
        voxy$snapshot(section.position, "geometry-result");
    }

    @Inject(method = "processChildChange", at = @At("RETURN"))
    private void voxy$debugChildChange(long position, byte childExistence, CallbackInfo ci) {
        voxy$snapshot(position, "child-change");
    }

    @Inject(method = "processRequest", at = @At("RETURN"))
    private void voxy$debugProcessRequest(long position, CallbackInfo ci) {
        voxy$snapshot(position, "process-request");
    }

    @Inject(method = "finishRequest", at = @At("RETURN"))
    private void voxy$debugFinishRequest(int requestId, @Coerce Object request, CallbackInfo ci) {
        voxy$snapshot(((NodeRequestAccess) request).voxy$position(), "finish-request");
    }

    private void voxy$snapshot(long position, String phase) {
        int encoded = activeSectionMap.get(position);
        int type = encoded == -1 ? -1 : encoded >>> 30;
        int nodeId = encoded == -1 ? -1 : encoded & NodeManager.NODE_ID_MSK;
        int watcherFlags = watcher.get(position);
        boolean resolved = ClientLodNetwork.isSectionResolved(position);
        boolean topLevel = topLevelNodes.contains(position);
        int childMask = -1;
        int geometry = -3;
        int childPtr = -2;
        int childCount = 0;
        boolean requestInFlight = false;
        int requestId = -1;
        boolean geometryInFlight = false;
        int requiredMask = -1;
        int meshMask = -1;
        int existenceMask = -1;

        if (type == 0 || type == 1) {
            if (nodeData.nodeExists(nodeId)) {
                childMask = Byte.toUnsignedInt(nodeData.getNodeChildExistence(nodeId));
                geometry = nodeData.getNodeGeometry(nodeId);
                childPtr = nodeData.getChildPtr(nodeId);
                if (childPtr >= 0) childCount = nodeData.getChildPtrCount(nodeId);
                requestInFlight = nodeData.isNodeRequestInFlight(nodeId);
                geometryInFlight = nodeData.isNodeGeometryInFlight(nodeId);
                if (requestInFlight) {
                    requestId = nodeData.getNodeRequest(nodeId);
                    Object request = childRequests.get(requestId);
                    NodeRequestAccess access = (NodeRequestAccess) request;
                    requiredMask = access.voxy$requiredMask();
                    meshMask = access.voxy$meshMask();
                    existenceMask = access.voxy$existenceMask();
                }
            }
        } else if (type == 2) {
            requestId = nodeId;
            boolean childRequest = (encoded & (1 << 29)) != 0;
            Object request = (childRequest ? childRequests : topLevelRequests).get(requestId);
            NodeRequestAccess access = (NodeRequestAccess) request;
            requestInFlight = true;
            requiredMask = access.voxy$requiredMask();
            meshMask = access.voxy$meshMask();
            existenceMask = access.voxy$existenceMask();
        }

        LodAudit.cpuNodeState(position, phase, type, nodeId, watcherFlags, resolved,
                topLevel, childMask, geometry, childPtr, childCount, requestInFlight,
                requestId, geometryInFlight, requiredMask, meshMask, existenceMask);
    }

    @Mixin(targets = "me.cortex.voxy.client.core.rendering.hierachical.NodeRequest", remap = false)
    interface NodeRequestAccess {
        @Accessor("position") long voxy$position();
        @Accessor("requiredMask") int voxy$requiredMask();
        @Accessor("meshMask") int voxy$meshMask();
        @Accessor("existenceMask") int voxy$existenceMask();
    }
}
