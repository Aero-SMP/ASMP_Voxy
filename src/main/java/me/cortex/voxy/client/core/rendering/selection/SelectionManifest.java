package me.cortex.voxy.client.core.rendering.selection;

import me.cortex.voxy.client.core.rendering.SectionKey;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Consumer-owned selection snapshot over immutable structural topology.
 *
 * <p>Topology is rebuilt only when the manifest handle namespace changes. Frequently changing
 * residency, compatibility, in-flight, and renderable state lives in pooled primitive arrays.
 * Every asynchronous holder retains the snapshot and releases it when finished, so storage is
 * never overwritten while a GPU result or state-thread batch still refers to it.</p>
 */
public final class SelectionManifest implements AutoCloseable {
    public static final int MICROTILE_COUNT = 64;
    public static final int NO_HANDLE = -1;
    public static final int MAX_NODES = 262_144;
    public static final int MAX_DEPENDENCIES_PER_CONTENT = 0xffff;
    private static final int CONTENT_CLASSES = 3;
    private static final int MASKS_PER_CONTENT = 6;

    public enum ContentClass { EXTERIOR, INTERIOR, COMPLEX }

    /** Recycles only primitive dynamic state. Immutable topology is shared across snapshots. */
    public static final class Pool {
        private final int maximum;
        private final ArrayDeque<Storage> available = new ArrayDeque<>();
        private int created;

        public Pool(int maximum) {
            if (maximum < 2) throw new IllegalArgumentException("manifest pool is too small");
            this.maximum = maximum;
        }

        public synchronized SelectionManifest acquire(
                Topology topology, long generation, long snapshotId, long authorityId,
                long planRevision, long cameraVisibilityDomain) {
            Storage storage = this.available.pollFirst();
            if (storage == null) {
                if (this.created >= this.maximum) return null;
                storage = new Storage();
                this.created++;
            }
            storage.ensure(topology.nodes.length * CONTENT_CLASSES * MASKS_PER_CONTENT,
                    topology.dependencyStates, topology.neighborStates);
            return new SelectionManifest(this, storage, topology, generation, snapshotId,
                    authorityId, planRevision, cameraVisibilityDomain);
        }

        private synchronized void release(Storage storage) {
            // The builder overwrites every live range before seal. Retained capacity outside the
            // next topology is unreachable, so clearing whole historical arrays here only turns
            // bounded reuse into capacity-proportional memory traffic.
            this.available.addFirst(storage);
        }
    }

    /** Immutable, validated node topology and content-state layout. */
    public static final class Topology {
        private final Node[] nodes;
        private final int[] nodeIndexByHandle;
        private final int dependencyStates;
        private final int neighborStates;

        public Topology(Node[] nodes, int dependencyStates, int neighborStates) {
            Objects.requireNonNull(nodes, "nodes");
            if (nodes.length > MAX_NODES || dependencyStates < 0 || neighborStates < 0) {
                throw new IllegalArgumentException("invalid selection topology bounds");
            }
            this.nodes = nodes;
            this.dependencyStates = dependencyStates;
            this.neighborStates = neighborStates;

            int maximumHandle = -1;
            for (Node node : nodes) {
                Objects.requireNonNull(node, "node");
                if (node.handle < 0 || node.handle >= MAX_NODES) {
                    throw new IllegalArgumentException("selection node handle exceeds its bound");
                }
                maximumHandle = Math.max(maximumHandle, node.handle);
                validateContent(node.exterior, dependencyStates, neighborStates);
                validateContent(node.interior, dependencyStates, neighborStates);
                validateContent(node.complex, dependencyStates, neighborStates);
            }
            this.nodeIndexByHandle = new int[maximumHandle + 1];
            Arrays.fill(this.nodeIndexByHandle, NO_HANDLE);
            for (int index = 0; index < nodes.length; index++) {
                Node node = nodes[index];
                if (this.nodeIndexByHandle[node.handle] != NO_HANDLE) {
                    throw new IllegalArgumentException("duplicate selection node handle");
                }
                this.nodeIndexByHandle[node.handle] = index;
            }
            for (Node node : nodes) validateLinks(node);
        }

