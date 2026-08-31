package me.cortex.voxy.common.world;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.storage.SectionStorage;
import me.cortex.voxy.common.world.other.Mapper;

import java.lang.invoke.VarHandle;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

public class WorldEngine {
    public static final int MAX_LOD_LAYER = 4;

    public static final int UPDATE_TYPE_BLOCK_BIT = 1;
    public static final int UPDATE_TYPE_CHILD_EXISTENCE_BIT = 2;
    public static final int UPDATE_TYPE_DONT_SAVE = 4;
    public static final int DEFAULT_UPDATE_FLAGS = UPDATE_TYPE_BLOCK_BIT | UPDATE_TYPE_CHILD_EXISTENCE_BIT;

    public interface ISectionChangeCallback {void accept(WorldSection section, int updateFlags, int neighborMsk);}
    public interface ISectionSaveCallback {boolean save(WorldEngine engine, WorldSection section, boolean nonBlocking, boolean sectionAlreadyAcquired);}


    public final SectionStorage storage;
    private final Mapper mapper;
    private final ActiveSectionTracker sectionTracker;
    private ISectionChangeCallback dirtyCallback;
    private ISectionSaveCallback saveCallback;
    volatile boolean isLive = true;

    public void setDirtyCallback(ISectionChangeCallback callback) {
        this.dirtyCallback = callback;
    }

    public void setSaveCallback(ISectionSaveCallback callback) {
        this.saveCallback = callback;
    }

    public Mapper getMapper() {return this.mapper;}
    public boolean isLive() {return this.isLive;}

    private final AtomicInteger refCount = new AtomicInteger();
    volatile long lastActiveTime = System.currentTimeMillis();//Time in millis the world was last "active" i.e. had a total ref count or active section count of != 0

    public WorldEngine(SectionStorage storage) {
        int cacheSize = 1024;
        if (Runtime.getRuntime().maxMemory()>=(1L<<32)-(200L<<20)) {
            cacheSize = 2048;
        }

        this.storage = storage;
        this.mapper = new Mapper(this.storage);
        //5 cache size bits means that the section tracker has 32 separate maps that it uses
        this.sectionTracker = new ActiveSectionTracker(6, storage::loadSection, cacheSize, this);
    }

    public WorldSection acquireIfExists(int lvl, int x, int y, int z) {
        if (!this.isLive) throw new IllegalStateException("World is not live");
        return this.sectionTracker.acquire(lvl, x, y, z, true);
    }

    public WorldSection acquire(int lvl, int x, int y, int z) {
        if (!this.isLive) throw new IllegalStateException("World is not live");
        return this.sectionTracker.acquire(lvl, x, y, z, false);
    }

    public WorldSection acquire(long pos) {
        if (!this.isLive) throw new IllegalStateException("World is not live");
        return this.sectionTracker.acquire(pos, false);
    }

    public WorldSection acquireIfExists(long pos) {
        if (!this.isLive) throw new IllegalStateException("World is not live");
        return this.sectionTracker.acquire(pos, true);
    }

    //TODO: Fixme/optimize, cause as the lvl gets higher, the size of x,y,z gets smaller so i can dynamically compact the format
    // depending on the lvl, which should optimize colisions and whatnot
    public static long getWorldSectionId(int lvl, int x, int y, int z) {
        return ((long)lvl<<60)|((long)(y&0xFF)<<52)|((long)(z&((1<<24)-1))<<28)|((long)(x&((1<<24)-1))<<4);//NOTE: 4 bits spare for whatever
    }

    public static int getLevel(long id) {
        return (int) ((id>>60)&0xf);
    }
    public static int getX(long id) {
        return (int) ((id<<36)>>40);
    }

    public static int getY(long id) {
        return (int) ((id<<4)>>56);
    }

    public static int getZ(long id) {
        return (int) ((id<<12)>>40);
    }

    public static String pprintPos(long pos) {
        return getLevel(pos)+"@["+getX(pos)+", "+getY(pos)+", " + getZ(pos)+"]";
    }

