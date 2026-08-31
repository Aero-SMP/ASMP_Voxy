package me.cortex.voxy.debug;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DetailStateMachineTest {
    @Test
    void initialTtfdUsesSubmittedFrameTimestamp() {
        DetailStateMachine machine = new DetailStateMachine();
        machine.handshake(100);
        machine.accept(new DetailStateMachine.Frame(1, 150, false, true, "missingRoots=1"));
        DetailStateMachine.Transition result = machine.accept(
                new DetailStateMachine.Frame(2, 400, true, true, "none"));
        assertEquals(300, result.initialTtfdNanos());
        assertEquals(DetailStateMachine.State.FULL_DETAIL, result.after());
    }

    @Test
    void inconclusiveAndStaleFramesCannotCompleteTtfd() {
        DetailStateMachine machine = new DetailStateMachine();
        machine.handshake(10);
        machine.accept(new DetailStateMachine.Frame(2, 20, true, false, "inconclusive"));
        assertEquals(DetailStateMachine.State.SEEKING_FULL_DETAIL, machine.state());
        machine.accept(new DetailStateMachine.Frame(1, 30, true, true, "none"));
        assertEquals(DetailStateMachine.State.SEEKING_FULL_DETAIL, machine.state());
    }

    @Test
    void reportsLossAndRestorationDuration() {
        DetailStateMachine machine = new DetailStateMachine();
        machine.handshake(0);
        machine.accept(new DetailStateMachine.Frame(1, 10, true, true, "none"));
        DetailStateMachine.Transition lost = machine.accept(
                new DetailStateMachine.Frame(2, 20, false, true, "missingMesh=1"));
        assertEquals(DetailStateMachine.State.DEGRADED, lost.after());
        DetailStateMachine.Transition restored = machine.accept(
                new DetailStateMachine.Frame(3, 75, true, true, "none"));
        assertEquals(55, restored.degradedDurationNanos());
        assertEquals(65, restored.lastFullToFullNanos());
    }

    @Test
    void stableFullDetailIsSeparateThirtyFrameMilestone() {
        DetailStateMachine machine = new DetailStateMachine();
        machine.handshake(0);
        DetailStateMachine.Transition result = null;
        for (int i = 1; i <= 30; i++) {
            result = machine.accept(new DetailStateMachine.Frame(i, i, true, true, "none"));
        }
        assertNotNull(result);
        assertTrue(result.stableFullDetail());
        assertFalse(machine.accept(new DetailStateMachine.Frame(31, 31, true, true, "none"))
                .stableFullDetail());
    }

    @Test
    void resetSeparatesReconnectMeasurements() {
        DetailStateMachine machine = new DetailStateMachine();
        machine.handshake(100);
        assertEquals(50, machine.accept(new DetailStateMachine.Frame(1, 150, true, true, "none"))
                .initialTtfdNanos());
        machine.reset();
        assertEquals(DetailStateMachine.State.WAITING_FOR_HANDSHAKE, machine.state());
        machine.handshake(1_000);
        assertEquals(250, machine.accept(new DetailStateMachine.Frame(2, 1_250, true, true, "none"))
                .initialTtfdNanos());
    }

    @Test
    void incompleteTelemetryCannotInventDetailLoss() {
        DetailStateMachine machine = new DetailStateMachine();
        machine.handshake(0);
        machine.accept(new DetailStateMachine.Frame(1, 10, true, true, "none"));
        DetailStateMachine.Transition result = machine.accept(
                new DetailStateMachine.Frame(2, 20, false, false, "inconclusive"));
        assertEquals(DetailStateMachine.State.FULL_DETAIL, result.after());
    }
}
