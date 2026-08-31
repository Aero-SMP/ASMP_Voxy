package me.cortex.voxy.common.util;

import java.util.BitSet;
import java.util.Objects;

/** Fixed-capacity allocator for individual or consecutive integer IDs. */
public final class HierarchicalBitSet {
    public static final int SET_FULL = -1;
    private static final int MAX_CAPACITY = 1 << 24;

    private final BitSet allocated;
    private final int limit;
    private int count;

    public HierarchicalBitSet() {
        this(MAX_CAPACITY);
    }

    public HierarchicalBitSet(int limit) {
        if (limit < 0 || limit > MAX_CAPACITY) {
            throw new IllegalArgumentException("Limit outside allocator capacity");
        }
        this.limit = limit;
        this.allocated = new BitSet(limit);
    }

    public int allocateNext() {
        if (this.count == this.limit) return SET_FULL;
        int index = this.allocated.nextClearBit(0);
        if (index >= this.limit) return SET_FULL;
        this.allocated.set(index);
        this.count++;
        return index;
    }

    public int allocateNextConsecutiveCounted(int requested) {
        if (requested <= 0) throw new IllegalArgumentException("Count must be positive");
        if (requested > this.limit || this.count + requested > this.limit) return -2;

        int start = this.allocated.nextClearBit(0);
        while (start <= this.limit - requested) {
            int occupied = this.allocated.nextSetBit(start);
            if (occupied < 0 || occupied >= start + requested) {
                this.allocated.set(start, start + requested);
                this.count += requested;
                return start;
            }
            start = this.allocated.nextClearBit(occupied + 1);
        }
        return -2;
    }

    public boolean free(int index) {
        Objects.checkIndex(index, this.limit);
        if (!this.allocated.get(index)) return false;
        this.allocated.clear(index);
        this.count--;
        return true;
    }

    public boolean isSet(int index) {
        Objects.checkIndex(index, this.limit);
        return this.allocated.get(index);
    }

    public int getCount() {
        return this.count;
    }

    public int getLimit() {
        return this.limit;
    }

    public int getMaxIndex() {
        return this.allocated.length() - 1;
    }
}
