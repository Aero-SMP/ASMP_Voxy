package me.cortex.voxy.client.lod;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.function.BooleanSupplier;

/** One existing 2 GiB allowance shared by payloads and startup metadata in every namespace.
 * Disk methods run on cache/metadata workers; this monitor also orders eviction and atomic writes. */
final class RegionalDiskBudget {
    static final long LIMIT = 2L * 1024 * 1024 * 1024;
    private static final Map<Path, WeakReference<RegionalDiskBudget>> OPEN = new HashMap<>();
    final Path root;
    final long limit;
    // Volatile for approximate telemetry only; all accounting mutations require this monitor.
    volatile long bytes = -1;
    volatile long eviction;
    enum InventoryState { NEW, SCANNING, CLEANING, READY, FAILED, CLOSED }
    private volatile InventoryState state = InventoryState.NEW;
    private volatile String inventoryFailure;
    private volatile long inventoryNanos;
    private volatile long inventoryStarted;
    // Counts failed writability checks, not unique lost writes. Mutated under this monitor.
    private volatile long skippedWrites;
    private int owners;
    private volatile Thread maintenance;
    private FileChannel ownershipChannel;
    private FileLock ownershipLock;
    private final Map<Path, Integer> pins = new HashMap<>();
    @FunctionalInterface interface ReferenceReader { Path read(Path path) throws IOException; }
    private final ReferenceReader referenceReader;
    private final Map<Path, Map<RegionalCache, BooleanSupplier>> openFiles = new HashMap<>();
    private final Map<Path, Path> references = new HashMap<>();
    private final Map<Path, Integer> catalogReferences = new HashMap<>();

    static synchronized RegionalDiskBudget open(Path root) throws IOException {
        return open(root, RegionalMetadataStore::referencedCatalog);
    }
    static RegionalDiskBudget acquire(Path root) throws IOException {
        for (;;) {
            var budget = open(root);
            synchronized (budget) {
                // The previous session may have released its last owner after lookup.
                if (budget.state == InventoryState.CLOSED && budget.maintenance == null) continue;
                budget.retain();
                return budget;
            }
        }
    }
    static synchronized RegionalDiskBudget open(Path root, ReferenceReader reader) throws IOException {
        root = Files.createDirectories(root).toRealPath();
        var reference = OPEN.get(root);
        var budget = reference == null ? null : reference.get();
        if (budget == null || budget.state == InventoryState.CLOSED && budget.maintenance == null) {
            budget = new RegionalDiskBudget(root, LIMIT, reader);
            OPEN.put(root, new WeakReference<>(budget));
        }
        return budget;
    }

    RegionalDiskBudget(Path root, long limit) throws IOException {
        this(root, limit, RegionalMetadataStore::referencedCatalog);
    }
    RegionalDiskBudget(Path root, long limit, ReferenceReader reader) throws IOException {
        this.root = Files.createDirectories(root).toRealPath();
        this.limit = limit;
        this.referenceReader = reader;
    }

    synchronized void retain() {
        if (this.state == InventoryState.CLOSED && this.maintenance == null)
            throw new IllegalStateException("closed cache budget");
        this.owners++;
        if (this.state != InventoryState.NEW) return;
        startInventory();
    }
    private void startInventory() {
        this.state = InventoryState.SCANNING;
        this.maintenance = Thread.ofPlatform().daemon().name("Voxy cache inventory").unstarted(this::inventory);
        this.maintenance.start();
    }

    synchronized void release() {
        if (this.owners <= 0) throw new IllegalStateException("cache owner underflow");
        if (--this.owners != 0) return;
        this.state = InventoryState.CLOSED;
        if (this.maintenance != null) this.maintenance.interrupt();
        else closeOwnership();
    }

    boolean ready() { return this.state == InventoryState.READY; }
    synchronized boolean writable() {
        if (ready()) return true;
        this.skippedWrites++;
        return false;
    }
    /** Best-effort telemetry, not an atomic accounting snapshot or permission to write/evict. */
    String snapshot() {
        var observedState = this.state;
        long observedBytes = this.bytes, skipped = this.skippedWrites;
        long elapsed = this.inventoryNanos, started = this.inventoryStarted;
        var running = this.maintenance;
        String failure = this.inventoryFailure;
        return " cacheInventory=" + observedState + " cacheDiskBytes=" + (observedState == InventoryState.READY ? observedBytes : -1)
                + " cacheDiskLimit=" + this.limit + " cacheInventoryNs="
                + (running == null ? elapsed : Math.max(0, System.nanoTime() - started))
                + " cacheSkippedWrites=" + skipped + " cacheInventoryFailure=" + failure;
    }

