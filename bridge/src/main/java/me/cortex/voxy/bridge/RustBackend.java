package me.cortex.voxy.bridge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.TimeUnit;

/** Keeps the Rust backend alive for exactly the lifetime of the Minecraft server. */
final class RustBackend {
    private static final Logger LOGGER = LoggerFactory.getLogger("Voxy Rust Backend");
    static final Path CONFIG = Path.of("voxy-rust.toml").toAbsolutePath();

    private static volatile boolean running;
    private static volatile Process process;
    private static Thread supervisor;
    private static Path directory;
    private static Path binary;

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(RustBackend::stop, "Voxy Rust shutdown"));
    }

    private RustBackend() {}

    static synchronized void start() {
        if (running) return;
        try {
            directory = Files.createTempDirectory("voxy-rust-");
            binary = directory.resolve("voxy-rust-server");
            try (InputStream input = RustBackend.class.getResourceAsStream(
                    "/native/linux-x86_64/voxy-rust-server")) {
                if (input == null) throw new IOException("embedded Rust server is missing");
                Files.copy(input, binary);
            }
            Files.setPosixFilePermissions(binary, PosixFilePermissions.fromString("rwx------"));
        } catch (IOException | UnsupportedOperationException exception) {
            cleanup();
            LOGGER.error("Could not extract the embedded Rust server", exception);
            return;
        }
        running = true;
        supervisor = new Thread(RustBackend::supervise, "Voxy Rust supervisor");
        supervisor.setDaemon(true);
        supervisor.start();
    }

    private static void supervise() {
        while (running) {
            try {
                Process child = new ProcessBuilder(binary.toString(), "--config", CONFIG.toString())
                        .inheritIO().start();
                synchronized (RustBackend.class) {
                    if (!running) {
                        child.destroy();
                        return;
                    }
                    process = child;
                }
                int exit = child.waitFor();
                if (process == child) process = null;
                if (running) {
                    LOGGER.error("Rust backend exited with code {}; restarting", exit);
                    Thread.sleep(1_000);
                }
            } catch (IOException exception) {
                LOGGER.error("Could not start the Rust backend; retrying", exception);
                pause();
            } catch (InterruptedException ignored) {
                if (!running) return;
            }
        }
    }

    private static void pause() {
        try {
            Thread.sleep(1_000);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    static void stop() {
        Process child;
        Thread thread;
        synchronized (RustBackend.class) {
            if (!running && supervisor == null) return;
            running = false;
            child = process;
            process = null;
            thread = supervisor;
            supervisor = null;
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
        } catch (IOException exception) {
            LOGGER.warn("Could not remove the temporary Rust server", exception);
        } finally {
            binary = null;
            directory = null;
        }
    }
}
