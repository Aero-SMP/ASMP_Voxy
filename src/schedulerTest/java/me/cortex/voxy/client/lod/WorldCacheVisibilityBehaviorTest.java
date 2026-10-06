package me.cortex.voxy.client.lod;

import me.cortex.voxy.client.config.ServerDownloadSettings;
import me.cortex.voxy.client.core.rendering.SectionKey;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** Exercises the real planner and shared retention lifecycle without Minecraft or networking. */
public final class WorldCacheVisibilityBehaviorTest {
    private static final int FIRST = 11, SECOND = 12;
    private static final String FIRST_NAME = "visibility:overworld", SECOND_NAME = "visibility:other";
    private static final long A = SectionKey.pack(0, 0, 0, 0);
    private static final long B = SectionKey.pack(0, 32, 0, 0);
    private static final long C = SectionKey.pack(1, -1, 0, -1);
    private static final long D = SectionKey.pack(4, 7, 0, 2);

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--benchmark")) WorldCacheRankingBehaviorTest.benchmark();
        else run();
    }

    public static void run() throws Exception {
        try (TestPolicies policies = new TestPolicies(); Fixture fixture = new Fixture(policies)) {
            var planner = fixture.planner;
            var visibility = new VisibleSectionState();
            check(visibility.update(10, new long[]{A, B, B}), "initial report rejected");
            // A report may exist before HELLO's dimension manifest. It must not be acknowledged then.
            planner.view(FIRST, 0, 0, visibility);
            planner.manifest(manifest(1));
            assertEmpty(fixture, FIRST, "initial manifest");
            planner.view(FIRST, 0, 0, visibility);
            assertView(fixture, FIRST, visibility, 0, 0, "initial full sync");

            check(visibility.update(11, new long[]{B, C}), "delta report rejected");
            planner.view(FIRST, 0, 0, visibility);
            assertView(fixture, FIRST, visibility, 0, 0, "matching delta");
            check(!fixture.dimension(FIRST).visibleRoots.contains(A), "delta retained removed root");

            // The last report has empty deltas, but the planner missed two membership changes.
            visibility.update(12, new long[]{D});
            visibility.update(13, new long[]{A, B});
            visibility.update(14, new long[]{B, A, B});
            check(visibility.addedKeys().isEmpty() && visibility.removedKeys().isEmpty(),
                    "unchanged final report should have empty deltas");
            planner.view(FIRST, 0, 0, visibility);
            assertView(fixture, FIRST, visibility, 0, 0, "skipped deltas plus unchanged report resync");

            // A new helper can have the same numeric generation and different membership.
            var replacement = new VisibleSectionState();
            replacement.update(1, new long[]{A}); replacement.update(2, new long[]{B});
            replacement.update(3, new long[]{C}); replacement.update(4, new long[]{D});
            check(replacement.generation() == visibility.generation(), "scope fixture generations differ");
            planner.view(FIRST, 0, 0, replacement);
            assertView(fixture, FIRST, replacement, 0, 0, "new helper identity");

            planner.detach();
            assertEmpty(fixture, FIRST, "detach first dimension");
            assertEmpty(fixture, SECOND, "detach second dimension");
            planner.view(FIRST, 0, 0, replacement);
            assertView(fixture, FIRST, replacement, 0, 0, "detach recovery without new report");
            planner.reconnect();
            assertEmpty(fixture, FIRST, "reconnect");
            planner.view(FIRST, 0, 0, replacement);
            assertView(fixture, FIRST, replacement, 0, 0, "reconnect recovery without new report");

            planner.view(SECOND, 0, 0, replacement);
            assertEmpty(fixture, FIRST, "switch clears previous dimension");
            assertView(fixture, SECOND, replacement, 0, 0, "switch full sync");
            planner.view(FIRST, 0, 0, replacement);
            assertEmpty(fixture, SECOND, "switch back clears previous dimension");
            assertView(fixture, FIRST, replacement, 0, 0, "switch back full sync");

            replacement.update(5, new long[]{D, D});
            planner.view(FIRST, 128, -64, replacement);
            assertView(fixture, FIRST, replacement, 128, -64, "unchanged membership with camera movement");

            // Session and planner share Budget state. Even an empty planner dimension must
            // issue its lifecycle clear when Session has independently changed protection.
            var empty = new VisibleSectionState(); empty.update(100, new long[0]);
            planner.view(FIRST, 0, 0, empty);
            fixture.metadata.updateRetention(FIRST_NAME, 200, 201, Set.of(region(D)));
            assertRetention(fixture, FIRST_NAME, Set.of(region(D)), 200, 201, "external retention change");
            planner.view(SECOND, 0, 0, replacement);
            assertEmpty(fixture, FIRST, "empty prior dimension clears shared retention");
            planner.view(FIRST, 128, -64, replacement);

            var oldDimension = fixture.dimension(FIRST);
            planner.manifest(manifest(3));
            check(fixture.dimension(FIRST) != oldDimension, "world identity replacement retained old dimension");
            assertEmpty(fixture, FIRST, "manifest world identity replacement");
            planner.view(FIRST, 128, -64, replacement);
            assertView(fixture, FIRST, replacement, 128, -64, "manifest replacement resync");

            planner.view(999, 0, 0, replacement);
            assertEmpty(fixture, FIRST, "unknown dimension clears previous protection");
            planner.view(FIRST, 128, -64, replacement);
            assertView(fixture, FIRST, replacement, 128, -64, "unknown-dimension recovery");
            replacement.update(6, new long[0]);
            planner.view(FIRST, 128, -64, replacement);
            assertView(fixture, FIRST, replacement, 128, -64, "matching removal to empty");
        }
        jobsAndFrontier();
        normalizationAndRecovery();
        WorldCacheRankingBehaviorTest.run();
        System.out.println("actual world-cache visibility delta/resync/retention/job/frontier lifecycle tests passed");
    }

    private static void jobsAndFrontier() throws Exception {
        try (TestPolicies policies = new TestPolicies(); Fixture fixture = new Fixture(policies)) {
            var planner = fixture.planner;
            planner.manifest(manifest(1));
            var visibility = new VisibleSectionState();
            long removed = SectionKey.pack(0, 2, 0, 0);
            long near = SectionKey.pack(0, 1, 0, 0);
            long movedNear = SectionKey.pack(0, 12, 0, 0);
            visibility.update(1, new long[]{A, removed, D});
            planner.view(FIRST, 0, 0, visibility);
            var dimension = fixture.dimension(FIRST);
            long[] saved = new long[16]; java.util.Arrays.fill(saved, -1L);
            dimension.regions.put(region(A), saved.clone());
            dimension.regions.put(region(C), saved.clone());
            dimension.regions.put(region(D), saved.clone());
            dimension.inventoryReady = true;
            fixture.dimension(SECOND).regions.put(region(removed), saved.clone());
            fixture.dimension(SECOND).inventoryReady = true;
            dimension.offer(dimension.node(near));
            dimension.offer(dimension.node(movedNear));
            int beforeAdded = dimension.queued.size();

            var cancelled = fixture.seedJob(FIRST, removed, 100, 3, false);
            var processing = fixture.seedJob(FIRST, D, 101, 3, true);
            var visible = fixture.seedJob(FIRST, A, 102, 3, false);
            var other = fixture.seedJob(SECOND, removed, 103, 4, false);
            check(dimension.pendingJobs.get(region(A)) == 2, "shared job-region count not seeded");
            check(planner.waiting() == 3, "waiting-job fixture count differs");

            visibility.update(2, new long[]{A, C});
            planner.view(FIRST, 0, 0, visibility);
            check(planner.job(cancelled.ticket) == null && planner.pending(FIRST, removed) == null,
                    "invisible unprocessed purpose-3 job retained");
            check(planner.drops().contains(cancelled.scope()), "cancelled job drop missing before replacement");
            check(planner.drops().contains(cancelled.scope()), "reading drops consumed an unacknowledged cancellation");
            check(planner.job(processing.ticket) == processing && planner.pending(FIRST, D) == processing,
                    "processing job cancelled with removed visibility");
            check(planner.job(visible.ticket) == visible, "still-visible unprocessed job cancelled");
            check(planner.job(other.ticket) == other && planner.pending(SECOND, removed) == other,
                    "other-dimension job changed by visibility delta");
            check(dimension.pendingJobs.get(region(A)) == 1 && dimension.pendingJobs.get(region(D)) == 1,
                    "cancellation corrupted pending region counts");
            check(fixture.dimension(SECOND).pendingJobs.get(region(removed)) == 1,
                    "other-dimension region count changed");
            check(planner.waiting() == 2, "cancelled job remained in waiting count");
            check(dimension.queued.size() == beforeAdded + 9,
                    "added root must enqueue its eight children plus the cancelled-job reoffer");
            assertFrontier(dimension, "added visible root and cancelled job reoffer");

            // Independently compute live roots, distance and ties before polling actual nodes.
            planner.view(FIRST, 400, 0, visibility);
            assertFrontier(dimension, "anchor move with unchanged membership");
            visibility.update(3, new long[]{A});
            planner.view(FIRST, 400, 0, visibility);
            assertFrontier(dimension, "removed visible root after anchor move");
            check(planner.job(processing.ticket) == processing && planner.job(other.ticket) == other,
                    "later root removal cancelled protected jobs");
            check(planner.drops().contains(cancelled.scope()), "drop disappeared before writer acknowledgment");
            planner.dropped(List.of(cancelled.scope()));
            check(!planner.drops().contains(cancelled.scope()), "writer acknowledgment did not consume drop");
        }
    }

    private static void normalizationAndRecovery() throws Exception {
        try (TestPolicies policies = new TestPolicies(); Fixture fixture = new Fixture(policies)) {
            var planner = fixture.planner;
            planner.manifest(manifest(1));
            var visibility = new VisibleSectionState();
            long near = SectionKey.pack(0, 1, 0, 0), farther = SectionKey.pack(0, 2, 0, 0);
            visibility.update(1, new long[]{A, near}); planner.view(FIRST, 0, 0, visibility);
            var dimension = fixture.dimension(FIRST);
            long[] saved = new long[16]; java.util.Arrays.fill(saved, -1L);
            dimension.regions.put(region(A), saved.clone()); dimension.inventoryReady = true;
            dimension.offer(dimension.node(A)); dimension.offer(dimension.node(near)); dimension.offer(dimension.node(farther));
            var roots = WorldCacheRankingBehaviorTest.instrument(dimension);
            var heap = WorldCacheRankingBehaviorTest.track(dimension, roots);
            visibility.update(2, new long[]{A, near, farther});
            planner.view(FIRST, 17, -17, visibility);
            check(heap.clears == 1, "combined anchor/membership rebuilt more than once");
            check(roots.lookups - heap.lookupsAtClear == dimension.queued.size(),
                    "normalization did not refresh each directly-visible surviving node exactly once");
            long lookups = roots.lookups;
            assertFrontier(dimension, "combined normalization counter proof");
            check(roots.lookups == lookups, "heap comparisons recalculated live visibility");
            heap = WorldCacheRankingBehaviorTest.track(dimension, roots);
            planner.view(FIRST, 17, -17, visibility);
            check(heap.clears == 0, "unchanged inputs rebuilt the heap");

            // A failure after a real roots mutation must precede generation acknowledgement.
            long next = SectionKey.pack(0, 3, 0, 0);
            long acknowledged = (long) value(planner, "viewedGeneration");
            roots.failAfterAdd = true;
            visibility.update(3, new long[]{A, near, farther, next});
            WorldCacheRankingBehaviorTest.expectFailure(() -> planner.view(FIRST, 17, -17, visibility));
            check(dimension.orderingDirty && (long) value(planner, "viewedGeneration") == acknowledged,
                    "partial roots mutation was acknowledged or left a clean stale heap");
            planner.foregroundReleased(FIRST, next);
            check(dimension.queued.size() == 4 && dimension.frontier.size() == 3,
                    "foreground-release dirty offer touched the stale heap");
            planner.next(500, 1); // Actual consumer must normalize before its first poll.
            check(!dimension.orderingDirty, "next consumed an unnormalized heap after failed view");
            assertFrontier(dimension, "consumer guard after partial mutation");
            planner.view(FIRST, 17, -17, visibility);
            assertView(fixture, FIRST, visibility, 17, -17, "partial mutation recovery");

            // Clearing already-empty roots still cancels purpose-3 work. Its reoffer
            // must enter the clean heap even though no ordering input changed.
            var empty = new VisibleSectionState(); empty.update(1, new long[0]);
            planner.view(FIRST, 17, -17, empty);
            dimension.clearFrontier();
            var cancellation = fixture.seedJob(FIRST, A, 501, 3, false);
            heap = WorldCacheRankingBehaviorTest.track(dimension, roots);
            planner.view(SECOND, 0, 0, empty);
            check(planner.job(cancellation.ticket) == null && dimension.queued.size() == 1,
                    "already-empty clear did not cancel/reoffer offscreen purpose-3 job");
            check(heap.clears == 0, "already-empty lifecycle clear rebuilt unchanged ordering");
            assertFrontier(dimension, "already-empty lifecycle reoffer");

            planner.view(FIRST, 17, -17, empty);
            dimension.clearFrontier();
            var regionOnly = fixture.seedJob(FIRST, near, 502, 3, false);
            dimension.visibleRegions.add(region(D));
            var replacementEmpty = new VisibleSectionState(); replacementEmpty.update(1, new long[0]);
            heap = WorldCacheRankingBehaviorTest.track(dimension, roots);
            planner.view(FIRST, 17, -17, replacementEmpty);
            check(planner.job(regionOnly.ticket) == null && heap.clears == 0,
                    "region-only resync changed ordering or retained offscreen job");
            assertFrontier(dimension, "root-unchanged region-only reoffer");

            // Reoffers from job failure paths obey the dirty-map-only rule.
            dimension.clearFrontier(); dimension.anchor(18, -17);
            var unsent = fixture.seedJob(FIRST, A, 503, 4, false); planner.unsent(unsent.ticket);
            var notReady = fixture.seedJob(FIRST, near, 504, 4, false);
            dimension.regionRevisions.put(region(near), notReady.availabilityRevision + 1); planner.notReady(notReady);
            var capacity = fixture.seedJob(FIRST, farther, 505, 4, false);
            field(planner.getClass(), "diskAdmission").setLong(planner, -1);
            planner.failed(capacity, new RegionalDiskBudget.Capacity(RegionalDiskBudget.Admission.QUOTA));
            dimension.admissionBlocked.add(region(A));
            dimension.spaceBlocked.put(region(A), new java.util.HashSet<>(Set.of(next)));
            field(planner.getClass(), "diskRecovery").setLong(planner, -1);
            field(planner.getClass(), "diskStamp").setLong(planner, -1);
            planner.view(FIRST, 18, -17, replacementEmpty);
            check(dimension.admissionBlocked.isEmpty() && dimension.spaceBlocked.isEmpty(),
                    "admission/disk recovery did not clear blocked sets");
            assertFrontier(dimension, "unsent/not-ready/capacity and admission/disk/eviction recovery");

            // Real policy reseeding restores an absent candidate without normalizing
            // a dirty heap. Remove one map-only node to make the reoffer observable.
            long coarse = SectionKey.pack(1, 0, 0, 0);
            replacementEmpty.update(2, new long[]{coarse});
            planner.view(FIRST, 18, -17, replacementEmpty);
            dimension.clearFrontier(); dimension.anchor(19, -17);
            Object missing = dimension.queued.keySet().iterator().next();
            dimension.queued.remove(missing);
            int beforePolicy = dimension.queued.size();
            planner.policyChanged();
            check(dimension.orderingDirty && dimension.frontier.isEmpty()
                            && dimension.queued.size() == beforePolicy + 1 && dimension.queued.containsKey(missing),
                    "policy reseeding lost a candidate or normalized/inserted into a dirty heap");
            dimension.normalizeOrdering();
            assertFrontier(dimension, "policy reseeding while dirty");

            // An older job's disk-full completion can arrive after recovery. Exercise
            // that real branch without changing Budget or filling the physical disk.
            dimension.clearFrontier(); dimension.anchor(20, -17);
            long recoveredKey = SectionKey.pack(0, 4, 0, 0);
            var recoveredJob = fixture.seedJob(FIRST, recoveredKey, 506, 4, false,
                    fixture.metadata.budget.recoveryGeneration(policies.settings.serverId()) - 1);
            int beforeFailure = dimension.queued.size();
            planner.failed(recoveredJob, new RegionalDiskBudget.Capacity(RegionalDiskBudget.Admission.DISK_FULL));
            check(planner.job(recoveredJob.ticket) == null && dimension.spaceBlocked.isEmpty()
                            && dimension.orderingDirty && dimension.frontier.isEmpty()
                            && dimension.queued.size() == beforeFailure + 1,
                    "recovered disk-full completion parked work or touched a dirty heap");
            dimension.normalizeOrdering();
            assertFrontier(dimension, "disk-full completion overtaken by recovery");

            // A geometry reset keeps original seed timing; snapshots clear without a seed.
            var changed = new RegionalProtocol.DimensionInfo(FIRST, FIRST_NAME, dimension.info.worldIdentity(),
                    -32, 64, true, 0, 0, 2048, 0, RegionalProtocol.Hash32.ZERO);
            planner.manifest(new RegionalProtocol.Manifest(List.of(changed, fixture.dimension(SECOND).info), List.of()));
            assertFrontier(dimension, "manifest geometry reset");
            dimension.anchor(19, -17);
            planner.inventory(new RegionalProtocol.RegionInventory(FIRST, 10,
                    RegionalProtocol.InventoryState.SNAPSHOT_BEGIN, 0, 0, new long[16]));
            check(!dimension.orderingDirty && dimension.frontier.isEmpty() && dimension.queued.isEmpty(),
                    "snapshot begin seeded or retained dirty frontier state");
            planner.inventory(new RegionalProtocol.RegionInventory(FIRST, 11,
                    RegionalProtocol.InventoryState.SAVED_PUBLISHED, 0, 0, saved));
            check(dimension.queued.isEmpty(), "saved snapshot entry seeded before completion");
            planner.inventory(new RegionalProtocol.RegionInventory(FIRST, 12,
                    RegionalProtocol.InventoryState.SNAPSHOT_COMPLETE, 0, 0, new long[16]));
            check(dimension.inventoryReady && !dimension.queued.isEmpty(), "snapshot complete did not seed saved inventory");
            assertFrontier(dimension, "snapshot complete original seed");
        }
    }

    private static void assertFrontier(WorldCacheDownloads.Dimension dimension, String phase) throws Exception {
        WorldCacheRankingBehaviorTest.assertFrontier(dimension, phase);
    }

    private static RegionalProtocol.Manifest manifest(long firstIdentity) {
        return new RegionalProtocol.Manifest(List.of(info(FIRST, FIRST_NAME, firstIdentity),
                info(SECOND, SECOND_NAME, 2)), List.of());
    }

    private static RegionalProtocol.DimensionInfo info(int id, String name, long identity) {
        return new RegionalProtocol.DimensionInfo(id, name, new RegionalProtocol.Hash32(identity, 0, 0, 0),
                0, 128, false, 0, 0, 1024, 0, RegionalProtocol.Hash32.ZERO);
    }

    private static long region(long key) {
        int divisor = 1 << (SectionKey.MAX_LOD_LAYER - SectionKey.level(key));
        int x = Math.floorDiv(SectionKey.x(key), divisor), z = Math.floorDiv(SectionKey.z(key), divisor);
        return Integer.toUnsignedLong(x) | Integer.toUnsignedLong(z) << 32;
    }

    private static void assertView(Fixture fixture, int id, VisibleSectionState expected,
                                   int x, int z, String phase) throws Exception {
        var dimension = fixture.dimension(id);
        check(dimension.visibleRoots.equals(expected.keys()), phase + ": root membership differs");
        check(dimension.visibleRegions.equals(expected.regions()), phase + ": region membership differs");
        check(dimension.x == x && dimension.z == z, phase + ": planner anchor differs");
        assertRetention(fixture, dimension.info.name(), expected.regions(), x, z, phase);
        assertFrontier(dimension, phase);
    }

    private static void assertEmpty(Fixture fixture, int id, String phase) throws Exception {
        var dimension = fixture.dimension(id);
        check(dimension.visibleRoots.isEmpty() && dimension.visibleRegions.isEmpty(), phase + ": membership retained");
        assertRetention(fixture, dimension.info.name(), Set.of(), dimension.x, dimension.z, phase);
        assertFrontier(dimension, phase);
    }

    private static void assertRetention(Fixture fixture, String name, Set<Long> expected,
                                        int x, int z, String phase) throws Exception {
        synchronized (fixture.metadata.budget) {
            var account = (RegionalDiskBudget.Account) value(fixture.metadata, "account");
            Object anchor = account.anchors.get(name);
            check(anchor != null, phase + ": retention anchor absent");
            check(value(anchor, "visible").equals(expected), phase + ": budget protection differs");
            check((long) value(anchor, "x") == x && (long) value(anchor, "z") == z,
                    phase + ": budget anchor differs");
        }
    }

    private static final class Fixture implements AutoCloseable {
        final TestPolicies policies;
        final Path scratch;
        final RegionalMetadataStore metadata;
        final WorldCacheDownloads planner;

        Fixture(TestPolicies policies) throws Exception {
            this.policies = policies;
            Path parent = Path.of("build", "visibility-behavior-scratch").toAbsolutePath();
            Files.createDirectories(parent);
            this.scratch = Files.createTempDirectory(parent, "planner-");
            this.metadata = new RegionalMetadataStore(this.scratch.resolve("cache"));
            this.metadata.bindServer(policies.settings, policies.settings.rawAddress());
            this.metadata.budget.awaitReady(() -> true);
            this.planner = new WorldCacheDownloads(policies.settings, this.metadata, () -> {}, (id, key) -> false);
        }

        @SuppressWarnings("unchecked")
        Map<Integer, WorldCacheDownloads.Dimension> dimensions() throws Exception {
            return (Map<Integer, WorldCacheDownloads.Dimension>) value(this.planner, "dimensions");
        }

        WorldCacheDownloads.Dimension dimension(int id) throws Exception {
            var dimension = dimensions().get(id);
            check(dimension != null, "missing fixture dimension " + id);
            return dimension;
        }

        WorldCacheDownloads.Job seedJob(int id, long key, long ticket, int purpose, boolean processing) throws Exception {
            return seedJob(id, key, ticket, purpose, processing,
                    this.metadata.budget.recoveryGeneration(this.policies.settings.serverId()));
        }

        @SuppressWarnings("unchecked")
        WorldCacheDownloads.Job seedJob(int id, long key, long ticket, int purpose, boolean processing, long diskRecovery) throws Exception {
            var dimension = dimension(id);
            var job = new WorldCacheDownloads.Job(dimension, key, ticket, 1, purpose, diskRecovery,
                    this.metadata.admissionGeneration());
            job.processing = processing;
            var jobs = (Map<Long, WorldCacheDownloads.Job>) value(this.planner, "jobs");
            var requested = (Map<RegionalProtocol.ScopedKey, WorldCacheDownloads.Job>) value(this.planner, "requested");
            check(!jobs.containsKey(ticket) && !requested.containsKey(job.scope()), "duplicate seeded job");
            long region = region(key);
            dimension.cache.retain(region); dimension.retainedRegions.add(region);
            dimension.pendingJobs.addTo(region, 1);
            jobs.put(ticket, job); requested.put(job.scope(), job);
            return job;
        }

        @Override public void close() throws Exception {
            var tasks = new ArrayList<FutureTask<?>>();
            for (var dimension : dimensions().values())
                if (dimension.catalogueTask != null) tasks.add(dimension.catalogueTask);
            // close() saves settings in production; the fixture must never access that path.
            this.policies.suppressSave();
            this.planner.close(); this.metadata.close();
            for (var task : tasks) {
                try { task.get(5, TimeUnit.SECONDS); }
                catch (CancellationException cancelled) { /* Retired metadata work is expected. */ }
            }
            var disposed = (CountDownLatch) value(this.metadata.budget, "disposed");
            check(disposed.await(5, TimeUnit.SECONDS), "fixture cache ownership did not close");
            try (var paths = Files.walk(this.scratch)) {
                for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    /** In-memory policies isolate actual planner/Budget code from the normal config directory. */
    private static final class TestPolicies implements AutoCloseable {
        final Object lock;
        final Field store, loaded, failure;
        final Object previousStore, previousLoaded, previousFailure;
        final ServerDownloadSettings settings;

        @SuppressWarnings("unchecked")
        TestPolicies() throws Exception {
            this.lock = field(ServerDownloadSettings.class, "LOCK").get(null);
            this.store = field(ServerDownloadSettings.class, "store");
            this.loaded = field(ServerDownloadSettings.class, "loaded");
            this.failure = field(ServerDownloadSettings.class, "failureReason");
            synchronized (this.lock) {
                this.previousStore = this.store.get(null); this.previousLoaded = this.loaded.get(null);
                this.previousFailure = this.failure.get(null);
                Object isolatedStore = construct(Class.forName(ServerDownloadSettings.class.getName() + "$Store"));
                Object policy = construct(Class.forName(ServerDownloadSettings.class.getName() + "$Policy"));
                field(policy.getClass(), "storageBytes").setLong(policy, Long.MAX_VALUE);
                field(policy.getClass(), "storageSelected").setBoolean(policy, true);
                String key = "visibility-fixture:25565";
                ((Map<String, Object>) value(isolatedStore, "servers")).put(key, policy);
                this.store.set(null, isolatedStore); this.loaded.setBoolean(null, true); this.failure.set(null, "");
                Constructor<ServerDownloadSettings> constructor = ServerDownloadSettings.class
                        .getDeclaredConstructor(String.class, String.class);
                constructor.setAccessible(true); this.settings = constructor.newInstance(key, key);
            }
        }

        void suppressSave() throws Exception { synchronized (this.lock) { this.store.set(null, null); } }

        @Override public void close() throws Exception {
            synchronized (this.lock) {
                this.store.set(null, this.previousStore); this.loaded.set(null, this.previousLoaded);
                this.failure.set(null, this.previousFailure);
            }
        }
    }

    private static Object construct(Class<?> type) throws Exception {
        var constructor = type.getDeclaredConstructor(); constructor.setAccessible(true);
        return constructor.newInstance();
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name); field.setAccessible(true); return field;
    }

    private static Object value(Object target, String name) throws Exception {
        return field(target.getClass(), name).get(target);
    }

    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
