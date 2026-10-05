package me.cortex.voxy.client.lod;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;

import java.util.AbstractMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * Owner-thread authority for regional section demand.
 *
 * <p>The table owns one record per section.  Cross-thread producers write only to the
 * coalescing mailboxes; runnable membership is intrusive, so reprioritising or cancelling a
 * section never leaves a stale job behind.</p>
 */
final class SectionDemandTable<D extends SectionDemandTable.Demand>
        extends AbstractMap<Long, D> {
    enum CandidateState {
        NONE, WAIT_REGION, READY_SOURCE, NETWORK_OWNED, WORKER_OWNED, WAIT_MODELS,
        RENDERER_OWNED
    }

    enum ReadyKind { SOURCE, RENDERER }

    record Ticket(long key, long sessionEpoch, long demandRevision, long regionGeneration,
                  int resourceSlot) {}

    record DetailUpdate(int action, int bucket, int epoch) {}

    static final class RegionDemand {
        final long key;
        int coverageUsers;
        int highestBucket;
        final int[] bucketCounts;
        int occupiedBuckets;
        int metadataBucket = -1;
        boolean metadataCoverage;
        RegionDemand metadataPrevious;
        RegionDemand metadataNext;
        boolean localTried;
        boolean localLoaded;
        long localIncarnation;
        Map<Long, LocalSection> localSections = Map.of();
        final Long2IntOpenHashMap localCoverage = new Long2IntOpenHashMap(0);
        final Map<Long, LocalSection> localCommits = new HashMap<>();
        volatile long metadataRevision;
        final LinkedHashMap<Long, Demand> members = new LinkedHashMap<>();

        RegionDemand(long key, int pixelBuckets) {
            this.key = key;
            this.bucketCounts = new int[pixelBuckets];
        }
    }

    static class Demand {
        final long key;
        final long regionKey;
        final boolean coverage;
        volatile long revision = 1;
        volatile long regionGeneration;
        int pixelBucket;
        volatile CandidateState candidate = CandidateState.NONE;

        ReadyKind readyKind;
        int readyBucket = -1;
        long readyRegion;
        Demand readyPrevious;
        Demand readyNext;

        Demand(long key, long regionKey, boolean coverage, int pixelBucket) {
            this.key = key;
            this.regionKey = regionKey;
            this.coverage = coverage;
            this.pixelBucket = pixelBucket;
        }

        boolean ready() { return this.readyKind != null; }

        Ticket ticket(long sessionEpoch, int resourceSlot) {
            return new Ticket(this.key, sessionEpoch, this.revision,
                    this.regionGeneration, resourceSlot);
        }
    }

    private static final class IntrusiveList {
        Demand head;
        Demand tail;
        int size;

        void add(Demand demand) {
            demand.readyPrevious = this.tail;
            demand.readyNext = null;
            if (this.tail == null) this.head = demand;
            else this.tail.readyNext = demand;
            this.tail = demand;
            this.size++;
        }

        void remove(Demand demand) {
            Demand previous = demand.readyPrevious;
            Demand next = demand.readyNext;
            if (previous == null) this.head = next;
            else previous.readyNext = next;
            if (next == null) this.tail = previous;
            else next.readyPrevious = previous;
            demand.readyPrevious = null;
            demand.readyNext = null;
            this.size--;
        }

        Demand removeFirst() {
            Demand result = this.head;
            if (result != null) this.remove(result);
            return result;
        }
    }

    /** Same-priority regions rotate after every claim. */
    private static final class ReadyGroup {
        final LinkedHashMap<Long, IntrusiveList> regions = new LinkedHashMap<>();
        int size;

        void add(Demand demand) {
            IntrusiveList list = this.regions.computeIfAbsent(demand.regionKey, ignored -> new IntrusiveList());
            list.add(demand);
            this.size++;
        }

        void remove(Demand demand) {
            IntrusiveList list = this.regions.get(demand.readyRegion);
            if (list == null) throw new IllegalStateException("missing ready region");
            list.remove(demand);
            this.size--;
            if (list.size == 0) {
                this.regions.remove(demand.readyRegion);
            }
        }

        Demand poll() {
            return this.regions.isEmpty() ? null : this.poll(this.regions.firstEntry().getKey());
        }

        Demand poll(long region) {
            IntrusiveList list = this.regions.get(region);
            if (list == null) return null;
            Demand result = list.removeFirst();
            this.size--;
            if (list.size == 0) this.regions.remove(region);
            else this.regions.putLast(region, list);
            return result;
        }
    }

    /** One metadata admission per region, directly unlinked when its priority changes. */
    private static final class RegionList {
        RegionDemand head;
        RegionDemand tail;

        void add(RegionDemand region) {
            region.metadataPrevious = this.tail;
            region.metadataNext = null;
            if (this.tail == null) this.head = region;
            else this.tail.metadataNext = region;
            this.tail = region;
        }

        void remove(RegionDemand region) {
            RegionDemand previous = region.metadataPrevious;
            RegionDemand next = region.metadataNext;
            if (previous == null) this.head = next;
            else previous.metadataNext = next;
            if (next == null) this.tail = previous;
            else next.metadataPrevious = previous;
            region.metadataPrevious = null;
            region.metadataNext = null;
        }
    }

    /** Latest-value mailbox; its memory is bounded by distinct identities, not event count. */
    private static final class CoalescingMailbox<V> {
        private final boolean ordered;
        private Map<Long, V> pending;
        private long overwritten;

        CoalescingMailbox(boolean ordered) {
            this.ordered = ordered;
            this.pending = this.newMap();
        }

        private Map<Long, V> newMap() {
            return this.ordered ? new LinkedHashMap<>() : new HashMap<>();
        }

        synchronized void offer(long key, V value) {
            if (this.pending.put(key, Objects.requireNonNull(value, "value")) != null) {
                this.overwritten++;
            }
        }

        synchronized Map<Long, V> take() {
            if (this.pending.isEmpty()) return Map.of();
            Map<Long, V> result = this.pending;
            this.pending = this.newMap();
            return result;
        }

        synchronized int size() { return this.pending.size(); }
        synchronized long overwritten() { return this.overwritten; }
        synchronized void clear() { this.pending.clear(); }
    }

    private final int pixelBuckets;
    private final long sessionEpoch;
    private long nextRevision;
    private final Map<Long, D> demands = new LinkedHashMap<>();
    private final Map<Long, RegionDemand> regions = new HashMap<>();
    private final RegionList[][] regionalReady;
    private int readyRegions;
    private final CoalescingMailbox<Boolean> topMailbox = new CoalescingMailbox<>(true);
    private final CoalescingMailbox<DetailUpdate> detailMailbox = new CoalescingMailbox<>(false);
    private final ReadyGroup[][][] ready;

    SectionDemandTable(int pixelBuckets) { this(pixelBuckets, 1); }

    SectionDemandTable(int pixelBuckets, long sessionEpoch) {
        if (pixelBuckets < 1 || pixelBuckets > Integer.SIZE) throw new IllegalArgumentException("pixelBuckets");
        if (sessionEpoch == 0) throw new IllegalArgumentException("sessionEpoch");
        this.pixelBuckets = pixelBuckets;
        this.sessionEpoch = sessionEpoch;
        this.regionalReady = new RegionList[2][pixelBuckets];
        for (var classes : this.regionalReady) {
            for (int bucket = 0; bucket < pixelBuckets; bucket++) classes[bucket] = new RegionList();
        }
        this.ready = new ReadyGroup[ReadyKind.values().length][2][pixelBuckets];
        for (int kind = 0; kind < this.ready.length; kind++) {
            for (int coverage = 0; coverage < 2; coverage++) {
                for (int bucket = 0; bucket < pixelBuckets; bucket++) {
                    this.ready[kind][coverage][bucket] = new ReadyGroup();
                }
            }
        }
    }

    void offerTop(long key, boolean entered) { this.topMailbox.offer(key, entered); }
    void offerDetail(long key, int action, int bucket, int epoch) {
        int bounded = Math.max(0, Math.min(this.pixelBuckets - 1, bucket));
        synchronized (this.detailMailbox) {
            // Preserve the newest unsigned GPU epoch even if producers race.
            DetailUpdate previous = this.detailMailbox.pending.get(key);
            if (previous != null && Integer.compareUnsigned(epoch, previous.epoch()) <= 0) return;
            this.detailMailbox.offer(key, new DetailUpdate(action, bounded, epoch));
        }
    }

    void drainTop(BiConsumer<Long, Boolean> consumer) {
        this.topMailbox.take().forEach(consumer);
    }
    void drainDetail(BiConsumer<Long, DetailUpdate> consumer) {
        this.detailMailbox.take().forEach(consumer);
    }


    D adopt(D demand) {
        Objects.requireNonNull(demand, "demand");
        D existing = this.demands.get(demand.key);
        if (existing != null) return existing;
        demand.revision = ++this.nextRevision;
        if (demand.revision == 0) demand.revision = ++this.nextRevision;
        demand.pixelBucket = Math.max(0, Math.min(this.pixelBuckets - 1,
                demand.pixelBucket));
        this.demands.put(demand.key, demand);
        RegionDemand region = this.regions.computeIfAbsent(demand.regionKey,
                key -> new RegionDemand(key, this.pixelBuckets));
        if (demand.coverage) region.coverageUsers++;
        region.members.put(demand.key, demand);
        this.changeBucket(region, demand.pixelBucket, 1);
        this.reclassifyRegion(region);
        return demand;
    }

    @Override public D get(Object key) { return this.demands.get(key); }
    D get(long key) { return this.demands.get(key); }
    @Override public boolean containsKey(Object key) { return this.demands.containsKey(key); }
    @Override public int size() { return this.demands.size(); }
    @Override public java.util.Collection<D> values() { return this.demands.values(); }
    @Override public Set<Entry<Long, D>> entrySet() {
        return java.util.Collections.unmodifiableSet(this.demands.entrySet());
    }
    RegionDemand region(long key) { return this.regions.get(key); }
    java.util.Collection<RegionDemand> regions() { return this.regions.values(); }
    int regionCount() { return this.regions.size(); }

    void readyRegion(RegionDemand region) {
        if (region != null && this.regions.get(region.key) == region
                && !region.localTried) {
            if (region.metadataBucket >= 0) this.reclassifyRegion(region);
            else this.linkRegion(region);
        }
    }

    RegionDemand pollRegion() {
        for (int coverage = 1; coverage >= 0; coverage--) {
            for (int bucket = this.pixelBuckets - 1; bucket >= 0; bucket--) {
                RegionDemand selected = this.regionalReady[coverage][bucket].head;
                if (selected != null) {
                    this.unlinkRegion(selected);
                    return selected;
                }
            }
        }
        return null;
    }

    int readyRegionCount() { return this.readyRegions; }

    private void changeBucket(RegionDemand region, int bucket, int delta) {
        int count = region.bucketCounts[bucket] += delta;
        if (count < 0) throw new IllegalStateException("regional priority accounting underflow");
        if (count == 0) region.occupiedBuckets &= ~(1 << bucket);
        else region.occupiedBuckets |= 1 << bucket;
        region.highestBucket = region.occupiedBuckets == 0 ? 0
                : Integer.SIZE - 1 - Integer.numberOfLeadingZeros(region.occupiedBuckets);
    }

    private void linkRegion(RegionDemand region) {
        region.metadataCoverage = region.coverageUsers > 0;
        region.metadataBucket = region.highestBucket;
        this.regionalReady[region.metadataCoverage ? 1 : 0][region.metadataBucket].add(region);
        this.readyRegions++;
    }

    private void unlinkRegion(RegionDemand region) {
        if (region.metadataBucket < 0) return;
        this.regionalReady[region.metadataCoverage ? 1 : 0][region.metadataBucket].remove(region);
        region.metadataBucket = -1;
        if (--this.readyRegions < 0) throw new IllegalStateException("regional ready accounting underflow");
    }

    private void reclassifyRegion(RegionDemand region) {
        if (region.metadataBucket < 0 || region.metadataBucket == region.highestBucket
                && region.metadataCoverage == (region.coverageUsers > 0)) return;
        this.unlinkRegion(region);
        this.linkRegion(region);
    }

    D remove(long key) {
        D demand = this.demands.get(key);
        if (demand == null) return null;
        RegionDemand region = this.regions.get(demand.regionKey);
        if (region == null || region.members.get(key) != demand) {
            throw new IllegalStateException("regional demand membership mismatch");
        }
        this.demands.remove(key);
        unlinkReady(demand);
        region.members.remove(demand.key);
        if (demand.coverage && --region.coverageUsers < 0) {
            throw new IllegalStateException("regional coverage accounting underflow");
        }
        this.changeBucket(region, demand.pixelBucket, -1);
        if (region.members.isEmpty()) {
            this.forgetUnusedRegion(region);
        } else this.reclassifyRegion(region);
        demand.revision++;
        return demand;
    }

    void forgetUnusedRegion(RegionDemand region) {
        if (region.members.isEmpty()) {
            this.regions.remove(region.key, region);
            this.unlinkRegion(region);
        }
    }

    void setPriority(Demand demand, int bucket) {
        requireCurrent(demand);
        bucket = Math.max(0, Math.min(this.pixelBuckets - 1, bucket));
        if (demand.pixelBucket == bucket) return;
        int previousBucket = demand.pixelBucket;
        ReadyKind kind = demand.readyKind;
        if (kind != null) unlinkReady(demand);
        demand.pixelBucket = bucket;
        RegionDemand region = this.regions.get(demand.regionKey);
        if (region != null) {
            this.changeBucket(region, previousBucket, -1);
            this.changeBucket(region, bucket, 1);
            this.reclassifyRegion(region);
        }
        if (kind != null) ready(demand, kind);
    }

    void revise(Demand demand) {
        requireCurrent(demand);
        unlinkReady(demand);
        demand.revision = ++this.nextRevision;
        if (demand.revision == 0) demand.revision = ++this.nextRevision;
    }

    void ready(Demand demand, ReadyKind kind) {
        requireCurrent(demand);
        unlinkReady(demand);
        demand.readyKind = Objects.requireNonNull(kind, "kind");
        demand.readyBucket = demand.coverage ? this.pixelBuckets - 1 : demand.pixelBucket;
        demand.readyRegion = demand.regionKey;
        group(demand).add(demand);
    }

    void owned(Demand demand, CandidateState state) {
        requireCurrent(demand);
        if (state == CandidateState.READY_SOURCE) {
            throw new IllegalArgumentException("use ready() for runnable state");
        }
        unlinkReady(demand);
        demand.candidate = Objects.requireNonNull(state, "state");
    }

    D poll(ReadyKind kind) {
        // Structural coverage wins regardless of its pixel bucket.
        for (int bucket = this.pixelBuckets - 1; bucket >= 0; bucket--) {
            Demand coverage = this.ready[kind.ordinal()][1][bucket].poll();
            if (coverage != null) return finishPollTyped(coverage);
        }
        for (int bucket = this.pixelBuckets - 1; bucket >= 0; bucket--) {
            Demand refinement = this.ready[kind.ordinal()][0][bucket].poll();
            if (refinement != null) return finishPollTyped(refinement);
        }
        return null;
    }

    D pollSameRegion(ReadyKind kind, long region, boolean coverage, int pixelBucket) {
        int bucket = coverage ? this.pixelBuckets - 1
                : Math.max(0, Math.min(this.pixelBuckets - 1, pixelBucket));
        Demand demand = this.ready[kind.ordinal()][coverage ? 1 : 0][bucket].poll(region);
        return demand == null ? null : finishPollTyped(demand);
    }

    private Demand finishPoll(Demand demand) {
        demand.readyKind = null;
        demand.readyBucket = -1;
        demand.readyRegion = 0;
        demand.readyPrevious = null;
        demand.readyNext = null;
        return demand;
    }

    @SuppressWarnings("unchecked")
    private D finishPollTyped(Demand demand) { return (D) finishPoll(demand); }

    void unlinkReady(Demand demand) {
        if (demand == null || demand.readyKind == null) return;
        group(demand).remove(demand);
        finishPoll(demand);
    }

    int readyCount(ReadyKind kind) {
        int count = 0;
        for (ReadyGroup[] classes : this.ready[kind.ordinal()]) {
            for (ReadyGroup group : classes) count += group.size;
        }
        return count;
    }

    int pendingInputCount() {
        return this.topMailbox.size() + this.detailMailbox.size();
    }

    long overwrittenInputCount() {
        return this.topMailbox.overwritten() + this.detailMailbox.overwritten();
    }

    boolean current(Ticket ticket) {
        Demand demand = this.demands.get(ticket.key());
        return ticket.sessionEpoch() == this.sessionEpoch && demand != null
                && demand.revision == ticket.demandRevision()
                && demand.regionGeneration == ticket.regionGeneration();
    }

    @Override public void clear() {
        for (Demand demand : this.demands.values()) unlinkReady(demand);
        for (RegionDemand region : this.regions.values()) this.unlinkRegion(region);
        this.demands.clear();
        this.regions.clear();
        this.topMailbox.clear();
        this.detailMailbox.clear();
    }

    void checkInvariants() {
        int memberships = 0;
        Map<Long, Integer> users = new HashMap<>();
        for (Demand demand : this.demands.values()) {
            RegionDemand region = this.regions.get(demand.regionKey);
            if (region == null || region.members.get(demand.key) != demand) {
                throw new IllegalStateException("regional demand membership mismatch");
            }
            users.merge(demand.regionKey, 1, Integer::sum);
            if (demand.readyKind != null) {
                memberships++;
                if (demand.readyBucket < 0 || demand.readyRegion != demand.regionKey) {
                    throw new IllegalStateException("invalid ready membership");
                }
            } else if (demand.readyPrevious != null || demand.readyNext != null) {
                throw new IllegalStateException("unindexed demand retains intrusive links");
            }
        }
        int indexed = 0;
        for (ReadyGroup[][] kinds : this.ready) {
            for (ReadyGroup[] classes : kinds) for (ReadyGroup group : classes) {
                indexed += group.size;
            }
        }
        if (memberships != indexed) throw new IllegalStateException("ready count mismatch");
        int metadataReady = 0;
        for (int coverage = 0; coverage < this.regionalReady.length; coverage++) {
            for (int bucket = 0; bucket < this.pixelBuckets; bucket++) {
                RegionList list = this.regionalReady[coverage][bucket];
                RegionDemand previous = null;
                for (RegionDemand region = list.head; region != null; region = region.metadataNext) {
                    if (++metadataReady > this.regions.size() || this.regions.get(region.key) != region
                            || region.localTried || region.metadataPrevious != previous
                            || region.metadataBucket != bucket || region.highestBucket != bucket
                            || region.metadataCoverage != (coverage != 0)
                            || region.metadataCoverage != (region.coverageUsers > 0)) {
                        throw new IllegalStateException("invalid regional ready membership");
                    }
                    previous = region;
                }
                if (list.tail != previous) throw new IllegalStateException("invalid regional ready tail");
            }
        }
        if (metadataReady != this.readyRegions) throw new IllegalStateException("regional ready count mismatch");
        for (RegionDemand region : this.regions.values()) {
            if (region.members.size() != users.getOrDefault(region.key, 0)) {
                throw new IllegalStateException("region user mismatch");
            }
            if (region.members.isEmpty()) {
                throw new IllegalStateException("region leak");
            }
            int coverage = 0;
            int[] counts = new int[this.pixelBuckets];
            for (var entry : region.members.entrySet()) {
                Demand member = entry.getValue();
                if (entry.getKey() != member.key || member.regionKey != region.key
                        || this.demands.get(member.key) != member) {
                    throw new IllegalStateException("stale regional member");
                }
                if (member.coverage) coverage++;
                counts[member.pixelBucket]++;
            }
            if (coverage != region.coverageUsers) {
                throw new IllegalStateException("region coverage mismatch");
            }
            int occupied = 0;
            for (int bucket = 0; bucket < counts.length; bucket++) {
                if (counts[bucket] != region.bucketCounts[bucket]) {
                    throw new IllegalStateException("region priority count mismatch");
                }
                if (counts[bucket] != 0) occupied |= 1 << bucket;
            }
            int highest = occupied == 0 ? 0 : Integer.SIZE - 1 - Integer.numberOfLeadingZeros(occupied);
            if (occupied != region.occupiedBuckets || highest != region.highestBucket) {
                throw new IllegalStateException("region priority mask mismatch");
            }
            if (region.metadataBucket < 0 && (region.metadataPrevious != null || region.metadataNext != null)) {
                throw new IllegalStateException("unindexed region retains metadata links");
            }
        }
    }

    private ReadyGroup group(Demand demand) {
        return this.ready[demand.readyKind.ordinal()][demand.coverage ? 1 : 0]
                [demand.readyBucket];
    }

    private void requireCurrent(Demand demand) {
        if (demand == null || this.demands.get(demand.key) != demand) {
            throw new IllegalArgumentException("demand is not owned by this table");
        }
    }
}
