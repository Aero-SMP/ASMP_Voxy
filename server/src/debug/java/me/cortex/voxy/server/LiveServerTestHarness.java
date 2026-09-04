package me.cortex.voxy.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import me.cortex.voxy.debugtest.DebugTestCommandPayload;
import me.cortex.voxy.debugtest.DebugTestProtocol;
import me.cortex.voxy.debugtest.DebugTestResultPayload;
import me.cortex.voxy.debugtest.DebugTestSnapshot;
import me.cortex.voxy.debugtest.DebugTestOrdering;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.HandlerThread;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

/** Debug-server authority for exactly one sequential live-client test run. */
final class LiveServerTestHarness {
    private static final Gson JSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final Path ROOT = Path.of("logs", "voxy-tests").toAbsolutePath();
    private static final double MAX_HORIZONTAL = 29_999_984.0;
    private static final double MAX_VERTICAL = 20_000_000.0;
    private static final Map<UUID, Capability> CLIENTS = new HashMap<>();
    private static ActiveRun active;

    private LiveServerTestHarness() {}

    static void register(IEventBus modBus) {
        modBus.addListener(LiveServerTestHarness::registerPayload);
        NeoForge.EVENT_BUS.addListener(LiveServerTestHarness::registerCommands);
        NeoForge.EVENT_BUS.addListener(LiveServerTestHarness::playerLoggedOut);
        NeoForge.EVENT_BUS.addListener(LiveServerTestHarness::serverStopping);
    }

    private static void registerPayload(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar(DebugTestProtocol.REGISTRATION_VERSION)
                .optional().executesOn(HandlerThread.NETWORK);
        // Both peers declare both directions so optional capability negotiation is symmetric.
        // This handler can only run on an incorrectly configured physical client.
        registrar.playToClient(DebugTestCommandPayload.TYPE, DebugTestCommandPayload.CODEC,
                (payload, context) -> {});
        registrar.playToServer(DebugTestResultPayload.TYPE, DebugTestResultPayload.CODEC,
                (payload, context) -> context.enqueueWork(() ->
                        accept((ServerPlayer) context.player(), payload)));
    }

    private static void registerCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        var root = Commands.literal("voxytest").requires(source -> source.hasPermission(4));

        var scenario = Commands.argument("scenario", StringArgumentType.word())
                .executes(context -> begin(context.getSource(),
                        EntityArgument.getPlayer(context, "player"), uuid(context, "run"),
                        StringArgumentType.getString(context, "scenario")));
        var beginRun = Commands.argument("run", StringArgumentType.word()).then(scenario);
        var beginPlayer = Commands.argument("player", EntityArgument.player()).then(beginRun);
        root.then(Commands.literal("begin").then(beginPlayer));

        var timeout = Commands.argument("timeout_ms", LongArgumentType.longArg(1, 3_600_000))
                .executes(context -> pose(context.getSource(), uuid(context, "run"),
                        LongArgumentType.getLong(context, "step"),
                        ResourceLocationArgument.getId(context, "dimension").toString(),
                        DoubleArgumentType.getDouble(context, "x"),
                        DoubleArgumentType.getDouble(context, "y"),
                        DoubleArgumentType.getDouble(context, "z"),
                        (float) DoubleArgumentType.getDouble(context, "yaw"),
                        (float) DoubleArgumentType.getDouble(context, "pitch"),
                        LongArgumentType.getLong(context, "timeout_ms")));
        var pitch = Commands.argument("pitch", DoubleArgumentType.doubleArg(-90, 90)).then(timeout);
        var yaw = Commands.argument("yaw", DoubleArgumentType.doubleArg()).then(pitch);
        var z = Commands.argument("z", DoubleArgumentType.doubleArg()).then(yaw);
        var y = Commands.argument("y", DoubleArgumentType.doubleArg()).then(z);
        var x = Commands.argument("x", DoubleArgumentType.doubleArg()).then(y);
        var dimension = Commands.argument("dimension", ResourceLocationArgument.id()).then(x);
        var poseStep = Commands.argument("step", LongArgumentType.longArg(1)).then(dimension);
        var poseRun = Commands.argument("run", StringArgumentType.word()).then(poseStep);
        root.then(Commands.literal("pose").then(poseRun));

