# Voxy correctness and performance audit

This report ranks possible runtime bugs and design problems that could cause high allocation rates, missing or unsent LODs, excessive memory or CPU use, unnecessary disk usage, hangs, or high time to full detail (TTFD).

`Confirmed` means the behavior follows directly from the current code. `Risk` means a reachable failure path exists but still needs a runtime reproduction.

## Ranked findings

| Rank | Severity | Finding | Main impact |
|---:|---|---|---|
| 1 | Critical | Minimum memory setting is internally infeasible | LOD selection can silently stop |
| 2 | Critical | Selection batch pool can retain about 444 MiB outside the budget | High heap use or OOM |
| 3 | Critical | Prediction history can retain hundreds of MiB or more outside the budget | High heap use or OOM |
| 4 | Critical | 32 activation jobs reserve up to 432 MiB for one worker | LOD starvation and high TTFD |
| 5 | Critical risk | Renderer hierarchy may not own directly selected descendants | LOD publication failure |
| 6 | High | Server rebuilds whole roots and neighbours repeatedly | CPU, disk I/O, and generation time |
| 7 | High | Full manifest snapshots can be rebuilt after individual objects | Extreme allocation rate |
| 8 | High | GPU selection reads back up to about 27 MiB per frame | CPU/GPU bandwidth and stalls |
| 9 | High | Network objects are copied repeatedly and decoded serially | Allocation rate and TTFD |
| 10 | High | Maximum render distance exceeds the metadata-root limit | Permanently missing LODs |
| 11 | High | Structural-node limit can prevent discovery from completing | Missing LODs; `ROOT_READY` never sent |
| 12 | High | Existing inner-node topology is not reconciled after changes | Holes, stale children, or leaks |
| 13 | High | Rust decompresses and verifies objects before retransmitting compressed bytes | Server CPU and multi-client throughput |
| 14 | High | Queued server requests cannot be canceled or reprioritized | High TTFD and wasted traffic |
| 15 | High | `--once` processes only one bounded refresh | Incomplete generated database |
| 16 | High | Generation is origin-first and publishes one group at a time | Distant players wait unnecessarily |
| 17 | Medium-high | Exact source microtiles duplicate renderable terrain storage | Larger server database |
| 18 | Medium-high | Client maintenance runs approximately every millisecond | Idle CPU and allocations |
| 19 | Medium | Compressed objects remain duplicated in memory after caching | Higher retained heap |
| 20 | Medium | Camera-domain requests have no timeout | Conservative mode can persist indefinitely |
| 21 | Medium | Root admission is frame-limited and unordered | Adds a TTFD floor |
| 22 | Medium | Client cache may use 4 GiB plus rewrite headroom | Large disk footprint |
| 23 | Medium | Shutdown waits have no timeout | Shutdown or reload deadlocks |
| 24 | Low-medium | Configuration loaded from JSON is mostly unvalidated | Crashes or pathological workloads |
| 25 | Low-medium | Several pressure paths repeatedly call `glFinish()` | Severe stalls when buffers fill |

## Detailed findings

### 1. The 256 MiB setting cannot realistically work — confirmed

Before storing useful terrain, the minimum setting reserves approximately:

- Control tables: **184 MiB**
- Root-pin tables: **15 MiB**
- Metadata-root accounting: **2.5 MiB**
- Persistent-cache accounting: **44.5 MiB**

That totals approximately **246 MiB**, before full selector buffers, decoded microtiles, geometry, meshing, or transfers.

The configuration permits 256 MiB, but the selector silently postpones work when it cannot reserve memory. This can look like LODs simply never appearing.

At the default 768 MiB setting, fixed worst-case accounting consumes approximately **365 MiB**, rising to roughly **445 MiB** when maximum selector buffers are installed.

Relevant code:

- `src/main/java/me/cortex/voxy/client/lod/ClientSession.java`
- `src/main/java/me/cortex/voxy/client/lod/ResidencyManager.java`
- `src/main/java/me/cortex/voxy/client/lod/ObjectCache.java`

### 2. Selection batches can retain roughly 444 MiB — confirmed

The primitive-array selection handoff avoids many temporary Java objects, but its arrays grow and never shrink.

At the maximum 262,144-node manifest, one batch can retain approximately:

- Output arrays: 90 MiB
- Input arrays: 16 MiB
- Candidate and planning arrays: 36 MiB
- Group and cost arrays: 6 MiB

That is approximately **148 MiB per batch**. The pool retains three batches, or approximately **444 MiB**.

This memory is not charged to `MemoryBudget`, so reported Voxy memory can be hundreds of MiB lower than actual Java heap use.

Relevant code: `src/main/java/me/cortex/voxy/client/core/rendering/selection/SelectionBatch.java`.

### 3. Prediction samples retain another unbudgeted pool — confirmed

Every 50 milliseconds, prediction feedback may retain a hash table containing three `long[]` arrays, one `int[]`, and one `byte[]`.

For 32,768 predicted rows:

- One sample is approximately **7.25 MiB**.
- A one-second horizon can create roughly 20 simultaneous samples: **about 145 MiB**.
- The bounded pool permits 64 retained samples: **about 464 MiB**.

At the maximum manifest size, one sample can approach 61 MiB. These arrays also never shrink and are outside `MemoryBudget`.

Relevant code: `src/main/java/me/cortex/voxy/client/core/rendering/selection/VirtualSurfaceSelector.java`.

### 4. Activation reserves much more work than it can process — confirmed

The client can stage 32 activations per pass, but owns only one meshing worker.

Each activation reserves:

- 5 MiB meshing scratch
- 8 MiB maximum geometry
- 0.5 MiB in-flight data

That is **13.5 MiB each**, or **432 MiB** for 32 queued activations. Most of those activations are only waiting for the single worker.

Objects are also pinned before staging. If staging fails, there is no immediate pin rollback. Pin reconciliation needs another temporary reservation and simply returns under pressure, potentially leaving objects pinned and making the pressure worse.

Relevant code:

- `src/main/java/me/cortex/voxy/client/lod/ClientSession.java`
- `src/main/java/me/cortex/voxy/client/lod/MicrotileActivationManager.java`

### 5. Directly selected descendants may lack renderer owners — strong risk

The manifest selector can identify deep descendants immediately, but the old renderer hierarchy still creates child owners progressively.

If geometry finishes before `NodeManager` owns that section, `stageGeometryResult()` returns `null`. `AsyncNodeManager` then treats it as a fatal error instead of waiting until the hierarchy owner exists.

Relevant code:

- `src/main/java/me/cortex/voxy/client/core/rendering/hierarchical/NodeManager.java`
- `src/main/java/me/cortex/voxy/client/core/rendering/hierarchical/AsyncNodeManager.java`

### 6. One server change causes broad regeneration — confirmed

For each root being rebuilt, Rust:

1. Loads exact source data for that root and its six neighbours.
2. Recreates all section content in those roots.
3. Checks or stores every generated microtile.
4. Loads old renderable sections.
5. Deep-clones the current root's section state.
6. Rebuilds manifests and the root directory.
7. Checkpoints and publishes a new root.

Publication batches are one region or group at a time. During an initial build, neighbouring roots are therefore processed repeatedly. This is likely one of the largest server CPU and generation-time problems.

Relevant code: `rust-server/src/surface/runtime.rs`.

### 7. Manifest rebuilding can recreate the entire Java object graph repeatedly

Every dirty publication creates new lists, maps, node records, child arrays, dependency arrays, and `BitSet`s. Validation also creates a new `HashSet` for every node.

The network path admits objects one at a time, and individual microtile installation marks the manifest dirty. Cold streaming can therefore rebuild a large immutable snapshot after individual objects arrive.

Relevant code:

- `src/main/java/me/cortex/voxy/client/lod/SelectionManifestBuilder.java`
- `src/main/java/me/cortex/voxy/client/lod/RootDemandPlan.java`
- `src/main/java/me/cortex/voxy/client/core/rendering/selection/SelectionManifest.java`

### 8. Selection readback can reach approximately 27 MiB per frame

The refined selector allocates and downloads output space for every manifest node instead of first reading the actual result count.

At 262,144 nodes:

- Refined readback: approximately **24 MiB**
- Conservative readback: approximately **3 MiB**

Both passes may run each frame. At 60 FPS, that is theoretically over **1.6 GiB/s** of GPU-to-CPU transfer before parsing and planning.

Relevant code: `src/main/java/me/cortex/voxy/client/core/rendering/selection/VirtualSurfaceSelector.java`.

