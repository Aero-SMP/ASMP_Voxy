# Server streaming: BBR and cheaper source inspection

Status: implementation plan only, written 2026-10-07. Writing this document does
not implement, build, deploy or start a live experiment.

Repository: `/home/aerosmp/Desktop/ASMP_Voxy_Cache_First_Updates`.
Branch: `feature/cache-first-background-updates`.
Reviewed baseline: `5aaa9c2216c274307958a1bf1c3c787ee0d6490f`.
The checkout was clean before writing this plan. Refresh source and runtime
identities before implementation; historical build numbers are not loaded-state
proof.

Implement approaches **1, 2, 4 and 5** from the server-streaming options reviewed
with the user:

| Approved approach | Complexity | Benefit | Unavoidable tradeoff severity |
| --- | ---: | ---: | ---: |
| 1. Select built-in server-side BBR with the existing startup window | 0 | 8 | 3 |
| 2. Recognize unchanged compressed source chunks before terrain decoding | +2 | 6 | 2 |
| 4. Inspect semantic terrain without materializing full cell arrays | +2 | 5 | 1 |
| 5. Reuse regional readers/scratch and share concurrent generation opens | +1 | 4 | 1 |

Scores are estimates. Complexity means net source/file/folder/binary growth, not
implementation difficulty. Benefits are not additive or promised speedups.

## 1. Evidence and intended behavior

The [30-client live results](server_timings_100_rust_clients_live_pressure_implementation_results.md)
established these priorities:

- Under approximately 300 ms unloaded RTT, 10% loss per direction and a 1 Mbps
  actual-IP-traffic cap per direction per client, useful terrain averaged
  118.818 kbps/client; the unimpeded control with the same cap averaged
  832.215 kbps/client.
- The impaired sampled congestion-window median was 6,377.5 bytes, below the
  approximately 37,500-byte bandwidth-delay product of a 1 Mbps/300 ms path.
  This supports a transport restriction, without proving CUBIC is its sole cause.
- Source NBT processing consumed 46.748/50.631 thread CPU-seconds in the selected
  impaired/control windows. That stage includes full section-cell construction
  and semantic fingerprint serialization, not just NBT deserialization.
- Regional-directory reads and compressed reads occasionally stalled, despite
  cheap typical request processing. Duplicate opens and per-chunk file/buffer
  work can be reduced without claiming all host disk stalls are avoidable.
- No sampled fake request waited for source data. Source optimizations primarily
  target CPU, allocation and repeated I/O; they are not proven to repair the
  impaired-link throughput shortfall themselves.

The server already avoids resending an identical terrain body when the client
reports matching cache contents. It already coalesces save notifications, skips
some unchanged source slots, rebuilds changed groups and ancestors incrementally,
and prefers coverage over freshness. Preserve these behaviors.

The desired improvement is faster missing-terrain delivery with less saved-source
checking work, while preserving terrain identities, generated geometry inputs,
cache-first use and safe handling of concurrent Minecraft saves.

## 2. Scope and preserved state

Production edits should remain concentrated in existing native files:

- [server.rs](../rust-server/src/server.rs): transport factory and controller identity.
- [anvil.rs](../rust-server/src/anvil.rs): captured regional reader, exclusive
  scratch, normalized section decoding and semantic-only inspection.
- [crc.rs](../rust-server/src/crc.rs): incremental xxHash64 with identical results.
- [source.rs](../rust-server/src/regional/source.rs): disposable compressed-content
  companion and shared source serialization/ownership digest.
- [runtime.rs](../rust-server/src/regional/runtime.rs): inspection shortcuts,
  companion publication and shared in-flight regional opens.
- [builder.rs](../rust-server/src/regional/builder.rs): use the shared reader and
  decoder while preserving final source validation and generation publication.
- [diagnostics.rs](../rust-server/src/diagnostics.rs): focused debug observations.

