package me.cortex.voxy.client.lod;

import me.cortex.voxy.client.core.model.CatalogMapper;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HexFormat;

final class RegionalSectionCodecBehaviorTest {
    static void run() throws Exception {
        int[] blocks = java.util.stream.IntStream.range(0, 32769).toArray();
        int[] biomes = java.util.stream.IntStream.range(0, 17).toArray();
        var mappings = new RegionalSectionCodec.Mappings(blocks, biomes);
        try (var codec = new RegionalSectionCodec()) {
            for (String line : Files.readAllLines(Path.of("test-fixtures/regional-section-cases.txt"))) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] f = line.split("\\s+");
                int count = Integer.parseInt(f[1]);
                int bits = count == 1 ? 0 : 32 - Integer.numberOfLeadingZeros(count - 1);
                ByteBuffer bytes = ByteBuffer.allocate(2 + count * 9 + 32768 * bits / 8)
                        .order(ByteOrder.LITTLE_ENDIAN);
                bytes.putShort((short) count);
                for (int i = 0; i < count; i++) {
                    int value = f[2].equals("duplicate") && i == 1 ? 0 : i;
                    bytes.putInt(value + 1).putInt(value % 17).put((byte) value);
                }
                int offset = bytes.position();
                int[] indexes = new int[32768];
                for (int i = 0; i < indexes.length; i++) {
                    int value = switch (f[2]) {
                        case "repeat" -> i / 2 % count;
                        case "first" -> i == 0 ? 1 : i % count;
                        case "skip" -> i == 1 ? 2 : i % count;
                        case "overflow" -> i == indexes.length - 1 ? count : i % count;
                        case "unused" -> i % (count - 1);
                        default -> i % count;
                    };
                    indexes[i] = value;
                    // Independent bit-at-a-time fixture packer, not the production word decoder.
                    for (int b = 0; b < bits; b++) {
                        int bit = i * bits + b;
                        int at = offset + bit / 8;
                        bytes.put(at, (byte) (bytes.get(at) | ((value >>> b) & 1) << (bit % 8)));
                    }
                }
                byte[] canonical = bytes.array();
                var fingerprint = fingerprint(canonical);
                check(HexFormat.of().formatHex(fingerprint.bytes()).equals(f[4]),
                        f[0] + " canonical bytes differ between Java and Rust fixtures");
                try {
                    var decoded = codec.decode(123, 0x65, canonical, fingerprint, mappings);
                    check(f[3].equals("true"), f[0] + " malformed palette accepted");
                    check(decoded.key() == 123 && decoded.childMask() == 0x65, "context changed");
                    for (int i = 0; i < indexes.length; i++) {
                        int value = indexes[i];
                        check(decoded.cells()[i] == CatalogMapper.composeMappingId((byte) value,
                                value + 1, value % 17), f[0] + " cell mismatch at " + i);
                    }
                } catch (IOException invalid) {
                    check(!f[3].equals("true"), f[0] + ": " + invalid);
                    check(!invalid.getMessage().contains("fingerprint"), "hash masked palette rejection");
                }
                if (f[0].equals("single")) {
                    reject(codec, canonical, fingerprint, new RegionalSectionCodec.Mappings(new int[1], biomes));
                    reject(codec, canonical, fingerprint, new RegionalSectionCodec.Mappings(blocks, new int[0]));
                    reject(codec, canonical, RegionalProtocol.Fingerprint.ZERO, mappings);
                    byte[] extended = Arrays.copyOf(canonical, canonical.length + 1);
                    reject(codec, extended, fingerprint(extended), mappings);
                }
            }
            for (byte[] malformed : new byte[][]{ {}, {1}, {0, 0} }) {
                reject(codec, malformed, fingerprint(malformed), mappings);
            }
        }
        System.out.println("shared Java/Rust palette fixture behavior tests passed");
    }

    private static RegionalProtocol.Fingerprint fingerprint(byte[] bytes) {
        return RegionalProtocol.Fingerprint.read(ByteBuffer.wrap(new Blake3.Hasher().update(bytes).digest())
                .order(ByteOrder.LITTLE_ENDIAN));
    }
    private static void reject(RegionalSectionCodec codec, byte[] bytes,
                               RegionalProtocol.Fingerprint hash, RegionalSectionCodec.Mappings mappings) throws Exception {
        try { codec.decode(0, 0, bytes, hash, mappings); }
        catch (IOException expected) { return; }
        throw new AssertionError("malformed section accepted");
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
