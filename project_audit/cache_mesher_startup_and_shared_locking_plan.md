# Improve meshing, cache startup and shared cache coordination

Status: implemented 2026-10-06 in client 261, source commit 7f798d60. Scoped real-PC verification finished within the ten-minute limit; broader correctness/performance claims remain unverified. See [implementation and results](cache_mesher_startup_and_shared_locking_implementation_261.md), [live receipt](cache_mesher_startup_and_shared_locking_live_261.json) and [size ledger](cache_mesher_startup_and_shared_locking_size_261.json).

Repository: /home/aerosmp/Desktop/ASMP_Voxy_Cache_First_Updates.
Branch: feature/cache-first-background-updates.
Audited baseline: aa89d593f99501f82e6c1899d22730374fe4196f, following debug client 260.

Implement the user's three selected groups in existing owners: conservative mesher
masks, removal of redundant startup work, and removal of shared locking/redundant
processing. Recovery scratch and unused directory accounting belong to both of the
last two groups; implement them once.

## 1. Scope and intended result

Reduce actual CPU work, allocation and cross-region waiting for cached and newly
downloaded terrain. Preserve the existing geometry and cache-first behavior.

- Keep the current client cache and wire representations, integrity checks, canonical
  names, cache-before-network activation rules, download importance, refresh cadence,
  bandwidth/storage controls and exact eviction ranking semantics.
- Keep model baking, renderer publication, VRAM selection and GPU fences intact.
  Add no runtime budgets, dependencies, executor pools, protocol versions, migrations,
  legacy readers, scalar-mesher fallback or unrestricted queues.
- Work on this feature branch. Preserve Main, ASMP_Voxy and ASMP_Voxy_Restart.
  The earlier branch instruction takes precedence over the repository's generic
  instruction to publish plans to main; publish this plan on the feature branch.
- Preserve the real PC's cache, settings, identities, resource packs, worlds and
  unrelated mods. Keep debug auto-updating and both independent PC backup SSH
  connections operational. Do not clear cache for this work.
- Verification is live-only on the real PC. No unit/integration suites, synthetic
  fixtures, fake clients, mutation-pressure runs or laptop testing. The 100-client
  verification remains suspended.
- The changes are client-side. Keep the deployed Java server and Rust backend
  untouched, including existing JVM arguments and the external native memory limit.
- Prefer existing files and readable code. Record source and binary deltas with the
  same counting scope before/after; there is no new line/file/resource ceiling.

Excluded: download/mesh pipeline separation, writer batching or handle retention,
compression outside writer ownership, a new download frontier, shared wire/cache
payloads, cached meshes, journal checkpoints and additional name-resolution batching.
Those are separate approaches, not part of this approval.

## 2. Evidence and limits

Existing evidence, not new controlled tests:

| Observation | Evidence and implication |
| --- | --- |
| 74,898 cache hits over 32.041 seconds in the held-transport 259 capture | [Receipt](deployment/cache259-warm-full-capture.json). Meshing used 179.123 summed worker-seconds, cache reading 51.444 and metadata 5.383. Meshing is the largest measured worker stage; durations overlap. |
| Heavy persistence after transport resumed | [Receipt](deployment/cache259-directory-commit-proof.json). 675.567 summed worker-seconds waiting for regional writers and 369.255 encoding/writing. Shared coordination is a credible interference mechanism, not proof of the user's Clumsy observation. |
| 260 initial metadata work | Uploaded-log inspection through 2026-10-05T20:40:33.217Z examined 328,307 recovery frames and approximately 310.6 MB of metadata-worker allocations during initial loading. Three per-frame read buffers and an unused accounting scan are confirmed allocation sources; their individual shares are unprofiled. |
| Cache loads before network arrival | The inspected 260 startup reached approximately 2,880 local activations before the first server HELLO at approximately 13 seconds. Initial delay in that capture cannot be blamed on incoming terrain. |

Do not infer a speedup percentage, raw disk latency or Voxy-only GC pauses from
these totals. The 259/260 captures differ in position, FOV, cache contents and
settings. They are context, not a matched before/after experiment.

The earlier priority buckets, BLAKE3 scratch, worker-owned voxel buffers, sparse
waterlogged depths, fused bounds and incremental local-directory commits are already
implemented. Do not implement or claim those fixes again.

## 3. Conservative face masks in the existing mesher

Primary file: src/main/java/me/cortex/voxy/client/core/rendering/building/SectionMesher.java.
Review ModelQueries.java and the current faceData(), cellIndex(), position() and
Workspace lifetimes. Keep faceData() as the authoritative face-generation rule.

