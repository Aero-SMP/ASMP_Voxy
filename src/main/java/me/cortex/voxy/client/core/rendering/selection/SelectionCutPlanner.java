package me.cortex.voxy.client.core.rendering.selection;

import me.cortex.voxy.client.core.rendering.SectionKey;
import me.cortex.voxy.client.core.rendering.selection.SelectionBatch.Priority;
import me.cortex.voxy.client.core.rendering.selection.SelectionBatch.Segment;
import me.cortex.voxy.client.core.rendering.selection.SelectionManifest.ContentClass;
import me.cortex.voxy.client.core.rendering.selection.SelectionManifest.ContentState;
import me.cortex.voxy.client.core.rendering.selection.SelectionManifest.Node;

import java.util.Arrays;
import java.util.BitSet;

/** Allocation-free desired-cut planner operating on a consumer-owned primitive batch. */
final class SelectionCutPlanner {
    private SelectionCutPlanner() {}

    static void plan(SelectionManifest manifest, SelectionBatch batch) {
        int currentCount = batch.inputCount(0);
        int currentOffset = batch.inputOffset(0);
        for (int index = 0; index < currentCount; index++) {
            int source = currentOffset + index;
            batch.candidateNodeIndexesA[index] = batch.inputNodeIndex(source);
            batch.candidateScoresA[index] = batch.inputScore(source);
            copyMasks(batch, source, batch.candidateMasksA, index);
        }
        sortCandidates(manifest, batch.candidateNodeIndexesA, batch.candidateScoresA,
                batch.candidateMasksA, 0, currentCount - 1);

        batch.beginSegment(Segment.DESIRED);
        for (int index = 0; index < currentCount; index++) {
            appendCandidate(batch, Segment.DESIRED, batch.candidateNodeIndexesA,
                    batch.candidateScoresA, batch.candidateMasksA, index,
                    Priority.CURRENT_VIEW);
        }

        int[] sourceNodes = batch.candidateNodeIndexesA;
        float[] sourceScores = batch.candidateScoresA;
        long[] sourceMasks = batch.candidateMasksA;
        int[] targetNodes = batch.candidateNodeIndexesB;
        float[] targetScores = batch.candidateScoresB;
        long[] targetMasks = batch.candidateMasksB;
        int candidateCount = currentCount;

        for (int targetLod = 1; targetLod <= 4; targetLod++) {
            int epoch = nextGroupingEpoch(batch);
            int groupCount = 0;
            for (int candidate = 0; candidate < candidateCount; candidate++) {
                int ancestor = ancestorAtLod(manifest, sourceNodes[candidate], targetLod);
                int group = ancestor < 0 ? sourceNodes[candidate] : ancestor;
                if (batch.groupEpoch[group] != epoch) {
                    batch.groupEpoch[group] = epoch;
                    batch.groupHead[group] = candidate;
                    batch.groupTail[group] = candidate;
                    batch.groupOrder[groupCount++] = group;
                } else {
                    batch.candidateNext[batch.groupTail[group]] = candidate;
                    batch.groupTail[group] = candidate;
                }
                batch.candidateNext[candidate] = -1;
            }

            int nextCount = 0;
            for (int order = 0; order < groupCount; order++) {
                int parentIndex = batch.groupOrder[order];
                Node parent = manifest.nodeAt(parentIndex);
                int childCount = 0;
                boolean allRenderable = true;
                boolean hasFinerChild = false;
                boolean parentValid = true;
                float parentScore = 0;
                long exterior = 0, interior = 0, complex = 0;
                for (int child = batch.groupHead[parentIndex]; child >= 0;
                     child = batch.candidateNext[child]) {
                    childCount++;
                    Node childNode = manifest.nodeAt(sourceNodes[child]);
                    hasFinerChild |= SectionKey.level(childNode.sectionKey())
                            < SectionKey.level(parent.sectionKey());
                    allRenderable &= fullyRenderable(childNode, sourceMasks, child);
                    parentScore = Math.max(parentScore, sourceScores[child]);
                    long mapped = mapToAncestor(childNode, parent, sourceMasks[child * 3]);
                    if ((mapped & ~parent.exterior().availableMask()) != 0) parentValid = false;
                    exterior |= mapped;
                    mapped = mapToAncestor(childNode, parent, sourceMasks[child * 3 + 1]);
                    if ((mapped & ~parent.interior().availableMask()) != 0) parentValid = false;
                    interior |= mapped;
                    mapped = mapToAncestor(childNode, parent, sourceMasks[child * 3 + 2]);
                    if ((mapped & ~parent.complex().availableMask()) != 0) parentValid = false;
                    complex |= mapped;
                }
                boolean parentDemand = parentValid && hasFinerChild
                        && (exterior | interior | complex) != 0;
                boolean collapse = parentDemand && !allRenderable
                        && (fullyRenderable(parent, exterior, interior, complex)
                        || childCount > 1
                        || missingBytes(batch, parent, exterior, interior, complex)
                        < missingBytes(batch, manifest, sourceNodes, sourceMasks,
                                batch.groupHead[parentIndex], batch.candidateNext));
                if (collapse) {
                    targetNodes[nextCount] = parentIndex;
                    targetScores[nextCount] = parentScore;
                    int mask = nextCount * 3;
                    targetMasks[mask] = exterior;
                    targetMasks[mask + 1] = interior;
                    targetMasks[mask + 2] = complex;
                    nextCount++;
                } else {
                    for (int child = batch.groupHead[parentIndex]; child >= 0;
                         child = batch.candidateNext[child]) {
                        targetNodes[nextCount] = sourceNodes[child];
                        targetScores[nextCount] = sourceScores[child];
                        System.arraycopy(sourceMasks, child * 3, targetMasks, nextCount * 3, 3);
                        nextCount++;
                    }
                }
            }
            int[] swapNodes = sourceNodes; sourceNodes = targetNodes; targetNodes = swapNodes;
            float[] swapScores = sourceScores; sourceScores = targetScores; targetScores = swapScores;
            long[] swapMasks = sourceMasks; sourceMasks = targetMasks; targetMasks = swapMasks;
            candidateCount = nextCount;
        }

        batch.beginSegment(Segment.RENDERABLE);
        for (int index = 0; index < candidateCount; index++) {
            Node node = manifest.nodeAt(sourceNodes[index]);
            if (fullyRenderable(node, sourceMasks, index)) {
                appendCandidate(batch, Segment.RENDERABLE, sourceNodes, sourceScores,
                        sourceMasks, index, Priority.CURRENT_VIEW);
            }
        }
        sortOutput(batch, Segment.RENDERABLE);

        batch.beginSegment(Segment.REQUESTS);
        for (int index = 0; index < candidateCount; index++) {
            Node node = manifest.nodeAt(sourceNodes[index]);
            if (!fullyRenderable(node, sourceMasks, index)
                    && hasRequestableWork(node, sourceMasks, index)) {
                appendCandidate(batch, Segment.REQUESTS, sourceNodes, sourceScores,
                        sourceMasks, index, Priority.COVERAGE);
            }
        }
        for (int index = 0; index < currentCount; index++) {
            int source = currentOffset + index;
            int nodeIndex = batch.inputNodeIndex(source);
            long exterior = batch.inputMask(source, 0);
            long interior = batch.inputMask(source, 1);
            long complex = batch.inputMask(source, 2);
            Node node = manifest.nodeAt(nodeIndex);
            if (hasRequestableWork(node, exterior, interior, complex)) {
                batch.append(Segment.REQUESTS, nodeIndex, Priority.CURRENT_VIEW,
                        batch.inputScore(source), exterior, interior, complex);
            }
        }
        int predictedOffset = batch.inputOffset(1);
        int predictedCount = batch.inputCount(1);
        for (int index = 0; index < predictedCount; index++) {
            int source = predictedOffset + index;
            int nodeIndex = batch.inputNodeIndex(source);
            long exterior = batch.inputMask(source, 0);
            long interior = batch.inputMask(source, 1);
            long complex = batch.inputMask(source, 2);
            Node node = manifest.nodeAt(nodeIndex);
            if (hasRequestableWork(node, exterior, interior, complex)) {
                batch.append(Segment.REQUESTS, nodeIndex, Priority.PREDICTED,
                        batch.inputScore(source), exterior, interior, complex);
            }
        }
        sortOutput(batch, Segment.REQUESTS);
    }

