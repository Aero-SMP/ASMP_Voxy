package me.cortex.voxy.client.lod;

import me.cortex.voxy.client.core.rendering.selection.SelectionManifest;
import me.cortex.voxy.client.lod.ContentPipeline.PreparedMicrotile;
import me.cortex.voxy.client.lod.ContentPipeline.CompatibilityState;
import me.cortex.voxy.client.lod.ManifestCodec.ContentClass;
import me.cortex.voxy.client.lod.ManifestCodec.ContentDescriptor;
import me.cortex.voxy.client.lod.ManifestCodec.ManifestNode;
import me.cortex.voxy.client.lod.ManifestCodec.NeighborDependency;
import me.cortex.voxy.client.lod.ManifestCodec.QuantizedBounds;
import me.cortex.voxy.client.lod.ManifestCodec.SpatialNode;
import me.cortex.voxy.client.lod.RootDemandPlan.ManifestView;
import me.cortex.voxy.client.lod.RootDemandPlan.NodeView;
import me.cortex.voxy.client.lod.RootDemandPlan.ObjectView;
import me.cortex.voxy.client.lod.WireMessage.Hash256;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Builds the immutable GPU selection snapshot directly from authenticated manifests. */
public final class SelectionManifestBuilder {
    private static final long CANONICAL_BYTES_PER_MICROTILE = 8L << 10;
    private static final long GEOMETRY_BYTES_PER_MICROTILE = 24L << 10;

    private SelectionManifestBuilder() {}

    public static SelectionManifest build(ManifestView view,
                                          ResidencyManager residency,
                                          MicrotileActivationManager activations,
                                          long snapshotId,
                                          long cameraVisibilityDomain,
                                          Map<SpatialNode, CompatibilityState> compatibility) {
        Objects.requireNonNull(view, "view");
        Objects.requireNonNull(residency, "residency");
        Objects.requireNonNull(activations, "activations");
        Objects.requireNonNull(compatibility, "compatibility");
        Map<SpatialNode, Integer> nodeHandles = new HashMap<>(view.nodes().size());
        Map<Hash256, ObjectView> objects = new HashMap<>(view.objects().size());
        for (NodeView node : view.nodes()) nodeHandles.put(node.spatial(), node.handle());
        for (ObjectView object : view.objects()) objects.put(object.hash(), object);

        ArrayList<SelectionManifest.Node> nodes = new ArrayList<>(view.nodes().size());
        for (NodeView node : view.nodes()) {
            SpatialNode spatial = node.spatial();
            ManifestNode manifest = node.manifestNode();
            int parent = parentHandle(spatial, nodeHandles);
            int[] children = childHandles(spatial, manifest.childMask(), nodeHandles);
            SelectionManifest.TightBounds bounds = manifest.bounds()
                    .map(SelectionManifestBuilder::bounds)
                    .orElseGet(() -> new SelectionManifest.TightBounds(
                            0, 0, 0, 0xffff, 0xffff, 0xffff));
            Map<ContentClass, Long> rendered = renderableMasks(spatial, activations);
            long ordinary = mask(manifest, ContentClass.EXTERIOR)
                    | mask(manifest, ContentClass.INTERIOR);
            CompatibilityState modelState = compatibility.get(spatial);
            long exteriorAvailable = modelState == null
                    ? mask(manifest, ContentClass.EXTERIOR)
                    : modelState.exteriorAvailableMask();
            long interiorAvailable = modelState == null
                    ? mask(manifest, ContentClass.INTERIOR)
                    : modelState.interiorAvailableMask();
            long complexAvailable = modelState == null
                    ? mask(manifest, ContentClass.COMPLEX) & ~ordinary
                    : modelState.complexAvailableMask();
            exteriorAvailable &= eligibleMask(manifest, ContentClass.EXTERIOR,
                    cameraVisibilityDomain);
            interiorAvailable &= eligibleMask(manifest, ContentClass.INTERIOR,
                    cameraVisibilityDomain);
            complexAvailable &= eligibleMask(manifest, ContentClass.COMPLEX,
                    cameraVisibilityDomain);
            nodes.add(new SelectionManifest.Node(node.handle(),
                    RootDemandPlan.sectionKey(spatial), parent, manifest.childMask(), children, bounds,
                    manifest.geometricErrorQ16(), !manifest.contents().isEmpty(),
                    content(manifest.contents().get(ContentClass.EXTERIOR),
                            ContentClass.EXTERIOR, view, residency, objects, rendered,
                            exteriorAvailable),
                    content(manifest.contents().get(ContentClass.INTERIOR),
                            ContentClass.INTERIOR, view, residency, objects, rendered,
                            interiorAvailable),
                    content(manifest.contents().get(ContentClass.COMPLEX),
                            ContentClass.COMPLEX, view, residency, objects, rendered,
                            complexAvailable)));
        }
        return new SelectionManifest(view.root().generation(), snapshotId,
                cameraVisibilityDomain, nodes);
    }

