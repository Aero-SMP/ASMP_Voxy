# Voxy Rust server

This crate is the standalone server-side half of Voxy. It reads Minecraft 1.21.1 Anvil saves,
builds Voxy's 32x32x32 sections at LOD levels 0 through 4, stores them in a corruption-contained
regional log, and streams them to the Java client over protocol v6. A small Java bridge installed
on the Minecraft server advertises the selected transport after normal Minecraft login. In
Minecraft mode it also relays bytes to Rust; all generation, storage, and streaming logic remains
in this Rust process.

The normal server JAR contains the Linux x86-64 release executable and runs it as a supervised
child process using `voxy-rust.toml` from Minecraft's working directory. Java reads the same
file to advertise the configured transport. If Rust exits while Minecraft is running, it is
started again after one second; Minecraft shutdown terminates it cleanly. The commands below
remain available for standalone development or deployments that intentionally do not use the
server JAR.

The database is a rebuildable visual cache. Anvil remains authoritative. A damaged record,
checkpoint, registry, scan state, or complete missing shard causes a bounded invalidation and
regeneration; it does not require deleting the whole cache by hand.

## Build and run

The release binary links the host's `libzstd.so.1`; this deployment targets the configured
Linux x86-64 server and requires the system Zstd library at build and runtime.

```sh
cargo build --release --locked
target/release/voxy-rust-server --config voxy-rust.toml
target/release/voxy-rust-server --config voxy-rust.toml --once
```

See `examples/voxy-rust-direct.toml` and `examples/voxy-rust-minecraft.toml`. `--index-only` is
an alias for `--once`. `dimension` supplies the ID only when `world` points directly to one
dimension directory. When `world` is a normal save root, one daemon discovers and serves:

- `region` as `minecraft:overworld`
- `DIM-1/region` as `minecraft:the_nether`
- `DIM1/region` as `minecraft:the_end`
- `dimensions/<namespace>/<path>/region` as `<namespace>:<path>`

Dimension IDs use a sanitized prefix plus a stable 64-bit hash for storage paths, so different
IDs cannot alias the same database directory.

`transport = "direct"` opens `direct.listen`. The bridge advertises that port and
`direct.advertise_host` after the normal Minecraft login; an empty advertised host reuses the
Minecraft server hostname. Rust performs no additional authentication and the protocol is not
encrypted, so an independently written client can bypass Minecraft if it discovers the endpoint.
Use firewalling, a private network, VPN, or authenticated proxy when that is unacceptable.

`transport = "minecraft"` opens `minecraft.socket` as a mode-0600 Unix socket. Clients
automatically relay frames over their existing authenticated Minecraft connection. Rust remains
a separate child process and opens no public Voxy port.

In both modes, the client first sends a transport request containing protocol version 6 over the
authenticated Minecraft connection. The bridge responds with Minecraft mode or the direct host
and port. It never pushes an early advertisement. A missing, malformed, or incompatible response
leaves Voxy streaming disabled; the client performs no DNS lookup or port fallback.

`trust_client_opacity = true` enables first-writer-only block-opacity feedback from a Java
client. The registry is saved before any mapping or section can expose
its IDs. A real opacity value change increments a separate mip-semantics generation; level 0 is
kept and only stale parent LODs are regenerated. Normal mapping additions do not reindex
unrelated dimensions.

See `examples/voxy-rust.service` for a deployable standalone service. Create the service's world
and data directories first and give the configured service user write access to the data
directory; systemd's `ReadWritePaths` does not create it.

## Update and scaling model

The daemon polls region headers at `poll_ms` (default 2000 ms). Region modification markers,
per-chunk location/timestamp fields, and stable 64-bit content fingerprints detect same-sector,
same-second rewrites whenever the region file metadata marks that file for rechecking. Repeated
world edits coalesce naturally into the next completed Anvil save,
roughly matching the relaxed database-update frequency requested for DH without copying DH's
snapshot or event systems.

Changed chunks are grouped by 2x2 chunk columns. Each of the four chunks is decompressed and
parsed once, all vertical level-0 sections are built from that in-memory group, and source
fingerprints are rechecked before publication. Initial indexing uses bounded group/section
batches. Parent LODs are built in global deduplicated level passes, so each affected parent is
built once rather than once per descendant. The work is therefore proportional to changed input
plus unique affected output sections. On a completely empty database, the level-0 pass finishes
before those global parent passes; run `--once` before opening the service if immediate wide-area
coarse terrain is required. Missing data still regenerates safely in the background.

