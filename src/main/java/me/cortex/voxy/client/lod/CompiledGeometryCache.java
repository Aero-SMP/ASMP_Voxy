package me.cortex.voxy.client.lod;

import me.cortex.voxy.client.core.rendering.building.BuiltSection;
import me.cortex.voxy.client.lod.ContentPipeline.RendererIdentity;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.client.lod.WireMessage.Hash256;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Bounded LRU for compiled geometry keyed by terrain, resource/model, and renderer identities. */
public final class CompiledGeometryCache implements AutoCloseable {
    private static final byte[] KEY_DOMAIN =
            "Voxy compiled geometry cache key\0".getBytes(StandardCharsets.UTF_8);

    public record Key(Hash256 terrainIdentity, Hash256 resourceModelFingerprint,
                      Hash256 identity) {
        public Key {
            Objects.requireNonNull(terrainIdentity, "terrainIdentity");
            Objects.requireNonNull(resourceModelFingerprint, "resourceModelFingerprint");
            Objects.requireNonNull(identity, "identity");
            Hash256 expected = computeIdentity(terrainIdentity, resourceModelFingerprint);
            if (!expected.equals(identity)) {
                throw new IllegalArgumentException("compiled geometry cache-key identity mismatch");
            }
        }

        public static Key create(Hash256 terrainIdentity, RendererIdentity rendererIdentity) {
            Objects.requireNonNull(rendererIdentity, "rendererIdentity");
            Hash256 fingerprint = rendererIdentity.resourceModelFingerprint();
            return new Key(terrainIdentity, fingerprint,
                    computeIdentity(terrainIdentity, fingerprint));
        }
    }

    private final int maxEntries;
    private final Map<Key, Entry> entries = new LinkedHashMap<>(16, 0.75f, true);
    private boolean closed;

    public CompiledGeometryCache(int maxEntries) {
        if (maxEntries < 1) throw new IllegalArgumentException("maxEntries must be positive");
        this.maxEntries = maxEntries;
    }

    /** Returns a caller-owned geometry copy with the requested spatial/revision metadata. */
    public synchronized Optional<BuiltSection> lookup(Key key, long sectionPosition,
                                                      long sourceRevision,
                                                      byte childExistence) {
        ensureOpen();
        Entry entry = this.entries.get(Objects.requireNonNull(key, "key"));
        if (entry == null) return Optional.empty();
        MemoryBuffer geometry = entry.geometry == null ? null : entry.geometry.copy();
        return Optional.of(new BuiltSection(sectionPosition, sourceRevision, childExistence,
                entry.aabb, geometry, entry.offsets == null ? null : entry.offsets.clone()));
    }

    /** Copies a completed renderer result into the bounded cache without taking its ownership. */
    public synchronized boolean put(Key key, BuiltSection section) {
        ensureOpen();
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(section, "section");
        if (this.entries.containsKey(key)) return false;
        MemoryBuffer geometry = null;
        try {
            geometry = section.geometryBuffer == null ? null : section.geometryBuffer.copy();
            Entry entry = new Entry(section.aabb, geometry,
                    section.offsets == null ? null : section.offsets.clone());
            geometry = null;
            this.entries.put(key, entry);
            while (this.entries.size() > this.maxEntries) evictEldest();
            return true;
        } catch (RuntimeException | Error failure) {
            if (geometry != null) geometry.free();
            throw failure;
        }
    }

    @Override
    public synchronized void close() {
        if (this.closed) return;
        this.closed = true;
        for (Entry entry : this.entries.values()) entry.close();
        this.entries.clear();
    }

    private void evictEldest() {
        var iterator = this.entries.entrySet().iterator();
        if (!iterator.hasNext()) return;
        Entry entry = iterator.next().getValue();
        iterator.remove();
        entry.close();
    }

    private void ensureOpen() {
        if (this.closed) throw new IllegalStateException("compiled geometry cache is closed");
    }

    private static Hash256 computeIdentity(Hash256 terrainIdentity,
                                           Hash256 resourceModelFingerprint) {
        Objects.requireNonNull(terrainIdentity, "terrainIdentity");
        Objects.requireNonNull(resourceModelFingerprint, "resourceModelFingerprint");
        Blake3.Hasher hasher = new Blake3.Hasher().update(KEY_DOMAIN)
                .update(terrainIdentity.toBytes())
                .update(resourceModelFingerprint.toBytes());
        return Hash256.fromBytes(hasher.digest());
    }

    private static final class Entry implements AutoCloseable {
        private final int aabb;
        private final MemoryBuffer geometry;
        private final int[] offsets;

        private Entry(int aabb, MemoryBuffer geometry, int[] offsets) {
            this.aabb = aabb;
            this.geometry = geometry;
            this.offsets = offsets;
        }

        @Override
        public void close() {
            if (this.geometry != null) this.geometry.free();
        }
    }
}
