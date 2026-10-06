# General code cleanup: third implementation results

Completed 2026-10-06 on `feature/cache-first-background-updates`.
Selected plan: `general_code_cleanup_third_implementation_plan.md`.
Settled baseline: `e1606b4f`, after reviewing both earlier implementation reports.
Final code commit: `866b6144`.

Implemented A1, A2, A3 and optional B. C1/C2 and all earlier deferred work remain
deferred as planned. The earlier plans/results and selected third plan are
unchanged. Rust sources, configuration, dependencies, versions, shaders, formats,
worker scheduling and ownership policies are unchanged.

No deployment, live controls, client launch, live renderer construction, cache reset
or pressure test was performed. All commits are local. The rejected GitHub
publication was not retried or transferred to another operator.
No loading-speed, frame-time, throughput, object-size or memory-use improvement
is claimed.

## Local patches and changed files

| Commit | Scope |
|---|---|
| `36baa3e0` | A1: remove only the unread stored pose marker. |
| `95622905` | A2: delete only the unreachable private texture constructor. |
| `41d8dbb9` | A3: share exact section ancestry, retain wrappers and add a production-invoking fixture. |
| `866b6144` | B: standard comparison functions and baseline/candidate runner tests. |

Nine maintained files changed:

- `src/debug/java/me/cortex/voxy/client/lod/LiveClientTestHarness.java`:
  remove `PoseExpectation.markerFrame:J` and its assignment only. Its constructor
  parameter, descriptor, deadline and `new DebugPoseStabilizer(markerFrame)` remain.
  `ScreenshotRequest.markerFrame` and the stabilizer's own marker are unchanged.
- `src/main/java/me/cortex/voxy/client/core/gl/GlTexture.java`: delete exactly
  private constructor `(IZ)V`; retain both public constructors, all fields,
  allocation/storage/zeroing/free behavior and imports.
- `src/main/java/me/cortex/voxy/client/core/rendering/SectionKey.java`: add public
  static `contains(long, long)` with the exact old short-circuit arithmetic.
- `src/main/java/me/cortex/voxy/client/lod/ClientSession.java`: retain the outer
  class's private `contains(JJ)Z` signature and replace only its body with delegation.
- `src/main/java/me/cortex/voxy/client/core/rendering/hierarchical/NodeManager.java`:
  retain the corresponding private wrapper and all caller/lifecycle logic.
- `src/schedulerTest/java/me/cortex/voxy/client/core/rendering/SectionKeyContainmentBehaviorTest.java`:
  new independent geometric fixture invoking the helper and both actual wrappers.
- `build.gradle`: one named JavaExec fixture task in the existing scheduler test
  source set, with its existing runtime/native dependencies. Existing task
  dependencies, packaging, exclusions and normal/debug facade selection remain intact.
- `tools/run_live_client_test.py`: private standard-operator import/table and
  direct comparison return. The second plan's allowed-field table remains intact.
- `tools/test_run_live_client_test.py`: nine added comparison/caller test methods
  in the existing offline unittest suite.

Reachability searches covered maintained main/debug/test/shared/server source,
Mixins, resources/access transformers, reflection and active toolkit code. Across
all 446 archived class files, the pose field has one `PUTFIELD`, no read, and no
maintained supported reflective consumer was found. The removed private texture
constructor has no instruction caller, factory, annotation or supported
name-based consumer. These are scoped audits; arbitrary external private
reflection/introspection cannot be ruled out.

## Executed validation and proof

Evidence and reproducible external utilities are retained under:
`/home/aerosmp/Desktop/Codex_Tools/Voxy_Workflow_Tooling/tasks/general-cleanup-third-20261006/`.
The pinned toolkit task is `general-cleanup-third-20261006`.

