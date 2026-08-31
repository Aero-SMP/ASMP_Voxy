package me.cortex.voxy.debug.mixin;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import me.cortex.voxy.client.core.rendering.building.BuiltSection;
import me.cortex.voxy.client.core.rendering.hierachical.NodeManager;
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

    @Inject(method = "finishTopLevelRequest", at = @At("RETURN"))
    private void voxy$debugRootReady(int requestId, @Coerce Object request, CallbackInfo ci) {
        long position = ((NodeRequestAccess) request).voxy$position();
        int encoded = activeSectionMap.get(position);
        LodAudit.rootCpuReady(position, encoded & NodeManager.NODE_ID_MSK);
    }

    @Inject(method = "processGeometryResult", at = @At("HEAD"))
    private void voxy$debugGeometryBuilt(BuiltSection section, CallbackInfo ci) {
        LodAudit.geometryBuilt(section.position, section.isEmpty());
    }

    @Mixin(targets = "me.cortex.voxy.client.core.rendering.hierachical.NodeRequest", remap = false)
    interface NodeRequestAccess {
        @Accessor("position") long voxy$position();
    }
}
