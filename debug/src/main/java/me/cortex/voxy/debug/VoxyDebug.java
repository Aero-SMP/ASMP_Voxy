package me.cortex.voxy.debug;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

@Mod("voxy_debug")
public final class VoxyDebug {
    public VoxyDebug(IEventBus modBus) {
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent event) ->
                event.getDispatcher().register(ServerDiagnostics.command()));
        if (FMLLoader.getDist() == Dist.CLIENT) {
            ClientBootstrap.init(modBus);
        }
    }
}
