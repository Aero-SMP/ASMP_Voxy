package me.cortex.voxy.client.core;

import com.mojang.blaze3d.systems.RenderSystem;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.gl.GlFramebuffer;
import me.cortex.voxy.client.core.gl.GlTexture;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.hierarchical.AsyncNodeManager;
import me.cortex.voxy.client.core.rendering.hierarchical.HierarchicalOcclusionTraverser;
import me.cortex.voxy.client.core.rendering.hierarchical.NodeCleaner;
import me.cortex.voxy.client.core.rendering.FullscreenBlit;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;

import static org.lwjgl.opengl.GL11C.GL_BLEND;
import static org.lwjgl.opengl.GL11C.GL_DEPTH_COMPONENT;
import static org.lwjgl.opengl.GL11C.GL_DEPTH_TEST;
import static org.lwjgl.opengl.GL11C.GL_NEAREST;
import static org.lwjgl.opengl.GL11C.GL_ONE;
import static org.lwjgl.opengl.GL11C.GL_ONE_MINUS_SRC_ALPHA;
import static org.lwjgl.opengl.GL11C.GL_RGBA8;
import static org.lwjgl.opengl.GL11C.GL_SRC_ALPHA;
import static org.lwjgl.opengl.GL11C.GL_STENCIL_TEST;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_MAG_FILTER;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_MIN_FILTER;
import static org.lwjgl.opengl.GL11C.glDisable;
import static org.lwjgl.opengl.GL11C.glEnable;
import static org.lwjgl.opengl.GL14C.glBlendFuncSeparate;
import static org.lwjgl.opengl.GL20C.glUniform4f;
import static org.lwjgl.opengl.GL30C.*;
import static org.lwjgl.opengl.GL43.GL_DEPTH_STENCIL_TEXTURE_MODE;
import static org.lwjgl.opengl.GL45C.glBindTextureUnit;
import static org.lwjgl.opengl.GL45C.glTextureParameterf;

public class NormalRenderPipeline extends AbstractRenderPipeline {
    private ShaderResourceScope targets = new ShaderResourceScope();
    private GlTexture colourTex;
    private GlTexture colourSSAOTex;
    private final GlFramebuffer fbSSAO;

    private final boolean useEnvFog;
    private final FullscreenBlit finalBlit;

    private final SSAO ssao;

    protected NormalRenderPipeline(AsyncNodeManager nodeManager, NodeCleaner nodeCleaner, HierarchicalOcclusionTraverser traversal) {
        super(nodeManager, nodeCleaner, traversal);
        this.useEnvFog = VoxyConfig.CONFIG.useEnvironmentalFog;
        try {
            this.fbSSAO = this.shaderResources.own(new GlFramebuffer(), GlFramebuffer::free);
            this.finalBlit = this.shaderResources.own(new FullscreenBlit("voxy:post/blit_texture_depth_cutout.frag",
                    a->a.defineIf("USE_ENV_FOG", this.useEnvFog).define("EMIT_COLOUR")), FullscreenBlit::delete);


            this.ssao = this.shaderResources.own(SSAO.createSSAO(VoxyConfig.CONFIG.getSSAOMode()), SSAO::free);
        } catch (RuntimeException | Error failure) {
            this.shaderResources.cleanupAfter(failure);
            throw failure;
        }
    }

