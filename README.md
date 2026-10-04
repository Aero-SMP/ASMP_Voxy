# ASMP Voxy

Development branch of ASMP Voxy for Minecraft 1.21.1 / NeoForge 21.1.229. The client and server
mods use the current terrain implementation: a Java saved-terrain publisher,
separately supervised Rust record streamer, Quinn/Kwik transport, and a Java
Minecraft-model renderer. The testing client joins `play.aerosmp.com:25587`.
The agreed scope and acceptance criteria are in
[the project audit plan](project_audit/voxy_reimplementation_plan.md).

Build (compilation and packaging only):

```sh
./gradlew buildAll
```

The single Gradle project builds `build/libs/voxy-client-debug.jar` and
`build/libs/voxy-server-debug.jar`. Install the appropriate jar in `mods`.
The installed artifact SHA-256 and live outcomes are recorded under
`project_audit`; publishing an artifact does not establish that it is running.

Updates use fixed artifact names and content hashes, with no application version
numbers. Publish the artifact before atomically announcing its SHA-256:

```sh
python3 tools/publish_update.py --side client --jar build/libs/voxy-client-debug.jar
python3 tools/publish_update.py --side server --jar build/libs/voxy-server-debug.jar
```

Clients read `releases/client/latest.properties` through the existing authenticated
`aerosmp@ssh.aerosmp.com` connection. The manifest contains exactly `file` and
`sha256`. Clients verify the SHA-256, side, and mod
identity before staging an update. A separate Java helper waits for Minecraft to
exit, replaces exactly one jar, and requests the configured official Modrinth
launcher URI. The launcher owns authentication and Java arguments. Readiness
checks the new client PID and artifact hash; a failed launch restores the previous
jar and requests recovery through the launcher.
Client updates and automatic join are scoped to MGengine during testing.

The server reads the local server feed, installs the verified jar, and shuts down
cleanly for its external supervisor to restart it. Run it with Astolfo or another
supervisor configured to restart after a clean shutdown. Polling is every 20
seconds on a daemon thread, independent of Minecraft ticks. Failures retain the
previous installation and are recorded in `logs/voxy-updater.log`.

Two independent laptop SSH helpers are installed separately under
`%LOCALAPPDATA%\AeroSMP\BackupSSH` and `BackupSSHsecondary`; both survive
Minecraft stopping and either Voxy client jar being replaced.
Server-side access uses `../ASMP_Voxy/tools/laptop_ssh_backup/connect.sh`.

The existing testing Rust backend is capped externally at 1,000,000,000 bytes
through `../Voxy_Testing/bin/voxy-memory-cap.py`. The server extracts its bundled
Linux x86_64 backend, starts it with `-Dvoxy.rust.launcher` when configured, and
advertises its certificate pin through the Minecraft connection. Saved world
files remain authoritative; derived spatial records live under
`.voxy/terrain/server`. Java checks requested source nodes and publishes missing
coarse coverage before detail or background refresh. Rust serves already published
records independently of source checks. See [native format](rust-server/README.md).
Minecraft retains its 1–4 GiB heap. Main stays read-only.

The local client worker opens its last-known server/world/dimension cache before
endpoint discovery. Separate network reconciliation fills missing sections,
then checks already covered terrain. Both use identical compressed section bytes
and self-contained NBT palettes, packed cells, a complete neighboring halo, and
saved block-entity data. Valid downloaded terrain becomes usable before optional
disk persistence. A shared OpenGL context uploads terrain on the worker; replacement
fences preserve existing coverage. GPU cleanup responds to driver memory pressure,
and inactive terrain remains available for reuse. No application work or resource
quotas are used.

Validation is live only with the real player and
[100 virtual QUIC clients](tools/load/README.md). Integration tests and automated
test suites have been removed. The poor-link acceptance workload applies
50–90% packet loss in each direction, 500 kbps–3 Mbps, 300–1,000 ms RTT, and concurrent
300 actual saved block changes per second. Failed runs remain in the audit.

For MGengine, `.voxy/terrain/control.properties` supports `network=false` to
pause only Voxy reconciliation, and a changed `screenshot` property to capture
the real GPU view. `.voxy/terrain/status.json` records live cache, mesh, transport,
and GPU measurements. These controls do not execute shell commands.

Camera quality uses the actual render projection and viewport, frustum visibility
and conservative GPU bounding-box depth queries. Sodium exposes the Voxy
General and Rendering tabs with enable/disable, render distance, and
projected section pixel size (64 px default). Configuration
lives in `config/voxy.properties`. Cached finer
coverage can satisfy demand without downloading a parent; freshness sweeps yield
to view changes and run in the background. See
[visibility implementation and live receipts](project_audit/live_client/visibility_implementation.md).

The full comparative goal is in [project_audit/overnight_goal.md](project_audit/overnight_goal.md).
Implementation and compilation do not establish its live acceptance gates.

The client and server use one current protocol and one current terrain layout.
There are no legacy identifiers, compatibility decoders, version negotiation, or
runtime data migrations. Protocol or layout changes require coordinated
deployment. Historical audit receipts retain the names and paths they observed.
