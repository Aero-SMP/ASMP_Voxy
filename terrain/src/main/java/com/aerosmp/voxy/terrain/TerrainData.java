package com.aerosmp.voxy.terrain;

/** Immutable after ownership is transferred to a worker or renderer. */
public record TerrainData(SectionKey key, int children, int[] blocks, int[] biomes,
                          byte[] light, String[] blockNames, String[] biomeNames) {
    public static final int CELLS = 32 * 32 * 32;
    public TerrainData {
        if (blocks.length != CELLS || biomes.length != CELLS || light.length != CELLS
                || children < 0 || children > 255 || blockNames.length == 0 || biomeNames.length == 0)
            throw new IllegalArgumentException("Invalid section data");
    }
}
