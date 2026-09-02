package me.cortex.voxy.bridge;

import me.cortex.voxy.network.TransportPayload;
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
    private static final String READY_PREFIX = "VOXY_READY transport=";
    private static final String DIRECT_READY = READY_PREFIX + "direct";
    private static final String MINECRAFT_READY = READY_PREFIX + "minecraft";
    static final Path CONFIG = Path.of("voxy-rust.toml").toAbsolutePath();

    private static volatile boolean running;
    private static volatile boolean ready;
    private static volatile Process process;
    private static Thread supervisor;
    private static Path directory;
    private static Path binary;
    private static byte expectedTransport;

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(RustBackend::stop, "Voxy Rust shutdown"));
    }

    private RustBackend() {}

    static void start(byte transport) {
        if (transport != TransportPayload.DIRECT && transport != TransportPayload.MINECRAFT) {
            throw new IllegalArgumentException("invalid Rust backend transport");
        }
        Thread thread;
        synchronized (RustBackend.class) {
            if (running) {
                if (expectedTransport != transport) {
                    throw new IllegalStateException("Rust backend is running in another transport mode");
                }
                return;
            }
            extract();
            running = true;
            ready = false;
            expectedTransport = transport;
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
                        child.destroy();
                        return;
                    }
                    process = child;
                }
                try (var output = new BufferedReader(new InputStreamReader(
                        child.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = output.readLine()) != null) {
                        LOGGER.info("[Rust] {}", line);
                        if (line.equals(expectedReadyLine())) {
                            synchronized (RustBackend.class) {
                                if (running && process == child) {
                                    ready = true;
                                    RustBackend.class.notifyAll();
                                }
                            }
                        }
                    }
                }
                int exit = child.waitFor();
                clearProcess(child);
                if (running) {
                    LOGGER.error("Rust backend exited with code {}; restarting", exit);
                    pause();
                }
            } catch (IOException | RuntimeException exception) {
                if (child != null) child.destroy();
                clearProcess(child);
                if (running) LOGGER.error("Could not start the Rust backend; retrying", exception);
                pause();
            } catch (InterruptedException exception) {
                if (!running) return;
                if (child != null) child.destroy();
                clearProcess(child);
                LOGGER.warn("Rust backend supervisor was interrupted; retrying", exception);
                pause();
            }
        }
    }

    private static String expectedReadyLine() {
        return expectedTransport == TransportPayload.DIRECT
                ? DIRECT_READY : MINECRAFT_READY;
    }

    private static void clearProcess(Process child) {
        synchronized (RustBackend.class) {
            if (process == child) process = null;
            ready = false;
            RustBackend.class.notifyAll();
        }
    }

    private static void pause() {
        try {
            Thread.sleep(1_000);
        } catch (InterruptedException ignored) {
            if (!running) Thread.currentThread().interrupt();
        }
    }

    static boolean isReady() {
        Process child = process;
        return running && ready && child != null && child.isAlive();
    }

    static void stop() {
        Process child;
        Thread thread;
        synchronized (RustBackend.class) {
            if (!running && supervisor == null) return;
            running = false;
            ready = false;
            child = process;
            process = null;
            thread = supervisor;
            supervisor = null;
            RustBackend.class.notifyAll();
        }
        if (child != null) {
            child.destroy();
            try {
                if (!child.waitFor(120, TimeUnit.SECONDS)) child.destroyForcibly().waitFor();
            } catch (InterruptedException exception) {
                child.destroyForcibly();
                Thread.currentThread().interrupt();
            }
        }
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
}
