package me.cortex.voxy.client.lod;

import me.cortex.voxy.common.storage.SectionStorage;
import me.cortex.voxy.common.world.WorldEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ClientLodResolutionTest {
    @TempDir
    Path directory;

    @AfterEach
    void clearNetworkState() {
        ClientLodNetwork.resetDemand();
    }

    @Test
    void resolutionIsVisibleBeforeRendererNotification() {
        long key = WorldEngine.getWorldSectionId(4, 3, 0, -2);
        SectionStorage storage = new SectionStorage(this.directory.resolve("resolved-order"));
        WorldEngine world = new WorldEngine(storage);
        AtomicBoolean notified = new AtomicBoolean();
        world.setDirtyCallback((section, flags, neighbors) -> {
            notified.set(true);
            assertTrue(ClientLodNetwork.isSectionResolved(key),
                    "Renderer notification observed a nonterminal unresolved node");
        });

        assertTrue(ClientLodNetwork.publishResolution(world, key, false));
        assertTrue(notified.get());
        assertTrue(ClientLodNetwork.isSectionResolved(key));
        world.free();
    }

    @Test
    void failedCachedResolutionIsNotPublished() {
        long key = WorldEngine.getWorldSectionId(3, -1, 1, 2);
        SectionStorage storage = new SectionStorage(this.directory.resolve("missing-cache"));
        WorldEngine world = new WorldEngine(storage);
        AtomicBoolean notified = new AtomicBoolean();
        world.setDirtyCallback((section, flags, neighbors) -> notified.set(true));

        assertFalse(ClientLodNetwork.publishResolution(world, key, true));
        assertFalse(notified.get());
        assertFalse(ClientLodNetwork.isSectionResolved(key));
        world.free();
    }

    @Test
    void failedRendererNotificationRollsBackResolution() {
        long key = WorldEngine.getWorldSectionId(4, 1, 0, 1);
        SectionStorage storage = new SectionStorage(this.directory.resolve("failed-notification"));
        WorldEngine world = new WorldEngine(storage);
        world.setDirtyCallback((section, flags, neighbors) -> {
            throw new IllegalStateException("simulated renderer failure");
        });

        assertThrows(IllegalStateException.class,
                () -> ClientLodNetwork.publishResolution(world, key, false));
        assertFalse(ClientLodNetwork.isSectionResolved(key));
        world.free();
    }
}
