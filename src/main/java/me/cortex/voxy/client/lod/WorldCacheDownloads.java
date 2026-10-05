package me.cortex.voxy.client.lod;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import me.cortex.voxy.client.config.ServerDownloadSettings;
import me.cortex.voxy.client.core.rendering.SectionKey;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.FutureTask;

/** Server-wide cache-only work. A lazy spatial frontier owns requests; journals own
 * completed sections. Local directory reads never run on the render-session owner. */
final class WorldCacheDownloads implements AutoCloseable {
    @FunctionalInterface interface ForegroundOwner { boolean owns(int dimensionId, long key); }
    private final ForegroundOwner foreground;
    private static final int SEARCH_LEVEL = 21;
    private final ServerDownloadSettings settings;
    private final RegionalMetadataStore metadata;
    private final Runnable wake;
    private final Map<Integer, Dimension> dimensions = new ConcurrentHashMap<>();
    private List<RegionalProtocol.DimensionExclusion> excludedDimensions = List.of();
    private final Map<Long, Job> jobs = new ConcurrentHashMap<>();
    private final Map<RegionalProtocol.ScopedKey, Job> requested = new HashMap<>();
    private final Set<RegionalProtocol.ScopedKey> drops = new HashSet<>();
    private final Set<DirectoryKey> directoryRequests = new LinkedHashSet<>();
    private FutureTask<DirectoryResult> directoryTask;
    private DirectoryKey directoryKey;
    private CatalogCodec.SharedNames catalogueNames = new CatalogCodec.SharedNames();
    private long directoryEpoch, diskStamp, diskRecovery, diskAdmission, sourceSections, sampledSections, sampledNamedBytes;
    private int activeDimension = -1;
    private Dimension viewedDimension;
    private Set<Long> viewedVisible;
    private long viewedEpoch = Long.MIN_VALUE;
    private volatile boolean closed;
    long committed, receivedBytes, skipped, failures;
    String lastFailure;

    static final class Job {
        final Dimension dimension;
        final long key, ticket, connection, diskRecovery, admissionGeneration, availabilityRevision;
        volatile int purpose;
        volatile boolean processing;
        long sentRank;
        Job(Dimension dimension, long key, long ticket, long connection, int purpose, long diskRecovery, long admissionGeneration) {
            this.dimension = dimension; this.key = key; this.ticket = ticket;
            this.connection = connection; this.purpose = purpose; this.diskRecovery = diskRecovery;
            this.admissionGeneration = admissionGeneration;
            this.availabilityRevision = dimension.regionRevisions.getOrDefault(regionFor(key), 0L);
            this.sentRank = rank(key, dimension.x, dimension.z);
        }
        RegionalProtocol.ScopedKey scope() { return new RegionalProtocol.ScopedKey(this.dimension.info.id(), this.key); }
    }
    private record Position(int level, int x, int y, int z) {}
    private record DirectoryKey(Dimension dimension, long region) {}
    private record DirectoryResult(DirectoryKey key, long epoch, long stamp,
                                   Map<Long, LocalSection> sections, long namedBytes, IOException failure) {}
    private record MetadataResult(boolean associated, RegionalProtocol.Hash32 catalogue,
                                  RegionalMetadataStore.Persistence outcome, Exception failure,
                                  boolean retryOnAdmission, long admissionGeneration, long policyGeneration) {}

    private static final class Node implements Comparable<Node> {
        final Dimension dimension;
        final int level, x, y, z;
        Node(Dimension dimension, int level, int x, int y, int z) {
            this.dimension = dimension; this.level = level; this.x = x; this.y = y; this.z = z;
        }
        Position position() { return new Position(this.level, this.x, this.y, this.z); }
        long key() { return SectionKey.pack(this.level, this.x, this.y, this.z); }
        boolean visible() {
            if (this.level > 4) return false;
            for (int level = this.level; level <= 4; level++) {
                int shift = level - this.level;
                if (this.dimension.visibleRoots.contains(SectionKey.pack(level,
                        this.x >> shift, this.y >> shift, this.z >> shift))) return true;
            }
            return false;
        }
        long rank() { return WorldCacheDownloads.rank(this.level, this.x, this.z, this.dimension.x, this.dimension.z); }
        @Override public int compareTo(Node other) {
            int result = Boolean.compare(other.visible(), this.visible());
            if (result == 0) result = Long.compare(this.rank(), other.rank());
            if (result == 0) result = Integer.compare(other.level, this.level);
            if (result == 0) result = Integer.compare(this.x, other.x);
            if (result == 0) result = Integer.compare(this.z, other.z);
            if (result == 0) result = Integer.compare(this.y, other.y);
            return result;
        }
    }

