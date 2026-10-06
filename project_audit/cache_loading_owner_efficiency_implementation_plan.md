# Reduce repeated cache-loading owner work

Status: implementation plan only; no runtime changes implemented.
Written 2026-10-06 against `336b8cba6963017a1549fff3f43f1c311b99a7bb`.
Repository: `/home/aerosmp/Desktop/ASMP_Voxy_Cache_First_Updates`.
Branch: `feature/cache-first-background-updates`.

Implement the user's selected approaches **1, 2, 4 and 6** from the efficiency
ranking. The numbers below retain that ranking, rather than renumbering them.

## 1. Intended result and boundaries

Reduce repeated topology notifications, temporary detail queues, unchanged
publication scans and individual GPU-feedback wakeups. These changes remove work
and synchronization traffic, while preserving useful updates and their ordering.

Keep worker counts, owner phase order, the wake protocol and ten-millisecond
fallback wait, priority rules, publication ownership, GPU fences, worker lease
release conditions and save acknowledgements intact. Keep cache/wire formats,
integrity validation, download/storage controls and existing client data intact.

This plan does not implement the larger refinement fast path, empty-result early
worker release, per-publication event queues, decoding scratch, name caches or
mesher changes. In particular, the measured empty-result lease hold remains a
separate optimization. Add no executor, dependency, unrestricted queue, runtime
budget, alternate implementation or compatibility path.

Keep Main, ASMP_Voxy, ASMP_Voxy_Restart and the deployed testing server untouched.
Future client deployment uses the existing debug updater and preserves the PC's
cache, settings, identities, other mods and both independent backup connections.
Work on the feature branch; its established branch instruction supersedes the
repository's generic instruction to publish plans to main.

## 2. Evidence and expected improvements

Baseline: [client265 measurements](cache_loading_timings265_results.txt).

| Observation | Opportunity and limit |
| --- | --- |
| 608,916 refinement actions and 2.7288 seconds of DETAIL_REFINE in a 9.0173-second loading window | Remove unchanged dependent fanout. This plan still performs child ensuring and genuine priority updates. |
| 9,686,191 publication checks; 9,652,622 still pending; PUBLICATIONS elapsed 1.7060 seconds | Skip scans without a relevant change. Progress-bearing turns still perform the existing full pass. |
| 93,312 owner turns during the 21.0294-second cache fill | Current detail processing constructs one bucket array, 32 deques and 32 backing arrays per call: roughly 6.1 million source-level allocations. JIT elimination and actual heap allocation were not measured. |
| One mailbox merge and owner signal per GPU detail action | Deliver one bounded readback as a batch, reducing lock/wakeup traffic while retaining every accepted action. |

Topology fanout changes from work proportional to refinement events times
dependents to work proportional to real topology changes times dependents.
Detail processing remains linear in drained actions, but its bucket containers
are allocated once per session. Publication polling changes from turns times
pending references to progress-bearing turns times pending references. Feedback
inspection stays linear in actions; mailbox acquisition and signaling become
per-readback operations. No end-to-end speedup percentage is promised.

## 3. Approach 1: suppress notifications for unchanged state

Primary files:

- `src/main/java/me/cortex/voxy/client/lod/ClientSession.java`
- `src/main/java/me/cortex/voxy/client/core/rendering/hierarchical/SectionPublicationState.java`

### Required-child topology

1. Make the two-argument `addDemand(key, bucket)` report whether it adopted a new
   demand, using its existing lookup. Keep every existing availability, priority,
   coverage, dependency and source-binding operation. Existing callers can ignore
   the result. Do not add another child-map probe or allocate a result object.
2. In `addChildren()`, remember whether `childrenRequired` changed false to true.
   Keep the existing child-mask loop and every `addDemand()` call; accumulate
   whether any required child was newly created or restored.
3. Call `topologyChanged(parent)` when either the required flag changed or a
   required child was newly adopted. Suppress the notification only when the
   required topology is unchanged.
4. Preserve explicit notifications for active child-mask changes in `activated()`
   and for retirement/coarsening. Preserve updates when an empty section becomes
   active. Do not globally suppress `topologyChanged()` based only on a flag.
5. Keep refinement epoch advancement after successful processing, and keep
   missing-content and failed-expansion reoffers unchanged.

A child may disappear while its parent still has `childrenRequired=true`.
That parent must still repair the child and notify dependent watchers. This is
why a bare return on that flag is outside the plan.

