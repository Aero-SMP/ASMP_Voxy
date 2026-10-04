package com.aerosmp.voxy.mixin;

import com.aerosmp.voxy.client.VoxyClient;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.resources.model.*;
import net.minecraft.util.random.WeightedEntry;
import java.util.List;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

public final class VoxyMixins {
    @Mixin(WeightedBakedModel.class)
    public interface WeightedModels { @Accessor("list") List<WeightedEntry.Wrapper<BakedModel>> models(); }
    @Mixin(com.mojang.blaze3d.platform.NativeImage.class)
    public interface CoveragePixels { @Accessor("pixels") long pixels(); }
    @Mixin(SodiumWorldRenderer.class)
    public interface SodiumCoverage { @Accessor("renderSectionManager") RenderSectionManager sections(); }
    @Mixin(VertexBuffer.class)
    public abstract static class VertexAccess implements VoxyClient.SharedVertices {
        @Shadow private int vertexBufferId, indexBufferId, indexCount;
        @Shadow private VertexFormat format;
        @Shadow private VertexFormat.Mode mode;
        @Shadow private VertexFormat.IndexType indexType;
        @Shadow private RenderSystem.AutoStorageIndexBuffer sequentialIndices;
        public void adopt(int vertices, int elements, MeshData.DrawState state) {
            org.lwjgl.opengl.GL15C.glDeleteBuffers(vertexBufferId); org.lwjgl.opengl.GL15C.glDeleteBuffers(indexBufferId);
            vertexBufferId = vertices; indexBufferId = elements; format = state.format(); mode = state.mode(); indexCount = state.indexCount();
            org.lwjgl.opengl.GL15C.glBindBuffer(org.lwjgl.opengl.GL15C.GL_ARRAY_BUFFER, vertices); format.setupBufferState();
            if (elements == 0) { sequentialIndices = RenderSystem.getSequentialBuffer(mode); sequentialIndices.bind(indexCount); indexType = sequentialIndices.type(); }
            else { org.lwjgl.opengl.GL15C.glBindBuffer(org.lwjgl.opengl.GL15C.GL_ELEMENT_ARRAY_BUFFER, elements); indexType = state.indexType(); }
        }
    }
    @Mixin(GameRenderer.class)
    public static final class FarPlane {
        @Inject(method = "getDepthFar", at = @At("RETURN"), cancellable = true)
        private void distant(CallbackInfoReturnable<Float> result) { result.setReturnValue(Math.max(result.getReturnValue(), VoxyClient.viewDistance() * 2f)); }
    }
}
