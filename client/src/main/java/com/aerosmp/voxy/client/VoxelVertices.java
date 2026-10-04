package com.aerosmp.voxy.client;

import com.aerosmp.voxy.mixin.VoxyMixins;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;

import org.lwjgl.system.MemoryUtil;

/** Native block vertices plus an explicit source voxel, independent of surface normals. */
final class VoxelVertices {
    static final VertexFormatElement SOURCE_CELL =
            VertexFormatElement.register(
                    VertexFormatElement.findNextId(),
                    0,
                    VertexFormatElement.Type.UBYTE,
                    VertexFormatElement.Usage.GENERIC,
                    3);
    static final VertexFormat FORMAT;

    static {
        VertexFormat.Builder builder = VertexFormat.builder();
        DefaultVertexFormat.BLOCK.getElementMapping().forEach(builder::add);
        // BLOCK includes its normal padding. Preserve it before the additional attribute.
        builder.padding(1).add("SourceCell", SOURCE_CELL).padding(1);
        FORMAT = builder.build();
    }

    static void cell(BufferBuilder output, int x, int y, int z) {
        long address = ((VoxyMixins.VertexElements) (Object) output).element(SOURCE_CELL);
        if (address == -1L) return;
        MemoryUtil.memPutByte(address, (byte) x);
        MemoryUtil.memPutByte(address + 1, (byte) y);
        MemoryUtil.memPutByte(address + 2, (byte) z);
    }
}
