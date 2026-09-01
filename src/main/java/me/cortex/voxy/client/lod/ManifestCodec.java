package me.cortex.voxy.client.lod;

import me.cortex.voxy.client.lod.WireMessage.Hash256;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Bounded decoders for the canonical root directory, five-level structural manifests, and descriptor pages. */
public final class ManifestCodec {
    public static final int MAX_LOD = 4;
    public static final int MAX_SUBTREE_LEVELS = 5;
    public static final int STRUCTURAL_SLOTS = 4_681;
    public static final int DESCRIPTOR_PAGE_NODE_SLOTS = 64;
    public static final int DESCRIPTOR_PAGE_SLOTS = 74;
    public static final int MAX_MANIFEST_BYTES = 16 * 1024 * 1024;
    public static final int MAX_DIRECTORY_ENTRIES = 4_096;
    public static final int MAX_OBJECT_REFERENCES = 262_144;
    public static final int MAX_DEPENDENCIES_PER_CONTENT = 256;
    public static final int MAX_NEIGHBOR_DEPENDENCIES_PER_CONTENT = 6 * 64;
    public static final int MAX_VISIBILITY_MEMBERSHIPS_PER_CONTENT = 256;
    public static final int BOUNDARY_FACE_BYTES = 128;

    private static final byte[] DIRECTORY_MAGIC = "VXYDIR\0\0".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] MANIFEST_MAGIC = "VXYMNFT\0".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] DESCRIPTOR_PAGE_MAGIC =
            "VXYDESC\0".getBytes(StandardCharsets.US_ASCII);
    private static final int DIRECTORY_ENTRY_BYTES = 70;

    private ManifestCodec() {}

    public enum DirectoryTarget {
        MANIFEST_SUBTREE(1),
        ROOT_DIRECTORY(2);

        private final int wireId;

        DirectoryTarget(int wireId) {
            this.wireId = wireId;
        }

        public int wireId() {
            return this.wireId;
        }

        private static DirectoryTarget decode(int wireId) throws DecodeException {
            return switch (wireId) {
                case 1 -> MANIFEST_SUBTREE;
                case 2 -> ROOT_DIRECTORY;
                default -> throw new DecodeException("unknown root-directory target " + wireId);
            };
        }
    }

    public enum ContentClass {
        EXTERIOR,
        INTERIOR,
        COMPLEX
    }

    /** Canonical manifest face order; renderer-specific face IDs must be translated explicitly. */
    public enum NeighborFace {
        NEGATIVE_X(0),
        POSITIVE_X(1),
        NEGATIVE_Y(2),
        POSITIVE_Y(3),
        NEGATIVE_Z(4),
        POSITIVE_Z(5);

        private final int wireId;

        NeighborFace(int wireId) {
            this.wireId = wireId;
        }

        public int wireId() {
            return this.wireId;
        }

        private static NeighborFace decode(int wireId) throws DecodeException {
            if (wireId < 0 || wireId >= values().length) {
                throw new DecodeException("unknown neighbor face " + wireId);
            }
            return values()[wireId];
        }
    }

    public record NeighborDependency(int sourceMicrotileIndex, NeighborFace face, Hash256 hash) {
        public NeighborDependency {
            if (sourceMicrotileIndex < 0 || sourceMicrotileIndex >= Long.SIZE) {
                throw new IllegalArgumentException("neighbor dependency source is outside 8-cubed content");
            }
            Objects.requireNonNull(face, "face");
            Objects.requireNonNull(hash, "hash");
        }
    }

    public record VisibilityMembership(long domain, long microtileMask) {
        public VisibilityMembership {
            if (Long.compareUnsigned(domain, 2) < 0 || microtileMask == 0) {
                throw new IllegalArgumentException(
                        "visibility membership needs a non-reserved domain and nonempty mask");
            }
        }
    }

    public record SpatialNode(int lod, int x, int y, int z) {
        public SpatialNode {
            if (lod < 0 || lod > MAX_LOD) {
                throw new IllegalArgumentException("LOD must be between zero and " + MAX_LOD);
            }
        }
    }

    /** Inclusive bounds in the coordinate system of the five-level hierarchy's LOD-4 roots. */
    public record TopRootBounds(int minX, int minY, int minZ,
                                int maxX, int maxY, int maxZ) {
        public TopRootBounds {
            if (minX > maxX || minY > maxY || minZ > maxZ) {
                throw new IllegalArgumentException("inverted root-directory bounds");
            }
        }

        public boolean contains(SpatialNode node) {
            return node.lod() == MAX_LOD
                    && node.x() >= this.minX && node.x() <= this.maxX
                    && node.y() >= this.minY && node.y() <= this.maxY
                    && node.z() >= this.minZ && node.z() <= this.maxZ;
        }

        public boolean contains(TopRootBounds other) {
            Objects.requireNonNull(other, "other");
            return other.minX >= this.minX && other.maxX <= this.maxX
                    && other.minY >= this.minY && other.maxY <= this.maxY
                    && other.minZ >= this.minZ && other.maxZ <= this.maxZ;
        }

        public static TopRootBounds exact(SpatialNode node) {
            Objects.requireNonNull(node, "node");
            if (node.lod() != MAX_LOD) {
                throw new IllegalArgumentException("root-directory anchors must be LOD 4");
            }
            return new TopRootBounds(node.x(), node.y(), node.z(),
                    node.x(), node.y(), node.z());
        }
    }

    /** The anchor is only the first-descendant Morton routing key; relevance uses bounds. */
    public record RootDirectoryEntry(SpatialNode node, TopRootBounds bounds,
                                     DirectoryTarget target, Hash256 hash) {
        public RootDirectoryEntry {
            Objects.requireNonNull(node, "node");
            Objects.requireNonNull(bounds, "bounds");
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(hash, "hash");
            if (node.lod() != MAX_LOD || !bounds.contains(node)) {
                throw new IllegalArgumentException(
                        "root-directory anchor must be a bounded LOD-4 root");
            }
            if (target == DirectoryTarget.MANIFEST_SUBTREE
                    && !bounds.equals(TopRootBounds.exact(node))) {
                throw new IllegalArgumentException(
                        "manifest directory entry must have exact anchor bounds");
            }
        }
    }

    public record RootDirectory(List<RootDirectoryEntry> entries) {
        public RootDirectory {
            entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
            if (entries.size() > MAX_DIRECTORY_ENTRIES) {
                throw new IllegalArgumentException("root directory has an invalid entry count");
            }
            SpatialNode previous = null;
            for (RootDirectoryEntry entry : entries) {
                Objects.requireNonNull(entry, "entry");
                if (previous != null && compareSpatial(previous, entry.node()) >= 0) {
                    throw new IllegalArgumentException("root directory is not strictly spatially sorted");
                }
                previous = entry.node();
            }
        }
    }

    public record QuantizedBounds(int minX, int minY, int minZ,
                                  int maxX, int maxY, int maxZ) {
        public QuantizedBounds {
            if ((minX | minY | minZ | maxX | maxY | maxZ) < 0
                    || minX > 0xffff || minY > 0xffff || minZ > 0xffff
                    || maxX > 0xffff || maxY > 0xffff || maxZ > 0xffff
                    || minX > maxX || minY > maxY || minZ > maxZ) {
                throw new IllegalArgumentException("invalid quantized bounds");
            }
        }
    }

    /** Six canonical 32x32 occupancy bit planes in -X,+X,-Y,+Y,-Z,+Z order. */
    public static final class BoundarySummary {
        private final int faceMask;
        private final byte[] occupancy;

        public BoundarySummary(int faceMask, byte[] occupancy) {
            if ((faceMask & ~0x3f) != 0) {
                throw new IllegalArgumentException("boundary face mask is not six bits");
            }
            Objects.requireNonNull(occupancy, "occupancy");
            if (occupancy.length != Integer.bitCount(faceMask) * BOUNDARY_FACE_BYTES) {
                throw new IllegalArgumentException(
                        "boundary summary length disagrees with its face mask");
            }
            this.faceMask = faceMask;
            this.occupancy = occupancy.clone();
        }

        public int faceMask() {
            return this.faceMask;
        }

        @Override
        public boolean equals(Object other) {
            return this == other || other instanceof BoundarySummary summary
                    && this.faceMask == summary.faceMask
                    && java.util.Arrays.equals(this.occupancy, summary.occupancy);
        }

        @Override
        public int hashCode() {
            return 31 * this.faceMask + java.util.Arrays.hashCode(this.occupancy);
        }
    }

    public record ContentDescriptor(int microtileEdge, long microtileMask,
                                    List<Hash256> objects, List<Hash256> dependencies,
                                    List<NeighborDependency> neighborDependencies,
                                    BoundarySummary boundarySummary,
                                    long exteriorVisibilityMask,
                                    long unknownVisibilityMask,
                                    List<VisibilityMembership> visibilityMemberships) {
        public ContentDescriptor {
            objects = List.copyOf(Objects.requireNonNull(objects, "objects"));
            dependencies = List.copyOf(Objects.requireNonNull(dependencies, "dependencies"));
            neighborDependencies = List.copyOf(Objects.requireNonNull(
                    neighborDependencies, "neighborDependencies"));
            boundarySummary = Objects.requireNonNull(boundarySummary, "boundarySummary");
            visibilityMemberships = List.copyOf(Objects.requireNonNull(
                    visibilityMemberships, "visibilityMemberships"));
            if (microtileEdge != 8) {
                throw new IllegalArgumentException(
                        "production virtual-surface microtiles must have edge 8");
            }
            if (microtileMask == 0 || objects.size() != Long.bitCount(microtileMask)) {
                throw new IllegalArgumentException("microtile mask and object list disagree");
            }
            if (dependencies.size() > MAX_DEPENDENCIES_PER_CONTENT
                    || neighborDependencies.size() > MAX_NEIGHBOR_DEPENDENCIES_PER_CONTENT
                    || visibilityMemberships.size()
                    > MAX_VISIBILITY_MEMBERSHIPS_PER_CONTENT) {
                throw new IllegalArgumentException("too many content dependencies");
            }
            if (((exteriorVisibilityMask | unknownVisibilityMask) & ~microtileMask) != 0
                    || (exteriorVisibilityMask & unknownVisibilityMask) != 0) {
                throw new IllegalArgumentException("invalid exterior/unknown visibility masks");
            }
            HashSet<Hash256> unique = new HashSet<>();
            for (Hash256 hash : objects) {
                if (!unique.add(Objects.requireNonNull(hash, "object hash"))) {
                    throw new IllegalArgumentException("content repeats an object hash");
                }
            }
            Hash256 previous = null;
            for (Hash256 hash : dependencies) {
                if (!unique.add(Objects.requireNonNull(hash, "dependency hash"))) {
                    throw new IllegalArgumentException("content repeats a dependency hash");
                }
                if (previous != null && compareHash(previous, hash) >= 0) {
                    throw new IllegalArgumentException("content dependencies are not sorted");
                }
                previous = hash;
            }
            NeighborFace previousFace = null;
            int previousSource = -1;
            HashSet<Long> uniqueNeighbors = new HashSet<>();
            for (NeighborDependency dependency : neighborDependencies) {
                NeighborDependency value = Objects.requireNonNull(
                        dependency, "neighbor dependency");
                if ((microtileMask & 1L << value.sourceMicrotileIndex()) == 0) {
                    throw new IllegalArgumentException(
                            "neighbor dependency names an unavailable source microtile");
                }
                long key = (long) value.face().wireId() << 32
                        | Integer.toUnsignedLong(value.sourceMicrotileIndex());
                if (!uniqueNeighbors.add(key)) {
                    throw new IllegalArgumentException(
                            "content repeats a source-microtile face dependency");
                }
                if (previousFace != null
                        && value.face().wireId() < previousFace.wireId()) {
                    throw new IllegalArgumentException(
                            "content neighbor face groups are not canonical");
                }
                if (value.face() == previousFace
                        && value.sourceMicrotileIndex() <= previousSource) {
                    throw new IllegalArgumentException(
                            "content face neighbor dependencies are not source-Morton sorted");
                }
                if (value.face() != previousFace) previousSource = -1;
                previousFace = value.face();
                previousSource = value.sourceMicrotileIndex();
            }
            long previousDomain = 0;
            boolean firstDomain = true;
            for (VisibilityMembership membership : visibilityMemberships) {
                VisibilityMembership value = Objects.requireNonNull(
                        membership, "visibility membership");
                if ((value.microtileMask() & ~microtileMask) != 0) {
                    throw new IllegalArgumentException(
                            "visibility membership escapes content availability");
                }
                if (!firstDomain && Long.compareUnsigned(previousDomain, value.domain()) >= 0) {
                    throw new IllegalArgumentException(
                            "visibility memberships are not strictly domain sorted");
                }
                firstDomain = false;
                previousDomain = value.domain();
            }
        }

        /** Domain zero is conservative startup/unknown-camera visibility. */
        public long eligibleMask(long cameraDomain) {
            if (cameraDomain == 0) return this.microtileMask;
            long eligible = this.exteriorVisibilityMask | this.unknownVisibilityMask;
            for (VisibilityMembership membership : this.visibilityMemberships) {
                if (membership.domain() == cameraDomain) {
                    eligible |= membership.microtileMask();
                    break;
                }
            }
            return eligible;
        }

    }

    public record ManifestNode(int childMask, Optional<QuantizedBounds> bounds,
                               long geometricErrorQ16,
                               Map<ContentClass, ContentDescriptor> contents) {
        public ManifestNode {
            if ((childMask & ~0xff) != 0) {
                throw new IllegalArgumentException("child mask is not an unsigned byte");
            }
            bounds = Objects.requireNonNull(bounds, "bounds");
            if (geometricErrorQ16 < 0 || geometricErrorQ16 > 0xffff_ffffL) {
                throw new IllegalArgumentException("geometric error is not an unsigned Q16.16 value");
            }
            Objects.requireNonNull(contents, "contents");
            EnumMap<ContentClass, ContentDescriptor> copy = new EnumMap<>(ContentClass.class);
            contents.forEach((contentClass, descriptor) -> copy.put(
                    Objects.requireNonNull(contentClass, "content class"),
                    Objects.requireNonNull(descriptor, "content descriptor")));
            long exterior = copy.containsKey(ContentClass.EXTERIOR)
                    ? copy.get(ContentClass.EXTERIOR).microtileMask() : 0;
            long interior = copy.containsKey(ContentClass.INTERIOR)
                    ? copy.get(ContentClass.INTERIOR).microtileMask() : 0;
            long complex = copy.containsKey(ContentClass.COMPLEX)
                    ? copy.get(ContentClass.COMPLEX).microtileMask() : 0;
            if (((exterior | interior) & ~complex) != 0 || (exterior & interior) != 0) {
                throw new IllegalArgumentException(
                        "production content-class coverage masks are inconsistent");
            }
            contents = Collections.unmodifiableMap(copy);
        }
    }

    /** Immutable, dense CPU representation with O(1) structural-slot lookup. */
    public static final class ManifestSubtree {
        private final SpatialNode root;
        private final BitSet tileAvailability;
        private final BitSet descriptorPageAvailability;
        private final List<Hash256> descriptorPages;
        private final List<ManifestNode> nodes;
        private final int[] denseIndexBySlot;
        private final int[] denseDescriptorPageIndex;

        private ManifestSubtree(SpatialNode root, BitSet tileAvailability,
                                BitSet descriptorPageAvailability,
                                List<Hash256> descriptorPages,
                                List<ManifestNode> nodes) {
            this.root = Objects.requireNonNull(root, "root");
            this.tileAvailability = (BitSet) tileAvailability.clone();
            this.descriptorPageAvailability = (BitSet) descriptorPageAvailability.clone();
            this.descriptorPages = List.copyOf(descriptorPages);
            this.nodes = List.copyOf(nodes);
            this.denseIndexBySlot = new int[STRUCTURAL_SLOTS];
            java.util.Arrays.fill(this.denseIndexBySlot, -1);
            int dense = 0;
            for (int slot = this.tileAvailability.nextSetBit(0); slot >= 0;
                 slot = this.tileAvailability.nextSetBit(slot + 1)) {
                this.denseIndexBySlot[slot] = dense++;
            }
            this.denseDescriptorPageIndex = new int[DESCRIPTOR_PAGE_SLOTS];
            java.util.Arrays.fill(this.denseDescriptorPageIndex, -1);
            dense = 0;
            for (int page = this.descriptorPageAvailability.nextSetBit(0); page >= 0;
                 page = this.descriptorPageAvailability.nextSetBit(page + 1)) {
                this.denseDescriptorPageIndex[page] = dense++;
            }
        }

        public SpatialNode root() {
            return this.root;
        }

        public int levels() {
            return MAX_SUBTREE_LEVELS;
        }

        public int structuralSlots() {
            return this.denseIndexBySlot.length;
        }

        public int descriptorPageSlots() {
            return DESCRIPTOR_PAGE_SLOTS;
        }

        public int availableNodeCount() {
            return this.nodes.size();
        }

        public Optional<Hash256> descriptorPage(int pageIndex) {
            checkSlot(pageIndex, DESCRIPTOR_PAGE_SLOTS);
            int dense = this.denseDescriptorPageIndex[pageIndex];
            return dense < 0 ? Optional.empty() : Optional.of(this.descriptorPages.get(dense));
        }

        public boolean tileAvailable(int structuralSlot) {
            checkSlot(structuralSlot, STRUCTURAL_SLOTS);
            return this.tileAvailability.get(structuralSlot);
        }

        public Optional<ManifestNode> node(int structuralSlot) {
            checkSlot(structuralSlot, structuralSlots());
            int dense = this.denseIndexBySlot[structuralSlot];
            return dense < 0 ? Optional.empty() : Optional.of(this.nodes.get(dense));
        }

        public List<ManifestNode> nodes() {
            return this.nodes;
        }

    }

    /** One independently authenticated, fixed structural-slot range of content descriptors. */
    public static final class DescriptorPage {
        private final SpatialNode root;
        private final int pageIndex;
        private final int slotCount;
        private final List<Map<ContentClass, ContentDescriptor>> contentsBySlot;

        private DescriptorPage(SpatialNode root, int pageIndex, int slotCount,
                               List<Map<ContentClass, ContentDescriptor>> contentsBySlot) {
            this.root = Objects.requireNonNull(root, "root");
            this.pageIndex = pageIndex;
            this.slotCount = slotCount;
            ArrayList<Map<ContentClass, ContentDescriptor>> copy =
                    new ArrayList<>(contentsBySlot.size());
            for (Map<ContentClass, ContentDescriptor> contents : contentsBySlot) {
                EnumMap<ContentClass, ContentDescriptor> values =
                        new EnumMap<>(ContentClass.class);
                values.putAll(Objects.requireNonNull(contents, "descriptor contents"));
                copy.add(Collections.unmodifiableMap(values));
            }
            this.contentsBySlot = List.copyOf(copy);
        }

        public SpatialNode root() { return this.root; }
        public int pageIndex() { return this.pageIndex; }
        public int slotCount() { return this.slotCount; }
        public int firstStructuralSlot() {
            return Math.multiplyExact(this.pageIndex, DESCRIPTOR_PAGE_NODE_SLOTS);
        }

        public Map<ContentClass, ContentDescriptor> contents(int localSlot) {
            checkSlot(localSlot, this.slotCount);
            return this.contentsBySlot.get(localSlot);
        }

        public int availableEntryCount() {
            int count = 0;
            for (Map<ContentClass, ContentDescriptor> contents : this.contentsBySlot) {
                if (!contents.isEmpty()) count++;
            }
            return count;
        }

        /** Binds an independently hashed page to its structural manifest before publication. */
        public void validateAgainst(ManifestSubtree manifest) throws DecodeException {
            Objects.requireNonNull(manifest, "manifest");
            if (!this.root.equals(manifest.root())
                    || manifest.descriptorPage(this.pageIndex).isEmpty()
                    || this.slotCount != descriptorPageSlotCount(this.pageIndex)) {
                throw new DecodeException("descriptor page does not belong to its manifest");
            }
            int first = firstStructuralSlot();
            for (int local = 0; local < this.slotCount; local++) {
                if (manifest.tileAvailable(first + local)
                        != !this.contentsBySlot.get(local).isEmpty()) {
                    throw new DecodeException(
                            "descriptor-page availability disagrees with manifest tiles");
                }
            }
        }
    }

    public static RootDirectory decodeRootDirectory(byte[] canonicalBytes) throws DecodeException {
        Objects.requireNonNull(canonicalBytes, "canonicalBytes");
        if (canonicalBytes.length < 12 || canonicalBytes.length > MAX_MANIFEST_BYTES) {
            throw new DecodeException("bad or oversized root directory");
        }
        Reader input = new Reader(canonicalBytes);
        input.expect(DIRECTORY_MAGIC, "root-directory magic");
        long unsignedCount = input.u32();
        if (unsignedCount > MAX_DIRECTORY_ENTRIES) {
            throw new DecodeException("invalid root-directory entry count");
        }
        int count = (int) unsignedCount;
        if (input.remaining() != Math.multiplyExact(count, DIRECTORY_ENTRY_BYTES)) {
            throw new DecodeException("root-directory size does not match its entry count");
        }
        List<RootDirectoryEntry> entries = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            int lod = input.u8();
            DirectoryTarget target = DirectoryTarget.decode(input.u8());
            SpatialNode node;
            TopRootBounds bounds;
            try {
                node = new SpatialNode(lod, input.i32(), input.i32(), input.i32());
                bounds = new TopRootBounds(input.i32(), input.i32(), input.i32(),
                        input.i32(), input.i32(), input.i32());
            } catch (IllegalArgumentException exception) {
                throw new DecodeException(exception.getMessage(), exception);
            }
            entries.add(new RootDirectoryEntry(node, bounds, target, input.hash()));
        }
        try {
            return new RootDirectory(entries);
        } catch (IllegalArgumentException exception) {
            throw new DecodeException(exception.getMessage(), exception);
        }
    }

    public static ManifestSubtree decodeManifestSubtree(byte[] canonicalBytes) throws DecodeException {
        Objects.requireNonNull(canonicalBytes, "canonicalBytes");
        if (canonicalBytes.length > MAX_MANIFEST_BYTES) {
            throw new DecodeException("manifest exceeds its byte bound");
        }
        Reader input = new Reader(canonicalBytes);
        input.expect(MANIFEST_MAGIC, "manifest magic");
        int levels = input.u8();
        int rootLod = input.u8();
        if (levels != MAX_SUBTREE_LEVELS || rootLod != MAX_LOD) {
            throw new DecodeException(
                    "production manifest must contain one complete LOD-4 hierarchy");
        }
        SpatialNode root;
        try {
            root = new SpatialNode(rootLod, input.i32(), input.i32(), input.i32());
        } catch (IllegalArgumentException exception) {
            throw new DecodeException(exception.getMessage(), exception);
        }

        int slots = STRUCTURAL_SLOTS;
        if (input.u32() != slots || input.u32() != DESCRIPTOR_PAGE_SLOTS) {
            throw new DecodeException("non-canonical manifest slot counts");
        }
        long unsignedNodeCount = input.u32();
        if (unsignedNodeCount > slots) {
            throw new DecodeException("manifest node count exceeds structural slots");
        }
        int nodeCount = (int) unsignedNodeCount;
        int tileBytes = bytesForBits(slots);
        BitSet tiles = input.bitSet(tileBytes, slots, "tile");
        BitSet descriptorPages = input.bitSet(bytesForBits(DESCRIPTOR_PAGE_SLOTS),
                DESCRIPTOR_PAGE_SLOTS, "descriptor-page");
        List<Hash256> descriptorPageHashes = new ArrayList<>(descriptorPages.cardinality());
        for (int index = 0; index < descriptorPages.cardinality(); index++) {
            descriptorPageHashes.add(input.hash());
        }
        if (!tiles.get(0) || tiles.cardinality() != nodeCount) {
            throw new DecodeException("manifest root or dense node count is invalid");
        }
        validateAvailableParents(tiles, levels);
        for (int page = 0; page < DESCRIPTOR_PAGE_SLOTS; page++) {
            int first = page * DESCRIPTOR_PAGE_NODE_SLOTS;
            int end = Math.min(first + DESCRIPTOR_PAGE_NODE_SLOTS, STRUCTURAL_SLOTS);
            boolean containsTile = tiles.nextSetBit(first) >= first
                    && tiles.nextSetBit(first) < end;
            if (descriptorPages.get(page) != containsTile) {
                throw new DecodeException(
                        "descriptor-page availability disagrees with structural tiles");
            }
        }

        List<ManifestNode> nodes = new ArrayList<>(nodeCount);
        for (int slot = 0; slot < slots; slot++) {
            if (!tiles.get(slot)) continue;
            int childMask = input.u8();
            int boundsPresent = input.u8();
            if (boundsPresent > 1) {
                throw new DecodeException("invalid manifest node flags");
            }
            long geometricError = input.u32();
            int[] values = new int[6];
            for (int index = 0; index < values.length; index++) values[index] = input.u16();
            Optional<QuantizedBounds> bounds;
            if (boundsPresent == 0) {
                for (int value : values) {
                    if (value != 0) throw new DecodeException("absent bounds contain nonzero data");
                }
                bounds = Optional.empty();
            } else {
                try {
                    bounds = Optional.of(new QuantizedBounds(values[0], values[1], values[2],
                            values[3], values[4], values[5]));
                } catch (IllegalArgumentException exception) {
                    throw new DecodeException(exception.getMessage(), exception);
                }
            }
            int expectedChildren = expectedChildMask(tiles, levels, slot);
            if (childMask != expectedChildren) {
                throw new DecodeException("manifest child mask disagrees with availability");
            }
            nodes.add(new ManifestNode(childMask, bounds, geometricError, Map.of()));
        }
        if (nodes.size() != nodeCount || input.remaining() != 0) {
            throw new DecodeException("manifest node count or trailing data is invalid");
        }
        return new ManifestSubtree(root, tiles, descriptorPages, descriptorPageHashes, nodes);
    }

    public static DescriptorPage decodeDescriptorPage(byte[] canonicalBytes)
            throws DecodeException {
        Objects.requireNonNull(canonicalBytes, "canonicalBytes");
        if (canonicalBytes.length > MAX_MANIFEST_BYTES) {
            throw new DecodeException("descriptor page exceeds its byte bound");
        }
        Reader input = new Reader(canonicalBytes);
        input.expect(DESCRIPTOR_PAGE_MAGIC, "descriptor-page magic");
        int pageIndex = input.u16();
        int levels = input.u8();
        int rootLod = input.u8();
        if (levels != MAX_SUBTREE_LEVELS || rootLod != MAX_LOD
                || pageIndex >= DESCRIPTOR_PAGE_SLOTS) {
            throw new DecodeException("invalid descriptor-page identity");
        }
        SpatialNode root;
        try {
            root = new SpatialNode(rootLod, input.i32(), input.i32(), input.i32());
        } catch (IllegalArgumentException exception) {
            throw new DecodeException(exception.getMessage(), exception);
        }
        int slotCount = input.u16();
        int entryCount = input.u16();
        if (slotCount != descriptorPageSlotCount(pageIndex) || entryCount > slotCount) {
            throw new DecodeException("non-canonical descriptor-page slot counts");
        }
        int availabilityBytes = bytesForBits(slotCount);
        BitSet[] availability = new BitSet[ContentClass.values().length];
        BitSet union = new BitSet(slotCount);
        for (ContentClass contentClass : ContentClass.values()) {
            BitSet present = input.bitSet(availabilityBytes, slotCount, "descriptor class");
            availability[contentClass.ordinal()] = present;
            union.or(present);
        }
        if (union.cardinality() != entryCount || entryCount == 0) {
            throw new DecodeException("descriptor-page entry count disagrees with availability");
        }
        ArrayList<Map<ContentClass, ContentDescriptor>> contentsBySlot =
                new ArrayList<>(slotCount);
        int[] objectReferences = {0};
        for (int localSlot = 0; localSlot < slotCount; localSlot++) {
            EnumMap<ContentClass, ContentDescriptor> contents =
                    new EnumMap<>(ContentClass.class);
            for (ContentClass contentClass : ContentClass.values()) {
                if (availability[contentClass.ordinal()].get(localSlot)) {
                    contents.put(contentClass,
                            decodeContentDescriptor(input, objectReferences));
                }
            }
            if (union.get(localSlot)) {
                try {
                    contents = new EnumMap<>(new ManifestNode(0, Optional.empty(), 0,
                            contents).contents());
                } catch (IllegalArgumentException exception) {
                    throw new DecodeException(exception.getMessage(), exception);
                }
            }
            contentsBySlot.add(contents);
        }
        if (input.remaining() != 0) {
            throw new DecodeException("descriptor page contains trailing data");
        }
        return new DescriptorPage(root, pageIndex, slotCount, contentsBySlot);
    }

    private static ContentDescriptor decodeContentDescriptor(Reader input,
                                                               int[] objectReferences)
            throws DecodeException {
        int edge = input.u8();
        if (input.u8() != 0) throw new DecodeException("nonzero content flags");
        if (input.u8() != 0) throw new DecodeException("nonzero content reserved byte");
        int boundaryFaceMask = input.u8();
        if ((boundaryFaceMask & ~0x3f) != 0) {
            throw new DecodeException("invalid content boundary face mask");
        }
        int objectCount = input.u16();
        int dependencyCount = input.u16();
        int[] neighborCounts = new int[NeighborFace.values().length];
        int neighborDependencyCount = 0;
        for (int face = 0; face < neighborCounts.length; face++) {
            neighborCounts[face] = input.u16();
            neighborDependencyCount = Math.addExact(
                    neighborDependencyCount, neighborCounts[face]);
        }
        int boundaryBytes = input.u16();
        int visibilityMembershipCount = input.u16();
        if (dependencyCount > MAX_DEPENDENCIES_PER_CONTENT
                || neighborDependencyCount > MAX_NEIGHBOR_DEPENDENCIES_PER_CONTENT
                || visibilityMembershipCount > MAX_VISIBILITY_MEMBERSHIPS_PER_CONTENT
                || boundaryBytes != Integer.bitCount(boundaryFaceMask)
                * BOUNDARY_FACE_BYTES) {
            throw new DecodeException("invalid content descriptor counts");
        }
        long mask = input.i64();
        long exteriorVisibilityMask = input.i64();
        long unknownVisibilityMask = input.i64();
        long[] neighborMasks = new long[NeighborFace.values().length];
        for (int face = 0; face < neighborMasks.length; face++) {
            neighborMasks[face] = input.i64();
            if ((neighborMasks[face] & ~mask) != 0
                    || Long.bitCount(neighborMasks[face]) != neighborCounts[face]) {
                throw new DecodeException(
                        "neighbor dependency mask and dense hashes disagree");
            }
        }
        objectReferences[0] = Math.addExact(objectReferences[0],
                Math.addExact(objectCount,
                        Math.addExact(dependencyCount, neighborDependencyCount)));
        if (objectReferences[0] > MAX_OBJECT_REFERENCES) {
            throw new DecodeException("descriptor page exceeds its object-reference bound");
        }
        List<Hash256> objects = new ArrayList<>(objectCount);
        List<Hash256> dependencies = new ArrayList<>(dependencyCount);
        List<NeighborDependency> neighborDependencies =
                new ArrayList<>(neighborDependencyCount);
        for (int index = 0; index < objectCount; index++) objects.add(input.hash());
        for (int index = 0; index < dependencyCount; index++) dependencies.add(input.hash());
        for (int face = 0; face < neighborCounts.length; face++) {
            NeighborFace neighborFace = NeighborFace.decode(face);
            for (int source = 0; source < Long.SIZE; source++) {
                if ((neighborMasks[face] & 1L << source) != 0) {
                    neighborDependencies.add(new NeighborDependency(
                            source, neighborFace, input.hash()));
                }
            }
        }
        List<VisibilityMembership> visibilityMemberships =
                new ArrayList<>(visibilityMembershipCount);
        for (int index = 0; index < visibilityMembershipCount; index++) {
            try {
                visibilityMemberships.add(new VisibilityMembership(input.i64(), input.i64()));
            } catch (IllegalArgumentException exception) {
                throw new DecodeException(exception.getMessage(), exception);
            }
        }
        byte[] boundary = input.bytes(boundaryBytes);
        try {
            return new ContentDescriptor(edge, mask, objects, dependencies,
                    neighborDependencies, new BoundarySummary(boundaryFaceMask, boundary),
                    exteriorVisibilityMask, unknownVisibilityMask, visibilityMemberships);
        } catch (IllegalArgumentException exception) {
            throw new DecodeException(exception.getMessage(), exception);
        }
    }

    public static int slotsForLevels(int levels) {
        if (levels < 0 || levels > MAX_SUBTREE_LEVELS) {
            throw new IllegalArgumentException("invalid subtree level count");
        }
        int slots = 0;
        for (int depth = 0; depth < levels; depth++) slots = Math.addExact(slots, pow8(depth));
        return slots;
    }

    public static int levelOffset(int depth) {
        return slotsForLevels(depth);
    }

    private static int expectedChildMask(BitSet tiles, int levels, int slot)
            throws DecodeException {
        int depth = depthOfSlot(slot, levels);
        int morton = slot - levelOffset(depth);
        int mask = 0;
        if (depth + 1 < levels) {
            int childOffset = levelOffset(depth + 1);
            for (int child = 0; child < 8; child++) {
                if (tiles.get(childOffset + morton * 8 + child)) mask |= 1 << child;
            }
        }
        return mask;
    }

    public static int descriptorPageSlotCount(int pageIndex) {
        checkSlot(pageIndex, DESCRIPTOR_PAGE_SLOTS);
        int first = Math.multiplyExact(pageIndex, DESCRIPTOR_PAGE_NODE_SLOTS);
        return Math.min(DESCRIPTOR_PAGE_NODE_SLOTS, STRUCTURAL_SLOTS - first);
    }

    private static int depthOfSlot(int slot, int levels) throws DecodeException {
        for (int depth = 0; depth < levels; depth++) {
            int start = levelOffset(depth);
            int end = start + pow8(depth);
            if (slot >= start && slot < end) return depth;
        }
        throw new DecodeException("structural slot is out of bounds");
    }

    private static void validateAvailableParents(BitSet tiles, int levels) throws DecodeException {
        for (int depth = 1; depth < levels; depth++) {
            int offset = levelOffset(depth);
            int parentOffset = levelOffset(depth - 1);
            for (int morton = 0; morton < pow8(depth); morton++) {
                if (tiles.get(offset + morton) && !tiles.get(parentOffset + (morton >>> 3))) {
                    throw new DecodeException("available manifest tile has an unavailable parent");
                }
            }
        }
    }

    private static int compareSpatial(SpatialNode left, SpatialNode right) {
        if (left.lod() != MAX_LOD || right.lod() != MAX_LOD) {
            return Integer.compare(left.lod(), right.lod());
        }
        int leftX = left.x() ^ Integer.MIN_VALUE;
        int leftY = left.y() ^ Integer.MIN_VALUE;
        int leftZ = left.z() ^ Integer.MIN_VALUE;
        int rightX = right.x() ^ Integer.MIN_VALUE;
        int rightY = right.y() ^ Integer.MIN_VALUE;
        int rightZ = right.z() ^ Integer.MIN_VALUE;
        for (int bit = Integer.SIZE - 1; bit >= 0; bit--) {
            int leftOctant = leftX >>> bit & 1
                    | (leftY >>> bit & 1) << 1 | (leftZ >>> bit & 1) << 2;
            int rightOctant = rightX >>> bit & 1
                    | (rightY >>> bit & 1) << 1 | (rightZ >>> bit & 1) << 2;
            if (leftOctant != rightOctant) return Integer.compare(leftOctant, rightOctant);
        }
        return 0;
    }

    private static int compareHash(Hash256 first, Hash256 second) {
        byte[] left = first.toBytes();
        byte[] right = second.toBytes();
        for (int index = 0; index < left.length; index++) {
            int compared = Integer.compare(Byte.toUnsignedInt(left[index]),
                    Byte.toUnsignedInt(right[index]));
            if (compared != 0) return compared;
        }
        return 0;
    }

    private static int pow8(int exponent) {
        int value = 1;
        for (int index = 0; index < exponent; index++) value = Math.multiplyExact(value, 8);
        return value;
    }

    private static int bytesForBits(int bitCount) {
        return Math.floorDiv(Math.addExact(bitCount, 7), 8);
    }

    private static void checkSlot(int slot, int slots) {
        if (slot < 0 || slot >= slots) throw new IndexOutOfBoundsException(slot);
    }

    public static final class DecodeException extends Exception {
        public DecodeException(String message) {
            super(message);
        }

        public DecodeException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final class Reader {
        private final ByteBuffer input;

        private Reader(byte[] bytes) {
            this.input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        }

        private int remaining() {
            return this.input.remaining();
        }

        private int u8() throws DecodeException {
            require(1);
            return Byte.toUnsignedInt(this.input.get());
        }

        private int u16() throws DecodeException {
            require(Short.BYTES);
            return Short.toUnsignedInt(this.input.getShort());
        }

        private long u32() throws DecodeException {
            require(Integer.BYTES);
            return Integer.toUnsignedLong(this.input.getInt());
        }

        private int i32() throws DecodeException {
            require(Integer.BYTES);
            return this.input.getInt();
        }

        private long i64() throws DecodeException {
            require(Long.BYTES);
            return this.input.getLong();
        }

        private Hash256 hash() throws DecodeException {
            byte[] bytes = bytes(32);
            try {
                return Hash256.fromBytes(bytes);
            } catch (IllegalArgumentException exception) {
                throw new DecodeException(exception.getMessage(), exception);
            }
        }

        private BitSet bitSet(int byteCount, int bitCount, String name) throws DecodeException {
            byte[] bytes = bytes(byteCount);
            if ((bitCount & 7) != 0) {
                int allowed = (1 << (bitCount & 7)) - 1;
                if ((Byte.toUnsignedInt(bytes[bytes.length - 1]) & ~allowed) != 0) {
                    throw new DecodeException(name + " availability has nonzero padding bits");
                }
            }
            return BitSet.valueOf(bytes);
        }

        private byte[] bytes(int count) throws DecodeException {
            require(count);
            byte[] output = new byte[count];
            this.input.get(output);
            return output;
        }

        private void expect(byte[] expected, String label) throws DecodeException {
            if (!java.util.Arrays.equals(bytes(expected.length), expected)) {
                throw new DecodeException("bad " + label);
            }
        }

        private void require(int count) throws DecodeException {
            if (count < 0 || this.input.remaining() < count) {
                throw new DecodeException("truncated canonical manifest object");
            }
        }
    }
}
