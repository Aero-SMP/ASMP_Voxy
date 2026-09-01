package me.cortex.voxy.client.lod;

import me.cortex.voxy.network.BridgePayload;
import me.cortex.voxy.network.TransportPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.HandlerThread;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Opens the transport explicitly advertised by the Minecraft server. */
final class ClientLodTransport implements Closeable {
    private static final int BRIDGE_INPUT_CHUNKS = 544;
    // Reserve every inbound queue slot up front so already-credited bridge bytes can never turn
    // a transient budget race into a disconnect. Outbound accounting covers the 256-KiB buffered
    // stream and all chunk copies that one flush can retain in Minecraft's send queue.
    private static final long BRIDGE_FIXED_BYTES = Math.addExact(
            Math.multiplyExact((long) BRIDGE_INPUT_CHUNKS,
                    BridgePayload.MAX_CHUNK + 64L), 512L << 10);
    private static final byte[] EOF = new byte[0];
    private static final AtomicReference<ClientLodTransport> BRIDGE = new AtomicReference<>();
    private static final AtomicReference<ArrayBlockingQueue<TransportPayload>> REQUEST = new AtomicReference<>();
    private static final AtomicLong NEXT_BRIDGE_STREAM = new AtomicLong(
            Math.max(1, System.nanoTime()));

    private final Closeable resource;
    private final InputStream input;
    private final OutputStream output;
    private final boolean direct;
    private final String description;
    private final ClientPacketListener bridgeListener;
    private final long bridgeStreamId;
    private final MemoryBudget.Reservation fixedBuffers;
    private final AtomicBoolean closed = new AtomicBoolean();

    private ClientLodTransport(Closeable resource, InputStream input, OutputStream output,
                               boolean direct, String description,
                               ClientPacketListener bridgeListener, long bridgeStreamId,
                               MemoryBudget.Reservation fixedBuffers) {
        this.resource = resource;
        this.input = input;
        this.output = output;
        this.direct = direct;
        this.description = description;
        this.bridgeListener = bridgeListener;
        this.bridgeStreamId = bridgeStreamId;
        this.fixedBuffers = fixedBuffers;
    }

    static void register(IEventBus modBus) {
        modBus.addListener(ClientLodTransport::registerPayload);
    }

    private static void registerPayload(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar(TransportPayload.CHANNEL)
                .optional().executesOn(HandlerThread.NETWORK);
        registrar
                .playBidirectional(TransportPayload.TYPE, TransportPayload.CODEC,
                        (payload, context) -> receiveTransport(payload))
                .playBidirectional(BridgePayload.TYPE, BridgePayload.CODEC,
                        (payload, context) -> receiveBridge(payload));
        ClientLodDebug.register(registrar);
    }

