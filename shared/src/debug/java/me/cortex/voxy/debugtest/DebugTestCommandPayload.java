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
        String option, String value) implements CustomPacketPayload {
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
                    output.writeUtf(payload.option, 128);
                    output.writeUtf(payload.value, 128);
                }
            };

    public DebugTestCommandPayload {
        if (option == null || value == null || option.length() > 128 || value.length() > 128
                || (kind != DebugTestProtocol.CommandKind.SHADER_OPTION && kind != DebugTestProtocol.CommandKind.DOWNLOAD_POLICY
                && (!option.isEmpty() || !value.isEmpty()))) {
            throw new IllegalArgumentException("invalid debug option fields");
        }
        if (kind == null || runId == null || dimension == null
                || dimension.length() > DebugTestProtocol.MAX_DIMENSION_LENGTH
                || stepId < 0 || connectionEpoch <= 0 || timeoutNanos < 0
                || durationNanos < 0 || cadenceNanos < 0
                || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || !Float.isFinite(yaw) || !Float.isFinite(pitch)) {
            throw new IllegalArgumentException("invalid debug-test command");
        }
        if (kind == DebugTestProtocol.CommandKind.DOWNLOAD_POLICY
                || kind == DebugTestProtocol.CommandKind.OPEN_SETTINGS || kind == DebugTestProtocol.CommandKind.CLOSE_SETTINGS) {
            if (!dimension.isEmpty() || x != 0 || y != 0 || z != 0 || yaw != 0 || pitch != 0
                    || timeoutNanos != 0 || durationNanos != 0 || cadenceNanos != 0)
                throw new IllegalArgumentException("settings command has unused fields");
            if (kind == DebugTestProtocol.CommandKind.DOWNLOAD_POLICY) downloadPolicyValue(option, value);
        }
    }

    /** Exact units match the settings: kbps, decimal bytes, seconds, displayed Minecraft chunks. */
    public static long downloadPolicyValue(String option, String value) {
        if (option.equals("storage") && value.equals("entire")) return Long.MAX_VALUE;
        if (!value.matches("[0-9]+")) throw new IllegalArgumentException("download policy value must be a positive integer");
        long number = Long.parseLong(value);
        boolean valid = switch (option) {
            case "bandwidth" -> number >= 100 && number <= 10_000;
            case "storage" -> number >= 100_000_000;
            case "interval" -> number >= 1 && number <= 60;
            case "render_distance" -> number >= 20 && number <= 2048 && (number & 1) == 0;
            default -> false;
        };
        if (!valid) throw new IllegalArgumentException("invalid download policy option or range");
        return number;
    }

    @Override public Type<DebugTestCommandPayload> type() { return TYPE; }
}
