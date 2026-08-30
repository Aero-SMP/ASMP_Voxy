package me.cortex.voxy.debug;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import me.cortex.voxy.commonImpl.lod.LodGenerationService;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

public final class ServerDiagnostics {
    private static long processed;
    private static long failed;
    private static long previousProcessed;
    private static long sampledAt = System.nanoTime();
    private static double perSecond;

    private ServerDiagnostics() {}

    static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("voxydebug")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("stats").executes(context -> showStats(context.getSource())));
    }

    private static int showStats(CommandSourceStack source) {
        Object service = LodGenerationService.getInstance();
        long active = Reflection.number(Reflection.field(service, "activeTaskCount"));
        double load = Reflection.field(service, "loadFactor") instanceof Number number
                ? number.doubleValue() : 1.0;
        source.sendSuccess(() -> Component.literal("Voxy LOD generation: "
                + count() + " processed, " + failures() + " failed, " + active + " active, "
                + String.format("%.1f chunks/s, %.0f%% load", perSecond(), load * 100)), false);
        return 1;
    }

    public static synchronized void processed() {
        processed++;
        sample();
    }

    public static synchronized void failed() {
        failed++;
    }

    public static synchronized void reset() {
        processed = failed = previousProcessed = 0;
        perSecond = 0;
        sampledAt = System.nanoTime();
    }

    private static void sample() {
        long now = System.nanoTime();
        double seconds = (now - sampledAt) / 1_000_000_000.0;
        if (seconds < 1.0) return;
        perSecond = (processed - previousProcessed) / seconds;
        previousProcessed = processed;
        sampledAt = now;
    }

    public static synchronized long count() { return processed; }
    public static synchronized long failures() { return failed; }
    public static synchronized double perSecond() { sample(); return perSecond; }
}
