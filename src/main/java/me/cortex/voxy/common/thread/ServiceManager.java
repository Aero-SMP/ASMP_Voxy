package me.cortex.voxy.common.thread;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.Pair;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/** Deterministically shares worker time between independently queued services. */
public final class ServiceManager {
    private final IntConsumer jobRelease;
    private final AtomicInteger totalJobs = new AtomicInteger();
    private final List<Service> services = new ArrayList<>();
    private boolean shutdown;

    public ServiceManager(IntConsumer jobRelease) {
        this.jobRelease = jobRelease;
    }

    public Service createServiceNoCleanup(Supplier<Runnable> factory, long weight) {
        return this.createServiceNoCleanup(factory, weight, "");
    }

    public Service createServiceNoCleanup(Supplier<Runnable> factory, long weight, String name) {
        return this.createService(() -> new Pair<>(factory.get(), () -> {}), weight, name);
    }

    public Service createService(Supplier<Pair<Runnable, Runnable>> factory, long weight) {
        return this.createService(factory, weight, "");
    }

    public Service createService(Supplier<Pair<Runnable, Runnable>> factory, long weight, String name) {
        return this.createService(factory, weight, name, null);
    }

    public synchronized Service createService(Supplier<Pair<Runnable, Runnable>> factory,
                                               long weight, String name, BooleanSupplier limiter) {
        if (this.shutdown) throw new IllegalStateException("Service manager is shut down");
        if (weight <= 0) throw new IllegalArgumentException("Service weight must be positive");
        Service service = new Service(factory, this, weight, name, limiter);
        this.services.add(service);
        return service;
    }

    public int tryRunAJob() {
        Service selected;
        synchronized (this) {
            while (true) {
                Selection selection = this.select();
                if (selection.service == null) return selection.status;
                if (selection.service.claimJob()) {
                    selected = selection.service;
                    break;
                }
            }
        }
        selected.runClaimedJob();
        return 0;
    }

    private Selection select() {
        if (this.services.isEmpty() || this.totalJobs.get() == 0) return new Selection(null, 1);
        Service selected = null;
        long totalWeight = 0;
        boolean limited = false;
        for (Service service : this.services) {
            int jobs = service.numJobs();
            if (!service.isLive() || jobs == 0) {
                service.selectionCredit = 0;
                continue;
            }
            if (service.limiter != null && !service.limiter.getAsBoolean()) {
                service.selectionCredit = 0;
                limited = true;
                continue;
            }
            service.selectionCredit = Math.addExact(service.selectionCredit, service.weight);
            totalWeight = Math.addExact(totalWeight, service.weight);
            if (selected == null || service.selectionCredit > selected.selectionCredit) {
                selected = service;
            }
        }
        if (selected == null) return new Selection(null, limited ? 3 : 2);
        selected.selectionCredit -= totalWeight;
        return new Selection(selected, 0);
    }

    public synchronized void shutdown() {
        if (this.shutdown) throw new IllegalStateException("Service manager already shut down");
        if (!this.services.isEmpty() || this.totalJobs.get() != 0) {
            throw new IllegalStateException("Services still active during manager shutdown");
        }
        this.shutdown = true;
    }

    synchronized void removeService(Service service) {
        if (!this.services.remove(service)) throw new IllegalStateException("Unknown service");
    }

    void execute() {
        this.totalJobs.incrementAndGet();
        this.jobRelease.accept(1);
    }

    void removeJobs(int count) {
        if (this.totalJobs.addAndGet(-count) < 0) {
            throw new IllegalStateException("Job count became negative");
        }
    }

    void handleException(Service service, Exception exception) {
        Logger.error("Service '" + service.name + "' on thread '"
                + Thread.currentThread().getName() + "' had an exception", exception);
    }

    private record Selection(Service service, int status) {}
}
