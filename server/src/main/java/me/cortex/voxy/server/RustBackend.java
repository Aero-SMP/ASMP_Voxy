package me.cortex.voxy.server;

import me.cortex.voxy.network.QuicEndpointPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.TimeUnit;

/** Keeps the Rust backend alive for exactly the lifetime of the Minecraft server. */
final class RustBackend {
    private static final Logger LOGGER = LoggerFactory.getLogger("Voxy Rust Backend");
    private static final String READY_MARKER = "VOXY_READY";
    static final Path CONFIG = Path.of("voxy-rust.toml").toAbsolutePath();

    private static volatile boolean running;
    private static ReadyRecord ready;
    private static Process process;
    private static Thread supervisor;
    private static Path directory;
    private static Path binary;

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(RustBackend::stop, "Voxy Rust shutdown"));
    }

    private RustBackend() {}

    static void start() {
        Thread thread;
        synchronized (RustBackend.class) {
            if (running) return;
            extract();
            ServerDebug.rustStarting();
            running = true;
            ready = null;
            thread = Thread.ofPlatform().daemon().name("Voxy Rust supervisor")
                    .unstarted(RustBackend::supervise);
            supervisor = thread;
        }
        try {
            thread.start();
        } catch (RuntimeException failure) {
            synchronized (RustBackend.class) {
                running = false;
                supervisor = null;
            }
            cleanup();
            throw new IllegalStateException("Could not start the Rust supervisor", failure);
        }
    }

    private static void extract() {
        try {
            if (!Files.isRegularFile(CONFIG) || !Files.isReadable(CONFIG)) {
                throw new IOException("missing or unreadable configuration " + CONFIG);
            }
            directory = Files.createTempDirectory("voxy-rust-");
            binary = directory.resolve("voxy-rust-server");
            try (InputStream input = RustBackend.class.getResourceAsStream(
                    "/native/linux-x86_64/voxy-rust-server")) {
                if (input == null) throw new IOException("embedded Rust server is missing");
                Files.copy(input, binary);
            }
            Files.setPosixFilePermissions(binary, PosixFilePermissions.fromString("rwx------"));
        } catch (IOException | RuntimeException exception) {
            cleanup();
            throw new IllegalStateException("Could not prepare the embedded Rust server", exception);
        }
    }

    private static void supervise() {
        while (running) {
            Process child = null;
            try {
                child = new ProcessBuilder(binary.toString(), "--config", CONFIG.toString())
                        .redirectErrorStream(true)
                        .start();
                synchronized (RustBackend.class) {
                    if (!running) {
                        terminateChild(child);
                        return;
                    }
                    process = child;
                    ready = null;
                }
                try (var output = new BufferedReader(new InputStreamReader(
                        child.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = output.readLine()) != null) {
                        LOGGER.info("[Rust] {}", line);
                        if (line.equals(READY_MARKER) || line.startsWith(READY_MARKER + " ")) {
                            ReadyRecord announced = parseReady(line);
                            synchronized (RustBackend.class) {
                                if (running && process == child) {
                                    if (ready != null) {
                                        throw new IllegalStateException(
                                                "Rust backend emitted more than one readiness record");
                                    }
                                    ready = announced;
                                    ServerDebug.rustReady(announced);
                                }
                            }
                        }
                    }
                }
                int exit = child.waitFor();
                clearProcess(child);
                if (running) {
                    ServerDebug.rustExited(exit, true);
                    LOGGER.error("Rust backend exited with code {}; restarting", exit);
                    pause();
                }
            } catch (IOException | RuntimeException exception) {
                terminateChild(child);
                if (running) {
                    ServerDebug.rustFailed(exception, true);
                    LOGGER.error("Could not start the Rust backend; retrying", exception);
                }
                pause();
            } catch (InterruptedException exception) {
                boolean retry = running;
                terminateChild(child);
                if (!retry) return;
                ServerDebug.rustFailed(exception, true);
                LOGGER.warn("Rust backend supervisor was interrupted; retrying", exception);
                pause();
            }
        }
    }

    private static void clearProcess(Process child) {
        synchronized (RustBackend.class) {
            if (process == child) {
                process = null;
                ready = null;
            }
        }
    }

    /** Never relinquishes process ownership until the child has actually exited. */
    private static void terminateChild(Process child) {
        if (child == null) return;
        boolean interrupted = false;
        child.destroy();
        try {
            if (!child.waitFor(10, TimeUnit.SECONDS)) child.destroyForcibly();
        } catch (InterruptedException exception) {
            interrupted = true;
            child.destroyForcibly();
        }
        while (child.isAlive()) {
            try {
                child.waitFor();
            } catch (InterruptedException exception) {
                interrupted = true;
                child.destroyForcibly();
            }
        }
        clearProcess(child);
        if (interrupted) Thread.currentThread().interrupt();
    }

    private static void pause() {
        try {
            Thread.sleep(1_000);
        } catch (InterruptedException ignored) {
            if (!running) Thread.currentThread().interrupt();
        }
    }

    static synchronized ReadyRecord ready() {
        return running && ready != null && process != null && process.isAlive()
                ? ready : null;
    }

    static void stop() {
        Process child;
        Thread thread;
        synchronized (RustBackend.class) {
            if (!running && supervisor == null) return;
            running = false;
            ready = null;
            child = process;
            thread = supervisor;
            supervisor = null;
        }
        terminateChild(child);
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(5_000);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
        cleanup();
    }

    private static void cleanup() {
        try {
            if (binary != null) Files.deleteIfExists(binary);
            if (directory != null) Files.deleteIfExists(directory);
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("Could not remove the temporary Rust server", exception);
        } finally {
            binary = null;
            directory = null;
        }
    }

    private static ReadyRecord parseReady(String line) {
        String[] fields = line.split(" ", -1);
        if (fields.length != 4 || !fields[0].equals(READY_MARKER)) {
            throw new IllegalArgumentException("malformed Rust QUIC readiness record");
        }
        int udpPort = parsePort(field(fields[1], "udp_port"));
        String alpn = field(fields[2], "alpn");
        if (alpn.isEmpty() || !QuicEndpointPayload.isValidAlpn(alpn)) {
            throw new IllegalArgumentException("Rust QUIC readiness ALPN is not an ASCII token");
        }
        byte[] certificateSha256 = parseSha256(field(fields[3], "cert_sha256"));
        return new ReadyRecord(udpPort, alpn, certificateSha256);
    }

    private static String field(String encoded, String name) {
        String prefix = name + "=";
        if (!encoded.startsWith(prefix) || encoded.length() == prefix.length()) {
            throw new IllegalArgumentException("malformed Rust QUIC readiness field " + name);
        }
        return encoded.substring(prefix.length());
    }

    private static int parsePort(String value) {
        if (value.length() > 5 || value.length() > 1 && value.charAt(0) == '0'
                || value.chars().anyMatch(character -> character < '0' || character > '9')) {
            throw new IllegalArgumentException("Rust QUIC readiness UDP port is not canonical");
        }
        int port = Integer.parseInt(value);
        if (port == 0 || port > 0xffff) {
            throw new IllegalArgumentException("Rust QUIC readiness UDP port is out of range");
        }
        return port;
    }

    private static byte[] parseSha256(String value) {
        if (value.length() != QuicEndpointPayload.CERTIFICATE_SHA256_BYTES * 2) {
            throw new IllegalArgumentException(
                    "Rust QUIC readiness certificate fingerprint has the wrong length");
        }
        byte[] bytes = new byte[QuicEndpointPayload.CERTIFICATE_SHA256_BYTES];
        for (int index = 0; index < bytes.length; index++) {
            int high = lowercaseHex(value.charAt(index * 2));
            int low = lowercaseHex(value.charAt(index * 2 + 1));
            if ((high | low) < 0) {
                throw new IllegalArgumentException(
                        "Rust QUIC readiness certificate fingerprint is not lowercase hexadecimal");
            }
            bytes[index] = (byte) (high << 4 | low);
        }
        return bytes;
    }

    private static int lowercaseHex(char value) {
        if (value >= '0' && value <= '9') return value - '0';
        return value >= 'a' && value <= 'f' ? value - 'a' + 10 : -1;
    }

    record ReadyRecord(int udpPort, String alpn, byte[] certificateSha256) {
        ReadyRecord {
            if (udpPort <= 0 || udpPort > 0xffff || alpn == null || alpn.isEmpty()
                    || !QuicEndpointPayload.isValidAlpn(alpn)
                    || certificateSha256 == null
                    || certificateSha256.length
                    != QuicEndpointPayload.CERTIFICATE_SHA256_BYTES) {
                throw new IllegalArgumentException("invalid Rust QUIC readiness record");
            }
            certificateSha256 = certificateSha256.clone();
        }

        @Override
        public byte[] certificateSha256() {
            return this.certificateSha256.clone();
        }
    }
}
