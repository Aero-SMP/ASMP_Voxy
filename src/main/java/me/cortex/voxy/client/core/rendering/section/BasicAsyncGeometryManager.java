package me.cortex.voxy.client.core.rendering.section;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import me.cortex.voxy.client.core.rendering.building.BuiltSection;
import me.cortex.voxy.common.util.AllocationArena;
import me.cortex.voxy.common.util.HierarchicalBitSet;
import me.cortex.voxy.common.util.MemoryBuffer;
import org.lwjgl.system.MemoryUtil;
/** Stages geometry allocation changes before applying them to the backing GPU data store. */
public class BasicAsyncGeometryManager {
    public static final int SECTION_METADATA_SIZE = 48;

    private static final long GEOMETRY_ELEMENT_SIZE = 8;
    private final HierarchicalBitSet allocationSet;
    private final AllocationArena allocationHeap = new AllocationArena();
    private final ObjectArrayList<SectionMeta> sectionMetadata = new ObjectArrayList<>(1<<15);

    //Changes that need to be applied to the underlying data store to match this state
    private final IntOpenHashSet invalidatedIds = new IntOpenHashSet(1024);//Ids that need to be invalidated
    //TODO: maybe change from it pointing to MemoryBuffer, to BuiltSection
    //Note!: the int part is an unsigned int ptr, must be scaled by GEOMETRY_ELEMENT_SIZE
    private final Int2ObjectOpenHashMap<MemoryBuffer> heapUploads = new Int2ObjectOpenHashMap<>(1024);//Uploads into the buffer at the given location
    private final IntOpenHashSet heapRemoveUploads = new IntOpenHashSet(1024);//Any removals are added here, so that it can be properly synced
    private long usedCapacity = 0;
    private long pendingUploadBytes = 0;

    public BasicAsyncGeometryManager(int maxSectionCount, long geometryCapacity) {
        this.allocationSet = new HierarchicalBitSet(maxSectionCount);
        if (geometryCapacity%GEOMETRY_ELEMENT_SIZE != 0)  throw new IllegalStateException();
        this.allocationHeap.setLimit(geometryCapacity/GEOMETRY_ELEMENT_SIZE);
    }

    public int uploadSection(BuiltSection section) {
        return this.uploadReplaceSection(-1, section);
    }

    public int uploadReplaceSection(int oldId, BuiltSection section) {
        if (section.isEmpty()) {
            throw new IllegalArgumentException("sectionData is empty, cannot upload nothing");
        }

        // Admission of the complete replacement happens before the active allocation is retired.
        // Geometry OOM or malformed candidate metadata therefore cannot punch a transient hole.
        if (oldId != -1) {
            int replacement = this.uploadReplaceSection(-1, section);
            this.removeSection(oldId);
            return replacement;
        }

        //Free the old id and replace it with a new one
        // if oldId is -1, then treat it as not previously existing

        int newId =  this.allocationSet.allocateNext();
        if (newId == HierarchicalBitSet.SET_FULL) {
            throw new IllegalStateException("Tried adding section when section count is already at capacity");
        }
        if (newId > this.sectionMetadata.size()) {
            throw new IllegalStateException("Size exceeds limits: " + newId + ", " + this.sectionMetadata.size() + ", " + this.allocationSet.getCount());
        }

        if (newId < this.sectionMetadata.size()) {
            if (this.sectionMetadata.get(newId) != null) {
                throw new IllegalStateException();
            }
        }

        SectionMeta newMeta;
        try {
            newMeta = this.createMeta(section);
        } catch (RuntimeException | Error failure) {
            if (!this.allocationSet.free(newId)) {
                failure.addSuppressed(new IllegalStateException(
                        "failed geometry admission leaked its section id"));
            }
            throw failure;
        }

        if (newId == this.sectionMetadata.size()) {
            this.sectionMetadata.add(newMeta);
        } else {
            if (this.sectionMetadata.set(newId, newMeta) != null) {
                throw new IllegalStateException();
            }
        }

        //Invalidate the section id
        this.invalidatedIds.add(newId);

        return newId;
    }

    public void removeSection(int id) {
        if (!this.allocationSet.free(id)) {
            throw new IllegalStateException("Id was not already allocated. id: " + id);
        }
        var oldMetadata = this.sectionMetadata.set(id, null);
        int ptr = oldMetadata.geometryPtr;
        //Free from the heap
        this.usedCapacity -= this.allocationHeap.free(Integer.toUnsignedLong(ptr));
        //Free the upload if it was uploading
        var buf = this.heapUploads.remove(ptr);
        if (buf != null) {
            this.pendingUploadBytes -= buf.size;
            buf.free();
        }
        this.heapRemoveUploads.add(ptr);
        this.invalidatedIds.add(id);
    }