### Preparation and mask layout

1. Extend the existing worker-owned Workspace with reusable 32-bit row masks for
   base face existence, occlusion eligibility and opposing-face occlusion. Populate
   them during the existing prepare() traversal, alongside current model metadata,
   bounds and overlay-depth handling. Do not add a second cell-preparation pass.
2. Reset all relevant masks on every mesh, including empty and failed work. Storage
   belongs to the existing workspace, not a section, demand or world-wide map.
3. Match the current cellIndex() axes exactly:

   | Face axis | Depth | Row v | Bit u |
   | --- | --- | --- | --- |
   | Y, faces 0/1 | y | z | x |
   | Z, faces 2/3 | z | y | x |
   | X, faces 4/5 | x | z | y |

4. Set existence bits only when modelId is nonzero and faceExists(metadata, face)
   is true. Air metadata is zero, but faceExists(0, face) returns true; metadata
   alone is therefore insufficient. Preserve the existing model-zero semantics.
5. For interior base planes, use the corresponding adjacent depth and opposite
   face to calculate:

       candidates = exists & ~(canBeOccluded & neighborOppositeOccludes)

   At depth 0 for negative faces and depth 31 for positive faces, use zero neighbor
   occlusion. Do not wrap into another section or assume neighboring section data.
6. Run unchanged faceData() on candidates. It still handles same-model culling,
   custom models, lighting, biome tint and boundary-water suppression. A mask may
   reject only a face the existing rule guarantees is absent.
7. Keep fluid-overlay face generation on its existing path and eligible depth
   masks. Base-model occlusion masks must not suppress an overlay whose fluid
   model has different face/occlusion properties. Feed actual nonzero overlay
   output into the same mask-aware merge traversal if that simplifies the code.

### Greedy merging without stale-plane dependence

1. Build the current plane's nonzero row masks from actual faceData() results.
   A surviving candidate may still produce zero. Values outside those row masks
   are not valid current-plane data.
2. Traverse faces and depths in the existing order, then increasing v and the
   lowest remaining u bit. Preserve quad ordering, bucket selection, bounds,
   MAX_QUAD_EDGE=16 and exact packed-data equality.
3. Width/height extension must require both current row-mask membership and equal
   packed face data. Stale plane[] values from a previous plane must never extend
   a quad or emit a face.
4. Clear emitted runs in every affected row mask. Do not depend on zeroing stale
   plane values. Avoid a replacement full-plane clearing/scanning pass that defeats
   the intended reduction in unnecessary work.
5. Handle bit 31 with nonzero tests and trailing-zero/unsigned operations; do not
   use positive-only mask tests. Keep run-mask expressions valid at the right edge
   and avoid Java's shift-by-32 behavior.
6. Retain one implementation. Remove superseded scalar fill/merge traversal when
   it has no caller; keep the existing fluid path only where it remains required.

For C cells, machine-word width w=32, candidate faces F and emitted quads Q, the
target work is approximately Theta(C + 6C/w + F + Q). Worst-case time remains
linear, with the existing fixed maximum quad dimensions. Reusable mask memory is
Theta(C) per actual workspace, not per cached or rendered section. Three sets of
six 32x32 integer rows add approximately 72 KiB per workspace before headers.
Report actual implementation storage instead of assuming this exact layout.

## 4. Remove redundant startup work

Primary files: CompletedSectionJournal.java, CompletedSectionCache.java and
ClientSession.java under src/main/java/me/cortex/voxy/client/lod/.

### Recovery scratch

1. In recover(), allocate reusable header, metadata and footer buffers plus CRC
   scratch once for that recovery. No global or shared concurrent read buffer.
2. Reset positions, limits and checksum state for every read. Preserve positional
   reads, little-endian interpretation, partial-read handling and interruption.
3. CRC must cover the actual metadata extent for PAYLOAD, BINDING or RESET, not
   the whole largest backing array. Preserve magic, lengths, canonical/compressed
   bounds, identities, reserved fields, footer commits, predecessor chains and
   payload references. Preserve stopped/truncated-tail behavior and write recovery.
4. Keep general read helpers for callers that legitimately need independent
   returned data; do not accidentally return scratch that escapes recovery.

Transient read-buffer allocation changes from Theta(H) to O(1) per recovery for
H historical frames. Recovery remains Theta(H); retained indexes remain
Theta(payloads + bindings). This optimizes necessary first recovery, not the
already-fixed repeated-recovery lifecycle.

### Foreground directory snapshots

