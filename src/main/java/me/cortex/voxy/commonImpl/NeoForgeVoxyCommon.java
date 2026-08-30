package me.cortex.voxy.commonImpl;

import me.cortex.voxy.common.config.Serialization;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

@Mod("voxy")
public class NeoForgeVoxyCommon extends VoxyCommon {
    public NeoForgeVoxyCommon() {
        Serialization.init();
    }
}
