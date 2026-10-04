# Historical pressure evidence limitations

Existing historical records are retained as diagnostics. They are not a matched acceptance result for the current100-client/300-persisted-change/severe-network requirements.

| Receipt | Confirmed result | Limitation |
|---|---|---|
| `old-baseline/failed-run.json` | Native cgroup OOM-kill at999,997,440 bytes; writer18,000 changes over70.8766s =253.9626/s. The peer report declares100 connected. | No run manifest establishes both frozen artifact hashes or impairment. Required300 persisted/s was not achieved. Repeating the known dangerous workload would violate the current no-OOM instruction. |
| `old-full-02/run.json` |100 full32×32-chunk regions,597 possible nodes/peer. Cold and warm reports each declare100 connected; the later partial case ended with cgroup OOM-kill. | Loopback traffic was unimpaired; no simultaneous300-saved-change case completed. Metadata also repeats one SHA for both native and peer roles and differs from the current frozen reference hashes, so artifact equivalence is unproven. |
| `old-impaired-01/run.json` | Actual per-client UDP impairment was recorded as50–90% loss,1,000ms RTT,3Mbps. Cached-changing report declares `all_connected=false`,98 errors, zero transferred payloads and only5 per-client connected flags. | The fixture was128 blocks/32 detailed sections per peer, not the current512-block/597-node footprint. The reported start barrier did not establish100 actual active connections. Persisted rate299.4428/s does not meet300/s, and its artifact metadata does not prove the frozen reference identity. |
| `old-suite-01/run.json` | Smaller loopback cases declare100 connected; changing writer299.4773/s. |33-node demand, no severe-network setup, different artifact hashes and sub300 actual persisted rate. |

`historical_pressure_evidence.json` contains a compact extraction from the unchanged raw receipts, including declared connection flags, cases, artifact/profile/route metadata and actual saved-change rates. A declared barrier or successful driver exit alone cannot substitute for active-connection proof.

The authoritative current operator uses100 fixed independent UDP ports/profiles, waits for100 live QUIC handles plus dimension acknowledgement before START, and determines300/s from actual fsynced commits before its deadline. It does not retain a historical application decoder. An unused shared-CID router prototype was removed: fresh reconnect CIDs from one common source port cannot establish a stable logical-client/profile mapping without extra identity, and retries can cluster active connections on easier profiles. No known dangerous historical native run will be restarted merely to reproduce its failure.
