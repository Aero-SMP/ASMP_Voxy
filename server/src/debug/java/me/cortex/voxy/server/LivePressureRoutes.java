package me.cortex.voxy.server;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Console-only test routes, one immutable deadline and the existing native pipe owner. */
final class LivePressureRoutes {
    private static final Path ROOT = Path.of("logs", "voxy-tests", "pressure").toAbsolutePath();
    private static final Object OWNERSHIP = new Object();
    private static final java.util.concurrent.ScheduledExecutorService GUARD =
            Executors.newSingleThreadScheduledExecutor(task -> Thread.ofPlatform().daemon()
                    .name("Voxy pressure observations").unstarted(task));
    // Deadline revocation must not wait behind filesystem/logging or observation callbacks.
    private static final java.util.concurrent.ScheduledExecutorService DEADLINE =
            Executors.newSingleThreadScheduledExecutor(task -> Thread.ofPlatform().daemon()
                    .name("Voxy pressure expiry").unstarted(task));
    private static final ConcurrentHashMap<String, Pending> PENDING = new ConcurrentHashMap<>();
    private static volatile Process nativeProcess;
    private static volatile long nativeEpoch;
    private static volatile Run active, last;
    private record Pending(Process process, UUID run, long epoch, CompletableFuture<JsonObject> response) {}

    private static final class Run {
        final UUID id;
        final long deadline, epoch;
        final Process process;
        final byte[][] tokens;
        final ArrayList<String> fingerprints = new ArrayList<>();
        final Path directory;
        volatile String state = "REGISTERING", error = "";
        volatile JsonObject observation;
        volatile JsonObject timingAck;
        volatile boolean stopping;
        boolean polling;
        ScheduledFuture<?> expiry, poll;
        Run(UUID id, long deadline, Process process, long epoch, int count) {
            this.id = id; this.deadline = deadline; this.process = process; this.epoch = epoch;
            this.directory = ROOT.resolve(id.toString());
            this.tokens = new byte[count][32];
        }
    }

    private LivePressureRoutes() {}

    static void register() {
        NeoForge.EVENT_BUS.addListener(LivePressureRoutes::commands);
        NeoForge.EVENT_BUS.addListener((ServerStoppingEvent event) -> {
            Run run = active;
            if (run != null) stop(run, "SERVER_STOPPING");
        });
    }

    private static void commands(RegisterCommandsEvent event) {
        var pressure = Commands.literal("pressure")
                .requires(source -> source.hasPermission(4) && source.getEntity() == null);
        pressure.then(Commands.literal("start")
                .then(Commands.argument("run", StringArgumentType.word())
                .then(Commands.argument("deadline_unix_ms", LongArgumentType.longArg(1))
                .then(Commands.argument("count", IntegerArgumentType.integer(1, 100))
                .executes(context -> start(context.getSource(), id(context),
                        LongArgumentType.getLong(context, "deadline_unix_ms"),
                        IntegerArgumentType.getInteger(context, "count")))))));
        for (String operation : new String[]{"status", "stop"}) {
            pressure.then(Commands.literal(operation)
                    .then(Commands.argument("run", StringArgumentType.word())
                    .executes(context -> operate(context.getSource(), id(context), operation))));
        }
        pressure.then(Commands.literal("timings")
                .then(Commands.argument("run", StringArgumentType.word())
                .then(Commands.argument("enabled", StringArgumentType.word())
                .executes(context -> timings(context.getSource(), id(context),
                        StringArgumentType.getString(context, "enabled"))))));
        event.getDispatcher().register(Commands.literal("voxytest")
                .requires(source -> source.hasPermission(4) && source.getEntity() == null).then(pressure));
    }

    private static UUID id(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        try { return UUID.fromString(StringArgumentType.getString(context, "run")); }
        catch (IllegalArgumentException invalid) {
            throw new com.mojang.brigadier.exceptions.SimpleCommandExceptionType(
                    Component.literal("run must be a UUID")).create();
        }
    }

    static boolean ownedByOther(UUID id) {
        Run run = active;
        return run != null && !run.id.equals(id);
    }

