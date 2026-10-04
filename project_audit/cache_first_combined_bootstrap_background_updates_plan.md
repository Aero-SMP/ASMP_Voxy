# Cache-first combined bootstrap and coalesced background terrain updates

Status: plan only. Written 2026-10-04 for the restored original fork at
`/home/aerosmp/Desktop/ASMP_Voxy_Restart`, HEAD
`60aa5d434ad13331075d7c91a45e046095848ca1`.

The user selected approach 2: combined bootstrap and server-pushed changes to
interested sections. The comparative estimates were complexity **+2**, benefit
**9/10**, approximately **+1,000–1,800 readable production lines**, **+2–5 source
files**, **+0–1 source folders**, and **5.6–5.9 MB combined client/server JAR size**.
These are planning estimates, not measurements or implementation limits.

Writing this document does not start implementation, deployment, tests, server
control, or the paused rewrite goal. Historical audit plans do not add requirements
to this plan. Preserve unrelated working-tree changes.

## 1. Desired behavior and scope

- Display usable cached terrain before requesting missing terrain for that view
  branch. Cached data remains usable when the Voxy backend is offline.
- Load as much high-quality visible terrain as quickly as possible. Staleness is
  acceptable; missing coverage and missing zoom detail take priority over freshness.
- Reduce dependent network exchanges before the first uncached terrain arrives.
- Coalesce saved block changes on the server into latest-state batches. The client
  receives replacement terrain, not individual block-change events.
- Add a configurable background update interval with a one-second minimum and a
  configurable background download cap in Voxy's existing Sodium settings.
- The cap counts actual server-to-client IP traffic, including QUIC retransmissions
  and protocol overhead. It is not a compressed-payload-byte approximation.

Keep the existing self-contained local cache format, regional storage and
incremental builder, catalog/section codec, greedy mesher, GPU hierarchy, coverage
and refinement lanes, and fence-based publication. Replace superseded protocol
paths rather than retaining old readers or compatibility fallbacks. Add no protocol
version negotiation, cache migration, generic scheduler, or new transport library.

The requested interval and bandwidth controls are user policies. Add no unrelated
CPU, memory, section-count, upload, or sections-per-second budgets. Preserve existing
JVM arguments, the external testing backend memory ceiling, and the GPU-memory
setting. Structural validation and QUIC flow control remain necessary correctness
mechanisms.

## 2. Verified starting point

The original already starts local cache discovery independently of the backend.
`ClientSession.start()` launches bootstrap/workers before connecting, stored
server-address/dimension associations permit opening the correct cache without
HELLO, and recovered local directories can seed cached detail with missing coarse
ancestors. Backend disconnection preserves installed geometry.

However, online work can overtake startup: HELLO requests a catalog unconditionally;
catalog processing shares the local metadata worker; regional requests can start
before local discovery finishes; and fresh absence can retire a demand before its
cached data is considered. There is no strict first-render barrier.

The usual cold path after QUIC connects is:

```text
HELLO/world identity -> catalog + regional index in parallel -> section payload
```

This is approximately three application round-trip dependencies. Endpoint
discovery, TLS, source generation and byte transfer add their own time. Already
cached sections require zero backend round trips. An indexed-region miss may
already need only a section request/reply.

The native publication worker currently polls at two seconds while caught up,
continues immediately while work remains, and wakes on requests. This is not a
client refresh cadence. Native change announcements currently disconnect lagging
subscribers; the client can also drop an announcement while responses are pending.
Both behaviors must become latest-state reconciliation.

## 3. Cache-first demand and coverage rules

Use existing demand ownership and renderer callbacks; do not create a parallel
loading pipeline or scan the entire cache.

1. For a demanded branch, reuse suitable GPU-resident geometry first.
2. Complete that branch's local directory probe before declaring a network miss.
   Read desired cached detail, or available cached covering terrain, using the
   existing local decode/model/mesh/publication path. Finer cached descendants that
   cover the view can satisfy demand even when the coarse record is missing.
