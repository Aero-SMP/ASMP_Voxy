# Rust supervisor failure recovery — implementation and verification

Implemented 2026-09-06 on `main`: `053e5f60` (ownership/failure policy),
`ee63d038` (pipe-preserving native shutdown). Final server version: debug `.204`.
This is a Java server-bridge change, not a Rust transport/storage or client update.

## Behavior

- One ownership context retains the actual supervisor, child, executable and readiness.
  Thread publication/start is serialized with stop; shutdown joins the actual thread.
  Old owners cannot clean up a replacement's resources. A child whose exit cannot be
  established remains owned and prevents another start.
- Readiness parsing precedes presentation logging. Normal and debug diagnostic routes
  independently disable after a RuntimeException or narrowly scoped StackOverflowError.
  Output continues draining and ordinary failures/exits still use the one-second retry.
- OutOfMemoryError, other Errors and stack overflow outside reporting are terminal:
  readiness and restart intent are withdrawn, cleanup attempted, and a fixed raw-stderr
  message requests manual recovery/server restart. The fallback bypasses redirected
  System.err/Log4j, never closes stderr, and never recursively reports its own failure.
  No watchdog, automatic JVM restart, heap increase or retained exception history.
- Termination signals through ProcessHandle, waits up to ten seconds gracefully, forces
  if needed, and proves exit before closing pipes or removing the extracted executable.
  Unsupported handles fall back to the Process API. Independent pipe cleanup continues
  after a close failure; failed executable deletion retains ownership for a later stop.
- Debug state/status includes lifecycle, wanted/thread/child liveness, PID, known exit,
  failure category and executable ownership. Fatal evidence uses raw fallback/status,
  not a potentially broken Log4j hook. STOPPED emitted inside the finalizer truthfully
  shows the supervisor still alive at that instant; stop returns only after actual join.
- Extraction remains in a task-owned `/tmp/voxy-rust-*` directory. Preparation now runs
  inside the supervisor's finalizer boundary. Missing configuration/resource/extraction
  failure is terminal and never advertises readiness; launch/read failures are retried.
  VoxyServer still gates advertisements on both acceptance and live backend readiness.

## Offline gates

All commands used `-I /tmp/voxy-renderer-admission-build.gradle` to redirect JAR outputs
to `/tmp/voxy-renderer-admission-artifacts`, outside the client updater's watched directory.
Final complete run: `rust_supervisor_failure_recovery_evidence/gates-204.log`.

```text
./gradlew :server:serverLifecycleTest :server:debugServerLifecycleTest \
  :server:serverRustIntegrationTest schedulerBehaviorTest debugHarnessJavaTest \
  debugHarnessPythonTest buildAll verifyDebugHarnessArtifacts --console=plain
cargo test --manifest-path rust-server/Cargo.toml server::priority_tests -- --nocapture
git diff --check
```

- Normal and actual-debug-facade lifecycle runners each passed 266 assertions including
  real NeoForge normal/crash-only/repeated stop events; maximum live controlled children: 1.
- Logger RuntimeException/SOE, every supervisor debug hook's SOE and constructed OOM,
  launch/read/logger OOM, unexpected non-logger SOE, failing fallback, early zero/nonzero
  exit, malformed/duplicate readiness, launch/read IO, EOF-before-exit, force termination,
  failed-owned restart refusal, independent pipe cleanup, launch/start/stop barriers,
  thread-start OOM, failed preparation, interrupted stop and reentrant READY/STOPPED stop.
- Real noisy Java subprocesses drained 20,000 lines across two children with the logger
  disabled; exit 23, retry, replacement and no overlap/orphan verified.
- Actual bundled Rust with isolated empty world/config/data and ephemeral loopback ports:
  PIDs 2060204 → 2060639, clean SIGTERM and supervisor-stop exit codes zero, unchanged
  persisted certificate/private key, pinned Kwik QUIC handshake and identical 111-byte
  catalog records transferred before/after replacement. This transfers metadata, not
  synthetic terrain; actual new terrain receipt is separately verified on the live client.
