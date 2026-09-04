package me.cortex.voxy.server;

import net.neoforged.bus.api.IEventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HexFormat;

/** Server-controller diagnostics compiled only into the debug server JAR. */
final class ServerDebug {
    private static final Logger LOGGER = LoggerFactory.getLogger("Voxy Server Debug");

    private ServerDebug() {}

    static void initialize(IEventBus modBus) {
        String version = VoxyServer.class.getPackage().getImplementationVersion();
        LOGGER.info("Voxy version {} role=server debug=true",
                version == null ? "<UNKNOWN>" : version);
    }

    static void rustStarting() {
        LOGGER.info("VOXY_RUST state=STARTING");
    }

    static void rustReady(RustBackend.ReadyRecord ready) {
        LOGGER.info("VOXY_RUST state=READY udpPort={} alpn={} certSha256={}",
                ready.udpPort(), ready.alpn(),
                HexFormat.of().formatHex(ready.certificateSha256()));
    }

    static void rustExited(int exit, boolean restarting) {
        LOGGER.warn("VOXY_RUST state=EXITED exitCode={} restarting={}", exit, restarting);
    }

    static void rustFailed(Throwable failure, boolean restarting) {
        LOGGER.error("VOXY_RUST state=FAILED restarting={}", restarting, failure);
    }

    static void endpointAdvertised(String player, String host, int port, String alpn) {
        LOGGER.info("VOXY_ENDPOINT state=ADVERTISED player={} host={} udpPort={} alpn={}",
                player, host.isEmpty() ? "<minecraft-peer>" : host, port, alpn);
    }
}
