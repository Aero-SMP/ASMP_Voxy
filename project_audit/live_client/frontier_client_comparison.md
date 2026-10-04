# Frontier client live comparison

The loaded client `71610179cc45d7325e7f0bac9cd2bc64ee0dfe1f0826b49de3ce4c06d2390b4c` launched through the official Modrinth instance URI as PID28964 and joined Voxy Testing. Its installed hash, readiness file, fresh status and actual screenshots agree. Both independent SSH helpers remained alive. The running server is `8678010a862c2754c918602cae2f79887e746337576c25a8986bcf51214efa0a`, native `7457e3e48fbc4360e4f2f3337851ac247199c5ee94552aae51d3dcd75dc571d7`; no virtual pressure ran during these captures.

| Measurement | Original 9b13 | Failed intermediate 9c18 | Frontier 716101 |
|---|---:|---:|---:|
| Stock F3+L sampled FPS | 9.9047 | 7.1192 | 15.3210 |
| Full-loop p99.5 milliseconds | 152.9887 | 187.3520 | 97.5413 |
| Voxy stack-attributed allocation MiB/s | 238.3921 | 71.8179 | 102.1903 |
| All sampled allocation MiB/s | 361.7191 | 222.4815 | 246.3176 |

Frame recordings use the same original position/rotation, FOV70, pixel64, distance2048, foreground confirmation and JFR inactive. The frontier result includes 614 full render-loop samples totaling40.0758 seconds from four discontinuous stock ten-second F3+L archives. The stock profiler adds overhead; this is a comparable sampled measurement, not an uninterrupted or uninstrumented40-second recording. Relative to the original, FPS rises54.7% and p99.5 falls36.2%. Each implementation performs its actual cache/mesh lifecycle; equal resident node counts are not asserted.

Minimal allocation JFR ran separately for60 seconds from02:47:03UTC, using only ObjectAllocationSample and one-second ThreadAllocationStatistics. Each thread's first allocation sample is omitted because it carries bytes accumulated before this recording. Weighted Voxy attribution is57.1% lower than the original but remains above the requested50MB/s target and higher than the failed intermediate candidate. Exact whole-thread counters over59.0559 seconds show Voxy cache114.306MiB/s, connection0.0145MiB/s and the shared Render thread127.143MiB/s. The shared Render thread total is not all attributable to Voxy. Meshing dominates Voxy samples at4.638GB/60 seconds; Key.child and Key.parent no longer appear among the leading allocation sites.

`frontier-normal.png` shows textured terrain, water and modded structures at the original scene. The former magenta fallback strip and giant coarse blocks are absent. At02:47:57UTC, fresh normal status reported selected24149, wanted39605, uploads33510, evictions0, GPU geometry6286758732bytes and all local/network/publication failures0. This geometry counter is not total device VRAM. Depth rejection remains0; normal screenshot correctness does not prove all visibility cases.

The reversible40-second cache-only FOV30/pixel64 observation received zero payloads and zero network bytes. Fine hillside now appears instead of the failed intermediate candidate's giant grass/stone blocks and sky gap. However, `frontier-cached-zoom.png` has black vertical terrain slits absent from `baseline-original-settled-cached-zoom.png`; visual correctness is not accepted as complete. First projection change was observed after1582ms with500ms polling, which does not prove next-frame refinement. Selected882/wanted1134 at the end shows useful cache-only coverage, but cannot by itself establish complete quality.

VRAM reuse also remains unproven. Around2094ms after the zoom request, evictions jumped0→29471 and GPU-ready records30006→538. Geometry fell6.287GB→1.060GB by the end, while uploads increased33921→34723. Releasing obsolete geometry can be useful, but this large discard burst followed by restoration/reload does not satisfy the requested reuse improvement. There were no local/network/publication failures during the observation. Controls restored FOV70, the exact original pose remained unchanged and PID28964 plus both backups were alive afterward.

Raw evidence: `frontier-frame-{1,2,3,4}.zip`, `frontier-frame-times.json`, `frontier_stock_frame_profiles.json`, `frontier-minimal-60s.jfr`, `frontier-minimal-jfr-events.json.gz`, `frontier-minimal-allocation.json`, `frontier_cached_zoom.json`, `frontier-cached-zoom-summary.json`, `frontier_post_zoom_status.json`, and both screenshots. The compressed JFR event JSON is checked against its original streaming SHA before the uncompressed duplicate is removed.
