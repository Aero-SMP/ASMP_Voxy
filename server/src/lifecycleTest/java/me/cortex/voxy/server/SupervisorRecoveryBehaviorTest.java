package me.cortex.voxy.server;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import java.util.concurrent.locks.LockSupport;

/** Production owner, reader, failure policy, retry and cleanup; controlled process boundaries. */
final class SupervisorRecoveryBehaviorTest {
    static final String READY = "VOXY_READY udp_port=25587 alpn=voxy-regional cert_sha256=" + "ab".repeat(32);
    static final AtomicInteger assertions = new AtomicInteger(), living = new AtomicInteger(), maximum = new AtomicInteger();

    static void run() throws Exception {
        for (boolean stack : new boolean[]{false, true}) degradedLoggingRestarts(stack);
        for (var state : new RustBackend.State[]{RustBackend.State.STARTING, RustBackend.State.READY,
                RustBackend.State.EXITED, RustBackend.State.RETRYING, RustBackend.State.STOPPED}) debugHookFailure(state);
        for (String location : new String[]{"launch", "read", "logger", "debug"}) fatalOom(location);
        fatalDebugHooks();
        normalExitBeforeReady();
        independentPipeCleanup();
        unexpectedError();
        fallbackFailure();
        malformedReadiness();
        ioFailuresAndRetry();
        eofIsNotExit();
        failedOwnedChild();
        stopDuringLaunch();
        serializedStartStop();
        threadStartFailureAndPreparationFailure();
        interruptedStop();
        reentrantDiagnosticStop();
        realPipeRecovery();
        check(living.get() == 0 && maximum.get() <= 1, "orphan or overlapping controlled children");
        System.out.println("supervisor recovery assertions=" + assertions.get() + " maxLivingChildren=" + maximum.get());
    }

    static RustBackend.Owner owner(Child child) {
        var owned = new RustBackend.Owner(RustBackend.CONFIG);
        owned.launch = child::launch;
        return owned;
    }

    static void ready(RustBackend.Owner owned, Child child) {
        child.output.line(READY);
        RustBackend.start(owned);
        until(() -> RustBackend.ready() != null);
    }

    private static void degradedLoggingRestarts(boolean stack) throws Exception {
        var first = new Child(); var second = new Child();
        var owned = owner(first);
        AtomicInteger launches = new AtomicInteger(), reports = new AtomicInteger(), fallback = new AtomicInteger();
        CountDownLatch retry = new CountDownLatch(1), proceed = new CountDownLatch(1);
        owned.launch = () -> (launches.getAndIncrement() == 0 ? first : second).launch();
        owned.logger = action -> { reports.incrementAndGet(); if (stack) throw new StackOverflowError("injected logger"); throw new IllegalStateException("injected logger"); };
        owned.fallback = message -> fallback.incrementAndGet();
        owned.retry = () -> { retry.countDown(); awaitUninterruptibly(proceed); };
        ready(owned, first);
        until(() -> !owned.loggerEnabled);
        for (int i = 0; i < 10000; i++) first.output.line("ordinary line " + i);
        first.exit(23); await(retry);
        check(owned.exitCode == 23 && RustBackend.ready() == null, "exit status/readiness lost to logger failure");
        check(launches.get() == 1, "replacement bypassed retry boundary");
        second.output.line(READY); proceed.countDown();
        until(() -> RustBackend.ready() != null && launches.get() == 2);
        check(reports.get() == 1 && fallback.get() == 1, "broken logger/fallback recursively retried");
        check(first.output.bytesRead.get() > 100000, "degraded output was not drained");
        RustBackend.stop(); RustBackend.stop();
        check(second.destroyed.get() == 1 && owned.child == null && !owned.thread.isAlive(), "stop lost child/thread");
        check(first.output.closed.get() == 1 && second.output.closed.get() == 1, "output ownership not closed once");
    }

