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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Pinned current-only QUIC connection with reusable, independently progressing section lanes. */
final class RegionalQuicClient implements AutoCloseable {
    interface BatchReceiver {
        void reply(RegionalProtocol.SectionReply reply) throws Exception;
        void complete();
        void failed(Throwable failure);
    }

    private static final String TLS_SERVER_NAME = "voxy.local";
    private static final long STREAM_ERROR_CANCELLED = 0x10;
    private static final long STREAM_RECEIVE_BYTES = 5L * 1024 * 1024;
    private static final int[] LANE_COUNTS = {2, 6};

    private final QuicClientConnection connection;
    private final QuicStream control;
    private final InputStream controlInput;
    private final OutputStream controlOutput;
    private final ExecutorService workers = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("Voxy regional QUIC-", 0).factory());
    private final Object controlHandoffLock = new Object();
    private RegionalProtocol.Control controlHandoff;
    @SuppressWarnings("unchecked")
    private final List<LaneWorker>[] lanes = new List[RegionalProtocol.Lane.values().length];
    private final AtomicInteger[] nextLane = new AtomicInteger[RegionalProtocol.Lane.values().length];
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final AtomicReference<Runnable> activity = new AtomicReference<>(() -> {});
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object controlWriteLock = new Object();
    private final String description;

    static RegionalQuicClient connect(InetAddress[] addresses, int port, String alpn,
                                      byte[] certificateSha256) throws IOException {
        Objects.requireNonNull(addresses, "addresses");
        Objects.requireNonNull(alpn, "alpn");
        Objects.requireNonNull(certificateSha256, "certificate fingerprint");
        if (addresses.length == 0 || port < 1 || port > 0xffff || alpn.isEmpty()
                || certificateSha256.length != 32) {
            throw new IOException("invalid Voxy regional QUIC endpoint");
        }
        Throwable last = null;
        for (InetAddress address : addresses) {
            QuicClientConnection connection = null;
            try {
                ConnectionOwner owner = new ConnectionOwner();
                connection = QuicClientConnection.newBuilder()
                        .host(TLS_SERVER_NAME).proxy(address.getHostAddress()).port(port)
                        .applicationProtocol(alpn).connectTimeout(Duration.ofSeconds(5))
                        .maxIdleTimeout(Duration.ofSeconds(60))
                        .defaultStreamReceiveBufferSize(STREAM_RECEIVE_BYTES)
                        .maxOpenPeerInitiatedBidirectionalStreams(0)
                        .maxOpenPeerInitiatedUnidirectionalStreams(0)
                        .customTrustManager(new FingerprintTrustManager(certificateSha256))
                        .build();
                connection.setPeerInitiatedStreamCallback(RegionalQuicClient::rejectRemoteStream);
                connection.setConnectionListener(owner);
                connection.connect();
                QuicStream control = connection.createStream(true);
                OutputStream output = control.getOutputStream();
                output.write(RegionalProtocol.STREAM_CONTROL);
                output.flush();
                String host = address instanceof Inet6Address
                        ? '[' + address.getHostAddress() + "]:" + port
                        : address.getHostAddress() + ':' + port;
                RegionalQuicClient result = new RegionalQuicClient(connection, control, host);
                owner.publish(result);
                result.start();
                return result;
            } catch (Throwable failure) {
                last = failure;
                if (connection != null) connection.close();
            }
        }
        throw new IOException("could not connect to the Voxy regional endpoint", last);
    }

    private RegionalQuicClient(QuicClientConnection connection, QuicStream control,
                               String description) {
        this.connection = connection;
        this.control = control;
        this.controlInput = control.getInputStream();
        this.controlOutput = control.getOutputStream();
        this.description = description;
        for (RegionalProtocol.Lane lane : RegionalProtocol.Lane.values()) {
            this.nextLane[lane.ordinal()] = new AtomicInteger();
            List<LaneWorker> workers = new ArrayList<>(LANE_COUNTS[lane.ordinal()]);
            for (int index = 0; index < LANE_COUNTS[lane.ordinal()]; index++) {
                workers.add(new LaneWorker(lane));
            }
            this.lanes[lane.ordinal()] = List.copyOf(workers);
        }
    }

    private void start() {
        this.workers.submit(this::readControls);
        for (List<LaneWorker> group : this.lanes) {
            for (LaneWorker lane : group) this.workers.submit(lane::run);
        }
    }

    String description() { return this.description; }
    boolean isOpen() {
        return !this.closed.get() && this.failure.get() == null && this.connection.isConnected();
    }
    Throwable failure() { return this.failure.get(); }
    RegionalProtocol.Control pollControl() {
        synchronized (this.controlHandoffLock) {
            RegionalProtocol.Control result = this.controlHandoff;
            if (result != null) {
                this.controlHandoff = null;
                this.controlHandoffLock.notifyAll();
            }
            return result;
        }
    }

    void setActivityListener(Runnable listener) {
        this.activity.set(Objects.requireNonNull(listener, "listener"));
        synchronized (this.controlHandoffLock) {
            if (this.controlHandoff != null || !isOpen()) signalActivity();
        }
    }

    void hello(String dimension) throws IOException {
        sendControl(RegionalProtocol.hello(dimension));
    }
    void requestRegion(int regionX, int regionZ) throws IOException {
        sendControl(RegionalProtocol.regionRequest(regionX, regionZ));
    }
    void releaseRegion(int regionX, int regionZ) throws IOException {
        sendControl(RegionalProtocol.regionRelease(regionX, regionZ));
    }
    void requestCatalog() throws IOException {
        sendControl(RegionalProtocol.catalogRequest());
    }

    boolean requestSections(RegionalProtocol.Lane priority, long epoch,
                            RegionalProtocol.RegionIndex index, List<Integer> ordinals,
                            BatchReceiver receiver) throws IOException {
        Objects.requireNonNull(priority, "priority");
        LaneTask task = new LaneTask(epoch, Objects.requireNonNull(index, "index"),
                List.copyOf(ordinals),
                Objects.requireNonNull(receiver, "receiver"));
        List<LaneWorker> group = this.lanes[priority.ordinal()];
        int start = Math.floorMod(this.nextLane[priority.ordinal()].getAndIncrement(), group.size());
        for (int offset = 0; offset < group.size(); offset++) {
            if (group.get((start + offset) % group.size()).tryAssign(task)) return true;
        }
        return false;
    }

    private void sendControl(byte[] record) throws IOException {
        if (!isOpen()) throw closedFailure();
        synchronized (this.controlWriteLock) {
            try {
                this.controlOutput.write(record);
                this.controlOutput.flush();
            } catch (IOException failure) {
                fail(failure);
                throw failure;
            }
        }
    }

    private void readControls() {
        try {
            while (!this.closed.get()) {
                RegionalProtocol.Control control = RegionalProtocol.readControl(this.controlInput);
                synchronized (this.controlHandoffLock) {
                    while (this.controlHandoff != null && !this.closed.get()) {
                        this.controlHandoffLock.wait();
                    }
                    if (this.closed.get()) return;
                    this.controlHandoff = control;
                }
                signalActivity();
            }
        } catch (Throwable failure) {
            if (!this.closed.get()) fail(failure);
        }
    }

    private final class LaneWorker {
        private final RegionalProtocol.Lane priority;
        private LaneTask task;
        private boolean active;
        private volatile QuicStream stream;

        private LaneWorker(RegionalProtocol.Lane priority) { this.priority = priority; }

        private synchronized boolean tryAssign(LaneTask offered) {
            if (this.active || closed.get()) return false;
            this.active = true;
            this.task = offered;
            this.notifyAll();
            return true;
        }

        private synchronized LaneTask awaitTask() throws InterruptedException {
            while (this.task == null && !closed.get()) this.wait();
            LaneTask result = this.task;
            this.task = null;
            return result;
        }

        private synchronized void finishTask() {
            this.active = false;
            this.notifyAll();
            signalActivity();
        }

        private void run() {
            try {
                this.stream = connection.createStream(true);
                OutputStream output = this.stream.getOutputStream();
                InputStream input = this.stream.getInputStream();
                output.write(RegionalProtocol.STREAM_SECTION_LANE);
                output.write(this.priority.id);
                output.flush();
                while (!closed.get()) {
                    LaneTask task = this.awaitTask();
                    if (task == null) return;
                    try {
                        output.write(RegionalProtocol.sectionRequest(
                                task.epoch, task.index, task.ordinals));
                        output.flush();
                        int received = 0;
                        while (received < task.ordinals.size()) {
                            received += RegionalProtocol.readReplyBatch(input, task.epoch,
                                    task.index, task.ordinals, received, task.receiver::reply);
                        }
                        task.receiver.complete();
                        this.finishTask();
                    } catch (Throwable taskFailure) {
                        task.receiver.failed(taskFailure);
                        fail(taskFailure);
                        return;
                    }
                }
            } catch (Throwable failure) {
                if (!closed.get()) fail(failure);
            }
        }

        private void stop() {
            QuicStream current = this.stream;
            if (current != null) rejectRemoteStream(current);
            LaneTask pending;
            synchronized (this) {
                pending = this.task;
                this.task = null;
                this.active = false;
                this.notifyAll();
            }
            if (pending != null) pending.receiver.failed(closedFailure());
        }
    }

    private record LaneTask(long epoch, RegionalProtocol.RegionIndex index,
                            List<Integer> ordinals, BatchReceiver receiver) {}

    private void fail(Throwable cause) {
        Throwable actual = cause == null ? new IOException("regional QUIC connection failed") : cause;
        if (this.failure.compareAndSet(null, actual)) {
            signalActivity();
            close();
        }
    }

    private void signalActivity() {
        try { this.activity.get().run(); } catch (RuntimeException ignored) {}
    }

    private IOException closedFailure() {
        return new IOException("Voxy regional QUIC connection is closed", this.failure.get());
    }

    @Override
    public void close() {
        if (!this.closed.compareAndSet(false, true)) return;
        for (List<LaneWorker> group : this.lanes) for (LaneWorker lane : group) lane.stop();
        synchronized (this.controlHandoffLock) {
            this.controlHandoffLock.notifyAll();
        }
        rejectRemoteStream(this.control);
        this.connection.close();
        this.workers.shutdownNow();
        signalActivity();
    }

    private static void rejectRemoteStream(QuicStream stream) {
        stream.abortReading(STREAM_ERROR_CANCELLED);
        stream.resetStream(STREAM_ERROR_CANCELLED);
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
