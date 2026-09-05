package me.cortex.voxy.client.lod;

import me.cortex.voxy.client.core.VoxyRenderSystem.*;
import me.cortex.voxy.client.core.rendering.SectionKey;
import me.cortex.voxy.client.core.rendering.building.SectionMesher;
import me.cortex.voxy.client.core.rendering.hierarchical.AsyncNodeManager.PublicationProgress;
import me.cortex.voxy.client.core.rendering.hierarchical.SectionPublicationState;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Real metadata/cache codecs, owned workers, mesher and scheduler. Only GPU/model/network
 * boundaries are controlled; no fake hello is used to bootstrap local terrain. */
final class CacheStartupBehaviorTest {
    static final String DIMENSION = "minecraft:overworld", SERVER = "example.test:25565";
    static final RegionalProtocol.Hash32 WORLD = new RegionalProtocol.Hash32(1, 2, 3, 4);
    static final long KEY = SectionKey.pack(4, 0, 0, 0);
    static final RegionalSectionCodec.Mappings MAPPINGS = new RegionalSectionCodec.Mappings(new int[]{15}, new int[]{0});

    static void run() throws Exception {
        shardReconstruction();
        grownShardOffsets();
        overlappingShardOwners();
        trimmedShardsReopen();
        CacheInventoryBehaviorTest.run();
        RuntimeCachePressureBehaviorTest.run();
        localActivationAndValidation();
        cachedRefinementWhileHeld();
        missesDoNotSpin();
        corruptPayloadAndLateMapping();
        metadataIntegrityAndBudget();
        connectorCancellation();
        lateConnectionSuccessIsClosed();
        wireAbsence();
        System.out.println("cache-startup production worker/mesher, validation, disk and connector tests passed");
    }

    record Fixture(RegionalProtocol.CatalogMessage catalog, RegionalProtocol.RegionMessage message,
                   RegionalProtocol.RegionIndex index, byte[] payload) {}

    private static void shardReconstruction() throws Exception {
        Path root = Files.createTempDirectory("voxy-shard-open-");
        Path path = root.resolve("r.0.0.vxcache");
        byte[] header = buffer(64).put(new byte[]{'V','X','Y','S','E','C',0,0})
                .putLong(1).putLong(2).putLong(3).putLong(4).putInt(0).putInt(0).putLong(0).array();
        try {
            Files.write(path, new byte[0]);
            check(openShard(path, false) == null, "empty file accepted");
            Files.write(path, header);
            try (var shard = openShard(path, false)) {
                check(shard != null && shardValue(shard, 1, 2) == null, "header-only shard rejected");
            }
            // Same fingerprint with different lengths, last-record wins, and a reinsert after a tombstone.
            byte[] records = buffer(64 + 7 * 20 + 2 + 3 + 2 + 2 + 4)
                    .put(header).put(record(1, 2, new byte[]{1, 1}))
                    .put(record(1, 3, new byte[]{3, 3, 3}))
                    .put(record(1, 2, new byte[]{2, 2})).put(record(1, -2, new byte[0]))
                    .put(record(1, 2, new byte[]{4, 4})).put(record(2, 4, new byte[]{5, 5, 5, 5}))
                    .put(record(2, -4, new byte[0])).array();
            Files.write(path, Arrays.copyOf(records, records.length - 20));
            try (var shard = openShard(path, false)) {
                check(Arrays.equals(shardValue(shard, 2, 4), new byte[]{5, 5, 5, 5}), "exact-end payload lost");
            }
            List<byte[]> suffixes = new ArrayList<>();
            suffixes.add(new byte[0]); suffixes.add(new byte[]{7, 8, 9});
            suffixes.add(record(3, 4, new byte[]{7})); // incomplete payload
            for (int invalid : new int[]{0, Integer.MIN_VALUE, RegionalProtocol.MAX_SECTION_BYTES + 1,
                    -RegionalProtocol.MAX_SECTION_BYTES - 1}) suffixes.add(record(3, invalid, new byte[0]));
            for (byte[] suffix : suffixes) {
                Files.write(path, buffer(records.length + suffix.length).put(records).put(suffix).array());
                var modified = java.nio.file.attribute.FileTime.fromMillis(123456000);
                Files.setLastModifiedTime(path, modified);
                byte[] before = Files.readAllBytes(path);
                for (int reopen = 0; reopen < 3; reopen++) try (var shard = openShard(path, false)) {
                    check(Arrays.equals(shardValue(shard, 1, 2), new byte[]{4, 4}), "reinsert/duplicate lost");
                    check(Arrays.equals(shardValue(shard, 1, 3), new byte[]{3, 3, 3}), "length keys conflated");
                    check(shardValue(shard, 2, 4) == null, "exact-end tombstone lost");
                }
                check(Arrays.equals(before, Files.readAllBytes(path)) && Files.getLastModifiedTime(path).equals(modified),
                        "read-only scan repaired or touched file");
                try (var shard = openShard(path, true)) {
                    check(Files.size(path) == records.length && shardValue(shard, 1, 2) != null, "suffix repair changed prefix");
                    var field = shard.getClass().getDeclaredField("file"); field.setAccessible(true);
                    var file = (java.io.RandomAccessFile) field.get(shard);
                    shard.close(); check(!file.getFD().valid(), "closed shard leaked file handle");
                }
                try (var shard = openShard(path, true)) {
                    check(Files.size(path) == records.length, "repeated repair changed exact end");
                }
            }
            for (int offset : new int[]{0, 8, 40, 44, 48}) {
                byte[] invalid = header.clone(); invalid[offset] ^= 1; Files.write(path, invalid);
                check(openShard(path, false) == null, "malformed magic/world/coordinates/reserved field accepted");
            }
            Files.write(path, Arrays.copyOf(header, 63));
            check(openShard(path, false) == null, "short header accepted");
            try (var file = new java.io.RandomAccessFile(path.toFile(), "rw")) { file.setLength(256L * 1024 * 1024 + 1); }
            check(openShard(path, false) == null, "oversized shard accepted");
            // Real positional reads still fail if the underlying payload disappears after opening.
            Files.write(path, records);
            try (var shard = openShard(path, false)) {
                Files.write(path, header);
                try { shardValue(shard, 1, 2); throw new AssertionError("truncated read accepted"); }
                catch (IOException expected) { check(expected.getMessage().contains("truncated"), "wrong read failure"); }
            }
        } finally { cleanup(root); }
    }

