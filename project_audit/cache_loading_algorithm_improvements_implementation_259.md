# Cache loading algorithm improvements: implementation and live evidence

All six approved changes and ordered top-root admission are implemented on
`feature/cache-first-background-updates`, implementation commit `28e55c15`, in debug
client 259. Compilation succeeded and the real PC automatically updated to it.
The scoped live check completed; its evidence and limitations appear below.
Original repositories and Main remain unchanged. Client 259 is intended to run
against Testing's existing Java/native 257 deployment.

## Algorithms and ownership

- Regional metadata selection uses intrusive FIFO queues in the existing 32 pixel
  buckets, split by coverage priority. Bucket histograms replace repeated member
  maximum scans. Admission, removal, priority updates and selection are O(1) with
  respect to regional/member count; draining R regions is Theta(R), not Theta(R^2).
  Reclassification may alter equal-priority order, as approved. There is no new
  quota or exact-distance scheduler.
- The top coalescing mailbox preserves the upstream planner's nearest-first
  insertion order. Other GPU/detail scheduling remains unchanged. This preserves
  startup/new-entry ordering, not perfect circular activation under asynchronous
  work or continual sorting after movement.
- BLAKE3 keeps worker/operation-owned compression arrays and reusable tree slots.
  The codec owns read hash state across read/decode/integrity checking; append
  retains independent state across suspended steps; compaction owns its own
  reusable state. Hashing remains Theta(bytes), with O(log bytes) tree scratch.
  CRC, content hashes, byte order, deferred final chunk, flags, padding, reset
  and finalized-state checks remain intact.
- Codec first-use ordering uses the existing scalar ordering invariant; redundant
  Boolean arrays are removed. Used-model IDs are built from distinct canonical
  block-state names, excluding air and deduplicating aliases. This operation
  changes from Theta(palette entries) to Theta(block states); the palette and voxel
  decode still require their original entries and linear work.
- Mesher preparation also computes the same nonair packed bounds. Reused Y/Z/X
  depth masks restrict waterlogged overlays to eligible planes; all base passes
  and face/custom-model/light/tint/fluid rules remain unchanged. Bounds fusion
  removes one cell traversal. Worst-case work remains Theta(cells + output quads).
- READY inventory answers definitive absence using existing accounting and writer
  ownership. Unknown/mutating regions use normal metadata inspection. Completed
  background bindings patch the active local directory rather than rereading it.
  Pending initial snapshots merge same-key overlays, guarded by region identity,
  view/revision and file incarnation. Direct/child-completeness bits update only
  the fixed ancestor path, avoiding recursive incomplete-cut scans. A real cut
  still takes Theta(emitted dependencies) to enumerate.

The eviction review required small additional plumbing in the existing disk/cache
owners and `WorldCacheDownloads`. Retained Region objects carry unique internal
incarnation stamps; snapshots, save completions and predecessor fallbacks capture
one while pinned. Eviction, RESET and payload quarantine advance it; compaction
preserves it. Before using affected local/downloader summaries, stale snapshots,
coverage and pending overlays are rejected selectively. Delayed writes cannot
reinsert bindings from an evicted file. Valid predecessor fallback survives
invalidation as a local-only overlay, without claiming a new disk commit or
upgrading downloader full-quality coverage. Installed geometry and worker leases
remain owned. No permanent world map, format change or new retention reason was
introduced. Checks of retained regions use expected O(1) lookup without filesystem
access/path allocation; rare unretained checks fall back to the budget's map.

Initial active-directory bookkeeping is Theta(N), ordinary K patches Theta(K)
with fixed hierarchy depth, excluding real invalidation, payload work and necessary
cut output. Existing downloader global physical-eviction invalidation is retained;
no claim is made that every prior algorithm in Voxy is now O(1).

## Build, size and preparation

Final build: `./gradlew jar debugJar --console=plain`, successful in five seconds.
No test task ran. Compiler failures during implementation were corrected and their
logs retained, including missing throws declarations, base/subclass demand typing
and one missing local stamp declaration. Build success is compilation evidence,
not runtime integrity or meshing proof. Gradle assembled dependent server jars;
those are not deployed and no server/native restart is part of this change.

See `deployment/build259-final-success.log`, `deployment/staged259.json`,
`cache_algorithm_source_count_259.json` and `cache_algorithm_size_ledger_259.json`.

| Same scope | 258 | 259 | Delta |
| --- | ---: | ---: | ---: |
| Release source physical lines | 37,842 | 38,144 | +302 |
| Release source files / directories | 183 / 56 | 183 / 56 | 0 / 0 |
| All source including shared physical lines | 50,827 | 51,133 | +306 |
| All source including shared files / directories | 249 / 122 | 249 / 122 | 0 / 0 |
| Normal client bytes | 3,909,781 | 3,915,855 | +6,074 |
| Debug client bytes | 4,065,180 | 4,071,384 | +6,204 |

