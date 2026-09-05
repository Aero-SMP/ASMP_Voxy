package me.cortex.voxy.client.core;

import com.mojang.blaze3d.platform.GlConst;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.model.ModelBakerySubsystem;
import me.cortex.voxy.client.core.rendering.ChunkBoundRenderer;
import me.cortex.voxy.client.core.rendering.RenderDistanceTracker;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.building.BuiltSection;
import me.cortex.voxy.client.core.rendering.building.SectionMesher;
import me.cortex.voxy.client.core.rendering.hierarchical.AsyncNodeManager;
import me.cortex.voxy.client.core.rendering.hierarchical.SectionPublicationState;
import me.cortex.voxy.client.core.rendering.hierarchical.HierarchicalOcclusionTraverser;
import me.cortex.voxy.client.core.rendering.hierarchical.NodeCleaner;
import me.cortex.voxy.client.core.rendering.section.MDICSectionRenderer;
import me.cortex.voxy.client.core.rendering.section.BasicSectionGeometryData;
import me.cortex.voxy.client.core.rendering.util.DownloadStream;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import me.cortex.voxy.client.iris.IrisUtil;
import me.cortex.voxy.client.lod.ClientLodClient;
import me.cortex.voxy.client.iris.IGetIrisVoxyPipelineData;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.client.core.model.CatalogMapper;
import net.irisshaders.iris.Iris;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.lwjgl.opengl.GL11.GL_VIEWPORT;
import static org.lwjgl.opengl.GL11.glEnable;
import static org.lwjgl.opengl.GL11.glFinish;
import static org.lwjgl.opengl.GL11.glGetIntegerv;
import static org.lwjgl.opengl.GL11.glViewport;
import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL20C.glUseProgram;
import static org.lwjgl.opengl.GL30.glGetIntegeri;
import static org.lwjgl.opengl.GL30C.*;
import static org.lwjgl.opengl.GL33.glBindSampler;
import static org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER;
import static org.lwjgl.opengl.GL43C.GL_SHADER_STORAGE_BUFFER_BINDING;

public class VoxyRenderSystem {
    private final CatalogMapper mapper;
    private ModelBakerySubsystem modelService;
    private BasicSectionGeometryData geometryData;
    private AsyncNodeManager nodeManager;
    private final AtomicLong regionalSectionRevision = new AtomicLong(1);
    private static final long[] PUBLICATION_LATENCY_BUCKET_NANOS = {
            1_000_000L, 4_000_000L, 16_000_000L, 50_000_000L, 200_000_000L
    };
    private final PublicationLatencyCounters[][] regionalPublicationLatencies =
            createPublicationLatencyCounters();

    /** The renderer-local translation table populated exclusively from the catalog. */
    public CatalogMapper getMapper() {
        return this.mapper;
    }

    public interface SectionPublication extends AutoCloseable {
        boolean activationFencePassed();
        Optional<UploadOutcome> takeUploadOutcome();
        void abandon(Runnable resolved);
        boolean retirementFencePassed();
        @Override void close();
    }

    public enum AllocationStatus {
        NO_CONTIGUOUS_GEOMETRY_SPACE, NO_SECTION_ID, TOPOLOGY_NOT_READY, IMPOSSIBLE, STALE
    }

    public record AllocationBlock(BuiltSection geometry, AllocationStatus status,
                                  long requiredUnits, long largestFreeUnits, long prerequisite,
                                  AsyncNodeManager.PublicationProgress observed) {}

    public enum UploadStatus { ACTIVATED, RETURNED, CANCELLED, FAILED }
    public record UploadOutcome(UploadStatus status, AllocationBlock block, Throwable failure) {}
    public enum SubmissionStatus { ACCEPTED, BUSY }
    public record SubmissionAttempt(SubmissionStatus status, List<SectionPublication> publications) {}

    public record SectionSubmission(long position, BuiltSection geometry, boolean coverage,
                                    long meshCompletedNanos,
                                    Optional<SectionPublication> previous,
                                    BooleanSupplier current, Runnable reserved) {
        public SectionSubmission {
            Objects.requireNonNull(geometry, "geometry");
            Objects.requireNonNull(previous, "previous");
            Objects.requireNonNull(current, "current");
            Objects.requireNonNull(reserved, "reserved");
        }
    }

