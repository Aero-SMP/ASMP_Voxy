package me.cortex.voxy.client.core.rendering.hierarchical;

import me.cortex.voxy.client.core.VoxyRenderSystem.*;
import me.cortex.voxy.client.core.rendering.SectionKey;
import me.cortex.voxy.client.core.rendering.building.BuiltSection;
import me.cortex.voxy.client.core.rendering.section.BasicAsyncGeometryManager;
import me.cortex.voxy.common.util.MemoryBuffer;
import sun.misc.Unsafe;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.*;

/** Runs the real AsyncNodeManager admission consumer, NodeManager and allocator.
 * Only GL construction and fence advancement are excluded from this headless fixture. */
final class RendererAdmissionBehaviorTest {
    static void run() {
        admissionOrderAndFailures();
        stalledUploadsAndFences();
        System.out.println("real renderer admission ordering and bounded staging tests passed");
    }

    private static final class Buffer extends MemoryBuffer {
        int frees;
        Buffer(long bytes) { super(bytes); }
        @Override public void free() { super.free(); frees++; }
    }

    private static final class State extends SectionPublicationState {
        int wakes;
        Runnable changed = () -> {};
        @Override protected void requestRetirement() {}
        @Override protected void stateChanged() {
            check(!Thread.holdsLock(this), "admission notification retained publication monitor");
            wakes++;
            changed.run();
        }
    }

    private static final class Nodes extends NodeManager {
        int failAt;
        Runnable afterCommit = () -> {};
        Nodes(BasicAsyncGeometryManager allocator) { super(4096, allocator); }
        @Override RendererFence stageGeometryResult(BuiltSection section) {
            if (failAt == 1) throw new IllegalStateException("injected stage failure");
            return super.stageGeometryResult(section);
        }
        @Override public void commitStagedRoot(long revision, Set<Long> positions) {
            if (failAt == 2) throw new IllegalStateException("injected commit preflight failure");
            super.commitStagedRoot(revision, positions);
            afterCommit.run();
            if (failAt == 3) throw new IllegalStateException("injected post-commit failure");
        }
    }

    private static final class Attempt {
        final State state = new State();
        final BuiltSection mesh;
        Object publication;
        AsyncNodeManager.RegionalAllocationBlock blocked;
        Throwable failure;
        Attempt(ConsumerHarness h, BuiltSection mesh) {
            this.mesh = mesh;
            Class<?> type = nested("RegionalSectionPublication");
            var timing = new AsyncNodeManager.RegionalPublicationTiming() {
                public void recordRendererQueued(long time) {}
                public void recordGpuUploadSubmitted(long time) {}
            };
            this.publication = construct(type, mesh, -1L,
                    (BooleanSupplier) state::acceptsUpload,
                    (Runnable) () -> {
                        check(h.completed.contains(this.publication), "acknowledged before completion registration");
                        this.state.markRendererAdmitted();
                    }, timing, (Runnable) () -> {},
                    (Runnable) () -> state.completeUpload(new UploadOutcome(UploadStatus.CANCELLED, null, null)),
                    (Consumer<AsyncNodeManager.RegionalAllocationBlock>) value -> blocked = value,
                    (Consumer<Throwable>) value -> failure = value);
        }
    }

    private static final class ConsumerHarness implements AutoCloseable {
        final BasicAsyncGeometryManager allocator;
        final Nodes nodes;
        final AsyncNodeManager consumer;
        final ArrayList<Object> completed = new ArrayList<>();
        final List<Long> roots = new ArrayList<>();
        final List<Attempt> attempts = new ArrayList<>();
        ConsumerHarness(int sections, long bytes) {
            this.allocator = new BasicAsyncGeometryManager(sections, bytes);
            this.nodes = new Nodes(allocator);
            // Avoid executing GL shader field initializers. The consumer method below is
            // invoked unchanged, using its actual CPU dependencies and completion list.
            try {
                Field singleton = Unsafe.class.getDeclaredField("theUnsafe"); singleton.setAccessible(true);
                this.consumer = (AsyncNodeManager) ((Unsafe) singleton.get(null)).allocateInstance(AsyncNodeManager.class);
                set(consumer, "manager", nodes); set(consumer, "geometryManager", allocator);
                set(consumer, "regionalBatchHandoff", new PublicationHandoff<>());
                set(consumer, "topologyGeneration", new AtomicLong());
                set(consumer, "completedRegionalSectionPublications", completed);
                set(consumer, "running", true);
            } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
        }
        Attempt prepare(int x, long revision, long bytes) {
            long key = SectionKey.pack(4, x, 0, 0);
            if (!roots.contains(key)) { nodes.insertTopLevelNode(key); roots.add(key); }
            Attempt attempt = new Attempt(this, new BuiltSection(key, revision, (byte) 0,
                    0, bytes == 0 ? null : new Buffer(bytes), new int[8]));
            attempts.add(attempt);
            return attempt;
        }
        boolean room(Attempt attempt) {
            return (boolean) invoke(consumer, "regionalSyncBatchHasRoom", attempt.publication);
        }
        void consume(Attempt attempt) { invoke(consumer, "processRegionalSectionPublication", attempt.publication); }
        @Override public void close() {
            // Fence phases explicitly advanced only during cleanup.
            for (Attempt attempt : attempts) {
                nodes.rollbackStagedRoot(attempt.mesh.sourceRevision);
                nodes.completeRollback(attempt.mesh.sourceRevision);
            }
            roots.forEach(nodes::removeTopLevelNode);
            for (Attempt attempt : attempts) {
                MemoryBuffer buffer = attempt.mesh.geometryBuffer;
                if (buffer != null && !buffer.isFreed()) buffer.free(); // Refused/unsubmitted input remains caller-owned.
                if (buffer != null) check(((Buffer) buffer).frees == 1, "geometry disposed more than once");
            }
            check(allocator.getSectionCount() == 0 && allocator.getPendingUploadBytes() == 0,
                    "renderer fixture leaked allocations/native buffers");
        }
    }

