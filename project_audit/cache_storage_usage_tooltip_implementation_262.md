# Restore Cache Storage usage — client 262

Implemented 2026-10-06 on `feature/cache-first-background-updates`, source commit
`7e871af8`. The short Cache Storage description is unchanged. Its bottom line now
shows **Cache used: <amount>**, using the existing decimal MB/GB formatter and
localized text. It counts the current server's actual cache-file bytes across
all its dimensions, including journal/metadata records.

The tooltip reads the existing maintained `RegionalDiskBudget.Account.bytes`
counter through a synchronized expected-O(1) lookup. There is no filesystem scan,
free-space probe, recovery attempt, extra counter or runtime polling. Session
metadata is volatile for safe publication to the UI; an exact server-identity
check prevents displaying another active session's usage. Partial/failed/closed
inventory or ambiguous/missing ownership displays **Cache used: unavailable**
instead of an invented zero. Disk-full/quota pauses still display known usage.

Source review of the installed Sodium 0.8.12 bytecode confirms that `getTooltip()`
calls the provider and continued hover regenerates tooltip content. The footer
therefore refreshes from current accounting while hovered. This is source/API
verification, not an observed hover screenshot.

## Build and deployment

The first compilation failed because `Component` exposes no `append(String)`.
Using `copy()` supplies the mutable component; packaging then succeeded. A final
comment correction was rebuilt before publication. The final
`./gradlew jar debugJar --offline --console=plain` succeeded in four seconds;
no test tasks ran. Source review found no material issue. No runtime cache/wire
format or backwards-compatibility path changed.

| Client artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| Debug 262 | 4,080,496 | `ad0f501329f7b2c7ef8b4c8c623d4199e5a3f5f7c4dcc4f5c8bc679974ce69f4` |
| Normal 262 | 3,925,094 | `ea6f2252022dd99775bd7133abc22ae902dff6ea1d5f1e130526b0e0b801a118` |

Debug/normal jars grew 687/683 bytes from 261. Three existing Java files changed:
**+19 physical source lines**, including comments/blanks, with no new source
files/folders. The English resource adds two translation entries and the existing
artifact version increments for auto-updating. No server/native source changed.

The debug artifact was atomically published to the existing MGengine updater feed.
GIORKOSPC automatically restarted once, PID **23216 → 5608**. Its installed SHA
and current log header verify loaded debug 262; the final regional pipeline was
ACTIVE, with accepted HELLO, inventory READY and null session failure.

## Brief real-PC verification and limits

The single clock ran **03:48:27.004859 → 03:51:16.299411 UTC**,
**169.295 seconds**, including SSH checks, publication/update/startup and closure.
No integration, unit, synthetic, fake, laptop or 100-client testing ran. No further
live queries/tests occurred after closure; this report uses preserved receipts.

- Current user selections remained **20 Mbps / Entire world**. General config
  hash was unchanged. World estimate/player anchors updated normally, so the
  complete server-policy JSON was not byte-identical.
- Existing cache was preserved; no reset, namespace change, transport hold,
  disk-filling, quota change or terrain mutation was requested. Preflight file
  size was 1,556,481,695 bytes; final inventory accounting across all accounts
  was 1,560,978,839 bytes with zero unowned bytes. These figures are not an
  observed per-server tooltip value or a performance comparison.
- Backup helper PIDs **19916 / 22444** stayed unchanged and both pinned SSH
  routes responded before and after deployment.
- Controller/native 260 stayed at PIDs **306308 / 307526**, with identical start
  ticks, deployed jar/native/config/unrelated-mod hashes. JVM heap remained
  1/4 GiB, native `memory.max=999997440`, swap max zero and all max/OOM/OOM-kill
  events zero. Final sampled native accounted memory was 474,636,288 bytes.
  Main and the original Voxy repositories were unchanged.

Actual tooltip hover/layout was **not verified through a new screenshot**. Native
foreground input was unavailable in the prior UI run; this small change did not
add another input mechanism or repeat that failed experiment. Display composition
and dynamic-provider behavior received source/API review; live checks verified
actual deployment, startup and preservation only. No speedup claim is made.

[Compact receipt](cache_storage_usage_tooltip_live_262.json). Full local receipts,
previous artifact/settings preservation and helper scripts remain in
`project_audit/deployment/cache262/`; only this report and the compact receipt
are committed. Build artifact:
`build/libs/ASMP_voxy-0.2.262-beta+1.21.1-neoforge-debug.jar`.
