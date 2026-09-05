package me.cortex.voxy.client.config;

import com.google.gson.Gson;
import net.caffeinemc.mods.sodium.client.config.builder.ConfigBuilderImpl;
import net.caffeinemc.mods.sodium.client.config.structure.Config;
import net.caffeinemc.mods.sodium.client.config.structure.IntegerOption;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import java.util.ArrayList;

/** Tests the actual config adapter, historical key and shared UI mapping without Minecraft/GL. */
public final class LodPixelSizeBehaviorTest {
    public static void run() throws Exception {
        var field = VoxyConfig.class.getDeclaredField("GSON"); field.setAccessible(true);
        Gson gson = (Gson) field.get(null);
        check(gson.fromJson("{}", VoxyConfig.class).getSubDivisionSize() == 64, "missing quality default");
        for (float value : new float[]{28, 64, 128, 256, 1, 4096, 0, -1,
                Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
            VoxyConfig config = gson.fromJson("{}", VoxyConfig.class);
            config.subDivisionSize = value;
            float expected = LodPixelSize.validate(value);
            check(config.getSubDivisionSize() == expected, "upload getter accepts invalid quality");
            // The actual save path validates even when Voxy is unavailable in a headless JVM.
            config.save();
            check(config.subDivisionSize == expected, "save did not normalize invalid quality");
            String json = gson.toJson(config);
            check(json.contains("\"sub_division_size\""), "historical serialized key changed");
            check(gson.fromJson(json, VoxyConfig.class).subDivisionSize == expected, "quality round-trip failed");
        }
        check(gson.fromJson("{\"sub_division_size\":128}", VoxyConfig.class).getSubDivisionSize() == 128,
                "historical preference not restored");
        check(LodPixelSize.validate(1) == 28 && LodPixelSize.validate(4096) == 256,
                "finite positive out-of-range values not clamped");
        check(LodPixelSize.validate(0) == 64 && LodPixelSize.validate(-1) == 64
                && LodPixelSize.validate(Float.NaN) == 64, "invalid values did not use default");
        float previous = 0;
        for (int position = 0; position <= 100; position++) {
            float pixels = LodPixelSize.fromSlider(position);
            check(pixels > previous && LodPixelSize.toSlider(pixels) == position, "logarithmic slider not monotonic/invertible");
            check(LodPixelSize.label(position, 64).endsWith(" px"), "slider units changed");
            previous = pixels;
        }
        check(LodPixelSize.fromSlider(0) == 28 && LodPixelSize.fromSlider(100) == 256,
                "historical slider bounds changed");
        check(LodPixelSize.label(LodPixelSize.toSlider(64), 64).equals("64 px"), "opening menu relabels default incorrectly");
        applyAndCancel();
        System.out.println("pixel-size config/serialization/logarithmic mapping tests passed");
    }

    private static void applyAndCancel() {
        float original = VoxyConfig.CONFIG.subDivisionSize;
        try {
            VoxyConfig.CONFIG.subDivisionSize = 64;
            ConfigBuilderImpl builder = new ConfigBuilderImpl(ignored -> null, "voxy");
            var group = builder.createOptionGroup();
            for (String key : new String[]{"enabled", "rendering"}) {
                group.addOption(builder.createBooleanOption(ResourceLocation.fromNamespaceAndPath("voxy", key))
                        .setName(Component.literal(key)).setTooltip(Component.literal(key))
                        .setStorageHandler(() -> {}).setBinding(ignored -> {}, () -> true).setDefaultValue(true));
            }
            group.addOption(VoxyConfigMenu.pixelSizeOption(builder));
            builder.registerModOptions("voxy", "Voxy", "test")
                    .addPage(builder.createOptionPage().setName(Component.literal("Rendering")).addOptionGroup(group));
            Config config = new Config(new ArrayList<>(builder.build()));
            IntegerOption option = (IntegerOption) config.getOption(ResourceLocation.fromNamespaceAndPath("voxy", "subdivsize"));
            check((option.getFlags() == null || option.getFlags().isEmpty()) && option.getApplyHook() == null,
                    "pixel slider requests reload/session hook");
            check(option.formatValue(option.getAppliedValue()).getString().equals("64 px"), "UI default display mismatch");
            config.applyAllOptions();
            check(VoxyConfig.CONFIG.subDivisionSize == 64, "unmodified Apply quantized historical setting");
            for (int position : new int[]{0, 100, 40, 0, 100}) {
                float before = VoxyConfig.CONFIG.subDivisionSize;
                option.modifyValue(position);
                check(VoxyConfig.CONFIG.subDivisionSize == before, "dragging slider applied immediately");
                config.resetAllOptionsFromBindings();
                check(VoxyConfig.CONFIG.subDivisionSize == before && !option.hasChanged(), "Cancel changed quality");
                option.modifyValue(position);
                config.applyAllOptions();
                check(VoxyConfig.CONFIG.subDivisionSize == LodPixelSize.fromSlider(position), "Apply did not update quality");
            }
        } finally { VoxyConfig.CONFIG.subDivisionSize = original; }
    }

    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
