package me.cortex.voxy.client.lod;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.zip.CRC32C;

/** Bounded payload codecs for the terrain stream. */
public final class WireMessage {
    public static final int C_HELLO = 0x0001;
    public static final int C_PING = 0x0003;
    public static final int C_CREDIT = 0x0005;
    public static final int C_SUBTREE_REQUEST = 0x0006;
    public static final int C_OBJECT_REQUEST = 0x0007;
    public static final int C_ROOT_READY = 0x0008;
    public static final int C_CAMERA_DOMAIN = 0x0009;
    public static final int S_HELLO = 0x8001;
    public static final int S_PONG = 0x8005;
    public static final int S_ROOT_ANNOUNCE = 0x8007;
    public static final int S_SUBTREE_DATA = 0x8008;
    public static final int S_OBJECT_BUNDLE = 0x8009;
    public static final int S_CAMERA_DOMAIN = 0x800a;
    public static final int S_ERROR = 0x80ff;

    public static final int HASH_BYTES = 32;
    private static final int ROOT_TOKEN_BYTES = Long.BYTES + HASH_BYTES * 2;
    private static final int OBJECT_HEADER_BYTES = HASH_BYTES + 17;
    public static final int MAX_FRAME_PAYLOAD = 16 * 1024 * 1024;
    public static final int MAX_DIMENSION_BYTES = 1024;
    public static final int MAX_REQUEST_ENTRIES = 256;
    public static final int MAX_BUNDLE_ENTRIES = 256;
    /** Largest object that can be the sole entry in a legal object-data frame. */
    public static final int MAX_COMPRESSED_OBJECT_BYTES = MAX_FRAME_PAYLOAD
            - ROOT_TOKEN_BYTES - Short.BYTES - OBJECT_HEADER_BYTES;
    public static final int MAX_CANONICAL_OBJECT_BYTES = 64 * 1024 * 1024;
    public static final int MAX_MANIFEST_BYTES = 16 * 1024 * 1024;
    private WireMessage() {}

    /** Selects the canonical dimension for this terrain connection. */
    public static byte[] encodeHello(String dimension) {
        validateDimension(dimension);
        PayloadWriter output = new PayloadWriter();
        output.putString(dimension);
        return output.toByteArray();
    }

    public sealed interface Message permits RootAnnounce, SubtreeRequest, SubtreeData,
            ObjectRequest, ObjectBundle, RootReady {
        int frameType();
    }

    public enum ObjectKind {
        EXTERIOR_MICROTILE(1),
        INTERIOR_MICROTILE(2),
        COMPLEX_MICROTILE(3),
        MANIFEST_SUBTREE(4),
        ROOT_DIRECTORY(5),
        CATALOG(6),
        COMPRESSION_DICTIONARY(7),
        DICTIONARY_SET(8),
        /** Root-bound visibility policy metadata; rendering membership stays in manifests. */
        VISIBILITY_DIRECTORY(9),
        /** Server-owned bounded visibility lookup page. Never selected as terrain content. */
        VISIBILITY_PAGE(10),
        /** Server-owned regional connectivity summary. Never requested by the client. */
        VISIBILITY_SUMMARY_PAGE(11),
        /** Bounded content descriptors for one fixed range in a five-level manifest. */
        MANIFEST_DESCRIPTOR_PAGE(13);

        private final int wireId;

        ObjectKind(int wireId) {
            this.wireId = wireId;
        }

        public int wireId() {
            return this.wireId;
        }

        private static ObjectKind decode(int value) throws DecodeException {
            for (ObjectKind kind : values()) {
                if (kind.wireId == value) return kind;
            }
            throw new DecodeException("unknown object kind " + value);
        }
    }

    /** Four opaque 64-bit words whose byte representation is exactly the BLAKE3-256 output. */
    public record Hash256(long word0, long word1, long word2, long word3) {
        public Hash256 {
            if ((word0 | word1 | word2 | word3) == 0) {
                throw new IllegalArgumentException("the all-zero object hash is reserved");
            }
        }

