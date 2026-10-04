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
import java.util.Map;

/** Coalesces completed Anvil saves; the storage thread never waits for the child pipe. */
final class ChunkSaveNotifications implements AutoCloseable {
    private final Object wake = new Object();
    private Map<ResourceKey<Level>, Long2ObjectOpenHashMap<BitSet>> dirty = new HashMap<>();
    private Process child;
    private boolean open = true;
    private final Thread drain = Thread.ofPlatform().daemon().name("Voxy saved chunks").unstarted(this::run);

    void start() { drain.start(); }

    void attach(Process next) {
        synchronized (wake) { child = next; wake.notifyAll(); }
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

    private void run() {
        while (true) {
            Process target;
            Map<ResourceKey<Level>, Long2ObjectOpenHashMap<BitSet>> batch;
            synchronized (wake) {
                while (open && (child == null || dirty.isEmpty())) {
                    try { wake.wait(); } catch (InterruptedException ignored) { if (!open) return; }
                }
                if (!open) return;
                target = child;
                batch = dirty;
                dirty = new HashMap<>();
            }
            try {
                for (var dimension : batch.entrySet()) {
                    synchronized (wake) {
                        if (!open) return;
                        if (child != target || !target.isAlive()) throw new IOException("backend child changed");
                    }
                    byte[] name = dimension.getKey().location().toString().getBytes(StandardCharsets.UTF_8);
                    if (name.length == 0 || name.length > 1024) throw new IOException("invalid dimension name");
                    int count = 0;
                    for (BitSet slots : dimension.getValue().values()) count = Math.addExact(count, slots.cardinality());
                    var frame = ByteBuffer.allocate(Math.addExact(6 + name.length, Math.multiplyExact(count, 8)))
                            .order(ByteOrder.LITTLE_ENDIAN);
                    frame.putShort((short) name.length).put(name).putInt(count);
                    for (var region : dimension.getValue().long2ObjectEntrySet()) {
                        int x = (int) region.getLongKey() << 5, z = (int) (region.getLongKey() >>> 32) << 5;
                        BitSet slots = region.getValue();
                        for (int slot = slots.nextSetBit(0); slot >= 0; slot = slots.nextSetBit(slot + 1)) {
                            frame.putInt(x + (slot & 31)).putInt(z + (slot >>> 5));
                        }
                    }
                    target.getOutputStream().write(frame.array());
                    target.getOutputStream().flush();
                }
                synchronized (wake) { if (child != target) merge(batch); }
            } catch (IOException | RuntimeException failure) {
                synchronized (wake) {
                    if (!open) return;
                    merge(batch);
                    if (child == target) child = null;
                }
                LoggerFactory.getLogger("Voxy Rust Backend").warn(
                        "Completed saves retained until backend replacement; reconciliation remains active", failure);
            }
        }
    }

    private void merge(Map<ResourceKey<Level>, Long2ObjectOpenHashMap<BitSet>> batch) {
        for (var dimension : batch.entrySet()) {
            var regions = dirty.computeIfAbsent(dimension.getKey(), ignored -> new Long2ObjectOpenHashMap<>());
            for (var region : dimension.getValue().long2ObjectEntrySet()) {
                regions.computeIfAbsent(region.getLongKey(), ignored -> new BitSet(1024)).or(region.getValue());
            }
        }
    }

    @Override public void close() {
        synchronized (wake) { open = false; child = null; wake.notifyAll(); }
    }
}
