package me.cortex.voxy.client.core.rendering.hierarchical;

import me.cortex.voxy.client.core.VoxyRenderSystem;
import java.util.Optional;

/** Upload outcome ownership and subsequent retirement of one exact publication identity. */
public abstract class SectionPublicationState implements VoxyRenderSystem.SectionPublication {
    private final PublicationOutcome<VoxyRenderSystem.UploadOutcome> outcome =
            new PublicationOutcome<>(result -> {
                if (result.block() != null) result.block().geometry().free();
            });
    private VoxyRenderSystem.UploadStatus status;
    private boolean closed;
    private boolean retired;
    private boolean retirementRequested;

    protected abstract void requestRetirement();
    protected abstract void stateChanged();

    public synchronized boolean acceptsUpload() { return !this.closed; }
    @Override public synchronized boolean activationFencePassed() {
        return this.status == VoxyRenderSystem.UploadStatus.ACTIVATED;
    }
    @Override public synchronized boolean retirementFencePassed() { return this.retired; }
    @Override public Optional<VoxyRenderSystem.UploadOutcome> takeUploadOutcome() {
        return Optional.ofNullable(this.outcome.claim());
    }

    public void completeUpload(VoxyRenderSystem.UploadOutcome result) {
        boolean retire;
        synchronized (this) {
            if (this.status != null) return;
            this.status = result.status();
            if (this.status != VoxyRenderSystem.UploadStatus.ACTIVATED) this.retired = true;
            retire = this.claimRetirement();
        }
        this.outcome.complete(result);
        if (retire) this.requestRetirement();
        this.stateChanged();
    }

    public void markRetired() {
        synchronized (this) { this.retired = true; }
        this.stateChanged();
    }

    @Override public void close() {
        boolean retire;
        synchronized (this) {
            this.closed = true;
            retire = this.claimRetirement();
        }
        // Renderer submission takes its own lock and can inspect other publications. Never
        // call it while holding this publication's monitor (including failure callbacks).
        if (retire) this.requestRetirement();
        this.stateChanged();
    }

    /** Terminal ownership when the entire renderer is being destroyed, not a reusable slot.
     * Normal live-renderer retirement still requires its actual GPU fence. */
    public void rendererStopped() {
        synchronized (this) { this.closed = true; this.retired = true; }
        this.completeUpload(new VoxyRenderSystem.UploadOutcome(
                VoxyRenderSystem.UploadStatus.CANCELLED, null, null));
        this.stateChanged();
    }

    @Override public void abandon(Runnable resolved) {
        this.close();
        this.outcome.abandon(resolved);
    }

    private boolean claimRetirement() {
        if (!this.closed || this.retired || this.retirementRequested
                || this.status != VoxyRenderSystem.UploadStatus.ACTIVATED) return false;
        this.retirementRequested = true;
        return true;
    }
}
