package me.cortex.voxy.client.lod;

/** Render-thread-only observation; no per-upload logging, allocation or production overhead. */
public final class GeometryEndpointTelemetry {
    private static long capacity;
    private static long highestEndpoint;
    private static boolean sparse;

    public static void reset(long bytes, boolean isSparse) {
        capacity = bytes;
        sparse = isSparse;
        highestEndpoint = 0;
    }

    public static void accepted(long endpointBytes) {
        highestEndpoint = Math.max(highestEndpoint, endpointBytes);
    }

    static void checkpoint() {
        me.cortex.voxy.common.Logger.info("Geometry endpoint checkpoint: capacity=" + capacity
                + " sparse=" + sparse + " highestAcceptedByteEndpoint=" + highestEndpoint);
    }
}
