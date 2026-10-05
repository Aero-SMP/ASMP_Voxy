package me.cortex.voxy.network;

import io.netty.util.NetUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.net.IDN;
import java.util.Arrays;
import java.util.Locale;

/** Empty endpoint request or the exact QUIC endpoint authenticated by Minecraft. */
public record QuicEndpointPayload(String host, int udpPort, String alpn,
                                  byte[] certificateSha256, int bandwidthKbps,
                                  byte[] routeToken) implements CustomPacketPayload {
    public static final String REGISTRATION_VERSION = "voxy-cache-start";
    public static final int MAX_HOST_LENGTH = 255;
    public static final int MAX_ALPN_LENGTH = 255;
    public static final int CERTIFICATE_SHA256_BYTES = 32;

    public static final Type<QuicEndpointPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath("voxy", "quic_endpoint"));
    public static final StreamCodec<RegistryFriendlyByteBuf, QuicEndpointPayload> CODEC =
            new StreamCodec<>() {
                @Override
                public QuicEndpointPayload decode(RegistryFriendlyByteBuf input) {
                    String host = input.readUtf(MAX_HOST_LENGTH);
                    int udpPort = input.readUnsignedShort();
                    String alpn = input.readUtf(MAX_ALPN_LENGTH);
                    byte[] fingerprint = new byte[CERTIFICATE_SHA256_BYTES];
                    input.readBytes(fingerprint);
                    int bandwidth = input.readUnsignedShort();
                    byte[] token = new byte[32];
                    input.readBytes(token);
                    return new QuicEndpointPayload(host, udpPort, alpn, fingerprint, bandwidth, token);
                }

                @Override
                public void encode(RegistryFriendlyByteBuf output, QuicEndpointPayload payload) {
                    output.writeUtf(payload.host, MAX_HOST_LENGTH);
                    output.writeShort(payload.udpPort);
                    output.writeUtf(payload.alpn, MAX_ALPN_LENGTH);
                    output.writeBytes(payload.certificateSha256);
                    output.writeShort(payload.bandwidthKbps);
                    output.writeBytes(payload.routeToken);
                }
            };

    public QuicEndpointPayload {
        if (host == null || host.length() > MAX_HOST_LENGTH || !host.equals(host.trim())) {
            throw new IllegalArgumentException("invalid Voxy QUIC host");
        }
        if (alpn == null || alpn.length() > MAX_ALPN_LENGTH || !isValidAlpn(alpn)) {
            throw new IllegalArgumentException("invalid Voxy QUIC ALPN");
        }
        certificateSha256 = certificateSha256 == null ? null : certificateSha256.clone();
        if (certificateSha256 == null
                || certificateSha256.length != CERTIFICATE_SHA256_BYTES) {
            throw new IllegalArgumentException("invalid Voxy QUIC certificate fingerprint");
        }
        routeToken = routeToken == null ? null : routeToken.clone();
        if (routeToken == null || routeToken.length != 32 || bandwidthKbps < 100 || bandwidthKbps > 20_000)
            throw new IllegalArgumentException("invalid Voxy QUIC route or download bandwidth");

        if (udpPort == 0) {
            if (!host.isEmpty() || !alpn.isEmpty() || !allZero(certificateSha256) || !allZero(routeToken)) {
                throw new IllegalArgumentException("invalid Voxy QUIC endpoint request");
            }
        } else if (udpPort < 0 || udpPort > 0xffff
                || !host.equals(canonicalHost(host)) || alpn.isEmpty() || allZero(routeToken)) {
            throw new IllegalArgumentException("invalid Voxy QUIC endpoint");
        }
    }

    public static QuicEndpointPayload request(int bandwidthKbps) {
        return new QuicEndpointPayload("", 0, "", new byte[CERTIFICATE_SHA256_BYTES], bandwidthKbps, new byte[32]);
    }

    public static QuicEndpointPayload endpoint(String host, int udpPort, String alpn,
                                               byte[] certificateSha256, byte[] routeToken, int bandwidthKbps) {
        return new QuicEndpointPayload(canonicalHost(host), udpPort, alpn, certificateSha256, bandwidthKbps, routeToken);
    }

    public boolean isRequest() {
        return this.udpPort == 0;
    }

    @Override
    public byte[] certificateSha256() {
        return this.certificateSha256.clone();
    }

    @Override
    public byte[] routeToken() { return this.routeToken.clone(); }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof QuicEndpointPayload endpoint
                && this.udpPort == endpoint.udpPort && this.host.equals(endpoint.host)
                && this.alpn.equals(endpoint.alpn)
                && this.bandwidthKbps == endpoint.bandwidthKbps
                && Arrays.equals(this.certificateSha256, endpoint.certificateSha256)
                && Arrays.equals(this.routeToken, endpoint.routeToken);
    }

    @Override
    public int hashCode() {
        int hash = 31 * this.host.hashCode() + this.udpPort;
        hash = 31 * hash + this.alpn.hashCode();
        hash = 31 * hash + Arrays.hashCode(this.certificateSha256);
        hash = 31 * hash + this.bandwidthKbps;
        return 31 * hash + Arrays.hashCode(this.routeToken);
    }

    /** Empty means reuse the authenticated Minecraft peer address. */
    public static String canonicalHost(String configured) {
        if (configured == null || !configured.equals(configured.trim())) {
            throw new IllegalArgumentException("QUIC host contains surrounding whitespace");
        }
        if (configured.isEmpty()) return "";

        boolean bracketed = configured.startsWith("[") && configured.endsWith("]");
        if (configured.startsWith("[") != configured.endsWith("]")) {
            throw new IllegalArgumentException("QUIC host has mismatched IPv6 brackets");
        }
        String literal = bracketed
                ? configured.substring(1, configured.length() - 1) : configured;
        if (literal.indexOf('%') >= 0) {
            throw new IllegalArgumentException("QUIC host cannot contain an IPv6 scope");
        }
        byte[] address = NetUtil.createByteArrayFromIpAddressString(literal);
        if (address != null) return NetUtil.bytesToIpAddress(address);
        if (bracketed || configured.indexOf(':') >= 0) {
            throw new IllegalArgumentException("QUIC host is not a valid IPv6 address");
        }

        final String ascii;
        try {
            ascii = IDN.toASCII(configured, IDN.USE_STD3_ASCII_RULES)
                    .toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("QUIC host is not a valid DNS hostname", invalid);
        }
        int contentLength = ascii.endsWith(".") ? ascii.length() - 1 : ascii.length();
        if (contentLength == 0 || contentLength > 253) {
            throw new IllegalArgumentException("QUIC DNS hostname has an invalid length");
        }
        String[] labels = ascii.substring(0, contentLength).split("\\.", -1);
        for (String label : labels) {
            if (label.isEmpty() || label.length() > 63
                    || label.charAt(0) == '-' || label.charAt(label.length() - 1) == '-') {
                throw new IllegalArgumentException("QUIC DNS hostname has an invalid label");
            }
        }
        return ascii;
    }

    /** Empty is reserved for endpoint requests; advertised ALPN values must also be nonempty. */
    public static boolean isValidAlpn(String value) {
        if (value == null || value.length() > MAX_ALPN_LENGTH) return false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < 0x21 || character > 0x7e) return false;
        }
        return true;
    }

    private static boolean allZero(byte[] value) {
        for (byte element : value) if (element != 0) return false;
        return true;
    }

    @Override
    public Type<QuicEndpointPayload> type() {
        return TYPE;
    }
}
