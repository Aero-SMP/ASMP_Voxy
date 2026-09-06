# Cache keys, mesher neighbor setup and metadata length results

2026-09-06 UTC. Baseline `b58b1714`, initially clean; implementation `1799adad`.
The unrelated untracked `fix_response` appeared during the task and was left untouched.

**Final status:** all three client changes are implemented and deployed as debug .200.
Offline gates, cache-before-network restart, and repeated zoom tests pass. Overall live
acceptance is **not an all-clear**: the unchanged .198 Minecraft server suffered a heap OOM
after the reconnect test and lost its Rust supervisor/backend. See the incident below.

## Implemented scope

- `RegionalCache` retains one `(long low, long high, int length)` record per indexed
  entry. Both fingerprint halves, compressed length, generated equality/hash, primitive
  offsets, zero sentinel, replay order, tombstones, read-only behavior and ownership remain.
- `SectionMesher` calculates signed neighbor stride and outside-plane status once per
  plane. The new delta is the last argument to `faceData`, preserving its existing argument
  order. Neighbor access remains after the original early exits. The old helper is removed.
  `prepare`, separate `aabb`, output ordering, water-side omission and lighting are unchanged.
- `RegionalMetadataStore` captures one extent immediately after each channel open and
  reuses it for validation. Final files are still written to a pending file, forced and
  atomically replaced; no production writer mutates the final file in place. Header limits,
  exact length, full reads, CRC, reference extraction, pins and cleanup are unchanged.

No protocol/storage migration, server algorithm, renderer limit, scheduling or eviction
change. No worlds or ordinary client caches were deleted. No production instrumentation.

## Offline correctness and artifacts

All of the following passed after the cache change, after adding the mesher change, and
in the combined/final configuration:

```text
./gradlew schedulerBehaviorTest
./gradlew workerDebugBehaviorTest debugHarnessJavaTest debugHarnessPythonTest
./gradlew buildAll verifyDebugHarnessArtifacts
git diff --check
```

Build commands used `-I /tmp/voxy-renderer-admission-build.gradle --console=plain` to keep
unverified artifacts out of `build/libs`, which the live updater polls. Scheduler benchmarks
ran separately with the optional test-only agent. Final non-agent gates are in
`project_audit/cache_key_mesher_neighbor_metadata_evidence/final-gates.log`; the post-commit
artifact rebuild is `committed-artifacts.log`. Normal/debug packaging checks explicitly reject
`InventoryQueryAgent*` as well as behavior tests; normal jars must exclude debug controls.

New tests execute through the scheduler runner:

- Independent low/high/length keys, zero and negative bit patterns, duplicate put, tombstone,
  reinsert, missing length and 256 deliberately colliding hashes survive real shard replay.
  Whole file bytes match an independently encoded legacy envelope. Existing malformed/torn
  suffix, growing map, offsets, read-only repair, ownership, pin/trim and truncation tests remain.
- The actual `fillPlane` path checks every one of the **196,608** face/depth/position mappings
  against independent XYZ arithmetic. Two light-byte projections identify all 15 neighbor
  address bits; encoded tint, interior light, boundary light and reused air workspace are checked.
  Existing complete ordered mesh golden remains
  `902cccc18a2c2594be1be3df61fbb370153362ec802e88be795d139beb2ff87c`.
  It covers controlled solids, sparse/fluid/overlay/invisible/empty fixtures, corners, walls,
  all five LODs, masks/revisions, independent workers and lookup failure/reuse. Existing
  boundary-water, lit-air, shader readiness and geometry endpoint tests also pass.
- Real metadata readers accept legal bodies and reject malformed headers, truncated/trailing
  bytes, invalid lengths, bad CRC and missing files. Zero/nonzero catalog references are checked.
  Test-only bytecode interception deterministically replaces an opened path after its first
  size query: the reader gets the complete old file. Truncation at that point fails safely.
  This is Linux open-file behavior, not a Windows replacement test. Concurrent external
  extension after the snapshot is not guaranteed to be detected; normal open-time validation
  remains strict. Existing catalog pin, inventory mutation and replacement/lifecycle tests remain.

Baseline sources with identical added tests are isolated at
`/tmp/voxy-key-neighbor-baseline.uiY0bJ`. Both directions of file compatibility passed:
candidate reads baseline output; the isolated old reader reads candidate output.
Both exported fixture files have SHA-256
`c10f7083c0c5784cc48b7935b6a877c0cecf48218342f38a5d0b23ab7be75637`.

## Measurements and limitations

Reproduction entry point:

```text
./gradlew -I tools/simplification_bench.gradle schedulerBehaviorTest
# Old baseline only:
# -Dvoxy.baselineKeys=true -Dvoxy.metadataReadSizeQueries=3 -Dvoxy.metadataReferenceSizeQueries=2
# Optional cross-reader fixtures: -Dvoxy.cacheInteropInput=<path> -Dvoxy.cacheInteropOutput=<path>
```

Each measurement uses two warmup samples and five measured samples. Meshing additionally
warms 400 jobs per fixture and measures 200 jobs per sample. Test-only ThreadMXBean records
allocation and worker CPU time, reporting unsupported measurements as unavailable (-1).
Instrumentation counts explicit Voxy size queries, not filesystem internals.

| Structural measurement | Baseline | Candidate |
| --- | ---: | ---: |
| Retained key objects, 32,768 entries | 65,536 | 32,768 |
| Retained key graph, excluding map arrays | 1,835,008 B | 1,048,576 B |
| Allocated bytes per replay, paired runs | 3,409,112 B | 2,622,680 B |
| Explicit sizes per valid metadata read | 3 | 1 |
| Explicit sizes per catalog-reference read | 2 | 1 |

The key graph saves **786,432 B (768 KiB)** in this OpenJDK 21 JVM, or 24 B per entry.
This is measured object layout, not a universal JVM guarantee or process RSS reduction.
The map remains O(R); temporary lookup fingerprints still exist. Mesh allocation remains
128 B/job for measured nonempty results and 48 B/job for empty results, excluding native geometry.

Initial cache-only and cumulative mesher/metadata measurements are preserved as `baseline.log`,
`key-only.log`, `key-mesher.log`, `combined.log`, `combined-repeat.log`, and
`combined-delta-last.log`. Early mesher runs showed a possible 10–20% regression, so deployment
was delayed for investigation. Inlining diagnostics show `faceData` remains a separate hot
call; the final implementation passes delta last. No alternate mesher or JVM tuning was added.

Final fresh-JVM tests ran sequentially on CPU 6 in B1/C1/C2/B2 order, with identical inputs and
test classpaths resolved from Gradle. Raw logs: `paired-b1.log`, `paired-c1.log`, `paired-c2.log`,
`paired-b2.log`. `/tmp/voxy-key-paired-bench.sh` records the exact direct-JVM invocation.
Median wall milliseconds per job (first two samples excluded):

| Fixture | B1 | C1 | C2 | B2 |
| --- | ---: | ---: | ---: | ---: |
| Dense | 1.632 | 1.364 | 1.111 | 1.132 |
| Air | 0.832 | 0.665 | 0.837 | 0.799 |
| Sparse | 2.534 | 1.421 | 1.803 | 1.711 |
| Waterlogged overlay | 5.294 | 2.818 | 3.726 | 4.283 |
| No-face model | 0.805 | 0.727 | 0.857 | 1.167 |
| Model zero | 0.806 | 0.720 | 0.801 | 1.136 |

CPU medians track these wall medians closely. Across the four runs, median replay times are
20.35 / 17.21 / 15.05 / 20.78 ms; median metadata read+reference pairs are
10.73 / 10.63 / 11.21 / 11.04 microseconds. Replay measured-sample ranges are
14.79–27.53 / 13.70–18.49 / 13.97–23.29 / 17.21–21.80 ms.
Large fresh-JVM/JIT/shared-host variation remains. There is no consistent material mesher
regression in the final pairs, but **no reliable meshing speedup or metadata elapsed-time
speedup is claimed**. The structural reductions are deterministic; timing ratios are not.

Earlier `baseline-cpu6.log` and `combined-cpu6-inlining.log` overlapped on one CPU and are
diagnostic only, not valid paired wall-time comparisons. Their raw failures/output are retained.
No host OS caches were dropped: these are warm-filesystem/fresh-process tests, not cold-disk
measurements. No JFR/heap dump, isolated lookup/insert-throughput benchmark, or new exhaustive
material-library/checkerboard golden was produced. Existing fixtures plus exhaustive neighbor
address tests establish this change's behavior; do not claim those extra measurements passed.

## Client deployment and live checks

Intended pairing: debug client **.200**, unchanged debug server **.198**.

- Baseline loaded client `.199`: `c5ba9da2b9d3b4500e6977f7e694951458e3440959c50a7e2d26f6f6abed7313`.
- Published `.200` from commit `1799adad`: `19edae7f54e5f894cce914d23c9221dbe037841cbbded8cfa315f666ddea4502`.
- Existing server jar: `c3b902d448d6aa3267ececf1c938ad2ab411bc2a6254c6794c7c2e6b8927417d`.
- Pre-incident running Rust PID 1679938 executable: `2c3c11158b507421cb9b45c60acca0c7aa5c181d6f1e8591b5152d5ce56d1dca`.