3. When usable cached coverage exists, let it activate and participate in a render
   frame before releasing missing-detail requests for that branch. An upload's
   `ACTIVATED` result alone does not prove the terrain was selected for rendering.
   Add the smallest acknowledgement to the existing renderer/session handoff.
4. When no usable cached cover exists, release that branch's misses immediately
   after the probe. Do not hold it behind unrelated regions or a whole-cache drain.
5. A partial set of cached children must not deadlock requests for missing siblings.
   The original refinement rule can require every authoritative child to be ready.
   Gate on usable coverage or a complete cached cut, not every fine child having
   been drawn. Missing structural siblings remain foreground dependencies even
   when individually outside the frustum.
6. Give local discovery priority over incoming catalog work. Request a network
   catalog only when a genuine network payload needs it; local records remain
   self-contained. Deferred controls must coalesce to current state.

Keep orphan-cache navigation and accurate child masks. Do not fabricate an empty
parent to expose cached children. Integrity failure means a miss; newer metadata,
transient unavailability, or an unfinished build does not mean authoritative air.
Preserve releasing worker ownership at renderer admission, before final activation;
holding every worker until a complete sibling group activates can deadlock meshing.

Offline operation requires a valid saved world association and usable local
records. Never choose an arbitrary old cache namespace. An authenticated identity
mismatch must switch to the correct world; stale terrain from the same world is
acceptable, terrain from a different world is not.

## 4. Combined bootstrap contract

Replace the serialized initial HELLO/catalog/index/section request dependency with
one bootstrap operation after local probes identify initial misses.

- Request: dimension, stored expected world identity when known, useful known
  catalog/index fingerprints, missing coarse section keys, and streaming settings.
  Include required structural coverage keys, not just selected visible leaves.
- Response: actual world identity, required catalog and index metadata, and usable
  payloads for requested keys. Reuse the existing compressed section representation.
- Supply payloads on the existing foreground coverage machinery as part of the
  same logical response. Client lane setup must not wait for a bootstrap reply;
  metadata delivery must not require another request or acknowledgement round trip.
- Keep control messages separate from bulk terrain. Each record descriptor supplies
  the full spatial key, batch/session ownership, actual regional snapshot identity,
  kind/status, child mask, catalog/content fingerprints, canonical/compressed lengths
  and integrity fields. A compressed body alone cannot replace the old index-derived
  descriptor. Emit required catalog definitions lazily, before dependent decoding.
  Resolve their metadata dependencies through existing owned workers/stream
  backpressure rather than a new queue of copied payload buffers.
- Bind metadata and bytes to the same immutable regional snapshot. Validate world,
  catalog, section identity and integrity before publishing or committing records.
- A key absent from local cache can use the current published server generation
  immediately, even if that generation is stale. If no server generation exists,
  begin its build without waiting for the background refresh interval.

First usable uncached coarse terrain can then begin arriving after approximately
one application RTT following connection establishment. This is not a promise of
one RTT from Minecraft join or a deadline for a cold server build.

Use bootstrap and subsequent demand/interest deltas for newly missing spatial keys;
the server resolves them with its existing regional index. Preserve foreground
coverage/refinement batching and worker handoffs. Known-index lookup remains an
internal shortcut, not a required client index-fetch round trip or a parallel legacy
protocol. Treat newly missing zoom quality as foreground work. Adequate cached
quality produces no terrain download; a lightweight interest registration enables
its future background refresh. Start decoding each usable record without waiting
for all bootstrap regions. Remove superseded request gating/parsers and deploy
matched client/server artifacts together later.

## 5. Completed-save notification and shared server coalescing

Add a narrow completed terrain-save hook in the Minecraft bridge. Observe successful
terrain chunk writes/deletions, using the actual storage completion boundary;
ordinary save events may fire before disk writes finish. Filter out entity and POI
storage. Verify the pinned Minecraft method/event semantics before choosing the hook.

