#define SENTINAL_OUT_OF_BOUNDS uint(-1)

layout(location = NODE_QUEUE_INDEX_BINDING) uniform uint queueIdx;

layout(binding = NODE_QUEUE_META_BINDING, std430) restrict buffer NodeQueueMeta {
    uvec4 nodeQueueMetadata[MAX_ITERATIONS];
};

layout(binding = NODE_QUEUE_SOURCE_BINDING, std430) restrict readonly buffer NodeQueueSource {
    uint[] nodeQueueSource;
};

layout(binding = NODE_QUEUE_SINK_BINDING, std430) restrict writeonly buffer NodeQueueSink {
    uint[] nodeQueueSink;
};

uint getCurrentNode() {
    if (nodeQueueMetadata[queueIdx].w <= gl_GlobalInvocationID.x) {
        return SENTINAL_OUT_OF_BOUNDS;
    }
    return nodeQueueSource[gl_GlobalInvocationID.x];
}


//TODO: limit the size/writing out of bounds
uint nodePushIndex = -1;
void pushNodesInit(uint nodeCount) {
    uint index = atomicAdd(nodeQueueMetadata[queueIdx+1].w, nodeCount);
    //Increment first metadata value if it changes threash hold
    uint inc = ((index+LOCAL_SIZE)>>LOCAL_SIZE_BITS)-(index>>LOCAL_SIZE_BITS);
    atomicAdd(nodeQueueMetadata[queueIdx+1].x, inc);//TODO: see if making this conditional on inc != 0 is faster
    nodePushIndex = index;
}

void pushNode(uint nodeId) {
    nodeQueueSink[nodePushIndex++] = nodeId;
}
