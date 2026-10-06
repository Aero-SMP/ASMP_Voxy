package me.cortex.voxy.client.lod;

import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.VoxyRenderSystem.*;
import me.cortex.voxy.client.core.rendering.SectionKey;
import me.cortex.voxy.client.core.rendering.building.BuiltSection;
import me.cortex.voxy.client.core.rendering.hierarchical.AsyncNodeManager.PublicationProgress;
import me.cortex.voxy.client.core.rendering.hierarchical.HierarchicalOcclusionTraverser;
import me.cortex.voxy.client.core.rendering.hierarchical.SectionPublicationState;
import me.cortex.voxy.common.util.MemoryBuffer;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Drives the production owner without workers, networking or GL initialization.
 * Faulting collections inject failures at existing owner boundaries, rather than copying
 * the topology, detail drain, publication scan or worker ownership algorithms. */
public final class OwnerEfficiencyBehaviorTest {
    private static final int REFINE = HierarchicalOcclusionTraverser.ACTION_REFINE;
    private static final int DORMANT = HierarchicalOcclusionTraverser.ACTION_DORMANT;
    private static final int WAKE = HierarchicalOcclusionTraverser.ACTION_WAKE;
    private static long fixtureIds;

    public static void main(String[] args) throws Exception {
        topologyTransitions();
        detailOrderAndRetries();
        detailFailuresClearLocalBuckets();
        publicationScanChanges();
        publicationOutcomesAndLeases();
        preservationInvalidationAndShutdown();
        sessionBatchScopeAndPrefixWake();
        System.out.println("production cache-loading owner efficiency lifecycle tests passed");
    }

    private static long key(int level, int x) { return SectionKey.pack(level, x, 0, 0); }
    private static long child(long parent, int child) {
        return SectionKey.pack(SectionKey.level(parent) - 1,
                SectionKey.x(parent) * 2 + (child & 1), child >>> 2 & 1, child >>> 1 & 1);
    }
    private static LocalSection empty(long key, int children) {
        return new LocalSection(key, LocalSection.EMPTY, children, 0, 0, 0,
                RegionalProtocol.Fingerprint.ZERO, RegionalProtocol.Hash32.ZERO);
    }

    private static final class Buffer extends MemoryBuffer {
        int frees;
        Buffer() { super(64); }
        @Override public void free() { super.free(); this.frees++; }
    }

    private static final class Publication extends SectionPublicationState {
        final Publisher publisher;
        int inspections, admissionQueries, changes, retirements, abandonments;
        Runnable inspect = () -> {};
        Publication(Publisher publisher) { this.publisher = publisher; }
        @Override public boolean rendererAdmitted() { this.admissionQueries++; return super.rendererAdmitted(); }
        @Override public Optional<UploadOutcome> takeUploadOutcome() {
            this.inspections++; this.inspect.run(); return super.takeUploadOutcome();
        }
        @Override protected void requestRetirement() {
            check(!Thread.holdsLock(this), "retirement retained publication monitor"); this.retirements++;
        }
        @Override protected void stateChanged() {
            check(!Thread.holdsLock(this), "progress retained publication monitor"); this.changes++;
            if (this.publisher.listener != null) this.publisher.listener.run();
        }
        @Override public void abandon(Runnable resolved) {
            this.abandonments++; super.abandon(resolved);
        }
    }

    private static final class Publisher implements SectionPublisher {
        PublicationProgress progress = new PublicationProgress(0, 0, 0, 0, false, null);
        Runnable listener;
        Consumer<List<Publication>> beforeReturn = ignored -> {};
        Runnable coarsening = () -> {};
        final List<Publication> handles = new ArrayList<>();
        int removals;
        @Override public SubmissionAttempt tryPublishBatch(List<SectionSubmission> submissions) {
            List<Publication> publications = new ArrayList<>();
            for (var submission : submissions) {
                check(submission.current().getAsBoolean(), "owner submitted an obsolete candidate");
                var publication = new Publication(this); publications.add(publication); this.handles.add(publication);
            }
            this.beforeReturn.accept(publications);
            return new SubmissionAttempt(SubmissionStatus.ACCEPTED, new ArrayList<>(publications));
        }
        @Override public PublicationProgress progress() { return this.progress; }
        @Override public void setProgressListener(Runnable listener) { this.listener = listener; }
        @Override public void clearProgressListener(Runnable listener) {
            check(this.listener == listener, "owner unregistered a different renderer callback");
            this.listener = null; this.removals++;
        }
        @Override public void coarsen(long parent, Runnable success, Consumer<Throwable> failure) {
            this.coarsening.run(); success.run();
        }
        Publication last() { return this.handles.get(this.handles.size() - 1); }
    }