    private static void copyMasks(SelectionBatch batch, int input, long[] target, int row) {
        target[row * 3] = batch.inputMask(input, 0);
        target[row * 3 + 1] = batch.inputMask(input, 1);
        target[row * 3 + 2] = batch.inputMask(input, 2);
    }

    private static void appendCandidate(SelectionBatch batch, Segment segment, int[] nodes,
                                        float[] scores, long[] masks, int row,
                                        Priority priority) {
        int mask = row * 3;
        batch.append(segment, nodes[row], priority, scores[row], masks[mask],
                masks[mask + 1], masks[mask + 2]);
    }

    private static int ancestorAtLod(SelectionManifest manifest, int nodeIndex, int targetLod) {
        Node node = manifest.nodeAt(nodeIndex);
        if (SectionKey.level(node.sectionKey()) > targetLod) return -1;
        while (SectionKey.level(node.sectionKey()) < targetLod) {
            nodeIndex = manifest.indexForHandle(node.parentHandle());
            if (nodeIndex < 0) return -1;
            node = manifest.nodeAt(nodeIndex);
        }
        return nodeIndex;
    }

    private static boolean fullyRenderable(Node node, long[] masks, int row) {
        int offset = row * 3;
        return fullyRenderable(node, masks[offset], masks[offset + 1], masks[offset + 2]);
    }

