# Repair Rust supervisor lifecycle and failure handling

Status: implemented 2026-09-06 in `053e5f60` and `ee63d038`; debug server `.204`.
See `rust_supervisor_failure_recovery_results.md` for offline, live and publication outcomes.

## Goal, scope and authorization

Keep the existing Java-owned Rust process model, but prevent logging failures and unexpected
supervisor termination from leaving Voxy permanently unavailable while its state says it is
running. Preserve single-process ownership, automatic recovery of recoverable failures and
shutdown with Minecraft. Make genuinely fatal failures observable and cleanup reliable.

This plan does NOT diagnose or fix the Java heap consumer, increase heap limits, switch to
external service management, change Rust storage/QUIC, relocate the executable, or add a
watchdog thread. Those are separate tasks. Do not combine this with a transport rewrite.

Writing this document does not implement, deploy, restart, commit or push anything. Execution
uses repository standing approval for scoped builds, debug deployment, restarts and tests,
subject to tool permissions. Coordinate live disruption with the designated player. Preserve
worlds, generated Voxy data, catalogs, certificates, configuration, client caches and unrelated
work. No regeneration or data deletion is needed. Only validated task-owned temporary files
may be removed. Do not push merely because this plan was written.

## 1. Evidence and current failure boundaries

Creative's recorded 2026-09-06 incident:

- 09:32:46.474 UTC: `OutOfMemoryError: Java heap space` on a Minecraft networking thread.
- 09:32:50.399: another Java heap failure during structure loading.
- 09:32:50.904: `Voxy Rust supervisor` reports `StackOverflowError` through recursively failing
  Log4j exception handling. Later checks found no supervisor thread, Rust process or UDP 25587
  listener. Client `.200` rendered cached terrain but received no endpoint or new terrain.
- Configured heap ceiling was 4 GiB, soft target 3 GiB. This does not establish what retained
  the heap or prove host-wide RAM exhaustion. Rust's exact exit status was not recorded.

Relevant source:

- `server/src/main/java/me/cortex/voxy/server/RustBackend.java`: static owner fields, start,
  extraction, supervision, termination, readiness and cleanup.
- `VoxyServer.java`: lifecycle events and readiness-gated endpoint advertisement.
- Normal/debug `ServerDebug.java` implementations: debug hooks also invoke logging and can fail.
- `server/src/lifecycleTest/.../ServerLifecycleBehaviorTest.java`: real NeoForge stop events
  with a controlled Process, currently only basic stop/crash cleanup coverage.

Current weaknesses to repair:

1. Rust output is logged before readiness parsing; logging failure can abort the control loop.
2. Retry/cleanup diagnostics can themselves throw, bypassing subsequent recovery steps.
3. Errors escape supervision without a terminal ownership/state finalizer.
4. `running` can remain true after the only supervisor exits; `start()` then returns immediately.
5. Start publishes a thread before starting it; stop clears its reference before confirming
   exit. Review concurrent start/stop carefully rather than assuming callbacks are serialized.
6. A timed join does not establish exit. Executable cleanup/restart must not run while an old
   supervisor could still launch/use it or a child remains owned.

No guarantee of recovery from an arbitrarily damaged JVM is possible. The acceptance target
is explicit, tested failure policy and ownership, not "catch every Throwable and continue."

## 2. Failure policy: decide before restructuring

Implement and document this small policy in the existing supervisor, not a generic framework:

| Failure | Required result |
| --- | --- |
| Normal unexpected child exit | Record known exit status, clear ready, retry while server still wants backend |
| Process launch/readiness/read IOException or RuntimeException | Resolve any owned child, then use existing delayed retry while wanted |
| RuntimeException or StackOverflowError confined to a logging/debug reporting call | Disable that failing diagnostic route for this supervisor lifetime; continue draining output and supervising |
| Actual OutOfMemoryError, other unrecoverable JVM Error, or unexpected Error outside the narrowly isolated logger | Best-effort ownership cleanup and terminal failed state; no automatic tight retry |
| Explicit Minecraft stop | Clear advertisement, stop/reap child, finish supervisor; never restart |
| Secondary failure while reporting/cleaning a primary failure | Do not re-enter logger recursively or forget live ownership; preserve primary failure evidence where possible |

StackOverflowError handling is intentionally narrow: a known failing diagnostic boundary is
not equivalent to arbitrary stack overflow anywhere in application logic. Do not swallow
all VirtualMachineErrors globally. An OutOfMemoryError thrown by logging is still heap
exhaustion and follows fatal policy, not routine logger degradation.

