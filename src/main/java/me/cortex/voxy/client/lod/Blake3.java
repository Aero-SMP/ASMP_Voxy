package me.cortex.voxy.client.lod;

import java.util.Arrays;
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
    private static final int[][] ROUND_SCHEDULES = roundSchedules();

    private static int[][] roundSchedules() {
        int[][] schedules = new int[7][16];
        for (int index = 0; index < 16; index++) schedules[0][index] = index;
        for (int round = 1; round < 7; round++) {
            for (int index = 0; index < 16; index++) {
                schedules[round][index] = schedules[round - 1][MESSAGE_PERMUTATION[index]];
            }
        }
        return schedules;
    }

    private Blake3() {}

    public static byte[] hash(byte[] input) {
        return new Hasher().update(input).digest();
    }

    /** Incremental, allocation-bounded hasher. Instances are intentionally not thread-safe. */
    public static final class Hasher {
        private final byte[] chunk = new byte[CHUNK_BYTES];
        // A long chunk counter needs at most one retained chaining value per bit.
        private final int[][] chainingStack = new int[Long.SIZE][];
        private final int[] chainingValue = new int[8];
        private final int[] message = new int[16];
        private final int[] state = new int[16];
        private int stackDepth;
        private int chunkLength;
        private long completeChunks;
        private long outputCounter;
        private int outputLength, outputFlags;
        private boolean finalized;

        /** The sole owner may reuse this workspace after its previous operation has ended. */
        public Hasher reset() {
            this.chunkLength = 0;
            this.completeChunks = 0;
            this.stackDepth = 0;
            this.outputCounter = 0;
            this.outputLength = this.outputFlags = 0;
            this.finalized = false;
            // Chunk bytes and stack slots beyond their logical extents are never read.
            // Each message, chaining value and compression state is overwritten before use.
            return this;
        }

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
            chunkOutput(this.chunkLength, this.completeChunks);
            while (this.stackDepth != 0) {
                outputChainingValue();
                parentOutput(this.chainingStack[--this.stackDepth]);
            }
            compress(0, this.outputLength, this.outputFlags | ROOT);
            byte[] hash = new byte[32];
            for (int index = 0; index < 8; index++) {
                int word = this.state[index];
                hash[index * 4] = (byte) word;
                hash[index * 4 + 1] = (byte) (word >>> 8);
                hash[index * 4 + 2] = (byte) (word >>> 16);
                hash[index * 4 + 3] = (byte) (word >>> 24);
            }
            return hash;
        }

        private void pushCompleteChunk() {
            chunkOutput(CHUNK_BYTES, this.completeChunks);
            outputChainingValue();
            this.completeChunks = Math.addExact(this.completeChunks, 1);
            long totalChunks = this.completeChunks;
            while ((totalChunks & 1) == 0) {
                parentOutput(this.chainingStack[--this.stackDepth]);
                outputChainingValue();
                totalChunks >>>= 1;
            }
            int[] slot = this.chainingStack[this.stackDepth];
            if (slot == null) this.chainingStack[this.stackDepth] = slot = new int[8];
            System.arraycopy(this.chainingValue, 0, slot, 0, 8);
            this.stackDepth++;
            this.chunkLength = 0;
        }

        private void chunkOutput(int length, long chunkCounter) {
            System.arraycopy(IV, 0, this.chainingValue, 0, 8);
            int blockCount = Math.max(1, Math.floorDiv(length + BLOCK_BYTES - 1, BLOCK_BYTES));
            for (int block = 0; block < blockCount - 1; block++) {
                words(block * BLOCK_BYTES, BLOCK_BYTES);
                compress(chunkCounter, BLOCK_BYTES, block == 0 ? CHUNK_START : 0);
                System.arraycopy(this.state, 0, this.chainingValue, 0, 8);
            }
            int lastOffset = (blockCount - 1) * BLOCK_BYTES;
            words(lastOffset, length - lastOffset);
            this.outputCounter = chunkCounter;
            this.outputLength = length - lastOffset;
            this.outputFlags = CHUNK_END | (blockCount == 1 ? CHUNK_START : 0);
        }

        private void parentOutput(int[] left) {
            System.arraycopy(left, 0, this.message, 0, 8);
            System.arraycopy(this.chainingValue, 0, this.message, 8, 8);
            System.arraycopy(IV, 0, this.chainingValue, 0, 8);
            this.outputCounter = 0;
            this.outputLength = BLOCK_BYTES;
            this.outputFlags = PARENT;
        }

        private void outputChainingValue() {
            compress(this.outputCounter, this.outputLength, this.outputFlags);
            System.arraycopy(this.state, 0, this.chainingValue, 0, 8);
        }

        private void compress(long counter, int blockLength, int flags) {
            System.arraycopy(this.chainingValue, 0, this.state, 0, 8);
            System.arraycopy(IV, 0, this.state, 8, 4);
            this.state[12] = (int) counter;
            this.state[13] = (int) (counter >>> 32);
            this.state[14] = blockLength;
            this.state[15] = flags;
            for (int round = 0; round < 7; round++) {
                round(this.state, this.message, ROUND_SCHEDULES[round]);
            }
            for (int index = 0; index < 8; index++) {
                int upper = this.state[index + 8];
                this.state[index] ^= upper;
                this.state[index + 8] = upper ^ this.chainingValue[index];
            }
        }

        private void words(int offset, int length) {
            Arrays.fill(this.message, 0);
            for (int index = 0; index < length; index++) {
                this.message[index >>> 2] |= Byte.toUnsignedInt(this.chunk[offset + index]) << ((index & 3) * 8);
            }
        }
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
}
