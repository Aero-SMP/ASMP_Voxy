# Voxy

Voxy renders distant terrain for Minecraft 1.21.1 on NeoForge 21.1.229.
This fork has two primary programs and one small server integration:

- a Java/NeoForge **client mod** that renders and locally caches LOD sections; and
- a standalone **Rust LOD service** that reads saved Anvil chunks, builds LODs, stores them in
  self-healing spatial logs, and streams them directly to clients.
- a minimal **server JAR** containing the Rust executable. It keeps Rust running, tells
  authenticated clients which transport the server selected, and in Minecraft mode relays
  opaque protocol bytes through a Unix socket.

Do not install the client JAR on a dedicated server. Install the bridge JAR on every server that
advertises Voxy, including direct mode.

Please do not ask for support for this fork in Cortex's server. Use
<https://discord.gg/6rH7nzmfg8> instead.

## Building

Requirements are Java 21, a Rust toolchain, and Cargo.

```shell
./gradlew buildAll
```

This produces:

- normal and `-debug` client JARs in `build/libs/`;
- normal and `-debug` server bridge JARs in `bridge/build/libs/`; and
- the server executable at `rust-server/target/release/voxy-rust-server`.

Normal JARs contain only no-op diagnostic facades. Debug counters, payloads, logging, and their
strings are compiled from separate debug source sets and exist only in the `-debug` JARs.

Build either half independently with `./gradlew build` or
`cargo build --release --locked --manifest-path rust-server/Cargo.toml`.

## Running the Rust service

Create `voxy-rust.toml`. Direct mode opens a dedicated listener:

```toml
world = "/path/to/minecraft/world"
data = "/path/to/voxy-data"
transport = "direct"
rayon_threads = 16

[direct]
listen = "0.0.0.0:25587"
advertise_host = ""
```

An empty `advertise_host` tells clients to reuse the Minecraft server hostname. Set it only when
the public Rust endpoint uses a different hostname. The advertised port comes from `listen`.

Minecraft mode opens only a local Unix socket. Install the bridge JAR on that Minecraft server:

```toml
world = "/path/to/minecraft/world"
data = "/path/to/voxy-data"
transport = "minecraft"
rayon_threads = 4

[minecraft]
socket = "/run/voxy/bridge.sock"
```

Place `voxy-rust.toml` in the Minecraft server's working directory. The server JAR extracts and
starts its bundled Linux x86-64 Rust executable automatically, restarts it after an unexpected
exit, and stops it with Minecraft. Both Java and Rust read this same file; no Voxy environment
variables are used. `poll_ms = 2000` controls how frequently saved Anvil region headers are
checked. See
[`rust-server/README.md`](rust-server/README.md) for recovery behavior and standalone operation.

The Rust process observes chunks after Minecraft saves them. It does not receive every live
block event and cannot generate chunks or invoke modded world generation. Use Minecraft,
Chunky, or another pregenerator to create terrain; Voxy will discover its saved region data.

## Connecting a client

Install the JAR from `build/libs/` only on the client. There are no transport, host, port, or
token settings. After the normal authenticated Minecraft login, the client requests transport
information from the bridge. A Minecraft response keeps Voxy on that connection; a direct
response supplies the Rust host and port. Without a valid response, Voxy streaming remains
unavailable. There is no DNS lookup or fixed-port fallback.

Voxy performs no second Mojang request. In Minecraft mode, the authenticated connection carries
all Voxy traffic. In direct mode, it carries only transport discovery and bulk LOD data then uses
the advertised TCP endpoint. That direct endpoint deliberately has no separate authentication;
protect it with firewalling, a private network, or a proxy if anonymous access is unacceptable.
Both modes carry the identical Voxy protocol, and leaving Minecraft closes the normal client's
Voxy connection.

There is no separate server-side view-radius setting. The renderer subscribes only to the
hierarchy sections it currently needs. Coarse parents arrive first and remain visible while
their visible children are requested; a smaller field of view, including zoom, naturally asks
for finer children. Moving away unsubscribes sections that are no longer useful.

Each request includes the server revision already present in the local cache. Rust sends
nothing when that copy is current, so reconnecting does not replay the complete view. A byte
credit returned only after the client consumes a section bounds in-flight data and applies
backpressure through both direct TCP and the Minecraft bridge.

## Storage and recovery

Both halves use append-only, spatially sharded storage with checksummed records and replaceable
indexes. A damaged record or shard is isolated instead of invalidating the complete cache.
Missing or damaged server sections are rebuilt from saved Anvil chunks; clients can always
re-download missing cache entries. While the Rust service is authoritative, Java's local chunk
ingestion is disabled so an older client-side job cannot overwrite a newer server revision.
Old record versions are removed by regional compaction.

There is intentionally no RocksDB, database importer, Java server fallback, or separate debug
mod. This is a new cache format, so compatibility with older Voxy or Distant Horizons databases
is neither required nor attempted.
