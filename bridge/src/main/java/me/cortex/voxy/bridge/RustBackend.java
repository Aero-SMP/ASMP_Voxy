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
    private static final long START_TIMEOUT_SECONDS = 120;
    static final Path CONFIG = Path.of("voxy-rust.toml").toAbsolutePath();

    private static volatile boolean running;
    private static volatile boolean ready;
    private static volatile Process process;
    private static Thread supervisor;
    private static Path directory;
    private static Path binary;
    private static Throwable startupFailure;
    private static byte expectedTransport;

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(RustBackend::stop, "Voxy Rust shutdown"));
    }

    private RustBackend() {}

    static void start(byte transport) {
        String readyLine = switch (transport) {
            case TransportPayload.DIRECT -> DIRECT_READY;
            case TransportPayload.MINECRAFT -> MINECRAFT_READY;
            default -> throw new IllegalArgumentException("invalid Rust backend transport");
        };
        Thread thread;
        synchronized (RustBackend.class) {
            if (isReady()) {
                if (expectedTransport != transport) {
                    throw new IllegalStateException("Rust backend is running in another transport mode");
                }
                return;
            }
            if (running) throw new IllegalStateException("Rust backend is already starting");
            extract();
            running = true;
            ready = false;
            startupFailure = null;
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

        Throwable failure = awaitInitialReadiness(readyLine);
        if (failure == null) return;
        stop();
        throw new IllegalStateException("Rust backend did not become ready", failure);
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

    private static Throwable awaitInitialReadiness(String readyLine) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(START_TIMEOUT_SECONDS);
        synchronized (RustBackend.class) {
            while (running && !ready && startupFailure == null) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return new IOException("timed out after " + START_TIMEOUT_SECONDS
                            + " seconds waiting for the Rust listener");
                }
                try {
                    TimeUnit.NANOSECONDS.timedWait(RustBackend.class, remaining);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return exception;
                }
            }
            if (ready) return null;
            if (startupFailure != null) return startupFailure;
            return new IOException("Rust backend stopped before announcing " + readyLine);
        }
    }

    private static void supervise() {
        boolean becameReady = false;
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
                Process launched = child;
                Thread.ofPlatform().daemon().name("Voxy Rust readiness watchdog")
                        .start(() -> enforceReadinessDeadline(launched));
                try (var output = new BufferedReader(new InputStreamReader(
                        child.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = output.readLine()) != null) {
                        LOGGER.info("[Rust] {}", line);
                        if (line.equals(expectedReadyLine())) {
                            synchronized (RustBackend.class) {
                                if (running && process == child) {
                                    ready = true;
                                    becameReady = true;
                                    RustBackend.class.notifyAll();
                                }
                            }
                        }
                    }
                }
                int exit = child.waitFor();
                clearProcess(child);
                if (running) {
                    if (!becameReady) {
                        failInitial(new IOException(
                                "Rust backend exited with code " + exit + " before readiness"));
                        return;
                    }
                    LOGGER.error("Rust backend exited with code {}; restarting", exit);
                    pause();
                }
            } catch (IOException | RuntimeException exception) {
                if (child != null) child.destroy();
                clearProcess(child);
                if (!becameReady) {
                    failInitial(exception);
                    return;
                }
                LOGGER.error("Could not restart the Rust backend; retrying", exception);
                pause();
            } catch (InterruptedException ignored) {
                if (!running) return;
                if (child != null) child.destroy();
                clearProcess(child);
            }
        }
    }

    private static String expectedReadyLine() {
        return expectedTransport == TransportPayload.DIRECT
                ? DIRECT_READY : MINECRAFT_READY;
    }

    /** A post-crash child that starts but wedges before binding must not stop supervision. */
    private static void enforceReadinessDeadline(Process child) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(START_TIMEOUT_SECONDS);
        synchronized (RustBackend.class) {
            while (running && process == child && child.isAlive() && !ready) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    LOGGER.error("Rust backend did not become ready after {} seconds; restarting",
                            START_TIMEOUT_SECONDS);
                    child.destroy();
                    return;
                }
                try {
                    TimeUnit.NANOSECONDS.timedWait(RustBackend.class, remaining);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private static void clearProcess(Process child) {
        synchronized (RustBackend.class) {
            if (process == child) process = null;
            ready = false;
            RustBackend.class.notifyAll();
        }
    }

    private static void failInitial(Throwable failure) {
        synchronized (RustBackend.class) {
            startupFailure = failure;
            running = false;
            ready = false;
            RustBackend.class.notifyAll();
        }
        LOGGER.error("Rust backend failed before its listener became ready", failure);
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
        synchronized (RustBackend.class) {
            startupFailure = null;
        }
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
