package me.cortex.voxy.client;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.common.DebugUtils;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

public class VoxyCommands {
    public static LiteralArgumentBuilder<CommandSourceStack> register() {
        var debug = LiteralArgumentBuilder.<CommandSourceStack>literal("debug")
            .then(LiteralArgumentBuilder.<CommandSourceStack>literal("verifyTLNChildMask")
                .executes(ctx -> verifyTLNs(ctx, false))
                .then(RequiredArgumentBuilder.<CommandSourceStack, Boolean>argument("attemptRepair", BoolArgumentType.bool())
                    .executes(ctx -> verifyTLNs(ctx, BoolArgumentType.getBool(ctx, "attemptRepair")))));

        return LiteralArgumentBuilder.<CommandSourceStack>literal("voxy")
            .then(LiteralArgumentBuilder.<CommandSourceStack>literal("reload")
                .executes(VoxyCommands::reloadInstance))
            .then(debug);
    }

    private static int reloadInstance(CommandContext<CommandSourceStack> ctx) {
        var instance = (VoxyClientInstance) VoxyCommon.getInstance();
        if (instance == null) {
            sendErrorToPlayer(Component.translatable("Voxy must be enabled in settings to use this"));
            return 1;
        }
        var renderer = Minecraft.getInstance().levelRenderer;
        if (renderer != null) {
            ((IGetVoxyRenderSystem) renderer).voxy$shutdownRenderer();
        }

        VoxyCommon.shutdownInstance();
        System.gc();
        VoxyCommon.createInstance();

        renderer = Minecraft.getInstance().levelRenderer;
        if (renderer != null) renderer.allChanged();
        return 0;
    }

    private static int verifyTLNs(CommandContext<CommandSourceStack> ctx, boolean attemptRepair) {
        if (VoxyCommon.getInstance() == null) {
            sendErrorToPlayer(Component.translatable("Voxy must be enabled in settings to use this"));
            return 1;
        }
        if (Minecraft.getInstance().level == null) {
            throw new IllegalStateException("How you even do this");
        }
        DebugUtils.verifyAllTopLevelNodes(WorldIdentifier.ofEngine(Minecraft.getInstance().level), attemptRepair);
        return 0;
    }

    private static void sendErrorToPlayer(Component message) {
        var player = Minecraft.getInstance().player;
        if (player != null) player.displayClientMessage(message, false);
    }
}
