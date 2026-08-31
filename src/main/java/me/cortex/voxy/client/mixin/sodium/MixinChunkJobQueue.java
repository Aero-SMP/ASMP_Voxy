package me.cortex.voxy.client.mixin.sodium;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.VoxyClient;
import me.cortex.voxy.common.thread.MultiThreadPrioritySemaphore;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.Semaphore;

@Mixin(targets={"net.caffeinemc.mods.sodium.client.render.chunk.compile.executor.ChunkJobQueue"},remap = false)
public class MixinChunkJobQueue {
    @Unique private MultiThreadPrioritySemaphore.Block voxy$semaphoreBlock;

    @Redirect(method = "<init>", at = @At(value = "NEW", target = "(I)Ljava/util/concurrent/Semaphore;"))
    private Semaphore voxy$injectUnifiedPool(int permits) {
        var runtime = VoxyClient.getRuntime();
        if (runtime != null && !VoxyConfig.CONFIG.dontUseSodiumBuilderThreads) {
            this.voxy$semaphoreBlock = runtime.getThreadPool().groupSemaphore.createBlock();
            return new SemaphoreBlockImpersonator(this.voxy$semaphoreBlock);
        }
        return new Semaphore(permits);
    }

    @Inject(method = "shutdown", at = @At("RETURN"))
    private void voxy$injectAtShutdown(CallbackInfoReturnable ci) {
        if (this.voxy$semaphoreBlock != null) {
            this.voxy$semaphoreBlock.free();
        }
    }

    private static final class SemaphoreBlockImpersonator extends Semaphore {
        private final MultiThreadPrioritySemaphore.Block block;

        private SemaphoreBlockImpersonator(MultiThreadPrioritySemaphore.Block block) {
            super(0);
            this.block = block;
        }

        @Override
        public void release(int permits) {
            this.block.release(permits);
        }

        @Override
        public void acquire() throws InterruptedException {
            this.block.acquire();
        }

        @Override
        public boolean tryAcquire() {
            return this.block.tryAcquire();
        }

        @Override
        public int availablePermits() {
            return this.block.availablePermits();
        }
    }
}
