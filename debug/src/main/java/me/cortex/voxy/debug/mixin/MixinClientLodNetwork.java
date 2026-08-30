package me.cortex.voxy.debug.mixin;

import me.cortex.voxy.client.lod.ClientLodNetwork;
import me.cortex.voxy.commonImpl.lod.LodNetwork;
import me.cortex.voxy.debug.ClientDiagnostics;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ClientLodNetwork.class, remap = false)
abstract class MixinClientLodNetwork {
    @Inject(method = "processLODData", at = @At("HEAD"))
    private static void voxyDebug$received(ClientLevel level, LodNetwork.LODDataPayload payload,
                                           CallbackInfo callback) {
        long bytes = 0;
        for (var section : payload.sections()) {
            bytes += section.states().length + section.biomes().length;
            if (section.blockLight() != null) bytes += section.blockLight().length;
            if (section.skyLight() != null) bytes += section.skyLight().length;
        }
        ClientDiagnostics.received(bytes);
    }

    @Inject(method = "setServerConnected", at = @At("RETURN"))
    private static void voxyDebug$connectionChanged(boolean connected, CallbackInfo callback) {
        if (!connected) ClientDiagnostics.resetNetwork();
    }
}
