# Current client comparison before 100-client pressure

The actual 9c18 client automatically replaced 94f, launched through the official Modrinth instance URI and reached the testing world as PID820. Both independent SSH helpers retained their original identities throughout. Automatic update proof is in `automatic_update_proof.md`.

This candidate reduces allocation but currently regresses ordinary frame performance. It is not accepted as a performance improvement.

| Measurement | Original 9b13 | Current 9c18 |
|---|---:|---:|
| Stock F3+L sampled FPS | 9.9047 | 7.1192 |
| Full-loop p99.5 milliseconds | 152.9887 | 187.3520 |
| Voxy stack-attributed allocation MiB/s | 238.3921 | 71.8179 |
| All sampled allocation MiB/s | 361.7191 | 222.4815 |

Frame measurements use four discontinuous stock ten-second F3+L captures, JFR inactive, foreground confirmed, identical pose/FOV70/pixel64/distance2048, no virtual-client pressure. The stock profiler adds overhead equally to both recordings; these are sampled full-loop durations, not a claim of uninterrupted 40-second capture or uninstrumented performance. Current FPS is 28.1% lower and p99.5 22.5% higher. The current warm cache was converted from the preserved original data. Each implementation has its actual background cache/mesh lifecycle; equal node counts are not asserted. The current client is still recursively preparing much more fine terrain, so the candidate is measured with its actual ongoing work rather than a fabricated settled state.

Minimal allocation JFR ran separately for60seconds from01:40:38UTC, using only ObjectAllocationSample and one-second ThreadAllocationStatistics. Each thread's first allocation sample is omitted because it carries bytes accumulated before this recording. Weighted stack attribution falls69.9%, but71.82MiB/s remains above the requested50MiB/s target. Exact whole-thread counters over59.044seconds show Voxy cache58.863MiB/s, connection7.420MiB/s and shared Render thread153.434MiB/s. The shared render-thread total cannot all be attributed to Voxy. The largest sampled Voxy sites are mesh2.110GB and Key.child1.618GB during60seconds.

At01:44:06UTC, fresh status reported wanted39605, selected21188, uploads33223, GPU geometry6191668404bytes, signed available GPU bytes0, all local/network/publication failures0 and no lastError. Geometry had stabilized under driver free-space feedback; a geometry byte counter is not total VRAM usage. Depth queries reached35,119,528 while depthRejected remained0. Source inspection suggests invalidation by the changing world-time epoch prevents asynchronous depth results from becoming useful; this is an inference, not an independently isolated experiment. Unconditional recursive prepared traversal and repeated Key construction are additional concrete source findings.

`candidate-auto-updated.png` shows normal textured terrain, water and modded structures at the same baseline pose. The old magenta/black strip is absent; the near cave/void appears in both old and current images. A single still image does not prove absence of flicker or all visibility/zoom cases.

Raw evidence: candidate-dummy-frame-{1,2,3,4}.zip, candidate-dummy-frame-times.json, candidate-dummy-minimal-60s.jfr, candidate-dummy-minimal-jfr-events.json.gz, candidate-dummy-minimal-allocation.json, candidate_dummy_pressure_setup_status.json.

A later reversible cache-only zoom observation fails the visual quality gate. With the exact original pose and FOV30/pixel64, `candidate-dummy-cached-zoom.png` shows enormous coarse grass/stone blocks over the nearby hillside and a large sky-colored gap, whereas `baseline-original-settled-cached-zoom.png` shows fine continuous hillside. This failure was preserved rather than accepted as faster refinement. Projection was first observed after536ms, but geometry quality is visibly worse. By the end of40seconds, selected19, wanted1134 and GPUready1134; available driver memory feedback caused GPU geometry to drop from6.191GB to1.060GB and evictions to rise158902→184728. The pre-zoom193189uploads/158902evictions demonstrate repeated discard/reload cycles, not successful VRAM reuse. During the first transition second,19inflight payloads/20406bytes arrived before network closure; no further downloads arrived during the remaining observation. The deliberate connection closure incremented networkFailures1 with StreamClosedException; this is an observability issue, not a claim of an unprompted network fault. Local/publication failures remained0. Controls were restored, position/rotation remained exact, and01:54:51status confirms FOV70/PID820 alive. Evidence `candidate_dummy_cached_zoom.json`, `candidate-dummy-cached-zoom-summary.json`, `candidate_dummy_post_zoom_status.json`.
