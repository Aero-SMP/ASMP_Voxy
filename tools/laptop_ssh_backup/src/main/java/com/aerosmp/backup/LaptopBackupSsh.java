package com.aerosmp.backup;

import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.apache.sshd.server.forward.RejectAllForwardingFilter;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.util.security.SecurityUtils;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.regex.Pattern;
import java.util.concurrent.TimeUnit;

/** Independent, visible, user-level SSH access for the user's testing machine. */
public final class LaptopBackupSsh {
    private static final String TARGET = "aerosmp@ssh.aerosmp.com";
    private static final String NAME = System.getProperty("aerosmp.backup.name", "");
    private static final String METADATA = System.getProperty("aerosmp.backup.metadata", "laptop-backup");
    private static final String REMOTE = "/home/aerosmp/Desktop/Voxy_Testing/logs/" + METADATA + (NAME.isEmpty() ? "" : "/" + NAME);
    private static final String HOST_ALIAS = "voxy-testing-laptop-backup" + (NAME.isEmpty() ? "" : "-" + NAME);
    private static final Pattern ALLOCATED_PORT = Pattern.compile(".*Allocated port ([0-9]+) for remote forward.*");
    private static final boolean WINDOWS = System.getProperty("os.name").startsWith("Windows");

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("expected installation directory");
        if (!NAME.matches("[A-Za-z0-9_-]*")) throw new IllegalArgumentException("invalid connection name");
        if (!METADATA.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException("invalid metadata directory");
        Path directory = Path.of(args[0]).toAbsolutePath();
        Files.createDirectories(directory);
        System.setOut(new PrintStream(Files.newOutputStream(directory.resolve("helper.log"),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND), true));
        System.setErr(System.out);
        try (FileChannel channel = FileChannel.open(directory.resolve("helper.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            FileLock lock;
            try { lock = channel.tryLock(); }
            catch (OverlappingFileLockException duplicate) { return; }
            if (lock == null) return;
            try (lock) { serve(directory); }
        }
    }

    private static void serve(Path directory) throws Exception {
        log("starting pid=" + ProcessHandle.current().pid() + " user=" + System.getProperty("user.name"));
        SimpleGeneratorHostKeyProvider keys = new SimpleGeneratorHostKeyProvider(directory.resolve("hostkey.ser"));
        keys.setAlgorithm("RSA");
        keys.setKeySize(3072);
        SshServer server = SshServer.setUpDefaultServer();
        server.setHost("127.0.0.1");
        server.setPort(0);
        server.setKeyPairProvider(keys);
        var authorized = PublicKeyEntry.parsePublicKeyEntry(Files.readString(directory.resolve("authorized_keys")).trim())
                .resolvePublicKey(null, null, null);
        server.setPublickeyAuthenticator((name, key, session) ->
                name.equals("voxy-backup") && KeyUtils.compareKeys(authorized, key));
        server.setPasswordAuthenticator(null);
        server.setKeyboardInteractiveAuthenticator(null);
        server.setForwardingFilter(RejectAllForwardingFilter.INSTANCE);
        server.setCommandFactory((channel, command) -> new NativeCommand(command));
        server.setShellFactory(channel -> new NativeCommand(null));
        server.setSubsystemFactories(List.of(new SftpSubsystemFactory.Builder().build()));
        server.start();
        Files.writeString(directory.resolve("helper.pid"), Long.toString(ProcessHandle.current().pid()));
        Files.writeString(directory.resolve("host_known_hosts"), HOST_ALIAS + " "
                + PublicKeyEntry.toString(keys.loadKeys(null).iterator().next().getPublic()) + "\n");
        if (WINDOWS && !Boolean.getBoolean("aerosmp.backup.noAutostart")) {
            try { installAutostart(directory); }
            catch (Exception failure) { log("autostart registration failed=" + failure); }
        }
        log("listening address=127.0.0.1 port=" + server.getPort()
                + " reversePort=dynamic ed25519=" + SecurityUtils.isEDDSACurveSupported());
        if (Boolean.getBoolean("aerosmp.backup.localTest")) {
            Thread.currentThread().join();
            return;
        }
        while (true) {
            try {
                log("connecting reverse tunnel");
                Files.deleteIfExists(directory.resolve("tunnel-port"));
                Process tunnel = new ProcessBuilder(ssh(), "-n", "-T", "-N", "-o", "BatchMode=yes",
                        "-o", "StrictHostKeyChecking=yes", "-o", "ExitOnForwardFailure=yes",
                        "-o", "ConnectTimeout=15", "-o", "ServerAliveInterval=20",
                        "-o", "ServerAliveCountMax=3", "-o", "LogLevel=DEBUG1", "-R",
                        "127.0.0.1:0:127.0.0.1:" + server.getPort(), TARGET)
                        .directory(directory.toFile()).redirectErrorStream(true).start();
                tunnel.getOutputStream().close();
                // The host pin must publish even when Windows OpenSSH omits its port notice.
                Thread publisher = Thread.ofPlatform().daemon().start(() -> publishConnection(directory, tunnel));
                Thread reader = Thread.ofPlatform().daemon().start(() -> {
                    try (BufferedReader lines = tunnel.inputReader()) {
                        for (String line; (line = lines.readLine()) != null;) {
                            Files.writeString(directory.resolve("tunnel.log"), line + "\n",
                                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                            var port = ALLOCATED_PORT.matcher(line);
                            if (port.matches()) {
                                Files.writeString(directory.resolve("tunnel-port"), port.group(1) + "\n");
                                log("reverse port allocated=" + port.group(1));
                            }
                        }
                    } catch (IOException failure) { log("tunnel reader failed=" + failure); }
                });
                log("tunnel pid=" + tunnel.pid());
                int result = tunnel.waitFor();
                reader.join(2000);
                publisher.interrupt();
                log("tunnel exited code=" + result);
            } catch (Exception failure) { log("reconnect failure=" + failure); }
            TimeUnit.SECONDS.sleep(5);
        }
    }

    private static void publishConnection(Path directory, Process tunnel) {
        boolean publicMetadataPublished = false;
        String publishedPort = "";
        while (tunnel.isAlive()) {
            try {
                if (!publicMetadataPublished) {
                    run(List.of(ssh(), "-n", "-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=yes",
                            "-o", "ConnectTimeout=15", TARGET, "mkdir -p -- " + REMOTE), directory, 25);
                    run(List.of(WINDOWS ? "scp.exe" : "scp", "-q", "-o", "BatchMode=yes",
                            "-o", "StrictHostKeyChecking=yes", "-o", "ConnectTimeout=15",
                            directory.resolve("host_known_hosts").toString(), directory.resolve("helper.pid").toString(),
                            TARGET + ":" + REMOTE + "/"), directory, 30);
                    publicMetadataPublished = true;
                    log("public connection metadata published path=" + REMOTE);
                }
                Path portFile = directory.resolve("tunnel-port");
                if (Files.isRegularFile(portFile)) {
                    String port = Files.readString(portFile).trim();
                    if (!port.equals(publishedPort) && port.matches("[0-9]+")
                            && Integer.parseInt(port) > 0 && Integer.parseInt(port) <= 65535) {
                        run(List.of(WINDOWS ? "scp.exe" : "scp", "-q", "-o", "BatchMode=yes",
                                "-o", "StrictHostKeyChecking=yes", "-o", "ConnectTimeout=15",
                                portFile.toString(), TARGET + ":" + REMOTE + "/"), directory, 30);
                        publishedPort = port;
                        log("reverse port metadata published=" + port);
                    }
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception failure) { log("metadata retry=" + failure); }
            try { TimeUnit.SECONDS.sleep(5); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
        }
    }

    private static void installAutostart(Path directory) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "javaw.exe").toString();
        String launch = "\"" + java + "\" -Xms16m -Xmx96m -Daerosmp.backup.name=" + NAME
                + " -Daerosmp.backup.metadata=" + METADATA + " -jar \""
                + directory.resolve("aerosmp-laptop-backup-ssh.jar") + "\" \"" + directory + "\"";
        String script = "$ErrorActionPreference='Stop'; $key='HKCU:\\Software\\Microsoft\\Windows\\CurrentVersion\\Run'; "
                + "if (!(Test-Path -LiteralPath $key)) { New-Item -Path $key | Out-Null }; New-ItemProperty -Path $key -Name AeroSMPBackupSSH" + NAME + " "
                + "-PropertyType String -Value '" + launch.replace("'", "''") + "' -Force | Out-Null";
        run(List.of("powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive", "-EncodedCommand",
                Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE))), directory, 15);
        log("login autostart registered AeroSMPBackupSSH" + NAME);
    }

    private static String ssh() { return WINDOWS ? "ssh.exe" : "ssh"; }
    private static void run(List<String> command, Path directory, int seconds) throws Exception {
        Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(directory.resolve("setup.log").toFile())).start();
        try {
            if (!process.waitFor(seconds, TimeUnit.SECONDS)) {
                throw new IOException("timed out: " + command.getFirst());
            }
            if (process.exitValue() != 0) throw new IOException(command.getFirst() + " exit=" + process.exitValue());
        } finally {
            if (process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroy);
                process.destroyForcibly();
            }
        }
    }
    private static void log(String message) { System.out.println(Instant.now() + " " + message); }

    private static final class NativeCommand implements Command {
        private final String command;
        private InputStream input;
        private OutputStream output, error;
        private ExitCallback callback;
        private volatile Process process;
        NativeCommand(String command) { this.command = command; }
        public void setInputStream(InputStream stream) { input = stream; }
        public void setOutputStream(OutputStream stream) { output = stream; }
        public void setErrorStream(OutputStream stream) { error = stream; }
        public void setExitCallback(ExitCallback value) { callback = value; }
        public void start(ChannelSession channel, Environment environment) {
            Thread.ofPlatform().daemon().start(() -> {
                try {
                    List<String> launch = WINDOWS
                            ? (command == null ? List.of("powershell.exe", "-NoLogo", "-NoProfile", "-Command", "-")
                                : List.of("powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive", "-EncodedCommand",
                                        Base64.getEncoder().encodeToString(command.getBytes(StandardCharsets.UTF_16LE))))
                            : (command == null ? List.of("/bin/sh", "-i") : List.of("/bin/sh", "-c", command));
                    process = new ProcessBuilder(launch).start();
                    Thread.ofPlatform().daemon().start(() -> copy(input, process.getOutputStream()));
                    Thread stdout = Thread.ofPlatform().daemon().start(() -> copy(process.getInputStream(), output));
                    Thread stderr = Thread.ofPlatform().daemon().start(() -> copy(process.getErrorStream(), error));
                    int result = process.waitFor();
                    stdout.join(); stderr.join();
                    callback.onExit(result);
                } catch (Exception failure) { callback.onExit(1, failure.toString()); }
            });
        }
        public void destroy(ChannelSession channel) {
            Process active = process;
            if (active != null && active.isAlive()) {
                active.descendants().forEach(ProcessHandle::destroy);
                active.destroyForcibly();
            }
        }
        private static void copy(InputStream source, OutputStream target) {
            try (target) { source.transferTo(target); } catch (IOException ignored) {}
        }
    }
}
