package me.cortex.voxy.client.core.rendering.building;

import java.util.Arrays;
import me.cortex.voxy.client.core.model.CatalogMapper;

/** Exercise every production fillPlane address, not a duplicate neighbor implementation. */
public final class MesherNeighborBehaviorTest {
    public static void run() throws Exception {
        var models = new SectionMesher.Models() {
            public int getModelId(int block) { return 1; }
            public long getModelMetadataFromClientId(int model) { return 1L << 48; }
            public int getFluidClientStateId(int model) { throw new AssertionError("unexpected overlay"); }
            public boolean isModelReadyForBlockId(int block) { return true; }
            public boolean isWaterState(int block) { return false; }
        };
        var mesher = new SectionMesher(models, ignored -> {});
        Class<?> type = Class.forName(SectionMesher.class.getName() + "$Workspace");
        var constructor = type.getDeclaredConstructor(); constructor.setAccessible(true);
        Object workspace = constructor.newInstance();
        var prepare = SectionMesher.class.getDeclaredMethod("prepare", long[].class, type); prepare.setAccessible(true);
        var fill = SectionMesher.class.getDeclaredMethod("fillPlane", long[].class, type, int.class, int.class, boolean.class); fill.setAccessible(true);
        var field = type.getDeclaredField("plane"); field.setAccessible(true);
        long[] plane = (long[]) field.get(workspace), cells = new long[32768];
        // Two light-byte projections jointly identify every interior neighbor's 15-bit address.
        for (int shift : new int[]{0, 8}) {
            for (int i = 0; i < cells.length; i++) cells[i] = CatalogMapper.composeMappingId((byte) (i >>> shift), 1, i & 511);
            prepare.invoke(mesher, cells, workspace);
            for (int face = 0; face < 6; face++) for (int depth = 0; depth < 32; depth++) {
                fill.invoke(mesher, cells, workspace, face, depth, false);
                for (int v = 0; v < 32; v++) for (int u = 0; u < 32; u++) {
                    int x = face < 4 ? u : depth;
                    int y = face < 2 ? depth : face < 4 ? v : u;
                    int z = face < 2 || face >= 4 ? v : depth;
                    int own = x + z * 32 + y * 1024;
                    switch (face) {
                        case 0 -> y--; case 1 -> y++; case 2 -> z--;
                        case 3 -> z++; case 4 -> x--; case 5 -> x++;
                    }
                    boolean outside = x < 0 || x == 32 || y < 0 || y == 32 || z < 0 || z == 32;
                    int light = ((outside ? own : x + z * 32 + y * 1024) >>> shift) & 255;
                    if (outside) light |= 15;
                    long expected = 1L << 26 | (long) (own & 511) << 46 | (long) light << 55;
                    if (plane[u + v * 32] != expected) throw new AssertionError("plane neighbor/light/tint mismatch: " + face + "/" + depth + "/" + u + "/" + v);
                }
            }
        }
        // Air and lit air must clear workspace data and must not produce geometry.
        Arrays.fill(cells, CatalogMapper.composeMappingId((byte) 0xa5, 0, 0));
        prepare.invoke(mesher, cells, workspace);
        fill.invoke(mesher, cells, workspace, 5, 31, false);
        for (long value : plane) if (value != 0) throw new AssertionError("air retained a model");
        System.out.println("196608 production neighbor mappings checked with two independent address-byte projections");
    }
}
