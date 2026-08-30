package me.cortex.voxy.debug.mixin;

import me.cortex.voxy.client.core.gl.GlTexture;
import me.cortex.voxy.debug.ClientDiagnostics;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = GlTexture.class, remap = false)
abstract class MixinGlTexture {
    @Shadow @Final public int id;
    @Shadow private int format;
    @Shadow private int width;
    @Shadow private int height;
    @Shadow private int levels;
    @Unique private long voxyDebug$bytes;

    @Inject(method = "<init>(I)V", at = @At("RETURN"))
    private void voxyDebug$created(int type, CallbackInfo callback) {
        ClientDiagnostics.addTexture();
        org.lwjgl.opengl.GL43C.glObjectLabel(org.lwjgl.opengl.GL43C.GL_TEXTURE, this.id,
                "Voxy texture " + this.id);
    }

    @Inject(method = "<init>(IZ)V", at = @At("RETURN"))
    private void voxyDebug$createdView(int type, boolean generated, CallbackInfo callback) {
        ClientDiagnostics.addTexture();
        org.lwjgl.opengl.GL43C.glObjectLabel(org.lwjgl.opengl.GL43C.GL_TEXTURE, this.id,
                "Voxy texture view " + this.id);
    }

    @Inject(method = "store", at = @At("RETURN"))
    private void voxyDebug$allocated(int format, int levels, int width, int height,
                                     CallbackInfoReturnable<GlTexture> callback) {
        int bytesPerPixel = 4;
        long bytes = 0;
        for (int level = 0; level < this.levels; level++) {
            bytes += Math.max(this.width >> level, 1) * (long) Math.max(this.height >> level, 1)
                    * bytesPerPixel;
        }
        this.voxyDebug$bytes = bytes;
        ClientDiagnostics.allocateTexture(bytes);
    }

    @Inject(method = "free", at = @At("RETURN"))
    private void voxyDebug$freed(CallbackInfo callback) {
        ClientDiagnostics.removeTexture(this.voxyDebug$bytes);
    }
}
