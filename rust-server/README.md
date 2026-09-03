# Voxy Rust server

This crate is the native regional storage and QUIC backend. Saved Minecraft Anvil chunks remain
authoritative.

For each Anvil region and dimension, the backend maintains:

- a fixed source table containing Anvil save markers and semantic terrain fingerprints;
- one checksummed regional LOD file with a direct spatial section directory;
- compressed 32-cubed section payloads containing block, biome, and light cells exactly once.

Changed files are written, synced, validated by construction, and atomically renamed. Existing
request readers retain their already-open file generation. There are no append-only object packs,
global roots, reachability scans, format bridges, or stored normalized source blocks.

## Running

The server-controller JAR embeds the Linux x86-64 release executable and runs it with
`voxy-rust.toml`. The host must provide `libzstd.so.1`. The backend can also run directly:

```sh
cargo build --release --locked --bin voxy-rust-server
target/release/voxy-rust-server --config voxy-rust.toml
```

`--once` imports every currently saved region and exits without opening QUIC. The save root
discovers the Overworld, Nether, End, and namespaced dimension region directories automatically.
`poll_ms` controls saved-Anvil polling. `rayon_threads = 0` uses Rayon's default worker count.

The configuration shape is:

```toml
world = "/path/to/minecraft/world"
data = "/path/to/voxy-data"
dimension = "minecraft:overworld"
poll_ms = 2000
rayon_threads = 16

[quic]
listen = "0.0.0.0:25587"
advertise_host = ""
advertise_port = 0
```

## Network

Rust creates a persistent certificate below `data/quic` and emits one readiness record after the
UDP endpoint is live:

```text
VOXY_READY udp_port=<1..65535> alpn=voxy-region cert_sha256=<64 lowercase hex>
```

Each connection has one control stream plus persistent section lanes for coverage, current detail,
and prediction. Control records expose the current catalog and spatially paged region directory.
Section requests contain only a generation and exact section coordinate; replies are emitted in
bounded batches while regional files are read. Protocol bounds, finite lane queues, QUIC flow
control, CRC32C, BLAKE3 fingerprints, and pinned TLS protect the current data path.