    private static void debugHookFailure(RustBackend.State target) throws Exception {
        var child = new Child(); var owned = owner(child);
        AtomicInteger broken = new AtomicInteger(), fallback = new AtomicInteger();
        CountDownLatch retry = new CountDownLatch(1), proceed = new CountDownLatch(1);
        owned.debug = action -> {
            if (owned.state == target) { broken.incrementAndGet(); throw new StackOverflowError("injected debug hook"); }
            action.run(); // Executes the actual facade on the debug-server test classpath.
        };
        owned.fallback = bytes -> fallback.incrementAndGet();
        owned.retry = () -> { retry.countDown(); awaitUninterruptibly(proceed); };
        ready(owned, child);
        if (target == RustBackend.State.STARTING || target == RustBackend.State.READY) until(() -> broken.get() == 1);
        if (target == RustBackend.State.EXITED || target == RustBackend.State.RETRYING) {
            child.exit(0); await(retry); owned.wanted = false; proceed.countDown();
        }
        RustBackend.stop();
        check(!owned.fatal && broken.get() == 1 && fallback.get() == 1, "debug " + target + " killed supervision");
        check(!owned.debugEnabled && owned.state == RustBackend.State.STOPPED, "debug route/state not settled");
    }

    private static void fatalOom(String location) throws Exception {
        var child = new Child(); var owned = owner(child);
        AtomicInteger retries = new AtomicInteger(); owned.retry = retries::incrementAndGet;
        switch (location) {
            case "launch" -> owned.launch = () -> { throw new OutOfMemoryError("constructed launch OOM"); };
            case "read" -> child.output.failure(new OutOfMemoryError("constructed read OOM"));
            case "logger" -> { child.output.line(READY); owned.logger = action -> { throw new OutOfMemoryError("constructed logger OOM"); }; }
            case "debug" -> { child.output.line(READY); owned.debug = action -> { if (owned.state == RustBackend.State.READY) throw new OutOfMemoryError("constructed debug OOM"); action.run(); }; }
            default -> throw new AssertionError();
        }
        RustBackend.start(owned); join(owned.thread);
        check(owned.state == RustBackend.State.FAILED && !owned.wanted && owned.child == null, "fatal " + location + " stale ownership");
        check(owned.failure == RustBackend.Failure.OUT_OF_MEMORY && retries.get() == 0 && RustBackend.ready() == null,
                "fatal " + location + " retried or lost primary classification");
        if (!location.equals("launch")) check(child.destroyed.get() == 1, "fatal child not terminated");
        var replacement = new Child(); var next = owner(replacement); ready(next, replacement); RustBackend.stop();
        check(next.state == RustBackend.State.STOPPED, "later explicit start remained stuck after fatal cleanup");
    }

    private static void unexpectedError() throws Exception {
        var child = new Child(); var owned = owner(child);
        child.output.failure(new StackOverflowError("outside reporting boundary"));
        RustBackend.start(owned); join(owned.thread);
        check(owned.state == RustBackend.State.FAILED && owned.failure == RustBackend.Failure.ERROR && owned.child == null,
                "arbitrary stack overflow swallowed as logger degradation");
    }

    private static void fatalDebugHooks() throws Exception {
        for (var target : new RustBackend.State[]{RustBackend.State.STARTING, RustBackend.State.EXITED,
                RustBackend.State.RETRYING, RustBackend.State.STOPPED}) {
            var child = new Child(); var owned = owner(child);
            AtomicInteger retries = new AtomicInteger();
            owned.retry = () -> { retries.incrementAndGet(); owned.wanted = false; };
            owned.debug = action -> { if (owned.state == target) throw new OutOfMemoryError("constructed hook OOM"); };
            if (target != RustBackend.State.STARTING) {
                ready(owned, child);
                if (target == RustBackend.State.STOPPED) RustBackend.stop();
                else child.exit(0);
            } else RustBackend.start(owned);
            join(owned.thread);
            check(owned.failure == RustBackend.Failure.OUT_OF_MEMORY && owned.state == RustBackend.State.FAILED
                    && !owned.wanted && owned.child == null && RustBackend.ready() == null && retries.get() == 0,
                    "fatal debug " + target + " retried or skipped cleanup");
        }
    }

