package me.cortex.voxy.client.core.rendering.hierarchical;

import it.unimi.dsi.fastutil.ints.IntConsumer;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import me.cortex.voxy.client.core.rendering.building.BuiltSection;
import me.cortex.voxy.client.core.rendering.section.BasicAsyncGeometryManager;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.HierarchicalBitSet;
import me.cortex.voxy.client.core.rendering.SectionKey;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static me.cortex.voxy.client.core.rendering.SectionKey.MAX_LOD_LAYER;


public class NodeManager {
    // Leaf nodes own geometry (the empty sentinel is valid) and may advertise future children.
    // Inner nodes own a compact child allocation matching their active child positions.
    // All non-top-level nodes have exactly one hierarchy parent.

    public static final int NULL_GEOMETRY_ID = -1;
    public static final int EMPTY_GEOMETRY_ID = -2;
    public static final int NULL_REQUEST_ID = NodeStore.REQUEST_ID_MSK;
    public static final int SENTINEL_EMPTY_CHILD_PTR = NodeStore.NODE_ID_MSK-1;

    public static final int NODE_ID_MSK = ((1<<24)-1);
    private static final int NODE_TYPE_MSK = 0b11<<30;
    private static final int NODE_TYPE_LEAF = 0b00<<30;
    private static final int NODE_TYPE_INNER = 0b01<<30;
    private static final int NODE_TYPE_REQUEST = 0b10<<30;

    private static final int REQUEST_TYPE_SINGLE = 0b0<<29;
    private static final int REQUEST_TYPE_CHILD = 0b1<<29;
    private static final int REQUEST_TYPE_MSK = 0b1<<29;

    private final RequestPool topLevelRequests = new RequestPool();
    private final RequestPool childRequests = new RequestPool();
    private final IntOpenHashSet nodeUpdates = new IntOpenHashSet();
    private final BasicAsyncGeometryManager geometryManager;
    private final LongOpenHashSet watched = new LongOpenHashSet();
    private final Long2IntOpenHashMap activeSectionMap = new Long2IntOpenHashMap();
    private final NodeStore nodeData;
    public final int maxNodeCount;
    private final IntOpenHashSet topLevelNodeIds = new IntOpenHashSet();
    private final LongOpenHashSet topLevelNodes = new LongOpenHashSet();
    private final Map<StagedGeometryKey, StagedGeometry> stagedGeometry = new HashMap<>();
    private final Map<StagedGeometryKey, CommittedGeometry> committedGeometry = new HashMap<>();
    private final Map<Long, StagedGeometryKey> committedPositions = new HashMap<>();
    /** Candidate allocations detached by rollback but retained until its GPU pointer fence. */
    private final Map<StagedGeometryKey, Integer> rolledBackGeometry = new HashMap<>();
    /** Detached subtree geometry retained until the parent-leaf update crosses a GPU fence. */
    private final Map<Long, CoarsenedGeometry> coarsenedGeometry = new HashMap<>();

    public record RendererFence(long position, long sourceRevision, long geometryBytes) {}
    private record StagedGeometryKey(long sourceRevision, long position) {}
    private record StagedGeometry(int geometryId, byte childExistence, long geometryBytes) {}
    private record CoarsenedGeometry(long parent, IntArrayList geometryIds) {}

    private static final class RequestPool {
        private final HierarchicalBitSet allocated = new HierarchicalBitSet(NodeStore.REQUEST_ID_MSK);
        private NodeRequest[] requests = new NodeRequest[16];

        int put(NodeRequest request) {
            int id = this.allocated.allocateNext();
            if (id < 0) throw new IllegalStateException("Id over max request capacity");
            if (id >= this.requests.length) {
                int growth = (this.requests.length * 3 + 3) / 4;
                this.requests = Arrays.copyOf(this.requests, this.requests.length + growth);
            }
            this.requests[id] = request;
            return id;
        }

        void release(int id) {
            if (!this.allocated.free(id)) {
                throw new IllegalArgumentException("Index " + id + " was already released");
            }
            this.requests[id] = null;
        }

        NodeRequest get(int id) {
            if (!this.allocated.isSet(id)) {
                throw new IllegalArgumentException("Index " + id + " is not allocated");
            }
            return this.requests[id];
        }
    }

    private IntConsumer topLevelNodeIdAddedCallback;
    private IntConsumer topLevelNodeIdRemovedCallback;

    private IntConsumer clearAlloc;
    private IntConsumer clearFree;
    public void setClear(IntConsumer onAlloc, IntConsumer onFree) {
        this.clearAlloc = onAlloc;
        this.clearFree = onFree;
    }
    private void clearAllocId(int id) { if (this.clearAlloc != null) this.clearAlloc.accept(id); }
    private void clearFreeId(int id) { if (this.clearFree != null) this.clearFree.accept(id); }

    public void setTLNCallbacks(IntConsumer onAdd, IntConsumer onRemove) {
        this.topLevelNodeIdAddedCallback = onAdd;
        this.topLevelNodeIdRemovedCallback = onRemove;
    }

    public NodeManager(int maxNodeCount, BasicAsyncGeometryManager geometryManager) {
        if ((maxNodeCount&(maxNodeCount-1))!=0) {
            throw new IllegalArgumentException("Max node count must be a power of 2");
        }
        if (maxNodeCount>(1<<24)) {
            throw new IllegalArgumentException("Max node count cannot exceed 2^24");
        }
        this.activeSectionMap.defaultReturnValue(-1);
        this.maxNodeCount = maxNodeCount;
        this.nodeData = new NodeStore(maxNodeCount);
        this.geometryManager = geometryManager;
    }

    private static void assertPosValid(long pos) {
        int lvl = SectionKey.level(pos);
        int x = SectionKey.x(pos);
        int y = SectionKey.y(pos);
        int z = SectionKey.z(pos);
        if (SectionKey.pack(lvl, x, y, z) != pos) {
            throw new IllegalStateException("Reconstructed pos not same as original");
        }
        x <<= lvl;
        y <<= lvl;
        z <<= lvl;
        long p2 = SectionKey.pack(0, x, y, z);
        if (SectionKey.level(p2) != 0 || SectionKey.x(p2) != x || SectionKey.y(p2) != y || SectionKey.z(p2) != z) {
            throw new IllegalStateException("Position not valid at all levels: " + pos + "-"+SectionKey.describe(pos) + ":"+SectionKey.describe(p2));
        }
    }

