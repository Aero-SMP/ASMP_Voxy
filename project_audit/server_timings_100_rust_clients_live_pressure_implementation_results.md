# Server timings and 30 Rust clients: completed live results

Updated: 2026-10-07. Branch: `feature/cache-first-background-updates`.
Selected plan: `server_timings_100_rust_clients_live_pressure_implementation_plan.md`,
SHA-256 `c2e2d73c646139aabd36438e82245df61323d78ba4fe487f645e585d410bade3`.
The filenames retain the original 100-client task name; the user's current scope is
30 clients. No 100-client plateau was performed. There is no overall testing-duration
or retry limit. Finite abandonment/cleanup watchdogs protect each owned invocation.

**Both 30-client, 240-second plateaus passed technical progress, integrity and cleanup
gates.** The impaired test used approximately 300 ms unloaded RTT, 10% datagram loss
in each direction and a 1 Mbps actual-traffic cap per direction. The control kept
30 clients and the cap but removed artificial RTT/loss. Results and qualifications
follow; historical failed/preparation attempts remain below.

**Thirty independently authenticated Rust clients maintained the full 240-second
plateau, made useful progress in every workload phase, and exited successfully.**
The real PC rendered terrain before and during pressure. Owned routes, sessions,
subscriptions, route sockets and pending source work were independently observed
removed before the root lease closed. These are technical and lifecycle results,
not blanket production, perfect-rendering or exact-network-profile acceptance.

## Scope and identities

| Item | Recorded value |
| --- | --- |
| Completed run | `962583e5-34b0-4155-b89c-83a10e23f216` |
| Fake clients | 30 distinct tokens, QUIC connections and cold run-owned caches; no Minecraft connections |
| Existing real PC | Debug client 268, retained without replacing its JAR or resetting its cache |
| Testing server | Debug server 270; native process 380333, start ticks 58622623 |
| Java process | Testing PID 379283, start ticks 58621260 |
| Native epoch | `1791363077357410108` |
| Main | Read only; PID 2703517, start ticks 52797315 remained the independently checked baseline identity |
| Saved terrain | Existing saved inventory, 400 regions, radius-5000 world border; no artificial block changes or world generation |
| Workload seed | 17 |
| Measurement window | 240 seconds with all 30 connections present; 256.813 seconds for runner setup, plateau and drain |
| Setup to plateau | 14.001 seconds |
| Full owned-run closure | 926.258 seconds, lease `CLOSED` |
| Abandonment containment | Manifest watchdog 1,800 seconds, with 180 seconds reserved for cleanup; this was not a project-wide testing or attempt limit |

The native SHA-256 was
`51d0d065a20dd0c68dfb236577b134dfae16d6755da16660b0d55789346f0d0f`.
The loaded server-270 JAR SHA-256 was
`89292257ab4b28cb01c72325d2b44a01a0ef74d5fd1680d608b734f071d77f23`.
The retained client-268 JAR SHA-256 was
`74dc0d71781c677bca2256a04d3913b6bfac87adf67d1b8220cb7d81cac38eeb`.
The cold30 runner log SHA-256 was
`99bef9a3b4fc853f839cb8fa5a1a5b017d7ed05ef642ec06db2597f3a39ab3dc`;
its stderr file was empty. These identities and the run manifest are in
[root-run.json](/home/aerosmp/Desktop/Codex_Tools/Voxy_Workflow_Tooling/tasks/server-pressure-20261006/runs/962583e5-34b0-4155-b89c-83a10e23f216/root-run.json)
and [guard.json](/home/aerosmp/Desktop/Codex_Tools/Voxy_Workflow_Tooling/tasks/server-pressure-20261006/runs/962583e5-34b0-4155-b89c-83a10e23f216/cold30/guard.json).

## What passed, and what remains unproven

| Check | Finding |
| --- | --- |
| 30-client technical plateau | Passed: full 240 seconds, minimum concurrent count 30, exit 0, no runner failure |
| Sustained useful demand | Passed: every client completed missing terrain in all three phases; maximum idle-sample count 0 |
| Payload and protocol validation | Passed runner checks; DATA/EMPTY/REUSE completions were validated before counting usefulness |
| Per-client actual traffic ceiling | Passed completed-send and charged-service rolling-window checks; zero violation windows in both directions |
| Seeded loss and packet accounting | Passed runner reconciliation; observed fractions approximately 10% per direction |
| Clean 300 ms-only path under pressure | Not established: unloaded RTT matched the target, but relay scheduling added occasional large delays |
| Server timing instrumentation | Functional emission and epoch/identity gates passed; exact probe CPU overhead remains inconclusive |
| Low generator resource usage | Supported by observed process CPU/RSS; sampling gaps and event-loop jitter remain disclosed |
| Real-PC rendering | Verified at the before/during stationary views with signed results and saved PNGs |
| Real-PC FPS, frame-time percentiles or cold-cache loading | Not measured by this run |
| Both PC SSH backups and settings/cache preservation | Independently verified by root before admission and at closure |
| Native/JVM memory enforcement and no new native OOM | Verified throughout observed receipts and at closure |
| Owned cleanup | Passed: actual native counts/work empty, owned processes absent, diagnostic reporter reset, root lease closed |
| Thirty real Minecraft/render clients | Not tested; the fake clients exercise Voxy transport, validation and persistence |
| Long-duration soak, perfect meshing, every modded block, hole/seam absence | Not established |
| Artificial 300 block changes/s or 100-client pressure | Not performed in this scope |
| Integration tests | None added or run |

The runner's own final `acceptance:"inconclusive"` deliberately leaves physical-PC,
profile-fidelity and external cleanup checks to the owner. Later receipts establish
the lifecycle/PC checks; they do not erase the network-fidelity and rendering limits.

## Calibration and network profile

Fresh one-client baseline, impaired timing-off and impaired timing-on invocations
completed their five-second plateaus before admission. The baseline setup RTT was
22.493 ms. The initially added 300 ms produced 322.153 ms setup RTT, so the pressure
run used 278 ms added RTT, approximately 139 ms each way. Server timing probes were
on for the full cohort; verbose network tracing was quiet.

| Property | Completed pressure measurement |
| --- | --- |
| Actual unloaded setup RTT across 30 clients | 293.663–314.223 ms; mean 302.064 ms |
| Per-client ceiling | 1,000,000 bits/s actual IP-accounted traffic in each direction, independently full duplex |
| Upstream intentional loss | 10,646 / 104,916 serviced datagrams = 10.147% |
| Downstream intentional loss | 9,688 / 97,432 serviced datagrams = 9.943% |
| Actual send-window violations | 0 upstream, 0 downstream |
| Charged-service-window violations | 0 upstream, 0 downstream |
| Socket errors | 0 |
| Maximum extra relay delivery lateness | 609.833 ms upstream; 610.978 ms downstream |
| Weighted mean service lateness | 1.758 ms upstream; 1.812 ms downstream |
| Maximum client progress-pulse lateness | 1,199.170 ms |

The loss applies to datagrams after charging link capacity. The cap includes IP/UDP
headers, the already-present route envelope, QUIC control/ACK traffic and retransmits.
The relay's additional local socket hop is not double-counted. Lost bytes still
consume the simulated link. Packet indivisibility and timestamp precision are part
of the documented rate-check allowance, rather than a claim of fractional-packet
instantaneous rate control.

This demonstrates the configured loss/cap and approximately 300 ms unloaded RTT;
occasional additional scheduling delay means it is not evidence for an otherwise
clean 300 ms path. Client progress-pulse lateness includes waits for validation and
cache-worker completion; it is not global packet-loop lateness. The independent
relay delivery lateness is the relevant packet-scheduling observation. Observed kernel UDP-drop counters were zero while the owned
sockets were visible, but their close/drain tail was unsampled. Do not infer a
whole-run zero-kernel-drop guarantee.

The functional timing-on/off comparison was accepted for admission, with a clear
qualification: differing completed terrain cohorts and concurrent source work make
exact overhead inconclusive. It was not reported as zero overhead. See
[instrumentation-gate.json](/home/aerosmp/Desktop/Codex_Tools/Voxy_Workflow_Tooling/tasks/server-pressure-20261006/runs/962583e5-34b0-4155-b89c-83a10e23f216/instrumentation-gate.json).

## Useful terrain progress and delivery

| Whole-invocation result | Value |
| --- | --- |
| DATA | 21,989 |
| EMPTY | 6,365 |
| REUSE | 4,675 |
| ABSENT / NOT_READY | 0 / 0 |
| Validated compressed payload | 110,642,990 bytes |
| First coarse completion | Mean 3.158 s; range 1.431–6.342 s across clients |
| First detail completion | Mean 8.711 s; range 4.774–14.202 s across clients |
| Per-client mean completed-request latency | Range 2.634–4.804 s; mean of client means 3.660 s |
| Per-client largest observed pending age | Range 7.321–16.123 s |
| Largest sampled no-missing-progress gap | 3.191 s |
| Completion histogram p50 | (2.097152, 4.194304] seconds |
| Completion histogram p95 | (4.194304, 8.388608] seconds |
| Completion histogram p99.5 | (8.388608, 16.777216] seconds |

