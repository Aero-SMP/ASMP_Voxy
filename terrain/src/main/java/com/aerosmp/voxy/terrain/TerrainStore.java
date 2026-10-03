package com.aerosmp.voxy.terrain;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Coordinate files are atomically replaced; dictionaries persist beside their sections. */
public final class TerrainStore {
    private final Path association;
    private final String dimension;
    private final Path base;
    private volatile Path world;
    public TerrainStore(Path root, String server, String dimension) throws IOException {
        this.dimension = dimension;
        base = root.resolve(SectionCodec.hex(SectionCodec.hash(server.getBytes(StandardCharsets.UTF_8))));
        Files.createDirectories(base);
        association = base.resolve("world");
        if (Files.isRegularFile(association)) {
            String value = Files.readString(association).strip();
            if (value.matches("[a-f0-9]{32}")) world = namespace(value);
        }
    }
    private Path namespace(String id) {
        return base.resolve(id).resolve(SectionCodec.hex(SectionCodec.hash(dimension.getBytes(StandardCharsets.UTF_8))));
    }
    public boolean associate(byte[] id) throws IOException {
        if (id.length != 16 || Arrays.equals(id, new byte[16])) throw new IOException("Invalid world identity");
        String hex = SectionCodec.hex(id);
        Path target = namespace(hex), old = world;
        Files.createDirectories(target);
        atomic(association, hex.getBytes(StandardCharsets.US_ASCII));
        world = target;
        return old != null && !old.equals(target);
    }
    public String identity() { Path value = world; return value == null ? "" : value.getParent().getFileName().toString(); }
    public Path namespace() { return world; }
    public boolean hasFrame(SectionKey key) {
        Path folder = world;
        return folder != null && Files.isRegularFile(folder.resolve(key.filename()));
    }
    public byte[] frame(SectionKey key) throws IOException {
        Path folder = world;
        if (folder == null) return null;
        Path file = folder.resolve(key.filename());
        if (!Files.isRegularFile(file)) return null;
        byte[] bytes = bounded(file, SectionCodec.HEADER + SectionCodec.MAX_CANONICAL + 2048);
        SectionCodec.checkFrame(bytes);
        return bytes;
    }
    public SectionCodec.Catalog catalog(byte[] hash) throws IOException {
        Path folder = world;
        if (folder == null) throw new IOException("World not associated");
        return SectionCodec.catalog(bounded(folder.resolve(SectionCodec.hex(hash) + ".vxc"), 64 * 1024 * 1024), hash);
    }
    public void saveCatalog(byte[] bytes, byte[] hash) throws IOException {
        SectionCodec.catalog(bytes, hash);
        Path folder = world;
        if (folder == null) throw new IOException("World not associated");
        atomic(folder.resolve(SectionCodec.hex(hash) + ".vxc"), bytes);
    }
    public void saveFrame(SectionKey key, byte[] frame) throws IOException {
        SectionCodec.checkFrame(frame);
        Path folder = world;
        if (folder == null) throw new IOException("World not associated");
        // A persisted frame is independently decodable after restart.
        if (!hasCatalog(SectionCodec.catalogHash(frame))) throw new IOException("Missing local catalog");
        atomic(folder.resolve(key.filename()), frame);
    }
    public boolean hasCatalog(byte[] hash) {
        Path folder = world;
        return folder != null && Files.isRegularFile(folder.resolve(SectionCodec.hex(hash) + ".vxc"));
    }
    public static byte[] bounded(Path file, int maximum) throws IOException {
        try (var input = Files.newInputStream(file)) {
            byte[] bytes = input.readNBytes(maximum + 1);
            if (bytes.length > maximum) throw new IOException("Invalid persisted record length");
            return bytes;
        }
    }
    public static void atomic(Path file, byte[] bytes) throws IOException {
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), ".voxy-", ".part");
        try {
            Files.write(temporary, bytes);
            try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException unsupported) {
                throw new IOException("Cache filesystem must support atomic replacement", unsupported);
            }
        } finally { Files.deleteIfExists(temporary); }
    }
}
