# Regional refresh failure isolation — results

Implemented on `main`, based on `faa3f1759bc2fb25e524de5f3b993788ec5bafa8`.
Verified on 2026-09-10 UTC. Debug server **0.2.212-beta** is deployed to
**Mod_Testing**; the compatible debug client deliberately remains **0.2.211-beta**.
The unrelated `.gitignore` edit is preserved and excluded from this commit.

## Result

Recoverable refresh failures no longer discard completed removal/publication reports
or starve independent regions and dimensions. The baseline reproduction failed with
zero announcements and neither healthy region available. The new production-path
regression delivers the tombstone and both healthy regions, while truthfully reporting
the broken region as incomplete.

Live verification passed: two repeated camera tests, a stable-view test, a QUIC
reconnect with screenshot, and a stable-view interval spanning a normal Minecraft
save. Rare corruption/write/crash failures were exercised only in isolated fixtures.

This is not a claim that all Voxy failures or all causes of coarse terrain are fixed.

## Implementation

- Preserve the existing refresh report and service announcement loops. Add incomplete
  status and one contextual diagnostic summary; stop explicitly on poisoned shared
  state rather than treating it as recoverable regional input.
- Keep the cheap current-header check. Read a fresh header only for an actual
  candidate, and retain the final consistency check. `SourceChanged` is typed; it
  never triggers a full rebuild against the stale snapshot.
- Permit one full fallback specifically for unusable incremental/reused payloads,
  including compressed-copy CRC failures. Source-read and publication failures do
  not automatically repeat the equivalent full build.
- Separate the builder's terrain-write result from its constructed source table.
  Reconcile final coordinates, generation, world/catalog identity, layout, payload
  CRCs and durability after a possible rename. Publish valid terrain independently
  of sidecar persistence; never reuse an old sidecar for a new generation.
- Preserve subscribed immutable old handles while durability is unresolved. Keep a
  generation high-water mark in the coalesced retry record. Invalid replacements
  owned by the failed writer can be quarantined and rebuilt; valid unexplained
  identities/generations are not overwritten. No recursive maintenance locking.
- Logical deletion and tombstones precede fallible cleanup. Failed cleanup persists
  across rounds/restart; source reappearance cancels obsolete deletion. Failed
  inventory or individual headers never prove absence.
- One small retry record per unresolved coordinate: operation, generation and last
  failed round. One service-level polling deadline advances rounds. Immediate work
  and subscriber wakes cannot clear failed-coordinate suppression. No new thread,
  per-region timer, history, payload retention or response buffer was introduced.
- `--once` drains eligible healthy work in one round, then fails if work is unresolved.

Formats, ALPN, protocol limits, payload checksums, compression, client code and client
cache keys are unchanged. Missing sidecar recovery may require a fresh full build;
we deliberately do not retain whole source tables in failed-work bookkeeping.

## Automated verification

Final command:

```sh
cargo test --manifest-path rust-server/Cargo.toml --all-targets -- --nocapture
./gradlew -I project_audit/refresh_isolation_212_build.gradle \
  buildAll verifyDebugHarnessArtifacts \
  :server:serverLifecycleTest :server:debugServerLifecycleTest \
  :server:serverRustIntegrationTest
```

**58 Rust library tests passed**, plus main/example targets. Changed Rust files pass
rustfmt checks; `git diff --check` passes. Gradle lifecycle and artifact gates passed.
The bundled Rust/Java Kwik integration test passed actual pinned QUIC transfers,
standard/custom dimension discovery, child restart, unchanged persisted identity,
automatic port recalculation and clean shutdown.

Coverage includes:

- Mixed broken/healthy regions, later dimensions, production responder and broadcast
  delivery, immutable old handles, and truthful mixed/unstable one-shot results.
- Failures before and after metadata-only successes; inventory failure and failed
  individual headers; authoritative removal and source reappearance.
- Inventory-to-candidate change, probe change, full/incremental final-check changes,
  missing candidate and unreadable source. Builder-entry counters prove that source
  instability does not invoke an equivalent full fallback.
- Two actual reused-payload corruption cases (ancestor reads and compressed copies).
  One incremental attempt followed by one full fallback equals a clean full build.
- Before/after terrain rename, post-rename reopen, before/after sidecar rename,
  persistent sync failure, invalid identity and corrupt final header. In-process and
  reopened-runtime recovery; four actual subprocess exits at publication boundaries.
- Failure deleting **either** derived file, repeated tombstone suppression, cleanup
  after restart and cancellation when the source returns.
- Multiple failed priority entries, natural-order rediscovery, repeated immediate
  calls, clock-controlled round expiry and eventual retry-record removal.
- Existing air-lighting, boundary, protocol and shutdown checks remain enabled.

