package me.cortex.voxy.client.core.rendering.selection;

import me.cortex.voxy.client.lod.MemoryBudget;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** Pooled primitive handoff from asynchronous GPU readback to the state thread. */
public final class SelectionBatch implements AutoCloseable {
    private static final byte[] EMPTY_BYTES = new byte[0];
    private static final float[] EMPTY_FLOATS = new float[0];
    private static final int[] EMPTY_INTS = new int[0];
    private static final long[] EMPTY_LONGS = new long[0];

    public enum Pass { CONSERVATIVE, REFINED }
    public enum Priority { COVERAGE, CURRENT_VIEW, PREDICTED }
    public enum Segment { DESIRED, RENDERABLE, REQUESTS }

    /** Bounded ownership pool. A batch is never reused before its consumer closes it. */
    public static final class Pool {
        private final int maximum;
        private final ArrayDeque<SelectionBatch> available = new ArrayDeque<>();
        private MemoryBudget memory;
        private int created;
        private long allocatedBytes;

        public Pool(int maximum) {
            if (maximum <= 0) throw new IllegalArgumentException("pool maximum must be positive");
            this.maximum = maximum;
        }

        public synchronized void bindMemory(MemoryBudget memory) {
            Objects.requireNonNull(memory, "memory");
            if (this.memory == memory) return;
            if (this.memory != null) {
                throw new IllegalStateException("another session still owns selection batches");
            }
            this.memory = memory;
        }

        public synchronized void unbindMemory(MemoryBudget memory) {
            if (this.memory != memory) return;
            this.memory = null;
            while (!this.available.isEmpty()) dispose(this.available.removeFirst());
        }

        public synchronized SelectionBatch acquire(SelectionManifest manifest, int inputCapacity,
                                                    int outputCapacity) {
            Objects.requireNonNull(manifest, "manifest");
            if (this.memory == null) return null;
            SelectionBatch batch = this.available.pollFirst();
            if (batch == null) {
                if (this.created >= this.maximum) return null;
                batch = new SelectionBatch(this);
                this.created++;
            }
            long before = batch.storageBytes();
            try {
                if (!batch.prepare(manifest, inputCapacity, outputCapacity, this.memory)) {
                    this.available.addFirst(batch);
                    return null;
                }
                this.allocatedBytes += batch.storageBytes() - before;
                return batch;
            } catch (RuntimeException | Error failure) {
                this.allocatedBytes -= before;
                this.created--;
                batch.dispose();
                throw failure;
            }
        }

        private synchronized void release(SelectionBatch batch) {
            batch.reset();
            if (this.memory != batch.memory) {
                dispose(batch);
                return;
            }
            SelectionBatch retained = this.available.peekFirst();
            if (retained == null) {
                this.available.addFirst(batch);
            } else if (batch.storageBytes() > retained.storageBytes()) {
                this.available.removeFirst();
                dispose(retained);
                this.available.addFirst(batch);
            } else {
                dispose(batch);
            }
        }

        synchronized void offerLatest(AtomicReference<SelectionBatch> handoff,
                                      SelectionBatch batch) {
            if (batch.owner == this && this.memory != batch.memory) {
                batch.close();
                return;
            }
            while (true) {
                SelectionBatch previous = handoff.get();
                if (previous != null
                        && Long.compareUnsigned(previous.sequence(), batch.sequence()) >= 0) {
                    batch.close();
                    return;
                }
                if (handoff.compareAndSet(previous, batch)) {
                    if (previous != null) previous.close();
                    return;
                }
            }
        }

        private void dispose(SelectionBatch batch) {
            this.allocatedBytes -= batch.storageBytes();
            this.created--;
            batch.dispose();
        }

        public synchronized int created() { return this.created; }
        public synchronized long allocatedBytes() { return this.allocatedBytes; }
    }

    private final Pool owner;
    private MemoryBudget memory;
    private MemoryBudget.Reservation storageMemory;
    private long accountedBytes;
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
    private int[] outputNodeIndexes = EMPTY_INTS;
    private int[] outputHandles = EMPTY_INTS;
    private long[] outputKeys = EMPTY_LONGS;
    private byte[] outputPriorities = EMPTY_BYTES;
    private float[] outputScores = EMPTY_FLOATS;
    private long[] outputMasks = EMPTY_LONGS;

