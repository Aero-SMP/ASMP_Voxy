package me.cortex.voxy.client.lod;

import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.ShaderResourceScope;
import me.cortex.voxy.client.iris.IrisUtil;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.api.v0.IrisApi;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.debugtest.DebugTestCommandPayload;
import me.cortex.voxy.debugtest.DebugLatestMailbox;
import me.cortex.voxy.debugtest.DebugPoseStabilizer;
import me.cortex.voxy.debugtest.DebugPoseMath;
import me.cortex.voxy.debugtest.DebugTestProtocol;
import me.cortex.voxy.debugtest.DebugTestResultPayload;
import me.cortex.voxy.debugtest.DebugTestSnapshot;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.HandlerThread;

import java.nio.file.Path;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Debug-only controller that observes the real client and never replaces production behavior. */
final class LiveClientTestHarness {
    private static final UUID CONNECTION_READY = new UUID(0, 0);
    private static final double POSITION_TOLERANCE = 1.0e-4;
    private static final double CAMERA_TOLERANCE = 2.0e-3;
    private static final float ANGLE_TOLERANCE = 0.05f;

    private static ClientPacketListener connection;
    private static long epochCounter;
    private static long connectionEpoch;
    private static boolean readyAdvertised;
    private static Run run;
    private static Pose observedPose = Pose.unavailable();
    private static long renderedFrame;
    private static boolean snapshotPending;
    private static long snapshotToken;
    private static long snapshotSession;
    private static final DebugLatestMailbox<DebugTestResultPayload> TRACE_MAILBOX =
            new DebugLatestMailbox<>();
    private static long traceCaptureCoalesced;
    private static volatile BuildIdentity buildIdentity;

    private LiveClientTestHarness() {}

    static void register(IEventBus modBus) {
        modBus.addListener(LiveClientTestHarness::registerPayload);
        Thread.ofPlatform().daemon().name("Voxy debug build identity")
                .start(LiveClientTestHarness::loadBuildIdentity);
    }

    private static void registerPayload(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar(DebugTestProtocol.REGISTRATION_VERSION)
                .optional().executesOn(HandlerThread.NETWORK);
        registrar.playToClient(DebugTestCommandPayload.TYPE, DebugTestCommandPayload.CODEC,
                (payload, context) -> context.enqueueWork(() -> accept(payload)));
        // Both peers declare both directions so optional capability negotiation is symmetric.
        // This handler can only run on an incorrectly configured physical server.
        registrar.playToServer(DebugTestResultPayload.TYPE, DebugTestResultPayload.CODEC,
                (payload, context) -> {});
    }

    static void tick() {
        Minecraft minecraft = Minecraft.getInstance();
        ClientPacketListener current = minecraft.getConnection();
        if (current != connection) {
            failRun(DebugTestProtocol.Failure.DISCONNECTED,
                    (failed, reason) -> localTerminal(failed, reason, "Minecraft disconnected; not sent"),
                    LiveClientTestHarness::restoreSettings);
            connection = current;
            connectionEpoch = current == null ? 0 : ++epochCounter;
            readyAdvertised = false;
            clearRun();
        }
        if (snapshotPending && snapshotSession != ClientSession.debugOpenSessionIdentity()) {
            if (run != null) failRun(DebugTestProtocol.Failure.RENDERER_REPLACED);
            else { invalidateSnapshot(); readyAdvertised = false; }
        }
        if (current != null && !readyAdvertised && observedPose.frame >= 0
                && buildIdentity != null && current.hasChannel(DebugTestResultPayload.TYPE)
                && !snapshotPending) {
            readyAdvertised = true;
            requestResult(DebugTestProtocol.ResultKind.CLIENT_READY, CONNECTION_READY,
                    0, DebugTestProtocol.Failure.NONE, false);
        }
        Run active = run;
        if (active == null) return;
        if (connection == null) {
            clearRun();
            return;
        }
        VoxyRenderSystem renderer = IGetVoxyRenderSystem.getNullable();
        if (renderer != active.renderer) {
            failRun(DebugTestProtocol.Failure.RENDERER_REPLACED);
            return;
        }
        long now = System.nanoTime();
        if (active.zoomPending && now >= active.zoomDeadline) {
            failRun(DebugTestProtocol.Failure.ZOOM_TIMEOUT);
            return;
        }
        if (active.pose != null && now - active.pose.deadlineNanos >= 0) {
            active.pose = null;
            requestResult(DebugTestProtocol.ResultKind.POSE_FAILED, active.runId,
                    active.stepId, DebugTestProtocol.Failure.POSE_TIMEOUT, false);
            return;
        }
        Trace trace = active.trace;
        if (trace != null) {
            if (!trace.finishing && now - trace.endNanos >= 0) trace.finishing = true;
            if (!trace.finishing && now - trace.nextSampleNanos >= 0) {
                trace.nextSampleNanos = now + trace.cadenceNanos;
                requestTraceSample(active, trace);
            }
            finishTraceIfDrained(active, trace);
        }
    }