    private SectionMeta createMeta(BuiltSection section) {
        if ((section.geometryBuffer.size%GEOMETRY_ELEMENT_SIZE)!=0) throw new IllegalStateException();
        int size = (int) (section.geometryBuffer.size/GEOMETRY_ELEMENT_SIZE);
        //clamp size upwards to ranges of 127
        int upsized = (size+127)&~127;
        //Address
        int addr = (int)this.allocationHeap.alloc(upsized);
        if (addr == -1) {
            throw new IllegalStateException("Geometry OOM. requested allocation size (in elements): " + size + ", Heap size at top remaining: " + (this.allocationHeap.getLimit()-this.allocationHeap.getSize()) + ", used elements: " + this.usedCapacity);
        }
        this.usedCapacity += upsized;
        //Create upload
        if (this.heapUploads.put(addr, section.geometryBuffer) != null) {
            throw new IllegalStateException("Addr: " + addr);
        }
        this.pendingUploadBytes += section.geometryBuffer.size;
        this.heapRemoveUploads.remove(addr);
        //Create Meta
        return new SectionMeta(section.position, section.aabb, addr, size, section.offsets, section.childExistence);
    }

    public Int2ObjectOpenHashMap<MemoryBuffer> getUploads() {
        return this.heapUploads;
    }

    public long getPendingUploadBytes() {
        return this.pendingUploadBytes;
    }

    public void uploadsDrained() {
        if (!this.heapUploads.isEmpty()) {
            throw new IllegalStateException("geometry uploads were not drained");
        }
        this.pendingUploadBytes = 0;
    }

    public IntOpenHashSet getHeapRemovals() {
        return this.heapRemoveUploads;
    }

    public int getSectionCount() {
        return this.allocationSet.getCount();
    }

    public long getGeometryUsedBytes() {
        return this.usedCapacity * GEOMETRY_ELEMENT_SIZE;
    }

    public IntOpenHashSet getUpdateIds() {
        return this.invalidatedIds;
    }

    public void writeMetadata(int sectionId, long ptr) {
        var sec = this.sectionMetadata.get(sectionId);
        if (sec == null) {
            //Write nothing
            MemoryUtil.memSet(ptr, 0, SECTION_METADATA_SIZE);
        } else {
            sec.writeMetadata(ptr);
        }
    }

    public void writeMetadataSplit(int sectionId, long ptrA, long ptrB, long ptrC) {
        var sec = this.sectionMetadata.get(sectionId);
        if (sec == null) {
            //Write nothing
            MemoryUtil.memSet(ptrA, 0, 16);
            MemoryUtil.memSet(ptrB, 0, 16);
            MemoryUtil.memSet(ptrC, 0, 16);
        } else {
            sec.writeMetadataSplitParts(ptrA, ptrB, ptrC);
        }
    }

    private record SectionMeta(long position, int aabb, int geometryPtr, int itemCount, int[] offsets, byte childExistence) {
        public void writeMetadata(long ptr) {
            this.writeMetadataSplitParts(ptr, ptr+16, ptr+32);
        }

        public void writeMetadataSplitParts(long ptrA, long ptrB, long ptrC) {
            //Split the long into 2 ints to solve endian issues
            MemoryUtil.memPutInt(ptrA, (int) (this.position>>32)); ptrA += 4;
            MemoryUtil.memPutInt(ptrA, (int) this.position); ptrA += 4;
            MemoryUtil.memPutInt(ptrA, (int) this.aabb); ptrA += 4;
            MemoryUtil.memPutInt(ptrA, this.geometryPtr + this.offsets[0]); ptrA += 4;

            MemoryUtil.memPutInt(ptrB, this.offsets[1]-this.offsets[0]); ptrB += 4;
            MemoryUtil.memPutInt(ptrB, this.offsets[2]-this.offsets[1]); ptrB += 4;
            MemoryUtil.memPutInt(ptrB, this.offsets[3]-this.offsets[2]); ptrB += 4;
            MemoryUtil.memPutInt(ptrB, this.offsets[4]-this.offsets[3]);
            MemoryUtil.memPutInt(ptrC, this.offsets[5]-this.offsets[4]); ptrC += 4;
            MemoryUtil.memPutInt(ptrC, this.offsets[6]-this.offsets[5]); ptrC += 4;
            MemoryUtil.memPutInt(ptrC, this.offsets[7]-this.offsets[6]); ptrC += 4;
            MemoryUtil.memPutInt(ptrC, this.itemCount-this.offsets[7]);
        }
    }
}
