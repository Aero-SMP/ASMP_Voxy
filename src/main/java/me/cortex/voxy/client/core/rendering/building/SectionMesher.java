package me.cortex.voxy.client.core.rendering.building;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import me.cortex.voxy.client.core.model.CatalogMapper;
import me.cortex.voxy.client.core.model.ModelBakerySubsystem;
import me.cortex.voxy.client.core.model.ModelFactory;
import me.cortex.voxy.client.core.model.ModelQueries;
import me.cortex.voxy.client.lod.RegionalSectionCodec;
import me.cortex.voxy.common.util.MemoryBuffer;
import org.lwjgl.system.MemoryUtil;

import java.util.Arrays;
import java.util.Objects;

/** Compact greedy mesher for the regional 32-cubed section representation. */
public final class SectionMesher {
    private static final int EDGE = 32;
    private static final int CELLS = EDGE * EDGE * EDGE;
    private static final int PLANE = EDGE * EDGE;
    private static final int MAX_QUAD_EDGE = 16;
    private static final int BUCKETS = 8;

    private final ModelBakerySubsystem bakery;
    private final ModelFactory models;
    private final ThreadLocal<Workspace> workspaces = ThreadLocal.withInitial(Workspace::new);

    public SectionMesher(ModelBakerySubsystem bakery) {
        this.bakery = Objects.requireNonNull(bakery, "bakery");
        this.models = bakery.factory;
    }

    public void requestModels(RegionalSectionCodec.SectionData section) {
        for (int block : section.usedBlocks()) this.bakery.requestBlockBake(block);
    }

    public boolean modelsReady(RegionalSectionCodec.SectionData section) {
        for (int block : section.usedBlocks()) {
            if (!this.models.isModelReadyForBlockId(block)) return false;
        }
        return true;
    }

    public BuiltSection mesh(RegionalSectionCodec.SectionData section, long sourceRevision) {
        Objects.requireNonNull(section, "section");
        Workspace workspace = this.workspaces.get();
        workspace.reset();
        long[] cells = section.cells();
        boolean fluidOverlays = prepare(cells, workspace);

        for (int face = 0; face < 6; face++) {
            for (int depth = 0; depth < EDGE; depth++) {
                fillPlane(cells, workspace, face, depth, false);
                mergePlane(workspace, face, depth);
                if (fluidOverlays) {
                    fillPlane(cells, workspace, face, depth, true);
                    mergePlane(workspace, face, depth);
                }
            }
        }

        int total = 0;
        for (LongArrayList bucket : workspace.buckets) {
            total = Math.addExact(total, bucket.size());
        }
        if (total == 0) {
            return BuiltSection.emptyWithChildren(section.key(), sourceRevision,
                    (byte) section.childMask());
        }
        MemoryBuffer geometry = new MemoryBuffer((long) total * Long.BYTES);
        try {
            int[] offsets = new int[BUCKETS];
            int cursor = 0;
            for (int bucket = 0; bucket < BUCKETS; bucket++) {
                offsets[bucket] = cursor;
                LongArrayList values = workspace.buckets[bucket];
                for (int index = 0; index < values.size(); index++) {
                    MemoryUtil.memPutLong(geometry.address + (long) cursor++ * Long.BYTES,
                            values.getLong(index));
                }
            }
            BuiltSection result = new BuiltSection(section.key(), sourceRevision,
                    (byte) section.childMask(), aabb(cells), geometry, offsets);
            geometry = null;
            return result;
        } finally {
            if (geometry != null) geometry.free();
        }
    }

    private boolean prepare(long[] cells, Workspace workspace) {
        boolean overlays = false;
        for (int index = 0; index < CELLS; index++) {
            long cell = cells[index];
            if (CatalogMapper.isAir(cell)) {
                workspace.modelIds[index] = 0;
                workspace.metadata[index] = 0;
                continue;
            }
            int model = this.models.getModelId(CatalogMapper.getBlockId(cell));
            long metadata = this.models.getModelMetadataFromClientId(model);
            workspace.modelIds[index] = model;
            workspace.metadata[index] = metadata;
            overlays |= ModelQueries.containsFluid(metadata) && !ModelQueries.isFluid(metadata);
        }
        return overlays;
    }

    private void fillPlane(long[] cells, Workspace workspace, int face, int depth,
                           boolean fluidLayer) {
        for (int v = 0; v < EDGE; v++) {
            for (int u = 0; u < EDGE; u++) {
                int cell = cellIndex(face >>> 1, depth, u, v);
                workspace.plane[u + v * EDGE] = faceData(cell, face, cells,
                        workspace.modelIds, workspace.metadata, fluidLayer);
            }
        }
    }

