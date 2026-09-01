package me.cortex.voxy.client.lod;

import me.cortex.voxy.client.core.rendering.building.BuiltSection;
import me.cortex.voxy.client.core.rendering.building.HybridMeshingDispatcher;
import me.cortex.voxy.client.lod.ContentPipeline.ActivationGroup;
import me.cortex.voxy.client.lod.ManifestCodec.SpatialNode;
import me.cortex.voxy.client.lod.WireMessage.RootToken;
import me.cortex.voxy.client.lod.WireMessage.Hash256;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Bounded dependency-complete activation of final microtile groups.
 *
 * <p>A replacement reserves scratch plus its full geometry limit before compilation. Publication
 * swaps exactly one complete node group. The replaced renderer allocation and its budget remain
 * owned until the renderer fence reports safe retirement.</p>
 */
public final class MicrotileActivationManager implements AutoCloseable {
    private static final long SLOT_BYTES = 384;
    public interface Publication extends AutoCloseable {
        /** True after the complete replacement is uploaded and renderer-visible. */
        boolean activationFencePassed();

        /** Asynchronous upload/synchronization failure, if publication can no longer activate. */
        Optional<Throwable> activationFailure();

        /** True only after no submitted renderer work can reference this publication. */
        boolean retirementFencePassed();

        /**
         * Transfers an accounting release to the renderer lifetime. The callback runs exactly
         * once, and never before the publication is retired or a failed publication has been
         * rolled back completely.
         */
        void releaseWhenSafe(Runnable release);

        @Override
        void close();
    }

    public interface RendererBridge {
        /**
         * Atomically publishes the complete replacement and returns its retained renderer handle.
         * On success ownership of {@code geometry} transfers to the returned publication. The
         * previous handle must remain usable until its retirement fence passes.
         */
        Publication publishAtomically(SpatialNode node, BuiltSection geometry,
                                      Optional<Publication> previous) throws Exception;

        /** Queues an atomic authoritative removal while retaining the current publication. */
        Publication removeAtomically(SpatialNode node, Publication previous) throws Exception;
    }

    public enum CompileStatus {
        NO_CANDIDATE,
        ALREADY_COMPILED,
        COMPILED
    }

    public enum RemovalStatus {
        ALREADY_ABSENT,
        BLOCKED,
        QUEUED
    }

    public record ActiveGroup(ActivationGroup content, Publication publication,
                              long geometryBytes) {
        public ActiveGroup {
            Objects.requireNonNull(content, "content");
            Objects.requireNonNull(publication, "publication");
            if (geometryBytes < 0) throw new IllegalArgumentException("negative geometry bytes");
        }
    }

    private final MemoryBudget memory;
    private final HybridMeshingDispatcher dispatcher;
    private final RendererBridge renderer;
    private final Map<SpatialNode, Slot> slots = new HashMap<>();
    private final int maximumSlots;
    private final ArrayDeque<Retired> retired = new ArrayDeque<>();
    private final ArrayDeque<Publication> retiredRemovals = new ArrayDeque<>();
    private int compilingCandidates;
    private boolean dispatcherClosed;
    private boolean closed;

    public MicrotileActivationManager(MemoryBudget memory,
                                        HybridMeshingDispatcher dispatcher,
                                        RendererBridge renderer) {
        this.memory = Objects.requireNonNull(memory, "memory");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.maximumSlots = (int) Math.max(1_024L,
                Math.min(262_144L, memory.limit() >>> 12));
    }

