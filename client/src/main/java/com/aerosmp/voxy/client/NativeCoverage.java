package com.aerosmp.voxy.client;

import static org.lwjgl.opengl.GL43C.*;

/** A native depth snapshot that can also serve as a private occlusion target. */
final class NativeCoverage implements AutoCloseable {
    private int texture, framebuffer, width, height, format;
    private int sourceFramebuffer, sourceDepth, sourceDepthType;
    private final org.joml.Vector4f viewport = new org.joml.Vector4f();
    private boolean valid;
    private long failures;
    private String error = "";

    int texture() {
        return texture;
    }

    boolean valid() {
        return valid;
    }

    long failures() {
        return failures;
    }

    String error() {
        return error;
    }

    org.joml.Vector4f viewport() {
        return viewport;
    }

    int sourceFramebuffer() {
        return sourceFramebuffer;
    }

    int sourceDepth() {
        return sourceDepth;
    }

    int sourceDepthType() {
        return sourceDepthType;
    }

    void invalidate() {
        valid = false;
    }

    Target bind() {
        if (!valid) throw new IllegalStateException("Native depth snapshot is unavailable");
        return new Target();
    }

    /** Only the private target is changed; display framebuffer state is restored on close. */
    final class Target implements AutoCloseable {
        private final int draw = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        private final int read = glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
        private final int[] oldViewport = new int[4], oldScissor = new int[4];
        private final boolean scissor = glIsEnabled(GL_SCISSOR_TEST);
        private boolean restored;

        private Target() {
            glGetIntegerv(GL_VIEWPORT, oldViewport);
            glGetIntegerv(GL_SCISSOR_BOX, oldScissor);
            glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
            glViewport((int) viewport.x, (int) viewport.y, (int) viewport.z, (int) viewport.w);
            glDisable(GL_SCISSOR_TEST);
        }

