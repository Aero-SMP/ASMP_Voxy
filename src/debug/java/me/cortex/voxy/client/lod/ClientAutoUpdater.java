package me.cortex.voxy.client.lod;

import me.cortex.voxy.client.VoxyClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Development updater included only in debug client builds. */
final class ClientAutoUpdater {
    private static final String SSH_TARGET = "printer@ssh.aerosmp.com";
    private static final String REMOTE_DIRECTORY =
            "/home/printer/Desktop/ASMP_Voxy/build/libs";
    private static final String MINECRAFT_SERVER = "ssh.aerosmp.com:25586";
    private static final String REMOTE_DIAGNOSTICS =
            "/home/printer/Desktop/Creative/logs/client-upload";
    private static final long POLL_SECONDS = 20;
    private static final Pattern DEBUG_JAR = Pattern.compile(
            "^ASMP_voxy-([0-9A-Za-z][0-9A-Za-z.-]*)\\+([0-9.]+)-neoforge-debug\\.jar$");
    private static final Pattern VERSION_TOKEN = Pattern.compile("[0-9]+|[A-Za-z]+");
    private static final AtomicBoolean STARTED = new AtomicBoolean();
    private static volatile boolean readyToConnect;
    private static volatile boolean restartPending;
    private static boolean restartDisconnectRequested;
    private static long nextConnectNanos;

    private ClientAutoUpdater() {}

