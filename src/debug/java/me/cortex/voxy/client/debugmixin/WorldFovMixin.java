package me.cortex.voxy.client.debugmixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import me.cortex.voxy.client.lod.DebugZoomControl;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(GameRenderer.class)
public abstract class WorldFovMixin {
    @WrapMethod(method = "getFov")
    private double voxy$observeWorldFov(Camera camera, float tickDelta, boolean useFovSetting,
                                      Operation<Double> original) {
        double fov = original.call(camera, tickDelta, useFovSetting);
        if (useFovSetting) DebugZoomControl.recordWorldFov(fov);
        return fov;
    }
}