- Existing Rust bounded concurrent metadata/coverage transport test passed (1 test).
  Existing scheduler/renderer/cache, Java debug-harness and Python harness gates passed.
  Python failure/timeout output and headless updater errors in that fixture log are expected
  fixture activity, not live deployment results.
- Normal/debug JAR isolation passed; lifecycle fixtures and test-only Kwik dependency are
  absent from shipped server JARs. No Rust production source or payload changed.

These are constructed JVM errors, not proof an arbitrarily exhausted/corrupted JVM can
recover. No live OOM/logger recursion or host pressure was injected. Linux x86-64 only;
other platforms and indefinitely blocked logger/OS writes were not validated.

## Failed/intermediate runs retained

- `lifecycle-1.log`, `lifecycle-2.log`: fixture races around readiness publication before
  a debug hook and interruption of the controlled retry barrier. Fixed latch ordering and
  stop-aware fixture coordination, without weakening lifecycle assertions.
- The reentrant READY stop test exposed waiting for a still-live child on the supervisor
  itself. Production now terminates when stop was requested instead of waiting for EOF exit.
- `lifecycle-4.log`: isolated Gradle task execution rejected cross-project configuration
  resolution; replaced it with a local test-only dependency. Full builds had hidden this.
- An isolated Process.destroy test produced exit 134 because that API also closes pipes;
  external-PID simulation now signals through ProcessHandle and requires clean exit zero.
- Live `.203` shutdown reproduced exit 134 while still correctly reaping all ownership.
  `final-stop-server.log` preserves it. This prompted the production signal fix in `.204`
  and the additional clean supervisor-stop exit assertion. Do not treat `.203` as final.

## Artifacts and preserved identity

| Artifact | SHA-256 |
| --- | --- |
| Final server debug `.204` | `389a136b6b5791ae350b1db04cf784939453955f3af930ad5a7a26440c4b3458` |
| Retained compatible client debug `.202` | `e2908b18cf46f6d647f97d69fa547beed6014a21198eaa6c719a7abfc1a3c28d` |
| Unchanged running/bundled Rust | `2c3c11158b507421cb9b45c60acca0c7aa5c181d6f1e8591b5152d5ce56d1dca` |
| Original `.198` rollback server | `c3b902d448d6aa3267ececf1c938ad2ab411bc2a6254c6794c7c2e6b8927417d` |
| QUIC certificate | `64462f6f59b2f4081e9419fbfc679929a7d6681681293f8b9d9f7894076ea984` |
| QUIC private-key file (hash only) | `92472fee28ef7621b98cfbc5d034d2701f4a2db3397e6083ee02420c9c83daa9` |
| Shared voxy-rust.toml | `f717f2030f0cc7d55e3cae94d516a7bde08014bc87d425ae06dbe46018b1c545` |

Server path: `/home/printer/Desktop/Creative/mods/voxy-server-0.2.204-beta+1.21.1-neoforge-debug.jar`.
Prior `.198` and intermediate `.203` jars were moved, not deleted, into
`/home/printer/Desktop/Creative/deploy-backups/rust-supervisor-203/`.
No source worlds, regional data, catalogs, certificates, configuration or client cache
were reset. Normal saved terrain changes continued publishing naturally during testing.

## Live acceptance

Deployment used `printer_session:0.0`, graceful `stop`, proof of JVM/child/listener exit and
all-dimensions-saved, then existing `bash ./run.sh`. Player MGengine received in-game notices.
CLIENT_READY runtime hash confirmed `.202` with Photon, not merely an updater listing.
Initial pose was (3949.657777, 107.444889, -177.953259), yaw -96.088745, pitch 6.7498603;
no test camera/zoom/shader edits were required. Automatic full-server rejoin can involve several
logins; checks were started only after rejoining. Full server restarts reset sessions and
are not mixed into the Rust-only continuity deltas below.

