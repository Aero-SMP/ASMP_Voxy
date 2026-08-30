package me.cortex.voxy.debug;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

final class VoxyDebugCommands {
    private VoxyDebugCommands() {}

    static LiteralArgumentBuilder<CommandSourceStack> register() {
        return LiteralArgumentBuilder.<CommandSourceStack>literal("voxy")
                .then(LiteralArgumentBuilder.<CommandSourceStack>literal("reload")
                        .executes(context -> reload()))
                .then(LiteralArgumentBuilder.<CommandSourceStack>literal("debug")
                        .then(LiteralArgumentBuilder.<CommandSourceStack>literal("verifyTLNChildMask")
                                .executes(context -> verify(false))
                                .then(RequiredArgumentBuilder.<CommandSourceStack, Boolean>argument(
                                                "attemptRepair", BoolArgumentType.bool())
                                        .executes(context -> verify(BoolArgumentType.getBool(
                                                context, "attemptRepair"))))));
    }

    private static int reload() {
        if (VoxyCommon.getInstance() == null) {
            showError("Voxy must be enabled in settings to use this");
            return 1;
        }

        var renderer = Minecraft.getInstance().levelRenderer;
        if (renderer != null) {
            ((IGetVoxyRenderSystem) renderer).voxy$shutdownRenderer();
        }
        VoxyCommon.shutdownInstance();
        System.gc();
        VoxyCommon.createInstance();
        if (renderer != null) renderer.allChanged();
        return 0;
    }

    private static int verify(boolean repair) {
        Minecraft minecraft = Minecraft.getInstance();
        if (VoxyCommon.getInstance() == null || minecraft.level == null) {
            showError("Voxy must be active to use this command");
            return 1;
        }
        TlnVerifier.verify(WorldIdentifier.ofEngine(minecraft.level), repair);
        return 0;
    }

    private static void showError(String message) {
        var player = Minecraft.getInstance().player;
        if (player != null) player.displayClientMessage(Component.literal(message), false);
    }
}
