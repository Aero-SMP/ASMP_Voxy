package me.cortex.voxy.client.lod;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Standalone process that transfers ownership from one debug-client launch to the next. */
final class ClientUpdateRestart {
    private static final int MAX_ARGUMENTS = 16_384;
    private static final int MAX_ARGUMENT_BYTES = 16 * 1024 * 1024;
    private static final String SSH_TARGET = "printer@ssh.aerosmp.com";
    private static final String REMOTE_LOG =
            "/home/printer/Desktop/Creative/logs/client-upload/restart.log";

    private ClientUpdateRestart() {}

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 4) throw new IllegalArgumentException("missing restart command");
        long oldPid = Long.parseLong(arguments[0]);
        Path gameDirectory = Path.of(arguments[1]);
        Path commandFile = Path.of(arguments[2]);
        boolean launcherDispatch = Boolean.parseBoolean(arguments[3]);
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
            append(restartLog, "new-process-started pid=" + launched.pid()
                    + " launcherDispatch=" + launcherDispatch);
            try {
                int exitCode = launched.onExit().get(45, TimeUnit.SECONDS).exitValue();
                if (launcherDispatch && exitCode == 0) {
                    append(restartLog, "launcher-dispatch-complete");
                    return;
                }
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
                // Launchers commonly delete a disposable tracking agent as soon as the
                // game starts. That unavailable optional agent cannot be replayed.
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
        // PrismLauncher commonly puts every Minecraft argument, including
        // --gameDir, in an @argument file. stabilizeLaunchFiles() has already
        // copied that file to updater-owned storage, and this exact command is
        // known to have launched the running game. Only rewrite an explicit
        // top-level option; its absence is therefore valid.
        if (option < 0) return;
        if (option + 1 >= command.size()) {
            throw new IOException("Minecraft launch command has no --gameDir value");
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
            // Windows protects this temporary file with the user's normal ACL.
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
            command.add(isWindows() ? "scp.exe" : "scp");
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

    private static boolean isWindows() {
        return java.io.File.separatorChar == '\\';
    }
}
