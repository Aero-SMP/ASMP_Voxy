package me.cortex.voxy.common.config.storage.rocksdb;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.world.WorldEngine;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.rocksdb.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.LongConsumer;

public class RocksDBStorageBackend {
    private final RocksDB db;
    private final ColumnFamilyHandle worldSections;
    private final ColumnFamilyHandle idMappings;
    private final ReadOptions sectionReadOps;
    private final WriteOptions sectionWriteOps;

    //NOTE: closes in order
    private final List<AbstractImmutableNativeReference> closeList = new ArrayList<>();

    public RocksDBStorageBackend(Path path) {
        RocksDB.loadLibrary();

        try {
            Files.createDirectories(path);
        } catch (IOException e) {
            throw new RuntimeException("Unable to create Voxy storage directory " + path, e);
        }

        final ColumnFamilyOptions cfOpts = new ColumnFamilyOptions()
                .optimizeForSmallDb()
                .setCompressionType(CompressionType.LZ4_COMPRESSION)
                .setBottommostCompressionType(CompressionType.ZSTD_COMPRESSION);

        final ColumnFamilyOptions cfWorldSecOpts = new ColumnFamilyOptions()
                .setCompressionType(CompressionType.LZ4_COMPRESSION)
                .setBottommostCompressionType(CompressionType.ZSTD_COMPRESSION)
                .setCompactionPriority(CompactionPriority.MinOverlappingRatio)
                .setLevelCompactionDynamicLevelBytes(true)
                .optimizeForPointLookup(128);

        var bCache = new HyperClockCache(128*1024L*1024L,0, 4, false);
        var filter = new BloomFilter(10);
        cfWorldSecOpts.setTableFormatConfig(new BlockBasedTableConfig()
                .setCacheIndexAndFilterBlocksWithHighPriority(true)
                .setBlockCache(bCache)
                .setDataBlockHashTableUtilRatio(0.75)
                //.setIndexType(IndexType.kHashSearch)//Maybe?
                .setDataBlockIndexType(DataBlockIndexType.kDataBlockBinaryAndHash)
                .setFilterPolicy(filter)
        );

        final List<ColumnFamilyDescriptor> cfDescriptors = Arrays.asList(
            new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY, cfOpts),
            new ColumnFamilyDescriptor("world_sections".getBytes(), cfWorldSecOpts),
            new ColumnFamilyDescriptor("id_mappings".getBytes(), cfOpts)
        );

        final DBOptions options = new DBOptions()
                //.setUnorderedWrite(true)
                .setAvoidUnnecessaryBlockingIO(true)
                .setIncreaseParallelism(2)
                .setCreateIfMissing(true)
                .setCreateMissingColumnFamilies(true)
                .setMaxTotalWalSize(1024*1024*128);//128 mb max WAL size

        List<ColumnFamilyHandle> handles = new ArrayList<>();

