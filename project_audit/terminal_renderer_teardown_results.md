# Terminal renderer teardown results

Implemented and deployed: debug client **.202**, compatible server **.198**. Production teardown
is commit `00f5eb0e` (.201); `46bcf6ca` adds only a debug shutdown census and the .202 version bump.
Baseline source: `1b888899`. Required offline gates pass. Live teardown, cache rejoin and
compatible reload checks pass; the failed runs and incomplete five-large-scene repetition
requirement are reported below. This is not an all-clear for unrelated rendering/network bugs.

## Ownership and implementation

Full renderer destruction, including the earlier NeoForge LoggingOut callback, publishes
STOPPING before detaching/closing the session. Node stop initiation is nonblocking and distinct
from its final render-thread disposal guard. Active publications take the existing terminal
resolution path instead of submitting ordinary retirements. Normal live retirement, transport
reconnect, shader-compatible reload, formats, caches and capacity limits are unchanged.

| Owner | Completion boundary before dependent disposal |
| --- | --- |
| Session owner | Detached under LIFECYCLE, joined outside that monitor |
| Section/cache/decode/mesh workers | All close requests issued, then actual thread exits joined; CLOSED alone is insufficient |
| Metadata worker | Independently closed; not joined because it owns no renderer/model/native mesh resources |
| Model processor | Stops between one biome/model job and the next; joined before scratch/model/atlas disposal |
| Hierarchy worker | Completes its current mutation, skips queued obsolete mutations and packing after observing stop, then exits |
| Publications/results | Terminal callback ownership drained once; cached uploaded results do not redeliver callbacks owned by GPU completions |
| GPU/readback stream | Existing terminal GL completion and fixed-point readback drain precede GL deletion/reuse; callback failures do not skip other callbacks |

Joins retain interruption without treating it as worker exit. A failed completion barrier
cannot fall through to dependent disposal. Independent cleanup exceptions are collected and
reported after the remaining owners are released. Native/GL acquisitions in the async manager
are guarded for partial construction; model factory acquisition/start failure cleans its
acquired owners. Constructor failures preserve their original error.

No new worker, registry, timeout, retirement index or arbitrary batching limit was introduced.
Debug-only logs identify shutdown phases and queued terminal work. The normal facade is a no-op.

## Offline evidence

Evidence directory: `project_audit/terminal_renderer_teardown_evidence/` (local, ignored).
All executable tests are registered in the existing scheduler runner.

- Durable production NodeManager baseline: 1k/2k/4k/8k accumulated ordinary retirements visit
  499,500 / 1,999,000 / 7,998,000 / 31,996,000 pending entries respectively. That live-retirement
  implementation is intentionally unchanged; terminal destruction no longer exercises it.
- Real RegionalSectionPublication terminal routing: 1k/2k/4k/8k/100k fixtures, two warmups and
  five measured repetitions, zero ordinary retirement submissions, one preserved outcome per
  publication, repeated close/stop and stop-before-start do not resurrect ownership.
- Actual AsyncNodeManager consumer: stop during one retirement with 7,999 queued behind it;
  exactly one NodeManager mutation completes, all 8,000 callbacks resolve once, no GPU result
  packing begins for the discarded backlog.
- Actual Session/WorkerSlot/cache/mesher: stop while a model lookup is held; CLOSED is observed
  while the operation is still live, release waits for it, its late native result is discarded,
  and the session then releases its publications/cache.
- Interrupted join/late completion, throwing terminal callbacks, throwing worker disposer,
  pending/assembling/cached native results and fake GPU fence ownership are covered.
- Existing scheduler, worker debug, Java/Python harness, shader, publication repair/topology,
  WARM/COLD, cache-startup and endpoint regressions pass. `buildAll` and
  `verifyDebugHarnessArtifacts` pass. No server artifact is deployed by these build gates.

`gates-1.log` preserves an initial test-fixture compilation failure (package-private NodeManager
methods); the package-access bridge fixed the fixture without widening production APIs.
Subsequent gates passed. The test uses controlled GL boundaries, not a real driver context;
constructor failures at every internal OpenGL allocation are not exhaustively fault-injected.
These limitations are not counted as real-GL passes.

Warmed candidate publication-loop work is linear; raw logs record wall/CPU/allocation counts.
In `gates-2.log`, 100k terminal publications take approximately 7–9 ms after warmup. This is
NOT whole-renderer teardown latency and is not directly comparable with the historical
71.187-second in-game pause. The baseline count probe is not a same-GL live benchmark.

