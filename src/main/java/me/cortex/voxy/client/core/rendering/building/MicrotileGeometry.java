package me.cortex.voxy.client.core.rendering.building;

import me.cortex.voxy.client.lod.ContentPipeline.ActivationGroup;
import me.cortex.voxy.client.lod.ContentPipeline.MeshingPath;
import me.cortex.voxy.client.lod.ContentPipeline.NeighborDependencyMicrotile;
import me.cortex.voxy.client.lod.ContentPipeline.PreparedMicrotile;
import me.cortex.voxy.client.lod.ManifestCodec.NeighborFace;
import me.cortex.voxy.client.lod.MicrotileCodec;
import me.cortex.voxy.client.core.model.CatalogMapper;

import java.util.Objects;

/** Shared fixed-grid assembly and renderer-format helpers for the final microtile meshers. */
final class MicrotileGeometry {
    static final int SECTION_EDGE = 32;
    static final int SECTION_CELLS = SECTION_EDGE * SECTION_EDGE * SECTION_EDGE;
    static final int HALO_FACE_CELLS = SECTION_EDGE * SECTION_EDGE;
    static final int HALO_CELLS = 6 * HALO_FACE_CELLS;
    static final int INPUT_CELLS = SECTION_CELLS + HALO_CELLS;
    static final byte GPU_PATH = 1;
    static final byte CPU_PATH = 2;

    private MicrotileGeometry() {}

    static Grid assemble(ActivationGroup activation) {
        Objects.requireNonNull(activation, "activation");
        long[] cells = new long[SECTION_CELLS];
        byte[] paths = new byte[SECTION_CELLS];
        for (PreparedMicrotile microtile : activation.microtiles()) {
            byte path = microtile.meshingPath() == MeshingPath.GPU_OPAQUE_TEMPLATE
                    ? GPU_PATH : CPU_PATH;
            MicrotileCodec.Metadata metadata = microtile.content().metadata();
            long[] source = microtile.content().cells();
            for (int localY = 0; localY < MicrotileCodec.EDGE; localY++) {
                int y = metadata.originY() + localY;
                for (int localZ = 0; localZ < MicrotileCodec.EDGE; localZ++) {
                    int z = metadata.originZ() + localZ;
                    for (int localX = 0; localX < MicrotileCodec.EDGE; localX++) {
                        int x = metadata.originX() + localX;
                        int sourceIndex = localX | localZ << 3 | localY << 6;
                        int targetIndex = index(x, y, z);
                        long incoming = source[sourceIndex];
                        long existing = cells[targetIndex];
                        if (CatalogMapper.isAir(incoming)) {
                            if (CatalogMapper.isAir(existing)
                                    && CatalogMapper.getLightId(incoming) > CatalogMapper.getLightId(existing)) {
                                cells[targetIndex] = incoming;
                            }
                            continue;
                        }
                        if (!CatalogMapper.isAir(existing) && existing != incoming) {
                            throw new IllegalArgumentException(
                                    "selected microtiles overlap with different cell content");
                        }
                        cells[targetIndex] = incoming;
                        // Complex content owns an overlap.  This cannot normally occur after the
                        // effective cut, but keeping one owner prevents duplicate hybrid quads.
                        paths[targetIndex] = paths[targetIndex] == CPU_PATH || path == CPU_PATH
                                ? CPU_PATH : GPU_PATH;
                    }
                }
            }
        }
        long[] haloCells = new long[HALO_CELLS];
        boolean[] haloPresent = new boolean[HALO_CELLS];
        for (NeighborDependencyMicrotile dependency
                : activation.neighborDependencyMicrotiles()) {
            addNeighborContext(cells, haloCells, haloPresent, dependency);
        }
        return new Grid(cells, paths, haloCells, haloPresent);
    }

    private static void addNeighborContext(long[] cells, long[] haloCells,
                                           boolean[] haloPresent,
                                           NeighborDependencyMicrotile dependency) {
        int[] source = inverseMorton2(dependency.sourceMicrotileIndex());
        int axis = switch (dependency.face()) {
            case NEGATIVE_X, POSITIVE_X -> 0;
            case NEGATIVE_Y, POSITIVE_Y -> 1;
            case NEGATIVE_Z, POSITIVE_Z -> 2;
        };
        int direction = switch (dependency.face()) {
            case NEGATIVE_X, NEGATIVE_Y, NEGATIVE_Z -> -1;
            case POSITIVE_X, POSITIVE_Y, POSITIVE_Z -> 1;
        };
        int adjacent = source[axis] + direction;
        if (adjacent < 0 || adjacent >= 4) {
            addNeighborFace(haloCells, haloPresent, dependency);
            return;
        }
        MicrotileCodec.Metadata metadata = dependency.content().metadata();
        long[] context = dependency.content().cells();
        for (int localY = 0; localY < MicrotileCodec.EDGE; localY++) {
            int y = metadata.originY() + localY;
            for (int localZ = 0; localZ < MicrotileCodec.EDGE; localZ++) {
                int z = metadata.originZ() + localZ;
                for (int localX = 0; localX < MicrotileCodec.EDGE; localX++) {
                    int x = metadata.originX() + localX;
                    int sourceIndex = localX | localZ << 3 | localY << 6;
                    mergeContextCell(cells, index(x, y, z), context[sourceIndex]);
                }
            }
        }
    }

