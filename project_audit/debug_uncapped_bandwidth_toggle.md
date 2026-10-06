# Debug-only unlimited download bandwidth

User request2026-10-06: add a debug-only toggle that uncaps Voxy bandwidth and
disables the bandwidth slider while enabled. Branch:
`feature/cache-first-background-updates`; starting commit `b39ed114`.

## Implementation contract

- Add Unlimited Download Bandwidth alongside the per-server download controls.
- Register the toggle only in the debug client, default off, persisted per server.
- Disable the slider immediately using Sodium's pending toggle state. Keep its
  saved finite value; turning unlimited off restores that value.
- Apply bandwidth changes through the existing streaming-settings wake and
  OPEN/SETTINGS paths. Zero means genuinely uncapped, not a large finite limit.
- Permit zero only on the debug server. The controller explicitly selects the
  native capability, rejects unsupported requests before bridge submission, and
  native route registration and subsequent stream settings enforce that gate.
- Uncapped sends bypass pacing debt/timers; counters, socket/framing overhead
  accounting and QUIC congestion control remain active. Transitions involving
  uncapped mode reset pacing debt without granting finite idle credit.
- Normal clients retain the finite slider and ignore the stored debug flag.
  Normal servers continue enforcing finite policy. No cache/wire version,
  compatibility path, runtime budget, thread or unrelated renderer change.

## Verification and publication scope

Build normal/debug clients and server/native267. Use focused existing unit
fixtures for the changed pacer/gating behavior and packaging checks; no integration,
laptop or fake100 test. Record failures honestly. Deployment scope is the PC debug
updater and Voxy_Testing controller/native only. Keep cache/settings, both backup
SSH helpers, unrelated mods/world data and Main/original repositories untouched.

The old deployed server260 rejects zero and therefore cannot provide this feature.
Replace the Testing controller only after Java/native/cgroups have stopped and
rollback artifacts are retained. Observe actual loaded hashes rather than feed
publication alone. Verify the new toggle/slider on the PC when foreground input
is available; preserve any unavailable evidence as a limitation.

Use one task-scoped live clock, target300s, hard600s, with at least90s reserved
for cleanup. Prepare/build offline first. Never reset a clock to extend testing.
Actual deployment/build/live outcomes are recorded below after implementation.

## Result267

Implemented in `b5494a77` on the feature branch. Normal/debug clients and bundled
server/native built successfully through toolkit job
`1f591575-956c-42cb-ad76-9738e2471fb0`; the existing focused Java ownership/timing/
harness and artifact checks passed in `f6663b68-9509-4bd9-a8a5-bd6022390e56`.
No integration/pressure suite was run. Normal/debug client bytecode confirmed
the capability hook returns false/true respectively.

The first Rust unit command failed compiling unrelated existing fixtures:
`config.rs` uses an obsolete constructor and `refresh_tests.rs` calls removed
APIs. These were left unchanged. An isolated external Cargo manifest points
directly at the actual production `pacer.rs`; its new transition/writable-poller/
counter unit case passed. The additional full-library wire/gate cases did not
execute because of those pre-existing compilation errors. Source review and
actual debug deployment supplement that limited fixture execution.

| Artifact | SHA-256 |
| --- | --- |
| Debug client267 | `0f062b5ba224db563bde539cf10686a48dd5dff73fb201488c6cfedd850623dc` |
| Debug server267 | `9900d5273f09304714d481f4288666b26c43da7501807f39c18a98899efbf383` |
| Embedded/running native | `e5dad2dd36b4dfcc6b6c9b9ae54d090e8d351a033980f026327cdaf6c1bb8d7f` |

The PC feed was published while the exact original server260 still matched the
immutable run manifest. Testing was then stopped; all owned Java/native processes
were proved absent and the old native cgroup empty before installation. Controller260
was retained as a hash-verified rollback. Server267 started and PC auto-update/
rejoin completed without a cache reset.

The live check discovered that Testing's external memory launcher forwards only
selected environment variables. It initially dropped the new debug capability.
After proving Testing stopped again, one forwarding property was added to
`/home/aerosmp/Desktop/Voxy_Testing/bin/voxy-memory-cap.py` and Testing restarted.
No limiter/watchdog value or algorithm changed. Original launcher SHA:
`b0ce47e005e49c82dc7af6376094325083f5a541f2891e4dc1a206d6321c6e39`;
updated SHA: `3bda469433353046dafc741eefda6b29e9f04ec6cdcf473d21aaece2aebe4264`.
This necessary deployment adjustment is retained with an original-file backup.
The sealed toolkit and its run manifest were not edited.

Final native PID2290032/startticks51523350 matched the selected native hash and
had `VOXY_DEBUG_UNCAPPED_BANDWIDTH=1`. Its cgroup reported memory.max999997440,
swap.max0, current235724800 bytes, oom0 and oom_kill0. JVM PID2289177/
startticks51522105 ran controller267. JVM arguments and native TOML hashes were
unchanged; unrelated Testing mod hashes remained identical.

GIORKOSPC PID30484/start2026-10-06T13:04:32.4146252Z had the matching installed
debug267 hash/header. Typed CLIENT_READY independently confirmed the loaded build.
Backup helpers19916/22444 retained their exact original creation times on both
pre-update routes and the final PC observation. The general config hash and cache
reset marker were unchanged. Cache metadata/anchors naturally advanced.

The operator opened actual Sodium Voxy Rendering settings and viewed the captured
screenshot. It shows Unlimited Download Bandwidth enabled and Download Bandwidth
struck through/disabled. Final per-server policy had `debugUncappedBandwidth=true`
while retaining `downloadKbps=20000`. Server log observations showed corresponding
`VOXY_TOTAL_POLICY total_kbps=0`, confirming actual uncapped mode. The operator
did not click/apply the toggle or overwrite this observed choice. Live off→on→off
interaction was not executed; restoring a finite value is covered by source
review and the production pacer transition fixture.

One task-scoped run `787da8a0-c28d-4f76-8845-2837ccc1e6f6` lasted502.20 seconds
including closure, within600 seconds; the300-second target was exceeded. It was
finished **ABORTED** to retain the incomplete operator-driven interaction check,
with no harness failure and no pending operations. CLOSE_SETTINGS and END_RUN
were confirmed. Client/server267 and the forwarding fix intentionally remain;
no operator bandwidth/storage/cache/transport setting change needed restoration.
No additional live work followed closure. This is feature/deployment evidence,
not a bandwidth-throughput or performance benchmark.

Evidence is under
`/home/aerosmp/Desktop/Codex_Tools/Voxy_Workflow_Tooling/tasks/debug-bandwidth-20261006`:
`artifact-identities.json`, `receipts/rust-unit.log`, `receipts/pacer-unit.log`,
`receipts/limiter-env-forward.json`, `receipts/server-final.json`,
`receipts/pc-final.json` and `receipts/closed-run.json`.
The inspected screenshot is
[saved settings screenshot](../../Codex_Tools/Voxy_Workflow_Tooling/runs/787da8a0-c28d-4f76-8845-2837ccc1e6f6/captures/6484eefa-766d-43fc-aed5-4856b5142cb5.png).