Histogram quantiles are bin bounds over all 33,029 completed statuses, including
setup/drain and REUSE; they are not exact percentile values, rendered latency,
FPS or per-phase DATA-only latency. First coarse/detail describe first validated
completions, not complete visible-area coverage.

| Phase | Missing completions | Minimum for any client | Mean useful payload rate/client | Mean charged bidirectional rate/client |
| --- | ---: | ---: | ---: | ---: |
| Cold coarse coverage, first 90 s | 9,660 | 204 | 117.579 kbps | 153.447 kbps |
| Detail/movement, next 90 s | 9,969 | 234 | 120.658 kbps | 157.539 kbps |
| Warm revisit plus continuing missing demand, last 60 s | 6,456 | 148 | 117.523 kbps | 164.542 kbps |

These phase rates use only wholly sampled client intervals; phase-boundary
intervals are excluded. They show continued useful demand without approaching the
1 Mbps ceiling. Across the entire invocation, mean charged downstream traffic was
146.116 kbps/client. The sampled phase charged rates sum upstream and downstream; each direction has
its own 1 Mbps cap. The payload/charged-bidirectional-IP ratios were
76.6%, 76.6% and 71.4%; the difference includes protocol traffic and impairment,
and is not an exact retransmission-byte estimate.

The fake caches were fresh. The server's regional snapshots/catalogues were not
declared cold: the selected native window had 41,563 catalogue hits, no catalogue
misses, 39,288 snapshot hits and 2,275 snapshot misses. This is a cold fake-client
cache workload against the actual live server state, not a forced cold-server build.

## Resource usage and measurement completeness

The impaired plateau had **83 host/process resource snapshots and a 67.533-second
largest gap**, overlapping its first and second phases. The final warm phase had only one native/resource observation, 0.577 seconds into
that phase; its remaining 59.423 seconds were unsampled. A second 61.706-second
gap separated that observation from a post-pressure sample with zero connections.
The client event logs still record useful progress throughout all three phases.
CPU endpoint deltas below cover the entire same-PID interval including these gaps,
241.340 seconds; instantaneous gauges and peaks during gaps remain unknown. Client
logs and native timing records cover the pressure workload independently.

The control collected 219 owned-generator resource observations, of which 218
were inside its estimated plateau. Its first approximately 18.6 plateau seconds
preceded resource collection, with a 3.621-second largest later gap. Its process
endpoint interval is 221.748 seconds, not a claim of all-240-second resource coverage.

| Process | Impaired CPU core equivalents | Control CPU core equivalents | Impaired maximum sampled RSS | Control maximum sampled RSS |
| --- | ---: | ---: | ---: | ---: |
| Native Voxy | 0.25885 | 0.35888 | 74.412 MB | 141.099 MB |
| Testing Java | 0.37122 | 0.37827 | 4,893.770 MB | 4,559.143 MB |
| Rust generator | 0.03091 | 0.16717 | 59.331 MB | 155.333 MB |

Process endpoint means include CPU performed during observation gaps, with minor
setup/drain boundary mixing. They require matching PID/start identities at both
endpoints. MB here means decimal bytes / 1,000,000. Core equivalents mean CPU
seconds divided by actual endpoint wall seconds, not a percentage of the entire
32-logical-CPU host. RSS and thread-count maxima are sampled gauges; peaks in gaps
remain unobserved. The generator reached 30/31 sampled threads in the impaired/control
runs; one shared current-thread async runtime served all 30 fake clients.

The old observers had lifetime high-water RSS roughly 114–183 MiB. The continuous
control observer had sampled RSS 28,905,472–34,189,312 bytes (27.6–32.6 MiB), with
8.63 CPU seconds over 223.756 observed seconds, or 0.03857 core equivalents. Compact
journaling and continuous ownership sampling reduced observed collector memory;
this sequential comparison does not isolate each change’s exact causal contribution.
Full raw snapshots and hashed byte-range ownership receipts remain auditable.

| Impaired-run memory / GC observation | Value |
| --- | --- |
| Native cgroup `memory.max` | 999,997,440 bytes, unchanged |
| Native cgroup `memory.swap.max` | 0, unchanged |
| Maximum observed native cgroup `memory.current` inside plateau | 620,281,856 bytes |
| Cgroup lifetime `memory.peak` shown in plateau | 638,812,160 bytes; this is a lifetime high-water mark, not an isolated plateau peak |
| Actually loaded JVM heap init / max | 1,073,741,824 / 4,294,967,296 bytes |
| Maximum sampled Java heap used | 4,143,972,352 bytes |
| New native OOM / OOM-kill / max events | 0 in saved first-to-last counters |
| Sampled plateau ZGC minor cycles | 2, reported cycle time 3,661 ms |
| Sampled plateau ZGC minor pauses | 6, reported time 0 ms; resolution does not imply literally zero duration |
| Sampled plateau ZGC major cycles | 0 |

JVM RSS includes non-heap memory and must not be compared directly with `-Xmx` as
if it were heap usage. Native cgroup accounting includes cache and charges beyond
native process RSS. ZGC cycle time is not stop-the-world pause time.

Host I/O pressure was already substantial: sampled interval I/O PSI was 65.7%
`some` and 59.0% `full`, while native-cgroup I/O PSI was approximately 5.6%.
Native process read-byte delta was about 1.145 GB over the 179.634-second observed interval union;
generator writes were about 147.9 MB over those 179.634 observed interval seconds.
These partial-interval I/O/PSI figures establish activity and shared
contention, not device-level attribution or a proof that Voxy caused the host's
I/O pressure. Optional cgroup `io.stat` was unavailable and is recorded as null,
not fabricated zero. Runnable scheduler wait is unavailable where schedstats are
disabled.

## Native stage measurements and bottleneck interpretation

The nearest saved native snapshots bracket the estimated plateau by approximately
one second: a **241.000-second same-epoch window**, starting about 12.8 ms before
the estimated start and ending about 987.2 ms after its estimated end. The estimate
uses guard spawn plus the runner's elapsed plateau start; unmeasured runner startup
offset remains. Global counters include the real PC and shared regional work.

| Stage | Completed count | Mean completed wall time | Same-thread CPU total | Meaning |
| --- | ---: | ---: | ---: | --- |
| Visible selection queue | 33,163 | 2.532 ms | Wait only | Completed eligibility-to-claim observations |
| Preparation executor queue | 41,563 | 0.00588 ms | Wait only | Blocking-worker submission to start |
| Preparation work | 41,563 | 0.467 ms | 1.082 s | Includes regional lookup |
| Directory read | 2,407 | 7.765 ms | 0.409 s | Miss-path read/validation work |
| Body executor queue | 33,215 | 0.00561 ms | Wait only | Blocking-worker submission to start |
| Body work | 33,215 | 1.670 ms | 0.365 s | Includes compressed payload reads |
| Compressed read | 21,901 | 2.547 ms | 0.269 s | Read latency mainly exceeds actual CPU work |
| Record admission | 33,215 | 0.000853 ms | Wait only | Transport admission boundary |
| Record write | 33,215 | 0.001710 ms | Wait only | Bytes accepted by Quinn, not client receipt |
| Native request inclusive | 41,563 | 1.826 ms | Async elapsed | Native completion boundary, not full remote request latency |
| Pacer wait | 45,486 | 12.280 ms | Wait only | Intentional existing server link scheduling |
| Pacer resume lateness | 45,486 | 0.999 ms | Wait only | Delay beyond scheduled native pacer wake |
| Source inspection | 264 | 209.599 ms | 55.273 s | Includes semantic saved-chunk parsing |
| Anvil NBT parse | 135,288 | 0.346 ms | 46.748 s | Largest measured CPU leaf |
| Anvil decompression | 135,288 | 0.0571 ms | 7.689 s | Saved-chunk decode cost |
| Source-table write | 114 | 200.704 ms | 0.0192 s | 22.880 s elapsed vs little executing-thread CPU |
| Fsync/rename | 20 | 196.497 ms | 0.0107 s | 3.930 s elapsed publication/durability work |

These totals are inclusive, nested and sometimes parallel. For example NBT parsing
is inside source inspection, which is inside regional refresh/publication. Do not
add their totals together, divide overlapping wall totals by 241 seconds to infer
utilization, or treat aggregate waits over many connections as one critical path.
The broader 446-second pre/post window in `final-analysis.json` also includes work
outside the pressure plateau and must not be described as a pure 240-second cohort.

