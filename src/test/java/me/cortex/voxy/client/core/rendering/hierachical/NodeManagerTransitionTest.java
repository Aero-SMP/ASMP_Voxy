package me.cortex.voxy.client.core.rendering.hierachical;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import me.cortex.voxy.client.core.rendering.SectionUpdateRouter;
import me.cortex.voxy.client.core.rendering.building.BuiltSection;
import me.cortex.voxy.client.core.rendering.section.geometry.BasicAsyncGeometryManager;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.world.WorldEngine;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class NodeManagerTransitionTest {
    @Test
    void requestsAddRelocateRemoveAndCollapseChildren() throws Exception {
        StubRouter router = new StubRouter();
        NodeManager manager = new NodeManager(1<<12,
                new BasicAsyncGeometryManager(1<<12, 1L<<24), router);
        long root = WorldEngine.getWorldSectionId(3, 0, 0, 0);

        manager.insertTopLevelNode(root);
        manager.processGeometryResult(BuiltSection.emptyWithChildren(root, (byte) 0b0000_0011));
        assertType(manager, root, 0);

        manager.processRequest(root);
        complete(manager, child(root, 0));
        complete(manager, child(root, 1));
        assertType(manager, root, 1);

        manager.processChildChange(root, (byte) 0b0000_0111);
        complete(manager, child(root, 2));
        assertType(manager, root, 1);
        assertType(manager, child(root, 0), 0);
        assertType(manager, child(root, 1), 0);
        assertType(manager, child(root, 2), 0);

        manager.processChildChange(root, (byte) 0b0000_0101);
        assertType(manager, child(root, 0), 0);
        assertMissing(manager, child(root, 1));
        assertType(manager, child(root, 2), 0);

        manager.processChildChange(root, (byte) 0);
        assertType(manager, root, 0);
        assertMissing(manager, child(root, 0));
        assertMissing(manager, child(root, 2));

        manager.removeTopLevelNode(root);
        assertMissing(manager, root);
        assertTrue(manager.getTopLevelNodeIds().isEmpty());
        assertTrue(router.flags.isEmpty());
    }

    @Test
    void collapseKeepsChildrenUntilMissingParentGeometryIsRebuilt() throws Exception {
        StubRouter router = new StubRouter();
        NodeManager manager = new NodeManager(1<<12,
                new BasicAsyncGeometryManager(1<<12, 1L<<24), router);
        long root = WorldEngine.getWorldSectionId(3, 0, 0, 0);
        long child = child(root, 0);

        manager.insertTopLevelNode(root);
        manager.processGeometryResult(nonEmpty(root, (byte) 1));
        manager.processRequest(root);
        complete(manager, child);
        manager.removeNodeGeometry(root);

        manager.processChildChange(root, (byte) 0);
        assertType(manager, root, 1);
        assertType(manager, child, 0);

        manager.processGeometryResult(BuiltSection.empty(root));
        assertType(manager, root, 0);
        assertMissing(manager, child);
        manager.removeTopLevelNode(root);
    }

    private static void complete(NodeManager manager, long position) {
        manager.processGeometryResult(BuiltSection.empty(position));
    }

    private static BuiltSection nonEmpty(long position, byte children) {
        return new BuiltSection(position, children, 0, new MemoryBuffer(8).zero(), new int[8]);
    }

    private static long child(long parent, int child) {
        return WorldEngine.getWorldSectionId(WorldEngine.getLevel(parent)-1,
                (WorldEngine.getX(parent)<<1)|(child&1),
                (WorldEngine.getY(parent)<<1)|((child>>2)&1),
                (WorldEngine.getZ(parent)<<1)|((child>>1)&1));
    }

    private static void assertType(NodeManager manager, long position, int type) throws Exception {
        int state = states(manager).get(position);
        assertNotEquals(-1, state);
        assertEquals(type, state>>>30);
    }

    private static void assertMissing(NodeManager manager, long position) throws Exception {
        assertEquals(-1, states(manager).get(position));
    }

    private static Long2IntOpenHashMap states(NodeManager manager) throws Exception {
        Field field = NodeManager.class.getDeclaredField("activeSectionMap");
        field.setAccessible(true);
        return (Long2IntOpenHashMap) field.get(manager);
    }

    private static final class StubRouter extends SectionUpdateRouter {
        private final Map<Long, Integer> flags = new HashMap<>();

        @Override
        public boolean watch(long position, int types) {
            int old = this.flags.getOrDefault(position, 0);
            this.flags.put(position, old|types);
            return (old&types) != types;
        }

        @Override
        public boolean unwatch(long position, int types) {
            int old = this.flags.getOrDefault(position, 0);
            if (old == 0) throw new IllegalStateException("Not watched");
            int replacement = old&~types;
            if (replacement == 0) this.flags.remove(position);
            else this.flags.put(position, replacement);
            return replacement == 0;
        }

        @Override
        public int get(long position) {
            return this.flags.getOrDefault(position, 0);
        }
    }
}
