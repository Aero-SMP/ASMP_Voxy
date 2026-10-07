# Server streaming: BBR and cheaper source inspection — implementation results

Written 2026-10-07 on `feature/cache-first-background-updates`.
Selected plan: [server_bbr_source_inspection_efficiency_implementation_plan.md](server_bbr_source_inspection_efficiency_implementation_plan.md).
That plan document remains unchanged.

**The approved production changes are implemented and retained on Voxy_Testing.
Independent live equivalence, source-efficiency comparisons, native companion
fallback probes, matched impaired transport runs, all three clean controls and
final independent cleanup have completed. Unobserved edge cases remain explicitly
unverified below.**
This report distinguishes implemented behavior, observed correctness and still
unverified cases. The oracle run is correctness evidence, not a throughput benchmark.

## 1. Status of the approved approaches

| Approach | Implementation | Evidence and remaining gate |
| --- | --- | --- |
| 1. Built-in server BBR | Implemented in the existing Quinn factory; startup window remains 12,000 bytes. | Built with unchanged transport buffers/protocol. Matched impaired runs show 3.736× useful goodput and 71.44% lower request-weighted completion time. All three clean controls and final retained deployment passed their cohort/lifecycle checks. BBR clean goodput was 0.53% lower and weighted completion 1.08% higher; shared-bottleneck fairness remains unverified. |
| 2. Compressed-content shortcut | Implemented using a disposable, exactly owner-bound `.vxcontent` file per region. | Live raw reuse and exact missing/corrupt/owner-mismatched native rejection, revalidation and safe restoration passed. New PC network delivery for the injected target was not demonstrated. |
| 4. Semantic-only inspection | Implemented through the shared validated normalized traversal and streaming xxHash64. | Independent baseline oracle passed for 4,884 actual saved chunks and 324 hash boundary checks. Matched non-oracle source comparison shows large normalized CPU/allocation reductions. |
| 5. Transaction readers and shared generation opens | Implemented with positioned reads, exclusive reusable scratch and an in-flight generation-opening owner. | 87 regional reader opens served 89,328 source records; sharing and safe source retries were observed live. External `.mcc` reads and deliberately interrupted opening owners remain unverified. |

No custom congestion controller, Quinn fork, new dependency, protocol version,
legacy reader, migration/reset, world generation, decoded-world RAM cache or new
production CPU/memory/request-rate budget was added. Client code, cache formats,
rendering/VRAM selection and the existing update policy are unchanged.

## 2. Exact source and artifact provenance

The implementation started from the existing plan-bearing branch at
`75cf2bb3c5f50757cfcfa834b4adbaddcce1f5a2`. The planning reference remains
`5aaa9c2216c274307958a1bf1c3c787ee0d6490f`.
The current production sources match the frozen precise input manifest, input SHA-256
`e9b4e06185c9e1f18912b0aff5f070a8da66a1dc3a622c370583eb6064c22149`:
[before](../../Codex_Tools/Voxy_Workflow_Tooling/tasks/server-streaming-efficiency-20261007/precise-final-before.json)
and [after](../../Codex_Tools/Voxy_Workflow_Tooling/tasks/server-streaming-efficiency-20261007/precise-final-after.json).
Audit documents are excluded from that build-input hash; untracked build inputs are included.
Source fingerprints identify dirty source contents, not merely the Git HEAD.

Production changes are concentrated in existing native files:

- [server.rs](../rust-server/src/server.rs): built-in BBR factory and debug controller identity.
- [anvil.rs](../rust-server/src/anvil.rs): captured regional reader, external-file guards,
  normalized decoder/inspector and streaming semantic digest.
- [crc.rs](../rust-server/src/crc.rs): one validated streaming xxHash64 implementation;
  the one-shot convenience function delegates to it.
- [source.rs](../rust-server/src/regional/source.rs): canonical authoritative source
  serialization/digest and disposable compressed-content companion.
- [runtime.rs](../rust-server/src/regional/runtime.rs): raw-content reuse, companion
  publication, external discovery bookkeeping and shared concurrent generation opens.
- [builder.rs](../rust-server/src/regional/builder.rs): reuse one transaction reader,
  retain source validation, and collect raw digests from work already performed.
- [diagnostics.rs](../rust-server/src/diagnostics.rs): debug stage/counter/allocation
  observations and an explicit host-monotonic snapshot clock.

The repaired fake-client harness remains in the existing `live_pressure.rs` and
`pressure_relay.rs`; operational helpers, logs, comparison checkouts and rollback
material remain outside the repository under the toolkit task.
Embedded `cfg(test)` constructor adjustments are compilation maintenance, not
executed tests. No unit or integration test target was run for this implementation.

[Precise artifact verification](../../Codex_Tools/Voxy_Workflow_Tooling/tasks/server-streaming-efficiency-20261007/precise-final-artifacts.json)
records exact embedded native identity and normal/debug packaging separation:

| Artifact | JAR SHA-256 | Embedded native SHA-256 | JAR / native bytes |
| --- | --- | --- | ---: |
| Normal server 273 | `8152f938d9927a2e2f70c5546be4eb4bd28a29da39a299598825fe8bb073dfcc` | `6e51ae73bea7f3ade3512d90d748eecedeeb71855fb9013c69248e5587b69fcb` | 1,746,028 / 3,548,880 |
| Debug optimized BBR 273 | `97150959350c8f86d11f6befba1dcb128b5ccf6f683441d1deb22a19b708cd3f` | `523696db897d13054aaa81e96df8405f723b1fde00a6cc407af280770812761c` | 1,927,250 / 3,808,464 |
| Debug optimized CUBIC 273 | `ce51d9b04b6a7f15aba5628fdc09809ece6983c56855ba291126cefa86f679eb` | `89c756d11c632ee2bd8bf162c4169db6cae2afb4b28d073103cbf92cb769f1cb` | 1,922,487 / 3,799,456 |
| Debug baseline CUBIC 273 | `09b2b1a5dc3bd96e5695126a9e73f766856870de68704ac91c1c33babdaad7a8` | `80fdf36d9dc660fc4d71bae7948468d7c507c0a3b70debb9aaead05be9180131` | 1,887,417 / 3,721,904 |

These are built/staged identities. The actual loaded identities and epochs for
each later comparison must come from that run's fresh preflight and native observations.
Normal artifacts exclude the Java pressure class and native diagnostic markers;
debug artifacts include them. Temporary oracle source is absent from the precise candidates.

The comparison variants are isolated external copies. The controller-only variant
changes `BbrConfig` to `CubicConfig` while retaining the same 12,000-byte startup
window and other inputs. The baseline retains the old source algorithms while
using matched diagnostics and the repaired harness. See the
[controller input manifest](../../Codex_Tools/Voxy_Workflow_Tooling/tasks/server-streaming-efficiency-20261007/precise-controller-comparison-inputs.json)
and [baseline construction record](../../Codex_Tools/Voxy_Workflow_Tooling/tasks/server-streaming-efficiency-20261007/baseline-comparison-inputs.json).

## 3. Implemented ownership and correctness rules

### Compressed-content reuse

A raw identity hashes a domain tag, compression kind, actual compressed length and
all compressed bytes. Sector placement and timestamps are deliberately excluded.
Reuse is allowed only against a prior validated semantic result for that exact
slot in a companion bound to world identity, region coordinates, terrain generation
and the digest of the exact canonical authoritative `.vxsource` serialization.
The companion additionally checks its own BLAKE3 checksum, reserved fields,
cardinality and presence consistency with the authoritative source table.

A raw hit uses the existing semantic fingerprint and skips decompression/NBT/cell
construction. A raw miss runs the validated semantic inspector. A missing,
unpopulated, corrupt or mismatched companion loses an optimization; it never
establishes terrain presence, absence or freshness. Existing unchanged-header and
save-notification shortcuts remain; they were not presented as new optimizations.

The companion is one current disposable format, with no old-format parser or
startup backfill. Failure to load it follows the same normal inspection path.
Failure to write it is nonfatal to valid terrain. No `.vxsource`, `.vxregion`,
world, registry, server identity or client-cache reset is required.

### Shared normalized semantic representation

Full cell decoding and semantic inspection use the same validated normalized
section. Packed palette cardinality and every index are checked before mapping
registration. Section/palette registration retains the original input order;
sorted signed section Y determines digest order. Single-entry palettes, absent
block states, biome fallback, air normalization, light nibbles, incomplete status
and duplicate-section rejection retain the baseline behavior.

The exact digest stream is: little-endian section count; then ascending signed
section Y; then 4,096 cells in the original x-fast/z-next/y-last order, each containing
little-endian block ID, little-endian biome ID and one light byte. Both original
xxHash64 seeds are unchanged. Semantic inspection avoids the full cell arrays,
unpacked-index arrays and serialized fingerprint vector. It still parses required
NBT and visits required cells; this is linear work, not an O(1) terrain hash.

### Transaction reads and source snapshot verification

One regional transaction owns its `.mca` descriptor, captured header and reusable
compressed/decompressed buffers. Positioned reads use the captured slot location.
Borrowing prevents the next chunk from resetting bytes still used by a decoder.
The incremental inspector and builder share this reader, including its external
guards; raw digests collected during building populate the companion without a
second content scan.

Before publication, the reader verifies descriptor/path identity and the captured
header. Every consumed external `.mcc` is read with before/after identity checks
and rehashed before publication. Repeated reads must agree with the first external
identity/content. The existing final header verification remains as well.
Concurrent saves or coordinate mismatches abort the candidate rather than publishing
a mixed source snapshot. Metadata guards are observations, not filesystem locks;
Minecraft can save again after a validated snapshot, making that publication stale.

External changes are discovered through the existing single directory inventory
pass, using a volatile per-region aggregate of external file metadata. This aggregate
is a discovery hint, not content authority. Unknown inventory forces inspection;
nonzero external state after restart requires reconciliation. Changed `.mcc` files
that were not consumed are discovered on a subsequent inventory pass. No per-region
directory rescan or enduring decoded/raw-content cache was added.

### Negative authority and durable publication

Static review confirms that stale positive terrain remains usable, while a negative
answer requires an unretired runtime, no dirty/reconciling owner, readable observed
inventory, the current terrain generation and a reconciled source stamp matching
generation, marker, header and observed external state. A disposable raw companion
is absent from that authority decision. Captured dirty bits are restored on failure;
the reconciling marker remains until successful reconciliation.

Authoritative shard publication still writes the temporary file, synchronizes the
file, renames and synchronizes its parent. Authoritative source-table publication
retains the same durable sequence. A newly installed terrain generation cannot
reuse the older source stamp to certify negative/fresh results. Recovery of an
uncertain final shard validates its payloads and durability before acceptance.
The companion is published only after the corresponding authoritative source state;
its atomic rename deliberately adds no additional fsync requirement.

These ordering/authority statements are source-review findings. They are not proof
that every crash, deletion, absent/incomplete transition or fsync failure branch
was exercised live during this work.

### Concurrent generation opening

The in-flight opening map is keyed by exact region and generation. One owner performs
the validated open; concurrent waiters receive the same result. Terminal ownership
settles/notifies waiters and removes the flight. Active-index installation checks
retirement, current generation and subscription ownership, so an older completion
does not replace a newer active generation. No persistent inactive-handle cache
was added. The drop guard also handles unwinding builds; release uses `panic=abort`,
where process termination is the fatal-panic outcome rather than an in-process retry.

## 4. Live correctness and retained-PC evidence

The correctness run was
`f95eccc8-b887-4aba-8d4f-310c2efa59fb`, with loaded debug server 271/native
`a8f2c17d761345e489f949d1312b9db85866518a09ef51247cf1c0a3b3a89062`, PID 1093371,
native epoch `1791384004647933370`. It deliberately retained PC debug 268, SHA-256
`74dc0d71781c677bca2256a04d3913b6bfac87adf67d1b8220cb7d81cac38eeb`.
The server-only change keeps the established protocol; no client update was needed.

[Final correctness artifact](../../Codex_Tools/Voxy_Workflow_Tooling/tasks/server-streaming-efficiency-20261007/runs/f95eccc8-b887-4aba-8d4f-310c2efa59fb/oracle-correctness-results.json)
records:

- **4,884 actual saved source chunks** compared against an independently retained
  baseline full decoder on the same captured bytes, with no oracle mismatch.
- **324 independent xxHash64 boundary checks**, using the original one-shot reference,
  actual saved input bytes, three seeds, boundary lengths and multiple update splits.
- 373,201 raw matches, 3,928 raw misses, 3,670 semantic-equal inspections,
  254 semantic changes, 187 metadata-only refreshes and 184 unchanged-source hits.
  Semantic-equal/raw-miss work includes unpopulated companions; it does not prove
  that all of those compressed payloads had changed since the last save.