    private long faceData(int cell, int face, long[] cells, int[] modelIds,
                          long[] metadata, boolean fluidLayer) {
        int model = modelIds[cell];
        long own = metadata[cell];
        if (fluidLayer) {
            if (!ModelQueries.containsFluid(own) || ModelQueries.isFluid(own)) return 0;
            model = this.models.getFluidClientStateId(model);
            own = this.models.getModelMetadataFromClientId(model);
        }
        if (model == 0 || !ModelQueries.faceExists(own, face)) return 0;

        int neighbor = neighbor(cell, face);
        int neighborModel = 0;
        long neighborMetadata = 0;
        int neighborLight = CatalogMapper.getLightId(cells[cell]);
        if (neighbor >= 0) {
            long neighborCell = cells[neighbor];
            neighborLight = CatalogMapper.getLightId(neighborCell);
            if (!CatalogMapper.isAir(neighborCell)) {
                neighborModel = modelIds[neighbor];
                neighborMetadata = metadata[neighbor];
                if (fluidLayer) {
                    if (ModelQueries.isFluid(neighborMetadata)) {
                        // The neighboring model is already the fluid surface.
                    } else if (ModelQueries.containsFluid(neighborMetadata)) {
                        neighborModel = this.models.getFluidClientStateId(neighborModel);
                        neighborMetadata = this.models.getModelMetadataFromClientId(neighborModel);
                    } else {
                        neighborModel = 0;
                        neighborMetadata = 0;
                    }
                }
            }
        }
        if (neighborModel == model && ModelQueries.cullsSame(own)) return 0;
        if (ModelQueries.faceCanBeOccluded(own, face)
                && ModelQueries.faceOccludes(neighborMetadata, face ^ 1)) return 0;

        int light = ModelQueries.faceUsesSelfLighting(own, face)
                ? CatalogMapper.getLightId(cells[cell]) : neighborLight;
        if (neighbor < 0) light |= 0x0f;
        light = (light & 15) | Math.max(light >>> 4,
                (int) ModelQueries.lightEmission(own)) << 4;
        int biome = ModelQueries.isBiomeColoured(own)
                ? CatalogMapper.getBiomeId(cells[cell]) : 0;
        return (long) model << 26 | (long) biome << 46 | (long) light << 55;
    }

    private void mergePlane(Workspace workspace, int face, int depth) {
        long[] plane = workspace.plane;
        for (int v = 0; v < EDGE; v++) {
            for (int u = 0; u < EDGE; u++) {
                int origin = u + v * EDGE;
                long data = plane[origin];
                if (data == 0) continue;
                int width = 1;
                while (width < MAX_QUAD_EDGE && u + width < EDGE
                        && plane[origin + width] == data) width++;
                int height = 1;
                heightLoop: while (height < MAX_QUAD_EDGE && v + height < EDGE) {
                    int row = origin + height * EDGE;
                    for (int offset = 0; offset < width; offset++) {
                        if (plane[row + offset] != data) break heightLoop;
                    }
                    height++;
                }
                for (int row = 0; row < height; row++) {
                    Arrays.fill(plane, origin + row * EDGE,
                            origin + row * EDGE + width, 0);
                }
                int model = (int) (data >>> 26) & 0xfffff;
                long metadata = this.models.getModelMetadataFromClientId(model);
                int bucket = ModelQueries._isTranslucent(metadata) != 0 ? 0
                        : ModelQueries._isDoubleSided(metadata) != 0 ? 1 : face + 2;
                workspace.buckets[bucket].add(data
                        | Integer.toUnsignedLong(position(face, depth, u, v, width, height)));
            }
        }
    }

    private static int cellIndex(int axis, int depth, int u, int v) {
        return switch (axis) {
            case 0 -> u | v << 5 | depth << 10;
            case 1 -> u | depth << 5 | v << 10;
            case 2 -> depth | v << 5 | u << 10;
            default -> throw new IllegalArgumentException("invalid face axis");
        };
    }

    private static int position(int face, int depth, int u, int v, int width, int height) {
        int axis = face >>> 1;
        int x = axis == 2 ? depth : u;
        int y = axis == 0 ? depth : axis == 1 ? v : u;
        int z = axis == 0 ? v : axis == 1 ? depth : v;
        return face | (width - 1) << 3 | (height - 1) << 7
                | x << 21 | y << 16 | z << 11;
    }

    private static int neighbor(int index, int face) {
        int x = index & 31, z = index >>> 5 & 31, y = index >>> 10;
        return switch (face) {
            case 0 -> y == 0 ? -1 : index - 1024;
            case 1 -> y == 31 ? -1 : index + 1024;
            case 2 -> z == 0 ? -1 : index - 32;
            case 3 -> z == 31 ? -1 : index + 32;
            case 4 -> x == 0 ? -1 : index - 1;
            case 5 -> x == 31 ? -1 : index + 1;
            default -> throw new IllegalArgumentException("invalid face");
        };
    }

    private static int aabb(long[] cells) {
        int minX = 32, minY = 32, minZ = 32, maxX = 0, maxY = 0, maxZ = 0;
        for (int index = 0; index < CELLS; index++) {
            if (CatalogMapper.isAir(cells[index])) continue;
            int x = index & 31, z = index >>> 5 & 31, y = index >>> 10;
            minX = Math.min(minX, x); minY = Math.min(minY, y); minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x + 1); maxY = Math.max(maxY, y + 1);
            maxZ = Math.max(maxZ, z + 1);
        }
        if (minX == 32) throw new IllegalStateException("geometry emitted for an empty section");
        return minX | minY << 5 | minZ << 10 | (maxX - minX - 1) << 15
                | (maxY - minY - 1) << 20 | (maxZ - minZ - 1) << 25;
    }

    private static final class Workspace {
        final int[] modelIds = new int[CELLS];
        final long[] metadata = new long[CELLS];
        final long[] plane = new long[PLANE];
        final LongArrayList[] buckets = new LongArrayList[BUCKETS];

        Workspace() {
            for (int index = 0; index < BUCKETS; index++) {
                this.buckets[index] = new LongArrayList();
            }
        }

        void reset() {
            for (LongArrayList bucket : this.buckets) bucket.clear();
        }
    }
}
