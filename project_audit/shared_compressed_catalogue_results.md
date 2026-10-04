# Shared compressed catalogue execution

Implemented on `feature/cache-first-background-updates`, using
`ASMP_Voxy_Cache_First_Updates`. Matched debug248 client, Java server and native
server were built, deployed and identified in the running PC/Testing processes.
The original ASMP_Voxy and ASMP_Voxy_Restart working trees were preserved.
The user's subsequently withdrawn bandwidth/storage-slider proposal was not
implemented. No integration tests or automated test suites were run.
The user suspended all 100-client verification before implementation; none was
started for this feature, and neither mutation-pressure phase was run.

## Implementation

- Rust builds one immutable Zstd level-1 catalogue frame per registry snapshot.
  Eight foreground lanes share one serialized primary control writer and its
  fingerprint announcement set. This adds no catalogue request or receipt ACK.
- The client reads catalogue metadata independently of terrain workers. Each
  lane consumes its one owned section body before waiting on the shared exact
  fingerprint binding, allowing connection receive credit to progress.
- Strong fingerprint aliases share verified block/biome prefixes. Names are
  resolved lazily through existing mappings. Accepted catalogue ownership no
  longer depends on weak references or repeated full historical tables.
- One optional `catalogue.vxcat` is probed and atomically replaced through the
  existing metadata store, pins, writer and disk accounting. A validated held
  binding can be advertised in OPEN and background greeting. Named terrain
  journals remain independently usable offline.
- Background-only definitions use the existing wire-paced connection. Urgent
  foreground misses may send one rescue definition on primary. Reconnection
  releases abandoned background claims, and an older completion cannot clear
  a newer foreground owner. No transport windows or stream counts were changed.
- Cold empty-cache startup previously waited for local region metadata before
  sending OPEN even when world identity was unknown. OPEN/HELLO was also needed
  to learn that identity. The local-first wait now applies only when an identity
  is available, removing that startup cycle without delaying known cached data.
- Superseded raw/per-lane catalogue handling was removed. No legacy decoder,
  protocol negotiation, library or production source file was added for approach1.

Lookup is expected O(1); prefix append is amortized O(1). Validation, hashing and
compression are O(C) for C catalogue bytes. Retained names and fingerprint aliases
are O(N + K) within a compatible catalogue domain. This feature adds no per-voxel
catalogue scan.

## Live PC evidence

The authorized cache reset occurred before implementation. Request
`24db0342-b886-4227-8f70-13229adc7445` stopped the old game and removed
32,243,236 bytes from only the active PC profile's `.voxy`. The two backup SSH
helpers survived. Other profiles were untouched.
Receipts: [reset](live_client/approach1-reset-verified.json) and
[loaded build](live_client/approach1-pc248-online-verified.json).

| Case | Actual result |
|---|---|
| Empty cache on debug247 | After about ten minutes: zero HELLO, received bytes and geometry. This exposed the startup cycle above. |
| Cold debug248 | Eight foreground lanes shared exactly one required catalogue: 355,925 canonical bytes, 48,096 compressed bytes, 48,141 framed bytes. First HELLO took about 19.67s. Terrain subsequently streamed: 288 geometry sections and 1,291,417 completed terrain bytes by 23:43:06 UTC. |
| Full PC JVM restart with Voxy transport held | 304 cached sections reached geometry, with 363/671 draw counters, zero connection/HELLO/download bytes and one locally loaded catalogue. First local activation was 403,116,800ns after session start. |
| Reconnect with cached catalogue | OPEN advertised `dd9d633e3da37dee2d4dc2df8b5817cf4d77550402ef80812b9b0289a904bf16`. Native trace and PC counters both show zero catalogue frames/bytes. By 23:53:15 UTC there were 691 geometry sections and 2,249,853 fresh terrain bytes. |
| Lifecycle | Actual PC debug248 jar hash matched publication; two independent SSH helper processes remained alive. The transport hold expired and normal connectivity resumed. |

