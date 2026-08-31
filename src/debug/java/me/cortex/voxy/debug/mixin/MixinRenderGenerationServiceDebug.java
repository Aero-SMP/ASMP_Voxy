package me.cortex.voxy.debug.mixin;

import me.cortex.voxy.client.core.rendering.building.RenderGenerationService;
import me.cortex.voxy.debug.LodAudit;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Debug-only mesh request lifecycle hook. */
@Mixin(value = RenderGenerationService.class, remap = false)
abstract class MixinRenderGenerationServiceDebug {
    @Inject(method = "enqueueTask", at = @At("HEAD"))
    private void voxy$debugMeshRequested(long key, CallbackInfo ci) {
        LodAudit.geometryRequested(key);
    }
}
