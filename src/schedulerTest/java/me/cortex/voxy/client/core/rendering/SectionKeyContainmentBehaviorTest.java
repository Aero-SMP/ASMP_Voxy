package me.cortex.voxy.client.core.rendering;

import me.cortex.voxy.client.core.rendering.hierarchical.NodeManager;
import me.cortex.voxy.client.lod.ClientSession;

import java.lang.reflect.Method;
import java.util.Random;

/** Geometric oracle for the real helper and both private production wrappers. */
public final class SectionKeyContainmentBehaviorTest {
    private record Position(int level, long x, long y, long z) {}
    private static long cases;

    public static void main(String[] args) throws Exception {
        // Neither wrapper constructs a session/renderer; their static initializers are GL-free.
        Method[] wrappers = {wrapper(ClientSession.class), wrapper(NodeManager.class)};
        int[][] coordinates = {{-8_388_608, -128, -8_388_608}, {8_388_607, 127, 8_388_607},
                {-8_388_608, 127, 8_388_607}, {8_388_607, -128, -8_388_608},
                {-8_388_607, -127, 8_388_606}, {8_388_606, 126, -8_388_607},
                {-257, -128, 257}, {-129, 127, 129}, {-128, -127, 128}, {-127, 126, 127},
                {-33, -33, 33}, {-32, -32, 32}, {-31, -31, 31}, {-17, -17, 17},
                {-16, -16, 16}, {-15, -15, 15}, {-3, -3, 3}, {-2, -2, 2},
                {-1, -1, 1}, {0, 0, 0}, {1, 1, -1}, {2, 2, -2}, {3, 3, -3}};
        for (int ancestorLevel = 0; ancestorLevel < 16; ancestorLevel++) {
            for (int descendantLevel = 0; descendantLevel < 16; descendantLevel++) {
                int difference = ancestorLevel - descendantLevel;
                for (int[] xyz : coordinates) {
                    Position descendant = new Position(descendantLevel, xyz[0], xyz[1], xyz[2]);
                    Position ancestor = atLevel(descendant, ancestorLevel);
                    check(wrappers, packed(ancestor, 0), packed(descendant, 0), difference >= 0);
                    if (difference < 0) continue;
                    check(wrappers, packed(new Position(ancestorLevel, other(ancestor.x, 8_388_607), ancestor.y, ancestor.z), 0), packed(descendant, 0), false);
                    check(wrappers, packed(new Position(ancestorLevel, ancestor.x, other(ancestor.y, 127), ancestor.z), 0), packed(descendant, 0), false);
                    check(wrappers, packed(new Position(ancestorLevel, ancestor.x, ancestor.y, other(ancestor.z, 8_388_607)), 0), packed(descendant, 0), false);
                }
                if (difference < 0) continue;
                long width = 1L << difference;
                for (long edge : new long[]{-width - 1, -width, -width + 1, width - 1, width, width + 1}) {
                    for (Position descendant : new Position[]{new Position(descendantLevel, edge, 0, -edge),
                            new Position(descendantLevel, 0, Math.max(-128, Math.min(127, edge)), 0)}) {
                        check(wrappers, packed(atLevel(descendant, ancestorLevel), 0), packed(descendant, 0), true);
                    }
                }
            }
        }
        for (int level = 0; level < 16; level++) {
            Position descendant = new Position(level, -257, -127, 129);
            for (int aBits = 0; aBits < 16; aBits++) {
                for (int dBits = 0; dBits < 16; dBits++) {
                    check(wrappers, packed(descendant, aBits), packed(descendant, dBits), true);
                    check(wrappers, packed(atLevel(descendant, 15), aBits), packed(descendant, dBits), true);
                    if (level < 15) check(wrappers, packed(descendant, aBits), packed(atLevel(descendant, 15), dBits), false);
                }
            }
        }
        long[] arbitrary = {0, -1, Long.MIN_VALUE, Long.MAX_VALUE, 0x0123456789ABCDEFL,
                0xFEDCBA9876543210L, 0x800FFFFFFFFFFFFFL, 0x0FF000000000000FL};
        for (long ancestor : arbitrary) for (long descendant : arbitrary) oracleCheck(wrappers, ancestor, descendant);
        Random random = new Random(0x53EC710L);
        for (int i = 0; i < 4096; i++) {
            long descendant = random.nextLong();
            oracleCheck(wrappers, random.nextLong(), descendant);
            Position decoded = decoded(descendant);
            oracleCheck(wrappers, packed(atLevel(decoded, i % 16), i % 16), descendant);
        }
        System.out.println("section containment geometric oracle passed " + cases + " pairs across helper and both production wrappers");
    }

    private static Method wrapper(Class<?> owner) throws ReflectiveOperationException {
        Method method = owner.getDeclaredMethod("contains", long.class, long.class);
        method.setAccessible(true);
        return method;
    }

    private static Position atLevel(Position descendant, int level) {
        int difference = level - descendant.level;
        if (difference < 0) return new Position(level, descendant.x, descendant.y, descendant.z);
        long width = 1L << difference;
        return new Position(level, Math.floorDiv(descendant.x, width), Math.floorDiv(descendant.y, width), Math.floorDiv(descendant.z, width));
    }

    private static long other(long coordinate, long maximum) { return coordinate == maximum ? coordinate - 1 : coordinate + 1; }

    private static long packed(Position p, int reserved) {
        return p.level * 0x1000000000000000L + Math.floorMod(p.y, 256L) * 0x0010000000000000L
                + Math.floorMod(p.z, 16_777_216L) * 0x10000000L + Math.floorMod(p.x, 16_777_216L) * 16L + reserved;
    }

    private static Position decoded(long packed) {
        return new Position((int) Long.divideUnsigned(packed, 0x1000000000000000L),
                signedField(packed, 16L, 16_777_216L), signedField(packed, 0x0010000000000000L, 256L),
                signedField(packed, 0x10000000L, 16_777_216L));
    }

    private static long signedField(long packed, long unit, long values) {
        long field = Long.divideUnsigned(packed, unit) % values;
        return field < values / 2 ? field : field - values;
    }

    private static void oracleCheck(Method[] wrappers, long ancestor, long descendant) throws Exception {
        Position a = decoded(ancestor), d = decoded(descendant);
        int difference = a.level - d.level;
        boolean expected = false;
        if (difference >= 0) {
            long width = 1L << difference;
            expected = a.x == Math.floorDiv(d.x, width) && a.y == Math.floorDiv(d.y, width) && a.z == Math.floorDiv(d.z, width);
        }
        check(wrappers, ancestor, descendant, expected);
    }

    private static void check(Method[] wrappers, long ancestor, long descendant, boolean expected) throws Exception {
        if (SectionKey.contains(ancestor, descendant) != expected) throw new AssertionError("helper mismatch for " + ancestor + " / " + descendant);
        for (Method wrapper : wrappers) {
            if ((boolean) wrapper.invoke(null, ancestor, descendant) != expected) throw new AssertionError(wrapper.getDeclaringClass().getName() + " mismatch for " + ancestor + " / " + descendant);
        }
        cases++;
    }
}
