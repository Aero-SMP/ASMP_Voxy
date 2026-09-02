package me.cortex.voxy.server;

/** No-op facade replaced by the compile-time debug implementation in debug server JARs. */
final class ServerDebug {
    private ServerDebug() {}

    static void initialize() {}
    static void rustStarting() {}
    static void rustReady(RustBackend.ReadyRecord ready) {}
    static void rustExited(int exit, boolean restarting) {}
    static void rustFailed(Throwable failure, boolean restarting) {}
    static void endpointAdvertised(String player, String host, int port, String alpn) {}
}