    public interface SectionPublisher {
        SubmissionAttempt tryPublishBatch(List<SectionSubmission> submissions);
        AsyncNodeManager.PublicationProgress progress();
        void setProgressListener(Runnable listener);
        void clearProgressListener(Runnable listener);
        void coarsen(long parent, Runnable success, Consumer<Throwable> failure);
    }

    public SectionPublisher regionalSectionPublisher() {
        return new SectionPublisher() {
            @Override
            public SubmissionAttempt tryPublishBatch(List<SectionSubmission> submissions) {
                return publishRegionalSections(submissions);
            }
            @Override public AsyncNodeManager.PublicationProgress progress() {
                return nodeManager.publicationProgress();
            }
            @Override public void setProgressListener(Runnable listener) {
                nodeManager.setPublicationProgressListener(listener);
            }
            @Override public void clearProgressListener(Runnable listener) {
                nodeManager.clearPublicationProgressListener(listener);
            }

            @Override
            public void coarsen(long parent, Runnable success, Consumer<Throwable> failure) {
                coarsenRegionalSubtree(parent, success, failure);
            }
        };
    }

    public SectionMesher regionalSectionMesher() {
        return new SectionMesher(this.modelService);
    }

    public long regionalGeometryUsedBytes() {
        AsyncNodeManager nodes = this.nodeManager;
        return nodes == null ? 0 : nodes.geometryUsedBytes();
    }

    public long regionalGeometryCapacityBytes() {
        AsyncNodeManager nodes = this.nodeManager;
        return nodes == null ? 0 : nodes.geometryCapacityBytes();
    }

    public long regionalLargestFreeGeometryUnits() {
        AsyncNodeManager nodes = this.nodeManager;
        return nodes == null ? 0 : nodes.largestFreeGeometryUnits();
    }

    public int regionalGeometrySectionCount() {
        AsyncNodeManager nodes = this.nodeManager;
        return nodes == null ? 0 : nodes.usedGeometrySections();
    }

    public long regionalGeometryPublicationLimitBytes() {
        AsyncNodeManager nodes = this.nodeManager;
        return nodes == null ? 0 : nodes.geometryPublicationLimitBytes();
    }

    public String regionalPublicationLatencySnapshot() {
        AsyncNodeManager nodes = this.nodeManager;
        return "publishLatencyBuckets=<1ms,<4ms,<16ms,<50ms,<200ms,>=200ms"
                + " publishCoverage=" + publicationLatencyLane(0)
                + " publishRefinement=" + publicationLatencyLane(1)
                + (nodes == null ? " rendererBatches=STOPPED"
                        : ' ' + nodes.regionalPublicationBatchSnapshot());
    }

    private String publicationLatencyLane(int lane) {
        return "meshToQueue:" + this.regionalPublicationLatencies[lane][0].snapshot()
                + ";queueToGpu:" + this.regionalPublicationLatencies[lane][1].snapshot()
                + ";gpuToActive:" + this.regionalPublicationLatencies[lane][2].snapshot();
    }

    private SubmissionAttempt publishRegionalSections(List<SectionSubmission> submissions) {
        Objects.requireNonNull(submissions, "submissions");
        if (submissions.isEmpty()) return new SubmissionAttempt(SubmissionStatus.ACCEPTED, List.of());
        SubmissionAttempt accepted = this.nodeManager.tryPublishRegionalSections(() -> {
            ArrayList<AsyncNodeManager.RegionalSectionSubmission> rendererSubmissions =
                    new ArrayList<>(submissions.size());
            ArrayList<SectionPublication> handles = new ArrayList<>(submissions.size());
            for (SectionSubmission submission : submissions) {
                Objects.requireNonNull(submission, "submission");
                if (submission.geometry().position != submission.position()) {
                    throw new IllegalArgumentException("geometry is bound to the wrong section");
                }
                RegionalSectionPublication previous = submission.previous()
                        .map(value -> requireRegionalSectionPublication(submission.position(), value))
                        .orElse(null);
                PreparedRegionalSection prepared = this.prepareRegionalSection(submission.position(),
                        submission.geometry(), previous, submission.coverage(),
                        submission.meshCompletedNanos(), submission.current(), submission.reserved());
                rendererSubmissions.add(prepared.submission());
                handles.add(prepared.publication());
            }
            return new AsyncNodeManager.PreparedBatch<>(rendererSubmissions,
                    new SubmissionAttempt(SubmissionStatus.ACCEPTED, List.copyOf(handles)));
        });
        return accepted == null ? new SubmissionAttempt(SubmissionStatus.BUSY, List.of()) : accepted;
    }

