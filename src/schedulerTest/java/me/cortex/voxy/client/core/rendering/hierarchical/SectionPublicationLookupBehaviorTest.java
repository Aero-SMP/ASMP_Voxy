package me.cortex.voxy.client.core.rendering.hierarchical;

import me.cortex.voxy.client.core.rendering.SectionKey;
import me.cortex.voxy.client.core.rendering.building.BuiltSection;
import me.cortex.voxy.client.core.rendering.section.BasicAsyncGeometryManager;
import me.cortex.voxy.common.util.MemoryBuffer;
import java.lang.reflect.Field;
import java.util.*;

/** Real publication operations; only allocator storage and fence advancement are headless. */
final class SectionPublicationLookupBehaviorTest {
    static void run() {
        int[] baseline = null;
        for (int pending : new int[]{0, 64, 4096}) {
            int[] counts = isolatedLookupAndLateCallbacks(pending);
            if (baseline == null) baseline = counts;
            check(Arrays.equals(baseline, counts), "identity lookup grew with unrelated publications");
            System.out.println("publication lookup unrelated=" + pending + " mapOperations="
                    + Arrays.toString(counts) + " traversals=0");
        }
        for (boolean reverse : new boolean[]{false, true}) {
            for (boolean batch : new boolean[]{false, true}) siblings(reverse, batch);
        }
        blockedTopologyRetries();
        System.out.println("section publication isolation, sibling orders, retry and stale-fence tests passed");
    }

    private static final class Buffer extends MemoryBuffer {
        int frees;
        Buffer() { super(1024); }
        @Override public void free() { super.free(); frees++; }
    }

    /** Count actual keyed map operations; fail on any traversal during the isolated operations. */
    private static final class ProbeMap extends HashMap<Object, Object> {
        boolean measuring;
        int operations;
        private void lookup() { if (measuring) operations++; }
        private void traversal() { check(!measuring, "publication identity lookup traversed a map"); }
        @Override public Object get(Object key) { lookup(); return super.get(key); }
        @Override public boolean containsKey(Object key) { lookup(); return super.containsKey(key); }
        @Override public Object put(Object key, Object value) { lookup(); return super.put(key, value); }
        @Override public Object remove(Object key) { lookup(); return super.remove(key); }
        @Override public boolean remove(Object key, Object value) { lookup(); return super.remove(key, value); }
        @Override public Set<Map.Entry<Object, Object>> entrySet() { traversal(); return super.entrySet(); }
        @Override public Set<Object> keySet() { traversal(); return super.keySet(); }
        @Override public Collection<Object> values() { traversal(); return super.values(); }
    }

    private static final String[] MAPS = {"stagedGeometry", "committedGeometry", "committedPositions",
            "publishedRevisions", "rolledBackGeometry"};

