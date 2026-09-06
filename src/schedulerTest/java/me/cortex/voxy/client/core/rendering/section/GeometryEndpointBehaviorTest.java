package me.cortex.voxy.client.core.rendering.section;

/** Headless tests of the actual production endpoint and commitment calculations. */
public final class GeometryEndpointBehaviorTest {
    private static final long REPORTED_CAPACITY = 2_147_482_624L;
    private static final long LOOKAHEAD = 64L << 20;

    public static void run() {
        for (long capacity : new long[]{0, 8, 65_528, 65_536, 65_544, REPORTED_CAPACITY,
                1L << 32, Long.MAX_VALUE & ~7L}) {
            long end = capacity / 8;
            equal(BasicSectionGeometryData.checkedByteEndpoint(end, capacity), capacity, "exact end");
            equal(BasicSectionGeometryData.checkedByteEndpoint(0, capacity), 0, "zero");
            if (end > 0) equal(BasicSectionGeometryData.checkedByteEndpoint(end - 1, capacity), capacity - 8, "below end");
            for (long invalid : new long[]{end + 1, -1, Long.MIN_VALUE, Long.MAX_VALUE, 1L << 61, (1L << 61) + 1}) {
                rejects(() -> BasicSectionGeometryData.checkedByteEndpoint(invalid, capacity));
            }
        }
        equal(BasicSectionGeometryData.checkedByteEndpoint(268_435_328L, REPORTED_CAPACITY), REPORTED_CAPACITY, "reported crash");
        // Old code would incorrectly ask for another 1,024 bytes at this valid endpoint.
        equal((REPORTED_CAPACITY + 65_535) & ~65_535L, 2_147_483_648L, "old false rounding");
        for (int page : new int[]{4096, 65_536, 3000}) {
            for (long capacity : new long[]{8, page - 8L, page, page + 8L, LOOKAHEAD - 8,
                    REPORTED_CAPACITY, 1L << 40, Long.MAX_VALUE & ~7L}) {
                long committed = 0;
                for (long required : new long[]{0, 1, page - 1L, page, page + 1L,
                        LOOKAHEAD + page + 1L, capacity - 8, capacity}) {
                    if (required < 0 || required > capacity) continue;
                    long target = BasicSectionGeometryData.sparseCommitmentTarget(required, committed, capacity, page);
                    check(target >= committed && target <= capacity && target >= required, "commit bounds");
                    check(target % page == 0 || target == capacity, "target page alignment");
                    if (target > committed) check(committed % page == 0, "commit offset alignment");
                    if (required <= committed) equal(target, committed, "already covered");
                    committed = target;
                }
                equal(committed, capacity, "last partial page");
                equal(BasicSectionGeometryData.sparseCommitmentTarget(capacity, committed, capacity, page), capacity, "repeat final page");
                equal(BasicSectionGeometryData.sparseCommitmentTarget(0, committed, capacity, page), capacity, "covered zero");
            }
        }
        equal(BasicSectionGeometryData.sparseCommitmentTarget(65_535, 0, 1L << 30, 65_536), LOOKAHEAD + 65_536, "lookahead below page");
        equal(BasicSectionGeometryData.sparseCommitmentTarget(65_536, 0, 1L << 30, 65_536), LOOKAHEAD + 65_536, "lookahead on page");
        equal(BasicSectionGeometryData.sparseCommitmentTarget(65_537, 0, 1L << 30, 65_536), LOOKAHEAD + 131_072, "lookahead above page");
        equal(BasicSectionGeometryData.sparseCommitmentTarget(3001, 0, 1L << 30, 3000), 67_116_000, "non-power-of-two lookahead");
        equal(BasicSectionGeometryData.sparseCommitmentTarget(REPORTED_CAPACITY, 0, REPORTED_CAPACITY, 65_536), REPORTED_CAPACITY, "reported partial tail");
        for (int page : new int[]{0, -1, Integer.MIN_VALUE}) {
            rejects(() -> BasicSectionGeometryData.sparseCommitmentTarget(0, 0, 8, page));
        }
        rejects(() -> BasicSectionGeometryData.sparseCommitmentTarget(REPORTED_CAPACITY + 8, 0, REPORTED_CAPACITY, 65_536));
        rejects(() -> BasicSectionGeometryData.sparseCommitmentTarget(-1, 0, 8, 65_536));
        System.out.println("geometry endpoint and sparse commitment boundary tests passed");
    }

    private static void equal(long actual, long expected, String message) {
        check(actual == expected, message + ": " + actual + " != " + expected);
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static void rejects(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("invalid endpoint/page size accepted");
    }
}