    private static final class Fixture implements AutoCloseable {
        final Publisher publisher = new Publisher();
        final ClientSession.Session session;
        final List<Buffer> buffers = new ArrayList<>();
        final Object previousRenderer;
        final LinkedHashSet<Long> previousRoots;
        boolean released;
        @SuppressWarnings("unchecked") Fixture() {
            this.previousRenderer = field(ClientSession.class, "topRenderer");
            this.previousRoots = new LinkedHashSet<>((Set<Long>) field(ClientSession.class, "TOP_LEVEL"));
            VoxyRenderSystem renderer = allocate(VoxyRenderSystem.class);
            ClientSession.attachRenderer(renderer);
            this.session = new ClientSession.Session(++fixtureIds, "fixture:owner", renderer,
                    this.publisher, null, 2);
            this.publisher.setProgressListener(this.session.rendererWake);
        }
        ClientSession.Demand demand(long key, int children) {
            if (SectionKey.level(key) == SectionKey.MAX_LOD_LAYER)
                ClientSession.sectionEntered(this.session.renderer, key);
            this.session.addDemand(key, 0);
            var demand = this.session.demands.get(key);
            demand.content = empty(key, children); demand.activeContent = demand.content;
            demand.candidate = SectionDemandTable.CandidateState.NONE;
            this.session.demands.unlinkReady(demand);
            if (!demand.installed) { demand.installed = true; this.session.activeCount++; }
            return demand;
        }
        Publication submit(ClientSession.Demand demand, boolean nonempty, boolean save) throws Exception {
            int slot = this.publisher.handles.size() % this.session.sectionWorkers.length;
            var worker = this.session.sectionWorkers[slot];
            var lease = worker.resource.acquire(); check(lease != null, "fixture exhausted a worker slot");
            if (save) worker.resource.retainSave(lease);
            demand.workLease = lease;
            demand.candidate = SectionDemandTable.CandidateState.WORKER_OWNED;
            BuiltSection mesh;
            if (nonempty) {
                Buffer buffer = new Buffer(); this.buffers.add(buffer);
                mesh = new BuiltSection(demand.key, demand.revision, (byte) demand.content.children(),
                        0, buffer, new int[8]);
            } else mesh = BuiltSection.emptyWithChildren(demand.key, demand.revision, (byte) demand.content.children());
            worker.resource.complete(lease, new ClientSession.Session.WorkerGeometry(
                    demand.ticket(this.session.id, slot), mesh, 0, true, 0));
            this.session.drainWorkers();
            this.session.scheduleReadyPublications();
            check(demand.candidate == SectionDemandTable.CandidateState.RENDERER_OWNED,
                    "real publication scheduling did not transfer the candidate");
            return this.publisher.last();
        }
        void release() {
            if (this.released) return;
            this.released = true; this.session.open.set(false); this.session.release();
        }
        @SuppressWarnings("unchecked") @Override public void close() {
            try { this.release(); }
            finally {
                for (Buffer buffer : this.buffers) {
                    // The fake publisher has no GPU. Activated/rolled-back renderer-owned
                    // buffers are explicitly reclaimed by this renderer fixture only.
                    if (!buffer.isFreed()) buffer.free();
                    check(buffer.frees == 1, "fixture geometry was leaked or freed twice");
                }
                synchronized (field(ClientSession.class, "LIFECYCLE")) {
                    Set<Long> roots = (Set<Long>) field(ClientSession.class, "TOP_LEVEL");
                    roots.clear(); roots.addAll(this.previousRoots);
                    set(ClientSession.class, "topRenderer", this.previousRenderer);
                }
            }
        }
    }

