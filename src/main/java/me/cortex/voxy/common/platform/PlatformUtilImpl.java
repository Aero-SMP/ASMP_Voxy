package me.cortex.voxy.common.platform;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.fml.loading.LoadingModList;
import net.neoforged.fml.loading.moddiscovery.ModFileInfo;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

public class PlatformUtilImpl implements PlatformUtil {
    @Override
    public boolean isModLoaded(String modId) {
        LoadingModList lm = LoadingModList.get();
        if (lm == null) return false;
        return lm.getModFileById(modId) != null;
    }

    @Override
    public Path getModRootPath(String modId) {
        LoadingModList lm = LoadingModList.get();
        if (lm == null) return null;

        ModFileInfo info = lm.getModFileById(modId);
        if (info == null) return null;

        return info.getFile().getSecureJar().getRootPath();
    }

    @Override
    public String getModVersion(String modId) {
        LoadingModList lm = LoadingModList.get();
        if (lm == null) return null;

        ModFileInfo info = lm.getModFileById(modId);
        if (info == null) return null;

        return info.versionString();
    }

    @Override
    public Path getReplayStoragePath(boolean flashbackInstalled) {
        return null;
    }

    @Override
    public Path getConfigDir() {
        return FMLPaths.CONFIGDIR.get();
    }

    @Override
    public boolean isDedicatedServer() {
        return FMLLoader.getDist() == Dist.DEDICATED_SERVER;
    }
}
