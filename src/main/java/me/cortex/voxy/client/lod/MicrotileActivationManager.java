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
import java.util.function.Function;

/**
 * Bounded dependency-complete activation of final microtile groups.
 *
 * <p>Publication swaps exactly one complete node group. Replaced renderer allocations remain
 * owned until their retirement fence reports that no submitted work can reference them.</p>
 */
public final class MicrotileActivationManager implements AutoCloseable {
    /** A renderer publication became irrelevant before it acquired a hierarchy owner. */
    public static final class PublicationCancelledException extends RuntimeException {
        public PublicationCancelledException(String message) {
            super(message, null, false, false);
        }
    }

    public interface Publication extends AutoCloseable {
        /** True after the complete replacement is uploaded and renderer-visible. */
        boolean activationFencePassed();

        /** Asynchronous upload/synchronization failure, if publication can no longer activate. */
        Optional<Throwable> activationFailure();

        /** True only after no submitted renderer work can reference this publication. */
        boolean retirementFencePassed();

        @Override
        void close();
    }

    public interface Renderer {
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

    /** Compact, point-in-time activation pipeline state for debug diagnostics. */
    public record Diagnostics(int slots, int candidates, int compiling,
                              int pendingPublications, int active, int pendingRemovals,
                              int retiredGroups, int retiredRemovals,
                              long activeGeometryBytes, long pendingGeometryBytes) {}

    private final HybridMeshingDispatcher dispatcher;
    private final Renderer renderer;
    private final Map<SpatialNode, Slot> slots = new HashMap<>();
    private final ArrayDeque<Retired> retired = new ArrayDeque<>();
    private final ArrayDeque<Publication> retiredRemovals = new ArrayDeque<>();
    private int compilingCandidates;
    private long retentionRevision;
    private boolean dispatcherClosed;
    private boolean closed;

    public MicrotileActivationManager(HybridMeshingDispatcher dispatcher, Renderer renderer) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.renderer = Objects.requireNonNull(renderer, "renderer");
    }

    /** Stages a complete candidate while leaving the currently active group untouched. */
    public synchronized boolean stage(ActivationGroup group) {
        ensureOpen();
        Objects.requireNonNull(group, "group");
        Slot slot = this.slots.get(group.node());
        if (slot != null && (slot.pending != null
                || slot.pendingRemoval != null
                || slot.candidate != null && slot.candidate.compiling)) return false;
        if (slot != null && compareAuthority(group.root(), slot.authority) < 0) return false;
        if (slot == null) {
            slot = new Slot();
            this.slots.put(group.node(), slot);
        }
        if (slot.candidate != null) slot.candidate.close();
        slot.authority = group.root();
        slot.candidate = new Candidate(group);
        this.retentionRevision++;
        return true;
    }

