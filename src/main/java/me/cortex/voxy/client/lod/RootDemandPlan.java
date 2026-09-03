package me.cortex.voxy.client.lod;

import me.cortex.voxy.client.lod.ManifestCodec.ContentClass;
import me.cortex.voxy.client.lod.ManifestCodec.ContentDescriptor;
import me.cortex.voxy.client.lod.ManifestCodec.BoundarySummary;
import me.cortex.voxy.client.lod.ManifestCodec.DirectoryTarget;
import me.cortex.voxy.client.lod.ManifestCodec.DescriptorPage;
import me.cortex.voxy.client.lod.ManifestCodec.ManifestSubtree;
import me.cortex.voxy.client.lod.ManifestCodec.NeighborDependency;
import me.cortex.voxy.client.lod.ManifestCodec.RootDirectory;
import me.cortex.voxy.client.lod.ManifestCodec.RootDirectoryEntry;
import me.cortex.voxy.client.lod.ManifestCodec.SpatialNode;
import me.cortex.voxy.client.lod.ManifestCodec.TopRootBounds;
import me.cortex.voxy.client.lod.ManifestCodec.VisibilityMembership;
import me.cortex.voxy.client.core.rendering.SectionKey;
import me.cortex.voxy.client.lod.WireMessage.Hash256;
import me.cortex.voxy.client.lod.WireMessage.ObjectKind;
import me.cortex.voxy.client.lod.WireMessage.RootAnnounce;
import me.cortex.voxy.client.lod.WireMessage.RootToken;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * One immutable root's bounded structural metadata and exact-object request plan.
 *
 * <p>Descriptor pages authenticate the immutable object references they contain. Only exact
 * renderer demand registers those references as live capabilities and selector-expanded handles
 * enqueue the chosen fixed-8-cubed microtiles and dependencies.</p>
 */
public final class RootDemandPlan {
    public static final int MAX_STRUCTURAL_NODES = 262_144;

    /** Protocol cardinality limits for one immutable root. */
    public record Limits(int maxObjects, int maxNodes) {
        public Limits {
            if (maxObjects < 1 || maxObjects > ManifestCodec.MAX_OBJECT_REFERENCES
                    || maxNodes < 1 || maxNodes > MAX_STRUCTURAL_NODES) {
                throw new IllegalArgumentException("invalid root-demand table limits");
            }
        }
    }

    public record ExpectedObject(ObjectKind kind) {}

    private record ExpectedDescriptorPage(Hash256 manifestHash, SpatialNode root, int pageIndex) {
        private ExpectedDescriptorPage {
            Objects.requireNonNull(manifestHash, "manifestHash");
            Objects.requireNonNull(root, "root");
            if (pageIndex < 0 || pageIndex >= ManifestCodec.DESCRIPTOR_PAGE_SLOTS) {
                throw new IllegalArgumentException("descriptor page index is outside a manifest");
            }
        }
    }

    public enum ContentPriority {
        PREDICTED(1),
        CURRENT_VIEW(2),
        COVERAGE(3);

        private final int queuePriority;

        ContentPriority(int queuePriority) {
            this.queuePriority = queuePriority;
        }
    }

    public record ObjectView(int handle, Hash256 hash, ExpectedObject expected,
                             boolean processed, boolean inFlight) {
        public ObjectView {
            if (handle < 0) throw new IllegalArgumentException("negative object handle");
            Objects.requireNonNull(hash, "hash");
            Objects.requireNonNull(expected, "expected");
        }
    }

    public record NodeView(int handle, SpatialNode spatial,
                           ManifestCodec.ManifestNode manifestNode) {
        public NodeView {
            if (handle < 0) throw new IllegalArgumentException("negative node handle");
            Objects.requireNonNull(spatial, "spatial");
            Objects.requireNonNull(manifestNode, "manifestNode");
        }
    }

    /** Owner-thread diagnostic view. It never grants request authority. */
    public record Diagnostics(int metadataRoots, int demandedNodes, int resolvedNodes,
                              int expectedDirectories, int expectedManifests,
                              int expectedDescriptorPages, int loadedDirectories,
                              int loadedManifests, int loadedDescriptorPages,
                              int queuedMetadata, int inFlightMetadata,
                              int expectedObjects, int processedObjects,
                              int queuedCoverage, int queuedCurrent, int queuedPredicted,
                              int inFlightObjects, boolean metadataCapacityBlocked,
                              boolean discoveryComplete, CameraCoverage cameraCoverage,
                              int availableWindowRoots, int pendingWindowRoots,
                              int absentWindowRoots, SpatialNode sampleAbsentWindowRoot,
                              int minRootX, int maxRootX, int minRootY, int maxRootY,
                              int minRootZ, int maxRootZ) {}

    public enum CameraCoverage {
        OUTSIDE_CLIENT_WINDOW,
        DISCOVERING,
        MANIFEST_PENDING,
        AVAILABLE,
        ABSENT_FROM_PUBLISHED_ROOT
    }

    /** Immutable renderer/selection input for every structural node currently loaded. */
    public record ManifestView(RootToken root, List<NodeView> nodes,
                               List<ObjectView> objects,
                               Map<Hash256, Integer> objectHandles,
                               boolean topologyComplete) {
        public ManifestView {
            Objects.requireNonNull(root, "root");
            nodes = List.copyOf(Objects.requireNonNull(nodes, "nodes"));
            objects = List.copyOf(Objects.requireNonNull(objects, "objects"));
            objectHandles = Map.copyOf(Objects.requireNonNull(objectHandles, "objectHandles"));
        }
    }

    public record ContentObject(ContentClass contentClass, ObjectKind kind, int microtileIndex,
                                Hash256 hash) {
        public ContentObject {
            Objects.requireNonNull(contentClass, "contentClass");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(hash, "hash");
            if (kind != MicrotileCodec.objectKind(contentClass)
                    || microtileIndex < 0 || microtileIndex >= Long.SIZE) {
                throw new IllegalArgumentException("invalid typed microtile binding");
            }
        }
    }

    public record ContentLayer(ContentClass contentClass, long microtileMask,
                               List<ContentObject> objects, List<Hash256> dependencies,
                               List<NeighborDependency> neighborDependencies,
                               BoundarySummary boundarySummary,
                               long exteriorVisibilityMask,
                               long unknownVisibilityMask,
                               List<VisibilityMembership> visibilityMemberships) {
        public ContentLayer {
            Objects.requireNonNull(contentClass, "contentClass");
            objects = List.copyOf(Objects.requireNonNull(objects, "objects"));
            dependencies = List.copyOf(Objects.requireNonNull(dependencies, "dependencies"));
            neighborDependencies = List.copyOf(Objects.requireNonNull(
                    neighborDependencies, "neighborDependencies"));
            Objects.requireNonNull(boundarySummary, "boundarySummary");
            visibilityMemberships = List.copyOf(Objects.requireNonNull(
                    visibilityMemberships, "visibilityMemberships"));
            if (microtileMask == 0 || objects.size() != Long.bitCount(microtileMask)) {
                throw new IllegalArgumentException("layer mask and objects disagree");
            }
            for (NeighborDependency dependency : neighborDependencies) {
                NeighborDependency value = Objects.requireNonNull(
                        dependency, "neighbor dependency");
                if ((microtileMask & 1L << value.sourceMicrotileIndex()) == 0) {
                    throw new IllegalArgumentException(
                            "neighbor dependency source is outside the layer mask");
                }
            }
            if (((exteriorVisibilityMask | unknownVisibilityMask) & ~microtileMask) != 0
                    || (exteriorVisibilityMask & unknownVisibilityMask) != 0) {
                throw new IllegalArgumentException("invalid layer visibility masks");
            }
            if (visibilityMemberships.size()
                    > ManifestCodec.MAX_VISIBILITY_MEMBERSHIPS_PER_CONTENT) {
                throw new IllegalArgumentException("too many layer visibility memberships");
            }
            long previousDomain = 0;
            boolean firstDomain = true;
            for (VisibilityMembership membership : visibilityMemberships) {
                VisibilityMembership value = Objects.requireNonNull(
                        membership, "visibility membership");
                if ((value.microtileMask() & ~microtileMask) != 0
                        || !firstDomain
                        && Long.compareUnsigned(previousDomain, value.domain()) >= 0) {
                    throw new IllegalArgumentException("invalid layer visibility membership");
                }
                firstDomain = false;
                previousDomain = value.domain();
            }
        }

    }

    public record Binding(long sectionKey, int childMask,
                          Optional<ManifestCodec.QuantizedBounds> bounds,
                          long geometricErrorQ16, List<ContentLayer> layers) {
        public Binding {
            if ((childMask & ~0xff) != 0 || geometricErrorQ16 < 0
                    || geometricErrorQ16 > 0xffff_ffffL) {
                throw new IllegalArgumentException("invalid structural binding metadata");
            }
            bounds = Objects.requireNonNull(bounds, "bounds");
            layers = List.copyOf(Objects.requireNonNull(layers, "layers"));
            if (layers.isEmpty()) {
                throw new IllegalArgumentException("a present binding requires content layers");
            }
            HashSet<ContentClass> classes = new HashSet<>();
            for (ContentLayer layer : layers) {
                if (!classes.add(Objects.requireNonNull(layer, "content layer").contentClass())) {
                    throw new IllegalArgumentException("binding repeats a content class");
                }
            }
        }

        public List<ContentObject> objects() {
            ArrayList<ContentObject> result = new ArrayList<>();
            for (ContentLayer layer : this.layers) result.addAll(layer.objects());
            return List.copyOf(result);
        }

        public List<Hash256> dependencies() {
            LinkedHashSet<Hash256> result = new LinkedHashSet<>();
            for (ContentLayer layer : this.layers) result.addAll(layer.dependencies());
            return List.copyOf(result);
        }

        public List<NeighborDependency> neighborDependencies() {
            LinkedHashSet<NeighborDependency> result = new LinkedHashSet<>();
            for (ContentLayer layer : this.layers) result.addAll(layer.neighborDependencies());
            return List.copyOf(result);
        }

        public List<Hash256> requiredHashes() {
            LinkedHashSet<Hash256> result = new LinkedHashSet<>();
            for (ContentLayer layer : this.layers) {
                for (ContentObject object : layer.objects()) result.add(object.hash());
                result.addAll(layer.dependencies());
                for (NeighborDependency dependency : layer.neighborDependencies()) {
                    result.add(dependency.hash());
                }
            }
            return List.copyOf(result);
        }

    }

