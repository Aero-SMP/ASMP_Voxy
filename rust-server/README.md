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

The server-controller JAR embeds the Linux x86-64 release executable and runs it in the
server directory with `voxy-rust.toml`. The host must provide `libzstd.so.1`.
The backend can also run directly from that directory:

```sh
cargo build --release --locked --bin voxy-rust-server
/path/to/voxy-rust-server --config voxy-rust.toml --minecraft-port 25565
```

`--once` imports every currently saved region and exits without opening QUIC. The fixed
`./world` root discovers the Overworld, Nether, End, and namespaced dimension region
directories automatically. Data is stored in `./voxy-data`. Idle saved-Anvil polling is fixed
at two seconds. `rayon_threads = 0` uses Rayon's default worker count.

The configuration shape is:

```toml
rayon_threads = 0

[quic]
listen = ""
advertise = ""
```

## Network

Empty `listen` binds all IPv4 interfaces at `--minecraft-port + 200`; an explicit socket
address overrides it. The Java controller supplies Minecraft's current port automatically.
`advertise` only tells clients the reachable address through the controller; it does not bind
a socket or configure NAT/firewall rules. Empty inherits the Minecraft peer and bound port;
`host`, `:port`, `host:port`, `[IPv6]` and `[IPv6]:port` override the corresponding parts.
Java validates this controller-only setting; standalone Rust does not use it.
Replace the old `advertise_host`/`advertise_port` keys when upgrading. See the root README.

Rust creates a persistent certificate below `voxy-data/quic` and emits one readiness record after the
UDP endpoint is live:

```text
VOXY_READY udp_port=<1..65535> alpn=voxy-region cert_sha256=<64 lowercase hex>
```

Each connection has one control stream plus persistent section lanes for coverage, current detail,
and prediction. Control records expose the current catalog and spatially paged region directory.
Section requests contain only a generation and exact section coordinate; replies are emitted in
bounded batches while regional files are read. Protocol bounds, finite lane queues, QUIC flow
control, CRC32C, BLAKE3 fingerprints, and pinned TLS protect the current data path.