        try {

            this.db = RocksDB.open(options,
                    path.toString(), cfDescriptors,
                    handles);

            this.sectionReadOps = new ReadOptions();
            this.sectionWriteOps = new WriteOptions();

            this.closeList.add(options);
            this.closeList.add(cfOpts);
            this.closeList.add(cfWorldSecOpts);
            this.closeList.add(this.sectionReadOps);
            this.closeList.add(this.sectionWriteOps);
            this.closeList.add(filter);
            this.closeList.add(bCache);
            this.closeList.addAll(handles);

            this.worldSections = handles.get(1);
            this.idMappings = handles.get(2);

            this.db.flushWal(true);
        } catch (RocksDBException e) {
            throw new RuntimeException(e);
        }
    }

    public void iteratePositions(int level, LongConsumer consumer) {
        try (var stack = MemoryStack.stackPush()) {
            try (var iter = this.db.newIterator(this.worldSections, this.sectionReadOps)) {
                ByteBuffer keyBuff = stack.calloc(8);
                long keyBuffPtr = MemoryUtil.memAddress(keyBuff);
                //TODO: this can be optimized if needed by useing a prefix-seek https://github.com/facebook/rocksdb/wiki/Prefix-Seek

                if (level != -1) {//-1 means iterate all
                    var seekBuff = stack.calloc(8);
                    MemoryUtil.memPutLong(MemoryUtil.memAddress(seekBuff), Long.reverseBytes(Integer.toUnsignedLong(level) << 60));
                    iter.seek(seekBuff);//we seak to the first level
                } else {
                    iter.seekToFirst();
                }
                while (iter.isValid()) {
                    keyBuff.clear();
                    iter.key(keyBuff);
                    long key = Long.reverseBytes(MemoryUtil.memGetLong(keyBuffPtr));
                    if (level != -1 && WorldEngine.getLevel(key) != level) {
                        break;
                    }
                    consumer.accept(key);
                    iter.next();
                }
            }
        }
    }

    public MemoryBuffer getSectionData(long key, MemoryBuffer scratch) {
        try (var stack = MemoryStack.stackPush()){
            var buffer = stack.malloc(8);
            MemoryUtil.memPutLong(MemoryUtil.memAddress(buffer), Long.reverseBytes(key));

            var result = this.db.get(this.worldSections,
                    this.sectionReadOps,
                    buffer,
                    MemoryUtil.memByteBuffer(scratch.address, (int) (scratch.size)));

            if (result == RocksDB.NOT_FOUND) {
                return null;
            }

            return scratch.subSize(result);
        } catch (RocksDBException e) {
            throw new RuntimeException(e);
        }
    }

    public void setSectionData(long key, MemoryBuffer data) {
        try (var stack = MemoryStack.stackPush()) {
            var keyBuff = stack.calloc(8);
            MemoryUtil.memPutLong(MemoryUtil.memAddress(keyBuff), Long.reverseBytes(key));
            this.db.put(this.worldSections, this.sectionWriteOps, keyBuff, data.asByteBuffer());
        } catch (RocksDBException e) {
            throw new RuntimeException(e);
        }
    }

    public void deleteSectionData(long key) {
        try {
            this.db.delete(this.worldSections, longToBytes(key));
        } catch (RocksDBException e) {
            throw new RuntimeException(e);
        }
    }

    public void putIdMapping(int id, ByteBuffer data) {
        try {
            var buffer = new byte[data.remaining()];
            data.get(buffer);
            data.rewind();
            this.db.put(this.idMappings, intToBytes(id), buffer);
        } catch (
                RocksDBException e) {
            throw new RuntimeException(e);
        }
    }

    public Int2ObjectOpenHashMap<byte[]> getIdMappingsData() {
        var out = new Int2ObjectOpenHashMap<byte[]>();
        try (var iterator = this.db.newIterator(this.idMappings)) {
            for (iterator.seekToFirst(); iterator.isValid(); iterator.next()) {
                out.put(bytesToInt(iterator.key()), iterator.value());
            }
        }
        return out;
    }

    public void flush() {
        try {
            this.db.flushWal(true);
        } catch (RocksDBException e) {
            throw new RuntimeException(e);
        }
    }

    public void close() {
        this.flush();
        this.closeList.forEach(AbstractImmutableNativeReference::close);
        try {
            this.db.closeE();
        } catch (RocksDBException e) {
            throw new RuntimeException(e);
        }
    }

    private static byte[] intToBytes(int i) {
        return new byte[] {(byte)(i>>24), (byte)(i>>16), (byte)(i>>8), (byte) i};
    }
    private static int bytesToInt(byte[] i) {
        return (Byte.toUnsignedInt(i[0])<<24)|(Byte.toUnsignedInt(i[1])<<16)|(Byte.toUnsignedInt(i[2])<<8)|(Byte.toUnsignedInt(i[3]));
    }

    private static byte[] longToBytes(long l) {
        byte[] result = new byte[Long.BYTES];
        for (int i = Long.BYTES - 1; i >= 0; i--) {
            result[i] = (byte)(l & 0xFF);
            l >>= Byte.SIZE;
        }
        return result;
    }
}