- 183 shared-opening owners and 177 waiters, with zero opening-failure counter
  observations in that run. This demonstrates exercised sharing, not every
  cancellation/panic path or a normalized speedup.

The initially reported 221 checked chunks was an intermediate gate; the final
loaded oracle accumulated 4,884 checks. Its full reference decoder, unpacker and
original hash were preserved externally before removal:
[preservation receipt](../../Codex_Tools/Voxy_Workflow_Tooling/tasks/server-streaming-efficiency-20261007/oracle-reference/preservation.json).
Only the validated shared algorithms remain in production. Oracle-enabled CPU,
allocation and elapsed totals include duplicate checking and cannot establish the
performance of the cleaned candidate.

The retained log contains **25 rejected source observations: 20 snapshot changes
and five coordinate mismatches**. Four coordinate mismatches occurred during enabled
timing; one preceded it. The baseline already rejected mismatched NBT coordinates.
All requested/reported pairs remain within their requested region, and nearby
descriptor/path-change rejections support concurrent-save interference as a possible
cause. Sector reuse is not proven. Later source/companion generations advanced for
all three involved regions; individual rejected-chunk recovery was not independently
catalogued. Rejections were deferred retries, not successful semantic inspections.

### New disk-cache work with Voxy connectivity held

The confirmed [hold](../../Codex_Tools/Voxy_Workflow_Tooling/runs/f95eccc8-b887-4aba-8d4f-310c2efa59fb/748bcd06-5002-466a-8c5a-f0fa9165b182.json)
and subsequent [checkpoint](../../Codex_Tools/Voxy_Workflow_Tooling/runs/f95eccc8-b887-4aba-8d4f-310c2efa59fb/915ec919-7006-4ce5-8cf5-c51d59b0543f.json)
show actual new cache work after the controlled pose change:

| Metric | At hold | Held checkpoint | Change |
| --- | ---: | ---: | ---: |
| Cache reads/hits | 80,349 | 105,088 | **+24,739** |
| Decoded sections | 142,747 | 167,486 | +24,739 |
| Uploaded sections | 142,450 | 167,201 | +24,751 |
| Network bytes | 326,123,331 | 326,123,331 | **0** |
| Downloads active | 0 | 0 | 0 |

The upload delta also includes completion of previously queued work, so it is not
an independently attributed count of precisely those cache reads. This proves that
new disk-cache sections could proceed to decoding/upload while Voxy QUIC was held.
It is not a cache-loading speed comparison. The checkpoint reported
`coverageMissing=25,446`; this cannot support a zero-hole or complete-world claim.

Root viewed the [held screenshot](../../Codex_Tools/Voxy_Workflow_Tooling/runs/f95eccc8-b887-4aba-8d4f-310c2efa59fb/captures/690f7133-dc94-4fc2-b326-32cd0389f826.png)
and [restored/reconnected screenshot](../../Codex_Tools/Voxy_Workflow_Tooling/runs/f95eccc8-b887-4aba-8d4f-310c2efa59fb/captures/b6dcfce2-637e-48fc-a0a4-080f8186290c.png).
Two observed views do not establish universal modded-block correctness, perfect
meshing, full-world coverage, FPS or long-term freshness.

The [independent oracle-run closure proof](../../Codex_Tools/Voxy_Workflow_Tooling/tasks/server-streaming-efficiency-20261007/runs/f95eccc8-b887-4aba-8d4f-310c2efa59fb/29be26fe-22c2-4dd8-aa5f-e0f10d0790c0.root.json)
confirms the original pose restored, hold removed, retained game/artifact/settings/
cache markers preserved, both existing SSH helper routes alive, Main identity
unchanged, typed client run ended and owned routes/sessions/subscriptions/sockets/
exclusive or unknown source work cleared. Diagnostics reporting was disabled.
The successful oracle candidate remained loaded at that closure; this does not
describe the later comparison run's current deployment.

At oracle closure, `memory.max=999997440`, `memory.swap.max=0`, and `oom`, `oom_kill`
and `oom_group_kill` were zero. Limit/reclaim events occurred (`max=504`). JVM arguments
remained 1–4 GiB. These observations preserve the existing enforcement; they do not
prove an instantaneous never-over-1-GB guarantee or absence of reclaim overhead.

## 5. Measurement repair and interpretation

All fake impairment relays share one dedicated I/O/timer runtime thread, separate
from client-owner work, blocking validation/persistence and synchronous output.
Connections, request state, random seeds and directional relay queues remain
independent; the fakes do not connect to Minecraft or render. Actual IP bytes include
headers, lost attempts and retransmissions. Scheduled service, completed sends,
queue residence and independent relay heartbeat are recorded separately. The
client-owner pulse is labelled progress lateness, not relay-thread timing fidelity.

Diagnostics separate source open/read/hash, decompression, NBT deserialization,
semantic inspection/hash, cell materialization, shared-open waiting and publication.
Same-thread CPU/allocation observations are inclusive. Allocation bytes measure
successful allocated bytes plus positive realloc growth, without frees; they are
neither retained memory nor whole-process allocation traffic. Nested stages must
not be added as disjoint work. `SourceOpens` counts transaction reader descriptors,
not every header-capture open. `RawBytes`/`SourceReads` exclude external verification
rereads and are not complete filesystem-traffic totals.

The precise debug snapshot includes `host_monotonic_ns` from Linux CLOCK_MONOTONIC
to align native snapshots with external operator intervals. Clock failure remains
null. The old `monotonic_ns` is the diagnostics-origin clock and must not be
treated as process-start elapsed time or substituted for host-clock alignment.
The debug-only `VOXY_DEBUG_COMPANION` event identifies PID, dimension, region and
source/candidate generation and reports validated/missing/corrupt/owner-mismatched
outcomes. This enables attribution of later named-file probes; global counters
alone cannot prove which region or injected variant was inspected.

Public pinned Quinn statistics expose controller/window/RTT/loss and controller
pacing telemetry. Instantaneous bytes in flight, app-limited state and private
recovery state remain explicitly unavailable in the current snapshot. The proposed
transparent callback delegation was not added: the pinned public callback surface
requires `RttEstimator`, which cannot be named through Quinn 0.11.11's public exports with the existing dependencies. The pinned quinn-proto 0.11.17 trait requires it; see the [exact callback-surface evidence](../../Codex_Tools/Voxy_Workflow_Tooling/tasks/server-streaming-efficiency-20261007/transport-callback-surface-limit.json).
Adding a direct dependency or transport fork was outside scope. This is a diagnostic
deviation, not a measured flight/recovery result. Controller pacing telemetry is
not an independently applied pacer setting; no invented field fills those gaps.

The first resource-observer attempt failed without samples and was retained; a
later owned attempt provided continuous samples. Calibration failures/retries are
retained separately. See [saved oracle/calibration analysis](../../Codex_Tools/Voxy_Workflow_Tooling/tasks/server-streaming-efficiency-20261007/runs/f95eccc8-b887-4aba-8d4f-310c2efa59fb/oracle-offline-summary.json).
Neither a dedicated relay thread nor an `EXITED` guard receipt independently
certifies a valid network profile or a passed pressure test.

## 6. Size, complexity and unavoidable tradeoffs

The optional companion has a fixed 33,152-byte logical size. The oracle census
observed 13 files, 430,976 logical bytes and 479,232 allocated filesystem bytes.
The 11,702,656 companion-bytes-written counter is cumulative write traffic, not
resident cache size. There is no startup world-wide companion generation pass.

Source inspection remains linear in compressed/decompressed bytes and normalized
cells. Hash scratch is O(1); reader slot bookkeeping is fixed at 1,024 entries;
palette/NBT data and necessary full-build cells remain input-dependent. One
directory inventory pass uses ordered-map lookups, approximately
O((R+E) log(R+E)) for R regional and E external files. There is no directory scan
per captured header/verification or new O(R²) cross-region algorithm. Shared opens
use an in-flight map, not a permanently growing inactive-region cache.

Unavoidable tradeoffs are BBR probing/fairness/flight-state behavior; reading and
hashing compressed bytes plus the extra disposable disk file; maintaining one
normalized traversal; retaining the largest scratch allocation for the lifetime
of its transaction; and explicit shared-opening ownership/waiting. Whole-shard
rewrites and authoritative fsync costs remain. Incorrect hashes, false negative
authority, mixed-source publication, stale generation installation or stranded
waiters would be implementation failures, not acceptable tradeoffs.

The [source-directory census](../../Codex_Tools/Voxy_Workflow_Tooling/tasks/server-streaming-efficiency-20261007/final-source-directories.json)
records two directories under `rust-server/src`, including its root, with no empty
directories. Final physical line/file deltas must use the same toolkit category
definition as the plan's 22 production-native files / 12,460-line baseline, including
its treatment of embedded `cfg(test)` code. Git diff insertion counts are not a
replacement for that census. The matching-scope comparison is recorded in section 8.

## 7. Live comparative results

All authoritative comparisons completed 30 independent Rust QUIC clients, a
240-second plateau, fresh fake-client caches, and zero failed clients. The relays
charge actual IP bytes independently in each direction. Impaired runs target
300 ms unloaded RTT with 10% seeded directional loss; clean controls retain the
1 Mbps directional cap with no added delay or scheduled loss. The real PC remains
connected separately. Fresh fake caches do not make server or Linux caches cold.
The finite ownership deadlines are lifecycle safeguards, not a testing/retry limit.

### Source efficiency: matched CUBIC pair with the original Main process

The sequential `fa9bf6ed-84b3-47ad-a7d9-0780c066e930` baseline and
`3e9c029c-395f-478d-9130-3ac7975463c3` optimized cases both used CUBIC, the
impaired 30-client profile and Main PID 2703517/start 52797315. The server was
already regionally built; these were warm, repeated source-inspection workloads,
not full cold-world construction. Values below use the saved plateau selections:
[source comparison](../../Codex_Tools/Voxy_Workflow_Tooling/tasks/server-streaming-efficiency-20261007/source-cubic-plateau-comparison.json) and
[I/O extension](../../Codex_Tools/Voxy_Workflow_Tooling/tasks/server-streaming-efficiency-20261007/source-cubic-plateau-io-comparison.json).

| Measurement | Baseline | Optimized | Interpretation |
|---|---:|---:|---|
| Inclusive SourceInspect same-thread CPU | 46.598 s | 0.769 s | 98.35% lower observed CPU work |
| Inclusive SourceInspect allocated bytes | 360.691 GB | 78.343 MB | 99.978% lower cumulative allocation traffic; not retained memory |
| SourceInspect allocation calls | 146,241,920 | 285,773 | 99.80% fewer observed calls |
| CPU per all source-record read | 409.073 µs | 8.612 µs | 97.89% lower; denominator includes inspection and actual materialization |
| Allocated bytes per all source-record read | 3,166,426 B | 877 B | 99.972% lower on that normalization |
| Native process CPU | 53.65 s / 0.2228 cores | 8.20 s / 0.03406 cores | 84.72% lower observed native CPU |
| Mean native anonymous RSS | 136.325 MB | 135.263 MB | About 1 MB difference; no meaningful resident-memory improvement established |
| Useful terrain per client | 119.37 kbps | 119.92 kbps | Essentially unchanged delivery under CUBIC impairment |
| Mean first coarse arrival | 3.249 s | 3.197 s | Validated record arrival, not rendered coverage |
| Mean first finest arrival | 8.862 s | 8.824 s | Does not mean the visible area was complete |
| Mean of the 30 client request means | 3.679 s | 3.653 s | Not a request-weighted global mean |
| Request-weighted completed mean | 3.596 s | 3.582 s | Separately calculated from completed-request totals |

Request-weighted rows come from the later saved
[baseline completed cohort](../../Codex_Tools/Voxy_Workflow_Tooling/tasks/server-streaming-efficiency-20261007/runs/fa9bf6ed-84b3-47ad-a7d9-0780c066e930/baseline-cohort-delivery.json)
and [optimized completed cohort](../../Codex_Tools/Voxy_Workflow_Tooling/tasks/server-streaming-efficiency-20261007/runs/3e9c029c-395f-478d-9130-3ac7975463c3/optimized-cohort-delivery.json),
with 32,888/32,880 completed requests. Their Jain useful-throughput indices are
0.99617/0.99795. These whole-cohort completions are a different scope from the
plateau source CPU counters.

The optimized inspector matched 88,894 of 89,088 raw inspection comparisons
(99.782%). Only 194 semantic inspections and 240 materialized chunks were needed
across 89,328 source-record reads. Compressed hashing consumed 0.472 CPU-seconds
for 621.636 MB and recorded zero allocations; semantic hashing also recorded zero
allocations. Descriptor reuse is directly measured at 87 opens, roughly 1,027
records per open. Baseline open counters had no old-code callsites and are
unavailable, rather than measured zero.

