# Continuous cache downloads: implementation results

Implemented 2026-10-06 on `feature/cache-first-background-updates`.
Source commit: `8891a301b0b4530181057f04e7cb3b2a46444a1f`.
Baseline: `b3706834a4558efc13f939044880f448f2163c52`.
Selected plan SHA-256: `2698130d49c5d0591921663d9491ecc2fe8bf1354e1d79cab54e258cb7ce32bd`.

Both selected approaches are implemented. Focused offline gates passed. Debug268
automatically restarted and reconnected on GIORKOSPC, with an independently verified
installed SHA and matching typed loaded-build identity. Live logs demonstrate
background progress alongside pending cached watches and cache loading. A controlled
before/after throughput or latency improvement is **not established**: the baseline
view and demand differed, and view maintenance remains the dominant owner CPU cost.
The one live run closed within its original 600-second clock. Its final harness
status is ABORTED to record incomplete full performance acceptance, rather than
presenting these functional observations as an unrestricted live PASS.

## Changes and boundaries

- `WorldCacheDownloads`: cache exact visibility and signed-long spatial rank in each
  Node, retain the old level/x/z/y ties, and normalize dirty ordering once after a
  view's anchor and membership changes. Dirty offers update the authoritative map;
  keys are never changed while a node remains in a heap. Guard actual heap consumption
  after directory maintenance and preserve dirty state on exceptions.
- `ClientSession`: keep the existing major phases and foreground dispatch; perform
  the late foreground pass and the original bounded SOURCE pass before assigning
  cache-only records to ordinary idle workers. Count uniquely observed publication
  blockers with lazy pass-local identity scratch. Allocation failure, synchronously
  requeued sources and unseen remaining sources defer the tail for that turn.
- Keep control maintenance in DOWNLOADS. Admit at most one new prefetch after the
  successful passes, using current owner/connection, storage, DROP/reprioritization,
  existing urgent-interest bookkeeping and waiting-versus-idle checks. Unrelated
  watches are no longer a blanket veto. Real send refusal still calls `unsent`.
- Add production-invoking admission/ranking fixtures and finite Gradle gates. No
  new production queue, executor, worker reservation, rate, memory or CPU budget.

Native/server source, protocol, worker/stream counts, cache formats/integrity,
storage/disk protection, renderer publication/fences and spatial priority are
unchanged. No approaches 3–7, cache reset, cap adjustment, artificial terrain,
100-client test, server restart or source-world editing/generation task was added.
Main, ASMP_Voxy and ASMP_Voxy_Restart were not edited or operated.

## Source, binary and retained-state cost

The same stable toolkit `voxy-physical` version 1 scope includes tracked/untracked
physical sources, including native test lines. It excludes generated outputs.

| Category | Before | After | Delta |
| --- | ---: | ---: | ---: |
| Client source files / lines | 159 / 28,651 | 159 / 28,755 | 0 / +104 |
| Fixture files / lines | 30 / 6,678 | 32 / 8,083 | +2 / +1,405 |
| Other source categories | unchanged | unchanged | 0 |
| Normal client JAR bytes | 3,946,287 | 3,947,966 | +1,679 |
| Locally built debug267 → debug268 bytes | 4,125,037 | 4,126,716 | +1,679 |
| Actually loaded PC debug267 → debug268 bytes | 4,124,254 | 4,126,716 | +2,462 |

No new source folders. Both new fixture files use the existing package directory;
no source folders, including empty ones, were removed. The local267 baseline and
the retained loaded267 artifact differ because earlier local cleanup had not been
deployed; both baselines are frozen independently. Gradle wiring adds 26 physical
lines outside the source-count scope. Fixtures and debug diagnostics are excluded
from normal packaging by the artifact gate.

An isolated instrumentation agent measured Node 32 → 48 bytes (+16), including
temporary Node allocations; Dimension 184 → 184 bytes. Source-pass scratch allocates
nothing on empty/runnable-only passes, holds one reference for one observed blocker,
and an identity set proportional to distinct observed blockers otherwise. It is
pass-local, with no persistent world-sized blocked-source index.

Ranking comparisons are constant primitive work. Changed ordering still costs
O(F), with O(log F) heap insertion/removal; camera movement is not O(1).

Evidence: [source comparison](../../Codex_Tools/Voxy_Workflow_Tooling/state/receipts/a995c5eb-20af-4474-a923-5f90c72fe29a.json),
[final gate](../../Codex_Tools/Voxy_Workflow_Tooling/tasks/continuous-download-20261006/continuity-gates.log).

## Offline gates

Approach 2 was implemented and passed its isolated gate first (8.529 seconds),
before Approach 1. The first full admission gate failed in 6.181 seconds because its
writer fixture assumed an immediately available directory. The failed log and
receipt are retained. The fixture now settles the real metadata future and retries
actual owner selection within a finite setup deadline; the real non-null job
assertion was preserved. Expanded cases then passed in 10.915 seconds. The final
full gate, after two additional lifecycle cases, passed in 10.641 seconds with stable
source hashes. Client packaging was rebuilt after the source commit and passed
in 3.666 seconds with stable inputs.