    private static void admissionOrderAndFailures() {
        for (int failAt = 0; failAt <= 3; failAt++) {
            try (ConsumerHarness h = new ConsumerHarness(8, 8192)) {
                Attempt attempt = h.prepare(0, 1, 1024);
                h.nodes.failAt = failAt;
                h.consume(attempt);
                check(attempt.state.rendererAdmitted() == (failAt == 0), "false/missing commit acknowledgement");
                check(!attempt.state.activationFencePassed(), "admission activated geometry");
                check(h.completed.size() == (failAt == 0 ? 1 : 0), "failed publication registered for success");
                check((attempt.failure == null) == (failAt == 0), "injected failure was swallowed");
                if (failAt == 3) {
                    check(h.allocator.getSectionCount() == 1 && !attempt.mesh.geometryBuffer.isFreed(),
                            "post-commit rollback skipped retirement fence");
                    h.nodes.completeRollback(1);
                }
                if (failAt != 0) {
                    h.nodes.failAt = 0;
                    Attempt retry = h.prepare(0, 2, 1024); h.consume(retry);
                    check(retry.state.rendererAdmitted(), "failure leaked allocation or hierarchy owner");
                }
            }
        }
        try (ConsumerHarness h = new ConsumerHarness(8, 8192)) {
            Attempt attempt = h.prepare(0, 1, 1024);
            h.nodes.afterCommit = attempt.state::close;
            h.consume(attempt);
            check(attempt.state.rendererAdmitted() && !attempt.state.acceptsUpload()
                    && !attempt.state.activationFencePassed(), "cancellation between commit/ack erased ownership");
        }
        try (ConsumerHarness h = new ConsumerHarness(8, 8192)) {
            Attempt attempt = h.prepare(0, 1, 1024);
            attempt.state.changed = () -> { throw new IllegalStateException("injected notification failure"); };
            h.consume(attempt);
            check(attempt.state.rendererAdmitted() && attempt.failure != null && h.completed.isEmpty(),
                    "failure after acknowledgement retained a success completion");
            check(h.allocator.getSectionCount() == 1 && !attempt.mesh.geometryBuffer.isFreed(),
                    "failure after admission freed before rollback fence");
            h.nodes.completeRollback(1);
            check(h.allocator.getSectionCount() == 0, "admitted notification failure leaked allocation");
        }
        try (ConsumerHarness h = new ConsumerHarness(1, 1024)) {
            Attempt a = h.prepare(0, 1, 1024); h.consume(a);
            Attempt b = h.prepare(1, 2, 1024); h.consume(b);
            check(!b.state.rendererAdmitted() && b.blocked != null
                    && b.blocked.status() == AsyncNodeManager.RegionalAllocationStatus.NO_SECTION_ID
                    && !b.mesh.geometryBuffer.isFreed(), "section-id refusal transferred ownership");
        }
        try (ConsumerHarness h = new ConsumerHarness(8, 1024)) {
            Attempt a = h.prepare(0, 1, 1024); h.consume(a);
            Attempt b = h.prepare(1, 2, 1024); h.consume(b);
            check(!b.state.rendererAdmitted() && b.blocked != null
                    && b.blocked.status() == AsyncNodeManager.RegionalAllocationStatus.NO_CONTIGUOUS_GEOMETRY_SPACE,
                    "space refusal acknowledged admission");
            Attempt c = h.prepare(0, 3, 1024); h.consume(c);
            check(!c.state.rendererAdmitted() && c.blocked != null
                    && c.blocked.status() == AsyncNodeManager.RegionalAllocationStatus.TOPOLOGY_NOT_READY,
                    "pending topology acknowledged admission");
        }
    }

