package me.cortex.voxy.common.config.storage.other;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import me.cortex.voxy.common.config.ConfigBuildCtx;
import me.cortex.voxy.common.config.compressors.CompressorConfig;
import me.cortex.voxy.common.config.compressors.StorageCompressor;
import me.cortex.voxy.common.config.storage.StorageBackend;
import me.cortex.voxy.common.config.storage.StorageConfig;
import me.cortex.voxy.common.util.MemoryBuffer;

import java.nio.ByteBuffer;
import java.util.function.LongConsumer;

//Compresses the section data
public class CompressionStorageAdaptor extends StorageBackend {
    private final StorageCompressor compressor;
    private final StorageBackend delegate;

    public CompressionStorageAdaptor(StorageCompressor compressor, StorageBackend delegate) {
        this.compressor = compressor;
        this.delegate = delegate;
    }

    //TODO: figure out a nicer way w.r.t scratch buffer shit
    @Override
    public MemoryBuffer getSectionData(long key, MemoryBuffer scratch) {
        var data = this.delegate.getSectionData(key, scratch);
        if (data == null) {
            return null;
        }
        return this.compressor.decompress(data);
    }

    @Override
    public void setSectionData(long key, MemoryBuffer data) {
        var cdata = this.compressor.compress(data);
        this.delegate.setSectionData(key, cdata);
        //Note that the data isnt freed (data cache in the compressors are used)
    }

    @Override
    public void deleteSectionData(long key) {
        this.delegate.deleteSectionData(key);
    }

    @Override
    public void putIdMapping(int id, ByteBuffer data) {
        this.delegate.putIdMapping(id, data);
    }

    @Override
    public Int2ObjectOpenHashMap<byte[]> getIdMappingsData() {
        return this.delegate.getIdMappingsData();
    }

    @Override
    public void flush() {
        this.delegate.flush();
    }

    @Override
    public void iteratePositions(int level, LongConsumer consumer) {
        this.delegate.iteratePositions(level, consumer);
    }

    @Override
    public void close() {
        this.compressor.close();
        this.delegate.close();
    }

    public static class Config extends StorageConfig {
        public CompressorConfig compressor;
        public StorageConfig delegate;

        @Override
        public StorageBackend build(ConfigBuildCtx ctx) {
            return new CompressionStorageAdaptor(this.compressor.build(ctx), this.delegate.build(ctx));
        }

        public static String getConfigTypeName() {
            return "CompressionAdaptor";
        }
    }
}
