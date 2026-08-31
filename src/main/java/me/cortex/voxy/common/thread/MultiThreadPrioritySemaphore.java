package me.cortex.voxy.common.thread;

import me.cortex.voxy.common.util.TrackedObject;

import java.util.function.IntSupplier;

/** Lets a local worker wait for its own permit while helping the shared Voxy work pool. */
public final class MultiThreadPrioritySemaphore {
    public final class Block extends TrackedObject {
        private int permits;
        private boolean freed;

        private Block() {}

        public void release(int permits) {
            if (permits <= 0) throw new IllegalArgumentException("Permits must be positive");
            synchronized (MultiThreadPrioritySemaphore.this) {
                checkLive();
                this.permits = Math.addExact(this.permits, permits);
                MultiThreadPrioritySemaphore.this.notifyAll();
            }
        }

        public void acquire() {
            this.acquire(true);
        }

        /** Waits for a local permit, helping execute shared jobs while one is unavailable. */
        public void acquire(boolean runJob) {
            boolean interrupted = false;
            try {
                while (true) {
                    synchronized (MultiThreadPrioritySemaphore.this) {
                        checkLive();
                        while (this.permits == 0 && (!runJob || pooledPermits == 0)) {
                            try {
                                MultiThreadPrioritySemaphore.this.wait();
                            } catch (InterruptedException ignored) {
                                interrupted = true;
                            }
                            checkLive();
                        }
                        if (this.permits != 0) {
                            this.permits--;
                            return;
                        }
                        pooledPermits--;
                    }

                    int status;
                    try {
                        status = executor.getAsInt();
                    } catch (RuntimeException | Error exception) {
                        pooledRelease(1);
                        throw exception;
                    }
                    if (status >= 2) {
                        synchronized (MultiThreadPrioritySemaphore.this) {
                            pooledPermits = Math.addExact(pooledPermits, 1);
                            if (this.permits == 0) {
                                try {
                                    MultiThreadPrioritySemaphore.this.wait(10);
                                } catch (InterruptedException ignored) {
                                    interrupted = true;
                                }
                            }
                        }
                    }
                }
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
        }

        public int availablePermits() {
            synchronized (MultiThreadPrioritySemaphore.this) {
                checkLive();
                return this.permits;
            }
        }

        public boolean tryAcquire() {
            synchronized (MultiThreadPrioritySemaphore.this) {
                checkLive();
                if (this.permits == 0) return false;
                this.permits--;
                return true;
            }
        }

        public void free() {
            synchronized (MultiThreadPrioritySemaphore.this) {
                checkLive();
                this.freed = true;
                MultiThreadPrioritySemaphore.this.notifyAll();
            }
            this.free0();
        }

        private void checkLive() {
            if (this.freed) throw new IllegalStateException("Semaphore block is freed");
        }
    }

    private final IntSupplier executor;
    private int pooledPermits;

    public MultiThreadPrioritySemaphore(IntSupplier executor) {
        this.executor = executor;
    }

    public Block createBlock() {
        return new Block();
    }

    public synchronized void pooledRelease(int permits) {
        if (permits <= 0) throw new IllegalArgumentException("Permits must be positive");
        this.pooledPermits = Math.addExact(this.pooledPermits, permits);
        this.notifyAll();
    }
}
