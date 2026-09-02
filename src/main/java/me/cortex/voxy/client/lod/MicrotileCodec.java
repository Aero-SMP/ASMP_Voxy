package me.cortex.voxy.client.lod;

import me.cortex.voxy.client.lod.ManifestCodec.ContentClass;
import me.cortex.voxy.client.core.model.CatalogMapper;
import me.cortex.voxy.client.lod.WireMessage.ObjectKind;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Objects;

/** Strict decoder for final production 8-cubed exterior, interior, and complex objects. */
public final class MicrotileCodec {
    public static final int EDGE = 8;
    public static final int CELL_COUNT = EDGE * EDGE * EDGE;
    public static final int HEADER_BYTES = 32;
    public static final int MAX_ENCODED_BYTES = 512 * 1024;
    private static final byte[] MAGIC = "VXYTILE\0".getBytes(StandardCharsets.US_ASCII);

    private MicrotileCodec() {}

    public record Metadata(ContentClass contentClass, int originX, int originY, int originZ,
                           int paletteSize, int bitsPerIndex) {
        public Metadata {
            Objects.requireNonNull(contentClass, "contentClass");
            validateOrigin(originX, originY, originZ);
            if (paletteSize < 1 || paletteSize > CELL_COUNT
                    || bitsPerIndex != minimumBits(paletteSize)) {
                throw new IllegalArgumentException("invalid microtile palette metadata");
            }
        }

        public int microtileIndex() {
            return indexForOrigin(originX, originY, originZ);
        }
    }

    /** Structurally validated canonical data awaiting client-catalog translation. */
    static final class Decoded {
        private final Metadata metadata;
        private final long catalogId;
        private final int[] blocks;
        private final int[] biomes;
        private final byte[] lights;
        private final short[] paletteIndexes;

        private Decoded(Metadata metadata, long catalogId, int[] blocks, int[] biomes,
                        byte[] lights, short[] paletteIndexes) {
            this.metadata = metadata;
            this.catalogId = catalogId;
            this.blocks = blocks;
            this.biomes = biomes;
            this.lights = lights;
            this.paletteIndexes = paletteIndexes;
        }

    }

    public static final class Prepared {
        private final Metadata metadata;
        private final int nonEmptyBlockCount;
        private final long[] cells;

        private Prepared(Metadata metadata, int nonEmptyBlockCount, long[] cells) {
            this.metadata = metadata;
            this.nonEmptyBlockCount = nonEmptyBlockCount;
            this.cells = cells;
        }

        public Metadata metadata() {
            return this.metadata;
        }

        public int nonEmptyBlockCount() {
            return this.nonEmptyBlockCount;
        }

        public long[] cells() {
            return this.cells.clone();
        }

        long[] cellsInternal() {
            return this.cells;
        }
    }

    /** Parses and validates canonical bytes once, before catalog translation is available. */
    static Decoded decodeCanonical(byte[] canonical, ObjectKind expectedKind)
            throws DecodeException {
        return parse(canonical, expectedKind);
    }

    /** Translates a validated canonical microtile without rereading its byte representation. */
    static Prepared prepare(Decoded decoded, long expectedCatalogId,
                            int[] blockTranslations, int[] biomeTranslations,
                            boolean countNonAir) throws DecodeException {
        Objects.requireNonNull(decoded, "decoded");
        Objects.requireNonNull(blockTranslations, "blockTranslations");
        Objects.requireNonNull(biomeTranslations, "biomeTranslations");
        if (expectedCatalogId == 0 || decoded.catalogId != expectedCatalogId) {
            throw new DecodeException("microtile belongs to another catalog");
        }
        int paletteSize = decoded.metadata.paletteSize();
        long[] palette = new long[paletteSize];
        for (int index = 0; index < paletteSize; index++) {
            long remoteBlock = Integer.toUnsignedLong(decoded.blocks[index]);
            long remoteBiome = Integer.toUnsignedLong(decoded.biomes[index]);
            if (remoteBlock >= blockTranslations.length || remoteBiome >= biomeTranslations.length) {
                throw new DecodeException("microtile references an unknown catalog entry");
            }
            int block = blockTranslations[(int) remoteBlock];
            int biome = biomeTranslations[(int) remoteBiome];
            if (block < 0 || biome < 0) {
                throw new DecodeException("microtile references an untranslated catalog entry");
            }
            try {
                palette[index] = CatalogMapper.composeMappingId(decoded.lights[index], block, biome);
            } catch (IllegalArgumentException exception) {
                throw new DecodeException("translated microtile mapping is out of bounds",
                        exception);
            }
        }

        long[] cells = new long[CELL_COUNT];
        int nonAir = 0;
        for (int index = 0; index < CELL_COUNT; index++) {
            long value = palette[Short.toUnsignedInt(decoded.paletteIndexes[index])];
            cells[index] = value;
            if (countNonAir && !CatalogMapper.isAir(value)) nonAir++;
        }
        return new Prepared(decoded.metadata, nonAir, cells);
    }

