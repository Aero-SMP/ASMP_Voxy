package me.cortex.voxy.client.lod;

import me.cortex.voxy.client.lod.WireMessage.Hash256;
import me.cortex.voxy.client.lod.WireMessage.ObjectKind;

import java.util.Arrays;
import java.util.Objects;

/** Immutable canonical object whose typed BLAKE3 identity has already been verified. */
public final class CanonicalObject {
    private final Hash256 hash;
    private final ObjectKind kind;
    private final byte[] canonicalBytes;

    public CanonicalObject(Hash256 hash, ObjectKind kind, byte[] canonicalBytes) {
        this.hash = Objects.requireNonNull(hash, "hash");
        this.kind = Objects.requireNonNull(kind, "kind");
        byte[] ownedBytes = Objects.requireNonNull(canonicalBytes, "canonicalBytes").clone();
        if (ownedBytes.length > WireMessage.MAX_CANONICAL_OBJECT_BYTES) {
            throw new IllegalArgumentException("canonical object exceeds the size bound");
        }
        if (!ObjectHash.verifies(hash, kind, ownedBytes)) {
            throw new IllegalArgumentException("canonical object BLAKE3 identity mismatch");
        }
        this.canonicalBytes = ownedBytes;
    }

    public Hash256 hash() {
        return this.hash;
    }

    public ObjectKind kind() {
        return this.kind;
    }

    public int canonicalLength() {
        return this.canonicalBytes.length;
    }

    public byte[] canonicalBytes() {
        return this.canonicalBytes.clone();
    }

    byte[] bytesInternal() {
        return this.canonicalBytes;
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof CanonicalObject object
                && this.hash.equals(object.hash)
                && this.kind == object.kind
                && Arrays.equals(this.canonicalBytes, object.canonicalBytes);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(this.hash, this.kind);
        return 31 * result + Arrays.hashCode(this.canonicalBytes);
    }
}
