# Voxy

Voxy streams and renders distant saved terrain for Minecraft 1.21.1 on NeoForge 21.1.229.
This fork ships two components that are deployed together:

- a Java client mod that performs projected-pixel-size LOD selection, regional caching, meshing,
  and rendering;
- a server JAR that advertises Voxy and supervises its bundled Linux Rust backend.

Install the client JAR only on clients and the server JAR on every server that advertises Voxy.
The client and server use one current regional protocol; there is no older-format reader or
negotiated compatibility path.

Please do not ask for support for this fork in Cortex's server. Use
<https://discord.gg/6rH7nzmfg8> instead.

## Build

Java 21, Cargo, a Rust toolchain, and the system Zstd development library are required.

```sh
./gradlew buildAll
```

The client JAR is written to `build/libs/`. The matching server-controller JAR is written to
`server/build/libs/` and embeds the release Linux x86-64 Rust executable.

## Server configuration

Place `voxy-rust.toml` in the Minecraft server working directory:

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

An empty advertised host reuses the authenticated Minecraft peer address. Port zero uses the UDP
port reported by Rust; set a public port only when NAT or a proxy remaps it. Minecraft TCP and Voxy
UDP may use the same number. The Minecraft connection authenticates the endpoint and pins Rust's
persistent certificate for the client session.

The controller starts Rust, forwards its output, restarts it after an unexpected exit, and removes
the advertised endpoint whenever that process is unavailable. See
[`rust-server/README.md`](rust-server/README.md) for standalone operation.

## Current data path

Minecraft Anvil files are the source of truth. Rust stores one compact source-fingerprint table and
one atomically replaceable LOD file per Anvil region. Each regional file has a direct spatial index
and one compressed 32-cubed block/biome/light payload per present LOD section. Empty sections have
index entries but no payload. There is no global content-object index, reachability graph, or
garbage collector.

The client requests regional indexes once, checks its bounded regional cache, then downloads
complete sections on persistent priority-separated QUIC lanes. Decode, model-aware meshing, upload,
and activation are bounded stages. An active coarse section stays visible until its finer children
are complete and have crossed the renderer's GPU fence.

The normal GPU hierarchy remains responsible for projected pixel-size selection, frustum culling,
and HZB occlusion, including shader pipelines. Camera feedback requests spatial child sections; it
cannot evict required coverage or create permanent content handles.

Voxy never generates Minecraft chunks. Minecraft or a pregenerator must create and save them.
