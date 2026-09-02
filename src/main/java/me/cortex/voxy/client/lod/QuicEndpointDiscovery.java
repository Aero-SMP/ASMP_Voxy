package me.cortex.voxy.client.lod;

import me.cortex.voxy.network.QuicEndpointPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.HandlerThread;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/** Discovers the authenticated QUIC endpoint advertised by the Minecraft server. */
final class QuicEndpointDiscovery {
    private static final AtomicReference<ArrayBlockingQueue<QuicEndpointPayload>> REQUEST =
            new AtomicReference<>();
    /** One resolver may be stuck in native DNS; no further work queues behind it. */
    private static final ThreadPoolExecutor RESOLVER = new ThreadPoolExecutor(
            1, 1, 0, TimeUnit.MILLISECONDS, new SynchronousQueue<>(), task -> {
                Thread thread = new Thread(task, "Voxy endpoint resolver");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    private QuicEndpointDiscovery() {}

    static void register(IEventBus modBus) {
        modBus.addListener(QuicEndpointDiscovery::registerPayload);
    }

    private static void registerPayload(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar(QuicEndpointPayload.REGISTRATION_VERSION)
                .optional().executesOn(HandlerThread.NETWORK);
        registrar.playBidirectional(QuicEndpointPayload.TYPE, QuicEndpointPayload.CODEC,
                (payload, context) -> receiveEndpoint(payload));
    }

    static QuicClient connect() throws IOException {
        ClientPacketListener listener = Minecraft.getInstance().getConnection();
        if (listener == null || !listener.hasChannel(QuicEndpointPayload.TYPE)) {
            throw new IOException("Minecraft server does not advertise Voxy QUIC");
        }

        var response = new ArrayBlockingQueue<QuicEndpointPayload>(1);
        if (!REQUEST.compareAndSet(null, response)) {
            throw new IOException("a Voxy QUIC endpoint request is already pending");
        }
        QuicEndpointPayload endpoint = null;
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (endpoint == null && System.nanoTime() - deadline < 0) {
                listener.send(QuicEndpointPayload.request());
                endpoint = response.poll(250, TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted waiting for the Voxy QUIC endpoint", exception);
        } finally {
            REQUEST.compareAndSet(response, null);
        }
        if (endpoint == null || endpoint.isRequest()) {
            throw new IOException("Minecraft server did not provide a Voxy QUIC endpoint");
        }
        InetAddress[] addresses = endpoint.host().isEmpty()
                ? new InetAddress[]{remoteAddress(listener.getConnection().getRemoteAddress())}
                : resolve(endpoint.host());
        return QuicClient.connect(addresses, endpoint.udpPort(), endpoint.alpn(),
                endpoint.certificateSha256());
    }

    private static void receiveEndpoint(QuicEndpointPayload payload) {
        var response = REQUEST.get();
        if (response != null) response.offer(payload);
    }

    private static InetAddress remoteAddress(SocketAddress remote) throws IOException {
        if (!(remote instanceof InetSocketAddress endpoint) || endpoint.getAddress() == null) {
            throw new IOException("Minecraft peer has no numeric address for Voxy QUIC");
        }
        return endpoint.getAddress();
    }

    private static InetAddress[] resolve(String host) throws IOException {
        Future<InetAddress[]> lookup;
        try {
            lookup = RESOLVER.submit(() -> InetAddress.getAllByName(host));
        } catch (java.util.concurrent.RejectedExecutionException busy) {
            throw new IOException("the Voxy endpoint resolver is still busy", busy);
        }
        try {
            return lookup.get(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            lookup.cancel(true);
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while resolving the Voxy QUIC endpoint",
                    interrupted);
        } catch (TimeoutException timeout) {
            lookup.cancel(true);
            throw new IOException("timed out resolving the Voxy QUIC endpoint", timeout);
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof IOException io) throw io;
            throw new IOException("could not resolve the Voxy QUIC endpoint", cause);
        }
    }
}
