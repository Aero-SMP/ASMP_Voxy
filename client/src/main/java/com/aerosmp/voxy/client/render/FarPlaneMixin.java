package com.aerosmp.voxy.client.render;

import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** All world draws share one depth projection, including vanilla and Sodium terrain. */
@Mixin(GameRenderer.class)
public final class FarPlaneMixin {
    @Inject(method = "getDepthFar", at = @At("RETURN"), cancellable = true)
    private void distantFarPlane(CallbackInfoReturnable<Float> result) {
        result.setReturnValue(Math.max(result.getReturnValue(), TerrainRenderer.viewDistance() * 2f));
    }
}
