# Per-server world-cache implementation

Status: implemented, built, deployed and permitted real-PC checks completed in debug 256, 2026-10-05. Temporary runtime state is restored. Receipts remain version-specific; unverified acceptance cases are listed explicitly.


Current qualification (2026-10-05): debug256 completion does not establish lifecycle correctness. The subsequent [repair implementation](per_server_world_cache_lifecycle_repair_implementation.md) fixes the reviewed defects and separates source completion from limited live evidence, including the missed600-second cap.
Scope: `feature/cache-first-background-updates`, Voxy_Testing and GIORKOSPC only.
Original source trees and Main are read only. Normal PC cache is preserved;
quota/cold work uses an isolated debug-profile cache. No integration/unit suites,
100-player verification, or mutation pressure runs.

## Implementation

- One persisted per-logical-server total QUIC download cap (100–10,000 decimal
  kbps, default 1,000); native registration precedes the handshake. Successful
  UDP sends include the routing envelope and IPv4/IPv6+UDP headers, with one
  ledger retained across dimension changes and reconnect overlap.
- Per-server all-dimension actual-file quota (default 500 MB), growing Entire
  world estimate, protected ownership/offline metadata, exact spatial region
  retention, atomic history reclamation and physical-disk pause.
- One Minecraft-connection download owner, scoped dimension/world/catalogue
  identities, lazy saved-terrain discovery/frontiers and cache-only validation
  and named-record commit. Existing cache display and GPU/mesh ownership stay
  in place.
- Exact integer distance/LOD comparator, coverage and required-detail priority,
  finer visible preparation, current-dimension-first ordinary prefetch and
  low-priority changed-section refresh with the accepted quality gate.
- Initial OPEN contains only visible misses after local cache lookup; cached
  watches follow HELLO. Control work is split by actual QUIC packet capacity,
  avoiding megabyte-long cached-HAVE startup frames. The actual nonoverlapping
  visible cut controls quality/area scoring; camera changes reprioritize missing
  work. The 255 cached-air child case exposed a child-watch gap. The 256
  correction restores needed child/watch ownership; the scoped target
  network/cache/restoration receipts below establish that case, not every topology.
- Sodium controls dynamically select the current server at menu rebuild;
  changes save/live-apply without rebuilding the renderer. Old background-only
  cap, background connection/socket and old special foreground policy removed.
- Native cold-import inventory cursor avoids scanning all source headers after
  each publication; full source tables belong to current transactions. Source
  timestamp changes remain build triggers but do not become wire availability
  changes when saved/readable footprint is identical.

## Build and deployment receipts

Matched debug 249 exposed a live bootstrap stall while cached terrain rendered.
Debug 250 fixes OPEN admission and obtains HELLO; normal cache activates before
HELLO. Remaining source issues found during real verification are included in 251.
BuildAll compiles normal/debug client and server plus native; it runs no test suites.

Receipts: `deployment/staged249.json`, `runtime249.json`, `staged250.json`,
`runtime250.json`, `live_client/pc250-warm-bootstrap.json`, and actual
`live_client/world-cache-pc249-warm.png`. These are dated results, not final 251 proof.
The Testing wrapper trace-name handoff changes only the inherited debug environment
name; byte-for-byte safety settings are retained in `deployment/wrapper-trace251.json`.

## Independent packet observation and limits

A transparent GIORKOSPC UDP relay observes unchanged QUIC datagrams at ingress,
including envelopes, handshake, retransmissions and close. It does not decrypt,
shape traffic, change impairment tools, or touch other applications. The PC has
clumsy and NLSvc processes, but process names alone do not establish their enabled
settings. Relay readiness/identity and the two surviving independent SSH helpers
are recorded in `live_client/pc-relay250-health.json`.

