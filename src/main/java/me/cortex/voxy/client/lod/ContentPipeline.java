package me.cortex.voxy.client.lod;

import me.cortex.voxy.client.lod.ManifestCodec.BoundarySummary;
import me.cortex.voxy.client.lod.ManifestCodec.ContentClass;
import me.cortex.voxy.client.lod.ManifestCodec.NeighborDependency;
import me.cortex.voxy.client.lod.ManifestCodec.NeighborFace;
import me.cortex.voxy.client.lod.ManifestCodec.SpatialNode;
import me.cortex.voxy.client.lod.ManifestCodec.VisibilityMembership;
import me.cortex.voxy.client.lod.RootDemandPlan.Binding;
import me.cortex.voxy.client.lod.RootDemandPlan.ContentLayer;
import me.cortex.voxy.client.lod.RootDemandPlan.ContentObject;
import me.cortex.voxy.client.core.model.CatalogMapper;
import me.cortex.voxy.client.lod.WireMessage.Hash256;
import me.cortex.voxy.client.lod.WireMessage.RootToken;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * Resolves authenticated 8-cubed objects into one final renderer activation group.
 *
 * <p>Exterior/interior objects use the GPU path only when every local baked model is proven safe.
 * A failed local classification selects the same-bit complex companion. Complex objects are the
 * final canonical representation for unknown/modded content.</p>
 */
public final class ContentPipeline {
    private static final byte[] TERRAIN_IDENTITY_DOMAIN =
            "Voxy virtual surface terrain group\0".getBytes(StandardCharsets.UTF_8);

    public enum ModelClass {
        AIR,
        SAFE_OPAQUE_CUBE,
        SAFE_TEMPLATE,
        COMPLEX,
        UNKNOWN;

        public boolean gpuSafe() {
            return this == AIR || this == SAFE_OPAQUE_CUBE || this == SAFE_TEMPLATE;
        }
    }

    public interface ModelCompatibility {
        /** Must include catalog authority and the current baked resource/model state. */
        ModelClass classify(int localBlockId);

        /** True once meshing may safely read this local model and its GPU metadata. */
        boolean ready(int localBlockId);
    }

    public record CatalogMappings(long catalogId, int[] blocks, int[] biomes) {
        public CatalogMappings {
            if (catalogId == 0) throw new IllegalArgumentException("catalog identity zero is reserved");
            blocks = Objects.requireNonNull(blocks, "blocks").clone();
            biomes = Objects.requireNonNull(biomes, "biomes").clone();
            if (blocks.length == 0 || biomes.length == 0) {
                throw new IllegalArgumentException("catalog translations are empty");
            }
        }

        @Override
        public int[] blocks() {
            return this.blocks.clone();
        }

        @Override
        public int[] biomes() {
            return this.biomes.clone();
        }

    }

    public record RendererIdentity(Hash256 resourceModelFingerprint) {
        public RendererIdentity {
            Objects.requireNonNull(resourceModelFingerprint, "resourceModelFingerprint");
        }
    }

    public enum MeshingPath {
        GPU_OPAQUE_TEMPLATE,
        CPU_COMPLEX
    }

    /** Exact fixed-8-cubed content cut selected for one structural node. */
    public record SelectionCut(long exteriorMask, long interiorMask, long complexMask) {
        public SelectionCut {
            if ((exteriorMask | interiorMask | complexMask) == 0) {
                throw new IllegalArgumentException("a content selection cut cannot be empty");
            }
        }

        public long mask(ContentClass contentClass) {
            return switch (Objects.requireNonNull(contentClass, "contentClass")) {
                case EXTERIOR -> this.exteriorMask;
                case INTERIOR -> this.interiorMask;
                case COMPLEX -> this.complexMask;
            };
        }

        public boolean contains(ContentClass contentClass, int microtileIndex) {
            if (microtileIndex < 0 || microtileIndex >= Long.SIZE) {
                throw new IllegalArgumentException("microtile index outside fixed cut");
            }
            return (mask(contentClass) & 1L << microtileIndex) != 0;
        }

    }

