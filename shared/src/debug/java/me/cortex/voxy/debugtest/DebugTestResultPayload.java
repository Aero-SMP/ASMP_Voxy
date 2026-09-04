package me.cortex.voxy.debugtest;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.UUID;

/** Fixed-layout client-to-server result or cumulative trace observation. */
public record DebugTestResultPayload(
        DebugTestProtocol.ResultKind kind,
        UUID runId,
        long stepId,
        long connectionEpoch,
        DebugTestProtocol.Failure failure,
        long firstFrame,
        long lastFrame,
        long coalescedSamples,
        DebugTestSnapshot snapshot) implements CustomPacketPayload {
    public static final Type<DebugTestResultPayload> TYPE = new Type<>(
            DebugTestProtocol.RESULT_ID);
    public static final StreamCodec<RegistryFriendlyByteBuf, DebugTestResultPayload> CODEC =
            new StreamCodec<>() {
                @Override
                public DebugTestResultPayload decode(RegistryFriendlyByteBuf input) {
                    int version = input.readVarInt();
                    if (version != DebugTestProtocol.VERSION) {
                        throw new IllegalArgumentException("debug-test protocol version " + version);
                    }
                    return new DebugTestResultPayload(
                            DebugTestProtocol.ResultKind.fromWire(input.readUnsignedByte()),
                            input.readUUID(), input.readVarLong(), input.readVarLong(),
                            DebugTestProtocol.Failure.fromWire(input.readUnsignedByte()),
                            input.readVarLong(), input.readVarLong(), input.readVarLong(),
                            DebugTestSnapshot.decode(input));
                }

                @Override
                public void encode(RegistryFriendlyByteBuf output,
                                   DebugTestResultPayload payload) {
                    output.writeVarInt(DebugTestProtocol.VERSION);
                    output.writeByte(payload.kind.wireId());
                    output.writeUUID(payload.runId);
                    output.writeVarLong(payload.stepId);
                    output.writeVarLong(payload.connectionEpoch);
                    output.writeByte(payload.failure.wireId());
                    output.writeVarLong(payload.firstFrame);
                    output.writeVarLong(payload.lastFrame);
                    output.writeVarLong(payload.coalescedSamples);
                    payload.snapshot.encode(output);
                }
            };

    public DebugTestResultPayload {
        if (kind == null || runId == null || failure == null || snapshot == null
                || stepId < 0 || connectionEpoch <= 0 || firstFrame < 0 || lastFrame < 0
                || coalescedSamples < 0 || lastFrame < firstFrame) {
            throw new IllegalArgumentException("invalid debug-test result");
        }
    }

    @Override public Type<DebugTestResultPayload> type() { return TYPE; }
}
