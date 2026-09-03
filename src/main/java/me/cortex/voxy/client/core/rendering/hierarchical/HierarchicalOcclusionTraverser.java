package me.cortex.voxy.client.core.rendering.hierarchical;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.AbstractRenderPipeline;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.gl.shader.AutoBindingShader;
import me.cortex.voxy.client.core.gl.shader.Shader;
import me.cortex.voxy.client.core.gl.shader.ShaderLoader;
import me.cortex.voxy.client.core.gl.shader.ShaderType;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.util.DownloadStream;
import me.cortex.voxy.client.core.rendering.util.HiZBuffer;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.client.core.rendering.SectionKey;
import org.lwjgl.system.MemoryUtil;

import java.util.Objects;
import java.util.function.LongConsumer;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.GL_UNPACK_IMAGE_HEIGHT;
import static org.lwjgl.opengl.GL12.GL_UNPACK_SKIP_IMAGES;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL30C.GL_RED_INTEGER;
import static org.lwjgl.opengl.GL42.glMemoryBarrier;
import static org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BARRIER_BIT;
import static org.lwjgl.opengl.GL45.*;

// TODO: swap to persistent gpu threads instead of dispatching MAX_ITERATIONS of compute layers
public class HierarchicalOcclusionTraverser {
    public static final int MAX_REQUEST_QUEUE_SIZE = 50;
    public static final int MAX_COARSEN_QUEUE_SIZE = 64;
    public static final int MAX_QUEUE_SIZE = 200_000;


    private static final int MAX_ITERATIONS = SectionKey.MAX_LOD_LAYER+1;
    private static final int LOCAL_WORK_SIZE_BITS = 5;
    private static final double COARSEN_GRACE_NANOS = 2_000_000_000.0;

    private final AsyncNodeManager nodeManager;
    private final NodeCleaner nodeCleaner;
    private LongConsumer refinementListener = ignored -> {};
    private LongConsumer coarseningListener = ignored -> {};

    private final GlBuffer requestBuffer;

    private final GlBuffer nodeBuffer;
    private final GlBuffer uniformBuffer = new GlBuffer(1024).zero();


    private int topNodeCount;
    private final Int2IntOpenHashMap topNode2idxMapping = new Int2IntOpenHashMap();//Used to store mapping from TLN to array index
    private final int[] idx2topNodeMapping = new int[MAX_QUEUE_SIZE];//Used to map idx to TLN id
    private final GlBuffer topNodeIds = new GlBuffer(MAX_QUEUE_SIZE*4).zero();
    private final GlBuffer queueMetaBuffer = new GlBuffer(4*4*MAX_ITERATIONS).zero();
    private final GlBuffer scratchQueueA = new GlBuffer(MAX_QUEUE_SIZE*4).zero();
    private final GlBuffer scratchQueueB = new GlBuffer(MAX_QUEUE_SIZE*4).zero();

    private static int BINDING_COUNTER = 1;
    private static final int SCENE_UNIFORM_BINDING = BINDING_COUNTER++;
    private static final int REQUEST_QUEUE_BINDING = BINDING_COUNTER++;
    private static final int RENDER_QUEUE_BINDING = BINDING_COUNTER++;
    private static final int NODE_DATA_BINDING = BINDING_COUNTER++;
    private static final int NODE_QUEUE_INDEX_BINDING = BINDING_COUNTER++;
    private static final int NODE_QUEUE_META_BINDING = BINDING_COUNTER++;
    private static final int NODE_QUEUE_SOURCE_BINDING = BINDING_COUNTER++;
    private static final int NODE_QUEUE_SINK_BINDING = BINDING_COUNTER++;
    private static final int RENDER_TRACKER_BINDING = BINDING_COUNTER++;

    private final int hizSampler = glGenSamplers();

    private AutoBindingShader traversal;

    private AbstractRenderPipeline pipeline;//Used to bind shader taa uniforms
    private long previousFrameNanos;
    private double averageFrameNanos = 16_666_667.0;
    private int coarsenGraceFrames = 120;

