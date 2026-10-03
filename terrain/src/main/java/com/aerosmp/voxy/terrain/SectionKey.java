package com.aerosmp.voxy.terrain;

public record SectionKey(int level, int x, int y, int z) {
    public SectionKey {
        if (level < 0 || level > 4 || y < -128 || y > 127
                || x < -(1 << 23) || x >= (1 << 23) || z < -(1 << 23) || z >= (1 << 23))
            throw new IllegalArgumentException("Invalid terrain coordinate");
    }
    public int size() { return 32 << level; }
    public SectionKey parent() { return level == 4 ? null : new SectionKey(level + 1,
            Math.floorDiv(x, 2), Math.floorDiv(y, 2), Math.floorDiv(z, 2)); }
    public SectionKey child(int index) {
        if (level == 0 || index < 0 || index > 7) throw new IllegalArgumentException("Invalid child");
        return new SectionKey(level - 1, x * 2 + (index & 1),
                y * 2 + ((index >> 2) & 1), z * 2 + ((index >> 1) & 1));
    }
    public String filename() { return level + "_" + x + "_" + y + "_" + z + ".vxs"; }
}
