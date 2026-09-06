package me.cortex.voxy.server;

import tech.kwik.core.QuicClientConnection;
import javax.net.ssl.X509TrustManager;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.cert.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Bundled production binary, production supervisor, persisted identity and real Kwik QUIC. */
public final class SupervisorRustIntegrationTest {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("voxy-supervisor-interop-");
        Files.createDirectories(root.resolve("world/region"));
        Path config = root.resolve("voxy-rust.toml");
        RustBackend.ensureConfig(config);
        // Parse the real generated defaults in Rust, isolating only paths and listener.
        Files.writeString(config, Files.readString(config)
                .replace("world = \"world\"", "world = \"" + root.resolve("world") + "\"")
                .replace("data = \"voxy-rust/data\"", "data = \"" + root.resolve("data") + "\"")
                .replace("0.0.0.0:25587", "127.0.0.1:0"));
        var owned = new RustBackend.Owner(config);
        try {
            RustBackend.start(owned);
            SupervisorRecoveryBehaviorTest.until(() -> RustBackend.ready() != null);
            var firstReady = RustBackend.ready();
            Process first = owned.child;
            Path executable = owned.binary;
            byte[] certificate = Files.readAllBytes(root.resolve("data/quic/certificate.der"));
            byte[] key = Files.readAllBytes(root.resolve("data/quic/private-key.der"));
            byte[] catalog = probe(firstReady);
            // Process.destroy() also closes Java's pipes. Signal like the live PID test,
            // leaving output draining to the supervisor until the child actually exits.
            SupervisorRecoveryBehaviorTest.check(first.toHandle().destroy(), "isolated child signal failed");
            SupervisorRecoveryBehaviorTest.until(() -> owned.child != null && owned.child != first && RustBackend.ready() != null);
            Process second = owned.child;
            var secondReady = RustBackend.ready();
            SupervisorRecoveryBehaviorTest.check(!first.isAlive() && second.isAlive(), "bundled Rust overlap or no replacement");
            SupervisorRecoveryBehaviorTest.check(first.exitValue() == 0, "isolated Rust did not exit cleanly on SIGTERM");
            SupervisorRecoveryBehaviorTest.check(Arrays.equals(firstReady.certificateSha256(), secondReady.certificateSha256()), "identity changed after replacement");
            SupervisorRecoveryBehaviorTest.check(Arrays.equals(certificate, Files.readAllBytes(root.resolve("data/quic/certificate.der")))
                    && Arrays.equals(key, Files.readAllBytes(root.resolve("data/quic/private-key.der"))), "persisted identity rewritten");
            SupervisorRecoveryBehaviorTest.check(Arrays.equals(catalog, probe(secondReady)), "catalog transfer changed across restart");
            RustBackend.stop();
            SupervisorRecoveryBehaviorTest.check(second.exitValue() == 0, "supervisor closed Rust shutdown pipes before clean exit");
            SupervisorRecoveryBehaviorTest.check(!first.isAlive() && !second.isAlive() && !Files.exists(executable)
                    && owned.child == null && !owned.thread.isAlive(), "bundled Rust stop leaked ownership");
            System.out.println("Bundled Rust interop PASS: PIDs " + first.pid() + " -> " + second.pid()
                    + "; same certificate/private key; pinned QUIC handshake and " + catalog.length
                    + " catalog bytes transferred before/after; no child/executable after stop");
        } finally {
            RustBackend.stop();
            if (owned.child == null && (owned.thread == null || !owned.thread.isAlive())) {
                try (var paths = Files.walk(root)) {
                    for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
                }
            }
        }
    }

    private static byte[] probe(RustBackend.ReadyRecord ready) throws Exception {
        var connection = QuicClientConnection.newBuilder().host("voxy.local").proxy("127.0.0.1")
                .port(ready.udpPort()).applicationProtocol(ready.alpn()).connectTimeout(Duration.ofSeconds(5))
                .customTrustManager(new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                    public void checkClientTrusted(X509Certificate[] chain, String auth) throws CertificateException { throw new CertificateException(); }
                    public void checkServerTrusted(X509Certificate[] chain, String auth) throws CertificateException {
                        try {
                            if (chain.length == 0 || !MessageDigest.isEqual(ready.certificateSha256(),
                                    MessageDigest.getInstance("SHA-256").digest(chain[0].getEncoded()))) throw new CertificateException("pin mismatch");
                        } catch (java.security.NoSuchAlgorithmException error) { throw new CertificateException(error); }
                    }
                }).build();
        ExecutorService reader = Executors.newSingleThreadExecutor();
        try {
            connection.connect();
            return reader.submit(() -> {
                var stream = connection.createStream(true);
                var output = stream.getOutputStream();
                output.write(0); // Production regional control stream.
                byte[] dimension = "minecraft:overworld".getBytes(StandardCharsets.UTF_8);
                byte[] hello = ByteBuffer.allocate(7 + dimension.length).order(ByteOrder.LITTLE_ENDIAN)
                        .put((byte) 1).putInt(2 + dimension.length).putShort((short) dimension.length).put(dimension).array();
                output.write(hello); output.flush();
                var input = stream.getInputStream();
                record(input, 0x81);
                output.write(new byte[]{3, 0, 0, 0, 0}); output.flush();
                byte[] catalog = record(input, 0x83);
                if (catalog.length <= 32) throw new IOException("empty catalog transfer");
                return catalog;
            }).get(10, TimeUnit.SECONDS);
        } finally { connection.close(); reader.shutdownNow(); }
    }

    private static byte[] record(InputStream input, int expected) throws IOException {
        byte[] header = input.readNBytes(5);
        if (header.length != 5 || Byte.toUnsignedInt(header[0]) != expected) throw new IOException("unexpected record");
        int length = ByteBuffer.wrap(header, 1, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
        if (length < 0 || length > 65536) throw new IOException("isolated fixture record out of bounds");
        byte[] payload = input.readNBytes(length);
        if (payload.length != length) throw new EOFException();
        return payload;
    }
}
