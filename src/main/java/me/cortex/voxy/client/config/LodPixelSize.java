package me.cortex.voxy.client.config;

/** Projected section-size target, in render pixels (not per-block pixels). */
public final class LodPixelSize {
    public static final float DEFAULT = 64;
    public static final float MIN = 28, MAX = 256;
    public static final int SLIDER_MAX = 100;
    private static final double LOG_RANGE = Math.log(MAX / MIN);

    private LodPixelSize() {}

    public static float validate(float pixels) {
        if (!Float.isFinite(pixels) || pixels <= 0) return DEFAULT;
        return Math.clamp(pixels, MIN, MAX);
    }

    public static float fromSlider(int position) {
        return validate((float) (MIN * Math.exp(LOG_RANGE
                * Math.clamp(position, 0, SLIDER_MAX) / SLIDER_MAX)));
    }

    public static int toSlider(float pixels) {
        return Math.clamp((int) Math.round(Math.log(validate(pixels) / MIN)
                / LOG_RANGE * SLIDER_MAX), 0, SLIDER_MAX);
    }

    public static String label(int position, float configured) {
        // Historical/manual values need not sit exactly on a logarithmic step.
        // Opening the menu must neither relabel 64 as 63 nor quantize the saved value.
        float pixels = position == toSlider(configured) ? validate(configured) : fromSlider(position);
        return Math.round(pixels) + " px";
    }
}
