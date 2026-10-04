# Live pressure operator

This opens 100 actual QUIC connections without Minecraft players. The existing
testing Java server publishes terrain; its existing native process retains the
external 1 GB guard. The operator starts no server and never controls Main.

```sh
cargo build --offline --release --manifest-path tools/load/Cargo.toml
python3 tools/load/pressure.py run --name cold-pressure --seconds 120 \
  --server 127.0.0.1:25787 --cert /path/to/current/server-cert.der \
  --pids NATIVE_PID,JAVA_PID
python3 tools/load/pressure.py run --name warm-pressure --seconds 120 \
  --server 127.0.0.1:25787 --cert /path/to/current/server-cert.der \
  --pids NATIVE_PID,JAVA_PID \
  --warm-from project_audit/load_results/cold-pressure/cache
```

The existing immutable fixture snapshot and its manifest are recorded under
`project_audit/load_results/fixture-current-frozen`. Its live copy is at
`Voxy_Testing/world/dimensions/voxy/pressure/region`, a separate `voxy:pressure`
saved dimension containing 100 spread-out regions. Each contains a 32×32 chunk
patch with four vertical sections, stone/dirt/grass/water topography, biomes and
light. A synthetic view asks for levels4 through1 coverage and512 level-0 detail
sections, up to597 hierarchical nodes per peer across512 blocks.
Moving priority is synthetic demand, not captured GPU visibility. The default
always requests597 nodes for comparable pressure. `--zoom` runs a separate quality
case alternating597 and85 nodes every ten seconds. The real player establishes
rendered correctness.

Each peer has a separate actual UDP link. Loss rises from 50% to 90%, RTT from
300 to 1,000 ms, and bandwidth decreases from 3 Mbps to 500 kbps. Both directions
apply independent seeded loss and serialization to every datagram, including
handshakes, ACKs and retransmissions. Setup never bypasses impairment. It waits
for all 100 live QUIC connection handles and dimension acknowledgements before
the pressure clock starts; interrupted setup stays in its evidence directory.
Acknowledged clients begin actual terrain demand immediately during staggered
startup, as real clients do. They do not sit idle while poorer links connect.
Cold first-coarse/detail times start at each actor's first connection attempt;
warm local readiness is measured before connecting. Setup counters and the cache
size at the simultaneous100-client barrier distinguish cold startup from the
later pressure interval, which has mixed cache state. No post-barrier all-cold
claim is made. Both native server and virtual peers use Quinn's default BBR.

An independent writer receives 300 unique non-no-op edits each wall-clock second
and saves only dirty chunk bodies, allocation entries and timestamps, then fsyncs
touched regions. Sector growth receives a valid appended allocation. Dirty raw
chunks are released after each publication. Actual completed commits before the
pressure deadline determine the persisted rate; drain time cannot manufacture
300 changes/second. The client interval includes 30 additional seconds for final
refresh observation. A deliberate disconnect occurs at 60% of that interval,
while cached data remains available and clients reconnect under the same links.

Warm cache frames are decoded before any connection begins. Cache persistence
keeps the exact validated compressed payload, without catalog dependencies or
recompression. Payload requests carry their cached SHA so unchanged records do
not transfer again. Current-format validation checks palette fields, all 39,304
halo indices, padded Minecraft packed storage, children and entity fields.

Each run creates a new `project_audit/load_results/NAME` directory and preserves
client events, detailed final client records, saved-commit timestamps, impairment
counters, host memory, native/Java/writer/client process and cgroup samples,
certificate and artifact hashes. Existing directories are never overwritten.
Confirmed watched-process death, changed existing ceilings, OOM-kill increments,
failed setup, unverified final process health and insufficient persisted changes fail
the run. Low coverage and slow refinement remain explicit in the raw records;
successful operator exit alone does not prove rendering quality or throughput.
No unit, mock, integration or automated test suite is included.