This is strong evidence that redundant saved-terrain CPU/allocation work was
removed. It does not establish faster impaired delivery: both cases remained near
120 kbps. The old `anvil_nbt` stage includes parsing, mapping, cells and fingerprint
work, while the new leaf stage is fastnbt decoding alone, so their times cannot be
advertised as a decoder speedup. SourceInspect and publication/build spans are
inclusive; nested times and allocations must not be added.

The I/O extension preserves the less favorable observations too:

| Measurement | Baseline | Optimized |
|---|---:|---:|
| Process-caused `read_bytes` | 194.54 MB | 86.38 MB |
| Process-caused `write_bytes` | 295.49 MB | 419.74 MB |
| Source-table writes | 95 | 87 |
| Regional publications | 36 | 51 |
| Source-table write mean / observed maximum | 3.40 / 103.29 ms | 15.06 / 416.25 ms |
| Fsync/rename mean / observed maximum | 10.79 / 134.77 ms | 33.57 / 335.08 ms |
| Directory-read mean / observed maximum | 0.044 / 1.480 ms | 0.048 / 6.247 ms |
| Compressed-read mean / observed maximum | 0.095 / 6.437 ms | 0.034 / 48.202 ms |

Live-save/dirty-column cohorts differed, with 36 versus 51 publications. Thus the
higher write volume and durable-write tails are measured costs, not an isolated
algorithm regression. Successful optional companion writes were 2.884 MB, much
smaller than the optimized process-caused writes of 419.738 MB. Device `io.stat`
was unavailable. `/proc/io` logical bytes, kernel storage bytes, page-cache
ownership, writeback and inclusive instrumented wall stalls have different
meanings; none alone identifies device latency or the request critical path.

These older native artifacts lack the later absolute host clock. Their roughly
241-second native selections use adjoining snapshots and 51/59 ms runner-origin
brackets, with an unknown constant Java-log delivery offset. Live saves, regions,
companion population and host/page-cache contention differ across sequential
cases; normalized results reduce, but do not remove, those confounders.

### Matched impaired controller comparison

Precise optimized CUBIC `9c31532c-ee15-41cb-9561-fe4fa0de543d` and optimized BBR
`98ab23e4-2176-4e18-8cf4-6e25d6a655b8` both observed Main PID 1282851/start
61190536. Both completed the 30-client, 240-second impaired profile with fresh
owned fake caches, a 12,000-byte startup window, 10% directional loss and a
1 Mbps actual-IP cap per direction per client. Mean setup RTT was 302.535/305.096 ms.
The intended code delta was the built-in controller factory. Actual native hashes,
epochs and saved-input hashes are recorded in the
[paired analysis](../../Codex_Tools/Voxy_Workflow_Tooling/tasks/server-streaming-efficiency-20261007/precise-controller-impaired-paired-comparison.json). The earlier
BBR `598` used the old Main epoch and is separate corroboration.

| Measurement | CUBIC `9c315` | BBR `98ab` |
|---|---:|---:|
| Useful terrain mean per client, sampled plateau | 119.985699 kbps | 448.226773 kbps |
| Minimum–maximum useful rate across 30 clients | 103.760–132.827 kbps | 388.535–521.066 kbps |
| Jain useful-throughput index | 0.997035 | 0.992965 |
| Whole-cohort completed requests | 33,108 | 114,525 |
| Request-weighted completed mean | 3.583813 s | 1.023383 s |
| Completed-request p50 bin bounds | (2.097152, 4.194304] s | (0.524288, 1.048576] s |
| Completed-request p95 bin bounds | (4.194304, 8.388608] s | (1.048576, 2.097152] s |
| Completed-request p99.5 bin bounds | (8.388608, 16.777216] s | (2.097152, 4.194304] s |
| Maximum completed request | 16.466596 s | 5.011179 s |
| Median first coarse arrival | 2.649701 s | 2.093907 s |
| Median first finest arrival | 8.538775 s | 3.911201 s |

Observed useful goodput increased **3.735668×** and the completed-request weighted
mean fell **71.4443%**. Request statistics include setup/drain and completed DATA,
EMPTY and REUSE; neither cohort completed ABSENT/NOT_READY records. Percentiles
are histogram bounds. First arrivals mean the first validated DATA or EMPTY at
that level, not rendered coverage or completion of the visible area. Jain fairness
describes 30 independent virtual paths, not competition with other controllers at
one shared bottleneck. See [CUBIC delivery](../../Codex_Tools/Voxy_Workflow_Tooling/tasks/server-streaming-efficiency-20261007/runs/9c31532c-ee15-41cb-9561-fe4fa0de543d/cubic-cohort-delivery.json)
and [BBR delivery](../../Codex_Tools/Voxy_Workflow_Tooling/tasks/server-streaming-efficiency-20261007/runs/98ab23e4-2176-4e18-8cf4-6e25d6a655b8/bbr-cohort-delivery.json).

The source work was warm in both cases: raw comparisons matched 118,531/118,784
(99.7870%) CUBIC and 96,071/96,256 (99.8078%) BBR. All source-record reads, including
actual materialization, totaled 119,148/96,468; semantic inspections totaled
253/185 and materialized chunks 364/212. Inclusive SourceInspect CPU per source
record was 8.792/8.393 µs and allocation traffic 876/815 B per record. Compressed
hashing cost 0.769/0.756 ns per hashed byte, with zero recorded allocations.
Different refresh/save work and companion population therefore remain explicit;
the lower BBR source totals are not another source-algorithm improvement.
[CUBIC source metrics](../../Codex_Tools/Voxy_Workflow_Tooling/tasks/server-streaming-efficiency-20261007/runs/9c31532c-ee15-41cb-9561-fe4fa0de543d/cubic-selected-metrics.json)
and [BBR source metrics](../../Codex_Tools/Voxy_Workflow_Tooling/tasks/server-streaming-efficiency-20261007/runs/98ab23e4-2176-4e18-8cf4-6e25d6a655b8/bbr-selected-metrics.json)
retain normalization and inclusive-stage limits.

Wholly sampled phase intervals give these per-client rates in kbps. Boundary
intervals are excluded, so useful rates differ from full-plateau means:

| Phase | CUBIC useful | CUBIC charged down / up | BBR useful | BBR charged down / up |
|---|---:|---:|---:|---:|
| Coarse | 120.932 | 146.634 / 10.986 | 510.173 | 626.903 / 42.380 |
| Detail/movement | 120.685 | 146.614 / 11.144 | 491.167 | 602.093 / 42.178 |
| Warm revisit | 115.792 | 143.823 / 17.207 | 284.055 | 367.205 / 43.809 |
| Combined interior intervals | 119.544 | 145.918 / 12.612 | 447.619 | 553.927 / 42.653 |

Charged traffic includes IP overhead and attempts later dropped by the relay.
The old sampled `shaped_traffic_bps` totals combine UP+DOWN (158.530/596.580 kbps
over these interior intervals); the configured 1 Mbps cap applies separately to
each direction. The [CUBIC directional extension](../../Codex_Tools/Voxy_Workflow_Tooling/tasks/server-streaming-efficiency-20261007/runs/9c31532c-ee15-41cb-9561-fe4fa0de543d/cubic-plateau-directional-traffic.json)
and [BBR directional extension](../../Codex_Tools/Voxy_Workflow_Tooling/tasks/server-streaming-efficiency-20261007/runs/98ab23e4-2176-4e18-8cf4-6e25d6a655b8/bbr-plateau-directional-traffic.json)
reconcile exactly with those duplex totals. These are relay service counters,
not direct wire timing.
Interior-interval fake-connection Quinn lost-packet totals were 10,023/31,971 and reported lost
bytes 521,623/1,625,527. Lost bytes are **not exact retransmitted bytes**; packet
overhead and charged-minus-payload differences are not a retransmission estimate.
Both controllers underfilled the 1 Mbps cap. Residual pipeline, ordered-stream or
offered-demand causes were not isolated; the fake workload has 16 pending requests
and two terrain streams.

| Public sampled transport | CUBIC | BBR |
|---|---:|---:|
| Connection gauge samples | 14,220 | 14,370 |
| Median / p95 cwnd | 6,361 / 10,392 B | 51,600 / 749,478 B |
| Maximum sampled cwnd | 16,067 B | 1,079,376 B |
| Median / p95 smoothed RTT | 299.765 / 303.020 ms | 301.583 / 306.543 ms |
| Sampled waiting-source requests | 0 | 0 |

The larger BBR window accompanies improved impaired delivery; the window alone
does not isolate the critical path. Flight bytes, application-limited and recovery
state remain null/unknown through the pinned public API. Sampled idle lanes do not
prove application-limited transport. BBR's median 6.617008 Mbps target pacing is
controller telemetry, not an independently configured packet pacer.
[CUBIC transport](../../Codex_Tools/Voxy_Workflow_Tooling/tasks/server-streaming-efficiency-20261007/runs/9c31532c-ee15-41cb-9561-fe4fa0de543d/cubic-plateau-transport.json)
and [BBR transport](../../Codex_Tools/Voxy_Workflow_Tooling/tasks/server-streaming-efficiency-20261007/runs/98ab23e4-2176-4e18-8cf4-6e25d6a655b8/bbr-plateau-transport.json)
retain public distributions and unavailable-field counts.

| Resource observation | CUBIC | BBR |
|---|---:|---:|
| Adjacent-selection native CPU / mean cores | 9.73 s / 0.04046 | 18.48 s / 0.07668 |
| Native CPU per useful decimal MB | 0.09010 s | 0.04581 s |
| Mean native anonymous RSS | 135.276 MB | 134.038 MB |
| Maximum sampled total native RSS | 150.487 MB | 147.743 MB |
| Generator mean cores / sampled peak RSS | 0.03584 / 67.584 MB | 0.09686 / 114.405 MB |
| Testing Java / Main mean cores | 0.34362 / 2.06458 | 0.34060 / 1.81547 |
| Native charged memory mean / sampled plateau max | 376.710 / 529.826 MB | 423.867 / 550.719 MB |
| Native process-caused read / write bytes | 706.834 / 537.596 MB | 646.029 / 403.030 MB |

Native CPU increased with greater delivery; CPU per useful MB fell 49.16%.
Anonymous RSS differs by about 1 MB, so no resident-memory improvement is
established. Charged memory includes file-cache ownership and is not allocator
savings. Native `memory.max=999997440`, swap cap zero and Java heap 1/4 GiB were
preserved; saved max/OOM/OOM-kill increments were zero. Cgroup lifetime peaks were
666.685/556.274 MB, distinct from plateau-only samples. Device `io.stat` was
unavailable. Native resource endpoints and source timing endpoints are adjacent
selections rather than identical intervals; complete interior native CPU is
9.70/18.40 s. The generator is one Rust process with 30 QUIC clients and no renderer.

[CUBIC resources](../../Codex_Tools/Voxy_Workflow_Tooling/tasks/server-streaming-efficiency-20261007/runs/9c31532c-ee15-41cb-9561-fe4fa0de543d/cubic-plateau-resources.json)
and [BBR resources](../../Codex_Tools/Voxy_Workflow_Tooling/tasks/server-streaming-efficiency-20261007/runs/98ab23e4-2176-4e18-8cf4-6e25d6a655b8/bbr-plateau-resources.json)
retain all 32 logical CPUs, 16 physical-core sibling groups, sampled target
threads, host processes and coverage rows. Host busy accounting was 17.84/12.91%;
individual logical CPU means ranged 7.28–74.08/3.05–72.23%. These include unrelated
work. Busiest sampled native worker CPU was 0.64/1.31 s over the plateau, with no
persistent native core saturation shown. A thread's last sampled processor cannot
assign its whole interval CPU to that core; scheduler-running/wait counters were
unavailable. Main process behavior was sampled; Main thread statistics were not.

Independent sampling remained active through tool handoffs. Plateau continuous
clock intervals were 0.670–1.330/0.985–1.015 s, with 240 samples each; capture duration
reached 478.6/42.4 ms. Operator-sampler gaps reached 4.140/2.882 s. Combined resource
observation gaps reached 1.443/1.012 s, but backend status ages reached
3.111/3.117 s, so public gauges can repeat stale values or miss bursts. These are
distinct clocks and limitations, not uniform fresh one-second gauges. Exact
boundaries and age definitions are in the
[sampling extension](../../Codex_Tools/Voxy_Workflow_Tooling/tasks/server-streaming-efficiency-20261007/matched-controller-sampling-extension.json). Packet/byte,
seeded-drop, service, cap and final-client/heartbeat checks passed structurally;
The root acceptance assessment below covers these observation gaps, the
completed clean-path controls and final restoration. Same Main identity does not make live saves,
background load or page cache identical, and fresh fake caches do not prove a
cold server or OS cache.

