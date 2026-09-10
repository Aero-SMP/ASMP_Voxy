# Control-before-terrain admission (.211)

## Status

Implementation and offline verification complete. Debug client .211 was published,
installed and restarted through Prism. **Live acceptance remains blocked:** after an
independent Mod_Testing restart/world replacement, the server did not complete client
logins or answer the harness. No candidate performance improvement is claimed.

Started from `987d40f8` on main with only the unrelated `.gitignore` edit. That edit
is preserved and excluded from the implementation commit.

## Production change

Only the relative order in `ClientSession.Session.run()` changed:

```text
connect → drainWorkers → drainControls (if connected) → drainNetworkReplies
```

Added one explanatory comment. All subsequent phases remain in place. No production
test seam, queue, thread, reservation, protocol, new limit, or telemetry was added.
No server source/config/data changes are part of this implementation.

## Permanent tests

Extended `RegionalControlFlowBehaviorTest`, reusing codecs, mesher, renderer allocation
fixture and publication fixtures from existing suites. The actual `Session.thread`
executes `run()`; semaphores pause only the controlled renderer boundary. Tests do not
copy the loop or manually call the three drains in the desired order. Worker completion
signals and deterministic resource completions control interleavings, without sleeps.

- 1, 2 and 8 workers: waiting index gets one worker before terrain refill; remaining
  capacity admits DATA/EMPTY terrain, for coverage and refinement.
- Original ordering failed with `arrived index lost free worker to terrain:
  workers=1 data=false coverage=false`. Candidate passes. The original failure is
  `/tmp/voxy-control-original-order-2.log`.
- Full occupancy preserves pending metadata. Releasing a worker disposes a counted
  stale native mesh once, then admits metadata ahead of eight queued terrain replies.
- Repeated finite metadata bursts use real INDEX_DECODE; terrain then resumes real
  decode/meshing and all eight sections activate without reconnect or new replies.
- No-control capacity and priority are unchanged; two indexes decode concurrently.
- Confirmed absence/world changes invalidate queued terrain before new ownership.
  Error/shutdown controls release handoffs once and retain existing active publications.
- Older announced generations and unsubscribed indexes are skipped. Connection, view
  and metadata-revision changes reject stale index completions. Unknown catalogs park
  decoded indexes; corrupt indexes retain existing region-retry behavior.
- Occupied catalog worker preserves its pending record while terrain and renderer
  phases continue. Freeing it permits real catalog decode, mapping/event delivery,
  and lease release.
- Held renderer publications do not block owner polling; activation frees capacity
  for the pending index. Existing coverage-reclamation/disposal tests also pass.
- Section and metadata workers/leases are closed after tests. Existing duplex writer,
  cache, ownership, rendering, shader and debug suites still pass.

Two fixture failures were retained: the first owner fixture lacked a headless renderer;
the first corrupt-index assertion incorrectly expected reconnect. Inspection confirmed
the existing behavior is region retry, so the test now asserts that exact behavior;
production recovery and timeout assertions were not changed.

## Build verification

```sh
./gradlew -I project_audit/control_admission_211_build.gradle \
  schedulerBehaviorTest workerDebugBehaviorTest debugHarnessPythonTest \
  buildAll verifyDebugHarnessArtifacts --console=plain
git diff --check
```

All passed. Python harness suite: 10 tests. Final output:
`/tmp/voxy-control-validation-final.log`. Earlier fixture/mutation failures and final
validation output are also copied into the local, ignored
`project_audit/control_before_terrain_admission_evidence/` directory.

Artifacts were built in `build/release-validation-211`; only the tested debug client
was subsequently copied to the updater's `build/libs` directory.

| Artifact | SHA-256 |
| --- | --- |
| Debug client .211 | `457a47fe6f4056c922e994a1504a82cda74cde75a5eb0e2bdf2f4936f58e9488` |
| Normal client .211 | `f2f098086948d8b5920273dc66993d2a2e4dd74473774efb95902637f21312a1` |
| Debug server .211 (built, not deployed) | `057bdf77d84e46acb8fe97bb3ec2eb223893a0f331e374eac7853503d90dd9f7` |
| Normal server .211 (built, not deployed) | `8845108775b8c3d263c9dfbb05ded429eec327201b87d878f653b6132334f21b` |

## Runtime identities and deployment

Target is **Mod_Testing**, not Creative. Existing updater diagnostics still arrive at
the historical `Creative/logs/client-upload` directory; reading those does not test or
modify the Creative server. All harness commands targeted the resolved Mod_Testing
pane `%10`, with output explicitly under `Mod_Testing/logs/voxy-tests`.