For recoverable failures retain the current one-second, stop-aware retry delay unless tests
demonstrate a required change. Never immediately spin on a persistent failure. No arbitrary
retry-count cap, heartbeat polling thread or automatic JVM restart is introduced.

Fatal failure must explicitly say manual recovery/server restart is required. Clearing stale
state makes a later authorized start possible after cleanup; it does not itself automatically
invoke start. Do not promise same-process fatal recovery if no caller/command exists.

## 3. Make logging nonessential to supervision

- Parse and validate readiness and maintain process state independently of successful logging.
  A failed `LOGGER.info` or debug hook must not discard a valid readiness record or exit status.
- Use one small guarded reporting boundary for supervisor-owned normal/debug diagnostics,
  including startup, readiness, child exit, retry, failure and executable cleanup messages.
- On a recoverable diagnostic failure, disable the failing log route once for that supervisor
  lifetime. Continue reading/discarding ordinary Rust output so its pipe cannot fill merely
  because Java no longer logs it. Do not retry the broken logger for every line.
- Retain the readiness parser even in degraded logging mode. Do not disable endpoint publication
  merely because presentation logging is unavailable.
- Provide a minimal best-effort fallback diagnostic that cannot route through Minecraft's
  redirected `System.err`/STDERR Log4j integration. A small preinitialized raw descriptor sink
  is one option; inspect runtime redirection before selecting it. Never close global stderr.
- Fatal-path output should use a fixed small message, without stack formatting, JSON, string
  concatenation, unbounded buffering or another logging dependency. Do not claim zero allocation
  or guaranteed output under actual exhaustion. Fallback failure must not trigger recursion.
- Retain ordinary detailed exceptions when the logger is healthy. In degraded mode retain only
  compact last-state/error classification where safe, not an unbounded exception history or
  strongly retained huge Throwable graph.
- Keep diagnostic calls outside lifecycle monitors where possible. A logger must not own the
  lifecycle lock while blocking or re-entering backend methods. This plan isolates exceptions;
  it does not guarantee recovery from a logger or OS write that blocks forever.

## 4. One owner, truthful state, unconditional finalization

Restructure the existing start/supervise/stop paths around a small ownership model. Reuse
current fields or a single supervisor-owned context if that deletes duplicated state; do not
maintain parallel process registries. Conceptual states are STARTING, READY, RETRYING,
STOPPING, STOPPED and FAILED; an enum is optional, not an instruction to add a framework.

- Distinguish "server requests backend" from "a supervisor actually exists/owns this child."
  A lone boolean is not proof of liveness. Readiness requires the current live owned child,
  valid ready record and a nonterminal supervisor lifecycle.
- Outer finalization runs for normal exit, recoverable-path escape, interruption and Errors.
  Clear readiness before terminal exit and settle running/supervisor references truthfully.
- Associate cleanup with its owning thread/context identity. Old finalizers/callbacks cannot
  clear or terminate a successor's child, ready record or executable reference.
- Never launch a replacement until the prior owned child has actually exited. If termination
  fails, retain the handle and report a failed-owned state; never clear it and start a duplicate.
- Keep destroy -> existing graceful wait -> force -> confirmed exit semantics. Resolve pipes
  and record exit status if available. Avoid double termination from racing stop and failure.
- Do not wait for child/thread exit while holding a monitor they need to finish. Address the
  current post-launch stop race that calls child termination inside the lifecycle lock.
- Serialize publication of a new supervisor, its start, and concurrent stop so an unpublished
  local child or not-yet-started thread cannot escape ownership. Cover thread-start failure too.
- `stop()` must be idempotent for normal shutdown, crash-only ServerStoppedEvent and JVM hook.
  It must not null the supervisor reference or delete the executable merely because a join
  deadline elapsed. Preserve interrupt status without allowing repeated immediate interruption
  to turn cleanup into a spin loop. Guard against joining the current thread.
- Startup rollback must handle extraction/diagnostic/thread-start failure without falsely
  claiming success. Keep existing extraction location behavior; no persistent-path migration.
- Best-effort cleanup under severe JVM failure must never claim successful disposal if it
  could not establish it. Independent cleanup should continue where safe despite a reporting
  failure. Do not allocate a large list of suppressed failures on a low-memory path.

## 5. Readiness and debug observability

- Preserve ALPN, certificate fingerprint, configured host/port overrides and strict readiness
  parsing. Identity/data must survive backend restarts; no certificate regeneration.
- Clear endpoint readiness immediately on owned-child exit or terminal supervisor failure.
  A new valid record can advertise only the matching replacement process.
