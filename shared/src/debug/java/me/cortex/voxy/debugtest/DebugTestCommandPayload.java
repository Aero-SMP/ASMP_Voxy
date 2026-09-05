package me.cortex.voxy.debugtest;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.UUID;

/** Fixed-layout server-to-client operation. Unused numeric fields must be zero. */
public record DebugTestCommandPayload(
        DebugTestProtocol.CommandKind kind,
        UUID runId,
        long stepId,
        long connectionEpoch,
        String dimension,
        double x,
        double y,
        double z,
        float yaw,
        float pitch,
        long timeoutNanos,
        long durationNanos,
        long cadenceNanos,
        String shaderOption, String shaderValue) implements CustomPacketPayload {
    public static final Type<DebugTestCommandPayload> TYPE = new Type<>(
            DebugTestProtocol.COMMAND_ID);
    public static final StreamCodec<RegistryFriendlyByteBuf, DebugTestCommandPayload> CODEC =
            new StreamCodec<>() {
                @Override
                public DebugTestCommandPayload decode(RegistryFriendlyByteBuf input) {
                    int version = input.readVarInt();
                    if (version != DebugTestProtocol.VERSION) {
                        throw new IllegalArgumentException("debug-test protocol version " + version);
                    }
                    return new DebugTestCommandPayload(
                            DebugTestProtocol.CommandKind.fromWire(input.readUnsignedByte()),
                            input.readUUID(), input.readVarLong(), input.readVarLong(),
                            input.readUtf(DebugTestProtocol.MAX_DIMENSION_LENGTH),
                            input.readDouble(), input.readDouble(), input.readDouble(),
                            input.readFloat(), input.readFloat(), input.readVarLong(),
                            input.readVarLong(), input.readVarLong(), input.readUtf(128), input.readUtf(128));
                }

                @Override
                public void encode(RegistryFriendlyByteBuf output,
                                   DebugTestCommandPayload payload) {
                    output.writeVarInt(DebugTestProtocol.VERSION);
                    output.writeByte(payload.kind.wireId());
                    output.writeUUID(payload.runId);
                    output.writeVarLong(payload.stepId);
                    output.writeVarLong(payload.connectionEpoch);
                    output.writeUtf(payload.dimension,
                            DebugTestProtocol.MAX_DIMENSION_LENGTH);
                    output.writeDouble(payload.x);
                    output.writeDouble(payload.y);
                    output.writeDouble(payload.z);
                    output.writeFloat(payload.yaw);
                    output.writeFloat(payload.pitch);
                    output.writeVarLong(payload.timeoutNanos);
                    output.writeVarLong(payload.durationNanos);
                    output.writeVarLong(payload.cadenceNanos);
                    output.writeUtf(payload.shaderOption, 128);
                    output.writeUtf(payload.shaderValue, 128);
                }
            };

    public DebugTestCommandPayload {
        if (shaderOption == null || shaderValue == null || shaderOption.length() > 128 || shaderValue.length() > 128
                || (kind != DebugTestProtocol.CommandKind.SHADER_OPTION && (!shaderOption.isEmpty() || !shaderValue.isEmpty()))) {
            throw new IllegalArgumentException("invalid debug shader option fields");
        }
        if (kind == null || runId == null || dimension == null
                || dimension.length() > DebugTestProtocol.MAX_DIMENSION_LENGTH
                || stepId < 0 || connectionEpoch <= 0 || timeoutNanos < 0
                || durationNanos < 0 || cadenceNanos < 0
                || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || !Float.isFinite(yaw) || !Float.isFinite(pitch)) {
            throw new IllegalArgumentException("invalid debug-test command");
        }
    }

    @Override public Type<DebugTestCommandPayload> type() { return TYPE; }
}
