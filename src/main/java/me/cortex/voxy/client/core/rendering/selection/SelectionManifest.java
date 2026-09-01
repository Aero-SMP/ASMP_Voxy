package me.cortex.voxy.client.core.rendering.selection;

import java.util.BitSet;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable renderer snapshot of one manifested Virtual Surface root.
 *
 * <p>Object and dependency handles are opaque, non-negative indices owned by the content
 * table.  The renderer never interprets them; it returns the exact handles selected by the GPU
 * cut.  All three final content classes use a fixed 8-cubed layout and are activated together.
 */
public final class SelectionManifest {
    public static final int MICROTILE_COUNT = 64;
    public static final int NO_HANDLE = -1;
    public static final int MAX_NODES = 262_144;
    public static final int MAX_OBJECT_HANDLES = 262_144;
    public static final int MAX_DEPENDENCIES_PER_CONTENT = 0xffff;

    private final long generation;
    private final long snapshotId;
    private final long cameraVisibilityDomain;
    private final List<Node> nodes;
    private final Node[] nodesByIndex;
    private final int[] nodeIndexByHandle;
    private final int objectHandleCapacity;

    public SelectionManifest(long generation, long snapshotId, long cameraVisibilityDomain,
                             List<Node> nodes) {
        Objects.requireNonNull(nodes, "nodes");
        if (nodes.size() > MAX_NODES) {
            throw new IllegalArgumentException("selection manifest exceeds the node bound");
        }
        this.generation = generation;
        this.snapshotId = snapshotId;
        this.cameraVisibilityDomain = cameraVisibilityDomain;
        this.nodes = List.copyOf(nodes);
        this.nodesByIndex = this.nodes.toArray(Node[]::new);

        int maximumHandle = -1;
        int maximumObjectHandle = -1;
        for (Node node : this.nodesByIndex) {
            Objects.requireNonNull(node, "node");
            if (node.handle() >= MAX_NODES) {
                throw new IllegalArgumentException("selection node handle exceeds its bound");
            }
            maximumHandle = Math.max(maximumHandle, node.handle());
            maximumObjectHandle = Math.max(maximumObjectHandle, maximumObjectHandle(node));
        }
        this.nodeIndexByHandle = new int[maximumHandle + 1];
        java.util.Arrays.fill(this.nodeIndexByHandle, NO_HANDLE);
        for (int index = 0; index < this.nodesByIndex.length; index++) {
            Node node = this.nodesByIndex[index];
            if (this.nodeIndexByHandle[node.handle()] != NO_HANDLE) {
                throw new IllegalArgumentException("duplicate selection node handle "
                        + node.handle());
            }
            this.nodeIndexByHandle[node.handle()] = index;
        }
        this.objectHandleCapacity = maximumObjectHandle + 1;
        for (Node node : this.nodesByIndex) {
            if (node.parentHandle() != NO_HANDLE && indexForHandle(node.parentHandle()) < 0) {
                throw new IllegalArgumentException("selection node has an unknown parent");
            }
            for (int child : node.childHandles()) {
                if (child == NO_HANDLE) continue;
                Node childNode = nodeForHandle(child);
                if (childNode == null || childNode.parentHandle() != node.handle()) {
                    throw new IllegalArgumentException(
                            "selection child and parent links are inconsistent");
                }
            }
            if (node.parentHandle() != NO_HANDLE) {
                boolean linked = false;
                for (int child : nodeForHandle(node.parentHandle()).childHandlesInternal()) {
                    linked |= child == node.handle();
                }
                if (!linked) {
                    throw new IllegalArgumentException(
                            "selection parent does not list one of its children");
                }
            }
        }
        // Parent-chain validation is deliberately bounded.  Besides rejecting cycles, this
        // bounds the per-invocation ancestor walk in the production compute shader.
        for (Node node : this.nodesByIndex) {
            Set<Integer> path = new HashSet<>();
            Node cursor = node;
            int depth = 0;
            while (cursor.parentHandle() != NO_HANDLE) {
                if (!path.add(cursor.handle())) {
                    throw new IllegalArgumentException("selection manifest contains a cycle");
                }
                if (++depth > 32) {
                    throw new IllegalArgumentException("selection manifest exceeds depth 32");
                }
                cursor = nodeForHandle(cursor.parentHandle());
            }
        }
    }

    public long generation() { return this.generation; }
    public long snapshotId() { return this.snapshotId; }
    public long cameraVisibilityDomain() { return this.cameraVisibilityDomain; }
    public List<Node> nodes() { return this.nodes; }
    public Node nodeAt(int index) { return this.nodesByIndex[index]; }
    public int nodeCount() { return this.nodesByIndex.length; }
    public int nodeHandleCapacity() { return this.nodeIndexByHandle.length; }
    public int objectHandleCapacity() { return this.objectHandleCapacity; }

