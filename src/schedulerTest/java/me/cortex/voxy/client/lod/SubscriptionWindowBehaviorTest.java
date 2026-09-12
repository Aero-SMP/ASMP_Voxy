package me.cortex.voxy.client.lod;

import me.cortex.voxy.client.core.rendering.RenderDistanceTracker;
import me.cortex.voxy.client.core.rendering.SectionKey;
import tech.kwik.core.QuicClientConnection;
import tech.kwik.core.QuicStream;
import java.io.*;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** Real tracker, owner, encoder and writer; only QUIC I/O and the renderer are substituted. */
final class SubscriptionWindowBehaviorTest {
    public static void main(String[] args) throws Exception { run(); }

    static void run() throws Exception {
        trackerWindowPublication();
        blockedWindowChanges();
        retainedReentryAndResponseOrder();
        retiredReentryAndReconnect();
        try (var wire = new Wire()) {
            wire.autoRespond = true;
            var tracker = new RenderDistanceTracker(0, 1, key -> {
                var demand = wire.session.demands.adopt(new ClientSession.Demand(key));
                wire.session.queueRegion(demand.regionKey);
            }, key -> {
                long region = wire.session.demands.get(key).regionKey;
                var state = wire.session.demands.region(region);
                wire.session.demands.remove(key);
                if (state.users == 0) wire.session.releaseRegion(region, state);
            }, wire.session::offerWindow);
            tracker.setRenderDistance(65);
            settle(tracker, wire, 0, 0);
            check(wire.server.size() == 13273, "initial window mismatch");
            settle(tracker, wire, 32 * 512, 0);
            System.out.println("subscription wire peak=" + wire.peak + " final=" + wire.server.size());
            check(wire.peak <= 16384, "ordered wire subscriptions exceeded unchanged server bound: " + wire.peak);
            for (int[] point : new int[][]{{-32, -32}, {500, -500}, {0, 0}, {-1, 0}, {-1, -1}}) {
                settle(tracker, wire, point[0] * 512, point[1] * 512);
                exactWindow(wire, point[0], point[1], 65);
            }
            var random = new java.util.Random(8192);
            for (int step = 0; step < 20; step++) {
                // Deliberately do not finish the previous tracker operation backlog.
                tracker.setCenterAndProcess((random.nextInt(200) - 100) * 512, (random.nextInt(200) - 100) * 512);
                wire.drain();
                check(wire.peak <= 16384, "rapid revisions overflowed");
            }
            settle(tracker, wire, 0, 0);
            exactWindow(wire, 0, 0, 65);
            // Every menu value maps to one of these exact integer radii.
            for (int radius = 64; radius >= 2; radius--) {
                tracker.setRenderDistance(radius);
                settle(tracker, wire, 0, 0);
                exactWindow(wire, 0, 0, radius);
            }
            long reconciliations = wire.session.windowReconciliations;
            long allocated = WorkerDebugTelemetry.allocatedBytes();
            for (int wake = 0; wake < 1000; wake++) wire.session.processRegions();
            check(wire.session.windowReconciliations == reconciliations, "unchanged window rescanned regions");
            System.out.println("unchanged-window 1000 wakes allocatedBytes=" + (WorkerDebugTelemetry.allocatedBytes() - allocated)
                    + " reconciliations=0; maximum wire subscriptions=" + wire.peak);
        }
    }

