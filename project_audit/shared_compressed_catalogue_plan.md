# Shared compressed catalogue and validated local reuse

Status: implementation plan, not implemented. Work belongs to
`feature/cache-first-background-updates` in `ASMP_Voxy_Cache_First_Updates`.
Leave `ASMP_Voxy` and `ASMP_Voxy_Restart` unchanged.

This implements approach 1 from
[the uncached-delivery assessment](cache_first_uncached_delivery_options.md).
Estimated scores: complexity **+2**, benefit **9**, tradeoff severity **1**.
These are engineering estimates. The earlier table's tradeoff score of 2 is
superseded: existing streams already wait for catalogue data independently;
sharing a definition does not introduce a new request/acknowledgement round trip.

## Problem and measured opportunity

The current server writes an uncompressed catalogue before a section on every
foreground stream that has not seen that fingerprint. Eight streams can therefore
transfer eight copies of the same definition before useful terrain arrives.
Background delivery has another independent copy.

The captured catalogue contains 4,759 block states and 56 biomes:

| Quantity | Measured bytes |
|---|---:|
| Canonical catalogue | 355,925 |
| Eight existing foreground catalogue frames | 2,847,728 |
| One Zstd level-1 compressed definition, excluding new envelope | 48,096 |

Compression alone reduced the definition by 86.49%. Compression plus foreground
deduplication would reduce this particular cold metadata transfer by about 98%.
This is a byte calculation, not a measured latency improvement. The actual Rust
encoder and final envelope must be measured after implementation.
Receipt: `read_only_timing/catalogue_compression_size_receipt.json`.

The sampled debug247 PC session completed its first fresh section about 128.45s
after the first observed HELLO sample, with roughly 1s sampling uncertainty.
At that point only 5,149 compressed terrain bytes had completed, while 1,240,547
QUIC UDP payload bytes had arrived. Worker timings and idle workers support
delivery before processing as the leading delay in that capture. The entire byte
gap cannot be attributed to catalogues: it also includes control, descriptors,
transport overhead and received retransmissions.
Evidence: `read_only_timing/client_247_20261004T221049Z.json`.
This is incident evidence, not a controlled before/after latency baseline: the
user changed the impairment during the session. The captured Clumsy screenshot
shows 600ms added RTT, 10% loss each way and about 8Mbps each way; exact removal
boundaries are unverified. Section completion also precedes possible geometry
publication. Measure these events separately under repeatable conditions.

Persistent OPEN/DESIRE streams already batch requests and push section records.
Keep that behavior, the direct regional lookup, precompressed terrain bodies,
renderer, mesher and self-contained named section cache.

## Required behavior

1. Render cached terrain immediately, including with the Voxy server offline.
   Reading or validating an optional catalogue must never gate local terrain.
2. Transfer a compressed definition once per distinct required fingerprint on
   the primary connection, shared by all eight foreground streams. A validated,
   already held definition advertised at connection start needs no transfer.
3. Reuse that definition for subsequent sections without a catalogue request,
   acknowledgement round trip or per-section negotiation.
4. A genuinely new block state or biome can produce a new catalogue. Updates
   using already known entries do not require another definition. Keep exact
   fingerprint bindings when older and newer sections overlap.
5. Background-only definitions remain inside the existing actual-network-traffic
   cap, including retransmissions and transport overhead. Urgent missing terrain
   must not wait behind a background-only transfer.
6. Remove superseded raw/per-lane catalogue handling. Introduce no legacy decoder,
   protocol version negotiation, new dependency or terrain-cache migration.

The normal guarantee is one foreground definition, rather than eight copies.
Foreground and background use separate QUIC connections; the exceptional rescue
copy described below deliberately takes precedence over deduplicating every byte
across both transports.

## Current wire contract

Change the existing CATALOG frame to carry the canonical BLAKE3 fingerprint,
canonical byte length, compressed byte length and one complete Zstd frame.
Use Zstd level 1 initially and the existing compression dependencies.
The fingerprint remains over the canonical decoded bytes.

Extend OPEN with one held-catalogue `Hash32`, using zero for none. Advertise it
only after the complete catalogue has passed length, decompression, hash and
`CatalogCodec` validation and its live binding is strongly owned. A fingerprint
stored in a named terrain record alone does not prove that its catalogue is held.
Include the same held hint in the existing background greeting where useful;
do not add another handshake or an unbounded list of old fingerprints.