        private void validateLinks(Node node) {
            int nodeLod = SectionKey.level(node.sectionKey);
            if (node.parentHandle != NO_HANDLE) {
                Node parent = nodeForHandle(node.parentHandle);
                if (parent == null || SectionKey.level(parent.sectionKey) != nodeLod + 1) {
                    throw new IllegalArgumentException("selection node has an invalid parent");
                }
                boolean linked = false;
                for (int child : parent.childHandles) linked |= child == node.handle;
                if (!linked) throw new IllegalArgumentException("selection parent link disagrees");
            }
            int linkedMask = 0;
            for (int childIndex = 0; childIndex < node.childHandles.length; childIndex++) {
                int handle = node.childHandles[childIndex];
                if (handle == NO_HANDLE) continue;
                Node child = nodeForHandle(handle);
                if (child == null || child.parentHandle != node.handle
                        || SectionKey.level(child.sectionKey) + 1 != nodeLod
                        || (node.manifestedChildMask & 1 << childIndex) == 0) {
                    throw new IllegalArgumentException("selection child link disagrees");
                }
                linkedMask |= 1 << childIndex;
            }
            if ((linkedMask & ~node.manifestedChildMask) != 0) {
                throw new IllegalArgumentException("linked child is not manifested");
            }
        }

        private Node nodeForHandle(int handle) {
            int index = handle < 0 || handle >= this.nodeIndexByHandle.length
                    ? NO_HANDLE : this.nodeIndexByHandle[handle];
            return index == NO_HANDLE ? null : this.nodes[index];
        }

        private static void validateContent(ContentLayout content,
                                            int dependencyStates, int neighborStates) {
            Objects.requireNonNull(content, "content");
            if (content.objectCount != Long.bitCount(content.declaredMask)
                    || content.dependencyCount > MAX_DEPENDENCIES_PER_CONTENT
                    || content.neighborDependencySources.length > MAX_DEPENDENCIES_PER_CONTENT
                    || content.dependencyStateOffset < 0
                    || content.dependencyStateOffset + content.dependencyCount
                    > dependencyStates
                    || content.neighborStateOffset < 0
                    || content.neighborStateOffset + content.neighborDependencySources.length
                    > neighborStates) {
                throw new IllegalArgumentException("invalid selection content layout");
            }
            for (int source : content.neighborDependencySources) {
                if (source < 0 || source >= MICROTILE_COUNT
                        || (content.declaredMask & 1L << source) == 0) {
                    throw new IllegalArgumentException("invalid neighbor dependency source");
                }
            }
        }

        public int nodeCount() { return this.nodes.length; }
        public Node nodeAt(int index) { return this.nodes[index]; }
        public int nodeHandleCapacity() { return this.nodeIndexByHandle.length; }
        public int indexForHandle(int handle) {
            return handle < 0 || handle >= this.nodeIndexByHandle.length
                    ? NO_HANDLE : this.nodeIndexByHandle[handle];
        }
    }

    /** Unsigned node-relative bounds; zero through 65535 span the structural node. */
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

    /** Immutable object/dependency layout for one node content class. */
    public static final class ContentLayout {
        private final long declaredMask;
        private final int objectCount;
        private final int dependencyCount;
        private final int[] neighborDependencySources;
        private final int dependencyStateOffset;
        private final int neighborStateOffset;
        private final int boundaryFaceMask;
        private final long estimatedCanonicalBytes;
        private final long estimatedGeometryBytes;
        private final long estimatedCompletionMicros;

