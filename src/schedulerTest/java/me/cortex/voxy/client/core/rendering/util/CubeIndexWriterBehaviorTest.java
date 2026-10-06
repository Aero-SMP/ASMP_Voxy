package me.cortex.voxy.client.core.rendering.util;

import org.lwjgl.system.MemoryUtil;

/** Exercises the production native writer without loading GL-owning classes. */
public final class CubeIndexWriterBehaviorTest {
    public static void main(String[] args) {
        // Independent literal triangle winding; do not derive this from the writer.
        int[] winding = {0, 1, 2, 3, 2, 1, 6, 5, 4, 5, 6, 7,
                0, 4, 1, 5, 1, 4, 3, 6, 2, 6, 3, 7,
                2, 4, 0, 4, 2, 6, 1, 5, 3, 7, 3, 5};
        int[] bases = {0, 8, 248, 255, 256, -1, -8,
                Integer.MIN_VALUE, Integer.MIN_VALUE + 7,
                Integer.MAX_VALUE - 7, Integer.MAX_VALUE};
        int guard = 16;
        int size = guard * 2 + winding.length;
        long allocation = MemoryUtil.nmemAlloc(size);
        if (allocation == 0) throw new OutOfMemoryError("native fixture allocation failed");
        try {
            for (int base : bases) {
                MemoryUtil.memSet(allocation, 0x5A, size);
                long start = allocation + guard;
                long end = CubeIndexWriter.write(start, base);
                check(end == start + winding.length, "returned pointer at base " + base);
                for (int i = 0; i < winding.length; i++) {
                    check(MemoryUtil.memGetByte(start + i) == (byte) (base + winding[i]),
                            "winding at base " + base + " index " + i);
                }
                for (int i = 0; i < guard; i++) {
                    check(MemoryUtil.memGetByte(allocation + i) == (byte) 0x5A,
                            "leading guard at base " + base + " index " + i);
                    check(MemoryUtil.memGetByte(start + winding.length + i) == (byte) 0x5A,
                            "trailing guard at base " + base + " index " + i);
                }
            }
        } finally {
            MemoryUtil.nmemFree(allocation);
        }
        System.out.println("cube index writer winding, pointer, guard and integer-wrap checks passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