    static void start() {
        if (!STARTED.compareAndSet(false, true)) return;
        Thread.ofPlatform().daemon().name("Voxy debug auto-updater").start(() -> {
            while (true) {
                try {
                    if (checkAndInstall()) return;
                } catch (Throwable failure) {
                    ClientLodDebug.updaterEvent("state=FAILED type="
                            + failure.getClass().getSimpleName() + " message="
                            + oneLine(failure.getMessage()));
                    readyToConnect = true;
                }
                try {
                    uploadDiagnostics();
                } catch (Throwable failure) {
                    ClientLodDebug.updaterEvent("state=UPLOAD_FAILED type="
                            + failure.getClass().getSimpleName() + " message="
                            + oneLine(failure.getMessage()));
                }
                try {
                    TimeUnit.SECONDS.sleep(POLL_SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        });
    }

    /** Runs on Minecraft's client thread through the existing debug tick. */
    static void tick() {
        Minecraft minecraft = Minecraft.getInstance();
        if (restartPending) {
            if (minecraft.getConnection() != null || minecraft.level != null) {
                if (!restartDisconnectRequested) {
                    restartDisconnectRequested = true;
                    ClientLodDebug.updaterEvent("state=DISCONNECTING_FOR_RESTART");
                    minecraft.disconnect();
                }
                return;
            }
            restartPending = false;
            ClientLodDebug.updaterEvent("state=EXITING_FOR_RESTART");
            minecraft.stop();
            return;
        }
        if (!readyToConnect) return;
        if (!minecraft.isGameLoadFinished() || minecraft.getConnection() != null
                || minecraft.level != null || minecraft.screen == null
                || minecraft.screen instanceof ConnectScreen) return;
        long now = System.nanoTime();
        if (now < nextConnectNanos) return;
        nextConnectNanos = now + TimeUnit.SECONDS.toNanos(5);
        ClientLodDebug.updaterEvent("state=AUTO_CONNECT address=" + MINECRAFT_SERVER);
        ServerData server = new ServerData("AeroSMP", MINECRAFT_SERVER,
                ServerData.Type.OTHER);
        ConnectScreen.startConnecting(minecraft.screen, minecraft,
                ServerAddress.parseString(MINECRAFT_SERVER), server, false, null);
    }

    private static boolean checkAndInstall() throws Exception {
        ClientLodDebug.updaterEvent("state=CHECKING target=" + SSH_TARGET);
        CommandResult listing = run(Duration.ofSeconds(15), sshExecutable(),
                "-o", "BatchMode=yes", "-o", "ConnectTimeout=5", SSH_TARGET,
                "LC_ALL=C find " + REMOTE_DIRECTORY
                        + " -maxdepth 1 -type f -printf '%f\\n' | sort");
        if (listing.exitCode != 0) {
            throw new IOException("ssh listing failed (exit " + listing.exitCode + "): "
                    + oneLine(listing.output));
        }

        List<Artifact> artifacts = listing.output.lines()
                .map(String::trim)
                .filter(name -> !name.isEmpty())
                .peek(name -> ClientLodDebug.updaterEvent("remoteFile=" + oneLine(name)))
                .map(ClientAutoUpdater::artifact)
                .filter(java.util.Objects::nonNull)
                .toList();
        Artifact newest = artifacts.stream().max(Comparator.comparing(
                Artifact::version, ClientAutoUpdater::compareVersions)).orElse(null);
        if (newest == null) {
            throw new IOException("build/libs contains no debug client JAR");
        }
        if (compareVersions(newest.version, VoxyClient.MOD_VERSION) <= 0) {
            ClientLodDebug.updaterEvent("state=CURRENT local=" + VoxyClient.MOD_VERSION
                    + " remote=" + newest.version);
            readyToConnect = true;
            return false;
        }

        Path gameDirectory = Minecraft.getInstance().gameDirectory.toPath().toAbsolutePath();
        Path modsDirectory = gameDirectory.resolve("mods");
        Path current = findCurrentJar(modsDirectory);
        Path updateDirectory = gameDirectory.resolve(".voxy-updater");
        Files.createDirectories(updateDirectory);
        Path staged = updateDirectory.resolve(newest.filename + ".part");
        Path backup = updateDirectory.resolve(current.getFileName() + ".backup");
        Files.deleteIfExists(staged);

        ClientLodDebug.updaterEvent("state=DOWNLOADING version=" + newest.version);
        CommandResult download = run(Duration.ofMinutes(3), scpExecutable(),
                "-q", "-o", "BatchMode=yes", "-o", "ConnectTimeout=10",
                SSH_TARGET + ':' + REMOTE_DIRECTORY + '/' + newest.filename,
                staged.toString());
        if (download.exitCode != 0) {
            Files.deleteIfExists(staged);
            throw new IOException("scp failed (exit " + download.exitCode + "): "
                    + oneLine(download.output));
        }
        validate(newest, staged);

        Path installed = modsDirectory.resolve(newest.filename);
        if (Files.isSymbolicLink(current) || Files.isSymbolicLink(installed)) {
            throw new IOException("refusing to replace a symbolic-link mod JAR");
        }
        Files.copy(current, backup, StandardCopyOption.REPLACE_EXISTING);
        try {
            Files.delete(current);
            move(staged, installed);
        } catch (Throwable failure) {
            Files.deleteIfExists(installed);
            if (!Files.exists(current)) {
                move(backup, current);
            }
            throw failure;
        }
        try {
            launchRestartHelper(installed, gameDirectory);
        } catch (Throwable failure) {
            Files.deleteIfExists(installed);
            move(backup, current);
            throw failure;
        }
        Files.deleteIfExists(backup);
        ClientLodDebug.updaterEvent("state=INSTALLED from=" + VoxyClient.MOD_VERSION
                + " to=" + newest.version + " restart=NOW");
        // Finish the current tick, disconnect on a following post-tick, then stop only
        // after the world is gone. This keeps other mods from observing partial teardown.
        restartPending = true;
        return true;
    }

    private static void uploadDiagnostics() throws Exception {
        Path gameDirectory = Minecraft.getInstance().gameDirectory.toPath().toAbsolutePath();
        Path staging = gameDirectory.resolve(".voxy-updater").resolve("diagnostics");
        Files.createDirectories(staging);
        ArrayList<String> sources = new ArrayList<>();
        StringBuilder snapshotStatus = new StringBuilder()
                .append("time=").append(Instant.now()).append('\n')
                .append("version=").append(VoxyClient.MOD_VERSION).append('\n');
        snapshot(gameDirectory.resolve("logs").resolve("voxy-client-debug.log"),
                staging.resolve("voxy-client-debug.log"), sources, snapshotStatus);
        snapshot(gameDirectory.resolve("logs").resolve("latest.log"),
                staging.resolve("latest.log"), sources, snapshotStatus);
        snapshot(gameDirectory.resolve(".voxy-updater").resolve("restart.log"),
                staging.resolve("restart.log"), sources, snapshotStatus);
        snapshot(gameDirectory.resolve(".voxy-updater").resolve("relaunched-java.log"),
                staging.resolve("relaunched-java.log"), sources, snapshotStatus);
        try {
            Path screenshot = newestFile(gameDirectory.resolve("screenshots"));
            if (screenshot != null) {
                snapshot(screenshot, staging.resolve("latest-screenshot"
                        + extension(screenshot.getFileName().toString())), sources,
                        snapshotStatus);
            }
        } catch (IOException failure) {
            snapshotStatus.append("screenshots=FAILED:")
                    .append(oneLine(failure.getMessage())).append('\n');
        }
        Path status = staging.resolve("upload-status.txt");
        Files.writeString(status, snapshotStatus);
        sources.add(status.toString());

        CommandResult mkdir = run(Duration.ofSeconds(15), sshExecutable(),
                "-o", "BatchMode=yes", "-o", "ConnectTimeout=5", SSH_TARGET,
                "mkdir -p " + REMOTE_DIAGNOSTICS);
        if (mkdir.exitCode != 0) {
            throw new IOException("diagnostic directory creation failed (exit "
                    + mkdir.exitCode + "): " + oneLine(mkdir.output));
        }
        ArrayList<String> command = new ArrayList<>(sources.size() + 9);
        command.add(scpExecutable());
        command.add("-q");
        command.add("-o");
        command.add("BatchMode=yes");
        command.add("-o");
        command.add("ConnectTimeout=10");
        command.addAll(sources);
        command.add(SSH_TARGET + ':' + REMOTE_DIAGNOSTICS + '/');
        CommandResult upload = run(Duration.ofSeconds(45), command.toArray(String[]::new));
        if (upload.exitCode != 0) {
            throw new IOException("diagnostic upload failed (exit " + upload.exitCode
                    + "): " + oneLine(upload.output));
        }
        ClientLodDebug.updaterEvent("state=DIAGNOSTICS_UPLOADED files=" + sources.size());
    }

    private static void snapshot(Path source, Path destination, List<String> destinations,
                                 StringBuilder status) {
        if (!Files.isRegularFile(source)) {
            status.append(source.getFileName()).append("=ABSENT\n");
            return;
        }
        try {
            Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
            destinations.add(destination.toString());
            status.append(source.getFileName()).append("=OK\n");
        } catch (IOException failure) {
            status.append(source.getFileName()).append("=FAILED:")
                    .append(oneLine(failure.getMessage())).append('\n');
        }
    }

    private static Path newestFile(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) return null;
        try (var files = Files.list(directory)) {
            return files.filter(Files::isRegularFile).max(Comparator.comparingLong(path -> {
                try {
                    return Files.getLastModifiedTime(path).toMillis();
                } catch (IOException ignored) {
                    return Long.MIN_VALUE;
                }
            })).orElse(null);
        }
    }

    private static String extension(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || filename.length() - dot > 6) return ".png";
        String extension = filename.substring(dot).toLowerCase(java.util.Locale.ROOT);
        return extension.matches("\\.[a-z0-9]+") ? extension : ".png";
    }

    private static Path findCurrentJar(Path modsDirectory) throws IOException {
        try (var files = Files.list(modsDirectory)) {
            List<Path> matches = files.filter(Files::isRegularFile)
                    .filter(path -> artifact(path.getFileName().toString()) != null)
                    .toList();
            if (matches.size() != 1) {
                throw new IOException("expected one Voxy debug JAR in "
                        + modsDirectory + ", found " + matches.size());
            }
            Path current = matches.getFirst().toAbsolutePath();
            Artifact disk = artifact(current.getFileName().toString());
            if (!disk.version.equals(VoxyClient.MOD_VERSION)) {
                ClientLodDebug.updaterEvent("state=DISK_AHEAD loaded="
                        + VoxyClient.MOD_VERSION + " disk=" + disk.version);
            }
            return current;
        }
    }

    private static void validate(Artifact artifact, Path staged) throws IOException {
        try (JarFile jar = new JarFile(staged.toFile(), true)) {
            String implementation = jar.getManifest() == null ? null
                    : jar.getManifest().getMainAttributes()
                    .getValue("Implementation-Version");
            if (!artifact.version.equals(implementation)
                    || jar.getJarEntry("META-INF/neoforge.mods.toml") == null
                    || jar.getJarEntry("me/cortex/voxy/client/lod/ClientAutoUpdater.class")
                    == null) {
                throw new IOException("downloaded JAR failed Voxy debug artifact validation");
            }
        }
    }

    private static void launchRestartHelper(Path installed, Path gameDirectory)
            throws IOException, InterruptedException {
        ProcessHandle current = ProcessHandle.current();
        ProcessHandle.Info info = current.info();
        String executable = info.command().orElseThrow(
                () -> new IOException("current Java executable is unavailable"));
        List<String> restartCommand = currentCommand(current.pid(), info, executable);

        Path updaterDirectory = gameDirectory.resolve(".voxy-updater");
        Path commandFile = updaterDirectory.resolve("restart-command.bin");
        writeRestartCommand(commandFile, restartCommand);

        List<String> helper = new ArrayList<>(7);
        helper.add(executable);
        helper.add("-cp");
        helper.add(installed.toString());
        helper.add(ClientUpdateRestart.class.getName());
        helper.add(Long.toString(current.pid()));
        helper.add(gameDirectory.toString());
        helper.add(commandFile.toString());
        Path restartLog = updaterDirectory.resolve("restart.log");
        Process process;
        try {
            process = new ProcessBuilder(helper).directory(gameDirectory.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(restartLog.toFile()))
                    .start();
        } catch (IOException failure) {
            Files.deleteIfExists(commandFile);
            throw failure;
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (Files.exists(commandFile) && process.isAlive()
                && System.nanoTime() < deadline) {
            try {
                Thread.sleep(25);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while starting restart helper", interrupted);
            }
        }
        if (Files.exists(commandFile)) {
            process.destroyForcibly();
            Files.deleteIfExists(commandFile);
            throw new IOException("restart helper failed to accept launch command; see "
                    + restartLog);
        }
    }

    private static List<String> currentCommand(long pid, ProcessHandle.Info info,
                                               String executable)
            throws IOException, InterruptedException {
        String[] processArguments = info.arguments().orElse(null);
        if (processArguments != null) {
            ArrayList<String> command = new ArrayList<>(processArguments.length + 1);
            command.add(executable);
            command.addAll(List.of(processArguments));
            return restoreExactModrinthArguments(command);
        }
        if (isWindows()) {
            String commandLine = info.commandLine().orElse(null);
            if (commandLine == null || commandLine.isBlank()) {
                CommandResult result = run(Duration.ofSeconds(10), "powershell.exe",
                        "-NoProfile", "-NonInteractive", "-Command",
                        "[Console]::Out.Write((Get-CimInstance Win32_Process -Filter "
                                + "\"ProcessId = " + pid + "\").CommandLine)");
                if (result.exitCode == 0) commandLine = result.output;
            }
            if (commandLine != null && !commandLine.isBlank()) {
                List<String> parsed = parseWindowsCommandLine(commandLine);
                if (!parsed.isEmpty()) return restoreExactModrinthArguments(parsed);
            }
        }

        String application = System.getProperty("sun.java.command", "");
        String classPath = System.getProperty("java.class.path", "");
        if (application.isBlank() || classPath.isBlank()) {
            throw new IOException("current Java launch command is unavailable");
        }
        List<String> applicationArguments = isWindows()
                ? parseWindowsCommandLine(application) : List.of(application.split(" "));
        ArrayList<String> command = new ArrayList<>(
                ManagementFactory.getRuntimeMXBean().getInputArguments().size()
                        + applicationArguments.size() + 3);
        command.add(executable);
        command.addAll(ManagementFactory.getRuntimeMXBean().getInputArguments());
        command.add("-cp");
        command.add(classPath);
        command.addAll(applicationArguments);
        return restoreExactModrinthArguments(command);
    }

    private static List<String> restoreExactModrinthArguments(List<String> command) {
        int wrapper = command.indexOf("com.modrinth.theseus.MinecraftLaunch");
        String encoded = System.getProperty("modrinth.process.args");
        if (wrapper < 0 || wrapper + 1 >= command.size()
                || encoded == null || encoded.isEmpty()) return command;
        ArrayList<String> exact = new ArrayList<>(command.subList(0, wrapper + 2));
        exact.addAll(List.of(encoded.split("\u001f", -1)));
        return exact;
    }

    /** Implements the Windows CommandLineToArgvW backslash-and-quote rules. */
    private static List<String> parseWindowsCommandLine(String value) throws IOException {
        ArrayList<String> arguments = new ArrayList<>();
        int cursor = 0;
        while (true) {
            while (cursor < value.length() && isCommandWhitespace(value.charAt(cursor))) {
                cursor++;
            }
            if (cursor == value.length()) return arguments;
            StringBuilder argument = new StringBuilder();
            boolean quoted = false;
            while (cursor < value.length()) {
                char current = value.charAt(cursor);
                if (!quoted && isCommandWhitespace(current)) break;
                if (current != '\\') {
                    if (current == '"') quoted = !quoted;
                    else argument.append(current);
                    cursor++;
                    continue;
                }
                int slashStart = cursor;
                while (cursor < value.length() && value.charAt(cursor) == '\\') cursor++;
                int slashes = cursor - slashStart;
                if (cursor < value.length() && value.charAt(cursor) == '"') {
                    argument.append("\\".repeat(slashes / 2));
                    if ((slashes & 1) == 0) quoted = !quoted;
                    else argument.append('"');
                    cursor++;
                } else {
                    argument.append("\\".repeat(slashes));
                }
            }
            if (quoted) throw new IOException("unterminated quote in Java command line");
            arguments.add(argument.toString());
        }
    }

    private static boolean isCommandWhitespace(char value) {
        return value == ' ' || value == '\t';
    }

    private static void writeRestartCommand(Path path, List<String> command)
            throws IOException {
        if (command.isEmpty() || command.size() > 16_384) {
            throw new IOException("invalid restart command argument count: " + command.size());
        }
        try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(
                Files.newOutputStream(path)))) {
            output.writeInt(command.size());
            for (String argument : command) {
                byte[] encoded = argument.getBytes(StandardCharsets.UTF_8);
                if (encoded.length > 16 * 1024 * 1024) {
                    throw new IOException("restart argument exceeds 16 MiB");
                }
                output.writeInt(encoded.length);
                output.write(encoded);
            }
        }
        try {
            Files.setPosixFilePermissions(path, Set.of(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException ignored) {
            // Windows protects this temporary file with the user's normal ACL.
        }
    }

    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Artifact artifact(String filename) {
        Matcher matcher = DEBUG_JAR.matcher(filename);
        return matcher.matches() ? new Artifact(filename, matcher.group(1)) : null;
    }

    private static int compareVersions(String left, String right) {
        List<String> a = tokens(left);
        List<String> b = tokens(right);
        for (int index = 0; index < Math.max(a.size(), b.size()); index++) {
            if (index >= a.size()) return remainingQualifier(b, index) ? 1 : -1;
            if (index >= b.size()) return remainingQualifier(a, index) ? -1 : 1;
            String x = a.get(index);
            String y = b.get(index);
            boolean xn = Character.isDigit(x.charAt(0));
            boolean yn = Character.isDigit(y.charAt(0));
            int compared = xn && yn ? new BigInteger(x).compareTo(new BigInteger(y))
                    : xn ? 1 : yn ? -1 : x.compareToIgnoreCase(y);
            if (compared != 0) return compared;
        }
        return 0;
    }

    private static boolean remainingQualifier(List<String> tokens, int start) {
        for (int index = start; index < tokens.size(); index++) {
            String token = tokens.get(index);
            if (!token.chars().allMatch(Character::isDigit)
                    || new BigInteger(token).signum() != 0) return true;
        }
        return false;
    }

    private static List<String> tokens(String version) {
        ArrayList<String> result = new ArrayList<>();
        Matcher matcher = VERSION_TOKEN.matcher(version);
        while (matcher.find()) result.add(matcher.group());
        return result;
    }

    private static CommandResult run(Duration timeout, String... command)
            throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread reader = Thread.ofVirtual().start(() -> {
            try (var input = process.getInputStream()) {
                input.transferTo(output);
            } catch (IOException ignored) {}
        });
        if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            process.waitFor();
            reader.join();
            throw new IOException("command timed out: " + command[0]);
        }
        reader.join();
        return new CommandResult(process.exitValue(), output.toString(StandardCharsets.UTF_8));
    }