    private static SelectionManifest.ContentState content(
            ContentDescriptor descriptor, ContentClass contentClass, ManifestView view,
            ResidencyManager residency,
            Map<Hash256, ObjectView> objects, Map<ContentClass, Long> rendered,
            long availableMask) {
        if (descriptor == null || availableMask == 0) return emptyContent();
        int[] objectHandles = new int[Long.bitCount(availableMask)];
        long resident = 0;
        long inFlight = 0;
        int sourceDense = 0;
        int outputDense = 0;
        for (int microtile = 0; microtile < Long.SIZE; microtile++) {
            long bit = 1L << microtile;
            if ((descriptor.microtileMask() & bit) == 0) continue;
            Hash256 hash = descriptor.objects().get(sourceDense++);
            if ((availableMask & bit) == 0) continue;
            objectHandles[outputDense++] = requireHandle(view, hash);
            ObjectView object = objects.get(hash);
            if (residency.decodedMicrotile(hash).isPresent()) resident |= bit;
            if (object != null && object.inFlight()) inFlight |= bit;
        }
        long renderable = rendered.getOrDefault(contentClass, 0L);
        resident &= availableMask;
        inFlight &= availableMask;
        renderable &= resident & availableMask;

        int[] dependencies = handles(view, descriptor.dependencies());
        List<NeighborDependency> selectedSourceNeighbors = descriptor.neighborDependencies()
                .stream()
                .filter(dependency -> (availableMask
                        & 1L << dependency.sourceMicrotileIndex()) != 0)
                .toList();
        List<Hash256> neighborHashes = selectedSourceNeighbors.stream()
                .map(NeighborDependency::hash)
                .toList();
        int[] neighborDependencies = handles(view, neighborHashes);
        int[] neighborSources = selectedSourceNeighbors.stream()
                .mapToInt(NeighborDependency::sourceMicrotileIndex)
                .toArray();
        BitSet residentDependencies = residentStates(descriptor.dependencies(), residency);
        BitSet inFlightDependencies = inFlightStates(descriptor.dependencies(), objects);
        BitSet residentNeighbors = residentStates(neighborHashes, residency);
        BitSet inFlightNeighbors = inFlightStates(neighborHashes, objects);
        long count = Long.bitCount(availableMask);
        return new SelectionManifest.ContentState(availableMask, objectHandles,
                resident, renderable, inFlight, dependencies, residentDependencies,
                inFlightDependencies, neighborDependencies, neighborSources, residentNeighbors,
                inFlightNeighbors, descriptor.boundarySummary().faceMask(),
                count * CANONICAL_BYTES_PER_MICROTILE,
                count * GEOMETRY_BYTES_PER_MICROTILE, 2_000);
    }

    private static long mask(ManifestNode node, ContentClass contentClass) {
        ContentDescriptor descriptor = node.contents().get(contentClass);
        return descriptor == null ? 0 : descriptor.microtileMask();
    }

