package me.cortex.voxy.client.config;

import me.cortex.voxy.client.VoxyClient;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.SSAO;
import me.cortex.voxy.client.iris.IrisUtil;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPointForge;
import net.caffeinemc.mods.sodium.api.config.ConfigState;
import net.caffeinemc.mods.sodium.api.config.option.OptionFlag;
import net.caffeinemc.mods.sodium.api.config.option.OptionImpact;
import net.caffeinemc.mods.sodium.api.config.option.Range;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.IntegerOptionBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.ModOptionsBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.OptionBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.OptionGroupBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.StatefulOptionBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.function.Consumer;
import java.util.function.Supplier;

@ConfigEntryPointForge("voxy")
public class VoxyConfigMenu implements ConfigEntryPoint {
    private static final VoxyConfig CFG = VoxyConfig.CONFIG;
    private static final ResourceLocation ENABLED = id("enabled");
    private static final ResourceLocation IRIS_RELOAD = id("iris_reload");
    private static final ResourceLocation RENDERING = id("rendering");
    private static final ResourceLocation RENDER_DISTANCE = id("render_distance");
    private static final ResourceLocation RENDER_RELOAD = OptionFlag.REQUIRES_RENDERER_RELOAD.getId();

    @Override
    public void registerConfigLate(ConfigBuilder builder) {
        if (!VoxyClient.isAvailable()) return;

        var options = builder.registerModOptions("voxy", "Voxy", VoxyClient.MOD_VERSION)
                .setIcon(ResourceLocation.parse("voxy:icon.png"));

        var enabled = option(builder.createBooleanOption(ENABLED), "voxy.config.general.enabled",
                () -> CFG.enabled, value -> {
                    CFG.enabled = value;
                    if (value && VoxyClient.inSession) {
                        VoxyClient.createRuntime();
                    }
                }, ENABLED, RENDER_RELOAD, IRIS_RELOAD);

        options.addPage(builder.createOptionPage()
                .setName(Component.translatable("voxy.config.general"))
                .addOptionGroup(group(builder, enabled)));

        var rendering = option(builder.createBooleanOption(RENDERING),
                "voxy.config.general.rendering", () -> CFG.enableRendering,
                value -> CFG.enableRendering = value, RENDERING, IRIS_RELOAD)
                .setEnabledProvider(VoxyConfigMenu::voxyEnabled, ENABLED);

        var subdivisionSize = option(builder.createIntegerOption(id("subdivsize")),
                "voxy.config.general.subDivisionSize", () -> subDiv2ln(CFG.subDivisionSize),
                value -> CFG.subDivisionSize = ln2subDiv(value))
                .setRange(new Range(0, SUBDIV_IN_MAX, 1))
                .setValueFormatter(value -> Component.literal(Integer.toString(Math.round(ln2subDiv(value)))))
                .setImpact(OptionImpact.HIGH)
                .setEnabledProvider(VoxyConfigMenu::renderingEnabled, ENABLED, RENDERING);

        var renderDistance = option(builder.createIntegerOption(RENDER_DISTANCE),
                "voxy.config.general.renderDistance", () -> Math.round(CFG.sectionRenderDistance * 16),
                value -> CFG.sectionRenderDistance = (float) value / 16, RENDER_DISTANCE)
                .setRange(new Range(10, 64 * 16, 1))
                .setValueFormatter(value -> Component.literal(Integer.toString(value * 2)))
                .setImpact(OptionImpact.MEDIUM)
                .setEnabledProvider(VoxyConfigMenu::renderingEnabled, ENABLED, RENDERING);

        var environmentalFog = option(builder.createBooleanOption(id("eviromental_fog")),
                "voxy.config.general.environmental_fog", () -> CFG.useEnvironmentalFog,
                value -> CFG.useEnvironmentalFog = value, RENDER_RELOAD)
                .setEnabledProvider(VoxyConfigMenu::renderingEnabled, ENABLED, RENDERING);

        var ssao = option(builder.createEnumOption(id("ssao_mode"), SSAO.SSAOMode.class),
                "voxy.config.general.ssao_mode", CFG::getSSAOMode, CFG::setSSAOMode, RENDER_RELOAD)
                .setElementNameProvider(value -> Component.literal(value == null ? "NULL" : value.toString()))
                .setImpact(OptionImpact.MEDIUM)
                .setEnabledProvider(VoxyConfigMenu::renderingEnabled, ENABLED, RENDERING);

        var adaptCloudDistance = option(builder.createBooleanOption(id("adapt_cloud_distance")),
                "voxy.config.general.adaptCloudDistance", () -> CFG.adaptCloudDistance,
                value -> CFG.adaptCloudDistance = value, RENDER_RELOAD)
                .setEnabledProvider(VoxyConfigMenu::renderingEnabled, ENABLED, RENDERING);

        var cloudDistance = option(builder.createIntegerOption(id("cloud_distance")),
                "voxy.config.general.cloudDistance", () -> CFG.cloudDistance,
                value -> CFG.cloudDistance = value, RENDER_RELOAD)
                .setRange(new Range(0, 1024, 1))
                .setImpact(OptionImpact.LOW)
                .setEnabledProvider(VoxyConfigMenu::renderingEnabled, ENABLED, RENDERING);

        var fogIntensity = option(builder.createIntegerOption(id("fog_intensity")),
                "voxy.config.general.fogIntensity", () -> Math.round(CFG.fogIntensity * 100),
                value -> CFG.fogIntensity = value / 100.0f, RENDER_RELOAD)
                .setRange(new Range(0, 100, 1))
                .setImpact(OptionImpact.LOW)
                .setEnabledProvider(VoxyConfigMenu::fogOptionsEnabled,
                        ENABLED, RENDERING, ConfigState.UPDATE_ON_REBUILD);

        var fogDensity = option(builder.createIntegerOption(id("fog_density")),
                "voxy.config.general.fogDensity", () -> Math.round(CFG.fogDensity * 100),
                value -> CFG.fogDensity = value / 100.0f, RENDER_RELOAD)
                .setRange(new Range(0, 100, 1))
                .setImpact(OptionImpact.LOW)
                .setEnabledProvider(VoxyConfigMenu::fogOptionsEnabled,
                        ENABLED, RENDERING, ConfigState.UPDATE_ON_REBUILD);

        var skyFogDistance = option(builder.createIntegerOption(id("sky_fog_distance")),
                "voxy.config.general.skyFogDistance", () -> CFG.skyFogDistance,
                value -> CFG.skyFogDistance = value, RENDER_RELOAD)
                .setRange(new Range(0, 1024, 1))
                .setImpact(OptionImpact.LOW)
                .setEnabledProvider(VoxyConfigMenu::fogOptionsEnabled,
                        ENABLED, RENDERING, ConfigState.UPDATE_ON_REBUILD);

        options.addPage(builder.createOptionPage()
                .setName(Component.translatable("voxy.config.rendering"))
                .addOptionGroup(group(builder, rendering))
                .addOptionGroup(group(builder, subdivisionSize, renderDistance))
                .addOptionGroup(group(builder, environmentalFog, ssao))
                .addOptionGroup(group(builder, adaptCloudDistance, cloudDistance))
                .addOptionGroup(group(builder, fogIntensity, fogDensity, skyFogDistance)));

        registerApplyHooks(options);
    }

