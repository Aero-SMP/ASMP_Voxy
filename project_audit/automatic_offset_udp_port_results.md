# Automatic UDP port: Minecraft + 2000

2026-09-06, release server `.207`.

Generated TOML now contains `[quic] listen = ""`. It stays empty. Before each native
backend launch the Java supervisor reads the sibling `server.properties` with Java's
Properties parser and passes the current server-port via `--minecraft-port`. Rust resolves
an empty (or omitted) listener to `0.0.0.0:(minecraft_port + 2000)` in memory only.
Thus a server restart or backend replacement picks up a changed server.properties without
rewriting the TOML. The running socket is not live-rebound merely by editing the file.

Explicit IPv4/IPv6 socket addresses remain overrides. Missing Minecraft properties/file
defaults to 25565, hence Voxy 27565. Addition is checked: auto mode above Minecraft port
63535 fails with instructions to set an explicit listener; it never wraps/truncates.
Direct standalone Rust invocation accepts `--minecraft-port` (default 25565); the Java
controller supplies it automatically. No protocol/storage changes or property-parser
dependency were added. Endpoint advertisement continues using the actual ready port.

## Verification

- `cargo test --manifest-path rust-server/Cargo.toml config::tests -- --nocapture`: PASS.
  Tests parse the shipped template, check offset/fallback/boundaries/overflow and explicit
  IPv4/IPv6 overrides. Log `/tmp/voxy-auto-port-rust-test.log`.
- Normal and debug server lifecycle suites: 288 assertions each, PASS. Current Minecraft
  port is reread while the generated TOML remains identical. Existing configs are preserved.
- Real bundled native interop: PASS. Changed only isolated server.properties; Rust moved
  UDP 38388→53367 after replacement. The empty-listen TOML, certificate and private key
  remained unchanged. Pinned QUIC handshake and catalog transfer succeeded before/after;
  both child exits were clean. Log `/tmp/voxy-auto-port-207-final-tests.log`.
- `buildAll verifyDebugHarnessArtifacts` and `git diff --check`: PASS. Final build log
  `/tmp/voxy-auto-port-207-final-build.log`. Jars were redirected outside watched build/libs
  using `/tmp/voxy-renderer-admission-build.gradle`; no client/debug update was published.

## Deployment

Target: `/home/printer/Desktop/Mod_Testing`, `printer_session:1.0`, non-debug server `.207`.
Previous .206 was retrying a bind collision on UDP 25586, owned by Sable in Java PID2553611.
Minecraft server-port is still 25586. Only Voxy's explicit listener was changed to empty,
along with its explanatory comments; no Sable/JVM/Minecraft-port changes were made.
Old config and jar retained in `deploy-backups/automatic-port-207/`.
Worlds, existing derived data and QUIC identity preserved. Graceful stop saved all dimensions
at 15:11:36.860 UTC before replacement and restart using existing `bash ./run.sh`.

Installed release jar SHA-256:
`7aed3cb05e6310ef260f88e93a1c8addffa6fa076afbcaa086edd4b73bbcd4ab`.

Live acceptance: Minecraft Done at 15:13:23.625 UTC, VOXY_READY udp_port=27586 at
15:13:23.718. Actual JVM 2683879 owns Rust 2684657, whose launch command contains
`--minecraft-port 25586`. UDP 27586 belongs to that child. TOML still contains `listen = ""`.
Certificate SHA-256 remains `1c1fd2962903b429a631ef8ca3b8d59614efcd129692dfd4be730237a029bfd7`.

Local commits exclude unrelated `.gitignore` changes. Earlier remote-push destination
approval rejection remains in effect; no bypass was attempted. Server acceptance does not
imply an unobserved client update or rendering test.