    //Marks a section as dirty, enqueuing it for saving and or render data rebuilding
    public void markDirty(WorldSection section) {
        this.markDirty(section, DEFAULT_UPDATE_FLAGS, 0);
    }

    public void markDirty(WorldSection section, int changeState, int neighborMsk) {
        if (!this.isLive) throw new IllegalStateException("World is not live");
        if (section.tracker != this.sectionTracker) {
            throw new IllegalStateException("Section is not from here");
        }
        if (this.dirtyCallback != null) {
            this.dirtyCallback.accept(section, changeState, neighborMsk);
        }
        if ((changeState&UPDATE_TYPE_DONT_SAVE)==0) {
            section.markDirty();
        }
    }

    /** Replaces one complete native Voxy section received from the authoritative Rust service. */
    public void replaceRemoteSection(long key, long revision, long[] data, byte nonEmptyChildren,
                                     int nonEmptyBlockCount) {
        int level = getLevel(key);
        if (data.length != WorldSection.SECTION_VOLUME || level > MAX_LOD_LAYER || (key & 0xf) != 0
                || (level == 0 && (nonEmptyBlockCount < 0
                || nonEmptyBlockCount > WorldSection.SECTION_VOLUME))) {
            throw new IllegalArgumentException("Invalid remote section");
        }
        WorldSection section = this.acquire(key);
        try {
            synchronized (section) {
                boolean blocksChanged = !Arrays.equals(section.data, data);
                boolean childrenChanged = section.nonEmptyChildren != nonEmptyChildren;
                boolean revisionChanged = section.getRemoteRevision() != revision;
                if (!blocksChanged && !childrenChanged && !revisionChanged) return;

                section.advanceStorageRevision();
                section.setRemoteRevision(revision);
                System.arraycopy(data, 0, section.data, 0, data.length);
                section.nonEmptyChildren = nonEmptyChildren;
                if (section.lvl == 0) section.nonEmptyBlockCount = nonEmptyBlockCount;
                int flags = (blocksChanged ? UPDATE_TYPE_BLOCK_BIT : 0)
                        | (childrenChanged ? UPDATE_TYPE_CHILD_EXISTENCE_BIT : 0);
                this.markDirty(section, flags, blocksChanged ? 0x3f : 0);
            }
        } finally {
            section.release();
        }
    }

    /** Makes stale or corrupt remote data absent and durably remembers its server revision. */
    public void invalidateRemoteSection(long key, long revision) {
        if (getLevel(key) > MAX_LOD_LAYER || (key & 0xf) != 0) throw new IllegalArgumentException("Invalid remote section key");
        WorldSection section = this.acquire(key);
        try {
            int flags;
            int neighborMask;
            synchronized (section) {
                section.advanceStorageRevision();
                section.setRemoteRevision(revision);
                long air = Mapper.airWithLight(15);
                boolean blocksChanged = false;
                for (long value : section.data) blocksChanged |= value != air;
                boolean childrenChanged = section.nonEmptyChildren != 0;
                Arrays.fill(section.data, air);
                section.nonEmptyChildren = 0;
                section.nonEmptyBlockCount = 0;

                // Keep a compact all-air record instead of deleting the key. Its remote
                // revision is the durable client tombstone, so reconnects do not request and
                // reapply the same server tombstone forever.
                this.storage.saveSection(section);
                section.setNotDirty();
                flags = (blocksChanged ? UPDATE_TYPE_BLOCK_BIT : 0)
                        | (childrenChanged ? UPDATE_TYPE_CHILD_EXISTENCE_BIT : 0);
                neighborMask = blocksChanged ? 0x3f : 0;
            }
            // Remove a contradictory parent bit first, so an in-flight child request cannot
            // briefly publish an empty child and replace the parent's coarse fallback.
            this.clearParentChild(section);
            if (flags != 0) {
                synchronized (section) {
                    this.markDirty(section, flags | UPDATE_TYPE_DONT_SAVE, neighborMask);
                }
            }
        } finally {
            section.release();
        }
    }

