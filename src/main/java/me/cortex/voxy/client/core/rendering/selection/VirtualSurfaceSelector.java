package me.cortex.voxy.client.core.rendering.selection;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.gl.shader.Shader;
import me.cortex.voxy.client.core.gl.shader.ShaderType;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.selection.SelectionBatch.Pass;
import me.cortex.voxy.client.core.rendering.selection.SelectionBatch.Priority;
import me.cortex.voxy.client.core.rendering.util.DownloadStream;
import me.cortex.voxy.client.core.rendering.util.HiZBuffer;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import me.cortex.voxy.client.lod.MemoryBudget;
import me.cortex.voxy.client.core.rendering.selection.SelectionManifest.ContentClass;
import me.cortex.voxy.client.core.rendering.selection.SelectionManifest.ContentState;
import me.cortex.voxy.client.core.rendering.selection.SelectionManifest.Node;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryUtil;

import java.util.ArrayDeque;
import java.util.BitSet;
import java.util.Iterator;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import static org.lwjgl.opengl.GL11C.GL_NEAREST;
import static org.lwjgl.opengl.GL11C.GL_NEAREST_MIPMAP_NEAREST;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_MAG_FILTER;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_MIN_FILTER;
import static org.lwjgl.opengl.GL12C.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL12C.GL_TEXTURE_WRAP_S;
import static org.lwjgl.opengl.GL12C.GL_TEXTURE_WRAP_T;
import static org.lwjgl.opengl.GL30C.glBindBufferBase;
import static org.lwjgl.opengl.GL31C.GL_UNIFORM_BUFFER;
import static org.lwjgl.opengl.GL33C.glBindSampler;
import static org.lwjgl.opengl.GL33C.glDeleteSamplers;
import static org.lwjgl.opengl.GL33C.glGenSamplers;
import static org.lwjgl.opengl.GL33C.glSamplerParameteri;
import static org.lwjgl.opengl.GL42C.GL_BUFFER_UPDATE_BARRIER_BIT;
import static org.lwjgl.opengl.GL42C.glMemoryBarrier;
import static org.lwjgl.opengl.GL43C.GL_SHADER_STORAGE_BARRIER_BIT;
import static org.lwjgl.opengl.GL43C.GL_SHADER_STORAGE_BUFFER;
import static org.lwjgl.opengl.GL43C.glDispatchCompute;
import static org.lwjgl.opengl.GL45C.glBindTextureUnit;

/**
 * Production GPU desired-cut selector for manifested 8-cubed Virtual Surface content.
 *
 * <p>Publication is thread-safe and contains no GL work.  Snapshots are installed at the next
 * render pass, and asynchronous readbacks are rejected if their captured snapshot has since
 * been replaced.  Refined handoffs are complete latest frontiers; conservative handoffs are
 * explicitly additions-only until their refined counterpart arrives.</p>
 */
public final class VirtualSurfaceSelector {
    private static final int NODE_WORDS = 56;
    private static final int NODE_BYTES = NODE_WORDS * Integer.BYTES;
    private static final int CONSERVATIVE_OUTPUT_CAPACITY = 32_768;
    private static final int OUTPUT_HEADER_BYTES = 16 * Integer.BYTES;
    private static final int OUTPUT_ENTRY_BYTES = 12 * Integer.BYTES;
    private static final int OUTPUT_QUEUES = 2;
    private static final int UNIFORM_BYTES = 320;
    private static final int NO_NODE = -1;
    private static final int MAX_PREDICTION_SAMPLES = 64;
    private static final double PREDICTION_EWMA_WEIGHT = 0.125;

    private static final int SCENE_BINDING = 0;
    private static final int NODE_BINDING = 1;
    private static final int OUTPUT_BINDING = 2;

    private final Shader shader = Shader.make()
            .define("USE_ZERO_ONE_DEPTH")
            .add(ShaderType.COMPUTE, "voxy:lod/selection/manifest_cut.comp")
            .compile();
    private final GlBuffer uniformBuffer = new GlBuffer(UNIFORM_BYTES).zero();
    private GlBuffer outputBuffer = new GlBuffer(outputBytes(1)).zero();
    private GlBuffer nodeBuffer = new GlBuffer(Integer.BYTES).zero();
    private final int hizSampler = glGenSamplers();

    /** Bound only for the lifetime of the session that owns these GPU resources. */
    private MemoryBudget memory;
    private MemoryBudget.Reservation fixedMemory;
    private MemoryBudget.Reservation nodeMemory;
    private MemoryBudget.Reservation outputMemory;

    private final AtomicReference<PendingManifest> pendingManifest = new AtomicReference<>();
    private final AtomicReference<SelectionTelemetry> telemetry =
            new AtomicReference<>(SelectionTelemetry.DEFAULT);
    private final AtomicReference<SelectionBatch> handoff = new AtomicReference<>();
    private final SelectionBatch.Pool selectionPool = new SelectionBatch.Pool(3);