Baseline CLIENT_READY identity exactly matched debug client .204:
`6fa177fdf927be056832bf90839a7885ac63d357854a153f5a014f6a1af18138`.

Intended candidate pairing: **debug client .211 / debug server .210**. The endpoint and
streaming protocols did not change. Live server jar/hash verified before and after its
independent restart:
`cccede6674e340903396a27a527e0c46d2ab94cc5c816c911a827959431d5293`.
We did not deploy or restart the server. Native PID changed externally from 2858098 to
2903008 (JVM 2902274). Its 17:01:26.763 UTC VOXY_READY announced UDP25786 and unchanged
certificate `1c1fd2962903b429a631ef8ca3b8d59614efcd129692dfd4be730237a029bfd7`.

Published client .211 around 17:01 UTC. Uploaded restart evidence records:

- 17:01:25.701 helper starts for old PID 17624.
- 17:01:26.914 old process stops.
- 17:01:27.456 launcher dispatch starts PID 944.
- 17:01:29.090 Prism starts Minecraft PID 12144.
- Fresh debug log begins `Voxy version 0.2.211-beta`; updater reports CURRENT .211.
- 17:02:09.691 automatic connect to `ssh.aerosmp.com:25586`, followed by repeated
  connection timeouts/retries.

Thus loaded version/restart are confirmed, but candidate CLIENT_READY hash confirmation
and successful rejoin are **not** confirmed. Published artifact identity is not treated
as a live handshake identity.

## Live runs and limitations

Used existing runner/scenarios without deadline changes or cache deletion:

```sh
python3 tools/run_live_client_test.py --player MGengine --console-target %10 \
  --output /home/printer/Desktop/Mod_Testing/logs/voxy-tests \
  --scenario tools/scenarios/camera_turn_response.json --repeat 2
```

Also used `ok_zoomer_zoom_cycle.json --repeat 2` and
`regional_control_recovery.json` on the baseline.

| Phase/scenario | Run IDs | Result |
| --- | --- | --- |
| Baseline camera turns | `d371d20c-319a-42cd-a07c-fe08e127c86a`, `bd2a8e08-1f91-4a18-9706-8ff3558cb14b` | Both PASS |
| Baseline zoom cycle | `4fbf7216-7bfa-47a2-b9a5-793305057b7c`, `cf8ea0d2-ce93-44ec-bd00-a41be92e6ad3` | CLIENT_READY timeout, then PASS |
| Baseline control recovery | `3f52dcc2-7473-49bd-8aa7-1b1a3b0b6800` | FAIL: initial GPU-draw prerequisite stayed zero; reconnect step not reached |
| Candidate camera turns | `4efe4f9d-be5c-4d45-acc4-ff094e0dc3f3`, `59131f13-bb58-4db7-9779-7174ee5017f2` | Both CLIENT_READY timeouts |

Baseline camera pose round trips were 102.37 and 51.85 ms; 15-second trace steps took
15085.22 and 15085.08 ms. These are harness timings, **not TTFD**. Both runs retained
42 active sections, zero missing coverage and unchanged 862 received network bytes;
draw counts ranged 0–34 in the first run and stayed zero in the second. This is cached,
mostly settled work, not evidence of high-throughput discovery performance.

Mod_Testing's source world and runtime changed externally between baseline and candidate.
No fair paired performance comparison is possible from these samples. Normal caches
were preserved. Cold-cache, constrained-link/4G, large-discovery comparison, candidate
zoom/rendering, allocation/TTFD and live reconnect resource acceptance remain unverified.
The stock harness provides hold/reconnect controls, not a controlled bandwidth/RTT profile;
no global network changes or substitute benchmark runner were introduced.

## Live blocker

Two read-only JVM dumps about 80 seconds apart both show `Server thread` parked waiting
for a chunk through `ServerChunkCache.getChunk`, called by Create Radar
`WeaponGroupCoordinator.onLevelTick` line 50. TCP25586 is listening, but logins/harness
commands did not complete. Dumps:

- `/tmp/voxy-control-server-thread-dump.log`
- `/tmp/voxy-control-server-thread-dump-2.log`

This establishes the observed server wait, not a complete deadlock diagnosis. It is
outside the client-ordering change; no mods/worlds/configs were removed to bypass it.
Live acceptance needs the server responsive again, then exact CLIENT_READY identity
verification and the remaining paired scenarios. Continuous incoming metadata still
has no general fairness guarantee; this patch does not claim to fix every timeout,
catalog-worker wait, or Rust refresh issue.
