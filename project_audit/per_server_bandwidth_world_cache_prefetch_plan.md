# Per-server bandwidth, world cache and spatial download priority

Status: implementation plan, accepted policy; written 2026-10-05.

Repository: `/home/aerosmp/Desktop/ASMP_Voxy_Cache_First_Updates`.
Branch: `feature/cache-first-background-updates`.
Starting commit: `745084e90abcb577fa37b39d68c9d4d5a1ce83ec`.

This document does not implement, deploy, restart, clear caches or run tests. Keep
the original `ASMP_Voxy` and `ASMP_Voxy_Restart` working trees unchanged. Future
implementation stays on this feature branch. Main and its source world remain
read only. Historical rewrite goals and superseded bandwidth proposals do not
add requirements to this plan.

This extends the existing [combined-bootstrap/cache-first plan](cache_first_combined_bootstrap_background_updates_plan.md)
and [shared-catalogue plan](shared_compressed_catalogue_plan.md). Its total-cap,
per-server storage and prefetch rules supersede their background-only bandwidth
policy; their retained offline cache and shared catalogue behavior stay in scope.

## 1. Outcome and accepted policies

Use available download capacity to acquire useful saved terrain continuously:
first missing visible coverage and required detail, then finer detail for likely
zoom, then spatial prefetch and low-priority freshness. Suitable cached terrain
is usable immediately, even stale and even with the Voxy backend offline.

| Setting or behavior | Accepted policy |
| --- | --- |
| Total download bandwidth | One cap, 100 kbps through 10 Mbps; default 1 Mbps. Decimal bits per second. Saved separately per logical Minecraft server. |
| Traffic covered | All Voxy QUIC server-to-client IP/UDP traffic, including handshake, catalogues, control, routing envelope, terrain, retransmissions, ACKs and connection close. Shared across dimensions and overlapping reconnects. |
| Old bandwidth setting | Remove the background-only slider, unlimited option, configuration field and special uncapped foreground policy. No separate refresh cap. |
| Storage allowance | Per server across all its dimensions; minimum 100 MB, default 500 MB, with a growing `Entire world` position at the far right. Decimal MB/GB, clearly labeled. |
| Storage accounting | Complete cache files on disk, including metadata, obsolete journal records, catalogue files and cache-owned temporary files. Never just live compressed payloads. |
| Sodium presentation | Show only the current server's two sliders; identify that server and cross-dimension scope in each tooltip. |
| Saved terrain | Existing saved terrain only, across all stored heights and LOD levels 0 through 4. Never ask Minecraft to generate unexplored terrain. |
| Border | Automatically prefetch sections intersecting the dimension's custom border; retain intersecting sections whole and count all their bytes. |
| No custom border | Fixed square centered at `(0,0)`, radius 512. Automatic prefetch stays inside it. Visible terrain outside this fallback is allowed and counts toward the same storage allowance. |
| Visible quality | First the detail required by existing pixel-size and zoom settings; then prepare every finer level through LOD 0 in cache. This does not force finest rendering. |
| Other dimensions | Finish current-dimension ordinary missing-data prefetch before inactive dimensions. Inactive rings use last player position, or border center if never visited. |
| Freshness | Known changes may compete before storage fills, but at very low priority and only after the nearby visible quality gate below. Stale cache remains usable. |
| Refresh cadence | Preserve configurable coalesced terrain updates with a minimum interval of one second. The interval governs freshness, not initial missing-data downloads. |
| Eviction | Whole 512-by-512 horizontal region files. Admit ordinary prefetch only when the destination improves retained cache; equal importance keeps existing data. |
| Physical disk exhaustion | Pause new downloads and preserve committed cache. Do not delete useful regions to continue through a physically full disk. |
| Renderer and cache loading | Keep existing VRAM selection, VRAM setting, cached-terrain loading, frustum/depth visibility, pixel-size logic, meshing and fence publication. |

One Mbps is 1,000,000 bits/s or 125,000 bytes/s; 500 MB is 500,000,000
bytes. Do not mix these labels with MiB or payload-only goodput.

`Entire world` means all eligible existing saved terrain at every LOD, not
unexplored coordinates inside the border. New saved terrain and border expansion
grow its estimate and work set. There is no invented fixed GB ceiling. Show an
estimate while the server cannot provide exact local-encoding costs; native wire
payload size is not the client's named-cache file size. Preserve a selected
numeric allowance when the estimate changes. The numeric slider range must keep
100 MB and the 500 MB default representable even for a tiny world; the separate
rightmost sentinel remains `Entire world`.

Apply the two controls to the live server policy without rebuilding the renderer
or discarding cache/VRAM residency. If no multiplayer server is selected, show
the controls as unavailable rather than editing an unrelated server's policy.