    public int indexForHandle(int handle) {
        return handle < 0 || handle >= this.nodeIndexByHandle.length
                ? NO_HANDLE : this.nodeIndexByHandle[handle];
    }

    public Node nodeForHandle(int handle) {
        int index = indexForHandle(handle);
        return index == NO_HANDLE ? null : this.nodesByIndex[index];
    }

    public enum ContentClass {
        EXTERIOR,
        INTERIOR,
        COMPLEX
    }

    /** Unsigned, node-relative bounds.  Zero through 65535 span the structural node. */
    public record TightBounds(int minX, int minY, int minZ,
                              int maxX, int maxY, int maxZ) {
        public TightBounds {
            if ((minX | minY | minZ | maxX | maxY | maxZ) < 0
                    || minX > 0xffff || minY > 0xffff || minZ > 0xffff
                    || maxX > 0xffff || maxY > 0xffff || maxZ > 0xffff
                    || minX > maxX || minY > maxY || minZ > maxZ) {
                throw new IllegalArgumentException("invalid selection tight bounds");
            }
        }
    }

    /**
     * One final 8-cubed content class and its captured object-table state.
     *
     * <p>{@code objectHandles} is dense in ascending microtile-bit order.  Dependency bit sets
     * index their respective handle arrays.  Residency is captured atomically with the manifest
     * snapshot; the receiver must still recheck it before issuing a request or activation.</p>
     */
    public record ContentState(long availableMask, int[] objectHandles,
                               long residentMask, long renderableMask, long inFlightMask,
                               int[] dependencyHandles, BitSet residentDependencies,
                               BitSet inFlightDependencies,
                               int[] neighborDependencyHandles,
                               int[] neighborDependencySources,
                               BitSet residentNeighborDependencies,
                               BitSet inFlightNeighborDependencies,
                               int boundaryFaceMask, long estimatedCanonicalBytes,
                               long estimatedGeometryBytes, long estimatedCompletionMicros) {
        public ContentState {
            objectHandles = copyHandles(objectHandles, "object handles", MICROTILE_COUNT, true);
            dependencyHandles = copyHandles(dependencyHandles, "dependency handles",
                    MAX_DEPENDENCIES_PER_CONTENT, false);
            neighborDependencyHandles = copyHandles(neighborDependencyHandles,
                    "neighbor dependency handles", MAX_DEPENDENCIES_PER_CONTENT, true);
            neighborDependencySources = Objects.requireNonNull(
                    neighborDependencySources, "neighbor dependency sources").clone();
            if (neighborDependencySources.length != neighborDependencyHandles.length) {
                throw new IllegalArgumentException(
                        "neighbor dependency handles and source microtiles disagree");
            }
            for (int source : neighborDependencySources) {
                if (source < 0 || source >= MICROTILE_COUNT
                        || (availableMask & 1L << source) == 0) {
                    throw new IllegalArgumentException(
                            "neighbor dependency source is outside content availability");
                }
            }
            residentDependencies = copyBits(residentDependencies, dependencyHandles.length,
                    "resident dependencies");
            inFlightDependencies = copyBits(inFlightDependencies, dependencyHandles.length,
                    "in-flight dependencies");
            residentNeighborDependencies = copyBits(residentNeighborDependencies,
                    neighborDependencyHandles.length, "resident neighbor dependencies");
            inFlightNeighborDependencies = copyBits(inFlightNeighborDependencies,
                    neighborDependencyHandles.length, "in-flight neighbor dependencies");
            if (objectHandles.length != Long.bitCount(availableMask)) {
                throw new IllegalArgumentException(
                        "8-cubed availability and dense object handles disagree");
            }
            if ((residentMask & ~availableMask) != 0
                    || (renderableMask & ~residentMask) != 0
                    || (inFlightMask & ~availableMask) != 0) {
                throw new IllegalArgumentException("invalid content residency masks");
            }
            if ((boundaryFaceMask & ~0x3f) != 0) {
                throw new IllegalArgumentException("boundary face mask is not six bits");
            }
            if (estimatedCanonicalBytes < 0 || estimatedGeometryBytes < 0
                    || estimatedCompletionMicros < 0) {
                throw new IllegalArgumentException("negative selection content estimate");
            }
        }

        @Override
        public int[] objectHandles() {
            return this.objectHandles.clone();
        }

        @Override
        public int[] dependencyHandles() {
            return this.dependencyHandles.clone();
        }

        @Override
        public BitSet residentDependencies() {
            return (BitSet) this.residentDependencies.clone();
        }

        @Override
        public BitSet inFlightDependencies() {
            return (BitSet) this.inFlightDependencies.clone();
        }

        @Override
        public int[] neighborDependencyHandles() {
            return this.neighborDependencyHandles.clone();
        }

        @Override
        public int[] neighborDependencySources() {
            return this.neighborDependencySources.clone();
        }

        @Override
        public BitSet residentNeighborDependencies() {
            return (BitSet) this.residentNeighborDependencies.clone();
        }

        @Override
        public BitSet inFlightNeighborDependencies() {
            return (BitSet) this.inFlightNeighborDependencies.clone();
        }

        public int[] objectHandlesInternal() {
            return this.objectHandles;
        }

        public int[] dependencyHandlesInternal() {
            return this.dependencyHandles;
        }

        public BitSet residentDependenciesInternal() {
            return this.residentDependencies;
        }

        public BitSet inFlightDependenciesInternal() {
            return this.inFlightDependencies;
        }

        public int[] neighborDependencyHandlesInternal() {
            return this.neighborDependencyHandles;
        }

        public int[] neighborDependencySourcesInternal() {
            return this.neighborDependencySources;
        }

        public BitSet residentNeighborDependenciesInternal() {
            return this.residentNeighborDependencies;
        }

        public BitSet inFlightNeighborDependenciesInternal() {
            return this.inFlightNeighborDependencies;
        }

        private static int[] copyHandles(int[] handles, String name, int maximum,
                                         boolean allowDuplicates) {
            Objects.requireNonNull(handles, name);
            if (handles.length > maximum) {
                throw new IllegalArgumentException(name + " exceeds its bound");
            }
            int[] copy = handles.clone();
            Set<Integer> unique = new HashSet<>(copy.length);
            for (int handle : copy) {
                if (handle < 0 || handle >= MAX_OBJECT_HANDLES
                        || (!allowDuplicates && !unique.add(handle))) {
                    throw new IllegalArgumentException(name
                            + " contains a negative or duplicate handle");
                }
            }
            return copy;
        }

        private static BitSet copyBits(BitSet bits, int limit, String name) {
            Objects.requireNonNull(bits, name);
            BitSet copy = (BitSet) bits.clone();
            if (copy.length() > limit) {
                throw new IllegalArgumentException(name + " exceeds its handle array");
            }
            return copy;
        }
    }

