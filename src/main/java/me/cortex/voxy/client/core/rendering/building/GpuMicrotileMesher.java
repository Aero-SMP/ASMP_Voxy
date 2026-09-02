package me.cortex.voxy.client.core.rendering.building;

import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.gl.shader.Shader;
import me.cortex.voxy.client.core.gl.shader.ShaderType;
import me.cortex.voxy.client.core.model.ModelFactory;
import me.cortex.voxy.client.core.model.ModelQueries;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import me.cortex.voxy.client.core.rendering.util.DownloadStream;
import me.cortex.voxy.client.lod.ContentPipeline.MeshingPath;
import me.cortex.voxy.client.lod.RootDemandPlan;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.client.core.model.CatalogMapper;
import org.lwjgl.system.MemoryUtil;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;

import static org.lwjgl.opengl.GL42C.GL_BUFFER_UPDATE_BARRIER_BIT;
import static org.lwjgl.opengl.GL42C.glMemoryBarrier;
import static org.lwjgl.opengl.GL43C.GL_SHADER_STORAGE_BARRIER_BIT;
import static org.lwjgl.opengl.GL43C.GL_SHADER_STORAGE_BUFFER;
import static org.lwjgl.opengl.GL43C.glDispatchCompute;
import static org.lwjgl.opengl.GL30C.glBindBufferBase;