The exact traffic accounting boundary is Voxy QUIC at the IP layer, not Ethernet
frames. Endpoint discovery/authentication still uses the existing Minecraft TCP
connection. Its shared TCP/IP overhead cannot be uniquely attributed to Voxy;
it is existing Minecraft bootstrap traffic outside the QUIC ledger. Minecraft
game traffic, debug updater downloads and backup SSH connections are also
outside this terrain-download cap. Do not describe UDP receipts as accounting
for those other connections.

## 2. Keep the working parts; replace conflicting policies

The current fork already has self-contained named local cache records,
cache-first/offline bootstrap, a shared compressed catalogue, direct regional
lookup, incremental builds, saved-chunk notifications, server-side change
coalescing and slow interested-section updates. Reuse these. Preserve local
record encoding and existing usable cache data. No alternate old/new readers,
protocol-version negotiation or compatibility layer.

Source review found these concrete conflicts:

- `RegionalDiskBudget` has a fixed global 2 GiB allowance and insertion-order
  deletion. Replace it with the selected server allowance and spatial retention.
- `CompletedSectionCache.save()` catches `RotationRequired` and rotates away the
  entire active region at the journal's fixed 256 MiB extent. Remove this
  destructive size-based reset; it can discard useful records even without quota
  pressure. Reclaim obsolete records safely when needed, or refuse the append
  while preserving committed data. Add no replacement arbitrary journal budget.
- Rust currently paces only the separate background socket; primary QUIC can
  send before the client's rate setting arrives. A renamed slider cannot fix
  this. Register the aggregate cap before the first QUIC response and remove
  the uncapped primary path.
- Normal received-section work resolves models and meshes. Ordinary prefetch
  needs a genuinely cache-only path, before those operations.
- A `ClientSession` and its current wire keys are dimension-scoped. Inactive
  dimensions cannot share those unqualified section keys. Make download ownership
  server-scoped with explicit dimension-qualified work and responses.
- Rust discovers saved dimensions at startup and applies the same fixed vertical
  layout to all dimensions. Whole-world discovery must handle actual dimension
  heights, newly saved regions and new dimensions without generating chunks.

Avoid new transport libraries, general scheduling frameworks, per-section
timers, full-world per-client subscription maps and duplicated palette scanners.
No new CPU, memory, request-count, sections-per-second or upload budgets. The
user's bandwidth/storage settings and existing external backend/JVM limits are
the applicable policies. Existing transport flow control and structural checks
remain correctness mechanisms.

## 3. Exact priority, equality and the accepted delay tradeoff

### 3.1 Geometry

Use a single horizontal player block anchor `(floor(x), floor(z))` for scoring.
For inactive dimensions use their saved anchor, or the border center converted
to block coordinates. This gives integer geometry at Minecraft's voxel precision.
Do not quantize the renderer's camera, frustum, zoom, Y coordinate or matrices.

A section's horizontal footprint has side `L = 32 << lod`: 32, 64, 128, 256 or
512 blocks. Let `d` be horizontal distance from the anchor to the nearest point
of the section footprint, not its center. For ranking use the geometric closed
footprint `[origin, origin+L]`; inside or on an outer face has distance zero.
Calculate squared distance as
`dx*dx + dz*dz`, with integer coordinates and checked arithmetic. This plan
intentionally does not use height for ordinary spatial download ranking.

### 3.2 Download order

Use three semantic classes, in this order:

1. **Visible missing coverage and required detail.** Use existing visibility and
   pixel-size/zoom demand. Suitable cached data satisfies demand even if stale.
   Fill coverage gaps before refining already covered visible terrain.
2. **Visible finer-detail preparation.** After required detail, acquire every
   missing finer level down to LOD 0 for those visible areas. Persist these levels;
   render only what the unchanged renderer selects.
3. **Ordinary missing prefetch and eligible refresh.** Select the current
   dimension's eligible missing-prefetch pool first; when that is exhausted or
   cannot improve retained cache, select inactive dimensions in deterministic
   order. Eligible refresh joins that selected pool by weighted score. Refresh
   receives no current-dimension precedence of its own: current refresh may
   compete with inactive missing terrain, but does not automatically beat it.

For ordinary missing data:

```
I(section) = L*L / (L*L + d*d)
```

Higher wins. This favors nearby coarse coverage initially while allowing nearby
fine detail to overtake distant coarse sections. Generate candidates outward in
concentric spatial rings; merge the five LOD frontiers by score, rather than
finishing the entire coarsest world before adding any nearby detail. Scores pick
downloads only; do not feed them into GPU selection.

