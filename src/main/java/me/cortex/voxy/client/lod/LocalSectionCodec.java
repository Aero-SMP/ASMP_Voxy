package me.cortex.voxy.client.lod;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntLinkedOpenHashSet;
import me.cortex.voxy.client.core.model.CatalogMapper;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.zstd.ZSTDInBuffer;
import org.lwjgl.util.zstd.ZSTDOutBuffer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.lwjgl.util.zstd.Zstd.*;

/** One worker-owned streaming codec. Local name bytes are never materialized as a whole record.
 * Source fingerprints remain wire freshness tokens; callers authenticate local records separately.
 */
final class LocalSectionCodec implements AutoCloseable {
    static final int CELLS = 32768, MAX_NAME_BYTES = 4096, HEADER_BYTES = 6;
    static final int WINDOW_LOG = 18, CHUNK_BYTES = 128 * 1024;
    // Catalog names retain their u16 lengths and drop block flags. A subset cannot be larger
    // than its accepted source catalog minus the 40-byte catalog header.
    static final long MAX_CANONICAL_BYTES = Math.addExact(CatalogCodec.MAX_BYTES - 40L,
            HEADER_BYTES + 5L * CELLS + 15L * CELLS / 8);
    // No flush calls, no dictionaries, workers or target-block splitting. e_continue fills
    // 128 KiB blocks; each can fall back to raw + 3 bytes. Allow one final empty block,
    // the largest frame header (18) and checksum (4). Not the one-shot compressBound().
    static final long MAX_COMPRESSED_BYTES = compressedBound(MAX_CANONICAL_BYTES);

    @FunctionalInterface interface Names {
        /** Resolution may park the owning section worker, but never performs registry work there. */
        int resolve(String canonical, boolean biome) throws IOException;
    }

    private long encoder, decoder;
    private long decodedBytes;
    long decodedBytes() { return this.decodedBytes; }
    private boolean busy, closed;
    private Buffers buffers;

    static long compressedBound(long canonical) {
        if (canonical < 0 || canonical > MAX_CANONICAL_BYTES)
            throw new IllegalArgumentException("local canonical extent outside bounds");
        return Math.addExact(canonical, 22L + 3L * (1L + (canonical + CHUNK_BYTES - 1) / CHUNK_BYTES));
    }

    /** Takes the already validated canonical wire palette/index bytes, not expanded cells.
     * Source catalog ownership is shared; no catalog copy or decompression is performed here.
     */
    Encoder encode(byte[] canonical, CatalogCodec.Catalog source) throws IOException {
        claim();
        try {
            var input = new NamesInput(canonical, source);
            if (this.encoder == 0) this.encoder = ZSTD_createCCtx();
            if (this.encoder == 0) throw new IOException("no local Zstd encoder");
            check(ZSTD_CCtx_reset(this.encoder, ZSTD_reset_session_and_parameters));
            check(ZSTD_CCtx_setParameter(this.encoder, ZSTD_c_compressionLevel, 3));
            check(ZSTD_CCtx_setParameter(this.encoder, ZSTD_c_windowLog, WINDOW_LOG));
            check(ZSTD_CCtx_setParameter(this.encoder, ZSTD_c_nbWorkers, 0));
            check(ZSTD_CCtx_setParameter(this.encoder, ZSTD_c_checksumFlag, 1));
            check(ZSTD_CCtx_setPledgedSrcSize(this.encoder, input.length));
            return new Encoder(input);
        } catch (Throwable failure) { this.busy = false; throw failure; }
    }

    final class Encoder implements AutoCloseable {
        private final NamesInput source;
        private final Buffers buffers = LocalSectionCodec.this.buffers();
        private long written;
        private boolean ended, released;

        private Encoder(NamesInput source) { this.source = source; }
        long canonicalBytes() { return this.source.length; }
        long compressedBytes() { return this.written; }