1. Give foreground loadMetadata() a pinned snapshot path returning sections and
   incarnation without computing currentNamedBytes(). directorySnapshot() must
   likewise avoid accounting it discards.
2. Keep a single underlying pin/journal acquisition and capture the same incarnation
   as the copied bindings. Do not expose the journal's mutable map to the owner.
3. Keep named-byte accounting for WorldCacheDownloads callers that consume it.
   Audit every directory/inspection caller before narrowing the API.

This removes an unused Theta(N) binding scan and O(U) temporary deduplication
storage. The required N-entry snapshot copy remains Theta(N).

### Already-cached EMPTY publication

1. The cached publishEmpty() path must not create a persistence obligation for its
   already-stored binding. Make the source distinction explicit at task creation.
2. Preserve normal topology/geometry publication, revisions and cache activation.
3. Remotely received EMPTY and ABSENT records still persist through their current
   task path, with cancellation and current-revision checks. An absent/unavailable
   cached record must not become fabricated empty coverage.

The removed cost is writer acquisition and persistence-path overhead; the current
journal can already short-circuit identical bindings. Do not claim every redundant
EMPTY save currently rewrites a payload.

## 5. Short shared locks and incremental retention

Primary files: RegionalDiskBudget.java and CompletedSectionCache.java.
Review every physicalSpace(), usableSpace(), safety()/status(), pin, writer,
reservation, deletion, inventory-cleanup, ownership-record and recovery caller.

### Filesystem probes and close ownership

1. Separate pure synchronized policy/accounting decisions from potentially slow
   filesystem probes. No getUsableSpace(), file-store lookup or wait for a region
   belongs inside the shared budget or cache-facade monitor.
2. Keep mutation transactions under the existing changes lock. Preserve the lock
   order: acquire transaction ownership before entering the budget monitor where
   both are needed. Ordinary cache-read pins must not take the global mutation lock.
3. Capture the relevant pause/policy/mutation identity before probing, perform I/O
   outside the short monitor and revalidate before applying the result. A late
   observation must not clear a newer disk-full pause or authorize stale admission.
   Reuse existing generations where adequate; add only narrow event generations
   if necessary, not timers, guessed free space or a cached-space TTL.
   Paused recovery still requires ready inventory, valid policy/ownership, no active
   mutations, enough free space for requiredGrowth and the existing baseline-increase
   rule unless an explicit policy correction permits recovery. Establish an unknown
   paused baseline through an out-of-monitor probe when the final mutation finishes;
   until then refuse conservatively. Advance physicalRecovery and affected admission
   generations once for a successful paused-to-ready transition, not for every probe.
4. Preserve exact physical-byte charging, quota enforcement, pending-file ownership,
   ambiguous-ownership preservation, rejection/admission wakeups and disk-full
   recovery. An unavailable probe or actual write failure must remain conservative.
5. In CompletedSectionCache.acquire(), reserve operations++ under its monitor,
   then acquire budget.pin() outside it. On any failed/interrupted acquisition,
   release that reserved operation exactly once. Close retains budget ownership
   until accepted operations drain; pre-close operations may finish normally.
6. Audit nested calls, not just direct synchronized methods. A caller retaining the
   monitor defeats an apparently unlocked helper. Document the resulting ownership
   order and keep interruption/cancellation cleanup intact.
   Preserve deletion's nonblocking acquisition of regional writer ownership while
   holding changes; do not introduce a blocking reverse lock acquisition.

### Region ranking and pin eligibility

1. Parse coordinates once for an actual regional file when spatial ranking is
   needed; reuse a small immutable value in existing file/rank metadata and share
   it with an active Region where useful. Preserve accepted filename rules and
   ownership/path validation. Do not create permanent Region objects or writer
   locks for every inventoried file just to retain coordinates. Capture old
   coordinates before removing a Ranked entry; real index rebuilds may parse once.
2. Recalculate rank only when its inputs change. Update eviction eligibility on
   the first pin/last pin and corresponding writer/draining transitions, rather
   than rebuilding a Ranked/Rank object on every overlapping pin and release.
3. Preserve rejectedBeforeBusy, admission-generation notification and reconciliation
   when a formerly busy region becomes usable. Cached rank must not become a way
   to omit these wakeups or allow eviction while pins/writers remain.
4. At an unchanged x/z anchor, compute the old/new visible-region symmetric
   difference once and rekey only affected regional files in matching namespaces.
   Use a compact direct lookup over inventoried regional metadata by account,
   dimension and region key. Reorganize/share existing entries where practical;
   maintain it on creation, ownership assignment, recount and eviction. It describes
   actual files only and has their inventory lifetime, not an additional terrain
   cache. Do not scan all files per changed key or probe every namespace/key pair.
