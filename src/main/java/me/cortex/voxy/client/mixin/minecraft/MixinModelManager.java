package me.cortex.voxy.client.mixin.minecraft;

import me.cortex.voxy.client.core.VoxyRenderSystem;
import net.minecraft.client.resources.model.ModelManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ModelManager.class)
public class MixinModelManager {
    @Inject(method = "apply", at = @At("HEAD"))
    private void voxy$modelResourcesChanged(CallbackInfo ci) {
        VoxyRenderSystem.modelResourcesChanged();
    }
}
