package me.cortex.voxy.common.util;

import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

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

    //Creates a new MemoryBuffer, defunking this buffer and sets the size to be a subsize of the current size
    public MemoryBuffer subSize(long size) {
        if (size > this.size || size <= 0) {
            throw new IllegalArgumentException("Requested size larger than current size, or less than 0, requested: "+size+" capacity: " + this.size);
        }

        //Free the current object, but not the memory associated with it
        this.free0();
        return new MemoryBuffer(this.address, size, this.freeable);
    }

    public MemoryBuffer zero() {
        MemoryUtil.memSet(this.address, 0, this.size);
        return this;
    }

    public ByteBuffer asByteBuffer() {
        return MemoryUtil.memByteBuffer(this.address, (int) this.size);
    }

    //TODO: create like Long(offset) -> value at offset
    // methods for get and set, that way can have a single unifed system to ensure memory access bounds


    public static MemoryBuffer createUnfreeableRawFrom(long address, long size) {
        return new MemoryBuffer(address, size, false);
    }

    public MemoryBuffer createUnfreeableReference() {
        return new MemoryBuffer(this.address, this.size, false);
    }

}