    private static String sshExecutable() {
        return isWindows() ? "ssh.exe" : "ssh";
    }

    private static String scpExecutable() {
        return isWindows() ? "scp.exe" : "scp";
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").startsWith("Windows");
    }

    private static String oneLine(String value) {
        return String.valueOf(value).replace('\n', ' ').replace('\r', ' ');
    }

    private record Artifact(String filename, String version) {}
    private record CommandResult(int exitCode, String output) {}
}

/** Minimal standalone process used to restart the exact Java executable after update. */
final class ClientUpdateRestart {
    private static final int MAX_ARGUMENTS = 16_384;
    private static final int MAX_ARGUMENT_BYTES = 16 * 1024 * 1024;
    private static final String SSH_TARGET = "printer@ssh.aerosmp.com";
    private static final String REMOTE_LOG =
            "/home/printer/Desktop/Creative/logs/client-upload/restart.log";

    private ClientUpdateRestart() {}

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 3) throw new IllegalArgumentException("missing restart command");
        long oldPid = Long.parseLong(arguments[0]);
        Path gameDirectory = Path.of(arguments[1]);
        Path commandFile = Path.of(arguments[2]);
        Path restartLog = gameDirectory.resolve(".voxy-updater").resolve("restart.log");
        Path launchLog = gameDirectory.resolve(".voxy-updater")
                .resolve("relaunched-java.log");
        try {
            append(restartLog, "helper-start oldPid=" + oldPid);
            List<String> command = stabilizeLaunchFiles(
                    readCommand(commandFile), commandFile.getParent(), gameDirectory);
            Files.delete(commandFile);
            append(restartLog, "command-ready arguments=" + command.size());
            stopOldProcess(oldPid);
            append(restartLog, "old-process-stopped");
            Thread.sleep(500);
            Files.deleteIfExists(launchLog);
            Process launched = new ProcessBuilder(command)
                    .directory(gameDirectory.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(launchLog.toFile()))
                    .start();
            append(restartLog, "new-process-started pid=" + launched.pid());
            try {
                int exitCode = launched.onExit().get(45, TimeUnit.SECONDS).exitValue();
                throw new IOException("restarted Java process exited during startup: "
                        + exitCode);
            } catch (TimeoutException running) {
                append(restartLog, "new-process-alive-after-45-seconds");
            }
        } catch (Throwable failure) {
            append(restartLog, "restart-failed type=" + failure.getClass().getName()
                    + " message=" + String.valueOf(failure.getMessage()));
            uploadFailure(restartLog, launchLog);
            throw failure;
        } finally {
            deleteLaunchCopies(commandFile.getParent(), restartLog);
        }
    }

    private static List<String> stabilizeLaunchFiles(List<String> command, Path directory,
                                                     Path gameDirectory)
            throws IOException {
        ArrayList<String> stable = new ArrayList<>(command);
        for (int index = 1; index < stable.size(); index++) {
            String argument = stable.get(index);
            if (argument.startsWith("@") && !argument.startsWith("@@")
                    && argument.length() > 1) {
                copyLaunchFile(stable, index, argument.substring(1), "arguments", ".txt",
                        "@", "", directory);
                continue;
            }
            if (!argument.startsWith("-javaagent:")
                    || argument.length() == "-javaagent:".length()) continue;
            String specification = argument.substring("-javaagent:".length());
            int options = specification.indexOf('=');
            String filename = options < 0 ? specification : specification.substring(0, options);
            String suffix = options < 0 ? "" : specification.substring(options);
            if (!copyLaunchFile(stable, index, filename, "javaagent", ".jar",
                    "-javaagent:", suffix, directory)) {
                // Launchers commonly inject a disposable tracking agent and delete it as
                // soon as Minecraft starts. It cannot be replayed and is not required by
                // Minecraft itself, so omit only that unavailable agent.
                stable.remove(index--);
            }
        }
        unwrapModrinthLauncher(stable);
        canonicalizeGameDirectory(stable, gameDirectory);
        return stable;
    }

    private static void canonicalizeGameDirectory(ArrayList<String> command,
                                                  Path gameDirectory)
            throws IOException {
        int option = command.indexOf("--gameDir");
        if (option < 0 || option + 1 >= command.size()) {
            throw new IOException("Minecraft launch command omitted --gameDir");
        }
        int end = option + 2;
        while (end < command.size() && !command.get(end).startsWith("--")) end++;
        command.set(option + 1, gameDirectory.toString());
        if (end > option + 2) command.subList(option + 2, end).clear();
    }

    private static void unwrapModrinthLauncher(ArrayList<String> command)
            throws IOException {
        int wrapper = command.indexOf("com.modrinth.theseus.MinecraftLaunch");
        if (wrapper < 0) return;
        if (wrapper + 1 >= command.size()) {
            throw new IOException("Modrinth launch wrapper omitted the Minecraft main class");
        }
        String minecraftMain = command.remove(wrapper + 1);
        if (minecraftMain.isBlank() || minecraftMain.startsWith("-")) {
            throw new IOException("invalid Minecraft main class after Modrinth wrapper");
        }
        command.set(wrapper, minecraftMain);
    }

    private static boolean copyLaunchFile(ArrayList<String> command, int index,
                                          String filename, String kind, String extension,
                                          String prefix, String suffix, Path directory)
            throws IOException {
        Path source;
        try {
            source = Path.of(filename);
        } catch (RuntimeException malformed) {
            return false;
        }
        if (!Files.isRegularFile(source)) return false;
        Path copy = directory.resolve("launch-" + kind + '-' + index + extension);
        Files.copy(source, copy, StandardCopyOption.REPLACE_EXISTING);
        restrict(copy);
        command.set(index, prefix + copy.toAbsolutePath() + suffix);
        return true;
    }

    private static void deleteLaunchCopies(Path directory, Path restartLog) {
        try (var paths = Files.list(directory)) {
            for (Path path : paths.filter(candidate -> {
                String name = candidate.getFileName().toString();
                return name.startsWith("launch-arguments-")
                        || name.startsWith("launch-javaagent-");
            }).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException failure) {
            append(restartLog, "launch-copy-cleanup-failed message="
                    + String.valueOf(failure.getMessage()));
        }
    }

    private static void restrict(Path path) throws IOException {
        try {
            Files.setPosixFilePermissions(path, Set.of(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException ignored) {
            // Windows applies the current user's normal ACL.
        }
    }

    private static void stopOldProcess(long oldPid) throws Exception {
        ProcessHandle process = ProcessHandle.of(oldPid).orElse(null);
        if (process == null) return;
        long helperPid = ProcessHandle.current().pid();
        List<ProcessHandle> children = process.descendants()
                .filter(child -> child.pid() != helperPid).toList();
        try {
            process.onExit().get(30, TimeUnit.SECONDS);
        } catch (TimeoutException gracefulStopFailed) {
            for (ProcessHandle child : children) child.destroyForcibly();
            process.destroyForcibly();
            try {
                process.onExit().get(10, TimeUnit.SECONDS);
            } catch (TimeoutException timeout) {
                throw new IOException("old Java process did not terminate: " + oldPid,
                        timeout);
            }
        } finally {
            long childDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            for (ProcessHandle child : children) {
                if (!child.isAlive()) continue;
                long remaining = childDeadline - System.nanoTime();
                if (remaining > 0) {
                    try {
                        child.onExit().get(remaining, TimeUnit.NANOSECONDS);
                    } catch (TimeoutException ignored) {}
                }
                if (child.isAlive()) child.destroyForcibly();
            }
        }
    }

    private static List<String> readCommand(Path path) throws IOException {
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(
                Files.newInputStream(path)))) {
            int count = input.readInt();
            if (count <= 0 || count > MAX_ARGUMENTS) {
                throw new IOException("invalid restart command argument count: " + count);
            }
            ArrayList<String> command = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                int length = input.readInt();
                if (length < 0 || length > MAX_ARGUMENT_BYTES) {
                    throw new IOException("invalid restart argument length: " + length);
                }
                byte[] encoded = input.readNBytes(length);
                if (encoded.length != length) {
                    throw new IOException("truncated restart argument");
                }
                command.add(new String(encoded, StandardCharsets.UTF_8));
            }
            if (input.read() != -1) throw new IOException("trailing restart command data");
            return command;
        }
    }

    private static void append(Path log, String message) {
        try {
            Files.writeString(log, Instant.now() + " " + message + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {}
    }

    private static void uploadFailure(Path... logs) {
        try {
            ArrayList<String> command = new ArrayList<>();
            command.add(System.getProperty("os.name", "").startsWith("Windows")
                    ? "scp.exe" : "scp");
            command.add("-q");
            command.add("-o");
            command.add("BatchMode=yes");
            command.add("-o");
            command.add("ConnectTimeout=10");
            for (Path log : logs) {
                if (Files.isRegularFile(log)) command.add(log.toString());
            }
            command.add(SSH_TARGET + ':' + REMOTE_LOG.substring(0,
                    REMOTE_LOG.lastIndexOf('/') + 1));
            Process upload = new ProcessBuilder(command).redirectErrorStream(true).start();
            if (!upload.waitFor(30, TimeUnit.SECONDS)) upload.destroyForcibly();
        } catch (Throwable ignored) {}
    }

}