    /** Only visible regions and the current frontier region retain local summaries. */
    private static final class Coverage {
        final Map<Long, LocalSection> bindings;
        final Set<Long> full = new HashSet<>();
        Coverage(Map<Long, LocalSection> bindings) { this.bindings = bindings; }
        void rebuild(Dimension dimension) {
            this.full.clear();
            for (int level = 0; level <= 4; level++)
                for (var section : this.bindings.values())
                    if (SectionKey.level(section.key()) == level) update(dimension, section.key());
        }
        void update(Dimension dimension, long key) {
            LocalSection binding = this.bindings.get(key);
            boolean complete = binding != null && binding.kind() != LocalSection.ABSENT;
            int level = SectionKey.level(key);
            if (complete && level != 0) {
                int x = SectionKey.x(key) * 2, y = SectionKey.y(key) * 2, z = SectionKey.z(key) * 2;
                for (int dx = 0; dx < 2; dx++) for (int dy = 0; dy < 2; dy++) for (int dz = 0; dz < 2; dz++) {
                    var child = new Node(dimension, level - 1, x + dx, y + dy, z + dz);
                    if (dimension.height(child) && dimension.saved(child) && !this.full.contains(child.key())) complete = false;
                }
            }
            if (complete) this.full.add(key); else this.full.remove(key);
        }
        void committed(Dimension dimension, LocalSection section) {
            this.bindings.put(section.key(), section);
            long key = section.key();
            for (int level = SectionKey.level(key); level <= 4; level++) {
                update(dimension, key);
                key = SectionKey.pack(level + 1, SectionKey.x(key) >> 1, SectionKey.y(key) >> 1, SectionKey.z(key) >> 1);
            }
        }
    }

    static final class Dimension {
        volatile RegionalProtocol.DimensionInfo info;
        final CompletedSectionCache cache;
        final Map<Long, long[]> regions = new HashMap<>();
        final Map<Long, Long> regionRevisions = new HashMap<>();
        final Long2IntOpenHashMap occupancy = new Long2IntOpenHashMap();
        final Set<Long> sourceBlocked = new HashSet<>(), admissionBlocked = new HashSet<>();
        final Set<Long> unpublishedRegions = new HashSet<>(), parkedRegions = new HashSet<>();
        final Set<Long> visibleRoots = new HashSet<>(), visibleRegions = new HashSet<>();
        final Map<Long, Set<Long>> spaceBlocked = new HashMap<>();
        final Set<Long> unreadableRegions = new HashSet<>();
        final Map<Position, Node> queued = new HashMap<>();
        final Map<Long, Coverage> coverage = new HashMap<>();
        final Map<Long, Map<Long, LocalSection>> pendingBindings = new HashMap<>();
        PriorityQueue<Node> frontier = new PriorityQueue<>();
        volatile RegionalProtocol.CatalogMessage catalogue;
        RegionalProtocol.Hash32 persistedCatalogue = RegionalProtocol.Hash32.ZERO;
        RegionalProtocol.Hash32 failedCatalogue;
        RegionalProtocol.Hash32 attemptedCatalogue;
        FutureTask<MetadataResult> catalogueTask;
        boolean associationPersisted;
        boolean metadataRetryOnAdmission = true;
        long metadataBlockedGeneration = Long.MIN_VALUE;
        volatile long metadataPolicyGeneration;
        long catalogueStamp;
        int x, z;
        long probeRegion = Long.MIN_VALUE;
        boolean inventoryReady, inventoryComplete, inventoryFailed;
        long inventoryRevision, sourceSections;
        Dimension(RegionalProtocol.DimensionInfo info, CompletedSectionCache cache) { this.info = info; this.cache = cache; }
        void anchor(int x, int z) {
            if (this.x == x && this.z == z) return;
            this.x = x; this.z = z; reheap(); this.admissionBlocked.clear(); seed();
        }
        void reheap() { this.frontier = new PriorityQueue<>(this.queued.values()); }
        void resetFrontier() { this.frontier.clear(); this.queued.clear(); seed(); }
        void seed() {
            for (int x = -1; x <= 0; x++) for (int z = -1; z <= 0; z++) offer(new Node(this, SEARCH_LEVEL, x, 0, z));
            for (long key : this.visibleRoots) expand(node(key));
        }
        Node node(long key) { return new Node(this, SectionKey.level(key), SectionKey.x(key), SectionKey.y(key), SectionKey.z(key)); }
        boolean inside(Node node) {
            if (node.visible()) return true;
            return insideBorder(node);
        }
        boolean insideBorder(Node node) {
            long size = 32L << node.level;
            double radius = this.info.customBorder() ? this.info.borderSize() / 2 : 512;
            double cx = this.info.customBorder() ? this.info.centerX() : 0, cz = this.info.customBorder() ? this.info.centerZ() : 0;
            return (long) node.x * size < cx + radius && (node.x + 1L) * size > cx - radius
                    && (long) node.z * size < cz + radius && (node.z + 1L) * size > cz - radius;
        }
        boolean height(Node node) {
            if (node.level > 4) return true;
            int first = Math.floorDiv(this.info.minSectionY(), 1 << node.level);
            int last = Math.floorDiv(this.info.minSectionY() + this.info.sectionCount() - 1, 1 << node.level);
            return node.y >= first && node.y <= last;
        }
        boolean saved(Node node) {
            if (node.level > 4) return this.occupancy.get(coverKey(node.level, node.x, node.z)) != 0;
            int shift = 4 - node.level;
            long[] slots = this.regions.get(region(node.x >> shift, node.z >> shift));
            return savedSlots(slots, node.level, node.x, node.z);
        }
        static boolean savedSlots(long[] slots, int level, int x, int z) {
            if (slots == null) return false;
            int chunks = 2 << level, chunkX = (x * chunks) & 31, chunkZ = (z * chunks) & 31;
            long mask = ((1L << chunks) - 1) << chunkX;
            for (int row = chunkZ; row < chunkZ + chunks; row++) {
                int bit = row << 5;
                if ((slots[bit >>> 6] & mask << (bit & 63)) != 0) return true;
            }
            return false;
        }
        void expand(Node node) {
            if (node.level == 0) return;
            int level = node.level - 1;
            if (level == 4) {
                int first = Math.floorDiv(this.info.minSectionY(), 16), last = Math.floorDiv(this.info.minSectionY() + this.info.sectionCount() - 1, 16);
                for (int x = 0; x < 2; x++) for (int z = 0; z < 2; z++) for (int y = first; y <= last; y++)
                    offer(new Node(this, level, node.x * 2 + x, y, node.z * 2 + z));
            } else if (level > 4) {
                for (int x = 0; x < 2; x++) for (int z = 0; z < 2; z++) offer(new Node(this, level, node.x * 2 + x, 0, node.z * 2 + z));
            } else {
                for (int x = 0; x < 2; x++) for (int z = 0; z < 2; z++) for (int y = 0; y < 2; y++)
                    offer(new Node(this, level, node.x * 2 + x, node.y * 2 + y, node.z * 2 + z));
            }
        }
        void offer(Node node) {
            if (!inside(node) || !height(node) || !saved(node) || this.queued.putIfAbsent(node.position(), node) != null) return;
            this.frontier.add(node);
        }
        void trimCoverage() { this.coverage.keySet().removeIf(key -> key != this.probeRegion && !this.visibleRegions.contains(key)); }
    }

