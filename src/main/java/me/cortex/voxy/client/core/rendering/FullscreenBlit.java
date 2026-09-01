package me.cortex.voxy.client.core.rendering;

import me.cortex.voxy.client.core.gl.shader.Shader;
import me.cortex.voxy.client.core.gl.shader.ShaderType;
import me.cortex.voxy.client.core.rendering.util.SharedIndexBuffer;

import java.util.function.Consumer;

import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL15C.GL_ELEMENT_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL15C.glBindBuffer;
import static org.lwjgl.opengl.GL30C.glBindVertexArray;
import static org.lwjgl.opengl.GL45C.glCreateVertexArrays;

public class FullscreenBlit {
    private static final int EMPTY_VAO = glCreateVertexArrays();

    private final Shader shader;
    public FullscreenBlit(String fragId) {
        this(fragId, b->{});
    }

    public FullscreenBlit(String vertId, String fragId) {
        this(vertId, fragId, b->{});
    }

    public <T extends Shader> FullscreenBlit(String fragId, Consumer<Shader.Builder<T>> applyer) {
        this("voxy:post/fullscreen.vert", fragId, applyer);
    }

    public <T extends Shader> FullscreenBlit(String vertId, String fragId, Consumer<Shader.Builder<T>> applyer) {
        this.shader = ((Shader.Builder<T>)Shader.make())
                .define("USE_ZERO_ONE_DEPTH")
                .add(ShaderType.VERTEX, vertId)
                .add(ShaderType.FRAGMENT, fragId)
                .apply(applyer)
                .compile();
    }

    public void bind() {
        this.shader.bind();
    }

    public void blit() {
        glBindVertexArray(EMPTY_VAO);
        this.shader.bind();
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, SharedIndexBuffer.INSTANCE_BYTE.id());
        glDrawElements(GL_TRIANGLES, 6, GL_UNSIGNED_BYTE, 0);
        glBindVertexArray(0);
    }

    public void delete() {
        this.shader.free();
    }
}
