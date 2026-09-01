package me.cortex.voxy.client.lod;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Bounded decoder for the canonical block/biome catalog. */
public final class CatalogCodec {
    public static final int MAX_BYTES = 64 * 1024 * 1024;
    public static final int MAX_BLOCKS = 1 << 20;
    public static final int MAX_BIOMES = 1 << 9;
    private static final int MAX_NAME_BYTES = 4096;
    private static final byte[] MAGIC = "VXYCAT\0\0".getBytes(StandardCharsets.US_ASCII);

    private CatalogCodec() {}

    public record Block(String canonical, int opacity, boolean authoritative) {
        public Block {
            Objects.requireNonNull(canonical, "canonical");
            if (canonical.isEmpty() || canonical.getBytes(StandardCharsets.UTF_8).length
                    > MAX_NAME_BYTES || opacity < 0 || opacity > 15) {
                throw new IllegalArgumentException("invalid canonical catalog block");
            }
        }
    }

    public record Catalog(long catalogId, long generation, long mipGeneration,
                          List<Block> blocks, List<String> biomes) {
        public Catalog {
            if (catalogId == 0) throw new IllegalArgumentException("catalog identity zero is reserved");
            blocks = List.copyOf(Objects.requireNonNull(blocks, "blocks"));
            biomes = List.copyOf(Objects.requireNonNull(biomes, "biomes"));
            if (blocks.isEmpty() || blocks.size() > MAX_BLOCKS
                    || biomes.isEmpty() || biomes.size() > MAX_BIOMES) {
                throw new IllegalArgumentException("catalog entry counts are outside bounds");
            }
            for (Block block : blocks) Objects.requireNonNull(block, "block");
            for (String biome : biomes) {
                Objects.requireNonNull(biome, "biome");
                int bytes = biome.getBytes(StandardCharsets.UTF_8).length;
                if (biome.isEmpty() || bytes > MAX_NAME_BYTES) {
                    throw new IllegalArgumentException("invalid canonical biome name");
                }
            }
        }
    }

    public static Catalog decode(byte[] canonical) throws DecodeException {
        Objects.requireNonNull(canonical, "canonical");
        if (canonical.length < 40 || canonical.length > MAX_BYTES) {
            throw new DecodeException("canonical catalog is truncated or oversized");
        }
        ByteBuffer input = ByteBuffer.wrap(canonical).order(ByteOrder.LITTLE_ENDIAN);
        byte[] magic = new byte[MAGIC.length];
        input.get(magic);
        if (!java.util.Arrays.equals(magic, MAGIC)) {
            throw new DecodeException("unsupported canonical catalog envelope");
        }
        long catalogId = input.getLong();
        long generation = input.getLong();
        long mipGeneration = input.getLong();
        long rawBlocks = Integer.toUnsignedLong(input.getInt());
        long rawBiomes = Integer.toUnsignedLong(input.getInt());
        if (rawBlocks < 1 || rawBlocks > MAX_BLOCKS || rawBiomes < 1 || rawBiomes > MAX_BIOMES
                || rawBlocks * 4L + rawBiomes * 2L > input.remaining()) {
            throw new DecodeException("canonical catalog counts cannot fit its payload");
        }
        List<Block> blocks = new ArrayList<>((int) rawBlocks);
        for (int index = 0; index < rawBlocks; index++) {
            require(input, 4);
            int opacity = Byte.toUnsignedInt(input.get());
            int flags = Byte.toUnsignedInt(input.get());
            if (opacity > 15 || (flags & ~1) != 0) {
                throw new DecodeException("invalid canonical catalog block flags");
            }
            blocks.add(new Block(readName(input), opacity, (flags & 1) != 0));
        }
        List<String> biomes = new ArrayList<>((int) rawBiomes);
        for (int index = 0; index < rawBiomes; index++) biomes.add(readName(input));
        if (input.hasRemaining()) throw new DecodeException("trailing canonical catalog bytes");
        try {
            return new Catalog(catalogId, generation, mipGeneration, blocks, biomes);
        } catch (IllegalArgumentException exception) {
            throw new DecodeException(exception.getMessage(), exception);
        }
    }

    private static String readName(ByteBuffer input) throws DecodeException {
        require(input, 2);
        int length = Short.toUnsignedInt(input.getShort());
        if (length < 1 || length > MAX_NAME_BYTES) {
            throw new DecodeException("canonical catalog name length is outside bounds");
        }
        require(input, length);
        ByteBuffer bytes = input.slice();
        bytes.limit(length);
        input.position(input.position() + length);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(bytes).toString();
        } catch (CharacterCodingException exception) {
            throw new DecodeException("canonical catalog name is not UTF-8", exception);
        }
    }

    private static void require(ByteBuffer input, int count) throws DecodeException {
        if (count < 0 || input.remaining() < count) {
            throw new DecodeException("truncated canonical catalog");
        }
    }

    public static final class DecodeException extends Exception {
        public DecodeException(String message) { super(message); }
        public DecodeException(String message, Throwable cause) { super(message, cause); }
    }
}