    /**
     * Local resource/model classification projected onto manifest availability.
     * Unclassified ordinary slots stay exposed so they can be fetched and classified. An unsafe
     * ordinary slot is removed from both ordinary layers and exposes only its complex companion.
     */
    public record CompatibilityState(long exteriorAvailableMask, long interiorAvailableMask,
                                     long complexAvailableMask, long unclassifiedOrdinaryMask,
                                     long compatibleOrdinaryMask, long complexRequiredMask) {
        public CompatibilityState {
            long overlap = unclassifiedOrdinaryMask & compatibleOrdinaryMask
                    | unclassifiedOrdinaryMask & complexRequiredMask
                    | compatibleOrdinaryMask & complexRequiredMask;
            if (overlap != 0) {
                throw new IllegalArgumentException("compatibility state masks overlap");
            }
            if ((complexRequiredMask & ~complexAvailableMask) != 0) {
                throw new IllegalArgumentException("required complex slots are not exposed");
            }
        }

    }

    public record PreparedMicrotile(ContentObject object, MicrotileCodec.Prepared content,
                                    MeshingPath meshingPath) {
        public PreparedMicrotile {
            Objects.requireNonNull(object, "object");
            Objects.requireNonNull(content, "content");
            Objects.requireNonNull(meshingPath, "meshingPath");
            if (object.contentClass() != content.metadata().contentClass()
                    || object.microtileIndex() != content.metadata().microtileIndex()) {
                throw new IllegalArgumentException("prepared microtile disagrees with its manifest slot");
            }
            if (object.contentClass() == ContentClass.COMPLEX
                    && meshingPath != MeshingPath.CPU_COMPLEX) {
                throw new IllegalArgumentException("complex content cannot enter the GPU fast path");
            }
        }
    }

    public record LayerMetadata(ContentClass contentClass, BoundarySummary boundarySummary,
                                long exteriorVisibilityMask, long unknownVisibilityMask,
                                List<VisibilityMembership> visibilityMemberships) {
        public LayerMetadata {
            Objects.requireNonNull(contentClass, "contentClass");
            Objects.requireNonNull(boundarySummary, "boundarySummary");
            visibilityMemberships = List.copyOf(Objects.requireNonNull(
                    visibilityMemberships, "visibilityMemberships"));
        }
    }

    public record DependencyMicrotile(Hash256 hash, ContentClass contentClass,
                                      MicrotileCodec.Prepared content) {
        public DependencyMicrotile {
            Objects.requireNonNull(hash, "hash");
            Objects.requireNonNull(contentClass, "contentClass");
            Objects.requireNonNull(content, "content");
            if (content.metadata().contentClass() != contentClass) {
                throw new IllegalArgumentException(
                        "prepared dependency disagrees with its descriptor class");
            }
        }
    }

    /** Exact complex context adjacent to one selected source microtile face. */
    public record NeighborDependencyMicrotile(int sourceMicrotileIndex,
                                              NeighborFace face, Hash256 hash,
                                              ContentClass contentClass,
                                              MicrotileCodec.Prepared content) {
        public NeighborDependencyMicrotile {
            if (sourceMicrotileIndex < 0 || sourceMicrotileIndex >= Long.SIZE) {
                throw new IllegalArgumentException(
                        "neighbor dependency source is outside fixed 8-cubed content");
            }
            Objects.requireNonNull(face, "face");
            Objects.requireNonNull(hash, "hash");
            Objects.requireNonNull(contentClass, "contentClass");
            Objects.requireNonNull(content, "content");
            if (content.metadata().contentClass() != contentClass) {
                throw new IllegalArgumentException(
                        "prepared neighbor dependency disagrees with its descriptor class");
            }
            if (!isAdjacentMicrotile(sourceMicrotileIndex, face, content.metadata())) {
                throw new IllegalArgumentException(
                        "neighbor dependency is not adjacent to its source microtile face");
            }
        }
    }

