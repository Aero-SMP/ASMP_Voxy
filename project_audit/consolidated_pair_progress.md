# Consolidated implementation and first paired deployment

This is progress evidence, not an acceptance or completion claim.

The single Gradle project builds the client and server from eight production
Java/Rust/shader files. The first coordinated pair is frozen in
`.verification/paired-consolidated`: client SHA-256
`c7231162b970cb25f58fdc754e6aa13af6f5f28f64e9dd713ecaa2949c1c7d28`, server
`9f402e02d15909bba7abc265966432c621e220606172cb5dc5eca4c90b542275`, and native
`7a30d80c933e5f66b164b5259922f869cf9b0fc0e0dc8afb6c36954bdf04162c`.
Compilation and wrong-side jar inspection passed; no test suites were run.

Validated downloads become usable before optional cache persistence. An optional
write failure retains usable data and does not restart QUIC or request the same
payload again. The current format is self-contained compressed NBT with a full
34-cubed halo, canonical states and biomes, light, and saved block entities. The
publisher reads Anvil files without opening writable region storage. Native warm
responses pin existing records while source checks run independently. BBR uses
the library's adaptive defaults; no new application quotas were added.

The one-time laptop conversion completed with 151,806 records and zero reported
errors. Original cache and jar are preserved. The operator converter does not
ship in the mod. Missing old neighbors become air and old records contain no
block-entity NBT; new source publication supplies that data. Buffered NBT output
reduces calls into the compressor. The stopped unbuffered partial run and both
logs remain available rather than being counted as a successful full conversion.
Both independent SSH helpers survived the entire stopped-client interval.

Testing was stopped normally, old process absence checked, and its server jar
replaced manually because the old updater required the removed helper class.
No compatibility bridge was introduced. Both exact frozen feeds were published
before the new client launch. Server Java PID 2113055 and native PID 2113819
started with unchanged identity/certificate. The native cgroup retained
`memory.max=999997440`, `memory.swap.max=0`, and zero OOM kills. Live JVM flags
confirmed 1 GiB initial and 4 GiB maximum heap. Shared user-cgroup memory is not
attributed to the Java process. See `paired_manual_server_install.json` and
`paired_server_running.json` for hashes and process evidence.

Further source changes are unpublished: canonical BlockState palette ownership,
stationary demand reuse, primitive projected sizes, conservative opaque boundary
faces, pressure-driven GPU admission, and identity-aware updater waiting. The
initial pair stays frozen so its behavior can be compared before the legitimate
remote update to those changes. Mixed-LOD water seams, shader-pack behavior, and
the shared GPU upload path still need live proof.

The last consolidation inventory was 2,182/4,656 owned lines, 12/40 source files,
and 22/77 folders. Files and folders met their 30% gates; lines did not. Subsequent
feature and workload changes require recounting. No minification or weakened
baseline is used to claim a pass.

The first pressure draft's 33 nodes per client was too small compared with the
real cold run's hundreds. The counted live driver is being expanded to full
512-block saved regions and the historical driver's 597-node demand/order, with
dirty-sector persistence rather than repeated whole-region rewrites. No 100-client
run has yet established throughput, coverage, or allocation acceptance. Actual
live connections must precede pressure timing, and only saved changes completed
during that interval count toward the required 300 per second.

Main remains read-only. Full comparative frame, allocation, cache, refinement,
correctness, remote-update, and pressure gates remain open.
