package me.cortex.voxy.client.lod;

import me.cortex.voxy.client.core.model.CatalogMapper;
import it.unimi.dsi.fastutil.ints.IntLinkedOpenHashSet;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.lwjgl.util.zstd.Zstd.ZSTD_createDCtx;
import static org.lwjgl.util.zstd.Zstd.ZSTD_decompressDCtx;
import static org.lwjgl.util.zstd.Zstd.ZSTD_freeDCtx;
import static org.lwjgl.util.zstd.Zstd.ZSTD_getErrorName;
import static org.lwjgl.util.zstd.Zstd.ZSTD_getFrameContentSize;
import static org.lwjgl.util.zstd.Zstd.ZSTD_isError;

/** Decodes one compressed regional section directly into the renderer's packed cell format. */
public final class RegionalSectionCodec implements AutoCloseable {
    static final int SECTION_CELLS = 32 * 32 * 32;
    private static final int HEADER_BYTES = 2;

    record Mappings(int[] blocks, int[] biomes) {
        Mappings {
            blocks = Objects.requireNonNull(blocks, "blocks").clone();
            biomes = Objects.requireNonNull(biomes, "biomes").clone();
        }
    }

    record BoundCatalog(RegionalProtocol.Hash32 fingerprint, Mappings mappings) {}

    public record SectionData(long key, int childMask, long[] cells, int[] usedBlocks) {
        public SectionData {
            if ((childMask & ~0xff) != 0 || cells.length != SECTION_CELLS
                    || usedBlocks == null) {
                throw new IllegalArgumentException("invalid decoded regional section");
            }
        }
    }

    private final ConcurrentLinkedQueue<Context> contexts = new ConcurrentLinkedQueue<>();
    private final ThreadLocal<Context> local = ThreadLocal.withInitial(() -> {
        Context context = new Context();
        this.contexts.add(context);
        return context;
    });
    private volatile boolean closed;

    byte[] decompress(byte[] compressed, int canonicalLength) throws IOException {
        Objects.requireNonNull(compressed, "compressed");
        if (this.closed || compressed.length < 1 || compressed.length > RegionalProtocol.MAX_SECTION_BYTES
                || canonicalLength < HEADER_BYTES
                || canonicalLength > RegionalProtocol.MAX_SECTION_BYTES) {
            throw new IOException("invalid regional compressed section bounds");
        }
        ByteBuffer source = MemoryUtil.memAlloc(compressed.length);
        ByteBuffer destination = MemoryUtil.memAlloc(canonicalLength);
        try {
            source.put(compressed).flip();
            long result = ZSTD_decompressDCtx(this.local.get().address, destination, source);
            if (ZSTD_isError(result)) {
                throw new IOException("regional Zstd decode failed: " + ZSTD_getErrorName(result));
            }
            if (result != canonicalLength) {
                throw new IOException("regional Zstd length disagrees with section header");
            }
            byte[] canonical = new byte[canonicalLength];
            destination.position(0).limit(canonicalLength).get(canonical);
            return canonical;
        } finally {
            MemoryUtil.memFree(destination);
            MemoryUtil.memFree(source);
        }
    }

    byte[] decompressFramed(byte[] compressed, int maximum) throws IOException {
        if (this.closed || compressed.length < 1 || compressed.length > maximum) {
            throw new IOException("invalid framed Zstd bounds");
        }
        ByteBuffer source = MemoryUtil.memAlloc(compressed.length);
        try {
            source.put(compressed).flip();
            long length = ZSTD_getFrameContentSize(source);
            if (length <= 0 || length > maximum) {
                throw new IOException("framed Zstd content size is absent or outside bounds");
            }
            return decompress(compressed, (int) length);
        } finally {
            MemoryUtil.memFree(source);
        }
    }