    private static void topologyTransitions() {
        try (Fixture f = new Fixture()) {
            var s = f.session;
            var parent = f.demand(key(1, 0), 3);
            check(s.addChildren(parent.key, 3), "first expansion failed");
            check(parent.childrenRequired && s.demands.containsKey(child(parent.key, 0))
                    && s.demands.containsKey(child(parent.key, 1)), "required children were not adopted");
            var leaf = s.demands.get(child(parent.key, 0));
            leaf.content = empty(leaf.key, 0);
            invoke(s, "activated", leaf, leaf.content, 0L, false);
            check(s.emptyTopologyDependents.get(parent.key).contains(leaf.key), "empty terrain lost its topology watch");
            s.interestChanges.clear();
            check(s.addChildren(parent.key, 27), "identical expansion failed");
            check(!s.interestChanges.contains(leaf.key), "unchanged topology repeated dependent fanout");
            check(s.demands.get(child(parent.key, 1)).pixelBucket == 27,
                    "suppressed fanout also suppressed genuine priority updates");
            s.retireDetailDemand(child(parent.key, 1)); s.interestChanges.clear();
            check(parent.childrenRequired && s.addChildren(parent.key, 29), "required child repair failed");
            check(s.demands.containsKey(child(parent.key, 1)) && s.interestChanges.contains(leaf.key),
                    "repair with an already-required parent did not notify empty watchers");
            s.interestChanges.clear();
            invoke(s, "activated", parent, empty(parent.key, 7), 0L, false);
            check(s.interestChanges.contains(leaf.key) && s.demands.containsKey(child(parent.key, 2)),
                    "active child-mask change lost notification or adoption");
            s.interestChanges.clear();
            int[] coarseningNotices = {0};
            s.emptyTopologyDependents.put(parent.key, observedWatch(leaf.key, () -> {
                if (!parent.childrenRequired && s.demands.containsKey(leaf.key)) coarseningNotices[0]++;
            }));
            check(s.coarsen(parent.key) > 0 && !parent.childrenRequired && coarseningNotices[0] > 0,
                    "coarsening suppressed the required topology transition");
            s.resetRequested.set(true); s.drainDemand();
            check(s.demands.isEmpty() && s.emptyTopologyDependents.isEmpty(), "reset retained topology ownership");
        }
    }

    private static LinkedHashSet<Long> observedWatch(long watched, Runnable observed) {
        return new LinkedHashSet<>(Set.of(watched)) {
            @Override public Iterator<Long> iterator() { observed.run(); return super.iterator(); }
        };
    }

    private static final class ObservedDeque extends ArrayDeque<Object> {
        int additions, clears;
        @Override public void addLast(Object event) { this.additions++; super.addLast(event); }
        @Override public void clear() { this.clears++; super.clear(); }
    }