    static void renderFrame(RenderFrameEvent.Post event) {
        renderedFrame++;
        observedPose = observePose();
        Run active = run;
        if (active == null) return;
        if (active.zoomPending && active.zoomControl.observed()) {
            active.zoomPending = false;
            requestResult(DebugTestProtocol.ResultKind.CHECKPOINT_RESULT, active.runId,
                    active.stepId, DebugTestProtocol.Failure.NONE, false);
        }
        if (active.shaderAwaitFrame >= 0 && renderedFrame > active.shaderAwaitFrame) {
            var renderer = IGetVoxyRenderSystem.getNullable();
            if (renderer != active.renderer) { failRun(DebugTestProtocol.Failure.RENDERER_REPLACED); return; }
            if (renderer.shaderReloadStatus().equals("FAILED")) { failRun(DebugTestProtocol.Failure.INTERNAL); return; }
            if (renderer.shaderReloadStatus().equals("READY") && renderer.shaderResumedDraws() > active.shaderDrawMarker) {
                active.shaderAwaitFrame = -1;
                requestResult(active.endingShaderRestore ? DebugTestProtocol.ResultKind.RUN_COMPLETE
                                : DebugTestProtocol.ResultKind.CHECKPOINT_RESULT, active.runId,
                        active.stepId, DebugTestProtocol.Failure.NONE, active.endingShaderRestore);
            }
        }
        PoseExpectation expected = active.pose;
        if (expected != null) {
            if (expected.stabilizer.observe(renderedFrame, matches(expected, observedPose))) {
                active.pose = null;
                requestResult(DebugTestProtocol.ResultKind.POSE_REACHED, active.runId,
                        active.stepId, DebugTestProtocol.Failure.NONE, false);
            }
        }
        ScreenshotRequest screenshot = active.screenshot;
        if (screenshot != null && renderedFrame > screenshot.markerFrame
                && !screenshot.captureStarted) {
            screenshot.captureStarted = true;
            Screenshot.grab(Minecraft.getInstance().gameDirectory, screenshot.filename,
                    Minecraft.getInstance().getMainRenderTarget(), ignored -> {});
        }
    }

    static boolean claimScreenshot(Path path) {
        Run active = run;
        ScreenshotRequest request = active == null ? null : active.screenshot;
        if (request == null || !path.getFileName().toString().equals(request.filename)) return false;
        ClientAutoUpdater.queueScreenshot(path, success -> Minecraft.getInstance().execute(() -> {
            Run current = run;
            if (current != active || current.screenshot != request) return;
            current.screenshot = null;
            requestResult(DebugTestProtocol.ResultKind.SCREENSHOT_RESULT, current.runId,
                    current.stepId, success ? DebugTestProtocol.Failure.NONE
                            : DebugTestProtocol.Failure.SCREENSHOT_FAILED, false);
        }));
        return true;
    }