5. When the anchor moves, preserve the existing full distance rekey. Every exact
   distance can change; do not leave a TreeSet mixing old and new ranks, introduce
   distance quantization or add an arbitrary update interval.
6. In Entire world mode, omit eviction-only ranking/tree maintenance while retaining
   accurate bytes, ownership, anchors, pins and physical-space checks. Rebuild exact
   ordering before any finite-allowance admission or reconciliation after a switch.
   Use the existing allowance semantic, not an approximation of available world size.
7. A proven identical configure/bind can skip a full recount. Equal allowance alone
   is insufficient: new metadata/shared ownership, inventory transitions and an
   Entire world-to-finite switch still require correct byte attribution and index
   rebuilding before admission. Reuse the existing configure/recount transition.

With M matching namespace bookkeeping visits, V visible-region entries and Delta
actual affected regional files resolved by that direct lookup, unchanged-anchor
retention becomes approximately
Theta(M + V + Delta*log R), replacing a full Theta(R log R) rekey over R files.
Any compact inventory lookup remains Theta(R) in storage and needs incremental
maintenance; this is not a hidden namespace-by-region Cartesian scan.
Whole visible-set comparison/differencing is not O(1). Overlapping pins with no
eligibility change have an expected O(1) fast path; necessary tree transitions
remain O(log R). Moving-anchor ranking remains Theta(R log R).

## 6. Reuse validated UTF-8 spellings for cache encoding

Primary files: CatalogCodec.java and LocalSectionCodec.java.

1. Put lazily derived encoded spellings in the existing accepted Source/SharedNames
   ownership domain, indexed by block/biome ID. Update all current Source
   implementations; older Prefix definitions retain their original fixed bounds.
   Do not add a global spelling cache, per-section name copy or compatibility adapter.
2. Strictly validate UTF-8, nonempty spelling and the existing byte-length limits
   before publishing a privately immutable encoded value. Preserve surrogate-error
   rejection. Coordinate concurrent first use without exposing partial initialization.
3. Keep canonical equality, block flags and prefix validation unchanged. Derived
   byte[] identity must not participate accidentally in record equality/hashCode.
4. NamesInput uses the cached byte length and emits the existing two-byte length
   prefix followed by slices of the cached name. Remove repeated utf8Length scans,
   getBytes() and the concatenated prefix/name temporary array where superseded.
5. Preserve name order, palette/index bytes, exact encoded length and compression
   framing/settings, including the aggregate CatalogCodec.MAX_BYTES - 40L name
   extent and checked length arithmetic. Drop encoded-name references with their
   source/task owner. Report O(accepted names) indexed slot references separately
   from O(used name bytes) lazy encoded payloads; neither is per saved section.

For repeated use of a spelling, encoding/allocation becomes once per existing
source name instead of once per saved section. Necessary output copying remains
Theta(name bytes). Retained UTF-8 bytes increase source-owned memory; quantify that
cost alongside allocation savings. No malformed-name validation is removed.

## 7. Implementation order and preparation

1. Implement recovery scratch, narrower foreground snapshots and cached-EMPTY task
   creation. Review checks and caller ownership.
2. Implement short-lock acquisition/probes, rank/eligibility fast paths and exact
   incremental visibility retention. Audit failure/close and finite-mode transitions.
3. Implement source-owned UTF-8 reuse, verifying byte-for-byte representation and
   semantic equality by source reasoning. Keep all valid current data readable.
4. Implement mesher masks and mask-aware greedy traversal with the axis, boundary,
   fluid and stale-plane rules above. Do not retain an old implementation for fallback.
5. Conduct independent source review for ownership, skipped-face equivalence, lock
   order, races, asymptotic claims and all removed callers. Remove dead code.
6. Compile/package normal and debug client artifacts without invoking test tasks.
   Prepare updater publication, exact hashes and the previous client's rollback
   package before starting any live validation.

Use existing debug stage/allocation/activation counters. Add only small aggregated
hooks in the existing production no-op/debug facade if needed to distinguish mask
candidates, redundant saves or incremental rekeys. No per-cell/per-face logging,
permanent telemetry maps, synthetic harness or second scalar mesher. Remove
temporary diagnostic work before the final candidate is built.

## 8. Live verification: target five minutes, hard maximum ten

