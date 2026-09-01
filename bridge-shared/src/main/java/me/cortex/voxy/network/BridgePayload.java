package me.cortex.voxy.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** One ordered chunk of the Voxy byte stream relayed over the Minecraft connection. */
public record BridgePayload(long streamId, byte action, byte[] data)
        implements CustomPacketPayload {
    public static final byte OPEN = 0;
    public static final byte DATA = 1;
    public static final byte CLOSE = 2;
    public static final int MAX_CHUNK = 32 * 1024;
    public static final Type<BridgePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath("voxy", "rust_bridge"));
    public static final StreamCodec<RegistryFriendlyByteBuf, BridgePayload> CODEC = new StreamCodec<>() {
        @Override
        public BridgePayload decode(RegistryFriendlyByteBuf input) {
            return new BridgePayload(input.readLong(), input.readByte(),
                    input.readByteArray(MAX_CHUNK));
        }

        @Override
        public void encode(RegistryFriendlyByteBuf output, BridgePayload payload) {
            output.writeLong(payload.streamId);
            output.writeByte(payload.action);
            output.writeByteArray(payload.data);
        }
    };

    public BridgePayload {
        if (streamId == 0 || data == null || data.length > MAX_CHUNK) {
            throw new IllegalArgumentException("invalid Voxy bridge payload");
        }
        if (action == DATA ? data.length == 0
                : (action != OPEN && action != CLOSE) || data.length != 0) {
            throw new IllegalArgumentException("invalid Voxy bridge action payload");
        }
    }

    public static BridgePayload open(long streamId) {
        return new BridgePayload(streamId, OPEN, new byte[0]);
    }

    public static BridgePayload data(long streamId, byte[] data) {
        return new BridgePayload(streamId, DATA, data);
    }

    public static BridgePayload close(long streamId) {
        return new BridgePayload(streamId, CLOSE, new byte[0]);
    }

    @Override
    public Type<BridgePayload> type() {
        return TYPE;
    }
}
