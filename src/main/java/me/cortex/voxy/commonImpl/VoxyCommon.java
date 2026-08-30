package me.cortex.voxy.commonImpl;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.commonImpl.lod.LodStreamingService;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.fml.loading.LoadingModList;

import java.nio.file.Path;
import java.util.function.Supplier;

@Mod("voxy")
public class VoxyCommon {
    public static final String MOD_VERSION;
    public static final boolean IS_DEDICATED_SERVER;
    public static final boolean IS_IN_MINECRAFT;

    public VoxyCommon(IEventBus modBus) {
        new LodStreamingService(modBus);
    }

    static {
        String modVersion;
        boolean dedicated;
        boolean inMinecraft;

        var version = getModVersion("voxy");
        var commit = "<UNKNOWN>";
        if (version == null) {
            inMinecraft = false;
            Logger.error("Running voxy without minecraft");
            modVersion = "<UNKNOWN>";
            dedicated = false;
        } else {
            inMinecraft = true;
            if (commit == null) commit = "unknown";
            modVersion = version + "-" + (commit.length() >= 7 ? commit.substring(0, 7) : commit);
            dedicated = FMLLoader.getDist() == Dist.DEDICATED_SERVER;
        }

        MOD_VERSION = modVersion;
        IS_DEDICATED_SERVER = dedicated;
        IS_IN_MINECRAFT = inMinecraft;
    }

    //This is hardcoded like this because people do not understand what they are doing
    public static boolean isVerificationFlagOn(String name) {
        return isVerificationFlagOn(name, false);
    }

    public static boolean isVerificationFlagOn(String name, boolean defaultOn) {
        return System.getProperty("voxy."+name, defaultOn?"true":"false").equals("true");
    }

    public static void breakpoint() {
        int breakpoint = 0;
    }

    private static VoxyInstance INSTANCE;
    private static Supplier<VoxyInstance> FACTORY;

    public static void setInstanceFactory(Supplier<VoxyInstance> factory) {
        if (FACTORY != null) {
            throw new IllegalStateException("Cannot set instance factory more than once");
        }
        FACTORY = factory;
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
        if (FACTORY == null) {
            //Logger.info("Voxy factory");
            return;
        }
        if (INSTANCE != null) {
            throw new IllegalStateException("Cannot create multiple instances");
        }
        INSTANCE = FACTORY.get();
    }

    //Is voxy available in any capacity
    public static boolean isAvailable() {
        return FACTORY != null;
    }

    public static boolean isModLoaded(String modId) {
        var mods = LoadingModList.get();
        return mods != null && mods.getModFileById(modId) != null;
    }

    public static Path getModRootPath(String modId) {
        var mods = LoadingModList.get();
        if (mods == null) return null;

        var info = mods.getModFileById(modId);
        return info == null ? null : info.getFile().getSecureJar().getRootPath();
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