    private SelectionManifest activeManifest;
    private long activeEpoch;
    private long sequence;

    private final Vector3d lastCamera = new Vector3d();
    private final Vector3d velocity = new Vector3d();
    private final Vector3f lastForward = new Vector3f();
    private long lastMotionNanos;
    private boolean hasMotionSample;
    private int predictionFrame = Integer.MIN_VALUE;
    private Prediction framePrediction = new Prediction(new Vector3f(), 0.0f, 0.1f);
    private final ArrayDeque<PredictionSample> predictionSamples = new ArrayDeque<>();
    private final ArrayDeque<PredictionSample> availablePredictionSamples = new ArrayDeque<>();
    private double predictionAccuracy = 0.75;
    private long lastPredictionFeedbackSequence;
    private long predictionGeneration = Long.MIN_VALUE;
    private long lastPredictionSampleNanos;

    public VirtualSurfaceSelector() {
        glSamplerParameteri(this.hizSampler, GL_TEXTURE_MIN_FILTER, GL_NEAREST_MIPMAP_NEAREST);
        glSamplerParameteri(this.hizSampler, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glSamplerParameteri(this.hizSampler, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glSamplerParameteri(this.hizSampler, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    }

    /** Accounts already-created fixed buffers before a session may publish manifested terrain. */
    public void bindMemory(MemoryBudget memory) {
        Objects.requireNonNull(memory, "memory");
        if (this.memory == memory) return;
        if (this.memory != null) {
            throw new IllegalStateException("another session still owns selector memory");
        }
        MemoryBudget.Reservation fixed = reserve(memory, MemoryBudget.Pool.MANIFEST,
                this.uniformBuffer.size());
        MemoryBudget.Reservation nodes = null;
        MemoryBudget.Reservation output = null;
        try {
            nodes = reserve(memory, MemoryBudget.Pool.MANIFEST, this.nodeBuffer.size());
            output = reserve(memory, MemoryBudget.Pool.MANIFEST, this.outputBuffer.size());
            this.selectionPool.bindMemory(memory);
        } catch (RuntimeException failure) {
            if (output != null) output.close();
            if (nodes != null) nodes.close();
            fixed.close();
            throw failure;
        }
        this.memory = memory;
        this.fixedMemory = fixed;
        this.nodeMemory = nodes;
        this.outputMemory = output;
    }

    /** Releases session accounting; outstanding readback reservations retire in their callbacks. */
    public void unbindMemory(MemoryBudget memory) {
        if (this.memory != memory) return;
        this.selectionPool.unbindMemory(memory);
        this.releaseHandoff();
        this.memory = null;
        closeReservation(this.fixedMemory);
        closeReservation(this.nodeMemory);
        closeReservation(this.outputMemory);
        this.fixedMemory = null;
        this.nodeMemory = null;
        this.outputMemory = null;
    }

    /** May be called from the state thread; only the newest unpublished snapshot is retained. */
    public void publish(SelectionManifest manifest) {
        Objects.requireNonNull(manifest, "manifest");
        this.pendingManifest.set(new PendingManifest(manifest, manifest.generation(),
                manifest.snapshotId()));
    }

    /** Clears selection authority without allowing an older asynchronous result to reappear. */
    public void clear(long generation, long snapshotId) {
        this.pendingManifest.set(new PendingManifest(null, generation, snapshotId));
    }

    public void updateTelemetry(SelectionTelemetry telemetry) {
        this.telemetry.set(Objects.requireNonNull(telemetry, "telemetry"));
    }

    /** Returns and removes the latest atomic handoff; intermediate snapshots may be coalesced. */
    public SelectionBatch poll() {
        return this.handoff.getAndSet(null);
    }

    public void select(Viewport viewport, HiZBuffer hiz, Pass pass) {
        Objects.requireNonNull(viewport, "viewport");
        Objects.requireNonNull(hiz, "hiz");
        Objects.requireNonNull(pass, "pass");
        this.installPendingManifest();
        SelectionManifest manifest = this.activeManifest;
        if (manifest == null || manifest.nodes().isEmpty()) return;

        Prediction prediction = this.predictionForFrame(viewport, this.telemetry.get());
        int outputCapacity = pass == Pass.REFINED ? manifest.nodes().size()
                : Math.min(manifest.nodes().size(), CONSERVATIVE_OUTPUT_CAPACITY);
        long downloadBytes = outputBytes(outputCapacity);
        MemoryBudget memory = this.memory;
        if (memory == null) return;
        MemoryBudget.Reservation readbackMemory = memory.tryReserve(
                MemoryBudget.Allocation.of(MemoryBudget.Pool.IN_FLIGHT,
                        Math.addExact(downloadBytes, 64L))).orElse(null);
        if (readbackMemory == null) return;
        this.uploadUniforms(viewport, hiz, manifest, prediction, pass, outputCapacity);
        this.outputBuffer.zeroRange(0, OUTPUT_HEADER_BYTES);

        this.shader.bind();
        glBindBufferBase(GL_UNIFORM_BUFFER, SCENE_BINDING, this.uniformBuffer.id);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, NODE_BINDING, this.nodeBuffer.id);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, OUTPUT_BINDING, this.outputBuffer.id);
        glBindTextureUnit(0, hiz.getHizTextureId());
        glBindSampler(0, this.hizSampler);
        glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT | GL_SHADER_STORAGE_BARRIER_BIT);
        glDispatchCompute((manifest.nodes().size() + 127) / 128, 1, 1);
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);