    private static void detailOrderAndRetries() {
        try (Fixture f = new Fixture()) {
            var s = f.session;
            var low = f.demand(key(1, 0), 1); var middleA = f.demand(key(1, 1), 1);
            var middleB = f.demand(key(1, 2), 1); var high = f.demand(key(1, 3), 1);
            var sleeping = f.demand(key(0, 20), 0); var waking = f.demand(key(0, 21), 0);
            sleeping.publication = new Publication(f.publisher); waking.publication = new Publication(f.publisher);
            waking.dormant = true;
            List<Long> order = new ArrayList<>();
            for (var demand : List.of(low, middleA, middleB, high))
                s.emptyTopologyDependents.put(demand.key, observedWatch(key(0, 30), () -> {
                    check(sleeping.latestDormancyEpoch == 2 && waking.latestDormancyEpoch == 3,
                            "refinement ran before dormancy/wake processing"); order.add(demand.key);
                }));
            mailbox(s, new LinkedHashMap<>());
            s.demands.offerDetail(low.key, REFINE, 0, 4); s.demands.offerDetail(middleA.key, REFINE, 8, 4);
            s.demands.offerDetail(middleB.key, REFINE, 8, 4); s.demands.offerDetail(high.key, REFINE, 31, 4);
            s.demands.offerDetail(waking.key, WAKE, 30, 3); s.demands.offerDetail(sleeping.key, DORMANT, 1, 2);
            ObservedDeque observed = new ObservedDeque();
            // Erasure allows observing actual private DetailEvent objects without widening
            // production visibility. Allocating local buckets would leave this deque unused.
            Object[] ownedBuckets = s.detailBuckets; ownedBuckets[8] = observed;
            ArrayDeque<?>[] retainedBuckets = s.detailBuckets;
            Object[] buckets = retainedBuckets.clone(); s.drainDetailMailbox();
            check(order.equals(List.of(high.key, middleA.key, middleB.key, low.key)),
                    "detail priority or equal-bucket encounter order changed");
            check(sleeping.lastSelectedSequence < waking.lastSelectedSequence,
                    "dormancy pass did not preserve ascending bucket order");
            check(observed.additions == 4 && observed.clears == 1,
                    "production detail work/cleanup bypassed the retained deque");
            assertEmptyBuckets(s);
            s.drainDetailMailbox();
            check(observed.additions == 4 && observed.clears == 2,
                    "empty drain did not clean the same retained deque");
            s.demands.offerDetail(middleA.key, REFINE, 8, 5);
            s.demands.offerDetail(middleB.key, REFINE, 8, 5); s.drainDetailMailbox();
            check(observed.additions == 8 && observed.clears == 3,
                    "subsequent detail work reconstructed or skipped its retained deque");
            ArrayDeque<?>[] currentBuckets = s.detailBuckets;
            for (int i = 0; i < buckets.length; i++) check(buckets[i] == currentBuckets[i], "detail deque was reconstructed");
            assertEmptyBuckets(s);

            var retry = f.demand(key(1, 4), 1); retry.activeContent = null;
            LinkedHashMap<Long, SectionDemandTable.DetailUpdate> incoming = new LinkedHashMap<>() {
                @Override public void forEach(BiConsumer<? super Long, ? super SectionDemandTable.DetailUpdate> consumer) {
                    super.forEach(consumer);
                    s.demands.offerDetail(retry.key, REFINE, 19, 6);
                }
            };
            mailbox(s, incoming); s.demands.offerDetail(retry.key, REFINE, 9, 5);
            s.drainDetailMailbox();
            check(retry.latestRefinementEpoch == -1 && !retry.childrenRequired,
                    "missing-content retry advanced its refinement epoch");
            Map<Long, SectionDemandTable.DetailUpdate> remaining = new LinkedHashMap<>();
            s.demands.drainDetail(remaining::put);
            check(remaining.get(retry.key).epoch() == 6 && remaining.get(retry.key).bucket() == 19,
                    "older owner retry overwrote newer producer feedback");

            var failed = f.demand(key(1, 5), 1); var discarded = f.demand(key(1, 6), 1);
            var lower = f.demand(key(1, 7), 1);
            var map = demandMap(s); map.target = failed.key; map.returnNullAt = 3;
            mailbox(s, new LinkedHashMap<>());
            s.demands.offerDetail(failed.key, REFINE, 25, 8);
            s.demands.offerDetail(discarded.key, REFINE, 25, 8); s.demands.offerDetail(lower.key, REFINE, 3, 8);
            s.drainDetailMailbox(); map.returnNullAt = 0;
            check(failed.latestRefinementEpoch == -1 && discarded.latestRefinementEpoch == -1
                    && lower.latestRefinementEpoch == 8, "failed expansion changed break/lower-bucket behavior");
            remaining.clear(); s.demands.drainDetail(remaining::put);
            check(remaining.size() == 1 && remaining.containsKey(failed.key), "failed expansion replayed discarded local events");
            assertEmptyBuckets(s);
        }
    }

    private static final class DemandMap extends LinkedHashMap<Long, ClientSession.Demand> {
        long target; int gets, returnNullAt, throwAt;
        DemandMap(Map<Long, ClientSession.Demand> original) { super(original); }
        @Override public ClientSession.Demand get(Object key) {
            if (Objects.equals(key, this.target)) {
                this.gets++;
                if (this.gets == this.throwAt) throw new Injected();
                if (this.gets == this.returnNullAt) return null;
            }
            return super.get(key);
        }
    }
    @SuppressWarnings("unchecked") private static DemandMap demandMap(ClientSession.Session s) {
        var map = new DemandMap((Map<Long, ClientSession.Demand>) field(s.demands, "demands"));
        set(s.demands, "demands", map); return map;
    }
    private static void mailbox(ClientSession.Session s, Map<Long, SectionDemandTable.DetailUpdate> pending) {
        set(field(s.demands, "detailMailbox"), "pending", pending);
    }

