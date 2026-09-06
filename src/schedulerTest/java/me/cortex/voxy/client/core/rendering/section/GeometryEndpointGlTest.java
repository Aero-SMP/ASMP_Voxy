package me.cortex.voxy.client.core.rendering.section;

import me.cortex.voxy.client.core.gl.GlBuffer;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.ARBSparseBuffer.*;
import static org.lwjgl.opengl.GL45C.*;

/** Optional integration probe; requires a working EGL/OpenGL context, not the headless runner. */
public final class GeometryEndpointGlTest {
    public static void main(String[] args) {
        GLFWErrorCallback errors = GLFWErrorCallback.createPrint(System.err).set();
        long window = 0;
        try {
            glfwInitHint(GLFW_PLATFORM, GLFW_PLATFORM_NULL);
            if (!glfwInit()) {
                System.out.println("SKIP: no GLFW null-platform context");
                return;
            }
            glfwWindowHint(GLFW_CONTEXT_CREATION_API, GLFW_EGL_CONTEXT_API);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 5);
            glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
            window = glfwCreateWindow(16, 16, "Geometry endpoint probe", 0, 0);
            if (window == 0) {
                System.out.println("SKIP: no EGL OpenGL 4.5 context");
                return;
            }
            glfwMakeContextCurrent(window);
            var capabilities = GL.createCapabilities();
            System.out.println("GL renderer: " + glGetString(GL_RENDERER));
            exercise(65528, false);
            if (capabilities.GL_ARB_sparse_buffer) {
                int page = glGetInteger(GL_SPARSE_BUFFER_PAGE_SIZE_ARB);
                if (page <= 0) throw new AssertionError("Invalid driver page size");
                exercise((64L << 20) + 3L * page + 8, true);
            } else {
                System.out.println("SKIP: ARB_sparse_buffer unsupported");
            }
        } finally {
            GL.setCapabilities(null);
            if (window != 0) glfwDestroyWindow(window);
            glfwTerminate();
            glfwSetErrorCallback(null);
            errors.free();
        }
    }

    private static void exercise(long capacity, boolean sparse) {
        GlBuffer buffer = new GlBuffer(capacity, sparse ? GL_SPARSE_STORAGE_BIT_ARB : 0, false);
        BasicSectionGeometryData store = new BasicSectionGeometryData(1, buffer);
        int source = glCreateBuffers();
        try {
            store.ensureAccessible(1);
            store.ensureAccessible(capacity / 8);
            store.ensureAccessible(capacity / 8);
            for (long invalid : new long[]{-1, capacity / 8 + 1, Long.MAX_VALUE}) {
                try {
                    store.ensureAccessible(invalid);
                    throw new AssertionError("Accepted invalid endpoint " + invalid);
                } catch (IllegalArgumentException expected) {
                    // Must fail before any GL operation.
                }
            }
            int[] pattern = {0x12345678, 0x9abcdef0};
            glNamedBufferStorage(source, pattern, 0);
            glCopyNamedBufferSubData(source, buffer.id, 0, capacity - 8, 8);
            glFinish(); // Test-only readback synchronization.
            int[] actual = new int[2];
            glGetNamedBufferSubData(buffer.id, capacity - 8, actual);
            if (!java.util.Arrays.equals(actual, pattern)) throw new AssertionError("Tail readback mismatch");
            checkErrors();
            System.out.println("PASS: tail upload/readback, sparse=" + sparse + ", endpoint=" + capacity);
        } finally {
            glDeleteBuffers(source);
            store.free();
            buffer.free();
        }
        checkErrors();
    }

    private static void checkErrors() {
        int error = glGetError();
        if (error != GL_NO_ERROR) throw new AssertionError("OpenGL error " + error);
    }
}