For an eligible changed section:

```
I(refresh) = I(section) / 256
```

256 is the area ratio between the largest and smallest section footprints,
`(512/32)^2`; it is the proposed low freshness weight, not a transmission quota.
Eligibility requires both:

- The current visible pixel-size/zoom requirement is satisfied.
- More than half of the estimated nearby visible area already has all levels
  through LOD 0 cached. Nearby means visible branches requiring finer detail
  than LOD 4 under the existing pixel-size criterion.

Use existing visibility estimates on a single hierarchy cut so overlapping
parent/child projected areas are not added repeatedly. If there are no nearby
visible branches, the second condition is vacuously satisfied. An age timer by
itself does not request data; only a known changed binding becomes refresh work.
Missing visible/finer work remains ahead of ordinary refresh. This gate is a
quality policy, not a CPU or bandwidth allowance.

### 3.3 Equality without floating-point tolerances

For the unweighted section score, compare this integer rank, lower first:

```
C = 512*512
N = (dx*dx + dz*dz) << (2*(4-lod))
K = C + N
I = C / K
```

Thus equal importance means equal semantic class and equal normalized geometric
rank, not two rounded floating-point reciprocals happening to match. Symmetric
sections and all footprints containing the anchor can tie exactly. Ordinary
missing-versus-refresh comparison uses `K_missing` versus `256*K_refresh`, since
the score numerator is common. Same-purpose comparison just compares `K`.
Apply coverage/refinement class first. Dimension precedence selects the missing
candidate pool as above; it is not an extra current-dimension boost for refresh.

The valid Minecraft horizontal coordinate domain keeps unweighted `K` within a
signed 64-bit integer, but the freshness multiplier can overflow. Do not blindly
multiply. In Java, if `K_refresh > Long.MAX_VALUE/256`, its weighted denominator
exceeds every valid unweighted candidate and the missing candidate wins; otherwise
compare the exact product. Rust can use checked arithmetic or `i128`. Validate
external coordinates before ranking. No epsilon, buckets, allocating `BigInteger`
comparators or silent overflow.

Equal ordinary request scores prefer missing coarse coverage, then a stable
dimension/region/section key. A request tie-breaker does not increase retention
importance or authorize eviction. Near-equal scores are deliberately distinct;
the exact tie rule does not promise to prevent all replacements as the player
moves. Do not add hysteresis or aging at this stage.

### 3.4 Whole-region retention

Define retention explicitly by the whole 512-by-512 region footprint:

```
R(region) = 512*512 / (512*512 + d_region*d_region)
```

Regions supplying terrain for the current visible/required view have the highest
retention class; otherwise compare this spatial rank using each dimension's
anchor. This is a region-geometry policy, not a sum or a scan of every section's
score. A sparse region receives the same geometric rank as another nonempty
usable region at that footprint; that simplicity is intentional.

Freshness, downloaded LOD count, append count, obsolete records and compression
ratio do not raise or lower retention rank. Fresh cached data keeps its rank.
Extra levels in the same region do not make it progressively harder to evict.
Download ordering of dimensions does not automatically evict every inactive
region before any distant current-dimension region.

Before requesting ordinary prefetch that needs space, compare its destination
region with potential whole-region victims. Skip the candidate unless it is
strictly more important than every victim needed to accommodate it. Equal rank
keeps existing data. Recheck before committing because the player, pins, actual
encoded size or allowance may have changed. Visible demand can promote an
existing pending download, but does not permit unlimited disk use.

### 3.5 Accepted delay tradeoff and Main's square

Higher-priority work can occupy the connection continuously: new visible
requirements while moving/zooming, finer preparation for changing views, or
ordinary nearer work scoring above a distant candidate. Then that candidate
waits indefinitely. This can include distant coarse coverage. A server without
more useful work does not cause this delay; sustained higher-ranked arrivals do.

For a static anchor at `(0,0)` and Main's requested radius-5000 square, the
furthest intersecting LOD-4 horizontal sections start at coordinate 4608 on each
axis. Their nearest footprint distance squared is `2*4608*4608`, so their missing
score is `1/163`, about 0.006135. That exceeds the maximum ordinary refresh score
`1/256`, about 0.003906. Consequently ordinary nearby freshness cannot outrank
those coarse sections under the starting rule. Visible demand and its finer
preparation still outrank them, and nearer missing work can delay them. The
score observation is not a universal completion guarantee.

The user accepts this tradeoff. Add no reserved coarse bandwidth, fixed fairness
share, maximum wait, aging or separate anti-starvation mechanism. Measure the
real experience first; revise score values only if a real problem appears.
`Entire world` completes when eligible saved terrain is finite, disk permits it,
and higher-ranked demand leaves capacity. A numeric allowance may be too small
to retain every coarse region; never claim guaranteed whole-world storage in
that case.

