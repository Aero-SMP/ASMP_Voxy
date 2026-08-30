package me.cortex.voxy.debug.mixin;

import me.cortex.voxy.client.core.gl.GlFramebuffer;
import me.cortex.voxy.client.core.gl.GlPersistentMappedBuffer;
import me.cortex.voxy.client.core.gl.shader.Shader;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static org.lwjgl.opengl.GL43C.GL_BUFFER;
import static org.lwjgl.opengl.GL43C.GL_FRAMEBUFFER;
import static org.lwjgl.opengl.GL43C.GL_PROGRAM;
import static org.lwjgl.opengl.GL43C.glObjectLabel;

@Mixin(value = {GlFramebuffer.class, GlPersistentMappedBuffer.class, Shader.class}, remap = false)
abstract class MixinGlObjectLabels {
    @Shadow @Final private int id;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void voxyDebug$label(CallbackInfo callback) {
        int type;
        String name;
        if ((Object) this instanceof GlFramebuffer) {
            type = GL_FRAMEBUFFER;
            name = "framebuffer";
        } else if ((Object) this instanceof GlPersistentMappedBuffer) {
            type = GL_BUFFER;
            name = "mapped buffer";
        } else {
            type = GL_PROGRAM;
            name = "shader";
        }
        glObjectLabel(type, this.id, "Voxy " + name + " " + this.id);
    }
}
