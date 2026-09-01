package me.cortex.voxy.client.lod;

import me.cortex.voxy.client.lod.ManifestCodec.ContentClass;
import me.cortex.voxy.client.lod.WireMessage.Hash256;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Canonical codecs for the dictionary set and its typed Zstd dictionaries. */
public final class DictionaryCodec {
    public static final int DICTIONARY_COUNT = 3;
    private static final int DICTIONARY_HEADER_BYTES = 14;
    private static final int MIN_DICTIONARY_BYTES = 1 << 10;
    private static final int MAX_DICTIONARY_BYTES = 64 << 10;
    private static final byte[] SET_MAGIC = "VXYDSET\0".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] DICTIONARY_MAGIC =
            "VXYDICT\0".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] ZSTD_DICTIONARY_MAGIC = {
            0x37, (byte) 0xa4, 0x30, (byte) 0xec
    };

    private DictionaryCodec() {}

    public record Dictionary(ContentClass contentClass, byte[] rawDictionaryBytes) {
        public Dictionary {
            Objects.requireNonNull(contentClass, "contentClass");
            rawDictionaryBytes = Objects.requireNonNull(
                    rawDictionaryBytes, "rawDictionaryBytes").clone();
        }

        @Override
        public byte[] rawDictionaryBytes() {
            return this.rawDictionaryBytes.clone();
        }

        byte[] rawBytesInternal() {
            return this.rawDictionaryBytes;
        }
    }

    public static List<Hash256> decodeSet(byte[] canonical) throws DecodeException {
        Objects.requireNonNull(canonical, "canonical");
        if (canonical.length != 12 + DICTIONARY_COUNT * WireMessage.HASH_BYTES) {
            throw new DecodeException("dictionary set has the wrong production size");
        }
        ByteBuffer input = ByteBuffer.wrap(canonical).order(ByteOrder.LITTLE_ENDIAN);
        byte[] magic = new byte[SET_MAGIC.length];
        input.get(magic);
        long count = Integer.toUnsignedLong(input.getInt());
        if (!Arrays.equals(magic, SET_MAGIC) || count != DICTIONARY_COUNT
                || input.remaining() != count * WireMessage.HASH_BYTES) {
            throw new DecodeException("invalid canonical dictionary set");
        }
        List<Hash256> hashes = new ArrayList<>(DICTIONARY_COUNT);
        Hash256 previous = null;
        for (int index = 0; index < DICTIONARY_COUNT; index++) {
            byte[] bytes = new byte[WireMessage.HASH_BYTES];
            input.get(bytes);
            Hash256 hash;
            try {
                hash = Hash256.fromBytes(bytes);
            } catch (IllegalArgumentException exception) {
                throw new DecodeException("dictionary set contains a reserved hash", exception);
            }
            if (previous != null && compare(previous, hash) >= 0) {
                throw new DecodeException("dictionary hashes are not strictly sorted");
            }
            hashes.add(hash);
            previous = hash;
        }
        return List.copyOf(hashes);
    }

    public static Dictionary decodeDictionary(byte[] canonical) throws DecodeException {
        Objects.requireNonNull(canonical, "canonical");
        if (canonical.length < DICTIONARY_HEADER_BYTES + MIN_DICTIONARY_BYTES
                || canonical.length > DICTIONARY_HEADER_BYTES + MAX_DICTIONARY_BYTES) {
            throw new DecodeException("compression dictionary is outside its byte bound");
        }
        ByteBuffer input = ByteBuffer.wrap(canonical).order(ByteOrder.LITTLE_ENDIAN);
        byte[] magic = new byte[DICTIONARY_MAGIC.length];
        input.get(magic);
        int classId = Byte.toUnsignedInt(input.get());
        int edge = Byte.toUnsignedInt(input.get());
        long rawLength = Integer.toUnsignedLong(input.getInt());
        if (!Arrays.equals(magic, DICTIONARY_MAGIC)
                || classId >= ContentClass.values().length || edge != MicrotileCodec.EDGE) {
            throw new DecodeException("invalid typed compression dictionary header");
        }
        if (rawLength < MIN_DICTIONARY_BYTES || rawLength > MAX_DICTIONARY_BYTES
                || rawLength != canonical.length - DICTIONARY_HEADER_BYTES) {
            throw new DecodeException("compression dictionary raw length disagrees");
        }
        byte[] raw = Arrays.copyOfRange(canonical, DICTIONARY_HEADER_BYTES, canonical.length);
        if (!startsWith(raw, ZSTD_DICTIONARY_MAGIC)) {
            throw new DecodeException("raw Zstd dictionary has invalid magic");
        }
        return new Dictionary(ContentClass.values()[classId], raw);
    }

    private static boolean startsWith(byte[] bytes, byte[] prefix) {
        if (bytes.length < prefix.length) return false;
        for (int index = 0; index < prefix.length; index++) {
            if (bytes[index] != prefix[index]) return false;
        }
        return true;
    }

    private static int compare(Hash256 first, Hash256 second) {
        byte[] left = first.toBytes();
        byte[] right = second.toBytes();
        for (int index = 0; index < left.length; index++) {
            int compared = Integer.compare(Byte.toUnsignedInt(left[index]),
                    Byte.toUnsignedInt(right[index]));
            if (compared != 0) return compared;
        }
        return 0;
    }

    public static final class DecodeException extends Exception {
        public DecodeException(String message) { super(message); }
        public DecodeException(String message, Throwable cause) { super(message, cause); }
    }
}
