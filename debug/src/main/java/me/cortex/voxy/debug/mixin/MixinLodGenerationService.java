package me.cortex.voxy.debug.mixin;

import me.cortex.voxy.commonImpl.lod.LodGenerationService;
import me.cortex.voxy.debug.ServerDiagnostics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = LodGenerationService.class, remap = false)
abstract class MixinLodGenerationService {
    @Inject(method = "onSuccess", at = @At("HEAD"))
    private void voxyDebug$success(CallbackInfo callback) {
        ServerDiagnostics.processed();
    }

    @Inject(method = "onFailure", at = @At("HEAD"))
    private void voxyDebug$failure(CallbackInfo callback) {
        ServerDiagnostics.failed();
    }

    @Inject(method = "shutdown", at = @At("RETURN"))
    private void voxyDebug$reset(CallbackInfo callback) {
        ServerDiagnostics.reset();
    }
}
