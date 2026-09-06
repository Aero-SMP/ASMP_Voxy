# Simplify cache inventory, mesh bounds and Anvil array access

Status: implementation plan. Reviewed against `f321e54c` on 2026-09-06.
Implement all three changes below, preserving unrelated work and existing correctness fixes.

## Explicit user approval and scope

The user explicitly requested that this plan include approval for everything required to
complete it. Together with repository AGENTS.md, this authorizes the implementing agent to:

- Implement these three scoped changes and their regression/performance tests.
- Create isolated fixtures, scratch files and test artifacts; run builds, diagnostics and tests.
- Build and deploy the relevant debug client and bundled server artifacts through the existing
  deployment/updater workflow, including required scoped server/client restarts.
- Use the existing debug harness with the designated test player/client, including repeatable
  position, rotation and zoom scenarios, screenshots and collection of logs/resource telemetry.
  Coordinate disruptive tests with the test player; do not manipulate unrelated players.
- Verify actual running build identities, repeat failed tests after repairs, record evidence,
  and commit/push the completed plan and implementation to `main`.

No repeated user confirmation is required for these ordinary in-scope implementation steps.
Tool/sandbox permission controls still apply. This is not approval for unrelated changes,
disabling safety checks, or destructive operations against a broad directory.

These changes require NO format migration or production cache regeneration. Preserve source
Minecraft worlds, catalogs, identities, configuration, existing client caches and unrelated
files. Cold-cache tests use isolated temporary namespaces or a designated test profile; never
delete the player's normal cache to obtain a benchmark. Remove only validated task-owned
temporary data when cleaning up. Do not restart unrelated services or change modpack contents.

Writing/publishing this plan is not itself an instruction to start implementation in the
planning turn. The authorization above applies when the plan is executed.

## Goals and baseline evidence

Remove redundant filesystem metadata reads, one client cell traversal, and server array copies
without adding caches, indexes, worker machinery, formats or persistent state.

The inspected live pairing was debug client `.197` and debug server `.196`; the previous
geometry endpoint fix was client-only. At an inspected sample, worker elapsed averages were
approximately cache read 5.45 ms, mesh 3.77 ms, cache write 3.33 ms, decode/validation 0.32 ms
and decompression 0.06 ms. Cache inventory recorded 94.3 seconds. These include waits and are
not isolated CPU measurements or predictions of the savings from this plan.

| Change | Expected simplification | Scope of cost reduction |
| --- | --- | --- |
| One attribute read per inventory pass | Delete duplicate classification reads and an intermediate list | Explicit attribute reads roughly 4F to 2F for stable managed regular files |
| Bounds during mesh preparation | Delete the separate bounds scan and private preparation wrapper | One fewer V-cell traversal for meshes that emit geometry |
| Borrow four Anvil arrays | Delete four optional vector copies | Copy-specific O(A) time/storage to O(1) borrowed references |

Inventory remains O(F) before eviction sorting, meshing remains input/output-dependent, and
Anvil parsing still allocates decoded data and output cells. Do not claim whole operations
become O(1), zero allocation or universally faster.

## 1. Cache inventory: classify and use attributes once

File: `src/main/java/me/cortex/voxy/client/lod/RegionalDiskBudget.java`, `inventory()`.

Current: both walks filter through `inventoryFile()`, which calls `Files.readAttributes()`;
the consuming accounting/validation loop then reads the same attributes again.

- Retain BOTH directory passes and the existing observed-attributes/reference/count maps.
- First pass: iterate the walk directly, filter with the existing filename-only predicate,
  read attributes once, reject nonregular entries, and reuse those attributes for `observed`
  and byte accounting. Continue reading catalog references through the existing reader.
- Do not collect a first-pass `.toList()` before processing. Do not introduce a replacement
  candidate map, filesystem index or reusable global buffer.
- Second pass: use one local mutable candidates list. For each matching filename, read
  attributes once, skip nonregular entries, compare with the first-pass entry, and append
  the validated regular file to the existing eviction candidates list.
- Compare candidate count with observed count after that pass, BEFORE publishing accounting
  or performing cleanup. A new path, missing path or mismatching size/time/file key fails
  inventory; no partial observation may authorize writes or deletions.