    public HierarchicalOcclusionTraverser(AsyncNodeManager nodeManager, NodeCleaner nodeCleaner) {
        this.nodeCleaner = nodeCleaner;
        this.nodeManager = nodeManager;
        this.requestBuffer = new GlBuffer(
                (MAX_REQUEST_QUEUE_SIZE + MAX_COARSEN_QUEUE_SIZE) * 8L + 8).zero();
        this.nodeBuffer = new GlBuffer(nodeManager.maxNodeCount*16L).fill(-1);


        glSamplerParameteri(this.hizSampler, GL_TEXTURE_MIN_FILTER, GL_NEAREST_MIPMAP_NEAREST);
        glSamplerParameteri(this.hizSampler, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glSamplerParameteri(this.hizSampler, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glSamplerParameteri(this.hizSampler, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);

        this.topNode2idxMapping.defaultReturnValue(-1);
        this.nodeManager.setTLNAddRemoveCallbacks(this::addTLN, this::remTLN);
    }

    public void lateStageCompile(AbstractRenderPipeline pipeline) {
        String taa = pipeline.taaFunction("getTAA");
        var scr = ShaderLoader.parse("voxy:lod/hierarchical/traversal.comp");
        if (taa != null) {
            scr += "\n\n\n" + taa;
            this.pipeline = pipeline;
        }
        this.traversal = Shader.makeAuto()
            .define("USE_ZERO_ONE_DEPTH")
            .define("MAX_ITERATIONS", MAX_ITERATIONS)
            .define("LOCAL_SIZE_BITS", LOCAL_WORK_SIZE_BITS)
            .define("MAX_REQUEST_QUEUE_SIZE", MAX_REQUEST_QUEUE_SIZE)
            .define("MAX_COARSEN_QUEUE_SIZE", MAX_COARSEN_QUEUE_SIZE)

            .define("HIZ_BINDING", 0)

            .define("SCENE_UNIFORM_BINDING", SCENE_UNIFORM_BINDING)
            .define("REQUEST_QUEUE_BINDING", REQUEST_QUEUE_BINDING)
            .define("RENDER_QUEUE_BINDING", RENDER_QUEUE_BINDING)
            .define("NODE_DATA_BINDING", NODE_DATA_BINDING)

            .define("NODE_QUEUE_INDEX_BINDING", NODE_QUEUE_INDEX_BINDING)
            .define("NODE_QUEUE_META_BINDING", NODE_QUEUE_META_BINDING)
            .define("NODE_QUEUE_SOURCE_BINDING", NODE_QUEUE_SOURCE_BINDING)
            .define("NODE_QUEUE_SINK_BINDING", NODE_QUEUE_SINK_BINDING)

            .define("RENDER_TRACKER_BINDING", RENDER_TRACKER_BINDING)

            .defineIf("TAA", taa != null)

            .addSource(ShaderType.COMPUTE, scr)
            .compile();


        this.traversal
                .ubo("SCENE_UNIFORM_BINDING", this.uniformBuffer)
                .ssbo("REQUEST_QUEUE_BINDING", this.requestBuffer)
                .ssbo("NODE_DATA_BINDING", this.nodeBuffer)
                .ssbo("NODE_QUEUE_META_BINDING", this.queueMetaBuffer)
                .ssbo("RENDER_TRACKER_BINDING", this.nodeCleaner.visibilityBuffer);
    }

    private void addTLN(int id) {
        int aid = this.topNodeCount++;//Increment buffer
        if (this.topNodeCount > this.topNodeIds.size()/4) {
            throw new IllegalStateException("Top level node count greater than capacity");
        }

        //Use clear buffer, yes know is a bad idea, TODO: replace
        //Add the new top level node to the queue
        MemoryUtil.memPutInt(SCRATCH, id);
        nglClearNamedBufferSubData(this.topNodeIds.id, GL_R32UI, aid * 4L, 4, GL_RED_INTEGER, GL_UNSIGNED_INT, SCRATCH);

        if (this.topNode2idxMapping.put(id, aid) != -1) {
            throw new IllegalStateException();
        }
        this.idx2topNodeMapping[aid] = id;
    }

    private void remTLN(int id) {
        //Remove id
        int idx = this.topNode2idxMapping.remove(id);
        //Decrement count
        this.topNodeCount--;
        if (idx == -1) {
            throw new IllegalStateException();
        }

        //Count has already been decremented so is an exact match
        //If we are at the end of the array we dont need to do anything
        if (idx == this.topNodeCount) {
            return;
        }

        //Move the entry at the end to the current index
        int endTLNId = this.idx2topNodeMapping[this.topNodeCount];
        this.idx2topNodeMapping[idx] = endTLNId;//Set the old to the new
        if (this.topNode2idxMapping.put(endTLNId, idx) == -1)
            throw new IllegalStateException();

        //Move it server side, from end to new idx
        MemoryUtil.memPutInt(SCRATCH, endTLNId);
        nglClearNamedBufferSubData(this.topNodeIds.id, GL_R32UI, idx*4L, 4, GL_RED_INTEGER, GL_UNSIGNED_INT, SCRATCH);
    }

    private static void setFrustum(Viewport viewport, long ptr) {
        for (int i = 0; i < 6; i++) {
            var plane = viewport.frustumPlanes[i];
            plane.getToAddress(ptr); ptr += 4*4;
        }
    }

    private void uploadUniform(Viewport viewport, HiZBuffer hiZBuffer, boolean finalPass) {
        long ptr = UploadStream.INSTANCE.upload(this.uniformBuffer, 0, 1024);

        viewport.MVP.getToAddress(ptr); ptr += 4*4*4;

        viewport.section.getToAddress(ptr); ptr += 4*3;

        MemoryUtil.memPutInt(ptr, hiZBuffer.getPackedLevels()); ptr += 4;

        viewport.innerTranslation.getToAddress(ptr); ptr += 4*3;


        final float screenspaceAreaDecreasingSize = VoxyConfig.CONFIG.subDivisionSize*VoxyConfig.CONFIG.subDivisionSize;
        //Screen space size for descending
        MemoryUtil.memPutFloat(ptr, (float) (screenspaceAreaDecreasingSize) /(viewport.width*viewport.height)); ptr += 4;

        setFrustum(viewport, ptr); ptr += 4*4*6;

        MemoryUtil.memPutInt(ptr, (int) (viewport.getRenderList().size()/4-1)); ptr += 4;

        //VisibilityId
        MemoryUtil.memPutInt(ptr, this.nodeCleaner.visibilityId); ptr += 4;

        // These requests reserve hierarchy owners and directly request regional child sections.
        MemoryUtil.memPutInt(ptr, MAX_REQUEST_QUEUE_SIZE); ptr += 4;

        //Put the render distance here so that it can generate a correct circle, TODO: make it not top level section sized
        MemoryUtil.memPutFloat(ptr, (float) Math.pow(VoxyConfig.CONFIG.sectionRenderDistance*16*32,2));ptr += 4;

        MemoryUtil.memPutInt(ptr, finalPass ? 1 : 0);
        ptr += 4;
        MemoryUtil.memPutInt(ptr, this.coarsenGraceFrames);


    }

    private void bindings(Viewport viewport, HiZBuffer hiZBuffer) {
        glBindBuffer(GL_DISPATCH_INDIRECT_BUFFER, this.queueMetaBuffer.id);

        //Bind the hiz buffer
        glBindTextureUnit(0, hiZBuffer.getHizTextureId());
        glBindSampler(0, this.hizSampler);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, RENDER_QUEUE_BINDING, viewport.getRenderList().id);
    }

    public void doTraversal(Viewport viewport) {
        long now = System.nanoTime();
        if (this.previousFrameNanos != 0) {
            long elapsed = now - this.previousFrameNanos;
            if (elapsed > 0 && elapsed < 250_000_000L) {
                this.averageFrameNanos += (elapsed - this.averageFrameNanos) * 0.05;
                this.coarsenGraceFrames = Math.max(30, Math.min(600,
                        (int) Math.ceil(COARSEN_GRACE_NANOS / this.averageFrameNanos)));
            }
        }
        this.previousFrameNanos = now;
        this.doTraversal(viewport, viewport.hiZBuffer, false);
    }

    public void doSecondPass(Viewport viewport) {
        this.doTraversal(viewport, viewport.refinedHiZBuffer, true);
    }

    private void doTraversal(Viewport viewport, HiZBuffer hiZBuffer, boolean finalPass) {
        this.uploadUniform(viewport, hiZBuffer, finalPass);

        this.traversal.bind();
        this.bindings(viewport, hiZBuffer);

        //Bind shader uniforms for taa if we have a pipeline
        if (this.pipeline != null) this.pipeline.bindUniforms();

        //Clear the render output counter
        nglClearNamedBufferSubData(viewport.getRenderList().id, GL_R32UI, 0, 4, GL_RED_INTEGER, GL_UNSIGNED_INT, 0);

        //Traverse
        this.traverseInternal();

        this.downloadResetRequestQueue();

        //Bind the hiz buffer
        glBindSampler(0, 0);
        glBindTextureUnit(0, 0);
    }

    public void setRefinementListener(LongConsumer listener) {
        this.refinementListener = Objects.requireNonNull(listener, "listener");
    }

    public void setCoarseningListener(LongConsumer listener) {
        this.coarseningListener = Objects.requireNonNull(listener, "listener");
    }

    private void traverseInternal() {
        {
            //Fix mesa bug
            glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
            glPixelStorei(GL_UNPACK_IMAGE_HEIGHT, 0);
            glPixelStorei(GL_UNPACK_SKIP_PIXELS, 0);
            glPixelStorei(GL_UNPACK_SKIP_ROWS, 0);
            glPixelStorei(GL_UNPACK_SKIP_IMAGES, 0);
        }

        int firstDispatchSize = (this.topNodeCount+(1<<LOCAL_WORK_SIZE_BITS)-1)>>LOCAL_WORK_SIZE_BITS;
        { // Explicitly clear indirect-dispatch metadata for Intel drivers.
            long ptr = UploadStream.INSTANCE.upload(this.queueMetaBuffer, 0, 16*MAX_ITERATIONS);
            MemoryUtil.memPutInt(ptr +  0, firstDispatchSize);
            MemoryUtil.memPutInt(ptr +  4, 1);
            MemoryUtil.memPutInt(ptr +  8, 1);
            MemoryUtil.memPutInt(ptr + 12, this.topNodeCount);
            for (int i = 1; i < MAX_ITERATIONS; i++) {
                MemoryUtil.memPutInt(ptr + (i*16)+ 0, 0);
                MemoryUtil.memPutInt(ptr + (i*16)+ 4, 1);
                MemoryUtil.memPutInt(ptr + (i*16)+ 8, 1);
                MemoryUtil.memPutInt(ptr + (i*16)+12, 0);
            }
            UploadStream.INSTANCE.commit();
        }

        //Execute first iteration
        glUniform1ui(NODE_QUEUE_INDEX_BINDING, 0);

        //Use the top node id buffer
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, NODE_QUEUE_SOURCE_BINDING, this.topNodeIds.id);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, NODE_QUEUE_SINK_BINDING, this.scratchQueueB.id);