    @Override public void prepareTargets(int width, int height) {
        if (this.colourTex == null || this.colourTex.getHeight() != height || this.colourTex.getWidth() != width) {
            this.targets.close();
            this.targets = new ShaderResourceScope();
            this.colourTex = this.colourSSAOTex = null;
            this.fb.resize(width, height);

            this.colourTex = this.targets.own(new GlTexture(), GlTexture::free);
            this.colourTex.store(GL_RGBA8, 1, width, height);
            this.colourSSAOTex = this.targets.own(new GlTexture(), GlTexture::free);
            this.colourSSAOTex.store(GL_RGBA8, 1, width, height);

            this.fb.framebuffer.bind(GL_COLOR_ATTACHMENT0, this.colourTex).verify();
            this.fbSSAO.bind(this.fb.getDepthAttachmentType(), this.fb.getDepthTex()).bind(GL_COLOR_ATTACHMENT0, this.colourSSAOTex).verify();


            glTextureParameterf(this.colourTex.id, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTextureParameterf(this.colourTex.id, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glTextureParameterf(this.colourSSAOTex.id, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTextureParameterf(this.colourSSAOTex.id, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glTextureParameterf(this.fb.getDepthTex().id, GL_DEPTH_STENCIL_TEXTURE_MODE, GL_DEPTH_COMPONENT);
        }

    }

    @Override
    protected int setup(Viewport viewport, int sourceFB, int srcWidth, int srcHeight) {
        this.prepareTargets(viewport.width, viewport.height);

        this.initDepthStencil(sourceFB, this.fb.framebuffer.id, viewport.width, viewport.height, viewport.width, viewport.height);

        return this.fb.getDepthTex().id;
    }

    @Override
    protected void postOpaquePreTranslucent(Viewport viewport, int sourceFrameBuffer) {
        this.ssao.computeSSAO(viewport, this.colourSSAOTex, this.colourTex, this.fb.getDepthTex(), sourceFrameBuffer);
        glBindFramebuffer(GL_FRAMEBUFFER, this.fbSSAO.id);
    }

    @Override
    protected void finish(Viewport viewport, int sourceFrameBuffer, int srcWidth, int srcHeight) {
        this.finalBlit.bind();
        var vrs = IGetVoxyRenderSystem.getNullable();
        float fogStart = vrs != null ? vrs.getCapturedFogStart() : RenderSystem.getShaderFogStart();
        float fogEnd   = vrs != null ? vrs.getCapturedFogEnd()   : RenderSystem.getShaderFogEnd();
        float[] fogColor = vrs != null ? vrs.getCapturedFogColor() : RenderSystem.getShaderFogColor();

        float renderDistance = Minecraft.getInstance().gameRenderer.getRenderDistance();
        boolean fogCoversAllRendering = fogEnd < renderDistance;

        if (this.useEnvFog) {
            if (Math.abs(fogEnd - fogStart) > 1) {
                glUniform2f(4, fogStart, fogEnd);
                glUniform4f(5, fogColor[0], fogColor[1], fogColor[2], 1.0f);
                glUniform1i(6, RenderSystem.getShaderFogShape().getIndex());
                glUniform1f(7, VoxyConfig.CONFIG.fogIntensity);
                glUniform1f(8, VoxyConfig.CONFIG.fogDensity);
            } else {
                glUniform2f(4, 0, 0);
                glUniform4f(5, 0, 0, 0, 0);
                glUniform1i(6, 0);
                glUniform1f(7, 0);
                glUniform1f(8, 0);
            }
        }

        glBindTextureUnit(3, this.colourSSAOTex.id);

        //Do alpha blending
        //Unbelievably jank hack, only blit out to the framebuffer if we are rendering fog
        if (!fogCoversAllRendering) {
            glEnable(GL_BLEND);
            glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
            AbstractRenderPipeline.transformBlitDepth(this.finalBlit, this.fb.getDepthTex().id, sourceFrameBuffer, viewport, new Matrix4f(viewport.vanillaProjection).mul(viewport.modelView));
            glDisable(GL_BLEND);
        } else {
            glDisable(GL_STENCIL_TEST);
            glDisable(GL_DEPTH_TEST);
        }
    }

    @Override
    public void setupAndBindOpaque(Viewport viewport) {
        this.fb.bind();
    }

    @Override
    public void setupAndBindTranslucent(Viewport viewport) {
        glBindFramebuffer(GL_FRAMEBUFFER, this.fbSSAO.id);
    }

    @Override public void free() {
        if (this.isFreed()) return;
        try { this.targets.close(); } finally { super.free0(); }
    }
}