### Repeated publication closure

1. In `SectionPublicationState.close()`, determine under its existing monitor
   whether closure changes state. Preserve retirement claiming and exactly-once
   retirement ownership.
2. Notify `stateChanged()` for the actual closure transition, or a newly requested
   retirement, rather than every repeated close of an unchanged publication.
3. Keep retirement submission and callbacks outside the publication monitor.
   Keep `completeUpload()`'s independent late-activation retirement logic intact.
4. Do not extend this change to `markRetired()`, `rendererStopped()` or outcome
   ownership. Repeated `abandon()` must retain its exactly-once disposal behavior.

Implement this before approach 4: repeated stale-reference closure currently
produces progress notifications itself and could defeat a scan guard.

## 4. Approach 2: reuse the owner's detail buckets

Primary method: `ClientSession.Session.drainDetailMailbox()`.

1. Allocate the existing 32 `ArrayDeque<DetailEvent>` buckets once in the Session.
   They belong exclusively to the owner thread; producers still use the mailbox.
2. Reuse them for the existing passes, preserving this exact order:
   mailbox collection; ascending-bucket dormancy/wake; dormancy eviction;
   descending-bucket refinement. Keep existing encounter order within a bucket.
3. Put collection and all processing inside a `try/finally` that clears all
   retained buckets. They must be empty between drains, including exceptions.
4. Preserve current early-exit semantics. On failed expansion, only the failed
   event is reoffered, the remainder of that bucket is discarded, and lower
   buckets continue. Reuse must not replay the discarded local events next turn.
5. Never clear the shared mailbox in this cleanup: it holds explicit retries
   and new concurrent updates. Preserve unsigned-epoch coalescing when an older
   retry races a newer producer update.
6. Keep dormancy eviction running even when there are no detail events.

`clear()` releases event references but retains deque backing capacity until
the session ends. That peak-capacity retention is the intended memory tradeoff.
Do not add a speculative shrinking policy or new cap. One `DetailEvent` per
drained update remains; this is not a zero-allocation processing path.

## 5. Approach 4: guard unchanged publication scans

Primary methods: `rendererWake`, `pollPublications()`, publication submission,
demand invalidation/retirement and `release()` in ClientSession.

### State and scan protocol

Add only:

- An atomic generation incremented by renderer progress notifications.
- An owner-only last-scanned renderer generation.
- An owner-only publication-dirty flag, initially true.

Change the existing final `rendererWake` callback to increment the generation
before invoking `signal()`. Preserve its identity for listener unregistration.

At the beginning of `pollPublications()`:

1. Read the renderer generation once.
2. If neither the owner dirty flag nor that generation changed, skip the queue
   pass. Still execute existing blocked-publication retry and health checks.
3. If scanning, consume the dirty flag and remember the observed generation
   **before** processing. Then run the existing bounded pass without changing its
   ownership/accounting logic.
4. Never clear dirty or replace the remembered generation with a fresh read at
   the end. Notifications and owner mutations during scanning must cause another
   pass. If the pass aborts, leave it dirty before propagating the failure.
5. An owner mutation that needs a subsequent pass must preserve/arrange a wake;
   do not silently depend on unrelated feedback arriving.

Do not compare only `publisher.progress()` fields: admission and terminal outcome
can change without changing handoff/topology/allocation/section-ID generations.
Generic renderer progress can still trigger unnecessary passes; this is the
deliberately small scan guard, not per-publication notification dispatch.

### Owner invalidation inventory

Introduce one owner-only helper to mark publication state dirty. Audit these
sites against the current code and mark before clearing relevant identities:

| Site | Handling |
| --- | --- |
| `publishReadyBatch()` | Mark after accepted references are installed. Admission/completion may happen synchronously before the publisher returns; installation occurs after this turn's publication scan. |
| `invalidateNetworkCandidate()` | Mark when preserving/replacing publication identity or changing a published demand's revision/candidate. |
| `retireDetailDemand()` and `retireDemand()` | Mark removal/revision and current/preserved publication changes. Detail retirement deliberately avoids closing every child directly. |
| `drainDemand()` reset, `changeWorld()` and `resetConnection()` | Mark bulk invalidation/preservation. |
| Demand revision/removal/clear paths, including `bindLocal()` | Audit every direct mutation; use a small owner helper where needed to prevent missing a published or preserved reference. |
| Outcome handling inside the scan | Changes affecting other pending references leave dirty set for the next pass. |

