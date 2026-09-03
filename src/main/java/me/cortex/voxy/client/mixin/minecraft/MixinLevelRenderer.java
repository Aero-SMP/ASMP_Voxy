package me.cortex.voxy.client.mixin.minecraft;

import me.cortex.voxy.client.VoxyClient;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.lod.ClientLodClient;
import me.cortex.voxy.client.core.model.CatalogMapper;
import me.cortex.voxy.common.Logger;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class MixinLevelRenderer implements IGetVoxyRenderSystem {
    @Shadow private @Nullable ClientLevel level;
    @Unique private VoxyRenderSystem renderer;

    @Override
    public VoxyRenderSystem voxy$getRenderSystem() {
        return this.renderer;
    }

    @Inject(method = "allChanged()V", at = @At("RETURN"), order = 900)//We want to inject before sodium
    private void voxy$reloadVoxyRenderer(CallbackInfo ci) {
        this.voxy$shutdownRenderer();
        if (this.level != null) {
            this.voxy$createRenderer();
        }
    }

    @Inject(method = "setLevel", at = @At("HEAD"))
    private void voxy$captureSetWorld(ClientLevel world, CallbackInfo ci) {
        if (this.level != world) {
            this.voxy$shutdownRenderer();
        }
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void voxy$injectClose(CallbackInfo ci) {
        this.voxy$shutdownRenderer();
    }

    @Override
    public void voxy$shutdownRenderer() {
        if (this.renderer != null) {
            // Stop regional decode, meshing, publication, and GPU fences while every renderer
            // and model resource they own is still valid.
            ClientLodClient.rendererLifecycleChanged();
            VoxyRenderSystem closing = this.renderer;
            this.renderer = null;
            closing.shutdown();
        }
    }

    @Override
    public void voxy$createRenderer() {
        if (this.renderer != null) throw new IllegalStateException("Cannot have multiple renderers");
        if (!VoxyConfig.CONFIG.enabled) {
            Logger.info("Not creating renderer due to disabled");
            return;
        }
        if (!VoxyConfig.CONFIG.isRenderingEnabled()) {
            Logger.info("Not creating renderer due to disabled rendering");
            return;
        }
        if (this.level == null) {
            Logger.error("Not creating renderer due to null world");
            return;
        }
        CatalogMapper mapper = VoxyClient.getMapper();
        if (mapper == null) {
            Logger.error("Not creating renderer due to null runtime");
            return;
        }
        try {
            // Clear demand owned by the previous renderer before the new tracker publishes its
            // initial window. Clearing it after construction discards that new window and leaves
            // the QUIC session with no coverage roots, so it never requests terrain.
            ClientLodClient.rendererLifecycleChanged();
            VoxyRenderSystem created = new VoxyRenderSystem(mapper);
            this.renderer = created;
        } catch (RuntimeException e) {
            // Renderer setup runs inside Minecraft's login packet.  Propagating a shader or
            // driver failure leaves the connection with a level but no player, causing a
            // misleading vanilla NPE on the following tick.  Iris-specific setup failures are
            // handled inside VoxyRenderSystem; a core failure disables Voxy for this reload.
            Logger.error("Failed to create Voxy renderer; continuing without Voxy rendering", e);
        }
    }
}
