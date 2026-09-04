package me.cortex.voxy.debugtest;

/** Pure pose comparisons shared with deterministic harness tests. */
public final class DebugPoseMath {
    private DebugPoseMath() {}

    public static float angularDistance(float first, float second) {
        float wrapped = (first - second) % 360.0f;
        if (wrapped >= 180.0f) wrapped -= 360.0f;
        if (wrapped < -180.0f) wrapped += 360.0f;
        return Math.abs(wrapped);
    }
}
