# Voxy reimplementation plan

Date: 2026-10-03 UTC.
Status: Implementation and matched-load verification in progress.

Validation policy, updated by the user: no integration tests or automated test
suites. Remove the existing integration tests from this rewrite repository. Use
only live testing with the user's real player and the 100 virtual Voxy clients;
compilation and artifact inspection remain part of building releases. Earlier
isolated diagnostics are historical evidence, not the current acceptance method.

## Objective

Increase throughput and efficiency while keeping production code as small and
readable as possible. Demonstrate that the backend handles the Voxy workload of
100 players spread around the map while the world receives 300 block changes per
second, without connecting 100 Minecraft players. These loads must run
simultaneously. The change rate is aggregate across the shared world.

All 100 virtual clients must also have poor network connections: seeded packet
loss between 50% and 90% per client, a 3 Mbps link, and at least 1,000 ms round-trip
latency. Apply impairment to real UDP packets, including QUIC handshakes and
retransmissions, rather than delaying application replies. Model 500 ms one-way
delay and 3 Mbps in each direction, record achieved conditions and transport
failures, and run the same impairment against both backends. Cached clients must
keep loading locally through these conditions. Fast loopback runs remain useful
diagnostics but do not satisfy this acceptance case.

Prioritize complete terrain coverage and fast delivery of high-quality detail.
Terrain freshness is secondary: clients may receive coalesced refreshes roughly
once per second or less often. There is no requirement to deliver each block edit
immediately or to publish one terrain generation per edit.

Reduce reliance on the Voxy server as much as possible. Locally valid cached
terrain must start loading immediately, independently of server availability,
endpoint discovery, handshakes, metadata refresh, or freshness confirmation.

Remove custom memory reservation managers, byte budgets, per-stage quotas, and
arbitrary operating-memory targets from the rewrite design. Achieve low memory
use through ownership, streaming, reuse, shared data, and avoiding obsolete work.
Do not replace those managers with equivalent accounting under different names.

Keep the previously requested external testing kill guard as a separate
operational test condition. It adds no budget machinery to the implementation.
Main remains read-only. Simulator runs and derived-data experiments belong to
isolated testing instances or fixtures; preserve source worlds and unrelated data.

## Coverage, quality, and refresh priorities

1. Start usable cached terrain immediately, with no Voxy-server prerequisite.
2. Fill missing visible coverage first, using coarse terrain where needed.
3. Progress to the requested high-quality detail as quickly as possible.
4. Refresh already covered terrain in the background, accepting older snapshots
   while preserving coverage and detail.

Refresh cadence applies to changes in terrain that is already available. New
terrain requests, cache hits, and refinement requests must not wait for that
cadence before being processed. Serving a usable previously published generation
must not wait for a newer generation to finish rebuilding.

Coalesce changes into the latest useful snapshot per affected area. Track dirty
coordinates or current generations rather than accumulating a job for every
block edit. Use a simple refresh cadence; avoid adding a separate adaptive
freshness or budget framework.

Preserve usable geometry until its replacement is complete and GPU-published.
Refreshes must not create holes, clear valid cached coverage, or temporarily
reduce available detail just because newer source data exists. Repeated edits
must not continually cancel otherwise useful cache-loading or refinement work;
finish a consistent admitted snapshot where it still serves the current view,
then replace it through a later refresh.

Measure coverage against saved terrain available in the test dataset and the
specified view. Track initial cold-loading gaps separately from holes introduced
by refinement or refresh. Report genuinely unavailable or ungenerated terrain
separately, rather than counting it as delivered coverage.

## Architecture to retain

- A small Java NeoForge server bridge supervises a separate Rust backend and
  advertises its endpoint through the Minecraft connection.
- Rust reads saved Anvil terrain, builds shared LOD generations, and serves
  compressed voxel sections. Minecraft world files remain authoritative; Voxy
  does not generate Minecraft chunks.
