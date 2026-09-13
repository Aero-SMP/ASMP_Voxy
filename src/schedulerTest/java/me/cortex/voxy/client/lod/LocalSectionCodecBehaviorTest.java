package me.cortex.voxy.client.lod;

import me.cortex.voxy.client.core.model.CatalogMapper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import static me.cortex.voxy.client.lod.CacheStartupBehaviorTest.check;

/** Exercises the actual streaming encoder/decoder. Large records go through files, not byte arrays. */
public final class LocalSectionCodecBehaviorTest {
    public static void main(String[] args) throws Exception {
        run();
        if (args.length != 0) sample(java.nio.file.Path.of(args[0]));
    }
    static void run() throws Exception {
        for (boolean duplicateBlock : new boolean[]{false, true}) {
            var block = new CatalogCodec.Block("test:block", 15, true);
            try {
                new CatalogCodec.Catalog(1, 1, 1, duplicateBlock ? List.of(block, block) : List.of(block),
                        duplicateBlock ? List.of("test:biome") : List.of("test:biome", "test:biome"));
                throw new AssertionError("duplicate source catalog names accepted");
            } catch (IllegalArgumentException expected) { }
        }
        check(LocalSectionCodec.MAX_COMPRESSED_BYTES + 256 < CompletedSectionJournal.MAX_BYTES,
                "maximum local record cannot fit existing regional ceiling");
        check(CompletedSectionJournal.MAX_BYTES < RegionalDiskBudget.LIMIT, "region exceeds total disk limit");
        var root = Files.createTempDirectory(java.nio.file.Path.of("project_audit"), "voxy-local-codec-");
        try (var codec = new LocalSectionCodec()) {
            for (int count : new int[]{1, 3, 257, 32768, 16368}) {
                boolean large = count == 16368;
                var random = new Random(177);
                var blocks = new ArrayList<CatalogCodec.Block>(count);
                long nameBytes = 0;
                for (int i = 0; i < count; i++) {
                    String prefix = "test:b" + i + "_";
                    var name = new StringBuilder(prefix);
                    if (large) while (name.length() < 4096) name.append((char) ('a' + random.nextInt(26)));
                    blocks.add(new CatalogCodec.Block(name.toString(), 15, true));
                    nameBytes += name.length() + 4;
                }
                check(nameBytes + 40 + 2 + "test:biome".length() <= CatalogCodec.MAX_BYTES,
                        "fixture exceeds accepted catalog extent");
                var catalog = new CatalogCodec.Catalog(1, 1, 1, blocks, List.of("test:biome"));
                byte[] wire = wire(count);
                var expected = RegionalProtocol.Fingerprint.read(ByteBuffer.wrap(Blake3.hash(wire)).order(ByteOrder.LITTLE_ENDIAN));
                try (var wireCodec = new RegionalSectionCodec()) {
                    wireCodec.decode(0, 0x81, wire, expected, new RegionalSectionCodec.Mappings(
                            java.util.stream.IntStream.rangeClosed(1, count).toArray(), new int[]{7}));
                }
                var path = root.resolve(count + ".zst");
                long raw, compressed, steps = 0;
                long start = System.nanoTime();
                try (var encoder = codec.encode(wire, catalog); var output = Files.newOutputStream(path)) {
                    while (!encoder.step(output)) steps++;
                    raw = encoder.canonicalBytes(); compressed = encoder.compressedBytes();
                }
                long encodeNs = System.nanoTime() - start;
                check(compressed == Files.size(path), "output accounting mismatch");
                check(!large || compressed > RegionalProtocol.MAX_SECTION_BYTES,
                        "large local fixture did not cross wire ceiling");
                start = System.nanoTime();
                try (var input = Files.newInputStream(path)) {
                    int[] resolved = {0};
                    var section = codec.decode(0, 0x81, input, compressed, raw, (name, biome) -> {
                        resolved[0]++;
                        return biome ? 7 : Integer.parseInt(name.substring(6, name.indexOf('_'))) + 1;
                    });
                    check(resolved[0] == count + 1, "names resolved per cell instead of per table");
                    for (int i = 0; i < 32768; i++) check(section.cells()[i] == CatalogMapper.composeMappingId(
                            (byte) (i % count), i % count + 1, 7), "local cell/light/index order changed");
                    check(section.childMask() == 0x81 && section.usedBlocks().length == count, "section metadata changed");
                }
                System.out.println("LOCAL_CODEC palette=" + count + " raw=" + raw + " compressed=" + compressed
                        + " steps=" + (steps + 1) + " encodeNs=" + encodeNs + " decodeNs=" + (System.nanoTime() - start)
                        + " nativeContexts=" + codec.nativeContextBytes() + " nativeIo=" + 2 * LocalSectionCodec.CHUNK_BYTES
                        + " heapIo=" + LocalSectionCodec.CHUNK_BYTES);
                if (large) oversizedJournal(root.resolve("large-journal"), catalog, wire, expected);
                // Exact extent, truncation, and checksum validation must finish before returning cells.
                if (!large) {
                    byte[] bad = Files.readAllBytes(path);
                    if (count == 32768) {
                        check((bad[4] & 32) == 0, "window fixture unexpectedly single-segment");
                        byte[] oversizedWindow = bad.clone();
                        oversizedWindow[5] = (byte) ((27 - 10) << 3); // 128 MiB window, unchanged content.
                        long before = codec.nativeContextBytes();
                        reject(codec, oversizedWindow, raw);
                        check(codec.nativeContextBytes() == before, "oversized window allocated decoder history");
                    }
                    bad[bad.length - 1] ^= 1;
                    reject(codec, bad, raw);
                    reject(codec, java.util.Arrays.copyOf(bad, bad.length - 1), raw);
                    try (var input = Files.newInputStream(path)) {
                        try { codec.decode(0, 0, input, compressed, raw - 1, (n, b) -> 1);
                            throw new AssertionError("wrong expanded extent accepted"); }
                        catch (IOException expectedFailure) { }
                    }
                }
            }
        malformedTables(codec);
            check(!org.lwjgl.util.zstd.Zstd.ZSTD_versionString().isBlank(), "missing bundled Zstd version");
            System.out.println("LOCAL_CODEC zstd=" + org.lwjgl.util.zstd.Zstd.ZSTD_versionString()
                    + " rawBound=" + LocalSectionCodec.MAX_CANONICAL_BYTES
                    + " compressedBound=" + LocalSectionCodec.MAX_COMPRESSED_BYTES);
            nativeEstimates(codec.nativeContextBytes());
        } finally { CacheStartupBehaviorTest.cleanup(root); }
        ownershipReset();
        System.out.println("local section streaming codec tests passed");
    }

