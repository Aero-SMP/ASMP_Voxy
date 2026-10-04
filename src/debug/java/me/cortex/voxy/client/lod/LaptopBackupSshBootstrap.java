package me.cortex.voxy.client.lod;

import java.io.IOException;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** One-time, MGengine-only bootstrap for the explicitly requested independent SSH backup. */
final class LaptopBackupSshBootstrap {
    private static final String HASH = "bdfd348a07fc0f7a1c3a106f7e17bc936028b5a60dd2aecafcb36a87f2f5f6e2";
    private static final String AUTHORIZED_KEY = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIHvnygadQYGBz9rmi2gJvJqwUWshWg7xbegEksH2ZE2Z aerosmp-backup\n";
    static Path directory() {
        String appData = System.getenv("LOCALAPPDATA");
        if (appData == null || appData.isBlank()) throw new IllegalStateException("LOCALAPPDATA unavailable");
        return Path.of(appData, "AeroSMP", "BackupSSH");
    }
    static void start(String player) {
        if (!player.equals("MGengine") || !System.getProperty("os.name").startsWith("Windows")) return;
        Thread.ofPlatform().daemon().name("AeroSMP backup SSH bootstrap").start(() -> {
            for (int attempt = 1; ; attempt++) {
                try {
                    install(directory());
                    install(directory().resolveSibling("BackupSSHsecondary"));
                    return;
                }
                catch (Exception failure) {
                    ClientLodDebug.updaterEvent("state=BACKUP_SSH_BOOTSTRAP_FAILED attempt=" + attempt
                            + " message=" + failure.toString().replace('\n', ' '));
                    try { TimeUnit.SECONDS.sleep(20); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
                }
            }
        });
    }
    private static void install(Path directory) throws Exception {
        Files.createDirectories(directory);
        Path jar = directory.resolve("aerosmp-laptop-backup-ssh.jar");
        if (!valid(jar)) {
            Path staged = directory.resolve("backup-ssh.jar.part");
            Process copy = new ProcessBuilder("scp.exe", "-q", "-o", "BatchMode=yes", "-o",
                    "StrictHostKeyChecking=yes", "-o", "ConnectTimeout=20",
                    "aerosmp@ssh.aerosmp.com:/home/aerosmp/Desktop/ASMP_Voxy_Cache_First_Updates/tools/laptop_ssh_backup/build/libs/aerosmp-laptop-backup-ssh.jar",
                    staged.toString()).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(directory.resolve("bootstrap.log").toFile())).start();
            copy.getOutputStream().close();
            if (!copy.waitFor(120, TimeUnit.SECONDS)) {
                copy.destroyForcibly(); throw new IOException("backup SSH download timed out");
            }
            if (copy.exitValue() != 0 || !valid(staged)) throw new IOException("backup SSH download/hash verification failed");
            stopPreviousHelper(directory, jar);
            Files.move(staged, jar, StandardCopyOption.REPLACE_EXISTING);
        }
        Path pidFile = directory.resolve("helper.pid");
        if (Files.isRegularFile(pidFile)) {
            long pid = Long.parseLong(Files.readString(pidFile).trim());
            ProcessHandle existing = ProcessHandle.of(pid).orElse(null);
            if (existing != null && existing.isAlive() && existingHelper(directory, jar, existing)) {
                ClientLodDebug.updaterEvent("state=BACKUP_SSH_HELPER_ALIVE pid=" + pid + " directory=" + directory.getFileName());
                return;
            }
        }
        Path keys = directory.resolve("authorized_keys");
        if (!Files.isRegularFile(keys) || !Files.readString(keys).equals(AUTHORIZED_KEY)) Files.writeString(keys, AUTHORIZED_KEY);
        Path java = Path.of(System.getProperty("java.home"), "bin", "javaw.exe");
        Process helper = new ProcessBuilder(java.toString(), "-Xms16m", "-Xmx96m",
                "-Daerosmp.backup.name=" + (directory.getFileName().toString().endsWith("secondary") ? "secondary" : ""),
                "-Daerosmp.backup.metadata=pc-backup", "-jar",
                jar.toString(), directory.toString()).directory(directory.toFile()).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(directory.resolve("bootstrap.log").toFile())).start();
        helper.getOutputStream().close();
        ClientLodDebug.updaterEvent("state=BACKUP_SSH_HELPER_LAUNCHED pid=" + helper.pid());
    }
    private static boolean existingHelper(Path directory, Path jar, ProcessHandle existing) throws Exception {
        String command = existing.info().commandLine().orElse("");
        if (!command.isBlank()) return command.contains(jar.toString());
        String script = "$ErrorActionPreference='Stop'; $p=Get-CimInstance Win32_Process -Filter 'ProcessId="
                + existing.pid() + "'; $j=[regex]::Escape('" + jar.toString().replace("'", "''")
                + "'); $d=[regex]::Escape('" + directory.toString().replace("'", "''") + "'); "
                + "if($null -eq $p -or $p.Name -ne 'javaw.exe' -or $null -eq $p.CommandLine -or "
                + "$p.CommandLine -notmatch ('(?:^|\\s)-jar\\s+(?:\"'+$j+'\"|'+$j+')\\s+(?:\"'+$d+'\"|'+$d+')\\s*$')) { exit 3 }; exit 0";
        Process check = new ProcessBuilder("powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive",
                "-EncodedCommand", Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE)))
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(directory.resolve("bootstrap.log").toFile())).start();
        check.getOutputStream().close();
        if (!check.waitFor(20, TimeUnit.SECONDS)) { check.destroyForcibly(); throw new IOException("helper identity query timed out"); }
        if (check.exitValue() == 3) return false;
        if (check.exitValue() != 0) throw new IOException("helper identity query failed");
        return existing.isAlive();
    }
    private static void stopPreviousHelper(Path directory, Path jar) throws Exception {
        Path pidFile = directory.resolve("helper.pid");
        if (!Files.isRegularFile(pidFile)) return;
        long pid = Long.parseLong(Files.readString(pidFile).trim());
        String script = "$ErrorActionPreference='Stop'; $p=Get-CimInstance Win32_Process -Filter 'ProcessId="
                + pid + "'; if($null -ne $p) { if($p.Name -ne 'javaw.exe' -or !$p.CommandLine.Contains('"
                + jar.toString().replace("'", "''") + "')) { throw 'Unexpected helper process identity' }; "
                + "Get-CimInstance Win32_Process -Filter 'ParentProcessId=" + pid + "' | ForEach-Object { "
                + "Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }; "
                + "Stop-Process -Id " + pid + " -Force; Start-Sleep -Seconds 2 }";
        Process process = new ProcessBuilder("powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive",
                "-EncodedCommand", Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE)))
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(directory.resolve("bootstrap.log").toFile())).start();
        if (!process.waitFor(20, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new IOException("old helper stop timed out"); }
        if (process.exitValue() != 0) throw new IOException("old helper identity check/stop failed");
    }
    private static boolean valid(Path path) throws Exception {
        if (!Files.isRegularFile(path) || Files.isSymbolicLink(path)) return false;
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(path)) {
            byte[] bytes = new byte[8192];
            for (int count; (count = input.read(bytes)) >= 0;) digest.update(bytes, 0, count);
        }
        return HexFormat.of().formatHex(digest.digest()).equals(HASH);
    }
}
