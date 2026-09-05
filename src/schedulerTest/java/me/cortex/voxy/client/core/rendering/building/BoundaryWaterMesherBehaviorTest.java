package me.cortex.voxy.client.core.rendering.building;

import me.cortex.voxy.client.core.model.CatalogMapper;
import me.cortex.voxy.client.core.rendering.SectionKey;
import me.cortex.voxy.client.lod.RegionalSectionCodec.SectionData;
import org.lwjgl.system.MemoryUtil;

import java.util.*;

/** Runs the production greedy mesher; only baked model lookups are controlled. */
public final class BoundaryWaterMesherBehaviorTest {
    private static final int WATER = 10, FLOWING = 11, WATERLOGGED = 12, LAVA = 13,
            GLASS = 14, STONE = 15, OTHER_FLUID = 16, TAGGED_WATER = 17, SHARED_NON_WATER = 18;
    private static final long FLUID = (16L | 32L | 2L | 1L) << 48;

    private static final class Models implements SectionMesher.Models {
        int waterQueries;
        @Override public int getModelId(int block) {
            return switch (block) {
                case WATER, FLOWING, TAGGED_WATER, SHARED_NON_WATER -> 1;
                case WATERLOGGED -> 2;
                case LAVA -> 3;
                case GLASS -> 4;
                case STONE -> 5;
                case OTHER_FLUID -> 6;
                default -> throw new AssertionError("unexpected block " + block);
            };
        }
        @Override public long getModelMetadataFromClientId(int model) {
            return switch (model) {
                case 1, 3, 6 -> FLUID;
                case 2 -> 8L << 48; // Solid waterlogged primary, with a separate fluid overlay.
                case 4 -> 2L << 48;
                case 5 -> 0;
                default -> throw new AssertionError("unexpected model " + model);
            };
        }
        @Override public int getFluidClientStateId(int model) {
            check(model == 2, "fluid lookup on a non-waterlogged solid");
            return 1;
        }
        @Override public boolean isModelReadyForBlockId(int block) { return true; }
        @Override public boolean isWaterState(int block) {
            waterQueries++;
            check(block >= WATER, "water query received a deduplicated model ID");
            return block == WATER || block == FLOWING || block == WATERLOGGED || block == TAGGED_WATER;
        }
    }

