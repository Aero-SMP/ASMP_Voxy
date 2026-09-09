package me.cortex.voxy.server;

import me.cortex.voxy.network.QuicEndpointPayload;

/** Public endpoint only. Empty host/zero port inherit the Minecraft peer/bound UDP port. */
record AdvertisedAddress(String host, int udpPortOverride) {
    static AdvertisedAddress parse(String configured) {
        if (configured == null || !configured.equals(configured.trim())) {
            throw new IllegalArgumentException("quic.advertise must be an address without surrounding whitespace");
        }
        String host = configured;
        String port = null;
        if (configured.startsWith("[")) {
            int end = configured.indexOf(']');
            if (end < 0 || (end + 1 < configured.length() && configured.charAt(end + 1) != ':')) {
                throw new IllegalArgumentException("quic.advertise requires [IPv6] or [IPv6]:port");
            }
            host = configured.substring(0, end + 1);
            if (end + 1 < configured.length()) port = configured.substring(end + 2);
        } else {
            int colon = configured.indexOf(':');
            if (colon >= 0) {
                host = configured.substring(0, colon);
                port = configured.substring(colon + 1);
            }
        }
        int number = 0;
        if (port != null) {
            if (port.isEmpty() || port.length() > 5 || !port.chars().allMatch(c -> c >= '0' && c <= '9')) {
                throw new IllegalArgumentException("quic.advertise requires a port from 1 to 65535; bracket IPv6 addresses");
            }
            number = Integer.parseInt(port);
            if (number == 0 || number > 65535) {
                throw new IllegalArgumentException("quic.advertise port must be from 1 to 65535");
            }
        }
        return new AdvertisedAddress(QuicEndpointPayload.canonicalHost(host), number);
    }
}
