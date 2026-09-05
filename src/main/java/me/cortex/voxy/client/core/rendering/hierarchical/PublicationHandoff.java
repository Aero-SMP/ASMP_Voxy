package me.cortex.voxy.client.core.rendering.hierarchical;

import java.util.Objects;
import java.util.function.Supplier;

/** All-or-nothing single producer handoff. The factory runs only after capacity is acquired. */
public final class PublicationHandoff<T> {
    private T pending;
    private Throwable stopped;
    private long consumed;

    public synchronized T trySubmit(Supplier<T> prepare) {
        if (this.stopped != null) throw new IllegalStateException("renderer stopped", this.stopped);
        if (this.pending != null) return null;
        T value = Objects.requireNonNull(prepare.get());
        this.pending = value;
        return value;
    }

    public synchronized T take() {
        T value = this.pending;
        if (value != null) { this.pending = null; this.consumed++; }
        return value;
    }

    public synchronized long consumed() { return this.consumed; }
    public synchronized boolean occupied() { return this.pending != null; }
    public synchronized void stop(Throwable failure) { this.stopped = Objects.requireNonNull(failure); }
}
