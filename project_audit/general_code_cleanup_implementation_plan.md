# General code cleanup: analysis and implementation plan

Status: analysis and plan only. No implementation, builds, deployment, client controls,
or cache changes were performed to produce this document.

Reviewed baseline: `8bec1b4adecbcaf68aa8fdc28b777a6f778594a1`, branch
`feature/cache-first-background-updates`, 2026-10-06. The checkout was clean.
All source anchors below refer to this baseline and must be refreshed before editing.
Work and publication stay on this feature branch, following the established branch
instruction rather than the generic AGENTS.md instruction to publish plans to main.

## Objective and recommendation

Make the existing code easier to understand and review while retaining its current
functional behavior, including failure handling. Start with comments, seven unused
imports, narrowly scoped formatting, and compile-time layout constants. These are
the recommended first implementation batch. Treat helper extraction as a separate,
conditional batch requiring stronger evidence; do not force it to meet a line-count goal.

This is maintenance work. No loading-speed, memory-use, binary-size, or asymptotic
performance improvement is established by this review. Removing locks, changing
workers, fixing download starvation, increasing GPU capacity, changing lease release,
or fixing suspected defects belongs in separate feature/fix plans.

## What the current code shows

The main client Java source contains 123 files and 26,284 physical lines; client
debug Java adds 18 files and 4,169 lines. Rust `src` contains 27 files and 13,511
physical lines, including inline tests. These counts include comments and blank lines
and exclude generated build outputs; they are an inventory, not a complexity score.

The largest responsibilities are concentrated in `ClientSession.java` (3,950 lines),
Rust `server.rs` (1,942), Rust `regional/runtime.rs` (1,613),
`AsyncNodeManager.java` (1,561), Rust `regional/store.rs` (1,320), and
`RegionalDiskBudget.java` (1,210). Size alone does not justify splitting them:
authority, callback identity, initialization, and resource lifetimes cross many methods.

There are genuine small repetitions: detail-readback buffer layout, journal metadata
serialization, and regional-coordinate packing. Other repetitions are intentional:
different pin/writer lifetimes, disk-space revalidation rules, file-replacement policies,
and normal/debug implementations. Consolidating these merely because they look similar
can change behavior.

Test execution needs explicit attention. The root build removes conventional `test`
source sets and disables Gradle `Test` tasks (`build.gradle:18-27`). Custom `JavaExec`
tasks are the meaningful Java gates. `schedulerTestClasses` compiles fixtures; it does
not establish that their `run()` methods execute.

## Ranked cleanup candidates

Scores are maintenance judgments, not measured results. Benefit is readability and
reviewability; complexity is implementation plus verification effort; risk is the
chance of an accidental behavior change. All use 0-10, with higher complexity/risk worse.

| Priority | Candidate | Benefit | Complexity | Risk | Decision |
|---|---|---:|---:|---:|---|
| 1 | Explain ownership, callbacks, and misleading representation comments | 8 | 1 | 0 | First batch; comments must describe verified current behavior. |
| 2 | Remove seven confirmed unused imports | 2 | 0 | 0 | First batch; no speculative dead-code deletion. |
| 3 | Expand selected dense statements without changing executable tokens | 5 | 1 | 1 | First batch; no repository-wide formatter. |
| 4 | Name repeated detail-readback layout constants | 5 | 1 | 1 | First batch; require unchanged compiled method instructions. |
| 5 | Wire relevant existing production fixtures into explicit test tasks | 6 | 2 | 1 | Before touching uncovered logic; test/build scope only. |
| 6 | Extract the two journal metadata byte builders | 4 | 4 | 3 | Conditional second batch, after append/compaction coverage. |
| 7 | Share exact regional packing calculations | 3 | 2 | 2 | Optional; retain duplication if extraction adds more indirection than clarity. |
| 8 | Deduplicate similar Gradle test registration blocks | 2 | 3 | 2 | Defer; preserve task/classpath/artifact differences. |
| 9 | Delete apparently unused entry points or split large stateful classes | Unproven | 6 | 6 | Excluded from the initial cleanup; needs a separate reachability/ownership case. |

## Hard preservation rules

1. Preserve owner phase order (`ClientSession.java:1234-1265`), all demand priorities,
   retry gates, coalescing, wakeups, fallback waits, generations, and worker counts.
   Preserve ordered collections and their iteration/removal behavior. Do not replace
   `LinkedHashSet` buckets with unordered sets or streams.