- Existing `VOXY_RUST` debug events should report STARTING/READY/EXITED/RETRYING/FAILED/STOPPED
  as appropriate, restart intent, known child PID/exit status and failure category. Reuse
  existing facilities; do not add per-line/per-tick events or retain every transition in RAM.
- Guard ServerDebug calls exactly like other diagnostic calls. The normal no-op facade and
  debug replacement must have matching signatures and behavior outside observability.
- Provide a compact read-only status snapshot for tests/debug inspection if current fields
  cannot express terminal failure; do not add a generic admin shell/restart command to this plan.
- When the main logger is disabled, the fallback/status is the evidence. Do not require a
  successful Log4j event to prove a Log4j failure was handled.

## 6. Deterministic production regression tests

Extend `:server:serverLifecycleTest` and its existing test source set. Use production lifecycle,
readiness and supervisor code, not a second model of the supervisor. Introduce only minimal
package-private injection seams for process launch/reporting/wait coordination if necessary;
avoid new production factories/interfaces solely to support elaborate mocking.

Use latches/barriers and controlled Process implementations. Register every case with an
executable runner. Test timeouts diagnose hangs; never treat timeout as successful cleanup.

### Core failure reproductions

1. Valid readiness with logger throwing RuntimeException: child remains owned, readiness
   becomes usable, output continues draining, no duplicate launch; failing sink is called once
   before degradation and fallback is not recursively retried.
2. Same with injected StackOverflowError at the logging boundary and at each ServerDebug hook.
   Throw a constructed error; do not recursively exhaust the test JVM's stack for this case.
3. After degraded logging, child exits: actual exit status/state update and delayed replacement
   still happen. Replacement readiness works without resurrecting the broken logger.
4. Constructed OutOfMemoryError at launch, line reading, logging and debug hook: terminal policy,
   no retry loop, readiness withdrawn, owned child settled and no stale running flag. This tests
   control flow, not survivability of real memory exhaustion; state that limitation explicitly.
5. Unexpected Error outside logging: outer finalization occurs, no false READY, no silent retry.
6. Fallback sink also throws: no recursive reporting, no skipped state transition or duplicate
   process. Primary classification remains available if safe.

### Process and readiness matrix

- Normal exit zero/nonzero, before readiness and after readiness; launch IOException;
  malformed/duplicate readiness; invalid port/ALPN/hash; truncated output/read IOException.
- EOF while child is still alive; delayed exit; graceful termination; refusal of graceful
  termination followed by force; termination/wait failures with retained process ownership.
- Ready arriving concurrently with stop; old ready record after replacement attempt; no endpoint
  when stopped/failed/dead, and exact identity from the current ready child only.
- Large normal output after logging degradation must drain without buffering accumulation or
  restarting per line. Do not add an arbitrary new output limit as a shortcut.
- Validate retry delay using a controlled wait boundary, not elapsed-time sleeps; a persistent
  ordinary failure does not busy-spin and an explicit stop cancels further launches.

### Lifecycle race matrix

- Start twice, stop twice, stop before thread starts, stop between process launch and publication,
  stop during read, wait, retry delay, readiness announcement and logger failure.
- Concurrent stop and child exit; stale supervisor finalizer versus later authorized start;
  failed thread.start after ownership publication; constructor/extraction rollback.
- Error in reporting during cleanup; interrupted caller/supervisor; no self-join, lock inversion,
  orphan child, lost process handle or executable cleanup before the last user finishes.
- After terminal failure and proven cleanup, a later explicit production start can work.
  If a child remains alive, restart must refuse/wait rather than duplicate it.
- Real NeoForge ServerStoppingEvent then ServerStoppedEvent; ServerStoppedEvent alone after a
  tick-loop failure; repeated events. No backend restarts after Minecraft stops accepting it.

Track launch count, maximum simultaneously living children, stream closure, termination calls,
ready ownership and terminal status. Require max living children <= 1 throughout, not just at
the end. Cover normal and debug facade implementations; existing tests using only no-op hooks
cannot prove the debug artifact's logging is safe.

## 7. Subprocess and integration verification

In an isolated temporary working directory, exercise real subprocess pipes and exit behavior:

- A task-owned small child emits valid readiness, ordinary output, then a selected exit code.
  Prove restart after logger failure without closing the pipe prematurely.
- A noisy child continues emitting after diagnostics degrade; prove output is drained and
  child progress continues. Use deterministic markers, not a throughput flood on the live host.
- Stop while child is starting/writing; confirm PID disappearance, no descendants/listeners
  left behind and release of task-owned executable files only after exit.
