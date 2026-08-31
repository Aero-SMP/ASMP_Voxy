package me.cortex.voxy.debug;

import java.util.ArrayList;

/** Pure debug-only interpretation of one completed GPU audit frame. */
final class DetailDecision {
    record Input(boolean viewportValid, long visited, long missingVisibleRoots,
                 long blockedDescent, long missingMesh, long coverageHoles,
                 long requestOverflow, long renderOverflow, long traversalOverflow,
                 boolean rootListOverflow,
                 boolean auditOverflow) {}

    record Result(boolean conclusive, boolean fullDetail, String blockers) {}

    private DetailDecision() {}

    static Result evaluate(Input input) {
        boolean conclusive = input.viewportValid && !input.rootListOverflow
                && !input.auditOverflow
                && input.requestOverflow == 0 && input.renderOverflow == 0
                && input.traversalOverflow == 0;
        ArrayList<String> blockers = new ArrayList<>();
        if (!conclusive) blockers.add("inconclusive");
        if (input.visited == 0) blockers.add("noVisitedNodes");
        add(blockers, "missingRoots", input.missingVisibleRoots);
        add(blockers, "blockedDescent", input.blockedDescent);
        add(blockers, "missingMesh", input.missingMesh);
        add(blockers, "coverageHoles", input.coverageHoles);
        add(blockers, "requestOverflow", input.requestOverflow);
        add(blockers, "renderOverflow", input.renderOverflow);
        add(blockers, "traversalOverflow", input.traversalOverflow);
        if (input.rootListOverflow) blockers.add("rootListOverflow");
        boolean full = conclusive && input.visited > 0 && input.missingVisibleRoots == 0
                && input.blockedDescent == 0 && input.missingMesh == 0
                && input.coverageHoles == 0;
        return new Result(conclusive, full, blockers.isEmpty() ? "none" : String.join(",", blockers));
    }

    private static void add(ArrayList<String> blockers, String name, long count) {
        if (count != 0) blockers.add(name + '=' + count);
    }
}
