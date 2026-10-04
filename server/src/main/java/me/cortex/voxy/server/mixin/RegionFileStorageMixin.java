package me.cortex.voxy.server.mixin;

import me.cortex.voxy.server.VoxyServer;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(RegionFileStorage.class)
abstract class RegionFileStorageMixin {
    @Shadow @Final private RegionStorageInfo info;

    @Inject(method = "write", at = @At("RETURN"))
    private void voxy$saved(ChunkPos position, CompoundTag data, CallbackInfo callback) {
        if (info.type().equals("chunk")) VoxyServer.completedTerrainSave(info.dimension(), position.x, position.z);
    }
}
