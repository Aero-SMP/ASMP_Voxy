package me.cortex.voxy.client.lod;

import me.cortex.voxy.client.compat.ZoomRange;

final class ZoomRangeBehaviorTest {
    static void run() {
        for (int base : new int[]{2, 3, 10, 100}) for (int resolution : new int[]{1, 5, 20})
            for (int limit : new int[]{0, 10, 30, 100}) {
                int max = ZoomRange.maximumStep(base, resolution, limit);
                double old = Math.pow(base, (double) limit / resolution);
                double cap = ZoomRange.maximumDivisor(base, resolution, limit);
                check(cap == old * 10, "maximum magnification is not exactly 10x");
                check(Math.pow(base, (double) max / resolution) >= cap * (1 - 1e-14), "final step cannot reach cap");
                for (int step = 0; step <= limit; step++) {
                    double original = Math.pow(base, (double) step / resolution);
                    check(Math.min(original, cap) == original, "existing zoom step changed");
                }
            }
        check(ZoomRange.maximumStep(2, 5, 30) == 47 && ZoomRange.maximumDivisor(2, 5, 30) == 640,
                "default range should be 64x to 640x, not 10x as many exponential steps");
        check(ZoomRange.maximumStep(2, Integer.MAX_VALUE, Integer.MAX_VALUE) == Integer.MAX_VALUE - 1,
                "extended step arithmetic overflows");
        check(Double.isFinite(ZoomRange.maximumDivisor(Integer.MAX_VALUE, 1, Integer.MAX_VALUE)),
                "maximum overflowed to infinity");
        System.out.println("10x zoom range, unchanged lower steps and overflow tests passed");
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
