package me.cortex.voxy.client.lod;

import me.cortex.voxy.client.lod.WireMessage.EncodedObject;
import me.cortex.voxy.client.lod.WireMessage.Hash256;
import me.cortex.voxy.client.lod.WireMessage.ObjectKind;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.CRC32C;

/**
 * Recoverable append-only client object packs with a disposable checksummed index.
 *
 * <p>The compressed wire envelope remains independently addressable. Pack IDs and offsets never
 * participate in its canonical identity. A checkpoint permits metadata-only startup; bytes are
 * still CRC-checked on every read and typed-BLAKE3 checked by {@link ObjectDecoder} before use.
 * A missing or damaged checkpoint falls back to a bounded pack scan.</p>
 */
final class PackedObjectStore implements AutoCloseable {
    private static final byte[] PACK_MAGIC = {'V', 'X', 'Y', 'P', 'A', 'C', 'K', 0};
    private static final byte[] RECORD_MAGIC = {'V', 'X', 'Y', 'O', 'B', 'J', 0, 0};
    private static final byte[] INDEX_MAGIC = {'V', 'X', 'Y', 'I', 'N', 'D', 'X', 0};
    private static final int PACK_HEADER_BYTES = 20;
    private static final int RECORD_HEADER_BYTES = 64;
    private static final int INDEX_HEADER_BYTES = 16;
    private static final int INDEX_PACK_BYTES = 16;
    private static final int INDEX_ENTRY_BYTES = 65;
    private static final byte PUT = 1;
    private static final byte DELETE = 2;
    private static final byte RESET = 3;
    private static final int MAX_PACK_FILES = 65_536;
    private static final long DEFAULT_MAX_PACK_BYTES = 128L << 20;
    private static final long MIN_PHYSICAL_HEADROOM = 64L << 20;
    private static final long CHECKPOINT_BYTES = 64L << 20;
    private static final int CHECKPOINT_RECORDS = 4096;

    private final Path root;
    private final Path packsDirectory;
    private final Path indexPath;
    private final int maxObjects;
    private final long maxStoredBytes;
    private final int maxObjectBytes;
    private final long maxPackBytes;
    /** Payload plus worst-case per-object record, padding, and pack headers. */
    private final long maxLogicalPhysicalBytes;
    /** Hard steady-state bound; an epoch rewrite may temporarily require one live-set copy. */
    private final long maxPhysicalBytes;
    /** Maximum interrupted-rewrite footprint before further rewrites are refused. */
    private final long maxRecoveryPhysicalBytes;
    private final TreeMap<Long, Pack> packs = new TreeMap<>();
    private final LinkedHashMap<Hash256, Entry> index = new LinkedHashMap<>(16, 0.75f, true);
    private final Set<Hash256> pins = new HashSet<>();
    private long activePack;
    private long nextPack;
    private long storedBytes;
    private long uncheckpointedBytes;
    private int uncheckpointedRecords;
    private boolean closed;
    private boolean epochRewrite;

    PackedObjectStore(Path root, int maxObjects, long maxStoredBytes, int maxObjectBytes)
            throws IOException {
        this(root, maxObjects, maxStoredBytes, maxObjectBytes, DEFAULT_MAX_PACK_BYTES);
    }

    PackedObjectStore(Path root, int maxObjects, long maxStoredBytes, int maxObjectBytes,
                        long maxPackBytes) throws IOException {
        this(root, maxObjects, maxStoredBytes, maxObjectBytes, maxPackBytes,
                Math.max(saturatingMultiply(maxPackBytes, 2), MIN_PHYSICAL_HEADROOM));
    }

    PackedObjectStore(Path root, int maxObjects, long maxStoredBytes, int maxObjectBytes,
                        long maxPackBytes, long physicalHeadroom) throws IOException {
        if (maxObjects < 1 || maxStoredBytes < 0 || maxObjectBytes < 0
                || maxObjectBytes > WireMessage.MAX_COMPRESSED_OBJECT_BYTES
                || maxObjectBytes > maxStoredBytes
                || maxPackBytes < PACK_HEADER_BYTES + RECORD_HEADER_BYTES + 1L
                || physicalHeadroom < saturatingMultiply(maxPackBytes, 2)) {
            throw new IllegalArgumentException("invalid packed cache limits");
        }
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        this.maxObjects = maxObjects;
        this.maxStoredBytes = maxStoredBytes;
        this.maxObjectBytes = maxObjectBytes;
        this.maxPackBytes = maxPackBytes;
        long perObjectOverhead = RECORD_HEADER_BYTES + 7L + PACK_HEADER_BYTES;
        long logicalOverhead = saturatingAdd(PACK_HEADER_BYTES,
                saturatingMultiply(maxObjects, perObjectOverhead));
        this.maxLogicalPhysicalBytes = saturatingAdd(maxStoredBytes, logicalOverhead);
        this.maxPhysicalBytes = saturatingAdd(this.maxLogicalPhysicalBytes, physicalHeadroom);
        this.maxRecoveryPhysicalBytes = saturatingAdd(
                this.maxPhysicalBytes, this.maxLogicalPhysicalBytes);
        Files.createDirectories(this.root);
        this.packsDirectory = Files.createDirectories(this.root.resolve("packs"));
        this.indexPath = this.root.resolve("objects.idx");

        removeStartupDebris();
        OpenResult opened = openStore();
        int overLimit = evictOverLimitOnOpen();
        if (this.packs.isEmpty()) rotate();
        if (opened.indexRebuilt || opened.tailsScanned || overLimit != 0) checkpoint();
    }

