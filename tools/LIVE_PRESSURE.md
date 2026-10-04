# Live terrain pressure

`live_pressure.py` opens 100 current-protocol QUIC players without Minecraft
logins. It starts no server. Deploy the matching debug client/server and verify
the real player and existing memory guards before starting this operator.

Build the separate consumer with the existing Rust dependencies:

```sh
cargo build --offline --release --manifest-path rust-server/Cargo.toml --bin live_pressure
```

Create an operator preflight JSON with the actual observed process identities:

```json
{
  "testing_root": "/home/aerosmp/Desktop/Voxy_Testing",
  "native_pid": 12345,
  "java_pid": 12346,
  "real_client_verified": true,
  "runtime_headroom_verified": true
}
```

The booleans describe completed live verification; this file is not a substitute
for that verification. Both processes must still have the Testing working
directory. The native cgroup must retain its existing external ceiling of at
most 1 GB. Process start ticks, ceilings and OOM-kill counters are watched.

Provide `locations.tsv`: exactly 100 distinct saved Anvil region coordinates,
one `region_x region_z` pair per line, spread across the existing map. Include
`0 0` for the debug mutation patch. Put that pair first to give the patch's
consumer the 300 ms/50%/3 Mbps profile. The remaining profiles gradually reach
1,000 ms/90%/500 kbps. The eight foreground lanes match the real client: two
coverage and six refinement streams. Each peer uses a separate actual UDP link.
Foreground and background share that peer's bandwidth in each direction.

Enable native batch diagnostics with `-Dvoxy.background.trace=1` on the Testing
JVM. Supply its native diagnostic log (normally the Java server's current log):

```sh
python3 tools/live_pressure.py --name cold-100 --server 127.0.0.1:25787 \
  --cert /home/aerosmp/Desktop/Voxy_Testing/voxy-data/quic/certificate.der \
  --preflight project_audit/pressure-preflight.json \
  --native-log /home/aerosmp/Desktop/Voxy_Testing/logs/latest.log \
  --locations project_audit/pressure-locations.tsv --dimension minecraft:overworld \
  --phase-seconds 60 --interval-ms 1000 --cap-kbps 1000
```

The existing authenticated debug command `voxyload` applies actual changes to
its preserved AIR-only patch at chunk `(0,0)`, Y `312..315`. The relevant consumer
always requests that fine section and its parents; other clients use their
ordinary two vertical sections. Edit `--mutation-block-x/y/z` if the operator
selects a different approved patch. The mutation writer starts after all 100
primary sessions are live, each has usable coarse terrain, each has connected
its background channel, and the patch's fine section is locally available.
It runs the 300 and 1000 changes/s phases, followed by a settling interval.

`voxy_testing_control.py` reads Testing's RCON credentials internally and checks
that the RCON listener belongs to the Testing working directory. It supports
`command TEXT`, `mutation-start RUN SECONDS 300 1000`, `mutation-stop RUN` and
`mutation-restore RUN`. Credentials are never printed. A custom
`--mutation-control` JSON may replace its argv lists; shell commands are not used.

Warm-cache repeat:

```sh
python3 tools/live_pressure.py --name warm-100 --server 127.0.0.1:25787 \
  --cert /home/aerosmp/Desktop/Voxy_Testing/voxy-data/quic/certificate.der \
  --preflight project_audit/pressure-preflight.json \
  --native-log /home/aerosmp/Desktop/Voxy_Testing/logs/latest.log \
  --locations project_audit/pressure-locations.tsv --dimension minecraft:overworld \
  --warm-from project_audit/load_results/cold-100/cache
```

Each consumer validates its cached catalog and section before connecting. An
adequate cached section creates background interest, without a foreground miss.
Zoom demand alternates coarse and fine views; cached fine data is used locally.
New records validate compressed CRC, canonical length/BLAKE3, palette packing,
block/biome IDs, key/ticket ownership and world association. Persistence keeps
the exact compressed bytes. A failed background connection reconnects on the
same configured endpoint/token while foreground coverage and cache stay usable.
The background endpoint uses the configured foreground server address and UDP
port, including any public port mapping. Its UDP packets
carry a 17-byte envelope (`0x00` plus the first 16 bytes of its session token),
which the consumer removes and validates before handing packets to QUIC. The
role-2 stream still authenticates with the full 32-byte token. The observer
classifies background packets by their envelope, including handshake traffic.

All packets, including handshake, ACK, close and retransmission traffic, receive
independent seeded loss, one-way propagation delay and serialization. Loss also
consumes bandwidth. Link serialization and QUIC recovery can add latency beyond
the configured propagation RTT. The server's background cap is observed before
proxy loss, with the envelope and IPv4/UDP headers included. `background-packets.bin` contains
16-byte little-endian records: kernel epoch nanoseconds (`u64`), IP bytes (`u32`),
client id (`u32`). Kernel timestamps prevent a delayed Python callback from
appearing to be a server send burst. Kernel socket-queue loss fails the operator.

Results are preserved in a new `project_audit/load_results/NAME` directory:
client/transport counters, local readiness and cache demand outcomes, per-link
packet loss/bytes/delay, packet captures, native batch start/finish/cadence,
process/cgroup/host memory, actual mutation phases, completed saves and restore
receipts. Actual achieved changes/s uses the recorded last mutation time.
Asynchronous save completion is reported separately, with its real timestamp;
drain time cannot inflate the mutation rate. Below-target mutation rates, missing
save/restore receipts, evidence failures, absent replacement data, insufficient
100-player continuity, integrity errors, cap/cadence failures, changed process
identity/ceilings or new OOM kills fail the run and preserve its evidence.

`--no-mutations` is an observation-only case and cannot report a verified full
pressure workload. Virtual consumers exercise terrain delivery/cache/integrity;
GPU appearance, meshing, publication and frame behavior require the real player.
No integration tests or automated test suite are involved.
