# Voxy rewrite bootstrap

Fresh repository for Minecraft 1.21.1 / NeoForge 21.1.229. The client and server
mods currently do **only automatic updates**. The client also joins the designated
testing address, `play.aerosmp.com:25587`. No previous terrain, rendering, caching,
protocol, shader, Rust, or instrumentation implementation is included.

Build and verify:

```sh
./gradlew buildAll
```

Artifacts are `client/build/libs/voxy-rewrite-client-*-debug.jar` and
`server/build/libs/voxy-rewrite-server-*-debug.jar`. Install the appropriate jar
in place of the previous `voxy` / `voxy_server` mod. Do not install both
implementations of the same mod ID. This repository is initialized locally;
the running testing pair remains legacy client 251 / capped server 248 until
the rewrite bootstrap is installed.

Each release has an increasing `mod_build` number and a new `mod_version`.
Publish its immutable artifact before atomically announcing the hash:

```sh
python3 tools/publish_update.py --side client --jar client/build/libs/voxy-rewrite-client-0.3.0-beta-debug.jar
python3 tools/publish_update.py --side server --jar server/build/libs/voxy-rewrite-server-0.3.0-beta-debug.jar
```

Clients read `releases/client/latest.properties` through the existing authenticated
`aerosmp@ssh.aerosmp.com` connection. They verify the SHA-256, build, side, and mod
identity before staging an update. A separate Java helper waits for Minecraft to
exit, replaces exactly one jar, and restarts the captured Java command. Windows
Modrinth and direct Java launches are supported; Prism restart is rejected safely.
Launch arguments remain in private local files and are never written to logs.
Client updates and automatic join are scoped to MGengine during testing.

The server reads the local server feed, installs the verified jar, and shuts down
cleanly for its external supervisor to restart it. Run it with Astolfo or another
supervisor configured to restart after a clean shutdown. Polling is every 20
seconds on a daemon thread, independent of Minecraft ticks. Failures retain the
previous installation and are recorded in `logs/voxy-rewrite-updater.log`.

The independent laptop SSH connection is already installed separately under
`%LOCALAPPDATA%\AeroSMP\BackupSSH`; it survives replacing either Voxy client jar.
Server-side access uses `../ASMP_Voxy/tools/laptop_ssh_backup/connect.sh`.

The existing testing Rust backend is capped externally at 1,000,000,000 bytes
through `../Voxy_Testing/bin/voxy-memory-cap.py`. The rewrite currently starts no
backend. Future terrain work must remain in a separate process under that hard
cap. Minecraft retains its 1–4 GiB heap. Main stays read-only.
