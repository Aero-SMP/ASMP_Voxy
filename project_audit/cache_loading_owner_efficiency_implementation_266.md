# Cache-loading owner efficiency: implementation266

Recorded 2026-10-06. Selected plan:
[cache_loading_owner_efficiency_implementation_plan.md](cache_loading_owner_efficiency_implementation_plan.md).
Branch: `feature/cache-first-background-updates`.
Baseline: `187f80184d6b8df001e1d83665c0720714aaff23`.
Implementation: `6957a5432f1baaaa74459def6449cdc86aec2513`, pushed to the feature branch.

## Outcome

The four selected approaches are implemented. Focused production-method fixtures,
normal/debug packaging checks and both client builds passed. Debug client266 was
published, remotely restarted, hash-confirmed on GIORKOSPC, and photographed;
the actual screenshot was viewed. Terrain activation continued and the typed
trace reached zero outstanding worker leases.

Live acceptance is **partial / TIMEOUT**, not PASS. The one ten-minute clock was
exceeded: remote harness cleanup finished at624.54 seconds; state-only lease
closure finished at683.95 seconds. This violated the plan's hard ceiling. The
guard rejected additional observations, no replacement clock was started, and
no further live probes or tests were performed. The captured log tail lacks a
sustained startup cache backlog, so it cannot establish an end-to-end cache
loading speedup or exclude a performance regression.

## Implemented changes

| Approach | Change | Preserved behavior |
| --- | --- | --- |
| 1: unchanged notifications | `addDemand(key,bucket)` reports adoption using its existing lookup. `addChildren()` still ensures every child and updates priority, but emits dependent topology notifications only for a flag transition or newly adopted/repaired child. Repeated publication closure notifies only for a real transition/new retirement. | Removed-child repair, empty-dependent interests, child-mask activation, coarsening, retries and exactly-once retirement; callbacks remain outside the publication monitor. |
| 2: retained detail buckets | Each session owns the32 detail deques. The original processing passes reuse them and a `finally` clears every deque. | Ascending dormancy/wake, eviction, descending refinement, equal-bucket encounter order, failed-expansion break, lower-bucket continuation and newer-epoch retry coalescing. The shared mailbox is not cleared by local cleanup. |
| 4: publication scan guard | A renderer progress generation and owner dirty flag skip unchanged queue passes. The observed generation and dirty flag are consumed before scanning; failures re-dirty. Accepted references dirty once after batch installation. | Synchronous completion, changes during scans, revision/preservation/removal/reset paths, independent blocked retry and health checks, and unconditional shutdown ownership cleanup. |
| 6: detail readback batching | The traverser supplies one synchronous borrowed reader for each bounded native readback. One captured renderer/session/view/input scope merges under one mailbox monitor and signals once after release when any prefix was accepted. | Native bounds/action filtering, unsigned epochs, equal-epoch rejection, per-key overwrite accounting, concurrent take/merge, stale-scope rejection and exception propagation. No native pointer escapes callback lifetime. |

The native readback remains bounded by its existing32 buckets of256 records.
No new runtime budget, executor, copied action array, persistent action queue,
compatibility listener, format, worker count or owner phase-order change was added.
Worker lease release, save acknowledgements, GPU fences and the fallback wait
remain unchanged. Meshing, decoding, renderer selection and cache storage policy
were outside this implementation.

World correction now advances the volatile view revision **before** invalidating
the input generation and clearing queued detail. Review identified that reversing
these steps could capture the new input generation with the old view. This is a
touched-path lifecycle correction required by the plan's stale-readback guarantee.

The owner telemetry appends bounded topology/scan/invalidation counters and
separate batch counters. Batch duration is timed once **inside** the mailbox
monitor; it excludes acquisition wait and the subsequent signal. Normal hooks
remain no-ops. Owner state is owner-confined; producer batch statistics use their
separate lock, and the debug timing enable flag is volatile.

Primary production files:

- [ClientSession.java](../src/main/java/me/cortex/voxy/client/lod/ClientSession.java)
- [SectionDemandTable.java](../src/main/java/me/cortex/voxy/client/lod/SectionDemandTable.java)
- [SectionPublicationState.java](../src/main/java/me/cortex/voxy/client/core/rendering/hierarchical/SectionPublicationState.java)
- [HierarchicalOcclusionTraverser.java](../src/main/java/me/cortex/voxy/client/core/rendering/hierarchical/HierarchicalOcclusionTraverser.java)
- [VoxyRenderSystem.java](../src/main/java/me/cortex/voxy/client/core/VoxyRenderSystem.java)
- [ClientLodClient.java](../src/main/java/me/cortex/voxy/client/lod/ClientLodClient.java)

