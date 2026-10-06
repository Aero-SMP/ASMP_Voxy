# General code cleanup implementation results

Completed 2026-10-06 on `feature/cache-first-background-updates`.
Selected plan: `general_code_cleanup_implementation_plan.md` at local commit
`3947dbca`. Code baseline: `3947dbca`; final code commit: `9dcd32d5`.

Implemented the recommended mechanical Batch A. The conditional Batch C helpers
remain deferred under their stated gates. Scheduling, locking, worker, cache,
wire, renderer and settings behavior is unchanged, as are dependencies, build
wiring and version numbers.
No deployment, live restart, client controls, cache deletion or GitHub publishing
was performed. The rejected publication was not retried. The separate follow-up
document and the selected plan document were left untouched.

## Local patches

| Commit | Scope |
|---|---|
| `be140151` | Verified ownership/monitor/callback comments and exactly seven unused imports. |
| `53234b1f` | Selected interest methods, bottom-of-file statements/accessors and shader-resource cleanup formatting. |
| `9dcd32d5` | Four private compile-time constants for the existing detail-readback layout. |

Eight Java files and one Rust file changed:

- `client/lod/ClientSession.java`: owner-only interests, wake/publication boundaries,
  retained callback identity and metadata-worker construction notes; three imports;
  selected formatting. The refreshed plan anchors actually identify the
  `PublicationRef` constructor and canonical-property statements as well as nearby
  accessors. Their statements were expanded without changing executable tokens.
- `client/lod/SectionDemandTable.java`: mailbox monitor, detached-map transfer,
  feedback-scope invalidation and synchronous reader/caller/owner boundaries.
- `client/lod/LocalSection.java`: unused `IOException` import only.
- `client/core/VoxyRenderSystem.java`: unused `AtomicReference` import only.
- `client/core/ShaderResourceScope.java`: formatting only; release order, successful
  `FREED` increments and primary/suppressed failures retained.
- `client/core/rendering/hierarchical/NodeManager.java`: unused `ArrayList` import only.
- `client/core/rendering/hierarchical/HierarchicalOcclusionTraverser.java`: unused
  `Logger` import and detail layout constants only.
- `client/core/rendering/section/BasicAsyncGeometryManager.java`: rounds to a
  multiple of 128 elements, corrected comment only.
- `rust-server/src/regional/index.rs`: EMPTY index projection omits payload metadata;
  DATA section replies can still retain air lighting/biomes, corrected comment only.

Java paths above are relative to `src/main/java/me/cortex/voxy/`.
The new long constants are exactly 4, 128, 16 and 131200 bytes. The existing nested
`REQUIRED_BYTES` field remains a compile-time alias. Arithmetic widths, record
offsets, read order, callback registration/thread checks and borrowed lifetime remain
unchanged. Existing worker/publication lifecycle comments were not duplicated.

## Executed validation

Evidence is retained outside maintained source at:
`/home/aerosmp/Desktop/Codex_Tools/Voxy_Workflow_Tooling/tasks/general-cleanup-20261006/`.
The pinned external toolkit task is `general-cleanup-20261006`.

| Gate | Result | Evidence |
|---|---|---|
| Baseline `compileJava compileDebugJava --console=plain` | PASS, 3 seconds; compile tasks up-to-date for unchanged inputs | `baseline-compile.log`, `baseline-compile.json` |
| Candidate same compile tasks | PASS, 25 seconds | `candidate-compile.log`, `candidate-compile.json` |
| `cacheLoadingOwnerEfficiencyTest verifyClientOwnershipArtifacts --console=plain` | PASS, 6 seconds | `candidate-gates.log`, `candidate-gates.json` |
| Final compile tasks and `verifyClientOwnershipArtifacts` | PASS, 5 seconds, code commit `9dcd32d5` | `final-compile-packaging.log`, `final-compile-packaging.json` |
| Mechanical token comparison | PASS, eight current-file comparisons plus two comments/imports snapshots; only exact authorized imports removed from those baselines | `check_mechanical_tokens.py`, `mechanical-token-equivalence-final.json` |
| Structural compiled-class comparison | PASS, 445 classes, 4295 methods, 4225 Code bodies; no missing/added classes or unsupported attributes | `compare_classes.py`, `comparator-final-main.json`, `comparator-final-debug.json` |
| Independent verifier checks | PASS, 20 offline unittest cases | `test_compare_classes.py`, `comparator-selftest-final.log` |
| Built JAR class/member comparison | PASS; same members and equivalent changed class structures | `jar-class-equivalence.json`, `final-artifact-deltas.json` |

