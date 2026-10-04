# GPU reclamation and updater review

Read-only source review on 2026-10-04. No builds, tests, native execution, deployment, or client/server operations were performed for this review.

Reviewed source SHA-256:

- `client/java/VoxyClient.java`: `03551afcebbfa640062b51cef6e4ec7f2e4f8c44d4b6753ecde77a0d2050fb34`
- `updater/AutoUpdater.java`: `9dbf3eccfc69dfcdc2d96fbdc62924871a5555c2a68d794faba17ad637051bec`

## GPU ownership and cancellation

The render callback retires eligible inactive/prepared geometry, creates a fence after deletion commands, flushes its creating context, and transfers the fence through `CompletableFuture<Long>`. The worker waits and deletes the transferred fence. If close already completed the future with zero, the callback deletes its unclaimed fence instead. `CompletableFuture.complete` has one successful winner, so these normal paths do not double-delete the fence. [JDK completion contract](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/CompletableFuture.html)

Current source fixes both cancellation races: close captures the volatile future once, avoiding a second read becoming null; the worker checks `closed` immediately after publishing a new future, covering close that happened before publication. The worker can therefore leave `join` even if the queued render callback never runs, releasing `meshLock` so cleanup can proceed.

If `makeRoom` cannot retire enough permitted geometry, it parks the candidate and returns false. The callback flushes any partial deletions and completes with zero; the worker closes its CPU meshes and returns without allocating new GPU buffers. No deletion fence is needed for that path because no replacement allocation follows. Eligible retirement remains outside the selected coverage frontier.

`makeRoom` subtracts retired geometry bytes from the measured shortfall rather than repeatedly trusting a driver counter after each deletion. This is admission based on a memory hint, not a guarantee of physical allocation: other GL users and driver accounting can change available memory. Upload failure still retains preceding selected coverage.

The shared worker context unbinds `GL_ARRAY_BUFFER` in its finally block. This matters: deleting a buffer in another context does not clear this context's bindings, and bound/attached objects can retain their storage. [OpenGL 4.6 core specification, sections 5.1.3 and 6.1](https://registry.khronos.org/OpenGL/specs/gl/glspec46.core.pdf)

`Long.MAX_VALUE` is a valid unsigned-nanosecond timeout for `glClientWaitSync`; `MAX_SERVER_WAIT_TIMEOUT` concerns `glWaitSync`, not this client wait. Shared-context sync objects are supported. The explicit flush in the fence's creating context removes the application-side unflushed-fence deadlock. After ownership transfers, close cannot interrupt the native wait; completion still depends on GPU/driver progress. No render-thread client wait is introduced. [ARB_sync specification, sections 5.2.1–5.2.2 and issue 1](https://registry.khronos.org/OpenGL/extensions/ARB/ARB_sync.txt)

Callback exceptions complete the future exceptionally; worker failure cleanup closes its GPU/CPU resources and releases the shared context. One error-path distinction remains useful: `glFenceSync` returning zero after successful reclamation means fence creation failed and generated a GL error. Current source classifies it as denial/close, leaving the error unreported and potentially visible to the next upload's `glGetError`. Selected coverage remains intact. [ARB_sync fence creation rules](https://registry.khronos.org/OpenGL/extensions/ARB/ARB_sync.txt)

## Windows updater complexity

The reviewed upstream OpenJDK 21 Windows implementation of `getProcessPids0` takes a system-wide Toolhelp snapshot and walks all process entries, filtering matching parent IDs before producing child handles. Consequently, `parent.children()` does **not** establish O(children) native enumeration time. [Native source, lines 199–302](https://github.com/openjdk/jdk21u/blob/master/src/java.base/windows/native/libjava/ProcessHandleImpl_win.c#L199-L302)

The preceding implementation called `parent()` while filtering host-wide `allProcesses()`. Windows `getParentPid0` itself takes another system-wide snapshot and scans for that process. Repeating this across host processes admits quadratic enumeration work in the host process count. This is a source-based complexity inference, not a measured client timing result. [Native parent lookup, lines 153–190](https://github.com/openjdk/jdk21u/blob/master/src/java.base/windows/native/libjava/ProcessHandleImpl_win.c#L153-L190)

The safe improvement claim is fewer Java process handles/filter operations and removal of repeated per-process native parent lookups. The direct-child/executable matching rule remains. These upstream sources were reviewed; byte-for-byte identity with the laptop's installed Windows JDK was not established. [Java children implementation, lines 395–429](https://github.com/openjdk/jdk21u/blob/master/src/java.base/share/classes/java/lang/ProcessHandleImpl.java#L395-L429)

The final candidate distinguishes a zero-return reclamation fence from ordinary space denial, consumes the GL error into an exception, and completes the worker future exceptionally. The joint offline buildAll completed successfully; deployment and visual verification remain separate gates.
