# Terrain search and capacity checks: implementation results

Completed source implementation on 2026-10-07; live verification and restoration
finished 2026-10-08 UTC. Scope: A and B in
[the selected plan](terrain_streaming_search_and_refresh_efficiency_implementation_plan.md).
Coarse-group visibility rejection and other optimizations were excluded.

**Both edits are built and loaded on the real PC as debug 274. The observations
do not establish an overall terrain-streaming speedup.** Warm-cache rendering,
movement, zoom controls and additional offline cache activations were observed.
The dimension check failed in the debug harness and is not reported as passed.

## Source changes and correctness review

- `WorldCacheDownloads.next()` expands a cached parent only when the existing
  `Coverage.full` summary does not contain its key. It keeps the cached-hit
  increment, wake and one-candidate yield. It does not remove already queued
  descendants, change ordering, skip incomplete parents or require freshness.
- `waitingAtLeast()` stops when it finds enough non-processing jobs. The caller
  immediately rejects zero idle capacity after the existing foreground/DROP
  gates. Processing jobs remain excluded. There is no extra counter or state.
- Five assertions in the two existing scheduler fixture source files were
  adapted to the predicate. No fixtures, unit tests or integration tests were
  added or executed. Those fixture sources were not compiled by the client build.
- The debug updater version advances from 273 to 274.

Independent source review found no blocker. `directory()` still checks the
region incarnation before the completeness summary is used. Saved-slot changes
rebuild completeness and reoffer roots. Eviction invalidates/reseeds summaries;
payload quarantine invalidates the incarnation. Current worker commits and
matching directory overlays retain their existing coherence checks.

The capacity predicate is equivalent to `waitingCount >= idleCapacity`: zero
capacity rejects immediately; a positive threshold rejects only after that many
waiting jobs; fewer waiting jobs permits selection; processing jobs do not count.
This is source reasoning, not a claim that every combination was reproduced live.

For a fully cached subtree with D descendants, the additional completeness lookup
is expected O(1) and avoids enqueueing those descendants. Heap operations and the
rest of the search keep their existing costs. Already queued descendants can
still be popped. The capacity scan is O(1) for zero capacity and stops at the
threshold when reached; its worst case remains O(J) for J jobs when not reached.

## Source, build and binary evidence

Reviewed base: `013e0cee1f0316f5ba977f5611644476f71c3415`, branch
`feature/cache-first-background-updates`. Plan SHA-256:
`9bbdaa8af1de5191ac19d45739c9ea11c0b6c57b43f002c6d78c3f0786a65d11`.

Pinned toolkit 1.0.0 build job `6f0b58af-e769-43ac-a121-4cabf6ae765e`,
build `086de192-74fc-40e7-87fe-60e9cf34ce85`, completed with exit 0:

```text
./gradlew jar debugJar --console=plain --no-daemon
```

[Complete build log](/home/aerosmp/Desktop/Codex_Tools/Voxy_Workflow_Tooling/builds/086de192-74fc-40e7-87fe-60e9cf34ce85/build.log).
No server/native build or test task ran. Packaged bytecode independently confirms
the new predicate/caller and completeness lookup; neither JAR contains fixture
classes. The toolkit build records configuration hashes but does not itself
prove a stable source snapshot. The later stable source ledger, sole source
writer, unchanged final diff and packaged class comparison provide the additional
provenance evidence; they are not labelled a build-time frozen checkout.

| Artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| Normal 274 | 3,948,021 | `6b0944be2c94b2f83785df560a90afd47cd1a12c88d2c754be18bceb1f604743` |
| Debug 274 | 4,126,771 | `557f074d3a19ee53c41423b3a438ecc6893d1e7e1f476818cd19c190a248282b` |

Both JARs increased **54 bytes** versus the locally retained 273 builds, or
55 bytes versus 268. There are no added/removed class files versus the PC's 268
JAR. Only `ClientSession$Session`, `WorldCacheDownloads` and its anonymous `$2`
class differ. The rest of the packaged classes, including rendering and the
debug harness, are byte-identical.

The default versioned `voxy-physical-v1` ledger includes tracked/untracked source,
physical Rust lines including embedded tests, and named client/server/debug/tool/
test categories. The reviewed HEAD ledger was reconstructed with `git show`
using the identical directory membership; no worktree checkout was performed.

| Client production source | Base | Candidate | Delta |
| --- | ---: | ---: | ---: |
| Physical lines | 28,755 | 28,760 | +5 |
| Source files | 159 | 159 | 0 |
| Directories, including empty directories | 45 | 45 | 0 |

All other source categories have zero line/file delta. Fixture text grows 109
bytes with zero line delta; production Java text grows 157 bytes. There are no
empty client source directories. No production fields, dependencies or queues
were added. Operational receipts/helpers live outside the repository source.

## Real PC observations

PC: `GIORKOSPC`; Minecraft: `play.aerosmp.com:25587`; player: `MGengine`.
Baseline pose: `(161.6151474830231, 124, 357.30514965280094)`, yaw 911.2005,
pitch 1.3699092. Candidate was aligned to this pose; wrapped yaw is equivalent.
Photon `photon_v1.3b.zip`, pixel size 28, distance 64, automatic GPU setting,
100 kbps, debug uncapping off and Entire world storage were preserved.

The existing updater installed 274 and restarted only the PC client:
old PID 19012, new PID **36596**, start `2026-10-07T23:50:04.0968547Z`.
PC artifact hash/header and `CLIENT_READY` signed build identity match the
candidate hash. The restart helper reported the new process alive after 45 s.
It also logged an existing launch-copy cleanup/file-in-use warning; the running
client was not terminated to remove that file.

