package me.cortex.voxy.client.lod;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32C;

/** Immutable values and bounds shared by the QUIC control and object streams. */
public final class WireMessage {
    public static final int HASH_BYTES = 32;
    public static final int MAX_DIMENSION_BYTES = 1024;
    public static final int MAX_REQUEST_ENTRIES = 256;
    public static final int MAX_COMPRESSED_OBJECT_BYTES = 16 * 1024 * 1024;
    public static final int MAX_CANONICAL_OBJECT_BYTES = 64 * 1024 * 1024;

    private WireMessage() {}

    public enum ObjectKind {
        EXTERIOR_MICROTILE(1),
        INTERIOR_MICROTILE(2),
        COMPLEX_MICROTILE(3),
        MANIFEST_SUBTREE(4),
        ROOT_DIRECTORY(5),
        CATALOG(6),
        COMPRESSION_DICTIONARY(7),
        DICTIONARY_SET(8),
        VISIBILITY_DIRECTORY(9),
        VISIBILITY_PAGE(10),
        VISIBILITY_SUMMARY_PAGE(11),
        MANIFEST_DESCRIPTOR_PAGE(13);

        private final int wireId;

        ObjectKind(int wireId) {
            this.wireId = wireId;
        }

        public int wireId() {
            return this.wireId;
        }

        public static ObjectKind fromWireId(int value) {
            for (ObjectKind kind : values()) {
                if (kind.wireId == value) return kind;
            }
            throw new IllegalArgumentException("unknown object kind " + value);
        }
    }

    /** Four opaque little-endian words containing an exact BLAKE3-256 output. */
    public record Hash256(long word0, long word1, long word2, long word3) {
        public Hash256 {
            if ((word0 | word1 | word2 | word3) == 0) {
                throw new IllegalArgumentException("the all-zero object hash is reserved");
            }
        }

        public static Hash256 fromBytes(byte[] bytes) {
            Objects.requireNonNull(bytes, "bytes");
            if (bytes.length != HASH_BYTES) {
                throw new IllegalArgumentException("content hash must be exactly 32 bytes");
            }
            ByteBuffer input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            return new Hash256(input.getLong(), input.getLong(), input.getLong(), input.getLong());
        }

        public byte[] toBytes() {
            return ByteBuffer.allocate(HASH_BYTES).order(ByteOrder.LITTLE_ENDIAN)
                    .putLong(this.word0).putLong(this.word1)
                    .putLong(this.word2).putLong(this.word3).array();
        }
    }

    /** Immutable root capability; unsigned generation establishes freshness. */
    public record RootToken(long generation, Hash256 dimensionHash, Hash256 rootHash) {
        public RootToken {
            if (generation == 0) {
                throw new IllegalArgumentException("root generation zero is reserved");
            }
            Objects.requireNonNull(dimensionHash, "dimensionHash");
            Objects.requireNonNull(rootHash, "rootHash");
        }
    }

    public record RootAnnounce(String dimension, RootToken root,
                               Hash256 catalogHash, Hash256 dictionarySetHash,
                               Hash256 visibilityDirectoryHash) {
        public RootAnnounce {
            validateDimension(dimension);
            Objects.requireNonNull(root, "root");
            Objects.requireNonNull(catalogHash, "catalogHash");
            Objects.requireNonNull(dictionarySetHash, "dictionarySetHash");
            Objects.requireNonNull(visibilityDirectoryHash, "visibilityDirectoryHash");
        }
    }

    /** One independently addressable compressed canonical object. */
    public static final class EncodedObject implements AutoCloseable {
        private final Hash256 hash;
        private final ObjectKind kind;
        private final int dictionaryId;
        private final int canonicalLength;
        private final int compressedChecksum;
        private final Body compressed;

        public EncodedObject(Hash256 hash, ObjectKind kind, int dictionaryId,
                             int canonicalLength, int compressedChecksum,
                             byte[] compressedBytes) {
            this(hash, kind, dictionaryId, canonicalLength, compressedChecksum,
                    new Body(ByteBuffer.wrap(compressedBytes.clone()), () -> {}), true);
        }

        /** Transfers an otherwise unshared completed QUIC body directly to the decoder. */
        static EncodedObject takeOwnership(Hash256 hash, ObjectKind kind, int dictionaryId,
                                           int canonicalLength, int compressedChecksum,
                                           byte[] compressedBytes) {
            return new EncodedObject(hash, kind, dictionaryId, canonicalLength,
                    compressedChecksum,
                    new Body(ByteBuffer.wrap(compressedBytes), () -> {}), true);
        }

        /** The QUIC parser has already incrementally verified the declared CRC32C. */
        static EncodedObject takeVerifiedOwnership(Hash256 hash, ObjectKind kind,
                                                   int dictionaryId, int canonicalLength,
                                                   int compressedChecksum,
                                                   ByteBuffer compressedBytes,
                                                   Runnable releaser) {
            Body body = new Body(compressedBytes, releaser);
            try {
                return new EncodedObject(hash, kind, dictionaryId, canonicalLength,
                        compressedChecksum, body, false);
            } catch (RuntimeException | Error failure) {
                body.release();
                throw failure;
            }
        }