    /**
     * Retains only candidates that cover the latest cut and do not belong to a superseded root.
     * A compiler keeps ownership until it returns, at which point an obsolete result is
     * discarded. A candidate newer than the caller's authority is left untouched.
     */
    public synchronized void retainCandidates(
            RootToken authority, Function<SpatialNode, ContentPipeline.SelectionCut> requiredCut) {
        ensureOpen();
        Objects.requireNonNull(authority, "authority");
        Objects.requireNonNull(requiredCut, "requiredCut");
        var iterator = this.slots.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<SpatialNode, Slot> entry = iterator.next();
            Slot slot = entry.getValue();
            Candidate candidate = slot.candidate;
            if (candidate == null) continue;
            int freshness = compareAuthority(authority, candidate.content.root());
            if (freshness < 0) continue;
            ContentPipeline.SelectionCut required = freshness == 0
                    ? requiredCut.apply(entry.getKey()) : null;
            if (required != null && covers(candidate.content.selectionCut(), required)) continue;
            candidate.obsolete = true;
            if (!candidate.compiling) {
                candidate.close();
                slot.candidate = null;
                this.retentionRevision++;
                if (slot.active == null && slot.pending == null && slot.pendingRemoval == null) {
                    iterator.remove();
                }
            }
        }
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
                if (candidate.obsolete) {
                    geometry.free();
                    geometry = null;
                    candidate.close();
                    slot.candidate = null;
                    this.retentionRevision++;
                    removeSlotIfEmpty(node, slot);
                    return CompileStatus.NO_CANDIDATE;
                }
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
                // the closed-manager branch above, so the compiler must close the candidate.
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
                || slot.candidate.obsolete
                || !slot.candidate.content.root().equals(authority)) return false;
        Candidate candidate = slot.candidate;
        Publication publication = Objects.requireNonNull(this.renderer.publishAtomically(node,
                candidate.geometry, Optional.ofNullable(slot.active)
                        .map(active -> active.publication)), "renderer publication");
        candidate.geometry = null;
        Active pending = new Active(candidate.content, publication, candidate.geometryBytes);
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
            this.retentionRevision++;
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
        if (count != 0) this.retentionRevision++;
        return count;
    }

    /** Changes only when the set of content hashes retained by this manager may have changed. */
    public synchronized long retentionRevision() {
        ensureOpen();
        return this.retentionRevision;
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

    /**
     * Captures one synchronized pipeline snapshot. Pending geometry includes compiled candidates
     * and publications waiting for their activation fence; retired geometry remains represented
     * by the retired-group count until its retirement fence completes.
     */
    public synchronized Diagnostics diagnostics() {
        ensureOpen();
        int candidates = 0;
        int pendingPublications = 0;
        int active = 0;
        int pendingRemovals = 0;
        long activeGeometryBytes = 0;
        long pendingGeometryBytes = 0;
        for (Slot slot : this.slots.values()) {
            if (slot.candidate != null) {
                candidates++;
                pendingGeometryBytes += slot.candidate.geometryBytes;
            }
            if (slot.pending != null) {
                pendingPublications++;
                pendingGeometryBytes += slot.pending.geometryBytes;
            }
            if (slot.active != null) {
                active++;
                activeGeometryBytes += slot.active.geometryBytes;
            }
            if (slot.pendingRemoval != null) pendingRemovals++;
        }
        return new Diagnostics(this.slots.size(), candidates, this.compilingCandidates,
                pendingPublications, active, pendingRemovals, this.retired.size(),
                this.retiredRemovals.size(), activeGeometryBytes, pendingGeometryBytes);
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
            this.retentionRevision++;
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
        this.retentionRevision++;
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

    private static boolean covers(ContentPipeline.SelectionCut available,
                                  ContentPipeline.SelectionCut required) {
        return (available.exteriorMask() & required.exteriorMask()) == required.exteriorMask()
                && (available.interiorMask() & required.interiorMask()) == required.interiorMask()
                && (available.complexMask() & required.complexMask()) == required.complexMask();
    }

    private static final class Slot implements AutoCloseable {
        private RootToken authority;
        private Active active;
        private Active pending;
        private Publication pendingRemoval;
        private Candidate candidate;

        @Override
        public void close() {}
    }

    private static final class Candidate implements AutoCloseable {
        private final ActivationGroup content;
        private BuiltSection geometry;
        private long geometryBytes;
        private boolean compiling;
        private boolean obsolete;

        private Candidate(ActivationGroup content) {
            this.content = content;
        }

        @Override
        public void close() {
            if (this.geometry != null) {
                this.geometry.free();
                this.geometry = null;
            }
        }
    }

    private static final class Active implements AutoCloseable {
        private final ActivationGroup content;
        private final Publication publication;
        private final long geometryBytes;

        private Active(ActivationGroup content, Publication publication, long geometryBytes) {
            this.content = content;
            this.publication = publication;
            this.geometryBytes = geometryBytes;
        }

        @Override
        public void close() {
            this.publication.close();
        }
    }

    private record Retired(Active active) {}
}