2. Preserve monitor objects and synchronized boundaries, volatile/atomic accesses,
   lock order, and callbacks outside monitors. In particular,
   `SectionPublicationState.close()` and `WorkerResource.close()` invoke retirement
   or disposal after releasing their monitors. Do not make these whole synchronized methods.
3. Preserve callback object identity, including `Session.rendererWake`
   (`ClientSession.java:630-633`). Recreating a method reference for unregistration
   is not equivalent to retaining the original object.
4. Preserve field initializer and constructor statement order. Renderer initializers
   allocate GL resources; Session constructs its metadata worker before assigning some
   other fields and starts it later. Do not regroup fields by purpose or visibility.
5. Preserve exact ownership transfer and release conditions: claiming a result does
   not itself release its worker lease; save acknowledgements, admission, activation,
   and retirement are different events. Preserve exactly-once cleanup and GPU fences.
6. Preserve integer widths, casts, masks, unsigned comparisons, shifts, overflow
   behavior, floating-point evaluation, native-read order, and allocation counts.
   Do not replace floor division with shifting as a general cleanup.
7. Preserve wire/cache versions, endian order, reserved fields, CRC/hash scopes,
   predecessor/offset rules, storage quotas, file permissions, durability operations,
   integrity checks, corruption policy, and source/world identity.
8. Preserve exception classes/messages, catch boundaries, primary/suppressed exception
   order, cancellation/interruption behavior, logs, debug counters, and configuration
   keys/defaults. Do not replace explicit cleanup with try-with-resources without proving
   identical failure ordering. Pure formatting can move diagnostic line numbers;
   helper extraction can add stack frames. If exact diagnostic locations/frames are
   required for a target, preserve its line mapping explicitly or leave it unchanged.
   Imports and comments can also shift diagnostic line numbers.
9. Preserve public/package-facing signatures, Mixin names/targets, resource paths,
   normal/debug facade selection, dependencies, native classifiers, JAR exclusions,
   task names, and artifact entry points. No dependency upgrades or blanket warning fixes.

## Batch A: recommended mechanical cleanup

### A1. Describe existing rules beside the code

- Add only missing ownership/monitor/callback notes near Session fields and
  `PendingInterests` (`ClientSession.java:517-735`), demand mailbox operations
  (`SectionDemandTable.java:42-245`), and publication/worker lifetime boundaries.
  Existing comments in `WorkerResource` and `SectionPublicationState` already explain
  important rules; avoid duplicating them in a second, drifting narrative.
- Clarify `rust-server/src/regional/index.rs:30`: its EMPTY-entry projection omits
  payload metadata. This does not mean that every air body is omitted by the wire
  representation. `regional/wire.rs:74` deliberately retains DATA bodies carrying
  lighting/biomes. Keep flags and transformations unchanged.
- Correct the comment in `BasicAsyncGeometryManager.java:225`: `(size+127)&~127`
  rounds to a multiple of 128 elements. Leave the arithmetic, overflow behavior, and
  allocation path untouched.
- Do not implement TODOs or delete warnings merely because this is called cleanup.

### A2. Delete exactly the verified unused imports

| File under `src/main/java/me/cortex/voxy/client/` | Imported symbol |
|---|---|
| `lod/ClientSession.java:6` | `VoxyConfig` |
| `lod/ClientSession.java:10` | `CatalogMapper` |
| `lod/ClientSession.java:34` | `LinkedHashMap` |
| `lod/LocalSection.java:4` | `IOException` |
| `core/VoxyRenderSystem.java:44` | `AtomicReference` |
| `core/rendering/hierarchical/NodeManager.java:14` | `ArrayList` |
| `core/rendering/hierarchical/HierarchicalOcclusionTraverser.java:16` | `Logger` |

Recheck each symbol against the current file immediately before deletion. Do not
apply global import sorting or remove unrelated imports found by a broad lint pass.

### A3. Format only selected dense method bodies

Expand statements in `ClientSession.PendingInterests` (`:553-580`), selected bottom-of-file
accessors/cleanup (`:3814-3817`, `:3919-3938`), and
`core/ShaderResourceScope.java:25-35` onto separate lines. Preserve every executable
token, string literal, local name, statement, catch, and expression in its original order.
Keep resource-close order and the `FREED` increment conditional on successful cleanup.

Do not reorder members, rewrite boolean expressions, replace loops/iterators with
streams, introduce braces around different scopes, or reformat shaders. Compare tokens
as well as the visible diff; reject any unexpected compiled-method change.

