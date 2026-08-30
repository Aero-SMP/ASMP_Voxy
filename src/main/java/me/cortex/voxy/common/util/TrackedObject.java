package me.cortex.voxy.common.util;

public abstract class TrackedObject {
    private boolean freed;

    protected void free0() {
        if (this.freed) {
            throw new IllegalStateException("Object " + this + " was double freed.");
        }
        this.freed = true;
    }

    public abstract void free();

    public void assertNotFreed() {
        if (isFreed()) {
            throw new IllegalStateException("Object " + this + " should not be free, but is");
        }
    }

    public boolean isFreed() {
        return this.freed;
    }
}