Ordinary priority and ready-queue updates do not dirty publication scans. Keep
`retryRendererBlocked()` and the existing renderer failure check in
`scheduleReadyPublications()` independent of the guard.
Coalesce the helper's wake on a false-to-true dirty transition; installing a
batch of accepted references needs one owner dirty mark after installation.

Shutdown ignores the guard: unregister the exact callback, abandon every queued
publication, arrange exact lease/disposal callbacks, then clear the owning queue.
Progress notifications are suppressed while the renderer stops; cleanup cannot
depend on receiving one final notification.

## 6. Approach 6: deliver GPU detail feedback in batches

Primary files:

- `src/main/java/me/cortex/voxy/client/core/rendering/hierarchical/HierarchicalOcclusionTraverser.java`
- `src/main/java/me/cortex/voxy/client/core/VoxyRenderSystem.java`
- `src/main/java/me/cortex/voxy/client/lod/ClientLodClient.java`
- `src/main/java/me/cortex/voxy/client/lod/ClientSession.java`
- `src/main/java/me/cortex/voxy/client/lod/SectionDemandTable.java`

### Borrowed readback and session scope

1. Replace the production per-action listener with one synchronous batch
   listener. There is one production registration; add no fallback listener path.
2. Keep native decoding in the traverser, exposing a small borrowed batch view
   or synchronous reader. The existing buffer bounds input to 32 buckets of
   256 records, at most 8192 actions. Keep unsigned count clamping, bucket order,
   record layout and action filtering. Validate the borrowed extent before reads.
3. The native pointer/view is valid only during the DownloadStream callback.
   It must never enter a mailbox, executor, field or later owner callback. Retain
   only the existing copied key/action/bucket/epoch updates in the mailbox.
4. Capture the listener registration generation, renderer, numeric Session
   identity, existing volatile view revision and detail-mailbox input generation
   when scheduling the readback. Bind one
   target Session for the entire delivery; do not reread `active` per action and
   redirect a suffix into a new session.
5. Listener replacement/disable invalidates earlier scheduled readbacks. Validate
   scope before merging and at the mailbox boundary. Reject stale listener,
   renderer, session, view and input-generation results. No active target means
   no merge and no owner signal. Invalidate the borrowed view in the traverser's
   callback `finally`, including exceptions.

### Mailbox merge and wake

1. Factor the existing per-key merge into a private locked helper that preserves
   newest unsigned GPU epoch, equal-epoch rejection, bounded buckets and overwrite
   accounting. A batch acquires the detail-mailbox monitor once.
2. Decode/merge the bounded readback under that monitor. Restrict the critical
   section to input decoding, primitive checks and mailbox updates. Run no demand
   processing, renderer work, filesystem operation, registry lookup or wake there.
3. Keep the table's mailbox merge API independent of native pointers; pass a
   synchronous reader/visitor from the traverser-facing code.
4. After releasing the monitor, signal the target owner once if any update was
   accepted. Empty, unsupported, stale or entirely rejected batches need no wake.
5. Use `finally` to wake for an accepted prefix even if decoding/merging fails.
   Do not swallow the exception or emit a wake per record. Preserve other
   renderer-progress, worker-completion, fatal-failure and handoff-capacity wakes.

One input generation in the detail mailbox is justified by reset behavior:
`resetDemand()` can clear demands without changing the Session or view revision.
Increment this generation and invalidate/clear queued detail feedback under the
same mailbox monitor on demand clear/reset, world correction and shutdown.
Readbacks captured before that boundary cannot repopulate the cleared mailbox.
World correction currently changes the view but does not clear the detail
mailbox; make this touched-path lifecycle boundary explicit and test it.

Batching retains the existing latest-value coalescing semantics. Feedback may
wait until the current readback is merged, and the mailbox lock is held longer
per acquisition. The 8192-record input bound prevents an unrestricted critical
section. Add no copied action arrays, second persistent queue or frame-delay timer.

## 7. Meaningful verification and diagnostics

Implement tests around production methods and existing injectable Session,
publisher and publication-state contracts. Do not test a copied guard algorithm.