The strongest supported processing finding is **saved-source semantic inspection
and NBT parsing dominate measured synchronous CPU**. Metadata-only publication
and durability also consume notable elapsed time with little CPU. Typical request
preparation/body/admission complete in milliseconds or less, whereas remote
validated completions take seconds. These observations direct investigation toward
network/congestion/request flow, saved-source scans and persistence; they do not
establish a single cause for the delivery shortfall. The matched control below strengthens the evidence for impaired-link/congestion
limitations: the same native epoch delivered substantially more terrain at the
same configured cap without the added delay/loss. It does not isolate RTT from
loss or eliminate changing live-source/file-cache effects.

| Follow-up target | Source / why it is relevant |
| --- | --- |
| Source semantic inspection | [runtime.rs:1544](/home/aerosmp/Desktop/ASMP_Voxy_Cache_First_Updates/rust-server/src/regional/runtime.rs:1544): changed-header/forced/unnotified cases read and parse saved chunks; this surrounds the largest CPU leaf |
| Chunk decode and parsing | [anvil.rs:333](/home/aerosmp/Desktop/ASMP_Voxy_Cache_First_Updates/rust-server/src/anvil.rs:333): file read, decompression and NBT are separately probed |
| Metadata-only sidecar persistence | [runtime.rs:1336](/home/aerosmp/Desktop/ASMP_Voxy_Cache_First_Updates/rust-server/src/regional/runtime.rs:1336), [source.rs:134](/home/aerosmp/Desktop/ASMP_Voxy_Cache_First_Updates/rust-server/src/regional/source.rs:134): unchanged semantics can still publish durable source metadata |
| Payload file reads and integrity | [store.rs:800](/home/aerosmp/Desktop/ASMP_Voxy_Cache_First_Updates/rust-server/src/regional/store.rs:800): compressed read/CRC stages distinguish I/O from validation CPU |
| Request/transport completion boundaries | [server.rs:1876](/home/aerosmp/Desktop/ASMP_Voxy_Cache_First_Updates/rust-server/src/server.rs:1876): preparation/body/Quinn write have separate waits and different completion meaning |
| Shared publication | [service.rs:531](/home/aerosmp/Desktop/ASMP_Voxy_Cache_First_Updates/rust-server/src/regional/service.rs:531): build work is shared and must be attributed once |
| Actual relay timing | [pressure_relay.rs:128](/home/aerosmp/Desktop/ASMP_Voxy_Cache_First_Updates/rust-server/src/pressure_relay.rs:128): serialization, loss and overdue delivery are independently counted |
| Probe semantics | [diagnostics.rs:1](/home/aerosmp/Desktop/ASMP_Voxy_Cache_First_Updates/rust-server/src/diagnostics.rs:1): asynchronous wall time and synchronous CPU have separate meaning |

No production scheduling, codec, renderer, cache-selection or refresh-cadence tuning
was applied by this diagnostic task.

## Transport capacity and production-readiness assessment

The exact 240-second useful-payload totals come from each client's final phase
counters, rather than sampling intervals or setup/drain bytes:

| Exact plateau metric | Impaired30 | Control30 |
| --- | ---: | ---: |
| Useful compressed terrain payload | 106,936,223 bytes | 748,993,664 bytes |
| Aggregate useful terrain rate | 3.564541 Mbps | 24.966455 Mbps |
| Per-client useful terrain rate, mean | 118.818 kbps | 832.215 kbps |
| Per-client useful terrain rate, range | 99.633–130.751 kbps | 822.458–839.187 kbps |
| Jain throughput fairness, 1 is equal | 0.997225 | 0.999973 |
| Minimum simultaneous authenticated clients | 30 | 30 |

All clients made useful missing-terrain progress in every phase. The control
establishes about 25 Mbps aggregate useful terrain delivery at this workload;
it cannot establish the server's maximum throughput because the intentional
aggregate downstream link ceiling was 30 Mbps. Setup and drain are excluded here.
The fake clients validate and persist terrain but do not mesh or render it.

Native connection snapshots distinguish source availability from transport:

| Sampled native transport state | Impaired30 | Control30 |
| --- | ---: | ---: |
| Full-cohort snapshots / connection observations | 83 / 2,490 | 218 / 6,540 |
| Quinn congestion window, minimum / median / maximum | 2,400 / 6,377.5 / 17,363 bytes | 2,245,017 / 14,277,242 / 26,650,425 bytes |
| Loaded smoothed RTT, median | 301.308 ms | 156.787 ms |
| Loaded smoothed RTT, range | 294.011–489.977 ms | 26.761–440.274 ms |
| Observations waiting for requested source data | 0 | 0 |
| Observations with nonzero request queue | 8 | 11 |
| Maximum request queue depth | 3 | 12 |
| Maximum oldest queued-request age | 203.886 ms | 107.714 ms |

The impaired observations end near the start of the warm phase; there is no
sampled transport-state proof for its final roughly 59 seconds. The runner's
full-plateau useful-progress and completion counters are independent of that gap.
Control loaded RTT includes serialization/queueing under the shaped link; it is
not the approximately 22 ms unloaded setup RTT and does not mean artificial RTT
was enabled in the control.

At 1 Mbps and 300 ms, the bandwidth-delay product is 37,500 bytes. Every sampled
impaired congestion window was below that, with a median around 6.4 KB. Median
`window / RTT` suggests roughly 169 kbps of in-flight capacity before overhead,
loss/recovery and application effects. This is a useful diagnostic approximation,
not an exact throughput bound or proof that one congestion-control change fixes it.
The observed approximately 119 kbps/client useful payload is consistent with this
network limitation. Source waits and request queues do not show a persistent source
supply bottleneck in these observations. The control's much larger windows and
near-cap delivery strengthen the distinction.

The strongest supported conclusion is **no demonstrated server CPU or request
supply saturation for these 30 clients**. Reliability/progress and fair delivery
passed the short live workload. The impaired cold-loading experience is still slow:
first fine detail took 4.8–14.2 seconds, despite low server CPU. That is not a claim
that the present system is satisfactory for every production network.

Investigation priorities from these measurements are:

1. Impaired-link transport: quantify congestion/loss recovery, stream flow-control
   and request completion dependencies before changing controllers or protocol.
   The current evidence identifies this as the immediate delivery restriction,
   but does not isolate RTT, loss and added scheduling jitter experimentally.
2. Saved-source inspection: NBT parsing is the largest measured synchronous CPU
   leaf (46.748 / 50.631 CPU seconds in the selected impaired/control windows).
   This is substantial background work; it did not put sampled fake requests into
   source wait, so reducing it is not proven to fix the impaired download latency.
3. Shared disk and publication: host I/O pressure and source-table/fsync wall time
   warrant separate inspection. Competing host activity, server file cache and
   generator persistence are not causally separated by these tests.
4. Production qualification: this is two four-minute Voxy-only cohorts, not a soak
   test or 30 Minecraft clients contributing normal ticking/rendering workloads.
   Neither maximum unshaped capacity nor sustained block-change freshness was
   established. Real-PC screenshots/telemetry verify the retained player's view,
   not an FPS or complete meshing correctness benchmark.

The raw per-connection state is retained in both `resources.jsonl` files below;
per-client final counters are in the corresponding runner logs. No production
transport or source-scan optimization was applied during this measurement task.

## Real PC: signed telemetry and viewed screenshots

The existing game process 22328, started `2026-10-07T07:37:23.7265165Z`, remained
on client 268. Both pinned backup SSH routes and helpers 24924/21732 survived.
Stale local port records were repaired to ports 34431/44977 while retaining the
existing pins/helpers. The real PC's own network profile was not replaced by the
fake-client impairment.

Both of the following typed Minecraft render captures succeeded and were viewed
by root and the report author. They show the same quarry and distant terrain view.

| Capture | Render result | SHA-256 | Evidence |
| --- | --- | --- | --- |
| Before pressure, 08:56:12.724 UTC | `SCREENSHOT_RESULT`, success, failure `NONE` | `3d2ca66b8e350bb8dcbbf8756c1c9cfde042bc15ed55d4a31c3cba6991e606ee` | [Before PNG](/home/aerosmp/Desktop/Codex_Tools/Voxy_Workflow_Tooling/runs/962583e5-34b0-4155-b89c-83a10e23f216/voxy-test-962583e5-34b0-4155-b89c-83a10e23f216-1.png) |
| During pressure, 08:59:58.690 UTC | `SCREENSHOT_RESULT`, success, failure `NONE` | `527fc989b5f95b0f06860c6832f624a0e5afe89edddc5a07a6ff0a09c77aefc3` | [During PNG](/home/aerosmp/Desktop/Codex_Tools/Voxy_Workflow_Tooling/runs/962583e5-34b0-4155-b89c-83a10e23f216/voxy-test-962583e5-34b0-4155-b89c-83a10e23f216-3.png) |

| Signed cumulative PC counter | Baseline trace | Recovery trace |
| --- | ---: | ---: |
| Cache hits / misses | 64,849 / 0 | 64,861 / 0 |
| Decoded and meshed | 64,902 | 65,007 |
| Activated / active sections | 65,132 / 65,079 | 65,237 / 65,091 |
| GPU draws in snapshot | 45,082 | 46,099 |
| Publication failures / failure code | 0 / 0 | 0 / 0 |
| Renderer identity | 4 | 4 |

