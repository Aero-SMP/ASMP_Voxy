# Server bottleneck timings and 100 Rust clients: implementation and live-test plan

Status: plan only, written 2026-10-06. No instrumentation, builds, deployment,
route registration, impairment or live pressure test was performed when writing
this document.

Repository: `/home/aerosmp/Desktop/ASMP_Voxy_Cache_First_Updates`.
Branch: `feature/cache-first-background-updates`.
Reviewed baseline: `070fb1dc26a8cbe9d0c83742228e122a092916a7`; checkout clean at
the start of review. Recheck source and concurrent work before implementation.

## 1. Required outcome and scope

First add and validate server timings that distinguish processing from waiting.
Then run **100 simultaneous independent Rust clients against the live Testing
Voxy QUIC server**, using its current authentication and terrain protocol. They
do not connect to Minecraft, render terrain or create Minecraft players.

| Property | Required profile |
| --- | --- |
| Concurrent fake clients | 100, with distinct authenticated routes and QUIC connections |
| Ping | 300 ms unloaded round-trip target, approximately 150 ms each way |
| Loss | Seeded independent 10% datagram loss in each direction |
| Download ceiling | 1,000,000 bits/s per client, actual IP/UDP traffic including routing envelope, QUIC overhead and retransmissions |
| Upload | Also emulate a separate 1,000,000 bits/s ceiling; full duplex, not a combined upload/download allowance |
| Source terrain | Existing saved terrain inside the current eligible world border |
| Real PC | Retain its own connection, cache, settings and network profile |

Serialization, queueing, ACK delay and loss recovery can raise observed RTT above
300 ms. Record both configured propagation delay and measured RTT. The observed
loss fraction is statistical; 10% in each direction does not mean exactly 10% of
round trips fail. These interpretations are explicit plan assumptions.

This request re-enables the 100-client verification previously suspended in
`tools/LIVE_PRESSURE.md`. It does not re-enable artificial block-change pressure.
No world generation, source-world edits, renderer changes, cache resets, protocol
changes, new integration tests, arbitrary service budgets or production tuning.
Keep the existing 2 s terrain-refresh cadence and existing transport settings.

Main, ASMP_Voxy and ASMP_Voxy_Restart remain untouched. Work only on the feature
branch and scoped Testing deployment. Preserve both PC backup SSH helpers and
their routes. Do not publish the previously rejected GitHub plan commit or use
this task to bypass that rejection.

## 2. Source findings that the implementation must address

| Finding at the reviewed baseline | Consequence |
| --- | --- |
| `rust-server/src/live_pressure.rs:391-396` explicitly supports one registered route; `489-525` selects only five sections and can exit from a warm cache before connecting. | Extend the Rust runner into persistent independent clients with sustained useful demand. Do not treat 100 invocations of its current workload as acceptance. |
| `rust-server/src/server.rs:1307-1314` replaces an authenticated connection on the same route. | Generate 100 distinct test-owned tokens; never copy the PC token. |
| `server/.../RustBackend.java:390-403` and native `server.rs:359-370` already own registration/revocation. | Register through the Java bridge, without tokenless admission or another writer to native stdin. |
| `server.rs::send_claim`, starting at 1513, awaits preparation, catalogue announcement, body reading, admission and stream writing. | Measure these stages and their waits separately. |
| `regional/runtime.rs::region` can reuse an active regional snapshot; a miss opens and validates its directory. `PreparedSection::body` reads compressed bytes and checks their CRC. | Count cache hits/misses; do not describe every request as rebuilding or recompressing terrain. |
| Regional construction/publication is shared work. Existing logs expose catalogue build duration and some counters, but not the full request path. | Attribute builds once per region/generation, alongside each consumer's wait. |
| Normal and debug server JARs currently embed the same native release binary (`build.gradle:223-229`, `server/build.gradle:33-43`). | Separate normal/debug native build inputs before claiming compile-time debug-only instrumentation. |
| The old Python driver targets retired connections/lane layouts; old pressure coordinates include very distant regions. | Reuse neither its protocol nor its location list. Derive fresh positions from saved inventory. |

## 3. Implement server diagnostics before pressure

Use one small Rust diagnostics module, compiled through an explicit debug Cargo
feature. Add probes at existing ownership transitions rather than adding queues,
executors or a parallel scheduler. The feature is absent from the normal native
build. Extend the existing debug Java harness only for test-route lifecycle.

### 3.1 Timed stages and owners

All elapsed timings use monotonic timestamps and retain nanosecond totals. Record
count, sum, maximum, useful bytes/work units, completion status, failures and
cancellations. Publish histogram bin edges; percentile results are bounded
estimates, not exact nanosecond percentiles.

