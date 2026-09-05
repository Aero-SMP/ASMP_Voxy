package me.cortex.voxy.client.lod;

/** Render-thread-only, scoped input override for the real Ok Zoomer implementation. */
public final class DebugZoomControl implements AutoCloseable {
    private static DebugZoomControl owner;
    private static boolean available, observedZoom;
    private static double worldFov;
    private static long signalSample, fovSample;
    private boolean requested, previousZoom;
    private double previousFov;
    private long signalMarker, fovMarker;

    private DebugZoomControl() { this.requested = observedZoom; }

    public static DebugZoomControl begin() {
        if (!available || fovSample == 0) throw new IllegalStateException("Ok Zoomer/render observation unavailable");
        if (owner != null) throw new IllegalStateException("zoom already owned by a test");
        return owner = new DebugZoomControl();
    }

    public void select(boolean zoom) {
        if (owner != this) throw new IllegalStateException("expired zoom control");
        this.previousZoom = observedZoom;
        this.previousFov = worldFov;
        this.signalMarker = signalSample;
        this.fovMarker = fovSample;
        this.requested = zoom;
    }

    public boolean observed() {
        if (owner != this || signalSample <= this.signalMarker || fovSample <= this.fovMarker
                || observedZoom != this.requested) return false;
        // Idempotent input is allowed. A changed signal must also change the real rendered FOV;
        // acknowledgement does not claim the easing animation or terrain refinement has finished.
        return this.previousZoom == this.requested || (this.requested
                ? worldFov < this.previousFov - 0.01 : worldFov > this.previousFov + 0.01);
    }

    @Override public void close() { if (owner == this) owner = null; }

    /** Called only by the optional Ok Zoomer mixin; normal input passes through outside a run. */
    public static boolean zoomSignal(boolean normal) {
        available = true;
        signalSample++;
        return observedZoom = owner == null ? normal : owner.requested;
    }

    /** Final world-camera FOV after the normal getFov method, including Ok Zoomer's easing. */
    public static void recordWorldFov(double fov) {
        if (!Double.isFinite(fov) || fov <= 0) return;
        worldFov = fov;
        fovSample++;
    }

    public static boolean available() { return available; }
    public static boolean zoomActive() { return observedZoom; }
    public static double worldFov() { return worldFov; }
}
