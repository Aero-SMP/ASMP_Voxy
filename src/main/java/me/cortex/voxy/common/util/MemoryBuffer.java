package me.cortex.voxy.common.util;

import org.lwjgl.system.MemoryUtil;

public class MemoryBuffer extends TrackedObject {
    public final long address;
    public final long size;
    private final boolean freeable;

    public MemoryBuffer(long size) {
        this(MemoryUtil.nmemAlloc(size), size, true);
    }

    private MemoryBuffer(long address, long size, boolean freeable) {
        this.size = size;
        this.address = address;
        this.freeable = freeable;
    }

    public void cpyTo(long dst) {
        super.assertNotFreed();
        UnsafeUtil.memcpy(this.address, dst, this.size);
    }

    public MemoryBuffer cpyFrom(long src) {
        super.assertNotFreed();
        UnsafeUtil.memcpy(src, this.address, this.size);
        return this;
    }

    @Override
    public void free() {
        super.free0();
        if (this.freeable) {
            MemoryUtil.nmemFree(this.address);
        } else {
            throw new IllegalArgumentException("Tried to free unfreeable buffer");
        }
    }

    public MemoryBuffer copy() {
        var copy = new MemoryBuffer(this.size);
        this.cpyTo(copy.address);
        return copy;
    }

    public MemoryBuffer zero() {
        MemoryUtil.memSet(this.address, 0, this.size);
        return this;
    }

    public static MemoryBuffer createUnfreeableRawFrom(long address, long size) {
        return new MemoryBuffer(address, size, false);
    }
}