        public static Hash256 fromBytes(byte[] bytes) {
            Objects.requireNonNull(bytes, "bytes");
            if (bytes.length != HASH_BYTES) {
                throw new IllegalArgumentException("content hash must contain exactly " + HASH_BYTES + " bytes");
            }
            ByteBuffer input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            return new Hash256(input.getLong(), input.getLong(), input.getLong(), input.getLong());
        }

        public byte[] toBytes() {
            return ByteBuffer.allocate(HASH_BYTES).order(ByteOrder.LITTLE_ENDIAN)
                    .putLong(this.word0).putLong(this.word1).putLong(this.word2).putLong(this.word3)
                    .array();
        }
    }

    /**
     * Identifies one immutable root while its unsigned generation establishes freshness.
     * The dimension identity is part of every capability so equal sparse root directories in
     * two dimensions can never authorize each other's objects.
     */
    public record RootToken(long generation, Hash256 dimensionHash, Hash256 rootHash) {
        public RootToken {
            if (generation == 0) throw new IllegalArgumentException("root generation zero is reserved");
            Objects.requireNonNull(dimensionHash, "dimensionHash");
            Objects.requireNonNull(rootHash, "rootHash");
        }
    }

    public record RootAnnounce(String dimension, RootToken root,
                               Hash256 catalogHash, Hash256 dictionarySetHash,
                               Hash256 visibilityDirectoryHash) implements Message {
        public RootAnnounce {
            validateDimension(dimension);
            Objects.requireNonNull(root, "root");
            Objects.requireNonNull(catalogHash, "catalogHash");
            Objects.requireNonNull(dictionarySetHash, "dictionarySetHash");
            Objects.requireNonNull(visibilityDirectoryHash, "visibilityDirectoryHash");
        }

        @Override
        public int frameType() {
            return S_ROOT_ANNOUNCE;
        }
    }

    public record SubtreeRequest(RootToken root, List<Hash256> hashes) implements Message {
        public SubtreeRequest {
            Objects.requireNonNull(root, "root");
            hashes = immutableUniqueHashes(hashes, MAX_REQUEST_ENTRIES);
        }

        @Override
        public int frameType() {
            return C_SUBTREE_REQUEST;
        }
    }

    public record SubtreeData(RootToken root, List<EncodedObject> entries) implements Message {
        public SubtreeData {
            Objects.requireNonNull(root, "root");
            entries = immutableUniqueObjects(entries, true);
        }

        @Override
        public int frameType() {
            return S_SUBTREE_DATA;
        }
    }

    public record ObjectRequest(RootToken root, List<Hash256> hashes) implements Message {
        public ObjectRequest {
            Objects.requireNonNull(root, "root");
            hashes = immutableUniqueHashes(hashes, MAX_REQUEST_ENTRIES);
        }

        @Override
        public int frameType() {
            return C_OBJECT_REQUEST;
        }
    }

    public record ObjectBundle(RootToken root, List<EncodedObject> entries) implements Message {
        public ObjectBundle {
            Objects.requireNonNull(root, "root");
            entries = immutableUniqueObjects(entries, false);
        }

        @Override
        public int frameType() {
            return S_OBJECT_BUNDLE;
        }
    }

    public record RootReady(String dimension, RootToken root) implements Message {
        public RootReady {
            validateDimension(dimension);
            Objects.requireNonNull(root, "root");
        }

        @Override
        public int frameType() {
            return C_ROOT_READY;
        }
    }

    /** One independently addressable compressed canonical object. */
    public static final class EncodedObject {
        private final Hash256 hash;
        private final ObjectKind kind;
        private final int dictionaryId;
        private final int canonicalLength;
        private final int compressedChecksum;
        private final byte[] compressedBytes;

