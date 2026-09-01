package me.cortex.voxy.client.core.rendering.building;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import me.cortex.voxy.client.core.model.ModelFactory;
import me.cortex.voxy.client.core.model.ModelQueries;
import me.cortex.voxy.client.lod.ContentPipeline.MeshingPath;
import me.cortex.voxy.client.lod.RootDemandPlan;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.client.core.model.CatalogMapper;
import org.lwjgl.system.MemoryUtil;

import java.util.Objects;

/** Conservative CPU path for complex, fluid, translucent, and locally unknown microtiles. */
public final class CpuMicrotileMesher implements HybridMeshingDispatcher.Backend {
    private static final int BUCKET_COUNT = 8;
    private static final int MAX_BUCKET_QUADS = 0xffff;

    private final ModelFactory models;

    public CpuMicrotileMesher(ModelFactory models) {
        this.models = Objects.requireNonNull(models, "models");
    }

    @Override
    public BuiltSection mesh(HybridMeshingDispatcher.FragmentRequest request) {
        Objects.requireNonNull(request, "request");
        if (request.sectionPosition() != RootDemandPlan.sectionKey(request.activation().node())) {
            throw new IllegalArgumentException("CPU meshing request is bound to another node");
        }
        for (var microtile : request.microtiles()) {
            if (microtile.meshingPath() != MeshingPath.CPU_COMPLEX) {
                throw new IllegalArgumentException("GPU-safe microtile entered CPU fragment");
            }
        }

        MicrotileGeometry.Grid grid = MicrotileGeometry.assemble(request.activation());
        long[] cells = grid.cells();
        int[] modelIds = new int[MicrotileGeometry.SECTION_CELLS];
        long[] metadata = new long[MicrotileGeometry.SECTION_CELLS];
        for (int index = 0; index < cells.length; index++) {
            if (CatalogMapper.isAir(cells[index])) continue;
            int blockId = CatalogMapper.getBlockId(cells[index]);
            int modelId = this.models.getModelId(blockId);
            modelIds[index] = modelId;
            metadata[index] = this.models.getModelMetadataFromClientId(modelId);
        }

        LongArrayList[] buckets = new LongArrayList[BUCKET_COUNT];
        for (int bucket = 0; bucket < buckets.length; bucket++) {
            buckets[bucket] = new LongArrayList();
        }
        for (int index = 0; index < cells.length; index++) {
            if (grid.paths()[index] != MicrotileGeometry.CPU_PATH
                    || CatalogMapper.isAir(cells[index]) || modelIds[index] == 0) continue;
            this.emitModel(index, modelIds[index], metadata[index], grid, cells, modelIds,
                    metadata, false, buckets);
            if (ModelQueries.containsFluid(metadata[index])
                    && !ModelQueries.isFluid(metadata[index])) {
                int fluidModel = this.models.getFluidClientStateId(modelIds[index]);
                long fluidMetadata = this.models.getModelMetadataFromClientId(fluidModel);
                this.emitModel(index, fluidModel, fluidMetadata, grid, cells, modelIds,
                        metadata, true, buckets);
            }
        }

        int total = 0;
        for (LongArrayList bucket : buckets) {
            if (bucket.size() > MAX_BUCKET_QUADS) {
                throw new IllegalStateException(
                        "complex microtile geometry exceeds a renderer bucket bound");
            }
            total = Math.addExact(total, bucket.size());
        }
        byte children = (byte) request.activation().childMask();
        if (total == 0) {
            return BuiltSection.emptyWithChildren(request.sectionPosition(),
                    request.sourceRevision(), children);
        }

        MemoryBuffer geometry = new MemoryBuffer((long) total * Long.BYTES);
        try {
            int[] offsets = new int[BUCKET_COUNT];
            int cursor = 0;
            for (int bucket = 0; bucket < buckets.length; bucket++) {
                offsets[bucket] = cursor;
                LongArrayList quads = buckets[bucket];
                for (int index = 0; index < quads.size(); index++) {
                    MemoryUtil.memPutLong(geometry.address + (long) cursor++ * Long.BYTES,
                            quads.getLong(index));
                }
            }
            int aabb = MicrotileGeometry.emittedAabb(grid,
                    MicrotileGeometry.CPU_PATH);
            if (aabb == -1) throw new IllegalStateException("CPU emitted geometry without bounds");
            BuiltSection result = new BuiltSection(request.sectionPosition(),
                    request.sourceRevision(), children, aabb, geometry, offsets);
            geometry = null;
            return result;
        } finally {
            if (geometry != null) geometry.free();
        }
    }

    private void emitModel(int cell, int modelId, long ownMetadata,
                           MicrotileGeometry.Grid grid, long[] cells,
                           int[] modelIds, long[] metadata, boolean fluidLayer,
                           LongArrayList[] buckets) {
        for (int face = 0; face < 6; face++) {
            if (!ModelQueries.faceExists(ownMetadata, face)) continue;
            int neighbor = MicrotileGeometry.neighbor(cell, face);
            int neighborModel = 0;
            long neighborMetadata = 0;
            int neighborLight = CatalogMapper.getLightId(cells[cell]);
            boolean neighborKnown = neighbor >= 0
                    || MicrotileGeometry.neighborKnown(grid, cell, face);
            if (neighborKnown) {
                long neighborCell = MicrotileGeometry.neighborCell(grid, cell, face);
                neighborLight = CatalogMapper.getLightId(neighborCell);
                if (!CatalogMapper.isAir(neighborCell)) {
                    if (neighbor >= 0) {
                        neighborModel = modelIds[neighbor];
                        neighborMetadata = metadata[neighbor];
                    } else {
                        neighborModel = this.models.getModelId(CatalogMapper.getBlockId(neighborCell));
                        neighborMetadata = this.models.getModelMetadataFromClientId(
                                neighborModel);
                    }
                    if (fluidLayer) {
                        if (ModelQueries.isFluid(neighborMetadata)) {
                            // The base model is already the fluid model.
                        } else if (ModelQueries.containsFluid(neighborMetadata)) {
                            neighborModel = this.models.getFluidClientStateId(neighborModel);
                            neighborMetadata = this.models.getModelMetadataFromClientId(
                                    neighborModel);
                        } else {
                            neighborModel = 0;
                            neighborMetadata = 0;
                        }
                    }
                }
            }
            boolean sameCulled = neighborModel == modelId
                    && ModelQueries.cullsSame(ownMetadata);
            boolean occluded = ModelQueries.faceCanBeOccluded(ownMetadata, face)
                    && ModelQueries.faceOccludes(neighborMetadata, face ^ 1);
            if (sameCulled || occluded) continue;

            int ownLight = CatalogMapper.getLightId(cells[cell]);
            int light = ModelQueries.faceUsesSelfLighting(ownMetadata, face)
                    ? ownLight : neighborLight;
            int emission = (int) ModelQueries.lightEmission(ownMetadata);
            light = (light & 0x0f) | Math.max(light >>> 4, emission) << 4;
            int biome = ModelQueries.isBiomeColoured(ownMetadata)
                    ? CatalogMapper.getBiomeId(cells[cell]) : 0;
            int bucket = ModelQueries._isTranslucent(ownMetadata) != 0 ? 0
                    : ModelQueries._isDoubleSided(ownMetadata) != 0 ? 1 : face + 2;
            LongArrayList output = buckets[bucket];
            if (output.size() == MAX_BUCKET_QUADS) {
                throw new IllegalStateException(
                        "complex microtile geometry exceeds a renderer bucket bound");
            }
            output.add(MicrotileGeometry.encodeQuad(
                    cell, face, modelId, biome, light));
        }
    }
}
