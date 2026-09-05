package me.cortex.voxy.client.lod;

import java.util.Objects;
import java.util.function.Consumer;

/** One real worker operation. Claiming its result transfers the value, not the slot lease. */
final class WorkerResource<T> {
    enum State { IDLE, RUNNING, COMPLETED, CLOSED }
    record Lease(int slot, long generation) {}
    record Completion<T>(Lease lease, T value) {}

    private final int slot;
    private final Consumer<T> dispose;
    private long generation;
    private State state = State.IDLE;
    private T result;

    WorkerResource(int slot, Consumer<T> dispose) {
        this.slot = slot;
        this.dispose = dispose;
    }

    synchronized Lease acquire() {
        if (this.state != State.IDLE) return null;
        this.state = State.RUNNING;
        return new Lease(this.slot, ++this.generation);
    }

    synchronized boolean matches(Lease lease) {
        return lease != null && lease.slot == this.slot && lease.generation == this.generation
                && this.state != State.IDLE && this.state != State.CLOSED;
    }

    synchronized void complete(Lease lease, T value) {
        Objects.requireNonNull(value);
        if (!matches(lease)) { this.dispose.accept(value); return; }
        if (this.state != State.RUNNING) throw new IllegalStateException("duplicate worker completion");
        this.result = value;
        this.state = State.COMPLETED;
    }

    synchronized Completion<T> claim() {
        if (this.state != State.COMPLETED || this.result == null) return null;
        T value = this.result;
        this.result = null;
        return new Completion<>(new Lease(this.slot, this.generation), value);
    }

    synchronized boolean release(Lease lease) {
        if (!matches(lease) || this.state != State.COMPLETED) return false;
        if (this.result != null) throw new IllegalStateException("worker result must be claimed");
        this.state = State.IDLE;
        return true;
    }

    synchronized State state() { return this.state; }
    synchronized T pendingResult() { return this.result; }

    synchronized void close() {
        this.state = State.CLOSED;
        if (this.result != null) this.dispose.accept(this.result);
        this.result = null;
    }
}
