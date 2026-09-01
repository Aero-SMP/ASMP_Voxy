# Voxy terrain surface

Voxy has one client/server architecture. Both ends are built and deployed together and use one
fixed wire and storage format.

## Representation

- Terrain is split into independently addressable 8³ microtiles.
- Exterior, interior, and complex content are stored separately.
- Each LOD-4 root has one bounded Morton-ordered manifest containing all five structural levels.
- Structural metadata is separate from descriptor payloads.
- Objects are identified by `BLAKE3-256(object kind + canonical bytes)`.
- Compression and physical pack placement do not affect identity.
- Independent Zstd frames live in append-only indexed packfiles.

Only the Overworld may infer exterior reachability from open sky. The Nether uses portal
connectivity. The End and unknown dimensions remain conservatively visible unless explicitly
configured otherwise.

## Client behavior

The client traverses manifests using frustum, bounds, screen-space error, visibility domains,
two-pass hierarchical-Z occlusion, cache residency, and bounded movement prediction. It asks
for exact missing objects and gives current visible coverage priority over refinement and
prediction.

Residency is bounded. A refinement reserves space for compressed input, decoded content,
temporary meshing data, compiled geometry, and the active fallback. Existing coverage remains
active until all content, dependencies, boundaries, and compiled geometry for its replacement
are ready.

Supported opaque and template microtiles use GPU meshing; complex or unknown content uses the
CPU path. Model classification is conservative and compiled geometry remains an in-memory
cache.

## Publication and storage

The server publishes in this order:

```text
write objects
-> fsync pack and index
-> write manifests
-> fsync manifests
-> atomically publish generation and root hash
-> notify clients
```

Garbage collection traces current, retained, building, and safety-period roots plus their
dictionaries and dependencies. Unreachable objects enter a grace generation before verified
pack compaction and atomic index replacement.

## Transport

The wire contract has root announcements, manifest and object requests, object bundles,
root-ready acknowledgements, camera-domain updates, credit, ping/pong, and bounded errors. It
is a fixed implementation detail, not a negotiated architecture or public version.

Direct mode uses a dedicated TCP connection. Minecraft mode relays identical bytes through the
Minecraft connection and a local Unix socket. The bridge may supervise Rust but owns no terrain,
storage, selection, or rendering behavior.