Counters are cumulative or single snapshots, not rates. The trace first/last frame
fields are equal within each receipt, so there is no defensible FPS or p99.5 frame
time estimate. The PC cache was warm before the experiment; these counters do not
prove a new cold-cache speed gain. The final typed END/finish result succeeded;
cache size was 360,434,657 bytes, renderer identity remained 4, and no publication
failure was reported. No PC cache deletion, scripted movement or settings reset
was used. Two views cannot prove all meshing, border, modded-block or flicker cases.

An earlier native desktop capture failed with `REMOTE_ERROR`, exit 1 and no PNG.
Root reconciled that read as rejected without input or target mutation, and used
the successful typed render captures for actual screenshot proof. The native path
selects PrintWindow when it observes a nonforeground window. Its remote stderr was
not retained, so the precise native failure is unproven; this task does not claim
to have fixed PrintWindow. The later typed PNG sizes exceed the native capture's
4 MiB limit, but that alone does not prove the failed native attempt's cause.

## Cleanup and preservation

Calibration initially treated persistent route-mux sockets as leaked sessions.
That interpretation was corrected: registered routes can retain their sockets
between one-client invocations. Reuse is permitted only after owned authenticated
connections/sessions/subscriptions are independently absent. **Final token cleanup
still requires route sockets themselves to reach zero.**

The final debug pressure state was `CLOSED`: owned routes, sessions, subscriptions,
route sockets, queued/inflight/outstanding/shared/exclusive/unknown source work
were all zero, `cleanup_pending` was false and `connections` was empty. The native
mode-2 reset acknowledgment reported timing probes and reporting off. The observed
regional-coordinate set is retained scope metadata until reset; a nonzero historical
observed-coordinate count is not pending work. No owned guard/runner process survived.

Root independently closed the lease after verifying real-PC controls/settings/cache,
both backup routes, Main identity and existing memory limits. The successful Testing
server-270 candidate remained loaded by explicit closure choice; rollback server-269
material remains in the run directory. Natural Minecraft saves and user movement
are not treated as unauthorized operator mutation or forcibly reversed.

## Raw evidence and exported metrics

All numerical analysis is offline from saved artifacts. Full samples preserve
unavailable fields, identities, timestamps and gaps. CSVs support per-core,
per-process, per-thread and per-client examination rather than replacing the raw
receipts with summary averages.

| Evidence | File |
| --- | --- |
| Final offline analysis, broader saved-resource window | [final-analysis.json](/home/aerosmp/Desktop/Codex_Tools/Voxy_Workflow_Tooling/tasks/server-pressure-20261006/runs/962583e5-34b0-4155-b89c-83a10e23f216/final-analysis.json) |
| Estimated plateau summary | [pressure-summary.json](/home/aerosmp/Desktop/Codex_Tools/Voxy_Workflow_Tooling/tasks/server-pressure-20261006/runs/962583e5-34b0-4155-b89c-83a10e23f216/pressure-summary.json) |
| Same-epoch selected stage delta | [plateau-timing-delta.json](/home/aerosmp/Desktop/Codex_Tools/Voxy_Workflow_Tooling/tasks/server-pressure-20261006/runs/962583e5-34b0-4155-b89c-83a10e23f216/plateau-timing-delta.json) |
| Runner events and final checks | [runner.jsonl](/home/aerosmp/Desktop/Codex_Tools/Voxy_Workflow_Tooling/tasks/server-pressure-20261006/runs/962583e5-34b0-4155-b89c-83a10e23f216/cold30/runner.jsonl) |
| Full host/process/native status observations | [resources.jsonl](/home/aerosmp/Desktop/Codex_Tools/Voxy_Workflow_Tooling/tasks/server-pressure-20261006/runs/962583e5-34b0-4155-b89c-83a10e23f216/resources.jsonl) |
| Aggregate native stage snapshots | [testing-pressure.log](/home/aerosmp/Desktop/Codex_Tools/Voxy_Workflow_Tooling/tasks/server-pressure-20261006/runs/962583e5-34b0-4155-b89c-83a10e23f216/testing-pressure.log) |
| Independent cleanup proposal | [cleanup-proposal.json](/home/aerosmp/Desktop/Codex_Tools/Voxy_Workflow_Tooling/tasks/server-pressure-20261006/runs/962583e5-34b0-4155-b89c-83a10e23f216/cleanup-proposal.json) |
| Root closure receipt | [closure.stdout](/home/aerosmp/Desktop/Codex_Tools/Voxy_Workflow_Tooling/tasks/server-pressure-20261006/runs/962583e5-34b0-4155-b89c-83a10e23f216/closure.stdout) |
| Per-core host CPU | [host_cpu.csv](/home/aerosmp/Desktop/Codex_Tools/Voxy_Workflow_Tooling/tasks/server-pressure-20261006/runs/962583e5-34b0-4155-b89c-83a10e23f216/metrics/host_cpu.csv) |
| All readable host process CPU/RSS | [host_processes.csv](/home/aerosmp/Desktop/Codex_Tools/Voxy_Workflow_Tooling/tasks/server-pressure-20261006/runs/962583e5-34b0-4155-b89c-83a10e23f216/metrics/host_processes.csv) |
| Target process CPU/RSS/I/O | [processes.csv](/home/aerosmp/Desktop/Codex_Tools/Voxy_Workflow_Tooling/tasks/server-pressure-20261006/runs/962583e5-34b0-4155-b89c-83a10e23f216/metrics/processes.csv) |
| Native/Java/generator/observer threads | [threads.csv](/home/aerosmp/Desktop/Codex_Tools/Voxy_Workflow_Tooling/tasks/server-pressure-20261006/runs/962583e5-34b0-4155-b89c-83a10e23f216/metrics/threads.csv) |
| Per-client phase/transport/progress | [clients.csv](/home/aerosmp/Desktop/Codex_Tools/Voxy_Workflow_Tooling/tasks/server-pressure-20261006/runs/962583e5-34b0-4155-b89c-83a10e23f216/metrics/clients.csv) |
| Sampling duration/lateness/gaps | [sample_clock.csv](/home/aerosmp/Desktop/Codex_Tools/Voxy_Workflow_Tooling/tasks/server-pressure-20261006/runs/962583e5-34b0-4155-b89c-83a10e23f216/metrics/sample_clock.csv) |
| Hard limits/OOM counters | [memory_limits.csv](/home/aerosmp/Desktop/Codex_Tools/Voxy_Workflow_Tooling/tasks/server-pressure-20261006/runs/962583e5-34b0-4155-b89c-83a10e23f216/metrics/memory_limits.csv) |
| Host and native pressure stalls | [psi.csv](/home/aerosmp/Desktop/Codex_Tools/Voxy_Workflow_Tooling/tasks/server-pressure-20261006/runs/962583e5-34b0-4155-b89c-83a10e23f216/metrics/psi.csv) |
| Optional cgroup I/O controller data | [cgroup_io.csv](/home/aerosmp/Desktop/Codex_Tools/Voxy_Workflow_Tooling/tasks/server-pressure-20261006/runs/962583e5-34b0-4155-b89c-83a10e23f216/metrics/cgroup_io.csv) |
| Loaded JVM heap/GC | [gc.csv](/home/aerosmp/Desktop/Codex_Tools/Voxy_Workflow_Tooling/tasks/server-pressure-20261006/runs/962583e5-34b0-4155-b89c-83a10e23f216/metrics/gc.csv) |

## Historical attempt and completed control comparison

The earlier run `d617ed73-ce07-4fd8-b2ea-d63455863f34` registered 100 routes but did
not run a 100-client pressure plateau. It stopped during one-client calibration
under the then-active short timeout and closed with owned cleanup; its failure is
preserved, not relabelled successful or removed. The user's later explicit 30-client
scope and removal of global testing limits govern the completed run above.

The matched control `989d9c6a-416a-4b8a-b174-17cdd7ae58f5` completed with the same
native epoch/server 270/retained PC 268, distinct owned tokens and fresh fake caches,
the same seed/workload, and 1 Mbps actual per-direction cap. It added 0 ms delay and 0%
loss; its unloaded setup RTT was 22.188–22.966 ms. No server/PC redeployment or
production tuning was performed between these runs. Root closed this control lease
at 516.812 seconds with both backup routes, user cache/settings and Main preserved.