    synchronized boolean put(EncodedObject encoded) throws IOException {
        ensureOpen();
        Objects.requireNonNull(encoded, "encoded");
        Entry existing = this.index.get(encoded.hash());
        if (existing != null) {
            try {
                readValidated(encoded.hash(), existing).close();
                return false;
            } catch (CorruptRecordException failure) {
                discard(encoded.hash(), existing);
            }
        }
        if (encoded.compressedLength() > this.maxObjectBytes) return false;
        long incomingPhysical = recordBytes(encoded.compressedLength());
        if (!canAppendSteadyState(incomingPhysical)) {
            // Try to reclaim old PUT/DELETE history before mutating the logical cache. If the
            // filesystem cannot hold an epoch rewrite, admission stops at the hard bound; valid
            // terrain still proceeds through the in-memory residency path.
            compactAll(false);
        }
        try {
            evictUntilFits(encoded.compressedLength(), false);
            if (this.index.size() >= this.maxObjects
                    || encoded.compressedLength() > this.maxStoredBytes - this.storedBytes) {
                return false;
            }
            appendPut(encoded);
        } catch (PhysicalLimitException full) {
            // Some completed tombstones may have reduced the logical cache before the reserved
            // maintenance space ran out. Checkpoint that safe state and decline this object.
            checkpointIfNeeded();
            return false;
        }
        checkpointIfNeeded();
        compactIfNeeded();
        return true;
    }

    synchronized Optional<EncodedObject> getEncoded(Hash256 hash) throws IOException {
        ensureOpen();
        Objects.requireNonNull(hash, "hash");
        Entry entry = this.index.get(hash);
        if (entry == null) return Optional.empty();
        try {
            return Optional.of(readValidated(hash, entry));
        } catch (CorruptRecordException failure) {
            discard(hash, entry);
            checkpointIfNeeded();
            return Optional.empty();
        }
    }

    synchronized void quarantine(Hash256 hash) throws IOException {
        ensureOpen();
        Objects.requireNonNull(hash, "hash");
        Entry entry = this.index.get(hash);
        if (entry == null) return;
        discard(hash, entry);
        checkpointIfNeeded();
    }

    private synchronized long physicalBytes() {
        ensureOpen();
        long total = 0;
        for (Pack pack : this.packs.values()) total = saturatingAdd(total, pack.length);
        return total;
    }

    synchronized void replacePins(ObjectCache.PinSource source) {
        ensureOpen();
        Objects.requireNonNull(source, "source");
        this.pins.clear();
        source.forEach(hash -> {
            this.pins.add(Objects.requireNonNull(hash, "pinned hash"));
            if (this.pins.size() > this.maxObjects) {
                this.pins.clear();
                throw new IllegalStateException("cache pin set exceeds the object bound");
            }
        });
    }

    @Override
    public synchronized void close() {
        if (this.closed) return;
        RuntimeException failure = null;
        try {
            checkpoint();
        } catch (IOException exception) {
            failure = new UncheckedIOException("unable to checkpoint packed cache", exception);
        }
        for (Pack pack : this.packs.values()) {
            try {
                pack.channel.close();
            } catch (IOException exception) {
                if (failure == null) failure = new UncheckedIOException(exception);
                else failure.addSuppressed(exception);
            }
        }
        this.packs.clear();
        this.index.clear();
        this.pins.clear();
        this.storedBytes = 0;
        this.closed = true;
        if (failure != null) throw failure;
    }

    private OpenResult openStore() throws IOException {
        TreeMap<Long, Path> paths = discoverPacks();
        Checkpoint checkpoint = null;
        boolean indexRebuilt = false;
        if (Files.isRegularFile(this.indexPath, LinkOption.NOFOLLOW_LINKS)) {
            try {
                checkpoint = readIndex(this.indexPath);
            } catch (IOException failure) {
                Files.deleteIfExists(this.indexPath);
                indexRebuilt = true;
            }
        } else {
            indexRebuilt = true;
        }
        if (checkpoint != null) {
            try {
                OpenResult result = openCheckpoint(paths, checkpoint);
                return new OpenResult(indexRebuilt, result.tailsScanned,
                        result.corruptRecords, result.tornPacks);
            } catch (IOException | RuntimeException failure) {
                closePacks();
                this.index.clear();
                this.storedBytes = 0;
                indexRebuilt = true;
            }
        }
        OpenResult rebuilt = rebuildFromPacks(paths);
        return new OpenResult(indexRebuilt, rebuilt.tailsScanned,
                rebuilt.corruptRecords, rebuilt.tornPacks);
    }

    private OpenResult openCheckpoint(TreeMap<Long, Path> paths, Checkpoint checkpoint)
            throws IOException {
        if (!paths.keySet().containsAll(checkpoint.lengths.keySet())) {
            throw new CorruptRecordException("cache index references a missing pack");
        }
        for (Map.Entry<Long, Path> path : paths.entrySet()) {
            Pack pack = openPack(path.getValue(), path.getKey());
            this.packs.put(path.getKey(), pack);
            long checkpointLength = checkpoint.lengths.getOrDefault(path.getKey(),
                    (long) PACK_HEADER_BYTES);
            if (checkpointLength > pack.length || checkpointLength < PACK_HEADER_BYTES
                    || ((checkpointLength - PACK_HEADER_BYTES) & 7L) != 0L) {
                throw new CorruptRecordException("cache pack is shorter than its index");
            }
        }
        for (Map.Entry<Hash256, Entry> indexed : checkpoint.entries.entrySet()) {
            Entry entry = indexed.getValue();
            Long checkpointLength = checkpoint.lengths.get(entry.packId);
            if (checkpointLength == null || !entry.boundsWithin(checkpointLength,
                    this.maxObjectBytes)) {
                throw new CorruptRecordException("cache index entry is outside its pack");
            }
            replaceEntry(indexed.getKey(), entry);
        }
        int corrupt = 0;
        int torn = 0;
        boolean tails = false;
        for (Pack pack : this.packs.values()) {
            long start = checkpoint.lengths.getOrDefault(pack.id, (long) PACK_HEADER_BYTES);
            if (start != pack.length) {
                tails = true;
                ScanResult result = scanPack(pack, start);
                corrupt += result.corruptRecords;
                torn += result.torn ? 1 : 0;
            }
        }
        finishOpen();
        return new OpenResult(false, tails, corrupt, torn);
    }

