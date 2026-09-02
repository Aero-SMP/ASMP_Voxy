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
import me.cortex.voxy.client.core.rendering.selection.SelectionManifest.ContentClass;
import me.cortex.voxy.client.core.rendering.selection.SelectionManifest.ContentLayout;
import me.cortex.voxy.client.core.rendering.selection.SelectionManifest.Node;
import me.cortex.voxy.common.Logger;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryUtil;

import java.util.ArrayDeque;
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
    private static final int OUTPUT_SLOT_COUNT = 4;
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
    private GlBuffer nodeBuffer = new GlBuffer(Integer.BYTES).zero();
    private final int hizSampler = glGenSamplers();
    private final OutputSlot[] outputSlots = new OutputSlot[OUTPUT_SLOT_COUNT];
    private int nextOutputSlot;

    private final AtomicReference<PendingManifest> pendingManifest = new AtomicReference<>();
    private final AtomicReference<PredictionTiming> predictionTiming =
            new AtomicReference<>(PredictionTiming.DEFAULT);
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
    private PredictionSample availablePredictionSample;
    private double predictionAccuracy = 0.75;
    private long lastPredictionFeedbackSequence;
    private long predictionGeneration = Long.MIN_VALUE;
    private long lastPredictionSampleNanos;

    public VirtualSurfaceSelector() {
        for (int index = 0; index < this.outputSlots.length; index++) {
            this.outputSlots[index] = new OutputSlot();
        }
        glSamplerParameteri(this.hizSampler, GL_TEXTURE_MIN_FILTER, GL_NEAREST_MIPMAP_NEAREST);
        glSamplerParameteri(this.hizSampler, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glSamplerParameteri(this.hizSampler, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glSamplerParameteri(this.hizSampler, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    }

    /** Drops pooled selection state between world sessions. */
    public void resetSession() {
        this.activeEpoch++;
        releasePendingManifest();
        SelectionManifest active = this.activeManifest;
        this.activeManifest = null;
        if (active != null) active.close();
        this.disposePredictionSamples();
        this.releaseHandoff();
        this.selectionPool.clear();
        this.lastPredictionFeedbackSequence = 0;
        this.lastPredictionSampleNanos = 0;
        this.predictionGeneration = Long.MIN_VALUE;
    }

    /** May be called from the state thread; only the newest unpublished snapshot is retained. */
    public void publish(SelectionManifest manifest) {
        Objects.requireNonNull(manifest, "manifest");
        PendingManifest previous = this.pendingManifest.getAndSet(new PendingManifest(
                manifest, manifest.generation(), manifest.snapshotId()));
        if (previous != null && previous.manifest != null) previous.manifest.close();
    }

    /** Clears selection authority without allowing an older asynchronous result to reappear. */
    public void clear(long generation, long snapshotId) {
        PendingManifest previous = this.pendingManifest.getAndSet(
                new PendingManifest(null, generation, snapshotId));
        if (previous != null && previous.manifest != null) previous.manifest.close();
    }

    public void updatePredictionTiming(PredictionTiming timing) {
        this.predictionTiming.set(Objects.requireNonNull(timing, "timing"));
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
        if (manifest == null || manifest.nodeCount() == 0) return;

        Prediction prediction = this.predictionForFrame(viewport, this.predictionTiming.get());
        int outputCapacity = pass == Pass.REFINED ? manifest.nodeCount()
                : Math.min(manifest.nodeCount(), CONSERVATIVE_OUTPUT_CAPACITY);
        OutputSlot output = this.acquireOutputSlot(outputBytes(outputCapacity));
        if (output == null) return;
        SelectionTicket ticket = output.ticket;
        SelectionManifest retained = manifest.retain();
        try {
            ticket.begin(retained, this.activeEpoch, ++this.sequence, viewport.frameId,
                    pass, outputCapacity, System.nanoTime(), prediction.horizonSeconds);
        } catch (RuntimeException | Error failure) {
            retained.close();
            output.busy = false;
            throw failure;
        }
        try {
            this.uploadUniforms(viewport, hiz, manifest, prediction, pass, outputCapacity);
            output.buffer.zeroRange(0, OUTPUT_HEADER_BYTES);

            this.shader.bind();
            glBindBufferBase(GL_UNIFORM_BUFFER, SCENE_BINDING, this.uniformBuffer.id);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, NODE_BINDING, this.nodeBuffer.id);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, OUTPUT_BINDING, output.buffer.id);
            glBindTextureUnit(0, hiz.getHizTextureId());
            glBindSampler(0, this.hizSampler);
            glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT | GL_SHADER_STORAGE_BARRIER_BIT);
            glDispatchCompute((manifest.nodeCount() + 127) / 128, 1, 1);
            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);

            DownloadStream.INSTANCE.download(output.buffer, 0, OUTPUT_HEADER_BYTES,
                    (pointer, size) -> this.acceptHeader(ticket, pointer, size));
        } catch (RuntimeException | Error failure) {
            this.releaseTicket(ticket);
            throw failure;
        } finally {
            glBindSampler(0, 0);
            glBindTextureUnit(0, 0);
        }
    }

    private void installPendingManifest() {
        PendingManifest pending = this.pendingManifest.getAndSet(null);
        if (pending == null) return;
        if (pending.manifest == null) {
            this.activeEpoch++;
            this.releaseHandoff();
            SelectionManifest active = this.activeManifest;
            this.activeManifest = null;
            if (active != null) active.close();
            this.disposePredictionSamples();
            this.lastPredictionFeedbackSequence = 0;
            this.lastPredictionSampleNanos = 0;
            this.predictionGeneration = Long.MIN_VALUE;
            this.offer(SelectionBatch.empty(pending.generation, pending.snapshotId,
                    ++this.sequence, true));
            return;
        }

        long requiredBytes = Math.max(Integer.BYTES,
                Math.multiplyExact((long) pending.manifest.nodeCount(), NODE_BYTES));

        GlBuffer replacement = null;
        try {
            replacement = new GlBuffer(requiredBytes).zero();
            if (pending.manifest.nodeCount() != 0) {
                long pointer = UploadStream.INSTANCE.upload(replacement, 0, requiredBytes);
                this.writeManifest(pending.manifest, pointer);
                UploadStream.INSTANCE.commit();
            }
        } catch (RuntimeException | Error failure) {
            if (replacement != null) replacement.free();
            pending.manifest.close();
            throw failure;
        }

        this.activeEpoch++;
        this.releaseHandoff();
        SelectionManifest oldManifest = this.activeManifest;
        this.activeManifest = pending.manifest;
        if (oldManifest != null) oldManifest.close();
        if (this.predictionGeneration != pending.manifest.generation()) {
            this.disposePredictionSamples();
            this.lastPredictionFeedbackSequence = 0;
            this.lastPredictionSampleNanos = 0;
            this.predictionGeneration = pending.manifest.generation();
        }
        GlBuffer oldNode = this.nodeBuffer;
        this.nodeBuffer = replacement;
        oldNode.free();
        if (pending.manifest.nodeCount() == 0) {
            this.offer(SelectionBatch.empty(pending.manifest, ++this.sequence, true));
        }
    }

    private void writeManifest(SelectionManifest manifest, long pointer) {
        for (int nodeIndex = 0; nodeIndex < manifest.nodeCount(); nodeIndex++) {
            Node node = manifest.nodeAt(nodeIndex);
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
                ContentLayout state = node.layout(contentClass);
                putLongWords(pointer, manifest.availableMask(nodeIndex, contentClass)); pointer += 8;
                putLongWords(pointer, manifest.residentMask(nodeIndex, contentClass)); pointer += 8;
                putLongWords(pointer, manifest.renderableMask(nodeIndex, contentClass)); pointer += 8;
                putLongWords(pointer, manifest.inFlightMask(nodeIndex, contentClass)); pointer += 8;
                int meta = 0;
                if (allDependenciesResident(manifest, nodeIndex, contentClass,
                        state.dependencyCount(), false)) meta |= 1 << 2;
                if (allDependenciesResident(manifest, nodeIndex, contentClass,
                        state.neighborDependencyCount(), true)) meta |= 1 << 3;
                if (hasRequestableDependency(manifest, nodeIndex, contentClass,
                        state.dependencyCount(), false)) meta |= 1 << 4;
                if (hasRequestableDependency(manifest, nodeIndex, contentClass,
                        state.neighborDependencyCount(), true)) meta |= 1 << 5;
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
        putInt(pointer, manifest.nodeCount()); pointer += 4;
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

    private Prediction predictionForFrame(Viewport viewport, PredictionTiming timing) {
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
        if (timing.throughputBytesPerSecond() == 0) {
            transferSeconds = timing.outstandingBytes() == 0 ? 0.0 : 1.0;
        } else {
            transferSeconds = (double) timing.outstandingBytes()
                    / timing.throughputBytesPerSecond();
        }
        double baseHorizon = timing.roundTripMicros() / 1_000_000.0
                + transferSeconds + timing.meshingMicros() / 1_000_000.0;
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

    private void acceptHeader(SelectionTicket ticket, long pointer, long size) {
        try {
            SelectionManifest active = this.activeManifest;
            if (size != OUTPUT_HEADER_BYTES || active == null
                    || ticket.manifest.generation() != active.generation()) {
                this.releaseTicket(ticket);
                return;
            }
            long currentUnsigned = Integer.toUnsignedLong(MemoryUtil.memGetInt(pointer));
            long predictedUnsigned = Integer.toUnsignedLong(MemoryUtil.memGetInt(pointer + 4));
            ticket.headerMalformed = currentUnsigned > ticket.outputCapacity
                    || predictedUnsigned > ticket.outputCapacity;
            for (int word = 3; word < OUTPUT_HEADER_BYTES / Integer.BYTES; word++) {
                ticket.headerMalformed |= MemoryUtil.memGetInt(pointer + (long) word * 4) != 0;
            }
            ticket.currentCount = (int) Math.min(currentUnsigned, ticket.outputCapacity);
            ticket.predictedCount = (int) Math.min(predictedUnsigned, ticket.outputCapacity);
            ticket.overflow = MemoryUtil.memGetInt(pointer + 8);
            int inputCapacity = Math.addExact(ticket.currentCount, ticket.predictedCount);
            int plannedCapacity = Math.addExact(Math.multiplyExact(ticket.currentCount, 4),
                    ticket.predictedCount);
            ticket.batch = this.selectionPool.acquire(ticket.manifest, inputCapacity,
                    plannedCapacity);
            if (ticket.batch == null) {
                this.releaseTicket(ticket);
                return;
            }
            ticket.batch.begin(ticket.manifest.generation(), ticket.manifest.snapshotId(),
                    ticket.sequence, false);
            if (ticket.currentCount == 0) ticket.batch.beginInput(0);
            if (ticket.predictedCount == 0) ticket.batch.beginInput(1);

            this.scheduleQueue(ticket, 0, ticket.currentCount);
            this.scheduleQueue(ticket, 1, ticket.predictedCount);
            if (ticket.pendingQueues == 0) this.finishTicket(ticket);
        } catch (RuntimeException | Error failure) {
            this.abandonTicket(ticket, failure);
        }
    }

    private void scheduleQueue(SelectionTicket ticket, int queue, int count) {
        if (count == 0 || ticket.failed) return;
        long offset = OUTPUT_HEADER_BYTES
                + (long) queue * ticket.outputCapacity * OUTPUT_ENTRY_BYTES;
        long bytes = (long) count * OUTPUT_ENTRY_BYTES;
        ticket.pendingQueues++;
        try {
            DownloadStream.INSTANCE.download(ticket.slot.buffer, offset, bytes,
                    (pointer, size) -> this.acceptQueue(ticket, queue, count, pointer, size));
        } catch (RuntimeException | Error failure) {
            ticket.pendingQueues--;
            throw failure;
        }
    }

    private void acceptQueue(SelectionTicket ticket, int queue, int count,
                             long pointer, long size) {
        try {
            if (!ticket.failed) {
                if (size != (long) count * OUTPUT_ENTRY_BYTES) {
                    ticket.queueMalformed[queue] = true;
                    ticket.batch.beginInput(queue);
                } else {
                    ticket.queueMalformed[queue] = decodeQueue(ticket.manifest, pointer, queue,
                            count, ticket.pass, ticket.batch);
                }
            }
        } catch (RuntimeException | Error failure) {
            ticket.failed = true;
            Logger.error("Selection output decode failed", failure);
        } finally {
            ticket.pendingQueues--;
            if (ticket.pendingQueues == 0) {
                if (ticket.failed) this.releaseTicket(ticket);
                else this.finishTicket(ticket);
            }
        }
    }

    private void finishTicket(SelectionTicket ticket) {
        SelectionBatch batch = ticket.batch;
        if (batch == null) {
            this.releaseTicket(ticket);
            return;
        }
        try {
            SelectionManifest active = this.activeManifest;
            if (active == null || ticket.manifest.generation() != active.generation()) return;
            SelectionCutPlanner.plan(ticket.manifest, batch);
            boolean currentSnapshot = active != null
                    && ticket.epoch == this.activeEpoch && ticket.manifest == active;
            boolean frontierComplete = currentSnapshot && ticket.pass == Pass.REFINED
                    && !ticket.headerMalformed && ticket.overflow == 0
                    && !ticket.queueMalformed[0] && !ticket.queueMalformed[1]
                    && !batch.structureIncomplete();
            batch.setFrontierComplete(frontierComplete);
            if (frontierComplete
                    && Long.compareUnsigned(ticket.sequence,
                    this.lastPredictionFeedbackSequence) > 0) {
                this.lastPredictionFeedbackSequence = ticket.sequence;
                this.observePredictionUsefulness(ticket, batch);
            }
            this.offer(batch);
            ticket.batch = null;
        } catch (RuntimeException | Error failure) {
            Logger.error("Selection output planning failed", failure);
        } finally {
            if (ticket.batch != null) {
                ticket.batch.close();
                ticket.batch = null;
            }
            this.releaseTicket(ticket);
        }
    }

    private void abandonTicket(SelectionTicket ticket, Throwable failure) {
        ticket.failed = true;
        Logger.error("Selection readback failed", failure);
        if (ticket.pendingQueues == 0) this.releaseTicket(ticket);
    }

    private void releaseTicket(SelectionTicket ticket) {
        if (ticket.released) return;
        ticket.released = true;
        if (ticket.batch != null) {
            ticket.batch.close();
            ticket.batch = null;
        }
        SelectionManifest manifest = ticket.manifest;
        ticket.manifest = null;
        if (manifest != null) manifest.close();
        ticket.slot.busy = false;
    }

    static boolean decodeQueue(SelectionManifest manifest, long base, int queue,
                               int count, Pass expectedPass, SelectionBatch batch) {
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
            int entryFlags = MemoryUtil.memGetInt(address + 40);
            int reserved = MemoryUtil.memGetInt(address + 44);
            if (nodeIndex < 0 || nodeIndex >= manifest.nodeCount()
                    || (classes & ~7) != 0
                    || encodedPass != expectedPass.ordinal()
                    || !Float.isFinite(score) || score < 0.0f
                    || (entryFlags & ~1) != 0 || reserved != 0) {
                malformed = true;
                continue;
            }
            if (queue == 0 && (entryFlags & 1) != 0) batch.markStructureIncomplete();
            Node node = manifest.nodeAt(nodeIndex);
            int maskClasses = 0;
            if (exterior != 0) maskClasses |= 1;
            if (interior != 0) maskClasses |= 2;
            if (complex != 0) maskClasses |= 4;
            boolean invalidMask = (exterior & ~manifest.availableMask(
                    nodeIndex, ContentClass.EXTERIOR)) != 0
                    || (interior & ~manifest.availableMask(
                    nodeIndex, ContentClass.INTERIOR)) != 0
                    || (complex & ~manifest.availableMask(
                    nodeIndex, ContentClass.COMPLEX)) != 0;
            boolean metadataDemand = classes == 0 && maskClasses == 0
                    && (!node.descriptorReady() || (entryFlags & 1) != 0);
            if (invalidMask || classes != maskClasses
                    || classes == 0 && !metadataDemand
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
            PredictionSample sample = this.availablePredictionSample;
            this.availablePredictionSample = null;
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

    private void recyclePredictionSample(PredictionSample sample) {
        sample.reset();
        PredictionSample retained = this.availablePredictionSample;
        if (retained == null) {
            this.availablePredictionSample = sample;
        } else if (sample.storageBytes() > retained.storageBytes()) {
            retained.dispose();
            this.availablePredictionSample = sample;
        } else {
            sample.dispose();
        }
    }

    private void disposePredictionSamples() {
        while (!this.predictionSamples.isEmpty()) {
            this.predictionSamples.removeFirst().dispose();
        }
        if (this.availablePredictionSample != null) this.availablePredictionSample.dispose();
        this.availablePredictionSample = null;
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
        releasePendingManifest();
        SelectionManifest active = this.activeManifest;
        this.activeManifest = null;
        if (active != null) active.close();
        this.disposePredictionSamples();
        this.selectionPool.clear();
        this.releaseHandoff();
        for (OutputSlot slot : this.outputSlots) {
            if (slot.busy) {
                throw new IllegalStateException("selection output is still owned during free");
            }
        }
        this.shader.free();
        this.uniformBuffer.free();
        for (OutputSlot slot : this.outputSlots) {
            slot.buffer.free();
        }
        this.nodeBuffer.free();
        glDeleteSamplers(this.hizSampler);
    }


    private static boolean allDependenciesResident(SelectionManifest manifest, int nodeIndex,
                                                   ContentClass contentClass, int count,
                                                   boolean neighbor) {
        for (int index = 0; index < count; index++) {
            if (!(neighbor ? manifest.neighborResident(nodeIndex, contentClass, index)
                    : manifest.dependencyResident(nodeIndex, contentClass, index))) return false;
        }
        return true;
    }

    private static boolean hasRequestableDependency(SelectionManifest manifest, int nodeIndex,
                                                    ContentClass contentClass, int count,
                                                    boolean neighbor) {
        for (int index = 0; index < count; index++) {
            boolean resident = neighbor
                    ? manifest.neighborResident(nodeIndex, contentClass, index)
                    : manifest.dependencyResident(nodeIndex, contentClass, index);
            boolean inFlight = neighbor
                    ? manifest.neighborInFlight(nodeIndex, contentClass, index)
                    : manifest.dependencyInFlight(nodeIndex, contentClass, index);
            if (!resident && !inFlight) return true;
        }
        return false;
    }

    private void releasePendingManifest() {
        PendingManifest pending = this.pendingManifest.getAndSet(null);
        if (pending != null && pending.manifest != null) pending.manifest.close();
    }

    private OutputSlot acquireOutputSlot(long requiredBytes) {
        for (int offset = 0; offset < this.outputSlots.length; offset++) {
            int index = (this.nextOutputSlot + offset) % this.outputSlots.length;
            OutputSlot slot = this.outputSlots[index];
            if (slot.busy) continue;
            if (slot.buffer.size() < requiredBytes) {
                GlBuffer replacement = new GlBuffer(requiredBytes).zero();
                slot.buffer.free();
                slot.buffer = replacement;
            }
            slot.busy = true;
            this.nextOutputSlot = (index + 1) % this.outputSlots.length;
            return slot;
        }
        return null;
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

    private static final class OutputSlot {
        private GlBuffer buffer = new GlBuffer(outputBytes(1)).zero();
        private final SelectionTicket ticket = new SelectionTicket(this);
        private boolean busy;
    }

    private static final class SelectionTicket {
        private final OutputSlot slot;
        private final boolean[] queueMalformed = new boolean[OUTPUT_QUEUES];
        private SelectionManifest manifest;
        private SelectionBatch batch;
        private long epoch;
        private long sequence;
        private int frameId;
        private Pass pass;
        private int outputCapacity;
        private long submittedNanos;
        private float predictionHorizonSeconds;
        private int currentCount;
        private int predictedCount;
        private int overflow;
        private int pendingQueues;
        private boolean headerMalformed;
        private boolean failed;
        private boolean released = true;

        private SelectionTicket(OutputSlot slot) {
            this.slot = slot;
        }

        private void begin(SelectionManifest manifest, long epoch, long sequence, int frameId,
                           Pass pass, int outputCapacity, long submittedNanos,
                           float predictionHorizonSeconds) {
            if (!this.released || this.slot.busy && this.manifest != null) {
                throw new IllegalStateException("selection output ticket is already owned");
            }
            this.manifest = manifest;
            this.batch = null;
            this.epoch = epoch;
            this.sequence = sequence;
            this.frameId = frameId;
            this.pass = pass;
            this.outputCapacity = outputCapacity;
            this.submittedNanos = submittedNanos;
            this.predictionHorizonSeconds = predictionHorizonSeconds;
            this.currentCount = 0;
            this.predictedCount = 0;
            this.overflow = 0;
            this.pendingQueues = 0;
            this.headerMalformed = false;
            this.queueMalformed[0] = false;
            this.queueMalformed[1] = false;
            this.failed = false;
            this.released = false;
        }
    }

    private record Prediction(Vector3f delta, float angularPadding, float horizonSeconds) {}

    private static final class PredictionSample {
        private static final byte[] EMPTY_BYTES = new byte[0];
        private static final int[] EMPTY_INTS = new int[0];
        private static final long[] EMPTY_LONGS = new long[0];

        private long cameraDomain;
        private int frameId;
        private long deadlineNanos;
        private long accountedBytes;
        private long[] keys = EMPTY_LONGS;
        private long[] predicted = EMPTY_LONGS;
        private long[] matched = EMPTY_LONGS;
        private byte[] classes = EMPTY_BYTES;
        private int[] epochs = EMPTY_INTS;
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
            long requiredBytes = storageBytes(capacity);
            try {
                long[] keys = new long[capacity];
                long[] predicted = new long[capacity];
                long[] matched = new long[capacity];
                byte[] classes = new byte[capacity];
                int[] epochs = new int[capacity];
                this.keys = keys;
                this.predicted = predicted;
                this.matched = matched;
                this.classes = classes;
                this.epochs = epochs;
                this.epoch = 0;
                this.accountedBytes = requiredBytes;
            } catch (RuntimeException | Error failure) {
                this.dispose();
                throw failure;
            }
        }

        private long storageBytes() {
            return this.accountedBytes;
        }

        private void dispose() {
            reset();
            this.keys = EMPTY_LONGS;
            this.predicted = EMPTY_LONGS;
            this.matched = EMPTY_LONGS;
            this.classes = EMPTY_BYTES;
            this.epochs = EMPTY_INTS;
            this.epoch = 0;
            this.accountedBytes = 0;
        }

        private static long storageBytes(int capacity) {
            return Math.addExact(96L, arrayBytes(capacity));
        }

        private static long arrayBytes(int capacity) {
            long bytes = Math.multiplyExact(alignedArrayBytes(capacity, Long.BYTES), 3);
            bytes = Math.addExact(bytes, alignedArrayBytes(capacity, Byte.BYTES));
            return Math.addExact(bytes, alignedArrayBytes(capacity, Integer.BYTES));
        }

        private static long alignedArrayBytes(int elements, int width) {
            long bytes = Math.addExact(16L, Math.multiplyExact((long) elements, width));
            return Math.addExact(bytes, 7) & ~7L;
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
