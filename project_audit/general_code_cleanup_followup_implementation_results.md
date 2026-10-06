# General code cleanup follow-up implementation results

Completed 2026-10-06 on `feature/cache-first-background-updates`.
Selected plan: `general_code_cleanup_followup_implementation_plan.md`.
Actual baseline: `8fc6988a`, after reading the first cleanup's completed
`general_code_cleanup_implementation_results.md`. Final code commit: `42641d35`.

Implemented A1, A2, A3, C1 and C3. B1/B2 and C2 remain deferred under the plan's
prerequisite and proof rules. The first cleanup's deferred journal and packing
helpers remain deferred. Both plan documents and the first results document
were left unchanged.

This is a maintenance change. No loading-speed, throughput, allocation or
rendering improvement is claimed. No deployment, live client/server controls,
restart, cache reset or pressure test was performed. Local commits only;
the rejected GitHub publication was not retried or bypassed.

## Local patches and scope

| Commit | Scope |
|---|---|
| `e56c3b87` | Separately reviewed, test-only configuration prerequisite correction. |
| `827fd1ad` | A1: exactly three private encoded-operation set names. |
| `61f0c74b` | A2: seven private integer encoder phase/table constants. |
| `e96a2159` | A3: five private integer publication lane/stage constants. |
| `430fad45` | Execute the existing regional codec and Blake3 fixtures. |
| `76aee4a6` | C1: exact stateless native cube writer and GL-free fixture. |
| `42641d35` | C3: one Python operation/allowed-field declaration and offline characterization tests. |

A1 changes only `AsyncNodeManager.tlnIdChange`, `cleanerIdResetClear` and
`SyncResults.tlnDelta` to the planned names. Their objects, access flags,
descriptors, encoded operations and lifecycle remain intact. Source/debug/test,
Mixin/resource and tooling references were audited. `results`, `resultCache1`
and `resultCache2`, including their name-based VarHandle lookups, are untouched.
No maintained reflective or diagnostic contract for the three renamed private
fields was found; arbitrary external private reflection cannot be excluded.

A2 retains the integer state machine, implicit zero initialization and all
existing transitions/read/error behavior in `LocalSectionCodec.NamesInput`.
A3 retains the two-by-three telemetry arrays, construction, atomic accesses,
timestamps, guards and emitted labels in `VoxyRenderSystem`.

C1 replaces only the two 36-write blocks in `SharedIndexBuffer` with calls to
the package-private `CubeIndexWriter`. The helper has no fields, GL references
or static initializer. It performs the exact unrolled writes and returns the
advanced pointer. Allocations, memset, loops, integer overflow, casts,
constructors, static singletons, upload/free and commit placement remain intact.
The four runtime users in `VoxyClient`, `MDICSectionRenderer`, `FullscreenBlit`
and `ChunkBoundRenderer` are unchanged. One helper class/call is the deliberate
maintenance tradeoff; no runtime performance benefit was measured.

C3 moves the exact 18-operation allowed-field dictionary to module scope,
derives the existing public `VALID_STEPS` set once, and uses the dictionary at
the original extras-check stage. All 29 function definitions match after the
approved data-layout normalization. Validation order, errors, numeric rules,
hashes, dispatch, defaults, result kinds, timeouts and exits are retained.
Keeping this small dictionary alive is the stated storage tradeoff. Existing
questionable nonfinite/odd-input behavior was characterized and preserved.

`build.gradle` adds only two named JavaExec fixture gates using the existing
scheduler test source set/runtime dependencies. The existing regional codec
fixture gains a main entry point invoking itself and the existing Blake3
fixture. C1 adds one fixture in the existing test source set. No dependency,
version, packaging exclusion, native entry or production task dependency changes.

## Executed validation

External evidence directory and pinned toolkit task:
`/home/aerosmp/Desktop/Codex_Tools/Voxy_Workflow_Tooling/tasks/general-cleanup-followup-20261006/`.

| Gate | Actual result | Evidence in that directory |
|---|---|---|
| Baseline Java compile and client artifact gate | PASS, 3 seconds | `baseline-java.*` |
| Baseline `cargo test --locked --lib` | FAIL, exit 101: seven compile errors | `rust-baseline-test.*` |
| Same Cargo command after isolated prerequisite fix | FAIL, exit 101: six compile errors | `rust-prerequisite-test.*`, `rust-prerequisite-review.json` |
| A1-A3 compile, codec/hash and journal fixtures | PASS, 26 seconds including Rust release rebuild | `client-naming-compile-codec.*` |
| Owner contracts and normal/debug artifact gates | PASS, 7 seconds | `client-naming-contract-gates.*` |
| C1 compile, real native writer and artifact gates | PASS, 5 seconds | `cube-native-compile-gates.*` |
| Expanded Python unittest suite against baseline and candidate | PASS: 15 test methods on each | `python-baseline-tests-passed.log`, `python-candidate-tests.log` |
| Final compile and both artifact gates at `42641d35` | PASS, 4.538 seconds; 446 loose class files unchanged from proven candidate | `final-compile-artifact-gates.*` |
| Final structural class comparison | PASS: 445 existing class files, one approved helper added | `comparator-final-main.json`, `comparator-final-debug.json` |
| Independent structural-checker tests | PASS: 36 tests | `comparator-final-self-tests.log` |
| Exact C1 source/caller proof | PASS | `check_cube_index_extraction.py`, `c1-caller-source-proof-final.json` |
| Actual baseline/candidate Python characterization and AST proof | PASS: 176 validator cases, 18 dispatch operations, 15 scenario hashes | `characterize_python_cleanup.py`, `python-cleanup-characterization.json` |
| Final JAR membership, resources, facades and compiler-output identity | PASS | `final-artifact-deltas.json`, `jar-facade-selection-diagnostic.json` |

