package me.cortex.voxy.client;

import me.cortex.voxy.client.lod.ClientLodStreaming;
import me.cortex.voxy.client.core.gl.Capabilities;
import me.cortex.voxy.client.core.rendering.util.SharedIndexBuffer;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileLock;
import java.nio.channels.NonWritableChannelException;

@Mod(value = "voxy", dist = Dist.CLIENT)
public class VoxyClient {
    private static FileLock EXCLUSIVE_LOCK;
    public static boolean inSession;

    public VoxyClient(IEventBus modBus) {
        new ClientLodStreaming();
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

            VoxyCommon.setInstanceFactory(VoxyClientInstance::new);

            if (!Capabilities.INSTANCE.subgroup) {
                Logger.warn("GPU does not support subgroup operations, expect some performance degradation");
            }

        }
    }

    public static void sessionStart() {
        if (inSession) throw new IllegalStateException("Cannot start new session while in a session");
        inSession = true;

        if (VoxyCommon.getInstance() != null) throw new IllegalStateException();
        if (VoxyCommon.isAvailable() && VoxyConfig.CONFIG.enabled) {
            VoxyCommon.createInstance();
        }
    }

    public static void sessionEnd() {
        if (!inSession) throw new IllegalStateException("Cannot end a session while not in a session");
        inSession = false;
        VoxyCommon.shutdownInstance();
    }
}
