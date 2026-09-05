package me.cortex.voxy.client.core;

import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Owns shader-group allocations, including constructor-partial resources. Never owns terrain. */
public final class ShaderResourceScope implements AutoCloseable {
    private static final AtomicLong CREATED = new AtomicLong(), FREED = new AtomicLong();
    private final ArrayDeque<Runnable> cleanup = new ArrayDeque<>();
    private boolean closed;

    public <T> T own(T value, Consumer<T> release) {
        if (this.closed) throw new IllegalStateException("shader scope closed");
        this.cleanup.addFirst(() -> release.accept(value));
        CREATED.incrementAndGet();
        return value;
    }

    @Override public void close() {
        if (this.closed) return;
        this.closed = true;
        Throwable failure = null;
        while (!this.cleanup.isEmpty()) {
            try { this.cleanup.removeFirst().run(); FREED.incrementAndGet(); }
            catch (Throwable problem) {
                if (failure == null) failure = problem;
                else failure.addSuppressed(problem);
            }
        }
        if (failure != null) throw new IllegalStateException("shader resource cleanup failed", failure);
    }

    public void cleanupAfter(Throwable failure) {
        try { this.close(); } catch (Throwable cleanup) { failure.addSuppressed(cleanup); }
    }

    public static long created() { return CREATED.get(); }
    public static long freed() { return FREED.get(); }
}