The debug-only Rust pressure harness may change in
[live_pressure.rs](../rust-server/src/live_pressure.rs) and
[pressure_relay.rs](../rust-server/src/pressure_relay.rs) to correct measurement
fidelity. Keep external operator/analyzer changes in the new toolkit task directory.

Preserve Main, ASMP_Voxy, ASMP_Voxy_Restart and archived work read only. Deploy only
the exact Voxy_Testing artifact selected for an authorized implementation run.
Preserve Minecraft source files, existing `.vxsource` and `.vxregion` formats,
world/dimension identities, catalogues, client cache/settings, rendering/VRAM
selection, protocol and normal client auto-update behavior.

Do not add protocol versions, legacy readers, compatibility branches, configurable
controller choices, new dependencies, a resident decoded-world cache or arbitrary
CPU/memory/request-rate budgets. Keep the existing JVM arguments and external
native memory enforcement. Source line/file limits have been lifted; retain clear
code and measure growth rather than introducing compression or line-count hacks.

Custom congestion control, a Quinn fork, ACK tuning, file-cache eviction advice,
physical storage migration, refresh-priority changes and weaker durability are
outside this plan. The external watchdog's RSS-versus-cgroup-accounting behavior
is also outside this scope; report its limitations accurately.

## 3. Use the improved workflow

Use the pinned [toolkit instructions](../../Codex_Tools/Voxy_Workflow_Tooling/USE_THE_TOOLKIT.txt)
and [API shapes](../../Codex_Tools/Voxy_Workflow_Tooling/releases/1.0.0/API_REFERENCE.txt).
The sealed release is read only. Runtime state, logs, hashes, build caches,
temporary comparison checkouts and rollback artifacts belong under Desktop's
`Codex_Tools/Voxy_Workflow_Tooling`, never `/tmp`.

1. Register a new external task for this exact plan, with **30 Rust clients**, no
   overall testing/retry limit, no integration tests and the actual protected
   targets. Do not copy the old task JSON's superseded 100-client/600-second limits.
   Planning status must not be represented as implementation or live acceptance.
2. Use MCP `voxy_source` resolve/outline/read_symbol/references and current source
   hashes. Navigation is lexical. If a Rust method is not found, retain the failed
   receipt and use an exact bounded filesystem read instead of repeated requests
   or assuming the method does not exist.
3. Use MCP `voxy_manifest` **source_snapshot/source_compare** with one matching
   physical counting definition, including untracked sources and separate
   production/debug/harness categories. Record source directories, including empty
   ones, separately if the manifest does not supply that count. Measure native/JAR
   size and compressed companion storage separately.
4. Build through owner-authorized finite jobs and reviewed presets. Poll each
   returned job ID; inspect terminal status and retained full receipts when a
   response is capped. Do not infer success from submission or replay an uncertain
   job. Exclude presets that execute tests; do not run unit/integration test targets.
5. Capture compiler, lockfile/dependency, feature/configuration and source-input
   hashes before/after each build. Stage candidates externally and verify server
   JAR, embedded native and standalone native hashes independently. HEAD alone
   does not associate source with an artifact.
6. Prepare each live manifest before activation, with exact permitted operations,
   retained identities/settings, restoration material and both PC routes. Use the
   handover/owner lease; the root remains the sole live operator. Sub-agents may
   independently review source and analyze saved evidence, not operate live targets.
7. The toolkit requires a finite deadline and restoration reserve for each owned
   run. Size them for that experiment's setup, plateau and cleanup. These are
   lifecycle safeguards, not a total testing limit. Finish/restore/close each
   attempt before another; repeat useful experiments without asking permission.
   Never reset or extend an ACTIVE clock or weaken sealed toolkit checks.
8. Import saved logs once into evidence bundles; refresh incrementally, query
   bounded records and compare compatible epochs. Keep continuous raw sampling
   outside per-action tool calls, with compact byte-offset/hash receipts. Avoid
   repeatedly parsing full historical resource journals.