    private static void detailFailuresClearLocalBuckets() {
        for (int phase = 0; phase < 4; phase++) try (Fixture f = new Fixture()) {
            var s = f.session; var parent = f.demand(key(1, 0), 1);
            var leaf = f.demand(child(parent.key, 0), 0); leaf.publication = new Publication(f.publisher);
            if (phase == 0) mailbox(s, new LinkedHashMap<>() {
                @Override public void forEach(BiConsumer<? super Long, ? super SectionDemandTable.DetailUpdate> consumer) {
                    super.forEach(consumer);
                    s.demands.offerDetail(key(0, 40), WAKE, 2, 9); throw new Injected();
                }
            });
            if (phase == 1) { s.dormantGeometryBytes = 1; s.activeGeometryBytes = 0; }
            if (phase == 2) {
                parent.publication = new Publication(f.publisher); parent.childrenRequired = true;
                leaf.activeGeometryBytes = 1; s.activeGeometryBytes = 1;
                f.publisher.coarsening = () -> { throw new Injected(); };
            }
            DemandMap fault = phase == 3 ? demandMap(s) : null;
            if (fault != null) { fault.target = parent.key; fault.throwAt = 3; }
            if (phase == 1) s.demands.offerDetail(leaf.key, WAKE, 0, 1);
            if (phase == 2) s.demands.offerDetail(parent.key, DORMANT, 0, 1);
            if (phase != 2) s.demands.offerDetail(parent.key, REFINE, 20, 1);
            // Different keys are required because each key's mailbox entry is latest-only.
            if (phase == 2) s.demands.offerDetail(key(0, 50), REFINE, 20, 1);
            boolean failed = false;
            try { s.drainDetailMailbox(); }
            catch (Injected | IllegalStateException expected) { failed = true; }
            finally {
                if (fault != null) fault.throwAt = 0;
                if (phase == 1) s.dormantGeometryBytes = 0;
                f.publisher.coarsening = () -> {};
            }
            check(failed, "fault injection did not reach detail phase " + phase);
            assertEmptyBuckets(s);
            if (phase == 0) {
                Map<Long, SectionDemandTable.DetailUpdate> retry = new HashMap<>(); s.demands.drainDetail(retry::put);
                check(retry.containsKey(key(0, 40)), "local cleanup cleared concurrent shared-mailbox updates");
            }
        }
    }

    private static void publicationScanChanges() throws Exception {
        try (Fixture f = new Fixture()) {
            var s = f.session; s.pollPublications();
            var demand = f.demand(key(1, 0), 0); var publication = f.submit(demand, false, false);
            check(s.publicationDirty && s.wakePending, "accepted installation did not dirty/wake owner");
            s.pollPublications(); int checks = publication.inspections;
            for (int i = 0; i < 8; i++) s.pollPublications();
            check(publication.inspections == checks, "unchanged owner turns still query publications");
            var progress = f.publisher.progress;
            s.rendererWake.run(); s.pollPublications();
            check(publication.inspections == checks + 1 && f.publisher.progress.equals(progress),
                    "generation-only renderer progress did not trigger a scan");
            publication.inspect = once(s.rendererWake);
            s.rendererWake.run(); s.pollPublications(); checks = publication.inspections;
            s.pollPublications(); check(publication.inspections == checks + 1,
                    "notification during scan was lost by an end-of-scan generation refresh");
            publication.inspect = once(s::markPublicationsDirty);
            s.rendererWake.run(); s.pollPublications(); checks = publication.inspections;
            s.pollPublications(); check(publication.inspections == checks + 1, "owner dirty mark during scan was consumed prematurely");
            s.rendererWake.run(); s.pollPublications(); checks = publication.inspections;
            s.rendererWake.run(); s.pollPublications(); check(publication.inspections == checks + 1, "notification after scan was lost");
            publication.inspect = () -> { throw new Injected(); };
            s.rendererWake.run();
            try { s.pollPublications(); throw new AssertionError("publication failure was swallowed"); }
            catch (Injected expected) { check(s.publicationDirty, "aborted scan left publication guard clean"); }
            publication.inspect = () -> {};
        }
        try (Fixture f = new Fixture()) {
            var s = f.session; s.pollPublications();
            f.publisher.beforeReturn = handles -> handles.forEach(p -> p.completeUpload(new UploadOutcome(UploadStatus.ACTIVATED, null, null)));
            var demand = f.demand(key(1, 0), 0); long before = s.activated;
            f.submit(demand, false, false); s.pollPublications();
            check(s.activated == before + 1 && s.publicationQueue.isEmpty(), "synchronous completion before queue installation stalled");
        }
        try (Fixture f = new Fixture()) {
            var s = f.session; s.pollPublications();
            var pending = f.submit(f.demand(key(1, 0), 0), false, false); s.pollPublications();
            var blocked = f.demand(key(1, 1), 0);
            blocked.blockedReason = AllocationStatus.NO_SECTION_ID; blocked.blockedAt = f.publisher.progress;
            s.rendererBlocked.add(blocked.key); int checks = pending.inspections;
            f.publisher.progress = new PublicationProgress(0, 0, 0, 1, false, null);
            s.pollPublications();
            check(pending.inspections == checks && blocked.blockedReason == null
                    && blocked.readyKind == SectionDemandTable.ReadyKind.RENDERER,
                    "scan skip suppressed independent blocked-publication retry");
            f.publisher.progress = new PublicationProgress(0, 0, 0, 1, false, new Injected());
            try { s.scheduleReadyPublications(); throw new AssertionError("renderer failure health check was skipped"); }
            catch (IllegalStateException expected) { check(expected.getCause() instanceof Injected, "wrong renderer failure"); }
        }
    }