### 9. Direct-mode receiving is serial and copy-heavy

For each network object, the compressed payload is approximately:

1. Allocated by the frame decoder.
2. Cloned into `EncodedObject`.
3. Cloned for decompression.
4. Cloned again when written to the disk cache.

The decompressed bytes are cloned into `CanonicalObject`, and some typed decoders request another clone.

The network-reading thread also waits for each state transition, then decodes and stores every object one at a time before reading more network frames. Slow disk caching or decoding therefore pauses the direct TCP receive path.

Relevant code:

- `src/main/java/me/cortex/voxy/client/lod/WireMessage.java`
- `src/main/java/me/cortex/voxy/client/lod/ObjectDecoder.java`
- `src/main/java/me/cortex/voxy/client/lod/ClientSession.java`

### 10. Maximum render distance exceeds the metadata-root limit — confirmed

At the maximum configured radius, an Overworld-like dimension can require approximately 26,546 LOD-4 roots. `MAX_METADATA_ROOTS` is only 8,192.

The renderer ignores `metadataRootEntered()` returning false. Some visible roots can therefore exist in `NodeManager` without entering manifest demand, so no terrain is requested for them.

Relevant code:

- `src/main/java/me/cortex/voxy/client/core/VoxyRenderSystem.java`
- `src/main/java/me/cortex/voxy/client/lod/ClientSession.java`

### 11. Structural-node capacity can prevent discovery from completing

The planner permits at most 262,144 structural nodes. If the active root window requires more, manifests remain deferred, discovery never becomes complete, and `ROOT_READY` cannot be sent.

Relevant code: `src/main/java/me/cortex/voxy/client/lod/RootDemandPlan.java`.

### 12. Existing inner-node topology is not reconciled after changes

Updating an existing node changes its child-existence mask but does not add or remove allocated children to match it. Inner nodes ignore later child requests, and removing children does not convert the node back into a leaf.

The source contains an unresolved TODO specifically for an inner node whose child mask becomes zero.

This can produce stale children, missing new children, hierarchy disagreement, leaked geometry, or visible holes.

Relevant code: `src/main/java/me/cortex/voxy/client/core/rendering/hierarchical/NodeManager.java`.

### 13. Rust decompresses stored objects before retransmitting them

For each requested object, Rust reads the compressed object, resolves and decodes its dictionary, decompresses it, verifies its canonical identity, then discards the canonical result and sends the original compressed bytes.

That work is repeated per client request even though the stored compressed object is already what the client needs.

Relevant code:

- `rust-server/src/surface/runtime.rs`
- `rust-server/src/surface/service.rs`

### 14. Server requests cannot be canceled or reprioritized

The server stores requested hashes in FIFO `VecDeque` jobs. Once queued, old predicted or off-screen terrain remains ahead of newly visible terrain.

Movement, rotation, zoom, or teleporting can therefore waste disk reads and network traffic while increasing TTFD for the new view.

Relevant code: `rust-server/src/surface/service.rs`.

### 15. One-shot generation can stop before the world is built

`--once` calls `refresh_all()` once. One refresh intentionally handles only a bounded publication batch and reports whether more work remains, but `refresh_all()` ignores that pending flag.

The process can print that the build is complete after generating only one small batch.

Relevant code:

- `rust-server/src/main.rs`
- `rust-server/src/surface/service.rs`

### 16. Generation is prioritized around world origin

Changed regions are sorted by distance from world coordinate `(0,0)`, not by connected-player demand. Publication also processes one region or group per root generation.

Players near a distant spawn or teleport destination can wait while unrelated origin-near terrain is processed first.

Relevant code: `rust-server/src/surface/runtime.rs`.

### 17. Exact source microtiles duplicate server terrain storage

Every nonempty 32-cubed section at every LOD stores 64 exact `SourceMicrotile` objects, including air tiles because their light contributes to parent generation.

The same section separately stores renderable exterior, interior, and complex microtiles. The encodings differ, but much of the underlying terrain is stored twice under different object identities.

Relevant code:

- `rust-server/src/surface/content.rs`
- `rust-server/src/surface/runtime.rs`

### 18. Client maintenance runs approximately every millisecond

The state writer sleeps for only one millisecond while idle. It repeatedly:

- Scans every object handle looking for installable microtiles.
- Scans active nodes and constructs retirement lists.
- Sorts retirement candidates.
- Rebuilds temporary pin sets.
- Attempts manifest publication and activation.

Most of this should be driven by dirty queues or events instead of polling nearly 1,000 times per second.

Relevant code: `src/main/java/me/cortex/voxy/client/lod/ClientSession.java`.

### 19. Resident objects retain compressed bytes after disk persistence

`ResidencyManager.ResidentObject` retains its `EncodedObject` after the same compressed payload has been written to the persistent cache. Dropping canonical bytes does not drop the encoded byte array.

Pinned content therefore retains both decoded cells and compressed data in heap, even when the compressed bytes can be recovered from disk.

Relevant code: `src/main/java/me/cortex/voxy/client/lod/ResidencyManager.java`.

### 20. Camera-domain queries have no timeout

Once `pendingCameraDomainSequence` is nonzero, no replacement request is sent until the response arrives or the connection closes.

If server processing stalls without immediately breaking TCP, the client can remain in conservative domain zero indefinitely. That can increase interior traffic and prevent stable selection authority.

Relevant code: `src/main/java/me/cortex/voxy/client/lod/ClientSession.java`.

### 21. Root admission is frame-limited and unordered

Only 40 horizontal columns are admitted per rendered frame, in hash-map iteration order rather than camera priority.

- Default radius: approximately 23 frames.
- Maximum radius: approximately 332 frames.

Low frame rate directly increases manifest-discovery time, while the most important roots are not guaranteed to be admitted first.

Relevant code: `src/main/java/me/cortex/voxy/client/core/rendering/RenderDistanceTracker.java`.

### 22. Client cache can consume considerably more than 4 GiB temporarily

The cache permits 4 GiB of compressed payload. Append-only records, tombstones, per-object headers, pack headroom, and atomic epoch rewriting can temporarily require both the old and replacement live sets.

The behavior is safe, but its disk footprint can be much larger than users expect from a nominal 4 GiB cache.

Relevant code:

- `src/main/java/me/cortex/voxy/client/lod/ObjectCache.java`
- `src/main/java/me/cortex/voxy/client/lod/PackedObjectStore.java`

### 23. Shutdown can wait forever

Session shutdown loops until worker executors terminate and joins the writer thread without a deadline. Interrupts are remembered but otherwise ignored.

The GPU mesher can also enqueue cleanup on the render thread and call `join()` without a timeout. If the render thread is already shutting down or no longer processing tasks, shutdown can deadlock permanently.

Relevant code:

- `src/main/java/me/cortex/voxy/client/lod/ClientSession.java`
- `src/main/java/me/cortex/voxy/client/core/rendering/building/GpuMicrotileMesher.java`

No definite Rust lock-order deadlock was identified. The clearest deadlock and hang risks are in Java shutdown and render-thread handoff.

### 24. Most JSON configuration values are not validated

Only the virtual-surface memory value is clamped when used. Directly edited JSON can provide negative, non-finite, or excessively large render distance, subdivision, fog, and related values.

Those values can create invalid array sizes, excessive loops, incorrect shader inputs, or crashes.

Relevant code: `src/main/java/me/cortex/voxy/client/config/VoxyConfig.java`.

### 25. Buffer pressure can trigger repeated full GPU stalls

When upload or download stream allocation fails, several paths repeatedly call `glFinish()`. `flushWaitClear()` also performs multiple consecutive global GPU waits.

This is primarily a pressure fallback, but the memory and readback issues above make that pressure more likely. Once triggered, it can cause severe frame stalls and further delay LOD activation.

Relevant code:

- `src/main/java/me/cortex/voxy/client/core/rendering/util/UploadStream.java`
- `src/main/java/me/cortex/voxy/client/core/rendering/util/DownloadStream.java`

## Recommended repair order

1. Fix memory accounting and remove unbudgeted selection and prediction retention.
2. Limit activation staging to actual worker concurrency and make pin rollback unconditional.
3. Fix renderer hierarchy ownership and child-mask transitions.
4. Replace full manifest rebuilding with incremental snapshots.
5. Remove broad server regeneration and per-request decompression.
6. Add priority and cancellation to server requests.
7. Address metadata/root limits and one-shot generation correctness.
8. Reduce source-object and cache/index disk amplification.

