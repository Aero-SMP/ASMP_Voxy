package me.cortex.voxy.common.thread;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

final class ServiceManagerTest {
    @Test
    void deterministicSelectionHonorsWeightsAndLimiters() {
        ServiceManager manager = new ServiceManager(ignored -> {});
        AtomicInteger lowRuns = new AtomicInteger();
        AtomicInteger highRuns = new AtomicInteger();
        Service low = manager.createServiceNoCleanup(() -> lowRuns::incrementAndGet, 1, "low");
        Service high = manager.createServiceNoCleanup(() -> highRuns::incrementAndGet, 3, "high");
        for (int i = 0; i < 400; i++) {
            low.execute();
            high.execute();
        }
        for (int i = 0; i < 400; i++) assertEquals(0, manager.tryRunAJob());
        assertTrue(highRuns.get() > lowRuns.get() * 2);

        AtomicInteger blockedRuns = new AtomicInteger();
        Service blocked = manager.createService(() -> new me.cortex.voxy.common.util.Pair<>(
                blockedRuns::incrementAndGet, () -> {}), 100, "blocked", () -> false);
        blocked.execute();
        assertEquals(0, manager.tryRunAJob());
        assertEquals(0, blockedRuns.get());

        low.drain();
        high.drain();
        blocked.drain();
        low.shutdown();
        high.shutdown();
        blocked.shutdown();
        manager.shutdown();
    }

    @Test
    void fatalJobFailureCannotStrandShutdown() {
        ServiceManager manager = new ServiceManager(ignored -> {});
        Service service = manager.createServiceNoCleanup(() -> () -> {
            throw new AssertionError("expected");
        }, 1, "failure");
        service.execute();
        assertThrows(AssertionError.class, manager::tryRunAJob);
        assertTimeoutPreemptively(Duration.ofSeconds(1), service::shutdown);
        manager.shutdown();
    }
}
