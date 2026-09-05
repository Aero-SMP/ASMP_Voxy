package me.cortex.voxy.client.lod;

import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.VoxyRenderSystem.*;
import me.cortex.voxy.client.core.rendering.SectionKey;
import me.cortex.voxy.client.core.rendering.building.BuiltSection;
import me.cortex.voxy.client.core.rendering.hierarchical.AsyncNodeManager.PublicationProgress;
import me.cortex.voxy.client.core.rendering.hierarchical.PublicationHandoff;
import me.cortex.voxy.client.core.rendering.hierarchical.PublicationOutcome;
import me.cortex.voxy.client.core.rendering.hierarchical.SectionPublicationState;
import me.cortex.voxy.common.util.MemoryBuffer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Explicitly driven production scheduler/ownership transitions; no clocks or sleeps. */
public final class PublicationRepairBehaviorTest {
    private PublicationRepairBehaviorTest() {}

    public static void run() throws Exception {
        busyHandoffKeepsOwnershipAndDoesNotReprepare();
        returnCancellationOrderings();
        abandonedLateReturnAndWorkerReuse();
        independentlyCompletedPublicationPipelinesNextBatch();
        everyRefusalAndLostWakeOrdering();
        coveragePassesBlockedRefinement();
        waitingRepliesUseCurrentPriorityAndIdentity();
        emptyDemandStillNeedsRealSlot();
        prerequisiteReclaimsOnlyWorkerOwnedDependent();
        randomizedOwnershipInterleavings();
        repeatedCompletionIsIdempotent();
        dormancyRetirementDoesNotInvalidateBlockedSweep();
        dormancyEpochsAndQuickWake();
        measureDormancyBurstAllocation();
        System.out.println("publication ownership and priority behavior tests passed");
    }

    static final class CountedBuffer extends MemoryBuffer {
        int frees;
        CountedBuffer() { super(1024); }
        @Override public void free() { super.free(); this.frees++; }
    }

    private static long key(int x) { return SectionKey.pack(0, x, 0, 0); }
    private static BuiltSection mesh(long key, CountedBuffer buffer) {
        return new BuiltSection(key, 1, (byte) 0, 0, buffer, new int[8]);
    }

    private static final class Publication extends SectionPublicationState {
        final SectionSubmission input;
        int retirements;
        Publication(SectionSubmission input) { this.input = input; }
        @Override protected void requestRetirement() { this.retirements++; }
        @Override protected void stateChanged() {}
        void activate() {
            this.input.geometry().free(); // Explicit native upload completion.
            this.input.previous().ifPresent(previous -> ((Publication) previous).markRetired());
            this.completeUpload(new UploadOutcome(UploadStatus.ACTIVATED, null, null));
        }
        void giveBack(AllocationStatus reason, PublicationProgress observed) {
            this.completeUpload(new UploadOutcome(UploadStatus.RETURNED,
                    new AllocationBlock(this.input.geometry(), reason, 128, 0,
                            this.input.position(), observed), null));
        }
    }

    private static final class Publisher implements SectionPublisher {
        final PublicationHandoff<List<Publication>> handoff = new PublicationHandoff<>();
        final List<Publication> created = new ArrayList<>();
        int preparations, evictions;
        Runnable retirementDone;
        Consumer<Throwable> retirementFailed;
        long topology, allocation, sectionIds;
        @Override public SubmissionAttempt tryPublishBatch(List<SectionSubmission> submissions) {
            List<Publication> accepted = this.handoff.trySubmit(() -> {
                this.preparations++;
                List<Publication> batch = submissions.stream().map(Publication::new).toList();
                this.created.addAll(batch);
                return batch;
            });
            return accepted == null ? new SubmissionAttempt(SubmissionStatus.BUSY, List.of())
                    : new SubmissionAttempt(SubmissionStatus.ACCEPTED, new ArrayList<>(accepted));
        }
        @Override public PublicationProgress progress() {
            return new PublicationProgress(this.handoff.consumed(), this.topology,
                    this.allocation, this.sectionIds, this.handoff.occupied(), null);
        }
        @Override public void setProgressListener(Runnable listener) {}
        @Override public void clearProgressListener(Runnable listener) {}
        @Override public void coarsen(long parent, Runnable success, Consumer<Throwable> failure) {
            this.evictions++;
            this.retirementDone = success;
            this.retirementFailed = failure;
        }
    }