        public EncodedObject(Hash256 hash, ObjectKind kind, int dictionaryId, int canonicalLength,
                             int compressedChecksum, byte[] compressedBytes) {
            this.hash = Objects.requireNonNull(hash, "hash");
            this.kind = Objects.requireNonNull(kind, "kind");
            if (!isClientServiceableObject(kind)) {
                throw new IllegalArgumentException(
                        "server-internal visibility objects are not client-serviceable");
            }
            this.dictionaryId = dictionaryId;
            boolean content = kind == ObjectKind.EXTERIOR_MICROTILE
                    || kind == ObjectKind.INTERIOR_MICROTILE
                    || kind == ObjectKind.COMPLEX_MICROTILE;
            if (content != (dictionaryId != 0)) {
                throw new IllegalArgumentException(
                        "only final content microtiles require a dictionary ID");
            }
            if (canonicalLength < 0 || canonicalLength > MAX_CANONICAL_OBJECT_BYTES) {
                throw new IllegalArgumentException("canonical object length is out of bounds");
            }
            this.canonicalLength = canonicalLength;
            Objects.requireNonNull(compressedBytes, "compressedBytes");
            if (compressedBytes.length > MAX_COMPRESSED_OBJECT_BYTES) {
                throw new IllegalArgumentException("compressed object is too large");
            }
            if (checksum(compressedBytes) != compressedChecksum) {
                throw new IllegalArgumentException("compressed object checksum mismatch");
            }
            this.compressedChecksum = compressedChecksum;
            this.compressedBytes = compressedBytes.clone();
        }

        public static EncodedObject create(Hash256 hash, ObjectKind kind, int dictionaryId,
                                           int canonicalLength, byte[] compressedBytes) {
            return new EncodedObject(hash, kind, dictionaryId,
                    canonicalLength, checksum(compressedBytes), compressedBytes);
        }

        public Hash256 hash() {
            return this.hash;
        }

        public ObjectKind kind() {
            return this.kind;
        }

        /** Unsigned index in the announced dictionary set; zero means no dictionary. */
        public int dictionaryId() {
            return this.dictionaryId;
        }

        public int canonicalLength() {
            return this.canonicalLength;
        }

        public int compressedLength() {
            return this.compressedBytes.length;
        }

        public int compressedChecksum() {
            return this.compressedChecksum;
        }

        public byte[] compressedBytes() {
            return this.compressedBytes.clone();
        }

        private void writeBytesTo(PayloadWriter output) {
            output.putBytes(this.compressedBytes);
        }

        @Override
        public boolean equals(Object other) {
            return this == other || other instanceof EncodedObject object
                    && this.dictionaryId == object.dictionaryId
                    && this.canonicalLength == object.canonicalLength
                    && this.compressedChecksum == object.compressedChecksum
                    && this.hash.equals(object.hash)
                    && this.kind == object.kind
                    && Arrays.equals(this.compressedBytes, object.compressedBytes);
        }

        @Override
        public int hashCode() {
            int result = Objects.hash(this.hash, this.kind, this.dictionaryId,
                    this.canonicalLength, this.compressedChecksum);
            return 31 * result + Arrays.hashCode(this.compressedBytes);
        }

        @Override
        public String toString() {
            return "EncodedObject[hash=" + this.hash + ", kind=" + this.kind
                    + ", dictionaryId="
                    + Integer.toUnsignedString(this.dictionaryId)
                    + ", canonicalLength=" + this.canonicalLength
                    + ", compressedLength=" + this.compressedBytes.length + ']';
        }
    }

    /** Encode one message payload. The outer frame header is not included. */
    public static byte[] encode(Message message) {
        Objects.requireNonNull(message, "message");
        PayloadWriter output = new PayloadWriter();
        if (message instanceof RootAnnounce root) {
            output.putString(root.dimension());
            output.putRoot(root.root());
            output.putHash(root.catalogHash());
            output.putHash(root.dictionarySetHash());
            output.putHash(root.visibilityDirectoryHash());
        } else if (message instanceof SubtreeRequest request) {
            writeRequest(output, request.root(), request.hashes());
        } else if (message instanceof SubtreeData data) {
            writeBundle(output, data.root(), data.entries());
        } else if (message instanceof ObjectRequest request) {
            writeRequest(output, request.root(), request.hashes());
        } else if (message instanceof ObjectBundle bundle) {
            writeBundle(output, bundle.root(), bundle.entries());
        } else if (message instanceof RootReady ready) {
            output.putString(ready.dimension());
            output.putRoot(ready.root());
        } else {
            throw new IllegalArgumentException("unsupported message " + message.getClass().getName());
        }
        return output.toByteArray();
    }

