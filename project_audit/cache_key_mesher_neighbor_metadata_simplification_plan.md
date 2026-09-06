# Flatten cache keys, hoist mesher neighbor setup, and snapshot metadata lengths

Status: implementation plan only. Source reviewed on 2026-09-06 through `9fa528aa`.
No implementation, deployment, restart, commit or push is performed by writing this plan.

## Scope and coordination

Implement these three narrow client simplifications, preserving existing behavior:

1. Replace the nested cache key with one primitive-field record.
2. Determine neighbor direction and outside-boundary status once per meshing plane.
3. Observe metadata-file length once per opened channel and reuse it for validation.

Do not reopen the previous inventory/mesh-bounds/Anvil plan or count its benefits here.
The inspected commit retains `prepare()` and the separate `aabb()` scan in SectionMesher;
changing those is outside this plan. Before starting, inspect HEAD, the worktree and the
previous implementer's results. Preserve ongoing changes and use the resulting baseline,
not an older revision that lacks recently completed correctness fixes.

Implementation and required scoped debug deployment/testing use repository standing approval
when this plan is executed. Coordinate player-control scenarios with the designated test
player; do not move unrelated players. Tool permission controls still apply. Writing this
document is not authorization to start implementation or push the planning work.

No server algorithm, wire protocol, storage format, scheduling policy, lighting rule,
geometry budget or eviction policy changes. No production profiler, additional worker,
dependency, persistent index, cache, queue or telemetry stream. No production-data reset is
needed. Preserve worlds, identities, catalogs, configuration and normal client caches.
Use isolated task-owned fixtures/profiles for corruption and cold-cache tests.

## Evidence and expected result

The inspected live pairing was debug client `.197` and server `.196`. At 08:38 UTC the client
had approximately 913 seconds of cumulative cache-read worker time, 673 seconds of meshing,
113 seconds of cache writes and 58 seconds of decode/validation. These totals span workers
and include waiting; they are not CPU profiles. Geometry was about 99.9% of its allocation
limit, so the outstanding detail queue is not proof of a processing bottleneck alone.

| Change | Complexity score | Benefit estimate | Tradeoff estimate | Demonstrable reduction |
| --- | ---: | ---: | ---: | --- |
| Flatten cache keys | -2 | 4/10 | 0/10 | One retained key object instead of key plus fingerprint |
| Per-plane neighbor setup | -1 | 4/10 | 1/10 | Shared boundary/direction calculation instead of per-eligible-cell calculation |
| Metadata length snapshot | -1 | 3/10 | 1/10 | Three or two explicit size queries become one per valid read |

These are qualitative priorities, not measured speedups or zero-risk guarantees. Cache-index
memory remains O(R), meshing remains input/output-dependent, and metadata queries were already
O(1) per file. Never describe an entire cache scan or meshing operation as becoming O(1).

## 1. Flatten the private cache key

File: `src/main/java/me/cortex/voxy/client/lod/RegionalCache.java`.

Current representation is `CacheKey(Fingerprint fingerprint, int length)`. Shard replay creates
a Fingerprint and then a CacheKey, retaining both for each indexed entry.

- Replace it with `CacheKey(long low, long high, int length)`.
- During `Shard.open()`, read the two longs directly from the existing little-endian record
  buffer, followed by signed length. Preserve validation order and all signed-length checks.
- Keep the complete 128-bit fingerprint AND compressed length in identity. Same fingerprint
  with different lengths must remain distinct; neither half may be truncated or combined
  into a lossy surrogate key.
- Update normal lookup-key construction and append/tombstone encoding to use those fields.
  `RegionIndex.sectionFingerprint()` can still return its existing temporary value. Do not
  expand the protocol/index API or introduce mutable probe keys just to avoid that temporary.
- Keep the existing primitive-offset map, zero missing-offset sentinel, last-record-wins
  replay, tombstones, read-only semantics, synchronization, pins, leases and LRU behavior.
- Keep generated record equality/hash behavior unless an actual defect is demonstrated;
  do not add a custom hash table or hash implementation as an optimization.
