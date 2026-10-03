package com.aerosmp.voxy.server;

import com.aerosmp.voxy.update.AutoUpdater;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import java.nio.file.Path;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.HandlerThread;
import net.minecraft.world.level.storage.LevelResource;
import com.aerosmp.voxy.network.Endpoint;

@Mod(value = "voxy_server", dist = Dist.DEDICATED_SERVER)
public final class VoxyServer {
    private AutoUpdater updater;
    private volatile RustBackend backend;
    public VoxyServer(IEventBus modBus) {
        modBus.addListener(this::registerPayload);
        NeoForge.EVENT_BUS.addListener(this::start);
        NeoForge.EVENT_BUS.addListener(this::stop);
    }
    private void registerPayload(RegisterPayloadHandlersEvent event) {
        event.registrar("1").optional().executesOn(HandlerThread.NETWORK)
                .playBidirectional(Endpoint.TYPE, Endpoint.CODEC, (request, context) -> {
                    RustBackend active = backend; Endpoint ready = active == null ? null : active.endpoint();
                    if (request.port() == 0 && ready != null) context.reply(ready);
                });
    }
    private void start(ServerStartedEvent event) {
        try { backend = new RustBackend(event.getServer().getWorldPath(LevelResource.ROOT)); }
        catch (java.io.IOException failure) { System.err.println("Voxy native startup: " + failure); }
        updater = new AutoUpdater(Path.of("."), "server", false, update -> {
            AutoUpdater.install(update);
            event.getServer().execute(() -> event.getServer().halt(false));
        });
        updater.start();
    }
    private void stop(ServerStoppingEvent event) {
        if (updater != null) updater.close();
        RustBackend active = backend; backend = null; if (active != null) active.close();
    }
}
