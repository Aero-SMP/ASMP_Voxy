package com.aerosmp.voxy.client;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL43C.*;

import com.aerosmp.voxy.Common.*;
import com.aerosmp.voxy.mixin.VoxyMixins;
import com.aerosmp.voxy.update.AutoUpdater;
import com.mojang.blaze3d.vertex.*;

import net.caffeinemc.mods.sodium.api.config.*;
import net.caffeinemc.mods.sodium.api.config.option.*;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.*;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.renderer.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.*;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.material.*;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.*;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.HandlerThread;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

@Mod(value = "voxy", dist = Dist.CLIENT)
public final class VoxyClient {
    private AutoUpdater updater;
    private volatile TerrainSession terrain;
    private net.minecraft.client.multiplayer.ClientLevel level;
    private long nextJoin, nextDiscovery, nextControl, modified = -1;
    private boolean network = true, render = true, clipping = true;
    private float pixels = Float.NaN;
    private Integer originalFov;

    public VoxyClient(IEventBus modBus) {
        AutoUpdater.starting(net.neoforged.fml.loading.FMLPaths.GAMEDIR.get());
        modBus.addListener(this::registerPayload);
        modBus.addListener(TerrainRenderer::registerShaders);
        NeoForge.EVENT_BUS.addListener(this::tick);
    }

    private void registerPayload(RegisterPayloadHandlersEvent event) {
        event.registrar("voxy")
                .optional()
                .executesOn(HandlerThread.NETWORK)
                .playBidirectional(
                        Endpoint.TYPE,
                        Endpoint.CODEC,
                        (value, context) -> {
                            TerrainSession session = terrain;
                            if (session == null || value.port() == 0) return;
                            Endpoint previous = session.endpoint;
                            if (previous == null
                                    || previous.port() != value.port()
                                    || !previous.host().equals(value.host())
                                    || !Arrays.equals(previous.certificate(), value.certificate()))
                                session.endpoint = value;
                        });
    }

    private void tick(ClientTickEvent.Post event) {
        Minecraft client = Minecraft.getInstance();
        boolean managed = client.getUser() != null && client.getUser().getName().equals("MGengine");
        if (managed) control(client);
        if (client.level != level || !ClientSettings.rendering && terrain != null) reset();
        if (ClientSettings.rendering
                && client.level != null
                && client.player != null
                && terrain == null) {
            level = client.level;
            String address =
                    client.getCurrentServer() == null ? "local" : client.getCurrentServer().ip;
            try {
                terrain =
                        new TerrainSession(
                                client.gameDirectory.toPath().resolve(".voxy/terrain"),
                                address,
                                level.dimension().location().toString(),
                                this::reset);
            } catch (IOException failure) {
                System.err.println("Voxy cache: " + failure);
            }
        }
        if (terrain != null) {
            terrain.network(network);
            terrain.renderer.enabled = render;
            terrain.renderer.clipping = clipping;
            terrain.renderer.pixelOverride = pixels;
            terrain.tick();
            if (network
                    && client.getConnection() != null
                    && client.getConnection().hasChannel(Endpoint.TYPE)
                    && System.nanoTime() >= nextDiscovery) {
                nextDiscovery = System.nanoTime() + 5_000_000_000L;
                client.getConnection().send(Endpoint.request());
            }
        }
        if (!managed) return;
        if (updater == null) {
            updater =
                    new AutoUpdater(
                            client.gameDirectory.toPath(),
                            "client",
                            true,
                            update -> {
                                AutoUpdater.prepare(update);
                                client.execute(
                                        () -> {
                                            client.disconnect();
                                            client.stop();
                                        });
                            });
            updater.start();
        }
        if (client.isGameLoadFinished()) updater.loaded();
        if (updater.restartPending()
                || !client.isGameLoadFinished()
                || client.level != null
                || client.getConnection() != null
                || !(client.screen instanceof TitleScreen
                        || client.screen instanceof JoinMultiplayerScreen
                        || client.screen instanceof DisconnectedScreen)
                || System.nanoTime() < nextJoin) return;
        nextJoin = System.nanoTime() + 5_000_000_000L;
        String address = "play.aerosmp.com:25587";
        ConnectScreen.startConnecting(
                client.screen,
                client,
                ServerAddress.parseString(address),
                new ServerData("Voxy Testing", address, ServerData.Type.OTHER),
                false,
                null);
    }

    private void reset() {
        TerrainSession previous = terrain;
        terrain = null;
        level = null;
        if (previous != null) previous.close();
    }