| Matched workload metric | Impaired30 | Control30 |
| --- | ---: | ---: |
| Full plateau / minimum concurrent clients | 240 s / 30 | 240 s / 30 |
| Runner elapsed including setup/drain | 256.813 s | 242.459 s |
| Whole-invocation compressed payload | 110.643 MB | 750.737 MB |
| Three-phase plateau compressed payload | 106.936 MB | 748.994 MB |
| DATA / EMPTY / REUSE | 21,989 / 6,365 / 4,675 | 150,106 / 43,928 / 35,566 |
| Weighted mean completed-request latency | 3.579853 s | 0.502631 s |
| Completion p50 bin | (2.097152,4.194304]s | (0.262144,0.524288]s |
| Completion p95 bin | (4.194304,8.388608]s | (0.524288,1.048576]s |
| Completion p99.5 bin | (8.388608,16.777216]s | (1.048576,2.097152]s |
| Median first coarse / first detail | 2.691 /8.601 s | 0.595 /1.588 s |
| Missing completions by phase | 9,660 /9,969 / 6,456 | 67,932 /70,600 /45,551 |
| Lowest client’s missing completions by phase | 204 /234 /148 | 1,799 /1,864 /1,282 |
| Idle samples / ABSENT / NOT_READY | 0 / 0 / 0 | 0 / 0 / 0 |
| Actual send/service cap violations / socket errors | 0 / 0 / 0 | 0 / 0 / 0 |
| Largest client progress-pulse lateness | 1,199.170 ms | 703.273 ms |
| Largest relay downstream delivery lateness | 610.978 ms | 3.893 ms |

Weighted mean latency weights each client by its completed records; it differs from
the mean-of-client-means reported earlier (3.660s/0.508s). All latency histograms
include completed statuses and setup/drain rather than exact DATA-only phase cohorts.
Control whole-invocation payload was 6.79 times greater; its approximately 7.12 times
shorter weighted completion mean shows that this server could serve much more useful
traffic than the impaired run delivered. That supports network/congestion/request
flow as the immediate delivery investigation priority. The follow-up still combines
RTT and loss changes, later live-world state, and a warmer server/file cache. It is
not a fully isolated causal experiment or proof of unlimited server capacity.

Control useful-payload rates/client in sampled phase intervals were 842.1/842.9/800.9
kbps, with charged bidirectional IP rates 976.6/979.0/1,006.7 kbps. These totals
include upstream ACK/request traffic plus downstream terrain/control traffic. Each
direction independently has a 1 Mbps cap, so a bidirectional sum above 1 Mbps is
consistent with both caps. Counter-derived interval averages also differ from exact
completed-send rolling-window checks, which reported zero violations. Neither
measurement claims fractional-packet instantaneous rate control. Actual
observed loss was zero in both control directions, and unexpected socket errors were
zero. Native source NBT CPU remained substantial (50.631 s in the selected 241 s timing
window), while source-table persistence elapsed time was 5.047 s vs 22.880 s in the
impaired selected window. Live-source and cache/disk state can affect that difference.

The control also made the existing cgroup work near its configured memory ceiling.
Maximum sampled `memory.current` was 999,993,344 bytes, while the lifetime
`memory.peak` reached**1,000,017,920 bytes**, 20,480 bytes above configured
`memory.max` 999,997,440 and 17,920 bytes above decimal 1 GB. `memory.events:max`
increased 1,381 across the analyzer’s captured endpoints (1,399 in the later root
post-control receipt), with OOM/OOM-kill counters still 0. These receipts verify the
configured enforcement/reclaim mechanism and absence of OOM; they do **not** prove
that charged memory never exceeded 1 GB at any instant.

A root near-cap breakdown measured about 851.9 MB file cache, 139.7 MB anonymous memory
and 6.7 MB kernel accounting. The later breakdown showed 843.0 MB file cache and 139.9 MB
anonymous memory. This explains why cgroup charged usage approached the cap while
native process RSS was about 141 MB, without treating the readings as perfectly atomic.
See [near-cap memory breakdown](/home/aerosmp/Desktop/Codex_Tools/Voxy_Workflow_Tooling/tasks/server-pressure-20261006/runs/989d9c6a-416a-4b8a-b174-17cdd7ae58f5/near-cap-memory-breakdown.json)
and [post-control memory breakdown](/home/aerosmp/Desktop/Codex_Tools/Voxy_Workflow_Tooling/tasks/server-pressure-20261006/runs/989d9c6a-416a-4b8a-b174-17cdd7ae58f5/post-control-memory-breakdown.json).

Control native request stages changed with the faster link: preparation averaged
0.0196 ms and body work 0.0548 ms, while transport admission/write waits averaged
3.386/2.390 ms. The faster workload can fill the transport; these Quinn acceptance
boundaries remain different from the client’s completed validation. Background
refresh queue waits averaged 15.090 s over 6,628 completed entries, consistent with
lower-priority refresh competing with sustained useful demand; this run does not
prove block-change freshness. Inclusive/parallel stage totals remain nonadditive.

The continuous observer covered the control after its initial 18.6 s admission/start
interval. Full raw snapshots remain on disk while the ownership journal stores
compact hashed byte-range receipts, avoiding duplicated sample payloads and repeated
large status parsing. The earlier observer’s memory cost was measured; assigning it
to a particular quadratic per-sample guard algorithm would be incorrect: RunStore
status parses prior responses, but its per-operation guard does not.

Control raw analysis and all exported metrics:

- [final-analysis.json](/home/aerosmp/Desktop/Codex_Tools/Voxy_Workflow_Tooling/tasks/server-pressure-20261006/runs/989d9c6a-416a-4b8a-b174-17cdd7ae58f5/final-analysis.json)
- [pressure-summary.json](/home/aerosmp/Desktop/Codex_Tools/Voxy_Workflow_Tooling/tasks/server-pressure-20261006/runs/989d9c6a-416a-4b8a-b174-17cdd7ae58f5/pressure-summary.json)
- [plateau-timing-delta.json](/home/aerosmp/Desktop/Codex_Tools/Voxy_Workflow_Tooling/tasks/server-pressure-20261006/runs/989d9c6a-416a-4b8a-b174-17cdd7ae58f5/plateau-timing-delta.json)
- [control30/runner.jsonl](/home/aerosmp/Desktop/Codex_Tools/Voxy_Workflow_Tooling/tasks/server-pressure-20261006/runs/989d9c6a-416a-4b8a-b174-17cdd7ae58f5/control30/runner.jsonl)
- [resources.jsonl](/home/aerosmp/Desktop/Codex_Tools/Voxy_Workflow_Tooling/tasks/server-pressure-20261006/runs/989d9c6a-416a-4b8a-b174-17cdd7ae58f5/resources.jsonl)
- [sample-clock.jsonl](/home/aerosmp/Desktop/Codex_Tools/Voxy_Workflow_Tooling/tasks/server-pressure-20261006/runs/989d9c6a-416a-4b8a-b174-17cdd7ae58f5/sample-clock.jsonl)
- [cleanup-proposal.json](/home/aerosmp/Desktop/Codex_Tools/Voxy_Workflow_Tooling/tasks/server-pressure-20261006/runs/989d9c6a-416a-4b8a-b174-17cdd7ae58f5/cleanup-proposal.json)
- [closure.stdout](/home/aerosmp/Desktop/Codex_Tools/Voxy_Workflow_Tooling/tasks/server-pressure-20261006/runs/989d9c6a-416a-4b8a-b174-17cdd7ae58f5/closure.stdout)

- [host_cpu.csv](/home/aerosmp/Desktop/Codex_Tools/Voxy_Workflow_Tooling/tasks/server-pressure-20261006/runs/989d9c6a-416a-4b8a-b174-17cdd7ae58f5/metrics/host_cpu.csv)
- [host_processes.csv](/home/aerosmp/Desktop/Codex_Tools/Voxy_Workflow_Tooling/tasks/server-pressure-20261006/runs/989d9c6a-416a-4b8a-b174-17cdd7ae58f5/metrics/host_processes.csv)
- [processes.csv](/home/aerosmp/Desktop/Codex_Tools/Voxy_Workflow_Tooling/tasks/server-pressure-20261006/runs/989d9c6a-416a-4b8a-b174-17cdd7ae58f5/metrics/processes.csv)
- [threads.csv](/home/aerosmp/Desktop/Codex_Tools/Voxy_Workflow_Tooling/tasks/server-pressure-20261006/runs/989d9c6a-416a-4b8a-b174-17cdd7ae58f5/metrics/threads.csv)
- [clients.csv](/home/aerosmp/Desktop/Codex_Tools/Voxy_Workflow_Tooling/tasks/server-pressure-20261006/runs/989d9c6a-416a-4b8a-b174-17cdd7ae58f5/metrics/clients.csv)
- [sample_clock.csv](/home/aerosmp/Desktop/Codex_Tools/Voxy_Workflow_Tooling/tasks/server-pressure-20261006/runs/989d9c6a-416a-4b8a-b174-17cdd7ae58f5/metrics/sample_clock.csv)
- [memory_limits.csv](/home/aerosmp/Desktop/Codex_Tools/Voxy_Workflow_Tooling/tasks/server-pressure-20261006/runs/989d9c6a-416a-4b8a-b174-17cdd7ae58f5/metrics/memory_limits.csv)
- [psi.csv](/home/aerosmp/Desktop/Codex_Tools/Voxy_Workflow_Tooling/tasks/server-pressure-20261006/runs/989d9c6a-416a-4b8a-b174-17cdd7ae58f5/metrics/psi.csv)
- [cgroup_io.csv](/home/aerosmp/Desktop/Codex_Tools/Voxy_Workflow_Tooling/tasks/server-pressure-20261006/runs/989d9c6a-416a-4b8a-b174-17cdd7ae58f5/metrics/cgroup_io.csv)
- [gc.csv](/home/aerosmp/Desktop/Codex_Tools/Voxy_Workflow_Tooling/tasks/server-pressure-20261006/runs/989d9c6a-416a-4b8a-b174-17cdd7ae58f5/metrics/gc.csv)

