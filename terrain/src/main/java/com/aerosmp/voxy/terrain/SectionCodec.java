package com.aerosmp.voxy.terrain;

import org.lwjgl.util.zstd.Zstd;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;

public final class SectionCodec {
    public static final int HEADER = 81;
    public static final int MAX_CANONICAL = 2 + 9 * TerrainData.CELLS + 2 * TerrainData.CELLS;
    public record Catalog(byte[] hash, String[] blocks, String[] biomes) {}
    private SectionCodec() {}
    public static byte[] hash(byte[] value) {
        return hash(value, 0, value.length);
    }
    private static byte[] hash(byte[] value, int start, int length) {
        try { MessageDigest digest = MessageDigest.getInstance("SHA-256"); digest.update(value, start, length); return digest.digest(); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    public static String hex(byte[] value) { return HexFormat.of().formatHex(value); }
    public static byte[] catalogHash(byte[] frame) throws IOException {
        if (frame.length < HEADER) throw new IOException("Truncated section");
        return Arrays.copyOfRange(frame, 8, 40);
    }
    public static void checkFrame(byte[] frame) throws IOException {
        if (frame.length < HEADER || frame.length > HEADER + MAX_CANONICAL + 2048
                || !Arrays.equals(Arrays.copyOf(frame, 8), "VXRSEC01".getBytes(StandardCharsets.US_ASCII)))
            throw new IOException("Invalid section envelope");
        ByteBuffer input = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN);
        int canonical = input.getInt(73), compressed = input.getInt(77);
        if (canonical < 11 || canonical > MAX_CANONICAL
                || compressed <= 0 || compressed != frame.length - HEADER
                || !MessageDigest.isEqual(Arrays.copyOfRange(frame, 40, 72),
                hash(frame, HEADER, frame.length - HEADER)))
            throw new IOException("Invalid section length or checksum");
    }
    public static Catalog catalog(byte[] bytes, byte[] expected) throws IOException {
        if (!MessageDigest.isEqual(hash(bytes), expected)) throw new IOException("Catalog checksum mismatch");
        try {
            ByteBuffer input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            byte[] magic = new byte[8]; input.get(magic);
            if (!Arrays.equals(magic, "VXRCAT01".getBytes(StandardCharsets.US_ASCII)))
                throw new IOException("Invalid catalog envelope");
            String[] blocks = names(input), biomes = names(input);
            if (input.hasRemaining() || !blocks[0].equals("minecraft:air")) throw new IOException("Invalid catalog");
            return new Catalog(expected.clone(), blocks, biomes);
        } catch (RuntimeException malformed) { throw new IOException("Malformed catalog", malformed); }
    }
    private static String[] names(ByteBuffer input) throws IOException {
        int count = input.getInt();
        if (count <= 0 || count > input.remaining() / 3) throw new IOException("Invalid catalog count");
        String[] names = new String[count];
        var unique = new java.util.HashSet<String>();
        for (int i = 0; i < count; i++) {
            int length = Short.toUnsignedInt(input.getShort());
            if (length == 0 || length > input.remaining()) throw new IOException("Invalid catalog name");
            ByteBuffer name = input.slice(); name.limit(length); input.position(input.position() + length);
            names[i] = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(name).toString();
            if (!unique.add(names[i])) throw new IOException("Duplicate catalog name");
        }
        return names;
    }
    public static TerrainData decode(SectionKey key, byte[] frame, Catalog catalog) throws IOException {
        checkFrame(frame);
        if (!MessageDigest.isEqual(catalogHash(frame), catalog.hash)) throw new IOException("Wrong section catalog");
        int length = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN).getInt(73);
        ByteBuffer input;
        ByteBuffer source = org.lwjgl.system.MemoryUtil.memAlloc(frame.length - HEADER);
        ByteBuffer target = org.lwjgl.system.MemoryUtil.memAlloc(length);
        try {
            source.put(frame, HEADER, frame.length - HEADER).flip();
            long decoded = Zstd.ZSTD_decompress(target, source);
            if (Zstd.ZSTD_isError(decoded) || decoded != length) throw new IOException("Invalid compressed section");
            byte[] canonical = new byte[length]; target.get(canonical);
            input = ByteBuffer.wrap(canonical).order(ByteOrder.LITTLE_ENDIAN);
        } finally {
            org.lwjgl.system.MemoryUtil.memFree(source); org.lwjgl.system.MemoryUtil.memFree(target);
        }
        int count = Short.toUnsignedInt(input.getShort());
        int bits = Math.max(1, 32 - Integer.numberOfLeadingZeros(count - 1));
        int perWord = 64 / bits;
        if (count == 0 || count > TerrainData.CELLS || input.remaining() != count * 9
                + ((TerrainData.CELLS + perWord - 1) / perWord) * 8)
            throw new IOException("Invalid section palette");
        int[] paletteBlocks = new int[count], paletteBiomes = new int[count];
        byte[] paletteLight = new byte[count];
        for (int i = 0; i < count; i++) {
            paletteBlocks[i] = input.getInt(); paletteBiomes[i] = input.getInt(); paletteLight[i] = input.get();
            if (paletteBlocks[i] < 0 || paletteBlocks[i] >= catalog.blocks.length
                    || paletteBiomes[i] < 0 || paletteBiomes[i] >= catalog.biomes.length)
                throw new IOException("Section palette references missing catalog entry");
        }
        int[] blocks = new int[TerrainData.CELLS], biomes = new int[TerrainData.CELLS];
        byte[] light = new byte[TerrainData.CELLS];
        long packed = 0, mask = (1L << bits) - 1;
        for (int i = 0; i < TerrainData.CELLS; i++) {
            if (i % perWord == 0) packed = input.getLong();
            int id = (int)((packed >>> ((i % perWord) * bits)) & mask);
            if (id >= count) throw new IOException("Invalid voxel palette index");
            blocks[i] = paletteBlocks[id]; biomes[i] = paletteBiomes[id]; light[i] = paletteLight[id];
        }
        return new TerrainData(key, Byte.toUnsignedInt(frame[72]), blocks, biomes, light, catalog.blocks, catalog.biomes);
    }
}
