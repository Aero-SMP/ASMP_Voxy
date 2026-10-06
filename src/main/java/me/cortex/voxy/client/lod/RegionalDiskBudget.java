package me.cortex.voxy.client.lod;

import java.io.*;
import java.lang.ref.WeakReference;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import me.cortex.voxy.client.config.ServerDownloadSettings;

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
    private long reconcileEvents;
    private boolean policyAvailable = ServerDownloadSettings.policiesAvailable();
    private String policyFailure = "Server download settings unavailable";
    private int mutations;
    private boolean diskPaused, policyProbe;
    private long pausedFree = -1, requiredGrowth = 1, physicalRecovery;
    private long spaceEpoch; // Invalidates observations when pause/policy/mutation ownership changes.
    private final Map<Path, Ranked> ranked = new HashMap<>();
    private final Map<Path, RegionalFile> regionalFiles = new HashMap<>();
    private final Map<Path, Region> regions = new HashMap<>();
    private long regionIncarnations;
    private final ReentrantLock changes = new ReentrantLock();
    private final java.util.concurrent.CountDownLatch disposed = new java.util.concurrent.CountDownLatch(1);
    private int owners;

    static final class Region {
        final ReentrantLock writer = new ReentrantLock(true);
        CompletedSectionJournal journal; // Region monitor; identity outlives the file incarnation.
        int pins, directories, writers; // Budget monitor, including waiting writer references.
        long rejectedBeforeBusy;
        volatile long incarnation;
        boolean draining;
        Region(long incarnation) { this.incarnation = incarnation; }
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
        refreshRecovery(path);
        Region region;
        synchronized (this) {
            requireMutation(path);
            region = this.regions.computeIfAbsent(path, ignored -> new Region(++this.regionIncarnations));
            if (region.pins == 0 && region.writers == 0) region.rejectedBeforeBusy = owner(path).rejections;
            if (region.writers++ == 0 && region.pins == 0) eligibility(path);
            beginMutation();
        }
        boolean locked = false, acquired = false;
        try {
            checkCurrent(current);
            lock(region.writer); locked = true;
            checkCurrent(current);
            synchronized (this) { requireMutation(path); }
            var writer = new Writer(path, region);
            acquired = true;
            return writer;
        } finally {
            if (!acquired) {
                if (locked) region.writer.unlock();
                releaseWriter(path, region, false);
            }
        }
    }
    final class Writer implements AutoCloseable {
        final Path path;
        private final Region region;
        private boolean closed;
        private boolean completed;
        private boolean rejected;
        private final long beforeBytes;
        private long growth;
        private Writer(Path path, Region region) {
            this.path = path; this.region = region;
            synchronized (RegionalDiskBudget.this) { this.beforeBytes = files.getOrDefault(path, 0L); }
        }
        boolean fits(long growth) {
            for (;;) {
                var observation = probeSpace();
                synchronized (RegionalDiskBudget.this) {
                    if (observation.epoch != spaceEpoch) continue;
                    return physicalSpace(growth, observation);
                }
            }
        }
        void expect(long growth) throws IOException {
            if (growth < 0) throw new IOException("invalid cache write extent");
            this.growth = growth;
            for (;;) {
                synchronized (RegionalDiskBudget.this) { requireMutation(this.path); }
                var observation = probeSpace();
                synchronized (RegionalDiskBudget.this) {
                    if (observation.epoch != spaceEpoch) continue;
                    requireMutation(this.path);
                    if (!physicalSpace(growth, observation)) { pauseDisk(growth, observation); throw new Capacity(Admission.DISK_FULL); }
                    return;
                }
            }
        }
        void completed() { this.completed = true; this.rejected = false; }
        void failed(IOException failure) {
            if (failure instanceof Capacity || outOfSpace(failure)) this.rejected = true;
            writeFailure(this.path, failure, this.growth);
        }
        boolean compact(RegionalProtocol.Hash32 world, long key, java.util.function.BooleanSupplier current) throws IOException {
            synchronized (RegionalDiskBudget.this) {
                if (this.region.pins != 0 || this.region.draining) return false;
                this.region.draining = true;
            }
            Path temporary = this.path.resolveSibling(this.path.getFileName() + ".pending");
            long reserved = 0;
            boolean installed = false, touched = false;
            try {
                checkCurrent(current);
                if (!Files.isRegularFile(this.path, LinkOption.NOFOLLOW_LINKS) || size(this.path) < CompletedSectionJournal.HEADER_BYTES) return false;
                CompletedSectionJournal journal = this.region.journal;
                if (journal == null || journal.closed()) {
                    journal = CompletedSectionJournal.open(this.path, world, key, false);
                    this.region.journal = journal;
                }
                long compactBytes = journal.compactedBytes();
                long oldBytes = size(this.path);
                if (compactBytes >= oldBytes) return false;
                expect(compactBytes);
                commit(this.path, () -> {
                    LocalCacheOwnership.rejectLinks(temporary);
                    long oldTemporary = size(temporary);
                    if (Files.deleteIfExists(temporary)) resized(temporary, -oldTemporary);
                });
                touched = true;
                reserve(temporary, compactBytes); reserved = compactBytes;
                journal.compactTo(temporary, current);
                try (var verified = CompletedSectionJournal.open(temporary, world, key, false)) {
                    if (verified.bytes() != compactBytes || !verified.directory().equals(journal.directory()))
                        throw new IOException("compacted journal validation failed");
                }
                checkCurrent(current);
                journal.close(); this.region.journal = null;
                replace(temporary, this.path, compactBytes, oldBytes, compactBytes, current); reserved = 0;
                installed = true; completed();
                this.region.journal = CompletedSectionJournal.open(this.path, world, key, false);
                return true;
            } catch (IOException failure) {
                writeFailure(this.path, failure, this.growth); throw failure;
            } finally {
                try {
                    if (!installed && touched) {
                        try { Files.deleteIfExists(temporary); }
                        catch (IOException failure) { writeFailure(this.path, failure, this.growth); throw failure; }
                        finally { if (reserved != 0) resized(temporary, size(temporary) - reserved); }
                    }
                } finally {
                    synchronized (RegionalDiskBudget.this) { this.region.draining = false; RegionalDiskBudget.this.notifyAll(); }
                }
            }
        }
        @Override public void close() {
            if (this.closed) return;
            this.closed = true;
            boolean reclaimed;
            synchronized (RegionalDiskBudget.this) { reclaimed = this.completed && files.getOrDefault(this.path, 0L) < this.beforeBytes; }
            this.region.writer.unlock(); releaseWriter(this.path, this.region, reclaimed, true, !this.rejected);
        }
    }
    private void releaseWriter(Path path, Region region, boolean completed) {
        releaseWriter(path, region, completed, true, false);
    }
    private void releaseWriter(Path path, Region region, boolean reclaimed, boolean mutation, boolean eligible) {
        synchronized (this) {
            if (--region.writers < 0) throw new IllegalStateException("cache writer underflow");
            if (region.writers == 0 && region.pins == 0) eligibility(path);
            Account account = owner(path);
            if (account != null && safety(account) == Admission.READY && (reclaimed
                    || eligible && region.writers == 0 && region.pins == 0 && this.ranked.containsKey(path)
                    && account.rejections > region.rejectedBeforeBusy)) eligibilityChanged(account);
            if (mutation && region.writers == 0 && region.pins == 0 && account != null
                    && safety(account) == Admission.READY && account.bytes > account.limit)
                requestReconcile();
        }
        if (mutation) finishMutation();
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
            this.state = InventoryState.CLOSED; this.spaceEpoch++;
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
            lock(this.changes);
            try {
                for (var entry : observed) if (entry.getKey().toString().endsWith(".pending")) {
                    checkInventory();
                    if (!beginInventoryCleanup(entry.getKey())) continue;
                    try {
                        LocalCacheOwnership.rejectLinks(entry.getKey());
                        Files.deleteIfExists(entry.getKey()); resized(entry.getKey(), -entry.getValue().size());
                    } catch (IOException failure) {
                        writeFailure(entry.getKey(), failure); throw failure;
                    } finally { finishMutation(); }
                }
            } finally { this.changes.unlock(); }
            ServerDownloadSettings resolvedPolicy = null;
            synchronized (this) {
                checkInventory(); this.state = InventoryState.READY; this.spaceEpoch++;
                boolean rerank = false;
                for (var account : this.accounts.values()) {
                    if (account.defaultPolicy != null) {
                        long resolved = account.defaultPolicy.retainStorageBytes(account.persistedAllowance ? account.limit : -1, true);
                        if (resolved >= ServerDownloadSettings.MIN_STORAGE_BYTES) {
                            rerank |= account.limit != resolved; account.limit = resolved;
                        }
                        resolvedPolicy = account.defaultPolicy;
                    }
                    eligibilityChanged(account);
                }
                if (rerank) recount();
            }
            if (resolvedPolicy != null) resolvedPolicy.save();
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
    private boolean beginInventoryCleanup(Path path) {
        for (;;) {
            var observation = probeSpace();
            synchronized (this) {
                if (observation.epoch != this.spaceEpoch) continue;
                Account account = owner(path);
                if (!this.policyAvailable || account == null || account.ambiguous || this.diskPaused) return false;
                if (!physicalSpace(1, observation)) { pauseDisk(1, observation); return false; }
                beginMutation(); return true;
            }
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
    Pin pin(Path path) throws IOException {
        Pin pin;
        synchronized (this) {
            if (this.state == InventoryState.CLOSED) throw new IOException("cache file unavailable");
            Region region = this.regions.computeIfAbsent(path, ignored -> new Region(++this.regionIncarnations));
            if (region.pins == 0 && region.writers == 0) {
                Account account = owner(path); region.rejectedBeforeBusy = account == null ? 0 : account.rejections;
            }
            // Count a waiting reader too: deletion must not forget its Region before it wakes.
            if (region.pins++ == 0 && region.writers == 0) eligibility(path);
            pin = new Pin(path, region);
        }
        boolean acquired = false;
        try {
            synchronized (this) {
                while (pin.region.draining) try { this.wait(); }
                catch (InterruptedException stopped) {
                    Thread.currentThread().interrupt(); throw new InterruptedIOException("cache replacement wait interrupted");
                }
                if (this.state == InventoryState.CLOSED) throw new IOException("cache file unavailable");
                acquired = true; return pin;
            }
        } finally { if (!acquired) pin.close(); }
    }
    final class Pin implements AutoCloseable {
        private final Path path; private final Region region; private boolean closed;
        private Pin(Path path, Region region) { this.path = path; this.region = region; }
        long incarnation() { return this.region.incarnation; }
        @Override public void close() {
            synchronized (RegionalDiskBudget.this) {
                if (this.closed) return;
                this.closed = true;
                if (--this.region.pins < 0) throw new IllegalStateException("cache pin underflow");
                if (this.region.pins == 0) {
                    if (this.region.writers == 0) eligibility(this.path);
                    Account account = owner(this.path);
                    if (this.region.writers == 0 && ranked.containsKey(this.path) && account != null && safety(account) == Admission.READY) {
                        boolean needed = account.rejections > this.region.rejectedBeforeBusy;
                        if (needed) eligibilityChanged(account);
                        if (needed || account.bytes > account.limit) requestReconcile();
                    }
                }
                RegionalDiskBudget.this.notifyAll();
            }
            forgetUnused(this.path);
        }
    }
    synchronized Region retainDirectory(Path path) {
        var region = this.regions.computeIfAbsent(path, ignored -> new Region(++this.regionIncarnations));
        region.directories++; return region;
    }
    synchronized void invalidateDirectory(Path path) {
        var region = this.regions.get(path);
        if (region != null) region.incarnation = ++this.regionIncarnations;
    }
    synchronized long incarnation(Path path) {
        var region = this.regions.get(path);
        return region == null ? 0 : region.incarnation;
    }
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

    synchronized boolean knownAbsent(Path path) {
        if (this.state != InventoryState.READY || this.files.getOrDefault(path, 0L) != 0) return false;
        var region = this.regions.get(path);
        return region == null || region.writers == 0 && !region.draining;
    }

    enum Admission { READY, INVENTORY, POLICY, OWNERSHIP, QUOTA, DISK_FULL }
    static final class Capacity extends IOException {
        final Admission reason;
        Capacity(Admission reason) { super("cache admission paused: " + reason); this.reason = reason; }
    }
    record StorageState(long bytes, long limit, long unownedBytes, boolean ready, boolean downloadPaused, String reason) {}
    static final class Account {
        final String key;
        final Path record;
        final Set<Path> links = new HashSet<>();
        final Set<NamespaceClaim> claims = new HashSet<>();
        final Map<String, Anchor> anchors = new HashMap<>();
        final Map<String, Map<Long, Set<Path>>> spatialFiles = new HashMap<>();
        long limit, bytes;
        boolean persistedAllowance;
        ServerDownloadSettings defaultPolicy;
        volatile boolean dirty, ambiguous;
        boolean claimsDirty;
        long revision, admissionGeneration, rejections;
        final NavigableSet<Ranked> victims = new TreeSet<>((a, b) -> {
            int result = Boolean.compare(a.rank.visible, b.rank.visible);
            if (result == 0) result = Long.compare(b.rank.distance, a.rank.distance);
            return result == 0 ? a.path.toString().compareTo(b.path.toString()) : result;
        });
        Account(String key, Path record, long limit) { this.key = key; this.record = record; this.limit = limit; }
    }
    private record Anchor(long x, long z, Set<Long> visible) {}
    private record Namespace(Account owner, String dimension) {}
    private record NamespaceClaim(Path path, String dimension) {}
    private record Coordinates(int x, int z, long key) {}
    private record RegionalFile(Coordinates coordinates, Namespace namespace) {}
    private record Ranked(Path path, Account owner, Rank rank) {}
    private record Rank(boolean visible, long distance) {
        boolean better(Rank other) { return this.visible != other.visible ? this.visible : this.distance < other.distance; }
    }
    private static final long OWNER_MAGIC = 0x5658594f574e4552L;

    Account configure(ServerDownloadSettings policy) throws IOException {
        String key = policy.serverId(); long limit = policy.storageBytes(); String reason = policy.failureReason();
        if (key == null || key.isBlank() || limit != -1 && limit < 100_000_000L)
            throw new IOException("invalid server cache policy");
        lock(this.changes);
        try { synchronized (this) {
            boolean created = !this.accounts.containsKey(key);
            Account account = this.accounts.get(key);
            if (account == null) {
                account = new Account(key, ClientLodDebug.cacheNamespace(this.root).resolve("servers")
                        .resolve(RegionalMetadataStore.identifier(key) + ".vxowner"), limit);
                this.accounts.put(key, account);
            }
            boolean available = limit != -1;
            if (available) limit = policy.retainStorageBytes(account.persistedAllowance ? account.limit : -1, ready());
            account.defaultPolicy = available && !policy.storageSelected() ? policy : null;
            boolean changed = this.policyAvailable != available || available && account.limit != limit;
            this.policyAvailable = available;
            this.policyFailure = reason == null || reason.isBlank() ? "Server download settings unavailable" : reason;
            if (available && (created || account.limit != limit)) { account.limit = limit; account.dirty = true; account.revision++; }
            Account oldOwner = this.metadataOwners.put(account.record, account);
            boolean ownershipChanged = oldOwner != account || this.sharedOwner == null;
            if (this.sharedOwner == null) this.sharedOwner = account;
            if (changed) {
                if (this.diskPaused && available) this.policyProbe = true;
                for (var value : this.accounts.values()) eligibilityChanged(value);
            }
            if (created || ownershipChanged || changed) { this.spaceEpoch++; recount(); }
            requestReconcile(); return account;
        } } finally { this.changes.unlock(); }
    }
    void claim(Account account, Path namespace, String dimension, Path link) throws IOException {
        if (account == null) throw new IOException("cache server ownership is not bound");
        namespace = namespace.toAbsolutePath().normalize();
        if (!namespace.startsWith(this.root) || namespace.equals(this.root)) throw new IOException("namespace outside owned cache");
        lock(this.changes);
        try { synchronized (this) {
            boolean changed = account.claims.add(new NamespaceClaim(namespace, dimension));
            Namespace old = this.namespaces.get(namespace);
            if (old != null && (old.owner != account || !old.dimension.equals(dimension))) {
                old.owner.ambiguous = true; account.ambiguous = true; eligibilityChanged(old.owner);
            } else if (old == null) {
                this.namespaces.put(namespace, new Namespace(account, dimension));
                if (this.bytes >= 0) for (var path : this.directoryFiles.getOrDefault(namespace, Set.of())) {
                    account.bytes = Math.addExact(account.bytes, this.files.get(path)); rekey(path);
                }
            }
            if (link != null) {
                Account other = this.metadataOwners.putIfAbsent(link, account);
                if (other != null && other != account) {
                    other.ambiguous = true; account.ambiguous = true; eligibilityChanged(other);
                }
                if (account.links.add(link)) {
                    changed = true;
                    if (this.bytes >= 0 && other == null) {
                        account.bytes = Math.addExact(account.bytes, this.files.getOrDefault(link, 0L));
                        account.bytes = Math.addExact(account.bytes, this.files.getOrDefault(link.resolveSibling(link.getFileName() + ".pending"), 0L));
                    }
                }
            }
            if (changed) { account.dirty = account.claimsDirty = true; account.revision++; this.spaceEpoch++; eligibilityChanged(account); }
            requestReconcile();
        } } finally { this.changes.unlock(); }
    }
    void retention(Account account, String dimension, long x, long z, Set<Long> visible) {
        if (account == null) return;
        if (x < -30_000_000L || x > 30_000_000L || z < -30_000_000L || z > 30_000_000L)
            throw new IllegalArgumentException("cache anchor outside Minecraft coordinates");
        this.changes.lock();
        try { synchronized (this) {
            Anchor before = account.anchors.get(dimension);
            if (before != null && before.x == x && before.z == z && before.visible.equals(visible)) return;
            Set<Long> nextVisible = Set.copyOf(visible);
            account.anchors.put(dimension, new Anchor(x, z, nextVisible));
            if (before == null) { account.dirty = true; account.revision++; }
            if (account.limit != Long.MAX_VALUE) {
                if (before == null || before.x != x || before.z != z) {
                    // Exact distances all change when the anchor moves.
                    for (var namespace : this.namespaces.entrySet()) if (namespace.getValue().owner == account
                            && namespace.getValue().dimension.equals(dimension))
                        for (var path : this.directoryFiles.getOrDefault(namespace.getKey(), Set.of())) rekey(path, true);
                } else {
                    var indexed = account.spatialFiles.get(dimension);
                    if (indexed != null) {
                        // Two set passes compute the symmetric difference, with no namespace/key Cartesian scan.
                        for (long key : before.visible) if (!nextVisible.contains(key))
                            for (var path : indexed.getOrDefault(key, Set.of())) rekey(path, true);
                        for (long key : nextVisible) if (!before.visible.contains(key))
                            for (var path : indexed.getOrDefault(key, Set.of())) rekey(path, true);
                    }
                }
            }
            eligibilityChanged(account); requestReconcile();
        } } finally { this.changes.unlock(); }
    }
    synchronized void flushAnchors(Account account) {
        if (account != null) { account.dirty = true; account.revision++; requestReconcile(); }
    }
    private void eligibilityChanged(Account account) { if (account != null) account.admissionGeneration++; }
    private void admissionBlocked(Account account) {
        if (account != null) account.rejections++;
    }
    long admissionGeneration(String serverKey) {
        Account account;
        synchronized (this) { account = this.accounts.get(serverKey); }
        refreshRecovery(account, false);
        synchronized (this) { return account == null ? 0 : account.admissionGeneration; }
    }
    StorageState storage(Account account) {
        refreshRecovery(account, false);
        synchronized (this) {
            long assigned = this.accounts.values().stream().mapToLong(value -> value.bytes).sum();
            Admission status = status(account);
            return new StorageState(account == null || !ready() ? -1 : account.bytes,
                    account == null ? -1 : account.limit, ready() ? this.bytes - assigned : -1,
                    ready(), status != Admission.READY, status == Admission.READY ? ""
                    : status == Admission.POLICY ? this.policyFailure : status.name());
        }
    }
    long recoveryGeneration(String serverKey) {
        Account account;
        synchronized (this) { account = this.accounts.get(serverKey); }
        refreshRecovery(account, false);
        synchronized (this) { return this.physicalRecovery; }
    }
    /** Pure monitor-held predicate; probes and recovery happen before entering the monitor. */
    private Admission safety(Account account) { return safety(account, false); }
    private Admission safety(Account account, boolean ownershipRecord) {
        Admission base = policySafety(account, ownershipRecord);
        return base != Admission.READY ? base : this.diskPaused ? Admission.DISK_FULL : Admission.READY;
    }
    private Admission policySafety(Account account, boolean ownershipRecord) {
        if (!ready()) return Admission.INVENTORY;
        if (!this.policyAvailable || account != null && account.limit < ServerDownloadSettings.MIN_STORAGE_BYTES) return Admission.POLICY;
        if (account == null || account.ambiguous && !ownershipRecord) return Admission.OWNERSHIP;
        return Admission.READY;
    }
    private void refreshRecovery(Path path) {
        Account account; boolean preserving;
        synchronized (this) { account = owner(path); preserving = preservingClaims(path, account); }
        refreshRecovery(account, preserving);
    }
    private void refreshRecovery(Account account, boolean ownershipRecord) {
        synchronized (this) {
            if (!this.diskPaused || this.mutations != 0 || policySafety(account, ownershipRecord) != Admission.READY) return;
        }
        SpaceObservation observation;
        try { observation = probeSpace(); }
        catch (java.util.concurrent.CancellationException stopped) { return; }
        synchronized (this) {
            if (!this.diskPaused || observation.epoch != this.spaceEpoch || this.mutations != 0
                    || policySafety(account, ownershipRecord) != Admission.READY || observation.free < 0) return;
            if (this.pausedFree < 0) {
                this.pausedFree = observation.free;
                if (!this.policyProbe) return;
            }
            if (observation.free < this.requiredGrowth || !this.policyProbe && observation.free <= this.pausedFree) return;
            this.diskPaused = false; this.policyProbe = false; this.pausedFree = -1;
            this.requiredGrowth = 1; this.physicalRecovery++; this.spaceEpoch++;
            for (var value : this.accounts.values()) eligibilityChanged(value);
            requestReconcile();
        }
    }
    private Admission status(Account account) {
        Admission state = safety(account);
        return state == Admission.READY && account.bytes > account.limit ? Admission.QUOTA : state;
    }
    private void requireMutation(Path path) throws Capacity {
        Account account = owner(path); Admission state = safety(account, preservingClaims(path, account));
        if (state == Admission.READY && account.bytes > account.limit
                && !preservingClaims(path, account)) state = Admission.QUOTA;
        if (state != Admission.READY) {
            admissionBlocked(account);
            throw new Capacity(state);
        }
    }
    private boolean ownershipRecord(Path path, Account account) {
        return account != null && (path.equals(account.record) || path.equals(account.record.resolveSibling(account.record.getFileName() + ".pending")));
    }
    private boolean preservingClaims(Path path, Account account) {
        return account != null && account.ambiguous && account.claimsDirty && ownershipRecord(path, account);
    }
    Admission admission(Path path, long added) {
        refreshRecovery(path);
        for (;;) {
            synchronized (this) {
                Account account = owner(path); Admission state = status(account);
                long growth = added;
                if (growth < 0 || !this.files.containsKey(path) && growth > Long.MAX_VALUE - CompletedSectionJournal.HEADER_BYTES)
                    return Admission.QUOTA;
                if (!this.files.containsKey(path)) growth += CompletedSectionJournal.HEADER_BYTES;
                if (state == Admission.READY && (growth > account.limit || victims(account, path, growth, false) == null)) state = Admission.QUOTA;
                if (state != Admission.READY) { admissionBlocked(account); return state; }
            }
            var observation = probeSpace();
            synchronized (this) {
                if (observation.epoch != this.spaceEpoch) continue;
                if (added < 0) return Admission.QUOTA;
                long growth = added;
                if (!this.files.containsKey(path)) {
                    if (growth > Long.MAX_VALUE - CompletedSectionJournal.HEADER_BYTES) return Admission.QUOTA;
                    growth += CompletedSectionJournal.HEADER_BYTES;
                }
                Account account = owner(path); Admission state = status(account);
                if (state == Admission.READY) {
                    if (growth > account.limit || victims(account, path, growth, false) == null) state = Admission.QUOTA;
                    else if (!physicalSpace(growth, observation)) { pauseDisk(growth, observation); state = Admission.DISK_FULL; }
                }
                if (state != Admission.READY) admissionBlocked(account);
                return state;
            }
        }
    }
    void reserve(Path path, long added) throws IOException {
        lock(this.changes);
        try {
            refreshRecovery(path);
            List<Path> selected; Account account; boolean preserveClaims;
            for (;;) {
                synchronized (this) { requireMutation(path); }
                var observation = probeSpace();
                synchronized (this) {
                    if (observation.epoch != this.spaceEpoch) continue;
                    requireMutation(path); account = owner(path);
                    preserveClaims = preservingClaims(path, account);
                    if (added < 0 || !preserveClaims && added > account.limit) { admissionBlocked(account); throw new Capacity(Admission.QUOTA); }
                    if (!physicalSpace(added, observation)) { pauseDisk(added, observation); throw new Capacity(Admission.DISK_FULL); }
                    // Conflict evidence is preservation metadata, never permission to evict terrain.
                    selected = preserveClaims ? List.of() : victims(account, path, added, false);
                    if (selected == null) { admissionBlocked(account); throw new Capacity(Admission.QUOTA); }
                    if (selected.isEmpty()) { charge(path, account, added, preserveClaims); return; }
                    break;
                }
            }
            for (var victim : selected) {
                synchronized (this) { if (added <= account.limit - account.bytes) break; }
                if (!delete(victim, account, path, false)) throw new Capacity(Admission.QUOTA);
            }
            for (;;) {
                var observation = probeSpace();
                synchronized (this) {
                    if (observation.epoch != this.spaceEpoch) continue;
                    requireMutation(path);
                    if (!physicalSpace(added, observation)) { pauseDisk(added, observation); throw new Capacity(Admission.DISK_FULL); }
                    charge(path, account, added, preserveClaims); break;
                }
            }
        } finally { this.changes.unlock(); }
    }
    private void charge(Path path, Account account, long added, boolean preserveClaims) throws Capacity {
        requireMutation(path);
        if (!preserveClaims && added > account.limit - account.bytes) { admissionBlocked(account); throw new Capacity(Admission.QUOTA); }
        this.bytes = Math.addExact(this.bytes, added); this.files.merge(path, added, Math::addExact);
        this.directoryFiles.computeIfAbsent(path.getParent(), ignored -> new HashSet<>()).add(path);
        account.bytes = Math.addExact(account.bytes, added); this.spaceEpoch++; rekey(path);
    }
    void commit(Path path, CompletedSectionJournal.IOAction action) throws IOException {
        lock(this.changes);
        try { refreshRecovery(path); synchronized (this) { requireMutation(path); } action.run(); }
        finally { this.changes.unlock(); }
    }
    void replace(Path temporary, Path path, long length, long before, long chargedTemporary,
                 java.util.function.BooleanSupplier current) throws IOException {
        commit(path, () -> {
            checkCurrent(current); LocalCacheOwnership.rejectLinks(path); LocalCacheOwnership.rejectLinks(temporary);
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            resized(temporary, -chargedTemporary); resized(path, length - before);
        });
    }
    void writeFailure(Path path, IOException failure) { writeFailure(path, failure, 1); }
    void writeFailure(Path path, IOException failure, long growth) {
        if (!outOfSpace(failure)) return;
        synchronized (this) { pauseDisk(Math.max(1, growth), null); }
        establishPausedBaseline();
    }
    static boolean outOfSpace(Throwable failure) {
        if (failure instanceof Capacity capacity) return capacity.reason == Admission.DISK_FULL;
        String message = failure.toString().toLowerCase(Locale.ROOT);
        return message.contains("no space") || message.contains("disk full") || message.contains("not enough space");
    }
    private record SpaceObservation(long epoch, long free) {}
    private SpaceObservation probeSpace() {
        // Reprobing on mutation churn must remain cancellable without manufacturing a disk-full pause.
        if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("cache disk observation interrupted");
        long epoch;
        synchronized (this) { epoch = this.spaceEpoch; }
        long free;
        try { free = Files.getFileStore(this.root).getUsableSpace(); }
        catch (IOException unavailable) { free = -1; }
        if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("cache disk observation interrupted");
        return new SpaceObservation(epoch, free);
    }
    private boolean physicalSpace(long added, SpaceObservation observation) {
        // Policy, mutation and pause identity must still match at the admission boundary.
        return observation.epoch == this.spaceEpoch && observation.free >= added;
    }
    private void pauseDisk(long growth, SpaceObservation observation) {
        if (!this.diskPaused) {
            this.diskPaused = true; this.pausedFree = -1; this.requiredGrowth = 1; this.policyProbe = false;
        }
        this.requiredGrowth = Math.max(this.requiredGrowth, growth);
        if (this.mutations == 0 && this.pausedFree < 0 && observation != null
                && observation.epoch == this.spaceEpoch && observation.free >= 0) this.pausedFree = observation.free;
        this.spaceEpoch++;
    }
    private void beginMutation() { this.mutations++; this.spaceEpoch++; }
    private void finishMutation() {
        synchronized (this) {
            if (--this.mutations < 0) throw new IllegalStateException("cache transaction underflow");
            this.spaceEpoch++;
            if (this.diskPaused && this.mutations == 0) this.pausedFree = -1;
        }
        establishPausedBaseline();
    }
    private void establishPausedBaseline() {
        synchronized (this) { if (!this.diskPaused || this.mutations != 0 || this.pausedFree >= 0) return; }
        SpaceObservation observation;
        try { observation = probeSpace(); }
        catch (java.util.concurrent.CancellationException stopped) {
            // Cleanup still releases ownership; a later fresh probe establishes baseline.
            return;
        }
        synchronized (this) {
            if (this.diskPaused && this.mutations == 0 && this.pausedFree < 0
                    && observation.epoch == this.spaceEpoch && observation.free >= 0) this.pausedFree = observation.free;
        }
    }
    synchronized void resized(Path path, long delta) {
        if (this.bytes < 0 || delta == 0) return;
        this.spaceEpoch++;
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
        for (var account : this.accounts.values()) { account.bytes = 0; account.victims.clear(); account.spatialFiles.clear(); }
        this.ranked.clear();
        // Keep coordinates across rebuilds; reset only namespace attribution and spatial membership.
        this.regionalFiles.replaceAll((path, file) -> new RegionalFile(file.coordinates, null));
        this.regionalFiles.keySet().removeIf(path -> !this.files.containsKey(path));
        for (var entry : this.files.entrySet()) {
            Account account = owner(entry.getKey());
            if (account != null) account.bytes = Math.addExact(account.bytes, entry.getValue());
            rekey(entry.getKey());
        }
    }
    private boolean indexFile(Path path) {
        RegionalFile old = this.regionalFiles.get(path);
        Namespace namespace = this.namespaces.get(path.getParent());
        Account account = owner(path);
        boolean present = this.files.containsKey(path) && path.getFileName().toString().endsWith(".vxlocal")
                && account != null && account.limit != Long.MAX_VALUE;
        if (old != null && present && Objects.equals(old.namespace, namespace)) return false;
        if (old != null && old.namespace != null) {
            var byDimension = old.namespace.owner.spatialFiles.get(old.namespace.dimension);
            var paths = byDimension == null ? null : byDimension.get(old.coordinates.key);
            if (paths != null) {
                paths.remove(path);
                if (paths.isEmpty()) byDimension.remove(old.coordinates.key);
                if (byDimension.isEmpty()) old.namespace.owner.spatialFiles.remove(old.namespace.dimension);
            }
        }
        if (!present) { this.regionalFiles.remove(path); return old != null; }
        Coordinates coordinates = old == null ? parseCoordinates(path) : old.coordinates;
        if (coordinates == null) return false;
        this.regionalFiles.put(path, new RegionalFile(coordinates, namespace));
        if (namespace != null) namespace.owner.spatialFiles.computeIfAbsent(namespace.dimension, ignored -> new HashMap<>())
                .computeIfAbsent(coordinates.key, ignored -> new HashSet<>()).add(path);
        return true;
    }
    private void rekey(Path path) { rekey(path, false); }
    private void rekey(Path path, boolean inputsChanged) {
        inputsChanged |= indexFile(path);
        Ranked old = this.ranked.get(path);
        Account account = owner(path);
        if (!inputsChanged && old != null && old.owner == account) return;
        Rank rank = account == null || account.limit == Long.MAX_VALUE || !this.regionalFiles.containsKey(path) ? null : calculateRank(path);
        if (old != null && old.owner == account && old.rank.equals(rank)) { eligibility(path); return; }
        if (old != null) { this.ranked.remove(path); old.owner.victims.remove(old); }
        if (rank == null) return;
        var entry = new Ranked(path, account, rank); this.ranked.put(path, entry);
        eligibility(path);
    }
    private void eligibility(Path path) {
        Ranked entry = this.ranked.get(path);
        if (entry == null) return;
        Region region = this.regions.get(path);
        if (region == null || region.pins == 0 && !region.draining && region.writers == 0) entry.owner.victims.add(entry);
        else entry.owner.victims.remove(entry);
    }
    private static Coordinates parseCoordinates(Path path) {
        String[] name = path.getFileName().toString().split("\\.");
        if (name.length < 4 || !name[0].equals("r") || !name[3].equals("vxlocal")) return null;
        try {
            int x = Integer.parseInt(name[1]), z = Integer.parseInt(name[2]);
            return new Coordinates(x, z, Integer.toUnsignedLong(x) | (long) z << 32);
        } catch (NumberFormatException invalid) { return null; }
    }
    private Rank rank(Path path) {
        Ranked existing = this.ranked.get(path);
        return existing == null ? calculateRank(path) : existing.rank;
    }
    private Rank calculateRank(Path path) {
        RegionalFile file = this.regionalFiles.get(path);
        Namespace namespace = file == null ? this.namespaces.get(path.getParent()) : file.namespace;
        if (namespace == null) return null;
        Coordinates coordinates = file == null ? parseCoordinates(path) : file.coordinates;
        Anchor anchor = namespace.owner.anchors.get(namespace.dimension);
        if (coordinates == null || anchor == null) return null;
        try {
            long minX = (long) coordinates.x * 512, minZ = (long) coordinates.z * 512;
            long dx = Math.max(0, Math.max(minX - anchor.x, anchor.x - (minX + 512)));
            long dz = Math.max(0, Math.max(minZ - anchor.z, anchor.z - (minZ + 512)));
            long distance = Math.addExact(Math.multiplyExact(dx, dx), Math.multiplyExact(dz, dz));
            return new Rank(anchor.visible.contains(coordinates.key), distance);
        } catch (ArithmeticException invalid) { return null; }
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
        if (!ready() || !needsReconcile()) return;
        this.reconcileEvents++;
        if (this.reconciling) return;
        this.reconciling = true; retain();
        Thread.ofVirtual().name("Voxy cache policy").start(() -> {
            boolean relinquished = false;
            try {
                for (;;) {
                    long observed;
                    synchronized (RegionalDiskBudget.this) { observed = reconcileEvents; }
                    try { reconcile(); }
                    catch (IOException failure) { me.cortex.voxy.common.Logger.warn("Cache policy persistence paused", failure); }
                    synchronized (RegionalDiskBudget.this) {
                        if (ready() && observed != reconcileEvents && needsReconcile()) continue;
                        reconciling = false; relinquished = true; break;
                    }
                }
            } finally {
                if (!relinquished) synchronized (RegionalDiskBudget.this) { reconciling = false; }
                release();
            }
        });
    }
    private boolean needsReconcile() {
        return this.accounts.values().stream().anyMatch(account -> account.dirty || account.limit >= 0 && account.bytes > account.limit);
    }
    private void reconcile() throws IOException {
        lock(this.changes);
        try {
            List<Account> current;
            synchronized (this) { current = List.copyOf(this.accounts.values()); }
            for (var account : current) {
                refreshRecovery(account, false);
                List<Path> remove;
                synchronized (this) {
                    remove = safety(account) == Admission.READY ? victims(account, null, 0, true) : null;
                }
                if (remove != null) for (var path : remove) {
                    synchronized (this) { if (account.bytes <= account.limit) break; }
                    if (!delete(path, account, null, true)) break;
                }
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
                account.persistedAllowance = true;
                if (account.defaultPolicy != null) {
                    long retained = account.defaultPolicy.retainStorageBytes(limit, false);
                    account.limit = retained >= ServerDownloadSettings.MIN_STORAGE_BYTES ? retained : limit;
                } else if (account.limit == -1) account.limit = limit;
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
                    account.claims.add(new NamespaceClaim(namespace, dimension));
                    Namespace old = this.namespaces.putIfAbsent(namespace, new Namespace(account, dimension));
                    if (old != null && (old.owner != account || !old.dimension.equals(dimension))) {
                        account.ambiguous = true; old.owner.ambiguous = true;
                    }
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
                output.writeInt(account.claims.size());
                for (var claim : account.claims) {
                    output.writeUTF(this.root.relativize(claim.path).toString()); output.writeUTF(claim.dimension);
                    Anchor anchor = account.anchors.get(claim.dimension);
                    output.writeLong(anchor == null ? 0 : anchor.x); output.writeLong(anchor == null ? 0 : anchor.z);
                }
                output.writeInt(account.links.size());
                for (var link : account.links) output.writeUTF(this.root.relativize(link).toString());
            }
        }
        Path pending = account.record.resolveSibling(account.record.getFileName() + ".pending");
        refreshRecovery(account.record);
        for (;;) {
            synchronized (this) { requireMutation(account.record); }
            var observation = probeSpace();
            synchronized (this) {
                if (observation.epoch != this.spaceEpoch) continue;
                requireMutation(account.record);
                if (!physicalSpace(bytes.size(), observation)) { pauseDisk(bytes.size(), observation); throw new Capacity(Admission.DISK_FULL); }
                beginMutation(); break;
            }
        }
        long before = size(account.record), oldPending = size(pending), charged = oldPending;
        boolean installed = false, touched = false;
        try {
            Files.createDirectories(account.record.getParent());
            LocalCacheOwnership.rejectLinks(pending);
            LocalCacheOwnership.rejectLinks(account.record);
            reserve(pending, Math.max(0, bytes.size() - oldPending)); charged = Math.max(bytes.size(), oldPending);
            touched = true;
            try (var file = java.nio.channels.FileChannel.open(pending, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                var buffer = java.nio.ByteBuffer.wrap(bytes.toByteArray());
                while (buffer.hasRemaining()) if (file.write(buffer) <= 0) throw new IOException("short cache ownership write");
                file.force(true);
            }
            replace(pending, account.record, bytes.size(), before, charged, () -> ready());
            synchronized (this) {
                account.dirty = account.revision != revision; account.claimsDirty = false; account.persistedAllowance = true;
            }
            installed = true;
        } catch (IOException failure) {
            writeFailure(pending, failure, bytes.size()); throw failure;
        } finally {
            try {
                if (!installed && touched) { try { LocalCacheOwnership.rejectLinks(pending); Files.deleteIfExists(pending); }
                    catch (IOException failure) { writeFailure(pending, failure, bytes.size()); throw failure; }
                    finally { resized(pending, size(pending) - charged); } }
            } finally { finishMutation(); }
        }
    }
    private boolean delete(Path path, Account account, Path destination, boolean reduction) throws IOException {
        if (!path.getFileName().toString().endsWith(".vxlocal")) return false;
        Region region;
        synchronized (this) {
            region = this.regions.computeIfAbsent(path, ignored -> new Region(++this.regionIncarnations));
            if (region.writers++ == 0 && region.pins == 0) eligibility(path);
        }
        // Never wait for another region while reserve owns the capacity-change lock.
        boolean locked = region.writer.tryLock();
        try {
            if (!locked) return false;
            refreshRecovery(account, false);
            for (;;) {
                var observation = probeSpace();
                synchronized (this) {
                    if (observation.epoch != this.spaceEpoch) continue;
                    if (safety(account) != Admission.READY || owner(path) != account || region.pins != 0
                            || region.draining || !ClientLodDebug.cacheDeletionAllowed(path)) return false;
                    Rank outgoing = rank(path), incoming = destination == null ? null : rank(destination);
                    if (!reduction && (outgoing == null || outgoing.visible || incoming == null || !incoming.better(outgoing))) return false;
                    if (!physicalSpace(1, observation)) { pauseDisk(1, observation); return false; }
                    region.draining = true; beginMutation(); break;
                }
            }
            try { remove(path, region); return true; }
            catch (IOException failure) { writeFailure(path, failure); throw failure; }
            finally {
                synchronized (this) { region.draining = false; this.notifyAll(); }
                finishMutation();
            }
        } finally {
            if (locked) region.writer.unlock();
            releaseWriter(path, region, false, false, false);
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
            region.incarnation = ++this.regionIncarnations;
            this.eviction++; eligibilityChanged(owner(path));
        }
    }
    CompletedSectionJournal journal(Path path, RegionalProtocol.Hash32 world, long region, boolean create) throws IOException {
        Region owner;
        synchronized (this) { owner = this.regions.get(path); }
        if (owner == null) throw new IllegalStateException("journal requires a pin or retained directory");
        synchronized (owner) {
            if (owner.journal != null && !owner.journal.closed()) {
                ClientLodDebug.cacheJournalReused();
                return owner.journal;
            }
            owner.journal = null;
            boolean present = Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && Files.size(path) != 0;
            if (!present && !create) return null;
            if (!present) {
                reserve(path, CompletedSectionJournal.HEADER_BYTES);
            }
            long before = size(path);
            try {
                if (!present) Files.createDirectories(path.getParent());
                if (present) owner.journal = CompletedSectionJournal.open(path, world, region, false);
                else commit(path, () -> owner.journal = CompletedSectionJournal.open(path, world, region, true));
                owner.journal.closeHandle();
                return owner.journal;
            } catch (IOException failure) {
                if (!present) {
                    writeFailure(path, failure);
                    try { Files.deleteIfExists(path); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
                }
                throw failure;
            } finally {
                if (!present) resized(path, size(path) - before - CompletedSectionJournal.HEADER_BYTES);
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
