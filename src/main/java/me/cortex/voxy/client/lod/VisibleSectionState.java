package me.cortex.voxy.client.lod;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.longs.LongLists;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import me.cortex.voxy.client.core.rendering.SectionKey;

import java.util.Objects;

/**
 * Owner-confined membership derived from complete renderer visibility reports.
 * Keys follow the renderer's SectionKey contract (LOD levels zero through four).
 * Returned views are read-only and live; deltas last until the next accepted report.
 */
final class VisibleSectionState {
    private final Long2IntOpenHashMap keyIndices = new Long2IntOpenHashMap();
    private final LongArrayList activeKeys = new LongArrayList();
    private final LongArrayList seenEpochs = new LongArrayList();
    private final Long2IntOpenHashMap watchReferences = new Long2IntOpenHashMap();
    private final Long2IntOpenHashMap regionReferences = new Long2IntOpenHashMap();
    private final LongSet keys = LongSets.unmodifiable(this.keyIndices.keySet());
    private final LongSet watchKeys = LongSets.unmodifiable(this.watchReferences.keySet());
    private final LongSet regions = LongSets.unmodifiable(this.regionReferences.keySet());

    private final LongArrayList addedKeys = new LongArrayList();
    private final LongArrayList removedKeys = new LongArrayList();
    private final LongArrayList addedWatchKeys = new LongArrayList();
    private final LongArrayList removedWatchKeys = new LongArrayList();
    private final LongArrayList addedRegions = new LongArrayList();
    private final LongArrayList removedRegions = new LongArrayList();
    private final LongList addedKeysView = LongLists.unmodifiable(this.addedKeys);
    private final LongList removedKeysView = LongLists.unmodifiable(this.removedKeys);
    private final LongList addedWatchKeysView = LongLists.unmodifiable(this.addedWatchKeys);
    private final LongList removedWatchKeysView = LongLists.unmodifiable(this.removedWatchKeys);
    private final LongList addedRegionsView = LongLists.unmodifiable(this.addedRegions);
    private final LongList removedRegionsView = LongLists.unmodifiable(this.removedRegions);

    private long reportEpoch = -1;
    private long baseGeneration;
    private long generation;

    VisibleSectionState() {
        this.keyIndices.defaultReturnValue(-1);
    }

    /** Returns false for stale/equal reports without changing state or previous deltas. */
    boolean update(long reportEpoch, long[] keys) {
        if (reportEpoch <= this.reportEpoch) return false;
        Objects.requireNonNull(keys, "visible keys");
        this.addedKeys.clear(); this.removedKeys.clear();
        this.addedWatchKeys.clear(); this.removedWatchKeys.clear();
        this.addedRegions.clear(); this.removedRegions.clear();
        this.baseGeneration = this.generation;

        // Install all additions first: replacement keys sharing an ancestor or region
        // must not produce a transient removal followed by an addition.
        int uniqueKeys = 0;
        for (long key : keys) {
            int index = this.keyIndices.get(key);
            if (index >= 0) {
                if (this.seenEpochs.set(index, reportEpoch) != reportEpoch) uniqueKeys++;
                continue;
            }
            this.keyIndices.put(key, this.activeKeys.size());
            this.activeKeys.add(key); this.seenEpochs.add(reportEpoch);
            uniqueKeys++;
            this.addedKeys.add(key);
            addReferences(key);
        }

        int removals = this.activeKeys.size() - uniqueKeys;
        boolean bulkShrink = removals >= 512 && uniqueKeys <= this.activeKeys.size() / 4;
        // Sweep only live keys, including new additions, rather than hash-table capacity.
        for (int index = 0; index < this.activeKeys.size();) {
            if (this.seenEpochs.getLong(index) == reportEpoch) { index++; continue; }
            long key = this.activeKeys.getLong(index);
            this.removedKeys.add(key);
            if (!bulkShrink) {
                removeReferences(key);
                this.keyIndices.remove(key);
            }
            int last = this.activeKeys.size() - 1;
            if (index != last) {
                long moved = this.activeKeys.getLong(last);
                this.activeKeys.set(index, moved);
                this.seenEpochs.set(index, this.seenEpochs.getLong(last));
                if (!bulkShrink) this.keyIndices.put(moved, index);
            }
            this.activeKeys.removeLong(last); this.seenEpochs.removeLong(last);
        }
        if (bulkShrink) finishBulkShrink();

        if (!this.addedKeys.isEmpty() || !this.removedKeys.isEmpty())
            this.generation = Math.incrementExact(this.generation);
        this.reportEpoch = reportEpoch;
        return true;
    }

