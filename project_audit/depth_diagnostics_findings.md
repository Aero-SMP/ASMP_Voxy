# Actual depth diagnostic findings

The actual loaded client is `0d8a024261a49fa38cdefe7d36112aaf0d25f54c64c78057a40c0edd029123bd`, Minecraft PID 15608/start 2026-10-04T08:18:24.3792737Z. The update observer captured the previous 32092 process alive and then its exit, the new ready marker, and both original independent backup handles surviving. Fresh status contains the new counters.

`live_client/depth-diagnostics-current-status.json` is a warming snapshot, not a settled performance result. It has 3,207,581 discarded query tickets, all with scene mismatch; camera position, full matrix, target, bounds revision and shader mismatch counters are 0. Sprite alpha accounts for 24,353 scene epochs, while native publication is 0. The GPU did actually report 622,136 zero-sample queries; a zero current occluded gauge did not mean all query boxes were visible.

The source aggregates animated sprites from all geometry layers and sends that entire array to alpha tracking whenever any cutout layer exists. This includes translucent fluid sprites that do not contribute opaque depth. The next candidate will retain activation of all visible sprites while tracking depth invalidation only for sprites emitted through actual opaque cutout layers. All-mip alpha comparisons remain necessary for those occluders. This source correction is not yet live proof that static query reuse works.

The prior 9390 client has separate real full-stack allocation/frame evidence. Its 38.005874 MB/s Voxy-attributed warm allocation estimate meets the 50 MB/s target for that capture. Its 16.084342 FPS and 81.9889 ms p99.5 are pilot results; prior 795 weather and pitch differ. Neither result establishes performance for this diagnostics client or the upcoming source changes.
