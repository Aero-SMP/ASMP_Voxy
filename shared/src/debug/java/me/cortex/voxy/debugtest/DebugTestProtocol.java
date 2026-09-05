package me.cortex.voxy.debugtest;

import net.minecraft.resources.ResourceLocation;

/** Constants shared only by matching debug client/server artifacts. */
public final class DebugTestProtocol {
    public static final int VERSION = 1;
    public static final String REGISTRATION_VERSION = "voxy-debug-test-v1";
    public static final int MAX_DIMENSION_LENGTH = 256;
    public static final ResourceLocation COMMAND_ID = ResourceLocation.fromNamespaceAndPath(
            "voxy", "debug_test_command_v1");
    public static final ResourceLocation RESULT_ID = ResourceLocation.fromNamespaceAndPath(
            "voxy", "debug_test_result_v1");

    private DebugTestProtocol() {}

    public enum CommandKind {
        BEGIN_RUN(1), EXPECT_POSE(2), START_TRACE(3), CAPTURE_CHECKPOINT(4),
        CAPTURE_SCREENSHOT(5), END_RUN(6), ABORT_RUN(7), RECONNECT_QUIC(8);

        private final int wireId;
        CommandKind(int wireId) { this.wireId = wireId; }
        public int wireId() { return this.wireId; }
        public static CommandKind fromWire(int value) {
            for (CommandKind kind : values()) if (kind.wireId == value) return kind;
            throw new IllegalArgumentException("unknown debug-test command: " + value);
        }
    }

    public enum ResultKind {
        CLIENT_READY(1), POSE_REACHED(2), POSE_FAILED(3), TRACE_SAMPLE(4),
        CHECKPOINT_RESULT(5), SCREENSHOT_RESULT(6), RUN_COMPLETE(7), RUN_FAILED(8);

        private final int wireId;
        ResultKind(int wireId) { this.wireId = wireId; }
        public int wireId() { return this.wireId; }
        public static ResultKind fromWire(int value) {
            for (ResultKind kind : values()) if (kind.wireId == value) return kind;
            throw new IllegalArgumentException("unknown debug-test result: " + value);
        }
    }

    public enum Failure {
        NONE(0), INVALID_STATE(1), PRECONDITION(2), POSE_TIMEOUT(3), DISCONNECTED(4),
        RENDERER_REPLACED(5), SCREENSHOT_FAILED(6), VERSION_MISMATCH(7), ABORTED(8),
        INTERNAL(9);

        private final int wireId;
        Failure(int wireId) { this.wireId = wireId; }
        public int wireId() { return this.wireId; }
        public static Failure fromWire(int value) {
            for (Failure failure : values()) if (failure.wireId == value) return failure;
            throw new IllegalArgumentException("unknown debug-test failure: " + value);
        }
    }
}
