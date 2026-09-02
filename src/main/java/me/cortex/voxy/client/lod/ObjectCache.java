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
    private boolean enabled;

    public ObjectCache(Path root, Limits limits) throws IOException {
        Objects.requireNonNull(limits, "limits");
        this.store = new PackedObjectStore(Objects.requireNonNull(root, "root"),
                limits.maxObjects(), limits.maxStoredBytes(), limits.maxObjectBytes());
        this.enabled = true;
    }

    private ObjectCache() {
        this.store = null;
    }

    /** A disposable miss-only cache used while disk recovery is still running. */
    static ObjectCache disabled() {
        return new ObjectCache();
    }

    /** Opens the disposable cache, falling back to a no-op cache on any startup I/O failure. */
    public static ObjectCache openBestEffort(Path root, Limits limits) {
        try {
            return new ObjectCache(root, limits);
        } catch (IOException failure) {
            return new ObjectCache();
        }
    }

    synchronized boolean putVerified(EncodedObject encoded) throws IOException {
        if (!this.enabled) return false;
        try {
            return this.store.put(encoded);
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
        }
    }
}