    /**
     * A large shrink would repeatedly shift clusters and halve three open-addressed maps.
     * Rebuild from the small surviving cut instead, emitting the same final transitions.
     * Temporary maps and removal-buffer growth occur inside this update; no history is kept.
     */
    private void finishBulkShrink() {
        var nextWatches = new Long2IntOpenHashMap();
        var nextRegions = new Long2IntOpenHashMap();
        this.keyIndices.clear();
        for (int index = 0; index < this.activeKeys.size(); index++) {
            long key = this.activeKeys.getLong(index);
            this.keyIndices.put(key, index);
            incrementCount(nextRegions, regionFor(key));
            for (;;) {
                incrementCount(nextWatches, key);
                if (SectionKey.level(key) == SectionKey.MAX_LOD_LAYER) break;
                key = parent(key);
            }
        }
        replaceReferences(this.watchReferences, nextWatches, this.removedWatchKeys);
        replaceReferences(this.regionReferences, nextRegions, this.removedRegions);
        // Keep the original map objects for existing live views. One best-effort trim per
        // map releases oversized tables; reusable dense/delta list capacities remain.
        this.keyIndices.trim();
    }

    private static void replaceReferences(Long2IntOpenHashMap counts, Long2IntOpenHashMap next,
                                          LongArrayList removed) {
        for (var entries = counts.long2IntEntrySet().fastIterator(); entries.hasNext();) {
            long key = entries.next().getLongKey();
            if (!next.containsKey(key)) removed.add(key);
        }
        counts.clear(); counts.putAll(next); counts.trim();
    }

    private static void incrementCount(Long2IntOpenHashMap counts, long key) {
        counts.put(key, Math.incrementExact(counts.get(key)));
    }

    private void addReferences(long key) {
        increment(this.regionReferences, regionFor(key), this.addedRegions);
        for (;;) {
            increment(this.watchReferences, key, this.addedWatchKeys);
            if (SectionKey.level(key) == SectionKey.MAX_LOD_LAYER) return;
            key = parent(key);
        }
    }

    private void removeReferences(long key) {
        decrement(this.regionReferences, regionFor(key), this.removedRegions);
        for (;;) {
            decrement(this.watchReferences, key, this.removedWatchKeys);
            if (SectionKey.level(key) == SectionKey.MAX_LOD_LAYER) return;
            key = parent(key);
        }
    }

    private static void increment(Long2IntOpenHashMap counts, long key, LongArrayList added) {
        int before = counts.get(key);
        counts.put(key, Math.incrementExact(before));
        if (before == 0) added.add(key);
    }

    private static void decrement(Long2IntOpenHashMap counts, long key, LongArrayList removed) {
        int before = counts.get(key);
        if (before <= 0) throw new IllegalStateException("visibility reference count underflow for " + key);
        if (before == 1) { counts.remove(key); removed.add(key); }
        else counts.put(key, before - 1);
    }

    private static long parent(long key) {
        return SectionKey.pack(SectionKey.level(key) + 1, SectionKey.x(key) >> 1,
                SectionKey.y(key) >> 1, SectionKey.z(key) >> 1);
    }

    private static long regionFor(long key) {
        int size = 16 >> SectionKey.level(key);
        int x = Math.floorDiv(SectionKey.x(key), size), z = Math.floorDiv(SectionKey.z(key), size);
        return Integer.toUnsignedLong(x) | Integer.toUnsignedLong(z) << 32;
    }

    long reportEpoch() { return this.reportEpoch; }
    long baseGeneration() { return this.baseGeneration; }
    long generation() { return this.generation; }
    LongSet keys() { return this.keys; }
    LongSet watchKeys() { return this.watchKeys; }
    LongSet regions() { return this.regions; }
    LongList addedKeys() { return this.addedKeysView; }
    LongList removedKeys() { return this.removedKeysView; }
    LongList addedWatchKeys() { return this.addedWatchKeysView; }
    LongList removedWatchKeys() { return this.removedWatchKeysView; }
    LongList addedRegions() { return this.addedRegionsView; }
    LongList removedRegions() { return this.removedRegionsView; }
}
