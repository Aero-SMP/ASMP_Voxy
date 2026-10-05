# Cache-worker buffer and journal-index reuse: implementation and live evidence

Implemented both fixes from the [plan](cache_worker_buffers_and_journal_index_reuse_plan.md)
on `feature/cache-first-background-updates`, implementation commit `4512f56f`.
The real PC automatically updated to debug client **0.2.258-beta**. Testing's
Java server and native backend remained on their existing build 257 artifacts.
Original repositories and Main were not modified.

## Changes and ownership

`LocalSectionCodec` now lazily owns one 32,768-cell array and one 4,096-byte name
buffer. It reuses these through the existing worker lifecycle and drops their
references on closure. Successful decoding overwrites every cell; invalid or
cancelled decoding never publishes partial scratch. Exact-length UTF-8 decoding,
CRC/content hash checks, Zstd checks, palette validation and fallback are intact.
Local decoded cells are explicitly borrowed until the next decode/closure.

All local decode callers were reviewed. The normal worker completes model waiting
and synchronous meshing before reuse. Model polling uses block IDs; published
geometry and save inputs retain no decoded cell array. The existing debug cache
chain check consumes each decode before moving to the next. No pool or copy-back
was added; the codec's busy guard is not mistaken for lifetime protection after
decode returns.

`CompletedSectionCache.retain()` reuses its existing idempotent registration.
`WorldCacheDownloads` acquires it on the owner before asynchronous inspection,
including when no journal exists yet. An accepted visible/probe Coverage,
outstanding directory inspection, or pending background Job keeps the region
registered. Per-region job counts increment on installation and decrement in
central successful removal; a single release checks those reasons with expected
O(1) lookups. Probe/blocked-region cleanup is also local rather than a scan.

Coverage removal, snapshot/geometry reset, source removal, physical-eviction
stamp changes, cancellation, detach/reconnect, retirement and closure release
ownership consistently. Per-dimension epochs and task cancellation prevent
pre-reset directory results from reinstating Coverage. The asynchronous callable
owns only its temporary file pin, so cancellation cannot lose a returned lease.

Existing operation pins and writers preserve started work. Directory retention
does not pin files between jobs, open idle channels, alter eviction rank or exempt
files from eviction. Compaction/eviction replace or clear the budget-owned journal;
the downloader captures neither its object nor payload offsets. Source review of
these boundaries was performed independently as well as during implementation.

The existing no-op/debug facade now records recovery summaries, actual append
commits and aggregate index reuse. Recovery loops count visits locally and emit
once; there are no per-header strings or world-history maps. Existing downloader
snapshots include active retained-index, job-region and directory-owner counts.
Normal rendering, meshing algorithms, GPU selection, settings and formats are
unchanged. No dependency, new production source file or runtime budget was added.

## Builds and deployed identities

Normal and debug clients built with `./gradlew jar debugJar --console=plain`.
The final build succeeded in three seconds, without executing test tasks. Build
preparation preceded the live clock and establishes compilation, not correctness.
See [build log](deployment/build258-final.log) and [staging](deployment/staged258.json).

| Artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| Client debug 258 | 4,065,180 | `a54dce7c4ec1f3ee05f084898684a348337dc08e59acd69e17086536535075a4` |
| Client normal 258 | 3,909,781 | `be7dc1fc808d84c2175ba3a023ff1ad1337676a4dd16c08e6e6f7699f72ebafc` |

Debug download: `build/libs/ASMP_voxy-0.2.258-beta+1.21.1-neoforge-debug.jar`.
Published atomically to the existing `build/libs/debug-clients/MGengine/` feed.
The PC's typed CLIENT_READY identity and run metadata match the debug SHA above;
the installed filename alone was not used as proof. The recorded server version
is 0.2.257-beta and no server/native protocol change was required.

PC game PID changed from 16912 to 32836. Both independent helper PIDs, 19916 and
22444, remained present. Both pinned SSH routes reached GIORKOSPC, including the
secondary route during final restoration. The old client and settings were saved
under the PC profile's `.voxy-updater/cache-first-live/cache258/` directory.
The task did not change settings or cache profile, clear cache, edit terrain,
change impairment settings, restart a backup helper or restart the server.