    private final RootAnnounce root;
    private final Limits limits;
    private final LinkedHashSet<SpatialNode> metadataRoots = new LinkedHashSet<>();
    private final PrimitiveLongSet demanded = new PrimitiveLongSet();
    private final PrimitiveLongSet demandScratch = new PrimitiveLongSet();
    /** Reused desired selector topology; manifests remain resident outside this working cut. */
    private final PrimitiveLongSet admittedScratch = new PrimitiveLongSet();
    private final Map<Hash256, TopRootBounds> expectedDirectories = new LinkedHashMap<>();
    private final Map<Hash256, SpatialNode> expectedManifests = new LinkedHashMap<>();
    private final Map<Hash256, ExpectedDescriptorPage> expectedDescriptorPages =
            new LinkedHashMap<>();
    private final ArrayDeque<Hash256> subtreeQueue = new ArrayDeque<>();
    private final Set<Hash256> subtreeInFlight = new HashSet<>();
    private final Map<Hash256, RootDirectory> directories = new HashMap<>();
    private final Map<Hash256, ManifestSubtree> manifests = new HashMap<>();
    private final Map<SpatialNode, ManifestSubtree> manifestsByRoot = new HashMap<>();
    private final Map<Hash256, DescriptorPage> descriptorPages = new HashMap<>();
    /** Verified manifests retained until bounded planner tables have room for one atomic install. */
    private final Map<Hash256, ManifestSubtree> deferredManifests = new LinkedHashMap<>();
    /** Verified pages retained until their object capabilities fit as one atomic install. */
    private final Map<Hash256, DescriptorPage> deferredDescriptorPages = new LinkedHashMap<>();
    private final Map<Hash256, ExpectedObject> expectedObjects = new LinkedHashMap<>();
    private final Map<Hash256, Integer> objectHandles = new LinkedHashMap<>();
    private final ArrayList<Hash256> objectsByHandle = new ArrayList<>();
    private final Map<SpatialNode, Integer> nodeHandles = new LinkedHashMap<>();
    private final ArrayList<SpatialNode> nodesByHandle = new ArrayList<>();
    private final LinkedHashSet<Hash256> bootstrapQueue = new LinkedHashSet<>();
    private final LinkedHashSet<Hash256> coverageQueue = new LinkedHashSet<>();
    private final LinkedHashSet<Hash256> currentViewQueue = new LinkedHashSet<>();
    private final LinkedHashSet<Hash256> predictedQueue = new LinkedHashSet<>();
    private final Set<Hash256> objectInFlight = new HashSet<>();
    private final Set<Hash256> processedObjects = new HashSet<>();
    private final Map<Hash256, ContentPriority> requestedContent = new LinkedHashMap<>();
    /** Exact selected content retained for residency without granting request authority. */
    private final Map<Hash256, ContentPriority> selectedContent = new LinkedHashMap<>();
    /** Selected neighbor context retained for residency, but never used as request authority. */
    private final Map<Hash256, ContentPriority> selectedNeighborContent = new LinkedHashMap<>();
    private final PrimitivePriorityMarks primitivePriorities = new PrimitivePriorityMarks();
    private final PrimitivePriorityMarks primitiveRequestable = new PrimitivePriorityMarks();
    private final Map<Hash256, LinkedHashSet<Long>> bindingsByObject = new HashMap<>();
    private final Map<Long, Optional<Binding>> resolutions = new LinkedHashMap<>();
    private boolean metadataCapacityBlocked;
    private volatile long manifestRevision;
    /** Changes only when the selector's immutable node/object namespace changes. */
    private volatile long selectionTopologyRevision;

    /** Metadata roots discover structure; only exact contentDemand entries enqueue microtiles. */
    public RootDemandPlan(RootAnnounce root, Collection<SpatialNode> metadataRoots,
                            Collection<Long> contentDemand) {
        this(root, metadataRoots, contentDemand,
                new Limits(ManifestCodec.MAX_OBJECT_REFERENCES, MAX_STRUCTURAL_NODES));
    }

    public RootDemandPlan(RootAnnounce root, Collection<SpatialNode> metadataRoots,
                            Collection<Long> contentDemand, Limits limits) {
        this.root = Objects.requireNonNull(root, "root");
        this.limits = Objects.requireNonNull(limits, "limits");
        Objects.requireNonNull(metadataRoots, "metadataRoots");
        for (SpatialNode metadataRoot : metadataRoots) {
            SpatialNode value = Objects.requireNonNull(metadataRoot, "metadata root");
            if (value.lod() != ManifestCodec.MAX_LOD) {
                throw new IllegalArgumentException("metadata roots must be top-level LOD nodes");
            }
            this.metadataRoots.add(value);
        }
        requireNodeCapacity(this.metadataRoots.size(), "metadata-root window");
        Objects.requireNonNull(contentDemand, "contentDemand");
        for (long key : contentDemand) {
            spatial(key);
            this.demanded.add(key);
        }
        requireNodeCapacity(this.demanded.size(), "initial content demand");
        if (!tryExpectDirectory(root.root().rootHash(), null)) {
            throw new IllegalStateException("root directory exceeds the metadata table");
        }
        expectObject(root.catalogHash(), ObjectKind.CATALOG);
        expectObject(root.dictionarySetHash(), ObjectKind.DICTIONARY_SET);
    }

    public RootAnnounce root() { return this.root; }
    /** Monotonic epoch for structural node topology and descriptor-binding mutations. */
    public long manifestRevision() { return this.manifestRevision; }
    public long selectionTopologyRevision() { return this.selectionTopologyRevision; }

    /**
     * Captures the exact first-stage state used by debug builds. In particular, this separates
     * an empty GPU selection from a server root that does not advertise the camera's LOD-4 root.
     */
    public Diagnostics diagnostics(SpatialNode cameraRoot) {
        Objects.requireNonNull(cameraRoot, "camera root");
        if (cameraRoot.lod() != ManifestCodec.MAX_LOD) {
            throw new IllegalArgumentException("camera diagnostic root must be LOD 4");
        }
        Set<SpatialNode> loadedRoots = new HashSet<>();
        for (ManifestSubtree manifest : this.manifests.values()) {
            loadedRoots.add(manifest.root());
        }
        Set<SpatialNode> expectedRoots = new HashSet<>(this.expectedManifests.values());
        boolean windowed = this.metadataRoots.contains(cameraRoot);
        boolean loaded = loadedRoots.contains(cameraRoot);
        boolean expected = expectedRoots.contains(cameraRoot);
        boolean complete = discoveryComplete();
        CameraCoverage coverage = !windowed ? CameraCoverage.OUTSIDE_CLIENT_WINDOW
                : loaded ? CameraCoverage.AVAILABLE
                : expected ? CameraCoverage.MANIFEST_PENDING
                : complete ? CameraCoverage.ABSENT_FROM_PUBLISHED_ROOT
                : CameraCoverage.DISCOVERING;

        int availableWindowRoots = 0;
        int pendingWindowRoots = 0;
        int absentWindowRoots = 0;
        SpatialNode sampleAbsentWindowRoot = null;
        for (SpatialNode metadataRoot : this.metadataRoots) {
            if (loadedRoots.contains(metadataRoot)) {
                availableWindowRoots++;
            } else if (!complete || expectedRoots.contains(metadataRoot)) {
                pendingWindowRoots++;
            } else {
                absentWindowRoots++;
                if (sampleAbsentWindowRoot == null) sampleAbsentWindowRoot = metadataRoot;
            }
        }

        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (ManifestSubtree manifest : this.manifests.values()) {
            SpatialNode root = manifest.root();
            minX = Math.min(minX, root.x());
            maxX = Math.max(maxX, root.x());
            minY = Math.min(minY, root.y());
            maxY = Math.max(maxY, root.y());
            minZ = Math.min(minZ, root.z());
            maxZ = Math.max(maxZ, root.z());
        }
        if (this.manifests.isEmpty()) minX = maxX = minY = maxY = minZ = maxZ = 0;
        return new Diagnostics(this.metadataRoots.size(), this.demanded.size(),
                this.resolutions.size(), this.expectedDirectories.size(),
                this.expectedManifests.size(), this.expectedDescriptorPages.size(),
                this.directories.size(), this.manifests.size(), this.descriptorPages.size(),
                this.subtreeQueue.size(), this.subtreeInFlight.size(),
                this.expectedObjects.size(), this.processedObjects.size(),
                this.coverageQueue.size(), this.currentViewQueue.size(),
                this.predictedQueue.size(), this.objectInFlight.size(),
                this.metadataCapacityBlocked, complete, coverage,
                availableWindowRoots, pendingWindowRoots, absentWindowRoots,
                sampleAbsentWindowRoot,
                minX, maxX, minY, maxY, minZ, maxZ);
    }
    public boolean hasMetadataRoots(Collection<SpatialNode> roots) {
        Objects.requireNonNull(roots, "roots");
        return roots.size() == this.metadataRoots.size() && this.metadataRoots.containsAll(roots);
    }
    /** Adds exact structural content demand without queuing any content object. */
    public boolean addDemand(long key) {
        spatial(key);
        if (!this.demanded.contains(key)) {
            requireNodeCapacity((long) this.demanded.size() + 1, "content demand");
        }
        if (!this.demanded.add(key)) return false;
        refreshMetadataDiscovery();
        return true;
    }

    public boolean addContentDemand(long key) {
        return addDemand(key);
    }

    public void reconcileMetadataRoots(Collection<SpatialNode> roots) {
        Objects.requireNonNull(roots, "roots");
        boolean topologyWasComplete = discoveryComplete();
        LinkedHashSet<SpatialNode> desired = new LinkedHashSet<>();
        for (SpatialNode root : roots) {
            Objects.requireNonNull(root, "metadata root");
            if (root.lod() != ManifestCodec.MAX_LOD) {
                throw new IllegalArgumentException("metadata roots must be top-level LOD nodes");
            }
            desired.add(root);
        }
        requireNodeCapacity(desired.size(), "metadata-root window");
        boolean changed = this.metadataRoots.retainAll(desired);
        changed |= this.metadataRoots.addAll(desired);
        if (changed) {
            pruneIrrelevantMetadata(topologyWasComplete);
            refreshMetadataDiscovery();
        }
    }

    /** Reconciles a bounded handoff overflow from the authoritative current-demand snapshot. */
    public void reconcileDemand(Collection<Long> currentDemand) {
        Objects.requireNonNull(currentDemand, "currentDemand");
        this.demandScratch.clear();
        for (long key : currentDemand) {
            spatial(key);
            this.demandScratch.add(key);
        }
        reconcileDemandScratch();
    }

    /** Allocation-free complete-frontier demand reconciliation. */
    public void reconcileDemand(long[] currentDemand, int count) {
        Objects.requireNonNull(currentDemand, "currentDemand");
        if (count < 0 || count > currentDemand.length) {
            throw new IllegalArgumentException("invalid primitive demand range");
        }
        this.demandScratch.clear();
        for (int index = 0; index < count; index++) {
            long key = currentDemand[index];
            spatial(key);
            this.demandScratch.add(key);
        }
        reconcileDemandScratch();
    }

    private void reconcileDemandScratch() {
        boolean topologyWasComplete = discoveryComplete();
        requireNodeCapacity(this.demandScratch.size(), "content demand");
        for (int index = this.demanded.size() - 1; index >= 0; index--) {
            long key = this.demanded.valueAt(index);
            if (!this.demandScratch.contains(key)) removeDemandState(key);
        }
        for (int index = 0; index < this.demandScratch.size(); index++) {
            this.demanded.add(this.demandScratch.valueAt(index));
        }
        rebuildAdmittedNodes();
        pruneIrrelevantMetadata(topologyWasComplete);
        refreshMetadataDiscovery();
    }

    private boolean removeDemandState(long key) {
        if (!this.demanded.remove(key)) return false;
        Optional<Binding> oldBinding = this.resolutions.remove(key);
        if (oldBinding != null && oldBinding.isPresent()) {
            for (Hash256 hash : oldBinding.orElseThrow().requiredHashes()) {
                LinkedHashSet<Long> keys = this.bindingsByObject.get(hash);
                if (keys == null) continue;
                keys.remove(key);
                if (keys.isEmpty()) this.bindingsByObject.remove(hash);
            }
        }
        if (oldBinding != null) {
            this.manifestRevision++;
            this.selectionTopologyRevision++;
        }
        return true;
    }

    public List<Hash256> takeSubtreeRequests(int maximum) {
        return drain(this.subtreeQueue, this.subtreeInFlight, maximum);
    }

    public List<Hash256> takeBootstrapObjectRequests(int maximum) {
        ArrayList<Hash256> result = new ArrayList<>(maximum);
        drainSet(this.bootstrapQueue, this.objectInFlight, maximum, result);
        return List.copyOf(result);
    }

    /** Drains one priority lane without destroying the request scheduling class. */
    public List<Hash256> takeContentObjectRequests(ContentPriority priority, int maximum) {
        Objects.requireNonNull(priority, "priority");
        if (!bootstrapObjectsProcessed()) return List.of();
        ArrayList<Hash256> result = new ArrayList<>(maximum);
        LinkedHashSet<Hash256> queue = switch (priority) {
            case COVERAGE -> this.coverageQueue;
            case CURRENT_VIEW -> this.currentViewQueue;
            case PREDICTED -> this.predictedQueue;
        };
        drainSet(queue, this.objectInFlight, maximum, result);
        return List.copyOf(result);
    }

    /** Adds exact selector-expanded object handles without widening to the rest of the node. */
    public void requestObjectsByHandle(Collection<Integer> handles, ContentPriority priority) {
        Objects.requireNonNull(handles, "handles");
        Objects.requireNonNull(priority, "priority");
        for (int handle : handles) requestSelectorObject(hashForObjectHandle(handle), priority);
    }