    private static void trimmedShardsReopen() throws Exception {
        Path root = Files.createTempDirectory("voxy-shard-trim-");
        var budget = new RegionalDiskBudget(root, 1024 * 1024);
        try (var store = new RegionalMetadataStore(budget); var cache = new RegionalCache(root, WORLD, budget)) {
            awaitInventory(budget);
            List<Fixture> fixtures = new ArrayList<>();
            for (int x = 0; x < 66; x++) {
                var f = fixture(1, 0, 1, 1, x, 0); fixtures.add(f);
                cache.put(f.index(), 340, f.payload());
            }
            var field = RegionalCache.class.getDeclaredField("shards"); field.setAccessible(true);
            Map<?, ?> handles = (Map<?, ?>) field.get(cache);
            check(handles.size() == 64 && !handles.containsKey(0L), "open shard limit not exercised");
            for (int pass = 0; pass < 3; pass++) for (var f : fixtures) {
                check(!handles.containsKey(Integer.toUnsignedLong(f.index().regionX())), "fixture unexpectedly hit open handle");
                check(Arrays.equals(cache.get(f.index(), 340), f.payload()), "trimmed shard reconstruction lost payload");
            }
            long total;
            try (var paths = Files.walk(root)) { total = paths.filter(Files::isRegularFile).mapToLong(RegionalDiskBudget::size).sum(); }
            check(budget.bytes == total, "repeated real shard reopen drifted accounting");
        } finally { cleanup(root); }
    }