        private EncodedObject(Hash256 hash, ObjectKind kind, int dictionaryId,
                              int canonicalLength, int compressedChecksum,
                              Body compressed, boolean verifyChecksum) {
            this.hash = Objects.requireNonNull(hash, "hash");
            this.kind = Objects.requireNonNull(kind, "kind");
            if (!isClientServiceableObject(kind)) {
                throw new IllegalArgumentException("server-internal object is not serviceable");
            }
            boolean microtile = kind == ObjectKind.EXTERIOR_MICROTILE
                    || kind == ObjectKind.INTERIOR_MICROTILE
                    || kind == ObjectKind.COMPLEX_MICROTILE;
            if (microtile != (dictionaryId != 0)) {
                throw new IllegalArgumentException(
                        "only final content microtiles require a dictionary ID");
            }
            if (canonicalLength < 0 || canonicalLength > MAX_CANONICAL_OBJECT_BYTES) {
                throw new IllegalArgumentException("canonical object length is out of bounds");
            }
            this.compressed = Objects.requireNonNull(compressed, "compressed");
            int compressedLength = compressed.length();
            if (compressedLength == 0 || compressedLength > MAX_COMPRESSED_OBJECT_BYTES) {
                throw new IllegalArgumentException("compressed object is too large");
            }
            if (verifyChecksum && checksum(compressed.buffer()) != compressedChecksum) {
                throw new IllegalArgumentException("compressed object checksum mismatch");
            }
            this.dictionaryId = dictionaryId;
            this.canonicalLength = canonicalLength;
            this.compressedChecksum = compressedChecksum;
        }

        public static EncodedObject create(Hash256 hash, ObjectKind kind, int dictionaryId,
                                           int canonicalLength, byte[] compressedBytes) {
            return new EncodedObject(hash, kind, dictionaryId, canonicalLength,
                    checksum(compressedBytes), compressedBytes);
        }

        public Hash256 hash() { return this.hash; }
        public ObjectKind kind() { return this.kind; }
        public int dictionaryId() { return this.dictionaryId; }
        public int canonicalLength() { return this.canonicalLength; }
        public int compressedLength() { return this.compressed.length(); }
        public int compressedChecksum() { return this.compressedChecksum; }
        public byte[] compressedBytes() {
            ByteBuffer source = this.compressed.buffer();
            byte[] copy = new byte[source.remaining()];
            source.get(copy);
            return copy;
        }
        ByteBuffer compressedBufferInternal() { return this.compressed.buffer(); }

        EncodedObject retain() {
            this.compressed.retain();
            return this;
        }

        @Override
        public void close() {
            this.compressed.release();
        }

        @Override
        public boolean equals(Object other) {
            return this == other || other instanceof EncodedObject object
                    && this.dictionaryId == object.dictionaryId
                    && this.canonicalLength == object.canonicalLength
                    && this.compressedChecksum == object.compressedChecksum
                    && this.hash.equals(object.hash) && this.kind == object.kind
                    && this.compressed.buffer().equals(object.compressed.buffer());
        }

        @Override
        public int hashCode() {
            int result = Objects.hash(this.hash, this.kind, this.dictionaryId,
                    this.canonicalLength, this.compressedChecksum);
            return 31 * result + this.compressed.buffer().hashCode();
        }

        @Override
        public String toString() {
            return "EncodedObject[hash=" + this.hash + ", kind=" + this.kind
                    + ", dictionaryId=" + Integer.toUnsignedString(this.dictionaryId)
                    + ", canonicalLength=" + this.canonicalLength
                    + ", compressedLength=" + this.compressed.length() + ']';
        }

        private static final class Body {
            private final ByteBuffer bytes;
            private final Runnable releaser;
            private final AtomicInteger references = new AtomicInteger(1);

            private Body(ByteBuffer bytes, Runnable releaser) {
                this.bytes = Objects.requireNonNull(bytes, "bytes").slice().asReadOnlyBuffer();
                this.releaser = Objects.requireNonNull(releaser, "releaser");
            }

            private int length() {
                return this.bytes.remaining();
            }

            private ByteBuffer buffer() {
                if (this.references.get() <= 0) {
                    throw new IllegalStateException("compressed object body was released");
                }
                return this.bytes.asReadOnlyBuffer();
            }

            private void retain() {
                int references;
                do {
                    references = this.references.get();
                    if (references <= 0) throw new IllegalStateException(
                            "compressed object body was released");
                } while (!this.references.compareAndSet(references, references + 1));
            }

            private void release() {
                int references = this.references.decrementAndGet();
                if (references == 0) this.releaser.run();
                else if (references < 0) throw new IllegalStateException(
                        "compressed object body released more than retained");
            }
        }
    }

    public static int checksum(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        CRC32C checksum = new CRC32C();
        checksum.update(bytes, 0, bytes.length);
        return (int) checksum.getValue();
    }

    static int checksum(ByteBuffer bytes) {
        CRC32C checksum = new CRC32C();
        checksum.update(bytes.duplicate());
        return (int) checksum.getValue();
    }

    private static void validateDimension(String dimension) {
        Objects.requireNonNull(dimension, "dimension");
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(dimension));
            if (!encoded.hasRemaining() || encoded.remaining() > MAX_DIMENSION_BYTES) {
                throw new IllegalArgumentException("dimension name is out of bounds");
            }
        } catch (CharacterCodingException error) {
            throw new IllegalArgumentException("dimension name is not valid UTF-8", error);
        }
    }

    private static boolean isClientServiceableObject(ObjectKind kind) {
        return kind != ObjectKind.VISIBILITY_DIRECTORY
                && kind != ObjectKind.VISIBILITY_PAGE
                && kind != ObjectKind.VISIBILITY_SUMMARY_PAGE;
    }
}