    /** Primitive selector handoff; avoids boxing every final missing object handle. */
    public void requestObjectsByHandle(int[] handles, byte[] priorities, int count) {
        requirePrimitiveHandles(handles, priorities, count);
        for (int index = 0; index < count; index++) {
            validatedMicrotileHandle(handles[index]);
            primitivePriority(priorities[index]);
        }
        // The GPU rows are already lane/score sorted. Move them to the queue front in reverse so
        // an incomplete frontier can improve priority without cancelling demand it cannot see.
        for (int index = count - 1; index >= 0; index--) {
            Hash256 hash = validatedMicrotileHandle(handles[index]);
            ContentPriority priority = primitivePriority(priorities[index]);
            requestSelectorObject(hash, priority, true);
        }
    }

    /** Retains additions-only request authority without inventing work from a partial frontier. */
    public void retainContentRequests(int[] handles, byte[] priorities, int count) {
        requirePrimitiveHandles(handles, priorities, count);
        for (int index = 0; index < count; index++) {
            Hash256 hash = validatedMicrotileHandle(handles[index]);
            ContentPriority priority = primitivePriority(priorities[index]);
            this.requestedContent.merge(hash, priority, RootDemandPlan::higherPriority);
        }
    }

    private void requestSelectorObject(Hash256 hash, ContentPriority priority) {
        requestSelectorObject(hash, priority, false);
    }

    private void requestSelectorObject(Hash256 hash, ContentPriority priority,
                                       boolean prioritize) {
        ContentPriority previous = this.requestedContent.get(hash);
        if (previous == null || previous.queuePriority < priority.queuePriority) {
            this.requestedContent.put(hash, priority);
        }
        ContentPriority effective = this.requestedContent.get(hash);
        // GPU selection readback is asynchronous. Its missing mask may have been captured
        // before this immutable object completed, so selector demand must not revoke completed
        // residency and turn the same object back into network work. A confirmed physical
        // residency miss uses retryMissingResidentObject() instead.
        if (!this.processedObjects.contains(hash)
                && !this.objectInFlight.contains(hash)
                && (prioritize || previous != effective || !queuedAt(hash, effective))) {
            removeFromContentQueues(hash);
            queueContent(hash, effective, prioritize);
        }
    }

    /** Adds exact descriptor-bound hashes, including a complex companion discovered locally. */
    public void requestObjectsByHash(long sectionKey, Collection<Hash256> hashes,
                                     ContentPriority priority) {
        Objects.requireNonNull(hashes, "hashes");
        Objects.requireNonNull(priority, "priority");
        Binding binding = binding(sectionKey).orElseThrow(() ->
                new IllegalArgumentException("content request belongs to an unresolved node"));
        LinkedHashMap<Hash256, ObjectKind> validated =
                bindingObjectKinds(binding, hashes);
        for (Map.Entry<Hash256, ObjectKind> entry : validated.entrySet()) {
            Hash256 hash = entry.getKey();
            // A selected capability may have been compacted after asynchronous GPU readback.
            // Re-register only this descriptor-authenticated hash; the hard table limit remains
            // enforced by registerExpectedObject().
            registerSelectedObject(sectionKey, hash, entry.getValue());
            // An explicit selector request means disposable residency no longer has this object,
            // even if the same immutable hash completed an earlier transfer.
            this.processedObjects.remove(hash);
            ContentPriority previous = this.requestedContent.get(hash);
            if (previous == null || previous.queuePriority < priority.queuePriority) {
                this.requestedContent.put(hash, priority);
            }
            refreshContentPriority(hash);
        }
    }

    private static LinkedHashMap<Hash256, ObjectKind> bindingObjectKinds(
            Binding binding, Collection<Hash256> hashes) {
        LinkedHashMap<Hash256, ObjectKind> result = new LinkedHashMap<>();
        for (Hash256 hash : hashes) {
            result.put(Objects.requireNonNull(hash, "content request hash"), null);
        }
        for (ContentLayer layer : binding.layers()) {
            ObjectKind layerKind = MicrotileCodec.objectKind(layer.contentClass());
            for (ContentObject object : layer.objects()) {
                mergeBoundKind(result, object.hash(), object.kind());
            }
            for (Hash256 dependency : layer.dependencies()) {
                mergeBoundKind(result, dependency, layerKind);
            }
            for (NeighborDependency dependency : layer.neighborDependencies()) {
                mergeBoundKind(result, dependency.hash(), ObjectKind.COMPLEX_MICROTILE);
            }
        }
        for (ObjectKind kind : result.values()) {
            if (kind == null) throw new IllegalArgumentException(
                    "object is not bound to the requesting section");
        }
        return result;
    }

    private static void mergeBoundKind(Map<Hash256, ObjectKind> requested,
                                       Hash256 hash, ObjectKind candidate) {
        if (!requested.containsKey(hash)) return;
        requested.put(hash, mergeObjectKind(requested.get(hash), candidate));
    }

    private static ObjectKind mergeObjectKind(ObjectKind present, ObjectKind candidate) {
        if (present != null && present != candidate) {
            throw new IllegalArgumentException("descriptor hash changes object kind");
        }
        return candidate;
    }

    /**
     * Atomically replaces queued exact content demand from a complete selector frontier.
     * Already-sent requests remain authorized, but no cancelled unsent object is emitted.
     */
    public void reconcileContentRequests(Map<Integer, ContentPriority> desiredByHandle) {
        Objects.requireNonNull(desiredByHandle, "desiredByHandle");
        LinkedHashMap<Hash256, ContentPriority> desired = new LinkedHashMap<>();
        for (Map.Entry<Integer, ContentPriority> entry : desiredByHandle.entrySet()) {
            Hash256 hash = hashForObjectHandle(Objects.requireNonNull(
                    entry.getKey(), "desired object handle"));
            ContentPriority priority = Objects.requireNonNull(
                    entry.getValue(), "desired object priority");
            ExpectedObject expected = this.expectedObjects.get(hash);
            if (expected == null || !isMicrotile(expected.kind())) {
                throw new IllegalArgumentException("desired handle is not microtile content");
            }
            desired.merge(hash, priority, (left, right) ->
                    left.queuePriority >= right.queuePriority ? left : right);
        }
        this.requestedContent.clear();
        this.coverageQueue.clear();
        this.currentViewQueue.clear();
        this.predictedQueue.clear();
        for (Map.Entry<Hash256, ContentPriority> entry : desired.entrySet()) {
            Hash256 hash = entry.getKey();
            ContentPriority priority = entry.getValue();
            this.requestedContent.put(hash, priority);
            if (!this.processedObjects.contains(hash) && !this.objectInFlight.contains(hash)) {
                queueContent(hash, priority, false);
            }
        }
    }

    /** Primitive complete-frontier request reconciliation. */
    public void reconcileContentRequests(int[] handles, byte[] priorities, int count,
                                         int[] requestableHandles,
                                         byte[] requestablePriorities,
                                         int requestableCount) {
        requirePrimitiveHandles(handles, priorities, count);
        requirePrimitiveHandles(requestableHandles, requestablePriorities, requestableCount);
        PrimitivePriorityMarks desired = this.primitivePriorities.begin(
                this.objectsByHandle.size(), handles, priorities, count);
        PrimitivePriorityMarks requestable = this.primitiveRequestable.begin(
                this.objectsByHandle.size(), requestableHandles,
                requestablePriorities, requestableCount);
        // Authenticate the whole replacement before mutating the live request authority.
        for (int index = 0; index < desired.count; index++) {
            validatedMicrotileHandle(desired.handles[index]);
        }
        for (int index = 0; index < requestable.count; index++) {
            int handle = requestable.handles[index];
            validatedMicrotileHandle(handle);
            if (!desired.contains(handle)) {
                throw new IllegalArgumentException(
                        "requestable object is outside request authority");
            }
        }
        this.requestedContent.clear();
        this.coverageQueue.clear();
        this.currentViewQueue.clear();
        this.predictedQueue.clear();
        for (int index = 0; index < desired.count; index++) {
            int handle = desired.handles[index];
            Hash256 hash = validatedMicrotileHandle(handle);
            ContentPriority priority = primitivePriority(desired.priorities[index]);
            this.requestedContent.put(hash, priority);
            if (requestable.contains(handle) && !this.processedObjects.contains(hash)
                    && !this.objectInFlight.contains(hash)) {
                queueContent(hash, priority, false);
            }
        }
    }

    /** Replaces selected content residency without enqueueing any object. */
    public void reconcileSelectedContent(Map<Integer, ContentPriority> desiredByHandle) {
        LinkedHashMap<Hash256, ContentPriority> desired = selectedContentHashes(
                desiredByHandle, false);
        this.selectedContent.clear();
        this.selectedContent.putAll(desired);
    }

    public void reconcileSelectedContent(int[] handles, byte[] priorities, int count) {
        reconcilePrimitiveSelected(this.selectedContent, handles, priorities, count, false);
    }

    /** Adds selected content from an additions-only handoff without enqueueing it. */
    public void retainSelectedContent(Map<Integer, ContentPriority> desiredByHandle) {
        for (Map.Entry<Hash256, ContentPriority> entry
                : selectedContentHashes(desiredByHandle, false).entrySet()) {
            this.selectedContent.merge(entry.getKey(), entry.getValue(), (left, right) ->
                    left.queuePriority >= right.queuePriority ? left : right);
        }
    }

    public void retainSelectedContent(int[] handles, byte[] priorities, int count) {
        mergePrimitiveSelected(this.selectedContent, handles, priorities, count, false);
    }

    /**
     * Replaces the selected per-8-cubed neighbor residency set from a complete GPU frontier.
     *
     * <p>This method deliberately does not mutate {@link #requestedContent}, processed state, or
     * either content queue. A neighbor may enter those request-authority structures only through
     * an explicit missing-neighbor handle returned by a GPU selection row.</p>
     */
    public void reconcileSelectedNeighborContent(Map<Integer, ContentPriority> desiredByHandle) {
        LinkedHashMap<Hash256, ContentPriority> desired = selectedNeighborHashes(desiredByHandle);
        this.selectedNeighborContent.clear();
        this.selectedNeighborContent.putAll(desired);
    }

    public void reconcileSelectedNeighborContent(int[] handles, byte[] priorities, int count) {
        reconcilePrimitiveSelected(this.selectedNeighborContent,
                handles, priorities, count, true);
    }

    /** Adds selected neighbor residency from an additions-only GPU handoff without queuing it. */
    public void retainSelectedNeighborContent(Map<Integer, ContentPriority> desiredByHandle) {
        for (Map.Entry<Hash256, ContentPriority> entry
                : selectedNeighborHashes(desiredByHandle).entrySet()) {
            this.selectedNeighborContent.merge(entry.getKey(), entry.getValue(), (left, right) ->
                    left.queuePriority >= right.queuePriority ? left : right);
        }
    }

    public void retainSelectedNeighborContent(int[] handles, byte[] priorities, int count) {
        mergePrimitiveSelected(this.selectedNeighborContent, handles, priorities, count, true);
    }

    private LinkedHashMap<Hash256, ContentPriority> selectedNeighborHashes(
            Map<Integer, ContentPriority> desiredByHandle) {
        return selectedContentHashes(desiredByHandle, true);
    }

    private void mergePrimitiveSelected(Map<Hash256, ContentPriority> target,
                                        int[] handles, byte[] priorities, int count,
                                        boolean neighborOnly) {
        requirePrimitiveHandles(handles, priorities, count);
        for (int index = 0; index < count; index++) {
            Hash256 hash = validatedMicrotileHandle(handles[index]);
            if (neighborOnly && this.expectedObjects.get(hash).kind()
                    != ObjectKind.COMPLEX_MICROTILE) {
                throw new IllegalArgumentException(
                        "selected neighbor is not reachable complex microtile content");
            }
            target.merge(hash, primitivePriority(priorities[index]),
                    RootDemandPlan::higherPriority);
        }
    }