- Start the actual bundled Rust binary against isolated configuration/data and a loopback test
  port. Verify valid readiness, terminate that exact task-owned PID, observe one replacement
  and restored handshake/object transfer with the same persisted identity.

No live OOM injection, live recursive logger fault or global memory-pressure stress. If a
real small-heap OOM subprocess test is useful, isolate it with independent process/resources
and deadlines, and report its limitations; it must not exhaust the host or production JVM.

## 8. Builds and offline gates

```text
./gradlew :server:serverLifecycleTest
./gradlew schedulerBehaviorTest debugHarnessJavaTest debugHarnessPythonTest
./gradlew buildAll verifyDebugHarnessArtifacts
git diff --check
```

Add a debug-server lifecycle execution variant if needed to load the actual debug facade;
name/register it explicitly in the implementation and include it in verification. Run Rust
transport tests when executing bundled-backend interop, without changing Rust production code.

Check test children/fault injectors/fixtures are absent from shipped normal and debug artifacts.
Debug controls remain absent from normal artifacts. Record exact commands, assertion counts,
failed runs, skipped platforms and permission restrictions. Source-string matching alone is
not a regression test. Do not publish a client update merely because buildAll creates one.

## 9. Scoped debug deployment and live acceptance

This is a server-bridge change. Install the relevant bundled debug server artifact through
the established graceful Creative restart workflow, retaining a recoverable prior JAR.
Keep the currently compatible client and unchanged Rust payload unless the actual build
requires otherwise; record the intended pairing rather than assume equal version numbers.
Confirm actual running Java server version/JAR identity, Rust PID/executable hash and client
CLIENT_READY identity. An updater listing or staged file is not running-version evidence.

Deployment restart restores the currently failed supervisor, but that alone does not prove
the new recovery path works. Complete these separate checks:

1. Healthy startup: one supervisor, one Rust child, correct UDP listener/readiness and actual
   client endpoint receipt/QUIC handshake. Verify no data/catalog/certificate changes.
2. Recoverable child-exit test in a coordinated window: resolve the exact owned Rust PID and
   parent JVM first, terminate only that child, and observe one replacement after the existing
   retry delay. No broad process matching, Minecraft kill or data reset.
3. Confirm the existing client reconnects, retains fallback terrain and receives a genuinely
   uncached section/metadata response. Merely drawing cached terrain is not online recovery.
   Use a documented known cache miss or isolated designated profile; do not erase normal cache.
4. Use existing `run_live_client_test.py` checkpoint/trace/pose/zoom and reconnect-continuity
   scenarios before/after recovery. Restore designated player's pose/zoom. Counter deltas must
   not cross session resets without explicitly splitting observations.
5. Graceful Minecraft stop in the scoped deployment/test sequence: no restart after stop, no
   child/listener left alive and no unreaped ownership. Restart normally and verify readiness.

Fault-injected logger/VM errors belong in isolated tests, not the production server. Existing
client telemetry, `VOXY_RUST`/endpoint events, tmux console, process/UDP checks and uploaded logs
are sufficient for live healthy/child-exit acceptance. Use read-only jcmd thread/heap diagnostics
if a real fault recurs, but do not mistake post-failure heap usage for the OOM's retained cause.

If Java heap exhaustion recurs, stop stress testing, preserve evidence and report that separate
incident. Do not change Xmx, disable assertions or repeatedly restart to manufacture a pass.
If the designated client is offline, mark live client acceptance blocked rather than infer it
from process readiness. Server-only integration and client live verification are separate results.

## 10. Acceptance and handoff

- Recoverable logging failures no longer kill supervision or prevent readiness/restart.
- Fatal failure leaves truthful terminal state, withdrawn readiness and settled or explicitly
  retained ownership; no unconditional Error retry loop and no dead-thread running sentinel.
- No duplicate Rust child, stale finalizer mutation, forgotten live process, executable cleanup
  race, lock-bound wait or recursive failure logging in tested paths.
- Existing protocol, identity, storage, retry policy and Minecraft lifetime coupling remain.
- Offline behavior/real-process tests pass; live child recovery and actual client streaming are
  verified separately with exact runtime identities. State unsupported/error-exhaustion limits.
- Write `project_audit/rust_supervisor_failure_recovery_results.md` with source/build identities,
  commands, fault matrix results, evidence paths, performance/resource observations and remaining
  limitations. Do not claim the Java heap cause or historical Rust exit cause was solved.
- Roll back only this task's code/artifact if needed, preserving other work and all data. Keep
  failure evidence. No client cache wipe or server-world rollback is necessary.
