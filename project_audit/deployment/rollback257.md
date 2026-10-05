# Prepared matched rollback (safety restoration only if deployment fails)

Read `rollback257.path` and `preflight257.json` for the exact target backup,
previous executable identity and JVM limits. Control only Astolfo server_id
`voxy_testing` using the protocol-6 hello and stop_server/start_server sequence
in deploy257.py. Wait for both cwd=Voxy_Testing Java/native processes to stop.
Copy/hash-verify the saved `voxy-server*.jar` from that backup with a `.pending`
file and atomic replace; remove only the new version257 server JAR. Start
Testing and verify the saved embedded native hash and existing hard limits.

Before rollback, remove only build/libs/debug-clients/MGengine's version257
feed artifact (preserve the root download artifact and audit receipt). The prior
256 debug client remains staged in build/libs and in the updater rollback
folder on the PC. Stop only the game JVM identified by PC preflight (never
backup SSH helpers), stage/hash the matching 256 client in the exact profile,
and use its existing .voxy-updater restart helper and recorded original launch
command to reconnect. Preserve normal cache and all unrelated mods.

Restore both configuration snapshots, release the task-owned Voxy hold, and
publish a fresh `off` cache-test lease through the existing debug updater
request. Restore original pose/zoom through the typed run and close it when
possible. Do not start a new scenario. A cleanup beyond600 seconds, if necessary,
is restoration only and must be reported separately.
