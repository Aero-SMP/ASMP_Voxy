package me.cortex.voxy.client.mixin.iris;

import me.cortex.voxy.client.iris.IrisShaderPatch.ShaderLoadError;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import me.cortex.voxy.common.Logger;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(value = Iris.class, remap = false)
public class MixinIris {
    @WrapMethod(method = "reload")
    private static void voxy$preserveTerrain(Operation<Void> original) throws java.io.IOException {
        var renderer = IGetVoxyRenderSystem.getNullable();
        var scope = renderer == null ? null : renderer.beginShaderReload("Iris.reload");
        Throwable failure = null;
        try {
            original.call();
        } catch (Throwable problem) {
            failure = problem;
            if (problem instanceof java.io.IOException io) throw io;
            if (problem instanceof RuntimeException runtime) throw runtime;
            if (problem instanceof Error error) throw error;
            throw new RuntimeException(problem);
        } finally {
            if (scope != null) scope.finish(failure);
        }
    }

    @Redirect(method = "createPipeline", at = @At(value = "INVOKE", target = "Lnet/irisshaders/iris/shaderpack/ShaderPack;getProgramSet(Lnet/irisshaders/iris/shaderpack/materialmap/NamespacedId;)Lnet/irisshaders/iris/shaderpack/programs/ProgramSet;"))
    private static ProgramSet voxy$redirectProgramSet(ShaderPack shaderPack, NamespacedId dim) {
        try {
            return shaderPack.getProgramSet(dim);
        } catch (ShaderLoadError e) {
            Logger.error(e);
            return null;
        }
    }
}
