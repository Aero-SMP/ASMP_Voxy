# Cache loading algorithm improvements: implementation and live evidence

All six approved changes and ordered top-root admission are implemented on
`feature/cache-first-background-updates` in debug client 259. Compilation succeeded;
client publication and the single real-PC verification window are pending.
Original repositories and Main remain unchanged. Client 259 is intended to run
against Testing's existing Java/native 257 deployment.

## Algorithms and ownership

- Regional metadata selection uses intrusive FIFO queues in the existing 32 pixel
  buckets, split by coverage priority. Bucket histograms replace repeated member
  maximum scans. Admission, removal, priority updates and selection are O(1) with
  respect to regional/member count; draining R regions is Theta(R), not Theta(R^2).
  Reclassification may alter equal-priority order, as approved. There is no new
  quota or exact-distance scheduler.
- The top coalescing mailbox preserves the upstream planner's nearest-first
  insertion order. Other GPU/detail scheduling remains unchanged. This preserves
  startup/new-entry ordering, not perfect circular activation under asynchronous
  work or continual sorting after movement.
- BLAKE3 keeps worker/operation-owned compression arrays and reusable tree slots.
  The codec owns read hash state across read/decode/integrity checking; append
  retains independent state across suspended steps; compaction owns its own
  reusable state. Hashing remains Theta(bytes), with O(log bytes) tree scratch.
  CRC, content hashes, byte order, deferred final chunk, flags, padding, reset
  and finalized-state checks remain intact.
- Codec first-use ordering uses the existing scalar ordering invariant; redundant
  Boolean arrays are removed. Used-model IDs are built from distinct canonical
  block-state names, excluding air and deduplicating aliases. This operation
  changes from Theta(palette entries) to Theta(block states); the palette and voxel
  decode still require their original entries and linear work.
- Mesher preparation also computes the same nonair packed bounds. Reused Y/Z/X
  depth masks restrict waterlogged overlays to eligible planes; all base passes
  and face/custom-model/light/tint/fluid rules remain unchanged. Bounds fusion
  removes one cell traversal. Worst-case work remains Theta(cells + output quads).
- READY inventory answers definitive absence using existing accounting and writer
  ownership. Unknown/mutating regions use normal metadata inspection. Completed
  background bindings patch the active local directory rather than rereading it.
  Pending initial snapshots merge same-key overlays, guarded by region identity,
  view/revision and file incarnation. Direct/child-completeness bits update only
  the fixed ancestor path, avoiding recursive incomplete-cut scans. A real cut
  still takes Theta(emitted dependencies) to enumerate.

The eviction review required small additional plumbing in the existing disk/cache
owners and `WorldCacheDownloads`. Retained Region objects carry unique internal
incarnation stamps; snapshots, save completions and predecessor fallbacks capture
one while pinned. Eviction, RESET and payload quarantine advance it; compaction
preserves it. Before using affected local/downloader summaries, stale snapshots,
coverage and pending overlays are rejected selectively. Delayed writes cannot
reinsert bindings from an evicted file. Valid predecessor fallback survives
invalidation as a local-only overlay, without claiming a new disk commit or
upgrading downloader full-quality coverage. Installed geometry and worker leases
remain owned. No permanent world map, format change or new retention reason was
introduced. Checks of retained regions use expected O(1) lookup without filesystem
access/path allocation; rare unretained checks fall back to the budget's map.

Initial active-directory bookkeeping is Theta(N), ordinary K patches Theta(K)
with fixed hierarchy depth, excluding real invalidation, payload work and necessary
cut output. Existing downloader global physical-eviction invalidation is retained;
no claim is made that every prior algorithm in Voxy is now O(1).

## Build, size and preparation

Final build: `./gradlew jar debugJar --console=plain`, successful in five seconds.
No test task ran. Compiler failures during implementation were corrected and their
logs retained, including missing throws declarations, base/subclass demand typing
and one missing local stamp declaration. Build success is compilation evidence,
not runtime integrity or meshing proof. Gradle assembled dependent server jars;
those are not deployed and no server/native restart is part of this change.

See `deployment/build259-final-success.log`, `deployment/staged259.json`,
`cache_algorithm_source_count_259.json` and `cache_algorithm_size_ledger_259.json`.

| Same scope | 258 | 259 | Delta |
| --- | ---: | ---: | ---: |
| Release source physical lines | 37,842 | 38,144 | +302 |
| Release source files / directories | 183 / 56 | 183 / 56 | 0 / 0 |
| All source including shared physical lines | 50,827 | 51,133 | +306 |
| All source including shared files / directories | 249 / 122 | 249 / 122 | 0 / 0 |
| Normal client bytes | 3,909,781 | 3,915,855 | +6,074 |
| Debug client bytes | 4,065,180 | 4,071,384 | +6,204 |

Physical source counts include comments/blanks and tracked/untracked maintained
source in the same scope as 258; generated/build/audit files are excluded. Shared
source is unchanged and counted separately in the previous ledger. No empty
selected directory remains. Audit helper files are outside that source scope.

Debug SHA-256: `140f41e1f6ca61edaa6943d16d623a20c67abe1918a886b15e091a38e716c354`.
Normal SHA-256: `91a34cf3ca681c7564ef5bbea11aa8361f733d53f56baad2d523048f269fd6cd`.

Real-PC checks will share one non-resettable monotonic clock, target five minutes,
absolute ten minutes including updater waits, SSH checks, screenshots and
restoration. No integration/unit suite, laptop, synthetic or 100-client testing.
Cache/settings/world/identity preservation and both backup connections are
required; the hold has an automatic five-minute expiry. Publication checks any
pending cache-reset/profile updater requests against preserved PC receipts.
