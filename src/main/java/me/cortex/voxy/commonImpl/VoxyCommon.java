package me.cortex.voxy.commonImpl;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.config.Serialization;
import me.cortex.voxy.common.platform.PlatformUtil;
import me.cortex.voxy.common.platform.PlatformUtilImpl;

public class VoxyCommon {
    public static final String MOD_VERSION;
    public static final boolean IS_DEDICATED_SERVER;
    public static final boolean IS_IN_MINECRAFT;
    private static PlatformUtil PLATFORM_UTIL = new PlatformUtilImpl();

    static {
        String modVersion;
        boolean dedicated;
        boolean inMinecraft;

        var version = PLATFORM_UTIL.getModVersion("voxy");
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
            dedicated = PLATFORM_UTIL.isDedicatedServer();
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

    public interface IInstanceFactory {VoxyInstance create();}
    private static VoxyInstance INSTANCE;
    private static IInstanceFactory FACTORY = null;

    public static void setInstanceFactory(IInstanceFactory factory) {
        if (FACTORY != null) {
            throw new IllegalStateException("Cannot set instance factory more than once");
        }
        FACTORY = factory;
    }

    public static PlatformUtil getPlatformUtil() {
        return PLATFORM_UTIL;
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
        INSTANCE = FACTORY.create();
    }

    //Is voxy available in any capacity
    public static boolean isAvailable() {
        return FACTORY != null;
    }

    public static final boolean IS_MINE_IN_ABYSS = false;
}