## Complexity and intrinsic costs

Child ensuring still takes work proportional to required children; unchanged
dependent fanout now scales with actual topology changes instead of refinement
events. The32 bucket containers and their backing storage are constructed once
per session rather than once per drain. Detail processing and feedback inspection
remain linear in accepted/drained actions; this is not a zero-allocation path.

Publication polling changes from owner turns times pending references to
progress-bearing turns times pending references. Each required scan still visits
the existing queue. Generic renderer progress can still cause unnecessary scans;
this intentionally stays smaller than per-publication dispatch.

Feedback monitor acquisition and owner signaling are per-readback rather than
per-action. An individual acquisition lasts longer, and atomic merging changes
intermediate producer/owner interleavings. Latest-epoch coalescing and ordering
rules are preserved. Small callback/merge objects remain. Deque `clear()` drops
event references but retains peak backing capacity until the session ends.

No new pairwise/quadratic algorithm was introduced. The larger refinement fast
path, persistence throughput and early empty-result lease release remain separate
work; this implementation does not claim to solve them.

## Focused verification and builds

All jobs used the pinned external toolkit1.0.0 and finite build presets. No laptop,
fake100, pressure or integration suite was run. The selected plan explicitly
required isolated ownership/order/lifetime fixtures against production methods.

The aggregate `cacheLoadingOwnerEfficiencyTest` executes normal/debug owner,
batch and publication fixtures, including existing topology, renderer admission,
shutdown and callback lock-order checks. It also executes the existing timing
fixtures. `debugHarnessJavaTest` depends on this aggregate and packaging checks;
the harness preset additionally ran its Java checks and10 Python runner fixtures.
The latter deliberately emit failure/timeout scenarios as fixture inputs.

Coverage includes first/repeated/repaired topology and priorities; empty watchers;
coarsening/reset; exact bucket order and exceptional cleanup; retries/newer epochs;
real worker lease/save/publication outcomes; scan notification races and synchronous
installation; blocked retry/health/shutdown; native empty/full/oversized counts;
borrowed-view invalidation; renderer/session/view/reset replacement; accepted-prefix
failure wake; and concurrent merge/take. The retained-deque fixture instruments
the actual deque objects, so replacing them with temporary buckets fails it.
Wake fixtures verify wake/no-wake conditions; exact one-signal placement is also
established by the production `finally` path and consistent live counters.

Two initial build failures are retained. The first was fixture compilation
(collection type and private nested-event access); it was corrected without a
production test seam. The second was an existing debug-harness fixture supplying
an empty DOWNLOAD_POLICY despite required bandwidth fields. The fixture now uses
valid policy input and checks valid/invalid policy handling; production policy
behavior was unchanged. Optional headless LWJGL/terminal warnings are retained
in logs; the required native operations and ownership assertions passed.

| Job | Result | Saved log |
| --- | --- | --- |
| `f55cb6d3-a59c-43df-a56b-0e831bacc69f` | Fixture compilation failed; corrected | [first build](../../Codex_Tools/Voxy_Workflow_Tooling/builds/99c9b94e-3d29-4533-8f9b-4b6c96ee076c/build.log) |
| `8be3ea48-a8ad-4c39-acf6-8734c374d59b` | Owner fixtures passed; old policy fixture failed; corrected | [second build](../../Codex_Tools/Voxy_Workflow_Tooling/builds/7441b8f2-93e4-48dd-946c-2f90f9b32cbb/build.log) |
| `18211cd5-dbca-4b79-ba77-0221f097ea15` | All scoped fixtures and packaging checks passed | [successful harness](../../Codex_Tools/Voxy_Workflow_Tooling/builds/830f3c0a-7e33-4727-adf6-ce2d9597202a/build.log) |
| `9dc5dfdf-6a29-424d-bcea-a6bbb4abb08a` | Normal/debug clients built from committed implementation | [client build](../../Codex_Tools/Voxy_Workflow_Tooling/builds/eeba7063-6fb0-44cb-a7a6-f983e85c9dd1/build.log) |