    SectionData decode(long key, int childMask, byte[] canonical,
                       RegionalProtocol.Fingerprint expected,
                       Mappings mappings) throws IOException {
        Objects.requireNonNull(canonical, "canonical");
        Objects.requireNonNull(expected, "fingerprint");
        Objects.requireNonNull(mappings, "mappings");
        byte[] hash = new Blake3.Hasher().update(canonical).digest();
        if (!Arrays.equals(Arrays.copyOf(hash, 16), expected.bytes())) {
            throw new IOException("regional section content fingerprint mismatch");
        }
        if (canonical.length < HEADER_BYTES) throw new IOException("truncated regional section");
        ByteBuffer input = ByteBuffer.wrap(canonical).order(ByteOrder.LITTLE_ENDIAN);
        int paletteCount = Short.toUnsignedInt(input.getShort());
        if ((childMask & ~0xff) != 0 || paletteCount < 1) {
            throw new IOException("invalid regional section header");
        }
        int bits = minimumBits(paletteCount);
        long expectedWords = bits == 0 ? 0 : ((long) SECTION_CELLS * bits + 63) >>> 6;
        long expectedLength = HEADER_BYTES + (long) paletteCount * 9 + expectedWords * 8;
        if (expectedLength != canonical.length) {
            throw new IOException("regional section palette or word extent is invalid");
        }
        long[] palette = new long[paletteCount];
        // The validated palette bounds unique IDs; reserve once instead of rehashing during translation.
        var usedBlocks = new IntLinkedOpenHashSet(paletteCount);
        java.util.HashSet<RemotePaletteEntry> remotePalette =
                new java.util.HashSet<>(paletteCount * 2);
        for (int index = 0; index < paletteCount; index++) {
            long remoteBlock = Integer.toUnsignedLong(input.getInt());
            long remoteBiome = Integer.toUnsignedLong(input.getInt());
            byte light = input.get();
            RemotePaletteEntry identity = new RemotePaletteEntry(
                    (int) remoteBlock, (int) remoteBiome, light);
            if (!remotePalette.add(identity) || remoteBlock >= mappings.blocks.length
                    || remoteBiome >= mappings.biomes.length) {
                throw new IOException("invalid regional section palette entry");
            }
            int localBlock = mappings.blocks[(int) remoteBlock];
            if (localBlock != 0) usedBlocks.add(localBlock);
            palette[index] = CatalogMapper.composeMappingId(light,
                    localBlock, mappings.biomes[(int) remoteBiome]);
        }
        int wordsOffset = input.position();
        if (bits != 0 && (SECTION_CELLS * bits & 63) != 0) {
            int used = SECTION_CELLS * bits & 63;
            long finalWord = input.getLong(wordsOffset + ((int) expectedWords - 1) * 8);
            if (finalWord >>> used != 0) throw new IOException("nonzero section index padding");
        }
        long[] cells = new long[SECTION_CELLS];
        int next = 0;
        if (bits == 0) {
            Arrays.fill(cells, palette[0]);
            next = 1;
        } else {
            long mask = (1L << bits) - 1;
            for (int index = 0; index < SECTION_CELLS; index++) {
                long bit = (long) index * bits;
                int word = (int) (bit >>> 6); int shift = (int) bit & 63;
                long packed = input.getLong(wordsOffset + word * 8) >>> shift;
                if (shift + bits > 64) {
                    packed |= input.getLong(wordsOffset + (word + 1) * 8) << (64 - shift);
                }
                int selected = (int) (packed & mask);
                if (selected >= paletteCount) throw new IOException("section palette index overflow");
                if (selected > next) throw new IOException("noncanonical section palette order");
                if (selected == next) next++;
                cells[index] = palette[selected];
            }
        }
        if (next != paletteCount) throw new IOException("unused regional section palette entry");
        return new SectionData(key, childMask, cells, usedBlocks.toIntArray());
    }

    private static int minimumBits(int paletteCount) {
        return paletteCount == 1 ? 0 : 32 - Integer.numberOfLeadingZeros(paletteCount - 1);
    }

    private record RemotePaletteEntry(int block, int biome, byte light) {}

    @Override public void close() {
        this.closed = true;
        Context context;
        while ((context = this.contexts.poll()) != null) context.close();
        this.local.remove();
    }

    private static final class Context implements AutoCloseable {
        private long address = ZSTD_createDCtx();
        private Context() {
            if (this.address == MemoryUtil.NULL) throw new IllegalStateException("no Zstd context");
        }
        @Override public void close() {
            long value = this.address; this.address = MemoryUtil.NULL;
            if (value == MemoryUtil.NULL) return;
            long result = ZSTD_freeDCtx(value);
            if (ZSTD_isError(result)) {
                throw new IllegalStateException("could not free Zstd context: "
                        + ZSTD_getErrorName(result));
            }
        }
    }
}