Final command ran `cacheDownloadContinuityTest`,
`cacheLoadingOwnerEfficiencyTest`, normal/debug compilation,
`verifyClientOwnershipArtifacts` and `worldCacheRankingBenchmark`, under the same
external cooperative build lease. Normal/debug admission fixtures and related
ownership/publication/telemetry fixtures passed. Existing Rust build dependencies
were UP-TO-DATE; no native build, server JAR build or live server install was required.

Admission proof invokes actual source scheduling, late-idle failure boundaries,
worker leases, foreground/cache-only dispatch, current urgent classification and
its dependency bookkeeping, actual DROP/changed-desire deferral, sendControl/writer
refusal/unsent, and reset/shutdown cleanup. Actual GL-free cache-only workers validate
and persist DATA/EMPTY/ABSENT, reject missing catalogues, and exercise duplicate,
cancelled, promoted and stale epoch/world handoffs. No socket, Minecraft instance
or GL context is launched by these fixtures. Disk/quota refusal uses seeded state,
not physical disk exhaustion. Intentional reset warnings and the existing LWJGL
initialization warnings in fixtures are not live-client failures.

Ranking proof independently checks 220,644 actual Node polls, all supported/search
levels 0–21, negative coordinates, ties, signed overflow, dirty offers and failures,
combined/unchanged views, world/dimension identities, detach/reconnect, snapshot
seed timing, policy reseeding, admission/recovery/stamp reoffers and an older
disk-full completion overtaken by recovery. Comparator bytecode contains only
primitive comparisons; no production per-comparison counter was introduced.

Replay uses identical 2,048-node inventories and events, four warmups and seven
trials, with medians below. Setup/oracle/seed/eligibility/I/O are excluded equally;
test-only lookup counters are included. These are isolated ordering measurements.

| Workload | Former CPU | Candidate CPU | Rebuilds before → after |
| --- | ---: | ---: | ---: |
| Mixed128 events | 20.983 ms | 5.838 ms | 128 → 96 |
| Combined32 events | 15.514 ms | 2.810 ms | 64 → 32 |
| Membership32 events | 8.233 ms | 2.792 ms | 32 → 32 |
| Movement32 events | 2.294 ms | 1.219 ms | 32 → 32 |
| Stable32 events | 1.890 µs | 8.350 µs | 0 → 0 |

Mixed CPU decreased 72.2%; combined CPU decreased 81.9%. Stable replay shows a tiny
additional fixed guard/harness cost, so not every replay is faster. Mixed ordering
allocations decrease 2,110,496 → 1,585,440 bytes; combined 1,055,224 → 528,888. Other
replays have unchanged measured allocations. Node construction is outside those
timed regions; its additional 16 bytes is reported separately. Derived non-null heap
references during rebuild decrease 4,096 → 2,048, excluding backing/constructor arrays
and authoritative-map references. This is not measured peak JVM memory.

## Artifact and running identity

- Normal268: `build/libs/ASMP_voxy-0.2.268-beta+1.21.1-neoforge.jar`,
  SHA-256 `befcfe9021cf07012250c3ac5625b7dc182d7edc921d05acdadac9dfbb23edbc`.
- Debug268: `build/libs/ASMP_voxy-0.2.268-beta+1.21.1-neoforge-debug.jar`,
  SHA-256 `74dc0d71781c677bca2256a04d3913b6bfac87adf67d1b8220cb7d81cac38eeb`.
- PC game30484/start13:04:32.4146252Z automatically became11412/start18:00:54.5600844Z.
  Both pinned routes verify debug268 and its header; typed CLIENT_READY independently
  verifies the four build-identity fields against that SHA.
- Retained server267 controller SHA
  `9900d5273f09304714d481f4288666b26c43da7501807f39c18a98899efbf383`;
  actual Java PID 2289177/start51522105 and native PID 2290032/start51523350 unchanged.
  Actual native and controller embedding match
  `e5dad2dd36b4dfcc6b6c9b9ae54d090e8d351a033980f026327cdaf6c1bb8d7f`.

Hash-verified candidate staging and exact-PC emergency rollback were prepared
externally before publication. Rollback was not executed. Only the selected debug
client feed entry was published. No GitHub push occurred; the earlier automatic
approval rejection of GitHub plan publishing was not bypassed.

## Live observations and remaining limits

Run `0111ad91-f87c-49b3-bf0d-e9ed7106fcd6` used one toolkit manifest/lease,
600 seconds total and 180 seconds reserved for restoration. It closed at 18:09:20.481Z,
elapsed 583.425 seconds, without resetting the clock. BEGIN, traces, pose operations,
two screenshots, restored original pose and END_RUN returned successful typed
results. A typed pose result reached Y100, while both timed traces remained at Y69.
A fall between actions is consistent with normal game physics, but its trajectory
was not captured. Rotation and position changes were observed; a continuous
movement trace or controlled horizontal walking workload was not established. Original pose was restored to overworld
(69.70415812590123,69,144.12548064766514), yaw−49.896973/pitch37.200108.

