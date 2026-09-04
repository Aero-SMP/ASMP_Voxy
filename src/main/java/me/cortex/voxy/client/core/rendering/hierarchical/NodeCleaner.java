package me.cortex.voxy.client.core.rendering.hierarchical;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.gl.shader.Shader;
import me.cortex.voxy.client.core.gl.shader.ShaderType;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import org.lwjgl.system.MemoryUtil;

import static org.lwjgl.opengl.GL30C.glBindBufferRange;
import static org.lwjgl.opengl.GL42C.glMemoryBarrier;
import static org.lwjgl.opengl.GL43C.*;

/** Maintains per-node visibility generations for the active renderer hierarchy. */
public class NodeCleaner {
    private final me.cortex.voxy.client.core.gl.shader.AutoBindingShader batchClear = Shader.makeAuto()
            .define("VISIBILITY_BUFFER_BINDING", 0)
            .define("LIST_BUFFER_BINDING", 1)
            .add(ShaderType.COMPUTE, "voxy:lod/hierarchical/cleaner/batch_visibility_set.comp")
            .compile();


    final GlBuffer visibilityBuffer;
    int visibilityId = 0;


    public NodeCleaner(AsyncNodeManager nodeManager) {
        this.visibilityBuffer = new GlBuffer(nodeManager.maxNodeCount*4L).zero();
        this.visibilityBuffer.fill(-1);

        this.batchClear
                .ssbo("VISIBILITY_BUFFER_BINDING", this.visibilityBuffer);
    }


    public void tick() {
        // The high bit is the traversal's persistent dormant-state bit.
        this.visibilityId = (this.visibilityId + 1) & Integer.MAX_VALUE;
    }

    public void updateIds(IntOpenHashSet collection) {
        if (!collection.isEmpty()) {
            int count = collection.size();
            long addr = UploadStream.INSTANCE.rawUploadAddress(count*4);//Internally does upsizing alignement

            long ptr = UploadStream.INSTANCE.getBaseAddress() + addr;
            var iter = collection.iterator();
            while (iter.hasNext()) {
                MemoryUtil.memPutInt(ptr, iter.nextInt()); ptr+=4;
            }
            UploadStream.INSTANCE.commit();

            this.batchClear.bind();
            glBindBufferRange(GL_SHADER_STORAGE_BUFFER, 1, UploadStream.INSTANCE.getRawBufferId(), addr, UploadStream.alignUpAlloc(count*4));
            glUniform1ui(0, count);
            glUniform1ui(1, this.visibilityId);
            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
            glDispatchCompute((count+127)/128, 1, 1);
            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
        }
    }

    public void free() {
        this.visibilityBuffer.free();
        this.batchClear.free();
    }
}
