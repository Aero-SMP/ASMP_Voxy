# Simplify Voxy streaming settings

Status: implemented 2026-10-05 in `caf1d876`, with matched debug 260 deployment.
Live verification is partial; see
[the implementation report](streaming_settings_simplification_implementation_260.md).

Repository: `/home/aerosmp/Desktop/ASMP_Voxy_Cache_First_Updates`.
Branch: `feature/cache-first-background-updates`.
Audited starting commit: `6234331b` (client debug 259; deployed Java/native 257).

This supersedes the earlier plans' configurable update interval, 1 Mbps default,
10 Mbps maximum and preset storage choices. Their per-server, cross-dimension
policies, physical-byte accounting, cache-first behavior and Entire world mode
remain applicable. Keep the original repositories and Main read only.

## 1. Requested behavior

| Control | Result |
| --- | --- |
| Terrain Update Interval | Remove the setting. Ordinary background terrain updates use 2 seconds. |
| Download Bandwidth | 100 kbps–20 Mbps; default/reset value 5 Mbps. |
| Cache Storage | Smooth decimal-valued slider without preset detents; minimum 100 MB, default/reset exactly 500 MB, growing Entire world endpoint. |
| Tooltips | One short sentence per control, without server addresses, testing references, transport details or appended diagnostics. |

Use decimal units: 5 Mbps = 5,000 kbps = 625,000 bytes/s; 20 Mbps =
20,000 kbps = 2,500,000 bytes/s; 500 MB = 500,000,000 bytes.
The bandwidth cap continues to count actual server-to-client Voxy traffic,
including overhead/retransmissions, across the server's dimensions.

Defaults apply to new policies and Reset. Preserve previously selected bandwidth
and storage allowances; changing a default does not overwrite user choices.
Opening/rebuilding the menu or updating a world-size estimate must not alter a
saved allowance. No cache clearing or configuration migration is needed.

## 2. Fixed two-second updates

Primary files: `VoxyConfigMenu.java`, `VoxyConfig.java`, `ClientSession.java`,
`RegionalProtocol.java`, `LiveClientTestHarness.java`,
`shared/src/debug/.../DebugTestCommandPayload.java`, `en_us.json`.

1. Delete the interval option and its now-empty option group. Delete its title
   and tooltip translations.
2. Remove `backgroundUpdateIntervalSeconds`, its getter and save-time
   normalization from `VoxyConfig`. Do not leave a hidden editable field,
   constant-returning compatibility getter or obsolete key reader. Existing Gson
   loading naturally ignores the removed key and the next normal save omits it.
3. Define one Java-side fixed value, `UPDATE_INTERVAL_MILLIS = 2_000`, in the
   existing protocol/streaming owner. Use it for initial OPEN and SETTINGS.
   Debug reporting derives seconds from the same constant.
4. Remove the debug DOWNLOAD_POLICY `interval` setter and validator case, so
   debug controls cannot silently reintroduce the removed setting. Keep the
   remaining bandwidth/storage/render-distance controls.
5. Remove interval-change tracking that has no remaining purpose, such as
   `sentIntervalMillis` and its comparison/reset assignments. Preserve initial
   OPEN, reconnect, bandwidth/anchor/refresh-setting updates and their tickets.
6. Align Rust's initial runtime freshness value and no-subscriber cadence fallback
   to 2,000 ms, using one Rust-side constant in the existing regional module.
   Review `regional/runtime.rs` and `regional/service.rs`. Existing minimum-range
   validation and session cadence coordination can remain; do not redesign the
   coalescer or remove the existing wire field for this settings cleanup.

The two seconds govern background freshness and server-side coalescing. Missing
coverage, required zoom detail and immediate cached-terrain loading retain their
existing paths. This is a fixed cadence, not a promise that every changed block
arrives exactly two seconds later under congestion.

## 3. Short tooltips and removal of unused status formatting

Exact proposed English copy:

- **Download Bandwidth:** “Voxy download limit for this server, across all dimensions.”
- **Cache Storage:** “Terrain cache space for this server, across all dimensions.”
- **No current server:** “Join a server to change these settings.”
- **Unavailable policy:** “These settings are unavailable.”

Use the ordinary translated components without a server-ID argument. Keep
per-server persistence, but describe its scope with “this server”. Do not append
IP/port, cache-byte inventory, disk-pause reasons, raw exceptions, test-server
names or protocol/accounting explanations. The labels remain Download Bandwidth,
Cache Storage, and Entire world.