    private static void grownShardOffsets() throws Exception {
        Path root = Files.createTempDirectory("voxy-shard-growth-");
        Path path = root.resolve("r.0.0.vxcache");
        int count = 8192;
        try {
            ByteBuffer bytes = buffer(64 + count * 24);
            bytes.put(new byte[]{'V','X','Y','S','E','C',0,0}).putLong(1).putLong(2).putLong(3).putLong(4);
            bytes.putInt(0).putInt(0).putLong(0).position(64);
            for (int i = 0; i < count; i++) bytes.put(record(i, 4, buffer(4).putInt(i).array()));
            Files.write(path, bytes.array());
            try (var shard = openShard(path, true)) {
                check(Arrays.equals(shardValue(shard, 0, 4), new byte[4]), "first payload offset confused with zero absence sentinel");
                var key = shardKey(count + 1, 4);
                var remove = shard.getClass().getDeclaredMethod("remove", key.getClass()); remove.setAccessible(true);
                long before = Files.size(path);
                invoke(remove, shard, key); check(Files.size(path) == before, "absent removal appended a tombstone");
                for (int i = 0; i < count; i += 3) invoke(remove, shard, shardKey(i, 4));
                long after = Files.size(path);
                for (int i = 0; i < count; i += 3) invoke(remove, shard, shardKey(i, 4));
                check(Files.size(path) == after, "repeated removals appended tombstones");
            }
            // Replay duplicates, resurrection and same fingerprint/different lengths through the real parser.
            Files.write(path, record(0, 4, buffer(4).putInt(99).array()), StandardOpenOption.APPEND);
            Files.write(path, record(1, 4, buffer(4).putInt(101).array()), StandardOpenOption.APPEND);
            Files.write(path, record(1, 2, new byte[]{8, 9}), StandardOpenOption.APPEND);
            byte[] expectedDisk = Files.readAllBytes(path);
            for (int pass = 0; pass < 3; pass++) try (var shard = openShard(path, false)) {
                for (int i = 0; i < count; i++) {
                    byte[] expected = i == 0 ? buffer(4).putInt(99).array() : i == 1 ? buffer(4).putInt(101).array()
                            : i % 3 == 0 ? null : buffer(4).putInt(i).array();
                    check(Arrays.equals(shardValue(shard, i, 4), expected), "grown/replayed offset lost for " + i);
                }
                check(Arrays.equals(shardValue(shard, 1, 2), new byte[]{8, 9}), "different-length key conflated");
                check(shardValue(shard, count + 1, 4) == null, "missing offset became readable");
                check(Arrays.equals(Files.readAllBytes(path), expectedDisk), "read-only replay changed file");
            }
        } finally { cleanup(root); }
    }

    private static byte[] record(long fingerprint, int length, byte[] payload) {
        return buffer(20 + payload.length).putLong(fingerprint).putLong(0).putInt(length).put(payload).array();
    }

