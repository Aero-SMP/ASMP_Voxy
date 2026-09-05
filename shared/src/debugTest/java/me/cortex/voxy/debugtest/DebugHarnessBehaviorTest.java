package me.cortex.voxy.debugtest;

import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Dependency-free executable assertions for debug-only protocol and ownership primitives. */
public final class DebugHarnessBehaviorTest {
    private DebugHarnessBehaviorTest() {}

    public static void main(String[] arguments) {
        me.cortex.voxy.client.lod.DebugZoomBehaviorTest.run();
        codecRoundTrips();
        snapshotCountersRoundTripIndividually();
        everyCommandRoundTrips();
        malformedVersionIsRejected();
        orderingRejectsStaleAndFutureResults();
        yawWrapsAtTheSignedBoundary();
        cameraRequiresTwoConsecutiveFrames();
        latestSampleIsConstantSizeAndRejectsStaleCompletion();
        prismArgumentFileLaunchIsAccepted();
        explicitGameDirectoryIsCanonicalized();
        prismInstanceIsDerivedFromGameDirectory();
        System.out.println("debug harness Java behavior tests passed");
    }

    private static void codecRoundTrips() {
        UUID run = UUID.randomUUID();
        var command = new DebugTestCommandPayload(DebugTestProtocol.CommandKind.EXPECT_POSE,
                run, 7, 3, "minecraft:overworld", 1.25, -64, 9.5,
                179, -30, 15_000_000_000L, 0, 0, "", "");
        RegistryFriendlyByteBuf commandBytes = buffer();
        DebugTestCommandPayload.CODEC.encode(commandBytes, command);
        check(command.equals(DebugTestCommandPayload.CODEC.decode(commandBytes)),
                "command codec changed a field");

        var result = new DebugTestResultPayload(DebugTestProtocol.ResultKind.POSE_REACHED,
                run, 7, 3, DebugTestProtocol.Failure.NONE, 11, 12, 4,
                1, 2, 3, 4,
                DebugTestSnapshot.empty());
        RegistryFriendlyByteBuf resultBytes = buffer();
        DebugTestResultPayload.CODEC.encode(resultBytes, result);
        check(result.equals(DebugTestResultPayload.CODEC.decode(resultBytes)),
                "result codec changed a field");
    }

    private static void everyCommandRoundTrips() {
        for (var kind : DebugTestProtocol.CommandKind.values()) {
            var command = new DebugTestCommandPayload(kind, UUID.randomUUID(), 1, 1, "",
                    0, 0, 0, 0, 0, 0, 0, 0,
                    kind == DebugTestProtocol.CommandKind.SHADER_OPTION ? "TAA" : "",
                    kind == DebugTestProtocol.CommandKind.SHADER_OPTION ? "true" : "");
            var bytes = buffer();
            DebugTestCommandPayload.CODEC.encode(bytes, command);
            check(command.equals(DebugTestCommandPayload.CODEC.decode(bytes)) && !bytes.isReadable(),
                    "new shader command did not round trip");
            bytes.release();
        }
    }

    private static void malformedVersionIsRejected() {
        RegistryFriendlyByteBuf bytes = buffer();
        bytes.writeVarInt(DebugTestProtocol.VERSION + 1);
        expectFailure(() -> DebugTestCommandPayload.CODEC.decode(bytes),
                "wrong protocol version was accepted");
        expectFailure(() -> new DebugTestCommandPayload(DebugTestProtocol.CommandKind.BEGIN_RUN,
                        UUID.randomUUID(), -1, 1, "", 0, 0, 0, 0, 0, 0, 0, 0, "", ""),
                "negative step was accepted");
    }

    private static void snapshotCountersRoundTripIndividually() {
        try {
            var fields = DebugTestSnapshot.class.getRecordComponents();
            Class<?>[] types = new Class<?>[fields.length];
            Object[] values = new Object[fields.length];
            for (int i = 0; i < fields.length; i++) {
                types[i] = fields[i].getType();
                if (types[i] == long.class) values[i] = (long) i + 1;
                else if (types[i] == int.class) values[i] = i + 1;
                else if (types[i] == float.class) values[i] = (float) i + 1;
                else if (types[i] == double.class) values[i] = (double) i + 1;
                else if (types[i] == boolean.class) values[i] = true;
                else values[i] = "minecraft:overworld";
            }
            var original = DebugTestSnapshot.class.getDeclaredConstructor(types).newInstance(values);
            RegistryFriendlyByteBuf bytes = buffer();
            original.encode(bytes);
            check(original.equals(DebugTestSnapshot.decode(bytes)), "snapshot field ordering changed");
            check(!bytes.isReadable(), "snapshot codec left unread counters");
            bytes.release();
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("snapshot codec test could not construct observation", failure);
        }
    }