The hook only marks dimension/chunk coordinates in a coalesced dirty set. It never
copies NBT, queues one item per block edit, waits for Rust, or blocks a storage/tick
thread on IPC. Use one Java-owned IPC drain tied to the existing backend supervisor;
retain unsent dirty state across child replacement and reject stale child handles.

Native ownership:

1. Use one dirty-chunk bitset per dirty region. Repeated saves set the same bit.
   Its dimensions follow the Anvil layout, not an arbitrary work quota.
2. Hook-dirty slots must force a source read even if their Anvil location and
   second-resolution timestamp are unchanged.
3. Schedule shared freshness builds from the shortest active interested-client
   interval, never below one second. Use scheduled epochs, not a sliding debounce
   reset on every save. Clients with slower intervals consume the same shared builds.
4. Capture only the dirty bits assigned to a build. Saves during the build enter
   the next dirty set. On failure, merge captured bits back; successful publication
   must not clear later saves.
5. Reuse incremental rebuilding of affected chunk groups and LOD ancestors,
   unchanged compressed payloads, source-coherence checks and atomic publication.
   On source instability retain the previous usable generation and retry.
6. Keep reconciliation polling for missed notifications, external edits and restart
   recovery. Do not replace all scanning with notifications or pretend the current
   two-second poll constant alone implements batching.

The cadence concerns completed saved data, not a promise that in-memory block edits
are saved within one second. Region publication still copies stored bytes and
continuous writes can force retries; neither is constant-time work.

## 6. Interested-section push and latest-state ownership

The client sends additions/removals to its current section interests using existing
demand/window changes. Include known validated fingerprints. Register cached-only
sections after local-first use; otherwise their updates would never arrive. Send
interest deltas, not a complete inventory on every frame.

The server keeps current interests and one latest pending state per interested key.
Compare section fingerprints so a regional publication does not resend unchanged
sections. Identity comparisons also include kind, child mask and catalog binding;
an unchanged compressed body can have changed topology. Send descriptor-only
changes where the valid body can be reused. Overwrite obsolete unsent states; do
not retain a per-client edit log or queue every regional generation.

At an eligible background deadline, push changed interested sections with their
required catalog/index/topology metadata. Read payloads lazily from immutable
published files when transport is ready. Track validated client receipt/fingerprint
and last sent state without inventing a request/reply acknowledgement for every
record. Reliable stream delivery avoids repeated sends on a healthy connection;
any semantic receipt required for ownership must be batched/piggybacked and must
not delay independent foreground work. Sending bytes is not proof of durable client
persistence. Reconnect reconciliation uses current client holdings, not assumptions
from an abandoned connection.

Allow one active background batch per client and retain latest pending state while
it transfers. Subsequent batch starts are separated by at least the chosen interval;
missed timer ticks do not cause catch-up bursts. Already submitted QUIC bytes cannot
be retracted; supersession applies to unsent work. Cancellation/releases discard
obsolete interests without leaking files, tasks, or worker ownership.

Route every received payload through existing validation, local persistence and
mesh/publication ownership. A background descriptor initially replaces latest
pending intent; it must not revise/cancel a running cache job or take its bootstrap
metadata worker. Reuse stream-to-worker backpressure rather than buffer whole
batch bodies. Keep old coverage until replacement or complete child groups are
ready. Empty can have a nonzero child mask; use the real snapshot mask. Confirmed
absence/deletion includes affected ancestor masks and any required neighbor or
cross-region meshing dependencies. Temporary not-ready and stale-generation
replies cannot erase terrain. Each region has its own snapshot identity, and
independent replacement groups can publish independently. Do not require or claim
simultaneous replacement of every section in a world-wide batch.

Recover subscriber lag by reconciling current published state. If a client operation
is pending, retain the newest announcement instead of dropping it or accumulating
all intermediate announcements.

## 7. Actual-traffic cap and connection lifecycle