    private void unwatch(long position) {
        if (!this.watched.remove(position)) {
            throw new IllegalStateException("Section position is not watched "
                    + SectionKey.describe(position));
        }
    }

    public void insertTopLevelNode(long pos) {
        //Verify that pos is actually valid
        assertPosValid(pos);

        if (this.activeSectionMap.containsKey(pos)) {
            Logger.error("Tried inserting top level pos " + SectionKey.describe(pos) + " but it was in active map, discarding!");
            return;
        }

        var request = new NodeRequest(pos);
        request.require(0);
        int id = this.topLevelRequests.put(request);
        this.watched.add(pos);
        this.activeSectionMap.put(pos, id|NODE_TYPE_REQUEST|REQUEST_TYPE_SINGLE);
        this.topLevelNodes.add(pos);
    }

    public void removeTopLevelNode(long pos) {
        if (!this.topLevelNodes.remove(pos)) {
            throw new IllegalStateException("Position not in top level map: " + SectionKey.describe(pos));
        }
        int nodeId = this.activeSectionMap.get(pos);
        if (nodeId == -1) {
            throw new IllegalStateException("Tried removing top level pos " + SectionKey.describe(pos) + " but it was not in active map, discarding!");
        }
        if ((nodeId&NODE_TYPE_MSK)!=NODE_TYPE_REQUEST) {
            int id = nodeId&NODE_ID_MSK;
            if (!this.topLevelNodeIds.remove(id)) {
                throw new IllegalStateException("Node id was not in top level node ids: " + nodeId + " pos: " + SectionKey.describe(pos));
            }
            if (this.topLevelNodeIdRemovedCallback != null)
                this.topLevelNodeIdRemovedCallback.accept(id);
        }
        //Remove the entire thing
        this.recurseRemoveNode(pos);
    }


    IntOpenHashSet getTopLevelNodeIds() {
        return this.topLevelNodeIds;
    }

    //==================================================================================================================

    RendererFence stageGeometryResult(BuiltSection sectionResult) {
        long pos = sectionResult.position;
        long sourceRevision = sectionResult.sourceRevision;
        long geometryBytes = sectionResult.geometryBuffer == null ? 0
                : sectionResult.geometryBuffer.size;
        if (this.activeSectionMap.get(pos) == -1) return null;
        StagedGeometryKey key = new StagedGeometryKey(sourceRevision, pos);
        if (this.committedGeometry.containsKey(key)) {
            sectionResult.free();
            return new RendererFence(pos, sourceRevision, geometryBytes);
        }
        StagedGeometry previous = this.stagedGeometry.remove(key);
        if (previous != null) removeGeometryIfAllocated(previous.geometryId);
        int geometryId;
        if (sectionResult.isEmpty()) {
            sectionResult.free();
            geometryId = EMPTY_GEOMETRY_ID;
        } else {
            geometryId = this.geometryManager.uploadSection(sectionResult);
        }
        this.stagedGeometry.put(key, new StagedGeometry(geometryId,
                sectionResult.childExistence, geometryBytes));
        return new RendererFence(pos, sourceRevision, geometryBytes);
    }

    /**
     * Creates every immediately possible request owner on the path to {@code position}.
     * Deeper owners remain pending until their nearest requested ancestor publishes geometry.
     */
    boolean ensureHierarchyOwner(long position) {
        assertPosValid(position);
        if (this.activeSectionMap.get(position) != -1) return true;
        long top = ancestorAtLevel(position, MAX_LOD_LAYER);
        if (!this.topLevelNodes.contains(top)) return false;
        for (int level = MAX_LOD_LAYER; level > SectionKey.level(position); level--) {
            long ancestor = ancestorAtLevel(position, level);
            if (this.activeSectionMap.get(ancestor) == -1) return false;
            this.processRequest(ancestor);
        }
        return this.activeSectionMap.get(position) != -1;
    }

    boolean hasTopLevelAncestor(long position) {
        assertPosValid(position);
        while (SectionKey.level(position) < MAX_LOD_LAYER) position = makeParentPos(position);
        return this.topLevelNodes.contains(position);
    }

    private static long ancestorAtLevel(long position, int level) {
        int shift = level - SectionKey.level(position);
        if (shift < 0) throw new IllegalArgumentException("ancestor level is below its node");
        return SectionKey.pack(level, SectionKey.x(position) >> shift,
                SectionKey.y(position) >> shift, SectionKey.z(position) >> shift);
    }

    /**
     * Attaches already-uploaded candidates and indexed child topology without freeing active
     * geometry until every key passes preflight. Request-to-node publication waits for finalize.
     */
    public void commitStagedRoot(long sourceRevision, Set<Long> positions) {
        Objects.requireNonNull(positions, "positions");
        ArrayList<CommitTarget> targets = new ArrayList<>(positions.size());
        HashSet<Long> unique = new HashSet<>();
        for (long position : positions) {
            if (!unique.add(position)) throw new IllegalArgumentException("duplicate staged position");
            StagedGeometryKey key = new StagedGeometryKey(sourceRevision, position);
            if (this.committedGeometry.containsKey(key)
                    || this.committedPositions.containsKey(position)) {
                throw new IllegalStateException("staged geometry is already committed");
            }
            StagedGeometry candidate = this.stagedGeometry.get(key);
            if (candidate == null) {
                throw new IllegalStateException("missing staged geometry for "
                        + SectionKey.describe(position));
            }
            int state = this.activeSectionMap.get(position);
            if (state == -1) {
                throw new IllegalStateException("staged geometry lost its hierarchy owner");
            }
            if ((state & NODE_TYPE_MSK) == NODE_TYPE_REQUEST) {
                NodeRequest request = this.request(state);
                int child = (state & REQUEST_TYPE_MSK) == REQUEST_TYPE_SINGLE
                        ? 0 : getChildIdx(position);
                // Access is also the complete, non-mutating ownership preflight.
                request.mesh(child);
                targets.add(new CommitTarget(key, candidate, state, request, child));
            } else if ((state & NODE_TYPE_MSK) == NODE_TYPE_INNER
                    || (state & NODE_TYPE_MSK) == NODE_TYPE_LEAF) {
                targets.add(new CommitTarget(key, candidate, state, null, -1));
            } else {
                throw new IllegalStateException("invalid staged hierarchy owner");
            }
        }

        for (CommitTarget target : targets) {
            int oldGeometry;
            byte previousChildExistence;
            boolean previousChildExistenceKnown;
            if (target.request != null) {
                previousChildExistenceKnown = target.request.hasChildExistence(target.child);
                previousChildExistence = previousChildExistenceKnown
                        ? target.request.childExistence(target.child) : 0;
                oldGeometry = target.request.replaceMesh(target.child,
                        target.candidate.geometryId);
                target.request.setChildExistence(target.child,
                        target.candidate.childExistence);
                // Keep the request form until the renderer fence is finalized. Rollback can then
                // restore the exact request instead of reconstructing it from published nodes.
            } else {
                int node = target.state & NODE_ID_MSK;
                oldGeometry = this.nodeData.getNodeGeometry(node);
                previousChildExistence = this.nodeData.getNodeChildExistence(node);
                previousChildExistenceKnown = true;
                this.nodeData.setNodeGeometry(node, target.candidate.geometryId);
                // Existing child topology remains authoritative through the geometry fence.
                // Finalization reconciles the old allocation with the new indexed mask.
                this.invalidateNode(node);
            }
            this.stagedGeometry.remove(target.key);
            this.committedGeometry.put(target.key, new CommittedGeometry(
                    target.candidate.geometryId, oldGeometry,
                    target.candidate.childExistence, previousChildExistence,
                    previousChildExistenceKnown));
            this.committedPositions.put(target.key.position, target.key);
        }
    }