    private static void accept(DebugTestCommandPayload command) {
        if (command.connectionEpoch() != connectionEpoch || connection == null) return;
        if (command.kind() == DebugTestProtocol.CommandKind.BEGIN_RUN) {
            if (run != null) {
                sendFailure(command, DebugTestProtocol.Failure.INVALID_STATE);
                return;
            }
            invalidateSnapshot(); // A discarded connection-ready observation cannot gate a new run.
            run = new Run(command.runId(), command.stepId(),
                    IGetVoxyRenderSystem.getNullable());
            requestResult(DebugTestProtocol.ResultKind.CLIENT_READY, command.runId(),
                    command.stepId(), DebugTestProtocol.Failure.NONE, false);
            return;
        }
        Run active = run;
        if (command.kind() == DebugTestProtocol.CommandKind.ABORT_RUN && active != null
                && active.runId.equals(command.runId()) && command.stepId() > active.stepId) {
            active.stepId = command.stepId();
            active.command = command.kind();
            failRun(DebugTestProtocol.Failure.ABORTED);
            return;
        }
        if (active == null || !active.runId.equals(command.runId())
                || command.stepId() != active.stepId + 1 || active.hasOutstandingOperation()) {
            sendFailure(command, DebugTestProtocol.Failure.INVALID_STATE);
            return;
        }
        active.stepId = command.stepId();
        active.command = command.kind();
        switch (command.kind()) {
            case ZOOM_IN, ZOOM_OUT, ZOOM_MAX -> {
                if (!DebugZoomControl.available() || DebugZoomControl.worldFov() <= 0) {
                    sendFailure(command, DebugTestProtocol.Failure.PRECONDITION);
                    break;
                }
                if (active.zoomControl == null) active.zoomControl = DebugZoomControl.begin();
                try {
                    if (command.kind() == DebugTestProtocol.CommandKind.ZOOM_MAX) active.zoomControl.maximum();
                    active.zoomControl.select(command.kind() != DebugTestProtocol.CommandKind.ZOOM_OUT);
                } catch (RuntimeException failure) {
                    failRun(DebugTestProtocol.Failure.INTERNAL);
                    break;
                }
                active.zoomDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
                active.zoomPending = true;
            }
            case SHADER_RELOAD, SHADERS_ON, SHADERS_OFF, SHADER_RELOAD_ALL_CHANGED, SHADER_OPTION -> {
                if (!IrisUtil.IRIS_INSTALLED || active.renderer == null) {
                    sendFailure(command, DebugTestProtocol.Failure.PRECONDITION);
                    break;
                }
                active.shaderAwaitFrame = renderedFrame;
                active.shaderDrawMarker = active.renderer.shaderResumedDraws();
                try {
                    if (active.shaderSettings == null) active.shaderSettings = new DebugShaderSettings();
                    if (command.kind() == DebugTestProtocol.CommandKind.SHADER_OPTION) {
                        active.shaderSettings.option(command.shaderOption(), command.shaderValue());
                    } else if (command.kind() == DebugTestProtocol.CommandKind.SHADERS_ON
                            || command.kind() == DebugTestProtocol.CommandKind.SHADERS_OFF) {
                        IrisApi.getInstance().getConfig().setShadersEnabledAndApply(
                                command.kind() == DebugTestProtocol.CommandKind.SHADERS_ON);
                    } else if (command.kind() == DebugTestProtocol.CommandKind.SHADER_RELOAD_ALL_CHANGED) {
                        var scope = active.renderer.beginShaderReload("debug Iris.reload with nested allChanged");
                        Throwable failure = null;
                        try { Iris.reload(); Minecraft.getInstance().levelRenderer.allChanged(); }
                        catch (Throwable problem) { failure = problem; throw problem; }
                        finally { scope.finish(failure); }
                    } else {
                        Iris.reload();
                    }
                    if (IGetVoxyRenderSystem.getNullable() != active.renderer) {
                        failRun(DebugTestProtocol.Failure.RENDERER_REPLACED);
                    } else if (active.renderer.shaderReloadStatus().equals("FAILED")) {
                        failRun(DebugTestProtocol.Failure.INTERNAL);
                    }
                } catch (Throwable failure) {
                    me.cortex.voxy.common.Logger.error("Debug shader reload failed", failure);
                    failRun(DebugTestProtocol.Failure.INTERNAL);
                }
            }
            case RECONNECT_QUIC, HOLD_QUIC, RESUME_QUIC -> {
                // Close only this Voxy transport on its owner. Normal connection-loss recovery
                // must reconnect; neither Minecraft nor any other client is disconnected.
                if (!ClientSession.requestDebugSession(session -> {
                    if (command.kind() != DebugTestProtocol.CommandKind.RECONNECT_QUIC) {
                        ClientLodDebug.holdTransport(command.kind() == DebugTestProtocol.CommandKind.HOLD_QUIC);
                    }
                    if (command.kind() != DebugTestProtocol.CommandKind.RESUME_QUIC) {
                        if (session.connectionAttempt != null) {
                            session.connectionAttempt.close();
                            session.connectionAttempt = null;
                        }
                        if (session.quic != null) session.quic.close();
                    }
                    session.signal();
                    Minecraft.getInstance().execute(() -> {
                        if (run == active) requestResult(DebugTestProtocol.ResultKind.CHECKPOINT_RESULT,
                                active.runId, active.stepId, DebugTestProtocol.Failure.NONE, false);
                    });
                })) sendFailure(command, DebugTestProtocol.Failure.PRECONDITION);
            }
            case EXPECT_POSE -> active.pose = new PoseExpectation(command,
                    System.nanoTime() + command.timeoutNanos(), renderedFrame);
            case START_TRACE -> {
                if (command.durationNanos() <= 0 || command.cadenceNanos() <= 0) {
                    sendFailure(command, DebugTestProtocol.Failure.PRECONDITION);
                } else {
                    long now = System.nanoTime();
                    active.trace = new Trace(now + command.durationNanos(), now,
                            command.cadenceNanos());
                }
            }
            case CAPTURE_CHECKPOINT -> requestResult(
                    DebugTestProtocol.ResultKind.CHECKPOINT_RESULT, active.runId,
                    active.stepId, DebugTestProtocol.Failure.NONE, false);
            case CAPTURE_SCREENSHOT -> active.screenshot = new ScreenshotRequest(
                    "voxy-test-" + active.runId + '-' + active.stepId + ".png", renderedFrame);
            case END_RUN -> {
                releaseZoom(active);
                active.shaderDrawMarker = active.renderer == null ? 0 : active.renderer.shaderResumedDraws();
                try {
                    if (active.shaderSettings != null) {
                        var settings = active.shaderSettings;
                        active.shaderSettings = null;
                        settings.restore();
                    }
                    if (IGetVoxyRenderSystem.getNullable() != active.renderer) {
                        failRun(DebugTestProtocol.Failure.RENDERER_REPLACED);
                    } else if (active.renderer != null && active.renderer.shaderReloadStatus().equals("SUSPENDED")) {
                        active.endingShaderRestore = true;
                        active.shaderAwaitFrame = renderedFrame;
                    } else if (active.renderer != null && !active.renderer.shaderReloadStatus().equals("READY")) {
                        failRun(DebugTestProtocol.Failure.INTERNAL);
                    } else {
                        requestResult(DebugTestProtocol.ResultKind.RUN_COMPLETE,
                                active.runId, active.stepId, DebugTestProtocol.Failure.NONE, true);
                    }
                } catch (Throwable failure) {
                    me.cortex.voxy.common.Logger.error("Debug shader settings restoration failed", failure);
                    failRun(DebugTestProtocol.Failure.INTERNAL);
                }
            }
            case ABORT_RUN -> failRun(DebugTestProtocol.Failure.ABORTED);
            case BEGIN_RUN -> throw new AssertionError();
        }
    }

