package me.cortex.voxy.client.core.rendering.hierarchical;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntConsumer;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.gl.GlFence;
import me.cortex.voxy.client.core.gl.shader.Shader;
import me.cortex.voxy.client.core.gl.shader.ShaderType;
import me.cortex.voxy.client.core.rendering.building.BuiltSection;
import me.cortex.voxy.client.core.rendering.section.BasicAsyncGeometryManager;
import me.cortex.voxy.client.core.rendering.section.BasicSectionGeometryData;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.AllocationArena;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.util.UnsafeUtil;
import org.lwjgl.system.MemoryUtil;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.function.BooleanSupplier;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.StampedLock;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.lwjgl.opengl.ARBUniformBufferObject.glBindBufferBase;
import static org.lwjgl.opengl.GL30C.glUniform1ui;
import static org.lwjgl.opengl.GL42C.GL_UNIFORM_BARRIER_BIT;
import static org.lwjgl.opengl.GL42C.glMemoryBarrier;
import static org.lwjgl.opengl.GL43C.*;

//TODO: create an "async upload stream", that is, the upload stream is a raw mapped buffer pointer that can be written to
// which is then synced to the gpu on "render thread sync",


//An "async host" for a NodeManager, has specific synchonius entry and exit points
// this is done off thread to reduce the amount of work done on the render thread, improving frame stability and reducing runtime overhead
public class AsyncNodeManager {
    private static final long MAX_SYNC_GEOMETRY_BYTES = 16L << 20;
    private static final long[] BATCH_START_LATENCY_BUCKET_NANOS = {
            100_000L, 500_000L, 1_000_000L, 4_000_000L, 16_000_000L
    };
    private static final int[] BATCH_SECTION_BUCKET_LIMITS = {1, 4, 16, 64, 256};
    private static final ThreadMXBean THREAD_MX_BEAN = ManagementFactory.getThreadMXBean();
    private static final VarHandle RESULT_HANDLE;
    private static final VarHandle RESULT_CACHE_1_HANDLE;
    private static final VarHandle RESULT_CACHE_2_HANDLE;
    static {
        try {
            RESULT_HANDLE = MethodHandles.lookup().findVarHandle(AsyncNodeManager.class, "results", SyncResults.class);
            RESULT_CACHE_1_HANDLE = MethodHandles.lookup().findVarHandle(AsyncNodeManager.class, "resultCache1", SyncResults.class);
            RESULT_CACHE_2_HANDLE = MethodHandles.lookup().findVarHandle(AsyncNodeManager.class, "resultCache2", SyncResults.class);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    private final Thread thread;
    public final int maxNodeCount;
    private final long geometryCapacity;
    private volatile boolean running = true;
    private volatile Throwable uncaughtException;

    private final NodeManager manager;
    private final BasicAsyncGeometryManager geometryManager;
    private final BasicSectionGeometryData geometryData;

    /** A level-triggered notification, not an estimate of queued item count. */
    private final AtomicBoolean workPending = new AtomicBoolean();
    private final Object submissionLock = new Object();
    private final ArrayList<RendererTransaction> completedRendererTransactions = new ArrayList<>();
    private final ArrayList<RegionalSectionPublication> completedRegionalSectionPublications =
            new ArrayList<>();
    /**
     * Render-thread-owned completion queue. A result becoming visible to OpenGL is not the same
     * thing as its commands merely having been submitted: publication and retirement callbacks
     * are released only after the fence following their geometry and node-pointer writes signals.
     */
    private final ArrayDeque<GpuCompletion> gpuCompletions = new ArrayDeque<>();

    @SuppressWarnings("FieldMayBeFinal")
    private volatile SyncResults results = null, resultCache1 = new SyncResults(), resultCache2 = new SyncResults();


    //locals for during iteration
    private final IntOpenHashSet tlnIdChange = new IntOpenHashSet();//"Encoded" add/remove id, first bit indicates if its add or remove, 1 is add
    //Top bit indicates clear or reset
    private final IntOpenHashSet cleanerIdResetClear = new IntOpenHashSet();//Tells the cleaner if it needs to clear the id to 0, or reset the id to the current frame

    private boolean needsWaitForSync = false;
    private volatile boolean waitingForRenderSync;

    private final AtomicLong submittedRegionalBatches = new AtomicLong();
    private final AtomicLong submittedRegionalSections = new AtomicLong();
    private final AtomicLong maximumRegionalBatchSections = new AtomicLong();
    private final AtomicLongArray regionalBatchSectionBuckets =
            new AtomicLongArray(BATCH_SECTION_BUCKET_LIMITS.length + 1);
    private final LatencyCounters regionalBatchStartLatency =
            new LatencyCounters(BATCH_START_LATENCY_BUCKET_NANOS);
    private final AtomicLong managerWakeups = new AtomicLong();
    private final AtomicLong regionalRenderSyncs = new AtomicLong();
    private final AtomicLong regionalRenderSyncPublications = new AtomicLong();
    private final AtomicLong regionalRenderSyncBytes = new AtomicLong();
    private final AtomicLong maximumRegionalRenderSyncPublications = new AtomicLong();
    private final AtomicLong maximumRegionalRenderSyncBytes = new AtomicLong();
    private final AtomicLong regionalBatchSyncSplits = new AtomicLong();
    private final AtomicLong workerCpuNanos = new AtomicLong();
    private final AtomicLong workerIdleNanos = new AtomicLong();

    public AsyncNodeManager(int maxNodeCount, BasicSectionGeometryData geometryData) {
        //Note: geometry data is the data store/source, not the management, it is just a raw store of data
        // it MUST ONLY be accessed on the render thread
        // AsyncNodeManager will use an AsyncGeometryManager as the manager for the data store, and sync the results on the render thread
        this.geometryData = geometryData;
        this.geometryCapacity = geometryData.getGeometryCapacityBytes();

        this.maxNodeCount = maxNodeCount;
        enableThreadCpuTiming();

        this.thread = new Thread(()->{
            try {
                while (this.running) {
                    if (!this.awaitWork()) continue;
                    long cpuStart = currentThreadCpuNanos();
                    try {
                        this.run();
                    } finally {
                        long cpuEnd = currentThreadCpuNanos();
                        if (cpuStart >= 0 && cpuEnd >= cpuStart) {
                            this.workerCpuNanos.addAndGet(cpuEnd - cpuStart);
                        }
                    }
                }
            } catch (Exception e) {
                Logger.error("Critical error occurred in async processor, things will be broken", e);
                throw e;
            }
        });
        this.thread.setUncaughtExceptionHandler((t,e)->{
            if (e == null) {
                e = new RuntimeException("null throwable");
            }
            this.uncaughtException = e;
            this.running = false;
            this.regionalBatchHandoff.stop(e);
            this.notifyPublicationProgress();
        });
        this.thread.setName("Async Node Manager");

        this.geometryManager = new BasicAsyncGeometryManager(((BasicSectionGeometryData)geometryData).getMaxSectionCount(), this.geometryCapacity);

        this.manager = new NodeManager(maxNodeCount, this.geometryManager);

        //Dont do the move... is just to much effort
        this.manager.setClear(id -> {
            this.cleanerIdResetClear.remove(id);//Remove clear
            this.cleanerIdResetClear.add(id|(1<<31));//Add reset
        }, id -> {
            this.cleanerIdResetClear.remove(id|(1<<31));//Remove reset
            this.cleanerIdResetClear.add(id);//Add clear
        });
        this.manager.setTLNCallbacks(id->{
            if (!this.tlnIdChange.remove(id)) {
                if (!this.tlnIdChange.add(id|(1<<31))) {
                    throw new IllegalStateException();
                }
            }
        }, id -> {
            if (!this.tlnIdChange.remove(id|(1<<31))) {
                if (!this.tlnIdChange.add(id)) {
                    throw new IllegalStateException();
                }
            }
        });
    }

    /**
     * Consumes one level-triggered notification before doing work. An enqueue racing with the
     * transition to park either changes the flag or leaves an unpark permit, so it cannot be
     * lost. No timed coalescing is performed here: a complete producer batch is ready already.
     */
    private boolean awaitWork() {
        boolean notified = this.workPending.getAndSet(false);
        if (notified) return this.running;
        long idleStart = System.nanoTime();
        while (this.running) {
            LockSupport.park();
            this.managerWakeups.incrementAndGet();
            notified = this.workPending.getAndSet(false);
            if (notified) break;
        }
        this.workerIdleNanos.addAndGet(System.nanoTime() - idleStart);
        return this.running;
    }

    private static long currentThreadCpuNanos() {
        try {
            return THREAD_MX_BEAN.isCurrentThreadCpuTimeSupported()
                    ? THREAD_MX_BEAN.getCurrentThreadCpuTime() : -1;
        } catch (RuntimeException ignored) {
            return -1;
        }
    }

    private static void enableThreadCpuTiming() {
        try {
            if (THREAD_MX_BEAN.isThreadCpuTimeSupported()
                    && !THREAD_MX_BEAN.isThreadCpuTimeEnabled()) {
                THREAD_MX_BEAN.setThreadCpuTimeEnabled(true);
            }
        } catch (RuntimeException ignored) {
            // Unsupported or denied JVM diagnostics leave the aggregate at zero.
        }
    }

    private SyncResults getMakeResultObject() {
        SyncResults resultSet = (SyncResults)RESULT_CACHE_1_HANDLE.getAndSet(this, null);
        if (resultSet == null) {//Not in the first object
            resultSet = (SyncResults)RESULT_CACHE_2_HANDLE.getAndSet(this, null);
        }
        if (resultSet == null) {
            throw new IllegalStateException("There should always be an object in the result set cache pair");
        }
        //Reset everything to default
        resultSet.reset();
        return resultSet;
    }

    private final Shader scatterWrite = Shader.make()
            .define("INPUT_BUFFER_BINDING", 0)
            .define("OUTPUT_BUFFER1_BINDING", 1)
            .define("OUTPUT_BUFFER2_BINDING", 2)
            .add(ShaderType.COMPUTE, "voxy:util/scatter.comp")
            .compile();

    private final Shader multiMemcpy = Shader.make()
            .define("INPUT_HEADER_BUFFER_BINDING", 0)
            .define("INPUT_DATA_BUFFER_BINDING", 1)
            .define("OUTPUT_BUFFER_BINDING", 2)
            .add(ShaderType.COMPUTE, "voxy:util/memcpy.comp")
            .compile();

    private void run() {
        if (!this.running) {
            return;
        }


        int workDone = 0;
        boolean hierarchyAdvanced = false;

        {
            LongOpenHashSet add = null;
            LongOpenHashSet rem = null;
            long stamp = this.tlnLock.writeLock();

            if (!this.tlnAdd.isEmpty()) {
                add = new LongOpenHashSet(this.tlnAdd);
                this.tlnAdd.clear();
            }
            if (!this.tlnRem.isEmpty()) {
                rem = new LongOpenHashSet(this.tlnRem);
                this.tlnRem.clear();
            }

            this.tlnLock.unlockWrite(stamp);
            int work = 0;
            if (rem != null) {
                var iter = rem.longIterator();
                while (iter.hasNext()) {
                    this.manager.removeTopLevelNode(iter.nextLong());
                    work++;
                }
            }

            if (add != null) {
                var iter = add.longIterator();
                while (iter.hasNext()) {
                    this.manager.insertTopLevelNode(iter.nextLong());
                    work++;
                }
            }

            workDone += work;
            hierarchyAdvanced = work != 0;
        }

        int rendererTransactions = this.rendererTransactionQueue.size();
        boolean topologyDeferred = false;
        while (rendererTransactions-- > 0) {
            RendererTransaction transaction = this.rendererTransactionQueue.poll();
            if (transaction == null) break;
            try {
                boolean completed = switch (transaction.operation) {
                    case RETIRE -> this.manager.retirePublication(transaction.sourceRevision,
                            transaction.expectedRevision, transaction.positions.iterator().next());
                    case COMMIT -> {
                        this.manager.commitStagedRoot(
                                transaction.sourceRevision, transaction.positions);
                        yield true;
                    }
                    case ROLLBACK -> {
                        this.manager.rollbackStagedRoot(transaction.sourceRevision);
                        yield true;
                    }
                    case COMPLETE_ROLLBACK -> {
                        this.manager.completeRollback(transaction.sourceRevision);
                        yield true;
                    }
                    case FINALIZE -> this.manager.finalizeStagedRoot(transaction.sourceRevision);
                    case RELEASE_COARSEN -> {
                        this.manager.releaseCoarsened(transaction.sourceRevision);
                        yield true;
                    }
                    case COARSEN -> throw new IllegalStateException(
                            "coarsening must retain regional publication order");
                };
                if (!completed) {
                    this.rendererTransactionQueue.add(transaction);
                    topologyDeferred = true;
                    continue;
                }
                workDone++;
                hierarchyAdvanced = true;
                this.completedRendererTransactions.add(transaction);
            } catch (Throwable failure) {
                workDone++;
                transaction.failure.accept(failure);
            }
        }

        // Coarsening frees geometry and must not wait behind publications which may require that
        // capacity. Its release transaction still crosses the normal GPU fence before storage is
        // reclaimed.
        {
            int coarsenings = this.coarsenQueue.size();
            while (coarsenings-- > 0) {
                RendererTransaction transaction = this.coarsenQueue.peek();
                if (transaction == null) break;
                try {
                    long parent = transaction.positions.iterator().next();
                    if (!this.manager.coarsenSubtree(transaction.sourceRevision, parent)) {
                        topologyDeferred = true;
                        break;
                    }
                    this.coarsenQueue.poll();
                    workDone++;
                    hierarchyAdvanced = true;
                    this.completedRendererTransactions.add(transaction);
                } catch (Throwable failure) {
                    this.coarsenQueue.poll();
                    workDone++;
                    transaction.failure.accept(failure);
                }
            }
        }

        boolean regionalBatchLimited = false;
        while (!regionalBatchLimited) {
            RegionalSectionPublication publication = this.peekRegionalSectionPublication();
            if (publication == null) break;
            if (!publication.current.getAsBoolean()) {
                this.takeRegionalSectionPublication();
                workDone++;
                this.cancelRegionalSectionPublication(publication);
                continue;
            }
            if (!this.regionalSyncBatchHasRoom(publication)) {
                regionalBatchLimited = true;
                this.regionalBatchSyncSplits.incrementAndGet();
                break;
            }
            publication = this.takeRegionalSectionPublication();
            workDone++;
            hierarchyAdvanced |= this.processRegionalSectionPublication(publication);
        }

        if (workDone == 0) return;
        // Retry topology commands only after real hierarchy/fence/input progress.
        if (hierarchyAdvanced) {
            this.topologyGeneration.incrementAndGet();
            this.notifyPublicationProgress();
            if (topologyDeferred) this.workPending.set(true);
        }
        this.largestFreeGeometryUnits = this.geometryManager.getLargestFreeGeometryUnits();
        this.usedGeometrySections = this.geometryManager.getSectionCount();
        this.notifyPublicationProgress();
        if (this.needsWaitForSync) {
            while (RESULT_HANDLE.get(this) != null && this.running) {
                this.waitingForRenderSync = true;
                if (RESULT_HANDLE.get(this) == null || !this.running) break;
                long idleStart = System.nanoTime();
                LockSupport.park();
                this.workerIdleNanos.addAndGet(System.nanoTime() - idleStart);
            }
            this.waitingForRenderSync = false;
        }


        var prev = (SyncResults) RESULT_HANDLE.getAndSet(this, null);
        SyncResults results = null;
        if (prev == null) {
            this.needsWaitForSync = false;
            results = this.getMakeResultObject();
            //Clear old data (if it exists), create a new result set
            results.tlnDelta.addAll(this.tlnIdChange);
            this.tlnIdChange.clear();

            if (!this.geometryManager.getUploads().isEmpty()){//Put in new data into sync set
                var iter = this.geometryManager.getUploads().int2ObjectEntrySet().fastIterator();
                while (iter.hasNext()) {
                    var val = iter.next();
                    results.geometryUpload.upload(val.getIntKey(), val.getValue());
                    val.getValue().free();
                }
                this.geometryManager.getUploads().clear();
                this.geometryManager.uploadsDrained();
            }

            this.geometryManager.getHeapRemovals().clear();//We dont do removals on new data (as there is "none")
            results.cleanerOperations.addAll(this.cleanerIdResetClear); this.cleanerIdResetClear.clear();
        } else {
            results = prev;
            // merge with the previous result set

            if (!this.tlnIdChange.isEmpty()) {//Merge top level node id changes
                var iter = this.tlnIdChange.intIterator();
                while (iter.hasNext()) {
                    int val = iter.nextInt();
                    if (!results.tlnDelta.remove(val ^ (1 << 31))) {//Remove opposite
                        results.tlnDelta.add(val);//Add this if not added
                    }
                }
                this.tlnIdChange.clear();
            }

            if (!this.cleanerIdResetClear.isEmpty()) {//Merge top level node id changes
                var iter = this.cleanerIdResetClear.intIterator();
                while (iter.hasNext()) {
                    int val = iter.nextInt();
                    results.cleanerOperations.remove(val^(1<<31));//Remove opposite
                    results.cleanerOperations.add(val);//Add this
                }
                this.cleanerIdResetClear.clear();
            }

            if (!this.geometryManager.getHeapRemovals().isEmpty()) {//Remove and free all the removed geometry uploads
                var rem = this.geometryManager.getHeapRemovals();
                var iter = rem.intIterator();
                while (iter.hasNext()) {
                    results.geometryUpload.remove(iter.nextInt());
                }
                rem.clear();
            }

            if (!this.geometryManager.getUploads().isEmpty()) {//Add all the new uploads to the result set
                var add = this.geometryManager.getUploads();
                var iter = add.int2ObjectEntrySet().fastIterator();
                while (iter.hasNext()) {
                    var val = iter.next();
                    results.geometryUpload.upload(val.getIntKey(), val.getValue());
                    val.getValue().free();
                }
                add.clear();
                this.geometryManager.uploadsDrained();
            }
        }

        {//This is the same regardless of if is a merge or new result
            //Geometry id metadata updates
            if (!this.geometryManager.getUpdateIds().isEmpty()) {
                var ids = this.geometryManager.getUpdateIds();
                var iter = ids.intIterator();
                while (iter.hasNext()) {
                    int val = iter.nextInt();
                    int scatterAddr = (val*3)|(1<<31);//Since we write to the second buffer

                    // Three aligned writes retain full u32 bucket counts for dense sections.
                    long ptrA = results.getScatterWritePtr(scatterAddr+0, 2);
                    long ptrB = results.getScatterWritePtr(scatterAddr+1, 0);
                    long ptrC = results.getScatterWritePtr(scatterAddr+2, 0);

                    //Write update data
                    this.geometryManager.writeMetadataSplit(val, ptrA, ptrB, ptrC);
                }
                ids.clear();
            }

            //Node updates
            if (!this.manager.getNodeUpdates().isEmpty()) {
                var ids = this.manager.getNodeUpdates();
                var iter = ids.intIterator();
                while (iter.hasNext()) {
                    int val = iter.nextInt();
                    //Dont need to modify the write location since we write to buffer 0
                    long ptr = results.getScatterWritePtr(val);
                    //Write updated data
                    this.manager.writeNode(val, ptr);
                }
                ids.clear();
            }
        }

        results.rendererTransactions.addAll(this.completedRendererTransactions);
        this.completedRendererTransactions.clear();
        results.regionalSectionPublications.addAll(this.completedRegionalSectionPublications);
        this.completedRegionalSectionPublications.clear();

        results.geometrySectionCount = this.geometryManager.getSectionCount();
        results.usedGeometry = this.geometryManager.getGeometryUsedBytes();
        this.largestFreeGeometryUnits = this.geometryManager.getLargestFreeGeometryUnits();
        this.usedGeometrySections = results.geometrySectionCount;
        results.currentMaxNodeId = this.manager.getCurrentMaxNodeId();

        this.needsWaitForSync |= results.geometryUpload.currentElemCopyAmount*8L > 2L<<20;//2mb limit per frame
        this.needsWaitForSync |= results.cleanerOperations.size() > 1024;
        this.needsWaitForSync |= results.scatterWriteLocationMap.size() > 4096;
        this.needsWaitForSync |= results.tlnDelta.size() > 10;
        this.needsWaitForSync |= regionalBatchLimited;

        if (!RESULT_HANDLE.compareAndSet(this, null, results)) {
            throw new IllegalArgumentException("Should always have null");
        }

    }

    private boolean regionalSyncBatchHasRoom(RegionalSectionPublication publication) {
        int publications = this.completedRegionalSectionPublications.size();
        BuiltSection geometry = publication.geometry();
        long bytes = geometry.geometryBuffer == null ? 0 : geometry.geometryBuffer.size;
        return publications == 0
                || this.geometryManager.getPendingUploadBytes() + bytes
                <= MAX_SYNC_GEOMETRY_BYTES;
    }

    private RegionalSectionPublication peekRegionalSectionPublication() {
        if (this.activeRegionalBatch == null) {
            this.activeRegionalBatch = this.regionalBatchHandoff.take();
            if (this.activeRegionalBatch == null) return null;
            this.notifyPublicationProgress();
            this.activeRegionalBatchIndex = 0;
            this.regionalBatchStartLatency.record(System.nanoTime()
                    - this.activeRegionalBatch.enqueuedNanos());
        }
        return this.activeRegionalBatch.publications()[this.activeRegionalBatchIndex];
    }

    private RegionalSectionPublication takeRegionalSectionPublication() {
        RegionalSectionPublication publication = this.peekRegionalSectionPublication();
        if (publication == null) return null;
        if (++this.activeRegionalBatchIndex
                == this.activeRegionalBatch.publications().length) {
            this.activeRegionalBatch = null;
            this.activeRegionalBatchIndex = 0;
        }
        return publication;
    }

    /** Uploads one complete node, or retains it until its indexed parent path exists. */
    private boolean processRegionalSectionPublication(RegionalSectionPublication publication) {
        BuiltSection geometry = publication.geometry();
        boolean transferred = false;
        try {
            if (!publication.current.getAsBoolean()) {
                this.cancelRegionalSectionPublication(publication);
                return true;
            }
            PublicationProgress observed = this.publicationProgress();
            long geometryBytes = geometry.geometryBuffer == null ? 0 : geometry.geometryBuffer.size;
            long stagedBytes = UploadStream.alignUp(geometryBytes,
                    UploadStream.BASE_ALLOCATION_ALIGNEMENT)
                    + UploadStream.alignUp(16, UploadStream.BASE_ALLOCATION_ALIGNEMENT);
            if (stagedBytes > UploadStream.CAPACITY_BYTES) {
                throw new IllegalArgumentException("one regional section exceeds the render "
                        + "upload safety ceiling: " + geometryBytes + " bytes");
            }
            if (publication.previousRevision() >= 0) {
                if (!this.manager.finalizeStagedRoot(publication.previousRevision())) {
                    publication.blocked().accept(new RegionalAllocationBlock(geometry,
                            RegionalAllocationStatus.TOPOLOGY_NOT_READY, 0, 0,
                            geometry.position, observed));
                    return false;
                }
            }
            NodeManager.RendererFence staged;
            try {
                staged = this.manager.stageGeometryResult(geometry);
            } catch (BasicAsyncGeometryManager.GeometryAdmissionException blocked) {
                var admission = blocked.admission();
                RegionalAllocationStatus status = switch (admission.status()) {
                    case NO_CONTIGUOUS_GEOMETRY_SPACE ->
                            RegionalAllocationStatus.NO_CONTIGUOUS_GEOMETRY_SPACE;
                    case NO_SECTION_ID -> RegionalAllocationStatus.NO_SECTION_ID;
                    case IMPOSSIBLE -> RegionalAllocationStatus.IMPOSSIBLE;
                    case ACCEPTED -> throw new IllegalStateException(
                            "accepted allocation raised an admission exception");
                };
                publication.blocked().accept(new RegionalAllocationBlock(geometry, status,
                        admission.requiredUnits(), admission.largestFreeUnits(), geometry.position, observed));
                return false;
            }
            if (staged == null && this.manager.ensureHierarchyOwner(geometry.position)) {
                try {
                    staged = this.manager.stageGeometryResult(geometry);
                } catch (BasicAsyncGeometryManager.GeometryAdmissionException blocked) {
                    var admission = blocked.admission();
                    RegionalAllocationStatus status = switch (admission.status()) {
                        case NO_CONTIGUOUS_GEOMETRY_SPACE ->
                                RegionalAllocationStatus.NO_CONTIGUOUS_GEOMETRY_SPACE;
                        case NO_SECTION_ID -> RegionalAllocationStatus.NO_SECTION_ID;
                        case IMPOSSIBLE -> RegionalAllocationStatus.IMPOSSIBLE;
                        case ACCEPTED -> throw new IllegalStateException(
                                "accepted allocation raised an admission exception");
                    };
                    publication.blocked().accept(new RegionalAllocationBlock(geometry, status,
                            admission.requiredUnits(), admission.largestFreeUnits(), geometry.position, observed));
                    return false;
                }
            }
            if (staged == null) {
                RegionalAllocationStatus status = this.manager.hasTopLevelAncestor(
                        geometry.position) ? RegionalAllocationStatus.TOPOLOGY_NOT_READY
                        : RegionalAllocationStatus.STALE;
                publication.blocked().accept(new RegionalAllocationBlock(geometry, status, 0, 0,
                        this.manager.publicationPrerequisite(geometry.position), observed));
                return false;
            }
            transferred = true;
            this.usedGeometryAmount = this.geometryManager.getGeometryUsedBytes();
            publication.reserved.run();
            this.manager.commitStagedRoot(geometry.sourceRevision, Set.of(geometry.position));
            this.completedRegionalSectionPublications.add(publication);
        } catch (Throwable failure) {
            if (!transferred) geometry.free();
            try {
                this.manager.rollbackStagedRoot(geometry.sourceRevision);
            } catch (Throwable rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            publication.failure().accept(failure);
        }
        return true;
    }

    private void cancelRegionalSectionPublication(RegionalSectionPublication publication) {
        publication.geometry().free();
        publication.canceled().run();
    }

    private IntConsumer tlnAddCallback; private IntConsumer tlnRemoveCallback;
    //Render thread synchronization
    public void tick(GlBuffer nodeBuffer, NodeCleaner cleaner) {//TODO: dont pass nodeBuffer here??, do something else thats better
        if (this.uncaughtException != null) {
            throw new RuntimeException(this.uncaughtException);//Propagate internal exception
        }
        this.pollGpuCompletions();
        var results = (SyncResults)RESULT_HANDLE.getAndSet(this, null);//Acquire the results
        if (results == null) {//There are no new results to process, return
            return;
        }
        if (this.waitingForRenderSync) LockSupport.unpark(this.thread);

        //top level node add/remove
        if (!results.tlnDelta.isEmpty()) {
            var iter = results.tlnDelta.intIterator();
            while (iter.hasNext()) {
                int val = iter.nextInt();
                if ((val&(1<<31))!=0) {//Add node
                    this.tlnAddCallback.accept(val&(-1>>>1));
                } else {
                    this.tlnRemoveCallback.accept(val);
                }
            }
            //Dont need to clear as is not used again
        }

        {//Update basic geometry data
            var store = (BasicSectionGeometryData)this.geometryData;

            store.setSectionCount(results.geometrySectionCount);

            var upload = results.geometryUpload;
            if (!upload.dataUploadPoints.isEmpty()) {
                ((BasicSectionGeometryData)this.geometryData).ensureAccessible(upload.maxElementAccess);
                int copies = upload.dataUploadPoints.size();
                int upCopies = UploadStream.alignUpAlloc(copies*16);
                int scratchSize = (int) upload.arena.getSize() * 8;
                int upScratchSize = UploadStream.alignUpAlloc(scratchSize);
                long ptr = UploadStream.INSTANCE.rawUploadAddress(upScratchSize + upCopies);
                UnsafeUtil.memcpy(upload.scratchHeaderBuffer.address, UploadStream.INSTANCE.getBaseAddress() + ptr, copies * 16L);
                UnsafeUtil.memcpy(upload.scratchDataBuffer.address, UploadStream.INSTANCE.getBaseAddress() + ptr + upCopies, scratchSize);
                UploadStream.INSTANCE.commit();//Commit the buffer

                this.multiMemcpy.bind();
                glBindBufferRange(GL_SHADER_STORAGE_BUFFER, 0, UploadStream.INSTANCE.getRawBufferId(), ptr, upCopies);
                glBindBufferRange(GL_SHADER_STORAGE_BUFFER, 1, UploadStream.INSTANCE.getRawBufferId(), ptr+upCopies, upScratchSize);
                glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, ((BasicSectionGeometryData) this.geometryData).getGeometryBuffer().id);

                if (copies > 500) {
                    Logger.warn("Large amount of copies, lag will probably happen: " + copies);
                }

                glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
                glDispatchCompute(copies, 1, 1);//Execute the copies
                glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);

            }
        }

        if (!results.scatterWriteLocationMap.isEmpty()) {//Scatter write
            int count = results.scatterWriteLocationMap.size();//Number of writes, not chunks or uvec4 count
            int chunks = (count+3)/4;
            int streamSize = chunks*80;//80 bytes per chunk, it is guaranteed the buffer is big enough
            long ptr = UploadStream.INSTANCE.rawUploadAddress(streamSize);//Internally implicitly aligned alloc
            MemoryUtil.memCopy(results.scatterWriteBuffer.address, UploadStream.INSTANCE.getBaseAddress() + ptr, streamSize);
            UploadStream.INSTANCE.commit();//Commit the buffer

            this.scatterWrite.bind();
            glBindBufferRange(GL_SHADER_STORAGE_BUFFER, 0, UploadStream.INSTANCE.getRawBufferId(), ptr, UploadStream.alignUpAlloc(streamSize));
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, nodeBuffer.id);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, ((BasicSectionGeometryData) this.geometryData).getMetadataBuffer().id);
            glUniform1ui(0, count);
            glMemoryBarrier(GL_UNIFORM_BARRIER_BIT|GL_SHADER_STORAGE_BARRIER_BIT);
            glDispatchCompute((count+127)/128, 1, 1);
            glMemoryBarrier(GL_UNIFORM_BARRIER_BIT|GL_SHADER_STORAGE_BARRIER_BIT);
        }

