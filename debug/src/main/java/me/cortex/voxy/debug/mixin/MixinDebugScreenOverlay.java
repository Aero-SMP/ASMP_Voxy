package me.cortex.voxy.debug.mixin;

import me.cortex.voxy.debug.ClientDiagnostics;
import net.minecraft.client.gui.components.DebugScreenOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(DebugScreenOverlay.class)
abstract class MixinDebugScreenOverlay {
    @Inject(method = "getGameInformation", at = @At("RETURN"))
    private void voxyDebug$append(CallbackInfoReturnable<List<String>> callback) {
        ClientDiagnostics.appendF3(callback.getReturnValue());
    }
}
