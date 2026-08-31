package me.cortex.voxy.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Opaque Voxy protocol bytes. An empty payload closes the relayed byte stream. */
public record BridgePayload(byte[] data) implements CustomPacketPayload {
    public static final int MAX_CHUNK = 32 * 1024;
    public static final Type<BridgePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath("voxy", "rust_bridge"));
    public static final StreamCodec<RegistryFriendlyByteBuf, BridgePayload> CODEC = new StreamCodec<>() {
        @Override
        public BridgePayload decode(RegistryFriendlyByteBuf input) {
            return new BridgePayload(input.readByteArray(MAX_CHUNK));
        }

        @Override
        public void encode(RegistryFriendlyByteBuf output, BridgePayload payload) {
            if (payload.data.length > MAX_CHUNK) throw new IllegalArgumentException("Voxy bridge chunk too large");
            output.writeByteArray(payload.data);
        }
    };

    @Override
    public Type<BridgePayload> type() {
        return TYPE;
    }
}