    static void retiredReentryAndReconnect() throws Exception {
        try (var wire = new Wire()) {
            var s = wire.session;
            var here = new RenderDistanceTracker.Window(0, 0, 2);
            var away = new RenderDistanceTracker.Window(100, 0, 2);
            var old = s.demands.adopt(new ClientSession.Demand(SectionKey.pack(0, 0, 0, 0)));
            var region = s.demands.region(old.regionKey);
            s.offerWindow(here); s.queueRegion(region.key); wire.drain();
            s.retireDemand(old.key);
            check(region.users == 0 && s.demands.region(region.key) == region, "retirement lost ordered response ownership");
            var replacement = s.demands.adopt(new ClientSession.Demand(old.key));
            s.queueRegion(region.key); wire.drain();
            check(region.users == 1 && region.pendingResponses == 2, "recreated demand did not reuse reply accounting");
            s.acceptRegionUnavailable(new RegionalProtocol.RegionUnavailable(0, 0, true));
            check(s.demands.get(old.key) == replacement && !region.validated, "late retired-generation response removed successor");
            s.offerWindow(away);
            var current = s.demands.adopt(new ClientSession.Demand(SectionKey.pack(4, 100, 0, 0)));
            s.queueRegion(current.regionKey);
            s.connectionEpoch++;
            s.resetConnection(new IOException("window transition reconnect fixture"));
            try (var reconnected = new Wire(s)) {
                reconnected.drain();
                check(reconnected.server.equals(Set.of(current.regionKey)), "reconnect resubscribed retained outgoing demand");
                check(region.pendingResponses == 0 && !region.subscribed && !region.validated,
                        "reconnect retained old response authority");
                s.retireDemand(replacement.key);
                check(s.demands.region(region.key) == null, "reconnect retained unused response record");
            }
        }
    }

    static void blockedWindowChanges() throws Exception {
        for (boolean blockRelease : new boolean[]{false, true}) try (var wire = new Wire()) {
            var s = wire.session;
            var here = new RenderDistanceTracker.Window(0, 0, 2);
            var away = new RenderDistanceTracker.Window(100, 0, 2);
            var a = s.demands.adopt(new ClientSession.Demand(SectionKey.pack(4, 0, 0, 0)));
            var b = s.demands.adopt(new ClientSession.Demand(SectionKey.pack(4, 100, 0, 0)));
            s.queueRegion(a.regionKey); s.queueRegion(b.regionKey);
            s.offerWindow(here);
            if (blockRelease) wire.drain();
            var entered = new java.util.concurrent.CountDownLatch(1);
            var resume = new java.util.concurrent.CountDownLatch(1);
            int kind = Byte.toUnsignedInt((blockRelease ? RegionalProtocol.regionRelease(0, 0)
                    : RegionalProtocol.regionRequest(0, 0))[0]);
            wire.beforeWrite = record -> {
                if (Byte.toUnsignedInt(record[0]) != kind) return;
                entered.countDown();
                try { check(resume.await(5, TimeUnit.SECONDS), "test gate not released"); }
                catch (InterruptedException error) { throw new AssertionError(error); }
            };
            try {
                if (blockRelease) s.offerWindow(away);
                // Flush initial catalog independently; a rejected region offer must retry.
                for (int attempt = 0; attempt < 3 && entered.getCount() != 0; attempt++) {
                    s.processRegions();
                    if (entered.getCount() == 0) break;
                    if (wire.completed.tryAcquire(100, TimeUnit.MILLISECONDS)) continue;
                    check(entered.await(5, TimeUnit.SECONDS), "writer gate not reached");
                }
                check(entered.getCount() == 0, "writer gate not reached");
                s.offerWindow(blockRelease ? here : away);
                s.processRegions();
                check(!s.demands.region(b.regionKey).subscribed, "blocked writer falsely admitted incoming region");
            } finally { resume.countDown(); }
            wire.beforeWrite = record -> {};
            wire.drain();
            check(wire.server.equals(Set.of(blockRelease ? a.regionKey : b.regionKey)),
                    "window replacement lost pending release or reentry");
            check(wire.peak == 1, "incoming request overtook release");
        }
    }

    static void trackerWindowPublication() {
        var windows = new java.util.ArrayList<RenderDistanceTracker.Window>();
        var tracker = new RenderDistanceTracker(0, 0, key -> {
            check(!windows.isEmpty(), "callback preceded target publication");
        }, key -> {}, windows::add);
        tracker.setRenderDistance(3);
        check(windows.isEmpty(), "uninitialized tracker published origin");
        tracker.setCenterAndProcess(-1, -1);
        check(windows.getLast().equals(new RenderDistanceTracker.Window(-1, -1, 3)), "negative coordinates did not floor");
        tracker.setCenterAndProcess(1, 1);
        check(windows.size() == 1, "movement threshold changed");
        tracker.setCenterAndProcess(129, 1);
        check(windows.getLast().equals(new RenderDistanceTracker.Window(0, 0, 3)), "real center change not published");
        tracker.setRenderDistance(2);
        check(windows.getLast().equals(new RenderDistanceTracker.Window(0, 0, 2)), "distance change not published");
    }

