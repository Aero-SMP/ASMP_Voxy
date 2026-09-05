package me.cortex.voxy.client.config;

import java.util.Arrays;

/** Shared GPU Memory slider choices and hardware-dependent default. */
public final class GeometryMemoryOptions {
    private static final long MIB = 1024L * 1024L;
    private static final int[] CHOICES = {
            256, 512, 768, 1024, 1536, 2048, 3072, 4096, 8192,
            12 * 1024, 16 * 1024, 20 * 1024, 24 * 1024, 28 * 1024
    };

    private GeometryMemoryOptions() {}

    private static int availableCount(long limitBytes) {
        // Allow a label to round over sub-MiB alignment; allocation still clamps
        // to the exact byte limit, including the reserved address sentinel.
        long limitMib = Math.max(0, limitBytes) / MIB;
        if (limitBytes > 0 && limitBytes % MIB != 0) limitMib++;
        int count = 0;
        while (count < CHOICES.length && CHOICES[count] <= limitMib) count++;
        // Keep a valid slider on broken/unknown drivers; allocation is clamped.
        return Math.max(1, count);
    }

    public static int[] available(long limitBytes) {
        return Arrays.copyOf(CHOICES, availableCount(limitBytes));
    }

    public static int maximum(long limitBytes) {
        return CHOICES[availableCount(limitBytes) - 1];
    }
}
