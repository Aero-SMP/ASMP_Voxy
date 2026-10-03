package com.aerosmp.voxy.terrain;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/** This path has no transport object or server-readiness condition. */
public final class CacheLoader {
    private final TerrainStore store;
    private SectionCodec.Catalog catalog;
    public record Loaded(byte[] hash, TerrainData data) {}
    public CacheLoader(TerrainStore store) { this.store = store; }
    public Loaded load(SectionKey key, byte[] previous) throws IOException {
        byte[] frame = store.frame(key);
        if (frame == null) return null;
        byte[] hash = SectionCodec.hash(frame);
        if (previous != null && MessageDigest.isEqual(hash, previous)) return null;
        byte[] catalogHash = SectionCodec.catalogHash(frame);
        if (catalog == null || !MessageDigest.isEqual(catalogHash, catalog.hash())) catalog = store.catalog(catalogHash);
        return new Loaded(hash, SectionCodec.decode(key, frame, catalog));
    }
    public void clear() { catalog = null; }
}