    private PreparedRegionalSection prepareRegionalSection(
            long position, BuiltSection geometry, RegionalSectionPublication previous,
            boolean coverage, long meshCompletedNanos,
            BooleanSupplier current, Runnable reserved) {
        Objects.requireNonNull(geometry, "geometry");
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(reserved, "reserved");
        long revision = this.regionalSectionRevision.getAndIncrement();
        if (revision <= 0) throw new IllegalStateException("regional-section revision exhausted");
        BuiltSection queued = new BuiltSection(position, revision, geometry.childExistence,
                geometry.aabb, geometry.geometryBuffer, geometry.offsets);
        RegionalSectionPublication publication = new RegionalSectionPublication(
                this.nodeManager, position, revision, coverage, meshCompletedNanos);
        long previousRevision = previous == null ? -1 : previous.revision;
        AsyncNodeManager.RegionalSectionSubmission submission =
                new AsyncNodeManager.RegionalSectionSubmission(queued, previousRevision,
                () -> current.getAsBoolean() && publication.acceptsUpload(),
                reserved, publication, () ->
                this.nodeManager.finalizeStagedRoot(revision, () -> {
                    publication.recordActivationFencePassed(System.nanoTime());
                    publication.completeUpload(new UploadOutcome(UploadStatus.ACTIVATED, null, null));
                    if (previous != null) previous.markRetired();
                }, publication::failAndRollback), publication::cancelBeforeStaging,
                block -> publication.recordAllocationBlock(new AllocationBlock(block.geometry(),
                        AllocationStatus.valueOf(block.status().name()), block.requiredUnits(),
                        block.largestFreeUnits(), block.prerequisite(), block.observed())),
                publication::failAndRollback);
        return new PreparedRegionalSection(publication, submission);
    }

    private record PreparedRegionalSection(
            RegionalSectionPublication publication,
            AsyncNodeManager.RegionalSectionSubmission submission) {}

    private void coarsenRegionalSubtree(long parent, Runnable success,
                                        Consumer<Throwable> failure) {
        Objects.requireNonNull(success, "success");
        Objects.requireNonNull(failure, "failure");
        long revision = this.regionalSectionRevision.getAndIncrement();
        if (revision <= 0) throw new IllegalStateException("regional-section revision exhausted");
        this.nodeManager.coarsenSubtree(revision, parent, success, failure);
    }

    private RegionalSectionPublication requireRegionalSectionPublication(
            long position, SectionPublication value) {
        if (!(Objects.requireNonNull(value, "previous")
                instanceof RegionalSectionPublication publication)
                || publication.position != position) {
            throw new IllegalArgumentException(
                    "replacement publication belongs to another renderer");
        }
        if (!publication.activationFencePassed() || publication.retirementFencePassed()) {
            throw new IllegalStateException("publication is not an active renderer surface");
        }
        return publication;
    }

