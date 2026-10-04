# Original client live baseline

The reference artifact is `voxy-client-debug.jar`, SHA-256
`9b13c7510eef97903cb6883c472cb4536c1011ed5ccb5815001a6debf30cdb65`.
All captures used the real MGengine testing profile and world. Main was untouched.
No integration tests, mock clients, or custom profiling agent were used.

The comparison pose is position `(-34.36480745558629,141.6281903322415,269.4080841357899)`,
yaw `67.24432`, pitch `32.653877`. Normal FOV is70, pixel setting64, render distance2048,
GPU limit4096MiB, framebuffer2560×1601. Minecraft maxFPS is260, VSync is disabled,
and pause-on-lost-focus is false. Windows foreground activation initially failed;
the final captures explicitly verified foreground Minecraft. Focusing moved the
camera once; the testing server restored the exact pose before valid captures.

The final full-frame result is397frames over40.0821seconds aggregate across four
discontinuous stock F3+L captures: **9.9047FPS and152.9887ms p99.5**. These recordings
ran after JFR had finished. CSV `client/metrics/ticking.csv` samples the complete
Minecraft render-loop iteration in nanoseconds, including tick, rendering, swap,
and frame limiting. FPS is frame count divided by the sum of these durations;
p99.5 uses nearest rank. The built-in recorder adds profiling overhead and stops
after ten seconds; this is neither a continuous40-second trace nor an unprofiled
FPS claim. The same method, pose, focus and comparable cache/mesh lifecycle must
be used for the next implementation. At capture start the client had6297 GPU
meshes,3283 selected sections,13942 wanted sections and4004395700 GPU bytes.
Raw archives and `baseline-original-separate-frame-times.json` preserve the data.

A separate continuous60-second minimal JFR enables only allocation samples and
one-second thread allocation counters. It records **238.39MiB/s attributed to
Voxy stacks**, from361.72MiB/s total weighted allocation. Exact thread-counter
deltas over59.04seconds are66.78MiB/s for Voxy local terrain,0.031MiB/s for Voxy
reconciliation, and289.68MiB/s for the shared render thread. The shared thread
counter includes Minecraft and other mods; it is not entirely Voxy allocation.
Largest attributed sites are camera projection, projected bounds, section-key
creation, meshing, rendering, and repeated palette-name decoding. Sampling is an
estimate, while counter deltas measure whole-thread allocation.

The first allocation sample of each thread is excluded from in-window weighting.
Its weight includes allocations before recording start: OpenJDK updates the
last-sampled counter only when an allocation event commits, as shown by its
[allocation sampler](https://raw.githubusercontent.com/openjdk/jdk21u/master/src/hotspot/share/jfr/support/jfrObjectAllocationSample.cpp).
Initial uncorrected weights greatly exaggerated rates; raw values remain in the
receipts. The corrected first diagnostic's total460.95MiB/s agrees with its exact
thread-counter sum463.07MiB/s. Use `baseline-original-minimal-allocation.json` and
`baseline-original-minimal-fixed-60s.jfr` for the final allocation baseline.

For the cold zoom run, the stopped profile's entire cache was preserved by
directory rename. The unchanged reference jar launched with no terrain cache,
the same pose, FOV30 and pixel64. From the first terrain status, first published
mesh appeared after3.202seconds,200 GPU meshes after15.584seconds, and the final
314-demand/319-GPU count after20.788seconds. The run downloaded462 payloads and
3397058bytes, with zero local/network/depth/publication failures. Counting meshes
does not prove every projected pixel meets the quality threshold. The original
cache and controls were restored afterward; cold cache and status remain as
evidence. See `baseline-original-cold-summary.json` and the cold zoom screenshot.

The settled cached zoom run disabled Voxy networking for40seconds. Projection
changed to FOV30 after1.123seconds;10 additional cache loads occurred with zero
received bytes and zero downloads. Terrain remained visible in the saved settled
screenshot, with219 selected sections,314 wanted sections and8539 resident GPU
meshes.8225 meshes became inactive while4294359788 GPU bytes remained resident.
Local/network/depth failure counts did not increase in this final run. Controls
were restored. This proves observed cached rendering without Voxy communication;
the old aggregate telemetry does not prove complete projected-pixel quality.

Earlier background captures, shifted-pose captures, and a4.96FPS capture that
overlapped a heavily instrumented JFR remain retained with their limitations.
The latter logged1.52million deoptimization events and is not the ordinary-play
baseline. Initial screenshots requested in the same tick as FOV changes show
the prior projection; the final cached screenshot was requested after six seconds
and preserved before controls were restored. Some wide screenshots visibly contain
purple/black distant blocks; their precise renderer/model origin has not been
isolated. These failures and ambiguities are not replaced by a success claim.

Both independent backup SSH connections survived normal client stop/restart,
forced crash/relaunch, and the cache-isolation lifecycle. The separate
`independent_backup_proof.md` retains process, tunnel, host-pin and artifact proof.
