# Generated Voxy UDP port follows server.properties

2026-09-06, non-debug server `.206`.

New `voxy-rust.toml` files now read the sibling `server.properties` and substitute its
`server-port` into `quic.listen`. Missing file/property uses Minecraft's 25565 default.
Malformed/out-of-range ports fail explicitly before creating a TOML. Existing TOMLs
remain untouched, including when server.properties later changes; the user requested a
default, not a forced override of saved Voxy configuration.

Normal/debug lifecycle runners passed 284 assertions each, including port 25582, boundaries
1/65535, missing file/property fallback, invalid values, existing-file preservation and
the previous supervisor matrix. Bundled Rust integration passed using generated defaults
with isolated paths/loopback port. `buildAll`, `verifyDebugHarnessArtifacts` and
`git diff --check` passed. Log: `/tmp/voxy-server-port-206-build.log`.

Build command used `/tmp/voxy-renderer-admission-build.gradle` to keep client/debug artifacts
out of the auto-updater publish directory. No client update or other mod changes requested.

## Mod_Testing deployment constraint

The live Mod_Testing Java process already owned UDP `*:25582` before deployment. Its
startup log identifies Sable's UDP channel, while voice chat separately uses 24461.
Minecraft `server-port=25582`; therefore switching Voxy to that port would conflict with
Sable. The existing Voxy TOML is intentionally preserved on UDP 25587, not silently migrated.
No Sable bind setting, JVM argument or other service was changed. Moving this instance's
Voxy listener to 25582 requires resolving that separate port ownership first.

The old .205 jar is retained in `Mod_Testing/deploy-backups/server-port-206/`. Deployment
uses graceful shutdown and the existing `printer_session:1.0` / `bash ./run.sh` workflow.
Worlds, derived data and certificate are preserved. No players were online before restart.

Local commits exclude unrelated `.gitignore` changes. Remote push remains blocked by the
earlier tool-review destination approval restriction; no bypass was attempted.