    static void exactWindow(Wire wire, int x, int z, int radius) {
        var expected = new HashSet<Long>();
        for (int dx = -radius; dx <= radius; dx++) {
            int edge = (int) Math.sqrt(radius * radius - dx * dx);
            for (int dz = -edge; dz <= edge; dz++) {
                expected.add(Integer.toUnsignedLong(x + dx) | (Integer.toUnsignedLong(z + dz) << 32));
            }
        }
        check(wire.server.equals(expected), "settled wire membership differs from tracker circle");
        check(expected.size() <= 16384, "supported radius exceeds bound at rest");
    }

    static final class Publication extends me.cortex.voxy.client.core.rendering.hierarchical.SectionPublicationState {
        int closes;
        Publication() {
            completeUpload(new me.cortex.voxy.client.core.VoxyRenderSystem.UploadOutcome(
                    me.cortex.voxy.client.core.VoxyRenderSystem.UploadStatus.ACTIVATED, null, null));
        }
        @Override protected void requestRetirement() { closes++; markRetired(); }
        @Override protected void stateChanged() {}
    }

    static void retainedReentryAndResponseOrder() throws Exception {
        try (var wire = new Wire()) {
            var s = wire.session;
            var demand = s.demands.adopt(new ClientSession.Demand(SectionKey.pack(0, 0, 0, 0)));
            var region = s.demands.region(demand.regionKey);
            s.queueRegion(region.key);
            wire.drain();
            check(wire.server.isEmpty(), "startup invented an origin window");
            var here = new RenderDistanceTracker.Window(0, 0, 2);
            var away = new RenderDistanceTracker.Window(100, 100, 2);
            s.offerWindow(here);
            wire.drain();
            check(region.pendingResponses == 1, "request response ownership missing");
            var publication = new Publication();
            demand.publication = publication;
            demand.installed = true;
            s.setActiveGeometryBytes(demand, 1024);
            s.activeCount = 1;
            s.offerWindow(away);
            s.reconcileWindow();
            check(!region.subscribed && !region.validated && demand.installed && publication.closes == 0
                    && demand.activeGeometryBytes == 1024 && s.activeCount == 1
                    && s.demands.get(demand.key) == demand, "early release retired retained geometry");
            s.applyRegionChanged(region.key, 0);
            check(publication.closes == 0, "outgoing zero-generation notification retired geometry");
            s.offerWindow(here); // Reentry before release is sent must retain release/request order.
            wire.drain();
            check(region.pendingResponses == 2, "reentry forgot old response");
            s.regionChanged(new RegionalProtocol.RegionChanged(0, 0, 0));
            s.acceptRegionUnavailable(new RegionalProtocol.RegionUnavailable(0, 0, true));
            s.demands.drainRegions(s::applyRegionChanged);
            check(publication.closes == 0 && !region.validated && region.pendingResponses == 1,
                    "pre-release absence became authoritative after reentry");
            s.acceptRegionUnavailable(new RegionalProtocol.RegionUnavailable(0, 0, false));
            check(region.pendingResponses == 0 && !region.validated && publication.closes == 0,
                    "temporary unavailability destroyed retained geometry");
            s.retireDemand(demand.key);
            check(publication.closes == 1 && s.activeCount == 0, "normal retirement did not close exactly once");
            wire.drain();
            check(wire.server.isEmpty() && s.demands.regionCount() == 0, "retirement leaked subscription/region");
        }
    }

    static void settle(RenderDistanceTracker tracker, Wire wire, double x, double z) throws Exception {
        boolean changed;
        do {
            changed = tracker.setCenterAndProcess(x, z);
            wire.drain();
        } while (changed);
    }

