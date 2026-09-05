package me.cortex.voxy.client.lod;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.BooleanSupplier;

/** One existing 2 GiB allowance shared by payloads and startup metadata in every namespace.
 * Disk methods run on cache/metadata workers; this monitor also orders eviction and atomic writes. */
final class RegionalDiskBudget {
    static final long LIMIT = 2L * 1024 * 1024 * 1024;
    private static final Map<Path, WeakReference<RegionalDiskBudget>> OPEN = new HashMap<>();
    final Path root;
    final long limit;
    long bytes;
    volatile long eviction;
    private final Map<Path, Map<RegionalCache, BooleanSupplier>> openFiles = new HashMap<>();
    private final Map<Path, Path> references = new HashMap<>();
    private final Map<Path, Integer> catalogReferences = new HashMap<>();

    static synchronized RegionalDiskBudget open(Path root) throws IOException {
        root = root.toAbsolutePath().normalize();
        var reference = OPEN.get(root);
        var budget = reference == null ? null : reference.get();
        if (budget == null) {
            budget = new RegionalDiskBudget(root, LIMIT);
            OPEN.put(root, new WeakReference<>(budget));
        }
        return budget;
    }

    RegionalDiskBudget(Path root, long limit) throws IOException {
        this.root = Files.createDirectories(root);
        this.limit = limit;
        try (var files = Files.walk(root)) {
            for (Path path : files.filter(RegionalDiskBudget::managed).toList()) {
                this.bytes += size(path);
                if (path.toString().endsWith(".vxmeta")) {
                    Path catalog = RegionalMetadataStore.referencedCatalog(path);
                    if (catalog != null) this.reference(path, catalog);
                }
            }
        }
        // Unreferenced immutable catalogs and interrupted atomic replacements are safe misses.
        try (var files = Files.walk(root)) {
            for (Path path : files.filter(RegionalDiskBudget::managed).toList()) {
                if (path.toString().endsWith(".pending") || path.toString().endsWith(".vxcat")
                        && !this.catalogReferences.containsKey(path)) this.delete(path);
            }
        }
        this.ensure(0, Set.of());
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
        if (added < 0 || added > this.limit) return false;
        if (this.bytes + added <= this.limit) return true;
        // Only pressure scans the directory; section hits never scan it.
        try (var files = Files.walk(this.root)) {
            for (Path candidate : files.filter(RegionalDiskBudget::managed)
                    .filter(path -> !protectedPaths.contains(path))
                    .sorted(Comparator.comparingLong(RegionalDiskBudget::modified)).toList()) {
                if (candidate.toString().endsWith(".vxcat")
                        && this.catalogReferences.containsKey(candidate)) continue;
                this.delete(candidate);
                if (this.bytes + added <= this.limit) return true;
            }
        }
        return this.bytes + added <= this.limit;
    }

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
            this.delete(catalog);
        }
    }

    boolean delete(Path path) throws IOException {
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
    private static long modified(Path path) {
        try { return Files.getLastModifiedTime(path).toMillis(); }
        catch (IOException missing) { return Long.MIN_VALUE; }
    }
    private static boolean managed(Path path) {
        if (!Files.isRegularFile(path)) return false;
        String name = path.getFileName().toString();
        return name.endsWith(".vxcache") || name.endsWith(".vxmeta")
                || name.endsWith(".vxcat") || name.endsWith(".vxlink")
                || name.endsWith(".vxmeta.pending") || name.endsWith(".vxcat.pending")
                || name.endsWith(".vxlink.pending");
    }
}
