package me.cortex.voxy.client.core.rendering.building;

import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import me.cortex.voxy.client.core.model.CatalogMapper;
import me.cortex.voxy.client.core.rendering.SectionKey;
import me.cortex.voxy.client.lod.RegionalSectionCodec.SectionData;
import org.lwjgl.system.MemoryUtil;

/** Exact baseline outputs and independent coordinate bounds using the real mesher. */
public final class MeshBoundsBehaviorTest {
    private static final String BASELINE = "902cccc18a2c2594be1be3df61fbb370153362ec802e88be795d139beb2ff87c";
    private static final SectionMesher.Models MODELS = new SectionMesher.Models() {
        public int getModelId(int block) {
            if (block == 99) throw new IllegalStateException("injected lookup");
            return block == 7 ? 0 : block;
        }
        public long getModelMetadataFromClientId(int model) {
            return switch (model) {
                case 0, 6 -> 0xffffffffffffL;
                case 2, 3 -> (16L | 32L | 2L | 1L) << 48;
                case 4 -> 8L << 48;
                case 5 -> 2L << 48;
                default -> 0x050505050505L;
            };
        }
        public int getFluidClientStateId(int model) { return 2; }
        public boolean isModelReadyForBlockId(int block) { return true; }
        public boolean isWaterState(int block) { return block == 2 || block == 4; }
    };

