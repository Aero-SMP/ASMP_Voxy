package me.cortex.voxy.client.lod;

import java.io.IOException;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static me.cortex.voxy.client.lod.CacheStartupBehaviorTest.*;

/** Production inventory and foreground workers; only descriptor I/O is delayed/faulted. */
final class CacheInventoryBehaviorTest {
    static void run() throws Exception {
        foregroundDuringInventory();
        failedAndUnstableInventory();
        inventoryOrderingAndGarbage();
        changedBetweenPasses();
        oversizedAndPinnedCleanup();
        ownershipAndReopen();
        System.out.println("background inventory, read-only repair guards, accounting, pins and ownership tests passed");
    }

    private static void inventoryOrderingAndGarbage() throws Exception {
        for (String mode : List.of("under", "garbage", "aged", "ties", "protected")) {
            Path root = Files.createTempDirectory("voxy-inventory-" + mode + '-');
            try {
                List<Path> payloads = new ArrayList<>();
                for (int i = 0; i < 5; i++) {
                    Path path = root.resolve("part-" + i + ".vxcache");
                    Files.write(path, new byte[100]); payloads.add(path);
                    Files.setLastModifiedTime(path, java.nio.file.attribute.FileTime.fromMillis(
                            mode.equals("ties") ? 1000 : (i + 1) * 1000));
                }
                // Observe this fixture's actual encounter order; never assume lexical directory order.
                List<Path> encounter;
                try (var paths = Files.walk(root)) { encounter = paths.filter(payloads::contains).toList(); }
                if (mode.equals("garbage")) {
                    Files.write(root.resolve("orphan.vxcat"), new byte[200]);
                    Files.write(root.resolve("leftover.vxmeta.pending"), new byte[200]);
                }
                long limit = mode.equals("under") || mode.equals("garbage") ? 500 : 300;
                Path catalog = root.resolve("referenced.vxcat");
                if (mode.equals("protected")) {
                    Files.write(catalog, new byte[10]);
                    Files.write(root.resolve("owner.vxmeta"), new byte[10]);
                    Files.setLastModifiedTime(catalog, java.nio.file.attribute.FileTime.fromMillis(0));
                    limit = 320;
                }
                var budget = new RegionalDiskBudget(root, limit, path -> catalog);
                List<Path> attempts = Collections.synchronizedList(new ArrayList<>());
                // Real deletion uses registered lease callbacks; record attempts without replacing eviction.
                synchronized (budget) {
                    for (Path path : payloads) budget.register(path, null, () -> {
                        attempts.add(path);
                        return !mode.equals("protected") || !path.equals(payloads.get(1));
                    });
                }
                try (var pin = budget.pin(mode.equals("protected") ? payloads.get(0) : root.resolve("unused"));
                     var store = new RegionalMetadataStore(budget)) {
                    awaitInventory(budget);
                    check(budget.bytes == diskBytes(root), mode + " accounting mismatch");
                    if (mode.equals("under") || mode.equals("garbage")) {
                        check(payloads.stream().allMatch(Files::exists) && attempts.isEmpty(), "unnecessary pressure eviction");
                        check(budget.bytes == 500, "garbage did not bring cache under budget");
                    } else if (mode.equals("protected")) {
                        check(Files.exists(payloads.get(0)) && Files.exists(payloads.get(1)) && Files.exists(catalog),
                                "pin, busy lease or referenced catalog evicted");
                        check(!Files.exists(payloads.get(2)) && !Files.exists(payloads.get(3)) && Files.exists(payloads.get(4)),
                                "oldest eligible payloads not evicted");
                        check(budget.bytes == 320, "protected files misaccounted");
                    } else {
                        List<Path> order = mode.equals("ties") ? encounter : payloads;
                        check(attempts.equals(order.subList(0, 2)), "age/stable encounter ordering changed: " + attempts);
                        check(budget.bytes == 300, "pressure deletion accounting mismatch");
                    }
                }
            } finally { cleanup(root); }
        }
    }

