# Terminal renderer teardown results

Implementation: debug client .201, client-only; compatible server .198 is intentionally unchanged.
Baseline source: `1b888899`. Implementation and offline gates are complete; live validation is
in progress. This document does not yet claim full live acceptance.

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

## Live baseline and outstanding verification

Baseline checkpoint `8da751e7-3431-4e46-aed7-f1b79e915857` passed on runtime .200, preserving
the player's current pose. It reports 58,617 active sections and epoch 0, drawing from cache.
The earlier server OOM/backend-supervisor failure still blocks normal online Voxy service;
this is independent of terminal teardown and must not be reported as online streaming success.
The intended candidate pairing is client .201 with server .198, with exact running identities
and candidate full-disconnect outcomes to be appended after deployment.

No world/cache/configuration data is deleted or regenerated. The unrelated `.gitignore`
review-prompt change and `fix_response` are outside this implementation.