    private void reconcilePrimitiveSelected(Map<Hash256, ContentPriority> target,
                                            int[] handles, byte[] priorities, int count,
                                            boolean neighborOnly) {
        requirePrimitiveHandles(handles, priorities, count);
        PrimitivePriorityMarks desired = this.primitivePriorities.begin(
                this.objectsByHandle.size(), handles, priorities, count);
        var iterator = target.keySet().iterator();
        while (iterator.hasNext()) {
            Hash256 hash = iterator.next();
            Integer handle = this.objectHandles.get(hash);
            if (handle == null || !desired.contains(handle)) iterator.remove();
        }
        mergePrimitiveSelected(target, desired.handles, desired.priorities,
                desired.count, neighborOnly);
    }

    private Hash256 validatedMicrotileHandle(int handle) {
        Hash256 hash = hashForObjectHandle(handle);
        ExpectedObject expected = this.expectedObjects.get(hash);
        if (expected == null || !isMicrotile(expected.kind())) {
            throw new IllegalArgumentException("object handle is not reachable content");
        }
        return hash;
    }

    private static void requirePrimitiveHandles(int[] handles, byte[] priorities, int count) {
        Objects.requireNonNull(handles, "handles");
        Objects.requireNonNull(priorities, "priorities");
        if (count < 0 || count > handles.length || count > priorities.length) {
            throw new IllegalArgumentException("invalid primitive handle range");
        }
    }

    private static ContentPriority primitivePriority(byte ordinal) {
        return switch (ordinal) {
            case 0 -> ContentPriority.PREDICTED;
            case 1 -> ContentPriority.CURRENT_VIEW;
            case 2 -> ContentPriority.COVERAGE;
            default -> throw new IllegalArgumentException("invalid content priority");
        };
    }

    private static ContentPriority higherPriority(ContentPriority left, ContentPriority right) {
        return left.queuePriority >= right.queuePriority ? left : right;
    }

    private LinkedHashMap<Hash256, ContentPriority> selectedContentHashes(
            Map<Integer, ContentPriority> desiredByHandle, boolean neighborOnly) {
        Objects.requireNonNull(desiredByHandle, "desiredByHandle");
        LinkedHashMap<Hash256, ContentPriority> desired = new LinkedHashMap<>();
        for (Map.Entry<Integer, ContentPriority> entry : desiredByHandle.entrySet()) {
            Hash256 hash = hashForObjectHandle(Objects.requireNonNull(
                    entry.getKey(), "selected neighbor handle"));
            ContentPriority priority = Objects.requireNonNull(
                    entry.getValue(), "selected neighbor priority");
            ExpectedObject expected = this.expectedObjects.get(hash);
            if (expected == null || !isMicrotile(expected.kind())
                    || neighborOnly && expected.kind() != ObjectKind.COMPLEX_MICROTILE) {
                throw new IllegalArgumentException(
                        "selected handle is not reachable microtile content");
            }
            desired.merge(hash, priority, (left, right) ->
                    left.queuePriority >= right.queuePriority ? left : right);
        }
        return desired;
    }

    public boolean discoveryComplete() {
        if (this.metadataCapacityBlocked || !this.deferredManifests.isEmpty()
                || !this.deferredDescriptorPages.isEmpty()) return false;
        if (!this.subtreeQueue.isEmpty()) return false;
        for (Hash256 hash : this.subtreeInFlight) {
            if (subtreeRelevant(hash)) return false;
        }
        return true;
    }

    public boolean bootstrapObjectsProcessed() {
        if (!this.processedObjects.contains(this.root.catalogHash())) return false;
        if (!this.processedObjects.contains(this.root.dictionarySetHash())) {
            return false;
        }
        for (Map.Entry<Hash256, ExpectedObject> object : this.expectedObjects.entrySet()) {
            if (object.getValue().kind() == ObjectKind.COMPRESSION_DICTIONARY
                    && !this.processedObjects.contains(object.getKey())) return false;
        }
        return true;
    }

    /**
     * Authorizes a server response before its compressed payload is decompressed or cached.
     * Network responses must correspond to an exact outstanding request; merely being reachable
     * from the root is insufficient because queued-but-unsent objects are not server capabilities.
     */
    public void requireInFlightResponse(Hash256 hash, ObjectKind kind, boolean subtree) {
        Objects.requireNonNull(hash, "hash");
        Objects.requireNonNull(kind, "kind");
        if (subtree) {
            ObjectKind expected;
            if (this.expectedDirectories.containsKey(hash)) expected = ObjectKind.ROOT_DIRECTORY;
            else if (this.expectedManifests.containsKey(hash)) expected = ObjectKind.MANIFEST_SUBTREE;
            else if (this.expectedDescriptorPages.containsKey(hash)) {
                expected = ObjectKind.MANIFEST_DESCRIPTOR_PAGE;
            }
            else throw new IllegalArgumentException("unsolicited subtree object");
            if (!this.subtreeInFlight.contains(hash) || kind != expected) {
                throw new IllegalArgumentException("unsolicited or wrong-type subtree object");
            }
            return;
        }
        ExpectedObject expected = this.expectedObjects.get(hash);
        if (!this.objectInFlight.contains(hash) || expected == null
                || expected.kind() != kind) {
            throw new IllegalArgumentException("unsolicited or wrong-type content object");
        }
    }

    /** Whether a not-yet-received stream item still contributes to the current exact demand. */
    public boolean inFlightResponseRelevant(Hash256 hash, boolean subtree) {
        Objects.requireNonNull(hash, "hash");
        if (subtree) return this.subtreeInFlight.contains(hash) && subtreeRelevant(hash);
        if (!this.objectInFlight.contains(hash)) return false;
        ExpectedObject expected = this.expectedObjects.get(hash);
        return expected != null && (!isMicrotile(expected.kind())
                || this.requestedContent.containsKey(hash));
    }

    /** Returns an authorized response to its request queue for a later retry. */
    public void deferInFlightResponse(Hash256 hash, boolean subtree) {
        Objects.requireNonNull(hash, "hash");
        if (subtree) {
            if (!this.subtreeInFlight.remove(hash)
                    || !this.expectedDirectories.containsKey(hash)
                    && !this.expectedManifests.containsKey(hash)
                    && !this.expectedDescriptorPages.containsKey(hash)) {
                throw new IllegalArgumentException("cannot defer a non-in-flight subtree object");
            }
            if (subtreeRelevant(hash)) {
                this.subtreeQueue.addFirst(hash);
            } else {
                this.expectedDirectories.remove(hash);
                this.expectedManifests.remove(hash);
                this.expectedDescriptorPages.remove(hash);
            }
            return;
        }
        if (!this.objectInFlight.remove(hash) || !this.expectedObjects.containsKey(hash)) {
            throw new IllegalArgumentException("cannot defer a non-in-flight content object");
        }
        enqueueExpectedObject(hash);
    }

    /** Reissues still-required content reclaimed from disposable physical residency. */
    public boolean retryMissingResidentObject(Hash256 hash) {
        Objects.requireNonNull(hash, "hash");
        ExpectedObject expected = this.expectedObjects.get(hash);
        if (expected == null) return false;
        boolean changed = this.processedObjects.remove(hash);
        if (!isMicrotile(expected.kind())) {
            enqueueExpectedObject(hash);
            return changed;
        }
        ContentPriority priority = this.requestedContent.get(hash);
        ContentPriority selected = this.selectedContent.get(hash);
        if (selected != null) {
            priority = priority == null ? selected : higherPriority(priority, selected);
        }
        ContentPriority neighbor = this.selectedNeighborContent.get(hash);
        if (neighbor != null) {
            priority = priority == null ? neighbor : higherPriority(priority, neighbor);
        }
        if (priority == null) return changed;
        ContentPriority previous = this.requestedContent.put(hash, priority);
        refreshContentPriority(hash);
        return changed || previous != priority;
    }

    public void acceptDirectory(Hash256 hash, RootDirectory directory) {
        Objects.requireNonNull(hash, "hash");
        Objects.requireNonNull(directory, "directory");
        TopRootBounds expected = this.expectedDirectories.get(hash);
        if (!this.expectedDirectories.containsKey(hash) || !consumeSubtree(hash)) {
            throw new IllegalArgumentException("unsolicited root directory");
        }
        if (expected != null && !relevant(expected)) {
            this.expectedDirectories.remove(hash);
            return;
        }
        if (!this.directories.containsKey(hash)) {
            requireObjectCapacity(loadedMetadataCount() + 1,
                    "loaded manifest metadata");
        }
        if (this.directories.putIfAbsent(hash, directory) != null) return;
        for (RootDirectoryEntry entry : directory.entries()) {
            if (expected != null && !expected.contains(entry.bounds())) {
                throw new IllegalArgumentException("nested root directory escapes its parent");
            }
            if (!relevant(entry.bounds())) continue;
            if (entry.target() == DirectoryTarget.ROOT_DIRECTORY) {
                tryExpectDirectory(entry.hash(), entry.bounds());
            } else {
                tryExpectManifest(entry.hash(), entry.node());
            }
        }
        refreshMetadataDiscovery();
    }

    public void acceptManifest(Hash256 hash, ManifestSubtree manifest) {
        Objects.requireNonNull(hash, "hash");
        Objects.requireNonNull(manifest, "manifest");
        SpatialNode expected = this.expectedManifests.get(hash);
        if (expected == null || !consumeSubtree(hash) || !expected.equals(manifest.root())) {
            throw new IllegalArgumentException("unsolicited or misplaced manifest subtree");
        }
        if (!relevant(expected)) {
            this.expectedManifests.remove(hash);
            return;
        }
        if (!tryInstallManifest(hash, manifest)) {
            requireObjectCapacity(loadedMetadataCount() + 1,
                    "loaded manifest metadata");
            this.deferredManifests.put(hash, manifest);
            this.metadataCapacityBlocked = true;
        }
        refreshMetadataDiscovery();
    }

    public void acceptDescriptorPage(Hash256 hash, DescriptorPage page) {
        Objects.requireNonNull(hash, "hash");
        Objects.requireNonNull(page, "page");
        ExpectedDescriptorPage expected = this.expectedDescriptorPages.get(hash);
        if (expected == null || !consumeSubtree(hash)
                || !expected.root().equals(page.root())
                || expected.pageIndex() != page.pageIndex()) {
            throw new IllegalArgumentException("unsolicited or misplaced descriptor page");
        }
        ManifestSubtree manifest = this.manifests.get(expected.manifestHash());
        if (manifest == null || !relevant(expected.root())) {
            this.expectedDescriptorPages.remove(hash);
            return;
        }
        try {
            page.validateAgainst(manifest);
        } catch (ManifestCodec.DecodeException exception) {
            throw new IllegalArgumentException(exception.getMessage(), exception);
        }
        if (!tryInstallDescriptorPage(hash, page)) {
            requireObjectCapacity(loadedMetadataCount() + 1, "loaded descriptor metadata");
            this.deferredDescriptorPages.put(hash, page);
            this.metadataCapacityBlocked = true;
        }
        refreshMetadataDiscovery();
    }

    /** Resolves every captured renderer demand after all relevant manifest objects arrive. */
    public Map<Long, Optional<Binding>> sealDiscovery() {
        if (!discoveryComplete()) {
            throw new IllegalStateException("manifest discovery is incomplete");
        }
        resolveAvailableDemands();
        return Map.copyOf(this.resolutions);
    }

    /**
     * Resolves each demand as soon as its own bounded descriptor page is present. Unresolved
     * metadata elsewhere in the view must not delay visible coverage. A missing manifest is
     * recorded as authoritative absence only after discovery is complete.
     */
    private void resolveAvailableDemands() {
        boolean discoveryComplete = discoveryComplete();
        // Resolve coarse coverage before fine detail so the first useful selector snapshot has
        // the hierarchy required for a draw. Descriptor resolution itself grants no object
        // capability; exact GPU-selected hashes are registered at the selection handoff.
        for (int lod = ManifestCodec.MAX_LOD; lod >= 0; lod--) {
            for (int demandIndex = 0; demandIndex < this.demanded.size(); demandIndex++) {
                long key = this.demanded.valueAt(demandIndex);
                if (spatial(key).lod() != lod) continue;
                if (this.resolutions.containsKey(key)) continue;
                if (!discoveryComplete && !resolutionAvailable(key)) continue;
                requireNodeCapacity((long) this.resolutions.size() + 1, "resolved demand");
                Optional<Binding> binding = resolve(key);
                this.resolutions.put(key, binding);
                // Descriptor visibility changes the immutable renderer manifest even when every
                // referenced object was already registered by another selected node.
                this.manifestRevision++;
                this.selectionTopologyRevision++;
            }
        }
    }

