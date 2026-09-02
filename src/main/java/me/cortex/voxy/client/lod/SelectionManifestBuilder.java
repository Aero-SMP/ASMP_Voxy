package me.cortex.voxy.client.lod;

import me.cortex.voxy.client.core.rendering.selection.SelectionManifest;
import me.cortex.voxy.client.core.rendering.selection.SelectionManifest.ContentLayout;
import me.cortex.voxy.client.core.rendering.selection.SelectionManifest.Node;
import me.cortex.voxy.client.lod.ContentPipeline.CompatibilityState;
import me.cortex.voxy.client.lod.ContentPipeline.PreparedMicrotile;
import me.cortex.voxy.client.lod.ManifestCodec.ContentClass;
import me.cortex.voxy.client.lod.ManifestCodec.ManifestNode;
import me.cortex.voxy.client.lod.ManifestCodec.NeighborDependency;
import me.cortex.voxy.client.lod.ManifestCodec.QuantizedBounds;
import me.cortex.voxy.client.lod.ManifestCodec.SpatialNode;
import me.cortex.voxy.client.lod.ManifestCodec.VisibilityMembership;
import me.cortex.voxy.client.lod.RootDemandPlan.Binding;
import me.cortex.voxy.client.lod.RootDemandPlan.ContentLayer;
import me.cortex.voxy.client.lod.RootDemandPlan.ContentObject;
import me.cortex.voxy.client.lod.WireMessage.Hash256;

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;

/** Publishes pooled primitive state over a topology shared until the plan namespace changes. */
public final class SelectionManifestBuilder {
    private static final long CANONICAL_BYTES_PER_MICROTILE = 8L << 10;
    private static final long GEOMETRY_BYTES_PER_MICROTILE = 24L << 10;
    private static final int POOL_SIZE = 4;
    private static final ContentLayout EMPTY_CONTENT = new ContentLayout(0, new int[0],
            new int[0], new int[0], new int[0], 0, 0, 0, 0, 0, 0);

    private final SelectionManifest.Pool pool = new SelectionManifest.Pool(POOL_SIZE);
    private final long[] renderableScratch = new long[3];
    private RootDemandPlan topologyPlan;
    private long topologyRevision = Long.MIN_VALUE;
    private SelectionManifest.Topology topology;
    private ContentLayer[] sources = new ContentLayer[0];
    private SpatialNode[] spatials = new SpatialNode[0];

