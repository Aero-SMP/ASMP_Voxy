package me.cortex.voxy.common.world;


import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.ArrayBlockingQueue;

//Represents a loaded world section at a specific detail level
// holds a 32x32x32 region of detail
public final class WorldSection {
    public static final int SECTION_VOLUME = 32*32*32;
    static final VarHandle ATOMIC_STATE_HANDLE;
    private static final VarHandle NON_EMPTY_CHILD_HANDLE;
    private static final VarHandle IN_SAVE_QUEUE_HANDLE;
    private static final VarHandle IS_DIRTY_HANDLE;

    static {
        try {
            ATOMIC_STATE_HANDLE = MethodHandles.lookup().findVarHandle(WorldSection.class, "atomicState", int.class);
            NON_EMPTY_CHILD_HANDLE = MethodHandles.lookup().findVarHandle(WorldSection.class, "nonEmptyChildren", byte.class);
            IN_SAVE_QUEUE_HANDLE = MethodHandles.lookup().findVarHandle(WorldSection.class, "inSaveQueue", boolean.class);
            IS_DIRTY_HANDLE = MethodHandles.lookup().findVarHandle(WorldSection.class, "isDirty", boolean.class);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }


    //TODO: should make it dynamically adjust the size allowance based on memory pressure/WorldSection allocation rate (e.g. is it doing a world import)
    private static final int ARRAY_REUSE_CACHE_SIZE = 400;//500;//32*32*32*8*ARRAY_REUSE_CACHE_SIZE == number of bytes
    private static final ArrayBlockingQueue<long[]> ARRAY_REUSE_CACHE = new ArrayBlockingQueue<>(ARRAY_REUSE_CACHE_SIZE);


    public final int lvl;
    public final int x;
    public final int y;
    public final int z;
    public final long key;


    //Serialized states
    long metadata;
    long[] data = null;
    volatile int nonEmptyBlockCount = 0;//Note: only needed for level 0 sections
    volatile byte nonEmptyChildren;

    final ActiveSectionTracker tracker;
    volatile boolean inSaveQueue;
    volatile boolean isDirty;
    private long storageRevision;
    private long remoteRevision = -1;

    //When the first bit is set it means its loaded
    @SuppressWarnings("all")
    private volatile int atomicState = 1;

    WorldSection(int lvl, int x, int y, int z, ActiveSectionTracker tracker) {
        this.lvl = lvl;
        this.x = x;
        this.y = y;
        this.z = z;
        this.key = WorldEngine.getWorldSectionId(lvl, x, y, z);
        this.tracker = tracker;

        this.data = ARRAY_REUSE_CACHE.poll();
        if (this.data == null) {
            this.data = new long[32 * 32 * 32];
        }
    }

    void primeForReuse() {
        ATOMIC_STATE_HANDLE.set(this, 1);
    }

    public long[] _unsafeGetRawDataArray() {
        return this.data;
    }

    @Override
    public int hashCode() {
        return ((x*1235641+y)*8127451+z)*918267913+lvl;
    }

    public boolean tryAcquire() {
        int prev, next;
        do {
            prev = (int) ATOMIC_STATE_HANDLE.get(this);
            if ((prev&1) == 0) {
                //The object has been release so early exit
                return false;
            }
            next = prev + 2;
        } while (!ATOMIC_STATE_HANDLE.compareAndSet(this, prev, next));
        return true;
    }

    public int acquire() {
        int state = ((int) ATOMIC_STATE_HANDLE.getAndAdd(this, 2)) + 2;
        if ((state & 1) == 0) {
            throw new IllegalStateException("Tried to acquire unloaded section: " + WorldEngine.pprintPos(this.key) + " obj: " + System.identityHashCode(this));
        }
        return state>>1;
    }

    public int getRefCount() {
        return ((int)ATOMIC_STATE_HANDLE.get(this))>>1;
    }

    public int release() {
        return release(true, 0);
    }


    public static final int RELEASE_HINT_POSSIBLE_REUSE = 1;
    //Unload but specify possible reuse hints
    public int release(int hints) {
        return release(true, hints);
    }

    int release(boolean unload, int hints) {
        int state = ((int) ATOMIC_STATE_HANDLE.getAndAdd(this, -2)) - 2;
        if (state < 1) {
            throw new IllegalStateException("Section got into an invalid state");
        }
        if ((state & 1) == 0) {
            throw new IllegalStateException("Tried releasing a freed section");
        }
        if ((state>>1)==0 && unload) {
            if (this.tracker != null) {
                this.tracker.tryUnload(this, hints);
            } else {
                //This should _ONLY_ ever happen when its an untracked section
                // If it is, try release it
                if (this.trySetFreed()) {
                    this._releaseArray();
                }
            }
        }
        return state>>1;
    }

    //Returns true on success, false on failure
    boolean trySetFreed() {
        int witness = (int) ATOMIC_STATE_HANDLE.compareAndExchange(this, 1, 0);
        if ((witness & 1) == 0 && witness != 0) {
            throw new IllegalStateException("Section marked as free but has refs");
        }
        if (witness == 1 && (this.isDirty || this.inSaveQueue)) {
            throw new IllegalStateException("Section freed while marked as dirty or in the save queue: " + (this.isDirty?"dirty, ":"") + (this.inSaveQueue?"saveQueue":""));
        }
        return witness == 1;
    }

    void _releaseArray() {
        ARRAY_REUSE_CACHE.offer(this.data);
        this.data = null;
    }


    public byte getNonEmptyChildren() {
        return (byte) NON_EMPTY_CHILD_HANDLE.get(this);
    }

    //Updates this.nonEmptyChildren atomically with respect to the child passed in
    // returns 0 if no change, 1 if it just updated and didnt do a major state change, 2 if it was a major state change (something -> nothing, nothing -> something)
    public int updateEmptyChildState(WorldSection child) {
        int childIdx = (child.x & 1) | ((child.y & 1) << 2) | ((child.z & 1) << 1);
        byte msk = (byte) (1<<childIdx);
        byte prev, next;
        do {
            prev = this.getNonEmptyChildren();
            next = (byte) ((prev&(~msk))|(child.getNonEmptyChildren()!=0?msk:0));
        } while (!NON_EMPTY_CHILD_HANDLE.compareAndSet(this, prev, next));

        return ((prev!=0)^(next!=0))?2:(prev!=next?1:0);
    }

    public int getNonEmptyBlockCount() {
        return this.nonEmptyBlockCount;
    }

    public void markDirty() {
        IS_DIRTY_HANDLE.getAndSet(this, true);
    }


    public boolean exchangeIsInSaveQueue(boolean state) {
        return ((boolean) IN_SAVE_QUEUE_HANDLE.compareAndExchange(this, !state, state)) == !state;
    }

    //Should only be called by the saving service
    public boolean setNotDirty() {
        return (boolean) IS_DIRTY_HANDLE.getAndSet(this, false);
    }

    public long getStorageRevision() {
        return this.storageRevision;
    }

    public void advanceStorageRevision() {
        this.storageRevision++;
    }

    public long getRemoteRevision() {
        return this.remoteRevision;
    }

    void setRemoteRevision(long revision) {
        this.remoteRevision = revision;
    }

    public boolean shouldSave() {
        return this.isDirty&&!this.inSaveQueue;
    }

    public boolean isFreed() {
        return (((int)ATOMIC_STATE_HANDLE.get(this))&1)==0;
    }
}
