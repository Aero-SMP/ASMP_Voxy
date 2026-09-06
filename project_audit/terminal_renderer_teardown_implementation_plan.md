# Terminal renderer teardown without mass section retirement

Status: implemented and deployed as debug .202, 2026-09-06; scoped verification results and
remaining validation limits are in `terminal_renderer_teardown_results.md`. Originally reviewed
through `b58b1714`; implementation baseline was `1b888899`, preserving the separately completed
cache-key/neighbor/metadata simplifications.

## Goal and scope

When an entire renderer is being destroyed, terminate its ownership safely instead of
simulating thousands of ordinary section evictions. Eliminate the reproduced quadratic
retirement burst and unnecessary GPU update preparation during shutdown.

Use the existing publication terminal state, worker ownership and renderer cleanup paths.
Do not introduce a retirement index, another worker, timed retirement batches, a shutdown
service, or an alternate rendering architecture. Normal live-renderer eviction, WARM/COLD
retention, publication fences and recoverable QUIC reconnection must remain unchanged.

This is a client-only lifecycle change. No cache/wire format change, server algorithm change,
world regeneration, cache deletion or lowered safety limit. Source files may gain a small
number of lifecycle checks; the simplification is removing unnecessary teardown work and
making resource ownership explicit, not promising a large source/binary reduction.

Writing this plan does not implement, deploy, restart, commit or push anything. When the
plan is executed, scoped implementation/debug deployment/testing follows repository standing
approval and tool permissions. Coordinate disruptive tests with the designated player.
Preserve unrelated edits, worlds, catalogs, identities, configuration and normal client caches.
Do not push merely because this planning document was created.

## Evidence and limits

Saved `.198` client, from
`project_audit/cache_inventory_mesher_anvil_evidence/disconnect-stall-client.log`:

| Event | Client-local timestamp |
| --- | --- |
| Renderer cleanup begins | 11:49:29.702 |
| Renderer cleanup completes | 11:50:40.889 |
| Reconnection starts | 11:50:40.927 |

Renderer cleanup occupied 71.187 seconds. About 94,183 sections were active immediately
before disconnect. Async Node Manager scatter-buffer expansion messages appear immediately
before cleanup finally completes. The code at issue was unchanged in the `.199` republish.

An isolated probe exercised real NodeManager retirements without intermediate finalization:

| Retirements | Pending-entry visits |
| ---: | ---: |
| 1,000 | 499,500 |
| 2,000 | 1,999,000 |
| 4,000 | 7,998,000 |
| 8,000 | 31,996,000 |

This establishes N(N-1)/2 scan work for that path, not that every live shutdown performs
exactly that many visits. No contemporaneous thread dump proves the entire recorded pause
was spent there. Other waits must be measured separately, not silently attributed to it.
The temporary probe was `/tmp/voxy-disconnect-audit.TnjjKK/RetirementProbe.java`; inspect it
if still available, but durable tests must not depend on a temporary file surviving.

## 1. Establish the ownership boundary

Read the current production paths before editing:

- `MixinLevelRenderer.voxy$shutdownRenderer()` currently notifies ClientLodClient before
  calling `VoxyRenderSystem.shutdown()`.
- `ClientSession.Session.close()` stops/signals the owner but does not join it. Its asynchronous
  `release()` closes current/previous publications and worker resources.
- `SectionPublicationState.close()` claims normal retirement after activation;
  `rendererStopped()` supplies the existing terminal resolution behavior.
- `VoxyRenderSystem.RegionalSectionPublication.requestRetirement()` already checks
  `AsyncNodeManager.isStopping()` and routes to `rendererStopped()` when true.
- `AsyncNodeManager.stop()` currently sets stopping/running, joins, drains callbacks and frees
  resources in one method. Its early `if (stopping) return` cannot survive an earlier stop marker
  unchanged: that would skip all final cleanup.