        /** One bounded input/output step. The metadata owner may service discovery between steps.
         * The sink must reserve/charge each portion before writing it to the journal.
         */
        boolean step(OutputStream sink) throws IOException {
            if (this.released) throw new IOException("released local encoder");
            if (this.ended) return true;
            var b = this.buffers;
            if (b.in.pos() == b.in.size()) {
                int count = this.source.read(b.heap, 0, b.heap.length);
                b.input.clear();
                if (count > 0) b.input.put(b.heap, 0, count);
                b.input.flip(); b.in.set(b.input, 0);
            }
            b.output.clear(); b.out.set(b.output, 0);
            boolean eof = this.source.remaining() == 0;
            long result = ZSTD_compressStream2(LocalSectionCodec.this.encoder, b.out, b.in,
                    eof ? ZSTD_e_end : ZSTD_e_continue);
            check(result);
            int count = Math.toIntExact(b.out.pos());
            this.written = Math.addExact(this.written, count);
            if (this.written > compressedBound(this.source.length))
                throw new IOException("local streaming output exceeded derived bound");
            b.output.get(0, b.heap, 0, count);
            if (count != 0) sink.write(b.heap, 0, count);
            this.ended = eof && result == 0;
            return this.ended;
        }

        @Override public void close() {
            if (this.released) return;
            this.released = true;
            LocalSectionCodec.this.busy = false;
        }
    }

    RegionalSectionCodec.SectionData decode(long key, int children, InputStream compressed,
                                            long compressedBytes, long canonicalBytes,
                                            Names resolver) throws IOException {
        if (compressedBytes < 1 || compressedBytes > MAX_COMPRESSED_BYTES
                || canonicalBytes < HEADER_BYTES || canonicalBytes > MAX_CANONICAL_BYTES
                || compressedBytes > compressedBound(canonicalBytes) || (children & ~255) != 0)
            throw new IOException("invalid local section extent");
        claim();
        try {
            if (this.decoder == 0) this.decoder = ZSTD_createDCtx();
            if (this.decoder == 0) throw new IOException("no local Zstd decoder");
            check(ZSTD_DCtx_reset(this.decoder, ZSTD_reset_session_and_parameters));
            // Streaming API rejects oversized windows before allocating its history buffer.
            check(ZSTD_DCtx_setParameter(this.decoder, ZSTD_d_windowLogMax, WINDOW_LOG));
            try (var input = new DecodedInput(compressed, compressedBytes, canonicalBytes)) {
                int count = u16(input), blocks = u16(input), biomes = u16(input);
                if (count < 1 || count > CELLS || blocks < 1 || blocks > count
                        || biomes < 1 || biomes > Math.min(count, CatalogCodec.MAX_BIOMES))
                    throw new IOException("invalid local name table counts");
                int[] blockIds = names(input, blocks, false, resolver);
                int[] biomeIds = names(input, biomes, true, resolver);
                long[] palette = new long[count];
                var used = new IntLinkedOpenHashSet(count);
                boolean[] seenBlocks = new boolean[blocks], seenBiomes = new boolean[biomes];
                int nextBlock = 0, nextBiome = 0;
                // Validate remote table identities, not translated aliases (including air).
                var identities = new it.unimi.dsi.fastutil.longs.LongOpenHashSet(count);
                for (int i = 0; i < count; i++) {
                    int block = u16(input), biome = u16(input), light = required(input);
                    if (block >= blocks || biome >= biomes || block > nextBlock || biome > nextBiome
                            || !identities.add((long) block << 24 | (long) biome << 8 | light))
                        throw new IOException("invalid local palette reference/order");
                    if (!seenBlocks[block]) { seenBlocks[block] = true; nextBlock++; }
                    if (!seenBiomes[biome]) { seenBiomes[biome] = true; nextBiome++; }
                    int id = blockIds[block];
                    if (id != 0) used.add(id);
                    palette[i] = CatalogMapper.composeMappingId((byte) light, id, biomeIds[biome]);
                }
                if (nextBlock != blocks || nextBiome != biomes) throw new IOException("unused local names");
                long[] cells = new long[CELLS];
                int bits = count == 1 ? 0 : 32 - Integer.numberOfLeadingZeros(count - 1);
                int next = 0, available = 0; long packed = 0;
                for (int cell = 0; cell < CELLS; cell++) {
                    while (available < bits) {
                        packed |= (long) required(input) << available;
                        available += 8;
                    }
                    int index = (int) (packed & ((1 << bits) - 1));
                    packed >>>= bits; available -= bits;
                    if (index >= count || index > next) throw new IOException("invalid local cell index/order");
                    if (index == next) next++;
                    cells[cell] = palette[index];
                }
                if (next != count || input.read() != -1) throw new IOException("unused palette or trailing local data");
                this.decodedBytes = compressedBytes;
                return new RegionalSectionCodec.SectionData(key, children, cells, used.toIntArray());
            }
        } finally { this.busy = false; }
    }

