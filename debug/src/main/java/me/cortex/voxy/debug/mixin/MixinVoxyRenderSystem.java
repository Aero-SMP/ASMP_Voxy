package me.cortex.voxy.debug.mixin;

import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.debug.ClientDiagnostics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = VoxyRenderSystem.class, remap = false)
abstract class MixinVoxyRenderSystem {
    @Inject(method = "renderOpaque", at = @At("HEAD"))
    private void voxyDebug$startFrame(Viewport<?> viewport, CallbackInfo callback) {
        ClientDiagnostics.startFrame();
    }

    @Inject(method = "renderOpaque", at = @At("RETURN"))
    private void voxyDebug$finishFrame(Viewport<?> viewport, CallbackInfo callback) {
        ClientDiagnostics.finishFrame();
    }

    @Inject(method = "shutdown", at = @At("HEAD"))
    private void voxyDebug$clearQueries(CallbackInfo callback) {
        ClientDiagnostics.clearGpuQueries();
    }
}
