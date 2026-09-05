package me.cortex.voxy.client.mixin.iris;

import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import me.cortex.voxy.client.iris.IrisUtil;
import me.cortex.voxy.client.iris.IGetIrisVoxyPipelineData;
import me.cortex.voxy.client.iris.IGetVoxyPatchData;
import me.cortex.voxy.client.iris.IrisShaderPatch;
import me.cortex.voxy.client.iris.IrisVoxyRenderPipelineData;
import net.irisshaders.iris.gl.buffer.ShaderStorageBufferHolder;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.uniforms.custom.CustomUniforms;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = IrisRenderingPipeline.class, remap = false)
public class MixinIrisRenderingPipeline implements IGetVoxyPatchData, IGetIrisVoxyPipelineData {
    @Shadow @Final private CustomUniforms customUniforms;
    @Shadow private boolean initializedBlockIds;
    @Shadow private ShaderStorageBufferHolder shaderStorageBufferHolder;
    @Unique IrisShaderPatch patchData;
    @Unique
    IrisVoxyRenderPipelineData pipeline;

    @Inject(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/irisshaders/iris/pipeline/transform/ShaderPrinter;resetPrintState()V", shift = At.Shift.AFTER))
    private void voxy$injectPatchDataStore(ProgramSet programSet, CallbackInfo ci) {
        this.patchData = ((IGetVoxyPatchData) programSet).voxy$getPatchData();
    }

    @Inject(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/irisshaders/iris/pipeline/IrisRenderingPipeline;createSetupComputes([Lnet/irisshaders/iris/shaderpack/programs/ComputeSource;Lnet/irisshaders/iris/shaderpack/programs/ProgramSet;Lnet/irisshaders/iris/shaderpack/texture/TextureStage;)[Lnet/irisshaders/iris/gl/program/ComputeProgram;"))
    private void voxy$injectPipeline(ProgramSet programSet, CallbackInfo ci) {
        if (this.patchData != null) {
            this.pipeline = IrisVoxyRenderPipelineData.buildPipeline((IrisRenderingPipeline)(Object)this, this.patchData, this.customUniforms, this.shaderStorageBufferHolder);
        }
    }

    @Inject(method = "beginLevelRendering", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;activeTexture(I)V", shift = At.Shift.BEFORE), remap = false)
    private void voxy$injectViewportSetup(CallbackInfo ci) {
        if (IrisUtil.CAPTURED_VIEWPORT_PARAMETERS != null) {
            var renderer = ((IGetVoxyRenderSystem) Minecraft.getInstance().levelRenderer).voxy$getRenderSystem();
            if (renderer != null) {
                IrisUtil.CAPTURED_VIEWPORT_PARAMETERS.apply(renderer);
            }
        }
    }

    @Override public boolean voxy$blockMappingsReady() { return this.initializedBlockIds; }

    @WrapMethod(method = "beginLevelRendering")
    private void voxy$initializeMaterialMappings(Operation<Void> original) {
        if (this.initializedBlockIds) { original.call(); return; }
        var renderer = IGetVoxyRenderSystem.getNullable();
        var scope = renderer == null ? null : renderer.beginShaderReload("Iris block mapping initialization");
        var captured = IrisUtil.CAPTURED_VIEWPORT_PARAMETERS;
        Throwable failure = null;
        try {
            original.call();
        } catch (RuntimeException | Error problem) {
            failure = problem;
            throw problem;
        } finally {
            if (scope != null) {
                scope.finish(failure);
                renderer.irisMappingsPrepared(failure);
                // The earlier viewport hook ran while suspended. Apply this frame's exact
                // captured camera after committing, never to a replacement world/renderer.
                if (failure == null && captured != null && IGetVoxyRenderSystem.getNullable() == renderer) {
                    captured.apply(renderer);
                }
            }
        }
    }

    @Inject(method = "destroy", at = @At("HEAD"))
    private void voxy$beforeTargetsDestroyed(CallbackInfo ci) {
        var renderer = IGetVoxyRenderSystem.getNullable();
        if (renderer != null && this.pipeline != null && this.pipeline.thePipeline != null) {
            renderer.irisPipelineDestroyed(this.pipeline.thePipeline);
        }
    }

    @Override
    public IrisShaderPatch voxy$getPatchData() {
        return this.patchData;
    }

    @Override
    public IrisVoxyRenderPipelineData voxy$getPipelineData() {
        return this.pipeline;
    }
}