9. Use report requirements/links/publication_preview for an evidence-backed result.
   Preserve failed and inconclusive runs. No GitHub publication or bypass of the
   earlier automatic approval rejection is part of this plan.

## 4. Approach 5 foundation: owned regional readers and shared opens

### 4.1 One captured source reader per inspection/build transaction

Current `AnvilWorld::read_chunk` repeatedly opens `.mca`, reads metadata/header
information and allocates buffers. Introduce a small transaction-owned reader:

1. Capture the existing validated `RegionHeader`, open its regional file once and
   verify the open descriptor/current pathname correspond to that captured file.
   Detect replacement of the file, not just equal length. Keep source-file markers
   and final validation as consistency guards, not sole content authority.
2. Read slot records from the captured 1,024-entry header using positioned reads
   (`FileExt::read_exact_at`) rather than mutable shared seek positions.
3. Keep the existing offset, sector count, record length, compression-kind,
   generated-status, external `.mcc` and absolute format-safety checks. Read
   external contents through the same owned transaction and include actual bytes
   in content fingerprints. Preserve existing external record/size/codec checks.
4. Reuse compressed/decompressed buffers with explicit populated lengths and
   exclusive ownership. No borrowed NBT/iterator state may survive a buffer reset.
   Buffers belong to the transaction/worker and are released when its work ends.
5. Use this reader in semantic probes and full/incremental building without
   retaining decoded chunks across unrelated transactions. Avoid a new queue,
   world-sized cache or resource quota.
6. Preserve final `verify_header`, dirty-bit restoration and publication/retry
   rules. Explicitly verify external descriptor/path ownership and before/after
   consistency for every `.mcc` consumed, including raw matches; rehash actual
   external contents when metadata/header coupling cannot establish stability.
   Existing regional probes/builds do not call the separate `verify_sources`
   helper. An unchanged `.mca` header alone does not certify a separately changed
   `.mcc`, and one descriptor alone does not make in-place saves safe.

Time remains linear in bytes actually read. File-open/header work falls from once
per chunk to once per transaction, plus genuinely necessary external files.

### 4.2 Share concurrent opens of an immutable Voxy generation

`RegionalRuntime::region` currently releases the priority lock before opening a
generation, allowing concurrent misses to duplicate directory/index work.

1. Track only active opening operations keyed by region and generation inside its
   world/dimension runtime. One owner opens; other callers wait for that result.
2. Perform all file I/O and waiting outside the global priority lock. Never hold a
   lock while waiting for a task that needs that lock to finish.
3. Recheck generation/subscriber ownership before installing the successful
   `Arc<RegionFile>` in the existing active index. Never overwrite a newer active
   generation with an older result.
4. Retain immutable snapshots already held by prepared requests. Remove completed
   opening state; do not add an enduring region/payload cache.
5. Success, missing files, errors, cancellation and panic must settle the operation,
   wake waiters and release ownership. Preserve quarantine/negative-authority
   behavior and permit a later legitimate retry.

Keep the existing O(log R) BTreeMap metadata lookups; changing map structures is
outside this plan. Opening/index construction remains linear in that index.
Concurrent callers share one construction instead of multiplying it.

## 5. Approach 4: semantic inspection without full cell materialization

Separate validated normalized terrain traversal from whether cells are retained.
Use the same normalization rules for the builder and semantic-only inspector.

Preserve these exact inputs and validation behaviors:

- Chunk coordinates/status, duplicate section Y rejection, block/biome palette
  cardinality, every packed palette index, canonical sorted block properties,
  mapping-name validation, light array sizes and missing-array defaults.
- Registry registration order from the existing decoded section/palette traversal.
  Sorting sections for hashing must not reorder first-time registry registration.
- Section count as u32 little-endian, then sections in ascending signed Y order;
  each Y is i32 little-endian followed by its 4,096 cells in original Anvil order
  (x fastest, then z, then y).