    private static void normalExitBeforeReady() throws Exception {
        for (int code : new int[]{0, 23}) {
            var child = new Child(); var owned = owner(child);
            owned.launch = () -> { child.launch(); child.exit(code); return child; };
            owned.retry = () -> owned.wanted = false;
            RustBackend.start(owned); join(owned.thread);
            check(owned.exitCode == code && owned.child == null && RustBackend.ready() == null && !owned.fatal,
                    "exit before readiness misclassified");
        }
    }

    private static void independentPipeCleanup() throws Exception {
        var child = new Child(); var owned = owner(child);
        child.failPipeClose = true;
        ready(owned, child); RustBackend.stop();
        check(owned.state == RustBackend.State.FAILED && owned.failure == RustBackend.Failure.CLEANUP
                && child.otherClosed.get() == 2 && owned.child == null && !child.isAlive(),
                "failed pipe close prevented independent cleanup or retained dead child");
    }

    private static void fallbackFailure() throws Exception {
        var child = new Child(); var owned = owner(child); AtomicInteger fallback = new AtomicInteger();
        owned.logger = action -> { throw new IllegalStateException("logging"); };
        owned.fallback = bytes -> { fallback.incrementAndGet(); throw new StackOverflowError("fallback"); };
        ready(owned, child); until(() -> !owned.loggerEnabled);
        child.output.line("still draining"); RustBackend.stop();
        check(fallback.get() == 1 && owned.state == RustBackend.State.STOPPED, "fallback recursed or skipped stop");
        var fatalChild = new Child(); var fatal = owner(fatalChild);
        fatalChild.output.failure(new OutOfMemoryError("primary"));
        fatal.fallback = bytes -> { fallback.incrementAndGet(); throw new OutOfMemoryError("secondary fallback"); };
        RustBackend.start(fatal); join(fatal.thread);
        check(fatal.failure == RustBackend.Failure.OUT_OF_MEMORY && fatal.child == null && fallback.get() == 2,
                "fatal fallback lost classification/ownership or recursed");
    }

    private static void malformedReadiness() throws Exception {
        for (String line : new String[]{"VOXY_READY", READY.replace("25587", "0"), READY.replace("25587", "025587"),
                READY.replace("25587", "65536"), READY.replace("alpn=voxy-regional", "alpn=bad\ttoken"),
                READY.replace("ab".repeat(32), "AB".repeat(32)), READY.substring(0, READY.length() - 1), READY + " extra"}) {
            var child = new Child(); var owned = owner(child);
            CountDownLatch retry = new CountDownLatch(1); owned.retry = () -> { retry.countDown(); owned.wanted = false; };
            child.output.line(line); RustBackend.start(owned); await(retry); join(owned.thread);
            check(RustBackend.ready() == null && child.destroyed.get() == 1, "malformed readiness accepted: " + line);
        }
        var child = new Child(); var owned = owner(child); owned.retry = () -> owned.wanted = false;
        ready(owned, child); child.output.line(READY); join(owned.thread);
        check(owned.failure == RustBackend.Failure.RUNTIME && RustBackend.ready() == null, "duplicate ready accepted");
    }

    private static void ioFailuresAndRetry() throws Exception {
        for (boolean launch : new boolean[]{true, false}) {
            var child = new Child(); var owned = owner(child); AtomicInteger launches = new AtomicInteger();
            CountDownLatch paused = new CountDownLatch(1), release = new CountDownLatch(1);
            owned.launch = () -> { launches.incrementAndGet(); if (launch) throw new IOException("launch"); return child.launch(); };
            if (!launch) child.output.failure(new IOException("read"));
            owned.retry = () -> { paused.countDown(); awaitUninterruptibly(release); };
            RustBackend.start(owned); await(paused);
            check(launches.get() == 1 && owned.child == null, "IO retried before cleanup/delay");
            owned.wanted = false; release.countDown(); RustBackend.stop();
            check(launches.get() == 1, "stop restarted IO failure");
        }
    }