    private static int[] names(DecodedInput input, int count, boolean biome, Names resolver) throws IOException {
        int[] ids = new int[count];
        byte[] bytes = new byte[MAX_NAME_BYTES];
        var decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        // Only compact mapped IDs survive each name; resolver owns canonical spelling/epoch checks.
        for (int i = 0; i < count; i++) {
            int length = u16(input);
            if (length < 1 || length > bytes.length) throw new IOException("invalid local name length");
            input.nameBytes += 2L + length;
            if (input.nameBytes > CatalogCodec.MAX_BYTES - 40L) throw new IOException("local name tables exceed catalog bound");
            readFully(input, bytes, length);
            String name = decoder.decode(ByteBuffer.wrap(bytes, 0, length)).toString();
            ids[i] = resolver.resolve(name, biome);
        }
        return ids;
    }

    /** Pulls one expanded name at a time, followed by compact palette/index bytes. */
    private static final class NamesInput extends InputStream {
        final CatalogCodec.Catalog catalog;
        final byte[] canonical, palette;
        final int[] blocks, biomes;
        final long length;
        private int table, name, position, phase;
        private byte[] part;
        private long consumed;

        NamesInput(byte[] canonical, CatalogCodec.Catalog catalog) throws IOException {
            this.catalog = catalog; this.canonical = canonical;
            if (canonical.length < 2) throw new IOException("truncated wire palette");
            var input = ByteBuffer.wrap(canonical).order(ByteOrder.LITTLE_ENDIAN);
            int count = Short.toUnsignedInt(input.getShort());
            if (count < 1 || count > CELLS) throw new IOException("invalid wire palette count");
            int bits = count == 1 ? 0 : 32 - Integer.numberOfLeadingZeros(count - 1);
            if (canonical.length != 2 + 9 * count + CELLS * bits / 8)
                throw new IOException("invalid wire palette extent");
            // Catalog validation makes each source ID correspond to one distinct canonical
            // name. Deduplicate those IDs without copying strings into a per-section map.
            var blockMap = new Int2IntOpenHashMap(count); blockMap.defaultReturnValue(-1);
            var biomeMap = new Int2IntOpenHashMap(Math.min(count, 512)); biomeMap.defaultReturnValue(-1);
            var blockList = new IntArrayList(); var biomeList = new IntArrayList();
            var packed = ByteBuffer.allocate(HEADER_BYTES + count * 5).order(ByteOrder.LITTLE_ENDIAN);
            packed.position(HEADER_BYTES);
            for (int i = 0; i < count; i++) {
                int block = input.getInt(), biome = input.getInt(); byte light = input.get();
                if (block < 0 || block >= catalog.blocks().size() || biome < 0 || biome >= catalog.biomes().size())
                    throw new IOException("wire palette outside source catalog");
                int b = blockMap.get(block), v = biomeMap.get(biome);
                if (b == -1) { b = blockList.size(); blockMap.put(block, b); blockList.add(block); }
                if (v == -1) { v = biomeList.size(); biomeMap.put(biome, v); biomeList.add(biome); }
                packed.putShort((short) b).putShort((short) v).put(light);
            }
            this.blocks = blockList.toIntArray(); this.biomes = biomeList.toIntArray();
            packed.putShort(0, (short) count).putShort(2, (short) this.blocks.length)
                    .putShort(4, (short) this.biomes.length);
            this.palette = packed.array();
            long names = 0;
            for (int id : this.blocks) names = Math.addExact(names, 2L + utf8Length(catalog.blocks().get(id).canonical()));
            for (int id : this.biomes) names = Math.addExact(names, 2L + utf8Length(catalog.biomes().get(id)));
            if (names > CatalogCodec.MAX_BYTES - 40L) throw new IOException("local names exceed source catalog extent");
            this.length = Math.addExact(names, HEADER_BYTES + count * 5L + CELLS * bits / 8);
            this.part = Arrays.copyOf(this.palette, HEADER_BYTES);
        }

