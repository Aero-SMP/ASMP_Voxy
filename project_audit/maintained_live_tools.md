# Maintained operator source and dependency ownership

The reused live operator implementations have been promoted from the excluded
`.verification/ops` area into counted `tools/live` source. The exact original
inputs and their SHA-256 hashes remain in `live_tool_promotion.json`, with their
historical copies under `.verification/ops/history/promoted`. Canonical files
were subsequently generalized and formatted; their new hashes must not be
mistaken for those historical input hashes.

The promoted responsibilities are SSH execution and restricted transfers,
current status, two-backup identity checks, update observation, telemetry deltas,
stock frame/JFR capture, allocation analysis, cached zoom, SSH error observation,
and existing pressure-run observation. The custom Apache MINA-based backup SSH
helper and its build/connector source are also counted under `tools/live/ssh`.
They previously lived in the original ASMP_Voxy repository while the branch's
private Python wrapper invoked that repository's connector by absolute path.

The canonical update observer uses actual process handles/start times and
generic fixed paths. It appends one JSONL row per observation rather than
rewriting an ever-growing JSON array. Common PowerShell readers preserve
FileShare.ReadWrite and FileShare.Delete for starting/ready/status and jar reads,
so operator observation permits atomic updater replacement. Operator promotion
does not change either deployed SSH helper, its credentials/pins, the client,
server, native process or running load-driver artifact.

Retired one-time UpgradeAgent, TransitionHelper, RuntimeCapture, AttachUpgrade,
LaptopMigration and DesktopSupport sources/artifacts are historical operations,
not product runtime or maintained launch implementations. The product updater
uses its counted source and the official launcher URI. One-time cache conversion
and record relocation operators remain historical migration evidence; frozen
baseline sources/artifacts and failed-run scripts remain comparison evidence.
Those artifacts must not become an excluded home for new maintained behavior.

`local_maven_runtime_audit.json` inventories all 16 cached Maven jars with local
SHA-256, upstream checksum URL and match result. All 16 match their public
upstream SHA-1 checksums: 15 from Maven Central and Sodium API from the official
CaffeineMC repository. The components are LWJGL core and Zstd/native libraries,
Kwik, agent15, HKDF, SipHash and Sodium API. No jar contains the project namespaces
`com/aerosmp`, `me/cortex/voxy`, `assets/voxy` or the project native-server resource.
The exact upstream checksum matches establish that these jars are unmodified
third-party distributions rather than project-owned runtime code hidden in a
dependency. Current Gradle packaging embeds the four Kwik-related distributions
in the client, uses Sodium API for compilation, and embeds none of these jars in
the server. LWJGL/Zstd copies served the historical standalone migration work.

The copied JFR configuration is visible alongside the maintained capture tool;
report its configuration line count separately if the source inventory does not
classify `.jfc` as source. Generated third-party JDK attach modules and standard
dependency caches remain generated inputs, not custom operator implementations.
This audit does not establish performance or pressure acceptance for a source
candidate that has not been compiled and loaded.