        public ContentLayout(long declaredMask, int objectCount, int dependencyCount,
                             int[] neighborDependencySources, int dependencyStateOffset,
                             int neighborStateOffset, int boundaryFaceMask,
                             long estimatedCanonicalBytes, long estimatedGeometryBytes,
                             long estimatedCompletionMicros) {
            this.declaredMask = declaredMask;
            this.objectCount = objectCount;
            this.dependencyCount = dependencyCount;
            this.neighborDependencySources = Objects.requireNonNull(neighborDependencySources,
                    "neighbor sources");
            this.dependencyStateOffset = dependencyStateOffset;
            this.neighborStateOffset = neighborStateOffset;
            this.boundaryFaceMask = boundaryFaceMask;
            this.estimatedCanonicalBytes = estimatedCanonicalBytes;
            this.estimatedGeometryBytes = estimatedGeometryBytes;
            this.estimatedCompletionMicros = estimatedCompletionMicros;
            if (objectCount < 0 || dependencyCount < 0
                    || (boundaryFaceMask & ~0x3f) != 0 || estimatedCanonicalBytes < 0
                    || estimatedGeometryBytes < 0 || estimatedCompletionMicros < 0) {
                throw new IllegalArgumentException("invalid content layout metadata");
            }
        }

        public long declaredMask() { return this.declaredMask; }
        public int[] neighborDependencySourcesInternal() { return this.neighborDependencySources; }
        public int objectCount() { return this.objectCount; }
        public int dependencyCount() { return this.dependencyCount; }
        public int neighborDependencyCount() { return this.neighborDependencySources.length; }
        public int boundaryFaceMask() { return this.boundaryFaceMask; }
        public long estimatedCanonicalBytes() { return this.estimatedCanonicalBytes; }
        public long estimatedGeometryBytes() { return this.estimatedGeometryBytes; }
        public long estimatedCompletionMicros() { return this.estimatedCompletionMicros; }
    }

    /** One immutable structural node shared by all state snapshots in its handle namespace. */
    public static final class Node {
        private final int handle;
        private final long sectionKey;
        private final int parentHandle;
        private final int manifestedChildMask;
        private final int[] childHandles;
        private final TightBounds tightBounds;
        private final long geometricErrorQ16;
        private final boolean descriptorReady;
        private final ContentLayout exterior;
        private final ContentLayout interior;
        private final ContentLayout complex;

        public Node(int handle, long sectionKey, int parentHandle, int manifestedChildMask,
                    int[] childHandles, TightBounds tightBounds, long geometricErrorQ16,
                    boolean descriptorReady, ContentLayout exterior,
                    ContentLayout interior, ContentLayout complex) {
            if (handle < 0 || parentHandle < NO_HANDLE || (manifestedChildMask & ~0xff) != 0
                    || childHandles == null || childHandles.length != 8
                    || geometricErrorQ16 < 0 || geometricErrorQ16 > 0xffff_ffffL) {
                throw new IllegalArgumentException("invalid selection node");
            }
            this.handle = handle;
            this.sectionKey = sectionKey;
            this.parentHandle = parentHandle;
            this.manifestedChildMask = manifestedChildMask;
            this.childHandles = childHandles;
            this.tightBounds = Objects.requireNonNull(tightBounds, "tight bounds");
            this.geometricErrorQ16 = geometricErrorQ16;
            this.descriptorReady = descriptorReady;
            this.exterior = Objects.requireNonNull(exterior, "exterior");
            this.interior = Objects.requireNonNull(interior, "interior");
            this.complex = Objects.requireNonNull(complex, "complex");
        }

        public int handle() { return this.handle; }
        public long sectionKey() { return this.sectionKey; }
        public int parentHandle() { return this.parentHandle; }
        public int manifestedChildMask() { return this.manifestedChildMask; }
        public int[] childHandlesInternal() { return this.childHandles; }
        public TightBounds tightBounds() { return this.tightBounds; }
        public long geometricErrorQ16() { return this.geometricErrorQ16; }
        public boolean descriptorReady() { return this.descriptorReady; }

        public ContentLayout layout(ContentClass contentClass) {
            return switch (Objects.requireNonNull(contentClass, "content class")) {
                case EXTERIOR -> this.exterior;
                case INTERIOR -> this.interior;
                case COMPLEX -> this.complex;
            };
        }
    }

