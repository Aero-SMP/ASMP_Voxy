package me.cortex.voxy.client.lod;

import me.cortex.voxy.client.core.VoxyRenderSystem.AllocationStatus;
import me.cortex.voxy.client.core.rendering.hierarchical.AsyncNodeManager.PublicationProgress;

/** A refused operation retries only when its actual prerequisite has changed. */
final class RendererWait {
    static boolean progressed(AllocationStatus reason, PublicationProgress observed,
                              PublicationProgress current) {
        if (current.failure() != null) throw new IllegalStateException("renderer stopped", current.failure());
        return switch (reason) {
            case NO_CONTIGUOUS_GEOMETRY_SPACE -> current.allocation() != observed.allocation();
            case NO_SECTION_ID -> current.sectionIds() != observed.sectionIds();
            case TOPOLOGY_NOT_READY, STALE -> current.topology() != observed.topology();
            case IMPOSSIBLE -> false;
        };
    }

    static boolean needsRetirement(AllocationStatus reason, boolean retirementPending) {
        return !retirementPending && (reason == AllocationStatus.NO_CONTIGUOUS_GEOMETRY_SPACE
                || reason == AllocationStatus.NO_SECTION_ID);
    }
}
