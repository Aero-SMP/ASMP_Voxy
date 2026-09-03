package me.cortex.voxy.client.lod;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Bounded per-region cache addressed directly by immutable section fingerprints. */
final class RegionalCache implements AutoCloseable {
    private static final byte[] MAGIC = {'V','X','Y','S','E','C',0,0};
    private static final int HEADER_BYTES = 64;
    private static final int RECORD_BYTES = 20;
    private static final int MAX_OPEN_SHARDS = 64;
    private static final long MAX_SHARD_BYTES = 256L * 1024 * 1024;
    private static final long MAX_CACHE_BYTES = 2L * 1024 * 1024 * 1024;

    private final Path root;
    private final RegionalProtocol.Hash32 worldIdentity;
    private final LinkedHashMap<Long, Shard> shards = new LinkedHashMap<>(64, 0.75f, true);
    private long cacheBytes;

    RegionalCache(Path root, RegionalProtocol.Hash32 worldIdentity) throws IOException {
        this.root = Files.createDirectories(Objects.requireNonNull(root, "cache root"));
        this.worldIdentity = Objects.requireNonNull(worldIdentity, "world identity");
        try (var paths = Files.list(this.root)) {
            this.cacheBytes = paths.filter(RegionalCache::isShard)
                    .mapToLong(RegionalCache::sizeOrZero).sum();
        }
    }

    byte[] get(RegionalProtocol.RegionIndex index, int ordinal) throws IOException {
        if (index.isEmpty(ordinal)) return null;
        Shard shard;
        synchronized (this) {
            shard = shard(index, false);
            if (shard != null) shard.users++;
        }
        if (shard == null) return null;
        try {
            return shard.get(key(index, ordinal));
        } finally {
            release(shard);
        }
    }

    void put(RegionalProtocol.RegionIndex index, int ordinal, byte[] compressed)
            throws IOException {
        if (index.isEmpty(ordinal) || compressed.length != index.compressedLength(ordinal)) return;
        CacheKey key = key(index, ordinal);
        long added = RECORD_BYTES + (long) compressed.length;
        Shard shard;
        synchronized (this) {
            shard = shard(index, true);
            if (shard == null || shard.contains(key)) return;
            if (shard.length() + added > MAX_SHARD_BYTES) {
                if (shard.users != 0) return;
                resetShard(index, shard);
                shard = shard(index, true);
            }
            if (shard == null || !ensureBudget(added, shard.path)) return;
            this.cacheBytes += added;
            shard.users++;
        }
        boolean written = false;
        try {
            written = shard.put(key, compressed);
        } finally {
            synchronized (this) {
                if (!written) {
                    this.cacheBytes = Math.max(0, this.cacheBytes - added);
                }
                releaseLocked(shard);
            }
        }
    }

    synchronized void quarantine(RegionalProtocol.RegionIndex index, int ordinal) {
        try {
            Shard shard = shard(index, false);
            if (shard == null || !shard.contains(key(index, ordinal))) return;
            long added = RECORD_BYTES;
            if (shard.length() + added <= MAX_SHARD_BYTES
                    && ensureBudget(added, shard.path)) {
                shard.remove(key(index, ordinal));
                this.cacheBytes += added;
            } else {
                if (shard.users == 0) resetShard(index, shard);
            }
        } catch (IOException ignored) {
            // A cache miss is always safe; the authoritative section remains on the server.
        }
    }

    private Shard shard(RegionalProtocol.RegionIndex index, boolean create) throws IOException {
        long id = regionKey(index.regionX(), index.regionZ());
        Shard current = this.shards.get(id);
        if (current != null) return current;
        Path path = path(index.regionX(), index.regionZ());
        Shard opened = Files.exists(path)
                ? Shard.open(path, this.worldIdentity, index.regionX(), index.regionZ()) : null;
        if (opened == null && Files.exists(path)) {
            this.cacheBytes -= Files.size(path);
            Files.delete(path);
        }
        if (opened == null && create && ensureBudget(HEADER_BYTES, path)) {
            opened = Shard.create(path, this.worldIdentity, index.regionX(), index.regionZ());
            this.cacheBytes += HEADER_BYTES;
        }
        if (opened != null) {
            this.shards.put(id, opened);
            trimOpenShards();
        }
        return opened;
    }

