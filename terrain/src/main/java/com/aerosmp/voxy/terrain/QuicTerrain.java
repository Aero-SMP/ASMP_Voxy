package com.aerosmp.voxy.terrain;

import tech.kwik.core.QuicClientConnection;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Duration;

/** Ordered terrain pipeline and a dictionary stream; flow control belongs to QUIC. */
public final class QuicTerrain implements AutoCloseable {
    public static final String ALPN = "voxy-rewrite-1";
    private final QuicClientConnection connection;
    private InputStream input, dictionaryInput;
    private OutputStream output, dictionaryOutput;
    private byte[] world;
    private String dimension;
    public QuicTerrain(String address, int port, byte[] certificate) throws IOException {
        connection = QuicClientConnection.newBuilder().host("voxy.local").proxy(address).port(port)
                .applicationProtocol(ALPN).connectTimeout(Duration.ofSeconds(120))
                .maxIdleTimeout(Duration.ofSeconds(300))
                .maxOpenPeerInitiatedBidirectionalStreams(0).maxOpenPeerInitiatedUnidirectionalStreams(0)
                .customTrustManager(new Pinned(certificate)).build();
    }
    public void connect(String dimension) throws IOException {
        this.dimension = dimension;
        try {
            connection.connect();
            var stream = connection.createStream(true);
            input = stream.getInputStream(); output = stream.getOutputStream();
            byte[] name = dimension.getBytes(StandardCharsets.UTF_8);
            if (name.length == 0 || name.length > 1024) throw new IOException("Invalid dimension");
            output.write(name.length); output.write(name.length >>> 8); output.write(name); output.flush();
            world = read(16);
        } catch (IOException | RuntimeException failure) { connection.close(); throw failure; }
    }
    public byte[] world() { return world.clone(); }
    public record Request(SectionKey key, byte[] known) {}
    @FunctionalInterface public interface Receiver { void received(SectionKey key, byte[] frame) throws IOException; }
    public void sections(java.util.List<Request> requests, Receiver receiver) throws IOException {
        Thread sender = Thread.ofVirtual().name("Voxy current view requests").start(() -> {
            try {
                ByteBuffer request = ByteBuffer.allocate(46).order(ByteOrder.LITTLE_ENDIAN);
                for (Request item : requests) {
                    request.clear(); encode(request, item.key, item.known);
                    output.write(request.array());
                }
                output.flush();
            } catch (IOException failure) { connection.close(); }
        });
        try {
            for (Request item : requests) receiver.received(item.key, receiveSection());
        } catch (IOException | RuntimeException failure) { close(); throw failure; }
        finally {
            try { sender.join(); }
            catch (InterruptedException stopped) { close(); Thread.currentThread().interrupt(); throw new IOException(stopped); }
        }
    }
    /** Null means unchanged or unavailable; neither authorizes removing existing coverage. */
    public byte[] section(SectionKey key, byte[] known) throws IOException {
        ByteBuffer request = ByteBuffer.allocate(46).order(ByteOrder.LITTLE_ENDIAN);
        encode(request, key, known);
        output.write(request.array()); output.flush();
        return receiveSection();
    }
    private static void encode(ByteBuffer request, SectionKey key, byte[] known) {
        if (known != null && known.length != 32) throw new IllegalArgumentException("Invalid known digest");
        request.put((byte) 0).put((byte) key.level()).putInt(key.x()).putInt(key.y()).putInt(key.z())
                .put(known == null ? new byte[32] : known);
    }
    private byte[] receiveSection() throws IOException {
        int status = input.read();
        if (status == 0 || status == 1) return null;
        if (status != 2) throw new IOException("Invalid terrain reply " + status);
        byte[] frame = record(SectionCodec.HEADER + SectionCodec.MAX_CANONICAL + 2048);
        SectionCodec.checkFrame(frame);
        return frame;
    }
    public byte[] catalog(byte[] hash) throws IOException {
        if (dictionaryInput == null) {
            var stream = connection.createStream(true);
            dictionaryInput = stream.getInputStream(); dictionaryOutput = stream.getOutputStream();
            byte[] name = dimension.getBytes(StandardCharsets.UTF_8);
            dictionaryOutput.write(name.length); dictionaryOutput.write(name.length >>> 8);
            dictionaryOutput.write(name); dictionaryOutput.flush();
            if (!MessageDigest.isEqual(world, read(dictionaryInput, 16))) throw new IOException("World identity changed on one connection");
        }
        dictionaryOutput.write(1); dictionaryOutput.write(hash); dictionaryOutput.flush();
        byte[] value = record(dictionaryInput, 64 * 1024 * 1024);
        SectionCodec.catalog(value, hash);
        return value;
    }
    private byte[] record(int maximum) throws IOException {
        return record(input, maximum);
    }
    private byte[] record(InputStream source, int maximum) throws IOException {
        int length = ByteBuffer.wrap(read(source, 4)).order(ByteOrder.LITTLE_ENDIAN).getInt();
        if (length <= 0 || length > maximum) throw new IOException("Invalid terrain reply length");
        return read(source, length);
    }
    private byte[] read(int count) throws IOException {
        return read(input, count);
    }
    private byte[] read(InputStream source, int count) throws IOException {
        byte[] value = source.readNBytes(count);
        if (value.length != count) throw new IOException("Truncated terrain reply");
        return value;
    }
    @Override public void close() { connection.close(); }
    private record Pinned(byte[] fingerprint) implements X509TrustManager {
        @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        @Override public void checkClientTrusted(X509Certificate[] chain, String authentication) throws CertificateException {
            throw new CertificateException("Client certificates not accepted");
        }
        @Override public void checkServerTrusted(X509Certificate[] chain, String authentication) throws CertificateException {
            if (chain.length == 0 || fingerprint.length != 32
                    || !MessageDigest.isEqual(SectionCodec.hash(chain[0].getEncoded()), fingerprint))
                throw new CertificateException("Terrain certificate does not match Minecraft advertisement");
        }
    }
}
