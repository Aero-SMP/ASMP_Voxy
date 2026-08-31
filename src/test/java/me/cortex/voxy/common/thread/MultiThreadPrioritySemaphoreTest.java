package me.cortex.voxy.common.thread;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class MultiThreadPrioritySemaphoreTest {
    @Test
    void localPermitsTakePriorityOverSharedWork() {
        AtomicInteger jobs = new AtomicInteger();
        MultiThreadPrioritySemaphore semaphore = new MultiThreadPrioritySemaphore(() -> {
            jobs.incrementAndGet();
            return 0;
        });
        MultiThreadPrioritySemaphore.Block block = semaphore.createBlock();
        semaphore.pooledRelease(1);
        block.release(1);

        block.acquire();

        assertEquals(0, jobs.get());
        assertFalse(block.tryAcquire());
        block.free();
    }

    @Test
    void waitingThreadHelpsUntilItsLocalPermitArrives() {
        AtomicInteger jobs = new AtomicInteger();
        AtomicReference<MultiThreadPrioritySemaphore.Block> blockRef = new AtomicReference<>();
        MultiThreadPrioritySemaphore semaphore = new MultiThreadPrioritySemaphore(() -> {
            if (jobs.incrementAndGet() == 3) blockRef.get().release(1);
            return 0;
        });
        MultiThreadPrioritySemaphore.Block block = semaphore.createBlock();
        blockRef.set(block);
        semaphore.pooledRelease(3);

        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> block.acquire());

        assertEquals(3, jobs.get());
        assertEquals(0, block.availablePermits());
        block.free();
    }

    @Test
    void freedBlocksRejectFurtherUse() {
        MultiThreadPrioritySemaphore.Block block = new MultiThreadPrioritySemaphore(() -> 1).createBlock();
        block.free();
        assertThrows(IllegalStateException.class, block::tryAcquire);
        assertThrows(IllegalStateException.class, () -> block.release(1));
    }
}
