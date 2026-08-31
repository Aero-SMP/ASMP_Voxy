package me.cortex.voxy.client.core.rendering.hierachical;

import java.util.Arrays;

final class NodeRequest {
    private final long position;
    private final int[] meshes = new int[8];
    private final byte[] childExistence = new byte[8];
    private int meshMask;
    private int existenceMask;
    private int requiredMask;

    NodeRequest(long position) {
        this.position = position;
        Arrays.fill(this.meshes, NodeManager.NULL_GEOMETRY_ID);
    }

    long position() {
        return this.position;
    }

    int requiredMask() {
        return this.requiredMask;
    }

    void require(int child) {
        int bit = 1 << child;
        if ((this.requiredMask & bit) != 0) {
            throw new IllegalStateException("Child is already required");
        }
        this.requiredMask |= bit;
    }

    int unrequire(int child) {
        int bit = 1 << child;
        if ((this.requiredMask & bit) == 0) {
            throw new IllegalStateException("Child is not required");
        }
        int mesh = this.meshes[child];
        this.meshes[child] = NodeManager.NULL_GEOMETRY_ID;
        this.requiredMask &= ~bit;
        this.meshMask &= ~bit;
        this.existenceMask &= ~bit;
        return mesh;
    }

    int mesh(int child) {
        requireBit(child);
        return this.meshes[child];
    }

    int replaceMesh(int child, int mesh) {
        requireBit(child);
        int previous = this.meshes[child];
        this.meshes[child] = mesh;
        this.meshMask |= 1 << child;
        return previous;
    }

    boolean hasChildExistence(int child) {
        requireBit(child);
        return (this.existenceMask & (1 << child)) != 0;
    }

    byte childExistence(int child) {
        if (!hasChildExistence(child)) {
            throw new IllegalStateException("Child existence is not available");
        }
        return this.childExistence[child];
    }

    void setChildExistence(int child, byte existence) {
        requireBit(child);
        this.childExistence[child] = existence;
        this.existenceMask |= 1 << child;
    }

    boolean isSatisfied() {
        return (this.meshMask & this.requiredMask) == this.requiredMask
                && (this.existenceMask & this.requiredMask) == this.requiredMask;
    }

    private void requireBit(int child) {
        if ((this.requiredMask & (1 << child)) == 0) {
            throw new IllegalStateException("Child is not required");
        }
    }
}
