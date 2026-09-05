package me.cortex.voxy.client.core.rendering.hierarchical;

import me.cortex.voxy.client.core.VoxyRenderSystem.*;
import me.cortex.voxy.client.core.rendering.SectionKey;
import me.cortex.voxy.client.core.rendering.building.BuiltSection;
import me.cortex.voxy.client.core.rendering.section.BasicAsyncGeometryManager;
import me.cortex.voxy.common.util.MemoryBuffer;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** The production topology/allocator with fence phases explicitly driven by the test. */
public final class PublicationTopologyBehaviorTest {
    private PublicationTopologyBehaviorTest() {}
    public static void run() {
        retirementsProgressIndependentlyAndRespectRevision();
        cancellationDuringStagingWaitsForRollbackFence();
        fragmentedAndSectionIdAllocation();
        System.out.println("publication topology and allocator behavior tests passed");
    }

    private static final class Buffer extends MemoryBuffer {
        int frees;
        Buffer(long bytes) { super(bytes); }
        @Override public void free() { super.free(); this.frees++; }
    }
    private static BuiltSection mesh(long key, long revision, Buffer buffer) {
        return new BuiltSection(key, revision, (byte) 0, 0, buffer, new int[8]);
    }
    private static long key(int x) { return SectionKey.pack(4, x, 0, 0); }
    private static void stage(NodeManager nodes, BuiltSection geometry) {
        check(nodes.stageGeometryResult(geometry) != null, "geometry has no hierarchy owner");
        nodes.commitStagedRoot(geometry.sourceRevision, Set.of(geometry.position));
    }

    private static final class Publication extends SectionPublicationState {
        final long key, revision, retirementRevision;
        final List<Publication> retirements;
        Publication(long key, long revision, long retirementRevision, List<Publication> retirements) {
            this.key = key; this.revision = revision; this.retirementRevision = retirementRevision;
            this.retirements = retirements;
        }
        @Override protected void requestRetirement() { this.retirements.add(this); }
        @Override protected void stateChanged() {}
    }

    private static void retirementsProgressIndependentlyAndRespectRevision() {
        BasicAsyncGeometryManager allocator = new BasicAsyncGeometryManager(16, 16 * 1024);
        NodeManager nodes = new NodeManager(1024, allocator);
        PublicationHandoff<Object> handoff = new PublicationHandoff<>();
        Object occupied = handoff.trySubmit(Object::new);
        List<Publication> retirements = new ArrayList<>();
        List<Buffer> buffers = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            long key = key(i), revision = i + 1;
            nodes.insertTopLevelNode(key);
            Buffer buffer = new Buffer(1024); buffers.add(buffer);
            stage(nodes, mesh(key, revision, buffer));
            check(nodes.finalizeStagedRoot(revision), "initial upload did not finalize");
            Publication publication = new Publication(key, revision, 100 + i, retirements);
            publication.completeUpload(new UploadOutcome(UploadStatus.ACTIVATED, null, null));
            publication.close(); publication.close();
        }
        check(retirements.size() == 4 && allocator.getSectionCount() == 4, "close duplicated or freed live state");
        Buffer newer = new Buffer(1024);
        stage(nodes, mesh(key(0), 20, newer));
        check(nodes.finalizeStagedRoot(20), "replacement did not finalize");
        for (Publication p : retirements) {
            check(nodes.retirePublication(p.retirementRevision, p.revision, p.key), "retirement blocked on handoff");
            check(allocator.getSectionCount() >= 1, "retirement freed before fence");
        }
        check(allocator.getSectionCount() == 4 && !newer.isFreed(), "staging retirement freed GPU state early");
        for (Publication p : retirements) {
            check(nodes.finalizeStagedRoot(p.retirementRevision), "fenced retirement did not finalize");
            p.markRetired();
        }
        check(allocator.getSectionCount() == 1 && !newer.isFreed(), "late old close removed newer publication");
        check(handoff.take() == occupied, "retirement consumed geometry handoff");
        for (Buffer buffer : buffers) check(buffer.frees == 1, "retirement native free count incorrect");
        nodes.removeTopLevelNode(key(0));
        check(newer.frees == 1 && allocator.getSectionCount() == 0, "replacement cleanup leaked");
    }

    private static void cancellationDuringStagingWaitsForRollbackFence() {
        BasicAsyncGeometryManager allocator = new BasicAsyncGeometryManager(8, 8192);
        NodeManager nodes = new NodeManager(1024, allocator);
        nodes.insertTopLevelNode(key(0));
        Buffer fallback = new Buffer(1024), candidate = new Buffer(1024);
        stage(nodes, mesh(key(0), 1, fallback)); nodes.finalizeStagedRoot(1);
        stage(nodes, mesh(key(0), 2, candidate));
        List<Publication> retirements = new ArrayList<>();
        Publication upload = new Publication(key(0), 2, 3, retirements);
        upload.close(); upload.close();
        check(retirements.isEmpty() && allocator.getSectionCount() == 2, "close overtook staged upload");
        nodes.rollbackStagedRoot(2);
        check(!candidate.isFreed() && !fallback.isFreed(), "rollback freed before pointer fence");
        check(!nodes.retirePublication(4, 1, key(0)), "retirement overtook rollback-owned geometry");
        nodes.completeRollback(2);
        upload.completeUpload(new UploadOutcome(UploadStatus.FAILED, null, new IllegalStateException("injected")));
        check(candidate.frees == 1 && !fallback.isFreed(), "rollback failed to retain fallback");
        nodes.removeTopLevelNode(key(0));
        check(fallback.frees == 1, "fallback leaked after shutdown");
    }

    private static void fragmentedAndSectionIdAllocation() {
        BasicAsyncGeometryManager allocator = new BasicAsyncGeometryManager(4, 4096);
        Buffer[] buffers = {new Buffer(1024), new Buffer(1024), new Buffer(1024), new Buffer(1024)};
        int[] ids = new int[4];
        for (int i = 0; i < ids.length; i++) ids[i] = allocator.uploadSection(mesh(key(i), i, buffers[i]));
        Buffer request = new Buffer(2048);
        check(allocator.tryUploadSection(mesh(key(5), 5, request)).status()
                == BasicAsyncGeometryManager.AdmissionStatus.NO_SECTION_ID, "section-ID failure was hidden");
        allocator.removeSection(ids[1]); allocator.removeSection(ids[3]);
        long released = allocator.sectionReleaseGeneration();
        check(allocator.tryUploadSection(mesh(key(5), 5, request)).status()
                == BasicAsyncGeometryManager.AdmissionStatus.NO_CONTIGUOUS_GEOMETRY_SPACE,
                "aggregate free bytes hid fragmentation");
        check(allocator.sectionReleaseGeneration() == released && !request.isFreed(),
                "failed admission changed ownership/progress");
        allocator.removeSection(ids[2]);
        int accepted = allocator.uploadSection(mesh(key(5), 5, request));
        allocator.removeSection(accepted); allocator.removeSection(ids[0]);
        check(request.frees == 1, "allocator retry consumed mesh more than once");
        for (Buffer buffer : buffers) check(buffer.frees == 1, "allocator leaked section");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
