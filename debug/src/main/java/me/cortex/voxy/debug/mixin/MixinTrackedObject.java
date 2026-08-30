package me.cortex.voxy.debug.mixin;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.util.TrackedObject;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.ref.Cleaner;

@Mixin(value = TrackedObject.class, remap = false)
abstract class MixinTrackedObject {
    @Unique private static final Cleaner voxyDebug$cleaner = Cleaner.create();
    @Unique private boolean[] voxyDebug$freed;
    @Unique private Cleaner.Cleanable voxyDebug$cleanable;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void voxyDebug$track(CallbackInfo callback) {
        if ((Object) this instanceof MemoryBuffer) return;
        this.voxyDebug$freed = new boolean[1];
        boolean[] freed = this.voxyDebug$freed;
        String type = this.getClass().getName();
        Throwable allocation = Boolean.getBoolean("voxy.trackObjectAllocationStacks")
                ? new Throwable("Allocated here") : null;
        this.voxyDebug$cleanable = voxyDebug$cleaner.register(this, () -> {
            if (!freed[0]) {
                Logger.error("Voxy object was not freed: " + type,
                        allocation == null ? "Use -Dvoxy.trackObjectAllocationStacks=true for a trace"
                                : allocation);
            }
        });
    }

    @Inject(method = "free0", at = @At("RETURN"))
    private void voxyDebug$freed(CallbackInfo callback) {
        if (this.voxyDebug$cleanable == null) return;
        this.voxyDebug$freed[0] = true;
        this.voxyDebug$cleanable.clean();
    }
}