## 4. Authoritative saved-terrain discovery

Extend the existing authenticated bootstrap and control stream, without adding
another dependent round trip before visible terrain. Publish server identity,
dimension identifiers, per-dimension vertical layout, custom-border status,
center/size, and saved-region/chunk availability. Java supplies live border and
dimension metadata through the existing Rust bridge; Rust inventories saved
Anvil terrain. Neither path loads or generates unexplored chunks.

Use actual per-dimension layouts, including custom modded heights. Border data
must distinguish vanilla's unset/default border from a deliberately configured
custom border. Do not apply an Overworld coordinate conversion to another
dimension unless its authoritative border metadata requires it.

Persist enough discovery/ownership data locally for offline size/accounting,
dimension centers and cache access. Missing online metadata is not permission
to block old cache reads. Discover newly created dimensions and saved regions
incrementally using the existing save notifications and recovery reconciliation;
do not rescan all source files for every player or request.

Availability states must distinguish saved content, authoritatively absent
terrain, not-yet-built terrain, inventory failure and unreadable source. A
`NotReady` response waits for shared publication, not blind repeated requests.
An error is never interpreted as empty terrain or whole-world completion.
Bind negative availability/completion to current source publication state so
newly saved terrain becomes eligible. Do not prune finer content just because a
coarse representation is empty; only authoritative source availability can
establish no saved terrain there.

Automatic eligibility is positive-area footprint/border intersection and saved
vertical content, with correct negative-coordinate floor division; a section
merely touching the outside border does not add another row. Keep intersecting
records whole. Without a custom border, coarsest sections are corner-aligned:
the origin is a corner, and the fallback square spans four 512-by-512 footprints.
Outside that square accept visible quality and finer preparation, but no
unrequested ordinary prefetch. A custom border remains the eligibility boundary
for ordinary prefetch.

## 5. One transport and one wire-rate ledger

Use one prioritized QUIC connection per Minecraft server connection for visible,
lookahead, prefetch and refresh traffic across dimensions. Reuse Quinn, Kwik,
shared compressed catalogues and existing independent reliable section streams.
Remove the separate background connection, background handshake token,
background reconnect/rescue state and uncapped foreground route rather than
keeping both architectures. Qualify every work item/handoff by the dimension,
that dimension's world identity and catalogue domain, and the active connection/
session generation. Native world identity is dimension-derived; do not replace
all dimension identities with one server-level world hash. Preserve catalogue
fingerprints and revision tickets to reject delayed old-world/old-session work.

Before QUIC starts, piggyback the selected total rate and authenticated routing
registration on the existing Minecraft endpoint request. Java pre-registers the
player's route/cap with Rust asynchronously through the existing IPC bridge and
only advertises the ready route after registration. Never block the Minecraft
tick thread on IPC and never add another client discovery round trip. The first
QUIC server handshake packet must already use that ledger. Use the existing
token-envelope multiplexing on the public UDP port, generalized to all Voxy
packets; remove its old background-only semantics.

The ledger belongs to the authenticated Minecraft session, not a dimension,
new IP address or newly opened QUIC connection. Dimension changes, rate changes
and reconnect overlap must not obtain parallel allowances or reset accumulated
send debt. Rate reductions affect unsent traffic immediately. End the route
with its Minecraft session; preserve the current certificate pin and reject
unauthenticated route ownership.

Pace successful datagram sends by actual datagram length plus routing envelope
and applicable IPv4/IPv6/UDP headers. Cover TLS/QUIC handshake, metadata, payload,
retransmissions, ACKs, errors and close. Charge each UDP segment separately when
GSO is used, or disable GSO on this routed socket for correctness; preserve
envelope-adjusted MTU. Failed sends consume no successful-traffic credit and
must not permit a burst on retry. Datagram indivisibility yields at most one
packet of instantaneous rounding; account for it in the receipt rather than
claiming arbitrary sub-packet smoothing.

Use the existing transport's readiness and flow control. Prioritize before
bytes enter transport buffers; don't queue the entire world then hope stream
priority rescues urgent work. Reprioritize unsent work; an already transmitted
packet cannot be recalled. Maintain streaming delivery and avoid whole-region
response batching that delays first useful sections.

Try to fill the configured cap whenever useful eligible work is ready. A rate
cap is an upper bound, not guaranteed throughput: congestion control under loss,
client decoding/storage, source preparation, or a paused disk can reduce useful
goodput. Record these causes. Do not manufacture padding or retransmissions to
make a utilization counter look full, and add no sections-per-second limit.