    private static void registerApplyHooks(ModOptionsBuilder options) {
        options.registerFlagHook((identifiers, state) -> {
            for (var identifier : identifiers) {
                if (identifier.equals(IRIS_RELOAD)) {
                    IrisUtil.reload();
                } else if (identifier.equals(ENABLED)) {
                    if (!CFG.enabled) {
                        var renderer = (IGetVoxyRenderSystem) Minecraft.getInstance().levelRenderer;
                        if (renderer != null) renderer.voxy$shutdownRenderer();
                        VoxyClient.shutdownRuntime();
                    }
                } else if (identifier.equals(RENDERING)) {
                    if (!identifiers.contains(ENABLED) && !identifiers.contains(RENDER_RELOAD)) {
                        var renderer = (IGetVoxyRenderSystem) Minecraft.getInstance().levelRenderer;
                        if (renderer != null) {
                            if (CFG.enableRendering) renderer.voxy$createRenderer();
                            else renderer.voxy$shutdownRenderer();
                        }
                    }
                } else if (identifier.equals(RENDER_DISTANCE)) {
                    if (!identifiers.contains(ENABLED) && !identifiers.contains(RENDERING)
                            && !identifiers.contains(RENDER_RELOAD)) {
                        var renderer = (IGetVoxyRenderSystem) Minecraft.getInstance().levelRenderer;
                        if (renderer != null && renderer.voxy$getRenderSystem() != null) {
                            renderer.voxy$getRenderSystem().setRenderDistance(CFG.sectionRenderDistance);
                        }
                    }
                }
            }
        }, IRIS_RELOAD, ENABLED, RENDERING, RENDER_DISTANCE);
    }

    private static <T, B extends StatefulOptionBuilder<T>> B option(B builder, String translation,
                                                                     Supplier<T> getter, Consumer<T> setter,
                                                                     ResourceLocation... flags) {
        builder.setName(Component.translatable(translation));
        builder.setTooltip(Component.translatable(translation + ".tooltip"));
        if (flags.length != 0) builder.setFlags(flags);
        builder.setBinding(setter, getter);
        builder.setStorageHandler(CFG::save);
        builder.setDefaultValue(getter.get());
        if (builder instanceof IntegerOptionBuilder integer) {
            integer.setValueFormatter(value -> Component.literal(Integer.toString(value)));
        }
        return builder;
    }

    private static OptionGroupBuilder group(ConfigBuilder builder, OptionBuilder... options) {
        var group = builder.createOptionGroup();
        for (var option : options) group.addOption(option);
        return group;
    }

    private static boolean voxyEnabled(ConfigState state) {
        return state.readBooleanOption(ENABLED);
    }

    private static boolean renderingEnabled(ConfigState state) {
        return voxyEnabled(state) && state.readBooleanOption(RENDERING);
    }

    private static boolean fogOptionsEnabled(ConfigState state) {
        return !IrisUtil.irisShadersEnabledInConfig() && renderingEnabled(state);
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("voxy", path);
    }

    private static final int SUBDIV_IN_MAX = 100;
    private static final double SUBDIV_MIN = 28;
    private static final double SUBDIV_MAX = 256;
    private static final double SUBDIV_CONST = Math.log(SUBDIV_MAX / SUBDIV_MIN) / Math.log(2);

    private static float ln2subDiv(int value) {
        return (float) (SUBDIV_MIN * Math.pow(2, SUBDIV_CONST * ((double) value / SUBDIV_IN_MAX)));
    }

    private static int subDiv2ln(float value) {
        return (int) ((Math.log((double) value / SUBDIV_MIN) / Math.log(2) / SUBDIV_CONST) * SUBDIV_IN_MAX);
    }
}