    /** All pools are reserved together; failure leaves the currently active group untouched. */
    public synchronized boolean stage(ActivationGroup group, long meshingScratchBytes,
                                      long maximumGeometryBytes, long inFlightBytes) {
        ensureOpen();
        Objects.requireNonNull(group, "group");
        if ((meshingScratchBytes | maximumGeometryBytes | inFlightBytes) < 0) {
            throw new IllegalArgumentException("activation memory bounds cannot be negative");
        }
        Slot slot = this.slots.get(group.node());
        if (slot != null && (slot.pending != null
                || slot.pendingRemoval != null
                || slot.candidate != null && slot.candidate.compiling)) return false;
        if (slot != null && compareAuthority(group.root(), slot.authority) < 0) return false;
        MemoryBudget.Allocation allocation = new MemoryBudget.Allocation(
                0, 0, 0, 0, meshingScratchBytes, maximumGeometryBytes, 0,
                inFlightBytes);
        Optional<MemoryBudget.Reservation> reservation = this.memory.tryReserve(allocation);
        if (reservation.isEmpty()) return false;
        if (slot == null) {
            if (this.slots.size() >= this.maximumSlots) {
                reservation.orElseThrow().close();
                return false;
            }
            Optional<MemoryBudget.Reservation> slotMemory = this.memory.tryReserve(
                    MemoryBudget.Allocation.of(MemoryBudget.Pool.OBJECT_TABLE, SLOT_BYTES));
            if (slotMemory.isEmpty()) {
                reservation.orElseThrow().close();
                return false;
            }
            slot = new Slot(slotMemory.orElseThrow());
            this.slots.put(group.node(), slot);
        }
        if (slot.candidate != null) slot.candidate.close();
        slot.authority = group.root();
        slot.candidate = new Candidate(group, maximumGeometryBytes, inFlightBytes,
                reservation.orElseThrow());
        return true;
    }

    /** Runs the already-classified hybrid compiler; intended for a bounded meshing worker. */
    public CompileStatus compile(SpatialNode node, RootToken authority,
                                 long sectionPosition, long sourceRevision)
            throws Exception {
        Candidate candidate;
        synchronized (this) {
            ensureOpen();
            Objects.requireNonNull(authority, "authority");
            Slot slot = this.slots.get(Objects.requireNonNull(node, "node"));
            if (slot == null || slot.candidate == null
                    || !slot.candidate.content.root().equals(authority)) {
                return CompileStatus.NO_CANDIDATE;
            }
            candidate = slot.candidate;
            if (candidate.geometry != null) return CompileStatus.ALREADY_COMPILED;
            if (candidate.compiling) return CompileStatus.NO_CANDIDATE;
            candidate.compiling = true;
            this.compilingCandidates++;
        }

        BuiltSection geometry = null;
        try {
            geometry = this.dispatcher.mesh(sectionPosition, sourceRevision,
                    candidate.content);
            long geometryBytes = geometry.geometryBuffer == null ? 0 : geometry.geometryBuffer.size;
            synchronized (this) {
                if (this.closed) {
                    geometry.free();
                    geometry = null;
                    candidate.close();
                    return CompileStatus.NO_CANDIDATE;
                }
                Slot slot = this.slots.get(node);
                if (slot == null || slot.candidate != candidate) {
                    geometry.free();
                    return CompileStatus.NO_CANDIDATE;
                }
                if (geometryBytes > candidate.maximumGeometryBytes) {
                    throw new IllegalStateException(
                            "compiled geometry exceeds its pre-admitted bound");
                }
                candidate.memory.reduceTo(new MemoryBudget.Allocation(
                        0, 0, 0, 0, 0, geometryBytes, 0,
                        candidate.inFlightBytes));
                candidate.geometry = geometry;
                candidate.geometryBytes = geometryBytes;
                candidate.compiling = false;
                geometry = null;
                return CompileStatus.COMPILED;
            }
        } finally {
            if (geometry != null) geometry.free();
            boolean closeDispatcher;
            boolean closeCandidate;
            synchronized (this) {
                candidate.compiling = false;
                this.compilingCandidates--;
                if (this.compilingCandidates < 0) {
                    throw new IllegalStateException("negative compilation count");
                }
                // close() cannot release a candidate while its compiler owns it. If meshing
                // failed after the manager shut down, the normal success path never reaches
                // the closed-manager branch above, so the compiler must release that admission.
                closeCandidate = this.closed;
                closeDispatcher = this.closed && this.compilingCandidates == 0
                        && !this.dispatcherClosed;
                if (closeDispatcher) this.dispatcherClosed = true;
            }
            if (closeCandidate) candidate.close();
            if (closeDispatcher) this.dispatcher.close();
        }
    }

