# Voxy terrain service

Java reads saved Minecraft Anvil chunks without opening a writable world file.
One publisher builds only requested spatial nodes, missing coverage before
background refresh and coarse coverage before detail. It uses Minecraft NBT,
block-state and packed-storage APIs. Unknown source remains unavailable; a
failed refresh preserves the last published terrain.

Rust serves immutable records over QUIC. A warm GET pins the published file,
answers immediately and independently requests a background source check.
Only a missing record waits for publication. Shared pending jobs contain no
connection vectors, and canceled readers release their own waiters.

```sh
cargo build --release --offline
target/release/voxy-server --world ../world --data ../voxy-data --listen 0.0.0.0:25787
```

`world.id` is the stable 16-byte world identity. The `voxy.local` certificate and
key remain at `quic/server-cert.der` and `quic/server-key.der`. ALPN is `voxy`.
A connection opens one bidirectional stream, sends a u16 little-endian UTF-8
dimension length and its bytes, and receives the world identity.

GET is byte 0, byte LOD, three i32 little-endian coordinates and known SHA-256.
Reply is byte 0 unavailable, byte 1 unchanged, or byte 2 followed by u32
little-endian payload length and payload. LOD 0–4 covers `32 << level` blocks.
Native requests publication using `VOXY_NEED dimension level x y z` on stdout;
Java acknowledges `<dimension SHA256>_<level>_<x>_<y>_<z>.vxs` on stdin.

Records live at `records/<dimension SHA256>/<level>/<x>>4>_<z>>4>/<key>.vxs`.
Each is 32-byte payload SHA-256 followed by the unchanged Deflater-level-1 NBT
payload. NBT contains `children`, `palette` entries `{state, biome, light}`,
Minecraft `SimpleBitStorage` `data`, and exact saved block-entity `entities`.
Cells include the complete one-cell halo: 34³ ordered x + 34*z + 34²*y.
Entity `voxy_state` contains its actual saved state, independent of coarse votes.
There is no catalog dependency, format version, compatibility decoder or region
publication barrier.

A 64-byte `.stamp` stores source-file metadata SHA-256 and this node's compressed
chunk-content SHA-256, committed after its payload. Unchanged metadata takes at
most nine region-file checks. Changed metadata reads only intersecting source
chunks; unchanged content skips NBT decoding and publication. Uniform sections
avoid voxel reduction. The publisher reuses its scratch arrays and Rust streams
files directly; resource accounting remains with the external testing guard.

Compilation and live real/fake clients provide verification. No integration
or automated test suite is included.
