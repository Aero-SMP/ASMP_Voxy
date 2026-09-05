package me.cortex.voxy.client.compat;

/** Extend the available magnification, not the exponential step count or default zoom. */
public final class ZoomRange {
    private ZoomRange() {}

    public static int maximumStep(int base, int resolution, int configuredLimit) {
        long extra = (long) Math.ceil(resolution * Math.log(10.0) / Math.log(base));
        // Leave room for Ok Zoomer's step + 1 before its clamp; do not introduce overflow.
        return (int) Math.min(Integer.MAX_VALUE - 1L, configuredLimit + extra);
    }

    public static double maximumDivisor(int base, int resolution, int configuredLimit) {
        return Math.min(Double.MAX_VALUE, 10.0 * Math.pow(base, (double) configuredLimit / resolution));
    }
}
