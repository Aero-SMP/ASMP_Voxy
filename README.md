# Voxy rewrite

Fresh repository for Minecraft 1.21.1 / NeoForge 21.1.229. The client and server
mods use a fresh terrain implementation: a separately supervised Rust Anvil/LOD
backend, Quinn/Kwik transport, persistent compressed client cache, and a Java
Minecraft-model renderer. The testing client joins `play.aerosmp.com:25587`.
The agreed scope and acceptance criteria are in
[the project audit plan](project_audit/voxy_reimplementation_plan.md).

Build (compilation and packaging only):

```sh
./gradlew buildAll
```

Artifacts are `client/build/libs/voxy-rewrite-client-*-debug.jar` and
`server/build/libs/voxy-rewrite-server-*-debug.jar`. Install the appropriate jar
in place of the previous `voxy` / `voxy_server` mod. Do not install both
implementations of the same mod ID. Runtime versions and live outcomes are
recorded under `project_audit`; a staged jar does not establish a running version.

Each release has an increasing `mod_build` number and a new `mod_version`.
Publish its immutable artifact before atomically announcing the hash:

```sh
python3 tools/publish_update.py --side client --jar client/build/libs/voxy-rewrite-client-0.3.1-beta-debug.jar
python3 tools/publish_update.py --side server --jar server/build/libs/voxy-rewrite-server-0.3.1-beta-debug.jar
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
through `../Voxy_Testing/bin/voxy-memory-cap.py`. The server extracts its bundled
Linux x86_64 backend, starts it with `-Dvoxy.rust.launcher` when configured, and
advertises its certificate pin through the Minecraft connection. Saved world
files remain authoritative; derived spatial generations live under
`.voxy-rewrite/server`. Native refreshes coalesce source changes and reuse
unchanged source groups. See [native format](rust-server/README.md).
Minecraft retains its 1–4 GiB heap. Main stays read-only.

The local client worker opens its last-known server/world/dimension cache before
endpoint discovery. Separate network reconciliation fills missing sections,
then checks already covered terrain. Both use identical compressed section bytes
and locally persisted catalogs. Old GPU terrain remains until replacement fences
complete. There are no internal memory reservations, budgets, or upload quotas.

Validation is live only with the real player and
[100 virtual QUIC clients](tools/load/README.md). Integration tests and automated
test suites have been removed. The poor-link acceptance workload applies
50–90% packet loss, 3 Mbps per direction, at least 1,000 ms RTT, and concurrent
300 actual saved block changes per second. Failed runs remain in the audit.

For MGengine, `.voxy-rewrite/control.properties` supports `network=false` to
pause only Voxy reconciliation, and a changed `screenshot` property to capture
the real GPU view. `.voxy-rewrite/status.json` records live cache, mesh, transport,
and GPU measurements. These controls do not execute shell commands.

Camera quality uses the actual render projection and viewport, frustum visibility
and conservative GPU hierarchical depth tests. The Sodium Voxy page exposes
render distance and projected section pixel size (64 px default). Cached finer
coverage can satisfy demand without downloading a parent; freshness sweeps yield
to view changes and run in the background. See
[visibility implementation and live receipts](project_audit/live_client/visibility_implementation.md).