    private static long eligibleMask(ManifestNode node, ContentClass contentClass,
                                     long cameraVisibilityDomain) {
        ContentDescriptor descriptor = node.contents().get(contentClass);
        return descriptor == null ? 0 : descriptor.eligibleMask(cameraVisibilityDomain);
    }

    private static SelectionManifest.ContentState emptyContent() {
        return new SelectionManifest.ContentState(0, new int[0], 0, 0, 0,
                new int[0], new BitSet(), new BitSet(), new int[0], new int[0], new BitSet(),
                new BitSet(), 0, 0, 0, 0);
    }

    private static Map<ContentClass, Long> renderableMasks(
            SpatialNode node, MicrotileActivationManager activations) {
        Map<ContentClass, Long> masks = new java.util.EnumMap<>(ContentClass.class);
        activations.active(node).ifPresent(active -> {
            if (!active.publication().activationFencePassed()) return;
            for (PreparedMicrotile tile : active.content().microtiles()) {
                masks.merge(tile.object().contentClass(),
                        1L << tile.object().microtileIndex(), (left, right) -> left | right);
            }
        });
        return masks;
    }

    private static int parentHandle(SpatialNode node, Map<SpatialNode, Integer> handles) {
        if (node.lod() == ManifestCodec.MAX_LOD) return SelectionManifest.NO_HANDLE;
        return handles.getOrDefault(new SpatialNode(node.lod() + 1,
                        Math.floorDiv(node.x(), 2), Math.floorDiv(node.y(), 2),
                        Math.floorDiv(node.z(), 2)), SelectionManifest.NO_HANDLE);
    }

    private static int[] childHandles(SpatialNode node, int childMask,
                                      Map<SpatialNode, Integer> handles) {
        int[] result = new int[8];
        java.util.Arrays.fill(result, SelectionManifest.NO_HANDLE);
        if (node.lod() == 0) return result;
        for (int child = 0; child < 8; child++) {
            if ((childMask & 1 << child) == 0) continue;
            SpatialNode spatial = new SpatialNode(node.lod() - 1,
                    node.x() * 2 + (child & 1), node.y() * 2 + (child >>> 1 & 1),
                    node.z() * 2 + (child >>> 2 & 1));
            result[child] = handles.getOrDefault(spatial, SelectionManifest.NO_HANDLE);
        }
        return result;
    }

    private static int[] handles(ManifestView view, List<Hash256> hashes) {
        int[] result = new int[hashes.size()];
        for (int index = 0; index < result.length; index++) {
            result[index] = requireHandle(view, hashes.get(index));
        }
        return result;
    }

    private static BitSet residentStates(List<Hash256> hashes,
                                         ResidencyManager residency) {
        BitSet result = new BitSet(hashes.size());
        for (int index = 0; index < hashes.size(); index++) {
            if (residency.decodedMicrotile(hashes.get(index)).isPresent()) result.set(index);
        }
        return result;
    }

    private static BitSet inFlightStates(List<Hash256> hashes,
                                         Map<Hash256, ObjectView> objects) {
        BitSet result = new BitSet(hashes.size());
        for (int index = 0; index < hashes.size(); index++) {
            ObjectView object = objects.get(hashes.get(index));
            if (object != null && object.inFlight()) result.set(index);
        }
        return result;
    }

    private static int requireHandle(ManifestView view, Hash256 hash) {
        Integer handle = view.objectHandles().get(hash);
        if (handle == null) throw new IllegalStateException("manifest object lacks a handle");
        return handle;
    }

    private static SelectionManifest.TightBounds bounds(QuantizedBounds bounds) {
        return new SelectionManifest.TightBounds(bounds.minX(), bounds.minY(), bounds.minZ(),
                bounds.maxX(), bounds.maxY(), bounds.maxZ());
    }

}
