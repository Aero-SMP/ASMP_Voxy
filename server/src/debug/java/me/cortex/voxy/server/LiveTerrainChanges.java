package me.cortex.voxy.server;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import me.cortex.voxy.server.mixin.PressureChunkMapAccessor;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicLongArray;

/** Actual reversible block edits on one existing testing chunk, driven by elapsed time. */
public final class LiveTerrainChanges {
    public static final String STAMP = "_voxy_live_pressure";
    private static final Path ROOT = Path.of("logs", "voxy-pressure").toAbsolutePath();
    private static final Gson JSON = new Gson();
    private static final Logger LOGGER = LoggerFactory.getLogger("Voxy terrain pressure");
    private static volatile Run active;

    static void register() {
        NeoForge.EVENT_BUS.addListener(LiveTerrainChanges::commands);
        NeoForge.EVENT_BUS.addListener(LiveTerrainChanges::tick);
        NeoForge.EVENT_BUS.addListener((ServerStoppingEvent event) -> {
            Run run = active;
            if (run != null) restore(run);
        });
    }

    private static void commands(RegisterCommandsEvent event) {
        var root = Commands.literal("voxyload").requires(source -> source.hasPermission(4));
        root.then(Commands.literal("start").then(Commands.argument("run", StringArgumentType.word())
                .then(Commands.argument("seconds", IntegerArgumentType.integer(1))
                .then(Commands.argument("first", IntegerArgumentType.integer(1))
                .then(Commands.argument("second", IntegerArgumentType.integer(1))
                .executes(context -> start(context.getSource(), StringArgumentType.getString(context, "run"),
                        IntegerArgumentType.getInteger(context, "seconds"),
                        IntegerArgumentType.getInteger(context, "first"),
                        IntegerArgumentType.getInteger(context, "second"))))))));
        for (String operation : new String[]{"stop", "restore"}) {
            root.then(Commands.literal(operation).then(Commands.argument("run", StringArgumentType.word())
                    .executes(context -> finish(context.getSource(), StringArgumentType.getString(context, "run")))));
        }
        event.getDispatcher().register(root);
        var backend = Commands.literal("voxybackend").requires(source -> source.hasPermission(4)
                && source.getServer().getPort() == 25587);
        backend.then(Commands.literal("stop").executes(context -> {
            RustBackend.stop();
            context.getSource().sendSuccess(() -> Component.literal("testing Voxy backend stopped; Minecraft remains online"), false);
            return 1;
        }));
        backend.then(Commands.literal("start").executes(context -> {
            RustBackend.start();
            context.getSource().sendSuccess(() -> Component.literal("testing Voxy backend start requested"), false);
            return 1;
        }));
        backend.then(Commands.literal("status").executes(context -> {
            context.getSource().sendSuccess(() -> Component.literal(RustBackend.status().toString()), false);
            return 1;
        }));
        event.getDispatcher().register(backend);
    }

    private static int start(CommandSourceStack source, String id, int seconds, int first, int second) {
        try {
            if (!id.matches("[A-Za-z0-9_.-]+") || id.equals(".") || id.equals("..")) throw new IllegalArgumentException("invalid run name");
            if (source.getServer().getPort() != 25587
                    || !Path.of("").toRealPath().equals(Path.of("/home/aerosmp/Desktop/Voxy_Testing"))) {
                throw new IllegalStateException("terrain load is restricted to Voxy_Testing:25587");
            }
            if (active != null) throw new IllegalStateException("a terrain load already owns its patch");
            ServerLevel level = source.getServer().overworld();
            int x = Integer.getInteger("voxy.pressure.chunkX", 0), z = Integer.getInteger("voxy.pressure.chunkZ", 0);
            // The caller checks that this chunk exists in the Anvil header before deployment.
            LevelChunk chunk = level.getChunk(x, z);
            BlockPos[] positions = new BlockPos[16 * 16 * 4];
            BlockState[] original = new BlockState[positions.length];
            int bottom = level.getMaxBuildHeight() - 8;
            for (int i = 0; i < positions.length; i++) {
                positions[i] = new BlockPos((x << 4) + (i & 15), bottom + (i >>> 8), (z << 4) + ((i >>> 4) & 15));
                original[i] = level.getBlockState(positions[i]);
                if (original[i] != Blocks.AIR.defaultBlockState() || level.getBlockEntity(positions[i]) != null) {
                    throw new IllegalStateException("load patch is not empty air; no blocks changed");
                }
            }
            Files.createDirectories(ROOT);
            Path evidence = ROOT.resolve(id + ".jsonl");
            Files.createFile(evidence);
            var run = new Run(id, level, chunk, positions, original, seconds, new int[]{first, second}, evidence);
            JsonObject rollback = new JsonObject();
            rollback.addProperty("run_id", id);
            rollback.addProperty("dimension", level.dimension().location().toString());
            rollback.addProperty("chunk_x", x); rollback.addProperty("chunk_z", z);
            rollback.addProperty("bottom_y", bottom); rollback.addProperty("height", 4);
            rollback.add("original_states", JSON.toJsonTree(java.util.Arrays.stream(original).map(Object::toString).toList()));
            Files.writeString(ROOT.resolve(id + "-rollback.json"), JSON.toJson(rollback), StandardOpenOption.CREATE_NEW);
            active = run;
            event(run, "started", null);
            source.sendSuccess(() -> Component.literal("terrain load started " + id + " chunk=" + x + ',' + z), false);
            return 1;
        } catch (Exception failure) {
            source.sendFailure(Component.literal(failure.toString())); return 0;
        }
    }