Both client artifacts exclude fixture classes. Normal artifact telemetry remains
absent. The clients preset also produced local server artifacts through Gradle's
subproject task selection; none was deployed and the server was not restarted.

## Source and artifact deltas

Counts use toolkit **voxy-physical-v1**, physical lines including comments/blanks,
tracked/untracked source and embedded Rust test lines. This is not the historical
semantic release counter. Baseline/current scope fingerprint is identical:
`aa3f8993266dbe954f1bea5d839819bf73218f1d152e487aae8ce3020e016044`.

| Category | Files | Physical lines | Delta |
| --- | ---: | ---: | --- |
| Client | 158 | 28,573 | +201 lines; no files |
| Debug | 32 | 5,778 | +38 lines; no files |
| Tests in default scope | 28 | 6,088 | +2 files, +966 lines |
| Server | 6 | 1,110 | unchanged |
| Native | 20 | 10,706 | unchanged |
| Shared | 1 | 178 | unchanged |
| Tools | 9 | 1,908 | unchanged |

No production source file/folder was added. Default scope omits `src/debugTest`:
its changed fixtures contribute another108 lines, so all changed Java test source
is +1,074 lines. `build.gradle` adds51 lines for executable fixture tasks and is
outside the default source extension scope. No line-compaction workaround was used.

Saved snapshots:
[baseline](../../Codex_Tools/Voxy_Workflow_Tooling/state/sources/source-067964c94c964b0cb4def8d4d89b716d.json),
[committed current](../../Codex_Tools/Voxy_Workflow_Tooling/state/sources/source-febb5703d3084d84935a52a77f48fd26.json).

| Artifact266 | Bytes | Change from265 | SHA-256 |
| --- | ---: | ---: | --- |
| Normal client | 3,944,433 | +9,516 | `7617d92f0dff845588381082bd66433c8c5fdfa4d1cd6d3a0861a66dd0ef0809` |
| Debug client | 4,123,202 | +11,591 | `2008c8409b86ded8e26da635410fcad20279f2eb71fbcbaf319bc66c20cc17a6` |

Debug artifact:
[ASMP_voxy-0.2.266-beta+1.21.1-neoforge-debug.jar](../build/libs/ASMP_voxy-0.2.266-beta+1.21.1-neoforge-debug.jar).
The PC updater feed contains the same hash under `build/libs/debug-clients/MGengine/`.
Immutable candidates, prior265 rollback artifact and receipts are stored under
[external task evidence](../../Codex_Tools/Voxy_Workflow_Tooling/tasks/owner-efficiency-20261006/).

## Real-PC deployment and observation

One adopted toolkit run was used:
`0d8da93d-e27e-4f96-b6a8-5d20b578177b`, owner `owner-efficiency-266`.
The originally selected plan hash was
`758545527c27a179b68f5cfa37ccfa4240e1aa51755c28128ec4621b0a77ef01`.
The clock began2026-10-06T12:24:10.26Z with600 seconds total and45 seconds
reserved for cleanup. No pose, settings, cache deletion/reset or transport-hold
control was used. Existing cache and policies were retained.

The initial PC was client265, PID18220. The updated PC was PID21660,
start2026-10-06T12:29:21.1529742Z, header `Voxy version 0.2.266-beta`,
installed hash exactly matching the debug artifact above. Typed CLIENT_READY and
trace build-identity chunks independently matched that hash. Both backup SSH
routes were confirmed before publication; their helper PIDs19916/22444 and
creation times were unchanged in the loaded-client observation.

The loaded-state receipt also retains the general config hash, cache-reset marker
and profile-off state. Background cache contents and per-server saved anchors/
estimates naturally change during loading; no claim is made that all cache or
per-server metadata bytes stayed identical. No unrelated mod replacement was
issued. Client266 was intentionally retained after harness cleanup.

The inspected [1920×1081 screenshot](../../Codex_Tools/Voxy_Workflow_Tooling/runs/0d8da93d-e27e-4f96-b6a8-5d20b578177b/captures/43ff0ac0-7997-4d8c-bd37-fae3bee804db.png)
shows nearby structures/forest and distant mountain terrain during rain. No
obvious missing-texture checkerboard or large terrain hole was visible in this
frame. It does not prove every model, seam, zoom path or region correct.