    private final class RegionalSectionPublication extends SectionPublicationState
            implements AsyncNodeManager.RegionalPublicationTiming {
        private final AsyncNodeManager renderer;
        private final long position;
        private final long revision;
        private final boolean coverage;
        private final long meshCompletedNanos;
        private volatile long rendererQueuedNanos;
        private volatile long gpuUploadSubmittedNanos;
        private final AtomicBoolean failureRecoveryQueued = new AtomicBoolean();

        private RegionalSectionPublication(AsyncNodeManager renderer, long position,
                                           long revision, boolean coverage,
                                           long meshCompletedNanos) {
            this.renderer = renderer;
            this.position = position;
            this.revision = revision;
            this.coverage = coverage;
            this.meshCompletedNanos = meshCompletedNanos;
        }

        @Override
        public void recordRendererQueued(long nowNanos) {
            this.rendererQueuedNanos = nowNanos;
            regionalPublicationLatencies[this.coverage ? 0 : 1][0]
                    .record(nowNanos - this.meshCompletedNanos);
        }

        @Override
        public void recordGpuUploadSubmitted(long nowNanos) {
            if (this.rendererQueuedNanos == 0) return;
            this.gpuUploadSubmittedNanos = nowNanos;
            regionalPublicationLatencies[this.coverage ? 0 : 1][1]
                    .record(nowNanos - this.rendererQueuedNanos);
        }

        private void recordActivationFencePassed(long nowNanos) {
            if (this.gpuUploadSubmittedNanos == 0) return;
            regionalPublicationLatencies[this.coverage ? 0 : 1][2]
                    .record(nowNanos - this.gpuUploadSubmittedNanos);
        }

        private void recordAllocationBlock(AllocationBlock block) {
            this.completeUpload(new UploadOutcome(UploadStatus.RETURNED, block, null));
        }

        @Override protected void stateChanged() {
            this.renderer.regionalPublicationStateChanged();
            this.renderer.notifyPublicationProgress();
        }

        @Override protected void requestRetirement() {
            long retirementRevision = regionalSectionRevision.getAndIncrement();
            this.renderer.retirePublication(retirementRevision, this.revision, this.position,
                    this::markRetired, failure -> this.renderer.rollbackStagedRoot(
                            retirementRevision, this::markRetired, rollback -> {
                                failure.addSuppressed(rollback);
                                Logger.error("Regional retirement rollback failed", failure);
                            }));
        }

        private void cancelBeforeStaging() {
            this.completeUpload(new UploadOutcome(UploadStatus.CANCELLED, null, null));
        }

        /**
         * A failed upload/fence must restore the retained old pointer before the failure becomes
         * externally actionable. NodeManager rollback is idempotent for failures that happened
         * before staging, so every asynchronous failure can use the same path.
         */
        private void failAndRollback(Throwable primary) {
            Objects.requireNonNull(primary, "primary");
            if (!this.failureRecoveryQueued.compareAndSet(false, true)) return;
            this.renderer.rollbackStagedRoot(this.revision,
                    () -> this.recordFailure(primary, null),
                    rollback -> this.recordFailure(primary, rollback));
        }

        private void recordFailure(Throwable primary, Throwable rollback) {
            if (rollback != null && rollback != primary) primary.addSuppressed(rollback);
            this.completeUpload(new UploadOutcome(UploadStatus.FAILED, null, primary));
        }

    }

    private static PublicationLatencyCounters[][] createPublicationLatencyCounters() {
        PublicationLatencyCounters[][] counters = new PublicationLatencyCounters[2][3];
        for (int lane = 0; lane < counters.length; lane++) {
            for (int stage = 0; stage < counters[lane].length; stage++) {
                counters[lane][stage] = new PublicationLatencyCounters();
            }
        }
        return counters;
    }

    private static final class PublicationLatencyCounters {
        private final AtomicLong count = new AtomicLong();
        private final AtomicLong maximumNanos = new AtomicLong();
        private final AtomicLongArray buckets =
                new AtomicLongArray(PUBLICATION_LATENCY_BUCKET_NANOS.length + 1);

        void record(long nanos) {
            if (nanos < 0) return;
            this.count.incrementAndGet();
            long maximum = this.maximumNanos.get();
            while (nanos > maximum
                    && !this.maximumNanos.compareAndSet(maximum, nanos)) {
                maximum = this.maximumNanos.get();
            }
            int bucket = 0;
            while (bucket < PUBLICATION_LATENCY_BUCKET_NANOS.length
                    && nanos >= PUBLICATION_LATENCY_BUCKET_NANOS[bucket]) bucket++;
            this.buckets.incrementAndGet(bucket);
        }

        String snapshot() {
            StringBuilder result = new StringBuilder().append(this.count.get()).append('/')
                    .append(this.maximumNanos.get() / 1_000).append("us/");
            for (int bucket = 0; bucket < this.buckets.length(); bucket++) {
                if (bucket != 0) result.append(',');
                result.append(this.buckets.get(bucket));
            }
            return result.toString();
        }
    }

    private NodeCleaner nodeCleaner;
    private HierarchicalOcclusionTraverser traversal;


    private RenderDistanceTracker renderDistanceTracker;
    public ChunkBoundRenderer chunkBoundRenderer;

    private Viewport viewport;