    private static void tick(ServerTickEvent.Post event) {
        Run run = active;
        if (run == null || run.stopped) return;
        try {
            long now = System.nanoTime();
            double elapsed = (now - run.phaseStart) / 1_000_000_000.0;
            long target = elapsed >= run.seconds ? (long) Math.ceil(elapsed * run.rates[run.phase])
                    : (long) (elapsed * run.rates[run.phase]);
            while (run.changed[run.phase] < target) {
                int index = (int) (run.ordinal++ % run.positions.length);
                BlockState next = run.level.getBlockState(run.positions[index]).is(Blocks.STONE)
                        ? Blocks.GLASS.defaultBlockState() : Blocks.STONE.defaultBlockState();
                if (!run.level.setBlock(run.positions[index], next, 2 | 16)) {
                    throw new IllegalStateException("block mutation was not applied");
                }
                run.changed[run.phase]++;
                run.lastChange[run.phase] = (System.nanoTime() - run.starts[run.phase]) / 1_000_000_000.0;
            }
            if (now - run.lastSave >= 1_000_000_000L || elapsed >= run.seconds) {
                // Serialize only the owned chunk. Native pressure still observes real completed Anvil writes.
                ((PressureChunkMapAccessor) run.level.getChunkSource().chunkMap).voxy$save(run.chunk);
                run.lastSave = now;
                event(run, "progress", null);
            }
            if (elapsed >= run.seconds) {
                event(run, "phase_completed", null);
                if (run.phase == 0) {
                    run.phase = 1; run.phaseStart = System.nanoTime(); run.starts[1] = run.phaseStart; run.lastSave = run.phaseStart;
                    event(run, "progress", null);
                } else {
                    run.stopped = true;
                    try { event(run, "stopped", null); }
                    finally { restore(run); }
                }
            }
        } catch (Exception failure) {
            try { event(run, "error", failure.toString()); }
            finally { restore(run); }
        }
    }

    private static int finish(CommandSourceStack source, String id) {
        Run run = active;
        if (run == null) {
            return recover(source, id);
        }
        if (!run.id.equals(id)) { source.sendFailure(Component.literal("terrain load owner differs")); return 0; }
        run.stopped = true;
        try { event(run, "stopped", null); }
        finally { restore(run); }
        source.sendSuccess(() -> Component.literal("terrain patch restoration submitted " + id), false); return 1;
    }