## 6. Cache-only downloads and a small spatial frontier

Add a narrow server-level download owner; keep the active dimension's existing
render/cache owner and GPU lifecycle. All downloads have explicit dimension,
per-dimension world identity, catalogue domain, session generation, section,
purpose and revision/ticket identity. Enforce that scope at promotion and commit,
not only at request creation. Cache-only destinations in
inactive dimensions never access the active world's registry/model context.
Reuse transport/catalogue and persistence work rather than duplicating a
complete `ClientSession` per inactive dimension.

For any demand, consult committed usable cache first. Suitable stale records
satisfy coverage and detail; do not request them again as missing. Local
inventory and reads start independently of endpoint discovery, catalogue
revalidation or server acknowledgement. Reuse offline server/dimension links
and the existing named local record format.

Preserve the existing per-view-branch cache-first admission gate: hand available
cached coverage/detail to the existing publication path before requesting that
branch's missing network refinement. Do not make cached display wait for a
server check, or make all branches wait for a complete world-cache scan. If that
branch has no usable cached record, its missing-data request may proceed once
the local lookup establishes the miss. Bootstrap/control metadata may proceed
independently; it must never become a dependency of cached display.

The cache-only path is:

```
scoped request -> shared catalogue/section validation -> local named encoding
              -> atomic journal commit -> local completion -> release desire
```

Reuse canonical codec checks for extent, integrity, palette/index structure and
catalogue names. Do not expand 32,768 cells, resolve renderer-local models, bake
models, mesh, submit GPU work or force a render reload for ordinary prefetch.
Preserve palette order, lighting and biome identity. Extract validation from
the existing decoder rather than writing a second divergent codec.

If a pending prefetch becomes visible, promote the same owner/ticket. No duplicate
download, competing persistence owner or fabricated completion. The normal
cache/render path can consume the committed record; an urgent validated in-flight
record may be handed to the existing visible worker under the same lifecycle.
Cache-only completion means a valid committed record, not merely receipt of
QUIC bytes. Disconnect/cancellation cannot mark partial files complete.

Use per-LOD concentric frontiers, saved-region availability and existing local
directory lookups. Maintain a priority queue for discovered ready work and the
next ring/frontier bounds. Before dispatch, the ready heap head must beat the
maximum possible priority of undiscovered frontier work; otherwise expand that
frontier first. Square rings alone are not monotone Euclidean distance: a farther
ring's axis entries can outrank a nearer ring's corners. Expand outward lazily;
avoid creating an object for every world section. Fine near work and coarse far
work compete by the specified
score. Skip already cached, authoritatively absent and admission-rejected work
until a relevant state change; do not redownload/reject it in a loop.

Score calculation and hashed section lookup are O(1). Queue updates are O(log F)
for F active frontier candidates; an O(F) heapify when the player block anchor
changes is acceptable and necessary for correct ordering. Reanchor/rebuild
frontier bounds and discovery cursors as well as ready candidates. Coalesce pose updates
and do not repeatedly sort the entire world. Lazy rekeying of only the popped
heap entry is insufficient when movement changes other candidates' priority.
Initial file inventory is O(number of cache files), retained once and incrementally
updated. No pairwise section comparisons or rescanning a region on every append.

Release completed cache-only section desires and regional subscriptions. Keep
coverage/completion in local directories and compact discovery state, not an
ever-growing resident subscription for every downloaded key. Only actively
visible/needed interests remain subscribed for coalesced refresh. On leaving a
view or dimension, drop those interests; retained cache stays available and its
known stale status does not become immediate urgent work.

## 7. Per-server storage, admission and safe reclamation

Normalize Minecraft server addresses once, including default port, DNS case and
IPv6 spelling; do not key settings by transient resolved IP, QUIC port or rotating
certificate. Keep this policy identity separate from existing raw-address
association filenames until their namespaces are recorded in the ownership
ledger. Existing `.vxlink` files hash the raw address plus dimension: changing
lookup spelling alone would strand warm/offline caches. Retain the existing
single-format association reader and record known aliases locally without
requiring a live server, a second reader or a directory fallback.

Persist server policy, owned world/dimension namespaces, remembered
anchors and committed file accounting. Verified aliases of the same logical
server share one ownership/accounting entry; never infer shared ownership merely
from two equal file paths or endpoint IPs.

Current journals are stored by world/dimension hash and addresses have one-way
hashed `.vxlink` associations. Preserve those records in place; do not re-encode
or purge them to simplify ownership. Adopt known namespaces into the new ledger
using authenticated identity and existing associations. Attribute each physical
file once. Distinct owners must not double-charge it or let one server evict
another's cache. Ambiguous existing ownership preserves files and suspends
ambiguous destructive admission/eviction until identity is resolved; it does
not disable offline terrain reads. No legacy-directory fallback or dual cache
format. Report unresolved ownership instead of quietly excluding old files from
the allowance.

