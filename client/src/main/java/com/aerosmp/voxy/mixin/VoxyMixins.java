package com.aerosmp.voxy.mixin;

import com.aerosmp.voxy.client.TerrainRenderer;
import com.aerosmp.voxy.client.VoxyClient;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;

import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.data.BuiltSectionInfo;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.*;
import net.minecraft.core.BlockPos;
import net.minecraft.util.random.WeightedEntry;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.neoforge.client.textures.FluidSpriteCache;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

public final class VoxyMixins {
    @Pseudo
    @Mixin(
            targets = "dev.engine_room.flywheel.impl.visualization.VisualizationManagerImpl",
            remap = false)
    public static final class CachedBlockEntityFallback {
        @Inject(
                method = "supportsVisualization(Lnet/minecraft/world/level/LevelAccessor;)Z",
                at = @At("HEAD"),
                cancellable = true,
                remap = false,
                require = 1)
        private static void cachedBlockEntity(
                LevelAccessor level, CallbackInfoReturnable<Boolean> result) {
            if (TerrainRenderer.ownsBlockEntity(level)) result.setReturnValue(false);
        }
    }

    @Mixin(value = RenderSectionManager.class, remap = false)
    public static final class SodiumGeometry {
        @Inject(method = "updateSectionInfo", at = @At("RETURN"), remap = false)
        private void published(
                RenderSection section,
                BuiltSectionInfo info,
                CallbackInfoReturnable<Boolean> result) {
            TerrainRenderer.nativeGeometryChanged(section);
        }
    }

    @Mixin(value = RenderSectionManager.class, remap = false)
    public static final class SodiumSolidDepth {
        @Inject(
                method =
                        "renderLayer(Lnet/caffeinemc/mods/sodium/client/render/chunk/ChunkRenderMatrices;Lnet/caffeinemc/mods/sodium/client/render/chunk/terrain/TerrainRenderPass;DDD)V",
                at = @At("RETURN"),
                remap = false,
                require = 1)
        private void solidDepth(
                ChunkRenderMatrices matrices,
                TerrainRenderPass pass,
                double cameraX,
                double cameraY,
                double cameraZ,
                CallbackInfo result) {
            if (pass == DefaultTerrainRenderPasses.SOLID)
                TerrainRenderer.captureSolidDepth(matrices, cameraX, cameraY, cameraZ);
        }
    }

    @Mixin(value = FluidSpriteCache.class, remap = false)
    public static final class FluidAnimations {
        @Inject(method = "getFluidSprites", at = @At("RETURN"), remap = false)
        private static void sprites(
                BlockAndTintGetter world,
                BlockPos pos,
                FluidState fluid,
                CallbackInfoReturnable<TextureAtlasSprite[]> result) {
            TerrainRenderer.recordFluidSprites(world, result.getReturnValue());
        }
    }

    @Mixin(BufferBuilder.class)
    public interface VertexElements {
        @Invoker("beginElement")
        long element(VertexFormatElement element);
    }

    @Mixin(WeightedBakedModel.class)
    public interface WeightedModels {
        @Accessor("list")
        List<WeightedEntry.Wrapper<BakedModel>> models();
    }

    @Mixin(com.mojang.blaze3d.platform.NativeImage.class)
    public interface CoveragePixels {
        @Accessor("pixels")
        long pixels();
    }

    @Mixin(SodiumWorldRenderer.class)
    public interface SodiumCoverage {
        @Accessor("renderSectionManager")
        RenderSectionManager sections();
    }

    @Mixin(VertexBuffer.class)
    public abstract static class VertexAccess implements VoxyClient.SharedVertices {
        @Shadow private int vertexBufferId, indexBufferId, indexCount;
        @Shadow private VertexFormat format;
        @Shadow private VertexFormat.Mode mode;
        @Shadow private VertexFormat.IndexType indexType;
        @Shadow private RenderSystem.AutoStorageIndexBuffer sequentialIndices;

        public void adopt(int vertices, int elements, MeshData.DrawState state) {
            org.lwjgl.opengl.GL15C.glDeleteBuffers(vertexBufferId);
            org.lwjgl.opengl.GL15C.glDeleteBuffers(indexBufferId);
            vertexBufferId = vertices;
            indexBufferId = elements;
            format = state.format();
            mode = state.mode();
            indexCount = state.indexCount();
            org.lwjgl.opengl.GL15C.glBindBuffer(org.lwjgl.opengl.GL15C.GL_ARRAY_BUFFER, vertices);
            format.setupBufferState();
            if (elements == 0) {
                sequentialIndices = RenderSystem.getSequentialBuffer(mode);
                sequentialIndices.bind(indexCount);
                indexType = sequentialIndices.type();
            } else {
                org.lwjgl.opengl.GL15C.glBindBuffer(
                        org.lwjgl.opengl.GL15C.GL_ELEMENT_ARRAY_BUFFER, elements);
                indexType = state.indexType();
            }
        }
    }

    @Mixin(GameRenderer.class)
    public static final class FarPlane {
        @Inject(method = "getDepthFar", at = @At("RETURN"), cancellable = true)
        private void distant(CallbackInfoReturnable<Float> result) {
            result.setReturnValue(
                    Math.max(result.getReturnValue(), VoxyClient.viewDistance() * 2f));
        }
    }
}