- Each cell contributes block ID u32 little-endian, biome ID u32 little-endian and
  one byte `(blockLight << 4) | skyLight`. Air uses biome zero. Incomplete chunk
  status retains the existing empty-section semantic result.
- Existing xxHash64 seeds `0x5658593254455252` and `0x9e3779b97f4a7c15`.

The semantic inspector feeds two incremental hash states directly, without a
4,096-cell array per section or the large normalized fingerprint byte vector.
Full building still materializes the cells it actually consumes. Prefer validated
packed-index traversal over retaining whole expanded index arrays where this does
not weaken before-registry-mutation validation.

Implement incremental xxHash64 in the existing CRC/hash module with fixed-size
state/tail. Make the existing one-shot API a wrapper after independent live
equivalence has been established; retain one production algorithm. A debug-only
oracle must compare against independent baseline results/reference computation,
not merely compare two callers using the same new hashing implementation.

Inspection remains Theta(terrain values plus NBT bytes). Semantic hash scratch
becomes O(1); parsed NBT/palettes remain proportional to their content. Do not claim
the whole parser has constant memory or that arbitrary terrain hashing is O(1).

## 6. Approach 2: compressed-content identity before semantic inspection

### 6.1 Preserve authoritative formats; introduce disposable acceleration data

Do not expand, delete or invalidate existing `.vxsource` records. Current handling
can quarantine an unreadable source table and select a full regional rebuild;
that would create avoidable CPU/I/O and invalidate the intended comparison.

Add a lazily populated `.vxcontent` companion using one current layout. It is
optional acceleration data, with no protocol/version field or legacy parser:

- Bind it to world identity, region coordinates, terrain generation and a BLAKE3
  digest of the **exact validated serialized `.vxsource` bytes**.
- Store explicit populated/presence bits and a strong content digest per slot.
  Do not treat an all-zero digest as an implicit valid entry.
- Validate ownership, exact lengths/counts and companion integrity before use.
  Missing, corrupt or owner-mismatched data takes the normal semantic inspection
  path; it must not invalidate terrain or grant negative authority.
- Obtain source serialization/digest through a shared helper rather than adding
  another `.vxsource` parser. Keep the existing authoritative layout unchanged.

A full 32-byte digest table adds roughly 32 KiB per inspected region, plus its
header/bitsets. Report actual disk growth. Populate only during work already
needed; do not scan/backfill the world at startup or hold a new permanent map.

### 6.2 Inspection decision

1. Preserve the existing trustworthy unchanged-slot fast path and conservative
   treatment of forced/unnotified changes.
2. For a slot requiring inspection, read its validated compressed record through
   the transaction reader. Domain-separate the digest and include compression
   kind, actual byte length and actual bytes, including external `.mcc` data.
   Exclude physical sector location and Minecraft timestamps from content identity.
3. Only when a populated companion entry belongs to the exact prior source table
   and its content digest matches may the prior semantic result be reused. Update
   current location/timestamp metadata normally. Do not decompress or construct
   cells merely to reconfirm an exact byte match.
4. On a miss, use the new semantic inspector. Compare its semantic result with
   the prior record; physically changed but semantically equal data must not cause
   terrain rebuilds. True changes select the same groups/ancestors as before.
5. Absent/generated/incomplete-status transitions remain explicit. A raw miss is
   not proof of a terrain change, and a changed file marker is not content identity.
6. Verify the complete source snapshot before publishing derived results. Preserve
   safe retries and restoration of consumed dirty notifications.

### 6.3 Publication and failure behavior

Publish authoritative terrain/source using the existing durable sequence. Then
atomically replace a matching companion. A crash between replacements yields a
safe ownership mismatch and inspection fallback. Companion failure cannot revoke
valid terrain, suppress a real change or turn an unknown source into absence.

The disposable companion needs no extra durability barrier: an interrupted write
may lose this optimization, but must never lose authoritative terrain durability.
Use one transaction-level write/rename, not per-chunk writes. Write only when its
contents/owner actually changed; remove owned temporary files on failure. Remove
the companion when its authoritative regional source is removed. Quarantine of
optional acceleration data must not cascade to the authoritative shard.

