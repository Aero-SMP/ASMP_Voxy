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
    private Runnable restoreScroll;

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

    /** Exercise the actual last scroll step and both production range clamps. */
    public void maximum() {
        if (owner != this) throw new IllegalStateException("expired zoom control");
        var config = page.langeweile.ok_zoomer.config.OkZoomerConfigManager.CONFIG.zoomScrolling;
        int base = (Integer) config.scrollBase.value(), resolution = (Integer) config.scrollResolution.value();
        int configuredLimit = (Integer) config.scrollStepLimit.value();
        int step = page.langeweile.ok_zoomer.utils.ZoomUtils.zoomStep;
        double divisor = page.langeweile.ok_zoomer.zoom.Zoom.getZoomDivisor();
        if (this.restoreScroll == null) this.restoreScroll = () -> {
            page.langeweile.ok_zoomer.utils.ZoomUtils.zoomStep = step;
            page.langeweile.ok_zoomer.zoom.Zoom.setZoomDivisor(divisor);
        };
        int maximum = me.cortex.voxy.client.compat.ZoomRange.maximumStep(
                base, resolution, configuredLimit);
        double expected = me.cortex.voxy.client.compat.ZoomRange.maximumDivisor(
                base, resolution, configuredLimit);
        page.langeweile.ok_zoomer.utils.ZoomUtils.zoomStep = configuredLimit - 1;
        page.langeweile.ok_zoomer.utils.ZoomUtils.changeZoomDivisor(true);
        double originalMaximum = page.langeweile.ok_zoomer.zoom.Zoom.getZoomDivisor();
        if (originalMaximum != Math.pow(base, (double) configuredLimit / resolution))
            throw new IllegalStateException("original zoom steps changed");
        page.langeweile.ok_zoomer.utils.ZoomUtils.zoomStep = maximum - 1;
        page.langeweile.ok_zoomer.utils.ZoomUtils.changeZoomDivisor(true);
        page.langeweile.ok_zoomer.utils.ZoomUtils.keepZoomStepsWithinBounds();
        page.langeweile.ok_zoomer.utils.ZoomUtils.changeZoomDivisor(true);
        double actual = page.langeweile.ok_zoomer.zoom.Zoom.getZoomDivisor();
        if (page.langeweile.ok_zoomer.utils.ZoomUtils.zoomStep != maximum || actual != expected)
            throw new IllegalStateException("zoom maximum did not match production limit: " + actual + " != " + expected);
        ClientLodDebug.updaterEvent("state=ZOOM_MAX_VERIFIED base=" + config.scrollBase.value()
                + " resolution=" + config.scrollResolution.value() + " configuredSteps=" + config.scrollStepLimit.value()
                + " extendedSteps=" + maximum + " oldMaximum=" + originalMaximum + " newMaximum=" + actual);
    }

    @Override public void close() {
        if (owner != this) return;
        owner = null;
        if (this.restoreScroll != null) { this.restoreScroll.run(); this.restoreScroll = null; }
    }

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