Compatible Testing server260 was retained. Before publication, independently
observed controller PID306308/startticks45505073 and native PID307526/
startticks45506585 matched retained state. Server jar hash:
`89ab21697970868d5289e43bb3bfc061f43fc4536d3534a84c9c40c6ce1d788c`;
embedded native hash:
`c1352e54bfd0af7f5c7f03518970cd0a2e162715cc05b18e0eda42e315f40ea9`.
Its native cgroup had `memory.max=999997440`, `memory.swap.max=0`,
current669741056 bytes, `oom=0`, `oom_kill=0`; existing `max=63` was already
present before publication. These are before-publication observations. The final
server check was rejected by the deadline guard, so no post-run OOM or complete
end-state preservation claim is made.

The toolkit `server.state` adapter failed while enumerating an unrelated foreign
`/proc` cwd. A read-only fallback restricted observation to the two known target
PIDs and used the same deadline plus retained-state/artifact validators. No sealed
toolkit file was changed and no old deployment script was imported as a library.
The rejected read operation and a read during client startup were explicitly
reconciled; unknown mutations were not replayed.

## Measurements and limits

The captured32MiB debug tail starts at byte36,303,551 of69,857,983 and has hash
`deb42f5f6882c62b450a2ed3bf23a3ac5178fc3a2446f2fecab15d790d044121`.
It contains47 snapshots spanning48.7754004 monotonic seconds, with log timestamps
12:31:01–12:31:49. The startup header is outside this tail; the analyzer reports
`versionHeaderMatch=false` honestly. Loaded version is established by the separate
process/header/hash and typed receipts, not by manufacturing a log header.

| Measurement in saved window | Result |
| --- | ---: |
| Unchanged topology expansions suppressed / actual notifications | 226,843 / 7 |
| Publication passes / skipped turns | 3,752 / 16,603; 81.57% skipped |
| Publication references visited | 19,792 |
| Accepted feedback actions / targeted batches / signals | 389,155 / 9,882 / 5,832 |
| Accepted actions per signal | 66.73 |
| Total mailbox merge elapsed / mean per targeted batch | 76.81ms / 7.77µs |
| Endpoint lifetime maximum merge elapsed | 1.564ms; not a window maximum |
| Cached DATA activations / fresh DATA activations | 113 / 4,142 |
| All DATA activation rate / cached DATA activation rate | 87.24/s / 2.32/s |
| References visited per all DATA activation | 4.65 |
| Owner overall CPU | 69.03% of one core |
| DETAIL_REFINE / PUBLICATIONS elapsed | 0.7541s / 0.04442s |
| Measured worker allocation | 1,495,277,952 bytes; 30.66MB/s |
| GC delta | 12 collections; 427 collector-ms |

Batch counts include empty/rejected targeted merges; stale scopes rejected before
the callback are not counted. Consequently signals need not equal batch count.
The allocation rate covers14 section workers plus the metadata worker, including
their diagnostics. It excludes owner/render/network threads and is not total
Voxy allocation. GC collector time is not stop-the-world pause time. Windows
fine-grained phase CPU attribution remains quantized; overall owner CPU and
monotonic elapsed intervals are preferred.

Exact paired lease cohorts partition into all four stages with matching counts:

| Cohort | Count | Assign→begin | Compute | Complete→claim | Claim→reuse | Total mean |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| CACHE | 156 | 0.014ms | 6.256ms | 20.246ms | 18.615ms | 45.131ms |
| EMPTY | 82 | 0.015ms | 0.018ms | 23.018ms | 71.821ms | 94.872ms |

These measure paired completed lease residence and can cross window boundaries;
they are not instantaneous occupancy. Across paired section cohorts,98.88% of
lease residence followed computation. Conditional failed-dispatch gate samples
were74.52% SAVE_AFTER_RELEASE, which is not percentage wall-time utilization.
The unchanged empty-result and persistence lease limitations remain visible.

The saved tail recorded zero worker-failure, cache-corruption and save-failure
deltas, with14 save cancellations. Final saved pipeline source/publication/save
queues were drained; the later typed trace had zero outstanding leases. Its
coverageMissing count was still25,446, so an idle endpoint is not complete-world
coverage proof. No error/warning/exception lines were found in the retained tails;
that statement does not cover discarded prefixes or every mod's output.

