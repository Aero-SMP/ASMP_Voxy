package me.cortex.voxy.client.core;

import com.mojang.blaze3d.platform.GlConst;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.model.ModelBakerySubsystem;
import me.cortex.voxy.client.core.rendering.ChunkBoundRenderer;
import me.cortex.voxy.client.core.rendering.RenderDistanceTracker;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.building.RenderGenerationService;
import me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager;
import me.cortex.voxy.client.core.rendering.hierachical.HierarchicalOcclusionTraverser;
import me.cortex.voxy.client.core.rendering.hierachical.NodeCleaner;
import me.cortex.voxy.client.core.rendering.section.backend.mdic.MDICSectionRenderer;
import me.cortex.voxy.client.core.rendering.section.geometry.BasicSectionGeometryData;
import me.cortex.voxy.client.core.rendering.util.DownloadStream;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import me.cortex.voxy.client.core.util.IrisUtil;
import me.cortex.voxy.client.iris.IGetIrisVoxyPipelineData;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.thread.ServiceManager;
import me.cortex.voxy.common.world.WorldEngine;
import net.irisshaders.iris.Iris;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.opengl.GL11;

import java.util.Arrays;

import static org.lwjgl.opengl.GL11.GL_VIEWPORT;
import static org.lwjgl.opengl.GL11.glEnable;
import static org.lwjgl.opengl.GL11.glFinish;
import static org.lwjgl.opengl.GL11.glGetIntegerv;
import static org.lwjgl.opengl.GL11.glViewport;
import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL20C.glUseProgram;
import static org.lwjgl.opengl.GL30.glGetIntegeri;
import static org.lwjgl.opengl.GL30C.*;
import static org.lwjgl.opengl.GL33.glBindSampler;
import static org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER;
import static org.lwjgl.opengl.GL43C.GL_SHADER_STORAGE_BUFFER_BINDING;

public class VoxyRenderSystem {
    private final WorldEngine worldIn;


    private final ModelBakerySubsystem modelService;
    private final RenderGenerationService renderGen;
    private final BasicSectionGeometryData geometryData;
    private final AsyncNodeManager nodeManager;
    private final NodeCleaner nodeCleaner;
    private final HierarchicalOcclusionTraverser traversal;


    private final RenderDistanceTracker renderDistanceTracker;
    public final ChunkBoundRenderer chunkBoundRenderer;

    private final Viewport viewport;

    private final AbstractRenderPipeline pipeline;

    // Fog parameters captured before modification by MixinFogRenderer, for Voxy's own fog pass
    private float capturedFogStart;
    private float capturedFogEnd;
    private final float[] capturedFogColor = new float[4];

    public void setCapturedFog(float fogStart, float fogEnd, float[] fogColor) {
        this.capturedFogStart = fogStart;
        this.capturedFogEnd = fogEnd;
        System.arraycopy(fogColor, 0, this.capturedFogColor, 0, 4);
    }

    public float getCapturedFogStart() { return this.capturedFogStart; }
    public float getCapturedFogEnd()   { return this.capturedFogEnd; }
    public float[] getCapturedFogColor() { return this.capturedFogColor; }

