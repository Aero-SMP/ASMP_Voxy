package me.cortex.voxy.client.lod;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Process-lifetime counters for Voxy-owned UDP sockets only, including retries/reconnects.
 * Byte counts include QUIC framing/encryption, but not IP/UDP headers or link-layer overhead. */
final class TransportDebugTelemetry {
    static final AtomicLong sent = new AtomicLong(), received = new AtomicLong();
    static final AtomicLong sends = new AtomicLong(), receives = new AtomicLong();
    static final AtomicLong opened = new AtomicLong(), closed = new AtomicLong();

    static final class Socket extends DatagramSocket {
        private final AtomicBoolean reportedClosed = new AtomicBoolean();
        // Kwik 0.10.10's default factory is also new DatagramSocket(): same bind semantics.
        Socket() throws SocketException { super(); opened.incrementAndGet(); }
        @Override public void send(DatagramPacket packet) throws IOException {
            super.send(packet);
            sent.addAndGet(packet.getLength()); sends.incrementAndGet();
        }
        @Override public void receive(DatagramPacket packet) throws IOException {
            super.receive(packet);
            received.addAndGet(packet.getLength()); receives.incrementAndGet();
        }
        @Override public void close() {
            super.close();
            if (this.reportedClosed.compareAndSet(false, true)) closed.incrementAndGet();
        }
    }

    static String snapshot() {
        return " quicUdpSentBytesJvm=" + sent.get() + " quicUdpReceivedBytesJvm=" + received.get()
                + " quicUdpSentDatagramsJvm=" + sends.get() + " quicUdpReceivedDatagramsJvm=" + receives.get()
                + " quicSocketsOpenedJvm=" + opened.get() + " quicSocketsClosedJvm=" + closed.get();
    }
}
