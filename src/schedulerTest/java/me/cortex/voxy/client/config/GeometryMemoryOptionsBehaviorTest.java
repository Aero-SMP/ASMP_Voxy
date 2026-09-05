package me.cortex.voxy.client.config;

public final class GeometryMemoryOptionsBehaviorTest {
    public static void run() {
        long mib = 1024L * 1024L;
        check(2048 * mib, 2048);
        check(2048 * mib - 1024, 2048);
        check(2000 * mib, 1536);
        check(12 * 1024 * mib, 12 * 1024);
        check(14 * 1024 * mib, 12 * 1024);
        check(32 * 1024 * mib, 28 * 1024);
        check(0, 256);
        check(128 * mib, 256);
        int[] options = GeometryMemoryOptions.available(2048 * mib);
        options[options.length - 1] = 1;
        check(2048 * mib, 2048);
    }

    private static void check(long limit, int expected) {
        int[] options = GeometryMemoryOptions.available(limit);
        if (GeometryMemoryOptions.maximum(limit) != expected
                || options[options.length - 1] != expected) {
            throw new AssertionError("GPU default must match highest slider option for " + limit);
        }
        for (int i = 1; i < options.length; i++) {
            if (options[i - 1] >= options[i]) throw new AssertionError("GPU options not ordered");
        }
    }
}
