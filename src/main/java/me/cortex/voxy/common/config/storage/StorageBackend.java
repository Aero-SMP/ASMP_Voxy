package me.cortex.voxy.common.config.storage;

import me.cortex.voxy.common.config.IMappingStorage;
import me.cortex.voxy.common.util.MemoryBuffer;

import java.util.function.LongConsumer;

public abstract class StorageBackend implements IMappingStorage {

    //Implementation may use the scratch buffer as the return value, it MUST NOT free the scratch buffer
    public abstract MemoryBuffer getSectionData(long key, MemoryBuffer scratch);

    public abstract void setSectionData(long key, MemoryBuffer data);

    public abstract void deleteSectionData(long key);

    public abstract void flush();

    public abstract void close();

    public abstract void iteratePositions(int level, LongConsumer callback);
}
