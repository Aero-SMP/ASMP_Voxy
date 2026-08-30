package me.cortex.voxy.client;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.RenderResourceReuse;
import me.cortex.voxy.client.mixin.sodium.AccessorSodiumWorldRenderer;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.config.section.SectionSerializationStorage;
import me.cortex.voxy.common.config.storage.rocksdb.RocksDBStorageBackend;
import me.cortex.voxy.commonImpl.VoxyInstance;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.storage.LevelResource;
import java.nio.file.Path;

public class VoxyClientInstance extends VoxyInstance {
    private final Path basePath;

    public VoxyClientInstance() {
        super();
        this.basePath = getBasePath().normalize();
        this.updateDedicatedThreads();
    }

    @Override
    public void updateDedicatedThreads() {
        int target = VoxyConfig.CONFIG.serviceThreads;
        if (!VoxyConfig.CONFIG.dontUseSodiumBuilderThreads) {
            var swr = SodiumWorldRenderer.instanceNullable();
            if (swr != null) {
                var rsm = ((AccessorSodiumWorldRenderer) swr).getRenderSectionManager();
                if (rsm != null) {
                    this.setNumThreads(Math.max(1, target - rsm.getBuilder().getTotalThreadCount()));
                    return;
                }
            }
        }
        this.setNumThreads(target);
    }

    @Override
    protected SectionSerializationStorage createStorage(WorldIdentifier identifier) {
        Path storagePath = this.basePath.resolve(identifier.getWorldId()).resolve("storage-v2");
        return new SectionSerializationStorage(new RocksDBStorageBackend(storagePath));
    }

    @Override
    public boolean isIngestEnabled(WorldIdentifier worldId) {
        return VoxyConfig.CONFIG.ingestEnabled;
    }

    @Override
    public void shutdown() {
        super.shutdown();
        //Free the render resources cache since the entire instance is freed
        RenderResourceReuse.clearResources();
    }

    private static Path getBasePath() {
        Path basePath = Minecraft.getInstance().gameDirectory.toPath().resolve(".voxy").resolve("saves");
        var iserver = Minecraft.getInstance().getSingleplayerServer();
        if (iserver != null) {
            basePath = iserver.getWorldPath(LevelResource.ROOT).resolve("voxy");
        } else {
            var netHandle = Minecraft.getInstance().gameMode;
            if (netHandle == null) {
                Logger.error("Network handle null");
                basePath = basePath.resolve("UNKNOWN");
            } else {
                var info = netHandle.connection.getServerData();
                if (info == null) {
                    Logger.error("Server info null");
                    basePath = basePath.resolve("UNKNOWN");
                } else {
                    if (info.isRealm()) {
                        basePath = basePath.resolve("realms");
                    } else {
                        basePath = basePath.resolve(info.ip.replace(":", "_"));
                    }
                }
            }
        }
        return basePath.toAbsolutePath();
    }
}
