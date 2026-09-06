package me.cortex.voxy.client.core.model;


import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import me.cortex.voxy.common.Logger;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;

public class ModelBakerySubsystem {
    //Redo to just make it request the block faces with the async texture download stream which
    // basicly solves all the render stutter due to the baking

    private final ModelStore storage = new ModelStore();
    public final ModelFactory factory;
    private final CatalogMapper mapper;

    private final Thread processingThread;
    private volatile boolean isRunning = true;
    private volatile Throwable processingThreadException;
    public ModelBakerySubsystem(CatalogMapper mapper) {
        this.mapper = mapper;
        try { this.factory = new ModelFactory(mapper, this.storage); }
        catch (RuntimeException | Error failure) {
            try { this.storage.free(); }
            catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
        this.processingThread = new Thread(()->{//TODO replace this with something good/integrate it into the async processor so that we just have less threads overall
            while (this.isRunning) {
                while (this.isRunning && this.factory.processOneThing());
                LockSupport.park();
            }
        }, "Model factory processor");
        this.processingThread.setUncaughtExceptionHandler((t,e)->{
            this.isRunning = false;
            if (e == null) {
                e = new RuntimeException("unhandled excpetion not added");
            }
            this.processingThreadException = e;
        });
        try { this.processingThread.start(); }
        catch (RuntimeException | Error failure) {
            try { this.shutdown(); }
            catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    public void tick() {
        if (this.processingThreadException != null) {
            Logger.error(this.processingThreadException.getStackTrace().toString(), this.processingThreadException);
            throw new RuntimeException(this.processingThreadException);
        }
        this.factory.processUploads();
    }

    public void beginStopping() {
        this.isRunning = false;
        LockSupport.unpark(this.processingThread);
    }

    public void awaitStopped() {
        this.beginStopping();
        me.cortex.voxy.common.util.Cleanup.join(this.processingThread);
    }

    private boolean disposed;
    public void shutdown() {
        if (this.disposed) return;
        this.awaitStopped();
        this.disposed = true;

        var cleanup = new me.cortex.voxy.common.util.Cleanup();
        cleanup.run(this.factory::free);
        cleanup.run(this.storage::free);
        cleanup.rethrow();
    }

    //This is on this side only and done like this as only worker threads call this code
    private final ReentrantLock seenIdsLock = new ReentrantLock();
    private final ReentrantLock enqueueLock = new ReentrantLock();
    private final IntOpenHashSet seenIds = new IntOpenHashSet(6000);//TODO: move to a lock free concurrent hashmap
    public void requestBlockBake(int blockId) {
        if (this.mapper.getBlockStateCount() <= blockId) {
            Logger.error("Error, got bakeing request for out of range state id. StateId: " + blockId + " max id: " + this.mapper.getBlockStateCount(), new Exception());
            return;
        }
        this.seenIdsLock.lock();
        try {
            if (!this.seenIds.add(blockId)) return;
        } finally {
            this.seenIdsLock.unlock();
        }
        this.enqueueLock.lock();
        try {
            this.factory.addEntry(blockId);
        } finally {
            this.enqueueLock.unlock();
        }
        LockSupport.unpark(this.processingThread);
    }

    public void addBiome(CatalogMapper.BiomeEntry biomeEntry) {
        this.factory.addBiome(biomeEntry);
        LockSupport.unpark(this.processingThread);
    }

    public ModelStore getStore() {
        return this.storage;
    }

}
