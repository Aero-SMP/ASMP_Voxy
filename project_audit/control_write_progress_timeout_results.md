# Ordinary control write progress timeout — results

Implemented from `0f6d75c8`, verified 2026-09-10 UTC. Server-only deployment:
**debug server 0.2.213-beta / existing debug client 0.2.211-beta** on **Mod_Testing**.
The unrelated `.gitignore` edit is preserved and excluded.

Implemented, deployed and verified: automated gates, two candidate camera runs,
stable loading, and the reconnect retry passed. Failed baseline/live attempts remain
documented below; this is not a claim that every test attempt succeeded.

## Behavior and scope

The old whole-record deadline is replaced by one private ordinary-control writer.
It encodes once, borrows the remaining slice, awaits Quinn's inherent `write()` with
the unchanged **15-second** deadline, and advances by exactly the accepted prefix.
Only accepted bytes start the next wait. Zero-byte success returns an error rather
than spinning. Timeout errors say the write made no progress, not that the client
stopped reading. No retry/replay or terminal record is appended to a partial record.

The pinned dependency is **Quinn 0.11.11**, with **Tokio 1.53.1**. Its local
`send_stream.rs` documents `write()` as cancellation-safe, returning the accepted
prefix; a blocked write registers its waker without reporting progress. This is why
the deadline surrounds each write future, not each future poll. Cancelling the whole
operation does not roll back previously accepted bytes.

The single-caller generic wire writer was removed; `encode_control_record` and all
four ordinary server call sites remain canonical. Every production call uses the
same constant. The duration argument permits short real-transport tests without
another wrapper, user setting, global override or alternative production path.

Unchanged: protocol/ALPN, client, priorities (control/coverage 2, refinement 1), 512 KiB
send window, message/connection/subscription limits, admission, handshake deadlines,
terminal write/drain/reset, endpoint drain, shutdown grace, idle and keepalive policy.
No new worker, queue, response copy per partial write or background task was added.

## Automated verification

**69 Rust tests passed**, plus main/example targets. This includes the pre-existing
concurrent metadata/coverage test and all regional, wire and storage regressions.
Changed files pass rustfmt and diff whitespace checks.

```sh
cargo test --manifest-path rust-server/Cargo.toml --all-targets -- --nocapture
./gradlew -I project_audit/control_progress_213_build.gradle \
  buildAll verifyDebugHarnessArtifacts \
  :server:serverLifecycleTest :server:debugServerLifecycleTest \
  :server:serverRustIntegrationTest
```

Gradle build, lifecycle and artifact-separation checks passed. Bundled Java/Kwik ↔
Rust integration passed pinned QUIC/catalog transfer, standard/custom dimensions,
supervisor restart, unchanged persisted identity, automatic port recalculation and
clean shutdown. The Java integration transfer is not a slow-network simulation.

Eleven new tests use actual loopback Quinn connections and the production writer;
successful framing checks use the canonical decoder. Test-only observation counts
accepted writes without changing production behavior. The fixtures cover:

| Case | Result |
| --- | --- |
| 128 KiB nonuniform body, old 500 ms total deadline | Times out at about 500 ms despite regular reads |
| Same body, 500 ms progress deadline | Completes in about 1.83 s, 32 writes, exact first and second records plus EOF |
| Real 15-second progress constant | Writer completes in **19.534 s**, exact 131,113-byte first record and 21-byte second record |
| Reader stopped, keepalives active | Times out at 500 ms; transport connection itself remains alive |
| Trickle reader | Consumes 256 bytes but still times out at 500 ms because credit is not replenished fast enough |
| Pause/resume | Short pause succeeds; pause beyond deadline fails; received bytes are an exact prefix without replay |
| Cancellation/reset/disconnect | Dropping the blocked writer preserves only the already accepted prefix; STOP_SENDING and peer close promptly return errors |
| Other stream active | Unrelated bytes do not extend the blocked control write's deadline |
| Invalid oversized dimension record | Rejected before bytes; the next valid record decodes without contamination |
| Actual handler, stalled catalog | 15-second timeout; subscriptions return to zero and both owned permits are released |
| Actual handler, stop/disconnect/shutdown | Prompt cleanup; no retained subscription or connection permit |
| Actual handler, slow real catalog | 71,796-byte record drained over **21.269 s**; exact catalog decoded, later control request served, clean EOF/cleanup |
| Announcement overflow while blocked | Existing guard reports **4,096 missed announcements**, disconnects, and releases subscriptions/permits |
| Actual `serve()` shutdown while blocked | Returns in about **587 ms**, with subscription cleanup, not after a 15-second wait |
| HELLO deadline and terminal error | Original five-second handshake deadline, terminal error/FIN delivery and one-second terminal reset policy retained |

