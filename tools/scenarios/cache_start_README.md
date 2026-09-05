# Cache-first live verification (matching debug client and server required)

Run `cache_start_populate.json` using the existing `run_live_client_test.py`
runner. It records visible online terrain, then holds only Voxy transport.
Wait for `VOXY_CACHE_START transportHeld=true persisted=true` in the client log.
Record the camera pose and retain the run's counters and screenshots.

Exit Minecraft completely and restart the same profile, world and camera pose.
Do not delete `.voxy` data. The debug marker
`.voxy/debug-hold-regional-transport` prevents even endpoint discovery on restart;
it does not disconnect Minecraft. Run `cache_start_verify.json`. Its local
checkpoint requires actual GPU draws and cache hits with connection epoch zero.
Only afterward does it resume Voxy and observe background validation.

This deliberately uses two runs separated by a **whole-game restart**. Running
both scenarios in the same process is not cache-start acceptance. The scenarios
do not automate launcher process control or change the world.

Use the same fixed camera route for both phases; repeat with a known finer-detail
camera pose cached during population. Compare `firstLocalNanos`,
`firstHelloNanos`, `localActivations`, `validatedViews`, `replacements`,
`metadataNetworkBytes`, existing section network/cache counters, heap/GPU metrics
and screenshots in `voxy-client-debug.log` and harness traces. First activation
is not TTFD. For an unchanged stationary view, validation should not retire and
remesh already installed sections merely because the region generation changed.

Then repeat against separately prepared block-change, region-deletion,
pending-rebuild and new-world-identity fixtures, and slow-network/memory-pressure
conditions. Fixture creation/destructive changes need separate authorization.
The scripts do not claim those cases passed merely because a handshake succeeded.

To recover from an interrupted test, run a harness `resume_quic` step. If no
Minecraft connection is available, remove only the debug marker while the game
is stopped, then restart. Normal artifacts neither read this marker nor register
these commands. Existing F2/periodic screenshot upload behavior is unchanged.