    private static ClientSession.Session session(Publisher publisher, int workers) {
        return new ClientSession.Session(70, "minecraft:overworld", null, publisher, null, workers);
    }

    private static ClientSession.Demand ready(ClientSession.Session session, long key,
                                              CountedBuffer buffer) throws Exception {
        ClientSession.Demand demand = session.demands.adopt(new ClientSession.Demand(key));
        var worker = session.idleWorker();
        check(worker != null, "no actual worker available for test operation");
        var ticket = demand.ticket(session.id, worker.index);
        demand.workLease = worker.assign(new ClientSession.Session.EmptyWorkerTask(ticket, (byte) 0));
        session.demands.owned(demand, SectionDemandTable.CandidateState.WORKER_OWNED);
        BuiltSection geometry = buffer == null ? BuiltSection.empty(key) : mesh(key, buffer);
        worker.resource.complete(demand.workLease,
                new ClientSession.Session.WorkerGeometry(ticket, geometry, 1, true, 0));
        session.drainWorkers();
        return demand;
    }

    private static void busyHandoffKeepsOwnershipAndDoesNotReprepare() throws Exception {
        Publisher p = new Publisher();
        ClientSession.Session s = session(p, 2);
        CountedBuffer first = new CountedBuffer(), second = new CountedBuffer();
        ready(s, key(1), first);
        s.scheduleReadyPublications();
        ClientSession.Demand b = ready(s, key(2), second);
        var lease = b.workLease;
        s.scheduleReadyPublications();
        for (int i = 0; i < 100; i++) s.scheduleReadyPublications();
        check(p.preparations == 1 && b.workLease.equals(lease) && b.completedGeometry != null,
                "busy handoff changed ownership or repeatedly prepared wrappers");
        check(second.frees == 0 && s.demands.readyCount(SectionDemandTable.ReadyKind.RENDERER) == 1,
                "busy handoff lost ready membership");
        List<Publication> batch = p.handoff.take();
        s.scheduleReadyPublications();
        check(p.preparations == 2, "consumption did not unblock submission");
        batch.getFirst().activate();
        p.handoff.take().getFirst().activate();
        s.pollPublications();
        s.release();
        check(first.frees == 1 && second.frees == 1, "mesh freed incorrectly");
    }

    private static void returnCancellationOrderings() throws Exception {
        for (boolean returnFirst : new boolean[]{false, true}) {
            Publisher p = new Publisher(); ClientSession.Session s = session(p, 1);
            CountedBuffer buffer = new CountedBuffer();
            ClientSession.Demand demand = ready(s, key(1), buffer);
            var lease = demand.workLease;
            s.scheduleReadyPublications();
            Publication publication = p.handoff.take().getFirst();
            if (returnFirst) publication.giveBack(AllocationStatus.NO_SECTION_ID, p.progress());
            s.retireDemand(demand.key);
            check(s.idleWorker() == null, "cancellation prematurely released renderer lease");
            if (!returnFirst) publication.giveBack(AllocationStatus.NO_SECTION_ID, p.progress());
            s.pollPublications(); s.pollPublications(); publication.close(); publication.close();
            check(buffer.frees == 1 && s.idleWorker() != null, "abandoned return leaked or double freed");
            ClientSession.Demand newer = ready(s, key(1), null);
            s.releaseRendererSlot(lease);
            check(s.sectionWorkers[0].resource.matches(newer.workLease), "old publication released new lease");
            s.release();
        }
    }

    private static void abandonedLateReturnAndWorkerReuse() throws Exception {
        Publisher p = new Publisher(); ClientSession.Session s = session(p, 1);
        CountedBuffer buffer = new CountedBuffer();
        ready(s, key(1), buffer); s.scheduleReadyPublications();
        Publication publication = p.handoff.take().getFirst();
        s.release();
        check(buffer.frees == 0, "shutdown freed renderer-owned data");
        publication.giveBack(AllocationStatus.TOPOLOGY_NOT_READY, p.progress());
        check(buffer.frees == 1, "late return after shutdown was unclaimed");

        WorkerResource<CountedBuffer> worker = new WorkerResource<>(0, CountedBuffer::free);
        var first = worker.acquire(); CountedBuffer a = new CountedBuffer();
        worker.complete(first, a); worker.claim().value().free(); worker.release(first);
        var second = worker.acquire(); CountedBuffer b = new CountedBuffer();
        worker.complete(second, b);
        check(!worker.release(first), "stale generation released replacement completion");
        check(worker.claim().value() == b, "stale generation cleared replacement value");
        b.free(); worker.release(second); worker.close();
    }

