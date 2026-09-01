package me.cortex.voxy.client.core.rendering.selection;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Pooled primitive handoff from asynchronous GPU readback to the state thread. */
public final class SelectionBatch implements AutoCloseable {
    public enum Pass { CONSERVATIVE, REFINED }
    public enum Priority { COVERAGE, CURRENT_VIEW, PREDICTED }
    public enum Segment { DESIRED, RENDERABLE, REQUESTS }

    /** Bounded ownership pool. A batch is never reused before its consumer closes it. */
    public static final class Pool {
        private final int maximum;
        private final ArrayDeque<SelectionBatch> available = new ArrayDeque<>();
        private int created;
        private long allocatedBytes;

        public Pool(int maximum) {
            if (maximum <= 0) throw new IllegalArgumentException("pool maximum must be positive");
            this.maximum = maximum;
        }

        public synchronized SelectionBatch acquire(SelectionManifest manifest, int inputCapacity,
                                                    int outputCapacity) {
            Objects.requireNonNull(manifest, "manifest");
            SelectionBatch batch = this.available.pollFirst();
            if (batch == null) {
                if (this.created >= this.maximum) return null;
                batch = new SelectionBatch(this);
                this.created++;
            }
            long before = batch.storageBytes();
            batch.prepare(manifest, inputCapacity, outputCapacity);
            this.allocatedBytes += batch.storageBytes() - before;
            return batch;
        }

        private synchronized void release(SelectionBatch batch) {
            batch.reset();
            this.available.addFirst(batch);
        }

        public synchronized int created() { return this.created; }
        public synchronized long allocatedBytes() { return this.allocatedBytes; }
    }

    private final Pool owner;
    private SelectionManifest manifest;
    private long generation;
    private long snapshotId;
    private long sequence;
    private int frameId;
    private Pass pass;
    private boolean frontierComplete;
    private boolean released = true;

    private final int[] segmentOffsets = new int[3];
    private final int[] segmentCounts = new int[3];
    private int outputSize;
    private int[] outputNodeIndexes = new int[0];
    private int[] outputHandles = new int[0];
    private long[] outputKeys = new long[0];
    private byte[] outputPriorities = new byte[0];
    private float[] outputScores = new float[0];
    private long[] outputMasks = new long[0];

    private final int[] inputOffsets = new int[2];
    private final int[] inputCounts = new int[2];
    private int inputSize;
    private int[] inputNodeIndexes = new int[0];
    private float[] inputScores = new float[0];
    private long[] inputMasks = new long[0];

    int[] candidateNodeIndexesA = new int[0];
    int[] candidateNodeIndexesB = new int[0];
    float[] candidateScoresA = new float[0];
    float[] candidateScoresB = new float[0];
    long[] candidateMasksA = new long[0];
    long[] candidateMasksB = new long[0];
    int[] candidateNext = new int[0];
    int[] groupEpoch = new int[0];
    int[] groupHead = new int[0];
    int[] groupTail = new int[0];
    int[] groupOrder = new int[0];
    int groupingEpoch;
    int[] costEpoch = new int[0];
    long[] costValues = new long[0];
    int costingEpoch;

    private SelectionBatch(Pool owner) { this.owner = owner; }

    public static SelectionBatch empty(long generation, long snapshotId, long sequence,
                                       int frameId, Pass pass, boolean complete) {
        SelectionBatch batch = new SelectionBatch(null);
        batch.prepare(new SelectionManifest(generation, snapshotId, 0, List.of()), 0, 0);
        batch.begin(generation, snapshotId, sequence, frameId, pass, complete);
        batch.beginSegment(Segment.DESIRED);
        batch.beginSegment(Segment.RENDERABLE);
        batch.beginSegment(Segment.REQUESTS);
        return batch;
    }