    public static void run() throws Exception {
        var fixtures = fixtures();
        String actual = exercise(fixtures);
        check(BASELINE.equals(actual), "complete mesh output differs: " + actual);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var a = pool.submit(() -> exercise(fixtures));
            var b = pool.submit(() -> exercise(fixtures));
            check(actual.equals(a.get()) && actual.equals(b.get()), "concurrent workspaces differ");
        } finally { pool.shutdownNow(); }
        var mesher = new SectionMesher(MODELS, ignored -> {});
        long[] invalid = new long[32768]; invalid[500] = cell(99, 0);
        try {
            mesher.mesh(new SectionData(0, 0, invalid, new int[0]), 0).free();
            throw new AssertionError("lookup exception swallowed");
        } catch (IllegalStateException expected) {
            check(expected.getMessage().equals("injected lookup"), "unexpected exception");
        }
        checkMesh(mesher, fixtures.get("sparse"), 0, 7, 19, MessageDigest.getInstance("SHA-256"));
        if (Boolean.getBoolean("voxy.simplificationBench")) benchmark(fixtures);
        System.out.println("mesh bounds, exact quad baseline, workspace and exception tests passed");
    }

    private static String exercise(Map<String, long[]> fixtures) throws Exception {
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        var mesher = new SectionMesher(MODELS, ignored -> {});
        for (int repeat = 0; repeat < 2; repeat++) for (int lod = 0; lod < 5; lod++)
            for (long[] cells : fixtures.values())
                checkMesh(mesher, cells, SectionKey.pack(lod, -17, -3, 9), (lod * 57) & 255, 923 + repeat, hash);
        return HexFormat.of().formatHex(hash.digest());
    }

    private static void checkMesh(SectionMesher mesher, long[] cells, long key, int mask, long revision, MessageDigest hash) {
        BuiltSection result = mesher.mesh(new SectionData(key, mask, cells, new int[0]), revision);
        try {
            check(result.position == key && result.sourceRevision == revision
                    && Byte.toUnsignedInt(result.childExistence) == mask, "metadata changed");
            if (result.isEmpty()) check(result.aabb == -1 && result.offsets == null, "empty bounds changed");
            else {
                // Enumerate fixture coordinates independently of the production index decoding.
                int[] min = {32, 32, 32}, max = {-1, -1, -1};
                for (int y = 0; y < 32; y++) for (int z = 0; z < 32; z++) for (int x = 0; x < 32; x++) {
                    if (CatalogMapper.isAir(cells[x + 32 * z + 1024 * y])) continue;
                    min[0] = Math.min(min[0], x); min[1] = Math.min(min[1], y); min[2] = Math.min(min[2], z);
                    max[0] = Math.max(max[0], x); max[1] = Math.max(max[1], y); max[2] = Math.max(max[2], z);
                }
                for (int axis = 0; axis < 3; axis++) {
                    check((result.aabb >>> (axis * 5) & 31) == min[axis], "minimum coordinate");
                    check((result.aabb >>> (15 + axis * 5) & 31) + min[axis] == max[axis], "extent/max coordinate");
                }
            }
            update(hash, result.position); update(hash, result.sourceRevision);
            update(hash, result.childExistence); update(hash, result.aabb);
            update(hash, result.isEmpty() ? 0 : result.geometryBuffer.size);
            if (!result.isEmpty()) {
                for (int offset : result.offsets) update(hash, offset);
                for (long at = 0; at < result.geometryBuffer.size; at += 8)
                    update(hash, MemoryUtil.memGetLong(result.geometryBuffer.address + at));
            }
        } finally { result.free(); }
    }

    private static Map<String, long[]> fixtures() {
        Map<String, long[]> fixtures = new LinkedHashMap<>();
        long[] full = new long[32768]; Arrays.fill(full, cell(1, 0)); fixtures.put("dense", full);
        fixtures.put("air", new long[32768]);
        long[] sparse = new long[32768]; Random random = new Random(9182);
        for (int i = 0; i < 700; i++) sparse[random.nextInt(sparse.length)] = cell(1 + random.nextInt(5), i);
        fixtures.put("sparse", sparse);
        for (int model : new int[]{4, 6, 7, 2, 3, 5}) {
            long[] cells = new long[32768]; Arrays.fill(cells, cell(model, 0)); fixtures.put("model-" + model, cells);
        }
        for (int corner = 0; corner < 8; corner++) {
            long[] cells = new long[32768];
            cells[(corner % 2 * 31) + (corner / 2 % 2 * 31 * 32) + (corner / 4 * 31 * 1024)] = cell(1, corner);
            fixtures.put("corner-" + corner, cells);
        }
        long[] hidden = new long[32768]; hidden[15 + 15 * 32 + 15 * 1024] = cell(1, 0);
        hidden[0] = cell(7, 0); hidden[32767] = cell(6, 0); fixtures.put("invisible-bounds", hidden);
        long[] plane = new long[32768], line = new long[32768];
        for (int i = 0; i < 1024; i++) plane[1024 * 9 + i] = cell(1, i);
        for (int i = 0; i < 32; i++) line[10 * 1024 + 7 * 32 + i] = cell(1, i);
        fixtures.put("plane", plane); fixtures.put("line", line);
        return fixtures;
    }

    private static void benchmark(Map<String, long[]> fixtures) {
        ThreadMXBean bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        boolean allocationSupported = bean.isThreadAllocatedMemorySupported() && bean.isThreadAllocatedMemoryEnabled();
        boolean cpuSupported = bean.isCurrentThreadCpuTimeSupported() && bean.isThreadCpuTimeEnabled();
        for (String name : List.of("dense", "air", "sparse", "model-4", "model-6", "model-7")) {
            var mesher = new SectionMesher(MODELS, ignored -> {});
            var section = new SectionData(0, 0, fixtures.get(name), new int[0]);
            for (int i = 0; i < 400; i++) mesher.mesh(section, 0).free();
            for (int sample = 0; sample < 7; sample++) {
                long allocation = allocationSupported ? bean.getThreadAllocatedBytes(Thread.currentThread().threadId()) : -1;
                long cpu = cpuSupported ? bean.getCurrentThreadCpuTime() : -1, start = System.nanoTime();
                for (int i = 0; i < 200; i++) mesher.mesh(section, 0).free();
                long nanos = System.nanoTime() - start;
                System.out.println("MESH_BENCH " + name + " ns/job=" + nanos / 200
                        + " heap/job=" + (allocationSupported ? (bean.getThreadAllocatedBytes(Thread.currentThread().threadId()) - allocation) / 200 : -1)
                        + " cpu/job=" + (cpuSupported ? (bean.getCurrentThreadCpuTime() - cpu) / 200 : -1));
            }
        }
    }
    private static long cell(int block, int salt) { return CatalogMapper.composeMappingId((byte) (salt * 37), block, salt & 7); }
    private static void update(MessageDigest hash, long value) { hash.update(ByteBuffer.allocate(8).putLong(value).array()); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