- Delete newly unused imports only. No on-disk migration: record bytes must stay identical.

This guarantees a flatter retained key graph, not zero allocation for every lookup. Measure
allocation and retained memory separately; JVM escape analysis can change temporary counts.

### Regression tests

Extend production cache fixtures in `CacheStartupBehaviorTest`, including `shardReconstruction`,
`grownShardOffsets`, overlapping owners and trimmed-shard reopening. Its reflective CacheKey
constructor helper currently expects `(Fingerprint, int)` and must be updated without
weakening the tests or adding a compatibility constructor to production.

- Independently vary low half, high half and length; include zero, negative-bit-pattern longs,
  equal low halves, equal high halves and equal fingerprints with different valid lengths.
  Every lookup returns precisely the original bytes, or a miss for a different identity.
- Insert duplicates, remove/tombstone one identity, reinsert, close and reopen. Verify duplicate
  precedence, the unaffected different-length entry, payload offsets and missing sentinel.
- Exercise map growth/rehashing with deterministic generated keys and deliberate equal-hash
  fixtures where practical. Hash collisions must not become identity collisions.
- Verify append and tombstone headers byte-for-byte against independently encoded legacy
  fixtures. Reopen old cache files with the new code, and new files with the baseline reader
  in an isolated reference build. Do not depend only on a writer/reader sharing the same bug.
- Preserve malformed header/world/coordinates/reserved-field cases, truncated record headers,
  incomplete payloads, zero/MIN_VALUE/oversized signed lengths and exact-end records.
- Read-only opening must not repair or touch a file. Writable opening must repair only the
  existing incomplete suffix, and repeated reopening must not change the retained prefix.
- Preserve append failure rollback, quarantine behavior, pinned/busy eviction, multiple cache
  owners, closed handles, and reads after an externally truncated fixture.
- Run populated-cache startup/background validation and runtime-pressure tests: no duplicate
  download, lost cached entry, invalid offset, deadlock or changed accounting.

## 2. Hoist neighbor setup out of the cell loop

File: `src/main/java/me/cortex/voxy/client/core/rendering/building/SectionMesher.java`.

For one `fillPlane()` invocation, face and depth are fixed for all 1,024 plane cells. Existing
`neighbor(cell, face)` extracts X/Z/Y and switches direction for each eligible cell.

- Compute the signed stride once in `fillPlane()`: faces 0/1 use -/+1024, faces 2/3 use -/+32,
  and faces 4/5 use -/+1. Outside planes are depth 0 for even faces and 31 for odd faces.
- Pass one primitive neighbor delta to `faceData()`. Delta zero denotes an outside plane;
  valid interior strides are never zero. Document that local convention briefly.
- After the EXISTING fluid/model/face early exits, calculate the neighbor as
  `delta == 0 ? -1 : cell + delta`. Do not read a neighbor or do extra model work for air,
  model-zero or nonexistent faces that previously returned immediately.
- Remove the old per-cell neighbor helper once unused. No six-face lookup arrays, packed
  boundary descriptors, workspace fields, precomputed neighbor tables or parallel fast path.
- Preserve face/depth/u/v order, cell-index mapping, fluid overlay processing, greedy merging,
  quad order, buckets, offsets, bounds, metadata and native-buffer ownership exactly.
- In particular, preserve current boundary-water omission and boundary lighting, interior
  neighbor light (including lit air), same-model culling and translucent/fluid decisions.
  This is not a boundary-geometry or lighting fix.

There are 192 face/depth planes per pass. Only the shared setup is hoisted; neighbor addresses
and relevant face decisions still require per-cell work. The JVM may already optimize part
of the old calculation, so an isolated throughput gain is not assumed.

### Regression tests

Exercise production `SectionMesher.mesh()` using the existing controlled Models boundary in
`BoundaryWaterMesherBehaviorTest`; retain `MeshBoundsBehaviorTest` if available in the baseline.
Register additional cases in the executable scheduler test runner, not just an unused class.

