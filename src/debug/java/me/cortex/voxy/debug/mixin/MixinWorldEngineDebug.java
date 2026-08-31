package me.cortex.voxy.debug.mixin;

import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.debug.LodAudit;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Debug-only authoritative section installation evidence. */
@Mixin(value = WorldEngine.class, remap = false)
abstract class MixinWorldEngineDebug {
    @Inject(method = "replaceRemoteSection", at = @At("RETURN"))
    private void voxy$debugInstalled(long key, long revision, long[] data,
                                     byte nonEmptyChildren, int nonEmptyBlockCount,
                                     CallbackInfo ci) {
        LodAudit.sectionInstalled(key, revision, data);
    }

    @Inject(method = "invalidateRemoteSection", at = @At("RETURN"))
    private void voxy$debugInvalidated(long key, long revision, CallbackInfo ci) {
        LodAudit.sectionInvalidated(key, revision);
    }
}
