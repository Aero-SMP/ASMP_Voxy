package me.cortex.voxy.client.lod;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.function.Consumer;

/** Exclusive ownership and one-time obsolete-format reset, before any payload worker opens files.
 * Only recognized derived cache names in recognized namespace paths are disposable, not .voxy.
 */
final class LocalCacheOwnership implements AutoCloseable {
    private static final byte[] FORMAT = "VXY-NAMES-1\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    private static final byte[] RESET = "VXY-RESET-OLD-TO-NAMES-1\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    private static final String HASH = "[0-9a-f]{64}";
    private static final String TEST = "(?:test-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/)?";
    private static final String REGION = "r\\.-?[0-9]+\\.-?[0-9]+\\.(?:vxcache|vxlocal|vxmeta(?:\\.pending)?)";
    private static final String CATALOG = HASH + "\\.vxcat(?:\\.pending)?";
    private static final java.util.regex.Pattern OLD_FILE = java.util.regex.Pattern.compile(
            "(?:" + HASH + '/' + HASH + "/(?:completed-v1/" + TEST + ")?(?:" + REGION + '|' + CATALOG + ")"
            + "|(?:completed-v1/" + TEST + ")?servers/" + HASH + "\\.vxlink(?:\\.pending)?)");
    private final FileChannel channel;
    private final FileLock lock;
    final Path root;

    static LocalCacheOwnership open(Path root, Consumer<String> log) throws IOException {
        root = root.toAbsolutePath().normalize();
        rejectLinks(root);
        Files.createDirectories(root);
        rejectLinks(root);
        var channel = FileChannel.open(root.resolve(".voxy-cache.lock"), StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        FileLock lock = null;
        try {
            try { lock = channel.tryLock(); }
            catch (java.nio.channels.OverlappingFileLockException busy) { throw new IOException("client cache already owned", busy); }
            if (lock == null) throw new IOException("client cache already owned");
            var owned = new LocalCacheOwnership(root, channel, lock);
            owned.initialize(log);
            return owned;
        } catch (Throwable failure) {
            if (lock != null) try { lock.close(); } catch (IOException close) { failure.addSuppressed(close); }
            try { channel.close(); } catch (IOException close) { failure.addSuppressed(close); }
            throw failure;
        }
    }

    private LocalCacheOwnership(Path root, FileChannel channel, FileLock lock) {
        this.root = root; this.channel = channel; this.lock = lock;
    }

    private void initialize(Consumer<String> log) throws IOException {
        Path marker = this.root.resolve("cache-format"), resetting = this.root.resolve("cache-reset");
        checkPending(marker, FORMAT);
        checkPending(resetting, RESET);
        if (Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
            requireMarker(marker, FORMAT); // Unknown or future storage formats are never deleted.
            if (Files.exists(resetting, LinkOption.NOFOLLOW_LINKS)) {
                requireMarker(resetting, RESET);
                Files.delete(resetting); // Completion marker was installed before a prior crash.
            }
            return;
        }
        if (Files.exists(resetting, LinkOption.NOFOLLOW_LINKS)) requireMarker(resetting, RESET);
        // Full preflight before deleting anything. Do not collect an unbounded file inventory.
        long[] counts = {0, 0};
        visitOld((path, attributes) -> {
            counts[0] = Math.addExact(counts[0], 1);
            counts[1] = Math.addExact(counts[1], attributes.size());
        });
        writeMarker(resetting, RESET);
        log.accept("Resetting obsolete derived Voxy client cache at " + this.root + ": "
                + counts[0] + " files / " + counts[1] + " bytes; next terrain load is cold");
        visitOld((path, attributes) -> {
            rejectLinks(path);
            Files.delete(path);
        });
        // Marker is last. A failed or interrupted reset stays recognizable and repeatable.
        writeMarker(marker, FORMAT);
        Files.delete(resetting);
    }

    @FunctionalInterface private interface OldFile { void accept(Path path, BasicFileAttributes attributes) throws IOException; }
    private void visitOld(OldFile action) throws IOException {
        Files.walkFileTree(this.root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                rejectLinks(dir);
                if (OLD_FILE.matcher(root.relativize(dir).toString().replace('\\', '/')).matches())
                    throw new IOException("directory at derived cache file path: " + dir);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) throws IOException {
                if (attrs.isSymbolicLink()) throw new IOException("symlink in client cache reset: " + path);
                String relative = root.relativize(path).toString().replace('\\', '/');
                if (OLD_FILE.matcher(relative).matches()) {
                    if (!attrs.isRegularFile()) throw new IOException("non-file cache entry: " + path);
                    recognizeOld(path);
                    action.accept(path, attrs);
                } else if (relative.endsWith(".vxlocal") || relative.endsWith(".vxcache")
                        || relative.endsWith(".vxmeta") || relative.endsWith(".vxcat") || relative.endsWith(".vxlink")) {
                    throw new IOException("unknown cache layout; refusing reset: " + path);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void recognizeOld(Path path) throws IOException {
        String name = path.getFileName().toString().replace(".pending", "");
        String magic = name.endsWith(".vxcache") ? "VXYSEC\0\0"
                : name.endsWith(".vxlocal") ? "VXYLOC1\0" : "VXSTART1";
        byte[] prefix;
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) { prefix = input.readNBytes(16); }
        byte[] expected = magic.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        for (int i = 0; i < Math.min(prefix.length, expected.length); i++)
            if (prefix[i] != expected[i]) throw new IOException("unknown derived cache format; preserving " + path);
        if (magic.equals("VXSTART1") && prefix.length >= 12
                && ByteBuffer.wrap(prefix).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt(8) != 1)
            throw new IOException("unknown derived metadata version; preserving " + path);
    }

    private static void checkPending(Path marker, byte[] expected) throws IOException {
        Path path = marker.resolveSibling(marker.getFileName() + ".pending");
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
        rejectLinks(path);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > expected.length)
            throw new IOException("unknown pending cache marker; preserving " + path);
        byte[] partial = Files.readAllBytes(path);
        if (!Arrays.equals(partial, Arrays.copyOf(expected, partial.length)))
            throw new IOException("unknown pending cache marker; preserving " + path);
    }

    private static void requireMarker(Path path, byte[] expected) throws IOException {
        rejectLinks(path);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) != expected.length
                || !Arrays.equals(Files.readAllBytes(path), expected))
            throw new IOException("unknown client cache format/reset marker; preserving data: " + path);
    }

    private static void writeMarker(Path path, byte[] bytes) throws IOException {
        Path pending = path.resolveSibling(path.getFileName() + ".pending");
        rejectLinks(pending);
        // An interrupted marker write can only occur after exclusive ownership was acquired.
        try (var output = FileChannel.open(pending, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            var buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) if (output.write(buffer) <= 0) throw new IOException("short cache marker write");
            output.force(true);
        }
        Files.move(pending, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    static void rejectLinks(Path path) throws IOException {
        path = path.toAbsolutePath().normalize();
        Path current = path.getRoot();
        for (Path part : path) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) throw new IOException("symlink in client cache ownership path: " + current);
        }
    }

    @Override public void close() throws IOException {
        try { this.lock.close(); } finally { this.channel.close(); }
    }
}