- Exhaust all six faces, 32 depths and 32x32 plane positions (196,608 mappings). Check the
  production-used neighbor setup/address path against independent coordinate arithmetic.
  A standalone reimplementation of the proposed algorithm is not sufficient acceptance.
  Use a minimal test seam/reflection if needed; do not expose a public production testing API.
- Capture complete baseline mesh outputs BEFORE edits for deterministic fixtures. Compare
  every quad in original order, bucket offsets, geometry size, AABB, key, revision and child
  mask. Do not sort quads or compare only face counts; those would conceal ordering regressions.
- Cover each boundary/corner and just-inside plane, single cells, full solids, thin walls,
  checkerboards, seeded sparse mixtures, empty/air-only and non-air/no-face models.
- Include stone, glass, water, flowing water, lava, other fluids, tagged water, shared model
  IDs and waterlogged overlays. Cover both overlay/no-overlay passes, vertical water boundaries
  and horizontal sides; existing intentional waterfall-side omission must remain unchanged.
- Assign distinct skylight/block-light/biome values to each neighbor direction. Include air
  with nonzero light, zero-light air and solid neighbors. Verify encoded light/tint semantics
  independently as well as baseline equality; do not bless a new golden after a failure.
- Exercise all LODs, negative section coordinates, child masks and revisions. Loop workspace
  reuse through dense -> empty -> fluid -> sparse -> zero-output; run independent workers too.
- Preserve model failure paths and exact native-buffer cleanup; free every BuiltSection in
  a finally block. Retain shader/model readiness and geometry endpoint regression suites.

## 3. Capture metadata length once

File: `src/main/java/me/cortex/voxy/client/lod/RegionalMetadataStore.java`.

- In `read()`, immediately after opening the channel, store `long extent = channel.size()`.
  Use extent in the initial header/maximum checks and the exact header-plus-body check.
- Do the same in `referencedCatalog()` for the minimum prefix and exact declared-length check.
- Preserve long arithmetic, allocation limits, magic/version/kind checks, full reads, CRC
  verification, fixed-prefix catalog extraction and invalid-input return/exception behavior.
- Keep pins, channel closure, temporary-file writes, force and atomic replacement unchanged.
  Do not reuse a length across opens, pass directory observations into the reader, or add a
  persistent size field. The snapshot belongs to that specific open channel.
- The invariant is that Voxy installs immutable final metadata files via atomic replacement;
  verify this still holds at implementation time. If any writer now mutates final metadata
  in place, resolve that conflict before applying the optimization rather than deleting checks.
- Retain both inventory passes and their attribute consistency checks. This snapshot is not
  an atomic guarantee against external in-place corruption; concurrent external edits were
  not made safe by the old repeated size calls either. Truncated full reads must still fail.

### Regression tests

Extend `CacheStartupBehaviorTest.metadataIntegrityAndBudget()` and inventory/catalog-reference
tests against the real reader/writer, not a mock parser.

- Valid empty/minimum/maximum legal bodies, catalogs, regional descriptors, deletion descriptors
  and zero/nonzero catalog references. Fixed-prefix lookup must not eagerly decode full indexes.
- Short header/prefix/body, extra trailing bytes, inconsistent declared lengths, negative and
  over-limit lengths, wrong magic/version/kind, bad CRC and missing files. Require baseline
  rejection behavior and no oversized allocation before validation.
- Opening and reading old-format files remains compatible; successful writes remain byte-identical.
- Deterministically replace a metadata path with another valid file after opening: a read must
  produce a complete version or a safe platform-specific failure, never mixed accepted bytes.
  Use test-only instrumentation/latches if needed; no timing sleeps or production callback API.
- Truncate after the captured length but before body reading in an isolated fixture: preserve
  read failure/cleanup. Document that concurrent external extension is not promised to be
  detected after the snapshot; keep corruption checks at normal open time strict.
- Cover catalog pin/reference protection, failed atomic replacement, cancellation, close and
  inventory mutation between passes. Keep existing busy-file/platform behavior.
