package me.cortex.voxy.commonImpl;

import me.cortex.voxy.common.config.Serialization;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.eventbus.api.IEventBus;


@Mod("voxy")
public class ForgeVoxyCommon extends VoxyCommon {
    public ForgeVoxyCommon() {
        org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

        LOGGER.info("Initializing Voxy Common");
        Serialization.init();
    }
}