### A4. Name one native layout in one class

In `HierarchicalOcclusionTraverser.java`, use same-class private primitive compile-time
constants for detail counter stride/header bytes, detail record stride, and total bytes.
Reuse them in `BorrowedDetailActions` (`:98-135`), buffer construction (`:189-190`),
and counter reset (`:525-528`). Retain `BorrowedDetailActions.REQUIRED_BYTES` as a
compile-time alias of the outer total rather than removing the existing field.
Keep the original `4L` and `16L` arithmetic widths,
offsets `0/4/8/12`, unsigned count clamp, key assembly, and action filtering.

Keep the registration recheck, callback-thread restriction, borrowed address lifetime,
and finally invalidation intact. Do not merge the visible-section layout with this
layout merely because both use 16-byte records. Do not change shaders or GL calls.
Require unchanged compiled instructions for existing affected methods.

## Batch B: verification before any optional helper extraction

The following existing tests actually execute production contracts:

- `cacheLoadingOwnerEfficiencyTest` runs owner, detail-batch, and publication-shutdown
  fixtures in both normal/debug modes, plus timing checks (`build.gradle:361-409`).
  Shutdown also calls publication topology, renderer admission, and lookup fixtures.
- `worldCacheVisibilityTest` executes the visibility fixture.
- `cacheLoadingJournalTimingTest` calls production journal/local-codec paths and
  corruption/quarantine handling; its current payload fixture has one entry and
  does not cover compaction.
- Rust library tests cover storage, source replacement, refresh/recovery, and pacing;
  the shared palette fixture is a registered test in `regional/section.rs:352-354`.

`RegionalSectionCodecBehaviorTest` and `Blake3BehaviorTest` currently expose only `run()`
with no checked-in caller/task. Several geometry/config/shader fixtures have the same
gap. If a later cleanup touches those areas, add a narrow runner in the existing test
source set and a named Gradle execution task for the relevant fixtures. Keep fixtures
out of every normal/debug JAR; do not bundle tests to make them runnable.

Before journal extraction, add tests calling the real append, compact, and reopen
paths with multiple bindings and a shared payload. Cover overwrite predecessors,
compaction predecessor zero, payload offsets, EMPTY/ABSENT/reset, reserved zero fields,
little-endian metadata bytes, CRC/hash rejection, and unchanged decoded bindings/cells.
Independently exercise frame metadata/footer corruption, compressed-body CRC mismatch,
and local-hash mismatch, keeping the other integrity checks valid where necessary so
one rejection cannot mask a changed checksum scope. Exercise truncated input and
cancellation without modifying production rules.
Expected bytes must be specified independently of the extracted helper. Do not sort
existing maps to stabilize a golden file: compaction uses unordered-map iteration.
Compare exact per-frame metadata using its explicit offsets and verify the resulting
reference graph; document legitimate cross-process iteration variation.

## Batch C: small optional helpers, each in its own patch

### C1. Journal metadata builders

`CompletedSectionJournal.java:196-197` and `:380-381` repeat payload metadata encoding;
`:216-218` and `:396-399` repeat binding metadata encoding. Extract only private, pure
byte builders. Pass payload and predecessor offsets explicitly: compaction passes
predecessor zero; append passes the previous binding offset. Allocate the same buffer
at the same call-site stage and write the same fields in the same order.

Keep pinning, reservations, `current` checks, locks, frame/footer writes, CRC/hash work,
commit callbacks, forcing files, map mutations, failure notification, and release
where they currently occur. No generic append/compaction lifecycle abstraction.
Proceed only after Batch B passes on the baseline and the candidate with identical
metadata/error results. Stop if a helper obscures rather than clarifies the format.

### C2. Regional packing, only if worth the indirection

The shift-based region calculations in `LocalSection.java:27-30` and
`CompletedSectionCache.java:28-32` are identical. `WorldCacheDownloads.java:913`
contains the same two-coordinate packing expression. A tiny stateless helper in an
existing appropriate class may remove this repetition; do not create a utility
framework or make a cache class depend on the download coordinator.

Retain arithmetic right shifts, unsigned low-half packing, signed high-half coordinates,
and existing behavior for every long/int input. Add no validation or new exceptions.
Test negative/positive region edges, all supported LODs, coordinate extrema, and the
existing malformed-level behavior. Do not fold in the floorDiv variants in
`VisibleSectionState.java:177` or `ClientSession.java:3878`; their invalid-input behavior
differs. If class initialization, extra calls, or dependencies make equivalence unclear,
leave these few duplicate lines alone.