- On valid reads, instrument production call sites to prove `read()` size calls go 3 -> 1 and
  `referencedCatalog()` 2 -> 1. Counts for early-invalid files may differ; do not force them
  through unnecessary work just to fit the valid-file benchmark.

## 4. Repeatable performance and allocation checks

Establish a baseline after the preceding work settles. Preserve its source identity, dirty
diff if any, binary hashes and fixture hashes. Build references in isolated locations without
resetting the user's worktree. Measure each change separately before the combined candidate.

- Use the existing scheduler runner and `tools/simplification_bench.gradle` test-only agent
  pattern where useful. Extend narrowly for metadata-size calls; its current inventory-read
  counter does NOT already measure FileChannel.size(). No new production instrumentation.
- Cache: replay identical small/large shards with unique entries, duplicates, tombstones and
  reopen churn. Record allocated bytes per scan, retained key graph/heap size, scan time and
  lookup/insert throughput. Retained Fingerprint objects attributable to keys should disappear;
  unrelated fingerprints remain. Do not assert an unmeasured fixed byte saving per entry.
- Mesher: warmed-up repeated production calls on sparse, dense, checkerboard, fluid, invisible
  and all-air fixtures. Measure per-job allocation, CPU/wall time and throughput with identical
  workspace reuse. Exclude fixture construction and output-comparison work from timed regions.
- Metadata: identical descriptor sets, body distributions and filesystem state. Record explicit
  size queries, per-file time and inventory time separately. Fewer queries do not prove a
  proportional reduction in the previously observed 94-second inventory duration.
- Use ThreadMXBean allocated-byte measurements already supported by the test/debug code;
  report unsupported values as unavailable, never zero. Use JFR or allocation/heap profiling
  in an isolated JVM when available to distinguish temporary allocation from retained objects.
  Keep heap-dump/forced-GC profiling out of live frame-time comparisons.
- At minimum, use two warmup runs and five measured repetitions per configuration, alternating
  baseline/candidate order where practical. Report distributions and host/filesystem noise.
  Test warm filesystem cache and fresh-process reopening separately; do not call either a true
  cold-disk run without controlling OS caching. Do not drop host caches on the live server.
- Require output equality and the stated structural reduction. Investigate repeatable material
  regressions rather than hiding them with averages. If JIT makes the mesher speedup negligible,
  report that honestly; retain only if the final code is simpler and measured performance is
  not materially worse. Seek direction before silently omitting a requested change.

## 5. Offline gates and artifact checks

Run and record exact commands/results after each scoped change and for the combined build:

```text
./gradlew schedulerBehaviorTest
./gradlew workerDebugBehaviorTest debugHarnessJavaTest debugHarnessPythonTest
./gradlew buildAll verifyDebugHarnessArtifacts
git diff --check
```

Run optional test-agent measurements separately and record their flags/baseline expectations.
Source-string searches are not regression tests. All new behavior tests must execute through
the runner. Verify test/agent classes are absent from normal AND debug game artifacts, and
debug control classes remain absent from normal artifacts. Server/Rust code is unchanged;
building bundled server artifacts is not an instruction to deploy or restart the server.

## 6. Debug deployment and live verification

This is a client-only change: deploy the debug client using the existing updater/workflow.
Retain the current compatible server unless an independently required harness incompatibility
is established. State the intended client/server version pairing explicitly. Verify actual
loaded versions/build identities using runtime evidence and hashes/build manifests where
available; staged artifacts or updater publication alone do not prove a client is running it.
If exact identity cannot be verified, state that limitation and do not claim candidate results.

Available tools to reuse:

- `tools/run_live_client_test.py`: pose/rotation acknowledgement, traces, checkpoints, assertions,
  screenshots and real Ok Zoomer control. Inspect current schema before editing scenarios.
- Existing `harness_smoke`, `stable_view_loading`, `camera_turn_response`, `deterministic_sweep`,
  `quick_return`, `ok_zoomer_zoom_cycle` and `boundary_water_observation` scenarios.