    WorldCacheDownloads(ServerDownloadSettings settings, RegionalMetadataStore store, Runnable wake, ForegroundOwner foreground) throws IOException {
        this.settings = settings; this.wake = wake; this.foreground = foreground;
        this.metadata = new RegionalMetadataStore(store.budget);
        this.metadata.bindServer(settings, settings.rawAddress());
        this.diskStamp = this.metadata.budget.stamp();
        this.diskRecovery = this.metadata.budget.recoveryGeneration(settings.serverId());
        this.diskAdmission = this.metadata.admissionGeneration();
    }
    void manifest(RegionalProtocol.Manifest manifest) throws IOException {
        this.excludedDimensions = manifest.excluded();
        Set<Integer> present = new HashSet<>();
        for (var info : manifest.dimensions()) {
            present.add(info.id());
            var old = this.dimensions.get(info.id());
            if (old != null && (!old.info.worldIdentity().equals(info.worldIdentity()) || !old.info.name().equals(info.name()))) {
                retire(old); old = null;
            }
            this.metadata.remember(info.name(), info.worldIdentity());
            if (old == null) {
                old = new Dimension(info, new CompletedSectionCache(this.metadata, info.worldIdentity(), info.name()));
                var anchor = this.settings.anchor(info.name(), (int) Math.floor(info.centerX()), (int) Math.floor(info.centerZ()));
                old.x = anchor.x(); old.z = anchor.z(); this.dimensions.put(info.id(), old);
                this.metadata.updateRetention(info.name(), old.x, old.z, Set.of());
            } else {
                boolean geometryChanged = old.info.minSectionY() != info.minSectionY() || old.info.sectionCount() != info.sectionCount()
                        || old.info.customBorder() != info.customBorder() || old.info.centerX() != info.centerX()
                        || old.info.centerZ() != info.centerZ() || old.info.borderSize() != info.borderSize();
                old.info = info;
                if (geometryChanged) {
                    old.sourceSections = 0;
                    for (var region : old.regions.entrySet()) old.sourceSections += countRegion(old, region.getKey(), region.getValue());
                    old.coverage.clear(); old.admissionBlocked.clear(); old.spaceBlocked.clear(); old.resetFrontier();
                }
            }
            startCatalogue(old);
        }
        for (var dimension : List.copyOf(this.dimensions.values())) if (!present.contains(dimension.info.id())) retire(dimension);
        estimate();
    }
    private void retire(Dimension dimension) {
        this.dimensions.remove(dimension.info.id(), dimension);
        for (var job : List.copyOf(this.jobs.values())) if (job.dimension == dimension) remove(job, true);
        if (dimension.catalogueTask != null) dimension.catalogueTask.cancel(true);
        this.directoryRequests.removeIf(key -> key.dimension == dimension);
        this.metadata.updateRetention(dimension.info.name(), dimension.x, dimension.z, Set.of());
        dimension.cache.close();
    }
    void inventory(RegionalProtocol.RegionInventory update) {
        var dimension = this.dimensions.get(update.dimensionId());
        if (dimension == null || update.revision() < dimension.inventoryRevision) return;
        dimension.inventoryRevision = update.revision();
        long key = region(update.regionX(), update.regionZ());
        switch (update.state()) {
            case SNAPSHOT_BEGIN -> {
                dimension.inventoryReady = false; dimension.inventoryComplete = false; dimension.inventoryFailed = false;
                dimension.regions.clear(); dimension.occupancy.clear(); dimension.coverage.clear();
                dimension.regionRevisions.clear();
                dimension.unreadableRegions.clear();
                dimension.sourceSections = 0;
                for (var job : List.copyOf(this.jobs.values())) if (job.dimension == dimension) remove(job, true);
                dimension.frontier.clear(); dimension.queued.clear(); dimension.sourceBlocked.clear(); dimension.admissionBlocked.clear(); dimension.spaceBlocked.clear();
                dimension.unpublishedRegions.clear(); dimension.parkedRegions.clear();
                dimension.pendingBindings.clear();
            }
            case FAILURE -> { dimension.inventoryReady = false; dimension.inventoryFailed = true; dimension.inventoryComplete = false; }
            case SNAPSHOT_COMPLETE -> {
                dimension.inventoryReady = !dimension.inventoryFailed;
                dimension.inventoryComplete = dimension.inventoryReady && dimension.unreadableRegions.isEmpty();
                dimension.seed();
            }
            case SAVED_PUBLISHED, SAVED_NOT_PUBLISHED, REMOVED_REGION, UNREADABLE_REGION -> {
                dimension.regionRevisions.put(key, update.revision());
                if (update.state() == RegionalProtocol.InventoryState.UNREADABLE_REGION) dimension.unreadableRegions.add(key);
                else dimension.unreadableRegions.remove(key);
                dimension.inventoryComplete = dimension.inventoryReady && dimension.unreadableRegions.isEmpty();
                long[] before = dimension.regions.get(key);
                boolean published = update.state() == RegionalProtocol.InventoryState.SAVED_PUBLISHED;
                boolean readinessChanged = published ? dimension.unpublishedRegions.remove(key)
                        : update.state().saved() && dimension.unpublishedRegions.add(key);
                boolean resume = published && dimension.parkedRegions.remove(key);
                if (update.state() == RegionalProtocol.InventoryState.REMOVED_REGION || update.state() == RegionalProtocol.InventoryState.UNREADABLE_REGION) {
                    dimension.unpublishedRegions.remove(key); dimension.parkedRegions.remove(key);
                    dimension.regionRevisions.remove(key);
                    for (var job : List.copyOf(this.jobs.values()))
                        if (job.dimension == dimension && regionFor(job.key) == key) remove(job, true);
                }
                if (resume || readinessChanged && published) {
                    dimension.sourceBlocked.remove(key);
                    reoffer(dimension, key);
                }
                long[] slots = update.state().saved() ? update.savedSlots() : null;
                if (Arrays.equals(before, slots)) break;
                dimension.sourceSections += countRegion(dimension, key, slots) - countRegion(dimension, key, before);
                if (slots == null) dimension.regions.remove(key); else dimension.regions.put(key, slots);
                if (before == null || slots == null) for (int level = 5; level <= SEARCH_LEVEL; level++) {
                    long parent = coverKey(level, update.regionX() >> (level - 4), update.regionZ() >> (level - 4));
                    int delta = slots == null ? -1 : 1, count = dimension.occupancy.addTo(parent, delta) + delta;
                    if (count == 0) dimension.occupancy.remove(parent);
                }
                dimension.sourceBlocked.remove(key); dimension.admissionBlocked.remove(key);
                Coverage coverage = dimension.coverage.get(key);
                if (coverage != null) coverage.rebuild(dimension);
                if (slots == null) {
                    dimension.coverage.remove(key); dimension.pendingBindings.remove(key); dimension.spaceBlocked.remove(key);
                }
                if (dimension.inventoryReady && slots != null) {
                    reoffer(dimension, key);
                }
            }
        }
        estimate();
    }
    private static void reoffer(Dimension dimension, long region) {
        int first = Math.floorDiv(dimension.info.minSectionY(), 16);
        int last = Math.floorDiv(dimension.info.minSectionY() + dimension.info.sectionCount() - 1, 16);
        for (int y = first; y <= last; y++) dimension.offer(new Node(dimension, 4, (int) region, y, (int) (region >> 32)));
    }
    void view(int dimensionId, int x, int z, Set<Long> visible, long visibleEpoch) {
        drainDirectory();
        var dimension = this.dimensions.get(dimensionId);
        boolean changedDimension = this.activeDimension != dimensionId || this.viewedDimension != dimension;
        if (!changedDimension && dimension != null && dimension.x == x && dimension.z == z
                && this.viewedVisible == visible && this.viewedEpoch == visibleEpoch) return;
        if (this.activeDimension != dimensionId) {
            var previous = this.dimensions.get(this.activeDimension);
            if (previous != null) setVisible(previous, Set.of());
            this.activeDimension = dimensionId;
        }
        if (dimension == null) { this.viewedDimension = null; this.viewedVisible = null; return; }
        boolean changedAnchor = dimension.x != x || dimension.z != z;
        if (changedAnchor) dimension.anchor(x, z);
        if (changedDimension || changedAnchor) this.settings.rememberDimension(dimension.info.name(), x, z);
        if (!dimension.visibleRoots.equals(visible)) setVisible(dimension, visible);
        else if (changedDimension || changedAnchor)
            this.metadata.updateRetention(dimension.info.name(), x, z, dimension.visibleRegions);
        this.viewedDimension = dimension; this.viewedVisible = visible; this.viewedEpoch = visibleEpoch;
    }
    private void setVisible(Dimension dimension, Set<Long> visible) {
        Set<Long> added = new HashSet<>(visible); added.removeAll(dimension.visibleRoots);
        dimension.visibleRoots.clear(); dimension.visibleRoots.addAll(visible); dimension.visibleRegions.clear();
        for (long key : visible) dimension.visibleRegions.add(regionFor(key));
        dimension.reheap(); dimension.trimCoverage();
        this.metadata.updateRetention(dimension.info.name(), dimension.x, dimension.z, dimension.visibleRegions);
        for (var job : List.copyOf(this.jobs.values())) if (job.dimension == dimension && job.purpose == 3
                && !dimension.node(job.key).visible() && !job.processing) {
            remove(job, true); dimension.offer(dimension.node(job.key));
        }
        for (long key : added) dimension.expand(dimension.node(key));
    }
    RegionalProtocol.DimensionInfo dimension(String name) {
        for (var dimension : this.dimensions.values()) if (dimension.info.name().equals(name)) return dimension.info;
        return null;
    }
    boolean inventoryKnown(int dimension) {
        var state = this.dimensions.get(dimension); return state != null && state.inventoryReady;
    }
    boolean absentRegion(int dimension, long region) {
        var state = this.dimensions.get(dimension);
        return state != null && state.inventoryReady && !state.regions.containsKey(region)
                && !state.unreadableRegions.contains(region);
    }
    List<RegionalProtocol.DimensionAnchor> anchors() {
        return this.dimensions.values().stream().map(d -> new RegionalProtocol.DimensionAnchor(d.info.id(), d.x, d.z)).toList();
    }
    RegionalSectionCodec.BoundCatalog catalogue(RegionalProtocol.CatalogMessage message, CatalogCodec.Catalog decoded) throws IOException {
        var dimension = this.dimensions.get(message.dimensionId());
        if (dimension == null || !dimension.info.worldIdentity().equals(message.worldIdentity()) || dimension.info.catalogId() != decoded.catalogId())
            throw new IOException("catalogue disagrees with dimension manifest");
        CatalogCodec.Source source;
        try { source = this.catalogueNames.bind(decoded); }
        catch (CatalogCodec.DecodeException mismatch) { this.catalogueNames = new CatalogCodec.SharedNames(); source = this.catalogueNames.bind(decoded); }
        var binding = new RegionalSectionCodec.BoundCatalog(message.fingerprint(), new RegionalSectionCodec.Mappings(source));
        var previous = dimension.catalogue;
        if (previous == null || dimension.catalogueStamp <= decoded.generation()) {
            dimension.catalogue = message; dimension.catalogueStamp = decoded.generation();
            startCatalogue(dimension);
        }
        return binding;
    }
    private void startCatalogue(Dimension dimension) {
        var message = dimension.catalogue;
        boolean associate = !dimension.associationPersisted;
        boolean catalogue = message != null && !dimension.persistedCatalogue.equals(message.fingerprint());
        long generation = this.metadata.admissionGeneration();
        if (this.closed || dimension.catalogueTask != null || !associate && !catalogue
                || !this.metadata.canDownload()
                || dimension.metadataBlockedGeneration != Long.MIN_VALUE
                && (!dimension.metadataRetryOnAdmission || dimension.metadataBlockedGeneration == generation)
                && (!catalogue || message.fingerprint().equals(dimension.failedCatalogue))) return;
        long stamp = dimension.catalogueStamp;
        long policyGeneration = dimension.metadataPolicyGeneration;
        var info = dimension.info;
        var task = new FutureTask<MetadataResult>(() -> {
            java.util.function.BooleanSupplier current = () -> !this.closed && this.dimensions.get(info.id()) == dimension
                    && dimension.metadataPolicyGeneration == policyGeneration;
            boolean associated = !associate;
            try {
                if (associate) {
                    var result = this.metadata.associate(this.settings.rawAddress(), info.name(), info.worldIdentity(), stamp, current);
                    if (result != RegionalMetadataStore.Persistence.PERSISTED)
                        return new MetadataResult(false, null, result, null, true, generation, policyGeneration);
                    associated = true;
                }
                if (catalogue) {
                    var result = this.metadata.persistCatalogue(message.worldIdentity(), info.name(), message, stamp,
                            () -> current.getAsBoolean() && dimension.catalogue == message);
                    return new MetadataResult(associated, result == RegionalMetadataStore.Persistence.PERSISTED ? message.fingerprint() : null,
                            result, null, true, generation, policyGeneration);
                }
                return new MetadataResult(associated, null, RegionalMetadataStore.Persistence.PERSISTED, null, true, generation, policyGeneration);
            } catch (Exception failure) {
                return new MetadataResult(associated, null, RegionalMetadataStore.Persistence.UNAVAILABLE, failure,
                        failure instanceof RegionalDiskBudget.Capacity || RegionalDiskBudget.outOfSpace(failure), generation, policyGeneration);
            }
        }) {
            @Override protected void done() { wake.run(); }
        };
        dimension.attemptedCatalogue = message == null ? null : message.fingerprint();
        dimension.catalogueTask = task; Thread.startVirtualThread(task);
    }
    boolean allCached(int dimensionId, long key) {
        // view/next/committed poll owned work once; a per-section quality query never
        // polls every dimension or accepts a summary invalidated by physical eviction.
        if (this.diskStamp != this.metadata.budget.stamp()) return false;
        var dimension = this.dimensions.get(dimensionId);
        if (dimension == null || !dimension.inventoryReady || dimension.unreadableRegions.contains(regionFor(key))) return false;
        var coverage = directory(dimension, regionFor(key));
        return coverage != null && coverage.full.contains(key);
    }
    void committed(int dimensionId, LocalSection section) {
        drainDirectory();
        var dimension = this.dimensions.get(dimensionId);
        if (dimension == null) return;
        var coverage = dimension.coverage.get(section.region());
        if (coverage != null) coverage.committed(dimension, section);
        else {
            var key = new DirectoryKey(dimension, section.region());
            if (key.equals(this.directoryKey) || this.directoryRequests.contains(key))
                dimension.pendingBindings.computeIfAbsent(section.region(), ignored -> new HashMap<>()).put(section.key(), section);
        }
    }
    Job job(long ticket) { return this.jobs.get(ticket); }
    Job pending(int dimension, long key) { return this.requested.get(new RegionalProtocol.ScopedKey(dimension, key)); }
    int pending() { return this.jobs.size(); }
    int waiting() { int count = 0; for (var job : this.jobs.values()) if (!job.processing) count++; return count; }
    boolean current(Job job) { return !this.closed && this.jobs.get(job.ticket) == job && this.dimensions.get(job.dimension.info.id()) == job.dimension; }
    RegionalProtocol.Desire next(long ticket, long connection) throws IOException {
        drainDirectory();
        if (!this.metadata.canDownload()) return null;
        var ordered = new ArrayList<>(this.dimensions.values());
        ordered.sort((a, b) -> {
            int active = Boolean.compare(b.info.id() == this.activeDimension, a.info.id() == this.activeDimension);
            return active != 0 ? active : a.info.name().compareTo(b.info.name());
        });
        for (var dimension : ordered) {
            if (!dimension.inventoryReady) continue;
            while (!dimension.frontier.isEmpty()) {
                Node node = dimension.frontier.remove(); dimension.queued.remove(node.position());
                if (!dimension.inside(node) || !dimension.height(node) || !dimension.saved(node)) continue;
                if (node.level > 4) { dimension.expand(node); continue; }
                long key = node.key(), region = regionFor(key);
                if (this.requested.containsKey(new RegionalProtocol.ScopedKey(dimension.info.id(), key))) continue;
                if (dimension.sourceBlocked.contains(region) || dimension.admissionBlocked.contains(region)
                        || dimension.spaceBlocked.containsKey(region) || dimension.parkedRegions.contains(region)) continue;
                if (dimension.probeRegion != region) { dimension.probeRegion = region; dimension.trimCoverage(); }
                var coverage = directory(dimension, region);
                if (coverage == null) { dimension.offer(node); return null; }
                LocalSection have = coverage.bindings.get(key);
                if (have != null && have.kind() != LocalSection.ABSENT) {
                    dimension.expand(node); this.skipped++; this.wake.run(); return null;
                }
                // The existing view record already owns this miss/ticket. Descendants
                // may still prepare, but never replace its in-flight request with prefetch.
                if (this.foreground.owns(dimension.info.id(), key)) { dimension.expand(node); continue; }
                // No cold DESIRE descriptor carries a body length. The binding frame
                // is certain growth; converted body growth is rechecked as it is encoded.
                long binding = CompletedSectionJournal.FRAME_BYTES + CompletedSectionJournal.BINDING_BYTES
                        + CompletedSectionJournal.FOOTER_BYTES;
                var admission = dimension.cache.canAdmit(key, binding);
                if (admission == RegionalDiskBudget.Admission.DISK_FULL) {
                    dimension.spaceBlocked.computeIfAbsent(region, ignored -> new HashSet<>()).add(key);
                    return null;
                }
                if (admission != RegionalDiskBudget.Admission.READY) { dimension.admissionBlocked.add(region); continue; }
                int purpose = node.visible() ? 3 : 4;
                var job = new Job(dimension, key, ticket, connection, purpose, this.diskRecovery, this.diskAdmission);
                this.jobs.put(ticket, job); this.requested.put(job.scope(), job); dimension.expand(node);
                return new RegionalProtocol.Desire(dimension.info.id(), dimension.info.worldIdentity(), ticket, key, purpose, null, node.rank());
            }
        }
        return null;
    }
    private Coverage directory(Dimension dimension, long region) {
        var coverage = dimension.coverage.get(region);
        if (coverage == null && !dimension.sourceBlocked.contains(region)) {
            var key = new DirectoryKey(dimension, region);
            if (!key.equals(this.directoryKey)) this.directoryRequests.add(key);
            startDirectory();
        }
        return coverage;
    }
    private void startDirectory() {
        if (this.closed || this.directoryTask != null || this.directoryRequests.isEmpty()) return;
        var iterator = this.directoryRequests.iterator(); this.directoryKey = iterator.next(); iterator.remove();
        var key = this.directoryKey; long epoch = this.directoryEpoch, stamp = this.diskStamp;
        var task = new FutureTask<DirectoryResult>(() -> {
            try {
                var directory = key.dimension.cache.inspectDirectory(key.region);
                return new DirectoryResult(key, epoch, stamp, directory.sections(), directory.namedBytes(), null);
            } catch (IOException failure) { return new DirectoryResult(key, epoch, stamp, Map.of(), 0, failure); }
        }) { @Override protected void done() { wake.run(); } };
        this.directoryTask = task; Thread.startVirtualThread(task);
    }
    private void drainDirectory() {
        long admission = this.metadata.admissionGeneration();
        if (admission != this.diskAdmission) {
            this.diskAdmission = admission;
            for (var dimension : this.dimensions.values()) {
                for (long region : dimension.admissionBlocked) reoffer(dimension, region);
                dimension.admissionBlocked.clear();
            }
        }
        long recovered = this.metadata.budget.recoveryGeneration(this.settings.serverId());
        if (recovered != this.diskRecovery) {
            this.diskRecovery = recovered;
            for (var dimension : this.dimensions.values()) {
                for (var keys : dimension.spaceBlocked.values())
                    for (long key : keys) dimension.offer(dimension.node(key));
                dimension.spaceBlocked.clear();
            }
        }
        for (var dimension : this.dimensions.values()) {
            var saved = dimension.catalogueTask;
            if (saved != null && saved.isDone()) {
                dimension.catalogueTask = null;
                try {
                    var result = saved.get();
                    if (result.associated) dimension.associationPersisted = true;
                    if (result.catalogue != null) dimension.persistedCatalogue = result.catalogue;
                    if (result.outcome != RegionalMetadataStore.Persistence.PERSISTED
                            && result.policyGeneration == dimension.metadataPolicyGeneration) {
                        dimension.metadataBlockedGeneration = result.admissionGeneration;
                        dimension.metadataRetryOnAdmission = result.retryOnAdmission;
                        dimension.failedCatalogue = dimension.attemptedCatalogue;
                        if (result.failure != null) { this.failures++; this.lastFailure = String.valueOf(result.failure); }
                    }
                } catch (Exception failure) {
                    dimension.metadataBlockedGeneration = admission;
                    dimension.failedCatalogue = dimension.attemptedCatalogue;
                    this.failures++; this.lastFailure = String.valueOf(failure);
                }
            }
            startCatalogue(dimension);
        }
        long stamp = this.metadata.budget.stamp();
        if (stamp != this.diskStamp) {
            this.diskStamp = stamp;
            for (var dimension : this.dimensions.values()) { dimension.coverage.clear(); dimension.admissionBlocked.clear(); dimension.seed(); }
        }
        var task = this.directoryTask;
        if (task != null && task.isDone()) {
            this.directoryTask = null; this.directoryKey = null;
            try {
                var result = task.get(); var dimension = result.key.dimension;
                if (result.epoch == this.directoryEpoch && result.stamp == this.diskStamp && this.dimensions.get(dimension.info.id()) == dimension) {
                    if (result.failure != null) { this.failures++; this.lastFailure = String.valueOf(result.failure); dimension.sourceBlocked.add(result.key.region); }
                    else if (dimension.visibleRegions.contains(result.key.region) || dimension.probeRegion == result.key.region) {
                        var overlay = dimension.pendingBindings.remove(result.key.region);
                        if (overlay != null) result.sections.putAll(overlay);
                        var coverage = new Coverage(result.sections); coverage.rebuild(dimension); dimension.coverage.put(result.key.region, coverage);
                        long count = result.sections.values().stream().filter(section -> section.kind() != LocalSection.ABSENT).count();
                        if (count != 0) { this.sampledSections += count; this.sampledNamedBytes += result.namedBytes; estimate(); }
                    }
                }
            } catch (Exception failure) { if (!task.isCancelled()) { this.failures++; this.lastFailure = String.valueOf(failure); } }
        }
        startDirectory();
    }
    private boolean remove(Job job, boolean drop) {
        if (!this.jobs.remove(job.ticket, job)) return false;
        this.requested.remove(job.scope(), job); if (drop) this.drops.add(job.scope()); return true;
    }
    List<RegionalProtocol.Desire> changes() {
        var changes = new ArrayList<RegionalProtocol.Desire>();
        for (var job : this.jobs.values()) {
            if (job.processing) continue;
            var node = job.dimension.node(job.key);
            int purpose = node.visible() ? 3 : 4;
            long rank = node.rank();
            if (purpose != job.purpose || rank != job.sentRank)
                changes.add(new RegionalProtocol.Desire(job.dimension.info.id(), job.dimension.info.worldIdentity(),
                        job.ticket, job.key, purpose, null, rank));
        }
        return changes;
    }
    void changed(List<RegionalProtocol.Desire> changes) {
        for (var change : changes) {
            var job = this.jobs.get(change.ticket());
            if (job != null) { job.purpose = change.purpose(); job.sentRank = change.rank(); }
        }
    }
    void foreground(int dimensionId, long key) { this.drops.remove(new RegionalProtocol.ScopedKey(dimensionId, key)); }
    void foregroundReleased(int dimensionId, long key) {
        var dimension = this.dimensions.get(dimensionId);
        if (dimension != null) dimension.offer(dimension.node(key));
    }
    boolean promote(Job job) { return !job.processing && remove(job, false); }
    void unsent(long ticket) {
        var job = this.jobs.get(ticket);
        if (job != null && remove(job, false)) job.dimension.offer(job.dimension.node(job.key));
    }
    void complete(Job job, LocalSection content, long bytes) {
        if (!remove(job, true)) return;
        this.committed++; this.receivedBytes += bytes;
    }
    void notReady(Job job) {
        if (!remove(job, true)) return;
        long region = regionFor(job.key);
        // Discovery and body lanes are independent. A newer ready notice may
        // already have overtaken this reply; consume that event rather than wait twice.
        if (!job.dimension.unpublishedRegions.contains(region)
                && job.dimension.regionRevisions.getOrDefault(region, 0L) > job.availabilityRevision)
            reoffer(job.dimension, region);
        else job.dimension.parkedRegions.add(region);
    }
    void failed(Job job, Throwable failure) {
        if (!remove(job, true)) return;
        this.failures++; this.lastFailure = String.valueOf(failure);
        if (RegionalDiskBudget.outOfSpace(failure)) {
            // Recovery may precede delivery of this worker's failed completion.
            // A queued retry remains behind the account's physical-space pause.
            if (this.metadata.budget.recoveryGeneration(this.settings.serverId()) > job.diskRecovery)
                job.dimension.offer(job.dimension.node(job.key));
            else job.dimension.spaceBlocked.computeIfAbsent(regionFor(job.key), ignored -> new HashSet<>()).add(job.key);
        } else if (failure instanceof RegionalDiskBudget.Capacity) {
            if (this.metadata.admissionGeneration() != job.admissionGeneration) reoffer(job.dimension, regionFor(job.key));
            else job.dimension.admissionBlocked.add(regionFor(job.key));
        } else job.dimension.sourceBlocked.add(regionFor(job.key));
    }
    List<RegionalProtocol.ScopedKey> drops() { return List.copyOf(this.drops); }
    void dropped(List<RegionalProtocol.ScopedKey> keys) { this.drops.removeAll(keys); }
    private void reset(boolean sameConnection) {
        for (var job : List.copyOf(this.jobs.values())) remove(job, sameConnection);
        if (!sameConnection) this.drops.clear();
        ++this.directoryEpoch;
        if (this.directoryTask != null) this.directoryTask.cancel(true);
        this.directoryTask = null; this.directoryKey = null; this.directoryRequests.clear();
        this.activeDimension = -1;
        this.viewedDimension = null; this.viewedVisible = null; this.viewedEpoch = Long.MIN_VALUE;
        for (var dimension : this.dimensions.values()) {
            if (!sameConnection) { dimension.inventoryReady = false; dimension.inventoryComplete = false; }
            dimension.visibleRoots.clear(); dimension.visibleRegions.clear(); dimension.admissionBlocked.clear(); dimension.spaceBlocked.clear();
            dimension.probeRegion = Long.MIN_VALUE; dimension.coverage.clear(); dimension.resetFrontier();
            dimension.pendingBindings.clear();
            this.metadata.updateRetention(dimension.info.name(), dimension.x, dimension.z, Set.of());
        }
    }
    void detach() { reset(true); }
    void reconnect() { reset(false); }
    void policyChanged() throws IOException {
        this.metadata.bindServer(this.settings, this.settings.rawAddress());
        for (var dimension : this.dimensions.values()) {
            dimension.admissionBlocked.clear(); dimension.spaceBlocked.clear(); dimension.seed();
            dimension.metadataPolicyGeneration++; dimension.metadataBlockedGeneration = Long.MIN_VALUE;
            dimension.failedCatalogue = null; dimension.metadataRetryOnAdmission = true;
            startCatalogue(dimension);
        }
    }
    /** The density comes from actual self-contained named records, including empty
     * bindings. This is an estimate, never an invented exact compressed world size. */
    private void estimate() {
        long count = 0;
        boolean complete = this.excludedDimensions.isEmpty();
        for (var dimension : this.dimensions.values()) {
            if (dimension.inventoryReady) count += dimension.sourceSections;
            complete &= dimension.inventoryComplete;
        }
        this.sourceSections = count;
        if (this.sampledSections == 0 || !this.settings.available()) return;
        double density = (double) this.sampledNamedBytes / this.sampledSections;
        long estimated = (long) Math.min(Long.MAX_VALUE, Math.ceil(density * count));
        if (!complete) estimated = Math.max(estimated, this.settings.estimatedWorldBytes());
        var storage = this.metadata.namespaceBudget();
        this.settings.setEstimatedWorldBytes(Math.max(estimated, Math.max(0, storage.bytes())));
    }
    private static long countRegion(Dimension dimension, long coordinate, long[] slots) {
        if (slots == null) return 0;
        long count = 0;
        for (int level = 0; level <= 4; level++) {
            int side = 1 << (4 - level);
            int first = Math.floorDiv(dimension.info.minSectionY(), 1 << level);
            int last = Math.floorDiv(dimension.info.minSectionY() + dimension.info.sectionCount() - 1, 1 << level);
            for (int x = 0; x < side; x++) for (int z = 0; z < side; z++) {
                var node = new Node(dimension, level, (int) coordinate * side + x, first, (int) (coordinate >>> 32) * side + z);
                if (dimension.insideBorder(node) && Dimension.savedSlots(slots, level, node.x, node.z)) count += last - first + 1L;
            }
        }
        return count;
    }
    String snapshot() {
        int incomplete = 0, unreadable = 0;
        for (var dimension : this.dimensions.values()) {
            if (!dimension.inventoryComplete) incomplete++;
            unreadable += dimension.unreadableRegions.size();
        }
        return "prefetchCommitted=" + this.committed + " prefetchPending=" + this.jobs.size()
                + " prefetchBytes=" + this.receivedBytes + " prefetchFailures=" + this.failures
                + " prefetchInventoryIncomplete=" + incomplete + " prefetchUnreadableRegions=" + unreadable
                + " prefetchExcludedDimensions=" + this.excludedDimensions
                + " prefetchSourceSections=" + this.sourceSections + " prefetchFailure=" + this.lastFailure;
    }
    @Override public void close() {
        this.closed = true; reset(false);
        for (var dimension : this.dimensions.values()) dimension.cache.close();
        this.dimensions.clear(); this.metadata.close(); this.settings.save();
    }

    static long region(int x, int z) { return Integer.toUnsignedLong(x) | (long) z << 32; }
    private static long regionFor(long key) {
        int shift = 4 - SectionKey.level(key); return region(SectionKey.x(key) >> shift, SectionKey.z(key) >> shift);
    }
    private static long coverKey(int level, int x, int z) { return (long) level << 56 | (long) (x & 0x0fffffff) << 28 | (z & 0x0fffffff); }
    static long rank(long key, int x, int z) { return rank(SectionKey.level(key), SectionKey.x(key), SectionKey.z(key), x, z); }
    private static long rank(int level, int x, int z, int anchorX, int anchorZ) {
        long size = 32L << level;
        long dx = Math.max(0, Math.max((long) x * size - anchorX, (long) anchorX - (x + 1L) * size));
        long dz = Math.max(0, Math.max((long) z * size - anchorZ, (long) anchorZ - (z + 1L) * size));
        return 512L * 512 + ((dx * dx + dz * dz) << (2 * Math.max(0, 4 - level)));
    }
}