    private void control(Minecraft client) {
        if (System.nanoTime() < nextControl) return;
        nextControl = System.nanoTime() + 1_000_000_000L;
        Path path = client.gameDirectory.toPath().resolve(".voxy/terrain/control.properties");
        try {
            if (!Files.isRegularFile(path)) return;
            long time = Files.getLastModifiedTime(path).toMillis();
            if (time == modified) return;
            Properties values = new Properties();
            try (var input = Files.newInputStream(path)) {
                values.load(input);
            }
            network = Boolean.parseBoolean(values.getProperty("network", "true"));
            render = Boolean.parseBoolean(values.getProperty("render", "true"));
            clipping = Boolean.parseBoolean(values.getProperty("clip", "true"));
            pixels =
                    values.containsKey("pixels")
                            ? ClientSettings.validate(
                                    Float.parseFloat(values.getProperty("pixels")))
                            : Float.NaN;
            if (values.containsKey("fov")) {
                if (originalFov == null) originalFov = client.options.fov().get();
                client.options
                        .fov()
                        .set(Math.clamp(Integer.parseInt(values.getProperty("fov")), 30, 110));
            } else if (originalFov != null) {
                client.options.fov().set(originalFov);
                originalFov = null;
            }
            if (modified != -1 && values.containsKey("screenshot"))
                net.minecraft.client.Screenshot.grab(
                        client.gameDirectory,
                        client.getMainRenderTarget(),
                        result -> System.out.println("[Voxy live] " + result.getString()));
            if (modified != -1 && values.containsKey("reload")) client.reloadResourcePacks();
            if (modified != -1 && values.containsKey("profile"))
                client.debugClientMetricsStart(
                        result -> System.out.println("[Voxy profiling] " + result.getString()));
            if (modified != -1 && values.containsKey("inspect") && terrain != null) {
                String[] pixel = values.getProperty("inspect").split(",");
                if (pixel.length != 2) throw new IllegalArgumentException("Expected inspect=x,y");
                terrain.renderer.inspect(
                        Integer.parseInt(pixel[0].strip()),
                        Integer.parseInt(pixel[1].strip()),
                        client.gameDirectory.toPath().resolve(".voxy/terrain/inspection.json"));
            }
            modified = time;
        } catch (IOException | IllegalArgumentException failure) {
            System.err.println("Voxy live control: " + failure);
        }
    }

    static boolean vanilla(BlockPos pos) {
        RenderSectionManager manager = vanillaManager();
        if (manager == null) return Minecraft.getInstance().levelRenderer.isSectionCompiled(pos);
        return manager.isSectionBuilt(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4)
                && manager.isSectionVisible(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4);
    }

    static RenderSectionManager vanillaManager() {
        SodiumWorldRenderer sodium = SodiumWorldRenderer.instanceNullable();
        return sodium == null ? null : ((VoxyMixins.SodiumCoverage) (Object) sodium).sections();
    }

    public interface SharedVertices {
        void adopt(int vertices, int elements, MeshData.DrawState state);
    }

    public static int viewDistance() {
        return TerrainRenderer.viewDistance();
    }

    @ConfigEntryPointForge("voxy")
    public static final class Menu implements ConfigEntryPoint {
        public void registerConfigLate(ConfigBuilder builder) {
            var enabled = builder.createBooleanOption(id("enabled"));
            enabled.setName(text("enabled"));
            enabled.setTooltip(text("enabled.tooltip"));
            enabled.setBinding(
                    value -> ClientSettings.rendering = value, () -> ClientSettings.rendering);
            enabled.setDefaultValue(true).setStorageHandler(ClientSettings::save);
            var distance = builder.createIntegerOption(id("render_distance"));
            distance.setName(text("renderDistance"));
            distance.setTooltip(text("renderDistance.tooltip"));
            distance.setBinding(
                    value -> ClientSettings.distance = value * 16,
                    () -> Math.round(ClientSettings.distance / 16f));
            distance.setDefaultValue(128).setStorageHandler(ClientSettings::save);
            distance.setRange(new Range(2, 512, 2))
                    .setValueFormatter(value -> Component.literal(Integer.toString(value)));
            var pixels = builder.createIntegerOption(id("subdivsize"));
            pixels.setName(text("subDivisionSize"));
            pixels.setTooltip(text("subDivisionSize.tooltip"));
            pixels.setBinding(
                    value -> ClientSettings.pixels = ClientSettings.fromSlider(value),
                    () -> ClientSettings.toSlider(ClientSettings.pixels));
            pixels.setDefaultValue(ClientSettings.toSlider(64))
                    .setStorageHandler(ClientSettings::save);
            pixels.setRange(new Range(0, 100, 1));
            pixels.setValueFormatter(
                    value ->
                            Component.literal(
                                    Math.round(ClientSettings.fromSlider(value)) + " px"));
            builder.registerModOptions("voxy", "Voxy", "0")
                    .setIcon(id("icon.png"))
                    .addPage(
                            builder.createOptionPage()
                                    .setName(Component.translatable("voxy.config.general"))
                                    .addOptionGroup(builder.createOptionGroup().addOption(enabled)))
                    .addPage(
                            builder.createOptionPage()
                                    .setName(Component.translatable("voxy.config.rendering"))
                                    .addOptionGroup(
                                            builder.createOptionGroup()
                                                    .addOption(distance)
                                                    .addOption(pixels)));
        }

        private static ResourceLocation id(String value) {
            return ResourceLocation.fromNamespaceAndPath("voxy", value);
        }

        private static Component text(String value) {
            return Component.translatable("voxy.config.general." + value);
        }
    }
}
