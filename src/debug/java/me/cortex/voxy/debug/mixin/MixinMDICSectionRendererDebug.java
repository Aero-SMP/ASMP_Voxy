package me.cortex.voxy.debug.mixin;

import me.cortex.voxy.client.core.AbstractRenderPipeline;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.model.ModelStore;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.section.geometry.BasicSectionGeometryData;
import me.cortex.voxy.client.core.rendering.section.backend.mdic.MDICSectionRenderer;
import me.cortex.voxy.client.core.rendering.util.DownloadStream;
import me.cortex.voxy.debug.LodAudit;
import org.lwjgl.system.MemoryStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static org.lwjgl.opengl.GL30C.glBindBufferBase;
import static org.lwjgl.opengl.GL42C.glMemoryBarrier;
import static org.lwjgl.opengl.GL43C.GL_SHADER_STORAGE_BUFFER;
import static org.lwjgl.opengl.GL43C.GL_SHADER_STORAGE_BARRIER_BIT;
import static org.lwjgl.opengl.GL45C.glNamedBufferSubData;

/** Debug-only evidence from final raster visibility and draw-command generation. */
@Mixin(value = MDICSectionRenderer.class, remap = false)
abstract class MixinMDICSectionRendererDebug {
    @Unique private GlBuffer voxy$drawAuditBuffer;
    @Unique private LodAudit.FrameTicket voxy$drawTicket;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void voxy$debugCreate(AbstractRenderPipeline pipeline, ModelStore modelStore,
                                  BasicSectionGeometryData geometryData, CallbackInfo ci) {
        voxy$drawAuditBuffer = new GlBuffer(LodAudit.DRAW_AUDIT_BYTES).zero();
    }

    @Inject(method = "buildDrawCalls", at = @At("HEAD"))
    private void voxy$debugDrawStart(Viewport viewport, CallbackInfo ci) {
        voxy$drawTicket = LodAudit.currentTicket(viewport);
        voxy$drawAuditBuffer.zero();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            glNamedBufferSubData(voxy$drawAuditBuffer.id, (LodAudit.COUNTER_COUNT - 1L) * Integer.BYTES,
                    stack.ints(LodAudit.forensic() ? 1 : 0));
        }
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 14, voxy$drawAuditBuffer.id);
    }

    @Inject(method = "buildDrawCalls", at = @At("RETURN"))
    private void voxy$debugDrawEnd(Viewport viewport, CallbackInfo ci) {
        LodAudit.FrameTicket ticket = voxy$drawTicket;
        voxy$drawTicket = null;
        if (ticket == null) return;
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
        DownloadStream.INSTANCE.download(voxy$drawAuditBuffer, 0, LodAudit.DRAW_AUDIT_BYTES,
                (pointer, size) -> LodAudit.drawResult(ticket, pointer));
    }

    @Inject(method = "free", at = @At("HEAD"))
    private void voxy$debugFree(CallbackInfo ci) {
        DownloadStream.INSTANCE.flushWaitClear();
        if (voxy$drawAuditBuffer != null) voxy$drawAuditBuffer.free();
    }
}
