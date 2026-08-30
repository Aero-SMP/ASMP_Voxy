package me.cortex.voxy.commonImpl.importers;

import me.cortex.voxy.common.world.WorldEngine;

import java.util.function.IntConsumer;

public interface IDataImporter {
    interface IUpdateCallback{void onUpdate(int finished, int outOf);}

    void runImport(IUpdateCallback updateCallback, IntConsumer completionCallback);

    WorldEngine getEngine();

    void shutdown();
    boolean isRunning();
}
