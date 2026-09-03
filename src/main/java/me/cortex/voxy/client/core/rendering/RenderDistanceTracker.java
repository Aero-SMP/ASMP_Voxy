package me.cortex.voxy.client.core.rendering;

import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;

import java.util.function.LongConsumer;

public class RenderDistanceTracker {
    private static final int CHECK_DISTANCE_BLOCKS = 128;
    private static final int MAX_OPERATIONS_PER_UPDATE = 512;
    private static final long PROCESS_BUDGET_NANOS = 1_000_000L;
    private final LongConsumer addTopLevelNode;
    private final LongConsumer removeTopLevelNode;
    private final int minSec;
    private final int maxSec;
    private final long[] candidateKeys = new long[MAX_OPERATIONS_PER_UPDATE];
    private final long[] candidateDistances = new long[MAX_OPERATIONS_PER_UPDATE];
    private final byte[] candidateOperations = new byte[MAX_OPERATIONS_PER_UPDATE];
    private Long2ByteOpenHashMap operations = new Long2ByteOpenHashMap(1<<13);
    private int[] boundDist;
    private int radius;
    private int centerX;
    private int centerZ;
    private int renderDistance;
    private double posX;
    private double posZ;
    private boolean initialized;
    public RenderDistanceTracker(int minSec, int maxSec, LongConsumer addTopLevelNode,
                                 LongConsumer removeTopLevelNode) {
        this.addTopLevelNode = addTopLevelNode;
        this.removeTopLevelNode = removeTopLevelNode;
        this.radius = this.renderDistance = 2;
        this.boundDist = generateBoundingHalfCircleDistance(this.radius);
        this.minSec = minSec;
        this.maxSec = maxSec;
    }

    public void setRenderDistance(int renderDistance) {
        if (renderDistance == this.renderDistance) {
            return;
        }
        if (this.initialized) this.fillRing(false);
        var previousOperations = this.operations;
        this.operations = new Long2ByteOpenHashMap(1<<13);
        this.radius = this.renderDistance = renderDistance;
        this.centerX = (int) Math.floor(this.posX / 512.0);
        this.centerZ = (int) Math.floor(this.posZ / 512.0);
        this.boundDist = generateBoundingHalfCircleDistance(this.radius);
        this.operations.putAll(previousOperations);
        previousOperations.clear();
        if (this.initialized) this.fillRing(true);
    }

    public boolean setCenterAndProcess(double x, double z) {
        if (!this.initialized) {
            this.posX = x;
            this.posZ = z;
            this.centerX = (int) Math.floor(x / 512.0);
            this.centerZ = (int) Math.floor(z / 512.0);
            this.initialized = true;
            this.fillRing(true);
            return this.process() != 0;
        }
        double dx = this.posX-x;
        double dz = this.posZ-z;
        if (CHECK_DISTANCE_BLOCKS*CHECK_DISTANCE_BLOCKS<dx*dx+dz*dz) {
            this.posX = x;
            this.posZ = z;
            this.moveCenter((int) Math.floor(x / 512.0), (int) Math.floor(z / 512.0));
        }

        return this.process()!=0;
    }

    private void add(int x, int z) {
        for (int y = this.minSec; y <= this.maxSec; y++) {
            this.addTopLevelNode.accept(SectionKey.pack(4, x, y, z));
        }
    }

    private void rem(int x, int z) {
        for (int y = this.minSec; y <= this.maxSec; y++) {
            this.removeTopLevelNode.accept(SectionKey.pack(4, x, y, z));
        }
    }

    private static long pack(int x, int z) {
        return Integer.toUnsignedLong(x)|(Integer.toUnsignedLong(z)<<32);
    }

    private void fillRing(boolean load) {
        for (int i = 0; i <= this.radius*2; i++) {
            int x = this.centerX + i - this.radius;
            int d = this.boundDist[i];
            for (int z = this.centerZ-d; z <= this.centerZ+d; z++) {
                int res = this.operations.addTo(pack(x, z), (byte) (load?1:-1));
                if ((load&&0<res)||(((!load)&&res<0))) {
                    throw new IllegalStateException();
                }
            }
        }
    }

    private void moveCenter(int x, int z) {
        if (this.radius+1<Math.abs(x-this.centerX) || this.radius+1<Math.abs(z-this.centerZ)) {
            this.fillRing(false);
            this.centerX = x;
            this.centerZ = z;
            this.fillRing(true);
        } else {
            if (x != this.centerX) {
                moveX(x - this.centerX);
            }
            if (z != this.centerZ) {
                moveZ(z - this.centerZ);
            }
        }
    }

    private void moveZ(int delta) {
        if (delta == 0) return;
        if (delta == -1 || delta == 1) {
            for (int i = 0; i <= this.radius * 2; i++) {
                int x = this.centerX + i - this.radius;
                int d = this.boundDist[i]*delta;
                int pz = this.centerZ+d+delta;
                int nz = this.centerZ-d;
                if (0<this.operations.addTo(pack(x, pz), (byte) 1))
                    throw new IllegalStateException("x: "+x+", z: "+pz+" state: "+this.operations.get(pack(x, pz)));
                if (this.operations.addTo(pack(x, nz), (byte) -1)<0)
                    throw new IllegalStateException("x: "+x+", z: "+nz+" state: "+this.operations.get(pack(x, nz)));
            }
            this.centerZ += delta;
        } else {
            int sDelta = Integer.signum(delta);
            for (int i = 0; i <= this.radius * 2; i++) {
                int x = this.centerX + i - this.radius;
                int d = this.boundDist[i]*sDelta;
                int pz = this.centerZ+d;
                for (int z = pz + (sDelta<0?delta:1); z <= pz + (sDelta<0?-1:delta); z++) {
                    if (0<this.operations.addTo(pack(x, z), (byte) 1))
                        throw new IllegalStateException();
                }
                int nz = this.centerZ-d;
                for (int z = nz + (sDelta<0?(delta+1):0); z < nz + (sDelta<0?1:delta); z++) {
                    if (this.operations.addTo(pack(x, z), (byte) -1)<0)
                        throw new IllegalStateException();
                }
            }
            this.centerZ += delta;
        }
    }