    private static void eofIsNotExit() throws Exception {
        var child = new Child(); var owned = owner(child);
        CountDownLatch retry = new CountDownLatch(1); owned.retry = () -> { retry.countDown(); owned.wanted = false; };
        ready(owned, child); child.output.end(); await(child.waiting);
        check(owned.child == child && child.isAlive(), "EOF relinquished live child");
        child.exit(7); await(retry); join(owned.thread);
        check(owned.exitCode == 7 && owned.child == null, "delayed EOF exit status lost");
        var stubborn = new Child(); stubborn.refuseGraceful = true; var next = owner(stubborn);
        ready(next, stubborn); RustBackend.stop();
        check(stubborn.destroyed.get() == 1 && stubborn.forced.get() == 1, "graceful/force order changed");
    }

    private static void failedOwnedChild() throws Exception {
        var child = new Child(); child.failTermination = true; var owned = owner(child);
        var directory = Files.createTempDirectory("voxy-owned-failure-");
        owned.directory = directory; owned.binary = Files.writeString(directory.resolve("child"), "owned");
        child.output.failure(new IOException("trigger termination"));
        RustBackend.start(owned); join(owned.thread);
        check(owned.state == RustBackend.State.FAILED && owned.child == child && child.isAlive(), "forgot live failed child");
        check(Files.exists(owned.binary), "deleted executable while child owned");
        boolean refused = false;
        try { RustBackend.start(owner(new Child())); } catch (IllegalStateException expected) { refused = true; }
        check(refused, "duplicate child allowed after failed termination");
        child.failTermination = false; RustBackend.stop();
        check(owned.child == null && !Files.exists(directory), "later cleanup did not resolve retained owner");
    }

    private static void stopDuringLaunch() throws Exception {
        var child = new Child(); var owned = owner(child);
        CountDownLatch entered = new CountDownLatch(1), proceed = new CountDownLatch(1);
        owned.launch = () -> { entered.countDown(); awaitUninterruptibly(proceed); return child.launch(); };
        RustBackend.start(owned); await(entered);
        Thread stop = new Thread(RustBackend::stop); stop.start(); until(() -> !owned.wanted);
        check(stop.isAlive(), "stop returned before in-progress launch relinquished ownership");
        RustBackend.start(owner(new Child())); // Existing owner blocks a second start.
        proceed.countDown(); join(stop);
        check(child.destroyed.get() == 1 && owned.child == null && !owned.thread.isAlive(), "post-launch stop orphaned child");
    }

    private static void serializedStartStop() throws Exception {
        var child = new Child(); var owned = owner(child);
        CountDownLatch entering = new CountDownLatch(1), proceed = new CountDownLatch(1);
        RustBackend.startThread = thread -> { entering.countDown(); await(proceed); thread.start(); };
        Thread start = new Thread(() -> RustBackend.start(owned)); start.start(); await(entering);
        Thread stop = new Thread(RustBackend::stop); stop.start(); until(() -> stop.getState() == Thread.State.BLOCKED);
        proceed.countDown(); join(start); join(stop); RustBackend.startThread = Thread::start;
        check(!owned.thread.isAlive() && owned.child == null && !owned.wanted, "NEW supervisor escaped stop");
    }