- The Java client resolves Minecraft models, meshes sections, and renders them.
- The client cache supplies terrain independently of the Voxy connection. The
  backend fills cache misses and supplies coalesced changes in the background.
- Direct spatial lookup and atomic generation publication remain useful storage
  properties. Existing readers can finish against their original generation.
- Coarse terrain stays visible until its finer replacements are complete and
  their GPU publication fence has passed.
- Keep automatic updates separate from terrain processing and rendering. The
  existing rewrite repository currently contains the updater bootstrap.

## Cache-first client path: hard requirement

As soon as the client has the relevant Minecraft server/world and dimension
context, start loading the matching local terrain. Do not wait for the Voxy
endpoint, QUIC connection, server HELLO, remote catalog, regional index, generation
comparison, or freshness check before decoding, meshing, or displaying cached
sections. Local disk reads, validation, model preparation, meshing, and GPU
publication still take time; there must be no server-dependent wait in this path.

- Persist enough local context to discover and interpret the cache: its namespace,
  last-known world association, dimension, spatial section metadata, format
  version, and any required catalog or palette. A cached section and its decoding
  dependencies must remain usable together after a client restart.
- Validate cached data locally. Use Minecraft's local registries and models to
  interpret it. A server catalog fetch must not be required to decode previously
  cached terrain.
- Open the relevant local indexes on demand. Do not require a whole-cache scan,
  unrelated recovery work, or a metadata persistence flush before useful cached
  sections can begin loading.
- Start network discovery and reconciliation independently. Refresh failures or
  an unavailable backend leave locally valid cached terrain usable. Network work
  must not hold a lock or worker handoff that cached loading needs to proceed.
- Request section payloads for missing, locally invalid, or server-confirmed
  changed content. Unchanged cache hits do not trigger duplicate terrain downloads.
  Prefer change checks for the currently needed area over a full-world inventory.
  Missing coverage and useful refinement take priority over refreshing existing
  terrain; confirmed changes can wait for the background refresh cadence.
- Keep cached terrain visible while replacements load. It may represent the
  last-known saved world until background reconciliation supplies newer data.
  Apply confirmed deletions explicitly; a timeout or unavailable region alone
  does not establish that cached terrain should disappear.
- Keep world namespaces distinct. If background reconciliation confirms a changed
  world identity, switch the association explicitly and stop using the previous
  world's cache for that session. Do not blend separate worlds' section identities.

The cache-loading path is the primary path for existing data. Server contact adds
missing or updated data without becoming a prerequisite for local terrain use.

## Decisions on the previous six recommendations

| Previous recommendation | Decision | Revised direction |
| --- | --- | --- |
| 1. Bounded working set with explicit budgets | Replace | Keep the Rust process boundary. Process terrain lazily, reuse worker scratch, and stream results. Remove the arbitrary 750–800 MB target and internal budget accounting. |
| 2. One payload representation | Keep, revise | Reuse validated compressed section bytes across storage, transport, and client cache. Start by testing compact numeric data with its catalog cached alongside it. Cached data and its decoding dependencies must be usable without contacting the server. Do not assume names inside every section improve the result. |
| 3. Ownership and lifecycle | Keep, simplify | Store authoritative state once. Give work a clear owner and minimal cancellation/version information. Avoid generic scheduling frameworks and overlapping state machines. |
| 4. Spatial storage and coverage priority | Keep most | Preserve direct lookup and atomic publication. Read metadata on demand, fill coarse coverage, then deliver high-quality refinement. Refresh existing terrain at a coalesced background cadence. Add custom caches or eviction machinery only when measurements justify them. |
| 5. Renderer milestones | Keep the method | Preserve replacement correctness and GPU fences. Choose meshing and rendering techniques through measurement. Remove the arbitrary upload quota. |
| 6. Existing transport libraries | Keep, simplify | Use Quinn/Kwik, a small protocol, and transport backpressure. Avoid a separate global budget manager. Retain protocol validation needed for decoding correctness. |

