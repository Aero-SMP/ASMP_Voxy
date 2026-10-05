# Approach 1: repair cache and download lifecycles

Status: implementation plan only; written 2026-10-05. No fixes, builds,
deployments or live tests are performed by writing this document.

Repository: `/home/aerosmp/Desktop/ASMP_Voxy_Cache_First_Updates`.
Branch: `feature/cache-first-background-updates`.
Audited starting commit: `215baf37`.

This repairs the implementation of
[the per-server bandwidth/world-cache plan](per_server_bandwidth_world_cache_prefetch_plan.md).
The recorded [implementation](per_server_bandwidth_world_cache_prefetch_implementation.md)
and [code review](per_server_world_cache_code_review.md) contain useful historical
receipts; they do not establish acceptance of the defects addressed here.
Their earlier completion wording must be qualified when this work is reported.

## 1. Scope and constraints

Keep the existing storage format, renderer, GPU selection, mesher, spatial
priority rule, cache-first publication and transport libraries. Repair the
existing owners and their state transitions. Do not introduce a storage actor,
merge all `Job`/`Demand` records, replace the cache/protocol architecture, add
dependencies, or add compatibility readers, format versions or migrations.
Use existing discovery frames for readiness information; client/native protocol
changes must be deployed together without maintaining an old handler.

Keep the accepted per-server total bandwidth and storage settings, Entire world
mode, exact ties, current-dimension ordering and low-priority coalesced refresh.
Add no memory, CPU, upload, pending-request or sections-per-second governor.
Reuse actual worker ownership and transport flow control. Estimates below are
planning estimates, not a new source-size limit.

- Original `ASMP_Voxy`, `ASMP_Voxy_Restart`, and Main remain read only.
- Implementation stays on the existing feature branch; do not merge into the
  original or switch its checkout to `main`.
- Preserve normal PC cache, source worlds, identities and unrelated settings.
  Do not clear, manufacture or manually rewrite cache journals for verification.
- Use the real PC only. Keep both independent backup SSH helpers and automatic
  debug-client updating operational.
- Preserve Testing's existing JVM heap arguments, `-Xms1G -Xmx4G`, and external
  Rust limit, `memory.max=999997440`, with swap disabled. Resolve actual processes
  and limits before live changes; historical PIDs are not current authority.
- No laptop work, integration/unit suites, 100-client runs or mutation-pressure
  testing. Pressure verification remains suspended.
- **All testing combined is limited to 600 seconds.** Section 7 defines the
  clock, priorities, restoration and honest treatment of skipped checks.

Expected production growth for the complete repair is approximately 500–1,000
readable source lines, primarily in existing files, with no new folder or
dependency expected. Measure actual net lines/files/folders and built artifact
bytes afterward; do not reduce readability to meet this estimate.

## 2. Required invariants

1. Unavailable settings, unknown inventory and ambiguous ownership never grant
   permission to reduce an allowance or delete cache.
2. An ordinary eviction uses current ownership, pins and strict retention rank
   within one transaction. Explicit allowance reduction relaxes rank only.
3. Every retry has a relevant state-change cause. Internal rollback, a previous
   attempt or persistent quota excess is not sufficient to repeat work.
4. Valid cached DATA/EMPTY is usable while stale, offline, awaiting publication,
   or unable to persist new metadata. ABSENT is not usable covering terrain.
5. Negative network answers require current source/publication authority.
   Availability waiting does not consume a body worker's admission credit.
6. Success means committed metadata or activated required detail, as appropriate;
   starting I/O or receiving bytes is not equivalent to either.
7. Tickets and processing predicates reject obsolete region/session ownership.
   Installed stale terrain remains until normal validated replacement publication.
8. Refresh obeys the current selected interval and gate before entering the
   transport. A frame already admitted must finish without corrupting the lane.

## 3. Storage and policy repairs

### 3.1 Explicit unavailable policy