    static ClientLodTransport open(MemoryBudget memory) throws IOException {
        java.util.Objects.requireNonNull(memory, "memory");
        var listener = Minecraft.getInstance().getConnection();
        if (listener == null || !listener.hasChannel(TransportPayload.TYPE)) {
            throw new IOException("Minecraft server does not advertise Voxy transport");
        }

        ArrayBlockingQueue<TransportPayload> response = new ArrayBlockingQueue<>(1);
        if (!REQUEST.compareAndSet(null, response)) {
            throw new IOException("A Voxy transport request is already pending");
        }
        TransportPayload transport;
        long requestStarted = ClientLodDebug.timer();
        try {
            listener.send(TransportPayload.request());
            transport = response.poll(5, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted waiting for Voxy transport information", exception);
        } finally {
            REQUEST.compareAndSet(response, null);
        }
        if (transport == null) throw new IOException("Minecraft server did not provide Voxy transport information");
        ClientLodDebug.transportResponse(requestStarted, transport.mode());
        if (transport.mode() == TransportPayload.MINECRAFT) {
            if (!transport.host().isEmpty() || transport.port() != 0
                    || !listener.hasChannel(BridgePayload.TYPE)) {
                throw new IOException("Invalid Minecraft Voxy transport advertisement");
            }
            return openBridge(listener, memory);
        }
        if (transport.mode() != TransportPayload.DIRECT || transport.port() == 0) {
            throw new IOException("Invalid Voxy transport advertisement");
        }

        InetAddress[] addresses = transport.host().isEmpty()
                ? new InetAddress[]{remoteAddress(listener.getConnection().getRemoteAddress())}
                : InetAddress.getAllByName(transport.host());
        return openDirect(addresses, transport.port(), memory);
    }

    private static ClientLodTransport openDirect(InetAddress[] addresses, int port,
                                                  MemoryBudget memory)
            throws IOException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        IOException failure = null;
        for (InetAddress address : addresses) {
            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0) break;
            int timeoutMillis = (int) Math.max(1,
                    TimeUnit.NANOSECONDS.toMillis(remainingNanos));
            Socket socket = new Socket();
            MemoryBudget.Reservation buffers = null;
            try {
                socket.connect(new InetSocketAddress(address, port), timeoutMillis);
                socket.setSoTimeout(30_000);
                socket.setTcpNoDelay(true);
                socket.setKeepAlive(true);
                socket.setReceiveBufferSize(4 * 1024 * 1024);
                buffers = memory.tryReserve(
                        MemoryBudget.Allocation.of(MemoryBudget.Pool.IN_FLIGHT,
                                512L << 10)).orElseThrow(() -> new IOException(
                        "Virtual Surface memory budget cannot admit direct transport buffers"));
                return new ClientLodTransport(socket,
                        new BufferedInputStream(socket.getInputStream(), 256 * 1024),
                        new BufferedOutputStream(socket.getOutputStream(), 256 * 1024), true,
                        endpointDescription(address, port), null, 0, buffers);
            } catch (IOException exception) {
                if (buffers != null) buffers.close();
                failure = exception;
                try { socket.close(); } catch (IOException closeFailure) {
                    exception.addSuppressed(closeFailure);
                }
            } catch (RuntimeException exception) {
                if (buffers != null) buffers.close();
                try { socket.close(); } catch (IOException closeFailure) {
                    exception.addSuppressed(closeFailure);
                }
                throw exception;
            }
        }
        if (failure != null) throw failure;
        throw new IOException("Direct Voxy endpoint connection timed out");
    }

    private static synchronized ClientLodTransport openBridge(ClientPacketListener listener,
                                                               MemoryBudget memory)
            throws IOException {
        ClientLodTransport previous = BRIDGE.getAndSet(null);
        if (previous != null) {
            try {
                previous.close();
            } catch (IOException ignored) {
                // The new Minecraft connection is authoritative; a stale stream can no longer
                // be notified, but close() has still released its local input state.
            }
        }
        long streamId = nextBridgeStreamId();
        MemoryBudget.Reservation outputMemory = memory.tryReserve(
                MemoryBudget.Allocation.of(MemoryBudget.Pool.IN_FLIGHT,
                        BRIDGE_FIXED_BYTES))
                .orElseThrow(() -> new IOException(
                        "Virtual Surface memory budget cannot admit bridge output buffer"));
        BridgeInput input = new BridgeInput();
        ClientLodTransport transport;
        try {
            transport = new ClientLodTransport(input, input,
                    new BufferedOutputStream(new BridgeOutput(listener, streamId), 256 * 1024),
                    false, "Minecraft relay", listener, streamId, outputMemory);
        } catch (RuntimeException failure) {
            outputMemory.close();
            throw failure;
        }
        BRIDGE.set(transport);
        try {
            sendBridge(listener, BridgePayload.open(streamId));
            return transport;
        } catch (IOException failure) {
            BRIDGE.compareAndSet(transport, null);
            input.close();
            outputMemory.close();
            throw failure;
        }
    }

    InputStream input() { return this.input; }
    OutputStream output() { return this.output; }
    boolean direct() { return this.direct; }
    String description() { return this.description; }

    @Override
    public void close() throws IOException {
        if (!this.closed.compareAndSet(false, true)) return;
        IOException failure = null;
        if (!this.direct) {
            BRIDGE.compareAndSet(this, null);
            try {
                sendBridge(this.bridgeListener, BridgePayload.close(this.bridgeStreamId));
            } catch (IOException exception) {
                failure = exception;
            }
        }
        try { this.resource.close(); } catch (IOException exception) {
            if (failure == null) failure = exception; else failure.addSuppressed(exception);
        }
        if (this.fixedBuffers != null) this.fixedBuffers.close();
        if (failure != null) throw failure;
    }

    private static void receiveBridge(BridgePayload payload) {
        ClientLodDebug.bridgeIn(payload.data().length);
        ClientLodTransport transport = BRIDGE.get();
        if (transport == null || transport.closed.get()
                || transport.bridgeStreamId != payload.streamId()
                || !(transport.resource instanceof BridgeInput input)) return;
        if (payload.action() == BridgePayload.DATA) input.offer(payload.data());
        else if (payload.action() == BridgePayload.CLOSE) input.close();
        else input.fail();
    }

    private static void receiveTransport(TransportPayload payload) {
        ArrayBlockingQueue<TransportPayload> response = REQUEST.get();
        if (response != null) response.offer(payload);
    }

    private static void sendBridge(ClientPacketListener listener, BridgePayload payload)
            throws IOException {
        if (listener == null || !listener.hasChannel(BridgePayload.TYPE)) {
            throw new IOException("Minecraft Voxy bridge closed");
        }
        var channel = listener.getConnection().channel();
        if (!channel.isOpen() || !channel.isActive()) {
            throw new IOException("Minecraft Voxy bridge closed");
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!channel.isWritable()) {
            if (!channel.isOpen() || !channel.isActive()
                    || System.nanoTime() - deadline >= 0) {
                throw new IOException("Minecraft Voxy bridge backpressure timed out");
            }
            try {
                Thread.sleep(1);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted waiting for Minecraft Voxy bridge", exception);
            }
        }
        ClientLodDebug.bridgeOut(payload.data().length);
        listener.send(payload);
    }

    private static InetAddress remoteAddress(SocketAddress remote) throws IOException {
        if (!(remote instanceof InetSocketAddress endpoint) || endpoint.getAddress() == null) {
            throw new IOException("Minecraft peer has no numeric address for direct Voxy transport");
        }
        return endpoint.getAddress();
    }

    private static String endpointDescription(InetAddress address, int port) {
        String host = address.getHostAddress();
        return address instanceof Inet6Address ? '[' + host + "]:" + port : host + ':' + port;
    }

    private static long nextBridgeStreamId() {
        while (true) {
            long value = NEXT_BRIDGE_STREAM.getAndIncrement();
            if (value != 0) return value;
        }
    }

    private static final class BridgeInput extends InputStream {
        // One maximum data frame is slightly larger than 512 bridge chunks after its outer
        // header. Keep one complete bounded frame plus control slack.
        private static final byte[] END = EOF;
        private final ArrayBlockingQueue<byte[]> chunks =
                new ArrayBlockingQueue<>(BRIDGE_INPUT_CHUNKS);
        private byte[] current;
        private int offset;
        private final AtomicBoolean closed = new AtomicBoolean();

        private synchronized void offer(byte[] data) {
            if (this.closed.get() || data.length == 0) return;
            if (this.closed.get() || !this.chunks.offer(data)) {
                if (!this.closed.get()) ClientLodDebug.bridgeInputOverflow(this.chunks.size());
                fail();
            }
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : Byte.toUnsignedInt(one[0]);
        }

        @Override
        public int read(byte[] destination, int start, int length) throws IOException {
            java.util.Objects.checkFromIndexSize(start, length, destination.length);
            if (length == 0) return 0;
            while (true) {
                synchronized (this) {
                    if (this.current != null) {
                        int remaining = this.current.length - this.offset;
                        if (remaining > 0) {
                            int copied = Math.min(length, remaining);
                            System.arraycopy(this.current, this.offset,
                                    destination, start, copied);
                            this.offset += copied;
                            if (this.offset == this.current.length) {
                                this.current = null;
                                this.offset = 0;
                            }
                            return copied;
                        }
                        this.current = null;
                        this.offset = 0;
                    }
                    if (this.closed.get() && this.chunks.isEmpty()) return -1;
                }
                byte[] next;
                try {
                    next = this.chunks.take();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted waiting for Minecraft bridge", exception);
                }
                if (next == END) return -1;
                synchronized (this) {
                    if (this.closed.get()) {
                        return -1;
                    }
                    if (this.current != null) {
                        throw new IOException("Minecraft bridge input has multiple readers");
                    }
                    this.current = next;
                    this.offset = 0;
                }
            }
        }

        @Override
        public void close() {
            fail();
        }

        private void fail() {
            if (!this.closed.compareAndSet(false, true)) return;
            synchronized (this) {
                this.current = null;
                this.offset = 0;
            }
            this.chunks.clear();
            this.chunks.offer(END);
        }
    }

    private static final class BridgeOutput extends OutputStream {
        private final ClientPacketListener listener;
        private final long streamId;

        private BridgeOutput(ClientPacketListener listener, long streamId) {
            this.listener = listener;
            this.streamId = streamId;
        }

        @Override
        public void write(int value) throws IOException {
            write(new byte[]{(byte) value});
        }

        @Override
        public void write(byte[] source, int start, int length) throws IOException {
            while (length > 0) {
                int chunk = Math.min(length, BridgePayload.MAX_CHUNK);
                byte[] copy = new byte[chunk];
                System.arraycopy(source, start, copy, 0, chunk);
                sendBridge(this.listener, BridgePayload.data(this.streamId, copy));
                start += chunk;
                length -= chunk;
            }
        }
    }
}
