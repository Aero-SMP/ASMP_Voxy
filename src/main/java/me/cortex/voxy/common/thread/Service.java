package me.cortex.voxy.common.thread;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.Pair;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

public final class Service {
    private final Supplier<Pair<Runnable, Runnable>> contextFactory;
    private final Map<Thread, Pair<Runnable, Runnable>> contexts = new HashMap<>();
    private final ServiceManager manager;
    private final Semaphore tasks = new Semaphore(0);
    private final Object state = new Object();

    final long weight;
    final String name;
    final BooleanSupplier limiter;
    long selectionCredit;

    private int running;
    private volatile boolean stopping;

    Service(Supplier<Pair<Runnable, Runnable>> contextFactory, ServiceManager manager,
            long weight, String name, BooleanSupplier limiter) {
        this.contextFactory = contextFactory;
        this.manager = manager;
        this.weight = weight;
        this.name = name;
        this.limiter = limiter;
    }

    public void execute() {
        synchronized (this.state) {
            if (this.stopping) {
                Logger.error("Tried executing on a stopped service");
                return;
            }
            this.tasks.release();
            this.manager.execute();
            this.state.notifyAll();
        }
    }

    boolean claimJob() {
        synchronized (this.state) {
            if (this.stopping || !this.tasks.tryAcquire()) return false;
            this.running++;
            return true;
        }
    }

    void runClaimedJob() {
        try {
            try {
                Pair<Runnable, Runnable> context;
                synchronized (this.contexts) {
                    context = this.contexts.computeIfAbsent(Thread.currentThread(),
                            ignored -> this.contextFactory.get());
                }
                context.left().run();
            } catch (Exception exception) {
                this.manager.handleException(this, exception);
            }
        } finally {
            this.manager.removeJobs(1);
            synchronized (this.state) {
                this.running--;
                this.state.notifyAll();
            }
        }
    }

    public boolean isLive() {
        return !this.stopping;
    }

    public int numJobs() {
        return this.tasks.availablePermits();
    }

    public void blockTillEmpty() {
        synchronized (this.state) {
            while (this.isLive() && this.numJobs() != 0) {
                try {
                    this.state.wait(10);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted waiting for service", exception);
                }
            }
        }
    }

    public int shutdown() {
        synchronized (this.state) {
            if (this.stopping) throw new IllegalStateException("Service is not live");
            this.stopping = true;
        }
        this.manager.removeService(this);
        synchronized (this.state) {
            while (this.running != 0) {
                try {
                    this.state.wait();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted stopping service", exception);
                }
            }
        }

        int remaining = this.tasks.drainPermits();
        this.manager.removeJobs(remaining);
        synchronized (this.contexts) {
            for (Pair<Runnable, Runnable> context : this.contexts.values()) {
                try {
                    context.right().run();
                } catch (Exception exception) {
                    this.manager.handleException(this, exception);
                }
            }
            this.contexts.clear();
        }
        return remaining;
    }

    public boolean steal() {
        if (!this.tasks.tryAcquire()) return false;
        this.manager.removeJobs(1);
        synchronized (this.state) {
            this.state.notifyAll();
        }
        return true;
    }

    public int drain() {
        int drained = this.tasks.drainPermits();
        if (drained != 0) this.manager.removeJobs(drained);
        synchronized (this.state) {
            this.state.notifyAll();
        }
        return drained;
    }
}