### Clean-path controls

All three saved controls completed 30 authenticated fake clients for the full 240-second plateau at a 1,000 kbps actual-IP cap in each direction, zero configured added RTT and zero configured loss. Their runner guards exited successfully and root cohort-review receipts mark the pressure cohorts valid. Project acceptance remains external to these saved artifacts.

In the consecutive baseline-CUBIC and optimized-CUBIC measurements, normalized inclusive source inspection CPU was 422.052 -> 8.218 us per examined source chunk (98.05% lower), and allocation requests were 3,175,082.85 -> 751.92 bytes per examined chunk (99.976% lower). These are measured cohort results; the read/decode/cache-hit mix and concurrent world activity are retained below.

Optimized-CUBIC versus optimized-BBR is the controller pairing. Its saved source manifest and factory-only diff change CubicConfig to BbrConfig with the same 12,000-byte initial window. Clean plateau useful goodput was 829.658 -> 825.292 kbps per client; whole-cohort request-weighted completion mean was 502.858 -> 508.295 ms. This comparison does not identify a critical path. Although the compiled source change is confined to the controller factory, these consecutive live cohorts do not hold world content, host pressure or all cache history identical.

Excluded attempt: ed1fffd0-ac13-4d9b-b42a-4462708df3de was interrupted by ENOSPC and is not included in any comparative row or exported metric in this summary. Root handled its runtime cleanup separately.

#### Identity, profile, and scope

|Control|Run ID|Native SHA-256 / epoch|Confirmed plateau / resource endpoints (s)|Source counter interval (s)|
|---|---|---|---|---|
|Baseline CUBIC|19728595-2985-4342-a242-df8f34b47b52|80fdf36d9dc660fc4d71bae7948468d7c507c0a3b70debb9aaead05be9180131 / 1791391926512869741|239.927329 / 239.712787|241.000482|
|Optimized CUBIC|265c2ee9-b367-4f19-9070-a6e33cf84ff3|89c756d11c632ee2bd8bf162c4169db6cae2afb4b28d073103cbf92cb769f1cb / 1791392538471108902|239.943007 / 239.610500|240.999723|
|Optimized BBR|d30fc95b-a564-4c55-9b36-1152520227ce|523696db897d13054aaa81e96df8405f723b1fde00a6cc407af280770812761c / 1791394456128344505|239.931723 / 239.750338|240.999134|

Profile equality checked for clients, duration, cap, added RTT, directional loss, seed, Minecraft connections, owner runtime, relay runtime and output writer: {'clients': 30, 'plateau_seconds': 240, 'full_duplex_cap_kbps': 1000, 'rtt_ms': 0, 'loss_percent_per_direction': 0, 'seed': 17, 'minecraft_connections': 0, 'runtime': 'current_thread', 'relay_runtime': 'one_dedicated_current_thread', 'output_writer': 'dedicated_thread'}. Main remained the same saved process identity, PID 1282851 / start ticks 61190536. That identity does not establish identical live world content between consecutive runs.

The same compiled fake-client runner binary was used in all three controls: SHA-256 ba634d2811fca3e4c46d6623452d0775beea1d06d293f5e6ac7ed9526fee10c7. The calibration-fidelity and root-run receipts record this artifact identity. The differing runner JSONL log hashes below identify captured outputs, not compiled binaries.

Source counters use native snapshots bracketing both possible plateau boundaries. Process/thread CPU uses only complete saved intervals wholly inside the confirmed plateau. Context/PSI/I/O use sampled resource endpoints inside that plateau. Whole-cohort request latency, status counts, and first arrivals include setup and drain. Plateau useful goodput uses sampled client endpoint payload divided by requested 240 seconds; phase goodput and IP rates use wholly sampled phase intervals and their measured client-seconds. These scopes must not be added or substituted.

Absolute native host-monotonic clocks are present for all controls. Runner-origin bracket widths (ms): Baseline CUBIC 72.671, Optimized CUBIC 56.993, Optimized BBR 68.277. Native counters remain non-atomic, resource scans sequential, and boundary tails unsampled.

#### Normalized source work: baseline versus optimized CUBIC

|Stage (inclusive where named)|Baseline CPU us/examined chunk|Optimized CPU us/examined chunk|Baseline allocation bytes/examined chunk|Optimized allocation bytes/examined chunk|
|---|---|---|---|---|
|source_inventory|3.599098|2.835000|951.449|773.678|
|source_inspect|422.051647|8.218348|3,175,082.854|751.917|
|publication_work_inclusive|439.266011|23.409924|3,207,306.085|26,187.976|
|incremental_build_inclusive|10.642898|9.688079|29,524.384|22,917.300|
|source_table_write|0.133030|0.133856|20.673|32.302|
|terrain_write_inclusive|2.716876|2.437689|502.686|505.028|
|fsync_rename|0.367807|0.383897|0.000|0.000|

|Source count / hit counter|Baseline CUBIC|Optimized CUBIC|Optimized BBR|
|---|---|---|---|
|examined_source_chunks|84,954|108,736|83,088|
|decompressed_source_chunks|84,954|369|316|
|raw_match|not separately recorded|108,367|82,772|
|raw_miss|not separately recorded|177|172|
|raw_populated|not separately recorded|154,624|106,496|
|semantic_inspections|not separately recorded|177|172|
|semantic_equal|not separately recorded|128|136|
|semantic_changed|not separately recorded|49|36|
|materialized_cells|not separately recorded|18,874,368|14,155,776|
|materialized_bytes|not separately recorded|226,492,416|169,869,312|
|source_opens|not separately recorded|108|81|
|source_reads|not separately recorded|108,736|83,088|
|open_owner|not separately recorded|14,220|11,678|
|open_waiter|not separately recorded|23|22|
|companion_fallback|not separately recorded|0|0|
|companion_owner_mismatch|not separately recorded|0|0|

The baseline denominator is the legacy anvil_read operation count because its source_chunks counter is absent/zero. The optimized denominator counts all source-record reads, including inspection and actual materialization; it counts read operations, not unique chunk IDs. Baseline zero-valued new diagnostic fields are not interpreted as zero actual reads or zero materialization. Optimized raw_match/raw_miss/raw_populated and semantic/materialization counters are separately scoped; they are not an additive partition unless their source definition explicitly says so. Allocation bytes count successful allocated bytes plus positive realloc growth during inclusive same-thread spans; frees are not deducted. They do not measure retained heap or RSS.

|Regional lookup transaction metric|Baseline CUBIC|Optimized CUBIC|
|---|---|---|
|Completed lookup count|224,192|230,864|
|Same-thread CPU us/completed lookup|17.856572|17.444029|
|Allocation bytes/completed lookup|71,466.405|72,069.960|

Inclusive/nested CPU, wall time and allocations overlap and cannot be summed as exclusive costs. Baseline anvil_nbt covers the full old parse_chunk, including mapping, cells and fingerprint work; optimized anvil_nbt covers fastnbt decoding only. Baseline anvil_read includes the legacy compressed xxHash; optimized compressed BLAKE3 is timed separately. A direct stage-to-stage NBT speedup claim would compare different work.

|Source stage fixed histogram bound (ms)|Baseline p50|Baseline p95|Optimized CUBIC p50|Optimized CUBIC p95|
|---|---|---|---|---|
|source_inspect|(262.144, 524.288]|(262.144, 524.288]|(0.032, 0.064]|(8.192, 16.384]|
|incremental_build_inclusive|(32.768, 65.536]|(262.144, 524.288]|(32.768, 65.536]|(262.144, 524.288]|
|regional_lookup|(0.000, 0.001]|(0.128, 0.256]|(0.000, 0.001]|(0.128, 0.256]|
|fsync_rename|(8.192, 16.384]|(131.072, 262.144]|(8.192, 16.384]|(262.144, 524.288]|

Intervals are lower-exclusive and upper-inclusive fixed histogram bins, not exact latency percentiles.

#### Validated record delivery and completed-request latency

|Metric|Baseline CUBIC|Optimized CUBIC|Optimized BBR|
|---|---|---|---|
|Whole-cohort completed requests|224,285|229,505|227,016|
|Whole-cohort request-weighted mean (ms)|516.626|502.858|508.295|
|Mean of per-client request means (ms)|521.760|508.018|513.376|
|Per-client mean min / max (ms)|435.252 / 619.494|422.705 / 603.063|427.229 / 609.256|
|Whole-cohort maximum completion (ms)|3,388.899|3,328.920|2,881.101|
|First coarse mean / median / max (ms)|635.913 / 635.400 / 678.344|621.097 / 618.745 / 646.190|639.068 / 624.623 / 721.497|
|First finest mean / median / max (ms)|1,645.827 / 1,644.705 / 1,763.685|1,611.031 / 1,613.544 / 1,694.277|1,637.577 / 1,635.796 / 1,781.141|
|Sampled plateau useful goodput mean / min / max (kbps/client)|818.403 / 806.951 / 825.218|829.658 / 821.371 / 836.612|825.292 / 813.146 / 832.877|
|Sampled useful-goodput Jain index|0.99996972|0.99997276|0.99996387|

|Whole-cohort fixed completion bound (ms)|Baseline CUBIC|Optimized CUBIC|Optimized BBR|
|---|---|---|---|
|p50|(262.144, 524.288]|(262.144, 524.288]|(262.144, 524.288]|
|p95|(524.288, 1,048.576]|(524.288, 1,048.576]|(524.288, 1,048.576]|
|p99_5|(1,048.576, 2,097.152]|(1,048.576, 2,097.152]|(1,048.576, 2,097.152]|

|Whole-cohort completed status|Baseline CUBIC|Optimized CUBIC|Optimized BBR|
|---|---|---|---|
|data|147,852|149,485|149,021|
|empty|41,318|44,605|42,636|
|reuse|35,115|35,415|35,359|
|absent|0|0|0|
|not_ready|0|0|0|

Latency weights every completed request equally, including DATA/EMPTY/REUSE/ABSENT statuses; the client-mean row weights clients equally. First coarse/finest is the first validated DATA or EMPTY record at that level from client start, with 30 observations per control. Neither establishes rendered arrival, all visible terrain completion, nor real-PC behavior. Histogram bounds use the full completed-request cohort including setup/drain and cannot be presented as plateau-only percentiles.

#### Actual-IP service rates and useful goodput by phase

|Control / phase|Retained client-seconds|Down actual-IP kbps/client|Up actual-IP kbps/client|Duplex sum kbps/client|Useful goodput kbps/client|Exact phase completed payload bytes|
|---|---|---|---|---|---|---|
|Baseline CUBIC / 0|2,699.980780|916.896|59.246|976.142|840.550|283,683,673|
|Baseline CUBIC / 1|2,669.995357|919.833|60.229|980.062|842.736|284,367,887|
|Baseline CUBIC / 2|1,769.996083|835.526|96.992|932.518|741.078|167,081,448|
|Optimized CUBIC / 0|2,670.005045|915.351|59.306|974.657|839.097|283,209,086|
|Optimized CUBIC / 1|2,669.985988|898.678|59.041|957.719|828.486|279,675,970|
|Optimized CUBIC / 2|1,794.975484|913.655|105.291|1,018.946|812.560|182,828,468|
|Optimized BBR / 0|2,670.023087|924.549|59.277|983.827|840.726|283,769,092|
|Optimized BBR / 1|2,669.998553|920.256|59.772|980.028|844.127|284,902,132|
|Optimized BBR / 2|1,774.993659|876.930|100.957|977.887|778.225|175,255,165|

Phases are requested plateau seconds [0,90), [90,180), [180,240). Directional rates reconcile exactly with the old sampled_phase_traffic duplex totals. The old shaped_traffic_bps field sums UP+DOWN actual-IP service bytes. It must not be labeled downstream or compared as one direction against a 1 Mbps cap. Exact phase payload classifies completed records and has different inclusion from sampled phase rates. Service bytes are accounted before configured drop; delivered bytes are accounted after drop. No configured drops occurred in these clean controls. These are relay counters rather than direct kernel/wire timing.

#### Native server controller observations

