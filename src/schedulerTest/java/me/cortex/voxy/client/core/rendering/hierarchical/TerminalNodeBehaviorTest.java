package me.cortex.voxy.client.core.rendering.hierarchical;

import me.cortex.voxy.client.core.rendering.building.BuiltSection;
import me.cortex.voxy.client.core.rendering.section.BasicAsyncGeometryManager;

/** Package access bridge for the real NodeManager mutation boundary, not a second scheduler. */
public class TerminalNodeBehaviorTest extends NodeManager {
    public TerminalNodeBehaviorTest(int capacity, BasicAsyncGeometryManager geometry) { super(capacity, geometry); }
    public boolean stage(BuiltSection section) { return super.stageGeometryResult(section) != null; }
    @Override public boolean retirePublication(long revision, long expected, long key) {
        return super.retirePublication(revision, expected, key);
    }
}
