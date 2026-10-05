# Reuse cache-worker buffers and active region journal indexes

Status: implemented on 2026-10-05, commit `4512f56f`; debug client 258 deployed
to the real PC. See [implementation and live evidence](cache_worker_buffers_and_journal_index_reuse_implementation.md).
The live window lasted 465.744807 seconds, within the hard ten-minute limit.

Repository: `/home/aerosmp/Desktop/ASMP_Voxy_Cache_First_Updates`.
Branch: `feature/cache-first-background-updates`.
Audited starting commit: `7aa10d3b1b98d093346906d51d0cb6aac8e608cf`.

Implement both changes below in existing owners. Prefer a five-minute live
verification window; **all testing combined has a hard ten-minute limit**.

## 1. Evidence and intended result

The preserved build 257 [warm-cache capture](live_client/repair257-capture/voxy-client-debug.log)
activated 8,678 cached sections over approximately 10.02 sampled seconds with
Voxy transport held and zero received network bytes. It recorded 8,737 successful
cache decodes and approximately 2.80 GB allocated across the observed workers.

| Observation | Measured value | Interpretation |
| --- | --- | --- |
| Cache inventory | 63.12 ms | Not the dominant startup cost in this sample. |
| CACHE_READ | 17.70 summed worker-seconds | Includes reading, integrity checks, decoding, name resolution and voxel expansion; not a measurement of disk I/O alone. |
| MESH | 24.63 summed worker-seconds | Largest measured worker stage; mesher changes are outside this focused plan. |
| Fresh voxel arrays | 2,290,352,128 bytes implied by successful decodes | Approximately 82% of measured worker allocation, before array headers. |
| Two name buffers per decode | 71,573,504 additional bytes | Voxel and name buffers together account for approximately 84% of worker allocation. |

Worker timings overlap and must not be added as sequential loading time.
Whole-JVM collector accounting is not a Voxy-only pause measurement. Allocation
savings do not establish the same percentage improvement in loading speed.

The second defect is conditional and source-confirmed: an unretained cache-only
region can recover its growing journal index again before successive saves.
The warm capture had no network/cache-only writes, so this defect did not explain
that startup. Fix it to make background cache acquisition efficient as well.

## 2. Scope and invariants

- Keep local file layout, wire format, integrity validation, name/model resolution,
  cache-first display, corrupt-cache fallback, meshing output, renderer and GPU
  selection/retirement unchanged. No migration, versioning or compatibility path.
- Keep existing per-server bandwidth/storage settings, spatial priority, refresh
  cadence, eviction rank and physical-space pause behavior. Add no dependency,
  process, permanent world-wide index cache or arbitrary runtime budget.
- Work on the existing feature branch. Original `ASMP_Voxy`,
  `ASMP_Voxy_Restart` and Main remain read only; do not merge into the originals.
- Preserve the real PC's normal `.voxy` cache, source worlds, identities,
  configurations and unrelated mods. Do not delete cache to measure these fixes.
- Keep automatic debug-client updating and both independent PC backup SSH helpers
  operational. No laptop work, integration/unit suites, 100-client verification
  or mutation-pressure run. The suspended pressure test remains suspended.
- Preserve Testing's existing JVM arguments and external native memory ceiling.
  The recorded values are `-Xms1G -Xmx4G`, `memory.max=999997440` and
  `memory.swap.max=0`; verify actual targets inside the live window if touched.
- Keep code readable and changes small. Reuse existing files and owners; measure
  actual source lines/files/folders and client artifact bytes instead of imposing
  a new size limit or disguising complexity with compressed source.

## 3. Fix A: worker-owned decoded-cell and name scratch

Primary file: `src/main/java/me/cortex/voxy/client/lod/LocalSectionCodec.java`.
Review ownership in `ClientSession.WorkerSlot` and `SectionMesher`; their execution
and output need not change.

1. Lazily create one `long[CELLS]` per local codec. Replace the fresh array in
   `decode()` with this scratch array. Allocate only when decoding DATA so a codec
   used solely for metadata or cache-only encoding does not acquire voxel scratch.
2. Make the name-reading helper use one lazily allocated `byte[MAX_NAME_BYTES]`
   per codec. Reuse it for block and biome tables; names are decoded using their
   exact validated lengths. Keep the returned name-ID tables independent.
3. Document that local decoded `SectionData.cells()` is borrowed until the next
   decode or codec closure. The worker owns it through name resolution, model
   waiting and synchronous meshing. Neither geometry publication nor save input
   may retain it. A global pool, extra copy or asynchronous decoded-section queue
   is unnecessary.
4. Do not clear/fill scratch between decodes. Every successful DATA decode writes
   all 32,768 cells. Failed/cancelled decoding may leave partial scratch, but must
   throw before publishing it; the next successful decode overwrites it completely.
5. Preserve CRC, content hash, Zstd, UTF-8, name length, palette/index ordering,
   trailing-data and other existing checks. Preserve cancellation/fallback paths.
   Release scratch references with codec closure alongside existing resources.