## Live deployment and identities

Baseline checkpoint `8da751e7-3431-4e46-aed7-f1b79e915857` passed on runtime .200, preserving
the player's current pose. It reports 58,617 active sections and epoch 0, drawing from cache.
The earlier server OOM/backend-supervisor failure initially blocked normal online Voxy service.
The unchanged large-scene test failed at 35,102 active sections with epoch 0. This failure is
preserved as `ef68b3e9-6049-4887-8086-62f302f8bcb0`, not relabeled as a streaming pass.

With only designated MGengine connected, Creative was gracefully stopped at 10:14 UTC. All
dimensions saved and PID 1678818 exited before restarting through the existing `run.sh`.
No server jar/config/world/cache change was made. New JVM PID 1910629 and its supervised Rust
PID 1911597 recovered normal service. Exact identities:

- Server .198 JAR: `c3b902d448d6aa3267ececf1c938ad2ab411bc2a6254c6794c7c2e6b8927417d`.
- Actual recovered `/proc/1911597/exe`: `2c3c11158b507421cb9b45c60acca0c7aa5c181d6f1e8591b5152d5ce56d1dca`, identical to the original backend.
- Client .201 JAR: `158349cd08d931b2c6f2055b1e94a319c61a06a97a48f0cae4601560bd4787ae`.
- Client .202 JAR: `e2908b18cf46f6d647f97d69fa547beed6014a21198eaa6c719a7abfc1a3c28d`.

CLIENT_READY signed 64-bit identity words reconstruct the exact .201 and .202 hashes using
integer arithmetic, not floating-point JSON conversion. Updater publication was atomic and
only occurred after isolated build gates. Required gates passed again for the census build
(`census-gates.log`); committed artifact verification is in `census-committed-build.log`.

The helper automatically replaced .200 PID 20588 with .201 PID 1532 at 10:10 UTC, which joined
at 10:10:40.989. The candidate itself then completed whole-game exit/update: helper start
10:24:40.340725, old PID 1532 stopped 10:24:43.422715500, .202 PID 18216 started
10:24:43.960812400, server login 10:25:22.786. No forced-kill timeout occurred. These process
timestamps are not renderer-phase timings. The preexisting in-use launch-javaagent cleanup
warning remains separate from successful restart/login.

## Live teardown and retained behavior

Monotonic durations below start at the earliest renderer STOPPING marker and finish at
resource disposal. They include the interval between logout notification and LevelRenderer
cleanup; sums of named worker/GPU phases can therefore be smaller. Counts are the last pipeline
sample before teardown, not an invented atomic count at the exact stop instruction.

| Client / renderer | Last active sections | Cause | Stop marker through disposal |
| --- | ---: | --- | ---: |
| .201 / 1 | 33,060 | Full Minecraft kick | 48.543 ms |
| .201 / 6 | 96,109, at 2,147,482,624 B limit | Incompatible shader full replacement | 82.081 ms |
| .201 / 8 | 41,138 | Full Minecraft kick | 48.603 ms |
| .202 / 1 | 60,851 | Full Minecraft kick | 136.609 ms |
| .202 / 2 | 1,418 | Secondary login/disconnect | 49.762 ms |
| .202 / 3 | 59,305 | Full disconnect with QUIC held | 55.475 ms |

For the 96k-section case, regional worker quiescence took 42.603 ms, hierarchy join 0.024 ms,
model join 0.018 ms, GPU/readback drain 36.911 ms, hierarchy disposal 0.147 ms, and model disposal
0.120 ms. This is real context-owning client teardown, not the headless loop benchmark. It
does not establish TTFD or prove that all of the historical 71-second pause had one cause.

No shutdown phase reported FAILED, no mass retirement burst appeared in terminal queue
summaries, and no Voxy cleanup exception was observed in these captured runs. .202's first
two terminal censuses report owner/section/metadata/hierarchy/model worker counts all zero.
The held-transport shutdown and its subsequent secondary disconnect also report all five
counts zero (four terminal censuses total).
Heap-used samples were 4,896,849,920 and 3,695,181,824 B; direct-buffer samples 24,682,068 and
35,894,903 B. These are whole-JVM observations, not proof that all native/driver memory is
leak-free. They are insufficient for a controlled long-run leak distribution.

Full disconnect intentionally invokes existing `VoxyClient.sessionEnd()` resource-cache
clearing, so subsequent new buffer allocations across game sessions are expected. Renderer-only
replacement retains the existing reuse path after genuine GPU/worker quiescence; compatible
reload keeps the same renderer rather than disposing/reallocating its terrain.

