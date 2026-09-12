package me.cortex.voxy.client.lod;

import java.nio.file.*;
import java.util.concurrent.*;
import static me.cortex.voxy.client.lod.CacheStartupBehaviorTest.*;

/** Real inventory and owner/worker scheduling; no server metadata redelivery. */
final class DeferredMetadataBehaviorTest {
    static void run() throws Exception {
        readiness(false);
        readiness(true);
        acknowledgementIdentity();
        catalogAndWorldSuccessors();
        localReadFairness();
        unavailableWriteDoesNotRetry();
        System.out.println("deferred metadata: once-delivered metadata, terminal failure, coalescing, stale acknowledgement and reopen passed");
    }

    private static void readiness(boolean failInventory) throws Exception {
        Path root = Files.createTempDirectory("voxy-deferred-metadata-");
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var f = fixture(1, 1, 240, 1);
        try {
            Files.write(root.resolve("gate.vxmeta"), new byte[1]);
            var budget = RegionalDiskBudget.open(root, path -> {
                entered.countDown();
                try { if (!release.await(10, TimeUnit.SECONDS)) throw new java.io.IOException("inventory gate timeout"); }
                catch (InterruptedException e) { throw new java.io.IOException(e); }
                if (failInventory) throw new java.io.IOException("injected inventory failure");
                return null;
            });
            try (var owner = new RegionalMetadataStore(budget); var driver = new Driver(root)) {
                check(entered.await(5, TimeUnit.SECONDS), "inventory not held");
                var s = driver.session;
                driver.until(() -> s.metadata != null);
                s.acceptHello(new RegionalProtocol.ServerHello(1, WORLD, 1, f.catalog().fingerprint()));
                driver.until(() -> s.cache != null);
                driver.until(() -> s.metadataWorker.idle());
                check(s.acceptCatalog(f.catalog()), "catalog not accepted");
                driver.until(() -> s.currentCatalog != null);
                var region = s.demands.region(0);
                for (int i = 0; i < 1000; i++) s.saveMetadata(region, i % 2 == 0 ? null : f.index(), f.catalog().fingerprint());
                check(s.metadataWrites.size() == 1 && s.catalogWrites.size() == 1
                        && s.pendingCatalogBytes() == f.catalog().canonical().length, "metadata history did not coalesce");
                for (int i = 0; i < 100; i++) { driver.step(); s.awaitWake(1); }
                check(!budget.ready() && s.currentCatalog != null && s.metadataWorker.idle(),
                        "optional persistence blocked catalog use or held worker during inventory");
                release.countDown();
                if (failInventory) {
                    driver.until(() -> budget.persistenceUnavailable() == RegionalMetadataStore.Persistence.UNAVAILABLE);
                    driver.until(() -> s.metadataWrites.isEmpty() && s.catalogWrites.isEmpty() && !s.associationPending);
                    long failures = s.persistenceOutcomes[RegionalMetadataStore.Persistence.UNAVAILABLE.ordinal()];
                    check(failures == 3 && s.pendingCatalogBytes() == 0, "terminal refusal was not acknowledged");
                    for (int i = 0; i < 100; i++) driver.step();
                    check(s.persistenceOutcomes[RegionalMetadataStore.Persistence.UNAVAILABLE.ordinal()] == failures
                            && s.failure == null && s.connectionEpoch == 0, "terminal failure spun or broke streaming");
                    return;
                }
                awaitInventory(budget);
                driver.until(() -> {
                    try { return s.metadata.readCatalog(WORLD, DIMENSION, f.catalog().fingerprint()) != null; }
                    catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
                });
                check(WORLD.equals(s.metadata.world(SERVER, DIMENSION)), "association intent lost");
                driver.until(() -> s.metadataWrites.isEmpty() && s.metadataWorker.idle());
                check(s.cache.put(LocalSection.from(f.index(), 340, f.catalog().fingerprint()), f.payload(), () -> true),
                        "subsequent section could not commit against deferred catalog");
            }
            try (var driver = new Driver(root)) {
                driver.until(() -> driver.session.activeCount == 1);
                check(driver.session.receivedBytes == 0 && !driver.session.helloAccepted,
                        "candidate-written root needed network metadata after reopen");
            }
        } finally { release.countDown(); cleanup(root); }
    }