    private static boolean fullyRenderable(Node node, long exterior, long interior, long complex) {
        return (exterior | interior | complex) != 0
                && (exterior & ~node.exterior().renderableMask()) == 0
                && (interior & ~node.interior().renderableMask()) == 0
                && (complex & ~node.complex().renderableMask()) == 0;
    }

    private static boolean hasRequestableWork(Node node, long[] masks, int row) {
        int offset = row * 3;
        return hasRequestableWork(node, masks[offset], masks[offset + 1], masks[offset + 2]);
    }

    private static boolean hasRequestableWork(Node node, long exterior, long interior,
                                              long complex) {
        return requestable(node.exterior(), exterior) || requestable(node.interior(), interior)
                || requestable(node.complex(), complex);
    }

    private static boolean requestable(ContentState state, long selected) {
        if (selected == 0) return false;
        if ((selected & ~(state.residentMask() | state.inFlightMask())) != 0) return true;
        if (hasMissing(state.residentDependenciesInternal(),
                state.inFlightDependenciesInternal(), state.dependencyHandlesInternal().length)) {
            return true;
        }
        int[] sources = state.neighborDependencySourcesInternal();
        BitSet resident = state.residentNeighborDependenciesInternal();
        BitSet inFlight = state.inFlightNeighborDependenciesInternal();
        for (int index = 0; index < sources.length; index++) {
            if ((selected & 1L << sources[index]) != 0
                    && !resident.get(index) && !inFlight.get(index)) return true;
        }
        return false;
    }

    private static boolean hasMissing(BitSet resident, BitSet inFlight, int count) {
        for (int index = 0; index < count; index++) {
            if (!resident.get(index) && !inFlight.get(index)) return true;
        }
        return false;
    }

    private static long missingBytes(SelectionBatch batch, SelectionManifest manifest,
                                     int[] nodes, long[] masks, int first, int[] next) {
        int epoch = nextCostingEpoch(batch);
        long total = 0;
        for (int row = first; row >= 0; row = next[row]) {
            Node node = manifest.nodeAt(nodes[row]);
            total = addNodeCosts(batch, epoch, total, node, masks[row * 3],
                    masks[row * 3 + 1], masks[row * 3 + 2]);
            if (total == Long.MAX_VALUE) return total;
        }
        return total;
    }

    private static long missingBytes(SelectionBatch batch, Node node, long exterior,
                                     long interior, long complex) {
        int epoch = nextCostingEpoch(batch);
        return addNodeCosts(batch, epoch, 0, node, exterior, interior, complex);
    }

    private static long addNodeCosts(SelectionBatch batch, int epoch, long total, Node node,
                                     long exterior, long interior, long complex) {
        total = addContentCosts(batch, epoch, total, node.exterior(), exterior);
        total = addContentCosts(batch, epoch, total, node.interior(), interior);
        return addContentCosts(batch, epoch, total, node.complex(), complex);
    }

