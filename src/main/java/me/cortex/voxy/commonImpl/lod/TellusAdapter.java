package me.cortex.voxy.commonImpl.lod;

import me.cortex.voxy.commonImpl.lod.LodStreamingService;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

public final class TellusAdapter {
    private static boolean initialized;
    private static ExecutorService workerPool;
    private static final AtomicInteger threadCounter = new AtomicInteger();
    private static final Set<ChunkPos> buildingChunks = ConcurrentHashMap.newKeySet();

    private TellusAdapter() {}

    public static void shutdown() {
        if (workerPool != null) {
            workerPool.shutdownNow();
            try {
                workerPool.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {}
            workerPool = null;
        }
        buildingChunks.clear();
        initialized = false;
        LodStreamingService.LOGGER.info("tellus integration shutdown");
    }

    private static void initialize() {
        if (initialized) return;
        initialized = true;

        initializeSampler();
        initializeIngester();

        int threadCount = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
        workerPool = new ThreadPoolExecutor(threadCount, threadCount, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(10000),
                r -> {
                    Thread t = new Thread(r, "Tellus-LOD-Worker-" + threadCounter.getAndIncrement());
                    t.setDaemon(true);
                    t.setPriority(Thread.NORM_PRIORITY - 1);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy());

        LodStreamingService.LOGGER.info("tellus integration hub initialized ({} workers)", threadCount);
    }

    public static boolean isTellusWorld(ServerLevel level) {
        if (!initialized) initialize();
        if (!isTellusPresent()) return false;
        Object generator = level.getChunkSource().getGenerator();
        return generator != null && generator.getClass().getName().contains("EarthChunkGenerator");
    }

    public static void enqueueGenerate(ServerLevel level, ChunkPos pos, Runnable onComplete) {
        if (workerPool == null || !buildingChunks.add(pos)) {
            if (onComplete != null) onComplete.run();
            return;
        }
        workerPool.execute(() -> {
            try {
                prefetch(level, pos);
                Object[] data = sample(level, pos);
                if (data != null) {
                    buildAndIngest(level, pos, data);
                }
            } finally {
                buildingChunks.remove(pos);
                if (onComplete != null) onComplete.run();
            }
        });
    }

    private static BlockState[] getPalette(Holder<Biome> biome, Random random) {
        if (biome != null) {
            if (biome.is(BiomeTags.IS_OCEAN) || biome.is(BiomeTags.IS_RIVER)) {
                int roll = random.nextInt(100);
                if (roll < 10) return new BlockState[]{Blocks.GRAVEL.defaultBlockState(), Blocks.GRAVEL.defaultBlockState()};
                if (roll < 15) return new BlockState[]{Blocks.CLAY.defaultBlockState(), Blocks.CLAY.defaultBlockState()};
                return new BlockState[]{Blocks.SAND.defaultBlockState(), Blocks.SAND.defaultBlockState()};
            }
            if (biome.is(Biomes.DESERT)) return new BlockState[]{Blocks.SAND.defaultBlockState(), Blocks.SANDSTONE.defaultBlockState()};
            if (biome.is(BiomeTags.IS_BEACH)) return new BlockState[]{Blocks.SAND.defaultBlockState(), Blocks.SAND.defaultBlockState()};
            if (biome.is(BiomeTags.IS_BADLANDS)) return new BlockState[]{Blocks.RED_SAND.defaultBlockState(), Blocks.TERRACOTTA.defaultBlockState()};
            if (biome.is(Biomes.MANGROVE_SWAMP)) return new BlockState[]{Blocks.MUD.defaultBlockState(), Blocks.MUD.defaultBlockState()};
            if (biome.is(Biomes.SNOWY_PLAINS) || biome.is(Biomes.SNOWY_TAIGA)) return new BlockState[]{Blocks.SNOW_BLOCK.defaultBlockState(), Blocks.DIRT.defaultBlockState()};
        }
        return new BlockState[]{Blocks.GRASS_BLOCK.defaultBlockState(), Blocks.DIRT.defaultBlockState()};
    }

    public static long seedFromCoords(int x, int y, int z) {
        long seed = (long) (x * 3129871) ^ (long) z * 116129781L ^ (long) y;
        seed = seed * seed * 42317861L + seed * 11L;
        return seed >> 16;
    }

    private static void buildAndIngest(ServerLevel level, ChunkPos pos, Object[] data) {
        if (!isAvailable()) return;
        try {
            Object voxy = getVoxyInstance();
            if (voxy == null) return;
            Object engine = getWorldEngine(voxy, level);
            if (engine == null) return;
            Object mapper = getMapper(engine);
            if (mapper == null) return;

            BiomeSource biomeSource = level.getChunkSource().getGenerator().getBiomeSource();
            Climate.Sampler sampler = level.getChunkSource().randomState().sampler();

            int minX = pos.getMinBlockX();
            int minZ = pos.getMinBlockZ();

            Map<Integer, Holder<Biome>> biomeIdToHolder = new HashMap<>();
            Map<Holder<Biome>, Integer> biomeHolderToId = new IdentityHashMap<>();
            Random random = new Random(pos.toLong());

            int grassId_Voxy = getBlockId(mapper, Blocks.GRASS_BLOCK);
            int sandId_Voxy = getBlockId(mapper, Blocks.SAND);

            int seaLevel = (int) data[5];
            int[] heights = (int[]) data[0];
            byte[] cover = (byte[]) data[1];
            byte[] slopes = (byte[]) data[2];
            boolean[] hasWaters = (boolean[]) data[3];
            int[] waterHeads = (int[]) data[4];

            int[] biomeIds = new int[256];
            boolean[] vegAllowed = new boolean[256];
            long[] colTopIds = new long[256];
            long[] colFillerIds = new long[256];
            long[] colStoneIds = new long[256];
            long[] colDeepIds = new long[256];
            long[] colWaterIds = new long[256];

            int stoneId = getBlockId(mapper, Blocks.STONE.defaultBlockState());
            int deepId = getBlockId(mapper, Blocks.DEEPSLATE.defaultBlockState());
            int waterId = getBlockId(mapper, Blocks.WATER.defaultBlockState());
            if (waterId == 0) waterId = getBlockId(mapper, Blocks.ICE.defaultBlockState());
            if (waterId == 0) waterId = stoneId;

            // wider water grid so the shoreline check sees past the 16x16 area
            boolean[] expandedWater = new boolean[24 * 24];
            for (int dz = -4; dz < 20; dz++) {
                for (int dx = -4; dx < 20; dx++) {
                    int idx = (dz + 4) * 24 + (dx + 4);
                    if (dx >= 0 && dx < 16 && dz >= 0 && dz < 16) {
                        expandedWater[idx] = hasWaters[dz << 4 | dx];
                    } else {
                        // sample neighbor cover to get water status outside the local 16x16
                        int c = sampleCoverClass(level, minX + dx, minZ + dz);
                        expandedWater[idx] = (c == 80 || c == 0); // 80 is water, 0 is no-data/ocean
                    }
                }
            }

            // pass 1, resolve biomes and terrain/water heights
            for (int i = 0; i < 256; i++) {
                int wx = minX + (i & 15);
                int wz = minZ + (i >> 4);
                int h = heights[i];
                int c = cover[i] & 0xFF;

                boolean isOcean = (c == 0 && h <= seaLevel);
                boolean hasWaterValue = hasWaters[i] || (c == 80 || c == 95 || isOcean);

                if (hasWaterValue) {
                    int waterH = hasWaters[i] ? waterHeads[i] : seaLevel;
                    if (waterH <= h) waterH = h + 1;
                    waterHeads[i] = waterH;

                    if (!isOcean) {
                        // distance to shore so water can slope shallow near land
                        int distToShore = 5;
                        int ix = i & 15;
                        int iz = i >> 4;
                        for (int d = 1; d < 5; d++) {
                            boolean foundLand = false;
                            for (int dz = -d; dz <= d; dz++) {
                                for (int dx = -d; dx <= d; dx++) {
                                    if (Math.abs(dx) < d && Math.abs(dz) < d) continue;
                                    if (!expandedWater[(iz + dz + 4) * 24 + (ix + dx + 4)]) {
                                        foundLand = true; break;
                                    }
                                }
                                if (foundLand) break;
                            }
                            if (foundLand) { distToShore = d; break; }
                        }

                        // make water shallow near land
                        int targetH = waterH - distToShore;
                        if (h > targetH) {
                            h = targetH;
                            heights[i] = h;
                        }
                    } else if (h > seaLevel - 8) {
                         h = seaLevel - 8;
                         heights[i] = h;
                    }
                    hasWaters[i] = true;
                }

                Holder<Biome> biome = biomeSource.getNoiseBiome(QuartPos.fromBlock(wx), QuartPos.fromBlock(h), QuartPos.fromBlock(wz), sampler);
                int bId = biomeHolderToId.computeIfAbsent(biome, b -> {
                    int id = getBiomeId(mapper, b);
                    biomeIdToHolder.put(id, b);
                    return id;
                });
                biomeIds[i] = bId;
            }

            // pass 2, apply surface rules
            for (int i = 0; i < 256; i++) {
                int h = heights[i];
                int c = cover[i] & 0xFF;
                int slope = slopes[i] & 0xFF;
                int bId = biomeIds[i];
                Holder<Biome> biome = biomeIdToHolder.get(bId);

                BlockState[] palette = getPalette(biome, random);

                colStoneIds[i] = composeId(stoneId, bId, 15);
                colDeepIds[i] = composeId(deepId, bId, 15);
                colWaterIds[i] = composeId(waterId, bId, 15);

                boolean isStony = slope >= 3 && h >= 0;
                boolean isSnowIce = (c == 70); // ESA_SNOW_ICE

                if (isStony) {
                    colTopIds[i] = colStoneIds[i];
                    colFillerIds[i] = colStoneIds[i];
                    vegAllowed[i] = false;
                } else if (isSnowIce) {
                    colTopIds[i] = composeId(getBlockId(mapper, Blocks.SNOW_BLOCK), bId, 15);
                    colFillerIds[i] = colDeepIds[i];
                    vegAllowed[i] = false;
                } else {
                    int topBlockId = getBlockId(mapper, palette[0]);
                    colTopIds[i] = composeId(topBlockId, bId, 15);
                    colFillerIds[i] = composeId(getBlockId(mapper, palette[1]), bId, 15);
                    vegAllowed[i] = (topBlockId == grassId_Voxy) &&
                                    (biome == null || (!biome.is(BiomeTags.IS_BADLANDS) && !biome.is(Biomes.DESERT))) &&
                                    !hasWaters[i];
                }
            }

            Map<BlockPos, Long> propBlocks = new HashMap<>();
            placeProceduralTrees(level, pos, data, mapper, propBlocks, biomeIds, biomeSource, sampler, waterHeads);
            placeVegetation(pos, data, mapper, propBlocks, biomeIds, vegAllowed);
            placeUnderwaterVegetation(pos, data, mapper, propBlocks, biomeIds, hasWaters);

            long brightAir = composeId(0, 0, 15);
            int minSY = level.getMinSection();
            int sCount = level.getSectionsCount();

            int maxHV = -64;
            for (int h : heights) if (h > maxHV) maxHV = h;
            for (int i = 0; i < 256; i++) if (hasWaters[i] && waterHeads[i] > maxHV) maxHV = waterHeads[i];
            for (BlockPos p : propBlocks.keySet()) if (p.getY() > maxHV) maxHV = p.getY();

            for (int sy = 0; sy < sCount; sy++) {
                int sY = minSY + sy;
                int bY = sY << 4;
                if (bY > maxHV + 16) continue;

                Object vs = createSection(pos.x, sY, pos.z);
                long[] dataArray = getSectionData(vs);
                if (dataArray == null) continue;
                Arrays.fill(dataArray, brightAir);

                int nAir = 0;
                BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
                for (int i = 0; i < 4096; i++) {
                    int ly = (i >> 8) & 15;
                    int lz = (i >> 4) & 15;
                    int lx = i & 15;
                    int col = lz << 4 | lx;

                    int wX = minX + lx;
                    int wY = bY + ly;
                    int wZ = minZ + lz;

                    int h = heights[col];
                    boolean isTerrain = wY <= h;
                    boolean isWater = !isTerrain && hasWaters[col] && wY <= waterHeads[col];

                    if (isTerrain) {
                        dataArray[i] = (wY == h) ? colTopIds[col] : (wY > h - 4) ? colFillerIds[col] : (wY < 0) ? colDeepIds[col] : colStoneIds[col];
                        nAir++;
                    } else if (isWater) {
                        dataArray[i] = colWaterIds[col];
                        nAir++;
                    } else {
                        cursor.set(wX, wY, wZ);
                        Long propId = propBlocks.get(cursor);
                        if (propId != null) {
                            dataArray[i] = propId;
                            if (propId != brightAir) nAir++;
                        }
                    }
                }

                if (nAir > 0) {
                    setNonAirCount(vs, nAir);
                    mipAndInsert(engine, mapper, vs);
                }
            }
        } catch (Throwable e) {
            LodStreamingService.LOGGER.error("Tellus LOD build failed for {}", pos, e);
        }
    }

    private static boolean samplerInitialized;
    private static boolean tellusPresent;

    private static Class<?> earthChunkGeneratorClass, tellusElevationSourceClass, tellusLandCoverSourceClass;
    private static Class<?> tellusLandMaskSourceClass, landMaskSampleClass, earthGeneratorSettingsClass;
    private static Class<?> waterSurfaceResolverClass, waterChunkDataClass;

    private static MethodHandle sampleElevationMetersMethod, sampleCoverClassMethod, sampleLandMaskMethod;
    private static MethodHandle landMaskSampleKnownMethod, landMaskSampleLandMethod, getSettingsMethod;
    private static MethodHandle worldScaleHandle, terrestrialHeightScaleHandle, oceanicHeightScaleHandle;
    private static MethodHandle heightOffsetHandle, resolveSeaLevelHandle, resolveChunkWaterDataMethod;
    private static MethodHandle waterChunkHasWaterMethod, waterChunkWaterSurfaceMethod, waterChunkTerrainSurfaceMethod;

    private static Field elevationSourceField, landCoverSourceField, landMaskSourceField, waterResolverField;

    private static final int ESA_NO_DATA = 0;
    private static final int ESA_WATER = 80;
    private static MethodHandle treeFeaturesForBiomeMethod;

    private static void initializeSampler() {
        if (samplerInitialized) return;
        samplerInitialized = true;

        try {
            earthChunkGeneratorClass = Class.forName("com.yucareux.tellus.worldgen.EarthChunkGenerator");
            tellusElevationSourceClass = Class.forName("com.yucareux.tellus.world.data.elevation.TellusElevationSource");
            tellusLandCoverSourceClass = Class.forName("com.yucareux.tellus.world.data.cover.TellusLandCoverSource");
            tellusLandMaskSourceClass = Class.forName("com.yucareux.tellus.world.data.mask.TellusLandMaskSource");
            landMaskSampleClass = Class.forName("com.yucareux.tellus.world.data.mask.TellusLandMaskSource$LandMaskSample");
            earthGeneratorSettingsClass = Class.forName("com.yucareux.tellus.worldgen.EarthGeneratorSettings");
            waterSurfaceResolverClass = Class.forName("com.yucareux.tellus.worldgen.WaterSurfaceResolver");
            waterChunkDataClass = Class.forName("com.yucareux.tellus.worldgen.WaterSurfaceResolver$WaterChunkData");

            MethodHandles.Lookup lookup = MethodHandles.lookup();

            elevationSourceField = earthChunkGeneratorClass.getDeclaredField("ELEVATION_SOURCE");
            elevationSourceField.setAccessible(true);
            landCoverSourceField = earthChunkGeneratorClass.getDeclaredField("LAND_COVER_SOURCE");
            landCoverSourceField.setAccessible(true);
            landMaskSourceField = earthChunkGeneratorClass.getDeclaredField("LAND_MASK_SOURCE");
            landMaskSourceField.setAccessible(true);
            waterResolverField = earthChunkGeneratorClass.getDeclaredField("waterResolver");
            waterResolverField.setAccessible(true);

            sampleElevationMetersMethod = lookup.unreflect(tellusElevationSourceClass.getMethod("sampleElevationMeters", double.class, double.class, double.class, boolean.class));
            sampleCoverClassMethod = lookup.unreflect(tellusLandCoverSourceClass.getMethod("sampleCoverClass", double.class, double.class, double.class));
            sampleLandMaskMethod = lookup.unreflect(tellusLandMaskSourceClass.getMethod("sampleLandMask", double.class, double.class, double.class));

            landMaskSampleKnownMethod = lookup.unreflect(landMaskSampleClass.getMethod("known"));
            landMaskSampleLandMethod = lookup.unreflect(landMaskSampleClass.getMethod("land"));

            getSettingsMethod = lookup.unreflect(earthChunkGeneratorClass.getMethod("settings"));

            worldScaleHandle = lookup.unreflect(earthGeneratorSettingsClass.getMethod("worldScale"));
            terrestrialHeightScaleHandle = lookup.unreflect(earthGeneratorSettingsClass.getMethod("terrestrialHeightScale"));
            oceanicHeightScaleHandle = lookup.unreflect(earthGeneratorSettingsClass.getMethod("oceanicHeightScale"));
            heightOffsetHandle = lookup.unreflect(earthGeneratorSettingsClass.getMethod("heightOffset"));
            resolveSeaLevelHandle = lookup.unreflect(earthGeneratorSettingsClass.getMethod("resolveSeaLevel"));

            resolveChunkWaterDataMethod = lookup.unreflect(waterSurfaceResolverClass.getMethod("resolveChunkWaterData", int.class, int.class));
            waterChunkHasWaterMethod = lookup.unreflect(waterChunkDataClass.getMethod("hasWater", int.class, int.class));
            waterChunkWaterSurfaceMethod = lookup.unreflect(waterChunkDataClass.getMethod("waterSurface", int.class, int.class));
            waterChunkTerrainSurfaceMethod = lookup.unreflect(waterChunkDataClass.getMethod("terrainSurface", int.class, int.class));

            try {
                Method m = earthChunkGeneratorClass.getDeclaredMethod("treeFeaturesForBiome", Holder.class);
                m.setAccessible(true);
                treeFeaturesForBiomeMethod = lookup.unreflect(m);
            } catch (Exception e) {
                LodStreamingService.LOGGER.warn("could not find treeFeaturesForBiome");
            }

            tellusPresent = true;
        } catch (Exception e) {
            LodStreamingService.LOGGER.warn("tellus not found for sampling integration: " + e.getMessage());
            tellusPresent = false;
        }
    }

    public static boolean isTellusPresent() {
        if (!samplerInitialized) initializeSampler();
        return tellusPresent;
    }

    public static List<?> getTreeFeatures(Holder<Biome> biome) {
        if (treeFeaturesForBiomeMethod == null) return Collections.emptyList();
        try {
            return (List<?>) treeFeaturesForBiomeMethod.invoke(null, biome);
        } catch (Throwable e) {
            return Collections.emptyList();
        }
    }

    public static void prefetch(ServerLevel level, ChunkPos pos) {
        if (!isTellusPresent()) return;
        try {
            Object generator = level.getChunkSource().getGenerator();
            earthChunkGeneratorClass.getMethod("prefetchForChunk", int.class, int.class).invoke(generator, pos.x, pos.z);
        } catch (Throwable ignored) {}
    }

    private static Object[] sample(ServerLevel level, ChunkPos pos) {
        if (!isTellusPresent()) return null;

        try {
            Object generator = level.getChunkSource().getGenerator();
            Object settings = getSettingsMethod.invoke(generator);
            Object elevationSource = elevationSourceField.get(null);
            Object landCoverSource = landCoverSourceField.get(null);
            Object landMaskSource = landMaskSourceField.get(null);
            Object waterResolver = waterResolverField.get(generator);

            double worldScale = (double) worldScaleHandle.invoke(settings);
            double terrestrialHeightScale = (double) terrestrialHeightScaleHandle.invoke(settings);
            double oceanicHeightScale = (double) oceanicHeightScaleHandle.invoke(settings);
            int heightOffset = (int) heightOffsetHandle.invoke(settings);
            int seaLevel = (int) resolveSeaLevelHandle.invoke(settings);

            int[] heights = new int[256];
            byte[] coverClasses = new byte[256];
            byte[] slopes = new byte[256];
            boolean[] hasWaters = new boolean[256];
            int[] waterSurfaces = new int[256];

            int minBlockX = pos.getMinBlockX();
            int minBlockZ = pos.getMinBlockZ();

            Object waterData = resolveChunkWaterDataMethod.invoke(waterResolver, pos.x, pos.z);

            for (int i = 0; i < 256; i++) {
                int z = i >> 4;
                int x = i & 15;
                int worldZ = minBlockZ + z;
                int worldX = minBlockX + x;
                try {
                    boolean hWater = (boolean) waterChunkHasWaterMethod.invoke(waterData, x, z);
                    int terrainH = (int) waterChunkTerrainSurfaceMethod.invoke(waterData, x, z);
                    int waterH = (int) waterChunkWaterSurfaceMethod.invoke(waterData, x, z);

                    heights[i] = terrainH;
                    hasWaters[i] = hWater;
                    waterSurfaces[i] = waterH;
                    coverClasses[i] = (byte) (int) sampleCoverClassMethod.invoke(landCoverSource, (double) worldX, (double) worldZ, worldScale);

                    // sampling slopes with step 4
                    int step = 4;
                    int hE = samplePixelHeight(elevationSource, landCoverSource, landMaskSource, worldScale, terrestrialHeightScale, oceanicHeightScale, heightOffset, worldX + step, worldZ);
                    int hW = samplePixelHeight(elevationSource, landCoverSource, landMaskSource, worldScale, terrestrialHeightScale, oceanicHeightScale, heightOffset, worldX - step, worldZ);
                    int hN = samplePixelHeight(elevationSource, landCoverSource, landMaskSource, worldScale, terrestrialHeightScale, oceanicHeightScale, heightOffset, worldX, worldZ - step);
                    int hS = samplePixelHeight(elevationSource, landCoverSource, landMaskSource, worldScale, terrestrialHeightScale, oceanicHeightScale, heightOffset, worldX, worldZ + step);

                    int maxDiff = Math.max(
                            Math.max(Math.abs(hE - terrainH), Math.abs(hW - terrainH)),
                            Math.max(Math.abs(hN - terrainH), Math.abs(hS - terrainH))
                    );
                    slopes[i] = (byte) Math.min(255, maxDiff);
                } catch (Throwable e) {
                    heights[i] = heightOffset;
                    coverClasses[i] = 0;
                    slopes[i] = 0;
                    hasWaters[i] = false;
                    waterSurfaces[i] = seaLevel;
                }
            }
            return new Object[]{heights, coverClasses, slopes, hasWaters, waterSurfaces, seaLevel};
        } catch (Throwable e) {
            return null;
        }
    }

    private static int samplePixelHeight(Object elevationSource, Object landCoverSource, Object landMaskSource, double worldScale, double terrestrialHeightScale, double oceanicHeightScale, int heightOffset, int worldX, int worldZ) throws Throwable {
        boolean oceanZoom = true;
        Object landSample = sampleLandMaskMethod.invoke(landMaskSource, (double) worldX, (double) worldZ, worldScale);
        if ((boolean) landMaskSampleKnownMethod.invoke(landSample)) {
            if ((boolean) landMaskSampleLandMethod.invoke(landSample)) {
                oceanZoom = false;
            } else {
                int coverClass = (int) sampleCoverClassMethod.invoke(landCoverSource, (double) worldX, (double) worldZ, worldScale);
                oceanZoom = (coverClass == ESA_NO_DATA || coverClass == ESA_WATER);
            }
        }
        double elevation = (double) sampleElevationMetersMethod.invoke(elevationSource, (double) worldX, (double) worldZ, worldScale, oceanZoom);
        double heightScale = (elevation >= 0.0) ? terrestrialHeightScale : oceanicHeightScale;
        double scaled = (elevation * heightScale) / worldScale;
        return ((elevation >= 0.0) ? Mth.ceil(scaled) : Mth.floor(scaled)) + heightOffset;
    }

    public static int sampleHeightOnly(ServerLevel level, int worldX, int worldZ) {
        if (!isTellusPresent()) return 64;
        try {
            Object generator = level.getChunkSource().getGenerator();
            Object settings = getSettingsMethod.invoke(generator);
            Object elevationSource = elevationSourceField.get(null);
            Object landCoverSource = landCoverSourceField.get(null);
            Object landMaskSource = landMaskSourceField.get(null);
            double worldScale = (double) worldScaleHandle.invoke(settings);
            double terrestrialHeightScale = (double) terrestrialHeightScaleHandle.invoke(settings);
            double oceanicHeightScale = (double) oceanicHeightScaleHandle.invoke(settings);
            int heightOffset = (int) heightOffsetHandle.invoke(settings);
            return samplePixelHeight(elevationSource, landCoverSource, landMaskSource, worldScale, terrestrialHeightScale, oceanicHeightScale, heightOffset, worldX, worldZ);
        } catch (Throwable e) {
            return 64;
        }
    }

    public static int sampleCoverClass(ServerLevel level, int worldX, int worldZ) {
        if (!isTellusPresent()) return 0;
        try {
            Object generator = level.getChunkSource().getGenerator();
            Object settings = getSettingsMethod.invoke(generator);
            Object landCoverSource = landCoverSourceField.get(null);
            double worldScale = (double) worldScaleHandle.invoke(settings);
            return (int) sampleCoverClassMethod.invoke(landCoverSource, (double) worldX, (double) worldZ, worldScale);
        } catch (Throwable e) {
            return 0;
        }
    }

    public static void placeVegetation(ChunkPos pos, Object[] data, Object mapper, Map<BlockPos, Long> blocks, int[] biomeIds, boolean[] vegAllowed) {
        Random random = new Random(pos.toLong() ^ 0x67726173);
        int minX = pos.getMinBlockX();
        int minZ = pos.getMinBlockZ();
        int[] heights = (int[]) data[0];

        int grassId = getBlockId(mapper, Blocks.SHORT_GRASS);
        int tallGrassLowerId = getBlockId(mapper, Blocks.TALL_GRASS.defaultBlockState().setValue(DoublePlantBlock.HALF, DoubleBlockHalf.LOWER));
        int tallGrassUpperId = getBlockId(mapper, Blocks.TALL_GRASS.defaultBlockState().setValue(DoublePlantBlock.HALF, DoubleBlockHalf.UPPER));

        for (int i = 0; i < 256; i++) {
            if (!vegAllowed[i]) continue;

            int h = heights[i];
            int bId = biomeIds[i];

            if (random.nextFloat() < 0.20f) {
                int wx = minX + (i & 15);
                int wz = minZ + (i >> 4);
                if (random.nextFloat() < 0.15f) {
                    BlockPos p1 = new BlockPos(wx, h + 1, wz);
                    BlockPos p2 = new BlockPos(wx, h + 2, wz);
                    if (!blocks.containsKey(p1) && !blocks.containsKey(p2)) {
                        blocks.put(p1, composeId(tallGrassLowerId, bId, 15));
                        blocks.put(p2, composeId(tallGrassUpperId, bId, 15));
                    }
                } else {
                    BlockPos p1 = new BlockPos(wx, h + 1, wz);
                    if (!blocks.containsKey(p1)) {
                        blocks.put(p1, composeId(grassId, bId, 15));
                    }
                }
            }
        }
    }

    public static void placeUnderwaterVegetation(ChunkPos pos, Object[] data, Object mapper, Map<BlockPos, Long> blocks, int[] biomeIds, boolean[] hasWaters) {
        Random random = new Random(pos.toLong() ^ 0x73656167);
        int minX = pos.getMinBlockX();
        int minZ = pos.getMinBlockZ();
        int[] heights = (int[]) data[0];
        int[] waterHeads = (int[]) data[4];

        int seagrassId = getBlockId(mapper, Blocks.SEAGRASS);
        int tallSeagrassLowerId = getBlockId(mapper, Blocks.TALL_SEAGRASS.defaultBlockState().setValue(DoublePlantBlock.HALF, DoubleBlockHalf.LOWER));
        int tallSeagrassUpperId = getBlockId(mapper, Blocks.TALL_SEAGRASS.defaultBlockState().setValue(DoublePlantBlock.HALF, DoubleBlockHalf.UPPER));

        for (int i = 0; i < 256; i++) {
            if (!hasWaters[i]) continue;

            int h = heights[i];
            int wH = waterHeads[i];
            int bId = biomeIds[i];

            // seagrass needs space and doesn't always grow
            if (wH - h >= 2 && random.nextFloat() < 0.15f) {
                int wx = minX + (i & 15);
                int wz = minZ + (i >> 4);

                if (wH - h >= 3 && random.nextFloat() < 0.10f) {
                    blocks.put(new BlockPos(wx, h + 1, wz), composeId(tallSeagrassLowerId, bId, 15));
                    blocks.put(new BlockPos(wx, h + 2, wz), composeId(tallSeagrassUpperId, bId, 15));
                } else {
                    blocks.put(new BlockPos(wx, h + 1, wz), composeId(seagrassId, bId, 15));
                }
            }
        }
    }

    public static void placeProceduralTrees(ServerLevel level, ChunkPos pos, Object[] data, Object mapper, Map<BlockPos, Long> blocks, int[] biomeIds, BiomeSource biomeSource, Climate.Sampler sampler, int[] waterHeads) {
        int minX = pos.getMinBlockX();
        int minZ = pos.getMinBlockZ();
        long seed_base = level.getSeed();

        int cellMinX = Math.floorDiv(minX - 8, 5);
        int cellMaxX = Math.floorDiv(minX + 23, 5);
        int cellMinZ = Math.floorDiv(minZ - 8, 5);
        int cellMaxZ = Math.floorDiv(minZ + 23, 5);

        for (int cx = cellMinX; cx <= cellMaxX; cx++) {
            for (int cz = cellMinZ; cz <= cellMaxZ; cz++) {
                long cellSeed = seedFromCoords(cx, 0, cz) ^ seed_base;
                Random random = new Random(cellSeed);
                int wx = cx * 5 + random.nextInt(5);
                int wz = cz * 5 + random.nextInt(5);

                int coverClass = sampleCoverClass(level, wx, wz);
                if (coverClass == 10 || coverClass == 95) {
                     int surface = sampleHeightOnly(level, wx, wz);

                     int localWaterY = -64;
                     if (wx >= minX && wx < minX + 16 && wz >= minZ && wz < minZ + 16) {
                         localWaterY = waterHeads[(wz - minZ) << 4 | (wx - minX)];
                     } else {
                         localWaterY = (int) data[5];
                     }

                     if (surface >= localWaterY) {
                         Holder<Biome> biome = biomeSource.getNoiseBiome(QuartPos.fromBlock(wx), QuartPos.fromBlock(surface), QuartPos.fromBlock(wz), sampler);
                         int bId = getBiomeId(mapper, biome);
                         buildProceduralTree(wx, surface + 1, wz, biome, mapper, blocks, random, bId, minX, minZ);
                     }
                }
            }
        }
    }

    private static void buildProceduralTree(int x, int y, int z, Holder<Biome> biome, Object mapper, Map<BlockPos, Long> blocks, Random random, int biomeId, int minX, int minZ) {
        boolean isSpruceBiome = biome.is(BiomeTags.IS_TAIGA) ||
                                 biome.is(Biomes.GROVE) ||
                                 biome.is(Biomes.SNOWY_SLOPES) ||
                                 biome.is(Biomes.FROZEN_PEAKS) ||
                                 biome.is(Biomes.WINDSWEPT_HILLS) ||
                                 biome.is(Biomes.WINDSWEPT_GRAVELLY_HILLS);

        if (isSpruceBiome) {
            buildRefinedSpruce(x, y, z, mapper, blocks, random, biomeId, minX, minZ);
        } else if (biome.is(BiomeTags.IS_JUNGLE)) {
            buildRefinedJungle(x, y, z, mapper, blocks, random, biomeId, minX, minZ);
        } else if (biome.is(Biomes.BIRCH_FOREST) || biome.is(Biomes.OLD_GROWTH_BIRCH_FOREST)) {
            buildRefinedBirch(x, y, z, mapper, blocks, random, biomeId, minX, minZ);
        } else if (biome.is(Biomes.DARK_FOREST)) {
            buildRefinedDarkOak(x, y, z, mapper, blocks, random, biomeId, minX, minZ);
        } else {
            buildRefinedOak(x, y, z, mapper, blocks, random, biomeId, minX, minZ);
        }
    }

    private static long getStateId(Object mapper, BlockState state, int bId) {
        return composeId(getBlockId(mapper, state), bId, 15);
    }

    private static void buildRefinedOak(int x, int y, int z, Object mapper, Map<BlockPos, Long> blocks, Random random, int bId, int minX, int minZ) {
        int height = 5 + random.nextInt(2);
        long logId = getStateId(mapper, Blocks.OAK_LOG.defaultBlockState(), bId);
        long leafId = getStateId(mapper, Blocks.OAK_LEAVES.defaultBlockState(), bId);

        for (int i = 0; i < height; i++) setBlockIfInChunk(x, y+i, z, logId, blocks, minX, minZ);

        int tipY = y + height - 1;
        fillEllipsoid(x, tipY, z, 2.8, 2.5, 2.8, leafId, blocks, minX, minZ, random, 0.1);
        setBlockIfInChunk(x, tipY, z, logId, blocks, minX, minZ);
    }

    private static void buildRefinedBirch(int x, int y, int z, Object mapper, Map<BlockPos, Long> blocks, Random random, int bId, int minX, int minZ) {
        int height = 6 + random.nextInt(4);
        long logId = getStateId(mapper, Blocks.BIRCH_LOG.defaultBlockState(), bId);
        long leafId = getStateId(mapper, Blocks.BIRCH_LEAVES.defaultBlockState(), bId);

        for (int i = 0; i < height; i++) setBlockIfInChunk(x, y+i, z, logId, blocks, minX, minZ);

        fillEllipsoid(x, y + height - 2, z, 1.8, 3.5, 1.8, leafId, blocks, minX, minZ, random, 0.05);
    }

    private static void buildRefinedSpruce(int x, int y, int z, Object mapper, Map<BlockPos, Long> blocks, Random random, int bId, int minX, int minZ) {
        int height = 10 + random.nextInt(6);
        long logId = getStateId(mapper, Blocks.SPRUCE_LOG.defaultBlockState(), bId);
        long leafId = getStateId(mapper, Blocks.SPRUCE_LEAVES.defaultBlockState(), bId);

        for (int i = 0; i < height; i++) setBlockIfInChunk(x, y+i, z, logId, blocks, minX, minZ);

        int currentRadius = 1;
        int maxRadius = 3;
        for (int oy = height - 1; oy >= 2; oy--) {
            double r = currentRadius + (random.nextDouble() * 0.7);
            for (int ox = (int)-Math.ceil(r); ox <= Math.ceil(r); ox++) {
                for (int oz = (int)-Math.ceil(r); oz <= Math.ceil(r); oz++) {
                    double d2 = ox*ox + oz*oz;
                    if (d2 <= r*r) {
                        setBlockIfInChunk(x + ox, y + oy, z + oz, leafId, blocks, minX, minZ);
                    }
                }
            }
            if (oy % 2 == 0) {
                if (currentRadius < maxRadius) currentRadius++;
                else currentRadius--;
            }
        }
        setBlockIfInChunk(x, y + height, z, leafId, blocks, minX, minZ);
    }

    private static void buildRefinedDarkOak(int x, int y, int z, Object mapper, Map<BlockPos, Long> blocks, Random random, int bId, int minX, int minZ) {
        int height = 4 + random.nextInt(2);
        long logId = getStateId(mapper, Blocks.DARK_OAK_LOG.defaultBlockState(), bId);
        long leafId = getStateId(mapper, Blocks.DARK_OAK_LEAVES.defaultBlockState(), bId);

        for (int dx = 0; dx <= 1; dx++) {
            for (int dz = 0; dz <= 1; dz++) {
                for (int i = 0; i < height; i++) setBlockIfInChunk(x+dx, y+i, z+dz, logId, blocks, minX, minZ);
            }
        }
        fillEllipsoid(x, y + height, z, 4.2, 2.2, 4.2, leafId, blocks, minX, minZ, random, 0.1);
    }

    private static void buildRefinedJungle(int x, int y, int z, Object mapper, Map<BlockPos, Long> blocks, Random random, int bId, int minX, int minZ) {
        int height = 12 + random.nextInt(15);
        long logId = getStateId(mapper, Blocks.JUNGLE_LOG.defaultBlockState(), bId);
        long leafId = getStateId(mapper, Blocks.JUNGLE_LEAVES.defaultBlockState(), bId);

        for (int i = 0; i < height; i++) setBlockIfInChunk(x, y+i, z, logId, blocks, minX, minZ);

        for (int b = 0; b < 4; b++) {
            int bh = (int)(height * 0.6) + random.nextInt((int)(height * 0.4));
            int box = random.nextInt(5) - 2;
            int boz = random.nextInt(5) - 2;
            fillEllipsoid(x + box, y + bh, z + boz, 3.5, 2.5, 3.5, leafId, blocks, minX, minZ, random, 0.2);
        }
        fillEllipsoid(x, y + height, z, 4.5, 3.5, 4.5, leafId, blocks, minX, minZ, random, 0.1);
    }

    private static void fillEllipsoid(int x, int y, int z, double rx, double ry, double rz, long id, Map<BlockPos, Long> blocks, int minX, int minZ, Random random, double noise) {
        int startX = (int)-Math.ceil(rx);
        int endX = (int)Math.ceil(rx);
        int startY = (int)-Math.ceil(ry);
        int endY = (int)Math.ceil(ry);
        int startZ = (int)-Math.ceil(rz);
        int endZ = (int)Math.ceil(rz);

        for (int ox = startX; ox <= endX; ox++) {
            for (int oy = startY; oy <= endY; oy++) {
                for (int oz = startZ; oz <= endZ; oz++) {
                    double dx = ox / rx;
                    double dy = oy / ry;
                    double dz = oz / rz;
                    double dist = dx*dx + dy*dy + dz*dz;
                    if (dist <= 1.0 + (random.nextDouble() * noise)) {
                        setBlockIfInChunk(x + ox, y + oy, z + oz, id, blocks, minX, minZ);
                    }
                }
            }
        }
    }

    private static void setBlockIfInChunk(int x, int y, int z, long id, Map<BlockPos, Long> blocks, int minX, int minZ) {
        if (x >= minX && x < minX + 16 && z >= minZ && z < minZ + 16) {
            blocks.put(new BlockPos(x, y, z), id);
        }
    }

    private static boolean ingesterInitialized;
    private static boolean voxyPresent;

    private static Class<?> voxyCommonClass, worldIdentifierClass, worldEngineClass, mapperClass;
    private static Class<?> voxelizedSectionClass, worldUpdaterClass, worldConversionFactoryClass;

    private static MethodHandle getInstanceHandle, getOrCreateWorldHandle, worldIdOfHandle, getMapperHandle;
    private static MethodHandle getIdForBlockStateHandle, createEmptySectionHandle, setPositionHandle;
    private static MethodHandle withLightHandle, withBlockBiomeHandle, mipSectionHandle, insertUpdateHandle;
    private static MethodHandle getBiomeIdHandle;

    private static Field sectionDataField, sectionNonAirCountField;

    private static void initializeIngester() {
        if (ingesterInitialized) return;
        ingesterInitialized = true;

        try {
            voxyCommonClass = Class.forName("me.cortex.voxy.commonImpl.VoxyCommon");
            worldIdentifierClass = Class.forName("me.cortex.voxy.commonImpl.WorldIdentifier");
            worldEngineClass = Class.forName("me.cortex.voxy.common.world.WorldEngine");
            mapperClass = Class.forName("me.cortex.voxy.common.world.other.Mapper");
            voxelizedSectionClass = Class.forName("me.cortex.voxy.common.voxelization.VoxelizedSection");
            worldUpdaterClass = Class.forName("me.cortex.voxy.common.world.WorldUpdater");
            worldConversionFactoryClass = Class.forName("me.cortex.voxy.common.voxelization.WorldConversionFactory");

            MethodHandles.Lookup lookup = MethodHandles.lookup();

            getInstanceHandle = lookup.unreflect(voxyCommonClass.getMethod("getInstance"));
            getOrCreateWorldHandle = lookup.unreflect(Class.forName("me.cortex.voxy.commonImpl.VoxyInstance").getMethod("getOrCreate", worldIdentifierClass));
            worldIdOfHandle = lookup.unreflect(worldIdentifierClass.getMethod("of", Level.class));

            getMapperHandle = lookup.unreflect(worldEngineClass.getMethod("getMapper"));
            getIdForBlockStateHandle = lookup.unreflect(mapperClass.getMethod("getIdForBlockState", BlockState.class));
            getBiomeIdHandle = lookup.unreflect(mapperClass.getMethod("getIdForBiome", Holder.class));

            createEmptySectionHandle = lookup.unreflect(voxelizedSectionClass.getMethod("createEmpty"));
            setPositionHandle = lookup.unreflect(voxelizedSectionClass.getMethod("setPosition", int.class, int.class, int.class));

            withLightHandle = lookup.unreflect(mapperClass.getMethod("withLight", long.class, int.class));
            withBlockBiomeHandle = lookup.unreflect(mapperClass.getMethod("withBlockBiome", long.class, int.class, int.class));

            mipSectionHandle = lookup.unreflect(worldConversionFactoryClass.getMethod("mipSection", voxelizedSectionClass, mapperClass));
            insertUpdateHandle = lookup.unreflect(worldUpdaterClass.getMethod("insertUpdate", worldEngineClass, voxelizedSectionClass));

            sectionDataField = voxelizedSectionClass.getDeclaredField("section");
            sectionDataField.setAccessible(true);
            sectionNonAirCountField = voxelizedSectionClass.getDeclaredField("lvl0NonAirCount");
            sectionNonAirCountField.setAccessible(true);

            voxyPresent = true;
            LodStreamingService.LOGGER.info("LOD ingester initialized successfully (reflective)");
        } catch (Exception e) {
            LodStreamingService.LOGGER.warn("failed to initialize LOD ingester reflection: {}", e.getMessage());
            voxyPresent = false;
        }
    }

    public static boolean isAvailable() {
        if (!ingesterInitialized) initializeIngester();
        return voxyPresent;
    }

    public static Object getVoxyInstance() {
        if (!isAvailable()) return null;
        try {
            return getInstanceHandle.invoke();
        } catch (Throwable e) {
            return null;
        }
    }

    public static Object getWorldEngine(Object voxyInstance, ServerLevel level) {
        try {
            Object worldId = worldIdOfHandle.invoke(level);
            return getOrCreateWorldHandle.invoke(voxyInstance, worldId);
        } catch (Throwable e) {
            return null;
        }
    }

    public static Object getMapper(Object worldEngine) {
        try {
            return getMapperHandle.invoke(worldEngine);
        } catch (Throwable e) {
            return null;
        }
    }

    public static int getBlockId(Object mapper, BlockState state) {
        try {
            return (int) getIdForBlockStateHandle.invoke(mapper, state);
        } catch (Throwable e) {
            return 0;
        }
    }

    public static int getBlockId(Object mapper, Block block) {
        return getBlockId(mapper, block.defaultBlockState());
    }

    public static int getBiomeId(Object mapper, Holder<Biome> biome) {
        try {
            return (int) getBiomeIdHandle.invoke(mapper, biome);
        } catch (Throwable e) {
            return 0;
        }
    }

    public static long composeId(int blockId, int biomeId, int light) {
        // voxy light packing, block in bits 4-7 and sky in bits 0-3
        if (blockId == 0) { // air
            return ((long) (light & 0xFF)) << 56;
        }
        return (((long) (light & 0xFF)) << 56) |
               (((long) (biomeId & 0x1FF)) << 47) |
               (((long) (blockId & 0xFFFFF)) << 27);
    }

    public static Object createSection(int cx, int cy, int cz) {
        try {
            Object vs = createEmptySectionHandle.invoke();
            return setPositionHandle.invoke(vs, cx, cy, cz);
        } catch (Throwable e) {
            return null;
        }
    }

    public static long[] getSectionData(Object section) {
        try {
            return (long[]) sectionDataField.get(section);
        } catch (IllegalAccessException e) {
            return null;
        }
    }

    public static void setNonAirCount(Object section, int count) {
        try {
            sectionNonAirCountField.set(section, count);
        } catch (IllegalAccessException ignored) {}
    }

    public static void mipAndInsert(Object engine, Object mapper, Object section) {
        try {
            mipSectionHandle.invoke(section, mapper);
            insertUpdateHandle.invoke(engine, section);
        } catch (Throwable e) {
            LodStreamingService.LOGGER.error("failed to mip/insert LOD section", e);
        }
    }
}
