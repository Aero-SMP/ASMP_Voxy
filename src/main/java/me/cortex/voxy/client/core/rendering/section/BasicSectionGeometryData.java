package me.cortex.voxy.client.core.rendering.section;

import me.cortex.voxy.client.core.gl.GlBuffer;

import static org.lwjgl.opengl.ARBSparseBuffer.*;
import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL15C.GL_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL15C.glBindBuffer;

public class BasicSectionGeometryData {
    public static final int SECTION_METADATA_SIZE = 48;
    private final GlBuffer sectionMetadataBuffer;
    private final GlBuffer geometryBuffer;
    private final int sparsePageSize;

    private final int maxSectionCount;
    private int currentSectionCount;

    public BasicSectionGeometryData(int maxSectionCount, GlBuffer geometryBuffer) {
        this.maxSectionCount = maxSectionCount;
        //8 Cause a quad is 8 bytes
        if ((geometryBuffer.size()%8)!=0) {
            throw new IllegalStateException();
        }
        this.geometryBuffer = geometryBuffer;
        this.sparsePageSize = geometryBuffer.isSparse() ? glGetInteger(GL_SPARSE_BUFFER_PAGE_SIZE_ARB) : 0;
        if (geometryBuffer.isSparse() && this.sparsePageSize <= 0) {
            throw new IllegalStateException("Invalid sparse buffer page size");
        }
        this.sectionMetadataBuffer = new GlBuffer((long) maxSectionCount * SECTION_METADATA_SIZE);
    }

    private long sparseCommitment = 0;//Tracks the current range of the allocated sparse buffer
    /** Validates an exclusive endpoint measured in 8-byte geometry elements. */
    public void ensureAccessible(long maxElementAccess) {
        long capacity = this.geometryBuffer.size();
        long requiredBytes = checkedByteEndpoint(maxElementAccess, capacity);
        if (!this.geometryBuffer.isSparse()) return;
        long target = sparseCommitmentTarget(requiredBytes, this.sparseCommitment, capacity, this.sparsePageSize);
        if (this.sparseCommitment < target) {
            glBindBuffer(GL_ARRAY_BUFFER, this.geometryBuffer.id);
            glBufferPageCommitmentARB(GL_ARRAY_BUFFER, this.sparseCommitment,
                    target-this.sparseCommitment, true);
            glBindBuffer(GL_ARRAY_BUFFER, 0);
            this.sparseCommitment = target;
        }
    }

    static long checkedByteEndpoint(long exclusiveElementEnd, long capacity) {
        if (exclusiveElementEnd < 0 || exclusiveElementEnd > capacity / 8L) {
            throw new IllegalArgumentException("Geometry upload exceeds buffer capacity");
        }
        return exclusiveElementEnd * 8L;
    }

    static long sparseCommitmentTarget(long requiredBytes, long committed, long capacity, int pageSize) {
        if (pageSize <= 0 || requiredBytes < 0 || requiredBytes > capacity
                || committed < 0 || committed > capacity) {
            throw new IllegalArgumentException("Invalid sparse commitment range or page size");
        }
        if (requiredBytes <= committed) return committed;
        long target = alignCommitmentEnd(requiredBytes, capacity, pageSize);
        target += Math.min(64L << 20, capacity - target);
        return alignCommitmentEnd(target, capacity, pageSize);
    }

    private static long alignCommitmentEnd(long end, long capacity, int pageSize) {
        long remainder = end % pageSize;
        return remainder == 0 ? end : end + Math.min(pageSize - remainder, capacity - end);
    }

    public GlBuffer getGeometryBuffer() {
        return this.geometryBuffer;
    }

    public GlBuffer getMetadataBuffer() {
        return this.sectionMetadataBuffer;
    }

    public int getSectionCount() {
        return this.currentSectionCount;
    }

    public void setSectionCount(int count) {
        this.currentSectionCount = count;
    }

    public int getMaxSectionCount() {
        return this.maxSectionCount;
    }

    public long getGeometryCapacityBytes() {
        return this.geometryBuffer.size();
    }

    public void free() {
        this.sectionMetadataBuffer.free();
        if (this.geometryBuffer.isSparse()) {
            glBindBuffer(GL_ARRAY_BUFFER, this.geometryBuffer.id);
            glBufferPageCommitmentARB(GL_ARRAY_BUFFER, 0, this.sparseCommitment, false);
            glBindBuffer(GL_ARRAY_BUFFER, 0);
        }
        glFinish();
    }

}