    /** Main/render-thread publication point. No partial microtile state is ever exposed. */
    public synchronized boolean publish(SpatialNode node, RootToken authority) throws Exception {
        ensureOpen();
        Objects.requireNonNull(authority, "authority");
        Slot slot = this.slots.get(Objects.requireNonNull(node, "node"));
        if (slot == null || slot.pending != null || slot.pendingRemoval != null
                || slot.candidate == null || slot.candidate.geometry == null
                || !slot.candidate.content.root().equals(authority)) return false;
        Candidate candidate = slot.candidate;
        Publication publication = Objects.requireNonNull(this.renderer.publishAtomically(node,
                candidate.geometry, Optional.ofNullable(slot.active)
                        .map(active -> active.publication)), "renderer publication");
        candidate.geometry = null;
        Active pending = new Active(candidate.content, publication, candidate.geometryBytes,
                candidate.memory);
        candidate.transferred = true;
        slot.candidate = null;
        slot.pending = pending;
        candidate.close();
        return true;
    }

    /** Queues an authoritative, fenced removal without dropping the currently visible group. */
    public synchronized RemovalStatus retire(SpatialNode node, RootToken authority)
            throws Exception {
        ensureOpen();
        Objects.requireNonNull(node, "node");
        Objects.requireNonNull(authority, "authority");
        Slot slot = this.slots.get(node);
        if (slot == null) return RemovalStatus.ALREADY_ABSENT;
        int freshness = compareAuthority(authority, slot.authority);
        if (freshness < 0) return RemovalStatus.BLOCKED;
        if (slot.pending != null || slot.pendingRemoval != null
                || slot.candidate != null && slot.candidate.compiling) {
            return RemovalStatus.BLOCKED;
        }
        if (slot.candidate != null) {
            slot.candidate.close();
            slot.candidate = null;
        }
        slot.authority = authority;
        if (slot.active == null) {
            removeSlotIfEmpty(node, slot);
            return RemovalStatus.ALREADY_ABSENT;
        }
        Publication removal = Objects.requireNonNull(this.renderer.removeAtomically(
                node, slot.active.publication), "renderer removal publication");
        if (removal == slot.active.publication) {
            throw new IllegalStateException("removal must return an independent publication");
        }
        slot.pendingRemoval = removal;
        return RemovalStatus.QUEUED;
    }

    /** Retires only fence-complete old groups; later entries may complete before earlier ones. */
    public synchronized int retireFenceComplete() {
        ensureOpen();
        int count = 0;
        var iterator = this.retired.iterator();
        while (iterator.hasNext()) {
            Retired retired = iterator.next();
            if (!retired.active.publication.retirementFencePassed()) continue;
            iterator.remove();
            retired.active.close();
            count++;
        }
        var removals = this.retiredRemovals.iterator();
        while (removals.hasNext()) {
            Publication removal = removals.next();
            if (!removal.retirementFencePassed()) continue;
            removals.remove();
            removal.close();
            count++;
        }
        return count;
    }

    public synchronized Optional<ActiveGroup> active(SpatialNode node) {
        ensureOpen();
        Slot slot = this.slots.get(Objects.requireNonNull(node, "node"));
        return slot == null || slot.active == null ? Optional.empty()
                : Optional.of(new ActiveGroup(slot.active.content, slot.active.publication,
                slot.active.geometryBytes));
    }

    public synchronized void forEachActive(Consumer<SpatialNode> visitor) {
        ensureOpen();
        Objects.requireNonNull(visitor, "visitor");
        for (Map.Entry<SpatialNode, Slot> entry : this.slots.entrySet()) {
            if (entry.getValue().active != null) visitor.accept(entry.getKey());
        }
    }

    public synchronized boolean isActive(SpatialNode node) {
        ensureOpen();
        Slot slot = this.slots.get(Objects.requireNonNull(node, "node"));
        return slot != null && slot.active != null;
    }

    public synchronized boolean retainsRoot(RootToken root) {
        ensureOpen();
        Objects.requireNonNull(root, "root");
        for (Slot slot : this.slots.values()) {
            if (slot.candidate != null && slot.candidate.content.root().equals(root)
                    || slot.pending != null && slot.pending.content.root().equals(root)
                    || slot.active != null && slot.active.content.root().equals(root)) return true;
        }
        for (Retired value : this.retired) {
            if (value.active.content.root().equals(root)) return true;
        }
        return false;
    }