The real handler test traverses `serve_connection` and its subscription/permit
ownership. The endpoint test calls actual `serve()`, not just the write helper.
Cancellation tests distinguish dropping the isolated write future from normal handler
error cleanup on peer stop/disconnect; they do not promise recovery after arbitrary
runtime/task destruction. All timing fixtures have a separate 50-second test watchdog;
watchdog expiry is not counted as the intended timeout. No watchdog expired.

The backlog test initially assumed a completed write guaranteed delivery of the entire
record before a fatal connection close. That test failed: shutdown can discard the
remaining buffered suffix. The corrected expectation retains byte-prefix integrity
and the actual lag/disconnect/cleanup assertions, not successful delivery after loss.
Raw failed output is preserved. An initial test compile typo is also retained.

## Cost and size

Three paired isolated test-process runs used the same 128 KiB fixture, 8 KiB receive
window and 60 ms read pacing, excluding compilation. Resource accounting uses the
existing `regional_refresh_failure_isolation_evidence/measure_fixture.py` wrapper.
Median results on this shared host:

| Measurement | Old total deadline | New progress deadline |
| --- | ---: | ---: |
| First-record write duration | 501 ms, failed | 1,834 ms, succeeded |
| Received bytes | 45,056-byte prefix | 131,134 bytes, both records |
| Whole test-process duration | 0.678 s | 2.025 s |
| User CPU | 12.773 ms | 21.250 ms |
| System CPU | 0 ms | 5.312 ms |
| Peak RSS | 13,144 KiB | 13,080 KiB |

New first-record writes: **32**, accepting exactly **131,113 bytes**. Quinn's old
`write_all()` hides individual prefixes, so its partial-write count is reported as
unobserved, not zero. More elapsed/CPU work on the successful case includes completing
the transfer that the old path abandoned. These measurements do not isolate timer
overhead or demonstrate a memory reduction; peak RSS varies and includes TLS/fixture
setup. No throughput improvement is promised for unaffected connections.

One encoded record remains owned for the operation. The borrowed suffix uses constant
extra memory, but encoding/transferring B bytes is still O(B); each write attempt has
a timeout. Slow successful writes retain their record/response longer than before.

Production changes are **+26/-19 physical Rust lines**, excluding test-only hooks and
module declarations: net +7, ordinary-write helper count **2→1**. The larger validation
addition is separate: **714 lines** in the new test file, **17** test-only inspection/
announcement helpers, and **6** test-hook/module lines in `server.rs`. Formatting and
comments count as physical lines; this is not an AST complexity score.

| Artifact | Server 212 | Server 213 |
| --- | ---: | ---: |
| Embedded release Rust executable | 3,191,904 B | 3,192,448 B (**+544 B**) |
| Debug server jar | 1,613,855 B | 1,613,936 B (**+81 B**) |
| Non-debug server jar, built but not deployed | 1,563,756 B | 1,563,837 B (**+81 B**) |

## Deployment and identity

Resolved target: `/home/printer/Desktop/Mod_Testing`, tmux `%17`. Players were warned;
the old backend exited cleanly and Minecraft saved all dimensions before replacement.
Server 212 is retained as `.jar.disabled` and in the evidence backup. No world, Voxy
data, catalog, certificate, configuration or client cache was removed or regenerated.
Creative was not deployed or restarted. Historical updater diagnostics happen to be
stored under its directory; those files were only read for evidence.

Build outputs use `build/release-validation-213`, outside the updater's watched
`build/libs`. No client update was published. Actual server mod discovery identifies
213; native PID **3315841** reached READY at **19:20:05.474 UTC**, UDP **25786**.
The running executable, built executable and deployed jar hashes were verified.

