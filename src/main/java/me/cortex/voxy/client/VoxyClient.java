package me.cortex.voxy.client;

import me.cortex.voxy.client.lod.ClientLodClient;
import me.cortex.voxy.client.core.RenderResourceReuse;
import me.cortex.voxy.client.core.gl.Capabilities;
import me.cortex.voxy.client.core.model.CatalogMapper;
import me.cortex.voxy.client.core.rendering.util.SharedIndexBuffer;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.common.Logger;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.fml.loading.LoadingModList;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileLock;
import java.nio.channels.NonWritableChannelException;
import java.nio.file.Path;

@Mod(value = "voxy", dist = Dist.CLIENT)
public class VoxyClient {
    public static final String MOD_VERSION = getModVersion("voxy");
    private static FileLock EXCLUSIVE_LOCK;
    private static CatalogMapper mapper;
    private static boolean available;
    public static boolean inSession;

    public VoxyClient(IEventBus modBus) {
        Logger.setErrorSink(VoxyClient::showErrorInHud);
        ClientLodClient.init(modBus);
    }

    private static void showErrorInHud(String message) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft != null) {
            minecraft.executeIfPossible(() -> {
                if (minecraft.player != null) {
                    minecraft.getChatListener().handleSystemMessage(Component.literal(message), true);
                }
            });
        }
    }

    public static void initVoxyClient() {
        Capabilities.init();//Ensure clinit is called

        if (Capabilities.INSTANCE.hasBrokenDepthSampler) {
            Logger.error("AMD broken depth sampler detected, voxy does not work correctly and has been disabled, this will hopefully be fixed in the future");
        }

        boolean systemSupported = Capabilities.INSTANCE.compute && Capabilities.INSTANCE.indirectParameters && !Capabilities.INSTANCE.hasBrokenDepthSampler;
        if (!systemSupported) {
             Logger.error("Voxy is unsupported on your system.");
        }

        if (systemSupported && System.getProperty("voxy.exclusiveLock", "false").equalsIgnoreCase("true")) {
            //Try acquire the lock file
            var vf = Minecraft.getInstance().gameDirectory.toPath().resolve(".voxy");
            if (!vf.toFile().isDirectory()) {
                vf.toFile().mkdir();
            }
            try {
                FileOutputStream fis = new FileOutputStream(vf.resolve("voxy.lock").toFile());
                EXCLUSIVE_LOCK = fis.getChannel().lock(0, Long.MAX_VALUE, false);
            } catch (NonWritableChannelException | IOException e) {
                //If some error write to log and unsupport
                Logger.error("Failed to acquire exclusive voxy lock file, mod will be disabled");
                systemSupported = false;
            }

        }

        if (systemSupported) {

            SharedIndexBuffer.INSTANCE.id();

            setAvailable();

            if (!Capabilities.INSTANCE.subgroup) {
                Logger.warn("GPU does not support subgroup operations, expect some performance degradation");
            }

        }
    }

    public static void sessionStart() {
        if (inSession) throw new IllegalStateException("Cannot start new session while in a session");
        try {
            ClientLodClient.resetDemand();
            inSession = true;
            if (getMapper() != null) throw new IllegalStateException();
            if (isAvailable() && VoxyConfig.CONFIG.enabled) {
                createRuntime();
            }
        } catch (RuntimeException exception) {
            ClientLodClient.disconnect();
            try {
                shutdownRuntime();
            } catch (RuntimeException cleanupException) {
                exception.addSuppressed(cleanupException);
            } finally {
                inSession = false;
            }
            throw exception;
        }
    }

    public static void sessionEnd() {
        if (!inSession) throw new IllegalStateException("Cannot end a session while not in a session");
        ClientLodClient.disconnect();
        try { shutdownRuntime(); }
        finally {
            ClientLodClient.resetDemand();
            inSession = false;
        }
    }

    public static CatalogMapper getMapper() {
        return mapper;
    }

    public static void createRuntime() {
        if (!available) return;
        if (mapper != null) throw new IllegalStateException("Cannot create multiple runtimes");
        Logger.info("Initializing Voxy client runtime");
        mapper = new CatalogMapper();
    }

    public static void shutdownRuntime() {
        if (mapper != null) {
            CatalogMapper closing = mapper;
            mapper = null;
            Logger.info("Shutting down Voxy client runtime");
            closing.setBiomeCallback(null);
            RenderResourceReuse.clearResources();
        }
    }

    public static boolean isAvailable() {
        return available;
    }

    public static boolean isModLoaded(String modId) {
        LoadingModList mods = LoadingModList.get();
        return mods != null && mods.getModFileById(modId) != null;
    }

    public static Path getConfigDir() {
        return FMLPaths.CONFIGDIR.get();
    }

    private static void setAvailable() {
        if (available) throw new IllegalStateException("Cannot make Voxy available more than once");
        available = true;
    }

    private static String getModVersion(String modId) {
        LoadingModList mods = LoadingModList.get();
        if (mods == null) return "<UNKNOWN>";
        var info = mods.getModFileById(modId);
        return info == null ? "<UNKNOWN>" : info.versionString();
    }
}
