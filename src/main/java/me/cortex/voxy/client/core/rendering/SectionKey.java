package me.cortex.voxy.client.core.rendering;

/** Packed spatial keys shared by the renderer and regional hierarchy. */
public final class SectionKey {
    public static final int MAX_LOD_LAYER = 4;

    private SectionKey() {}

    public static long pack(int level, int x, int y, int z) {
        return ((long) level << 60)
                | ((long) (y & 0xFF) << 52)
                | ((long) (z & ((1 << 24) - 1)) << 28)
                | ((long) (x & ((1 << 24) - 1)) << 4);
    }

    public static int level(long id) {
        return (int) ((id >> 60) & 0xF);
    }

    public static int x(long id) {
        return (int) ((id << 36) >> 40);
    }

    public static int y(long id) {
        return (int) ((id << 4) >> 56);
    }

    public static int z(long id) {
        return (int) ((id << 12) >> 40);
    }

    public static String describe(long position) {
        return level(position) + "@[" + x(position) + ", " + y(position)
                + ", " + z(position) + ']';
    }
}
