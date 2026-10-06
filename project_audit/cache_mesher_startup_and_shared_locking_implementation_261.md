# Mesher, startup and cache coordination — client 261

Implemented 2026-10-06 on `feature/cache-first-background-updates`, source commit
`7f798d60fcd3cae9cd76931595579cda8823c6ef`, from plan commit
`60748441fd24fd82f1d3d5b170d53e708d40b66b`. All three approved implementation
groups are complete. The real-PC cache-start/resumed-download check passed;
live verification of all rare paths and comparative performance is partial.
Debug client 261 is running with the unchanged Java controller/native backend 260.

## Implemented behavior

### Conservative mesher masks

`SectionMesher` builds reusable face-existence, can-be-occluded and occluding masks
in the existing preparation traversal. Interior candidates exclude only faces that
the unchanged model occlusion rule would reject. Boundary candidates retain the
original neighbour handling; fluid overlays bypass base masks. Model zero is
explicitly excluded because the model-query representation otherwise reports face
existence for it.

Rows traverse set bits in the original face/depth/row/column order. Greedy merging
requires both current-plane mask membership and matching original face data.
Consumed runs clear their bits, including column 31; the original maximum quad
edge remains 16. Stale values in reusable plane arrays cannot participate without
a live bit. Lighting, tint, custom-model face rules, packed geometry and fluid
face generation remain in the existing authoritative code. There is no second
scalar implementation or fallback.

Worst-case processing remains linear in cells and candidate faces. Dense terrain
does less face work; irregular exposed terrain still pays mask construction.
The unavoidable cost is **73,856 primitive bytes and four arrays per worker**:
72 KiB of face masks plus 128 bytes of row masks. The observed fourteen workers
therefore retain 1,033,984 primitive bytes across 56 arrays. Approximately
1,035,104 bytes including array headers/workspace references assumes the usual
compressed-oop layout; this is a layout estimate, not a heap measurement.
Incorrect masks would be a correctness bug, not an accepted geometry tradeoff.

### Remove redundant startup work

`CompletedSectionJournal.recover()` owns one reusable header, metadata and footer
buffer and CRC32C scratch for the recovery. Reads reset position/limit; CRC covers
the actual PAYLOAD/BINDING/RESET metadata lengths of 76/96/8 bytes. Existing
integrity, partial-read, footer, predecessor, payload-reference and tail-recovery
checks remain. Scratch never escapes. General reads that legitimately return
independent data retain their allocating helper.

Transient frame-read buffer allocation changes from Theta(H) to O(1) for H journal
frames. Recovery time remains Theta(H), and retained indexes remain proportional
to payloads and bindings. This does not make recovery constant-time.

`CompletedSectionCache.snapshotDirectory()` and foreground metadata loading omit
unused named-byte accounting. The required binding copy and incarnation are
captured under one pin/journal ownership interval. Downloader inspection still
computes accounting when its caller uses it. This removes a redundant Theta(N)
scan and temporary distinct-payload set; the necessary snapshot remains Theta(N).

Initial locally bound EMPTY publication uses a task with no save obligation.
Newly received EMPTY/ABSENT tasks retain their normal persistence path. In
particular, a remote EMPTY retains its save lease/input before geometry creation;
that save runs after completion and survives a renderer/worker retry with the same
revision. A retry can therefore enter `publishEmpty()` without itself proving
disk presence, but it does not cancel the original remote save. Optional save
failure remains optional persistence failure, not fabricated disk coverage.

The live held phase had 238 EMPTY activations and **zero saves/journal commits**.
After reconnection, successful server-backed saves and real commits occurred.
Redundant identical bindings could previously short-circuit without appending;
this change removes the persistence overhead rather than claiming every cached
EMPTY previously rewrote bytes.

### Short shared locks, incremental retention and UTF-8 reuse

`CompletedSectionCache` reserves an operation under its facade monitor, then
acquires the budget pin outside that monitor. Close cannot tear down the budget
while the reserved operation is in flight; failed acquisition balances ownership.

