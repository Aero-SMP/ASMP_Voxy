# Cache inventory, mesh bounds and Anvil simplification results

2026-09-06 UTC. Baseline `7cdd1791` (production code as reviewed at `f321e54c`).
Implementation `9fa528aa`; identical-code client republish `3224f886`.

## Decision: retain two changes, exclude the measured mesher regression

- **Inventory implemented:** both consistency passes remain. Each pass classifies a managed
  path using one attribute read, then reuses those attributes. The first intermediate list and
  `inventoryFile()` are removed. The second pass validates before publishing accounting or
  cleaning anything. Filename eligibility, link following, cancellation, locks, reference
  protection, encounter-order ties and eviction policy remain unchanged.
- **Anvil implemented:** packed blocks, packed biomes, block light and skylight borrow slices
  from the decoded section instead of copying four vectors. Ownership, validation/mutation
  order, absent-skylight behavior, output cells and all formats remain unchanged.
- **Mesher evaluated and excluded under the plan's explicit exception:** fused preparation/
  extrema passed exact-output tests, but was not a reliable performance win and repeatedly
  regressed zero-output models. The production mesher is byte-for-byte unchanged from baseline.
  Keep the new output/bounds/reuse tests. The evaluated patch is preserved in the ignored
  evidence directory as `rejected_mesher.diff`; no alternate mesher path was added.

No source worlds, catalogs, identities, configuration or player caches were reset or deleted.

## Correctness and build gates

All final required entry points passed:

```text
cargo test --manifest-path rust-server/Cargo.toml
./gradlew schedulerBehaviorTest
./gradlew debugHarnessJavaTest
./gradlew debugHarnessPythonTest
./gradlew buildAll verifyDebugHarnessArtifacts
git diff --check
```

The Java/build gates were combined in one invocation with
`-I /tmp/voxy-renderer-admission-build.gradle --console=plain` to stage jars outside the
updater directory. Rust: **34 passed**. Initial sandboxed Rust execution passed 33 tests but
the QUIC transport integration test failed with `Operation not permitted`; the authorized
network-enabled rerun passed all 34 without changing assertions. Both logs are retained.

New and extended tests exercise production methods:

- Inventory: nested empty/small/2,048-file trees, managed-looking directories, followed file
  symlinks, broken-link attribute failures, additions/removals/rename, size/time/file-identity
  changes, regular-file-to-directory replacement, shared catalog reference release, exact
  totals and READY state. Existing tests cover scan/read concurrency, incomplete accounting,
  safe readonly payload reads, torn descriptors/shards, age and encounter ordering, garbage-only
  recovery, pins/busy leases, ownership contention and close/reopen during blocked scanning.
  Mutations occur through the descriptor-reader seam, not timing-dependent race sleeps.
- Mesher: 20 fixture shapes, five LODs, two revisions/masks, repeated workspaces and concurrent
  independent workspaces. Complete ordered quads, offsets, lengths, metadata and AABBs are
  hashed against the pre-change production output. Coordinate extrema are also checked
  independently. Invisible/model-zero cells outside visible geometry and all-non-air
  zero-output inputs are included. Lookup failures are propagated and later reuse succeeds.
  Existing boundary-water, fluid/overlay and lighting tests remain enabled.
- Parser: 60 serialized NBT combinations cover block cardinalities 1/2/17/257/4096, biome
  cardinalities 1/3/64 and four light-array presence combinations. Every cell is independently
  checked and the complete cell/fingerprint digest matches baseline. Missing/short/long/index
  errors, invalid cardinality, duplicate Y, mutation ordering, malformed NBT, absent biome,
  single-palette acceptance, light-only air, zero/full/mixed signed light bytes, negative
  coordinates, partial status and owned output from 24 sections are exercised. Existing
  level-zero row-copy and lit-air full/incremental/reopen equivalence tests passed.

Golden digests captured **before** production edits:

- Mesh: `902cccc18a2c2594be1be3df61fbb370153362ec802e88be795d139beb2ff87c`.
- Anvil cells/fingerprints: `48aa47b2a8cfb67478b2d00a759c9a09fd1cc436fd1a726a684cd1a6a6090d7e`.

Limitations: no Windows filesystem test run, permission-denial test against a privileged user,
or separate deterministic directory-walker I/O fault injection was performed. Broken-link
attribute failure and lifecycle/descriptor faults are covered. No new graphics-context test
was needed for these retained non-renderer changes; live GPU evidence is reported separately.

## Measured costs, not performance promises

Baseline sources/binaries were isolated at `/tmp/voxy-simplification-baseline.FMPeOk`.
Fixtures, JVM/Rust options and warmup were held equal. Test-only bytecode instrumentation
counts **explicit Voxy attribute calls**, excluding `Files.walk` internals, and measures the
inventory worker's allotted heap. No production counter, filesystem abstraction or profiler
thread was introduced. Reproduce through the existing runner:

```text
./gradlew -I tools/simplification_bench.gradle schedulerBehaviorTest
# Baseline only: add -Dvoxy.inventoryExpectedReadsPerFile=4
VOXY_SIMPLIFICATION_BENCH=1 cargo test --manifest-path rust-server/Cargo.toml --release \
  anvil::array_tests -- --nocapture
```

Inventory fixture: 4,096 managed regular files, one managed-looking directory, under budget.
Nine runs; no descriptor payload reads or pressure eviction in this query-isolation fixture.

| Measurement | Baseline | Final |
| --- | ---: | ---: |
| Explicit attribute reads | 16,386 | 8,194 |
| Median worker-allotted heap | 8,348,592 B | 6,939,120 B |
| Median inventory time | 25.01 ms | 17.35 ms |
| Observed time range | 17.25–27.65 ms | 11.75–22.71 ms |

An earlier candidate run allotted 7,201,488 B rather than 6,939,120 B; JIT/escape-analysis and
run-order noise remain. Query reduction is deterministic; a universal elapsed-time ratio is
not. These are warm local-filesystem measurements, not cold-disk or client-SSD predictions.
Global OS cache dropping was deliberately not used on this shared host. Inventory remains O(F).

Parser benchmark uses the same serialized 17-block/3-biome section, warm registry, production
`parse_chunk`, 100 warmups and seven samples of 500 parses. Test-only thread-local allocation
observation is disabled outside the measured interval and absent from server binaries.

| Arrays present | Baseline allocated bytes / calls | Candidate bytes / calls | Peak tracked live bytes, before → after |
| --- | ---: | ---: | ---: |
| Packed blocks and biomes | 138,226 / 72 | 135,474 / 70 | 93,082 → 90,330 |
| Above plus both light arrays | 150,514 / 78 | 143,666 / 74 | 101,274 → 94,426 |

Thus this fixture eliminates 2,752 B/two allocations without lighting, or 6,848 B/four
allocations with lighting. The two light copies account for 4,096 B of that saving. These
are tracked allocation bytes, **not process RSS**. Decoded NBT and owned output still allocate.
Alternating pinned-CPU warmed parser runs were approximately 18–20 µs without light and
20–24 µs with light on both versions; no meaningful throughput improvement is established.

The first allocation baseline accidentally reused the candidate test binary through a shared
Cargo target directory. `rust-baseline-allocated.log` is **invalid as a baseline** and retained
only as diagnostic evidence. The corrected isolated target was explicitly rebuilt; use
`rust-baseline-isolated.log` and `rust-baseline-repeat-*.log` for comparisons. Their allocation
counts distinguish the binaries and show the expected copy delta.

Mesher benchmark: 400 warmups, seven samples of 200 meshes for dense/air/sparse/waterlogged/
no-face/model-zero fixtures, plus repeated fresh JVMs and a same-CPU-affinity pair. Per-job
heap remained 128 B for these nonempty outputs and 48 B for empty outputs. Initial no-face
median rose 0.705 → 0.797 ms and model-zero 0.672 → 0.717 ms. Another alternating pair rose
0.707 → 0.825 ms and 0.658 → 0.773 ms. One pair was slightly faster; CPU-pinned runs also
showed substantial broad slowdowns, including air. Shared-host load/JIT effects prevent
assigning all that variation to fused bounds, but there is no reliable benefit and a repeated
zero-output regression signal. Excluding the optimization is the conservative plan-authorized
choice; do not advertise a cell-traversal reduction in the shipped code.

## Deployment and actual identities

Creative was already running again when inspected for this implementation (started around
08:04 UTC); the earlier shutdown from the preceding user turn was not assumed to persist.
Only Creative and MGengine were used. The designated player was told about the update before
the authorized graceful stop. All dimensions saved; the old Java/Rust processes exited.

Original debug server `.196` was moved, not deleted, to the recoverable backup:
`/home/printer/Desktop/Creative/voxy-deploy-backups/0.2.198/`.
Debug `.198` server was installed and Creative restarted. No regeneration was requested.

Runtime-verified identities:

- Baseline client `.197`: `2748f988d0989c13c5d31ddaf9ea59789f7f68f45ed638d1f341ac145a8e22f3`.
- Tested client `.198`: `876c8200f8608f97261af834112624e215a5249fea7799506b1ecde7feaddc09`.
- Final client `.199`: `c5ba9da2b9d3b4500e6977f7e694951458e3440959c50a7e2d26f6f6abed7313`.
- Server `.198` jar: `c3b902d448d6aa3267ececf1c938ad2ab411bc2a6254c6794c7c2e6b8927417d`.
- Actual Rust PID 1679938 executable: `2c3c11158b507421cb9b45c60acca0c7aa5c181d6f1e8591b5152d5ce56d1dca`.