    private static void publicationOutcomesAndLeases() throws Exception {
        for (UploadStatus status : UploadStatus.values()) try (Fixture f = new Fixture()) {
            var s = f.session; var demand = f.demand(key(1, 0), 0);
            var publication = f.submit(demand, true, false); var lease = demand.workLease;
            var worker = s.sectionWorkers[lease.slot()];
            BuiltSection geometry = new BuiltSection(demand.key, demand.revision, (byte) 0, 0, f.buffers.get(0), new int[8]);
            AllocationBlock block = status == UploadStatus.RETURNED
                    ? new AllocationBlock(geometry, AllocationStatus.TOPOLOGY_NOT_READY, 1, 1, demand.key, f.publisher.progress) : null;
            if (status == UploadStatus.FAILED || status == UploadStatus.CANCELLED) geometry.free();
            publication.completeUpload(new UploadOutcome(status, block, null)); s.pollPublications();
            check(s.publicationQueue.isEmpty() && s.publicationOutcomes[status.ordinal()] == 1,
                    "terminal outcome was lost or retained in polling queue: " + status);
            if (status == UploadStatus.RETURNED) {
                check(demand.completedGeometry == geometry && worker.resource.matches(lease)
                        && s.rendererBlocked.contains(demand.key), "returned geometry lost worker/retry ownership");
            } else check(worker.resource.state() == WorkerResource.State.IDLE && demand.workLease == null,
                    "terminal outcome did not release its exact worker lease: " + status);
            int takes = publication.inspections; s.pollPublications(); s.pollPublications();
            check(publication.inspections == takes, "claimed terminal outcome was queried again");
        }
        try (Fixture f = new Fixture()) {
            var s = f.session; var demand = f.demand(key(1, 0), 0);
            var publication = f.submit(demand, true, true); var lease = demand.workLease;
            var worker = s.sectionWorkers[lease.slot()];
            publication.markRendererAdmitted(); s.pollPublications();
            check(demand.workLease == null && worker.resource.state() == WorkerResource.State.COMPLETED
                    && worker.resource.releaseRequested() && worker.resource.savePending(),
                    "admission bypassed pending save acknowledgement");
            check(worker.resource.finishSave(lease) && worker.resource.state() == WorkerResource.State.IDLE,
                    "same-lease save acknowledgement did not restore reuse");
            publication.completeUpload(new UploadOutcome(UploadStatus.ACTIVATED, null, null)); s.pollPublications();
        }
        try (Fixture f = new Fixture()) {
            var demand = f.demand(key(1, 0), 0); var publication = f.submit(demand, false, false);
            var lease = demand.workLease; publication.markRendererAdmitted(); f.session.pollPublications();
            check(f.session.sectionWorkers[lease.slot()].resource.matches(lease) && demand.workLease != null,
                    "empty-result optimization leaked into owner-efficiency work");
            publication.completeUpload(new UploadOutcome(UploadStatus.ACTIVATED, null, null)); f.session.pollPublications();
            check(f.session.sectionWorkers[lease.slot()].idle(), "empty terminal result did not release lease");
        }
    }