Evidence is retained locally in `regional_refresh_failure_isolation_evidence/`.
`tests/voxy-refresh-tests-final-4.log` is the final 58-test output. Earlier failures
are retained: the baseline lost-report reproduction, an incorrect test fixture
layout, and a genuine source-reappearance authority bug found and repaired during
implementation. The initial attempt to use `/usr/bin/time` failed because it is not
installed; the checked-in measurement wrapper uses child resource accounting instead.

## Isolated performance measurements

Baseline is a detached checkout at the recorded commit with measurement/probe tests
only. Candidate and baseline test executables ran the same fixtures sequentially,
excluding compilation. Three paired runs; table values are medians. These are
unoptimized test builds on a shared live host, not client TTFD or isolated hardware
benchmarks. Full/incremental samples include source-sidecar persistence on both sides.

| Measurement | Baseline | Patched |
| --- | ---: | ---: |
| Initial import, eight empty-header regions | 23.686 ms | 17.534 ms |
| 100 unchanged polls, eight regions | 171.747 ms | 35.519 ms |
| Metadata-only update, eight regions | 2.998 ms | 3.350 ms |
| Reopen those eight regions | 2.601 ms | 2.675 ms |
| Full five-chunk lighting rebuild | 1,130.678 ms | 1,068.871 ms |
| Incremental lighting update, 77 reused sections | 341.562 ms | 326.071 ms |
| Eight calls with a broken priority region and eight healthy candidates | 0 healthy published | 8 healthy published |

The failed baseline backlog makes eight failed attempts in about 4.1 ms and does no
healthy work. The patched backlog completes eight healthy publications in about
19.9 ms; a separate hook-count regression proves the failed coordinate is attempted
once per round, not eight times. These durations are not equivalent completed work.

The unchanged polling improvement comes from avoiding unnecessary terrain reopening
before the cheap current-header decision. The candidate-only counter assertion also
proves **zero** candidate/fresh-header reads during 100 unchanged polls. Inventory
header reads still exist; this is not a zero-I/O polling architecture.

The eight-region fixture stores exactly **395,904 derived bytes** on both versions.
Lighting outputs are identical at 241,688 / 242,391 / 244,155 file bytes, with wire
indexes 951 / 949 / 975 bytes and 77 reused sections in each revision. No format or
bandwidth increase was introduced.

For the whole small polling/import/backlog process, median user/system CPU was
198.845/7.953 ms baseline versus 65.606/16.401 ms patched; peak RSS was 11,128 versus
11,520 KiB. For the lighting-update process, median user/system CPU was
7.273/0.028 s versus 6.923/0.024 s; peak RSS 29,096 versus 29,380 KiB. RSS is a process
high-water measurement, not proof of a particular retained allocation. Retry tests
assert one record per failed coordinate and an empty map after recovery.

A separate real sidecar-rename failure fixture (a directory occupying the sidecar
path) measures the partial-write case. Baseline: error, disk generation 1, no runtime
authority; failure/recovery 2.038/2.627 ms, then generation 1 rebuilt. Patched: terrain
generation 1 immediately reported and servable, sidecar explicitly incomplete;
failure/recovery 2.198/2.565 ms, then safe generation 2 with a matching sidecar. Both
finish with two files. Fault-boundary tests also check no temporary-file leftovers.
Corrupt reused-payload recovery is timed in the retained suite output and validates
the resulting sections against clean full reconstruction; no comparative throughput
claim is made from that single recovery timing.

Costs are real: the tiny metadata-only sample is about 0.35 ms slower for eight
regions; startup now re-establishes durability for accepted stored files/sidecars.
Rare post-write reconciliation reads payloads and syncs files. No general claim of
zero tradeoffs or reduced production code size is justified.

## Deployment and actual identities

Only `/home/printer/Desktop/Mod_Testing` was restarted, using resolved tmux pane `%17`.
Players were warned first. Minecraft saved every dimension and Java/Rust exited
before artifact replacement. The old jar is retained as `.jar.disabled`, with another
copy under the evidence directory for rollback. No world, derived store, catalog,
identity, cache or configuration was deleted or reset. Nothing was deployed to Creative.

Loaded mod discovery confirms server 212. Native PID **3125056** reached READY at
**18:12:01.696 UTC**, UDP **25786**, using the existing ALPN. The final rebuild's native
hash equals `/proc/3125056/exe`; staged and deployed server jar hashes also match.

| Artifact/identity | SHA-256 |
| --- | --- |
| Debug server 212 | `6a5e195abc16ea3af97ea02e18aa6e9aa9571c3a96725413c6b1aa48131921cc` |
| Running native backend | `2dc31317b127ecbcf52902167bec18dd46872b643074df5d08d14151b957aec5` |
| Retained client 211, live CLIENT_READY identity | `457a47fe6f4056c922e994a1504a82cda74cde75a5eb0e2bdf2f4936f58e9488` |
| Previous server 210 | `cccede6674e340903396a27a527e0c46d2ab94cc5c816c911a827959431d5293` |
| Unchanged server TOML | `3b8c95ec8adc202c1f2970f4fb3438d0c6540ae8efd5b9ef2983808979cdc4a1` |
| Unchanged QUIC certificate | `1c1fd2962903b429a631ef8ca3b8d59614efcd129692dfd4be730237a029bfd7` |

