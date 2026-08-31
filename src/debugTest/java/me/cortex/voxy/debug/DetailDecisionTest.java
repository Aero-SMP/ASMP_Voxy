package me.cortex.voxy.debug;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DetailDecisionTest {
    private static DetailDecision.Result decide(long visited, long roots, long descent,
                                                 long mesh, boolean overflow) {
        return DetailDecision.evaluate(new DetailDecision.Input(true, visited, roots,
                descent, mesh, 0, 0, 0, 0, false, overflow));
    }

    @Test
    void completeLodZeroOrAuthoritativeEmptyFrontierCanBeFullDetail() {
        assertTrue(decide(1, 0, 0, 0, false).fullDetail());
    }

    @Test
    void InFlightOrMissingDetailCannotBeFullDetail() {
        assertFalse(decide(10, 0, 1, 0, false).fullDetail());
        assertTrue(decide(10, 0, 1, 0, false).blockers().contains("blockedDescent=1"));
    }

    @Test
    void missingVisibleRootOrMeshBlocksFullDetail() {
        assertFalse(decide(10, 1, 0, 0, false).fullDetail());
        assertFalse(decide(10, 0, 0, 1, false).fullDetail());
    }

    @Test
    void zeroVisitedNodesCannotProveFullDetail() {
        DetailDecision.Result result = decide(0, 0, 0, 0, false);
        assertFalse(result.fullDetail());
        assertEquals("noVisitedNodes", result.blockers());
    }

    @Test
    void telemetryLossMakesFrameInconclusive() {
        DetailDecision.Result result = decide(10, 0, 0, 0, true);
        assertFalse(result.conclusive());
        assertFalse(result.fullDetail());
    }

    @Test
    void uncoveredBranchCannotBeFullDetail() {
        DetailDecision.Result result = DetailDecision.evaluate(new DetailDecision.Input(
                true, 10, 0, 0, 0, 1, 0, 0, 0, false, false));
        assertTrue(result.conclusive());
        assertFalse(result.fullDetail());
        assertTrue(result.blockers().contains("coverageHoles=1"));
    }

    @Test
    void gpuQueueOverflowMakesFrameInconclusive() {
        DetailDecision.Result result = DetailDecision.evaluate(new DetailDecision.Input(
                true, 10, 0, 0, 0, 0, 1, 0, 0, false, false));
        assertFalse(result.conclusive());
        assertFalse(result.fullDetail());
        assertTrue(result.blockers().contains("requestOverflow=1"));
    }

    @Test
    void missingRootAuditOverflowMakesFrameInconclusive() {
        DetailDecision.Result result = DetailDecision.evaluate(new DetailDecision.Input(
                true, 10, 0, 0, 0, 0, 0, 0, 0, true, false));
        assertFalse(result.conclusive());
        assertTrue(result.blockers().contains("rootListOverflow"));
    }
}
