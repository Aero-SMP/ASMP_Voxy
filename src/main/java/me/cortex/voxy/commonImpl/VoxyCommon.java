package me.cortex.voxy.commonImpl;

import me.cortex.voxy.common.Logger;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.fml.loading.LoadingModList;

import java.nio.file.Path;

public class VoxyCommon {
    public static final String MOD_VERSION;
    public static final boolean IS_IN_MINECRAFT;

    static {
        String modVersion;
        boolean inMinecraft;

        var version = getModVersion("voxy");
        if (version == null) {
            inMinecraft = false;
            Logger.error("Running voxy without minecraft");
            modVersion = "<UNKNOWN>";
        } else {
            inMinecraft = true;
            modVersion = version;
        }

        MOD_VERSION = modVersion;
        IS_IN_MINECRAFT = inMinecraft;
    }

    private static VoxyInstance INSTANCE;
    private static boolean available;

    public static void setAvailable() {
        if (available) {
            throw new IllegalStateException("Cannot make Voxy available more than once");
        }
        available = true;
    }

    public static VoxyInstance getInstance() {
        return INSTANCE;
    }

    public static void shutdownInstance() {
        if (INSTANCE != null) {
            var instance = INSTANCE;
            INSTANCE = null;//Make it null before shutdown
            instance.shutdown();
        }
    }

    public static void createInstance() {
        if (!available) {
            return;
        }
        if (INSTANCE != null) {
            throw new IllegalStateException("Cannot create multiple instances");
        }
        INSTANCE = new VoxyInstance();
    }

    //Is voxy available in any capacity
    public static boolean isAvailable() {
        return available;
    }

    public static boolean isModLoaded(String modId) {
        var mods = LoadingModList.get();
        return mods != null && mods.getModFileById(modId) != null;
    }

    private static String getModVersion(String modId) {
        var mods = LoadingModList.get();
        if (mods == null) return null;

        var info = mods.getModFileById(modId);
        return info == null ? null : info.versionString();
    }

    public static Path getConfigDir() {
        return FMLPaths.CONFIGDIR.get();
    }
}
