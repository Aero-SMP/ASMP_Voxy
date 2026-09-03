package me.cortex.voxy.client.lod;

import me.cortex.voxy.client.lod.WireMessage.EncodedObject;
import me.cortex.voxy.client.lod.WireMessage.Hash256;
import me.cortex.voxy.client.lod.WireMessage.ObjectKind;
import me.cortex.voxy.client.lod.WireMessage.RootAnnounce;
import me.cortex.voxy.client.lod.WireMessage.RootToken;
import org.lwjgl.system.MemoryUtil;
import tech.kwik.core.ConnectionListener;
import tech.kwik.core.ConnectionTerminatedEvent;
import tech.kwik.core.QuicClientConnection;
import tech.kwik.core.QuicStream;

import javax.net.ssl.X509TrustManager;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.CRC32C;

/** Pure-Java QUIC client, isolated from Minecraft's parent-loaded Netty classes. */
final class KwikQuicClient implements QuicClient {
    private static final int CONTROL_ROLE = 0;
    private static final int OBJECT_ROLE = 1;
    private static final String TLS_SERVER_NAME = "voxy.local";
    private static final int C_HELLO = 0x01;
    private static final int C_ROOT_READY = 0x02;
    private static final int C_CAMERA_DOMAIN = 0x03;
    private static final int S_HELLO = 0x81;
    private static final int S_ROOT_ANNOUNCE = 0x82;
    private static final int S_CAMERA_DOMAIN = 0x83;
    private static final int S_SHUTDOWN = 0xfe;
    private static final int S_ERROR = 0xff;
    private static final int CONTROL_PAYLOAD_LIMIT = 4096;
    private static final long STREAM_ERROR_CANCELLED = 0x10;
    private static final long MAX_RESPONSE_COMPRESSED_BYTES = 16L << 20;
    private static final long MAX_RESPONSE_CANONICAL_BYTES = 64L << 20;
    private static final long STREAM_RECEIVE_BYTES = MAX_RESPONSE_COMPRESSED_BYTES + 64 * 1024;
    private static final int MAX_PENDING_BODIES_PER_STREAM = 4;
    private static final DirectBodyPool BODY_POOL = new DirectBodyPool();