|Gauge / counter (confirmed plateau)|Optimized CUBIC|Optimized BBR|
|---|---|---|
|Controller|['cubic']|['bbr']|
|Initial window bytes|[12000]|[12000]|
|Connection gauge samples|14,010|14,070|
|cwnd bytes min / mean / max|236,224 / 13,418,985 / 26,662,228|5,740 / 27,117 / 650,384|
|Smoothed RTT ms min / mean / max|15.855 / 57.165 / 158.735|14.114 / 70.925 / 166.909|
|Controller pacing metric bps mean / sample count|unavailable / 0|8,332,798.113 / 14,070|
|Native estimated lost packets (sampled connection deltas)|0|0|
|Native estimated lost bytes (sampled connection deltas)|0|0|

Controller pacing rate is the public Quinn controller metric in bits/s; this implementation does not use it to set independent packet pacing. Flight bytes, application-limited state and recovery state are unavailable through the pinned public API: all nonnull-sample counts are zero. Queue/lane/RTT/cwnd distributions pool sampled gauges, not event, packet or request percentiles. Idle samples do not prove transport was application-limited. Quinn lost bytes do not measure retransmitted bytes. Native server estimates above are distinct from runner fake/client-side transmit-loss estimates and directional relay drops.

#### Process and thread CPU

|Control / process role|PID / start ticks|CPU seconds retained|Mean logical-core equivalents|Peak sampled interval % of one core|Sampled RSS max bytes|
|---|---|---|---|---|---|
|Baseline CUBIC / Main_read_only|1282851 / 61190536|442.140|1.842808|302.743|18,539,814,912|
|Baseline CUBIC / Testing_java|1452473 / 61506160|81.400|0.339269|140.152|5,208,133,632|
|Baseline CUBIC / native|1453538 / 61507539|62.790|0.261704|120.729|156,377,088|
|Baseline CUBIC / generator:precise-baseline-cubic-control|1461824 / 61527432|40.880|0.170385|76.987|133,091,328|
|Baseline CUBIC / observer|1461612 / 61527057|10.580|0.044097|83.026|82,833,408|
|Baseline CUBIC / observer|1455209 / 61511548|9.660|0.040262|106.049|50,356,224|
|Optimized CUBIC / Main_read_only|1282851 / 61190536|449.760|1.874445|500.104|18,478,710,784|
|Optimized CUBIC / Testing_java|1500835 / 61567341|83.240|0.346916|189.255|4,845,916,160|
|Optimized CUBIC / generator:precise-optimized-cubic-control|1522577 / 61603914|40.350|0.168165|71.693|156,581,888|
|Optimized CUBIC / native|1501915 / 61568728|27.920|0.116361|67.744|150,065,152|
|Optimized CUBIC / observer|1522441 / 61603456|10.240|0.042677|74.393|82,219,008|
|Optimized CUBIC / observer|1505334 / 61572535|9.440|0.039343|118.707|32,010,240|
|Optimized BBR / Main_read_only|1282851 / 61190536|449.610|1.873908|881.874|18,531,397,632|
|Optimized BBR / Testing_java|1610784 / 61758628|79.030|0.329385|120.806|4,906,659,840|
|Optimized BBR / generator:precise-optimized-bbr-control2|1624919 / 61778768|40.940|0.170632|110.421|154,169,344|
|Optimized BBR / native|1613658 / 61760444|30.550|0.127328|196.721|161,947,648|
|Optimized BBR / observer|1624791 / 61778353|10.380|0.043262|35.042|75,677,696|
|Optimized BBR / observer|1616585 / 61764019|9.550|0.039803|7.743|29,941,760|

|Control / thread role|Distinct retained TID identities|Summed thread CPU seconds|Largest retained thread CPU / name / TID|
|---|---|---|---|
|Baseline CUBIC / native|72|62.380|2.950 / tokio-rt-worker / 1461834|
|Baseline CUBIC / generator|38|40.610|6.940 / live_pressure / 1461824|
|Baseline CUBIC / Testing_java|261|81.110|9.770 / Server thread / 1453243|
|Baseline CUBIC / Main_read_only|unavailable|unavailable|unavailable|
|Baseline CUBIC / observer|2|20.240|10.580 / python3 / 1461612|
|Optimized CUBIC / native|69|27.690|1.310 / tokio-rt-worker / 1501923|
|Optimized CUBIC / generator|35|40.160|7.040 / live_pressure / 1522577|
|Optimized CUBIC / Testing_java|243|83.090|11.130 / Server thread / 1501837|
|Optimized CUBIC / Main_read_only|unavailable|unavailable|unavailable|
|Optimized CUBIC / observer|2|19.680|10.240 / python3 / 1522441|
|Optimized BBR / native|69|30.350|1.490 / tokio-rt-worker / 1613681|
|Optimized BBR / generator|34|40.710|7.310 / live_pressure / 1624919|
|Optimized BBR / Testing_java|262|78.610|12.010 / Server thread / 1613512|
|Optimized BBR / Main_read_only|unavailable|unavailable|unavailable|
|Optimized BBR / observer|2|19.930|10.380 / python3 / 1624791|

Process RSS includes shared mapped pages and is not exclusive physical RAM. Main process CPU is available for the same guarded identity; Main thread stats were not collected. Thread/process totals differ because short-lived threads and first/last lifetime tails require two saved samples. Process and thread 100% equals one logical CPU. Last-processor samples do not assign interval CPU to a native-server core. Scheduler running/wait counters are unavailable in these saved tables.

#### All host logical CPUs

|Host CPU (includes unrelated work)|Baseline busy %|Optimized CUBIC busy %|Optimized BBR busy %|
|---|---|---|---|
|cpu0|16.170|14.351|15.829|
|cpu1|22.054|20.344|21.930|
|cpu2|22.250|20.232|21.440|
|cpu3|16.655|14.230|16.204|
|cpu4|17.643|16.404|14.991|
|cpu5|21.145|18.330|19.777|
|cpu6|14.880|13.034|14.017|
|cpu7|12.497|11.165|11.959|
|cpu8|8.268|5.907|4.969|
|cpu9|7.766|5.637|4.406|
|cpu10|8.066|6.013|4.844|
|cpu11|8.002|5.797|4.737|
|cpu12|8.416|6.554|5.473|
|cpu13|7.780|5.755|4.364|
|cpu14|7.582|5.484|4.013|
|cpu15|7.239|5.204|3.733|
|cpu16|20.636|16.883|20.034|
|cpu17|65.306|61.351|60.721|
|cpu18|36.185|28.186|34.821|
|cpu19|18.369|15.224|18.496|
|cpu20|71.113|67.568|77.525|
|cpu21|26.707|22.552|28.530|
|cpu22|15.646|13.341|16.093|
|cpu23|13.179|11.204|13.103|
|cpu24|8.682|6.284|5.273|
|cpu25|8.172|5.903|4.730|
|cpu26|8.878|6.424|5.457|
|cpu27|8.535|6.121|5.092|
|cpu28|9.439|6.886|5.948|
|cpu29|8.211|6.008|4.738|
|cpu30|7.751|5.790|4.234|
|cpu31|7.458|5.425|4.019|

|Host aggregate accounting|Baseline CUBIC|Optimized CUBIC|Optimized BBR|
|---|---|---|---|
|Busy core-seconds, excluding idle/iowait|1,287.140|1,086.600|1,134.360|
|Busy % of all CPU accounting|16.843|14.383|15.133|
|Iowait CPU-seconds|2,793.560|2,805.110|2,747.690|

The saved plateau-resource JSON also contains every physical-core sibling group. Host CPU/iowait includes all concurrent work. SMT logical sibling averages do not measure physical execution capacity and these values cannot be attributed to native processing alone.

#### Memory, events, and Java collector context

|Metric (sampled or scoped as labeled)|Baseline CUBIC|Optimized CUBIC|Optimized BBR|
|---|---|---|---|
|Native RSS mean / max bytes (adjacent window)|150,748,778 / 156,377,088|144,413,441 / 150,065,152|156,214,787 / 161,947,648|
|Native anonymous RSS mean / max bytes (adjacent window)|144,372,058 / 150,298,624|138,364,146 / 143,986,688|149,828,723 / 155,791,360|
|Native file RSS mean / max bytes (adjacent window)|6,376,720 / 6,537,216|6,049,294 / 6,410,240|6,386,064 / 6,430,720|
|Native-service cgroup charged memory mean / max bytes (inside plateau)|366,812,036 / 500,453,376|753,372,105 / 983,855,104|554,787,716 / 840,638,464|
|Cgroup lifetime memory.peak max bytes|506,064,896|983,855,104|846,757,888|
|Cgroup memory.max / memory.swap.max|['999997440'] / ['0']|['999997440'] / ['0']|['999997440'] / ['0']|
|Testing Java heap used mean / max bytes|1,832,278,586 / 2,545,942,528|2,268,004,773 / 4,016,046,080|2,546,045,373 / 4,028,628,992|
|Testing Java heap committed max / heap max bytes|3,953,131,520 / 4,294,967,296|4,060,086,272 / 4,294,967,296|4,055,891,968 / 4,294,967,296|

|memory.events delta key|Baseline CUBIC|Optimized CUBIC|Optimized BBR|
|---|---|---|---|
|high|0|0|0|
|low|0|0|0|
|max|0|0|0|
|oom|0|0|0|
|oom_group_kill|0|0|0|
|oom_kill|0|0|0|
|sock_throttled|0|0|0|

|Control / Java GC counter|Count delta|Reported time delta ms|
|---|---|---|
|Baseline CUBIC / ZGC Minor Cycles|0|0|
|Baseline CUBIC / ZGC Minor Pauses|0|0|
|Baseline CUBIC / ZGC Major Cycles|4|4,396|
|Baseline CUBIC / ZGC Major Pauses|20|0|
|Optimized CUBIC / ZGC Minor Cycles|1|153|
|Optimized CUBIC / ZGC Minor Pauses|3|0|
|Optimized CUBIC / ZGC Major Cycles|2|2,412|
|Optimized CUBIC / ZGC Major Pauses|10|0|
|Optimized BBR / ZGC Minor Cycles|1|159|
|Optimized BBR / ZGC Minor Pauses|3|0|
|Optimized BBR / ZGC Major Cycles|2|4,525|
|Optimized BBR / ZGC Major Pauses|10|0|

Cgroup memory includes charged page cache; first ownership/warming differs from allocator requests and does not establish an allocation gain. memory.peak is lifetime high-water rather than a plateau RSS maximum. No sampled memory-event/OOM increments occurred. Collector cycle duration is not pause duration or request-path attribution; pause counters retain their separately reported values.

#### Exact /proc I/O accounting

|Control / process|rchar|wchar|read_bytes|write_bytes|syscr|syscw|cancelled_write_bytes|
|---|---|---|---|---|---|---|---|
|Baseline CUBIC / native|3,935,816,031|299,422,980|1,437,822,976|284,557,312|582,640|4,953,557|0|
|Baseline CUBIC / java|25,928,385|89,201,265|20,553,728|86,822,912|1,184,111|17,505|0|
|Baseline CUBIC / Main_read_only|123,358,406|122,327,420|167,579,648|43,515,904|33,324|902,823|0|
|Optimized CUBIC / native|4,291,775,924|384,576,442|1,078,181,888|370,114,560|486,044|4,996,830|0|
|Optimized CUBIC / java|27,586,060|90,658,871|25,956,352|88,096,768|1,226,434|17,588|0|
|Optimized CUBIC / Main_read_only|145,469,421|170,330,785|203,132,928|72,781,824|37,976|952,279|0|
|Optimized BBR / native|3,626,842,887|205,962,493|1,783,631,872|190,861,312|408,822|5,003,304|0|
|Optimized BBR / java|26,583,016|90,216,476|196,468,736|88,031,232|1,278,094|17,363|0|
|Optimized BBR / Main_read_only|131,640,873|143,023,195|1,319,157,760|77,000,704|36,357|1,049,843|0|

These are /proc process accounting deltas over the saved interior resource endpoints. rchar/wchar count logical read/write characters; read_bytes/write_bytes are the kernel process storage-I/O accounting keys. Neither is a physical-device trace or proof of request causality. syscr/syscw count I/O calls, not bytes. Native cgroup io.stat was unavailable in every retained sample, so device-level cgroup I/O remains unmeasured.

#### Pressure-stall context

