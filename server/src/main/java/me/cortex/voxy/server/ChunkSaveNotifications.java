package me.cortex.voxy.server;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** One owned pipe writer. Save hooks and Minecraft handlers only update coalesced state. */
final class ChunkSaveNotifications implements AutoCloseable {
    record Dimension(String name, String root, int minSectionY, int sectionCount,
                     boolean customBorder, double centerX, double centerZ, double size) {}
    private static final class Route {
        final byte[] token;
        int rate;
        boolean pending = true;
        CompletableFuture<Void> ready = new CompletableFuture<>();
        Route(byte[] token, int rate) { this.token = token.clone(); this.rate = rate; }
    }
    private final Object wake = new Object();
    private Map<ResourceKey<Level>, Long2ObjectOpenHashMap<BitSet>> dirty = new HashMap<>();
    private final Map<String, Dimension> dimensions = new HashMap<>();
    private final Map<String, Dimension> changedDimensions = new HashMap<>();
    private final Map<String, Route> routes = new HashMap<>();
    private Map<String, byte[]> revoked = new HashMap<>();
    private Process child;
    private boolean open = true;
    private final Thread drain = Thread.ofPlatform().daemon().name("Voxy native bridge").unstarted(this::run);

    void start() { drain.start(); }

    void attach(Process next) {
        synchronized (wake) {
            if (child == next) return;
            child = next;
            changedDimensions.putAll(dimensions);
            for (Route route : routes.values()) {
                route.pending = true;
                if (route.ready.isDone()) route.ready = new CompletableFuture<>();
            }
            wake.notifyAll();
        }
    }

    void dimension(Dimension dimension) {
        synchronized (wake) {
            if (!open || dimension.equals(dimensions.put(dimension.name(), dimension))) return;
            changedDimensions.put(dimension.name(), dimension);
            wake.notifyAll();
        }
    }

    CompletableFuture<Void> register(byte[] token, int rate) {
        String key = HexFormat.of().formatHex(token);
        synchronized (wake) {
            if (!open) return CompletableFuture.failedFuture(new IOException("native owner closed"));
            Route route = routes.computeIfAbsent(key, ignored -> new Route(token, rate));
            if (route.rate != rate) {
                route.rate = rate;
                route.pending = true;
                if (route.ready.isDone()) route.ready = new CompletableFuture<>();
            }
            revoked.remove(key);
            wake.notifyAll();
            return route.ready;
        }
    }

    void routeReady(Process target, String token, int rate) {
        CompletableFuture<Void> ready = null;
        synchronized (wake) {
            Route route = routes.get(token);
            if (target == child && route != null && route.rate == rate) ready = route.ready;
        }
        // Completion may advertise an endpoint; never hold the bridge lock during user callbacks.
        if (ready != null) ready.complete(null);
    }

    void revoke(byte[] token) {
        String key = HexFormat.of().formatHex(token);
        Route route;
        synchronized (wake) {
            route = routes.remove(key);
            if (route != null) revoked.put(key, route.token);
            wake.notifyAll();
        }
        if (route != null) route.ready.completeExceptionally(new IOException("Minecraft session ended"));
    }

    void saved(ResourceKey<Level> dimension, int x, int z) {
        synchronized (wake) {
            if (!open) return;
            long region = Integer.toUnsignedLong(x >> 5) | ((long) (z >> 5) << 32);
            var regions = dirty.computeIfAbsent(dimension, ignored -> new Long2ObjectOpenHashMap<>());
            regions.computeIfAbsent(region, ignored -> new BitSet(1024)).set((x & 31) | ((z & 31) << 5));
            wake.notifyAll();
        }
    }

    private boolean pending() {
        return !dirty.isEmpty() || !changedDimensions.isEmpty() || !revoked.isEmpty()
                || routes.values().stream().anyMatch(route -> route.pending);
    }

