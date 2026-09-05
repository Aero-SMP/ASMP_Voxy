package me.cortex.voxy.client.core.model;

import me.cortex.voxy.client.core.ShaderReloadCoordinator;
import java.util.function.IntUnaryOperator;

/** Validates aliases before any GPU record or effective mapping is changed. */
public final class MaterialCompatibility {
    private MaterialCompatibility() {}
    public static int[] resolve(int states, int models, IntUnaryOperator modelId,
                                IntUnaryOperator material) {
        int[] result = new int[models];
        boolean[] seen = new boolean[models];
        for (int state = 0; state < states; state++) {
            int id = modelId.applyAsInt(state);
            if (id < 0) continue;
            int value = material.applyAsInt(state);
            if (seen[id] && result[id] != value) {
                throw new ShaderReloadCoordinator.Incompatible("shader material mapping splits shared model " + id);
            }
            seen[id] = true;
            result[id] = value;
        }
        return result;
    }

    /** Applied at the actual upload boundary, not when an asynchronous bake captured its data. */
    public static void patchUpload(long address, int material) {
        org.lwjgl.system.MemoryUtil.memPutInt(address + 32, material);
    }
}