|Control / pressure scope|Some total delta s|Full total delta s|Max sampled some avg10 %|Max sampled full avg10 %|
|---|---|---|---|---|
|Baseline CUBIC / native-service cgroup cpu.pressure|0.095|0.083|0.000|0.000|
|Baseline CUBIC / native-service cgroup io.pressure|3.727|3.705|9.900|9.780|
|Baseline CUBIC / native-service cgroup memory.pressure|0.372|0.370|2.500|2.500|
|Baseline CUBIC / host cpu|3.413|0.000|1.440|0.000|
|Baseline CUBIC / host io|152.931|131.730|71.540|61.660|
|Baseline CUBIC / host memory|3.485|3.162|16.230|14.330|
|Optimized CUBIC / native-service cgroup cpu.pressure|0.078|0.068|0.000|0.000|
|Optimized CUBIC / native-service cgroup io.pressure|4.245|4.234|10.820|10.820|
|Optimized CUBIC / native-service cgroup memory.pressure|0.000|0.000|0.000|0.000|
|Optimized CUBIC / host cpu|3.155|0.000|1.540|0.000|
|Optimized CUBIC / host io|153.787|134.785|70.510|63.670|
|Optimized CUBIC / host memory|0.448|0.399|2.290|2.110|
|Optimized BBR / native-service cgroup cpu.pressure|0.085|0.072|0.000|0.000|
|Optimized BBR / native-service cgroup io.pressure|4.220|4.206|7.600|7.600|
|Optimized BBR / native-service cgroup memory.pressure|0.052|0.051|0.100|0.100|
|Optimized BBR / host cpu|3.572|0.000|1.360|0.000|
|Optimized BBR / host io|154.311|131.891|69.070|60.140|
|Optimized BBR / host memory|1.303|1.186|2.700|2.360|

PSI totals are cumulative wall-time pressure over the saved endpoint window, with some/full scopes kept separate. Host pressure includes unrelated workloads; native-service cgroup pressure has a different population. High host I/O pressure is observed context, not proof that it limited a terrain request.

#### Saved sampling clocks and backend age

|Control|Continuous samples / max start gap s|Continuous max lateness / scan s|Operator samples / max start gap s|Operator max scan s|Backend status-file age mean / max ms|
|---|---|---|---|---|---|
|Baseline CUBIC|239 / 2.782217|1.783245 / 0.234034|232 / 4.129012|4.128196|346.233 / 2938.970|
|Optimized CUBIC|239 / 2.819680|1.820645 / 0.166728|227 / 3.913671|3.912121|471.316 / 2963.764|
|Optimized BBR|239 / 1.385693|0.386775 / 0.526104|228 / 3.558412|3.557627|595.603 / 2593.294|

These are saved journal timestamps inside each confirmed plateau, with maximum start gaps including clipped boundary gaps. The continuous observer and finite operator sampler are shown separately; operator interval fields can reset across calls, so the derived start gaps preserve those boundaries. Scan duration is elapsed capture work, not request latency. All retained journal/resource alarm flags are false and native epoch/artifact observations match. Backend age is observed_at minus captured status-file mtime; it is not a direct timestamp for every native gauge. Sequential scans, status age and between-sample gaps limit current-gauge claims. No sampled alarm does not establish absence of a between-sample excursion.

Sampling evidence: [baseline-plateau-sampling.json](/home/aerosmp/Desktop/Codex_Tools/Voxy_Workflow_Tooling/tasks/server-streaming-efficiency-20261007/runs/19728595-2985-4342-a242-df8f34b47b52/baseline-plateau-sampling.json), [cubic-plateau-sampling.json](/home/aerosmp/Desktop/Codex_Tools/Voxy_Workflow_Tooling/tasks/server-streaming-efficiency-20261007/runs/265c2ee9-b367-4f19-9070-a6e33cf84ff3/cubic-plateau-sampling.json), [bbr-plateau-sampling.json](/home/aerosmp/Desktop/Codex_Tools/Voxy_Workflow_Tooling/tasks/server-streaming-efficiency-20261007/runs/d30fc95b-a564-4c55-9b36-1152520227ce/bbr-plateau-sampling.json).

#### Relay fidelity

|Control / relay direction|Serviced packets (whole cohort)|Configured drops|Service rate violations|UDP-send completion rate violations|Mean extra delivery late ms|Max extra delivery late ms|p99.5 fixed late bound ms|
|---|---|---|---|---|---|---|---|
|Baseline CUBIC / down|570,283|0|0|0|1.001|3.505|(1.024, 2.048]|
|Baseline CUBIC / up|518,827|0|0|0|0.976|3.344|(1.024, 2.048]|
|Optimized CUBIC / down|571,321|0|0|0|1.007|2.974|(1.024, 2.048]|
|Optimized CUBIC / up|528,217|0|0|0|0.963|2.673|(1.024, 2.048]|
|Optimized BBR / down|564,788|0|0|0|1.009|2.476|(1.024, 2.048]|
|Optimized BBR / up|518,978|0|0|0|0.957|2.867|(1.024, 2.048]|

All saved directional byte/packet/service reconciliations, seeded zero-drop sequence checks and whole-service rate ceilings with one-packet allowance passed. Runner finals are present for all clients; dedicated relay runtime/output writer and final heartbeat are present; socket errors and truncations are zero. The added RTT is zero, so lateness as a fraction of configured added RTT is undefined. Per-direction service/UDP-send cap checks describe internal rolling windows; kernel/wire timing and close/drain tails can be unsampled. Root cohort-review/closed receipts contain later cleanup conclusions; an earlier sampled cleanup_pending observation in final-analysis is not itself the final cleanup proof.

#### Saved evidence

The controller source identity receipts are [precise-controller-comparison-inputs.json](/home/aerosmp/Desktop/Codex_Tools/Voxy_Workflow_Tooling/tasks/server-streaming-efficiency-20261007/precise-controller-comparison-inputs.json) and [precise-factory-only.diff](/home/aerosmp/Desktop/Codex_Tools/Voxy_Workflow_Tooling/tasks/server-streaming-efficiency-20261007/precise-factory-only.diff); BBR candidate packaging is [precise-optimized-bbr-candidate.json](/home/aerosmp/Desktop/Codex_Tools/Voxy_Workflow_Tooling/tasks/server-streaming-efficiency-20261007/precise-optimized-bbr-candidate.json). The saved fake-client source snapshot hashes to the compiled manifest; its connection.stats().path counters are client-side observations, distinct from native server-side backend counters.

**Baseline CUBIC**: [calibration-fidelity-evidence.json](/home/aerosmp/Desktop/Codex_Tools/Voxy_Workflow_Tooling/tasks/server-streaming-efficiency-20261007/runs/19728595-2985-4342-a242-df8f34b47b52/calibration-fidelity-evidence.json), [root-run.json](/home/aerosmp/Desktop/Codex_Tools/Voxy_Workflow_Tooling/tasks/server-streaming-efficiency-20261007/runs/19728595-2985-4342-a242-df8f34b47b52/root-run.json), [closed-root-driver.json](/home/aerosmp/Desktop/Codex_Tools/Voxy_Workflow_Tooling/tasks/server-streaming-efficiency-20261007/runs/19728595-2985-4342-a242-df8f34b47b52/closed-root-driver.json), [root-cohort-review.json](/home/aerosmp/Desktop/Codex_Tools/Voxy_Workflow_Tooling/tasks/server-streaming-efficiency-20261007/runs/19728595-2985-4342-a242-df8f34b47b52/root-cohort-review.json), [final-analysis.json](/home/aerosmp/Desktop/Codex_Tools/Voxy_Workflow_Tooling/tasks/server-streaming-efficiency-20261007/runs/19728595-2985-4342-a242-df8f34b47b52/final-analysis.json), [baseline-selected-metrics.json](/home/aerosmp/Desktop/Codex_Tools/Voxy_Workflow_Tooling/tasks/server-streaming-efficiency-20261007/runs/19728595-2985-4342-a242-df8f34b47b52/baseline-selected-metrics.json), [baseline-cohort-delivery.json](/home/aerosmp/Desktop/Codex_Tools/Voxy_Workflow_Tooling/tasks/server-streaming-efficiency-20261007/runs/19728595-2985-4342-a242-df8f34b47b52/baseline-cohort-delivery.json), [baseline-plateau-transport.json](/home/aerosmp/Desktop/Codex_Tools/Voxy_Workflow_Tooling/tasks/server-streaming-efficiency-20261007/runs/19728595-2985-4342-a242-df8f34b47b52/baseline-plateau-transport.json), [baseline-plateau-resources.json](/home/aerosmp/Desktop/Codex_Tools/Voxy_Workflow_Tooling/tasks/server-streaming-efficiency-20261007/runs/19728595-2985-4342-a242-df8f34b47b52/baseline-plateau-resources.json), [baseline-plateau-context.json](/home/aerosmp/Desktop/Codex_Tools/Voxy_Workflow_Tooling/tasks/server-streaming-efficiency-20261007/runs/19728595-2985-4342-a242-df8f34b47b52/baseline-plateau-context.json), [baseline-plateau-directional-traffic.json](/home/aerosmp/Desktop/Codex_Tools/Voxy_Workflow_Tooling/tasks/server-streaming-efficiency-20261007/runs/19728595-2985-4342-a242-df8f34b47b52/baseline-plateau-directional-traffic.json).

Input SHA-256: runner JSONL log 2b1ac31ea2afc5eac5fc112c143e51fd3f3fc14685594e8b32786247da8efb72; uncompressed resources 0fca7b6a5a9512398de3b6c3b55a0f44dbe4a12adc7ae8f2202ab89c710d1e58; native log 621dfafdeda844c8389effefd51b08b3ca447353786fe6b7692e47a1211e45de. Analyzer warnings: [].

The raw resources.jsonl was losslessly archived as resources.jsonl.gz after these analyses became terminal. Original byte count/SHA-256 and matching round-trip decompression SHA-256 are retained in [raw-resource-archive-manifest.json](/home/aerosmp/Desktop/Codex_Tools/Voxy_Workflow_Tooling/tasks/server-streaming-efficiency-20261007/runs/19728595-2985-4342-a242-df8f34b47b52/raw-resource-archive-manifest.json). The JSON metrics above retain the original uncompressed source digest; the plain raw-resource path may no longer exist.

Disposable derived CSVs were losslessly archived after all reads ended; [derived-csv-archive-manifest.json](/home/aerosmp/Desktop/Codex_Tools/Voxy_Workflow_Tooling/tasks/server-streaming-efficiency-20261007/runs/19728595-2985-4342-a242-df8f34b47b52/derived-csv-archive-manifest.json) retains original CSV hashes and matching decompression hashes. The plateau-resource JSON contains the original CSV digests.

**Optimized CUBIC**: [calibration-fidelity-evidence.json](/home/aerosmp/Desktop/Codex_Tools/Voxy_Workflow_Tooling/tasks/server-streaming-efficiency-20261007/runs/265c2ee9-b367-4f19-9070-a6e33cf84ff3/calibration-fidelity-evidence.json), [root-run.json](/home/aerosmp/Desktop/Codex_Tools/Voxy_Workflow_Tooling/tasks/server-streaming-efficiency-20261007/runs/265c2ee9-b367-4f19-9070-a6e33cf84ff3/root-run.json), [closed-root-driver.json](/home/aerosmp/Desktop/Codex_Tools/Voxy_Workflow_Tooling/tasks/server-streaming-efficiency-20261007/runs/265c2ee9-b367-4f19-9070-a6e33cf84ff3/closed-root-driver.json), [root-cohort-review.json](/home/aerosmp/Desktop/Codex_Tools/Voxy_Workflow_Tooling/tasks/server-streaming-efficiency-20261007/runs/265c2ee9-b367-4f19-9070-a6e33cf84ff3/root-cohort-review.json), [final-analysis.json](/home/aerosmp/Desktop/Codex_Tools/Voxy_Workflow_Tooling/tasks/server-streaming-efficiency-20261007/runs/265c2ee9-b367-4f19-9070-a6e33cf84ff3/final-analysis.json), [cubic-selected-metrics.json](/home/aerosmp/Desktop/Codex_Tools/Voxy_Workflow_Tooling/tasks/server-streaming-efficiency-20261007/runs/265c2ee9-b367-4f19-9070-a6e33cf84ff3/cubic-selected-metrics.json), [cubic-cohort-delivery.json](/home/aerosmp/Desktop/Codex_Tools/Voxy_Workflow_Tooling/tasks/server-streaming-efficiency-20261007/runs/265c2ee9-b367-4f19-9070-a6e33cf84ff3/cubic-cohort-delivery.json), [cubic-plateau-transport.json](/home/aerosmp/Desktop/Codex_Tools/Voxy_Workflow_Tooling/tasks/server-streaming-efficiency-20261007/runs/265c2ee9-b367-4f19-9070-a6e33cf84ff3/cubic-plateau-transport.json), [cubic-plateau-resources.json](/home/aerosmp/Desktop/Codex_Tools/Voxy_Workflow_Tooling/tasks/server-streaming-efficiency-20261007/runs/265c2ee9-b367-4f19-9070-a6e33cf84ff3/cubic-plateau-resources.json), [cubic-plateau-context.json](/home/aerosmp/Desktop/Codex_Tools/Voxy_Workflow_Tooling/tasks/server-streaming-efficiency-20261007/runs/265c2ee9-b367-4f19-9070-a6e33cf84ff3/cubic-plateau-context.json), [cubic-plateau-directional-traffic.json](/home/aerosmp/Desktop/Codex_Tools/Voxy_Workflow_Tooling/tasks/server-streaming-efficiency-20261007/runs/265c2ee9-b367-4f19-9070-a6e33cf84ff3/cubic-plateau-directional-traffic.json).

