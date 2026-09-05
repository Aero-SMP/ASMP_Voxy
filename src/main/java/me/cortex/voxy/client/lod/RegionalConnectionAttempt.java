package me.cortex.voxy.client.lod;

import java.io.IOException;
import java.util.concurrent.*;

/** A single owned setup operation, never a section worker or a queued executor backlog. */
final class RegionalConnectionAttempt implements AutoCloseable {
    @FunctionalInterface interface Connector { RegionalQuicClient connect() throws IOException; }
    record Outcome(RegionalQuicClient connection, Throwable failure) {}
    private static final ThreadPoolExecutor EXECUTOR = new ThreadPoolExecutor(1, 1,
            0, TimeUnit.MILLISECONDS, new SynchronousQueue<>(), task -> {
        Thread thread = new Thread(task, "Voxy background connection");
        thread.setDaemon(true);
        return thread;
    }, new ThreadPoolExecutor.AbortPolicy());
    private final WorkerResource<Outcome> resource = new WorkerResource<>(-1, outcome -> {
        if (outcome.connection() != null) outcome.connection().close();
    });
    private Future<?> task;

    RegionalConnectionAttempt(Connector connector) {
        var lease = this.resource.acquire();
        try {
            this.task = EXECUTOR.submit(() -> {
                Outcome outcome;
                try { outcome = new Outcome(connector.connect(), null); }
                catch (Throwable failure) { outcome = new Outcome(null, failure); }
                this.resource.complete(lease, outcome);
            });
        } catch (RejectedExecutionException busy) {
            this.resource.complete(lease, new Outcome(null, new IOException("previous endpoint attempt is still exiting", busy)));
        }
    }

    Outcome poll() {
        var completion = this.resource.claim();
        if (completion == null) return null;
        this.resource.release(completion.lease());
        return completion.value();
    }

    @Override public void close() {
        this.resource.close();
        if (this.task != null) this.task.cancel(true);
    }
}