        long remaining() { return this.length - this.consumed; }
        @Override public int read() { throw new UnsupportedOperationException("bulk encoder reads only"); }
        @Override public int read(byte[] out, int off, int len) throws IOException {
            java.util.Objects.checkFromIndexSize(off, len, out.length);
            if (len == 0) return 0;
            if (remaining() == 0) return -1;
            int total = 0;
            while (total < len && remaining() != 0) {
                if (this.position == this.part.length) {
                    this.position = 0;
                    if (this.phase == 0) {
                        if (this.table == 0 && this.name == this.blocks.length) { this.table = 1; this.name = 0; }
                        if (this.table == 1 && this.name == this.biomes.length) {
                            this.phase = 1; this.part = this.palette; this.position = HEADER_BYTES;
                        } else {
                            String value = this.table == 0 ? this.catalog.blocks().get(this.blocks[this.name++]).canonical()
                                    : this.catalog.biomes().get(this.biomes[this.name++]);
                            // Immutable source names were strictly checked while deriving length.
                            byte[] nameBytes = value.getBytes(StandardCharsets.UTF_8);
                            this.part = new byte[2 + nameBytes.length];
                            this.part[0] = (byte) nameBytes.length; this.part[1] = (byte) (nameBytes.length >>> 8);
                            System.arraycopy(nameBytes, 0, this.part, 2, nameBytes.length);
                        }
                    } else {
                        this.phase = 2; this.part = this.canonical;
                        this.position = 2 + (this.palette.length - HEADER_BYTES) / 5 * 9;
                    }
                }
                int count = Math.min(len - total, this.part.length - this.position);
                System.arraycopy(this.part, this.position, out, off + total, count);
                this.position += count; this.consumed += count; total += count;
            }
            return total;
        }
    }

    private final class DecodedInput extends InputStream {
        final InputStream source;
        final Buffers buffers = LocalSectionCodec.this.buffers();
        long sourceLeft, canonicalLeft, nameBytes;
        int at, size;
        boolean ended;
        DecodedInput(InputStream source, long compressed, long canonical) {
            this.source = source; this.sourceLeft = compressed; this.canonicalLeft = canonical;
        }
        @Override public int read() throws IOException {
            if (this.at == this.size && !fill()) return -1;
            return this.buffers.heap[this.at++] & 255;
        }
        @Override public int read(byte[] out, int offset, int length) throws IOException {
            java.util.Objects.checkFromIndexSize(offset, length, out.length);
            if (length == 0) return 0;
            if (this.at == this.size && !fill()) return -1;
            int count = Math.min(length, this.size - this.at);
            System.arraycopy(this.buffers.heap, this.at, out, offset, count); this.at += count;
            return count;
        }
        private boolean fill() throws IOException {
            var b = this.buffers;
            while (!this.ended) {
                if (b.in.pos() == b.in.size() && this.sourceLeft != 0) {
                    int count = (int) Math.min(CHUNK_BYTES, this.sourceLeft);
                    readFully(this.source, b.heap, count); this.sourceLeft -= count;
                    b.input.clear().put(b.heap, 0, count).flip(); b.in.set(b.input, 0);
                }
                b.output.clear(); b.out.set(b.output, 0);
                long before = b.in.pos();
                long result = ZSTD_decompressStream(LocalSectionCodec.this.decoder, b.out, b.in);
                check(result);
                this.size = Math.toIntExact(b.out.pos()); this.at = 0;
                this.canonicalLeft -= this.size;
                if (this.canonicalLeft < 0) throw new IOException("local expanded length overflow");
                this.ended = result == 0;
                if (this.ended && (this.sourceLeft != 0 || b.in.pos() != b.in.size() || this.canonicalLeft != 0))
                    throw new IOException("local frame length mismatch or trailing frame");
                b.output.get(0, b.heap, 0, this.size);
                if (this.size != 0) return true;
                if (!this.ended && before == b.in.pos() && this.sourceLeft == 0)
                    throw new IOException("truncated local Zstd frame");
            }
            return false;
        }
        @Override public void close() { /* Context and scratch belong to the enclosing worker. */ }
    }

