# Voxy

Voxy streams and renders distant terrain for Minecraft 1.21.1 on NeoForge 21.1.229.
This fork ships two installable components:

- a Java client mod that selects, caches, meshes, and renders terrain objects;
- a server/controller JAR that advertises Voxy, supervises its bundled Rust backend, and relays
  opaque bytes only when Minecraft transport is selected.

Do not install the client JAR on a dedicated server. Install the server/controller JAR on every
server that advertises Voxy, including direct-mode servers.

Please do not ask for support for this fork in Cortex's server. Use
<https://discord.gg/6rH7nzmfg8> instead.

## Build

Java 21, Cargo, a Rust toolchain, and the system Zstd development library are required.

```sh
./gradlew buildAll
```

The aggregate task creates four distributable JARs:

- normal and `-debug` client JARs in `build/libs/`;
- normal and `-debug` server/controller JARs in `bridge/build/libs/`.

Both server JARs embed the Linux x86-64 Rust executable. Debug implementations and the bounded
client-to-server telemetry payload exist only in debug JARs; normal JARs contain no-op diagnostic
facades. No sources JAR is produced.

## Server configuration

Place `voxy-rust.toml` in the Minecraft server working directory. Configuration is file-only;
there are no Voxy environment-variable overrides.

Direct mode opens a dedicated terrain listener:

```toml
world = "/path/to/minecraft/world"
data = "/path/to/voxy-data"
transport = "direct"
poll_ms = 2000
rayon_threads = 16

[memory]
managed_mib = 2048
max_connections = 256

[visibility]
"minecraft:the_end" = "conservative"

[direct]
listen = "0.0.0.0:25587"
advertise_host = ""
advertise_port = 0
```

An empty `advertise_host` reuses the Minecraft server hostname. An `advertise_port` of zero uses
the listener port; set it to the public port when NAT or a proxy remaps the listener. Direct terrain
traffic bypasses Minecraft networking after transport discovery. The endpoint has no second
account challenge or encryption, so protect it with a firewall, private network, VPN, or
authenticated proxy when anonymous access is unacceptable.

Minecraft mode exposes only the Minecraft server's public port:

```toml
world = "/path/to/minecraft/world"
data = "/path/to/voxy-data"
transport = "minecraft"
poll_ms = 2000
rayon_threads = 4

[memory]
managed_mib = 2048
max_connections = 256

[visibility]
"minecraft:the_end" = "conservative"

[minecraft]
socket = "/run/voxy/bridge.sock"
```

The controller extracts and starts Rust, forwards its logs, restarts it after an unexpected exit,
and stops it with Minecraft. Minecraft mode relays the same terrain byte stream through the
configured mode-0600 Unix socket and opens no additional public port.

`memory.managed_mib` is one aggregate Rust allocation budget (2 GiB by default), not a separate allowance for each
subsystem or connection. When ordinary capacity is exhausted, background publication and GC wait,
interactive work is admitted ahead of maintenance, and existing roots remain serviceable. A
reserved control slice remains available for bounded wire errors and clean shutdown. Requests
that can never fit are rejected instead of waiting forever.

Only the Overworld may use `sky_exterior`. The Nether defaults to `portal_only`; the End and
unknown dimensions default to `conservative`. Operators may explicitly choose `portal_only` or
`conservative` per dimension under `[visibility]`.

See [`rust-server/README.md`](rust-server/README.md) and the example configuration files under
`rust-server/examples/` for standalone operation.

## Client behavior

Install one client JAR from `build/libs/`. Clients have no transport, hostname, port, or token
settings. After the authenticated Minecraft login, the client requests the server-selected
transport. Without a valid response, Voxy streaming remains unavailable; there is no DNS lookup
or fixed-port fallback.

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
announced root. Credit-based flow control bounds in-flight data in both transports. Voxy observes
terrain after Minecraft saves it; Minecraft or a pregenerator must create the chunks.