    private final QuicClientConnection connection;
    private final QuicStream control;
    private final InputStream controlInput;
    private final OutputStream controlOutput;
    private final ExecutorService workers = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("Voxy QUIC I/O-", 0).factory());
    private final ArrayBlockingQueue<ControlMessage> controls = new ArrayBlockingQueue<>(64);
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final AtomicReference<Runnable> activityListener =
            new AtomicReference<>(() -> {});
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object controlWriteLock = new Object();
    private final String description;

    static QuicClient connect(InetAddress[] addresses, int port, String alpn,
                              byte[] certificateSha256) throws IOException {
        Objects.requireNonNull(addresses, "addresses");
        Objects.requireNonNull(alpn, "alpn");
        Objects.requireNonNull(certificateSha256, "certificateSha256");
        if (addresses.length == 0 || port < 1 || port > 0xffff
                || alpn.isEmpty() || certificateSha256.length != 32) {
            throw new IOException("invalid Voxy QUIC endpoint");
        }

        Throwable last = null;
        for (InetAddress address : addresses) {
            QuicClientConnection connection = null;
            try {
                ConnectionOwner owner = new ConnectionOwner();
                connection = QuicClientConnection.newBuilder()
                        // The persistent Rust identity is minted for voxy.local. Kwik's proxy
                        // address separates that TLS name from the advertised numeric peer.
                        .host(TLS_SERVER_NAME)
                        .proxy(address.getHostAddress())
                        .port(port)
                        .applicationProtocol(alpn)
                        .connectTimeout(Duration.ofSeconds(5))
                        .maxIdleTimeout(Duration.ofSeconds(60))
                        .defaultStreamReceiveBufferSize(STREAM_RECEIVE_BYTES)
                        .maxOpenPeerInitiatedBidirectionalStreams(0)
                        .maxOpenPeerInitiatedUnidirectionalStreams(0)
                        .customTrustManager(new FingerprintTrustManager(certificateSha256))
                        .build();
                connection.setPeerInitiatedStreamCallback(KwikQuicClient::rejectRemoteStream);
                connection.setConnectionListener(owner);
                connection.connect();

                QuicStream control = connection.createStream(true);
                OutputStream output = control.getOutputStream();
                output.write(CONTROL_ROLE);
                output.flush();
                String host = address instanceof Inet6Address
                        ? '[' + address.getHostAddress() + "]:" + port
                        : address.getHostAddress() + ':' + port;
                KwikQuicClient result = new KwikQuicClient(connection, control, host);
                owner.publish(result);
                result.workers.submit(result::readControls);
                return result;
            } catch (Throwable failure) {
                last = failure;
                if (connection != null) connection.close();
            }
        }
        throw new IOException("could not connect to the Voxy QUIC endpoint", last);
    }

    private KwikQuicClient(QuicClientConnection connection, QuicStream control,
                           String description) {
        this.connection = connection;
        this.control = control;
        this.controlInput = control.getInputStream();
        this.controlOutput = control.getOutputStream();
        this.description = description;
    }

    @Override
    public ControlMessage pollControl() {
        return this.controls.poll();
    }

    @Override
    public boolean isOpen() {
        return !this.closed.get() && this.failure.get() == null && this.connection.isConnected();
    }

    @Override
    public boolean isTerminated() {
        return this.workers.isTerminated();
    }

    @Override
    public Throwable failure() {
        return this.failure.get();
    }

    @Override
    public String description() {
        return this.description;
    }

    @Override
    public void setActivityListener(Runnable listener) {
        this.activityListener.set(Objects.requireNonNull(listener, "listener"));
        if (!this.controls.isEmpty() || !isOpen()) signalActivity();
    }

    @Override
    public void sendHello(String dimension) throws IOException {
        Payload output = new Payload();
        output.string(dimension);
        sendControl(C_HELLO, output.bytes());
    }

    @Override
    public void sendRootReady(String dimension, RootToken root) throws IOException {
        Payload output = new Payload();
        output.string(dimension);
        output.root(root);
        sendControl(C_ROOT_READY, output.bytes());
    }

    @Override
    public void sendCameraDomain(RootToken root, long sequence,
                                 int blockX, int blockY, int blockZ) throws IOException {
        if (sequence == 0) throw new IOException("camera-domain sequence zero is reserved");
        Payload output = new Payload();
        output.root(root);
        output.u64(sequence);
        output.i32(blockX);
        output.i32(blockY);
        output.i32(blockZ);
        sendControl(C_CAMERA_DOMAIN, output.bytes());
    }

    @Override
    public Request requestObjects(RootToken root, Lane lane, List<Hash256> hashes,
                                  ObjectReceiver receiver) throws IOException {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(lane, "lane");
        Objects.requireNonNull(receiver, "receiver");
        List<Hash256> requested = List.copyOf(Objects.requireNonNull(hashes, "hashes"));
        boolean duplicate = false;
        for (int index = 0; index < requested.size() && !duplicate; index++) {
            for (int other = 0; other < index; other++) {
                if (requested.get(index).equals(requested.get(other))) {
                    duplicate = true;
                    break;
                }
            }
        }
        if (requested.isEmpty() || requested.size() > WireMessage.MAX_REQUEST_ENTRIES
                || duplicate) {
            throw new IOException("invalid Voxy QUIC object request");
        }
        if (!isOpen()) throw closedFailure();

        RequestHandle handle = new RequestHandle(receiver);
        this.workers.submit(() -> runObjectRequest(root, lane, requested, handle));
        return handle;
    }

    @Override
    public void close() {
        if (!this.closed.compareAndSet(false, true)) return;
        this.connection.close();
        this.workers.shutdownNow();
        signalActivity();
    }

    private void readControls() {
        try {
            while (!this.closed.get()) {
                int type = this.controlInput.read();
                if (type < 0) throw new EOFException("Voxy QUIC control stream ended");
                int length = readU16(this.controlInput);
                if (length > CONTROL_PAYLOAD_LIMIT) {
                    throw new IOException("oversized Voxy QUIC control record");
                }
                ByteBuffer payload = ByteBuffer.wrap(readExact(this.controlInput, length))
                        .order(ByteOrder.LITTLE_ENDIAN);
                ControlMessage message = decodeControl(type, payload);
                if (payload.hasRemaining()) throw new IOException(
                        "trailing Voxy QUIC control payload");
                if (!this.controls.offer(message)) {
                    throw new IOException("QUIC control queue is full");
                }
                signalActivity();
            }
        } catch (Throwable failure) {
            if (!this.closed.get()) fail(failure);
        }
    }

    private void sendControl(int type, byte[] payload) throws IOException {
        if (payload.length > CONTROL_PAYLOAD_LIMIT || !isOpen()) throw closedFailure();
        synchronized (this.controlWriteLock) {
            try {
                byte[] record = new byte[3 + payload.length];
                record[0] = (byte) type;
                record[1] = (byte) payload.length;
                record[2] = (byte) (payload.length >>> 8);
                System.arraycopy(payload, 0, record, 3, payload.length);
                this.controlOutput.write(record);
                this.controlOutput.flush();
            } catch (IOException failure) {
                fail(failure);
                throw failure;
            }
        }
    }

    private void runObjectRequest(RootToken root, Lane lane, List<Hash256> requested,
                                  RequestHandle handle) {
        try {
            if (handle.cancelled()) return;
            QuicStream stream = this.connection.createStream(true);
            handle.open(stream);
            if (handle.cancelled()) return;

            ByteArrayOutputStream request = new ByteArrayOutputStream(
                    1 + 1 + 2 + 72 + requested.size() * WireMessage.HASH_BYTES);
            request.write(OBJECT_ROLE);
            request.write(lane.wireId);
            writeU16(request, requested.size());
            writeRoot(request, root);
            for (Hash256 hash : requested) writeHash(request, hash);
            OutputStream output = stream.getOutputStream();
            output.write(request.toByteArray());
            output.close();

            readObjectResponse(stream.getInputStream(), requested, handle);
        } catch (Throwable failure) {
            handle.fail(failure);
        }
    }

    private static void readObjectResponse(InputStream input, List<Hash256> requested,
                                           RequestHandle handle) throws IOException {
        int status = input.read();
        if (status < 0) throw new EOFException("truncated Voxy object response");
        if (status == 1) {
            int code = readU16(input);
            String message = readString(input, CONTROL_PAYLOAD_LIMIT - 4);
            requireFin(input);
            throw new ObjectStreamException(code, message);
        }
        if (status != 0) throw new IOException("invalid Voxy object response status");
        int count = readU16(input);
        if (count != requested.size()) {
            throw new IOException("Voxy object response count disagrees with request");
        }

        long canonicalBytes = 0;
        long compressedBytes = 0;
        byte[] transfer = new byte[32 * 1024];
        for (int index = 0; index < count; index++) {
            Hash256 hash = readHash(input);
            if (!hash.equals(requested.get(index))) {
                throw new IOException("Voxy object response is out of request order");
            }
            ObjectKind kind;
            try {
                int kindId = input.read();
                if (kindId < 0) throw new EOFException("truncated Voxy object header");
                kind = ObjectKind.fromWireId(kindId);
            } catch (IllegalArgumentException error) {
                throw new IOException("invalid Voxy object kind", error);
            }
            int dictionary = readI32(input);
            long canonical = readU32(input);
            long compressed = readU32(input);
            int expectedChecksum = readI32(input);
            if (canonical > WireMessage.MAX_CANONICAL_OBJECT_BYTES || compressed == 0
                    || compressed > WireMessage.MAX_COMPRESSED_OBJECT_BYTES) {
                throw new IOException("Voxy object response exceeds protocol bounds");
            }
            canonicalBytes += canonical;
            compressedBytes += compressed;
            if (canonicalBytes > MAX_RESPONSE_CANONICAL_BYTES
                    || compressedBytes > MAX_RESPONSE_COMPRESSED_BYTES) {
                throw new IOException("Voxy object response batch exceeds protocol bounds");
            }

            ReleaseGate gate = handle.acquireBody();
            BodySink body = null;
            boolean owned = false;
            boolean delivered = false;
            try {
                body = BODY_POOL.acquire((int) compressed);
                CRC32C checksum = new CRC32C();
                readBody(input, body.buffer, transfer, checksum, handle);
                body.buffer.flip();
                if ((int) checksum.getValue() != expectedChecksum) {
                    throw new IOException("compressed Voxy object checksum mismatch");
                }
                EncodedObject object;
                try {
                    object = EncodedObject.takeVerifiedOwnership(hash, kind, dictionary,
                            (int) canonical, expectedChecksum, body.buffer, body.release);
                } catch (IllegalArgumentException error) {
                    throw new IOException("invalid Voxy object envelope", error);
                }
                owned = true;
                handle.object(object, gate);
                delivered = true;
            } finally {
                if (!owned && body != null) body.release.run();
                if (!delivered) gate.release();
            }
        }
        requireFin(input);
        handle.complete();
    }

    private void fail(Throwable cause) {
        Throwable actual = cause == null ? new IOException("Voxy QUIC connection failed") : cause;
        if (this.failure.compareAndSet(null, actual)) {
            signalActivity();
            close();
        }
    }

    private void signalActivity() {
        this.activityListener.get().run();
    }

    private IOException closedFailure() {
        return new IOException("Voxy QUIC connection is closed", this.failure.get());
    }

    private static ControlMessage decodeControl(int type, ByteBuffer input) throws IOException {
        try {
            return switch (type) {
                case S_HELLO -> {
                    requireRemaining(input, 8, "HELLO");
                    yield new ServerHello(input.getLong());
                }
                case S_ROOT_ANNOUNCE -> {
                    String dimension = readString(input, WireMessage.MAX_DIMENSION_BYTES);
                    requireRemaining(input, 72 + 3 * WireMessage.HASH_BYTES, "ROOT_ANNOUNCE");
                    yield new RootAnnounceMessage(new RootAnnounce(dimension, readRoot(input),
                            readHash(input), readHash(input), readHash(input)));
                }
                case S_CAMERA_DOMAIN -> {
                    requireRemaining(input, 72 + 8 + 1 + 8 + 6 * 4, "CAMERA_DOMAIN");
                    RootToken root = readRoot(input);
                    long sequence = input.getLong();
                    int state = Byte.toUnsignedInt(input.get());
                    long domain = input.getLong();
                    yield new CameraDomain(root, sequence, state, domain,
                            input.getInt(), input.getInt(), input.getInt(),
                            input.getInt(), input.getInt(), input.getInt());
                }
                case S_ERROR -> {
                    if (input.remaining() < 4) throw new IOException("invalid ERROR");
                    int code = Short.toUnsignedInt(input.getShort());
                    yield new ServerError(code, readString(input, CONTROL_PAYLOAD_LIMIT - 4));
                }
                case S_SHUTDOWN -> new ServerShutdown(
                        readString(input, CONTROL_PAYLOAD_LIMIT - 2));
                default -> throw new IOException("unknown Voxy QUIC control type " + type);
            };
        } catch (java.nio.BufferUnderflowException | IllegalArgumentException error) {
            throw new IOException("malformed Voxy QUIC control record", error);
        }
    }

    private static void requireRemaining(ByteBuffer input, int length, String message)
            throws IOException {
        if (input.remaining() != length) throw new IOException("invalid " + message);
    }

    private static String readString(ByteBuffer input, int maximum) throws IOException {
        if (input.remaining() < 2) throw new IOException("truncated QUIC string");
        int length = Short.toUnsignedInt(input.getShort());
        if (length == 0 || length > maximum || input.remaining() < length) {
            throw new IOException("invalid QUIC string length");
        }
        ByteBuffer bytes = input.slice(input.position(), length);
        input.position(input.position() + length);
        return decodeUtf8(bytes);
    }

    private static String readString(InputStream input, int maximum) throws IOException {
        int length = readU16(input);
        if (length == 0 || length > maximum) throw new IOException("invalid QUIC string length");
        return decodeUtf8(ByteBuffer.wrap(readExact(input, length)));
    }

    private static String decodeUtf8(ByteBuffer bytes) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(bytes).toString();
        } catch (CharacterCodingException error) {
            throw new IOException("invalid UTF-8 on Voxy QUIC stream", error);
        }
    }

    private static void readBody(InputStream input, ByteBuffer output, byte[] transfer,
                                 CRC32C checksum, RequestHandle handle) throws IOException {
        while (output.hasRemaining()) {
            int length = Math.min(output.remaining(), transfer.length);
            int read = input.read(transfer, 0, length);
            if (read < 0) throw new EOFException("truncated Voxy object body");
            if (read == 0) continue;
            checksum.update(transfer, 0, read);
            output.put(transfer, 0, read);
            handle.progress();
        }
    }

    private static byte[] readExact(InputStream input, int length) throws IOException {
        byte[] bytes = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = input.read(bytes, offset, length - offset);
            if (read < 0) throw new EOFException("truncated Voxy QUIC stream");
            if (read != 0) offset += read;
        }
        return bytes;
    }

    private static void requireFin(InputStream input) throws IOException {
        if (input.read() >= 0) throw new IOException("trailing Voxy object response bytes");
    }

    private static int readU16(InputStream input) throws IOException {
        int low = input.read();
        int high = input.read();
        if ((low | high) < 0) throw new EOFException("truncated Voxy QUIC integer");
        return low | high << 8;
    }

    private static long readU32(InputStream input) throws IOException {
        return Integer.toUnsignedLong(readI32(input));
    }

    private static int readI32(InputStream input) throws IOException {
        int b0 = input.read();
        int b1 = input.read();
        int b2 = input.read();
        int b3 = input.read();
        if ((b0 | b1 | b2 | b3) < 0) throw new EOFException("truncated Voxy QUIC integer");
        return b0 | b1 << 8 | b2 << 16 | b3 << 24;
    }

    private static Hash256 readHash(InputStream input) throws IOException {
        return Hash256.fromBytes(readExact(input, WireMessage.HASH_BYTES));
    }

    private static Hash256 readHash(ByteBuffer input) {
        return new Hash256(input.getLong(), input.getLong(), input.getLong(), input.getLong());
    }

    private static RootToken readRoot(ByteBuffer input) {
        return new RootToken(input.getLong(), readHash(input), readHash(input));
    }

    private static void writeU16(OutputStream output, int value) throws IOException {
        output.write(value);
        output.write(value >>> 8);
    }

    private static void writeI32(OutputStream output, int value) throws IOException {
        for (int shift = 0; shift < 32; shift += 8) output.write(value >>> shift);
    }

    private static void writeU64(OutputStream output, long value) throws IOException {
        for (int shift = 0; shift < 64; shift += 8) output.write((int) (value >>> shift));
    }

    private static void writeHash(OutputStream output, Hash256 hash) throws IOException {
        output.write(hash.toBytes());
    }

    private static void writeRoot(OutputStream output, RootToken root) throws IOException {
        writeU64(output, root.generation());
        writeHash(output, root.dimensionHash());
        writeHash(output, root.rootHash());
    }

    private static void rejectRemoteStream(QuicStream stream) {
        stream.abortReading(STREAM_ERROR_CANCELLED);
        stream.resetStream(STREAM_ERROR_CANCELLED);
    }

    private record BodySink(ByteBuffer buffer, Runnable release) {}

    /** Small fixed size-class pool; overflow and large bodies are explicitly freed. */
    private static final class DirectBodyPool {
        private static final int[] SIZES = {4 << 10, 16 << 10, 64 << 10, 256 << 10};
        private static final int PER_CLASS = 8;
        private final List<ArrayBlockingQueue<ByteBuffer>> free = Arrays.stream(SIZES)
                .mapToObj(ignored -> new ArrayBlockingQueue<ByteBuffer>(PER_CLASS)).toList();

        private BodySink acquire(int length) {
            int sizeClass = -1;
            for (int index = 0; index < SIZES.length; index++) {
                if (length <= SIZES[index]) {
                    sizeClass = index;
                    break;
                }
            }
            int capacity = sizeClass < 0 ? length : SIZES[sizeClass];
            ByteBuffer buffer = sizeClass < 0 ? null : this.free.get(sizeClass).poll();
            if (buffer == null) buffer = MemoryUtil.memAlloc(capacity);
            buffer.clear().limit(length);
            ByteBuffer owned = buffer;
            int pooledClass = sizeClass;
            return new BodySink(buffer, () -> {
                owned.clear();
                if (pooledClass < 0 || !this.free.get(pooledClass).offer(owned)) {
                    MemoryUtil.memFree(owned);
                }
            });
        }
    }

    private static final class RequestHandle implements Request {
        private final ObjectReceiver receiver;
        private final AtomicReference<QuicStream> stream = new AtomicReference<>();
        private final Semaphore bodyCredits = new Semaphore(MAX_PENDING_BODIES_PER_STREAM);
        private final Set<ReleaseGate> gates = ConcurrentHashMap.newKeySet();
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicBoolean finished = new AtomicBoolean();

        private RequestHandle(ObjectReceiver receiver) {
            this.receiver = receiver;
        }

        private void open(QuicStream stream) {
            this.stream.set(stream);
            if (this.cancelled.get()) reset(stream);
        }

        private boolean cancelled() {
            return this.cancelled.get();
        }

        private void progress() {
            if (!this.cancelled.get() && !this.finished.get()) this.receiver.progress();
        }

        private ReleaseGate acquireBody() throws IOException {
            try {
                this.bodyCredits.acquire();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while awaiting Voxy body credit", interrupted);
            }
            if (this.cancelled.get() || this.finished.get()) {
                this.bodyCredits.release();
                throw new IOException("Voxy object request ended before its body arrived");
            }
            ReleaseGate gate = new ReleaseGate(this);
            this.gates.add(gate);
            if (this.cancelled.get() || this.finished.get()) {
                gate.release();
                throw new IOException("Voxy object request ended before its body arrived");
            }
            return gate;
        }

        private void object(EncodedObject object, ReleaseGate gate) throws IOException {
            if (this.cancelled.get() || this.finished.get()) {
                object.close();
                gate.release();
                return;
            }
            try {
                this.receiver.object(object, gate::release);
            } catch (Throwable failure) {
                object.close();
                gate.release();
                if (failure instanceof IOException io) throw io;
                throw new IOException("Voxy object receiver failed", failure);
            }
        }

        private void release(ReleaseGate gate) {
            if (this.gates.remove(gate)) this.bodyCredits.release();
        }

        private void complete() {
            if (this.finished.compareAndSet(false, true) && !this.cancelled.get()) {
                this.receiver.complete();
            }
        }

        private void fail(Throwable failure) {
            if (this.finished.compareAndSet(false, true) && !this.cancelled.get()) {
                this.receiver.failed(failure == null
                        ? new IOException("Voxy object stream failed") : failure);
                QuicStream stream = this.stream.get();
                if (stream != null) reset(stream);
            }
        }

        @Override
        public void cancel() {
            if (!this.cancelled.compareAndSet(false, true)) return;
            this.finished.set(true);
            for (ReleaseGate gate : this.gates) gate.release();
            QuicStream stream = this.stream.get();
            if (stream != null) reset(stream);
        }

        private static void reset(QuicStream stream) {
            stream.abortReading(STREAM_ERROR_CANCELLED);
            stream.resetStream(STREAM_ERROR_CANCELLED);
        }
    }

    private static final class ReleaseGate {
        private final RequestHandle owner;
        private final AtomicBoolean released = new AtomicBoolean();

        private ReleaseGate(RequestHandle owner) {
            this.owner = owner;
        }

        private void release() {
            if (this.released.compareAndSet(false, true)) this.owner.release(this);
        }
    }

    private static final class ConnectionOwner implements ConnectionListener {
        private KwikQuicClient owner;
        private ConnectionTerminatedEvent earlyTermination;

        private synchronized void publish(KwikQuicClient owner) {
            if (this.owner != null) throw new IllegalStateException("connection owner published twice");
            this.owner = owner;
            if (this.earlyTermination != null) owner.connectionClosed(this.earlyTermination);
        }

        @Override
        public synchronized void disconnected(ConnectionTerminatedEvent event) {
            if (this.owner != null) this.owner.connectionClosed(event);
            else this.earlyTermination = event;
        }
    }

    private void connectionClosed(ConnectionTerminatedEvent event) {
        if (!this.closed.get()) fail(new IOException(
                "Voxy QUIC connection ended: " + event.closeReason() + ": "
                        + event.errorDescription()));
    }

    private static final class FingerprintTrustManager implements X509TrustManager {
        private final byte[] expected;

        private FingerprintTrustManager(byte[] expected) {
            this.expected = expected.clone();
        }

        private void verify(X509Certificate[] chain) throws CertificateException {
            if (chain == null || chain.length == 0) {
                throw new CertificateException("Voxy QUIC server supplied no certificate");
            }
            try {
                byte[] actual = MessageDigest.getInstance("SHA-256").digest(chain[0].getEncoded());
                if (!MessageDigest.isEqual(this.expected, actual)) {
                    throw new CertificateException("Voxy QUIC certificate fingerprint mismatch");
                }
            } catch (java.security.GeneralSecurityException error) {
                if (error instanceof CertificateException certificate) throw certificate;
                throw new CertificateException("could not verify Voxy QUIC certificate", error);
            }
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            throw new CertificateException("client trust unsupported");
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            verify(chain);
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }

    private static final class Payload {
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();

        private void string(String value) throws IOException {
            byte[] bytes = Objects.requireNonNull(value, "value").getBytes(StandardCharsets.UTF_8);
            if (bytes.length == 0 || bytes.length > WireMessage.MAX_DIMENSION_BYTES) {
                throw new IOException("invalid Voxy dimension name");
            }
            writeU16(this.output, bytes.length);
            this.output.writeBytes(bytes);
        }

        private void root(RootToken root) throws IOException {
            writeRoot(this.output, root);
        }

        private void u64(long value) throws IOException {
            writeU64(this.output, value);
        }

        private void i32(int value) throws IOException {
            writeI32(this.output, value);
        }

        private byte[] bytes() {
            return this.output.toByteArray();
        }
    }
}
