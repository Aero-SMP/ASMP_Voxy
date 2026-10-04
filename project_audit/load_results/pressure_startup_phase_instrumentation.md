# Separate transport and terrain progress in the next controlled live run

The observation fields are maintained in the driver source and were compiled
into a separately staged release binary at 2026-10-04 06:18:46 UTC. They have
not been deployed or observed in the running rig. Each
existing setup/pressure line carries its observation timestamp and
`peer_changes`, containing only peers whose phase, application progress, or
transport snapshot changed since the previous observation. An omitted peer
retains its last observation; phase ages derive from the recorded timestamps.
Final per-peer reports also retain phase and the last transport snapshot.
An RAII guard removes the observation-only connection clone on every scope
exit, including errors and cancellation. The gate's connection table is unchanged.

The current `frontier-full-startup-pressure-01` run remains unchanged. Its loaded
peer SHA is `8855f0ab400c3905099db0ca64383e87ca860adfe3f02a102d2b8a0ab7fddf4c`.
At 2026-10-04 05:17 UTC it had 70 current world-acknowledged connections, a
maximum of 72, 78 peers with cached payloads, and 54 complete 597-node views.
It has not reached the simultaneous 100-client gate; no pressure clock or saved
mutation workload has started. These are setup observations, not a passing run.

## Current observability limitation

The currently loaded `8855…` driver groups QUIC connection establishment, bidirectional
stream opening, dimension writes, and the 16-byte world acknowledgement into one
`opened` operation. It publishes `current` only after that acknowledgement.
Consequently `active` already means world-acknowledged and transport-live, rather
than merely a successful QUIC handshake. A saved `world` file proves that a peer
received an acknowledgement at least once; it does not prove its current
connection remains live or reveal which stage a later attempt is waiting in.

Cold terrain requests are pipelined: the actor writes the whole request list
before reading the ordered responses. The batch is not 597 sequential request
round trips. Request and response progress inside an incomplete cycle are not
exposed in that running driver's one-second setup output.

## Staged instrumentation

Retain the existing acknowledged-connection table and 100-client gate exactly.
Add an observation-only transport handle after `endpoint.connect(...).await`
succeeds, independently of the handle used by that gate. Track each peer's
attempt number, current phase, phase entry time, and most recent application
progress time. Change phase only at existing operation boundaries:

1. Attempt started / waiting for the QUIC handshake.
2. Handshake completed / waiting to open the bidirectional stream.
3. Stream opened / writing the dimension greeting.
4. Greeting queued / waiting for the complete world acknowledgement.
5. World acknowledged / writing the existing terrain request batch.
6. Request batch queued / receiving response headers and complete payloads.

Queued writes must be labelled queued, never delivered: completion of
`write_all` does not establish receipt by the remote endpoint. Record response
index, completed payload count and received bytes as actual read operations
complete. Preserve the phase and error on a failed attempt and clear its
observation handle without changing reconnect behavior.

Extend the existing setup observation cadence with per-peer phase durations and
public `Connection::stats()` snapshots wherever a completed handshake provides
a handle. The locally installed official Quinn 0.11.11 / quinn-proto 0.11.17
source exposes UDP transmit/receive packets and bytes; frame ACK, CRYPTO, PING,
STREAM, DATA_BLOCKED and STREAM_DATA_BLOCKED counts; RTT, congestion window,
lost packets/bytes and congestion events. Capture counters and deltas rather
than adding packets. Before handshake completion, retain the existing proxy's
actual traffic/loss/latency counters; the ordinary completed-connection API
cannot provide those connection snapshots yet.

This is O(number of peers) observation at the existing cadence, with direct
per-peer phase updates at operation boundaries. Do not hold a statistics mutex
across network awaits. No new transport probes, application timeouts, retries,
PTO settings, quotas, resource budgets, loss exceptions or simulated replies
are needed to observe these phases.

## Preserve the acceptance conditions

Only simultaneous world-acknowledged connections with no close reason count
toward 100; successful handshakes alone never count. The writer continues to
start only after that same 100-client gate and its immediate start check.
Retain the exact 597-node route, isolated fixture, seeds, fixed peer links,
50–90% loss in each direction, 300–1,000 ms RTT, 500 kbps–3 Mbps bandwidth,
300 actual saved changes/s, and existing reconnect scenario. Record staggered
cold startup separately from the later mixed-cache pressure interval.

The next instrumented run requires the current run to terminate with preserved
receipts and a coordinated artifact/run decision. Elapsed setup time alone is
not a reason to restart it. Flat counters can localize a wait, but neither a
congestion window snapshot nor a phase label alone establishes its cause.

## Isolated compilation receipt

