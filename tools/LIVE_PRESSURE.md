# Live pressure verification

The server-timings/100-Rust-clients plan re-enables only the 100-client terrain run.
Artificial block-change pressure remains suspended. Use the exact implementation
plan and immutable external 600 s clock; no integration tests are added or run.

`rust-server/src/live_pressure.rs` is the current-protocol Rust runner. One shared
Tokio runtime owns 100 independent routes, Quinn endpoints/connections, opaque UDP
relays, protocol states and run-owned disk caches. There is no Minecraft login,
player creation, rendering, world generation or source-world mutation.

## Inputs and finite invocation

The privileged debug server command owns route admission and revocation:

```
voxytest pressure start <runUUID> <external_deadline_unix_ms> 100
voxytest pressure status <runUUID>
voxytest pressure timings <runUUID> on|off
voxytest pressure stop <runUUID>
```

The secret `routes.tsv` must grant no group/other permissions and contain 100 rows,
without a header: `zero_based_client_index<TAB>nonzero_token64`. The runner never
prints tokens. Locations TSV contains `client<TAB>dimension<TAB>x<TAB>y<TAB>z`,
with optional header. Optional saved-regions TSV is `dimension<TAB>region_x<TAB>region_z`.
Fresh saved-region inputs constrain the authenticated server inventory; source
Anvil files and border identities must be verified independently.

```
live_pressure --server 127.0.0.1:25787 --cert VERIFIED_CERTIFICATE_DER \
  --routes SECRET_ROUTES_TSV --locations VERIFIED_LOCATIONS_TSV \
  --saved-regions VERIFIED_SAVED_REGIONS_TSV --cache NEW_RUN_OWNED_DIRECTORY \
  --run-id UUID --clients 100 --duration 330 --plateau-seconds 240 \
  --stop-unix-ms PRESSURE_STOP_BEFORE_RESTORATION_RESERVE \
  --cleanup-unix-ms ORIGINAL_EXTERNAL_RUN_DEADLINE \
  --rtt-ms 300 --loss-percent 10 --cap-kbps 1000 --seed 17
```

Use actual verified addresses/paths, not the descriptive placeholders above. `--cache`
refuses an existing directory and requires an absolute Desktop path. No cache is
deleted. Every invocation opens QUIC even with warm-looking source state.

Calibration uses the same binary/routes and a separate new cache directory:
`--clients 1 --plateau-seconds 10 --duration 30`; unshaped baseline uses
`--rtt-ms 0 --loss-percent 0`. All live probes share the same external deadline;
calibration never resets or extends the operator clock. Both absolute deadlines
must match the external owner: pressure cutoff 420 s, original overall expiry 600 s.
The runner rejects cleanup deadlines more than 600 s away; this check does not
replace external proof of the original clock. Adjust symmetric added
propagation delay from measured unshaped RTT to the 300 ms unloaded target.

`--check-arithmetic` performs only socket-free serialization/loss/FIFO/cleanup
calculations, sparse saved-hierarchy traversal and plateau cohort boundaries.
It is a focused ownership check, not an integration test.

## Workload and profile

Each client boots with nearest coarse demand, consumes the current hello,
manifest/catalogue authority, both section lanes and discovery inventory, then
fills the existing maximum 16 worker-slot request ownership window from a lazy
spatial heap seeded only by saved regional roots. Distance/LOD ranking gives
near coverage and detail priority; popping a node expands at most eight children.
Empty border area is never walked. View changes rebuild the remaining heap once. Saved/border filtering happens before demand.
The 240 s plateau starts only after all 100 clients validate useful coverage and
refinement records, not merely after opening UDP sockets or reading a hello.
Setup completions never enter plateau counts. Every plateau phase must contain
new missing DATA/EMPTY completions; REUSE alone cannot pass. Each 1 s sample must
show outstanding missing demand, with no idle observations. Native-side session
proof, progress distributions and no-progress ages remain independently required.

Plateau phases: 90 s cold coverage/detail; 90 s movement one coarsest cell toward the
border center; 60 s warm revisit with ordinary coverage/refinement HAVE/REUSE
alongside continuing cold missing demand, with deterministic alternating warm/cold admission. Exact
retired key/tickets admit already-committed trailing updates after Drop; these
are validated separately and cannot overwrite current cache or count as missing
coverage. Unchanged refresh-only interests can
remain silent and do not occupy delivery slots. The existing 2 s refresh policy
remains active. Cached payloads stay on disk; only bindings/keys stay resident. Decode/integrity/persistence runs in
blocking workers with reusable per-client decompression scratch. Reader handoffs
retain one message from each of the four reliable streams.

Each relay shapes real wrapped UDP datagrams independently in both directions:
150 ms propagation (before baseline correction), seeded 10% loss after serialization,
and 1,000 kbps actual IP/UDP traffic. Wrapped payload bytes already include the 17 byte
routing envelope; IPv4 adds 28 bytes and IPv6 adds 48 bytes exactly once. Dropped
packets consume capacity; retries go through the same link. Send-await duration and actual send completion timestamps/lateness
are separate from serialized service decisions. Fixed-delay FIFO
selection is O(1); overdue service rebases the following packet so it cannot
catch up in a burst. Both direction ceilings are full duplex.

## Evidence and cleanup

JSONL reports 1 s cumulative per-client protocol/latency/worker/QUIC counters,
independent relay attempts/service/loss/delivery/pending/abandonment bytes and
rate-window checks. `relay_bound` publishes owned front/backend ports and Linux
socket inodes for independent `/proc/net/udp{,6}` receive-drop observation. The
runner detects send errors/truncation; absence of these does not prove kernel
receive-drop absence. The external observer also measures generator CPU/RSS,
event-loop lateness, server resources and actual route/session/subscription counts.

All clients must retain connection and useful progress throughout the complete
plateau. A generator/relay bottleneck, missing diagnostics or insufficient plateau
is inconclusive. Correctness faults and resource exhaustion are failures. No speed
or FPS target is invented after measuring results.

Closing clients has a QUIC drain and relay shutdown bounded by the original
overall 600 s deadline, not a fresh timer or fixed 2 s PTO assumption; queued packets are
counted as abandoned. Parent cancellation aborts owned relay tasks. Already
started blocking I/O is not cancelled by dropping its future; the independent
owned-process deadline supervisor remains mandatory, including runtime shutdown.
Drain failures appear in client and overall technical outcomes. Route removal
and shared-work cleanup require independently observed debug bridge acknowledgments
under the existing external guard. The runner's `run_finished` reports `technical_checks_passed` and an explicit
`acceptance: inconclusive` pending external profile, native cleanup, resource and
real-PC evidence. It does not establish
route cleanup, real PC recovery or a passing project-level result. Preserve the
real PC cache/settings/both SSH helpers, Main identities and current hard limits.
