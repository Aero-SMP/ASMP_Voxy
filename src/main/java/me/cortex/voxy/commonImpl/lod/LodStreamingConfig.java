package me.cortex.voxy.commonImpl.lod;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class LodStreamingConfig {

    private static final Path CONFIG_PATH = FMLPaths.CONFIGDIR.get().resolve("voxy-lod-streaming.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public static ConfigData DATA = new ConfigData();

    // Reloading replaces this object so callers always see the latest persisted values.

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            // first run, gentle default. cores*2 up to 48 buried the tick thread,
            // especially single player where the integrated server shares cores with
            // the client. 6 is a safe starting point, users can raise it
            DATA.maxActiveTasks = 6;
            DATA.generationRadius = 128;

            save();
            return;
        }
        try (var reader = Files.newBufferedReader(CONFIG_PATH)) {
            DATA = GSON.fromJson(reader, ConfigData.class);
        } catch (IOException e) {
            LodStreamingService.LOGGER.error("failed to load config", e);
        }
    }

    public static void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            try (var writer = Files.newBufferedWriter(CONFIG_PATH)) {
                GSON.toJson(DATA, writer);
            }
        } catch (IOException e) {
            LodStreamingService.LOGGER.error("failed to save config", e);
        }
    }

    public static class ConfigData {
        // server side, an op can push these from the client
        public boolean enabled = true;
        public int generationRadius = 128;
        public int update_interval = 20; // legacy field for compat
        public int maxQueueSize = 20000;
        public int maxActiveTasks = 20;

        // client side, local to each player
        public boolean showF3MenuStats = true;
    }

    // immutable snapshot of just the server-side fields, used to sync between server and clients
    public record ServerConfig(boolean enabled, int generationRadius, int updateInterval, int maxQueueSize, int maxActiveTasks) {
        public static ServerConfig snapshot() {
            return new ServerConfig(DATA.enabled, DATA.generationRadius, DATA.update_interval, DATA.maxQueueSize, DATA.maxActiveTasks);
        }
    }

    // overwrite the local server-side fields from a snapshot (used on the server when an op pushes)
    public static void applyServerConfig(ServerConfig sc) {
        DATA.enabled = sc.enabled();
        DATA.generationRadius = clamp(sc.generationRadius(), 1, 512);
        DATA.update_interval = clamp(sc.updateInterval(), 1, 200);
        DATA.maxQueueSize = Math.max(0, sc.maxQueueSize());
        DATA.maxActiveTasks = clamp(sc.maxActiveTasks(), 1, 128);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