    /** Decode one bounded message payload, rejecting non-canonical or trailing input. */
    public static Message decode(int frameType, byte[] payload) throws DecodeException {
        Objects.requireNonNull(payload, "payload");
        if (payload.length > MAX_FRAME_PAYLOAD) {
            throw new DecodeException("frame payload is larger than " + MAX_FRAME_PAYLOAD + " bytes");
        }
        PayloadReader input = new PayloadReader(payload);
        try {
            Message result = switch (frameType) {
                case S_ROOT_ANNOUNCE -> decodeRootAnnounce(input);
                case C_SUBTREE_REQUEST -> {
                    RootToken root = input.getRoot();
                    yield new SubtreeRequest(root, input.getHashList());
                }
                case S_SUBTREE_DATA -> {
                    RootToken root = input.getRoot();
                    yield new SubtreeData(root, input.getObjectList(true));
                }
                case C_OBJECT_REQUEST -> {
                    RootToken root = input.getRoot();
                    yield new ObjectRequest(root, input.getHashList());
                }
                case S_OBJECT_BUNDLE -> {
                    RootToken root = input.getRoot();
                    yield new ObjectBundle(root, input.getObjectList(false));
                }
                case C_ROOT_READY -> new RootReady(input.getString(), input.getRoot());
                default -> throw new DecodeException("unknown frame type 0x"
                        + Integer.toHexString(frameType));
            };
            input.finish();
            return result;
        } catch (DecodeException error) {
            throw error;
        } catch (IllegalArgumentException | NullPointerException error) {
            throw new DecodeException(error.getMessage() == null ? "invalid message payload" : error.getMessage(), error);
        }
    }

    public static int checksum(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        CRC32C checksum = new CRC32C();
        checksum.update(bytes, 0, bytes.length);
        return (int) checksum.getValue();
    }

    private static RootAnnounce decodeRootAnnounce(PayloadReader input) throws DecodeException {
        String dimension = input.getString();
        RootToken root = input.getRoot();
        Hash256 catalog = input.getHash();
        Hash256 dictionaries = input.getHash();
        Hash256 visibility = input.getHash();
        return new RootAnnounce(dimension, root, catalog, dictionaries, visibility);
    }

    private static void writeRequest(PayloadWriter output, RootToken root, List<Hash256> hashes) {
        output.putRoot(root);
        output.putU16(hashes.size());
        hashes.forEach(output::putHash);
    }

    private static void writeBundle(PayloadWriter output, RootToken root, List<EncodedObject> entries) {
        output.putRoot(root);
        output.putU16(entries.size());
        for (EncodedObject entry : entries) {
            output.putHash(entry.hash());
            output.putU8(entry.kind().wireId());
            output.putU32(entry.dictionaryId());
            output.putU32(entry.canonicalLength());
            output.putU32(entry.compressedLength());
            output.putU32(entry.compressedChecksum());
            entry.writeBytesTo(output);
        }
    }

    private static List<Hash256> immutableUniqueHashes(List<Hash256> hashes, int maximum) {
        Objects.requireNonNull(hashes, "hashes");
        if (hashes.isEmpty() || hashes.size() > maximum) {
            throw new IllegalArgumentException("hash count must be between 1 and " + maximum);
        }
        List<Hash256> copy = List.copyOf(hashes);
        if (new HashSet<>(copy).size() != copy.size()) {
            throw new IllegalArgumentException("duplicate content hash");
        }
        return copy;
    }

    private static List<EncodedObject> immutableUniqueObjects(List<EncodedObject> entries,
                                                               boolean manifestsOnly) {
        Objects.requireNonNull(entries, "entries");
        if (entries.isEmpty() || entries.size() > MAX_BUNDLE_ENTRIES) {
            throw new IllegalArgumentException("object count must be between 1 and " + MAX_BUNDLE_ENTRIES);
        }
        List<EncodedObject> copy = List.copyOf(entries);
        Set<Hash256> hashes = new HashSet<>();
        long canonicalBytes = 0;
        for (EncodedObject entry : copy) {
            if (!hashes.add(entry.hash())) throw new IllegalArgumentException("duplicate content object");
            canonicalBytes += entry.canonicalLength();
            if (isSubtreeObject(entry.kind()) != manifestsOnly
                    || manifestsOnly && (entry.canonicalLength() > MAX_MANIFEST_BYTES
                    || entry.compressedLength() > MAX_MANIFEST_BYTES)) {
                throw new IllegalArgumentException(
                        "object type does not match its bounded bundle channel");
            }
        }
        long maximum = manifestsOnly ? MAX_MANIFEST_BYTES : MAX_CANONICAL_OBJECT_BYTES;
        if (canonicalBytes > maximum) {
            throw new IllegalArgumentException("bundle canonical data exceeds " + maximum + " bytes");
        }
        return copy;
    }

