# Independent laptop access and recovery

The testing profile uses `voxy-client-debug.jar`, SHA-256
`9b13c7510eef97903cb6883c472cb4536c1011ed5ccb5815001a6debf30cdb65`.
The original fixed artifact was restored through Modrinth's own profile launch URI,
which supplies authentication without reading or replaying JVM credentials.

| Connection | Helper PID | Native SSH PID | Local listener | Separate installation |
|---|---:|---:|---:|---|
| Primary | 19292 | 21308 | 58972 | `%LOCALAPPDATA%\AeroSMP\BackupSSH` |
| Secondary | 30096 | 12544 | 65025 | `%LOCALAPPDATA%\AeroSMP\BackupSSHsecondary` |

Both helpers use artifact SHA-256
`1d2573a203635b20ae2e8c9876ad89e5eb5df8601957c0c8b84a809276ad7354`.
They have separate persistent host keys, exclusive locks, Windows login entries,
listeners, standalone processes, and native OpenSSH tunnels. Neither helper depends
on Minecraft. Password authentication and incoming SSH forwarding are disabled.

Primary host pin: `SHA256:9mdJEOVUlJj5m4C1G967sFGG8YGT8zO2RxhUBepljZs`.
Secondary host pin: `SHA256:NsnTQpL/XgLbkQg+nleNJyTz5EnWeFwMkk2FBc9VUBI`.
All verification commands used the corresponding strict host-key pin.

At 22:26:01 UTC, Minecraft PID 29248 closed normally. Both backup channels executed
commands while Minecraft was stopped. Modrinth restored Minecraft as PID 32328;
fresh connected terrain telemetry was verified at 22:28:08 UTC.

At 22:31:52 UTC, the authorized controlled crash forcibly stopped only Minecraft
PID 32328. At 22:32:08–09 UTC, both channels again executed commands while no
Minecraft window existed. Helper PIDs, tunnel PIDs, and helper start times were
unchanged. Modrinth restored Minecraft as PID 3696. At 22:33:58 UTC, terrain status
was 648 milliseconds old, connected, and reported zero local, network, or depth
failures. Artifact identity remained the verified SHA above.

The two named login entries were checked after fixing installation to preserve the
existing Windows Run key. Each helper was replaced through the other working
channel before these lifecycle checks. Receipts named `independent_backup_*.json`
retain the process, artifact, and lifecycle evidence.

The safe URI is stored in the testing profile's `.voxy/updater/launcher`:
`modrinth://launch/instance/local%3A1f8cde55-dd3a-4d66-9280-d18e11c1d48b`.
Matching stock Java reports Desktop and BROWSE support. Modrinth 0.21.6 accepts
this form in its [official URI handler](https://github.com/modrinth/code/blob/v0.21.6/packages/app-lib/src/api/handler.rs#L50-L81).
Only instance ID/path metadata was read from its database; authentication and
launch-argument columns were not inspected.