        var cadence = Commands.argument("cadence_ms", LongArgumentType.longArg(10, 60_000))
                .executes(context -> trace(context.getSource(), uuid(context, "run"),
                        LongArgumentType.getLong(context, "step"),
                        LongArgumentType.getLong(context, "duration_ms"),
                        LongArgumentType.getLong(context, "cadence_ms")));
        var duration = Commands.argument("duration_ms", LongArgumentType.longArg(1, 3_600_000))
                .then(cadence);
        var traceStep = Commands.argument("step", LongArgumentType.longArg(1)).then(duration);
        var traceRun = Commands.argument("run", StringArgumentType.word()).then(traceStep);
        root.then(Commands.literal("trace").then(traceRun));

        root.then(singleStep("checkpoint", LiveServerTestHarness::checkpoint));
        root.then(singleStep("screenshot", LiveServerTestHarness::screenshot));
        root.then(singleStep("end", LiveServerTestHarness::end));
        root.then(singleStep("abort", LiveServerTestHarness::abort));
        var status = Commands.argument("status", StringArgumentType.word())
                .executes(context -> finish(context.getSource(), uuid(context, "run"),
                        StringArgumentType.getString(context, "status")));
        var finishRun = Commands.argument("run", StringArgumentType.word()).then(status);
        root.then(Commands.literal("finish").then(finishRun));
        dispatcher.register(root);
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack>
    singleStep(String name, StepCommand command) {
        return Commands.literal(name)
                .then(Commands.argument("run", StringArgumentType.word())
                        .then(Commands.argument("step", LongArgumentType.longArg(1))
                                .executes(context -> command.run(context.getSource(),
                                        uuid(context, "run"),
                                        LongArgumentType.getLong(context, "step")))));
    }

    private static int begin(CommandSourceStack source, ServerPlayer player, UUID runId,
                             String scenarioHash) {
        if (active != null) return fail(source, "another Voxy test run is active");
        if (!isSha256(scenarioHash)) return fail(source, "scenario must be a SHA-256 hex value");
        Capability capability = CLIENTS.get(player.getUUID());
        if (capability == null || !player.connection.hasChannel(DebugTestCommandPayload.TYPE)) {
            return fail(source, "selected player has no matching debug-test client");
        }
        if (!capability.snapshot().firstPerson()) {
            return fail(source, "selected client is not in first-person view");
        }
        Path directory = ROOT.resolve(runId.toString());
        if (Files.exists(directory)) return fail(source, "run ID already exists");
        try {
            EvidenceWriter writer = new EvidenceWriter(directory,
                    metadata(player, runId, scenarioHash, capability));
            active = new ActiveRun(runId, player, capability.epoch, writer);
            return send(source, active, 0, DebugTestProtocol.CommandKind.BEGIN_RUN,
                    "", 0, 0, 0, 0, 0, 0, 0, 0);
        } catch (IOException failure) {
            return fail(source, "cannot create evidence writer: " + failure.getMessage());
        }
    }

    private static int pose(CommandSourceStack source, UUID runId, long step, String dimension,
                            double x, double y, double z, float yaw, float pitch,
                            long timeoutMillis) {
        ActiveRun run = requireReady(source, runId, step);
        if (run == null) return 0;
        if (!finite(x, y, z, yaw, pitch) || Math.abs(x) > MAX_HORIZONTAL
                || Math.abs(z) > MAX_HORIZONTAL || Math.abs(y) > MAX_VERTICAL) {
            return fail(source, "pose is outside valid finite coordinate bounds");
        }
        ResourceLocation location = ResourceLocation.tryParse(dimension);
        ServerLevel level = location == null ? null : run.player.server.getLevel(
                ResourceKey.create(Registries.DIMENSION, location));
        if (level == null) return fail(source, "unknown dimension");
        if (!run.player.isAlive() || run.player.isPassenger() || run.player.isSleeping()
                || run.player.isChangingDimension()) {
            return fail(source, "player state makes the rendered camera ambiguous");
        }
        Capability capability = CLIENTS.get(run.player.getUUID());
        if (capability == null || capability.epoch != run.connectionEpoch
                || !capability.snapshot().firstPerson()) {
            return fail(source, "client capability or first-person precondition changed");
        }
        run.player.teleportTo(level, x, y, z, yaw, pitch);
        return send(source, run, step, DebugTestProtocol.CommandKind.EXPECT_POSE,
                dimension, x, y, z, yaw, pitch, millisToNanos(timeoutMillis), 0, 0);
    }

