package me.cortex.voxy.server;

import net.neoforged.bus.api.IEventBus;

/** No-op facade replaced by the compile-time debug implementation in debug server JARs. */
final class ServerDebug {
    private ServerDebug() {}

    static void initialize(IEventBus modBus) {}
    static void rustState(RustBackend.Status status) {}
    static void endpointAdvertised(String player, String host, int port, String alpn) {}
}
