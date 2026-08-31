package me.cortex.voxy.debug;

/** Pure debug-only state machine used by the LOD audit and its tests. */
final class DetailStateMachine {
    enum State { WAITING_FOR_HANDSHAKE, SEEKING_FULL_DETAIL, FULL_DETAIL, DEGRADED }

    record Frame(long sequence, long submittedNanos, boolean fullDetail, boolean conclusive,
                 String blockers) {}

    record Transition(State before, State after, long initialTtfdNanos,
                      long degradedDurationNanos, long lastFullToFullNanos,
                      boolean stableFullDetail,
                      String blockers) {
        static Transition none(State state, String blockers) {
            return new Transition(state, state, -1, -1, -1, false, blockers);
        }
    }

    private State state = State.WAITING_FOR_HANDSHAKE;
    private long handshakeNanos = -1;
    private long degradedNanos = -1;
    private long lastFullFrameNanos = -1;
    private long lastSequence = -1;
    private int consecutiveFullFrames;
    private boolean stableReported;

    synchronized void handshake(long nanos) {
        handshakeNanos = nanos;
        degradedNanos = -1;
        lastFullFrameNanos = -1;
        lastSequence = -1;
        consecutiveFullFrames = 0;
        stableReported = false;
        state = State.SEEKING_FULL_DETAIL;
    }

    synchronized void reset() {
        state = State.WAITING_FOR_HANDSHAKE;
        handshakeNanos = -1;
        degradedNanos = -1;
        lastFullFrameNanos = -1;
        lastSequence = -1;
        consecutiveFullFrames = 0;
        stableReported = false;
    }

    synchronized Transition accept(Frame frame) {
        if (frame.sequence <= lastSequence || state == State.WAITING_FOR_HANDSHAKE) {
            return Transition.none(state, frame.blockers);
        }
        lastSequence = frame.sequence;
        if (!frame.conclusive) {
            consecutiveFullFrames = 0;
            return Transition.none(state, frame.blockers);
        }

        State before = state;
        long initial = -1;
        long degraded = -1;
        long fullToFull = -1;
        if (frame.fullDetail) {
            consecutiveFullFrames++;
            if (state == State.SEEKING_FULL_DETAIL) {
                state = State.FULL_DETAIL;
                initial = Math.max(0, frame.submittedNanos - handshakeNanos);
            } else if (state == State.DEGRADED) {
                state = State.FULL_DETAIL;
                degraded = Math.max(0, frame.submittedNanos - degradedNanos);
                if (lastFullFrameNanos >= 0) {
                    fullToFull = Math.max(0, frame.submittedNanos - lastFullFrameNanos);
                }
                degradedNanos = -1;
            }
            lastFullFrameNanos = frame.submittedNanos;
        } else {
            consecutiveFullFrames = 0;
            stableReported = false;
            if (state == State.FULL_DETAIL) {
                state = State.DEGRADED;
                degradedNanos = frame.submittedNanos;
            }
        }

        boolean stable = state == State.FULL_DETAIL && consecutiveFullFrames >= 30 && !stableReported;
        if (stable) stableReported = true;
        return new Transition(before, state, initial, degraded, fullToFull, stable, frame.blockers);
    }

    synchronized State state() {
        return state;
    }
}