    private boolean resolutionAvailable(long key) {
        SpatialNode target = spatial(key);
        for (ManifestSubtree manifest : this.manifests.values()) {
            if (!contains(manifest.root(), target)) continue;
            int depth = manifest.root().lod() - target.lod();
            if (depth < 0 || depth >= manifest.levels()) continue;
            int slot = structuralSlot(manifest, target);
            if (manifest.node(slot).isEmpty()) return true;
            int pageIndex = slot / ManifestCodec.DESCRIPTOR_PAGE_NODE_SLOTS;
            Hash256 page = manifest.descriptorPage(pageIndex).orElseThrow(() ->
                    new IllegalStateException(
                            "available structural node lacks a descriptor page"));
            return this.descriptorPages.containsKey(page);
        }
        return false;
    }

    /** Grants one exact GPU-selected object capability and associates its decoded consumers. */
    void registerSelectedObject(long key, Hash256 hash, ObjectKind kind) {
        if (!this.resolutions.containsKey(key)) {
            throw new IllegalArgumentException("selected object belongs to an unresolved node");
        }
        registerExpectedObject(hash, kind, false);
        this.bindingsByObject.computeIfAbsent(hash, ignored -> new LinkedHashSet<>()).add(key);
    }

    /** Accepts one exact completion without materializing or scanning the capability table. */
    public int acceptObject(Hash256 hash, ObjectKind kind) {
        ExpectedObject expected = this.expectedObjects.get(Objects.requireNonNull(hash, "hash"));
        if (expected == null || expected.kind() != kind || !consumeObject(hash)) {
            throw new IllegalArgumentException("unsolicited or wrong-type object");
        }
        this.processedObjects.add(hash);
        LinkedHashSet<Long> keys = this.bindingsByObject.get(hash);
        return keys == null ? 0 : keys.size();
    }

    /** Adds the three verified kind-7 objects named by the root's dictionary-set object. */
    public void expectCompressionDictionaries(Collection<Hash256> hashes) {
        if (!this.processedObjects.contains(this.root.dictionarySetHash())) {
            throw new IllegalStateException("the root dictionary set is not processed");
        }
        Objects.requireNonNull(hashes, "hashes");
        if (hashes.size() != DictionaryCodec.DICTIONARY_COUNT) {
            throw new IllegalArgumentException("production root requires three dictionaries");
        }
        for (Hash256 hash : hashes) {
            expectObject(Objects.requireNonNull(hash, "dictionary hash"),
                    ObjectKind.COMPRESSION_DICTIONARY);
        }
    }

    public Optional<Binding> binding(long key) {
        return this.resolutions.getOrDefault(key, Optional.empty());
    }
    public Set<Long> resolvedKeys() { return Set.copyOf(this.resolutions.keySet()); }

    public ManifestView manifestView() {
        ArrayList<NodeView> nodes = new ArrayList<>(this.nodeHandles.size());
        for (Map.Entry<SpatialNode, Integer> entry : this.nodeHandles.entrySet()) {
            SpatialNode spatial = entry.getKey();
            ManifestSubtree manifest = manifestFor(spatial);
            int slot = structuralSlot(manifest, spatial);
            Optional<ManifestCodec.ManifestNode> structural = manifest.node(slot);
            if (structural.isEmpty()) {
                throw new IllegalStateException("admitted manifest node is absent");
            }
            // A loaded page may cover 64 siblings, but its content becomes selectable only
            // after this exact node was demanded and resolved. Exposing every sibling here
            // would register the entire page's object graph rather than the visible working cut.
            Optional<ManifestCodec.ManifestNode> node = this.resolutions.containsKey(
                    sectionKey(spatial)) ? combinedNode(manifest, slot) : structural;
            nodes.add(new NodeView(entry.getValue(), spatial, node.orElseThrow()));
        }
        nodes.sort(java.util.Comparator.comparingInt(NodeView::handle));
        ArrayList<ObjectView> objects = new ArrayList<>(this.objectHandles.size());
        for (Map.Entry<Hash256, Integer> entry : this.objectHandles.entrySet()) {
            ExpectedObject expected = this.expectedObjects.get(entry.getKey());
            if (expected == null) continue;
            objects.add(new ObjectView(entry.getValue(), entry.getKey(), expected,
                    this.processedObjects.contains(entry.getKey()),
                    this.objectInFlight.contains(entry.getKey())));
        }
        objects.sort(java.util.Comparator.comparingInt(ObjectView::handle));
        return new ManifestView(this.root.root(), nodes, objects, this.objectHandles,
                discoveryComplete());
    }

    /** Allocation-free selector topology access; valid only on the owning state thread. */
    int selectionNodeCount() {
        return this.nodesByHandle.size();
    }

    SpatialNode selectionSpatial(int handle) {
        if (handle < 0 || handle >= this.nodesByHandle.size()) {
            throw new IllegalArgumentException("selection node handle is outside this root");
        }
        return this.nodesByHandle.get(handle);
    }

    ManifestCodec.ManifestNode selectionStructuralNode(int handle) {
        SpatialNode spatial = selectionSpatial(handle);
        ManifestSubtree manifest = manifestFor(spatial);
        return manifest.node(structuralSlot(manifest, spatial)).orElseThrow(() ->
                new IllegalStateException("admitted selection node is absent"));
    }

    Binding selectionBinding(int handle) {
        Optional<Binding> binding = this.resolutions.get(sectionKey(selectionSpatial(handle)));
        return binding == null ? null : binding.orElse(null);
    }

    int selectionHandle(SpatialNode spatial) {
        return this.nodeHandles.getOrDefault(Objects.requireNonNull(spatial, "spatial node"), -1);
    }

    int selectionObjectHandle(Hash256 hash) {
        Integer handle = this.objectHandles.get(Objects.requireNonNull(hash, "object hash"));
        if (handle == null) throw new IllegalStateException("manifest object lacks a handle");
        return handle;
    }

    boolean selectionObjectInFlight(Hash256 hash) {
        return this.objectInFlight.contains(Objects.requireNonNull(hash, "object hash"));
    }

    /** Allocation-free indexed access for state-thread residency installation. */
    public int objectHandleCount() {
        return this.objectsByHandle.size();
    }

    public ObjectView objectView(int handle) {
        Hash256 hash = hashForObjectHandle(handle);
        ExpectedObject expected = this.expectedObjects.get(hash);
        if (expected == null) throw new IllegalStateException("object handle lacks capability");
        return new ObjectView(handle, hash, expected, this.processedObjects.contains(hash),
                this.objectInFlight.contains(hash));
    }

    public void forEachMetadataPin(Consumer<Hash256> visitor) {
        Objects.requireNonNull(visitor, "visitor");
        this.directories.keySet().forEach(visitor);
        this.manifests.keySet().forEach(visitor);
        this.descriptorPages.keySet().forEach(visitor);
        this.deferredManifests.keySet().forEach(visitor);
        this.deferredDescriptorPages.keySet().forEach(visitor);
        if (this.processedObjects.contains(this.root.catalogHash())) visitor.accept(this.root.catalogHash());
        if (this.processedObjects.contains(this.root.dictionarySetHash())) {
            visitor.accept(this.root.dictionarySetHash());
        }
        for (Map.Entry<Hash256, ExpectedObject> object : this.expectedObjects.entrySet()) {
            if (object.getValue().kind() == ObjectKind.COMPRESSION_DICTIONARY
                    && this.processedObjects.contains(object.getKey())) visitor.accept(object.getKey());
        }
    }

    /**
     * Decoded exact-demand objects that must remain resident while they wait for the rest of their
     * activation group. Withdrawn and historical requests are deliberately excluded.
     */
    public void forEachContentPin(Consumer<Hash256> visitor) {
        Objects.requireNonNull(visitor, "visitor");
        for (Hash256 hash : this.requestedContent.keySet()) {
            if (this.processedObjects.contains(hash)) visitor.accept(hash);
        }
        for (Hash256 hash : this.selectedContent.keySet()) {
            if (this.processedObjects.contains(hash)) visitor.accept(hash);
        }
        for (Hash256 hash : this.selectedNeighborContent.keySet()) {
            if (this.processedObjects.contains(hash)) visitor.accept(hash);
        }
    }

    public Hash256 hashForObjectHandle(int handle) {
        if (handle < 0 || handle >= this.objectsByHandle.size()) {
            throw new IllegalArgumentException("object handle is outside this root");
        }
        return this.objectsByHandle.get(handle);
    }

    private Optional<Binding> resolve(long key) {
        SpatialNode target = spatial(key);
        for (ManifestSubtree manifest : this.manifests.values()) {
            if (!contains(manifest.root(), target)) continue;
            int depth = manifest.root().lod() - target.lod();
            if (depth < 0 || depth >= manifest.levels()) continue;
            int slot = structuralSlot(manifest, target);
            var node = combinedNode(manifest, slot);
            if (node.isEmpty()) return Optional.empty();
            if (node.orElseThrow().contents().isEmpty()) {
                throw new IllegalStateException(
                        "descriptor page is not loaded for a resolved structural node");
            }
            ArrayList<ContentLayer> layers = new ArrayList<>(ContentClass.values().length);
            for (ContentClass contentClass : ContentClass.values()) {
                ContentDescriptor descriptor = node.orElseThrow().contents().get(contentClass);
                if (descriptor == null) continue;
                ArrayList<ContentObject> objects = new ArrayList<>(descriptor.objects().size());
                int dense = 0;
                for (int microtile = 0; microtile < Long.SIZE; microtile++) {
                    if ((descriptor.microtileMask() & 1L << microtile) == 0) continue;
                    objects.add(new ContentObject(contentClass,
                            MicrotileCodec.objectKind(contentClass), microtile,
                            descriptor.objects().get(dense++)));
                }
                layers.add(new ContentLayer(contentClass, descriptor.microtileMask(), objects,
                        descriptor.dependencies(), descriptor.neighborDependencies(),
                        descriptor.boundarySummary(), descriptor.exteriorVisibilityMask(),
                        descriptor.unknownVisibilityMask(), descriptor.visibilityMemberships()));
            }
            return layers.isEmpty() ? Optional.empty() : Optional.of(new Binding(key,
                    node.orElseThrow().childMask(), node.orElseThrow().bounds(),
                    node.orElseThrow().geometricErrorQ16(), layers));
        }
        return Optional.empty();
    }

    private Optional<ManifestCodec.ManifestNode> combinedNode(ManifestSubtree manifest,
                                                                 int structuralSlot) {
        Optional<ManifestCodec.ManifestNode> structural = manifest.node(structuralSlot);
        if (structural.isEmpty()) return Optional.empty();
        int pageIndex = structuralSlot / ManifestCodec.DESCRIPTOR_PAGE_NODE_SLOTS;
        Optional<Hash256> pageHash = manifest.descriptorPage(pageIndex);
        if (pageHash.isEmpty()) {
            throw new IllegalStateException("available structural node lacks a descriptor page");
        }
        DescriptorPage page = this.descriptorPages.get(pageHash.orElseThrow());
        Map<ContentClass, ContentDescriptor> contents = page == null
                ? Map.of()
                : page.contents(structuralSlot % ManifestCodec.DESCRIPTOR_PAGE_NODE_SLOTS);
        ManifestCodec.ManifestNode node = structural.orElseThrow();
        return Optional.of(new ManifestCodec.ManifestNode(node.childMask(), node.bounds(),
                node.geometricErrorQ16(), contents));
    }