| Gate | Actual result | Evidence in that directory |
|---|---|---|
| Baseline normal/debug Java compile and client artifact gate | PASS, 4.354 seconds | `baseline-java.*` |
| Candidate compile, debug harness, containment, owner contracts and both artifact gates | PASS, 10.280 seconds | `candidate-java-contract-gates.*` |
| Expanded Python tests before production editing | PASS: 24 methods, archived baseline runner unchanged | `python-expanded-baseline-tests.*` |
| Same Python suite after B | PASS: 24 methods, identical fixture hash | `python-candidate-tests.*` |
| Structural baseline/candidate comparison | PASS: all 446 class files accounted for | `comparator-candidate-main.json`, `comparator-candidate-debug.json` |
| External checker rejection/positive tests | PASS: 34 tests | `comparator-candidate-self-tests.log` |
| Exact A1/A2 source deletions and compiled reachability | PASS | `a1-a2-source-proof.json`, `reachability-and-preservation-receipt.json` |
| Exact A3 source extraction and initialization review | PASS | `check_section_key_extraction.py`, `a3-source-extraction-proof.json`, `a3-source-initialization-audit.json` |
| Actual Python baseline/candidate AST/result/error/caller comparison | PASS | `characterize_python_comparison.py`, `python-comparison-characterization.json` |
| Final compile and both artifact gates at `866b6144` | PASS, 6.936 seconds; all 446 loose classes remain byte-identical to proven candidate | `final-java-artifact-gates.*` |
| Final JAR member/resource/facade/compiler-output checks | PASS | `final-artifact-deltas.json` |

`debugHarnessJavaTest` actually executes the fixed codecs, ownership/mailbox,
restart-command construction, zoom signal/acknowledgement/terminal cleanup and
the real two-consecutive-rendered-frame stabilizer fixture. It does not launch
a client, restart a process or execute the complete live pose controller.
Its logged disconnected-channel exception is an intentional injected failure
case; the fixture and Gradle invocation pass.

The owner aggregate actually executes six mode-specific owner/detail/shutdown
JavaExec fixtures plus four timing JavaExec fixtures. Shutdown also invokes the
real topology, admission and publication lookup contracts, including pending
descendant/unrelated geometry. The separate containment task executes 37,192
pairs through three paths: direct helper, reflective outer `ClientSession`
wrapper, and reflective `NodeManager` wrapper.

Containment tests use independently specified triples and results plus an oracle
that independently decodes arbitrary longs with unsigned division/modulo and
explicit signed-field conversion. Ancestry uses `Math.floorDiv` after rejecting
negative level differences. Coverage includes all 256 decoded-level pairs,
all 31 differences, normal levels and levels 5-15, signed 24-bit X/Z and 8-bit Y
extrema, negative/positive division boundaries, per-axis mismatches, independent
reserved-bit variation, fixed arbitrary packed patterns and seeded samples.
The containment fixture constructs no renderer/session instance or world. `SectionKey`/`NodeManager`
have no static initializer; outer `ClientSession` initialization creates only
the existing delay, monitor, ordered set and atomic ID counter. Neighboring
parent/child/top-root behavior and `SectionKey.pack` remain untouched.

The structural checker retains flags, signatures, annotations, nests/records,
exception tables, verifier frames, literals and resolved constant-pool/bootstrap
behavior. It ignores only source/line/local-variable debug metadata. It applies
no previous rename, constant or cube-extraction allowances. Unknown semantic
attributes or unexplained differences block acceptance.

Of 446 class files, 441 whole classes directly match canonically; exactly five
planned owners receive the following rules:

- A1 permits only the package-access final `markerFrame:J` declaration and the
  exact `aload_0 / lload 4 / putfield` deletion in the original constructor.
  Every remaining instruction, including the separate stabilizer load from
  slot 4, matches. Constructor flags, descriptor, maximum stack/locals and
  absence of handlers/semantic Code metadata remain exact.
- A2 permits only deletion of the exact private `(IZ)V` method. Every remaining
  method, field, initializer and public descriptor matches.
- A3 proves both old wrapper Code structures match each other and the new public
  static helper exactly, including short-circuit branches, stack maps and
  exception handling. Each wrapper contains only two long loads, the exact
  helper call and boolean return. This proof runs before any wrapper comparison
  normalization; the two bodies are not broadly ignored.

All 4,296 retained existing method metadata records match. Of the original
4,227 Code bodies, 4,223 retained bodies match exactly, three receive the precise
transformations above, and one private constructor is removed. One helper is
added with the old algorithm's exact Code. The total remains 4,297 methods and
4,227 Code bodies. Fields decrease only from 2,826 to 2,825. Parser failures: zero.

The 34 checker tests retain 20 parser checks and add 14 target-specific checks.
Negative mutations cover the pose marker slot/stabilizer argument/field flags,
other field removal, stack/handler changes, wrong private/public constructor
deletion, helper flags/arithmetic/branch/verifier changes, differing old
algorithms and wrong forwarding owner/loads/extra instructions/handlers/flags.
The extension and its invocation order received independent read-only review.

