package me.cortex.voxy.debugtest;

import java.util.UUID;

/** Pure identity/sequence rules used before accepting live-test results. */
public final class DebugTestOrdering {
    private DebugTestOrdering() {}

    public static boolean isNext(long completedStep, long proposedStep) {
        return completedStep >= -1 && proposedStep == completedStep + 1;
    }

    public static boolean matches(UUID run, long epoch, long outstandingStep,
                                  UUID receivedRun, long receivedEpoch, long receivedStep) {
        return run != null && run.equals(receivedRun) && epoch > 0 && epoch == receivedEpoch
                && outstandingStep >= 0 && outstandingStep == receivedStep;
    }
}
