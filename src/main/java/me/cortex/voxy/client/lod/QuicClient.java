package me.cortex.voxy.client.lod;

import me.cortex.voxy.client.lod.WireMessage.EncodedObject;
import me.cortex.voxy.client.lod.WireMessage.Hash256;
import me.cortex.voxy.client.lod.WireMessage.RootAnnounce;
import me.cortex.voxy.client.lod.WireMessage.RootToken;

import java.io.IOException;
import java.net.InetAddress;
import java.util.List;

/** Provider-neutral client boundary for the one production QUIC connection. */
interface QuicClient extends AutoCloseable {
    final class ObjectStreamException extends IOException {
        private final int code;

        ObjectStreamException(int code, String message) {
            super(message);
            this.code = code;
        }

        int code() {
            return this.code;
        }
    }

    enum Lane {
        COVERAGE(0), CURRENT_VIEW(1), PREDICTED(2);

        final int wireId;

        Lane(int wireId) {
            this.wireId = wireId;
        }
    }

    sealed interface ControlMessage permits ServerHello, RootAnnounceMessage,
            CameraDomain, ServerError, ServerShutdown {}

    record ServerHello(long serverInstance) implements ControlMessage {}
    record RootAnnounceMessage(RootAnnounce value) implements ControlMessage {}
    record CameraDomain(RootToken root, long sequence, int state, long domain,
                        int minX, int minY, int minZ,
                        int maxX, int maxY, int maxZ) implements ControlMessage {}
    record ServerError(int code, String message) implements ControlMessage {}
    record ServerShutdown(String message) implements ControlMessage {}

    interface ObjectReceiver {
        /**
         * Called on a provider I/O worker. The stream remains read-paused until the consumer
         * invokes {@code release}, propagating decoder/cache backpressure to QUIC flow control.
         */
        void object(EncodedObject object, Runnable release);
        void complete();
        void failed(Throwable failure);
    }

    interface Request {
        /** Sends STOP_SENDING and RESET_STREAM when this request is still active. */
        void cancel();
    }

    static QuicClient connect(InetAddress[] addresses, int port, String alpn,
                              byte[] certificateSha256) throws IOException {
        return KwikQuicClient.connect(addresses, port, alpn, certificateSha256);
    }

    ControlMessage pollControl();
    boolean isOpen();
    boolean isTerminated();
    Throwable failure();
    String description();

    /** Installs the connection owner's non-blocking wakeup for control or terminal events. */
    void setActivityListener(Runnable listener);

    void sendHello(String dimension) throws IOException;
    void sendRootReady(String dimension, RootToken root) throws IOException;
    void sendCameraDomain(RootToken root, long sequence,
                          int blockX, int blockY, int blockZ) throws IOException;

    Request requestObjects(RootToken root, Lane lane, List<Hash256> hashes,
                           ObjectReceiver receiver) throws IOException;

    @Override
    void close();
}
