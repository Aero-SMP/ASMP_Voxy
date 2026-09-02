package me.cortex.voxy.client.core.rendering.selection;

/** Network and meshing timing inputs used to choose a bounded swept-frustum horizon. */
public record PredictionTiming(long roundTripMicros, long throughputBytesPerSecond,
                               long outstandingBytes, long meshingMicros) {
    public static final PredictionTiming DEFAULT = new PredictionTiming(
            100_000, 8L << 20, 0, 50_000);

    public PredictionTiming {
        if (roundTripMicros < 0 || throughputBytesPerSecond < 0 || outstandingBytes < 0
                || meshingMicros < 0) {
            throw new IllegalArgumentException("invalid prediction timing");
        }
    }
}