    private static void requestTraceSample(Run active, Trace trace) {
        if (snapshotPending) {
            traceCaptureCoalesced++;
            return;
        }
        snapshotPending = true;
        snapshotSession = ClientSession.debugOpenSessionIdentity();
        long token = ++snapshotToken;
        Pose pose = observedPose;
        if (!ClientSession.requestDebugSnapshot(pipeline -> Minecraft.getInstance().execute(() -> {
            if (token != snapshotToken) return;
            snapshotPending = false;
            if (run != active || active.trace != trace) return;
            sendTrace(resultPayload(DebugTestProtocol.ResultKind.TRACE_SAMPLE,
                    active.runId, active.stepId, connectionEpoch, DebugTestProtocol.Failure.NONE,
                    renderedFrame, renderedFrame, traceCaptureCoalesced,
                    snapshot(pose, pipeline)));
            traceCaptureCoalesced = 0;
            finishTraceIfDrained(active, trace);
        }))) {
            if (token != snapshotToken) return;
            snapshotPending = false;
            sendTrace(resultPayload(DebugTestProtocol.ResultKind.TRACE_SAMPLE,
                    active.runId, active.stepId, connectionEpoch, DebugTestProtocol.Failure.NONE,
                    renderedFrame, renderedFrame, traceCaptureCoalesced,
                    snapshot(pose, null)));
            traceCaptureCoalesced = 0;
        }
    }

    private static void sendTrace(DebugTestResultPayload sample) {
        DebugLatestMailbox.Dispatch<DebugTestResultPayload> dispatch = TRACE_MAILBOX.offer(sample);
        if (dispatch != null) transmitTrace(dispatch);
    }

    private static void transmitTrace(
            DebugLatestMailbox.Dispatch<DebugTestResultPayload> dispatch) {
        ClientPacketListener target = connection;
        if (target == null || !target.hasChannel(DebugTestResultPayload.TYPE)) return;
        DebugTestResultPayload sample = dispatch.value();
        DebugTestResultPayload transmitted = new DebugTestResultPayload(sample.kind(),
                sample.runId(), sample.stepId(), sample.connectionEpoch(), sample.failure(),
                sample.firstFrame(), sample.lastFrame(),
                sample.coalescedSamples() + dispatch.coalesced(), sample.buildIdentity0(),
                sample.buildIdentity1(), sample.buildIdentity2(), sample.buildIdentity3(),
                sample.snapshot());
        target.getConnection().send(new ServerboundCustomPayloadPacket(transmitted),
                PacketSendListener.thenRun(() -> Minecraft.getInstance().execute(
                        () -> traceSendComplete(dispatch.generation()))));
    }

    private static void traceSendComplete(long generation) {
        DebugLatestMailbox.Dispatch<DebugTestResultPayload> next =
                TRACE_MAILBOX.complete(generation);
        if (next != null && run != null) transmitTrace(next);
        Run active = run;
        if (active != null && active.trace != null) finishTraceIfDrained(active, active.trace);
    }