In `ServerDownloadSettings`, distinguish absent settings for a genuinely new
server from a read/parse/validation failure. Defaults apply only to the former.
An error must not return a fabricated 500 MB policy as authoritative.

Carry unavailable policy into `RegionalMetadataStore` and `RegionalDiskBudget`.
Preserve the last valid persisted allowance where available; do not configure
or reconcile a replacement default. Pause new downloads and cache mutation.
Keep existing raw `.vxlink` lookup, world opening, reader pins and cached display
working independently. Do not merely skip binding and leave cache constructors
unable to open an existing namespace. Present a concise settings-error reason
through the existing Sodium tooltip/log path; preserve the damaged file.

An explicit valid policy reload/rejoin or user correction can restore authority.
Do not retry the same failed read continuously or silently overwrite it.

### 3.2 Shared deletion safety and persistent claims

Use one safety predicate in ordinary reservation, quota reconciliation and actual
victim claiming. It rejects unavailable policy, unknown inventory, ambiguous
ownership, known physical disk exhaustion and in-use/draining files.

Preserve all verified namespace claims, including conflicting ones, in the
existing ownership-record structure so restart reconstructs ambiguity. Physical
file bytes remain attributed once. Preserve the single ownership reader/format;
do not introduce an alternative reader or infer shared identity from endpoint IP.
Unresolved old claims preserve files and block destructive admission.

### 3.3 Transaction order and victim revalidation

Reuse `RegionalDiskBudget.changes` for configuration, ownership and retention
changes as well as selection/deletion/charging. Lock order is **changes, then
budget monitor**. Remove synchronized method wrappers before adding blocking
gate acquisition; never acquire `changes` while already holding that monitor.

Check the shared safety predicate when claiming each victim. Preserve strict
destination-better-than-victim comparison and equal-rank retention. Retention
updates cannot invalidate a deletion transaction midway through its decision.
Keep existing reader pins and draining protection. While holding `changes`,
never wait for another region writer: preserve `tryLock`/defer behavior.
Compression, decoding, meshing and ordinary reads remain outside this gate.

### 3.4 Reconciliation without lost notifications or spinning

Replace the discard-while-running behavior with a pending-event flag or event
generation. After a pass, repeat only if a relevant new event arrived. Checking
pending work and becoming idle must be atomic under the monitor.

Final reader/writer release schedules reconciliation when it makes a needed
victim eligible. These callbacks schedule work; they do not take `changes` while
holding the monitor. Persistent excess, pinned files or failed metadata alone
must not cause an immediate retry loop.

### 3.5 Physical-space pause and cleanup

Latch a physical-space failure before admitting another write transaction.
Account for existing append, compaction and metadata temporary-write transactions
across all accounts sharing this cache filesystem. Release their ownership in
`finally`, only after rollback/temporary cleanup and actual file accounting.

Capture a free-space baseline after all pre-fault transactions finish cleanup.
Another account's rollback must not appear to be external recovery. Resume only
after a meaningful space/policy change and a feasibility check for the pending
write, rather than comparing against its last small output chunk.

Use actual known growth or existing encoder-derived structural bounds. Do not
invent a fixed reserve or worst-case resource budget. If a bound rejects a
plausibly fitting compressed record, a streaming counting pass on the failure
path may obtain exact encoded size without staging or whole-record buffering.
Keep committed journals valid; inability to fit safe compaction is refusal/pause,
not an arbitrary journal reset. Cached reads and geometry remain usable.

## 4. Availability, prefetch and offline repairs

### 4.1 Usable ancestors and required quality

`ClientSession.applyLocalIndex` must consider an ancestor covering only when its
binding is usable DATA/EMPTY. An ABSENT record cannot suppress seeding a finer
cached cut. Reuse the existing usable-record rule rather than creating a second
cache traversal or format.

Track unmet visible pixel/zoom demand through the existing activation lifecycle,
independently of network receipt. Receiving a body does not open the refresh gate
while its required detail is unactivated. Clear ownership on activation,
retirement/coarsening and view replacement. Already active stale detail remains
sufficient. Keep the accepted greater-than-half nearby finer-cache gate.

