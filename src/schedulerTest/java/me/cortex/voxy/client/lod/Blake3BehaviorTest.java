package me.cortex.voxy.client.lod;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

final class Blake3BehaviorTest {
    private record Vector(byte[] input, byte[] hash) {}

    static void run() throws Exception {
        var vectors = new ArrayList<Vector>();
        for (String line : Files.readAllLines(Path.of("test-fixtures/blake3-unkeyed-cases.txt"))) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] fields = line.split("\\s+");
            byte[] input = new byte[Integer.parseInt(fields[0])];
            for (int i = 0; i < input.length; i++) input[i] = (byte) (i % 251);
            var vector = new Vector(input, HexFormat.of().parseHex(fields[1]));
            vectors.add(vector);
            check(vector.hash, Blake3.hash(input));
            var bytewise = new Blake3.Hasher();
            byte[] single = new byte[1];
            for (byte value : input) { single[0] = value; bytewise.update(single); }
            check(vector.hash, bytewise.digest());
            rejectFinalized(bytewise);
            byte[] padded = new byte[input.length + 13];
            System.arraycopy(input, 0, padded, 7, input.length);
            var split = new Blake3.Hasher();
            int cursor = 0, iteration = 0;
            int[] sizes = {1, 63, 1024, 17, 65, 4096};
            while (cursor < input.length) {
                split.update(padded, cursor + 7, 0);
                int count = Math.min(sizes[iteration++ % sizes.length], input.length - cursor);
                split.update(padded, cursor + 7, count); cursor += count;
            }
            split.update(padded, input.length + 7, 0);
            Arrays.fill(padded, (byte) 99); // update owns the bytes it still needs.
            check(vector.hash, split.digest());
            rejectFinalized(split);
            byte[] original = input.clone();
            check(vector.hash, Blake3.hash(input));
            if (!Arrays.equals(original, input)) throw new AssertionError("hash mutated input");
        }
        var executor = Executors.newFixedThreadPool(4);
        try {
            var jobs = new ArrayList<Callable<Void>>();
            for (Vector vector : vectors) jobs.add(() -> {
                for (int repeat = 0; repeat < 8; repeat++) {
                    byte[] result = Blake3.hash(vector.input);
                    check(vector.hash, result); Arrays.fill(result, (byte) 0);
                }
                return null;
            });
            for (var result : executor.invokeAll(jobs, 30, TimeUnit.SECONDS)) result.get();
        } finally { executor.shutdownNow(); }
        System.out.println("full Rust-cross-checked BLAKE3 vectors, splits, ownership, finalization and concurrent-instance tests passed");
    }

    private static void rejectFinalized(Blake3.Hasher hasher) {
        try { hasher.digest(); throw new AssertionError("second digest accepted"); }
        catch (IllegalStateException expected) {}
        try { hasher.update(new byte[0]); throw new AssertionError("finalized update accepted"); }
        catch (IllegalStateException expected) {}
    }
    private static void check(byte[] expected, byte[] actual) {
        if (!Arrays.equals(expected, actual)) throw new AssertionError("BLAKE3 digest mismatch");
    }
}
