package me.cortex.voxy.client.core.rendering.section.backend;


import me.cortex.voxy.client.core.RenderProperties;
import me.cortex.voxy.client.core.gl.shader.Shader;
import me.cortex.voxy.client.core.gl.shader.ShaderType;
import me.cortex.voxy.client.core.model.ModelStore;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.section.geometry.BasicSectionGeometryData;
import me.cortex.voxy.common.Logger;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Direction;
import net.minecraft.world.level.dimension.DimensionType;

import java.util.List;

//Takes in mesh ids from the hierachical traversal and may perform more culling then renders it
public abstract class AbstractSectionRenderer<T extends Viewport<T>> {
    protected final BasicSectionGeometryData geometryManager;
    protected final ModelStore modelStore;
    protected final RenderProperties properties;
    protected AbstractSectionRenderer(RenderProperties properties, ModelStore modelStore, BasicSectionGeometryData geometryManager) {
        this.properties = properties;
        this.geometryManager = geometryManager;
        this.modelStore = modelStore;
    }

    public abstract void renderOpaque(T viewport);
    public abstract void buildDrawCalls(T viewport);
    public abstract void renderTemporal(T viewport);
    public void postOpaquePreperation(T viewport){}//can be used for next frame culling
    public abstract void renderTranslucent(T viewport);
    public abstract T createViewport();
    public abstract void free();

    public BasicSectionGeometryData getGeometryManager() {
        return this.geometryManager;
    }

    public void addDebug(List<String> lines) {}

    protected static void addDirectionalFaceTint(Shader.Builder<?> builder, ClientLevel cl) {
        builder.define("NO_SHADE_FACE_TINT", cl.getShade(Direction.UP, false));
        builder.define("UP_FACE_TINT", cl.getShade(Direction.UP, true));
        builder.define("DOWN_FACE_TINT", cl.getShade(Direction.DOWN, true));
        builder.define("Z_AXIS_FACE_TINT", cl.getShade(Direction.NORTH, true));//assumed here that Direction.SOUTH returns the same value
        builder.define("X_AXIS_FACE_TINT", cl.getShade(Direction.EAST, true));//assumed here that Direction.WEST returns the same value
        /*
        //TODO: generate the tinting table here and use the replacement feature
        float[] tints = new float[7];
        tints[6] = cl.getShade(Direction.UP, false);
        for (Direction direction : Direction.values()) {
            tints[direction.get3DDataValue()] = cl.getShade(direction, true);
        }*/
    }

    protected static Shader tryCompilePatchedOrNormal(Shader.Builder<?> builder, String shader, String original) {
        boolean patched = shader != original;//This is the correct comparison type (reference)
        try {
            return builder.clone()
                    .defineIf("PATCHED_SHADER", patched)
                    .addSource(ShaderType.FRAGMENT, shader)
                    .compile();
        } catch (RuntimeException e) {
            if (patched) {
                Logger.error("Failed to compile shader patch, using normal pipeline to prevent errors", e);
                return tryCompilePatchedOrNormal(builder, original, original);
            } else {
                throw e;
            }
        }
    }
}