`RegionalDiskBudget` performs FileStore/free-space probes outside shared monitors.
It validates the observation epoch before admission against ownership, policy,
mutation and pause changes. A stale observation retries without inventing a
disk-full event. Eviction triggers a fresh probe before admission. Paused recovery
requires a current observation and safe idle state; interruption defers cleanup
recovery without hiding the original failure. Pin ownership is counted before a
draining wait, preserving the region until the waiter proceeds or unwinds.

Finite storage retains exact ranking. A direct account/dimension/region index
locates existing file metadata; visibility-only changes rekey the symmetric
difference. Overlapping pins do not reconstruct ranking; first/last pin or writer
transitions update eligibility. Moved anchors still rebuild exact ordering.
Entire world skips eviction-only index/tree storage; switching to finite storage
recounts and rebuilds before admission. Identical configuration skips a recount
only while ownership/policy identity is still unchanged.

For R files, V visible keys and Delta changed indexed files, visibility-only work
is O(V + Delta log R), overlapping pin changes are expected O(1), and anchor
movement remains O(R log R). Finite indexing costs O(R) retained state. These are
algorithmic improvements, not arbitrary admission or throughput limits.

`CatalogCodec.Source` owns lazily encoded immutable UTF-8 spellings. Catalog and
shared-prefix ownership publish each spelling once, with fixed prefix bounds.
Malformed surrogates, empty names, per-name and aggregate size checks remain.
Derived byte caches do not affect canonical equality. `LocalSectionCodec` streams
these bytes through a reusable two-byte length prefix, removing repeated
`getBytes()`, header copies and prefix-plus-name arrays. Palette/name order, valid
cache/wire bytes and compression are unchanged.

The unavoidable cost is source-owned name slots and bytes until the source is
released: O(accepted names) references and O(used UTF-8 bytes), with one immutable
wrapper and byte array per used name. With compressed references the slot payload
is approximately four bytes per slot, plus list capacity/headers. Exact retained
UTF-8 bytes were **not separately instrumented or measured**; the live run did
exercise new DATA encoding and commits. Scratch ownership, accounting and epoch
mistakes would be bugs, not accepted behavioural changes.

## Review, build and size

Independent source reviews covered axis/sign mapping, bit 31, boundaries, fluids,
mask-aware merging, encoded-name equality/Unicode/prefix ownership, CRC extents,
directory callers, EMPTY save lifetime, lock order, stale probes, draining pins,
pause recovery and finite-mode transitions. Review caught stale-observation and
interruption-cleanup issues during implementation; both were corrected before
the final build. The remaining remote-EMPTY retry question was checked against
retained save ownership and did not establish a defect.

`./gradlew jar debugJar --offline --console=plain` succeeded in six seconds.
No test tasks ran. The task graph also assembled server jars, with native build
already up to date; those artifacts were not deployed. Only existing API
deprecation/unchecked advisories appeared.

| Client artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| Normal 261 | 3,924,411 | `fac91da9bb8f392a2ae01f2901dd568cf420a36156985229ca9f79896b3df937` |
| Debug 261 | 4,079,809 | `b4f2b73b6c39704aa358761250ba3b568e05d2a8c0dfde199faa9ed732a0cc44` |

Both jars grew by 10,817 bytes from 260. Seven existing Java files changed; only
`gradle.properties` additionally changed to give the existing updater a newer
artifact number. No dependency, protocol/cache version, legacy reader, executor,
budget, source file or source directory was added.

The same physical-source count includes comments/blanks and filesystem untracked
source, separates test/pressure code, excludes generated/audit helpers, and counts
empty directories. Release sources changed **38,141 → 38,522 lines**, with
183 files/56 directories/zero empty directories unchanged. All selected sources
including shared changed 51,127 → 51,508 lines, with 249 files/122 directories
unchanged. Net source growth is **381 lines**; shared/server/native sources are
unchanged. See [the complete size ledger](cache_mesher_startup_and_shared_locking_size_261.json).