    public static ObjectKind objectKind(ContentClass contentClass) {
        return switch (Objects.requireNonNull(contentClass, "contentClass")) {
            case EXTERIOR -> ObjectKind.EXTERIOR_MICROTILE;
            case INTERIOR -> ObjectKind.INTERIOR_MICROTILE;
            case COMPLEX -> ObjectKind.COMPLEX_MICROTILE;
        };
    }

    public static ContentClass contentClass(ObjectKind objectKind) {
        return switch (Objects.requireNonNull(objectKind, "objectKind")) {
            case EXTERIOR_MICROTILE -> ContentClass.EXTERIOR;
            case INTERIOR_MICROTILE -> ContentClass.INTERIOR;
            case COMPLEX_MICROTILE -> ContentClass.COMPLEX;
            default -> throw new IllegalArgumentException("object is not a production microtile");
        };
    }

    /**
     * Returns the canonical two-step Morton path used by the manifest.  Each octant stores X in
     * bit 0, Y in bit 1, and Z in bit 2; the high coordinate bits form the first path step.
     */
    public static int indexForOrigin(int originX, int originY, int originZ) {
        validateOrigin(originX, originY, originZ);
        int x = originX / EDGE;
        int y = originY / EDGE;
        int z = originZ / EDGE;
        int high = (x >>> 1) | (y >>> 1) << 1 | (z >>> 1) << 2;
        int low = (x & 1) | (y & 1) << 1 | (z & 1) << 2;
        return high << 3 | low;
    }