## Every logical CPU and the busiest measured threads

The CPU table uses disjoint `/proc/stat` category counter deltas between the same
resource endpoints used for each run’s process table. These cumulative counters
include work during the impaired sampling gap; they cannot reconstruct transient
peaks within it. Busy includes executing user/kernel/interrupt/guest categories,
while I/O wait is shown separately; guest time is counted once. These are whole-host
means, including Main and unrelated processes, not Voxy-only attribution.

| Logical CPU | Impaired busy | Impaired I/O wait | Control busy | Control I/O wait |
| --- | ---: | ---: | ---: | ---: |
| 0 | 14.16% | 85.84% | 15.75% | 84.25% |
| 1 | 16.88% | 38.87% | 21.12% | 35.73% |
| 2 | 20.38% | 40.18% | 23.46% | 34.23% |
| 3 | 14.17% | 50.69% | 15.81% | 47.23% |
| 4 | 16.40% | 38.05% | 13.79% | 38.92% |
| 5 | 18.27% | 44.12% | 20.73% | 39.43% |
| 6 | 12.31% | 53.18% | 13.42% | 49.56% |
| 7 | 9.75% | 52.82% | 11.30% | 50.77% |
| 8 | 3.95% | 24.50% | 4.62% | 23.73% |
| 9 | 3.47% | 26.31% | 4.35% | 29.96% |
| 10 | 3.74% | 22.09% | 4.61% | 30.68% |
| 11 | 3.72% | 21.79% | 4.67% | 26.09% |
| 12 | 4.32% | 23.50% | 5.08% | 20.29% |
| 13 | 3.62% | 28.81% | 4.46% | 25.47% |
| 14 | 3.29% | 22.76% | 4.21% | 21.26% |
| 15 | 3.30% | 25.79% | 3.96% | 29.73% |
| 16 | 15.80% | 47.98% | 17.36% | 45.04% |
| 17 | 66.02% | 16.91% | 60.70% | 15.80% |
| 18 | 28.00% | 35.46% | 30.42% | 33.45% |
| 19 | 14.67% | 51.26% | 16.33% | 42.16% |
| 20 | 61.61% | 17.23% | 82.75% | 5.62% |
| 21 | 22.88% | 41.13% | 22.77% | 37.35% |
| 22 | 12.67% | 53.33% | 13.62% | 48.46% |
| 23 | 10.32% | 54.54% | 11.15% | 54.91% |
| 24 | 4.02% | 31.44% | 4.94% | 24.90% |
| 25 | 3.63% | 29.93% | 4.65% | 23.42% |
| 26 | 4.10% | 33.35% | 5.10% | 27.93% |
| 27 | 3.83% | 96.17% | 4.87% | 26.25% |
| 28 | 4.48% | 35.38% | 5.85% | 38.58% |
| 29 | 3.65% | 96.35% | 4.67% | 95.33% |
| 30 | 3.60% | 27.64% | 4.32% | 36.34% |
| 31 | 3.28% | 20.91% | 4.16% | 22.13% |

The following are the top ten measured threads for each target role in each run,
ranked by CPU seconds. IDs include their process/TID start identities in the full
CSV; identical names do not identify the same worker. Thread CPU accumulates across
cores, and “last processor” cannot assign migrated interval work to one core.
Only valid observed thread intervals are included; ended/absent threads and unseen
tails are not invented. This differs from the process endpoint means above.

### Native Voxy

| Rank | Impaired TID / name | CPU seconds | Control TID / name | CPU seconds |
| --- | --- | ---: | --- | ---: |
| 1 | `382768` tokio-rt-worker | 3.580 | `442938` tokio-rt-worker | 4.080 |
| 2 | `382770` tokio-rt-worker | 3.550 | `382767` tokio-rt-worker | 3.240 |
| 3 | `382764` tokio-rt-worker | 3.480 | `382760` tokio-rt-worker | 2.820 |
| 4 | `382763` tokio-rt-worker | 3.470 | `382766` tokio-rt-worker | 2.800 |
| 5 | `395253` tokio-rt-worker | 3.410 | `399864` tokio-rt-worker | 2.710 |
| 6 | `382761` tokio-rt-worker | 3.000 | `382763` tokio-rt-worker | 2.410 |
| 7 | `382762` tokio-rt-worker | 2.700 | `442937` tokio-rt-worker | 2.370 |
| 8 | `382766` tokio-rt-worker | 2.660 | `445962` tokio-rt-worker | 2.260 |
| 9 | `395257` tokio-rt-worker | 2.590 | `442936` tokio-rt-worker | 1.960 |
| 10 | `395251` tokio-rt-worker | 2.560 | `395257` tokio-rt-worker | 1.930 |

### Testing Java

| Rank | Impaired TID / name | CPU seconds | Control TID / name | CPU seconds |
| --- | --- | ---: | --- | ---: |
| 1 | `380092` Server thread | 11.000 | `380092` Server thread | 10.360 |
| 2 | `380381` sable-physics-v | 2.930 | `380381` sable-physics-v | 2.710 |
| 3 | `380382` sable-physics-v | 2.840 | `380382` sable-physics-v | 2.620 |
| 4 | `380383` sable-physics-v | 2.800 | `380383` sable-physics-v | 2.590 |
| 5 | `380384` sable-physics-v | 2.770 | `380384` sable-physics-v | 2.560 |
| 6 | `380385` sable-physics-v | 2.720 | `380385` sable-physics-v | 2.520 |
| 7 | `380386` sable-physics-v | 2.710 | `380386` sable-physics-v | 2.520 |
| 8 | `380387` sable-physics-v | 2.640 | `380387` sable-physics-v | 2.450 |
| 9 | `380388` sable-physics-v | 2.620 | `380388` sable-physics-v | 2.410 |
| 10 | `380389` sable-physics-v | 2.600 | `380389` sable-physics-v | 2.390 |

### Rust generator

| Rank | Impaired TID / name | CPU seconds | Control TID / name | CPU seconds |
| --- | --- | ---: | --- | ---: |
| 1 | `395250` live_pressure | 3.130 | `442933` live_pressure | 10.430 |
| 2 | `395267` tokio-rt-worker | 0.170 | `442948` tokio-rt-worker | 0.910 |
| 3 | `395380` tokio-rt-worker | 0.170 | `442947` tokio-rt-worker | 0.900 |
| 4 | `395719` tokio-rt-worker | 0.160 | `442951` tokio-rt-worker | 0.900 |
| 5 | `395268` tokio-rt-worker | 0.160 | `442954` tokio-rt-worker | 0.900 |
| 6 | `395749` tokio-rt-worker | 0.150 | `442955` tokio-rt-worker | 0.900 |
| 7 | `395750` tokio-rt-worker | 0.150 | `442956` tokio-rt-worker | 0.900 |
| 8 | `395751` tokio-rt-worker | 0.150 | `442957` tokio-rt-worker | 0.900 |
| 9 | `395769` tokio-rt-worker | 0.150 | `442958` tokio-rt-worker | 0.900 |
| 10 | `395770` tokio-rt-worker | 0.150 | `442959` tokio-rt-worker | 0.900 |

## Implementation and artifact validation

The locally committed source/plan change is `44ad65ff13c15d2e3159cecf45e6a3b736156c4e`.
It updates the runner to 30 clients, records resource/transport/progress metrics,
uses one current-thread async runtime with worker-owned validation/persistence,
and removes the old arbitrary debug-control 600-second ceiling. Production terrain
behavior and the real-PC artifact were not modified by these diagnostics.

The following checks passed before the live runs:

- Normal/debug server builds, Java pressure-control pure checks,
  `verifyServerDiagnosticsArtifacts` and `buildLivePressure`, all offline.
- Socket-free relay arithmetic/seeded-loss checks and native timing/ownership checks.
- Exact native embedding in both server JARs and absence of pressure/diagnostic
  classes and markers from normal artifacts.
- Build input hashes identical before/after/final:
  `71792c2eb1919911bff45a0a8e9ab3aaa07ecc4ba38aa2b25d6a36492f93eae7`.

No integration tests were added or run. The external observer was changed to keep
full raw samples while journaling compact hashed byte-range receipts; unavailable
optional cgroup counters remain null. The sealed pinned toolkit was not modified.
The server candidate stayed loaded after independently verified cleanup. No client
updater publication or GitHub publishing occurred.

