package me.cortex.voxy.client.lod;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Compact unkeyed BLAKE3-256 implementation used to authenticate canonical records. */
public final class Blake3 {
    private static final int BLOCK_BYTES = 64;
    private static final int CHUNK_BYTES = 1024;
    private static final int CHUNK_START = 1;
    private static final int CHUNK_END = 2;
    private static final int PARENT = 4;
    private static final int ROOT = 8;
    private static final int[] IV = {
            0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a,
            0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19
    };
    private static final int[] MESSAGE_PERMUTATION = {
            2, 6, 3, 10, 7, 0, 4, 13, 1, 11, 12, 5, 9, 14, 15, 8
    };

    private Blake3() {}

    public static byte[] hash(byte[] input) {
        return new Hasher().update(input).digest();
    }

    /** Incremental, allocation-bounded hasher. Instances are intentionally not thread-safe. */
    public static final class Hasher {
        private final byte[] chunk = new byte[CHUNK_BYTES];
        private final List<int[]> chainingStack = new ArrayList<>(54);
        private int chunkLength;
        private long completeChunks;
        private boolean finalized;

        public Hasher update(byte[] input) {
            Objects.requireNonNull(input, "input");
            return update(input, 0, input.length);
        }

        public Hasher update(byte[] input, int offset, int length) {
            Objects.requireNonNull(input, "input");
            Objects.checkFromIndexSize(offset, length, input.length);
            if (this.finalized) throw new IllegalStateException("BLAKE3 hasher is finalized");
            int cursor = offset;
            int remaining = length;
            while (remaining > 0) {
                if (this.chunkLength == CHUNK_BYTES) pushCompleteChunk();
                int copied = Math.min(remaining, CHUNK_BYTES - this.chunkLength);
                System.arraycopy(input, cursor, this.chunk, this.chunkLength, copied);
                this.chunkLength += copied;
                cursor += copied;
                remaining -= copied;
            }
            return this;
        }

        public byte[] digest() {
            if (this.finalized) throw new IllegalStateException("BLAKE3 hasher is finalized");
            this.finalized = true;
            Output output = chunkOutput(this.chunk, this.chunkLength, this.completeChunks);
            for (int index = this.chainingStack.size() - 1; index >= 0; index--) {
                output = parentOutput(this.chainingStack.get(index), output.chainingValue());
            }
            return output.rootHash();
        }

        private void pushCompleteChunk() {
            int[] chainingValue = chunkOutput(this.chunk, CHUNK_BYTES, this.completeChunks)
                    .chainingValue();
            this.completeChunks = Math.addExact(this.completeChunks, 1);
            long totalChunks = this.completeChunks;
            while ((totalChunks & 1) == 0) {
                int[] left = this.chainingStack.removeLast();
                chainingValue = parentOutput(left, chainingValue).chainingValue();
                totalChunks >>>= 1;
            }
            this.chainingStack.add(chainingValue);
            this.chunkLength = 0;
        }
    }

    private static Output chunkOutput(byte[] chunk, int length, long chunkCounter) {
        int[] chainingValue = IV.clone();
        int blockCount = Math.max(1, Math.floorDiv(length + BLOCK_BYTES - 1, BLOCK_BYTES));
        for (int block = 0; block < blockCount - 1; block++) {
            int flags = block == 0 ? CHUNK_START : 0;
            chainingValue = firstEight(compress(chainingValue,
                    words(chunk, block * BLOCK_BYTES, BLOCK_BYTES),
                    chunkCounter, BLOCK_BYTES, flags));
        }
        int lastOffset = (blockCount - 1) * BLOCK_BYTES;
        int lastLength = length - lastOffset;
        int flags = CHUNK_END | (blockCount == 1 ? CHUNK_START : 0);
        return new Output(chainingValue, words(chunk, lastOffset, lastLength),
                chunkCounter, lastLength, flags);
    }

