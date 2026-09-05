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
                    check(Arrays.equals(decoded.usedBlocks(), java.util.stream.IntStream.rangeClosed(1, count).toArray()),
                            f[0] + " used-block sequence changed");
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
            localBlockOrder(codec);
        }
        System.out.println("shared Java/Rust palette fixture behavior tests passed");
    }

    private static void localBlockOrder(RegionalSectionCodec codec) throws Exception {
        int[] local = new int[64], sequence = {4096, 0, 900, 4096, 130, 0, 900};
        for (int i = 0; i < local.length; i++) local[i] = sequence[i % sequence.length];
        var mappings = new RegionalSectionCodec.Mappings(local, new int[]{7, 9});
        for (int count : new int[]{1, 3, 5, 257, 32768}) {
            int bits = count == 1 ? 0 : 32 - Integer.numberOfLeadingZeros(count - 1);
            ByteBuffer bytes = ByteBuffer.allocate(2 + 9 * count + 32768 * bits / 8).order(ByteOrder.LITTLE_ENDIAN);
            bytes.putShort((short) count);
            for (int i = 0; i < count; i++) bytes.putInt(i % 64).putInt(i / 64 % 2).put((byte) (i / 128));
            int offset = bytes.position();
            for (int i = 0; i < 32768; i++) for (int b = 0; b < bits; b++) {
                int bit = i * bits + b, at = offset + bit / 8;
                bytes.put(at, (byte) (bytes.get(at) | ((i % count >>> b) & 1) << (bit % 8)));
            }
            byte[] canonical = bytes.array(); var hash = fingerprint(canonical);
            var result = codec.decode(456, 0xa5, canonical, hash, mappings);
            int[] expected = count == 1 ? new int[]{4096} : count == 3 ? new int[]{4096, 900} : new int[]{4096, 900, 130};
            check(Arrays.equals(expected, result.usedBlocks()), "local alias/air/order mismatch for palette " + count);
            check(result.key() == 456 && result.childMask() == 0xa5, "mapped context changed");
            for (int i = 0; i < 32768; i++) {
                int id = i % count;
                check(result.cells()[i] == CatalogMapper.composeMappingId((byte) (id / 128), local[id % 64], id / 64 % 2 == 0 ? 7 : 9),
                        "mapped cell changed at " + i);
            }
            var air = codec.decode(456, 0xa5, canonical, hash, new RegionalSectionCodec.Mappings(new int[64], new int[]{7, 9}));
            check(air.usedBlocks().length == 0, "zero-mapped remote states requested model bakes");
            byte[] corrupted = canonical.clone(); corrupted[corrupted.length - 1] ^= 1;
            try { codec.decode(456, 0xa5, corrupted, hash, mappings); throw new AssertionError("corrupt fingerprint accepted"); }
            catch (IOException expectedFailure) { check(expectedFailure.getMessage().contains("fingerprint"), "corruption bypassed authentication"); }
        }
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