Physical source counts include comments/blanks and tracked/untracked maintained
source in the same scope as 258; generated/build/audit files are excluded. Shared
source is unchanged and counted separately in the previous ledger. No empty
selected directory remains. Audit helper files are outside that source scope.

Debug SHA-256: `140f41e1f6ca61edaa6943d16d623a20c67abe1918a886b15e091a38e716c354`.
Normal SHA-256: `91a34cf3ca681c7564ef5bbea11aa8361f733d53f56baad2d523048f269fd6cd`.

Real-PC checks used one non-resettable monotonic clock, target five minutes,
absolute ten minutes including updater waits, SSH checks, screenshots and
restoration. No integration/unit suite, laptop, synthetic or 100-client testing.
Cache/settings/world/identity preservation and both backup connections are
required; the hold has an automatic five-minute expiry. Publication checks any
pending cache-reset/profile updater requests against preserved PC receipts.


## Deployment and actual live results

The published and actually loaded debug artifact is SHA-256
`140f41e1f6ca61edaa6943d16d623a20c67abe1918a886b15e091a38e716c354`.
The PC log header is `Voxy version 0.2.259-beta`, its installed artifact matches
that hash, and typed CLIENT_READY/RUN metadata independently match the build hash.
The run metadata reports server version `0.2.257-beta`. Intended pairing is
therefore **client debug 259 / Java-server debug 257 / existing native 257 artifact**.
The updater feed is `build/libs/debug-clients/MGengine/`; publication was atomic
and hash-verified. There was one automatic client restart, game PID 32836 -> 32240.
A pre-existing temporary launch-copy cleanup warning recurred because the live
javaagent file is in use; the replacement process remained alive and reconnected.
This was not interpreted as a failed restart.

Typed run: `7fdfbc26-471a-476f-ac88-10505d045880`.
The client was not teleported or switched between dimensions. Its sampled pose
was overworld (-32.8633, 1000, 30.1079), yaw 34.146667, pitch 33.720047, configured
FOV 70. Shaders were already off in the observed client state; this task did not
change them. The selected Voxy download allowance stayed 10,000 kbps and storage
stayed Entire world. General-config SHA stayed unchanged. The per-server file
changed through normal runtime persistence of the overworld anchor (0,0) ->
(-33,30) and estimated world bytes; its selected policies and Nether anchor
remained unchanged. Restoring this file wholesale would discard legitimate
runtime bookkeeping, so it was preserved.

Testing started at 2026-10-05 19:56:29.468780 UTC and ended at
20:01:54.650147 UTC: **325.181366 seconds (5 minutes 25 seconds)**. The ten-minute
hard limit was met; the preferred five-minute target was exceeded by 25 seconds.
This one non-resettable clock includes both backup-route checks, preflight,
publication, update/startup waits, screenshots and their inspection, restored
transport, closure and evidence capture. After closure, only preserved evidence
was analyzed. No additional live query, screenshot or retry ran.

All five typed results (CLIENT_READY, cached screenshot, resume checkpoint,
restored screenshot, RUN_COMPLETE) reported failure NONE. Server finalization
returned PASS. That status covers these observation/control predicates, not
exhaustive application correctness. See `live_client/cache259-capture/typed-run`
and `deployment/live259-clock.json`.

### Cached terrain, presence and integrity

The held screenshot recorded **74,828 successful cache reads, zero cache misses,
zero terrain network bytes and 74,953 active sections**. QUIC sent/received byte
counters remained zero during held startup. The existing pre-259 records retain
their independently stored hashes, so these successes exercise the new hash
reader against pre-change fingerprints rather than self-generated expectations.
First local activation was sampled at approximately **1.034 seconds** from the
existing startup telemetry origin. This is not updater-to-terrain elapsed time.

All 13,273 active regional local views completed. **12,669** used the definitive
known-absence shortcut; the metadata worker performed 604 actual
region inspections/recoveries instead of dispatching work for every absent region.
The initial inventory and existing journals still require real I/O. The fixed
queue/member policy and upstream insertion-order preservation were checked in
source; no separate exact-circle admission trace or controlled ordering baseline
was collected.

Both `cached-terrain.png` and `restored-terrain.png` were inspected within the
clock. They show textured forests, lakes, snow terrain and distant hills, with no
obvious introduced gap or missing-texture pattern in the sampled view. The first
image has falling weather particles. Individual custom models/waterlogged faces
at this altitude were not resolved sufficiently for exhaustive correctness.

After resuming transport, the restored screenshot recorded 79,661 active sections,
82,627 decoded/meshed sections and 38,101,272 terrain network bytes. RUN_COMPLETE
recorded 85,162 decoded/meshed sections and 50,935,505 network bytes. Real
Rust-produced DATA passed the preserved CRC/canonical validation path. Captured
logs contain no Voxy-specific integrity/persistence exception or missing canonical
cache-only payload error; this is scoped to the saved interval. Startup worker
FAILURE=421 appears alongside MODEL_RECLAIM/SECTION_RECOVERY=424; the aggregate
record does not independently classify every failure. Session/connection failures
and cache-corruption counters remain clear. Do not
claim every diagnostic counter was zero or every other mod started without errors.