- `AsyncNodeManager.run()` checks running at entry but processes its captured transaction
  count without checking between operations. A stop can wait for the whole obsolete batch.
- `VoxyRenderSystem.releaseComponents()` also serves constructor failure and currently catches
  cleanup failures before proceeding to later resource releases.

Document which resource each participant can still touch until it acknowledges termination:
session owner, section/metadata workers, model baker, hierarchy worker, outstanding callbacks,
render-thread upload/readback queues, and GPU commands. Verify actual WorkerResource close
semantics; requesting close is not equivalent to the running operation having returned.

## 2. Separate stop initiation from final disposal

Keep this as a small, renderer-owned lifecycle with existing flags where possible:

```text
RUNNING -> STOPPING -> DISPOSED
```

Conceptual operations, with names chosen to fit the current code:

1. A nonblocking, idempotent `beginStopping()` on the existing node manager.
2. The existing final `stop()`/dispose operation, which invokes beginStopping if necessary,
   waits for ownership to end, then drains and disposes exactly once.

Requirements:

- Publish STOPPING under the existing submission lock and reject later normal publications,
  retirements, coarsenings and finalization/rollback submissions with terminal resolution.
  Stop the batch handoff and wake a parked/sync-waiting worker using existing mechanisms.
- Invoke this boundary before the first renderer-destruction notification can close session
  publications. Route all full-destruction entry points through it, including setLevel,
  close, disable/recreate and constructor-partial cleanup where applicable.
- Inspect alternate logout hooks: the earliest session-close path during full renderer
  destruction must see STOPPING. Do not assume the mixin is always the first callback.
- Marking stopping must not free, null or recycle geometry/model resources that workers
  still use. Retain references until their final disposal step.
- Final disposal must still run after an earlier beginStopping call. Use a distinct disposal
  guard if needed; do not overload stopping to mean resources have already been freed.
- Do not create a global lifecycle registry, second publication list or shutdown thread.
- Recoverable transport failure is NOT terminal renderer shutdown. A session-only failure
  with a continuing renderer must retain whatever normal retirement semantics it requires.
- Compatible shader reload must continue preserving terrain. Only reload paths already
  requiring full renderer replacement take this terminal route.

## 3. Stop obsolete work at safe boundaries

In AsyncNodeManager, finish an operation already mutating hierarchy/allocation state, then
check stopping before beginning another normal operation. Audit transaction, coarsening,
regional publication and top-level update processing, not just the main loop condition.

- A captured queue length must not force processing the entire backlog after stopping.
- Do not abruptly interrupt a NodeManager mutation, use Thread.stop(), or abandon a partially
  staged allocation without preserving its existing cleanup owner.
- Any dequeued item must remain reachable by its normal completion/terminal cleanup path.
  Do not return early after removing an item and lose its callback or geometry.
- Once stop is observed, do not build additional scatter/copy buffers merely to represent
  terrain changes for the dead renderer. Existing completed/unpublished work must remain
  reachable for disposal even when result packing is skipped.
- Stop progress notifications must not reschedule gameplay work or form callback recursion
  that creates new retirement/rollback transactions.
- Keep the normal live-renderer processing path and its batching/capacity limits unchanged.

The target is O(P + Q) terminal resolution for P owned publications and Q outstanding work
items, plus necessary resource cleanup/current-operation/GPU completion cost. It is not O(1)
whole-renderer destruction. No promise of an absolute wall-clock bound on driver or disk calls.

## 4. Quiesce producers and resolve ownership exactly once

- Stop admissions/producers and detach the active session at the terminal boundary. Resolve
  network handoffs so no receiver is left waiting for a transfer acknowledgement.
- Establish a real completion boundary for renderer/model-using workers before freeing their
  dependencies. Reuse existing ownership primitives; do not interpret close-requested as exited.
- If waiting for session/worker completion is necessary, do not hold LIFECYCLE, submission,
  publication or resource monitors needed by those threads. Do not join while they need a
  render-thread callback that shutdown has stopped servicing. Resolve that dependency first.