    public record ActivationGroup(RootToken root, SpatialNode node, int childMask,
                                  Optional<ManifestCodec.QuantizedBounds> bounds,
                                  long geometricErrorQ16, Hash256 terrainIdentity,
                                  RendererIdentity rendererIdentity, SelectionCut selectionCut,
                                  List<PreparedMicrotile> microtiles,
                                  List<DependencyMicrotile> dependencyMicrotiles,
                                  List<NeighborDependencyMicrotile> neighborDependencyMicrotiles,
                                  List<LayerMetadata> layerMetadata) {
        public ActivationGroup {
            Objects.requireNonNull(root, "root");
            Objects.requireNonNull(node, "node");
            if ((childMask & ~0xff) != 0 || geometricErrorQ16 < 0
                    || geometricErrorQ16 > 0xffff_ffffL) {
                throw new IllegalArgumentException("invalid activation structural metadata");
            }
            bounds = Objects.requireNonNull(bounds, "bounds");
            Objects.requireNonNull(terrainIdentity, "terrainIdentity");
            Objects.requireNonNull(rendererIdentity, "rendererIdentity");
            Objects.requireNonNull(selectionCut, "selectionCut");
            microtiles = List.copyOf(Objects.requireNonNull(microtiles, "microtiles"));
            dependencyMicrotiles = uniqueDependencies(dependencyMicrotiles, "dependencies");
            neighborDependencyMicrotiles = uniqueNeighborDependencies(
                    neighborDependencyMicrotiles, "neighbor dependencies");
            layerMetadata = List.copyOf(Objects.requireNonNull(layerMetadata, "layerMetadata"));
            if (microtiles.isEmpty()) {
                throw new IllegalArgumentException("activation group has no content");
            }
            LinkedHashSet<Hash256> distinct = new LinkedHashSet<>();
            for (PreparedMicrotile microtile : microtiles) {
                if (!distinct.add(Objects.requireNonNull(microtile, "microtile").object().hash())) {
                    throw new IllegalArgumentException("activation group repeats a microtile");
                }
            }
        }

        public List<PreparedMicrotile> gpuMicrotiles() {
            return this.microtiles.stream()
                    .filter(tile -> tile.meshingPath() == MeshingPath.GPU_OPAQUE_TEMPLATE).toList();
        }

        public List<PreparedMicrotile> cpuMicrotiles() {
            return this.microtiles.stream()
                    .filter(tile -> tile.meshingPath() == MeshingPath.CPU_COMPLEX).toList();
        }

        public List<Hash256> requiredHashes() {
            LinkedHashSet<Hash256> hashes = new LinkedHashSet<>();
            for (PreparedMicrotile microtile : this.microtiles) hashes.add(microtile.object().hash());
            for (DependencyMicrotile dependency : this.dependencyMicrotiles) {
                hashes.add(dependency.hash());
            }
            for (NeighborDependencyMicrotile dependency : this.neighborDependencyMicrotiles) {
                hashes.add(dependency.hash());
            }
            return List.copyOf(hashes);
        }
    }

    /** Computes the effective selector availability without requesting latent companions. */
    public CompatibilityState resolveCompatibility(
            Binding binding, ModelCompatibility compatibility,
            Function<Hash256, Optional<MicrotileCodec.Prepared>> microtileLookup) {
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(compatibility, "compatibility");
        Objects.requireNonNull(microtileLookup, "microtileLookup");
        ContentObject[][] objects = indexedObjects(binding);
        long exterior = mask(objects[ContentClass.EXTERIOR.ordinal()]);
        long interior = mask(objects[ContentClass.INTERIOR.ordinal()]);
        long complex = mask(objects[ContentClass.COMPLEX.ordinal()]);
        long ordinary = exterior | interior;
        long unclassified = 0;
        long compatibleMask = 0;
        long complexRequired = 0;
        for (int index = 0; index < Long.SIZE; index++) {
            long bit = 1L << index;
            if ((ordinary & bit) == 0) continue;
            boolean missing = false;
            boolean modelsPending = false;
            boolean unsafe = false;
            for (ContentClass contentClass : List.of(ContentClass.EXTERIOR,
                    ContentClass.INTERIOR)) {
                ContentObject object = objects[contentClass.ordinal()][index];
                if (object == null) continue;
                Optional<MicrotileCodec.Prepared> value = Objects.requireNonNull(
                        microtileLookup.apply(object.hash()), "microtile lookup result");
                if (value.isEmpty()) {
                    missing = true;
                } else {
                    MicrotileCodec.Prepared content = value.orElseThrow();
                    if (!modelsReady(content, compatibility)) modelsPending = true;
                    else if (!gpuSafe(content, compatibility)) unsafe = true;
                }
            }
            if (modelsPending) {
                unclassified |= bit;
            } else if (unsafe) {
                if ((complex & bit) == 0) {
                    throw new IncompatibleContentException(
                            "unsafe ordinary microtile has no complex companion at slot " + index);
                }
                complexRequired |= bit;
            } else if (missing) {
                unclassified |= bit;
            } else {
                compatibleMask |= bit;
            }
        }
        long effectiveExterior = exterior & ~complexRequired;
        long effectiveInterior = interior & ~complexRequired;
        long directComplex = complex & ~ordinary;
        long effectiveComplex = directComplex | complexRequired;
        return new CompatibilityState(effectiveExterior, effectiveInterior, effectiveComplex,
                unclassified, compatibleMask, complexRequired);
    }

