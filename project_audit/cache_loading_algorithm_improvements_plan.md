# Improve cache loading algorithms and remove repeated work

Status: implemented in debug client 259; build complete, scoped live verification pending. Written 2026-10-05.

Implementation and evidence: `cache_loading_algorithm_improvements_implementation_259.md`.

Repository: `/home/aerosmp/Desktop/ASMP_Voxy_Cache_First_Updates`.
Branch: `feature/cache-first-background-updates`.
Audited starting commit: `808b373c` (debug client 258).

Implement the approved changes below in existing owners. Target five minutes of
testing; **all testing combined has an absolute ten-minute limit**.

## 1. Approval and scope

| Item | Status |
| --- | --- |
| Indexed regional metadata queues and constant-time priority accounting | Approved, including changed ordering among equally important regions. |
| Reusable BLAKE3 compression scratch and safe owned hash-state reuse | Approved. |
| Fused mesher bounding-box calculation and sparse fluid-overlay planes | Approved. |
| Remove redundant first-use Boolean arrays in the local codec | Approved; retain equivalent scalar validation. |
| Build used-model IDs from block names instead of palette entries | Approved after the P-versus-B explanation in section 5. |
| Skip probes for regions known absent in the completed inventory | Approved. |
| Incremental local-directory updates after background commits | Approved. |
| Preserve existing nearest-first root order through the top mailbox | Included under the user's same-or-better-work condition; no additional spatial sort or scheduler. See section 8 and the live throughput check. |

Keep local/wire bytes, integrity rules, canonical name/model resolution, cache-first
display and network-after-cache activation gating unchanged. Add no migration,
format version, compatibility path, dependency, executor or runtime budget.
Preserve bandwidth/storage controls, eviction, refresh cadence, GPU-derived
refinement priority, VRAM selection and fence-safe geometry publication.

Work on the feature branch. Original `ASMP_Voxy`, `ASMP_Voxy_Restart` and Main
remain read only. Preserve the real PC's existing cache, settings, worlds,
identities and unrelated mods. Keep automatic debug updating and both independent
PC backup SSH connections working. No laptop, unit/integration tests, synthetic
pressure run or 100-client verification. Do not delete cache for this work.

Prefer existing source files and readable code. Measure source lines/files/folders
and artifact bytes; do not introduce a new size ceiling or compress source to
hide complexity. Deferred candidates such as byte-name caching, retained palette
scratch, persisted meshes and a new bitmask mesher are outside this plan.

## 2. Evidence and limits

Preserved evidence: [build 258 real-PC log](live_client/cache258-capture/voxy-client-debug.log),
snapshot lines 301 through 52034, and
[allocation receipt](deployment/cache258-warm-full-capture.json).

| Observation | Value |
| --- | --- |
| Successful cache decodes | 50,906 over 37.0853473 sampled seconds |
| MESH | 50,875 operations; 127.462 accumulated worker-seconds; mean 2.505 ms |
| CACHE_READ | 51,091 operations; 52.295 accumulated worker-seconds; mean 1.024 ms |
| Worker allocation per successful cache decode | Approximately 46,681 bytes |
| Completed metadata operations at the last snapshot | 13,513 |
| Journal recoveries at the last snapshot | 620 |

Worker stage times overlap. CACHE_READ includes I/O, integrity, decompression,
name resolution and expansion. Neither hash allocation share nor individual
mesher passes are separately measured. Region-selection work runs on the session
owner and is not measured by the metadata-worker stage. The gap between metadata
operations and journal recoveries is not an exact count of absent regions.

For R simultaneously queued regions, the current selection scan performs
R(R+1)/2 visits when draining them. For 13,273 regions this would be 88,092,901
visits, a source-derived scenario rather than a measured counter.

## 3. Indexed regional queues and priority accounting

Primary file: `src/main/java/me/cortex/voxy/client/lod/SectionDemandTable.java`.
Review its sole metadata selection caller in `ClientSession.loadLocalMetadata()`.

1. Replace the scan over `readyRegions` in `pollRegion()` with FIFO groups for
   coverage/noncoverage and the existing pixel buckets. Coverage remains first,
   then the highest bucket. Use intrusive regional membership and direct unlink
   on cancellation/reclassification; do not accumulate obsolete queue entries.
