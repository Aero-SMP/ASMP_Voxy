# Automatic default Rust configuration — 2026-09-06

Implemented and deployed non-debug server `.205` to `/home/printer/Desktop/Mod_Testing`.

`VoxyServer` now asks `RustBackend.ensureConfig` to create `voxy-rust.toml` before parsing
the advertised address. The default resource is bundled in both normal and debug jars.
Creation does not replace existing files; existing invalid/empty/custom files and symlinks
remain untouched. A concurrent creator wins without being overwritten. Unreadable or
invalid existing configuration still produces an error rather than silently resetting it.

Defaults: `world`, `voxy-rust/data`, `minecraft:overworld`, poll 2000 ms, Rayon workers 0
(automatic), listen `0.0.0.0:25587`, advertised host empty, advertised port 0.
Paths are relative to the server working directory. Custom Minecraft `level-name` values
still require editing `world`, and simultaneous Voxy instances require distinct UDP ports.

## Verification

Executed with the isolated-output init script to avoid publishing a debug client update:

```text
./gradlew -I /tmp/voxy-renderer-admission-build.gradle \
  :server:serverLifecycleTest :server:debugServerLifecycleTest \
  :server:serverRustIntegrationTest buildAll verifyDebugHarnessArtifacts --console=plain
git diff --check
```

Passed. Each normal/debug lifecycle runner completed 274 assertions. Tests cover missing
config creation, parsed default values, repeat invocation and preserving custom, malformed
and empty existing files. The native integration uses the generated defaults with isolated
paths/loopback port, verifies Rust startup, pinned QUIC catalog transfer, process replacement,
unchanged certificate and clean shutdown. Build log: `/tmp/voxy-default-config-205-build.log`.

Mod_Testing was already stopped after its missing-TOML crash. Its `.204` release jar was
moved to `deploy-backups/default-config-205/`, not deleted. No configuration was pre-created
manually. Started through existing `bash ./run.sh` in `printer_session:1.0`.

Observed new config creation at 14:08 UTC; Minecraft Done at 14:08:31.643 and native
VOXY_READY at 14:08:31.731. JVM 2467032 owns Rust PID 2467744, with UDP 25587 confirmed.
Source worlds, existing data and Creative were not changed. Mod_Testing initializes its own
local derived data/identity as needed. No client update was published by this task and no
client rendering claim is made from server readiness alone.

Installed server SHA-256:
`8198dee7ad5a24d51f57d6247e94e507a6e2d07b24d14b5554554830ac9829de`.

Remote source push remains subject to the prior tool-reviewer destination-approval block;
no bypass was attempted. Unrelated `.gitignore` changes are excluded from the commit.
