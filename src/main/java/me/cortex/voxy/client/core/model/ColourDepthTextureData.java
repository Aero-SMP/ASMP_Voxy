package me.cortex.voxy.client.core.model;

import java.util.Arrays;

public record ColourDepthTextureData(int[] colour, int[] depth, int width, int height, int hash) {
    public ColourDepthTextureData(int[] colour, int[] depth, int width, int height) {
        this(colour, depth, width, height, width * 312337173 * (Arrays.hashCode(colour) ^ Arrays.hashCode(depth)) ^ height);
    }

    @Override
    public boolean equals(Object obj) {
        return obj == this || obj instanceof ColourDepthTextureData other
                && this.hash == other.hash
                && Arrays.equals(this.colour, other.colour)
                && Arrays.equals(this.depth, other.depth);
    }

    @Override
    public int hashCode() {
        return this.hash;
    }

}
