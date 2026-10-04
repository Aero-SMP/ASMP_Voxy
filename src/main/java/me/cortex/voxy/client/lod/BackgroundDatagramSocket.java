package me.cortex.voxy.client.lod;

import java.io.IOException;
import java.net.*;
import java.util.Arrays;

/** Routes a separately paced QUIC connection through the primary server's UDP port. */
final class BackgroundDatagramSocket extends DatagramSocket {
    private static final int HEADER = 17;
    private final DatagramSocket socket;
    private final byte[] token;
    private final byte[] sending = new byte[65535], receiving = new byte[65535];
    private final DatagramPacket incoming = new DatagramPacket(this.receiving, this.receiving.length);
    private final DatagramPacket outgoing = new DatagramPacket(this.sending, this.sending.length);

    BackgroundDatagramSocket(DatagramSocket socket, byte[] token) throws SocketException {
        super((SocketAddress) null);
        this.socket = socket;
        this.token = Arrays.copyOf(token, 16);
        System.arraycopy(this.token, 0, this.sending, 1, this.token.length);
    }

    @Override public void send(DatagramPacket packet) throws IOException {
        synchronized (this.sending) {
            int length = packet.getLength();
            if (length > 65507 - HEADER) throw new IOException("background datagram exceeds UDP payload size");
            System.arraycopy(packet.getData(), packet.getOffset(), this.sending, HEADER, length);
            this.outgoing.setLength(HEADER + length);
            this.outgoing.setSocketAddress(packet.getSocketAddress());
            this.socket.send(this.outgoing);
        }
    }

    @Override public void receive(DatagramPacket packet) throws IOException {
        synchronized (this.receiving) {
            while (true) {
                this.incoming.setLength(this.receiving.length);
                this.socket.receive(this.incoming);
                int length = this.incoming.getLength();
                if (length <= HEADER || this.receiving[0] != 0
                        || !Arrays.equals(this.token, 0, 16, this.receiving, 1, HEADER)) continue;
                int copied = Math.min(length - HEADER, packet.getLength());
                System.arraycopy(this.receiving, HEADER, packet.getData(), packet.getOffset(), copied);
                packet.setLength(copied);
                packet.setSocketAddress(this.incoming.getSocketAddress());
                return;
            }
        }
    }

    @Override public void close() { this.socket.close(); super.close(); }
    @Override public boolean isClosed() { return this.socket.isClosed(); }
    @Override public boolean isBound() { return this.socket.isBound(); }
    @Override public boolean isConnected() { return this.socket.isConnected(); }
    @Override public int getLocalPort() { return this.socket.getLocalPort(); }
    @Override public InetAddress getLocalAddress() { return this.socket.getLocalAddress(); }
    @Override public SocketAddress getLocalSocketAddress() { return this.socket.getLocalSocketAddress(); }
    @Override public SocketAddress getRemoteSocketAddress() { return this.socket.getRemoteSocketAddress(); }
    @Override public InetAddress getInetAddress() { return this.socket.getInetAddress(); }
    @Override public int getPort() { return this.socket.getPort(); }
    @Override public void bind(SocketAddress address) throws SocketException { this.socket.bind(address); }
    @Override public void connect(SocketAddress address) throws SocketException { this.socket.connect(address); }
    @Override public void connect(InetAddress address, int port) { this.socket.connect(address, port); }
    @Override public void disconnect() { this.socket.disconnect(); }
    @Override public void setSoTimeout(int timeout) throws SocketException { this.socket.setSoTimeout(timeout); }
    @Override public int getSoTimeout() throws SocketException { return this.socket.getSoTimeout(); }
    @Override public void setReceiveBufferSize(int size) throws SocketException { this.socket.setReceiveBufferSize(size); }
    @Override public int getReceiveBufferSize() throws SocketException { return this.socket.getReceiveBufferSize(); }
    @Override public void setSendBufferSize(int size) throws SocketException { this.socket.setSendBufferSize(size); }
    @Override public int getSendBufferSize() throws SocketException { return this.socket.getSendBufferSize(); }
    @Override public void setTrafficClass(int value) throws SocketException { this.socket.setTrafficClass(value); }
    @Override public int getTrafficClass() throws SocketException { return this.socket.getTrafficClass(); }
}