Raw IP capture is unavailable with the current non-admin PC login; pktmon reports
access denied and server capture lacks granted privileges. UDP payloads are
observed; IPv4 totals use inferred 20+8-byte headers and must be labeled that way.
The out-of-space preflight has no existing user-accessible constrained filesystem
provider. Do not fill the system disk or substitute a simulated budget failure
for actual filesystem exhaustion. See `live_client/pc-disk-test-preflight.json`.

## Recorded live results

| Check | Actual observation | Receipt / qualification |
| --- | --- | --- |
| Sodium policy controls | Real 251 screenshot shows current-server total bandwidth, storage and refresh interval alongside original Voxy controls | `live_client/pc251-sodium-current-server.png`; final 255 dynamic bindings reviewed in `per_server_world_cache_code_review.md` |
| Wire cap / reconnect | Actual UDP packets observed at 100/1000/10000 kbps; one native route ledger across QUIC reconnect | `live_client/pc251-rate-windows.json`; IP headers inferred 28 B IPv4/UDP, not raw capture; unsaturated link |
| Full-JVM offline cache | 252 PC JVM started with Voxy-only endpoint unavailable; first local activation 0.7316848 s;16857 active;0 terrain received,0 HELLO,0 catalogue frames | `live_client/world-cache252-offline-run/result.json` and `pc252-full-jvm-offline.png`; exact namespace clone/source hashes preserved |
| Offline numeric quota | 253 allowance 100000000 B applied while HELLO absent;98277165 B/21 files/14 journals retained,206 whole journals removed;0 metadata victims/0 survivor changes/0 normal-cache changes | `live_client/pc253-quota100MB-files.json`; actual all-dimension account; restored 500 MB |
| Cache-only commits / protocol correction | 254 live trace recorded 679 cache-only commits,313974 compressed input bytes,0 failures,0 reconnects;89 worker samples identify CACHE_ONLY_NETWORK | `live_client/pc254-online-pipeline.txt`, `pc254-cache-only-workers.txt`; transient old descriptor mismatch fixed in native, preserves DATA air/biomes/light |
| Descriptor error cause | 253 rejected DATA carrying wire EMPTY flag for geometry-air with actual body;81943 valid affected stored entries in 447 publications | `live_client/pc253-data-empty-descriptor-reset-20261005.json`;254 native normalizes wire flags only, no terrain deletion |
| Dimension handoff | 255 Nether is HELLO-accepted with same QUIC epoch 1/route; saved FULL origin height 0..255; returned to original Overworld position | `live_client/pc255-dimension-handoff-observation.json`; existing debug pose harness rejected normal renderer replacement, so no harness PASS claimed; End origin unsaved, no End teleport |
| Same-format compressed cache | Windows PowerShell 5 observer compiled, checked committed journal metadata, CRC32C and BLAKE3-128; no rejected bodies in initial isolated-cache scan | `live_client/pc254-journal-before.json`; observer reads shared and never repairs/deletes/decompresses/bakes/meshes |
| Latest build / auto-update | 256 buildAll PASS (no test suites); matched artifacts; final normal-profile PC JVM PID 31200 has the 256 client hash, both SSH helpers alive | `deployment/staged256.json`, `runtime256-final.json`, `live_client/pc256-final-restoration.json` |

All versioned results above are observations at the stated artifact, not a claim
that every check was repeated on 256. The 254 online harness ultimately recorded
DISCONNECTED during the profile/update transition; its completed typed trace and
screenshot establish that subset only. The 253 online run is FAIL, not silently
converted to a success after the native correction. Debug profile leases also
caused extra client restarts during renewal; these are not a QUIC terrain error.

Final 255 adds an account-scoped disk-space recovery generation, reoffering only
physical-space failures (including delayed worker results), and marks ownership
ledger out-of-space failures as the same pause. Prefetch admission charges the
actual 120-byte binding-frame minimum. Cold DESIRE does not carry body lengths,
so exact body/encoding size is rechecked after receipt and at each actual write;
no arbitrary worst-case or average estimate is invented.

## Main-square committed-cache proof completed on 255

