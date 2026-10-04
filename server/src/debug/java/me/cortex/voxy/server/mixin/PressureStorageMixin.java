package me.cortex.voxy.server.mixin;

import me.cortex.voxy.server.LiveTerrainChanges;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(RegionFileStorage.class)
abstract class PressureStorageMixin {
    @Unique private CompoundTag voxy$saveStamp;

    @Inject(method = "write", at = @At("HEAD"))
    private void voxy$beforeWrite(ChunkPos position, CompoundTag tag, CallbackInfo callback) {
        voxy$saveStamp = tag == null || !tag.contains(LiveTerrainChanges.STAMP) ? null : tag.getCompound(LiveTerrainChanges.STAMP);
        // The receipt travels with the snapshot but is removed before any world bytes are written.
        if (tag != null) tag.remove(LiveTerrainChanges.STAMP);
    }

    @Inject(method = "write", at = @At("RETURN"))
    private void voxy$afterWrite(ChunkPos position, CompoundTag tag, CallbackInfo callback) {
        CompoundTag stamp = voxy$saveStamp; voxy$saveStamp = null;
        LiveTerrainChanges.saved(stamp);
    }
}
