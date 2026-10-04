Improve the Voxy development branch autonomously while I’m away. Use the original rewrite as a frozen comparison baseline. I expect to be away around eight hours, but that is not an exact deadline. Complete the entire plan and demonstrate the improvements before marking the goal complete.

First make remote access and client recovery more reliable. Establish and keep two independent backup SSH connections to my laptop alive through Minecraft crashes, restarts, and client updates. Make remote automatic updating more reliable and demonstrate an automatic update, restart, and successful return to rendering with the published artifact loaded.

Make the implementation substantially smaller. Record the baseline’s lines of code, source files, and folders, including empty folders. Using identical counting rules, each final count must be at most 30% of its baseline count. Achieve this through simpler architecture and removal of unnecessary code and folders, without minification, unreadable compression, moving code outside the count, or hiding custom code in dependencies.

Compared with the original rewrite, deliver:

- Lower Voxy-attributed allocation, ideally below 50 MB/s under the measured workloads.
- Higher FPS, lower p99.5 frame time, and fewer visible stutters.
- Faster cache-first loading and less dependence on the server. Use locally cached terrain immediately, independently of server discovery, handshakes, metadata, or freshness confirmation. Request terrain payloads only when the required data or quality is missing locally.
- Faster delivery of more high-quality visible LODs, with fewer gaps. Prioritize complete visible coverage over freshness, preserve existing terrain during refinement, and refresh stale terrain slowly in the background.
- Faster zoom refinement with less perceptible delay. Use frustum and depth visibility together with zoom and projected pixel size to select detail. Aim to display cached finer detail in the next rendered frame; request missing detail while retaining existing coverage.
- Faster streaming, cache retrieval, decoding, meshing, GPU upload, and rendering, with less duplicate work and copying.
- Better VRAM reuse and cleanup, with fewer unnecessary evictions, fewer discard/reload cycles, and less obsolete geometry retained.
- Faster and more robust meshing. Reuse useful techniques from the original mesher and improve handling of custom modded blocks, textures, lighting, transparency, water, and section boundaries.
- Better visual correctness: fewer holes, flickers, invisible or missing faces, incorrect water edges, missing textures, black-and-purple fallback blocks, and visible seams between normal chunks and Voxy terrain. Introduce no new visual regressions.
- Simpler ownership and processing, fewer source files and folders, less repeated work, and better time and memory complexity. Approach O(1) where appropriate through direct lookup, reuse, and incremental processing.

Preserve these hard constraints:

- No quadratic algorithms in either time or memory complexity.
- No backward compatibility, legacy implementations, stale code, format versioning, or version negotiation. Use fixed artifact names and content hashes. Keep mandatory framework metadata fixed and unused for compatibility or update decisions.
- No new arbitrary memory or CPU budgets, quotas, per-frame work caps, section-rate limits, or resource reservation frameworks. Achieve lower resource use through better algorithms and implementation. Preserve the existing 1–4 GB JVM heap configuration and externally enforced 1 GB Voxy server limit, including automatic termination by an external process when the limit is exceeded in Testing.
- No integration tests or automated test suites. Remove existing integration tests. Verify through compilation, artifact inspection, profiling, and live testing.
- Main remains read-only. Watch host and process memory, preserve existing limits, and avoid causing an OOM.

Prove the improvements with comparable live measurements on my real client and 100 virtual Voxy clients spread around the map, without connecting 100 Minecraft players. Run 300 actual saved block changes per second concurrently with all 100 clients. Their connections must span 300–1,000 ms round-trip latency, 50–90% packet loss, and 500 kbps–3 Mbps bandwidth.

Apply impairment to actual QUIC traffic, including handshakes and retransmissions. Verify that all 100 clients are connected before starting the pressure measurement. Compare cold-cache, warm-cache, movement, zoom, terrain changes, disconnects, and reconnects using equivalent terrain, scenes, routes, and conditions. Terrain updates can arrive slowly; compare visible coverage and refinement speed ahead of freshness.

Keep comparative evidence in project_audit: counting rules and results, artifact hashes, actual loaded and running artifacts, allocation attribution, frame-time distributions, loading and refinement measurements, correctness screenshots, pressure-test results, and memory observations. Preserve failed results and investigate regressions without weakening the requirements.

Continue until the entire plan and comparative improvements are supported by live evidence. Compilation, theoretical benefits, installed-but-unloaded artifacts, and partial verification do not establish completion. Report remaining limitations and regressions honestly.
