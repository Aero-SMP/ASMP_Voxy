package me.cortex.voxy.client.lod;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

/** Shared hard disk allowance. The monitor protects accounting/pins only, never payload I/O.
 * The existing inventory thread counts files once; pressure uses that inventory, not a new walk.
 */
final class RegionalDiskBudget {
    static final long LIMIT = 2L * 1024 * 1024 * 1024;
    private static final Map<Path, WeakReference<RegionalDiskBudget>> OPEN = new HashMap<>();
    final Path root;
    final long limit;
    volatile long bytes = -1, eviction;
    enum InventoryState { NEW, SCANNING, CLEANING, READY, FAILED, CLOSED }
    private volatile InventoryState state = InventoryState.NEW;
    private volatile String inventoryFailure;
    private volatile long inventoryNanos, inventoryStarted;
    private volatile Thread maintenance;
    private final LocalCacheOwnership ownership;
    private final LinkedHashMap<Path, Long> files = new LinkedHashMap<>();
    private final Map<Path, Region> regions = new HashMap<>();
    private final ReentrantLock changes = new ReentrantLock();
    private final java.util.concurrent.CountDownLatch disposed = new java.util.concurrent.CountDownLatch(1);
    private int owners;

    private static final class Region {
        final ReentrantLock writer = new ReentrantLock(true);
        CompletedSectionJournal journal; // Region monitor; identity outlives the file incarnation.
        int pins, directories, writers; // Budget monitor, including waiting writer references.
        boolean draining;
    }