    private static void stalledUploadsAndFences() {
        // Raw native data stays in heapUploads while rendering is paused; the existing
        // 16 MiB sync gate admits two 8 MiB sections, then stops the batch.
        long mib = 1L << 20;
        try (ConsumerHarness h = new ConsumerHarness(16, 64 * mib)) {
            Attempt a = h.prepare(0, 1, 8 * mib), b = h.prepare(1, 2, 8 * mib);
            Attempt c = h.prepare(2, 3, 8 * mib);
            check(h.room(a), "first upload refused by sync gate"); h.consume(a);
            check(h.room(b), "second upload refused by sync gate"); h.consume(b);
            check(!h.room(c) && h.allocator.getPendingUploadBytes() == 16 * mib,
                    "paused rendering bypassed native staging gate");
            try (Scratch scratch = new Scratch()) {
                // Copy using the actual SyncResults copy implementation, then hold all
                // activation fences while continuing to drain upload batches.
                scratch.drain(h.allocator);
                long peakScratch = scratch.capacity();
                check(h.allocator.getPendingUploadBytes() == 0 && h.allocator.getSectionCount() == 2,
                        "draining CPU copies released GPU reservations before their fences");
                h.completed.clear(); scratch.reset();
                h.consume(c);
                for (int x = 3; x < 8; x++) {
                    Attempt next = h.prepare(x, x + 1, 8 * mib);
                    if (!h.room(next)) {
                        scratch.drain(h.allocator); h.completed.clear(); scratch.reset();
                    }
                    h.consume(next);
                }
                scratch.drain(h.allocator); peakScratch = Math.max(peakScratch, scratch.capacity());
                Attempt full = h.prepare(8, 9, 8 * mib); h.consume(full);
                check(full.blocked != null && !full.state.rendererAdmitted()
                        && h.allocator.getGeometryUsedBytes() == 64 * mib
                        && h.allocator.getSectionCount() == 8, "stalled fences did not stop at real capacity");
                check(peakScratch == 16 * mib + (1 << 16), "exact-fit reused scratch buffer expanded unnecessarily");
                System.out.println("admission pressure: gpuReserved=67108864 sections=8 rawBatchPeak=16777216"
                        + " scratchCapacityPeak=" + peakScratch + " refusedWorkerMesh=8388608");
            }
        }
        // Tiny meshes still consume one actual aligned allocation and one section ID.
        try (ConsumerHarness h = new ConsumerHarness(8, 8192)) {
            for (int x = 0; x < 8; x++) h.consume(h.prepare(x, x + 1, 8));
            Attempt full = h.prepare(8, 9, 8); h.consume(full);
            check(h.allocator.getGeometryUsedBytes() == 8192 && h.allocator.getPendingUploadBytes() == 64
                    && h.completed.size() == 8 && full.blocked != null && !full.state.rendererAdmitted(),
                    "tiny meshes created uncharged/unbounded pending publications");
        }
    }

    private static final class Scratch implements AutoCloseable {
        final Object value = construct(nested("ComputeMemoryCopy"));
        void drain(BasicAsyncGeometryManager allocator) {
            for (var entry : allocator.getUploads().int2ObjectEntrySet()) {
                invoke(value, "upload", entry.getIntKey(), entry.getValue()); entry.getValue().free();
            }
            allocator.getUploads().clear(); allocator.uploadsDrained();
        }
        void reset() { invoke(value, "reset"); }
        long capacity() {
            return ((MemoryBuffer) get(value, "scratchDataBuffer")).size
                    + ((MemoryBuffer) get(value, "scratchHeaderBuffer")).size;
        }
        public void close() { invoke(value, "free"); }
    }

    private static Class<?> nested(String name) {
        return Arrays.stream(AsyncNodeManager.class.getDeclaredClasses())
                .filter(type -> type.getSimpleName().equals(name)).findFirst().orElseThrow();
    }
    private static Object construct(Class<?> type, Object... args) {
        try {
            Constructor<?> constructor = type.getDeclaredConstructors()[0]; constructor.setAccessible(true);
            return constructor.newInstance(args);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    private static Object invoke(Object target, String name, Object... args) {
        try {
            Method method = Arrays.stream(target.getClass().getDeclaredMethods())
                    .filter(m -> m.getName().equals(name) && m.getParameterCount() == args.length).findFirst().orElseThrow();
            method.setAccessible(true); return method.invoke(target, args);
        } catch (InvocationTargetException e) { throw new AssertionError(e.getCause()); }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    private static void set(Object target, String name, Object value) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value);
    }
    private static Object get(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
