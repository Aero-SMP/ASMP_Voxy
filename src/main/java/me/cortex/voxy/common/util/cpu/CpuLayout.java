package me.cortex.voxy.common.util.cpu;

import com.sun.jna.platform.win32.Kernel32Util;
import com.sun.jna.platform.win32.WinNT;
import me.cortex.voxy.common.Logger;
import org.lwjgl.system.Platform;
import oshi.SystemInfo;

/** Detects the physical core count used to size Voxy's worker pool. */
public final class CpuLayout {
    private CpuLayout(){}

    private static final int CORE_COUNT = detectCoreCount();

    private static int detectCoreCount() {
        try {
            if (Platform.get() == Platform.WINDOWS) {
                return Kernel32Util.getLogicalProcessorInformationEx(
                        WinNT.LOGICAL_PROCESSOR_RELATIONSHIP.RelationProcessorCore).length;
            }
            if (Platform.get() == Platform.LINUX) {
                int cores = new SystemInfo().getHardware().getProcessor().getPhysicalProcessors().size();
                if (cores > 0) return cores;
            }
        } catch (RuntimeException e) {
            Logger.error("Failed to detect physical core count; using logical processors", e);
        }
        return Runtime.getRuntime().availableProcessors();
    }

    public static int getCoreCount() {
        return CORE_COUNT;
    }
}
