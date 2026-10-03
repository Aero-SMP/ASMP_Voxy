package com.aerosmp.voxy.server;

import com.aerosmp.voxy.update.AutoUpdater;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import java.nio.file.Path;

@Mod(value = "voxy_server", dist = Dist.DEDICATED_SERVER)
public final class VoxyServer {
    private AutoUpdater updater;
    public VoxyServer() {
        NeoForge.EVENT_BUS.addListener(this::start);
        NeoForge.EVENT_BUS.addListener(this::stop);
    }
    private void start(ServerStartedEvent event) {
        updater = new AutoUpdater(Path.of("."), "server", false, update -> {
            AutoUpdater.install(update);
            event.getServer().execute(() -> event.getServer().halt(false));
        });
        updater.start();
    }
    private void stop(ServerStoppingEvent event) {
        if (updater != null) updater.close();
    }
}
