# Visibility and rendering candidate

Candidate client SHA-256:
`9390ecd059bd8bad6be1c3e7a05cb226c21e518dbf107274f8fba2b6a83bbf70`.
Source archive, compilation, inventory and publication receipts are retained in
`visibility_candidate_freeze.json`, `visibility_candidate_build.log` and
`visibility_candidate_publication.json`. The actual loaded server remains
`8678010a862c2754c918602cae2f79887e746337576c25a8986bcf51214efa0a`;
all twelve shared class files are byte-identical to the previous client 795.

## Measured problem

The separate full64-depth stock JFR capture of actual client 795 attributes
104.588264 MB/s to Voxy. Its five-frame export misses callers and must not be
used to claim the allocation target passed. Transparent default shader setup
allocates about 19.67 MB/s in sampler names/arrays; scanning every selected
geometry's layer map allocates about 15.84 MB/s in iterators. Native block-entity
renderers account for 43.38 MB/s, plus 6.36 MB/s caused by Voxy's redundant global
Iris buffer flush and about 5.15 MB/s in outer poses and list iterators.

The stationary view also accumulated more than 105 million depth queries with
zero accepted occlusion by 07:33. The previous world-clock invalidation discarded
asynchronous results and repeatedly scanned demand. Draw telemetry excluded that
query cost. These observations belong to 795; they are not results for 939.

## Implementation

- Immutable transparent layer arrays and incrementally maintained owner maps
  remove the per-section map iterator from each frame. The pass still visits
  visible transparent owners in the same far-to-near order and preserves layer
  encounter order. Only consecutive identical render types share shader setup;
  each draw still uploads its own transform, source offset and LOD scale.
- Prepared block entities retain their renderer for the model generation. Only
  selected entity owners are visited, and exact native renderer bounds are
  checked before pushing a pose. Cached lighting and actual world coordinates
  remain intact; the native 64-block renderer distance cap is not introduced.
- Entity submission moves before native opaque-sheet flushing. Minecraft/Iris
  owns the shared buffer flush; Voxy no longer flushes unrelated queued segments.
  Installed Iris's per-type flush is a no-op, so it is not used as a replacement.
- Work-only occlusion preserves the cached draw frontier. Hidden branches pause
  new local/mesh/network work through at most five owner checks; they do not
  remove the geometry which established their occluder.
- Depth results retain actual camera, opaque-scene and bounds tickets. A single
  batch fence replaces per-query availability polling. Stable completed queries
  do constant-time dirty/fence checks; real input changes still scan demand.
- Actual emitted model/fluid bounds are retained and unioned through resident
  branches. Unknown or incomplete entity/model bounds remain conservative.
  Accepted native opaque publication/removal, membership changes and actual
  animated cutout alpha changes invalidate the scene. World ticks, unrelated
  list identities and inactive uploads do not.
- The installed public Iris API is sampled once per opaque frame through one
  bound method handle. Active or unknown shader pipelines cannot reuse hidden
  results to pause work. Status reports known/in-use/error independently.
- Incoming child metadata is unioned with the resident geometry's coverage
  until replacement publication. This repairs a lifecycle case where a source
  update could hide a child before ready replacement geometry existed.
- Block/fluid sprite animations are marked active for the selected geometry,
  and intentional obsolete preparation is counted separately from local faults.
- The explicit rendered-frame pixel inspection records selected keys, hashes,
  source/resident child masks and parent ownership for visual diagnosis.

No integration tests, resource quotas, new memory governors, section-rate limits,
server restarts or Main mutations accompany this candidate.

## Remaining acceptance and limitations

Compilation and source review passed; genuine update and live findings are
recorded separately. The source freeze has 8286 maintained lines, 39 files and 54
folders. The frozen original rewrite remains unchanged and the superseded 30%
size limits are not claimed passed.

The known water curtains, dark terrain planes and source-data/halo correctness
still need diagnosis from selected live geometry and cache records. Detached
modded block entities can query unavailable live neighbors. Initial frustum
admission still uses a key cube and can miss an overhanging custom model from
an entirely offscreen section. Dynamic shader packs conservatively keep new
work eligible; persistent depth rejection is not claimed for them.

The new fully impaired 100-client run remains independent. The 300 saved changes/s
clock starts only at the unchanged 100-simultaneous-client gate. Partial startup,
an elapsed observation period, or a new build is not a pressure pass. Current
profiling under that concurrent startup must be labeled accordingly and cannot
be substituted for a quiet matched-scene baseline comparison.