Server jar and actual `/proc` executable were rehashed. No server deployment/restart occurred.
The .200 jar was staged as `.pending` then atomically published to `build/libs` only after gates.
The earlier uncommitted staged jar hash `2a4a32f6...` was NOT deployed; rebuilding embedded the
implementation commit before publication.

Live evidence is under `/home/printer/Desktop/Creative/logs/voxy-tests/<run-id>/` and the ignored
task evidence directory. Screenshots are `/home/printer/screenshots/voxy-test-<run>-<step>.png`.
The designated player is MGengine; original pose is (-0.4575432366614107, 1002.2445958642462,
-0.7655339439280795), yaw -31.672363, pitch 12.735964, world FOV 76.99999332427979, shaders off.
Hard GPU limit stays 2,147,482,624 B. Only this player's existing test profile is exercised.

Baseline real zoom cycle `d29d27f0-4303-4746-a2cf-a5cc5b3124b1` passed. Repeated baseline
aggregate `aggregate-c27584123f1f-1788686270.json` ran two warmups plus five measured cycles:
all five measured cycles passed; first warmup `36d72b07-9d35-41a3-a50d-f92613002467` failed
the unchanged coverageMissing==0 assertion with value 1 at the GPU cap. Preserve this existing
intermittent coverage issue; it is not evidence caused by this candidate, nor an all-clear.

Cache population `a2b63a31-a112-4c49-890c-46151fa22e68` passed on runtime-verified .199.
Uploaded marker confirmed `VOXY_CACHE_START transportHeld=true persisted=true` at
09:20:37.263590 UTC before .200 publication. The updater helper started at 09:22:12.546868400;
old PID 16496 exited at 09:22:19.115213400 and new PID 20588 started at 09:22:19.652176400.
MGengine disconnected at 09:22:16.764 and rejoined at 09:22:54.913, at the original position.
This was a whole-game restart with the marker, not a same-JVM rejoin. The existing helper's
post-launch cleanup warning about the in-use `launch-javaagent-19.jar` remains; it did not
prevent restarting. No forced-kill timeout occurred in this update.

`cache_start_verify.json` run `5f217132-f1ed-4ddb-93eb-61c383e5eb10` **passed**. Exact signed
build words from CLIENT_READY reconstruct the published `.200` SHA above (without floating-
point conversion of 64-bit words). Its local checkpoint had epoch **0**, network bytes **0**,
cache hits **93,164**, active sections **92,656**, and GPU draws **222,603**. The screenshot
shows the original terrain/water view rendering before transport. After `resume_quic`, epoch
advanced to 1 and background validation preserved session 1 and GPU draws. The uploaded log
confirmed `transportHeld=false persisted=true`. These values prove cache-before-network drawing,
not full-detail completion: the local checkpoint still reported 21,542 missing coverage demands
and geometry was already essentially at its 2 GiB limit. Subsequent zoom warmups reached zero
missing coverage; do not claim the cache-start checkpoint itself was complete.

Candidate zoom aggregate `aggregate-c27584123f1f-1788686997.json` passed **both warmups and
all five measured cycles**, using the unchanged scenario and assertions. All five measured
final snapshots have coverageMissing=0 and the exact .200 build hash; no session reset occurred.
Each cycle lasted roughly 37–38 seconds. Final geometry allocations were 2.140–2.142 GB on
baseline and 2.140–2.142 GB on candidate, close to the unchanged physical limit.

Per-cycle deltas, measured runs only, in execution order:

| Counter | Baseline | Candidate |
| --- | --- | --- |
| Cache hits | 94, 55, 44, 71, 76 | 58, 52, 71, 42, 79 |
| Meshed sections | 103, 64, 51, 78, 84 | 65, 60, 79, 50, 86 |
| Section network bytes | 24,154; 40,046; 37,138; 36,626; 43,763 | 19,647; 37,308; 28,002; 41,626; 22,827 |

Worker telemetry windows inside those runs (approximately 36 seconds each) provide the
following completed counts and weighted stage wall averages. These exclude the uncompleted
endpoints, include waiting within stages, and are **not worker CPU or TTFD**:

| Stage | Baseline count / ms per completion | Candidate count / ms per completion |
| --- | ---: | ---: |
| Cache read | 339 / 0.850 | 301 / 0.731 |
| Decode/validate | 378 / 0.229 | 339 / 0.240 |
| Cache write | 39 / 8.742 | 38 / 5.491 |
| Mesh | 378 / 2.078 | 338 / 2.110 |

The warm view has little new work and remains capacity-bound; the terrain mix/publications
also differ between windows. These small samples show no obvious mesher processing regression,
not a throughput/TTFD improvement. `live-counter-deltas.jsonl`, `live-stage-deltas.jsonl` and
their task-only JS extractors preserve the calculations. Counters are subtracted within one
session only, not across the restart. No cumulative maxima are interpreted as per-run maxima.