    private static final class Storage {
        private long[] masks = new long[0];
        private byte[] dependencies = new byte[0];
        private byte[] neighbors = new byte[0];

        private void ensure(int maskCount, int dependencyCount, int neighborCount) {
            if (this.masks.length < maskCount) this.masks = new long[grow(maskCount)];
            if (this.dependencies.length < dependencyCount) {
                this.dependencies = new byte[grow(dependencyCount)];
            }
            if (this.neighbors.length < neighborCount) {
                this.neighbors = new byte[grow(neighborCount)];
            }
        }

        private static int grow(int required) {
            if (required == 0) return 0;
            int value = Integer.highestOneBit(required - 1) << 1;
            return value > 0 ? value : required;
        }
    }

    private final Pool owner;
    private final Storage storage;
    private final Topology topology;
    private final long generation;
    private final long snapshotId;
    private final long authorityId;
    private final long planRevision;
    private final long cameraVisibilityDomain;
    private final AtomicInteger references = new AtomicInteger(1);
    private boolean sealed;

    private SelectionManifest(Pool owner, Storage storage, Topology topology, long generation,
                              long snapshotId, long authorityId, long planRevision,
                              long cameraVisibilityDomain) {
        this.owner = owner;
        this.storage = storage;
        this.topology = topology;
        this.generation = generation;
        this.snapshotId = snapshotId;
        this.authorityId = authorityId;
        this.planRevision = planRevision;
        this.cameraVisibilityDomain = cameraVisibilityDomain;
    }

    public static SelectionManifest empty(long generation, long snapshotId) {
        Topology topology = new Topology(new Node[0], 0, 0);
        return new SelectionManifest(null, new Storage(), topology, generation, snapshotId,
                0, 0, 0).seal();
    }

    public SelectionManifest retain() {
        int old = this.references.getAndIncrement();
        if (old <= 0) {
            this.references.decrementAndGet();
            throw new IllegalStateException("selection manifest has been released");
        }
        return this;
    }

    @Override
    public void close() {
        int remaining = this.references.decrementAndGet();
        if (remaining < 0) throw new IllegalStateException("selection manifest released twice");
        if (remaining == 0 && this.owner != null) this.owner.release(this.storage);
    }

    public SelectionManifest seal() {
        this.sealed = true;
        return this;
    }

    public void setContentState(int nodeIndex, ContentClass contentClass,
                                long available, long resident, long renderable, long inFlight,
                                long coverageAvailable, long coverageRenderable) {
        ensureWritable();
        ContentLayout layout = contentLayout(nodeIndex, contentClass);
        if ((coverageAvailable & ~layout.declaredMask) != 0
                || (available & ~coverageAvailable) != 0
                || (resident & ~available) != 0 || (renderable & ~resident) != 0
                || (inFlight & ~available) != 0
                || (coverageRenderable & ~coverageAvailable) != 0) {
            throw new IllegalArgumentException("invalid dynamic content masks");
        }
        int offset = contentOffset(nodeIndex, contentClass);
        this.storage.masks[offset] = available;
        this.storage.masks[offset + 1] = resident;
        this.storage.masks[offset + 2] = renderable;
        this.storage.masks[offset + 3] = inFlight;
        this.storage.masks[offset + 4] = coverageAvailable;
        this.storage.masks[offset + 5] = coverageRenderable;
    }

    public void setDependencyState(int nodeIndex, ContentClass contentClass, int index,
                                   boolean resident, boolean inFlight) {
        ensureWritable();
        ContentLayout layout = contentLayout(nodeIndex, contentClass);
        if (index < 0 || index >= layout.dependencyCount) {
            throw new IndexOutOfBoundsException(index);
        }
        this.storage.dependencies[layout.dependencyStateOffset + index] = state(resident, inFlight);
    }

