package me.cortex.voxy.client.mixin.minecraft;

import me.cortex.voxy.client.VoxyClient;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public class MixinClientPacketListener {
    @Inject(method = "handleLogin", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/protocol/game/ClientboundLoginPacket;commonPlayerSpawnInfo()Lnet/minecraft/network/protocol/game/CommonPlayerSpawnInfo;"))
    private void voxy$init(ClientboundLoginPacket packet, CallbackInfo ci) {
        if (!VoxyClient.inSession) {
            VoxyClient.sessionStart();
        }
    }
}