    public VoxyRenderSystem(WorldEngine world, ServiceManager sm) {
        //Keep the world loaded, NOTE: this is done FIRST, to keep and ensure that even if the rest of loading takes more
        // than timeout, we keep the world acquired
        world.acquireRef();
        Logger.info("Creating Voxy render system");

        if (Minecraft.getInstance().options.renderDistance().get()<3) {
            String msg = "Voxy: Having a vanilla render distance of 2 can cause rare culling near the edge of your screen issues, please use 3 or more";
            Logger.warn(msg);
            Minecraft.getInstance().getChatListener().handleSystemMessage(Component.literal(msg), false);
        }

        //Fking HATE EVERYTHING AAAAAAAAAAAAAAAA
        int[] oldBufferBindings = new int[10];
        for (int i = 0; i < oldBufferBindings.length; i++) {
            oldBufferBindings[i] = glGetIntegeri(GL_SHADER_STORAGE_BUFFER_BINDING, i);
        }

        try {
            //wait for opengl to be finished, this should hopefully ensure all memory allocations are free
            glFinish();

            this.worldIn = world;

            {
                this.modelService = new ModelBakerySubsystem(world.getMapper());
                this.renderGen = new RenderGenerationService(world, this.modelService, sm);

                this.geometryData = new BasicSectionGeometryData(1<<20, RenderResourceReuse.getOrCreateGeometryBuffer());

                this.nodeManager = new AsyncNodeManager(1 << 21, this.geometryData, this.renderGen);
                this.nodeCleaner = new NodeCleaner(this.nodeManager);
                this.traversal = new HierarchicalOcclusionTraverser(this.nodeManager, this.nodeCleaner, this.renderGen);

                world.setDirtyCallback(this.nodeManager::worldEvent);

                Arrays.stream(world.getMapper().getBiomeEntries()).forEach(this.modelService::addBiome);
                world.getMapper().setBiomeCallback(this.modelService::addBiome);

                this.nodeManager.start();
            }

            AbstractRenderPipeline pipeline = null;
            if (IrisUtil.IRIS_INSTALLED) {
                var irisPipeline = Iris.getPipelineManager().getPipelineNullable();
                if (irisPipeline instanceof IGetIrisVoxyPipelineData voxyPipeline) {
                    var data = voxyPipeline.voxy$getPipelineData();
                    if (data != null) {
                        Logger.info("Creating voxy iris render pipeline");
                        try {
                            pipeline = new IrisVoxyRenderPipeline(data, this.nodeManager, this.nodeCleaner, this.traversal);
                        } catch (Exception e) {
                            Logger.error("Failed to create iris render pipeline", e);
                            IrisUtil.disableIrisShaders();
                        }
                    }
                }
            }
            this.pipeline = pipeline != null ? pipeline : new NormalRenderPipeline(this.nodeManager, this.nodeCleaner, this.traversal);
            this.pipeline.setupExtraModelBakeryData(this.modelService);//Configure the model service

            //Late stage traversal compile for shaders with taa
            this.traversal.lateStageCompile(this.pipeline);


            var sectionRenderer = new MDICSectionRenderer(this.pipeline, this.modelService.getStore(), this.geometryData);
            this.pipeline.setSectionRenderer(sectionRenderer);
            this.viewport = new Viewport(this.geometryData.getMaxSectionCount());

            {
                int minSec = Minecraft.getInstance().level.getMinSection() >> 5;
                int maxSec = (Minecraft.getInstance().level.getMaxSection() - 1) >> 5;

                this.renderDistanceTracker = new RenderDistanceTracker(40,
                        minSec,
                        maxSec,
                        this.nodeManager::addTopLevel,
                        this.nodeManager::removeTopLevel);

                this.setRenderDistance(VoxyConfig.CONFIG.sectionRenderDistance);
            }

            this.chunkBoundRenderer = new ChunkBoundRenderer(this.pipeline);

            Logger.info("Voxy render system created with " + this.geometryData.getGeometryCapacityBytes() + " geometry capacity, using pipeline '" + this.pipeline.getClass().getSimpleName() + "' with renderer '" + sectionRenderer.getClass().getSimpleName() + "'");
        } catch (RuntimeException e) {
            world.releaseRef();//If something goes wrong, we must release the world first
            throw e;
        }

        for (int i = 0; i < oldBufferBindings.length; i++) {
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, i, oldBufferBindings[i]);
        }