    private static int recover(CommandSourceStack source, String id) {
        try {
            if (!id.matches("[A-Za-z0-9_.-]+") || id.equals(".") || id.equals("..")) throw new IllegalArgumentException("invalid run name");
            Path rollback = ROOT.resolve(id + "-rollback.json");
            if (!Files.isRegularFile(rollback)) {
                source.sendSuccess(() -> Component.literal("no active terrain load or rollback"), false); return 1;
            }
            if (source.getServer().getPort() != 25587 || !Path.of("").toRealPath().equals(Path.of("/home/aerosmp/Desktop/Voxy_Testing"))) {
                throw new IllegalStateException("restore is restricted to Voxy_Testing");
            }
            // Avoid repeating a restored run and retain its original completed-save evidence.
            Path evidence = ROOT.resolve(id + ".jsonl");
            try (var lines = Files.lines(evidence)) {
                if (lines.anyMatch(line -> restoredReceipt(line, id))) {
                    source.sendSuccess(() -> Component.literal("terrain patch already restored " + id), false); return 1;
                }
            }
            JsonObject record = JsonParser.parseString(Files.readString(rollback)).getAsJsonObject();
            if (!record.get("run_id").getAsString().equals(id)
                    || !record.get("dimension").getAsString().equals("minecraft:overworld")
                    || record.get("height").getAsInt() != 4
                    || record.getAsJsonArray("original_states").size() != 1024) throw new IllegalStateException("invalid rollback");
            for (var state : record.getAsJsonArray("original_states")) {
                if (!state.getAsString().equals(Blocks.AIR.defaultBlockState().toString())) throw new IllegalStateException("rollback is not the isolated air patch");
            }
            ServerLevel level = source.getServer().overworld();
            int x = record.get("chunk_x").getAsInt(), z = record.get("chunk_z").getAsInt(), bottom = record.get("bottom_y").getAsInt();
            if (bottom != level.getMaxBuildHeight() - 8) throw new IllegalStateException("rollback height differs");
            LevelChunk chunk = level.getChunk(x, z);
            BlockPos[] positions = new BlockPos[1024]; BlockState[] original = new BlockState[1024];
            for (int i = 0; i < positions.length; i++) {
                positions[i] = new BlockPos((x << 4) + (i & 15), bottom + (i >>> 8), (z << 4) + ((i >>> 4) & 15));
                BlockState current = level.getBlockState(positions[i]);
                if (current != Blocks.AIR.defaultBlockState() && current != Blocks.STONE.defaultBlockState()
                        && current != Blocks.GLASS.defaultBlockState()) throw new IllegalStateException("patch changed externally; restore refused");
                original[i] = Blocks.AIR.defaultBlockState();
            }
            Run recovered = new Run(id, level, chunk, positions, original, 1, new int[]{300, 1000}, evidence);
            active = recovered; restore(recovered);
            source.sendSuccess(() -> Component.literal("terrain patch restoration submitted " + id), false); return 1;
        } catch (Exception failure) { source.sendFailure(Component.literal(failure.toString())); return 0; }
    }

    private static void restore(Run run) {
        try {
            run.stopped = true;
            for (int i = 0; i < run.positions.length; i++) run.level.setBlock(run.positions[i], run.original[i], 2 | 16);
            ((PressureChunkMapAccessor) run.level.getChunkSource().chunkMap).voxy$save(run.chunk);
            for (int i = 0; i < run.positions.length; i++) {
                if (run.level.getBlockState(run.positions[i]) != run.original[i]) throw new IllegalStateException("restore mismatch");
            }
            run.restoring = true;
            // Publish again with the restore receipt. Completion is logged only by successful storage RETURN.
            run.chunk.setUnsaved(true);
            ((PressureChunkMapAccessor) run.level.getChunkSource().chunkMap).voxy$save(run.chunk);
        } catch (Exception failure) { event(run, "error", "restore failed: " + failure); }
    }

    public static void serialized(ServerLevel level, ChunkAccess chunk, CompoundTag tag) {
        Run run = active;
        if (run == null || run.level != level || !chunk.getPos().equals(run.chunk.getPos())) return;
        CompoundTag stamp = new CompoundTag();
        stamp.putString("run", run.id);
        stamp.putLongArray("changes", run.changed.clone());
        stamp.putLongArray("last_changes", new long[]{Double.doubleToLongBits(run.lastChange[0]), Double.doubleToLongBits(run.lastChange[1])});
        stamp.putLongArray("phase_starts", run.starts.clone());
        stamp.putInt("phase", run.phase);
        stamp.putBoolean("restored", run.restoring);
        tag.put(STAMP, stamp);
    }

