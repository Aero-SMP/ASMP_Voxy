# Architecture reference for the original ASMP Voxy

This records the review before implementation of this branch. Current source
ownership and build instructions are in `../README.md`; current verification
and remaining work are recorded in `../project_audit`.

Source reviewed in `../ASMP_Voxy`: the root and Rust READMEs, build wiring,
`VoxyClient`, `ClientLodClient`, `ClientSession`, `CompletedSectionCache`,
`RegionalRuntime`, `RegionalService`, `server.rs`, `main.rs`, `VoxyServer`,
`RustBackend`, and the debug updater/restart implementation.

| Area | Current ownership and data path |
| --- | --- |
| Java server mod | Starts/stops and retries one native backend; advertises its live UDP endpoint and pinned certificate through the authenticated Minecraft connection. |
| Rust backend | Reads saved Anvil terrain; tracks source fingerprints; incrementally builds regional LOD files; atomically publishes generations; serves section batches through Quinn QUIC. |
| Java client | Discovers the endpoint; maintains demand for spatial sections; checks regional disk cache; downloads, decodes, meshes, uploads, and activates bounded work. |
| Renderer | GPU hierarchy selects detail, frustum and HZB visibility; coarse coverage stays active until finer geometry passes publication fences. |
| Debug updates | Polls SSH/SCP, swaps a jar, restarts Java through a separate helper, and uploads diagnostics. This lifecycle was exercised on MGengine's Windows Modrinth profile. |

Saved world data remains authoritative. The old backend does not generate chunks.
Derived data and persistent certificate/catalog identity are distinct from world
files and must not be erased as part of this branch by default.

The broadest coordination surface is `ClientSession`: lifecycle authority, demand,
network/cache admission, worker stages, and renderer publication share its session
owner. The backend separates source refresh/publication from request serving.
This review identified lifecycle coordination and repeated source work as areas
to simplify. It describes the reference implementation, not the current branch.

Testing baseline: legacy debug client 251, server 248, Minecraft port 25587,
Voxy UDP 25787. Only the testing native process is placed under the external
1 GB memory cap. Main was inspected read-only throughout this task.
