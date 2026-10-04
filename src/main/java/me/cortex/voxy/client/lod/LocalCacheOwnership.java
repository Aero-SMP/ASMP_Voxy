package me.cortex.voxy.client.lod;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.util.Arrays;
import java.util.function.Consumer;

/** Exclusive current-cache ownership. Unrecognized cache directories stay untouched. */
final class LocalCacheOwnership implements AutoCloseable {
    private static final byte[] FORMAT = "VXY-NAMES-1\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    private final FileChannel channel;
    private final FileLock lock;
    final Path root;

    static Path currentRoot(Path root) throws IOException {
        root = root.toAbsolutePath().normalize();
        rejectLinks(root);
        Path marker = root.resolve("cache-format");
        if (Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
            requireMarker(marker, FORMAT);
            return root;
        }
        if (root.getFileName() == null) throw new IOException("client cache requires a directory name");
        Path current = root.resolveSibling(root.getFileName() + "-current");
        rejectLinks(current);
        marker = current.resolve("cache-format");
        if (Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) requireMarker(marker, FORMAT);
        return current;
    }

    static LocalCacheOwnership open(Path root, Consumer<String> log) throws IOException {
        return openCurrent(currentRoot(root), log);
    }

    static LocalCacheOwnership openCurrent(Path root, Consumer<String> log) throws IOException {
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
            owned.initialize();
            log.accept("Using Voxy client cache at " + root);
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

    private void initialize() throws IOException {
        Path marker = this.root.resolve("cache-format");
        checkPending(marker, FORMAT);
        if (Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
            requireMarker(marker, FORMAT);
            return;
        }
        // Only a fresh ownership directory or our interrupted marker write can be initialized.
        // Stop at the first unknown entry; never inventory or delete another cache.
        try (var files = Files.newDirectoryStream(this.root)) {
            for (Path path : files) {
                String name = path.getFileName().toString();
                if (!name.equals(".voxy-cache.lock") && !name.equals("cache-format.pending"))
                    throw new IOException("unrecognized client cache directory; preserving data: " + this.root);
            }
        }
        writeMarker(marker, FORMAT);
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
            throw new IOException("unknown client cache format marker; preserving data: " + path);
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
