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

    public static final class Prepared {
        private final Metadata metadata;
        private final int nonEmptyBlockCount;
        private final long[] cells;

        private Prepared(Metadata metadata, int nonEmptyBlockCount, long[] cells) {
            this.metadata = Objects.requireNonNull(metadata, "metadata");
            if (nonEmptyBlockCount < 0 || nonEmptyBlockCount > CELL_COUNT
                    || cells.length != CELL_COUNT) {
                throw new IllegalArgumentException("invalid prepared microtile");
            }
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

    /** Validates canonical structure before catalog translation is available. */
    public static Metadata inspect(byte[] canonical, ObjectKind expectedKind)
            throws DecodeException {
        Objects.requireNonNull(canonical, "canonical");
        if (canonical.length < 20) throw new DecodeException("microtile is truncated");
        long catalogId = ByteBuffer.wrap(canonical).order(ByteOrder.LITTLE_ENDIAN).getLong(8);
        return inspect(canonical, expectedKind, catalogId);
    }

    /** Validates canonical structure and its announced catalog identity. */
    public static Metadata inspect(byte[] canonical, ObjectKind expectedKind,
                                   long expectedCatalogId) throws DecodeException {
        return parse(canonical, expectedKind, expectedCatalogId, null, null, false).metadata();
    }

    public static Prepared decode(byte[] canonical, ObjectKind expectedKind,
                                  long expectedCatalogId, int[] blockTranslations,
                                  int[] biomeTranslations, boolean countNonAir)
            throws DecodeException {
        Objects.requireNonNull(blockTranslations, "blockTranslations");
        Objects.requireNonNull(biomeTranslations, "biomeTranslations");
        return parse(canonical, expectedKind, expectedCatalogId, blockTranslations,
                biomeTranslations, countNonAir);
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

    private static Prepared parse(byte[] canonical, ObjectKind expectedKind,
                                  long expectedCatalogId, int[] blockTranslations,
                                  int[] biomeTranslations, boolean countNonAir)
            throws DecodeException {
        Objects.requireNonNull(canonical, "canonical");
        ContentClass expectedClass;
        try {
            expectedClass = contentClass(expectedKind);
        } catch (IllegalArgumentException exception) {
            throw new DecodeException(exception.getMessage(), exception);
        }
        if (expectedCatalogId == 0 || canonical.length < HEADER_BYTES + 12
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
        if (!java.util.Arrays.equals(magic, MAGIC)
                || catalogId != expectedCatalogId || rawClass != expectedClass.ordinal()
                || edge != EDGE
                || rawCellCount != CELL_COUNT || paletteSize < 1 || paletteSize > CELL_COUNT
                || bits != minimumBits(paletteSize)) {
            throw new DecodeException("invalid microtile identity or palette metadata");
        }
        try {
            validateOrigin(originX, originY, originZ);
        } catch (IllegalArgumentException exception) {
            throw new DecodeException(exception.getMessage(), exception);
        }
        long expectedWords = bits == 0 ? 0 : ((long) CELL_COUNT * bits + 63) >>> 6;
        long expectedLength = HEADER_BYTES + (long) paletteSize * 9
                + rawWordCount * Long.BYTES;
        if (rawWordCount != expectedWords || expectedLength != canonical.length) {
            throw new DecodeException("microtile word count or canonical length is invalid");
        }

        long[] palette = new long[paletteSize];
        HashSet<RemoteCell> uniquePalette = new HashSet<>(paletteSize * 2);
        boolean translate = blockTranslations != null;
        for (int index = 0; index < paletteSize; index++) {
            long remoteBlock = Integer.toUnsignedLong(input.getInt());
            long remoteBiome = Integer.toUnsignedLong(input.getInt());
            byte light = input.get();
            if (!uniquePalette.add(new RemoteCell(remoteBlock, remoteBiome,
                    Byte.toUnsignedInt(light)))) {
                throw new DecodeException("microtile palette is noncanonical");
            }
            if (translate) {
                if (remoteBlock >= blockTranslations.length
                        || remoteBiome >= biomeTranslations.length) {
                    throw new DecodeException("microtile references an unknown catalog entry");
                }
                int block = blockTranslations[(int) remoteBlock];
                int biome = biomeTranslations[(int) remoteBiome];
                if (block < 0 || biome < 0) {
                    throw new DecodeException("microtile references an untranslated catalog entry");
                }
                try {
                    palette[index] = CatalogMapper.composeMappingId(light, block, biome);
                } catch (IllegalArgumentException exception) {
                    throw new DecodeException("translated microtile mapping is out of bounds",
                            exception);
                }
            }
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
        long[] cells = new long[CELL_COUNT];
        boolean[] paletteUsed = new boolean[paletteSize];
        int nextCanonicalPalette = 0;
        int nonAir = 0;
        if (bits == 0) {
            java.util.Arrays.fill(cells, palette[0]);
            paletteUsed[0] = true;
            nextCanonicalPalette = 1;
            if (translate && countNonAir && !CatalogMapper.isAir(palette[0])) nonAir = CELL_COUNT;
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
                long value = palette[selected];
                cells[index] = value;
                if (translate && countNonAir && !CatalogMapper.isAir(value)) nonAir++;
            }
        }
        if (nextCanonicalPalette != paletteSize) {
            throw new DecodeException("microtile contains unused palette entries");
        }
        Metadata metadata = new Metadata(expectedClass, originX, originY, originZ,
                paletteSize, bits);
        return new Prepared(metadata, nonAir, cells);
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
