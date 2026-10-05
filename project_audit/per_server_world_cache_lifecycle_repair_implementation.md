# Approach 1: cache and download lifecycle repair implementation

Status: **implemented, matched build257 deployed to Testing and automatically
installed on the real PC**. Written2026-10-05 against feature plan HEAD
`3a34436a5274f0f65c262477ba3d4555c1de484d`; earlier defect audit `215baf37`
is a separate baseline. Section9 records limited live proof and the failure
to keep the total live clock within600 seconds. Broader acceptance remains open.

Repository: `/home/aerosmp/Desktop/ASMP_Voxy_Cache_First_Updates`.
Branch: `feature/cache-first-background-updates`.

This records the scoped implementation of
[the lifecycle repair plan](per_server_world_cache_lifecycle_repair_plan.md).
It repairs the existing owners from
[the per-server bandwidth/world-cache plan](per_server_bandwidth_world_cache_prefetch_plan.md).
It does **not** mark that original plan's full acceptance complete. Historical
build-256 and copied-Main receipts describe earlier artifacts and cannot prove
this repair's behavior.

## 1. Scope retained

The terrain journal and catalogue storage representations, mesher, renderer,
pixel/zoom selection, spatial ranking and transport libraries remain in use.
The changes repair policy authority, file transactions, metadata outcomes,
availability waiting and network/publication ownership. They introduce no
compatibility reader, format migration, protocol-version negotiation, dependency,
storage actor or new resource governor.

The existing selected per-server bandwidth and disk allowances, Entire world
mode, exact retention ties, current-dimension preference and low-priority
coalesced refresh remain the governing policies. Existing worker ownership and
transport flow control provide admission; no sections-per-second limit is added.

Original `ASMP_Voxy`, `ASMP_Voxy_Restart`, Main and the normal PC cache remain
outside this implementation's mutation scope. Existing Testing heap and native
external memory ceilings remain requirements for later deployment; a source
statement here is not verification of their current running values.

## 2. Settings, ownership and storage transactions

### Unavailable policy and default provenance

[`ServerDownloadSettings.java`](../src/main/java/me/cortex/voxy/client/config/ServerDownloadSettings.java)
distinguishes an actually absent settings file from read, parse or validation
failure. Failure leaves the store unavailable and preserves the file; ordinary
getters do not repeatedly retry its read. Explicit reload/rejoin or user
correction supplies a recovery event. Storage authority is unavailable rather
than a fabricated 500 MB replacement policy. The existing Sodium presentation
exposes the pause reason.

Binding passes the typed policy through
[`RegionalMetadataStore.java`](../src/main/java/me/cortex/voxy/client/lod/RegionalMetadataStore.java)
into the disk owner. It still permits raw `.vxlink` discovery, opening existing
worlds and reader pins when downloads and persistence are paused.

A new-server default is a transient placeholder until owned inventory resolves
whether a persisted `.vxowner` allowance exists. `storageSelected`,
`storageResolved`, `retainStorageBytes` and the account's persisted-allowance
provenance prevent that placeholder from shrinking an already known allowance.
An explicit user selection takes precedence under the settings lock. Inventory
resolves genuine-new defaults and persists resolved settings once, outside the
disk-owner monitor. These are in-memory provenance fields; no alternative
settings or ownership-record reader is introduced.

### Shared mutation safety and persistent conflicts

[`RegionalDiskBudget.java`](../src/main/java/me/cortex/voxy/client/lod/RegionalDiskBudget.java)
uses one safety predicate for reservation, reconciliation and actual victim
claiming. Unknown/failed inventory, unavailable policy, ambiguous ownership and
latched physical-space exhaustion block destructive admission. Existing files
are attributed once; all verified namespace claims, including conflicting
claims, are retained in the existing `.vxowner` structure. Restart can therefore
reconstruct the conflict rather than silently assigning a new owner.