    /** Uses residency-owned translated microtiles without allocating a second decoded copy. */
    public ActivationGroup prepareResident(RootToken root, Binding binding,
                                           SelectionCut selectionCut,
                                           RendererIdentity rendererIdentity,
                                           ModelCompatibility compatibility,
                                           Function<Hash256, Optional<MicrotileCodec.Prepared>>
                                                   microtileLookup,
                                           java.util.function.Predicate<Hash256> dependencyResident)
            throws MissingObjectsException, ModelsNotReadyException {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(selectionCut, "selectionCut");
        Objects.requireNonNull(rendererIdentity, "rendererIdentity");
        Objects.requireNonNull(compatibility, "compatibility");
        Objects.requireNonNull(microtileLookup, "microtileLookup");
        Objects.requireNonNull(dependencyResident, "dependencyResident");
        Map<Hash256, PreparedMicrotile> decoded = new HashMap<>();
        try {
            return finish(root, binding, selectionCut, rendererIdentity, compatibility, object -> {
                PreparedMicrotile present = decoded.get(object.hash());
                if (present != null) return Optional.of(present);
                Optional<MicrotileCodec.Prepared> value = Objects.requireNonNull(
                        microtileLookup.apply(object.hash()), "microtile lookup result");
                if (value.isEmpty()) return Optional.empty();
                PreparedMicrotile result = prepared(object, value.orElseThrow());
                decoded.put(object.hash(), result);
                return Optional.of(result);
            }, (hash, contentClass) -> {
                if (!dependencyResident.test(hash)) return Optional.empty();
                Optional<MicrotileCodec.Prepared> value = Objects.requireNonNull(
                        microtileLookup.apply(hash), "dependency microtile lookup result");
                return value.map(prepared -> new DependencyMicrotile(
                        hash, contentClass, prepared));
            });
        } catch (MicrotileCodec.DecodeException impossible) {
            throw new IllegalStateException("resident microtile unexpectedly required decoding",
                    impossible);
        }
    }

