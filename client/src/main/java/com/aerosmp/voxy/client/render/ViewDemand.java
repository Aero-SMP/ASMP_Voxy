package com.aerosmp.voxy.client.render;

import com.aerosmp.voxy.terrain.SectionKey;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.Set;
import java.util.HashSet;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/** Camera demand only; the same geometric selection can drive a headless workload. */
public final class ViewDemand {
    private ViewDemand() {}
    public static List<SectionKey> sections(double x, double y, double z, int minY, int maxY, int radius) {
        return sections(x, y, z, minY, maxY, radius, key -> null);
    }
    public static List<SectionKey> sections(double x, double y, double z, int minY, int maxY, int radius,
                                             Function<SectionKey, Integer> children) {
        List<SectionKey> result = new ArrayList<>();
        int span = 32 << 4;
        for (int sx = floor(x - radius, span); sx <= floor(x + radius, span); sx++)
            for (int sz = floor(z - radius, span); sz <= floor(z + radius, span); sz++)
                for (int sy = Math.floorDiv(minY, span); sy <= Math.floorDiv(maxY - 1, span); sy++) {
                    SectionKey root = new SectionKey(4, sx, sy, sz);
                    if (horizontalDistance(root, x, z) <= (double)radius * radius)
                        add(root, x, y, z, minY, maxY, children, result);
                }
        result.sort(Comparator.comparingInt(SectionKey::level).reversed()
                .thenComparingDouble(key -> distance(key, x, y, z)));
        return List.copyOf(result);
    }
    private static void add(SectionKey key, double x, double y, double z, int minY, int maxY,
                            Function<SectionKey, Integer> children, List<SectionKey> result) {
        result.add(key);
        if (key.level() == 0 || distance(key, x, y, z) > key.size() * (double)key.size() * 2.25) return;
        Integer mask = children.apply(key);
        for (int i = 0; i < 8; i++) {
            if (mask != null && (mask & (1 << i)) == 0) continue;
            SectionKey child = key.child(i);
            if ((long)child.y() * child.size() < maxY && (long)(child.y() + 1) * child.size() > minY)
                add(child, x, y, z, minY, maxY, children, result);
        }
    }
    /** The exact render projection includes zoom, view bobbing and render resolution. */
    public static final class Camera {
        public final Vec3 position;
        public final Matrix4f matrix;
        public final int width, height;
        private final Frustum frustum;
        public Camera(Vec3 position, Matrix4f modelView, Matrix4f projection, int width, int height) {
            this.position = position; this.width = width; this.height = height;
            matrix = new Matrix4f(projection).mul(modelView);
            frustum = new Frustum(modelView, projection); frustum.prepare(position.x, position.y, position.z);
        }
        public boolean same(Camera other) {
            return other != null && position.equals(other.position) && matrix.equals(other.matrix)
                    && width == other.width && height == other.height;
        }
        public boolean visible(SectionKey key) {
            int size = key.size(); double x = (long)key.x() * size, y = (long)key.y() * size, z = (long)key.z() * size;
            return frustum.isVisible(new AABB(x, y, z, x + size, y + size, z + size));
        }
        // Rectangle, nearest depth and projected section diameter. Near-plane intersections stay eligible.
        public float[] project(SectionKey key) {
            float[] px = new float[8], py = new float[8];
            float minX = 1, minY = 1, maxX = 0, maxY = 0, depth = 1;
            int size = key.size();
            for (int i = 0; i < 8; i++) {
                Vector4f point = matrix.transform(new Vector4f(
                        (float)((long)key.x() * size - position.x) + (i & 1) * size,
                        (float)((long)key.y() * size - position.y) + ((i >> 2) & 1) * size,
                        (float)((long)key.z() * size - position.z) + ((i >> 1) & 1) * size, 1));
                if (point.w <= 0 || point.z <= -point.w) return new float[]{0, 0, 1, 1, 0, Float.POSITIVE_INFINITY};
                px[i] = point.x / point.w * .5f + .5f; py[i] = point.y / point.w * .5f + .5f;
                minX = Math.min(minX, px[i]); minY = Math.min(minY, py[i]);
                maxX = Math.max(maxX, px[i]); maxY = Math.max(maxY, py[i]);
                depth = Math.min(depth, point.z / point.w * .5f + .5f);
            }
            float area = cross(px, py, 0, 1, 4) + cross(px, py, 0, 1, 2) + cross(px, py, 0, 4, 2)
                    + cross(px, py, 7, 6, 3) + cross(px, py, 7, 6, 5) + cross(px, py, 7, 3, 5);
            minX = Math.clamp(minX, 0, 1); minY = Math.clamp(minY, 0, 1);
            maxX = Math.clamp(maxX, 0, 1); maxY = Math.clamp(maxY, 0, 1);
            float center = 1 - Math.min(1, (float)Math.hypot((minX + maxX) * .5f - .5f, (minY + maxY) * .5f - .5f) * 1.41421356f);
            float diameter = (float)Math.sqrt(area * .5f * width * height) * (1 + .25f * center);
            return new float[]{minX, minY, maxX, maxY, depth, diameter};
        }
        private static float cross(float[] x, float[] y, int a, int b, int c) {
            return Math.abs((x[b] - x[a]) * (y[c] - y[a]) - (x[c] - x[a]) * (y[b] - y[a]));
        }
    }
    public static List<SectionKey> sections(Camera camera, int minY, int maxY, int radius, float pixels,
            Function<SectionKey, Integer> children, Predicate<SectionKey> hidden, Set<SectionKey> refined) {
        List<SectionKey> result = new ArrayList<>(); Set<SectionKey> next = new HashSet<>();
        Vec3 pos = camera.position; int span = 512;
        for (int x = floor(pos.x - radius, span); x <= floor(pos.x + radius, span); x++)
            for (int z = floor(pos.z - radius, span); z <= floor(pos.z + radius, span); z++)
                for (int y = Math.floorDiv(minY, span); y <= Math.floorDiv(maxY - 1, span); y++) {
                    SectionKey root = new SectionKey(4, x, y, z);
                    if (horizontalDistance(root, pos.x, pos.z) <= (double)radius * radius)
                        add(root, camera, minY, maxY, pixels, children, hidden, refined, next, result);
                }
        refined.clear(); refined.addAll(next);
        // Coverage before refinement; within each level, highest screen-space benefit first.
        result.sort(Comparator.comparingInt(SectionKey::level).reversed()
                .thenComparing(Comparator.comparingDouble((SectionKey k) -> camera.project(k)[5]).reversed()));
        return List.copyOf(result);
    }
    private static void add(SectionKey key, Camera camera, int minY, int maxY, float pixels,
            Function<SectionKey, Integer> children, Predicate<SectionKey> hidden, Set<SectionKey> refined,
            Set<SectionKey> next, List<SectionKey> result) {
        if (!camera.visible(key)) return;
        result.add(key);
        if (key.level() == 0 || hidden.test(key)) return;
        float diameter = camera.project(key)[5];
        if (diameter < pixels * (refined.contains(key) ? .9f : 1f)) return;
        next.add(key); Integer mask = children.apply(key);
        for (int i = 0; i < 8; i++) {
            if (mask != null && (mask & (1 << i)) == 0) continue;
            SectionKey child = key.child(i);
            if ((long)child.y() * child.size() < maxY && (long)(child.y() + 1) * child.size() > minY)
                add(child, camera, minY, maxY, pixels, children, hidden, refined, next, result);
        }
    }
    private static int floor(double coordinate, int span) { return (int)Math.floor(coordinate / span); }
    public static double distance(SectionKey key, double x, double y, double z) {
        double dy = axis(y, (long)key.y() * key.size(), key.size());
        return horizontalDistance(key, x, z) + dy * dy;
    }
    public static double horizontalDistance(SectionKey key, double x, double z) {
        double dx = axis(x, (long)key.x() * key.size(), key.size());
        double dz = axis(z, (long)key.z() * key.size(), key.size());
        return dx * dx + dz * dz;
    }
    private static double axis(double point, long start, int span) {
        return Math.max(Math.max(start - point, point - start - span), 0);
    }
}
