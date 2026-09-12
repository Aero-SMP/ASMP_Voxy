package me.cortex.voxy.client.lod;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import me.cortex.voxy.client.core.rendering.SectionKey;
import static me.cortex.voxy.client.lod.CacheStartupBehaviorTest.*;

/** Diagnostic benchmark, not a timing assertion. Reports journal-only warm-filesystem costs;
 * this is not end-to-end startup, retained heap, wire throughput or live frame-time evidence. */
public final class CompletedCacheCostBenchmark {
    public static void main(String[] args) throws Exception {
        var root = Files.createTempDirectory("voxy-completed-cost-");
        try {
            var a = fixture(1, 0, 240, 1); var b = fixture(2, 0, 224, 2);
            // Warm codec/class initialization and journal paths separately from measured files.
            measure(root.resolve("warm.vxlocal"), 341, 2, a, b, false);
            System.out.println("positions,versions,physical_bytes,unique_payload_bytes,minimum_current_binding_bytes,reopen_ns,cpu_ns,allocated_bytes");
            for (int positions : new int[]{5, 341, 4096}) for (int versions : new int[]{1, 8})
                measure(root.resolve(positions + "-" + versions + ".vxlocal"), positions, versions, a, b, true);
        } finally { cleanup(root); }
    }

    private static void measure(Path path, int positions, int versions, Fixture a, Fixture b, boolean report) throws Exception {
        try (var journal = CompletedSectionJournal.open(path, WORLD, 0, true)) {
            for (int version = 0; version < versions; version++) {
                var f = (version & 1) == 0 ? a : b;
                var source = LocalSection.from(f.index(), 340, f.catalog().fingerprint());
                for (int index = 0; index < positions; index++) {
                    long key = SectionKey.pack(0, index & 15, (index >>> 8) - 8, (index >>> 4) & 15);
                    journal.append(new LocalSection(key, source.kind(), 0, source.compressedBytes(),
                            source.canonicalBytes(), source.crc(), source.fingerprint(), source.catalog()), f.payload());
                }
            }
        }
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        if (bean.isThreadAllocatedMemorySupported()) bean.setThreadAllocatedMemoryEnabled(true);
        if (bean.isThreadCpuTimeSupported()) bean.setThreadCpuTimeEnabled(true);
        for (int run = 0; run < 3; run++) {
            long thread = Thread.currentThread().threadId();
            long allocated = bean.isThreadAllocatedMemoryEnabled() ? bean.getThreadAllocatedBytes(thread) : -1;
            long cpu = bean.isThreadCpuTimeEnabled() ? bean.getCurrentThreadCpuTime() : -1;
            long start = System.nanoTime();
            try (var journal = CompletedSectionJournal.open(path, WORLD, 0, false)) {
                check(journal.directory().size() == positions, "benchmark recovery lost positions");
                check(journal.payloadCount() == Math.min(2, versions), "benchmark duplicated shared payloads");
            }
            long elapsed = System.nanoTime() - start;
            long cpuElapsed = cpu < 0 ? -1 : bean.getCurrentThreadCpuTime() - cpu;
            long allocatedBytes = allocated < 0 ? -1 : bean.getThreadAllocatedBytes(thread) - allocated;
            if (report) System.out.printf(Locale.ROOT, "%d,%d,%d,%d,%d,%d,%d,%d%n", positions, versions,
                    Files.size(path), a.payload().length + (versions > 1 ? b.payload().length : 0),
                    positions * 120L, elapsed, cpuElapsed, allocatedBytes);
        }
    }
}
