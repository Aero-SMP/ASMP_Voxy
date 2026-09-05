package me.cortex.voxy.client.core.rendering.hierarchical;

import java.util.Objects;
import java.util.function.Consumer;

/** One terminal upload result, claimed by its owner or disposed after abandonment. */
public final class PublicationOutcome<T> {
    private final Consumer<T> dispose;
    private boolean completed;
    private boolean claimed;
    private T result;
    private Runnable abandoned;

    public PublicationOutcome(Consumer<T> dispose) { this.dispose = dispose; }

    public void complete(T result) {
        Objects.requireNonNull(result);
        Runnable resolved;
        synchronized (this) {
            if (this.completed) return;
            this.completed = true;
            resolved = this.abandoned;
            if (resolved != null) this.claimed = true;
            else this.result = result;
        }
        if (resolved != null) {
            try { this.dispose.accept(result); }
            finally { resolved.run(); }
        }
    }

    public synchronized T claim() {
        if (!this.completed || this.claimed) return null;
        this.claimed = true;
        T value = this.result;
        this.result = null;
        return value;
    }

    public void abandon(Runnable resolved) {
        T discarded = null;
        boolean completed;
        synchronized (this) {
            if (this.abandoned != null) return;
            this.abandoned = Objects.requireNonNull(resolved);
            completed = this.completed;
            if (completed && !this.claimed) {
                discarded = this.result;
                this.result = null;
                this.claimed = true;
            }
        }
        if (completed) {
            try { if (discarded != null) this.dispose.accept(discarded); }
            finally { resolved.run(); }
        }
    }
}