    static final class Wire extends OutputStream implements AutoCloseable {
        final Set<Long> server = new HashSet<>();
        final ClientSession.Session session;
        final Semaphore completed = new Semaphore(0);
        final java.util.concurrent.ConcurrentLinkedQueue<Long> responses = new java.util.concurrent.ConcurrentLinkedQueue<>();
        boolean autoRespond;
        volatile java.util.function.Consumer<byte[]> beforeWrite = record -> {};
        final RegionalQuicClient client;
        final Thread writing;
        final RegionalQuicClient.ControlWriter writer;
        int peak;
        volatile Throwable failure;

        Wire() throws Exception {
            this(new ClientSession.Session(900, "minecraft:overworld", null, null, null, 0));
        }

        Wire(ClientSession.Session session) throws Exception {
            this.session = session;
            var connection = (QuicClientConnection) Proxy.newProxyInstance(
                    QuicClientConnection.class.getClassLoader(), new Class[]{QuicClientConnection.class},
                    (proxy, method, args) -> method.getName().equals("isConnected") ? true : null);
            var stream = (QuicStream) Proxy.newProxyInstance(QuicStream.class.getClassLoader(),
                    new Class[]{QuicStream.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "getInputStream" -> InputStream.nullInputStream();
                        case "getOutputStream" -> this;
                        default -> null;
                    });
            var constructor = RegionalQuicClient.class.getDeclaredConstructor(
                    QuicClientConnection.class, QuicStream.class, String.class);
            constructor.setAccessible(true);
            client = constructor.newInstance(connection, stream, "subscription test");
            writer = new RegionalQuicClient.ControlWriter(this, completed::release, error -> {
                failure = error;
                completed.release();
            });
            DebugSnapshotShutdownBehaviorTest.set(client, "controlWriter", writer);
            session.quic = client;
            session.worldIdentity = RegionalProtocol.Hash32.ZERO;
            session.helloAccepted = true;
            writing = Thread.ofVirtual().start(writer);
        }

        @Override public void write(int value) { throw new AssertionError("expected complete record"); }
        @Override public void write(byte[] record) {
            beforeWrite.accept(record);
            var bytes = ByteBuffer.wrap(record).order(ByteOrder.LITTLE_ENDIAN);
            int kind = Byte.toUnsignedInt(bytes.get());
            if (java.util.Arrays.equals(record, RegionalProtocol.catalogRequest())) return;
            check(bytes.getInt() == 8, "unexpected record: " + java.util.HexFormat.of().formatHex(record));
            long key = Integer.toUnsignedLong(bytes.getInt()) | (Integer.toUnsignedLong(bytes.getInt()) << 32);
            if (kind == Byte.toUnsignedInt(RegionalProtocol.regionRequest(0, 0)[0])) {
                server.add(key);
                if (autoRespond) responses.add(key);
            }
            else {
                check(kind == Byte.toUnsignedInt(RegionalProtocol.regionRelease(0, 0)[0]), "unexpected control");
                check(server.remove(key), "duplicate or unowned release");
            }
            peak = Math.max(peak, server.size());
        }

        void drain() throws Exception {
            // A full writer retains one real record. Wait for its completion before the next pass.
            do {
                session.processRegions();
                var record = writer.getClass().getDeclaredField("record");
                record.setAccessible(true);
                synchronized (writer) {
                    if (record.get(writer) == null && completed.availablePermits() == 0) break;
                }
                check(completed.tryAcquire(10, TimeUnit.SECONDS), "writer stalled");
                check(failure == null, "writer failed: " + failure);
                Long response;
                while ((response = responses.poll()) != null) {
                    session.acceptRegionUnavailable(new RegionalProtocol.RegionUnavailable((int) (long) response,
                            (int) (response >>> 32), false));
                    var state = session.demands.region(response);
                    if (state != null) state.retryAfter = System.nanoTime() + TimeUnit.HOURS.toNanos(1);
                }
            } while (true);
        }

        @Override public void close() throws IOException {
            client.close();
            try { writing.join(5000); }
            catch (InterruptedException error) { throw new IOException(error); }
            check(!writing.isAlive(), "writer leaked");
        }
    }

    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
