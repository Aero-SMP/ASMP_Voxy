package me.cortex.voxy.client.lod;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.RandomAccess;

/** Bounded decoder for the canonical block/biome catalog. */
public final class CatalogCodec {
    public static final int MAX_BYTES = 64 * 1024 * 1024;
    public static final int MAX_BLOCKS = 1 << 20;
    public static final int MAX_BIOMES = 1 << 9;
    private static final int MAX_NAME_BYTES = 4096;
    private static final byte[] MAGIC = "VXYCAT\0\0".getBytes(StandardCharsets.US_ASCII);

    private CatalogCodec() {}

    public record Block(String canonical, int opacity, boolean authoritative) {
        public Block {
            Objects.requireNonNull(canonical, "canonical");
            validateName(canonical);
            if (opacity < 0 || opacity > 15) {
                throw new IllegalArgumentException("invalid canonical catalog block");
            }
        }
    }

    public interface Source {
        long catalogId();
        long generation();
        long mipGeneration();
        List<Block> blocks();
        List<String> biomes();
        EncodedName blockName(int index) throws IOException;
        EncodedName biomeName(int index) throws IOException;
    }

    public record Catalog(long catalogId, long generation, long mipGeneration,
                          List<Block> blocks, List<String> biomes) implements Source {
        public Catalog {
            if (catalogId == 0) throw new IllegalArgumentException("catalog identity zero is reserved");
            blocks = new CatalogNames<>(List.copyOf(Objects.requireNonNull(blocks, "blocks")), true);
            biomes = new CatalogNames<>(List.copyOf(Objects.requireNonNull(biomes, "biomes")), false);
            if (blocks.isEmpty() || blocks.size() > MAX_BLOCKS
                    || biomes.isEmpty() || biomes.size() > MAX_BIOMES) {
                throw new IllegalArgumentException("catalog entry counts are outside bounds");
            }
            var blockNames = new java.util.HashSet<String>();
            for (Block block : blocks) {
                Objects.requireNonNull(block, "block");
                if (!blockNames.add(block.canonical())) throw new IllegalArgumentException("duplicate canonical block name");
            }
            var biomeNames = new java.util.HashSet<String>();
            for (String biome : biomes) {
                Objects.requireNonNull(biome, "biome");
                validateName(biome);
                if (!biomeNames.add(biome)) throw new IllegalArgumentException("duplicate canonical biome name");
            }
        }
        @Override public EncodedName blockName(int index) throws IOException {
            return ((CatalogNames<Block>) this.blocks).encoded(index);
        }
        @Override public EncodedName biomeName(int index) throws IOException {
            return ((CatalogNames<String>) this.biomes).encoded(index);
        }
    }

    /** One live catalogue domain owns names once. Older definitions keep fixed prefix bounds. */
    public static final class SharedNames {
        private long catalogId;
        private final List<Block> blocks = new ArrayList<>();
        private final List<String> biomes = new ArrayList<>();
        private final List<EncodedName> blockNames = new ArrayList<>();
        private final List<EncodedName> biomeNames = new ArrayList<>();

        public synchronized Source bind(Catalog snapshot) throws DecodeException {
            Objects.requireNonNull(snapshot, "snapshot");
            if (this.catalogId != 0 && this.catalogId != snapshot.catalogId())
                throw new DecodeException("catalog identity changed");
            for (int index = 0; index < Math.min(this.blocks.size(), snapshot.blocks().size()); index++)
                if (!this.blocks.get(index).equals(snapshot.blocks().get(index)))
                    throw new DecodeException("catalog block prefix changed");
            for (int index = 0; index < Math.min(this.biomes.size(), snapshot.biomes().size()); index++)
                if (!this.biomes.get(index).equals(snapshot.biomes().get(index)))
                    throw new DecodeException("catalog biome prefix changed");
            // Validate both prefixes before changing either table. A rejected snapshot is atomic.
            this.catalogId = snapshot.catalogId();
            for (int index = this.blocks.size(); index < snapshot.blocks().size(); index++) {
                this.blocks.add(snapshot.blocks().get(index));
                this.blockNames.add(null);
            }
            for (int index = this.biomes.size(); index < snapshot.biomes().size(); index++) {
                this.biomes.add(snapshot.biomes().get(index));
                this.biomeNames.add(null);
            }
            return new Prefix(snapshot.catalogId(), snapshot.generation(), snapshot.mipGeneration(),
                    new PrefixList<>(this, this.blocks, this.blockNames, snapshot.blocks().size(), true),
                    new PrefixList<>(this, this.biomes, this.biomeNames, snapshot.biomes().size(), false));
        }
    }

    private record Prefix(long catalogId, long generation, long mipGeneration,
                          List<Block> blocks, List<String> biomes) implements Source {
        @Override public EncodedName blockName(int index) throws IOException {
            return ((PrefixList<Block>) this.blocks).encoded(index);
        }
        @Override public EncodedName biomeName(int index) throws IOException {
            return ((PrefixList<String>) this.biomes).encoded(index);
        }
    }

    private static final class PrefixList<T> extends AbstractList<T> implements RandomAccess {
        private final SharedNames owner;
        private final List<T> entries;
        private final List<EncodedName> names;
        private final int length;
        private final boolean blocks;
        private PrefixList(SharedNames owner, List<T> entries, List<EncodedName> names, int length, boolean blocks) {
            this.owner = owner; this.entries = entries; this.names = names;
            this.length = length; this.blocks = blocks;
        }
        @Override public int size() { return this.length; }
        @Override public T get(int index) {
            Objects.checkIndex(index, this.length);
            synchronized (this.owner) { return this.entries.get(index); }
        }
        EncodedName encoded(int index) throws IOException {
            Objects.checkIndex(index, this.length);
            synchronized (this.owner) {
                EncodedName name = this.names.get(index);
                if (name == null) {
                    name = new EncodedName(spelling(this.entries.get(index), this.blocks));
                    this.names.set(index, name);
                }
                return name;
            }
        }
    }