The codec's `busy` guard ends when decode returns; it does not protect model
waiting or meshing. Safety comes from the existing one-task-per-worker lifecycle.
Audit all local decode callers rather than relying on that guard alone.

For S sections, C cells and W actual decoding workers, cumulative voxel-buffer
allocation changes from Theta(S*C) to Theta(W*C). Subsequent sections create no
new cell/name scratch. Peak live voxel storage already has Theta(W*C) complexity;
do not claim a peak-memory Big-O reduction. Decoding remains linear in output
cells and input/name bytes. In the recorded 14-worker run, reusable cells require
3.5 MiB and name scratch requires another 56 KiB.

## 4. Fix B: retain the recovered index while a region has active work

Primary files:

- `WorldCacheDownloads.java`: directory tasks, Coverage, probe and Job lifecycles.
- `CompletedSectionCache.java`: existing retained-directory registration.
- `RegionalDiskBudget.java` and `CompletedSectionJournal.java`: verify existing
  journal reuse, pin/writer protection, eviction, compaction and recovery behavior.
  Change these only where a verified lifecycle gap requires it.

The current temporary `inspectDirectory()` pin does not retain the recovered
index. Once pins, writers and retained directories are all zero, `forgetUnused()`
discards it. A later serial save then reopens and recovers the journal.

### 4.1 Retain before asynchronous inspection

Extract the existing retain-before-directory behavior in `CompletedSectionCache`
into a small idempotent owner-facing `retain(region)` helper. It registers the
existing directory ownership without opening a file. Keep normal `directory()`
behavior through the same helper and retain the existing `forget(region)` path.
Retain even when inspection finds no file: its Region placeholder must survive
so the first newly created journal stays indexed through subsequent saves.

`WorldCacheDownloads` retains on its owner thread before starting asynchronous
directory inspection. Use the existing directory work identity to track ownership.
An accepted result transfers that ownership to the region's active work; an
error, discarded result or cancelled task relinquishes its reason for retention.

Do not have the FutureTask callable acquire a lease that only its returned result
can release: cancellation can discard the result and leak that lease. Cancel and
release/transfer exactly once on the owner. The callable performs file inspection
only. Existing pins protect an inspection that has already started.

### 4.2 Cover in-flight background saves as well as Coverage

A region stays retained while any of these existing work reasons applies:

- accepted Coverage needed by current visible terrain or the current frontier probe;
- its current asynchronous directory inspection;
- admitted cache-only Jobs that have not been removed/completed.

Moving the probe can trim Coverage before a received body's cache-only commit
starts. Therefore Coverage alone is insufficient. Maintain an O(1) per-region
pending-job count on the existing Dimension owner, incrementing only after a Job
is installed and decrementing in the central successful `remove(job, ...)` path.
Keep one idempotent cache registration per region, independent of the number of
reasons. Release it only when all reasons disappear. Do not scan every Job to
decide whether a single region may be released.

A cancelled Job no longer owns future commits. Preserve existing current-ticket
predicates; pins/writer references keep already-started operations safe even if
the owner releases directory retention. A parked/blocked region with no visible,
directory or pending-job work must not retain its index indefinitely.

### 4.3 Centralize removal and invalidation

Replace every raw Coverage clear/remove/trim with the appropriate ownership
cleanup. Audit probe changes, view changes, inventory snapshot begin, geometry
changes, removed/unreadable regions, directory failure, disk-stamp invalidation,
detach, reconnect, dimension replacement/retirement and close.

Snapshot/geometry resets must also invalidate or cancel matching outstanding
directory work. The present snapshot clear does not advance the directory task's
epoch. A pre-reset task must not reinstall stale Coverage or ownership afterward.
Use the existing epoch/task identity, scoped to the invalidation where practical;
avoid another independent recovery subsystem.

Disk eviction must remain possible when only directory retention exists: an
index reference is not a file pin or retention-rank exemption. Preserve the
existing eviction stamp and remove/close handling. Compaction replaces the
journal/index under the existing drained writer owner; later work must use the
replacement. Never keep payload offsets or a journal instance across replacement.
Failed append/recovery still follows existing rollback and quarantine rules.

Normal directory-registration operations must perform no filesystem work on the
render/session owner. Retained journals keep no idle writer channel. Preserve
lock ordering, current writer arbitration and read pins; do not hold a filesystem
pin between jobs or share reader channels across workers.

For K serial saves with R existing journal frames during one active region
lifetime, index reconstruction work changes from Theta(K*R + K^2) to Theta(R+K),
excluding payload encoding, integrity work and deliberate replacement/recovery.
Binding/payload index updates remain expected O(1); first recovery remains O(R).
Revisiting a released region or replacing its file legitimately requires recovery.
Indexes remain proportional to active work, not every region ever visited.

## 5. Implementation order and evidence

Implement buffer reuse first, then retained-index ownership and complete removal
paths. Review the diff for escaped borrowed arrays, double release, stale async
results, index lifetime leaks and preserved eviction/compaction safety.

