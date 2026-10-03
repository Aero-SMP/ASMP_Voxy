package com.aerosmp.voxy.client.render;

import com.aerosmp.voxy.terrain.SectionKey;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryUtil;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.lwjgl.opengl.GL43C.*;

/** One GPU depth pyramid and one asynchronous section-visibility transaction. */
final class DepthVisibility implements AutoCloseable {
    private int reduce, test, texture, buffer, width, height, levels;
    private long fence, completed, rejected;
    private ViewDemand.Camera pendingCamera, resultCamera;
    private List<SectionKey> pending = List.of();
    private final Set<SectionKey> hidden = new HashSet<>();
    boolean hidden(SectionKey key, ViewDemand.Camera camera) { return camera.same(resultCamera) && hidden.contains(key); }
    long completed() { return completed; }
    long rejected() { return rejected; }
    void poll(ViewDemand.Camera camera) {
        if (fence == 0 || glClientWaitSync(fence, 0, 0) == GL_TIMEOUT_EXPIRED) return;
        glDeleteSync(fence); fence = 0; hidden.clear();
        resultCamera = pendingCamera;
        int old = glGetInteger(GL_SHADER_STORAGE_BUFFER_BINDING);
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, buffer);
        ByteBuffer bytes = glMapBufferRange(GL_SHADER_STORAGE_BUFFER, 0, pending.size() * 32L, GL_MAP_READ_BIT);
        if (bytes == null) throw new IllegalStateException("Cannot read terrain visibility");
        for (int i = 0; i < pending.size(); i++) if (bytes.getFloat(i * 32 + 20) == 0) hidden.add(pending.get(i));
        glUnmapBuffer(GL_SHADER_STORAGE_BUFFER); glBindBuffer(GL_SHADER_STORAGE_BUFFER, old);
        completed++; rejected = camera.same(resultCamera) ? hidden.size() : 0;
        pending = List.of();
    }
    void submit(int depthTexture, ViewDemand.Camera camera, Collection<SectionKey> keys) {
        if (fence != 0 || keys.isEmpty() || depthTexture <= 0) return;
        if (reduce == 0) { reduce = program("depth_reduce"); test = program("depth_test"); buffer = glGenBuffers(); }
        int oldProgram = glGetInteger(GL_CURRENT_PROGRAM), oldActive = glGetInteger(GL_ACTIVE_TEXTURE);
        glActiveTexture(GL_TEXTURE0);
        int oldTexture = glGetInteger(GL_TEXTURE_BINDING_2D), oldSampler = glGetIntegeri(GL_SAMPLER_BINDING, 0);
        int oldBuffer = glGetInteger(GL_SHADER_STORAGE_BUFFER_BINDING), oldBase = glGetIntegeri(GL_SHADER_STORAGE_BUFFER_BINDING, 0);
        int imageName = glGetIntegeri(GL_IMAGE_BINDING_NAME, 0), imageLevel = glGetIntegeri(GL_IMAGE_BINDING_LEVEL, 0);
        int imageLayered = glGetIntegeri(GL_IMAGE_BINDING_LAYERED, 0), imageLayer = glGetIntegeri(GL_IMAGE_BINDING_LAYER, 0);
        int imageAccess = glGetIntegeri(GL_IMAGE_BINDING_ACCESS, 0), imageFormat = glGetIntegeri(GL_IMAGE_BINDING_FORMAT, 0);
        try {
            if (width != camera.width || height != camera.height) {
                if (texture != 0) glDeleteTextures(texture);
                width = camera.width; height = camera.height;
                levels = 32 - Integer.numberOfLeadingZeros(Math.max(width, height));
                texture = glGenTextures(); glBindTexture(GL_TEXTURE_2D, texture);
                glTexStorage2D(GL_TEXTURE_2D, levels, GL_R32F, width, height);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST_MIPMAP_NEAREST);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            }
            glBindSampler(0, 0); glUseProgram(reduce);
            for (int level = 0; level < levels; level++) {
                int w = Math.max(1, width >> level), h = Math.max(1, height >> level);
                glBindTexture(GL_TEXTURE_2D, level == 0 ? depthTexture : texture);
                glUniform1i(0, level - 1);
                glBindImageTexture(0, texture, level, false, 0, GL_WRITE_ONLY, GL_R32F);
                glDispatchCompute((w + 7) / 8, (h + 7) / 8, 1);
                glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL_TEXTURE_FETCH_BARRIER_BIT);
            }
            pending = List.copyOf(keys); pendingCamera = camera;
            ByteBuffer input = MemoryUtil.memAlloc(pending.size() * 32);
            try {
                for (SectionKey key : pending) {
                    float[] projected = camera.project(key);
                    for (int i = 0; i < 5; i++) input.putFloat(projected[i]);
                    input.putFloat(1).putFloat(0).putFloat(0);
                }
                input.flip(); glBindBuffer(GL_SHADER_STORAGE_BUFFER, buffer);
                glBufferData(GL_SHADER_STORAGE_BUFFER, input, GL_STREAM_READ);
            } finally { MemoryUtil.memFree(input); }
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, buffer);
            glBindTexture(GL_TEXTURE_2D, texture); glUseProgram(test);
            glUniform1i(0, pending.size()); glDispatchCompute((pending.size() + 63) / 64, 1, 1);
            glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT | GL_SHADER_STORAGE_BARRIER_BIT);
            fence = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0); glFlush();
        } finally {
            glBindImageTexture(0, imageName, imageLevel, imageLayered != 0, imageLayer, imageAccess, imageFormat);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, oldBase); glBindBuffer(GL_SHADER_STORAGE_BUFFER, oldBuffer);
            glBindTexture(GL_TEXTURE_2D, oldTexture); glBindSampler(0, oldSampler);
            glActiveTexture(oldActive); glUseProgram(oldProgram);
        }
    }
    private static int program(String name) {
        String path = "/assets/voxy/shaders/core/" + name + ".comp";
        try (var input = DepthVisibility.class.getResourceAsStream(path)) {
            if (input == null) throw new IllegalStateException("Missing " + path);
            int shader = glCreateShader(GL_COMPUTE_SHADER), program = glCreateProgram();
            try {
                glShaderSource(shader, new String(input.readAllBytes(), StandardCharsets.UTF_8)); glCompileShader(shader);
                if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE) throw new IllegalStateException(glGetShaderInfoLog(shader));
                glAttachShader(program, shader); glLinkProgram(program);
                if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE) throw new IllegalStateException(glGetProgramInfoLog(program));
                return program;
            } catch (RuntimeException failure) { glDeleteProgram(program); throw failure; }
            finally { glDeleteShader(shader); }
        } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
    }
    public void close() {
        if (fence != 0) glDeleteSync(fence);
        if (texture != 0) glDeleteTextures(texture);
        if (buffer != 0) glDeleteBuffers(buffer);
        if (reduce != 0) glDeleteProgram(reduce);
        if (test != 0) glDeleteProgram(test);
    }
}