    private static void oversizedJournal(java.nio.file.Path root, CatalogCodec.Catalog catalog, byte[] wire,
                                         RegionalProtocol.Fingerprint source) throws Exception {
        byte[] network = CacheStartupBehaviorTest.compress(wire);
        int rotations = 0;
        try (var metadata = new RegionalMetadataStore(root);
             var cache = new CompletedSectionCache(metadata, CacheStartupBehaviorTest.WORLD, CacheStartupBehaviorTest.DIMENSION);
             var codec = new LocalSectionCodec()) {
            CacheStartupBehaviorTest.awaitInventory(metadata.budget);
            LocalSection latest = null;
            for (int generation = 1; generation <= 7; generation++) {
                // Distinct source-catalog identities with unchanged names and numeric content.
                latest = new LocalSection(0, LocalSection.DATA, 0, network.length, wire.length,
                        RegionalProtocol.crc32c(network), source, new RegionalProtocol.Hash32(generation, 2, 3, 4));
                long before = RegionalDiskBudget.size(cache.path(0));
                for (;;) {
                    try (var save = cache.begin(latest, codec, wire, catalog, () -> true)) {
                        while (!save.step()) check(metadata.budget.bytes <= RegionalDiskBudget.LIMIT,
                                "streamed uncommitted record exceeded total disk allowance");
                        break;
                    } catch (CompletedSectionJournal.RotationRequired full) {
                        check(RegionalDiskBudget.size(cache.path(0)) == before, "full shard lost its committed predecessor");
                        check(cache.rotate(0), "unleased full shard could not rotate"); rotations++;
                    }
                }
                check(Files.size(cache.path(0)) <= CompletedSectionJournal.MAX_BYTES,
                        "oversized valid section crossed regional safety ceiling");
            }
            check(rotations == 1, "fixture did not exercise the real full-region boundary");
            var decoded = cache.get(latest, codec, (name, biome) -> biome ? 7
                    : Integer.parseInt(name.substring(6, name.indexOf('_'))) + 1);
            check(decoded != null && decoded.usedBlocks().length == 16368,
                    "valid record over 4 MiB was skipped or lost after rotation");
            check(metadata.budget.bytes == Files.size(cache.path(0)) + Files.size(root.resolve("cache-format")),
                    "oversized append/rotation disk accounting mismatch");
            System.out.println("LOCAL_JOURNAL largeRecordRotations=" + rotations + " diskBytes=" + metadata.budget.bytes);
        }
    }

