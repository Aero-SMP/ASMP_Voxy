package me.cortex.voxy.client.lod;

import me.cortex.voxy.network.BridgePayload;
import me.cortex.voxy.network.TransportPayload;
import net.minecraft.client.Minecraft;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.HandlerThread;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Uses the transport explicitly advertised over the authenticated Minecraft connection. */
final class ClientLodTransport implements Closeable {
    private static final byte[] EOF = new byte[0];
    private static final AtomicReference<ClientLodTransport> BRIDGE = new AtomicReference<>();
    private static final AtomicReference<ArrayBlockingQueue<TransportPayload>> REQUEST = new AtomicReference<>();

    private final Closeable resource;
    private final InputStream input;
    private final OutputStream output;
    private final boolean direct;
    private final String description;

    private ClientLodTransport(Closeable resource, InputStream input, OutputStream output,
                               boolean direct, String description) {
        this.resource = resource;
        this.input = input;
        this.output = output;
        this.direct = direct;
        this.description = description;
    }

    static void register(IEventBus modBus) {
        modBus.addListener(ClientLodTransport::registerPayload);
    }

    private static void registerPayload(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("1").optional().executesOn(HandlerThread.NETWORK);
        registrar
                .playBidirectional(TransportPayload.TYPE, TransportPayload.CODEC,
                        (payload, context) -> receiveTransport(payload))
                .playBidirectional(BridgePayload.TYPE, BridgePayload.CODEC,
                        (payload, context) -> receiveBridge(payload.data()));
        ClientLodDebug.register(registrar);
    }

    static ClientLodTransport open() throws IOException {
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
        ClientLodDebug.transportResponse(requestStarted, transport.mode(), transport.protocolVersion());

        if (transport.protocolVersion() != TransportPayload.PROTOCOL_VERSION) {
            throw new IOException("Voxy protocol mismatch: client " + TransportPayload.PROTOCOL_VERSION
                    + ", server " + transport.protocolVersion());
        }
        if (transport.mode() == TransportPayload.MINECRAFT) {
            if (!transport.host().isEmpty() || transport.port() != 0
                    || !listener.hasChannel(BridgePayload.TYPE)) {
                throw new IOException("Invalid Minecraft Voxy transport advertisement");
            }
            return openBridge();
        }
        if (transport.mode() != TransportPayload.DIRECT || transport.port() == 0) {
            throw new IOException("Invalid Voxy transport advertisement");
        }

        String host = transport.host().trim();
        if (host.isEmpty()) {
            var server = Minecraft.getInstance().getCurrentServer();
            if (server == null) throw new IOException("Direct Voxy transport has no Minecraft server hostname");
            host = hostPart(server.ip);
        } else {
            host = hostPart(host);
        }
        if (host.isEmpty()) throw new IOException("Direct Voxy transport advertised an empty hostname");
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, transport.port()), 5000);
            socket.setSoTimeout(30_000);
            socket.setTcpNoDelay(true);
            socket.setKeepAlive(true);
            socket.setReceiveBufferSize(4 * 1024 * 1024);
            return new ClientLodTransport(socket,
                    new BufferedInputStream(socket.getInputStream(), 256 * 1024),
                    new BufferedOutputStream(socket.getOutputStream(), 256 * 1024), true,
                    host + ':' + transport.port());
        } catch (IOException | RuntimeException exception) {
            try { socket.close(); } catch (IOException ignored) {}
            throw exception;
        }
    }

    private static ClientLodTransport openBridge() throws IOException {
        BridgeInput input = new BridgeInput();
        ClientLodTransport transport = new ClientLodTransport(input, input, new BridgeOutput(),
                false, "Minecraft connection");
        ClientLodTransport previous = BRIDGE.get();
        if (previous != null) previous.close();
        BRIDGE.set(transport);
        return transport;
    }

    InputStream input() { return this.input; }
    OutputStream output() { return this.output; }
    boolean direct() { return this.direct; }
    String description() { return this.description; }

    @Override
    public void close() throws IOException {
        IOException failure = null;
        if (!this.direct && BRIDGE.compareAndSet(this, null)) {
            try { sendBridge(EOF); } catch (IOException exception) { failure = exception; }
        }
        try { this.resource.close(); } catch (IOException exception) {
            if (failure == null) failure = exception; else failure.addSuppressed(exception);
        }
        if (failure != null) throw failure;
    }

    private static void receiveBridge(byte[] data) {
        ClientLodDebug.bridgeIn(data.length);
        ClientLodTransport transport = BRIDGE.get();
        if (transport != null && transport.resource instanceof BridgeInput input) input.offer(data);
    }

    private static void receiveTransport(TransportPayload payload) {
        ArrayBlockingQueue<TransportPayload> response = REQUEST.get();
        if (response != null) response.offer(payload);
    }

    private static void sendBridge(byte[] data) throws IOException {
        var listener = Minecraft.getInstance().getConnection();
        if (listener == null || !listener.hasChannel(BridgePayload.TYPE)) {
            throw new IOException("Minecraft Voxy bridge closed");
        }
        ClientLodDebug.bridgeOut(data.length);
        listener.send(new BridgePayload(data));
    }

    private static String hostPart(String address) {
        String value = address.trim();
        if (value.startsWith("[")) {
            int end = value.indexOf(']');
            return end > 1 ? value.substring(1, end) : value;
        }
        int first = value.indexOf(':');
        return first >= 0 && first == value.lastIndexOf(':') ? value.substring(0, first) : value;
    }

    private static final class BridgeInput extends InputStream {
        private final ArrayBlockingQueue<byte[]> chunks = new ArrayBlockingQueue<>(512);
        private byte[] current;
        private int offset;
        private volatile boolean closed;

        private void offer(byte[] data) {
            if (this.closed) return;
            if (data.length == 0 || !this.chunks.offer(data)) {
                if (data.length != 0) ClientLodDebug.bridgeInputOverflow(this.chunks.size());
                this.closed = true;
                this.chunks.clear();
                this.chunks.offer(EOF);
            }
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : Byte.toUnsignedInt(one[0]);
        }

        @Override
        public int read(byte[] destination, int start, int length) throws IOException {
            if (length == 0) return 0;
            while (this.current == null || this.offset == this.current.length) {
                try {
                    this.current = this.chunks.take();
                    this.offset = 0;
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted waiting for Minecraft bridge", exception);
                }
                if (this.current.length == 0) return -1;
            }
            int copied = Math.min(length, this.current.length - this.offset);
            System.arraycopy(this.current, this.offset, destination, start, copied);
            this.offset += copied;
            return copied;
        }

        @Override
        public void close() {
            offer(EOF);
        }
    }

    private static final class BridgeOutput extends OutputStream {
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
                sendBridge(copy);
                start += chunk;
                length -= chunk;
            }
        }
    }
}