    private ActivationGroup finish(RootToken root, Binding binding, SelectionCut selectionCut,
                                   RendererIdentity rendererIdentity,
                                   ModelCompatibility compatibility,
                                   PreparedLookup lookup,
                                   DependencyLookup dependencyLookup)
            throws MicrotileCodec.DecodeException, MissingObjectsException,
            ModelsNotReadyException {

        ContentObject[][] objects = indexedObjects(binding);

        ArrayList<PreparedMicrotile> selected = new ArrayList<>();
        LinkedHashSet<Hash256> missing = new LinkedHashSet<>();
        long effectiveExterior = 0;
        long effectiveInterior = 0;
        long effectiveComplex = 0;
        for (int microtileIndex = 0; microtileIndex < Long.SIZE; microtileIndex++) {
            long bit = 1L << microtileIndex;
            ContentObject complexObject = objects[ContentClass.COMPLEX.ordinal()][microtileIndex];
            if ((selectionCut.complexMask() & bit) != 0) {
                Optional<PreparedMicrotile> complex = lookup.load(complexObject);
                if (complex.isEmpty()) missing.add(complexObject.hash());
                else {
                    selected.add(complex.orElseThrow());
                    effectiveComplex |= bit;
                }
                continue;
            }

            ArrayList<PreparedMicrotile> ordinary = new ArrayList<>(2);
            boolean ordinaryMissing = false;
            for (ContentClass contentClass : List.of(ContentClass.EXTERIOR,
                    ContentClass.INTERIOR)) {
                if ((selectionCut.mask(contentClass) & bit) == 0) continue;
                ContentObject object = objects[contentClass.ordinal()][microtileIndex];
                Optional<PreparedMicrotile> prepared = lookup.load(object);
                if (prepared.isEmpty()) {
                    missing.add(object.hash());
                    ordinaryMissing = true;
                } else {
                    ordinary.add(prepared.orElseThrow());
                }
            }
            if (ordinaryMissing) continue;
            if (ordinary.isEmpty()) {
                continue;
            }
            boolean gpuSafe = true;
            for (PreparedMicrotile microtile : ordinary) {
                if (!gpuSafe(microtile.content(), compatibility)) {
                    gpuSafe = false;
                    break;
                }
            }
            if (gpuSafe) {
                selected.addAll(ordinary);
                if ((selectionCut.exteriorMask() & bit) != 0) effectiveExterior |= bit;
                if ((selectionCut.interiorMask() & bit) != 0) effectiveInterior |= bit;
            } else if (complexObject != null) {
                Optional<PreparedMicrotile> complex = lookup.load(complexObject);
                if (complex.isEmpty()) missing.add(complexObject.hash());
                else {
                    selected.add(complex.orElseThrow());
                    effectiveComplex |= bit;
                }
            } else {
                throw new IncompatibleContentException(
                        "unsafe exterior/interior microtile has no complex companion at slot "
                                + microtileIndex);
            }
        }
        if (!missing.isEmpty()) {
            throw new MissingObjectsException(missing, List.of(), List.of());
        }
        selected.sort(Comparator.comparingInt(
                        (PreparedMicrotile tile) -> tile.object().microtileIndex())
                .thenComparingInt(tile -> tile.object().contentClass().ordinal()));

        LinkedHashSet<ContentClass> selectedClasses = new LinkedHashSet<>();
        for (PreparedMicrotile microtile : selected) {
            selectedClasses.add(microtile.object().contentClass());
        }
        LinkedHashMap<Hash256, ContentClass> dependencies = new LinkedHashMap<>();
        LinkedHashMap<NeighborKey, ContentClass> neighborDependencies = new LinkedHashMap<>();
        ArrayList<LayerMetadata> metadata = new ArrayList<>();
        for (ContentLayer layer : binding.layers()) {
            if (!selectedClasses.contains(layer.contentClass())) continue;
            for (Hash256 dependency : layer.dependencies()) {
                ContentClass previous = dependencies.putIfAbsent(
                        dependency, layer.contentClass());
                if (previous != null && previous != layer.contentClass()) {
                    throw new IllegalArgumentException("dependency changes content class");
                }
            }
            for (NeighborDependency dependency : layer.neighborDependencies()) {
                if ((effectiveMask(layer.contentClass(), effectiveExterior, effectiveInterior,
                        effectiveComplex) & 1L << dependency.sourceMicrotileIndex()) == 0) {
                    continue;
                }
                NeighborKey key = new NeighborKey(dependency.sourceMicrotileIndex(),
                        dependency.face(), dependency.hash());
                // Every manifest neighbor is deliberately a ComplexMicrotile.  It is boundary
                // context for the selected source class, not another object of that class.
                neighborDependencies.putIfAbsent(key, ContentClass.COMPLEX);
            }
            metadata.add(new LayerMetadata(layer.contentClass(), layer.boundarySummary(),
                    layer.exteriorVisibilityMask(), layer.unknownVisibilityMask(),
                    layer.visibilityMemberships()));
        }
        LinkedHashSet<Hash256> missingDependencies = new LinkedHashSet<>();
        LinkedHashSet<Hash256> missingNeighborDependencies = new LinkedHashSet<>();
        ArrayList<DependencyMicrotile> preparedDependencies = new ArrayList<>();
        ArrayList<NeighborDependencyMicrotile> preparedNeighbors = new ArrayList<>();
        for (Map.Entry<Hash256, ContentClass> dependency : dependencies.entrySet()) {
            Optional<DependencyMicrotile> prepared = dependencyLookup.load(
                    dependency.getKey(), dependency.getValue());
            if (prepared.isEmpty()) missingDependencies.add(dependency.getKey());
            else preparedDependencies.add(prepared.orElseThrow());
        }
        for (Map.Entry<NeighborKey, ContentClass> dependency : neighborDependencies.entrySet()) {
            Optional<DependencyMicrotile> prepared = dependencyLookup.load(
                    dependency.getKey().hash(), dependency.getValue());
            if (prepared.isEmpty()) {
                missingNeighborDependencies.add(dependency.getKey().hash());
            } else {
                DependencyMicrotile value = prepared.orElseThrow();
                preparedNeighbors.add(new NeighborDependencyMicrotile(
                        dependency.getKey().sourceMicrotileIndex(),
                        dependency.getKey().face(), value.hash(),
                        value.contentClass(), value.content()));
            }
        }
        if (!missingDependencies.isEmpty() || !missingNeighborDependencies.isEmpty()) {
            throw new MissingObjectsException(List.of(), missingDependencies,
                    missingNeighborDependencies);
        }
        for (PreparedMicrotile microtile : selected) {
            int pendingModel = firstUnreadyModel(microtile.content(), compatibility);
            if (pendingModel >= 0) throw new ModelsNotReadyException(pendingModel);
        }
        for (DependencyMicrotile dependency : preparedDependencies) {
            int pendingModel = firstUnreadyModel(dependency.content(), compatibility);
            if (pendingModel >= 0) throw new ModelsNotReadyException(pendingModel);
        }
        for (NeighborDependencyMicrotile dependency : preparedNeighbors) {
            int pendingModel = firstUnreadyModel(dependency.content(), compatibility);
            if (pendingModel >= 0) throw new ModelsNotReadyException(pendingModel);
        }

        SpatialNode node = RootDemandPlan.spatial(binding.sectionKey());
        SelectionCut effectiveCut = new SelectionCut(
                effectiveExterior, effectiveInterior, effectiveComplex);
        Hash256 terrainIdentity = terrainIdentity(selected, dependencies.keySet(),
                preparedNeighbors);
        return new ActivationGroup(root, node, binding.childMask(), binding.bounds(),
                binding.geometricErrorQ16(), terrainIdentity, rendererIdentity, effectiveCut, selected,
                preparedDependencies, preparedNeighbors, metadata);
    }