## Structural requirements

1. **Build each selected snapshot once, serve repeatedly.** Coalesce source edits
   into shared generations independently of how many clients request them. Keep
   decoding, meshing, and recompression out of the server request path. Serve an
   existing usable generation while its refresh is being prepared.
2. **Keep current demand rather than camera history.** Movement replaces obsolete
   requests. Teleports and disconnects stop unnecessary work promptly. Iterate
   current demand instead of eagerly creating a task for every possible section.
3. **Share immutable data across connections.** Connections and view state are
   individual; catalogs, generation metadata, and indexes should be shared or
   read on demand rather than copied for each player.
4. **Stream through the real consumer.** Await transport writes and hand work on
   when its consumer can accept it. A slow receiver must not cause the producer
   to accumulate payloads indefinitely. Use ordinary worker execution and reused
   scratch instead of spawning unlimited tasks or adding a quota framework.
5. **Persist without redundant transformations.** Make identical validated
   compressed bytes usable for serving and caching. Store the required catalog
   as part of the cached dataset so offline decoding remains possible. Avoid
   mandatory temporary-file staging followed by a second payload copy.
6. **Make complexity measurable.** Report production code size, dependencies,
   allocations, copies, CPU time, and throughput. Remove duplicate representations
   and mechanisms while keeping code readable. Report tests and simulator code
   separately, and compare production size at matching feature scope.
7. **Use local data first.** Cache lookup and loading proceed before remote
   validation. Reconciliation fills gaps and updates existing data in the
   background, with no server wait on cached terrain's path to rendering.
8. **Favor coverage and quality over freshness.** Preserve complete usable
   coverage and finish useful detail work. Batch changed terrain for refresh
   roughly once per second or less often; no per-edit delivery deadline applies.

A shared payload is a goal, not a commitment to the previous named format. The
earlier staged named-payload experiment increased accounted writes by 93.3% and
was about 80% slower under simulated 32 MiB/s write bandwidth, despite improving
the warm local codec/persistence benchmark. That experiment excluded networking,
meshing, GPU work, and end-to-end visible loading. Compare candidate formats
before selecting one, including bandwidth, disk traffic, and catalog lifecycle.

Evidence:
[previous experiment](../../ASMP_Voxy/project_audit/unified_section_payload_prototype_results.md).

## First development tool: 100-client load simulator with 300 block changes/sec

Build a standalone driver that runs 100 lightweight virtual Voxy clients without
launching Minecraft. Each has a position, camera, view distance, cache state, and
movement route. Exercise real QUIC connections whenever a workload needs backend
service; cache-aware workloads must not force server requests for local hits.

Run a deterministic mutation driver alongside those clients at an aggregate rate
of 300 actual block-state changes per second. The combined serving and mutation
workload is the acceptance case; passing separate 100-client and mutation-only
runs does not establish that the backend handles both together. The input edit
rate does not impose an equal client refresh rate. Coalesced, delayed refreshes
are expected; complete coverage and time to high-quality detail take precedence.

Exercise real QUIC and the actual backend metadata, storage, generation, and
section-serving paths. Do not substitute mocked handlers or synthetic byte replies
for the end-to-end comparison. The old backend's dimension-based handshake makes
a standalone baseline driver feasible.

Use recorded real-client section-demand traces where available, complemented by
documented synthetic camera routes over saved terrain. Reuse request-selection
logic where practical. A headless driver must not claim to reproduce GPU-driven
selection merely because it generates section requests.

