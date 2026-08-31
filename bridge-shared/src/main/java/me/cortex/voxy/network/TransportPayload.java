package me.cortex.voxy.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Request or response for the Voxy transport selected by the authenticated Minecraft server. */
public record TransportPayload(int protocolVersion, byte mode, String host, int port)
        implements CustomPacketPayload {
    public static final int PROTOCOL_VERSION = 6;
    public static final byte REQUEST = 0;
    public static final byte MINECRAFT = 1;
    public static final byte DIRECT = 2;
    public static final int MAX_HOST_LENGTH = 255;

    public static final Type<TransportPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath("voxy", "transport"));
    public static final StreamCodec<RegistryFriendlyByteBuf, TransportPayload> CODEC = new StreamCodec<>() {
        @Override
        public TransportPayload decode(RegistryFriendlyByteBuf input) {
            return new TransportPayload(input.readVarInt(), input.readByte(),
                    input.readUtf(MAX_HOST_LENGTH), input.readUnsignedShort());
        }

        @Override
        public void encode(RegistryFriendlyByteBuf output, TransportPayload payload) {
            output.writeVarInt(payload.protocolVersion);
            output.writeByte(payload.mode);
            output.writeUtf(payload.host, MAX_HOST_LENGTH);
            output.writeShort(payload.port);
        }
    };

    public static TransportPayload request() {
        return new TransportPayload(PROTOCOL_VERSION, REQUEST, "", 0);
    }

    @Override
    public Type<TransportPayload> type() {
        return TYPE;
    }
}