    private static int[] isolatedLookupAndLateCallbacks(int unrelated) {
        var allocator = new BasicAsyncGeometryManager(16, 16384);
        var nodes = new NodeManager(16384, allocator);
        List<ProbeMap> maps = new ArrayList<>();
        for (String name : MAPS) {
            var map = new ProbeMap(); set(nodes, name, map); maps.add(map);
        }
        long target = root(0);
        nodes.insertTopLevelNode(target);
        // Mix all four publication lifetimes. Empty unrelated sections isolate lookup from
        // allocation size and topology traversal, while using real staging/commit operations.
        for (int i = 1; i <= unrelated; i++) {
            long key = root(i), revision = unrelatedRevision(i);
            nodes.insertTopLevelNode(key);
            check(nodes.stageGeometryResult(BuiltSection.emptyWithChildren(key, revision, (byte) 0)) != null,
                    "unrelated staging failed");
            if (i % 4 != 0) nodes.commitSection(revision, key);
            if (i % 4 == 2) nodes.rollbackSection(revision, key);
            if (i % 4 == 3) check(nodes.finalizeSection(revision, key), "unrelated finalization failed");
        }
        var before = unrelatedSnapshot(maps, target);
        Buffer old = new Buffer(), cancelled = new Buffer(), newer = new Buffer();
        List<Integer> counts = new ArrayList<>();
        List<Runnable> phases = List.of(
                () -> nodes.rollbackSection(99999, target), // failure before staging
                () -> nodes.completeSectionRollback(99999, target),
                () -> nodes.stageGeometryResult(mesh(target, 1, old, 0)),
                () -> nodes.commitSection(1, target),
                () -> check(nodes.finalizeSection(1, target), "first install blocked"),
                () -> nodes.stageGeometryResult(mesh(target, 2, cancelled, 0)),
                () -> nodes.commitSection(2, target),
                () -> { check(!old.isFreed(), "old mesh freed before fence"); nodes.rollbackSection(2, target); },
                () -> { check(!cancelled.isFreed(), "rollback skipped fence");
                    nodes.stageGeometryResult(mesh(target, 3, newer, 0)); },
                () -> nodes.commitSection(3, target),
                () -> { nodes.rollbackSection(2, target); check(nodes.finalizeSection(2, target), "old finalize blocked"); },
                () -> nodes.completeSectionRollback(2, target),
                () -> { nodes.completeSectionRollback(2, target);
                    check(cancelled.frees == 1 && !old.isFreed() && !newer.isFreed(), "old callback freed wrong mesh");
                    check(maps.get(2).containsKey(target), "old callback cleared new position ownership"); },
                () -> check(nodes.finalizeSection(3, target), "new finalization blocked"),
                () -> { nodes.rollbackSection(1, target); nodes.completeSectionRollback(1, target);
                    nodes.finalizeSection(1, target);
                    check(maps.get(3).get(target).equals(3L), "old callback overwrote published revision"); }
        );
        for (Runnable phase : phases) {
            maps.forEach(map -> { map.operations = 0; map.measuring = true; });
            try { phase.run(); } finally { maps.forEach(map -> map.measuring = false); }
            counts.add(maps.stream().mapToInt(map -> map.operations).sum());
            check(before.equals(unrelatedSnapshot(maps, target)), "unrelated publication lifetime changed");
        }
        check(old.frees == 1 && cancelled.frees == 1 && !newer.isFreed(), "replacement fence lifetime incorrect");
        check(nodes.retirePublication(4, 1, target), "stale retirement unexpectedly blocked");
        nodes.finalizeSection(4, target);
        check(!newer.isFreed() && maps.get(3).get(target).equals(3L), "stale retirement erased new mesh");
        check(nodes.retirePublication(5, 3, target), "metadata retirement blocked");
        check(!newer.isFreed(), "metadata retirement skipped fence");
        check(nodes.finalizeSection(5, target) && newer.frees == 1, "retirement did not free exactly once");
        check(before.equals(unrelatedSnapshot(maps, target)), "retirement touched unrelated publications");
        for (int i = 1; i <= unrelated; i++) {
            nodes.rollbackSection(unrelatedRevision(i), root(i));
            nodes.completeSectionRollback(unrelatedRevision(i), root(i));
            nodes.removeTopLevelNode(root(i));
        }
        nodes.removeTopLevelNode(target);
        check(allocator.getSectionCount() == 0, "isolation fixture leaked allocations");
        return counts.stream().mapToInt(Integer::intValue).toArray();
    }

    private static List<Map<Object, Object>> unrelatedSnapshot(List<ProbeMap> maps, long target) {
        List<Map<Object, Object>> result = new ArrayList<>();
        for (var map : maps) {
            Map<Object, Object> snapshot = new HashMap<>(map);
            snapshot.keySet().removeIf(key -> key instanceof Long position ? position == target
                    : (long) get(key, "position") == target);
            result.add(snapshot);
        }
        return result;
    }

