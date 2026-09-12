package me.cortex.voxy.client.core.rendering;

import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ByteMap;
import it.unimi.dsi.fastutil.longs.LongHeapPriorityQueue;
import it.unimi.dsi.fastutil.objects.ObjectIterator;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongConsumer;
import java.util.function.Supplier;

/** Render-thread target publication only. Mutable planning lives on the hierarchy owner. */
public final class RenderDistanceTracker {
    private static final int CHECK_DISTANCE_BLOCKS = 128;
    private final Consumer<Window> targetChanged;
    // Render-owned sampling state; lives until renderer teardown. No per-stationary-frame allocation.
    private int radius = 2;
    private double posX, posZ;
    private Window window;
    // Render-written aggregate timings, retained only for this renderer's diagnostics.
    private volatile long submissions, submissionNanos, maximumSubmissionNanos;

    public record Window(int x, int z, int radius) {
        public boolean contains(long region) {
            long dx = (long) (int) region - x;
            long dz = (long) (int) (region >>> 32) - z;
            return Math.abs(dx) <= radius && Math.abs(dz) <= radius
                    && dx * dx + dz * dz <= (long) radius * radius;
        }
    }

    public RenderDistanceTracker(Consumer<Window> targetChanged) {
        this.targetChanged = targetChanged;
    }

    public void setRenderDistance(int radius) {
        if (this.radius == radius) return;
        this.radius = radius;
        if (this.window != null) this.publish();
    }

    public void setCenter(double x, double z) {
        double dx = this.posX - x, dz = this.posZ - z;
        if (this.window != null && dx * dx + dz * dz <= CHECK_DISTANCE_BLOCKS * CHECK_DISTANCE_BLOCKS) return;
        this.posX = x;
        this.posZ = z;
        this.publish();
    }

    private void publish() {
        int x = (int) Math.floor(this.posX / 512.0), z = (int) Math.floor(this.posZ / 512.0);
        if (this.window != null && this.window.x == x && this.window.z == z && this.window.radius == this.radius) return;
        long started = System.nanoTime();
        this.window = new Window(x, z, this.radius);
        this.targetChanged.accept(this.window);
        long elapsed = System.nanoTime() - started;
        this.submissions++;
        this.submissionNanos += elapsed;
        this.maximumSubmissionNanos = Math.max(this.maximumSubmissionNanos, elapsed);
    }

    public String submissionSnapshot() {
        return " terrainTargets=" + this.submissions + " terrainSubmitNs=" + this.submissionNanos
                + " terrainSubmitMaxNs=" + this.maximumSubmissionNanos;
    }

    /** Single hierarchy-owner state, released with that owner; no executor or output mailbox. */
    public static final class Planner {
        // Pending is desired-minus-applied CPU topology, never a duplicate resident set.
        private final Long2ByteOpenHashMap pending = new Long2ByteOpenHashMap();
        // Sign bit marks removal; packed LOD4 keys never use it. Comparator has no map lookups.
        private final LongHeapPriorityQueue ordered = new LongHeapPriorityQueue(this::compare);
        private final LongConsumer enter, leave;
        private final Supplier<Window> latest;
        private final BooleanSupplier running;
        private final Difference difference;
        // source: fully constructed desired window, NOT fully applied geometry.
        // target: current construction/ordering identity. Latest targets overwrite one external slot.
        private Window source, target;
        private ObjectIterator<Long2ByteMap.Entry> ordering;
        private int phase; // 0 idle, 1 difference, 2 undo partial difference, 3 ordering, 4 applying
        private long constructed, undoRemaining;
        // Owner-written bounded aggregate diagnostics, readable after slices; no sample queue.
        public volatile long orderingBuilds, differenceVisits, orderingVisits, appliedOperations, observedTargets;
        public volatile long preparationNanos, applicationNanos, slices;