    private static void orderingRejectsStaleAndFutureResults() {
        UUID run = UUID.randomUUID();
        check(DebugTestOrdering.isNext(-1, 0), "begin step was rejected");
        check(DebugTestOrdering.isNext(4, 5), "next step was rejected");
        check(!DebugTestOrdering.isNext(4, 4), "duplicate step was accepted");
        check(!DebugTestOrdering.isNext(4, 6), "future step was accepted");
        check(DebugTestOrdering.matches(run, 8, 5, run, 8, 5), "valid result rejected");
        check(!DebugTestOrdering.matches(run, 8, 5, run, 7, 5), "stale epoch accepted");
        check(!DebugTestOrdering.matches(run, 8, 5, UUID.randomUUID(), 8, 5),
                "cross-run result accepted");
    }

    private static void yawWrapsAtTheSignedBoundary() {
        check(DebugPoseMath.angularDistance(179, -181) == 0,
                "equivalent wrapped yaw differs");
        check(DebugPoseMath.angularDistance(-179, 179) == 2,
                "short wrapped distance was not used");
    }

    private static void cameraRequiresTwoConsecutiveFrames() {
        DebugPoseStabilizer stabilizer = new DebugPoseStabilizer(10);
        check(!stabilizer.observe(10, true), "marker frame counted");
        check(!stabilizer.observe(11, true), "one frame stabilized pose");
        check(!stabilizer.observe(12, false), "mismatch stabilized pose");
        check(!stabilizer.observe(13, true), "post-reset first frame stabilized pose");
        check(stabilizer.observe(14, true), "two matching frames did not stabilize pose");
    }

    private static void latestSampleIsConstantSizeAndRejectsStaleCompletion() {
        DebugLatestMailbox<String> mailbox = new DebugLatestMailbox<>();
        var first = mailbox.offer("first");
        check(first != null && first.value().equals("first"), "first sample did not dispatch");
        check(mailbox.offer("middle") == null, "second sample dispatched while occupied");
        check(mailbox.offer("latest") == null, "third sample dispatched while occupied");
        var latest = mailbox.complete(first.generation());
        check(latest != null && latest.value().equals("latest") && latest.coalesced() == 1,
                "latest sample or coalescing count was lost");
        long stale = latest.generation();
        mailbox.clear();
        check(mailbox.complete(stale) == null, "stale completion mutated a new generation");
        check(mailbox.isIdle(), "cleared mailbox retained ownership");
    }

    private static void prismArgumentFileLaunchIsAccepted() {
        ArrayList<String> command = new ArrayList<>(List.of("java", "@launch-arguments.txt"));
        invokeGameDirectoryCanonicalization(command, Path.of("canonical-game"));
        check(command.equals(List.of("java", "@launch-arguments.txt")),
                "Prism argument-file launch was changed or rejected");
    }

    private static void explicitGameDirectoryIsCanonicalized() {
        ArrayList<String> command = new ArrayList<>(List.of(
                "java", "Main", "--gameDir", "old", "split-path", "--username", "test"));
        invokeGameDirectoryCanonicalization(command, Path.of("canonical-game"));
        check(command.equals(List.of(
                        "java", "Main", "--gameDir", Path.of("canonical-game").toString(),
                        "--username", "test")),
                "explicit game directory was not canonicalized");
    }

    private static void prismInstanceIsDerivedFromGameDirectory() {
        Object instance = invokePrivateRestartMethod("prismInstanceId",
                new Class<?>[] {Path.class},
                Path.of("instances", "Aero SMP-4.1", "minecraft"));
        check("Aero SMP-4.1".equals(instance), "Prism instance ID was not preserved");
        Object invalid = invokePrivateRestartMethod("prismInstanceId",
                new Class<?>[] {Path.class}, Path.of("ordinary-game-directory"));
        check(invalid == null, "non-Prism directory produced an instance ID");
    }

    private static void invokeGameDirectoryCanonicalization(ArrayList<String> command,
                                                            Path gameDirectory) {
        invokePrivateRestartMethod("canonicalizeGameDirectory",
                new Class<?>[] {ArrayList.class, Path.class}, command, gameDirectory);
    }

    private static Object invokePrivateRestartMethod(String name, Class<?>[] types,
                                                     Object... arguments) {
        try {
            Class<?> helper = Class.forName(
                    "me.cortex.voxy.client.lod.ClientUpdateRestart");
            Method method = helper.getDeclaredMethod(name, types);
            method.setAccessible(true);
            return method.invoke(null, arguments);
        } catch (InvocationTargetException failure) {
            throw new AssertionError("restart command was rejected", failure.getCause());
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("restart helper test could not run", failure);
        }
    }

    private static RegistryFriendlyByteBuf buffer() {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
    }

    private static void expectFailure(Runnable operation, String message) {
        try { operation.run(); }
        catch (IllegalArgumentException expected) { return; }
        throw new AssertionError(message);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