- Delete `inventoryFile()` once unused and remove only newly unused imports/helpers.
- Preserve filename eligibility, link-following behavior, directory encounter order and
  stable equal-age ordering. Do not switch traversal API or change symlink policy.
- Attribute-read and walk failures must still fail inventory conservatively. Keep exception
  information useful; exact IOException versus its stream wrapper is not a compatibility API.
- Keep checks for cancellation/closure in both loops. Use try-with-resources so lazy iteration
  failures cannot leak directory handles. No budget monitor around walks or descriptor reads.
- Preserve CLEANING/READY transitions, reference protection, pins, busy-file callbacks,
  ownership locks, eviction accounting, under-budget no-sort behavior and sorting only by
  recorded age. Runtime pressure eviction is already optimized and is not part of this change.

This removes redundant explicit reads, not the attribute work performed internally by
`Files.walk`. Combining reads does not create an atomic snapshot against external mutation;
the two-pass consistency checks remain mandatory.

## 2. Mesh bounds: one cell-preparation pass

File: `src/main/java/me/cortex/voxy/client/core/rendering/building/SectionMesher.java`.

- Inline the private `prepare()` loop into `mesh()`, keeping workspace reset and model lookup
  behavior intact. Accumulate the six bounding extrema in local primitive variables during
  that same loop. Remove `prepare()` and the separate `aabb(long[])` traversal when unused.
- Use exactly the existing `CatalogMapper.isAir()` test. Every non-air cell contributes to
  bounds, including one whose model is zero or emits no face. Never derive bounds from quads.
- Preserve index-to-X/Y/Z mapping, exclusive maxima and the existing packed AABB bit layout.
  Pack once after confirming there is output geometry; retain the impossible empty-bounds
  validation before constructing a nonempty BuiltSection.
- Preserve the `total == 0` early return, key, source revision and child mask. Do not throw
  merely because an all-air input has no bounds.
- For air cells, continue clearing both workspace model-ID and metadata entries. Preserve
  fluid-overlay detection and all model lookup calls/order. Do not leave stale workspace data.
- Do not change plane traversal, greedy merging, face lighting, boundary-water policy, bucket
  ordering, output allocation/copying, exception cleanup or geometry ownership.
- No new per-job result object, workspace field, ThreadLocal, model cache or packed flag/AABB
  multiplexing. Local extrema and the existing local overlay boolean are sufficient.

Empty-output non-air models are an important tradeoff check: the old final bounds pass was
skipped for these, whereas fused extrema calculation runs during preparation. Measure that
case explicitly; do not add a second path to hide a regression. If a material regression is
reproducible, revisit or omit this individual optimization and document the evidence.

## 3. Anvil parsing: borrow existing numeric arrays

File: `rust-server/src/anvil.rs`, `parse_chunk()`.

- Replace `iter().copied().collect::<Vec<_>>()` on packed block data, packed biome data,
  block-light and skylight arrays with borrowed slices from the existing decoded NBT fields.
- `fastnbt::LongArray` already dereferences to `[i64]` and `ByteArray` to `[i8]`.
  `unpack_anvil_palette()` and `nibble()` already accept the corresponding optional slices.
  Adjust now-redundant `.as_deref()` calls; keep types/lifetimes explicit where helpful.
- Keep the owning decoded section alive while all slices are consumed. No unsafe conversion,
  returned borrow, lifetime extension, clone fallback, pool or additional dependency.
- Preserve every bounds/cardinality/index check, missing-array default and signed-byte nibble
  interpretation. In particular, absent skylight must not be treated as full sky exposure.
- Preserve the current validation order relative to registry mutation; do not change which
  errors can occur before/after registry updates under the guise of removing copies.
- Leave canonical block-name construction, biome-name handling, decoded index vectors,
  output cells, fingerprint algorithm and regional building unchanged. Those have separate
  ownership/validation purposes and are outside these four redundant copies.

## 4. Extensive regression tests

Run behavior against production methods. Establish baseline fixtures before edits; do not
write a second implementation and test only that. Register every new test with an executable
runner. Use deterministic synchronization, not sleeps intended to hit a race by chance.

