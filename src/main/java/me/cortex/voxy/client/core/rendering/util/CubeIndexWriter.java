package me.cortex.voxy.client.core.rendering.util;

import org.lwjgl.system.MemoryUtil;

final class CubeIndexWriter {
    private CubeIndexWriter() {}

    static long write(long ptr, int base) {
        // Bottom face
        MemoryUtil.memPutByte(ptr++, (byte) (0 + base));
        MemoryUtil.memPutByte(ptr++, (byte) (1 + base));
        MemoryUtil.memPutByte(ptr++, (byte) (2 + base));
        MemoryUtil.memPutByte(ptr++, (byte) (3 + base));
        MemoryUtil.memPutByte(ptr++, (byte) (2 + base));
        MemoryUtil.memPutByte(ptr++, (byte) (1 + base));

        // Top face
        MemoryUtil.memPutByte(ptr++, (byte) (6 + base));
        MemoryUtil.memPutByte(ptr++, (byte) (5 + base));
        MemoryUtil.memPutByte(ptr++, (byte) (4 + base));
        MemoryUtil.memPutByte(ptr++, (byte) (5 + base));
        MemoryUtil.memPutByte(ptr++, (byte) (6 + base));
        MemoryUtil.memPutByte(ptr++, (byte) (7 + base));

        // North face
        MemoryUtil.memPutByte(ptr++, (byte) (0 + base));
        MemoryUtil.memPutByte(ptr++, (byte) (4 + base));
        MemoryUtil.memPutByte(ptr++, (byte) (1 + base));
        MemoryUtil.memPutByte(ptr++, (byte) (5 + base));
        MemoryUtil.memPutByte(ptr++, (byte) (1 + base));
        MemoryUtil.memPutByte(ptr++, (byte) (4 + base));

        // South face
        MemoryUtil.memPutByte(ptr++, (byte) (3 + base));
        MemoryUtil.memPutByte(ptr++, (byte) (6 + base));
        MemoryUtil.memPutByte(ptr++, (byte) (2 + base));
        MemoryUtil.memPutByte(ptr++, (byte) (6 + base));
        MemoryUtil.memPutByte(ptr++, (byte) (3 + base));
        MemoryUtil.memPutByte(ptr++, (byte) (7 + base));

        // West face
        MemoryUtil.memPutByte(ptr++, (byte) (2 + base));
        MemoryUtil.memPutByte(ptr++, (byte) (4 + base));
        MemoryUtil.memPutByte(ptr++, (byte) (0 + base));
        MemoryUtil.memPutByte(ptr++, (byte) (4 + base));
        MemoryUtil.memPutByte(ptr++, (byte) (2 + base));
        MemoryUtil.memPutByte(ptr++, (byte) (6 + base));

        // East face
        MemoryUtil.memPutByte(ptr++, (byte) (1 + base));
        MemoryUtil.memPutByte(ptr++, (byte) (5 + base));
        MemoryUtil.memPutByte(ptr++, (byte) (3 + base));
        MemoryUtil.memPutByte(ptr++, (byte) (7 + base));
        MemoryUtil.memPutByte(ptr++, (byte) (3 + base));
        MemoryUtil.memPutByte(ptr++, (byte) (5 + base));
        return ptr;
    }
}
