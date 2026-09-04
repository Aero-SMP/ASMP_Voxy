package me.cortex.voxy.debugtest;

/** Requires two matching rendered frames strictly after an expectation marker. */
public final class DebugPoseStabilizer {
    private long markerFrame;
    private int consecutive;

    public DebugPoseStabilizer(long markerFrame) {
        this.markerFrame = markerFrame;
    }

    public boolean observe(long frame, boolean matches) {
        if (frame <= this.markerFrame) return false;
        this.consecutive = matches ? this.consecutive + 1 : 0;
        return this.consecutive >= 2;
    }

    public int consecutive() { return this.consecutive; }
}
