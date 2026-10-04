# Transport recovery review

This review concerns the actual deployed native e529 and peer 3f09. Session 50166 remains unchanged. No transport experiment has been executed for the candidate described below.

## Exact configurations

Both frozen sources use Quinn's existing BbrConfig::default, optional keep_alive_interval 15 seconds, and max_idle_timeout 300 seconds. Native admits the application's single bidirectional stream and no unidirectional streams. Each virtual actor has independent TLS resumption and token state, retained across its own retries.

The installed dependency is quinn 0.11.11 with quinn-proto 0.11.17. Its TransportConfig defaults are initial RTT 333 ms, packet loss threshold 3, time threshold 9/8 RTT, persistent-congestion threshold 3, ACK-frequency control disabled, and MTU discovery enabled. No application PTO override is present. These are existing library settings, not proposed resource budgets. [Versioned primary documentation](https://docs.rs/quinn-proto/0.11.17/quinn_proto/struct.TransportConfig.html).

Frozen configuration sources:

- `.verification/native-phase-cid-build-20261004T084513Z/main.rs`, lines 234–240, SHA-256 2d43ac06ef33b5a78625111694d7c8fac73a815da0be1cc99c65da476d9d44ed.
- `.verification/peer-independent-tls-build-20261004T084225Z/main.rs`, lines 514–552, SHA-256 620676fea09b4a153f4d027557960a43d10e6200f46c57fa36c69c62209fd877.

## Measured states

The severe peer observation contains clients with zero delivered reverse packets: their TLS handshake cannot progress. Native telemetry separately proves pre-TLS timeouts, established connections awaiting the first stream/dimension bytes, and established terrain transfers. These are distinct states.

Several world-ACK waits had approximately 240 KB cwnd, only 24 transmitted STREAM frames, zero received STREAM frames, and zero or one received ACK. The entire genuine cold request prefix is approximately 27.5 KB, so a small congestion window does not explain those particular traces. Other connections do reach smaller windows during bulk recovery; this does not establish their exact bottleneck.

## Primary-source timer mechanism

Under `/home/aerosmp/.cargo/registry/src/index.crates.io-1949cf8c6b5b557f/quinn-proto-0.11.17/src/`:

1. `connection/mod.rs:1164` handles an expired KeepAlive timer by calling ping. `reset_keep_alive` at 1964–1969 only arms it for an established connection. Sending or authenticating packets rearms the timer; it is an inactivity interval.
2. `connection/packet_builder.rs:218–227` rearms keepalive, records the last ack-eliciting send time, and recalculates loss detection for each sent ack-eliciting packet, including PING.
3. `connection/mod.rs:1800–1833` calculates PTO as the last ack-eliciting send plus RTT-based duration multiplied by 2^pto_count. Application-data PTO includes the ACK delay.
4. `connection/mod.rs:1622–1657` creates one anti-amplification probe or two ordinary probes and increments the backoff. Loss probes bypass congestion blocking at 595–599.
5. `connection/spaces.rs:111–149` uses actual pending data for a probe, otherwise requeues retransmittable data from the oldest in-flight packet, and uses PING only when neither exists.

Therefore, if Data PTO has grown beyond 15 seconds, optional keepalive PINGs can repeatedly move its deadline while no ACK arrives. The observed long world-ACK waits with increasing PING counts and unchanged STREAM counts are compatible with this mechanism. That is a supported hypothesis, not a causal live result. It does not explain a pre-TLS failure, and a separate earlier Handshake-space PTO can still fire.

An authenticated incoming PING keeps the connection alive but does not acknowledge our STREAM. A newly acknowledging ACK proceeds through loss detection, can declare earlier missing STREAM packets lost, and resets pto_count after address validation (`connection/mod.rs:1458–1468,1522–1527,1838–1849`). Duplicate ACKs acknowledging nothing new return early. Thus a newly received ACK for a later PING can restore real stream recovery; an unacknowledged outgoing PING can postpone its timer. This is consistent with QUIC's standard ACK-driven recovery. [RFC 9002](https://www.rfc-editor.org/rfc/rfc9002.html#section-6.2.1).

## BBR and alternatives

The installed BBR starts at 200 times the 1200-byte base datagram size, matching the measured 240 KB startup window. It remains loss-sensitive: recovery conservation and loss subtraction can restrict recovery_window; after Startup its effective window can be clamped to that recovery window. Its minimum is four MTUs. Its configuration exposes an initial-window setter, not a random-loss recovery mode (`congestion/bbr/mod.rs:128–153,339–368,468–491,519–539,634–636`).

The other installed controllers are Cubic and NewReno, both of which reduce their windows on loss. Replacing BBR cannot remove PTO's exponential backoff because PTO is independent of the controller. Increasing its initial window would also not address the observed request prefix already fitting comfortably within the existing window. No new controller is justified by this evidence.

## Small held candidate

The candidate removes only the explicit keep_alive_interval line from each configuration. All remaining source bytes are identical, including 300-second idle timeout, BBR, loss recovery defaults, actual impairment, per-actor TLS independence, request prefix, 597-node route, 100-client authenticated gate, and 300 saved changes/second clock.

Ready actors are not idle while awaiting the pressure barrier: frozen actor lines 382–472 continually request actual needed terrain or known-hash refreshes, receive replies, and wait one second between cycles. Removing keepalive therefore does not inherently expire ready clients just because the barrier takes longer than 300 seconds. A path with no authenticated progress can still expire and retry under the existing idle lifecycle. Actor cleanup explicitly closes its connection; retained 300-second native idle policy also reclaims vanished clients. Disabling idle timeout is not part of this candidate.

Quinn's public Connection API has no dynamic keepalive or per-connection transport-config setter. Endpoint configuration replacement applies to future connections. The candidate uses no library patch, new dependency, invented probe, arbitrary timeout change, or packet quota. Both isolated offline release compilations passed; no automated test or live candidate run was performed. Artifacts and hashes are in `project_audit/load_results/no-keepalive-candidate.json`.