A naturally observed re-read of newly committed DATA was **not established**:
cacheHits stayed 74,828 after online work. No second restart was performed solely
for that check. Exact empty/block/chunk hash lengths, real eviction/RESET/quarantine
races and cancellation invariants received source review, without fault injection
or an integration/unit suite.

### Incremental directory observation

Held startup ended with localViews=13,273, localPatches=238 and prefetchCommitted=0.
The final preserved sample has localViews=13,273, localPatches=14,946 and
prefetchCommitted=191, prefetchFailures=0. Thus ordinary foreground/background
commits and the 191 cache-only completions occurred without a corresponding
full active-local-directory snapshot for each commit. Existing journal commit
summaries identify actual serial appends: region 8589934590, journal identity
1793049773, handled 1,755 commits with one recovery. See
`deployment/cache259-directory-commit-proof.json`. Owner revision/identity/incarnation
checks and fixed-depth coverage maintenance were reviewed in source; rare races
were not forced during this short check.

### Restoration and server limits

Both independent pinned SSH routes reached GIORKOSPC before publication, and
primary cleanup plus the final secondary state query succeeded. Independent
backup helper PIDs **19916 and 22444** stayed present. Voxy's typed resume succeeded;
the task-owned hold marker was absent at final state. No settings control remained
owned by the harness. Normal cache and profile were preserved; no cache reset,
world edit, impairment change or unrelated-mod replacement was performed.

Java/native PID and start ticks stayed identical between preflight and closure:
4176932 / 44596567 and 4177620 / 44597892. JVM initial/max heap stayed
1,073,741,824 / 4,294,967,296 bytes. Native executable SHA stayed
`85dd01efb215ec1c33629b8bf1f8cbd7805f128e46b843c0bae1827215f06a01`;
`memory.max=999997440`, `memory.swap.max=0`. Accounted memory was 460,509,184 bytes
initially and 516,481,024 at closure. OOM/kill counters remained zero. Existing
memory.max event count 1347 stayed 1347; it was not a new event in this check.
Server/native artifacts, processes and settings were not deployed or restarted.

Preserved uploaded log: 36,201,424 bytes, SHA-256
`ff75735935fca42e87f73df92cb0c965eed04d77781c6f0132e5b3b04e3a860c`.
The uploaded snapshot timestamp is 20:01:48.2952638 UTC, preceding clock closure;
its last buffered event is not assumed to reach the final PC-state timestamp.
`live_client/cache259-capture/manifest.json` records captured hashes. No laptop,
100-client, block-mutation-pressure, integration/unit or synthetic tests were run.
This sample establishes cache-first rendering and successful live paths, not a
matched FPS/p99.5 or loading-speed comparison, exhaustive blocks or whole-mod
allocation target proof.


## Allocation and stage measurements

The complete preserved held interval spans log lines 64 to 76087: stable section
workers 0-13, sampled elapsed **32.041015 seconds**, successful worker CACHE_HIT
outcomes 0 -> **74,898**, allocation delta **1,669,435,184 bytes**. This is
**22,289.449 bytes per successful worker cache read** (22.3 KB) and **52.103 MB/s**
across those workers. The typed accepted cacheHits count is 74,828: worker success
can precede cancellation/owner acceptance, so these are different counters and
must not be mixed in the allocation denominator. Native allocations, the owner,
model generation, render threads and other mods are outside this worker Java
allocation counter.

The previous build-258 held capture measured 46,680.655 bytes/hit; this observed
sample is **52.251% lower per hit**. The scenes, cached sections, shaders and runtime
were not matched; this is evidence consistent with reduced temporary hashing and
model-list allocation, not a controlled speed/FPS claim. Average worker allocation
alone is slightly above 50 MB/s; no whole-Voxy <50 MB/s target is asserted. Faster
useful work can raise MB/s despite reducing bytes per section.

The last warm sample records 74,831 completed MESH stages, 179.123 summed worker
seconds (2.394 ms per completed stage), and 75,252 CACHE_READ stages, 51.444 summed
worker seconds (0.684 ms per stage). These are concurrent aggregate stage timings,
not wall time or a matched comparison against the previous scene.

All 74,898 warm successful reads precede the first actual journal COMMIT line
76245, and terrain received/QUIC traffic stayed zero. Consequently existing stored
CRC/content hashes were checked before this build appended any new record. Exact
stored local-hash hex values are not logged; no claim is made about a named
cross-build section/hash tuple match. See `deployment/cache259-stored-hash-reuse.json`
and `deployment/cache259-warm-full-capture.json` for the preserved calculations.

No follow-up testing was added after the clock closed. The footprint increase is
302 release-source lines, four debug-source lines, zero source files/directories
and approximately 6 KB per client artifact. The added incarnation bookkeeping is
a correctness requirement of incremental views, not a runtime quota or new wire
format. GPU selection/loading, render-distance/pixel settings, bandwidth policies,
wire protocol, canonical payload representation and dependencies are unchanged.
