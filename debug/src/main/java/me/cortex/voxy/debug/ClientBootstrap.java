package me.cortex.voxy.debug;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.common.NeoForge;

final class ClientBootstrap {
    private ClientBootstrap() {}

    static void init(IEventBus modBus) {
        NeoForge.EVENT_BUS.addListener((RegisterClientCommandsEvent event) ->
                event.getDispatcher().register(VoxyDebugCommands.register()));
        modBus.addListener((RegisterGuiLayersEvent event) -> ClientDiagnostics.register(event));
    }
}