| Workload | Purpose |
| --- | --- |
| 100 clients spread across distinct saved regions | Exercise little-sharing workloads and world-wide storage access. |
| 100 dispersed clients with 300 block changes/sec concurrently | Prove sustained coverage and high-quality terrain delivery while rebuilds and coalesced refreshes run in the background. |
| Clients clustered together | Verify that shared generation and encoding avoid repeated work. |
| Simultaneous joins with empty caches | Measure startup pressure, coverage latency, and fairness. |
| Walking, flying, and teleporting | Measure sustained throughput and obsolete-work cancellation. |
| Warm client caches and changing terrain | Verify avoided transfers, invalidation, and generation replacement. |
| Fully cached routes with the Voxy backend unavailable or delayed | Verify that local terrain loading starts without discovery, handshake, catalog, or freshness waits. |
| Partially cached routes | Verify that cache hits proceed while missing sections await the backend. |
| Rejoin or client restart with a warm cache | Verify that persisted local context supports loading before the Voxy connection is ready. |
| Mixed fast and slow receivers | Verify that slow clients do not stall unrelated clients or cause accumulating payloads. |
| Bursts followed by sustained movement | Verify recovery and expose latency or retained-state growth over time. |
| Continuous edits with refreshes once/sec and at slower cadences | Verify that older terrain stays usable, detail work completes, and refreshes introduce no holes. |

### Concurrent block-change workload

- Apply real state-changing edits to an isolated test world or saved-world
  fixture. Edits must reach the actual saved-Anvil/source-change path used by the
  backend. Synthetic dirty notifications alone do not constitute block changes.
- Run both scattered edits across regions and concentrated edits in a few active
  regions. Include edits within virtual clients' requested terrain so the test
  exercises delivery of changed data as well as background rebuilding.
- Schedule edits against wall-clock time independently of backend completion.
  Record requested and achieved edit rates, persisted changes, and the number of
  distinct changed blocks, chunks, and regions. Do not silently slow the writer
  when the backend falls behind or count no-op assignments as changes.
- Record source save cadence. Distinguish edit-to-save latency from saved-change
  detection, LOD rebuilding, publication, and client receipt. Ensure that the
  backend is exposed to the intended 300 changes/sec workload; edits that never
  reach saved terrain cannot establish native-backend update throughput.
- Allow normal coalescing of changes into shared generations. The backend does
  not need one rebuild or response per edit. Refreshes may run roughly once per
  second or less often. Validate each derived snapshot against its matching saved
  source and the defined LOD rules, allowing its age to differ from the latest
  edits. Do not require every intermediate state to reach clients.
- Maintain terrain serving throughout sustained changes, then stop edits and
  verify eventual convergence to the final saved state through the refresh path.
  Do not replay a historical per-edit backlog. Mutation processing must not block
  cached-terrain loading, missing-coverage delivery, or useful refinement.
- Use the same seeded edits, routes, save cadence, and duration for old/new
  comparisons. Record refresh cadences and any policy differences explicitly.
  Record mutation-driver resource use and achieved rate so a writer bottleneck
  cannot be mistaken for successful backend capacity.

Use deterministic seeds and routes, the same terrain dataset, and recorded
configuration to compare old and new implementations. If protocols differ, keep
the logical workload identical through driver adapters; do not add production
compatibility machinery solely to support the benchmark.

Camera movement follows wall-clock time even when responses fall behind. Record
unfulfilled demand and latency so the driver does not silently reduce the offered
workload. Separate maximum-throughput receivers from realistic consumption rates.

Record view distance, movement speed, dimensions, terrain coverage, client cache
state, backend derived-data state, and operating-system cache conditions. These
determine how much work 100 connections actually request. Treat missing saved
terrain explicitly rather than counting unavailable sections as successful
terrain delivery.

Run the generator separately from the backend where practical. Record generator
CPU and throughput to identify a driver bottleneck. Loopback runs exercise QUIC
and backend processing but do not establish performance over an Internet path.

### Measurements and success criteria

- Measure aggregate useful section throughput and actual received payload bytes.
- Measure per-client request completion latency, including p50/p95/p99, and time
  to coarse coverage. Report incomplete requests, errors, and starvation.