    private void resetShard(RegionalProtocol.RegionIndex index, Shard shard) throws IOException {
        this.shards.remove(regionKey(index.regionX(), index.regionZ()));
        shard.close();
        long size = sizeOrZero(shard.path);
        Files.deleteIfExists(shard.path);
        this.cacheBytes = Math.max(0, this.cacheBytes - size);
    }

    private boolean ensureBudget(long added, Path target) throws IOException {
        if (this.cacheBytes + added <= MAX_CACHE_BYTES) return true;
        try (var paths = Files.list(this.root)) {
            for (Path candidate : paths.filter(RegionalCache::isShard)
                    .filter(path -> !path.equals(target))
                    .sorted(Comparator.comparingLong(RegionalCache::modifiedOrMinimum)).toList()) {
                if (!closePath(candidate)) continue;
                long size = sizeOrZero(candidate);
                Files.deleteIfExists(candidate);
                this.cacheBytes = Math.max(0, this.cacheBytes - size);
                if (this.cacheBytes + added <= MAX_CACHE_BYTES) return true;
            }
        }
        return this.cacheBytes + added <= MAX_CACHE_BYTES;
    }

    private boolean closePath(Path path) {
        Iterator<Map.Entry<Long, Shard>> iterator = this.shards.entrySet().iterator();
        while (iterator.hasNext()) {
            Shard shard = iterator.next().getValue();
            if (!shard.path.equals(path)) continue;
            if (shard.users != 0) return false;
            iterator.remove();
            shard.close();
            return true;
        }
        return true;
    }

    private void trimOpenShards() {
        while (this.shards.size() > MAX_OPEN_SHARDS) {
            boolean closed = false;
            Iterator<Shard> iterator = this.shards.values().iterator();
            while (iterator.hasNext()) {
                Shard oldest = iterator.next();
                if (oldest.users != 0) continue;
                iterator.remove();
                oldest.close();
                closed = true;
                break;
            }
            if (!closed) return;
        }
    }

    private void release(Shard shard) {
        synchronized (this) { releaseLocked(shard); }
    }

    private void releaseLocked(Shard shard) {
        if (--shard.users < 0) throw new IllegalStateException("regional cache shard lease underflow");
        trimOpenShards();
    }

    @Override public synchronized void close() {
        for (Shard shard : this.shards.values()) shard.close();
        this.shards.clear();
    }

    private Path path(int x, int z) {
        return this.root.resolve("r." + x + "." + z + ".vxcache");
    }

    private static CacheKey key(RegionalProtocol.RegionIndex index, int ordinal) {
        return new CacheKey(index.sectionFingerprint(ordinal), index.compressedLength(ordinal));
    }

    private static long regionKey(int x, int z) {
        return Integer.toUnsignedLong(x) | Integer.toUnsignedLong(z) << 32;
    }

    private static boolean isShard(Path path) {
        return path.getFileName().toString().endsWith(".vxcache");
    }

    private static long sizeOrZero(Path path) {
        try { return Files.size(path); } catch (IOException ignored) { return 0; }
    }

    private static long modifiedOrMinimum(Path path) {
        try { return Files.getLastModifiedTime(path).toMillis(); }
        catch (IOException ignored) { return Long.MIN_VALUE; }
    }

    private record CacheKey(RegionalProtocol.Fingerprint fingerprint, int length) {}

    private static final class Shard implements AutoCloseable {
        final Path path;
        private final RandomAccessFile file;
        private final FileChannel channel;
        private final Map<CacheKey, Long> records;
        private int users;

        private Shard(Path path, RandomAccessFile file, Map<CacheKey, Long> records) {
            this.path = path;
            this.file = file;
            this.channel = file.getChannel();
            this.records = records;
        }