    private static void threadStartFailureAndPreparationFailure() throws Exception {
        var owned = owner(new Child());
        RustBackend.startThread = thread -> { throw new OutOfMemoryError("constructed thread start"); };
        try { RustBackend.start(owned); throw new AssertionError("start did not fail"); }
        catch (OutOfMemoryError expected) { check(owned.state == RustBackend.State.FAILED && !owned.wanted, "failed thread start stale state"); }
        finally { RustBackend.startThread = Thread::start; }
        var bad = new RustBackend.Owner(Path.of("not-present-supervisor-test-config")); RustBackend.start(bad); join(bad.thread);
        check(bad.state == RustBackend.State.FAILED && bad.child == null && bad.binary == null, "extraction rollback failed");
    }

    private static void interruptedStop() throws Exception {
        var child = new Child(); var owned = owner(child); ready(owned, child);
        AtomicBoolean restored = new AtomicBoolean();
        Thread caller = new Thread(() -> { Thread.currentThread().interrupt(); RustBackend.stop(); restored.set(Thread.currentThread().isInterrupted()); });
        caller.start(); join(caller);
        check(restored.get() && !owned.thread.isAlive() && owned.child == null, "interrupted join skipped ownership cleanup");
    }

    private static void reentrantDiagnosticStop() throws Exception {
        for (var target : new RustBackend.State[]{RustBackend.State.READY, RustBackend.State.STOPPED}) {
            var child = new Child(); var owned = owner(child);
            owned.debug = action -> { if (owned.state == target) RustBackend.stop(); action.run(); };
            child.output.line(READY); RustBackend.start(owned);
            if (target == RustBackend.State.STOPPED) { until(() -> RustBackend.ready() != null); RustBackend.stop(); }
            join(owned.thread);
            check(owned.child == null && owned.state == RustBackend.State.STOPPED, "reentrant logger stop self-joined or changed terminal state");
        }
    }

    private static void realPipeRecovery() throws Exception {
        Path marker = Files.createTempFile("voxy-child-drain-", ".marker"); Files.delete(marker);
        var owned = new RustBackend.Owner(RustBackend.CONFIG);
        AtomicInteger launches = new AtomicInteger(), reports = new AtomicInteger();
        CountDownLatch retry = new CountDownLatch(1), proceed = new CountDownLatch(1);
        List<Process> children = new CopyOnWriteArrayList<>();
        owned.launch = () -> {
            for (Process prior : children) check(!prior.isAlive(), "real child overlap");
            Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp", System.getProperty("java.class.path"), SupervisorChildBehaviorTest.class.getName(), marker.toString()).start();
            children.add(child); launches.incrementAndGet(); return child;
        };
        owned.logger = action -> { reports.incrementAndGet(); throw new StackOverflowError("real pipe diagnostic fault"); };
        owned.retry = () -> { retry.countDown(); awaitUninterruptibly(proceed); };
        try {
            RustBackend.start(owned); until(() -> RustBackend.ready() != null && Files.exists(marker));
            Process first = children.getFirst(); first.getOutputStream().write(1); first.getOutputStream().flush();
            await(retry); check(owned.exitCode == 23 && !first.isAlive(), "real exit/retry missing");
            Files.delete(marker); proceed.countDown();
            until(() -> launches.get() == 2 && RustBackend.ready() != null && Files.exists(marker));
            RustBackend.stop();
            check(reports.get() == 1 && children.stream().noneMatch(Process::isAlive), "real pipe recovery/orphan failure");
            System.out.println("real noisy subprocess pipes: drained 20k lines across two children; exit 23; no overlap/orphan");
        } finally { owned.wanted = false; proceed.countDown(); RustBackend.stop(); Files.deleteIfExists(marker); }
    }

