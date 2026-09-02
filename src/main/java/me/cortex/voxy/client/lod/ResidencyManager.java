package me.cortex.voxy.client.lod;

import me.cortex.voxy.client.lod.ManifestCodec.ManifestSubtree;
import me.cortex.voxy.client.lod.ManifestCodec.DescriptorPage;
import me.cortex.voxy.client.lod.ManifestCodec.RootDirectory;
import me.cortex.voxy.client.lod.WireMessage.EncodedObject;
import me.cortex.voxy.client.lod.WireMessage.Hash256;
import me.cortex.voxy.client.lod.WireMessage.ObjectKind;
import me.cortex.voxy.client.lod.WireMessage.RootToken;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/** Bounded residency for authenticated objects and their parsed production forms. */
public final class ResidencyManager implements AutoCloseable {
    private static final int MAX_PINNED_ROOTS = 3;

    public record Limits(int maxObjects, int maxManifestObjects) {
        public Limits {
            if (maxObjects < 1 || maxManifestObjects < 1 || maxManifestObjects > maxObjects) {
                throw new IllegalArgumentException("invalid residency table limits");
            }
        }
    }

    /** Meshing and geometry are intentionally owned by MicrotileActivationManager. */
    public record ObjectStatus(boolean compressed, boolean decoded, boolean meshing,
                               boolean renderable) {}

    /** Compact owner-thread snapshot used only when a debug client requests a report. */
    public record Diagnostics(int objects, int decodedObjects, int preparedMicrotiles,
                              int manifestObjects, int pinnedRoots, int pinnedObjects,
                              int objectLimit, int manifestLimit) {}

    /** Result of an atomic root-pin update. Missing residency is recoverable demand. */
    public enum PinResult {
        MISSING,
        UNCHANGED,
        CHANGED
    }

    private final Limits limits;
    private final Map<Hash256, ResidentObject> objects = new LinkedHashMap<>(16, 0.75f, true);
    private final Map<Hash256, ManifestRecord> manifests = new LinkedHashMap<>(16, 0.75f, true);
    private final Map<RootToken, Set<Hash256>> rootPins = new HashMap<>();
    private boolean closed;

