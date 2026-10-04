# Genuine content-hash client update

After the signed-GPU fix, the running real client was PID28256 with installed/loaded SHA-256 `94f94bc00ebb0aa60075c42059d21d7f814d485dd05e08788f42e27679bbc85e`. Root published the DUMMY block-entity correction with SHA-256 `9c18edf7e2bbefa446fcee96d4066826067e4cdfee1962f3212c06f0b5e6dba1`.

The current client prepared that update at 2026-10-04 01:17:22.4377047 UTC. Its independent helper waited for the old process exit, installed the verified fixed jar and used the stored official Modrinth instance URI. No operator manually replaced the jar, requested a launcher start, or replayed authentication/JVM arguments during this transition. The helper log records `Client readiness820 9c18edf7e2bbefa446fcee96d4066826067e4cdfee1962f3212c06f0b5e6dba1`.

At 01:18:27.9421325 UTC the new Minecraft window was PID820, installed SHA and fresh readiness matched, it had rejoined the preserved world/dimension at the exact baseline pose, and cached terrain was rendering. By 01:19:51 it had7,258 uploads,7,251 GPU-ready sections,5,745 selected sections and zero publication/local/network failures. It used7,258 cached sections and downloaded only19 payloads. The signed GPU hint was2,931,286,016 bytes, not a wrapped negative value. Old PID28256 was absent.

Both independent SSH helpers remained alive and the primary route independently executed after the restart, preserving their original helper/tunnel identities. The secondary route collected30 observations over90 seconds. These observations began after the rapid initial transition, so they demonstrate the new stable process; preparation, old-process absence and helper readiness provide the transition evidence.

The updater also records intermittent `Command failed:ssh.exe` errors during its maintained debug-control connection checks. One successful automatic update is proven; those intermittent remote-control failures remain a reliability issue for further investigation. They did not remove either independent backup route.

Raw receipts: `candidate_dummy_auto_update_logs.json`, `candidate_dummy_auto_update_status_01.json`, `candidate_dummy_auto_update_observations.json`, `candidate_dummy_auto_update_monitor.json`, and `candidate_dummy_auto_update_primary_backup.json`.