    public static void run() {
        Models models = new Models();
        SectionMesher mesher = new SectionMesher(models, ignored -> {});
        // Every LOD, negative region/section coordinates, each outward direction.
        int[][] edges = {{9, 10, 0, 2}, {9, 10, 31, 3}, {0, 10, 9, 4}, {31, 10, 9, 5}};
        for (int lod = 0; lod < 5; lod++) {
            long key = SectionKey.pack(lod, -17, -3, -9);
            for (int block : new int[]{WATER, FLOWING, TAGGED_WATER}) {
                for (int[] edge : edges) {
                    long[] cells = cell(block, edge[0], edge[1], edge[2]);
                    List<Long> quads = mesh(mesher, cells, key);
                    check(quads.size() == 5, "boundary water should retain exactly five isolated faces");
                    for (int face = 0; face < 6; face++) {
                        check(count(quads, face, 1) == (face == edge[3] ? 0 : 1), "wrong boundary direction suppressed");
                    }
                }
            }
        }
        List<Long> corner = mesh(mesher, cell(WATER, 0, 10, 31), 0);
        check(corner.size() == 4 && count(corner, 4, 1) == 0 && count(corner, 3, 1) == 0,
                "corner must suppress precisely two outward sides");
        for (int y : new int[]{0, 31}) {
            models.waterQueries = 0;
            List<Long> quads = mesh(mesher, cell(WATER, 10, y, 10), 0);
            check(quads.size() == 6 && models.waterQueries == 0, "Y boundary changed side or top/bottom behavior");
            int face = y == 0 ? 0 : 1;
            check((find(quads, face, 1) >>> 55 & 15) == 15, "remaining boundary water lost full skylight");
        }
        models.waterQueries = 0;
        check(mesh(mesher, cell(WATER, 1, 10, 10), 0).size() == 6 && models.waterQueries == 0,
                "interior water side removed or unnecessary state lookup");
        long[] neighbors = cell(WATER, 10, 10, 10);
        put(neighbors, WATER, 11, 10, 10);
        List<Long> adjacent = mesh(mesher, neighbors, 0);
        check(area(adjacent) == 10 && count(adjacent, 4, 1) == 1 && count(adjacent, 5, 1) == 1,
                "interior same-water culling changed");

        List<Long> logged = mesh(mesher, cell(WATERLOGGED, 0, 10, 10), 0);
        List<Long> solid = logged.stream().filter(q -> model(q) == 2).sorted().toList();
        // Compare records to an otherwise identical solid fixture, independent of bucket offsets.
        List<Long> reference = mesh(mesher, cell(STONE, 0, 10, 10), 0).stream()
                .map(q -> (q & ~(0xfffffL << 26)) | 2L << 26).sorted().toList();
        check(solid.equals(reference) && solid.size() == 6 && logged.size() == 11
                && count(logged, 4, 1) == 0, "waterlogged solid faces changed or outward overlay survived");
        check((find(logged, 4, 2) >>> 55 & 15) == 15, "solid boundary skylight changed");

        for (int block : new int[]{LAVA, GLASS, STONE, OTHER_FLUID, SHARED_NON_WATER}) {
            models.waterQueries = 0;
            List<Long> quads = mesh(mesher, cell(block, 0, 10, 10), 0);
            check(quads.size() == 6, "non-water geometry was suppressed");
            check(models.waterQueries == (block == GLASS || block == STONE ? 0 : 1),
                    "state classification was not limited to outward fluid sides");
        }

        // A genuine exposed waterfall on the section edge intentionally loses its outward wall.
        long[] waterfall = new long[32768];
        for (int y = 0; y < 32; y++) put(waterfall, FLOWING, 0, y, 10);
        List<Long> falling = mesh(mesher, waterfall, 0);
        check(count(falling, 4, 1) == 0 && area(falling) == 98,
                "intentional missing waterfall side not represented");
        for (int iteration = 0; iteration < 8; iteration++) {
            check(mesh(mesher, new long[32768], 0).isEmpty(), "empty section retained workspace faces");
            check(mesh(mesher, cell(WATER, 0, 10, 10), 0).size() == 5, "workspace reuse changed output");
        }
        System.out.println("boundary water production-mesher behavior tests passed");
    }

    private static long[] cell(int block, int x, int y, int z) {
        long[] cells = new long[32768];
        put(cells, block, x, y, z);
        return cells;
    }
    private static void put(long[] cells, int block, int x, int y, int z) {
        cells[x | z << 5 | y << 10] = CatalogMapper.composeMappingId((byte) 0x60, block, 7);
    }
    private static List<Long> mesh(SectionMesher mesher, long[] cells, long key) {
        BuiltSection result = mesher.mesh(new SectionData(key, 0xa5, cells, new int[0]), 923);
        try {
            check(result.position == key && result.sourceRevision == 923
                    && Byte.toUnsignedInt(result.childExistence) == 0xa5, "section metadata changed");
            List<Long> quads = new ArrayList<>();
            if (result.isEmpty()) {
                check(result.offsets == null && result.aabb == -1, "empty section has geometry metadata");
                return quads;
            }
            int total = Math.toIntExact(result.geometryBuffer.size / 8);
            int previous = 0;
            for (int offset : result.offsets) {
                check(offset >= previous && offset <= total, "invalid bucket offset");
                previous = offset;
            }
            for (int i = 0; i < total; i++) {
                long quad = MemoryUtil.memGetLong(result.geometryBuffer.address + 8L * i);
                check((quad & 7) < 6 && model(quad) > 0, "invalid quad record");
                quads.add(quad);
            }
            return quads;
        } finally { result.free(); }
    }
    private static int model(long quad) { return (int) (quad >>> 26 & 0xfffff); }
    private static long count(List<Long> quads, int face, int model) {
        return quads.stream().filter(q -> (q & 7) == face && model(q) == model).count();
    }
    private static long find(List<Long> quads, int face, int model) {
        return quads.stream().filter(q -> (q & 7) == face && model(q) == model).findFirst().orElseThrow();
    }
    private static int area(List<Long> quads) {
        return quads.stream().mapToInt(q -> ((int) (q >>> 3 & 15) + 1) * ((int) (q >>> 7 & 15) + 1)).sum();
    }
    private static void check(boolean valid, String message) {
        if (!valid) throw new AssertionError(message);
    }
}
