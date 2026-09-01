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

The normal and debug server/controller JARs both contain the Linux x86-64 release executable. The
controller extracts it, starts it with `voxy-rust.toml`, forwards its log output, restarts it after
an unexpected exit, and stops it with Minecraft. The host must provide `libzstd.so.1`.

The backend can also be run directly:

```sh
cargo build --release --locked --bin voxy-rust-server
target/release/voxy-rust-server --config voxy-rust.toml
```

`--once` refreshes the configured worlds and exits without opening a listener. Configuration is
file-only; there are no environment-variable overrides. See
`examples/voxy-rust-direct.toml` and `examples/voxy-rust-minecraft.toml`.

The save root automatically discovers:

- `region` as `minecraft:overworld`
- `DIM-1/region` as `minecraft:the_nether`
- `DIM1/region` as `minecraft:the_end`
- `dimensions/<namespace>/<path>/region` as `<namespace>:<path>`

`poll_ms` controls how often Anvil changes are coalesced into a new transactional root.
`rayon_threads = 0` uses Rayon's default worker count.
`memory.managed_mib` is the single process-wide managed allocation budget (2 GiB by default),
and `memory.max_connections` bounds live sessions. Reaching the budget delays lower-priority
publication or maintenance without deleting the current root; an impossible request fails with a
typed pressure error rather than deadlocking.

Visibility defaults to sky-exterior inference only for `minecraft:overworld`, portal connectivity
for `minecraft:the_nether`, and conservative visibility for the End and unknown dimensions. A
safe per-dimension override can select `portal_only` or `conservative`:

```toml
[visibility]
"minecraft:the_end" = "portal_only"
```

`sky_exterior` is rejected for every dimension except `minecraft:overworld`.

## Transports

`transport = "direct"` opens `direct.listen`. The authenticated Minecraft controller advertises
`direct.advertise_host` and `direct.advertise_port`; an empty host reuses the Minecraft server
hostname, while port zero reuses the listener port. Terrain traffic then travels directly between
the Java client and Rust.

Direct Voxy traffic has no second account challenge or encryption. Firewall, proxy, or private
network controls are required when the endpoint must not be publicly usable.

`transport = "minecraft"` opens the mode-0600 Unix socket configured by `minecraft.socket`. The
controller relays opaque terrain frames over the existing Minecraft connection, so Rust opens
no additional public port.

Both transports carry the same bounded frames: root announcements, subtree requests,
manifest data, exact object requests, object bundles, root-ready acknowledgements, camera-domain
updates, byte credit, ping, and errors.