There is one intentional, narrow preservation exception: **newly pending
verified conflict claims** may update that account's `.vxowner` record while
ambiguous, even if those fully charged metadata bytes exceed its selected
allowance. This exception requires `ambiguous && claimsDirty` and an ownership
record path. It does not select terrain victims or authorize terrain/catalogue
writes; policy, inventory and physical-space checks still apply. Successful
publication clears `claimsDirty`. Already reconstructed ambiguous accounts and
ordinary dirty anchor/settings updates have no general ambiguity exception.

### Lock order and ordinary eviction

Configuration, namespace ownership, retention, victim selection, deletion and
charging share `changes`. The order is `changes`, then the budget monitor;
release callbacks schedule work without acquiring `changes` under that monitor.
An ordinary victim is checked again against current ownership, pins, draining,
visibility and strict destination-better-than-victim rank before deletion.
Explicit allowance reduction relaxes rank only. Equal-rank retention remains.
Another region writer is acquired with `tryLock`, never waited for while the
capacity-change gate is held. Normal compression, decoding, meshing and reads
remain outside that gate.

### Event-driven reconciliation and physical-space recovery

The reconciler records an event generation while a pass runs, then checks for
relevant new work and becomes idle atomically. Persistent excess or a failed
ownership write alone does not restart the pass. Reader/writer release only
notifies when it makes a needed terrain victim eligible or reclaims useful
space; ordinary cache reads and growing successful appends are not retry events.
A write's own capacity rejection does not grant itself a retry epoch.

The filesystem-wide mutation count covers waiting writers, append/compaction
transactions, metadata temporary writes, ownership-record writes, admitted
policy deletions and inventory pending-file cleanup. Ownership lasts through
rollback, temporary cleanup and actual size reconciliation. A physical-space
failure latches before new transactions; the free-space baseline is established
after those pre-fault transactions drain. Their cleanup cannot masquerade as
external recovery. Resumption requires a relevant space/policy change and a
feasibility check for known total growth.

[`CompletedSectionCache.java`](../src/main/java/me/cortex/voxy/client/lod/CompletedSectionCache.java)
uses encoder-derived compressed bounds plus actual journal framing. Only a
bound-refusal path streams a counting pass to obtain exact output length; it
does not stage a whole record or run a second encoding pass on normal writes.
Compaction preserves committed records and is refused when safe replacement
does not fit, rather than resetting the journal.

[`CompletedSectionJournal.java`](../src/main/java/me/cortex/voxy/client/lod/CompletedSectionJournal.java)
opens existing journals read only for recovery and cached reads. It does not
truncate a torn tail merely because inventory/policy is unknown. A writer owns
later recovery truncation; binding/footer publication and atomic metadata
replacement recheck mutation guards. Failure closes the owned handle and
reconciles surviving tails before releasing filesystem ownership.

## 3. Cache quality, metadata goals and deferred work

[`ClientSession.java`](../src/main/java/me/cortex/voxy/client/lod/ClientSession.java)
seeds the highest usable cached nodes of a branch. Only DATA/EMPTY ancestors
cover it; ABSENT cannot conceal a finer valid cached cut. Valid cached content
continues toward activation before server metadata is available.

`unactivatedRequired` follows required demand into the GPU activation lifecycle.
Network receipt alone cannot open refresh while visible required pixel/zoom
detail is still unactivated. Activation, retirement/coarsening and view ownership
clear the appropriate requirement. Already installed stale detail remains
sufficient. The existing greater-than-half finer-cache rule remains in place;
this repair does not replace its ranking policy.

Association and catalogue persistence are separate, retained goals in
[`WorldCacheDownloads.java`](../src/main/java/me/cortex/voxy/client/lod/WorldCacheDownloads.java)
and the active session. `.vxlink` success and catalogue success are recorded only
after the matching successful result. Failed association cannot be hidden by a
later catalogue outcome. Pending goals carry world/dimension/catalogue and
policy ownership; admission/recovery or a new goal can retry them, while general
I/O failure does not spin on every owner iteration. Retiring the owner cancels
its obsolete work.