    private static void validateDimension(String dimension) {
        Objects.requireNonNull(dimension, "dimension");
        byte[] encoded = encodeUtf8(dimension);
        if (encoded.length == 0 || encoded.length > MAX_DIMENSION_BYTES) {
            throw new IllegalArgumentException("dimension name must contain 1 to "
                    + MAX_DIMENSION_BYTES + " UTF-8 bytes");
        }
    }

    private static byte[] encodeUtf8(String value) {
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(value));
            byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            return bytes;
        } catch (CharacterCodingException error) {
            throw new IllegalArgumentException("string is not valid UTF-8", error);
        }
    }

    private static boolean isSubtreeObject(ObjectKind kind) {
        return kind == ObjectKind.ROOT_DIRECTORY || kind == ObjectKind.MANIFEST_SUBTREE
                || kind == ObjectKind.MANIFEST_DESCRIPTOR_PAGE;
    }

    private static boolean isClientServiceableObject(ObjectKind kind) {
        return kind != ObjectKind.VISIBILITY_DIRECTORY
                && kind != ObjectKind.VISIBILITY_PAGE
                && kind != ObjectKind.VISIBILITY_SUMMARY_PAGE;
    }

    private static final class PayloadWriter {
        private final ByteArrayOutputStream output = new ByteArrayOutputStream(256);

        private void putU8(int value) {
            this.reserve(1);
            this.output.write(value);
        }

        private void putU16(int value) {
            if ((value & ~0xffff) != 0) throw new IllegalArgumentException("unsigned 16-bit value overflow");
            this.reserve(2);
            this.output.write(value);
            this.output.write(value >>> 8);
        }

        private void putU32(int value) {
            this.reserve(4);
            this.output.write(value);
            this.output.write(value >>> 8);
            this.output.write(value >>> 16);
            this.output.write(value >>> 24);
        }

        private void putU64(long value) {
            this.reserve(8);
            for (int shift = 0; shift < 64; shift += 8) this.output.write((int) (value >>> shift));
        }

        private void putHash(Hash256 hash) {
            this.putBytes(hash.toBytes());
        }

        private void putRoot(RootToken root) {
            this.putU64(root.generation());
            this.putHash(root.dimensionHash());
            this.putHash(root.rootHash());
        }

        private void putString(String value) {
            byte[] bytes = encodeUtf8(value);
            if (bytes.length == 0 || bytes.length > MAX_DIMENSION_BYTES) {
                throw new IllegalArgumentException("dimension name is out of bounds");
            }
            this.putU16(bytes.length);
            this.putBytes(bytes);
        }

        private void putBytes(byte[] bytes) {
            this.reserve(bytes.length);
            this.output.writeBytes(bytes);
        }

        private void reserve(int bytes) {
            if (bytes < 0 || this.output.size() > MAX_FRAME_PAYLOAD - bytes) {
                throw new IllegalArgumentException("encoded payload exceeds " + MAX_FRAME_PAYLOAD + " bytes");
            }
        }

        private byte[] toByteArray() {
            return this.output.toByteArray();
        }
    }

    private static final class PayloadReader {
        private final ByteBuffer input;

        private PayloadReader(byte[] payload) {
            this.input = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        }

        private int getU8() throws DecodeException {
            this.requireRemaining(1);
            return Byte.toUnsignedInt(this.input.get());
        }

        private int getU16() throws DecodeException {
            this.requireRemaining(2);
            return Short.toUnsignedInt(this.input.getShort());
        }

        private int getU32Length(int maximum, String name) throws DecodeException {
            this.requireRemaining(4);
            long value = Integer.toUnsignedLong(this.input.getInt());
            if (value > maximum) throw new DecodeException(name + " is larger than " + maximum + " bytes");
            return (int) value;
        }

        private int getU32Bits() throws DecodeException {
            this.requireRemaining(4);
            return this.input.getInt();
        }

        private long getU64() throws DecodeException {
            this.requireRemaining(8);
            return this.input.getLong();
        }

        private Hash256 getHash() throws DecodeException {
            this.requireRemaining(HASH_BYTES);
            return new Hash256(this.input.getLong(), this.input.getLong(),
                    this.input.getLong(), this.input.getLong());
        }

        private RootToken getRoot() throws DecodeException {
            return new RootToken(this.getU64(), this.getHash(), this.getHash());
        }

        private String getString() throws DecodeException {
            int length = this.getU16();
            if (length == 0 || length > MAX_DIMENSION_BYTES) {
                throw new DecodeException("dimension name is out of bounds");
            }
            byte[] bytes = this.getBytes(length);
            try {
                return StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes)).toString();
            } catch (CharacterCodingException error) {
                throw new DecodeException("dimension name is not valid UTF-8", error);
            }
        }

        private List<Hash256> getHashList() throws DecodeException {
            int count = this.getU16();
            if (count == 0 || count > MAX_REQUEST_ENTRIES) {
                throw new DecodeException("request hash count is out of bounds");
            }
            int required = Math.multiplyExact(count, HASH_BYTES);
            if (this.input.remaining() != required) {
                throw new DecodeException("request hash count does not match payload length");
            }
            Hash256[] hashes = new Hash256[count];
            for (int index = 0; index < count; index++) hashes[index] = this.getHash();
            return List.of(hashes);
        }

        private List<EncodedObject> getObjectList(boolean manifestsOnly) throws DecodeException {
            int count = this.getU16();
            if (count == 0 || count > MAX_BUNDLE_ENTRIES) {
                throw new DecodeException("bundle object count is out of bounds");
            }
            if (this.input.remaining() < Math.multiplyExact(count, OBJECT_HEADER_BYTES)) {
                throw new DecodeException("object count cannot fit in payload");
            }
            EncodedObject[] objects = new EncodedObject[count];
            long canonicalBytes = 0;
            for (int index = 0; index < count; index++) {
                Hash256 hash = this.getHash();
                ObjectKind kind = ObjectKind.decode(this.getU8());
                int dictionaryId = this.getU32Bits();
                int canonicalLength = this.getU32Length(MAX_CANONICAL_OBJECT_BYTES, "canonical object");
                canonicalBytes += canonicalLength;
                long maximum = manifestsOnly ? MAX_MANIFEST_BYTES : MAX_CANONICAL_OBJECT_BYTES;
                if (canonicalBytes > maximum) {
                    throw new DecodeException("bundle canonical data exceeds " + maximum + " bytes");
                }
                int compressedLength = this.getU32Length(MAX_COMPRESSED_OBJECT_BYTES, "compressed object");
                int checksum = this.getU32Bits();
                byte[] bytes = this.getBytes(compressedLength);
                try {
                    objects[index] = new EncodedObject(hash, kind, dictionaryId,
                            canonicalLength, checksum, bytes);
                } catch (IllegalArgumentException error) {
                    throw new DecodeException(error.getMessage(), error);
                }
                if (isSubtreeObject(kind) != manifestsOnly
                        || manifestsOnly && (canonicalLength > MAX_MANIFEST_BYTES
                        || compressedLength > MAX_MANIFEST_BYTES)) {
                    throw new DecodeException(
                            "object type does not match its bounded bundle channel");
                }
            }
            return List.of(objects);
        }

        private byte[] getBytes(int length) throws DecodeException {
            this.requireRemaining(length);
            byte[] bytes = new byte[length];
            this.input.get(bytes);
            return bytes;
        }

        private void requireRemaining(int bytes) throws DecodeException {
            if (bytes < 0 || this.input.remaining() < bytes) {
                throw new DecodeException("truncated payload");
            }
        }

        private void finish() throws DecodeException {
            if (this.input.hasRemaining()) {
                throw new DecodeException("trailing bytes in payload");
            }
        }
    }

    public static final class DecodeException extends IOException {
        private static final long serialVersionUID = 1L;

        public DecodeException(String message) {
            super(message);
        }

        public DecodeException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