    private static void finishTraceIfDrained(Run active, Trace trace) {
        if (!trace.finishing || snapshotPending || !TRACE_MAILBOX.isIdle()) return;
        active.trace = null;
        requestResult(DebugTestProtocol.ResultKind.CHECKPOINT_RESULT, active.runId,
                active.stepId, DebugTestProtocol.Failure.NONE, false);
    }

    private static void requestResult(DebugTestProtocol.ResultKind kind, UUID resultRun,
                                      long step, DebugTestProtocol.Failure failure,
                                      boolean clearAfter) {
        if (snapshotPending) {
            failRun(DebugTestProtocol.Failure.INTERNAL);
            return;
        }
        snapshotPending = true;
        snapshotSession = ClientSession.debugOpenSessionIdentity();
        long token = ++snapshotToken;
        long resultEpoch = connectionEpoch;
        ClientPacketListener resultConnection = connection;
        Pose pose = observedPose;
        if (!ClientSession.requestDebugSnapshot(pipeline -> Minecraft.getInstance().execute(() -> {
            if (token != snapshotToken || resultEpoch != connectionEpoch
                    || resultConnection != connection) return;
            snapshotPending = false;
            sendCritical(resultPayload(kind, resultRun, step, resultEpoch,
                    failure, Math.max(0, pose.frame), Math.max(0, pose.frame), 0,
                    snapshot(pose, pipeline)));
            if (clearAfter) clearRun();
        }))) {
            if (token != snapshotToken || resultEpoch != connectionEpoch
                    || resultConnection != connection) return;
            snapshotPending = false;
            sendCritical(resultPayload(kind, resultRun, step, resultEpoch,
                    failure, Math.max(0, pose.frame), Math.max(0, pose.frame), 0,
                    snapshot(pose, null)));
            if (clearAfter) clearRun();
        }
    }

    private static void sendFailure(DebugTestCommandPayload command,
                                    DebugTestProtocol.Failure failure) {
        sendCritical(resultPayload(failureResult(command.kind()),
                command.runId(), command.stepId(), connectionEpoch, failure,
                Math.max(0, observedPose.frame), Math.max(0, observedPose.frame), 0,
                snapshot(observedPose, null)));
    }

    private static void failRun(DebugTestProtocol.Failure failure) {
        failRun(failure, (active, reason) -> {
            if (connection == null || !connection.hasChannel(DebugTestResultPayload.TYPE)) {
                localTerminal(active, reason, "debug channel unavailable; not sent");
                return;
            }
            sendCritical(resultPayload(failureResult(active.command), active.runId,
                    active.stepId, connectionEpoch, reason,
                    Math.max(0, observedPose.frame), Math.max(0, observedPose.frame), 0,
                    snapshot(observedPose, null)));
        }, LiveClientTestHarness::restoreSettings);
    }

    // The existing receiver validates the operation's reply type before its failure field.
    // A non-NONE failure on that typed reply terminates the run; RUN_FAILED is the abort reply.
    static DebugTestProtocol.ResultKind failureResult(DebugTestProtocol.CommandKind command) {
        return switch (command) {
            case BEGIN_RUN -> DebugTestProtocol.ResultKind.CLIENT_READY;
            case EXPECT_POSE -> DebugTestProtocol.ResultKind.POSE_FAILED;
            case CAPTURE_SCREENSHOT -> DebugTestProtocol.ResultKind.SCREENSHOT_RESULT;
            case END_RUN -> DebugTestProtocol.ResultKind.RUN_COMPLETE;
            case ABORT_RUN -> DebugTestProtocol.ResultKind.RUN_FAILED;
            default -> DebugTestProtocol.ResultKind.CHECKPOINT_RESULT;
        };
    }

    // Terminal delivery does not request a SessionObservation. Detach before callbacks:
    // restoration can itself rebuild the renderer and late observations must be inert.
    static void failRun(DebugTestProtocol.Failure failure,
                        java.util.function.BiConsumer<Run, DebugTestProtocol.Failure> deliver,
                        java.util.function.Consumer<Run> restore) {
        Run active = run;
        if (active == null) return;
        run = null;
        invalidateSnapshot();
        TRACE_MAILBOX.clear();
        traceCaptureCoalesced = 0;
        try { deliver.accept(active, failure); }
        catch (Throwable unavailable) {
            localTerminal(active, failure, "terminal send failed: " + unavailable);
        } finally {
            try { restore.accept(active); }
            catch (Throwable problem) {
                me.cortex.voxy.common.Logger.error("Harness shader restoration failed after terminal result", problem);
            }
        }
    }

    private static void localTerminal(Run active, DebugTestProtocol.Failure failure, String detail) {
        ClientLodDebug.updaterEvent("state=HARNESS_TERMINAL_LOCAL run=" + active.runId
                + " step=" + active.stepId + " connection=" + connectionEpoch
                + " reason=" + failure + " detail=" + detail);
    }