Deferred regions distinguish quota/retention rejection, physical-space pause
and source/I/O failure. Admission generations reoffer rejected work after a
relevant policy, retention, victim-release or reclamation event. Physical
recovery reoffers its scoped keys. A camera turn does not clear unrelated source
errors. Admission is checked before requesting and again during persistence;
the lazy frontier and worker owners remain the scheduling mechanism.

## 4. Negative authority and publication readiness

[`runtime.rs`](../rust-server/src/regional/runtime.rs) captures reconciliation
ownership before taking completed-save dirty bits. That per-region pending
state survives failed source work, so a temporarily empty dirty map cannot make
an old publication authoritative. An ABSENT entry requires current readable
source state, reconciled source stamp, matching generation/marker/header and
supported layout, with no relevant dirty/reconciliation work. Unknown or
unreadable source remains NOT_READY. Known whole-region absence is distinct.

Valid stale DATA/EMPTY remains immediately serviceable. A compact, validated
saved-coverage stamp can continue to describe older positive coverage while a
routine replacement is being built; negative/freshness authority still requires
the current exact source/publication agreement. This avoids withdrawing cached
coverage or broadcasting a pending/ready cycle on every ordinary block edit.

[`service.rs`](../rust-server/src/regional/service.rs) converts an old absent
entry without current negative authority to NOT_READY and registers one shared
region publication waiter. Source prioritization remains with the shared native
builder when the cache client drops its individual request.

The existing 149-byte inventory frame retains this current-only state mapping:

| Value | State | Meaning |
| --- | --- | --- |
| 0 | SNAPSHOT_BEGIN | Begin authoritative scoped inventory replacement. |
| 1 | SAVED_PUBLISHED | Saved footprint has usable published coverage. |
| 2 | UNREADABLE_REGION | Source exists but cannot be authoritatively read. |
| 3 | REMOVED_REGION | Authoritative regional removal. |
| 4 | SNAPSHOT_COMPLETE | Finish the scoped snapshot, subject to its failures. |
| 5 | FAILURE | Inventory failed; completeness is unavailable. |
| 6 | SAVED_NOT_PUBLISHED | Saved footprint exists but required coverage is pending. |

Snapshots use publication readiness rather than labelling every readable source
published. Successful source/terrain agreement advances revision and wakes a
coalesced waiter, including metadata-only reconciliation with unchanged voxel
ordinals. This is availability of new saved coverage, not a promise of immediate
freshness for every edit.

The client processes readiness before its unchanged-bitmap early return.
Cache-only NOT_READY retires the body job/interest and parks its region frontier,
freeing admission for other work. A matching ready event reoffers coarse roots.
Per-job dispatch revision handles a ready event that overtook an older NOT_READY
reply on another stream. It adds neither polling nor whole-world per-key watches.

## 5. Refresh admission and removal/replacement ownership

[`server.rs`](../rust-server/src/server.rs) derives cadence from the last actual
refresh batch start plus the **current** interval. Policy notifications wake the
existing waiter. A cancelled queued claim does not advance the actual start.

Session, ticket, revision, refresh gate and ownership are rechecked after section
preparation/catalogue/body waits and while polling the first record byte for
QUIC credit. The existing notification is registered before the predicate.
Owner locks cover only the synchronous check/poll, never an await. `Sent`,
`NotReady` and `Cancelled` have separate completion paths; cancellation clears
active ownership but retains the latest eligible dirty work. Once Quinn accepts
the record type byte, its complete frame is finished to preserve stream framing.
This is application/transport admission, not recall of already queued packets.

Authoritative removal and full inventory replacement retire foreground and
prefetch wire tickets, processing jobs and revision predicates, and reset the
live generation guard. Reappearance issues new scoped tickets. Local journals
do not store those native generation high-water marks, so no persistent
tombstone, generation migration or cache purge is introduced.

