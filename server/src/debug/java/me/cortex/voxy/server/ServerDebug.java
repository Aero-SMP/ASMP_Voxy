package me.cortex.voxy.server;

import net.neoforged.bus.api.IEventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HexFormat;

/** Server-controller diagnostics compiled only into the debug server JAR. */
final class ServerDebug {
    private static final Logger LOGGER = LoggerFactory.getLogger("Voxy Server Debug");

    private ServerDebug() {}

    static boolean networkTrace() { return Boolean.getBoolean("voxy.network.trace"); }
    static boolean uncappedBandwidthSupported() { return true; }
    static void nativeLine(Process child, String line) {
        try { LivePressureRoutes.nativeLine(child, line); }
        catch (RuntimeException failure) { LOGGER.warn("Native debug response rejected", failure); }
    }
    static void nativeExited(Process child) { LivePressureRoutes.nativeExited(child); }

    static void initialize(IEventBus modBus) {
        String version = VoxyServer.class.getPackage().getImplementationVersion();
        LOGGER.info("Voxy version {} role=server debug=true",
                version == null ? "<UNKNOWN>" : version);
        LiveServerTestHarness.register(modBus);
        LivePressureRoutes.register();
        LiveTerrainChanges.register();
    }

    static void rustState(RustBackend.Status status) {
        LOGGER.info("VOXY_RUST state={} wanted={} supervisorAlive={} pid={} childAlive={} exitCode={} failure={} ownsExecutable={}",
                status.state(), status.wanted(), status.supervisorAlive(), status.pid(), status.childAlive(),
                status.exitCode(), status.failure(), status.ownsExecutable());
        var ready = status.ready();
        if (ready != null) LOGGER.info("VOXY_RUST endpoint udpPort={} alpn={} certSha256={}",
                ready.udpPort(), ready.alpn(), HexFormat.of().formatHex(ready.certificateSha256()));
    }

    static void endpointAdvertised(String player, String host, int port, String alpn) {
        LOGGER.info("VOXY_ENDPOINT state=ADVERTISED player={} host={} udpPort={} alpn={}",
                player, host.isEmpty() ? "<minecraft-peer>" : host, port, alpn);
    }
}