    private static void mergeContextCell(long[] cells, int index, long incoming) {
        long existing = cells[index];
        if (CatalogMapper.isAir(incoming)) {
            if (CatalogMapper.isAir(existing)
                    && CatalogMapper.getLightId(incoming) > CatalogMapper.getLightId(existing)) {
                cells[index] = incoming;
            }
            return;
        }
        if (!CatalogMapper.isAir(existing) && existing != incoming) {
            throw new IllegalArgumentException(
                    "internal neighbor dependency conflicts with selected content");
        }
        cells[index] = incoming;
    }

    private static void addNeighborFace(long[] haloCells, boolean[] haloPresent,
                                        NeighborDependencyMicrotile dependency) {
        int face = rendererFace(dependency.face());
        MicrotileCodec.Metadata metadata = dependency.content().metadata();
        long[] source = dependency.content().cells();
        for (int localY = 0; localY < MicrotileCodec.EDGE; localY++) {
            int y = metadata.originY() + localY;
            for (int localZ = 0; localZ < MicrotileCodec.EDGE; localZ++) {
                int z = metadata.originZ() + localZ;
                for (int localX = 0; localX < MicrotileCodec.EDGE; localX++) {
                    int x = metadata.originX() + localX;
                    if (!opposingBoundary(dependency.face(), x, y, z)) continue;
                    int sourceIndex = localX | localZ << 3 | localY << 6;
                    int haloIndex = face * HALO_FACE_CELLS
                            + faceCellIndex(face, x, y, z);
                    mergeHaloCell(haloCells, haloPresent, haloIndex, source[sourceIndex]);
                }
            }
        }
    }

    private static void mergeHaloCell(long[] haloCells, boolean[] haloPresent,
                                      int index, long incoming) {
        long existing = haloCells[index];
        if (!haloPresent[index]) {
            haloCells[index] = incoming;
            haloPresent[index] = true;
            return;
        }
        if (CatalogMapper.isAir(incoming)) {
            if (CatalogMapper.isAir(existing)
                    && CatalogMapper.getLightId(incoming) > CatalogMapper.getLightId(existing)) {
                haloCells[index] = incoming;
            }
            return;
        }
        if (!CatalogMapper.isAir(existing) && existing != incoming) {
            throw new IllegalArgumentException(
                    "face-tagged neighbor dependencies overlap with different cell content");
        }
        haloCells[index] = incoming;
    }

    /** Canonical manifest order -> renderer face order (-Y,+Y,-Z,+Z,-X,+X). */
    static int rendererFace(NeighborFace face) {
        return switch (Objects.requireNonNull(face, "face")) {
            case NEGATIVE_X -> 4;
            case POSITIVE_X -> 5;
            case NEGATIVE_Y -> 0;
            case POSITIVE_Y -> 1;
            case NEGATIVE_Z -> 2;
            case POSITIVE_Z -> 3;
        };
    }

    private static boolean opposingBoundary(NeighborFace face, int x, int y, int z) {
        return switch (face) {
            case NEGATIVE_X -> x == SECTION_EDGE - 1;
            case POSITIVE_X -> x == 0;
            case NEGATIVE_Y -> y == SECTION_EDGE - 1;
            case POSITIVE_Y -> y == 0;
            case NEGATIVE_Z -> z == SECTION_EDGE - 1;
            case POSITIVE_Z -> z == 0;
        };
    }