### 4.2 Separate metadata goals and successful completion

In `WorldCacheDownloads`, track association and catalogue persistence separately.
Mark each committed only on a matching successful result. Catalogue success
cannot overwrite a failed association result, and `associationAttempted` cannot
permanently suppress an unsuccessful first `.vxlink` write.

Retain pending goals with their world/dimension/catalogue ownership. Retry on the
matching storage recovery or admission-change event, not every owner iteration.
Cancel obsolete goals when their owner retires. This also repairs the analogous
active-session failure disposal where necessary.

### 4.3 Publication authority and readiness

In native runtime/service owners, mark reconciliation pending before taking dirty
save bits. An in-progress build must remain represented even if the dirty map is
temporarily empty. Return ABSENT only when readable current source state,
publication/source sidecar and layout agree and relevant reconciliation is clear.
Serve valid stale DATA/EMPTY immediately while replacement builds.

Carry `SAVED_NOT_PUBLISHED` / `SAVED_PUBLISHED` state in the existing fixed-size
inventory frame with its saved bitmap and revision. Keep absence, unreadable and
unknown distinct. Process readiness transitions before the client's unchanged-
bitmap early return. Emit readiness after successful terrain/source agreement,
including metadata-only reconciliation without changed voxel ordinals.

Readiness represents availability of new saved coverage, not freshness of every
block edit. Do not publish a whole-region readiness transition or generation
announcement for every routine edit. Cached display and cached-quality summaries
must not be withdrawn because a newer publication is pending.

On cache-only NOT_READY, drop the body job/interest and park its region frontier.
Reoffer coarse roots on the relevant readiness transition; preserve the native
shared build/prioritization owner. Do not retain section subscriptions for an
entire unbuilt world, occupy idle-worker credit, or poll availability. Foreground
requests can still use valid stale positive terrain through the existing path.

### 4.4 Admission-specific deferred work

Distinguish quota/retention rejection, physical-space pause and source/I/O failure.
Reconsider admission-rejected regions when visible protection, available victims,
allowance or relevant space changes. Newly visible preparation must not inherit
a permanent old rejection. Revalidate admission before requesting and committing.

Use the lazy frontier and scoped deferred state; do not clear every source error
on camera rotation or repeatedly sort/rescan all world sections. Preserve exact
ties, current-dimension preference and existing worker ownership.

## 5. Refresh and region/dimension lifecycle repairs

### 5.1 Current cadence and first-byte admission

Replace the stored next deadline with the last actual batch start; derive
`last_start + current_interval` when selecting work. Existing policy notifications
wake the existing wait. No additional per-section timer is needed.

After preparation/catalogue/body waits, validate session, ticket, revision and
refresh gate. Also validate while polling admission of the first record byte,
which can itself wait for QUIC credit. Register wake-up before checking state;
hold owner locks only during the synchronous check/poll, never across an await.

Use distinct completion outcomes for sent, source-not-ready and cancelled claims.
A gate/ticket cancellation clears active ownership but retains latest eligible
dirty work; it must not use a completion path that discards that work. After the
first byte is accepted by Quinn, finish that frame to preserve persistent-stream
framing. Later changes coalesce to the latest replacement. Do not claim that
already admitted packet/stream bytes can be recalled.

### 5.2 Removal, reappearance and runtime replacement

On authoritative region removal, retire all foreground/prefetch wire tickets,
invalidate processing jobs and worker predicates, and reset the live generation
guard. Preserve installed stale geometry and valid cached bytes. Reappearance
issues fresh scoped tickets; obsolete lane replies/completions fail ownership
checks. Full inventory resets perform equivalent removal handling.

No persistent tombstone, generation migration or cache purge is needed: local
journals do not store native publication generations.

