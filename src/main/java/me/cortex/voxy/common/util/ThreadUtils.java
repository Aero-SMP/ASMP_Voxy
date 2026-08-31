package me.cortex.voxy.common.util;
import org.lwjgl.system.Platform;

//Platform specific code to assist in thread utilities
public class ThreadUtils {
    public static final boolean isWindows = Platform.get() == Platform.WINDOWS;
    public static final boolean isLinux = Platform.get() == Platform.LINUX;

    private ThreadUtils() {}
}
