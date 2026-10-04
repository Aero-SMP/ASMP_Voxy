# Loaded ownership-correction client: live findings

The genuine automatic update to client SHA-256 `795fdb86a4495cd09e372008e60e682bc12803f58d4311bc5661c22c217505d9` succeeded. The original client handle (PID 9484, start 04:17:29.4308081 UTC) exited; official Modrinth started PID 23820 at 06:16:00.1992932 UTC. Its `.starting`, `.ready`, installed hash and fresh connected/rendering status agreed. There was no manual jar installation, restart or launcher invocation for this update. Both original independent SSH helper handles survived every observation. Full evidence is in `ownership_candidate_update_lifecycle.json` and the 240-row `ownership-candidate-update-observations.jsonl`.

Visual correctness **failed** on this actual loaded build. At the user's preserved pose, the lower-left landscape has enormous vertical water curtains and exposed rectangular terrain/cave cross-sections. Distant hill edges also contain dark areas. The native depth-read error counter is zero; this does not establish semantic visibility or terrain correctness.

| Capture | UTC | Clipping | Image |
| --- | --- | --- | --- |
| Initial rendering | 06:21:57.8589248 | true | `ownership-candidate-normal.png` |
| Invalid isolation attempt | 06:30:31.8176309 | false requested | `ownership-candidate-no-clip.png` |
| Invalid isolation attempt | 06:34:44.5707719 | rendering false requested | `ownership-candidate-native-only.png` |

All three captures use PID 23820 and the same loaded hash, position `[-39.75219210642211, 141.6281903322415, 259.73581404846107]`, yaw `-122.88401`, pitch `15.208784`, FOV 70 and pixel setting 64. The latter two attempts do **not** establish the effect of disabling clipping or rendering. Read-only inspection of the exact frozen 795 client found that `control()` calls `Screenshot.grab` before `tick()` assigns the renderer's `enabled` and `clipping` fields. The operator changed the flag and requested the screenshot in one write, so the existing framebuffer was captured before the changed flag affected a rendered frame. The earlier assertion that these images isolate clipping was withdrawn. Exact frozen bytecode is preserved in `ownership_candidate_control_bytecode.txt`; a corrected diagnostic must change the flag first, allow rendered frames, and request a screenshot through a separate subsequent write.

Cache/mesh preparation continued between captures, and ambient weather/light changed. None is a frame-performance or identical-lifecycle comparison. The operator restored the exact original controls in `finally`. The 06:35:22 status confirms `network=true`, `render=true`, `clip=true`, FOV 70, pixels 64, the same pose and the same client identity. Both original backup helper start identities and tunnel PIDs remained unchanged at 06:35:26.

The corrected counted screenshot operator applies the setting without a screenshot action, waits, checks that the client status timestamp progressed after setting application, then requests a screenshot in a separate later write. Both repetitions completed successfully:

| Corrected capture | Setting applied UTC | Fresh status UTC | Screenshot UTC | Image |
| --- | --- | --- | --- | --- |
| Rendering disabled | 06:39:49.8702686 | 06:39:51.996 | 06:39:54.5176380 | `ownership-candidate-native-only-settled.png` |
| Clipping disabled | 06:40:29.7732745 | 06:40:32.504 | 06:40:35.1431729 | `ownership-candidate-no-clip-settled.png` |

The corrected rendering-disabled image removes the distant LOD landscape and leaves only a small finite native terrain/water island at the bottom, proving the renderer toggle actually took effect. The corrected clipping-disabled image restores the LOD scene, but the giant lower-left curtains, rectangular cutaway sections and dark distant hill edges remain. This establishes that disabling native clipping alone does not cure this view. It does not identify the mesh/cache/native-boundary cause. The native-only image also contains finite native island cut edges; those should not be confused with a diagnosis of every exposed face in the combined scene.

Both corrected receipts contain the fresh settled status, exact 795 hash, PID/start identity and unchanged user pose. The 06:41:16 post-status verifies all original controls restored; the 06:41:17 backup receipt verifies both original independent helper and tunnel identities. No frame or allocation profiler ran during these diagnostics. The first two invalid attempts remain preserved and excluded from causal conclusions.

That restored status reports 37,446 GPU-ready nodes, 27,717 selected nodes, 6,190,480,560 geometry bytes, zero evictions, zero publication failures, zero native depth failures and zero network failures. Two local failures contain `CancellationException: Obsolete terrain preparation`; the parent identified these as intentional invalid-owner cancellation incorrectly classified by the broad local error handler. The receipt retains the actual counter rather than claiming zero errors.

