package me.cortex.voxy.client.core.rendering.hierachical;

import it.unimi.dsi.fastutil.ints.IntConsumer;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.lod.ClientLodNetwork;
import me.cortex.voxy.client.core.rendering.SectionUpdateRouter;
import me.cortex.voxy.client.core.rendering.building.BuiltSection;
import me.cortex.voxy.client.core.rendering.section.geometry.BasicAsyncGeometryManager;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
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

            //Unmark geometry inflight
            this.nodeData.unmarkNodeGeometryInFlight(nodeId);
            // Just doing a geometry update
            if (this.updateNodeGeometry(nodeId, sectionResult) != 0) {
                this.invalidateNode(nodeId);
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
        //Very complex and painful operation

        if (childExistence == 0) {
            Logger.warn("Inner node child existence is changing to 0, this is mild bad");
        }

        //This works in 2 parts, adding and removing, adding is (surprisingly) much easier than removing
        // adding, either adds to a request, or creates a new request
        byte existence = this.nodeData.getNodeChildExistence(nodeId);

        byte add = (byte) ((existence^childExistence)&childExistence);
        if (add != 0) {//We have nodes to add
            if (!this.nodeData.isNodeRequestInFlight(nodeId)) {//If there is not an existing request, create it
                this.beginChildRequest(nodeId, pos);
            }
            //It is guaranteed that at this point the node has a request
            // so add the new nodes to it
            int requestId = this.nodeData.getNodeRequest(nodeId);
            var request = this.childRequests.get(requestId);
            if (request.position() != pos)
                throw new IllegalStateException("Request is not at pos: got " + WorldEngine.pprintPos(pos) + " expected: " + WorldEngine.pprintPos(request.position()));

            this.setRequestedChildren(requestId, request, request.requiredMask()|Byte.toUnsignedInt(add));
        }

        //Update the nodes existence msk to the new one
        // this needs to be before the removal since that may invoke requestFinish, which expects updated node masks
        //TODO: verify this
        this.nodeData.setNodeChildExistence(nodeId, childExistence);

        // Do removals
        int rem = ((existence^childExistence)&existence)&0xFF;
        if (rem != 0) {
            //If there is an inflight request, update it w.r.t removals
            if (this.nodeData.isNodeRequestInFlight(nodeId)) {
                int requestId = this.nodeData.getNodeRequest(nodeId);
                var request = this.childRequests.get(requestId);
                if (request.position() != pos) throw new IllegalStateException("Request is not at pos");


                int reqRem = request.requiredMask()&rem;
                this.setRequestedChildren(requestId, request, request.requiredMask()&~reqRem);
                rem ^= reqRem;
            }

            if (rem != 0) {
                //There are child node entries that need removing
                // and of course still delete all the old data



                //Compact the node data with respect to what has been removed
                int oldPtr = this.nodeData.getChildPtr(nodeId);
                int oldCount = this.nodeData.getChildPtrCount(nodeId);
                if (oldPtr == -1) {
                    throw new IllegalStateException();
                }
                int oldExistence = 0;
                for (int i = 0; i < oldCount; i++) {
                    if (!this.nodeData.nodeExists(i+oldPtr)) throw new IllegalStateException();
                    oldExistence |= 1<<getChildIdx(this.nodeData.nodePosition(i+oldPtr));
                }

                if ((rem&oldExistence)!=rem) {//If rem contains stuff that does not exist, is illegal
                    throw new IllegalStateException();
                }

                int remaining = rem^oldExistence;

                if (remaining == 0) {
                    //This state should only ever occur when a node is inflight, or... if an inner node has existance mask of 0... sigh
                    if (childExistence != 0 && !this.nodeData.isNodeRequestInFlight(nodeId)) {
                        throw new IllegalStateException();
                    }
                    //TODO: TRIPPLY CHECK THIS IS RIGHT
                    //TODO: make new SENTINAL value for this!!! NodeStore.NODE_ID_MSK-1
                    // check in shader aswell!!!

                    this.nodeData.setAllChildrenAreLeaf(nodeId, false);//Children dont exist, therefor set them to false
                    this.nodeData.setChildPtr(nodeId, SENTINEL_EMPTY_CHILD_PTR);
                    this.nodeData.setChildPtrCount(nodeId, 8);
                    for (int i = 0; i < 8; i++) {
                        if ((rem&(1<<i))==0) continue;
                        long cPos = makeChildPos(pos, i);
                        this.recurseRemoveNode(cPos);
                    }

                } else {

                    int newCnt = Integer.bitCount(remaining);
                    int newPtr = this.nodeData.allocate(newCnt);
                    int prevChildId = oldPtr - 1;
                    int newChildId = newPtr - 1;

                    boolean allChildNodesLeaf = true;
                    //Need to compact the old into the new
                    for (int i = 0; i < 8; i++) {
                        if ((oldExistence & (1 << i)) == 0) continue;
                        prevChildId++;
                        if ((rem & (1 << i)) != 0) {//If we removing
                            long cPos = makeChildPos(pos, i);
                            this.recurseRemoveNode(cPos);
                        } else {//We are compacting
                            newChildId++;
                            long cPos = this.nodeData.nodePosition(prevChildId);
                            if (cPos != makeChildPos(pos, i)) {
                                throw new IllegalStateException();
                            }

                            //copy the previous entry to its new location
                            this.nodeData.copyNode(prevChildId, newChildId);

                            this.clearAllocId(newChildId);
                            this.clearFreeId(prevChildId);

                            int prevNodeId = this.activeSectionMap.get(cPos);
                            if ((prevNodeId & NODE_TYPE_MSK) == NODE_TYPE_REQUEST) {
                                throw new IllegalStateException();
                            }
                            if ((prevNodeId & NODE_ID_MSK) != prevChildId) {
                                throw new IllegalStateException("State inconsistency");
                            }
                            allChildNodesLeaf &= (prevNodeId & NODE_TYPE_MSK) == NODE_TYPE_LEAF;
                            this.activeSectionMap.put(cPos, (prevNodeId & NODE_TYPE_MSK) | newChildId);

                            //Release the old entry
                            this.nodeData.free(prevChildId);
                            //Need to invalidate the old and the new
                            this.invalidateNode(prevChildId);
                            this.invalidateNode(newChildId);
                        }
                    }
                    this.nodeData.setAllChildrenAreLeaf(nodeId, allChildNodesLeaf);

                    //Put the new childPtr into the map
                    this.nodeData.setChildPtr(nodeId, newPtr);
                    this.nodeData.setChildPtrCount(nodeId, newCnt);

                }

                //Invalidate the node as data has changed
                this.invalidateNode(nodeId);
            }

            //TODO: reuse requestId and obj from before (its faster)
            //Only finish the request after so that compaction of the child msk is correct
            if (this.nodeData.isNodeRequestInFlight(nodeId)) {//Also only need to do this after/if there are removals to be done
                int requestId = this.nodeData.getNodeRequest(nodeId);
                var request = this.childRequests.get(requestId);
                if (request.position() != pos) throw new IllegalStateException("Request is not at pos");

                if (request.isSatisfied()) {
                    this.finishRequest(requestId, request);
                }
            }
        }


        if (childExistence == 0) {
            //We need to change the node from inner to leaf as it does not have any children
            if (this.nodeData.isNodeRequestInFlight(nodeId))//Leaf nodes cannot have requests associated to them
                throw new IllegalStateException();

            if (this.nodeData.getNodeGeometry(nodeId) == NULL_GEOMETRY_ID) {
                Logger.error("Transforming inner node to leaf node while it has null geometry");
                if (!this.nodeData.isNodeGeometryInFlight(nodeId)) {
                    if ((this.watcher.get(pos) & UPDATE_TYPE_BLOCK_BIT) != 0) {
                        throw new IllegalStateException("Watcher was already watching for geometry update, but geometry was null");
                    }
                    this.processRequest(pos);//Force geometry request
                    if (((this.watcher.get(pos) & UPDATE_TYPE_BLOCK_BIT) == 0)||!this.nodeData.isNodeGeometryInFlight(nodeId)) {
                        throw new IllegalStateException("Watcher must be watching for geometry update");
                    }
                }
                //Set the geometry to EMPTY while the geometry update request is executing
                Logger.error("Setting geometry to EMPTY while request is inflight");
                //TODO: figure out a better way to mark this for tracing verificaction and like less confusion
                // (instead of like EMPTY_GEOMETRY_ID do like INFLIGHT_GEOMETRY_ID)
                this.nodeData.setNodeGeometry(nodeId, EMPTY_GEOMETRY_ID);
            }

            if (this.nodeData.getChildPtr(nodeId) != SENTINEL_EMPTY_CHILD_PTR) {//This should only ever be the sentinal ptr
                throw new IllegalStateException();
            }

            this.nodeData.setChildPtr(nodeId, -1);
            this.activeSectionMap.put(pos, NODE_TYPE_LEAF|nodeId);
            this.nodeData.setAllChildrenAreLeaf(nodeId, false);//Node is leaf so is not all child leaf
            this.invalidateNode(nodeId);
        }
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


    private void addRequestedChild(int requestId, NodeRequest request, int child) {
        request.require(child);
        long childPos = makeChildPos(request.position(), child);
        if (this.activeSectionMap.put(childPos, requestId|NODE_TYPE_REQUEST|REQUEST_TYPE_CHILD) != -1) {
            throw new IllegalStateException("Requested child was already active: " + WorldEngine.pprintPos(childPos));
        }
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

    private int removeRequestedChild(int requestId, NodeRequest request, int child) {
        long childPos = makeChildPos(request.position(), child);
        int expected = requestId|NODE_TYPE_REQUEST|REQUEST_TYPE_CHILD;
        int actual = this.activeSectionMap.remove(childPos);
        if (actual != expected) {
            throw new IllegalStateException("Requested child has wrong owner: " + actual + " != " + expected);
        }
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
        int previous = this.activeSectionMap.put(request.position(), id|NODE_TYPE_LEAF);
        if (previous != (requestId|NODE_TYPE_REQUEST|REQUEST_TYPE_SINGLE)) {
            throw new IllegalStateException("Top-level request has wrong owner: " + previous);
        }
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
        int parentNodeId = this.activeSectionMap.get(request.position());
        if (parentNodeId == -1 || (parentNodeId&NODE_TYPE_MSK)==NODE_TYPE_REQUEST) {
            throw new IllegalStateException("Request parent is no longer a node: " + WorldEngine.pprintPos(request.position()) + " " + parentNodeId);
        }
        int parentNodeType = parentNodeId&NODE_TYPE_MSK;
        parentNodeId &= NODE_ID_MSK;
        if (!this.nodeData.isNodeRequestInFlight(parentNodeId) || this.nodeData.getNodeRequest(parentNodeId) != requestId) {
            throw new IllegalStateException("Request is not owned by its parent: " + requestId);
        }

        if (request.requiredMask() == 0) {
            this.releaseChildRequest(parentNodeId, requestId);
            this.invalidateNode(parentNodeId);
            return;
        }
        if (parentNodeType==NODE_TYPE_LEAF) {
            int msk = request.requiredMask();
            int base = this.nodeData.allocate(Integer.bitCount(msk));
            int offset = -1;
            for (int childIdx = 0; childIdx < 8; childIdx++) {
                if ((msk&(1<<childIdx)) == 0) {
                    continue;
                }
                offset++;

                long childPos = makeChildPos(request.position(), childIdx);
                int childNodeId = base+offset;
                //Fill in node
                this.nodeData.setNodePosition(childNodeId, childPos);
                byte childExistence = request.childExistence(childIdx);
                if (childExistence == 0) {
                    //This is an ok error if it happens the request with a child state should never be zero


                    //TODO: make into warning or log error
                    Logger.warn("Request result with child existence of 0, for child pos " + WorldEngine.pprintPos(childPos));
                }
                this.nodeData.setNodeChildExistence(childNodeId, childExistence);
                this.nodeData.setNodeGeometry(childNodeId, request.mesh(childIdx));
                //Mark for update
                this.invalidateNode(childNodeId);

                //Put in map
                int pid = this.activeSectionMap.put(childPos, childNodeId|NODE_TYPE_LEAF);
                if (pid != (requestId|NODE_TYPE_REQUEST|REQUEST_TYPE_CHILD)) {
                    throw new IllegalStateException("Put node in map from request but type was not request: " + pid + " " + WorldEngine.pprintPos(childPos));
                }

                this.clearAllocId(childNodeId);
            }
            //Update the parent
            this.nodeData.setChildPtr(parentNodeId, base);
            this.nodeData.setChildPtrCount(parentNodeId, Integer.bitCount(msk));
            this.releaseChildRequest(parentNodeId, requestId);

            //Change it from a leaf to an inner node
            //Set the type from leaf to inner node
            if (this.activeSectionMap.put(request.position(), NODE_TYPE_INNER|parentNodeId) != (NODE_TYPE_LEAF|parentNodeId)) {
                throw new IllegalStateException();
            }
            this.invalidateNode(parentNodeId);
            this.nodeData.setAllChildrenAreLeaf(parentNodeId, true);

            //TODO: Need to set AllChildrenAreLeaf of the parent of the parent to false
            //Update the parentParent that all the children are leaf
            if (!this.topLevelNodes.contains(request.position())) {
                int ppnId = this.activeSectionMap.get(makeParentPos(request.position()));
                if ((ppnId&NODE_TYPE_MSK) != NODE_TYPE_INNER) {
                    throw new IllegalStateException();
                }
                //Since this node isnt a leaf node anymore
                this.nodeData.setAllChildrenAreLeaf(ppnId&NODE_ID_MSK, false);
            }
        } else if (parentNodeType==NODE_TYPE_INNER) {
            //For this, only need to add the nodes to the existing child set thing (shuffle around whatever) dont ever have to remove nodes

            int oldChildPtr = this.nodeData.getChildPtr(parentNodeId);
            int oldChildCnt = this.nodeData.getChildPtrCount(parentNodeId);
            if (oldChildPtr == -1) {
                throw new IllegalStateException();
            }

            int existingChildMsk = 0;

            //If the pointer is the empty ptr, dont check the count
            if (oldChildPtr != SENTINEL_EMPTY_CHILD_PTR) {
                //Ok so technically, it _is ok_ to just add to the end of the childPtr, however, imo that is stupid
                // and it should follow the logical allocation with respect to the 8 child indices
                // this means, need to extract the child indices already in the ptr (or technically could use the child existance? but having both and doing verification would be good)

                for (int i = 0; i < oldChildCnt; i++) {
                    if (!this.nodeData.nodeExists(i + oldChildPtr)) {
                        throw new IllegalStateException();
                    }
                    existingChildMsk |= 1 << getChildIdx(this.nodeData.nodePosition(i + oldChildPtr));
                }
            }
            int reqMsk = request.requiredMask();
            if ((byte) (existingChildMsk|reqMsk) != this.nodeData.getNodeChildExistence(parentNodeId)) {
                    throw new IllegalStateException("node data existence state does not match pointer mask");
            }


            if ((reqMsk&existingChildMsk)!=0) {
                throw new IllegalStateException("Overlapping child data!!! BAD");
            }

            //Create the new allocation
            int newMsk = reqMsk | existingChildMsk;
            int newChildPtr = this.nodeData.allocate(Integer.bitCount(newMsk));

            //Need to interlace the old and new data into the new allocation
            // FOR OLD ALLOCATIONS, NEED TO UPDATE POINTERS
            int childId = newChildPtr-1;
            int prevChildId = oldChildPtr-1;

            for (int i = 0; i < 8; i++) {
                if ((newMsk&(1<<i))==0) continue;
                childId++;

                if ((reqMsk&(1<<i))!=0) {

                    //Its an entry from the request
                    long childPos = makeChildPos(request.position(), i);

                    this.nodeData.setNodePosition(childId, childPos);
                    byte childExistence = request.childExistence(i);
                    if (childExistence == 0) {

                        //TODO: make into warning or log error


                    }
                    this.nodeData.setNodeChildExistence(childId, childExistence);
                    this.nodeData.setNodeGeometry(childId, request.mesh(i));

                    //Mark for update
                    this.invalidateNode(childId);

                    //Put in map
                    int pid = this.activeSectionMap.put(childPos, childId|NODE_TYPE_LEAF);
                    if (pid != (requestId|NODE_TYPE_REQUEST|REQUEST_TYPE_CHILD)) {
                        throw new IllegalStateException("Put node in map from request but type was not request: " + pid + " " + WorldEngine.pprintPos(childPos));
                    }
                    this.clearAllocId(childId);
                } else {
                    prevChildId++;

                    long pos = this.nodeData.nodePosition(prevChildId);

                    //Its a previous entry, copy it to its new location
                    this.nodeData.copyNode(prevChildId, childId);

                    this.clearAllocId(childId);
                    this.clearFreeId(prevChildId);

                    int prevNodeId = this.activeSectionMap.get(pos);
                    if ((prevNodeId&NODE_TYPE_MSK) == NODE_TYPE_REQUEST) {
                        throw new IllegalStateException();
                    }
                    if ((prevNodeId&NODE_ID_MSK) != prevChildId) {
                        throw new IllegalStateException("State inconsistency");
                    }
                    this.activeSectionMap.put(pos, (prevNodeId&NODE_TYPE_MSK)|childId);
                    //Need to invalidate the old and the new
                    this.invalidateNode(prevChildId);
                    this.invalidateNode(childId);
                }
            }

            //Do final steps

            //Free the old child data
            if (oldChildPtr != SENTINEL_EMPTY_CHILD_PTR) {
                this.nodeData.free(oldChildPtr, oldChildCnt);
            }

            //If the old ptr was sentinal null, this node is now pure leaf children
            if (oldChildPtr == SENTINEL_EMPTY_CHILD_PTR) {
                this.nodeData.setAllChildrenAreLeaf(parentNodeId, true);
            }

            //Update the parent
            this.nodeData.setChildPtr(parentNodeId, newChildPtr);
            this.nodeData.setChildPtrCount(parentNodeId, Integer.bitCount(newMsk));
            this.releaseChildRequest(parentNodeId, requestId);

            //Invalidate parent
            this.invalidateNode(parentNodeId);
        } else {
            throw new IllegalStateException();
        }
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
                Logger.warn("Tried processing a node that already has a request in flight: " + nodeId + " pos: " + WorldEngine.pprintPos(pos) + " ignoring");
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
            //Convert to leaf node
            this.recurseRemoveChildNodes(pPos);//TODO: make this download/fetch the data instead of just deleting it

            //Make node a leaf
            int old = this.activeSectionMap.put(pPos, NODE_TYPE_LEAF|pId);
            if (old == -1)
                throw new IllegalStateException();
            if ((old&NODE_TYPE_MSK)!=NODE_TYPE_INNER || (old&NODE_ID_MSK)!=pId)
                throw new IllegalStateException();

            //Mark all children as not leaf (as this is a leaf node)
            this.nodeData.setAllChildrenAreLeaf(pId, false);
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

    //==================================================================================================================
    public boolean writeChanges(GlBuffer nodeBuffer) {
        //TODO: use like compute based copy system or something
        // since microcopies are bad
        if (this.nodeUpdates.isEmpty()) {
            return false;
        }
        this.nodeUpdates.forEach((int i) -> this.writeNodeData(
                UploadStream.INSTANCE.upload(nodeBuffer, i * 16L, 16L), i));
        this.nodeUpdates.clear();
        return true;
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
