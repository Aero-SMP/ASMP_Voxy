package me.cortex.voxy.debugtest;

/** One in-flight value plus one replaceable latest value, with stale-completion tokens. */
public final class DebugLatestMailbox<T> {
    private long generation = 1;
    private boolean inFlight;
    private T latest;
    private long coalesced;

    public synchronized Dispatch<T> offer(T value) {
        if (value == null) throw new NullPointerException("mailbox value");
        if (!this.inFlight) {
            this.inFlight = true;
            return new Dispatch<>(this.generation, value, 0);
        }
        if (this.latest != null) this.coalesced++;
        this.latest = value;
        return null;
    }

    public synchronized Dispatch<T> complete(long token) {
        if (token != this.generation || !this.inFlight) return null;
        if (this.latest == null) {
            this.inFlight = false;
            return null;
        }
        T next = this.latest;
        long skipped = this.coalesced;
        this.latest = null;
        this.coalesced = 0;
        return new Dispatch<>(this.generation, next, skipped);
    }

    public synchronized boolean isIdle() {
        return !this.inFlight && this.latest == null;
    }

    public synchronized void clear() {
        this.generation++;
        this.inFlight = false;
        this.latest = null;
        this.coalesced = 0;
    }

    public record Dispatch<T>(long generation, T value, long coalesced) {}
}