    private static void changedBetweenPasses() throws Exception {
        for (String change : List.of("added", "removed", "replaced", "same-size-modified")) {
            Path root = Files.createTempDirectory("voxy-inventory-change-");
            Path descriptor = root.resolve("change.vxmeta"), garbage = root.resolve("keep.vxcat");
            Files.write(descriptor, new byte[10]); Files.write(garbage, new byte[10]);
            var time = java.nio.file.attribute.FileTime.fromMillis(1000);
            Files.setLastModifiedTime(descriptor, time);
            var budget = new RegionalDiskBudget(root, 1, path -> {
                switch (change) {
                    case "added" -> Files.write(root.resolve("added.vxcache"), new byte[10]);
                    case "removed" -> Files.delete(path);
                    case "replaced" -> {
                        // Keep old inode alive so replacement cannot immediately reuse its identity.
                        Files.move(path, root.resolve("old.unmanaged"));
                        Files.write(path, new byte[10]); Files.setLastModifiedTime(path, time);
                    }
                    case "same-size-modified" -> {
                        Files.write(path, new byte[]{1, 0, 0, 0, 0, 0, 0, 0, 0, 0});
                        Files.setLastModifiedTime(path, java.nio.file.attribute.FileTime.fromMillis(2000));
                    }
                    default -> throw new AssertionError(change);
                }
                return null;
            });
            try (var store = new RegionalMetadataStore(budget)) {
                until(() -> budget.snapshot().contains("cacheInventory=FAILED"));
                check(budget.bytes == -1 && Files.exists(garbage) && !budget.ensure(1, Set.of()),
                        "partially validated inventory deleted files or authorized growth: " + change);
            } finally { cleanup(root); }
        }
    }

    private static void foregroundDuringInventory() throws Exception {
        Path root = Files.createTempDirectory("voxy-inventory-foreground-");
        var fixture = fixture(1, 1, 255, 1);
        Path shard;
        try (var seed = new RegionalMetadataStore(root)) {
            persist(seed, fixture, true);
            shard = seed.namespace(WORLD, DIMENSION).resolve("r.0.0.vxcache");
        }
        Files.write(shard, new byte[]{1, 2, 3}, StandardOpenOption.APPEND); // valid prefix, torn suffix
        Map<Path, byte[]> before = contents(root);
        CountDownLatch entered = new CountDownLatch(1), resume = new CountDownLatch(1);
        AtomicInteger reads = new AtomicInteger();
        var budget = RegionalDiskBudget.open(root, path -> {
            reads.incrementAndGet(); entered.countDown(); waitFor(resume);
            return RegionalMetadataStore.referencedCatalog(path);
        });
        try (var store = new RegionalMetadataStore(budget)) {
            check(entered.await(5, TimeUnit.SECONDS), "inventory did not reach real descriptor I/O");
            check(budget.snapshot().contains("cacheDiskBytes=-1"), "unknown disk bytes reported as known");
            try (var driver = new Driver(root)) {
                driver.until(() -> driver.session.activeCount == 1);
                check(!budget.ready() && driver.session.cacheHits == 1 && driver.session.meshedSections == 1,
                        "foreground terrain waited for inventory");
                check(driver.session.metadata.budget == budget && reads.get() == 1, "session started another inventory");
                // An independently owned real worker handles downloaded bytes while persistence is disabled.
                var worker = driver.session.sectionWorkers[0];
                var demand = driver.session.demands.get(KEY);
                worker.assign(new ClientSession.Session.SectionWorkerTask(demand.ticket(driver.session.id, 0),
                        fixture.index(), 340, ClientSession.Session.WorkerSource.NETWORK,
                        fixture.payload(), MAPPINGS, driver.session.cache));
                until(() -> worker.resource.state() == WorkerResource.State.COMPLETED);
                var completion = worker.resource.claim();
                check(completion.value() instanceof ClientSession.Session.WorkerGeometry, "network terrain waited for inventory");
                ((ClientSession.Session.WorkerGeometry) completion.value()).geometry().free();
                worker.releaseCompletion(completion.lease());
                store.associate("new server", DIMENSION, WORLD, budget.stamp(), () -> true);
                store.saveCatalog(new RegionalProtocol.Hash32(9, 0, 0, 0), DIMENSION, fixture.catalog(), budget.stamp(), () -> true);
                store.saveRegion(WORLD, DIMENSION, 0, 0, null, budget.stamp(), () -> true);
                driver.session.cache.quarantine(fixture.index(), 340);
                driver.session.saveRegion(driver.session.demands.region(0), fixture.message());
                check(driver.session.metadataWrites.isEmpty(), "unknown accounting queued persistence");
                check(!budget.delete(shard), "unknown accounting deleted cache data");
                try (var wrongWorld = new RegionalCache(shard.getParent(), RegionalProtocol.Hash32.ZERO, budget)) {
                    check(wrongWorld.get(fixture.index(), 340) == null, "wrong world accepted");
                    wrongWorld.put(fixture.index(), 340, fixture.payload());
                }
                check(same(before, contents(root)), "unknown accounting mutated payload/metadata/torn suffix");
                check(store.region(WORLD, DIMENSION, 0, 0) != null
                        && store.readCatalog(WORLD, DIMENSION, fixture.catalog().fingerprint()) != null,
                        "foreground metadata gated by inventory");
                resume.countDown(); awaitInventory(budget);
                check(budget.bytes == diskBytes(root), "published inventory differs from disk");
                // Upgrade a previously read-only shard, safely repair its tail and persist normally.
                driver.session.cache.put(fixture.index(), 340, fixture.payload());
                check(Files.size(shard) == before.get(shard).length - 3, "ready shard did not repair its suffix");
                check(budget.bytes == diskBytes(root), "tail repair did not update accounting");
            }
        } finally { resume.countDown(); cleanup(root); }
    }