Detect source-root/layout runtime replacement and retire affected Voxy sessions
so reconnect creates fresh responders and discovery state. Preserve the
Minecraft connection and its shared pacing ledger. Border-only changes stay live.

Inventory all saved dimensions for which authoritative definitions are available,
without loading/generating chunks. Explicitly report saved dimensions with missing
definitions or unrepresentable heights and prevent an unqualified whole-server
completion claim. Extending the packed coordinate/layout range is a separate
design change, not silently included or declared solved by these repairs.

## 6. Implementation order and review

| Order | Work | Primary existing files |
| --- | --- | --- |
| 1 | Unavailable policy; shared deletion safety; persistent ownership claims | `ServerDownloadSettings.java`, `RegionalMetadataStore.java`, `RegionalDiskBudget.java`, existing Sodium presentation |
| 2 | Transaction order; reconciliation events; disk cleanup/recovery | `RegionalDiskBudget.java`, `CompletedSectionCache.java`, `CompletedSectionJournal.java`, metadata writer |
| 3 | ABSENT ancestor; metadata goals; admission-deferred work; activation quality | `ClientSession.java`, `WorldCacheDownloads.java`, `SectionDemandTable.java` |
| 4 | Negative authority; readiness; NOT_READY parking | native `regional/runtime.rs`, `regional/service.rs`, `regional/wire.rs`, `server.rs`; client `RegionalProtocol.java`, `WorldCacheDownloads.java`, `ClientSession.java` |
| 5 | Cadence; cancellable first-byte admission; removal/runtime replacement | native `server.rs`, runtime/service owners; client inventory and worker completion paths; Java dimension metadata bridge |
| 6 | Source review, matched build, staged deployment and capped live checks | existing build/deployment/debug helpers; no new test suite |

Before deployment, review lock order, every failure/cancellation cleanup path,
readiness with unchanged bitmaps, and how notifications reach parked work.
Keep checks O(1) where state permits, frontier operations O(log F), and existing
linear inventory/pose work explicit. Introduce no pairwise/quadratic world walk.
Remove replaced flags/paths in the same change instead of retaining alternatives.

Build matched normal/debug Java and native artifacts without executing test
tasks. Compilation time is separate from the testing allowance and establishes
compilation only. Locally stage/hash build artifacts and prepare rollback commands
before the live clock. Reading/copying live targets, creating a live cache/profile
scope and deployment verification belong inside the clock. Use debug builds and
automatic PC updating. Do not infer loaded identity from a staged filename. Keep
each receipt tied to actual loaded hashes and timestamps.

## 7. Strict ten-minute total testing limit

Use **one continuous monotonic 600-second clock**, starting immediately before
the first live setup/preflight/deployment-verification action. Include target and
SSH/limit verification, cache/profile setup, live replacement/restarts, connection
waits, observations, packet captures, comparisons, retries and normal restoration.
Parallel checks share the same clock. No per-case reset, fresh clock after a fix,
or extra window for client versus server. Offline review and compilation cannot
be relabeled live acceptance to evade this limit.

The following allocation is a priority schedule, not a promise that every case
fits. Skip later cases if startup consumes their time. Use already available
owned isolated cache/profile facilities; do not delete the normal cache.

| Aggregate elapsed time | Priority live activity |
| --- | --- |
| 0:00–3:00 | Resolve exact targets, verify two SSH helpers/limits, deploy matched debug artifacts and perform the required PC restart using an owned warm-cache namespace. If scoped Voxy-only blocking is already safely available, combine that restart with cache-before-HELLO/offline observation. Capture actual loaded hashes and an identifiable normal-FOV terrain view. |
| 3:00–5:00 | Release the Voxy-only hold, observe reconnect and zoom/turn in already cached terrain. Check cached activation, required-detail priority, normal Sodium settings, persistence pause reasons and absence of immediate redundant missing requests for identified cached keys. |
| 5:00–7:00 | At most one short reversible check: isolated quota/rotation recovery, or a few saved changes to one existing FULL Testing chunk if the target and original state are verified and restoration fits. Check cadence/gate traces where naturally exercised. Prefer no world mutation if timing or client visibility is uncertain. |
| 7:00–9:00 | Stop new scenarios. Restore all task-owned holds, policy/profile/camera changes and any original saved block state/force-load lease. Return the normal cache and endpoint; confirm ordinary cached rendering and both backup helpers. |
| 9:00–10:00 | Finish restoration/close captures and record outcomes. No new scenario or evidence-chasing retry. |

