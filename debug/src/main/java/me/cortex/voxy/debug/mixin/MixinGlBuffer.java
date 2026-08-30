package me.cortex.voxy.debug.mixin;

import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.debug.ClientDiagnostics;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = GlBuffer.class, remap = false)
abstract class MixinGlBuffer {
    @Shadow @Final private long size;
    @Shadow @Final public int id;

    @Inject(method = "<init>(JIZ)V", at = @At("RETURN"))
    private void voxyDebug$allocated(long size, int flags, boolean zero, CallbackInfo callback) {
        ClientDiagnostics.addBuffer(this.size);
        org.lwjgl.opengl.GL43C.glObjectLabel(org.lwjgl.opengl.GL43C.GL_BUFFER, this.id,
                "Voxy buffer " + this.id);
    }

    @Inject(method = "free", at = @At("RETURN"))
    private void voxyDebug$freed(CallbackInfo callback) {
        ClientDiagnostics.removeBuffer(this.size);
    }
}
