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
        storedAirProjectionSkipsPayloadWork();
        cachedRefinementWhileHeld();
        missesDoNotSpin();
        connectorCancellation();
        lateConnectionSuccessIsClosed();
        wireAbsence();
        System.out.println("self-contained startup, empty topology, cache-only refinement and connector lifetime passed");
    }

    record Fixture(RegionalProtocol.CatalogMessage catalog, RegionalProtocol.RegionMessage message,
                   RegionalProtocol.RegionIndex index, byte[] payload) {}

    static CompletedSectionCache completedCache(Path root, Fixture fixture) throws Exception {
        try (var metadata = new RegionalMetadataStore(root)) {
            awaitInventory(metadata.budget);
            var cache = new CompletedSectionCache(metadata, WORLD, DIMENSION);
            storeSection(cache, fixture, 340);
            return cache;
        }
    }

    static void storeSection(CompletedSectionCache cache, Fixture fixture, int ordinal) throws Exception {
        var section = LocalSection.from(fixture.index(), ordinal, fixture.catalog().fingerprint());
        if (section.kind() != LocalSection.DATA) { cache.putMetadata(section, null, () -> true); return; }
        try (var wire = new RegionalSectionCodec(); var local = new LocalSectionCodec()) {
            byte[] canonical = wire.decompress(fixture.payload(), section.canonicalBytes());
            wire.decode(section.key(), section.children(), canonical, section.fingerprint(), MAPPINGS);
            try (var save = cache.begin(section, local, canonical, CatalogCodec.decode(fixture.catalog().canonical()), () -> true)) {
                while (!save.step()) {}
            }
        }
    }

    static Fixture fixture(long generation, int children, int light, long catalogId) throws Exception {
        return fixture(generation, children, light, catalogId, 0, 0);
    }

    static Fixture fixture(long generation, int children, int light, long catalogId, int x, int z) throws Exception {
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
        try (var cache = new CompletedSectionCache(store, WORLD, DIMENSION)) {
            for (int ordinal = 0; ordinal < fixture.index().entryCount(); ordinal++) if (fixture.index().isPresent(ordinal)
                    && (payload || fixture.index().isEmpty(ordinal))) storeSection(cache, fixture, ordinal);
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

    static class Publisher implements SectionPublisher {
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
        final Publisher publisher;
        final ClientSession.Session session;
        Driver(Path root) throws Exception {
            this(root, new Publisher(), mesher());
        }
        Driver(Path root, Publisher publisher, SectionMesher mesher) throws Exception {
            this.publisher = publisher;
            session = new ClientSession.Session(77, DIMENSION, null, publisher, mesher, 2);
            session.cacheRoot = root; session.serverKey = SERVER;
            session.metadataWorker.start();
            session.metadataWorker.assign(new ClientSession.Session.BootstrapTask(root, SERVER, DIMENSION));
            for (var worker : session.sectionWorkers) worker.start();
            session.demands.adopt(new ClientSession.Demand(KEY));
            session.queueRegion(0);
        }
        static SectionMesher mesher() throws Exception {
            var models = new SectionMesher.Models() {
                public int getModelId(int block) { return 1; }
                public long getModelMetadataFromClientId(int model) { return 0; }
                public int getFluidClientStateId(int model) { throw new AssertionError(); }
                public boolean isModelReadyForBlockId(int block) { return true; }
                public boolean isWaterState(int block) { return false; }
            };
            var mesherConstructor = SectionMesher.class.getDeclaredConstructor(SectionMesher.Models.class, java.util.function.IntConsumer.class);
            mesherConstructor.setAccessible(true);
            return mesherConstructor.newInstance(models, (java.util.function.IntConsumer) ignored -> {});
        }
        void step() throws Exception {
            session.connect(); session.drainWorkers(); session.drainNetworkReplies();
            session.resolveNames((name, biome) -> biome ? 0 : 15);
            session.drainEvents(); session.processMetadata();
            session.pollPublications(); session.processStages();
            check(session.failure == null, "session failed");
        }
        void until(BooleanSupplier complete) throws Exception {
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!complete.getAsBoolean() && System.nanoTime() < end) { step(); session.awaitWake(1); }
            if (!complete.getAsBoolean()) {
                for (var demand : session.demands.values()) System.out.println("WAIT key=" + demand.key
                        + " candidate=" + demand.candidate + " ready=" + demand.readyKind
                        + " index=" + (demand.index == null ? "none" : demand.index.generation())
                        + " pending=" + (demand.pendingIndex == null ? "none" : demand.pendingIndex.generation())
                        + " lease=" + demand.workLease + " installed=" + demand.installed);
                System.out.println("WAIT catalog=" + session.catalogFingerprint + " saved=" + session.retainedSaveBytes()
                        + " persistence=" + session.lastPersistenceFailure);
            }
            check(complete.getAsBoolean(), "production pipeline did not reach expected state");
        }
        void catalog(RegionalProtocol.CatalogMessage catalog) throws Exception {
            var accepted = new java.util.concurrent.atomic.AtomicBoolean();
            until(() -> accepted.get() || (session.acceptCatalog(catalog) && accepted.compareAndSet(false, true)));
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

    static void storedAirProjectionSkipsPayloadWork() throws Exception {
        byte[] canonical = new byte[36 + 341 * 48];
        RegionalProtocol.Fingerprint expected = null;
        for (String line : Files.readAllLines(Path.of("test-fixtures/regional-air-index.txt"))) {
            String[] fields = line.split("\\|");
            byte[] bytes = HexFormat.of().parseHex(fields[1]);
            if (fields[0].equals("hash")) {
                expected = RegionalProtocol.Fingerprint.read(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN));
            } else {
                int offset = fields[0].equals("header") ? 0 : 36 + Integer.parseInt(fields[0]) * 48;
                System.arraycopy(bytes, 0, canonical, offset, bytes.length);
            }
        }
        check(fingerprint(canonical).equals(expected), "Rust normalized index hash differs in Java");
        var index = RegionalProtocol.decodeIndex(canonical, expected);
        check(index.isEmpty(0) && index.isEmpty(1) && index.isEmpty(340)
                && !index.isEmpty(2) && index.childMask(340) == 0xa5,
                "stored-air wire semantics changed");
        // Storage permits this metadata; the unchanged Java wire validator must not.
        byte[] malformed = canonical.clone();
        System.arraycopy(canonical, 36 + 2 * 48 + 8, malformed, 36 + 48 + 8, 36);
        try {
            RegionalProtocol.decodeIndex(malformed, fingerprint(malformed));
            throw new AssertionError("storage-only air metadata accepted on wire");
        } catch (IOException expectedFailure) { }
        Path root = Files.createTempDirectory("voxy-stored-air-");
        try {
            var catalog = fixture(1, 0, 15, 1).catalog();
            var message = new RegionalProtocol.RegionMessage(0, 0, 1, expected, catalog.fingerprint(), compress(canonical));
            var store = new RegionalMetadataStore(root);
            persist(store, new Fixture(catalog, message, index, new byte[0]), false);
            try (var driver = new Driver(root)) {
                var s = driver.session;
                s.connector = () -> { throw new IOException("no terrain server for empty fixture"); };
                driver.until(() -> s.activated == 1);
                check(s.demands.get(KEY).installed && s.demands.get(KEY).content.children() == 0xa5,
                        "empty hierarchy was not completed");
                check(s.cacheReads == 0 && s.cacheHits == 0 && s.cacheMisses == 0 && s.cacheBytes == 0
                        && s.receivedBytes == 0 && s.decodedSections == 0 && s.meshedSections == 0,
                        "stored air caused payload I/O, decoding or meshing");
                check(driver.publisher.publications.size() == 1, "empty hierarchy publication missing");
                check(!Files.exists(store.namespace(WORLD, DIMENSION).resolve("r.0.0.vxcache")),
                        "stored air created a section payload cache file");
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
                driver.until(() -> s.demands.region(0).localLoaded);
                for (int i = 0; i < 50; i++) driver.step();
                check(s.cacheMisses == 0 && s.demands.get(KEY).candidate == SectionDemandTable.CandidateState.WAIT_REGION,
                        "provisional payload miss spun or sent obsolete generation");
                s.changeWorld(new RegionalProtocol.Hash32(5, 6, 7, 8));
                driver.until(() -> s.cacheOpened && s.metadataWorker.idle());
                check(s.activeCount == 0 && !s.worldIdentity.equals(WORLD) && s.mapping(fixture.catalog().fingerprint()) == null,
                        "world correction retained old mappings/geometry");
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
        RegionalConnectionAttempt.Connector connector = () -> {
            entered.countDown();
            for (;;) try { finish.await(); break; } catch (InterruptedException ignored) { }
            return client;
        };
        var attempt = new RegionalConnectionAttempt(connector);
        // The preceding cancellation fixture signals from inside its connector, before the
        // single executor has finished its resource callback. Respect its real busy response.
        long available = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (entered.getCount() != 0 && System.nanoTime() < available) {
            var outcome = attempt.poll();
            if (outcome != null) {
                check(outcome.failure() instanceof IOException, "unexpected late-success setup failure");
                attempt.close();
                attempt = new RegionalConnectionAttempt(connector);
            }
            Thread.sleep(1);
        }
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