    private boolean tryExpectDirectory(Hash256 hash, TopRootBounds bounds) {
        if (this.directories.containsKey(hash)) return true;
        if (this.expectedManifests.containsKey(hash)
                || this.expectedDescriptorPages.containsKey(hash)) {
            throw new IllegalArgumentException("subtree hash changes type");
        }
        if (this.expectedDirectories.containsKey(hash)) {
            if (!Objects.equals(this.expectedDirectories.get(hash), bounds)) {
                    throw new IllegalArgumentException(
                            "directory hash appears at two spatial extents");
            }
            return true;
        }
        if (!hasObjectCapacity(expectedMetadataCount() + 1)) {
            this.metadataCapacityBlocked = true;
            return false;
        }
        this.expectedDirectories.put(hash, bounds);
        this.subtreeQueue.add(hash);
        return true;
    }

    private boolean tryExpectManifest(Hash256 hash, SpatialNode root) {
        if (this.manifests.containsKey(hash) || this.deferredManifests.containsKey(hash)) {
            SpatialNode previous = this.expectedManifests.get(hash);
            if (previous != null && !previous.equals(root)) {
                throw new IllegalArgumentException("manifest hash appears at two spatial roots");
            }
            return true;
        }
        if (this.expectedDirectories.containsKey(hash)
                || this.expectedDescriptorPages.containsKey(hash)) {
            throw new IllegalArgumentException("subtree hash changes type");
        }
        SpatialNode previous = this.expectedManifests.get(hash);
        if (previous != null) {
            if (!previous.equals(root)) {
                throw new IllegalArgumentException("manifest hash appears at two spatial roots");
            }
            return true;
        }
        if (!hasObjectCapacity(expectedMetadataCount() + 1)) {
            this.metadataCapacityBlocked = true;
            return false;
        }
        this.expectedManifests.put(hash, root);
        this.subtreeQueue.add(hash);
        return true;
    }

    private boolean tryExpectDescriptorPage(Hash256 hash, Hash256 manifestHash,
                                            SpatialNode root, int pageIndex) {
        ExpectedDescriptorPage expected = new ExpectedDescriptorPage(
                manifestHash, root, pageIndex);
        if (this.descriptorPages.containsKey(hash)
                || this.deferredDescriptorPages.containsKey(hash)) {
            ExpectedDescriptorPage previous = this.expectedDescriptorPages.get(hash);
            if (previous != null && !previous.equals(expected)) {
                throw new IllegalArgumentException(
                        "descriptor-page hash appears at two manifest slots");
            }
            return true;
        }
        if (this.expectedDirectories.containsKey(hash)
                || this.expectedManifests.containsKey(hash)) {
            throw new IllegalArgumentException("subtree hash changes type");
        }
        ExpectedDescriptorPage previous = this.expectedDescriptorPages.get(hash);
        if (previous != null) {
            if (!previous.equals(expected)) {
                throw new IllegalArgumentException(
                        "descriptor-page hash appears at two manifest slots");
            }
            return true;
        }
        if (!hasObjectCapacity(expectedMetadataCount() + 1)) {
            this.metadataCapacityBlocked = true;
            return false;
        }
        this.expectedDescriptorPages.put(hash, expected);
        this.subtreeQueue.add(hash);
        return true;
    }

    /** Retains the complete five-level manifest while admitting only its root into the GPU cut. */
    private boolean tryInstallManifest(Hash256 hash, ManifestSubtree manifest) {
        if (this.manifests.containsKey(hash)) return true;
        long loadedMetadata = loadedMetadataCount()
                + (this.deferredManifests.containsKey(hash) ? 0 : 1);
        if (!hasObjectCapacity(loadedMetadata)) return false;
        ManifestSubtree previous = this.manifestsByRoot.get(manifest.root());
        if (previous != null && previous != manifest) {
            throw new IllegalArgumentException("loaded manifests overlap a top-level root");
        }

        this.deferredManifests.remove(hash);
        this.manifests.put(hash, manifest);
        this.manifestsByRoot.put(manifest.root(), manifest);
        this.manifestRevision++;
        rebuildAdmittedNodes();
        return true;
    }

    /** Installs one descriptor page without widening the exact renderer demand. */
    private boolean tryInstallDescriptorPage(Hash256 hash, DescriptorPage page) {
        if (this.descriptorPages.containsKey(hash)) return true;
        // Validate the immutable page at its trust boundary, but do not widen live demand to
        // every object referenced by all 64 descriptor slots. Only exact hashes selected by the
        // renderer are registered later at the selection handoff.
        LinkedHashMap<Hash256, ExpectedObject> additions = new LinkedHashMap<>();
        for (int localSlot = 0; localSlot < page.slotCount(); localSlot++) {
            for (Map.Entry<ContentClass, ContentDescriptor> content
                    : page.contents(localSlot).entrySet()) {
                ObjectKind kind = MicrotileCodec.objectKind(content.getKey());
                ExpectedObject expected = new ExpectedObject(kind);
                for (Hash256 object : content.getValue().objects()) {
                    validateManifestReference(object, expected, additions);
                }
                for (Hash256 dependency : content.getValue().dependencies()) {
                    validateManifestReference(dependency, expected, additions);
                }
                ExpectedObject complex = new ExpectedObject(ObjectKind.COMPLEX_MICROTILE);
                for (NeighborDependency dependency
                        : content.getValue().neighborDependencies()) {
                    validateManifestReference(dependency.hash(), complex, additions);
                }
            }
        }
        long loadedMetadata = loadedMetadataCount()
                + (this.deferredDescriptorPages.containsKey(hash) ? 0 : 1);
        if (!hasObjectCapacity(loadedMetadata)) {
            return false;
        }
        this.deferredDescriptorPages.remove(hash);
        this.descriptorPages.put(hash, page);
        this.manifestRevision++;
        return true;
    }

    private long expectedMetadataCount() {
        return (long) this.expectedDirectories.size() + this.expectedManifests.size()
                + this.expectedDescriptorPages.size();
    }

    private long loadedMetadataCount() {
        return (long) this.directories.size() + this.manifests.size()
                + this.descriptorPages.size() + this.deferredManifests.size()
                + this.deferredDescriptorPages.size();
    }

    private void validateManifestReference(Hash256 hash, ExpectedObject expected,
                                           Map<Hash256, ExpectedObject> additions) {
        ExpectedObject present = this.expectedObjects.get(hash);
        if (present != null) {
            if (!present.equals(expected)) {
                throw new IllegalArgumentException("object hash changes type");
            }
            return;
        }
        ExpectedObject pending = additions.putIfAbsent(hash, expected);
        if (pending != null && !pending.equals(expected)) {
            throw new IllegalArgumentException("object hash changes type");
        }
    }

    private void expectObject(Hash256 hash, ObjectKind kind) {
        if (isMicrotile(kind)) {
            throw new IllegalArgumentException(
                    "microtile capabilities must be requested by exact selector demand");
        }
        registerExpectedObject(hash, kind, true);
    }

    private void registerExpectedObject(Hash256 hash, ObjectKind kind, boolean enqueue) {
        ExpectedObject expected = new ExpectedObject(kind);
        ExpectedObject previous = this.expectedObjects.get(hash);
        if (previous != null) {
            if (!previous.equals(expected)) throw new IllegalArgumentException("object hash changes type");
            if (enqueue) enqueueExpectedObject(hash);
            return;
        }
        requireObjectCapacity((long) this.expectedObjects.size() + 1,
                "content object table");
        this.expectedObjects.put(hash, expected);
        this.objectHandles.put(hash, this.objectHandles.size());
        this.objectsByHandle.add(hash);
        // Object handles never enter GPU selection topology. They are owner-thread-local
        // capabilities expanded from authenticated selected node masks, so granting one must not
        // invalidate the very selector frontier that will later retire it.
        if (enqueue) enqueueExpectedObject(hash);
    }

    private void enqueueExpectedObject(Hash256 hash) {
        ExpectedObject expected = this.expectedObjects.get(hash);
        if (expected == null || this.processedObjects.contains(hash)
                || this.objectInFlight.contains(hash)) return;
        if (isMicrotile(expected.kind())) {
            if (this.requestedContent.containsKey(hash)) refreshContentPriority(hash);
        }
        else this.bootstrapQueue.add(hash);
    }

    private boolean consumeSubtree(Hash256 hash) {
        return this.subtreeInFlight.remove(hash) || this.subtreeQueue.remove(hash);
    }

    private boolean consumeObject(Hash256 hash) {
        return this.objectInFlight.remove(hash) || this.bootstrapQueue.remove(hash)
                || this.coverageQueue.remove(hash) || this.currentViewQueue.remove(hash)
                || this.predictedQueue.remove(hash);
    }

    private void refreshContentPriority(Hash256 hash) {
        if (this.processedObjects.contains(hash) || this.objectInFlight.contains(hash)) return;
        ContentPriority requested = this.requestedContent.get(hash);
        removeFromContentQueues(hash);
        if (requested == null) return;
        queueContent(hash, requested, false);
    }

    private void queueContent(Hash256 hash, ContentPriority requested, boolean first) {
        int priority = requested.queuePriority;
        LinkedHashSet<Hash256> queue;
        if (priority >= ContentPriority.COVERAGE.queuePriority) {
            queue = this.coverageQueue;
        } else if (priority >= ContentPriority.CURRENT_VIEW.queuePriority) {
            queue = this.currentViewQueue;
        } else {
            queue = this.predictedQueue;
        }
        if (first) queue.addFirst(hash); else queue.addLast(hash);
    }

    private boolean queuedAt(Hash256 hash, ContentPriority priority) {
        if (priority == null) return false;
        return switch (priority) {
            case COVERAGE -> this.coverageQueue.contains(hash);
            case CURRENT_VIEW -> this.currentViewQueue.contains(hash);
            case PREDICTED -> this.predictedQueue.contains(hash);
        };
    }

    private void removeFromContentQueues(Hash256 hash) {
        this.coverageQueue.remove(hash);
        this.currentViewQueue.remove(hash);
        this.predictedQueue.remove(hash);
    }

    private static boolean isMicrotile(ObjectKind kind) {
        return kind == ObjectKind.EXTERIOR_MICROTILE
                || kind == ObjectKind.INTERIOR_MICROTILE
                || kind == ObjectKind.COMPLEX_MICROTILE;
    }

    private void requireObjectCapacity(long count, String label) {
        if (!hasObjectCapacity(count)) {
            throw new IllegalStateException(label + " exceeds its protocol limit");
        }
    }

    private void requireNodeCapacity(long count, String label) {
        if (!hasNodeCapacity(count)) {
            throw new IllegalStateException(label + " exceeds its protocol limit");
        }
    }

    private boolean hasObjectCapacity(long count) {
        return count <= this.limits.maxObjects();
    }

    private boolean hasNodeCapacity(long count) {
        return count <= this.limits.maxNodes();
    }

    private boolean relevant(SpatialNode entry) {
        for (SpatialNode root : this.metadataRoots) {
            if (contains(entry, root) || contains(root, entry)) return true;
        }
        for (int demandIndex = 0; demandIndex < this.demanded.size(); demandIndex++) {
            long key = this.demanded.valueAt(demandIndex);
            SpatialNode target = spatial(key);
            if (contains(entry, target) || contains(target, entry)) return true;
        }
        return false;
    }

    private boolean relevant(TopRootBounds bounds) {
        for (SpatialNode root : this.metadataRoots) {
            if (bounds.contains(root)) return true;
        }
        for (int demandIndex = 0; demandIndex < this.demanded.size(); demandIndex++) {
            long key = this.demanded.valueAt(demandIndex);
            if (bounds.contains(topRoot(spatial(key)))) return true;
        }
        return false;
    }

    private boolean subtreeRelevant(Hash256 hash) {
        if (this.expectedDirectories.containsKey(hash)) {
            TopRootBounds bounds = this.expectedDirectories.get(hash);
            return bounds == null || relevant(bounds);
        }
        SpatialNode root = this.expectedManifests.get(hash);
        if (root != null) return relevant(root);
        ExpectedDescriptorPage page = this.expectedDescriptorPages.get(hash);
        return page != null && relevant(page.root());
    }