    private void moveX(int delta) {
        if (delta == 0) return;
        if (delta == -1 || delta == 1) {
            for (int i = 0; i <= this.radius * 2; i++) {
                int z = this.centerZ + i - this.radius;
                int d = this.boundDist[i]*delta;
                int px = this.centerX+d+delta;
                int nx = this.centerX-d;
                if (0<this.operations.addTo(pack(px, z), (byte) 1))
                    throw new IllegalStateException();
                if (this.operations.addTo(pack(nx, z), (byte) -1)<0)
                    throw new IllegalStateException();
            }
            this.centerX += delta;
        } else {
            int sDelta = Integer.signum(delta);
            for (int i = 0; i <= this.radius * 2; i++) {
                int z = this.centerZ + i - this.radius;
                int d = this.boundDist[i]*sDelta;
                int px = this.centerX+d;
                for (int x = px + (sDelta<0?delta:1); x <= px + (sDelta<0?-1:delta); x++) {
                    if (0<this.operations.addTo(pack(x, z), (byte) 1))
                        throw new IllegalStateException();
                }
                int nx = this.centerX-d;
                for (int x = nx + (sDelta<0?(delta+1):0); x < nx + (sDelta<0?1:delta); x++) {
                    if (this.operations.addTo(pack(x, z), (byte) -1)<0)
                        throw new IllegalStateException();
                }
            }
            this.centerX += delta;
        }
    }

    private int process() {
        if (this.operations.isEmpty()) {
            return 0;
        }
        int candidates = 0;
        var iter = this.operations.long2ByteEntrySet().fastIterator();
        while (iter.hasNext()) {
            var entry = iter.next();
            if (entry.getByteValue()==0) {
                iter.remove();
                continue;
            }
            byte op = entry.getByteValue();
            if (op != 1 && op != -1) {
                throw new IllegalStateException();
            }
            long key = entry.getLongKey();
            int x = (int) key;
            int z = (int) (key >>> 32);
            long dx = (long) x - this.centerX;
            long dz = (long) z - this.centerZ;
            long distance = dx * dx + dz * dz;
            int insertion = candidates;
            if (insertion == MAX_OPERATIONS_PER_UPDATE
                    && compare(op, distance, key,
                    this.candidateOperations[insertion - 1],
                    this.candidateDistances[insertion - 1],
                    this.candidateKeys[insertion - 1]) >= 0) continue;
            if (insertion == MAX_OPERATIONS_PER_UPDATE) insertion--;
            while (insertion > 0 && compare(op, distance, key,
                    this.candidateOperations[insertion - 1],
                    this.candidateDistances[insertion - 1],
                    this.candidateKeys[insertion - 1]) < 0) {
                if (insertion < MAX_OPERATIONS_PER_UPDATE) {
                    this.candidateOperations[insertion] = this.candidateOperations[insertion - 1];
                    this.candidateDistances[insertion] = this.candidateDistances[insertion - 1];
                    this.candidateKeys[insertion] = this.candidateKeys[insertion - 1];
                }
                insertion--;
            }
            this.candidateOperations[insertion] = op;
            this.candidateDistances[insertion] = distance;
            this.candidateKeys[insertion] = key;
            if (candidates < MAX_OPERATIONS_PER_UPDATE) candidates++;
        }

        long deadline = System.nanoTime() + PROCESS_BUDGET_NANOS;
        int processed = 0;
        for (int index = 0; index < candidates; index++) {
            if (processed != 0 && System.nanoTime() - deadline >= 0) break;
            long pos = this.candidateKeys[index];
            byte op = this.operations.remove(pos);
            if (op == 0) continue;
            int x = (int) (pos&0xFFFFFFFFL);
            int z = (int) ((pos>>>32)&0xFFFFFFFFL);
            if (op == 1) {
                this.add(x, z);
            } else {
                this.rem(x, z);
            }
            processed++;
        }
        return processed;
    }

    /** Additions nearest the camera precede removals; obsolete removals run farthest-first. */
    private static int compare(byte leftOperation, long leftDistance, long leftKey,
                               byte rightOperation, long rightDistance, long rightKey) {
        if (leftOperation != rightOperation) return leftOperation == 1 ? -1 : 1;
        int distance = leftOperation == 1
                ? Long.compare(leftDistance, rightDistance)
                : Long.compare(rightDistance, leftDistance);
        return distance != 0 ? distance : Long.compareUnsigned(leftKey, rightKey);
    }

    private static int[] generateBoundingHalfCircleDistance(int radius) {
        var ret = new int[radius*2+1];
        for (int i = -radius; i <= radius; i++) {
            ret[i+radius] = (int)Math.sqrt(radius*radius - i*i);
        }
        return ret;
    }
}
