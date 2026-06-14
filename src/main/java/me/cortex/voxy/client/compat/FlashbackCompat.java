package me.cortex.voxy.client.compat;

import me.cortex.voxy.commonImpl.VoxyCommon;

import java.nio.file.Path;

public class FlashbackCompat {
    public static final boolean FLASHBACK_INSTALLED = VoxyCommon.getPlatformUtil().isModLoaded("flashback");

    public static Path getReplayStoragePath() {
        return VoxyCommon.getPlatformUtil().getReplayStoragePath(FLASHBACK_INSTALLED);
    }
}