    private static ContentObject[][] indexedObjects(Binding binding) {
        ContentObject[][] result = new ContentObject[ContentClass.values().length][Long.SIZE];
        for (ContentLayer layer : binding.layers()) {
            ContentObject[] indexed = result[layer.contentClass().ordinal()];
            for (ContentObject object : layer.objects()) {
                indexed[object.microtileIndex()] = object;
            }
        }
        return result;
    }

    private static long mask(ContentObject[] objects) {
        long result = 0;
        for (int index = 0; index < objects.length; index++) {
            if (objects[index] != null) result |= 1L << index;
        }
        return result;
    }

    private static PreparedMicrotile prepared(ContentObject object,
                                               MicrotileCodec.Prepared content) {
        return new PreparedMicrotile(object, content,
                object.contentClass() == ContentClass.COMPLEX
                        ? MeshingPath.CPU_COMPLEX : MeshingPath.GPU_OPAQUE_TEMPLATE);
    }

    @FunctionalInterface
    private interface PreparedLookup {
        Optional<PreparedMicrotile> load(ContentObject object)
                throws MicrotileCodec.DecodeException;
    }

    @FunctionalInterface
    private interface DependencyLookup {
        Optional<DependencyMicrotile> load(Hash256 hash, ContentClass contentClass)
                throws MicrotileCodec.DecodeException;
    }