The old eight raw frames totalled 2,847,728 bytes. This cold definition transfer
used 98.31% fewer application bytes. That is measured framing/byte saving,
not a controlled latency or total-network-traffic improvement claim.
Server writer timing measures transport acceptance, not client receipt.

Evidence:
[offline counters](live_client/approach1-pc248-offline-verified.json),
[warm PC counters](live_client/approach1-pc248-warm-resumed-verified.json),
[warm native trace](live_client/approach1-pc248-warm-native.json).

The screenshots were captured and viewed:
[cold online](live_client/approach1-pc248-online-20261004T234058Z.png),
[offline](live_client/approach1-pc248-offline-20261004T234741Z.png),
[resumed](live_client/approach1-pc248-warm-resumed-20261004T235424Z.png).
They show incomplete/coarse terrain coverage with exposed slab faces around the
normal chunks; they do not establish correct complete visible coverage. The
cause of the apparent slabs is not established. Catalogue changes
do not change cell order, keys, child masks, meshing or shader transforms; source
review found matching Java/Rust key and child layouts. Those facts do not prove
the screenshots correct or establish whether the visible problem predates248.
All24 core renderer source files and35 shader files are byte-identical to the
restored original; their sampled key decode and camera-relative transform also
agree. The offline cache contained only LOD3/4 and lacked194 coverage nodes;
sampled cached keys have valid Y origins of0 or−256. Those are possible reasons
for disconnected coarse fragments, not proof that every rendered section is
correctly positioned. No speculative mesher or renderer changes were made.

Exact impairment settings during the captures were not controlled or verified.
The earlier debug247 incident and this session therefore cannot establish a
repeatable cold-load latency improvement. Catalogue-to-first-terrain delay also
cannot yet be attributed precisely to network loss, control/data ordering or
payload delivery from the available timing seams.

## Build, resource and size evidence

Passed `cargo check --offline --locked --bins`, Java/debug/server compilation
and `./gradlew --offline buildAll --console=plain`. No test task was invoked.

| Artifact | SHA-256 | Bytes | Change from247 |
|---|---|---:|---:|
| Client debug248 | `8e979f8fa0342d06670ac37c958c3797cb112cbec001daa8ba52edf6985864bf` | 3,937,951 | +9,590 |
| Java server debug248 | `d5313041c40b10fbf47096859bb897b332d3860b89029a73bec57646434869a0` | 1,722,338 | +8,466 |
| Native server | `6006cda57c29bbd9aa71fc744462f6fa67b592ad7aaf2383e2b7de9ab8e0bb48` | 3,398,304 | +22,272 |

The native binary is embedded in the server jar; do not add it again to the
combined shipped jar size. Actual native executable hash was checked after
Testing restart. Java remains Xms1G/Xmx4G. Native cgroup memory.max remains
999,997,440 bytes, swap.max0, with zero OOM/max events in observed receipts.
Observed native cgroup peak during this deployment was 639,102,976 bytes.
[Deployment receipt](deployment/runtime248.json),
[binary delta](approach1-binary-delta.json).

Fresh filesystem counts include untracked source files and use the same scope:
original Restart release sources32,303 physical lines /177 files /55 folders;
this branch33,022 lines /181 files /56 folders, no empty source folders. This
**+719 lines /4 files /1 folder** includes the earlier complete cache-first and
background-update implementation as well as approach1. An isolated pre-approach1
source snapshot was not captured, so this is not its individual line delta.
[Count receipt](approach1-source-count-final.json).

## Remaining verification

Core catalogue-sharing/reuse and independent cache startup have live evidence.
Complete visual correctness, a comparable latency improvement, live registry
growth/reordering/corrupt-catalogue recovery and paced-background rescue under
controlled loss are not proven by this run. The existing actual-traffic pacing
path was retained; this run did not force a new background-only definition and
measure that transfer's retransmissions. These cases must not be reported passed
on build success alone. The 100-client and mutation phases remain explicitly
suspended until the user re-enables them.