        public Planner(int minY, int maxY, LongConsumer enter, LongConsumer leave,
                       Supplier<Window> latest, BooleanSupplier running) {
            this.difference = new Difference(minY, maxY);
            this.enter = enter;
            this.leave = leave;
            this.latest = latest;
            this.running = running;
        }

        public boolean hasWork() { return this.phase != 0 || this.latest.get() != this.target; }

        /** Budget includes preparation. An individual callback/allocation is not preemptible. */
        public int process() { return this.process(512, 1_000_000L); }

        /** Deterministic operation budget is also used by production-path regression fixtures. */
        public int process(int budget, long nanos) {
            if (!this.running.getAsBoolean() || !this.hasWork()) return 0;
            long start = System.nanoTime(), application = 0;
            int applied = 0;
            this.slices++;
            for (int step = 0; step < budget && this.running.getAsBoolean(); step++) {
                if (step != 0 && System.nanoTime() - start >= nanos) break;
                Window newest = this.latest.get();
                if (newest != this.target && this.phase != 2) {
                    this.observedTargets++;
                    this.ordering = null; // Never mutate pending underneath a retained iterator.
                    this.ordered.clear();
                    if (this.phase == 1 && this.constructed != 0) {
                        // Undo just the constructed prefix, not applied topology. Replaying the
                        // same resumable cursor avoids a history queue or a second world snapshot.
                        this.undoRemaining = this.constructed;
                        this.difference.reset(this.source, this.target);
                        this.phase = 2;
                    } else {
                        this.begin(newest);
                    }
                }
                if (this.phase == 0) break;
                if (this.phase == 1 || this.phase == 2) {
                    boolean changed = this.difference.advance();
                    this.differenceVisits++;
                    if (changed) {
                        byte operation = (byte) (this.phase == 2 ? -this.difference.operation : this.difference.operation);
                        long key = this.difference.key;
                        byte previous = this.pending.get(key);
                        int next = previous + operation;
                        if (next < -1 || next > 1) throw new IllegalStateException("invalid terrain difference");
                        // Keep zero tombstones until the ordering iterator removes them (without
                        // fastutil's per-remove shrink/rehash). Reuse peak workspace across targets.
                        this.pending.put(key, (byte) next);
                        if (this.phase == 2) this.undoRemaining--;
                        else this.constructed++;
                    }
                    if (this.phase == 2 && this.undoRemaining == 0) {
                        this.begin(this.latest.get());
                    } else if (this.phase == 1 && this.difference.done()) {
                        this.source = this.target;
                        this.ordering = this.pending.long2ByteEntrySet().fastIterator();
                        this.orderingBuilds++;
                        this.phase = 3;
                    }
                } else if (this.phase == 3) {
                    if (this.ordering.hasNext()) {
                        var entry = this.ordering.next();
                        if (entry.getByteValue() == 0) this.ordering.remove();
                        else this.ordered.enqueue(entry.getLongKey() | (entry.getByteValue() < 0 ? Long.MIN_VALUE : 0));
                        this.orderingVisits++;
                    } else {
                        this.ordering = null;
                        this.phase = 4;
                    }
                } else {
                    if (this.ordered.isEmpty()) { this.pending.clear(); this.phase = 0; break; }
                    long encoded = this.ordered.firstLong(), key = encoded & Long.MAX_VALUE;
                    long before = System.nanoTime();
                    // If this throws, the owner fails terminally and pending still describes the
                    // unacknowledged operation. Never silently retry half-applied topology.
                    if (encoded < 0) this.leave.accept(key); else this.enter.accept(key);
                    application += System.nanoTime() - before;
                    this.ordered.dequeueLong();
                    this.pending.put(key, (byte) 0);
                    this.appliedOperations++;
                    applied++;
                }
            }
            this.applicationNanos += application;
            this.preparationNanos += System.nanoTime() - start - application;
            return applied;
        }