- Metadata persistence need not become a new render-thread wait merely for convenience if
  it owns no renderer resources; preserve its existing independent cleanup where safe.
- Current and previous active publications take terminal resolution rather than ordinary
  per-section retirement once the renderer is stopping. Preserve outcome-once semantics:
  ACTIVATED outcomes already delivered must not be rewritten, and pending outcomes must not
  be reported as newly activated just because shutdown finished.
- Returned meshes, staged uploads, active batches, handoff items, completed transactions,
  GPU-completion records and all cached SyncResults must each retain exactly one disposer.
- An admission acknowledgement must never release a reused worker lease. Late completion,
  cancellation, close and abandon calls must be harmless to a successor renderer/session.
- Retain the existing prohibition on callbacks/disposal under publication monitors where it
  prevents lock inversion. Dispose all independent items even if one callback throws, while
  preserving/reporting the failure; do not suppress errors to claim clean shutdown.

## 5. Preserve GPU and model-resource lifetimes

Joining the hierarchy worker only proves CPU quiescence; deleting a GL fence does not prove
GPU completion. Before freeing or returning buffers/textures for reuse, verify the existing
GL-context ordering and upload/readback ownership satisfy their actual lifetime requirements.

- Keep GL operations on the render/context-owning thread.
- Drain/cancel readback callbacks safely so they cannot recreate work after STOPPING.
- Preserve required GPU synchronization. Do not add a glFinish per section or replace
  correct resource ordering with a timeout. If one terminal synchronization is necessary,
  justify it against the existing flush/fence/context guarantees and measure it separately.
- Never continue freeing dependent resources merely because joining a worker was interrupted
  or threw. Handle that failure without allowing access to freed memory; report incomplete
  teardown honestly. Do not shorten joins to conceal slow work.
- Audit model-baker termination: its inner work loop must not be mistaken for quiescent
  solely because isRunning was cleared. Only make a scoped lifecycle correction if needed
  for safe terminal disposal; do not redesign model generation under this plan.
- Preserve geometry-buffer/model-atlas reuse after genuine quiescence and existing capacity
  checks. Do not clear/delete the disk cache or rebuild the terrain as part of shutdown.

## 6. Extensive production-behavior regression tests

Extend existing `PublicationShutdownBehaviorTest`, `PublicationTopologyBehaviorTest`,
`PublicationRepairBehaviorTest`, `RendererAdmissionBehaviorTest`,
`DebugSnapshotShutdownBehaviorTest` and scheduler lifecycle fixtures as appropriate.
All tests must execute through existing test runners. Use deterministic barriers/latches,
not sleeps intended to hit races. Timeout failures must retain thread/ownership diagnostics.

### Reproduce and eliminate the retirement burst

- Preserve a small baseline production-NodeManager reproduction showing N(N-1)/2 visits
  when normal retirements accumulate. Instrument collection visits in tests, not production.
- Exercise real terminal routing with 1,000, 2,000, 4,000, 8,000 and a large roughly 100,000
  publication fixture. Use real production lifecycle/submission logic with only GL/storage
  boundaries controlled; calling rendererStopped manually in a loop is insufficient.
- After STOPPING, closing active current/previous publications enqueues zero new ordinary
  retirements. Preexisting queued retirements are resolved without visiting pending-subtree
  maps for each cancelled item. Count normal retirement calls and cleanup deliveries.
- Stop a worker while one transaction is in progress and thousands remain. Release the
  in-progress operation; assert it completes safely and the rest do not enter NodeManager.
- Verify zero new post-stop GPU-update packing for discarded work, no queue growth and
  exactly-once resolution. Test linear cleanup work counts separately from noisy wall time.

### Lifecycle and race matrix

- Stop before start; idle/parked worker; render-sync wait; active transaction; coarsening;
  stage-before-commit; committed-before-upload; uploaded-before-fence; activated publication;
  previous fallback; returned mesh; empty geometry and child-only topology updates.
