package me.cortex.voxy.debug.mixin;

import me.cortex.voxy.client.lod.ClientLodNetwork;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.debug.LodAudit;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.ByteBuffer;

/** Debug-only section demand and decode lifecycle hooks. */
@Mixin(value = ClientLodNetwork.class, remap = false)
abstract class MixinClientLodNetworkDebug {
    private static final ThreadLocal<Integer> VOXY$SECTION_WIRE_BYTES = new ThreadLocal<>();
    @Inject(method = "subscribe", at = @At("RETURN"))
    private static void voxy$debugSubscribe(long key, CallbackInfo ci) {
        LodAudit.watched(key);
    }

    @Inject(method = "unsubscribe", at = @At("RETURN"))
    private static void voxy$debugUnsubscribe(long key, CallbackInfo ci) {
        LodAudit.unwatched(key);
    }

    @Inject(method = "decodeSection", at = @At("HEAD"))
    private static void voxy$debugSectionBytes(long session, ByteBuffer input, int[] blockMap,
                                                int[] biomeMap, long[] data,
                                                CallbackInfoReturnable<?> cir) {
        VOXY$SECTION_WIRE_BYTES.set(input.remaining() + 16);
    }

    @Inject(method = "decodeSection", at = @At("RETURN"))
    private static void voxy$debugPrepared(long session, ByteBuffer input, int[] blockMap,
                                           int[] biomeMap, long[] data,
                                           CallbackInfoReturnable<?> cir) {
        Object result = cir.getReturnValue();
        if (result != null) {
            PreparedSectionAccess section = (PreparedSectionAccess) result;
            Integer wireBytes = VOXY$SECTION_WIRE_BYTES.get();
            if (wireBytes != null) LodAudit.sectionDelivered(section.voxy$key(), wireBytes);
            LodAudit.sectionPrepared(section.voxy$key(), section.voxy$revision(), section.voxy$data());
        }
        VOXY$SECTION_WIRE_BYTES.remove();
    }

    @Inject(method = "applySection", at = @At("HEAD"))
    private static void voxy$debugApplyAttempt(WorldEngine world,
                                                @Coerce Object update,
                                                CallbackInfo ci) {
        PreparedSectionAccess section = (PreparedSectionAccess) update;
        LodAudit.sectionApplyAttempt(section.voxy$key(), section.voxy$revision());
    }

    @Inject(method = "applySection", at = @At(value = "INVOKE",
            target = "Lme/cortex/voxy/client/lod/ClientLodDebug;droppedUnsubscribed()V"))
    private static void voxy$debugDroppedUnsubscribed(WorldEngine world,
                                                       @Coerce Object update,
                                                       CallbackInfo ci) {
        PreparedSectionAccess section = (PreparedSectionAccess) update;
        LodAudit.sectionDropped(section.voxy$key(), section.voxy$revision(), "unsubscribed");
    }

    @Inject(method = "applySection", at = @At(value = "INVOKE",
            target = "Lme/cortex/voxy/client/lod/ClientLodDebug;droppedRevision()V"))
    private static void voxy$debugDroppedRevision(WorldEngine world,
                                                   @Coerce Object update,
                                                   CallbackInfo ci) {
        PreparedSectionAccess section = (PreparedSectionAccess) update;
        LodAudit.sectionDropped(section.voxy$key(), section.voxy$revision(), "older-revision");
    }

    @Mixin(targets = "me.cortex.voxy.client.lod.ClientLodNetwork$PreparedSection", remap = false)
    interface PreparedSectionAccess {
        @Accessor("key") long voxy$key();
        @Accessor("revision") long voxy$revision();
        @Accessor("data") long[] voxy$data();
    }
}