    private static int faceCellIndex(int face, int x, int y, int z) {
        return switch (face) {
            case 0, 1 -> x | z << 5;
            case 2, 3 -> x | y << 5;
            case 4, 5 -> z | y << 5;
            default -> throw new IllegalArgumentException("face is outside six directions");
        };
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

    static int index(int x, int y, int z) {
        return x | z << 5 | y << 10;
    }

    static int x(int index) {
        return index & 31;
    }

    static int z(int index) {
        return index >>> 5 & 31;
    }

    static int y(int index) {
        return index >>> 10;
    }

    static int neighbor(int index, int face) {
        int x = x(index);
        int y = y(index);
        int z = z(index);
        return switch (face) {
            case 0 -> y == 0 ? -1 : index - 1024;
            case 1 -> y == 31 ? -1 : index + 1024;
            case 2 -> z == 0 ? -1 : index - 32;
            case 3 -> z == 31 ? -1 : index + 32;
            case 4 -> x == 0 ? -1 : index - 1;
            case 5 -> x == 31 ? -1 : index + 1;
            default -> throw new IllegalArgumentException("face is outside six directions");
        };
    }

    static long neighborCell(Grid grid, int index, int face) {
        int neighbor = neighbor(index, face);
        if (neighbor >= 0) return grid.cells[neighbor];
        return grid.haloCells[face * HALO_FACE_CELLS
                + faceCellIndex(face, x(index), y(index), z(index))];
    }

    static boolean neighborKnown(Grid grid, int index, int face) {
        if (neighbor(index, face) >= 0) return true;
        return grid.haloPresent[face * HALO_FACE_CELLS
                + faceCellIndex(face, x(index), y(index), z(index))];
    }

    static int encodePosition(int index, int face) {
        int x = x(index);
        int y = y(index);
        int z = z(index);
        if (face < 0 || face >= 6) {
            throw new IllegalArgumentException("face is outside six directions");
        }
        return face | x << 21 | y << 16 | z << 11;
    }

    static long encodeQuad(int index, int face, int modelId, int biomeId, int lightId) {
        if ((modelId & ~0xffff) != 0 || (biomeId & ~0x1ff) != 0
                || (lightId & ~0xff) != 0) {
            throw new IllegalArgumentException("quad material exceeds renderer format");
        }
        return Integer.toUnsignedLong(encodePosition(index, face))
                | (long) modelId << 26 | (long) biomeId << 46 | (long) lightId << 55;
    }

    static int emittedAabb(Grid grid, byte path) {
        int minX = SECTION_EDGE;
        int minY = SECTION_EDGE;
        int minZ = SECTION_EDGE;
        int maxX = 0;
        int maxY = 0;
        int maxZ = 0;
        for (int index = 0; index < SECTION_CELLS; index++) {
            if (grid.paths[index] != path || CatalogMapper.isAir(grid.cells[index])) continue;
            int x = x(index);
            int y = y(index);
            int z = z(index);
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x + 1);
            maxY = Math.max(maxY, y + 1);
            maxZ = Math.max(maxZ, z + 1);
        }
        if (minX == SECTION_EDGE) return -1;
        return encodeAabb(minX, minY, minZ, maxX, maxY, maxZ);
    }

    static int encodeAabb(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        if (minX < 0 || minY < 0 || minZ < 0 || maxX > SECTION_EDGE
                || maxY > SECTION_EDGE || maxZ > SECTION_EDGE
                || minX >= maxX || minY >= maxY || minZ >= maxZ) {
            throw new IllegalArgumentException("invalid section geometry bounds");
        }
        return minX | minY << 5 | minZ << 10
                | (maxX - minX - 1) << 15
                | (maxY - minY - 1) << 20
                | (maxZ - minZ - 1) << 25;
    }

    static int mergeAabb(int first, int second) {
        if (first == -1) return second;
        if (second == -1) return first;
        int firstX = first & 31;
        int firstY = first >>> 5 & 31;
        int firstZ = first >>> 10 & 31;
        int secondX = second & 31;
        int secondY = second >>> 5 & 31;
        int secondZ = second >>> 10 & 31;
        int firstMaxX = firstX + (first >>> 15 & 31) + 1;
        int firstMaxY = firstY + (first >>> 20 & 31) + 1;
        int firstMaxZ = firstZ + (first >>> 25 & 31) + 1;
        int secondMaxX = secondX + (second >>> 15 & 31) + 1;
        int secondMaxY = secondY + (second >>> 20 & 31) + 1;
        int secondMaxZ = secondZ + (second >>> 25 & 31) + 1;
        return encodeAabb(Math.min(firstX, secondX), Math.min(firstY, secondY),
                Math.min(firstZ, secondZ), Math.max(firstMaxX, secondMaxX),
                Math.max(firstMaxY, secondMaxY), Math.max(firstMaxZ, secondMaxZ));
    }

    record Grid(long[] cells, byte[] paths, long[] haloCells, boolean[] haloPresent) {
        long inputCell(int index) {
            return index < SECTION_CELLS ? this.cells[index]
                    : this.haloCells[index - SECTION_CELLS];
        }

        boolean inputKnown(int index) {
            return index < SECTION_CELLS || this.haloPresent[index - SECTION_CELLS];
        }
    }
}
