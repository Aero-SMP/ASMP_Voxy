package me.cortex.voxy.client.core.rendering.hierachical;

import it.unimi.dsi.fastutil.ints.IntConsumer;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import me.cortex.voxy.client.lod.ClientLodNetwork;
import me.cortex.voxy.client.core.rendering.SectionUpdateRouter;
import me.cortex.voxy.client.core.rendering.building.BuiltSection;
import me.cortex.voxy.client.core.rendering.section.geometry.BasicAsyncGeometryManager;
import me.cortex.voxy.client.core.util.ExpandingObjectAllocationList;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.world.WorldEngine;


import static me.cortex.voxy.common.world.WorldEngine.MAX_LOD_LAYER;
import static me.cortex.voxy.common.world.WorldEngine.UPDATE_TYPE_BLOCK_BIT;


public class NodeManager {
    //Assumptions:
    // all nodes have children (i.e. all nodes have at least one child existence bit set at all times)
    // leaf nodes always contain geometry (empty geometry counts as geometry (it just doesnt take any memory to store))
    // All nodes except top nodes have parents

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

    private final ExpandingObjectAllocationList<NodeRequest> topLevelRequests = new ExpandingObjectAllocationList<>(NodeRequest[]::new, NodeStore.REQUEST_ID_MSK);
    private final ExpandingObjectAllocationList<NodeRequest> childRequests = new ExpandingObjectAllocationList<>(NodeRequest[]::new, NodeStore.REQUEST_ID_MSK);
    private final IntOpenHashSet nodeUpdates = new IntOpenHashSet();
    private final BasicAsyncGeometryManager geometryManager;
    private final SectionUpdateRouter watcher;
    private final Long2IntOpenHashMap activeSectionMap = new Long2IntOpenHashMap();
    private final LongOpenHashSet pendingLeafTransitions = new LongOpenHashSet();
    private final NodeStore nodeData;
    public final int maxNodeCount;
    private final IntOpenHashSet topLevelNodeIds = new IntOpenHashSet();
    private final LongOpenHashSet topLevelNodes = new LongOpenHashSet();
    private int activeNodeRequestCount;

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

    public NodeManager(int maxNodeCount, BasicAsyncGeometryManager geometryManager, SectionUpdateRouter watcher) {
        if ((maxNodeCount&(maxNodeCount-1))!=0) {
            throw new IllegalArgumentException("Max node count must be a power of 2");
        }
        if (maxNodeCount>(1<<24)) {
            throw new IllegalArgumentException("Max node count cannot exceed 2^24");
        }
        this.activeSectionMap.defaultReturnValue(-1);
        this.watcher = watcher;
        this.maxNodeCount = maxNodeCount;
        this.nodeData = new NodeStore(maxNodeCount);
        this.geometryManager = geometryManager;
    }

    private static void assertPosValid(long pos) {
        int lvl = WorldEngine.getLevel(pos);
        int x = WorldEngine.getX(pos);
        int y = WorldEngine.getY(pos);
        int z = WorldEngine.getZ(pos);
        if (WorldEngine.getWorldSectionId(lvl, x, y, z) != pos) {
            throw new IllegalStateException("Reconstructed pos not same as original");
        }
        x <<= lvl;
        y <<= lvl;
        z <<= lvl;
        long p2 = WorldEngine.getWorldSectionId(0, x, y, z);
        if (WorldEngine.getLevel(p2) != 0 || WorldEngine.getX(p2) != x || WorldEngine.getY(p2) != y || WorldEngine.getZ(p2) != z) {
            throw new IllegalStateException("Position not valid at all levels: " + pos + "-"+WorldEngine.pprintPos(pos) + ":"+WorldEngine.pprintPos(p2));
        }
    }

    public void insertTopLevelNode(long pos) {
        //Verify that pos is actually valid
        assertPosValid(pos);

        if (this.activeSectionMap.containsKey(pos)) {
            Logger.error("Tried inserting top level pos " + WorldEngine.pprintPos(pos) + " but it was in active map, discarding!");
            return;
        }

        var request = new NodeRequest(pos);
        request.require(0);
        int id = this.topLevelRequests.put(request);
        ClientLodNetwork.prioritizeCoverage(pos);
        this.watcher.watch(pos, WorldEngine.DEFAULT_UPDATE_FLAGS);
        this.activeSectionMap.put(pos, id|NODE_TYPE_REQUEST|REQUEST_TYPE_SINGLE);
        this.topLevelNodes.add(pos);
    }

