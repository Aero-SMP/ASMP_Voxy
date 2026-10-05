package me.cortex.voxy.client.lod;

import java.io.*;
import java.lang.ref.WeakReference;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

/** Shared file ownership and actual-byte accounting; each logical server has its own allowance. */
final class RegionalDiskBudget {
    private static final Map<Path, WeakReference<RegionalDiskBudget>> OPEN = new HashMap<>();
    final Path root;
    volatile long bytes = -1, eviction;
    enum InventoryState { NEW, SCANNING, CLEANING, READY, FAILED, CLOSED }
    private volatile InventoryState state = InventoryState.NEW;
    private volatile String inventoryFailure;
    private volatile long inventoryNanos, inventoryStarted;
    private volatile Thread maintenance;
    private final LocalCacheOwnership ownership;
    private final Map<Path, Long> files = new HashMap<>();
    private final Map<Path, Set<Path>> directoryFiles = new HashMap<>();
    private final Map<String, Account> accounts = new HashMap<>();
    private final Map<Path, Account> metadataOwners = new HashMap<>();
    private final Map<Path, Namespace> namespaces = new HashMap<>();
    private Account sharedOwner;
    private boolean reconciling;
    private final Map<Path, Ranked> ranked = new HashMap<>();
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
            region.writers++; rekey(path);
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
        boolean compact(RegionalProtocol.Hash32 world, long key, java.util.function.BooleanSupplier current) throws IOException {
            synchronized (RegionalDiskBudget.this) {
                if (this.region.pins != 0 || this.region.draining) return false;
                this.region.draining = true;
            }
            Path temporary = this.path.resolveSibling(this.path.getFileName() + ".pending");
            long reserved = 0;
            boolean installed = false;
            try {
                checkCurrent(current);
                if (!Files.isRegularFile(this.path, LinkOption.NOFOLLOW_LINKS) || size(this.path) < CompletedSectionJournal.HEADER_BYTES) return false;
                LocalCacheOwnership.rejectLinks(temporary);
                long oldTemporary = size(temporary);
                if (Files.deleteIfExists(temporary)) resized(temporary, -oldTemporary);
                CompletedSectionJournal journal = this.region.journal;
                if (journal == null || journal.closed()) {
                    journal = CompletedSectionJournal.open(this.path, world, key, false);
                    this.region.journal = journal;
                }
                long compactBytes = journal.compactedBytes();
                long oldBytes = size(this.path);
                if (compactBytes >= oldBytes) return false;
                reserve(temporary, compactBytes); reserved = compactBytes;
                journal.compactTo(temporary, current);
                try (var verified = CompletedSectionJournal.open(temporary, world, key, false)) {
                    if (verified.bytes() != compactBytes || !verified.directory().equals(journal.directory()))
                        throw new IOException("compacted journal validation failed");
                }
                checkCurrent(current);
                journal.close(); this.region.journal = null;
                Files.move(temporary, this.path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                resized(temporary, -compactBytes); reserved = 0;
                resized(this.path, compactBytes - oldBytes); installed = true;
                this.region.journal = CompletedSectionJournal.open(this.path, world, key, false);
                return true;
            } finally {
                if (!installed) {
                    try { Files.deleteIfExists(temporary); }
                    finally { if (reserved != 0) resized(temporary, size(temporary) - reserved); }
                }
                synchronized (RegionalDiskBudget.this) { this.region.draining = false; RegionalDiskBudget.this.notifyAll(); }
            }
        }
        @Override public void close() {
            if (this.closed) return;
            this.closed = true;
            this.region.writer.unlock(); releaseWriter(this.path, this.region);
        }
    }
    private void releaseWriter(Path path, Region region) {
        synchronized (this) { region.writers--; rekey(path); }
        forgetUnused(path);
    }