Intermediate `.203` test `40bda54c-1418-4533-83cf-08046527904a`: PASS.
JVM 2044051 retained ownership while exact child 2045201 exited zero at 11:07:58.886 and
replacement 2049373 became ready at 11:08:01.150 (2.264 seconds including retry/startup).
One supervisor in jcmd; its CPU total was 36.33 ms over 116 seconds, an observation rather
than a benchmark. Same session 7, QUIC epoch 1→2; every sampled active/draw count nonzero,
coverageMissing zero. Post-reconnect epoch-2 samples alone gained 13,289 terrain bytes,
one completed batch and two cache misses, with root generation 505→506. Thus live online
progress was not inferred from cached rendering. Screenshot visually confirmed Photon LODs.

Final `.204` test `7075f3dd-a536-4818-9363-191ef2c798d9`: PASS.
JVM 2062158 remained running; exact child 2063266 exited zero at 11:13:11.003 and the
single replacement 2066975 became ready at 11:13:14.043 (3.040 seconds including the
one-second retry and startup). Client runtime hash again matched `.202`, Photon enabled.
Session 10 stayed unchanged and QUIC epoch changed exactly 1→2. Minimum sampled active
sections 37,585; minimum draws 3,077. Within epoch 2 alone, terrain bytes increased from
9,322,929 to 14,153,732 (+4,830,803), completed batches 1,472→2,015 (+543), and cache
misses 1,562→2,424 (+862). No cache was erased to generate those misses.

The player actively travelled during this final trace, from (3907.28, 135.38, -172.24)
to (1492.94, 113.14, 353.30). CoverageMissing ended at 62 rather than zero while new
terrain loaded. This is a moving-view reconnect/streaming pass, NOT a fully-loaded-scene
or fixed-pose performance benchmark. The harness did not teleport, zoom or change shaders,
so there was no test-imposed pose to restore over the player's subsequent movement.

Final `.204` graceful stop: 11:15:33.726, `STOPPED wanted=false childAlive=false
exitCode=0 failure=NONE ownsExecutable=false`; all dimensions saved at 11:15:35.476.
JVM 2062158 and child 2066975 disappeared, UDP 25587 had no listener, and the owned
executable `/tmp/voxy-rust-13916880428205536692/voxy-rust-server` no longer existed.
No replacement was started after stop. Evidence: `final-stop-server-204.log`.
Normal final restart: JVM 2077102, one Rust child 2077926, ready at 11:17:02.632;
one supervisor confirmed by jcmd, UDP 25587 owned by that child. Actual loaded `.204`
version, installed server hash and `/proc/2077926/exe` hash verified. Certificate, key
and configuration hashes still match the pre-deployment values above.
Final client checkpoint `4583fa21-d265-43ab-8254-f86373133010`: PASS after automatic
rejoin, `.202` CLIENT_READY runtime hash, positive LOD geometry/draws and Photon enabled.
Evidence: `final-server.log`, `final-threads.txt`, `final-runtime-hashes.txt`,
`final-client.log` and `/home/printer/Desktop/Creative/logs/voxy-tests/<run-id>/events.jsonl`.
The updated server and compatible client were left running and connected.

## Limits and source publication

The historical Java heap consumer and unknown historical Rust exit cause remain unresolved;
this plan changes failure handling, not heap sizing or the allocation architecture.
Hard protocol/resource ceilings are unchanged. No live memory-exhaustion incident has been
observed during this task's acceptance window.

Implementation commits are local on main. Remote push remains blocked by the tool reviewer's
earlier destination-approval rejection for `git@github.com:Aero-SMP/ASMP_Voxy.git`; it was not
bypassed. This source-publication restriction is separate from successful artifact deployment.
Unrelated `.gitignore` edits remain unstaged. Large evidence logs/screenshots are local, not
committed; this report provides their exact paths/run IDs.