## Deployment and bounded real-PC verification

The one monotonic clock started **2026-10-06 03:28:18.276502 UTC** and finished
**03:36:18.065670 UTC**, including cleanup: **479.789 seconds**. This exceeds the
five-minute target and stays below the hard ten-minute maximum. Startup, updater
waiting and failed automation consumed part of the window. No unit/integration,
synthetic, fake, laptop or 100-client tests ran. Subsequent review used frozen
logs/receipts only; no additional live testing followed clock closure.

The prepared/hash-verified debug jar was published atomically to the MGengine
updater feed. GIORKOSPC restarted once, game PID **32192 → 23216**. Installed
artifact SHA, debug header and typed build identity prove the loaded 261 artifact,
not merely the intended feed version. Backup helper PIDs **19916 and 22444**
remained unchanged; both pinned SSH routes responded before and after deployment.

The existing expiring Voxy-only transport hold allowed cached terrain to activate
before HELLO without QUIC traffic. Normal Minecraft traffic continued. The hold
was resumed and absent at closure. No cache reset, alternate namespace, Clumsy
change, teleport, terrain mutation, disk filling or quota change was performed.
The selected **200 kbps** cap and **Entire world** storage policy remained selected.
General configuration hash was unchanged. The policy file legitimately changed
its estimated-world bytes and saved player anchor; it was not byte-identical.

The Testing JVM/native remained PIDs 306308/307526 with identical start ticks,
jar/native/configuration/unrelated-mod hashes. JVM initial/max heap remained
1/4 GiB. Native `memory.max=999997440` and `memory.swap.max=0` stayed enforced;
sampled accounted memory grew 188,686,336 → 259,072,000 bytes, with zero max/OOM/
OOM-kill events. Main, original Voxy and Restart were not modified.

| Candidate observation | Measured result |
| --- | --- |
| First cached terrain | 0.799079 s from the Voxy session's startup origin; first cached LOD4/3/2/1/0: 0.796421/0.835917/0.936005/0.977068/1.112098 s. This is not full Minecraft launch time. |
| Held cache phase | 32,144 local activations: 31,906 DATA + 238 EMPTY; zero QUIC bytes and no accepted HELLO at the held sample. Cache-hit count 32,145 includes retries and need not equal unique sections. |
| Activation event milestones | 10,000/20,000/30,000/32,000 activations logged at 03:31:47.468/50.299/53.258/53.592 UTC; all 32,144 at 03:32:18.797 UTC. Event timestamps and delayed pipeline-snapshot formatting are distinct observations. |
| Held section-worker allocation | 634,765,056 bytes over fourteen worker lifetimes, about 19,747 bytes per cache hit; includes initialization/all worker tasks. Metadata worker separately allocated 389,077,776 bytes for inventory/recovery/snapshots and other metadata work. |
| Held CACHE_READ | 32,314 visits; 28.584431 worker-seconds total, 0.884583 ms mean, 390.3603 ms maximum. |
| Held MESH | 32,123 visits; 30.859117 worker-seconds total, 0.960655 ms mean, 26.574 ms maximum. |
| Held METADATA / WAIT_MODELS | 25.208729 / 4.920039 worker-seconds, maxima 81.1506 / 131.023 ms. |
| Resumed transport | Final frozen sample: 60 fresh DATA activations, 333,358 compressed terrain bytes; 2,260 successful saves, 977 actual journal commits across 513 regions. |
| Persistence/accounting | No save failures/cancellations, cache misses or corruption; final actual-file accounting 1,551,819,712 bytes, +498,970 after the last pre-HELLO inventory sample; unowned bytes zero. No independent final filesystem scan was taken. |
| Journal recovery | Final 6,365 recovery events/645,811 visited frames, 6.307604 summed seconds; valid existing journals recovered and were used. |