    private final int[] inputOffsets = new int[2];
    private final int[] inputCounts = new int[2];
    private int inputSize;
    private int[] inputNodeIndexes = EMPTY_INTS;
    private float[] inputScores = EMPTY_FLOATS;
    private long[] inputMasks = EMPTY_LONGS;

    int[] candidateNodeIndexesA = EMPTY_INTS;
    int[] candidateNodeIndexesB = EMPTY_INTS;
    float[] candidateScoresA = EMPTY_FLOATS;
    float[] candidateScoresB = EMPTY_FLOATS;
    long[] candidateMasksA = EMPTY_LONGS;
    long[] candidateMasksB = EMPTY_LONGS;
    int[] candidateNext = EMPTY_INTS;
    int[] groupEpoch = EMPTY_INTS;
    int[] groupHead = EMPTY_INTS;
    int[] groupTail = EMPTY_INTS;
    int[] groupOrder = EMPTY_INTS;
    int groupingEpoch;
    int[] costEpoch = EMPTY_INTS;
    long[] costValues = EMPTY_LONGS;
    int costingEpoch;

    private SelectionBatch(Pool owner) { this.owner = owner; }

    public static SelectionBatch empty(long generation, long snapshotId, long sequence,
                                       int frameId, Pass pass, boolean complete) {
        SelectionBatch batch = new SelectionBatch(null);
        batch.manifest = new SelectionManifest(generation, snapshotId, 0, List.of());
        batch.released = false;
        batch.begin(generation, snapshotId, sequence, frameId, pass, complete);
        batch.beginSegment(Segment.DESIRED);
        batch.beginSegment(Segment.RENDERABLE);
        batch.beginSegment(Segment.REQUESTS);
        return batch;
    }

    private boolean prepare(SelectionManifest manifest, int inputCapacity, int outputCapacity,
                            MemoryBudget memory) {
        if (inputCapacity < 0 || outputCapacity < 0) {
            throw new IllegalArgumentException("negative selection capacity");
        }
        int inputs = grow(this.inputNodeIndexes.length, inputCapacity);
        int outputs = grow(this.outputNodeIndexes.length, outputCapacity);
        int candidates = grow(this.candidateNodeIndexesA.length, inputCapacity);
        int nodes = grow(this.groupEpoch.length, manifest.nodeHandleCapacity());
        int objects = grow(this.costEpoch.length, manifest.objectHandleCapacity());
        long requiredBytes = storageBytes(inputs, outputs, candidates, nodes, objects);
        MemoryBudget.Allocation allocation = MemoryBudget.Allocation.of(
                MemoryBudget.Pool.MANIFEST, requiredBytes);
        if (this.storageMemory == null) {
            this.storageMemory = memory.tryReserve(allocation).orElse(null);
            if (this.storageMemory == null) return false;
            this.memory = memory;
        } else {
            if (this.memory != memory) {
                throw new IllegalStateException("selection batch belongs to another memory budget");
            }
            if (!this.storageMemory.tryResizeTo(allocation)) return false;
        }
        this.accountedBytes = requiredBytes;

        if (inputs != this.inputNodeIndexes.length) {
            this.inputNodeIndexes = new int[inputs];
            this.inputScores = new float[inputs];
            this.inputMasks = new long[Math.multiplyExact(inputs, 3)];
        }
        if (outputs != this.outputNodeIndexes.length) {
            this.outputNodeIndexes = new int[outputs];
            this.outputHandles = new int[outputs];
            this.outputKeys = new long[outputs];
            this.outputPriorities = new byte[outputs];
            this.outputScores = new float[outputs];
            this.outputMasks = new long[Math.multiplyExact(outputs, 3)];
        }
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
        if (nodes != this.groupEpoch.length) {
            this.groupEpoch = new int[nodes];
            this.groupHead = new int[nodes];
            this.groupTail = new int[nodes];
            this.groupingEpoch = 0;
        }
        if (objects != this.costEpoch.length) {
            this.costEpoch = new int[objects];
            this.costValues = new long[objects];
            this.costingEpoch = 0;
        }
        this.manifest = manifest;
        this.released = false;
        this.inputSize = 0;
        this.outputSize = 0;
        Arrays.fill(this.inputCounts, 0);
        Arrays.fill(this.segmentCounts, 0);
        return true;
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
        return this.accountedBytes;
    }

