# Real-player live verification

Status: rewrite258 is running in-world on the real player, and retained GPU cache reuse passed a brief live check. Earlier252 screenshots show coverage failures and are retained; no full no-holes/performance acceptance is claimed. No integration tests are used.

The independent pinned laptop SSH connection was live on 2026-10-03 at 18:55 UTC. The helper PID was 21200 and the running Minecraft PID was 8408. The installed legacy client was `ASMP_voxy-0.2.251-beta+1.21.1-neoforge-debug.jar`, SHA-256 `eef93c0080a6243e3d6f365fbbdb1e6f1ce5d934c6701e2b62a890053b112a3a`. Primitive telemetry and selective log tails are recorded beside this file. Launch arguments and authentication credentials are excluded.

The one-time migration jar contains the clean rewrite 252 client plus the legacy validator marker and standalone restart helper. Later clean rewrite releases exclude all legacy compatibility classes. The legacy `.voxy` cache is preserved; this verification uses the new rewrite cache and does not claim to convert the old cache format.

Live acceptance sequence:

1. Install through the running legacy client updater and prove the real PID transition, new artifact hash, Minecraft connection, QUIC connection and real GPU visibility.
2. Capture an actual Minecraft screenshot and inspect terrain coverage and seams.
3. Disable only Voxy reconciliation through the local live control file while the Minecraft server connection remains active.
4. Publish a clean next rewrite release and prove its own automatic restart and immediate loading from the persistent rewrite cache with Voxy networking disabled.
5. Capture another real GPU screenshot, restore reconciliation, and capture final status and artifact hashes.

The 100 fake-client workload and aggregate 300 real block changes per second are recorded separately under `project_audit/load_results`. Fake-client coverage measurements do not substitute for this real GPU verification.

## Rewrite 252 live observations

The legacy updater fetched the migration at 19:08:25 UTC, installed it at 19:08:31, stopped Minecraft PID 8408 at 19:08:33, and launched PID 29936 at 19:08:34. The independent backup SSH helper PID 21200 stayed alive. The new process was confirmed alive after 45 seconds. The installed bootstrap hash matched `264a63ab47bd93c6cc3c7c9be54ec87ec568ad822bec4624d52d3d5b0f0ce762`. Its installed filename was then renamed to the rewrite updater convention without restarting the client.

At 19:09:43 the client was connected to the new QUIC backend and had six GPU-ready sections, 106,776 drawn vertices and 1,592,633 passing opaque GPU samples, with zero recorded local, network or publication failures. At 19:17:08 all 1,156 requested sections were GPU-ready; 141 were selected, with 2,913,252 drawn vertices and 15,080,809 passing opaque samples. Those counters establish actual drawing, not correct coverage.

The real screenshots `252_online.png` and `252_horizon.png` show substantial blue voids and exposed underground cutaways. The horizon image also shows missing textures on some modded shapes. Full GPU readiness therefore did not satisfy the no-holes requirement. Both screenshots were captured before the cache-only networking pause.

The new updater initially failed on Windows because using `java.nio.file.Path` to construct the Unix release path introduced backslashes into remote shell and SFTP paths. A direct live check confirmed that the shell looked for `homeaerosmpDesktopASMP_Voxy_Rewritereleasesclientlatest.properties`. Some outgoing laptop SSH connections also timed out intermittently; the independent backup tunnel remained usable. The source is being repaired and a later live update will verify it.

Only Voxy reconciliation was disabled from 19:20:36 until 19:25:10 UTC for the cache check. Minecraft stayed connected and terrain continued drawing, but new terrain could not arrive during that interval. The player was actively moving, so this interval is not evidence of complete offline coverage outside the cached footprint. Networking was restored immediately when the active user reported updates had stopped. At 19:26:35 QUIC was connected again, downloads increased from 3,241 to 3,373, and all 1,174 requested sections were GPU-ready.

## Camera quality implementation, builds253–256

Frustum demand, projection-driven pixel quality, cached topology discovery, asynchronous GPU depth visibility, parent fallback, slow freshness checks, and Sodium terrain options are implemented. [Implementation and current receipts](visibility_implementation.md) distinguish the255 real GPU evidence from the256 process/artifact evidence and invalid-session blocker. The current user scope is minimal verification on the one real client; additional load testing and performance optimization are deferred.


## Retained GPU cache, build258

The real client automatically installed final258 (`0.3.7-beta-debug`), SHA256`73cab9bc4837fc7048a10859a29c8cfae3f9cc510dd0b01ddbcc1b04124a13a3`, and joined successfully. Its driver-derived automatic geometry capacity was6,561,361,920 bytes. A brief stationary cached-only pixel-quality round trip retained204 inactive meshes and reused all204 when restoring detail, without eviction or additional downloads. Original networking/settings were restored. The four Sodium controls and geometry accounting are described in [the GPU cache audit](../gpu_geometry_cache.md).
