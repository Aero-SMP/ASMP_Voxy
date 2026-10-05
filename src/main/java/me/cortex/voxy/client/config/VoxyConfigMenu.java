package me.cortex.voxy.client.config;

import me.cortex.voxy.client.VoxyClient;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.RenderResourceReuse;
import me.cortex.voxy.client.core.SSAO;
import me.cortex.voxy.client.iris.IrisUtil;
import me.cortex.voxy.client.lod.ClientSession;
import me.cortex.voxy.common.Logger;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPointForge;
import net.caffeinemc.mods.sodium.api.config.ConfigState;
import net.caffeinemc.mods.sodium.api.config.option.OptionFlag;
import net.caffeinemc.mods.sodium.api.config.option.OptionImpact;
import net.caffeinemc.mods.sodium.api.config.option.Range;
import net.caffeinemc.mods.sodium.api.config.option.SteppedValidator;
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
    private static final ResourceLocation GEOMETRY_MEMORY = id("geometry_memory");
    private static final ResourceLocation RENDER_DISTANCE = id("render_distance");
    private static final ResourceLocation STREAMING_SETTINGS = id("streaming_settings");
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

        var renderDistance = option(builder.createIntegerOption(RENDER_DISTANCE),
                "voxy.config.general.renderDistance", () -> Math.round(CFG.sectionRenderDistance * 16),
                value -> CFG.sectionRenderDistance = (float) value / 16, RENDER_DISTANCE)
                .setRange(new Range(10, 64 * 16, 1))
                .setValueFormatter(value -> Component.literal(Integer.toString(value * 2)))
                .setImpact(OptionImpact.MEDIUM)
                .setEnabledProvider(VoxyConfigMenu::renderingEnabled, ENABLED, RENDERING);

        var pixelSize = pixelSizeOption(builder);

        var updateInterval = option(builder.createIntegerOption(id("background_update_interval")),
                "voxy.config.streaming.update_interval", CFG::getBackgroundUpdateIntervalSeconds,
                value -> CFG.backgroundUpdateIntervalSeconds = value, STREAMING_SETTINGS)
                .setRange(new Range(1, 60, 1))
                .setValueFormatter(value -> Component.literal(value + " s"))
                .setEnabledProvider(VoxyConfigMenu::voxyEnabled, ENABLED);

        int[] geometryMemoryChoices = GeometryMemoryOptions.available(
                RenderResourceReuse.getSafeGeometryMemoryLimitBytes());
        Logger.info("GPU Memory slider maximum is "
                + geometryMemoryChoices[geometryMemoryChoices.length - 1] + " MiB");
        var geometryMemory = option(builder.createIntegerOption(GEOMETRY_MEMORY),
                "voxy.config.general.geometry_memory",
                () -> geometryMemoryIndex(effectiveConfiguredGeometryMemoryMib(), geometryMemoryChoices),
                value -> CFG.geometryMemoryMib = geometryMemoryChoices[value], RENDER_RELOAD)
                .setRange(new Range(0, geometryMemoryChoices.length - 1, 1))
                .setValueFormatter(value -> geometryMemoryLabel(geometryMemoryChoices[value]))
                .setImpact(OptionImpact.HIGH)
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

        var renderingPage = builder.createOptionPage()
                .setName(Component.translatable("voxy.config.rendering"))
                .addOptionGroup(group(builder, rendering))
                .addOptionGroup(group(builder, renderDistance, pixelSize, geometryMemory))
                .addOptionGroup(group(builder, updateInterval));
        renderingPage.addOptionGroup(serverDownloadOptions(builder));
        renderingPage
                .addOptionGroup(group(builder, environmentalFog, ssao))
                .addOptionGroup(group(builder, adaptCloudDistance, cloudDistance))
                .addOptionGroup(group(builder, fogIntensity, fogDensity, skyFogDistance));
        options.addPage(renderingPage);

        registerApplyHooks(options);
    }

    private static void registerApplyHooks(ModOptionsBuilder options) {
        options.registerFlagHook((identifiers, state) -> {
            for (var identifier : identifiers) {
                if (identifier.equals(IRIS_RELOAD)) {
                    IrisUtil.reload();
                } else if (identifier.equals(STREAMING_SETTINGS)) {
                    ClientSession.streamingSettingsChanged();
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
        }, IRIS_RELOAD, ENABLED, RENDERING, RENDER_DISTANCE, STREAMING_SETTINGS);
    }

    private static OptionGroupBuilder serverDownloadOptions(ConfigBuilder builder) {
        var group = builder.createOptionGroup();
        var bandwidth = option(builder.createIntegerOption(id("download_bandwidth")),
                "voxy.config.streaming.bandwidth", () -> {
                    var policy = ServerDownloadSettings.current();
                    return policy == null ? ServerDownloadSettings.DEFAULT_KBPS : policy.downloadKbps();
                }, value -> { var policy = ServerDownloadSettings.current(); if (policy != null) policy.setDownloadKbps(value); }, STREAMING_SETTINGS)
                .setRange(new Range(ServerDownloadSettings.MIN_KBPS, ServerDownloadSettings.MAX_KBPS, 100))
                .setValueFormatter(value -> Component.literal(value < 1000 ? value + " kbps"
                        : String.format(java.util.Locale.ROOT, "%.1f Mbps", value / 1000.0)))
                .setEnabledProvider(state -> ServerDownloadSettings.current() != null && voxyEnabled(state),
                        ENABLED, ConfigState.UPDATE_ON_REBUILD);
        bandwidth.setTooltip(value -> {
            var policy = ServerDownloadSettings.current();
            return policy == null ? Component.translatable("voxy.config.streaming.no_server")
                    : Component.translatable("voxy.config.streaming.bandwidth.tooltip", policy.serverId());
        });
        bandwidth.setDefaultValue(ServerDownloadSettings.DEFAULT_KBPS);
        bandwidth.setStorageHandler(VoxyConfigMenu::saveCurrentServerPolicy);

        var values = new CacheStorageOptions();
        var storage = option(builder.createIntegerOption(id("cache_storage")),
                "voxy.config.streaming.storage", values::index, values::apply, STREAMING_SETTINGS)
                .setValidator(values)
                .setValueFormatter(values::label)
                .setEnabledProvider(state -> ServerDownloadSettings.current() != null && voxyEnabled(state),
                        ENABLED, ConfigState.UPDATE_ON_REBUILD);
        storage.setTooltip(value -> {
            var policy = ServerDownloadSettings.current();
            return policy == null ? Component.translatable("voxy.config.streaming.no_server")
                    : Component.translatable("voxy.config.streaming.storage.tooltip", policy.serverId())
                    .append(Component.literal("\n" + ClientSession.storageStatus(policy.serverId())));
        });
        storage.setDefaultProvider(state -> values.defaultIndex(), ConfigState.UPDATE_ON_REBUILD);
        storage.setStorageHandler(VoxyConfigMenu::saveCurrentServerPolicy);
        group.addOption(bandwidth);
        group.addOption(storage);
        return group;
    }

    private static void saveCurrentServerPolicy() {
        var policy = ServerDownloadSettings.current();
        if (policy != null) policy.save();
    }

    /** Refresh choices with binding resets when a menu opens, keeping slider indices stable while editing. */
    private static final class CacheStorageOptions implements SteppedValidator {
        private String serverId;
        private long[] choices;

        private ServerDownloadSettings select(boolean rebuild) {
            var policy = ServerDownloadSettings.current();
            String selected = policy == null ? null : policy.serverId();
            long bytes = policy == null ? ServerDownloadSettings.DEFAULT_STORAGE_BYTES : policy.storageBytes();
            if (rebuild || this.choices == null || !java.util.Objects.equals(this.serverId, selected)
                    || bytes != Long.MAX_VALUE && java.util.Arrays.binarySearch(this.choices, bytes) < 0) {
                this.serverId = selected;
                this.choices = storageChoices(policy);
            }
            return policy;
        }

        int index() {
            var policy = select(true);
            return policy != null && policy.entireWorld() ? this.choices.length : java.util.Arrays.binarySearch(this.choices,
                    policy == null ? ServerDownloadSettings.DEFAULT_STORAGE_BYTES : policy.storageBytes());
        }

        void apply(int value) {
            var policy = select(false);
            if (policy != null) policy.setStorageBytes(value == this.choices.length ? Long.MAX_VALUE : this.choices[value]);
        }

        @Override public int min() { return 0; }
        @Override public int max() { select(false); return this.choices.length; }
        @Override public int step() { return 1; }
        int defaultIndex() { select(false); return java.util.Arrays.binarySearch(this.choices, ServerDownloadSettings.DEFAULT_STORAGE_BYTES); }
        Component label(int value) {
            select(false);
            return value == this.choices.length ? Component.translatable("voxy.config.streaming.entire_world")
                    : Component.literal(storageLabel(this.choices[value]));
        }
    }

    private static long[] storageChoices(ServerDownloadSettings policy) {
        var choices = new java.util.TreeSet<Long>();
        choices.add(ServerDownloadSettings.DEFAULT_STORAGE_BYTES);
        long current = policy == null || policy.entireWorld() ? ServerDownloadSettings.DEFAULT_STORAGE_BYTES : policy.storageBytes();
        choices.add(current);
        long maximum = Math.min(Long.MAX_VALUE - 1, Math.max(current,
                Math.max(ServerDownloadSettings.DEFAULT_STORAGE_BYTES, policy == null ? 0 : policy.estimatedWorldBytes())));
        for (long bytes = ServerDownloadSettings.MIN_STORAGE_BYTES; bytes < maximum;) {
            choices.add(bytes);
            if (bytes > maximum / 2) break;
            bytes *= 2;
        }
        choices.add(maximum);
        return choices.stream().mapToLong(Long::longValue).toArray();
    }

    private static String storageLabel(long bytes) {
        double unit = bytes < 1_000_000_000 ? 1_000_000.0 : 1_000_000_000.0;
        return String.format(java.util.Locale.ROOT, "%.1f %s", bytes / unit, bytes < 1_000_000_000 ? "MB" : "GB");
    }

    static IntegerOptionBuilder pixelSizeOption(ConfigBuilder builder) {
        return option(builder.createIntegerOption(id("subdivsize")),
                "voxy.config.general.subDivisionSize",
                () -> LodPixelSize.toSlider(CFG.getSubDivisionSize()),
                value -> CFG.subDivisionSize = LodPixelSize.fromSlider(value))
                .setRange(new Range(0, LodPixelSize.SLIDER_MAX, 1))
                .setValueFormatter(value -> Component.literal(LodPixelSize.label(value, CFG.getSubDivisionSize())))
                .setImpact(OptionImpact.HIGH)
                .setEnabledProvider(VoxyConfigMenu::renderingEnabled, ENABLED, RENDERING);
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

    private static int effectiveConfiguredGeometryMemoryMib() {
        if (CFG.geometryMemoryMib > 0) return CFG.geometryMemoryMib;
        return GeometryMemoryOptions.maximum(RenderResourceReuse.getSafeGeometryMemoryLimitBytes());
    }

    private static int geometryMemoryIndex(int configuredMib, int[] choices) {
        int nearest = 0;
        int nearestDistance = Math.abs(configuredMib - choices[0]);
        for (int i = 1; i < choices.length; i++) {
            int distance = Math.abs(configuredMib - choices[i]);
            if (distance < nearestDistance) {
                nearest = i;
                nearestDistance = distance;
            }
        }
        return nearest;
    }

    private static Component geometryMemoryLabel(int mib) {
        if (mib % 1024 == 0) return Component.literal((mib / 1024) + " GiB");
        return Component.literal(mib + " MiB");
    }
}