### Cache inventory correctness and lifecycle

Extend `CacheInventoryBehaviorTest` and reuse existing cache/startup/pressure fixtures.

- Stable empty, small and large nested inventories: exact managed byte totals, catalog
  reference counts and READY state. Unrelated files and managed-looking directories excluded.
- Under budget, over budget, garbage-only recovery, orphan catalogs, referenced catalogs,
  pending writes and equal timestamps: preserve deletion choices and real encounter-order ties.
- Preserve pins, busy shard leases, multiple cache owners and readonly foreground reads.
  Block the existing descriptor-reader seam to prove foreground work progresses during scans.
- Mutate fixtures deterministically between observations: additions, removals, same-count
  replacement, size changes, timestamps and file identity where supported. Verify failure,
  no cleanup based on incomplete accounting, disabled writes and continued safe cache reads.
- Include a path changing regular-file status and symlinks with the current following policy
  on supported filesystems. No permission-dependent test may silently count as passed if the
  host user bypasses that permission restriction.
- Test failed attribute reads, truncated/unreadable descriptors, walk failures, cancellation,
  close during scanning, ownership-lock contention and close/reopen. All handles must close.
- Cover garbage bringing the cache below budget so sorting/pressure eviction stays unnecessary.
- Validate both passes independently: the first observed attributes must not be overwritten
  with second-pass values before comparison.

For query counts, use test-only filesystem tracing/instrumentation of the production calls
or an existing suitable test filesystem. Distinguish explicit reads from walker-internal
ones. Do not add a filesystem abstraction or permanent counter solely for this benchmark.

### Mesh output equivalence and workspace reuse

Reuse the model boundary in `BoundaryWaterMesherBehaviorTest`; exercise the actual mesher.

- Capture baseline complete mesh outputs for deterministic fixtures: key/revision/child mask,
  packed AABB, offsets, geometry byte length and every quad in exact order, not just counts.
- Empty/all-air, one cell at every corner, interior cells, narrow lines/planes, full solid
  sections and irregular sparse sections. Independently calculate expected extrema from the
  fixture coordinates, including exclusive maxima at 32.
- Include non-air invisible/model-zero cells beyond visible geometry, plus non-air inputs
  producing zero quads. Bounds must follow occupied cells while empty output stays valid.
- Opaque, translucent, water, lava, waterlogged overlays and shared models; preserve existing
  boundary-water and self/neighbour lighting tests. Include varied light and biome values.
- Cover every LOD, negative coordinates, child masks and source revisions. Geometry output
  must be byte-identical to the baseline for identical valid inputs/models.
- Repeatedly reuse one mesher/workspace across full -> empty -> sparse -> waterlogged ->
  no-face inputs. Also use concurrent workers with separate workspaces to detect state leaks.
- Exercise model-lookup exceptions and native-buffer cleanup with existing ownership fixtures.
  Free every test BuiltSection/native allocation even when an assertion fails.
- Keep focused independent AABB expectations alongside baseline fixtures, so regenerating
  golden outputs cannot accidentally bless a changed coordinate/bounds convention.

### Anvil decode equivalence and invalid-input handling

Extend production parser tests in `anvil.rs`; use serialized NBT fixtures and temporary
registries, not live registry mutation. Compare every decoded cell and source/terrain
fingerprint with pre-change outputs for the same input and starting registry snapshot.

- Missing/single/multiple block palettes, minimum-width packing, indices at word boundaries,
  valid high-cardinality block palettes, and single/multiple biome palettes.
- Missing versus present packed arrays, exact lengths, truncated/overlong arrays, out-of-range
  indices, empty/oversized palettes and existing single-palette acceptance semantics.
- Both lighting arrays absent; either present; both present; all-zero, full-light and mixed
  nibble patterns including negative `[i8]` values. Check even and odd cell positions.
- Light-only all-air sections without block-state data, absent biome data, nondefault biome
  mappings, partial chunks, negative coordinates/section Y and duplicate Y rejection.
- Invalid light lengths and malformed NBT must retain error behavior and current registry
  mutation ordering. Do not strengthen/weaken unrelated validation to make tests easier.
