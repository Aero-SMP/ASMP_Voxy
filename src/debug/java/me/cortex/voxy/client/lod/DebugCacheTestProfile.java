package me.cortex.voxy.client.lod;

import java.io.IOException;
import java.nio.file.*;
import java.util.UUID;
import net.minecraft.client.Minecraft;

/** Authenticated updater opt-in, debug only. No cache deletion or server-side changes. */
final class DebugCacheTestProfile {
    record Profile(UUID request, String namespace, long expires, int delayMillis) {}
    private static final String FILE = "cache-test-profile.txt";
    private static volatile Profile loaded;
    private static volatile boolean initialized;
    private static final java.util.concurrent.atomic.AtomicLong nextProbe = new java.util.concurrent.atomic.AtomicLong();

    static Profile parse(String text, long now) {
        String[] parts = text.trim().split(" ");
        if (parts.length != 4) throw new IllegalArgumentException("cache profile needs four fields");
        UUID request = UUID.fromString(parts[0]);
        String namespace = parts[1].equals("off") ? "off" : UUID.fromString(parts[1]).toString();
        long expires = Long.parseLong(parts[2]);
        int delay = Integer.parseInt(parts[3]);
        if (expires <= now || expires - now > 30 * 60_000L || delay < 0 || delay > 60_000
                || namespace.equals("off") && delay != 0) throw new IllegalArgumentException("invalid cache profile lease");
        return new Profile(request, namespace, expires, delay);
    }

    static boolean install(Path game, String text) throws IOException {
        Profile profile = parse(text, System.currentTimeMillis());
        Path file = game.resolve(".voxy-updater").resolve(FILE);
        if (Files.isSymbolicLink(file) || Files.isSymbolicLink(file.getParent())) throw new IOException("linked cache profile path");
        if (Files.isRegularFile(file)) {
            if (Files.size(file) > 200) throw new IOException("oversized cache profile");
            if (Files.readString(file).trim().split(" ")[0].equals(profile.request().toString())) return false;
        }
        Files.createDirectories(file.getParent());
        Path temporary = file.resolveSibling(FILE + ".pending");
        if (Files.isSymbolicLink(temporary)) throw new IOException("linked cache profile temporary");
        Files.writeString(temporary, text.trim());
        Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        return true;
    }

    private static Profile profile() {
        if (!initialized) synchronized (DebugCacheTestProfile.class) {
            if (!initialized) {
                try {
                    Path file = Minecraft.getInstance().gameDirectory.toPath().resolve(".voxy-updater").resolve(FILE);
                    if (Files.isRegularFile(file) && !Files.isSymbolicLink(file) && Files.size(file) <= 200) {
                        loaded = parse(Files.readString(file), System.currentTimeMillis());
                    }
                } catch (Exception invalid) { loaded = null; }
                initialized = true;
            }
        }
        return loaded;
    }

    static Path namespace(Path normal) {
        var p = profile();
        // Stable for this JVM: lease expiry releases holds, never redirects an open cache.
        return p == null || p.namespace().equals("off") ? normal : normal.resolve("test-" + p.namespace());
    }

    static boolean canDelete(Path path) {
        var p = profile();
        if (p == null || p.namespace().equals("off")) return true;
        for (Path component : path) if (component.toString().equals("test-" + p.namespace())) return true;
        return false;
    }

    static void inventoryDelay(Path root) throws IOException {
        var p = profile();
        if (p == null || p.namespace().equals("off") || p.delayMillis() == 0) return;
        long duration = Math.max(0, Math.min(p.expires() - System.currentTimeMillis(), p.delayMillis()));
        long until = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(duration);
        ClientLodDebug.workerEvidence("CACHE_TEST inventoryHold namespace=" + p.namespace() + " durationMillis=" + duration);
        try {
            while (System.nanoTime() < until) Thread.sleep(10);
        } catch (InterruptedException closed) {
            Thread.currentThread().interrupt();
            throw new IOException("cache test inventory hold cancelled", closed);
        }
        ClientLodDebug.workerEvidence("CACHE_TEST inventoryReleased namespace=" + p.namespace());
    }

    static void verifyChain(CompletedSectionCache cache, LocalSection fine) {
        var p = profile();
        if (p == null || p.namespace().equals("off") || p.expires() <= System.currentTimeMillis()
                || me.cortex.voxy.client.core.rendering.SectionKey.level(fine.key()) != 0) return;
        long now = System.nanoTime(), next = nextProbe.get();
        if (now < next || !nextProbe.compareAndSet(next, now + 1_000_000_000L)) return;
        var evidence = new StringBuilder("CACHE_TEST_CHAIN namespace=").append(p.namespace())
                .append(" world=").append(java.util.HexFormat.of().formatHex(cache.world.bytes()))
                .append(" fineKey=").append(fine.key());
        boolean ready = true;
        try (var codec = new LocalSectionCodec()) {
            var sections = cache.directory(fine.region());
            for (int level = 4; level >= 0; level--) {
                long key = me.cortex.voxy.client.core.rendering.SectionKey.pack(level,
                        me.cortex.voxy.client.core.rendering.SectionKey.x(fine.key()) >> level,
                        me.cortex.voxy.client.core.rendering.SectionKey.y(fine.key()) >> level,
                        me.cortex.voxy.client.core.rendering.SectionKey.z(fine.key()) >> level);
                var section = sections.get(key);
                boolean valid = section != null && section.kind() != LocalSection.ABSENT;
                if (valid) {
                    if (level > 0) {
                        int shift = level - 1;
                        int child = (me.cortex.voxy.client.core.rendering.SectionKey.x(fine.key()) >> shift & 1)
                                | (me.cortex.voxy.client.core.rendering.SectionKey.z(fine.key()) >> shift & 1) << 1
                                | (me.cortex.voxy.client.core.rendering.SectionKey.y(fine.key()) >> shift & 1) << 2;
                        valid &= (section.children() & 1 << child) != 0;
                    }
                    if (valid && section.kind() == LocalSection.DATA) {
                        valid = cache.get(section, codec, (name, biome) -> 0) != null;
                    }
                    evidence.append(" lod").append(level).append('=').append(key).append(':').append(valid)
                            .append(':').append(java.util.HexFormat.of().formatHex(section.catalog().bytes()));
                } else evidence.append(" lod").append(level).append("=missing");
                ready &= valid;
            }
        } catch (Exception failure) { ready = false; evidence.append(" error=").append(failure.getClass().getSimpleName()); }
        evidence.append(" ready=").append(ready);
        ClientLodDebug.workerEvidence(evidence.toString());
    }
}
