# Server timings and 100 Rust clients: implementation results

Updated: 2026-10-07. Branch: `feature/cache-first-background-updates`.
Selected plan: `server_timings_100_rust_clients_live_pressure_implementation_plan.md`,
SHA-256 `4b2fb89e6189df4026e7efe62a96405fe06a8bbb0157cbd5ed331ed44cebc025`.

**Implementation and offline gates: PASS. First live attempt: ABORTED / INCONCLUSIVE;
100-client plateau: NOT RUN.** Server269 is now deployed and its actual native
identity matches the debug embedding. PC268 was retained and confirmed through
both SSH routes and signed typed results. The October6 preparation status below
is historical; the October7 evidence in the next section supersedes it.

## October7 live attempt and cleanup

Run: `d617ed73-ce07-4fd8-b2ea-d63455863f34`. All owned evidence is under
`/home/aerosmp/Desktop/Codex_Tools/Voxy_Workflow_Tooling/tasks/server-pressure-20261006/runs/d617ed73-ce07-4fd8-b2ea-d63455863f34/`.
The immutable600-second clock closed after435.588seconds. No additional clock
has been activated. Two earlier preparation manifests were never activated;
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

The plan's one-clock rule requires new authorization before another live attempt;
this request is pending. Remaining gates are successful impaired off/on calibration,
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