Receipts:
[build gates](/home/aerosmp/Desktop/Codex_Tools/Voxy_Workflow_Tooling/tasks/server-pressure-20261006/pressure270-gates.json),
[relay checks](/home/aerosmp/Desktop/Codex_Tools/Voxy_Workflow_Tooling/tasks/server-pressure-20261006/pressure270-relay.json),
[timing checks](/home/aerosmp/Desktop/Codex_Tools/Voxy_Workflow_Tooling/tasks/server-pressure-20261006/pressure270-timers.json),
[artifact hashes](/home/aerosmp/Desktop/Codex_Tools/Voxy_Workflow_Tooling/tasks/server-pressure-20261006/pressure270-artifacts.json),
[final source inputs](/home/aerosmp/Desktop/Codex_Tools/Voxy_Workflow_Tooling/tasks/server-pressure-20261006/pressure270-final-inputs.json),
[qualified completion record](/home/aerosmp/Desktop/Codex_Tools/Voxy_Workflow_Tooling/tasks/server-pressure-20261006/completion-record.json).

## Preserved historical evidence

The following sections describe earlier attempts and preparation, including older
artifact identities and restrictions. They are preserved for auditability and are
superseded by the completed 30-client results above. They are not current runtime
status or remaining permission requirements.

## Historical October7 first attempt and cleanup

Run: `d617ed73-ce07-4fd8-b2ea-d63455863f34`. All owned evidence is under
`/home/aerosmp/Desktop/Codex_Tools/Voxy_Workflow_Tooling/tasks/server-pressure-20261006/runs/d617ed73-ce07-4fd8-b2ea-d63455863f34/`.
The then-active600-second clock closed after435.588seconds. At closure of that
first attempt no additional clock had been activated. The user subsequently removed
this restriction; the completed30-client runs above supersede it. Two earlier preparation manifests were never activated;
one was cancelled while PREPARED, and one failed before RunStore preparation.

Both existing PC host keys matched newly assigned loopback SSH ports34431 and44977.
Only the stale local `tunnel-port` records were repaired; pins and remote helpers
were preserved. `receipts/route-rediscovery-oct7.json` records old/new ports and
unchanged pin hashes. Real PC game22328/start07:37:23.7265165Z and helpers24924/21732
remained alive. Scoped Testing deployment preserved configuration, unrelated mods,
saved worlds and the PC cache. The first typed BEGIN was explicitly rejected
with `No player was found` during ordinary reconnect; the later BEGIN succeeded,
with exact signed PC268 build identity. There was no replay of an uncertain call.

The Java bridge registered100 unique run-owned routes and native READY confirmed
them. This establishes registration, **not100 connections or capacity**. Only one
Rust client was admitted for each of the two calibration workloads below.

| One-client workload | Unshaped baseline | Impaired, timings off |
| --- | ---: | ---: |
| Added RTT / seeded loss each direction / full-duplex cap | 0ms /0% /1000kbps | 300ms /10% /1000kbps |
| First validated coarse terrain | 615.076ms | 6631.362ms |
| First validated finer terrain | 1675.256ms | 12505.493ms |
| Validated records, DATA/EMPTY | 105,99/6 | 10,9/1 |
| Validated compressed payload bytes | 586649 | 51021 |
| Shared plateau start after runner launch | 2.001s | 13.002s |
| Full required5-second calibration plateau | Yes | No; original15-second runner window elapsed |
| Runner technical outcome | PASS | INCOMPLETE; `external/setup deadline before full plateau` |
| Final Quinn smoothed RTT estimate | 54.488ms | 317.309ms |
| Intentionally dropped upload /download datagrams | 0/416;0/575 | 16/134;16/178 |
| Integrity/protocol errors, relay errors/truncation/rate violations | 0 observed | 0 observed |

These are measured calibration results, not100-client results or a controlled
timing-overhead comparison. Smoothed RTT under traffic is not an unloaded RTT
distribution. Loss percentages are statistical; the saved analyzer verified
the exact seeded drop counts, packet/byte reconciliation and reported service
ceilings. No timing-on workload completed, so server processing bottlenecks and
instrumentation overhead remain unmeasured. Probes were off; zero stage counters
cannot be interpreted as zero processing time.

Three external-driver mistakes were found and corrected without changing Voxy
source or artifacts:

- The PC preservation gate compared the derived `estimatedWorldBytes` field as
  a user control. Bootstrap legitimately refreshed3632364026 to3351352893. The
  corrected comparison excludes only that derived field and its containing file
  hash; all actual settings, anchors, artifacts, game/helpers and cache markers
  remain checked. Neither value was written back to the PC.
- The resource reader assumed this cgroup exposed `io.stat`. Its absence raised
  a known read failure; watch's fault cleanup correctly entered RESTORING. Optional
  unavailable I/O/PSI counters now remain null, never fabricated zeros. Mandatory
  memory limits/events and process identity checks are unchanged. No complete
  resource sample was captured by that failed observer, so generator CPU/RSS,
  native peak/PSI and socket-close-tail claims are unavailable for this attempt.
- The15-second one-client window left less than5seconds after measured13-second
  impaired setup. Its experiment ceiling is now30seconds, while retaining the
  full5-second calibration plateau and original600/180-second run boundaries.
  The attempted follow-up was rejected before submission because the run had
  already entered RESTORING; it did not start another client or change timings.

`owner-corrections.json` records corrected external helper hashes. `analysis.json`
contains saved-artifact analysis, and `testing-latest-after-cleanup.log` has SHA256
`4c822d3856e3dfd1d1e367c785fed8f5877addc12c1da4d49054044aa1507e1f`.

Cleanup independently observed zero owned routes, sessions, subscriptions, routed
sockets, exclusive/unknown source regions, and no surviving guard/runner processes.
The native diagnostic-reset acknowledgement proved reporting/probes off.
`cleanup-proposal.json`, confirmed typed END/ABORTED finish, and `closure.stdout`
establish restoration and the closed cooperative lease. PC controls/cache markers
and helpers, Testing configuration/unrelated mods, and MainPID2703517/start52797315
were preserved. No operator pose change occurred; natural login-position settlement
was recorded rather than overwritten. No screenshot or controlled FPS observation
was performed. The final real-PC snapshot showed67314 cache hits,67554 activated
sections and44682 GPU draws; those cumulative counters establish activity only.

Loaded Testing JavaPID267145/start58275978 and nativePID268114/start58277257 use
debugserver269/native51d0d065…; the exact hashes remain in the artifact table below.
The retained PC268 hash is
`74dc0d71781c677bca2256a04d3913b6bfac87adf67d1b8220cb7d81cac38eeb`.
Actual Java heap init/max were1073741824/4294967296bytes. Native hard limit remained
999997440bytes with swap0; its new cgroup's OOM/kill/group-kill and max-event
counters remained0. Native memory.current at final closure was621707264bytes,
**not a measured peak or100-client pressure observation**. The candidate remains
loaded; prepared267 rollback is preserved.

At this historical closure, the then-active plan's one-clock rule required new
authorization before another attempt. The user later explicitly removed that rule;
no authorization remains pending for the completed runs above. Historical remaining
gates were successful impaired off/on calibration,
stage/overhead validation, profile calibration, the complete100×240-second plateau,
resource/fairness/bottleneck measurements, real-PC screenshots and verified cleanup.
No capacity or bottleneck ranking is claimed. No integration tests, client updater
publication or GitHub publishing occurred.

## Historical October6 preparation status

Both pinned PC backup SSH routes returned `SSH_ROUTE_UNAVAILABLE` during repeated
read-only checks, including 22:21 UTC. The question about bringing the PC/client
online remains unanswered. The plan requires fresh proof of both backup routes
before the Testing restart and load. No candidate deployment, restart, test-token
registration, impairment, calibration or 100-client plateau occurred. The
600-second live clock has not started. This document does not claim measured
server capacity, runtime compatibility, rendering correctness or a bottleneck.

## Implemented

- Debug Cargo feature `debug-diagnostics` owns 51 fixed timing stages, nanosecond
  totals, published histogram bounds, bytes/work units, failure/cancellation
  counts and active ages. Synchronous thread CPU is measured on the executing
  thread; async wall time is separate. Blocking executor queue, execution and
  resumption are distinct. Shared builds are measured at their owner, not once
  per consumer. Per-session/lane telemetry includes request identities, queue
  ages, Quinn counters and route IP-accounted traffic.
- Timing probes default off. Owned debug IPC switches on/off while keeping
  verbose logging equally quiet. Cleanup confirms reporting/probes restored off.
  Timing switches affect newly started spans; older spans can finish afterward.
  Global active age is explicitly a continuous-busy-epoch upper bound; per-lane
  phase ages are exact elapsed times. Nested/parallel stages are not additive.
- Separate normal/debug native outputs and exact JAR embedding. Normal artifacts
  exclude diagnostic markers and `LivePressureRoutes` classes. The shared bridge
  keeps one stdin writer; empty extension batches avoid new per-drain allocations.
