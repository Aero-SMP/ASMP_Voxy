# Fixed world/data layout and reduced config — 2026-09-09

Implemented `.209`: removed world, data, dimension and poll_ms from both the generated
TOML and Rust's accepted configuration schema. Unknown/obsolete settings are rejected, not
silently retained as compatibility settings. Runtime Config no longer carries these fields.

The backend always reads `./world`, discovers standard Overworld/Nether/End and namespaced
custom dimension directories, stores generated data/catalog/certificate in `./voxy-data`,
and uses a fixed two-second idle polling interval. Pending rebuild work still runs without
that idle delay. Removed the obsolete single-dimension override/discovery branch.
Java explicitly starts the child in the config's server directory, making the fixed paths
work identically in production and the isolated native integration test.

The existing automatic listener remains blank and resolves Minecraft port + 200 at launch.
README documentation now distinguishes local listen address, client-advertised host/port,
loopback versus all interfaces, and an example of external UDP port forwarding. Advertisement
does not create forwarding/firewall rules or bind another socket.

## Gates

All 36 Rust library tests passed. Normal/debug lifecycle suites passed 290 assertions each.
The native integration ran an unmodified generated TOML, verified creation in voxy-data,
handshakes/catalog transfers for Overworld, Nether, End and nested test:nested/custom,
automatic port change on replacement, unchanged TOML/certificate/key and clean shutdown.
buildAll, verifyDebugHarnessArtifacts and git diff --check passed.

Commands:
```text
cargo test --manifest-path rust-server/Cargo.toml --lib
./gradlew -I project_audit/fixed_layout_209_build.gradle \
  :server:serverLifecycleTest :server:debugServerLifecycleTest \
  :server:serverRustIntegrationTest buildAll verifyDebugHarnessArtifacts --console=plain
```

Logs: `/tmp/voxy-fixed-layout-209-rust.log`, `/tmp/voxy-fixed-layout-209-build-2.log`.
The initial Gradle run failed because an old temporary init script no longer existed;
`/tmp/voxy-fixed-layout-209-build.log` preserves that failure. Replaced it with the recorded
isolated-output init script. No client/debug artifact was published to the auto-updater.

## Deployment and data preservation

The actual current Mod_Testing instance was debug `.208` (not the earlier release instance),
so debug mode was preserved for `.209`. Exact console was pane %8, printer_session:2.0.
Its command-owned pane was temporarily retained through stop, respawned with the existing
`/bin/bash ./run.sh nogui`, and its previous inherited exit setting restored. Zero players
were online; all dimensions saved at 21:29:13.277 UTC after clean Rust exit zero.

Moved the validated 5.9 MiB `Mod_Testing/voxy-rust/data` directory into the previously absent
`Mod_Testing/voxy-data` on the same filesystem. No merge, deletion, world reset or identity
regeneration. Every file checksum matched before/after the move, prior to backend startup:
`/tmp/voxy-fixed-layout-209-data-before.sha256` and `...-after.sha256`.
Only the four obsolete keys/comments were removed from the live TOML; network/worker values
were preserved. Old TOML/debug jar retained in `deploy-backups/fixed-layout-209/`.

Verified actual debug `.209` startup, Minecraft Done at 21:30:37.460 and native ready at
21:30:37.545, JVM3155725/Rust3156512, UDP25786 listener owned by that child. Certificate
fingerprint remained `1c1fd2962903b429a631ef8ca3b8d59614efcd129692dfd4be730237a029bfd7`;
private-key file hash also unchanged. No live client acceptance claimed with zero players.

Installed debug server SHA-256:
`baa4cab374c0dabd3ce28e66eff5694c84dcd8622bb23cfa3ac9dc3241f6dcaa`.
Both normal/debug builds are in `build/release-validation-209/`.
Unrelated `.gitignore` edits are excluded from this task's commit.
