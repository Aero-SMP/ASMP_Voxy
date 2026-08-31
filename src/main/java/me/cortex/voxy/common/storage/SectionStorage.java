package me.cortex.voxy.common.storage;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.util.ThreadLocalMemoryBuffer;
import me.cortex.voxy.common.world.SaveLoadSystem3;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.other.Mapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.zip.CRC32C;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * A small, recoverable cache store. Sections are grouped by their level-4 root and appended to
 * independent logs; indexes are disposable and rebuilt from the checksummed records when needed.
 */
public final class SectionStorage implements AutoCloseable {
    private static final int MAX_RECORD_SIZE = 1 << 20;
    private static final int RECORD_MAGIC = 0x56584C31; // VXL1
    private static final int INDEX_MAGIC = 0x56584932;  // VXI2
    private static final int MAPPING_MANIFEST_MAGIC = 0x56584D31; // VXM1
    private static final byte FORMAT_VERSION = 1;
    private static final byte PUT = 1;
    private static final byte DELETE = 2;
    private static final short COMPRESSED = 1;
    private static final int RECORD_HEADER_SIZE = 28;
    private static final int INDEX_HEADER_SIZE = 20;
    private static final int INDEX_ENTRY_SIZE = 40;
    private static final int MAPPING_HIGH_WATER_COUNT = 4;
    private static final int MAPPING_MANIFEST_DATA_SIZE = Integer.BYTES * (2 + MAPPING_HIGH_WATER_COUNT);
    private static final int MAPPING_MANIFEST_SIZE = MAPPING_MANIFEST_DATA_SIZE + Integer.BYTES;
    private static final long COMPACT_MIN_SIZE = 16L << 20;
    private static final long COMPACT_EAGER_SIZE = 256L << 20;
    private static final int NOT_FOUND = -1;
    private static final int CORRUPT = -2;
    private static final long MAPPING_HIGH_WATER = 1L << 63;
    private static final int MAX_BLOCK_MAPPING = (1 << 20) - 1;
    private static final int MAX_BIOME_MAPPING = (1 << 9) - 1;
    private static final int MAX_OPEN_SECTION_LOGS = 128;
    private static final long CHECKPOINT_BYTES = 64L << 20;
    private static final int CHECKPOINT_RECORDS = 4096;

    private static final ThreadLocalMemoryBuffer SECTION_BUFFER = new ThreadLocalMemoryBuffer(MAX_RECORD_SIZE);
    private static final ThreadLocal<CodecScratch> CODEC = ThreadLocal.withInitial(CodecScratch::new);

    private final Path sectionDirectory;
    private final Path mappingManifestPath;
    private final ConcurrentHashMap<Long, Log> sectionLogs = new ConcurrentHashMap<>();
    private Log mappings;
    private final int[] mappingHighWater = {-1, -1, -1, -1};
    private boolean mappingManifestValid;
    private boolean mappingsDirty;
    private final AtomicInteger accesses = new AtomicInteger();
    private volatile boolean closed;