        SelectionTicket ticket = new SelectionTicket(manifest, this.activeEpoch,
                ++this.sequence, viewport.frameId, pass, outputCapacity, downloadBytes,
                System.nanoTime(), prediction.horizonSeconds, readbackMemory);
        try {
            DownloadStream.INSTANCE.download(this.outputBuffer, 0, downloadBytes,
                    (pointer, size) -> this.acceptDownload(ticket, pointer, size));
        } catch (RuntimeException | Error failure) {
            readbackMemory.close();
            throw failure;
        }
        glBindSampler(0, 0);
        glBindTextureUnit(0, 0);
    }

    private void installPendingManifest() {
        PendingManifest pending = this.pendingManifest.getAndSet(null);
        if (pending == null) return;
        if (pending.manifest != null && this.memory == null) {
            this.pendingManifest.compareAndSet(null, pending);
            return;
        }

        if (pending.manifest == null) {
            this.activeEpoch++;
            this.releaseHandoff();
            this.activeManifest = null;
            this.clearPredictionSamples();
            this.lastPredictionFeedbackSequence = 0;
            this.lastPredictionSampleNanos = 0;
            this.predictionGeneration = Long.MIN_VALUE;
            this.offer(SelectionBatch.empty(pending.generation, pending.snapshotId,
                    ++this.sequence, 0, Pass.REFINED, true));
            return;
        }

        long requiredBytes = Math.max(Integer.BYTES,
                Math.multiplyExact((long) pending.manifest.nodes().size(), NODE_BYTES));
        long requiredOutputBytes = outputBytes(Math.max(1, pending.manifest.nodes().size()));
        MemoryBudget.Reservation replacementNodeMemory = this.memory.tryReserve(
                MemoryBudget.Allocation.of(MemoryBudget.Pool.MANIFEST,
                        requiredBytes)).orElse(null);
        if (replacementNodeMemory == null) {
            this.pendingManifest.compareAndSet(null, pending);
            return;
        }
        MemoryBudget.Reservation replacementOutputMemory = null;
        if (this.outputBuffer.size() != requiredOutputBytes) {
            replacementOutputMemory = this.memory.tryReserve(
                    MemoryBudget.Allocation.of(MemoryBudget.Pool.MANIFEST,
                            requiredOutputBytes)).orElse(null);
            if (replacementOutputMemory == null) {
                replacementNodeMemory.close();
                this.pendingManifest.compareAndSet(null, pending);
                return;
            }
        }

        GlBuffer replacement = null;
        GlBuffer replacementOutput = null;
        try {
            replacement = new GlBuffer(requiredBytes).zero();
            if (!pending.manifest.nodes().isEmpty()) {
                long pointer = UploadStream.INSTANCE.upload(replacement, 0, requiredBytes);
                this.writeManifest(pending.manifest, pointer);
                UploadStream.INSTANCE.commit();
            }
            if (replacementOutputMemory != null) {
                replacementOutput = new GlBuffer(requiredOutputBytes).zero();
            }
        } catch (RuntimeException | Error failure) {
            if (replacementOutput != null) replacementOutput.free();
            if (replacement != null) replacement.free();
            if (replacementOutputMemory != null) replacementOutputMemory.close();
            replacementNodeMemory.close();
            throw failure;
        }

        this.activeEpoch++;
        this.releaseHandoff();
        this.activeManifest = pending.manifest;
        if (this.predictionGeneration != pending.manifest.generation()) {
            this.clearPredictionSamples();
            this.lastPredictionFeedbackSequence = 0;
            this.lastPredictionSampleNanos = 0;
            this.predictionGeneration = pending.manifest.generation();
        }
        GlBuffer oldNode = this.nodeBuffer;
        MemoryBudget.Reservation oldNodeMemory = this.nodeMemory;
        this.nodeBuffer = replacement;
        this.nodeMemory = replacementNodeMemory;
        oldNode.free();
        closeReservation(oldNodeMemory);
        if (replacementOutput != null) {
            GlBuffer oldOutput = this.outputBuffer;
            MemoryBudget.Reservation oldOutputMemory = this.outputMemory;
            this.outputBuffer = replacementOutput;
            this.outputMemory = replacementOutputMemory;
            oldOutput.free();
            closeReservation(oldOutputMemory);
        }
        if (pending.manifest.nodes().isEmpty()) {
            this.offer(SelectionBatch.empty(pending.generation, pending.snapshotId,
                    ++this.sequence, 0, Pass.REFINED, true));
        }
    }

    private void writeManifest(SelectionManifest manifest, long pointer) {
        for (Node node : manifest.nodes()) {
            long base = pointer;
            putInt(pointer, (int) (node.sectionKey() >>> 32)); pointer += 4;
            putInt(pointer, (int) node.sectionKey()); pointer += 4;
            putInt(pointer, node.parentHandle() == SelectionManifest.NO_HANDLE
                    ? NO_NODE : manifest.indexForHandle(node.parentHandle())); pointer += 4;
            int[] children = node.childHandlesInternal();
            int childMask = node.manifestedChildMask();
            int flags = childMask | (node.tightBounds() == null ? 0 : 1 << 8)
                    | (node.descriptorReady() ? 1 << 9 : 0);
            putInt(pointer, flags); pointer += 4;
            for (int child : children) {
                putInt(pointer, child == SelectionManifest.NO_HANDLE
                        ? NO_NODE : manifest.indexForHandle(child));
                pointer += 4;
            }
            SelectionManifest.TightBounds bounds = node.tightBounds();
            if (bounds == null) {
                putInt(pointer, 0); pointer += 4;
                putInt(pointer, 0); pointer += 4;
                putInt(pointer, 0); pointer += 4;
            } else {
                putInt(pointer, bounds.minX() | bounds.maxX() << 16); pointer += 4;
                putInt(pointer, bounds.minY() | bounds.maxY() << 16); pointer += 4;
                putInt(pointer, bounds.minZ() | bounds.maxZ() << 16); pointer += 4;
            }
            putInt(pointer, (int) node.geometricErrorQ16()); pointer += 4;

            long canonicalBytes = 0;
            long geometryBytes = 0;
            long completionMicros = 0;
            for (ContentClass contentClass : ContentClass.values()) {
                ContentState state = node.content(contentClass);
                putLongWords(pointer, state.availableMask()); pointer += 8;
                putLongWords(pointer, state.residentMask()); pointer += 8;
                putLongWords(pointer, state.renderableMask()); pointer += 8;
                putLongWords(pointer, state.inFlightMask()); pointer += 8;
                int meta = 0;
                if (state.residentDependenciesInternal().cardinality()
                        == state.dependencyHandlesInternal().length) meta |= 1 << 2;
                if (state.residentNeighborDependenciesInternal().cardinality()
                        == state.neighborDependencyHandlesInternal().length) meta |= 1 << 3;
                if (hasRequestableDependency(state.residentDependenciesInternal(),
                        state.inFlightDependenciesInternal(),
                        state.dependencyHandlesInternal().length)) meta |= 1 << 4;
                if (hasRequestableDependency(state.residentNeighborDependenciesInternal(),
                        state.inFlightNeighborDependenciesInternal(),
                        state.neighborDependencyHandlesInternal().length)) meta |= 1 << 5;
                meta |= state.boundaryFaceMask() << 8;
                putInt(pointer, meta); pointer += 4;
                // Two reserved words retain the stable GPU node stride. Per-microtile camera
                // visibility has already been intersected into availableMask by the immutable
                // manifest builder, so a stale scalar class domain cannot suppress coverage.
                putLongWords(pointer, 0); pointer += 8;
                canonicalBytes = saturatingAdd(canonicalBytes, state.estimatedCanonicalBytes());
                geometryBytes = saturatingAdd(geometryBytes, state.estimatedGeometryBytes());
                completionMicros = Math.max(completionMicros,
                        state.estimatedCompletionMicros());
            }
            putLongWords(pointer, canonicalBytes); pointer += 8;
            putLongWords(pointer, geometryBytes); pointer += 8;
            putInt(pointer, (int) Math.min(0xffff_ffffL, completionMicros)); pointer += 4;
            putInt(pointer, 0); pointer += 4;
            putInt(pointer, 0); pointer += 4;
            if (pointer - base != NODE_BYTES) {
                throw new IllegalStateException("selection node packing drift");
            }
        }
    }

    private void uploadUniforms(Viewport viewport, HiZBuffer hiz, SelectionManifest manifest,
                                Prediction prediction, Pass pass, int outputCapacity) {
        long pointer = UploadStream.INSTANCE.upload(this.uniformBuffer, 0, UNIFORM_BYTES);
        long start = pointer;
        viewport.MVP.getToAddress(pointer); pointer += 64;
        viewport.section.getToAddress(pointer); pointer += 12;
        putInt(pointer, hiz.getPackedLevels()); pointer += 4;
        viewport.innerTranslation.getToAddress(pointer); pointer += 12;
        float threshold = Math.max(1.0f, VoxyConfig.CONFIG.subDivisionSize);
        MemoryUtil.memPutFloat(pointer, threshold); pointer += 4;
        for (int planeIndex = 0; planeIndex < 6; planeIndex++) {
            viewport.frustumPlanes[planeIndex].getToAddress(pointer); pointer += 16;
        }
        prediction.delta.getToAddress(pointer); pointer += 12;
        MemoryUtil.memPutFloat(pointer, prediction.angularPadding); pointer += 4;
        putInt(pointer, manifest.nodes().size()); pointer += 4;
        putInt(pointer, viewport.frameId); pointer += 4;
        putInt(pointer, pass.ordinal()); pointer += 4;
        putInt(pointer, (int) manifest.cameraVisibilityDomain()); pointer += 4;
        putInt(pointer, (int) (manifest.cameraVisibilityDomain() >>> 32)); pointer += 4;
        putInt(pointer, outputCapacity); pointer += 4;
        putInt(pointer, 0); pointer += 4;
        putInt(pointer, 0); pointer += 4;
        float projectionScale = Math.abs(viewport.projection.m11()) * viewport.height * 0.5f;
        if (!Float.isFinite(projectionScale) || projectionScale <= 0.0f) {
            projectionScale = Math.max(1, viewport.height);
        }
        MemoryUtil.memPutFloat(pointer, projectionScale); pointer += 4;
        MemoryUtil.memPutFloat(pointer, prediction.horizonSeconds); pointer += 4;
        MemoryUtil.memPutFloat(pointer, pass == Pass.CONSERVATIVE ? 0.0001f : 0.00005f);
        pointer += 4;
        MemoryUtil.memPutFloat(pointer, 0.0f); pointer += 4;
        while (pointer - start < UNIFORM_BYTES) {
            putInt(pointer, 0);
            pointer += 4;
        }
        UploadStream.INSTANCE.commit();
    }

    private Prediction predictionForFrame(Viewport viewport, SelectionTelemetry telemetry) {
        if (this.predictionFrame == viewport.frameId) return this.framePrediction;
        this.predictionFrame = viewport.frameId;
        long now = System.nanoTime();
        Vector3d camera = new Vector3d(viewport.cameraX, viewport.cameraY, viewport.cameraZ);
        Vector3f forward = new Vector3f(-viewport.modelView.m02(), -viewport.modelView.m12(),
                -viewport.modelView.m22());
        if (forward.lengthSquared() > 0.000001f) forward.normalize();
        else forward.set(0.0f, 0.0f, -1.0f);

        double angularVelocity = 0.0;
        if (this.hasMotionSample) {
            double seconds = Math.max(1.0 / 240.0,
                    Math.min(0.25, (now - this.lastMotionNanos) / 1_000_000_000.0));
            Vector3d instantaneous = new Vector3d(camera).sub(this.lastCamera).div(seconds);
            if (instantaneous.lengthSquared() > 512.0 * 512.0) {
                this.velocity.zero();
            } else {
                this.velocity.lerp(instantaneous, 0.25);
            }
            float cosine = Math.max(-1.0f, Math.min(1.0f, forward.dot(this.lastForward)));
            angularVelocity = Math.acos(cosine) / seconds;
        } else {
            this.velocity.zero();
            this.hasMotionSample = true;
        }
        this.lastCamera.set(camera);
        this.lastForward.set(forward);
        this.lastMotionNanos = now;

        double transferSeconds;
        if (telemetry.throughputBytesPerSecond() == 0) {
            transferSeconds = telemetry.outstandingBytes() == 0 ? 0.0 : 1.0;
        } else {
            transferSeconds = (double) telemetry.outstandingBytes()
                    / telemetry.throughputBytesPerSecond();
        }
        double baseHorizon = telemetry.roundTripMicros() / 1_000_000.0
                + transferSeconds + telemetry.meshingMicros() / 1_000_000.0;
        double confidence = 0.2 + 0.8 * this.predictionAccuracy;
        float horizon = (float) Math.max(0.1, Math.min(1.0, baseHorizon * confidence));
        Vector3f delta = new Vector3f((float) (this.velocity.x * horizon),
                (float) (this.velocity.y * horizon), (float) (this.velocity.z * horizon));
        float maximumSweep = 1024.0f * Math.max(0.25f, (float) this.predictionAccuracy);
        if (delta.lengthSquared() > maximumSweep * maximumSweep) delta.normalize(maximumSweep);
        float angularPadding = (float) Math.min(Math.toRadians(60.0),
                angularVelocity * horizon * confidence);
        return this.framePrediction = new Prediction(delta, angularPadding, horizon);
    }

    private void acceptDownload(SelectionTicket ticket, long pointer, long size) {
        try {
            acceptDownloadRetained(ticket, pointer, size);
        } finally {
            ticket.readbackMemory.close();
        }
    }

    private void acceptDownloadRetained(SelectionTicket ticket, long pointer, long size) {
        SelectionManifest active = this.activeManifest;
        if (active == null || ticket.manifest.generation() != active.generation()
                || size < ticket.outputBytes) return;
        // Object residency and descriptor discovery may replace the immutable GPU snapshot while
        // an asynchronous readback is in flight. Handles are append-only within one root, so the
        // old result is still safe for requesting/retaining content. It is not, however, current
        // enough to cancel demand or retire coverage. Only an exact active-snapshot result may be
        // published as a complete frontier below.
        boolean currentSnapshot = ticket.epoch == this.activeEpoch
                && ticket.manifest == active;
        boolean malformed = false;
        long currentUnsigned = Integer.toUnsignedLong(MemoryUtil.memGetInt(pointer));
        long predictedUnsigned = Integer.toUnsignedLong(MemoryUtil.memGetInt(pointer + 4));
        malformed |= currentUnsigned > ticket.outputCapacity
                || predictedUnsigned > ticket.outputCapacity;
        int currentCount = (int) Math.min(currentUnsigned, ticket.outputCapacity);
        int predictedCount = (int) Math.min(predictedUnsigned, ticket.outputCapacity);
        int overflow = MemoryUtil.memGetInt(pointer + 8);
        int inputCapacity = Math.addExact(currentCount, predictedCount);
        int outputCapacity = Math.addExact(Math.multiplyExact(currentCount, 4), predictedCount);
        SelectionBatch batch = this.selectionPool.acquire(ticket.manifest, inputCapacity,
                outputCapacity);
        if (batch == null) return;
        batch.begin(ticket.manifest.generation(), ticket.manifest.snapshotId(), ticket.sequence,
                ticket.frameId, ticket.pass, false);
        boolean currentMalformed;
        boolean predictedMalformed;
        try {
            currentMalformed = this.decodeQueue(ticket.manifest, pointer, 0, currentCount,
                    ticket.outputCapacity, ticket.pass, batch);
            predictedMalformed = this.decodeQueue(ticket.manifest, pointer, 1, predictedCount,
                    ticket.outputCapacity, ticket.pass, batch);
            SelectionCutPlanner.plan(ticket.manifest, batch);
        } catch (RuntimeException | Error failure) {
            batch.close();
            throw failure;
        }
        boolean frontierComplete = currentSnapshot && ticket.pass == Pass.REFINED
                && !malformed && overflow == 0
                && !currentMalformed && !predictedMalformed;
        batch.setFrontierComplete(frontierComplete);
        if (frontierComplete
                && Long.compareUnsigned(ticket.sequence,
                this.lastPredictionFeedbackSequence) > 0) {
            this.lastPredictionFeedbackSequence = ticket.sequence;
            this.observePredictionUsefulness(ticket, batch);
        }
        this.offer(batch);
    }

    static boolean decodeQueue(SelectionManifest manifest, long pointer, int queue,
                               int count, int outputCapacity, Pass expectedPass,
                               SelectionBatch batch) {
        long base = pointer + OUTPUT_HEADER_BYTES
                + (long) queue * outputCapacity * OUTPUT_ENTRY_BYTES;
        batch.beginInput(queue);
        boolean malformed = false;
        for (int entry = 0; entry < count; entry++) {
            long address = base + (long) entry * OUTPUT_ENTRY_BYTES;
            int nodeIndex = MemoryUtil.memGetInt(address);
            int classes = MemoryUtil.memGetInt(address + 4);
            float score = Float.intBitsToFloat(MemoryUtil.memGetInt(address + 8));
            int encodedPass = MemoryUtil.memGetInt(address + 12);
            long exterior = getLongWords(address + 16);
            long interior = getLongWords(address + 24);
            long complex = getLongWords(address + 32);
            if (nodeIndex < 0 || nodeIndex >= manifest.nodes().size()
                    || (classes & ~7) != 0
                    || encodedPass != expectedPass.ordinal()
                    || !Float.isFinite(score) || score < 0.0f) {
                malformed = true;
                continue;
            }
            Node node = manifest.nodes().get(nodeIndex);
            int maskClasses = 0;
            if (exterior != 0) maskClasses |= 1;
            if (interior != 0) maskClasses |= 2;
            if (complex != 0) maskClasses |= 4;
            boolean invalidMask = (exterior & ~node.exterior().availableMask()) != 0
                    || (interior & ~node.interior().availableMask()) != 0
                    || (complex & ~node.complex().availableMask()) != 0;
            boolean missingDescriptorDemand = !node.descriptorReady()
                    && classes == 0 && maskClasses == 0;
            if (invalidMask || classes != maskClasses
                    || classes == 0 && !missingDescriptorDemand
                    || classes != 0 && !node.descriptorReady()) {
                malformed = true;
                continue;
            }
            batch.appendInput(queue, nodeIndex, score, exterior, interior, complex);
        }
        return malformed;
    }

    private void offer(SelectionBatch batch) {
        // Every entry carries its own authority bit, so a newer snapshot can safely coalesce
        // an unconsumed older one. Pool synchronization also closes the unbind/callback race.
        this.selectionPool.offerLatest(this.handoff, batch);
    }

    private void releaseHandoff() {
        SelectionBatch old = this.handoff.getAndSet(null);
        if (old != null) old.close();
    }

    private void observePredictionUsefulness(SelectionTicket ticket, SelectionBatch batch) {
        long now = System.nanoTime();
        Iterator<PredictionSample> samples = this.predictionSamples.iterator();
        while (samples.hasNext()) {
            PredictionSample sample = samples.next();
            if (sample.cameraDomain != ticket.manifest.cameraVisibilityDomain()) {
                // A movement query temporarily switches selection to conservative domain zero.
                // Preserve the prior-domain sample long enough for a same-domain reply, but never
                // score incomparable masks from another visibility domain.
                if (now - sample.deadlineNanos > 2_000_000_000L) {
                    samples.remove();
                    this.recyclePredictionSample(sample);
                }
                continue;
            }
            if (sample.frameId != ticket.frameId) sample.observe(batch, 0);
            if (sample.deadlineNanos - now <= 0) {
                this.updatePredictionAccuracy(sample.usefulness());
                samples.remove();
                this.recyclePredictionSample(sample);
            }
        }

        if (now - this.lastPredictionSampleNanos >= 50_000_000L) {
            PredictionSample sample = this.availablePredictionSamples.pollFirst();
            if (sample == null) sample = new PredictionSample();
            if (sample.capture(ticket.manifest.cameraVisibilityDomain(), ticket.frameId,
                    deadline(ticket.submittedNanos, ticket.predictionHorizonSeconds), batch, 1)) {
                this.predictionSamples.addLast(sample);
                this.lastPredictionSampleNanos = now;
            } else {
                this.recyclePredictionSample(sample);
            }
        }
        while (this.predictionSamples.size() > MAX_PREDICTION_SAMPLES) {
            this.recyclePredictionSample(this.predictionSamples.removeFirst());
        }
    }

    private void clearPredictionSamples() {
        while (!this.predictionSamples.isEmpty()) {
            this.recyclePredictionSample(this.predictionSamples.removeFirst());
        }
    }

    private void recyclePredictionSample(PredictionSample sample) {
        sample.reset();
        if (this.availablePredictionSamples.size() < MAX_PREDICTION_SAMPLES) {
            this.availablePredictionSamples.addFirst(sample);
        }
    }

    private void updatePredictionAccuracy(double usefulness) {
        this.predictionAccuracy += (usefulness - this.predictionAccuracy)
                * PREDICTION_EWMA_WEIGHT;
        this.predictionAccuracy = Math.max(0.05, Math.min(1.0, this.predictionAccuracy));
    }

    private static long deadline(long submittedNanos, float horizonSeconds) {
        long horizonNanos = (long) (Math.max(0.05f, horizonSeconds) * 1_000_000_000.0);
        if (Long.MAX_VALUE - submittedNanos < horizonNanos) return Long.MAX_VALUE;
        return submittedNanos + horizonNanos;
    }

    public void free() {
        this.activeEpoch++;
        this.pendingManifest.set(null);
        this.activeManifest = null;
        this.clearPredictionSamples();
        if (this.memory != null) this.selectionPool.unbindMemory(this.memory);
        this.releaseHandoff();
        this.shader.free();
        this.uniformBuffer.free();
        this.outputBuffer.free();
        this.nodeBuffer.free();
        closeReservation(this.fixedMemory);
        closeReservation(this.outputMemory);
        closeReservation(this.nodeMemory);
        this.fixedMemory = null;
        this.outputMemory = null;
        this.nodeMemory = null;
        this.memory = null;
        glDeleteSamplers(this.hizSampler);
    }


    private static boolean hasRequestableDependency(BitSet resident, BitSet inFlight,
                                                    int count) {
        for (int index = 0; index < count; index++) {
            if (!resident.get(index) && !inFlight.get(index)) return true;
        }
        return false;
    }

    private static long saturatingAdd(long left, long right) {
        if (Long.MAX_VALUE - left < right) return Long.MAX_VALUE;
        return left + right;
    }

    private static long outputBytes(int capacity) {
        if (capacity <= 0 || capacity > SelectionManifest.MAX_NODES) {
            throw new IllegalArgumentException("invalid selection output capacity");
        }
        return OUTPUT_HEADER_BYTES
                + (long) capacity * OUTPUT_ENTRY_BYTES * OUTPUT_QUEUES;
    }

    private static MemoryBudget.Reservation reserve(MemoryBudget memory,
                                                       MemoryBudget.Pool pool,
                                                       long bytes) {
        return memory.tryReserve(MemoryBudget.Allocation.of(pool, bytes))
                .orElseThrow(() -> new IllegalStateException(
                        "Virtual Surface memory budget cannot admit selector buffers"));
    }

    private static void closeReservation(MemoryBudget.Reservation reservation) {
        if (reservation != null) reservation.close();
    }

    private static void putInt(long pointer, int value) {
        MemoryUtil.memPutInt(pointer, value);
    }

    private static void putLongWords(long pointer, long value) {
        MemoryUtil.memPutInt(pointer, (int) value);
        MemoryUtil.memPutInt(pointer + 4, (int) (value >>> 32));
    }

    private static long getLongWords(long pointer) {
        return Integer.toUnsignedLong(MemoryUtil.memGetInt(pointer))
                | Integer.toUnsignedLong(MemoryUtil.memGetInt(pointer + 4)) << 32;
    }

    private record PendingManifest(SelectionManifest manifest, long generation, long snapshotId) {}
    private record SelectionTicket(SelectionManifest manifest, long epoch, long sequence,
                                   int frameId, Pass pass, int outputCapacity,
                                   long outputBytes, long submittedNanos,
                                   float predictionHorizonSeconds,
                                   MemoryBudget.Reservation readbackMemory) {}
    private record Prediction(Vector3f delta, float angularPadding, float horizonSeconds) {}

    private static final class PredictionSample {
        private long cameraDomain;
        private int frameId;
        private long deadlineNanos;
        private long[] keys = new long[0];
        private long[] predicted = new long[0];
        private long[] matched = new long[0];
        private byte[] classes = new byte[0];
        private int[] epochs = new int[0];
        private int epoch;
        private int size;

        private boolean capture(long cameraDomain, int frameId, long deadlineNanos,
                                SelectionBatch batch, int queue) {
            int capacity = tableCapacity(Math.multiplyExact(batch.inputCount(queue), 3));
            ensureCapacity(capacity);
            beginEpoch();
            this.cameraDomain = cameraDomain;
            this.frameId = frameId;
            this.deadlineNanos = deadlineNanos;
            int start = batch.inputOffset(queue);
            int end = start + batch.inputCount(queue);
            for (int row = start; row < end; row++) {
                long key = batch.manifest().nodeAt(batch.inputNodeIndex(row)).sectionKey();
                for (int content = 0; content < 3; content++) {
                    long mask = batch.inputMask(row, content);
                    if (mask != 0) merge(key, content, mask, false);
                }
            }
            return this.size != 0;
        }

        private void observe(SelectionBatch batch, int queue) {
            int start = batch.inputOffset(queue);
            int end = start + batch.inputCount(queue);
            for (int row = start; row < end; row++) {
                long key = batch.manifest().nodeAt(batch.inputNodeIndex(row)).sectionKey();
                for (int content = 0; content < 3; content++) {
                    long mask = batch.inputMask(row, content);
                    if (mask != 0) merge(key, content, mask, true);
                }
            }
        }

        private void merge(long key, int contentClass, long mask, boolean matching) {
            int index = mix(key, contentClass) & (this.keys.length - 1);
            while (this.epochs[index] == this.epoch) {
                if (this.keys[index] == key && this.classes[index] == contentClass) {
                    if (matching) this.matched[index] |= this.predicted[index] & mask;
                    else this.predicted[index] |= mask;
                    return;
                }
                index = index + 1 & (this.keys.length - 1);
            }
            if (matching) return;
            this.epochs[index] = this.epoch;
            this.keys[index] = key;
            this.classes[index] = (byte) contentClass;
            this.predicted[index] = mask;
            this.matched[index] = 0;
            this.size++;
        }

        private double usefulness() {
            long predictedBits = 0, matchedBits = 0;
            for (int index = 0; index < this.keys.length; index++) {
                if (this.epochs[index] != this.epoch) continue;
                predictedBits += Long.bitCount(this.predicted[index]);
                matchedBits += Long.bitCount(this.matched[index]);
            }
            return predictedBits == 0 ? 1.0 : (double) matchedBits / predictedBits;
        }

        private void reset() { this.size = 0; }

        private void beginEpoch() {
            if (++this.epoch == 0) {
                java.util.Arrays.fill(this.epochs, 0);
                this.epoch = 1;
            }
            this.size = 0;
        }

        private void ensureCapacity(int capacity) {
            if (capacity <= this.keys.length) return;
            this.keys = new long[capacity];
            this.predicted = new long[capacity];
            this.matched = new long[capacity];
            this.classes = new byte[capacity];
            this.epochs = new int[capacity];
            this.epoch = 0;
        }

        private static int tableCapacity(int entries) {
            int target = Math.max(16, Math.multiplyExact(entries, 2));
            int capacity = 1;
            while (capacity < target) capacity = Math.multiplyExact(capacity, 2);
            return capacity;
        }

        private static int mix(long key, int contentClass) {
            long value = key ^ 0x9e3779b97f4a7c15L * (contentClass + 1L);
            value ^= value >>> 33;
            value *= 0xff51afd7ed558ccdL;
            value ^= value >>> 33;
            return (int) value;
        }
    }
}