    private static long addContentCosts(SelectionBatch batch, int epoch, long total,
                                        ContentState state, long selected) {
        if (selected == 0 || total == Long.MAX_VALUE) return total;
        long unit = Math.max(1L, divideCeil(state.estimatedCanonicalBytes(),
                Math.max(1, Long.bitCount(state.availableMask()))));
        long requestable = selected & ~(state.residentMask() | state.inFlightMask());
        int denseIndex = 0;
        int[] objects = state.objectHandlesInternal();
        for (int microtile = 0; microtile < Long.SIZE; microtile++) {
            long bit = 1L << microtile;
            if ((state.availableMask() & bit) == 0) continue;
            int handle = objects[denseIndex++];
            if ((requestable & bit) != 0) total = addCost(batch, epoch, total, handle, unit);
        }
        long dependencyUnit = Math.max(1024L, unit >>> 2);
        int[] dependencies = state.dependencyHandlesInternal();
        for (int index = 0; index < dependencies.length; index++) {
            if (!state.residentDependenciesInternal().get(index)
                    && !state.inFlightDependenciesInternal().get(index)) {
                total = addCost(batch, epoch, total, dependencies[index], dependencyUnit);
            }
        }
        int[] neighbors = state.neighborDependencyHandlesInternal();
        int[] sources = state.neighborDependencySourcesInternal();
        for (int index = 0; index < neighbors.length; index++) {
            if ((selected & 1L << sources[index]) != 0
                    && !state.residentNeighborDependenciesInternal().get(index)
                    && !state.inFlightNeighborDependenciesInternal().get(index)) {
                total = addCost(batch, epoch, total, neighbors[index], dependencyUnit);
            }
        }
        return total;
    }

    private static long addCost(SelectionBatch batch, int epoch, long total,
                                int handle, long value) {
        if (batch.costEpoch[handle] == epoch) {
            long previous = batch.costValues[handle];
            if (value <= previous) return total;
            value -= previous;
            batch.costValues[handle] += value;
        } else {
            batch.costEpoch[handle] = epoch;
            batch.costValues[handle] = value;
        }
        return Long.MAX_VALUE - total < value ? Long.MAX_VALUE : total + value;
    }

    private static int nextGroupingEpoch(SelectionBatch batch) {
        if (++batch.groupingEpoch == 0) {
            Arrays.fill(batch.groupEpoch, 0);
            batch.groupingEpoch = 1;
        }
        return batch.groupingEpoch;
    }

    private static int nextCostingEpoch(SelectionBatch batch) {
        if (++batch.costingEpoch == 0) {
            Arrays.fill(batch.costEpoch, 0);
            batch.costingEpoch = 1;
        }
        return batch.costingEpoch;
    }

    private static long divideCeil(long value, long divisor) {
        return value / divisor + (value % divisor == 0 ? 0 : 1);
    }

    private static long mapToAncestor(Node descendant, Node ancestor, long mask) {
        int sourceLod = SectionKey.level(descendant.sectionKey());
        int targetLod = SectionKey.level(ancestor.sectionKey());
        if (targetLod < sourceLod) throw new IllegalArgumentException("target is not an ancestor");
        long result = 0;
        for (int microtile = 0; microtile < 64; microtile++) {
            if ((mask & 1L << microtile) == 0) continue;
            int x = inverseMorton(microtile, 0);
            int y = inverseMorton(microtile, 1);
            int z = inverseMorton(microtile, 2);
            int parentX = ancestorCoordinate(SectionKey.x(descendant.sectionKey()), x, sourceLod,
                    SectionKey.x(ancestor.sectionKey()), targetLod);
            int parentY = ancestorCoordinate(SectionKey.y(descendant.sectionKey()), y, sourceLod,
                    SectionKey.y(ancestor.sectionKey()), targetLod);
            int parentZ = ancestorCoordinate(SectionKey.z(descendant.sectionKey()), z, sourceLod,
                    SectionKey.z(ancestor.sectionKey()), targetLod);
            if ((parentX | parentY | parentZ) < 0
                    || parentX > 3 || parentY > 3 || parentZ > 3) {
                throw new IllegalArgumentException("node is outside its declared ancestor");
            }
            result |= 1L << morton(parentX, parentY, parentZ);
        }
        return result;
    }

