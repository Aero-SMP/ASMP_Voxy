package me.cortex.voxy.client.core.rendering.building;

import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.util.UnsafeUtil;

import java.util.Objects;

/** Merges independently owned GPU and CPU fragments into the renderer's eight contiguous bins. */
public final class GeometryMerger implements HybridMeshingDispatcher.GeometryMerger {
    private static final int BUCKET_COUNT = 8;
    private static final int MAX_BUCKET_QUADS = 0xffff;

    @Override
    public BuiltSection merge(long sectionPosition, long sourceRevision,
                              BuiltSection gpuGeometry, BuiltSection cpuGeometry) {
        Objects.requireNonNull(gpuGeometry, "gpuGeometry");
        Objects.requireNonNull(cpuGeometry, "cpuGeometry");
        validate(sectionPosition, sourceRevision, gpuGeometry);
        validate(sectionPosition, sourceRevision, cpuGeometry);
        if (gpuGeometry.childExistence != cpuGeometry.childExistence) {
            throw new IllegalArgumentException("hybrid fragments disagree on child topology");
        }

        int[] gpuCounts = counts(gpuGeometry);
        int[] cpuCounts = counts(cpuGeometry);
        int[] offsets = new int[BUCKET_COUNT];
        int total = 0;
        for (int bucket = 0; bucket < BUCKET_COUNT; bucket++) {
            offsets[bucket] = total;
            int count = Math.addExact(gpuCounts[bucket], cpuCounts[bucket]);
            if (count > MAX_BUCKET_QUADS) {
                throw new IllegalStateException("merged geometry exceeds a renderer bucket bound");
            }
            total = Math.addExact(total, count);
        }
        if (total == 0) {
            return BuiltSection.emptyWithChildren(sectionPosition, sourceRevision,
                    gpuGeometry.childExistence);
        }

        MemoryBuffer output = new MemoryBuffer((long) total * Long.BYTES);
        try {
            int cursor = 0;
            for (int bucket = 0; bucket < BUCKET_COUNT; bucket++) {
                cursor = copyBucket(gpuGeometry, bucket, gpuCounts[bucket], output, cursor);
                cursor = copyBucket(cpuGeometry, bucket, cpuCounts[bucket], output, cursor);
            }
            int aabb = MicrotileGeometry.mergeAabb(gpuGeometry.aabb, cpuGeometry.aabb);
            if (aabb == -1) throw new IllegalStateException("merged geometry has no bounds");
            BuiltSection result = new BuiltSection(sectionPosition, sourceRevision,
                    gpuGeometry.childExistence, aabb, output, offsets);
            output = null;
            return result;
        } finally {
            if (output != null) output.free();
        }
    }

    private static void validate(long position, long revision, BuiltSection section) {
        if (section.position != position || section.sourceRevision != revision) {
            throw new IllegalArgumentException("hybrid fragment identity mismatch");
        }
        counts(section);
    }

    private static int[] counts(BuiltSection section) {
        int[] result = new int[BUCKET_COUNT];
        if (section.isEmpty()) {
            if (section.offsets != null || section.aabb != -1) {
                throw new IllegalArgumentException("empty hybrid fragment has geometry metadata");
            }
            return result;
        }
        if (section.offsets == null || section.offsets.length != BUCKET_COUNT
                || section.geometryBuffer.size % Long.BYTES != 0
                || section.geometryBuffer.size / Long.BYTES > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("malformed hybrid fragment geometry");
        }
        int elements = (int) (section.geometryBuffer.size / Long.BYTES);
        int previous = 0;
        for (int bucket = 0; bucket < BUCKET_COUNT; bucket++) {
            int start = section.offsets[bucket];
            int end = bucket + 1 == BUCKET_COUNT ? elements : section.offsets[bucket + 1];
            if (start != previous || end < start || end > elements) {
                throw new IllegalArgumentException("malformed hybrid fragment offsets");
            }
            result[bucket] = end - start;
            previous = end;
        }
        return result;
    }

    private static int copyBucket(BuiltSection source, int bucket, int count,
                                  MemoryBuffer target, int targetElement) {
        if (count == 0) return targetElement;
        long bytes = (long) count * Long.BYTES;
        UnsafeUtil.memcpy(source.geometryBuffer.address
                        + (long) source.offsets[bucket] * Long.BYTES,
                target.address + (long) targetElement * Long.BYTES, bytes);
        return targetElement + count;
    }
}
