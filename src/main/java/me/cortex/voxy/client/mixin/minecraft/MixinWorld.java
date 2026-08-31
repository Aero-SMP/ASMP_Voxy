package me.cortex.voxy.client.mixin.minecraft;

import me.cortex.voxy.client.world.LevelIdentifierAccess;
import me.cortex.voxy.client.world.WorldIdentifier;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.WritableLevelData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Supplier;

@Mixin(Level.class)
public class MixinWorld implements LevelIdentifierAccess {
    @Unique
    private WorldIdentifier identifier;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void voxy$injectIdentifier(WritableLevelData properties,
                                       ResourceKey<Level> key,
                                       RegistryAccess registryManager,
                                       Holder<DimensionType> dimensionEntry,
                                       Supplier<ProfilerFiller> profiler,
                                       boolean isClient,
                                       boolean ignored,
                                       long seed,
                                       int maxChainedNeighborUpdates,
                                       CallbackInfo ci) {
        this.identifier = key == null ? null : new WorldIdentifier(
                key, seed, dimensionEntry == null ? null : dimensionEntry.unwrapKey().orElse(null));
    }

    @Override
    public WorldIdentifier voxy$getIdentifier() {
        return this.identifier;
    }
}
