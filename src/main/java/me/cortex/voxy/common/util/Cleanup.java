package me.cortex.voxy.common.util;

/** Independent disposers all run; their failures are reported after ownership is released. */
public final class Cleanup {
    private Throwable failure;

    public void run(Runnable action) {
        try { action.run(); }
        catch (RuntimeException | Error error) {
            if (this.failure == null) this.failure = error;
            else if (this.failure != error) this.failure.addSuppressed(error);
        }
    }

    public void rethrow() {
        if (this.failure instanceof RuntimeException error) throw error;
        if (this.failure instanceof Error error) throw error;
    }

    /** Interruption is preserved, but is never mistaken for a worker relinquishing resources. */
    public static void join(Thread thread) {
        if (thread == Thread.currentThread()) throw new IllegalStateException("cannot join the resource owner itself");
        boolean interrupted = false;
        while (thread.isAlive()) {
            try { thread.join(); }
            catch (InterruptedException ignored) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
}