Before binding, startup performs only metadata recovery and tombstones dirty/unowned region
columns. The daemon then accepts clients immediately while the expensive initial Anvil scan runs
in the background; every section visible to a client uses a durable transaction. `--once` may use
deferred bounded writes because it has no listener, then syncs before advancing source state.
Parent generation and Anvil parsing use Rayon across the allocated CPU cores. Set
`rayon_threads` in `voxy-rust.toml` to cap that pool; zero uses Rayon's default.

The Java renderer is also the network demand planner. It subscribes to exact section keys as its
existing hierarchy needs them and unsubscribes when it releases them. This changes request work
from scanning overlapping fixed-radius squares to expected `O(requested keys)` hash lookups.
Coarse parents are requested first and remain available while visible children refine them;
zoom changes the renderer's screen-space choice and therefore requests finer sections naturally.
Each addition includes the durable revision in the client cache, and a matching server revision
returns no payload. Live changes are sent only for currently subscribed keys.

Lookup jobs consume the protocol's full 256-key batch. The client orders those keys by missing
coarse coverage, visible refinement, projected size, camera distance, and request age. Rust may
read a shard in file-offset order, then restores that priority before transmission.

Section traffic consumes a byte-credit window. The client returns those bytes only after its
main thread consumes the frame, propagating backpressure through either TCP or the Minecraft
relay. Frame writes and buffer flushes retain a 30-second deadline, so a non-reading client
cannot hold a connection forever.

## Storage and recovery

Each spatial shard is an append-only `.vxlog` with:

- a catalog ID and random physical log incarnation in the file header;
- CRC32C on every record header and payload;
- durable `INVALIDATE` records before live replacement publication;
- `PUT` records followed by a transaction `COMMIT`;
- monotonic time-based logical revisions;
- an atomic `.vxidx` checkpoint bound to the exact log incarnation;
- a durable repair marker for any destructively recovered shard;
- a per-dimension expected-shard manifest and store epoch.

Canonical section bytes are compared exactly before publication, compressed once with Zstd
level 1, and stored in that form. Network section frames reuse the identical compressed bytes;
the server does not decompress and recompress data per client.

Indexes are disposable. A missing or stale checkpoint is rebuilt by scanning the log. A torn tail
is truncated and marks only that shard for repair. Non-tail corruption quarantines and replaces
only its shard, writes newer tombstones for every known key, and schedules source regeneration.
Deleting or losing a manifested shard changes the durable store identity, so clients discard stale
cached sections at the next HELLO. A runtime destructive recovery also advances that identity;
existing connections close within one second or at the next stream batch and reconnect. The
source fingerprint manifest remains trusted across a store-epoch change, so only missing spatial
groups regenerate rather than the complete dimension. Registry snapshots are checksummed and
committed to both peers before new IDs become publishable; either valid peer repairs a damaged or
missing copy without changing the catalog or reassigning IDs. Source scan state is
split into independently length-delimited, CRC32C-protected Anvil-region snapshots, each capped at
1024 chunks and atomically replaced. Only changed region snapshots are written, so one damaged or
missing state file invalidates and regenerates one spatial column instead of the whole dimension.
A durable dirty sentinel is written per affected region before store mutation and removed only
after that region's source state is durable, closing the crash window without a global journal.

Compaction copies sorted live records one payload at a time, bounds memory independently of shard
size, changes the physical incarnation, then atomically replaces the log and checkpoint. Reads
validate both record CRC and the Voxy section encoding before streaming.

## Why Tokio instead of io_uring

AeroNet's io_uring use does not automatically apply to this separate process. This daemon uses
Tokio's mature TCP reactor (epoll on Linux) and ordinary page-cache file I/O because it keeps the
dependency surface and recovery code smaller. Disk scans and section reads run outside Tokio's
async workers, while transport backpressure is handled by awaited writes. The workload is dominated by
Anvil decompression, mip generation, large sequential shard access, and network bandwidth; adding
an io_uring runtime would not make those operations free. A Linux-only io_uring fast path can be
profiled later without changing the on-disk or wire formats.

## Protocol v4 (frozen layout)

