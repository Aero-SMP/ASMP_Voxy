package me.cortex.voxy.client.lod;

import me.cortex.voxy.client.lod.DictionaryCodec.Dictionary;
import me.cortex.voxy.client.lod.DictionaryCodec.DictionarySet;
import me.cortex.voxy.client.lod.ManifestCodec.DescriptorPage;
import me.cortex.voxy.client.lod.ManifestCodec.ManifestSubtree;
import me.cortex.voxy.client.lod.ManifestCodec.RootDirectory;
import me.cortex.voxy.client.lod.WireMessage.Hash256;
import me.cortex.voxy.client.lod.WireMessage.ObjectKind;

/** Immutable authenticated identity paired with its once-decoded canonical payload. */
final class DecodedObject {
    private final Hash256 hash;
    private final ObjectKind kind;
    private final int canonicalLength;
    private final Object payload;

    /** Called only after ObjectDecoder has authenticated and decoded the owned byte array. */
    DecodedObject(Hash256 hash, ObjectKind kind, int canonicalLength, Object payload) {
        this.hash = hash;
        this.kind = kind;
        this.canonicalLength = canonicalLength;
        this.payload = payload;
    }

    public Hash256 hash() {
        return this.hash;
    }

    public ObjectKind kind() {
        return this.kind;
    }

    public int canonicalLength() {
        return this.canonicalLength;
    }

    RootDirectory rootDirectory() {
        return (RootDirectory) this.payload;
    }

    ManifestSubtree manifestSubtree() {
        return (ManifestSubtree) this.payload;
    }

    DescriptorPage descriptorPage() {
        return (DescriptorPage) this.payload;
    }

    CatalogCodec.Catalog catalog() {
        return (CatalogCodec.Catalog) this.payload;
    }

    DictionarySet dictionarySet() {
        return (DictionarySet) this.payload;
    }

    Dictionary dictionary() {
        return (Dictionary) this.payload;
    }

    MicrotileCodec.Decoded microtile() {
        return (MicrotileCodec.Decoded) this.payload;
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof DecodedObject object
                && this.canonicalLength == object.canonicalLength
                && this.hash.equals(object.hash) && this.kind == object.kind;
    }

    @Override
    public int hashCode() {
        int result = 31 * this.hash.hashCode() + this.kind.hashCode();
        return 31 * result + this.canonicalLength;
    }
}