Live runs (raw events, results and screenshots under Creative `logs/voxy-tests/<id>/`):

- `43f1c66f-92c6-43c1-9152-a801ffe47780`: runtime .201 checkpoint PASS; cache draws confirmed.
- `b95e4131-b39d-4f09-a81d-ea476f375e38`: unchanged 50k-section scenario PASS after server recovery;
  online epoch 1 and zero missing coverage were observed while refinement continued.
- `c7ca5893-61b3-4c5a-97fd-3c27794ea645`: shader-enable variants **FAIL / RENDERER_REPLACED**.
  The unchanged compatibility check reported `shader material mapping splits shared model 0`
  (air versus chest material 10030). Full replacement completed safely. Harness aftercare
  restored shaders off. Do not claim shader enabling always preserves geometry.
- `0d63758e-11fc-4998-8a31-6ff37483cc0d`: compatible Iris reload/allChanged preservation **PASS**,
  unchanged renderer/session identity and resumed draws. The configured starting state was
  shaders off; this is not proof of every enabled shader-pack variant.
- `34f98ec8-f4cf-47c3-95dd-e6230024419f`: pre-repeat large-scene check PASS on .201.
- `ef5b0e79-522c-4af0-b37b-89cff5f104af`: repeat harness **TIMEOUT** after a secondary Minecraft
  reconnect occurred between its first and second checkpoints. Both corresponding renderer
  teardowns completed; the timeout is preserved, not counted as a passed full cycle.
- `dcd81a93-b858-4fc9-a4f7-cda25aae5c50`: runtime .202 large-scene precheck **PASS**, maximum
  checkpoint 62,213 active, 76,998 cache hits, epoch 1, zero missing coverage, actual draws.
- `d5c70f74-bb4c-43cf-bfda-116795924a5e`: subsequent 50k check **FAIL**, ending at 39,838.
  Camera/player location changed to ground-level terrain during this sequence; one settled
  sample required only about 19k sections. No assertion was lowered or player teleported back.
  Five comparable large-scene cycles were consequently **not completed**. This is a validation
  limitation, not evidence of a teardown stall, and not a five-cycle performance claim.
- `5224303d-849f-4724-9812-5cdb944459ac`: hold QUIC while preserving visible geometry **PASS**.
- `f74c0cc1-2cce-4213-838d-acfd52b99479`: after a full disconnect with transport held, cached
  terrain drew at epoch 0 (3,862+ active, 3,067+ draws), then resume advanced epoch to 1 with
  renderer/session identity 5 unchanged and continued draws: **PASS**. Transport was restored.
- `be53c3be-8ac6-4abb-80a4-32270846c7aa`: final QUIC-only continuity **PASS**, epoch 1 to 2,
  renderer/session 5 unchanged, zero missing coverage, active 40,340 to 42,207 and final
  11,954 GPU draws. The actual screenshot was inspected and shows terrain and water rendering.
- `0eff4527-a53d-4b28-b0ea-fe219326f723`: compatible reload/allChanged **PASS on .202 with
  Photon enabled** (`photon_v1.3b.zip`, verified IrisVoxyRenderPipeline). This final enabled-pack
  check supplements, rather than replaces, the earlier off-state pass and failed enable test.

The final client remains on the exact .202 hash above, connected to the verified .198 server,
with QUIC enabled and its current Photon setting preserved. Backend JVM/Rust liveness and
the executable hash were rechecked after live tests. No periodic screenshots were enabled.

Extra vanilla/Netty connection resets and duplicate logins occurred during testing; shader
material incompatibility and prior server OOM recovery are outside this client lifecycle
implementation. They must not be hidden by calling all modpack behavior healthy.

## Remaining limits and handoff

No standalone cross-driver GL fault-injection suite was available. The real client supplied
actual GL teardown/recreation evidence, but exhaustive constructor-allocation failures and
five matched large-scene memory samples remain unverified. No safety ceiling, assertion,
worker timeout, protocol or on-disk format was relaxed to obtain the reported passes.

The earlier tool-level rejection of pushing to the GitHub destination remains unresolved;
local implementation/census commits were made, but no Git push was retried or bypassed.

No manual world/cache reset or regeneration was performed; normal saves and cache I/O continued.
The unrelated `.gitignore`
review-prompt change and `fix_response` are outside this implementation.
