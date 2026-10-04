# Retained GPU terrain cache and four Sodium controls

Scope: Enable Voxy, LOD pixel size, render distance, GPU memory limit. Fog, clouds and SSAO are excluded by the user's explicit clarification. No integration tests or virtual-client load runs.

The old rewrite destroyed every GPU section absent from the current frustum/quality demand. The replacement uses the existing section-key geometry table as an access-ordered cache. Returning to a resident hash and model revision skips cache decoding, CPU meshing and upload. Renderer lookup and recency updates are average O(1); eviction walks only as needed when capacity is exhausted. Local section state survives while its GPU mesh is resident, and disk cache contents are preserved.

The GPU limit accounts for uploaded vertex buffers and private sorted index buffers, including pending uploads and old/new replacement overlap. Minecraft-owned shared indices, framebuffer/texture allocations and driver overhead are outside the terrain geometry setting. Allocation admission happens before creating/uploading new terrain buffers. Inactive LRU geometry is removed first. When new coarse coverage needs room, finer active geometry can be removed only while a resident ancestor remains available. Refinements or freshness replacements that cannot fit stay deferred; they do not repeatedly remesh until view/capacity/residency changes. The existing GPU fence publication still guards replacements.

Automatic uses driver-reported free buffer VRAM at renderer creation when the standard NVIDIA/AMD queries exist. On drivers without this information, automatic retains terrain within the configured spatial render distance; selecting a numeric limit enforces the chosen geometry capacity. The original capacity choices256 MiB through28 GiB are available, with Automatic as the default. The explicit user-requested GPU cache capacity adds no CPU/memory/work queue budgets.

Lowering the limit trims immediately. If the chosen size is below already resident coarse coverage, that coverage must also shrink to honor the hard setting. Resource/model reload invalidates old geometry and hashes; dimension/server/session changes or explicit disable close the cache. Turning or changing pixel quality alone does not destroy otherwise useful meshes.

Client build258 /0.3.7-beta is intended to pair with unchanged server252. Build and live outcomes are recorded below after completion; publication alone does not establish a running build.


Compilation: `./gradlew --offline :client:jar` passed for final258. A focused source review found no critical accounting/coverage defects; the final release also prevents an off-view queued upload from evicting active detail. The whole production rewrite is3,762 lines/32 Java/Rust/shader files, up130 lines with no new source files for this change. `git diff --check` passed. Publication SHA256: `73cab9bc4837fc7048a10859a29c8cfae3f9cc510dd0b01ddbcc1b04124a13a3`.


## Minimal live result, 2026-10-03

The existing updater installed257 at21:09:12 UTC and the final258 at21:10:03 UTC, PID31828→21228. Actual installed258 hash matches the published SHA; its fresh in-world GPU/QUIC status is retained in `live_client/258_world_status.json`. Independent backup SSH PID21200 survived both restarts. Server252 was not restarted or changed.

`live_client/258_gpu_cache_live_check.json` records a stationary real-player eight-second cache-only check (64px→256px→64px), followed by restoration of the exact original control file. Coarse quality retained204 inactive GPU meshes; returning to64px recorded204 GPU cache hits and zero evictions. Downloads stayed177 and received bytes stayed599,434 throughout, while Voxy networking was disabled only for the two four-second sample windows. Other already cached sections continued meshing as the view filled, so aggregate mesh/upload counters increased; the test does not claim the entire view was already warm. The reactivated204 resident meshes use their existing buffers via the retained hash path.

Automatic selected6,561,361,920 bytes of driver-reported free buffer memory. All four telemetry samples remained below that geometry limit including pending uploads; the final sample used876,513,732 bytes and zero pending bytes. No local, publication, network or GPU depth failures were recorded. No pressure/limit-reduction or Sodium UI interaction test was performed in this minimal pass; capacity admission/eviction was inspected in source. No performance or no-holes acceptance is claimed. Original networking was confirmed reconnected, pixel target64, FOV70, and player pose unchanged after restoration.
