# Voxy

Voxy streams and renders distant terrain for Minecraft 1.21.1 on NeoForge 21.1.229.
This fork ships two installable components:

- a Java client mod that selects, caches, meshes, and renders terrain objects;
- a server JAR that advertises Voxy and supervises its bundled Rust backend.

Do not install the client JAR on a dedicated server. Install the Voxy server JAR on every server
that advertises Voxy.

Please do not ask for support for this fork in Cortex's server. Use
<https://discord.gg/6rH7nzmfg8> instead.

## Build

Java 21, Cargo, a Rust toolchain, and the system Zstd development library are required. The
client JAR embeds the Netty QUIC provider and its supported desktop native libraries; the server
JAR embeds one QUIC-enabled Linux x86-64 Rust executable.

```sh
./gradlew buildAll
```

The aggregate task creates two distributable JARs:

- the client JAR in `build/libs/`;
- the server-controller JAR in `server/build/libs/`.

The server JAR embeds the Linux x86-64 Rust executable. No sources JAR is produced.

## Server configuration

Place `voxy-rust.toml` in the Minecraft server working directory. Configuration is file-only;
there are no Voxy environment-variable overrides.

Voxy opens a QUIC terrain endpoint over UDP:

```toml
world = "/path/to/minecraft/world"
data = "/path/to/voxy-data"
poll_ms = 2000
rayon_threads = 16

[visibility]
"minecraft:the_end" = "conservative"

[quic]
listen = "0.0.0.0:25587"
advertise_host = ""
advertise_port = 0
```

An empty `advertise_host` reuses the authenticated Minecraft peer address. An `advertise_port` of
zero uses the UDP port Rust actually bound; set it to the public UDP port when NAT or a proxy
remaps the listener. Minecraft and Voxy may use the same number because Minecraft listens on TCP
and Voxy listens on UDP. Terrain then travels over encrypted QUIC without crossing Minecraft's
packet stream. The authenticated Minecraft response pins the exact persistent Rust certificate for
the session. Rust does not perform a second player login, so restrict the UDP endpoint when
arbitrary protocol clients must not reach it.

The server JAR extracts and starts Rust, forwards its logs, restarts it after an unexpected exit,
and stops it with Minecraft. It advertises only after the current child reports its actual bound
UDP port, ALPN, and certificate fingerprint, and invalidates that identity whenever the child
exits. Stream memory is bounded structurally through finite request concurrency, fixed protocol
limits, ownership-scoped buffers, and QUIC stream and connection flow control. There is no
aggregate memory governor or pressure-spin path.

Only the Overworld may use `sky_exterior`. The Nether defaults to `portal_only`; the End and
unknown dimensions default to `conservative`. Operators may explicitly choose `portal_only` or
`conservative` per dimension under `[visibility]`.

See [`rust-server/README.md`](rust-server/README.md) and the example configuration files under
`rust-server/examples/` for standalone operation.

## Client behavior

Install one client JAR from `build/libs/`. Clients have no Voxy hostname, port, certificate, or
token settings. After the authenticated Minecraft login, the client requests the current QUIC
endpoint and pins its advertised certificate fingerprint. Without a valid response, Voxy
streaming remains unavailable; there is no DNS lookup, fixed-port assumption, TCP path, or relay
fallback.

The renderer traverses bounded Morton manifests and requests exact missing content hashes. Its GPU
selection accounts for screen-space error, visibility domains, two-pass HZB results, cache
residency, and movement prediction. Coverage objects remain active until a complete replacement
microtile set is resident, compiled, and atomically visible.

## Data model

Rust derives independently addressed 8³ exterior, interior, and complex microtiles from saved
Anvil terrain. Canonical BLAKE3 identities are independent of compression, append-only packfile
placement, and network bundling. Dictionary-compressed objects, bounded manifests, monotonic root
generations, transactional publication, and grace-period garbage collection form the only storage
authority.

The client caches immutable objects by content hash and sends exact object requests against the
announced root on bounded, priority-separated QUIC streams. QUIC flow control and finite stream
ownership bound in-flight data. Voxy observes terrain after Minecraft saves it; Minecraft or a
pregenerator must create the chunks.
