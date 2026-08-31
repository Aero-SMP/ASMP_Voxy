package me.cortex.voxy.common.world;

import me.cortex.voxy.common.storage.SectionStorage;
import me.cortex.voxy.common.world.other.Mapper;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

final class RemoteTombstoneTest {
    @TempDir
    Path directory;

    @Test
    void remoteTombstoneRevisionSurvivesCacheReopen() {
        long key = WorldEngine.getWorldSectionId(2, 7, -1, -4);
        try (SectionStorage storage = new SectionStorage(this.directory)) {
            WorldSection tombstone = new WorldSection(2, 7, -1, -4, null);
            Arrays.fill(tombstone._unsafeGetRawDataArray(), Mapper.airWithLight(15));
            tombstone.setRemoteRevision(42);
            storage.saveSection(tombstone);
        }

        try (SectionStorage storage = new SectionStorage(this.directory)) {
            WorldSection restored = new WorldSection(2, 7, -1, -4, null);
            assertEquals(0, storage.loadSection(restored));
            assertEquals(key, restored.key);
            assertEquals(42, restored.getRemoteRevision());
            assertEquals(0, restored.getNonEmptyChildren());
        }
    }

    @Test
    void preparedNonAirCountSurvivesSectionStorage() {
        Path storagePath = this.directory.resolve("prepared-section");
        long key = WorldEngine.getWorldSectionId(0, 2, 3, -5);

        SectionStorage storage = new SectionStorage(storagePath);
        WorldEngine engine = new WorldEngine(storage);
        int block = engine.getMapper().getIdForBlockState(Blocks.STONE.defaultBlockState(), false);
        int biome = engine.getMapper().getIdForBiome("minecraft:plains", false);
        engine.getMapper().flushMappings();
        long[] data = new long[WorldSection.SECTION_VOLUME];
        Arrays.fill(data, Mapper.airWithLight(15));
        long solid = Mapper.composeMappingId((byte) 12, block, biome);
        Arrays.fill(data, 0, 731, solid);
        engine.setSaveCallback((ignoredEngine, saving, nonBlocking, alreadyAcquired) -> {
            if (!alreadyAcquired) throw new IllegalStateException("Expected tracker-owned save reference");
            storage.saveSection(saving);
            saving.setNotDirty();
            saving.release(false, 0);
            return true;
        });
        engine.replaceRemoteSection(key, 57, data, (byte) 0xff, 731);
        WorldSection section = engine.acquireIfExists(key);
        try {
            assertEquals(731, section.getNonEmptyBlockCount());
        } finally {
            section.release();
        }
        engine.free();

        try (SectionStorage reopened = new SectionStorage(storagePath)) {
            WorldSection restored = new WorldSection(0, 2, 3, -5, null);
            assertEquals(0, reopened.loadSection(restored));
            assertArrayEquals(data, restored._unsafeGetRawDataArray());
            assertEquals(731, restored.getNonEmptyBlockCount());
            assertEquals(57, restored.getRemoteRevision());
            assertEquals((byte) 0xff, restored.getNonEmptyChildren());
        }
    }
}
