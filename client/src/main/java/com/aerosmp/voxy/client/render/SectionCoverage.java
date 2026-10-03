package com.aerosmp.voxy.client.render;

import com.aerosmp.voxy.terrain.SectionKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/** A parent is replaced only by a complete set of GPU-published children. */
public final class SectionCoverage {
    private SectionCoverage() {}
    public static List<SectionKey> select(List<SectionKey> demand, Function<SectionKey, Integer> published) {
        Set<SectionKey> wanted = Set.copyOf(demand);
        List<SectionKey> result = new ArrayList<>();
        for (SectionKey root : demand) if (root.level() == 4) select(root, wanted, published, result);
        return result;
    }
    public static boolean mayReplace(SectionKey key, int currentMask, int replacementMask,
                                      Set<SectionKey> demand, Predicate<SectionKey> ready) {
        if (key.level() == 0 || currentMask == 0) return true;
        boolean finer = false;
        for (int i = 0; i < 8; i++) {
            finer |= demand.contains(key.child(i));
            if ((currentMask & (1 << i)) != 0 && demand.contains(key.child(i)) && !ready.test(key.child(i))) return true;
        }
        if (!finer) return true;
        for (int i = 0; i < 8; i++) if ((replacementMask & (1 << i)) != 0 && demand.contains(key.child(i)) && !ready.test(key.child(i))) return false;
        return true;
    }
    private static void select(SectionKey key, Set<SectionKey> wanted,
                               Function<SectionKey, Integer> published, List<SectionKey> result) {
        Integer mask = published.apply(key);
        if (key.level() == 0) { if (mask != null) result.add(key); return; }
        boolean finer = false, complete = mask != null && mask != 0;
        for (int i = 0; i < 8; i++) {
            SectionKey child = key.child(i);
            finer |= wanted.contains(child);
            if (mask != null && (mask & (1 << i)) != 0 && wanted.contains(child) && published.apply(child) == null) complete = false;
        }
        if (mask != null && (!finer || !complete)) { result.add(key); return; }
        for (int i = 0; i < 8; i++) {
            SectionKey child = key.child(i);
            if ((mask == null && wanted.contains(child)) || (mask != null && (mask & (1 << i)) != 0 && wanted.contains(child)))
                select(child, wanted, published, result);
        }
    }
}