    private static void independentlyCompletedPublicationPipelinesNextBatch() throws Exception {
        Publisher p = new Publisher(); ClientSession.Session s = session(p, 2);
        ready(s, key(1), null); ready(s, key(2), null); s.scheduleReadyPublications();
        List<Publication> a = p.handoff.take();
        a.getFirst().activate(); s.pollPublications();
        ready(s, key(3), null); s.scheduleReadyPublications();
        check(p.preparations == 2 && !a.getLast().activationFencePassed(),
                "unrelated activation fence blocked next batch");
        check(s.idleWorker() == null, "handoff consumption released worker too early");
        a.getLast().activate(); p.handoff.take().getFirst().activate();
        s.pollPublications(); s.release();
    }

    private static void everyRefusalAndLostWakeOrdering() throws Exception {
        for (AllocationStatus reason : AllocationStatus.values()) {
            Publisher p = new Publisher(); ClientSession.Session s = session(p, 1);
            CountedBuffer buffer = new CountedBuffer();
            ClientSession.Demand d = ready(s, key(1), buffer);
            s.pendingDormantEvictions.put(123, 1024); // Already claimed, still fenced capacity.
            s.scheduleReadyPublications();
            Publication publication = p.handoff.take().getFirst();
            PublicationProgress before = p.progress();
            publication.giveBack(reason, before); s.pollPublications();
            for (int i = 0; i < 100; i++) { s.retryRendererBlocked(); s.scheduleReadyPublications(); }
            check(p.preparations == 1 && p.evictions == 0, "unchanged refusal retried or over-evicted: " + reason);
            if (reason == AllocationStatus.IMPOSSIBLE || reason == AllocationStatus.STALE) {
                check(buffer.frees == 1, "obsolete/impossible candidate remained allocated");
            } else {
                check(buffer.frees == 0 && d.workLease != null, "refusal lost worker-owned mesh");
            }
            p.handoff.trySubmit(List::of); p.handoff.take();
            check(!RendererWait.progressed(reason, before, p.progress()), "handoff event woke wrong reason");
            p.topology++;
            check(RendererWait.progressed(reason, before, p.progress())
                    == (reason == AllocationStatus.TOPOLOGY_NOT_READY || reason == AllocationStatus.STALE),
                    "topology event woke allocation wait");
            p.allocation++;
            if (reason == AllocationStatus.NO_SECTION_ID) {
                check(!RendererWait.progressed(reason, before, p.progress()), "byte free substituted for ID free");
            }
            p.sectionIds++;
            if (reason != AllocationStatus.IMPOSSIBLE) {
                // Both before/after registration compare the same observed failure generation.
                check(RendererWait.progressed(reason, before, p.progress()), "lost progress before registration");
                PublicationProgress registered = p.progress();
                check(!RendererWait.progressed(reason, registered, registered), "unchanged registered wait spun");
            }
            s.release();
            check(buffer.frees == 1, "refused mesh cleanup leaked");
        }
    }

    private static RegionalProtocol.RegionIndex emptyIndex() throws Exception {
        int count = 256 + 64 + 16 + 4 + 1;
        ByteBuffer bytes = ByteBuffer.allocate(36 + count * 48).order(ByteOrder.LITTLE_ENDIAN);
        bytes.put("VXYRIDX\0".getBytes(StandardCharsets.US_ASCII));
        bytes.putInt(0).putInt(0).putLong(1).putInt(0).putShort((short) 1).put((byte) 5).put((byte) 0).putInt(count);
        for (int i = 0; i < count; i++) bytes.putShort(36 + i * 48, (short) 0x8001);
        return RegionalProtocol.decodeIndex(bytes.array(), new RegionalProtocol.Fingerprint(1, 2));
    }