Python B preserves the function signature, annotations, keyword parameter names,
lookup-before-comparison order and direct result identity. Its independent
characterization compares 2,904 scalar results/exact errors/nonmutation, 42 rich
comparison/reflected/subclass/fallback cases, eight lookup errors, nine real
assertion-caller cases, six real wait-caller cases and 15 scenario hashes.
Fake evidence/endpoints/clocks replace all control calls and waits. Other module
AST and function definitions, validation, aggregation, dispatch, file writes,
CLI defaults, result kinds and the second plan's field declarations match.
This proof excludes import hooks, private module monkeypatching/inspection and
traceback-frame introspection.

B deliberately retains a small private mutable function table/import in module
state. Comparison failure tracebacks lose the old `<lambda>` frame; exception
classes/messages and operand callback order are preserved. This planned
diagnostic difference is recorded, not treated as exact traceback preservation.
A3 also introduces a delegation call before any possible JIT inlining; neither
inlining nor throughput improvement is promised. Removing a long field does
not prove an eight-byte per-object saving across JVMs.

Builds used the cooperative build lease, external Gradle/Cargo caches and task
TMPDIR. Root was the sole build/suite operator. Finite owner-controlled commands
provided exact gates outside the sealed toolkit's preset surface; its release
was not modified. Authorized local sandbox escalation supplied Gradle's lock
socket, without publishing. Test JVM temporary storage was directed under
Desktop; existing fixtures retain their own project scratch directories.
Existing compiler/deprecation/native-loading warnings are retained.

One preservation check initially assumed the ignored, local-only second plan
existed in the baseline Git tree. That read failed with exit 128. Verification
was corrected to compare its previously frozen external copy; the failure
receipt and passing checks are retained. No product change or relaxed behavior
assertion resulted. All executed candidate test/gate invocations passed.

## Deferred scope and limits

Third-plan C1's server capability wrapper and C2's debug version-check helper
remain untouched. Their handler-transition/metadata-output and diagnostic-frame
gates are not established by these arithmetic or comparison fixtures.
Earlier journal/packing/Iris helpers and the Rust opcode/default batch remain
deferred. The second report records six preexisting Rust test compile errors;
Rust sources are unchanged and that library suite was not rerun for this plan.

Offline tests and structural proof do not establish real GL-driver behavior,
full client-harness controller execution, live network behavior or performance.
No supported reachable GL path was changed; the unreachable constructor removal
does not justify an uncontrolled GPU test. Scheduling, ownership, native
lifetimes, callbacks/fences, settings and data remain unchanged.

## Inventory and artifact deltas

Counts use the same maintained physical-line scopes as the previous report,
including comments/blanks and Rust inline tests, excluding generated outputs.
Nine maintained files change, including one added fixture. No source file or
source folder is deleted; no source folder is added. Main/debug production
source file counts remain unchanged. Python counts cover the seven maintained
`.py` files and exclude unrelated ignored build/cache directories under `tools`.

| Scope | Files before / after | Physical lines before / after |
|---|---:|---:|
| Main Java | 124 / 124 | 26326 / 26317 |
| Debug Java | 18 / 18 | 4169 / 4169 |
| Rust `src` | 27 / 27 | 13512 / 13512 |
| Scheduler test Java | 20 / 21 | 3822 / 3940 |
| Python tools and their tests | 7 / 7 | 1449 / 1676 |

The fresh normal 267 JAR is 3946335 → 3946287 bytes (-48); the debug 267 JAR is
4125097 → 4125037 bytes (-60). Both keep exactly the same ZIP members and exclude
all fixtures; the pose harness stays debug-only and the helper is present in the
existing `SectionKey.class`. Five normal/six debug class entries change raw bytes,
including source-debug-table effects accounted for by the structural comparison.
Selected loose compiler outputs match all 383 normal and 445 debug JAR class
entries exactly, with debug-facade precedence retained.

Native/dependency, Mixin and all other resource entries are unchanged. The only
changed non-class entry is generated `META-INF/neoforge.mods.toml`, whose build
label changes from `e1606b4f` to `866b6144`; version 267 is unchanged. The baseline
was freshly built at the settled second-report commit, so its ZIP sizes are not
borrowed from that report's earlier build label. Stable final copies, exact
SHA-256 values and source inventories remain in `final-artifacts/`,
`final-artifact-deltas.json` and `final-source-inventory.json`. Nothing was deployed.
