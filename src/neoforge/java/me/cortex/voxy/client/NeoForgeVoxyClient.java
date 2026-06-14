package me.cortex.voxy.client;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.common.NeoForge;

@Mod(value = "voxy", dist = Dist.CLIENT)
public class NeoForgeVoxyClient extends VoxyClient {
    public NeoForgeVoxyClient(IEventBus modBus, ModContainer container) {
        NeoForge.EVENT_BUS.addListener((RegisterClientCommandsEvent evt) ->
                evt.getDispatcher().register(VoxyCommands.register()));
    }
}