    private static ClientSession.Session.NetworkReply reply(ClientSession.Session s, long key, int bucket) throws Exception {
        ClientSession.Demand demand = s.demands.adopt(new ClientSession.Demand(key));
        demand.index = emptyIndex(); demand.ordinal = demand.index.ordinal(key); demand.regionGeneration = 1;
        s.demands.setPriority(demand, bucket);
        s.demands.owned(demand, SectionDemandTable.CandidateState.NETWORK_OWNED);
        var body = new ClientSession.Session.NetworkReply(s.connectionEpoch,
                new RegionalProtocol.SectionReply(1, demand.ordinal, key, RegionalProtocol.Status.EMPTY, new byte[0]),
                demand.ticket(s.id, -1));
        s.networkReplies.add(body);
        return body;
    }

    private static void coveragePassesBlockedRefinement() throws Exception {
        Publisher p = new Publisher(); ClientSession.Session s = session(p, 1);
        CountedBuffer buffer = new CountedBuffer();
        ready(s, key(3), buffer);
        var refinement = reply(s, key(1), 15);
        long coverageKey = SectionKey.pack(4, 0, 0, 0);
        var coverage = reply(s, coverageKey, 0);
        s.drainNetworkReplies();
        check(coverage.released.get() && !refinement.released.get(), "coverage waited behind refinement");
        check(buffer.frees == 1, "coverage did not reclaim eligible worker completion exactly once");
        check(s.demands.get(coverageKey).workLease != null, "coverage has no operation lease");
        s.release();
    }

    private static void waitingRepliesUseCurrentPriorityAndIdentity() throws Exception {
        Publisher p = new Publisher(); ClientSession.Session s = session(p, 1);
        var first = reply(s, key(1), 12); var second = reply(s, key(2), 1);
        s.demands.setPriority(s.demands.get(key(2)), 15);
        s.drainNetworkReplies();
        check(second.released.get() && !first.released.get(), "reply used send-time priority");
        s.demands.revise(s.demands.get(key(1)));
        s.drainNetworkReplies(); s.drainNetworkReplies();
        check(first.transferred.availablePermits() == 1, "stale lane released more than once");
        s.release();
    }

    private static void emptyDemandStillNeedsRealSlot() throws Exception {
        Publisher p = new Publisher(); ClientSession.Session s = session(p, 2);
        var index = emptyIndex();
        for (int x = 0; x < 100; x++) {
            ClientSession.Demand d = s.demands.adopt(new ClientSession.Demand(key(x)));
            d.index = index; d.ordinal = 0; s.queueBound(d);
        }
        s.scheduleSourceWork();
        check(s.idleWorker() == null && s.demands.readyCount(SectionDemandTable.ReadyKind.SOURCE) == 98,
                "empty sections bypassed actual worker ownership");
        check(s.publicationQueue.isEmpty(), "empty sections created an unlimited publication tracker");
        s.release();
    }

    private static void prerequisiteReclaimsOnlyWorkerOwnedDependent() throws Exception {
        Publisher p = new Publisher(); ClientSession.Session s = session(p, 1);
        CountedBuffer buffer = new CountedBuffer();
        ClientSession.Demand dependent = ready(s, key(1), buffer);
        long parent = SectionKey.pack(1, 0, 0, 0);
        dependent.blockedReason = AllocationStatus.TOPOLOGY_NOT_READY;
        dependent.prerequisite = parent;
        ClientSession.Demand prerequisite = s.demands.adopt(new ClientSession.Demand(parent));
        check(s.idleWorker(prerequisite) != null && buffer.frees == 1 && p.evictions == 0,
                "prerequisite did not reclaim dependent CPU mesh safely");
        s.release();
    }