    /** Restores attached candidates and drops unattached ones without exposing a missing mesh. */
    public void rollbackStagedRoot(long sourceRevision) {
        ArrayList<Map.Entry<StagedGeometryKey, CommittedGeometry>> committed = new ArrayList<>();
        for (Map.Entry<StagedGeometryKey, CommittedGeometry> entry
                : this.committedGeometry.entrySet()) {
            if (entry.getKey().sourceRevision == sourceRevision) committed.add(entry);
        }
        ArrayList<RollbackTarget> targets = new ArrayList<>(committed.size());
        for (Map.Entry<StagedGeometryKey, CommittedGeometry> entry : committed) {
            targets.add(this.resolveRollbackTarget(entry.getKey(), entry.getValue()));
        }
        for (RollbackTarget target : targets) {
            CommittedGeometry geometry = target.geometry;
            if (target.request != null) {
                target.request.restoreMesh(target.child, geometry.previousGeometry);
                target.request.restoreChildExistence(target.child,
                        geometry.previousChildExistenceKnown,
                        geometry.previousChildExistence);
            } else {
                this.nodeData.setNodeGeometry(target.node, geometry.previousGeometry);
                this.nodeData.setNodeChildExistence(target.node,
                        geometry.previousChildExistence);
                this.invalidateNode(target.node);
            }
            Integer priorRetirement = this.rolledBackGeometry.put(target.key,
                    geometry.candidateGeometry);
            if (priorRetirement != null) {
                throw new IllegalStateException("rollback geometry is already awaiting retirement");
            }
            this.committedGeometry.remove(target.key);
            this.committedPositions.remove(target.key.position, target.key);
        }

        var iterator = this.stagedGeometry.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<StagedGeometryKey, StagedGeometry> entry = iterator.next();
            if (entry.getKey().sourceRevision != sourceRevision) continue;
            removeGeometryIfAllocated(entry.getValue().geometryId);
            iterator.remove();
        }
    }

    /** Releases rollback candidates only after the restored node pointers crossed a GPU fence. */
    public void completeRollback(long sourceRevision) {
        var iterator = this.rolledBackGeometry.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<StagedGeometryKey, Integer> entry = iterator.next();
            if (entry.getKey().sourceRevision != sourceRevision) continue;
            removeGeometryIfAllocated(entry.getValue());
            iterator.remove();
        }
    }

    /** Releases the old allocation only after the committed node pointers crossed a GPU fence. */
    public boolean finalizeStagedRoot(long sourceRevision) {
        for (Map.Entry<StagedGeometryKey, CommittedGeometry> entry
                : this.committedGeometry.entrySet()) {
            if (entry.getKey().sourceRevision != sourceRevision) continue;
            int state = this.activeSectionMap.get(entry.getKey().position);
            if (state == -1 || (state & NODE_TYPE_MSK) == NODE_TYPE_REQUEST) continue;
            int node = state & NODE_ID_MSK;
            int current = Byte.toUnsignedInt(this.nodeData.getNodeChildExistence(node));
            int desired = Byte.toUnsignedInt(entry.getValue().candidateChildExistence);
            if (current != desired
                    && !this.canReconcileTopology(entry.getKey().position, node, desired,
                    sourceRevision)) return false;
        }
        this.finishPublishedRequests(sourceRevision);
        var iterator = this.committedGeometry.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<StagedGeometryKey, CommittedGeometry> entry = iterator.next();
            if (entry.getKey().sourceRevision != sourceRevision) continue;
            CommittedGeometry geometry = entry.getValue();
            int state = this.activeSectionMap.get(entry.getKey().position);
            if (state != -1 && (state & NODE_TYPE_MSK) != NODE_TYPE_REQUEST) {
                int node = state & NODE_ID_MSK;
                int current = Byte.toUnsignedInt(this.nodeData.getNodeChildExistence(node));
                int desired = Byte.toUnsignedInt(geometry.candidateChildExistence);
                if (current != desired) {
                    this.reconcileTopology(entry.getKey().position, node, desired);
                }
            }
            if (geometry.previousGeometry != geometry.candidateGeometry) {
                removeGeometryIfAllocated(geometry.previousGeometry);
            }
            this.committedPositions.remove(entry.getKey().position, entry.getKey());
            iterator.remove();
        }
        var staged = this.stagedGeometry.entrySet().iterator();
        while (staged.hasNext()) {
            Map.Entry<StagedGeometryKey, StagedGeometry> entry = staged.next();
            if (entry.getKey().sourceRevision != sourceRevision) continue;
            removeGeometryIfAllocated(entry.getValue().geometryId);
            staged.remove();
        }
        return true;
    }

    private record CommitTarget(StagedGeometryKey key, StagedGeometry candidate, int state,
                                NodeRequest request, int child) {}
    private record CommittedGeometry(int candidateGeometry, int previousGeometry,
                                     byte candidateChildExistence,
                                     byte previousChildExistence,
                                     boolean previousChildExistenceKnown) {}
    private record RollbackTarget(StagedGeometryKey key, CommittedGeometry geometry,
                                  NodeRequest request, int child, int node) {}

    private RollbackTarget resolveRollbackTarget(StagedGeometryKey key,
                                                 CommittedGeometry geometry) {
        int state = this.activeSectionMap.get(key.position);
        if (state == -1) {
            throw new IllegalStateException("committed geometry lost its hierarchy owner");
        }
        if ((state & NODE_TYPE_MSK) == NODE_TYPE_REQUEST) {
            NodeRequest request = this.request(state);
            int child = (state & REQUEST_TYPE_MSK) == REQUEST_TYPE_SINGLE
                    ? 0 : getChildIdx(key.position);
            if (request.mesh(child) != geometry.candidateGeometry) {
                throw new IllegalStateException("committed request geometry was replaced");
            }
            return new RollbackTarget(key, geometry, request, child, -1);
        }
        if ((state & NODE_TYPE_MSK) == NODE_TYPE_INNER
                || (state & NODE_TYPE_MSK) == NODE_TYPE_LEAF) {
            int node = state & NODE_ID_MSK;
            if (this.nodeData.getNodeGeometry(node) != geometry.candidateGeometry) {
                throw new IllegalStateException("committed node geometry was replaced");
            }
            return new RollbackTarget(key, geometry, null, -1, node);
        }
        throw new IllegalStateException("invalid committed hierarchy owner");
    }

    /** Staged requests become real nodes only after geometry and topology crossed sync. */
    private void finishPublishedRequests(long sourceRevision) {
        for (Map.Entry<StagedGeometryKey, CommittedGeometry> entry
                : this.committedGeometry.entrySet()) {
            StagedGeometryKey key = entry.getKey();
            CommittedGeometry geometry = entry.getValue();
            if (key.sourceRevision != sourceRevision) continue;
            int state = this.activeSectionMap.get(key.position);
            if (state == -1 || (state & NODE_TYPE_MSK) != NODE_TYPE_REQUEST) continue;
            NodeRequest request = this.request(state);
            int child = (state & REQUEST_TYPE_MSK) == REQUEST_TYPE_SINGLE
                    ? 0 : getChildIdx(key.position);
            if (request.mesh(child) != geometry.candidateGeometry) {
                throw new IllegalStateException("committed request geometry was replaced");
            }
            this.finishRequestIfSatisfied(state & NODE_ID_MSK, request,
                    state & REQUEST_TYPE_MSK);
        }
    }

    /**
     * Detaches every loaded descendant while retaining the coarse parent and its indexed child
     * mask. Geometry allocations are deliberately retained for a second, post-fence phase.
     */
    boolean coarsenSubtree(long revision, long parent) {
        assertPosValid(parent);
        if (this.coarsenedGeometry.containsKey(revision)) {
            throw new IllegalStateException("duplicate subtree coarsening revision");
        }
        if (this.hasPendingGeometry(parent)) return false;
        for (CoarsenedGeometry pending : this.coarsenedGeometry.values()) {
            if (contains(pending.parent, parent) || contains(parent, pending.parent)) return false;
        }

        IntArrayList retired = new IntArrayList();
        int state = this.activeSectionMap.get(parent);
        int type = state & NODE_TYPE_MSK;
        if (state != -1 && type == NODE_TYPE_INNER) {
            int nodeId = state & NODE_ID_MSK;
            this._recurseRemoveNode(parent, true, retired);
            this.transition(parent, state, NODE_TYPE_LEAF | nodeId);
            this.refreshParentLeafState(parent);
            this.invalidateNode(nodeId);
        } else if (state != -1 && type != NODE_TYPE_LEAF && type != NODE_TYPE_REQUEST) {
            throw new IllegalStateException("invalid coarsening owner for "
                    + SectionKey.describe(parent));
        }
        this.coarsenedGeometry.put(revision, new CoarsenedGeometry(parent, retired));
        return true;
    }

    private boolean hasPendingGeometry(long parent) {
        for (StagedGeometryKey key : this.stagedGeometry.keySet()) {
            if (contains(parent, key.position)) return true;
        }
        for (StagedGeometryKey key : this.committedPositions.values()) {
            if (contains(parent, key.position)) return true;
        }
        return false;
    }

    /** Releases detached geometry only after the topology fence completed. */
    void releaseCoarsened(long revision) {
        CoarsenedGeometry retired = this.coarsenedGeometry.remove(revision);
        if (retired == null) {
            throw new IllegalStateException("missing fenced subtree coarsening revision");
        }
        for (int index = 0; index < retired.geometryIds.size(); index++) {
            this.removeGeometryIfAllocated(retired.geometryIds.getInt(index));
        }
    }

    private void removeGeometryIfAllocated(int geometryId) {
        if (geometryId != NULL_GEOMETRY_ID && geometryId != EMPTY_GEOMETRY_ID) {
            this.geometryManager.removeSection(geometryId);
        }
    }

    private void removeGeometry(int id) {
        this.geometryManager.removeSection(id);
    }

    private void recurseRemoveNode(long pos) {
        this._recurseRemoveNode(pos, false, null);
    }

    private NodeRequest request(int state) {
        int requestId = state&NODE_ID_MSK;
        return (state&REQUEST_TYPE_MSK) == REQUEST_TYPE_SINGLE
                ? this.topLevelRequests.get(requestId)
                : this.childRequests.get(requestId);
    }

    private void transition(long position, int expected, int replacement) {
        int current = this.activeSectionMap.get(position);
        if (current != expected) {
            throw new IllegalStateException("Hierarchy state changed at " + SectionKey.describe(position)
                    + ": expected " + expected + ", found " + current);
        }
        this.activeSectionMap.put(position, replacement);
    }

    private void removeState(long position, int expected) {
        if (this.activeSectionMap.get(position) != expected) {
            throw new IllegalStateException("Hierarchy owner changed at " + SectionKey.describe(position));
        }
        this.activeSectionMap.remove(position);
    }

    private void refreshParentLeafState(long position) {
        if (this.topLevelNodes.contains(position)) return;
        int parentState = this.activeSectionMap.get(makeParentPos(position));
        if ((parentState&NODE_TYPE_MSK) != NODE_TYPE_INNER) return;
        int parentId = parentState&NODE_ID_MSK;
        int ptr = this.nodeData.getChildPtr(parentId);
        boolean allLeaf = ptr >= 0 && ptr != SENTINEL_EMPTY_CHILD_PTR;
        for (int i = 0; allLeaf && i < this.nodeData.getChildPtrCount(parentId); i++) {
            int childState = this.activeSectionMap.get(this.nodeData.nodePosition(ptr+i));
            allLeaf = childState == (NODE_TYPE_LEAF|(ptr+i));
        }
        this.nodeData.setAllChildrenAreLeaf(parentId, allLeaf);
        this.invalidateNode(parentId);
    }

    /**
     * An indexed topology replacement may remove only renderer-empty branches. Their activation
     * owners retire them first; a committed or nonempty descendant keeps the parent replacement
     * pending instead of becoming unreachable.
     */
    private boolean canReconcileTopology(long position, int nodeId, int desiredMask,
                                         long sourceRevision) {
        int state = this.activeSectionMap.get(position);
        int type = state & NODE_TYPE_MSK;
        if ((state & NODE_ID_MSK) != nodeId
                || type != NODE_TYPE_LEAF && type != NODE_TYPE_INNER) {
            throw new IllegalStateException("topology owner changed for "
                    + SectionKey.describe(position));
        }
        if (type == NODE_TYPE_LEAF) return true;
        int existingMask = this.existingChildMask(nodeId);
        int removedMask = existingMask & ~desiredMask;
        for (int child = 0; child < 8; child++) {
            if ((removedMask & 1 << child) != 0
                    && !this.canRemoveSubtree(makeChildPos(position, child), sourceRevision)) {
                return false;
            }
        }
        return true;
    }

    private boolean canRemoveSubtree(long position, long sourceRevision) {
        for (StagedGeometryKey key : this.committedPositions.values()) {
            if (key.sourceRevision != sourceRevision && contains(position, key.position)) {
                return false;
            }
        }
        for (StagedGeometryKey key : this.stagedGeometry.keySet()) {
            if (key.sourceRevision != sourceRevision && contains(position, key.position)) {
                return false;
            }
        }
        int state = this.activeSectionMap.get(position);
        if (state == -1) return true;
        int type = state & NODE_TYPE_MSK;
        if (type == NODE_TYPE_REQUEST) return true;
        if (type != NODE_TYPE_LEAF && type != NODE_TYPE_INNER) {
            throw new IllegalStateException("invalid removable hierarchy state");
        }
        int nodeId = state & NODE_ID_MSK;
        if (this.nodeData.getNodeGeometry(nodeId) != EMPTY_GEOMETRY_ID) return false;
        if (type == NODE_TYPE_INNER) {
            int pointer = this.nodeData.getChildPtr(nodeId);
            int count = this.nodeData.getChildPtrCount(nodeId);
            if (pointer < 0 || pointer == SENTINEL_EMPTY_CHILD_PTR) {
                throw new IllegalStateException("inner node has no concrete children");
            }
            for (int index = 0; index < count; index++) {
                if (!this.canRemoveSubtree(this.nodeData.nodePosition(pointer + index),
                        sourceRevision)) return false;
            }
        }
        return true;
    }

    private void reconcileTopology(long position, int nodeId, int desiredMask) {
        int state = this.activeSectionMap.get(position);
        int type = state & NODE_TYPE_MSK;
        if ((state & NODE_ID_MSK) != nodeId
                || type != NODE_TYPE_LEAF && type != NODE_TYPE_INNER) {
            throw new IllegalStateException("topology owner changed for "
                    + SectionKey.describe(position));
        }
        if (SectionKey.level(position) == 0 && desiredMask != 0) {
            throw new IllegalArgumentException("LOD 0 node cannot have children");
        }

        int existingMask = type == NODE_TYPE_INNER ? this.existingChildMask(nodeId) : 0;
        RelocatedChild[] retained = new RelocatedChild[8];
        int retainedCount = 0;
        if (type == NODE_TYPE_INNER) {
            int pointer = this.nodeData.getChildPtr(nodeId);
            int count = this.nodeData.getChildPtrCount(nodeId);
            for (int index = 0; index < count; index++) {
                int childId = pointer + index;
                long childPosition = this.nodeData.nodePosition(childId);
                int child = getChildIdx(childPosition);
                int childState = this.activeSectionMap.get(childPosition);
                if ((existingMask & 1 << child) == 0
                        || (childState & NODE_ID_MSK) != childId) {
                    throw new IllegalStateException("child allocation and hierarchy map disagree");
                }
                if ((desiredMask & 1 << child) == 0) {
                    this.recurseRemoveNode(childPosition);
                } else {
                    retained[child] = new RelocatedChild(childPosition, childState,
                            this.nodeData.snapshotNode(childId));
                    retainedCount++;
                }
            }
            for (RelocatedChild child : retained) {
                if (child == null) continue;
                int oldId = child.state & NODE_ID_MSK;
                this.nodeData.free(oldId);
                this.clearFreeId(oldId);
                this.invalidateNode(oldId);
            }
        }

        int newPointer = -1;
        boolean allLeaf = retainedCount != 0;
        if (retainedCount != 0) {
            newPointer = this.nodeData.allocate(retainedCount);
            int newId = newPointer;
            for (RelocatedChild child : retained) {
                if (child == null) continue;
                this.nodeData.restoreNode(newId, child.snapshot);
                int childType = child.state & NODE_TYPE_MSK;
                this.transition(child.position, child.state, childType | newId);
                this.clearAllocId(newId);
                this.invalidateNode(newId);
                allLeaf &= childType == NODE_TYPE_LEAF;
                newId++;
            }
        }

        this.nodeData.setChildPtr(nodeId, newPointer);
        if (retainedCount != 0) this.nodeData.setChildPtrCount(nodeId, retainedCount);
        this.nodeData.setAllChildrenAreLeaf(nodeId, allLeaf);
        this.nodeData.setNodeChildExistence(nodeId, (byte) desiredMask);
        int replacementType = retainedCount == 0 ? NODE_TYPE_LEAF : NODE_TYPE_INNER;
        if (replacementType != type) {
            this.transition(position, state, replacementType | nodeId);
            this.refreshParentLeafState(position);
        }
        this.updateChildRequest(nodeId, position, desiredMask & ~existingChildMask(nodeId));
        this.invalidateNode(nodeId);
    }

    private int existingChildMask(int nodeId) {
        int pointer = this.nodeData.getChildPtr(nodeId);
        if (pointer == -1) return 0;
        if (pointer == SENTINEL_EMPTY_CHILD_PTR) {
            throw new IllegalStateException("empty child-pointer sentinel is not a topology");
        }
        int mask = 0;
        int count = this.nodeData.getChildPtrCount(nodeId);
        for (int index = 0; index < count; index++) {
            int childId = pointer + index;
            if (!this.nodeData.nodeExists(childId)) {
                throw new IllegalStateException("missing allocated child node");
            }
            int child = getChildIdx(this.nodeData.nodePosition(childId));
            if ((mask & 1 << child) != 0) {
                throw new IllegalStateException("duplicate child octant");
            }
            mask |= 1 << child;
        }
        return mask;
    }

    private void updateChildRequest(int nodeId, long position, int requiredMask) {
        if (this.nodeData.isNodeRequestInFlight(nodeId)) {
            int requestId = this.nodeData.getNodeRequest(nodeId);
            NodeRequest request = this.childRequests.get(requestId);
            this.setRequestedChildren(requestId, request, requiredMask);
            if (requiredMask == 0) this.releaseChildRequest(nodeId, requestId);
            return;
        }
        if (requiredMask == 0) return;
        NodeRequest request = this.beginChildRequest(nodeId, position);
        this.setRequestedChildren(this.nodeData.getNodeRequest(nodeId), request, requiredMask);
    }

    private static boolean contains(long ancestor, long descendant) {
        int shift = SectionKey.level(ancestor) - SectionKey.level(descendant);
        return shift >= 0
                && SectionKey.x(ancestor) == SectionKey.x(descendant) >> shift
                && SectionKey.y(ancestor) == SectionKey.y(descendant) >> shift
                && SectionKey.z(ancestor) == SectionKey.z(descendant) >> shift;
    }

    private record RelocatedChild(long position, int state, long[] snapshot) {}


    private void addRequestedChild(int requestId, NodeRequest request, int child) {
        request.require(child);
        long childPos = makeChildPos(request.position(), child);
        if (this.activeSectionMap.get(childPos) != -1) {
            throw new IllegalStateException("Requested child was already active: " + SectionKey.describe(childPos));
        }
        this.activeSectionMap.put(childPos, requestId|NODE_TYPE_REQUEST|REQUEST_TYPE_CHILD);
        if (!this.watched.add(childPos)) {
            throw new IllegalStateException("Requested child was already watched: " + SectionKey.describe(childPos));
        }
    }

    private void setRequestedChildren(int requestId, NodeRequest request, int requiredMask) {
        if ((requiredMask & ~0xFF) != 0) {
            throw new IllegalArgumentException("Invalid child mask: " + requiredMask);
        }
        int removed = request.requiredMask()&~requiredMask;
        for (int child = 0; child < 8; child++) {
            if ((removed&(1<<child)) == 0) continue;
            int meshId = this.removeRequestedChild(requestId, request, child);
            if (meshId != NULL_GEOMETRY_ID && meshId != EMPTY_GEOMETRY_ID) {
                this.removeGeometry(meshId);
            }
        }
        int added = requiredMask&~request.requiredMask();
        for (int child = 0; child < 8; child++) {
            if ((added&(1<<child)) != 0) {
                this.addRequestedChild(requestId, request, child);
            }
        }
    }

    private NodeRequest beginChildRequest(int parentNodeId, long position) {
        if (this.nodeData.isNodeRequestInFlight(parentNodeId)) {
            throw new IllegalStateException("Node already has a request");
        }
        var request = new NodeRequest(position);
        int requestId = this.childRequests.put(request);
        this.nodeData.markRequestInFlight(parentNodeId);
        this.nodeData.setNodeRequest(parentNodeId, requestId);
        return request;
    }

    private int removeRequestedChild(int requestId, NodeRequest request, int child) {
        long childPos = makeChildPos(request.position(), child);
        int expected = requestId|NODE_TYPE_REQUEST|REQUEST_TYPE_CHILD;
        this.removeState(childPos, expected);
        this.unwatch(childPos);
        return request.unrequire(child);
    }

    private void releaseChildRequest(int parentNodeId, int requestId) {
        if (!this.nodeData.isNodeRequestInFlight(parentNodeId) || this.nodeData.getNodeRequest(parentNodeId) != requestId) {
            throw new IllegalStateException("Parent does not own request " + requestId);
        }
        this.childRequests.release(requestId);
        this.nodeData.setNodeRequest(parentNodeId, NULL_REQUEST_ID);
        this.nodeData.unmarkRequestInFlight(parentNodeId);
    }

    private void _removeRequest(int reqId, NodeRequest req, IntArrayList retiredGeometry) {
        int required = req.requiredMask();
        for (int child = 0; child < 8; child++) {
            if ((required & 1 << child) == 0) continue;
            int meshId = this.removeRequestedChild(reqId, req, child);
            this.retireGeometry(meshId, retiredGeometry);
        }
        this.childRequests.release(reqId);
    }

    //Recursivly fully removes all nodes and children
    private void _recurseRemoveNode(long pos, boolean onlyRemoveChildren,
                                    IntArrayList retiredGeometry) {
        //NOTE: this also removes from the section map
        int nodeId;
        if (onlyRemoveChildren) {
            nodeId = this.activeSectionMap.get(pos);
        } else {
            nodeId = this.activeSectionMap.remove(pos);
        }
        if (nodeId == -1) {
            throw new IllegalStateException("Cannot remove pos that doesnt exist");
        }
        int type = nodeId&NODE_TYPE_MSK;
        if (type == NODE_TYPE_INNER || type == NODE_TYPE_LEAF) {
            nodeId &= NODE_ID_MSK;
            if (!this.nodeData.nodeExists(nodeId)) {
                throw new IllegalStateException("Node exists in section map but not in nodeData");
            }


            byte childExistence = this.nodeData.getNodeChildExistence(nodeId);
            if (this.nodeData.isNodeRequestInFlight(nodeId)) {
                //If there is an inflight request, the request and all associated data
                int reqId = this.nodeData.getNodeRequest(nodeId);
                var req = this.childRequests.get(reqId);
                childExistence ^= req.requiredMask();
                this._removeRequest(reqId, req, retiredGeometry);

                if (onlyRemoveChildren) {
                    this.nodeData.unmarkRequestInFlight(nodeId);
                    this.nodeData.setNodeRequest(nodeId, NULL_REQUEST_ID);
                }
            }


            //Need to recurse into childExistence that exist, this is xor between a request mask if there is and the
            // childRequest
            // this is only valid if this node is an inner node

            //Only recursively delete if the node is not a leaf
            if (type == NODE_TYPE_INNER) {
                for (int i = 0; i < 8; i++) {
                    if ((childExistence & (1 << i)) == 0) continue;

                    long childPos = makeChildPos(pos, i);
                    this._recurseRemoveNode(childPos, false, retiredGeometry);
                }

                if (onlyRemoveChildren) {
                    this.nodeData.setChildPtr(nodeId, -1);
                }
            }

            if (!onlyRemoveChildren) {
                //Free geometry and related memory for this node
                int meshId = this.nodeData.getNodeGeometry(nodeId);
                this.retireGeometry(meshId, retiredGeometry);

                this.nodeData.free(nodeId);
                this.clearFreeId(nodeId);
                this.invalidateNode(nodeId);

                //Unwatch position
                this.unwatch(pos);
            } else {
                //All children removed, clear marker
                this.nodeData.setAllChildrenAreLeaf(nodeId, false);
                //TODO: probably need this.clearId(nodeId);
                this.invalidateNode(nodeId);
            }
        } else if (type == NODE_TYPE_REQUEST) {
            this.unwatch(pos);
            if ((nodeId&REQUEST_TYPE_MSK) == REQUEST_TYPE_SINGLE) {
                nodeId &= NODE_ID_MSK;

                var req = this.topLevelRequests.get(nodeId);
                if (req.position() != pos)
                    throw new IllegalStateException();

                this.topLevelRequests.release(nodeId);
                int meshId = req.mesh(0);
                this.retireGeometry(meshId, retiredGeometry);

            } else {
                throw new IllegalStateException("Cannot recursively remove one child from an active request");
            }
        } else {
            throw new IllegalStateException();
        }
    }

    private void retireGeometry(int geometryId, IntArrayList retiredGeometry) {
        if (geometryId == EMPTY_GEOMETRY_ID || geometryId == NULL_GEOMETRY_ID) return;
        if (retiredGeometry == null) this.removeGeometry(geometryId);
        else retiredGeometry.add(geometryId);
    }

    //==================================================================================================================

    private void finishRequestIfSatisfied(int requestId, NodeRequest request, int requestType) {
        if (!request.isSatisfied()) {
            return;
        }
        if (requestType == REQUEST_TYPE_SINGLE) {
            this.finishTopLevelRequest(requestId, request);
        } else if (requestType == REQUEST_TYPE_CHILD) {
            this.finishRequest(requestId, request);
        } else {
            throw new IllegalStateException("Unknown request type: " + requestType);
        }
    }

    private void finishTopLevelRequest(int requestId, NodeRequest request) {
        int id = this.nodeData.allocate();
        this.nodeData.setNodePosition(id, request.position());
        this.nodeData.setNodeGeometry(id, request.mesh(0));
        this.nodeData.setNodeChildExistence(id, request.childExistence(0));
        this.transition(request.position(), requestId|NODE_TYPE_REQUEST|REQUEST_TYPE_SINGLE, id|NODE_TYPE_LEAF);
        this.topLevelRequests.release(requestId);
        this.invalidateNode(id);

        if (!this.topLevelNodeIds.add(id)) {
            throw new IllegalStateException();
        }
        this.clearAllocId(id);
        if (this.topLevelNodeIdAddedCallback != null)
            this.topLevelNodeIdAddedCallback.accept(id);
    }

    private void finishRequest(int requestId, NodeRequest request) {
        int parentState = this.activeSectionMap.get(request.position());
        int parentType = parentState&NODE_TYPE_MSK;
        if (parentState == -1 || (parentType != NODE_TYPE_LEAF && parentType != NODE_TYPE_INNER)) {
            throw new IllegalStateException("Request parent is no longer a node: "
                    + SectionKey.describe(request.position()) + " " + parentState);
        }
        int parentNodeId = parentState&NODE_ID_MSK;
        if (!this.nodeData.isNodeRequestInFlight(parentNodeId) || this.nodeData.getNodeRequest(parentNodeId) != requestId) {
            throw new IllegalStateException("Request is not owned by its parent: " + requestId);
        }

        int requested = request.requiredMask();
        if (requested == 0) {
            this.releaseChildRequest(parentNodeId, requestId);
            this.invalidateNode(parentNodeId);
            return;
        }

        int oldPtr = -1;
        int oldCount = 0;
        int existing = 0;
        RelocatedChild[] retained = new RelocatedChild[8];
        if (parentType == NODE_TYPE_INNER) {
            oldPtr = this.nodeData.getChildPtr(parentNodeId);
            oldCount = this.nodeData.getChildPtrCount(parentNodeId);
            if (oldPtr == -1) throw new IllegalStateException("Inner node has no child allocation");
            if (oldPtr != SENTINEL_EMPTY_CHILD_PTR) {
                for (int i = 0; i < oldCount; i++) {
                    int oldId = oldPtr + i;
                    if (!this.nodeData.nodeExists(oldId)) {
                        throw new IllegalStateException("Missing child node");
                    }
                    long childPosition = this.nodeData.nodePosition(oldId);
                    int child = getChildIdx(childPosition);
                    int childState = this.activeSectionMap.get(childPosition);
                    if ((childState & NODE_ID_MSK) != oldId || retained[child] != null) {
                        throw new IllegalStateException(
                                "Child allocation does not own its hierarchy position");
                    }
                    retained[child] = new RelocatedChild(childPosition, childState,
                            this.nodeData.snapshotNode(oldId));
                    existing |= 1 << child;
                }
            }
        }

        int combined = existing|requested;
        if ((existing&requested) != 0) throw new IllegalStateException("Requested children already exist");
        if (combined != Byte.toUnsignedInt(this.nodeData.getNodeChildExistence(parentNodeId))) {
            throw new IllegalStateException("Child allocation does not match the existence mask");
        }

        int combinedCount = Integer.bitCount(combined);
        // A same-size or shrinking replacement can reuse the captured old block without
        // temporary headroom. A growing replacement must reserve its larger block before
        // changing the active hierarchy so allocation failure leaves parent coverage intact.
        boolean releaseBeforeAllocation = oldPtr != -1
                && oldPtr != SENTINEL_EMPTY_CHILD_PTR && combinedCount <= oldCount;
        if (releaseBeforeAllocation) {
            for (int index = 0; index < oldCount; index++) {
                int oldId = oldPtr + index;
                this.nodeData.free(oldId);
                this.clearFreeId(oldId);
                this.invalidateNode(oldId);
            }
        }

        int newPtr = this.nodeData.allocate(combinedCount);
        int newId = newPtr;
        boolean allLeaf = true;
        for (int child = 0; child < 8; child++) {
            int bit = 1<<child;
            if ((combined&bit) == 0) continue;
            if ((requested&bit) != 0) {
                this.installRequestedChild(requestId, request, child, newId);
            } else {
                RelocatedChild retainedChild = retained[child];
                if (retainedChild == null) {
                    throw new IllegalStateException("Missing retained child snapshot");
                }
                this.nodeData.restoreNode(newId, retainedChild.snapshot);
                int childType = retainedChild.state & NODE_TYPE_MSK;
                this.transition(retainedChild.position, retainedChild.state,
                        childType | newId);
                this.clearAllocId(newId);
                this.invalidateNode(newId);
                allLeaf &= childType == NODE_TYPE_LEAF;
            }
            newId++;
        }

        if (!releaseBeforeAllocation && oldPtr != -1
                && oldPtr != SENTINEL_EMPTY_CHILD_PTR) {
            for (int index = 0; index < oldCount; index++) {
                int oldId = oldPtr + index;
                this.nodeData.free(oldId);
                this.clearFreeId(oldId);
                this.invalidateNode(oldId);
            }
        }

        this.nodeData.setChildPtr(parentNodeId, newPtr);
        this.nodeData.setChildPtrCount(parentNodeId, Integer.bitCount(combined));
        this.nodeData.setAllChildrenAreLeaf(parentNodeId, allLeaf);
        this.releaseChildRequest(parentNodeId, requestId);
        if (parentType == NODE_TYPE_LEAF) {
            this.transition(request.position(), parentState, NODE_TYPE_INNER|parentNodeId);
            this.refreshParentLeafState(request.position());
        }
        this.invalidateNode(parentNodeId);
    }

    private void installRequestedChild(int requestId, NodeRequest request, int child, int nodeId) {
        long position = makeChildPos(request.position(), child);
        this.nodeData.setNodePosition(nodeId, position);
        this.nodeData.setNodeChildExistence(nodeId, request.childExistence(child));
        this.nodeData.setNodeGeometry(nodeId, request.mesh(child));
        this.transition(position, requestId|NODE_TYPE_REQUEST|REQUEST_TYPE_CHILD, NODE_TYPE_LEAF|nodeId);
        this.clearAllocId(nodeId);
        this.invalidateNode(nodeId);
    }

    //==================================================================================================================
    private void processRequest(long pos) {
        int state = this.activeSectionMap.get(pos);
        if (state == -1) return;
        int nodeType = state & NODE_TYPE_MSK;
        if (nodeType == NODE_TYPE_REQUEST) return;
        if (nodeType != NODE_TYPE_LEAF && nodeType != NODE_TYPE_INNER) {
            throw new IllegalStateException("Unknown node type: " + nodeType);
        }
        if (SectionKey.level(pos) == 0) return;

        int nodeId = state & NODE_ID_MSK;
        if (this.nodeData.getNodeGeometry(nodeId) == NULL_GEOMETRY_ID
                || this.nodeData.isNodeRequestInFlight(nodeId)) return;
        int desired = Byte.toUnsignedInt(this.nodeData.getNodeChildExistence(nodeId));
        int existing = nodeType == NODE_TYPE_INNER ? this.existingChildMask(nodeId) : 0;
        int missing = desired & ~existing;
        if (missing == 0) return;
        NodeRequest request = this.beginChildRequest(nodeId, pos);
        this.setRequestedChildren(this.nodeData.getNodeRequest(nodeId), request, missing);
    }

    //Used for raw access to the update map, internal (used in async)
    IntOpenHashSet getNodeUpdates() {
        return this.nodeUpdates;
    }

    //Used to write a specified node into a specific address (used in async)
    void writeNode(int node, long address) {
        this.writeNodeData(address, node);
    }

    private void writeNodeData(long address, int node) {
        boolean terminal = this.nodeData.getNodeChildExistence(node) == 0;
        this.nodeData.writeNode(address, node, terminal);
    }

    private void invalidateNode(int nodeId) {
        this.nodeUpdates.add(nodeId);
    }

    //==================================================================================================================
    private static int getChildIdx(long pos) {
        int x = SectionKey.x(pos);
        int y = SectionKey.y(pos);
        int z = SectionKey.z(pos);
        // Regional octants match the cell layout: X is bit 0, Z is bit 1, Y is bit 2.
        return (x & 1) | ((z & 1) << 1) | ((y & 1) << 2);
    }

    private static long makeChildPos(long basePos, int addin) {
        int lvl = SectionKey.level(basePos);
        if (lvl == 0) {
            throw new IllegalArgumentException("Cannot create a child lower than lod level 0");
        }
        return SectionKey.pack(lvl-1,
                (SectionKey.x(basePos)<<1)|(addin&1),
                (SectionKey.y(basePos)<<1)|((addin>>2)&1),
                (SectionKey.z(basePos)<<1)|((addin>>1)&1));
    }

    private static long makeParentPos(long pos) {
        int lvl = SectionKey.level(pos);
        if (lvl == MAX_LOD_LAYER) {
            throw new IllegalArgumentException("Cannot create a parent higher than LoD " + (MAX_LOD_LAYER));
        }
        return SectionKey.pack(lvl+1,
                SectionKey.x(pos)>>1,
                SectionKey.y(pos)>>1,
                SectionKey.z(pos)>>1);
    }

    public int getCurrentMaxNodeId() {
        return this.nodeData.getEndNodeId();
    }
}