    private static int trace(CommandSourceStack source, UUID runId, long step,
                             long durationMillis, long cadenceMillis) {
        ActiveRun run = requireReady(source, runId, step);
        if (run == null) return 0;
        return send(source, run, step, DebugTestProtocol.CommandKind.START_TRACE,
                "", 0, 0, 0, 0, 0, 0, millisToNanos(durationMillis),
                millisToNanos(cadenceMillis));
    }

    private static int checkpoint(CommandSourceStack source, UUID runId, long step) {
        return simple(source, runId, step, DebugTestProtocol.CommandKind.CAPTURE_CHECKPOINT);
    }

    private static int screenshot(CommandSourceStack source, UUID runId, long step) {
        return simple(source, runId, step, DebugTestProtocol.CommandKind.CAPTURE_SCREENSHOT);
    }

    private static int end(CommandSourceStack source, UUID runId, long step) {
        return simple(source, runId, step, DebugTestProtocol.CommandKind.END_RUN);
    }

    private static int abort(CommandSourceStack source, UUID runId, long step) {
        return simple(source, runId, step, DebugTestProtocol.CommandKind.ABORT_RUN);
    }

    private static int simple(CommandSourceStack source, UUID runId, long step,
                              DebugTestProtocol.CommandKind kind) {
        ActiveRun run = requireReady(source, runId, step);
        if (run == null) return 0;
        return send(source, run, step, kind, "", 0, 0, 0, 0, 0, 0, 0, 0);
    }

    private static int finish(CommandSourceStack source, UUID runId, String value) {
        ActiveRun run = active;
        if (run == null || !run.runId.equals(runId)) return fail(source, "run is not active");
        RunStatus status;
        try { status = RunStatus.valueOf(value.toUpperCase(java.util.Locale.ROOT)); }
        catch (IllegalArgumentException invalid) { return fail(source, "invalid result status"); }
        if (status == RunStatus.PASS && !run.ended) {
            return fail(source, "run cannot pass before END_RUN completes");
        }
        if (status != RunStatus.PASS && run.player.connection.isAcceptingMessages()
                && run.player.connection.hasChannel(DebugTestCommandPayload.TYPE)) {
            long abortStep = Math.max(run.lastStep, run.outstandingStep) + 1;
            run.player.connection.send(new DebugTestCommandPayload(
                    DebugTestProtocol.CommandKind.ABORT_RUN, run.runId, abortStep,
                    run.connectionEpoch, "", 0, 0, 0, 0, 0, 0, 0, 0));
        }
        complete(run, status, DebugTestProtocol.Failure.NONE);
        success(source, "finalized " + runId + " as " + status);
        return 1;
    }

    private static ActiveRun requireReady(CommandSourceStack source, UUID runId, long step) {
        ActiveRun run = active;
        if (run == null || !run.runId.equals(runId)) {
            fail(source, "run is not active"); return null;
        }
        if (run.ended || run.outstandingStep >= 0) {
            fail(source, "previous test operation is still outstanding"); return null;
        }
        if (!DebugTestOrdering.isNext(run.lastStep, step)) {
            fail(source, "step must be exactly " + (run.lastStep + 1)); return null;
        }
        if (!run.player.connection.isAcceptingMessages()
                || !run.player.connection.hasChannel(DebugTestCommandPayload.TYPE)) {
            fail(source, "debug client disconnected or lost capability"); return null;
        }
        return run;
    }

    private static int send(CommandSourceStack source, ActiveRun run, long step,
                            DebugTestProtocol.CommandKind kind, String dimension,
                            double x, double y, double z, float yaw, float pitch,
                            long timeoutNanos, long durationNanos, long cadenceNanos) {
        run.outstandingStep = step;
        run.outstandingKind = kind;
        run.player.connection.send(new DebugTestCommandPayload(kind, run.runId, step,
                run.connectionEpoch, dimension, x, y, z, yaw, pitch, timeoutNanos,
                durationNanos, cadenceNanos));
        success(source, "started " + kind + " run=" + run.runId + " step=" + step);
        return 1;
    }