Retain existing canonical catalogue, entry-count and name-length integrity
limits. Derive compressed and outer-frame limits from
`Zstd.compressBound(MAX_CATALOG_BYTES)` plus the exact envelope, with checked
arithmetic. A compressed frame can be larger than its canonical input.
The current 64 MiB control limit cannot simply be reused as the outer limit.
Catalogue decompression must accept the existing 64 MiB canonical extent;
`RegionalSectionCodec.decompressFramed()` currently delegates to a section-only
4 MiB helper and needs an explicit appropriate bound. Section limits remain
unchanged. These are wire-validation extents, not new operational budgets.

## Server ownership and stream ordering

Extend `CatalogCache` in `rust-server/src/regional/service.rs` to cache one
immutable, compressed, framed definition for the current registry snapshot.
Hash and compress once when that snapshot changes, then share an `Arc` across
sessions and prepared sections. Release obsolete encoded snapshots when their
existing send/preparation owners finish; do not retain a history of full frames.

`PreparedSection` must retain the exact definition matching its descriptor.
Never substitute whatever catalogue happens to be current at send time.
Do not recompress definitions per client, lane or section.

Give the existing primary control send stream one serialized owner, using a
small asynchronous mutex or equivalent existing ownership. After HELLO, lanes
ask that owner to ensure their required fingerprint has been announced. The
owner serializes complete frames and records compact announced fingerprints.
Concurrent lanes requesting the same fingerprint share that operation.
Set metadata/control priority above both coverage and refinement. Current control
and coverage priorities tie, so this requires an explicit relative ordering change,
not an assumption that metadata already wins.

Mark a definition announced only after its complete frame has been accepted by
the transport writer. This does not mean the client has decoded or persisted it.
Send dependent descriptors/bodies afterward without waiting for a new ACK.
QUIC stream arrival order can still differ; the client must handle that.

A partially written metadata frame must finish or fail its stream/connection.
Never cancel it and reuse the cursor for another frame. On primary shutdown,
fail all definition waiters and drain existing owned tasks. Reconnection creates
fresh announcement state. Do not add a second stall timer or a copied prefetch
queue, and do not change congestion control, stream counts or send windows in
this feature.

## Client bindings and linear retained storage

Accept catalogue frames on the primary control reader and route them through
one shared catalogue owner in `ClientSession`. Both foreground and background
readers resolve section descriptors against that owner's exact fingerprint.

Validate a section descriptor and its body length, then receive that lane's one
owned record before waiting for its exact shared binding. Preserve the existing
single-record handoff; add no copied payload/prefetch queue. Waiting before reading
the body can strand shared connection receive credit in terrain streams and keep
delayed metadata from progressing. A separate control reader alone does not solve
that flow-control risk.

Decompress, hash and validate catalogue frames on the existing control/background
reader executor, then hand the validated result to the shared owner. Do not acquire
a section/mesh worker, add a thread pool, or wait for model resolution, rendering
or optional persistence. The current catalogue handoff uses a section worker;
remove that dependency. The independent metadata reader and consumption of each
lane's owned body let receive credit progress while bindings arrive. Catalogue
work must not take over the local-directory worker or delay cached terrain jobs.

Keep successfully announced bindings strong for the connection lifetime. The
current weak-reference map cannot safely support permanent server deduplication:
GC must not make a previously announced definition unavailable to a later record.
All accepted primary/background definitions and validated disk hits share that
primary-session owner. A background reconnect must not drop a binding that primary
records still use. Reject handoffs from stale primary or background epochs;
closing a background transfer must not cancel a live primary rescue waiter.

Avoid replacing that weak map with a map of complete historical catalogues,
which would retain growing prefixes quadratically. Keep `CatalogCodec.Catalog`
as the temporary validated wire snapshot. Add a small source interface and
shared append-only block/biome tables with immutable prefix views. Each fingerprint
alias holds identity fields, fixed entry counts and a shared table reference.
Existing live mappings already resolve canonical names lazily; retain that path
and its shared successful name resolutions instead of allocating complete
ID-mapping arrays for every fingerprint.
Do not eagerly resolve all catalogue names when loading a definition or advertising
the held hint. Validate remote IDs against their alias's fixed prefix first.