The initial saved window loaded cached terrain with no background commits while
the planner was traversing directories. The later saved 18:03:10.636–18:05:22.153 UTC
window contains 126 unique snapshots over 131.274 owner-snapshot seconds:

| Observation | Measured delta |
| --- | ---: |
| Background commits / compressed bytes | 3,654 / 5,392,551 |
| Cached / fresh DATA activations | 8,043 / 281 |
| Foreground compressed bytes | 950,946 |
| Cache-file read bytes | 49,163,725 |
| Managed cache disk growth | 7,319,001 bytes |
| QUIC socket UDP payload received / sent | 7,520,480 / 1,333,993 bytes |
| Owner CPU / busy elapsed | 100.750 / 110.055 seconds |
| DOWNLOAD_VIEW CPU / elapsed | 82.125 / 91.915 seconds |
| STAGES CPU / elapsed | 3.219 / 5.174 seconds |

QUIC counters include encrypted payload/framing/retransmissions, excluding IP/UDP
and link headers. Cache growth is not bandwidth; validation can transfer no body.
The snapshot clock and published UTC span differ slightly; rates use snapshot time.

Six adjacent intervals begin with nonzero interests, idle workers and SOURCE 0,
then show 701 background commits/1,017,044 compressed bytes. Four also contain
198 purpose2/HAVE/localProbed watch events. For example18:03:39.334→18:03:40.334:
interest 15 at both ends, 14 idle workers and SOURCE 0 at both ends, 82 background
commits/136,921 bytes, 19 cached watch events. This supports watch/spare/progress
coexistence at sampled-interval granularity; it is not same-turn instrumentation.
Foreground precedence is established directly by the production-method fixtures;
live SOURCE pressure was also present in 51/126 snapshots.

Completed-cohort cache mean lease is 94.656 ms: assignment 0.036, service 3.910,
completion-to-claim 30.668, claim-to-reuse 60.043 ms. Cache meshing totals 7.491 worker
seconds; model waits 17.873 seconds. Background mean lease 105.117 ms includes
71.548 ms waiting for a regional writer and20.763 ms save/encode. These are summed
worker/cohort observations, not independent elapsed stages to add to owner time.
Refinement publication records in this window have queue-to-GPU below 16 ms and
GPU-to-active below 50 ms. They are not a controlled urgent-arrival latency comparison.
SOURCE_SCHEDULE telemetry now includes the late reply pass/background tail; it is
not a pure source scheduling leaf. Full owner CPU is reported to avoid claiming a
phase move alone is an improvement.

No error lines, cache corruption or save failures occur in the captured candidate
windows. Screenshots were inspected: near terrain is textured and terrain is visible,
but the views are partly occluded and do not prove whole-world coverage, seams,
absence of flicker or perfect rendering. Worker/metadata allocation delta is
421,820,984 bytes (~3.21 MB/s), excluding owner/renderer; no whole-mod allocation
or FPS claim follows from that subset.

Baseline and candidate view, cache warmth and demand differ. Maximum bandwidth,
controlled throughput improvement, horizontal continuous-movement equivalence and
urgent-arrival regression acceptance remain inconclusive. DOWNLOAD_VIEW still
dominates CPU. Writer waits and publication/model handoffs are other measured costs;
indexed heaps, extra threads or approaches 3–7 require a separate decision.

An attempted final run-owned PC observation was refused by the restoration-reserve
guard before submission; no extra observation clock was started. Confirmed END_RUN
and independent restoration-only checks finished in the same original clock.
Normal config SHA, bandwidth 20,000 kbps/debug-uncapped true/Entire-world allowance,
reset/profile markers and both original helper PID/start/session identities are
preserved on both routes. Estimates/anchors legitimately changed during operation.
Server/controller/native/config/other-mod identities match the pre-run snapshot.
JVM args remain `-Xms1G -Xmx4G`; native memory.max remains 999,997,440 bytes, swap.max 0,
and all memory.events counters are unchanged (OOM/kill 0; historical max 53).
Final native memory.current 227,377,152 bytes. No additional resource limiter was added.

Physical disk exhaustion, constructor OOM and arbitrary directory-result fault
injection were not performed. Source-audited unchanged paths and seeded refusal
fixtures do not constitute those physical experiments.

Evidence:
[late measurements](../../Codex_Tools/Voxy_Workflow_Tooling/tasks/continuous-download-20261006/candidate-moving-late-summary.json),
[watch/progress intervals](../../Codex_Tools/Voxy_Workflow_Tooling/tasks/continuous-download-20261006/candidate-moving-overlap-summary.json),
[typed loaded identity](../../Codex_Tools/Voxy_Workflow_Tooling/state/receipts/b666ef6e-da7a-4665-ace2-1227209d01ba.json),
[restoration checks](../../Codex_Tools/Voxy_Workflow_Tooling/tasks/continuous-download-20261006/restoration.json),
[closed clock](../../Codex_Tools/Voxy_Workflow_Tooling/state/receipts/344ca98e-ca8d-4846-b536-250664757d52.json).
