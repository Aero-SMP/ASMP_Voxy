package me.cortex.voxy.client.core.model;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * Connection-lifetime translation from the authoritative catalog to renderer-local IDs.
 *
 * <p>The server catalog is the durable identity. These IDs exist only to fit the renderer's
 * packed cell format and are rebuilt for each client runtime; they are never persisted as a
 * second terrain or catalog database.</p>
 */
public final class CatalogMapper {
    private static final int MAX_BLOCK_STATES = 1 << 20;
    private static final int MAX_BIOMES = 1 << 9;

    private final ReentrantLock blockLock = new ReentrantLock();
    private final ConcurrentHashMap<BlockState, Integer> block2id =
            new ConcurrentHashMap<>(2000, 0.75f, 10);
    private final ObjectArrayList<BlockState> blockStates = new ObjectArrayList<>();

    private final ReentrantLock biomeLock = new ReentrantLock();
    private final ConcurrentHashMap<String, BiomeEntry> biome2biomeEntry =
            new ConcurrentHashMap<>(256, 0.75f, 4);
    private final ObjectArrayList<BiomeEntry> biomeId2biomeEntry = new ObjectArrayList<>();

    private volatile Consumer<BiomeEntry> newBiomeCallback;

    public CatalogMapper() {
        BlockState air = Blocks.AIR.defaultBlockState();
        this.block2id.put(air, 0);
        this.blockStates.add(air);
    }

    public static boolean isAir(long id) {
        return (id & (((1L << 20) - 1) << 27)) == 0;
    }

    public static int getBlockId(long id) {
        return (int) ((id >> 27) & ((1 << 20) - 1));
    }

    public static int getBiomeId(long id) {
        return (int) ((id >> 47) & ((1 << 9) - 1));
    }

    public static int getLightId(long id) {
        return (int) ((id >> 56) & 0xFF);
    }

    public static long composeMappingId(byte light, int blockId, int biomeId) {
        if (blockId < 0 || blockId >= MAX_BLOCK_STATES || biomeId < 0 || biomeId >= MAX_BIOMES) {
            throw new IllegalArgumentException("Mapping ID exceeds Voxy's packed format");
        }
        if (blockId == 0) return Byte.toUnsignedLong(light) << 56;
        return (Byte.toUnsignedLong(light) << 56)
                | (Integer.toUnsignedLong(biomeId) << 47)
                | (Integer.toUnsignedLong(blockId) << 27);
    }

    public void setBiomeCallback(Consumer<BiomeEntry> callback) {
        this.newBiomeCallback = callback;
    }

    public int getBlockStateCount() {
        return this.blockStates.size();
    }

    public BlockState getBlockStateFromBlockId(int blockId) {
        return this.blockStates.get(blockId);
    }

    public int getIdForBlockState(BlockState state) {
        if (state.isAir()) return 0;
        Integer id = this.block2id.get(state);
        if (id != null) return id;

        this.blockLock.lock();
        try {
            id = this.block2id.get(state);
            if (id == null) {
                id = this.blockStates.size();
                if (id >= MAX_BLOCK_STATES) {
                    throw new IllegalStateException("Voxy block catalog exceeds its 20-bit format");
                }
                this.blockStates.add(state);
                this.block2id.put(state, id);
            }
        } finally {
            this.blockLock.unlock();
        }
        return id;
    }

    public int getIdForBiome(String biomeId) {
        BiomeEntry entry = this.biome2biomeEntry.get(biomeId);
        if (entry != null) return entry.id;

        boolean created = false;
        this.biomeLock.lock();
        try {
            entry = this.biome2biomeEntry.get(biomeId);
            if (entry == null) {
                entry = new BiomeEntry(this.biomeId2biomeEntry.size(), biomeId);
                if (entry.id >= MAX_BIOMES) {
                    throw new IllegalStateException("Voxy biome catalog exceeds its 9-bit format");
                }
                this.biomeId2biomeEntry.add(entry);
                this.biome2biomeEntry.put(biomeId, entry);
                created = true;
            }
        } finally {
            this.biomeLock.unlock();
        }
        Consumer<BiomeEntry> callback = created ? this.newBiomeCallback : null;
        if (callback != null) callback.accept(entry);
        return entry.id;
    }

    public BiomeEntry[] getBiomeEntries() {
        this.biomeLock.lock();
        try {
            return this.biomeId2biomeEntry.toArray(new BiomeEntry[0]);
        } finally {
            this.biomeLock.unlock();
        }
    }

    public static final class BiomeEntry {
        public final int id;
        public final String biome;

        public BiomeEntry(int id, String biome) {
            this.id = id;
            this.biome = biome;
        }
    }
}
