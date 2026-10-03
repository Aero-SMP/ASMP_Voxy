package com.aerosmp.voxy.client;

import com.aerosmp.voxy.terrain.TerrainStore;
import net.minecraft.client.Minecraft;
import java.nio.file.*;
import java.util.Properties;

/** Quality settings only; no resource quotas. */
public final class ClientSettings {
    private static final Path FILE = Minecraft.getInstance().gameDirectory.toPath().resolve("config/voxy-rewrite.properties");
    public static boolean rendering = true;
    public static int distance = Integer.getInteger("voxy.viewDistance", 2048);
    public static float pixels = 64;
    static {
        try {
            Properties values = new Properties();
            if (Files.isRegularFile(FILE)) try (var input = Files.newInputStream(FILE)) { values.load(input); }
            rendering = Boolean.parseBoolean(values.getProperty("rendering", "true"));
            distance = Math.clamp(Integer.parseInt(values.getProperty("distance", Integer.toString(distance))), 32, 8192);
            pixels = validate(Float.parseFloat(values.getProperty("pixels", "64")));
        } catch (Exception failure) { System.err.println("Voxy settings: " + failure); }
    }
    public static float validate(float value) { return Float.isFinite(value) ? Math.clamp(value, 28, 256) : 64; }
    public static float fromSlider(int value) { return (float)(28 * Math.exp(Math.log(256.0 / 28) * value / 100)); }
    public static int toSlider(float value) { return Math.clamp((int)Math.round(Math.log(validate(value) / 28) / Math.log(256.0 / 28) * 100), 0, 100); }
    public static void save() {
        try { TerrainStore.atomic(FILE, ("rendering=" + rendering + "\ndistance=" + distance + "\npixels=" + pixels + "\n")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
        catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
    }
    private ClientSettings() {}
}