`cargo build --offline --locked --release` completed successfully (exit 0) in
8.858 seconds using a frozen copy of the maintained source and an independent
target directory. No tests or driver invocations ran. Compiler versions were
Cargo 1.98.1 and rustc 1.98.1. The original operator-watched executable
`tools/load/target/release/voxy-load` retained SHA-256
`8855f0ab400c3905099db0ca64383e87ca860adfe3f02a102d2b8a0ab7fddf4c`
before and after compilation. The pressure owner independently supplied that
loaded hash for PID 2609821; this compilation namespace could not inspect that
PID, so the process observation is owner-provided rather than locally verified.
No process, connection, operator, laptop, server, or running workload was altered.

| Input or artifact | SHA-256 |
| --- | --- |
| `tools/load/src/main.rs` | `25d23b5911d0b117c9cb0088ece78d11aebf0f53e6009ec87a53edfa24085d73` |
| `tools/load/Cargo.toml` | `f9ae3ce1dec9efb90f7f24889e5200049f6f0abaeb60d8365cfd600163d618d3` |
| `tools/load/Cargo.lock` | `04d3b6a3317b4aa55709bb82ea6aab6b01eef51cdf67401b5d786dedd9af4c8c` |
| Staged release driver (4,528,136 bytes) | `44a4f02ae4800aa3186fd3d6e3626d19f19bdf62f0ce8d1a8d57ae39143da422` |

The binary is
`.verification/pressure-phase-driver-build-20261004T061757Z/voxy-load-44a4f02ae4800aa3186fd3d6e3626d19f19bdf62f0ce8d1a8d57ae39143da422`.
The structured receipt and complete compiler log are
`phase-driver-build-20261004T061757Z.json` and
`phase-driver-build-20261004T061757Z.log` beside this document. Maintained source
and manifest hashes still matched the frozen inputs after compilation.

The source review compared rustfmt stdout for the frozen
`model-wrapper-identity-candidate/source/tools/load/src/main.rs` and current
source without modifying either file. Beyond formatting, the difference adds
observation state, operation-boundary updates, transport snapshots and sparse
per-peer JSON observations. The existing `current` table is still populated
only after the complete 16-byte world acknowledgement and cache association.
The simultaneous live-client gate, immediate START check, 597-command batch,
spatial route, cache payload validation, BBR selection, 15-second keepalive,
300-second configured idle timeout, one-second retry/cycle cadence and planned
disconnect/reconnect event are unchanged. The observation-only connection
clone is dropped before the existing failure retry wait and on scope exit.
Statistics locks do not cross network awaits. Read-only snapshots add observer
CPU/allocation and output overhead; this build is a diagnostic artifact, not a
performance result.

## Next diagnostic decision

Run this staged binary only after the current rig has a preserved terminal
receipt and the pressure owner coordinates the next run. Preserve all 100
independent impaired links, exact seeds/route, workload, native limit and gate.
Reconstruct each peer from its first observation plus `peer_changes`; omitted
peers retain their previous observation. Compare attempt/phase changes and
actual UDP counter deltas with each proxy's delivered and dropped traffic.

- A peer waiting in `handshake` needs transport-level handshake evidence next.
  This driver has no completed-connection stats handle at that stage; delivered
  proxy packets alone do not establish authenticated QUIC progress. Pinned
  Quinn supports feature-gated qlog or tracing with an installed subscriber.
  The current binaries install neither; setting `RUST_LOG` alone is insufficient.
- A peer waiting in `open_stream`, `write_dimension` or `world_ack` has an
  established-connection snapshot. Correlate STREAM/ACK/loss counter progress
  and packet loss with the direction-specific proxy counters. If the phase
  persists, the next diagnostic should record native accept/greeting/world-write
  boundaries and QUIC events, preserving every existing transport setting.
  A queued world write is still not acknowledgement delivery.
- A peer repeatedly failing after `world_ack` requires its original failure
  phase and final error list to distinguish transport closure from cache or
  payload errors. It must not be counted as continuously live merely because
  it saved a world identity earlier.

The native greeting sends the world identity before terrain record lookup or
Java build waiting, so those waits do not directly precede the first world
acknowledgement. Existing evidence does not establish a causal transport bug.
Pinned quinn-proto 0.11.17 re-arms keepalive on packet transmission
(`connection/packet_builder.rs:218`) and authenticated reception. Its transmit
path exempts loss probes from congestion blocking (`connection/mod.rs:597`),
so a small congestion window alone does not prove probe deadlock. Server
anti-amplification and PTO/backoff are concrete states to observe if the
handshake phase persists, not proven causes or permission to change settings.
The 100 fake connections use Quinn at both ends; Java Kwik 0.10.10 serves the
real player and does not itself control their startup.
