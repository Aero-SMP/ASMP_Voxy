package me.cortex.voxy.client.lod;

import java.util.function.ToIntFunction;

/** Chooses only among already-delivered lane bodies, using current owner-thread authority. */
final class ReplyAdmission {
    static final int INELIGIBLE = Integer.MIN_VALUE;
    static <T> T select(Iterable<T> bodies, ToIntFunction<T> currentPriority) {
        T selected = null;
        int priority = INELIGIBLE;
        for (T body : bodies) {
            int candidate = currentPriority.applyAsInt(body);
            if (candidate > priority) {
                selected = body;
                priority = candidate;
            }
        }
        // Arrival order breaks ties. Removing an admitted body lets the other lanes progress.
        return selected;
    }
}