    public ResidencyManager(String dimension, Limits limits) {
        Objects.requireNonNull(dimension, "dimension");
        if (dimension.isEmpty()) throw new IllegalArgumentException("dimension is empty");
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    /** Atomically admits the compressed envelope and verified canonical representation. */
    public synchronized boolean admitVerifiedObject(EncodedObject encoded,
                                                     DecodedObject decoded) {
        ensureOpen();
        Objects.requireNonNull(encoded, "encoded");
        Objects.requireNonNull(decoded, "decoded");
        ResidentObject existing = this.objects.get(encoded.hash());
        if (existing != null) {
            if (existing.decoded != null && !existing.decoded.equals(decoded)) {
                throw new IllegalStateException("conflicting decoded content for one hash");
            }
            // Compression is deliberately outside canonical object identity. A later root may
            // encode the same authenticated object with a newly trained dictionary or different
            // Zstd settings while old renderer work still pins the resident canonical value.
            // Keep the first bounded envelope allocation; the equal canonical hash is authority.
            return true;
        }
        if (!makeObjectRoom()) return false;
        this.objects.put(encoded.hash(), new ResidentObject(decoded));
        return true;
    }

    /** Retains the decoded form of one authenticated manifest object. */
    public synchronized boolean installManifestObject(DecodedObject decoded) {
        ensureOpen();
        Objects.requireNonNull(decoded, "decoded");
        if (this.manifests.containsKey(decoded.hash())) return false;
        ResidentObject resident = requireDecoded(decoded);
        if (this.manifests.size() >= this.limits.maxManifestObjects()) {
            reclaimUnreferencedLocked(Set.of(decoded.hash()));
            if (this.manifests.size() >= this.limits.maxManifestObjects()) return false;
        }
        Object parsed = switch (decoded.kind()) {
            case ROOT_DIRECTORY -> decoded.rootDirectory();
            case MANIFEST_SUBTREE -> decoded.manifestSubtree();
            case MANIFEST_DESCRIPTOR_PAGE -> decoded.descriptorPage();
            default -> throw new IllegalArgumentException("object is not manifest metadata");
        };
        this.manifests.put(decoded.hash(), new ManifestRecord(parsed));
        resident.discardDecoded();
        return true;
    }

    public synchronized Optional<RootDirectory> rootDirectory(Hash256 hash) {
        ensureOpen();
        ManifestRecord record = this.manifests.get(Objects.requireNonNull(hash, "hash"));
        return record != null && record.value instanceof RootDirectory directory
                ? Optional.of(directory) : Optional.empty();
    }

    public synchronized Optional<ManifestSubtree> manifestSubtree(Hash256 hash) {
        ensureOpen();
        ManifestRecord record = this.manifests.get(Objects.requireNonNull(hash, "hash"));
        return record != null && record.value instanceof ManifestSubtree subtree
                ? Optional.of(subtree) : Optional.empty();
    }

    public synchronized Optional<DescriptorPage> descriptorPage(Hash256 hash) {
        ensureOpen();
        ManifestRecord record = this.manifests.get(Objects.requireNonNull(hash, "hash"));
        return record != null && record.value instanceof DescriptorPage page
                ? Optional.of(page) : Optional.empty();
    }

    /** Translates and retains one fixed-8-cubed object. */
    public synchronized boolean installMicrotile(DecodedObject decoded,
                                                 long expectedCatalogId,
                                                 int[] blockTranslations,
                                                 int[] biomeTranslations)
            throws MicrotileCodec.DecodeException {
        ensureOpen();
        Objects.requireNonNull(decoded, "decoded");
        ResidentObject resident = requireDecoded(decoded);
        if (!isMicrotile(decoded.kind())) {
            throw new IllegalArgumentException("object is not a production microtile");
        }
        if (resident.preparedMicrotile != null) return false;
        MicrotileCodec.Prepared prepared = MicrotileCodec.prepare(decoded.microtile(),
                expectedCatalogId, blockTranslations, biomeTranslations, true);
        resident.preparedMicrotile = prepared;
        resident.discardDecoded();
        return true;
    }

    public synchronized Optional<MicrotileCodec.Prepared> decodedMicrotile(Hash256 hash) {
        ensureOpen();
        ResidentObject object = this.objects.get(Objects.requireNonNull(hash, "hash"));
        return object == null ? Optional.empty() : Optional.ofNullable(object.preparedMicrotile);
    }

    /** Allocation-free prepared-content probe for hot selector snapshot publication. */
    public synchronized boolean hasPreparedMicrotile(Hash256 hash) {
        ensureOpen();
        ResidentObject object = this.objects.get(Objects.requireNonNull(hash, "hash"));
        return object != null && object.preparedMicrotile != null;
    }

    /** Returns an authenticated object whose decoded payload has not yet been installed. */
    public synchronized Optional<DecodedObject> decodedObject(Hash256 hash) {
        ensureOpen();
        ResidentObject object = this.objects.get(Objects.requireNonNull(hash, "hash"));
        return object == null ? Optional.empty() : Optional.ofNullable(object.decoded);
    }

    /** Pins one reachable object to an immutable root until that root is released. */
    public synchronized PinResult pinRootObject(RootToken root, Hash256 hash) {
        ensureOpen();
        Objects.requireNonNull(root, "root");
        Hash256 value = Objects.requireNonNull(hash, "hash");
        if (!this.objects.containsKey(value)) return PinResult.MISSING;
        return pinsFor(root).add(value) ? PinResult.CHANGED : PinResult.UNCHANGED;
    }

    public synchronized PinResult pinRootObjects(RootToken root, Collection<Hash256> hashes) {
        ensureOpen();
        Objects.requireNonNull(hashes, "hashes");
        for (Hash256 hash : hashes) {
            Hash256 value = Objects.requireNonNull(hash, "root pin hash");
            if (!this.objects.containsKey(value)) return PinResult.MISSING;
        }
        return pinsFor(Objects.requireNonNull(root, "root")).addAll(hashes)
                ? PinResult.CHANGED : PinResult.UNCHANGED;
    }

    public synchronized boolean releaseRootPins(RootToken root) {
        ensureOpen();
        Set<Hash256> removed = this.rootPins.remove(Objects.requireNonNull(root, "root"));
        return removed != null && !removed.isEmpty();
    }

    /** Atomically replaces one root's exact residency ownership set. */
    public synchronized PinResult reconcileRootPins(RootToken root,
                                                     Collection<Hash256> hashes) {
        ensureOpen();
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(hashes, "hashes");
        LinkedHashSet<Hash256> desired = new LinkedHashSet<>();
        for (Hash256 hash : hashes) {
            Hash256 value = Objects.requireNonNull(hash, "root pin hash");
            if (!this.objects.containsKey(value)) return PinResult.MISSING;
            desired.add(value);
        }
        Set<Hash256> existing = this.rootPins.get(root);
        if (desired.equals(existing == null ? Set.of() : existing)) {
            return PinResult.UNCHANGED;
        }
        if (desired.isEmpty()) this.rootPins.remove(root);
        else {
            if (!this.rootPins.containsKey(root)
                    && this.rootPins.size() >= MAX_PINNED_ROOTS) {
                throw new IllegalStateException("more than three immutable roots retained");
            }
            this.rootPins.put(root, desired);
        }
        return PinResult.CHANGED;
    }

    /** Visits retained-root hashes without materializing another set. */
    public synchronized void forEachProtectedHash(Consumer<Hash256> visitor) {
        ensureOpen();
        Objects.requireNonNull(visitor, "visitor");
        for (Set<Hash256> pins : this.rootPins.values()) pins.forEach(visitor);
    }

    public synchronized int reclaimUnreferenced() {
        ensureOpen();
        return reclaimUnreferencedLocked();
    }

    public synchronized Optional<ObjectStatus> objectStatus(Hash256 hash) {
        ensureOpen();
        ResidentObject object = this.objects.get(Objects.requireNonNull(hash, "hash"));
        if (object == null) return Optional.empty();
        boolean parsed = this.manifests.containsKey(hash);
        boolean decoded = object.decoded != null || object.preparedMicrotile != null || parsed;
        return Optional.of(new ObjectStatus(true, decoded, false,
                object.preparedMicrotile != null));
    }

    public synchronized boolean contains(Hash256 hash) {
        ensureOpen();
        return this.objects.containsKey(Objects.requireNonNull(hash, "hash"));
    }

    public synchronized Diagnostics diagnostics() {
        ensureOpen();
        int decoded = 0;
        int prepared = 0;
        for (ResidentObject object : this.objects.values()) {
            if (object.decoded != null) decoded++;
            if (object.preparedMicrotile != null) prepared++;
        }
        int pins = 0;
        for (Set<Hash256> root : this.rootPins.values()) pins += root.size();
        return new Diagnostics(this.objects.size(), decoded, prepared, this.manifests.size(),
                this.rootPins.size(), pins, this.limits.maxObjects(),
                this.limits.maxManifestObjects());
    }

    @Override
    public synchronized void close() {
        if (this.closed) return;
        this.closed = true;
        this.manifests.clear();
        this.objects.clear();
        this.rootPins.clear();
    }

    private ResidentObject requireDecoded(DecodedObject decoded) {
        ResidentObject object = this.objects.get(decoded.hash());
        if (object == null || object.decoded == null || !object.decoded.equals(decoded)) {
            throw new IllegalArgumentException(
                    "object was not admitted as authenticated decoded data");
        }
        return object;
    }

    private boolean makeObjectRoom() {
        if (this.objects.size() < this.limits.maxObjects()) return true;
        reclaimUnreferencedLocked();
        return this.objects.size() < this.limits.maxObjects();
    }

    private int reclaimUnreferencedLocked() {
        return reclaimUnreferencedLocked(Set.of());
    }

    private int reclaimUnreferencedLocked(Collection<Hash256> additionallyProtected) {
        int reclaimed = 0;
        var iterator = this.objects.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Hash256, ResidentObject> entry = iterator.next();
            if (isProtected(entry.getKey(), additionallyProtected)) continue;
            this.manifests.remove(entry.getKey());
            iterator.remove();
            reclaimed++;
        }
        return reclaimed;
    }