Installed stale geometry and valid cache bytes remain available. There is also
an intentional activation-race treatment: an in-flight validated candidate may
have already activated when removal is observed. The existing publication
outcome decides this. An ACTIVATED outcome becomes the installed stale fallback;
an unactivated/returned candidate is released normally. This does not restore
its old wire ticket or authorize obsolete worker persistence.

Source-root or layout replacement retires the old native runtime transaction and
affected Voxy sessions; reconnect builds new responders and discovery state.
Minecraft remains connected and retains the same authenticated route/pacing
ledger. Border-only changes reuse the runtime. Sessions remember runtime binding
for discovery-only dimensions as well as terrain responders. Delayed replacement
events point to the old runtime and cannot close a fresh binding.

After `runtime.refresh` finishes, `refresh_all` checks that the runtime Arc is
still current and holds the service read guard through its announcements.
Results from superseded runtimes are suppressed or ordered before replacement.
The guard is not held across refresh/retirement maintenance, avoiding the IPC
retirement deadlock that such a broad guard would introduce.

## 6. Saved dimensions and matched current protocol

[`VoxyServer.java`](../server/src/main/java/me/cortex/voxy/server/VoxyServer.java)
enumerates saved terrain directories at startup and resolves authoritative
`LEVEL_STEM` definitions for unloaded saved dimensions without loading or
generating chunks. Loaded dimensions publish actual 32-block base-section
heights and border metadata; saved-only definitions follow the authoritative
server border. A legal intermediate path component named `region` is traversed;
only confirmed MCA terrain directories stop recursion.

Missing definitions, unmappable terrain paths, directory-scan failure,
unsupported height and unavailable border metadata produce explicit exclusions.
The current packed key's height support is `[-4096, 4096)` blocks; arbitrary
taller dimensions require a separate key/layout design. Exclusion is not full
support and prevents an unqualified whole-server completeness claim. Externally
created unloaded saved directories after startup are not claimed to have an
independent live filesystem rescan; normal loaded-definition/save updates remain.

[`ChunkSaveNotifications.java`](../server/src/main/java/me/cortex/voxy/server/ChunkSaveNotifications.java)
coalesces current definitions and exclusions, replays them to the owned child and
merges failed batches only when they still match the latest owner. Native IPC
opcode 5 carries UTF-8 u16-length name and reason; successful opcode 4 clears
the exclusion. It does not load or alter source terrain.

The manifest in [`wire.rs`](../rust-server/src/regional/wire.rs) and
[`RegionalProtocol.java`](../src/main/java/me/cortex/voxy/client/lod/RegionalProtocol.java)
now appends an excluded count and name/reason pairs. There is one matched current
reader/writer, without an old manifest fallback or version negotiation. Cache
records and stored catalogues retain their existing representation. The static
single-consumer CLI manifest pattern was adapted for compilation; it was not
executed and is not a replacement for the suspended 100-client pressure proof.

## 7. Plan invariant coverage from source

| Required invariant | Implemented ownership/check | Evidence limit |
| --- | --- | --- |
| Unavailable/unknown/ambiguous state cannot reduce allowance or evict | Typed policy/default provenance and shared mutation safety; narrowly counted new conflict-claim metadata exception | Error/ambiguity live reproduction is still pending. |
| Ordinary eviction uses current pins/ownership/strict rank | `changes` transaction and victim revalidation; reduction relaxes rank only | Concurrent race behavior is source-reviewed, not live-exercised here. |
| Retry requires a relevant new event | Reconciler event generation, admission/recovery epochs, goal ownership and busy-victim release | Persistent-error recovery is not proved by ordinary success. |
| Cached DATA/EMPTY remains usable; ABSENT does not cover | Usable ancestor seeding, cached reads without mutation, installed stale fallback | Cached zoom needs identified keys plus activation evidence. |
| Negative authority and waiting admission are correct | Native reconciled source authority, fixed inventory readiness, dropped/parked cache-only NOT_READY | A saved-slot/build race must occur to prove this live. |
| Success means commit or required activation | Separate metadata goal results and activation-owned refresh gate | Receipt/body counters alone are insufficient. |
| Obsolete region/session ownership is rejected | Fresh wire tickets/revisions, all-job retirement, runtime replacement reconnect and superseded announcement suppression | Activation-race fallback intentionally retains already-valid stale terrain. |
| Current refresh policy holds until first-byte admission | Current interval derivation, synchronous guarded Quinn poll and distinct cancellation | Cannot recall bytes admitted earlier; traces must distinguish that boundary. |