- `cache_start_populate.json` / `cache_start_verify.json` with the procedure in
  `tools/scenarios/cache_start_README.md`: these require a WHOLE-GAME restart between phases.
- Uploaded `voxy-client-debug.log`, client/server latest logs and run artifacts. Existing worker
  telemetry includes stage totals/counts, CPU availability, allocation observations and
  `VOXY_WORKER_STALL` thread/lock-owner stacks. Inspect these before adding another profiler.

Example runner invocation, substituting a verified designated player and tmux console target:

```text
python3 tools/run_live_client_test.py --player <test-player> --console-target <verified-target> --scenario tools/scenarios/camera_turn_response.json --warmup 2 --repeat 5 --output <task-evidence-directory>
```

Validate safe terrain coordinates and restore the player's original pose/zoom at the end.
Existing water-observation scenarios use the current pose: choose a documented water/section
boundary view first. Screenshots are supporting evidence, not byte-level mesh equality tests.

### Live test matrix

1. Populated-cache whole-game restart: verify local cache hits and actual GPU draws before
   Voxy transport resumes, then unchanged-world validation without needless terrain teardown.
   Follow the transport-held marker procedure exactly; a rejoin in the same JVM is insufficient.
2. Stationary view, fixed turns, repeated return and zoom: compare cache/mesh completed-stage
   deltas, allocation, CPU/wall time, coverage and target-detail completion. Use matching world,
   camera/FOV/pixel threshold, shaders, render distance and memory settings. First draw is not TTFD.
3. A route spanning enough regions to reopen shards: ensure the run actually exercised reopen
   work using test evidence or profiling; repeat with the same populated cache snapshot in a
   designated profile. No deletion of the player's ordinary cache to force misses.
4. Waterlogged/transparent terrain and section-edge views across LODs: compare screenshots
   and failures, including side-water suppression and existing black-face/lighting expectations.
5. Near-capacity look-away/return and reconnect continuity: no session crash, corrupted cache,
   invalid geometry upload, leaked leases or stalled publication. Keep hard renderer limits.
   Run a separate non-capacity-bound view for throughput comparisons; do not blame a full GPU
   budget on cache/mesher speed or weaken quality/budgets to manufacture an improvement.
6. Optional cache-miss profile: measure without cached content using an isolated profile;
   confirm no obvious processing regression. Network variability makes this supporting evidence,
   not the primary performance comparison for these local optimizations.

Use counter DELTAS within one session; do not subtract across resets. Report cache/read/write/
mesh averages with completed counts, and stage maxima only with their observation interval.
Separate lock waiting from worker CPU, and GPU occupancy from GPU execution time. Investigate
new stalls through existing stack samples; remote JFR/full thread dumps require actual client
access and are not assumed to be available through the harness.

Keep scenario deadlines/assertions intact. Preserve failed runs. A missing/offline client is
blocked live verification, not a passed test. Restore Voxy transport after a held-transport
scenario, including failure cleanup; document interrupted-marker recovery per the existing README.

## 7. Acceptance, evidence and rollback

- All three scoped changes have behavior coverage and demonstrated key-object/query/setup
  reductions. Formats, geometry bytes, lighting, cache identity and lifetime rules are unchanged.
- No new persistent machinery, parallel implementation path or relaxed safety assertion.
- Offline tests/builds pass; live outcomes are reported separately and actual identities verified.
- Write `project_audit/cache_key_mesher_neighbor_metadata_simplification_results.md` with baseline
  and candidate identities, commands, fixtures, raw evidence locations, timings/allocation,
  failures, unsupported measurements and whether each expected benefit was measurable.
- Keep changes independently reviewable. If one fails correctness or materially regresses,
  isolate/revert only that task's implementation, preserving other work and its tests/evidence.
  Reinstall the verified prior debug client if live failure requires rollback. No cache wipe
  or server-data rebuild is necessary because formats never changed.
- Do not describe completion of this planning document as implementation, deployment or live
  success. Commit/push implementation only under the applicable execution authorization;
  do not push merely because this plan was written.
