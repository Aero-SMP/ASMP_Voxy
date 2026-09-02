package me.cortex.voxy.client.lod;

import java.util.EnumMap;
import java.util.Objects;
import java.util.Optional;

/**
 * One hard byte budget for every retained or in-flight Virtual Surface allocation.
 *
 * <p>Pools are accounting labels, not independent allowances.  A reservation succeeds only when
 * the sum of every pool remains within {@link #limit()}.  This prevents individually reasonable
 * compressed, decoded, meshing, geometry, fallback, and transport limits from multiplying into an
 * unbounded process total.</p>
 */
public final class MemoryBudget {
    public enum Pool {
        MANIFEST,
        OBJECT_TABLE,
        COMPRESSED,
        DECODED,
        MESHING,
        GEOMETRY,
        COMPILED_CACHE,
        IN_FLIGHT
    }

    /** Byte counts for one admission. */
    public record Allocation(long manifest, long objectTable, long compressed, long decoded,
                             long meshing, long geometry, long compiledCache,
                             long inFlight) {
        public static final Allocation ZERO = new Allocation(0, 0, 0, 0, 0, 0, 0, 0);

        public Allocation {
            if ((manifest | objectTable | compressed | decoded | meshing | geometry
                    | compiledCache | inFlight) < 0) {
                throw new IllegalArgumentException("memory allocations cannot be negative");
            }
        }

        public static Allocation of(Pool pool, long bytes) {
            Objects.requireNonNull(pool, "pool");
            return switch (pool) {
                case MANIFEST -> new Allocation(bytes, 0, 0, 0, 0, 0, 0, 0);
                case OBJECT_TABLE -> new Allocation(0, bytes, 0, 0, 0, 0, 0, 0);
                case COMPRESSED -> new Allocation(0, 0, bytes, 0, 0, 0, 0, 0);
                case DECODED -> new Allocation(0, 0, 0, bytes, 0, 0, 0, 0);
                case MESHING -> new Allocation(0, 0, 0, 0, bytes, 0, 0, 0);
                case GEOMETRY -> new Allocation(0, 0, 0, 0, 0, bytes, 0, 0);
                case COMPILED_CACHE -> new Allocation(0, 0, 0, 0, 0, 0, bytes, 0);
                case IN_FLIGHT -> new Allocation(0, 0, 0, 0, 0, 0, 0, bytes);
            };
        }

        public long bytes(Pool pool) {
            return switch (pool) {
                case MANIFEST -> this.manifest;
                case OBJECT_TABLE -> this.objectTable;
                case COMPRESSED -> this.compressed;
                case DECODED -> this.decoded;
                case MESHING -> this.meshing;
                case GEOMETRY -> this.geometry;
                case COMPILED_CACHE -> this.compiledCache;
                case IN_FLIGHT -> this.inFlight;
            };
        }

        public long totalBytes() {
            long total = 0;
            for (Pool pool : Pool.values()) total = Math.addExact(total, bytes(pool));
            return total;
        }

    }

    private final long limit;
    private final EnumMap<Pool, Long> used = new EnumMap<>(Pool.class);
    private long totalUsed;

    public MemoryBudget(long limit) {
        if (limit < 1) throw new IllegalArgumentException("memory limit must be positive");
        this.limit = limit;
        for (Pool pool : Pool.values()) {
            this.used.put(pool, 0L);
        }
    }

    /**
     * Reserves every requested pool or none of them. A failed multi-pool admission never leaves
     * partial accounting behind.
     */
    public synchronized Optional<Reservation> tryReserve(Allocation request) {
        Objects.requireNonNull(request, "request");
        long requested = request.totalBytes();
        if (requested > this.limit - this.totalUsed) return Optional.empty();
        for (Pool pool : Pool.values()) {
            this.used.put(pool, this.used.get(pool) + request.bytes(pool));
        }
        this.totalUsed += requested;
        return Optional.of(new Reservation(this, request));
    }

