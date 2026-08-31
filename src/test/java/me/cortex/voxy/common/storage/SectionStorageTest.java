package me.cortex.voxy.common.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SectionStorageTest {
    @TempDir
    Path directory;

    @Test
    void createsAndReopensFreshMappingManifest() throws IOException {
        try (SectionStorage storage = new SectionStorage(this.directory)) {
            assertTrue(storage.getIdMappingsData().isEmpty());
        }

        Path manifest = this.directory.resolve("mappings.manifest");
        assertEquals(28, Files.size(manifest));

        assertDoesNotThrow(() -> {
            try (SectionStorage storage = new SectionStorage(this.directory)) {
                assertTrue(storage.getIdMappingsData().isEmpty());
            }
        });
    }

    @Test
    void preservesMappingsAcrossReopen() {
        int id = (1 << 30) | 1;
        try (SectionStorage storage = new SectionStorage(this.directory)) {
            storage.putIdMapping(id, ByteBuffer.wrap(new byte[] {42}));
        }

        try (SectionStorage storage = new SectionStorage(this.directory)) {
            assertArrayEquals(new byte[] {42}, storage.getIdMappingsData().get(id));
        }
    }

    @Test
    void replacesDamagedEmptyMappingManifest() throws IOException {
        try (SectionStorage storage = new SectionStorage(this.directory)) {
            storage.getIdMappingsData();
        }

        Path manifest = this.directory.resolve("mappings.manifest");
        byte[] expected = Files.readAllBytes(manifest);
        byte[] damaged = expected.clone();
        ByteBuffer.wrap(damaged).putInt(12, 0);
        Files.write(manifest, damaged);

        try (SectionStorage storage = new SectionStorage(this.directory)) {
            assertTrue(storage.getIdMappingsData().isEmpty());
        }
        assertArrayEquals(expected, Files.readAllBytes(manifest));
    }
}