2. Track per-region member counts for each existing bucket and its occupied-bucket
   mask. Update on adopt, removal and priority change. Compute highest priority
   from the mask instead of scanning `region.members`. Preserve count-underflow
   checks and the empty-region lifecycle.
3. The production bucket count is 32. Use that existing domain, with correct bit-31
   handling. These buckets are priorities, not a new runtime work quota. A fixed
   bucket scan is also O(1) relative to region/section count if simpler than masks.
4. Reclassify a queued region when coverage membership or highest bucket changes.
   Unlink once and append to its new FIFO. Repeated admission into the same class
   must not duplicate or unnecessarily move it. Maintain an explicit ready count.
5. Remove the unused general eligibility scan: the sole production predicate is
   `!localTried`, already enforced on admission. Dequeue before marking tried;
   invalidation/commit paths explicitly readmit when appropriate.
6. Audit retirement, session/world reset, pending metadata work and close. Preserve
   ticket ownership and keep actual filesystem/codec work off the session owner.

With a fixed priority domain, regional admission, removal, reprioritization and
selection become O(1). Draining R ready regions becomes Theta(R), not Theta(R^2).
Removing/reprioritizing M members also becomes Theta(M) total instead of the
quadratic repeated maximum scans. Memory remains Theta(R + M), with approximately
128 raw histogram bytes plus bookkeeping per region. Exact historical tie order
after reclassification is deliberately dropped, as approved.

## 4. Reusable BLAKE3 scratch with unchanged integrity

Primary files: `Blake3.java`, `CompletedSectionJournal.java`, `LocalSectionCodec.java`
and, only where ownership plumbing requires it, `CompletedSectionCache.java`.

1. Replace per-block message/state/chaining/output allocations with private mutable
   scratch. Keep tree chaining values in reusable primitive storage. Keep one
   implementation and the existing public hash behavior; add explicit reset for
   owners that safely reuse a Hasher.
2. Preserve byte order, seven rounds, flags, partial-block zero padding, empty
   input and exact digest bytes. Preserve the deferred full-1024-byte final chunk:
   push it only when additional input proves it is not the final chunk. Preserve
   CHUNK_END/ROOT handling at exact block/chunk boundaries and parent reductions.
3. Reset lengths, counters, stack depth and finalization; overwrite/clear scratch
   before reading it. Update/digest after finalization must still fail until an
   explicit reset. No caller may reset someone else's in-progress hash.
4. Cache reading may use the existing codec/worker's owned hasher for the complete
   read/decode/integrity operation. Acquire/reset before the first byte; publish
   decoded output only after both existing CRC and content hash checks succeed.
5. Append hash state spans its entire lifetime, including suspended `step()` calls.
   If borrowing codec-owned write state, bind it to the full Encoder lifetime,
   with Append closed before Encoder release. Never reset between encoding steps.
   Distinct read/write workspaces are acceptable where lifetimes differ.
6. Compaction and ancillary one-shot callers keep independently owned state. Do
   not introduce a journal-shared/static hasher or global pool. Where borrowing
   would require broad plumbing, retain an operation-private Hasher whose reused
   internals still remove per-block allocation. Review cancellation/failure cleanup.

Hash CPU remains Theta(S) for S input bytes; live tree scratch remains O(log S).
The gain is eliminating Theta(S) cumulative temporary allocation during hashing,
with essentially no new per-block arrays after owned workspace initialization.
Do not describe hashing itself, or its asymptotic tree storage, as O(1).

## 5. Local-codec validation and model-list simplification

Primary file: `LocalSectionCodec.java`, current decode lines 139-181.

Approved: remove `seenBlocks` and `seenBiomes`. Keep the earlier rejection of an
index greater than `nextBlock`/`nextBiome`; advance each scalar when the index
equals that scalar. Inductively, every smaller index has already appeared. Keep
duplicate palette identities, table/index bounds, first-use ordering, every-name
used, trailing-data and all other checks. This removes redundant Boolean storage,
not ordering validation.

Approved: construct used-model IDs once from `blockIds`, sizing the deduplication
set by the block table rather than the complete palette. Exclude air/ID zero and
deduplicate translated aliases. Preserve first-use ordering and the lifetime of
the independent returned used-model array through waiting/meshing.

