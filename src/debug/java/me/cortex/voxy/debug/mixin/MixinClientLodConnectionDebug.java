package me.cortex.voxy.debug.mixin;

import me.cortex.voxy.debug.LodAudit;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Debug-only evidence for the exact section subscriptions written to Rust. */
@Mixin(targets = "me.cortex.voxy.client.lod.ClientLodNetwork$Connection", remap = false)
abstract class MixinClientLodConnectionDebug {
    @Inject(method = "writeFrame", at = @At("HEAD"))
    private void voxy$debugSubscriptionFrame(short type, byte[] payload, CallbackInfo ci) {
        if (type == 0x0002) LodAudit.subscriptionFrame(payload);
    }
}