    private static void preservationInvalidationAndShutdown() throws Exception {
        for (boolean activates : new boolean[]{false, true}) try (Fixture f = new Fixture()) {
            var s = f.session; var demand = f.demand(key(1, 0), 0);
            var publication = f.submit(demand, false, false); s.pollPublications();
            demand.networkWork = true;
            invoke(s, "invalidateNetworkCandidate", demand);
            check(s.publicationDirty && demand.preservedPublication == publication,
                    "network invalidation lost publication identity or scan dirtiness");
            publication.completeUpload(new UploadOutcome(activates ? UploadStatus.ACTIVATED : UploadStatus.CANCELLED, null, null));
            s.pollPublications();
            check(demand.preservedPublication == null && s.publicationQueue.isEmpty(), "preserved terminal ownership did not drain");
            if (activates) check(demand.publication == publication && demand.installed,
                    "late activation did not retain its validated stale fallback");
        }
        try (Fixture f = new Fixture()) {
            var s = f.session; var first = f.demand(key(1, 0), 0); var second = f.demand(key(1, 1), 0);
            var a = f.submit(first, false, false); var b = f.submit(second, false, false); s.pollPublications();
            a.inspect = once(() -> s.retireDemand(second.key));
            s.rendererWake.run(); s.pollPublications();
            check(s.publicationDirty && !b.acceptsUpload(), "midscan demand removal did not invalidate the other reference");
            b.completeUpload(new UploadOutcome(UploadStatus.CANCELLED, null, null)); s.pollPublications();
            check(s.publicationQueue.size() == 1, "removed-demand terminal reference was not drained");
            s.pollPublications();
            int takes = a.inspections;
            f.release();
            check(a.abandonments == 1 && f.publisher.removals == 1 && f.publisher.listener == null
                    && s.publicationQueue.isEmpty() && a.inspections == takes,
                    "shutdown required another progress notification or abandoned ownership twice");
        }
        try (Fixture f = new Fixture()) {
            var s = f.session; var demand = f.demand(key(1, 0), 0); var publication = f.submit(demand, false, false);
            s.pollPublications(); s.worldIdentity = new RegionalProtocol.Hash32(4, 3, 2, 1);
            s.changeWorld(new RegionalProtocol.Hash32(1, 2, 3, 4));
            check(s.publicationDirty && !publication.acceptsUpload(), "world correction failed to invalidate publications");
        }
    }