Input SHA-256: runner JSONL log ce1cefb2899255070aa2c88146a967c12a17a4eae0f258040ce41ebcd74d3e47; uncompressed resources e807571327b651d860e07a4461f808df39dfc51b1d0f90442e98c08fffcfef8d; native log 8e0041d81eb04be42a58efcd41689c03813c8951f454ebba704c1f0c717613a1. Analyzer warnings: [].

The raw resources.jsonl was losslessly archived as resources.jsonl.gz after these analyses became terminal. Original byte count/SHA-256 and matching round-trip decompression SHA-256 are retained in [raw-resource-archive-manifest.json](/home/aerosmp/Desktop/Codex_Tools/Voxy_Workflow_Tooling/tasks/server-streaming-efficiency-20261007/runs/265c2ee9-b367-4f19-9070-a6e33cf84ff3/raw-resource-archive-manifest.json). The JSON metrics above retain the original uncompressed source digest; the plain raw-resource path may no longer exist.

Disposable derived CSVs were losslessly archived after all reads ended; [derived-csv-archive-manifest.json](/home/aerosmp/Desktop/Codex_Tools/Voxy_Workflow_Tooling/tasks/server-streaming-efficiency-20261007/runs/265c2ee9-b367-4f19-9070-a6e33cf84ff3/derived-csv-archive-manifest.json) retains original CSV hashes and matching decompression hashes. The plateau-resource JSON contains the original CSV digests.

**Optimized BBR**: [calibration-fidelity-evidence.json](/home/aerosmp/Desktop/Codex_Tools/Voxy_Workflow_Tooling/tasks/server-streaming-efficiency-20261007/runs/d30fc95b-a564-4c55-9b36-1152520227ce/calibration-fidelity-evidence.json), [root-run.json](/home/aerosmp/Desktop/Codex_Tools/Voxy_Workflow_Tooling/tasks/server-streaming-efficiency-20261007/runs/d30fc95b-a564-4c55-9b36-1152520227ce/root-run.json), [closed-root-driver.json](/home/aerosmp/Desktop/Codex_Tools/Voxy_Workflow_Tooling/tasks/server-streaming-efficiency-20261007/runs/d30fc95b-a564-4c55-9b36-1152520227ce/closed-root-driver.json), [root-cohort-review.json](/home/aerosmp/Desktop/Codex_Tools/Voxy_Workflow_Tooling/tasks/server-streaming-efficiency-20261007/runs/d30fc95b-a564-4c55-9b36-1152520227ce/root-cohort-review.json), [final-analysis.json](/home/aerosmp/Desktop/Codex_Tools/Voxy_Workflow_Tooling/tasks/server-streaming-efficiency-20261007/runs/d30fc95b-a564-4c55-9b36-1152520227ce/final-analysis.json), [bbr-selected-metrics.json](/home/aerosmp/Desktop/Codex_Tools/Voxy_Workflow_Tooling/tasks/server-streaming-efficiency-20261007/runs/d30fc95b-a564-4c55-9b36-1152520227ce/bbr-selected-metrics.json), [bbr-cohort-delivery.json](/home/aerosmp/Desktop/Codex_Tools/Voxy_Workflow_Tooling/tasks/server-streaming-efficiency-20261007/runs/d30fc95b-a564-4c55-9b36-1152520227ce/bbr-cohort-delivery.json), [bbr-plateau-transport.json](/home/aerosmp/Desktop/Codex_Tools/Voxy_Workflow_Tooling/tasks/server-streaming-efficiency-20261007/runs/d30fc95b-a564-4c55-9b36-1152520227ce/bbr-plateau-transport.json), [bbr-plateau-resources.json](/home/aerosmp/Desktop/Codex_Tools/Voxy_Workflow_Tooling/tasks/server-streaming-efficiency-20261007/runs/d30fc95b-a564-4c55-9b36-1152520227ce/bbr-plateau-resources.json), [bbr-plateau-context.json](/home/aerosmp/Desktop/Codex_Tools/Voxy_Workflow_Tooling/tasks/server-streaming-efficiency-20261007/runs/d30fc95b-a564-4c55-9b36-1152520227ce/bbr-plateau-context.json), [bbr-plateau-directional-traffic.json](/home/aerosmp/Desktop/Codex_Tools/Voxy_Workflow_Tooling/tasks/server-streaming-efficiency-20261007/runs/d30fc95b-a564-4c55-9b36-1152520227ce/bbr-plateau-directional-traffic.json).

Input SHA-256: runner JSONL log 6a275b7a2a484778edfc89e39d2fe9074e8ae58f258daea88e8c7d460f65ebaf; uncompressed resources 8572c6f81500ffe24b2cc8ff3891af88411ffdc26683c321046aa0612a907502; native log 28cde8b07e67d1daa25f82b91632dcb320e175994d2368c88fe12942c1dabefb. Analyzer warnings: [].

This summary is an offline analysis of successfully completed saved controls. It does not assert real-PC/cache/settings/backup-route gates or project acceptance, and does not infer critical-path causation from inclusive work totals, sampled transport state, host I/O pressure, or controller pacing metrics.


## 8. Offline count, artifact and fallback audit

This appendix records an independent read-only audit at 15:46–15:48 UTC on
2026-10-07. It adds no build or live operations. Counts compare the clean pre-task
snapshot at commit `5aaa9c2216c274307958a1bf1c3c787ee0d6490f` with the observed
working tree at `75cf2bb3c5f50757cfcfa834b4adbaddcce1f5a2`. Both use the exact
`voxy-physical` scope, physical `splitlines`, the same extensions/exclusions and
untracked-file inclusion. Embedded `cfg(test)` source remains in its containing
file; standalone native test files are categorized as tests and `live_pressure.rs`
as tools. Audit documents, build output and external toolkit code are outside this
source scope.

| Category | Baseline files | Current files | Baseline lines | Current lines | Line delta |
|---|---:|---:|---:|---:|---:|
| Client | 159 | 159 | 28,755 | 28,755 | 0 |
| Java server | 6 | 6 | 1,158 | 1,158 | 0 |
| Native | 22 | 22 | 12,460 | 13,686 | +1,226 |
| Shared | 1 | 1 | 180 | 180 | 0 |
| Debug Java | 33 | 33 | 6,208 | 6,208 | 0 |
| Tests | 32 | 32 | 8,083 | 8,087 | +4 |
| Tools/harness | 9 | 9 | 2,933 | 3,038 | +105 |
| Total | 262 | 262 | 59,777 | 61,112 | +1,335 |

Both snapshots are stable with no omitted or untracked source files. Source bytes
increase from 2,770,267 to 2,818,221; native source bytes increase from 460,646 to
504,323. There are no added or removed source files. Evidence:
[baseline snapshot](../../Codex_Tools/Voxy_Workflow_Tooling/state/sources/source-b634355bf9df456e972477ae080e019a.json),
[current snapshot](../../Codex_Tools/Voxy_Workflow_Tooling/state/sources/source-391c3f40092748798fb1970773edcc7f.json),
[matching-scope MCP comparison](../../Codex_Tools/Voxy_Workflow_Tooling/state/receipts/3e2dcb1f-87df-48b5-b5d8-8728602c2528.json).

The current source roots contain 114 directories including each declared root and
all its subdirectories, with zero empty directories. The baseline Git tree
reconstructs the same 114 tracked nonempty directories. The historical snapshot
did not record empty directories, so a historical empty-folder delta cannot be
proved. Native remains two directories including its root. The full per-root
directory census and this qualification are saved in
[directory recheck](../../Codex_Tools/Voxy_Workflow_Tooling/tasks/server-streaming-efficiency-20261007/final-source-directory-recheck.json).

All 326 files in the precise-final compiled-input manifest still match their
recorded SHA-256 values, with no missing or changed files. Before/after build input
manifests are identical, fingerprint
`e9b4e06185c9e1f18912b0aff5f070a8da66a1dc3a622c370583eb6064c22149`.
Audit-document changes are excluded by that manifest. This proves identity of the
recorded inputs; it is not another compilation or runtime gate. See
[input recheck](../../Codex_Tools/Voxy_Workflow_Tooling/tasks/server-streaming-efficiency-20261007/final-input-recheck.json).

| Artifact | JAR bytes | Embedded native bytes |
|---|---:|---:|
| Matched baseline CUBIC debug 273 | 1,887,417 | 3,721,904 |
| Optimized BBR debug 273 | 1,927,250 | 3,808,464 |
| Optimized BBR normal 273 | 1,746,028 | 3,548,880 |

The matched debug comparison adds 39,833 JAR bytes and 86,560 native bytes.
Baseline and optimized candidates use identical non-native JAR members. No matched
normal baseline 273 was built, so the normal row is its current absolute size.
Normal/debug 273 JARs still have their recorded hashes, and their embedded native
members exactly match the corresponding built native binaries. See
[artifact recheck](../../Codex_Tools/Voxy_Workflow_Tooling/tasks/server-streaming-efficiency-20261007/final-artifact-recheck.json)
and [matched baseline candidate](../../Codex_Tools/Voxy_Workflow_Tooling/tasks/server-streaming-efficiency-20261007/precise-baseline-cubic-candidate.json).

The three named companion fallback probes in run
`59831d70-ff1e-4c48-93f8-cbe952e2707f` reached terminal restoration journals:

| Probe | Native rejection at source generation | Later exact-owner validation | Restoration |
|---|---:|---:|---|
| Missing | 411 | 412 | Preserved native publication |
| Corrupt | 416 | 417 | Preserved native publication |
| Owner mismatch | 421 | 422 | Preserved native publication |

Each saved native event identifies PID 1209671, `minecraft:overworld`, region
`(-3,-2)`, the same world identity, its exact rejection reason, and later
`integrity_and_owner_validated=true`. This provides region-attributed native
rejection and revalidation rather than attributing a global counter to the
injected file. Native checksum validation precedes the owner comparison; the
owner-mismatch input was an intact different-region companion. The source owner
advanced during each probe. Conditional restoration therefore preserved the
new native publication instead of overwriting it with the stale original. Saved
evidence: [missing](../../Codex_Tools/Voxy_Workflow_Tooling/tasks/server-streaming-efficiency-20261007/runs/59831d70-ff1e-4c48-93f8-cbe952e2707f/companion-probes/missing/live-result.json),
[corrupt](../../Codex_Tools/Voxy_Workflow_Tooling/tasks/server-streaming-efficiency-20261007/runs/59831d70-ff1e-4c48-93f8-cbe952e2707f/companion-probes/corrupt/live-result.json),
[owner mismatch](../../Codex_Tools/Voxy_Workflow_Tooling/tasks/server-streaming-efficiency-20261007/runs/59831d70-ff1e-4c48-93f8-cbe952e2707f/companion-probes/owner_mismatch/live-result.json).

All three real-PC checkpoints completed with `failure=NONE` and 80,585 active/
uploaded sections. However, all three have `networkBytes=0`, 80,368 cache reads,
the same upload totals and no downloading; sampled server record-write bytes did
not advance either. They show that the existing cached terrain remained available
during the probes. New network delivery or rendered replacement for the injected
target remains unverified; the separate streaming cohorts establish general
acquisition. Missing coverage remained 25,446.

## 9. Lifecycle, retained evidence and remaining limitations

### Excluded attempts and Main process boundary

Main was externally restarted from PID 2703517/start 52797315 to PID
1282851/start 61190536. The source-CUBIC pair `fa9`/`3e9` and earlier BBR `598`
used the original epoch. Precise CUBIC `9c315` and BBR `98ab` used the same newer
Main epoch. Main originals remained read-only; the external restart is an
observed environmental change, not a task-owned restart. Cross-epoch observations
remain separate from the authoritative matched pair.

| Excluded attempt | Observed interruption | Saved terminal scope |
|---|---|---|
| `5a40ee76-feaa-4b2e-af94-b4a910ce6574` | Main identity changed externally. | Excluded; owned cleanup and artifact restoration saved. No passing pressure result. |
| `00e8f56b-7177-4ab7-8ef1-586eeb314080` | ENOSPC before the 30-client clean-control launch. | CLOSED, ABORTED_DISK_FULL, `pass_claim=false`. |
| `c45c5064-d813-4d82-b2c0-df2390fc129c` | Disk pressure interrupted a partial control before plateau end. | CLOSED, ABORTED_DISK_PRESSURE_BEFORE_PLATEAU_END, `pass_claim=false`. |
| `ed1fffd0-ac13-4d9b-b42a-4462708df3de` | ENOSPC during the clean-BBR plateau. | CLOSED, ABORTED_DISK_FULL_DURING_PLATEAU, `pass_claim=false`; excluded from comparison. |