    private static void accept(ServerPlayer player, DebugTestResultPayload result) {
        if (result.runId().equals(new UUID(0, 0))
                && result.kind() == DebugTestProtocol.ResultKind.CLIENT_READY
                && result.stepId() == 0) {
            Capability previous = CLIENTS.get(player.getUUID());
            if (previous == null || result.connectionEpoch() > previous.epoch) {
                CLIENTS.put(player.getUUID(), new Capability(result.connectionEpoch(),
                        result.snapshot(), result));
                if (active != null && active.player.getUUID().equals(player.getUUID())
                        && result.connectionEpoch() != active.connectionEpoch) {
                    complete(active, RunStatus.DISCONNECTED,
                            DebugTestProtocol.Failure.DISCONNECTED);
                }
            }
            return;
        }
        ActiveRun run = active;
        if (run == null || run.player != player || !DebugTestOrdering.matches(
                run.runId, run.connectionEpoch, run.outstandingStep,
                result.runId(), result.connectionEpoch(), result.stepId())) return;
        if (result.kind() == DebugTestProtocol.ResultKind.TRACE_SAMPLE) {
            if (run.outstandingKind != DebugTestProtocol.CommandKind.START_TRACE
                    || result.stepId() != run.outstandingStep) return;
            run.writer.trace(eventJson(player, result));
            return;
        }
        if (result.stepId() != run.outstandingStep
                || !expected(run.outstandingKind, result.kind())) return;
        String screenshot = result.kind() == DebugTestProtocol.ResultKind.SCREENSHOT_RESULT
                ? screenshotJson(result) : null;
        if (!run.writer.critical(eventJson(player, result), screenshot)) {
            complete(run, RunStatus.FAIL, DebugTestProtocol.Failure.INTERNAL);
            return;
        }
        run.lastStep = result.stepId();
        run.outstandingStep = -1;
        DebugTestProtocol.CommandKind completed = run.outstandingKind;
        run.outstandingKind = null;
        CLIENTS.put(player.getUUID(), new Capability(result.connectionEpoch(),
                result.snapshot(), result));
        if (result.failure() != DebugTestProtocol.Failure.NONE
                || result.kind() == DebugTestProtocol.ResultKind.RUN_FAILED) {
            complete(run, result.failure() == DebugTestProtocol.Failure.ABORTED
                    ? RunStatus.ABORTED : RunStatus.FAIL, result.failure());
        } else if (completed == DebugTestProtocol.CommandKind.END_RUN) {
            run.ended = true;
        }
    }

    private static boolean expected(DebugTestProtocol.CommandKind command,
                                    DebugTestProtocol.ResultKind result) {
        if (command == null) return false;
        return switch (command) {
            case BEGIN_RUN -> result == DebugTestProtocol.ResultKind.CLIENT_READY;
            case EXPECT_POSE -> result == DebugTestProtocol.ResultKind.POSE_REACHED
                    || result == DebugTestProtocol.ResultKind.POSE_FAILED;
            case START_TRACE, CAPTURE_CHECKPOINT ->
                    result == DebugTestProtocol.ResultKind.CHECKPOINT_RESULT;
            case CAPTURE_SCREENSHOT -> result == DebugTestProtocol.ResultKind.SCREENSHOT_RESULT;
            case END_RUN -> result == DebugTestProtocol.ResultKind.RUN_COMPLETE;
            case ABORT_RUN -> result == DebugTestProtocol.ResultKind.RUN_FAILED;
        };
    }