    public SectionStorage(Path path) {
        try {
            Files.createDirectories(path);
            this.sectionDirectory = Files.createDirectories(path.resolve("sections"));
            this.mappingManifestPath = path.resolve("mappings.manifest");
            this.mappings = new Log(path.resolve("mappings.vxl"), false);
            int[] manifest = readMappingManifest(this.mappingManifestPath);
            if (manifest != null) {
                System.arraycopy(manifest, 0, this.mappingHighWater, 0, this.mappingHighWater.length);
                this.mappingManifestValid = true;
            }

            try (var files = Files.list(this.sectionDirectory)) {
                files.filter(p -> p.getFileName().toString().endsWith(".vxl")).forEach(p -> {
                    String name = p.getFileName().toString();
                    try {
                        long root = Long.parseUnsignedLong(name.substring(0, name.length() - 4), 16);
                        Log log = new Log(p, true);
                        this.sectionLogs.put(root, log);
                        log.checkpointAndPark();
                    } catch (NumberFormatException e) {
                        Logger.warn("Ignoring unrecognized Voxy shard", p);
                    } catch (IOException e) {
                        throw new UncheckedIOException("Unable to open Voxy shard " + p, e);
                    }
                });
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to open Voxy storage at " + path, e);
        }
    }

    public int loadSection(WorldSection section) {
        this.checkOpen();
        Log log = this.sectionLogs.get(shardKey(section.key));
        if (log == null) return 1;

        MemoryBuffer buffer = SECTION_BUFFER.get().createUnfreeableReference();
        ByteBuffer target = buffer.asByteBuffer();
        int size = log.readInto(section.key, target);
        this.pruneOpenLogs();
        if (size == NOT_FOUND) return 1;

        if (size == CORRUPT || !validSection(section.key, target, size)) {
            this.deleteSection(section.key);
            Arrays.fill(section._unsafeGetRawDataArray(), Mapper.AIR);
            Logger.error("Discarded corrupt Voxy section", WorldEngine.pprintPos(section.key));
            return -1;
        }

        if (SaveLoadSystem3.deserialize(section, buffer.subSize(size))) return 0;

        this.deleteSection(section.key);
        Arrays.fill(section._unsafeGetRawDataArray(), Mapper.AIR);
        Logger.error("Discarded invalid Voxy section", WorldEngine.pprintPos(section.key));
        return -1;
    }

    public void saveSection(WorldSection section) {
        this.checkOpen();
        ByteBuffer data = SaveLoadSystem3.serialize(section).asByteBuffer();
        // A section may only become durable after every mapping ID it references. Mapping
        // registration appends before publishing an ID. Snapshot first, then force the
        // catalog: every ID observed by serialization is therefore covered by this barrier.
        this.flushMappings();
        this.getSectionLog(section.key).put(section.key, data, true, false, section.getRemoteRevision());
        this.pruneOpenLogs();
    }

    /** Reads only the disposable cache index; it never loads or decompresses section cells. */
    public long getRemoteRevision(long key) {
        this.checkOpen();
        Log log = this.sectionLogs.get(shardKey(key));
        return log == null ? -1 : log.remoteRevision(key);
    }

    public void deleteSection(long key) {
        this.deleteSection(key, false);
    }

    public void deleteSection(long key, boolean durable) {
        this.checkOpen();
        Log log = this.sectionLogs.get(shardKey(key));
        if (log != null) {
            log.delete(key, durable);
            this.pruneOpenLogs();
        }
    }

    public synchronized void putIdMapping(int id, ByteBuffer data) {
        this.putIdMapping(id, data, true);
    }

    public synchronized void putIdMapping(int id, ByteBuffer data, boolean durable) {
        this.checkOpen();
        long key = Integer.toUnsignedLong(id);
        int type = id >>> 30;
        int value = id & 0x3FFF_FFFF;
        if (!validMappingValue(type, value)) throw new IllegalArgumentException("Invalid mapping ID");

        // Two copies let recovery retain the earlier value if the later append is damaged.
        this.mappings.put(key, data.duplicate(), false, false, -1);
        this.mappings.put(key, data.duplicate(), false, false, -1);
        ByteBuffer marker = ByteBuffer.allocate(4).putInt(value).flip();
        long markerKey = MAPPING_HIGH_WATER | Integer.toUnsignedLong(type);
        this.mappings.put(markerKey, marker.duplicate(), false, false, -1);
        this.mappings.put(markerKey, marker.duplicate(), false, false, -1);
        this.mappingHighWater[type] = Math.max(this.mappingHighWater[type], value);
        this.mappingsDirty = true;
        if (durable) this.flushMappings();
    }

    public synchronized void flushMappings() {
        this.checkOpen();
        if (!this.mappingsDirty && this.mappingManifestValid) return;
        this.mappings.flush();
        this.writeMappingManifest();
        this.mappingsDirty = false;
    }

    public Int2ObjectOpenHashMap<byte[]> getIdMappingsData() {
        this.checkOpen();
        for (int attempt = 0; attempt < 2; attempt++) {
            var output = new Int2ObjectOpenHashMap<byte[]>();
            int[] highWater = {-1, -1, -1, -1};
            boolean corrupt = false;
            for (long key : this.mappings.keys()) {
                byte[] value = this.mappings.readBytes(key);
                if (value == null) {
                    corrupt = true;
                    break;
                }
                if ((key & MAPPING_HIGH_WATER) != 0) {
                    int type = (int) (key & 0x3FFF_FFFF);
                    if (type >= highWater.length || value.length != 4) {
                        corrupt = true;
                        break;
                    }
                    int marker = ByteBuffer.wrap(value).getInt();
                    if (!validMappingValue(type, marker)) {
                        corrupt = true;
                        break;
                    }
                    highWater[type] = Math.max(highWater[type], marker);
                } else {
                    int id = (int) key;
                    int type = id >>> 30;
                    int mapping = id & 0x3FFF_FFFF;
                    if (!validMappingValue(type, mapping)) {
                        corrupt = true;
                        break;
                    }
                    output.put(id, value);
                    highWater[type] = Math.max(highWater[type], mapping);
                }
            }
            if (!this.mappingManifestValid) {
                if (!output.isEmpty() || this.hasStoredSections()) {
                    Logger.error("Voxy mapping guard is missing or damaged; resetting dependent cache sections");
                    this.resetAfterMappingCorruption();
                    return new Int2ObjectOpenHashMap<>();
                }
                this.writeMappingManifest();
            }
            for (int type = 1; type <= 2; type++) {
                highWater[type] = Math.max(highWater[type], this.mappingHighWater[type]);
            }
            if (!corrupt && completeMappings(output, highWater)) {
                boolean advanced = false;
                for (int type = 1; type <= 2; type++) {
                    if (highWater[type] > this.mappingHighWater[type]) {
                        this.mappingHighWater[type] = highWater[type];
                        advanced = true;
                    }
                }
                if (advanced) {
                    // The recovered suffix must reach stable storage before its high-water
                    // guard can make dependent sections safe to retain.
                    this.mappingsDirty = true;
                    this.flushMappings();
                }
                return output;
            }
            this.mappings.rebuildIndex();
        }
        Logger.error("The Voxy mapping catalog is unrecoverable; resetting its dependent cache");
        this.resetAfterMappingCorruption();
        return new Int2ObjectOpenHashMap<>();
    }

    public void flush() {
        this.checkOpen();
        this.flushMappings();
        for (Log log : this.sectionLogs.values()) log.flush();
    }

    @Override
    public void close() {
        if (this.closed) return;
        this.closed = true;
        RuntimeException failure = null;
        try {
            this.mappings.close();
        } catch (RuntimeException e) {
            failure = e;
        }
        for (Log log : this.sectionLogs.values()) {
            try {
                log.close();
            } catch (RuntimeException e) {
                if (failure == null) failure = e;
                else failure.addSuppressed(e);
            }
        }
        if (failure != null) throw failure;
    }

    private Log getSectionLog(long key) {
        long root = shardKey(key);
        return this.sectionLogs.computeIfAbsent(root, value -> {
            Path file = this.sectionDirectory.resolve(Long.toUnsignedString(value, 16) + ".vxl");
            try {
                return new Log(file, true);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    private void checkOpen() {
        if (this.closed) throw new IllegalStateException("Voxy storage is closed");
    }

    private void resetAfterMappingCorruption() {
        try {
            this.mappings.discardClose();
            for (Log log : this.sectionLogs.values()) log.discardClose();
            this.sectionLogs.clear();
            try (var files = Files.list(this.sectionDirectory)) {
                for (Path file : files.toList()) {
                    String name = file.getFileName().toString();
                    if (Files.isRegularFile(file) && (name.endsWith(".vxl") || name.endsWith(".idx")
                            || name.endsWith(".tmp") || name.endsWith(".compact"))) Files.deleteIfExists(file);
                }
            }
            Path mappingPath = this.mappings.path;
            Files.deleteIfExists(mappingPath);
            Files.deleteIfExists(this.mappings.indexPath);
            this.mappings = new Log(mappingPath, false);
            Arrays.fill(this.mappingHighWater, -1);
            this.mappingManifestValid = true;
            this.mappingsDirty = true;
            this.writeMappingManifest();
            this.mappingsDirty = false;
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to reset corrupt Voxy mapping catalog", e);
        }
    }

    private boolean hasStoredSections() {
        for (Log log : this.sectionLogs.values()) if (log.keys().length != 0) return true;
        return false;
    }

    private synchronized void writeMappingManifest() {
        try {
            ByteBuffer data = ByteBuffer.allocate(MAPPING_MANIFEST_SIZE);
            data.putInt(MAPPING_MANIFEST_MAGIC).putInt(FORMAT_VERSION);
            for (int value : this.mappingHighWater) data.putInt(value);
            CRC32C crc = new CRC32C();
            crc.update(data.array(), 0, MAPPING_MANIFEST_DATA_SIZE);
            data.putInt((int) crc.getValue()).flip();
            Path temporary = this.mappingManifestPath.resolveSibling(
                    this.mappingManifestPath.getFileName() + ".tmp");
            try (FileChannel output = FileChannel.open(temporary, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                writeFully(output, data, 0);
                output.force(false);
            }
            atomicReplace(temporary, this.mappingManifestPath);
            forceDirectory(this.mappingManifestPath);
            this.mappingManifestValid = true;
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to persist Voxy mapping guard", e);
        }
    }

    private static int[] readMappingManifest(Path path) {
        try {
            byte[] bytes = Files.readAllBytes(path);
            if (bytes.length != MAPPING_MANIFEST_SIZE) return null;
            ByteBuffer data = ByteBuffer.wrap(bytes);
            if (data.getInt() != MAPPING_MANIFEST_MAGIC || data.getInt() != FORMAT_VERSION) return null;
            int[] result = {data.getInt(), data.getInt(), data.getInt(), data.getInt()};
            CRC32C crc = new CRC32C();
            crc.update(bytes, 0, MAPPING_MANIFEST_DATA_SIZE);
            if ((int) crc.getValue() != data.getInt() || result[0] != -1 || result[3] != -1
                    || result[1] < -1 || result[1] > MAX_BLOCK_MAPPING
                    || result[2] < -1 || result[2] > MAX_BIOME_MAPPING) return null;
            return result;
        } catch (IOException e) {
            return null;
        }
    }

    private void pruneOpenLogs() {
        if ((this.accesses.incrementAndGet() & 127) != 0) return;
        ArrayList<Log> open = new ArrayList<>();
        for (Log log : this.sectionLogs.values()) if (log.isChannelOpen()) open.add(log);
        if (open.size() <= MAX_OPEN_SECTION_LOGS) return;
        open.sort(Comparator.comparingLong(Log::lastAccess));
        for (int i = 0; i < open.size() - MAX_OPEN_SECTION_LOGS; i++) open.get(i).park();
    }

    private static boolean completeMappings(Int2ObjectOpenHashMap<byte[]> mappings, int[] highWater) {
        for (int type = 1; type <= 2; type++) {
            int first = type == 1 ? 1 : 0; // Block zero is the implicit air entry; biomes start at zero.
            for (int id = first; id <= highWater[type]; id++) {
                if (!mappings.containsKey(id | (type << 30))) return false;
            }
        }
        return true;
    }

    private static boolean validMappingValue(int type, int value) {
        return switch (type) {
            case 1 -> value >= 1 && value <= MAX_BLOCK_MAPPING;
            case 2 -> value >= 0 && value <= MAX_BIOME_MAPPING;
            default -> false;
        };
    }

    private static long shardKey(long key) {
        int level = WorldEngine.getLevel(key);
        if (level > WorldEngine.MAX_LOD_LAYER) throw new IllegalArgumentException("Invalid Voxy level " + level);
        int shift = WorldEngine.MAX_LOD_LAYER - level;
        return WorldEngine.getWorldSectionId(WorldEngine.MAX_LOD_LAYER,
                WorldEngine.getX(key) >> shift,
                WorldEngine.getY(key) >> shift,
                WorldEngine.getZ(key) >> shift);
    }

    private synchronized boolean validSection(long key, ByteBuffer input, int size) {
        if (size < 24 + WorldSection.SECTION_VOLUME * 2) return false;
        ByteBuffer data = input.duplicate().order(ByteOrder.nativeOrder());
        data.flip();
        if (data.remaining() != size || data.getLong(0) != key) return false;
        long metadata = data.getLong(8);
        if ((metadata >>> 56) != 1) return false;
        int lutSize = (int) (metadata & 0xFFFF);
        long expected = 24L + WorldSection.SECTION_VOLUME * 2L + lutSize * 8L;
        if (lutSize == 0 || expected != size) return false;
        int indices = 24;
        for (int i = 0; i < WorldSection.SECTION_VOLUME; i++, indices += 2) {
            if (Short.toUnsignedInt(data.getShort(indices)) >= lutSize) return false;
        }
        int lut = 24 + WorldSection.SECTION_VOLUME * 2;
        for (int i = 0; i < lutSize; i++) {
            long mapping = data.getLong(lut + i * Long.BYTES);
            int block = Mapper.getBlockId(mapping);
            int biome = Mapper.getBiomeId(mapping);
            if ((block != 0 && block > this.mappingHighWater[1])
                    || (block != 0 && biome > this.mappingHighWater[2])) return false;
        }
        return true;
    }

    private static final class Log {
        private final Path path;
        private final Path indexPath;
        private final boolean compactable;
        private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
        private final HashMap<Long, Entry> entries = new HashMap<>();
        private volatile FileChannel channel;
        private volatile long lastAccess;
        private long logSize;
        private long liveBytes;
        private boolean dirty;
        private boolean closed;
        private long uncheckpointedBytes;
        private int uncheckpointedRecords;

        private Log(Path path, boolean compactable) throws IOException {
            this.path = path;
            this.indexPath = path.resolveSibling(path.getFileName() + ".idx");
            this.compactable = compactable;
            boolean created = Files.notExists(path);
            this.channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
            if (created) forceDirectory(path);
            boolean hadIndex = Files.isRegularFile(this.indexPath);
            long indexedThrough = this.loadIndex();
            this.scan(indexedThrough);
            this.uncheckpointedBytes = this.logSize - indexedThrough;
            if (!hadIndex && this.logSize != 0) this.dirty = true;
        }

        private boolean isChannelOpen() { return this.channel != null; }
        private long lastAccess() { return this.lastAccess; }

        private long[] keys() {
            this.lock.readLock().lock();
            try {
                return this.entries.keySet().stream().mapToLong(Long::longValue).toArray();
            } finally {
                this.lock.readLock().unlock();
            }
        }

        private long remoteRevision(long key) {
            this.lock.readLock().lock();
            try {
                Entry entry = this.entries.get(key);
                return entry == null ? -1 : entry.remoteRevision;
            } finally {
                this.lock.readLock().unlock();
            }
        }

        private int readInto(long key, ByteBuffer target) {
            this.lock.readLock().lock();
            try {
                Entry entry = this.entries.get(key);
                if (entry == null) return NOT_FOUND;
                return this.readEntry(entry, target);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } finally {
                this.lock.readLock().unlock();
            }
        }

        private byte[] readBytes(long key) {
            this.lock.readLock().lock();
            try {
                Entry entry = this.entries.get(key);
                if (entry == null) return null;
                byte[] result = new byte[entry.rawLength];
                return this.readEntry(entry, ByteBuffer.wrap(result)) == entry.rawLength ? result : null;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } finally {
                this.lock.readLock().unlock();
            }
        }

        private int readEntry(Entry entry, ByteBuffer target) throws IOException {
            if (entry.rawLength > target.remaining()) return CORRUPT;
            CodecScratch scratch = CODEC.get();
            ByteBuffer stored = scratch.buffer.clear();
            stored.limit(entry.storedLength);
            if (!readFully(this.openChannel(), stored, entry.payloadOffset)) return CORRUPT;
            stored.flip();
            if (checksum(PUT, entry.flags, entry.key, entry.storedLength, entry.rawLength, stored) != entry.crc) {
                return CORRUPT;
            }

            if ((entry.flags & COMPRESSED) == 0) {
                if (entry.storedLength != entry.rawLength) return CORRUPT;
                target.put(stored);
                return entry.rawLength;
            }

            Inflater inflater = scratch.inflater;
            inflater.reset();
            inflater.setInput(stored);
            int start = target.position();
            int oldLimit = target.limit();
            target.limit(start + entry.rawLength);
            try {
                while (!inflater.finished() && target.hasRemaining()) {
                    if (inflater.inflate(target) == 0 && inflater.needsInput()) break;
                }
                if (!inflater.finished() || target.position() - start != entry.rawLength) return CORRUPT;
                return entry.rawLength;
            } catch (DataFormatException e) {
                return CORRUPT;
            } finally {
                target.limit(oldLimit);
            }
        }

        private void put(long key, ByteBuffer source, boolean compress, boolean durable,
                         long remoteRevision) {
            this.lock.writeLock().lock();
            try {
                this.ensureOpen();
                ByteBuffer raw = source.duplicate();
                int rawLength = raw.remaining();
                if (rawLength <= 0 || rawLength > MAX_RECORD_SIZE) {
                    throw new IllegalArgumentException("Invalid Voxy record size " + rawLength);
                }

                short flags = 0;
                ByteBuffer stored = raw;
                if (compress && rawLength >= 256) {
                    ByteBuffer compressed = compress(raw);
                    if (compressed != null) {
                        flags = COMPRESSED;
                        stored = compressed;
                    }
                }

                Entry next = this.append(PUT, flags, key, stored, rawLength, remoteRevision);
                Entry previous = this.entries.put(key, next);
                this.liveBytes += RECORD_HEADER_SIZE + next.storedLength;
                if (previous != null) this.liveBytes -= RECORD_HEADER_SIZE + previous.storedLength;
                this.checkpointIfNeeded();
                if (durable) this.openChannel().force(false);
                if (this.compactable && this.logSize >= COMPACT_EAGER_SIZE && this.shouldCompact()) this.compact();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } finally {
                this.lock.writeLock().unlock();
            }
        }

        private void delete(long key, boolean durable) {
            this.lock.writeLock().lock();
            try {
                this.ensureOpen();
                Entry previous = this.entries.remove(key);
                if (previous == null) return;
                this.append(DELETE, (short) 0, key, ByteBuffer.allocate(0), 0, -1);
                this.liveBytes -= RECORD_HEADER_SIZE + previous.storedLength;
                this.checkpointIfNeeded();
                if (durable) this.openChannel().force(false);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } finally {
                this.lock.writeLock().unlock();
            }
        }

        private Entry append(byte type, short flags, long key, ByteBuffer payload, int rawLength,
                             long remoteRevision) throws IOException {
            int storedLength = payload.remaining();
            int crc = checksum(type, flags, key, storedLength, rawLength, payload);
            ByteBuffer header = ByteBuffer.allocate(RECORD_HEADER_SIZE);
            header.putInt(RECORD_MAGIC).put(FORMAT_VERSION).put(type).putShort(flags).putLong(key)
                    .putInt(storedLength).putInt(rawLength).putInt(crc).flip();

            long recordOffset = this.logSize;
            FileChannel channel = this.openChannel();
            writeFully(channel, header, recordOffset);
            writeFully(channel, payload.duplicate(), recordOffset + RECORD_HEADER_SIZE);
            this.logSize += RECORD_HEADER_SIZE + storedLength;
            this.uncheckpointedBytes += RECORD_HEADER_SIZE + storedLength;
            this.uncheckpointedRecords++;
            this.dirty = true;
            return new Entry(key, recordOffset + RECORD_HEADER_SIZE, storedLength, rawLength, crc,
                    flags, remoteRevision);
        }

        private void checkpointIfNeeded() throws IOException {
            if (this.uncheckpointedBytes >= CHECKPOINT_BYTES
                    || this.uncheckpointedRecords >= CHECKPOINT_RECORDS) {
                this.openChannel().force(false);
                this.writeIndex();
            }
        }

        private void checkpointAndPark() {
            this.lock.writeLock().lock();
            try {
                this.ensureOpen();
                if (this.channel != null) {
                    this.channel.force(false);
                    if (this.dirty) this.writeIndex();
                    this.closeChannel();
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } finally {
                this.lock.writeLock().unlock();
            }
        }

        private void flush() {
            this.lock.writeLock().lock();
            try {
                this.ensureOpen();
                if (this.compactable && this.shouldCompact()) this.compact();
                this.openChannel().force(false);
                if (this.dirty) this.writeIndex();
                if (this.compactable) this.closeChannel();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } finally {
                this.lock.writeLock().unlock();
            }
        }

        private void close() {
            this.lock.writeLock().lock();
            try {
                if (this.closed) return;
                if (this.compactable && this.shouldCompact()) this.compact();
                if (this.channel != null) {
                    this.channel.force(false);
                    if (this.dirty) this.writeIndex();
                    this.closeChannel();
                } else if (this.dirty) {
                    this.openChannel().force(false);
                    this.writeIndex();
                    this.closeChannel();
                }
                this.closed = true;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } finally {
                this.lock.writeLock().unlock();
            }
        }

        private boolean shouldCompact() {
            return this.logSize >= COMPACT_MIN_SIZE && this.logSize > this.liveBytes * 2 + (1L << 20);
        }

        private void compact() throws IOException {
            FileChannel current = this.openChannel();
            Path temporary = this.path.resolveSibling(this.path.getFileName() + ".compact");
            HashMap<Long, Entry> compacted = new HashMap<>(this.entries.size());
            long newSize = 0;
            try (FileChannel output = FileChannel.open(temporary, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
                for (Entry entry : this.entries.values()) {
                    CodecScratch scratch = CODEC.get();
                    ByteBuffer payload = scratch.buffer.clear();
                    payload.limit(entry.storedLength);
                    if (!readFully(current, payload, entry.payloadOffset)) continue;
                    payload.flip();
                    if (checksum(PUT, entry.flags, entry.key, entry.storedLength, entry.rawLength, payload) != entry.crc) {
                        Logger.warn("Dropping corrupt Voxy record during compaction", entry.key);
                        continue;
                    }
                    ByteBuffer header = ByteBuffer.allocate(RECORD_HEADER_SIZE);
                    header.putInt(RECORD_MAGIC).put(FORMAT_VERSION).put(PUT).putShort(entry.flags).putLong(entry.key)
                            .putInt(entry.storedLength).putInt(entry.rawLength).putInt(entry.crc).flip();
                    writeFully(output, header, newSize);
                    writeFully(output, payload, newSize + RECORD_HEADER_SIZE);
                    compacted.put(entry.key, new Entry(entry.key, newSize + RECORD_HEADER_SIZE,
                            entry.storedLength, entry.rawLength, entry.crc, entry.flags,
                            entry.remoteRevision));
                    newSize += RECORD_HEADER_SIZE + entry.storedLength;
                }
                output.force(false);
            }

            Files.deleteIfExists(this.indexPath);
            this.closeChannel();
            try {
                atomicReplace(temporary, this.path);
                forceDirectory(this.path);
            } catch (IOException e) {
                this.channel = FileChannel.open(this.path, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
                throw e;
            }
            this.channel = FileChannel.open(this.path, StandardOpenOption.READ, StandardOpenOption.WRITE);
            this.entries.clear();
            this.entries.putAll(compacted);
            this.logSize = newSize;
            this.liveBytes = newSize;
            this.dirty = true;
        }

        private long loadIndex() throws IOException {
            if (!Files.isRegularFile(this.indexPath)) return 0;
            try {
                byte[] bytes = Files.readAllBytes(this.indexPath);
                if (bytes.length < INDEX_HEADER_SIZE + 4) throw new IOException("short index");
                ByteBuffer data = ByteBuffer.wrap(bytes);
                if (data.getInt() != INDEX_MAGIC || data.getInt() != FORMAT_VERSION) throw new IOException("bad index header");
                long indexedThrough = data.getLong();
                int count = data.getInt();
                long expected = INDEX_HEADER_SIZE + (long) count * INDEX_ENTRY_SIZE + 4;
                if (count < 0 || expected != bytes.length || indexedThrough > this.openChannel().size()) throw new IOException("bad index bounds");

                CRC32C checksum = new CRC32C();
                checksum.update(bytes, 0, bytes.length - 4);
                if ((int) checksum.getValue() != ByteBuffer.wrap(bytes, bytes.length - 4, 4).getInt()) throw new IOException("bad index checksum");

                for (int i = 0; i < count; i++) {
                    long key = data.getLong();
                    long offset = data.getLong();
                    int storedLength = data.getInt();
                    int rawLength = data.getInt();
                    int crc = data.getInt();
                    short flags = (short) data.getInt();
                    long remoteRevision = data.getLong();
                    if (offset < RECORD_HEADER_SIZE || storedLength < 0 || rawLength <= 0
                            || storedLength > MAX_RECORD_SIZE || rawLength > MAX_RECORD_SIZE
                            || offset + storedLength > indexedThrough || (flags & ~COMPRESSED) != 0) {
                        throw new IOException("bad index entry");
                    }
                    this.entries.put(key, new Entry(key, offset, storedLength, rawLength, crc,
                            flags, remoteRevision));
                    this.liveBytes += RECORD_HEADER_SIZE + storedLength;
                }
                return indexedThrough;
            } catch (Exception e) {
                this.entries.clear();
                this.liveBytes = 0;
                this.dirty = true;
                Files.deleteIfExists(this.indexPath);
                Logger.warn("Rebuilding invalid Voxy index", this.indexPath, e);
                return 0;
            }
        }

        private void writeIndex() throws IOException {
            long length = INDEX_HEADER_SIZE + (long) this.entries.size() * INDEX_ENTRY_SIZE + 4;
            if (length > Integer.MAX_VALUE) throw new IOException("Voxy index too large");
            ByteBuffer data = ByteBuffer.allocate((int) length);
            data.putInt(INDEX_MAGIC).putInt(FORMAT_VERSION).putLong(this.logSize).putInt(this.entries.size());
            for (Entry entry : this.entries.values()) {
                data.putLong(entry.key).putLong(entry.payloadOffset).putInt(entry.storedLength)
                        .putInt(entry.rawLength).putInt(entry.crc).putInt(entry.flags)
                        .putLong(entry.remoteRevision);
            }
            CRC32C checksum = new CRC32C();
            ByteBuffer checked = data.duplicate();
            checked.flip();
            checksum.update(checked);
            data.putInt((int) checksum.getValue()).flip();

            Path temporary = this.indexPath.resolveSibling(this.indexPath.getFileName() + ".tmp");
            try (FileChannel output = FileChannel.open(temporary, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                writeFully(output, data, 0);
                output.force(false);
            }
            atomicReplace(temporary, this.indexPath);
            forceDirectory(this.indexPath);
            this.dirty = false;
            this.uncheckpointedBytes = 0;
            this.uncheckpointedRecords = 0;
        }

        private void rebuildIndex() {
            this.lock.writeLock().lock();
            try {
                this.ensureOpen();
                this.entries.clear();
                this.liveBytes = 0;
                Files.deleteIfExists(this.indexPath);
                this.scan(0);
                this.dirty = true;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } finally {
                this.lock.writeLock().unlock();
            }
        }

        private void scan(long start) throws IOException {
            FileChannel channel = this.openChannel();
            long size = channel.size();
            if (size != start) this.dirty = true;
            long position = Math.min(start, size);
            long completeThrough = position;
            ByteBuffer header = ByteBuffer.allocate(RECORD_HEADER_SIZE);
            ByteBuffer payload = CODEC.get().buffer;

            while (position + RECORD_HEADER_SIZE <= size) {
                header.clear();
                if (!readFully(channel, header, position)) break;
                header.flip();
                int magic = header.getInt();
                byte version = header.get();
                byte type = header.get();
                short flags = header.getShort();
                long key = header.getLong();
                int storedLength = header.getInt();
                int rawLength = header.getInt();
                int crc = header.getInt();

                boolean validHeader = magic == RECORD_MAGIC && version == FORMAT_VERSION
                        && (type == PUT || type == DELETE) && (flags & ~COMPRESSED) == 0
                        && storedLength >= 0 && storedLength <= MAX_RECORD_SIZE
                        && rawLength >= 0 && rawLength <= MAX_RECORD_SIZE
                        && ((type == PUT && storedLength > 0 && rawLength > 0)
                        || (type == DELETE && storedLength == 0 && rawLength == 0 && flags == 0));
                long end = position + RECORD_HEADER_SIZE + Math.max(storedLength, 0);
                if (!validHeader) {
                    if (this.compactable) {
                        Logger.error("Resetting Voxy shard with an unreadable record header", this.path, position);
                        this.entries.clear();
                        this.liveBytes = 0;
                        channel.truncate(0);
                        this.logSize = 0;
                        this.dirty = true;
                        return;
                    }
                    long next = findMagic(channel, position + 1, size);
                    if (next < 0) break;
                    position = next;
                    continue;
                }
                if (end > size) {
                    if (this.compactable) this.entries.remove(key);
                    break;
                }

                payload.clear();
                payload.limit(storedLength);
                if (!readFully(channel, payload, position + RECORD_HEADER_SIZE)) break;
                payload.flip();
                completeThrough = end;
                if (checksum(type, flags, key, storedLength, rawLength, payload) == crc) {
                    Entry previous;
                    if (type == PUT) {
                        long remoteRevision = this.compactable
                                ? recoverRemoteRevision(payload, flags, rawLength) : -1;
                        if (this.compactable && remoteRevision == Long.MIN_VALUE) {
                            Logger.warn("Ignoring Voxy section whose revision metadata cannot be recovered",
                                    this.path, position);
                            this.entries.remove(key);
                            position = end;
                            continue;
                        }
                        Entry entry = new Entry(key, position + RECORD_HEADER_SIZE, storedLength,
                                rawLength, crc, flags, remoteRevision);
                        previous = this.entries.put(key, entry);
                        this.liveBytes += RECORD_HEADER_SIZE + storedLength;
                    } else {
                        previous = this.entries.remove(key);
                    }
                    if (previous != null) this.liveBytes -= RECORD_HEADER_SIZE + previous.storedLength;
                } else {
                    Logger.warn("Ignoring corrupt Voxy record at", this.path, position);
                    // Mapping records are intentionally duplicated. Preserve the previous
                    // valid copy when a later copy is damaged; section shards instead drop the
                    // key so that authoritative data is regenerated.
                    if (this.compactable) this.entries.remove(key);
                }
                position = end;
            }

            if (completeThrough < size) {
                channel.truncate(completeThrough);
                this.dirty = true;
                Logger.warn("Truncated incomplete Voxy log tail", this.path, size - completeThrough, "bytes");
            }
            this.logSize = completeThrough;
        }

        private void ensureOpen() {
            if (this.closed) throw new IllegalStateException("Voxy log is closed");
        }

        private synchronized FileChannel openChannel() throws IOException {
            this.ensureOpen();
            this.lastAccess = System.nanoTime();
            if (this.channel == null) {
                this.channel = FileChannel.open(this.path, StandardOpenOption.CREATE,
                        StandardOpenOption.READ, StandardOpenOption.WRITE);
            }
            return this.channel;
        }

        private synchronized void closeChannel() throws IOException {
            FileChannel open = this.channel;
            this.channel = null;
            if (open != null) open.close();
        }

        private void park() {
            if (!this.lock.writeLock().tryLock()) return;
            try {
                if (this.channel != null) {
                    this.channel.force(false);
                    if (this.dirty) this.writeIndex();
                    this.closeChannel();
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } finally {
                this.lock.writeLock().unlock();
            }
        }

        private void discardClose() throws IOException {
            this.lock.writeLock().lock();
            try {
                if (this.channel != null) this.closeChannel();
                this.closed = true;
            } finally {
                this.lock.writeLock().unlock();
            }
        }
    }

    private record Entry(long key, long payloadOffset, int storedLength, int rawLength, int crc,
                         short flags, long remoteRevision) {}

    private static final class CodecScratch {
        private final byte[] bytes = new byte[MAX_RECORD_SIZE + 256];
        private final ByteBuffer buffer = ByteBuffer.wrap(this.bytes);
        private final Deflater deflater = new Deflater(Deflater.BEST_SPEED, true);
        private final Inflater inflater = new Inflater(true);
        private final byte[] sectionHeader = new byte[24];
    }

    /** Returns Long.MIN_VALUE when the first 24 canonical bytes cannot be recovered safely. */
    private static long recoverRemoteRevision(ByteBuffer stored, short flags, int rawLength) {
        if (rawLength < 24) return Long.MIN_VALUE;
        CodecScratch scratch = CODEC.get();
        byte[] header = scratch.sectionHeader;
        if ((flags & COMPRESSED) == 0) {
            if (stored.remaining() < header.length) return Long.MIN_VALUE;
            ByteBuffer copy = stored.duplicate();
            copy.get(header);
        } else {
            Inflater inflater = scratch.inflater;
            inflater.reset();
            inflater.setInput(stored.duplicate());
            int count = 0;
            try {
                while (count < header.length) {
                    int read = inflater.inflate(header, count, header.length - count);
                    if (read == 0) return Long.MIN_VALUE;
                    count += read;
                }
            } catch (DataFormatException exception) {
                return Long.MIN_VALUE;
            }
        }
        return ByteBuffer.wrap(header).order(ByteOrder.nativeOrder()).getLong(16);
    }

    private static ByteBuffer compress(ByteBuffer input) {
        CodecScratch scratch = CODEC.get();
        Deflater deflater = scratch.deflater;
        deflater.reset();
        deflater.setInput(input.duplicate());
        deflater.finish();
        int size = deflater.deflate(scratch.bytes);
        if (!deflater.finished() || size + 32 >= input.remaining()) return null;
        return ByteBuffer.wrap(scratch.bytes, 0, size);
    }

    private static int checksum(byte type, short flags, long key, int storedLength, int rawLength, ByteBuffer payload) {
        CRC32C crc = new CRC32C();
        crc.update(FORMAT_VERSION);
        crc.update(type);
        updateShort(crc, flags);
        updateLong(crc, key);
        updateInt(crc, storedLength);
        updateInt(crc, rawLength);
        crc.update(payload.duplicate());
        return (int) crc.getValue();
    }

    private static void updateShort(CRC32C crc, short value) {
        crc.update(value >>> 8);
        crc.update(value);
    }

    private static void updateInt(CRC32C crc, int value) {
        crc.update(value >>> 24);
        crc.update(value >>> 16);
        crc.update(value >>> 8);
        crc.update(value);
    }

    private static void updateLong(CRC32C crc, long value) {
        updateInt(crc, (int) (value >>> 32));
        updateInt(crc, (int) value);
    }

    private static boolean readFully(FileChannel channel, ByteBuffer target, long position) throws IOException {
        while (target.hasRemaining()) {
            int read = channel.read(target, position);
            if (read < 0) return false;
            if (read == 0) {
                Thread.onSpinWait();
            } else {
                position += read;
            }
        }
        return true;
    }

    private static void writeFully(FileChannel channel, ByteBuffer source, long position) throws IOException {
        while (source.hasRemaining()) {
            int written = channel.write(source, position);
            if (written == 0) {
                Thread.onSpinWait();
            } else {
                position += written;
            }
        }
    }

    private static long findMagic(FileChannel channel, long start, long size) throws IOException {
        byte[] expected = {(byte) (RECORD_MAGIC >>> 24), (byte) (RECORD_MAGIC >>> 16),
                (byte) (RECORD_MAGIC >>> 8), (byte) RECORD_MAGIC};
        ByteBuffer input = ByteBuffer.allocate(64 << 10);
        int matched = 0;
        long position = start;
        while (position < size) {
            input.clear();
            input.limit((int) Math.min(input.capacity(), size - position));
            int requested = input.remaining();
            if (!readFully(channel, input, position)) return -1;
            input.flip();
            while (input.hasRemaining()) {
                byte value = input.get();
                if (value == expected[matched]) {
                    matched++;
                    if (matched == expected.length) return position + input.position() - expected.length;
                } else {
                    matched = value == expected[0] ? 1 : 0;
                }
            }
            position += requested;
        }
        return -1;
    }

    private static void atomicReplace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Directory fsync is available on Unix; unsupported platforms still retain atomic rename semantics. */
    private static void forceDirectory(Path file) {
        try (FileChannel directory = FileChannel.open(file.getParent(), StandardOpenOption.READ)) {
            directory.force(true);
        } catch (IOException | UnsupportedOperationException ignored) {
        }
    }
}