    private static void nativeEstimates(long measured) {
        long params = org.lwjgl.util.zstd.ZstdX.ZSTD_createCCtxParams();
        check(params != 0, "native parameter allocation failed");
        try {
            for (int[] parameter : new int[][]{
                    {org.lwjgl.util.zstd.Zstd.ZSTD_c_compressionLevel, 3},
                    {org.lwjgl.util.zstd.Zstd.ZSTD_c_windowLog, LocalSectionCodec.WINDOW_LOG},
                    {org.lwjgl.util.zstd.Zstd.ZSTD_c_nbWorkers, 0},
                    {org.lwjgl.util.zstd.Zstd.ZSTD_c_checksumFlag, 1}})
                check(!org.lwjgl.util.zstd.Zstd.ZSTD_isError(org.lwjgl.util.zstd.ZstdX.ZSTD_CCtxParams_setParameter(
                        params, parameter[0], parameter[1])), "native codec parameter rejected");
            long encoder = org.lwjgl.util.zstd.ZstdX.ZSTD_estimateCStreamSize_usingCCtxParams(params);
            long decoder = org.lwjgl.util.zstd.ZstdX.ZSTD_estimateDStreamSize(1L << LocalSectionCodec.WINDOW_LOG);
            check(!org.lwjgl.util.zstd.Zstd.ZSTD_isError(encoder) && !org.lwjgl.util.zstd.Zstd.ZSTD_isError(decoder),
                    "native codec estimate failed");
            check(measured <= encoder + decoder, "measured contexts exceeded native sizing estimate");
            System.out.println("LOCAL_NATIVE estimatedEncoder=" + encoder + " estimatedDecoder=" + decoder
                    + " fourteenReadersOneWriter=" + (14 * decoder + encoder)
                    + " excludesIoAndAllocatorTransientOverlap=true");
        } finally { org.lwjgl.util.zstd.ZstdX.ZSTD_freeCCtxParams(params); }
    }