    private AbstractRenderPipeline pipeline;

    // Fog parameters captured before modification by MixinFogRenderer, for Voxy's own fog pass
    private float capturedFogStart;
    private float capturedFogEnd;
    private final float[] capturedFogColor = new float[4];

    public void setCapturedFog(float fogStart, float fogEnd, float[] fogColor) {
        this.capturedFogStart = fogStart;
        this.capturedFogEnd = fogEnd;
        System.arraycopy(fogColor, 0, this.capturedFogColor, 0, 4);
    }

    public float getCapturedFogStart() { return this.capturedFogStart; }
    public float getCapturedFogEnd()   { return this.capturedFogEnd; }
    public float[] getCapturedFogColor() { return this.capturedFogColor; }

    public VoxyRenderSystem(CatalogMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        Logger.info("Creating Voxy render system");

        if (Minecraft.getInstance().options.renderDistance().get()<3) {
            String msg = "Voxy: Having a vanilla render distance of 2 can cause rare culling near the edge of your screen issues, please use 3 or more";
            Logger.warn(msg);
            Minecraft.getInstance().getChatListener().handleSystemMessage(Component.literal(msg), false);
        }

        //Fking HATE EVERYTHING AAAAAAAAAAAAAAAA
        int[] oldBufferBindings = new int[10];
        for (int i = 0; i < oldBufferBindings.length; i++) {
            oldBufferBindings[i] = glGetIntegeri(GL_SHADER_STORAGE_BUFFER_BINDING, i);
        }

        try {
            //wait for opengl to be finished, this should hopefully ensure all memory allocations are free
            glFinish();

            {
                this.modelService = new ModelBakerySubsystem(this.mapper);

                this.geometryData = new BasicSectionGeometryData(1<<20, RenderResourceReuse.getOrCreateGeometryBuffer());

                this.nodeManager = new AsyncNodeManager(1 << 21, this.geometryData);
                this.nodeCleaner = new NodeCleaner(this.nodeManager);
                this.traversal = new HierarchicalOcclusionTraverser(this.nodeManager, this.nodeCleaner);
                this.traversal.setDetailActionListener(ClientLodClient::detailAction);

                Arrays.stream(this.mapper.getBiomeEntries()).forEach(this.modelService::addBiome);
                this.mapper.setBiomeCallback(this.modelService::addBiome);

                this.nodeManager.start();
            }

            AbstractRenderPipeline pipeline = null;
            if (IrisUtil.IRIS_INSTALLED) {
                var irisPipeline = Iris.getPipelineManager().getPipelineNullable();
                if (irisPipeline instanceof IGetIrisVoxyPipelineData voxyPipeline) {
                    var data = voxyPipeline.voxy$getPipelineData();
                    if (data != null) {
                        Logger.info("Creating voxy iris render pipeline");
                        try {
                            pipeline = new IrisVoxyRenderPipeline(data, this.nodeManager, this.nodeCleaner, this.traversal);
                        } catch (Exception e) {
                            Logger.error("Failed to create iris render pipeline", e);
                            IrisUtil.disableIrisShaders();
                        }
                    }
                }
            }
            this.pipeline = pipeline != null ? pipeline : new NormalRenderPipeline(this.nodeManager, this.nodeCleaner, this.traversal);
            this.pipeline.setupExtraModelBakeryData(this.modelService);//Configure the model service

            //Late stage traversal compile for shaders with taa
            this.traversal.lateStageCompile(this.pipeline);


            var sectionRenderer = new MDICSectionRenderer(this.pipeline, this.modelService.getStore(), this.geometryData);
            this.pipeline.setSectionRenderer(sectionRenderer);
            this.viewport = new Viewport(this.geometryData.getMaxSectionCount());

            {
                int minSec = Minecraft.getInstance().level.getMinSection() >> 5;
                int maxSec = (Minecraft.getInstance().level.getMaxSection() - 1) >> 5;

                this.renderDistanceTracker = new RenderDistanceTracker(minSec,
                        maxSec,
                        position -> {
                            this.nodeManager.addTopLevel(position);
                            ClientLodClient.sectionEntered(position);
                        },
                        position -> {
                            ClientLodClient.sectionLeft(position);
                            this.nodeManager.removeTopLevel(position);
                        });

                this.setRenderDistance(VoxyConfig.CONFIG.sectionRenderDistance);
            }

            this.chunkBoundRenderer = new ChunkBoundRenderer(this.pipeline);

            Logger.info("Voxy render system created with " + this.geometryData.getGeometryCapacityBytes() + " geometry capacity, using pipeline '" + this.pipeline.getClass().getSimpleName() + "' with renderer '" + sectionRenderer.getClass().getSimpleName() + "'");
        } catch (RuntimeException | Error failure) {
            this.releaseComponents(failure);
            throw failure;
        } finally {
            for (int i = 0; i < oldBufferBindings.length; i++) {
                glBindBufferBase(GL_SHADER_STORAGE_BUFFER, i, oldBufferBindings[i]);
            }
            for (int i = 0; i < 12; i++) {
                GlStateManager._activeTexture(GlConst.GL_TEXTURE0+i);
                GlStateManager._bindTexture(0);
                glBindSampler(i, 0);
            }
        }
    }