Use short synchronized table access/publication or an equally small safe scheme;
concurrent unsynchronized reads during `ArrayList` resizing are unsafe. Verify
identity and exact overlapping entries before sharing a prefix. Accept a valid
older shorter definition without truncating tables. Its section must use its own
alias and bounds, even when a newer alias exists. Keep complete decoded snapshots
and encoded arrays only for current validation/persistence ownership.

Lookup is expected O(1); append is amortized O(1). Parsing, hashing, compression
and prefix validation remain O(C) for a catalogue of C bytes. Within an unchanged
live catalogue domain, retained storage is O(N + K) for N unique names and K
fingerprint aliases, rather than a sum of full historical prefixes. Existing
name resolution remains shared; this feature adds no scan per voxel/section.

Registry classification can change at native startup under the same catalogue
ID. A prefix/classification mismatch must invalidate the old discovery hint and
start a fresh live domain without mutating tables used by existing decode/save
jobs. If a live-session invariant requires reconnecting, do not advertise the
rejected fingerprint again; that would create a reconnect loop. Retire old
bindings with their connection/jobs, preserving named local terrain and geometry.

## Optional persistent catalogue

Extend `RegionalMetadataStore` with one `catalogue.vxcat` in its existing
world/dimension namespace, selected from the actual `RegionalDiskBudget.root`
and existing debug namespace. Store the complete compressed definition and its
validation metadata. Keep the existing `.vxlink` association and named terrain
journals unchanged.

Use the existing writer lock, pins, byte accounting and atomic replacement through
`catalogue.vxcat.pending`. Include both suffixes in the existing managed-file
inventory/eviction rules. Add no independent cache directory, full-cache scan,
new disk limit or per-fingerprint file history.

Probe this single optional file asynchronously once the metadata owner and world
hint are available. Do not place its I/O in `BootstrapTask` or wait for disk
inventory before rendering local sections. OPEN advertises only a binding already
validated and installed when OPEN is assembled. If the probe is late, proceed
with no hint and receive one compressed definition. A late hit can still reuse
the binding; never cancel a partially transmitted metadata frame to save bytes.

Publish a valid binding before optional persistence. Missing, corrupt, evicted
or unwritable catalogue metadata loses this optimization only: cached terrain
continues and networking supplies a valid replacement. File eviction after load
does not invalidate the strongly held live binding.

Persist the newest validated catalogue by registry generation within its identity,
not by arrival order. A delayed older frame must not overwrite the discovery
hint. Check world/session ownership before committing. Coalesce pending saves
to the latest relevant definition with existing task ownership; do not retain
every compressed snapshot in a persistence queue.

## Background bandwidth and foreground rescue

Keep background freshness traffic on the paced connection. A definition needed
only by background updates travels there and is charged by the existing wire
ledger, including IP/UDP/QUIC overhead, the routing envelope and retransmissions.
Do not send it on the uncapped primary connection merely to achieve deduplication.

Background records may reuse a validated held hint or a definition already
announced on the still-live primary connection. Both readers wait on the shared
binding without a catalogue ACK. Otherwise background announces the fingerprint
once within its own connection epoch. Background resets discard that epoch's
announcement state, and primary closure ends dependent waits.

If an urgent foreground miss needs a definition currently being transferred
only under the background cap, allow one foreground rescue copy. It uses the
primary owner and benefits all eight foreground lanes. This rare two-connection
overlap is preferable to holding visible terrain behind a low refresh cap.
Avoid a chunking, cancellation or cross-connection acknowledgement framework.

## Implementation sequence

Before changing the deployed build, capture a repeatable debug247 PC baseline
using the same pose, owned cache state and documented impairment for the later
comparison. Preserve the user's normal cache and connection configuration.

1. Add current compressed framing and held hints to Rust/Java protocol code.
   Add correct decompression/frame extents and update the live pressure consumer
   to the same contract. Remove superseded raw catalogue parsing/writing.
2. Cache immutable compressed definitions server-side and introduce the primary
   serialized writer and compact announcement state. Bind sends to prepared
   snapshots and preserve paced background/rescue behavior.
3. Introduce shared prefix bindings and primary metadata reception independent of
   terrain workers. Preserve one complete owned record per lane before binding
   waits. Keep local rendering/name resolution independent and propagate connection
   closure to the affected pending waits.
