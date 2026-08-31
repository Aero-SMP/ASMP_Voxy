package me.cortex.voxy.client.world;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class WorldIdentifier {
    private static final ResourceKey<DimensionType> NULL_DIM_KEY = ResourceKey.create(Registries.DIMENSION_TYPE, ResourceLocation.parse("voxy:null_dimension_id"));

    public final ResourceKey<Level> key;
    public final long biomeSeed;
    public final ResourceKey<DimensionType> dimension;//Maybe?
    private final transient long hashCode;

    public WorldIdentifier(@NotNull ResourceKey<Level> key, long biomeSeed, @Nullable ResourceKey<DimensionType> dimension) {
        if (key == null) {
            throw new IllegalStateException("Key cannot be null");
        }
        dimension = dimension==null?NULL_DIM_KEY:dimension;
        this.key = key;
        this.biomeSeed = biomeSeed;
        this.dimension = dimension;
        this.hashCode = mixStafford13(registryKeyHashCode(key))^mixStafford13(registryKeyHashCode(dimension))^mixStafford13(biomeSeed);
    }

    @Override
    public int hashCode() {
        return (int) this.hashCode;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof WorldIdentifier other) {
            return other.hashCode == this.hashCode &&
                    other.biomeSeed == this.biomeSeed &&
                    equal(other.key, this.key) &&//other.key.equals(this.key) &&
                    equal(other.dimension, this.dimension)//other.dimension.equals(this.dimension)
                    ;
        }
        return false;
    }

    private static <T> boolean equal(ResourceKey<T> a, ResourceKey<T> b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        return a.registry().equals(b.registry()) && a.location().equals(b.location());
    }

    public static WorldIdentifier of(Level level) {
        //Gets or makes an identifier for world
        if (level == null) {
            return null;
        }
        return ((LevelIdentifierAccess)level).voxy$getIdentifier();
    }

    private static long mixStafford13(long seed) {
        seed += 918759875987111L;
        seed = (seed ^ seed >>> 30) * -4658895280553007687L;
        seed = (seed ^ seed >>> 27) * -7723592293110705685L;
        return seed ^ seed >>> 31;
    }

    public long getLongHash() {
        return this.hashCode;
    }

    private static long registryKeyHashCode(ResourceKey<?> key) {
        var A = key.registry();
        var B = key.location();
        int a = A==null?0:A.hashCode();
        int b = B==null?0:B.hashCode();
        return (Integer.toUnsignedLong(a)<<32)|Integer.toUnsignedLong(b);
    }


    private static String bytesToHex(byte[] hash) {
        StringBuilder hexString = new StringBuilder(2 * hash.length);
        for (byte b : hash) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) {
                hexString.append('0');
            }
            hexString.append(hex);
        }
        return hexString.toString();
    }

    public String getWorldId() {
        String data = this.biomeSeed + this.key.toString();
        try {
            return bytesToHex(MessageDigest.getInstance("SHA-256").digest(data.getBytes())).substring(0, 32);
        } catch (
                NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public String toString() {
        return "WorldIdentifier[" + this.key.location().toString() + ", " + this.biomeSeed + ", " + this.dimension.location().toString() + ']';
    }
}
