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
    volatile long bytes = -1, eviction, pinRevision;
    enum InventoryState { NEW, SCANNING, CLEANING, READY, FAILED, CLOSED }
    private volatile InventoryState state = InventoryState.NEW;
    private volatile String inventoryFailure;
    private volatile long inventoryNanos, inventoryStarted;
    private volatile Thread maintenance;
    private final LocalCacheOwnership ownership;
    private final Map<Path, Integer> pins = new HashMap<>();
    private final LinkedHashMap<Path, Long> files = new LinkedHashMap<>();
    private final Map<Path, CompletedSectionJournal> journals = new HashMap<>();
    private final Map<Path, Integer> directories = new HashMap<>();
    private final ReentrantLock changes = new ReentrantLock();
    private final Set<Path> removing = new HashSet<>();
    private final java.util.concurrent.CountDownLatch disposed = new java.util.concurrent.CountDownLatch(1);
    private int owners;

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
            if (this.maintenance != null) { this.maintenance.interrupt(); return; }
        }
        closeOwnership();
    }
    boolean ready() { return this.state == InventoryState.READY; }
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
            }
            if (closed) closeOwnership();
        }
    }
    private void checkInventory() throws IOException {
        if (this.state == InventoryState.CLOSED || Thread.currentThread().isInterrupted()) throw new IOException("cache inventory cancelled");
    }
    private void closeOwnership() {
        // Last cache owner is released only after its worker operations/leases drain.
        synchronized (this.journals) {
            for (var journal : this.journals.values()) try { journal.close(); }
            catch (IOException failure) { me.cortex.voxy.common.Logger.warn("Closing local journal", failure); }
            this.journals.clear();
        }
        try { this.ownership.close(); } catch (IOException failure) { me.cortex.voxy.common.Logger.warn("Closing cache ownership", failure); }
        finally { this.disposed.countDown(); }
    }
    synchronized Pin pin(Path path) throws IOException {
        if (this.state == InventoryState.CLOSED || this.removing.contains(path)) throw new IOException("cache file unavailable");
        this.pins.merge(path, 1, Integer::sum);
        return new Pin(path);
    }
    final class Pin implements AutoCloseable {
        private final Path path; private boolean closed;
        private Pin(Path path) { this.path = path; }
        @Override public void close() {
            synchronized (RegionalDiskBudget.this) {
                if (this.closed) return;
                this.closed = true;
                pins.compute(this.path, (key, n) -> n == null || n == 1 ? null : n - 1);
                pinRevision++;
            }
            forgetUnused(this.path);
        }
    }
    synchronized void retainDirectory(Path path) { this.directories.merge(path, 1, Integer::sum); }
    void releaseDirectory(Path path) {
        synchronized (this) {
            this.directories.compute(path, (key, n) -> n == null || n == 1 ? null : n - 1);
        }
        forgetUnused(path);
    }
    private void forgetUnused(Path path) {
        CompletedSectionJournal forgotten;
        synchronized (this.journals) {
            synchronized (this) {
                if (this.pins.containsKey(path) || this.directories.containsKey(path)) return;
                forgotten = this.journals.remove(path);
            }
        }
        // Production journals have no idle writer handle: append/recovery closes it.
        // Releasing a retained directory therefore performs no file access on the owner.
        if (forgotten != null) try { forgotten.close(); }
        catch (IOException failure) { me.cortex.voxy.common.Logger.warn("Closing released directory", failure); }
    }
    long stamp() { return this.eviction; }

    void reserve(Path path, long added) throws IOException {
        this.changes.lock();
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
        synchronized (this) {
            if (this.pins.containsKey(path) || !ClientLodDebug.cacheDeletionAllowed(path) || !this.removing.add(path)) return false;
        }
        try {
            synchronized (this.journals) {
                var journal = this.journals.get(path);
                if (journal != null) {
                    if (journal.busy()) return false;
                    journal.close(); this.journals.remove(path);
                }
            }
            LocalCacheOwnership.rejectLinks(path);
            Files.deleteIfExists(path);
            synchronized (this) {
                Long size = this.files.remove(path);
                if (size != null) this.bytes -= size;
                this.eviction++;
            }
            return true;
        } finally { synchronized (this) { this.removing.remove(path); } }
    }
    CompletedSectionJournal journal(Path path, RegionalProtocol.Hash32 world, long region, boolean create) throws IOException {
        this.changes.lock();
        try {
        // Metadata-only recovery is shared, once per retained file incarnation.
            CompletedSectionJournal journal;
            synchronized (this.journals) { journal = this.journals.get(path); }
            if (journal != null) return journal;
            boolean present = Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && Files.size(path) != 0;
            if (!present && !create) return null;
            if (!present) {
                reserve(path, CompletedSectionJournal.HEADER_BYTES);
                Files.createDirectories(path.getParent());
            }
            long before = size(path);
            try {
                journal = CompletedSectionJournal.open(path, world, region, ready());
                journal.closeHandle();
                synchronized (this.journals) { this.journals.put(path, journal); }
                return journal;
            } finally {
                if (ready()) resized(path, size(path) - before - (!present ? CompletedSectionJournal.HEADER_BYTES : 0));
            }
        } finally { this.changes.unlock(); }
    }
    static long size(Path path) { try { return Files.size(path); } catch (IOException missing) { return 0; } }
    private static boolean managedName(Path path) {
        String name = path.getFileName().toString();
        return name.endsWith(".vxlocal") || name.endsWith(".vxlink") || name.endsWith(".vxlink.pending");
    }
}