Main runtime border was queried read only: width 10016, center (0,0). The
independently copied source/publications have 400 eligible 512-block horizontal
footprints and 800 PRESENT DATA LOD 4 keys across Y=-1,0. See
`main_coverage_snapshot_receipt_20261005.json` and
`live_client/main_snapshot_expected_coarse_sections.json`. The copy is stable
per source/publication pair, not one atomic whole-Main tick. Main was not
teleported, edited, restarted or used as the mutable test service.

At 2026-10-05T14:53:19.9389271Z the independent read-only PC journal observer
found all 800 expected current DATA bindings with authenticated compressed
bodies, all 800 source content fingerprints/child masks matching the copied
publications, zero missing/empty/absent expected keys, zero fallback bodies and
zero sections read from changing files. It reported zero journal errors.
`live_client/pc255-main-cache-final.json` contains the compact live receipt;
`live_client/pc255-main-completion.json` records completion, scope and timing.
The full 1805204-byte metadata file is `live_client/pc255-main-full-final.json`,
SHA-256 `65b68be513f92be6b44d0e973cb6f9d3701aca7cfdcdfb1288153dc4fb3910b7`.

The observer checked compressed CRC32C/BLAKE3-128 and unchanged read metadata;
it did not decompress all bodies or prove all terrain was meshed/drawn.
Only 146 entries also match the current catalogue fingerprint exactly; all 800
match expected source content/children. Retained historical publications use
stable registry IDs under the current shared catalogue. Catalogue-only
mismatches are reported separately, not hidden or labeled content changes.
The receipt's complete result therefore proves the scoped copied-source coarse
cache predicate, not perfect visual correctness of every cached voxel.

The operator-only adapter used copied terrain with Testing's unchanged external
limiter; it is not production code. Its native cwd and actual limits are recorded
in `deployment/restart255-main-snapshot.json`. Minecraft near chunks still came
from Testing, so this source-mixed case does not prove normal-to-LOD seams.
The camera sweep used (0,1000,0), normal 70-degree configured FOV, the original
pixel-size setting and temporarily 512 MC chunks of horizontal render distance.
`live_client/world-cache255-main-progress/screenshots.json` records the captures;
`pc255-main-final-held.png` and the north/south/east/west and diagonal images are
scoped visual evidence. No single ordinary-FOV image is an all-square proof.
Fog/weather constrain extreme-distance visibility. Completion took about
69 minutes from the approximate 13:44 cold-start receipt while camera changes
and finer preparation competed; no guaranteed deadline or saturated-link claim.

Testing's original launcher/configuration were exactly restored, and the normal
255 server/native cwd was back at `/home/aerosmp/Desktop/Voxy_Testing` by
14:57:11.890444Z. See `deployment/restore-normal255.json` and
`deployment/restart255-restored-normal.json`: Java retained its 1–4 GiB heap;
native retained memory.max=999997440, swap.max=0 and zero OOM counters.
The task-owned transparent relay PID 27996 exited at 15:01:42.4872440Z;
`live_client/pc255-relay-stopped.json` also records both backup SSH helpers alive.
This restoration covers the temporary Main-copy backend/relay; final camera,
policy/profile and saved-block restoration still require their own receipts.

## Saved-change defect preserved on 255; scoped correction verified on 256

`live_client/pc255-three-saved-changes.json` records one existing FULL Testing
block at (-234,115,60), originally air, saved as gold, diamond and then stone
in three separate completed saves over 0.350698 seconds. This is scoped real
source work, not mutation pressure. That historical receipt records stone and
restorePending=true; subsequent saved-air restoration is proved separately below.