- Both orderings of submission versus beginStopping; beginStopping versus session release;
  close versus outcome delivery; abandon versus completion; admission versus cancellation;
  repeated beginStopping/final stop; late callbacks after a successor renderer exists.
- Constructor failure after each acquired component and before worker start. Dispose only
  initialized resources, preserve the original exception and suppress/report cleanup failures.
- Inject throwing callbacks and interrupted waits. No use-after-free, double free, stranded
  handoff, stale lease release, lock inversion or silently skipped final disposal.
- Count every owned native buffer, GL/test handle, worker lease and outcome delivery. Track
  order: dependent resource disposal happens only after all potential users relinquish it.
- Simulate workers completing cache reads/meshes/model requests after close was requested.
  Late results are discarded safely without touching freed renderer/model state.
- Normal live retirement still respects fences and revision identity. WARM/COLD eviction,
  allocation refusal, rollback and sibling activation behavior remain unchanged.
- QUIC-only reconnect retains renderer identity and active terrain. Compatible shader reload
  retains geometry; a genuinely incompatible reload performs one safe terminal replacement.

Do not introduce a second scheduler implementation as the test oracle. Reuse controlled
boundaries/reflection where needed, or a minimal production seam that also clarifies ownership.

## 7. Debug evidence and measurement

Reuse existing ClientLodDebug/debug-source-set facilities and live harness. Add only narrowly
scoped shutdown observations if existing logs cannot separate phases:

- Renderer/session identity and monotonic start/end times for stop initiation, producer
  quiescence, hierarchy join, callback/result disposal, model shutdown and GPU/resource release.
- Aggregate counts of cancelled queued items, terminal publications, remaining owned leases
  and ordinary retirement submissions after the stop boundary.
- Emit per-phase summaries, not per-section logs. Keep test-only counters out of normal
  artifacts; debug code must not retain old renderer/session graphs indefinitely.
- Record whether a phase completed or failed. Capture the next begin marker before a blocking
  operation so a stalled cleanup has a last-known phase even if end logging never executes.

The normal render-thread sampler cannot keep sampling while that thread is blocked. Do not
claim it captures shutdown stacks automatically. Use existing background diagnostics if they
support the required observation; otherwise use an available client-side thread dump/JFR
attachment during a stall. No new permanent sampling thread or unsupported remote command.
Keep optional attachment failures distinct from successful collection.

Baseline/candidate comparison: preserve exact source/build identities and identical fixtures.
Use warmed repeated headless runs and report work counts, CPU/wall time and allocation. For
large tests, report distributions; never gate correctness on one host-specific timing number.
Preserve failed runs and raw logs. A previously reproduced 71-second pause is context, not a
substitute for a controlled baseline run on the current loaded client.

## 8. Builds and offline gates

```text
./gradlew schedulerBehaviorTest
./gradlew workerDebugBehaviorTest debugHarnessJavaTest debugHarnessPythonTest
./gradlew buildAll verifyDebugHarnessArtifacts
git diff --check
```

Register every new test in an executable runner. Run existing shader shutdown, publication
repair, cache-startup, WARM/COLD and geometry-endpoint regressions. Use available real-GL
fixtures to verify disposal/reuse under a context; headless ownership tests alone do not
prove driver behavior. Record unsupported platforms/context tests as unavailable, not passed.
Verify profiler/test classes are absent from game JARs and debug hooks do not leak into normal
artifacts. Building server artifacts does not require deploying/restarting an unchanged server.

## 9. Scoped live deployment and disconnect/rejoin verification

Deploy the debug client through the existing workflow. Retain the current compatible debug
server unless required harness changes genuinely need a matching update. Verify actual loaded
client/server build identities, not just staged files/updater publication. State the intended
pairing explicitly; do not treat `.198`, `.199` or an independently updated client as candidate
evidence without verification.