    public synchronized long retainedHashReferenceCount() {
        ensureOpen();
        long count = 0;
        for (Slot slot : this.slots.values()) {
            if (slot.candidate != null) count += slot.candidate.content.requiredHashes().size();
            if (slot.pending != null) count += slot.pending.content.requiredHashes().size();
            if (slot.active != null) count += slot.active.content.requiredHashes().size();
        }
        for (Retired value : this.retired) {
            count += value.active.content.requiredHashes().size();
        }
        return count;
    }

    public synchronized void forEachRetainedHash(Consumer<Hash256> visitor) {
        ensureOpen();
        Objects.requireNonNull(visitor, "visitor");
        for (Slot slot : this.slots.values()) {
            if (slot.candidate != null) slot.candidate.content.requiredHashes().forEach(visitor);
            if (slot.pending != null) slot.pending.content.requiredHashes().forEach(visitor);
            if (slot.active != null) slot.active.content.requiredHashes().forEach(visitor);
        }
        for (Retired value : this.retired) {
            value.active.content.requiredHashes().forEach(visitor);
        }
    }

    /** Renderable state must wait for this fence after publication was queued. */
    public synchronized boolean activationFencePassed(SpatialNode node) {
        ensureOpen();
        Slot slot = this.slots.get(Objects.requireNonNull(node, "node"));
        if (slot == null) return false;
        if (slot.pendingRemoval != null) {
            Optional<Throwable> failure = Objects.requireNonNull(
                    slot.pendingRemoval.activationFailure(), "publication activation failure");
            if (failure.isPresent() || !slot.pendingRemoval.activationFencePassed()) return false;
            if (slot.active != null) this.retired.addLast(new Retired(slot.active));
            this.retiredRemovals.addLast(slot.pendingRemoval);
            slot.active = null;
            slot.pendingRemoval = null;
            removeSlotIfEmpty(node, slot);
            return true;
        }
        // Authority is only freshness metadata. A candidate may already have installed newer
        // authority while no renderer publication exists, so it is never renderability proof.
        if (slot.pending == null) return slot.active != null;
        if (slot.pending.publication.activationFailure().isPresent()
                || !slot.pending.publication.activationFencePassed()) return false;
        if (slot.active != null) this.retired.addLast(new Retired(slot.active));
        slot.pending.memory.reduceTo(MemoryBudget.Allocation.of(
                MemoryBudget.Pool.GEOMETRY, slot.pending.geometryBytes));
        slot.active = slot.pending;
        slot.pending = null;
        return true;
    }

    public synchronized Optional<Throwable> activationFailure(SpatialNode node) {
        ensureOpen();
        Slot slot = this.slots.get(Objects.requireNonNull(node, "node"));
        if (slot == null) return Optional.empty();
        if (slot.pending != null) return Objects.requireNonNull(
                slot.pending.publication.activationFailure(), "publication activation failure");
        return slot.pendingRemoval == null ? Optional.empty() : Objects.requireNonNull(
                slot.pendingRemoval.activationFailure(), "publication activation failure");
    }

    /** Discards a failed pending publication while leaving the last activated group untouched. */
    public synchronized boolean discardFailedPublication(SpatialNode node) {
        ensureOpen();
        Slot slot = this.slots.get(Objects.requireNonNull(node, "node"));
        if (slot == null) return false;
        if (slot.pending != null
                && slot.pending.publication.activationFailure().isPresent()) {
            slot.pending.close();
            slot.pending = null;
            removeSlotIfEmpty(node, slot);
            return true;
        }
        if (slot.pendingRemoval != null
                && slot.pendingRemoval.activationFailure().isPresent()) {
            slot.pendingRemoval.close();
            slot.pendingRemoval = null;
            removeSlotIfEmpty(node, slot);
            return true;
        }
        return false;
    }