Inventory complete cache-owned regular file lengths, not just active record
bytes. This counts stale append history and all named-cache/metadata overhead;
temporary replacements count concurrently with their source files. Maintain
actual file length deltas through append, truncation, commit, rename, deletion
and restart recovery. Do not give staging files a free allowance. File-length
accounting is the portable file-byte measure; distinguish filesystem allocation
rounding from this measure in receipts if the platform reports it.

Before ordinary request admission, use destination region rank, available
allowance and eligible least-important victims. The wire frame's compressed
length is not exact local encoded length: use known local sizes/encoding bounds
where available, then revalidate admission as actual encoded growth is known.
Do not download a whole region just to discover it can never be retained.
Pinned readers/writers and currently protected visible regions cannot be
deleted while in use; skip/defer unsafe candidates instead of scanning every
section or forcing a cache reload. Update eviction ordering on anchor/visibility
changes with the same exact comparison; ties retain residents.

Obsolete journal records consume disk but never importance. When history
reclamation would allow useful writes, compact a region's current committed
bindings using the existing recovery/atomic publication rules. Keep the old
file valid until the replacement is validated and committed. Account both files
against the selected allowance and available physical space. If safe compaction
cannot fit, pause/refuse that append; never replace it with whole-region deletion
under a journal-size threshold. Whole-region eviction occurs only through the
strictly better destination policy or an explicit user reduction of allowance.

Unknown inventory suspends new destructive admission; it does not mean zero
existing usage or permission to remove files.

At quota pressure, evict whole eligible least-important region journals until an accepted
write fits; do not let cache byte counts exceed the selected numeric allowance.
Victims are complete `.vxlocal` region journals only. Retain and count server
ownership, offline `.vxlink` associations, catalogue and discovery
metadata. They are not spatial eviction candidates: removing a link can make
otherwise retained terrain unavailable offline. Reclaim only metadata proven
unreferenced through the existing lifecycle, not insertion-order deletion.
If the user lowers the allowance below existing usage, reconcile unpinned files
in retention order without disrupting in-use reads; report pending excess while
pins release and suspend new growth. `Entire world` has no configured numeric
eviction ceiling, but it still obeys physical disk failure behavior.

Probe filesystem space before destructive eviction/compaction. If physical
exhaustion is known or an actual write reports out-of-space, stop new downloads
and preserve committed regions. Abort only an uncommitted tail/temporary file
through existing safe recovery. Expose the paused reason in Sodium tooltip/logs;
resume after a meaningful disk-space/policy change, not repeated download/write
failures. Ordinary cache reads and installed geometry remain usable.

## 8. Server coalescing and work ownership

Keep saved-chunk notifications and the shared incremental regional builder.
Frequent source changes mark dirty terrain; collapse repeated changes to a
section into the latest replacement binding for each eligible update interval.
Clients receive validated terrain replacements, not every block mutation.

The one-second-or-more setting delays refresh, not cold section generation or
cached reads. Only changed interested sections enter the low-priority refresh
queue. Newer revisions supersede unsent older ones. If a newer save arrives
during an in-flight replacement, retain current terrain until a validated newer
replacement can publish; don't create a gap or an unbounded revision backlog.

Send the current refresh gate and dimension anchors/priorities to the native
owner through the scoped control stream, or express them as conditional refresh
subscriptions. Enforce eligibility before selecting/queuing refresh bytes in
QUIC, not by discarding updates after receipt. A closing gate stops unsent refresh
work without withdrawing usable cached geometry or missing-data interests. When
the gate reopens, coalesce to the latest changed binding rather than replaying
every intervening revision. Already transmitted bytes cannot be recalled.

Publish prepared terrain once and share it among requests; preserve direct
spatial lookup and atomic generation publication. Store resident state for
current work/visible interest, not one source-table or subscription copy per
downloaded key per player. New discovery should reuse the existing inventory
and publication signals, not add frequent full-world scans.

Retain compact inventory once per shared source. Load larger source/index tables
for current builds/requests and release them when their owners finish rather
than retaining every full table touched by an entire-world sweep. Use ownership
and on-demand lookup, not another numeric residency budget.

Also retire the existing cold-import scan multiplication: the current publication
loop can call `refresh_all`/`region_headers` across all R regions again after
publishing just one region, producing O(R squared) inventory work. Keep a
successful inventory snapshot and cursor across incremental publications, verify
only affected sources before committing, and reconcile on actual inventory
changes or recovery. Avoid the global quadratic path as well as per-client
copies; no additional periodic quota is needed.