    private static void sessionBatchScopeAndPrefixWake() {
        Object previous = field(ClientSession.class, "active");
        try (Fixture f = new Fixture()) {
            var s = f.session;
            set(ClientSession.class, "active", null);
            check(ClientSession.captureDetailBatch(s.renderer) == null, "batch captured without an active session");
            set(ClientSession.class, "active", s);
            check(ClientSession.captureDetailBatch(allocate(VoxyRenderSystem.class)) == null,
                    "batch captured for the wrong renderer");
            var delivery = ClientSession.captureDetailBatch(s.renderer);
            s.wakePending = false;
            delivery.accept(actions -> {});
            delivery.accept(actions -> actions.accept(key(0, 0), 999, 0, 1));
            check(!s.wakePending, "empty/unsupported batch signaled the owner");
            delivery.accept(actions -> {
                actions.accept(key(0, 0), REFINE, 1, -2);
                actions.accept(key(0, 1), WAKE, 30, -1);
            });
            check(s.wakePending, "accepted batch did not wake its target owner");
            s.wakePending = false;
            delivery.accept(actions -> actions.accept(key(0, 0), DORMANT, 5, -2));
            check(!s.wakePending, "equal-epoch rejected batch signaled the owner");
            Map<Long, SectionDemandTable.DetailUpdate> captured = new HashMap<>(); s.demands.drainDetail(captured::put);
            check(captured.size() == 2 && captured.get(key(0, 0)).action() == REFINE,
                    "session batch changed unsigned/equal-epoch mailbox semantics");

            try {
                delivery.accept(actions -> { actions.accept(key(0, 2), REFINE, 4, 2); throw new Injected(); });
                throw new AssertionError("batch reader failure was swallowed");
            } catch (Injected expected) { check(s.wakePending, "accepted-prefix exception lost its one owner wake"); }
            captured.clear(); s.demands.drainDetail(captured::put);
            check(captured.size() == 1 && captured.containsKey(key(0, 2)), "exception discarded an accepted batch prefix");
            s.wakePending = false; s.viewRevision++;
            delivery.accept(actions -> { throw new AssertionError("stale view reader was invoked"); });
            check(!s.wakePending, "stale view delivery signaled owner");

            delivery = ClientSession.captureDetailBatch(s.renderer);
            long input = s.demands.detailInputGeneration();
            ClientSession.resetDemand();
            check(s.resetRequested.get() && s.wakePending && s.demands.detailInputGeneration() != input,
                    "demand reset did not invalidate readback input and wake its owner");
            s.wakePending = false;
            delivery.accept(actions -> { throw new AssertionError("pre-reset reader was invoked"); });
            check(!s.wakePending, "pre-reset batch repopulated a cleared mailbox");
            s.drainDemand();
            delivery = ClientSession.captureDetailBatch(s.renderer);
            s.changeWorld(new RegionalProtocol.Hash32(1, 2, 3, 4)); s.wakePending = false;
            delivery.accept(actions -> { throw new AssertionError("pre-world-correction reader was invoked"); });
            check(!s.wakePending, "world correction accepted a stale readback");

            delivery = ClientSession.captureDetailBatch(s.renderer);
            try (Fixture replacement = new Fixture()) {
                set(ClientSession.class, "active", replacement.session);
                delivery.accept(actions -> { throw new AssertionError("replaced-session reader was invoked"); });
                check(replacement.session.demands.pendingInputCount() == 0,
                        "old readback was redirected into the replacement session");
                set(ClientSession.class, "active", s);
                var bound = ClientSession.captureDetailBatch(s.renderer);
                bound.accept(actions -> {
                    actions.accept(key(0, 3), REFINE, 1, 3);
                    set(ClientSession.class, "active", replacement.session);
                    actions.accept(key(0, 4), REFINE, 1, 3);
                });
                check(replacement.session.demands.pendingInputCount() == 0,
                        "delivery reread active session and redirected a suffix");
                captured.clear(); s.demands.drainDetail(captured::put);
                check(captured.size() == 2, "captured delivery lost its bound target session");
            }
            set(ClientSession.class, "active", s);
            delivery = ClientSession.captureDetailBatch(s.renderer); f.release(); s.wakePending = false;
            delivery.accept(actions -> { throw new AssertionError("shutdown reader was invoked"); });
            check(!s.wakePending && s.demands.pendingInputCount() == 0, "shutdown accepted or signaled stale feedback");
        } finally { set(ClientSession.class, "active", previous); }
    }

    private static Runnable once(Runnable action) {
        return new Runnable() { boolean done; public void run() { if (!this.done) { this.done = true; action.run(); } } };
    }
    private static void assertEmptyBuckets(ClientSession.Session s) {
        ArrayDeque<?>[] buckets = s.detailBuckets;
        for (ArrayDeque<?> bucket : buckets) check(bucket.isEmpty(), "local detail event survived a drain");
    }
    private static final class Injected extends RuntimeException {}
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    @SuppressWarnings("unchecked") private static <T> T allocate(Class<T> type) {
        try {
            Field singleton = Unsafe.class.getDeclaredField("theUnsafe"); singleton.setAccessible(true);
            return (T) ((Unsafe) singleton.get(null)).allocateInstance(type);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    private static Object field(Object target, String name) {
        try {
            Field field = (target instanceof Class<?> type ? type : target.getClass()).getDeclaredField(name);
            field.setAccessible(true); return field.get(target instanceof Class<?> ? null : target);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    private static void set(Object target, String name, Object value) {
        try {
            Field field = (target instanceof Class<?> type ? type : target.getClass()).getDeclaredField(name);
            field.setAccessible(true); field.set(target instanceof Class<?> ? null : target, value);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    private static Object invoke(Object target, String name, Object... args) {
        try {
            Method method = Arrays.stream(target.getClass().getDeclaredMethods())
                    .filter(m -> m.getName().equals(name) && m.getParameterCount() == args.length).findFirst().orElseThrow();
            method.setAccessible(true); return method.invoke(target, args);
        } catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
            if (failure.getCause() instanceof Error error) throw error;
            throw new AssertionError(failure.getCause());
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
}
