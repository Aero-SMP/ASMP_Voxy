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
        var renderer = IGetVoxyRenderSystem.getNullable();
        var captured = IrisUtil.CAPTURED_VIEWPORT_PARAMETERS;
        if (renderer != null) {
            // Iris has installed its maps and completed allChanged(), but has not yet
            // evaluated custom uniforms. Commit now so this frame's matrices are valid.
            if (this.initializedBlockIds) renderer.irisMappingsPrepared(null);
            if (captured != null && IGetVoxyRenderSystem.getNullable() == renderer) captured.apply(renderer);
        }
    }

    @Override public boolean voxy$blockMappingsReady() { return this.initializedBlockIds; }

    @WrapMethod(method = "beginLevelRendering")
    private void voxy$initializeMaterialMappings(Operation<Void> original) {
        if (this.initializedBlockIds) { original.call(); return; }
        var renderer = IGetVoxyRenderSystem.getNullable();
        var captured = IrisUtil.CAPTURED_VIEWPORT_PARAMETERS;
        if (renderer != null) {
            renderer.deferUntilIrisMappingsReady();
            // Suspending clears stale captures; this one belongs to the current world frame.
            IrisUtil.CAPTURED_VIEWPORT_PARAMETERS = captured;
        }
        try {
            original.call();
        } catch (RuntimeException | Error problem) {
            if (renderer != null) renderer.irisMappingsPrepared(problem);
            throw problem;
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
