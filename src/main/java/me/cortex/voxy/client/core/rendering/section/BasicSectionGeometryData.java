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

    private final int maxSectionCount;
    private int currentSectionCount;

    public BasicSectionGeometryData(int maxSectionCount, GlBuffer geometryBuffer) {
        this.maxSectionCount = maxSectionCount;
        this.sectionMetadataBuffer = new GlBuffer((long) maxSectionCount * SECTION_METADATA_SIZE);
        //8 Cause a quad is 8 bytes
        if ((geometryBuffer.size()%8)!=0) {
            throw new IllegalStateException();
        }
        this.geometryBuffer = geometryBuffer;
    }

    private long sparseCommitment = 0;//Tracks the current range of the allocated sparse buffer
    public void ensureAccessible(long maxElementAccess) {
        long requiredBytes = (maxElementAccess*8L+65535L)&~65535L;
        if (requiredBytes > this.geometryBuffer.size()) {
            throw new IllegalArgumentException("Geometry upload exceeds buffer capacity");
        }
        //If we are a sparse buffer, ensure the memory upto the requested size is allocated
        if (this.geometryBuffer.isSparse() && this.sparseCommitment < requiredBytes) {
            long target = Math.min(this.geometryBuffer.size(), requiredBytes + (64L << 20));
            glBindBuffer(GL_ARRAY_BUFFER, this.geometryBuffer.id);
            glBufferPageCommitmentARB(GL_ARRAY_BUFFER, this.sparseCommitment,
                    target-this.sparseCommitment, true);
            glBindBuffer(GL_ARRAY_BUFFER, 0);
            this.sparseCommitment = target;
        }
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