Keep the same testing restriction as the earlier cache plans. Preparation includes
source review, analysis of preserved logs and compilation without executing tests.
Every executed test/live validation action shares one continuous monotonic clock,
starting immediately before the first live preflight, including PC/SSH checks,
baseline observation, deployment, updater/restart waits, screenshots and restoration.
Do not reset the clock after a rebuild, failed check or retry.

| Aggregate elapsed | Activity |
| --- | --- |
| 0:00-0:45 | Confirm real PC target, actual loaded client/server identities, both backup connections and updater. Record pose, FOV, pixel/render/VRAM settings, bandwidth/storage values and cache identity. Brief existing-client baseline if useful; preserve the normal cache. |
| 0:45-2:00 | Publish the staged debug client and perform at most one necessary PC restart/update. Verify the actually loaded artifact; server/native remain unchanged. Use the existing scoped Voxy-only transport hold with expiry/restoration if available. |
| 2:00-3:15 | Observe first cached activation, loading milestones, stage work, allocation/hit and metadata recovery. Capture and inspect real terrain at the preserved view: opaque terrain, available custom blocks, boundaries and water. |
| 3:15-4:15 | Resume normal transport. Observe actual new cache commits and simultaneous local work where demand naturally exists. Check that remotely received EMPTY/ABSENT handling remains distinct. |
| 4:15-5:00 | Stop scenarios, restore task-owned controls and original view/settings, close captures and verify both backup helpers and normal client operation. |

If startup consumes the window, skip later scenarios. Extend only within the same
ten minutes when essential checks/restoration require it. Stop new scenarios by
minute seven or earlier if restoration needs more time; minute nine is restoration
and evidence closure only. At 600 seconds, stop validation. Mandatory unexpected
cleanup still must finish but is reported as an overrun, not another test allowance.

Do not modify Clumsy rules, teleport through server commands, clear/repopulate the
cache, fill the disk, fabricate corruption, impose a quota merely to provoke eviction,
run pressure clients or mutate terrain for this verification. A scoped Voxy-only
hold must not impair normal Minecraft traffic and must be restored inside the clock.

One update/restart plus an existing steady baseline is not a matched startup A/B.
Compare only compatible scene/content/settings intervals; otherwise report measured
candidate timings and structural/allocation evidence without a controlled speedup
claim. Different model-ID assignments across restarts make raw quad-byte hashes
insufficient for cross-launch equivalence. Source review plus screenshots do not
establish correctness for every modded block or rare race.

## 9. Acceptance and report

| Area | Required evidence |
| --- | --- |
| Mesher | Source equivalence of every early mask rejection; unchanged candidate face rules/order; current-plane mask membership in all merge reads; correct axis/sign/boundary/fluid handling; actual mesh timings/output counters and inspected real terrain. |
| Recovery | No per-frame read-buffer creation in ordinary recovery; all existing checks retained; actual old cached journals successfully recover/read where exercised. No claim that the entire metadata allocation delta belongs to these buffers. |
| Directory/EMPTY | Foreground omits unused accounting but downloader retains it; pinned incarnation and copied snapshot stay coherent; cached EMPTY has no save obligation; remote EMPTY/ABSENT persistence retained. |
| Locking | No slow space probe or blocking pin acquisition under shared monitors; close/failure ownership balanced; stale probes cannot undo a newer pause; actual allowance/physical-space preservation and notification logic reviewed. |
| Retention | No full-file rekey for visibility-only changes; no per-change full-file search; overlapping pins avoid reconstruction; exact ordering after movement/finite switch and necessary eligibility wakeups preserved. |
| UTF-8 | One source-owned spelling encoding; unchanged valid byte representation, palette/name order and equality; strict validation and prefix bounds retained; allocation and retained-byte costs reported separately. |
| Live boundaries | Exact loaded candidate, unchanged server/native, one recorded clock, original settings/cache preserved, controls restored, helpers operational, attempts/failures/skips retained. |

Record source lines/files/folders including untracked source under the same defined
scope, compiled artifact bytes and hashes, allocation per cache hit, stage means
and tails, activation milestones, source-owned UTF-8 bytes and mask workspace bytes.
Do not turn a short real-client check into a p99.5, whole-world, Clumsy-causality or
100-player claim. Rare disk-full, eviction and cancellation paths remain source-reviewed
unless naturally observed; report that limitation explicitly.

Save implementation and receipts under project_audit. Commit scoped changes and
reports on this feature branch. Separate implementation/build results, observed live
correctness, measured performance and checks deferred by the time limit. If required
live validation fails, restore the prepared client artifact and document the failure;
do not weaken validation or introduce a stale fallback to claim success.