Both server checks identified the same Java/native PIDs and start ticks. Java
retained initial/max heap 1,073,741,824 / 4,294,967,296 bytes. Native executable SHA
remained `85dd01efb215ec1c33629b8bf1f8cbd7805f128e46b843c0bae1827215f06a01`;
`memory.max=999997440` and `memory.swap.max=0` were unchanged. Observed native
accounted memory was 396,328,960 bytes at preflight and 571,269,120 at final check.
OOM counters stayed zero. The existing cgroup max-event count was already 1347
and remained 1347; do not describe it as zero or attribute it to this check.

## Single live verification window

Clock: 2026-10-05 18:47:30.058715 UTC to 18:55:15.803524 UTC,
**465.744807 seconds (7 minutes 46 seconds)**. The hard 600-second limit was met;
the preferred five-minute target was not. Preflight, automatic update/startup,
observations, screenshot inspection and restoration share this one clock.
See [clock receipt](deployment/live258-clock.json).

The clock was never reset. Startup/identity checks and initial warm capture used
approximately the first 143 seconds. Initial online work was foreground work;
the brief sky-facing observation was needed to exercise actual cache-only saves.
All controls, capture and restoration finished before clock closure. Subsequent
work only reviewed the preserved files; no additional live checks or tests ran.

Typed run: `6a3d1e67-9607-4f53-b973-397f6272a5eb`. Steps were one cached screenshot,
resume QUIC, look upward at the same position, restore the recorded pose, a restored
screenshot, then END_RUN and server-side finalization. Every typed result reported
NONE and the run was finalized PASS. This proves those control/observation predicates,
not absence of every application error. No integration/unit suite, laptop check,
100-client run or mutation-pressure test was executed.

Preserved evidence: [capture directory](live_client/cache258-capture), including
the 48,751,811-byte debug-log snapshot, typed receipts and inspected screenshots.
The full uploaded log was captured during the clock; its buffered events are not
assumed to extend to the final PC-state receipt's exact time.

### Cached loading and allocation

With Voxy transport held, the typed screenshot recorded **51,118 active sections**,
50,911 cache hits and zero terrain network bytes. Actual terrain GPU draws were
present. The inspected [cached screenshot](live_client/cache258-capture/cached-terrain.png)
showed forested terrain and distant hills under Photon; the
[restored screenshot](live_client/cache258-capture/restored-terrain.png) retained
the normal view with no obvious newly introduced gap or missing-texture pattern.
This is not exhaustive modded-block, water-edge or seam correctness proof.

Using complete, stable section-worker samples in the preserved held interval:

| Quantity | Result |
| --- | ---: |
| Successful cache decodes | 0 to 50,906 |
| Sampled monotonic elapsed | 37.0853473 seconds |
| Allocation delta, workers 0-13 | 2,376,325,432 bytes |
| Worker allocation per cache hit | **46,680.66 bytes**, approximately 46.7 KB |
| Average allocation over that interval | 64.08 MB/s across those workers |

See [allocation calculation](deployment/cache258-warm-full-capture.json).
The earlier held build 257 capture implies approximately 321 KB per hit. The
new sample is approximately 85% lower per hit, consistent with removing repeated
256 KiB cells plus two 4 KiB name arrays. The views, caches, scene and shader state
were not matched: this is not a controlled loading-time/FPS speedup measurement.
These counters cover section workers, not all Voxy or the whole JVM. The live
run does not establish a whole-Voxy allocation rate below 50 MB/s.

Cumulative voxel scratch allocation now scales with W workers times C cells,
rather than S loaded sections times C. Peak live voxel storage already had W*C
complexity. Output decoding and meshing remain linear in input/cells/output;
neither became O(1) overall.

### Background journal reuse and release

During a 48.1299037-second sampled interval inside the sky-facing observation,
debug-log lines 123887-131699 show:

| Counter | Before | After |
| --- | ---: | ---: |
| Completed cache-only jobs | 36 | **7,755** |
| Journal append commits | 7,840 | 15,577 |
| Existing-index reuse | 59,503 | 73,737 |
| Journal recoveries | **660** | **660** |
| Recovery frame visits | **189,879** | **189,879** |
| Prefetch failure count | 9 | 9 |

Thus 7,719 additional cache-only completions occurred without another journal
recovery or header scan. Direct commit summaries in that interval record 7,725
appends across four existing journal identities, respectively 1,913, 2,051,
2,017 and 1,744. Each identity has exactly one recovery in the entire capture.
Worker snapshots independently identify committed keys processed by CacheOnlyTask.
Sampled counter deltas and event counts differ slightly because log events and
summaries are independently queued; they are not interchangeable counts.
See [index proof](deployment/cache258-index-proof.json).

At the view transition, retained prefetch indexes fell from 127 to 1. During the
sky interval, they stayed between 1 and 4, matching active frontier/jobs rather
than accumulating visited regions. Background work legitimately continued after
the observation; this does not require every retained count to become zero.

This proves index reuse under real serial background acquisition. The removed
journal-header rescan pattern was conditional Theta(K*R + K^2); during one active
region lifetime, recovery plus hash-index updates is Theta(R+K), excluding payload
encoding/hash/I/O, Coverage snapshot copying and deliberate replacement/recovery.
First recovery and returning to released regions still involve linear work.

## Recorded application errors and limits

Before the sky-facing interval, `prefetchFailures` rose 0 to 7 to 9. The recorded
last failure was `java.io.IOException: missing canonical cache-only section payload`.
This must not be hidden by the typed run's PASS status.

Source review identifies an unchanged cache-only worker guard in
`ClientSession.java:858`: DATA-kind REUSE replies are rejected before decoding or
`cache.save()`. DATA catalogue binding is resolved before dispatch, and the server
can legitimately send REUSE for a known matching payload. Foreground processing
has a cache reuse path; cache-only processing lacks the equivalent path. Neither
that worker nor the wire receiver/server code changed in this implementation.
There is no direct path from index retention to that pre-save guard.

The recorded last error therefore exposes an existing REUSE-handling gap rather
than a failed journal recovery. Individual failed ticket/status records are not
available, so the trigger for those particular replies and the cause of every
one of the nine failures remain unproven. No new failures occurred during the
quoted stable background interval. A separate protocol/worker repair was not
added to these two cache-local fixes or used to extend the testing allowance.

Eviction/compaction/error/cancellation branches were source-reviewed, but rare
races and explicit physical disk exhaustion were not deliberately exercised.
No p99.5/FPS gain, matched startup-time gain, full-world completion or pressure
acceptance is claimed. Normal cache and settings were not manually cleared or
rewritten; ordinary application persistence remained active.

## Size and final state

The [size ledger](cache_buffer_index_size_ledger_258.json) uses the same physical
line/file/folder scope as the previous implementation, including comments/blanks
and empty selected directories. Generated and project_audit files are excluded;
audit-only scripts, receipts and screenshots are additional audit artifacts.

| Scope | Before 257 | After 258 | Delta |
| --- | --- | --- | --- |
| Release sources | 37,716 lines / 183 files / 56 folders | 37,842 / 183 / 56 | **+126 lines**, no new source files/folders |
| All maintained sources including shared | 50,680 / 249 / 122 | 50,827 / 249 / 122 | **+147 lines**, no new source files/folders |
| Normal client JAR | 3,907,988 bytes | 3,909,781 | +1,793 bytes |
| Debug client JAR | 4,062,736 bytes | 4,065,180 | +2,444 bytes |

The game remained on build 258, its normal view was restored, the task-owned hold
was absent, settings hashes matched the initial observed hashes, and both backup
helpers were present through the final secondary SSH check. Server processes and
limits were unchanged. Implementation is committed locally; the earlier request
for explicit approval to push the internal plan to GitHub remains unanswered, so
no rejected remote push was retried or bypassed.