The fallback is one normal semantic algorithm, not an old-format compatibility
path. Existing client/server formats and cache identities remain unchanged.

Hashing is Theta(compressed bytes); matching entries avoid decompression and full
semantic traversal. Region slot metadata is fixed by the Anvil format. Changed
bytes still require inspection; maintain no pairwise or quadratic work.

## 7. Approach 1: built-in server-side BBR

Select Quinn's existing BBR factory in `make_transport_config`. Keep the pinned
dependency versions and existing transport buffers, streams, MTU discovery,
timeouts, bandwidth accounting and request priorities.

Explicitly retain the current CUBIC startup window of **12,000 bytes** for the first
comparison. Pinned BBR otherwise defaults to 240,000 bytes. This preserves an
existing protocol startup parameter; it introduces no new application budget.

Final production code has one selected controller, with no user setting or stale
CUBIC fallback branch. Create a temporary optimized-CUBIC comparison artifact
through an isolated externally owned checkout/patch, with identical source
optimizations and instrumentation. Record the exact factory-only source diff and
both artifact identities. Do not mutate another agent's checkout/index or a loaded
artifact in place.

BBR is experimental in this Quinn release and still has recovery behavior.
Its controller-specific pacing metric does not independently control the pinned
packet pacer. Do not promise full-cap goodput or solve failure by adding a fixed
congestion-window floor, arbitrary request rate or a Quinn fork in this plan.

## 8. Debug evidence and measurement repair

Reuse existing diagnostics; add focused counters/spans without per-cell logging:

- Region/chunk inspections, compressed bytes read/hashed, raw match/miss and
  populated/owner-mismatch counts, skipped decompressions/parses, raw-changed but
  semantic-equal counts, true terrain/status changes and snapshot retries.
- Separate raw read/hash, decompression, NBT decode, semantic traversal/hash,
  materialized-cell allocation and existing publication costs. Attach scoped wall
  and executing-thread CPU time, and diagnostic allocation counts/bytes where
  reliable. Explain nested/inclusive stages rather than summing them.
- `.mca`/external/Voxy-generation opens, reader reuse, positioned reads, requested
  bytes, shared-open owners/waiters/failures and optional companion write bytes.
- Controller/window/flight/RTT/loss/recovery, useful versus charged traffic,
  flow-control/application-limited intervals, and existing per-client queue ages.

Pinned Quinn's public path statistics do not expose flight/app-limited fields or
BBR's private recovery state. Where needed, observe already-supplied controller
callbacks through the same transparent debug-only delegation on both candidates;
do not change either algorithm. Label callback values as last observed, report
congestion/recovery events, and mark inaccessible values unavailable rather than
fabricating instantaneous state or adding a transport fork.

Before comparing candidates, repair the existing pressure measurement faults:

1. Put all 30 independent impairment relays on one dedicated I/O/timer runtime
   thread inside the same Rust process. Keep client-owner work, synchronous output,
   validation and persistence off that relay thread. Retain minimal CPU/RAM use.
2. Preserve independent directional losses, charging actual IP traffic including
   lost attempts/retransmissions/headers, each client's per-direction cap, and the
   existing no-catch-up-burst scheduling. A dedicated thread is not a timing proof.
3. Record scheduled versus actual relay service, completed sends, queue residence
   and independent heartbeat distributions. Label the old awaited client pulse as
   progress lateness, not a global packet-loop stall.
4. Collect host/process/thread/core CPU, memory/cgroup events, I/O and PSI
   continuously from setup through every plateau phase and teardown. Exact plateau
   boundary counters are separate from whole-invocation results. Sampling must not
   pause for screenshots/tool handoffs; absent optional metrics remain null.