/** Bounded render-thread compute mesher for locally proven opaque and template microtiles. */
public final class GpuMicrotileMesher
        implements HybridMeshingDispatcher.Backend {
    private static final int CELL_WORDS = 3;
    private static final int FACE_COUNT = 6;
    private static final int MAX_QUADS_PER_FACE = MicrotileGeometry.SECTION_CELLS;
    private static final int OUTPUT_HEADER_BYTES = 8 * Integer.BYTES;
    private static final long INPUT_BYTES = (long) MicrotileGeometry.INPUT_CELLS
            * CELL_WORDS * Integer.BYTES;
    private static final long OUTPUT_BYTES = OUTPUT_HEADER_BYTES
            + (long) FACE_COUNT * MAX_QUADS_PER_FACE * Long.BYTES;

    private final ModelFactory models;
    private final Thread ownerThread = Thread.currentThread();
    private final Executor ownerExecutor;
    private final Shader shader;
    private final GlBuffer input;
    private final GlBuffer output;
    private CompletableFuture<BuiltSection> pending;
    private boolean closeRequested;
    private boolean closed;

    public GpuMicrotileMesher(ModelFactory models, Executor ownerExecutor) {
        this.models = Objects.requireNonNull(models, "models");
        this.ownerExecutor = Objects.requireNonNull(ownerExecutor, "ownerExecutor");
        Shader createdShader = null;
        GlBuffer createdInput = null;
        GlBuffer createdOutput = null;
        try {
            createdShader = Shader.make()
                    .add(ShaderType.COMPUTE, "voxy:lod/meshing/microtile.comp")
                    .compile();
            createdInput = new GlBuffer(INPUT_BYTES).zero();
            createdOutput = new GlBuffer(OUTPUT_BYTES).zero();
        } catch (RuntimeException | Error failure) {
            if (createdOutput != null) createdOutput.free();
            if (createdInput != null) createdInput.free();
            if (createdShader != null) createdShader.free();
            throw failure;
        }
        this.shader = createdShader;
        this.input = createdInput;
        this.output = createdOutput;
    }

    @Override
    public BuiltSection mesh(HybridMeshingDispatcher.FragmentRequest request) throws Exception {
        Objects.requireNonNull(request, "request");
        if (Thread.currentThread() == this.ownerThread) {
            throw new IllegalStateException(
                    "GPU meshing completion cannot block the render thread");
        }
        CompletableFuture<BuiltSection> result = new CompletableFuture<>();
        this.ownerExecutor.execute(() -> {
            try {
                submitOnOwner(request, result);
            } catch (Throwable failure) {
                if (this.pending == result) this.pending = null;
                result.completeExceptionally(failure);
                finishDeferredClose();
            }
        });
        try {
            return result.get();
        } catch (InterruptedException interrupted) {
            // The render-thread dispatch may already be queued. Mark the waiter gone so the
            // eventual readback releases its caller-owned native geometry instead of completing
            // an abandoned future with an unowned MemoryBuffer.
            result.cancel(false);
            Thread.currentThread().interrupt();
            throw interrupted;
        } catch (ExecutionException wrapped) {
            Throwable failure = wrapped.getCause();
            if (failure instanceof Exception exception) throw exception;
            if (failure instanceof Error error) throw error;
            throw new IllegalStateException("GPU microtile operation failed", failure);
        }
    }

    private void submitOnOwner(HybridMeshingDispatcher.FragmentRequest request,
                               CompletableFuture<BuiltSection> result) {
        this.ensureOwner();
        if (result.isDone()) return;
        if (this.pending != null) {
            throw new IllegalStateException("GPU microtile mesher already has in-flight work");
        }
        this.pending = result;
        if (request.sectionPosition() != RootDemandPlan.sectionKey(request.activation().node())) {
            throw new IllegalArgumentException("GPU meshing request is bound to another node");
        }
        for (var microtile : request.microtiles()) {
            if (microtile.meshingPath() != MeshingPath.GPU_OPAQUE_TEMPLATE) {
                throw new IllegalArgumentException("complex microtile entered GPU meshing");
            }
        }

        MicrotileGeometry.Grid grid = MicrotileGeometry.assemble(request.activation());
        long pointer = UploadStream.INSTANCE.upload(this.input, 0, INPUT_BYTES);
        byte[] paths = grid.paths();
        for (int index = 0; index < MicrotileGeometry.INPUT_CELLS; index++) {
            long cell = grid.inputCell(index);
            int modelId = 0;
            long metadata = 0;
            if (!CatalogMapper.isAir(cell)) {
                int blockId = CatalogMapper.getBlockId(cell);
                if (this.models.hasModelForBlockId(blockId)) {
                    modelId = this.models.getModelId(blockId);
                    metadata = this.models.getModelMetadataFromClientId(modelId);
                }
                if (index < MicrotileGeometry.SECTION_CELLS
                        && paths[index] == MicrotileGeometry.GPU_PATH
                        && (modelId == 0 || !gpuSafe(metadata))) {
                    throw new IllegalStateException(
                            "unproven model reached the GPU microtile backend");
                }
            }
            int material = CatalogMapper.getBiomeId(cell)
                    | CatalogMapper.getLightId(cell) << 9
                    | (int) ModelQueries.lightEmission(metadata) << 17;
            if (ModelQueries.isBiomeColoured(metadata)) material |= 1 << 21;
            if (ModelQueries.cullsSame(metadata)) material |= 1 << 22;
            if (!CatalogMapper.isAir(cell)) material |= 1 << 23;
            if (index < MicrotileGeometry.SECTION_CELLS
                    && paths[index] == MicrotileGeometry.GPU_PATH) material |= 1 << 24;
            if (grid.inputKnown(index)) material |= 1 << 25;
            int faces = faceMask(metadata, FaceProperty.EXISTS)
                    | faceMask(metadata, FaceProperty.CAN_BE_OCCLUDED) << 8
                    | faceMask(metadata, FaceProperty.OCCLUDES) << 16
                    | faceMask(metadata, FaceProperty.SELF_LIGHT) << 24;
            MemoryUtil.memPutInt(pointer, modelId); pointer += 4;
            MemoryUtil.memPutInt(pointer, material); pointer += 4;
            MemoryUtil.memPutInt(pointer, faces); pointer += 4;
        }
        UploadStream.INSTANCE.commit();

        this.output.zeroRange(0, OUTPUT_HEADER_BYTES);
        this.shader.bind();
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, this.input.id);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, this.output.id);
        glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT | GL_SHADER_STORAGE_BARRIER_BIT);
        glDispatchCompute((MicrotileGeometry.SECTION_CELLS + 127) / 128, 1, 1);
        glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT | GL_SHADER_STORAGE_BARRIER_BIT);
        // The copy and its fence are queued on the render thread, but parsing happens only after
        // the fence signals on a later frame. No glGet* call stalls Minecraft's render loop.
        DownloadStream.INSTANCE.download(this.output, 0, OUTPUT_BYTES, (download, size) -> {
            try {
                if (this.closed) throw new IllegalStateException("GPU mesher closed in flight");
                BuiltSection geometry = readResult(request, grid, download, size);
                if (!result.complete(geometry)) geometry.free();
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            } finally {
                if (this.pending == result) this.pending = null;
                finishDeferredClose();
            }
        });
    }

    private BuiltSection readResult(HybridMeshingDispatcher.FragmentRequest request,
                                    MicrotileGeometry.Grid grid,
                                    long pointer, long size) {
        this.ensureOwner();
        if (size != OUTPUT_BYTES) {
            throw new IllegalStateException("GPU microtile download has the wrong size");
        }
        if (MemoryUtil.memGetInt(pointer + 24) != 0) {
            throw new IllegalStateException("GPU microtile output exceeded its proven bound");
        }
        int[] counts = new int[FACE_COUNT];
        int total = 0;
        for (int face = 0; face < FACE_COUNT; face++) {
            long count = Integer.toUnsignedLong(
                    MemoryUtil.memGetInt(pointer + face * 4L));
            if (count > MAX_QUADS_PER_FACE) {
                throw new IllegalStateException("GPU microtile face count is malformed");
            }
            counts[face] = (int) count;
            total = Math.addExact(total, counts[face]);
        }
        byte children = (byte) request.activation().childMask();
        if (total == 0) {
            return BuiltSection.emptyWithChildren(request.sectionPosition(),
                    request.sourceRevision(), children);
        }

        MemoryBuffer geometry = new MemoryBuffer((long) total * Long.BYTES);
        try {
            int[] offsets = new int[8];
            int cursor = 0;
            offsets[0] = 0;
            offsets[1] = 0;
            for (int face = 0; face < FACE_COUNT; face++) {
                offsets[face + 2] = cursor;
                int count = counts[face];
                if (count != 0) {
                    long sourceOffset = OUTPUT_HEADER_BYTES
                            + (long) face * MAX_QUADS_PER_FACE * Long.BYTES;
                    MemoryUtil.memCopy(pointer + sourceOffset,
                            geometry.address + (long) cursor * Long.BYTES,
                            (long) count * Long.BYTES);
                }
                cursor += count;
            }
            int aabb = MicrotileGeometry.emittedAabb(grid,
                    MicrotileGeometry.GPU_PATH);
            if (aabb == -1) throw new IllegalStateException("GPU emitted geometry without bounds");
            BuiltSection result = new BuiltSection(request.sectionPosition(),
                    request.sourceRevision(), children, aabb, geometry, offsets);
            geometry = null;
            return result;
        } finally {
            if (geometry != null) geometry.free();
        }
    }

    @Override
    public void close() {
        if (Thread.currentThread() == this.ownerThread) {
            closeOnOwner();
            return;
        }
        CompletableFuture<Void> completion = new CompletableFuture<>();
        this.ownerExecutor.execute(() -> {
            try {
                closeOnOwner();
                completion.complete(null);
            } catch (Throwable failure) {
                completion.completeExceptionally(failure);
            }
        });
        completion.join();
    }

    private void closeOnOwner() {
        if (Thread.currentThread() != this.ownerThread) {
            throw new IllegalStateException("GPU microtile meshing requires its render thread");
        }
        if (this.closed || this.closeRequested) return;
        if (this.pending != null) {
            this.closeRequested = true;
            return;
        }
        freeOnOwner();
    }

    private void finishDeferredClose() {
        this.ensureOwnerThreadOnly();
        if (this.closeRequested && this.pending == null && !this.closed) freeOnOwner();
    }

    private void freeOnOwner() {
        this.closed = true;
        this.closeRequested = false;
        this.shader.free();
        this.input.free();
        this.output.free();
    }

    private void ensureOwner() {
        this.ensureOwnerThreadOnly();
        if (this.closed) throw new IllegalStateException("GPU microtile mesher is closed");
    }

    private void ensureOwnerThreadOnly() {
        if (Thread.currentThread() != this.ownerThread) {
            throw new IllegalStateException("GPU microtile meshing requires its render thread");
        }
    }

    private static boolean gpuSafe(long metadata) {
        return !ModelQueries.containsFluid(metadata) && !ModelQueries.isFluid(metadata)
                && ModelQueries._isTranslucent(metadata) == 0
                && ModelQueries._isDoubleSided(metadata) == 0;
    }

    private static int faceMask(long metadata, FaceProperty property) {
        int result = 0;
        for (int face = 0; face < FACE_COUNT; face++) {
            boolean present = switch (property) {
                case EXISTS -> ModelQueries.faceExists(metadata, face);
                case CAN_BE_OCCLUDED -> ModelQueries.faceCanBeOccluded(metadata, face);
                case OCCLUDES -> ModelQueries.faceOccludes(metadata, face);
                case SELF_LIGHT -> ModelQueries.faceUsesSelfLighting(metadata, face);
            };
            if (present) result |= 1 << face;
        }
        return result;
    }

    private enum FaceProperty {
        EXISTS,
        CAN_BE_OCCLUDED,
        OCCLUDES,
        SELF_LIGHT
    }
}
