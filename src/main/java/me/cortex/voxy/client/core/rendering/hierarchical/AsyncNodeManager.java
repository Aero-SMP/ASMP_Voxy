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
import me.cortex.voxy.client.lod.MicrotileActivationManager.PublicationCancelledException;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.AllocationArena;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.util.UnsafeUtil;
import org.lwjgl.system.MemoryUtil;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.StampedLock;
import java.util.function.Consumer;

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

    private final AtomicInteger workCounter = new AtomicInteger();
    private final ArrayList<RendererTransaction> completedRendererTransactions = new ArrayList<>();
    private final ArrayList<VirtualSurfacePublication> completedVirtualSurfacePublications =
            new ArrayList<>();
    private final ArrayDeque<VirtualSurfacePublication> deferredVirtualSurfacePublications =
            new ArrayDeque<>();
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

    public AsyncNodeManager(int maxNodeCount, BasicSectionGeometryData geometryData) {
        //Note: geometry data is the data store/source, not the management, it is just a raw store of data
        // it MUST ONLY be accessed on the render thread
        // AsyncNodeManager will use an AsyncGeometryManager as the manager for the data store, and sync the results on the render thread
        this.geometryData = geometryData;
        this.geometryCapacity = geometryData.getGeometryCapacityBytes();

        this.maxNodeCount = maxNodeCount;

        this.thread = new Thread(()->{
            try {
                while (this.running) {
                    this.run();
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
        if (this.workCounter.get() <= 0) {
            //TODO: here, instead of parking, we can do more work on other sub-tasks such as filtering the mesh build queue
            LockSupport.park();
            if (this.workCounter.get() <= 0 || !this.running) {//No work
                return;
            }
            //This is a funny thing, wait a bit, this allows for better batching, but this thread is independent of everything else so waiting a bit should be mostly ok
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }

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

        while (true) {
            RendererTransaction transaction = this.rendererTransactionQueue.poll();
            if (transaction == null) break;
            workDone++;
            hierarchyAdvanced = true;
            try {
                switch (transaction.operation) {
                    case COMMIT -> this.manager.commitStagedRoot(
                            transaction.sourceRevision, transaction.positions);
                    case ROLLBACK -> this.manager.rollbackStagedRoot(transaction.sourceRevision);
                    case COMPLETE_ROLLBACK -> this.manager.completeRollback(
                            transaction.sourceRevision);
                    case FINALIZE -> this.manager.finalizeStagedRoot(transaction.sourceRevision);
                }
                this.completedRendererTransactions.add(transaction);
            } catch (Throwable failure) {
                transaction.failure.accept(failure);
            }
        }

        while (true) {//Process all request batches
            var job = this.requestBatchQueue.poll();
            if (job == null)
                break;
            workDone++;
            long ptr = job.address;
            int count = MemoryUtil.memGetInt(ptr);
            ptr += 8;//Its 8 to keep alignment
            if (job.size < count * 8L + 8) {
                throw new IllegalStateException();
            }
            for (int i = 0; i < count; i++) {
                long pos = ((long) MemoryUtil.memGetInt(ptr)) << 32; ptr += 4;
                pos |= Integer.toUnsignedLong(MemoryUtil.memGetInt(ptr)); ptr += 4;
                this.manager.processRequest(pos);
            }
            job.free();
            hierarchyAdvanced = true;
        }

        // Resolve topology work first. A directly selected descendant may arrive before its
        // request owner, so retain its complete geometry and retry only after hierarchy progress.
        if (hierarchyAdvanced) this.retryDeferredVirtualSurfacePublications();
        while (hasGeometryCapacity()) {
            VirtualSurfacePublication publication = this.virtualSurfaceQueue.poll();
            if (publication == null) break;
            workDone++;
            if (!this.processVirtualSurfacePublication(publication)) {
                this.deferredVirtualSurfacePublications.addLast(publication);
            }
        }


        if (this.workCounter.addAndGet(-workDone) < 0) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            //Due to synchronization "issues", wait a millis (give up this time slice)
            if (this.workCounter.get() < 0) {
                Logger.error("Work counter less than zero, hope it fixes itself...");
            }
        }

        if (workDone == 0) {//Nothing happened, which is odd, but just return
            //Should probably log that nothing happened, at least once
            return;
        }
        if (this.needsWaitForSync) {
            while (RESULT_HANDLE.get(this) != null && this.running) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
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
            }
        }

        {//This is the same regardless of if is a merge or new result
            //Geometry id metadata updates
            if (!this.geometryManager.getUpdateIds().isEmpty()) {
                var ids = this.geometryManager.getUpdateIds();
                var iter = ids.intIterator();
                while (iter.hasNext()) {
                    int val = iter.nextInt();
                    int scatterAddr = (val<<1)|(1<<31);//Since we write to the second buffer

                    //Geometry buffer is index of 1, so mutate to put it in that location, it is also 32 bytes, so needs to be split into 2 separate scatter writes
                    long ptrA = results.getScatterWritePtr(scatterAddr+0, 1);
                    long ptrB = results.getScatterWritePtr(scatterAddr+1, 0);

                    //Write update data
                    this.geometryManager.writeMetadataSplit(val, ptrA, ptrB);
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
        results.virtualSurfacePublications.addAll(this.completedVirtualSurfacePublications);
        this.completedVirtualSurfacePublications.clear();

        results.geometrySectionCount = this.geometryManager.getSectionCount();
        results.usedGeometry = this.geometryManager.getGeometryUsedBytes();
        results.currentMaxNodeId = this.manager.getCurrentMaxNodeId();

        this.needsWaitForSync |= results.geometryUpload.currentElemCopyAmount*8L > 2L<<20;//2mb limit per frame
        this.needsWaitForSync |= results.cleanerOperations.size() > 1024;
        this.needsWaitForSync |= results.scatterWriteLocationMap.size() > 4096;
        this.needsWaitForSync |= results.tlnDelta.size() > 10;

        if (!RESULT_HANDLE.compareAndSet(this, null, results)) {
            throw new IllegalArgumentException("Should always have null");
        }

    }

    private boolean hasGeometryCapacity() {
        return this.geometryCapacity - this.geometryManager.getGeometryUsedBytes()
                > 50_000_000L;
    }

    private void retryDeferredVirtualSurfacePublications() {
        int remaining = this.deferredVirtualSurfacePublications.size();
        while (remaining-- > 0 && hasGeometryCapacity()) {
            VirtualSurfacePublication publication =
                    this.deferredVirtualSurfacePublications.removeFirst();
            if (!this.processVirtualSurfacePublication(publication)) {
                this.deferredVirtualSurfacePublications.addLast(publication);
            }
        }
    }

    /** Uploads one complete node, or retains it until its manifested parent path exists. */
    private boolean processVirtualSurfacePublication(VirtualSurfacePublication publication) {
        BuiltSection geometry = publication.geometry();
        boolean transferred = false;
        try {
            if (publication.previousRevision() >= 0) {
                this.manager.finalizeStagedRoot(publication.previousRevision());
            }
            NodeManager.RendererFence staged = this.manager.stageGeometryResult(geometry);
            if (staged == null && this.manager.ensureHierarchyOwner(geometry.position)) {
                staged = this.manager.stageGeometryResult(geometry);
            }
            if (staged == null) {
                if (this.manager.hasTopLevelAncestor(geometry.position)) return false;
                throw new PublicationCancelledException(
                        "Virtual Surface hierarchy root is no longer active");
            }
            transferred = true;
            this.manager.commitStagedRoot(geometry.sourceRevision, Set.of(geometry.position));
            this.completedVirtualSurfacePublications.add(publication);
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
                || !results.virtualSurfacePublications.isEmpty()) {
            // glFenceSync is ordered after every upload, metadata scatter and top-level pointer
            // command emitted above. Polling happens on later render frames and never waits.
            this.queueGpuCompletion(new ArrayList<>(results.rendererTransactions),
                    new ArrayList<>(results.virtualSurfacePublications));
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
            for (VirtualSurfacePublication publication
                    : completion.virtualSurfacePublications) {
                try {
                    publication.success().run();
                } catch (RuntimeException failure) {
                    publication.failure().accept(failure);
                }
            }
        }
    }

    private void queueGpuCompletion(ArrayList<RendererTransaction> transactions,
                                    ArrayList<VirtualSurfacePublication> publications) {
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
                completion.virtualSurfacePublications, failure);
    }

    private void failGpuCompletion(ArrayList<RendererTransaction> transactions,
                                   ArrayList<VirtualSurfacePublication> publications,
                                   Throwable failure) {
        for (RendererTransaction transaction : transactions) {
            try {
                transaction.failure.accept(failure);
            } catch (RuntimeException callbackFailure) {
                Logger.error("Voxy renderer transaction failure callback failed",
                        callbackFailure);
            }
        }
        for (VirtualSurfacePublication publication : publications) {
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

    private long usedGeometryAmount = 0;
    //==================================================================================================================
    //Incoming events

    //TODO: add atomic counters for each event type probably
    private final ConcurrentLinkedDeque<MemoryBuffer> requestBatchQueue = new ConcurrentLinkedDeque<>();
    private final ConcurrentLinkedDeque<VirtualSurfacePublication> virtualSurfaceQueue =
            new ConcurrentLinkedDeque<>();
    private final ConcurrentLinkedDeque<RendererTransaction> rendererTransactionQueue =
            new ConcurrentLinkedDeque<>();

    private record VirtualSurfacePublication(BuiltSection geometry, long previousRevision,
                                             Runnable success,
                                             Consumer<Throwable> failure) {}

    private enum RendererOperation { COMMIT, ROLLBACK, COMPLETE_ROLLBACK, FINALIZE }
    private record RendererTransaction(long sourceRevision, Set<Long> positions,
                                       RendererOperation operation,
                                       Runnable success, Consumer<Throwable> failure) {}
    private record GpuCompletion(GlFence fence,
                                 ArrayList<RendererTransaction> rendererTransactions,
                                 ArrayList<VirtualSurfacePublication> virtualSurfacePublications) {}

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

    private void submitRendererTransaction(RendererTransaction transaction) {
        if (!this.running) {
            transaction.failure.accept(new IllegalStateException("Voxy renderer is not running"));
            return;
        }
        this.rendererTransactionQueue.add(transaction);
        this.addWork();
    }

    private final StampedLock tlnLock = new StampedLock();
    private final LongOpenHashSet tlnAdd = new LongOpenHashSet();
    private final LongOpenHashSet tlnRem = new LongOpenHashSet();

    private void addWork() {
        if (!this.running) {
            if (this.uncaughtException != null) {
                throw new RuntimeException(this.uncaughtException);//Propagate internal exception
            }
            throw new IllegalStateException("Not running");
        }
        if (this.workCounter.getAndIncrement() == 0) {
            LockSupport.unpark(this.thread);
        }
    }

    public void submitRequestBatch(MemoryBuffer batch) {//Only called from render thread
        this.requestBatchQueue.add(batch);
        this.addWork();
    }

    /**
     * Queues a complete Virtual Surface node for one indivisible upload and hierarchy swap.
     * Ownership of {@code geometry} transfers immediately to this manager.
     */
    public void publishVirtualSurface(BuiltSection geometry, long previousRevision,
                                      Runnable success,
                                      Consumer<Throwable> failure) {
        Objects.requireNonNull(geometry, "geometry");
        Objects.requireNonNull(success, "success");
        Objects.requireNonNull(failure, "failure");
        if (!this.running) {
            geometry.free();
            failure.accept(new IllegalStateException("Voxy renderer is not running"));
            return;
        }
        this.virtualSurfaceQueue.add(new VirtualSurfacePublication(geometry, previousRevision,
                success, failure));
        this.addWork();
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
            if (this.workCounter.getAndAdd(state) == 0) {
                LockSupport.unpark(this.thread);
            }
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
            if (this.workCounter.getAndAdd(state) == 0) {
                LockSupport.unpark(this.thread);
            }
        }
        this.tlnLock.unlockWrite(stamp);
    }

    //==================================================================================================================

    public void start() {
        this.thread.start();
    }

    public void stop() {
        if (!this.running) {
            throw new IllegalStateException();
        }
        this.running = false;
        LockSupport.unpark(this.thread);
        try {
            while (this.thread.isAlive()) {
                LockSupport.unpark(this.thread);
                this.thread.join(1000);
            }
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        while (true) {
            var buffer = this.requestBatchQueue.poll();
            if (buffer == null) break;
            buffer.free();
        }

        while (true) {
            VirtualSurfacePublication publication = this.virtualSurfaceQueue.poll();
            if (publication == null) break;
            publication.geometry().free();
            publication.failure().accept(new IllegalStateException("Voxy renderer stopped"));
        }
        while (!this.deferredVirtualSurfacePublications.isEmpty()) {
            VirtualSurfacePublication publication =
                    this.deferredVirtualSurfacePublications.removeFirst();
            publication.geometry().free();
            publication.failure().accept(new IllegalStateException("Voxy renderer stopped"));
        }
        while (!this.completedVirtualSurfacePublications.isEmpty()) {
            this.completedVirtualSurfacePublications.removeLast().failure().accept(
                    new IllegalStateException("Voxy renderer stopped before its GPU fence"));
        }

        while (true) {
            RendererTransaction transaction = this.rendererTransactionQueue.poll();
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
            for (VirtualSurfacePublication publication : result.virtualSurfacePublications) {
                publication.failure().accept(new IllegalStateException(
                        "Voxy renderer stopped before its GPU fence"));
            }
            result.virtualSurfacePublications.clear();
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
        private final ArrayList<VirtualSurfacePublication> virtualSurfacePublications =
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
            this.virtualSurfacePublications.clear();
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
        public int maxElementAccess;
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
            this.maxElementAccess = Math.max(this.maxElementAccess, point + elemSize);
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