Scope: **per-client background server-to-client IP traffic**. Count encrypted QUIC
datagrams, retransmissions, handshake/ACK/close traffic, freshness metadata, and
IP/UDP headers. Client-to-server acknowledgements are upload traffic. Ethernet,
Wi-Fi and provider encapsulation are outside this IP-layer metric. Initial terrain
and missing zoom detail remain on the uncapped foreground connection.

The smallest reliable design with the pinned Quinn/Kwik APIs is a separate background
QUIC connection with one dedicated paced server endpoint/socket per client:

1. Receive settings on the primary connection before creating the background socket.
   Advertise its reachable UDP port and bind it to the primary session with a session
   token. Keep the existing certificate verification; allow only the owning session.
2. Wrap the socket using Quinn's `Endpoint::new_with_abstract_socket` and
   `AsyncUdpSocket`. Pace before `try_send`, not around application payload writes.
3. Compute each send deadline from its actual datagram IP-byte cost and configured
   rate. Return `WouldBlock` until eligible and arrange timer-backed `UdpPoller`
   wakeups. Do not sleep a worker, poll in a busy loop, or create a second packet queue.
4. Charge successful socket submissions once. Failed send attempts are not traffic.
   Disable background transmit segmentation initially, or account every segment's
   headers correctly; preserve foreground transport behavior. Avoid IP fragmentation.
5. Use a pacing clock without accumulated idle burst credit. For rate R, the socket
   admission contract is bytes <= R * elapsed time + one datagram, reflecting packet
   granularity. A 1 Mbps rate is 125,000 IP bytes per second. Verify real egress too;
   socket accounting is not proof of exact physical-link departure timing.
6. Apply setting changes live without a free burst. Preserve a shared rate ledger
   across overlapping background reconnect/close traffic for the same primary
   session. Freshness metadata must use this background transport, not bypass the
   cap on the primary control stream.
7. Close background tasks/endpoints with their primary owner, retaining ownership
   while draining capped close traffic. A background failure preserves cached
   rendering and foreground loading; reconnect to latest state, not old batch history.

A separate stream on the primary QUIC connection cannot independently enforce this
cap because encrypted packets, retransmissions and congestion state can be shared.
Transport statistics are measurement, not pre-send enforcement.

Deployment prerequisite: extra advertised UDP ports must be reachable from the real
client. One hundred clients entail approximately one hundred additional sockets and
ports. Verify firewall/NAT routing before committing to that deployment shape; do
not assume an arbitrary ephemeral port is externally reachable. Use existing routing
where suitable and document any scoped routing configuration required.

If dedicated ports are unavailable, retain this approach's bootstrap/push contract
but implement one shared background port with explicit pre-handshake session/rate
association and packet routing. Inspect the actual library APIs and revise the size
estimate before implementing that alternative. A simple remote-address bucket,
disabled migration, or a token first received after TLS is insufficient to cap the
earlier handshake. Do not substitute payload-only throttling and claim completion.