    private static void siblings(boolean reverse, boolean batch) {
        var allocator = new BasicAsyncGeometryManager(16, 16384);
        var nodes = new NodeManager(1024, allocator);
        long parent = root(0);
        Buffer fallback = new Buffer();
        nodes.insertTopLevelNode(parent); stage(nodes, mesh(parent, 1, fallback, 255));
        check(nodes.finalizeSection(1, parent), "parent did not publish");
        List<Buffer> buffers = new ArrayList<>();
        for (int i = 0; i < 8; i++) buffers.add(new Buffer());
        for (int i = 0; i < 8; i++) {
            int child = reverse ? 7 - i : i;
            long position = child(child);
            check(nodes.ensureHierarchyOwner(position), "sibling owner missing");
            stage(nodes, mesh(position, 10 + child, buffers.get(child), 0));
            if (!batch) check(nodes.finalizeSection(10 + child, position), "sibling did not finalize");
            check(!fallback.isFreed(), "sibling publication lost parent fallback");
        }
        if (batch) for (int i = 0; i < 8; i++) {
            int child = reverse ? i : 7 - i;
            check(nodes.finalizeSection(10 + child, child(child)), "batch sibling did not finalize");
        }
        NodeStore data = (NodeStore) get(nodes, "nodeData");
        int parentId = active(nodes).get(parent) & NodeManager.NODE_ID_MSK;
        check(data.getChildPtrCount(parentId) == 8, "shared request did not install complete topology");
        for (int i = 0; i < 8; i++) {
            int state = active(nodes).get(child(i));
            check(state != -1 && (state & (3 << 30)) == 0, "sibling remained a request");
            check(!buffers.get(i).isFreed(), "sibling mesh freed at another sibling's fence");
        }
        nodes.removeTopLevelNode(parent);
        check(fallback.frees == 1 && buffers.stream().allMatch(b -> b.frees == 1)
                && allocator.getSectionCount() == 0, "sibling cleanup leaked/double-freed");
    }

    private static void blockedTopologyRetries() {
        var allocator = new BasicAsyncGeometryManager(16, 16384);
        var nodes = new NodeManager(1024, allocator);
        long parent = root(0), child = child(0), other = root(1);
        Buffer old = new Buffer(), replacement = new Buffer(), childMesh = new Buffer();
        nodes.insertTopLevelNode(parent); nodes.insertTopLevelNode(other);
        stage(nodes, mesh(parent, 1, old, 1)); nodes.finalizeSection(1, parent);
        check(nodes.ensureHierarchyOwner(child), "child owner missing");
        stage(nodes, mesh(child, 2, childMesh, 0)); nodes.finalizeSection(2, child);
        stage(nodes, mesh(parent, 3, replacement, 0));
        for (int attempt = 0; attempt < 3; attempt++) {
            check(!nodes.finalizeSection(3, parent) && !old.isFreed() && !replacement.isFreed(),
                    "blocked topology lost candidate or old geometry");
        }
        stage(nodes, BuiltSection.emptyWithChildren(other, 4, (byte) 0));
        check(nodes.finalizeSection(4, other), "unrelated publication stalled behind topology");
        check(!nodes.coarsenSubtree(5, parent), "coarsening bypassed pending publication");
        stage(nodes, BuiltSection.emptyWithChildren(child, 6, (byte) 0));
        check(!nodes.finalizeSection(3, parent), "parent removed committed child before child fence");
        check(nodes.finalizeSection(6, child) && childMesh.frees == 1, "child retirement failed");
        check(nodes.finalizeSection(3, parent) && old.frees == 1 && !replacement.isFreed(), "topology retry failed");
        check(nodes.finalizeSection(3, parent) && old.frees == 1, "repeat finalize double-freed");
        check(active(nodes).get(child) == -1, "removed child remained in topology");
        nodes.removeTopLevelNode(parent); nodes.removeTopLevelNode(other);
        check(replacement.frees == 1 && allocator.getSectionCount() == 0, "retry fixture leaked");
    }

    private static long root(int x) { return SectionKey.pack(4, x, 0, 0); }
    // Deliberately share a revision across unrelated positions in each lifetime: both key
    // components must participate even though production assigns a unique section revision.
    private static long unrelatedRevision(int i) { return i <= 4 ? 1 : 100 + i; }
    private static long child(int i) { return SectionKey.pack(3, i & 1, (i >> 2) & 1, (i >> 1) & 1); }
    private static BuiltSection mesh(long pos, long rev, Buffer data, int children) {
        return new BuiltSection(pos, rev, (byte) children, 0, data, new int[8]);
    }
    private static void stage(NodeManager nodes, BuiltSection section) {
        check(nodes.stageGeometryResult(section) != null, "missing owner");
        nodes.commitSection(section.sourceRevision, section.position);
    }
    private static it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap active(NodeManager nodes) {
        return (it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap) get(nodes, "activeSectionMap");
    }
    private static Object get(Object owner, String name) {
        try { Field f = owner.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(owner); }
        catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    private static void set(Object owner, String name, Object value) {
        try { Field f = owner.getClass().getDeclaredField(name); f.setAccessible(true); f.set(owner, value); }
        catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
