package me.cortex.voxy.client.lod;

import me.cortex.voxy.commonImpl.lod.LodStreamingConfig;
import me.cortex.voxy.client.lod.ClientLodNetwork;
import me.cortex.voxy.commonImpl.lod.LodNetwork;
import me.shedaniel.clothconfig2.api.AbstractConfigListEntry;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

public final class LodConfigScreen {

    private LodConfigScreen() {}

    public static Screen createConfigScreen(Screen parent) {
        ConfigBuilder builder = ConfigBuilder.create()
                .setParentScreen(parent)
                .setTitle(Component.translatable("voxy.config.lod_streaming.title"));

        ConfigEntryBuilder entryBuilder = builder.entryBuilder();

        buildClientCategory(builder, entryBuilder);
        java.util.function.Supplier<LodStreamingConfig.ServerConfig> edits = buildServerCategory(builder, entryBuilder);

        builder.setSavingRunnable(() -> {
            // client values save locally regardless
            LodStreamingConfig.save();

            boolean remote = isOnRemoteServer();
            if (!remote) {
                LodStreamingConfig.applyServerConfig(edits.get());
                LodStreamingConfig.save();
                me.cortex.voxy.commonImpl.lod.LodGenerationService.getInstance().scheduleConfigReload();
            } else if (ClientLodNetwork.canEditServerConfig()) {
                PacketDistributor.sendToServer(new LodNetwork.ServerConfigPushPayload(edits.get()));
            }
        });

        return builder.build();
    }

    private static void buildClientCategory(ConfigBuilder builder, ConfigEntryBuilder entryBuilder) {
        ConfigCategory client = builder.getOrCreateCategory(Component.translatable("voxy.config.lod_streaming.category.client"));

        client.addEntry(entryBuilder.startBooleanToggle(Component.translatable("voxy.config.lod_streaming.option.f3_stats"), LodStreamingConfig.DATA.showF3MenuStats)
                .setDefaultValue(true)
                .setTooltip(Component.translatable("voxy.config.lod_streaming.option.f3_stats.tooltip"))
                .setSaveConsumer(v -> LodStreamingConfig.DATA.showF3MenuStats = v)
                .build());
    }

    private static java.util.function.Supplier<LodStreamingConfig.ServerConfig> buildServerCategory(ConfigBuilder builder, ConfigEntryBuilder entryBuilder) {
        ConfigCategory server = builder.getOrCreateCategory(Component.translatable("voxy.config.lod_streaming.category.server"));

        // current server values, synced from the server when connected, else the local config
        LodStreamingConfig.ServerConfig sc = ClientLodNetwork.getServerConfig();

        boolean editable = !isOnRemoteServer() || ClientLodNetwork.canEditServerConfig();

        if (isOnRemoteServer() && !editable) {
            server.addEntry(entryBuilder.startTextDescription(
                    Component.translatable("voxy.config.lod_streaming.server.readonly")).build());
        }

        var enabled = entryBuilder.startBooleanToggle(Component.translatable("voxy.config.lod_streaming.option.enabled"), sc.enabled())
                .setDefaultValue(true)
                .setTooltip(Component.translatable("voxy.config.lod_streaming.option.enabled.tooltip"))
                .build();
        var radius = entryBuilder.startIntSlider(Component.translatable("voxy.config.lod_streaming.option.radius"), sc.generationRadius(), 1, 512)
                .setDefaultValue(128)
                .setTooltip(Component.translatable("voxy.config.lod_streaming.option.radius.tooltip"))
                .build();
        var updateInterval = entryBuilder.startIntSlider(Component.translatable("voxy.config.lod_streaming.option.update_interval"), sc.updateInterval(), 1, 200)
                .setDefaultValue(20)
                .setTooltip(Component.translatable("voxy.config.lod_streaming.option.update_interval.tooltip"))
                .build();
        var maxQueue = entryBuilder.startIntField(Component.translatable("voxy.config.lod_streaming.option.max_queue"), sc.maxQueueSize())
                .setDefaultValue(20000)
                .setTooltip(Component.translatable("voxy.config.lod_streaming.option.max_queue.tooltip"))
                .build();
        var maxActive = entryBuilder.startIntSlider(Component.translatable("voxy.config.lod_streaming.option.max_active"), sc.maxActiveTasks(), 1, 128)
                .setDefaultValue(20)
                .setTooltip(Component.translatable("voxy.config.lod_streaming.option.max_active.tooltip"))
                .build();

        enabled.setEditable(editable);
        radius.setEditable(editable);
        updateInterval.setEditable(editable);
        maxQueue.setEditable(editable);
        maxActive.setEditable(editable);

        addAll(server, enabled, radius, updateInterval, maxQueue, maxActive);

        return () -> new LodStreamingConfig.ServerConfig(enabled.getValue(), radius.getValue(), updateInterval.getValue(), maxQueue.getValue(), maxActive.getValue());
    }

    private static void addAll(ConfigCategory cat, AbstractConfigListEntry<?>... entries) {
        for (AbstractConfigListEntry<?> e : entries) cat.addEntry(e);
    }

    // true if connected to a server other than our own integrated (singleplayer) one
    private static boolean isOnRemoteServer() {
        Minecraft mc = Minecraft.getInstance();
        return mc.getConnection() != null && !mc.hasSingleplayerServer();
    }
}