        for (int i = 0; i < 12; i++) {
            GlStateManager._activeTexture(GlConst.GL_TEXTURE0+i);
            GlStateManager._bindTexture(0);
            glBindSampler(i, 0);
        }
    }


    public Viewport setupViewport(Matrix4fc vanillaProjection, Matrix4fc modelView, double cameraX, double cameraY, double cameraZ) {
        var viewport = this.getViewport();
        if (viewport == null) {
            return null;
        }

        //cameraY += 100;
        var voxyProjection = computeProjectionMat(vanillaProjection);

        int[] dims = new int[4];
        glGetIntegerv(GL_VIEWPORT, dims);

        int width = dims[2];
        int height = dims[3];

        {//Apply render scaling factor
            var factor = this.pipeline.getRenderScalingFactor();
            if (factor != null) {
                width = (int) (width*factor[0]);
                height = (int) (height*factor[1]);
            }
        }
        if (width == 0 || height == 0) {
            Logger.error("Viewport width or height was zero, this is bad bad bad");
            return null;
        }

        viewport
                .setVanillaProjection(vanillaProjection)
                .setProjection(voxyProjection)
                .setModelView(new Matrix4f(modelView))
                .setCamera(cameraX, cameraY, cameraZ)
                .setScreenSize(width, height)
                .update();

        viewport.frameId++;

        return viewport;
    }

    public void renderOpaque(Viewport viewport) {
        if (viewport == null) {
            return;
        }
        if (viewport.width <= 0 || viewport.height <= 0) {
            Logger.error("Viewport width or height was zero, this is bad bad bad, exiting frame");
            return;//Only render on valid viewport
        }

        //TODO: optimize
        int[] oldBufferBindings = new int[10];
        for (int i = 0; i < oldBufferBindings.length; i++) {
            oldBufferBindings[i] = glGetIntegeri(GL_SHADER_STORAGE_BUFFER_BINDING, i);
        }


        int oldFB = GL11.glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        int boundFB = oldFB;

        int[] dims = new int[4];
        glGetIntegerv(GL_VIEWPORT, dims);

        glViewport(0,0, viewport.width, viewport.height);

        if (boundFB == 0) {
            throw new IllegalStateException("Cannot use the default framebuffer as cannot source from it");
        }

        this.pipeline.preSetup(viewport);

        if (!IrisUtil.irisShadowActive()) {
            this.chunkBoundRenderer.render(viewport);
        } else {
            viewport.depthBoundingBuffer.clear(0.0f);
        }

        //The entire rendering pipeline (excluding the chunkbound thing)
        this.pipeline.runPipeline(viewport, boundFB, dims[2], dims[3]);

        //As much dynamic runtime stuff here
        {
            //Tick upload stream (this is ok to do here as upload ticking is just memory management)
            UploadStream.INSTANCE.tick();

            this.renderDistanceTracker.setCenterAndProcess(viewport.cameraX, viewport.cameraZ);
            //Done here as is allows less gl state resetup
            this.modelService.tick(900_000);
        }

        glBindFramebuffer(GlConst.GL_FRAMEBUFFER, oldFB);
        glViewport(dims[0], dims[1], dims[2], dims[3]);

        {//Reset state manager stuffs
            glUseProgram(0);
            glEnable(GL_DEPTH_TEST);
            glDisable(GL_STENCIL_TEST);

            GlStateManager._glBindVertexArray(0);//Clear binding

            GlStateManager._activeTexture(GlConst.GL_TEXTURE1);
            for (int i = 0; i < 12; i++) {
                GlStateManager._activeTexture(GlConst.GL_TEXTURE0+i);
                GlStateManager._bindTexture(0);
                glBindSampler(i, 0);
            }

            IrisUtil.clearIrisSamplers();//Thanks iris (sigh)

            //TODO: should/needto actually restore all of these, not just clear them
            //Clear all the bindings
            for (int i = 0; i < oldBufferBindings.length; i++) {
                glBindBufferBase(GL_SHADER_STORAGE_BUFFER, i, oldBufferBindings[i]);
            }

            //((SodiumShader) Iris.getPipelineManager().getPipelineNullable().getSodiumPrograms().getProgram(DefaultTerrainRenderPasses.CUTOUT).getInterface()).setupState(DefaultTerrainRenderPasses.CUTOUT, fogParameters);
        }

    }
    public static float getRenderDistance() {
        return Minecraft.getInstance().options.getEffectiveRenderDistance()*16;
    }

    private static Matrix4f computeProjectionMat(Matrix4fc base) {

        //this jank is to capture the extra crap they inject like viewbobbing
        var rawMCProj = RenderSystem.getProjectionMatrix();
        var extraProjection = rawMCProj.invert(new Matrix4f()).mul(base);

        float near = getRenderDistance()<=32.0f?8f:16f;
        float far = 16*3000;

        return extraProjection.mulLocal(
                new Matrix4f(rawMCProj)
                .m22(far / (near - far))
                .m32(far * near / (near - far))
        );
    }

    public void setRenderDistance(float renderDistance) {
        this.renderDistanceTracker.setRenderDistance((int) Math.ceil(renderDistance+1));//the +1 is to cover the outer ring of chunks when rendering a circle
    }

    public Viewport getViewport() {
        if (IrisUtil.irisShadowActive()) {
            return null;
        }
        return this.viewport;
    }

    public void shutdown() {
        Logger.info("Flushing download stream");
        DownloadStream.INSTANCE.flushWaitClear();
        Logger.info("Shutting down rendering");
        try {
            //Cleanup callbacks
            this.worldIn.setDirtyCallback(null);
            this.worldIn.getMapper().setBiomeCallback(null);
            this.worldIn.getMapper().setStateCallback(null);

            this.nodeManager.stop();

            this.modelService.shutdown();
            this.renderGen.shutdown();
            this.traversal.free();
            this.nodeCleaner.free();
            this.geometryData.free();
            if (((BasicSectionGeometryData)this.geometryData).isExternalGeometryBuffer) {
                RenderResourceReuse.giveBackGeometryBuffer(((BasicSectionGeometryData)this.geometryData).getGeometryBuffer());
            }

            this.chunkBoundRenderer.free();

            this.viewport.delete();
        } catch (Exception e) {Logger.error("Error shutting down renderer components", e);}
        Logger.info("Shutting down render pipeline");
        try {this.pipeline.free();} catch (Exception e){Logger.error("Error releasing render pipeline", e);}



        Logger.info("Flushing download stream");
        DownloadStream.INSTANCE.flushWaitClear();

        //Release hold on the world
        this.worldIn.releaseRef();
        Logger.info("Render shutdown completed");
    }

}