P counts palette entries: each includes block state, biome and lighting. B counts
distinct canonical block states, including their properties. One stone state at
16 different light values can have P=16 but B=1. The current model-list operation
then reserves space for 16 entries and attempts 16 insertions of one model; the
proposed operation reserves/inserts for one block name. B is always <= P. If B=P,
there is no asymptotic improvement. When blocks repeat across light/biome variants,
used-model discovery and set capacity change from Theta(P) to Theta(B). The actual
P-entry palette and linear voxel decode remain necessary and unchanged.

## 6. Remove redundant mesher scans

Primary file: `src/main/java/me/cortex/voxy/client/core/rendering/building/SectionMesher.java`.

### Bounding box

Calculate min/max during the existing preparation pass for exactly the same
nonair input cells as the current `aabb()` pass. Keep inclusive minimum/exclusive
maximum and the packed bounds expression. Coordinates are x=index&31,
z=(index>>>5)&31, y=index>>>10. Do not substitute model visibility or opaque-only
bounds. Preserve empty geometry handling and the invariant failure if geometry
exists for an all-air section. Remove the separate full-cell pass.

### Fluid overlays

Replace the whole-section overlay Boolean with three reused depth masks. Reset
them on every preparation, including empty sections. Set bits only for the exact
current predicate `containsFluid(metadata) && !isFluid(metadata)`.

| `face >>> 1` | Depth mask coordinate | Faces |
| --- | --- | --- |
| 0 | Y | 0, 1 |
| 1 | Z | 2, 3 |
| 2 | X | 4, 5 |

Keep every base-layer plane pass. Run an overlay only when its depth bit is set;
test `!= 0`, not `> 0`, because depth 31 uses the sign bit. An unmarked plane has
no eligible own cells, so its current fluid-layer output is all zero. Fluid
neighbors alone do not require that plane. Keep ordinary fluid models in the
base path and leave `faceData`, custom face metadata, lighting, tinting, culling
and section-boundary water suppression unchanged.

One eligible voxel needs six overlay planes instead of 192. Worst-case mesh
complexity remains Theta(C + Q) for C cells and Q output quads. The bounding-box
fusion removes one C-cell pass without extra per-section allocation.

## 7. Known-absent inventory lookup and incremental directories

Primary files: `RegionalDiskBudget.java`, `CompletedSectionCache.java`,
`SectionDemandTable.java`, `ClientSession.java`; review existing incremental
`WorldCacheDownloads.Coverage` and retained-directory ownership.

### Known-absent regions

Expose a small presence query through the existing cache/budget owner. Consult
the existing READY inventory under its synchronization; return unknown during
incomplete/failed inventory or ambiguous mutation/creation state. Do not make a
new whole-world negative cache. Do not mistake an in-flight/newly created journal
or stale inventory entry for a definitive absence.

For a definitively absent region, install an empty local view through the normal
current-region/view lifecycle, without metadata-worker dispatch or filesystem
calls. Unknown and present regions keep normal inspection. Preserve existing
cache-probed gating before network requests; a concurrent later commit must patch
or invalidate that empty view through the next subsection. Keep journal readers,
creation, resize, compaction, reset and eviction coherent with the inventory.

This replaces repeated worker/stat overhead with an expected O(1) existing-map
lookup. It does not remove the initial inventory scan or prove constant-time I/O.

### Background commits

The current `WorkerCached` completion marks an active region untried/unloaded and
queues a complete directory read after every committed section. Replace this with
one committed-binding update, sharing the existing foreground `recordCommitted`
pattern without double-counting download completion.

1. Patch only the current active region's owner-thread local view. Distinguish
   DATA, EMPTY and ABSENT bindings; never turn missing/unavailable data into a
   fabricated covering section. A released region requires no permanent overlay.
2. If initial directory inspection is outstanding, coalesce committed bindings
   by section key until that one snapshot arrives. Merge the current overlay over
   the snapshot; an old asynchronous result must not overwrite newer commits.
   Keep region identity, view/world and invalidation epoch checks. Drop overlay
   ownership on cancellation, retirement or invalidation.
