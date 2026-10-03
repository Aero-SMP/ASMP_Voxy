package com.aerosmp.voxy.client;

import com.aerosmp.voxy.update.AutoUpdater;
import com.aerosmp.voxy.update.RestartHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.HandlerThread;
import com.aerosmp.voxy.network.Endpoint;
import com.aerosmp.voxy.client.render.TerrainRenderer;

@Mod(value = "voxy", dist = Dist.CLIENT)
public final class VoxyClient {
    private AutoUpdater updater;
    private long nextJoin;
    private volatile TerrainSession terrain;
    private net.minecraft.client.multiplayer.ClientLevel level;
    private long nextDiscovery;
    private final LiveControl control = new LiveControl();
    public VoxyClient(IEventBus modBus) {
        modBus.addListener(this::registerPayload);
        modBus.addListener(TerrainRenderer::registerShaders);
        NeoForge.EVENT_BUS.addListener(this::tick);
    }
    private void registerPayload(RegisterPayloadHandlersEvent event) {
        event.registrar("1").optional().executesOn(HandlerThread.NETWORK)
                .playBidirectional(Endpoint.TYPE, Endpoint.CODEC, (value, context) -> {
                    TerrainSession active = terrain;
                    if (active != null && value.port() != 0) active.endpoint(value);
                });
    }
    private void tick(ClientTickEvent.Post event) {
        Minecraft client = Minecraft.getInstance();
        if (client.getUser() != null && client.getUser().getName().equals("MGengine")) control.tick(client);
        if (client.level != level) resetTerrain();
        if (client.level != null && client.player != null && terrain == null) {
            level = client.level;
            String address = client.getCurrentServer() == null ? "local" : client.getCurrentServer().ip;
            try { terrain = new TerrainSession(client.gameDirectory.toPath().resolve(".voxy-rewrite"),
                    address, level.dimension().location().toString(), this::resetTerrain); }
            catch (java.io.IOException failure) { System.err.println("Voxy cache: " + failure); }
        }
        if (terrain != null) {
            terrain.network(control.network);
            terrain.render(control.render);
            terrain.clipping(control.clipping);
            terrain.pixels(control.pixels);
            terrain.tick();
            if (client.getConnection() != null && client.getConnection().hasChannel(Endpoint.TYPE)
                    && System.nanoTime() >= nextDiscovery) {
                nextDiscovery = System.nanoTime() + 5_000_000_000L;
                client.getConnection().send(Endpoint.request());
            }
        }
        if (client.getUser() == null || !client.getUser().getName().equals("MGengine")) return;
        if (updater == null) {
            updater = new AutoUpdater(client.gameDirectory.toPath(), "client", true, update -> {
                RestartHelper.prepare(update);
                client.execute(() -> { client.disconnect(); client.stop(); });
            });
            updater.start();
        }
        if (updater.restartPending() || !client.isGameLoadFinished() || client.level != null
                || client.getConnection() != null || !(client.screen instanceof TitleScreen
                || client.screen instanceof JoinMultiplayerScreen || client.screen instanceof DisconnectedScreen)
                || System.nanoTime() < nextJoin) return;
        nextJoin = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        String address = "play.aerosmp.com:25587";
        ConnectScreen.startConnecting(client.screen, client, ServerAddress.parseString(address),
                new ServerData("Voxy Testing", address, ServerData.Type.OTHER), false, null);
    }
    private void resetTerrain() {
        TerrainSession previous = terrain; terrain = null; level = null;
        if (previous != null) previous.close();
    }
}