        if (!results.cleanerOperations.isEmpty()) {
            cleaner.updateIds(results.cleanerOperations);
        }

        this.currentMaxNodeId = results.currentMaxNodeId;
        this.usedGeometryAmount = results.usedGeometry;

        if (!results.rendererTransactions.isEmpty()
                || !results.regionalSectionPublications.isEmpty()) {
            // glFenceSync is ordered after every upload, metadata scatter and top-level pointer
            // command emitted above. Polling happens on later render frames and never waits.
            long submittedNanos = System.nanoTime();
            for (RegionalSectionPublication publication
                    : results.regionalSectionPublications) {
                publication.timing().recordGpuUploadSubmitted(submittedNanos);
            }
            this.queueGpuCompletion(new ArrayList<>(results.rendererTransactions),
                    new ArrayList<>(results.regionalSectionPublications));
        }
        if (!results.regionalSectionPublications.isEmpty()) {
            this.recordRegionalRenderSync(results.regionalSectionPublications);
        }

        //Insert the result set into the cache
        if (!RESULT_CACHE_1_HANDLE.compareAndSet(this, null, results)) {
            //Failed to insert into result set 1, insert it into result set 2
            if (!RESULT_CACHE_2_HANDLE.compareAndSet(this, null, results)) {
                throw new IllegalStateException("Could not insert result into cache");
            }
        }
    }

    private void pollGpuCompletions() {
        while (!this.gpuCompletions.isEmpty()) {
            GpuCompletion completion = this.gpuCompletions.peekFirst();
            final boolean signaled;
            try {
                signaled = completion.fence.signaled();
            } catch (RuntimeException failure) {
                this.gpuCompletions.removeFirst();
                completion.fence.free();
                this.failGpuCompletion(completion, failure);
                Logger.error("Voxy renderer GPU fence query failed", failure);
                return;
            }
            if (!signaled) return;
            this.gpuCompletions.removeFirst();
            completion.fence.free();
            for (RendererTransaction transaction : completion.rendererTransactions) {
                try {
                    transaction.success.run();
                } catch (RuntimeException failure) {
                    Logger.error("Voxy renderer transaction completion failed", failure);
                }
            }
            for (RegionalSectionPublication publication
                    : completion.regionalSectionPublications) {
                try {
                    publication.success().run();
                } catch (RuntimeException failure) {
                    publication.failure().accept(failure);
                }
            }
        }
    }

    private void queueGpuCompletion(ArrayList<RendererTransaction> transactions,
                                    ArrayList<RegionalSectionPublication> publications) {
        try {
            this.gpuCompletions.addLast(new GpuCompletion(new GlFence(), transactions,
                    publications));
        } catch (RuntimeException failure) {
            this.failGpuCompletion(transactions, publications, failure);
            Logger.error("Voxy renderer could not create a GPU completion fence", failure);
        }
    }

    private void failGpuCompletion(GpuCompletion completion, Throwable failure) {
        this.failGpuCompletion(completion.rendererTransactions,
                completion.regionalSectionPublications, failure);
    }

    private void failGpuCompletion(ArrayList<RendererTransaction> transactions,
                                   ArrayList<RegionalSectionPublication> publications,
                                   Throwable failure) {
        for (RendererTransaction transaction : transactions) {
            try {
                transaction.failure.accept(failure);
            } catch (RuntimeException callbackFailure) {
                Logger.error("Voxy renderer transaction failure callback failed",
                        callbackFailure);
            }
        }
        for (RegionalSectionPublication publication : publications) {
            try {
                publication.failure().accept(failure);
            } catch (RuntimeException callbackFailure) {
                Logger.error("Voxy renderer publication failure callback failed",
                        callbackFailure);
            }
        }
    }


    public void setTLNAddRemoveCallbacks(IntConsumer add, IntConsumer remove) {
        this.tlnAddCallback = add;
        this.tlnRemoveCallback = remove;
    }

    private int currentMaxNodeId = 0;
    public int getCurrentMaxNodeId() {
        return this.currentMaxNodeId;
    }

    private volatile long usedGeometryAmount = 0;
    private volatile long largestFreeGeometryUnits;
    private volatile int usedGeometrySections;
    //==================================================================================================================
    //Incoming events

    /** One producer handoff; the renderer does not hide a second geometry backlog. */
    private final PublicationHandoff<RegionalPublicationBatch> regionalBatchHandoff =
            new PublicationHandoff<>();
    private final AtomicLong topologyGeneration = new AtomicLong();
    private volatile Runnable progressListener = () -> {};

    public record PublicationProgress(long handoff, long topology, long allocation,
                                      long sectionIds, boolean occupied, Throwable failure) {}

    public PublicationProgress publicationProgress() {
        return new PublicationProgress(this.regionalBatchHandoff.consumed(),
                this.topologyGeneration.get(), this.geometryManager.allocationReleaseGeneration(),
                this.geometryManager.sectionReleaseGeneration(), this.regionalBatchHandoff.occupied(),
                this.uncaughtException != null ? this.uncaughtException
                        : this.running ? null : new IllegalStateException("renderer stopped"));
    }

    public void setPublicationProgressListener(Runnable listener) {
        this.progressListener = Objects.requireNonNull(listener);
        listener.run();
    }

    public void clearPublicationProgressListener(Runnable listener) {
        if (this.progressListener == listener) this.progressListener = () -> {};
    }

    public void notifyPublicationProgress() { this.progressListener.run(); }
    /** Worker-owned cursor into the one atomically published producer batch being drained. */
    private RegionalPublicationBatch activeRegionalBatch;
    private int activeRegionalBatchIndex;
    private final ConcurrentLinkedDeque<RendererTransaction> rendererTransactionQueue =
            new ConcurrentLinkedDeque<>();
    private final ConcurrentLinkedDeque<RendererTransaction> coarsenQueue =
            new ConcurrentLinkedDeque<>();

    public interface RegionalPublicationTiming {
        void recordRendererQueued(long nowNanos);
        void recordGpuUploadSubmitted(long nowNanos);
    }

    public enum RegionalAllocationStatus {
        NO_CONTIGUOUS_GEOMETRY_SPACE, NO_SECTION_ID, TOPOLOGY_NOT_READY, IMPOSSIBLE, STALE
    }

    public record RegionalAllocationBlock(BuiltSection geometry,
                                          RegionalAllocationStatus status,
                                          long requiredUnits, long largestFreeUnits,
                                          long prerequisite, PublicationProgress observed) {}

    public record RegionalSectionSubmission(BuiltSection geometry, long previousRevision,
                                            BooleanSupplier current, Runnable reserved,
                                            RegionalPublicationTiming timing, Runnable success,
                                            Runnable canceled,
                                            Consumer<RegionalAllocationBlock> blocked,
                                            Consumer<Throwable> failure) {
        public RegionalSectionSubmission {
            Objects.requireNonNull(geometry, "geometry");
            Objects.requireNonNull(current, "current");
            Objects.requireNonNull(reserved, "reserved");
            Objects.requireNonNull(timing, "timing");
            Objects.requireNonNull(success, "success");
            Objects.requireNonNull(canceled, "canceled");
            Objects.requireNonNull(blocked, "blocked");
            Objects.requireNonNull(failure, "failure");
        }
    }

    private record RegionalSectionPublication(BuiltSection geometry, long previousRevision,
                                             BooleanSupplier current, Runnable reserved,
                                             RegionalPublicationTiming timing, Runnable success,
                                             Runnable canceled,
                                             Consumer<RegionalAllocationBlock> blocked,
                                             Consumer<Throwable> failure) {}
    private record RegionalPublicationBatch(RegionalSectionPublication[] publications,
                                            long enqueuedNanos, Object receipt) {}

    public record PreparedBatch<T>(List<RegionalSectionSubmission> submissions, T receipt) {}

    private enum RendererOperation {
        COMMIT, ROLLBACK, COMPLETE_ROLLBACK, FINALIZE, COARSEN, RELEASE_COARSEN, RETIRE
    }
    private record RendererTransaction(long sourceRevision, Set<Long> positions,
                                       RendererOperation operation,
                                       Runnable success, Consumer<Throwable> failure,
                                       long expectedRevision) {
        RendererTransaction(long revision, Set<Long> positions, RendererOperation operation,
                            Runnable success, Consumer<Throwable> failure) {
            this(revision, positions, operation, success, failure, -1);
        }
    }
    private record GpuCompletion(GlFence fence,
                                 ArrayList<RendererTransaction> rendererTransactions,
                                 ArrayList<RegionalSectionPublication> regionalSectionPublications) {}

    public void commitStagedRoot(long sourceRevision, Set<Long> positions, Runnable success,
                                 Consumer<Throwable> failure) {
        this.submitRendererTransaction(new RendererTransaction(sourceRevision,
                Set.copyOf(positions), RendererOperation.COMMIT, success, failure));
    }

    public void rollbackStagedRoot(long sourceRevision, Runnable success,
                                   Consumer<Throwable> failure) {
        this.submitRendererTransaction(new RendererTransaction(sourceRevision,
                Set.of(), RendererOperation.ROLLBACK,
                () -> this.submitRendererTransaction(new RendererTransaction(sourceRevision,
                        Set.of(), RendererOperation.COMPLETE_ROLLBACK, success, failure)),
                failure));
    }

    public void finalizeStagedRoot(long sourceRevision, Runnable success,
                                   Consumer<Throwable> failure) {
        this.submitRendererTransaction(new RendererTransaction(sourceRevision,
                Set.of(), RendererOperation.FINALIZE, success, failure));
    }

    public void coarsenSubtree(long revision, long parent, Runnable success,
                               Consumer<Throwable> failure) {
        Objects.requireNonNull(success, "success");
        Objects.requireNonNull(failure, "failure");
        RendererTransaction transaction = new RendererTransaction(revision, Set.of(parent),
                RendererOperation.COARSEN,
                () -> this.submitRendererTransaction(new RendererTransaction(revision,
                        Set.of(), RendererOperation.RELEASE_COARSEN, success, failure)),
                failure);
        synchronized (this.submissionLock) {
            if (!this.running) {
                failure.accept(new IllegalStateException("Voxy renderer is not running"));
                return;
            }
            this.coarsenQueue.add(transaction);
        }
        this.signalWork();
    }

    public void retirePublication(long revision, long expectedRevision, long position,
                                  Runnable success, Consumer<Throwable> failure) {
        this.submitRendererTransaction(new RendererTransaction(revision, Set.of(position),
                RendererOperation.RETIRE,
                () -> this.finalizeStagedRoot(revision, success, failure), failure,
                expectedRevision));
    }

    private void submitRendererTransaction(RendererTransaction transaction) {
        synchronized (this.submissionLock) {
            if (!this.running) {
                transaction.failure.accept(
                        new IllegalStateException("Voxy renderer is not running"));
                return;
            }
            this.rendererTransactionQueue.add(transaction);
        }
        this.signalWork();
    }

    private final StampedLock tlnLock = new StampedLock();
    private final LongOpenHashSet tlnAdd = new LongOpenHashSet();
    private final LongOpenHashSet tlnRem = new LongOpenHashSet();

    private void signalWork() {
        if (this.workPending.compareAndSet(false, true)) {
            LockSupport.unpark(this.thread);
        }
    }

    public long geometryUsedBytes() {
        return this.usedGeometryAmount;
    }

    public long geometryCapacityBytes() {
        return this.geometryCapacity;
    }

    public long largestFreeGeometryUnits() {
        return this.largestFreeGeometryUnits;
    }

    public int usedGeometrySections() {
        return this.usedGeometrySections;
    }

    public long geometryPublicationLimitBytes() {
        return this.geometryCapacity;
    }

    /** Rechecks queued predicates after their owner retires or supersedes a publication. */
    public void regionalPublicationStateChanged() {
        if (this.running) this.signalWork();
    }

    public String regionalPublicationBatchSnapshot() {
        return "rendererBatches=" + this.submittedRegionalBatches.get()
                + '/' + this.submittedRegionalSections.get()
                + '/' + this.maximumRegionalBatchSections.get()
                + '/' + sectionBucketSnapshot(this.regionalBatchSectionBuckets)
                + " rendererBatchSectionBuckets=1,2-4,5-16,17-64,65-256,>256"
                + " rendererWakeups=" + this.managerWakeups.get()
                + " rendererBatchStart=" + this.regionalBatchStartLatency.snapshot()
                + " rendererBatchStartBuckets=<0.1ms,<0.5ms,<1ms,<4ms,<16ms,>=16ms"
                + " rendererSyncs=" + this.regionalRenderSyncs.get()
                + '/' + this.regionalRenderSyncPublications.get()
                + '/' + this.regionalRenderSyncBytes.get()
                + '/' + this.maximumRegionalRenderSyncPublications.get()
                + '/' + this.maximumRegionalRenderSyncBytes.get()
                + " rendererBatchSyncSplits=" + this.regionalBatchSyncSplits.get()
                + " rendererWorkerCpuMs=" + this.workerCpuNanos.get() / 1_000_000L
                + " rendererWorkerIdleMs=" + this.workerIdleNanos.get() / 1_000_000L;
    }

    private void recordSubmittedRegionalBatch(int sections) {
        this.submittedRegionalBatches.incrementAndGet();
        this.submittedRegionalSections.addAndGet(sections);
        updateMaximum(this.maximumRegionalBatchSections, sections);
        int bucket = 0;
        while (bucket < BATCH_SECTION_BUCKET_LIMITS.length
                && sections > BATCH_SECTION_BUCKET_LIMITS[bucket]) bucket++;
        this.regionalBatchSectionBuckets.incrementAndGet(bucket);
    }

    private void recordRegionalRenderSync(List<RegionalSectionPublication> publications) {
        long bytes = 0;
        for (RegionalSectionPublication publication : publications) {
            BuiltSection geometry = publication.geometry();
            if (geometry.geometryBuffer != null) bytes += geometry.geometryBuffer.size;
        }
        this.regionalRenderSyncs.incrementAndGet();
        this.regionalRenderSyncPublications.addAndGet(publications.size());
        this.regionalRenderSyncBytes.addAndGet(bytes);
        updateMaximum(this.maximumRegionalRenderSyncPublications, publications.size());
        updateMaximum(this.maximumRegionalRenderSyncBytes, bytes);
    }

    private static void updateMaximum(AtomicLong maximum, long value) {
        long observed = maximum.get();
        while (value > observed && !maximum.compareAndSet(observed, value)) {
            observed = maximum.get();
        }
    }

    private static String sectionBucketSnapshot(AtomicLongArray buckets) {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < buckets.length(); index++) {
            if (index != 0) result.append(',');
            result.append(buckets.get(index));
        }
        return result.toString();
    }

    /**
     * Atomically publishes one complete producer batch. Ownership of every geometry allocation
     * transfers together when the batch is appended; any thrown exception means none transferred.
     */
    @SuppressWarnings("unchecked")
    public <T> T tryPublishRegionalSections(Supplier<PreparedBatch<T>> prepare) {
        RegionalPublicationBatch batch;
        synchronized (this.submissionLock) {
            if (!this.running) throw new IllegalStateException("renderer stopped",
                    this.uncaughtException);
            batch = this.regionalBatchHandoff.trySubmit(() -> {
                PreparedBatch<T> prepared = Objects.requireNonNull(prepare.get());
                List<RegionalSectionSubmission> submissions = prepared.submissions();
                if (submissions.isEmpty()) throw new IllegalArgumentException("empty batch");
                RegionalSectionPublication[] publications =
                        new RegionalSectionPublication[submissions.size()];
                for (int index = 0; index < publications.length; index++) {
                    RegionalSectionSubmission submission = submissions.get(index);
                    BuiltSection geometry = submission.geometry();
                    long bytes = geometry.geometryBuffer == null ? 0 : geometry.geometryBuffer.size;
                    long staged = UploadStream.alignUp(bytes, UploadStream.BASE_ALLOCATION_ALIGNEMENT)
                            + UploadStream.alignUp(16, UploadStream.BASE_ALLOCATION_ALIGNEMENT);
                    if (staged > UploadStream.CAPACITY_BYTES) {
                        throw new IllegalArgumentException("regional section exceeds upload ceiling");
                    }
                    publications[index] = new RegionalSectionPublication(geometry,
                            submission.previousRevision(), submission.current(), submission.reserved(),
                            submission.timing(), submission.success(), submission.canceled(),
                            submission.blocked(), submission.failure());
                }
                long now = System.nanoTime();
                for (RegionalSectionPublication publication : publications) {
                    publication.timing().recordRendererQueued(now);
                }
                return new RegionalPublicationBatch(publications, now, prepared.receipt());
            });
        }
        if (batch == null) return null;
        this.recordSubmittedRegionalBatch(batch.publications().length);
        this.signalWork();
        return (T) batch.receipt();
    }

    public void addTopLevel(long section) {//Only called from render thread
        if (!this.running) throw new IllegalStateException("Not running");
        long stamp = this.tlnLock.writeLock();
        int state = 0;
        if (!this.tlnRem.remove(section)) {
            state += this.tlnAdd.add(section)?1:0;
        } else {
            state -= 1;
        }
        if (state != 0) {
            this.signalWork();
        }
        this.tlnLock.unlockWrite(stamp);
    }

    public void removeTopLevel(long section) {//Only called from render thread
        if (!this.running) throw new IllegalStateException("Not running");
        long stamp = this.tlnLock.writeLock();
        int state = 0;
        if (!this.tlnAdd.remove(section)) {
            state += this.tlnRem.add(section)?1:0;
        } else {
            state -= 1;
        }
        if (state != 0) {
            this.signalWork();
        }
        this.tlnLock.unlockWrite(stamp);
    }

    //==================================================================================================================

    public void start() {
        this.thread.start();
    }

    private volatile boolean stopping;

    public boolean isStopping() { return this.stopping; }

    public void stop() {
        synchronized (this.submissionLock) {
            if (this.stopping) return;
            this.stopping = true;
            this.running = false;
            this.regionalBatchHandoff.stop(new IllegalStateException("renderer stopped"));
        }
        this.notifyPublicationProgress();
        LockSupport.unpark(this.thread);
        try {
            while (this.thread.isAlive()) {
                LockSupport.unpark(this.thread);
                this.thread.join(1000);
            }
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        // A failed worker may have staged native meshes without reaching the sync upload.
        // No worker can touch them after join; queued publications below own different buffers.
        for (var buffer : this.geometryManager.getUploads().values()) {
            if (!buffer.isFreed()) buffer.free();
        }
        this.geometryManager.getUploads().clear();
        this.geometryManager.uploadsDrained();

        if (this.activeRegionalBatch != null) {
            RegionalSectionPublication[] publications =
                    this.activeRegionalBatch.publications();
            while (this.activeRegionalBatchIndex < publications.length) {
                this.failUnsubmittedPublication(
                        publications[this.activeRegionalBatchIndex++]);
            }
            this.activeRegionalBatch = null;
            this.activeRegionalBatchIndex = 0;
        }
        RegionalPublicationBatch handoff = this.regionalBatchHandoff.take();
        if (handoff != null) {
            for (RegionalSectionPublication publication : handoff.publications()) {
                this.failUnsubmittedPublication(publication);
            }
        }
        while (!this.completedRegionalSectionPublications.isEmpty()) {
            this.completedRegionalSectionPublications.removeLast().failure().accept(
                    new IllegalStateException("Voxy renderer stopped before its GPU fence"));
        }

        while (true) {
            RendererTransaction transaction = this.rendererTransactionQueue.poll();
            if (transaction == null) break;
            transaction.failure.accept(new IllegalStateException("Voxy renderer stopped"));
        }
        while (true) {
            RendererTransaction transaction = this.coarsenQueue.poll();
            if (transaction == null) break;
            transaction.failure.accept(new IllegalStateException("Voxy renderer stopped"));
        }
        while (!this.completedRendererTransactions.isEmpty()) {
            this.completedRendererTransactions.removeLast().failure.accept(
                    new IllegalStateException("Voxy renderer stopped before its GPU fence"));
        }

        while (!this.gpuCompletions.isEmpty()) {
            GpuCompletion completion = this.gpuCompletions.removeFirst();
            completion.fence.free();
            this.failGpuCompletion(completion,
                    new IllegalStateException("Voxy renderer stopped before its GPU fence"));
        }

        if (RESULT_HANDLE.get(this) != null) {
            var result = (SyncResults)RESULT_HANDLE.getAndSet(this, null);
            for (RendererTransaction transaction : result.rendererTransactions) {
                transaction.failure.accept(new IllegalStateException(
                        "Voxy renderer stopped before its GPU fence"));
            }
            result.rendererTransactions.clear();
            for (RegionalSectionPublication publication : result.regionalSectionPublications) {
                publication.failure().accept(new IllegalStateException(
                        "Voxy renderer stopped before its GPU fence"));
            }
            result.regionalSectionPublications.clear();
            result.geometryUpload.free();
            result.scatterWriteBuffer.free();
        }

        if (RESULT_CACHE_1_HANDLE.get(this) != null) {//Clear cache 1
            var result = (SyncResults)RESULT_CACHE_1_HANDLE.getAndSet(this, null);
            result.geometryUpload.free();
            result.scatterWriteBuffer.free();
        }

        if (RESULT_CACHE_2_HANDLE.get(this) != null) {//Clear cache 2
            var result = (SyncResults)RESULT_CACHE_2_HANDLE.getAndSet(this, null);
            result.geometryUpload.free();
            result.scatterWriteBuffer.free();
        }

        this.scatterWrite.free();
        this.multiMemcpy.free();
    }

    private void failUnsubmittedPublication(RegionalSectionPublication publication) {
        publication.geometry().free();
        publication.failure().accept(new IllegalStateException("Voxy renderer stopped"));
    }

    private static final class LatencyCounters {
        private final long[] thresholds;
        private final AtomicLong count = new AtomicLong();
        private final AtomicLong maximumNanos = new AtomicLong();
        private final AtomicLongArray buckets;

        private LatencyCounters(long[] thresholds) {
            this.thresholds = thresholds.clone();
            this.buckets = new AtomicLongArray(thresholds.length + 1);
        }

        private void record(long nanos) {
            if (nanos < 0) return;
            this.count.incrementAndGet();
            updateMaximum(this.maximumNanos, nanos);
            int bucket = 0;
            while (bucket < this.thresholds.length && nanos >= this.thresholds[bucket]) bucket++;
            this.buckets.incrementAndGet(bucket);
        }

        private String snapshot() {
            return this.count.get() + "/" + this.maximumNanos.get() / 1_000L + "us/"
                    + sectionBucketSnapshot(this.buckets);
        }
    }

    //Results object, which is to be synced between the render thread and worker thread
    private static final class SyncResults {
        //Contains
        // geometry uploads and id invalidations and the data
        // node ids to invalidate/update and its data
        // top level node ids to add/remove
        // cleaner move and set operations

        //Node id updates + size
        private int currentMaxNodeId;// the id of the ending of the node ids

        //TLN add/rem
        private final IntOpenHashSet tlnDelta = new IntOpenHashSet();

        //Deltas for geometry store
        private int geometrySectionCount;
        private long usedGeometry;
        private final ComputeMemoryCopy geometryUpload = new ComputeMemoryCopy();

        //Gpu geometry downloads



        //Scatter writes for both geometry and node metadata
        private MemoryBuffer scatterWriteBuffer = new MemoryBuffer(8192*2);
        private final Int2IntOpenHashMap scatterWriteLocationMap = new Int2IntOpenHashMap(1024);
        {this.scatterWriteLocationMap.defaultReturnValue(-1);}

        //Cleaner operations
        private final IntOpenHashSet cleanerOperations = new IntOpenHashSet();
        private final ArrayList<RendererTransaction> rendererTransactions = new ArrayList<>();
        private final ArrayList<RegionalSectionPublication> regionalSectionPublications =
                new ArrayList<>();

        public void reset() {
            this.cleanerOperations.clear();
            this.scatterWriteLocationMap.clear();
            this.currentMaxNodeId = 0;
            this.tlnDelta.clear();
            this.geometrySectionCount = 0;
            this.usedGeometry = 0;
            this.geometryUpload.reset();
            this.rendererTransactions.clear();
            this.regionalSectionPublications.clear();
        }

        //Get or create a scatter write address for the given location
        public long getScatterWritePtr(int location) {
            return this.getScatterWritePtr(location, 0);
        }

        //ensureExtra is used to ensure that allocations are "effectivly" in the same memory block (kinda?)
        public long getScatterWritePtr(int location, int ensureExtra) {
            int loc = this.scatterWriteLocationMap.get(location);
            if (loc == -1) {//Location doesnt exist, create it
                this.ensureScatterBufferCapacity(1+ensureExtra);//Ensure can contain capacity for this + extra
                int baseId = this.scatterWriteLocationMap.size();
                int chunkBase = (baseId/4)*5;//Base uvec4 index
                int innerId   = baseId&3;
                MemoryUtil.memPutInt(this.scatterWriteBuffer.address + (chunkBase*16L) + (innerId*4L), location);//Set the write location
                int writeLocation = (chunkBase+1+innerId);//Write location in uvec4
                this.scatterWriteLocationMap.put(location, writeLocation);
                return this.scatterWriteBuffer.address + (writeLocation*16L);
            } else {
                return this.scatterWriteBuffer.address + (16L*loc);
            }
        }

        private void ensureScatterBufferCapacity(int extra) {
            int requiredChunks = ((this.scatterWriteLocationMap.size()+extra)+3)/4;//4 entries in a chunk
            long requiredSize = requiredChunks*5L*16L;//5 uvec4 per chunk, 16 bytes per uvec4
            if (this.scatterWriteBuffer.size <= requiredSize) {//Needs resize
                long newSize = (long) ((this.scatterWriteBuffer.size*1.5) + extra*80L);
                newSize = ((newSize+79)/80)*80;//Ceil to chunk size

                Logger.info("Expanding scatter update buffer to " + newSize);

                var newBuffer = new MemoryBuffer(newSize);
                this.scatterWriteBuffer.cpyTo(newBuffer.address);
                this.scatterWriteBuffer.free();
                this.scatterWriteBuffer = newBuffer;
            }
        }
    }

    private static class ComputeMemoryCopy {
        public int currentElemCopyAmount;
        public long maxElementAccess;
        private MemoryBuffer scratchHeaderBuffer = new MemoryBuffer(1<<16);
        private MemoryBuffer scratchDataBuffer = new MemoryBuffer(1<<20);

        private final AllocationArena arena = new AllocationArena();
        private final Int2IntOpenHashMap dataUploadPoints = new Int2IntOpenHashMap();//Points to the header index
        {this.dataUploadPoints.defaultReturnValue(-1);}


        public void remove(int point) {
            int header = this.dataUploadPoints.remove(point);
            if (header == -1) {//No upload for point
                return;
            }
            int size = MemoryUtil.memGetInt(this.scratchHeaderBuffer.address + header*16L + 8L);
            this.currentElemCopyAmount -= size;
            //Free the old memory addr from arena
            if (this.arena.free(MemoryUtil.memGetInt(this.scratchHeaderBuffer.address + header*16L)) != size) {
                throw new IllegalStateException("Freed memory not same size as expected");
            }
            if (MemoryUtil.memGetInt(this.scratchHeaderBuffer.address + header*16L + 4L) != point) {
                throw new IllegalStateException("Destination not the same as point");
            }

            //If we were the end upload header, return as we dont need to shuffle
            if (header == this.dataUploadPoints.size()) {
                long A = this.scratchHeaderBuffer.address + header*16L;
                //Zero the memory, for consistancy
                MemoryUtil.memPutLong(A, 0);
                MemoryUtil.memPutLong(A+8, 0);
                return;
            }

            //Else: we need to move the ending upload header from the end to where the freed point was
            int endingPoint = MemoryUtil.memGetInt(this.scratchHeaderBuffer.address + this.dataUploadPoints.size()*16L + 4);
            if (this.dataUploadPoints.get(endingPoint) != this.dataUploadPoints.size()) {
                throw new IllegalStateException("ending header not pointing at end point");
            }

            //Move the end header to the old header location
            long A = this.scratchHeaderBuffer.address + this.dataUploadPoints.size()*16L;
            long B = this.scratchHeaderBuffer.address + header*16L;
            MemoryUtil.memPutLong(B, MemoryUtil.memGetLong(A)); MemoryUtil.memPutLong(A, 0);
            MemoryUtil.memPutLong(B+8, MemoryUtil.memGetLong(A+8)); MemoryUtil.memPutLong(A+8, 0);

            //Update the map
            this.dataUploadPoints.put(endingPoint, header);
        }

        public void upload(int point, MemoryBuffer data) {
            if ((data.size%8)!=0) throw new IllegalStateException("Data must be of size multiple 8");
            int elemSize = (int) (data.size / 8);
            this.maxElementAccess = Math.max(this.maxElementAccess,
                    Integer.toUnsignedLong(point) + elemSize);
            int header = this.dataUploadPoints.get(point);
            if (header != -1) {
                //If we already have a header location, we just need to reallocate the data
                long headerPtr = this.scratchHeaderBuffer.address + header*16L;
                if (MemoryUtil.memGetInt(headerPtr+4L) != point) {
                    throw new IllegalStateException("Existing destination not the point");
                }
                int pSize = MemoryUtil.memGetInt(headerPtr+8L);//Previous size
                if (pSize == elemSize) {
                    //The data we are replacing is the same size, so just overwrite it, this is the easiest
                    data.cpyTo(this.scratchDataBuffer.address+MemoryUtil.memGetInt(headerPtr)*8L);
                } else {
                    //Dealloc
                    if (this.arena.free(MemoryUtil.memGetInt(headerPtr)) != pSize) {
                        throw new IllegalStateException("Freed allocation not size as expected");
                    }

                    this.currentElemCopyAmount -= pSize;
                    this.currentElemCopyAmount += elemSize;

                    int alloc = this.allocScratchDataPos(elemSize);//New allocation position
                    //Copy data into position
                    data.cpyTo(this.scratchDataBuffer.address+alloc*8L);

                    //Update the header
                    MemoryUtil.memPutInt(headerPtr, alloc);
                    MemoryUtil.memPutInt(headerPtr+8, elemSize);
                }
            } else {
                //We need to create and allocate a new header for the upload
                header = this.dataUploadPoints.size();
                this.dataUploadPoints.put(point, header);

                if (this.scratchHeaderBuffer.size<=header*16L) {
                    //We must resize the header buffer
                    long newSize = Math.max(this.scratchHeaderBuffer.size*2, header*16L);
                    Logger.info("Resizing scratch header buffer to: " + newSize);
                    var newScratch = new MemoryBuffer(newSize);
                    this.scratchHeaderBuffer.cpyTo(newScratch.address);
                    this.scratchHeaderBuffer.free();
                    this.scratchHeaderBuffer = newScratch;
                }

                long headerPtr = this.scratchHeaderBuffer.address + header*16L;//Header resize has happened so this is a stable address

                this.currentElemCopyAmount += elemSize;

                int alloc = this.allocScratchDataPos(elemSize);//New allocation position
                //Copy data into position
                data.cpyTo(this.scratchDataBuffer.address+alloc*8L);

                //Set header data
                MemoryUtil.memPutInt(headerPtr, alloc);
                MemoryUtil.memPutInt(headerPtr+4, point);
                MemoryUtil.memPutInt(headerPtr+8, elemSize);
            }
        }

        //This is done here as it enables easily doing scratch data resizing
        private int allocScratchDataPos(int size) {
            int pos = (int) this.arena.alloc(size);
            if (this.scratchDataBuffer.size <= (pos+size)*8L) {
                //We must resize :cri:
                long newSize = Math.max(this.scratchDataBuffer.size*2, (pos+size)*8L);
                Logger.info("Resizing scratch data buffer to: " + newSize);
                var newScratch = new MemoryBuffer(newSize);
                this.scratchDataBuffer.cpyTo(newScratch.address);
                this.scratchDataBuffer.free();
                this.scratchDataBuffer = newScratch;
            }
            return pos;
        }

        public void reset() {
            this.maxElementAccess = 0;
            this.currentElemCopyAmount = 0;
            this.dataUploadPoints.clear();
            this.arena.reset();
        }

        public void free() {
            this.scratchHeaderBuffer.free(); this.scratchHeaderBuffer = null;
            this.scratchDataBuffer.free(); this.scratchDataBuffer = null;
        }
    }
}
