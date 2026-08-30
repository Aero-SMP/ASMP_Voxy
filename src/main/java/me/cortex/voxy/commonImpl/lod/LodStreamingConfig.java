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
        public boolean enabled = true;
        public int generationRadius = 128;
        public int update_interval = 20; // legacy field for compat
        public int maxQueueSize = 20000;
        public int maxActiveTasks = 20;

    }

}