    private static int ancestorCoordinate(int node, int microtile, int lod,
                                          int ancestor, int ancestorLod) {
        long coordinate = ((long) node * 4 + microtile) << lod;
        long origin = ((long) ancestor * 4) << ancestorLod;
        return Math.toIntExact(Math.floorDiv(coordinate - origin, 1L << ancestorLod));
    }

    private static int inverseMorton(int value, int axis) {
        return (value >>> axis & 1) | (value >>> (axis + 3) & 1) << 1;
    }

    private static int morton(int x, int y, int z) {
        return (x & 1) | (y & 1) << 1 | (z & 1) << 2
                | (x & 2) << 2 | (y & 2) << 3 | (z & 2) << 4;
    }

    private static void sortCandidates(SelectionManifest manifest, int[] nodes, float[] scores,
                                       long[] masks, int left, int right) {
        int low = left, high = right;
        if (low >= high) return;
        int pivot = (low + high) >>> 1;
        float pivotScore = scores[pivot];
        int pivotHandle = manifest.nodeAt(nodes[pivot]).handle();
        while (low <= high) {
            while (compare(scores[low], manifest.nodeAt(nodes[low]).handle(),
                    pivotScore, pivotHandle) < 0) low++;
            while (compare(scores[high], manifest.nodeAt(nodes[high]).handle(),
                    pivotScore, pivotHandle) > 0) high--;
            if (low <= high) {
                swap(nodes, low, high);
                swap(scores, low, high);
                swapMasks(masks, low, high);
                low++; high--;
            }
        }
        if (left < high) sortCandidates(manifest, nodes, scores, masks, left, high);
        if (low < right) sortCandidates(manifest, nodes, scores, masks, low, right);
    }

    private static void sortOutput(SelectionBatch batch, Segment segment) {
        int count = batch.count(segment);
        if (count < 2) return;
        int offset = batch.outputOffset(segment);
        sortOutput(batch, offset, offset + count - 1);
    }

    private static void sortOutput(SelectionBatch batch, int left, int right) {
        int low = left, high = right;
        if (low >= high) return;
        int pivot = (low + high) >>> 1;
        byte pivotPriority = batch.outputPrioritiesInternal()[pivot];
        float pivotScore = batch.outputScoresInternal()[pivot];
        int pivotHandle = batch.outputHandlesInternal()[pivot];
        while (low <= high) {
            while (compare(batch, low, pivotPriority, pivotScore, pivotHandle) < 0) low++;
            while (compare(batch, high, pivotPriority, pivotScore, pivotHandle) > 0) high--;
            if (low <= high) {
                swap(batch.outputNodeIndexesInternal(), low, high);
                swap(batch.outputHandlesInternal(), low, high);
                swap(batch.outputKeysInternal(), low, high);
                swap(batch.outputPrioritiesInternal(), low, high);
                swap(batch.outputScoresInternal(), low, high);
                swapMasks(batch.outputMasksInternal(), low, high);
                low++; high--;
            }
        }
        if (left < high) sortOutput(batch, left, high);
        if (low < right) sortOutput(batch, low, right);
    }

    private static int compare(SelectionBatch batch, int index, byte priority,
                               float score, int handle) {
        int byPriority = Byte.compare(batch.outputPrioritiesInternal()[index], priority);
        return byPriority != 0 ? byPriority
                : compare(batch.outputScoresInternal()[index],
                batch.outputHandlesInternal()[index], score, handle);
    }

    private static int compare(float leftScore, int leftHandle,
                               float rightScore, int rightHandle) {
        int byScore = Float.compare(rightScore, leftScore);
        return byScore != 0 ? byScore : Integer.compare(leftHandle, rightHandle);
    }

    private static void swap(int[] values, int left, int right) {
        int value = values[left]; values[left] = values[right]; values[right] = value;
    }
    private static void swap(long[] values, int left, int right) {
        long value = values[left]; values[left] = values[right]; values[right] = value;
    }
    private static void swap(float[] values, int left, int right) {
        float value = values[left]; values[left] = values[right]; values[right] = value;
    }
    private static void swap(byte[] values, int left, int right) {
        byte value = values[left]; values[left] = values[right]; values[right] = value;
    }
    private static void swapMasks(long[] masks, int left, int right) {
        for (int content = 0; content < 3; content++) {
            swap(masks, left * 3 + content, right * 3 + content);
        }
    }
}