| Identity | SHA-256 |
| --- | --- |
| Deployed debug server 213 | `89307c530f98474427b402982860620ce5ac5f51a32dc56cbc33c800c2b69352` |
| Running native backend | `6f2915b90c72ec337fcd04f691bdc87f6a8b9fcf4f4e9985cb2908a8be32d11b` |
| Actual client 211 CLIENT_READY artifact | `457a47fe6f4056c922e994a1504a82cda74cde75a5eb0e2bdf2f4936f58e9488` |
| Unchanged TOML | `3b8c95ec8adc202c1f2970f4fb3438d0c6540ae8efd5b9ef2983808979cdc4a1` |
| Unchanged certificate | `1c1fd2962903b429a631ef8ca3b8d59614efcd129692dfd4be730237a029bfd7` |

## Live verification

The existing runner used `--player MGengine --console-target %17` and explicit output
under `Mod_Testing/logs/voxy-tests`. All successful runs must retain their actual
client artifact identity; staged/updater filenames are not identity proof.

Pre-deployment runs are retained:

- `563b317e-c3c8-440f-b6bb-0e5886899c9d`: **POSE_TIMEOUT**, before testing the changed
  code. Client 211 was verified, with active terrain and no Voxy failure code; the
  requested exact pose did not settle.
- `40242477-2e8a-4407-9edd-bfe122317701`: immediate retry **INVALID_STATE**, pipeline
  unavailable. Neither is a passing baseline or evidence of a candidate regression.

Post-deployment camera runs:

- `031a06de-6b38-4240-a1ee-14d75c73f2f8`: **PASS**, active 16,921→37,394, newest
  announced generation 647→649; missing coverage briefly reached 64, then zero.
- `19870015-b345-4878-8fe5-159b06d6c034`: **PASS**, active 37,465→52,110, generation
  649→650, missing coverage remained zero.
- `4c7c11bc-d1aa-4329-a0c6-2f99cb0ba7a1`: **PASS**, 30-second stable view; zero missing
  coverage, session/connection unchanged, generation 653→657, 5,980 new activations.
  Decoding/meshing and pending-reply counts returned to zero at the final checkpoint.

Those candidate intervals recorded zero pipeline failure codes and zero failed
publications. Server metadata/region updates and client generation/activation counters
continued progressing. No live per-record duration instrumentation or machine-wide
network shaping was added; the >15-second proof comes from the isolated Quinn tests,
not the real Minecraft/Kwik client or a claimed 4G incident reproduction.

The first reconnect run `f9b40e98-7a66-40f7-b84b-16fe8658f59d` did **not** pass. Its
trace was interrupted by a Minecraft TCP `SocketException: Connection reset`; the
updater's SSH connection aborted at essentially the same time. The runner timed out
and recorded `DISCONNECTED`. The client automatically rejoined. No server control
write timeout or announcement-lag error was logged in that interval. This is evidence
of a separate connection interruption, not proof of its network root cause or that
the new writer fixes it. The failed run and client stack trace are retained.

Retry `795eb8ee-cd31-48f6-8a0a-b3c5db1eed68` **passed**, 19:24:15–19:25:13 UTC, with
the same verified client 211 hash. LOD session 2 survived connection epoch 1→2;
announced generation advanced 679→687; failure and failed-publication counters stayed
zero. Active geometry stayed nonzero (35,983–64,752 sections, ending at 40,806).
Missing coverage briefly reached 70 and ended at zero. The current-view reconnect
scenario does not lock the camera, so these changing counts are not a fixed-pose
performance comparison. The uploaded screenshot
`voxy-test-795eb8ee-cd31-48f6-8a0a-b3c5db1eed68-6.png` was inspected: extensive rendered
terrain, water, snowy areas and structures under distance fog, not an empty scene.
It corroborates the positive geometry/draw telemetry but does not prove every LOD's
detail choice or reproduce slow metadata delivery on a Minecraft client.

## Remaining limits

Quinn accepting bytes is not remote application decode progress. Tiny application
reads, unavailable client workers or withheld flow-control credit can still lead to
a 15-second no-progress timeout. The connection loop still waits inside a control
write; announcements/other controls can be delayed and the unchanged backlog guard
can disconnect the peer. Longer-lived records retain resources longer. This change
does not solve TCP resets, every reconnect issue, worker stalls, Java heap exhaustion,
or guarantee low TTFD on mobile networks.

Raw commands/results, failed attempts, costs, build logs, deployment evidence and
live run directories are retained locally under
`project_audit/control_write_progress_timeout_evidence/`. No safety assertion or
transport ceiling was relaxed to produce a passing result.
