package me.cortex.voxy.client.mixin.iris;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import net.irisshaders.iris.pipeline.PipelineManager;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(value = PipelineManager.class, remap = false)
public class MixinPipelineManager {
    @WrapMethod(method = "preparePipeline")
    private WorldRenderingPipeline voxy$pipelineReady(NamespacedId dimension, Operation<WorldRenderingPipeline> original) {
        var renderer = IGetVoxyRenderSystem.getNullable();
        Throwable failure = null;
        try {
            return original.call(dimension);
        } catch (RuntimeException | Error problem) {
            failure = problem;
            throw problem;
        } finally {
            // Captured owner only: a world switch may have replaced it inside preparePipeline.
            if (renderer != null) renderer.irisPipelinePrepared(failure);
        }
    }
}