Remove the tooltip-only `ClientSession.storageStatus()` plumbing once its last
consumer is removed: `ServerStorageStatus`, its static field, `reportedStorage`
and the formatting block in `processCacheDownloads()`. Remove that block's unused
`namespaceBudget()` query; retain actual storage-policy/admission operations and
existing debug/log diagnostics. Do not replace the long tooltip with another
production status panel.

Keep the current no-server/unavailable disabling behavior. Production and debug
builds use the same concise settings copy.

## 4. Continuous storage selection using the installed Sodium API

Primary file: `VoxyConfigMenu.java`.

Source/dependency review confirmed Sodium 0.8.12 offers integer options but no
public float/double slider builder. The desired change is continuous storage
selection rather than a small list of predetermined sizes. Reuse its normal
slider with a high-resolution normalized position, and compute byte values using
`double`. Persist whole-byte `long` allowances, since files and accounting use
whole bytes. Add no custom Sodium screen, internal-widget mixin, new dependency
or floating-point cache-file accounting.

1. Replace `CacheStorageOptions.choices`, `storageChoices()` and the TreeSet /
   binary-search preset implementation with a captured numeric mapping in the
   same owner. Remove obsolete imports and helper methods.
2. Use a normalized range `0..2^20` with step 1; reserve only the final coordinate
   for Entire world. Numeric positions interpolate smoothly; the coordinate
   precision exceeds the widget's visible pixel resolution. This is UI precision,
   not a request, work, memory or disk budget. There are no preset detents/snaps.
3. The finite numeric maximum is:
   `max(500_000_000, current finite allowance, estimatedWorldBytes)`, bounded below
   `Long.MAX_VALUE`. The minimum stays 100,000,000 bytes. This keeps the 500 MB
   default available for tiny/unknown worlds and preserves saved allowances above
   a temporarily smaller estimate. Entire world stays a separate sentinel.
4. Capture the current server identity, finite maximum and saved value when the
   option binding loads/rebuilds. Freeze them while the user edits. Refresh the
   mapping on reopening/rebinding; do not let asynchronous estimates move the
   thumb or reinterpret an unapplied choice. Entire world continues to grow in
   the existing download/storage policy regardless of this UI snapshot.
5. Preserve exact minimum, maximum, 500 MB default and the current saved allowance.
   Use at most four distinct byte anchors and piecewise linear interpolation,
   with strictly increasing normalized positions. These anchors guarantee exact
   required values; they do not make selectable presets or cause snapping.
   Assign positions monotonically, reserving room for remaining anchors, to handle
   rounding collisions even for very large estimates. Matching byte values share
   an anchor. The finite maximum, default or current value may coincide.
6. Convert an ordinary numeric position through floating-point interpolation,
   round once to whole bytes and clamp to the finite range. Explicit anchor
   coordinates return their exact bytes. Clamp finite results to
   `Long.MAX_VALUE - 1`; only the final endpoint selects `Long.MAX_VALUE`.
   Do not accidentally turn a rounded large numeric allowance into Entire world.
7. Format numeric values as decimal MB/GB with fractional values using Locale.ROOT;
   keep the current one-decimal display unless more precision is needed to avoid
   a misleading boundary label. The default displays 500.0 MB. The endpoint
   displays Entire world, with no numeric estimate forced into that label.
8. Preserve exact saved bytes on an unchanged/rebound option. Sodium can write a
   validator-corrected value during binding reset before Apply, so the getter must
   already return a valid, lossless coordinate. Do not call `setStorageBytes()`
   from range, label, getter, estimate or default-provider code.
9. Applying verifies the captured server still matches the current selected
   policy and is available. A server change requires rebinding/abandoning the old
   edit; never write an allowance to a different server. Retain `storageSelected`
   and `retainStorageBytes()` semantics, existing Apply/Cancel/storage handlers
   and the STREAMING_SETTINGS hook; no renderer rebuild or cache reset.

Mapping/formatting takes O(1) time and space, with at most four anchors. It does
not traverse cache regions, alter eviction rank, change VRAM loading or add work
per downloaded section.

## 5. Five-Mbps default and end-to-end twenty-Mbps support

The current menu is not the only 10 Mbps limit. Update all existing owners in the
same implementation, keeping their fields and wire widths:

