# Streaming settings implementation — build 260

Implemented 2026-10-05 on `feature/cache-first-background-updates`, source commit
`caf1d876`. The implementation is complete; live verification is **partial**.
Matched debug client, Java controller and native backend 260 were deployed.
The real-player harness was finalized **FAIL**, because mouse-driven UI checks
could not run. This is not an all-checks-passed report.

## Implemented behavior

- Removed Terrain Update Interval, its translations, configuration field/getter,
  save normalization, debug setter and unnecessary change tracking. Java OPEN /
  SETTINGS use `RegionalProtocol.UPDATE_INTERVAL_MILLIS = 2_000`. Rust runtime
  initialization and no-subscriber fallback share its own 2,000 ms constant.
- Download Bandwidth now defaults/resets to 5,000 kbps, ranges from 100 to
  20,000 kbps, and retains the existing actual-traffic accounting. Client policy,
  shared discovery/debug validation, authenticated Rust route registration and
  OPEN/SETTINGS validation all accept 20,000. The missing-policy connector fallback
  also uses the new default. Removed an unused discovery overload with an old
  hardcoded default. Wire widths/layout and transport pacing remain unchanged.
- Replaced the preset storage list with smoothly interpolated numeric positions.
  The installed Sodium API exposes integer sliders, so a normalized `0..2^20`
  coordinate uses double interpolation and persists whole-byte longs. Only the
  final coordinate selects Entire world. At most four exact anchors preserve
  100 MB, 500 MB, the saved finite allowance and the finite maximum. Mapping is
  captured on binding load and remains stable while editing. Apply checks the
  captured server identity. Numeric values display decimal MB/GB.
- Applied the plan's four short translated tooltip sentences. Removed the
  unused tooltip-only cache-status record, accessor, inventory query and formatter.

Existing saved selections remain selected. Defaults affect new policies and
Reset; they do not overwrite a user's selected cap. No legacy reader, migration,
protocol version, custom screen, dependency or runtime budget was introduced.
Storage mapping costs O(1) time and space: it handles at most four anchors and
does not scan cache regions. Cache loading, rendering and eviction ranking are
unchanged by this implementation.

## Source review and build

Independent source/API review covered exact saved/default values, duplicate
anchors, unknown/tiny world estimates, estimates near Long.MAX_VALUE, finite
clamping, server switches and unchanged/rebound bindings. Installed Sodium
0.8.12 bytecode confirms binding loads precede default evaluation and valid
SteppedValidator values retain their original Integer reference; an unchanged
binding therefore avoids validator correction writes. Extremely large worlds
and new-server defaults received source review, not fabricated live scenarios.

`./gradlew buildAll --console=plain` succeeded in 31 seconds. It compiled client,
shared/controller debug sources, built the release Rust server and assembled
normal/debug client and bundled server jars. No unit, integration, synthetic,
laptop, pressure or 100-player tests ran. Existing deprecation/build advisories
did not fail assembly. Both server jars embed the matching release native hash.

| Artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| Debug client 260 | 4,068,992 | `5365cf3fce4f62cd157085c2952dc0d91dd42049a80e72d9859fd57b063a66f0` |
| Normal client 260 | 3,913,594 | See size ledger |
| Debug server 260 | 1,770,990 | `89ab21697970868d5289e43bb3bfc061f43fc4536d3534a84c9c40c6ce1d788c` |
| Native backend 260 | 3,462,352 | `c1352e54bfd0af7f5c7f03518970cd0a2e162715cc05b18e0eda42e315f40ea9` |

Normal/debug client jars shrink by 2,261 / 2,392 bytes compared with 259.
The same physical-source counting method gives 38,141 release lines (−3),
183 release files and 56 directories; all selected sources including shared
give 51,127 lines (−6), 249 files and 122 directories. File/directory counts
are unchanged, including zero empty directories. All counted source files were
tracked; generated files and audit-only helpers are excluded. Details are in
[the size ledger](streaming_settings_simplification_size_ledger_260.json).

## Rollout and bounded live verification

The one non-resettable clock ran **2026-10-05 20:24:18.836 UTC to
20:32:57.305 UTC: 518.469 seconds**, including SSH preflight, supervised server
restart, updater/startup waiting, screenshots, failed UI input, restoration and
final receipts. This missed the five-minute target but stayed below the absolute
600-second limit. No live queries or tests ran after clock closure; subsequent
analysis used preserved receipts/logs only.

The original Testing controller was 257 and PC client 259. Rollback artifacts and
three configuration copies were staged before the clock. The old Testing JVM
and owned native cgroup exited before installation. Only the Voxy controller
jar was replaced; server configuration and unrelated mod hashes matched after
startup. The existing supervisor started the matched controller/native 260,
then the hash-verified client was published to MGengine's updater feed. Pending
cache-reset receipts matched an already-completed request and the old test
profile was expired. No new reset, profile, transport hold or cache namespace
was requested.

Observed running identities:

- Testing JVM PID 306308, start ticks 45505073; native PID 307526, start ticks
  45506585. Current native executable SHA matched both jar embedding and build.
  The current server log and harness metadata report controller 260.
- GIORKOSPC game PID changed once from 32240 to 2304. The installed jar SHA,
  debug-log header and typed harness clientBuild all identify debug 260.
- Backup helper PIDs **19916 and 22444** remained unchanged. Primary and
  secondary pinned SSH routes responded before and after rollout.
- JVM initial/max heap remained 1 / 4 GiB. Native `memory.max=999997440`,
  `memory.swap.max=0`; the fresh native cgroup's final `max`, `oom` and
  `oom_kill` counters were all zero. Final sampled memory.current was
  84,713,472 bytes. This is a settings check, not a memory/load benchmark.

| Check | Result and evidence |
| --- | --- |
| Interval control removed | Actual rendering-page screenshots 2 and 7 show no Terrain Update Interval control. |
| Saved policy survives update/menu opening | Preflight and final PC settings show 400 kbps and Entire world. The new 5 Mbps default did not replace them. |
| 20 Mbps SETTINGS and initial OPEN | Real client DOWNLOAD_POLICY step 4 succeeded. Rust logged `total_kbps=20000 interval_ms=2000`; after typed QUIC reconnect, OPEN_READ and STREAM_SESSION also report 20,000. Screenshot 7 displays 20.0 Mbps. |
| Restore original cap | Step 9 restores 400 kbps; step 10 retains Entire world. After step 11 reconnect, Rust OPEN_READ / TOTAL_POLICY / STREAM_SESSION report 400 and 2,000 ms. Final PC settings confirm both original selections. |
| Renderer/cache continuity | Typed results retained rendererIdentity=1. CLIENT_READY showed 2,665 cache hits/reads and 103 GPU draws. END_RUN still showed 103 draws, no publication failures and no dormant evictions. Preserved logs show first local activation around 0.586 s versus HELLO around 13.026 s; these are observations, not a comparative cache-speed benchmark. |
| Cache preservation | Actual preflight disk cache was 913,720,969 bytes; final preserved pipeline reported about 915.6 MB. It was not cleared or reduced. No 500 MB cap was applied because it would be below actual usage. |
| Tooltip hover, decimal drag, UI Reset and finite Apply/reopen | **Unverified live.** Source/API reviewed; remote input failed as described below. No finite allowance was applied. |

The Windows settings screenshot was viewed during the live window. Screenshots
and typed results are under `project_audit/live_client/settings260-capture/`;
the compact retained evidence is linked below. The renderer's activity is not
proof of unrelated visual correctness, FPS improvement or throughput saturation.

## Failed checks and deviations

1. The first BEGIN_RUN arrived during updater startup and returned “No player was
   found”; it was retried only after loaded-260 evidence and succeeded.
2. An early screenshot command arrived before asynchronous OPEN_SETTINGS completed
   and returned “previous test operation is still outstanding”. It was not counted
   as a screenshot. Later commands used exact-result waiting under one deadline.
3. Native mouse input failed with **“Game cannot receive foreground input”**.
   A disposable interactive-task fallback also returned exit 1 with empty stdout /
   stderr and no successful input receipt. Its exact cause is not established.
   Tooltip hover, Shift-click Reset, continuous drag and finite UI Apply/reopen
   are consequently not claimed as passed. No fake UI or new client debug API
   was added to manufacture this evidence.
4. A finalization attempt used unsupported status PARTIAL, returned “invalid result
   status” and timed out waiting for a result. It did not finalize the run. The
   supported FAIL status then finalized it, retaining the failed automation record.
5. The five-minute target was exceeded while waiting for startup and attempting
   UI input. The hard ten-minute ceiling remained satisfied. Skipped UI checks
   were not repeated in another clock/window.

Two connection-ended warnings coincide with the two intentional QUIC reconnect
commands; restored ACTIVE state has no connection failure. Server startup also
logged unrelated mod/dist and recipe errors. This report does not claim those
were corrected or that the entire log is error-free.

Implementation follows the plan; verification coverage is narrower than desired.
The missing mouse-driven checks remain explicit limits of this release's proof.
No changes were made to Main or either original Voxy repository. No GitHub push
was attempted; commits remain on the requested feature branch.

## Evidence

- [Compact live evidence](streaming_settings_simplification_live_260.json)
- [Source/artifact ledger](streaming_settings_simplification_size_ledger_260.json)
- [Live clock](deployment/live260-clock.json)
- [Settings screenshot, saved values](live_client/settings260-capture/voxy-test-e0223118-29df-412a-b88f-f9d4576e6507-2.png)
- [Settings screenshot, 20 Mbps](live_client/settings260-capture/voxy-test-e0223118-29df-412a-b88f-f9d4576e6507-7.png)

Larger frozen logs, all failed-command receipts, rollback material and audit-only
helpers remain locally under project audit; the implementation report and compact
evidence are committed. No runtime source file was added for the verification.