    static void check(boolean value, String message) { assertions.incrementAndGet(); if (!value) throw new AssertionError(message); }
    static void await(CountDownLatch latch) {
        try { check(latch.await(10, TimeUnit.SECONDS), "barrier timeout"); }
        catch (InterruptedException failure) { throw new AssertionError(failure); }
    }
    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        for (;;) { try { check(latch.await(10, TimeUnit.SECONDS), "uninterruptible barrier timeout"); break; }
            catch (InterruptedException ignored) { interrupted = true; } }
        if (interrupted) Thread.currentThread().interrupt();
    }
    static void until(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("condition timeout: " + RustBackend.status());
            LockSupport.parkNanos(100_000);
        }
    }
    static void join(Thread thread) throws Exception {
        thread.join(10000); check(!thread.isAlive(), "thread timeout " + Arrays.toString(thread.getStackTrace()));
    }

    static final class Child extends Process {
        final Output output = new Output();
        final CountDownLatch exited = new CountDownLatch(1), waiting = new CountDownLatch(1);
        final AtomicInteger destroyed = new AtomicInteger(), forced = new AtomicInteger();
        final AtomicInteger otherClosed = new AtomicInteger();
        volatile boolean alive, refuseGraceful, failTermination, failPipeClose;
        volatile int code;
        Process launch() { check(!alive, "same fake child launched twice"); alive = true; maximum.accumulateAndGet(living.incrementAndGet(), Math::max); return this; }
        void exit(int code) { if (!alive) return; this.code = code; alive = false; living.decrementAndGet(); output.end(); exited.countDown(); }
        @Override public boolean isAlive() { return alive; }
        @Override public void destroy() { destroyed.incrementAndGet(); if (failTermination) throw new IllegalStateException("destroy refused"); if (!refuseGraceful) exit(143); }
        @Override public Process destroyForcibly() { forced.incrementAndGet(); if (failTermination) throw new IllegalStateException("force refused"); exit(137); return this; }
        @Override public int waitFor() throws InterruptedException { waiting.countDown(); exited.await(); return code; }
        @Override public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            check(timeout == 10 && unit == TimeUnit.SECONDS, "graceful timeout changed");
            if (refuseGraceful) return false; return exited.await(timeout, unit);
        }
        @Override public int exitValue() { if (alive) throw new IllegalThreadStateException(); return code; }
        @Override public InputStream getInputStream() {
            if (!failPipeClose) return output;
            return new FilterInputStream(output) { @Override public void close() throws IOException { output.close(); throw new IOException("close failure"); } };
        }
        @Override public InputStream getErrorStream() { return new ByteArrayInputStream(new byte[0]) {
            @Override public void close() { otherClosed.incrementAndGet(); }
        }; }
        @Override public OutputStream getOutputStream() { return new ByteArrayOutputStream() {
            @Override public void close() { otherClosed.incrementAndGet(); }
        }; }
    }

    static final class Output extends InputStream {
        final BlockingQueue<Object> chunks = new LinkedBlockingQueue<>();
        final AtomicInteger bytesRead = new AtomicInteger(), closed = new AtomicInteger();
        byte[] current; int offset; boolean eof;
        void line(String text) { chunks.add((text + "\n").getBytes(StandardCharsets.UTF_8)); }
        void failure(Throwable failure) { chunks.add(failure); }
        void end() { chunks.add(new byte[0]); }
        @Override public int read() throws IOException { byte[] one = new byte[1]; return read(one, 0, 1) < 0 ? -1 : one[0] & 255; }
        @Override public int read(byte[] into, int start, int length) throws IOException {
            if (eof) return -1;
            if (current == null || offset == current.length) {
                Object next;
                try { next = chunks.take(); } catch (InterruptedException interrupted) { throw new InterruptedIOException(); }
                if (next instanceof IOException failure) throw failure;
                if (next instanceof RuntimeException failure) throw failure;
                if (next instanceof Error failure) throw failure;
                current = (byte[]) next; offset = 0;
                if (current.length == 0) { eof = true; return -1; }
            }
            int count = Math.min(length, current.length - offset); System.arraycopy(current, offset, into, start, count);
            offset += count; bytesRead.addAndGet(count); return count;
        }
        @Override public void close() { check(closed.incrementAndGet() == 1, "pipe double-close"); end(); }
    }
}