    static void checkCurrent(java.util.function.BooleanSupplier current) {
        if (!current.getAsBoolean() || Thread.currentThread().isInterrupted())
            throw new java.util.concurrent.CancellationException("cache write superseded");
    }
    private static void lock(ReentrantLock lock) throws IOException {
        try { lock.lockInterruptibly(); }
        catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
            throw new java.io.InterruptedIOException("cache writer interrupted");
        }
    }
    Writer writer(Path path, java.util.function.BooleanSupplier current) throws IOException {
        Region region;
        synchronized (this) {
            if (this.state == InventoryState.CLOSED) throw new IOException("closed cache");
            region = this.regions.computeIfAbsent(path, ignored -> new Region());
            region.writers++;
        }
        boolean locked = false, acquired = false;
        try {
            checkCurrent(current);
            lock(region.writer); locked = true;
            checkCurrent(current);
            var writer = new Writer(path, region);
            acquired = true;
            return writer;
        } finally {
            if (!acquired) {
                if (locked) region.writer.unlock();
                releaseWriter(path, region);
            }
        }
    }
    final class Writer implements AutoCloseable {
        final Path path;
        private final Region region;
        private boolean closed;
        private Writer(Path path, Region region) { this.path = path; this.region = region; }
        void rotate(java.util.function.BooleanSupplier current) throws IOException {
            if (!ClientLodDebug.cacheDeletionAllowed(this.path)) throw new IOException("cache rotation outside test namespace");
            // Own no pin here. Deny fresh readers while the old incarnation drains.
            synchronized (RegionalDiskBudget.this) {
                this.region.draining = true;
                try {
                    while (this.region.pins != 0) {
                        checkCurrent(current);
                        RegionalDiskBudget.this.wait();
                    }
                } catch (InterruptedException stopped) {
                    Thread.currentThread().interrupt();
                    this.region.draining = false;
                    throw new java.io.InterruptedIOException("cache rotation interrupted");
                } catch (RuntimeException cancelled) {
                    this.region.draining = false; throw cancelled;
                }
            }
            try {
                checkCurrent(current);
                lock(changes);
                try { remove(this.path, this.region); }
                finally { changes.unlock(); }
            } finally { synchronized (RegionalDiskBudget.this) { this.region.draining = false; } }
        }
        @Override public void close() {
            if (this.closed) return;
            this.closed = true;
            this.region.writer.unlock(); releaseWriter(this.path, this.region);
        }
    }
    private void releaseWriter(Path path, Region region) {
        synchronized (this) { region.writers--; }
        forgetUnused(path);
    }

    static synchronized RegionalDiskBudget acquire(Path root) throws IOException {
        root = root.toAbsolutePath().normalize();
        var ref = OPEN.get(root);
        var budget = ref == null ? null : ref.get();
        if (budget == null || budget.state == InventoryState.CLOSED) {
            if (budget != null) try {
                if (!budget.disposed.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    throw new IOException("previous cache owner is still closing");
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt(); throw new IOException("cache reopen interrupted", stopped);
            }
            budget = new RegionalDiskBudget(root, LIMIT);
            OPEN.put(root, new WeakReference<>(budget));
        }
        budget.retain();
        return budget;
    }
    RegionalDiskBudget(Path root, long limit) throws IOException {
        this.ownership = LocalCacheOwnership.open(root, me.cortex.voxy.common.Logger::info);
        this.root = this.ownership.root; this.limit = limit;
    }
    synchronized void retain() {
        if (this.state == InventoryState.CLOSED) throw new IllegalStateException("closed cache budget");
        if (++this.owners == 1 && this.state == InventoryState.NEW) {
            this.state = InventoryState.SCANNING;
            this.maintenance = Thread.ofPlatform().daemon().name("Voxy cache inventory").start(this::inventory);
        }
    }
    void release() {
        synchronized (this) {
            if (this.owners <= 0) throw new IllegalStateException("cache owner underflow");
            if (--this.owners != 0) return;
            this.state = InventoryState.CLOSED;
            this.notifyAll();
            if (this.maintenance != null) { this.maintenance.interrupt(); return; }
        }
        closeOwnership();
    }
    boolean ready() { return this.state == InventoryState.READY; }
    synchronized void awaitReady(java.util.function.BooleanSupplier current) throws IOException {
        while (this.state == InventoryState.NEW || this.state == InventoryState.SCANNING || this.state == InventoryState.CLEANING) {
            checkCurrent(current);
            try { this.wait(); }
            catch (InterruptedException stopped) {
                Thread.currentThread().interrupt(); throw new java.io.InterruptedIOException("cache inventory wait interrupted");
            }
        }
        checkCurrent(current);
        if (!ready()) throw new IOException("cache persistence unavailable: " + this.state);
    }
    RegionalMetadataStore.Persistence persistenceUnavailable() {
        return switch (this.state) {
            case READY -> null;
            case NEW, SCANNING, CLEANING -> RegionalMetadataStore.Persistence.DEFERRED_INVENTORY;
            case CLOSED -> RegionalMetadataStore.Persistence.OBSOLETE;
            case FAILED -> RegionalMetadataStore.Persistence.UNAVAILABLE;
        };
    }
    String snapshot() {
        return " cacheInventory=" + this.state + " cacheDiskBytes=" + (ready() ? this.bytes : -1)
                + " cacheDiskLimit=" + this.limit + " cacheInventoryNs="
                + (this.maintenance == null ? this.inventoryNanos : Math.max(0, System.nanoTime() - this.inventoryStarted))
                + " cacheInventoryFailure=" + this.inventoryFailure;
    }
    private void inventory() {
        long start = System.nanoTime(); this.inventoryStarted = start;
        try {
            ClientLodDebug.inventoryDelay(this.root);
            var observed = new ArrayList<Map.Entry<Path, BasicFileAttributes>>();
            try (var paths = Files.walk(this.root)) {
                for (var iterator = paths.iterator(); iterator.hasNext();) {
                    checkInventory();
                    var path = iterator.next();
                    LocalCacheOwnership.rejectLinks(path);
                    if (!managedName(path)) continue;
                    var attrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    if (!attrs.isRegularFile()) throw new IOException("non-file local cache entry");
                    observed.add(Map.entry(path, attrs));
                }
            }
            observed.sort(Comparator.comparingLong(entry -> entry.getValue().lastModifiedTime().toMillis()));
            synchronized (this) {
                checkInventory();
                this.bytes = Files.size(this.root.resolve("cache-format"));
                for (var entry : observed) {
                    this.files.put(entry.getKey(), entry.getValue().size());
                    this.bytes = Math.addExact(this.bytes, entry.getValue().size());
                }
                this.state = InventoryState.CLEANING;
            }
            for (var entry : observed) if (entry.getKey().toString().endsWith(".pending")) delete(entry.getKey());
            while (this.bytes > this.limit) if (!evict(null)) throw new IOException("cache exceeds budget with leased files");
            synchronized (this) { checkInventory(); this.state = InventoryState.READY; }
        } catch (Exception failure) {
            synchronized (this) {
                if (this.state != InventoryState.CLOSED) {
                    this.state = InventoryState.FAILED; this.inventoryFailure = failure.toString();
                    me.cortex.voxy.common.Logger.warn("Cache inventory failed; persistence disabled", failure);
                }
            }
        } finally {
            boolean closed;
            synchronized (this) {
                this.inventoryNanos = System.nanoTime() - start;
                this.maintenance = null; closed = this.state == InventoryState.CLOSED;
                this.notifyAll();
            }
            if (closed) closeOwnership();
        }
    }
    private void checkInventory() throws IOException {
        if (this.state == InventoryState.CLOSED || Thread.currentThread().isInterrupted()) throw new IOException("cache inventory cancelled");
    }
    private void closeOwnership() {
        // Last cache owner is released only after its worker operations/leases drain.
        for (var region : this.regions.values()) {
            try { if (region.journal != null) region.journal.close(); }
            catch (IOException failure) { me.cortex.voxy.common.Logger.warn("Closing local journal", failure); }
        }
        this.regions.clear();
        try { this.ownership.close(); } catch (IOException failure) { me.cortex.voxy.common.Logger.warn("Closing cache ownership", failure); }
        finally { this.disposed.countDown(); }
    }
    synchronized Pin pin(Path path) throws IOException {
        if (this.state == InventoryState.CLOSED) throw new IOException("cache file unavailable");
        Region region = this.regions.computeIfAbsent(path, ignored -> new Region());
        if (region.draining) throw new IOException("cache incarnation retiring");
        region.pins++;
        return new Pin(path, region);
    }
    final class Pin implements AutoCloseable {
        private final Path path; private final Region region; private boolean closed;
        private Pin(Path path, Region region) { this.path = path; this.region = region; }
        @Override public void close() {
            synchronized (RegionalDiskBudget.this) {
                if (this.closed) return;
                this.closed = true;
                if (--this.region.pins < 0) throw new IllegalStateException("cache pin underflow");
                RegionalDiskBudget.this.notifyAll();
            }
            forgetUnused(this.path);
        }
    }
    synchronized void retainDirectory(Path path) { this.regions.computeIfAbsent(path, ignored -> new Region()).directories++; }
    void releaseDirectory(Path path) {
        synchronized (this) {
            var region = this.regions.get(path);
            if (region == null || --region.directories < 0) throw new IllegalStateException("cache directory underflow");
        }
        forgetUnused(path);
    }
    private void forgetUnused(Path path) {
        Region forgotten;
        synchronized (this) {
            forgotten = this.regions.get(path);
            if (forgotten == null || forgotten.pins != 0 || forgotten.directories != 0 || forgotten.writers != 0) return;
            this.regions.remove(path);
        }
        // Production journals have no idle writer handle: append/recovery closes it.
        // Releasing a retained directory therefore performs no file access on the owner.
        if (forgotten.journal != null) try { forgotten.journal.close(); }
        catch (IOException failure) { me.cortex.voxy.common.Logger.warn("Closing released directory", failure); }
    }
    long stamp() { return this.eviction; }

    void reserve(Path path, long added) throws IOException {
        lock(this.changes);
        try {
            if (added < 0 || added > this.limit) throw new IOException("cache reservation exceeds hard limit");
            while (true) {
                synchronized (this) {
                    if (!ready()) throw new IOException("cache persistence unavailable: " + this.state);
                    if (this.bytes + added <= this.limit) {
                        this.bytes += added; this.files.merge(path, added, Long::sum); return;
                    }
                }
                if (!evict(path)) throw new IOException("cache disk capacity is leased");
            }
        } finally { this.changes.unlock(); }
    }
    synchronized void resized(Path path, long delta) {
        if (this.bytes >= 0) { this.bytes += delta; this.files.merge(path, delta, Long::sum); }
    }
    private boolean evict(Path protect) throws IOException {
        List<Path> candidates;
        synchronized (this) { candidates = List.copyOf(this.files.keySet()); }
        for (var path : candidates) if (!path.equals(protect) && delete(path)) return true;
        return false;
    }
    boolean delete(Path path) throws IOException {
        Region region;
        synchronized (this) {
            region = this.regions.computeIfAbsent(path, ignored -> new Region());
            region.writers++;
        }
        // Never wait for another region while reserve owns the capacity-change lock.
        boolean locked = region.writer.tryLock();
        try {
            if (!locked) return false;
            synchronized (this) {
                if (region.pins != 0 || region.draining || !ClientLodDebug.cacheDeletionAllowed(path)) return false;
                region.draining = true;
            }
            try { remove(path, region); return true; }
            finally { synchronized (this) { region.draining = false; } }
        } finally {
            if (locked) region.writer.unlock();
            releaseWriter(path, region);
        }
    }
    private void remove(Path path, Region region) throws IOException {
        synchronized (region) {
            if (region.journal != null) {
                region.journal.close(); region.journal = null;
            }
            LocalCacheOwnership.rejectLinks(path);
            Files.deleteIfExists(path);
        }
        synchronized (this) {
            Long size = this.files.remove(path);
            if (size != null) this.bytes -= size;
            this.eviction++;
        }
    }
    CompletedSectionJournal journal(Path path, RegionalProtocol.Hash32 world, long region, boolean create) throws IOException {
        Region owner;
        synchronized (this) { owner = this.regions.get(path); }
        if (owner == null) throw new IllegalStateException("journal requires a pin or retained directory");
        synchronized (owner) {
            if (owner.journal != null && !owner.journal.closed()) return owner.journal;
            owner.journal = null;
            boolean present = Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && Files.size(path) != 0;
            if (!present && !create) return null;
            if (!present) {
                reserve(path, CompletedSectionJournal.HEADER_BYTES);
                Files.createDirectories(path.getParent());
            }
            long before = size(path);
            try {
                var journal = CompletedSectionJournal.open(path, world, region, ready());
                journal.closeHandle();
                owner.journal = journal;
                return journal;
            } finally {
                if (ready()) resized(path, size(path) - before - (!present ? CompletedSectionJournal.HEADER_BYTES : 0));
            }
        }
    }
    static long size(Path path) { try { return Files.size(path); } catch (IOException missing) { return 0; } }
    private static boolean managedName(Path path) {
        String name = path.getFileName().toString();
        return name.endsWith(".vxlocal") || name.endsWith(".vxlink") || name.endsWith(".vxlink.pending");
    }
}