4. Add the optional single-file catalogue probe/persistence through existing
   metadata/disk ownership. Install valid bindings before saving and advertise
   only completed, strongly held bindings.
5. Add small debug timing/counter seams, build matched debug client/server/native
   artifacts, and verify exact hashes. Then publish/deploy only to Voxy_Testing
   and the PC's existing updater, preserving the two PC backup SSH connections.
6. Perform the live checks below. Record actual results and comparable before/after
   measurements; neither compilation nor a setup-only load run proves completion.

Expected edits use existing Rust `regional/wire.rs`, `regional/service.rs`,
`server.rs` and `live_pressure.rs`; Java `RegionalProtocol`, `RegionalQuicClient`,
`ClientSession`, `CatalogCodec`, `RegionalSectionCodec`, `LocalSectionCodec`,
`CompletedSectionCache`, `RegionalMetadataStore`, `RegionalDiskBudget`; and the
existing debug telemetry/pressure tooling as needed. No new production source
files, folders or libraries should be necessary. Rough growth estimate is
250–500 production lines, not a limit or a measured count. Report the actual
source/file/folder and binary-size delta using the same counting scope afterward.

## Live verification and completion evidence

Do not add or run integration tests or automated test suites. Use static/build
checks and actual PC/virtual-client connections. Preserve existing client caches,
server world data and recorded failed runs. Isolate any repeatable cold-cache
measurement in an explicitly owned debug cache namespace.

Record definition construction/compression, frame-write progress, frame-read
completion, decompression/hash/validation, binding installation, optional cache
probe, first terrain record and geometry publication. Count catalogue frames and
compressed application bytes separately from wire traffic/retransmissions.
Avoid per-voxel logging. Capture comparable pose, network profile, cache state,
loaded artifact identities and screenshots for the real PC comparison.

| Live case | Required proof |
|---|---|
| Cold PC connection | One compressed foreground definition per required fingerprint across eight lanes; streaming terrain follows without a new request/ACK RTT. Compare first fresh geometry and useful terrain bytes against the repeatable pre-change baseline, keeping the earlier incident capture as supporting evidence. |
| Warm catalogue available before OPEN | Validated held hint; zero definition bytes for that fingerprint. Terrain caching still operates independently. |
| Late, missing, corrupt or evicted optional catalogue | No local-terrain wait or cache purge; at most one correct foreground definition. Late probes do not corrupt a partially written frame. |
| Voxy backend offline | Cached terrain appears with no HELLO/catalogue/network dependency; inspect the real PC screenshot and local-completion counters. |
| New block/biome and delayed old records | Exact aliases decode both fingerprints correctly; no growth-induced missing textures, invalid indices or geometry loss. A known-state block edit does not resend unchanged definitions. |
| Classification/prefix mismatch | Old hint invalidated without reconnect loops; named cache and visible terrain survive. Older arrivals cannot replace the latest persisted discovery hint. |
| Metadata/data reordering and connection reset | Control reader progresses while lanes wait; no connection-flow-control deadlock, orphaned wait or reuse of a partial frame. Retest with real loss/delay. |
| Background cap and urgent zoom/miss | At the configured 1 Mbps cap, definitions and retransmissions remain counted; existing cadence settings of at least 1s remain effective. Foreground rescue progresses without moving background-only traffic outside the cap. |
| 100 virtual clients | Existing profiles remain 300–1,000ms RTT, 50–90% packet loss and 500kbps–3Mbps shared foreground/background capacity. All 100 must become usable before calling the setup successful. Then complete live 300 and 1,000 block-change/s phases with saved-patch restoration and existing integrity/cap checks. |
| Resource/lifecycle preservation | Native external limit stays 999,997,440 bytes with no OOM; Java stays Xms1G/Xmx4G. Verify both PC SSH helpers and actual loaded matched builds, preserve originals and unrelated server state. |

The old debug247 pressure trial is baseline evidence, not a pass for this feature.
Preserve its receipts and finish/clean up its owned consumers before matched
deployment when this plan is executed. No mutations, restarts, builds or transport
tuning are part of writing this plan.

Completion requires the deduplication/reuse receipts, correct cache-first/offline
behavior, preserved actual-traffic cap, improved cold-PC delivery under comparable
conditions and a completed 100-client pressure run. Report any failing case
explicitly rather than relaxing profiles or claiming the expected byte saving is
proof of a latency improvement.