    private static void sendCritical(DebugTestResultPayload result) {
        ClientPacketListener target = connection;
        if (target != null && target.hasChannel(DebugTestResultPayload.TYPE)) target.send(result);
    }

    private static DebugTestResultPayload resultPayload(DebugTestProtocol.ResultKind kind,
                                                        UUID resultRun, long step, long epoch,
                                                        DebugTestProtocol.Failure failure,
                                                        long firstFrame, long lastFrame,
                                                        long coalesced,
                                                        DebugTestSnapshot snapshot) {
        BuildIdentity identity = buildIdentity;
        if (identity == null) identity = BuildIdentity.ZERO;
        return new DebugTestResultPayload(kind, resultRun, step, epoch, failure,
                firstFrame, lastFrame, coalesced, identity.first, identity.second,
                identity.third, identity.fourth, snapshot);
    }

    private static void loadBuildIdentity() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            Path path = net.neoforged.fml.ModList.get().getModFileById("voxy").getFile().getFilePath();
            if (java.nio.file.Files.isRegularFile(path)) {
                try (var input = java.nio.file.Files.newInputStream(path)) {
                    byte[] buffer = new byte[64 * 1024];
                    for (int read; (read = input.read(buffer)) >= 0;) {
                        digest.update(buffer, 0, read);
                    }
                }
            } else {
                digest.update(path.toAbsolutePath().normalize().toString()
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            ByteBuffer bytes = ByteBuffer.wrap(digest.digest());
            buildIdentity = new BuildIdentity(bytes.getLong(), bytes.getLong(),
                    bytes.getLong(), bytes.getLong());
        } catch (Exception failure) {
            ClientLodDebug.updaterEvent("state=HARNESS_BUILD_IDENTITY_FAILED type="
                    + failure.getClass().getSimpleName());
            buildIdentity = BuildIdentity.ZERO;
        }
    }

