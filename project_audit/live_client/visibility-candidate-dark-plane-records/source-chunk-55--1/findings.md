# Saved lighting at the dark-plane ray

The first occupied ray cell is interior grass in displayed section `(1,13,1,-1)`, local cell `(30,22,26)`. Its south face borders air. This cell is not a forced section boundary. The displayed record's digest matched the inspection's geometry hash when preserved.

One read-only snapshot of saved chunk `(55,-1)` in `Voxy_Testing/world/region/r.1.-1.mca` found `Status=minecraft:initialize_light` and no `isLightOn` tag. Section Y6 contains an actual 2,048-byte `SkyLight` array; `BlockLight` is absent. All eight unit samples reduced into the grass cell at world base `(892,108,-12)`, and all eight samples reduced into its adjacent south air cell at `(892,108,-10)`, contain stored skylight zero. Consequently, the present array's zero values explain the zero light already observed in the displayed payload. Missing-array fallback is not the direct cause of these samples.

The region size, modification time and chunk location remained unchanged during the snapshot. The compressed chunk SHA-256 is `2520bebbb62e22ca489d7f7a32209ae972a4f5f49a837bf129c5a9e92c7574ff`; the decompressed NBT SHA-256 is `db9f32e6e468035df910bcc44ca501e9850be7624826f7899d388f182b05ea56`. Raw bytes and the complete non-entity receipt are alongside this note. No source-world or cache files were modified.

## Publisher and native semantics

- `server/src/main/java/com/aerosmp/voxy/server/VoxyServer.java:336` validates saved coordinates, then consumes sections without checking `Status` or `isLightOn`. Lines 374–405 consume the stored light arrays. Lines 661–662 take the maximum of the eight reduced samples, so all-zero inputs remain zero.
- The cached Minecraft 1.21.1 primary source, `ChunkSerializer.java:93,216`, reads `isLightOn` and passes that value to `setLightCorrect`. `ChunkStatus.java:28–31` places `INITIALIZE_LIGHT` before `LIGHT` and `FULL`. `ThreadedLevelLightEngine.java:171–182` propagates sources during the light step and marks the result correct only after updates. This saved chunk therefore has unfinished lighting, rather than verified final dark terrain.
- A separate publisher defect exists at `VoxyServer.java:626–635`: absent skylight arrays always become zero. Native `SkyLightSectionStorage.java:26–54` instead searches upward for a missing layer, using the found layer's bottom row, or returns sky 15 above the data. Native semantics also distinguish enabled/updating storage. A blanket missing-array value is insufficient.

The Minecraft sources above are from the locally cached official/NeoForge source archive `sourcesWithNeoForge_aec458fcfaed1a9a135a2b9714fd8c4cb49f768b_output.zip`.

## Consequences and limits

Keep usable geometry available immediately, but treat unfinished saved lighting as provisional. Correct recovery must obtain native-equivalent lighting from the saved blocks and neighboring context, or use a later light-correct saved revision. Replacing zero with a global brightness floor would conceal the data-quality issue and alter legitimate underground darkness.

The preserved ray identifies voxel and AABB intersections, not the final GPU triangle or alpha-tested fragment. Its interior grass-to-air face and complete zero-light stencil provide a concrete upstream explanation for darkness at this ray. They do not establish that every photographed dark edge has the same cause; forced caps and mixed-LOD seams require their own evidence.
