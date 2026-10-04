package com.aerosmp.voxy.client;

import com.aerosmp.voxy.Common;

import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

final class ClientSettings {
    private static final Path FILE =
            Minecraft.getInstance().gameDirectory.toPath().resolve("config/voxy.properties");
    static volatile boolean rendering = true;
    static volatile int distance = Integer.getInteger("voxy.viewDistance", 2048);
    static volatile float pixels = 64;

    static {
        try {
            Properties values = new Properties();
            if (Files.isRegularFile(FILE))
                try (var input = Files.newInputStream(FILE)) {
                    values.load(input);
                }
            rendering = Boolean.parseBoolean(values.getProperty("rendering", "true"));
            distance =
                    Math.clamp(
                            Integer.parseInt(
                                    values.getProperty("distance", Integer.toString(distance))),
                            32,
                            8192);
            pixels = validate(Float.parseFloat(values.getProperty("pixels", "64")));
        } catch (Exception failure) {
            System.err.println("Voxy settings: " + failure);
        }
    }

    static float validate(float value) {
        return Float.isFinite(value) ? Math.clamp(value, 28, 256) : 64;
    }

    static float fromSlider(int value) {
        return (float) (28 * Math.exp(Math.log(256.0 / 28) * value / 100));
    }

    static int toSlider(float value) {
        return Math.clamp(
                (int) Math.round(Math.log(validate(value) / 28) / Math.log(256.0 / 28) * 100),
                0,
                100);
    }

    static void save() {
        try {
            Common.atomic(
                    FILE,
                    ("rendering="
                                    + rendering
                                    + "\ndistance="
                                    + distance
                                    + "\npixels="
                                    + pixels
                                    + "\n")
                            .getBytes(StandardCharsets.UTF_8));
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