    /**
     * Re-publishes cached metadata when a subscription has no newer payload. A sparse server
     * store missing a key is not proof that terrain was deleted, so only a revisioned
     * invalidation may erase last-known-good client data.
     */
    public void refreshResolvedRemoteSection(long key) {
        this.refreshResolvedRemoteSection(key, false);
    }

    /** Returns false when a response claimed an indexed cached revision whose payload is gone. */
    public boolean refreshResolvedRemoteSection(long key, boolean requireCached) {
        if (getLevel(key) > MAX_LOD_LAYER || (key & 0xf) != 0) {
            throw new IllegalArgumentException("Invalid remote section key");
        }
        WorldSection section = requireCached ? this.acquireIfExists(key) : this.acquire(key);
        if (section == null) return false;
        try {
            synchronized (section) {
                this.markDirty(section, UPDATE_TYPE_CHILD_EXISTENCE_BIT | UPDATE_TYPE_DONT_SAVE, 0);
            }
        } finally {
            section.release();
        }
        return true;
    }

    /**
     * Removes a server-confirmed absent child from the parent's transient hierarchy. Keeping
     * this change out of storage preserves the last durable server revision, while the parent
     * mesh remains available as a hole-free coarse fallback until Rust publishes a repair.
     */
    private void clearParentChild(WorldSection child) {
        if (child.lvl >= MAX_LOD_LAYER) return;
        long parentKey = getWorldSectionId(child.lvl + 1, Math.floorDiv(child.x, 2),
                Math.floorDiv(child.y, 2), Math.floorDiv(child.z, 2));
        WorldSection parent = this.acquireIfExists(parentKey);
        if (parent == null) return;
        try {
            synchronized (parent) {
                if (parent.updateEmptyChildState(child) == 0) return;
                parent.advanceStorageRevision();
                this.markDirty(parent, UPDATE_TYPE_CHILD_EXISTENCE_BIT | UPDATE_TYPE_DONT_SAVE, 0);
            }
        } finally {
            parent.release();
        }
    }

    public void free() {
        if (!this.isLive) throw new IllegalStateException();
        this.isLive = false;
        VarHandle.fullFence();
        //Cannot free while there are loaded sections
        if (this.sectionTracker.getLoadedCacheCount() != 0) {
            throw new IllegalStateException();
        }

        try {this.mapper.close();} catch (Exception e) {Logger.error(e);}
        try {this.storage.flush();} catch (Exception e) {Logger.error(e);}
        //Shutdown in this order to preserve as much data as possible
        try {this.storage.close();} catch (Exception e) {Logger.error(e);}
    }

    private static final long TIMEOUT_MILLIS = 10_000;//10 second timeout (is to long? or to short??)
    public boolean isWorldUsed() {
        if (!this.isLive) throw new IllegalStateException();
        return this.refCount.get() != 0 || this.sectionTracker.getLoadedCacheCount() != 0;
    }

    public boolean isWorldIdle() {
        if (this.isWorldUsed()) {
            this.lastActiveTime = System.currentTimeMillis();//Force an update if is not active
            VarHandle.fullFence();
            return false;
        }
        return TIMEOUT_MILLIS<(System.currentTimeMillis()-this.lastActiveTime);
    }

    public void markActive() {
        if (!this.isLive) throw new IllegalStateException();
        this.lastActiveTime = System.currentTimeMillis();
    }

    public void acquireRef() {
        if (!this.isLive) throw new IllegalStateException();
        this.refCount.incrementAndGet();
        this.lastActiveTime = System.currentTimeMillis();
    }

    public void releaseRef() {
        if (!this.isLive) throw new IllegalStateException();
        if (this.refCount.decrementAndGet()<0) {
            throw new IllegalStateException("ref count less than 0");
        }
        //TODO: maybe dont need to tick the last active time?
        this.lastActiveTime = System.currentTimeMillis();
    }

    public boolean saveSection(WorldSection section) {
        return this.saveSection(section, false, false);
    }

    public boolean saveSection(WorldSection section, boolean nonBlocking, boolean sectionAlreadyAcquired) {
        if (this.saveCallback != null) {
            return this.saveCallback.save(this, section, nonBlocking, sectionAlreadyAcquired);
        }
        return false;
    }
}