    private void run() {
        while (true) {
            Process target;
            Map<ResourceKey<Level>, Long2ObjectOpenHashMap<BitSet>> saves;
            Map<String, Dimension> definitions;
            Map<String, byte[]> removals;
            Map<String, Integer> registrations = new HashMap<>();
            synchronized (wake) {
                while (open && (child == null || !pending())) {
                    try { wake.wait(); } catch (InterruptedException ignored) { if (!open) return; }
                }
                if (!open) return;
                target = child;
                saves = dirty; dirty = new HashMap<>();
                definitions = new java.util.TreeMap<>(changedDimensions); changedDimensions.clear();
                removals = revoked; revoked = new HashMap<>();
                for (var entry : routes.entrySet()) if (entry.getValue().pending) {
                    registrations.put(entry.getKey(), entry.getValue().rate);
                    entry.getValue().pending = false;
                }
            }
            try {
                var output = target.getOutputStream();
                // Actual layouts precede registrations and saves. Native must never publish using
                // an assumed Overworld layout while discovering another dimension.
                for (Dimension dimension : definitions.values()) {
                    byte[] name = utf8(dimension.name()), root = utf8(dimension.root());
                    ByteBuffer frame = frame(1 + 2 + name.length + 2 + root.length + 8 + 1 + 24);
                    frame.put((byte) 4).putShort((short) name.length).put(name)
                            .putShort((short) root.length).put(root).putInt(dimension.minSectionY())
                            .putInt(dimension.sectionCount()).put((byte) (dimension.customBorder() ? 1 : 0))
                            .putDouble(dimension.centerX()).putDouble(dimension.centerZ()).putDouble(dimension.size());
                    write(target, output, frame);
                }
                for (var removal : removals.values()) write(target, output, frame(33).put((byte) 3).put(removal));
                for (var registration : registrations.entrySet()) {
                    write(target, output, frame(41).put((byte) 2)
                            .put(HexFormat.of().parseHex(registration.getKey())).putLong(registration.getValue()));
                }
                for (var dimension : saves.entrySet()) {
                    byte[] name = utf8(dimension.getKey().location().toString());
                    int count = 0;
                    for (BitSet slots : dimension.getValue().values()) count = Math.addExact(count, slots.cardinality());
                    ByteBuffer frame = frame(Math.addExact(7 + name.length, Math.multiplyExact(count, 8)));
                    frame.put((byte) 1).putShort((short) name.length).put(name).putInt(count);
                    for (var region : dimension.getValue().long2ObjectEntrySet()) {
                        int x = (int) region.getLongKey() << 5, z = (int) (region.getLongKey() >>> 32) << 5;
                        BitSet slots = region.getValue();
                        for (int slot = slots.nextSetBit(0); slot >= 0; slot = slots.nextSetBit(slot + 1))
                            frame.putInt(x + (slot & 31)).putInt(z + (slot >>> 5));
                    }
                    write(target, output, frame);
                }
                output.flush();
            } catch (IOException | RuntimeException failure) {
                synchronized (wake) {
                    if (!open) return;
                    merge(saves);
                    definitions.forEach(changedDimensions::putIfAbsent);
                    revoked.putAll(removals);
                    for (String key : registrations.keySet()) if (routes.containsKey(key)) routes.get(key).pending = true;
                    if (child == target) child = null;
                }
                LoggerFactory.getLogger("Voxy Rust Backend").warn("Native bridge delivery deferred until backend replacement", failure);
            }
        }
    }

    private void write(Process target, java.io.OutputStream output, ByteBuffer frame) throws IOException {
        synchronized (wake) {
            if (!open || child != target || !target.isAlive()) throw new IOException("backend child changed");
        }
        output.write(frame.array());
    }
    private static ByteBuffer frame(int length) { return ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN); }
    private static byte[] utf8(String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0 || bytes.length > 0xffff) throw new IOException("invalid IPC string");
        return bytes;
    }
    private void merge(Map<ResourceKey<Level>, Long2ObjectOpenHashMap<BitSet>> batch) {
        for (var dimension : batch.entrySet()) {
            var regions = dirty.computeIfAbsent(dimension.getKey(), ignored -> new Long2ObjectOpenHashMap<>());
            for (var region : dimension.getValue().long2ObjectEntrySet())
                regions.computeIfAbsent(region.getLongKey(), ignored -> new BitSet(1024)).or(region.getValue());
        }
    }

    @Override public void close() {
        java.util.List<CompletableFuture<Void>> pending;
        synchronized (wake) {
            open = false; child = null;
            pending = routes.values().stream().map(route -> route.ready).toList();
            routes.clear(); wake.notifyAll();
        }
        for (var ready : pending) ready.completeExceptionally(new IOException("native owner stopped"));
    }
}