The [5a40 interruption proof](../../Codex_Tools/Voxy_Workflow_Tooling/tasks/server-streaming-efficiency-20261007/runs/5a40ee76-feaa-4b2e-af94-b4a910ce6574/interrupted-restoration-proof.json)
and [loaded artifact restoration](../../Codex_Tools/Voxy_Workflow_Tooling/tasks/server-streaming-efficiency-20261007/runs/5a40ee76-feaa-4b2e-af94-b4a910ce6574/interruption-artifact-restoration.json)
separate run cleanup from the restored BBR 273 identity. The
[00e8 closed journal](../../Codex_Tools/Voxy_Workflow_Tooling/tasks/server-streaming-efficiency-20261007/runs/00e8f56b-7177-4ab7-8ef1-586eeb314080/closed-root-driver.json)
and [c45 closed journal](../../Codex_Tools/Voxy_Workflow_Tooling/tasks/server-streaming-efficiency-20261007/runs/c45c5064-d813-4d82-b2c0-df2390fc129c/closed-root-driver.json)
preserve the failed cohort statuses; the [ed1 closed journal](../../Codex_Tools/Voxy_Workflow_Tooling/tasks/server-streaming-efficiency-20261007/runs/ed1fffd0-ac13-4d9b-b42a-4462708df3de/closed-root-driver.json)
records the later failed BBR control. Root completed its exact Main/PC/SSH/cap
closure, retained the BBR candidate and cleared unknown operations. The successful
replacement is `d30fc95b-a564-4c55-9b36-1152520227ce`, included in section 7. ENOSPC prevented terminal observer/guard
journals in `00e8`: [observer exception](../../Codex_Tools/Voxy_Workflow_Tooling/tasks/server-streaming-efficiency-20261007/runs/00e8f56b-7177-4ab7-8ef1-586eeb314080/disk-interrupted-continuous-observer.json)
and [guard exception](../../Codex_Tools/Voxy_Workflow_Tooling/tasks/server-streaming-efficiency-20261007/runs/00e8f56b-7177-4ab7-8ef1-586eeb314080/disk-interrupted-guard.json)
record saved RUNNING state, original processes absent and unavailable terminal
exit receipts. No invented EXITED or PASS result replaces those missing receipts.
Disk pressure is an environment limitation, not evidence that either controller
passed or failed the intended clean-path comparison.

### Retained-PC cost outside this server scope

The retained PC debug 268 JAR is
`74dc0d71781c677bca2256a04d3913b6bfac87adf67d1b8220cb7d81cac38eeb`.
A saved 19.498514-second window contained 1,621 owner turns,
18.671875 owner CPU-seconds and 18.171875 CPU-seconds in refresh eligibility:
**97.3222% of that owner's CPU**. Terrain workers and lanes were otherwise idle.
The [refresh-cost proof](../../Codex_Tools/Voxy_Workflow_Tooling/tasks/server-streaming-efficiency-20261007/pc-existing-refresh-cost-evidence.json) records exact
endpoints, counter arithmetic and byte identity for all 78 relevant client/debug
class entries between 268 and 273. This is a pre-existing retained-client finding;
these server source changes did not introduce that code. The grouped eligibility
loops perform per-turn O(U+M+V) traversal, but their individual leaf costs were not
isolated. No client fix, measured FPS improvement or real-PC allocation/frame-time
improvement is claimed from this server task.

The approximately 3.99 GB warm PC disk cache can explain zero cold terrain traffic
at the probe views. Cache-size/estimate equality does not prove whole-world
completeness, and missing-demand counts can include unavailable or unsaved source
sections. Native companion rejection/revalidation/restoration and continuing
availability of cached terrain were observed; new network delivery or rendered
replacement for the injected target was not demonstrated.

### Retained evidence and deferred coverage

Five completed-run manifests (`fa9`, `3e9`, `598`, `9c315`, `98ab`) record lossless
gzip archiving of ten reproducible derived CSVs each: 50 files, 1,033,243,523
original bytes and 118,237,746 archive bytes. Every record contains matching
source/roundtrip SHA-256 values and `roundtrip_identical=true`. Raw logs, receipts
and selected JSON were retained. This compacts derived representations without
turning missing terminal observations into recorded data. The
[baseline archive](../../Codex_Tools/Voxy_Workflow_Tooling/tasks/server-streaming-efficiency-20261007/runs/fa9bf6ed-84b3-47ad-a7d9-0780c066e930/derived-csv-archive-manifest.json),
[source-optimized archive](../../Codex_Tools/Voxy_Workflow_Tooling/tasks/server-streaming-efficiency-20261007/runs/3e9c029c-395f-478d-9130-3ac7975463c3/derived-csv-archive-manifest.json),
[earlier BBR archive](../../Codex_Tools/Voxy_Workflow_Tooling/tasks/server-streaming-efficiency-20261007/runs/59831d70-ff1e-4c48-93f8-cbe952e2707f/derived-csv-archive-manifest.json),
[precise CUBIC archive](../../Codex_Tools/Voxy_Workflow_Tooling/tasks/server-streaming-efficiency-20261007/runs/9c31532c-ee15-41cb-9561-fe4fa0de543d/derived-csv-archive-manifest.json)
and [matched BBR archive](../../Codex_Tools/Voxy_Workflow_Tooling/tasks/server-streaming-efficiency-20261007/runs/98ab23e4-2176-4e18-8cf4-6e25d6a655b8/derived-csv-archive-manifest.json)
retain reconstruction paths and exact hashes. Use the archive/manifests when the
plain `.csv` no longer exists.

CLOSED raw `resources.jsonl` streams are additionally archived losslessly as
`resources.jsonl.gz`, with raw-resource roundtrip manifests. These are **raw
archives**, separate from the derived CSVs; the original evidence bytes remain
available after decompression. The twelve recorded CLOSED-stream manifests total
2,336,980,434 original bytes and 384,788,952 archived bytes: **1,952,191,482 bytes
reclaimed by raw compression**. Broader host free-space recovery is not entirely
attributable to this task; owned fake-cache/intermediate cleanup is recorded
separately. The [baseline-control raw manifest](../../Codex_Tools/Voxy_Workflow_Tooling/tasks/server-streaming-efficiency-20261007/runs/19728595-2985-4342-a242-df8f34b47b52/raw-resource-archive-manifest.json)
records 200,903,947 original bytes, 32,660,826 archived bytes and identical source/
roundtrip SHA-256 `0fca7b6a5a9512398de3b6c3b55a0f44dbe4a12adc7ae8f2202ab89c710d1e58`.
Further CLOSED-run raw compaction follows the same owned manifest procedure;
do not describe it as raw-log deletion or link an absent plain file.
Reconstructible task-owned build/cache cleanup is
separate from preservation of original worlds, authoritative terrain, catalogs,
PC cache/settings, updater and SSH routes.

External `.mcc` consumption/change/final verification, individually catalogued
block/light/biome/status/absence transitions, deliberate opening-owner or
durability/crash faults, full cold native construction, controlled 300-block/s
freshness, shared-bottleneck coexistence, universal rendering and long-term
production stability remain unverified. The observed digest oracle, safe source
retries, viewed screenshots and finite capped cohorts establish their recorded
scopes. The final valid clean-control and independent lifecycle evidence are linked below.
No unit or integration test target was executed.

## 10. Final acceptance and retained state

BBR is retained for its observed 3.736× impaired useful goodput and 71.44% lower
request-weighted mean. The clean control shows small less favorable observations:
0.526% lower goodput, 1.081% higher request-weighted latency, sampled mean RTT
57.165→70.925 ms and mean anonymous RSS 138.364→149.829 MB. Native CPU was
0.11636→0.12733 cores. These consecutive cases do not isolate causation from live
saves, page-cache ownership and host pressure; no exact no-regression claim is made.
The clean CUBIC cgroup peak was 983.855 MB, close to its unchanged 999.997 MB cap;
final BBR lifetime peak was 846.758 MB. All three clean cohorts recorded zero
max/OOM/OOM-kill increments. More flight/window state or different cache ownership
can cost memory; no new resource limit or weakened durability was introduced.

The remaining multi-second operator gaps and aged public gauges limit brief-event
attribution and exact tails. Independent continuous process/resource streams,
exact final byte/packet/completion totals, strict calibrated directional accounting,
small measured relay delivery lateness and successful full plateaus support the
aggregate source/delivery comparisons. These gaps cannot certify every transient
stall, instantaneous flight state, exact retransmission bytes or a never-over-1-GB
memory guarantee. The measured large impaired gain is accepted for this selected
path; perfect production readiness and every network/environment are unverified.

Final run `d30fc95b-a564-4c55-9b36-1152520227ce` is CLOSED at 17:43:34 UTC.
The [independent final lifecycle proof](../../Codex_Tools/Voxy_Workflow_Tooling/tasks/server-streaming-efficiency-20261007/runs/d30fc95b-a564-4c55-9b36-1152520227ce/final-lifecycle-proof.json)
confirms the exact debug BBR JAR/native in section 2 remain loaded, Main PID
1282851/start 61190536 is unchanged, both GIORKOSPC SSH routes return the original
Java PID 22328 and helpers 24924/21732, and PC artifact/cache marker/settings are
preserved. No QUIC hold remains. Typed END and finish were confirmed. Owned fake
processes, relays, routes, sessions, subscriptions, sockets, source queues and
exclusive/unknown source ownership are absent; native diagnostic reset was
confirmed. JVM arguments and native/swap enforcement retain their original hashes.
The updater/client was preserved; no client source changed in this server task.

The final signed Minecraft screenshot was captured and actually viewed:
[frame](../../Codex_Tools/Voxy_Workflow_Tooling/runs/d30fc95b-a564-4c55-9b36-1152520227ce/voxy-test-d30fc95b-a564-4c55-9b36-1152520227ce-1.png),
[signed result](../../Codex_Tools/Voxy_Workflow_Tooling/tasks/server-streaming-efficiency-20261007/runs/d30fc95b-a564-4c55-9b36-1152520227ce/pc-frame-screenshot-result.json)
and [root viewing scope](../../Codex_Tools/Voxy_Workflow_Tooling/tasks/server-streaming-efficiency-20261007/runs/d30fc95b-a564-4c55-9b36-1152520227ce/pc-frame-root-review.json).
It contains textured forests, snow, rivers and structures at the retained pose.
No obvious large missing areas or checkerboard terrain textures were seen in that
frame. This is an observed scene, not universal rendering or frame-time proof.

[Final input recheck](../../Codex_Tools/Voxy_Workflow_Tooling/tasks/server-streaming-efficiency-20261007/final-close-inputs.json) confirms all 326 build-input
hashes still match the frozen build; [final artifacts](../../Codex_Tools/Voxy_Workflow_Tooling/tasks/server-streaming-efficiency-20261007/final-close-artifacts.json)
confirm exact native embedding and normal/debug separation. The selected plan
SHA-256 remains `dd4f5f4439572c5df05e2ef6a8b92561ee6670bfa20ca192ca13ee21198dce0d`.
The [requirement ledger](../../Codex_Tools/Voxy_Workflow_Tooling/state/reports/server-streaming-efficiency-final.json)
records passed scopes and unverified cases with zero missing evidence receipts.
Saved logs were imported once into immutable MCP evidence bundles: precise paired
bundle `b0c01db6c72249159e5866c7ca45fa7b` and final control bundle
`f095f17a364441d38859b982edcd7f0e` (2,516 final-control indexed events, complete).

Finished fake-client cache bodies were reclaimed only after CLOSED, with
[exact file/space accounting](../../Codex_Tools/Voxy_Workflow_Tooling/tasks/server-streaming-efficiency-20261007/runs/d30fc95b-a564-4c55-9b36-1152520227ce/finished-fake-cache-reclamation-precise-optimized-bbr-control2.json).
The temporary generated-artifact hard links used during ENOSPC recovery were
[restored to independent files](../../Codex_Tools/Voxy_Workflow_Tooling/tasks/server-streaming-efficiency-20261007/generated-artifact-independent-storage-restored.json)
with every SHA-256 preserved. Raw journals and derivative archives retain their
recorded bytes and provenance; no source-world or real-PC cache was removed.
The local commit scope is the eleven changed source/build/harness files and this
results document. No GitHub publication or push is authorized or performed by this task.
