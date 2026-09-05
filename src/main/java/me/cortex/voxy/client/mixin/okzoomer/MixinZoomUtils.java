package me.cortex.voxy.client.mixin.okzoomer;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import me.cortex.voxy.client.compat.ZoomRange;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import page.langeweile.ok_zoomer.config.OkZoomerConfigManager;

@Pseudo
@Mixin(targets = "page.langeweile.ok_zoomer.utils.ZoomUtils", remap = false)
public abstract class MixinZoomUtils {
    @ModifyArg(method = "changeZoomDivisor", at = @At(value = "INVOKE",
            target = "Ljava/lang/Math;min(II)I"), index = 1, remap = false)
    private static int voxy$extendScrollLimit(int limit, @Local(ordinal = 0) int base,
                                             @Local(ordinal = 1) int resolution) {
        return ZoomRange.maximumStep(base, resolution, limit);
    }

    @ModifyArg(method = "keepZoomStepsWithinBounds", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/util/Mth;clamp(III)I"), index = 2, remap = false)
    private static int voxy$keepExtendedRange(int limit) {
        var config = OkZoomerConfigManager.CONFIG.zoomScrolling;
        return ZoomRange.maximumStep((Integer) config.scrollBase.value(), (Integer) config.scrollResolution.value(), limit);
    }

    // The last added step can overshoot 10x because steps are integral. Cap that final step
    // exactly, leaving every pre-existing step and the mod's default/reset behavior alone.
    @ModifyExpressionValue(method = "changeZoomDivisor", at = @At(value = "INVOKE",
            target = "Ljava/lang/Math;pow(DD)D"), remap = false)
    private static double voxy$capMaximumMagnification(double divisor, @Local(ordinal = 0) int base,
                                                      @Local(ordinal = 1) int resolution,
                                                      @Local(ordinal = 2) int limit) {
        return Math.min(divisor, ZoomRange.maximumDivisor(base, resolution, limit));
    }
}