Source work uses existing keyed owners and compact per-region state. Frontier
operations remain logarithmic; inventory, pose reclassification and some existing
owner passes remain linear in their maintained sets. This repair does not claim
all operations became O(1), and adds no pairwise whole-world traversal.

Source review found and corrected two additional storage gaps during this pass:
admitted policy deletion/pending cleanup had to participate in the physical-pause
drain, and unrelated reader releases had to stop retriggering dirty failed owner
persistence. The final inspected paths include both corrections. No additional
material defect was identified in this scoped source review; that is not proof
that all races or rendering defects are absent.

## 8. Explicit remaining limits

- No claim of higher FPS, improved p99.5, allocation rate, bandwidth saturation,
  perfect modded meshing, instant uncached zoom or flawless rendering follows
  from these lifecycle fixes or a successful compilation.
- Native IP ledger counters describe successful transport submission. Independent
  observed UDP and inferred IP overhead must remain separately labelled; neither
  an impairment process name nor a configured cap proves actual link conditions.
- Copied Main coarse receipts do not prove all LODs, all dimensions or full-square
  rendered continuity, and were collected with earlier artifacts.
- Tall unrepresentable dimensions remain explicitly excluded; extending the
  packed key/layout domain is outside this repair.
- Rare policy corruption, ownership ambiguity, disk exhaustion, source failures,
  negative-authority races and publication/removal races need actual captured
  occurrence before being labelled live-verified.
- The 100-client adverse-network pressure run remains suspended. No unit or
  integration suites or synthetic disk-full proof substitute for it.

## 9. Build, deployment, counts and scoped live evidence

Implementation is source-complete for this lifecycle repair on
`feature/cache-first-background-updates`, against plan HEAD `3a34436a`.
The earlier full-world/performance/pressure acceptance is still open.
Original/Main worktrees were not edited or controlled in this implementation.
Only Testing and the real GIORKOSPC profile were live targets.

### Matched artifacts

`CARGO_BUILD_JOBS=1 ./gradlew --offline buildAll --console=plain` passed in 26s;
no test tasks were executed. The initial client compile failed with12 errors in
two inventory loops: regional members expose a base demand record. Those loops
now look up the typed client demand by key without casts or new collections.
The failed compilation log remains alongside the successful log. Native bins
also received compile-only cargo checks during implementation. Compilation
establishes type/package consistency, not runtime correctness.

| Artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| Client debug257 | 4,062,736 | `b4599a9c0936c92849be6e837601dc04e132db5d17e529dad765d001d79d5c3d` |
| Server debug257 | 1,771,102 | `51a6046ad551d7486b691d228ea76bc1a024ec9432f754efe762f3e9105636da` |
| Native release | 3,462,352 | `85dd01efb215ec1c33629b8bf1f8cbd7805f128e46b843c0bae1827215f06a01` |

The server JAR's embedded native executable matches that native SHA.
Normal artifacts were also built (client 3907988 bytes; server 1705093 bytes);
[normal artifact hashes](deployment/normal-artifacts257.json) are separate from deployed debug identities.
Compared with staged256, the client grew19,607 bytes, the server25,359 bytes,
and native43,840 bytes. See [staging](deployment/staged257.json),
[build log](deployment/build257.log) and [runtime receipt](deployment/runtime257.json).
Client download:
`build/libs/ASMP_voxy-0.2.257-beta+1.21.1-neoforge-debug.jar`.
The automatic feed is `build/libs/debug-clients/MGengine/`.