    private static void failedAndUnstableInventory() throws Exception {
        for (boolean unstable : new boolean[]{false, true}) {
            Path root = Files.createTempDirectory("voxy-inventory-failure-");
            var fixture = fixture(1, 0, 255, 1);
            try (var seed = new RegionalMetadataStore(root)) { persist(seed, fixture, true); }
            var budget = RegionalDiskBudget.open(root, path -> {
                if (!unstable) throw new IOException("injected unreadable descriptor");
                Files.write(path, new byte[]{0}, StandardOpenOption.APPEND);
                return null;
            });
            try (var store = new RegionalMetadataStore(budget)) {
                until(() -> budget.snapshot().contains("cacheInventory=FAILED"));
                check(budget.bytes == -1 && !budget.ensure(1, Set.of()), "partial inventory authorized writes");
                check(store.world(SERVER, DIMENSION).equals(WORLD), "failure disabled valid association reads");
                check(store.readCatalog(WORLD, DIMENSION, fixture.catalog().fingerprint()) != null,
                        "unknown references deleted valid catalog");
                try (var cache = new RegionalCache(store.namespace(WORLD, DIMENSION), WORLD, budget)) {
                    check(Arrays.equals(cache.get(fixture.index(), 340), fixture.payload()), "failure disabled valid payload reads");
                }
            } finally { cleanup(root); }
        }
    }