References: [Quinn 0.11.11 Endpoint](https://docs.rs/quinn/0.11.11/quinn/struct.Endpoint.html),
[Quinn AsyncUdpSocket](https://docs.rs/quinn/latest/quinn/trait.AsyncUdpSocket.html).
The second link tracks latest documentation; verify behavior against pinned 0.11.11.

## 8. Sodium settings

Add a Streaming group to the existing Voxy settings page and two persisted fields
in `VoxyConfig`, using current translations and apply hooks:

| Setting | Initial default | Contract |
| --- | --- | --- |
| Terrain update interval | 1 second | Minimum 1 second; user can choose slower. Minimum spacing between background batch starts, not an arrival deadline. |
| Background download bandwidth | Unlimited | Decimal Mbps/kbit/s; 1 Mbps = 1,000 kbit/s. Count the IP download traffic defined above. |

Use zero as Unlimited if represented by an integer kbit/s field. Validate conversions
with checked arithmetic. Slider limits are presentation choices, not hidden runtime
resource budgets. Preserve valid explicit choices and label units clearly.

Apply policy through the session owner; no renderer/Iris reload, cache deletion,
connection-wide reset, or geometry eviction. Interval/cap affect freshness only.
Suggested help text: "Refreshes terrain you already have in the background. Cached
terrain stays visible; missing terrain and zoom detail load immediately."

Keep existing enable/disable, pixel-size, render-distance and GPU-memory settings.

## 9. Implementation order and likely touched code

1. Record source/artifact baseline and preserve the original fork for comparison.
   Inspect dirty state and current runtime identities before implementation/deployment.
2. Implement local-first demand/probe/publication rules and cache-only interests.
3. Replace the bootstrap dependency chain and reuse foreground lanes/worker ownership.
4. Add completed-save dirty notifications, native forced probes and shared coalescing.
5. Add latest-state background push, acknowledgement/reconciliation and cadence.
6. Add separately paced background transport, scoped endpoint lifecycle and settings.
7. Build matched debug artifacts; perform the live checks in section 11; record results
   and actual source/binary deltas. Do not call an unverified estimate a measured benefit.

Expected edits are within existing client config/session/protocol/QUIC files;
`server/.../RustBackend.java` and a narrow completed-save hook; and native
`server.rs`, regional wire/service/runtime/builder code. Put new helpers in existing
packages where clear. Share parsing, codec and ownership helpers; do not duplicate
the whole foreground QUIC client for background operation.

Relevant baseline locations:

| Source | Starting points |
| --- | --- |
| `src/main/java/me/cortex/voxy/client/lod/ClientSession.java` | start 891; HELLO/catalog 1061; announcements 1164; local binding 1748; stages 1899; regions 1917; local metadata 2034; orphan cache 2165; section requests 2593; publication 2910 |
| `src/main/java/me/cortex/voxy/client/lod/RegionalQuicClient.java` | coverage/refinement lanes 38; setup 56; HELLO 170; lane ownership 275 |
| `src/main/java/me/cortex/voxy/client/lod/RegionalProtocol.java` | message definitions 24; regional index 102; HELLO encoding 212 |
| `src/main/java/me/cortex/voxy/client/config/VoxyConfigMenu.java` | existing page 132; live apply hooks 143 |
| `server/src/main/java/me/cortex/voxy/server/RustBackend.java` | existing supervisor and child ownership |
| `rust-server/src/server.rs` | regional connection 205; index requests 238; subscriber lag and changes 261 |
| `rust-server/src/regional/service.rs` | publication refresh 164; worker 237; immutable responder ownership 348 |
| `rust-server/src/regional/runtime.rs` | source probe 1003; unchanged-header shortcut 1025 |
| `rust-server/src/regional/builder.rs` | incremental changed-group/ancestor rebuild 59 |

Line numbers describe the baseline and will move during implementation.

## 10. Complexity and size accounting

Dirty insertion and replacing pending latest-state entries are expected O(1) with
bitsets/keyed maps. Packet pacing is O(1) per emitted datagram. Interest changes scale
with changed interests; propagation scales with interested recipients. Builds scale
with affected source/groups/ancestors, publication with stored region bytes, and
transport with transmitted bytes. No pairwise scan over sections or clients is needed;
do not promise that processing or transmitting arbitrary terrain becomes O(1).

Measure allocations and throughput in live operation. Avoid per-block objects,
whole-cache inventories, copied per-client payloads, repeated unchanged catalogs,
and payload-history queues. Reuse owned buffers/context where existing code supports
it; do not compress, decode or mesh while merely waiting for bandwidth eligibility.

Baseline measured while writing this plan:

- Production Java/Rust/shader files under `src/main`, `server/src/main`, and
  `rust-server/src`: **36,377 physical lines**, **184 source files**, **55 directories
  including empty directories**. Count comments/blanks; do not minify or hide code.
- Client debug 223 JAR: **3,948,385 bytes**.
- Server debug 223 JAR: **1,608,781 bytes**; combined **5,557,166 bytes**.
- Standalone native executable: **3,178,872 bytes**; it is embedded in the server JAR,
  so do not add it a second time to combined shipped JAR size.

The estimate implies roughly 37,377–38,177 production lines, 186–189 source files
and 55–56 directories, with no new dependency required. Debug/tools/tests are
excluded symmetrically from that production count; report any changes to them
separately and in a total maintained-source count. Include new save-hook registration
resources and documentation separately. Measure actual matched-build binary sizes;
line counts cannot establish compressed JAR or linker output sizes.

## 11. Live validation and completion evidence

No integration tests or automated test suites. Do not add them or invoke historical
ones. Compilation/build checks are separate from live proof. Use the real player's
debug client and real QUIC virtual clients when implementation is authorized.

Keep both independent laptop backup SSH connections usable before updating the
client. Verify the updater's intended target/channel, preserve rollback artifacts,
and prove the actual loaded client/server/native hashes after scoped deployment.
Use Voxy_Testing after checking its actual paths, manager, ports and limits; keep
Main and unrelated instances untouched. Preserve world data, identities and caches.
Never delete caches to make offline validation easier; isolate a cold test namespace
without altering the player's warm cache when a cold case is needed.

| Live scenario | Required evidence |
| --- | --- |
| Warm cache, Voxy backend offline, Minecraft still connected | Screenshot plus first-visible/cache-source telemetry; cached terrain renders without backend replies or cache loss. |
| Warm cache, backend online | Cached cover reaches a frame before branch misses; catalog/index replies cannot suppress its startup. Adequate cached quality generates no terrain download. |
| Partial cache and missing sibling/parent | Cached coverage appears where available; uncached structural dependencies proceed; refinement does not deadlock or fabricate empty terrain. |
| Cold visible region | Protocol trace shows one bootstrap request yielding needed metadata and initial terrain without catalog/index/section request round trips afterward. Separate TLS/discovery/build/transfer time. |
| Zoom and movement | Resident/cached quality wins; genuine missing quality uses foreground lanes and bypasses the freshness interval/cap. Retain cover through replacement. |
| Continuous saved edits, same chunk/region | Same-second saves are detected; one latest pending state replaces repeated edits; changes during a build survive; authoritative deletion/air settles safely. |
| Update intervals of 1 second and a slower choice | Per-client batch starts respect settings; clients share published builds; sustained edits do not starve batches or cause historical catch-up bursts. |
| 1 Mbps background cap with heavy loss | IP egress capture/counters include metadata, headers, retransmissions, handshake and close; measured rate agrees with packet-granular contract. Foreground remains independently serviced. |
| Live setting change, disconnect, background reconnect, backend restart | No free pacing burst, lost dirty notifications, orphan endpoints, stale owner installs, fallback eviction or cache reset. |
| 100 virtual QUIC clients with sustained edits | Distinct map locations; RTT 300–1,000 ms, loss 50–90%, bandwidth 500 kbps–3 Mbps; at least 300 changes/s and a 1,000 changes/s phase. Exercise actual transport/loss, bootstrap, cache and update acknowledgements. |

First verify with the one real client; run the full pressure case afterward. Virtual
clients must be explicit protocol/cache consumers, not counters pretending to be
100 connections. Confirm all 100 are active and served, rather than report a lower
peak as a pass. Record mutation count, saves detected, bytes, retries, connection
health, pending-state growth, cache outcomes, and coverage/quality progress.

Watch actual JVM and backend process/cgroup memory, external limit settings and
OOM counters throughout pressure testing. Keep existing ceilings; stop the load
before unsafe host pressure rather than relaxing limits or claiming an aborted run
passed. Do not add internal arbitrary work budgets to obtain a pass.

Completion requires live screenshots and ownership/traffic traces for the above
behaviors, successful real-client operation and all-100 pressure evidence, measured
source/artifact deltas, and a results document that names failures and remaining
limits. Settings presence, compilation, publication to an updater, or aggregate
QUIC statistics alone do not prove the plan works.
