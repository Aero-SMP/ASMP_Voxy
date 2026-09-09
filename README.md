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

The controller creates `voxy-rust.toml` in the Minecraft server working directory if absent:

```toml
rayon_threads = 0

[quic]
listen = ""
advertise = ""
```

The source is always `./world`, with dimensions in their standard directories. Generated LOD
data, catalogs and certificate identity live in `./voxy-data`. Saved terrain polling is fixed
at two seconds once background work is caught up; pending work continues immediately.
`world`, `data`, `dimension` and `poll_ms` are no longer TOML settings. When upgrading, remove
those keys and move the old data directory to `voxy-data` while the server is stopped, preserving
its contents and certificate. Do not merge it with an existing unrelated data directory.

`listen` controls the **local UDP socket**. Empty means all IPv4 interfaces on Minecraft's
current `server-port + 200`, resolved at every backend launch without rewriting the config.
An explicit address such as `0.0.0.0:25786` overrides that automatic choice. `127.0.0.1` binds
only loopback; `0.0.0.0` is a bind address, not an address to give remote players.

`advertise` controls the **address sent to clients**, not the listening socket:

| Value | Client destination |
| --- | --- |
| `""` | Minecraft connection's peer address and the actual bound UDP port |
| `"lod.example.com"` | Override host only; keep the bound UDP port |
| `":30000"` | Override port only; keep the Minecraft peer address |
| `"lod.example.com:30000"` | Override both |
| `"[2001:db8::1]:30000"` | IPv6 and explicit port; `[2001:db8::1]` keeps the bound port |

Explicit ports must be 1–65535; omit the port to inherit it. IPv6 must be bracketed.
For example, local `0.0.0.0:25786` may be reachable as `lod.example.com:30000`; advertise
that address while forwarding UDP30000 to UDP25786. Advertisement does not create forwarding,
open a firewall or move a socket. Restart Minecraft after changing the configuration.
When upgrading, replace `advertise_host` and `advertise_port` with `advertise` using the
forms above (old port zero means omit the port). The old keys are no longer accepted.
The Minecraft connection authenticates the endpoint and pins Rust's persistent certificate.

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
