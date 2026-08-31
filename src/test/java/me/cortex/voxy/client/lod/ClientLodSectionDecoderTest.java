package me.cortex.voxy.client.lod;

import io.airlift.compress.zstd.ZstdCompressor;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.other.Mapper;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class ClientLodSectionDecoderTest {
    private static final long REVISION = 41;
    private static final byte CHILDREN = 0x35;

    @Test
    void decodesUniformZeroBitSection() throws Exception {
        int[] blocks = translations(8, 2, 7);
        int[] biomes = translations(8, 3, 4);
        Entry[] palette = {new Entry(2, 3, (byte) 0x9a)};
        long[] output = new long[WorldSection.SECTION_VOLUME];

        var section = ClientLodNetwork.decodeSection(9, payload(0, palette, null, (byte) 0),
                blocks, biomes, output);
        long expected = Mapper.composeMappingId((byte) 0x9a, 7, 4);

        assertEquals(9, section.session());
        assertEquals(REVISION, section.revision());
        assertEquals(CHILDREN, section.nonEmptyChildren());
        assertEquals(WorldSection.SECTION_VOLUME, section.nonEmptyBlockCount());
        for (long value : output) assertEquals(expected, value);
    }

    @Test
    void decodesCrossWordIndexesMappingsAndNonAirCount() throws Exception {
        int[] blocks = translations(12, 1, 0, 2, 7, 3, 8, 4, 9, 5, 10);
        int[] biomes = translations(12, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6);
        Entry[] palette = {
                new Entry(1, 1, (byte) 0x10),
                new Entry(2, 2, (byte) 0x21),
                new Entry(3, 3, (byte) 0x32),
                new Entry(4, 4, (byte) 0x43),
                new Entry(5, 5, (byte) 0x54)
        };
        int[] indexes = new int[WorldSection.SECTION_VOLUME];
        int expectedNonAir = 0;
        long[] expected = new long[indexes.length];
        for (int i = 0; i < indexes.length; i++) {
            int index = (i * 3 + i / 17) % palette.length;
            indexes[i] = index;
            Entry entry = palette[index];
            int localBlock = blocks[entry.block()];
            expected[i] = Mapper.composeMappingId(entry.light(), localBlock, biomes[entry.biome()]);
            if (localBlock != 0) expectedNonAir++;
        }

        long[] output = new long[WorldSection.SECTION_VOLUME];
        var section = ClientLodNetwork.decodeSection(3, payload(0, palette, indexes, (byte) 0),
                blocks, biomes, output);

        assertArrayEquals(expected, output);
        assertEquals(expectedNonAir, section.nonEmptyBlockCount());
    }

    @Test
    void rejectsUnknownBlockMapping() {
        int[] blocks = translations(8);
        int[] biomes = translations(8, 3, 4);
        ByteBuffer payload = payload(0, new Entry[] {new Entry(2, 3, (byte) 0)}, null, (byte) 0);

        assertThrows(ClientLodNetwork.ProtocolException.class,
                () -> decode(payload, blocks, biomes));
    }

    @Test
    void rejectsUnknownBiomeMapping() {
        int[] blocks = translations(8, 2, 7);
        int[] biomes = translations(8);
        ByteBuffer payload = payload(0, new Entry[] {new Entry(2, 3, (byte) 0)}, null, (byte) 0);

        assertThrows(ClientLodNetwork.ProtocolException.class,
                () -> decode(payload, blocks, biomes));
    }

    @Test
    void rejectsPaletteIndexOutsidePalette() {
        int[] blocks = translations(8, 1, 1, 2, 2, 3, 3);
        int[] biomes = translations(8, 1, 1, 2, 2, 3, 3);
        Entry[] palette = {
                new Entry(1, 1, (byte) 1),
                new Entry(2, 2, (byte) 2),
                new Entry(3, 3, (byte) 3)
        };
        int[] indexes = new int[WorldSection.SECTION_VOLUME];
        indexes[21] = 3;

        assertThrows(ClientLodNetwork.ProtocolException.class,
                () -> decode(payload(0, palette, indexes, (byte) 0), blocks, biomes));
    }

    @Test
    void rejectsTruncatedAndOversizedPayloads() {
        int[] blocks = translations(8, 2, 7);
        int[] biomes = translations(8, 3, 4);
        ByteBuffer valid = payload(0, new Entry[] {new Entry(2, 3, (byte) 5)}, null, (byte) 0);
        ByteBuffer truncated = valid.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        truncated.limit(truncated.limit() - 1);
        ByteBuffer oversized = ByteBuffer.allocate(valid.remaining() + 1).order(ByteOrder.LITTLE_ENDIAN);
        oversized.put(valid.duplicate()).put((byte) 0).flip();

        assertThrows(ClientLodNetwork.ProtocolException.class,
                () -> decode(truncated, blocks, biomes));
        assertThrows(ClientLodNetwork.ProtocolException.class,
                () -> decode(oversized, blocks, biomes));
    }

    @Test
    void rejectsNonzeroReservedPaletteBytes() {
        int[] blocks = translations(8, 2, 7);
        int[] biomes = translations(8, 3, 4);

        assertThrows(ClientLodNetwork.ProtocolException.class, () -> decode(
                payload(0, new Entry[] {new Entry(2, 3, (byte) 5)}, null, (byte) 1),
                blocks, biomes));
    }

    @Test
    void decodesNoUpdateResolutionBatch() throws Exception {
        long first = WorldEngine.getWorldSectionId(2, 7, -2, 11);
        long second = WorldEngine.getWorldSectionId(0, -1, 3, 4);
        var current = ClientLodNetwork.decodeResolution(17, resolution((byte) 1, (byte) 0, first, second));
        var legacy = ClientLodNetwork.decodeResolution(18, resolution((byte) 2, (byte) 0, second));

        assertEquals(17, current.session());
        assertArrayEquals(new long[] {first, second}, current.keys());
        assertEquals(18, legacy.session());
        assertArrayEquals(new long[] {second}, legacy.keys());
    }

    @Test
    void rejectsMalformedResolutionBatches() {
        long key = WorldEngine.getWorldSectionId(1, 0, 0, 0);
        assertThrows(ClientLodNetwork.ProtocolException.class,
                () -> ClientLodNetwork.decodeResolution(1, resolution((byte) 3, (byte) 0, key)));
        assertThrows(ClientLodNetwork.ProtocolException.class,
                () -> ClientLodNetwork.decodeResolution(1, resolution((byte) 1, (byte) 1, key)));
        assertThrows(ClientLodNetwork.ProtocolException.class,
                () -> ClientLodNetwork.decodeResolution(1,
                        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                                .put((byte) 1).put((byte) 0).putShort((short) 0).flip()));
        ByteBuffer trailing = ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN)
                .put((byte) 1).put((byte) 0).putShort((short) 1).putLong(key).put((byte) 0).flip();
        assertThrows(ClientLodNetwork.ProtocolException.class,
                () -> ClientLodNetwork.decodeResolution(1, trailing));
    }

    private static ClientLodNetwork.PreparedSection decode(ByteBuffer payload, int[] blocks,
                                                            int[] biomes) throws Exception {
        return ClientLodNetwork.decodeSection(1, payload, blocks, biomes,
                new long[WorldSection.SECTION_VOLUME]);
    }

    private static int[] translations(int size, int... pairs) {
        int[] result = new int[size];
        Arrays.fill(result, -1);
        for (int i = 0; i < pairs.length; i += 2) result[pairs[i]] = pairs[i + 1];
        return result;
    }

    private static ByteBuffer resolution(byte status, byte reserved, long... keys) {
        ByteBuffer output = ByteBuffer.allocate(4 + keys.length * Long.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);
        output.put(status).put(reserved).putShort((short) keys.length);
        for (long key : keys) output.putLong(key);
        return output.flip();
    }

    private static ByteBuffer payload(int level, Entry[] palette, int[] indexes, byte reserved) {
        int bits = palette.length == 1 ? 0 : 32 - Integer.numberOfLeadingZeros(palette.length - 1);
        int wordCount = (WorldSection.SECTION_VOLUME * bits + 63) >>> 6;
        long[] words = new long[wordCount];
        if (bits != 0) {
            if (indexes == null || indexes.length != WorldSection.SECTION_VOLUME) {
                throw new IllegalArgumentException("Packed sections need one index per cell");
            }
            for (int i = 0; i < indexes.length; i++) {
                long bit = (long) i * bits;
                int word = (int) (bit >>> 6);
                int shift = (int) (bit & 63);
                long index = Integer.toUnsignedLong(indexes[i]);
                words[word] |= index << shift;
                if (shift + bits > 64) words[word + 1] |= index >>> (64 - shift);
            }
        }

        ByteBuffer canonical = ByteBuffer.allocate(12 + palette.length * 12 + words.length * 8)
                .order(ByteOrder.LITTLE_ENDIAN);
        canonical.put((byte) 1).put(CHILDREN).put((byte) bits).put((byte) 0)
                .putShort((short) palette.length).putShort((short) 0).putInt(words.length);
        for (Entry entry : palette) {
            canonical.putInt(entry.block()).putInt(entry.biome()).put(entry.light())
                    .put(reserved).put((byte) 0).put((byte) 0);
        }
        for (long word : words) canonical.putLong(word);

        byte[] raw = canonical.array();
        ZstdCompressor compressor = new ZstdCompressor();
        byte[] compressed = new byte[compressor.maxCompressedLength(raw.length)];
        int compressedLength = compressor.compress(raw, 0, raw.length, compressed, 0, compressed.length);
        ByteBuffer output = ByteBuffer.allocate(24 + compressedLength).order(ByteOrder.LITTLE_ENDIAN);
        output.putLong(WorldEngine.getWorldSectionId(level, 7, -2, 11)).putLong(REVISION)
                .putInt(raw.length).put((byte) 1).put((byte) 0).put((byte) 0).put((byte) 0)
                .put(compressed, 0, compressedLength);
        return output.flip();
    }

    private record Entry(int block, int biome, byte light) {}
}
