package me.cortex.voxy.server;

import me.cortex.voxy.network.QuicEndpointPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** One Java-owned supervisor/child; failed ownership is retained until exit is proven. */
final class RustBackend {
    private static final Logger LOGGER = LoggerFactory.getLogger("Voxy Rust Backend");
    private static final String READY_MARKER = "VOXY_READY";
    static final Path CONFIG = Path.of("voxy-rust.toml").toAbsolutePath();
    // Minecraft redirects System.err into Log4j. This descriptor bypasses that redirection.
    // Never close it. Output remains best-effort, not allocation-free or exhaustion-proof.
    private static final OutputStream RAW_ERROR = new FileOutputStream(FileDescriptor.err);
    private static final byte[] LOG_DISABLED = "Voxy Rust: diagnostic route disabled; supervision continues.\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] FATAL = "Voxy Rust: supervisor FAILED; manual recovery/server restart required.\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] OWNED = "Voxy Rust: cleanup incomplete; child/executable ownership retained; restart refused.\n".getBytes(StandardCharsets.US_ASCII);

    enum State { STARTING, READY, EXITED, RETRYING, STOPPING, STOPPED, FAILED }
    enum Failure { NONE, IO, RUNTIME, INTERRUPTED, OUT_OF_MEMORY, ERROR, TERMINATION, CLEANUP, THREAD_START }
    record Status(State state, boolean wanted, boolean supervisorAlive, long pid, boolean childAlive,
                  Integer exitCode, Failure failure, boolean loggerEnabled, boolean debugEnabled,
                  boolean ownsExecutable, ReadyRecord ready) {}

    /** Only this context may mutate its child, readiness and extracted files. */
    static final class Owner {
        final Path config;
        final Object termination = new Object();
        volatile boolean wanted = true;
        volatile State state = State.STARTING;
        volatile Failure failure = Failure.NONE;
        volatile boolean fatal;
        volatile Process child;
        volatile ReadyRecord ready;
        volatile Integer exitCode;
        volatile Path directory, binary;
        Thread thread;
        volatile boolean loggerEnabled = true, debugEnabled = true;
        // Narrow boundaries used by the production-behavior fixtures; no alternate supervisor.
        Callable<Process> launch;
        Runnable retry;
        Consumer<Runnable> logger = Runnable::run, debug = Runnable::run;
        Consumer<byte[]> fallback = RustBackend::rawWrite;
        Owner(Path config) { this.config = config; }
    }

    private static volatile Owner owner;
    static Consumer<Thread> startThread = Thread::start;

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(RustBackend::stop, "Voxy Rust shutdown"));
    }
    private RustBackend() {}

    static void start() { start(new Owner(CONFIG)); }

    static void start(Owner next) {
        Throwable failure = null;
        synchronized (RustBackend.class) {
            Owner previous = owner;
            if (previous != null) {
                if (alive(previous)) return;
                if (previous.child != null || previous.directory != null || previous.binary != null) {
                    throw new IllegalStateException("Rust supervisor still owns cleanup; restart refused");
                }
            }
            owner = next;
            // Publication and start are serialized with stop; a NEW thread cannot escape it.
            try {
                next.thread = Thread.ofPlatform().daemon().name("Voxy Rust supervisor")
                        .unstarted(() -> supervise(next));
                startThread.accept(next.thread);
            }
            catch (RuntimeException | Error error) {
                fail(next, error, Failure.THREAD_START);
                failure = error;
            }
        }
        if (failure != null) {
            terminalReport(next);
            if (failure instanceof Error error) throw error;
            throw (RuntimeException) failure;
        }
    }

    private static void extract(Owner owned) throws IOException {
        if (!Files.isRegularFile(owned.config) || !Files.isReadable(owned.config)) {
            throw new IOException("missing or unreadable configuration " + owned.config);
        }
        owned.directory = Files.createTempDirectory("voxy-rust-");
        owned.binary = owned.directory.resolve("voxy-rust-server");
        try (InputStream input = RustBackend.class.getResourceAsStream("/native/linux-x86_64/voxy-rust-server")) {
            if (input == null) throw new IOException("embedded Rust server is missing");
            Files.copy(input, owned.binary);
        }
        Files.setPosixFilePermissions(owned.binary, PosixFilePermissions.fromString("rwx------"));
    }

    private static Process launch(Owner owned) throws Exception {
        if (owned.launch != null) return owned.launch.call();
        var builder = new ProcessBuilder(owned.binary.toString(), "--config", owned.config.toString())
                .redirectErrorStream(true);
        builder.environment().put("MALLOC_ARENA_MAX", "2");
        return builder.start();
    }

    private static void supervise(Owner owned) {
        try {
            if (!owned.wanted) return;
            if (owned.launch == null) extract(owned);
            event(owned, State.STARTING);
            while (owned.wanted) {
                try {
                    // Assignment immediately retains even a child returned after stop was requested.
                    owned.child = launch(owned);
                    owned.exitCode = null;
                    if (!owned.wanted) break;
                    Process child = owned.child;
                    var output = new BufferedReader(new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8));
                    String line;
                    while (owned.wanted && (line = output.readLine()) != null) {
                        boolean announced = line.equals(READY_MARKER) || line.startsWith(READY_MARKER + " ");
                        if (announced) {
                            ReadyRecord record = parseReady(line);
                            synchronized (RustBackend.class) {
                                if (owned.wanted && owned.child == child) {
                                    if (owned.ready != null) throw new IllegalStateException("duplicate Rust readiness");
                                    owned.ready = record;
                                    owned.state = State.READY;
                                }
                            }
                        }
                        // Control processing precedes presentation; degraded logging still drains.
                        String message = line;
                        if (owned.loggerEnabled) report(owned, false, () -> LOGGER.info("[Rust] {}", message));
                        if (announced && owned.wanted) reportState(owned);
                    }
                    // EOF is not proof of exit. stop() can terminate this owned child while we wait.
                    if (owned.wanted) owned.exitCode = child.waitFor();
                    owned.ready = null;
                    if (!terminate(owned)) break;
                    if (owned.wanted) event(owned, State.EXITED);
                } catch (IOException | RuntimeException failure) {
                    owned.ready = null;
                    owned.failure = classify(failure);
                    if (!terminate(owned)) break;
                    if (owned.wanted && owned.loggerEnabled) report(owned, false,
                            () -> LOGGER.error("Rust backend failed; retrying after one second", failure));
                } catch (InterruptedException interrupted) {
                    owned.ready = null;
                    owned.failure = Failure.INTERRUPTED;
                    if (!terminate(owned)) break;
                    // Interruption is consumed for a recoverable retry, retained on terminal exit.
                    if (!owned.wanted) { Thread.currentThread().interrupt(); break; }
                }
                if (owned.wanted) {
                    event(owned, State.RETRYING);
                    if (owned.retry != null) owned.retry.run();
                    else pause(owned);
                }
            }
        } catch (Throwable failure) {
            // Not an Error retry loop: any escape is terminal and never reaches the logger.
            fail(owned, failure, classify(failure));
        } finally {
            owned.wanted = false;
            owned.ready = null;
            if (terminate(owned)) cleanup(owned);
            owned.state = owned.fatal ? State.FAILED : State.STOPPED;
            terminalReport(owned);
        }
    }

    private static void event(Owner owned, State state) {
        synchronized (RustBackend.class) {
            if (!owned.wanted) return;
            owned.state = state;
        }
        reportState(owned);
    }

    private static void reportState(Owner owned) {
        if (owned.debugEnabled) report(owned, true, () -> ServerDebug.rustState(snapshot(owned)));
    }

    private static void report(Owner owned, boolean debug, Runnable action) {
        if (debug ? !owned.debugEnabled : !owned.loggerEnabled) return;
        boolean interrupted = Thread.interrupted();
        try { (debug ? owned.debug : owned.logger).accept(action); }
        catch (RuntimeException | StackOverflowError brokenDiagnostic) {
            if (debug) owned.debugEnabled = false;
            else owned.loggerEnabled = false;
            // Failure of this fallback must not recursively call either logging route.
            try { owned.fallback.accept(LOG_DISABLED); }
            catch (RuntimeException | StackOverflowError ignored) {}
            // Other Errors, including logging OOM, propagate to the terminal finalizer.
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }

    private static void rawWrite(byte[] message) {
        try { RAW_ERROR.write(message); RAW_ERROR.flush(); }
        catch (IOException ignored) {}
    }

    private static void terminalReport(Owner owned) {
        if (!owned.fatal) {
            try { reportState(owned); }
            catch (Throwable failure) { fail(owned, failure, classify(failure)); }
        }
        if (owned.fatal) {
            try { owned.fallback.accept(owned.child != null || owned.binary != null || owned.directory != null ? OWNED : FATAL); }
            catch (Throwable ignored) {} // Last-resort fatal sink only; never recurse into logging.
        }
    }

    private static Failure classify(Throwable failure) {
        if (failure instanceof OutOfMemoryError) return Failure.OUT_OF_MEMORY;
        if (failure instanceof Error) return Failure.ERROR;
        if (failure instanceof IOException) return Failure.IO;
        if (failure instanceof InterruptedException) return Failure.INTERRUPTED;
        return Failure.RUNTIME;
    }

    private static void fail(Owner owned, Throwable failure, Failure category) {
        if (!owned.fatal) owned.failure = failure instanceof Error ? classify(failure) : category;
        owned.fatal = true;
        owned.wanted = false;
        owned.ready = null;
        owned.state = State.FAILED;
    }

    /** Only this lock serializes termination; no lifecycle lock or thread join is held here. */
    private static boolean terminate(Owner owned) {
        synchronized (owned.termination) {
            Process child = owned.child;
            if (child == null) return true;
            owned.ready = null;
            boolean interrupted = Thread.interrupted();
            try {
                if (child.isAlive()) {
                    try {
                        child.destroy();
                        if (!child.waitFor(10, TimeUnit.SECONDS)) child.destroyForcibly();
                    } catch (InterruptedException ignored) {
                        interrupted = true;
                        child.destroyForcibly();
                    } catch (RuntimeException | Error failure) {
                        // A broken graceful route does not prevent independent force cleanup.
                        if (failure instanceof Error) fail(owned, failure, classify(failure));
                        child.destroyForcibly();
                    }
                    while (child.isAlive()) {
                        try { child.waitFor(); }
                        catch (InterruptedException ignored) { interrupted = true; }
                    }
                }
                owned.exitCode = child.exitValue();
                // Exit is proven before relinquishing ownership, even if a pipe close fails.
                for (int pipe = 0; pipe < 3; pipe++) closePipe(owned, child, pipe);
                owned.child = null;
                return true;
            } catch (Throwable failure) {
                fail(owned, failure, Failure.TERMINATION);
                return false; // Retain child and executable; never launch a duplicate.
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
    }

    private static void closePipe(Owner owned, Process child, int pipe) {
        try {
            switch (pipe) {
                case 0 -> child.getInputStream().close();
                case 1 -> child.getErrorStream().close();
                case 2 -> child.getOutputStream().close();
                default -> throw new AssertionError();
            }
        }
        catch (Throwable failure) { fail(owned, failure, Failure.CLEANUP); }
    }

    private static void pause(Owner owned) {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (owned.wanted) {
            long remaining = until - System.nanoTime();
            if (remaining <= 0) return;
            try { TimeUnit.NANOSECONDS.sleep(remaining); }
            catch (InterruptedException ignored) {
                if (!owned.wanted) { Thread.currentThread().interrupt(); return; }
            }
        }
    }

    static ReadyRecord ready() {
        Owner owned = owner;
        if (owned == null) return null;
        synchronized (RustBackend.class) {
            Process child = owned.child;
            ReadyRecord record = owned.ready;
            return owned.wanted && owned.state == State.READY && alive(owned)
                    && record != null && child != null && child.isAlive()
                    && owned.child == child && owned.ready == record ? record : null;
        }
    }

    static Status status() {
        Owner owned = owner;
        return owned == null ? new Status(State.STOPPED, false, false, -1, false, null,
                Failure.NONE, true, true, false, null) : snapshot(owned);
    }

    private static Status snapshot(Owner owned) {
        Process child = owned.child;
        long pid = -1;
        boolean alive = false;
        if (child != null) {
            alive = child.isAlive();
            try { pid = child.pid(); } catch (UnsupportedOperationException ignored) {}
        }
        return new Status(owned.state, owned.wanted, alive(owned), pid, alive,
                owned.exitCode, owned.failure, owned.loggerEnabled, owned.debugEnabled,
                owned.binary != null || owned.directory != null,
                owned.wanted && owned.state == State.READY && alive ? owned.ready : null);
    }

    static void stop() {
        Owner owned;
        synchronized (RustBackend.class) {
            owned = owner;
            if (owned == null) return;
            owned.wanted = false;
            owned.ready = null;
            if (!owned.fatal && owned.state != State.STOPPED) owned.state = State.STOPPING;
        }
        // Reentrant diagnostic stop requests must not join their own supervisor.
        if (owned.thread == Thread.currentThread()) return;
        boolean interrupted = Thread.interrupted();
        try {
            if (owned.thread != null) owned.thread.interrupt();
            terminate(owned);
            while (alive(owned)) {
                try { owned.thread.join(); }
                catch (InterruptedException ignored) { interrupted = true; }
            }
            // A previous failed-owned terminal context may be cleaned by a later explicit stop.
            if (terminate(owned)) cleanup(owned);
            owned.state = owned.fatal ? State.FAILED : State.STOPPED;
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private static void cleanup(Owner owned) {
        synchronized (owned.termination) {
            if (owned.child != null || alive(owned) && owned.thread != Thread.currentThread()) return;
            try {
                if (owned.binary != null) { Files.deleteIfExists(owned.binary); owned.binary = null; }
                if (owned.directory != null) { Files.deleteIfExists(owned.directory); owned.directory = null; }
            } catch (Throwable failure) { fail(owned, failure, Failure.CLEANUP); }
        }
    }

    private static boolean alive(Owner owned) { return owned.thread != null && owned.thread.isAlive(); }

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