5. Validate unloaded RTT, loss, cap/accounting and relay scheduling before accepting
   comparative conclusions. Correct material distortion/gaps and repeat rather
   than assigning an inconclusive run a pass.

These debug/harness changes support the approved optimizations. They introduce no
production resource quotas, renderer changes or standalone integration tests.

## 9. Live validation sequence and gates

No overall testing or retry cap applies. Use builds/static inspection and live
validation on the real PC plus **30** Rust clients; do not execute unit/integration
test targets. The virtual clients keep independent QUIC connections, request/cache
state and impairment inside one Rust process, with no Minecraft connections or
rendering.

1. Preserve the current artifact as historical baseline and make a fresh comparable
   baseline with the repaired harness. Capture actual loaded server/native and PC
   identities, both backup SSH routes, original policy/cache markers and existing
   limits. Prior server270/PC268 identities require fresh verification.
2. Establish correctness during live saved-source work using a debug-only oracle
   comparing semantic-only and full-decoder results on the **same captured bytes**.
   Keep independent baseline digest vectors/reference output for incremental hash
   boundary cases; an oracle sharing the new algorithm is insufficient. Separate
   oracle-enabled evidence from performance cohorts because duplicate checking
   deliberately adds work.
3. Observe live unchanged-content reuse, true block/light/biome changes,
   raw-changed/semantic-equal saves, status/absence transitions, external chunks,
   live-save rejection/retry and cold restart with pre-existing authoritative
   metadata. Use existing saved terrain; do not generate unexplored terrain or
   mutate source-world files to manufacture cases. Mark any unobserved case as
   unverified rather than claiming correctness from an unrelated screenshot.
4. During a scoped live fallback check, temporarily remove/replace only this run's
   disposable Testing `.vxcontent` file using preserved external rollback material.
   Exercise missing/corrupt/owner-mismatched entries. Confirm safe inspection and
   continued terrain delivery, with authoritative files and client cache intact.
   Reconcile the current owner before restoring a companion; a stale one is useless.
5. Compare baseline-CUBIC against optimized-CUBIC for source/reader improvements,
   normalized by examined chunks, hashed bytes and delivered records. Compare
   optimized-CUBIC against optimized-BBR for transport improvement. Keep the same
   diagnostic configuration, buffers, fake workload and startup window. Separate
   first `.vxcontent` population costs from repeated-hit savings. Match valid,
   populated companion state between controller candidates, or stratify results
   by raw-hit rate; a warmer later companion is not a controller CPU improvement.
6. Use fresh owned fake caches, the same 30 saved-terrain anchors, movement schedule
   and per-client seeds. Start with the existing 240-second plateau profile
   (90 seconds coarse, 90 detail/movement, 60 warm revisit with useful missing
   demand). This profile duration is a comparable experiment, not a testing limit.
7. Run matched approximately 300 ms unloaded RTT/10% loss/1 Mbps-per-direction
   impaired and no-added-delay/loss capped control cohorts. Recalibrate added delay
   from the actual unloaded baseline; do not blindly reuse historical 278 ms.
   Add latency-only/loss-only comparisons if needed to explain controller behavior.
8. Repeat in reversed/alternating order when cache warming or live-save variation
   could explain an apparent gain. Capture regional generations/source identities
   and save/retry counts. Do not flush global caches or stop Main. These are fresh
   fake caches, not proof of cold server/OS caches. Equal seeds do not imply equal
   packet-loss histories after controller behavior changes.
9. Keep the compatible real PC build/cache/settings and updater. Check terrain at
   matched poses using signed debug observations and screenshots that are actually
   viewed. Verify cached terrain remains immediately usable without Voxy connectivity
   using a scoped hold/resume action; observe coarse coverage and finer detail after
   reconnect. Do not mistake fake validated completion for real rendered arrival.
10. Restore temporary policy/holds/poses when required by the run manifest, end the
    client run, stop owned fakes/relays and prove routes, subscriptions, sockets and
    detached work are gone. Reverify both SSH routes, Main identity, PC cache/settings
    and memory/JVM enforcement. Keep a successful candidate only with verified loaded
    identity; restore the preserved artifact on failed deployment/correctness gates.

