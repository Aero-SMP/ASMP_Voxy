package me.cortex.voxy.server.mixin;

import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ChunkMap.class)
public interface PressureChunkMapAccessor {
    @Invoker("save") boolean voxy$save(ChunkAccess chunk);
}