- Treat coverage and quality as primary outcomes: measure coverage completeness,
  time to the requested detail level, and holes introduced during movement,
  refinement, or refresh. On the real GPU client, verify visibility, seams, and
  complete parent-to-child replacement under sustained edits.
- During the concurrent 100-client / 300-block-changes/sec run, measure update
  cadence and snapshot age as secondary diagnostics, separating save, detection,
  rebuild, publication, and receipt. Older terrain does not by itself fail the
  run. Verify sustained achieved input rate, serving fairness, complete useful
  coverage, fast detail delivery, and eventual final-state convergence after edits
  stop. Retained work must not grow with every intermediate edit. Keep real-GPU
  visibility measurements separate from headless receipt.
- Separately measure cache-hit loading from local demand to decode/mesh readiness
  and, on the GPU client, first visibility. Exercise an absent backend and delayed
  discovery, handshake, catalog, and index replies. Verify that these delays add
  no server-dependent wait to the cache-hit path.
- Fully cached, unchanged routes must cause no section-payload downloads.
  Background metadata checks may run independently. For partially cached routes,
  verify that cache misses do not block cache-hit processing or visibility.
- Measure CPU time, disk reads/writes, allocations, peak backend memory, and
  retained-state growth during sustained routes.
- Verify payload correctness and generation replacement. Compare equivalent
  detail and terrain coverage; reductions in requested quality are not speedups.
  Compare a delivered snapshot to its source generation, accepting intentionally
  delayed refreshes while checking eventual convergence separately.
- Demonstrate sustained service to all 100 clients while 300 block changes/sec
  occur concurrently, plus recovery after bursts and after edits stop.
  Aggregate throughput alone does not establish that individual clients are served.
- Compare results with the old implementation under matched conditions. Select
  optimizations from measured improvements in throughput, efficiency, latency,
  or code reduction; do not invent operating budgets to declare success.
- Preserve raw workload definitions, logs, artifact identities, and reports so
  results are reproducible. Record an external guard termination as a failed run.

The simulator measures server-side Voxy load. Measure client decoding, meshing,
rendering, frame time, visual correctness, and publication on the real player's
client. Do not run separate integration or headless test suites, and do not infer
client FPS from the server load test. Verify immediate cache use while its Minecraft
connection remains active and the separate Voxy backend is unavailable.

## Implementation sequence

1. **Baseline simulator:** exercise the old implementation and capture matched
   workloads, results, and artifact identities before implementing optimizations.
   Include cold, warm, partial-cache, backend-unavailable, and simultaneous
   100-client / 300-block-changes/sec cases. Exercise coalesced refresh cadences
   and measure coverage and detail delivery before evaluating freshness.
2. **Minimal new terrain-serving path:** read saved terrain, build one coarse
   section, publish it, and serve it through the real protocol. Verify a saved
   terrain change replaces the section correctly.
3. **Shared payload and cache path:** compare candidate representations, then
   implement storage/transport/cache byte reuse and offline catalog availability.
   Establish cache lookup, local decoding, and background reconciliation as
   independent paths. Prove warm-cache readiness without a Voxy connection before
   relying on this architecture for the renderer.
4. **Client rendering:** mesh and display sections, verify models, lighting,
   seams, cancellation, and coarse-to-fine replacement. Expand rendering features
   with comparable visual and performance checks. Demonstrate cached terrain
   becoming visible with the Voxy backend unavailable, plus nonblocking refresh
   when it returns. Prove that repeated edits and slower refreshes preserve
   coverage and do not prevent useful high-quality detail from completing.
5. **Measured optimizations:** rerun the 100-client workloads, including concurrent
   300 block changes/sec, after significant changes. Add parallelism, caching, GPU
   techniques, or abstractions only when evidence justifies their complexity.

Transport reference:
[Quinn awaited writes](https://docs.rs/quinn/latest/quinn/struct.SendStream.html#method.write).