    /** Drops historical metadata while preserving exact outstanding response capabilities. */
    private void pruneIrrelevantMetadata(boolean topologyWasComplete) {
        Map<SpatialNode, Integer> previousNodeHandles = Map.copyOf(this.nodeHandles);
        Set<Hash256> previousManifests = Set.copyOf(this.manifests.keySet());
        Set<Hash256> previousDescriptorPages = Set.copyOf(this.descriptorPages.keySet());
        this.subtreeQueue.removeIf(hash -> !subtreeRelevant(hash));
        this.directories.entrySet().removeIf(entry -> {
            TopRootBounds bounds = this.expectedDirectories.get(entry.getKey());
            return bounds != null && !relevant(bounds);
        });
        this.manifests.entrySet().removeIf(entry -> {
            SpatialNode root = this.expectedManifests.get(entry.getKey());
            return root == null || !relevant(root);
        });
        this.deferredManifests.entrySet().removeIf(entry -> {
            SpatialNode root = this.expectedManifests.get(entry.getKey());
            return root == null || !relevant(root);
        });
        this.descriptorPages.entrySet().removeIf(entry -> {
            ExpectedDescriptorPage page = this.expectedDescriptorPages.get(entry.getKey());
            return page == null || !relevant(page.root());
        });
        this.deferredDescriptorPages.entrySet().removeIf(entry -> {
            ExpectedDescriptorPage page = this.expectedDescriptorPages.get(entry.getKey());
            return page == null || !relevant(page.root());
        });
        this.expectedDirectories.entrySet().removeIf(entry -> entry.getValue() != null
                && !relevant(entry.getValue())
                && !this.subtreeInFlight.contains(entry.getKey()));
        this.expectedManifests.entrySet().removeIf(entry -> !relevant(entry.getValue())
                && !this.subtreeInFlight.contains(entry.getKey()));
        this.expectedDescriptorPages.entrySet().removeIf(entry ->
                !relevant(entry.getValue().root())
                        && !this.subtreeInFlight.contains(entry.getKey()));
        rebuildReachableRegistries();
        boolean handleNamespaceChanged = !previousNodeHandles.equals(this.nodeHandles);
        if (topologyWasComplete != discoveryComplete()
                || !previousManifests.equals(this.manifests.keySet())
                || !previousDescriptorPages.equals(this.descriptorPages.keySet())
                || handleNamespaceChanged) {
            this.manifestRevision++;
            if (handleNamespaceChanged) this.selectionTopologyRevision++;
        }
    }

    private void rebuildReachableRegistries() {
        LinkedHashMap<Hash256, ExpectedObject> retained = new LinkedHashMap<>();
        for (Map.Entry<Hash256, ExpectedObject> entry : this.expectedObjects.entrySet()) {
            if (!isMicrotile(entry.getValue().kind())) {
                putExpected(retained, entry.getKey(), entry.getValue());
            }
        }
        // A resolved descriptor is topology, not request authority. Retain only hashes selected,
        // requested, or already in flight; the next GPU handoff can grant a newly visible hash.
        for (Hash256 hash : this.requestedContent.keySet()) {
            ExpectedObject expected = this.expectedObjects.get(hash);
            if (expected != null) putExpected(retained, hash, expected);
        }
        for (Hash256 hash : this.selectedContent.keySet()) {
            ExpectedObject expected = this.expectedObjects.get(hash);
            if (expected != null) putExpected(retained, hash, expected);
        }
        for (Hash256 hash : this.selectedNeighborContent.keySet()) {
            ExpectedObject expected = this.expectedObjects.get(hash);
            if (expected != null) putExpected(retained, hash, expected);
        }
        for (Hash256 hash : this.objectInFlight) {
            ExpectedObject expected = this.expectedObjects.get(hash);
            if (expected != null) putExpected(retained, hash, expected);
        }
        this.expectedObjects.clear();
        this.expectedObjects.putAll(retained);

        Set<Hash256> reachable = this.expectedObjects.keySet();
        this.bootstrapQueue.retainAll(reachable);
        this.coverageQueue.retainAll(reachable);
        this.currentViewQueue.retainAll(reachable);
        this.predictedQueue.retainAll(reachable);
        this.processedObjects.retainAll(reachable);
        this.requestedContent.keySet().retainAll(reachable);
        this.selectedContent.keySet().retainAll(reachable);
        this.selectedNeighborContent.keySet().retainAll(reachable);
        this.bindingsByObject.entrySet().removeIf(entry -> !reachable.contains(entry.getKey()));
        for (LinkedHashSet<Long> keys : this.bindingsByObject.values()) {
            keys.removeIf(key -> !this.demanded.contains(key));
        }
        this.bindingsByObject.entrySet().removeIf(entry -> entry.getValue().isEmpty());

        ArrayList<Hash256> handleOrder = new ArrayList<>(this.expectedObjects.size());
        HashSet<Hash256> handled = new HashSet<>();
        for (Hash256 hash : this.objectsByHandle) {
            if (reachable.contains(hash) && handled.add(hash)) handleOrder.add(hash);
        }
        for (Hash256 hash : reachable) {
            if (handled.add(hash)) handleOrder.add(hash);
        }
        this.objectHandles.clear();
        this.objectsByHandle.clear();
        for (Hash256 hash : handleOrder) {
            this.objectHandles.put(hash, this.objectsByHandle.size());
            this.objectsByHandle.add(hash);
        }

        this.manifestsByRoot.clear();
        for (ManifestSubtree manifest : this.manifests.values()) {
            if (this.manifestsByRoot.put(manifest.root(), manifest) != null) {
                throw new IllegalStateException("loaded manifests overlap a top-level root");
            }
        }
        rebuildAdmittedNodes();
    }

    /**
     * Rebuilds the bounded selector working cut. Existing demanded coverage and all of its
     * ancestors are admitted first; top-level window roots follow; only then are the immediate
     * children of demanded nodes admitted for progressive refinement.
     */
    private void rebuildAdmittedNodes() {
        this.admittedScratch.clear();
        boolean blocked = false;

        // Preserve the complete path for every currently selected/renderable node before using
        // capacity on new roots or finer detail.
        for (int index = 0; index < this.demanded.size(); index++) {
            SpatialNode target = spatial(this.demanded.valueAt(index));
            for (int lod = ManifestCodec.MAX_LOD; lod >= target.lod(); lod--) {
                blocked |= !admitIfPresent(ancestorAt(target, lod));
            }
        }
        for (SpatialNode root : this.metadataRoots) {
            blocked |= !admitIfPresent(root);
        }

        // Demand order comes from the score-sorted selector frontier. If refinement reaches the
        // bound, lower-priority children wait while every already selected node remains present.
        for (int index = 0; index < this.demanded.size(); index++) {
            SpatialNode parent = spatial(this.demanded.valueAt(index));
            if (parent.lod() == 0) continue;
            ManifestSubtree manifest = manifestForOrNull(parent);
            if (manifest == null) continue;
            Optional<ManifestCodec.ManifestNode> node = manifest.node(
                    structuralSlot(manifest, parent));
            if (node.isEmpty()) continue;
            int childMask = node.orElseThrow().childMask();
            for (int child = 0; child < 8; child++) {
                if ((childMask & 1 << child) == 0) continue;
                SpatialNode childNode = new SpatialNode(parent.lod() - 1,
                        parent.x() * 2 + (child & 1),
                        parent.y() * 2 + (child >>> 1 & 1),
                        parent.z() * 2 + (child >>> 2 & 1));
                blocked |= !admitIfPresent(childNode);
            }
        }

        boolean changed = this.nodeHandles.size() != this.admittedScratch.size();
        if (!changed) {
            for (int index = 0; index < this.admittedScratch.size(); index++) {
                SpatialNode node = spatial(this.admittedScratch.valueAt(index));
                Integer handle = this.nodeHandles.get(node);
                if (handle == null || handle != index) {
                    changed = true;
                    break;
                }
            }
        }
        if (changed) {
            this.nodeHandles.clear();
            this.nodesByHandle.clear();
            for (int index = 0; index < this.admittedScratch.size(); index++) {
                SpatialNode node = spatial(this.admittedScratch.valueAt(index));
                if (this.nodeHandles.put(node, index) != null) {
                    throw new IllegalStateException("admitted structural node is duplicated");
                }
                this.nodesByHandle.add(node);
            }
            this.manifestRevision++;
            this.selectionTopologyRevision++;
        }
        this.metadataCapacityBlocked |= blocked;
    }

    /** Returns false only when a present node cannot enter the bounded working cut. */
    private boolean admitIfPresent(SpatialNode node) {
        ManifestSubtree manifest = manifestForOrNull(node);
        if (manifest == null) return true;
        int depth = manifest.root().lod() - node.lod();
        if (depth < 0 || depth >= manifest.levels()) return true;
        if (manifest.node(structuralSlot(manifest, node)).isEmpty()) return true;
        long key = sectionKey(node);
        if (this.admittedScratch.contains(key)) return true;
        if (this.admittedScratch.size() >= this.limits.maxNodes()) return false;
        this.admittedScratch.add(key);
        return true;
    }

    private ManifestSubtree manifestFor(SpatialNode node) {
        ManifestSubtree manifest = manifestForOrNull(node);
        if (manifest == null) {
            throw new IllegalStateException("node has no resident top-level manifest");
        }
        return manifest;
    }

    private ManifestSubtree manifestForOrNull(SpatialNode node) {
        return this.manifestsByRoot.get(topRoot(node));
    }

    private static SpatialNode ancestorAt(SpatialNode node, int lod) {
        if (lod < node.lod() || lod > ManifestCodec.MAX_LOD) {
            throw new IllegalArgumentException("invalid structural ancestor level");
        }
        int x = node.x();
        int y = node.y();
        int z = node.z();
        for (int current = node.lod(); current < lod; current++) {
            x = Math.floorDiv(x, 2);
            y = Math.floorDiv(y, 2);
            z = Math.floorDiv(z, 2);
        }
        return new SpatialNode(lod, x, y, z);
    }

    private static void putExpected(Map<Hash256, ExpectedObject> expectedObjects,
                                    Hash256 hash, ExpectedObject expected) {
        ExpectedObject previous = expectedObjects.putIfAbsent(hash, expected);
        if (previous != null && !previous.equals(expected)) {
            throw new IllegalArgumentException("object hash changes type");
        }
    }

    /** Replays resident metadata after movement frees bounded table capacity. */
    private void refreshMetadataDiscovery() {
        boolean topologyWasComplete = discoveryComplete();
        this.metadataCapacityBlocked = false;
        for (Map.Entry<Hash256, ManifestSubtree> deferred
                : List.copyOf(this.deferredManifests.entrySet())) {
            SpatialNode root = this.expectedManifests.get(deferred.getKey());
            if (root == null || !relevant(root)) {
                this.deferredManifests.remove(deferred.getKey());
                continue;
            }
            if (!tryInstallManifest(deferred.getKey(), deferred.getValue())) {
                this.metadataCapacityBlocked = true;
            }
        }
        for (Map.Entry<Hash256, DescriptorPage> deferred
                : List.copyOf(this.deferredDescriptorPages.entrySet())) {
            ExpectedDescriptorPage expected = this.expectedDescriptorPages.get(deferred.getKey());
            if (expected == null || !relevant(expected.root())) {
                this.deferredDescriptorPages.remove(deferred.getKey());
                continue;
            }
            if (!tryInstallDescriptorPage(deferred.getKey(), deferred.getValue())) {
                this.metadataCapacityBlocked = true;
            }
        }
        for (Map.Entry<Hash256, RootDirectory> directory : this.directories.entrySet()) {
            TopRootBounds parent = this.expectedDirectories.get(directory.getKey());
            for (RootDirectoryEntry entry : directory.getValue().entries()) {
                if (parent != null && !parent.contains(entry.bounds())) {
                    throw new IllegalArgumentException("nested root directory escapes its parent");
                }
                if (!relevant(entry.bounds())) continue;
                if (entry.target() == DirectoryTarget.ROOT_DIRECTORY) {
                    tryExpectDirectory(entry.hash(), entry.bounds());
                } else {
                    tryExpectManifest(entry.hash(), entry.node());
                }
            }
        }
        for (Map.Entry<Hash256, ManifestSubtree> entry : this.manifests.entrySet()) {
            ManifestSubtree manifest = entry.getValue();
            if (!relevant(manifest.root())) continue;
            // Structural metadata is sufficient for the GPU to choose a descriptorless node.
            // Fetch only the pages containing exact renderer demand; eagerly loading page zero
            // for every root makes a large render window consume content capacity off-screen.
            for (int demandIndex = 0; demandIndex < this.demanded.size(); demandIndex++) {
                long key = this.demanded.valueAt(demandIndex);
                SpatialNode target = spatial(key);
                if (!contains(manifest.root(), target)) continue;
                int depth = manifest.root().lod() - target.lod();
                if (depth < 0 || depth >= manifest.levels()) continue;
                int slot = structuralSlot(manifest, target);
                if (manifest.node(slot).isPresent()) {
                    expectDescriptorPage(entry.getKey(), manifest,
                            slot / ManifestCodec.DESCRIPTOR_PAGE_NODE_SLOTS);
                }
            }
        }
        if (!this.deferredManifests.isEmpty() || !this.deferredDescriptorPages.isEmpty()) {
            this.metadataCapacityBlocked = true;
        }
        rebuildAdmittedNodes();
        if (topologyWasComplete != discoveryComplete()) this.manifestRevision++;
        resolveAvailableDemands();
    }