### Exact source scope and deviation from estimate

The same physical-line ledger as256 counts comments/blanks, Java/Rust/shaders,
selected main/debug/fixture/tool roots, tracked and untracked sources, and empty
selected directories. Generated and project_audit trees are excluded. Shared
sources remain separately counted for symmetry with the historical helper.
Two identical path/content snapshots bracketed the final count.

| Scope | Before256 | After257 | Change |
| --- | --- | --- | --- |
| Release source, existing helper scope | 36,326 lines /183 files /56 directories | 37,716 /183 /56 | +1,390 lines; no file/directory growth |
| All maintained roots plus shared | 49,290 lines /249 files /122 directories | 50,680 /249 /122 | +1,390 lines; no file/directory growth |
| Empty selected directories | 0 | 0 | 0 |

Audit-only deployment/verification helpers and receipts are additional audit
files, outside this explicitly unchanged source-count scope. No production
file/folder/dependency was added. See
[full ledger](per_server_world_cache_repair_source_count_20261005.json).

The +1,390 lines exceed the500–1,000-line planning estimate. The extra work is
explicit lifecycle state, all-account filesystem cleanup tracking, default
policy provenance, renderer-publication preservation, saved-dimension discovery
and guarded first-byte admission. The estimate was not a ceiling. The repair
keeps readable code instead of compression or source-count tricks.

### Running identities and preserved limits

Testing restarted only through Astolfo server_id `voxy_testing`.
Java PID4176932 has initial heap1,073,741,824 and maximum4,294,967,296 bytes.
Native PID4177620's executable SHA matches staging. Existing cgroup
`memory.max=999997440` and `memory.swap.max=0` were preserved. At535.6s,
`memory.current=586133504`, `memory.peak=654311424`, and all OOM/max counters
were0. This is an observed bounded run, not pressure/OOM-proof acceptance.
Unrelated Testing mod hashes were checked unchanged by deployment.
Rollback server jars/logs were preserved under Testing's deployment log folder.

The PC updater restarted game PID31200 into257 PID24428, then normal-cache
restoration restarted it to PID25688. Both backup helper PIDs19916 and22444
were unchanged in final state. Primary and secondary pinned SSH paths reached
GIORKOSPC. A quoted secondary hostname command initially failed because the
hostname was interpreted as a PowerShell command; the subsequent encoded
secondary state query succeeded. Loaded client identity was established by the
typed CLIENT_READY SHA fields, not merely its filename: reconstructing the four
64-bit fields gives the exact staged client hash above.

Endpoint remained `play.aerosmp.com:25587` (Minecraft), native UDP25787.
No impairment configuration, source terrain, world identities, normal cache or
unrelated client mods were cleared/rewritten for this check.

### Live clock, successes and testing failure

Clock: `2026-10-05T17:52:46.249996Z` to
`2026-10-05T18:03:49.265907Z`, recorded663.016s. See
[clock receipt](deployment/live257-clock.json).
**The600-second testing requirement was missed by63.016s.** No clock was reset.
The final normal-cache confirmation was submitted before the deadline, but
BEGIN_RUN was still outstanding when its immediate checkpoint was sent.
Subsequent retry used the wrong step number. A late optional screenshot retry
crossed600s; this was a bad deviation, not permitted extra testing. Its screenshot
is excluded from acceptance. The restoration run was closed at18:03:07.612Z;
clock recording followed. No further live targets were read or tested afterward.
The audit step helper now accepts an explicit --clock and rejects observations
without sufficient remaining time, clips waits to that clock and fails immediately
on rejected commands. Future orchestrations must pass it and await CLIENT_READY
before step1. These audit-only repairs received syntax review, without another live run.

Accepted within-window run:
`0eaa3caf-fbf5-462f-8d7d-1900bcaf63f3`. Typed controls completed13 steps; its
operator PASS is scoped to command delivery and these observations only.