Current-pose turn/return plus QUIC reconnect, run `0a368a6a-9498-49ae-9180-ce94fdc5b8ed`,
**passed**. The camera turns 180 degrees, returns, then reconnects Voxy without resetting the
renderer session. The existing GPU cap stays intact. Original position/rotation are restored.
The run ended in session 1, epoch 2. This is a bounded scenario result, not a guarantee about
the later server incident.

High-altitude route `c54f4839-3844-46bd-98ab-578eef234b7c` passed its draw/session assertions,
moving 4,096 blocks along X and returning. **It started in session 2 after the server-triggered
disconnect**, so it is not continuity across that incident. Cache hits grew 28,833 -> 76,734;
meshed sections 27,124 -> 74,532, with zero section-network bytes. Thus this confirms useful
cached work across the route, not healthy server streaming or measured shard reopens.
Coverage remained incomplete (21,542 at the final checkpoint).

Current-pose water observation `f5e1b224-cb5d-48ee-96e9-0626878d69f7` and explicit restoration
`64b0529f-c3e3-4d8f-86f0-1e65e1667668` passed. Final restoration confirms the original XYZ,
yaw/pitch, normal world FOV, zoom inactive and 222,103 GPU draws. Transport hold is disabled.

Visual inspection compared baseline `d29d27f0-...-11.png` with candidate held-cache
`5f217132-...-3.png`: both render the terrain, lakes and rivers from the same pose; existing
distant cut edges/islands remain. This is not a claim that all previous rendering issues were
fixed. The CPU tests, not screenshots, prove exact mesh/lighting bytes. F2 uploads are unchanged;
no periodic screenshot timer was enabled.

Live limitations: this profile was near the GPU cap, not a separate non-capacity-bound live
throughput profile. No ordinary cache was wiped or cloned into a new isolated Windows profile.
Consequently a controlled repeat using an identical cache snapshot, exact live shard-reopen
counts, a true cold-disk/cache-miss run, and new Windows corruption fixtures remain unverified.
No new shader-pack toggle/reload matrix or controlled waterlogged block-edit fixture was run;
existing offline shader/fluid suites pass and water is visible in the recorded current-pose
screenshots. Headless benchmarks provide the non-renderer-capacity-bound comparison. The server
continued publishing naturally; those changes were not frozen or attributed to this client patch.

## Server incident discovered during final validation

At **09:32:46.474 UTC**, the unchanged Creative server logs `OutOfMemoryError: Java heap space`
on `AeroNet io_uring-3-1`, allocating through Netty/PacketFixer's `Varint21FrameDecoder` hook.
The client reports that server-sent disconnect reason at 09:32:46.525 UTC. At 09:32:50.399 the
server also fails to load `world/generated/nma/structures/skynight.nbt` with the same heap OOM.
These stacks identify where allocations failed, **not the retained-heap root cause**.

The `Voxy Rust supervisor` then logs a `StackOverflowError` in recursive Log4j exception
handling. Source inspection shows `RustBackend.supervise()` catches IO/runtime exceptions
and interruption but not Errors; there is no exposed same-process supervisor restart command.
At the final host check, original Rust PID 1679938 was absent and Creative JVM PID 1678818 had
no child processes. `jcmd 1678818 GC.heap_info` reported used 2330M, capacity 3014M, maximum
4096M. It was a read-only post-incident sample, not an OOM-time heap profile or proof of a leak.

Minecraft automatically rejoined at 09:32:51.589 and started client session 2. This explains
the counter reset between individually passing scenarios. No counters were subtracted across
that reset. The test runner's absence-of-failure checks apply only inside each run, so they
did not catch the between-run server failure. The final log audit did; do not hide it behind
PASS statuses. Subsequent route/water checks are cache-rendering evidence only.

Evidence is retained as `server-after-oom.log`, `latest-after-oom.log`, `client-after-oom.log`
and `relaunched-after-oom.log` in the ignored task evidence directory. No server source/jar,
heap setting, safety limit, structure, world or cache was modified to mask the incident.
No unowned replacement Rust process was launched. Stress scenarios are finished; pose, zoom
and transport are restored. The .200 client remains installed; reverting these three local
client operations would not restore the dead server supervisor, and their causal involvement
in the OOM has not been established. Full online validation now requires a separate server
OOM/supervisor recovery investigation, outside this explicitly client-only implementation.

## Repository delivery

Local implementation commit exists. The safety reviewer rejected GitHub pushes twice despite
the standing push instruction and verification of the existing origin. Explicit approval of
GitHub destination `Aero-SMP/ASMP_Voxy`, branch `main`, was requested. No alternate remote or
indirect push was used. Push remains pending that approval.