    public void setNeighborState(int nodeIndex, ContentClass contentClass, int index,
                                 boolean resident, boolean inFlight) {
        ensureWritable();
        ContentLayout layout = contentLayout(nodeIndex, contentClass);
        if (index < 0 || index >= layout.neighborDependencySources.length) {
            throw new IndexOutOfBoundsException(index);
        }
        this.storage.neighbors[layout.neighborStateOffset + index] = state(resident, inFlight);
    }

    private static byte state(boolean resident, boolean inFlight) {
        return (byte) ((resident ? 1 : 0) | (inFlight ? 2 : 0));
    }

    private void ensureWritable() {
        if (this.sealed) throw new IllegalStateException("selection manifest is sealed");
    }

    public long generation() { return this.generation; }
    public long snapshotId() { return this.snapshotId; }
    public long authorityId() { return this.authorityId; }
    public long planRevision() { return this.planRevision; }
    public long cameraVisibilityDomain() { return this.cameraVisibilityDomain; }
    public int nodeCount() { return this.topology.nodeCount(); }
    public Node nodeAt(int index) { return this.topology.nodeAt(index); }
    public int nodeHandleCapacity() { return this.topology.nodeHandleCapacity(); }
    public int indexForHandle(int handle) { return this.topology.indexForHandle(handle); }
    public Node nodeForHandle(int handle) {
        int index = indexForHandle(handle);
        return index == NO_HANDLE ? null : nodeAt(index);
    }

    public ContentLayout contentLayout(int nodeIndex, ContentClass contentClass) {
        return nodeAt(nodeIndex).layout(contentClass);
    }

    public long availableMask(int nodeIndex, ContentClass contentClass) {
        return this.storage.masks[contentOffset(nodeIndex, contentClass)];
    }

    public long residentMask(int nodeIndex, ContentClass contentClass) {
        return this.storage.masks[contentOffset(nodeIndex, contentClass) + 1];
    }

    public long renderableMask(int nodeIndex, ContentClass contentClass) {
        return this.storage.masks[contentOffset(nodeIndex, contentClass) + 2];
    }

    public long inFlightMask(int nodeIndex, ContentClass contentClass) {
        return this.storage.masks[contentOffset(nodeIndex, contentClass) + 3];
    }

    /** Compatibility-filtered content before the current camera visibility domain is applied. */
    public long coverageAvailableMask(int nodeIndex, ContentClass contentClass) {
        return this.storage.masks[contentOffset(nodeIndex, contentClass) + 4];
    }

    /** Renderer-visible content independent of the current camera visibility domain. */
    public long coverageRenderableMask(int nodeIndex, ContentClass contentClass) {
        return this.storage.masks[contentOffset(nodeIndex, contentClass) + 5];
    }

    public boolean dependencyResident(int nodeIndex, ContentClass contentClass, int index) {
        ContentLayout layout = contentLayout(nodeIndex, contentClass);
        return (this.storage.dependencies[layout.dependencyStateOffset + index] & 1) != 0;
    }

    public boolean dependencyInFlight(int nodeIndex, ContentClass contentClass, int index) {
        ContentLayout layout = contentLayout(nodeIndex, contentClass);
        return (this.storage.dependencies[layout.dependencyStateOffset + index] & 2) != 0;
    }

    public boolean neighborResident(int nodeIndex, ContentClass contentClass, int index) {
        ContentLayout layout = contentLayout(nodeIndex, contentClass);
        return (this.storage.neighbors[layout.neighborStateOffset + index] & 1) != 0;
    }

    public boolean neighborInFlight(int nodeIndex, ContentClass contentClass, int index) {
        ContentLayout layout = contentLayout(nodeIndex, contentClass);
        return (this.storage.neighbors[layout.neighborStateOffset + index] & 2) != 0;
    }

    private int contentOffset(int nodeIndex, ContentClass contentClass) {
        return (nodeIndex * CONTENT_CLASSES + contentClass.ordinal()) * MASKS_PER_CONTENT;
    }
}