    public synchronized long used(Pool pool) {
        return this.used.get(Objects.requireNonNull(pool, "pool"));
    }

    public synchronized long used() {
        return this.totalUsed;
    }

    public synchronized long available() {
        return this.limit - this.totalUsed;
    }

    public long limit() {
        return this.limit;
    }

    private synchronized void release(Allocation allocation) {
        for (Pool pool : Pool.values()) {
            if (allocation.bytes(pool) > this.used.get(pool)) {
                throw new IllegalStateException("memory-budget accounting underflow");
            }
        }
        for (Pool pool : Pool.values()) {
            this.used.put(pool, this.used.get(pool) - allocation.bytes(pool));
        }
        this.totalUsed -= allocation.totalBytes();
        if (this.totalUsed < 0) throw new IllegalStateException("memory-budget total underflow");
    }

    private synchronized void reduce(Allocation oldAllocation, Allocation newAllocation) {
        for (Pool pool : Pool.values()) {
            long released = oldAllocation.bytes(pool) - newAllocation.bytes(pool);
            if (released < 0) {
                throw new IllegalArgumentException("a reservation can only be reduced");
            }
            if (released > this.used.get(pool)) {
                throw new IllegalStateException("memory-budget accounting underflow");
            }
        }
        for (Pool pool : Pool.values()) {
            long released = oldAllocation.bytes(pool) - newAllocation.bytes(pool);
            this.used.put(pool, this.used.get(pool) - released);
        }
        this.totalUsed -= oldAllocation.totalBytes() - newAllocation.totalBytes();
    }

    /** Atomically replaces one live reservation, admitting growth only when the global cap allows it. */
    private synchronized boolean resize(Allocation oldAllocation, Allocation newAllocation) {
        long oldTotal = oldAllocation.totalBytes();
        long newTotal = newAllocation.totalBytes();
        long retained = this.totalUsed - oldTotal;
        if (retained < 0) throw new IllegalStateException("memory-budget accounting underflow");
        if (newTotal > this.limit - retained) return false;
        for (Pool pool : Pool.values()) {
            long current = this.used.get(pool);
            long oldBytes = oldAllocation.bytes(pool);
            if (oldBytes > current) {
                throw new IllegalStateException("memory-budget accounting underflow");
            }
            this.used.put(pool, Math.addExact(current - oldBytes, newAllocation.bytes(pool)));
        }
        this.totalUsed = Math.addExact(retained, newTotal);
        return true;
    }

    public static final class Reservation implements AutoCloseable {
        private MemoryBudget owner;
        private Allocation allocation;

        private Reservation(MemoryBudget owner, Allocation allocation) {
            this.owner = owner;
            this.allocation = allocation;
        }

        /** Releases unused parts while retaining the remaining admission atomically. */
        public void reduceTo(Allocation remaining) {
            Objects.requireNonNull(remaining, "remaining");
            MemoryBudget budget;
            Allocation old;
            synchronized (this) {
                if (this.owner == null) throw new IllegalStateException("reservation is closed");
                budget = this.owner;
                old = this.allocation;
                budget.reduce(old, remaining);
                this.allocation = remaining;
            }
        }

        /** Grows or shrinks this reservation atomically without exposing an unaccounted interval. */
        public boolean tryResizeTo(Allocation replacement) {
            Objects.requireNonNull(replacement, "replacement");
            synchronized (this) {
                if (this.owner == null) throw new IllegalStateException("reservation is closed");
                if (!this.owner.resize(this.allocation, replacement)) return false;
                this.allocation = replacement;
                return true;
            }
        }

        @Override
        public void close() {
            MemoryBudget budget;
            Allocation allocation;
            synchronized (this) {
                budget = this.owner;
                this.owner = null;
                allocation = this.allocation;
                this.allocation = Allocation.ZERO;
            }
            if (budget != null) budget.release(allocation);
        }
    }
}