    private static void oversizedAndPinnedCleanup() throws Exception {
        Path root = Files.createTempDirectory("voxy-inventory-oversized-");
        Path payload = root.resolve("huge.vxcache"), pending = root.resolve("old.vxmeta.pending");
        Files.write(payload, new byte[8192]); Files.write(pending, new byte[100]);
        Files.write(root.resolve("torn.vxmeta"), new byte[12]);
        var budget = new RegionalDiskBudget(root, 1024);
        try (var pin = budget.pin(payload); var store = new RegionalMetadataStore(budget)) {
            awaitInventory(budget);
            check(Files.exists(payload) && !Files.exists(pending), "cleanup lost pinned file or retained temporary");
            check(budget.bytes == diskBytes(root) && budget.bytes > budget.limit, "oversized cache reported under limit");
            check(!budget.ensure(1, Set.of(payload)), "oversized pinned cache admitted growth");
        }
        // A fresh lifetime can now safely evict the inherited oversized payload.
        var next = new RegionalDiskBudget(root, 1024);
        try (var store = new RegionalMetadataStore(next)) {
            awaitInventory(next);
            check(next.bytes == diskBytes(root) && next.bytes <= next.limit, "cleanup failed to enforce allowance");
            var fixture = fixture(1, 0, 255, 1);
            var catalogPath = store.namespace(WORLD, DIMENSION).resolve("busy.vxcat");
            Files.createDirectories(catalogPath.getParent()); Files.write(catalogPath, new byte[10]);
            next.bytes += 10;
            try (var cache = new RegionalCache(catalogPath.getParent(), WORLD, next)) {
                synchronized (next) { next.register(catalogPath, cache, () -> false); }
                check(!next.delete(catalogPath) && Files.exists(catalogPath), "busy/open file lease ignored");
                synchronized (next) { next.unregister(catalogPath, cache); }
            }
            check(next.bytes == diskBytes(root), "failed deletion lost accounting");
            Path nonempty = Files.createDirectory(root.resolve("undeletable.vxcache"));
            Files.write(nonempty.resolve("child"), new byte[10]);
            next.bytes += 10;
            synchronized (next) {
                check(!next.delete(nonempty) && next.bytes == diskBytes(root), "IOException deletion lost accounting");
            }
        } finally { cleanup(root); }
    }

    private static void ownershipAndReopen() throws Exception {
        Path root = Files.createTempDirectory("voxy-inventory-ownership-");
        try (var lockFile = FileChannel.open(root.resolve(".voxy-cache.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var lock = lockFile.lock(); var store = new RegionalMetadataStore(root)) {
            until(() -> store.budget.snapshot().contains("cacheInventory=FAILED"));
            check(!store.budget.writable(), "another cache owner allowed persistence");
        }
        var fixture = fixture(1, 0, 255, 1);
        try (var seed = new RegionalMetadataStore(root)) { persist(seed, fixture, true); }
        CountDownLatch entered = new CountDownLatch(1), resume = new CountDownLatch(1);
        var budget = RegionalDiskBudget.open(root, path -> {
            entered.countDown();
            // Model an OS operation which does not immediately honor cancellation.
            for (;;) try { resume.await(); break; } catch (InterruptedException ignored) {}
            return RegionalMetadataStore.referencedCatalog(path);
        });
        var old = new RegionalMetadataStore(budget);
        check(entered.await(5, TimeUnit.SECONDS), "inventory never entered");
        old.close();
        try (var next = new RegionalMetadataStore(root)) {
            check(next.budget == budget, "overlapping lifetime opened a second inventory");
            check(next.world(SERVER, DIMENSION).equals(WORLD), "reopened reads waited for cancelled inventory");
            resume.countDown(); awaitInventory(next.budget);
            check(next.budget.bytes == diskBytes(root), "reopened inventory not authoritative");
        } finally { resume.countDown(); cleanup(root); }
    }

    private static void waitFor(CountDownLatch latch) throws IOException {
        try { latch.await(); } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new IOException("inventory interrupted", interrupted);
        }
    }
    private static void until(java.util.function.BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(1);
        check(condition.getAsBoolean(), "inventory test timed out");
    }
    private static Map<Path, byte[]> contents(Path root) throws IOException {
        Map<Path, byte[]> result = new HashMap<>();
        try (var files = Files.walk(root)) {
            for (Path path : files.filter(Files::isRegularFile).toList()) {
                if (!path.getFileName().toString().equals(".voxy-cache.lock")) result.put(path, Files.readAllBytes(path));
            }
        }
        return result;
    }
    private static boolean same(Map<Path, byte[]> a, Map<Path, byte[]> b) {
        return a.keySet().equals(b.keySet()) && a.entrySet().stream().allMatch(e -> Arrays.equals(e.getValue(), b.get(e.getKey())));
    }
    private static long diskBytes(Path root) throws IOException {
        try (var files = Files.walk(root)) { return files.filter(Files::isRegularFile).mapToLong(RegionalDiskBudget::size).sum(); }
    }
}