| Observation | Actual evidence | Limits |
| --- | --- | --- |
| Full JVM restart into existing isolated warm cache with Voxy-only hold | PC owned namespace `test-cdce8542-bb60-4a2d-b418-eeffd878ddff`; checkpoint step1 has cacheHits8721, active8678, networkBytes0 and regional connectionEpoch0. Typed loaded SHA matches257. | Minecraft remained connected; this proves Voxy-before-HELLO cached activation, not completely offline Minecraft. |
| Identifiable normal-FOV scene before HELLO | Step2 screenshot uploaded successfully and viewed: river/lake, forested hills and rail/quarry scene. | Not a full-square, all-block, all-dimension correctness proof. |
| Reconnect and zoom | Hold released step3; regional connectionEpoch1 by step5; zoom FOV changed from76.99999 to19.30639 degrees. Step5 screenshot uploaded and viewed; active8678 and terrain networkBytes0 at that snapshot. | Foreground quarry/water dominates zoom; no newly activated finer cached LOD key was isolated. Fine-LOD instant-zoom and absence of redundant key requests remain unverified. |
| Sodium settings | Step8 screenshot uploaded/viewed; Voxy257, GPU Memory2GiB, render distance208, pixel size63, interval2s, total bandwidth1Mbps, storage500MB. | Unavailable-policy UI was not deliberately triggered. |
| One isolated allowance reduction | Step10 applied100MB to the clone; state at341.8s counted99,961,073 physical bytes, below100,000,000; original500MB restored step12. | Before setup clone counted101,526,000; intervening downloads mean this is not a per-file eviction race proof or disk-full simulation. |
| Normal cache preservation | All227 path/length/SHA tuples match, totaling261,695,964 bytes, before normal cache reactivation. | Initial PowerShell comparison falsely mismatched an array wrapper. Raw failures preserved; exact normalization correction is separately recorded. |
| Final restoration state | At543.4s PC state: normal `off` lease, game PID25688/hash257, hold absent, original1000kbps/500MB policy and both original helper PIDs. | Late optional restoration screenshot excluded. Ordinary normal-cache visual correctness is not newly accepted from it. |

Normal preservation correction:
[normalized receipt](live_client/pc257-normal-comparison-corrected.json).
The saved baseline was a `value/Count` wrapper from Windows PowerShell;
comparing the wrapper with individual records created false differences.
Reconstruction from the saved actual records matches all paths, lengths and
hashes. No baseline or cache bytes were altered to obtain that result.

Snapshots also retained unresolved coverage counts:212 in the offline view,
115 later, and publication-failure counter9→20. These counters are recorded
without equating all rejected publication attempts to permanent visual holes;
they prevent a claim of flawless rendering or complete coverage. The screenshot
shows terrain use, not proof that every selected demand is satisfied.

Both original configuration files were backed up before changes and copied
back; the in-memory storage policy was first reset to500MB. Normal rejoin may
legitimately refresh derived world-size/anchor metadata. Camera XYZ/yaw/pitch
were not changed; zoom override/settings screen were released. The existing
warm clone remains, with its legitimate test-scoped eviction/new data. Normal
cache was not deleted, edited or substituted. Task-owned transport hold was
removed, remote normal-cache `off` lease published, and both debug runs closed.

### Deferred live evidence

No disk exhaustion, damaged-settings mutation, ambiguous-owner reproduction,
negative/source-readiness race, NOT_READY parking wake race, dirty refresh
cancellation, region/root replacement race, tall-dimension acceptance, packet
capture/rate saturation sweep, source block edits, 100-client load, or mutation
pressure run was performed. No performance improvement/p99.5/allocation,
whole-world/full-square/all-LOD rendering, or perfect mesh claim is made.
The normal warmed-cache workload did not establish traffic-cap saturation.
These are explicit remaining evidence limits, not pending source repairs hidden
behind a completion claim.
