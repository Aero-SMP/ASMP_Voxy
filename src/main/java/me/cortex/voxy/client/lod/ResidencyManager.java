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
    private static final long OBJECT_RECORD_BYTES = 128;
    private static final int MAX_PINNED_ROOTS = 3;
    private static final long PIN_ENTRY_BYTES = 96;

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

    /** Result of an atomic root-pin update. Missing residency is recoverable demand. */
    public enum PinResult {
        MISSING,
        UNCHANGED,
        CHANGED
    }

    private final MemoryBudget memory;
    private final Limits limits;
    private final Map<Hash256, ResidentObject> objects = new LinkedHashMap<>(16, 0.75f, true);
    private final Map<Hash256, ManifestRecord> manifests = new LinkedHashMap<>(16, 0.75f, true);
    private final Map<RootToken, Set<Hash256>> rootPins = new HashMap<>();
    private final MemoryBudget.Reservation pinTableMemory;
    private boolean closed;

    public ResidencyManager(String dimension, MemoryBudget memory,
                              Limits limits) {
        Objects.requireNonNull(dimension, "dimension");
        if (dimension.isEmpty()) throw new IllegalArgumentException("dimension is empty");
        this.memory = Objects.requireNonNull(memory, "memory");
        this.limits = Objects.requireNonNull(limits, "limits");
        long pinBytes = Math.multiplyExact((long) limits.maxObjects(),
                Math.multiplyExact(PIN_ENTRY_BYTES, MAX_PINNED_ROOTS + 2L));
        this.pinTableMemory = memory.tryReserve(MemoryBudget.Allocation.of(
                MemoryBudget.Pool.OBJECT_TABLE, pinBytes)).orElseThrow(() ->
                new IllegalStateException(
                        "Virtual Surface memory budget cannot admit bounded root-pin tables"));
    }

    /** Atomically admits the compressed envelope and verified canonical representation. */
    public synchronized boolean admitVerifiedObject(EncodedObject encoded,
                                                     CanonicalObject canonical) {
        ensureOpen();
        Objects.requireNonNull(encoded, "encoded");
        Objects.requireNonNull(canonical, "canonical");
        if (!encoded.hash().equals(canonical.hash()) || encoded.kind() != canonical.kind()
                || encoded.canonicalLength() != canonical.canonicalLength()) {
            throw new IllegalArgumentException("encoded and canonical object metadata disagree");
        }
        ResidentObject existing = this.objects.get(encoded.hash());
        if (existing != null) {
            if (existing.canonical != null && !existing.canonical.equals(canonical)) {
                throw new IllegalStateException("conflicting canonical content for one hash");
            }
            // Compression is deliberately outside canonical object identity. A later root may
            // encode the same authenticated object with a newly trained dictionary or different
            // Zstd settings while old renderer work still pins the resident canonical value.
            // Keep the first bounded envelope allocation; the equal canonical hash is authority.
            return true;
        }
        if (!makeObjectRoom()) return false;
        MemoryBudget.Allocation allocation = new MemoryBudget.Allocation(
                0, OBJECT_RECORD_BYTES, encoded.compressedLength(), canonical.canonicalLength(),
                0, 0, 0, 0);
        Optional<MemoryBudget.Reservation> reservation = this.memory.tryReserve(allocation);
        if (reservation.isEmpty()) {
            reclaimUnreferencedLocked();
            reservation = this.memory.tryReserve(allocation);
            if (reservation.isEmpty()) return false;
        }
        this.objects.put(encoded.hash(), new ResidentObject(encoded, canonical,
                reservation.orElseThrow()));
        return true;
    }

    /** Parses and retains one authenticated root-directory or manifest-subtree object. */
    public synchronized boolean installManifestObject(CanonicalObject canonical)
            throws ManifestCodec.DecodeException {
        ensureOpen();
        Objects.requireNonNull(canonical, "canonical");
        if (this.manifests.containsKey(canonical.hash())) return false;
        ResidentObject resident = requireCanonical(canonical);
        if (canonical.kind() != ObjectKind.ROOT_DIRECTORY
                && canonical.kind() != ObjectKind.MANIFEST_SUBTREE
                && canonical.kind() != ObjectKind.MANIFEST_DESCRIPTOR_PAGE) {
            throw new IllegalArgumentException("object is not production manifest metadata");
        }
        if (this.manifests.size() >= this.limits.maxManifestObjects()) {
            reclaimUnreferencedLocked(Set.of(canonical.hash()));
            if (this.manifests.size() >= this.limits.maxManifestObjects()) return false;
        }
        Object parsed = switch (canonical.kind()) {
            case ROOT_DIRECTORY -> ManifestCodec.decodeRootDirectory(canonical.bytesInternal());
            case MANIFEST_SUBTREE -> ManifestCodec.decodeManifestSubtree(canonical.bytesInternal());
            case MANIFEST_DESCRIPTOR_PAGE ->
                    ManifestCodec.decodeDescriptorPage(canonical.bytesInternal());
            default -> throw new IllegalArgumentException("object is not manifest metadata");
        };
        long retainedBytes = manifestRetainedBytes(canonical.canonicalLength(), parsed);
        Optional<MemoryBudget.Reservation> retained = this.memory.tryReserve(
                MemoryBudget.Allocation.of(MemoryBudget.Pool.MANIFEST, retainedBytes));
        if (retained.isEmpty()) {
            reclaimUnreferencedLocked(Set.of(canonical.hash()));
            retained = this.memory.tryReserve(
                    MemoryBudget.Allocation.of(MemoryBudget.Pool.MANIFEST, retainedBytes));
            if (retained.isEmpty()) return false;
        }
        this.manifests.put(canonical.hash(), new ManifestRecord(parsed, retained.orElseThrow()));
        resident.discardCanonical();
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

    /** Translates and retains one fixed-8-cubed object under the decoded-content budget. */
    public synchronized boolean installMicrotile(CanonicalObject canonical,
                                                 long expectedCatalogId,
                                                 int[] blockTranslations,
                                                 int[] biomeTranslations)
            throws MicrotileCodec.DecodeException {
        ensureOpen();
        Objects.requireNonNull(canonical, "canonical");
        ResidentObject resident = requireCanonical(canonical);
        if (!isMicrotile(canonical.kind())) {
            throw new IllegalArgumentException("object is not a production microtile");
        }
        if (resident.preparedMicrotile != null) return false;
        long retainedBytes = Math.addExact(OBJECT_RECORD_BYTES,
                (long) MicrotileCodec.CELL_COUNT * Long.BYTES);
        Optional<MemoryBudget.Reservation> preparedMemory = this.memory.tryReserve(
                MemoryBudget.Allocation.of(MemoryBudget.Pool.DECODED, retainedBytes));
        if (preparedMemory.isEmpty()) {
            reclaimUnreferencedLocked(Set.of(canonical.hash()));
            preparedMemory = this.memory.tryReserve(
                    MemoryBudget.Allocation.of(MemoryBudget.Pool.DECODED, retainedBytes));
            if (preparedMemory.isEmpty()) return false;
        }
        MicrotileCodec.Prepared prepared;
        try {
            prepared = MicrotileCodec.decode(canonical.bytesInternal(), canonical.kind(),
                    expectedCatalogId, blockTranslations, biomeTranslations, true);
        } catch (MicrotileCodec.DecodeException | RuntimeException failure) {
            preparedMemory.orElseThrow().close();
            throw failure;
        }
        resident.preparedMicrotile = prepared;
        resident.preparedMemory = preparedMemory.orElseThrow();
        resident.discardCanonical();
        return true;
    }

    public synchronized Optional<MicrotileCodec.Prepared> decodedMicrotile(Hash256 hash) {
        ensureOpen();
        ResidentObject object = this.objects.get(Objects.requireNonNull(hash, "hash"));
        return object == null ? Optional.empty() : Optional.ofNullable(object.preparedMicrotile);
    }

    /** Returns canonical data retained for catalog, dictionary, or not-yet-installed content. */
    public synchronized Optional<CanonicalObject> verifiedCanonical(Hash256 hash) {
        ensureOpen();
        ResidentObject object = this.objects.get(Objects.requireNonNull(hash, "hash"));
        return object == null ? Optional.empty() : Optional.ofNullable(object.canonical);
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

    /** Visits retained-root hashes without materializing another budget-sized set. */
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
        boolean decoded = object.canonical != null || object.preparedMicrotile != null || parsed;
        return Optional.of(new ObjectStatus(true, decoded, false,
                object.preparedMicrotile != null));
    }

    public synchronized boolean contains(Hash256 hash) {
        ensureOpen();
        return this.objects.containsKey(Objects.requireNonNull(hash, "hash"));
    }

    @Override
    public synchronized void close() {
        if (this.closed) return;
        this.closed = true;
        for (ManifestRecord manifest : this.manifests.values()) manifest.close();
        for (ResidentObject object : this.objects.values()) object.close();
        this.manifests.clear();
        this.objects.clear();
        this.rootPins.clear();
        this.pinTableMemory.close();
    }

    private ResidentObject requireCanonical(CanonicalObject canonical) {
        ResidentObject object = this.objects.get(canonical.hash());
        if (object == null || object.canonical == null || !object.canonical.equals(canonical)) {
            throw new IllegalArgumentException(
                    "object was not admitted as verified canonical data");
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
            ManifestRecord manifest = this.manifests.remove(entry.getKey());
            if (manifest != null) manifest.close();
            entry.getValue().close();
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

    private static long manifestRetainedBytes(int canonicalLength, Object parsed) {
        long supplemental;
        if (parsed instanceof ManifestSubtree subtree) {
            supplemental = Math.addExact(
                    Math.multiplyExact((long) subtree.structuralSlots(), Integer.BYTES),
                    Math.addExact(
                            Math.multiplyExact((long) subtree.descriptorPageSlots(), Integer.BYTES),
                            Math.multiplyExact((long) subtree.availableNodeCount(), 64L)));
        } else if (parsed instanceof DescriptorPage page) {
            supplemental = Math.multiplyExact((long) page.slotCount(), 192L);
        } else {
            supplemental = Math.multiplyExact((long) ((RootDirectory) parsed).entries().size(),
                    160L);
        }
        return Math.addExact(Math.multiplyExact((long) canonicalLength, 2L), supplemental);
    }

    private void ensureOpen() {
        if (this.closed) throw new IllegalStateException("residency is closed");
    }

    private static final class ResidentObject implements AutoCloseable {
        private final EncodedObject encoded;
        private final MemoryBudget.Reservation memory;
        private CanonicalObject canonical;
        private MicrotileCodec.Prepared preparedMicrotile;
        private MemoryBudget.Reservation preparedMemory;

        private ResidentObject(EncodedObject encoded, CanonicalObject canonical,
                               MemoryBudget.Reservation memory) {
            this.encoded = encoded;
            this.canonical = canonical;
            this.memory = memory;
        }

        private void discardCanonical() {
            if (this.canonical == null) return;
            this.memory.reduceTo(new MemoryBudget.Allocation(
                    0, OBJECT_RECORD_BYTES, this.encoded.compressedLength(), 0,
                    0, 0, 0, 0));
            this.canonical = null;
        }

        @Override
        public void close() {
            if (this.preparedMemory != null) this.preparedMemory.close();
            this.memory.close();
        }
    }

    private record ManifestRecord(Object value, MemoryBudget.Reservation memory)
            implements AutoCloseable {
        private ManifestRecord {
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(memory, "memory");
        }

        @Override
        public void close() {
            this.memory.close();
        }
    }
}
