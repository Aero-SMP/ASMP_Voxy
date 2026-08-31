package me.cortex.voxy.common.world;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

final class ActiveSectionTrackerTest {
    @Test
    void failedLoadReleasesEveryWaiterAndCanBeRetried() {
        AtomicBoolean fail = new AtomicBoolean(true);
        CountDownLatch loading = new CountDownLatch(1);
        CountDownLatch continueLoad = new CountDownLatch(1);
        ActiveSectionTracker tracker = new ActiveSectionTracker(1, section -> {
            loading.countDown();
            try {
                continueLoad.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
            if (fail.getAndSet(false)) throw new IllegalStateException("expected failure");
            return 0;
        }, 2);
        long key = WorldEngine.getWorldSectionId(0, 1, 2, 3);

        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            try (var workers = Executors.newFixedThreadPool(2)) {
                var loader = workers.submit(() -> tracker.acquire(key, false));
                loading.await();
                var waiter = workers.submit(() -> tracker.acquire(key, false));
                continueLoad.countDown();
                assertInstanceOf(IllegalStateException.class, assertThrows(Exception.class, loader::get).getCause());
                try {
                    waiter.get(1, TimeUnit.SECONDS).release();
                } catch (ExecutionException failedWaiter) {
                    assertInstanceOf(IllegalStateException.class, failedWaiter.getCause());
                }
            }
        });
        assertEquals(0, tracker.getLoadedCacheCount());

        WorldSection retry = tracker.acquire(key, false);
        assertEquals(key, retry.key);
        retry.release();
        assertEquals(0, tracker.getLoadedCacheCount());
    }
}