    private record NeighborKey(int sourceMicrotileIndex, NeighborFace face, Hash256 hash) {
        private NeighborKey {
            if (sourceMicrotileIndex < 0 || sourceMicrotileIndex >= Long.SIZE) {
                throw new IllegalArgumentException("neighbor source is outside fixed content");
            }
            Objects.requireNonNull(face, "face");
            Objects.requireNonNull(hash, "hash");
        }
    }

    private static long effectiveMask(ContentClass contentClass, long exterior,
                                      long interior, long complex) {
        return switch (contentClass) {
            case EXTERIOR -> exterior;
            case INTERIOR -> interior;
            case COMPLEX -> complex;
        };
    }

    private static boolean gpuSafe(MicrotileCodec.Prepared microtile,
                                   ModelCompatibility compatibility) {
        for (long cell : microtile.cellsInternal()) {
            if (CatalogMapper.isAir(cell)) continue;
            ModelClass modelClass = Objects.requireNonNull(
                    compatibility.classify(CatalogMapper.getBlockId(cell)), "model classification");
            if (!modelClass.gpuSafe()) return false;
        }
        return true;
    }

    private static boolean modelsReady(MicrotileCodec.Prepared microtile,
                                       ModelCompatibility compatibility) {
        return firstUnreadyModel(microtile, compatibility) < 0;
    }

    private static int firstUnreadyModel(MicrotileCodec.Prepared microtile,
                                         ModelCompatibility compatibility) {
        for (long cell : microtile.cellsInternal()) {
            int block = CatalogMapper.getBlockId(cell);
            if (!CatalogMapper.isAir(cell) && !compatibility.ready(block)) return block;
        }
        return -1;
    }

    private static Hash256 terrainIdentity(List<PreparedMicrotile> selected,
                                           Collection<Hash256> dependencies,
                                           Collection<NeighborDependencyMicrotile>
                                                   neighborDependencies) {
        Blake3.Hasher hasher = new Blake3.Hasher().update(TERRAIN_IDENTITY_DOMAIN);
        ByteBuffer scalar = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        scalar.putInt(selected.size()).putInt(dependencies.size() + neighborDependencies.size());
        hasher.update(scalar.array());
        for (PreparedMicrotile microtile : selected) {
            hasher.update(new byte[]{(byte) microtile.object().contentClass().ordinal(),
                    (byte) microtile.object().microtileIndex()});
            hasher.update(microtile.object().hash().toBytes());
        }
        for (Hash256 dependency : dependencies) hasher.update(dependency.toBytes());
        for (NeighborDependencyMicrotile dependency : neighborDependencies) {
            hasher.update(new byte[]{(byte) dependency.sourceMicrotileIndex(),
                    (byte) dependency.face().wireId()});
            hasher.update(dependency.hash().toBytes());
        }
        return Hash256.fromBytes(hasher.digest());
    }

    private static List<DependencyMicrotile> uniqueDependencies(
            Collection<DependencyMicrotile> dependencies, String label) {
        Objects.requireNonNull(dependencies, label);
        LinkedHashSet<Hash256> hashes = new LinkedHashSet<>();
        ArrayList<DependencyMicrotile> result = new ArrayList<>(dependencies.size());
        for (DependencyMicrotile dependency : dependencies) {
            DependencyMicrotile value = Objects.requireNonNull(dependency, label + " entry");
            if (!hashes.add(value.hash())) {
                throw new IllegalArgumentException(label + " contains duplicate hashes");
            }
            result.add(value);
        }
        return List.copyOf(result);
    }

    private static List<NeighborDependencyMicrotile> uniqueNeighborDependencies(
            Collection<NeighborDependencyMicrotile> dependencies, String label) {
        Objects.requireNonNull(dependencies, label);
        LinkedHashSet<NeighborKey> keys = new LinkedHashSet<>();
        ArrayList<NeighborDependencyMicrotile> result = new ArrayList<>(dependencies.size());
        for (NeighborDependencyMicrotile dependency : dependencies) {
            NeighborDependencyMicrotile value = Objects.requireNonNull(
                    dependency, label + " entry");
            if (!keys.add(new NeighborKey(value.sourceMicrotileIndex(),
                    value.face(), value.hash()))) {
                throw new IllegalArgumentException(
                        label + " contains duplicate source/face/hash entries");
            }
            result.add(value);
        }
        return List.copyOf(result);
    }

