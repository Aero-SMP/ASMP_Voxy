package com.aerosmp.voxy.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** An empty request, or a certificate pin authenticated by the Minecraft connection. */
public record Endpoint(String host, int port, byte[] certificate) implements CustomPacketPayload {
    public static final Type<Endpoint> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("voxy", "rewrite_endpoint"));
    public static final StreamCodec<RegistryFriendlyByteBuf, Endpoint> CODEC = new StreamCodec<>() {
        public Endpoint decode(RegistryFriendlyByteBuf in) {
            String host = in.readUtf(255); int port = in.readUnsignedShort();
            byte[] pin = new byte[32]; in.readBytes(pin); return new Endpoint(host, port, pin);
        }
        public void encode(RegistryFriendlyByteBuf out, Endpoint value) {
            out.writeUtf(value.host, 255); out.writeShort(value.port); out.writeBytes(value.certificate);
        }
    };
    public Endpoint {
        if (host == null || host.length() > 255 || !host.equals(host.strip()) || host.indexOf('/') >= 0
                || port < 0 || port > 65535 || certificate == null || certificate.length != 32
                || (port == 0 && (!host.isEmpty() || !java.util.Arrays.equals(certificate, new byte[32]))))
            throw new IllegalArgumentException("Invalid terrain endpoint");
        certificate = certificate.clone();
    }
    @Override public byte[] certificate() { return certificate.clone(); }
    @Override public Type<Endpoint> type() { return TYPE; }
    public static Endpoint request() { return new Endpoint("", 0, new byte[32]); }
}
