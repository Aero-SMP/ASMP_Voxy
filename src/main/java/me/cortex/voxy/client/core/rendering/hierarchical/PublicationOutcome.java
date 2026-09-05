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

    public synchronized void complete(T result) {
        Objects.requireNonNull(result);
        if (this.completed) return;
        this.completed = true;
        if (this.abandoned != null) {
            this.dispose.accept(result);
            this.claimed = true;
            this.abandoned.run();
        } else this.result = result;
    }

    public synchronized T claim() {
        if (!this.completed || this.claimed) return null;
        this.claimed = true;
        T value = this.result;
        this.result = null;
        return value;
    }

    public synchronized void abandon(Runnable resolved) {
        if (this.abandoned != null) return;
        this.abandoned = Objects.requireNonNull(resolved);
        if (this.completed) {
            if (!this.claimed) {
                this.dispose.accept(this.result);
                this.result = null;
                this.claimed = true;
            }
            resolved.run();
        }
    }
}