    // Exercise the actual private parser, without substituting its file operations or reconstruction.
    private static AutoCloseable openShard(Path path, boolean writable) throws Exception {
        var type = Class.forName(RegionalCache.class.getName() + "$Shard");
        var method = type.getDeclaredMethod("open", Path.class, RegionalProtocol.Hash32.class, int.class, int.class, boolean.class);
        method.setAccessible(true);
        return (AutoCloseable) invoke(method, null, path, WORLD, 0, 0, writable);
    }
    private static byte[] shardValue(AutoCloseable shard, long fingerprint, int length) throws Exception {
        Object key = shardKey(fingerprint, length);
        var method = shard.getClass().getDeclaredMethod("get", key.getClass()); method.setAccessible(true);
        return (byte[]) invoke(method, shard, key);
    }
    private static Object shardKey(long fingerprint, int length) throws Exception {
        var keyType = Class.forName(RegionalCache.class.getName() + "$CacheKey");
        var constructor = keyType.getDeclaredConstructor(RegionalProtocol.Fingerprint.class, int.class);
        constructor.setAccessible(true);
        return constructor.newInstance(new RegionalProtocol.Fingerprint(fingerprint, 0), length);
    }
    private static Object invoke(java.lang.reflect.Method method, Object target, Object... args) throws Exception {
        try { return method.invoke(target, args); }
        catch (java.lang.reflect.InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception cause) throw cause;
            throw failure;
        }
    }

    private static void overlappingShardOwners() throws Exception {
        Path root = Files.createTempDirectory("voxy-shard-owners-");
        var first = fixture(1, 0, 1, 1); var second = fixture(1, 0, 2, 1);
        var budget = new RegionalDiskBudget(root, 1024 * 1024);
        try (var store = new RegionalMetadataStore(budget)) {
            awaitInventory(budget);
            for (int repeat = 0; repeat < 3; repeat++) {
                try (var a = new RegionalCache(root, WORLD, budget); var b = new RegionalCache(root, WORLD, budget)) {
                    // Force both handles to open before either append starts.
                    a.put(first.index(), 340, first.payload()); b.get(first.index(), 340);
                    var start = new CountDownLatch(1);
                    var executor = Executors.newFixedThreadPool(2);
                    try {
                        var x = executor.submit(() -> { start.await(); a.quarantine(first.index(), 340); return null; });
                        var y = executor.submit(() -> { start.await(); b.put(second.index(), 340, second.payload()); return null; });
                        start.countDown(); x.get(5, TimeUnit.SECONDS); y.get(5, TimeUnit.SECONDS);
                    } finally { start.countDown(); executor.shutdownNow(); }
                }
                try (var reopened = new RegionalCache(root, WORLD, budget)) {
                    check(reopened.get(first.index(), 340) == null, "overlapping tombstone lost");
                    check(Arrays.equals(reopened.get(second.index(), 340), second.payload()), "overlapping append lost");
                }
                check(budget.bytes == Files.size(root.resolve("r.0.0.vxcache")), "overlapping accounting drift");
            }
            Path broken = root.resolve("r.0.0.vxcache"); Files.delete(broken); Files.createDirectory(broken);
            try (var cache = new RegionalCache(root, WORLD, budget)) {
                try { cache.get(first.index(), 340); throw new AssertionError("directory opened as usable shard"); }
                catch (IOException expected) { /* Open failure must never register a shard. */ }
                var field = RegionalCache.class.getDeclaredField("shards"); field.setAccessible(true);
                check(((Map<?, ?>) field.get(cache)).isEmpty(), "failed open registered a shard");
            }
        } finally { cleanup(root); }
    }

    static Fixture fixture(long generation, int children, int light, long catalogId) throws Exception {
        return fixture(generation, children, light, catalogId, 0, 0);
    }

    private static Fixture fixture(long generation, int children, int light, long catalogId, int x, int z) throws Exception {
        byte[] block = "minecraft:stone".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] biome = "minecraft:plains".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ByteBuffer cat = buffer(46 + block.length + biome.length);
        cat.put("VXYCAT\0\0".getBytes()).putLong(catalogId).putLong(1).putLong(1).putInt(1).putInt(1);
        cat.put((byte) 15).put((byte) 1).putShort((short) block.length).put(block);
        cat.putShort((short) biome.length).put(biome);
        var catalog = new RegionalProtocol.CatalogMessage(RegionalMetadataStore.hash(cat.array()), cat.array());
        byte[] cells = buffer(11).putShort((short) 1).putInt(0).putInt(0).put((byte) light).array();
        byte[] payload = compress(cells);
        int count = 341;
        ByteBuffer index = buffer(36 + count * 48);
        index.put("VXYRIDX\0".getBytes()).putInt(x).putInt(z).putLong(generation)
                .putInt(0).putShort((short) 1).put((byte) 5).put((byte) 0).putInt(count);
        // One nonempty top-level section is sufficient to exercise real decode and greedy mesh.
        for (int ordinal : new int[]{0, 256, 320, 336, 340}) {
        index.position(36 + ordinal * 48);
        index.putShort((short) RegionalProtocol.SECTION_FLAG_PRESENT).put((byte) (ordinal == 0 ? 0 : children)).put(new byte[5])
                .putLong(4096).putInt(payload.length).putInt(cells.length).putInt(RegionalProtocol.crc32c(payload))
                .put(fingerprint(cells).bytes()).putInt(0);
        }
        var hash = fingerprint(index.array());
        var message = new RegionalProtocol.RegionMessage(x, z, generation, hash, catalog.fingerprint(), compress(index.array()));
        return new Fixture(catalog, message, RegionalProtocol.decodeIndex(index.array(), hash), payload);
    }

    static byte[] compress(byte[] bytes) {
        ByteBuffer input = MemoryUtil.memAlloc(bytes.length);
        ByteBuffer output = MemoryUtil.memAlloc((int) org.lwjgl.util.zstd.Zstd.ZSTD_compressBound(bytes.length));
        try {
            input.put(bytes).flip();
            long count = org.lwjgl.util.zstd.Zstd.ZSTD_compress(output, input, 1);
            check(!org.lwjgl.util.zstd.Zstd.ZSTD_isError(count), "fixture compression failed");
            byte[] result = new byte[(int) count]; output.get(result); return result;
        } finally { MemoryUtil.memFree(input); MemoryUtil.memFree(output); }
    }

    static void persist(RegionalMetadataStore store, Fixture fixture, boolean payload) throws Exception {
        awaitInventory(store.budget);
        store.associate(SERVER, DIMENSION, WORLD, store.budget.stamp(), () -> true);
        store.saveCatalog(WORLD, DIMENSION, fixture.catalog(), store.budget.stamp(), () -> true);
        store.saveRegion(WORLD, DIMENSION, 0, 0, fixture.message(), store.budget.stamp(), () -> true);
        if (payload) try (var cache = new RegionalCache(store.namespace(WORLD, DIMENSION), WORLD, store.budget)) {
            cache.put(fixture.index(), 340, fixture.payload());
        }
    }

    static void awaitInventory(RegionalDiskBudget budget) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!budget.ready() && System.nanoTime() < end) Thread.sleep(1);
        check(budget.ready(), "inventory did not become ready: " + budget.snapshot());
    }

    static final class Publication extends SectionPublicationState {
        int retirements;
        Publication(SectionSubmission submission) {
            submission.geometry().free();
            submission.previous().ifPresent(previous -> ((Publication) previous).markRetired());
            completeUpload(new UploadOutcome(UploadStatus.ACTIVATED, null, null));
        }
        @Override protected void requestRetirement() { retirements++; markRetired(); }
        @Override protected void stateChanged() {}
    }

    static final class Publisher implements SectionPublisher {
        final List<Publication> publications = new ArrayList<>();
        @Override public SubmissionAttempt tryPublishBatch(List<SectionSubmission> submissions) {
            var result = submissions.stream().map(Publication::new).toList();
            publications.addAll(result);
            return new SubmissionAttempt(SubmissionStatus.ACCEPTED, new ArrayList<>(result));
        }
        @Override public PublicationProgress progress() { return new PublicationProgress(publications.size(), 0, 0, 0, false, null); }
        @Override public void setProgressListener(Runnable listener) {}
        @Override public void clearProgressListener(Runnable listener) {}
        @Override public void coarsen(long parent, Runnable success, Consumer<Throwable> failure) { success.run(); }
    }

    static final class Driver implements AutoCloseable {
        final Publisher publisher = new Publisher();
        final ClientSession.Session session;
        boolean mapCatalog = true;
        Driver(Path root) throws Exception {
            var models = new SectionMesher.Models() {
                public int getModelId(int block) { return 1; }
                public long getModelMetadataFromClientId(int model) { return 0; }
                public int getFluidClientStateId(int model) { throw new AssertionError(); }
                public boolean isModelReadyForBlockId(int block) { return true; }
                public boolean isWaterState(int block) { return false; }
            };
            var mesherConstructor = SectionMesher.class.getDeclaredConstructor(SectionMesher.Models.class, java.util.function.IntConsumer.class);
            mesherConstructor.setAccessible(true);
            session = new ClientSession.Session(77, DIMENSION, null, publisher,
                    mesherConstructor.newInstance(models, (java.util.function.IntConsumer) ignored -> {}), 2);
            session.cacheRoot = root; session.serverKey = SERVER;
            session.metadataWorker.start();
            session.metadataWorker.assign(new ClientSession.Session.BootstrapTask(root, SERVER, DIMENSION));
            for (var worker : session.sectionWorkers) worker.start();
            session.demands.adopt(new ClientSession.Demand(KEY));
            session.queueRegion(0);
        }
        void step() throws Exception {
            session.connect(); session.drainWorkers();
            if (mapCatalog && session.pendingCatalogTask != null && !session.pendingCatalogSubmitted) {
                session.pendingCatalogSubmitted = true;
                // Current Minecraft registry mapping is the sole substituted catalog boundary.
                session.pendingCatalogTask.mapped(MAPPINGS);
            }
            session.drainEvents(); session.processMetadata();
            session.pollPublications(); session.processStages();
            check(session.failure == null, "session failed");
        }
        void until(BooleanSupplier complete) throws Exception {
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!complete.getAsBoolean() && System.nanoTime() < end) { step(); session.awaitWake(1); }
            check(complete.getAsBoolean(), "production pipeline did not reach expected state");
        }
        @Override public void close() throws Exception {
            session.open.set(false); session.release();
            session.metadataWorker.workerThread.join(5000);
            for (var worker : session.sectionWorkers) {
                worker.workerThread.join(5000);
                check(!worker.workerThread.isAlive(), "worker leaked on close");
            }
        }
    }

    static void localActivationAndValidation() throws Exception {
        Path root = Files.createTempDirectory("voxy-cache-start-");
        try {
            var store = new RegionalMetadataStore(root);
            var old = fixture(900, 0, 0xf0, 1);
            persist(store, old, true);
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            try (var driver = new Driver(root)) {
                var s = driver.session;
                s.connector = () -> {
                    entered.countDown();
                    try { release.await(); } catch (InterruptedException stop) { Thread.currentThread().interrupt(); }
                    throw new IOException("held endpoint unavailable");
                };
                driver.until(() -> s.activeCount == 1);
                check(entered.await(1, TimeUnit.SECONDS), "connector never started");
                check(s.quic == null && !s.helloAccepted && s.connectionEpoch == 0 && s.cacheHits == 1
                        && s.meshedSections == 1 && driver.publisher.publications.size() == 1,
                        "cached geometry required a Voxy network response");
                check(s.sectionWorkers[0].idle() && s.sectionWorkers[1].idle(), "activation retained worker");
                release.countDown(); driver.until(() -> s.lastConnectionFailure != null);
                check(s.activeCount == 1, "failed setup destroyed local geometry");
                var demand = s.demands.get(KEY);
                long revision = demand.revision;
                var binding = demand.catalog;
                // A provisional generation 900 does not overrule authoritative generation 1.
                var same = fixture(1, 0, 0xf0, 1);
                s.acceptHello(new RegionalProtocol.ServerHello(1, WORLD, 1, same.catalog().fingerprint()));
                s.demands.region(0).subscribed = true;
                check(s.acceptRegion(same.message()), "live validation not admitted");
                check(!s.validated(demand), "unverified incoming index authorized a saved-generation request");
                driver.until(() -> demand.index.generation() == 1);
                check(demand.revision == revision && demand.installed && s.meshedSections == 1,
                        "generation-only validation rebuilt geometry");
                var changed = fixture(2, 1, 0xe0, 1);
                s.bind(demand, changed.index(), 340, binding);
                check(demand.installed && demand.publication != null && demand.revision != revision,
                        "changed payload/mask removed fallback");
                // Return to cached bytes, but a different exact catalog must still replace.
                var catalogChange = fixture(3, 0, 0xf0, 2);
                var newBinding = new RegionalSectionCodec.BoundCatalog(catalogChange.catalog().fingerprint(), MAPPINGS);
                s.bind(demand, catalogChange.index(), 340, newBinding);
                check(demand.installed && demand.catalog == newBinding, "catalog change reused incompatible geometry");
                driver.until(() -> s.activated == 2);
                check(s.meshedSections == 2 && s.receivedBytes == 0, "catalog replacement redownloaded matching cells");
                var region = s.demands.region(0); region.subscribed = true;
                s.acceptRegionUnavailable(new RegionalProtocol.RegionUnavailable(0, 0, false));
                check(demand.installed && !region.validated, "pending regeneration erased provisional terrain");
                s.acceptRegionUnavailable(new RegionalProtocol.RegionUnavailable(0, 0, true));
                driver.until(() -> s.metadataWrites.isEmpty() && s.metadataWorker.idle());
                check(s.activeCount == 0 && store.region(WORLD, DIMENSION, 0, 0).absent(), "deletion not persisted");
            } finally { release.countDown(); }
            try (var restarted = new Driver(root)) {
                restarted.until(() -> restarted.session.metadataWorker.idle() && restarted.session.cacheOpened
                        && restarted.session.demands.region(0).localTried);
                check(restarted.session.activeCount == 0, "deleted terrain resurrected on restart");
            }
        } finally { cleanup(root); }
    }

    static void cachedRefinementWhileHeld() throws Exception {
        Path root = Files.createTempDirectory("voxy-cache-refine-");
        try {
            var store = new RegionalMetadataStore(root); persist(store, fixture(1, 1, 0xf0, 1), true);
            try (var driver = new Driver(root)) {
                var s = driver.session;
                driver.until(() -> s.activeCount == 1);
                for (int lod = 4; lod > 0; lod--) {
                    check(s.addChildren(SectionKey.pack(lod, 0, 0, 0), 15), "local index cannot refine");
                    int expected = 6 - lod;
                    driver.until(() -> s.activeCount == expected);
                }
                check(s.quic == null && !s.helloAccepted && s.cacheHits == 5 && s.meshedSections == 5,
                        "cached refinement required live metadata or transport");
            }
        } finally { cleanup(root); }
    }

    static void missesDoNotSpin() throws Exception {
        Path root = Files.createTempDirectory("voxy-cache-miss-");
        try {
            var store = new RegionalMetadataStore(root); var fixture = fixture(1, 0, 0xf0, 1);
            persist(store, fixture, false);
            try (var driver = new Driver(root)) {
                var s = driver.session;
                driver.until(() -> s.cacheMisses == 1);
                for (int i = 0; i < 50; i++) driver.step();
                check(s.cacheMisses == 1 && s.demands.get(KEY).candidate == SectionDemandTable.CandidateState.WAIT_REGION,
                        "provisional payload miss spun or sent obsolete generation");
                s.changeWorld(new RegionalProtocol.Hash32(5, 6, 7, 8));
                driver.until(() -> s.cacheOpened && s.metadataWorker.idle());
                check(s.activeCount == 0 && !s.worldIdentity.equals(WORLD) && s.mapping(fixture.catalog().fingerprint()) == null,
                        "world correction retained old mappings/geometry");
            }
        } finally { cleanup(root); }
    }

    static void metadataIntegrityAndBudget() throws Exception {
        Path root = Files.createTempDirectory("voxy-cache-integrity-");
        try {
            var budget = new RegionalDiskBudget(root, 8192); var store = new RegionalMetadataStore(budget);
            var fixture = fixture(1, 0, 0xf0, 1); persist(store, fixture, true);
            check(store.world(SERVER, DIMENSION).equals(WORLD), "association lost");
            check(store.world(SERVER, "minecraft:the_nether") == null, "dimension association leaked");
            check(store.region(new RegionalProtocol.Hash32(9, 0, 0, 0), DIMENSION, 0, 0) == null, "world namespace leaked");
            Path descriptor = store.descriptor(WORLD, DIMENSION, 0, 0);
            long stamp = budget.stamp(); budget.delete(descriptor);
            store.saveRegion(WORLD, DIMENSION, 0, 0, fixture.message(), stamp, () -> true);
            check(!Files.exists(descriptor), "late write resurrected evicted descriptor");
            persist(store, fixture, false);
            byte[] valid = Files.readAllBytes(descriptor), corrupt = valid.clone(); corrupt[corrupt.length - 1] ^= 1;
            Files.write(descriptor, corrupt);
            check(store.region(WORLD, DIMENSION, 0, 0) == null, "CRC corruption accepted");
            Files.write(descriptor, Arrays.copyOf(valid, 12));
            check(store.region(WORLD, DIMENSION, 0, 0) == null, "torn envelope accepted");
            Files.write(descriptor, valid);
            Path pending = descriptor.resolveSibling(descriptor.getFileName() + ".pending");
            Files.createDirectory(pending); // Inject a failed disk replacement without damaging the old target.
            try {
                store.saveRegion(WORLD, DIMENSION, 0, 0, null, budget.stamp(), () -> true);
                throw new AssertionError("injected write failure was accepted");
            } catch (IOException expected) {
                check(Arrays.equals(Files.readAllBytes(descriptor), valid), "failed disk replacement destroyed old descriptor");
            }
            store.saveRegion(WORLD, DIMENSION, 0, 0, null, budget.stamp(), () -> false);
            check(!store.region(WORLD, DIMENSION, 0, 0).absent(), "cancelled atomic replacement overwrote old view");
            // All world namespaces consume the same allowance, with no per-world multiplier.
            for (int i = 0; i < 70; i++) {
                var world = new RegionalProtocol.Hash32(100 + i, 0, 0, 0);
                store.saveCatalog(world, DIMENSION, fixture.catalog(), budget.stamp(), () -> true);
                store.saveRegion(world, DIMENSION, 0, 0, fixture.message(), budget.stamp(), () -> true);
                check(budget.bytes <= budget.limit, "metadata exceeded shared allowance");
            }
            long actual;
            try (var files = Files.walk(root)) { actual = files.filter(Files::isRegularFile).mapToLong(RegionalDiskBudget::size).sum(); }
            check(actual == budget.bytes, "budget accounting differs from disk extents");
        } finally { cleanup(root); }
    }

    static void corruptPayloadAndLateMapping() throws Exception {
        Path root = Files.createTempDirectory("voxy-cache-lifetime-");
        try {
            var store = new RegionalMetadataStore(root); var fixture = fixture(1, 0, 0xf0, 1);
            persist(store, fixture, true);
            Path payload = store.namespace(WORLD, DIMENSION).resolve("r.0.0.vxcache");
            byte[] bytes = Files.readAllBytes(payload); bytes[bytes.length - 1] ^= 1; Files.write(payload, bytes);
            try (var driver = new Driver(root)) {
                driver.until(() -> driver.session.cacheMisses == 1);
                check(driver.session.activeCount == 0 && driver.session.failure == null,
                        "corrupt compressed terrain escaped integrity checking");
            }
            try (var driver = new Driver(root)) {
                driver.mapCatalog = false;
                driver.until(() -> driver.session.pendingCatalogTask != null);
                var s = driver.session; var oldMapping = s.pendingCatalogTask;
                var newWorld = new RegionalProtocol.Hash32(50, 0, 0, 0);
                s.changeWorld(newWorld);
                oldMapping.mapped(MAPPINGS); s.drainEvents();
                check(s.metadataWorker.idle() && s.mapping(fixture.catalog().fingerprint()) == null
                        && s.activeCount == 0 && s.worldIdentity.equals(newWorld),
                        "late mapping crossed corrected world or leaked metadata worker");
            }
        } finally { cleanup(root); }
    }

    static void connectorCancellation() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), finish = new CountDownLatch(1), exited = new CountDownLatch(1);
        var held = new RegionalConnectionAttempt(() -> {
            entered.countDown();
            boolean done = false;
            while (!done) try { finish.await(); done = true; } catch (InterruptedException ignored) { }
            exited.countDown(); throw new IOException("late failed DNS");
        });
        check(entered.await(2, TimeUnit.SECONDS), "owned setup did not start");
        held.close();
        try (var next = new RegionalConnectionAttempt(() -> { throw new AssertionError("overlapping setup ran"); })) {
            check(next.poll().failure() instanceof IOException, "uninterruptible setup allowed another thread");
        } finally { finish.countDown(); }
        check(exited.await(2, TimeUnit.SECONDS), "setup failed to exit");
        check(held.poll() == null, "closed attempt delivered late result");
    }

    static void wireAbsence() throws Exception {
        // Matching Rust S_REGION encoding: coordinates, zero generation, explicit status byte.
        for (int status : new int[]{0, 1, 2}) {
            ByteBuffer body = buffer(22).put((byte) RegionalProtocol.S_REGION).putInt(17)
                    .putInt(0).putInt(0).putLong(0).put((byte) status);
            try {
                var message = RegionalProtocol.readControl(new java.io.ByteArrayInputStream(body.array()));
                check(status < 2 && message instanceof RegionalProtocol.RegionUnavailable absent
                        && absent.confirmedAbsent() == (status == 1), "absence semantics differ");
            } catch (IOException invalid) { check(status == 2, "valid unavailable status rejected"); }
        }
    }

    static void lateConnectionSuccessIsClosed() throws Exception {
        var closes = new java.util.concurrent.atomic.AtomicInteger();
        var connection = (tech.kwik.core.QuicClientConnection) java.lang.reflect.Proxy.newProxyInstance(
                tech.kwik.core.QuicClientConnection.class.getClassLoader(), new Class[]{tech.kwik.core.QuicClientConnection.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("close")) closes.incrementAndGet();
                    return method.getName().equals("isConnected") ? true : null;
                });
        var stream = (tech.kwik.core.QuicStream) java.lang.reflect.Proxy.newProxyInstance(
                tech.kwik.core.QuicStream.class.getClassLoader(), new Class[]{tech.kwik.core.QuicStream.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getInputStream" -> java.io.InputStream.nullInputStream();
                    case "getOutputStream" -> java.io.OutputStream.nullOutputStream();
                    default -> null;
                });
        var constructor = RegionalQuicClient.class.getDeclaredConstructor(
                tech.kwik.core.QuicClientConnection.class, tech.kwik.core.QuicStream.class, String.class);
        constructor.setAccessible(true);
        var client = constructor.newInstance(connection, stream, "late test result");
        var entered = new CountDownLatch(1); var finish = new CountDownLatch(1);
        var attempt = new RegionalConnectionAttempt(() -> {
            entered.countDown();
            for (;;) try { finish.await(); break; } catch (InterruptedException ignored) { }
            return client;
        });
        try {
            check(entered.await(2, TimeUnit.SECONDS), "late-success attempt did not start");
            attempt.close(); finish.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (closes.get() == 0 && System.nanoTime() < deadline) Thread.sleep(1);
            check(closes.get() == 1 && attempt.poll() == null, "late successful transport leaked or entered closed session");
            attempt.close(); check(closes.get() == 1, "late transport closed twice");
        } finally { finish.countDown(); attempt.close(); client.close(); }
    }

    static RegionalProtocol.Fingerprint fingerprint(byte[] bytes) {
        return RegionalProtocol.Fingerprint.read(ByteBuffer.wrap(new Blake3.Hasher().update(bytes).digest()).order(ByteOrder.LITTLE_ENDIAN));
    }
    static ByteBuffer buffer(int bytes) { return ByteBuffer.allocate(bytes).order(ByteOrder.LITTLE_ENDIAN); }
    static void cleanup(Path root) throws IOException {
        try (var files = Files.walk(root)) { for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); }
    }
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