    public Viewport setupViewport(Matrix4fc vanillaProjection, Matrix4fc modelView, double cameraX, double cameraY, double cameraZ) {
        var viewport = this.getViewport();
        if (viewport == null) {
            return null;
        }

        //cameraY += 100;
        var voxyProjection = computeProjectionMat(vanillaProjection);

        int[] dims = new int[4];
        glGetIntegerv(GL_VIEWPORT, dims);

        int width = dims[2];
        int height = dims[3];

        {//Apply render scaling factor
            var factor = this.pipeline.getRenderScalingFactor();
            if (factor != null) {
                width = (int) (width*factor[0]);
                height = (int) (height*factor[1]);
            }
        }
        if (width == 0 || height == 0) {
            Logger.error("Viewport width or height was zero, this is bad bad bad");
            return null;
        }

        viewport
                .setVanillaProjection(vanillaProjection)
                .setProjection(voxyProjection)
                .setModelView(new Matrix4f(modelView))
                .setCamera(cameraX, cameraY, cameraZ)
                .setScreenSize(width, height)
                .update();

        viewport.frameId++;

        return viewport;
    }

    public void renderOpaque(Viewport viewport) {
        if (viewport == null) {
            return;
        }
        if (viewport.width <= 0 || viewport.height <= 0) {
            Logger.error("Viewport width or height was zero, this is bad bad bad, exiting frame");
            return;//Only render on valid viewport
        }

        //TODO: optimize
        int[] oldBufferBindings = new int[10];
        for (int i = 0; i < oldBufferBindings.length; i++) {
            oldBufferBindings[i] = glGetIntegeri(GL_SHADER_STORAGE_BUFFER_BINDING, i);
        }


        int oldFB = GL11.glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        int boundFB = oldFB;

        int[] dims = new int[4];
        glGetIntegerv(GL_VIEWPORT, dims);

        glViewport(0,0, viewport.width, viewport.height);

        if (boundFB == 0) {
            throw new IllegalStateException("Cannot use the default framebuffer as cannot source from it");
        }

        this.pipeline.preSetup(viewport);

        if (!IrisUtil.irisShadowActive()) {
            this.chunkBoundRenderer.render(viewport);
        } else {
            viewport.depthBoundingBuffer.clear(0.0f);
        }

        //The entire rendering pipeline (excluding the chunkbound thing)
        this.pipeline.runPipeline(viewport, boundFB, dims[2], dims[3]);

        //As much dynamic runtime stuff here
        {
            //Tick upload stream (this is ok to do here as upload ticking is just memory management)
            UploadStream.INSTANCE.tick();

            this.renderDistanceTracker.setCenterAndProcess(viewport.cameraX, viewport.cameraZ);
            //Done here as is allows less gl state resetup
            this.modelService.tick();
        }

        glBindFramebuffer(GlConst.GL_FRAMEBUFFER, oldFB);
        glViewport(dims[0], dims[1], dims[2], dims[3]);

        {//Reset state manager stuffs
            glUseProgram(0);
            glEnable(GL_DEPTH_TEST);
            glDisable(GL_STENCIL_TEST);

            GlStateManager._glBindVertexArray(0);//Clear binding

            GlStateManager._activeTexture(GlConst.GL_TEXTURE1);
            for (int i = 0; i < 12; i++) {
                GlStateManager._activeTexture(GlConst.GL_TEXTURE0+i);
                GlStateManager._bindTexture(0);
                glBindSampler(i, 0);
            }

            IrisUtil.clearIrisSamplers();//Thanks iris (sigh)

            //TODO: should/needto actually restore all of these, not just clear them
            //Clear all the bindings
            for (int i = 0; i < oldBufferBindings.length; i++) {
                glBindBufferBase(GL_SHADER_STORAGE_BUFFER, i, oldBufferBindings[i]);
            }

            //((SodiumShader) Iris.getPipelineManager().getPipelineNullable().getSodiumPrograms().getProgram(DefaultTerrainRenderPasses.CUTOUT).getInterface()).setupState(DefaultTerrainRenderPasses.CUTOUT, fogParameters);
        }

    }
    public static float getRenderDistance() {
        return Minecraft.getInstance().options.getEffectiveRenderDistance()*16;
    }

