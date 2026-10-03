package com.aerosmp.voxy.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import java.nio.file.*;
import java.util.Properties;

/** Local controls for the authorized real-player session; no remote command execution. */
final class LiveControl {
    private long nextRead, modified = -1;
    boolean network = true;
    boolean render = true, clipping = true;
    float pixels = Float.NaN;
    private Integer originalFov;
    void tick(Minecraft client) {
        if (System.nanoTime() < nextRead) return;
        nextRead = System.nanoTime() + 1_000_000_000L;
        Path file = client.gameDirectory.toPath().resolve(".voxy-rewrite/control.properties");
        try {
            if (!Files.isRegularFile(file)) return;
            long time = Files.getLastModifiedTime(file).toMillis(); if (time == modified) return;
            Properties properties = new Properties();
            try (var input = Files.newInputStream(file)) { properties.load(input); }
            network = Boolean.parseBoolean(properties.getProperty("network", "true"));
            render = Boolean.parseBoolean(properties.getProperty("render", "true"));
            clipping = Boolean.parseBoolean(properties.getProperty("clip", "true"));
            pixels = properties.containsKey("pixels") ? ClientSettings.validate(Float.parseFloat(properties.getProperty("pixels"))) : Float.NaN;
            if (properties.containsKey("fov")) {
                if (originalFov == null) originalFov = client.options.fov().get();
                client.options.fov().set(Math.clamp(Integer.parseInt(properties.getProperty("fov")), 30, 110));
            } else if (originalFov != null) { client.options.fov().set(originalFov); originalFov = null; }
            if (modified != -1 && properties.containsKey("screenshot")) Screenshot.grab(client.gameDirectory,
                    client.getMainRenderTarget(), result -> System.out.println("[Voxy live] " + result.getString()));
            if (modified != -1 && properties.containsKey("reload")) client.reloadResourcePacks();
            modified = time;
        } catch (java.io.IOException | IllegalArgumentException failure) { System.err.println("Voxy live control: " + failure); }
    }
}
