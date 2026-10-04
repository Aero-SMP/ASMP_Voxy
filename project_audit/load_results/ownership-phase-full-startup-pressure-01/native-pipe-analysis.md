# Native stdout coupling during startup

This is a read-only trace of the loaded native `7457e3e4…` and server jar `8678010a…`, while the unchanged phase-instrumented pressure run remained active. The frozen Rust source hash is `c49d31035d73d61e9de365050977d2c0cffa6919dd6c607067420f5f8a628497`; the matching corrected Java source hash is `dbf246faf831e628e02a8bc7b90332ea05f0042692deb55eb8f7a06cb01657a8`.

The Rust connection task awaits the handshake, accepts one bidirectional stream, reads and validates the dimension, then writes the 16-byte world identity. This happens before reading terrain GET commands, opening records, or taking the publication jobs mutex. The world-ACK wait therefore has no direct publication dependency.

Each terrain GET opens its record and then enters `request`. The first request for a pending ticket holds the async jobs mutex while synchronously printing `VOXY_NEED`; existing pending tickets share a watch sender. A full output pipe could block the executing runtime worker while holding this mutex. Other tasks awaiting this Tokio mutex yield rather than synchronously block their worker. Completion processing also needs the same mutex.

The Java supervisor reads the native process's merged stdout/stderr. For `VOXY_NEED` it checks record presence and adds work to a `PriorityBlockingQueue`; the separate publisher owns reading and reducing saved chunks. After a build attempt, the publisher synchronously writes and flushes the completion ticket to native stdin. Other native log lines are forwarded to Java stdout. Those synchronous I/O operations are possible coupling points; source inspection alone does not establish that they are blocking during this run.

The actual native snapshot at epoch `1791099841.541` contained 41 `futex_do_wait` threads, one `do_epoll_wait`, and one `anon_pipe_read`; none was in a pipe-write wait. Native stdout and stderr both targeted `pipe:[247736612]`. The Java snapshot at epoch `1791099894.810` showed the native supervisor in `anon_pipe_read` and the saved-terrain publisher in `futex_do_wait`. Both process start identities matched their original starts. These instantaneous observations do not support a full native stdout pipe or total runtime worker starvation. They cannot exclude brief blocking between samples.

Receipts: `native-worker-observation.json` and `java-native-pipe-thread-observation.json`. No process attach, launch-argument inspection, transport changes, probes, restarts or deployments were performed. The 100-live barrier remained unpassed and the saved-mutation clock had not started.
