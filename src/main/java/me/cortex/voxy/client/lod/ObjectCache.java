package me.cortex.voxy.client.lod;

import me.cortex.voxy.client.lod.WireMessage.EncodedObject;
import me.cortex.voxy.client.lod.WireMessage.Hash256;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Bounded client cache for independently addressed immutable objects.
 *
 * <p>Objects are packed into segmented append-only logs. Their canonical hashes remain independent
 * of pack IDs, offsets, compression settings, and the disposable checksummed index. Recovery scans
 * only bytes beyond a valid checkpoint; index loss performs one bounded full scan. Corrupt payloads
 * remove only their own record, while tombstones and RESET epochs prevent deleted objects from
 * reappearing during reconstruction.</p>
 */
public final class ObjectCache implements AutoCloseable {
    @FunctionalInterface
    public interface PinSource {
        void forEach(Consumer<Hash256> visitor);
    }
    // Includes live and recovery/checkpoint map nodes, hash objects, pin-set entries, pack
    // metadata, and one maximum synchronized cache read buffer. The cache reserves its complete
    // worst case once instead of letting disposable metadata escape the session-wide budget.
    private static final long INDEX_BYTES_PER_OBJECT = 656;
    private static final long PACK_TABLE_BYTES = 8L << 20;
    public record Limits(int maxObjects, long maxStoredBytes, int maxObjectBytes) {
        public Limits {
            if (maxObjects < 1 || maxStoredBytes < 0 || maxObjectBytes < 0
                    || maxObjectBytes > WireMessage.MAX_COMPRESSED_OBJECT_BYTES
                    || maxObjectBytes > maxStoredBytes) {
                throw new IllegalArgumentException("invalid object-cache limits");
            }
        }
    }

    private final PackedObjectStore store;
    private final MemoryBudget.Reservation memory;
    private boolean enabled;

    public ObjectCache(Path root, Limits limits, MemoryBudget budget) throws IOException {
        Objects.requireNonNull(limits, "limits");
        Objects.requireNonNull(budget, "budget");
        long indexBytes = Math.addExact(PACK_TABLE_BYTES,
                Math.multiplyExact(INDEX_BYTES_PER_OBJECT, limits.maxObjects()));
        this.memory = budget.tryReserve(new MemoryBudget.Allocation(
                0, indexBytes, 0, 0, 0, 0, 0, limits.maxObjectBytes()))
                .orElseThrow(() -> new IOException(
                        "Virtual Surface memory budget cannot admit the persistent-cache index"));
        PackedObjectStore opened;
        try {
            opened = new PackedObjectStore(Objects.requireNonNull(root, "root"),
                    limits.maxObjects(), limits.maxStoredBytes(), limits.maxObjectBytes());
        } catch (IOException | RuntimeException failure) {
            this.memory.close();
            throw failure;
        }
        this.store = opened;
        this.enabled = true;
    }

    private ObjectCache() {
        this.store = null;
        this.memory = null;
    }

    /** Opens the disposable cache, falling back to a no-op cache on any startup I/O failure. */
    public static ObjectCache openBestEffort(Path root, Limits limits,
                                                MemoryBudget budget) {
        try {
            return new ObjectCache(root, limits, budget);
        } catch (IOException failure) {
            return new ObjectCache();
        }
    }

    public synchronized boolean put(EncodedObject encoded, CanonicalObject canonical)
            throws IOException {
        if (!this.enabled) return false;
        try {
            return this.store.put(encoded, canonical);
        } catch (IOException failure) {
            disable();
            return false;
        }
    }

    public synchronized Optional<EncodedObject> getEncoded(Hash256 hash) throws IOException {
        if (!this.enabled) return Optional.empty();
        try {
            return this.store.getEncoded(hash);
        } catch (IOException failure) {
            disable();
            return Optional.empty();
        }
    }

    /** Removes one envelope whose decompression or typed identity failed after cache read. */
    public synchronized void quarantine(Hash256 hash) throws IOException {
        if (!this.enabled) return;
        try {
            this.store.quarantine(hash);
        } catch (IOException failure) {
            disable();
        }
    }

    /** Replaces the exact immutable-root reachable set protected from LRU eviction. */
    public synchronized void replacePins(PinSource source) {
        Objects.requireNonNull(source, "source");
        if (this.enabled) this.store.replacePins(source);
    }

    /** Permanently yields this disposable cache's working set without closing the live session. */
    public synchronized void disableForPressure() {
        disable();
    }

    @Override
    public synchronized void close() {
        disable();
    }

    private void disable() {
        if (!this.enabled) return;
        this.enabled = false;
        try {
            this.store.close();
        } catch (RuntimeException ignored) {
            // This cache is disposable. A failed checkpoint/close must not affect live terrain.
        } finally {
            this.memory.close();
        }
    }
}