    static synchronized RegionalDiskBudget acquire(Path root) throws IOException {
        root = LocalCacheOwnership.currentRoot(root);
        var ref = OPEN.get(root);
        var budget = ref == null ? null : ref.get();
        if (budget == null || budget.state == InventoryState.CLOSED) {
            if (budget != null) try {
                if (!budget.disposed.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    throw new IOException("previous cache owner is still closing");
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt(); throw new IOException("cache reopen interrupted", stopped);
            }
            budget = new RegionalDiskBudget(LocalCacheOwnership.openCurrent(root, me.cortex.voxy.common.Logger::info));
            OPEN.put(root, new WeakReference<>(budget));
        }
        budget.retain();
        return budget;
    }
    private RegionalDiskBudget(LocalCacheOwnership ownership) {
        this.ownership = ownership;
        this.root = ownership.root;
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
    synchronized String snapshot() {
        long owned = this.accounts.values().stream().mapToLong(account -> account.bytes).sum();
        return " cacheInventory=" + this.state + " cacheDiskBytes=" + (ready() ? this.bytes : -1)
                + " cacheUnownedBytes=" + (ready() ? this.bytes - owned : -1)
                + " cacheInventoryNs=" + (this.maintenance == null ? this.inventoryNanos
                : Math.max(0, System.nanoTime() - this.inventoryStarted))
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
            for (var entry : observed) if (entry.getKey().getFileName().toString().endsWith(".vxowner"))
                readAccount(entry.getKey());
            synchronized (this) {
                checkInventory();
                this.bytes = 0;
                for (var entry : observed) {
                    this.files.put(entry.getKey(), entry.getValue().size());
                    this.directoryFiles.computeIfAbsent(entry.getKey().getParent(), ignored -> new HashSet<>()).add(entry.getKey());
                    this.bytes = Math.addExact(this.bytes, entry.getValue().size());
                }
                recount();
                this.state = InventoryState.CLEANING;
            }
            for (var entry : observed) if (entry.getKey().toString().endsWith(".pending")) {
                LocalCacheOwnership.rejectLinks(entry.getKey());
                Files.deleteIfExists(entry.getKey()); resized(entry.getKey(), -entry.getValue().size());
            }
            synchronized (this) { checkInventory(); this.state = InventoryState.READY; }
            reconcile();
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
        while (region.draining) try { this.wait(); }
        catch (InterruptedException stopped) {
            Thread.currentThread().interrupt(); throw new InterruptedIOException("cache replacement wait interrupted");
        }
        if (this.state == InventoryState.CLOSED) throw new IOException("cache file unavailable");
        region.pins++; rekey(path);
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
                rekey(this.path);
                if (this.region.pins == 0) requestReconcile();
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

    enum Admission { READY, INVENTORY, OWNERSHIP, QUOTA, DISK_FULL }
    static final class Capacity extends IOException {
        final Admission reason;
        Capacity(Admission reason) { super("cache admission paused: " + reason); this.reason = reason; }
    }
    record StorageState(long bytes, long limit, long unownedBytes, boolean ready, boolean downloadPaused, String reason) {}
    static final class Account {
        final String key;
        final Path record;
        final Set<Path> links = new HashSet<>();
        final Map<String, Anchor> anchors = new HashMap<>();
        long limit, bytes;
        volatile boolean dirty, diskFull, ambiguous;
        long revision, blockedGrowth = 1, recoveryGeneration;
        final NavigableSet<Ranked> victims = new TreeSet<>((a, b) -> {
            int result = Boolean.compare(a.rank.visible, b.rank.visible);
            if (result == 0) result = Long.compare(b.rank.distance, a.rank.distance);
            return result == 0 ? a.path.toString().compareTo(b.path.toString()) : result;
        });
        Account(String key, Path record, long limit) { this.key = key; this.record = record; this.limit = limit; }
    }
    private record Anchor(long x, long z, Set<Long> visible) {}
    private record Namespace(Account owner, String dimension) {}
    private record Ranked(Path path, Account owner, Rank rank) {}
    private record Rank(boolean visible, long distance) {
        boolean better(Rank other) { return this.visible != other.visible ? this.visible : this.distance < other.distance; }
    }
    private static final long OWNER_MAGIC = 0x5658594f574e4552L;

    synchronized Account configure(String key, long limit) throws IOException {
        if (key == null || key.isBlank() || limit < 100_000_000L) throw new IOException("invalid server cache policy");
        Account account = this.accounts.computeIfAbsent(key, value -> new Account(value,
                ClientLodDebug.cacheNamespace(this.root).resolve("servers").resolve(RegionalMetadataStore.identifier(value) + ".vxowner"), limit));
        if (account.limit != limit) {
            account.limit = limit;
            if (account.diskFull) { account.diskFull = false; account.recoveryGeneration++; }
        }
        account.dirty = true; account.revision++;
        this.metadataOwners.put(account.record, account);
        if (this.sharedOwner == null) this.sharedOwner = account;
        recount(); requestReconcile();
        return account;
    }
    synchronized void claim(Account account, Path namespace, String dimension, Path link) throws IOException {
        if (account == null) throw new IOException("cache server ownership is not bound");
        namespace = namespace.toAbsolutePath().normalize();
        if (!namespace.startsWith(this.root) || namespace.equals(this.root)) throw new IOException("namespace outside owned cache");
        Namespace old = this.namespaces.get(namespace);
        if (old != null && (old.owner != account || !old.dimension.equals(dimension))) { old.owner.ambiguous = true; account.ambiguous = true; }
        else if (old == null) {
            this.namespaces.put(namespace, new Namespace(account, dimension)); account.dirty = true; account.revision++;
            if (this.bytes >= 0) for (var path : this.directoryFiles.getOrDefault(namespace, Set.of())) {
                account.bytes = Math.addExact(account.bytes, this.files.get(path)); rekey(path);
            }
        }
        if (link != null) {
            Account other = this.metadataOwners.putIfAbsent(link, account);
            if (other != null && other != account) { other.ambiguous = true; account.ambiguous = true; }
            else if (account.links.add(link)) {
                account.dirty = true; account.revision++;
                if (this.bytes >= 0 && other == null) {
                    account.bytes = Math.addExact(account.bytes, this.files.getOrDefault(link, 0L));
                    account.bytes = Math.addExact(account.bytes, this.files.getOrDefault(link.resolveSibling(link.getFileName() + ".pending"), 0L));
                }
            }
        }
        requestReconcile();
    }
    synchronized void retention(Account account, String dimension, long x, long z, Set<Long> visible) {
        if (account == null) return;
        if (x < -30_000_000L || x > 30_000_000L || z < -30_000_000L || z > 30_000_000L) throw new IllegalArgumentException("cache anchor outside Minecraft coordinates");
        Anchor before = account.anchors.get(dimension);
        if (before != null && before.x == x && before.z == z && before.visible.equals(visible)) return;
        account.anchors.put(dimension, new Anchor(x, z, Set.copyOf(visible)));
        if (before == null) { account.dirty = true; account.revision++; }
        for (var namespace : this.namespaces.entrySet()) if (namespace.getValue().owner == account
                && namespace.getValue().dimension.equals(dimension))
            for (var path : this.directoryFiles.getOrDefault(namespace.getKey(), Set.of())) rekey(path);
        if (account.bytes > account.limit || account.dirty) requestReconcile();
    }
    synchronized void flushAnchors(Account account) {
        if (account != null) { account.dirty = true; account.revision++; requestReconcile(); }
    }
    synchronized StorageState storage(Account account) {
        long assigned = this.accounts.values().stream().mapToLong(value -> value.bytes).sum();
        Admission status = status(account);
        return new StorageState(account == null || !ready() ? -1 : account.bytes,
                account == null ? -1 : account.limit, ready() ? this.bytes - assigned : -1,
                ready(), status != Admission.READY, status == Admission.READY ? "" : status.name());
    }
    synchronized long recoveryGeneration(String serverKey) {
        Account account = this.accounts.get(serverKey);
        status(account);
        return account == null ? 0 : account.recoveryGeneration;
    }
    private Admission status(Account account) {
        if (!ready()) return Admission.INVENTORY;
        if (account == null || account.ambiguous) return Admission.OWNERSHIP;
        if (account.diskFull) {
            if (!physicalSpace(account.blockedGrowth)) return Admission.DISK_FULL;
            account.diskFull = false; account.recoveryGeneration++;
        }
        if (account.bytes > account.limit) return Admission.QUOTA;
        return Admission.READY;
    }
    synchronized Admission admission(Path path, long added) {
        if (added < 0) return Admission.QUOTA;
        if (!this.files.containsKey(path)) {
            if (added > Long.MAX_VALUE - CompletedSectionJournal.HEADER_BYTES) return Admission.QUOTA;
            added += CompletedSectionJournal.HEADER_BYTES;
        }
        Account account = owner(path);
        Admission state = status(account);
        if (state != Admission.READY) return state;
        if (added > account.limit) return Admission.QUOTA;
        if (!physicalSpace(added)) {
            account.diskFull = true; account.blockedGrowth = Math.max(1, added); return Admission.DISK_FULL;
        }
        return victims(account, path, added, false) == null ? Admission.QUOTA : Admission.READY;
    }
    void reserve(Path path, long added) throws IOException {
        lock(this.changes);
        try {
            List<Path> victims;
            Account account;
            synchronized (this) {
                account = owner(path);
                Admission status = status(account);
                if (status != Admission.READY) throw new Capacity(status);
                if (added < 0 || added > account.limit) throw new Capacity(Admission.QUOTA);
                account.blockedGrowth = Math.max(1, added);
                if (!physicalSpace(added)) { account.diskFull = true; account.blockedGrowth = Math.max(1, added); throw new Capacity(Admission.DISK_FULL); }
                victims = victims(account, path, added, false);
                if (victims == null) throw new Capacity(Admission.QUOTA);
            }
            for (var victim : victims) if (!delete(victim)) throw new Capacity(Admission.QUOTA);
            synchronized (this) {
                if (added > account.limit - account.bytes) throw new Capacity(Admission.QUOTA);
                this.bytes = Math.addExact(this.bytes, added); this.files.merge(path, added, Math::addExact);
                this.directoryFiles.computeIfAbsent(path.getParent(), ignored -> new HashSet<>()).add(path);
                account.bytes = Math.addExact(account.bytes, added); rekey(path);
            }
        } finally { this.changes.unlock(); }
    }
    synchronized void writeFailure(Path path, IOException failure) {
        if (!outOfSpace(failure)) return;
        Account account = owner(path);
        if (account != null) account.diskFull = true;
    }
    static boolean outOfSpace(Throwable failure) {
        if (failure instanceof Capacity capacity) return capacity.reason == Admission.DISK_FULL;
        String message = failure.toString().toLowerCase(Locale.ROOT);
        return message.contains("no space") || message.contains("disk full") || message.contains("not enough space");
    }
    private boolean physicalSpace(long added) {
        try { return Files.getFileStore(this.root).getUsableSpace() >= added; }
        catch (IOException unavailable) { return false; }
    }
    synchronized void resized(Path path, long delta) {
        if (this.bytes < 0 || delta == 0) return;
        Account account = owner(path);
        this.bytes = Math.addExact(this.bytes, delta);
        long result = Math.addExact(this.files.getOrDefault(path, 0L), delta);
        if (result < 0) throw new IllegalStateException("cache file accounting underflow");
        if (result == 0) {
            this.files.remove(path);
            Set<Path> directory = this.directoryFiles.get(path.getParent());
            if (directory != null) { directory.remove(path); if (directory.isEmpty()) this.directoryFiles.remove(path.getParent()); }
        } else {
            this.files.put(path, result);
            this.directoryFiles.computeIfAbsent(path.getParent(), ignored -> new HashSet<>()).add(path);
        }
        if (account != null) account.bytes = Math.addExact(account.bytes, delta);
        rekey(path);
    }
    private Account owner(Path path) {
        String name = path.getFileName().toString();
        Path committed = name.endsWith(".pending") ? path.resolveSibling(name.substring(0, name.length() - 8)) : path;
        Account account = this.metadataOwners.get(committed);
        if (account != null) return account;
        Namespace namespace = this.namespaces.get(path.getParent());
        if (namespace != null) return namespace.owner;
        return path.equals(this.root.resolve("cache-format")) || path.equals(this.root.resolve(".voxy-cache.lock")) ? this.sharedOwner : null;
    }
    private void recount() {
        if (this.bytes < 0) return;
        for (var account : this.accounts.values()) { account.bytes = 0; account.victims.clear(); }
        this.ranked.clear();
        for (var entry : this.files.entrySet()) {
            Account account = owner(entry.getKey());
            if (account != null) account.bytes = Math.addExact(account.bytes, entry.getValue());
            rekey(entry.getKey());
        }
    }
    private void rekey(Path path) {
        Ranked old = this.ranked.remove(path);
        if (old != null) old.owner.victims.remove(old);
        if (!this.files.containsKey(path) || !path.getFileName().toString().endsWith(".vxlocal")) return;
        Account account = owner(path); Rank rank = rank(path);
        if (account == null || rank == null) return;
        var entry = new Ranked(path, account, rank); this.ranked.put(path, entry);
        Region region = this.regions.get(path);
        if (region == null || region.pins == 0 && !region.draining && region.writers == 0) account.victims.add(entry);
    }
    private Rank rank(Path path) {
        Namespace namespace = this.namespaces.get(path.getParent());
        if (namespace == null) return null;
        String[] name = path.getFileName().toString().split("\\.");
        if (name.length < 4 || !name[0].equals("r") || !name[3].equals("vxlocal")) return null;
        try {
            int x = Integer.parseInt(name[1]), z = Integer.parseInt(name[2]);
            Anchor anchor = namespace.owner.anchors.get(namespace.dimension);
            if (anchor == null) return null;
            long minX = Math.multiplyExact((long) x, 512), minZ = Math.multiplyExact((long) z, 512);
            long dx = Math.max(0, Math.max(minX - anchor.x, anchor.x - (minX + 512)));
            long dz = Math.max(0, Math.max(minZ - anchor.z, anchor.z - (minZ + 512)));
            long distance = Math.addExact(Math.multiplyExact(dx, dx), Math.multiplyExact(dz, dz));
            long key = Integer.toUnsignedLong(x) | (long) z << 32;
            return new Rank(anchor.visible.contains(key), distance);
        } catch (IllegalArgumentException | ArithmeticException invalid) { return null; }
    }
    private List<Path> victims(Account account, Path destination, long added, boolean reduction) {
        if (added <= account.limit - account.bytes) return List.of();
        Rank incoming = destination == null ? null : rank(destination);
        if (!reduction && incoming == null) return null;
        long available = Math.max(0, account.limit - account.bytes);
        long needed = Math.addExact(added, Math.max(0, account.bytes - account.limit));
        var selected = new ArrayList<Path>();
        for (var candidate : account.victims) {
            Path path = candidate.path;
            if (path.equals(destination)) continue;
            if (!reduction && candidate.rank.visible) continue;
            if (!reduction && !incoming.better(candidate.rank)) break;
            if (!ClientLodDebug.cacheDeletionAllowed(path)) continue;
            available = Math.addExact(available, this.files.get(path)); selected.add(path);
            if (available >= needed) return selected;
        }
        return null;
    }
    private synchronized void requestReconcile() {
        if (!ready() || this.reconciling || this.accounts.values().stream().noneMatch(account -> account.dirty || account.bytes > account.limit)) return;
        this.reconciling = true; retain();
        Thread.ofVirtual().name("Voxy cache policy").start(() -> {
            try { reconcile(); }
            catch (IOException failure) { me.cortex.voxy.common.Logger.warn("Cache policy persistence paused", failure); }
            finally { synchronized (RegionalDiskBudget.this) { reconciling = false; } release(); }
        });
    }
    private void reconcile() throws IOException {
        lock(this.changes);
        try {
            List<Account> current;
            synchronized (this) { current = List.copyOf(this.accounts.values()); }
            for (var account : current) {
                List<Path> remove;
                synchronized (this) { remove = victims(account, null, 0, true); }
                if (!physicalSpace(1)) { synchronized (this) { account.diskFull = true; account.blockedGrowth = 1; } continue; }
                if (remove != null) for (var path : remove) if (!delete(path)) break;
                if (account.dirty && ready()) try { writeAccount(account); }
                catch (Capacity paused) { /* Preserve both committed metadata and region files. */ }
            }
        } finally { this.changes.unlock(); }
    }
    private void readAccount(Path path) throws IOException {
        LocalCacheOwnership.rejectLinks(path);
        try (var input = new DataInputStream(new BufferedInputStream(Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)))) {
            if (input.readLong() != OWNER_MAGIC) throw new IOException("unknown cache ownership record");
            String key = input.readUTF(); long limit = input.readLong();
            if (!path.getFileName().toString().equals(RegionalMetadataStore.identifier(key) + ".vxowner")
                    || !path.getParent().getFileName().toString().equals("servers"))
                throw new IOException("cache ownership filename disagrees with server identity");
            if (limit < 100_000_000L) throw new IOException("invalid stored cache allowance");
            Account account;
            synchronized (this) {
                account = this.accounts.computeIfAbsent(key, value -> new Account(value, path, limit));
                this.metadataOwners.put(path, account);
                if (this.sharedOwner == null) this.sharedOwner = account;
            }
            int count = input.readInt();
            if (count < 0 || count > Files.size(path) / 6) throw new IOException("invalid ownership namespace count");
            for (int i = 0; i < count; i++) {
                Path namespace = ownedPath(input.readUTF()); String dimension = input.readUTF();
                long x = input.readLong(), z = input.readLong();
                if (x < -30_000_000L || x > 30_000_000L || z < -30_000_000L || z > 30_000_000L)
                    throw new IOException("stored cache anchor outside Minecraft coordinates");
                synchronized (this) {
                    Namespace old = this.namespaces.putIfAbsent(namespace, new Namespace(account, dimension));
                    if (old != null && old.owner != account) { account.ambiguous = true; old.owner.ambiguous = true; }
                    account.anchors.putIfAbsent(dimension, new Anchor(x, z, Set.of()));
                }
            }
            count = input.readInt();
            if (count < 0 || count > Files.size(path) / 2) throw new IOException("invalid ownership link count");
            for (int i = 0; i < count; i++) {
                Path link = ownedPath(input.readUTF());
                synchronized (this) {
                    account.links.add(link); Account old = this.metadataOwners.putIfAbsent(link, account);
                    if (old != null && old != account) { account.ambiguous = true; old.ambiguous = true; }
                }
            }
            if (input.read() != -1) throw new IOException("trailing cache ownership bytes");
        }
    }
    private Path ownedPath(String relative) throws IOException {
        Path path = this.root.resolve(relative).normalize();
        if (!path.startsWith(this.root) || path.equals(this.root)) throw new IOException("ownership path outside cache");
        LocalCacheOwnership.rejectLinks(path); return path;
    }
    private void writeAccount(Account account) throws IOException {
        var bytes = new ByteArrayOutputStream();
        long revision;
        try (var output = new DataOutputStream(bytes)) {
            synchronized (this) {
                revision = account.revision;
                output.writeLong(OWNER_MAGIC); output.writeUTF(account.key); output.writeLong(account.limit);
                var owned = this.namespaces.entrySet().stream().filter(entry -> entry.getValue().owner == account).toList();
                output.writeInt(owned.size());
                for (var entry : owned) {
                    output.writeUTF(this.root.relativize(entry.getKey()).toString()); output.writeUTF(entry.getValue().dimension);
                    Anchor anchor = account.anchors.get(entry.getValue().dimension);
                    output.writeLong(anchor == null ? 0 : anchor.x); output.writeLong(anchor == null ? 0 : anchor.z);
                }
                output.writeInt(account.links.size());
                for (var link : account.links) output.writeUTF(this.root.relativize(link).toString());
            }
        }
        Path pending = account.record.resolveSibling(account.record.getFileName() + ".pending");
        Files.createDirectories(account.record.getParent());
        long before = size(account.record), oldPending = size(pending);
        reserve(pending, Math.max(0, bytes.size() - oldPending));
        boolean installed = false;
        try {
            LocalCacheOwnership.rejectLinks(pending);
            try (var file = java.nio.channels.FileChannel.open(pending, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                var buffer = java.nio.ByteBuffer.wrap(bytes.toByteArray());
                while (buffer.hasRemaining()) if (file.write(buffer) <= 0) throw new IOException("short cache ownership write");
                file.force(true);
            }
            Files.move(pending, account.record, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            resized(pending, -Math.max(bytes.size(), oldPending)); resized(account.record, bytes.size() - before);
            synchronized (this) { account.dirty = account.revision != revision; } installed = true;
        } catch (IOException failure) {
            writeFailure(pending, failure); throw failure;
        } finally {
            if (!installed) { try { Files.deleteIfExists(pending); }
                finally { resized(pending, size(pending) - Math.max(bytes.size(), oldPending)); } }
        }
    }
    boolean delete(Path path) throws IOException {
        if (!path.getFileName().toString().endsWith(".vxlocal")) return false;
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
            finally { synchronized (this) { region.draining = false; this.notifyAll(); } }
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
            long length = this.files.getOrDefault(path, 0L);
            resized(path, -length);
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
        return name.equals("cache-format") || name.equals(".voxy-cache.lock") || name.endsWith(".vxlocal")
                || name.endsWith(".vxlocal.pending") || name.endsWith(".vxlink") || name.endsWith(".vxlink.pending")
                || name.endsWith(".vxcat") || name.endsWith(".vxcat.pending")
                || name.endsWith(".vxowner") || name.endsWith(".vxowner.pending");
    }
}