Only1 of47 pipeline samples had a positive source-ready queue (maximum3).
This is primarily fresh/network work, not sustained warm-cache loading. The
client265 baseline was a high-altitude cache-heavy window near(0,1000,0); client266
remained near(-57.63,120,314.26), FOV70, yaw158.03, pitch16.60. Different demand,
viewpoint and network activity make activation/CPU ratios unsuitable for a
controlled before/after speedup claim. The batch/guard mechanisms are observed;
overall cache-loading improvement and unchanged FPS remain unproven.

Existing DOWNLOAD_VIEW elapsed28.93s and retention7.68s dominate remaining owner
work in this network-heavy window. Persistence stage totals are concurrent worker
sums, not wall duration. They identify off-scope remaining work, not justification
for changing the chosen plan during this implementation.

## Deadline deviation and acceptance record

The operator failed to reserve cleanup time while resolving toolkit target-state
and publication details. Final PC/server observations and a startup-prefix capture
were rejected at the reserve boundary before performing their commands. Cleanup
then ended the harness and submitted TIMEOUT after the600-second deadline.
Remote cleanup ended at624.541846 seconds; state-only close recorded683.952935
seconds. All recorded operations are resolved and the run is CLOSED. This is a
testing-process failure, not a passed ten-minute verification.

No cache/settings/transport controls had been applied, so cleanup required ending
the harness, not restoring those controls. No binary rollback was performed.
Both helpers were preserved at the loaded-client observation; absence of a final
PC/server observation limits final independent end-state proof.

| Requirement | Status |
| --- | --- |
| Four production changes and focused ownership/order/lifetime checks | Implemented; scoped checks passed |
| Normal/debug builds and packaging | Passed |
| Actual client266 loaded; screenshot inspected; useful activation continued | Confirmed |
| Repeated work reduced and targeted batches observed | Confirmed in saved window |
| Comparable sustained warm-cache throughput/FPS improvement | Unverified; retained window unsuitable |
| No newly growing retained backlog after loading settles | Partial endpoint evidence; no controlled sustained comparison |
| Strict600-second total live ceiling | Failed; TIMEOUT |
| Final independent PC/server preservation and memory observation | Unverified; guard rejected final probes |

## Evidence index

External task root:
`/home/aerosmp/Desktop/Codex_Tools/Voxy_Workflow_Tooling/tasks/owner-efficiency-20261006`.
Operators, artifacts, private build caches and full ledgers remain outside maintained
project source. Key saved evidence:

- [PC loaded state](../../Codex_Tools/Voxy_Workflow_Tooling/tasks/owner-efficiency-20261006/receipts/pc-loaded-state.json)
- [Typed trace](../../Codex_Tools/Voxy_Workflow_Tooling/tasks/owner-efficiency-20261006/receipts/trace266.json)
- [Publication receipt](../../Codex_Tools/Voxy_Workflow_Tooling/tasks/owner-efficiency-20261006/receipts/published266.json)
- [Log capture receipt](../../Codex_Tools/Voxy_Workflow_Tooling/tasks/owner-efficiency-20261006/captures/initial266/receipt.json)
- [Saved analysis](../../Codex_Tools/Voxy_Workflow_Tooling/tasks/owner-efficiency-20261006/captures/analysis266.json)
- [Paired metrics and limitations](../../Codex_Tools/Voxy_Workflow_Tooling/tasks/owner-efficiency-20261006/captures/analysis266-supplement.json)
- [TIMEOUT finish](../../Codex_Tools/Voxy_Workflow_Tooling/tasks/owner-efficiency-20261006/receipts/finish-cleanup.json)
- [Closed run and resolved operations](../../Codex_Tools/Voxy_Workflow_Tooling/tasks/owner-efficiency-20261006/receipts/run-closed.json)
- [Immutable toolkit evidence bundle](../../Codex_Tools/Voxy_Workflow_Tooling/evidence/0fc2e054f78742048cd4715c0ebf1774/bundle.json)

The imported bundle is complete for the saved tail only. It does not recover the
omitted startup prefix. The toolkit's subsequent `evidence errors` query failed
with `QUERY_INPUT_LIMIT` because its expanded index segment exceeded64MiB. Error
line statements above come from direct inspection of the frozen original tails,
not a successful toolkit error query. That failed query receipt is retained at
`state/receipts/75548394-786f-4288-b420-9c94ad77ad0c.json` under the toolkit root.
Requirement failures and mismatches remain recorded.