    private void expectDescriptorPage(Hash256 manifestHash, ManifestSubtree manifest,
                                      int pageIndex) {
        Hash256 page = manifest.descriptorPage(pageIndex).orElseThrow(() ->
                new IllegalStateException("available structural range lacks a descriptor page"));
        tryExpectDescriptorPage(page, manifestHash, manifest.root(), pageIndex);
    }

    private static SpatialNode topRoot(SpatialNode node) {
        int x = node.x();
        int y = node.y();
        int z = node.z();
        for (int lod = node.lod(); lod < ManifestCodec.MAX_LOD; lod++) {
            x = Math.floorDiv(x, 2);
            y = Math.floorDiv(y, 2);
            z = Math.floorDiv(z, 2);
        }
        return new SpatialNode(ManifestCodec.MAX_LOD, x, y, z);
    }

    private static <T> List<T> drain(ArrayDeque<T> queue, Set<T> inFlight, int maximum) {
        if (maximum < 1 || maximum > 256) throw new IllegalArgumentException("invalid request batch");
        List<T> values = new ArrayList<>(Math.min(maximum, queue.size()));
        while (values.size() < maximum && !queue.isEmpty()) {
            T value = queue.removeFirst();
            if (!inFlight.add(value)) throw new IllegalStateException("request is already in flight");
            values.add(value);
        }
        return List.copyOf(values);
    }

    private static <T> void drainSet(LinkedHashSet<T> queue, Set<T> inFlight, int maximum,
                                     List<T> output) {
        if (maximum < 1 || maximum > 256) throw new IllegalArgumentException("invalid request batch");
        var iterator = queue.iterator();
        while (iterator.hasNext() && output.size() < maximum) {
            T value = iterator.next();
            iterator.remove();
            if (!inFlight.add(value)) throw new IllegalStateException("request is already in flight");
            output.add(value);
        }
    }

    public static SpatialNode spatial(long key) {
        if ((key & 15) != 0 || SectionKey.level(key) > ManifestCodec.MAX_LOD) {
            throw new IllegalArgumentException("invalid Voxy section key");
        }
        SpatialNode node = new SpatialNode(SectionKey.level(key), SectionKey.x(key),
                SectionKey.y(key), SectionKey.z(key));
        if (SectionKey.pack(node.lod(), node.x(), node.y(), node.z()) != key) {
            throw new IllegalArgumentException("Voxy section key is not canonical");
        }
        return node;
    }

    public static long sectionKey(SpatialNode node) {
        long key = SectionKey.pack(node.lod(), node.x(), node.y(), node.z());
        if (!spatial(key).equals(node)) throw new IllegalArgumentException("spatial node exceeds Voxy key bounds");
        return key;
    }

    private static boolean contains(SpatialNode ancestor, SpatialNode descendant) {
        if (ancestor.lod() < descendant.lod()) return false;
        int x = descendant.x();
        int y = descendant.y();
        int z = descendant.z();
        for (int lod = descendant.lod(); lod < ancestor.lod(); lod++) {
            x = Math.floorDiv(x, 2);
            y = Math.floorDiv(y, 2);
            z = Math.floorDiv(z, 2);
        }
        return x == ancestor.x() && y == ancestor.y() && z == ancestor.z();
    }

    private static int structuralSlot(ManifestSubtree manifest, SpatialNode target) {
        if (!contains(manifest.root(), target)) throw new IllegalArgumentException("target is outside manifest");
        int depth = manifest.root().lod() - target.lod();
        if (depth < 0 || depth >= manifest.levels()) {
            throw new IllegalArgumentException("target is outside manifest depth");
        }
        int x = target.x();
        int y = target.y();
        int z = target.z();
        int morton = 0;
        int[] path = new int[depth];
        for (int index = depth - 1; index >= 0; index--) {
            path[index] = Math.floorMod(x, 2) | Math.floorMod(y, 2) << 1
                    | Math.floorMod(z, 2) << 2;
            x = Math.floorDiv(x, 2);
            y = Math.floorDiv(y, 2);
            z = Math.floorDiv(z, 2);
        }
        for (int octant : path) morton = morton * 8 + octant;
        return ManifestCodec.levelOffset(depth) + morton;
    }

    public static SpatialNode spatialAtSlot(ManifestSubtree manifest, int slot) {
        Objects.requireNonNull(manifest, "manifest");
        if (slot < 0 || slot >= manifest.structuralSlots()) {
            throw new IndexOutOfBoundsException(slot);
        }
        int depth = -1;
        for (int candidate = 0; candidate < manifest.levels(); candidate++) {
            int start = ManifestCodec.levelOffset(candidate);
            int end = start + powerOfEight(candidate);
            if (slot >= start && slot < end) {
                depth = candidate;
                break;
            }
        }
        if (depth < 0) throw new IllegalArgumentException("manifest slot has no depth");
        int morton = slot - ManifestCodec.levelOffset(depth);
        int x = manifest.root().x();
        int y = manifest.root().y();
        int z = manifest.root().z();
        int divisor = powerOfEight(Math.max(0, depth - 1));
        for (int level = 0; level < depth; level++) {
            int octant = morton / divisor;
            morton %= divisor;
            if (divisor > 1) divisor /= 8;
            x = x * 2 + (octant & 1);
            y = y * 2 + (octant >>> 1 & 1);
            z = z * 2 + (octant >>> 2 & 1);
        }
        return new SpatialNode(manifest.root().lod() - depth, x, y, z);
    }

    /** Bounded primitive set used by the hot demand reconciliation path. */
    private static final class PrimitiveLongSet {
        private long[] values = new long[16];
        private long[] tableKeys = new long[32];
        private int[] tableIndexes = new int[32];
        private byte[] states = new byte[32];
        private int size;
        private int occupied;

        private int size() { return this.size; }
        private long valueAt(int index) {
            if (index < 0 || index >= this.size) throw new IndexOutOfBoundsException(index);
            return this.values[index];
        }

        private boolean contains(long key) { return find(key) >= 0; }

        private boolean add(long key) {
            if ((this.occupied + 1) * 2 >= this.tableKeys.length) {
                rehash(Math.multiplyExact(this.tableKeys.length, 2));
            }
            int existing = find(key);
            if (existing >= 0) return false;
            if (this.size == this.values.length) {
                this.values = java.util.Arrays.copyOf(this.values,
                        Math.multiplyExact(this.values.length, 2));
            }
            int slot = insertionSlot(key);
            if (this.states[slot] == 0) this.occupied++;
            this.states[slot] = 1;
            this.tableKeys[slot] = key;
            this.tableIndexes[slot] = this.size;
            this.values[this.size++] = key;
            return true;
        }

        private boolean remove(long key) {
            int slot = find(key);
            if (slot < 0) return false;
            int removedIndex = this.tableIndexes[slot];
            this.states[slot] = 2;
            int lastIndex = --this.size;
            if (removedIndex != lastIndex) {
                long moved = this.values[lastIndex];
                this.values[removedIndex] = moved;
                int movedSlot = find(moved);
                if (movedSlot < 0) throw new IllegalStateException("primitive set index drift");
                this.tableIndexes[movedSlot] = removedIndex;
            }
            return true;
        }

        private void clear() {
            java.util.Arrays.fill(this.states, (byte) 0);
            this.size = 0;
            this.occupied = 0;
        }

        private int find(long key) {
            int mask = this.tableKeys.length - 1;
            int slot = mix(key) & mask;
            while (this.states[slot] != 0) {
                if (this.states[slot] == 1 && this.tableKeys[slot] == key) return slot;
                slot = slot + 1 & mask;
            }
            return -1;
        }

        private int insertionSlot(long key) {
            int mask = this.tableKeys.length - 1;
            int slot = mix(key) & mask;
            int tombstone = -1;
            while (this.states[slot] != 0) {
                if (tombstone < 0 && this.states[slot] == 2) tombstone = slot;
                slot = slot + 1 & mask;
            }
            return tombstone >= 0 ? tombstone : slot;
        }

        private void rehash(int capacity) {
            long[] oldValues = this.values;
            int oldSize = this.size;
            this.tableKeys = new long[capacity];
            this.tableIndexes = new int[capacity];
            this.states = new byte[capacity];
            this.size = 0;
            this.occupied = 0;
            for (int index = 0; index < oldSize; index++) add(oldValues[index]);
        }

        private static int mix(long key) {
            key ^= key >>> 33;
            key *= 0xc4ceb9fe1a85ec53L;
            key ^= key >>> 33;
            return (int) key;
        }
    }

    /** Reusable handle-indexed priority marks for complete selector frontiers. */
    private static final class PrimitivePriorityMarks {
        private int[] epochs = new int[0];
        private byte[] priorityByHandle = new byte[0];
        private int[] handles = new int[0];
        private byte[] priorities = new byte[0];
        private int[] slotByHandle = new int[0];
        private int epoch;
        private int count;

        private PrimitivePriorityMarks begin(int capacity, int[] sourceHandles,
                                             byte[] sourcePriorities, int sourceCount) {
            if (capacity > this.epochs.length) {
                int grown = Math.max(16, this.epochs.length);
                while (grown < capacity) grown = Math.multiplyExact(grown, 2);
                this.epochs = new int[grown];
                this.priorityByHandle = new byte[grown];
                this.handles = new int[grown];
                this.priorities = new byte[grown];
                this.slotByHandle = new int[grown];
                this.epoch = 0;
            }
            if (++this.epoch == 0) {
                java.util.Arrays.fill(this.epochs, 0);
                this.epoch = 1;
            }
            this.count = 0;
            for (int index = 0; index < sourceCount; index++) {
                int handle = sourceHandles[index];
                if (handle < 0 || handle >= capacity) {
                    throw new IllegalArgumentException("object handle is outside its root");
                }
                byte priority = sourcePriorities[index];
                primitivePriority(priority);
                if (this.epochs[handle] == this.epoch) {
                    if (priority > this.priorityByHandle[handle]) {
                        this.priorityByHandle[handle] = priority;
                        this.priorities[this.slotByHandle[handle]] = priority;
                    }
                } else {
                    this.epochs[handle] = this.epoch;
                    this.priorityByHandle[handle] = priority;
                    this.slotByHandle[handle] = this.count;
                    this.handles[this.count] = handle;
                    this.priorities[this.count] = priority;
                    this.count++;
                }
            }
            return this;
        }

        private boolean contains(int handle) {
            return handle >= 0 && handle < this.epochs.length
                    && this.epochs[handle] == this.epoch;
        }
    }

    private static int powerOfEight(int exponent) {
        int value = 1;
        for (int index = 0; index < exponent; index++) value = Math.multiplyExact(value, 8);
        return value;
    }
}
