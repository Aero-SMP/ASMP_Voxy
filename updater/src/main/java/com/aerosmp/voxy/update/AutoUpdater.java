package com.aerosmp.voxy.update;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.jar.JarFile;

/** Shared update transaction; no Minecraft, rendering, terrain, or networking libraries. */
public final class AutoUpdater implements AutoCloseable {
    private static final String SSH = "aerosmp@ssh.aerosmp.com";
    private static final String RELEASES = "/home/aerosmp/Desktop/ASMP_Voxy_Rewrite/releases";
    private final Path game;
    private final String side;
    private final boolean remote;
    private final UpdateHandler ready;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(
            task -> Thread.ofPlatform().daemon().name("Voxy automatic updates").unstarted(task));
    private volatile boolean pending;
    public record Update(Path game, Path current, Path staged, Path target, long build, String sha256) {}
    @FunctionalInterface public interface UpdateHandler { void accept(Update update) throws Exception; }
    public AutoUpdater(Path game, String side, boolean remote, UpdateHandler ready) {
        if (!Set.of("client", "server").contains(side)) throw new IllegalArgumentException("invalid side");
        this.game = game.toAbsolutePath().normalize(); this.side = side; this.remote = remote; this.ready = ready;
    }
    public void start() { worker.scheduleWithFixedDelay(this::poll, 1, 20, TimeUnit.SECONDS); }
    public boolean restartPending() { return pending; }
    public void close() { worker.shutdownNow(); }
    private void poll() {
        if (pending) return;
        try {
            String remoteFeed = RELEASES + "/" + side;
            Path feed = Path.of(remoteFeed);
            String text = remote ? command(30, ssh(), "-n", "-o", "BatchMode=yes", "-o",
                    "StrictHostKeyChecking=yes", "-o", "ConnectTimeout=15", SSH,
                    "head -c 4097 " + remoteFeed + "/latest.properties")
                    : (Files.exists(feed.resolve("latest.properties")) ? Files.readString(feed.resolve("latest.properties")) : "");
            if (text.isBlank()) return;
            Properties release = manifest(text);
            long build = Long.parseLong(release.getProperty("build"));
            if (build <= BuildInfo.BUILD) return;
            String file = release.getProperty("file"), hash = release.getProperty("sha256");
            Path state = game.resolve(".voxy-rewrite-updater"); Files.createDirectories(state);
            Path staged = state.resolve(file + ".part");
            if (remote) command(120, windows() ? "scp.exe" : "scp", "-q", "-o", "BatchMode=yes",
                    "-o", "StrictHostKeyChecking=yes", "-o", "ConnectTimeout=15", SSH + ":" + remoteFeed + "/" + file, staged.toString());
            else Files.copy(feed.resolve(file), staged, StandardCopyOption.REPLACE_EXISTING);
            verify(staged, side, build, hash);
            Path mods = game.resolve("mods");
            List<Path> installed;
            try (var files = Files.list(mods)) {
                installed = files.filter(path -> path.getFileName().toString().matches(
                        "voxy-rewrite-" + side + "-[A-Za-z0-9._+-]+-debug\\.jar")).toList();
            }
            if (installed.size() != 1) throw new IOException("expected exactly one installed rewrite " + side + " jar");
            Update update = new Update(game, installed.getFirst(), staged, mods.resolve(file), build, hash);
            pending = true;
            try { ready.accept(update); }
            catch (Exception failure) { pending = false; throw failure; }
            log("update prepared build=" + build);
        } catch (Exception failure) { log("update failed: " + failure); }
    }
    public static Properties manifest(String text) throws IOException {
        if (text.length() > 4096) throw new IOException("oversized update manifest");
        Properties properties = new Properties(); properties.load(new StringReader(text));
        String file = properties.getProperty("file", ""), hash = properties.getProperty("sha256", "");
        if (!file.matches("voxy-rewrite-(client|server)-[A-Za-z0-9._+-]+-debug\\.jar")
                || !hash.matches("[0-9a-f]{64}") || !properties.getProperty("build", "").matches("[0-9]{1,18}"))
            throw new IOException("invalid update manifest");
        return properties;
    }
    public static void verify(Path path, String side, long build, String expected) throws Exception {
        if (Files.isSymbolicLink(path) || Files.size(path) > 32 * 1024 * 1024) throw new IOException("invalid update file");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream stream = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            for (int count; (count = stream.read(buffer)) >= 0;) digest.update(buffer, 0, count);
        }
        if (!HexFormat.of().formatHex(digest.digest()).equals(expected)) throw new IOException("SHA-256 mismatch");
        try (JarFile jar = new JarFile(path.toFile())) {
            var attrs = jar.getManifest().getMainAttributes();
            if (!side.equals(attrs.getValue("Voxy-Update-Side"))
                    || !Long.toString(build).equals(attrs.getValue("Voxy-Update-Build"))
                    || jar.getEntry("com/aerosmp/voxy/update/RestartHelper.class") == null)
                throw new IOException("wrong update side or build");
            String id = side.equals("client") ? "voxy" : "voxy_server";
            var entry = jar.getJarEntry("META-INF/neoforge.mods.toml");
            if (entry == null || !new String(jar.getInputStream(entry).readNBytes(8192), StandardCharsets.UTF_8)
                    .contains("modId=\"" + id + "\"")) throw new IOException("wrong mod identity");
        }
    }
    public static void install(Update update) throws IOException {
        Path mods = update.game.resolve("mods").toRealPath();
        if (!update.current.toAbsolutePath().normalize().getParent().equals(mods)
                || !update.target.toAbsolutePath().normalize().getParent().equals(mods)
                || Files.isSymbolicLink(update.current) || Files.isSymbolicLink(update.target))
            throw new IOException("invalid installation target");
        if (!update.current.equals(update.target) && Files.exists(update.target)) throw new IOException("update target already exists");
        Path backup = update.game.resolve(".voxy-rewrite-updater").resolve(update.current.getFileName() + ".backup");
        Files.move(update.current, backup, StandardCopyOption.REPLACE_EXISTING);
        try { Files.move(update.staged, update.target, StandardCopyOption.ATOMIC_MOVE); }
        catch (IOException failure) { Files.move(backup, update.current, StandardCopyOption.REPLACE_EXISTING); throw failure; }
    }
    static String command(int seconds, String... command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        process.getOutputStream().close();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread reader = Thread.ofVirtual().start(() -> {
            try (InputStream stream = process.getInputStream()) {
                byte[] buffer = new byte[4096];
                for (int count; (count = stream.read(buffer)) >= 0;)
                    if (output.size() < 65536) output.write(buffer, 0, Math.min(count, 65536-output.size()));
            } catch (IOException ignored) {}
        });
        boolean completed;
        try { completed = process.waitFor(seconds, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { process.destroyForcibly(); throw interrupted; }
        if (!completed) { process.destroyForcibly(); process.waitFor(); }
        reader.join(5000);
        if (!completed || process.exitValue() != 0) throw new IOException("command failed/timed out: " + command[0]);
        return output.toString(StandardCharsets.UTF_8);
    }
    static boolean windows() { return System.getProperty("os.name").startsWith("Windows"); }
    private static String ssh() { return windows() ? "ssh.exe" : "ssh"; }
    private void log(String text) {
        try {
            Path logs = game.resolve("logs"); Files.createDirectories(logs);
            Files.writeString(logs.resolve("voxy-rewrite-updater.log"), Instant.now() + " " + text.replace('\n', ' ') + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {}
    }
}
