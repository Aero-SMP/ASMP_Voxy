# Source-count cleanup

The current goal.txt authorizes 10,000 maintained source lines and 50 source
files. Previous 30% numerical gates are superseded, not passed. The frozen
original rewrite and its original counting evidence remain unchanged.

The user explicitly requires removing tricks used to lower the count. Current
source cleanup replaces dense multi-statement Java with conventional formatting,
separates client settings/session/renderer and updater launcher responsibilities,
and counts reused custom live tooling. Historical source snapshots and one-time
migration evidence remain historical; they are not runtime dependencies.

Java sources and resources use conventional `src/main` package directories.
The flat source roots introduced to reduce folder counts have been removed.
Gradle configuration is conventionally formatted. Source file hashes were
checked through the directory moves in `source_layout_restoration.json`.

The unpublished Normal-byte ownership proposal has been removed. Voxy now has
an explicit SourceCell vertex attribute alongside native normals. Its source,
shader and narrow native BufferBuilder adapter are all counted.

The source cleanup was published as client artifact
`795fdb86a4495cd09e372008e60e682bc12803f58d4311bc5661c22c217505d9`.
The genuine updater transaction and actual replacement Minecraft PID are
recorded in `live_client/ownership_candidate_update_lifecycle.json`.
The live findings in `live_client/ownership_candidate_live_findings.md` retain
its visual failures. Later source edits remain unpublished until a separate
artifact and updater receipt prove otherwise. Source cleanup is not a live
correctness or performance acceptance.