    private static void ownershipReset() throws Exception {
        var root = Files.createTempDirectory(java.nio.file.Path.of("project_audit"), "voxy-format-reset-");
        try {
            var cache = root.resolve("regional");
            var old = cache.resolve("a".repeat(64)).resolve("b".repeat(64));
            Files.createDirectories(old.resolve("completed-v1"));
            var legacy = old.resolve("r.0.0.vxcache");
            var completed = old.resolve("completed-v1/r.0.0.vxlocal");
            Files.writeString(legacy, "VXYSEC\0\0");
            Files.writeString(completed, "VXYLOC1\0");
            var settings = old.resolve("settings.json"); Files.writeString(settings, "preserve");
            // Unknown future data is detected by preflight, before deleting any recognized file.
            var future = old.resolve("completed-v1/r.1.0.vxlocal"); Files.writeString(future, "VXYLOC2\0");
            rejectOwnership(cache);
            check(Files.exists(legacy) && Files.exists(completed), "preflight deleted before unknown-format rejection");
            Files.delete(future);
            // Simulate interruption after the durable reset intent but before deletion.
            try { LocalCacheOwnership.open(cache, message -> { throw new IllegalStateException("injected interruption"); });
                throw new AssertionError("reset interruption did not propagate"); }
            catch (IllegalStateException expected) { }
            check(Files.exists(cache.resolve("cache-reset")) && !Files.exists(cache.resolve("cache-format")),
                    "interrupted reset was not recognizable");
            try (var owned = LocalCacheOwnership.open(cache, message -> {})) {
                check(!Files.exists(legacy) && !Files.exists(completed), "obsolete files survived accepted reset");
                check(Files.readString(settings).equals("preserve"), "reset deleted unrelated settings");
                rejectOwnership(cache);
            }
            // A compatible ordinary reopen is not another cold reset.
            var current = old.resolve("r.0.0.names"); Files.writeString(current, "current fixture");
            try (var owned = LocalCacheOwnership.open(cache, message -> { throw new AssertionError("compatible cache reset"); })) {
                check(Files.exists(current), "compatible cache was deleted");
            }
            Files.writeString(cache.resolve("cache-format"), "VXY-NAMES-2\n");
            rejectOwnership(cache);
            check(Files.exists(current), "future format refusal deleted data");
            var linked = root.resolve("linked"); Files.createSymbolicLink(linked, cache);
            rejectOwnership(linked);
            Files.delete(linked);
            var unsafe = root.resolve("unsafe"); Files.createDirectories(unsafe);
            Files.createSymbolicLink(unsafe.resolve("escape"), old);
            rejectOwnership(unsafe);
            check(Files.readString(settings).equals("preserve"), "symlink rejection modified destination");
            Files.delete(unsafe.resolve("escape"));
        } finally { CacheStartupBehaviorTest.cleanup(root); }
    }
    private static void rejectOwnership(java.nio.file.Path root) throws Exception {
        try (var ignored = LocalCacheOwnership.open(root, message -> {})) {
            throw new AssertionError("unsafe/concurrent/unknown cache ownership accepted");
        } catch (IOException expected) { }
    }
    private static void reject(LocalSectionCodec codec, byte[] compressed, long raw) throws Exception {
        try { codec.decode(0, 0, new ByteArrayInputStream(compressed), compressed.length, raw, (n, b) -> 1); }
        catch (IOException expected) { return; }
        throw new AssertionError("damaged local payload accepted");
    }
    private static void malformedTables(LocalSectionCodec codec) throws Exception {
        // one palette entry, one block name, one biome name, then references and original light
        byte[] valid = {1,0,1,0,1,0, 1,0,'a', 1,0,'b', 0,0,0,0,(byte) 240};
        for (int offset : new int[]{0, 2, 4, 6, 12, 14}) {
            byte[] bad = valid.clone(); bad[offset] = (byte) 255;
            reject(codec, compressSmall(bad), bad.length);
        }
        byte[] badUtf8 = valid.clone(); badUtf8[8] = (byte) 0xff;
        reject(codec, compressSmall(badUtf8), badUtf8.length);
        byte[] trailing = java.util.Arrays.copyOf(valid, valid.length + 1);
        reject(codec, compressSmall(trailing), trailing.length);
    }
    private static byte[] compressSmall(byte[] raw) throws IOException {
        var input = org.lwjgl.system.MemoryUtil.memAlloc(raw.length);
        var output = org.lwjgl.system.MemoryUtil.memAlloc((int) org.lwjgl.util.zstd.Zstd.ZSTD_compressBound(raw.length));
        try {
            input.put(raw).flip();
            long result = org.lwjgl.util.zstd.Zstd.ZSTD_compress(output, input, 3);
            if (org.lwjgl.util.zstd.Zstd.ZSTD_isError(result)) throw new IOException("fixture compression failed");
            byte[] compressed = new byte[(int) result]; output.get(0, compressed); return compressed;
        } finally { org.lwjgl.system.MemoryUtil.memFree(input); org.lwjgl.system.MemoryUtil.memFree(output); }
    }
    private static byte[] wire(int count) {
        int bits = count == 1 ? 0 : 32 - Integer.numberOfLeadingZeros(count - 1);
        var bytes = ByteBuffer.allocate(2 + count * 9 + 32768 * bits / 8).order(ByteOrder.LITTLE_ENDIAN);
        bytes.putShort((short) count);
        for (int i = 0; i < count; i++) bytes.putInt(i).putInt(0).put((byte) i);
        int offset = bytes.position();
        for (int i = 0; i < 32768; i++) for (int b = 0; b < bits; b++) {
            int bit = i * bits + b, at = offset + bit / 8;
            bytes.put(at, (byte) (bytes.get(at) | ((i % count >>> b) & 1) << (bit % 8)));
        }
        return bytes.array();
    }