The six mode-specific owner/detail/shutdown JavaExec fixtures actually ran, three
in normal mode and three in debug mode. Shutdown also executed topology, renderer
admission and publication lookup fixtures. The four timing JavaExec fixtures also
ran: owner, worker, render and journal. All emitted their success messages. This is
ten top-level JavaExec executions, not a claimed count of individual assertions.

The token snapshots separately cover ClientSession before formatting and Traverser
before the deliberate layout substitutions. Current Traverser A4 substitutions are
covered by the compiled structural gate rather than claimed as identical source tokens.

The structural checker resolves constant-pool references and preserves their
sharing where resolution identity matters. It compares opcodes/operands, exception
tables, stack maps, flags, signatures, annotations, nests/records and bootstrap
behavior. It ignores only source/line/local-variable debug metadata. Exactly the
four named private static final long constants, with their expected values, are
allowed additions. Independent positive reindexing and negative mutation checks
exercise the verifier; unknown metadata blocks comparison.

Final main classes: 382 classes / 3576 methods / 3508 Code bodies. Final debug
classes: 63 / 719 / 717. Existing executable structures match the archived baseline.
All 63 debug class files are byte-identical. Each final JAR has 78 changed class
files whose executable structures match; its remaining class files are byte-identical.
JAR exclusions passed; native/dependency entries, Mixin resources and member sets
are unchanged. The only changed non-class resource is generated
`META-INF/neoforge.mods.toml`, whose build revision changes from `b5494a77` to
`9dcd32d5`. Those earlier baseline JARs have the same baseline source: commits
between `b5494a77` and `3947dbca` changed only the two audit/plan documents.

Rust executable tokens are unchanged, so the plan's comment-only token gate applies;
full Rust library/all-target checks were not required or claimed. The existing
Gradle task graph selected server compilation tasks (up-to-date) and rebuilt the
Rust release binary during candidate compilation after the Rust comment edit.
The task graph and build configuration were not modified.

The sandbox's first baseline attempt failed before compilation because Gradle
could not determine a usable wildcard IP for its lock socket. Its log/receipt are
retained as `baseline-sandbox-failed.*`; the authorized finite local compile then
passed outside the sandbox. This escalation was for local compilation, not
publishing. Build commands used the existing external cache paths and cooperative
build lease; exact compile/gate tasks are not exposed by the sealed toolkit's
named presets, so finite owner-controlled commands were used without changing it.

The initial token checker used an incorrect package for the authorized
`CatalogMapper` import. Its failed receipt was retained, the checker was corrected
against the archived source, and all ten final comparisons passed. This was a
verification-tool correction, not a production-code defect or a relaxed assertion.

Existing deprecated/unchecked compiler notes and LWJGL loading warnings are
retained. Fixtures completed successfully; their doubled/native-lifetime boundaries
do not establish real OpenGL-driver or network behavior. No live-performance,
loading-speed or resource-use improvement is claimed.

## Conditional and excluded work

Batch B adds fixture wiring only before touching uncovered logic. No such helper
was introduced, so no new source-set/task wiring or behavioral tests were added.

C1 journal builders remain deferred: the current timing fixture covers a single
payload and no compaction, and the required independent append/compaction/reopen,
predecessor/reference graph and corruption-scope checks have not been established.
Extracting now would violate the stated baseline/candidate gate. Direct metadata
writes remain beside their distinct append and compaction offsets/lifecycles.

C2 packing remains deferred: removing three tiny expressions would introduce an
additional call/class dependency without enough clarity benefit. The shift-based
malformed-level behavior and different floorDiv variants remain intact. No utility
framework, dead-entrypoint deletion, large-class split, Gradle deduplication or
shader/geometry/storage behavior changes were attempted.

## Inventory and artifact deltas

Same physical-line definition as the plan: comments and blank lines included;
generated outputs excluded; Rust inline tests included. Source file/folder sets
are unchanged. Nine existing source files changed, with 108 inserted and 45 deleted
lines (net +63); no source files or folders were added/deleted.

| Scope | Files before / after | Physical lines before / after |
|---|---:|---:|
| Main client Java | 123 / 123 | 26284 / 26346 |
| Debug client Java | 18 / 18 | 4169 / 4169 |
| Rust `src` | 27 / 27 | 13511 / 13512 |

Final local normal 267 JAR: 3945491 → 3945642 bytes (+151).
Final local debug 267 JAR: 4124254 → 4124405 bytes (+151).
These include debug-table/compression and generated Git-revision effects; this
maintenance patch does not promise binary-size or runtime improvements. Exact
hashes, stable copies, source hashes and inventories remain in the external evidence
directory. These artifacts were built locally and were not published or deployed.