## 9. Implementation order and source touchpoints

1. **Policy and ownership.** Replace the old bandwidth field and global disk
   allowance; add per-server settings/ownership and two Sodium sliders/tooltips.
   Preserve cache readers and all renderer controls. Define exact dimension-scoped
   work identity and geometric comparator before adding scheduling.
2. **Bootstrap, discovery and transport.** Register total pacing before QUIC,
   merge the old two connections, extend authoritative dimension/border/layout
   discovery and scoped messages. Keep compressed shared catalogue and held
   fingerprint reuse. Remove superseded handlers in the same change.
3. **Cache-only frontier.** Add per-LOD spatial candidates, current-dimension
   ordering, visible promotion and finest-detail preparation. Validate/commit
   prefetch without render/model work; release completed remote desires.
4. **Retention and reclamation.** Implement strict whole-region admission/eviction,
   replace insertion order, remove destructive journal rotation, and handle
   safe compaction, numeric allowance changes and physical-disk pause.
5. **Refresh and live proof.** Connect accepted quality gate, distance score and
   coalesced update interval. Verify on the real PC using the checks below.

| Responsibility | Existing files to modify or extract from |
| --- | --- |
| Sodium settings and server policy | `src/main/java/me/cortex/voxy/client/config/VoxyConfig.java`, `VoxyConfigMenu.java`, `src/main/resources/assets/voxy/lang/en_us.json` |
| Cache-first view ownership and scoped download promotion | `src/main/java/me/cortex/voxy/client/lod/ClientSession.java`, `SectionDemandTable.java` |
| QUIC discovery/messages/records | `shared/src/main/java/me/cortex/voxy/network/QuicEndpointPayload.java`; client `QuicEndpointDiscovery.java`, `RegionalProtocol.java`, `RegionalQuicClient.java` |
| Named cache validation/persistence | client `RegionalSectionCodec.java`, `LocalSectionCodec.java`, `CompletedSectionCache.java`, `CompletedSectionJournal.java` |
| Server ownership/accounting/retention | client `RegionalMetadataStore.java`, `RegionalDiskBudget.java`, `LocalCacheOwnership.java` |
| Authenticated native registration and live world metadata | `server/src/main/java/me/cortex/voxy/server/VoxyServer.java`, `RustBackend.java` and existing save-notification path |
| Unified wire pacing and scoped interests | `rust-server/src/server.rs`, `pacer.rs`, existing protocol definitions |
| Saved dimensions/layout/availability and shared builds | `rust-server/src/main.rs`, `anvil.rs`, `regional/service.rs`, `regional/runtime.rs` |

Prefer one small explicit server-download owner and one persisted per-server
policy/ownership record, reusing existing files where responsibilities fit.
Do not stuff unrelated code into compressed one-liners to lower line counts.
No numerical source-line/file ceiling is active, but count added/removed readable
source lines, files/folders and debug JAR/native binary sizes using the existing
audit method. Retire background-only code rather than growing a parallel stack.

## 10. Live verification and acceptance evidence

Future verification uses the user's real **PC** client. No laptop testing,
integration tests, new automated test suite, 100-client runs or mutation pressure
runs. All 100-player/300-change pressure verification remains suspended until
the user explicitly re-enables it; do not report it as passed. Builds establish
compilation only. This documentation task performs none of these live actions.

Before any future deployment, resolve exact PC/server processes, artifact hashes,
world roots, active controls and two independent backup SSH helpers. Preserve
both backup helpers and automatic client updating. Match debug client/server/
native artifacts and use staged replacement with rollback material. Preserve
the external testing Rust ceiling (`memory.max=999997440`, swap disabled) and
existing Java heap arguments (`-Xms1G -Xmx4G`); verify actual limits before load.
Do not weaken them to get a result. Main source/world files stay read only.

Required receipts, using scoped real-client live work:

1. **Settings and scope:** screenshot Sodium current-server controls/defaults;
   change server/dimension and reconnect/restart to demonstrate persistence,
   one server-wide storage/rate ledger, and no extra background-only slider.
2. **Cold useful delivery:** use a separate owned empty cache namespace rather
   than deleting the user's main cache. Capture first coarse coverage, required
   visible detail, finer cache preparation and concurrent farther rings. Record
   wire timestamps and catalogue counts; quantify source/transport/persistence
   wait when the cap is not utilized. No assertion of guaranteed saturation
   under severe loss or storage backpressure.
