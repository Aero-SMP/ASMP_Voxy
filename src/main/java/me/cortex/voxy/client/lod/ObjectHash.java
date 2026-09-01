package me.cortex.voxy.client.lod;

import me.cortex.voxy.client.lod.WireMessage.Hash256;
import me.cortex.voxy.client.lod.WireMessage.ObjectKind;

import java.nio.charset.StandardCharsets;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.security.MessageDigest;
import java.util.Objects;

/** Exact Java counterpart of the Rust typed canonical and dimension hash preimages. */
public final class ObjectHash {
    private static final byte[] OBJECT_DOMAIN =
            "Voxy canonical object\0".getBytes(StandardCharsets.UTF_8);
    private static final byte[] DIMENSION_DOMAIN =
            "Voxy dimension identity\0".getBytes(StandardCharsets.UTF_8);
    private static final byte[] NONZERO_ESCAPE =
            "Voxy nonzero hash escape".getBytes(StandardCharsets.UTF_8);

    private ObjectHash() {}

    public static Hash256 canonical(ObjectKind kind, byte[] canonicalBytes) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(canonicalBytes, "canonicalBytes");
        Blake3.Hasher hasher = new Blake3.Hasher();
        hasher.update(OBJECT_DOMAIN);
        hasher.update(new byte[] {(byte) kind.wireId()});
        hasher.update(littleEndianLength(canonicalBytes.length));
        hasher.update(canonicalBytes);
        return nonzero(hasher.digest());
    }

    public static boolean verifies(Hash256 expected, ObjectKind kind, byte[] canonicalBytes) {
        Objects.requireNonNull(expected, "expected");
        return MessageDigest.isEqual(expected.toBytes(),
                canonical(kind, canonicalBytes).toBytes());
    }

    public static Hash256 dimension(String dimension) {
        Objects.requireNonNull(dimension, "dimension");
        byte[] name;
        try {
            var encoded = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(java.nio.CharBuffer.wrap(dimension));
            name = new byte[encoded.remaining()];
            encoded.get(name);
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("dimension contains malformed Unicode", exception);
        }
        if (name.length < 1 || name.length > 1024) {
            throw new IllegalArgumentException("dimension name must contain 1..1024 UTF-8 bytes");
        }
        return nonzero(new Blake3.Hasher().update(DIMENSION_DOMAIN)
                .update(littleEndianLength(name.length)).update(name).digest());
    }

    private static Hash256 nonzero(byte[] digest) {
        boolean zero = true;
        for (byte value : digest) zero &= value == 0;
        return Hash256.fromBytes(zero ? Blake3.hash(NONZERO_ESCAPE) : digest);
    }

    private static byte[] littleEndianLength(long length) {
        byte[] bytes = new byte[Long.BYTES];
        for (int index = 0; index < bytes.length; index++) {
            bytes[index] = (byte) (length >>> (index * 8));
        }
        return bytes;
    }
}