    private static final class Buffers implements AutoCloseable {
        final byte[] heap = new byte[CHUNK_BYTES];
        ByteBuffer input, output;
        ZSTDInBuffer in;
        ZSTDOutBuffer out;
        Buffers() {
            try {
                this.input = MemoryUtil.memAlloc(CHUNK_BYTES);
                this.output = MemoryUtil.memAlloc(CHUNK_BYTES);
                this.in = ZSTDInBuffer.calloc();
                this.out = ZSTDOutBuffer.calloc();
                this.input.limit(0); this.in.set(this.input, 0);
            } catch (Throwable failure) { close(); throw failure; }
        }
        @Override public void close() {
            if (this.in != null) this.in.free();
            if (this.out != null) this.out.free();
            MemoryUtil.memFree(this.input); MemoryUtil.memFree(this.output);
        }
    }
    long nativeContextBytes() {
        return (this.encoder == 0 ? 0 : ZSTD_sizeof_CCtx(this.encoder))
                + (this.decoder == 0 ? 0 : ZSTD_sizeof_DCtx(this.decoder));
    }
    private Buffers buffers() {
        if (this.buffers == null) this.buffers = new Buffers();
        this.buffers.input.clear().limit(0);
        this.buffers.in.set(this.buffers.input, 0);
        return this.buffers;
    }
    private void claim() throws IOException {
        if (this.closed || this.busy) throw new IOException("local codec is closed or already owned");
        this.busy = true;
    }
    private static int utf8Length(String name) throws IOException {
        int bytes = 0;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 128) bytes++;
            else if (c < 2048) bytes += 2;
            else if (Character.isHighSurrogate(c)) {
                if (++i >= name.length() || !Character.isLowSurrogate(name.charAt(i)))
                    throw new IOException("unpaired source name surrogate");
                bytes += 4;
            } else if (Character.isLowSurrogate(c)) throw new IOException("unpaired source name surrogate");
            else bytes += 3;
            if (bytes > MAX_NAME_BYTES) throw new IOException("invalid source name length");
        }
        if (bytes == 0) throw new IOException("empty source name");
        return bytes;
    }
    private static int required(InputStream input) throws IOException {
        int value = input.read(); if (value < 0) throw new IOException("truncated local section"); return value;
    }
    private static int u16(InputStream input) throws IOException { return required(input) | required(input) << 8; }
    private static void readFully(InputStream input, byte[] bytes, int length) throws IOException {
        for (int at = 0; at < length;) {
            int read = input.read(bytes, at, length - at);
            if (read <= 0) throw new IOException("truncated local section"); at += read;
        }
    }
    private static void check(long result) throws IOException {
        if (ZSTD_isError(result)) throw new IOException("local Zstd: " + ZSTD_getErrorName(result));
    }
    @Override public void close() {
        if (this.busy) throw new IllegalStateException("local codec still owned");
        this.closed = true;
        if (this.encoder != 0) ZSTD_freeCCtx(this.encoder);
        if (this.decoder != 0) ZSTD_freeDCtx(this.decoder);
        if (this.buffers != null) { this.buffers.close(); this.buffers = null; }
        this.encoder = this.decoder = 0;
    }
}