3. **Wire cap:** packet receipts at server egress/PC ingress for 100 kbps, 1 Mbps
   and 10 Mbps, including handshake and retransmissions. Sum the actual IP
   bytes and correlate one aggregate ledger across dimension/reconnect activity.
   Verify rate changes and single-datagram rounding; compressed payload counters
   alone do not prove the cap. Use the user's current throttled PC conditions;
   do not change them without task scope.
4. **Cache-only operation:** counters/traces show ordinary prefetch validates
   and commits without model baking, 32,768-cell expansion, meshing or GPU
   publication. Later viewing/zoom uses those records through the existing
   cache/render path, with no redundant missing-section network request.
5. **Warm and offline:** reconnect, then perform a full PC JVM restart with
   only Voxy backend connectivity blocked in a scoped reversible way. Saved
   terrain must load before/without HELLO or catalogue confirmation. Screenshots
   and actual loaded artifact identity are required, not just cache-hit counters.
6. **Quota and retention:** use a dedicated live cache namespace with a numeric
   allowance. Show whole-file accounting across dimensions, nearer admission,
   rejection of equal/lower importance, fresh-data retention and no journal-history
   rank increase. Inspect safe compaction/recovery without arbitrary region reset.
   Demonstrate out-of-space pause using a dedicated reversible constrained cache
   destination, not by filling the PC's system disk. Preserve committed cache.
7. **Changes:** make a few scoped live saved block edits in the existing testing
   world, restore them, and capture latest-state coalescing at selected intervals.
   No 300-changes/sec or synthetic pressure run while suspended. Stale visible
   terrain remains installed until a valid replacement is available.
8. **Borders, dimensions and completeness:** verify authoritative live metadata,
   fallback behavior, intersecting whole sections, inactive centers, custom
   heights and dynamic saved-region discovery with owned existing test terrain.
   Unknown/error source states must block completion claims.
9. **Main-square coverage:** read-only Main metadata at planning time says center
   `(0,0)`, `BorderSize=10016`, target size also 10016, no active lerp: saved
   radius **5008**. The user's minimum acceptance square is radius **5000**.
   Re-read authoritative runtime metadata during verification; do not change
   Main's border to match a number. Both saved/requested squares intersect a
   20-by-20 grid of 512-block footprints spanning `[-5120,5120)` horizontally.
   Build the expected coarse key set from saved source occupancy and all stored
   vertical slots, not just 400 assumed nonempty sections. Demonstrate committed
   coarse coverage of every saved eligible part, with absent/unknown separated,
   from anchor `(0,1000,0)` using the real PC and read-only terrain source.
   Use the owned testing world/client camera setup for that pose; do not teleport
   or modify Main to obtain a screenshot. Record snapshot/source identity if an
   existing copy of Main supplies the terrain rather than the live files.

   Render distance is horizontal/circular, so reaching the square's corners
   requires over 7071 blocks for radius 5000 (over 7082 for 5008), plus the existing
   intersecting-section margin. The last-inspected PC setting corresponds to about
   3328 blocks and cannot show that square. Existing Sodium range supports up
   to 32768 blocks: its UI is in **chunks**, not blocks. Use a suitable existing
   setting for this receipt and restore user settings afterward; no renderer
   range extension is needed.

   At Y=1000, a straight-down ordinary-FOV screenshot cannot contain the whole
   square: even flat Y=0 terrain needs approximately 157.4 degrees vertical FOV.
   Prove retained all-square coverage and take a camera sweep at that position
   with normal projection/render settings. Do not claim a single ordinary-FOV
   image proves the full square, and do not alter renderer/FOV to hide this.
   Cache coverage and actual rendered continuity are separate acceptance checks.

Record live artifact hashes, timestamps, scoped changes/restoration, source
inventory identity, actual disk/IP bytes, snapshots and screenshots in
`project_audit`. Explain failures as failures. Do not claim that pipeline
counters, successful compilation or a nominal completed frontier establish
correct textures, seams or gap-free on-screen rendering.

## 11. Completion and limits

Implementation is complete when the accepted settings, scopes, ordering,
cache-only path, exact ties, whole-region retention, wire accounting and offline
behavior are implemented, superseded paths removed, and the permitted real-PC
checks have concrete receipts. Source-size/binary deltas and remaining live
limitations must be reported. Suspended pressure verification remains an
explicit unverified item, not work silently run or claimed complete.

Whole-world completion under continuous higher-priority demand is not promised;
the user accepted that tradeoff. Whole-world storage under a too-small numeric
allowance, maximum useful throughput under loss, and instantaneous delivery of
uncached zoom detail are also not implied by a score. The immediate zoom path
comes from cached finer levels. If live experience exposes distant starvation,
adjust importance values in a later scoped change before considering another
scheduling mechanism.