    private void prepare(SelectionManifest manifest, int inputCapacity, int outputCapacity) {
        if (inputCapacity < 0 || outputCapacity < 0) {
            throw new IllegalArgumentException("negative selection capacity");
        }
        this.manifest = manifest;
        int inputs = grow(this.inputNodeIndexes.length, inputCapacity);
        if (inputs != this.inputNodeIndexes.length) {
            this.inputNodeIndexes = new int[inputs];
            this.inputScores = new float[inputs];
            this.inputMasks = new long[Math.multiplyExact(inputs, 3)];
        }
        int outputs = grow(this.outputNodeIndexes.length, outputCapacity);
        if (outputs != this.outputNodeIndexes.length) {
            this.outputNodeIndexes = new int[outputs];
            this.outputHandles = new int[outputs];
            this.outputKeys = new long[outputs];
            this.outputPriorities = new byte[outputs];
            this.outputScores = new float[outputs];
            this.outputMasks = new long[Math.multiplyExact(outputs, 3)];
        }
        int candidates = grow(this.candidateNodeIndexesA.length, inputCapacity);
        if (candidates != this.candidateNodeIndexesA.length) {
            this.candidateNodeIndexesA = new int[candidates];
            this.candidateNodeIndexesB = new int[candidates];
            this.candidateScoresA = new float[candidates];
            this.candidateScoresB = new float[candidates];
            this.candidateMasksA = new long[Math.multiplyExact(candidates, 3)];
            this.candidateMasksB = new long[Math.multiplyExact(candidates, 3)];
            this.candidateNext = new int[candidates];
            this.groupOrder = new int[candidates];
        }
        int nodes = grow(this.groupEpoch.length, manifest.nodeHandleCapacity());
        if (nodes != this.groupEpoch.length) {
            this.groupEpoch = new int[nodes];
            this.groupHead = new int[nodes];
            this.groupTail = new int[nodes];
            this.groupingEpoch = 0;
        }
        int objects = grow(this.costEpoch.length, manifest.objectHandleCapacity());
        if (objects != this.costEpoch.length) {
            this.costEpoch = new int[objects];
            this.costValues = new long[objects];
            this.costingEpoch = 0;
        }
        this.released = false;
        this.inputSize = 0;
        this.outputSize = 0;
        Arrays.fill(this.inputCounts, 0);
        Arrays.fill(this.segmentCounts, 0);
    }

    public void begin(long generation, long snapshotId, long sequence, int frameId,
                      Pass pass, boolean frontierComplete) {
        ensureOwned();
        this.generation = generation;
        this.snapshotId = snapshotId;
        this.sequence = sequence;
        this.frameId = frameId;
        this.pass = Objects.requireNonNull(pass, "pass");
        this.frontierComplete = frontierComplete;
    }

    void setFrontierComplete(boolean value) { this.frontierComplete = value; }
    void beginInput(int queue) { this.inputOffsets[queue] = this.inputSize; this.inputCounts[queue] = 0; }

    void appendInput(int queue, int nodeIndex, float score,
                     long exterior, long interior, long complex) {
        int index = this.inputSize++;
        if (index >= this.inputNodeIndexes.length) throw new IllegalStateException("input overflow");
        this.inputNodeIndexes[index] = nodeIndex;
        this.inputScores[index] = score;
        int mask = index * 3;
        this.inputMasks[mask] = exterior;
        this.inputMasks[mask + 1] = interior;
        this.inputMasks[mask + 2] = complex;
        this.inputCounts[queue]++;
    }

    void beginSegment(Segment segment) {
        this.segmentOffsets[segment.ordinal()] = this.outputSize;
        this.segmentCounts[segment.ordinal()] = 0;
    }

    void append(Segment segment, int nodeIndex, Priority priority, float score,
                long exterior, long interior, long complex) {
        int index = this.outputSize++;
        if (index >= this.outputNodeIndexes.length) throw new IllegalStateException("output overflow");
        SelectionManifest.Node node = this.manifest.nodeAt(nodeIndex);
        this.outputNodeIndexes[index] = nodeIndex;
        this.outputHandles[index] = node.handle();
        this.outputKeys[index] = node.sectionKey();
        this.outputPriorities[index] = (byte) priority.ordinal();
        this.outputScores[index] = score;
        int mask = index * 3;
        this.outputMasks[mask] = exterior;
        this.outputMasks[mask + 1] = interior;
        this.outputMasks[mask + 2] = complex;
        this.segmentCounts[segment.ordinal()]++;
    }