    private static Decoded parse(byte[] canonical, ObjectKind expectedKind)
            throws DecodeException {
        Objects.requireNonNull(canonical, "canonical");
        ContentClass expectedClass;
        try {
            expectedClass = contentClass(expectedKind);
        } catch (IllegalArgumentException exception) {
            throw new DecodeException(exception.getMessage(), exception);
        }
        if (canonical.length < HEADER_BYTES + 12
                || canonical.length > MAX_ENCODED_BYTES) {
            throw new DecodeException("microtile is truncated or oversized");
        }
        ByteBuffer input = ByteBuffer.wrap(canonical).order(ByteOrder.LITTLE_ENDIAN);
        byte[] magic = new byte[MAGIC.length];
        input.get(magic);
        long catalogId = input.getLong();
        int rawClass = Byte.toUnsignedInt(input.get());
        int edge = Byte.toUnsignedInt(input.get());
        int originX = Byte.toUnsignedInt(input.get());
        int originY = Byte.toUnsignedInt(input.get());
        int originZ = Byte.toUnsignedInt(input.get());
        long rawCellCount = Integer.toUnsignedLong(input.getInt());
        int paletteSize = Short.toUnsignedInt(input.getShort());
        int bits = Byte.toUnsignedInt(input.get());
        long rawWordCount = Integer.toUnsignedLong(input.getInt());
        if (!java.util.Arrays.equals(magic, MAGIC) || catalogId == 0
                || rawClass != expectedClass.ordinal()
                || edge != EDGE || rawCellCount != CELL_COUNT) {
            throw new DecodeException("invalid microtile identity or palette metadata");
        }
        Metadata metadata;
        try {
            metadata = new Metadata(expectedClass, originX, originY, originZ,
                    paletteSize, bits);
        } catch (IllegalArgumentException exception) {
            throw new DecodeException(exception.getMessage(), exception);
        }
        long expectedWords = bits == 0 ? 0 : ((long) CELL_COUNT * bits + 63) >>> 6;
        long expectedLength = HEADER_BYTES + (long) paletteSize * 9
                + rawWordCount * Long.BYTES;
        if (rawWordCount != expectedWords || expectedLength != canonical.length) {
            throw new DecodeException("microtile word count or canonical length is invalid");
        }

        int[] blocks = new int[paletteSize];
        int[] biomes = new int[paletteSize];
        byte[] lights = new byte[paletteSize];
        HashSet<RemoteCell> uniquePalette = new HashSet<>(paletteSize * 2);
        for (int index = 0; index < paletteSize; index++) {
            int block = input.getInt();
            int biome = input.getInt();
            byte light = input.get();
            if (!uniquePalette.add(new RemoteCell(Integer.toUnsignedLong(block),
                    Integer.toUnsignedLong(biome),
                    Byte.toUnsignedInt(light)))) {
                throw new DecodeException("microtile palette is noncanonical");
            }
            blocks[index] = block;
            biomes[index] = biome;
            lights[index] = light;
        }

        int wordsOffset = input.position();
        if (bits != 0) {
            int usedFinalBits = CELL_COUNT * bits & 63;
            if (usedFinalBits != 0) {
                long finalWord = input.getLong(wordsOffset
                        + ((int) rawWordCount - 1) * Long.BYTES);
                if (finalWord >>> usedFinalBits != 0) {
                    throw new DecodeException("microtile has nonzero packed padding bits");
                }
            }
        }
        short[] paletteIndexes = new short[CELL_COUNT];
        boolean[] paletteUsed = new boolean[paletteSize];
        int nextCanonicalPalette = 0;
        if (bits == 0) {
            paletteUsed[0] = true;
            nextCanonicalPalette = 1;
        } else {
            long mask = (1L << bits) - 1;
            for (int index = 0; index < CELL_COUNT; index++) {
                long bit = (long) index * bits;
                int word = (int) (bit >>> 6);
                int shift = (int) (bit & 63);
                long packed = input.getLong(wordsOffset + word * Long.BYTES) >>> shift;
                if (shift + bits > Long.SIZE) {
                    packed |= input.getLong(wordsOffset + (word + 1) * Long.BYTES)
                            << (Long.SIZE - shift);
                }
                int selected = (int) (packed & mask);
                if (selected >= paletteSize) {
                    throw new DecodeException("microtile palette index is out of range");
                }
                if (!paletteUsed[selected]) {
                    if (selected != nextCanonicalPalette) {
                        throw new DecodeException(
                                "microtile palette is not ordered by first encounter");
                    }
                    paletteUsed[selected] = true;
                    nextCanonicalPalette++;
                }
                paletteIndexes[index] = (short) selected;
            }
        }
        if (nextCanonicalPalette != paletteSize) {
            throw new DecodeException("microtile contains unused palette entries");
        }
        return new Decoded(metadata, catalogId, blocks, biomes, lights, paletteIndexes);
    }

    private static int minimumBits(int paletteSize) {
        return paletteSize == 1 ? 0 : Integer.SIZE - Integer.numberOfLeadingZeros(paletteSize - 1);
    }

    private static void validateOrigin(int x, int y, int z) {
        if ((x | y | z) < 0 || x + EDGE > 32 || y + EDGE > 32 || z + EDGE > 32
                || x % EDGE != 0 || y % EDGE != 0 || z % EDGE != 0) {
            throw new IllegalArgumentException("invalid parent-local microtile origin");
        }
    }

    private record RemoteCell(long block, long biome, int light) {}

    public static final class DecodeException extends Exception {
        public DecodeException(String message) {
            super(message);
        }

        public DecodeException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
