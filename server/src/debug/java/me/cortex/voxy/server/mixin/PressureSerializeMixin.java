package me.cortex.voxy.server.mixin;

import me.cortex.voxy.server.LiveTerrainChanges;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ChunkSerializer.class)
abstract class PressureSerializeMixin {
    @Inject(method = "write", at = @At("RETURN"))
    private static void voxy$serialized(ServerLevel level, ChunkAccess chunk, CallbackInfoReturnable<CompoundTag> callback) {
        LiveTerrainChanges.serialized(level, chunk, callback.getReturnValue());
    }
}
