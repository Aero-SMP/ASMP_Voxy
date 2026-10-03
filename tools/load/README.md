# Live virtual-client workload

This tool opens 100 real QUIC connections against an isolated native Voxy backend.
It does not launch Minecraft bots, mock a handler, or modify Main. Production code
and GPU rendering are outside this tool. All compilation uses cached dependencies.

```sh
cargo build --offline --release --manifest-path tools/load/Cargo.toml
python3 tools/load/fixture.py create project_audit/load_results/fixture-base/world
python3 tools/load/run_suite.py old --name old-run
python3 tools/load/run_suite.py new --name new-run
python3 tools/load/run_suite.py new --name impaired-run --impaired --cases changing
```

The fixture contains 100 separated Anvil regions. Each saves an 8-by-8 chunk patch,
four vertical 16-block sections, nonuniform stone/dirt/grass/water topography,
biomes, and explicit light arrays. Each synthetic view requests one level-2
coverage section and 32 level-0 detail sections spanning 128-by-128-by-64 blocks.
This is documented synthetic demand, not a captured GPU selection trace or a
claim about every possible Minecraft view distance or modpack.

The mutation driver applies seeded, non-no-op edits to packed saved block states
at 300 edits/second on wall-clock time and publishes changed `.mca` files once
per second using atomic replacement. It records actual counts, source-save lag,
distinct blocks/chunks/regions, CPU, bytes written, and final save-drain time.
Backend completion never controls the mutation clock. The final level-0 digests
are checked against the final authoritative saved-Anvil input after edits stop.
Coarse LOD rules differ between implementations; compare coverage and level-0
content separately, and do not infer identical coarse visual appearance.

Each native backend runs as its own transient user service with external
`MemoryMax=1000000000`, `MemorySwapMax=0`, `OOMPolicy=kill`, and
`KillMode=control-group`. The fixture writer, virtual clients, and impairment
proxies are separate processes. The program stops only its named benchmark unit.
An external-guard termination is recorded as a failed run.

`--impaired` gives every client a distinct UDP listener and upstream socket. Loss
probability rises deterministically from 50% to 90% across the 100 clients; each
direction has its own seeded random sequence. Every datagram, including TLS and
ACKs, consumes serialization time at 3 Mbps and is subject to loss. Surviving
packets receive at least 500 ms propagation delay in each direction, giving at
least 1,000 ms RTT before queueing or retransmission. Packet counters and raw
resource samples are retained. Handshake failures count as failures; the driver
does not lower loss or bypass the actual protocol to produce 100 successes.

The load clock begins after all connection attempts reach the simultaneous start
barrier; setup latency and failures are recorded separately. The connection
deadline is 120 seconds. A running case ends at its specified wall-clock deadline
and reports unfinished cycles instead of silently reducing movement or waiting
indefinitely. Synthetic movement changes request order, and teleports replace the
currently requested region using wall-clock time.

Warm/offline driver cache entries record validated terrain content digests and
payload identities. Their lookup readiness is a receipt/coverage proxy; it does
not prove persistent-payload decoding, Minecraft model preparation, visible GPU
coverage, rendering seams, or frame time. Those require the real player client.
Initial cold gaps, missing saved terrain, and incomplete refreshes remain separate
from a claim about holes in rendered terrain.

Raw reports include per-client coverage/detail readiness, actual payload bytes,
latencies, errors, incomplete work, final-state validation, backend SHA-256,
certificate identity, external guard settings, and process memory/CPU/I/O. Cgroup
I/O accounting is not enabled in this user's slice; `/proc/PID/io` is captured
instead. Host filesystem cache is uncontrolled and Internet paths are not part
of an unimpaired loopback comparison.

Results are append-only: the runner refuses an existing output directory. Failed
experiments stay beside successful ones. No standalone unit, mock, codec, or
integration test suite is included.
