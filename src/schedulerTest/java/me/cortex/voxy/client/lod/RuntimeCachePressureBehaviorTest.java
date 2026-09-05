package me.cortex.voxy.client.lod;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.concurrent.*;
import static me.cortex.voxy.client.lod.CacheStartupBehaviorTest.*;

/** Real runtime pressure and worker persistence, after inventory has finished below the limit. */
final class RuntimeCachePressureBehaviorTest {
    static void run() throws Exception {
        for (String mode : List.of("oldest", "ties", "protected", "no-eligible", "recorded")) eviction(mode);
        filesystemFailures();
        replacementAccounting();
        for (String mode : List.of("valid", "stamp", "view", "revision", "closed")) schedulerPersistence(mode);
        concurrentSnapshots();
        System.out.println("runtime pressure ordering, safeguards, worker persistence and nonblocking cache statistics tests passed");
    }

    private static void eviction(String mode) throws Exception {
        Path root = Files.createTempDirectory("voxy-runtime-pressure-");
        try {
            List<Path> payloads = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                Path path = root.resolve(i + ".vxcache");
                Files.write(path, new byte[100]); payloads.add(path);
                Files.setLastModifiedTime(path, FileTime.fromMillis(mode.equals("ties") ? 1000 : 1000 + i));
            }
            Path catalog = root.resolve("referenced.vxcat"), descriptor = root.resolve("owner.vxmeta");
            Files.write(catalog, new byte[10]); Files.write(descriptor, new byte[10]);
            Files.setLastModifiedTime(catalog, FileTime.fromMillis(0));
            List<Path> encounter;
            try (var files = Files.walk(root)) { encounter = files.filter(payloads::contains).toList(); }
            var budget = new RegionalDiskBudget(root, 520, path -> catalog);
            try (var store = new RegionalMetadataStore(budget); var pin = budget.pin(descriptor);
                 var payloadPin = budget.pin(mode.equals("protected") ? payloads.get(1) : root.resolve("unused"))) {
                awaitInventory(budget);
                List<Path> attempts = new ArrayList<>();
                synchronized (budget) {
                    check(budget.bytes == 520 && budget.eviction == 0, "tested startup cleanup instead of runtime pressure");
                    for (Path path : payloads) budget.register(path, null, () -> {
                        attempts.add(path);
                        if (mode.equals("recorded") && path.equals(payloads.getFirst())) try {
                            // All candidates have already been classified/aged. Later mutations must
                            // not reprioritize them, but deletion must charge the actual current size.
                            Files.setLastModifiedTime(payloads.get(1), FileTime.fromMillis(99999));
                            Files.setLastModifiedTime(payloads.get(4), FileTime.fromMillis(0));
                            Files.write(payloads.get(1), new byte[150]); budget.bytes += 50;
                        } catch (IOException failure) { throw new AssertionError(failure); }
                        return !mode.equals("no-eligible") && (!mode.equals("protected") || !path.equals(payloads.get(2)));
                    });
                    check(budget.ensure(0, Set.of()) && attempts.isEmpty(), "no-pressure request evicted files");
                    check(!budget.ensure(-1, Set.of()) && !budget.ensure(521, Set.of()) && attempts.isEmpty(), "invalid admission evicted files");
                    boolean admitted = budget.ensure(200, mode.equals("protected") ? Set.of(payloads.getFirst()) : Set.of());
                    if (mode.equals("no-eligible")) {
                        check(!admitted && budget.bytes == 520 && budget.eviction == 0, "failed deletion authorized growth or changed accounting");
                        check(attempts.equals(payloads), "ineligible scan retried or skipped candidates");
                    } else {
                        List<Path> expected = mode.equals("ties") ? encounter.subList(0, 2)
                                : mode.equals("protected") ? payloads.subList(2, 5) : payloads.subList(0, 2);
                        check(admitted && attempts.equals(expected), "runtime age/encounter ordering or early stop changed: " + mode + attempts);
                        check(budget.bytes == 320 && budget.eviction == 2, "actual deletion sizes/stamps not accounted");
                    }
                    check(Files.exists(catalog) && Files.exists(descriptor), "referenced catalog or pinned descriptor evicted");
                    check(budget.bytes == diskBytes(root), "runtime accounting differs from disk");
                }
            }
        } finally { cleanup(root); }
    }

    private static void filesystemFailures() throws Exception {
        Path root = Files.createTempDirectory("voxy-runtime-failures-");
        Path path = root.resolve("only.vxcache"); Files.write(path, new byte[100]);
        var budget = new RegionalDiskBudget(root, 100);
        try (var store = new RegionalMetadataStore(budget)) {
            awaitInventory(budget);
            synchronized (budget) {
                // A dangling link is unclassifiable, not a fabricated oldest candidate.
                Path link = root.resolve("missing.vxcache");
                Files.createSymbolicLink(link, root.resolve("absent"));
                check(!budget.ensure(1, Set.of(path)) && Files.isSymbolicLink(link), "unreadable candidate authorized capacity");
                budget.register(path, null, () -> {
                    try { Files.delete(path); } catch (IOException failure) { throw new AssertionError(failure); }
                    return true;
                });
                check(!budget.ensure(1, Set.of()) && budget.bytes == 100 && budget.eviction == 0,
                        "externally vanished candidate invented freed bytes");
                budget.unregister(path, null);
                // The file may change after classification, before the actual delete.
                Files.write(path, new byte[100]);
                budget.register(path, null, () -> {
                    try {
                        Files.delete(path); Files.createDirectory(path); Files.write(path.resolve("child"), new byte[100]);
                    } catch (IOException failure) { throw new AssertionError(failure); }
                    return true;
                });
                check(!budget.ensure(1, Set.of()) && budget.bytes == 100 && budget.eviction == 0,
                        "failed nonempty-directory deletion lost accounting");
                budget.unregister(path, null);
                check(budget.bytes == diskBytes(root), "failure accounting changed");
            }
            // No-pressure returns must not traverse the filesystem. A traversal failure under
            // actual pressure must propagate, rather than authorize a write from partial results.
            Path moved = root.resolveSibling(root.getFileName() + "-moved");
            Files.move(root, moved);
            try {
                synchronized (budget) {
                    check(budget.ensure(0, Set.of()), "no-pressure path traversed missing root");
                    try {
                        budget.ensure(1, Set.of());
                        throw new AssertionError("traversal failure swallowed");
                    } catch (NoSuchFileException expected) { /* No partial scan may admit writes. */ }
                }
                Files.write(root, new byte[0]);
                synchronized (budget) {
                    check(budget.ensure(0, Set.of()), "no-pressure path touched disk");
                    check(!budget.ensure(1, Set.of()), "unavailable managed tree authorized growth");
                }
            } finally { Files.delete(root); Files.move(moved, root); }
        } finally { cleanup(root); }
    }

    private static void replacementAccounting() throws Exception {
        Path root = Files.createTempDirectory("voxy-runtime-replacement-");
        var budget = new RegionalDiskBudget(root, 8192);
        var f = fixture(1, 0, 255, 1);
        try (var store = new RegionalMetadataStore(budget)) {
            persist(store, f, true);
            Path target = store.descriptor(WORLD, DIMENSION, 0, 0);
            Path catalog = RegionalMetadataStore.referencedCatalog(target);
            Path victim = root.resolve("old.vxcache");
            synchronized (budget) {
                int free = (int) (budget.limit - budget.bytes);
                Files.write(victim, new byte[free]); budget.bytes += free;
                Files.setLastModifiedTime(victim, FileTime.fromMillis(0));
                long stamp = budget.stamp();
                var calls = new java.util.concurrent.atomic.AtomicInteger();
                store.saveRegion(WORLD, DIMENSION, 0, 0, null, stamp, () -> {
                    if (calls.incrementAndGet() == 2) try {
                        Path pending = target.resolveSibling(target.getFileName() + ".pending");
                        check(Files.exists(target) && Files.exists(pending), "old/new replacement overlap not exercised");
                        check(budget.bytes == diskBytes(root) && budget.bytes <= budget.limit, "temporary overlap uncharged");
                    } catch (IOException failure) { throw new AssertionError(failure); }
                    return true;
                });
                check(calls.get() == 2 && !Files.exists(victim), "replacement did not evict under runtime pressure");
                check(store.region(WORLD, DIMENSION, 0, 0).absent() && !Files.exists(catalog), "reference release failed");
                check(budget.stamp() > stamp && budget.bytes == diskBytes(root), "replacement/stamp accounting mismatch");
            }
        } finally { cleanup(root); }
    }

    private static void schedulerPersistence(String mode) throws Exception {
        Path root = Files.createTempDirectory("voxy-runtime-enqueue-");
        var old = fixture(1, 0, 255, 1); var next = fixture(2, 0, 127, 1);
        try (var store = new RegionalMetadataStore(root); var otherOwner = new RegionalMetadataStore(store.budget)) {
            persist(store, old, false);
            var session = new ClientSession.Session(781, DIMENSION, null, new Publisher(), null, 0);
            session.metadata = store; session.worldIdentity = WORLD;
            session.demands.adopt(new ClientSession.Demand(KEY)); session.queueRegion(0);
            var state = session.demands.region(0);
            try {
                whileBudgetHeld(store.budget, () -> {
                    setOwner(session);
                    String before = store.budget.snapshot();
                    session.saveRegion(state, old.message());
                    Object first = session.metadataWrites.get(0L);
                    session.saveRegion(state, next.message());
                    check(session.metadataWrites.size() == 1 && session.metadataWrites.get(0L) != first, "same-region writes not coalesced");
                    check(store.budget.snapshot().equals(before), "enqueue changed writable-check accounting");
                });
                switch (mode) {
                    case "stamp" -> { synchronized (store.budget) {
                        try (var files = Files.walk(root)) {
                            check(store.budget.delete(files.filter(p -> p.toString().endsWith(".vxlink")).findFirst().orElseThrow()), "stamp not advanced");
                        }
                    } }
                    case "view" -> session.viewRevision++;
                    case "revision" -> state.metadataRevision++;
                    case "closed" -> store.close(); // Other owner keeps accounting READY; store itself must reject.
                    case "valid" -> {}
                    default -> throw new AssertionError(mode);
                }
                session.metadataWorker.start();
                ClientSession.Session.WorkerTask task = session.metadataWrites.remove(0L);
                session.metadataWorker.assign(task);
                long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (session.metadataWorker.resource.state() != WorkerResource.State.COMPLETED && System.nanoTime() < end) Thread.sleep(1);
                var result = session.metadataWorker.resource.claim();
                check(result != null && result.value().getClass().getSimpleName().equals("WorkerSaved"), "persistence worker did not complete");
                session.metadataWorker.releaseCompletion(result.lease());
                check(otherOwner.region(WORLD, DIMENSION, 0, 0).message().generation() == (mode.equals("valid") ? 2 : 1),
                        "worker accepted stale/closed persistence: " + mode);
                synchronized (store.budget) { check(store.budget.bytes == diskBytes(root), "worker accounting mismatch"); }
            } finally {
                session.open.set(false); session.metadataWorker.close(); session.metadataWorker.workerThread.join(5000);
            }
        } finally { cleanup(root); }
    }

    private static void concurrentSnapshots() throws Exception {
        Path root = Files.createTempDirectory("voxy-runtime-snapshot-");
        var budget = new RegionalDiskBudget(root, 10);
        try {
            var writer = Executors.newSingleThreadExecutor();
            try {
                var future = writer.submit(() -> { for (int i = 0; i < 10000; i++) budget.writable(); });
                for (int i = 0; i < 10000; i++) {
                    String text = budget.snapshot();
                    check(text.contains("cacheInventory=NEW cacheDiskBytes=-1 cacheDiskLimit=10")
                            && text.matches(".*cacheSkippedWrites=\\d+ cacheInventoryFailure=null"), "unsafe approximate formatting");
                }
                future.get(5, TimeUnit.SECONDS);
                synchronized (budget) { check(budget.snapshot().contains("cacheSkippedWrites=10000 "), "lost synchronized increments"); }
            } finally { writer.shutdownNow(); }
        } finally { cleanup(root); }
    }

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
    private static long diskBytes(Path root) throws IOException {
        try (var files = Files.walk(root)) { return files.filter(Files::isRegularFile).mapToLong(RegionalDiskBudget::size).sum(); }
    }
}