| File | Required change |
| --- | --- |
| `ServerDownloadSettings.java` | DEFAULT_KBPS=5_000, MAX_KBPS=20_000; retain minimum 100; correct setter error text. Loading/range validation uses the expanded range. |
| `VoxyConfigMenu.java` | Inherit updated limits/default; preserve the existing 100 kbps bandwidth step and decimal formatter. |
| `shared/src/main/.../QuicEndpointPayload.java` | Accept up to 20_000 during discovery/route authentication. |
| `RegionalProtocol.java` | Accept up to 20_000 in OPEN/SETTINGS validation. |
| `rust-server/src/server.rs` | Authenticated route registration accepts 100..=20_000. |
| `rust-server/src/regional/wire.rs` | Streaming settings accept 100..=20_000; update error text. |
| `shared/src/debug/.../DebugTestCommandPayload.java` | Bandwidth debug validation accepts 20_000. |
| `rust-server/src/live_pressure.rs` | Correct bandwidth help/range and default cap to 5_000; align its default interval with 2,000 ms. Do not run pressure testing. |

The discovery field is an unsigned 16-bit quantity; 20,000 already fits. No wire
layout/version change, alternate reader, old-client mode or extra pacing layer is
needed. Preserve the actual-traffic ledger and byte conversion. Audit targeted
references for obsolete 10 Mbps/1 Mbps descriptions; do not replace unrelated
10,000/1,000 constants or rewrite historical audit evidence.

## 6. Implementation, rollout and verification

Implement interval/tooltips first, then the storage mapping, then the matched
Java/shared/Rust bandwidth expansion. Review default/reset, unchanged-value,
server-switch, tiny-world, large-estimate and Entire world boundary behavior from
source before deployment. Keep changes in existing source owners. Count source
and artifact deltas using the same ledger as build 259; no source-file addition or
runtime budget is expected. Do not promise a binary-size delta before building.

Build normal/debug client and bundled server artifacts using the existing
`buildAll` assembly task, which builds the release native backend. Do not invoke
unit/integration/synthetic/self-test tasks. Record hashes, native embedding and
actual loaded identities. The expanded limit requires a matched controller/shared
payload/backend update: deploying only the client would leave the old backend
rejecting 20 Mbps.

Prepare rollback artifacts and one bounded live script before testing. During
implementation, deploy only the existing Voxy_Testing instance and real PC,
with the native/backend update and Java controller ready before publishing the
client updater feed. Use the existing supervised lifecycle and its memory limits;
no second validation restart or new tunnel/helper process is needed. Keep both
independent PC SSH backups alive. Preserve client cache, catalogs, identities,
world files, settings selections and unrelated mods. Check pending updater reset
and profile requests before publication. Original Voxy repositories and Main
remain unchanged.

Carry forward the previous testing limit: **target five minutes, absolute ten
minutes total on one non-resettable clock**, starting before the first live
preflight. Include SSH checks, deployment/restarts, updater/startup waits, settings
screenshots and restoration. Reserve the final portion for restoration; skip
lower-priority observations if startup consumes the allowance. No laptop,
100-client, pressure, unit or integration tests. After clock closure analyze only
preserved receipts/logs.

The short real-PC check should establish, where time permits:

1. Actual client/controller/native identities; both backup routes and unchanged
   JVM 1–4 GiB/native hard ceiling (`memory.max=999997440`, swap 0).
2. Interval option absent; concise tooltips inspected; no address/testing references.
3. Storage drag passes decimal intermediate values between former presets; Reset
   selects exactly 500 MB; Apply/reopen preserves the chosen number; Entire world
   is the far-right endpoint. Cancel and unchanged-menu rebuild do not alter policy.
4. Bandwidth Reset is 5 Mbps; 20 Mbps applies and reaches endpoint registration and
   Rust without range/disconnect errors. Restore original selected allowances
   afterward through the current settings policy without rewriting unrelated data.
5. Existing cache remains usable, normal terrain draws continue, and normal
   refresh settings sent by the client contain 2,000 ms. No renderer reset is
   triggered by changing bandwidth/storage.

Default-new-policy behavior and extremely large/unknown estimates can receive
source review if the existing live server cannot exercise them naturally. Do not
clear policies, exhaust disk, lower storage enough to cause eviction, create a new
fake server identity or change worlds to force coverage. Changing storage applies
the real eviction policy; keep any live chosen allowance above actual cache usage
and verify the 500 MB default/reset action without committing a destructive lower
cap. Record skipped checks and any unmatched comparison. This settings change
needs no FPS benchmark or additional load scenario.

Save an implementation report and scoped screenshots/receipts under project
audit, stating actual elapsed testing time, restored controls and observed
limits. Commit locally on this feature branch. This document changes the plan
only; it does not authorize modifying the original repositories or Main.