    // A single lifecycle-owned job. No budget monitor is held during either directory walk
    // or descriptor inspection. Until both passes agree, foreground paths are read-only.
    private void inventory() {
        long started = System.nanoTime();
        this.inventoryStarted = started;
        try {
            this.ownershipChannel = FileChannel.open(this.root.resolve(".voxy-cache.lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            this.ownershipLock = this.ownershipChannel.tryLock();
            if (this.ownershipLock == null) throw new IOException("cache is owned by another process");
            Map<Path, BasicFileAttributes> observed = new HashMap<>();
            Map<Path, Path> discovered = new HashMap<>();
            Map<Path, Integer> counts = new HashMap<>();
            long total = 0;
            try (var files = Files.walk(this.root)) {
                for (Path path : files.filter(RegionalDiskBudget::inventoryFile).toList()) {
                    checkInventory();
                    var attributes = Files.readAttributes(path, BasicFileAttributes.class);
                    observed.put(path, attributes);
                    total = Math.addExact(total, attributes.size());
                    if (path.toString().endsWith(".vxmeta")) {
                        Path catalog = this.referenceReader.read(path);
                        if (catalog != null) {
                            discovered.put(path, catalog);
                            counts.merge(catalog, 1, Integer::sum);
                        }
                    }
                }
            }
            List<Path> candidates;
            try (var files = Files.walk(this.root)) {
                candidates = files.filter(RegionalDiskBudget::inventoryFile)
                        .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
            }
            if (candidates.size() != observed.size()) throw new IOException("cache changed during inventory");
            for (Path path : candidates) {
                checkInventory();
                var old = observed.get(path);
                var current = Files.readAttributes(path, BasicFileAttributes.class);
                if (old == null || old.size() != current.size()
                        || !old.lastModifiedTime().equals(current.lastModifiedTime())
                        || !Objects.equals(old.fileKey(), current.fileKey()))
                    throw new IOException("cache changed during inventory");
            }
            synchronized (this) {
                checkInventory();
                this.bytes = total;
                this.references.clear();
                this.catalogReferences.clear();
                this.references.putAll(discovered);
                this.catalogReferences.putAll(counts);
                this.state = InventoryState.CLEANING;
            }
            // All references are known before garbage collection. Each deletion honors the
            // same open-shard leases plus foreground metadata pins; no monolithic cleanup lock.
            for (Path path : candidates) synchronized (this) {
                checkInventory();
                if (path.toString().endsWith(".pending") || path.toString().endsWith(".vxcat")
                        && !this.catalogReferences.containsKey(path)) deleteKnown(path);
            }
            boolean overBudget;
            synchronized (this) {
                checkInventory();
                overBudget = this.bytes > this.limit;
            }
            if (overBudget) {
                checkInventory();
                candidates.sort(Comparator.comparingLong(path -> observed.get(path).lastModifiedTime().toMillis()));
                checkInventory();
                for (Path path : candidates) synchronized (this) {
                    checkInventory();
                    if (this.bytes <= this.limit) break;
                    if (!path.toString().endsWith(".vxcat") || !this.catalogReferences.containsKey(path)) deleteKnown(path);
                }
            }
            synchronized (this) {
                checkInventory();
                this.state = InventoryState.READY;
            }
        } catch (Exception failure) {
            synchronized (this) {
                if (this.state != InventoryState.CLOSED) {
                    this.state = InventoryState.FAILED;
                    this.bytes = -1;
                    this.inventoryFailure = failure.getClass().getSimpleName() + ": " + failure.getMessage();
                    me.cortex.voxy.common.Logger.warn("Cache inventory failed; valid reads remain enabled, persistence disabled", failure);
                }
            }
        } finally {
            synchronized (this) {
                this.inventoryNanos = System.nanoTime() - started;
                this.maintenance = null;
                if (this.state != InventoryState.READY) closeOwnership();
                if (this.state == InventoryState.CLOSED && this.owners > 0) startInventory();
            }
        }
    }

    private void checkInventory() throws IOException {
        if (this.state == InventoryState.CLOSED || Thread.currentThread().isInterrupted())
            throw new IOException("cache inventory cancelled");
    }
    private void closeOwnership() {
        try { if (this.ownershipLock != null) this.ownershipLock.close(); } catch (IOException ignored) {}
        try { if (this.ownershipChannel != null) this.ownershipChannel.close(); } catch (IOException ignored) {}
        this.ownershipLock = null;
        this.ownershipChannel = null;
    }

    synchronized Pin pin(Path path) {
        this.pins.merge(path, 1, Integer::sum);
        return new Pin(path);
    }
    final class Pin implements AutoCloseable {
        private final Path path;
        private boolean closed;
        private Pin(Path path) { this.path = path; }
        @Override public void close() { synchronized (RegionalDiskBudget.this) {
            if (this.closed) return;
            this.closed = true;
            pins.computeIfPresent(this.path, (key, count) -> count == 1 ? null : count - 1);
        } }
    }

    long stamp() { return this.eviction; }
    void register(Path path, RegionalCache owner, BooleanSupplier close) {
        this.openFiles.computeIfAbsent(path, ignored -> new HashMap<>()).put(owner, close);
    }
    void unregister(Path path, RegionalCache owner) {
        var readers = this.openFiles.get(path);
        if (readers != null && readers.remove(owner) != null && readers.isEmpty()) this.openFiles.remove(path);
    }

    boolean ensure(long added, Set<Path> protectedPaths) throws IOException {
        if (!writable()) return false;
        if (added < 0 || added > this.limit) return false;
        if (this.bytes + added <= this.limit) return true;
        // Only pressure scans the directory; section hits never scan it.
        var candidates = new ArrayList<EvictionCandidate>();
        try (var files = Files.walk(this.root)) {
            for (var iterator = files.iterator(); iterator.hasNext();) {
                Path path = iterator.next();
                if (protectedPaths.contains(path) || !managedName(path)) continue;
                try {
                    var attributes = Files.readAttributes(path, BasicFileAttributes.class);
                    if (attributes.isRegularFile())
                        candidates.add(new EvictionCandidate(path, attributes.lastModifiedTime().toMillis()));
                } catch (IOException unknown) { /* Unclassified files are not eviction candidates. */ }
            }
        }
        candidates.sort(Comparator.comparingLong(EvictionCandidate::modified));
        for (var candidate : candidates) {
            Path path = candidate.path();
            if (path.toString().endsWith(".vxcat") && this.catalogReferences.containsKey(path)) continue;
            this.delete(path);
            if (this.bytes + added <= this.limit) return true;
        }
        return this.bytes + added <= this.limit;
    }

    private record EvictionCandidate(Path path, long modified) {}

    void reference(Path descriptor, Path catalog) throws IOException {
        if (catalog == null) {
            this.unreference(this.references.remove(descriptor));
            return;
        }
        Path previous = this.references.put(descriptor, catalog);
        if (Objects.equals(previous, catalog)) return;
        this.catalogReferences.merge(catalog, 1, Integer::sum);
        this.unreference(previous);
    }

    private void unreference(Path catalog) throws IOException {
        if (catalog == null) return;
        int count = this.catalogReferences.getOrDefault(catalog, 1) - 1;
        if (count > 0) this.catalogReferences.put(catalog, count);
        else {
            this.catalogReferences.remove(catalog);
            this.deleteKnown(catalog);
        }
    }

    boolean delete(Path path) throws IOException {
        if (!writable()) return false;
        return deleteKnown(path);
    }

    private boolean deleteKnown(Path path) throws IOException {
        if (this.pins.containsKey(path)) return false;
        var readers = this.openFiles.get(path);
        if (readers != null) for (var close : List.copyOf(readers.values())) {
            if (!close.getAsBoolean()) return false;
        }
        long size = size(path);
        try {
            if (!Files.deleteIfExists(path)) return true;
        } catch (IOException busy) { return false; }
        this.bytes = Math.max(0, this.bytes - size);
        this.eviction++;
        this.openFiles.remove(path);
        this.unreference(this.references.remove(path));
        return true;
    }

    static long size(Path path) {
        try { return Files.size(path); } catch (IOException missing) { return 0; }
    }
    private static boolean inventoryFile(Path path) {
        if (!managedName(path)) return false;
        try { return Files.readAttributes(path, BasicFileAttributes.class).isRegularFile(); }
        catch (IOException unknown) { throw new java.io.UncheckedIOException(unknown); }
    }
    private static boolean managedName(Path path) {
        String name = path.getFileName().toString();
        return name.endsWith(".vxcache") || name.endsWith(".vxmeta")
                || name.endsWith(".vxcat") || name.endsWith(".vxlink")
                || name.endsWith(".vxmeta.pending") || name.endsWith(".vxcat.pending")
                || name.endsWith(".vxlink.pending");
    }
}
