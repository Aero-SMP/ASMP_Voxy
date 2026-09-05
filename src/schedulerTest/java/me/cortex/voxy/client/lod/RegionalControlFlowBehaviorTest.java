package me.cortex.voxy.client.lod;

import tech.kwik.core.QuicClientConnection;
import tech.kwik.core.QuicStream;
import me.cortex.voxy.client.core.rendering.SectionKey;
import java.io.*;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

final class RegionalControlFlowBehaviorTest {
    static void run() throws Exception {
        blockedDuplexStillDrainsResponses();
        ownerRetainsUnsentWorkAndReleases();
        writerFailureAndClose();
        System.out.println("regional control backpressure and owner retry tests passed");
    }

    private static void blockedDuplexStillDrainsResponses() throws Exception {
        try (var requests = new PipedInputStream(64);
             var sending = new PipedOutputStream(requests);
             var responses = new PipedInputStream(64);
             var replying = new PipedOutputStream(responses);
             var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            byte[] request = new byte[4096];
            Arrays.fill(request, (byte) 7);
            byte[] response = new byte[4096];
            Arrays.fill(response, (byte) 9);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Semaphore activity = new Semaphore(0);
            try (var writer = new RegionalQuicClient.ControlWriter(sending, activity::release, failure::set)) {
                var writing = threads.submit(writer);
                var server = threads.submit(() -> {
                    replying.write(response); // Must finish the response before reading requests.
                    check(Arrays.equals(requests.readNBytes(request.length), request), "request changed");
                    return null;
                });
                check(writer.offer(request), "initial write rejected");
                check(!writer.offer(new byte[]{1}), "blocked record was overwritten or queued");
                // The owner remains available to read: both bounded directions make progress.
                var owner = threads.submit(() -> responses.readNBytes(response.length));
                check(Arrays.equals(owner.get(5, TimeUnit.SECONDS), response), "response changed");
                server.get(5, TimeUnit.SECONDS);
                check(activity.tryAcquire(5, TimeUnit.SECONDS), "write completion did not wake owner");
                check(failure.get() == null, "duplex exchange failed: " + failure.get());
                writer.close();
                writing.get(5, TimeUnit.SECONDS);
                check(!writer.offer(request), "closed writer accepted work");
            }
        }
    }

    private static void ownerRetainsUnsentWorkAndReleases() throws Exception {
        ByteArrayOutputStream written = new ByteArrayOutputStream();
        var connection = (QuicClientConnection) Proxy.newProxyInstance(
                QuicClientConnection.class.getClassLoader(), new Class[]{QuicClientConnection.class},
                (proxy, method, args) -> method.getName().equals("isConnected") ? true : null);
        var stream = (QuicStream) Proxy.newProxyInstance(QuicStream.class.getClassLoader(),
                new Class[]{QuicStream.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getInputStream" -> InputStream.nullInputStream();
                    case "getOutputStream" -> written;
                    default -> null;
                });
        var constructor = RegionalQuicClient.class.getDeclaredConstructor(
                QuicClientConnection.class, QuicStream.class, String.class);
        constructor.setAccessible(true);
        try (var client = constructor.newInstance(connection, stream, "test")) {
            var field = RegionalQuicClient.class.getDeclaredField("controlWriter");
            field.setAccessible(true);
            var writer = (RegionalQuicClient.ControlWriter) field.get(client);
            var session = new ClientSession.Session(1, "minecraft:overworld", null, null, null, 0);
            session.quic = client;
            session.worldIdentity = RegionalProtocol.Hash32.ZERO;
            client.setActivityListener(session::signal);
            var demand = new ClientSession.Demand(SectionKey.pack(4, 0, 0, 0));
            session.demands.adopt(demand);
            session.queueRegion(demand.regionKey);
            client.hello("minecraft:overworld"); // Occupy the writer without running it yet.
            session.processRegions();
            var region = session.demands.region(demand.regionKey);
            check(!region.requested && !region.subscribed, "unsent region marked requested");
            session.ensureCatalog(RegionalProtocol.Hash32.ZERO);
            check(!session.catalogRequested, "unsent catalog marked requested");
            var writing = Thread.ofVirtual().start(writer);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!region.requested && System.nanoTime() < deadline) {
                session.processRegions();
                session.awaitWake(10);
            }
            check(region.requested && session.catalogRequested, "deferred work lost");
            // Releasing the last user is retained even if the writer still owns a request.
            session.demands.remove(demand.key);
            session.releaseRegion(demand.regionKey, region);
            check(session.regionReleases.contains(demand.regionKey), "release lost");
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!session.regionReleases.isEmpty() && System.nanoTime() < deadline) {
                session.processRegions();
                session.awaitWake(10);
            }
            check(session.regionReleases.isEmpty(), "release never retried");
            // Wait for the last write before checking exact ordering, with no extra catalog requests.
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            ByteArrayOutputStream expected = new ByteArrayOutputStream();
            expected.write(RegionalProtocol.hello("minecraft:overworld"));
            expected.write(RegionalProtocol.catalogRequest());
            expected.write(RegionalProtocol.regionRequest(0, 0));
            expected.write(RegionalProtocol.regionRelease(0, 0));
            while (written.size() < expected.size() && System.nanoTime() < deadline) session.awaitWake(10);
            check(Arrays.equals(written.toByteArray(), expected.toByteArray()), "control ordering/duplication");
            writer.close();
            writing.join(5000);
            check(!writing.isAlive(), "idle writer did not close");
        }
    }

    private static void writerFailureAndClose() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var broken = new OutputStream() {
            @Override public void write(int ignored) throws IOException { throw new IOException("injected reset"); }
        };
        try (var writer = new RegionalQuicClient.ControlWriter(broken, () -> {}, failure::set)) {
            writer.offer(new byte[]{1});
            var thread = Thread.ofVirtual().start(writer);
            thread.join(5000);
            check(!thread.isAlive() && failure.get() instanceof IOException, "write failure lost");
            check(!writer.offer(new byte[]{2}), "failed writer accepted another record");
        }
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