    private static Output parentOutput(int[] left, int[] right) {
        int[] block = new int[16];
        System.arraycopy(left, 0, block, 0, 8);
        System.arraycopy(right, 0, block, 8, 8);
        return new Output(IV, block, 0, BLOCK_BYTES, PARENT);
    }

    private static int[] compress(int[] chainingValue, int[] block, long counter,
                                  int blockLength, int flags) {
        int[] state = new int[16];
        System.arraycopy(chainingValue, 0, state, 0, 8);
        System.arraycopy(IV, 0, state, 8, 4);
        state[12] = (int) counter;
        state[13] = (int) (counter >>> 32);
        state[14] = blockLength;
        state[15] = flags;
        int[] schedule = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15};
        for (int round = 0; round < 7; round++) {
            round(state, block, schedule);
            int[] permuted = new int[16];
            for (int index = 0; index < 16; index++) {
                permuted[index] = schedule[MESSAGE_PERMUTATION[index]];
            }
            schedule = permuted;
        }
        int[] output = new int[16];
        for (int index = 0; index < 8; index++) {
            output[index] = state[index] ^ state[index + 8];
            output[index + 8] = state[index + 8] ^ chainingValue[index];
        }
        return output;
    }

    private static void round(int[] state, int[] message, int[] schedule) {
        mix(state, 0, 4, 8, 12, message[schedule[0]], message[schedule[1]]);
        mix(state, 1, 5, 9, 13, message[schedule[2]], message[schedule[3]]);
        mix(state, 2, 6, 10, 14, message[schedule[4]], message[schedule[5]]);
        mix(state, 3, 7, 11, 15, message[schedule[6]], message[schedule[7]]);
        mix(state, 0, 5, 10, 15, message[schedule[8]], message[schedule[9]]);
        mix(state, 1, 6, 11, 12, message[schedule[10]], message[schedule[11]]);
        mix(state, 2, 7, 8, 13, message[schedule[12]], message[schedule[13]]);
        mix(state, 3, 4, 9, 14, message[schedule[14]], message[schedule[15]]);
    }

    private static void mix(int[] state, int a, int b, int c, int d, int x, int y) {
        state[a] += state[b] + x;
        state[d] = Integer.rotateRight(state[d] ^ state[a], 16);
        state[c] += state[d];
        state[b] = Integer.rotateRight(state[b] ^ state[c], 12);
        state[a] += state[b] + y;
        state[d] = Integer.rotateRight(state[d] ^ state[a], 8);
        state[c] += state[d];
        state[b] = Integer.rotateRight(state[b] ^ state[c], 7);
    }

    private static int[] words(byte[] input, int offset, int length) {
        int[] words = new int[16];
        for (int index = 0; index < length; index++) {
            words[index >>> 2] |= Byte.toUnsignedInt(input[offset + index]) << ((index & 3) * 8);
        }
        return words;
    }

    private static int[] firstEight(int[] words) {
        return Arrays.copyOf(words, 8);
    }

    private record Output(int[] inputChainingValue, int[] blockWords, long counter,
                          int blockLength, int flags) {
        private Output {
            inputChainingValue = inputChainingValue.clone();
            blockWords = blockWords.clone();
        }

        private int[] chainingValue() {
            return firstEight(compress(this.inputChainingValue, this.blockWords, this.counter,
                    this.blockLength, this.flags));
        }

        private byte[] rootHash() {
            int[] words = compress(this.inputChainingValue, this.blockWords, 0,
                    this.blockLength, this.flags | ROOT);
            byte[] hash = new byte[32];
            for (int index = 0; index < 8; index++) {
                int word = words[index];
                hash[index * 4] = (byte) word;
                hash[index * 4 + 1] = (byte) (word >>> 8);
                hash[index * 4 + 2] = (byte) (word >>> 16);
                hash[index * 4 + 3] = (byte) (word >>> 24);
            }
            return hash;
        }
    }
}