    private static void randomizedOwnershipInterleavings() {
        for (long seed = 0; seed < 128; seed++) {
            try {
                Random random = new Random(seed);
                for (int i = 0; i < 50; i++) {
                    CountedBuffer buffer = new CountedBuffer();
                    Publication p = new Publication(new SectionSubmission(key(1), mesh(key(1), buffer),
                            false, 1, Optional.empty(), () -> true, () -> {}));
                    AtomicInteger resolved = new AtomicInteger();
                    boolean abandonFirst = random.nextBoolean();
                    if (abandonFirst) p.abandon(resolved::incrementAndGet);
                    if (random.nextBoolean()) p.activate();
                    else p.giveBack(AllocationStatus.NO_SECTION_ID,
                            new PublicationProgress(0, 0, 0, 0, false, null));
                    if (!abandonFirst) p.abandon(resolved::incrementAndGet);
                    p.abandon(resolved::incrementAndGet); p.close(); p.close();
                    check(resolved.get() == 1 && buffer.frees == 1 && p.retirements <= 1,
                            "randomized ownership invariant failed");
                }
            } catch (Throwable failure) { throw new AssertionError("failing publication seed=" + seed, failure); }
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void repeatedCompletionIsIdempotent() {
        for (boolean abandonFirst : new boolean[]{false, true}) {
            CountedBuffer buffer = new CountedBuffer();
            PublicationOutcome<CountedBuffer> outcome = new PublicationOutcome<>(CountedBuffer::free);
            AtomicInteger resolved = new AtomicInteger();
            if (abandonFirst) outcome.abandon(resolved::incrementAndGet);
            outcome.complete(buffer);
            outcome.complete(buffer);
            outcome.abandon(resolved::incrementAndGet);
            outcome.complete(buffer);
            check(buffer.frees == 1 && resolved.get() == 1 && outcome.claim() == null,
                    "duplicate completion changed terminal ownership");
        }
    }

    private static ClientSession.Demand installed(ClientSession.Session s, long key) {
        s.addDemand(key);
        var d = s.demands.get(key);
        Publication publication = new Publication(new SectionSubmission(key, BuiltSection.empty(key),
                false, 1, Optional.empty(), () -> true, () -> {}));
        publication.activate();
        d.publication = publication;
        d.installed = true;
        s.activeCount++;
        s.setActiveGeometryBytes(d, 1024);
        return d;
    }

    private static CountedBuffer replacement(ClientSession.Session s, ClientSession.Demand d)
            throws Exception {
        var worker = s.idleWorker();
        var ticket = d.ticket(s.id, worker.index);
        d.workLease = worker.assign(new ClientSession.Session.EmptyWorkerTask(ticket, (byte) 0));
        s.demands.owned(d, SectionDemandTable.CandidateState.WORKER_OWNED);
        CountedBuffer buffer = new CountedBuffer();
        worker.resource.complete(d.workLease, new ClientSession.Session.WorkerGeometry(ticket,
                mesh(d.key, buffer), 1, true, 0));
        s.drainWorkers();
        return buffer;
    }

    private static void dormancyRetirementDoesNotInvalidateBlockedSweep() throws Exception {
        for (AllocationStatus reason : List.of(AllocationStatus.NO_CONTIGUOUS_GEOMETRY_SPACE,
                AllocationStatus.NO_SECTION_ID)) for (boolean success : new boolean[]{false, true}) {
            Publisher p = new Publisher(); ClientSession.Session s = session(p, 3);
            long parentKey = SectionKey.pack(1, 0, 0, 0);
            var parent = installed(s, parentKey);
            var fallback = parent.publication;
            var a = installed(s, key(0)); var b = installed(s, key(1));
            var unrelated = installed(s, key(8));
            CountedBuffer first = replacement(s, a), second = replacement(s, b);
            CountedBuffer third = replacement(s, unrelated);
            s.scheduleReadyPublications();
            for (Publication publication : p.handoff.take()) publication.giveBack(reason, p.progress());
            s.pollPublications();
            check(s.rendererBlocked.size() == 3 && p.evictions == 0, "pressure setup did not block");
            s.markDormant(parentKey, 0, 1); // Removes two entries while walking rendererBlocked.
            check(p.evictions == 1 && s.coarseningRoots.equals(Set.of(parentKey)),
                    "dormancy did not schedule exactly one subtree retirement");
            check(s.demands.get(a.key) == null && s.demands.get(b.key) == null
                    && s.rendererBlocked.equals(Set.of(unrelated.key)), "removed demand membership survived");
            check(s.demands.readyCount(SectionDemandTable.ReadyKind.RENDERER) == 0
                    && first.frees == 1 && second.frees == 1 && third.frees == 0,
                    "retirement leaked/doubled a replacement buffer or ready membership");
            long occupied = Arrays.stream(s.sectionWorkers).filter(w -> !w.idle()).count();
            check(occupied == 1 && parent.publication == fallback && fallback.activationFencePassed()
                    && !fallback.retirementFencePassed(), "coarse fallback or operation lease lost");
            check(s.pendingDormantEvictionBytes == 2048 && s.dormantBytesFreedAfterFences == 0,
                    "pending physical bytes became free before a fence");
            s.markDormant(parentKey, 0, 1); s.markDormant(parentKey, 0, 0);
            s.wakeDormant(parentKey, 2);
            check(p.evictions == 1, "duplicate/stale dormancy or quick wake repeated retirement");
            if (success) {
                p.allocation++; p.sectionIds++; p.retirementDone.run();
            } else p.retirementFailed.accept(new IllegalStateException("injected retirement failure"));
            s.drainEvents();
            check(s.pendingDormantEvictionBytes == 0 && s.coarseningRoots.isEmpty()
                    && s.dormantBytesFreedAfterFences == (success ? 2048 : 0), "fence accounting incorrect");
            if (!success) {
                check(s.rendererBlocked.contains(unrelated.key), "failure falsely released physical capacity");
                p.allocation++; p.sectionIds++; s.retryRendererBlocked();
            }
            check(!s.rendererBlocked.contains(unrelated.key)
                    && s.demands.readyCount(SectionDemandTable.ReadyKind.RENDERER) == 1,
                    "unrelated blocked request lost its progress wake");
            s.release();
            check(first.frees == 1 && second.frees == 1 && third.frees == 1, "shutdown double free");
        }
    }

    private static void measureDormancyBurstAllocation() {
        var bean = java.lang.management.ManagementFactory.getThreadMXBean();
        if (!(bean instanceof com.sun.management.ThreadMXBean allocation) || !allocation.isThreadAllocatedMemorySupported()) return;
        allocation.setThreadAllocatedMemoryEnabled(true);
        long thread = Thread.currentThread().threadId();
        for (int blocked : new int[]{0, 256}) {
            Publisher publisher = new Publisher();
            ClientSession.Session s = session(publisher, 1);
            long parent = SectionKey.pack(1, 0, 0, 0);
            installed(s, parent); installed(s, key(0));
            // The benchmark measures the real event sweep; the pressure correctness test above
            // uses actual refused publications and leases. Topology waits do not evict here.
            for (int i = 0; i < blocked; i++) {
                long key = key(i + 32);
                var waiting = s.demands.adopt(new ClientSession.Demand(key));
                waiting.blockedReason = AllocationStatus.TOPOLOGY_NOT_READY;
                s.rendererBlocked.add(key);
            }
            for (int epoch = 1; epoch <= 100; epoch++) s.markDormant(parent, 1, epoch);
            long before = allocation.getThreadAllocatedBytes(thread);
            long start = System.nanoTime();
            for (int epoch = 101; epoch <= 1100; epoch++) s.markDormant(parent, 1, epoch);
            long elapsed = System.nanoTime() - start;
            long bytes = allocation.getThreadAllocatedBytes(thread) - before;
            System.out.println("dormancy burst: events=1000 blocked=" + blocked + " allocatedBytes="
                    + bytes + " elapsedNanos=" + elapsed);
            check(publisher.evictions == 0, "benchmark topology waits evicted terrain");
            s.release();
        }
    }

    private static void dormancyEpochsAndQuickWake() {
        Publisher p = new Publisher(); ClientSession.Session s = session(p, 1);
        long key = SectionKey.pack(1, 0, 0, 0);
        var parent = installed(s, key); installed(s, key(0));
        s.markDormant(key, 1, 1); s.markDormant(key, 1, 1); s.markDormant(key, 1, 0);
        check(s.dormancyTransitions == 1 && s.dormantGeometryBytes == 1024, "empty blocked sweep/epochs");
        s.wakeDormant(key, 2);
        check(!parent.dormant && s.dormantGeometryBytes == 0 && s.instantWakes == 1,
                "quick return did not retain installed descendants");
        // One blocked entry is also safe; it need not initiate an eviction for a topology wait.
        parent.blockedReason = AllocationStatus.TOPOLOGY_NOT_READY;
        s.rendererBlocked.add(key);
        s.markDormant(key, 1, 3);
        check(p.evictions == 0 && s.rendererBlocked.contains(key), "topology wait caused unrelated eviction");
        s.release();
    }
}