    public void removeTopLevelNode(long pos) {
        if (!this.topLevelNodes.remove(pos)) {
            throw new IllegalStateException("Position not in top level map: " + WorldEngine.pprintPos(pos));
        }
        int nodeId = this.activeSectionMap.get(pos);
        if (nodeId == -1) {
            throw new IllegalStateException("Tried removing top level pos " + WorldEngine.pprintPos(pos) + " but it was not in active map, discarding!");
        }
        if ((nodeId&NODE_TYPE_MSK)!=NODE_TYPE_REQUEST) {
            int id = nodeId&NODE_ID_MSK;
            if (!this.topLevelNodeIds.remove(id)) {
                throw new IllegalStateException("Node id was not in top level node ids: " + nodeId + " pos: " + WorldEngine.pprintPos(pos));
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

    public void processGeometryResult(BuiltSection sectionResult) {
        long pos = sectionResult.position;
        int nodeId = this.activeSectionMap.get(pos);
        if (nodeId == -1) {
            sectionResult.free();
            return;
        }

        if ((nodeId&NODE_TYPE_MSK)==NODE_TYPE_REQUEST) {
            int requestId = nodeId&NODE_ID_MSK;
            var request = this.request(nodeId);
            int child = (nodeId&REQUEST_TYPE_MSK)==REQUEST_TYPE_SINGLE ? 0 : getChildIdx(pos);
            request.replaceMesh(child, this.uploadReplaceSection(request.mesh(child), sectionResult));

            // Geometry results also carry child existence, while child-change events are only sent when it changes.
            if (!request.hasChildExistence(child)) {
                request.setChildExistence(child, sectionResult.childExistence);
            }
            this.finishRequestIfSatisfied(requestId, request, nodeId&REQUEST_TYPE_MSK);
        } else if ((nodeId&NODE_TYPE_MSK)==NODE_TYPE_INNER || (nodeId&NODE_TYPE_MSK)==NODE_TYPE_LEAF) {
            int nodeType = nodeId&NODE_TYPE_MSK;
            nodeId&=NODE_ID_MSK;


            //TODO: check this is ok and correct
            if ((this.watcher.get(pos)&UPDATE_TYPE_BLOCK_BIT)==0) {
                if (this.nodeData.isNodeGeometryInFlight(nodeId)) {
                    throw new IllegalStateException();
                }
                Logger.warn("Recieved geometry update but not watching it, discarding");
                sectionResult.free();
                return;
            }

            byte generatedChildExistence = sectionResult.childExistence;
            this.nodeData.unmarkNodeGeometryInFlight(nodeId);
            if (this.updateNodeGeometry(nodeId, sectionResult) != 0) {
                this.invalidateNode(nodeId);
            }
            if (nodeType == NODE_TYPE_INNER && this.pendingLeafTransitions.remove(pos)) {
                this.updateChildSectionsInner(pos, nodeId, generatedChildExistence);
            }
        } else {
            throw new IllegalStateException();
        }
    }

    private void removeGeometry(int id) {
        this.geometryManager.removeSection(id);
    }

    private int uploadReplaceSection(int meshId, BuiltSection section) {
        if (section.isEmpty()) {
            if (meshId != NULL_GEOMETRY_ID && meshId != EMPTY_GEOMETRY_ID) {
                this.geometryManager.removeSection(meshId);
            }
            section.free();
            return EMPTY_GEOMETRY_ID;
        }
        if (meshId != NULL_GEOMETRY_ID && meshId != EMPTY_GEOMETRY_ID) {
            return this.geometryManager.uploadReplaceSection(meshId, section);
        }
        return this.geometryManager.uploadSection(section);
    }

    private int updateNodeGeometry(int node, BuiltSection geometry) {
        int previousGeometry = this.nodeData.getNodeGeometry(node);
        int newGeometry = EMPTY_GEOMETRY_ID;
        if (previousGeometry != EMPTY_GEOMETRY_ID && previousGeometry != NULL_GEOMETRY_ID) {
            if (!geometry.isEmpty()) {
                newGeometry = this.geometryManager.uploadReplaceSection(previousGeometry, geometry);
            } else {
                this.geometryManager.removeSection(previousGeometry);
            }
        } else {
            if (!geometry.isEmpty()) {
                newGeometry = this.geometryManager.uploadSection(geometry);
            }
        }

        if (previousGeometry != newGeometry) {
            this.nodeData.setNodeGeometry(node, newGeometry);
        }
        if (previousGeometry == newGeometry) {
            return 0;//No change
        } else if (previousGeometry == EMPTY_GEOMETRY_ID || previousGeometry == NULL_GEOMETRY_ID) {
            return 1;//Became non-empty/non-null
        } else {
            return 2;//Became empty
        }
    }
    //==================================================================================================================

    public void processChildChange(long pos, byte childExistence) {
        int nodeId = this.activeSectionMap.get(pos);
        if (nodeId == -1) {
            Logger.warn("Got child change for pos " + WorldEngine.pprintPos(pos) + " but it was not in active map, ignoring!");
            return;
        }


        if ((nodeId&NODE_TYPE_MSK)==NODE_TYPE_REQUEST) {
            int requestId = nodeId&NODE_ID_MSK;
            var request = this.request(nodeId);
            int child = (nodeId&REQUEST_TYPE_MSK)==REQUEST_TYPE_SINGLE ? 0 : getChildIdx(pos);
            request.setChildExistence(child, childExistence);
            this.finishRequestIfSatisfied(requestId, request, nodeId&REQUEST_TYPE_MSK);
        } else if ((nodeId&NODE_TYPE_MSK)==NODE_TYPE_INNER) {
            this.updateChildSectionsInner(pos, nodeId&NODE_ID_MSK, childExistence);
        } else if ((nodeId&NODE_TYPE_MSK)==NODE_TYPE_LEAF) {

            //We might be leaf but we still might be inflight
            if (this.nodeData.isNodeRequestInFlight(nodeId&NODE_ID_MSK)) {
                int requestId = this.nodeData.getNodeRequest(nodeId);
                var request = this.childRequests.get(requestId);
                if (request.position() != pos) throw new IllegalStateException("Request is not at pos, got " + WorldEngine.pprintPos(request.position()) + " expected " + WorldEngine.pprintPos(pos));
                this.setRequestedChildren(requestId, request, Byte.toUnsignedInt(childExistence));
                if (request.isSatisfied()) {
                    this.finishRequest(requestId, request);
                }
            }

            //Just need to update the child node data, nothing else
            this.nodeData.setNodeChildExistence(nodeId&NODE_ID_MSK, childExistence);
            //Need to resubmit to gpu
            this.invalidateNode(nodeId&NODE_ID_MSK);//TODO:FIXME: Do we???
        }
    }

    private void updateChildSectionsInner(long pos, int nodeId, byte childExistence) {
        if (childExistence == 0 && this.nodeData.getNodeGeometry(nodeId) == NULL_GEOMETRY_ID) {
            this.pendingLeafTransitions.add(pos);
            this.processInnerRequest(pos, nodeId);
            return;
        }
        this.pendingLeafTransitions.remove(pos);
        int existence = Byte.toUnsignedInt(this.nodeData.getNodeChildExistence(nodeId));
        int target = Byte.toUnsignedInt(childExistence);
        int added = target&~existence;
        if (added != 0) {
            NodeRequest request = this.nodeData.isNodeRequestInFlight(nodeId)
                    ? this.childRequests.get(this.nodeData.getNodeRequest(nodeId))
                    : this.beginChildRequest(nodeId, pos);
            this.requireRequestOwner(request, pos);
            this.setRequestedChildren(this.nodeData.getNodeRequest(nodeId), request, request.requiredMask()|added);
        }
        this.nodeData.setNodeChildExistence(nodeId, childExistence);

        int removed = existence&~target;
        if (removed != 0 && this.nodeData.isNodeRequestInFlight(nodeId)) {
            int requestId = this.nodeData.getNodeRequest(nodeId);
            NodeRequest request = this.childRequests.get(requestId);
            this.requireRequestOwner(request, pos);
            int cancelled = request.requiredMask()&removed;
            this.setRequestedChildren(requestId, request, request.requiredMask()&~cancelled);
            removed &= ~cancelled;
        }
        if (removed != 0) this.removeLoadedChildren(pos, nodeId, removed);

        if (this.nodeData.isNodeRequestInFlight(nodeId)) {
            int requestId = this.nodeData.getNodeRequest(nodeId);
            NodeRequest request = this.childRequests.get(requestId);
            this.requireRequestOwner(request, pos);
            if (request.isSatisfied()) this.finishRequest(requestId, request);
        }
        if (childExistence == 0) this.transitionToLeaf(pos, nodeId);
    }

    private void removeLoadedChildren(long position, int parentId, int removed) {
        int oldPtr = this.nodeData.getChildPtr(parentId);
        int oldCount = this.nodeData.getChildPtrCount(parentId);
        if (oldPtr < 0 || oldPtr == SENTINEL_EMPTY_CHILD_PTR) {
            throw new IllegalStateException("Inner node has no loaded children");
        }
        int oldMask = 0;
        for (int i = 0; i < oldCount; i++) {
            if (!this.nodeData.nodeExists(oldPtr+i)) throw new IllegalStateException("Missing child node");
            oldMask |= 1<<getChildIdx(this.nodeData.nodePosition(oldPtr+i));
        }
        if ((oldMask&removed) != removed) throw new IllegalStateException("Removing unloaded children");

        int remaining = oldMask&~removed;
        int newPtr = remaining == 0 ? SENTINEL_EMPTY_CHILD_PTR : this.nodeData.allocate(Integer.bitCount(remaining));
        int oldId = oldPtr-1;
        int newId = newPtr-1;
        boolean allLeaf = remaining != 0;
        for (int child = 0; child < 8; child++) {
            int bit = 1<<child;
            if ((oldMask&bit) == 0) continue;
            oldId++;
            if ((removed&bit) != 0) {
                this.recurseRemoveNode(makeChildPos(position, child));
            } else {
                newId++;
                allLeaf &= this.relocateNode(oldId, newId);
                this.nodeData.free(oldId);
            }
        }
        this.nodeData.setChildPtr(parentId, newPtr);
        this.nodeData.setChildPtrCount(parentId, remaining == 0 ? 8 : Integer.bitCount(remaining));
        this.nodeData.setAllChildrenAreLeaf(parentId, allLeaf);
        this.invalidateNode(parentId);
    }

    private void recurseRemoveChildNodes(long pos) {
        this._recurseRemoveNode(pos, true);
    }

    private void recurseRemoveNode(long pos) {
        this._recurseRemoveNode(pos, false);
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
            throw new IllegalStateException("Hierarchy state changed at " + WorldEngine.pprintPos(position)
                    + ": expected " + expected + ", found " + current);
        }
        this.activeSectionMap.put(position, replacement);
    }

    private void removeState(long position, int expected) {
        if (this.activeSectionMap.get(position) != expected) {
            throw new IllegalStateException("Hierarchy owner changed at " + WorldEngine.pprintPos(position));
        }
        this.activeSectionMap.remove(position);
    }

    private void transitionToLeaf(long position, int nodeId) {
        if (this.nodeData.isNodeRequestInFlight(nodeId)) {
            throw new IllegalStateException("Cannot make a requested node a leaf");
        }
        int childPtr = this.nodeData.getChildPtr(nodeId);
        if (childPtr != -1 && childPtr != SENTINEL_EMPTY_CHILD_PTR) {
            throw new IllegalStateException("Cannot make a node with children a leaf");
        }
        if (this.nodeData.getNodeGeometry(nodeId) == NULL_GEOMETRY_ID) {
            throw new IllegalStateException("Leaf nodes require geometry");
        }
        this.nodeData.setChildPtr(nodeId, -1);
        this.nodeData.setAllChildrenAreLeaf(nodeId, false);
        this.transition(position, NODE_TYPE_INNER|nodeId, NODE_TYPE_LEAF|nodeId);
        this.invalidateNode(nodeId);
        this.refreshParentLeafState(position);
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


    private void addRequestedChild(int requestId, NodeRequest request, int child) {
        request.require(child);
        long childPos = makeChildPos(request.position(), child);
        if (this.activeSectionMap.get(childPos) != -1) {
            throw new IllegalStateException("Requested child was already active: " + WorldEngine.pprintPos(childPos));
        }
        this.activeSectionMap.put(childPos, requestId|NODE_TYPE_REQUEST|REQUEST_TYPE_CHILD);
        ClientLodNetwork.prioritizeVisible(childPos);
        if (!this.watcher.watch(childPos, WorldEngine.DEFAULT_UPDATE_FLAGS)) {
            throw new IllegalStateException("Requested child was already watched: " + WorldEngine.pprintPos(childPos));
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
        this.activeNodeRequestCount++;
        return request;
    }

    private void requireRequestOwner(NodeRequest request, long position) {
        if (request.position() != position) {
            throw new IllegalStateException("Request owner changed: " + WorldEngine.pprintPos(position));
        }
    }

    private int removeRequestedChild(int requestId, NodeRequest request, int child) {
        long childPos = makeChildPos(request.position(), child);
        int expected = requestId|NODE_TYPE_REQUEST|REQUEST_TYPE_CHILD;
        this.removeState(childPos, expected);
        if (!this.watcher.unwatch(childPos, WorldEngine.DEFAULT_UPDATE_FLAGS)) {
            throw new IllegalStateException("Requested child was not being watched: " + WorldEngine.pprintPos(childPos));
        }
        return request.unrequire(child);
    }

    private void releaseChildRequest(int parentNodeId, int requestId) {
        if (!this.nodeData.isNodeRequestInFlight(parentNodeId) || this.nodeData.getNodeRequest(parentNodeId) != requestId) {
            throw new IllegalStateException("Parent does not own request " + requestId);
        }
        this.childRequests.release(requestId);
        this.nodeData.setNodeRequest(parentNodeId, NULL_REQUEST_ID);
        this.nodeData.unmarkRequestInFlight(parentNodeId);
        this.activeNodeRequestCount--;
    }

    private void _removeRequest(int reqId, NodeRequest req) {
        this.setRequestedChildren(reqId, req, 0);
        this.childRequests.release(reqId);
        this.activeNodeRequestCount--;
    }

    //Recursivly fully removes all nodes and children
    private void _recurseRemoveNode(long pos, boolean onlyRemoveChildren) {
        //NOTE: this also removes from the section map
        int nodeId;
        if (onlyRemoveChildren) {
            nodeId = this.activeSectionMap.get(pos);
        } else {
            this.pendingLeafTransitions.remove(pos);
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
                this._removeRequest(reqId, req);

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
                    this.recurseRemoveNode(childPos);
                }

                if (onlyRemoveChildren) {
                    this.nodeData.setChildPtr(nodeId, -1);
                }
            }

            if (!onlyRemoveChildren) {
                //Free geometry and related memory for this node
                int meshId = this.nodeData.getNodeGeometry(nodeId);
                if (meshId != EMPTY_GEOMETRY_ID && meshId != NULL_GEOMETRY_ID)
                    this.removeGeometry(meshId);

                this.nodeData.free(nodeId);
                this.clearFreeId(nodeId);
                this.invalidateNode(nodeId);

                //Unwatch position
                if (!this.watcher.unwatch(pos, WorldEngine.DEFAULT_UPDATE_FLAGS)) {
                    throw new IllegalStateException("Pos was not being watched");
                }
            } else {
                //All children removed, clear marker
                this.nodeData.setAllChildrenAreLeaf(nodeId, false);
                //TODO: probably need this.clearId(nodeId);
                this.invalidateNode(nodeId);
            }
        } else if (type == NODE_TYPE_REQUEST) {
            if (!this.watcher.unwatch(pos, WorldEngine.DEFAULT_UPDATE_FLAGS)) {
                throw new IllegalStateException("Pos was not being watched");
            }
            if ((nodeId&REQUEST_TYPE_MSK) == REQUEST_TYPE_SINGLE) {
                nodeId &= NODE_ID_MSK;

                var req = this.topLevelRequests.get(nodeId);
                if (req.position() != pos)
                    throw new IllegalStateException();

                this.topLevelRequests.release(nodeId);
                int meshId = req.mesh(0);
                if (meshId != EMPTY_GEOMETRY_ID && meshId != NULL_GEOMETRY_ID)
                    this.removeGeometry(meshId);

            } else {
                throw new IllegalStateException("Cannot recursively remove one child from an active request");
            }
        } else {
            throw new IllegalStateException();
        }
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
                    + WorldEngine.pprintPos(request.position()) + " " + parentState);
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
        if (parentType == NODE_TYPE_INNER) {
            oldPtr = this.nodeData.getChildPtr(parentNodeId);
            oldCount = this.nodeData.getChildPtrCount(parentNodeId);
            if (oldPtr == -1) throw new IllegalStateException("Inner node has no child allocation");
            if (oldPtr != SENTINEL_EMPTY_CHILD_PTR) {
                for (int i = 0; i < oldCount; i++) {
                    if (!this.nodeData.nodeExists(oldPtr+i)) throw new IllegalStateException("Missing child node");
                    existing |= 1 << getChildIdx(this.nodeData.nodePosition(oldPtr+i));
                }
            }
        }

        int combined = existing|requested;
        if ((existing&requested) != 0) throw new IllegalStateException("Requested children already exist");
        if (combined != Byte.toUnsignedInt(this.nodeData.getNodeChildExistence(parentNodeId))) {
            throw new IllegalStateException("Child allocation does not match the existence mask");
        }

        int newPtr = this.nodeData.allocate(Integer.bitCount(combined));
        int oldId = oldPtr-1;
        int newId = newPtr-1;
        boolean allLeaf = true;
        for (int child = 0; child < 8; child++) {
            int bit = 1<<child;
            if ((combined&bit) == 0) continue;
            newId++;
            if ((requested&bit) != 0) {
                this.installRequestedChild(requestId, request, child, newId);
            } else {
                oldId++;
                allLeaf &= this.relocateNode(oldId, newId);
            }
        }

        if (oldPtr != -1 && oldPtr != SENTINEL_EMPTY_CHILD_PTR) this.nodeData.free(oldPtr, oldCount);
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

    private boolean relocateNode(int oldId, int newId) {
        long position = this.nodeData.nodePosition(oldId);
        int oldState = this.activeSectionMap.get(position);
        int type = oldState&NODE_TYPE_MSK;
        if ((type != NODE_TYPE_LEAF && type != NODE_TYPE_INNER) || (oldState&NODE_ID_MSK) != oldId) {
            throw new IllegalStateException("Child allocation does not own " + WorldEngine.pprintPos(position));
        }
        this.nodeData.copyNode(oldId, newId);
        this.transition(position, oldState, type|newId);
        this.clearAllocId(newId);
        this.clearFreeId(oldId);
        this.invalidateNode(oldId);
        this.invalidateNode(newId);
        return type == NODE_TYPE_LEAF;
    }

    //==================================================================================================================
    public void processRequest(long pos) {
        int nodeId = this.activeSectionMap.get(pos);
        if (nodeId == -1) {
            return;
        }
        int nodeType = nodeId&NODE_TYPE_MSK;
        nodeId &= NODE_ID_MSK;
        if (nodeType == NODE_TYPE_REQUEST) {
            Logger.error("Tried processing request for pos: " + WorldEngine.pprintPos(pos) + " but its type was a request, ignoring!");
            return;
        } else if (nodeType != NODE_TYPE_LEAF && nodeType != NODE_TYPE_INNER ) {
            throw new IllegalStateException("Unknown node type: " + nodeType);
        }


        if (WorldEngine.getLevel(pos) == 0) {
            Logger.error("Requests cannot exist for bottom level nodes. at: " + WorldEngine.pprintPos(pos) + ". Ignoring request");
            return;
        }

        //TODO:
        // Make it so that if a request is not in flight it has an invalid/null request entry

        // NOTE: inner nodes /w request should check they have geometry independenently of being inflight








        //TODO: FIXTHIS: https://discord.com/channels/973046939375505408/973046939375505411/1328785093812031489
        // this causes things to go bad, when racing the gpu, i.e. this becomes an inner node that has geometry and there is now a request for it
        // in this case we should not mark the node as inflight as it casuse very bad things to happen
        // we should only mark inflight when there is actually a request
        if (nodeType == NODE_TYPE_LEAF) {

            if (this.nodeData.getNodeGeometry(nodeId) == NULL_GEOMETRY_ID) {
                //Weird case that not sure how possible
                Logger.warn("Got request for leaf that doesnt have geometry, this should not be possible at pos " + WorldEngine.pprintPos(pos));
                if (!this.watcher.watch(pos, WorldEngine.UPDATE_TYPE_BLOCK_BIT)) {
                    Logger.warn("Node: " + nodeId + " at pos: " + WorldEngine.pprintPos(pos) + " got update request, but geometry was already being watched");
                }
                return;
            }

            //Check if the node is already in-flight, if it is, dont do any processing
            if (this.nodeData.isNodeRequestInFlight(nodeId)) {
                // GPU requests use a bounded retry lease so a lost asynchronous readback
                // cannot suppress refinement forever. A retry that reaches an active CPU
                // request is expected and requires no additional work.
                return;
            }

            this.makeLeafChildRequest(nodeId);

        } else {
            this.processInnerRequest(pos, nodeId);
        }
    }

    private void makeLeafChildRequest(int nodeId) {
        long pos = this.nodeData.nodePosition(nodeId);
        byte childExistence = this.nodeData.getNodeChildExistence(nodeId);

        if (childExistence == 0) {
            // A resolved empty node is terminal. This also repairs a node serialized by an
            // older resolution-ordering race instead of leaving a zero-child request in flight.
            if (ClientLodNetwork.isSectionResolved(pos)) {
                this.invalidateNode(nodeId);
                return;
            }
            if (!this.topLevelNodes.contains(pos)) {//Top level nodes are special, as they can have a request with child existence of 0 for performance reasons
                Logger.warn("Not creating a leaf request with existence mask of 0 at pos", WorldEngine.pprintPos(pos));
                this.invalidateNode(nodeId);
                return;
            }
        }

        var request = this.beginChildRequest(nodeId, pos);
        int requestId = this.nodeData.getNodeRequest(nodeId);
        this.setRequestedChildren(requestId, request, Byte.toUnsignedInt(childExistence));
    }

    //A request is received for an inner node position
    private void processInnerRequest(long pos, int nodeId) {
        //TODO: finish
        if (!this.nodeData.isNodeGeometryInFlight(nodeId)) {
            if (!this.watcher.watch(pos, WorldEngine.UPDATE_TYPE_BLOCK_BIT)) {
                this.invalidateNode(nodeId);//Who knows why but just invalidate the data just to keep in sync
            } else {
                this.nodeData.markNodeGeometryInFlight(nodeId);
            }
        }
    }
    //==================================================================================================================
    // Used by the cleaning system to ensure memory capacity in the geometry store

    //TODO: Think plan for this is to add new flag to NodeStore to indicate if geometry mesh request is inflight
    // this used for state verification and not emitting/assuming things
    // e.g. current issue is if an inner node wants/needs to convert into a leaf node, but the inner node has no geometry
    // how to deal with that?? e.g. inner node geometry gets cleared but then the childExistance gets set to 0
    // it needs to become a leaf node

    public void removeNodeGeometry(long pos) {
        int nodeId = this.activeSectionMap.get(pos);
        if (nodeId == -1) {
            return;
        }
        int nodeType = nodeId&NODE_TYPE_MSK;
        nodeId &= NODE_ID_MSK;
        if (nodeType == NODE_TYPE_REQUEST) {
            //TODO: only log a specific number of times
            return;
        }

        if (nodeType == NODE_TYPE_INNER) {
            this.clearGeometryInternal(pos, nodeId);
        } else {//NODE_TYPE_LEAF
            //TODO: here we need to make the parent node a leaf node...
            // TODO? think about maybe only doing it if all children of the parent are leaf nodes aswell

            if (this.topLevelNodes.contains(pos)) {
                //We are asked to remove the geometry of a top level leaf node, which we cannot do
                int geo = this.nodeData.getNodeGeometry(nodeId);
                if (geo == NULL_GEOMETRY_ID || geo == EMPTY_GEOMETRY_ID) {
                    //If its null or empty we can "ignore" the request
                } else {
                    Logger.warn("Tried removing geometry from top level node which is not allowed, disregarding request");
                    //TODO: probably do
                    return;
                }

            } else {
                this.processLeafGeometryRemoval(pos);
            }
        }
    }

    private void processLeafGeometryRemoval(long cPos) {
        long pPos = makeParentPos(cPos);
        int pId = this.activeSectionMap.get(pPos);
        if (pId == -1) throw new IllegalStateException("Parent node must exist");
        if ((pId & NODE_TYPE_MSK) != NODE_TYPE_INNER)
            throw new IllegalStateException("Parent node must be an inner node");
        pId &= NODE_ID_MSK;

        int pGeo = this.nodeData.getNodeGeometry(pId);
        if (pGeo == NULL_GEOMETRY_ID) {
            //We cannot make the parent a leaf node with null geometry
            this.processRequest(pPos);//Request geometry
        } else {
            this.recurseRemoveChildNodes(pPos);
            this.transitionToLeaf(pPos, pId);
        }
    }

    private void clearGeometryInternal(long pos, int nodeId) {
        int meshId = this.nodeData.getNodeGeometry(nodeId);

        //TODO: if isNodeGeometryInFlight is true and geometryId == NULL_GEOMETRY_ID, probably need to
        // unwatch from watcher and unmark

        if (meshId != NULL_GEOMETRY_ID && meshId != EMPTY_GEOMETRY_ID) {
            //Unwatch node geometry changes
            if (this.watcher.unwatch(pos, WorldEngine.UPDATE_TYPE_BLOCK_BIT)) {
                throw new IllegalStateException("Unwatching position for geometry removal at: " + WorldEngine.pprintPos(pos) + " resulted in full removal");
            }
            //Remove geometry and set to null
            this.removeGeometry(meshId);
            this.nodeData.setNodeGeometry(nodeId, NULL_GEOMETRY_ID);
            this.invalidateNode(nodeId);//Only need to invalidate on change
            this.nodeData.unmarkNodeGeometryInFlight(nodeId);//Remove geometry inflight as well, its removed
        }
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
        long position = this.nodeData.nodePosition(node);
        boolean terminal = this.nodeData.getNodeChildExistence(node) == 0
                && ClientLodNetwork.isSectionResolved(position);
        this.nodeData.writeNode(address, node, terminal);
    }

    private void invalidateNode(int nodeId) {
        this.nodeUpdates.add(nodeId);
    }

    //==================================================================================================================
    private static int getChildIdx(long pos) {
        int x = WorldEngine.getX(pos);
        int y = WorldEngine.getY(pos);
        int z = WorldEngine.getZ(pos);
        return (x&1)|((y&1)<<2)|((z&1)<<1);
    }

    private static long makeChildPos(long basePos, int addin) {
        int lvl = WorldEngine.getLevel(basePos);
        if (lvl == 0) {
            throw new IllegalArgumentException("Cannot create a child lower than lod level 0");
        }
        return WorldEngine.getWorldSectionId(lvl-1,
                (WorldEngine.getX(basePos)<<1)|(addin&1),
                (WorldEngine.getY(basePos)<<1)|((addin>>2)&1),
                (WorldEngine.getZ(basePos)<<1)|((addin>>1)&1));
    }

    private static long makeParentPos(long pos) {
        int lvl = WorldEngine.getLevel(pos);
        if (lvl == MAX_LOD_LAYER) {
            throw new IllegalArgumentException("Cannot create a parent higher than LoD " + (MAX_LOD_LAYER));
        }
        return WorldEngine.getWorldSectionId(lvl+1,
                WorldEngine.getX(pos)>>1,
                WorldEngine.getY(pos)>>1,
                WorldEngine.getZ(pos)>>1);
    }

    public int getCurrentMaxNodeId() {
        return this.nodeData.getEndNodeId();
    }


    //==================================================================================================================

    //TODO: need to figure out what happens if an inner node gets marked with child existence of 0
    // it should become a leaf node
    // however, if the node doesnt have geometry attached that would put it in an invalid state so need to figure out
    // a solution for this

}
