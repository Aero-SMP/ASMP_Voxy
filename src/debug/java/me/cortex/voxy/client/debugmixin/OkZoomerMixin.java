package me.cortex.voxy.client.debugmixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import me.cortex.voxy.client.lod.DebugZoomControl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/** Optional integration with Ok Zoomer; no copied zoom algorithm or dependency in normal jars. */
@Pseudo
@Mixin(targets = "page.langeweile.ok_zoomer.zoom.Zoom", remap = false)
public abstract class OkZoomerMixin {
    @ModifyReturnValue(method = "isZooming()Z", at = @At("RETURN"), remap = false)
    private static boolean voxy$testZoomSignal(boolean normal) {
        return DebugZoomControl.zoomSignal(normal);
    }
}
