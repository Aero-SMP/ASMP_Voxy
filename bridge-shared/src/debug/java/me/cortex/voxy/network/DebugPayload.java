package me.cortex.voxy.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** A bounded debug line from the client, or an empty server acknowledgement. */
public record DebugPayload(int sequence, long sentNanos, String message) implements CustomPacketPayload {
    public static final int MAX_MESSAGE_LENGTH = 2048;
    public static final Type<DebugPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath("voxy", "debug"));
    public static final StreamCodec<RegistryFriendlyByteBuf, DebugPayload> CODEC = new StreamCodec<>() {
        @Override
        public DebugPayload decode(RegistryFriendlyByteBuf input) {
            return new DebugPayload(input.readVarInt(), input.readLong(), input.readUtf(MAX_MESSAGE_LENGTH));
        }

        @Override
        public void encode(RegistryFriendlyByteBuf output, DebugPayload payload) {
            output.writeVarInt(payload.sequence);
            output.writeLong(payload.sentNanos);
            output.writeUtf(payload.message, MAX_MESSAGE_LENGTH);
        }
    };

    @Override
    public Type<DebugPayload> type() {
        return TYPE;
    }
}