3. Re-evaluate only affected demands/branches and the fixed ancestor path. A fixed
   number of calls to `bindAvailable()` is insufficient: `collectCachedCut()` can
   recursively scan descendants on every still-incomplete attempt. Initialize
   active-region coverage/completeness summaries once from the snapshot; patch
   direct availability and child-completeness bits along the changed ancestor path.
   Consult these before incomplete-cut attempts. Enumerate/register a real cut
   only on a newly complete transition, preserving the current eight-child rule
   and DATA/EMPTY/ABSENT semantics. Account for necessary emitted dependencies
   separately; no constant-time claim may hide their traversal.
   If a newly cached descendant supplies partial coverage beneath a missing
   ancestor, seed/bind that branch using the same cache-cut/activation rules. Do
   not scan unrelated members or prematurely discard active terrain. Clear or
   patch summaries with eviction, invalidation and payload quarantine; do not
   create a permanent world-wide summary or confuse renderable cached coverage
   with the downloader's different all-LOD-quality completeness predicate.
4. Reuse current dependency/coalescing mechanisms. Review initial-snapshot ordering,
   cache corruption/fallback, region reset, eviction, world/dimension replacement,
   compaction and reconnect. Real invalidation may legitimately rebuild a snapshot;
   ordinary successful binding commits must not trigger one.
5. Preserve the existing active-directory/job retention reasons and their release.
   Do not retain journal offsets or files beyond replacement, pin files between
   jobs, or change physical disk accounting and storage estimates.

For N initial bindings and K ordinary committed updates during one active region
lifetime, binding-map and completeness maintenance becomes Theta(N + K), excluding
actual invalidation and payload work. Each binding/completeness update is expected
O(1) with fixed hierarchy depth. Materializing A necessary cut dependencies adds
Theta(A) output work; do not claim that step is O(1), or repeatedly perform it for
an incomplete cut. This complements build 258's recovered-index retention;
index reuse alone did not remove directory copying/reprocessing.

## 8. Preserve nearby-first startup without another spatial scheduler

`RenderDistanceTracker.Planner.compare()` already orders entering LOD4 roots by
squared distance from its 512-block window center. `ClientSession.TOP_LEVEL` and
`topSnapshot()` preserve insertion order, and session construction feeds that
snapshot into `offerTop()`. The top mailbox in `SectionDemandTable` currently uses
a HashMap and drains in hash order, discarding the spatial order before regional
metadata admission. This is a confirmed ordering defect, not visual proof that it
is the sole cause of the reported strips.

Make only the top coalescing mailbox insertion ordered, including both creation
and replacement in `take()`. Keep latest-value enter/leave overwrites. Avoid changing
the unrelated GPU-detail mailbox unless sharing the same simple implementation
is demonstrably preferable without changing epoch behavior. The approved regional
FIFO buckets then inherit the producer's nearby-first admission order.

Offers/handoff remain expected O(1), drain remains Theta(changed identities), and
memory remains Theta(changed identities) with a small link overhead. No additional
distance calculation, heap, full-world sort, ring traversal or per-frame reordering
is added. Keep existing regional rotation, coarse coverage priority, pixel priority
and worker/model/fence concurrency. Thus this change adds no new traversal or
decode work and retains worker parallelism/locality. Runtime throughput still
requires observation; unchanged Big-O alone cannot guarantee unchanged speed.

This improves root-column startup and newly entering terrain, not exact concentric
activation at every LOD. Hash-ordered fallback seeding inside a region, regional
fairness, partial caches, model waits and asynchronous completions can still make
the visual result interleaved. Do not remove fairness or sort all cache bindings
to force a perfect circle. Do not continuously resort old queued roots when the
player moves. If the scoped live comparison reveals a repeatable throughput
regression attributable to this mailbox change, omit/revert it independently.

## 9. Implementation and preparation

Implement queue/accounting plus ordered top admission first, hash scratch second,
approved codec validation simplification third, mesher passes fourth, then the
inventory shortcut and incremental-directory lifecycle together. Include the
approved block-name-based model list with the codec simplification.

Review ownership/invalidation and hash boundary semantics before deployment.
Build normal/debug client artifacts without executing test tasks. Client-only
changes require no Java-server/native rebuild or restart. Keep Testing's existing
JVM arguments and external native ceiling unchanged; recorded values are
`-Xms1G -Xmx4G`, `memory.max=999997440`, `memory.swap.max=0`.