    private void dispose() {
        reset();
        this.inputNodeIndexes = EMPTY_INTS;
        this.inputScores = EMPTY_FLOATS;
        this.inputMasks = EMPTY_LONGS;
        this.outputNodeIndexes = EMPTY_INTS;
        this.outputHandles = EMPTY_INTS;
        this.outputKeys = EMPTY_LONGS;
        this.outputPriorities = EMPTY_BYTES;
        this.outputScores = EMPTY_FLOATS;
        this.outputMasks = EMPTY_LONGS;
        this.candidateNodeIndexesA = EMPTY_INTS;
        this.candidateNodeIndexesB = EMPTY_INTS;
        this.candidateScoresA = EMPTY_FLOATS;
        this.candidateScoresB = EMPTY_FLOATS;
        this.candidateMasksA = EMPTY_LONGS;
        this.candidateMasksB = EMPTY_LONGS;
        this.candidateNext = EMPTY_INTS;
        this.groupEpoch = EMPTY_INTS;
        this.groupHead = EMPTY_INTS;
        this.groupTail = EMPTY_INTS;
        this.groupOrder = EMPTY_INTS;
        this.costEpoch = EMPTY_INTS;
        this.costValues = EMPTY_LONGS;
        this.groupingEpoch = 0;
        this.costingEpoch = 0;
        if (this.storageMemory != null) this.storageMemory.close();
        this.storageMemory = null;
        this.memory = null;
        this.accountedBytes = 0;
    }

    private static long storageBytes(int inputs, int outputs, int candidates,
                                     int nodes, int objects) {
        long bytes = 192;
        bytes = Math.addExact(bytes, arrayBytes(3, Integer.BYTES) * 2);
        bytes = Math.addExact(bytes, arrayBytes(2, Integer.BYTES) * 2);
        bytes = Math.addExact(bytes, arrayBytes(outputs, Integer.BYTES) * 2);
        bytes = Math.addExact(bytes, arrayBytes(outputs, Long.BYTES));
        bytes = Math.addExact(bytes, arrayBytes(outputs, Byte.BYTES));
        bytes = Math.addExact(bytes, arrayBytes(outputs, Float.BYTES));
        bytes = Math.addExact(bytes, arrayBytes(Math.multiplyExact(outputs, 3), Long.BYTES));
        bytes = Math.addExact(bytes, arrayBytes(inputs, Integer.BYTES));
        bytes = Math.addExact(bytes, arrayBytes(inputs, Float.BYTES));
        bytes = Math.addExact(bytes, arrayBytes(Math.multiplyExact(inputs, 3), Long.BYTES));
        bytes = Math.addExact(bytes, arrayBytes(candidates, Integer.BYTES) * 4);
        bytes = Math.addExact(bytes, arrayBytes(candidates, Float.BYTES) * 2);
        bytes = Math.addExact(bytes,
                arrayBytes(Math.multiplyExact(candidates, 3), Long.BYTES) * 2);
        bytes = Math.addExact(bytes, arrayBytes(nodes, Integer.BYTES) * 3);
        bytes = Math.addExact(bytes, arrayBytes(objects, Integer.BYTES));
        return Math.addExact(bytes, arrayBytes(objects, Long.BYTES));
    }

    private static long arrayBytes(int elements, int width) {
        long bytes = Math.addExact(16L, Math.multiplyExact((long) elements, width));
        return Math.addExact(bytes, 7) & ~7L;
    }

    private static int grow(int current, int required) {
        if (required <= current) return current;
        int value = Math.max(16, current);
        while (value < required) value = Math.multiplyExact(value, 2);
        return value;
    }
}