        private void begin(Window newest) {
            this.target = newest;
            this.constructed = 0;
            this.difference.reset(this.source, newest);
            this.phase = newest == null ? 0 : 1;
        }

        private int compare(long a, long b) {
            boolean remove = a < 0;
            if (remove != (b < 0)) return remove ? 1 : -1;
            long ax = (long) SectionKey.x(a) - this.target.x, az = (long) SectionKey.z(a) - this.target.z;
            long bx = (long) SectionKey.x(b) - this.target.x, bz = (long) SectionKey.z(b) - this.target.z;
            int order = Long.compare(ax * ax + az * az, bx * bx + bz * bz);
            if (order != 0) return remove ? -order : order;
            long ak = Integer.toUnsignedLong(SectionKey.x(a)) | (Integer.toUnsignedLong(SectionKey.z(a)) << 32);
            long bk = Integer.toUnsignedLong(SectionKey.x(b)) | (Integer.toUnsignedLong(SectionKey.z(b)) << 32);
            order = Long.compareUnsigned(ak, bk);
            return order != 0 ? order : Integer.compare(SectionKey.y(a), SectionKey.y(b));
        }

        /** Row-interval subtraction: O(radius + changed cells), including small moves.
         * Each advance performs at most one row setup or one vertical section operation.
         * Cursor reset/replay handles interruption during a partially constructed column.
         */
        private static final class Difference {
            private final int minY, maxY;
            private Window oldWindow, newWindow;
            private int pass, x, z, endZ, secondZ, secondEnd, y;
            private boolean rowReady;
            long key;
            byte operation;

            Difference(int minY, int maxY) { this.minY = minY; this.maxY = maxY; }

            void reset(Window oldWindow, Window newWindow) {
                this.oldWindow = oldWindow;
                this.newWindow = newWindow;
                this.pass = 0;
                this.rowReady = false;
                this.x = oldWindow == null ? 0 : oldWindow.x - oldWindow.radius;
            }

            boolean done() { return this.pass == 2; }

            boolean advance() {
                if (this.done()) return false;
                Window circle = this.pass == 0 ? this.oldWindow : this.newWindow;
                Window other = this.pass == 0 ? this.newWindow : this.oldWindow;
                if (circle == null || this.x > circle.x + circle.radius) {
                    this.pass++;
                    this.rowReady = false;
                    this.x = this.newWindow == null ? 0 : this.newWindow.x - this.newWindow.radius;
                    return false;
                }
                if (!this.rowReady) {
                    long dx = (long) this.x - circle.x;
                    int d = (int) Math.sqrt((long) circle.radius * circle.radius - dx * dx);
                    this.z = circle.z - d;
                    this.endZ = circle.z + d;
                    this.secondZ = 1;
                    this.secondEnd = 0;
                    if (other != null && Math.abs((long) this.x - other.x) <= other.radius) {
                        long ox = (long) this.x - other.x;
                        int od = (int) Math.sqrt((long) other.radius * other.radius - ox * ox);
                        int low = other.z - od, high = other.z + od;
                        if (low <= this.endZ && high >= this.z) {
                            this.secondZ = Math.max(this.z, high + 1);
                            this.secondEnd = this.endZ;
                            this.endZ = Math.min(this.endZ, low - 1);
                        }
                    }
                    this.y = this.minY;
                    this.rowReady = true;
                    return false;
                }
                if (this.z > this.endZ) {
                    if (this.secondZ <= this.secondEnd) {
                        this.z = this.secondZ;
                        this.endZ = this.secondEnd;
                        this.secondZ = 1;
                        this.secondEnd = 0;
                    } else {
                        this.x++;
                        this.rowReady = false;
                    }
                    return false;
                }
                this.key = SectionKey.pack(4, this.x, this.y, this.z);
                this.operation = (byte) (this.pass == 0 ? -1 : 1);
                if (++this.y > this.maxY) { this.y = this.minY; this.z++; }
                return true;
            }
        }
    }
}
