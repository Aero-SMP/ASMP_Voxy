package me.cortex.voxy.client.core.rendering;

import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.rendering.hierachical.HierarchicalOcclusionTraverser;
import me.cortex.voxy.client.core.rendering.section.backend.mdic.MDICSectionRenderer;
import me.cortex.voxy.client.core.rendering.util.DepthFramebuffer;
import me.cortex.voxy.client.core.rendering.util.HiZBuffer;
import net.minecraft.util.Mth;
import org.joml.*;

import java.lang.reflect.Field;

public final class Viewport {
    public final HiZBuffer hiZBuffer;
    public final DepthFramebuffer depthBoundingBuffer = new DepthFramebuffer();
    public final GlBuffer drawCountCallBuffer = new GlBuffer(1024).zero();
    public final GlBuffer drawCallBuffer = new GlBuffer(5 * 4 * (MDICSectionRenderer.OPAQUE_DRAW_COUNT
            + MDICSectionRenderer.TRANSLUCENT_DRAW_COUNT + MDICSectionRenderer.TEMPORAL_DRAW_COUNT)).zero();
    public final GlBuffer positionScratchBuffer = new GlBuffer(8 * 400_000).zero();
    public final GlBuffer indirectLookupBuffer = new GlBuffer(HierarchicalOcclusionTraverser.MAX_QUEUE_SIZE * 4 + 4);
    public final GlBuffer visibilityBuffer;

    private static final Field planesField;
    static {
        try {
            planesField = FrustumIntersection.class.getDeclaredField("planes");
            planesField.setAccessible(true);
        } catch (NoSuchFieldException e) {
            throw new RuntimeException(e);
        }
    }

    public int width;
    public int height;
    public int frameId;
    public Matrix4f vanillaProjection = new Matrix4f();
    public Matrix4f projection = new Matrix4f();
    public Matrix4f modelView = new Matrix4f();
    public final FrustumIntersection frustum = new FrustumIntersection();
    public final Vector4f[] frustumPlanes;
    public double cameraX;
    public double cameraY;
    public double cameraZ;

    public final Matrix4f MVP = new Matrix4f();
    public final Vector3i section = new Vector3i();
    public final Vector3f innerTranslation = new Vector3f();

    public Viewport(int maxSectionCount) {
        Vector4f[] planes = null;
        try {
             planes = (Vector4f[]) planesField.get(this.frustum);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
        this.frustumPlanes = planes;

        this.hiZBuffer = new HiZBuffer();
        this.visibilityBuffer = new GlBuffer(maxSectionCount * 4L);
    }

    public final void delete() {
        this.hiZBuffer.free();
        this.depthBoundingBuffer.free();
        this.visibilityBuffer.free();
        this.indirectLookupBuffer.free();
        this.drawCountCallBuffer.free();
        this.drawCallBuffer.free();
        this.positionScratchBuffer.free();
    }

    public Viewport setVanillaProjection(Matrix4fc projection) {
        this.vanillaProjection.set(projection);
        return this;
    }

    public Viewport setProjection(Matrix4f projection) {
        this.projection = projection;
        return this;
    }

    public Viewport setModelView(Matrix4fc modelView) {
        this.modelView.set(modelView);
        return this;
    }

    public Viewport setCamera(double x, double y, double z) {
        this.cameraX = x;
        this.cameraY = y;
        this.cameraZ = z;
        return this;
    }

    public Viewport setScreenSize(int width, int height) {
        this.width = width;
        this.height = height;
        return this;
    }

    public Viewport update() {
        //MVP
        this.projection.mul(this.modelView, this.MVP);

        //Update the frustum
        this.frustum.set(this.MVP, false);

        //Translation vectors
        int sx = Mth.floor(this.cameraX)>>5;
        int sy = Mth.floor(this.cameraY)>>5;
        int sz = Mth.floor(this.cameraZ)>>5;
        this.section.set(sx, sy, sz);

        this.innerTranslation.set(
                (float) (this.cameraX-(sx<<5)),
                (float) (this.cameraY-(sy<<5)),
                (float) (this.cameraZ-(sz<<5)));

        if (this.depthBoundingBuffer.resize(this.width, this.height)) {
            this.depthBoundingBuffer.clear(0.0f);
        }

        return this;
    }

    public GlBuffer getRenderList() {
        return this.indirectLookupBuffer;
    }
}
