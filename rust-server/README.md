# Voxy rewrite backend

This is an independent saved-terrain backend. It reads world files and writes
only its own `--data` directory. No Minecraft chunk generation occurs here.

```sh
cargo build --release --offline
target/release/voxy-rewrite-server --world ../world --data ../voxy-rewrite-data --listen 0.0.0.0:25787
```

`--once` imports all currently saved regions and exits. `--refresh-ms 1000` is the
default source reconciliation cadence; larger values intentionally accept older
terrain. Missing initial regions are admitted immediately instead of waiting for
the refresh timer. Existing generation files stay readable during refresh.

One terrain worker streams four source chunks at a time into 32³ level-zero
sections, then reduces completed children into levels 1–4. Background
reconciliation scans previously selected derived regions instead of the whole
source world. It reuses immutable
generations across all connections. Its pending state is one coordinate entry
per affected region, rather than a queue of block edits. Slow receivers await
QUIC writes and cannot cause this worker to manufacture extra response payloads.
There are no memory reservations, byte budgets, or per-frame quotas.

Each `.vxr` spatial file contains a source-chunk fingerprint table, section
frames, and a sorted 25-byte
spatial index. A lookup opens the file and binary-searches its index. Building
streams payloads directly into an uncommitted generation file; catalog references
and the index are completed, synced, and atomically renamed over the previous
file. Existing open file descriptors continue reading the previous generation.
An unreadable or concurrently changing source leaves the last generation intact.
Saved deletions produce explicit air sections, never disappearance due to timeout.
Unchanged four-chunk groups copy their already compressed frames into the next
generation without NBT decoding or recompression. Only parents whose children
actually changed are reduced again; unchanged parent bytes and catalog references
remain stable. Fingerprints live in spatial files, never a resident world map.
`VXRREG02` adds fingerprints; old `VXRREG01` files remain readable while upgrading
in the background.

Catalog IDs are append-only and persisted before publication. Each generation
references a SHA-256-addressed catalog snapshot, retained so old client sections
remain independently decodable. `world.id` contains a stable random 16-byte
identity for this derived dataset. Distinct authoritative worlds must use
distinct derived directories; deleting or replacing a world requires a fresh
dataset identity. Standard overworld/nether/end and saved custom dimensions are
discovered without copying terrain between dimensions.

Level zero preserves block states, biomes and packed lighting exactly. Reduction
selects the most frequent non-air block among each 2³ group (ties follow sample
order), independently selects the most frequent biome, and retains the maximum
of each light channel. Unknown saved coverage is not invented outside populated
groups. Client-request coverage must be measured against actual saved terrain.

## Protocol version 1

QUIC ALPN is `voxy-rewrite-1`. The self-signed `voxy.local` certificate and private
key persist under `quic/server-cert.der` and `quic/server-key.der`. Readiness is:

```text
VOXY_READY udp_port=25787 alpn=voxy-rewrite-1 cert_sha256=<hex>
```

Each bidirectional stream begins with a little-endian u16 UTF-8 dimension length,
then its bytes. The server returns its 16-byte world identity. A second stream
may carry catalog requests independently, using the same dimension. GET records may be
pipelined; replies retain request order on each stream. This avoids one round
trip per section and lets lazy catalog reads proceed while terrain writes wait
for their consumer. The connection idle timeout is 300 seconds to accommodate
the lossy-link acceptance workload. Integer fields below are little-endian:

- `0 GET`: u8 level, i32 x/y/z, and 32-byte known whole-frame SHA-256. Reply is
  u8 status: 0 unavailable, 1 unchanged, or 2 followed by u32 frame length and
  the frame bytes. Levels 0–4 cover `32 << level` blocks per axis. Coordinates
  use floor division, including negative positions.
- `1 CATALOG`: 32-byte catalog SHA-256. Reply is u32 length and canonical bytes;
  zero length means unavailable.

The same section frame bytes appear in storage, responses and client cache:
`VXRSEC01` (8), catalog SHA-256 (32), compressed-body SHA-256 (32), children mask
(u8), canonical body length (u32), compressed length (u32), zstd body. The body is
u16 palette count, entries `{u32 block, u32 biome, u8 light}`, then 32³ compact
palette indexes ordered `x | z << 5 | y << 10`. Each index uses
`max(1, ceil(log2(palette_count)))` bits in little-endian u64 words with
`floor(64 / bits)` entries per word. Partial trailing words are zero-padded.
Block light is the low nibble, sky light
the high nibble. Child-mask bit is `x | z << 1 | y << 2`; level-zero masks are 0.

Catalog bytes are `VXRCAT01`, u32 block count, u16-length UTF-8 canonical block
states, u32 biome count, then u16-length UTF-8 biome names. Properties are sorted
and use `minecraft:block[property=value,...]`. Clients persist a section's
catalog alongside it and do not need a live connection to decode cached data.

Validation uses the live real client and the 100-client QUIC load driver only;
there is no offline integration-test suite in this repository.

The protocol retains structural length/palette validation needed for correctness;
these checks do not govern the server working set. The external testing kill
guard remains an operational launcher, outside this implementation.
