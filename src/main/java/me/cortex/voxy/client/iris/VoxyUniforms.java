package me.cortex.voxy.client.iris;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.rendering.Viewport;
import net.irisshaders.iris.gl.uniform.UniformHolder;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import java.util.function.Supplier;

import static net.irisshaders.iris.gl.uniform.UniformUpdateFrequency.PER_FRAME;

public class VoxyUniforms {

    private static Viewport viewport() {
        var renderer = IGetVoxyRenderSystem.getNullable();
        // Iris can evaluate uniforms during construction, failed reloads or shadow rendering.
        // A live renderer does not imply a drawable viewport.
        return renderer == null ? null : renderer.getViewport();
    }

    public static Matrix4f getViewProjection() { return getViewProjection(viewport()); }
    public static Matrix4f getModelView() { return getModelView(viewport()); }
    public static Matrix4f getProjection() { return getProjection(viewport()); }

    static Matrix4f getViewProjection(Viewport viewport) {
        return copyOrIdentity(viewport == null ? null : viewport.MVP);
    }

    static Matrix4f getModelView(Viewport viewport) {
        return copyOrIdentity(viewport == null ? null : viewport.modelView);
    }

    static Matrix4f getProjection(Viewport viewport) {
        return copyOrIdentity(viewport == null ? null : viewport.projection);
    }

    private static Matrix4f copyOrIdentity(Matrix4fc matrix) {
        return matrix == null ? new Matrix4f() : new Matrix4f(matrix);
    }

    public static void addUniforms(UniformHolder uniforms) {
        uniforms
                .uniform1i(PER_FRAME, "vxRenderDistance", ()->Math.round(VoxyConfig.CONFIG.sectionRenderDistance*32))//In chunks
                .uniformMatrix(PER_FRAME, "vxViewProj", VoxyUniforms::getViewProjection)
                .uniformMatrix(PER_FRAME, "vxViewProjInv", new Inverted(VoxyUniforms::getViewProjection))
                .uniformMatrix(PER_FRAME, "vxViewProjPrev", new PreviousMat(VoxyUniforms::getViewProjection))
                .uniformMatrix(PER_FRAME, "vxModelView", VoxyUniforms::getModelView)
                .uniformMatrix(PER_FRAME, "vxModelViewInv", new Inverted(VoxyUniforms::getModelView))
                .uniformMatrix(PER_FRAME, "vxModelViewPrev", new PreviousMat(VoxyUniforms::getModelView))
                .uniformMatrix(PER_FRAME, "vxProj", VoxyUniforms::getProjection)
                .uniformMatrix(PER_FRAME, "vxProjInv", new Inverted(VoxyUniforms::getProjection))
                .uniformMatrix(PER_FRAME, "vxProjPrev", new PreviousMat(VoxyUniforms::getProjection));
    }




    record Inverted(Supplier<Matrix4fc> parent) implements Supplier<Matrix4fc> {
        public Matrix4fc get() {
            Matrix4f copy = new Matrix4f(this.parent.get());
            copy.invert();
            return copy;
        }
    }

    static class PreviousMat implements Supplier<Matrix4fc> {
        private final Supplier<Matrix4fc> parent;
        private Matrix4f previous;

        PreviousMat(Supplier<Matrix4fc> parent) {
            this.parent = parent;
            this.previous = new Matrix4f();
        }

        public Matrix4fc get() {
            Matrix4f previous = this.previous;
            this.previous = new Matrix4f(this.parent.get());
            return previous;
        }
    }
}
