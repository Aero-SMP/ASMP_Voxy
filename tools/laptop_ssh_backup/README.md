# Independent laptop backup SSH

User-requested backup remote access for MGengine's Windows laptop. Debug client 251
bootstraps it once; the standalone helper has no Minecraft dependencies. It runs as
the logged-in Windows user, binds to localhost only, and uses the server's existing
Ed25519 public key for authentication. Password authentication and additional SSH
forwarding are disabled. Apache MINA SSHD 2.20.0 supplies SSH and SFTP.

The helper and its logs are installed in `%LOCALAPPDATA%\AeroSMP\BackupSSH`.
`HKCU\Software\Microsoft\Windows\CurrentVersion\Run\AeroSMPBackupSSH`
starts the helper on Windows login. Its exclusive file lock prevents duplicate
helpers. Native Windows OpenSSH maintains the reverse tunnel to
`aerosmp@ssh.aerosmp.com`, reconnecting after failure. The server listens only on
an automatically allocated loopback port, recorded in `tunnel-port`. A fresh port
on every reconnect avoids stale listeners after network failure. The helper host
key is persistent. Each reverse-tunnel attempt publishes its public host pin and
helper PID immediately over authenticated outbound SSH. It does not wait for an
allocated-port log message; `tunnel-port` uploads separately if OpenSSH reports it.
The connector also discovers a replacement loopback listener if Windows OpenSSH
omits its allocated-port notice. It verifies the already pinned laptop host key
before authenticating; unrelated local SSH endpoints cannot match that key.

From the AeroSMP server:

```sh
bash tools/laptop_ssh_backup/connect.sh 'whoami; hostname'
```

Commands run through PowerShell; interactive SSH and SFTP are also available.
Minecraft can be stopped or replaced without terminating this helper. Laptop
shutdown, logout, sleep, or network loss still interrupt access; login/network
recovery reconnects it.

To uninstall, remove the named HKCU Run value, stop the helper PID recorded in
`helper.pid`, then delete the installation directory. This does not modify the
machine-wide Windows OpenSSH service, firewall, administrator accounts, or Main.

Build with `./gradlew -p tools/laptop_ssh_backup jar`. If the helper is rebuilt,
update the bootstrap's SHA-256 pin before publishing the client.

For the PC's two existing independent installations, keep the default and
`secondary` names and current host aliases. Select a separate metadata directory:

```powershell
& "$JavaBin\javaw.exe" -Xms16m -Xmx96m -Daerosmp.backup.metadata=pc-backup -jar "$Primary\aerosmp-laptop-backup-ssh.jar" "$Primary"
& "$JavaBin\javaw.exe" -Xms16m -Xmx96m -Daerosmp.backup.name=secondary -Daerosmp.backup.metadata=pc-backup -jar "$Secondary\aerosmp-laptop-backup-ssh.jar" "$Secondary"
```

Each installation owns its lock, persistent key, reconnect loop and existing HKCU
Run entry. Autostart preserves the connection name and metadata selection. PC
public metadata lands in `Voxy_Testing/logs/pc-backup` and
`Voxy_Testing/logs/pc-backup/secondary`, keeping laptop metadata separate.
