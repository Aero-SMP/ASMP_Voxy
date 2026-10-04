package com.aerosmp.voxy.update;

import java.awt.Desktop;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.jar.JarFile;

/** One content-hash transaction; the launcher owns authentication and Java arguments. */
public final class AutoUpdater implements AutoCloseable {
    private static final String SSH = "aerosmp@ssh.aerosmp.com";
    private static final String RELEASES = "/home/aerosmp/Desktop/ASMP_Voxy_Branch/releases";
    private final Path game;
    private final String side;
    private final boolean remote;
    private final UpdateHandler ready;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(
            task -> Thread.ofPlatform().daemon().name("Voxy automatic updates").unstarted(task));
    private volatile boolean pending;
    private boolean loaded;
    public record Update(Path game, Path installed, Path staged, String side, String sha256) {}
    @FunctionalInterface public interface UpdateHandler { void accept(Update update) throws Exception; }
    public AutoUpdater(Path game, String side, boolean remote, UpdateHandler ready) {
        if (!Set.of("client", "server").contains(side)) throw new IllegalArgumentException("Invalid side");
        this.game = game.toAbsolutePath().normalize(); this.side = side; this.remote = remote; this.ready = ready;
    }
    public void start() { worker.scheduleWithFixedDelay(this::poll, 1, 20, TimeUnit.SECONDS); }
    public boolean restartPending() { return pending; }
    public void close() { worker.shutdownNow(); }
    public static void starting(Path game) {
        try {
            ProcessHandle process = ProcessHandle.current(); Path target = state(game).resolve("starting"), staged = target.resolveSibling("starting.part");
            Files.writeString(staged, process.pid() + " " + process.info().startInstant().orElseThrow() + " "
                    + process.parent().orElseThrow().pid() + " " + hash(game.resolve("mods/voxy-client-debug.jar")));
            Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception failure) { System.err.println("Voxy startup identity: " + failure); }
    }
    public void loaded() {
        if (loaded) return;
        try {
            Path staged = state(game).resolve("ready.part");
            Files.writeString(staged, ProcessHandle.current().pid() + " " + hash(game.resolve("mods/voxy-client-debug.jar")));
            Files.move(staged, state(game).resolve("ready"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            loaded = true;
        } catch (Exception failure) { log("Readiness: " + failure); }
    }
    private void poll() {
        if (pending) return;
        try {
            String feed = RELEASES + "/" + side;
            String text = remote ? command(30, windows() ? "ssh.exe" : "ssh", "-n", "-o", "BatchMode=yes",
                    "-o", "StrictHostKeyChecking=yes", "-o", "ConnectTimeout=15", SSH,
                    "head -c 4097 " + feed + "/latest.properties") : Files.readString(Path.of(feed, "latest.properties"));
            Properties release = new Properties(); release.load(new StringReader(text));
            String name = "voxy-" + side + "-debug.jar", digest = release.getProperty("sha256", "");
            if (text.length() > 4096 || !release.stringPropertyNames().equals(Set.of("file", "sha256"))
                    || !name.equals(release.getProperty("file")) || !digest.matches("[0-9a-f]{64}"))
                throw new IOException("Invalid update announcement");
            Path installed = game.resolve("mods").resolve(name), staged = state(game).resolve(name + ".part");
            if (hash(installed).equals(digest)) return;
            if (remote) command(120, windows() ? "scp.exe" : "scp", "-q", "-o", "BatchMode=yes", "-o",
                    "StrictHostKeyChecking=yes", "-o", "ConnectTimeout=15", SSH + ":" + feed + "/" + name, staged.toString());
            else Files.copy(Path.of(feed, name), staged, StandardCopyOption.REPLACE_EXISTING);
            pending = true;
            try { ready.accept(new Update(game, installed, staged, side, digest)); }
            catch (Exception failure) { pending = false; throw failure; }
            log("Update prepared sha256=" + digest);
        } catch (Exception failure) { log("Update: " + failure); }
    }
    public static String hash(Path file) throws Exception {
        try (DigestInputStream input = new DigestInputStream(Files.newInputStream(file), MessageDigest.getInstance("SHA-256"))) {
            input.transferTo(OutputStream.nullOutputStream());
            return HexFormat.of().formatHex(input.getMessageDigest().digest());
        }
    }
    public static void verify(Path file, String side, String expected) throws Exception {
        if (Files.isSymbolicLink(file) || !hash(file).equals(expected)) throw new IOException("Invalid update hash");
        try (JarFile jar = new JarFile(file.toFile())) {
            String id = side.equals("client") ? "voxy" : "voxy_server";
            if (!side.equals(jar.getManifest().getMainAttributes().getValue("Voxy-Update-Side"))
                    || jar.getEntry("com/aerosmp/voxy/update/AutoUpdater.class") == null
                    || !new String(jar.getInputStream(jar.getJarEntry("META-INF/neoforge.mods.toml"))
                    .readNBytes(8192), StandardCharsets.UTF_8).contains("modId=\"" + id + "\""))
                throw new IOException("Wrong update identity");
        }
    }
    public static void install(Update update) throws Exception {
        verify(update.staged, update.side, update.sha256);
        Path mods = update.game.resolve("mods").toRealPath(), backup = state(update.game).resolve("previous.jar");
        if (!Files.isSameFile(update.installed.getParent(), mods) || Files.isSymbolicLink(update.installed))
            throw new IOException("Invalid installation target");
        Files.copy(update.installed, backup, StandardCopyOption.REPLACE_EXISTING);
        Files.move(update.staged, update.installed, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
    public static void prepare(Update update) throws Exception {
        verify(update.staged, "client", update.sha256);
        Path state = state(update.game), helper = state.resolve("helper.jar"), accepted = state.resolve("accepted");
        launcher(state); Files.deleteIfExists(accepted);
        Files.copy(update.staged, helper, StandardCopyOption.REPLACE_EXISTING);
        ProcessHandle current = ProcessHandle.current(), parent = current.parent().orElseThrow();
        Process helperProcess = new ProcessBuilder(current.info().command().orElseThrow(), "-cp",
                helper.toString(), AutoUpdater.class.getName(), update.game.toString(), Long.toString(current.pid()),
                current.info().startInstant().orElseThrow().toString(), Long.toString(parent.pid()), update.sha256)
                .directory(update.game.toFile()).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(state.resolve("restart.log").toFile())).start();
        while (!Files.exists(accepted) && helperProcess.isAlive()) Thread.sleep(20);
        if (!helperProcess.isAlive()) throw new IOException("Restart helper failed before shutdown");
    }
    public static void main(String[] args) throws Exception {
        Path game = Path.of(args[0]), state = state(game), installed = game.resolve("mods/voxy-client-debug.jar");
        long parent = Long.parseLong(args[3]);
        Files.writeString(state.resolve("accepted"), args[1]);
        ProcessHandle.of(Long.parseLong(args[1])).filter(p -> p.info().startInstant().map(Instant::toString)
                .orElse("").equals(args[2])).ifPresent(p -> p.onExit().join());
        Update update = new Update(game, installed, state.resolve("voxy-client-debug.jar.part"), "client", args[4]);
        String previous = hash(installed);
        install(update);
        Files.deleteIfExists(state.resolve("ready")); Files.deleteIfExists(state.resolve("starting"));
        Desktop.getDesktop().browse(launcher(state));
        ProcessHandle launched = null; boolean announced = false;
        while (!Files.exists(state.resolve("ready"))) {
            if (!announced && Files.exists(state.resolve("starting"))) {
                String[] identity = Files.readString(state.resolve("starting")).split(" ");
                if (identity.length != 4 || !identity[2].equals(Long.toString(parent)) || !identity[3].equals(update.sha256)) throw new IOException("Invalid startup identity");
                announced = true;
                launched = ProcessHandle.of(Long.parseLong(identity[0])).filter(p -> p.info().startInstant().map(Instant::toString).orElse("").equals(identity[1])).orElse(null);
                System.out.println("Client starting " + String.join(" ", identity));
            }
            if (announced && (launched == null || !launched.isAlive())) {
                verify(state.resolve("previous.jar"), "client", previous);
                Files.copy(state.resolve("previous.jar"), installed, StandardCopyOption.REPLACE_EXISTING);
                Desktop.getDesktop().browse(launcher(state));
                throw new IOException("Client failed before readiness; restored previous jar and requested launcher recovery");
            }
            Thread.sleep(100);
        }
        String[] readiness = Files.readString(state.resolve("ready")).split(" ");
        if (!readiness[1].equals(update.sha256) || !ProcessHandle.of(Long.parseLong(readiness[0]))
                .filter(ProcessHandle::isAlive).isPresent()) throw new IOException("Invalid client readiness");
        System.out.println("Client readiness " + String.join(" ", readiness));
    }
    private static URI launcher(Path state) throws IOException {
        String value = Files.readString(state.resolve("launcher")).strip();
        if (!value.matches("modrinth://launch/instance/[a-zA-Z0-9%:_-]+")) throw new IOException("Invalid launcher URI");
        return URI.create(value);
    }
    private static Path state(Path game) throws IOException {
        Path path = game.resolve(".voxy/updater"); Files.createDirectories(path); return path;
    }
    private String command(int seconds, String... command) throws Exception {
        Path output = state(game).resolve("command.log");
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        process.getOutputStream().close();
        if (!process.waitFor(seconds, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new IOException("Command timed out: " + command[0]); }
        if (process.exitValue() != 0) throw new IOException("Command failed: " + command[0]);
        return Files.readString(output);
    }
    private static boolean windows() { return System.getProperty("os.name").startsWith("Windows"); }
    private void log(String message) {
        try {
            Path file = game.resolve("logs/voxy-updater.log"); Files.createDirectories(file.getParent());
            Files.writeString(file, Instant.now() + " " + message.replace('\n', ' ') + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {}
    }
}