    private static void playerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        CLIENTS.remove(player.getUUID());
        if (active != null && active.player == player) {
            complete(active, RunStatus.DISCONNECTED, DebugTestProtocol.Failure.DISCONNECTED);
        }
    }

    private static void serverStopping(ServerStoppingEvent event) {
        if (active != null) complete(active, RunStatus.ABORTED,
                DebugTestProtocol.Failure.ABORTED);
        CLIENTS.clear();
    }

    private static void complete(ActiveRun run, RunStatus status,
                                 DebugTestProtocol.Failure failure) {
        if (active != run) return;
        active = null;
        run.writer.finish(status, failure);
    }

    private static String eventJson(ServerPlayer player, DebugTestResultPayload result) {
        JsonObject event = new JsonObject();
        event.addProperty("serverNanos", System.nanoTime());
        event.addProperty("serverTime", Instant.now().toString());
        event.addProperty("player", player.getUUID().toString());
        event.add("result", JSON.toJsonTree(result));
        return JSON.toJson(event);
    }

    private static JsonObject metadata(ServerPlayer player, UUID runId, String scenarioHash,
                                       Capability capability) {
        JsonObject metadata = new JsonObject();
        metadata.addProperty("runId", runId.toString());
        metadata.addProperty("scenarioSha256", scenarioHash.toLowerCase(java.util.Locale.ROOT));
        metadata.addProperty("playerUuid", player.getUUID().toString());
        metadata.addProperty("playerName", player.getGameProfile().getName());
        metadata.addProperty("initialDimension", player.level().dimension().location().toString());
        metadata.addProperty("connectionEpoch", capability.epoch);
        metadata.addProperty("serverVersion",
                String.valueOf(VoxyServer.class.getPackage().getImplementationVersion()));
        ByteBuffer clientIdentity = ByteBuffer.allocate(32)
                .putLong(capability.result.buildIdentity0())
                .putLong(capability.result.buildIdentity1())
                .putLong(capability.result.buildIdentity2())
                .putLong(capability.result.buildIdentity3());
        metadata.addProperty("clientBuild", HexFormat.of().formatHex(clientIdentity.array()));
        metadata.addProperty("java", System.getProperty("java.version"));
        metadata.addProperty("os", System.getProperty("os.name") + ' '
                + System.getProperty("os.arch") + ' ' + System.getProperty("os.version"));
        metadata.addProperty("processors", Runtime.getRuntime().availableProcessors());
        metadata.add("initialSnapshot", JSON.toJsonTree(capability.snapshot()));
        return metadata;
    }

    private static String screenshotJson(DebugTestResultPayload result) {
        JsonObject screenshot = new JsonObject();
        screenshot.addProperty("runId", result.runId().toString());
        screenshot.addProperty("stepId", result.stepId());
        screenshot.addProperty("filename", "voxy-test-" + result.runId() + '-'
                + result.stepId() + ".png");
        screenshot.addProperty("uploaded", result.failure() == DebugTestProtocol.Failure.NONE);
        screenshot.addProperty("failure", result.failure().name());
        return JSON.toJson(screenshot);
    }

    private static String artifactIdentity(Class<?> anchor) {
        String version = anchor.getPackage().getImplementationVersion();
        try {
            var location = anchor.getProtectionDomain().getCodeSource().getLocation();
            Path path = Path.of(location.toURI());
            if (!Files.isRegularFile(path)) return String.valueOf(version) + ":classes";
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(path)) {
                byte[] buffer = new byte[64 * 1024];
                for (int read; (read = input.read(buffer)) >= 0;) digest.update(buffer, 0, read);
            }
            return String.valueOf(version) + ':' + HexFormat.of().formatHex(digest.digest());
        } catch (Exception failure) {
            return String.valueOf(version) + ":unavailable";
        }
    }

    private static UUID uuid(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context,
                             String name) {
        return UUID.fromString(StringArgumentType.getString(context, name));
    }

    private static boolean isSha256(String value) {
        return value != null && value.matches("[0-9a-fA-F]{64}");
    }

    private static boolean finite(double... values) {
        for (double value : values) if (!Double.isFinite(value)) return false;
        return true;
    }

    private static long millisToNanos(long millis) { return Math.multiplyExact(millis, 1_000_000L); }

    private static int fail(CommandSourceStack source, String message) {
        source.sendFailure(Component.literal(message)); return 0;
    }

    private static void success(CommandSourceStack source, String message) {
        source.sendSuccess(() -> Component.literal(message), false);
    }

    private interface StepCommand {
        int run(CommandSourceStack source, UUID runId, long step);
    }

    private record Capability(long epoch, DebugTestSnapshot snapshot,
                              DebugTestResultPayload result) {}

    private static final class ActiveRun {
        final UUID runId;
        final ServerPlayer player;
        final long connectionEpoch;
        final EvidenceWriter writer;
        long lastStep = -1;
        long outstandingStep = -1;
        DebugTestProtocol.CommandKind outstandingKind;
        boolean ended;
        ActiveRun(UUID runId, ServerPlayer player, long connectionEpoch,
                  EvidenceWriter writer) {
            this.runId = runId; this.player = player; this.connectionEpoch = connectionEpoch;
            this.writer = writer;
        }
    }

    private enum RunStatus { PASS, FAIL, TIMEOUT, DISCONNECTED, ABORTED }

    /** One critical slot plus one replaceable trace slot bounds disk-writer ownership. */
    private static final class EvidenceWriter {
        private final Object lock = new Object();
        private final Path directory;
        private final JsonObject metadata;
        private CriticalEvidence critical;
        private String trace;
        private long coalescedTrace;
        private long totalCoalescedTrace;
        private RunStatus finalStatus;
        private DebugTestProtocol.Failure finalFailure;

        EvidenceWriter(Path directory, JsonObject metadata) throws IOException {
            this.directory = directory;
            this.metadata = metadata;
            if (Files.exists(directory)) throw new IOException("evidence directory exists");
            Thread.ofPlatform().daemon().name("Voxy test evidence " + directory.getFileName())
                    .start(this::run);
        }

        boolean critical(String event, String screenshot) {
            synchronized (this.lock) {
                if (this.finalStatus != null || this.critical != null) return false;
                this.critical = new CriticalEvidence(event, screenshot);
                this.lock.notifyAll();
                return true;
            }
        }

        void trace(String event) {
            synchronized (this.lock) {
                if (this.finalStatus != null) return;
                if (this.trace != null) {
                    this.coalescedTrace++;
                    this.totalCoalescedTrace++;
                }
                this.trace = event;
                this.lock.notifyAll();
            }
        }

        void finish(RunStatus status, DebugTestProtocol.Failure failure) {
            synchronized (this.lock) {
                if (this.finalStatus != null) return;
                this.finalStatus = status;
                this.finalFailure = failure;
                this.lock.notifyAll();
            }
        }

        private void run() {
            try {
                Files.createDirectories(this.directory);
                this.metadata.addProperty("serverBuild", artifactIdentity(VoxyServer.class));
                Files.writeString(this.directory.resolve("metadata.json"), JSON.toJson(this.metadata),
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
                try (BufferedWriter output = Files.newBufferedWriter(
                        this.directory.resolve("events.jsonl"), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                     BufferedWriter screenshots = Files.newBufferedWriter(
                             this.directory.resolve("screenshots.json"), StandardCharsets.UTF_8,
                             StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                    screenshots.write('[');
                    boolean firstScreenshot = true;
                    while (true) {
                        CriticalEvidence critical;
                        String trace;
                        boolean boundary;
                        RunStatus done;
                        DebugTestProtocol.Failure failure;
                        long coalesced;
                        synchronized (this.lock) {
                            while (this.critical == null && this.trace == null
                                    && this.finalStatus == null) this.lock.wait();
                            if (this.critical != null) {
                                critical = this.critical; this.critical = null;
                                trace = null; boundary = true;
                            } else if (this.trace != null) {
                                critical = null; trace = this.trace;
                                this.trace = null; boundary = false;
                            } else {
                                critical = null; trace = null; boundary = false;
                            }
                            coalesced = this.coalescedTrace;
                            this.coalescedTrace = 0;
                            done = this.finalStatus;
                            failure = this.finalFailure;
                        }
                        String event = critical == null ? trace : critical.event();
                        if (event != null) {
                            output.write(event); output.newLine();
                            if (critical != null && critical.screenshot() != null) {
                                if (!firstScreenshot) screenshots.write(',');
                                screenshots.newLine();
                                screenshots.write(critical.screenshot());
                                firstScreenshot = false;
                            }
                            if (boundary) {
                                output.flush();
                                screenshots.flush();
                            }
                        }
                        if (done != null && event == null) {
                            output.flush();
                            if (!firstScreenshot) screenshots.newLine();
                            screenshots.write(']'); screenshots.newLine(); screenshots.flush();
                            publishResult(done, failure, this.totalCoalescedTrace);
                            return;
                        }
                    }
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        }

        private record CriticalEvidence(String event, String screenshot) {}

        private void publishResult(RunStatus status, DebugTestProtocol.Failure failure,
                                   long coalesced) throws IOException {
            JsonObject result = new JsonObject();
            result.addProperty("status", status.name());
            result.addProperty("failure", failure.name());
            result.addProperty("events", "events.jsonl");
            result.addProperty("metadata", "metadata.json");
            result.addProperty("screenshots", "screenshots.json");
            result.addProperty("serverWriterCoalescedTrace", coalesced);
            Path temporary = this.directory.resolve("result.json.tmp");
            Path destination = this.directory.resolve("result.json");
            Files.writeString(temporary, JSON.toJson(result), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }
}