Build outputs were isolated in `build/release-validation-212`, outside the client
updater's watched directory. MGengine automatically reconnected after the restart.
Every recorded successful harness run independently reports the expected client 211
artifact hash; updater publication alone was not treated as proof of the loaded client.

## Live tests and save correlation

The existing runner targeted `--player MGengine --console-target %17` and explicitly
used `/home/printer/Desktop/Mod_Testing/logs/voxy-tests` for output. No protocol or
live corruption injection was used. Baseline/candidate scenes share fixed poses but
the world and cache keep evolving, so these are acceptance runs, not a controlled
before/after client performance comparison.

| Run | Result | Observations |
| --- | --- | --- |
| Baseline camera `fa26b486-75d9-4d18-9c22-f1f246f44824` | PASS | Server 210/client 211 |
| Baseline camera `c38b1692-f90b-475d-ad79-ee42bc9fad86` | PASS | Same pairing |
| Candidate camera `c215da91-75fe-40b0-a15e-60f980de50f2` | PASS | Active 23,812→29,258; newest announced generation 218→219 |
| Candidate camera `816f394f-7599-45ae-9a6a-6fe829a07f6f` | PASS | Active 29,258→39,808; generation 219→220 |
| Stable `a4a8c0ae-48e4-47fd-b691-564b59234079` | TIMEOUT | Operator scheduling error: started while the second camera run was active; server rejected BEGIN_RUN |
| Sequential stable retry `2254120d-bfb5-4efb-b2cb-3ca0db12ca12` | PASS | Active 39,808→49,501; generation 228→232; coverage missing remained zero |
| Reconnect `0e9f6eb2-150d-4855-b62e-717d44f722a4` | PASS | Session 4 retained; connection 1→2; 49,501 active and 14,082 draws retained; screenshot uploaded |
| Save interval `e7e384e9-fa2b-4021-9d1f-f70d7d4f2beb` | PASS | 18:18:14–18:18:44; generation 254→257; 1,111 further activations; session/connection unchanged |

All successful candidate runs recorded zero pipeline failure codes and zero failed
publications. Camera transitions briefly reported up to two missing coverage demands;
the deliberate reconnect briefly reported 288, ending at zero while existing geometry
stayed active. Thus this does **not** claim missing-demand counters never increase.

Specific live isolation evidence: at 18:15:29.893, source instability deferred
`(-1,0)` at generation 232. Just **34 ms later**, `(28958,37799)` published, followed
by additional healthy regions. `(-1,0)` then published generation 233 at 18:15:31.549.
The retained-client trace advanced its newest announced generation 232→235 while
remaining in the same LOD session. That telemetry field is the maximum announced
regional generation, not a per-section proof of every updated block.

Normal automatic saving is recorded at 18:17:01–02. A scoped `save-all` during the
final stable run saved all dimensions at 18:18:37–38. The client kept 49,501 active
sections and 14,082 draws, with zero missing coverage/failures throughout, while
activation and announced-generation counters advanced. Deterministic fixtures provide
the stronger section-content, report and sidecar consistency assertions.

Inspected `voxy-test-0e9f6eb2-150d-4855-b62e-717d44f722a4-6.png`: populated night-time
terrain, water, distant snowy hills and city structures, not an empty LOD scene.
The image alone does not distinguish every vanilla chunk from every LOD or establish
visual equivalence; geometry/draw telemetry supplies the rendering evidence.

## Size and limitations

Before this report/build-helper addition: 14 implementation/version/test files,
**1,708 added / 253 removed lines**. Of these, dedicated Rust test/hook files plus
air-lighting tests account for **933 added / 12 removed**. Production-path Rust files
account for **774 added / 240 removed**, including their small inline test blocks and
test-only hook call sites; the version change is +1/-1. Three new Rust files are
test-only (`faults`, `refresh_tests`, `refresh_measurements`). Formatting contributes
to these physical-line counts; they are not an AST complexity score.

The embedded native executable grew from 3,149,152 to 3,191,904 bytes: **42,752 bytes
(1.36%)**. The compressed debug server jar grew from 1,596,176 to 1,613,855 bytes.

Necessary new state is O(unresolved coordinates), plus one dimension inventory-failure
round and one service polling deadline. No worker/buffer limits were relaxed. A valid
but unexplained replacement is intentionally left incomplete rather than overwritten.
An unstable region can remain stale; global storage failure can prevent all progress;
an operation that never returns still blocks the single publication worker. Process
power-loss durability is not proven by subprocess-exit tests. Broadcast receiver loss
still uses the existing recovery contract. This plan does not fix total-write timeout,
Java heap exhaustion, arbitrary shader issues or every cause of slow/coarse terrain.