    private OpenResult rebuildFromPacks(TreeMap<Long, Path> paths) throws IOException {
        int corrupt = 0;
        int torn = 0;
        for (Map.Entry<Long, Path> path : paths.entrySet()) {
            Pack pack;
            try {
                pack = openPack(path.getValue(), path.getKey());
            } catch (IOException failure) {
                Files.deleteIfExists(path.getValue());
                syncDirectory(this.packsDirectory);
                corrupt++;
                continue;
            }
            this.packs.put(pack.id, pack);
            ScanResult result = scanPack(pack, PACK_HEADER_BYTES);
            corrupt += result.corruptRecords;
            torn += result.torn ? 1 : 0;
        }
        finishOpen();
        return new OpenResult(true, true, corrupt, torn);
    }

    private void finishOpen() throws IOException {
        if (this.packs.isEmpty()) rotate();
        this.activePack = this.packs.lastKey();
        this.nextPack = Math.addExact(this.activePack, 1);
    }

    private TreeMap<Long, Path> discoverPacks() throws IOException {
        TreeMap<Long, Path> result = new TreeMap<>();
        long physicalBytes = 0;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(this.packsDirectory)) {
            for (Path file : files) {
                Long id = parsePackName(file);
                if (id == null || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) continue;
                long length = Files.size(file);
                if (length > this.maxPackBytes) {
                    throw new CorruptRecordException("cache pack exceeds its byte bound");
                }
                physicalBytes = saturatingAdd(physicalBytes, length);
                if (physicalBytes > this.maxRecoveryPhysicalBytes) {
                    throw new CorruptRecordException("cache exceeds its recovery byte bound");
                }
                if (result.put(id, file) != null) {
                    throw new CorruptRecordException("duplicate cache pack identifier");
                }
                if (result.size() > MAX_PACK_FILES) {
                    throw new CorruptRecordException("cache exceeds its bounded pack-file count");
                }
            }
        }
        return result;
    }

    private ScanResult scanPack(Pack pack, long start) throws IOException {
        long offset = start;
        int corrupt = 0;
        boolean torn = false;
        while (offset < pack.length) {
            if (pack.length - offset < RECORD_HEADER_BYTES) {
                truncate(pack, offset);
                torn = true;
                break;
            }
            byte[] raw = new byte[RECORD_HEADER_BYTES];
            if (!readFully(pack.channel, ByteBuffer.wrap(raw), offset)) {
                truncate(pack, offset);
                torn = true;
                break;
            }
            RecordHeader header;
            try {
                header = decodeRecordHeader(raw);
            } catch (CorruptRecordException failure) {
                truncate(pack, offset);
                torn = true;
                break;
            }
            long recordBytes = recordBytes(header.compressedLength);
            if (offset > pack.length - recordBytes) {
                truncate(pack, offset);
                torn = true;
                break;
            }
            boolean valid = true;
            if (header.type == PUT) {
                byte[] payload = new byte[header.compressedLength];
                if (!readFully(pack.channel, ByteBuffer.wrap(payload),
                        offset + RECORD_HEADER_BYTES)
                        || WireMessage.checksum(payload) != header.compressedChecksum) {
                    valid = false;
                }
            }
            int padding = padding(header.compressedLength);
            if (padding != 0) {
                byte[] bytes = new byte[padding];
                if (!readFully(pack.channel, ByteBuffer.wrap(bytes),
                        offset + RECORD_HEADER_BYTES + header.compressedLength)
                        || !allZero(bytes)) valid = false;
            }
            if (valid) {
                if (header.type == PUT) {
                    replaceEntry(header.hash, header.entry(pack.id, offset));
                } else if (header.type == DELETE) {
                    removeEntry(header.hash);
                } else {
                    clearIndex();
                }
            } else {
                corrupt++;
            }
            offset += recordBytes;
        }
        return new ScanResult(corrupt, torn);
    }

    private int evictOverLimitOnOpen() throws IOException {
        int removed = 0;
        while (this.index.size() > this.maxObjects || this.storedBytes > this.maxStoredBytes) {
            Hash256 victim = firstUnpinned();
            if (victim == null) break;
            Entry entry = this.index.get(victim);
            discard(victim, entry);
            removed++;
        }
        return removed;
    }

    private int evictUntilFits(long incomingBytes, boolean all) throws IOException {
        int removed = 0;
        while (!this.index.isEmpty() && (all || this.index.size() >= this.maxObjects
                || incomingBytes > this.maxStoredBytes - this.storedBytes)) {
            Hash256 victim = firstUnpinned();
            if (victim == null) break;
            Entry entry = this.index.get(victim);
            discard(victim, entry);
            removed++;
        }
        return removed;
    }

    private Hash256 firstUnpinned() {
        for (Hash256 hash : this.index.keySet()) if (!this.pins.contains(hash)) return hash;
        return null;
    }

    private void appendPut(EncodedObject object) throws IOException {
        ByteBuffer compressed = object.compressedBufferInternal();
        int compressedLength = compressed.remaining();
        long bytes = recordBytes(compressedLength);
        Pack pack = activeFor(bytes);
        long offset = pack.length;
        byte[] header = encodePutHeader(object);
        writeFully(pack.channel, ByteBuffer.wrap(header), offset);
        writeFully(pack.channel, compressed, offset + RECORD_HEADER_BYTES);
        writePadding(pack.channel, offset + RECORD_HEADER_BYTES + compressedLength,
                padding(compressedLength));
        pack.channel.truncate(offset + bytes);
        pack.length = offset + bytes;
        this.uncheckpointedBytes = Math.addExact(this.uncheckpointedBytes, bytes);
        this.uncheckpointedRecords++;
        replaceEntry(object.hash(), new Entry(pack.id, offset, object.kind(),
                object.dictionaryId(), object.canonicalLength(),
                object.compressedLength(), object.compressedChecksum()));
    }

    private void appendDelete(Hash256 hash) throws IOException {
        long bytes = RECORD_HEADER_BYTES;
        Pack pack = activeFor(bytes);
        long offset = pack.length;
        writeFully(pack.channel, ByteBuffer.wrap(encodeDeleteHeader(hash)), offset);
        pack.channel.truncate(offset + bytes);
        pack.length = offset + bytes;
        this.uncheckpointedBytes = Math.addExact(this.uncheckpointedBytes, bytes);
        this.uncheckpointedRecords++;
    }

    private Pack activeFor(long recordBytes) throws IOException {
        Pack pack = this.packs.get(this.activePack);
        boolean needsPack = pack == null || pack.length > PACK_HEADER_BYTES
                && pack.length + recordBytes > this.maxPackBytes;
        long physicalGrowth = saturatingAdd(recordBytes, needsPack ? PACK_HEADER_BYTES : 0);
        if (!this.epochRewrite && !canAppendSteadyState(physicalGrowth)) {
            throw new PhysicalLimitException();
        }
        if (needsPack) {
            rotate();
            pack = this.packs.get(this.activePack);
        }
        return pack;
    }

    private boolean canAppendSteadyState(long bytes) {
        long physical = physicalBytes();
        return bytes >= 0 && physical <= this.maxPhysicalBytes
                && bytes <= this.maxPhysicalBytes - physical;
    }

    private void rotate() throws IOException {
        if (this.packs.size() >= MAX_PACK_FILES) {
            throw new IOException("cache has too many pack files; compact it before writing");
        }
        long id = this.packs.isEmpty() ? 0 : this.nextPack;
        Path path = packPath(id);
        FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.READ, StandardOpenOption.WRITE);
        boolean success = false;
        try {
            writeFully(channel, ByteBuffer.wrap(encodePackHeader(id)), 0);
            channel.truncate(PACK_HEADER_BYTES);
            channel.force(true);
            syncDirectory(this.packsDirectory);
            this.packs.put(id, new Pack(id, path, channel, PACK_HEADER_BYTES));
            this.activePack = id;
            this.nextPack = Math.addExact(id, 1);
            success = true;
        } finally {
            if (!success) {
                channel.close();
                Files.deleteIfExists(path);
            }
        }
    }

    private void discard(Hash256 hash, Entry expected) throws IOException {
        if (this.index.get(hash) != expected) return;
        try {
            appendDelete(hash);
        } catch (PhysicalLimitException full) {
            compactAll(false);
            if (this.index.get(hash) != expected) return;
            try {
                appendDelete(hash);
            } catch (PhysicalLimitException stillFull) {
                // This cache is disposable and its checkpoint is authoritative for fast reopen.
                // If even the reserved maintenance space is exhausted, drop the logical entry
                // and checkpoint rather than failing terrain streaming. A later index loss may
                // rediscover a CRC-valid record, but typed verification rejects a bad object and
                // ordinary LRU eviction may harmlessly rediscover an old valid cache object.
                removeEntry(hash, expected);
                checkpoint();
                return;
            }
        }
        removeEntry(hash, expected);
    }

    private void replaceEntry(Hash256 hash, Entry next) {
        Entry previous = this.index.put(hash, next);
        if (previous != null) {
            this.storedBytes -= previous.compressedLength;
            Pack oldPack = this.packs.get(previous.packId);
            if (oldPack != null) oldPack.removeLive(previous);
        }
        this.storedBytes = Math.addExact(this.storedBytes, next.compressedLength);
        Pack pack = this.packs.get(next.packId);
        if (pack == null) throw new IllegalStateException("cache entry references no pack");
        pack.addLive(next);
    }

    private void removeEntry(Hash256 hash) {
        Entry previous = this.index.remove(hash);
        if (previous != null) accountRemoval(previous);
    }

    private void removeEntry(Hash256 hash, Entry expected) {
        if (!this.index.remove(hash, expected)) return;
        accountRemoval(expected);
    }

    private void accountRemoval(Entry expected) {
        this.storedBytes -= expected.compressedLength;
        Pack pack = this.packs.get(expected.packId);
        if (pack != null) pack.removeLive(expected);
    }

    private void clearIndex() {
        this.index.clear();
        this.storedBytes = 0;
        for (Pack pack : this.packs.values()) {
            pack.liveBytes = 0;
            pack.liveRecords = 0;
        }
    }

    private EncodedObject readValidated(Hash256 expectedHash, Entry entry) throws IOException {
        Pack pack = this.packs.get(entry.packId);
        if (pack == null) throw new CorruptRecordException("cached object pack is unavailable");
        byte[] raw = new byte[RECORD_HEADER_BYTES];
        if (!readFully(pack.channel, ByteBuffer.wrap(raw), entry.recordOffset)) {
            throw new CorruptRecordException("cached object header is truncated");
        }
        RecordHeader header = decodeRecordHeader(raw);
        if (header.type != PUT || !header.hash.equals(expectedHash)
                || !entry.matches(header, pack.id, entry.recordOffset)
                || !entry.boundsWithin(pack.length, this.maxObjectBytes)) {
            throw new CorruptRecordException("cached object index and record disagree");
        }
        byte[] compressed = new byte[entry.compressedLength];
        if (!readFully(pack.channel, ByteBuffer.wrap(compressed),
                entry.recordOffset + RECORD_HEADER_BYTES)) {
            throw new CorruptRecordException("cached object payload is truncated");
        }
        try {
            return EncodedObject.takeOwnership(expectedHash, entry.kind, entry.dictionaryId,
                    entry.canonicalLength, entry.compressedChecksum,
                    compressed);
        } catch (IllegalArgumentException failure) {
            throw new CorruptRecordException(failure.getMessage());
        }
    }

    private void checkpointIfNeeded() throws IOException {
        if (this.uncheckpointedBytes >= CHECKPOINT_BYTES
                || this.uncheckpointedRecords >= CHECKPOINT_RECORDS) checkpoint();
    }

    private void checkpoint() throws IOException {
        if (this.closed) return;
        for (Pack pack : this.packs.values()) pack.channel.force(false);
        byte[] bytes = encodeIndex();
        Path temporary = this.indexPath.resolveSibling(this.indexPath.getFileName() + ".tmp");
        try (FileChannel output = FileChannel.open(temporary, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            writeFully(output, ByteBuffer.wrap(bytes), 0);
            output.force(true);
        }
        atomicReplace(temporary, this.indexPath);
        syncDirectory(this.root);
        this.uncheckpointedBytes = 0;
        this.uncheckpointedRecords = 0;
    }

    private void compactIfNeeded() throws IOException {
        long physical = physicalBytes();
        long headroom = Math.max(this.maxPackBytes,
                Math.max(1, this.maxPhysicalBytes - this.maxLogicalPhysicalBytes) / 2);
        long trigger = saturatingAdd(this.maxLogicalPhysicalBytes, headroom);
        if (physical <= trigger) return;
        long livePhysical = this.packs.values().stream().mapToLong(pack -> pack.liveBytes).sum();
        long deadBytes = physical - (long) this.packs.size() * PACK_HEADER_BYTES - livePhysical;
        if (deadBytes <= 0 || deadBytes > Long.MAX_VALUE / 4 || deadBytes * 4 < physical) return;
        // Eviction and the newly appended object are already logically complete. Compaction is
        // cache hygiene, so an unavailable filesystem or insufficient double-write space must
        // not turn that successful put into an application-visible failure.
        try {
            compactAll(false);
        } catch (IOException ignored) {
            // A later cache open/rebuild or explicit maintenance pass can retry. Every written
            // record remains checksummed; retaining dead bytes is safe and bounded by the
            // logical-cache admission limits plus compaction headroom.
        }
    }

    /** Rewrites a complete epoch. RESET keeps a full scan correct across interrupted cleanup. */
    private void compactAll(boolean requireSpace) throws IOException {
        List<Map.Entry<Hash256, Entry>> live = this.index.entrySet().stream()
                .map(entry -> Map.entry(entry.getKey(), entry.getValue())).toList();
        // A pack containing a DELETE cannot be removed in isolation: a full rebuild would then
        // see the preceding PUT again. Epoch compaction is the only deletion path. It starts a
        // RESET record in a new pack, copies live objects, checkpoints that new epoch, then
        // removes every preceding pack as one group. If power fails after RESET but before the
        // checkpoint, a full scan retains only copied entries. That is deliberately safe here:
        // this is a disposable client cache, never an authority for an active world root.
        if (this.packs.size() >= MAX_PACK_FILES) {
            throw new IOException("cache compaction cannot allocate a fresh epoch pack");
        }
        long rewriteBytes = RECORD_HEADER_BYTES;
        for (Map.Entry<Hash256, Entry> entry : live) {
            rewriteBytes = saturatingAdd(rewriteBytes, entry.getValue().physicalBytes());
        }
        rewriteBytes = saturatingAdd(rewriteBytes,
                saturatingMultiply(live.size() + 1L, PACK_HEADER_BYTES));
        long physicalBeforeRewrite = physicalBytes();
        if (physicalBeforeRewrite > this.maxRecoveryPhysicalBytes
                || rewriteBytes > this.maxRecoveryPhysicalBytes - physicalBeforeRewrite) {
            if (requireSpace) {
                throw new IOException("cache rewrite would exceed its recovery byte bound");
            }
            return;
        }
        // The old epoch remains authoritative until the new epoch checkpoint is durable. Do not
        // begin a rewrite when the filesystem cannot hold both. Deferring compaction retains
        // extra disposable cache bytes, whereas an ENOSPC half-rewrite must never affect the
        // active root or terrain coverage.
        long usable;
        try {
            usable = Files.getFileStore(this.root).getUsableSpace();
        } catch (IOException failure) {
            if (requireSpace) throw failure;
            return;
        }
        if (usable < rewriteBytes) {
            if (requireSpace) {
                throw new IOException("insufficient space for an atomic cache epoch rewrite");
            }
            return;
        }
        this.epochRewrite = true;
        long resetPack;
        try {
            rotate();
            resetPack = this.activePack;
            appendReset();
            for (Map.Entry<Hash256, Entry> entry : live) {
                EncodedObject object;
                try {
                    object = readValidated(entry.getKey(), entry.getValue());
                } catch (CorruptRecordException failure) {
                    removeEntry(entry.getKey(), entry.getValue());
                    continue;
                }
                try {
                    appendPut(object);
                } finally {
                    object.close();
                }
            }
            checkpoint();
        } finally {
            this.epochRewrite = false;
        }
        List<Pack> obsolete = this.packs.headMap(resetPack, false).values().stream().toList();
        for (Pack pack : obsolete) removeObsoletePack(pack);
        checkpoint();
    }

    private void removeObsoletePack(Pack pack) throws IOException {
        if (pack.id == this.activePack) {
            throw new IllegalStateException("cannot remove the active cache pack");
        }
        for (Entry entry : this.index.values()) {
            if (entry.packId == pack.id) {
                throw new IllegalStateException("cannot remove a referenced cache pack");
            }
        }
        pack.channel.close();
        Files.deleteIfExists(pack.path);
        this.packs.remove(pack.id);
        syncDirectory(this.packsDirectory);
    }

    private byte[] encodeIndex() throws IOException {
        long size = INDEX_HEADER_BYTES + (long) this.packs.size() * INDEX_PACK_BYTES
                + (long) this.index.size() * INDEX_ENTRY_BYTES + Integer.BYTES;
        if (size > Integer.MAX_VALUE) throw new IOException("cache index exceeds Java array bounds");
        ByteBuffer output = ByteBuffer.allocate((int) size).order(ByteOrder.LITTLE_ENDIAN);
        output.put(INDEX_MAGIC).putInt(this.packs.size()).putInt(this.index.size());
        for (Pack pack : this.packs.values()) output.putLong(pack.id).putLong(pack.length);
        List<Map.Entry<Hash256, Entry>> entries = new ArrayList<>(this.index.entrySet());
        entries.sort((left, right) -> compareHashes(left.getKey(), right.getKey()));
        for (Map.Entry<Hash256, Entry> indexed : entries) {
            Entry entry = indexed.getValue();
            output.put(indexed.getKey().toBytes()).putLong(entry.packId)
                    .putLong(entry.recordOffset).putInt(entry.canonicalLength)
                    .putInt(entry.compressedLength).putInt(entry.compressedChecksum)
                    .putInt(entry.dictionaryId).put((byte) entry.kind.wireId());
        }
        int checksum = checksum(output.array(), 0, output.position());
        output.putInt(checksum);
        return output.array();
    }

    private Checkpoint readIndex(Path path) throws IOException {
        long maximum = INDEX_HEADER_BYTES + (long) MAX_PACK_FILES * INDEX_PACK_BYTES
                + (long) this.maxObjects * INDEX_ENTRY_BYTES + Integer.BYTES;
        long size = Files.size(path);
        if (size < INDEX_HEADER_BYTES + Integer.BYTES || size > maximum
                || size > Integer.MAX_VALUE) {
            throw new CorruptRecordException("cache index size is outside bounds");
        }
        byte[] bytes = Files.readAllBytes(path);
        int expectedChecksum = ByteBuffer.wrap(bytes, bytes.length - 4, 4)
                .order(ByteOrder.LITTLE_ENDIAN).getInt();
        if (checksum(bytes, 0, bytes.length - 4) != expectedChecksum) {
            throw new CorruptRecordException("cache index checksum mismatch");
        }
        ByteBuffer input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        byte[] magic = new byte[8];
        input.get(magic);
        long rawPacks = Integer.toUnsignedLong(input.getInt());
        long rawEntries = Integer.toUnsignedLong(input.getInt());
        long expected = INDEX_HEADER_BYTES + rawPacks * INDEX_PACK_BYTES
                + rawEntries * INDEX_ENTRY_BYTES + Integer.BYTES;
        if (!java.util.Arrays.equals(magic, INDEX_MAGIC) || rawPacks > MAX_PACK_FILES
                || rawEntries > this.maxObjects || expected != bytes.length) {
            throw new CorruptRecordException("invalid cache index header or counts");
        }
        TreeMap<Long, Long> lengths = new TreeMap<>();
        for (int index = 0; index < rawPacks; index++) {
            long id = input.getLong();
            long length = input.getLong();
            if (id < 0 || length < PACK_HEADER_BYTES || lengths.put(id, length) != null) {
                throw new CorruptRecordException("duplicate or invalid indexed pack");
            }
        }
        LinkedHashMap<Hash256, Entry> entries = new LinkedHashMap<>();
        Hash256 previous = null;
        for (int index = 0; index < rawEntries; index++) {
            byte[] hashBytes = new byte[32];
            input.get(hashBytes);
            Hash256 hash;
            try {
                hash = Hash256.fromBytes(hashBytes);
            } catch (IllegalArgumentException failure) {
                throw new CorruptRecordException(failure.getMessage());
            }
            long packId = input.getLong();
            long recordOffset = input.getLong();
            long canonical = Integer.toUnsignedLong(input.getInt());
            long compressed = Integer.toUnsignedLong(input.getInt());
            int crc = input.getInt();
            int dictionary = input.getInt();
            ObjectKind kind = objectKind(Byte.toUnsignedInt(input.get()));
            if (previous != null && compareHashes(previous, hash) >= 0
                    || canonical > WireMessage.MAX_CANONICAL_OBJECT_BYTES
                    || compressed > this.maxObjectBytes) {
                throw new CorruptRecordException("noncanonical or oversized cache index entry");
            }
            Entry entry = new Entry(packId, recordOffset, kind, dictionary,
                    (int) canonical, (int) compressed, crc);
            if (!lengths.containsKey(packId) || entries.put(hash, entry) != null) {
                throw new CorruptRecordException("cache index references a missing pack");
            }
            previous = hash;
        }
        if (input.position() != bytes.length - 4) {
            throw new CorruptRecordException("trailing cache index bytes");
        }
        return new Checkpoint(lengths, entries);
    }

    private Pack openPack(Path path, long expectedId) throws IOException {
        if (Files.isSymbolicLink(path)) throw new CorruptRecordException("cache pack is a symlink");
        FileChannel channel = FileChannel.open(path, StandardOpenOption.READ,
                StandardOpenOption.WRITE);
        boolean success = false;
        try {
            long length = channel.size();
            if (length < PACK_HEADER_BYTES) throw new CorruptRecordException("truncated pack header");
            byte[] header = new byte[PACK_HEADER_BYTES];
            if (!readFully(channel, ByteBuffer.wrap(header), 0)
                    || decodePackHeader(header) != expectedId) {
                throw new CorruptRecordException("pack header and filename disagree");
            }
            Pack pack = new Pack(expectedId, path, channel, length);
            success = true;
            return pack;
        } finally {
            if (!success) channel.close();
        }
    }

    private void removeStartupDebris() throws IOException {
        for (Path path : List.of(this.indexPath.resolveSibling(this.indexPath.getFileName() + ".tmp"),
                this.root.resolve("objects.idx.compact"))) {
            Files.deleteIfExists(path);
        }
    }

    private void closePacks() {
        for (Pack pack : this.packs.values()) {
            try {
                pack.channel.close();
            } catch (IOException ignored) {}
        }
        this.packs.clear();
    }

    private void truncate(Pack pack, long length) throws IOException {
        pack.channel.truncate(length);
        pack.channel.force(true);
        pack.length = length;
    }

    private Path packPath(long id) {
        return this.packsDirectory.resolve(String.format("pack-%016x.vxp", id));
    }

    private static Long parsePackName(Path path) {
        String name = path.getFileName().toString();
        if (!name.startsWith("pack-") || !name.endsWith(".vxp") || name.length() != 25) return null;
        try {
            long id = Long.parseUnsignedLong(name.substring(5, 21), 16);
            return id < 0 ? null : id;
        } catch (NumberFormatException failure) {
            return null;
        }
    }

    private static byte[] encodePackHeader(long id) {
        ByteBuffer output = ByteBuffer.allocate(PACK_HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        output.put(PACK_MAGIC).putLong(id);
        output.putInt(checksum(output.array(), 0, 16));
        return output.array();
    }

    private static long decodePackHeader(byte[] bytes) throws CorruptRecordException {
        if (bytes.length != PACK_HEADER_BYTES
                || checksum(bytes, 0, 16) != ByteBuffer.wrap(bytes, 16, 4)
                .order(ByteOrder.LITTLE_ENDIAN).getInt()) {
            throw new CorruptRecordException("invalid pack checksum");
        }
        ByteBuffer input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        byte[] magic = new byte[8];
        input.get(magic);
        long id = input.getLong();
        if (!java.util.Arrays.equals(magic, PACK_MAGIC) || id < 0) {
            throw new CorruptRecordException("invalid pack header");
        }
        return id;
    }

    private static byte[] encodePutHeader(EncodedObject object) {
        ByteBuffer output = recordPrefix(PUT, object.hash().toBytes());
        output.put(9, (byte) object.kind().wireId());
        output.putInt(12, object.dictionaryId());
        output.putInt(16, object.canonicalLength());
        output.putInt(20, object.compressedLength());
        output.putInt(24, object.compressedChecksum());
        output.putInt(60, checksum(output.array(), 0, 60));
        return output.array();
    }

    private static byte[] encodeDeleteHeader(Hash256 hash) {
        ByteBuffer output = recordPrefix(DELETE, hash.toBytes());
        output.putInt(60, checksum(output.array(), 0, 60));
        return output.array();
    }

    private static ByteBuffer recordPrefix(byte type, byte[] hash) {
        if (hash.length != 32) throw new IllegalArgumentException("invalid cached object hash length");
        ByteBuffer output = ByteBuffer.allocate(RECORD_HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        output.put(RECORD_MAGIC).put(type);
        output.position(28);
        output.put(hash);
        return output;
    }

    private void appendReset() throws IOException {
        Pack pack = this.packs.get(this.activePack);
        long offset = pack.length;
        writeFully(pack.channel, ByteBuffer.wrap(encodeResetHeader()), offset);
        pack.channel.truncate(offset + RECORD_HEADER_BYTES);
        pack.length = offset + RECORD_HEADER_BYTES;
        this.uncheckpointedBytes = Math.addExact(this.uncheckpointedBytes, RECORD_HEADER_BYTES);
        this.uncheckpointedRecords++;
    }

    private static byte[] encodeResetHeader() {
        ByteBuffer output = recordPrefix(RESET, new byte[32]);
        output.putInt(60, checksum(output.array(), 0, 60));
        return output.array();
    }

    private RecordHeader decodeRecordHeader(byte[] bytes) throws CorruptRecordException {
        if (bytes.length != RECORD_HEADER_BYTES
                || checksum(bytes, 0, 60) != ByteBuffer.wrap(bytes, 60, 4)
                .order(ByteOrder.LITTLE_ENDIAN).getInt()) {
            throw new CorruptRecordException("invalid cache record checksum");
        }
        ByteBuffer input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        byte[] magic = new byte[8];
        input.get(magic);
        byte type = input.get();
        int kindId = Byte.toUnsignedInt(input.get());
        int reserved0 = Byte.toUnsignedInt(input.get());
        int reserved1 = Byte.toUnsignedInt(input.get());
        int dictionary = input.getInt();
        long canonical = Integer.toUnsignedLong(input.getInt());
        long compressed = Integer.toUnsignedLong(input.getInt());
        int payloadCrc = input.getInt();
        byte[] hashBytes = new byte[32];
        input.get(hashBytes);
        if (!java.util.Arrays.equals(magic, RECORD_MAGIC) || reserved0 != 0 || reserved1 != 0
                || type != PUT && type != DELETE && type != RESET) {
            throw new CorruptRecordException("invalid cache record envelope");
        }
        if (type == DELETE || type == RESET) {
            if (kindId != 0 || dictionary != 0
                    || canonical != 0 || compressed != 0 || payloadCrc != 0) {
                throw new CorruptRecordException("invalid cache control record");
            }
            if (type == RESET && !allZero(hashBytes)) {
                throw new CorruptRecordException("invalid cache reset hash");
            }
            Hash256 hash = null;
            if (type == DELETE) {
                try {
                    hash = Hash256.fromBytes(hashBytes);
                } catch (IllegalArgumentException failure) {
                    throw new CorruptRecordException(failure.getMessage());
                }
            }
            return new RecordHeader(type, hash, null, 0, 0, 0, 0);
        }
        Hash256 hash;
        try {
            hash = Hash256.fromBytes(hashBytes);
        } catch (IllegalArgumentException failure) {
            throw new CorruptRecordException(failure.getMessage());
        }
        if (canonical > WireMessage.MAX_CANONICAL_OBJECT_BYTES
                || compressed > this.maxObjectBytes) {
            throw new CorruptRecordException("oversized cache record");
        }
        return new RecordHeader(type, hash, objectKind(kindId),
                dictionary, (int) canonical, (int) compressed, payloadCrc);
    }

    private static ObjectKind objectKind(int wireId) throws CorruptRecordException {
        for (ObjectKind kind : ObjectKind.values()) if (kind.wireId() == wireId) return kind;
        throw new CorruptRecordException("unknown cached object kind " + wireId);
    }

    private static long recordBytes(int compressedLength) {
        return RECORD_HEADER_BYTES + (long) compressedLength + padding(compressedLength);
    }

    private static int padding(int compressedLength) {
        return (8 - ((RECORD_HEADER_BYTES + compressedLength) & 7)) & 7;
    }

    private static void writePadding(FileChannel channel, long offset, int count) throws IOException {
        if (count != 0) writeFully(channel, ByteBuffer.wrap(new byte[count]), offset);
    }

    private static boolean allZero(byte[] bytes) {
        for (byte value : bytes) if (value != 0) return false;
        return true;
    }

    private static int compareHashes(Hash256 left, Hash256 right) {
        byte[] a = left.toBytes();
        byte[] b = right.toBytes();
        for (int index = 0; index < a.length; index++) {
            int compared = Integer.compare(Byte.toUnsignedInt(a[index]), Byte.toUnsignedInt(b[index]));
            if (compared != 0) return compared;
        }
        return 0;
    }

    private static int checksum(byte[] bytes, int offset, int length) {
        CRC32C crc = new CRC32C();
        crc.update(bytes, offset, length);
        return (int) crc.getValue();
    }

    private static boolean readFully(FileChannel channel, ByteBuffer output, long offset)
            throws IOException {
        int start = output.position();
        while (output.hasRemaining()) {
            int read = channel.read(output, offset + output.position() - start);
            if (read < 0) return false;
            if (read == 0) Thread.onSpinWait();
        }
        return true;
    }

    private static void writeFully(FileChannel channel, ByteBuffer input, long offset)
            throws IOException {
        int start = input.position();
        while (input.hasRemaining()) {
            int written = channel.write(input, offset + input.position() - start);
            if (written == 0) Thread.onSpinWait();
        }
    }

    private static long saturatingAdd(long left, long right) {
        if (left < 0 || right < 0) return Long.MAX_VALUE;
        if (right > Long.MAX_VALUE - left) return Long.MAX_VALUE;
        return left + right;
    }

    private static long saturatingMultiply(long value, long factor) {
        if (value != 0 && factor > Long.MAX_VALUE / value) return Long.MAX_VALUE;
        return value * factor;
    }

    private static void atomicReplace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException failure) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void syncDirectory(Path directory) throws IOException {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (AccessDeniedException | UnsupportedOperationException ignored) {
            // Directory fsync is unavailable on some client platforms. Records remain checksummed
            // and the index remains disposable, so startup safely scans whatever entries survived.
        }
    }

    private void ensureOpen() {
        if (this.closed) throw new IllegalStateException("packed cache is closed");
    }

    private record Entry(long packId, long recordOffset, ObjectKind kind, int dictionaryId,
                         int canonicalLength, int compressedLength, int compressedChecksum) {
        private long physicalBytes() { return recordBytes(this.compressedLength); }

        private boolean boundsWithin(long packLength, int maxObjectBytes) {
            return this.packId >= 0 && this.recordOffset >= PACK_HEADER_BYTES
                    && ((this.recordOffset - PACK_HEADER_BYTES) & 7L) == 0L
                    && this.canonicalLength >= 0
                    && this.canonicalLength <= WireMessage.MAX_CANONICAL_OBJECT_BYTES
                    && this.compressedLength >= 0 && this.compressedLength <= maxObjectBytes
                    && this.recordOffset <= packLength - this.physicalBytes();
        }

        private boolean matches(RecordHeader header, long actualPack, long actualOffset) {
            return this.packId == actualPack && this.recordOffset == actualOffset
                    && this.kind == header.kind
                    && this.dictionaryId == header.dictionaryId
                    && this.canonicalLength == header.canonicalLength
                    && this.compressedLength == header.compressedLength
                    && this.compressedChecksum == header.compressedChecksum;
        }
    }

    private record RecordHeader(byte type, Hash256 hash, ObjectKind kind, int dictionaryId,
                                int canonicalLength, int compressedLength,
                                int compressedChecksum) {
        private Entry entry(long packId, long offset) {
            return new Entry(packId, offset, this.kind, this.dictionaryId,
                    this.canonicalLength, this.compressedLength,
                    this.compressedChecksum);
        }
    }

    private static final class Pack {
        private final long id;
        private final Path path;
        private final FileChannel channel;
        private long length;
        private long liveBytes;
        private int liveRecords;

        private Pack(long id, Path path, FileChannel channel, long length) {
            this.id = id;
            this.path = path;
            this.channel = channel;
            this.length = length;
        }

        private void addLive(Entry entry) {
            this.liveBytes = Math.addExact(this.liveBytes, entry.physicalBytes());
            this.liveRecords++;
        }

        private void removeLive(Entry entry) {
            this.liveBytes -= entry.physicalBytes();
            this.liveRecords--;
            if (this.liveBytes < 0 || this.liveRecords < 0) {
                throw new IllegalStateException("cache pack live accounting underflow");
            }
        }

    }

    private record Checkpoint(TreeMap<Long, Long> lengths,
                              LinkedHashMap<Hash256, Entry> entries) {}
    private record ScanResult(int corruptRecords, boolean torn) {}
    private record OpenResult(boolean indexRebuilt, boolean tailsScanned,
                              int corruptRecords, int tornPacks) {}

    private static final class CorruptRecordException extends IOException {
        private CorruptRecordException(String message) { super(message); }
    }

    private static final class PhysicalLimitException extends IOException {
        private PhysicalLimitException() { super("cache reached its physical byte bound"); }
    }
}