    /** Read-only saved Mod_Testing sample. CRC/hash-check original payloads before conversion. */
    private static void sample(java.nio.file.Path data) throws Exception {
        byte[] snapshot = Files.readAllBytes(data.resolve("catalog/catalog.a"));
        var registry = ByteBuffer.wrap(snapshot).order(ByteOrder.LITTLE_ENDIAN);
        check(registry.getInt(snapshot.length - 4) == RegionalProtocol.crc32c(
                java.util.Arrays.copyOf(snapshot, snapshot.length - 4)), "sample registry CRC");
        byte[] canonicalCatalog = java.util.Arrays.copyOf(snapshot, snapshot.length - 4);
        System.arraycopy("VXYCAT\0\0".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, canonicalCatalog, 0, 8);
        var catalog = CatalogCodec.decode(canonicalCatalog);
        var mappings = new RegionalSectionCodec.Mappings(
                java.util.stream.IntStream.range(0, catalog.blocks().size()).toArray(),
                java.util.stream.IntStream.range(0, catalog.biomes().size()).toArray());
        for (String dimension : List.of("minecraft_3aoverworld", "minecraft_3athe_5fend")) {
            long original = 0, local = 0, encodeNs = 0, raw = 0;
            int count = 0;
            try (var codec = new LocalSectionCodec(); var wireCodec = new RegionalSectionCodec();
                 var paths = Files.list(data.resolve("regional").resolve(dimension))) {
                for (var path : paths.filter(p -> p.toString().endsWith(".vxregion")).sorted().toList()) {
                    try (var file = new java.io.RandomAccessFile(path.toFile(), "r")) {
                        byte[] header = new byte[256]; file.readFully(header);
                        var h = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
                        check(h.getLong(40) == catalog.catalogId(), "sample region catalog identity mismatch");
                        check(h.getInt(252) == RegionalProtocol.crc32c(java.util.Arrays.copyOf(header, 252)),
                                "sample region header CRC");
                        int entries = h.getInt(28);
                        check(entries >= 0 && entries <= RegionalProtocol.MAX_INDEX_BYTES / 48, "sample directory bounds");
                        byte[] directory = new byte[entries * 48];
                        file.seek(h.getLong(112)); file.readFully(directory);
                        check(RegionalProtocol.crc32c(directory) == h.getInt(136), "sample directory CRC");
                        for (int i = 0; i < entries && count < 1000; i++) {
                            byte[] entry = java.util.Arrays.copyOfRange(directory, i * 48, (i + 1) * 48);
                            var e = ByteBuffer.wrap(entry).order(ByteOrder.LITTLE_ENDIAN);
                            if ((e.getShort(0) & 0x8001) != 0x8000) continue;
                            int length = e.getInt(16), canonicalLength = e.getInt(20);
                            check(length > 0 && length <= RegionalProtocol.MAX_SECTION_BYTES, "sample wire bounds");
                            byte[] payload = new byte[length]; file.seek(e.getLong(8)); file.readFully(payload);
                            check(RegionalProtocol.crc32c(payload) == e.getInt(24), "sample payload CRC");
                            byte[] wire = wireCodec.decompress(payload, canonicalLength);
                            e.position(28);
                            wireCodec.decode(0, entry[2] & 255, wire, RegionalProtocol.Fingerprint.read(e), mappings);
                            long start = System.nanoTime();
                            try (var encoder = codec.encode(wire, catalog)) {
                                while (!encoder.step(java.io.OutputStream.nullOutputStream())) { }
                                local += encoder.compressedBytes(); raw += encoder.canonicalBytes();
                            }
                            encodeNs += System.nanoTime() - start; original += length; count++;
                        }
                    }
                    if (count >= 1000) break;
                }
            }
            check(count > 0, "empty terrain sample " + dimension);
            System.out.println("LOCAL_SAMPLE dimension=" + dimension + " sections=" + count
                    + " wireBytes=" + original + " localBytes=" + local + " localRaw=" + raw + " encodeNs=" + encodeNs);
        }
    }
}
