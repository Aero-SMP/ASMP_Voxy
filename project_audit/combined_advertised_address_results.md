# Combined advertised address (.210)

## Implementation

Replaced `quic.advertise_host` / `quic.advertise_port` with `quic.advertise = ""`.
`listen` remains independent and defaults to Minecraft's current port + 200 at each
backend launch. Nothing persists a resolved automatic host or port.

Java parses and canonicalizes the controller-only public address without DNS lookups:
empty, host-only, port-only (`:30000`), host:port, bracketed IPv6 with/without port.
An explicit port must be 1–65535; malformed addresses, whitespace, scopes and unbracketed
IPv6 are rejected. Rust accepts the shared string but only uses `listen`; there is no
second public-address parser. Removed keys are rejected, not supported as aliases.
The endpoint payload and QUIC protocol are unchanged; no client update is required.

## Offline validation

- Rust library: 37 tests passed with host UDP access. Initial restricted-sandbox run
  had 36 passes and one socket `Operation not permitted` failure; retained at
  `/tmp/voxy-advertise-210-rust.log`. Successful run:
  `/tmp/voxy-advertise-210-rust-host.log`.
- Normal and debug Java lifecycle suites: 328 assertions each, including valid/invalid
  advertised addresses and generated config defaults.
- Bundled Rust / Java Kwik integration passed: PIDs 3227060 → 3227159, UDP 35224 → 36822
  following a changed Minecraft port with byte-identical empty-listen/advertise TOML.
  Pinned TLS, catalog transfer, standard/custom dimensions and persistent identity
  passed before/after replacement; no leaked child/executable after stop.
- `buildAll`, `verifyDebugHarnessArtifacts` and `git diff --check` passed.
- Gradle log: `/tmp/voxy-advertise-210-build.log`.
- All jars isolated in `build/release-validation-210`; no watched client updater output
  was published. Build init: `project_audit/advertise_210_build.gradle`.

## Live deployment (2026-09-09 UTC)

Target `/home/printer/Desktop/Mod_Testing`, verified debug .209 before stopping.
No players online. Exact old JVM 3155725 and Rust 3156512 exited after console `stop`;
all dimensions saved at 21:56:31.536. Old jar and TOML retained in
`deploy-backups/advertise-210/`. Only the two advertised fields/comments were migrated;
worlds, derived data and identities were untouched.

Deployed debug server .210; startup log identifies its jar and Minecraft reached Done
at 21:57:27.971. Rust PID 3231810 emitted VOXY_READY at 21:57:28.047; host socket inspection
confirmed that process bound `0.0.0.0:25786` for Minecraft port 25586. Certificate SHA-256
was unchanged: `1c1fd2962903b429a631ef8ca3b8d59614efcd129692dfd4be730237a029bfd7`.

Deployed jar SHA-256:
`cccede6674e340903396a27a527e0c46d2ab94cc5c816c911a827959431d5293`.

Intended pairing is debug server .210 with existing protocol-compatible clients,
unchanged by this server-only configuration change. No player client was connected
or verified; live client advertisement, remote NAT/IPv6 reachability and rendering
are not claimed as tested. Live verification covers server startup, config loading,
readiness, listener and identity; actual QUIC round trips were tested in isolation.