Use the existing `tools/run_live_client_test.py`, actual render-camera pose acknowledgement,
checkpoints, traces, screenshots, zoom controls and scenario assertions for setup/aftercare.
Inspect current schema before use. Existing examples include `harness_smoke`,
`stable_view_loading`, `camera_turn_response`, `quick_return`, `ok_zoomer_zoom_cycle`,
`dormancy_pressure_look_away`, `shader_reload_preservation` and `reconnect_continuity`.

IMPORTANT: `reconnect_quic` does not destroy the renderer and cannot reproduce this defect.
The current scenario language does not provide a whole-game disconnect/launcher restart step.
Use the established authorized server kick/manual disconnect plus reconnect workflow outside
the scenario runner; collect independent timestamps across the resulting harness connection
loss. Do not add a generic remote shell/control framework to automate this one operation.

Test with the designated player only and preserve their cache:

1. Small settled scene: complete a full disconnect/rejoin cycle to validate basic ownership.
2. Large settled scene near the previous roughly 94,000-active-section case and configured
   geometry capacity: record active/pending counts, issue full disconnect, measure each phase,
   rejoin and confirm actual draws/cache reuse. Repeat at least five times when practical.
3. Disconnect during active cache/mesh/publication work and during zoom/refinement or
   WARM/COLD eviction. Assert no mass retirement burst, crash, persistent wait or missing cleanup.
4. Disconnect with Voxy transport held or reconnecting using existing harness controls;
   restore transport after the test. No receiver/handoff or connector should block teardown.
5. Recreate renderer through an already supported full-rebuild path; separately test a
   compatible shader reload to prove terrain retention was not accidentally replaced by shutdown.
6. Repeated full disconnect/rejoin and one whole-game exit: monitor surviving worker counts,
   leases, heap/native memory and reusable GPU allocations for monotonic leakage. Retained
   reusable allocations are not automatically leaks; distinguish intended reuse from extra copies.

Record kick/disconnect receipt, terminal marker, phase endpoints, vanilla cleanup completion,
connect start, server login and first rendered/cache checkpoint separately. Account for client
timezone/clock skew; use client monotonic phase durations for performance claims. Network/login
or launcher time is not renderer shutdown time, and first draw is not full-detail completion.

An unavailable client blocks live verification. A timeout is a failed run, not permission to
relax safety checks or kill a worker. Collect diagnostics and restore a usable client safely.
Do not repeatedly reproduce the expensive old shutdown on an unwilling/active player merely
for timing statistics. Fixed-camera screenshots and post-rejoin coverage supplement, rather
than replace, ownership assertions and actual in-game draw evidence.

## 10. Acceptance and handoff

- Terminal boundary precedes publication closure during full renderer destruction.
- No ordinary retirement burst after STOPPING; queued obsolete operations are terminally
  resolved at safe boundaries, and final disposal is never skipped by an early stop marker.
- Workers, callbacks and GPU resources obey exactly-once lifetime rules. No new timeouts,
  arbitrary backlog caps, global indexes or new threads substitute for correct ownership.
- Normal retirement, recoverable connection failure, cache reuse and compatible shader reload
  retain their previous behavior. No production cache/data reset or format migration.
- Headless scaling tests demonstrate removal of quadratic teardown work. Live phase logs
  establish actual shutdown duration and any remaining slow component; do not claim every
  disconnect cause is fixed because one asymptotic defect is removed.
- All required test/build outcomes and live outcomes are reported separately, with running
  build identities, failed runs, limitations and raw evidence locations.
- Write `project_audit/terminal_renderer_teardown_results.md`. Preserve diagnostic fixtures and
  logs in a task-specific evidence directory without committing player secrets or huge dumps.
- If unsafe behavior appears, revert only this implementation's changes or restore the prior
  verified client artifact; preserve unrelated work and caches. Record the failed test before
  repair/retest. No server/world rollback is inherently required for this client-only change.