    /** One node in an atomically complete five-level structural manifest. */
    public record Node(int handle, long sectionKey, int parentHandle,
                       int manifestedChildMask, int[] childHandles,
                       TightBounds tightBounds, long geometricErrorQ16, boolean descriptorReady,
                       ContentState exterior, ContentState interior, ContentState complex) {
        public Node {
            if (handle < 0 || parentHandle < NO_HANDLE
                    || (manifestedChildMask & ~0xff) != 0) {
                throw new IllegalArgumentException("invalid selection node handle");
            }
            Objects.requireNonNull(childHandles, "childHandles");
            if (childHandles.length != 8) {
                throw new IllegalArgumentException("selection nodes require eight child slots");
            }
            childHandles = childHandles.clone();
            Set<Integer> uniqueChildren = new HashSet<>();
            int linkedChildMask = 0;
            for (int childIndex = 0; childIndex < childHandles.length; childIndex++) {
                int child = childHandles[childIndex];
                if (child < NO_HANDLE || child == handle
                        || child != NO_HANDLE && ((manifestedChildMask & 1 << childIndex) == 0
                        || !uniqueChildren.add(child))) {
                    throw new IllegalArgumentException("invalid selection child handle");
                }
                if (child != NO_HANDLE) linkedChildMask |= 1 << childIndex;
            }
            if (linkedChildMask != manifestedChildMask) {
                throw new IllegalArgumentException(
                        "selection node does not contain its complete declared child set");
            }
            if (geometricErrorQ16 < 0 || geometricErrorQ16 > 0xffff_ffffL) {
                throw new IllegalArgumentException("geometric error is not unsigned Q16.16");
            }
            exterior = Objects.requireNonNull(exterior, "exterior");
            interior = Objects.requireNonNull(interior, "interior");
            complex = Objects.requireNonNull(complex, "complex");
        }

        @Override
        public int[] childHandles() {
            return this.childHandles.clone();
        }

        public int[] childHandlesInternal() {
            return this.childHandles;
        }

        public ContentState content(ContentClass contentClass) {
            return switch (Objects.requireNonNull(contentClass, "contentClass")) {
                case EXTERIOR -> this.exterior;
                case INTERIOR -> this.interior;
                case COMPLEX -> this.complex;
            };
        }
    }

    private static int maximumObjectHandle(Node node) {
        int maximum = -1;
        for (ContentClass contentClass : ContentClass.values()) {
            ContentState state = node.content(contentClass);
            maximum = maximum(maximum, state.objectHandlesInternal());
            maximum = maximum(maximum, state.dependencyHandlesInternal());
            maximum = maximum(maximum, state.neighborDependencyHandlesInternal());
        }
        return maximum;
    }

    private static int maximum(int maximum, int[] handles) {
        for (int handle : handles) maximum = Math.max(maximum, handle);
        return maximum;
    }
}
