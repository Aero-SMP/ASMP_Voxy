# Terrain streaming: cached-subtree pruning and capacity checks

Status: implementation plan only, updated 2026-10-07. The user explicitly requested
this plan update and instructed **do not implement yet**. No source implementation,
build, deployment or live testing was performed while updating this document.

Repository: `/home/aerosmp/Desktop/ASMP_Voxy_Cache_First_Updates`.
Branch: `feature/cache-first-background-updates`.
Reviewed baseline: `013e0cee1f0316f5ba977f5611644476f71c3415`.

Implement **only the original two fixes below**, after implementation is separately
requested. Reuse existing maintained data. Add no production index, cache, counter,
dirty-generation scheme, queue, executor, worker reservation or runtime budget.
Local variables used during a call are acceptable; retaining another copy of
world/demand state is not.

| Change | Complexity | Expected benefit | Unavoidable tradeoff severity |
| --- | ---: | ---: | ---: |
| A. Prune fully cached download-search subtrees | +1 | 7 | 0 |
| B. Stop counting waiting jobs once capacity is exhausted | 0 | 2 | 0 |

Scores are estimates: complexity measures net source/file/folder/binary growth,
not implementation difficulty. Benefit and tradeoff severity use 0–10. Actual
CPU, allocation and useful-arrival gains require live measurement.

## 1. Evidence and scope

Both changes remove work confirmed in the current source:

- `next()` expands cached parents even when the existing completeness summary
  could certify that all eligible finer descendants are already cached.
- Background admission needs a threshold decision, but currently counts every
  waiting job before comparing it with the number of idle workers.

These findings establish unnecessary work, not a measured download-throughput
speedup. The previously recorded refresh-eligibility CPU hotspot is outside this
plan: neither fix changes that calculation. Do not attribute its cost to these
fixes or claim that they remove it.

Current source anchors:

- [WorldCacheDownloads.java](../src/main/java/me/cortex/voxy/client/lod/WorldCacheDownloads.java):
  `Coverage.full` and its maintenance at 106–137; inventory rebuild/reoffer at
  359–434; `waiting` at 643; cached-parent expansion at 668–673.
- [ClientSession.java](../src/main/java/me/cortex/voxy/client/lod/ClientSession.java):
  spare-capacity admission at 2546–2575.
- [RegionalDiskBudget.java](../src/main/java/me/cortex/voxy/client/lod/RegionalDiskBudget.java):
  per-region incarnation invalidation at 429–435 and eviction stamp at 456.

The expected implementation touches the existing client planner/session files.
Reuse existing debug diagnostics for measurement. No new production source file,
folder or dependency is expected. Keep source readable; measure the actual delta.

Preserve cache-first/offline display, pixel/zoom demand, score/purpose/tie ordering,
refresh policy and its 2-second interval, bandwidth/storage settings, disk pause
and eviction, catalogue and integrity checks, model/mesh output, renderer/VRAM
selection, publication fences, worker leases, DROP ordering and foreground gates.
Refresh-eligibility restructuring, coarse-group frustum rejection, traversal
selection/fallback machinery and any replacement third optimization are excluded.

Main, ASMP_Voxy and ASMP_Voxy_Restart remain read only. This client-only work needs
no server restart or source-world modification. Preserve the PC's normal cache,
settings, identities and unrelated mods. Verify both PC backup SSH routes before
any subsequently authorized client deployment. Keep the existing JVM arguments
and native external ceiling.

## 2. Change A: prune complete cached subtrees

Use the existing `Coverage.full` set after obtaining the directory through the
current `directory()` path. That path validates incarnation and handles missing
or invalidated summaries. A cached parent binding alone is not proof that its
finer descendants are cached.

1. In `next()`, distinguish a complete subtree from a merely present binding.
2. If the current key is in this directory's coherent `full` set, do not call
   `dimension.expand(node)`. Keep the current cached-hit accounting, wake and
   owner-yield behavior; this change does not introduce a multi-candidate walk.
3. If the binding is cached but the subtree is incomplete, retain current
   expansion and yield. Its finer misses must still enter the frontier.
4. Keep foreground-owned miss handling and missing-directory waits unchanged.
5. Do not remove descendants from the priority queue with `remove(Object)` or
   add a descendant index. Descendants queued earlier remain subject to the same
   check when popped. Do not claim O(1) removal of already queued descendants.
6. Preserve inventory-growth rebuilds, region reoffers, commit propagation,
   physical eviction, unreadable/source-blocked handling and dimension teardown.
   A formerly complete subtree must become searchable when eligible saved terrain
   grows or cached data is invalidated.