        @Override
        public void close() {
            if (restored) return;
            restored = true;
            glBindFramebuffer(GL_READ_FRAMEBUFFER, read);
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, draw);
            glViewport(oldViewport[0], oldViewport[1], oldViewport[2], oldViewport[3]);
            glScissor(oldScissor[0], oldScissor[1], oldScissor[2], oldScissor[3]);
            if (scissor) glEnable(GL_SCISSOR_TEST);
            else glDisable(GL_SCISSOR_TEST);
        }
    }

    void capture() {
        valid = false;
        try {
            copyDepth();
            valid = true;
            error = "";
        } catch (RuntimeException failure) {
            failures++;
            String message = failure.toString();
            if (!message.equals(error)) System.err.println("Voxy native coverage: " + message);
            error = message;
        }
    }

    private void copyDepth() {
        int source = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        int read = glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
        int binding = glGetInteger(GL_TEXTURE_BINDING_2D);
        boolean scissor = glIsEnabled(GL_SCISSOR_TEST);
        try {
            try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
                var rectangle = stack.mallocInt(4);
                glGetIntegerv(GL_VIEWPORT, rectangle);
                viewport.set(
                        rectangle.get(0), rectangle.get(1), rectangle.get(2), rectangle.get(3));
            }
            int type =
                    glGetFramebufferAttachmentParameteri(
                            GL_DRAW_FRAMEBUFFER,
                            GL_DEPTH_ATTACHMENT,
                            GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
            int attachment =
                    glGetFramebufferAttachmentParameteri(
                            GL_DRAW_FRAMEBUFFER,
                            GL_DEPTH_ATTACHMENT,
                            GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
            int w, h, internal;
            if (type == GL_TEXTURE) {
                glBindTexture(GL_TEXTURE_2D, attachment);
                w = glGetTexLevelParameteri(GL_TEXTURE_2D, 0, GL_TEXTURE_WIDTH);
                h = glGetTexLevelParameteri(GL_TEXTURE_2D, 0, GL_TEXTURE_HEIGHT);
                internal = glGetTexLevelParameteri(GL_TEXTURE_2D, 0, GL_TEXTURE_INTERNAL_FORMAT);
            } else if (type == GL_RENDERBUFFER) {
                int renderbuffer = glGetInteger(GL_RENDERBUFFER_BINDING);
                try {
                    glBindRenderbuffer(GL_RENDERBUFFER, attachment);
                    w = glGetRenderbufferParameteri(GL_RENDERBUFFER, GL_RENDERBUFFER_WIDTH);
                    h = glGetRenderbufferParameteri(GL_RENDERBUFFER, GL_RENDERBUFFER_HEIGHT);
                    internal =
                            glGetRenderbufferParameteri(
                                    GL_RENDERBUFFER, GL_RENDERBUFFER_INTERNAL_FORMAT);
                } finally {
                    glBindRenderbuffer(GL_RENDERBUFFER, renderbuffer);
                }
            } else throw new IllegalStateException("Native terrain has no depth attachment");
            if (w <= 0 || h <= 0) throw new IllegalStateException("Native depth has no storage");
            if (texture == 0 || w != width || h != height || internal != format) {
                close();
                width = w;
                height = h;
                format = internal;
                texture = glGenTextures();
                glBindTexture(GL_TEXTURE_2D, texture);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_COMPARE_MODE, GL_NONE);
                boolean stencil =
                        internal == GL_DEPTH24_STENCIL8 || internal == GL_DEPTH32F_STENCIL8;
                int pixelType =
                        internal == GL_DEPTH32F_STENCIL8
                                ? GL_FLOAT_32_UNSIGNED_INT_24_8_REV
                                : stencil ? GL_UNSIGNED_INT_24_8 : GL_FLOAT;
                glTexImage2D(
                        GL_TEXTURE_2D,
                        0,
                        internal,
                        w,
                        h,
                        0,
                        stencil ? GL_DEPTH_STENCIL : GL_DEPTH_COMPONENT,
                        pixelType,
                        (java.nio.ByteBuffer) null);
                framebuffer = glGenFramebuffers();
                glBindFramebuffer(GL_DRAW_FRAMEBUFFER, framebuffer);
                glFramebufferTexture2D(
                        GL_DRAW_FRAMEBUFFER,
                        stencil ? GL_DEPTH_STENCIL_ATTACHMENT : GL_DEPTH_ATTACHMENT,
                        GL_TEXTURE_2D,
                        texture,
                        0);
                glDrawBuffer(GL_NONE);
                glBindFramebuffer(GL_READ_FRAMEBUFFER, framebuffer);
                glReadBuffer(GL_NONE);
                if (glCheckFramebufferStatus(GL_DRAW_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE)
                    throw new IllegalStateException(
                            "Native terrain depth copy framebuffer incomplete");
            }
            glBindFramebuffer(GL_READ_FRAMEBUFFER, source);
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, framebuffer);
            if (scissor) glDisable(GL_SCISSOR_TEST);
            glBlitFramebuffer(0, 0, w, h, 0, 0, w, h, GL_DEPTH_BUFFER_BIT, GL_NEAREST);
            int copyError = glGetError();
            if (copyError != GL_NO_ERROR)
                throw new IllegalStateException("Native depth copy failed: " + copyError);
            sourceFramebuffer = source;
            sourceDepth = attachment;
            sourceDepthType = type;
        } finally {
            glBindFramebuffer(GL_READ_FRAMEBUFFER, read);
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, source);
            glBindTexture(GL_TEXTURE_2D, binding);
            if (scissor) glEnable(GL_SCISSOR_TEST);
        }
    }

    public void close() {
        if (framebuffer != 0) glDeleteFramebuffers(framebuffer);
        if (texture != 0) glDeleteTextures(texture);
        framebuffer = texture = 0;
        sourceFramebuffer = sourceDepth = sourceDepthType = 0;
        valid = false;
    }
}
