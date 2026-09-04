package me.cortex.voxy.debugtest;

import net.minecraft.network.RegistryFriendlyByteBuf;

/** Immutable, cumulative observation of the real client and Voxy renderer. */
public record DebugTestSnapshot(
        long presence,
        long monotonicNanos,
        int renderedFrame,
        String dimension,
        double playerX, double playerY, double playerZ,
        float playerYaw, float playerPitch,
        double cameraX, double cameraY, double cameraZ,
        float cameraYaw, float cameraPitch, boolean firstPerson,
        long sessionGeneration, long connectionEpoch,
        long rootGeneration, long publicationGeneration,
        int failureCode, long retryNanos,
        long coverageMissing, long requested, long downloading, long cacheReading,
        long decoding, long meshing, long ready, long publishing, long active,
        long networkBytes, long completedBatches,
        long cacheHits, long cacheMisses, long cacheReads, long cacheBytes,
        long decodedTotal, long meshedTotal, long uploadedTotal,
        long activatedTotal, long retiredTotal,
        long selectedBytes, long warmBytes, long coldBytes,
        long pendingRetirementBytes, long physicalGeometryBytes,
        long rendererTargetBytes, long rendererAllocatedBytes,
        int gpuSelected, int gpuDraws, long gpuReadbackAgeNanos) {
    public static final long POSE_PRESENT = 1L;
    public static final long SESSION_PRESENT = 1L << 1;
    public static final long GEOMETRY_RETENTION_PRESENT = 1L << 2;
    public static final long GPU_COUNTERS_PRESENT = 1L << 3;

    public static DebugTestSnapshot empty() {
        return new DebugTestSnapshot(0, 0, 0, "", 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, false, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    static DebugTestSnapshot decode(RegistryFriendlyByteBuf input) {
        long presence = input.readVarLong();
        long monotonicNanos = input.readVarLong();
        int renderedFrame = input.readVarInt();
        String dimension = input.readUtf(DebugTestProtocol.MAX_DIMENSION_LENGTH);
        double playerX = input.readDouble(), playerY = input.readDouble();
        double playerZ = input.readDouble();
        float playerYaw = input.readFloat(), playerPitch = input.readFloat();
        double cameraX = input.readDouble(), cameraY = input.readDouble();
        double cameraZ = input.readDouble();
        float cameraYaw = input.readFloat(), cameraPitch = input.readFloat();
        boolean firstPerson = input.readBoolean();
        long sessionGeneration = input.readVarLong(), connectionEpoch = input.readVarLong();
        long rootGeneration = input.readVarLong(), publicationGeneration = input.readVarLong();
        int failureCode = input.readVarInt();
        long retryNanos = input.readVarLong();
        long[] counters = new long[27];
        for (int index = 0; index < counters.length; index++) {
            counters[index] = input.readVarLong();
        }
        return new DebugTestSnapshot(presence, monotonicNanos, renderedFrame, dimension,
                playerX, playerY, playerZ, playerYaw, playerPitch,
                cameraX, cameraY, cameraZ, cameraYaw, cameraPitch,
                firstPerson,
                sessionGeneration, connectionEpoch, rootGeneration, publicationGeneration,
                failureCode, retryNanos, counters[0], counters[1], counters[2], counters[3],
                counters[4], counters[5], counters[6], counters[7], counters[8], counters[9],
                counters[10], counters[11], counters[12], counters[13], counters[14],
                counters[15], counters[16], counters[17], counters[18], counters[19],
                counters[20], counters[21], counters[22], counters[23], counters[24],
                counters[25], counters[26], input.readVarInt(), input.readVarInt(),
                input.readVarLong());
    }

    void encode(RegistryFriendlyByteBuf output) {
        output.writeVarLong(this.presence); output.writeVarLong(this.monotonicNanos);
        output.writeVarInt(this.renderedFrame);
        output.writeUtf(this.dimension, DebugTestProtocol.MAX_DIMENSION_LENGTH);
        output.writeDouble(this.playerX); output.writeDouble(this.playerY);
        output.writeDouble(this.playerZ); output.writeFloat(this.playerYaw);
        output.writeFloat(this.playerPitch); output.writeDouble(this.cameraX);
        output.writeDouble(this.cameraY); output.writeDouble(this.cameraZ);
        output.writeFloat(this.cameraYaw); output.writeFloat(this.cameraPitch);
        output.writeBoolean(this.firstPerson);
        output.writeVarLong(this.sessionGeneration); output.writeVarLong(this.connectionEpoch);
        output.writeVarLong(this.rootGeneration); output.writeVarLong(this.publicationGeneration);
        output.writeVarInt(this.failureCode); output.writeVarLong(this.retryNanos);
        long[] counters = {this.coverageMissing, this.requested, this.downloading,
                this.cacheReading, this.decoding, this.meshing, this.ready, this.publishing,
                this.active, this.networkBytes, this.completedBatches, this.cacheHits,
                this.cacheMisses, this.cacheReads, this.cacheBytes, this.decodedTotal,
                this.meshedTotal, this.uploadedTotal, this.activatedTotal, this.retiredTotal,
                this.selectedBytes, this.warmBytes, this.coldBytes, this.pendingRetirementBytes,
                this.physicalGeometryBytes, this.rendererTargetBytes,
                this.rendererAllocatedBytes};
        for (long counter : counters) output.writeVarLong(counter);
        output.writeVarInt(this.gpuSelected); output.writeVarInt(this.gpuDraws);
        output.writeVarLong(this.gpuReadbackAgeNanos);
    }

    public DebugTestSnapshot {
        if (dimension == null || dimension.length() > DebugTestProtocol.MAX_DIMENSION_LENGTH) {
            throw new IllegalArgumentException("invalid snapshot dimension");
        }
    }
}