All integers are little-endian. Every frame begins with this 16-byte header:

| Offset | Type | Value |
|---:|---|---|
| 0 | `u32` | magic `0x32595856` (bytes `VXY2`) |
| 4 | `u16` | version `4` |
| 6 | `u16` | message type |
| 8 | `u32` | payload byte length |
| 12 | `u32` | CRC32C of payload |

Outbound payloads are capped at 16 MiB. The server rejects impossible client sizes from the
header before allocating: HELLO exactly 4 bytes, SUBSCRIBE <=8198, PING and CREDIT exactly 8,
and block properties <=16388.

Strings are `u16 byte_length` followed by UTF-8, at most 4096 bytes.

### Client to server

- `0x0001 C_HELLO`: `capabilities u32`. Capability bit 0 means block-property feedback support.
  This is the client's first frame on both transports.
- `0x0002 C_SUBSCRIBE`: `dimension string`, `addition_count u16`, `removal_count u16`, then
  additions (`key u64, known_revision u64`) and removals (`key u64`). A batch contains at most
  256 total changes. `known_revision = u64::MAX` means the client has no usable cached copy.
- `0x0003 C_PING`: `nonce u64`.
- `0x0004 C_BLOCK_PROPERTIES`: `count u32` (<=2048), then `id u32, opacity u8,
  reserved[3]=0` per entry.
- `0x0005 C_CREDIT`: `bytes u64`. Section and invalidation frames consume credit including their
  16-byte frame header; mappings, HELLO, PONG, and ERROR do not.

### Server to client

- `0x8001 S_HELLO` (24 bytes): `server_instance u64`, `flags u32`, `max_lod u8=4`,
  `reserved[3]=0`, `block_epoch u32`, `biome_epoch u32`.
- `0x8002 S_MAPPING_DELTA`: `block_count u32`; each block is `id u32, opacity u8,
  reserved u8, canonical string`; then `biome_count u32`; each biome is `id u32, name string`.
  A frame has at most 256 combined entries and is sent before any section that references it.
- `0x8003 S_SECTION`: `key u64`, `revision u64`, `canonical_length u32`, `codec u8=1`,
  `reserved[3]=0`, followed by one Zstd frame. The decompressed canonical data contains
  `schema u8=1`, `non_empty_children u8`, `bits_per_index u8`, `reserved u8=0`,
  `palette_len u16`, `reserved u16=0`, `word_count u32`, then palette entries and packed words.
  A palette entry is `block_id u32, biome_id u32, light u8, reserved[3]=0`. Words are `u64`; 32,768 palette
  indexes form one continuous least-significant-bit-first bitstream and may straddle words.
- `0x8004 S_INVALIDATE` (24 bytes): `key u64`, `revision u64`,
  `reason u8`, `reserved[7]=0`.
- `0x8005 S_PONG`: `nonce u64`.
- `0x80ff S_ERROR`: `code u16`, `message string`.

`SectionKey` is the Java Voxy key exactly: LOD in bits 60..63, signed Y in 52..59, signed Z in
28..51, signed X in 4..27, and zero low bits. It does not encode dimension; C_SUBSCRIBE selects
the dimension and changing it atomically clears that connection's old subscriptions.

Revisions order both replacement and invalidation. For equal revision, stream order wins:
`INVALIDATE N` then `SECTION N` publishes the section; the reverse removes it. The client drops
queued replies for keys it has since unsubscribed. If the bounded live-update queue lags, the
server closes the connection after an error; reconnect resubscribes with cached revisions and
transfers only keys that changed.

`server_instance` combines the durable registry catalog with every dimension's store epoch. It
stays stable across normal restarts and changes when destructive recovery may have lost an unknown
historical key, causing the Java client to clear only its Voxy section cache.

## Verification

```sh
cargo fmt --check
cargo test --all-targets --locked --offline
cargo clippy --all-targets --locked --offline -- -D warnings
cargo build --release --locked --offline
```

The test suite covers key packing, palette packing, Java wire sizes, CRC implementations, mip
selection, torn writes, corrupt-payload quarantine, stale-worker rejection, stale checkpoint
incarnation, runtime/restart identity changes, missing-shard identity changes, manifest-write
recovery, region-local dirty/state corruption, redundant-registry corruption, and source-state
checksums. It also exercises blocked-client write deadlines.
