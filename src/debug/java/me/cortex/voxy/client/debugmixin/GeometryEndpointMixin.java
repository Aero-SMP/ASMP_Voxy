package me.cortex.voxy.client.debugmixin;

import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.rendering.section.BasicSectionGeometryData;
import me.cortex.voxy.client.lod.GeometryEndpointTelemetry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = BasicSectionGeometryData.class, remap = false)
public abstract class GeometryEndpointMixin {
    @Inject(method = "<init>", at = @At("RETURN"))
    private void voxy$recordCapacity(int maxSections, GlBuffer geometry, CallbackInfo ci) {
        GeometryEndpointTelemetry.reset(geometry.size(), geometry.isSparse());
    }

    @Inject(method = "ensureAccessible", at = @At("RETURN"))
    private void voxy$recordEndpoint(long exclusiveElementEnd, CallbackInfo ci) {
        GeometryEndpointTelemetry.accepted(exclusiveElementEnd * 8L);
    }
}