| Area | Required checks |
| --- | --- |
| Topology | First expansion; identical repeat; changed priority; removed-child repair; child-mask change; coarsening/reset; missing-content retry without epoch advancement; correct empty-dependent interests. |
| Close | Repeated and concurrent close; close before/after activation; close racing activation; late admission; shutdown; exactly one retirement/outcome disposal; callbacks outside the publication monitor. |
| Reused buckets | Mixed action ordering; equal-bucket encounter order; reoffer and failed-expansion break; lower buckets continue; newer producer wins over older retry; injected failures during fill/dormancy/eviction/refinement leave no local events for the next drain. |
| Publication guard | Unchanged turns do not query publications; notification with identical PublicationProgress fields; synchronous completion before reference installation; notification before/during/after scan; owner dirty during scan; invalidation/preservation/reset; every terminal outcome; blocked retry while skipped; shutdown without another notification. |
| Batch | Sequential-versus-batched final mailbox equivalence; unsigned/equal epochs; unsupported actions; empty/full/oversized count; one wake after accepted delivery; accepted-prefix exception; concurrent take/merge; renderer/session/view/reset replacement; no native-view use outside callback lifetime. |

Extend existing fixtures where practical. Register an executable test entrypoint
and real task for new owner/batch cases. Some existing ownership fixtures expose
`run()` but have no registered Gradle task; compiling them is not executing them.
Run relevant normal/debug ownership, admission, shutdown and callback lock-order
checks, the existing timing fixtures, and both client builds. Verify no fixture
classes leak into either artifact and debug telemetry remains debug-only.

Use the existing debug-only owner telemetry for a few bounded counters:
topology notifications emitted/skipped; detail batches and accepted actions;
detail-batch signals; publication scan passes/skips/references visited; owner
invalidation events. Keep existing event meanings intact. Update their documented
order fields/analyzer and tests if counters are appended. Add no per-action timing
history or extra routine summary captures. Normal hooks remain no-ops.
Measure bounded batch-merge elapsed/max time once per readback, not per record:
the longest mailbox hold may increase even as total monitor traffic decreases.
Atomic batch merging can change intermediate producer/owner interleavings;
preserve the documented epoch/coalescing/priority rules rather than expecting
the same intermediate prefixes as individually signalled input.

## 8. Implementation order and live acceptance

1. Revalidate branch, HEAD, dirty state and other agents' activity. Implement in
   an isolated checkout if another agent is working; never overwrite their work.
2. Implement approach 1 and its lifecycle checks first.
3. Implement approach 2 and verify order, retries and exceptional cleanup.
4. Implement approach 4 after idempotent close is established.
5. Implement approach 6 and its synchronous lifetime/reset checks.
6. Run the scoped verification above, build normal/debug clients and record source
   and artifact deltas. Commit/push on the feature branch.
7. Use the next unused client patch version. Stage and hash-check the debug jar,
   publish atomically through the target PC feed, and verify the actual loaded
   process/header/hash. Retain compatible server260 without restarting it.
8. Run one finite warm-cache loading verification on GIORKOSPC using the existing
   cache. Target five minutes; all live verification, including retries and cleanup,
   has a hard ten-minute ceiling. Stop new probes with cleanup time reserved.
   Preserve backup routes, settings, request markers and failed-run evidence.

Success requires all ownership/order checks passing and evidence that the chosen
mechanisms changed: no repeated bucket-container reconstruction; no unchanged
topology/close notifications; unchanged publication turns skipped; one owner wake
per accepted GPU readback batch. Confirm useful refinement/activation continues,
pending work drains and no new retained backlog grows after loading settles.

Compare sustained windows with source-ready backlog, not idle endpoints. Record
cache activations/second, owner overall CPU, refinement/publication elapsed time,
publication checks per activation, batch/action/signal counts, paired lease
completion-to-claim and claim-to-reuse, and available allocation/GC counters.
Use the same timing build and comparable viewpoint/cache/settings where possible.
Preserve mismatches instead of claiming a controlled speedup.

Windows phase CPU attribution in265 was quantized and deferred; prefer overall
thread CPU and monotonic paired lease timings. Collector elapsed time is not a
stop-the-world pause measurement. Debug capture overhead remains present.

Acceptance is not merely a changed phase order, successful build or lower polling
count caused by stalled rendering. Report actual throughput and useful work,
intrinsic retained-buffer cost, any regression, and the unchanged empty-result
lease limitation. Store operators/logs/artifacts/receipts outside maintained source
under the existing Desktop tooling area; do not use `/tmp` for task state.
