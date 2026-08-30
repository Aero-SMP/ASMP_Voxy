package me.cortex.voxy.debug;

import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.world.WorldEngine;

import static me.cortex.voxy.common.world.WorldEngine.UPDATE_TYPE_CHILD_EXISTENCE_BIT;

final class TlnVerifier {
    private TlnVerifier() {}

    static void verify(WorldEngine engine, boolean repair) {
        engine.markActive();
        Thread worker = new Thread(() -> {
            engine.acquireRef();
            try {
                Logger.info("Verifying top-level node masks");
                LongArrayFIFOQueue positions = new LongArrayFIFOQueue();
                engine.storage.iteratePositions(WorldEngine.MAX_LOD_LAYER, positions::enqueue);
                while (!positions.isEmpty() && (engine.instanceIn == null || engine.instanceIn.isRunning())) {
                    long position = positions.dequeueLong();
                    verifyTree(engine, WorldEngine.getX(position), WorldEngine.getY(position),
                            WorldEngine.getZ(position), repair);
                }
                Logger.info("Top-level node verification complete");
            } finally {
                engine.releaseRef();
            }
        }, "Voxy diagnostics verifier");
        worker.setDaemon(true);
        worker.start();
    }

    private static void verifyTree(WorldEngine world, int rootX, int rootY, int rootZ, boolean repair) {
        boolean rootLogged = false;
        for (int level = 0; level < 5; level++) {
            for (int y = (rootY << 4) >> level; y < ((rootY + 1) << 4) >> level; y++) {
                for (int x = (rootX << 4) >> level; x < ((rootX + 1) << 4) >> level; x++) {
                    for (int z = (rootZ << 4) >> level; z < ((rootZ + 1) << 4) >> level; z++) {
                        if (world.instanceIn != null && !world.instanceIn.isRunning()) return;
                        if (level == 0) {
                            var section = world.acquireIfExists(0, x, y, z);
                            if (section == null) continue;
                            if ((section.getNonEmptyChildren() != 0) != (section.getNonEmptyBlockCount() != 0)) {
                                rootLogged = logRootOnce(rootLogged, rootX, rootY, rootZ);
                                Logger.error("Incorrect level-zero child mask " + WorldEngine.pprintPos(section.key));
                                if (repair) {
                                    section.updateLvl0State();
                                    world.markDirty(section, UPDATE_TYPE_CHILD_EXISTENCE_BIT, 0);
                                }
                            }
                            section.release();
                            continue;
                        }

                        byte expected = 0;
                        for (int child = 0; child < 8; child++) {
                            var section = world.acquireIfExists(level - 1,
                                    (child & 1) + (x << 1), ((child >> 2) & 1) + (y << 1),
                                    ((child >> 1) & 1) + (z << 1));
                            if (section != null) {
                                if (section.getNonEmptyChildren() != 0) expected |= (byte) (1 << child);
                                section.release();
                            }
                        }

                        var section = world.acquireIfExists(level, x, y, z);
                        if (section == null) {
                            if (expected != 0) {
                                rootLogged = logRootOnce(rootLogged, rootX, rootY, rootZ);
                                Logger.error("Missing parent section with non-empty children");
                            }
                            continue;
                        }
                        if (section.getNonEmptyChildren() != expected) {
                            rootLogged = logRootOnce(rootLogged, rootX, rootY, rootZ);
                            Logger.error("Incorrect child mask " + WorldEngine.pprintPos(section.key));
                            if (repair) {
                                for (int child = 0; child < 8; child++) {
                                    var childSection = world.acquireIfExists(level - 1,
                                            (child & 1) + (x << 1), ((child >> 2) & 1) + (y << 1),
                                            ((child >> 1) & 1) + (z << 1));
                                    if (childSection != null) {
                                        section.updateEmptyChildState(childSection);
                                        childSection.release();
                                    }
                                }
                                world.markDirty(section, UPDATE_TYPE_CHILD_EXISTENCE_BIT, 0);
                            }
                        }
                        section.release();
                    }
                }
            }
        }
    }

    private static boolean logRootOnce(boolean logged, int x, int y, int z) {
        if (!logged) Logger.error("Errors under top-level node " + x + "," + y + "," + z);
        return true;
    }
}
