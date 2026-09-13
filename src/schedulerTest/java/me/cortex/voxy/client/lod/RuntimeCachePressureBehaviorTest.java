package me.cortex.voxy.client.lod;

import java.util.concurrent.*;
import static me.cortex.voxy.client.lod.CacheStartupBehaviorTest.check;

/** Shared production-monitor test seam; disk resource cases live with the new journal tests. */
final class RuntimeCachePressureBehaviorTest {
    @FunctionalInterface interface CheckedAction { void run() throws Exception; }

    // The action runs as the fixture's owner, while a distinct thread owns the actual monitor.
    // Timeout detects deadlocks only; finally always releases the holder before joining either thread.
    static void whileBudgetHeld(RegionalDiskBudget budget, CheckedAction action) throws Exception {
        CountDownLatch held = new CountDownLatch(1), release = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        Future<?> holder = executor.submit(() -> { synchronized (budget) {
            held.countDown();
            try { release.await(); } catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
        } });
        try {
            check(held.await(5, TimeUnit.SECONDS), "budget holder did not start");
            executor.submit(() -> { action.run(); return null; }).get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown(); holder.get(5, TimeUnit.SECONDS);
            executor.shutdownNow(); check(executor.awaitTermination(5, TimeUnit.SECONDS), "lock test thread leaked");
        }
    }

    static void setOwner(ClientSession.Session session) throws Exception {
        var field = ClientSession.Session.class.getDeclaredField("thread"); field.setAccessible(true);
        field.set(session, Thread.currentThread());
    }
}
