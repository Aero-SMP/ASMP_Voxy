package me.cortex.voxy.common.world;

import me.cortex.voxy.common.storage.SectionStorage;
import me.cortex.voxy.common.world.other.Mapper;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

final class RemoteTombstoneTest {
    @TempDir
    Path directory;

    @Test
    void remoteTombstoneRevisionSurvivesCacheReopen() throws IOException {
        long key = WorldEngine.getWorldSectionId(2, 7, -1, -4);
        try (SectionStorage storage = new SectionStorage(this.directory)) {
            WorldSection tombstone = new WorldSection(2, 7, -1, -4, null);
            Arrays.fill(tombstone._unsafeGetRawDataArray(), Mapper.airWithLight(15));
            tombstone.setRemoteRevision(42);
            storage.saveSection(tombstone);
            assertEquals(42, storage.getRemoteRevision(key));
        }

        try (SectionStorage storage = new SectionStorage(this.directory)) {
            assertEquals(42, storage.getRemoteRevision(key));
            WorldSection restored = new WorldSection(2, 7, -1, -4, null);
            assertEquals(0, storage.loadSection(restored));
            assertEquals(key, restored.key);
            assertEquals(42, restored.getRemoteRevision());
            assertEquals(0, restored.getNonEmptyChildren());
        }

        // The revision metadata is disposable: losing the index rebuilds it from the durable
        // section record without changing or loading the section through WorldEngine.
        try (var files = Files.list(this.directory.resolve("sections"))) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".idx")).toList()) {
                Files.delete(file);
            }
        }
        try (SectionStorage storage = new SectionStorage(this.directory)) {
            assertEquals(42, storage.getRemoteRevision(key));
            storage.deleteSection(key);
            assertEquals(-1, storage.getRemoteRevision(key));
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

    @Test
    void indexedRevisionDoesNotHideCorruptCachedPayload() throws IOException {
        Path storagePath = this.directory.resolve("corrupt-indexed");
        long key = WorldEngine.getWorldSectionId(2, 1, 0, 1);
        try (SectionStorage storage = new SectionStorage(storagePath)) {
            WorldSection section = new WorldSection(2, 1, 0, 1, null);
            Arrays.fill(section._unsafeGetRawDataArray(), Mapper.airWithLight(15));
            section.setRemoteRevision(91);
            storage.saveSection(section);
        }
        Path log;
        try (var files = Files.list(storagePath.resolve("sections"))) {
            log = files.filter(path -> path.getFileName().toString().endsWith(".vxl"))
                    .findFirst().orElseThrow();
        }
        byte[] bytes = Files.readAllBytes(log);
        bytes[bytes.length - 1] ^= 1;
        Files.write(log, bytes);

        SectionStorage storage = new SectionStorage(storagePath);
        assertEquals(91, storage.getRemoteRevision(key));
        WorldEngine engine = new WorldEngine(storage);
        assertFalse(engine.refreshResolvedRemoteSection(key, true));
        assertEquals(-1, storage.getRemoteRevision(key));
        engine.free();
    }

    @Test
    void noUpdateResolutionPreservesLastGoodHierarchy() {
        Path storagePath = this.directory.resolve("no-update");
        long key = WorldEngine.getWorldSectionId(2, 3, -1, 5);
        long parentKey = WorldEngine.getWorldSectionId(3, 1, -1, 2);
        long air = Mapper.airWithLight(15);
        try (SectionStorage storage = new SectionStorage(storagePath)) {
            WorldSection stored = new WorldSection(2, 3, -1, 5, null);
            Arrays.fill(stored._unsafeGetRawDataArray(), air);
            stored.nonEmptyChildren = 3;
            stored.setRemoteRevision(42);
            storage.saveSection(stored);
            WorldSection parent = new WorldSection(3, 1, -1, 2, null);
            Arrays.fill(parent._unsafeGetRawDataArray(), air);
            parent.nonEmptyChildren = (byte) 0x80;
            parent.setRemoteRevision(21);
            storage.saveSection(parent);
        }

        SectionStorage storage = new SectionStorage(storagePath);
        WorldEngine engine = new WorldEngine(storage);
        AtomicInteger updates = new AtomicInteger();
        engine.setDirtyCallback((section, flags, neighborMask) -> {
            assertEquals(key, section.key);
            assertEquals(WorldEngine.UPDATE_TYPE_CHILD_EXISTENCE_BIT
                    | WorldEngine.UPDATE_TYPE_DONT_SAVE, flags);
            assertEquals(0, neighborMask);
            updates.incrementAndGet();
        });
        engine.refreshResolvedRemoteSection(key);
        WorldSection resolved = engine.acquireIfExists(key);
        try {
            assertEquals(42, resolved.getRemoteRevision());
            assertEquals(3, resolved.getNonEmptyChildren());
            for (long value : resolved._unsafeGetRawDataArray()) {
                assertEquals(Mapper.airWithLight(15), value);
            }
        } finally {
            resolved.release();
        }
        WorldSection parent = engine.acquireIfExists(parentKey);
        try {
            assertEquals((byte) 0x80, parent.getNonEmptyChildren());
            assertEquals(21, parent.getRemoteRevision());
        } finally {
            parent.release();
        }
        assertEquals(1, updates.get());
        engine.free();

        try (SectionStorage reopened = new SectionStorage(storagePath)) {
            WorldSection restored = new WorldSection(2, 3, -1, 5, null);
            assertEquals(0, reopened.loadSection(restored));
            assertEquals(42, restored.getRemoteRevision());
            assertEquals(3, restored.getNonEmptyChildren());
            for (long value : restored._unsafeGetRawDataArray()) assertEquals(air, value);
            WorldSection restoredParent = new WorldSection(3, 1, -1, 2, null);
            assertEquals(0, reopened.loadSection(restoredParent));
            assertEquals((byte) 0x80, restoredParent.getNonEmptyChildren());
            assertEquals(21, restoredParent.getRemoteRevision());
        }
    }
}