`live_client/pc255-target-chain-native-comparison-20261005T152818Z.json`
compares the PC journal observation at 15:18:25.9753540Z with later native
generation 16644. LOD 1–4 changed from baseline, have authenticated compressed PC
bodies and match current native source fingerprints/child masks. LOD 0 key
`003000001fffff80` remained at its cached-air fingerprint
`48cc176a44cab04735bd4f6612c230d5` while native DATA was
`97f10e057be6bd9a14b3f12a42eda4b7`. The old PC body was integrity-valid: the
failure was needed child/watch ownership, not journal corruption. The historical
255 run `454d7419-969f-41d9-925b-fbb5a864eceb` is finalized FAIL in
`/home/aerosmp/Desktop/Voxy_Testing/logs/voxy-tests/454d7419-969f-41d9-925b-fbb5a864eceb/result.json`.
Its failed result is retained, not converted into a PASS by the 256 correction.

On 256, `live_client/pc256-target-watch-events.txt` first records cached-air
activation at 15:36:55.2097997Z. The same target has a new HAVE watch at
15:39:13.3015694Z with cacheActivatedFrame=-1, identifying the network-sourced
activation before the later 100 MB quota experiment around 15:41. Thus the new
stone binding was not first produced by quota eviction and a cold reload.
`live_client/pc256-target-fine-stone2.json`, completed 15:41:17.1843864Z, has a
stable journal read, validated payload metadata, authenticated named compressed
CRC32C/BLAKE3 and source fingerprint `97f10e057be6bd9a14b3f12a42eda4b7` matching
the native saved-stone section. Its parent chain is also present/authenticated;
LOD 1 child mask is 68, reflecting the new nonempty branch.
The 256 run is `1d9c63c0-aee4-47d5-9b59-4f719bba9435`; its step 8 receipt restores
500 MB storage at 15:42:53.354044133Z. These receipts establish the scoped
fine-section network/cache lifecycle; they do not establish precise update
cadence or complete delivery of every intermediate block state.

`live_client/pc256-target-restoration.json` records saved original block-state
restoration to minecraft:air at 15:44:12.105630Z and removal of the task-owned
force-load. The explicit query says chunk[-15,3] is no longer force-loaded.
`live_client/native256-target-restored-air1.json` then records stable paired
native generation 16829 with source fingerprint
`71dfd8cd59a1dabcb56ddccc57a1bb19`, geometry-empty but with a 20-byte DATA body.
`live_client/pc256-target-fine-restored-air1.json`, completed 15:44:52.0733974Z,
authenticates the identical fine fingerprint and its committed named body;
all five chain bindings are authenticated and LOD 1 child mask is back to 4.
The restored air fingerprint need not equal the old `48cc...` baseline:
legitimate saved skylight changes affect section data. Restoration proves the
original block is air and owned force-loading is removed, not whole-chunk byte
equality or reversal of every lighting computation.

The 255/256 target screenshots were inspected but are essentially uniform at the
extreme zoom used. They do not establish a visible stone block, target-specific
GPU identity, perfect meshing or material correctness merely from image color.
The per-key cache/native comparison and watch provenance are the stronger proof.
The scoped 256 run `1d9c63c0-aee4-47d5-9b59-4f719bba9435` completed and was finalized
PASS after saved-block/cache comparison, restoration and acknowledged END. This
is a scoped cache-update/source-restoration result, not a universal renderer claim.
See `live_client/pc256-scoped-update-completion.json` and the Testing run receipt.

`live_client/pc-normal-preserved-final-before-off.json` records 227 original normal
cache files totaling 220441284 B unchanged/not missing at 15:46:13.2306187Z. The
receipt compares those original files; it does not inventory newly created files.
Both backup SSH helpers remain alive. The final off-profile request caused a full
JVM restart: PC PID 31200 started at 15:50:39Z with the matched 256 jar. Its normal
cache first activated at 0.784669599 s, before HELLO at 11.988952699 s; the final
pipeline reports zero missing coverage and no streaming failure. The actual
public endpoint is `95.164.127.81:25787`, Minecraft `play.aerosmp.com:25587`.
The allowance is restored to 500000000 B, cap 1000 kbps, interval 2 s and render
distance 208 chunks. Position is restored to (-89.90859400985697,
109.75106051840818,190.1464646879812), yaw 153.36505 and pitch 19.592451. Zoom,
transport holds, the owned relay and force-load lease are released. The normal
view run `61cbf565-39fa-4b1f-b7ff-b8b92529a0c3` completed/PASS; its real screenshot
was inspected. See `live_client/pc256-final-restoration.json` and
`live_client/pc256-final-normal-cache.png`. Normal cache may now legitimately
receive new writes; its before-off preservation claim remains timestamped.

