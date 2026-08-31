package me.cortex.voxy.common.world;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.world.other.Mapper;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

public final class ActiveSectionTracker {
    public interface SectionLoader { int load(WorldSection section); }

    private final AtomicInteger loadedSections = new AtomicInteger();
    private final Long2ObjectOpenHashMap<CompletableFuture<WorldSection>>[] loadedSectionCache;
    private final ReentrantLock[] locks;
    private final SectionLoader loader;
    private final int lruSize;
    private final ReentrantLock lruLock = new ReentrantLock();
    private final Long2ObjectLinkedOpenHashMap<WorldSection> lruSecondaryCache;

    @Nullable
    public final WorldEngine engine;

    public ActiveSectionTracker(int numSlicesBits, SectionLoader loader, int cacheSize) {
        this(numSlicesBits, loader, cacheSize, null);
    }

    @SuppressWarnings("unchecked")
    public ActiveSectionTracker(int numSlicesBits, SectionLoader loader, int cacheSize, WorldEngine engine) {
        this.engine = engine;
        this.loader = loader;
        this.loadedSectionCache = new Long2ObjectOpenHashMap[1 << numSlicesBits];
        this.locks = new ReentrantLock[this.loadedSectionCache.length];
        this.lruSize = cacheSize;
        this.lruSecondaryCache = new Long2ObjectLinkedOpenHashMap<>(cacheSize);
        for (int i = 0; i < this.loadedSectionCache.length; i++) {
            this.loadedSectionCache[i] = new Long2ObjectOpenHashMap<>(1024);
            this.locks[i] = new ReentrantLock();
        }
    }

    public WorldSection acquire(int level, int x, int y, int z, boolean nullOnEmpty) {
        return this.acquire(WorldEngine.getWorldSectionId(level, x, y, z), nullOnEmpty);
    }

    public WorldSection acquire(long key, boolean nullOnEmpty) {
        if (this.engine != null) this.engine.lastActiveTime = System.currentTimeMillis();
        int index = this.getCacheArrayIndex(key);
        ReentrantLock lock = this.locks[index];
        CompletableFuture<WorldSection> future;
        boolean load;

        while (true) {
            lock.lock();
            try {
                future = this.loadedSectionCache[index].get(key);
                if (future == null) {
                    future = new CompletableFuture<>();
                    this.loadedSectionCache[index].put(key, future);
                    this.loadedSections.incrementAndGet();
                    load = true;
                } else {
                    WorldSection section = future.getNow(null);
                    if (section == null) {
                        load = false;
                    } else {
                        if (section.tryAcquire()) return section;
                        continue;
                    }
                }
            } finally {
                lock.unlock();
            }

            if (load) return this.load(key, index, future, nullOnEmpty);
            await(future);
        }
    }

    private WorldSection load(long key, int index, CompletableFuture<WorldSection> future,
                              boolean nullOnEmpty) {
        WorldSection section = null;
        WorldSection eviction = null;
        try {
            this.lruLock.lock();
            try {
                section = this.lruSecondaryCache.remove(key);
                if (section == null && !this.lruSecondaryCache.isEmpty()
                        && this.lruSize + 100 < this.lruSecondaryCache.size() + this.getLoadedCacheCount()) {
                    eviction = this.lruSecondaryCache.removeFirst();
                }
            } finally {
                this.lruLock.unlock();
            }
            if (eviction != null) eviction._releaseArray();

            int status = 0;
            if (section == null) {
                section = new WorldSection(WorldEngine.getLevel(key), WorldEngine.getX(key),
                        WorldEngine.getY(key), WorldEngine.getZ(key), this);
                section.acquire();
                status = this.loader.load(section);
                if (status < 0) {
                    Logger.error("Unable to load section " + section.key + " setting to air");
                    status = 1;
                }
                if (status == 1) Arrays.fill(section.data, Mapper.airWithLight(15));
            } else {
                section.primeForReuse();
                section.acquire();
            }

            ReentrantLock lock = this.locks[index];
            lock.lock();
            try {
                if (this.loadedSectionCache[index].get(key) != future) {
                    throw new IllegalStateException("Section load entry changed before publication");
                }
                future.complete(section);
            } finally {
                lock.unlock();
            }
            if (nullOnEmpty && status == 1) {
                section.release();
                return null;
            }
            return section;
        } catch (Throwable failure) {
            ReentrantLock lock = this.locks[index];
            lock.lock();
            try {
                if (this.loadedSectionCache[index].remove(key, future)) {
                    this.loadedSections.decrementAndGet();
                }
                future.completeExceptionally(failure);
            } finally {
                lock.unlock();
            }
            if (section != null) {
                if (section.getRefCount() != 0) section.release(false, 0);
                if (section.isFreed() || section.trySetFreed()) section._releaseArray();
            }
            throw propagate(failure);
        }
    }

    private static void await(CompletableFuture<WorldSection> future) {
        try {
            future.join();
        } catch (CompletionException failure) {
            throw propagate(failure.getCause());
        }
    }

    private static RuntimeException propagate(Throwable failure) {
        if (failure instanceof RuntimeException runtime) return runtime;
        if (failure instanceof Error error) throw error;
        return new IllegalStateException("Section load failed", failure);
    }

    void tryUnload(WorldSection section, int hints) {
        if (this.engine != null) this.engine.lastActiveTime = System.currentTimeMillis();
        this.tryScheduleSave(section, hints);
        if (section.getRefCount() != 0) return;

        int index = this.getCacheArrayIndex(section.key);
        ReentrantLock lock = this.locks[index];
        boolean removed = false;
        lock.lock();
        try {
            if (section.getRefCount() != 0) return;
            if (section.shouldSave() && this.engine != null) {
                if (!section.tryAcquire()) {
                    throw new IllegalStateException("Dirty section was already unloaded");
                }
                if (this.engine.saveSection(section, true, true)) return;
                boolean retry = section.getRefCount() != 1 || section.isDirty;
                section.release(false, hints);
                if (retry) return;
            }
            if (section.getRefCount() == 0 && section.trySetFreed()) {
                CompletableFuture<WorldSection> future = this.loadedSectionCache[index].remove(section.key);
                if (future == null || future.getNow(null) != section) {
                    throw new IllegalStateException("Loaded section cache ownership mismatch");
                }
                this.loadedSections.decrementAndGet();
                removed = true;
            }
        } finally {
            lock.unlock();
        }

        if (removed) {
            WorldSection eviction;
            this.lruLock.lock();
            try {
                if (this.lruSecondaryCache.put(section.key, section) != null) {
                    throw new IllegalStateException("Duplicate section in reuse cache");
                }
                eviction = this.lruSecondaryCache.size() > this.lruSize
                        ? this.lruSecondaryCache.removeFirst() : null;
            } finally {
                this.lruLock.unlock();
            }
            if (eviction != null) eviction._releaseArray();
        }
    }

    private void tryScheduleSave(WorldSection section, int hints) {
        if (!section.shouldSave() || this.engine == null || !section.tryAcquire()) return;
        if (!section.shouldSave() || !this.engine.saveSection(section, true, true)) {
            section.release(false, hints);
        }
    }

    private int getCacheArrayIndex(long position) {
        return (int) (mixStafford13(position) & (this.loadedSectionCache.length - 1));
    }

    public static long mixStafford13(long seed) {
        seed = (seed ^ seed >>> 30) * -4658895280553007687L;
        seed = (seed ^ seed >>> 27) * -7723592293110705685L;
        return seed ^ seed >>> 31;
    }

    public int getLoadedCacheCount() {
        return this.loadedSections.get();
    }
}
