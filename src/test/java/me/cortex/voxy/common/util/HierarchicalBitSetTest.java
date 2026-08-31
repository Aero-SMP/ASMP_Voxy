package me.cortex.voxy.common.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class HierarchicalBitSetTest {
    @Test
    void allocationCanExactlyFillCapacity() {
        HierarchicalBitSet allocator = new HierarchicalBitSet(8);
        assertEquals(0, allocator.allocateNextConsecutiveCounted(8));
        assertEquals(8, allocator.getCount());
        assertEquals(7, allocator.getMaxIndex());
        assertEquals(-1, allocator.allocateNext());
    }

    @Test
    void sixtyFourBitRangeDoesNotProduceAnEmptyMask() {
        HierarchicalBitSet allocator = new HierarchicalBitSet(65);
        assertEquals(0, allocator.allocateNextConsecutiveCounted(64));
        for (int i = 0; i < 64; i++) assertTrue(allocator.isSet(i));
        assertFalse(allocator.isSet(64));
        assertEquals(64, allocator.allocateNext());
    }

    @Test
    void consecutiveAllocationSkipsOccupiedRangesAndReusesFreedIds() {
        HierarchicalBitSet allocator = new HierarchicalBitSet(12);
        assertEquals(0, allocator.allocateNextConsecutiveCounted(4));
        assertEquals(4, allocator.allocateNextConsecutiveCounted(4));
        assertTrue(allocator.free(2));
        assertTrue(allocator.free(3));
        assertEquals(2, allocator.allocateNextConsecutiveCounted(2));
        assertEquals(8, allocator.getCount());
    }
}