        static Shard open(Path path, RegionalProtocol.Hash32 world, int x, int z)
                throws IOException {
            RandomAccessFile file = new RandomAccessFile(path.toFile(), "rw");
            try {
                if (file.length() < HEADER_BYTES || file.length() > MAX_SHARD_BYTES) {
                    return closeNull(file);
                }
                byte[] header = new byte[HEADER_BYTES];
                file.readFully(header);
                ByteBuffer input = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
                byte[] magic = new byte[8]; input.get(magic);
                RegionalProtocol.Hash32 storedWorld = RegionalProtocol.Hash32.read(input);
                int storedX = input.getInt(), storedZ = input.getInt();
                if (!java.util.Arrays.equals(magic, MAGIC) || !storedWorld.equals(world)
                        || storedX != x || storedZ != z || input.getLong() != 0) {
                    return closeNull(file);
                }
                Map<CacheKey, Long> records = new HashMap<>();
                long offset = HEADER_BYTES;
                ByteBuffer record = ByteBuffer.allocate(RECORD_BYTES).order(ByteOrder.LITTLE_ENDIAN);
                while (offset + RECORD_BYTES <= file.length()) {
                    record.clear();
                    readFully(file.getChannel(), offset, record);
                    record.flip();
                    RegionalProtocol.Fingerprint fingerprint = RegionalProtocol.Fingerprint.read(record);
                    int signedLength = record.getInt();
                    if (signedLength == 0 || signedLength == Integer.MIN_VALUE) break;
                    int length = Math.abs(signedLength);
                    if (length > RegionalProtocol.MAX_SECTION_BYTES) break;
                    CacheKey key = new CacheKey(fingerprint, length);
                    if (signedLength < 0) {
                        records.remove(key);
                        offset += RECORD_BYTES;
                    } else {
                        if (offset + RECORD_BYTES + length > file.length()) break;
                        records.put(key, offset + RECORD_BYTES);
                        offset += RECORD_BYTES + length;
                    }
                }
                if (offset != file.length()) file.setLength(offset);
                Files.setLastModifiedTime(path, java.nio.file.attribute.FileTime.fromMillis(
                        System.currentTimeMillis()));
                return new Shard(path, file, records);
            } catch (Throwable failure) {
                try { file.close(); } catch (IOException suppressed) { failure.addSuppressed(suppressed); }
                if (failure instanceof IOException io) throw io;
                if (failure instanceof RuntimeException runtime) throw runtime;
                throw new IOException("cannot open regional section cache", failure);
            }
        }

        static Shard create(Path path, RegionalProtocol.Hash32 world, int x, int z)
                throws IOException {
            Files.createDirectories(path.getParent());
            ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
            header.put(MAGIC).putLong(world.a()).putLong(world.b()).putLong(world.c()).putLong(world.d());
            header.putInt(x).putInt(z).putLong(0).position(HEADER_BYTES).flip();
            try (FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE)) {
                writeFully(channel, 0, header);
            }
            return open(path, world, x, z);
        }

        private static Shard closeNull(RandomAccessFile file) throws IOException {
            file.close();
            return null;
        }

        synchronized boolean contains(CacheKey key) { return this.records.containsKey(key); }
        synchronized long length() throws IOException { return this.file.length(); }

        synchronized byte[] get(CacheKey key) throws IOException {
            Long offset = this.records.get(key);
            if (offset == null) return null;
            byte[] bytes = new byte[key.length];
            readFully(this.channel, offset, ByteBuffer.wrap(bytes));
            return bytes;
        }

        synchronized boolean put(CacheKey key, byte[] bytes) throws IOException {
            if (this.records.containsKey(key)) return false;
            long offset = this.file.length();
            ByteBuffer header = ByteBuffer.allocate(RECORD_BYTES).order(ByteOrder.LITTLE_ENDIAN);
            header.putLong(key.fingerprint.low()).putLong(key.fingerprint.high());
            header.putInt(key.length).flip();
            writeFully(this.channel, offset, header);
            writeFully(this.channel, offset + RECORD_BYTES, ByteBuffer.wrap(bytes));
            this.records.put(key, offset + RECORD_BYTES);
            return true;
        }

        synchronized void remove(CacheKey key) throws IOException {
            if (this.records.remove(key) == null) return;
            long offset = this.file.length();
            ByteBuffer tombstone = ByteBuffer.allocate(RECORD_BYTES).order(ByteOrder.LITTLE_ENDIAN);
            tombstone.putLong(key.fingerprint.low()).putLong(key.fingerprint.high());
            tombstone.putInt(-key.length).flip();
            writeFully(this.channel, offset, tombstone);
        }

        private static void readFully(FileChannel channel, long offset, ByteBuffer output)
                throws IOException {
            while (output.hasRemaining()) {
                int read = channel.read(output, offset);
                if (read < 0) throw new IOException("truncated regional cache record");
                offset += read;
            }
        }

        private static void writeFully(FileChannel channel, long offset, ByteBuffer input)
                throws IOException {
            while (input.hasRemaining()) offset += channel.write(input, offset);
        }

        @Override public synchronized void close() {
            try { this.file.close(); } catch (IOException ignored) {}
        }
    }
}