    /** Returns null only while every bounded snapshot slot is still owned asynchronously. */
    public SelectionManifest build(RootDemandPlan plan,
                                   ResidencyManager residency,
                                   MicrotileActivationManager activations,
                                   long snapshotId, long authorityId, long planRevision,
                                   long selectionTopologyRevision,
                                   long cameraVisibilityDomain,
                                   Map<SpatialNode, CompatibilityState> compatibility) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(residency, "residency");
        Objects.requireNonNull(activations, "activations");
        Objects.requireNonNull(compatibility, "compatibility");
        if (this.topologyPlan != plan
                || this.topologyRevision != selectionTopologyRevision) {
            rebuildTopology(plan, selectionTopologyRevision);
        }
        SelectionManifest manifest = this.pool.acquire(this.topology,
                plan.root().root().generation(), snapshotId, authorityId, planRevision,
                cameraVisibilityDomain);
        if (manifest == null) return null;
        try {
            for (int nodeIndex = 0; nodeIndex < this.spatials.length; nodeIndex++) {
                SpatialNode spatial = this.spatials[nodeIndex];
                Arrays.fill(this.renderableScratch, 0L);
                activations.active(spatial).ifPresent(active -> {
                    if (!active.publication().activationFencePassed()) return;
                    for (PreparedMicrotile tile : active.content().microtiles()) {
                        int content = switch (tile.object().contentClass()) {
                            case EXTERIOR -> 0;
                            case INTERIOR -> 1;
                            case COMPLEX -> 2;
                        };
                        this.renderableScratch[content] |= 1L << tile.object().microtileIndex();
                    }
                });
                CompatibilityState modelState = compatibility.get(spatial);
                long exteriorRaw = sourceMask(nodeIndex, 0);
                long interiorRaw = sourceMask(nodeIndex, 1);
                long complexRaw = sourceMask(nodeIndex, 2);
                long ordinaryRaw = exteriorRaw | interiorRaw;
                for (int content = 0; content < 3; content++) {
                    ContentLayer source = this.sources[nodeIndex * 3 + content];
                    var contentClass = selectionClass(content);
                    if (source == null) {
                        manifest.setContentState(nodeIndex, contentClass, 0, 0, 0, 0,
                                0, 0);
                        continue;
                    }
                    long coverageAvailable = switch (content) {
                        case 0 -> modelState == null
                                ? exteriorRaw : modelState.exteriorAvailableMask();
                        case 1 -> modelState == null
                                ? interiorRaw : modelState.interiorAvailableMask();
                        case 2 -> modelState == null
                                ? complexRaw & ~ordinaryRaw : modelState.complexAvailableMask();
                        default -> throw new AssertionError();
                    };
                    long available = coverageAvailable
                            & eligibleMask(source, cameraVisibilityDomain);
                    long coverageResident = 0;
                    long inFlight = 0;
                    for (ContentObject object : source.objects()) {
                        long bit = 1L << object.microtileIndex();
                        if ((coverageAvailable & bit) == 0) continue;
                        if (residency.hasPreparedMicrotile(object.hash())) coverageResident |= bit;
                        if ((available & bit) != 0
                                && plan.selectionObjectInFlight(object.hash())) inFlight |= bit;
                    }
                    long resident = coverageResident & available;
                    long renderable = this.renderableScratch[content] & resident & available;
                    long coverageRenderable = this.renderableScratch[content]
                            & coverageResident & coverageAvailable;
                    manifest.setContentState(nodeIndex, contentClass, available, resident,
                            renderable, inFlight, coverageAvailable, coverageRenderable);
                    for (int index = 0; index < source.dependencies().size(); index++) {
                        Hash256 hash = source.dependencies().get(index);
                        manifest.setDependencyState(nodeIndex, contentClass, index,
                                residency.hasPreparedMicrotile(hash),
                                plan.selectionObjectInFlight(hash));
                    }
                    for (int index = 0; index < source.neighborDependencies().size(); index++) {
                        Hash256 hash = source.neighborDependencies().get(index).hash();
                        manifest.setNeighborState(nodeIndex, contentClass, index,
                                residency.hasPreparedMicrotile(hash),
                                plan.selectionObjectInFlight(hash));
                    }
                }
            }
            return manifest.seal();
        } catch (RuntimeException | Error failure) {
            manifest.close();
            throw failure;
        }
    }

    private void rebuildTopology(RootDemandPlan plan, long planRevision) {
        int nodeCount = plan.selectionNodeCount();
        Node[] nodes = new Node[nodeCount];
        ContentLayer[] nextSources = new ContentLayer[Math.multiplyExact(nodeCount, 3)];
        SpatialNode[] nextSpatials = new SpatialNode[nodeCount];
        int dependencyOffset = 0;
        int neighborOffset = 0;
        for (int handle = 0; handle < nodeCount; handle++) {
            SpatialNode spatial = plan.selectionSpatial(handle);
            ManifestNode structural = plan.selectionStructuralNode(handle);
            Binding binding = plan.selectionBinding(handle);
            nextSpatials[handle] = spatial;
            int parent = parentHandle(plan, spatial);
            int[] children = childHandles(plan, spatial, structural.childMask());
            SelectionManifest.TightBounds bounds = structural.bounds()
                    .map(SelectionManifestBuilder::bounds)
                    .orElseGet(() -> new SelectionManifest.TightBounds(
                            0, 0, 0, 0xffff, 0xffff, 0xffff));
            ContentLayout[] layouts = new ContentLayout[3];
            for (int content = 0; content < 3; content++) {
                ContentLayer source = layer(binding, manifestClass(content));
                nextSources[handle * 3 + content] = source;
                if (source == null) {
                    layouts[content] = EMPTY_CONTENT;
                    continue;
                }
                int[] objects = new int[source.objects().size()];
                for (int index = 0; index < objects.length; index++) {
                    objects[index] = plan.selectionObjectHandle(source.objects().get(index).hash());
                }
                int[] dependencies = handles(plan, source.dependencies());
                int[] neighbors = new int[source.neighborDependencies().size()];
                int[] neighborSources = new int[neighbors.length];
                for (int index = 0; index < neighbors.length; index++) {
                    NeighborDependency dependency = source.neighborDependencies().get(index);
                    neighbors[index] = plan.selectionObjectHandle(dependency.hash());
                    neighborSources[index] = dependency.sourceMicrotileIndex();
                }
                long count = Long.bitCount(source.microtileMask());
                layouts[content] = new ContentLayout(source.microtileMask(), objects,
                        dependencies, neighbors, neighborSources, dependencyOffset,
                        neighborOffset, source.boundarySummary().faceMask(),
                        count * CANONICAL_BYTES_PER_MICROTILE,
                        count * GEOMETRY_BYTES_PER_MICROTILE, 2_000);
                dependencyOffset = Math.addExact(dependencyOffset, dependencies.length);
                neighborOffset = Math.addExact(neighborOffset, neighbors.length);
            }
            nodes[handle] = new Node(handle, RootDemandPlan.sectionKey(spatial), parent,
                    structural.childMask(), children, bounds, structural.geometricErrorQ16(),
                    binding != null, layouts[0], layouts[1], layouts[2]);
        }
        this.topology = new SelectionManifest.Topology(nodes, plan.objectHandleCount(),
                dependencyOffset, neighborOffset);
        this.sources = nextSources;
        this.spatials = nextSpatials;
        this.topologyPlan = plan;
        this.topologyRevision = planRevision;
    }

    private long sourceMask(int nodeIndex, int content) {
        ContentLayer source = this.sources[nodeIndex * 3 + content];
        return source == null ? 0 : source.microtileMask();
    }

    private static ContentLayer layer(Binding binding, ContentClass contentClass) {
        if (binding == null) return null;
        for (ContentLayer layer : binding.layers()) {
            if (layer.contentClass() == contentClass) return layer;
        }
        return null;
    }

    private static long eligibleMask(ContentLayer layer, long cameraDomain) {
        if (cameraDomain == 0) return layer.microtileMask();
        long eligible = layer.exteriorVisibilityMask() | layer.unknownVisibilityMask();
        for (VisibilityMembership membership : layer.visibilityMemberships()) {
            if (membership.domain() == cameraDomain) {
                eligible |= membership.microtileMask();
                break;
            }
        }
        return eligible;
    }

    private static int parentHandle(RootDemandPlan plan, SpatialNode node) {
        if (node.lod() == ManifestCodec.MAX_LOD) return SelectionManifest.NO_HANDLE;
        return plan.selectionHandle(new SpatialNode(node.lod() + 1,
                Math.floorDiv(node.x(), 2), Math.floorDiv(node.y(), 2),
                Math.floorDiv(node.z(), 2)));
    }

    private static int[] childHandles(RootDemandPlan plan, SpatialNode node, int childMask) {
        int[] result = new int[8];
        Arrays.fill(result, SelectionManifest.NO_HANDLE);
        if (node.lod() == 0) return result;
        for (int child = 0; child < 8; child++) {
            if ((childMask & 1 << child) == 0) continue;
            result[child] = plan.selectionHandle(new SpatialNode(node.lod() - 1,
                    node.x() * 2 + (child & 1), node.y() * 2 + (child >>> 1 & 1),
                    node.z() * 2 + (child >>> 2 & 1)));
        }
        return result;
    }

    private static int[] handles(RootDemandPlan plan, java.util.List<Hash256> hashes) {
        int[] result = new int[hashes.size()];
        for (int index = 0; index < result.length; index++) {
            result[index] = plan.selectionObjectHandle(hashes.get(index));
        }
        return result;
    }

    private static ContentClass manifestClass(int ordinal) {
        return switch (ordinal) {
            case 0 -> ContentClass.EXTERIOR;
            case 1 -> ContentClass.INTERIOR;
            case 2 -> ContentClass.COMPLEX;
            default -> throw new IllegalArgumentException("invalid content class");
        };
    }

    private static SelectionManifest.ContentClass selectionClass(int ordinal) {
        return switch (ordinal) {
            case 0 -> SelectionManifest.ContentClass.EXTERIOR;
            case 1 -> SelectionManifest.ContentClass.INTERIOR;
            case 2 -> SelectionManifest.ContentClass.COMPLEX;
            default -> throw new IllegalArgumentException("invalid content class");
        };
    }

    private static SelectionManifest.TightBounds bounds(QuantizedBounds bounds) {
        return new SelectionManifest.TightBounds(bounds.minX(), bounds.minY(), bounds.minZ(),
                bounds.maxX(), bounds.maxY(), bounds.maxZ());
    }
}