Acceptance requires:

- No digest/protocol/integrity failures or incorrect negative authority; unchanged
  semantics produce unchanged terrain identities and rebuild decisions.
- No OOM events, cap relaxation, lost authoritative source data or unintended
  client-cache invalidation. Report transient kernel charged-memory overshoot
  accurately; the existing limiter does not prove an instantaneous never-over-1GB
  guarantee.
- Raw hits actually skip decompression/semantic decoding; inspector-only work
  avoids full cell/fingerprint-vector allocation; transaction reads and shared
  opens reduce the corresponding operation counts without resident state growth.
- Measured reductions in normalized source CPU/allocation and repeated opens, with
  real disk-write/read costs included. No speedup claim based only on fewer calls.
- Demonstrated improved impaired terrain acquisition/goodput from BBR under a
  valid comparison. Report control regressions, RTT inflation, fairness, packet
  recovery and memory growth. If it does not improve this path, retain the known
  working controller and mark approach 1 failed/deferred rather than inventing a
  benefit or silently expanding scope.
- Real-PC cache-first behavior and visible terrain remain correct in the observed
  scenarios. Rendering/FPS/perfect-world coverage or long-term freshness are not
  established by a short Voxy-only pressure cohort.

## 10. Report, tradeoffs and completion

Write a companion implementation-results document with each approved approach
marked implemented/passed/failed/deferred/unverified and linked to exact receipts.
Include artifact/source identities, per-client goodput/completion percentiles,
first coarse/finest arrival, fairness, charged traffic/loss/retransmissions,
controller/flight/RTT, source CPU/allocation, reads/opens, companion disk growth,
publication/retry costs, per-core/thread CPU, memory, I/O/PSI, viewed PC screenshots,
restoration proof and matching-definition size changes.

Unavoidable tradeoffs remain BBR probing/fairness and flight-state growth; an
additional disposable file/read/hash path; small normalized-iterator maintenance;
and transaction scratch/shared-opening ownership. Incorrect fingerprint inputs,
stale generation publication, deadlocked waiters or concurrent buffer reuse are
implementation failures, not acceptable tradeoffs.

Remove temporary comparison/oracle source after validation and keep retained
reference evidence externally. Reuse the validated production decoder/hash helpers
rather than keeping duplicate old/new algorithms. Do not leave stale experimental
controller selection or a compatibility/migration reader.

Complete only after implementation, valid live evidence, cleanup and honest
requirement reporting. Compilation, toolkit doctor, staged hashes and a clean
checkout independently prove none of those live outcomes.

## 11. Planning evidence

The sealed toolkit file-integrity verifier passed for 51 files. This says nothing
about live compatibility. The MCP source snapshot used the stable reviewed HEAD
and `voxy-physical` v1 scope, including untracked files. Planning created no build
or live run.

- [Baseline source snapshot receipt](../../Codex_Tools/Voxy_Workflow_Tooling/state/receipts/932433d7-d5d8-4691-a4d2-351ad7748073.json).
- [Transport source receipt](../../Codex_Tools/Voxy_Workflow_Tooling/state/receipts/b6ca1096-43b9-42b7-8cf4-58153160cd51.json).
- [Lexical method lookup failure, followed by bounded source inspection](../../Codex_Tools/Voxy_Workflow_Tooling/state/receipts/0c487145-65de-487a-a129-61f7c26a4a0e.json).
- [Saved pressure comparison](../../Codex_Tools/Voxy_Workflow_Tooling/tasks/server-pressure-20261006/pressure-comparison.json).

The native source snapshot counted 22 production-native files and 12,460 physical
lines; other debug/test/harness categories remain separate. That scope includes
embedded cfg(test) lines as documented by the toolkit and does not mean tests were
executed. Use the same definition for the final delta.
