# Testing server update preflight

This is a read-only deployment review. The candidate has not been published or installed. Pressure session 85491 remains running; its 100-client gate is unmet and its saved-change phase has not started.

## Exact scope and preserved state

- Testing Java is PID 2515008/start 30385758; its Astolfo supervisor is PID 431060/start 25836677. Native PID 2516426/start 30387088 still maps SHA-256 `7457e3e48fbc4360e4f2f3337851ac247199c5ee94552aae51d3dcd75dc571d7`.
- Installed Testing jar and server feed remain `8678010a862c2754c918602cae2f79887e746337576c25a8986bcf51214efa0a`. A separate regular rollback jar with that exact hash exists under `.verification/alpha-envelope-lighting-candidate/prior/server/`.
- Candidate jar is `27cceb0810372a58747172c022ac2c896334037cd841eccf319e14e00f3e8454`; its embedded native is `e529e457545bdd7d2d536218cabe87f3d657ad318c0c8989a7d35f1fc51f59d6`. Its update side is server and its mod ID is voxy_server.
- Native memory.max is 999997440 bytes, memory.swap.max is 0, and OOM kills remain 0. The unchanged Java process preserves the previously verified 1–4 GiB heap. No launch arguments were inspected.
- Public certificate SHA-256 is `601d30b3f19a1eb8a10a4d124a9c3e2f8a909dfb6c89abe1aa9910f0a97a4598`; world.id SHA-256 is `929c73dba664f839f71af859ff0c0276a184901da90be8cce51414e13455d1d4`. Neither file is replaced by this update.
- The existing external guard admits the fixed Testing world/data paths and a hash-named voxy-server executable, so the candidate requires no guard or limit change.

## Main feed isolation

Main Java remains PID 2803082/start 30918421. Its installed `voxy-server-0.2.213-beta+1.21.1-neoforge.jar` hashes to `c8b3f4e0fe027138385364ea7d4663154ca3c1a734fb6250666e8d520c30330a`. Inspection of all its class/config/manifest entries found no updater classes, Branch release path, latest.properties consumer, Voxy-Update-Side marker, or fixed debug artifact. The proposed Branch server publication therefore cannot update Main through its installed Voxy mod. Main was only inspected; no Main file or process was changed.

## Automatic restart mechanism

The exact installed Testing updater polls the local Branch server feed, verifies SHA-256, mod ID and side, backs up and atomically replaces its fixed installed jar, then requests MinecraftServer.halt(false). It does not launch the new server itself.

Testing's `.astolfo-desired-online` marker exists. Astolfo's supervisor restarts an absent desired-online process using StartMode::DesiredOnline, with configured backoff 3/7/15/30/60 seconds. Its mapped daemon hash matches the inspected installed daemon. Static inspection establishes the mechanism; the candidate's successful automatic restart still requires actual lifecycle evidence.

## Concrete later deployment and proof

1. Preserve the existing pressure run as an unmet-startup result and terminate only its exact operator/peer/writer after the parent authorizes the diagnostic transition. Verify all three exited; do not stop Minecraft as a substitute.
2. Install the staged pressure dimension descriptor beside the saved-only fixture after that transition. Its exact SHA-256 is `1fb71c3e4b5178b4baa9106e05f36c03839b0d8f9902a13488ae6f028a077530`. The runtime sidecar is currently absent. Preserve the existing 100 MCA files and their fixture hashes.
3. Publish only the verified candidate server artifact and its file/SHA manifest to the Branch server feed. Keep the client feed coordinated separately. Do not invoke Astolfo stop: that would clear the desired-online intent and prevent a genuine updater-driven restart proof.
4. Observe the installed updater's verification/install event, old Java exit, old native/guard exit, then Astolfo's new Java start. Confirm the old PID/start pairs are absent before attributing the new ones.
5. Verify the installed jar hash, new native mapped hash, VOXY_READY, unchanged MC/QUIC ports, unchanged world ID/certificate, native effective ceiling/swap/OOM counters and actual Java heap flags. Verify Main's original PID/start and installed jar hash remain unchanged.
6. Only after successful server readiness coordinate the independent-TLS pressure driver and a new run. Preserve the preceding unmet 100/300 result; do not relabel it a pass.

If startup fails, restore the old server feed first to prevent repeated installation, then use the preserved exact 867 jar for a scoped Testing rollback under the parent's control. Keep the world, cache, identity, certificate, external guard and Main untouched. Record both the failed automatic lifecycle and the actual rollback result.

Machine-readable evidence: `project_audit/server_automatic_update_preflight.json`.