The codec task actually executes the production-invoking regional payload and
Blake3 fixtures. The journal task executes the real local streaming encoder
and persistence fixture. The owner aggregate executes six mode-specific
owner/detail/shutdown JavaExec fixtures and four timing fixtures; shutdown
also invokes its topology, admission and publication lookup contracts.

The C1 fixture directly invokes the production writer without initializing
`SharedIndexBuffer` or its eager GL singletons. Eleven bases cover zero, eight,
248, byte wrapping, negatives and integer extrema. It checks 396 payload bytes,
352 guard bytes and eleven returned pointers; its single native allocation is
freed exactly once. These are 759 checks, not live OpenGL-driver validation.

The compiled comparison resolves constant-pool references and retains flags,
signatures, annotations, nests, exception tables, bootstrap behavior and verifier
structure. Only source/line/local-variable debug metadata is ignored. Exactly
the three owner/name/descriptor field mappings and twelve expected private
constant fields are allowed. Of 4,225 existing Code bodies, 4,223 match exactly
under those naming mappings. The two cube generators pass a separately scoped
proof of the exact 36-store substitution, surrounding instructions, branch
destinations and stack-map locals/targets. Their changed code is not ignored.
All three `SharedIndexBuffer` constructors, its static initializer, both quad
generators and `id()` remain exact. The helper's two methods are separately
verified. There are no missing classes or parser failures.

The verifier's 36 tests include seven C1-specific checks; negative mutations
reject changed surrounding allocations, helper bases, handlers, stack/local
limits, branch destinations and verifier local types. Scripts and frozen class
snapshots remain external, rather than becoming another repository framework.

Python baseline/candidate checks execute the real validators and dispatch
against mocked command endpoints and clocks. They compare exact exception
classes/messages and input nonmutation, every operation and field set,
multiply-invalid inputs, boolean/zero/negative/nonfinite numerics and existing
odd operation values. No tmux/SSH commands or real waits were sent. The first
expanded baseline run exposed an incorrect fixture expectation: a set-valued
operation produces `ScenarioError`, whereas lists/dicts produce `TypeError`.
Only that expected outcome was corrected; the initial failed log is retained
as `python-baseline-tests.log`.

The first final artifact check incorrectly compared the debug facade against
main output first. The debug JAR intentionally substitutes `ClientLodDebug`.
Correcting the checker to the unchanged debug-before-main selection proves
every selected compiled class matches its JAR entry. No product change was
made for this verification error; diagnostic receipts are retained.

Builds used external caches/TMPDIR and the cooperative build lease, with root
as sole build/test operator. Exact selected compile/gate commands are outside
the sealed toolkit's preset surface; finite owner-controlled local commands
were used without modifying that release. Gradle's local lock socket requires
the authorized sandbox escalation. This was not a publishing escalation.
Existing compiler/deprecation and LWJGL loading warnings remain in the logs.

## Deferred work and limits of proof

B1/B2 were not started. The baseline configuration test passed three arguments
to a two-argument function. The separately reviewed correction removes only
the obsolete boolean inside `#[cfg(test)]`; production source preceding that
module is byte-identical. The rerun still fails on six preexisting
`regional/refresh_tests.rs` errors: four nonexistent `refresh_once` calls, one
nonexistent responder `region` call and one pattern missing `changed_ordinals`.
No Rust library test executed successfully. No unrelated fixture repair,
disabled assertion, opcode fixture, Rust protocol change or default derivation
was introduced. B's subsequent all-target gate was not claimed or run.

C2's Iris uniform scan remains unchanged. Its benefit is small and actual
baseline/candidate caller-level exception/message and side-effect proof was
not established within this scope. Simple helper tests would not satisfy that
gate. Extraction also moves a diagnostic stack frame; do not assume that
matching scan output proves full caller or Iris compatibility.

Offline checks do not establish real rendering, GL constructor execution,
Iris compatibility, QUIC behavior or throughput. Constructor instructions are
preserved structurally; the native fixture exercises only the new writer.
The telemetry fixtures do not directly cover A3's exact histogram paths;
instruction equivalence is the primary evidence for those names.
All other deliberately deferred items from both plans remain unchanged.

## Inventory and artifact deltas

Counts compare immutable `8fc6988a` source with the clean code tree at
`42641d35`. Physical lines include comments/blanks and Rust inline tests;
generated outputs are excluded. Eleven maintained files changed, including
two added Java files. No source file was deleted. Main-source folder count
is unchanged; one scheduler-test package folder was added. Python counts
cover the seven maintained `.py` files, excluding unrelated ignored build/cache
directories under `tools`.

| Scope | Files before / after | Physical lines before / after |
|---|---:|---:|
| Main Java | 123 / 124 | 26346 / 26326 |
| Debug Java | 18 / 18 | 4169 / 4169 |
| Rust `src` | 27 / 27 | 13512 / 13512 |
| Scheduler test Java | 19 / 20 | 3772 / 3822 |
| Python tools and their tests | 7 / 7 | 1251 / 1449 |

The normal 267 JAR is 3945642 → 3946336 bytes (+694); the debug 267 JAR is
4124405 → 4125098 bytes (+693). Each adds only `CubeIndexWriter.class`, removes
no member and contains no fixture classes. Twenty-two existing class files
change raw bytes; their executable structures are accounted for above.
Native/dependency entries and all other resources are unchanged. The only
changed non-class resource is generated `META-INF/neoforge.mods.toml`, whose
build revision changes from `8fc6988a` to `42641d35`. Version 267 is unchanged.
Final stable artifact copies, SHA-256 values, source hashes and inventory are
retained in `final-artifacts/`, `final-artifact-deltas.json` and
`final-source-inventory.json`. They are local artifacts, not deployed builds.