| Stage | Probe location and required separation |
| --- | --- |
| Bootstrap/control | `server.rs::serve_established` and `regional/wire.rs`: TLS/bootstrap phases, control read wait, frame decoding and desire application separately. |
| Request scheduling | `Wants::enqueue`, `Session::claim`: first eligible enqueue to claim, queue class, cancellation and source-NotReady wait. Preserve the original wait start during reprioritization. |
| Blocking preparation | First `spawn_blocking` in `send_claim`: submission to closure start, synchronous preparation, closure completion to async resumption. |
| Regional lookup | `runtime.rs::region`, `store.rs::RegionFile::open`: snapshot hit/miss, directory read/CRC validation and index construction. |
| Catalogue | `service.rs::CatalogCache::get`, `Session::announce_catalogue`: cache/lock wait, actual encoding/compression, metadata writer lock wait and stream-write wait. |
| Compressed body | Second `spawn_blocking` in `send_claim`, `PreparedSection::body`, `RegionFile::read_compressed_ordinal`: executor wait, read and CRC, async resumption. |
| Record sending | `Session::begin_record`, `wire::write_record_body`: priority/admission wait and Quinn body-write backpressure; distinguish DATA, REUSE, EMPTY, ABSENT, NOT_READY and cancelled work. |
| Actual socket/pacer | `pacer.rs::PacedSocket` and `PacedPoller`: intentional rate delay, underlying socket WouldBlock and resumption lateness, actual route bytes/datagrams. |
| Shared publication | `service.rs::publication_loop`, `refresh_all`, `runtime.rs::refresh_coordinate`: worker scheduling, source inspection, dirty-column coalescing, unchanged/full/incremental refresh and publication fanout. |
| Build components | `anvil.rs::read_chunk`, `builder.rs::rebuild_region`/`rebuild_changed_column`, `store.rs::RegionFileBuilder`: source read/decompression/NBT parsing, LOD construction, record encoding/compression, reused-byte copy/validation, terrain/index/source-table writes, fsync/rename. Aggregate fine probes in worker scratch instead of a global update for every voxel. |