    private static void acknowledgementIdentity() throws Exception {
        Path root = Files.createTempDirectory("voxy-metadata-ack-");
        var f = fixture(1, 1, 240, 1);
        try (var legacy = new RegionalMetadataStore(root)) {
            persist(legacy, f, true);
            try (var driver = new Driver(root)) {
                var s = driver.session;
                driver.until(() -> s.activeCount == 1 && s.metadataWorker.idle());
                var region = s.demands.region(0);
                s.saveMetadata(region, null, RegionalProtocol.Hash32.ZERO);
                Object old = s.metadataWrites.get(0L);
                var dispatch = ClientSession.Session.class.getDeclaredMethod("persistMetadata");
                dispatch.setAccessible(true);
                check((boolean) dispatch.invoke(s), "old intent not dispatched");
                long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (s.metadataWorker.resource.state() != WorkerResource.State.COMPLETED && System.nanoTime() < end) Thread.sleep(1);
                check(s.metadataWorker.resource.state() == WorkerResource.State.COMPLETED, "write did not complete");
                region.metadataRevision++;
                s.saveMetadata(region, f.index(), f.catalog().fingerprint());
                Object next = s.metadataWrites.get(0L);
                check(next != old, "replacement identity missing");
                s.drainWorkers();
                check(s.metadataWrites.get(0L) == next, "old acknowledgement erased newer region intent");
                driver.until(() -> s.metadataWrites.isEmpty() && s.metadataWorker.idle());
                check(s.regionPersisted == 2, "coalesced region outcomes inaccurate");
            }
        } finally { cleanup(root); }
    }

    private static void unavailableWriteDoesNotRetry() throws Exception {
        Path root = Files.createTempDirectory("voxy-metadata-unavailable-");
        var a = fixture(1, 1, 240, 1);
        var b = fixture(2, 1, 224, 2);
        try (var legacy = new RegionalMetadataStore(root)) {
            persist(legacy, a, true);
            try (var driver = new Driver(root)) {
                var s = driver.session;
                driver.until(() -> s.activeCount == 1 && s.metadataWorker.idle());
                Path target = s.metadata.catalogPath(WORLD, DIMENSION, b.catalog().fingerprint());
                Files.createDirectories(target.resolveSibling(target.getFileName() + ".pending"));
                s.acceptHello(new RegionalProtocol.ServerHello(1, WORLD, 1, b.catalog().fingerprint()));
                driver.until(() -> s.metadataWorker.idle());
                check(s.acceptCatalog(b.catalog()), "new catalog not accepted");
                driver.until(() -> s.currentCatalog != null && s.currentCatalog.fingerprint().equals(b.catalog().fingerprint()));
                long failures = s.persistenceOutcomes[RegionalMetadataStore.Persistence.UNAVAILABLE.ordinal()];
                check(failures == 1 && s.catalogWrites.isEmpty(), "failed catalog write became a retry or success");
                for (int i = 0; i < 100; i++) driver.step();
                check(s.persistenceOutcomes[RegionalMetadataStore.Persistence.UNAVAILABLE.ordinal()] == failures
                        && s.activeCount == 1 && s.failure == null, "optional write failure damaged rendering or spun");
            }
        } finally { cleanup(root); }
    }

