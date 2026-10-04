# Exact-library cold-handshake review

Read-only review on 2026-10-04. The current run, transport conditions, native server, client feed and application protocol were not changed. The pressure run has not achieved 100 simultaneous world-ACK-live peers, so its 300 persisted edits/sec clock has not begun.

## Evidence and scope

The running native is `7457e3e48fbc4360e4f2f3337851ac247199c5ee94552aae51d3dcd75dc571d7`, PID 2516426. Its frozen Rust source is `.verification/frontier-candidate-ready/source/rust-server/src/main.rs`, SHA-256 `c49d31035d73d61e9de365050977d2c0cffa6919dd6c607067420f5f8a628497`. The locked libraries are Quinn 0.11.11, quinn-proto 0.11.17 and rustls 0.23.43. Public library source was read from the exact cached published crates, with official documentation/source checked online.

The current fake-peer binary is `81ce31c2c9ba55fa0719655b9356d963147e5df1d9d4bdf335216ffd74c0bf89`. It queues actual absent-cache terrain requests immediately after DIM, before WORLD, and associates the returned world identity before committing terrain. This does not authenticate a peer early or alter the simultaneous-100 gate.

Existing observer evidence at 1778.9 seconds: 60 current / 62 peak world-ACK-live, 42 complete 597-node caches, 28,402 cached nodes total. Saved edits remain zero. Native RSS is 37.4 MB; its cgroup is 380.2 MB, peak 410.0 MB, ceiling 999,997,440 bytes, swap zero, OOM kills zero. These are a timestamped snapshot, not a completed pressure result.

## Confirmed accept and recovery behavior

The native calls `incoming.await`, which is Quinn's direct accept path. It does not call `Incoming.retry()`. Stateless Retry is an explicit application choice, not an automatic step in this code. [Exact Quinn Incoming source](https://raw.githubusercontent.com/quinn-rs/quinn/quinn-0.11.11/quinn/src/incoming.rs).

Normal QUIC anti-amplification protection remains active: before address validation the server's transmitted bytes cannot exceed three times its received bytes. Exact proto `connection/paths.rs:151–155` checks this; `connection/mod.rs:1866–1869` stops its loss timer when no further transmit can pass that check. An authenticated Handshake packet validates the path at `mod.rs:2528`, before the check for complete TLS. Existing client-side UDP/ACK statistics cannot establish that a particular server was anti-amplification blocked. [QUIC address validation](https://www.rfc-editor.org/rfc/rfc9000.html#section-8.1).

The package disables default Quinn features and does not enable `bloom`. The exact library's default address-token configuration therefore emits zero NEW_TOKENs and uses NoneTokenLog. Enabling the ordinary library feature could help subsequent connections to a known server, with its existing replay protection; it cannot provide a token for a never-connected cold client. [Quinn validation token behavior](https://docs.rs/quinn/latest/quinn/struct.ValidationTokenConfig.html).

PTO follows standard exponential backoff, reset by received acknowledgements. The effective idle expiry uses the larger of the negotiated timeout and three current PTOs; configured 300 seconds is consequently not a fixed maximum lifetime for a severe-loss handshake. Keepalive is only armed after establishment. Repeated sent keepalives do not independently reset idle forever: an authenticated receive must permit the next ack-eliciting send to reset it. Exact references: proto `mod.rs:1801`, `1951–1969`, and `packet_builder.rs:218–225`. [QUIC loss detection](https://www.rfc-editor.org/rfc/rfc9002.html#section-6.2), [QUIC idle timeout](https://www.rfc-editor.org/rfc/rfc9000.html#section-10.1).

## Why merely enabling server 0.5-RTT is insufficient

Quinn exposes an early server connection through `Connecting.into_0rtt()`, but the current client sends DIM in ordinary 1-RTT after its TLS future completes. Exact proto `mod.rs:2313–2316` discards short-header packets while server handshake state remains active; `2630–2633` permits early application frames only in actual 0-RTT packets. The server needs DIM before WORLD. An early server handle therefore cannot read the present cold DIM while the client's Finished is still missing. Changing only `incoming.await` would not remove this dependency. [Quinn early-connection API and conditions](https://raw.githubusercontent.com/quinn-rs/quinn/quinn-0.11.11/quinn/src/connection.rs).

True 0-RTT needs a previous TLS ticket, early-data configuration, rejected-stream recovery and replay-aware operation ownership. It is disabled in the current custom TLS configurations. The terrain GET is read-only, but client association and the 100 authenticated-live gate still cannot be inferred from early data. This adds complexity and does not improve the first-ever cold handshake. [rustls server early-data configuration](https://docs.rs/rustls/0.23.43/rustls/server/struct.ServerConfig.html).

## Separate fidelity issue: TLS cache ownership

Ordinary TLS resumption is already enabled by rustls defaults; accepted resumption has not been measured. All virtual actors currently share one Endpoint/client configuration and the same SNI `voxy.local`. The rustls TLS ticket store is keyed by server name, so later retries can consume another actor's received ticket. Cold terrain caches do not prove independent cold TLS stores. This is potentially optimistic workload evidence, not a reason to weaken the gate. A future faithful driver should give each actor its own ordinary resumption/token state while sharing only immutable certificate and transport configuration. [rustls client defaults and resumption ownership](https://docs.rs/rustls/0.23.43/rustls/client/struct.ClientConfig.html).

The real Common.Link rebuilds a Kwik connection without supplying a previous session ticket. The installed Kwik 0.10.10 public API exposes `getNewSessionTickets()` and builder `sessionTicket(...)`, verified directly with javap. Keeping a ticket for the same pinned server would be a small legitimate reconnect optimization, not a cold-handshake cure. The official current API corroborates those methods; its master source is not a substitute for the installed-version check. [Official Kwik API source](https://raw.githubusercontent.com/ptrd/kwik/master/core/src/main/java/tech/kwik/core/QuicClientConnection.java).

## Observed stalls and next evidence

`network-delay-observation.json` proves that at 787 seconds peer 90 had received zero of 23 reverse UDP datagrams; peer 98 received one of 17, and peer 99 received three of 25. Those are real aggregate proxy deliveries, not TLS flight/CRYPTO counts. `handshake-review-observation.json` preserves a later natural transition: peer 58 reached WORLD and acquired 117 cached nodes, while other peers remained in handshake or WORLD wait. A client TLS success does not prove server TLS completion.

The current snapshots do not identify first-flight composition, server path validation, accepted resumption, or the server's greeting-enqueue phase. The next observational addition should record those phases and public transport counters, and classify existing proxy packet headers before loss without storing payloads or TLS secrets. Counts must account for coalesced QUIC packets and distinguish attempts. Keep the currently running rig unchanged.

Do not force Retry, add artificial traffic, cap PTO, change idle timeouts, or tune arbitrary windows/rates from these observations. Retry adds another flight and is useful only if measured address-validation savings justify it. The already-small single certificate (355 bytes) also makes a large certificate-flight explanation unproven. The smallest credible improvements are independent per-peer resumption ownership and optional ordinary reconnect-ticket/address-token reuse, followed by measured server phase evidence. None has yet been shown to achieve the 100/300 requirement.