    private static boolean isAdjacentMicrotile(
            int sourceMicrotileIndex, NeighborFace face,
            MicrotileCodec.Metadata metadata) {
        int[] source = inverseMorton2(sourceMicrotileIndex);
        switch (face) {
            case NEGATIVE_X -> source[0]--;
            case POSITIVE_X -> source[0]++;
            case NEGATIVE_Y -> source[1]--;
            case POSITIVE_Y -> source[1]++;
            case NEGATIVE_Z -> source[2]--;
            case POSITIVE_Z -> source[2]++;
        }
        return Math.floorMod(source[0], 4) * MicrotileCodec.EDGE == metadata.originX()
                && Math.floorMod(source[1], 4) * MicrotileCodec.EDGE == metadata.originY()
                && Math.floorMod(source[2], 4) * MicrotileCodec.EDGE == metadata.originZ();
    }

    private static int[] inverseMorton2(int index) {
        int high = index >>> 3;
        int low = index & 7;
        return new int[]{
                (high & 1) << 1 | low & 1,
                (high >>> 1 & 1) << 1 | low >>> 1 & 1,
                (high >>> 2 & 1) << 1 | low >>> 2 & 1
        };
    }

    public static final class MissingObjectsException extends Exception {
        private final List<Hash256> content;
        private final List<Hash256> dependencies;
        private final List<Hash256> neighborDependencies;

        public MissingObjectsException(Collection<Hash256> content,
                                       Collection<Hash256> dependencies,
                                       Collection<Hash256> neighborDependencies) {
            super("activation group is missing " + distinctCount(content, dependencies,
                    neighborDependencies) + " authenticated objects");
            this.content = unique(content, "missing content");
            this.dependencies = unique(dependencies, "missing dependencies");
            this.neighborDependencies = unique(neighborDependencies,
                    "missing neighbor dependencies");
        }

        /** Content and general dependencies retain their existing CPU retry behavior. */
        public List<Hash256> requestable() {
            LinkedHashSet<Hash256> result = new LinkedHashSet<>(this.content);
            result.addAll(this.dependencies);
            return List.copyOf(result);
        }

        /** Neighbor demand must return through an exact GPU selection-row handoff. */
        public List<Hash256> neighborDependencies() {
            return this.neighborDependencies;
        }

        private static int distinctCount(Collection<Hash256> content,
                                         Collection<Hash256> dependencies,
                                         Collection<Hash256> neighborDependencies) {
            LinkedHashSet<Hash256> result = new LinkedHashSet<>(Objects.requireNonNull(
                    content, "missing content"));
            result.addAll(Objects.requireNonNull(dependencies, "missing dependencies"));
            result.addAll(Objects.requireNonNull(neighborDependencies,
                    "missing neighbor dependencies"));
            return result.size();
        }

        private static List<Hash256> unique(Collection<Hash256> hashes, String name) {
            LinkedHashSet<Hash256> result = new LinkedHashSet<>();
            for (Hash256 hash : Objects.requireNonNull(hashes, name)) {
                result.add(Objects.requireNonNull(hash, name + " hash"));
            }
            return List.copyOf(result);
        }
    }

    /** The terrain is resident, but one of its actual local block models is still baking. */
    public static final class ModelsNotReadyException extends Exception {
        private final int localBlockId;

        public ModelsNotReadyException(int localBlockId) {
            super("activation group is waiting for local block model " + localBlockId);
            if (localBlockId < 0) throw new IllegalArgumentException("negative block model ID");
            this.localBlockId = localBlockId;
        }

        public int localBlockId() { return this.localBlockId; }
    }

    public static final class IncompatibleContentException extends IllegalArgumentException {
        public IncompatibleContentException(String message) {
            super(message);
        }
    }
}
