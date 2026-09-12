package me.cortex.voxy.client.lod;

import me.cortex.voxy.client.core.rendering.SectionKey;
import java.io.IOException;

/** Immutable decoding identity, independent of server index generations and worker authority. */
record LocalSection(long key, int kind, int children, int compressedBytes, int canonicalBytes,
                    int crc, RegionalProtocol.Fingerprint fingerprint,
                    RegionalProtocol.Hash32 catalog) {
    static final int ABSENT = 0, EMPTY = 1, DATA = 2;

    LocalSection {
        if (SectionKey.level(key) > SectionKey.MAX_LOD_LAYER || (key & 15) != 0
                || kind < ABSENT || kind > DATA || (children & ~255) != 0
                || compressedBytes < 0 || compressedBytes > RegionalProtocol.MAX_SECTION_BYTES
                || canonicalBytes < 0 || canonicalBytes > RegionalProtocol.MAX_SECTION_BYTES
                || fingerprint == null || catalog == null)
            throw new IllegalArgumentException("invalid local section binding");
        if (kind == DATA ? compressedBytes == 0 || canonicalBytes < 2 || fingerprint.isZero()
                || catalog.equals(RegionalProtocol.Hash32.ZERO)
                : compressedBytes != 0 || canonicalBytes != 0 || crc != 0 || !fingerprint.isZero())
            throw new IllegalArgumentException("local section kind disagrees with payload");
        if (kind == ABSENT && (children != 0 || !catalog.equals(RegionalProtocol.Hash32.ZERO)))
            throw new IllegalArgumentException("absence carries decoding metadata");
    }

    static LocalSection from(RegionalProtocol.RegionIndex index, int ordinal,
                             RegionalProtocol.Hash32 catalog) {
        long key;
        try { key = index.key(ordinal); }
        catch (IOException invalid) { throw new IllegalArgumentException("invalid indexed section", invalid); }
        return new LocalSection(key, !index.isPresent(ordinal) ? ABSENT
                : index.isEmpty(ordinal) ? EMPTY : DATA, index.childMask(ordinal),
                index.compressedLength(ordinal), index.canonicalLength(ordinal),
                index.compressedCrc(ordinal), index.sectionFingerprint(ordinal),
                index.isPresent(ordinal) ? catalog : RegionalProtocol.Hash32.ZERO);
    }

    long region() {
        int shift = SectionKey.MAX_LOD_LAYER - SectionKey.level(this.key);
        return Integer.toUnsignedLong(SectionKey.x(this.key) >> shift)
                | (long) (SectionKey.z(this.key) >> shift) << 32;
    }

    /** Compression is a transport representation, not a reason to rebuild identical geometry. */
    boolean sameContent(LocalSection other) {
        return other != null && this.key == other.key && this.kind == other.kind
                && this.children == other.children && this.fingerprint.equals(other.fingerprint)
                && this.catalog.equals(other.catalog);
    }
}
