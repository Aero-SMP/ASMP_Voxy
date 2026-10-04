# Running 7457 native / 867 Java cached-source contract

Read-only source review, 2026-10-04. No live source, server, client, fixture, records, cache, feed or network setting was changed.

The live native SHA-256 is `7457e3e48fbc4360e4f2f3337851ac247199c5ee94552aae51d3dcd75dc571d7`, with frozen source `.verification/frontier-candidate-ready/source/rust-server/src/main.rs`, SHA-256 `c49d31035d73d61e9de365050977d2c0cffa6919dd6c607067420f5f8a628497`. The live server jar is `8678010a862c2754c918602cae2f79887e746337576c25a8986bcf51214efa0a`, with frozen server source `.verification/frontier-source-freshness-candidate/VoxyServer.java`, SHA-256 `dbf246faf831e628e02a8bc7b90332ea05f0042692deb55eb8f7a06cb01657a8`. Runtime identity and heap/guard receipts are in `project_audit/frontier_server_running.json` and this run's `phase-source-edit-live-preservation.json`.

## GET and pending refresh

1. Native reads a complete 46-byte GET and opens its immutable `.vxs` record (`main.rs:45`).
2. It always calls `request()` (`main.rs:46`). An authoritative per-ticket jobs map emits VOXY_NEED only when no request for that ticket is already pending (`main.rs:12–17`). Other clients subscribe to the same completion signal.
3. Only a missing record enters `job.wait_for()` (`main.rs:47–50`). An existing record does not await Java's metadata read, decode or publication. It reads the already-open file's SHA and returns status 1 if the supplied known SHA matches, or status 2 plus its compressed payload otherwise (`main.rs:56–65`).
4. An atomic replacement after the file was opened does not change that Linux file descriptor's inode. A GET can therefore validly finish with the previous record, including an unchanged response, while the replacement has already become visible to the next open. Refresh publication does not push a new payload into an already-completed response.

This proves absence of a **rebuild-completion dependency** for an existing record. It is not an unconditional latency guarantee: native file I/O and the shared jobs mutex still run, and request() performs a synchronous stdout write while owning that mutex. Earlier worker/pipe observations did not establish pipe-write starvation, but that possible blocking point remains distinct from freshness waiting.

## Java metadata and content checks

Supervisor VOXY_NEED handling enqueues work without building it (`VoxyServer.java:93–95`). The single publisher owns builds. Missing records have priority over existing records; within that category coarser levels precede finer levels, then arrival order (`57–58`). An existing-record refresh can be delayed behind cold misses.

For the exact requested section and its halo, the publisher derives the overlapping chunk and region ranges (`132–135`). Its 64-byte stamp contains:

- First 32 bytes: SHA-256 of the region directory's nanosecond mtime and each overlapping MCA's regular-file presence, size and nanosecond mtime (`221–234`).
- Last 32 bytes: SHA-256 of the copied source chunks' coordinates, compression IDs and compressed bytes (`140–145`). External `.mcc` bytes are included when the corresponding MCA entry points to external data (`248`).

If a record exists and its metadata half matches, the build returns without reading source chunk bodies (`138`). Otherwise it copies the relevant chunk bodies, then repeats the metadata fingerprint. A change during that snapshot raises an error and preserves the existing publication (`146`). If metadata changed but copied content did not, only the stamp is updated; NBT decode and terrain reduction are skipped (`147`). Different content is decoded and published.

The native Minecraft 1.21.1 RegionFile source corroborates ordinary detection: a saved chunk writes the MCA body/header and timestamp (`RegionFile.java:288–314`), while an external chunk is committed by moving a temporary file into the region directory (`328–336`). This changes MCA metadata and/or directory mtime. The saved-source reader does not lock Minecraft's writer; its before/after fingerprints are detection, not a transactional snapshot of the world.

The metadata shortcut assumes source changes update filesystem metadata. An external editor that modifies an existing `.mcc` in place while preserving the MCA and directory metadata can evade it. Arbitrary edits preserving size/mtime are not detected. No assertion is made that such edits occurred.

## Publication, retries and staleness

Changed payload bytes are hashed. If the payload hash differs, Common.atomic writes a same-directory temporary record and atomically replaces the path (`VoxyServer.java:215–218`, frozen `Common.java:80–85`). The stamp is replaced separately. Every publisher attempt sends completion in finally, including an unchanged build, absent source or exception (`118–128`). That removes native job ownership and lets a later GET enqueue another attempt.

An absent source directory, no available source cells, decode error, metadata race or I/O error does not delete an existing valid record. The old terrain remains available. In particular, deletion of all source chunks does not produce an immediate empty-world replacement: build() returns on no available source. This favors continuity but does not guarantee eventual removal of every obsolete cached surface.

There is no chunk-save notification, file watcher, server push or periodic autonomous rebuild in this deployed source. A client GET schedules freshness; a later GET obtains an already-published replacement. A newly changed block in Minecraft memory is outside this saved-file source until Minecraft saves it. A source save can also occur after the before/after copy check, leaving a valid older snapshot published until another request notices the new metadata.

## Falling-water implication

Level 0 does not reduce or approximate block states: the server reads saved NBT palettes into native BlockStates, uses the zero-level 16³ voxel data, and serializes those states back into the named payload (`VoxyServer.java:159–200,209–214`). Valid saved water properties therefore survive the L0 path. This is a source contract, not proof that a particular visible water curtain matches current saved or in-memory terrain.

To establish that causal claim, compare the inspected selected L0 owner/hash and its exact local water state/halo with the current published record and the source chunk's saved NBT at the same world coordinate. Also distinguish the client's valid older cached payload from the server's current publication. The presently inspected first L0 falling-water state alone cannot identify which of those generations it represents or prove a meshing defect.
