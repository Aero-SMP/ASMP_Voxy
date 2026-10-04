# First running consolidated client: rendering proof and GPU-pressure failure

The corrected fixed client jar had SHA-256 `ce84688ade56f2f32938996f16e99db162020afdd665c7592ff35af4b54cbf20`; PID 31924 started at 2026-10-04 00:49:03.8286063 UTC. Fresh updater readiness names that PID and exact content hash. The actual OpenGL renderer reports AMD Radeon RX 7700S, ATI memory reporting supported, and NVX unsupported.

At 00:51:07 the real client was connected to the preserved world identity at the exact baseline position, yaw, pitch and FOV70. It had 3,386 meshes/uploads, 3,375 GPU-ready sections and 2,613 selected sections, with zero publication, local or network failures. Warm-cache loads supplied most terrain; only13 payloads/444,365 bytes had arrived from the server. This proves actual shared-context buffer publication and drawing, beyond artifact installation or compilation.

`candidate-first-corrected.png`, captured at 00:52:13, shows textured terrain, water and modded structures. Compared with `baseline-original-fixed-focused.png`, distant terrain is finer and the previous conspicuous magenta/black strip beside the right hillside railway is absent. The large exposed cave at the near right edge exists in both images. One static screenshot does not establish absence of flicker, gaps elsewhere, or all water-boundary issues.

At 00:54:25 the client held 6,967,010,196 GPU geometry bytes and incorrectly reported 4,397,315,121,152 free bytes. The driver returns a signed ATI integer: the reported value corresponds exactly to negative714,248 KiB reinterpreted unsigned. This defeated pressure-based admission. At 00:58:00 geometry had grown to 10,715,402,712 bytes, with 22,658 uploads, 18,811 selected sections, 39,605 wanted sections, zero evictions and a last draw duration of201.2534ms. These are failure diagnostics, not a successful performance comparison.

The identified testing Minecraft process was closed gracefully and was absent by 00:58:57.6227705 UTC to avoid continued driver paging or a hang. No arbitrary GPU ceiling was added. Original and converted caches and all candidate jars remain preserved. Both independent backup SSH helpers survived with unchanged identities. No100-peer fixture or pressure run was started.

Raw evidence includes `candidate_corrected_live_status_02.json`, `candidate_corrected_live_status_03.json`, `candidate_pre_gpu_fix_status.json`, `candidate_corrected_gpu_log.json`, screenshot/hash receipts, `candidate_gpu_guarded_stop.json` and the independent backup receipts.