    private Set<Hash256> pinsFor(RootToken root) {
        Set<Hash256> existing = this.rootPins.get(root);
        if (existing != null) return existing;
        if (this.rootPins.size() >= MAX_PINNED_ROOTS) {
            throw new IllegalStateException("more than three immutable roots retained");
        }
        Set<Hash256> created = new LinkedHashSet<>();
        this.rootPins.put(root, created);
        return created;
    }

    private boolean isProtected(Hash256 hash, Collection<Hash256> additional) {
        if (additional.contains(hash)) return true;
        for (Set<Hash256> pins : this.rootPins.values()) {
            if (pins.contains(hash)) return true;
        }
        return false;
    }

    private static boolean isMicrotile(ObjectKind kind) {
        return kind == ObjectKind.EXTERIOR_MICROTILE
                || kind == ObjectKind.INTERIOR_MICROTILE
                || kind == ObjectKind.COMPLEX_MICROTILE;
    }

    private void ensureOpen() {
        if (this.closed) throw new IllegalStateException("residency is closed");
    }

    private static final class ResidentObject {
        private DecodedObject decoded;
        private MicrotileCodec.Prepared preparedMicrotile;

        private ResidentObject(DecodedObject decoded) {
            this.decoded = decoded;
        }

        private void discardDecoded() {
            this.decoded = null;
        }

    }

    private record ManifestRecord(Object value) {
        private ManifestRecord {
            Objects.requireNonNull(value, "value");
        }
    }
}
