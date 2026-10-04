# Detached native saved-source lighting candidate

This candidate is source-only. It has compiled successfully but has not been deployed or measured live. It changes only `server/src/main/java/com/aerosmp/voxy/server/VoxyServer.java`; no world loading, generation, source writes, restarts, pressure operations, client controls, or tests were performed.

## Trigger and resulting source behavior

The matched dark-plane record contained interior grass beside air with all relevant light samples zero. Its preserved source chunk `(55,-1)` was at `minecraft:initialize_light`, had no `isLightOn` tag, and contained a present zero-valued skylight array at the sampled positions. The publisher previously used those unfinished values directly. The preserved bytes and receipt are in `live_client/visibility-candidate-dark-plane-records/source-chunk-55--1`.

The publisher now decodes a saved snapshot into detached native `ProtoChunk`/`LevelChunkSection` objects and uses synchronous `LevelLightEngine` before the existing terrain reduction. It captures real registered dimension types at server startup. A saved-only, unregistered dimension must provide `dimension-type.json` beside its `region` directory, decoded with native `DimensionType.DIRECT_CODEC`; there is no guessed dimension type or implicit fixture registration.

Native lighting is initialized in the native order:

1. Retain and queue saved block/sky `DataLayer` arrays; validate their native shape.
2. Initialize native sky-source columns and register nonempty sections.
3. Run native updates to install queued arrays and create implicit layers.
4. Set each column's light-enabled state using `Status >= LIGHT && isLightOn`, then release retained data.
5. Propagate sources only for genuinely unfinished saved chunks and run updates to completion.
6. Resolve native visible layers once per vertical column for constant-time reduction lookups. Ignore queued layers that are not active in native storage.

An update runs at least once even if `hasLightWork()` is false. Native sky enabling can fill default layers without adding propagation work; that update publishes the resulting visible map. Present arrays, omitted homogeneous-zero layers, missing in-memory layers, skylight above the stored data, and dimensions without skylight therefore follow native storage behavior rather than a blanket brightness fallback.

The source rectangle includes `LightEngine.MAX_LEVEL` of physical light reach around the represented cells, and each included column retains its complete vertical source. Missing chunks remain absent: the native engine treats unknown block context as bedrock. Existing reduction and atomic record publication remain in place.

## Ownership, invalidation and complexity

One record build owns the compressed snapshot, decoded paletted chunks, native light engine and derived layer references. They are released after that build; no new persistent in-memory residency map or arbitrary resource cap was added. There is currently no reuse across different record/LOD builds, so overlapping records can repeat decoding and native lighting. That cost must be measured before claiming a throughput improvement.

Time and memory are O(source volume plus native propagation work). Native propagation has fixed light-level and face-neighbor bounds. Palette decoding, sky-source discovery, layer resolution and terrain reduction are linear passes; missing-layer lookup is not repeated for every voxel. Spatial chunk lookup uses a primitive long hash map. The existing eight-sample reducer has constant-size comparisons, independent of view/world size.

Metadata and content fingerprints include the producer class bytes, native dimension-type representation, and all sampled neighboring source chunks. The producer identity covers the outer mod, backend, snapshot, saved-chunk and section-light classes. This forces unchanged saved inputs to rebuild after a producer correction without format-version branches. Record payload hashes, source-change checks and atomic publication remain unchanged.

`VOXY_PUBLISH` now reports `light_unfinished`, `light_missing_columns`, `light_be_context`, `light_dynamic_states` and `light_snapshot_ms`. The last metric includes snapshot decoding and native lighting; it is not exclusively propagation time.

## Explicit limitations

- Missing neighboring saved columns are unavailable context, so derived lighting near them remains provisional.
- Detached snapshots do not instantiate or tick block entities on the publisher thread. Lighting dependent on block-entity data, auxiliary light managers, attachments or a live Level remains provisional. The context counters expose relevant saved entities and dynamic-emission palette states; they do not prove full modded-light fidelity.
- This does not finish world generation or change the saved chunk's lighting-correct flag. It derives light for terrain records using the currently saved block snapshot.
- There is no payload/protocol change carrying lighting quality to the client in this candidate. Existing geometry remains usable, and later source changes still refresh in the background.
- Full-column native lighting may add significant build time and heap use. Source-only compilation does not establish live performance, memory safety under the required pressure workload, or visual correctness.

## Primary source and compilation evidence

Primary sources were read from `/home/aerosmp/.gradle/caches/neoformruntime/intermediate_results/sourcesWithNeoForge_aec458fcfaed1a9a135a2b9714fd8c4cb49f768b_output.zip`:

- `net/minecraft/world/level/chunk/storage/ChunkSerializer.java:93,137–150,216,324–354`
- `net/minecraft/server/level/ThreadedLevelLightEngine.java:151–182`
- `net/minecraft/world/level/chunk/status/ChunkStatusTasks.java:25–26,159–173`
- `net/minecraft/world/level/lighting/SkyLightSectionStorage.java:26–54,92–125`
- `net/minecraft/world/level/lighting/SkyLightEngine.java:275–345`
- `net/minecraft/world/level/lighting/LayerLightSectionStorage.java:132–170,212–251`

Coordinated command: `./gradlew --offline compileJava`. Terminal result: exit 0, `BUILD SUCCESSFUL in 4s`. All current Java source compiled together. One existing client `ThreadDeath` deprecation warning was emitted. No jars or tests were built/run by this task.

Source SHA-256: `ddcce106541849a3cdb8074a6165cd1d383721bb1415fcfc0e141b327b0aed42` (972 readable lines).

Compiled producer class SHA-256 values:

| Class | SHA-256 |
| --- | --- |
| `VoxyServer` | `ccd00db1f9f3e86e9d9e7f7b0648c98cdc094fd2de5b61eb4b7ba1e51a672c5c` |
| `VoxyServer$Backend` | `1feb5d83be14e8e05fcefa5595b83d0bbf429b26f6afdaa8f3f7e7deae9620ee` |
| `VoxyServer$Backend$SavedLighting` | `4c507463755975a6ab2c0e86a9f69e622c4a188791c4451b2d79b9435dca73bd` |
| `VoxyServer$Backend$SavedChunk` | `3f5316b8a8f5cdc7bb91a6a3daf2904b3d6a3aa635bee73c148a1dce195acec8` |
| `VoxyServer$Backend$SectionLight` | `4720b36c5f904e08e350b0597f6369cc17ea29edf865edb009678d772f2652d2` |