Client hashes were reconstructed exactly from the four signed build-identity words in actual
CLIENT_READY events, not inferred from updater listings. Server version was verified from
startup and `/proc/<pid>/exe` hashing. Intended final pairing is **debug client .199 / debug
server .198**. `.199` changes only the version to invoke the existing updater/restart helper;
the implementation is the same as .198. Its client jars and scheduler suite were rebuilt
and passed. No newer server implementation is being withheld.

## Live results and the slow-disconnect finding

All harness paths below are under `/home/printer/Desktop/Creative/logs/voxy-tests/`.

| Test | Run ID | Outcome |
| --- | --- | --- |
| Baseline .197 zoom/unzoom | `8514622f-72a2-43c9-af22-f18c22ab4232` | PASS |
| .198 same zoom/unzoom | `d9091dfd-d2ba-4b0c-ab0a-8e0b1f62b063` | PASS |
| .198 QUIC recovery | `a978118e-8d90-4c61-bf4b-17264ed4aa40` | PASS |
| .199 populated-cache rejoin smoke | `5385487e-fd4a-49cc-9c32-d72df6601879` | PASS |

The .198 zoom run ended with coverageMissing=0, 66,544 active sections, 141,223 GPU draws and
60,005 cache hits. QUIC recovery preserved sessionGeneration=1 while connectionEpoch advanced
1 → 2; it ended with 94,188 active sections, 201,237 draws and 2,147,108,864 allocated geometry
bytes against the unchanged 2,147,482,624-byte non-sparse capacity. Actual accepted endpoint
telemetry later reached 2,147,481,936 bytes, **not** the exact tail. No endpoint-limit exception
occurred in these tests.

The normal cache was preserved throughout. Cached terrain rendered while inventory was still
SCANNING and persistence was conservatively disabled; inventory then reached READY in
88.864 s. Baseline recorded 94.302 s, but the cache and saved world changed in between, so this
is **not** a controlled 5.4-second speedup claim. Startup first-detail/worker times were not
isolated well enough to establish a TTFD or CPU improvement. Screenshots at the same position
and equivalent wrapped yaw show the same terrain layout; time/weather/occupancy differ, so
they are not pixel-identical or a GPU/FPS benchmark. Shaders were off and left unchanged.

The manual protocol disconnect/rejoin test exposed a serious latency concern: kick at
08:49:29.863 UTC, rejoin at 08:50:42.293 UTC (about **72.4 seconds**). During inspection, the last
render-thread message was `Shutting down rendering`, while updater diagnostics continued.
It initially appeared stuck, but the later server evidence establishes that it **did recover
on its own**, before .199 was published. There is no thread dump establishing the precise
cause; do not call it a proven deadlock, blame these changes, or claim it was fixed. Rendering
shutdown code was not changed. This latency issue needs separate investigation.

An identical-code .199 republish was prepared while the client appeared stuck. It subsequently
performed an additional successful automatic restart: old PID 5440 stopped, new PID 16496
started at 08:54:18.675 UTC, alive after 45 seconds; server rejoin at 08:54:53.266 UTC. The final
harness confirms the .199 hash, cache hits and rendered terrain. The existing nonfatal
`launch-copy-cleanup-failed` warning about a locked updater javaagent remains; it did not
prevent either restart. Do not mistake republishing for a shutdown fix.

Server saved-source imports/incremental publications resumed, with unchanged regions reusing
3,495–3,507 sections in sampled updates. A sampled Rust process RSS was about 1.03 GiB and
lifetime CPU about 81% of one core; this active-world sample is not a baseline comparison.
Occasional `Anvil region changed during metadata-only probe` safe retry messages remain.
No claim is made that this small parser copy reduction resolves overall server memory usage.

## Evidence and remaining limits

Ignored evidence directory: `project_audit/cache_inventory_mesher_anvil_evidence/`.
It retains initial/final build logs, raw baseline/candidate benchmark runs, the rejected
mesher patch, client update/restart logs, original disconnect observations, live-run references
and the cache-rejoin scenario. Harness result/event/screenshot files remain in the server
test directories above; uploaded screenshots are in `/home/printer/screenshots/`.

No player-cache deletion, controlled cold OS-cache experiment, Windows query tracing,
shader-toggle test or new deterministic full position/rotation sweep was performed. Live
comparison used the same stationary camera/zoom scenario and populated-cache restarts; strict
output equivalence comes from isolated fixtures. All benefits and limitations above are
separate from the still-unexplained 72-second graceful-disconnect latency.
