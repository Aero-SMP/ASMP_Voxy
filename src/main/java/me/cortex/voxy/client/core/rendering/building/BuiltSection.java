package me.cortex.voxy.client.core.rendering.building;

import me.cortex.voxy.common.util.MemoryBuffer;

import java.util.Arrays;

//TODO: also have an AABB size stored
public final class BuiltSection {
    public final long position;
    public final byte childExistence;
    public final int aabb;
    public final MemoryBuffer geometryBuffer;
    public final int[] offsets;

    private BuiltSection(long position, byte children) {
        this(position, children, -1, null, null);
    }

    public static BuiltSection empty(long position) {
        return new BuiltSection(position, (byte) 0);
    }
    public static BuiltSection emptyWithChildren(long position, byte children) {
        return new BuiltSection(position, children);
    }

    public BuiltSection(long position, byte childExistence, int aabb, MemoryBuffer geometryBuffer, int[] offsets) {
        this.position = position;
        this.childExistence = childExistence;
        this.aabb = aabb;
        this.geometryBuffer = geometryBuffer;
        this.offsets = offsets;
    }

    public BuiltSection clone() {
        return new BuiltSection(this.position, this.childExistence, this.aabb, this.geometryBuffer!=null?this.geometryBuffer.copy():null, this.offsets!=null?Arrays.copyOf(this.offsets, this.offsets.length):null);
    }

    public void free() {
        if (this.geometryBuffer != null) {
            this.geometryBuffer.free();
        }
    }

    public boolean isEmpty() {
        return this.geometryBuffer == null;
    }
}