        //Dont need to use indirect to dispatch the first iteration
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT|GL_COMMAND_BARRIER_BIT|GL_BUFFER_UPDATE_BARRIER_BIT);
        if (firstDispatchSize!=0) {
            //for some reason amd driver loves spitting out errors when its 0 (even tho it should just ignore it afak) so we do it ourselves
            glDispatchCompute(firstDispatchSize, 1,1);
        }
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT|GL_COMMAND_BARRIER_BIT);

        //Dispatch max iterations
        for (int iter = 1; iter < MAX_ITERATIONS; iter++) {
            glUniform1ui(NODE_QUEUE_INDEX_BINDING, iter);

            //Flipflop buffers
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, NODE_QUEUE_SOURCE_BINDING, ((iter & 1) == 0 ? this.scratchQueueA : this.scratchQueueB).id);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, NODE_QUEUE_SINK_BINDING, ((iter & 1) == 0 ? this.scratchQueueB : this.scratchQueueA).id);

            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT | GL_COMMAND_BARRIER_BIT);

            //Dispatch and barrier
            glDispatchComputeIndirect(iter * 4 * 4);
        }

        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT | GL_COMMAND_BARRIER_BIT);
    }


    private void downloadResetRequestQueue() {
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
        DownloadStream.INSTANCE.download(this.requestBuffer, this::forwardDownloadResult);
        nglClearNamedBufferSubData(this.requestBuffer.id, GL_R32UI, 0, 8,
                GL_RED_INTEGER, GL_UNSIGNED_INT, 0);
    }

    private void forwardDownloadResult(long ptr, long size) {
        int refinementCount = (int) Math.min(
                Integer.toUnsignedLong(MemoryUtil.memGetInt(ptr)), MAX_REQUEST_QUEUE_SIZE);
        int coarseningCount = (int) Math.min(
                Integer.toUnsignedLong(MemoryUtil.memGetInt(ptr + 4)), MAX_COARSEN_QUEUE_SIZE);
        long refinementPtr = ptr + 8;
        if (refinementCount != 0) {
            for (int index = 0; index < refinementCount; index++) {
                long address = refinementPtr + (long) index * 8;
                long position = (long) MemoryUtil.memGetInt(address) << 32
                        | Integer.toUnsignedLong(MemoryUtil.memGetInt(address + 4));
                this.refinementListener.accept(position);
            }
            MemoryUtil.memPutInt(ptr, refinementCount);
            this.nodeManager.submitRequestBatch(new MemoryBuffer(refinementCount * 8L + 8)
                    .cpyFrom(ptr));
        }
        long coarseningPtr = refinementPtr + MAX_REQUEST_QUEUE_SIZE * 8L;
        for (int index = 0; index < coarseningCount; index++) {
            long address = coarseningPtr + (long) index * 8;
            long position = (long) MemoryUtil.memGetInt(address) << 32
                    | Integer.toUnsignedLong(MemoryUtil.memGetInt(address + 4));
            this.coarseningListener.accept(position);
        }
    }

    public GlBuffer getNodeBuffer() {
        return this.nodeBuffer;
    }

    public void free() {
        if (this.traversal != null) this.traversal.free();
        this.requestBuffer.free();
        this.nodeBuffer.free();
        this.uniformBuffer.free();
        this.queueMetaBuffer.free();
        this.topNodeIds.free();
        this.scratchQueueA.free();
        this.scratchQueueB.free();
        glDeleteSamplers(this.hizSampler);
    }

    private static final long SCRATCH = MemoryUtil.nmemAlloc(32);//32 bytes of scratch memory

}