Measure thread CPU time only around synchronous work on the same executing
thread, including individual Rayon closures when relevant. An outer worker's
CPU does not include CPU on its child workers. Async tasks may migrate between
threads; their elapsed wait is not CPU time. Use process CPU deltas as a separate
cross-check. Linux distinguishes thread and process CPU clocks in its
[clock documentation](https://man7.org/linux/man-pages/man3/clock_gettime.3.html).

### 3.2 Reporting without turning logging into the bottleneck

- Emit aggregated snapshots approximately once per second. Include run ID,
  loaded native identity, connection/session epoch, stage counters, queue sizes,
  active phases and their current ages. A permanently stalled operation must be
  visible even when no completion histogram changes.
- Use fixed stage/histogram storage and existing interest/lane ownership. Avoid
  per-request maps, per-packet printing and allocations in timing updates. Keep
  detailed histograms global; per-session counts, totals and active ages establish
  fairness without duplicating the entire histogram set 100 times.
- Snapshot each connection's Quinn RTT, congestion window, loss/congestion and
  UDP counters. Confirm field definitions against pinned Quinn `0.11.11` and
  quinn-proto `0.11.17` locally. The official
  [ConnectionStats](https://docs.rs/quinn/0.11.9/quinn/struct.ConnectionStats.html)
  and [PathStats](https://docs.rs/quinn/0.11.9/quinn/struct.PathStats.html)
  references describe these fields, but the local pinned sources are authoritative
  for this build.
- Separate useful compressed payload bytes, Quinn UDP bytes and route IP-accounted
  bytes. Record the relay's corresponding counters independently.
- Existing `read_control_traced` emits verbose messages for subsequent control
  reads, too. Keep those disabled during measurement while retaining aggregated
  diagnostics; do not assume disabling bootstrap-labelled lines removes only
  startup work.
- State inclusive/nested measurements explicitly. Do not add overlapping pacer
  and stream-write delays, sum parallel worker times as elapsed latency, or charge
  a shared build separately to every waiting client. Snapshot deltas stay within
  one epoch, and pending/cancelled work is reported alongside completed work.
- Correlate request/client timings with existing ticket, dimension, key and
  generation identities. Client-local request-to-complete timings require no
  subtraction between unsynchronized client/server clocks.
- A completed Quinn write means bytes were accepted by the transport, not
  received or validated by the client. Report that boundary beside the client's
  own validated-completion latency; neither proves the other.

### 3.3 Packaging and instrumentation gate

Give normal and debug native builds separate Cargo output directories and Gradle
tasks. Exclude the normal embedded native member when constructing the debug
server JAR and insert exactly the debug member. Record source, feature/build
receipts and standalone/embedded SHA-256 identities. Verify diagnostic/test-route
classes and diagnostic markers are absent from normal artifacts and present in
debug artifacts. A runtime environment toggle alone is insufficient.

Build the affected normal/debug artifacts offline with the pinned dependencies.
Perform focused arithmetic/ownership checks for timer termination, cancellation,
histogram bounds, rate/loss accounting and cleanup; do not add or run integration
tests. Validate actual stage emission and timing-on/off overhead on equivalent
one-client live traffic before admitting 100 clients. Retain equal verbose-log
settings, workload and source/cache conditions; an inconclusive comparison is
labelled as such, not reported as zero overhead. Reject missing stages, broken
accounting, hot-path allocation/locking regressions or instrumentation that
materially changes the measured workload. Do not tune production scheduling here.

## 4. Extend the Rust live runner

Use the existing `live_pressure` binary, one shared async runtime and **100
independent client states**, each with its own token, Quinn endpoint/connection,
protocol state and run-owned cache directory. Separate OS processes are not
needed to exercise 100 server connections. Reuse the current codec, TLS certificate
validation, routing envelope and section-integrity checks. Do not resurrect the
old Python pressure protocol or add a Minecraft dependency to the clients.

### 4.1 Authenticated test-route lifecycle

Add debug-only, privileged run start/status/stop operations to the existing
server test harness. Bind the route registry to one run ID and external deadline;
reject conflicting live ownership. Register 100 random nonzero tokens through
`RustBackend.register(token, 1000)` and await each actual native-ready response.
Use the same total-cap settings in each client's current-protocol bootstrap.

Keep secrets in a run-owned permission-0600 file, outside source control. Public
receipts contain token fingerprints, connection IDs and counts, not full tokens.
Do not blindly redact the native-to-Java readiness record before its existing
parser consumes it; redact only operator-facing copies/logs.

Revoke only this run's tokens on stop, deadline expiry, server shutdown or runner
failure. Java must also expire the run if the external operator disappears; a
dead operator must not leave registered routes replaying after native restart.
Existing Java revocation is fire-and-forget and native revoke emits no removal
acknowledgment. Add a debug-only native status/removal acknowledgment through the
same bridge owner, correlated with run/request ID and native process epoch. Only
observed native removal of routes, sessions and subscriptions proves cleanup;
Java accepting the stop command does not. The PC's route remains independently
owned and untouched. No new public network admission/control endpoint is needed.

### 4.2 Sustained terrain workload

Derive 100 distinct populated anchors from the current manifest/world identities,
border and saved Anvil location tables. Avoid unsupported/out-of-border terrain
and empty-only points. Save anchors and workload seed in the run manifest. Use
the active overworld first; no Minecraft teleport is needed for virtual clients.

Start every virtual client's own cache empty. At each anchor request nearest
coarse coverage first, then visible finer detail and expanding saved-terrain
rings, using the existing purpose/rank, lane, catalogue and generation semantics.
Replenish completed desires through the existing request-window behavior rather
than a fabricated sections-per-second throttle. Do not keep an unbounded pending
world list or rescan/sort the whole world on every completion.

Keep all 100 connections alive through deterministic changing-view/movement and
warm-revisit phases. Keep actual missing demand available while cached sections
use local data and correct HAVE/REUSE behavior. Warm cache must not bypass QUIC
connection establishment or quietly turn the pressure plateau into 100 idle
sessions. Distinct starting areas exercise spatially distributed work; deliberate
later overlap can expose shared catalogue/build reuse and must be labelled.

Validate world association, ticket/key/generation, catalogue fingerprint, CRC,
payload hash and decoded structure before counting useful completion. Record
first coarse coverage, requested-detail completion, bytes, cache hits, NOT_READY,
failures and longest no-progress intervals for each client.

Keep disk/decode work off async packet reception using existing Rust facilities;
reuse scratch and release bodies after validation/persistence. Do not retain the
entire downloaded payload set in RAM. Record generator CPU/RSS, decode/persist
times, pending work and event-loop lateness separately. A generator-limited run
does not prove a server bottleneck or server capacity.

## 5. Impair actual datagrams, independently for every client

Prefer a run-owned local Rust UDP relay for each client, managed by the Rust
runner. Each relay forwards opaque routed QUIC datagrams to the verified Testing
endpoint. TLS, handshake, control, data, ACKs, retransmissions and close all traverse
the impairment. No shared host interface, qdisc, firewall, sysctl, PC route or SSH
connection is changed.

For each direction, maintain an independent monotonic serialization clock and
FIFO. Charge a datagram once for its already-present routing envelope plus
IP/UDP headers: observed wrapped UDP payload length + 28 bytes for IPv4 or + 48
for IPv6. Do not add the 17-byte envelope a second time.

Reserve transmission time `8 * accounted_bytes / 1_000_000` from
`max(arrival_time, previous_serialization_end)`, with no idle burst credit. Draw
seeded loss independently after charging capacity; lost packets still consume
the link. Deliver survivors after serialization and the one-way propagation
delay. With a fixed delay, deadlines remain ordered, allowing O(1) FIFO append
and removal instead of sorting queued datagrams. Count only the impersonated
remote link once, not the relay's additional localhost socket hop.

Enforce the ceiling at actual link-service/send times as well as scheduled
deadlines. An overdue relay must rebase remaining serialization times rather than
drain late packets in a catch-up burst. Lost service decisions also advance this
clock. Record this extra lateness instead of disguising it as configured latency.
Use completed service decisions, not just scheduled reservations, to validate the
rate and loss counters. For any measured window of duration T seconds require
accounted serviced bytes <= 125,000 * T + one maximum accounted datagram;
publish the packet-boundary allowance and timestamp precision.

Calibrate the unshaped baseline first and adjust symmetric added delay toward
300 ms unloaded RTT; do not add 300 ms in each direction. Publish actual measured
delay distributions, packet serialization contribution and scheduling lateness.
The production server route pacer remains at 1000 kbps. Equal server and relay
ceilings do not imply a halved rate, but can add queue/serialization delay;
measure the two independently and detect unintended underutilization.

Record attempted, intentionally dropped, delivered and pending packets/bytes by
client and direction. Capture unexpected socket drops/truncation and errors, and
actual send timestamps. Verify seeded decisions exactly and report observed loss
with sample counts/statistical uncertainty. Reconcile final packet/byte totals,
including packets abandoned during cleanup. Check the rate ceiling per client
over measured windows, allowing only the disclosed indivisible-datagram boundary
and measured clock precision. An aggregate 100 Mbps check is insufficient.

If the relay cannot preserve delay/rate/loss fidelity under load, mark the run
inconclusive. Do not quietly substitute application sleeps, drop decoded sections
or remove the impairment. A dedicated isolated network namespace is a possible
future alternative, not part of this implementation.

## 6. Deployment, finite live run and resource observations

Use the pinned Voxy workflow toolkit/MCP. Register this exact plan/hash externally;
store artifacts, receipts, logs, caches and secrets under
`/home/aerosmp/Desktop/Codex_Tools/Voxy_Workflow_Tooling/tasks/` with a unique task
and run ID. Never use `/tmp` or edit the sealed toolkit release. Existing PC-only
adapters do not establish pressure-run support: declare and verify the new finite
runner's operations and cooperative lease before executing them. Preserve unknown
operation outcomes and reconcile them rather than replaying uncertain jobs.

Before deployment, refresh loaded controller/native/client hashes, PIDs/start
identities, exact native listener/cwd, certificate/world identity, updater state,
PC settings/cache identity, both backup helpers and ownership of existing runs.
Old pressure/preflight JSONs are historical, not current readiness evidence.
Prepare hash-addressed rollback for the scoped server artifacts. Deploy only the
debug Testing controller/native, with a scoped Testing restart if required; verify
the actually running native matches the embedded candidate. Intentionally retain
the compatible PC client unless implementation demonstrates a required debug
client change; name and prove the resulting version pairing.

Keep the native external ceiling **999,997,440 bytes, swap disabled**, and Java
`-Xms1G -Xmx4G`; verify current enforcement before starting. Do not weaken them or
introduce new runtime memory/CPU/send budgets. Observe native cgroup current/peak,
events/OOM deltas, RSS, CPU, file I/O, PSI, Java heap/RSS/GC, and host available
memory throughout. The Rust generator/relays run outside the native server cgroup;
report their resource use and same-host contention, too. The 100 download caps
allow at most 100 Mbps combined accounted traffic; useful goodput will be lower
because of loss and overhead. Real-PC traffic is separate.

Use one externally enforced **600 s live-test clock with a 180 s restoration
reserve**. This is an experiment duration, not a service work budget. All live
calibration, timing overhead observations, registration, handshakes, pressure,
stream drain and restoration share this non-resettable clock. Build/preparation
happens beforehand. A proposed schedule is:

| Relative time | Operation |
| --- | --- |
| 0-60 s | One-client profile/timing checks and PC baseline; fail early if diagnostics or impairment are invalid. |
| 60-150 s | Register/ramp to 100 distinct authenticated sessions and prove server/client agreement. |
| 150-390 s | Hold all 100: cold coverage 90 s, finer detail/changing anchors 90 s, warm revisit with continuing missing demand 60 s. Capture PC telemetry/screenshots concurrently through existing authorized controls. |
| 390-420 s | Freeze new work, capture final counters, begin close/revocation. |
| 420-600 s | Restore and verify; this period permits cleanup only. |

Earlier phases consuming extra time do not shift the reserve or create another
clock. Require the full 240 s plateau; insufficient time/session loss means
incomplete verification, not a shorter passing run. Any fault/resource-exhaustion
signal stops new pressure and triggers cleanup. Stop the test-owned load first;
never kill unrelated processes or raise the existing hard ceiling to finish.

Keep both backup SSH connections alive before, during and after any scoped Testing
restart and load. Use the independent operator/deadline guard for Rust client and
relay shutdown, including `Endpoint::wait_idle`; the current runner's unbounded
final drain must not survive the external deadline.

Cleanup revokes test routes, closes only test sockets/processes, stops timed
observation and restores any changed PC pose/settings. Preserve real cache data
and saved worlds. Verify no test route/session/subscription remains, native/Java
identities and limits, PC recovery, both backup helpers and unaffected Main
process identity. Requested shared builds can survive their last subscription
(`runtime.rs:698-700`): inspect run-attributed queued/in-flight build/publication
work as well. Drain run-exclusive work within this deadline or retain and report
its outstanding state as `cleanup_pending`. Distinguish work still legitimately
shared with the PC; do not cancel unrelated work to claim an empty queue.
On a candidate fault use the prepared rollback; successful
instrumented debug deployment may remain loaded and must be reported explicitly.
Close the toolkit lease only after independently verified restoration; otherwise
retain `cleanup_pending` and report the unresolved operation.

## 7. Acceptance and bottleneck report

The run is valid only with independent server/client proof of **100 concurrently
authenticated fake sessions for the entire 240 s plateau**, each following the
verified profile, requesting useful missing terrain, receiving valid coarse
terrain and continuing useful completions. Report counts/minimum concurrency,
per-client useful throughput/latency distributions, the slowest clients, outstanding
ages and any disconnects. Connecting 100 idle endpoints is not acceptance.

Require zero integrity/authentication/scope failures, no new OOM kills, unchanged
hard limits, verified cleanup, preserved PC cache/settings and backup routes.
Existing cgroup event counts are baselined; only new events are attributed to the
run. Lack of a full plateau, insufficient demand, missing measurements or
generator/relay saturation is inconclusive. A correctness fault, OOM or repeatable
client starvation under eligible demand is a failure, not a result to hide by
reducing client count.

Do not invent a latency/FPS/throughput target after seeing results. This first run
establishes measured capacity under the requested profile; it cannot prove every
client reaches 1 Mbps useful throughput, instant fine detail, an arbitrary speedup
or perfect renderer correctness. The Rust clients prove transport/integrity/cache
delivery; PC screenshots and telemetry provide separate real rendering evidence.

Write results with these tables and link raw hashed receipts:

1. Intended versus loaded artifacts and test scope; setup/plateau/cleanup outcome.
2. Per-client impairment, authenticated concurrency, useful bytes/coverage/detail
   latency, no-progress ages and retransmission/overhead cost.
3. Stage counts, mean/max and percentile bounds, synchronous CPU, live queued
   ages and bytes/work units. Separate shared build work from per-request work.
4. Native, Java, generator and host resource changes; PC before/during/after.
5. Bottlenecks ranked by demonstrated critical-path delay or CPU work, with
   supporting source locations, competing explanations and uncertainty.

For example, fast prepare/read with long writes plus congestion/loss and relay
serialization indicates network delivery pressure; executor queue growth with
high measured build CPU indicates server processing pressure. Slow body reads
with low CPU and I/O stalls indicate storage waiting. These are interpretation
rules, not predicted findings. Overlapping stages need not have one dominant
bottleneck. Propose fixes only after the measured report; do not combine unrelated
optimizations with this instrumentation and verification task.

Implementation deliverables: debug-only diagnostics and native packaging proof;
reused Rust 100-client runner with owned datagram impairment; debug route ownership
and expiry; updated `tools/LIVE_PRESSURE.md`; external workload/run/evidence files;
and a project-audit results document with a truthful PASS/FAIL/inconclusive outcome.