    private static Pose observePose() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null
                || !minecraft.gameRenderer.getMainCamera().isInitialized()) {
            return Pose.unavailable();
        }
        Camera camera = minecraft.gameRenderer.getMainCamera();
        Vec3 cameraPosition = camera.getPosition();
        return new Pose(renderedFrame, minecraft.level.dimension().location().toString(),
                minecraft.player.getX(), minecraft.player.getY(), minecraft.player.getZ(),
                minecraft.player.getYRot(), minecraft.player.getXRot(), cameraPosition.x,
                cameraPosition.y, cameraPosition.z, camera.getYRot(), camera.getXRot(),
                minecraft.options.getCameraType().isFirstPerson());
    }

    private static boolean matches(PoseExpectation expected, Pose actual) {
        if (!actual.firstPerson || !expected.dimension.equals(actual.dimension)) return false;
        if (distance(expected.x, actual.playerX) > POSITION_TOLERANCE
                || distance(expected.y, actual.playerY) > POSITION_TOLERANCE
                || distance(expected.z, actual.playerZ) > POSITION_TOLERANCE
                || angle(expected.yaw, actual.playerYaw) > ANGLE_TOLERANCE
                || angle(expected.pitch, actual.playerPitch) > ANGLE_TOLERANCE) return false;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) return false;
        Vec3 eye = minecraft.player.getEyePosition(
                minecraft.gameRenderer.getMainCamera().getPartialTickTime());
        return distance(eye.x, actual.cameraX) <= CAMERA_TOLERANCE
                && distance(eye.y, actual.cameraY) <= CAMERA_TOLERANCE
                && distance(eye.z, actual.cameraZ) <= CAMERA_TOLERANCE
                && angle(actual.playerYaw, actual.cameraYaw) <= ANGLE_TOLERANCE
                && angle(actual.playerPitch, actual.cameraPitch) <= ANGLE_TOLERANCE;
    }

    static boolean poseMatchesForTest(double expected, double actual, float expectedYaw,
                                      float actualYaw) {
        return distance(expected, actual) <= POSITION_TOLERANCE
                && angle(expectedYaw, actualYaw) <= ANGLE_TOLERANCE;
    }

    private static double distance(double a, double b) { return Math.abs(a - b); }
    private static float angle(float a, float b) {
        return DebugPoseMath.angularDistance(a, b);
    }

    private static DebugTestSnapshot snapshot(Pose pose, ClientSession.PipelineSnapshot pipeline) {
        ClientLodDebug.RenderCounters render = ClientLodDebug.renderCounters();
        VoxyRenderSystem renderer = IGetVoxyRenderSystem.getNullable();
        long presence = pose.frame >= 0 ? DebugTestSnapshot.POSE_PRESENT : 0;
        if (pipeline != null) presence |= DebugTestSnapshot.SESSION_PRESENT
                | DebugTestSnapshot.GEOMETRY_RETENTION_PRESENT;
        if (render.frame() >= 0) presence |= DebugTestSnapshot.GPU_COUNTERS_PRESENT;
        return new DebugTestSnapshot(presence, System.nanoTime(), (int) renderedFrame,
                pose.dimension, pose.playerX, pose.playerY, pose.playerZ,
                pose.playerYaw, pose.playerPitch, pose.cameraX, pose.cameraY, pose.cameraZ,
                pose.cameraYaw, pose.cameraPitch, pose.firstPerson,
                pipeline == null ? 0 : pipeline.sessionGeneration(),
                pipeline == null ? 0 : pipeline.connectionEpoch(),
                pipeline == null ? 0 : pipeline.rootGeneration(),
                pipeline == null ? 0 : pipeline.rootGeneration(),
                pipeline != null && pipeline.failed() ? 1 : 0,
                pipeline == null ? 0 : pipeline.retryNanos(),
                pipeline == null ? 0 : pipeline.coverageMissing(),
                pipeline == null ? 0 : pipeline.requested(),
                pipeline == null ? 0 : pipeline.downloading(),
                pipeline == null ? 0 : pipeline.cacheReading(),
                pipeline == null ? 0 : pipeline.decoding(),
                pipeline == null ? 0 : pipeline.meshing(),
                pipeline == null ? 0 : pipeline.ready(),
                pipeline == null ? 0 : pipeline.publishing(),
                pipeline == null ? 0 : pipeline.active(),
                pipeline == null ? 0 : pipeline.networkBytes(),
                pipeline == null ? 0 : pipeline.completedBatches(),
                pipeline == null ? 0 : pipeline.cacheHits(),
                pipeline == null ? 0 : pipeline.cacheMisses(),
                pipeline == null ? 0 : pipeline.cacheReads(),
                pipeline == null ? 0 : pipeline.cacheBytes(),
                pipeline == null ? 0 : pipeline.decodedTotal(),
                pipeline == null ? 0 : pipeline.meshedTotal(),
                pipeline == null ? 0 : pipeline.uploadedTotal(),
                pipeline == null ? 0 : pipeline.activatedTotal(),
                pipeline == null ? 0 : pipeline.retiredTotal(),
                pipeline == null ? 0 : pipeline.selectedBytes(),
                pipeline == null ? 0 : pipeline.warmBytes(),
                pipeline == null ? 0 : pipeline.coldBytes(),
                pipeline == null ? 0 : pipeline.pendingRetirementBytes(),
                pipeline == null ? 0 : pipeline.physicalGeometryBytes(),
                pipeline == null ? 0 : pipeline.rendererTargetBytes(),
                pipeline == null ? 0 : pipeline.rendererAllocatedBytes(),
                Math.max(render.conservativeSelected(), render.refinedSelected()),
                Math.max(render.conservativeDraws(), render.refinedDraws()),
                render.ageNanos(),
                pipeline == null ? 0 : pipeline.handoffGeneration(),
                pipeline == null ? 0 : pipeline.handoffOccupied(),
                pipeline == null ? 0 : pipeline.publicationActivated(),
                pipeline == null ? 0 : pipeline.publicationReturned(),
                pipeline == null ? 0 : pipeline.publicationCancelled(),
                pipeline == null ? 0 : pipeline.publicationFailed(),
                pipeline == null ? 0 : pipeline.outstandingLeases(),
                pipeline == null ? 0 : pipeline.pendingCoverageReplies(),
                pipeline == null ? 0 : pipeline.pendingRefinementReplies(),
                pipeline == null ? 0 : pipeline.blockedGeometry(),
                pipeline == null ? 0 : pipeline.blockedSectionId(),
                pipeline == null ? 0 : pipeline.blockedTopology(),
                pipeline == null ? 0 : pipeline.blockedStale(),
                pipeline == null ? 0 : pipeline.impossible(),
                pipeline == null ? 0 : pipeline.topologyGeneration(),
                pipeline == null ? 0 : pipeline.allocationReleaseGeneration(),
                pipeline == null ? 0 : pipeline.sectionIdReleaseGeneration(),
                pipeline == null ? 0 : pipeline.handoffBusy(),
                renderer == null ? 0 : renderer.rendererIdentity(),
                renderer == null ? 0 : renderer.shaderReloadGeneration(),
                renderer == null ? "ABSENT" : renderer.shaderReloadStatus(),
                renderer == null ? "" : boundedReason(renderer.shaderReloadReason()),
                renderer == null ? 0 : renderer.shaderReloadPauseNanos(),
                renderer == null ? 0 : renderer.shaderHistoryInvalidations(),
                renderer == null ? 0 : renderer.shaderResumedDraws(),
                renderer == null ? 0 : renderer.shaderMaterialUpdates(),
                ShaderResourceScope.created(), ShaderResourceScope.freed(),
                IrisUtil.IRIS_INSTALLED ? boundedReason(Iris.getCurrentPackName()) : "Iris absent",
                Minecraft.getInstance().options.fov().get(),
                pipeline == null ? 0 : pipeline.dormancyTransitions(),
                pipeline == null ? 0 : pipeline.wakes(),
                pipeline == null ? 0 : pipeline.instantWakes(),
                pipeline == null ? 0 : pipeline.dormantEvictions(),
                DebugZoomControl.available(), DebugZoomControl.zoomActive(), DebugZoomControl.worldFov());
    }

    private static String boundedReason(String value) {
        return value == null ? "" : value.substring(0, Math.min(value.length(), 512));
    }

    private static void clearRun() {
        Run previous = run;
        run = null;
        invalidateSnapshot();
        TRACE_MAILBOX.clear();
        traceCaptureCoalesced = 0;
        restoreSettings(previous);
    }

    private static void restoreSettings(Run previous) {
        releaseZoom(previous);
        if (previous != null && previous.shaderSettings != null && IrisUtil.IRIS_INSTALLED) {
            var settings = previous.shaderSettings;
            previous.shaderSettings = null;
            try {
                settings.restore();
            } catch (Throwable failure) {
                me.cortex.voxy.common.Logger.error("Could not restore original shader enable state", failure);
            }
        }
    }

    private static void releaseZoom(Run previous) {
        if (previous != null && previous.zoomControl != null) {
            previous.zoomControl.close();
            previous.zoomControl = null;
            previous.zoomPending = false;
        }
    }

    private static void invalidateSnapshot() {
        snapshotToken++;
        snapshotPending = false;
        snapshotSession = 0;
    }

    static final class Run {
        final UUID runId;
        final VoxyRenderSystem renderer;
        DebugTestProtocol.CommandKind command = DebugTestProtocol.CommandKind.BEGIN_RUN;
        long stepId;
        DebugShaderSettings shaderSettings;
        DebugZoomControl zoomControl;
        boolean zoomPending;
        long zoomDeadline;
        long shaderAwaitFrame = -1, shaderDrawMarker;
        boolean endingShaderRestore;
        PoseExpectation pose;
        Trace trace;
        ScreenshotRequest screenshot;
        Run(UUID runId, long stepId, VoxyRenderSystem renderer) {
            this.runId = runId; this.stepId = stepId; this.renderer = renderer;
        }
        boolean hasOutstandingOperation() {
            return this.zoomPending || this.shaderAwaitFrame >= 0 || this.pose != null || this.trace != null || this.screenshot != null
                    || snapshotPending;
        }
    }

    private static final class PoseExpectation {
        final String dimension;
        final double x, y, z;
        final float yaw, pitch;
        final long deadlineNanos, markerFrame;
        final DebugPoseStabilizer stabilizer;
        PoseExpectation(DebugTestCommandPayload command, long deadlineNanos, long markerFrame) {
            this.dimension = command.dimension(); this.x = command.x(); this.y = command.y();
            this.z = command.z(); this.yaw = command.yaw(); this.pitch = command.pitch();
            this.deadlineNanos = deadlineNanos; this.markerFrame = markerFrame;
            this.stabilizer = new DebugPoseStabilizer(markerFrame);
        }
    }

    private static final class Trace {
        final long endNanos, cadenceNanos;
        long nextSampleNanos;
        boolean finishing;
        Trace(long endNanos, long nextSampleNanos, long cadenceNanos) {
            this.endNanos = endNanos; this.nextSampleNanos = nextSampleNanos;
            this.cadenceNanos = cadenceNanos;
        }
    }

    private static final class ScreenshotRequest {
        final String filename;
        final long markerFrame;
        boolean captureStarted;
        ScreenshotRequest(String filename, long markerFrame) {
            this.filename = filename; this.markerFrame = markerFrame;
        }
    }

    private record Pose(long frame, String dimension,
                        double playerX, double playerY, double playerZ,
                        float playerYaw, float playerPitch,
                        double cameraX, double cameraY, double cameraZ,
                        float cameraYaw, float cameraPitch, boolean firstPerson) {
        static Pose unavailable() {
            return new Pose(-1, "", 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, false);
        }
    }

    private record BuildIdentity(long first, long second, long third, long fourth) {
        private static final BuildIdentity ZERO = new BuildIdentity(0, 0, 0, 0);
    }
}
