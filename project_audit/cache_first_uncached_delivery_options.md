# Uncached terrain delivery options — PC debug247

This is a read-only performance assessment of the current deployed branch, not an implementation of these options. The production routing correction in debug247 and pressure-observer/lifecycle corrections were already part of the active cache-first plan.

## Observed evidence

The user's latest screenshot is `/home/aerosmp/.printer/screenshots/screenshot-444.png`: Clumsy0.3 applies300ms delay in each direction,10% packet drop in each direction, and1000KB/s per direction to MinecraftTCP25587 and VoxyUDP25787 at95.164.127.81. That means600ms added RTT and approximately8Mbps bandwidth per direction, not1000ms RTT or1Mbps. The user reports removing the impairment; the log snapshot does not independently establish its exact enable/disable boundaries.

PC auto-update status and current log header verify debug247. The captured session's first local completion was0.870s, first HELLO8.792s. The first telemetry sample with HELLO was22:07:18.226; the first completed fresh section was22:09:26.675, about128.45s later. These sampled times have approximately1s uncertainty and are not an exact request-to-screen trace. At that first fresh completion only5149compressed terrain bytes had reached completed section workers, against1,240,547cumulative QUIC UDP payload bytes received.

For the first six fresh section completions, summed worker stage deltas were: decompression0.6531ms, decode/validate19.8797ms, meshing11.4835ms, model waiting7.2935ms, optional cache saves49.9063ms, writer waiting0.1792ms. All14 workers were idle at the captured endpoints. These are cumulative worker wall times, not six end-to-end section latencies. They support delivery before processing as the leading delay in this session. GPU publication maxima must not be mistaken for averages.

Latest uploaded sample at22:23:55 still shows14idle workers andpublishQueue0:4381867compressed terrain bytes completed against7936448QUIC UDP payload bytes received, with827geometry sections. The gap includes catalogues, descriptors, control, transport overhead and any received retransmissions; it must not all be called catalogue bytes. The existing catalogue gate mostly harms cold startup; steady-state throughput needs separate congestion/transfer measurements. Latest summary: `read_only_timing/latest_pc_summary.json`.

Evidence snapshot: `read_only_timing/client_247_20261004T221049Z.json`. The `received` field counts completed network-source section bytes, not partial frames or total UDP bytes. `metadataNetworkBytes` counts a newly decoded canonical catalogue once, not every duplicate wire copy. Likewise, a lane is marked active only after a full frame is read; eight inactive lane indicators can conceal partial catalogue reads.

## Source-supported findings

`rust-server/src/server.rs:734` prepares a record, writes its catalogue, reads its body, then sends its descriptor/body. Every lane owns a separate last-catalogue fingerprint. Thus the same catalogue can be sent once on each of eight foreground streams and again on background. The current canonical catalogue is355925bytes. Eight framed copies total2847728application bytes. Zstdlevel1 reduced the exact canonical data to48096bytes in a read-only in-memory size calculation. This is a measured byte reduction, not a measured delivery speedup.

The persistent streams already deliver server-pushed records from batched OPEN/DESIRE requests. There is no fresh request-response RTT for each section. Region lookup is direct; the server retains regional handles and reads already-compressed bodies with CRC validation, without serving-time recompression. The client releases a lane when its record is assigned to a worker, before meshing and optional persistence finish.

Production download congestion control is Quinn's default CUBIC. The pressure consumer's BBR setting is client-side and does not change the server download controller. Per-connection loss/retransmission/cwnd and per-lane catalogue progress are needed to distinguish the remaining congestion-control delay from metadata serialization. The512KiB send window is not a deadlock; acknowledged bytes release it.

## Ranked options

Complexity measures expected source/binary growth relative to today's branch, not how intellectually difficult a change is. Scores are engineering estimates; only the catalogue size calculation is a measured optimization result. Smaller complexity/tradeoff and higher benefit are preferable. Options can be combined.

| Rank | Approach | Complexity (-10..10) | Benefit (0..10) | Tradeoff severity (0..10) | Cost or qualification |
|---|---|---:|---:|---:|---|
|1|Send one compressed catalogue per session; retain it in cache and reuse it when its fingerprint matches|+2|9|2|Small current-protocol/cache change; dependent streams must wait on the same validated definition without another ACK/request RTT. Cold catalogue portion could fall from up to2.85MB to about48KB; a valid held catalogue removes that portion entirely.|
|2|Compress catalogue frames while keeping existing per-lane ownership|+1|8|1|Simplest isolated change; measured86.49% smaller definitions, but eight duplicates remain. Existing Zstd dependency is sufficient.|
|3|Compare native download BBR against default CUBIC under the real impaired connection|0|7, unproven|4|Very small source change using the existing library. Random-loss gains, queueing, fairness to Minecraft and memory must be measured; do not promise it defeats90% loss.|
|4|Overlap server preparation/body reading with transmission using the existing lane owner|+2|5|3|Useful if timings reveal disk/task-transition gaps; retains current reliable streams. Avoid a copied, unbounded prefetch queue. This does not eliminate another RTT because requests already batch.|
|5|Merge preparation and body reading into one blocking task|-1|3|1|A small simplification removes one task transition per section. Preserve REUSE/unchanged shortcuts so cached content is not read needlessly. Server timing benefit remains unmeasured.|
|6|Send only definitions needed by arriving sections instead of the full global catalogue|+5|6|5|Removes the full-catalogue cold dependency, but sparse definition ownership and new regional payload construction are more code; repeated per-section names can increase total bytes. Current payload palettes are already compact.|
|7|Build requested coarse coverage before a complete cold regional shard|+6|6, cold-build only|5|Relevant when the server has no published regional data. Requires correct partial publication and ancestor/child coverage; current PC stall has not been attributed to this.|

First choice:1, with2 as a smaller independent first patch if desired. Then compare3 using measured server-side loss/cwnd stats. Keep the original spatial store, precompressed section path, mesher and cache-first publication. Avoid increasing stream count blindly: it multiplies the current redundant metadata and does not fix connection-wide congestion.

QUIC ordered-stream and loss/congestion behavior are documented by RFC9000 sections2.2–2.3 and RFC9002 section7. Zstd's existing API supports compressing/reusing immutable frames; no new compression dependency is needed. References:
- https://www.rfc-editor.org/rfc/rfc9000.html#section-2.2
- https://www.rfc-editor.org/rfc/rfc9002.html#section-7
- https://facebook.github.io/zstd/zstd_manual.html

## Current implementation/live-run status

Matched debug247 client/server is deployed only to Voxy_Testing, and the PC has auto-updated. Both PC backup SSH helpers were independently verified after that update:19916primary and22444secondary remain alive. The native executable matches the built hash and existing external999997440-byte memory maximum withswap0/OOM0. Java Xms1G/Xmx4G are preserved.

The100-client cold pressure trial is still in setup, using the required50–90% loss each direction,300–1000ms RTT and500kbps–3Mbps shared foreground/background link rates. It has not passed, and no300/1000changes-per-second phase has started. Its operator monitors process identity/limits/OOM continuously and will only mutate the reversible AIR patch after all100 consumers are usable. No recommendation above has been implemented as part of this read-only assessment.