    private static Matrix4f computeProjectionMat(Matrix4fc base) {

        //this jank is to capture the extra crap they inject like viewbobbing
        var rawMCProj = RenderSystem.getProjectionMatrix();
        var extraProjection = rawMCProj.invert(new Matrix4f()).mul(base);

        float near = getRenderDistance()<=32.0f?8f:16f;
        float far = 16*3000;

        return extraProjection.mulLocal(
                new Matrix4f(rawMCProj)
                .m22(far / (near - far))
                .m32(far * near / (near - far))
        );
    }

    public void setRenderDistance(float renderDistance) {
        this.renderDistanceTracker.setRenderDistance((int) Math.ceil(renderDistance+1));//the +1 is to cover the outer ring of chunks when rendering a circle
    }

    public Viewport getViewport() {
        if (IrisUtil.irisShadowActive()) {
            return null;
        }
        return this.viewport;
    }

    public void shutdown() {
        Logger.info("Flushing download stream");
        DownloadStream.INSTANCE.flushWaitClear();
        Logger.info("Shutting down rendering");
        this.releaseComponents(null);

        Logger.info("Flushing download stream");
        DownloadStream.INSTANCE.flushWaitClear();
        Logger.info("Render shutdown completed");
    }

    /** Releases both fully initialized and constructor-partial renderer state exactly once. */
    private void releaseComponents(Throwable constructionFailure) {
        release("biome callback", () -> this.mapper.setBiomeCallback(null), constructionFailure);
        AsyncNodeManager nodes = this.nodeManager;
        this.nodeManager = null;
        if (nodes != null) release("node manager", nodes::stop, constructionFailure);
        ModelBakerySubsystem models = this.modelService;
        this.modelService = null;
        if (models != null) release("model bakery", models::shutdown, constructionFailure);
        HierarchicalOcclusionTraverser traverser = this.traversal;
        this.traversal = null;
        if (traverser != null) release("hierarchy traversal", traverser::free, constructionFailure);
        NodeCleaner cleaner = this.nodeCleaner;
        this.nodeCleaner = null;
        if (cleaner != null) release("node cleaner", cleaner::free, constructionFailure);
        BasicSectionGeometryData geometry = this.geometryData;
        this.geometryData = null;
        if (geometry != null) release("geometry data", () -> {
            geometry.free();
            RenderResourceReuse.giveBackGeometryBuffer(geometry.getGeometryBuffer());
        }, constructionFailure);
        ChunkBoundRenderer bounds = this.chunkBoundRenderer;
        this.chunkBoundRenderer = null;
        if (bounds != null) release("chunk bounds", bounds::free, constructionFailure);
        Viewport oldViewport = this.viewport;
        this.viewport = null;
        if (oldViewport != null) release("viewport", oldViewport::delete, constructionFailure);
        AbstractRenderPipeline oldPipeline = this.pipeline;
        this.pipeline = null;
        if (oldPipeline != null) release("render pipeline", oldPipeline::free, constructionFailure);
    }

    private static void release(String component, Runnable action, Throwable constructionFailure) {
        try {
            action.run();
        } catch (RuntimeException | Error cleanupFailure) {
            if (constructionFailure != null) constructionFailure.addSuppressed(cleanupFailure);
            else Logger.error("Error releasing " + component, cleanupFailure);
        }
    }

}
