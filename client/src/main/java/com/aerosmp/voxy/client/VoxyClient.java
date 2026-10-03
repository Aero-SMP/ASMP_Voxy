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

@Mod(value = "voxy", dist = Dist.CLIENT)
public final class VoxyClient {
    private AutoUpdater updater;
    private long nextJoin;
    public VoxyClient() { NeoForge.EVENT_BUS.addListener(this::tick); }
    private void tick(ClientTickEvent.Post event) {
        Minecraft client = Minecraft.getInstance();
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
}
