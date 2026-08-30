package me.cortex.voxy.common.thread;

import me.cortex.voxy.common.util.Pair;
import me.cortex.voxy.common.util.TrackedObject;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

public class PerThreadContextExecutor extends TrackedObject {
    private static final AtomicLong CONTEXT_IDS = new AtomicLong();
    private static final ThreadLocal<LongSupplier> THREAD_CTX = ThreadLocal.withInitial(() -> {
        long id = CONTEXT_IDS.getAndIncrement();
        return () -> id;
    });
    private final WeakConcurrentCleanableHashMap<LongSupplier, Pair<Runnable, Runnable>> contexts = new WeakConcurrentCleanableHashMap<>(this::ctxCleaner);
    private final Supplier<Pair<Runnable, Runnable>> contextFactory;
    private final Consumer<Exception> exceptionHandler;

    private final AtomicInteger currentRunning = new AtomicInteger();
    private volatile boolean isLive = true;

    PerThreadContextExecutor(Supplier<Pair<Runnable, Runnable>> ctxFactory, Consumer<Exception> exceptionHandler) {
        this.contextFactory = ctxFactory;
        this.exceptionHandler = exceptionHandler;
    }

    private void ctxCleaner(Pair<Runnable, Runnable> ctx) {
        try {
            ctx.right().run();
        } catch (Exception e) {
            this.exceptionHandler.accept(e);
        }
    }

    boolean run() {
        this.currentRunning.incrementAndGet();
        if (!this.isLive) {
            this.currentRunning.decrementAndGet();
            this.exceptionHandler.accept(new IllegalStateException("Executor is in shutdown"));
            return false;
        }
        var ctx = this.contexts.computeIfAbsent(THREAD_CTX.get(), this.contextFactory);
        try {
            ctx.left().run();
        } catch (Exception e) {
            this.exceptionHandler.accept(e);
        }
        this.currentRunning.decrementAndGet();
        return true;
    }

    public void shutdown() {
        if (!this.isLive) {
            throw new IllegalStateException("Tried shutting down a executor twice");
        }
        this.isLive = false;
        while (this.currentRunning.get() != 0) {
            Thread.onSpinWait();//TODO: maybe add a sleep or something
        }
        for (var ctx : this.contexts.clear()) {
            ctx.right().run();
        }

        this.free0();
    }

    @Override
    public void free() {
        this.shutdown();
    }
}