    public SelectionManifest manifest() { ensureOwned(); return this.manifest; }
    public long generation() { ensureOwned(); return this.generation; }
    public long snapshotId() { ensureOwned(); return this.snapshotId; }
    public long sequence() { ensureOwned(); return this.sequence; }
    public int frameId() { ensureOwned(); return this.frameId; }
    public Pass pass() { ensureOwned(); return this.pass; }
    public boolean frontierComplete() { ensureOwned(); return this.frontierComplete; }
    public boolean permitsCancellation() { ensureOwned(); return this.frontierComplete; }
    public void disableCancellation() { ensureOwned(); this.frontierComplete = false; }
    public int count(Segment segment) { ensureOwned(); return this.segmentCounts[segment.ordinal()]; }
    public int nodeHandle(Segment segment, int row) { return this.outputHandles[absolute(segment, row)]; }
    public int nodeIndex(Segment segment, int row) { return this.outputNodeIndexes[absolute(segment, row)]; }
    public long sectionKey(Segment segment, int row) { return this.outputKeys[absolute(segment, row)]; }
    public Priority priority(Segment segment, int row) {
        return switch (this.outputPriorities[absolute(segment, row)]) {
            case 0 -> Priority.COVERAGE;
            case 1 -> Priority.CURRENT_VIEW;
            case 2 -> Priority.PREDICTED;
            default -> throw new IllegalStateException("invalid selection priority");
        };
    }
    public float score(Segment segment, int row) { return this.outputScores[absolute(segment, row)]; }
    public long selectedMask(Segment segment, int row,
                             SelectionManifest.ContentClass contentClass) {
        return this.outputMasks[absolute(segment, row) * 3 + contentClass.ordinal()];
    }
    public SelectionManifest.ContentState contentState(Segment segment, int row,
                                                       SelectionManifest.ContentClass contentClass) {
        return this.manifest.nodeAt(nodeIndex(segment, row)).content(contentClass);
    }

    int inputOffset(int queue) { return this.inputOffsets[queue]; }
    int inputCount(int queue) { return this.inputCounts[queue]; }
    int inputNodeIndex(int row) { return this.inputNodeIndexes[row]; }
    float inputScore(int row) { return this.inputScores[row]; }
    long inputMask(int row, int contentClass) { return this.inputMasks[row * 3 + contentClass]; }
    int outputOffset(Segment segment) { return this.segmentOffsets[segment.ordinal()]; }
    int[] outputNodeIndexesInternal() { return this.outputNodeIndexes; }
    int[] outputHandlesInternal() { return this.outputHandles; }
    long[] outputKeysInternal() { return this.outputKeys; }
    byte[] outputPrioritiesInternal() { return this.outputPriorities; }
    float[] outputScoresInternal() { return this.outputScores; }
    long[] outputMasksInternal() { return this.outputMasks; }

    private int absolute(Segment segment, int row) {
        ensureOwned();
        int count = this.segmentCounts[segment.ordinal()];
        if (row < 0 || row >= count) throw new IndexOutOfBoundsException(row);
        return this.segmentOffsets[segment.ordinal()] + row;
    }

    private void ensureOwned() {
        if (this.released) throw new IllegalStateException("selection batch has been released");
    }

    @Override
    public void close() {
        if (this.released) return;
        this.released = true;
        if (this.owner == null) reset(); else this.owner.release(this);
    }

    private void reset() {
        this.manifest = null;
        this.pass = null;
        this.inputSize = 0;
        this.outputSize = 0;
        Arrays.fill(this.inputCounts, 0);
        Arrays.fill(this.segmentCounts, 0);
    }

    long storageBytes() {
        return 4L * (this.outputNodeIndexes.length + this.outputHandles.length
                + this.inputNodeIndexes.length + this.candidateNodeIndexesA.length
                + this.candidateNodeIndexesB.length + this.candidateNext.length
                + this.groupEpoch.length + this.groupHead.length + this.groupTail.length
                + this.groupOrder.length + this.costEpoch.length)
                + 8L * (this.outputKeys.length + this.outputMasks.length + this.inputMasks.length
                + this.candidateMasksA.length + this.candidateMasksB.length
                + this.costValues.length)
                + 4L * (this.outputScores.length + this.inputScores.length
                + this.candidateScoresA.length + this.candidateScoresB.length)
                + this.outputPriorities.length;
    }

    private static int grow(int current, int required) {
        if (required <= current) return current;
        int value = Math.max(16, current);
        while (value < required) value = Math.multiplyExact(value, 2);
        return value;
    }
}