Stop introducing reversible changes by minute 7 or earlier if cleanup may take
longer. Do not begin a terrain edit or another JVM restart unless restoration can
reasonably finish within the remaining time. Pre-record the original state and
rollback action before each change. A slow startup or failed check consumes the
same allowance; it does not justify restarting the clock.

At 600 seconds, stop testing. Never abandon mandatory safety restoration merely
to meet a clock: an exceptional cleanup overrun performs restoration only, is
reported as an overrun, and does not authorize further observations or retries.
The normal plan must finish cleanup inside the limit.

Physical disk exhaustion is attempted only if a safe disposable constrained
destination already exists and fits the same window. Never fill the PC system
disk, create artificial cache journals or substitute a simulated quota failure
for real filesystem exhaustion. Do not corrupt the normal settings file; a
settings-error case requires an already isolated owned policy/profile scope.
Do not add a second restart merely to chase that case.

Capture actual wire observations only if existing tooling fits the window.
Label inferred IP overhead, submitted-byte counters and observed datagrams
separately. If the real link does not saturate a cap, record that limiting under
excess demand remains unverified. Collect available QUIC wait/loss/RTT/window
statistics without changing the user's impairment settings. No rate sweep or
whole-world acquisition run is required inside this short check.

## 8. Acceptance, reporting and remaining limits

Source-reviewed completion requires all scoped repairs above, consistent failure
ownership, no superseded duplicate path, matched builds and actual source/artifact
counts. Live verification is deliberately limited to what the 600-second window
actually establishes. Do not call the original plan's full acceptance complete.

| Check | Required evidence / honest limitation |
| --- | --- |
| Cache-first/offline | Actual loaded identity, local activation before HELLO, and identifiable terrain screenshot. A warm in-process hold is not a full offline JVM restart; report which happened. |
| Fine cached zoom | Identify relevant cached keys and subsequent activation; no redundant missing request where observed. A uniform extreme-zoom image is insufficient. |
| Quota/rotation or saved edit | Exact isolated scope, original state, event traces, outcome and restoration. An edit needs native/cache content comparison and visible/GPU identity to claim on-screen delivery. |
| Settings/ownership/eviction safety | Source paths must enforce the invariants. Claim live reproduction only if the actual error/ambiguity/race was exercised. |
| Disk/metadata recovery, NOT_READY and negative authority | Source review is required; live proof remains deferred unless the condition occurred and recovery was captured. No synthetic result may stand in for it. |
| Cadence/gate | Distinguish batch/publication timestamps, first-byte admission and rendered activation. One final cache binding does not prove every interval or absence of intermediate delivery. |
| Performance and total cap | Short observations only. No p99.5/FPS/allocation improvement, saturation, perfect rendering or adverse-network pressure claim without supporting measurement. |
| Dimensions/completeness | Explicit excluded/unknown states. A copied Overworld's coarse coverage is not all LODs, all dimensions or full-square rendered continuity. |

Save a compact receipt with the clock start/end and elapsed time, artifact hashes,
attempted checks, failures, skipped checks, restoration and remaining uncertainty
under `project_audit`. An operator/harness PASS is scoped to its demonstrated
predicates; compilation, receipt creation and a nominal completed frontier do
not establish terrain correctness. Preserve historical failed receipts.

Report implementation status separately from live proof. The ten-minute limit
supersedes the earlier longer acceptance schedule for this repair; untested rare
branches and suspended pressure verification remain explicit outstanding evidence.
