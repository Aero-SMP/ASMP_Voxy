package me.cortex.voxy.network;

import io.netty.util.NetUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.net.IDN;
import java.util.Locale;

/** Transport request or explicit direct/relayed endpoint advertisement. */
public record TransportPayload(byte mode, String host, int port)
        implements CustomPacketPayload {
    public static final String CHANNEL = "voxy";
    public static final byte REQUEST = 0;
    public static final byte MINECRAFT = 1;
    public static final byte DIRECT = 2;
    public static final int MAX_HOST_LENGTH = 255;
    public static final int DEFAULT_DIRECT_PORT = 25587;

    public static final Type<TransportPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath("voxy", "transport"));
    public static final StreamCodec<RegistryFriendlyByteBuf, TransportPayload> CODEC = new StreamCodec<>() {
        @Override
        public TransportPayload decode(RegistryFriendlyByteBuf input) {
            return new TransportPayload(input.readByte(),
                    input.readUtf(MAX_HOST_LENGTH), input.readUnsignedShort());
        }

        @Override
        public void encode(RegistryFriendlyByteBuf output, TransportPayload payload) {
            output.writeByte(payload.mode);
            output.writeUtf(payload.host, MAX_HOST_LENGTH);
            output.writeShort(payload.port);
        }
    };

    public TransportPayload {
        if (host == null || host.length() > MAX_HOST_LENGTH || !host.equals(host.trim())) {
            throw new IllegalArgumentException("invalid Voxy transport payload");
        }
        boolean valid = switch (mode) {
            case REQUEST, MINECRAFT -> host.isEmpty() && port == 0;
            case DIRECT -> port > 0 && port <= 0xffff
                    && host.equals(canonicalDirectHost(host));
            default -> false;
        };
        if (!valid) throw new IllegalArgumentException("invalid Voxy transport mode or endpoint");
    }

    public static TransportPayload request() {
        return new TransportPayload(REQUEST, "", 0);
    }

    public static TransportPayload minecraft() {
        return new TransportPayload(MINECRAFT, "", 0);
    }

    public static TransportPayload direct(String host, int port) {
        return new TransportPayload(DIRECT, canonicalDirectHost(host), port);
    }

    /**
     * Validates and canonicalizes an explicitly advertised endpoint without performing DNS
     * discovery. Empty means reuse the authenticated Minecraft peer address.
     */
    public static String canonicalDirectHost(String configured) {
        if (configured == null || !configured.equals(configured.trim())) {
            throw new IllegalArgumentException("direct host contains surrounding whitespace");
        }
        if (configured.isEmpty()) return "";

        boolean bracketed = configured.startsWith("[") && configured.endsWith("]");
        if (configured.startsWith("[") != configured.endsWith("]")) {
            throw new IllegalArgumentException("direct host has mismatched IPv6 brackets");
        }
        String literal = bracketed
                ? configured.substring(1, configured.length() - 1) : configured;
        if (literal.indexOf('%') >= 0) {
            throw new IllegalArgumentException("direct host cannot contain an IPv6 scope");
        }
        byte[] address = NetUtil.createByteArrayFromIpAddressString(literal);
        if (address != null) return NetUtil.bytesToIpAddress(address);
        if (bracketed || configured.indexOf(':') >= 0) {
            throw new IllegalArgumentException("direct host is not a valid IPv6 address");
        }

        final String ascii;
        try {
            ascii = IDN.toASCII(configured, IDN.USE_STD3_ASCII_RULES)
                    .toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("direct host is not a valid DNS hostname", invalid);
        }
        int contentLength = ascii.endsWith(".") ? ascii.length() - 1 : ascii.length();
        if (contentLength == 0 || contentLength > 253) {
            throw new IllegalArgumentException("direct DNS hostname has an invalid length");
        }
        String[] labels = ascii.substring(0, contentLength).split("\\.", -1);
        for (String label : labels) {
            if (label.isEmpty() || label.length() > 63
                    || label.charAt(0) == '-' || label.charAt(label.length() - 1) == '-') {
                throw new IllegalArgumentException("direct DNS hostname has an invalid label");
            }
        }
        return ascii;
    }

    @Override
    public Type<TransportPayload> type() {
        return TYPE;
    }
}