- Console-only debug route ownership registers 100 unique tokens through the Java
  bridge, awaits actual native readiness and stores secrets in a mode-0600 file.
  Native status/removal responses identify run, request, native epoch, actual PID
  and executable SHA-256. Removal observes actual routes, sessions, subscriptions,
  routed sockets and conservatively tracked source work. Unknown/shared work is
  reported explicitly; launcher exit alone does not prove native exit.
- Independent Java expiry removes replay ownership even if observation/disk work
  stalls. Native removal and diagnostic reset acknowledgments are required before
  `CLOSED`. Durable timing acknowledgments survive periodic status updates.
  Debug status also records actual JVM heap init/max/used/committed and GC counts
  and times, avoiding attach tools or configuration-only heap assumptions.
- Rust runner owns 100 independent QUIC endpoints/protocol states/caches in one
  runtime. It authenticates only to Voxy, uses current framing, pinned TLS and
  catalogue/binding/CRC/payload/structure validation, and generates no Minecraft
  player entities. A saved-region heap selects a lazy spatial hierarchy, avoiding
  empty-border scans; skipped keys yield cooperatively. Disk/decode work uses
  reusable blocking-worker scratch rather than retaining payloads in RAM.
- Setup cannot enter plateau cohorts. All clients must validate both section
  lanes before the shared 240-second plateau. Each phase requires new missing
  DATA/EMPTY completions, and sampled plateau demand must remain nonempty.
  Cold, movement and deterministic warm HAVE/REUSE phases continue missing demand.
  Disconnects, integrity errors and cleanup failures propagate. Unchanged refresh
  interests are intentionally silent, so warm delivery uses normal coverage/detail
  requests with HAVE. Exact retired tickets handle legitimate trailing records
  without stale cache overwrites.
- Opaque per-client UDP relays independently apply seeded 10% loss each direction,
  symmetric added delay and full-duplex 1,000 kbps IP/UDP ceilings. Routing bytes
  are counted once, lost datagrams consume capacity, FIFO service is O(1), and
  late service rebases rather than bursting. Scheduled service, actual sends,
  send waits/lateness, drops, pending/abandoned traffic and rate violations are
  separate. Technical runner checks never establish project-level acceptance.
- External finite operator uses the pinned toolkit RunStore and declares owned
  operations before RCON/spawn submission. It refuses unknown replay, requires
  fresh PC routes and actual candidate/limit identities, and gates 100-client
  admission on current-epoch timing/profile/overhead evidence. Independent process
  supervision survives operator loss. Route expiry/new pressure stop at T+420;
  close/drain and final owned-process kill use the original T+600 deadline. These
  are two boundaries of one clock, preserving its 180-second restoration reserve.
  Resource observations include native cgroup/events/CPU/I/O/PSI, Java, generator,
  host memory and actual owned UDP socket inode drop counters.

`tools/LIVE_PRESSURE.md` documents current inputs, commands, profile and evidence.
No production scheduler, transport settings, renderer, terrain-refresh cadence,
world generation or service budgets were changed.

## Validation performed

All build/check logs and hashed receipts live under:
`/home/aerosmp/Desktop/Codex_Tools/Voxy_Workflow_Tooling/tasks/server-pressure-20261006/`.

| Gate | Outcome / evidence |
| --- | --- |
| Normal/debug Rust compile checks | PASS, `rust-normal-check1.json`, `rust-debug-check1.json` |
| Final offline normal/debug build, Java ownership checks and native packaging gates | PASS, `all-gates4.json` / `.log`; `all-gates3` also passed before the Java-only heap telemetry addition |
| Built timer helpers: histogram edges, completion/failure/cancellation ownership, thread CPU and disabled probes | PASS, `debug-self-check.json` / `.log` |
| Built relay/frontier/cohort arithmetic | PASS, `relay-self-check.json` / `.log`; no sockets/network started |
| Stable final build inputs, including untracked build inputs | Identical before/after: `release-inputs-before.json`, `release-inputs-after.json`; fingerprint `97b4e79d4df91575fe3bcddd01b732544d37cee0c4c080e27b43f3bf4d24c808` |
| Native embedding and normal/debug exclusion | PASS, `release-artifacts.json` and repository `build/reports/server-diagnostics-artifacts.json` |
| External finite operator syntax and CLI contract | PASS, `external-source-gates.json`; help parsing performed no live action |
| Independent source review | Corrected setup-counting, idle-demand, deadline/drain, sparse frontier, live closure, warm trailing-record, timing-ack and expiry/guard failure defects before final release gates |
| Live stages, paired on/off overhead, unloaded RTT/loss calibration | NOT RUN |
| 100 authenticated sessions for 240 seconds / useful throughput / fairness | NOT RUN |
| Real-PC rendering/screenshots and two current backup routes | UNAVAILABLE; no rendering claims |

No integration tests were added or run. Pure socket-free ownership/arithmetic
checks are explicitly allowed by the selected plan. The first Gradle attempt
failed because the sandbox could not determine a usable local lock-socket address;
the host-authorized offline build passed. Earlier build receipts are preserved;
only final stable-input artifacts are the release candidate.

## Intended and currently loaded artifacts

| Artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| Normal server 269 | 1,709,961 | `e43e746eedd41bb9144b3f9f2747d8d149941772d29649e3376cea8f4453940a` |
| Debug server 269 | 1,858,899 | `3d22eb1bb9014ef2c6624c2423568a83451719dee6210bc279f1204c2e29ec9d` |
| Normal native | 3,474,952 | `1604d2b688903c0761fedce8788c27b450f0896ff64fe3ad748e0abf5dc120fd` |
| Debug native | 3,621,024 | `51d0d065a20dd0c68dfb236577b134dfae16d6755da16660b0d55789346f0d0f` |
| Rust pressure runner | 2,629,768 | `6595b79a7149cab7757084812d044ce24f62446c0c97cddad04f985d49a40420` |

Server JARs are in `server/build/libs/`. The runner is
`rust-server/target/normal/release/live_pressure`. Standalone natives are in
`rust-server/target/{normal,debug-diagnostics}/release/`.

Pinned MCP captured the final debug server/native as
`candidate-9e241b8219ef4de88ad1cecc64d0f806`, under external toolkit
`state/candidates/`. Its exact embedding/hashes match the table. An earlier
candidate predating the Java heap/GC addition is superseded and must not be used.

At 22:15 UTC, Testing still loaded debug server **267**, SHA-256
`9900d5273f09304714d481f4288666b26c43da7501807f39c18a98899efbf383`,
with matching native `e5dad2dd36b4dfcc6b6c9b9ae54d090e8d351a033980f026327cdaf6c1bb8d7f`.
Java PID/start remained `2289177/51522105`; native remained `2290032/51523350`.
Existing cgroup `memory.max=999997440`, `memory.swap.max=0` and event counts
remained unchanged: max 53, OOM/kill/group-kill 0. JVM argument file retains
`-Xms1G -Xmx4G`; actual heap telemetry will be checked after candidate deployment.
Native current memory was 143,163,392 bytes during this idle preflight, not a
pressure measurement. `unchanged-server-evidence.json` verifies process/artifact,
configuration and other-mod identities against the initial receipt.

The intended pairing retains compatible PC debug **268** while deploying only
debug controller/native 269. PC 268 is historical last-session evidence, not a
fresh loaded identity. The public terrain protocol was not changed. No PC JAR,
updater feed or GitHub commit was published. Main, original Voxy and Restart
received no edits, deployment or process-control actions.

## Prepared workload, limitations and remaining live gates

Read-only Anvil headers and current border data supplied 100 distinct populated
anchors across 400 eligible saved overworld regions, within the verified square
centered at (0,0), radius 5,000. `locations.tsv`, `saved_regions.tsv` and
`saved-inputs.json` preserve locations/header/certificate hashes. Refresh these
inputs and runtime identities before the eventual run; normal world saves can
change file hashes. Existing terrain only is used.

The external operator/configuration lives beside the receipts, outside the sealed
toolkit release. Its cleanup proposal covers owned fake load only; root must still
verify PC recovery/cache/settings/helpers and unaffected Main before closing the
cooperative lease. Kernel-drop snapshots prove only their observed socket lifetime;
the socket-close/drain interval after the last sample remains unverified and must
be reported rather than called zero drops. Native source-work tracking observes
run-subscribed coordinates conservatively, not causal ownership of every queued job.

Next required steps are fresh reachable PC/both-route proof, hash-addressed Testing
rollback/deployment, actual loaded identity and heap/cgroup verification, paired
one-client timing/profile checks, and one non-resettable 600-second live experiment
with the full 240-second 100-client plateau and independently verified cleanup.
No latency/FPS/goodput target will be invented afterward. Populate the plan's
per-client, stage, resource and bottleneck tables from that live evidence. No
bottleneck ranking is possible from offline arithmetic/build results.
