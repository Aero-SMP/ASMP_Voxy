package me.cortex.voxy.debug.mixin;

import me.cortex.voxy.client.core.AbstractRenderPipeline;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.gl.shader.Shader;
import me.cortex.voxy.client.core.gl.shader.ShaderLoader;
import me.cortex.voxy.client.core.gl.shader.ShaderType;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.building.RenderGenerationService;
import me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager;
import me.cortex.voxy.client.core.rendering.hierachical.HierarchicalOcclusionTraverser;
import me.cortex.voxy.client.core.rendering.hierachical.NodeCleaner;
import me.cortex.voxy.client.core.rendering.util.DownloadStream;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import me.cortex.voxy.debug.LodAudit;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static org.lwjgl.opengl.GL31C.GL_UNIFORM_BUFFER;
import static org.lwjgl.opengl.GL30C.glBindBufferBase;
import static org.lwjgl.opengl.GL42C.GL_COMMAND_BARRIER_BIT;
import static org.lwjgl.opengl.GL42C.glMemoryBarrier;
import static org.lwjgl.opengl.GL43C.GL_SHADER_STORAGE_BUFFER;
import static org.lwjgl.opengl.GL43C.GL_SHADER_STORAGE_BARRIER_BIT;
import static org.lwjgl.opengl.GL43C.glDispatchCompute;
import static org.lwjgl.opengl.GL45C.glNamedBufferSubData;
import static org.lwjgl.opengl.GL30C.glUniform1ui;

/** Debug-only GPU traversal audit and visible missing-root classification. */
@Mixin(value = HierarchicalOcclusionTraverser.class, remap = false)
abstract class MixinHierarchicalOcclusionTraverserDebug {
    @Shadow @Final private GlBuffer uniformBuffer;
    @Shadow private AbstractRenderPipeline pipeline;

    @Unique private GlBuffer voxy$auditBuffer;
    @Unique private GlBuffer voxy$missingRoots;
    @Unique private Shader voxy$missingRootShader;
    @Unique private LodAudit.FrameTicket voxy$currentTicket;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void voxy$debugCreate(AsyncNodeManager nodeManager, NodeCleaner nodeCleaner,
                                  RenderGenerationService meshGen,
                                  CallbackInfo ci) {
        LodAudit.rendererCreated();
        voxy$auditBuffer = new GlBuffer(LodAudit.TRAVERSAL_AUDIT_BYTES).zero();
        voxy$missingRoots = new GlBuffer((long) LodAudit.MISSING_ROOT_CAPACITY * Long.BYTES).zero();
    }

    @Inject(method = "lateStageCompile", at = @At("RETURN"))
    private void voxy$debugCompileMissingRoots(AbstractRenderPipeline renderPipeline, CallbackInfo ci) {
        String source = ShaderLoader.parse("voxy:debug/missing_roots.comp");
        String taa = renderPipeline.taaFunction("getTAA");
        if (taa != null) source += "\n\n" + taa;
        voxy$missingRootShader = Shader.make()
                .defineIf("TAA", taa != null)
                .addSource(ShaderType.COMPUTE, source)
                .compile();
    }

    @Inject(method = "doTraversal", at = @At("HEAD"))
    private void voxy$debugTraversalStart(Viewport viewport, CallbackInfo ci) {
        voxy$currentTicket = LodAudit.beginTraversal(viewport, pipeline != null && pipeline.hasTAA());
        voxy$auditBuffer.zero();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            glNamedBufferSubData(voxy$auditBuffer.id, (LodAudit.COUNTER_COUNT - 1L) * Integer.BYTES,
                    stack.ints(LodAudit.forensic() ? 1 : 0));
        }
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 15, voxy$auditBuffer.id);
    }

    @Inject(method = "doTraversal", at = @At(value = "INVOKE",
            target = "Lme/cortex/voxy/client/core/rendering/hierachical/HierarchicalOcclusionTraverser;downloadResetRequestQueue()V"))
    private void voxy$debugMissingRoots(Viewport viewport, CallbackInfo ci) {
        LodAudit.FrameTicket ticket = voxy$currentTicket;
        if (ticket == null || ticket.missingRoots().length == 0) return;
        long bytes = (long) ticket.missingRoots().length * Long.BYTES;
        long pointer = UploadStream.INSTANCE.upload(voxy$missingRoots, 0, bytes);
        for (long key : ticket.missingRoots()) {
            MemoryUtil.memPutInt(pointer, (int) (key >>> 32));
            MemoryUtil.memPutInt(pointer + 4, (int) key);
            pointer += Long.BYTES;
        }
        UploadStream.INSTANCE.commit();

        voxy$missingRootShader.bind();
        if (pipeline != null && pipeline.hasTAA()) pipeline.bindUniforms();
        glBindBufferBase(GL_UNIFORM_BUFFER, 13, uniformBuffer.id);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 13, voxy$missingRoots.id);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 15, voxy$auditBuffer.id);
        glUniform1ui(0, ticket.missingRoots().length);
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
        glDispatchCompute((ticket.missingRoots().length + 31) >>> 5, 1, 1);
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT | GL_COMMAND_BARRIER_BIT);
    }

    @Inject(method = "doTraversal", at = @At("RETURN"))
    private void voxy$debugTraversalEnd(Viewport viewport, CallbackInfo ci) {
        LodAudit.FrameTicket ticket = voxy$currentTicket;
        voxy$currentTicket = null;
        if (ticket == null) return;
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
        DownloadStream.INSTANCE.download(voxy$auditBuffer, 0, LodAudit.TRAVERSAL_AUDIT_BYTES,
                (pointer, size) -> LodAudit.traversalResult(ticket, pointer));
    }

    @Inject(method = "addTLN", at = @At("RETURN"))
    private void voxy$debugRootActive(int id, CallbackInfo ci) {
        LodAudit.rootGpuActive(id);
    }

    @Inject(method = "remTLN", at = @At("HEAD"))
    private void voxy$debugRootRemoved(int id, CallbackInfo ci) {
        LodAudit.rootGpuRemoved(id);
    }

    @Inject(method = "free", at = @At("HEAD"))
    private void voxy$debugFree(CallbackInfo ci) {
        DownloadStream.INSTANCE.flushWaitClear();
        LodAudit.rendererFreed();
        if (voxy$missingRootShader != null) voxy$missingRootShader.free();
        if (voxy$missingRoots != null) voxy$missingRoots.free();
        if (voxy$auditBuffer != null) voxy$auditBuffer.free();
    }
}