## Explicitly deferred work

- `BasicAsyncGeometryManager.uploadReplaceSection/createMeta` resembles
  `tryUploadSection`, but allocation, overflow, exception, and rollback behavior differs.
  No checked-in caller found for the former is not proof that a public method is unused.
- `CompletedSectionCache.begin/putMetadata/put` appear uncalled in current Java sources.
  Deletion needs a complete source/debug/test/resource/Mixin/reflection/tooling/API
  reachability check, not a single text search. Keep their `Save` ownership unchanged.
- Similar UTF-8 readers have different limits, buffer ownership, and error categories.
  Similar Rust file replacements have different creation modes, permissions, fault
  points, durability, and cleanup. Keep temporary-file counters independent.
- Keep normal/debug facade pairs and artifact checks. Similar Gradle registration
  blocks have different dependencies, classpath order, scratch paths, and entry points.
  Build abstraction is lower value than protecting those differences.
- Do not split Session, renderer managers, storage/runtime state, or move synchronized
  methods across objects in this plan. Do not change shader arithmetic, bindings,
  layouts, barriers, packed formats, pacing, backpressure, scheduling, or cache limits.

## Execution and verification

1. Recheck HEAD, branch, dirty files, and other agents' activity before each batch.
   Refresh anchors; do not overwrite another agent's edits. If the active checkout is
   being changed, use an isolated checkout under Desktop and leave its Git/index/tmux
   state alone. Keep baseline/candidate build evidence outside maintained source.
2. Compile baseline and candidate with the same toolchain and dependencies. For the
   Java mechanical batch use `./gradlew compileJava compileDebugJava --console=plain`.
   Include server compile tasks only if its Java/build wiring changes. Do not use
   `./gradlew clean` against another agent's active outputs.
3. For import/comment/token-only changes, compare compiled existing methods, including
   affected nested classes. Resolve constant-pool references rather than blindly deleting
   indices. Preserve opcodes/operands, exception tables, method flags/signatures,
   annotations, and invokedynamic/bootstrap behavior; ignore source line/local debug
   metadata. For A4 allow only the intended new private constant fields; existing
   method instructions must still match. An unexplained difference blocks that patch.
4. Run `cacheLoadingOwnerEfficiencyTest` and `verifyClientOwnershipArtifacts` for the
   layout/owner-area batch. Use existing production fixtures, not a second implementation
   of the edited algorithm. No new behavioral tests solely for imports or formatting.
5. For optional storage helpers run the newly wired codec/hash and journal tests,
   `cacheLoadingJournalTimingTest`, and `worldCacheVisibilityTest`. Record actual executed
   test names/counts and failures. Compilation alone is never reported as test execution.
6. When Rust executable code changes, use `cargo test --locked --lib` and
   `cargo check --locked --all-targets` in `rust-server`; comment-only Rust edits need
   a token-preservation check. Avoid a broad rustfmt/clippy autofix or new warning policy.
7. If build/tooling code is touched, inspect the task graph/classpaths and run the
   affected real tasks plus `verifyDebugHarnessArtifacts`. Preserve native/dependency
   entries, normal/debug exclusions, Mixin manifests, and test exclusion. Account
   explicitly for generated Git-version metadata when comparing artifacts.
   Python tooling changes require their existing offline unittest tests, using
   `PYTHONDONTWRITEBYTECODE=1`, `TMPDIR` under a task directory on Desktop, and the
   proper existing import path. No live SSH/tmux actions just to validate formatting.
8. Keep commits small: comments/imports, selected formatting, layout constants, test
   wiring, then each optional helper. Stage named files only; never sweep in unrelated
   dirty work or receipts. Each helper patch includes its production-contract evidence.
9. This plan requires no deployment or live restart. If later implementation changes
   observable runtime paths and offline proof is insufficient, define the missing
   check before deployment and honor existing exact-target, cache-preservation, and
   ten-minute total live-verification limits. Never treat offline tests as real-driver,
   real-network, or loading-throughput proof.

Accept a batch only when its diff is within scope, required equivalence/production
checks pass, and normal/debug packaging rules remain intact where affected. If a
failure exposes an existing defect, retain its evidence and handle the fix separately;
do not silently repair it in cleanup or weaken tests. Record changed files, validation,
remaining uncertainty, and why deferred candidates stayed deferred. Report actual
source/file/artifact deltas without promising a target line count or speedup.
