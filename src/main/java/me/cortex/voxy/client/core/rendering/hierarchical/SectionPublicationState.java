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

    public synchronized void completeUpload(VoxyRenderSystem.UploadOutcome result) {
        if (this.status != null) return;
        this.status = result.status();
        if (this.status != VoxyRenderSystem.UploadStatus.ACTIVATED) this.retired = true;
        this.outcome.complete(result);
        this.retireIfReady();
        this.stateChanged();
    }

    public synchronized void markRetired() {
        this.retired = true;
        this.stateChanged();
    }

    @Override public synchronized void close() {
        this.closed = true;
        this.retireIfReady();
        this.stateChanged();
    }

    @Override public void abandon(Runnable resolved) {
        this.close();
        this.outcome.abandon(resolved);
    }

    private void retireIfReady() {
        if (!this.closed || this.retired || this.retirementRequested
                || this.status != VoxyRenderSystem.UploadStatus.ACTIVATED) return;
        this.retirementRequested = true;
        this.requestRetirement();
    }
}