    public synchronized boolean cancelCandidate(SpatialNode node, RootToken authority) {
        ensureOpen();
        Slot slot = this.slots.get(Objects.requireNonNull(node, "node"));
        Objects.requireNonNull(authority, "authority");
        if (slot == null || slot.candidate == null
                || !slot.candidate.content.root().equals(authority)) return false;
        if (slot.candidate.compiling) {
            throw new IllegalStateException("cannot cancel a candidate while it is compiling");
        }
        slot.candidate.close();
        slot.candidate = null;
        removeSlotIfEmpty(node, slot);
        return true;
    }

    @Override
    public void close() {
        boolean closeDispatcher;
        synchronized (this) {
            if (this.closed) return;
            this.closed = true;
            for (Slot slot : this.slots.values()) {
                if (slot.candidate != null && !slot.candidate.compiling) {
                    slot.candidate.close();
                }
                if (slot.pending != null) slot.pending.close();
                if (slot.pendingRemoval != null) slot.pendingRemoval.close();
                if (slot.active != null) slot.active.close();
                slot.close();
            }
            for (Retired value : this.retired) value.active.close();
            for (Publication value : this.retiredRemovals) value.close();
            this.slots.clear();
            this.retired.clear();
            this.retiredRemovals.clear();
            closeDispatcher = this.compilingCandidates == 0 && !this.dispatcherClosed;
            if (closeDispatcher) this.dispatcherClosed = true;
        }
        if (closeDispatcher) this.dispatcher.close();
    }

    private void ensureOpen() {
        if (this.closed) throw new IllegalStateException("microtile activation manager is closed");
    }

    private void removeSlotIfEmpty(SpatialNode node, Slot slot) {
        if (slot.active != null || slot.pending != null || slot.pendingRemoval != null
                || slot.candidate != null) return;
        if (this.slots.remove(node, slot)) slot.close();
    }

    private static int compareAuthority(RootToken candidate, RootToken current) {
        Objects.requireNonNull(candidate, "candidate");
        if (current == null) return 1;
        if (!candidate.dimensionHash().equals(current.dimensionHash())) {
            throw new IllegalArgumentException("activation authority changes dimension");
        }
        int freshness = Long.compareUnsigned(candidate.generation(), current.generation());
        if (freshness == 0 && !candidate.equals(current)) {
            throw new IllegalArgumentException("one root generation has conflicting authority");
        }
        return freshness;
    }

    private static final class Slot implements AutoCloseable {
        private final MemoryBudget.Reservation memory;
        private RootToken authority;
        private Active active;
        private Active pending;
        private Publication pendingRemoval;
        private Candidate candidate;

        private Slot(MemoryBudget.Reservation memory) {
            this.memory = memory;
        }

        @Override
        public void close() {
            this.memory.close();
        }
    }

    private static final class Candidate implements AutoCloseable {
        private final ActivationGroup content;
        private final long maximumGeometryBytes;
        private final long inFlightBytes;
        private final MemoryBudget.Reservation memory;
        private BuiltSection geometry;
        private long geometryBytes;
        private boolean compiling;
        private boolean transferred;

        private Candidate(ActivationGroup content, long maximumGeometryBytes, long inFlightBytes,
                          MemoryBudget.Reservation memory) {
            this.content = content;
            this.maximumGeometryBytes = maximumGeometryBytes;
            this.inFlightBytes = inFlightBytes;
            this.memory = memory;
        }

        @Override
        public void close() {
            if (this.geometry != null) {
                this.geometry.free();
                this.geometry = null;
            }
            if (!this.transferred) this.memory.close();
        }
    }

    private static final class Active implements AutoCloseable {
        private final ActivationGroup content;
        private final Publication publication;
        private final long geometryBytes;
        private final MemoryBudget.Reservation memory;

        private Active(ActivationGroup content, Publication publication, long geometryBytes,
                       MemoryBudget.Reservation memory) {
            this.content = content;
            this.publication = publication;
            this.geometryBytes = geometryBytes;
            this.memory = memory;
            this.publication.releaseWhenSafe(this.memory::close);
        }

        @Override
        public void close() {
            this.publication.close();
        }
    }

    private record Retired(Active active) {}
}