Prefer existing debug telemetry. If needed, add only aggregate queue selection,
directory snapshot/patch/absence-skip and overlay-plane counters through the
existing no-op/debug facade. No per-cell logging, permanent telemetry maps or
diagnostic sorting. Record a bounded initial root/region admission trace only if
existing logs cannot establish ordering, with capture-only diagnostic ownership.

Prepare artifact hashes, rollback build and one scripted live clock before
accessing the PC. Preserve the normal cache: existing build-258 records provide
independent stored hashes for checking the new reader. Source review is not a
runtime test; compilation is not proof of hash/mesh correctness.

## 10. One testing clock: target five minutes, hard ten

All executed tests and live checks share one non-resettable monotonic clock,
starting immediately before the first live preflight. Include backup SSH checks,
baseline collection, deployment, updater/restart waits, transport controls,
screenshots and restoration. Builds and read-only analysis of preserved logs are
preparation. No unit/integration suites or synthetic fixtures/self-tests.

| Aggregate elapsed target | Work |
| --- | --- |
| 0:00-1:30 | Verify real PC, both backup routes, loaded identity, current view/settings and cache. Collect a brief baseline only if an actual comparable startup fits the same clock. Deploy/update only the client. |
| 1:30-2:45 | Warm-cache startup with the existing scoped Voxy-only transport hold, if safely available. Record first activation, LOD/count milestones, allocation/hit, stage timings, owner/worker availability and nearby-first admission. Inspect a screenshot. |
| 2:45-3:45 | Restore transport. Observe actual DATA verification and background commits, directory patch/snapshot counts and one naturally available read of newly committed data. Inspect accessible modded/waterlogged terrain if the scene provides it. |
| 3:45-5:00 | Stop new scenarios; restore task-owned transport/view/settings controls, verify ordinary rendering and both routes, close evidence and record elapsed time. |

If necessary, use only the remainder of the same ten-minute allowance. Stop new
scenarios by minute seven; reserve minute nine for restoration/closure only.
**At 600 seconds, stop testing: no extra screenshot, retry or verification.**
Use automatically expiring holds where possible. Unexpected mandatory safety
restoration after an overrun is restoration only, must be reported as a missed
limit, and cannot authorize more tests.

Do not restart twice, switch dimensions, mutate worlds, synthesize corrupt cache
records, alter storage allowances or exhaust disk merely to obtain coverage. Skip
checks that cannot fit. If no relevant fluid scene/serial commits/comparable
baseline occurs, record those checks unproven rather than extending the window.

## 11. Acceptance and reporting

- Queues: no regional/member scan in ordinary selection/priority updates, no stale
  duplicate entries, unchanged coverage/pixel policy, approved FIFO tie behavior.
- Hashes: existing cached records with stored pre-change fingerprints still read
  successfully; actual Rust-produced DATA checks succeed where observed. New
  append/read paths work where exercised. Source audit covers exact empty/block/
  chunk boundaries; do not claim those lengths were live-tested unless observed.
- Meshing: inspected scenes show no observed geometry/water/custom-block regression;
  base face rules and bounds invariant remain intact. Screenshots are scoped
  checks, not proof of perfect correctness for every block or race.
- Directory lifecycle: ordinary serial background commits patch the same active
  view without one complete snapshot per commit; initial snapshot cannot clobber
  newer bindings. Existing active-index retention/release remains intact.
- Presence shortcut: count definitive absence skips; unknown/present fallback
  remains correct. A missing local file does not bypass cache-before-network rules.
- Ordering: producer order reaches equal-priority regional metadata admission.
  Retain parallelism, fairness and coarse/pixel priority. Compare matched activation
  milestones/throughput if feasible; omit spatial change on attributable slowdown.
- Performance: report allocation per hit and stage/activation measurements with
  position, settings, cache keys and loaded artifact identity. Historical 258
  alone is not a controlled speed/FPS comparison. Faster work can increase raw
  MB/s while decreasing bytes/hit; worker figures are not whole-mod allocation.
- Boundaries: client debug artifact actually loaded, server/backend untouched,
  controls restored, backups alive, one recorded clock and all skipped checks.

Save implementation/evidence under `project_audit`, with hashes, source/artifact
size deltas, exact attempted/skipped checks, failures and timing. Commit locally
on the feature branch. Report implementation/build status separately from measured
speed and live proof. Do not claim exhaustive correctness or 100-player results.
