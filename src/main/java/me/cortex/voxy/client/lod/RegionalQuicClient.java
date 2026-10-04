package me.cortex.voxy.client.lod;

import tech.kwik.core.ConnectionListener;
import tech.kwik.core.ConnectionTerminatedEvent;
import tech.kwik.core.QuicClientConnection;
import tech.kwik.core.QuicStream;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Pinned current-only transport. Reader ownership and QUIC flow control carry backpressure. */
final class RegionalQuicClient implements AutoCloseable {
    interface RecordReceiver {
        void record(RegionalProtocol.SectionReply reply, boolean background, RegionalSectionCodec.BoundCatalog catalog) throws Exception;
        RegionalSectionCodec.BoundCatalog catalog(RegionalProtocol.CatalogMessage catalog, boolean background) throws Exception;
    }
    private static final String TLS_SERVER_NAME = "voxy.local";
    private static final long STREAM_ERROR_CANCELLED = 0x10, STREAM_RECEIVE_BYTES = 5L * 1024 * 1024;
    private static final int[] LANE_COUNTS = {2, 6};
    private final QuicClientConnection connection;
    private final QuicStream control;
    private final InputStream controlInput;
    private final ControlWriter controlWriter;
    private final ExecutorService workers = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("Voxy regional QUIC-", 0).factory());
    private final Object controlHandoffLock = new Object();
    private RegionalProtocol.Control controlHandoff;
    private final List<LaneWorker> lanes = new ArrayList<>();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final AtomicReference<Runnable> activity = new AtomicReference<>(() -> {});
    private final AtomicBoolean closed = new AtomicBoolean(), listening = new AtomicBoolean();
    private final String description, alpn;
    private final InetAddress address;
    private final int port;
    private final byte[] certificateSha256;
    private final boolean background;
    private final ConcurrentHashMap<RegionalProtocol.Hash32, CompletableFuture<RegionalSectionCodec.BoundCatalog>> catalogues;
    private volatile RecordReceiver receiver;

    static RegionalQuicClient connect(InetAddress[] addresses, int port, String alpn,
                                      byte[] certificateSha256) throws IOException {
        Throwable last = null;
        for (InetAddress address : Objects.requireNonNull(addresses)) {
            try { return connectEndpoint(address, port, alpn, certificateSha256, null, null, null); }
            catch (IOException failure) { last = failure; }
        }
        throw new IOException("could not connect to the Voxy regional endpoint", last);
    }
    static RegionalQuicClient connectBackground(RegionalQuicClient primary,
                                                byte[] token, RegionalSectionCodec.BoundCatalog held,
                                                RecordReceiver receiver) throws IOException {
        if (token == null || token.length != 32) throw new IOException("invalid background token");
        var result = connectEndpoint(primary.address, primary.port, primary.alpn, primary.certificateSha256,
                token, primary.catalogues, held);
        result.listen(receiver); return result;
    }
    private static RegionalQuicClient connectEndpoint(InetAddress address, int port, String alpn,
                                                       byte[] pin, byte[] token,
                                                       ConcurrentHashMap<RegionalProtocol.Hash32, CompletableFuture<RegionalSectionCodec.BoundCatalog>> catalogues,
                                                       RegionalSectionCodec.BoundCatalog held) throws IOException {
        if (address == null || port < 1 || port > 65535 || alpn == null || alpn.isEmpty() || pin == null || pin.length != 32)
            throw new IOException("invalid Voxy QUIC endpoint");
        QuicClientConnection connection = null;
        try {
            var owner = new ConnectionOwner();
            connection = QuicClientConnection.newBuilder()
                    .socketFactory(ignored -> token == null ? ClientLodDebug.quicSocket()
                            : new BackgroundDatagramSocket(ClientLodDebug.quicSocket(), token))
                    .host(TLS_SERVER_NAME).proxy(address.getHostAddress()).port(port)
                    .applicationProtocol(alpn).connectTimeout(Duration.ofSeconds(5))
                    .maxIdleTimeout(Duration.ofSeconds(60)).defaultStreamReceiveBufferSize(STREAM_RECEIVE_BYTES)
                    .maxOpenPeerInitiatedBidirectionalStreams(0).maxOpenPeerInitiatedUnidirectionalStreams(0)
                    .customTrustManager(new FingerprintTrustManager(pin)).build();
            connection.setPeerInitiatedStreamCallback(RegionalQuicClient::rejectRemoteStream);
            connection.setConnectionListener(owner); connection.connect();
            var stream = connection.createStream(true); var output = stream.getOutputStream();
            output.write(token == null ? RegionalProtocol.STREAM_CONTROL : RegionalProtocol.STREAM_BACKGROUND);
            if (token != null) { output.write(token); output.write(held == null ? RegionalProtocol.Hash32.ZERO.bytes() : held.fingerprint().bytes()); }
            output.flush();
            String host = address instanceof Inet6Address ? '[' + address.getHostAddress() + "]:" + port
                    : address.getHostAddress() + ':' + port;
            var result = new RegionalQuicClient(connection, stream, host, address, port, alpn, pin, token != null, catalogues);
            if (held != null) result.remember(held);
            owner.publish(result);
            if (token == null) { result.workers.submit(result::readControls); result.workers.submit(result.controlWriter); }
            return result;
        } catch (Throwable failure) {
            if (connection != null) connection.close();
            throw new IOException("could not connect to the Voxy endpoint", failure);
        }
    }
    private RegionalQuicClient(QuicClientConnection connection, QuicStream control, String description,
                                InetAddress address, int port, String alpn, byte[] pin, boolean background,
                                ConcurrentHashMap<RegionalProtocol.Hash32, CompletableFuture<RegionalSectionCodec.BoundCatalog>> catalogues) {
        this.connection = connection; this.control = control; this.controlInput = control.getInputStream();
        this.description = description; this.address = address; this.port = port; this.alpn = alpn;
        this.certificateSha256 = pin.clone(); this.background = background;
        this.catalogues = catalogues == null ? new ConcurrentHashMap<>() : catalogues;
        this.controlWriter = background ? null : new ControlWriter(control.getOutputStream(), this::signalActivity, this::fail);
    }
    void listen(RecordReceiver receiver) {
        Objects.requireNonNull(receiver);
        if (!this.listening.compareAndSet(false, true)) throw new IllegalStateException("record receiver already installed");
        this.receiver = receiver;
        if (this.background) { this.workers.submit(() -> readRecords(this.controlInput, receiver, null)); return; }
        for (var priority : RegionalProtocol.Lane.values()) for (int i = 0; i < LANE_COUNTS[priority.ordinal()]; i++) {
            var lane = new LaneWorker(priority, receiver); this.lanes.add(lane); this.workers.submit(lane::run);
        }
    }
    String description() { return this.description; }
    record LaneSnapshot(int idle, int active, int activeSections, long bodyBytes) {}
    LaneSnapshot laneSnapshot() {
        int active = 0; long bytes = 0;
        for (var lane : this.lanes) { if (lane.active) active++; bytes += lane.bodyBytes; }
        return new LaneSnapshot(this.lanes.size() - active, active, active, bytes);
    }
    boolean isOpen() { return !this.closed.get() && this.failure.get() == null && this.connection.isConnected(); }
    Throwable failure() { return this.failure.get(); }
    RegionalProtocol.Control pollControl() {
        synchronized (this.controlHandoffLock) {
            var result = this.controlHandoff;
            if (result != null) { this.controlHandoff = null; this.controlHandoffLock.notifyAll(); }
            return result;
        }
    }
    void setActivityListener(Runnable listener) {
        this.activity.set(Objects.requireNonNull(listener));
        synchronized (this.controlHandoffLock) { if (this.controlHandoff != null || !isOpen()) signalActivity(); }
    }
    boolean open(String dimension, RegionalProtocol.Hash32 expectedWorld, RegionalSectionCodec.BoundCatalog held, long intervalMillis,
                 long bandwidthKbps, List<RegionalProtocol.Desire> desires) throws IOException {
        if (held != null) remember(held);
        return sendControl(RegionalProtocol.open(dimension, expectedWorld, held == null ? null : held.fingerprint(), intervalMillis, bandwidthKbps, desires));
    }
    void remember(RegionalSectionCodec.BoundCatalog catalog) {
        this.catalogues.computeIfAbsent(catalog.fingerprint(), ignored -> new CompletableFuture<>()).complete(catalog);
    }
    boolean desire(List<RegionalProtocol.Desire> desires) throws IOException { return sendControl(RegionalProtocol.desire(desires)); }
    boolean drop(List<Long> keys) throws IOException { return sendControl(RegionalProtocol.drop(keys)); }
    boolean settings(long intervalMillis, long bandwidthKbps) throws IOException {
        return sendControl(RegionalProtocol.settings(intervalMillis, bandwidthKbps));
    }
    private boolean sendControl(byte[] record) throws IOException {
        if (!isOpen() || this.controlWriter == null) throw closedFailure();
        return this.controlWriter.offer(record);
    }
    /** One writer-owned record, no backlog. The owner must stay free to drain responses even
     * when the peer cannot read another request until its response has been consumed. */
    static final class ControlWriter implements Runnable, AutoCloseable {
        private final OutputStream output;
        private final Runnable activity;
        private final java.util.function.Consumer<Throwable> failure;
        private byte[] record;
        private boolean closed;

        ControlWriter(OutputStream output, Runnable activity,
                      java.util.function.Consumer<Throwable> failure) {
            this.output = output;
            this.activity = activity;
            this.failure = failure;
        }

        synchronized boolean offer(byte[] record) {
            if (this.closed || this.record != null) return false;
            this.record = Objects.requireNonNull(record);
            this.notifyAll();
            return true;
        }

        @Override public void run() {
            try {
                while (true) {
                    byte[] writing;
                    synchronized (this) {
                        while (!this.closed && this.record == null) this.wait();
                        if (this.closed) return;
                        writing = this.record;
                    }
                    this.output.write(writing);
                    this.output.flush();
                    synchronized (this) { this.record = null; }
                    this.activity.run();
                }
            } catch (Throwable failure) {
                boolean report;
                synchronized (this) { report = !this.closed; }
                this.close();
                if (report) this.failure.accept(failure);
            }
        }

        @Override public synchronized void close() {
            this.closed = true;
            this.record = null;
            this.notifyAll();
        }
    }


    private void readControls() {
        try {
            while (!this.closed.get()) {
                var incoming = RegionalProtocol.readControl(this.controlInput);
                if (incoming instanceof RegionalProtocol.CatalogMessage catalog) {
                    remember(this.receiver.catalog(catalog, false));
                    continue;
                }
                synchronized (this.controlHandoffLock) {
                    while (this.controlHandoff != null && !this.closed.get()) this.controlHandoffLock.wait();
                    if (this.closed.get()) return;
                    this.controlHandoff = incoming;
                }
                signalActivity();
            }
        } catch (Throwable failure) { if (!this.closed.get()) fail(failure); }
    }
    private void readRecords(InputStream input, RecordReceiver receiver, LaneWorker lane) {
        try {
            while (!this.closed.get()) {
                var frame = RegionalProtocol.readControl(input);
                if (lane != null) { lane.active = true; lane.bodyBytes = frame instanceof RegionalProtocol.SectionReply reply ? reply.compressed().length : 0; }
                try {
                    switch (frame) {
                        case RegionalProtocol.SectionReply reply -> {
                            RegionalSectionCodec.BoundCatalog binding = null;
                            if (reply.content().kind() == LocalSection.DATA)
                                binding = this.catalogues.computeIfAbsent(reply.content().catalog(), ignored -> new CompletableFuture<>()).get();
                            receiver.record(reply, this.background, binding);
                        }
                        case RegionalProtocol.CatalogMessage catalog -> {
                            if (!this.background) throw new IOException("catalogue on a foreground terrain lane");
                            remember(receiver.catalog(catalog, true));
                        }
                        case RegionalProtocol.ServerError error -> throw new IOException("Voxy server error " + error.code() + ": " + error.message());
                        case RegionalProtocol.ServerShutdown shutdown -> throw new IOException(shutdown.message());
                        default -> throw new IOException("unexpected terrain lane frame");
                    }
                } finally { if (lane != null) { lane.active = false; lane.bodyBytes = 0; } }
            }
        } catch (Throwable failure) { if (!this.closed.get()) fail(failure); }
    }
    private final class LaneWorker {
        final RegionalProtocol.Lane priority; final RecordReceiver receiver;
        volatile QuicStream stream; volatile boolean active; volatile long bodyBytes;
        LaneWorker(RegionalProtocol.Lane priority, RecordReceiver receiver) { this.priority = priority; this.receiver = receiver; }
        void run() {
            try {
                this.stream = connection.createStream(true);
                var output = this.stream.getOutputStream();
                output.write(RegionalProtocol.STREAM_SECTION_LANE); output.write(this.priority.id); output.flush();
                readRecords(this.stream.getInputStream(), this.receiver, this);
            } catch (Throwable failure) { if (!closed.get()) fail(failure); }
        }
        void stop() { if (this.stream != null) rejectRemoteStream(this.stream); }
    }
    private void fail(Throwable cause) {
        if (this.failure.compareAndSet(null, cause == null ? new IOException("regional QUIC connection failed") : cause)) {
            signalActivity(); close();
        }
    }
    private void signalActivity() { try { this.activity.get().run(); } catch (RuntimeException ignored) {} }
    private IOException closedFailure() { return new IOException("Voxy regional QUIC connection is closed", this.failure.get()); }
    @Override public void close() {
        if (!this.closed.compareAndSet(false, true)) return;
        if (this.controlWriter != null) this.controlWriter.close();
        for (var lane : this.lanes) lane.stop();
        synchronized (this.controlHandoffLock) { this.controlHandoffLock.notifyAll(); }
        if (!this.background) this.catalogues.values().forEach(waiter -> waiter.completeExceptionally(closedFailure()));
        rejectRemoteStream(this.control); this.connection.close(); this.workers.shutdownNow(); signalActivity();
    }
    private static void rejectRemoteStream(QuicStream stream) {
        stream.abortReading(STREAM_ERROR_CANCELLED); stream.resetStream(STREAM_ERROR_CANCELLED);
    }
    private static final class ConnectionOwner implements ConnectionListener {
        private RegionalQuicClient owner;
        private ConnectionTerminatedEvent early;
        private synchronized void publish(RegionalQuicClient owner) {
            this.owner = owner;
            if (this.early != null) owner.connectionClosed(this.early);
        }
        @Override public synchronized void disconnected(ConnectionTerminatedEvent event) {
            if (this.owner == null) this.early = event; else this.owner.connectionClosed(event);
        }
    }

    private void connectionClosed(ConnectionTerminatedEvent event) {
        if (!this.closed.get()) fail(new IOException("Voxy regional QUIC connection ended: "
                + event.closeReason() + ": " + event.errorDescription()));
    }

    private static final class FingerprintTrustManager implements X509TrustManager {
        private final byte[] expected;
        private FingerprintTrustManager(byte[] expected) { this.expected = expected.clone(); }
        private void verify(X509Certificate[] chain) throws CertificateException {
            if (chain == null || chain.length == 0) {
                throw new CertificateException("Voxy QUIC server supplied no certificate");
            }
            try {
                byte[] actual = MessageDigest.getInstance("SHA-256").digest(chain[0].getEncoded());
                if (!MessageDigest.isEqual(this.expected, actual)) {
                    throw new CertificateException("Voxy QUIC certificate fingerprint mismatch");
                }
            } catch (java.security.GeneralSecurityException failure) {
                if (failure instanceof CertificateException certificate) throw certificate;
                throw new CertificateException("could not verify Voxy QUIC certificate", failure);
            }
        }
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType)
                throws CertificateException { throw new CertificateException("unsupported"); }
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType)
                throws CertificateException { verify(chain); }
        @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    }
}
