package com.aerosmp.voxy.client;

import net.caffeinemc.mods.sodium.api.config.*;
import net.caffeinemc.mods.sodium.api.config.option.Range;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

@ConfigEntryPointForge("voxy")
public final class SettingsMenu implements ConfigEntryPoint {
    public void registerConfigLate(ConfigBuilder builder) {
        var enabled = builder.createBooleanOption(id("enabled"));
        enabled.setName(Component.literal("Render distant terrain"));
        enabled.setTooltip(Component.literal("Show locally cached Voxy terrain and load missing visible detail."));
        enabled.setBinding(value -> ClientSettings.rendering = value, () -> ClientSettings.rendering);
        enabled.setDefaultValue(true).setStorageHandler(ClientSettings::save);
        var distance = builder.createIntegerOption(id("distance"));
        distance.setName(Component.literal("Render distance"));
        distance.setTooltip(Component.literal("Maximum distant-terrain range in blocks."));
        distance.setBinding(value -> ClientSettings.distance = value, () -> ClientSettings.distance);
        distance.setDefaultValue(2048).setStorageHandler(ClientSettings::save);
        distance.setRange(new Range(32, 8192, 32)).setValueFormatter(value -> Component.literal(value + " blocks"));
        var pixels = builder.createIntegerOption(id("pixels"));
        pixels.setName(Component.literal("LOD pixel size"));
        pixels.setTooltip(Component.literal("Projected section size in render pixels. Smaller values give more detail. Zoom automatically increases detail; cached terrain is used first."));
        pixels.setBinding(value -> ClientSettings.pixels = ClientSettings.fromSlider(value), () -> ClientSettings.toSlider(ClientSettings.pixels));
        pixels.setDefaultValue(ClientSettings.toSlider(64)).setStorageHandler(ClientSettings::save);
        pixels.setRange(new Range(0, 100, 1)).setValueFormatter(value -> Component.literal(Math.round(
                value == ClientSettings.toSlider(ClientSettings.pixels) ? ClientSettings.pixels : ClientSettings.fromSlider(value)) + " px"));
        builder.registerModOptions("voxy", "Voxy", "Rewrite")
                .addPage(builder.createOptionPage().setName(Component.literal("Terrain"))
                        .addOptionGroup(builder.createOptionGroup().addOption(enabled).addOption(distance).addOption(pixels)));
    }
    private static ResourceLocation id(String value) { return ResourceLocation.fromNamespaceAndPath("voxy", value); }
}