    private static void catalogAndWorldSuccessors() throws Exception {
        Path root = Files.createTempDirectory("voxy-metadata-successors-");
        var a = fixture(1, 1, 240, 1);
        var b = fixture(2, 1, 224, 2);
        var retain = ClientSession.Session.class.getDeclaredMethod("retainCatalog", long.class,
                RegionalProtocol.Hash32.class, RegionalProtocol.CatalogMessage.class);
        var prune = ClientSession.Session.class.getDeclaredMethod("pruneCatalogWrites");
        var dispatch = ClientSession.Session.class.getDeclaredMethod("persistMetadata");
        retain.setAccessible(true); prune.setAccessible(true); dispatch.setAccessible(true);
        try (var legacy = new RegionalMetadataStore(root)) {
            persist(legacy, a, true);
            try (var driver = new Driver(root)) {
                var s = driver.session;
                driver.until(() -> s.activeCount == 1 && s.metadataWorker.idle() && s.catalogWrites.isEmpty());
                s.requiredCatalogFingerprint = b.catalog().fingerprint();
                retain.invoke(s, s.viewRevision, WORLD, a.catalog());
                retain.invoke(s, s.viewRevision, WORLD, b.catalog());
                prune.invoke(s);
                check(s.catalogWrites.size() == 2, "new requirement discarded catalog needed by active terrain");
                var region = s.demands.region(0);
                region.localCatalogs.clear(); region.catalog = null;
                prune.invoke(s);
                check(s.catalogWrites.size() == 2, "active content was not counted as an old-catalog consumer");
                s.retireDemand(KEY);
                prune.invoke(s);
                check(s.catalogWrites.size() == 1 && s.catalogWrites.containsKey(b.catalog().fingerprint()),
                        "unused catalog history retained after its last consumer retired");
                check((boolean) dispatch.invoke(s), "catalog intent not dispatched");
                awaitCompletedMetadata(s);
                Object old = s.catalogWrites.get(b.catalog().fingerprint());
                retain.invoke(s, s.viewRevision, WORLD, b.catalog());
                Object successor = s.catalogWrites.get(b.catalog().fingerprint());
                s.drainWorkers();
                check(successor != old && s.catalogWrites.get(b.catalog().fingerprint()) == successor,
                        "old catalog acknowledgement erased same-fingerprint successor");
                driver.until(() -> s.catalogWrites.isEmpty() && s.metadataWorker.idle());

                s.acceptHello(new RegionalProtocol.ServerHello(1, WORLD, 1, b.catalog().fingerprint()));
                check((boolean) dispatch.invoke(s), "association intent not dispatched");
                awaitCompletedMetadata(s);
                var newWorld = new RegionalProtocol.Hash32(9, 8, 7, 6);
                s.changeWorld(newWorld);
                s.acceptHello(new RegionalProtocol.ServerHello(1, newWorld, 1, b.catalog().fingerprint()));
                Object association = s.associationIntent;
                s.drainWorkers();
                check(s.associationPending && s.associationIntent == association,
                        "old-world acknowledgement cleared new-world association");
                driver.until(() -> !s.associationPending && s.metadataWorker.idle());
                check(newWorld.equals(s.metadata.world(SERVER, DIMENSION)), "new-world association never persisted");
                check(s.pendingCatalogBytes() == 0, "successor test retained canonical bytes");
            }
        } finally { cleanup(root); }
    }

    private static void awaitCompletedMetadata(ClientSession.Session session) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (session.metadataWorker.resource.state() != WorkerResource.State.COMPLETED
                && System.nanoTime() < end) Thread.sleep(1);
        check(session.metadataWorker.resource.state() == WorkerResource.State.COMPLETED, "metadata write did not complete");
    }

    private static void localReadFairness() throws Exception {
        Path root = Files.createTempDirectory("voxy-metadata-fairness-");
        var f = fixture(1, 1, 240, 1);
        try (var legacy = new RegionalMetadataStore(root)) {
            persist(legacy, f, true);
            try (var driver = new Driver(root)) {
                var s = driver.session;
                driver.until(() -> s.activeCount == 1 && s.metadataWorker.idle() && s.catalogWrites.isEmpty());
                long other = me.cortex.voxy.client.core.rendering.SectionKey.pack(4, 1, 0, 0);
                s.addDemand(other);
                var local = s.demands.region(s.demands.get(other).regionKey);
                var updated = s.demands.region(0);
                long start = System.nanoTime();
                int updates = 0;
                while ((!local.localLoaded || s.regionPersisted == 0)
                        && System.nanoTime() - start < TimeUnit.SECONDS.toNanos(5)) {
                    s.saveMetadata(updated, f.index(), f.catalog().fingerprint());
                    check(s.metadataWrites.size() == 1, "repeated updates accumulated history");
                    updates++;
                    driver.step(); s.awaitWake(1);
                }
                check(local.localLoaded && s.regionPersisted > 0,
                        "continuous metadata updates starved local reads or persistence");
                driver.until(() -> s.metadataWrites.isEmpty() && s.metadataWorker.idle());
                System.out.println("metadata fairness: updates=" + updates + " elapsedNs="
                        + (System.nanoTime() - start) + " persisted=" + s.regionPersisted);
            }
        } finally { cleanup(root); }
    }
}