`deployment/runtime256-final.json` verifies actual Testing JVM Xms/Xmx 1/4 GiB,
native memory.max 999997440 B, swap.max 0, peak 608399360 B and zero OOM events.
The normal Testing source/configuration is restored, not the copied Main adapter.
`original-worktree-status-final256.json` uses the same all-untracked Git status
scope as the previous receipt and has identical original-worktree status hashes.

## Limits and remaining verification

Physical out-of-space could not be demonstrated: PC login is non-admin, no
existing accessible constrained filesystem provider was found, and the system
disk has ample free space. Filling that disk or simulating a failure would not
be an honest safe proof. Raw-IP egress/ingress capture likewise lacks permissions;
UDP packets plus inferred IPv4/UDP headers are the available receipt. There are
no measured QUIC RTT/cwnd/loss counters, so unsaturated throughput is not assigned
a precise transport cause. Current native/worker CPU and physical disk samples
are low while useful traffic continues; see
`live_client/pc255-main-native-source-network-audit-20261005T140503Z.json`.

Unsupported dimension layouts outside the original signed section-key height
range are explicitly logged/omitted; see `unsupported_dimension_height.md`.
No exhaustive custom-height/source-error/disk-recovery injection proof or perfect
texture/seam/FPS claim is made. The visible-cut observer adds readback/allocation
work; that impact remains unmeasured. Main coarse cache completion and temporary
backend/relay restoration are recorded above. The 256 needed-child update and
saved-air/force-load restoration and normal PC cache/profile/policy/pose return
have concrete scoped receipts above. All observation runs are closed.

100-player verification, 300 changes/sec and mutation pressure remain suspended,
unrun and unclaimed. No integration/unit suites or new automated test suite ran.

## Source and artifacts: final 256 source snapshot

The 256 count/artifact snapshot is recorded in
`per_server_world_cache_source_count_20261005.json` (15:42:25Z), using the same
helper and 745084e baseline. Release source is 36326 physical lines/183 files
(+3304 lines/+2 files); all maintained sources including shared/debug/tools are
49290 lines/249 files/122 directories/zero empty directories
(+2245 lines/+1 file/no directory increase). All 3 new untracked production
classes are included; removed background socket and obsolete Python pressure
tool are excluded. Debug source is 4694 lines/22 files; tools 1592/11; shared 947/11.
These are physical source counts, not a minification result or performance proof.
The ledger retains prior 254/255 snapshots and full scope/hash/baseline limits.

`deployment/staged256.json` records the matched debug client/server/native:

- Client 4043129 B, SHA-256 `4c974d1fa21ef7ac935b8f6812e97cd0a0ac3a1a50a7cf7d48df77882ac2eb45`.
- Server 1745743 B, SHA-256 `de250efa3327d4689e2b88528305230a1db801e9b404c442d9e5ac9de06850ff`.
- Native 3418512 B, SHA-256 `f5840addd21a7c1aa5df28df4392916b04c7487fb9ae18a1d5cc270d8a32bc29`.

Normal client/server jars are 3888381/1679734 B, with hashes in the count ledger.
The live PC identity at 15:37:23Z records that 256 client hash, game PID 31248 and
both independent SSH helpers 19916/22444 alive. Build success is compilation
only; old version receipts are not silently treated as repeated 256 checks.

Original ASMP_Voxy and Restart worktrees are not the deployment target. Git remote
publication was rejected by automatic approval review as outside this task's
current authorization; no push retry/bypass is performed.