Use existing debug worker allocation, stage and cache-activation telemetry for
Fix A. If existing logs cannot identify journal reuse, add only small hooks to the
existing production no-op/debug `ClientLodDebug` facade and journal recovery path.
Record region/world identity, recovery count, recovered frame count and duration,
and active index registrations. Emit recovery summaries or aggregate counters,
not a string/log per cell or header. Do not add a permanent per-world telemetry map.

Build normal and debug client artifacts without running test tasks. There is no
server/protocol change, so leave the deployed server and native backend alone.
Prepare local artifact hashes and the previous client's rollback package before
live work. Commit implementation and receipts on the feature branch only.

## 6. Verification: target five minutes, absolute maximum ten

Source review, document preparation and compilation without executing tests are
preparation. **Every executed test or live validation action shares one continuous
monotonic clock**, beginning immediately before the first live preflight. Include
SSH/target checks, deployment verification, update/restart waits, cache-profile or
transport-hold setup, observation, retries, screenshots and restoration. Any test
run earlier starts the same clock earlier; there is no second allowance.

Record start/deadline before live preflight. Do not reset the clock after a failure,
a rebuilt artifact or a new scenario. Prefer this five-minute schedule:

| Aggregate elapsed | Activity |
| --- | --- |
| 0:00-1:30 | Verify actual PC, both backup helpers, updater and loaded artifact; publish the client debug build and restart only the real PC as needed. Preserve settings/caches. Use an existing warm namespace and existing scoped Voxy-only hold if safely available. |
| 1:30-2:30 | Observe cached activation without new Voxy traffic. Record worker allocations/cache hits and section activation milestones; capture/view terrain. |
| 2:30-3:45 | Restore transport and observe real background saves in an off-screen, cache-only region. Where naturally available, observe a probe/view transition or one reconnect to exercise release/cancellation. |
| 3:45-5:00 | Stop new scenarios. Restore task-owned holds/profile/view/settings changes, verify ordinary rendering and both helpers, close captures and record outcomes. |

These are priorities, not promises that startup fits. Skip later checks if startup
consumes their time. Do not add a JVM restart, dimension switch, world mutation or
synthetic cache fill just to obtain evidence. Do not overwrite impairment settings,
fill the PC disk, alter allowance limits or fabricate journals to provoke eviction.
Source review covers rare eviction/compaction/error branches unless they occur
naturally during this short real-client run.

If essential verification cannot finish in five minutes, use only the remaining
part of the same ten-minute window. Stop new scenarios by aggregate minute seven,
or sooner when restoration needs more time. At minute nine perform restoration
and evidence closure only. **At 600 seconds, stop testing; no final screenshot,
retry or fresh observation outside the cap.**

Prefer the existing transport hold with automatic expiry and record rollback
before applying any reversible control. Mandatory safety cleanup must still be
completed if an unexpected overrun occurs; it is restoration only, must be reported
as a missed limit, and never authorizes extra testing. The planned sequence must
finish restoration inside the cap. A previous repair exceeded its 600-second
limit; this plan must not repeat that failure by treating late evidence as cleanup.

## 7. Acceptance and reporting

| Change/check | Evidence required |
| --- | --- |
| Buffer ownership | Every local decode caller reviewed; scratch stays worker-owned through model waiting/meshing; independent output and validation preserved. |
| Buffer allocation | Stable worker/session interval: allocated-byte delta divided by successful cache decodes, including one-time scratch creation. Approximately 270,336 bytes plus array headers per hit are the identified repeat allocations to remove, not a guaranteed whole-mod rate. |
| Cache appearance | Time to first cached activation and matching section-count/LOD milestones, plus an inspected screenshot. Compare matched position/settings/cached keys where possible. Historical 257 data alone is not a controlled before/after speed test. |
| Journal reuse | Several actual serial cache-only commits in one identified region use its retained recovered index instead of one recovery per append, with no unexpected release/recovery between them. If network demand provides no suitable region within the window, mark live proof deferred. |
| Release correctness | Reviewed coverage/directory/job removal paths; debug ownership returns to the remaining active reasons on observed transition. Retired work cannot resurrect Coverage. |
| Rendering/network isolation | Existing cached terrain remains usable and normal traffic resumes; screenshots show no observed regression. No claim of exhaustive mesh correctness. |
| Boundaries | Actual running client identity, server/backend left unchanged, helpers operational, restored control state, one recorded clock and elapsed time. |

Prefer allocation per cache hit over raw MB/s: faster loading can process more
sections per second. Worker allocation is not whole-Voxy allocation. Summed stage
durations overlap; whole-JVM GC time is not proof of a Voxy-induced pause.
Report CPU/time/maximums as scoped observations, not a p99.5 performance guarantee.

Save implementation and live receipts under `project_audit`, including hashes,
source/artifact size changes, exact interval, attempted/skipped checks, failures,
restoration and remaining uncertainty. Claim implementation/build status separately
from measured speed and live proof. A short single-client check does not establish
100-player behavior, entire-world completion or rare-race correctness.
