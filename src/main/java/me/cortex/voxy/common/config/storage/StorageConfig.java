package me.cortex.voxy.common.config.storage;

import me.cortex.voxy.common.config.ConfigBuildCtx;
import me.cortex.voxy.common.config.Serialization;

public abstract class StorageConfig {
    static {
        Serialization.CONFIG_TYPES.add(StorageConfig.class);
    }

    public abstract StorageBackend build(ConfigBuildCtx ctx);
}
