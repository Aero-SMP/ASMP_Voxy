# Voxy Rust server

This crate is Voxy's native backend. Minecraft Anvil saves remain authoritative.
The server converts changed terrain into independently addressed 8³ exterior, interior, and
complex microtiles, publishes bounded Morton manifest subtrees, and serves exact immutable object
requests from the Java client.

Canonical objects use BLAKE3-256 identities. Dictionary-compressed Zstd objects are stored in
indexed append-only packfiles, so physical packing does not change object identity. A dimension's
monotonic root generation and Merkle root define freshness. Publication writes objects and
manifests before atomically replacing the advertised root; garbage collection retains current,
incoming, and grace-period roots while compacting reachable objects.

## Running

The server-controller JAR contains the Linux x86-64 release executable. The controller extracts
it, starts it with `voxy-rust.toml`, forwards its log output, restarts it after an unexpected exit,
and stops it with Minecraft. The host must provide `libzstd.so.1`.

The backend can also be run directly:

```sh
cargo build --release --locked --bin voxy-rust-server
target/release/voxy-rust-server --config voxy-rust.toml
```

`--once` refreshes the configured worlds and exits without opening a QUIC endpoint. Configuration
is file-only; there are no environment-variable overrides. See
`examples/voxy-rust-quic.toml`.

The save root automatically discovers:

- `region` as `minecraft:overworld`
- `DIM-1/region` as `minecraft:the_nether`
- `DIM1/region` as `minecraft:the_end`
- `dimensions/<namespace>/<path>/region` as `<namespace>:<path>`

`poll_ms` controls how often Anvil changes are coalesced into a new transactional root.
`rayon_threads = 0` uses Rayon's default worker count.
Streaming uses finite active request streams, bounded response records, positional packfile reads,
and QUIC stream and connection flow control. Publication normalizes one changed 2-by-2 chunk group
at a time, compression writes one object at a time, and large indexes remain file-backed. There is
no aggregate memory governor.

Visibility defaults to sky-exterior inference only for `minecraft:overworld`, portal connectivity
for `minecraft:the_nether`, and conservative visibility for the End and unknown dimensions. A
safe per-dimension override can select `portal_only` or `conservative`:

```toml
[visibility]
"minecraft:the_end" = "portal_only"
```

`sky_exterior` is rejected for every dimension except `minecraft:overworld`.

## Network

`quic.listen` binds the UDP terrain endpoint. The authenticated Minecraft server mod advertises
`quic.advertise_host` and `quic.advertise_port`; an empty host reuses the authenticated Minecraft
peer address, while port zero uses the port Rust actually bound. A nonzero advertised port supports
NAT or proxy remapping. Minecraft TCP and Voxy UDP may use the same numeric port.

Rust creates or reloads a persistent QUIC certificate and private key below the configured
`data/quic` directory. QUIC traffic is encrypted, and the controller authenticates the exact
certificate to the client through Minecraft by forwarding Rust's ALPN and SHA-256 fingerprint.
Rust has no second account challenge, so firewall or private-network controls remain appropriate
when arbitrary protocol clients must not reach the endpoint.

After binding and loading that identity, Rust emits exactly one controller readiness record:

```text
VOXY_READY udp_port=<1..65535> alpn=<ASCII token> cert_sha256=<64 lowercase hex>
```

Each connection owns one long-lived bidirectional control stream for hello, root announcements,
leases, activation, camera-domain messages, and bounded errors. Terrain, manifests, catalogs, and
dictionaries use short-lived client-initiated bidirectional request streams. Each request names
one root token, one priority lane, and a bounded same-lane list of canonical object hashes. QUIC
provides liveness and flow control; there is no application ping, byte-credit frame, TCP listener,
or Minecraft terrain relay.
