package me.cortex.voxy.client.core.rendering.selection;

/** Production timing inputs used to choose a bounded swept-frustum horizon. */
public record SelectionTelemetry(long roundTripMicros, long throughputBytesPerSecond,
                                 long outstandingBytes, long meshingMicros) {
    public static final SelectionTelemetry DEFAULT = new SelectionTelemetry(
            100_000, 8L << 20, 0, 50_000);

    public SelectionTelemetry {
        if (roundTripMicros < 0 || throughputBytesPerSecond < 0 || outstandingBytes < 0
                || meshingMicros < 0) {
            throw new IllegalArgumentException("invalid Virtual Surface selection telemetry");
        }
    }
}
