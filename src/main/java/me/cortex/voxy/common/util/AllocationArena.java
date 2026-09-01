package me.cortex.voxy.common.util;

import it.unimi.dsi.fastutil.longs.LongRBTreeSet;

/** A compact best-fit address allocator with 34-bit addresses and 30-bit allocation sizes. */
public class AllocationArena {
    public static final long SIZE_LIMIT = -1;

    private static final int ADDR_BITS = 34;
    private static final int SIZE_BITS = 64 - ADDR_BITS;
    private static final long SIZE_MSK = (1L << SIZE_BITS) - 1;
    private static final long ADDR_MSK = (1L << ADDR_BITS) - 1;
    private static final long ADDR_LIMIT = 1L << ADDR_BITS;
    private final LongRBTreeSet FREE = new LongRBTreeSet(Long::compareUnsigned); // size, address
    private final LongRBTreeSet TAKEN = new LongRBTreeSet(Long::compareUnsigned); // address, size

    private long sizeLimit = ADDR_LIMIT;
    private long totalSize;
    public void reset() {
        this.FREE.clear();
        this.TAKEN.clear();
        this.sizeLimit = ADDR_LIMIT;
        this.totalSize = 0;
    }

    public long getSize() {
        return this.totalSize;
    }

    public long alloc(int size) {
        if (size <= 0 || size > SIZE_MSK) {
            throw new IllegalArgumentException("allocation size is outside the 30-bit range");
        }
        // The iterator is exclusive, so begin one key before the requested size.
        var iter = this.FREE.iterator(((long) size << ADDR_BITS) - 1);
        if (!iter.hasNext()) {//No free space for allocation
            //Create new allocation
            long addr = this.totalSize;
            if (this.totalSize + size > this.sizeLimit) {
                return SIZE_LIMIT;
            }
            this.totalSize += size;
            this.TAKEN.add((addr<<SIZE_BITS)|((long) size));
            return addr;
        } else {
            long slot = iter.nextLong();
            iter.remove();
            if ((slot >>> ADDR_BITS) == size) {//If the allocation and slot is the same size, just add it to the taken
                this.TAKEN.add((slot<<SIZE_BITS)|(slot >>> ADDR_BITS));
            } else {
                this.TAKEN.add(((slot&ADDR_MSK)<<SIZE_BITS)|size);
                this.FREE.add((((slot >>> ADDR_BITS)-size)<<ADDR_BITS)|((slot&ADDR_MSK)+size));
            }
            return slot&ADDR_MSK;
        }
    }

    public int free(long addr) {//Returns size of freed memory
        addr &= ADDR_MSK; // Callers may store metadata in the unused upper bits.
        var iter = this.TAKEN.iterator(addr << SIZE_BITS);
        if (!iter.hasNext()) {
            throw new IllegalStateException("address is not allocated");
        }
        long slot = iter.nextLong();
        if (slot >>> SIZE_BITS != addr) {
            throw new IllegalStateException("address is not allocated");
        }
        long size = slot&SIZE_MSK;
        iter.remove();

        //Note: if there is a previous entry, it means that it is guaranteed for the ending address to either
        // be the addr, or indicate a free slot that needs to be merged
        if (iter.hasPrevious()) {
            long prevSlot = iter.previousLong();
            long endAddr = (prevSlot>>>SIZE_BITS) + (prevSlot&SIZE_MSK);
            if (endAddr != addr) {//It means there is a free slot that needs to get merged into
                long delta = (addr - endAddr);
                this.FREE.remove((delta<<ADDR_BITS)|endAddr);//Free the slot to be merged into
                //Generate a new slot to get put into FREE
                slot = (endAddr<<SIZE_BITS) | ((slot&SIZE_MSK) + delta);
            }
            iter.nextLong();//Need to reset the iter into its state
        }//If there is no previous it means were at the start of the buffer, we might need to merge with block 0 if we are not block 0
        else if (!this.FREE.isEmpty()) {// if free is not empty it means we must merge with block of free starting at 0
            if (this.FREE.remove(addr<<ADDR_BITS)) {//Attempt to remove block 0, this is very dodgy as it assumes block zero is 0 addr n size
                slot = addr + size;//slot at address 0 and size of 0 block + new block
            }
        }

        // If there is a next element it is guaranteed to either be the next block or indicate that there is
        // a block that needs to be merged into
        if (iter.hasNext()) {
            long nextSlot = iter.nextLong();
            long endAddr = (slot>>>SIZE_BITS) + (slot&SIZE_MSK);
            if (endAddr != nextSlot>>>SIZE_BITS) {//It means there is a memory block to be merged in FREE
                long delta = ((nextSlot>>>SIZE_BITS) - endAddr);
                this.FREE.remove((delta<<ADDR_BITS)|endAddr);
                slot = (slot&(ADDR_MSK<<SIZE_BITS)) | ((slot&SIZE_MSK) + delta);
            }
        }// if there is no next block it means that we have reached the end of the allocation sections and we can shrink the buffer
        else {
            this.totalSize -= (slot&SIZE_MSK);
            return (int) size;
        }

        //Need to swap around the slot to be in FREE format
        slot = (slot>>>SIZE_BITS) | (slot<<ADDR_BITS);
        this.FREE.add(slot);//Add the free slot into segments
        return (int) size;
    }



    /** Attempts to expand an allocation in place. */
    public boolean expand(long addr, int extra) {
        if (extra <= 0) {
            throw new IllegalArgumentException("expansion size must be positive");
        }
        addr &= ADDR_MSK; // Callers may store metadata in the unused upper bits.
        var iter = this.TAKEN.iterator(addr<<SIZE_BITS);
        if (!iter.hasNext()) {
            return false;
        }
        long slot = iter.nextLong();
        if (slot >>> SIZE_BITS != addr) {
            throw new IllegalStateException("address is not allocated");
        }
        long expandedSize = (slot & SIZE_MSK) + extra;
        if (expandedSize > SIZE_MSK) return false;
        long updatedSlot = (slot & (ADDR_MSK << SIZE_BITS)) | expandedSize;
        if (iter.hasNext()) {
            long next = iter.nextLong();
            long endAddr = (slot>>>SIZE_BITS)+(slot&SIZE_MSK);
            long delta = (next>>>SIZE_BITS) - endAddr;
            if (extra <= delta) {
                this.FREE.remove((delta<<ADDR_BITS)|endAddr);//Should assert this
                // Restore the iterator to the current allocation before removing it.
                iter.previousLong();
                iter.previousLong();
                iter.remove();//Remove the allocation so it can be updated
                this.TAKEN.add(updatedSlot);//Update the taken allocation
                if (extra != delta) {//More space than needed, need to add a new FREE block
                    this.FREE.add(((delta-extra)<<ADDR_BITS)|(endAddr+extra));
                }
                //else There is exactly enough free space, so removing the free block and updating the allocation is enough
                return true;
            } else {
                return false;//Not enough room to expand
            }
        } else {//We are at the end of the buffer, we can expand as we like
            if (this.totalSize+extra>this.sizeLimit)//If expanding and we would exceed the size limit, dont resize
                return false;
            iter.remove();
            this.TAKEN.add(updatedSlot);
            this.totalSize += extra;
            return true;
        }
    }

    public void setLimit(long size) {
        if (size < 0 || size > ADDR_LIMIT) {
            throw new IllegalArgumentException("size limit is outside the 34-bit address range");
        }
        if (size < this.totalSize) {
            throw new IllegalStateException("size limit is below current usage");
        }
        this.sizeLimit = size;
    }

    public long getLimit() {
        return this.sizeLimit;
    }
}