- Parse many sections and release the input after parsing; owned output cells remain valid.
  Repeat parsing with independent fixtures/registries to expose accidental shared scratch.
- Run level-zero row-copy tests and full/incremental regional equivalence tests, including
  the recently repaired lit-air round-trip/reuse case. Compare unchanged compressed payloads
  where generation/header differences do not apply.

## 5. Performance and allocation validation

- Compare isolated baseline and candidate builds against identical fixture snapshots, JVM/
  Rust settings and warmup. Keep baseline binaries/fixtures outside the production worktree;
  do not reset or overwrite user changes to build a reference version.
- Inventory: record file count, explicit metadata query counts, elapsed time and peak/allotted
  heap under warm and cold filesystem conditions where available. Separate directory walks,
  descriptor reads and eviction; a reduced query count does not prove total time halves.
- Mesher: measure repeated production `mesh()` calls for representative sparse/dense/fluid
  fixtures AND invisible/zero-output cases. Record throughput, per-job allocated bytes and
  output equality. Keep scratch reuse identical; exclude fixture construction from timing.
- Anvil: measure production parsing of identical saved chunks, allocated bytes/counts and
  peak memory. Separately report the four eliminated copies; output cells and NBT ownership
  remain. Both present light arrays remove 4 KiB of copying per section, plus packed arrays.
- Prefer existing allocation tools/JFR or temporary test instrumentation. Never install a new
  production profiler, dependency or telemetry thread as part of these simplifications.
- Use repeated runs and report distribution/noise and host contention. Preserve failures and
  negative results. Investigate any repeatable material regression; do not claim success from
  source line counts, syscall estimates or a single faster sample.

## 6. Builds, deployment and repeatable live validation

Run the existing entry points, extending their fixtures rather than adding parallel runners:

```text
cargo test --manifest-path rust-server/Cargo.toml
./gradlew schedulerBehaviorTest
./gradlew debugHarnessJavaTest
./gradlew debugHarnessPythonTest
./gradlew buildAll verifyDebugHarnessArtifacts
```

Also run `git diff --check`. Record exact commands, versions and results, including unavailable
optional test platforms. Pure-code/headless tests cannot prove live GPU behavior.

Deploy the debug client and bundled server under the approval above. No disk/wire format
change or cache/world reset is required. Use the existing graceful restart/update workflow.
Confirm actual running client and server version/build identities from runtime evidence and
artifact hashes, not just files staged in build/libs or updater listings. Record the intended
pairing explicitly; do not evaluate an older client's behavior as the new implementation.

Using the existing debug harness and designated test client, repeat the same scenarios:

1. Join with the existing populated cache; capture inventory duration, foreground progress,
   cache/read/write/mesh timings, allocation, failures and first-detail timings.
2. Repeat stationary views and fixed position/rotation/zoom sweeps over identical terrain.
   Compare screenshots, coverage, publication completion and geometry occupancy near capacity.
3. Disconnect/rejoin without deleting cache: verify cached reuse and normal background
   reconciliation. Exercise an isolated test-cache scenario separately if needed.
4. Observe server import/incremental activity on the same saved source snapshot used for the
   reference where feasible; record throughput/CPU/RSS, output equivalence and reused sections.
   Do not edit the live world merely to generate benchmark updates; fixtures cover that case.

Collect client/server logs, existing telemetry, screenshots and scenario results. Distinguish
main-thread pauses, lock waits, worker CPU and GPU occupancy; do not infer GPU timing from
geometry bytes. No per-frame/per-cell log spam. Report an unavailable client as blocked live
verification rather than claiming a deployment-only test passed.

## Completion and handoff

- All three changes are implemented or a specific measured reason for excluding one is
  documented; no silent omission. No new persistent machinery or unrelated rewrites.
- Correctness and ownership tests pass; outputs/formats remain unchanged and old fixes hold.
- Query/copy/pass reductions are demonstrated with honest timing/allocation measurements.
- Required debug artifacts are deployed and actual live identities verified; live outcomes
  are reported separately from unit/build results.
- Write a results document and evidence locations beside this plan. Commit/push only scoped
  work to `main`, including the plan/results, under the explicit approval above.
