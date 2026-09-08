# Automatic UDP offset reduced to 200 — 2026-09-08

Release `.208`: blank `quic.listen` now resolves to Minecraft server-port + 200 instead
of + 2000. The TOML remains empty; explicit addresses still override automatic mode.
Default Minecraft port 25565 gives UDP 25765. Checked overflow begins above 65335;
out-of-range automatic listeners require an explicit override and never wrap.
Polling remains 2000 ms; only the port offset changed.

Tests passed: Rust config test, normal/debug lifecycle tests (288 assertions each), real
bundled Rust replacement/QUIC integration, buildAll and verifyDebugHarnessArtifacts.
Native integration changed Minecraft's port and observed UDP 43884→54579 while the blank
TOML and persisted identity stayed unchanged. Logs: `/tmp/voxy-offset-208-rust.log` and
`/tmp/voxy-offset-208-build.log`. Build output redirected using
`/tmp/voxy-renderer-admission-build.gradle`; no client/debug update published.

Deployment: Mod_Testing, non-debug `.208`; Minecraft server-port remains 25586, hence
automatic Voxy port 25786. No players online. Graceful stop saved all dimensions at
15:53:49.763 UTC, old JVM2683879/Rust2684657 exited, then existing run.sh restarted.
The old jar/config are retained in `Mod_Testing/deploy-backups/offset-208/`. Only the
existing config's explanatory comment changed; `listen = ""` stayed blank. Worlds,
derived data, certificate and other mods unchanged.

Release jar SHA-256: `7b8d09ca183f33094bf2fffc1a7a66722b45cb777d8e20a332ce6d4caff3ab82`.
Live verified: loader discovered `.208`, Minecraft Done at 15:55:07.175 UTC and native
ready at 15:55:07.265. JVM125636 owns Rust126670, UDP25786 confirmed owned by that child;
the TOML still says `listen = ""`. The original certificate fingerprint is unchanged.
Unrelated `.gitignore` changes excluded. Remote push still subject to the prior destination
approval rejection; no bypass attempted. No client runtime verification claimed.
