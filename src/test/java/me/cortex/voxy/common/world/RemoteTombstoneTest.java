package me.cortex.voxy.common.world;

import me.cortex.voxy.common.storage.SectionStorage;
import me.cortex.voxy.common.world.other.Mapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
