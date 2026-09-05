package me.cortex.voxy.client.core;

import java.util.Objects;

/** Render-thread lifecycle of one renderer's replaceable shader group, not its terrain owners. */
public final class ShaderReloadCoordinator<T extends AutoCloseable> implements AutoCloseable {
    public enum Status { SUSPENDED, READY, FAILED, CLOSED }
    public interface Owner<T> {
        boolean current();
        void suspend();
        T prepare();
        void commit(T resources);
        void failed(Throwable failure);
        void incompatible(String reason);
    }
    public static final class Incompatible extends RuntimeException {
        public Incompatible(String reason) { super(reason); }
    }
    public final class Scope {
        private final long generation = ShaderReloadCoordinator.this.generation;
        private boolean ended;
        private Scope() {}
        public void finish(Throwable failure) {
            if (this.ended) return;
            this.ended = true;
            finishScope(this.generation, failure);
        }
    }

    private final Owner<T> owner;
    private T resources;
    private Status status = Status.SUSPENDED;
    private long generation, startedNanos, lastPauseNanos;
    private int depth;
    private Throwable failure;
    private String reason = "initial";

    public ShaderReloadCoordinator(Owner<T> owner) { this.owner = Objects.requireNonNull(owner); }
    public Status status() { return this.status; }
    public long generation() { return this.generation; }
    public String reason() { return this.reason; }
    public long lastPauseNanos() { return this.lastPauseNanos; }
    public boolean nestedReload() { return this.depth != 0 && this.status != Status.CLOSED; }
    public boolean drawable() { return this.status == Status.READY; }

    public Scope begin(String reason) {
        if (this.status == Status.CLOSED) return new Scope();
        if (this.depth++ == 0) {
            this.generation++;
            this.startedNanos = System.nanoTime();
            this.reason = reason;
            this.failure = null;
            this.status = Status.SUSPENDED;
            try { this.owner.suspend(); } catch (Throwable failure) { this.failure = failure; }
            this.disposeCurrent();
        }
        return new Scope();
    }

    private void finishScope(long generation, Throwable failure) {
        if (this.status == Status.CLOSED || generation != this.generation) return;
        if (failure != null) this.recordFailure(failure);
        if (--this.depth != 0) return;
        if (!this.owner.current()) { this.close(); return; }
        T prepared = null;
        try {
            if (this.failure != null) throw this.failure;
            prepared = this.owner.prepare();
            if (this.status == Status.CLOSED || generation != this.generation || !this.owner.current()) {
                prepared.close();
                return;
            }
            this.owner.commit(prepared);
            if (this.status == Status.CLOSED || generation != this.generation || !this.owner.current()) {
                prepared.close();
                return;
            }
            this.resources = prepared;
            this.status = Status.READY;
            this.lastPauseNanos = System.nanoTime() - this.startedNanos;
        } catch (Throwable problem) {
            if (prepared != null) try { prepared.close(); } catch (Throwable cleanup) { problem.addSuppressed(cleanup); }
            if (this.status == Status.CLOSED) return;
            this.failure = problem;
            this.status = Status.FAILED;
            this.reason = problem.getMessage() == null ? problem.getClass().getName() : problem.getMessage();
            this.lastPauseNanos = System.nanoTime() - this.startedNanos;
            if (problem instanceof Incompatible) this.owner.incompatible(this.reason);
            else this.owner.failed(problem);
        }
    }

    private void recordFailure(Throwable failure) {
        if (this.failure == null) this.failure = failure;
        else if (this.failure != failure) this.failure.addSuppressed(failure);
    }

    private void disposeCurrent() {
        T old = this.resources;
        this.resources = null;
        if (old != null) try { old.close(); } catch (Throwable failure) { this.recordFailure(failure); }
    }

    @Override public void close() {
        if (this.status == Status.CLOSED) return;
        this.status = Status.CLOSED;
        this.depth = 0;
        this.generation++;
        this.disposeCurrent();
    }
}