A fully populated LOD4 subtree has up to `1+8+64+512+4096 = 4681` nodes. A coherent
full-summary lookup avoids generating/walking its descendants. This is an
expected O(1) decision per complete subtree, not O(1) loading of the entire world.
Sparse heights and saved slots reduce the actual count.

There is no intended behavioral tradeoff or new maintained state. Incorrect or
stale completeness use could hide missing downloads; retain the existing
incarnation/inventory safeguards rather than weakening them.

## 3. Change B: short-circuit the existing waiting-job count

`selectCacheDownload()` needs only whether waiting jobs are at least the number
of idle workers. Its current caller counts all waiting jobs first.

1. Preserve every existing ownership, cancellation and foreground eligibility
   check before the capacity decision.
2. Return no new background request immediately when `available == 0`.
3. Otherwise inspect existing jobs and stop once `available` non-processing jobs
   have been found. Return the same decision as `waiting() >= available`.
4. Replace the full-count hot-path method with a threshold predicate. Audit all
   references, including non-production sources, so removing it leaves no stale
   compile references. Add or run no tests and retain no duplicate production
   counting helper solely for fixtures. Do not maintain another waiting counter
   or job index. Preserve waiting-versus-processing semantics.
5. Keep one new background request per owner turn and existing send-refusal
   rollback. This does not change worker counts or admit a batch of new requests.

Worst-case traversal remains O(J); a proven-full decision stops after enough
waiting jobs have been seen. Zero capacity is O(1). No behavioral tradeoff or
additional retained state is intended. The expected improvement is modest.

## 4. Implementation order and validation

The following steps describe future implementation; they are not authorization
to implement, build or operate the live client while only updating this plan.

1. Recheck HEAD, dirty state, source hashes, actual PC client and server/native
   artifacts. Use the pinned Voxy toolkit/MCP for source/provenance/evidence;
   poll returned jobs instead of replaying them. Preserve concurrent work.
2. Capture a baseline on the real PC with existing diagnostics. Record cache
   state, view/settings, owner work, frontier progress and worker/lane activity.
3. Implement A and B in existing files; compile/package normal and debug clients
   through the toolkit. Add or run no unit/integration tests or fixtures. Keep
   existing non-production compile references consistent with the method change.
4. Verify artifacts, stage a hash-verified client rollback, confirm both backup
   SSH routes, and use the existing debug updater. Verify the loaded process and
   artifact, not just updater publication. Retain the server/native build.
5. Compare baseline with the A+B candidate using matched live observations.
   Repeat comparisons when natural cache/world/host changes are confounders.
6. Exercise warm cache, partially cached saved terrain, movement/zoom, dimension
   transitions and existing cache-first offline use on the real PC. Do not clear
   the normal cache to manufacture a cold workload. Any controlled cache
   preparation must preserve the original and respect actual disk free space.
7. Record screenshots and client logs for cache hits, missing requests, activation
   and cancellation. No 100-client run is required for these Java-only changes.
   A 30-client Rust transport test does not execute these client implementations
   and cannot substitute for PC verification.

Testing has no inherited five/ten-minute cap or single global test clock. Keep
individual tooling jobs finite for ownership/recovery and validate as needed once
implementation is requested. Watch host memory/disk and keep existing external
ceilings. Add no production resource/request budget.

### Acceptance gates

- Complete cache subtrees stop generating descendants; incomplete cached parents
  still discover finer misses. Existing queued nodes remain correctly handled.
- Live cache commits, saved-terrain growth, eviction and invalidation make missing
  descendants discoverable again through existing rebuild/reoffer paths.
- Capacity decisions match the old count for zero/all-idle/all-busy and mixed
  processing/waiting states; foreground work and DROP ordering remain unchanged.
- Preserve rendering, cache-first/offline use, bandwidth/storage behavior and
  required-terrain demand during movement, zoom and dimension transitions.
- Measure owner CPU/work per turn, allocation, frontier progress, useful arrival
  and activation, and worker/lane utilization. Distinguish payload throughput
  from actual traffic; do not add concurrent worker timings as sequential wall
  time. Report any unexercised conditions without presenting them as passed.
- Keep refresh eligibility and frustum traversal unchanged. There is no third
  implementation, larger-benefit comparison or fallback-selection requirement.

## 5. Completion record and publication boundary

After implementation is requested and completed, write results to a separate
report with source/artifact identities, actual line/file/folder and binary deltas,
retained-state delta, matched live measurements, screenshots, failed attempts
and unresolved coverage. State loaded PC, server/native and backup-route outcomes
separately from build success.

Do not bypass the earlier automatic-review rejection of GitHub publication.
Save work locally on the feature branch; do not push or merge to Main/original
repositories. Only this plan document was changed during this update.