    /** List equality remains canonical; derived byte identities never enter record equality. */
    private static final class CatalogNames<T> extends AbstractList<T> implements RandomAccess {
        private final List<T> entries;
        private final boolean blocks;
        private EncodedName[] names;
        CatalogNames(List<T> entries, boolean blocks) { this.entries = entries; this.blocks = blocks; }
        @Override public int size() { return this.entries.size(); }
        @Override public T get(int index) { return this.entries.get(index); }
        synchronized EncodedName encoded(int index) throws IOException {
            Objects.checkIndex(index, this.entries.size());
            if (this.names == null) this.names = new EncodedName[this.entries.size()];
            EncodedName name = this.names[index];
            if (name == null) {
                name = new EncodedName(spelling(this.entries.get(index), this.blocks));
                this.names[index] = name;
            }
            return name;
        }
    }

    private static String spelling(Object entry, boolean block) {
        return block ? ((Block) entry).canonical() : (String) entry;
    }

    /** Accepted-name ownership retains these bytes; callers can copy but cannot mutate them. */
    public static final class EncodedName {
        private final byte[] bytes;
        private EncodedName(String name) throws IOException {
            int length = utf8Length(name);
            this.bytes = name.getBytes(StandardCharsets.UTF_8);
            if (this.bytes.length != length) throw new IOException("source name UTF-8 length changed");
        }
        public int length() { return this.bytes.length; }
        void copyTo(int position, byte[] destination, int offset, int length) {
            System.arraycopy(this.bytes, position, destination, offset, length);
        }
    }

    private static void validateName(String name) {
        try { utf8Length(name); }
        catch (IOException failure) { throw new IllegalArgumentException("invalid canonical catalog name", failure); }
    }

    private static int utf8Length(String name) throws IOException {
        int bytes = 0;
        for (int index = 0; index < name.length(); index++) {
            char value = name.charAt(index);
            if (value < 128) bytes++;
            else if (value < 2048) bytes += 2;
            else if (Character.isHighSurrogate(value)) {
                if (++index >= name.length() || !Character.isLowSurrogate(name.charAt(index)))
                    throw new IOException("unpaired source name surrogate");
                bytes += 4;
            } else if (Character.isLowSurrogate(value)) throw new IOException("unpaired source name surrogate");
            else bytes += 3;
            if (bytes > MAX_NAME_BYTES) throw new IOException("invalid source name length");
        }
        if (bytes == 0) throw new IOException("empty source name");
        return bytes;
    }

    public static Catalog decode(byte[] canonical) throws DecodeException {
        Objects.requireNonNull(canonical, "canonical");
        if (canonical.length < 40 || canonical.length > MAX_BYTES) {
            throw new DecodeException("canonical catalog is truncated or oversized");
        }
        ByteBuffer input = ByteBuffer.wrap(canonical).order(ByteOrder.LITTLE_ENDIAN);
        byte[] magic = new byte[MAGIC.length];
        input.get(magic);
        if (!java.util.Arrays.equals(magic, MAGIC)) {
            throw new DecodeException("unsupported canonical catalog envelope");
        }
        long catalogId = input.getLong();
        long generation = input.getLong();
        long mipGeneration = input.getLong();
        long rawBlocks = Integer.toUnsignedLong(input.getInt());
        long rawBiomes = Integer.toUnsignedLong(input.getInt());
        if (rawBlocks < 1 || rawBlocks > MAX_BLOCKS || rawBiomes < 1 || rawBiomes > MAX_BIOMES
                || rawBlocks * 4L + rawBiomes * 2L > input.remaining()) {
            throw new DecodeException("canonical catalog counts cannot fit its payload");
        }
        List<Block> blocks = new ArrayList<>((int) rawBlocks);
        for (int index = 0; index < rawBlocks; index++) {
            require(input, 4);
            int opacity = Byte.toUnsignedInt(input.get());
            int flags = Byte.toUnsignedInt(input.get());
            if (opacity > 15 || (flags & ~1) != 0) {
                throw new DecodeException("invalid canonical catalog block flags");
            }
            blocks.add(new Block(readName(input), opacity, (flags & 1) != 0));
        }
        List<String> biomes = new ArrayList<>((int) rawBiomes);
        for (int index = 0; index < rawBiomes; index++) biomes.add(readName(input));
        if (input.hasRemaining()) throw new DecodeException("trailing canonical catalog bytes");
        try {
            return new Catalog(catalogId, generation, mipGeneration, blocks, biomes);
        } catch (IllegalArgumentException exception) {
            throw new DecodeException(exception.getMessage(), exception);
        }
    }

    private static String readName(ByteBuffer input) throws DecodeException {
        require(input, 2);
        int length = Short.toUnsignedInt(input.getShort());
        if (length < 1 || length > MAX_NAME_BYTES) {
            throw new DecodeException("canonical catalog name length is outside bounds");
        }
        require(input, length);
        ByteBuffer bytes = input.slice();
        bytes.limit(length);
        input.position(input.position() + length);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(bytes).toString();
        } catch (CharacterCodingException exception) {
            throw new DecodeException("canonical catalog name is not UTF-8", exception);
        }
    }

    private static void require(ByteBuffer input, int count) throws DecodeException {
        if (count < 0 || input.remaining() < count) {
            throw new DecodeException("truncated canonical catalog");
        }
    }

    public static final class DecodeException extends java.io.IOException {
        public DecodeException(String message) { super(message); }
        public DecodeException(String message, Throwable cause) { super(message, cause); }
    }
}