    private static int start(CommandSourceStack source, UUID id, long deadline, int count) {
        try { remainingMillis(deadline, System.currentTimeMillis()); }
        catch (IllegalArgumentException invalid) { return fail(source, invalid.getMessage()); }
        Process process = nativeProcess;
        long epoch = nativeEpoch;
        if (process == null || !process.isAlive() || epoch == 0 || RustBackend.ready() == null)
            return fail(source, "debug native epoch is not ready");
        Run run;
        synchronized (OWNERSHIP) {
            if (active != null || !LiveServerTestHarness.permitsPressure(id))
                return fail(source, "another test owner is active");
            if (Files.exists(ROOT.resolve(id.toString()))) return fail(source, "pressure run ID already exists");
            run = new Run(id, deadline, process, epoch, count);
            active = last = run;
        }
        run.expiry = DEADLINE.schedule(() -> stop(run, "DEADLINE_EXPIRED"),
                Math.max(0, deadline - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
        GUARD.execute(() -> {
            try {
                Files.createDirectories(ROOT);
                Files.createDirectory(run.directory, PosixFilePermissions.asFileAttribute(
                        PosixFilePermissions.fromString("rwx------")));
                SecureRandom random = new SecureRandom();
                var prefixes = new java.util.HashSet<String>();
                CompletableFuture<?>[] ready = new CompletableFuture<?>[count];
                for (int index = 0; index < count; index++) {
                    do { random.nextBytes(run.tokens[index]); }
                    while (zero(run.tokens[index]) || !prefixes.add(HexFormat.of().formatHex(run.tokens[index], 0, 16)));
                    run.fingerprints.add(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                            .digest(run.tokens[index]), 0, 12));
                    synchronized (OWNERSHIP) {
                        if (run.stopping || active != run || nativeProcess != run.process)
                            throw new IOException("pressure ownership ended during registration");
                        ready[index] = RustBackend.register(run.tokens[index], 1000);
                    }
                }
                run.poll = GUARD.scheduleAtFixedRate(() -> poll(run), 0, 1, TimeUnit.SECONDS);
                publish(run);
                CompletableFuture.allOf(ready).whenComplete((ignored, failure) -> GUARD.execute(() -> {
                    if (run.stopping || active != run) return;
                    if (failure != null) { run.error = failure.toString(); stop(run, "REGISTRATION_FAILED"); return; }
                    try {
                        StringBuilder content = new StringBuilder();
                        for (int index = 0; index < count; index++) content.append(index).append('\t')
                                .append(HexFormat.of().formatHex(run.tokens[index])).append('\n');
                        Path file = run.directory.resolve("routes.pending");
                        Files.createFile(file, PosixFilePermissions.asFileAttribute(
                                PosixFilePermissions.fromString("rw-------")));
                        Files.writeString(file, content, StandardCharsets.US_ASCII);
                        Files.move(file, run.directory.resolve("routes.tsv"), StandardCopyOption.ATOMIC_MOVE);
                        synchronized (OWNERSHIP) {
                            if (run.stopping || active != run) return;
                            run.state = "READY";
                        }
                        publish(run);
                    } catch (IOException problem) { run.error = problem.toString(); stop(run, "ROUTE_FILE_FAILED"); }
                }));
            } catch (Exception failure) { run.error = failure.toString(); stop(run, "START_FAILED"); }
        });
        source.sendSuccess(() -> Component.literal("Pressure route registration started; inspect "
                + run.directory.resolve("status.json")), false);
        return 1;
    }

    private static int operate(CommandSourceStack source, UUID id, String operation) {
        Run run = find(id);
        if (run == null) return fail(source, "unknown pressure run");
        if (operation.equals("stop")) GUARD.execute(() -> stop(run, "OPERATOR_STOP"));
        else GUARD.execute(() -> { if (active == run) poll(run); publish(run); });
        source.sendSuccess(() -> Component.literal("Pressure " + operation + " accepted; "
                + run.directory.resolve("status.json")), false);
        return 1;
    }

    private static int timings(CommandSourceStack source, UUID id, String enabled) {
        Run run = find(id);
        if (run == null || run.stopping || active != run) return fail(source, "pressure owner is not active");
        if (!enabled.equals("on") && !enabled.equals("off")) return fail(source, "timings must be on or off");
        request(run, 8, enabled.equals("on") ? 1 : 0).whenComplete((response, failure) -> GUARD.execute(() -> {
            if (failure != null) run.error = failure.toString();
            else { run.observation = response; run.timingAck = response; }
            publish(run);
        }));
        return 1;
    }

    private static Run find(UUID id) {
        Run run = active;
        if (run != null && run.id.equals(id)) return run;
        run = last;
        return run != null && run.id.equals(id) ? run : null;
    }

    private static void stop(Run run, String reason) {
        synchronized (OWNERSHIP) {
            if (run.stopping || active != run) return;
            run.stopping = true;
            run.state = "STOPPING_" + reason;
            // Remove Java replay ownership before an acknowledgment or native replacement.
            for (byte[] token : run.tokens) RustBackend.revoke(token);
        }
        GUARD.execute(() -> {
            request(run, 7, 0).whenComplete((response, failure) -> GUARD.execute(() -> {
                if (failure != null) run.error = failure.toString();
                else run.observation = response;
                publish(run);
                poll(run);
            }));
            publish(run);
        });
    }

    private static void poll(Run run) {
        if (active != run || run.polling) return;
        run.polling = true;
        request(run, 6, 0).whenComplete((response, failure) -> GUARD.execute(() -> {
            run.polling = false;
            if (active != run) return;
            if (failure != null) run.error = failure.toString();
            else {
                run.observation = response;
                if (run.stopping && removed(response)) {
                    run.polling = true;
                    request(run, 8, 2).whenComplete((reset, resetFailure) -> GUARD.execute(() -> {
                        run.polling = false;
                        if (active != run) return;
                        if (resetFailure != null) run.error = resetFailure.toString();
                        else if (reset.has("reporting") && !reset.get("reporting").getAsBoolean()
                                && !reset.get("timings_enabled").getAsBoolean()) {
                            response.add("diagnostics_reset", reset);
                            run.timingAck = reset;
                            run.state = "CLOSED";
                            if (run.expiry != null) run.expiry.cancel(false);
                            if (run.poll != null) run.poll.cancel(false);
                            synchronized (OWNERSHIP) { if (active == run) active = null; }
                        } else run.error = "native diagnostics reset was not confirmed";
                        publish(run);
                    }));
                }
            }
            publish(run);
        }));
    }

    static long remainingMillis(long deadline, long now) {
        long remaining;
        try { remaining = Math.subtractExact(deadline, now); }
        catch (ArithmeticException overflow) { throw new IllegalArgumentException("invalid deadline", overflow); }
        if (remaining <= 0)
            throw new IllegalArgumentException("deadline must be in the future");
        return remaining;
    }

    static boolean removed(JsonObject response) {
        return response.has("ok") && response.get("ok").getAsBoolean()
                && response.has("routes") && response.get("routes").getAsInt() == 0
                && response.has("sessions") && response.get("sessions").getAsInt() == 0
                && response.has("subscriptions") && response.get("subscriptions").getAsInt() == 0
                && response.has("cleanup_pending") && !response.get("cleanup_pending").getAsBoolean();
    }

    private static CompletableFuture<JsonObject> request(Run run, int opcode, int mode) {
        if (nativeProcess != run.process || nativeEpoch != run.epoch)
            return CompletableFuture.failedFuture(new IOException("native epoch changed"));
        String request = UUID.randomUUID().toString();
        byte[] frame = frame(UUID.fromString(request), run.id, run.epoch, opcode, mode, run.tokens);
        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        PENDING.put(request, new Pending(run.process, run.id, run.epoch, future));
        RustBackend.command(run.process, frame).whenComplete((ignored, failure) -> {
            if (failure != null) {
                Pending pending = PENDING.remove(request);
                if (pending != null) pending.response.completeExceptionally(failure);
            }
        });
        return future;
    }

    static byte[] frame(UUID request, UUID run, long epoch, int opcode, int mode, byte[][] tokens) {
        if (opcode < 6 || opcode > 8) throw new IllegalArgumentException("unknown diagnostic command");
        if (opcode == 8 && (mode < 0 || mode > 2)) throw new IllegalArgumentException("unknown timing mode");
        byte[] requestBytes = request.toString().getBytes(StandardCharsets.UTF_8), runBytes = run.toString().getBytes(StandardCharsets.UTF_8);
        int suffix = opcode == 8 ? 1 : 4 + 32 * tokens.length;
        ByteBuffer frame = ByteBuffer.allocate(1 + 2 + requestBytes.length + 2 + runBytes.length + 8 + suffix)
                .order(ByteOrder.LITTLE_ENDIAN);
        frame.put((byte) opcode).putShort((short) requestBytes.length).put(requestBytes)
                .putShort((short) runBytes.length).put(runBytes).putLong(epoch);
        if (opcode == 8) frame.put((byte) mode);
        else { frame.putInt(tokens.length); for (byte[] token : tokens) frame.put(token); }
        return frame.array();
    }

    static void nativeLine(Process process, String line) {
        if (line.startsWith("VOXY_DEBUG_EPOCH ")) {
            nativeProcess = process;
            nativeEpoch = Long.parseUnsignedLong(line.substring("VOXY_DEBUG_EPOCH ".length()).trim());
        } else if (line.startsWith("VOXY_DEBUG_RESPONSE ")) {
            JsonObject response = JsonParser.parseString(line.substring("VOXY_DEBUG_RESPONSE ".length())).getAsJsonObject();
            String request = response.get("request_id").getAsString();
            Pending pending = PENDING.remove(request);
            if (pending == null) return;
            try {
                if (pending.process != process || !pending.run.toString().equals(response.get("run_id").getAsString())
                        || pending.epoch != response.get("epoch").getAsLong()) {
                    pending.response.completeExceptionally(new IOException("native response identity mismatch"));
                } else if (!response.get("ok").getAsBoolean()) {
                    pending.response.completeExceptionally(new IOException("native rejected debug request: " + response));
                } else pending.response.complete(response);
            } catch (RuntimeException malformed) { pending.response.completeExceptionally(malformed); }
        }
    }

    /** The bridge owner exited; an external launcher's exit alone does not prove native exit. */
    static void nativeExited(Process process) {
        if (nativeProcess != process) return;
        nativeProcess = null; nativeEpoch = 0;
        Run run = active;
        synchronized (OWNERSHIP) {
            if (run != null && run.process == process) {
                run.stopping = true;
                for (byte[] token : run.tokens) RustBackend.revoke(token);
                boolean directNative = run.observation != null && run.observation.has("native_pid")
                        && run.observation.get("native_pid").getAsLong() == process.pid();
                run.state = directNative ? "ABORTED_NATIVE_EXIT" : "ABORTED_BRIDGE_EXIT";
                if (directNative) active = null;
            }
        }
        PENDING.forEach((request, pending) -> {
            if (pending.process == process && PENDING.remove(request, pending))
                pending.response.completeExceptionally(new IOException("owned native process exited"));
        });
        if (run != null && run.process == process) GUARD.execute(() -> {
            if (run.expiry != null) run.expiry.cancel(false);
            if (run.poll != null) run.poll.cancel(false);
            JsonObject proof = new JsonObject();
            proof.addProperty("bridge_owner_exit_proven", true);
            proof.addProperty("native_exit_proven", run.state.equals("ABORTED_NATIVE_EXIT"));
            proof.addProperty("cleanup_pending", !run.state.equals("ABORTED_NATIVE_EXIT"));
            proof.addProperty("epoch", run.epoch);
            run.observation = proof;
            publish(run);
        });
    }

    private static void publish(Run run) {
        JsonObject state = new JsonObject();
        state.addProperty("run_id", run.id.toString());
        state.addProperty("state", run.state);
        state.addProperty("deadline_unix_ms", run.deadline);
        state.addProperty("native_epoch", run.epoch);
        state.addProperty("bridge_owner_pid", run.process.pid());
        state.addProperty("bridge_owner_start_unix_ms", run.process.toHandle().info().startInstant()
                .map(java.time.Instant::toEpochMilli).orElse(-1L));
        state.addProperty("route_count", run.tokens.length);
        state.addProperty("cap_kbps", 1000);
        var endpoint = RustBackend.ready();
        if (endpoint != null && nativeProcess == run.process) {
            state.addProperty("udp_port", endpoint.udpPort());
            state.addProperty("alpn", endpoint.alpn());
            state.addProperty("certificate_sha256", HexFormat.of().formatHex(endpoint.certificateSha256()));
        }
        state.addProperty("routes_file", run.directory.resolve("routes.tsv").toString());
        state.addProperty("error", run.error);
        var heap = java.lang.management.ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        JsonObject memory = new JsonObject();
        memory.addProperty("heap_init_bytes", heap.getInit());
        memory.addProperty("heap_max_bytes", heap.getMax());
        memory.addProperty("heap_used_bytes", heap.getUsed());
        memory.addProperty("heap_committed_bytes", heap.getCommitted());
        var collectors = new com.google.gson.JsonArray();
        for (var collector : java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()) {
            JsonObject gc = new JsonObject();
            gc.addProperty("name", collector.getName());
            gc.addProperty("count", collector.getCollectionCount());
            gc.addProperty("time_ms", collector.getCollectionTime());
            collectors.add(gc);
        }
        memory.add("gc", collectors);
        state.add("java_memory", memory);
        var fingerprints = new com.google.gson.JsonArray();
        for (String fingerprint : run.fingerprints) fingerprints.add(fingerprint);
        state.add("fingerprints", fingerprints);
        if (run.observation != null) state.add("native_observation", run.observation);
        if (run.timingAck != null) state.add("timing_ack", run.timingAck);
        LoggerFactory.getLogger("Voxy Server Debug").info("VOXY_PRESSURE_STATE {}", state);
        try {
            if (!Files.isDirectory(run.directory)) return;
            Path pending = run.directory.resolve("status.pending");
            Files.writeString(pending, state.toString() + '\n', StandardCharsets.UTF_8);
            Files.move(pending, run.directory.resolve("status.json"), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException failure) {
            LoggerFactory.getLogger("Voxy Server Debug").warn("Pressure state persistence failed", failure);
        }
    }

    private static boolean zero(byte[] token) { for (byte value : token) if (value != 0) return false; return true; }
    private static int fail(CommandSourceStack source, String reason) {
        source.sendFailure(Component.literal(reason)); return 0;
    }
}