    public static void saved(CompoundTag stamp) {
        Run run = active;
        if (run == null || stamp == null || !run.id.equals(stamp.getString("run"))) return;
        long[] counts = stamp.getLongArray("changes");
        long[] starts = stamp.getLongArray("phase_starts");
        long[] lastChanges = stamp.getLongArray("last_changes");
        int currentPhase = stamp.getInt("phase");
        if (counts.length != 2 || starts.length != 2 || lastChanges.length != 2
                || currentPhase < 0 || currentPhase >= 2) {
            evidenceFailure(run, new IllegalStateException("invalid completed-save receipt"));
            return;
        }
        // This callback runs on the storage worker. Use the immutable serialized snapshot,
        // not counters or phase timing concurrently updated by the Minecraft thread.
        for (int phase = 0; phase < 2; phase++) {
            run.saved.accumulateAndGet(phase, counts[phase], Math::max);
            if (starts[phase] != 0) {
                event(run, "saved", null, phase, counts[phase],
                        Double.longBitsToDouble(lastChanges[phase]), starts[phase]);
            }
        }
        if (stamp.getBoolean("restored")) {
            event(run, "restored", null, currentPhase, counts[currentPhase],
                    Double.longBitsToDouble(lastChanges[currentPhase]), starts[currentPhase]);
            if (active == run) active = null;
        }
    }

    private static boolean restoredReceipt(String line, String id) {
        try {
            JsonObject receipt = JsonParser.parseString(line).getAsJsonObject();
            return receipt.get("run_id").getAsString().equals(id)
                    && receipt.get("event").getAsString().equals("restored");
        } catch (RuntimeException tornEvidence) {
            // A crash can interrupt the final JSONL append. The rollback file is parsed
            // strictly elsewhere; incomplete evidence is never authority to skip restoring.
            return false;
        }
    }

    private static void event(Run run, String kind, String error) {
        int phase = run.phase;
        event(run, kind, error, phase, run.changed[phase], run.lastChange[phase], run.starts[phase]);
    }

    private static void event(Run run, String kind, String error, int phase, long changed,
                              double lastChange, long phaseStart) {
        try {
            JsonObject event = new JsonObject();
            event.addProperty("run_id", run.id);
            event.addProperty("event", kind);
            event.addProperty("phase", run.rates[phase]);
            event.addProperty("phase_elapsed_seconds", (System.nanoTime() - phaseStart) / 1_000_000_000.0);
            event.addProperty("changed_total", changed);
            event.addProperty("saved_total", run.saved.get(phase));
            event.addProperty("last_change_elapsed_seconds", lastChange);
            event.addProperty("evidence_failed", run.evidenceFailed);
            if (error != null) event.addProperty("error", error);
            synchronized (run) {
                Files.writeString(run.evidence, JSON.toJson(event) + '\n', StandardOpenOption.APPEND);
            }
        } catch (IOException | RuntimeException failure) {
            evidenceFailure(run, failure);
        }
    }

    private static void evidenceFailure(Run run, Exception failure) {
        run.evidenceFailed = true;
        // Evidence failure invalidates proof; it cannot turn an already-completed Anvil
        // write into a save failure or prevent restoration of the owned test patch.
        try { LOGGER.error("VOXY_PRESSURE_EVIDENCE_FAILED run={}", run.id, failure); }
        catch (RuntimeException loggerFailure) { /* Keep world/storage semantics independent. */ }
    }

    private static final class Run {
        final String id;
        final ServerLevel level;
        final LevelChunk chunk;
        final BlockPos[] positions;
        final BlockState[] original;
        final int seconds;
        final int[] rates;
        final Path evidence;
        final long[] changed = new long[2];
        final AtomicLongArray saved = new AtomicLongArray(2);
        final long[] starts = new long[2];
        final double[] lastChange = new double[2];
        int phase;
        volatile boolean stopped;
        volatile boolean restoring;
        volatile boolean evidenceFailed;
        long phaseStart = System.nanoTime(), lastSave = phaseStart, ordinal;
        Run(String id, ServerLevel level, LevelChunk chunk, BlockPos[] positions, BlockState[] original,
            int seconds, int[] rates, Path evidence) {
            this.id = id; this.level = level; this.chunk = chunk; this.positions = positions;
            this.original = original; this.seconds = seconds; this.rates = rates; this.evidence = evidence;
            this.starts[0] = phaseStart;
        }
    }
}