Stage durations overlap across workers and include elapsed waiting: they are not
wall completion time or CPU time. Sampled CPU deltas total 47.203125 section-worker
CPU-seconds and 8.1875 metadata CPU-seconds over the run. Generic worker FAILURE
counter 191 matches MODEL_RECLAIM 191; it must not be described as zero failures
or as 191 durable persistence failures. No non-stale regional worker warning or
session failure was observed. An expired pre-existing cache-profile lease was
rejected by the updater; no alternate namespace became active.

Successful-save count exceeds journal commits because identical bindings and
ABSENT handling can complete without an append. Separate NETWORK encoding/save
samples and matching commit records confirm fresh DATA persistence. There was
no second restart/reread of those newly written records.

## Screenshots, failures and limits

Both captured real-PC screenshots were opened and inspected at the same pose
(-36.56,100,68.21; yaw 24.27, pitch 29.75; effective world FOV about 77).
Textured grass/stone/sand, trees, rails and structures appear stable, with no obvious
missing-texture checkerboard at the shown scale. Both also show a large rectangular
exposed area and dark vertical water curtain. The images cannot determine whether
those are real excavation/caves, an existing rendering problem or a defect. This
run does **not** establish perfect geometry, absence of holes or all custom-model
correctness. Saved 260 views have different pose/FOV and are not a matched baseline.

| Preserved screenshot | SHA-256 |
| --- | --- |
| Held, sequence 1 | `20f008b6b383a36fc1b53ce95362a40ebe1ffa6c845f27aa105be11f221a0215` |
| Resumed, sequence 3 | `54bf56e095b9e3b612e6dcfcab7eb779a75415ca3317cca43219eafe9d90c131` |

Automation mistakes were retained, not reclassified as passed checks:

1. Initial BEGIN used a label instead of the required plan SHA and was rejected;
   its helper waited fifteen seconds. No run started.
2. A sandboxed BEGIN retry could not verify RCON listener ownership and failed
   before command delivery. A permitted retry with the correct SHA succeeded.
3. FINISH had an extra explanatory argument and was rejected. I prematurely
   marked the observation clock closed before checking that failure. Cleanup-only
   corrected FINISH succeeded; all elapsed time remained charged to the original
   clock, yielding 479.789 seconds. No clock reset or new scenarios occurred.

The final typed harness status is **PASS for this scoped cache-start/resumed-view
run**, not all plan risks. No rollback was needed for these checks. The final
receipt preserves both premature observation closure at 427.659 seconds and
actual cleanup completion. Future closure must check the finalization receipt
before marking the clock ended.

Unverified live: finite allowance/eviction/incremental ranking (Entire world was
selected), disk-full pause/recovery, delayed-space races, interrupted draining pins,
close races, corruption injection, EMPTY save failure/cancellation, cache-only
prefetch persistence and exhaustive model/geometry equivalence. These received
source review rather than manufactured scenarios. Cache loading had substantially
settled before transport resumed, so this is **not a concurrent cache/network
contention benchmark**. There is no matched 260 startup A/B, percent speedup,
Clumsy-causality, FPS/p99.5, whole-world or 100-player claim.

## Evidence locations

- [Compact live receipt](cache_mesher_startup_and_shared_locking_live_261.json)
  and [size ledger](cache_mesher_startup_and_shared_locking_size_261.json).
- Full frozen logs, server/PC/updater receipts, clock, screenshots and prepared
  260 rollback artifacts remain under `project_audit/deployment/cache261/`.
  Large logs, rollback binaries, source snapshots and helper scripts are local
  audit evidence and are not added to the repository.
- Build artifact: `build/libs/ASMP_voxy-0.2.261-beta+1.21.1-neoforge-debug.jar`.
  Published feed: `build/libs/debug-clients/MGengine/` with that filename.

No unapproved pipeline separation, writer batching, new frontier, cache format,
cached meshes, checkpointing, budgets or legacy compatibility was introduced.
