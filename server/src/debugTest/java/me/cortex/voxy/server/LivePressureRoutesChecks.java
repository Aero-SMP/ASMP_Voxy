package me.cortex.voxy.server;

import com.google.gson.JsonObject;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

/** Pure arithmetic/IPC acknowledgment checks: no server, networking or integration launch. */
public final class LivePressureRoutesChecks {
    public static void main(String[] arguments) {
        check(LivePressureRoutes.remainingMillis(1_600_000, 1_000_000) == 600_000, "declared deadline");
        check(LivePressureRoutes.remainingMillis(1_600_000, 1_100_000) == 500_000, "clock not extended");
        rejects(() -> LivePressureRoutes.remainingMillis(1_000_000, 1_000_000));
        check(LivePressureRoutes.remainingMillis(2_800_000, 1_000_000) == 1_800_000, "longer owned watchdog permitted");
        rejects(() -> LivePressureRoutes.remainingMillis(Long.MAX_VALUE, -1));

        UUID request = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID run = UUID.fromString("22222222-2222-2222-2222-222222222222");
        byte[][] tokens = {new byte[32], new byte[32]};
        Arrays.fill(tokens[0], (byte) 7); Arrays.fill(tokens[1], (byte) 9);
        for (int opcode : new int[]{6, 7, 8}) {
            ByteBuffer frame = ByteBuffer.wrap(LivePressureRoutes.frame(request, run, 12345, opcode, 1, tokens))
                    .order(ByteOrder.LITTLE_ENDIAN);
            check(frame.get() == opcode, "opcode");
            check(text(frame).equals(request.toString()), "request ownership");
            check(text(frame).equals(run.toString()), "run ownership");
            check(frame.getLong() == 12345, "epoch ownership");
            if (opcode == 8) check(frame.get() == 1, "timing toggle");
            else {
                check(frame.getInt() == 2, "token count");
                for (byte[] token : tokens) {
                    byte[] decoded = new byte[32]; frame.get(decoded);
                    check(Arrays.equals(decoded, token), "only owned tokens");
                }
            }
            check(!frame.hasRemaining(), "exact framing");
        }
        rejects(() -> LivePressureRoutes.frame(request, run, 1, 5, 0, tokens));
        rejects(() -> LivePressureRoutes.frame(request, run, 1, 8, 3, tokens));
        byte[] reset = LivePressureRoutes.frame(request, run, 12345, 8, 2, tokens);
        check(reset[reset.length - 1] == 2, "cleanup stops reporting and restores defaults");

        JsonObject observation = new JsonObject();
        check(!LivePressureRoutes.removed(observation), "missing acknowledgment");
        observation.addProperty("ok", true);
        observation.addProperty("routes", 0); observation.addProperty("sessions", 0);
        observation.addProperty("subscriptions", 0); observation.addProperty("cleanup_pending", true);
        check(!LivePressureRoutes.removed(observation), "unknown/outstanding work");
        observation.addProperty("cleanup_pending", false);
        check(LivePressureRoutes.removed(observation), "observed native removal");
        for (String outstanding : new String[]{"routes", "sessions", "subscriptions"}) {
            observation.addProperty(outstanding, 1);
            check(!LivePressureRoutes.removed(observation), "remaining " + outstanding);
            observation.addProperty(outstanding, 0);
        }
        observation.addProperty("ok", false);
        check(!LivePressureRoutes.removed(observation), "rejected status");
        System.out.println("pressure-control checks PASS");
    }

    private static String text(ByteBuffer frame) {
        byte[] bytes = new byte[Short.toUnsignedInt(frame.getShort())]; frame.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void rejects(Runnable operation) {
        try { operation.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("invalid value accepted");
    }
}