| Existing telemetry | Baseline | Candidate |
| --- | ---: | ---: |
| Monotonic observation window | 28.184 s | 26.091 s |
| Owner CPU, percentage of one core | 90.48% | 94.86% |
| Useful compressed received payload | 95.94 kbps | 91.50 kbps |
| Dedicated section-worker allocation | 0.821 MB/s | 0.794 MB/s |
| Background commits / pending jobs | 0 / 0 | 0 / 0 |
| New local activations within the window | 0 | 0 |
| GC collections / elapsed GC time | 14 / 2,170 ms | 16 / 198 ms |

Candidate JVM UDP downstream payload averaged 96.94 kbps. These counters do not
independently establish all wire overhead. Worker allocation excludes owner,
renderer and transport allocation; full Voxy allocation and FPS tails were not
measured. The baseline has only three pipeline samples; the candidate has 26.

Candidate owner CPU attribution: refresh eligibility 70.64%, `DOWNLOAD_VIEW`
20.27%; baseline 44.55% and 51.04%. Both windows mainly observe cached-terrain
refresh, with mostly idle workers/lanes. Inventory is READY and prefetch failures
are zero. Neither has direct pruning/frontier-hit counters or nonempty waiting
jobs. Consequently they cannot attribute a throughput gain to A+B. Different
fresh GPU/session demand and protocol/cache state also prevent treating the CPU
percentages or small useful-rate difference as a causal regression/speedup.

After restart, the candidate snapshot had 57,670 cache hits, zero cache misses
and 59,870 active records. Later matched-view screenshots show terrain rendering.
Zoom enabled and disabled with failure code zero; normal FOV returned to 70.
These observations do not measure instantaneous finest-detail completion.

Offline check: held only Voxy QUIC, moved 320 blocks through nearby saved terrain,
then observed additional disk-cache work. Cache hits rose from **88,778 to
92,356**, uploads from **91,591 to 95,169**, while received terrain bytes stayed
**911,822**, cache misses stayed zero and failure code stayed zero. This proves
additional cached work completed without incoming terrain in that interval.
Transport was resumed and the hold marker is absent in both final route reads.

The Nether pose was applied, but normal renderer replacement terminated the
debug harness with exact `POSE_FAILED / RENDERER_REPLACED` at step 9. Its result
is retained as **FAIL**. The pinned generic reconciler only accepts its expected
success kind, so the exact signed-build terminal event was verified against the
saved events and recorded as a rejection without replay. Subsequent trace/state
attempts were blocked by that pending operation and did not execute. `finish`
was rejected because the run had already ended automatically.

The original Overworld pose was restored with one fixed command to the verified
Testing/player through its owned RCON listener. The server acknowledged the exact
coordinates; the final screenshot matches the original view. No dimension pass
or comprehensive missing-terrain/eviction/corruption test is claimed. Coherence
paths were reviewed, not destructively fault-injected into the normal cache.

## Retained state, restoration and evidence

Testing JVM **692249** and native **693061** retain their preflight start identities.
Server JAR stays debug 273:
`97150959350c8f86d11f6befba1dcb128b5ccf6f683441d1deb22a19b708cd3f`;
native stays
`523696db897d13054aaa81e96df8405f723b1fde00a6cc407af280770812761c`.
Configurations and other installed server mod hashes match preflight. No Testing
server/native restart or Main/original repository mutation occurred.

The native hard cap stays **999,997,440 bytes**, swap max zero. An observed live
snapshot used 262,111,232 bytes with zero max/OOM/OOM-kill events. This is a sampled
observation, not proof of a continuous peak.

Both pinned PC backup routes are confirmed after update, with helper PIDs
30832 and 13800. Local port metadata was stale after the host reboot, then changed
again when the updater relaunched helpers. Discovery retained strict host-key
checking; final primary/secondary ports are **38025 / 34643**. No host key changed.
The normal cache/reset/profile markers and policy remain preserved. Server cache
anchors and estimated world bytes changed naturally; no slider setting changed.

The stock toolkit server reader failed on foreign process cwd permissions.
Publication retained its existing gates using the already adopted account-owned,
read-only preflight observer. The sealed toolkit was not edited. Failed read-only
SSH/process observations were reconciled before further actions. All cooperative
runs are closed with no uncertain outstanding operation; the failed run remains
failed and the loaded candidate remains installed.

Evidence root:
`/home/aerosmp/Desktop/Codex_Tools/Voxy_Workflow_Tooling/tasks/terrain-streaming-search-20261007`.

- [Baseline metrics](/home/aerosmp/Desktop/Codex_Tools/Voxy_Workflow_Tooling/tasks/terrain-streaming-search-20261007/baseline-metrics-review.json)
- [Candidate metrics](/home/aerosmp/Desktop/Codex_Tools/Voxy_Workflow_Tooling/tasks/terrain-streaming-search-20261007/candidate-metrics-review.json)
- [Restoration proof](/home/aerosmp/Desktop/Codex_Tools/Voxy_Workflow_Tooling/tasks/terrain-streaming-search-20261007/restoration-proof.json)
- [Matched candidate screenshot](/home/aerosmp/Desktop/Codex_Tools/Voxy_Workflow_Tooling/runs/c7ee5b1a-746d-4427-9002-e3422f65cd94/captures/34613dfa-b2ea-40bc-bb96-1c991c6eb214.png)
- [Final restored screenshot](/home/aerosmp/Desktop/Codex_Tools/Voxy_Workflow_Tooling/runs/c7ee5b1a-746d-4427-9002-e3422f65cd94/captures/98e1cda4-713a-49c8-a590-e4c4452bb31e.png)

No fake-client pressure run was used as proof of these Java client changes.
Changes are saved locally on the feature branch. No GitHub push/merge or bypass
of the earlier publication rejection occurred.
