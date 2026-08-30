package me.cortex.voxy.commonImpl.lod;

import me.cortex.voxy.commonImpl.lod.LodStreamingService;
import me.cortex.voxy.commonImpl.mixin.minecraft.InvokerServerChunkCache;
import me.cortex.voxy.commonImpl.lod.LodNetwork;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.storage.LevelResource;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public final class LodGenerationService {
    private static final LodGenerationService INSTANCE = new LodGenerationService();

    private static class DimensionState {
        final ServerLevel level;
        final LongSet completedChunks = LongSets.synchronize(new LongOpenHashSet());
        final LongSet trackedChunks = LongSets.synchronize(new LongOpenHashSet());
        final Map<Long, Node> graphRoots = new ConcurrentHashMap<>();
        final Set<Long> trackedBatches = ConcurrentHashMap.newKeySet();
        final Map<Long, AtomicInteger> batchCounters = new ConcurrentHashMap<>();
        final AtomicInteger remainingInRadius = new AtomicInteger(0);
        // chunks that failed to finish, retry count so we give up instead of looping
        final Map<Long, Integer> failCounts = new ConcurrentHashMap<>();
        boolean loaded;

        DimensionState(ServerLevel level) {
            this.level = level;
        }
    }

    // give up on a chunk after this many failed tries
    private static final int MAX_CHUNK_RETRIES = 3;

    private final Map<ResourceKey<Level>, DimensionState> dimensionStates = new ConcurrentHashMap<>();

    private final AtomicInteger activeTaskCount = new AtomicInteger(0);
    private final AtomicLong chunksQueued = new AtomicLong(), chunksCompleted = new AtomicLong();
    private final AtomicLong chunksFailed = new AtomicLong(), chunksSkipped = new AtomicLong();
    private final long[] rollingHistory = new long[10];
    private int historyIndex;
    private long lastCompletedCount, lastStatsTickTime;
    private final AtomicBoolean running = new AtomicBoolean();

    private final long[] recentTickTimes = new long[20];
    private int tickTimeIndex;
    private long lastTickNanos;
    private final AtomicBoolean throttled = new AtomicBoolean();
    private volatile double loadFactor = 1.0;
    private static final double MSPT_SOFT = 1000.0 / 22.0;
    private static final double MSPT_HARD = 75.0;
    private Semaphore throttle;
    private MinecraftServer server;
    private ResourceKey<Level> currentDimensionKey;
    private ServerLevel currentLevel;
    private final Map<UUID, ChunkPos> lastPlayerPositions = new ConcurrentHashMap<>();
    private BooleanSupplier pauseCheck = () -> false;

    private Thread workerThread;
    private final AtomicBoolean workerRunning = new AtomicBoolean(false);
    // rotated so one player doesn't hog the worker
    private int fairnessCursor;
    private int syncPruneCounter;
    private static final int SYNC_PRUNE_INTERVAL_TICKS = 600;

    // only run catchup every so often, it doesn't need to fire every loop
    private static final long CATCHUP_INTERVAL_MS = 400;
    // chunks resent per catchup pass per player, kept small to avoid tick spikes
    private static final int CATCHUP_BATCH = 8;
    private long lastCatchupMs;

    private final Map<ResourceKey<Level>, Map<Long, IntSet>> dirtySections = new ConcurrentHashMap<>();
    private final Map<ResourceKey<Level>, Long> lastDirtyProcessTimes = new ConcurrentHashMap<>();
    private static final int MAX_DIRTY_CHUNKS_PER_CYCLE = 64;
    private static final long MIN_DIRTY_PROCESS_INTERVAL_MS = 500;

    private final Set<ServerPlayer> players = ConcurrentHashMap.newKeySet();
    private final Map<UUID, LongSet> syncedChunks = new ConcurrentHashMap<>();
    private final Set<UUID> moddedPlayers = ConcurrentHashMap.newKeySet();
    private final Set<UUID> needsBackfill = ConcurrentHashMap.newKeySet();
    private final Map<UUID, ResourceKey<Level>> lastDimension = new ConcurrentHashMap<>();

    // track these so we can clear them on shutdown, a leftover one hangs the save screen
    private final Map<ServerLevel, LongSet> appliedTickets = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<Consumer<Set<ServerLevel>>> pendingTicketOps = new ConcurrentLinkedQueue<>();

    private LodGenerationService() {}

    public static LodGenerationService getInstance() {
        return INSTANCE;
    }

    private DimensionState getOrSetupState(ServerLevel level) {
        return dimensionStates.computeIfAbsent(level.dimension(), ignored -> new DimensionState(level));
    }

    public void initialize(MinecraftServer server) {
        this.server = server;
        this.running.set(true);
        this.pauseCheck = () -> false;
        LodStreamingConfig.load();
        this.throttle = new Semaphore(LodStreamingConfig.DATA.maxActiveTasks);
        startWorker();
        LodStreamingService.LOGGER.info("LOD generation service initialized");
    }

    public void shutdown() {
        running.set(false);
        stopWorker();

        // clear our tickets or the world hangs on the save screen when leaving
        releaseAllTickets();

        for (var entry : dimensionStates.entrySet()) {
            DimensionState state = entry.getValue();
            if (state.loaded) {
                saveChunks(state.level, entry.getKey(), state.completedChunks);
            }
        }

        dimensionStates.clear();
        pendingTicketOps.clear();
        server = null;
        resetStats();
        activeTaskCount.set(0);
        resetTpsMonitor();
        currentDimensionKey = null;
        currentLevel = null;
        lastPlayerPositions.clear();
    }

    private void startWorker() {
        if (workerRunning.getAndSet(true)) return;
        workerThread = new Thread(this::workerLoop, "Voxy-LOD-Generation");
        workerThread.setDaemon(true);
        workerThread.start();
    }

    private void stopWorker() {
        workerRunning.set(false);
        if (workerThread != null) {
            workerThread.interrupt();
            try {
                workerThread.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            workerThread = null;
        }
    }

    private void workerLoop() {
        while (workerRunning.get() && running.get()) {
            try {
                if (!LodStreamingConfig.DATA.enabled || server == null) {
                    Thread.sleep(100);
                    continue;
                }

                if (!LodStreamingService.isRenderingEnabled()) {
                    Thread.sleep(500);
                    continue;
                }

                if (pauseCheck.getAsBoolean()) {
                    Thread.sleep(500);
                    continue;
                }

                List<ServerPlayer> players = new ArrayList<>(getPlayers());
                if (players.isEmpty()) {
                    Thread.sleep(1000);
                    continue;
                }

                fairnessCursor = (fairnessCursor + 1) % players.size();
                players = rotated(players, fairnessCursor);

                // catchup is a resend pass, it doesn't need to run every loop
                long now = System.currentTimeMillis();
                if (now - lastCatchupMs >= CATCHUP_INTERVAL_MS) {
                    lastCatchupMs = now;
                    runCatchup(players);
                }

                // ease off under lag instead of stopping dead
                double load = loadFactor;
                if (load <= 0.0) {
                    Thread.sleep(200);
                    continue;
                }
                int budget = Math.max(1, (int) Math.ceil(LodStreamingConfig.DATA.maxActiveTasks * load));
                boolean dispatched = dispatchGeneration(players, budget);

                // the throttle semaphore already paces us, blocking in dispatchBatch
                // when tasks are in flight. a bigger floor here keeps us from spamming
                // the main thread queue when there's nothing to acquire
                Thread.sleep(dispatched ? 20 : 100);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                LodStreamingService.LOGGER.error("error in worker loop", e);
                try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
            }
        }
    }

    private int generationRadius() {
        return LodStreamingConfig.DATA.generationRadius;
    }

    // resend completed in-range chunks each player is missing, nearest first, joiners first
    private void runCatchup(List<ServerPlayer> players) {
        List<ServerPlayer> order = new ArrayList<>(players.size());
        for (ServerPlayer p : players) if (needsBackfill(p.getUUID())) order.add(p);
        for (ServerPlayer p : players) if (!needsBackfill(p.getUUID())) order.add(p);

        for (ServerPlayer player : order) {
            UUID uuid = player.getUUID();
            if (!isModded(uuid)) continue;
            var synced = getSyncedChunks(uuid);
            if (synced == null) continue;

            DimensionState ds = getOrSetupState((ServerLevel) player.level());
            List<ChunkPos> syncBatch = new ArrayList<>();
            // small slice per pass, each chunk serializes on the main thread so a big
            // batch is a tick spike. backfill continues over the next passes anyway
            collectCompletedInRange(ds, player.chunkPosition(), generationRadius(), synced, syncBatch, CATCHUP_BATCH);
            if (syncBatch.isEmpty()) {
                clearBackfill(uuid);
                continue;
            }

            // mark synced now so the next pass skips these, the send below unmarks any
            // it couldn't send so an unloaded chunk retries instead of leaving a hole
            for (ChunkPos pos : syncBatch) synced.add(pos.toLong());

            final List<ChunkPos> finalBatch = syncBatch;
            final ServerLevel level = ds.level;
            server.execute(() -> {
                ServerPlayer p = server.getPlayerList().getPlayer(uuid);
                if (p == null) {
                    // player gone, drop the marks so a rejoin re-syncs
                    var s = getSyncedChunks(uuid);
                    if (s != null) for (ChunkPos pos : finalBatch) s.remove(pos.toLong());
                    return;
                }
                var s = getSyncedChunks(uuid);
                for (ChunkPos pos : finalBatch) {
                    LevelChunk c = level.getChunkSource().getChunk(pos.x, pos.z, false);
                    if (c != null) {
                        // sendLODData rechecks range and sets the synced flag
                        LodNetwork.sendLODData(p, c);
                    } else if (s != null) {
                        // not loaded, clear the mark so we try again later
                        s.remove(pos.toLong());
                    }
                }
            });
        }
    }

    // generate missing chunks nearest first, one batch per player per pass so one
    // player with a big frontier can't eat the whole budget and starve the rest
    private boolean dispatchGeneration(List<ServerPlayer> players, int budget) {
        int dispatched = 0;
        int n = players.size();
        if (n == 0) return false;

        // each player's share, rounded up so a small budget still makes progress
        int perPlayerCap = Math.max(1, (budget + n - 1) / n);

        // per player: slice left, and whether they're out of work
        int[] remainingSlice = new int[n];
        boolean[] exhausted = new boolean[n];
        Arrays.fill(remainingSlice, perPlayerCap);
        int activePlayers = n;

        // cycle until budget spent or nobody has work
        while (dispatched < budget && activePlayers > 0) {
            for (int i = 0; i < n && dispatched < budget; i++) {
                if (exhausted[i] || remainingSlice[i] <= 0) continue;

                ServerPlayer player = players.get(i);
                DimensionState ds = getOrSetupState((ServerLevel) player.level());
                int radius = generationRadius();

                List<ChunkPos> batch = findWork(ds, player.chunkPosition(), radius, ds.trackedBatches);
                if (batch == null) {
                    exhausted[i] = true;
                    activePlayers--;
                    continue;
                }

                int limit = Math.min(remainingSlice[i], budget - dispatched);
                int sent = dispatchBatch(ds, batch, limit);
                dispatched += sent;
                remainingSlice[i] -= sent;
                if (remainingSlice[i] <= 0) {
                    // player used up their share this pass
                    exhausted[i] = true;
                    activePlayers--;
                }
            }
        }
        return dispatched > 0;
    }

    // dispatch up to limit chunks from one batch, returns how many were sent
    private int dispatchBatch(DimensionState state, List<ChunkPos> batch, int limit) {
        ChunkPos batchHead = batch.get(0);
        long batchKey = getBatchKey(batchHead.x, batchHead.z);
        state.batchCounters.put(batchKey, new AtomicInteger(batch.size()));

        List<ChunkPos> preFiltered = new ArrayList<>(batch.size());
        for (ChunkPos pos : batch) {
            long key = pos.toLong();
            if (state.completedChunks.contains(key) || state.trackedChunks.contains(key)) {
                // already done or in flight, this isn't work. just drop it from the
                // batch counter, don't re-run onSuccess (which re-marks the graph and
                // inflates the skipped stat every time a boundary batch comes back)
                decrementBatch(state, pos);
            } else {
                preFiltered.add(pos);
            }
        }
        if (preFiltered.isEmpty()) {
            state.trackedBatches.remove(batchKey);
            state.batchCounters.remove(batchKey);
            return 0;
        }

        List<ChunkPos> readyToGenerate = new ArrayList<>();
        int processedCount = 0;
        for (ChunkPos pos : preFiltered) {
            if (!workerRunning.get() || processedCount >= limit) break;

            boolean acquired;
            try {
                acquired = throttle.tryAcquire(50, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            if (!acquired) break;

            processedCount++;
            if (state.trackedChunks.add(pos.toLong())) {
                activeTaskCount.incrementAndGet();
                chunksQueued.incrementAndGet();
                readyToGenerate.add(pos);
            } else {
                throttle.release();
                onFailure(state, pos);
            }
        }

        // release the batch tracking so findWork can return the rest
        if (processedCount < preFiltered.size()) {
            state.trackedBatches.remove(batchKey);
            state.batchCounters.remove(batchKey);
        }

        if (!readyToGenerate.isEmpty()) {
            final DimensionState finalState = state;
            server.execute(() -> {
                ServerChunkCache cache = finalState.level.getChunkSource();
                List<ChunkPos> actuallyGenerate = new ArrayList<>();
                for (ChunkPos pos : readyToGenerate) {
                    if (finalState.level.hasChunk(pos.x, pos.z)) {
                        LevelChunk existingChunk = finalState.level.getChunk(pos.x, pos.z);
                        if (existingChunk != null && !existingChunk.isEmpty()) {
                            LodStreamingService.ingestChunk(existingChunk);
                            LodNetwork.broadcastLODData(existingChunk);
                        }
                        onSuccess(finalState, pos);
                        completeTask(finalState, pos);
                    } else {
                        queueTicketAdd(finalState.level, pos);
                        actuallyGenerate.add(pos);
                    }
                }
                if (!actuallyGenerate.isEmpty()) {
                    processPendingTickets();
                    for (ChunkPos pos : actuallyGenerate) {
                        ((InvokerServerChunkCache) cache).invokeGetChunkFutureMainThread(pos.x, pos.z, ChunkStatus.FULL, true)
                            .whenCompleteAsync((result, throwable) -> {
                                if (throwable == null && result != null && result.isSuccess() && result.orElse(null) instanceof LevelChunk chunk) {
                                    onSuccess(finalState, pos);
                                    if (!chunk.isEmpty()) {
                                        LodStreamingService.ingestChunk(chunk);
                                        LodNetwork.broadcastLODData(chunk);
                                    }
                                } else {
                                    onFailure(finalState, pos);
                                }
                                cleanupTask(finalState.level, pos);
                            }, server);
                    }
                }
            });
        }
        return processedCount;
    }

    public void tick() {
        if (!running.get() || server == null) return;

        processPendingTickets();

        tickTpsMonitor();
        tickStats();
        checkPlayerMovement();

        // drop far-away synced entries now and then so the set can't grow forever
        if (++syncPruneCounter >= SYNC_PRUNE_INTERVAL_TICKS) {
            syncPruneCounter = 0;
            pruneSyncedChunks();
        }

        Set<ServerLevel> activeLevels = new HashSet<>();
        for (ServerPlayer player : getPlayers()) {
            activeLevels.add((ServerLevel) player.level());
        }
        for (ServerLevel level : activeLevels) {
            processDirty(level);
        }
    }

    private void checkPlayerMovement() {
        var players = getPlayers();
        if (players.isEmpty()) {
            if (!lastPlayerPositions.isEmpty()) {
                lastPlayerPositions.clear();
            }
            return;
        }

        boolean shouldRescan = false;
        Map<ServerLevel, Integer> levelCounts = new HashMap<>();

        for (ServerPlayer player : players) {
            levelCounts.merge((ServerLevel) player.level(), 1, Integer::sum);
            ChunkPos currentPos = player.chunkPosition();
            ChunkPos lastPos = lastPlayerPositions.get(player.getUUID());

            // on a dim change, treat it like a big move so we rescan and backfill
            if (handleDimensionChange(player)) {
                lastPlayerPositions.put(player.getUUID(), currentPos);
                shouldRescan = true;
                continue;
            }

            if (lastPos == null || distSq(lastPos, currentPos) >= 4) {
                lastPlayerPositions.put(player.getUUID(), currentPos);
                shouldRescan = true;
            }
        }

        // only switch level when another has strictly more players
        ServerLevel majorLevel = currentLevel;
        int maxCount = levelCounts.getOrDefault(currentLevel, 0);

        for (var entry : levelCounts.entrySet()) {
            if (entry.getValue() > maxCount) {
                maxCount = entry.getValue();
                majorLevel = entry.getKey();
            }
        }

        if (majorLevel != currentLevel && majorLevel != null) {
            setupLevel(majorLevel);
            return;
        }

        Set<UUID> currentPlayerIds = new HashSet<>();
        for (ServerPlayer p : players) currentPlayerIds.add(p.getUUID());
        if (lastPlayerPositions.size() > currentPlayerIds.size()) {
            lastPlayerPositions.keySet().removeIf(uuid -> !currentPlayerIds.contains(uuid));
            shouldRescan = true;
        }

        if (shouldRescan) {
            restartScan();
        }
    }

    // drop synced entries well outside any player range, 2x margin avoids boundary churn
    private void pruneSyncedChunks() {
        var players = new ArrayList<>(getPlayers());
        if (players.isEmpty()) return;

        long marginChunks = (long) LodStreamingConfig.DATA.generationRadius * 2L;
        long marginSq = marginChunks * marginChunks;

        for (ServerPlayer player : players) {
            var synced = getSyncedChunks(player.getUUID());
            if (synced == null) continue;
            ChunkPos center = player.chunkPosition();

            synchronized (synced) {
                var it = synced.iterator();
                while (it.hasNext()) {
                    long key = it.nextLong();
                    int cx = ChunkPos.getX(key);
                    int cz = ChunkPos.getZ(key);
                    long dx = cx - center.x;
                    long dz = cz - center.z;
                    if (dx * dx + dz * dz > marginSq) {
                        it.remove();
                    }
                }
            }
        }
    }

    private double distSq(ChunkPos a, ChunkPos b) {
        int dx = a.x - b.x;
        int dz = a.z - b.z;
        return (double) dx * dx + dz * dz;
    }

    private void setupLevel(ServerLevel newLevel) {
        if (currentLevel != null && currentDimensionKey != null) {
            DimensionState oldState = dimensionStates.get(currentDimensionKey);
            if (oldState != null) {
                saveChunks(currentLevel, currentDimensionKey, oldState.completedChunks);
            }
        }

        currentLevel = newLevel;
        currentDimensionKey = newLevel.dimension();
        DimensionState state = getOrSetupState(newLevel);

        if (!state.loaded) {
            loadChunks(newLevel, currentDimensionKey, state.completedChunks);
            synchronized(state.completedChunks) {
                for (long pos : state.completedChunks) {
                    markChunkCompleted(state, ChunkPos.getX(pos), ChunkPos.getZ(pos));
                }
            }
            state.loaded = true;
        }

        restartScan();
    }

    private void restartScan() {
        var players = getPlayers();
        if (players.isEmpty()) return;

        Map<DimensionState, Integer> maxCounts = new HashMap<>();
        for (ServerPlayer player : players) {
            DimensionState state = getOrSetupState((ServerLevel) player.level());
            int radius = generationRadius();
            int missing = countMissingInRange(state, player.chunkPosition(), radius);
            maxCounts.merge(state, missing, Math::max);
        }

        maxCounts.forEach((state, count) -> state.remainingInRadius.set(count));
    }

    private void updateThrottleCapacity() {
        int target = LodStreamingConfig.DATA.maxActiveTasks;
        int available = throttle.availablePermits();
        int maxPossible = available + activeTaskCount.get();
        if (target > maxPossible) {
            throttle.release(target - maxPossible);
        }
    }

    private void processPendingTickets() {
        Consumer<Set<ServerLevel>> op;
        Set<ServerLevel> modifiedLevels = new HashSet<>();
        while ((op = pendingTicketOps.poll()) != null) {
            op.accept(modifiedLevels);
        }
        for (ServerLevel level : modifiedLevels) {
            ((InvokerServerChunkCache) level.getChunkSource()).invokeRunDistanceManagerUpdates();
        }
    }

    // removes every forced ticket we applied, so no chunk stays pinned after shutdown
    private void releaseAllTickets() {
        // drop queued ops first so we don't re-add a ticket we're about to remove
        pendingTicketOps.clear();

        for (var entry : appliedTickets.entrySet()) {
            ServerLevel level = entry.getKey();
            LongSet positions = entry.getValue();
            ServerChunkCache cache = level.getChunkSource();
            synchronized (positions) {
                var it = positions.iterator();
                while (it.hasNext()) {
                    long key = it.nextLong();
                    ChunkPos pos = new ChunkPos(key);
                    cache.removeRegionTicket(TicketType.FORCED, pos, 0, pos);
                }
            }
            ((InvokerServerChunkCache) cache).invokeRunDistanceManagerUpdates();
        }
        appliedTickets.clear();
    }

    private void queueTicketAdd(ServerLevel level, ChunkPos pos) {
        pendingTicketOps.add(modifiedLevels -> {
            level.getChunkSource().addRegionTicket(TicketType.FORCED, pos, 0, pos);
            appliedTickets.computeIfAbsent(level, k -> LongSets.synchronize(new LongOpenHashSet())).add(pos.toLong());
            modifiedLevels.add(level);
        });
    }

    private void queueTicketRemove(ServerLevel level, ChunkPos pos) {
        pendingTicketOps.add(modifiedLevels -> {
            level.getChunkSource().removeRegionTicket(TicketType.FORCED, pos, 0, pos);
            LongSet set = appliedTickets.get(level);
            if (set != null) set.remove(pos.toLong());
            modifiedLevels.add(level);
        });
    }

    private void cleanupTask(ServerLevel level, ChunkPos pos) {
        queueTicketRemove(level, pos);
        // old emptyTicks reset is gone, that field isn't here and the pause check covers it
        DimensionState state = dimensionStates.get(level.dimension());
        if (state != null) completeTask(state, pos);
    }

    private void onSuccess(DimensionState state, ChunkPos pos) {
        long key = pos.toLong();
        state.failCounts.remove(key);
        if (state.completedChunks.add(key)) {
            chunksCompleted.incrementAndGet();
            markChunkCompleted(state, pos.x, pos.z);
            state.remainingInRadius.decrementAndGet();
        } else {
            chunksSkipped.incrementAndGet();
            markChunkCompleted(state, pos.x, pos.z);
        }
        decrementBatch(state, pos);
    }

    private void onFailure(DimensionState state, ChunkPos pos) {
        chunksFailed.incrementAndGet();
        long key = pos.toLong();
        int fails = state.failCounts.merge(key, 1, Integer::sum);
        // after a few fails mark it done so findWork stops looping on the same batch
        if (fails >= MAX_CHUNK_RETRIES) {
            state.failCounts.remove(key);
            markChunkCompleted(state, pos.x, pos.z);
            state.remainingInRadius.updateAndGet(v -> Math.max(0, v - 1));
        }
        decrementBatch(state, pos);
    }

    private void decrementBatch(DimensionState state, ChunkPos pos) {
        long batchKey = getBatchKey(pos.x, pos.z);
        AtomicInteger counter = state.batchCounters.get(batchKey);
        if (counter != null && counter.decrementAndGet() <= 0) {
            state.trackedBatches.remove(batchKey);
            state.batchCounters.remove(batchKey);
        }
    }

    private void completeTask(DimensionState state, ChunkPos pos) {
        if (state.trackedChunks.remove(pos.toLong())) {
            activeTaskCount.decrementAndGet();
            throttle.release();
        }
    }

    // returns a new list rotated so element at offset comes first (fair iteration)
    private static <T> List<T> rotated(List<T> list, int offset) {
        int n = list.size();
        if (n <= 1) return list;
        offset = ((offset % n) + n) % n;
        if (offset == 0) return list;
        List<T> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(list.get((i + offset) % n));
        }
        return out;
    }

    public long getCompleted() { return chunksCompleted.get(); }
    public long getSkipped() { return chunksSkipped.get(); }
    public int getActiveTaskCount() { return activeTaskCount.get(); }
    public int getRemainingInRadius() {
        if (currentDimensionKey == null) return 0;
        DimensionState state = dimensionStates.get(currentDimensionKey);
        return state != null ? state.remainingInRadius.get() : 0;
    }
    public boolean isThrottled() { return throttled.get(); }
    public void setPauseCheck(BooleanSupplier check) {
        this.pauseCheck = check;
    }

    private void tickTpsMonitor() {
        long now = System.nanoTime();
        if (lastTickNanos > 0) {
            recentTickTimes[tickTimeIndex] = now - lastTickNanos;
            tickTimeIndex = (tickTimeIndex + 1) % recentTickTimes.length;
        }
        lastTickNanos = now;

        long totalTickTime = 0;
        int count = 0;
        for (long tickNanos : recentTickTimes) {
            if (tickNanos > 0) {
                totalTickTime += tickNanos;
                count++;
            }
        }

        double mspt = count > 0 ? (totalTickTime / (double) count) / 1_000_000.0 : 0.0;
        if (mspt <= MSPT_SOFT) loadFactor = 1.0;
        else if (mspt >= MSPT_HARD) loadFactor = 0.0;
        else loadFactor = 1.0 - (mspt - MSPT_SOFT) / (MSPT_HARD - MSPT_SOFT);
        throttled.set(loadFactor <= 0.0);
    }

    private void resetTpsMonitor() {
        lastTickNanos = 0;
        tickTimeIndex = 0;
        Arrays.fill(recentTickTimes, 0);
        throttled.set(false);
        loadFactor = 1.0;
    }

    private synchronized void tickStats() {
        long now = System.currentTimeMillis();
        long secondsPassed = (now - lastStatsTickTime) / 1000;
        if (secondsPassed < 1) return;

        long currentTotal = chunksCompleted.get();
        long delta = currentTotal - lastCompletedCount;
        long perSecond = delta / secondsPassed;
        long remainder = delta % secondsPassed;
        int updateCount = (int) Math.min(secondsPassed, rollingHistory.length);
        for (int i = 0; i < updateCount; i++) {
            rollingHistory[historyIndex] = perSecond + (i < remainder ? 1 : 0);
            historyIndex = (historyIndex + 1) % rollingHistory.length;
        }
        lastCompletedCount = currentTotal;
        lastStatsTickTime += secondsPassed * 1000;
    }

    public synchronized double getChunksPerSecond() {
        long sum = 0;
        int filled = 0;
        for (long value : rollingHistory) {
            sum += value;
            if (value > 0) filled++;
        }
        return filled == 0 ? 0.0 : (double) sum / filled;
    }

    private void resetStats() {
        chunksQueued.set(0);
        chunksCompleted.set(0);
        chunksFailed.set(0);
        chunksSkipped.set(0);
        synchronized (this) {
            Arrays.fill(rollingHistory, 0);
            lastCompletedCount = 0;
            lastStatsTickTime = System.currentTimeMillis();
        }
    }

    private static void saveChunks(ServerLevel level, ResourceKey<Level> dimension, Set<Long> completedChunks) {
        if (level == null || dimension == null) return;
        try {
            Path path = level.getServer().getWorldPath(LevelResource.ROOT).resolve("voxy_gen_" + dimensionId(dimension) + ".bin");
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(path)))) {
                synchronized (completedChunks) {
                    out.writeInt(completedChunks.size());
                    for (Long chunkPos : completedChunks) out.writeLong(chunkPos);
                }
            }
        } catch (Exception e) {
            LodStreamingService.LOGGER.error("failed to save chunk generation cache", e);
        }
    }

    private static void loadChunks(ServerLevel level, ResourceKey<Level> dimension, Set<Long> completedChunks) {
        completedChunks.clear();
        if (level == null || dimension == null) return;
        try {
            Path path = level.getServer().getWorldPath(LevelResource.ROOT).resolve("voxy_gen_" + dimensionId(dimension) + ".bin");
            if (Files.exists(path)) {
                try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
                    int count = in.readInt();
                    for (int i = 0; i < count; i++) completedChunks.add(in.readLong());
                }
                LodStreamingService.LOGGER.info("loaded {} chunks from LOD generation cache for {}", completedChunks.size(), dimension);
            }
        } catch (Exception e) {
            LodStreamingService.LOGGER.error("failed to load chunk generation cache", e);
        }
    }

    private static String dimensionId(ResourceKey<Level> dimension) {
        return dimension.toString().replace("ResourceKey[", "").replace("]", "")
                .replace("/", "_").replace(":", "_").trim();
    }

    public void markDirty(LevelChunk chunk, int blockY) {
        if (!anyModded()) return;

        long key = chunk.getPos().toLong();
        Map<Long, IntSet> levelDirty = dirtySections.computeIfAbsent(chunk.getLevel().dimension(), k -> new ConcurrentHashMap<>());
        int cap = LodStreamingConfig.DATA.maxQueueSize;
        if (cap > 0 && !levelDirty.containsKey(key) && levelDirty.size() >= cap) return;

        IntSet sections = levelDirty.computeIfAbsent(key, k -> new IntOpenHashSet());
        synchronized (sections) {
            sections.add(SectionPos.blockToSectionCoord(blockY));
        }
    }

    private void processDirty(ServerLevel level) {
        if (level == null) return;

        Map<Long, IntSet> levelDirty = dirtySections.get(level.dimension());
        if (levelDirty == null || levelDirty.isEmpty()) return;

        long now = System.currentTimeMillis();
        long lastTime = lastDirtyProcessTimes.getOrDefault(level.dimension(), 0L);
        long interval = Math.max(MIN_DIRTY_PROCESS_INTERVAL_MS, LodStreamingConfig.DATA.update_interval * 50L);
        if (now - lastTime < interval) return;
        lastDirtyProcessTimes.put(level.dimension(), now);

        double maxDistSq = LodNetwork.syncRadiusSq();
        List<ServerPlayer> players = new ArrayList<>();
        for (ServerPlayer player : getPlayers()) {
            if (player.level() == level) players.add(player);
        }

        int processed = 0;
        Iterator<Map.Entry<Long, IntSet>> iterator = levelDirty.entrySet().iterator();
        while (iterator.hasNext() && processed < MAX_DIRTY_CHUNKS_PER_CYCLE) {
            Map.Entry<Long, IntSet> entry = iterator.next();
            iterator.remove();

            ChunkPos pos = new ChunkPos(entry.getKey());
            if (!anyPlayerNear(players, pos, maxDistSq)) continue;

            IntSet sectionYs;
            synchronized (entry.getValue()) {
                sectionYs = new IntOpenHashSet(entry.getValue());
            }

            LevelChunk chunk = level.getChunkSource().getChunk(pos.x, pos.z, false);
            if (chunk != null) {
                LodNetwork.broadcastLODData(chunk, sectionYs);
                processed++;
            }
        }
    }

    private static boolean anyPlayerNear(List<ServerPlayer> players, ChunkPos pos, double maxDistSq) {
        for (ServerPlayer player : players) {
            double dx = player.getX() - pos.getMiddleBlockX();
            double dz = player.getZ() - pos.getMiddleBlockZ();
            if (dx * dx + dz * dz <= maxDistSq) return true;
        }
        return false;
    }

    public void addPlayer(ServerPlayer player) {
        players.add(player);
        syncedChunks.put(player.getUUID(), LongSets.synchronize(new LongOpenHashSet()));
        needsBackfill.add(player.getUUID());
        lastDimension.put(player.getUUID(), player.level().dimension());
    }

    public void removePlayer(ServerPlayer player) {
        players.remove(player);
        syncedChunks.remove(player.getUUID());
        moddedPlayers.remove(player.getUUID());
        needsBackfill.remove(player.getUUID());
        lastDimension.remove(player.getUUID());
    }

    public void clearPlayers() {
        players.clear();
        syncedChunks.clear();
        moddedPlayers.clear();
        needsBackfill.clear();
        lastDimension.clear();
    }

    private boolean handleDimensionChange(ServerPlayer player) {
        UUID uuid = player.getUUID();
        ResourceKey<Level> current = player.level().dimension();
        ResourceKey<Level> previous = lastDimension.put(uuid, current);
        if (previous != null && !previous.equals(current)) {
            clearSynced(uuid);
            needsBackfill.add(uuid);
            return true;
        }
        return false;
    }

    public Collection<ServerPlayer> getPlayers() { return Collections.unmodifiableCollection(players); }
    public LongSet getSyncedChunks(UUID uuid) { return syncedChunks.get(uuid); }
    public void setModded(UUID uuid, boolean modded) {
        if (modded) moddedPlayers.add(uuid);
        else moddedPlayers.remove(uuid);
    }
    public boolean isModded(UUID uuid) { return moddedPlayers.contains(uuid); }
    public boolean anyModded() { return !moddedPlayers.isEmpty(); }
    public boolean isSynced(UUID uuid, long chunk) {
        LongSet synced = syncedChunks.get(uuid);
        return synced != null && synced.contains(chunk);
    }
    private void clearSynced(UUID uuid) {
        LongSet synced = syncedChunks.get(uuid);
        if (synced != null) synced.clear();
    }
    private boolean needsBackfill(UUID uuid) { return needsBackfill.contains(uuid); }
    private void clearBackfill(UUID uuid) { needsBackfill.remove(uuid); }

    private static final int BATCH_SIZE_SHIFT = 2;
    private static final int NODE_SIZE_BITS = 3;
    private static final int ROOT_SIZE_SHIFT = 9;

    private static class Node {
        final int level;
        final int x, z; // level-space coords
        volatile long fullMask;
        final Map<Integer, Object> children = new ConcurrentHashMap<>();

        Node(int level, int x, int z) {
            this.level = level;
            this.x = x;
            this.z = z;
        }

        boolean isFull() { return fullMask == -1L; }
    }

    private static void markChunkCompleted(DimensionState state, int cx, int cz) {
        int bx = cx >> BATCH_SIZE_SHIFT;
        int bz = cz >> BATCH_SIZE_SHIFT;
        int bit = (cx & 3) + ((cz & 3) << 2);

        int rx = bx >> ROOT_SIZE_SHIFT;
        int rz = bz >> ROOT_SIZE_SHIFT;
        long rootKey = ChunkPos.asLong(rx, rz);

        Node root = state.graphRoots.computeIfAbsent(rootKey, k -> new Node(3, rx, rz));
        recursiveMark(root, bx, bz, bit);
    }

    private static void recursiveMark(Node node, int bx, int bz, int bit) {
        int idx = getLocalIndex(node.level, bx, bz);
        if ((node.fullMask & (1L << idx)) != 0) return;

        if (node.level == 1) {
            Integer mask = (Integer) node.children.getOrDefault(idx, 0);
            mask |= (1 << bit);
            if (mask == 0xFFFF) {
                synchronized(node) {
                    node.fullMask |= (1L << idx);
                    node.children.remove(idx);
                }
            } else {
                node.children.put(idx, mask);
            }
        } else {
            Node child = (Node) node.children.computeIfAbsent(idx, k -> {
                int cx = (node.x << NODE_SIZE_BITS) + (k & 0x7);
                int cz = (node.z << NODE_SIZE_BITS) + (k >> 3);
                return new Node(node.level - 1, cx, cz);
            });
            recursiveMark(child, bx, bz, bit);
            if (child.isFull()) {
                synchronized(node) {
                    node.fullMask |= (1L << idx);
                    node.children.remove(idx);
                }
            }
        }
    }

    private static List<ChunkPos> findWork(DimensionState state, ChunkPos center, int radiusChunks, Set<Long> trackedBatches) {
        int cbx = center.x >> BATCH_SIZE_SHIFT;
        int cbz = center.z >> BATCH_SIZE_SHIFT;
        int rb = (radiusChunks + 3) >> BATCH_SIZE_SHIFT;

        // chunk-space center + radius so we can drop out-of-range chunks from a
        // boundary batch, otherwise that batch never fills and gets re-pulled forever
        int ccx = center.x;
        int ccz = center.z;
        long radiusSq = (long) radiusChunks * radiusChunks;

        PriorityQueue<Object[]> queue = new PriorityQueue<>(Comparator.comparingDouble(i -> (double) i[4]));

        int rootSize = 1 << ROOT_SIZE_SHIFT;
        int rbxMin = (cbx - rb) >> ROOT_SIZE_SHIFT;
        int rbxMax = (cbx + rb) >> ROOT_SIZE_SHIFT;
        int rbzMin = (cbz - rb) >> ROOT_SIZE_SHIFT;
        int rbzMax = (cbz + rb) >> ROOT_SIZE_SHIFT;

        for (int rx = rbxMin; rx <= rbxMax; rx++) {
            for (int rz = rbzMin; rz <= rbzMax; rz++) {
                Node root = state.graphRoots.get(ChunkPos.asLong(rx, rz));
                // check empty space even if node is null
                double dSq = getDistSq(rx, rz, rootSize, cbx, cbz);
                if (dSq <= (double)rb * rb) {
                    queue.add(new Object[]{root, 3, rx, rz, dSq});
                }
            }
        }

        while (!queue.isEmpty()) {
            Object[] item = queue.poll();
            Node node = (Node) item[0];
            int level = (int) item[1];
            int x = (int) item[2];
            int z = (int) item[3];
            if (node != null && node.isFull()) continue;

            if (level == 0) {
                long key = ChunkPos.asLong(x, z);
                if (trackedBatches.add(key)) {
                    List<ChunkPos> batch = new ArrayList<>(16);
                    for (int lz = 0; lz < 4; lz++) {
                        for (int lx = 0; lx < 4; lx++) {
                            int chunkX = (x << 2) + lx;
                            int chunkZ = (z << 2) + lz;
                            long dx = chunkX - ccx;
                            long dz = chunkZ - ccz;
                            // only include chunks actually inside the radius
                            if (dx * dx + dz * dz <= radiusSq) {
                                batch.add(new ChunkPos(chunkX, chunkZ));
                            }
                        }
                    }
                    // whole batch was out of range, untrack and keep looking
                    if (batch.isEmpty()) {
                        trackedBatches.remove(key);
                        continue;
                    }
                    return batch;
                }
                continue;
            }

            int childLevel = level - 1;
            int childSize = 1 << (3 * childLevel);

            for (int i = 0; i < 64; i++) {
                if (node != null && (node.fullMask & (1L << i)) != 0) continue;

                int cx = (x << 3) + (i & 7);
                int cz = (z << 3) + (i >> 3);

                double dSq = getDistSq(cx, cz, childSize, cbx, cbz);
                if (dSq <= (double)rb * rb) {
                    Object child = node == null ? null : node.children.get(i);
                    Node childNode = (child instanceof Node) ? (Node) child : null;
                    queue.add(new Object[]{childNode, childLevel, cx, cz, dSq});
                }
            }
        }
        return null;
    }

    private static double getDistSq(int nx, int nz, int size, int cbx, int cbz) {
        // distance to nearest edge of node
        double dx = Math.max(0, Math.max((double)nx * size - cbx, (double)cbx - (nx + 1) * size + 1));
        double dz = Math.max(0, Math.max((double)nz * size - cbz, (double)cbz - (nz + 1) * size + 1));
        return dx * dx + dz * dz;
    }

    private static int getLocalIndex(int level, int bx, int bz) {
        int shift = (level - 1) * 3;
        int lx = (bx >> shift) & 7;
        int lz = (bz >> shift) & 7;
        return lx + (lz << 3);
    }

    private static int countMissingInRange(DimensionState state, ChunkPos center, int radiusChunks) {
        int cbx = center.x >> BATCH_SIZE_SHIFT;
        int cbz = center.z >> BATCH_SIZE_SHIFT;
        int rb = (radiusChunks + 3) >> BATCH_SIZE_SHIFT;

        int rootSize = 1 << ROOT_SIZE_SHIFT;
        int rbxMin = (cbx - rb) >> ROOT_SIZE_SHIFT;
        int rbxMax = (cbx + rb) >> ROOT_SIZE_SHIFT;
        int rbzMin = (cbz - rb) >> ROOT_SIZE_SHIFT;
        int rbzMax = (cbz + rb) >> ROOT_SIZE_SHIFT;

        int count = 0;
        for (int rx = rbxMin; rx <= rbxMax; rx++) {
            for (int rz = rbzMin; rz <= rbzMax; rz++) {
                Node root = state.graphRoots.get(ChunkPos.asLong(rx, rz));
                count += recursiveCount(root, 3, rx, rz, cbx, cbz, rb);
            }
        }
        return count;
    }

    private static void collectCompletedInRange(DimensionState state, ChunkPos center, int radiusChunks,
                                                LongSet alreadySynced, List<ChunkPos> out, int maxResults) {
        int cbx = center.x >> BATCH_SIZE_SHIFT;
        int cbz = center.z >> BATCH_SIZE_SHIFT;
        int rb = (radiusChunks + 3) >> BATCH_SIZE_SHIFT;

        // use a priority queue to process chunks from nearest to farthest
        PriorityQueue<Object[]> queue = new PriorityQueue<>(Comparator.comparingDouble(i -> (double) i[6]));

        int rbxMin = (cbx - rb) >> ROOT_SIZE_SHIFT;
        int rbxMax = (cbx + rb) >> ROOT_SIZE_SHIFT;
        int rbzMin = (cbz - rb) >> ROOT_SIZE_SHIFT;
        int rbzMax = (cbz + rb) >> ROOT_SIZE_SHIFT;

        int rootSize = 1 << ROOT_SIZE_SHIFT;
        double maxDistSq = (double) rb * rb;

        for (int rx = rbxMin; rx <= rbxMax; rx++) {
            for (int rz = rbzMin; rz <= rbzMax; rz++) {
                Node root = state.graphRoots.get(ChunkPos.asLong(rx, rz));
                if (root == null) continue;

                double dSq = getDistSq(rx, rz, rootSize, cbx, cbz);
                if (dSq <= maxDistSq) {
                    queue.add(new Object[]{root, false, 0, 3, rx, rz, dSq});
                }
            }
        }

        while (!queue.isEmpty() && out.size() < maxResults) {
            Object[] item = queue.poll();
            Node node = (Node) item[0];
            boolean virtualFull = (boolean) item[1];
            int mask = (int) item[2];
            int level = (int) item[3];
            int x = (int) item[4];
            int z = (int) item[5];

            if (level == 0) {
                mask = virtualFull ? 0xFFFF : mask;
                for (int i = 0; i < 16; i++) {
                    if ((mask & (1 << i)) != 0) {
                        int lx = i & 3;
                        int lz = i >> 2;
                        ChunkPos pos = new ChunkPos((x << 2) + lx, (z << 2) + lz);
                        if (!alreadySynced.contains(pos.toLong())) {
                            out.add(pos);
                            if (out.size() >= maxResults) return;
                        }
                    }
                }
                continue;
            }

            int childLevel = level - 1;
            int childSize = 1 << (3 * childLevel);

            for (int i = 0; i < 64; i++) {
                int cx = (x << 3) + (i & 7);
                int cz = (z << 3) + (i >> 3);

                double dSq = getDistSq(cx, cz, childSize, cbx, cbz);
                if (dSq > maxDistSq) continue;

                if (virtualFull || (node != null && (node.fullMask & (1L << i)) != 0)) {
                   queue.add(new Object[]{null, true, 0xFFFF, childLevel, cx, cz, dSq});
                   continue;
                }

                if (node == null) continue;
                Object child = node.children.get(i);
                if (child == null) continue;

                if (childLevel == 0) {
                    if (child instanceof Integer childMask) {
                        queue.add(new Object[]{null, false, childMask, 0, cx, cz, dSq});
                    }
                } else if (child instanceof Node childNode) {
                    queue.add(new Object[]{childNode, false, 0, childLevel, cx, cz, dSq});
                }
            }
        }
    }

    private static int recursiveCount(Node node, int level, int nx, int nz, int cbx, int cbz, int rb) {
        int size = 1 << (3 * level);
        if (getDistSq(nx, nz, size, cbx, cbz) > (double)rb * rb) return 0;
        if (node != null && node.isFull()) return 0;

        if (level == 0) return 1; // batch

        if (node == null) {
            // estimate chunks in circle inside empty node
            if (level == 1) {
                int c = 0;
                for (int i = 0; i < 64; i++) {
                    int bx = (nx << 3) + (i & 7);
                    int bz = (nz << 3) + (i >> 3);
                    if (getDistSq(bx, bz, 1, cbx, cbz) <= (double)rb * rb) c += 16;
                }
                return c;
            }
            // higher level, recurse null node
            int c = 0;
            for (int i = 0; i < 64; i++) {
                int cx = (nx << 3) + (i & 7);
                int cz = (nz << 3) + (i >> 3);
                c += recursiveCount(null, level - 1, cx, cz, cbx, cbz, rb);
            }
            return c;
        }

        // l1 partial
        if (level == 1) {
            int c = 0;
            for (int i = 0; i < 64; i++) {
                if ((node.fullMask & (1L << i)) != 0) continue;
                int bx = (nx << 3) + (i & 7);
                int bz = (nz << 3) + (i >> 3);
                if (getDistSq(bx, bz, 1, cbx, cbz) <= (double)rb * rb) {
                    Integer mask = (Integer) node.children.getOrDefault(i, 0);
                    c += (16 - Integer.bitCount(mask));
                }
            }
            return c;
        }

        // higher level partial
        int c = 0;
        for (int i = 0; i < 64; i++) {
            if ((node.fullMask & (1L << i)) != 0) continue;
            int cx = (nx << 3) + (i & 7);
            int cz = (nz << 3) + (i >> 3);
            Object child = node.children.get(i);
            Node childNode = (child instanceof Node) ? (Node) child : null;
            c += recursiveCount(childNode, level - 1, cx, cz, cbx, cbz, rb);
        }
        return c;
    }

    private static long getBatchKey(int cx, int cz) {
        return ChunkPos.asLong(cx >> 2, cz >> 2);
    }
}
