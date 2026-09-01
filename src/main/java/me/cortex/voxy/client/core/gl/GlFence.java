package me.cortex.voxy.client.core.gl;

import me.cortex.voxy.common.util.TrackedObject;

import static org.lwjgl.opengl.GL32.*;

public class GlFence extends TrackedObject {
    private final long fence;
    private boolean signaled;

    public GlFence() {
        long value = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        if (value == 0) {
            throw new IllegalStateException("OpenGL failed to create a GPU fence");
        }
        this.fence = value;
    }

    public boolean signaled() {
        if (!this.signaled) {
            int val = glGetSynci(this.fence, GL_SYNC_STATUS, null);
            if (val == GL_SIGNALED) {
                this.signaled = true;
            } else if (val != GL_UNSIGNALED) {
                throw new IllegalStateException("Unknown data from glGetSync: "+val);
            }
        }
        return this.signaled;
    }

    @Override
    public void free() {
        super.free0();
        glDeleteSync(this.fence);
    }
}
