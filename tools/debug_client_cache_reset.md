# Debug-client cold-cache testing

Debug client 0.2.215+ polls one optional request file alongside its normal authenticated
SSH update check. No Minecraft server command, server update, or server restart is needed.

For an explicitly authorized player:

```sh
python3 tools/request_debug_client_cache_reset.py MGengine
```

This creates/replaces `build/libs/debug-client-reset/MGengine.request` with a fresh UUID.
Only a debug client logged in with that Minecraft name reads the request. The request
is permanent until replaced/removed, but a completed UUID is remembered outside the
cache, so ordinary repeated polling/restarts do not repeat the deletion. Multiple
simultaneous instances using the same Minecraft name are not distinguished.

The client starts its existing restart helper and exits. Only after the old JVM is
confirmed dead does the helper permanently delete `<game directory>/.voxy`, record
completion in `.voxy-updater/cache-reset-completed`, and relaunch. Missing caches count
as successfully empty. Mods, options, screenshots, and `.voxy-updater` are preserved.
Linked cache roots are rejected; traversal does not follow links outside the cache.

The existing restart helper first allows graceful exit, then may force-stop the old
client if it fails to exit within its existing deadline. This never stops a server.
If deletion fails, startup is aborted and the failure is logged; partial deletion is
possible and cannot be recovered. Do not use this mechanism without authorization to
discard that client's cached data.

Verify `cache-reset-complete request=<UUID> removedBytes=<count>` in the uploaded
`restart.log`, followed by the new client's version and pipeline/cache-start evidence.
Creating the request is not proof that the client reset or restarted.

Current uploads: `/home/aerosmp/Desktop/Main/logs/client-upload`.
The client reconnects through Velocity and may land in the lobby. The authorized
Velocity-console command `send MGengine main` routes it back to the test destination.
Do not restart or modify Main or Velocity to perform this test.