## Quiet current-scene pilot profiling

Four foreground stock F3+L captures completed before a separate 60-second allocation recording. The actual 795 client, PID/start identity, user pose, FOV 70, pixels 64 and controls stayed unchanged. No pressure workload ran during these captures. Weather was left as it was. This scene differs from the frozen original's reference pose/weather, so the pilot does not establish a comparative improvement against that baseline.

| Measurement | Result | Evidence |
| --- | --- | --- |
| Four discontinuous foreground frame captures | 603 frames / 40.130217812 s; **15.0261 FPS**; **93.6381 ms p99.5** | `ownership_candidate_focused_frame_receipt.json`, `ownership-candidate-focused-frame-times.json`, four ZIPs under `ownership-candidate-focused-frames/` |
| Standalone 60-second minimal allocation recording, full 64-frame exported stacks | **104.588264 MB/s Voxy-attributed estimate** (99.743141 MiB/s), **FAIL** for the 50 MB/s target | `ownership_candidate_allocation_receipt.json`, `ownership-candidate-allocation-depth-64.json` |
| Exact whole Render-thread allocation counter | 106.451510 MiB/s over 58.081929 seconds between first/last counter samples | Same allocation result; this includes all render-thread callers |

The allocation recording started at 06:55:58 UTC and contains 17,201 `ObjectAllocationSample` events plus 6,556 thread counters. Execution-sample and deoptimization events are zero. Raw JFR SHA-256 is `fdbbbc6345bd915e92e91c7fc2a2b58faa665cb5319083934e6b1e68b1830521`. The minimal configuration enables allocation samples with stack traces and one-second thread allocation counters; it matches the baseline recording settings. Voxy attribution walks exported stack frames; weighted sampling is an estimate, while thread-counter differences are exact for the whole sampled thread. The first sample per thread was omitted because its weight includes pre-recording accumulated allocation.

Exporting only five stack frames, as earlier baseline JSON did, gives 23.960605 MB/s for the same recording and misses deeper Voxy callers. That shallow result is retained in `ownership-candidate-allocation-depth-5.json` solely as methodological evidence of under-attribution. **It is not a below-50 pass.** The full64 result above is authoritative for this pilot. A fair future comparison must use equivalent exported stack depth as well as matched live conditions.

The dominant attributed sites are `renderEntities` (54.8947 MB/s), `draw` (21.2299 MB/s) and `render` (16.7703 MB/s). Important sampled classes include linked-map entry iterators, strings, byte arrays, boxed floats, FancyMenu rotation/translation state and Mixin callback objects. The source review will use full representative stacks to distinguish expensive nested native/mod calls from Voxy's own coordination work.

An initial four-capture attempt did not obtain foreground focus; its 11.9184 FPS / 119.5366 ms p99.5 result is preserved under `ownership-candidate-unfocused-frames/` and `ownership-candidate-unfocused-frame-times.json`, and excluded from comparison. The counted focus operator now verifies the exact Minecraft window before and between captures, attaches/detaches its input-thread links in `finally`, and fails before capturing if foreground verification fails. The allocation operator separately verified foreground at startup; post-recording status still reports foreground true.

The post-recording status at 06:59:04 verifies the same hash/PID/start, pose and original controls. Both original independent SSH helper/tunnel identities remain unchanged. All profiling/export/analysis operations completed before starting the replacement phase-instrumented pressure rig; there is no simultaneous diagnostic workload in these pilot measurements.

The previous pressure startup was stopped through its exact identity-verified operator only, after the parent authorized replacement of its diagnostically blind driver. Session 69081 terminated with exit 130; operator 2609818, peer 2609821 and writer 2609819 were confirmed absent. Peak simultaneous world-ACK-live clients was 74. No 100-client barrier or mutation clock began, and the saved-change log has zero bytes. This is a failed startup result, not a 100-client/300-saved-changes-per-second pass. The failure, preceding counters, guarded signal and cleanup proof are preserved under `../load_results/frontier-full-startup-pressure-01/` in `authorized-stop.json` and `startup-failure.json`.

Testing Java PID 2515008 and native PID 2516426 retained their original start identities. The native loaded hash remains `7457e3e48fbc4360e4f2f3337851ac247199c5ee94552aae51d3dcd75dc571d7`; server hash remains `8678010a862c2754c918602cae2f79887e746337576c25a8986bcf51214efa0a`. Native `memory.max` remains 999,997,440 bytes, `memory.swap.max` is zero and OOM kills remain zero. Neither Java, native, laptop client nor either backup helper was stopped during pressure cleanup. No replacement pressure workload has started.
