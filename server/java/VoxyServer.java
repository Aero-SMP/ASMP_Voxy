package com.aerosmp.voxy.server;

import com.aerosmp.voxy.Common;
import com.aerosmp.voxy.Common.*;
import com.aerosmp.voxy.update.AutoUpdater;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.*;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.*;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.*;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.HandlerThread;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import java.io.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.*;

/** Saved source is read only. One publisher owns reduction; native streams immutable records. */
@Mod(value = "voxy_server", dist = Dist.DEDICATED_SERVER)
public final class VoxyServer {
    private AutoUpdater updater;
    private volatile Backend backend;
    public VoxyServer(IEventBus bus) {
        bus.addListener((RegisterPayloadHandlersEvent event) -> event.registrar("voxy").optional()
                .executesOn(HandlerThread.NETWORK).playBidirectional(Endpoint.TYPE, Endpoint.CODEC, (request, context) -> {
                    Backend active = backend; if (request.port() == 0 && active != null && active.endpoint != null) context.reply(active.endpoint);
                }));
        NeoForge.EVENT_BUS.addListener(this::start); NeoForge.EVENT_BUS.addListener(this::stop);
    }
    private void start(ServerStartedEvent event) {
        try { backend = new Backend(event.getServer().getWorldPath(LevelResource.ROOT), event.getServer().registryAccess().registryOrThrow(Registries.BIOME)); }
        catch (IOException error) { System.err.println("Voxy startup: " + error); }
        updater = new AutoUpdater(Path.of("."), "server", false, update -> {
            AutoUpdater.install(update); event.getServer().execute(() -> event.getServer().halt(false));
        }); updater.start();
    }
    private void stop(ServerStoppingEvent event) { if (updater != null) updater.close(); if (backend != null) backend.close(); backend = null; }
    private record Work(String dimension, Key key, Process process, boolean missing, long order) {}
    private record Chunk(int x, int z, int compression, byte[] bytes) {}
    private static final class Backend implements AutoCloseable {
        private final Path world, data = Path.of(".voxy/terrain/server").toAbsolutePath();
        private final Registry<Biome> biomes;
        private final PriorityBlockingQueue<Work> queue = new PriorityBlockingQueue<>(11,
                Comparator.comparing(Work::missing).reversed().thenComparing(Comparator.<Work>comparingInt(work -> work.key.level()).reversed()).thenComparingLong(Work::order));
        private final long[] cells = new long[Common.CELLS], first = new long[4096], second = new long[4096], samples = new long[8];
        private final java.security.MessageDigest fingerprint;
        private final ByteBuffer fingerprintWords = ByteBuffer.allocate(17);
        private final ByteBuffer regionHeader = ByteBuffer.allocate(4096);
        private final Thread supervisor, publisher;
        private volatile boolean closed;
        private volatile Process process;
        private volatile Endpoint endpoint;
        private long order, sourceBytes, decodedChunks, published;
        Backend(Path world, Registry<Biome> biomes) throws IOException {
            this.world = world.toAbsolutePath().normalize(); this.biomes = biomes;
            if (!Files.isDirectory(this.world)) throw new IOException("Missing source world");
            try { fingerprint = java.security.MessageDigest.getInstance("SHA-256"); }
            catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
            byte[] binary;
            try (InputStream input = VoxyServer.class.getResourceAsStream("/native/linux-x86_64/voxy-server")) {
                if (input == null || !System.getProperty("os.name").equals("Linux")) throw new IOException("Linux native backend missing"); binary = input.readAllBytes();
            }
            Path executable = data.getParent().resolve("bin").resolve(Common.hex(Common.hash(binary))).resolve("voxy-server");
            if (!Files.isRegularFile(executable) || !Arrays.equals(Common.hash(Files.readAllBytes(executable)), Common.hash(binary))) Common.atomic(executable, binary);
            if (!executable.toFile().setExecutable(true, true)) throw new IOException("Native executable permission");
            List<String> command = new ArrayList<>(); String launcher = System.getProperty("voxy.rust.launcher", "");
            if (!launcher.isBlank()) command.add(launcher);
            command.addAll(List.of(executable.toString(), "--world", this.world.toString(), "--data", data.toString(), "--listen", System.getProperty("voxy.listen", "0.0.0.0:25787")));
            publisher = Thread.ofPlatform().daemon().name("Voxy saved terrain").start(this::publish);
            supervisor = Thread.ofPlatform().daemon().name("Voxy native supervisor").start(() -> supervise(command));
        }
        private void supervise(List<String> command) {
            while (!closed) {
                try {
                    Process child = new ProcessBuilder(command).redirectErrorStream(true).start(); process = child;
                    if (closed) { child.destroy(); return; }
                    try (BufferedReader lines = child.inputReader(StandardCharsets.UTF_8)) {
                        for (String line; !closed && (line = lines.readLine()) != null;) {
                            String[] fields = line.split(" ");
                            if (fields[0].equals("VOXY_NEED") && fields.length == 6) {
                                Key key = new Key(Integer.parseInt(fields[2]), Integer.parseInt(fields[3]), Integer.parseInt(fields[4]), Integer.parseInt(fields[5]));
                                queue.add(new Work(fields[1], key, child, !Files.isRegularFile(record(fields[1], key)), order++));
                            } else {
                                System.out.println("[Voxy native] " + line);
                                if (fields[0].equals("VOXY_READY")) endpoint = new Endpoint(System.getProperty("voxy.host", ""),
                                        Integer.parseInt(fields[1].substring(9)), HexFormat.of().parseHex(fields[3].substring(12)));
                            }
                        }
                    }
                    System.out.println("[Voxy native] exit=" + child.waitFor());
                } catch (Exception error) { if (!closed) System.err.println("Voxy native: " + error); }
                finally { endpoint = null; Process child = process; if (child != null) child.destroy(); process = null; queue.clear(); }
                try { Thread.sleep(5000); } catch (InterruptedException stopped) { return; }
            }
        }
        private Path source(String dimension) {
            ResourceLocation name = ResourceLocation.parse(dimension);
            Path path = switch (dimension) { case "minecraft:overworld" -> world; case "minecraft:the_nether" -> world.resolve("DIM-1");
                case "minecraft:the_end" -> world.resolve("DIM1"); default -> world.resolve("dimensions").resolve(name.getNamespace()).resolve(name.getPath()); };
            path = path.normalize(); if (!path.startsWith(world)) throw new IllegalArgumentException("Dimension escapes source world"); return path;
        }
        private Path record(String dimension, Key key) {
            return Common.record(data.resolve("records").resolve(Common.hex(Common.hash(dimension.getBytes(StandardCharsets.UTF_8)))), key);
        }
        private void publish() {
            while (!closed) {
                Work work;
                try { work = queue.take(); } catch (InterruptedException stopped) { return; }
                try { if (work.process.isAlive()) build(work); }
                catch (Exception error) { if (!closed) System.err.println("Voxy saved source " + work.key + ": " + error); }
                finally {
                    try { work.process.getOutputStream().write((Common.hex(Common.hash(work.dimension.getBytes(StandardCharsets.UTF_8))) + "_" + work.key.filename() + "\n").getBytes(StandardCharsets.UTF_8)); work.process.getOutputStream().flush(); }
                    catch (IOException stopped) { /* Native restart owns its fresh request lifecycle. */ }
                }
            }
        }
        private void build(Work work) throws IOException {
            long start = System.nanoTime(), beforeBytes = sourceBytes;
            Key key = work.key; int scale = 1 << key.level(), ox = Math.multiplyExact(key.x(), key.size()), oy = Math.multiplyExact(key.y(), key.size()), oz = Math.multiplyExact(key.z(), key.size());
            Path source = source(work.dimension).resolve("region"), record = record(work.dimension, key);
            if (!Files.isDirectory(source)) return;
            int minX = Math.floorDiv(ox - scale, 16), maxX = Math.floorDiv(ox + 33 * scale - 1, 16), minZ = Math.floorDiv(oz - scale, 16), maxZ = Math.floorDiv(oz + 33 * scale - 1, 16);
            Path stamp = record.resolveSibling(record.getFileName() + ".stamp"); byte[] previous = Files.isRegularFile(stamp) ? Files.readAllBytes(stamp) : new byte[0];
            byte[] metadata = metadata(source, minX, maxX, minZ, maxZ);
            if (Files.isRegularFile(record) && previous.length == 64 && Arrays.equals(metadata, Arrays.copyOf(previous, 32))) return;
            List<Chunk> chunks = read(source, minX, maxX, minZ, maxZ);
            fingerprint.reset();
            for (Chunk chunk : chunks) {
                fingerprintWords.clear(); fingerprintWords.putInt(chunk.x).putInt(chunk.z).putInt(chunk.compression);
                fingerprint.update(fingerprintWords.array(), 0, 12); fingerprint.update(chunk.bytes);
            }
            byte[] content = fingerprint.digest();
            if (!Arrays.equals(metadata, metadata(source, minX, maxX, minZ, maxZ))) throw new IOException("Saved source changed during snapshot");
            if (Files.isRegularFile(record) && previous.length == 64 && Arrays.equals(content, Arrays.copyOfRange(previous, 32, 64))) { Common.atomic(stamp, metadata, content); return; }
            Arrays.fill(cells, (long)biomes.getId(biomes.get(ResourceLocation.withDefaultNamespace("plains"))) << 32 | 0xf0L << 48);
            ListTag entities = new ListTag(); int children = 0; boolean available = false;
            for (Chunk chunk : chunks) {
                CompoundTag nbt;
                try (DataInputStream input = new DataInputStream(RegionFileVersion.fromId(chunk.compression).wrap(new ByteArrayInputStream(chunk.bytes)))) { nbt = NbtIo.read(input); }
                if (nbt == null || nbt.getInt("xPos") != chunk.x || nbt.getInt("zPos") != chunk.z) throw new IOException("Invalid saved chunk coordinates"); decodedChunks++;
                ListTag sections = nbt.getList("sections", Tag.TAG_COMPOUND); Map<Integer, CompoundTag> byY = new HashMap<>();
                for (Tag sectionTag : sections) {
                    CompoundTag section = (CompoundTag)sectionTag; int sy = section.getByte("Y"); byY.put(sy, section);
                    int wy = sy * 16; if (wy + 16 <= oy - scale || wy >= oy + 33 * scale) continue;
                    CompoundTag blocks = section.getCompound("block_states"), biome = section.getCompound("biomes");
                    ListTag states = blocks.getList("palette", Tag.TAG_COMPOUND), names = biome.getList("palette", Tag.TAG_STRING);
                    int[] stateIds = new int[Math.max(1, states.size())], biomeIds = new int[Math.max(1, names.size())];
                    for (int i = 0; i < states.size(); i++) {
                        CompoundTag state = states.getCompound(i); if (!BuiltInRegistries.BLOCK.containsKey(ResourceLocation.parse(state.getString("Name")))) throw new IOException("Unknown saved block " + state);
                        stateIds[i] = Block.getId(NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), state));
                    }
                    biomeIds[0] = biomes.getId(biomes.get(ResourceLocation.withDefaultNamespace("plains")));
                    for (int i = 0; i < names.size(); i++) {
                        ResourceLocation name = ResourceLocation.parse(names.getString(i)); if (!biomes.containsKey(name)) throw new IOException("Unknown saved biome " + name); biomeIds[i] = biomes.getId(biomes.get(name));
                    }
                    BitStorage stateData = storage(blocks, stateIds.length, 4096, 4), biomeData = storage(biome, biomeIds.length, 64, 1);
                    byte[] blockLight = section.getByteArray("BlockLight"), skyLight = section.getByteArray("SkyLight");
                    long[] reduced = first, next = second; int side = 16 >> key.level();
                    int constantBlock = uniform(blockLight), constantSky = uniform(skyLight);
                    if (stateIds.length == 1 && biomeIds.length == 1 && constantBlock >= 0 && constantSky >= 0) {
                        Arrays.fill(first, 0, side*side*side, Integer.toUnsignedLong(stateIds[0]) | (long)biomeIds[0] << 32 | (long)(constantBlock | constantSky << 4) << 48);
                    } else {
                        for (int i = 0; i < 4096; i++) {
                            int x = i & 15, z = i >> 4 & 15, y = i >> 8;
                            first[i] = Integer.toUnsignedLong(stateIds[stateData.get(i)]) | (long)biomeIds[biomeData.get((x >> 2) + (z >> 2) * 4 + (y >> 2) * 16)] << 32
                                    | (long)(nibble(blockLight, i) | nibble(skyLight, i) << 4) << 48;
                        }
                        side = 16;
                        for (int level = 0; level < key.level(); level++) {
                            int half = side / 2;
                            for (int y = 0; y < half; y++) for (int z = 0; z < half; z++) for (int x = 0; x < half; x++) {
                                for (int i = 0; i < 8; i++) samples[i] = reduced[2*x+(i&1)+side*(2*z+(i>>1&1))+side*side*(2*y+(i>>2))];
                                next[x + half*z + half*half*y] = reduce(samples);
                            }
                            long[] swap = reduced; reduced = next; next = swap; side = half;
                        }
                    }
                    for (int y = 0; y < side; y++) for (int z = 0; z < side; z++) for (int x = 0; x < side; x++) {
                        int dx = Math.floorDiv(chunk.x * 16 + x * scale - ox, scale), dy = Math.floorDiv(wy + y * scale - oy, scale), dz = Math.floorDiv(chunk.z * 16 + z * scale - oz, scale);
                        if (dx < -1 || dy < -1 || dz < -1 || dx > 32 || dy > 32 || dz > 32) continue;
                        cells[dx + 1 + 34*(dz + 1) + 1156*(dy + 1)] = reduced[x + side*z + side*side*y];
                        if (dx >= 0 && dx < 32 && dy >= 0 && dy < 32 && dz >= 0 && dz < 32) { available = true; if (key.level() > 0) children |= 1 << ((dx >> 4) | (dz >> 4) << 1 | (dy >> 4) << 2); }
                    }
                }
                for (Tag entityTag : nbt.getList("block_entities", Tag.TAG_COMPOUND)) {
                    CompoundTag entity = (CompoundTag)entityTag; int x = entity.getInt("x"), y = entity.getInt("y"), z = entity.getInt("z");
                    if (x < ox-scale || y < oy-scale || z < oz-scale || x >= ox+33*scale || y >= oy+33*scale || z >= oz+33*scale) continue;
                    CompoundTag section = byY.get(y >> 4); if (section == null) continue;
                    CompoundTag blocks = section.getCompound("block_states"); ListTag palette = blocks.getList("palette", Tag.TAG_COMPOUND); if (palette.isEmpty()) continue;
                    CompoundTag copy = entity.copy(); copy.put("voxy_state", palette.getCompound(storage(blocks, palette.size(), 4096, 4).get((x&15)+(z&15)*16+(y&15)*256)).copy()); entities.add(copy);
                }
            }
            if (!available) return;
            CompoundTag tag = new CompoundTag(); ListTag palette = new ListTag(); Long2IntOpenHashMap indices = new Long2IntOpenHashMap();
            for (long cell : cells) if (!indices.containsKey(cell)) {
                CompoundTag entry = new CompoundTag(); entry.put("state", NbtUtils.writeBlockState(Block.stateById((int)cell)));
                entry.putString("biome", biomes.getKey(biomes.byId((int)(cell >>> 32 & 65535))).toString()); entry.putByte("light", (byte)(cell >>> 48)); indices.put(cell, palette.size()); palette.add(entry);
            }
            SimpleBitStorage packed = new SimpleBitStorage(Math.max(1, 32 - Integer.numberOfLeadingZeros(palette.size()-1)), Common.CELLS);
            for (int i = 0; i < cells.length; i++) packed.set(i, indices.get(cells[i]));
            tag.put("palette", palette); tag.putLongArray("data", packed.getRaw()); tag.putByte("children", (byte)children); tag.put("entities", entities);
            byte[] payload = Common.encode(tag), hash = Common.hash(payload), old;
            try (InputStream input = Files.newInputStream(record)) { old = input.readNBytes(32); } catch (NoSuchFileException absent) { old = new byte[0]; }
            if (!Arrays.equals(hash, old)) { Common.atomic(record, hash, payload); published++; }
            Common.atomic(stamp, metadata, content);
            System.out.println("VOXY_PUBLISH key=" + key + " source_bytes=" + sourceBytes + " read_bytes=" + (sourceBytes-beforeBytes) + " source_chunks=" + chunks.size() + " decoded_chunks=" + decodedChunks + " records=" + published + " payload_bytes=" + payload.length + " build_ms=" + (System.nanoTime()-start)/1_000_000);
        }
        private byte[] metadata(Path root, int minX, int maxX, int minZ, int maxZ) throws IOException {
            fingerprint.reset();
            fingerprintWords.clear(); fingerprintWords.putLong(Files.getLastModifiedTime(root).to(TimeUnit.NANOSECONDS));
            fingerprint.update(fingerprintWords.array(), 0, Long.BYTES);
            for (int z = minZ >> 5; z <= maxZ >> 5; z++) for (int x = minX >> 5; x <= maxX >> 5; x++) {
                fingerprintWords.clear();
                try {
                    BasicFileAttributes attributes = Files.readAttributes(root.resolve("r." + x + "." + z + ".mca"), BasicFileAttributes.class);
                    fingerprintWords.put((byte)(attributes.isRegularFile() ? 1 : 0));
                    if (attributes.isRegularFile()) fingerprintWords.putLong(attributes.size()).putLong(attributes.lastModifiedTime().to(TimeUnit.NANOSECONDS));
                } catch (NoSuchFileException absent) { fingerprintWords.put((byte)0); }
                fingerprint.update(fingerprintWords.array(), 0, fingerprintWords.position());
            }
            return fingerprint.digest();
        }
        private List<Chunk> read(Path root, int minX, int maxX, int minZ, int maxZ) throws IOException {
            List<Chunk> chunks = new ArrayList<>();
            for (int rz = minZ >> 5; rz <= maxZ >> 5; rz++) for (int rx = minX >> 5; rx <= maxX >> 5; rx++) {
                Path file = root.resolve("r." + rx + "." + rz + ".mca"); if (!Files.isRegularFile(file)) continue;
                try (FileChannel input = FileChannel.open(file, StandardOpenOption.READ)) {
                    regionHeader.clear(); readAt(input, regionHeader, 0); regionHeader.flip();
                    for (int z = Math.max(minZ, rz*32); z <= Math.min(maxZ, rz*32+31); z++) for (int x = Math.max(minX, rx*32); x <= Math.min(maxX, rx*32+31); x++) {
                        int location = regionHeader.getInt(((x&31)+(z&31)*32)*4); if (location == 0) continue;
                        long offset = (long)(location >>> 8)*4096; int sectors = location & 255;
                        fingerprintWords.clear().limit(5); readAt(input, fingerprintWords, offset); fingerprintWords.flip(); int length = fingerprintWords.getInt(), compression = Byte.toUnsignedInt(fingerprintWords.get());
                        if (offset < 8192 || sectors == 0 || length < 1 || length > sectors*4096-4) throw new IOException("Invalid saved chunk location");
                        byte[] payload;
                        if ((compression & 128) != 0) payload = Files.readAllBytes(root.resolve("c."+x+"."+z+".mcc"));
                        else { ByteBuffer body = ByteBuffer.allocate(length-1); readAt(input, body, offset+5); payload = body.array(); }
                        compression &= 127; if (RegionFileVersion.fromId(compression) == null) throw new IOException("Unknown Minecraft compression");
                        sourceBytes += payload.length; chunks.add(new Chunk(x, z, compression, payload));
                    }
                }
            } return chunks;
        }
        private static void readAt(FileChannel input, ByteBuffer bytes, long position) throws IOException {
            while (bytes.hasRemaining()) { int count = input.read(bytes, position); if (count < 0) throw new EOFException("Truncated saved region"); position += count; }
        }
        private static BitStorage storage(CompoundTag tag, int count, int size, int minimum) {
            return count <= 1 ? new ZeroBitStorage(size) : new SimpleBitStorage(Math.max(minimum, 32-Integer.numberOfLeadingZeros(count-1)), size, tag.getLongArray("data"));
        }
        private static int uniform(byte[] bytes) {
            if (bytes.length == 0) return 0; int value = Byte.toUnsignedInt(bytes[0]); if ((value & 15) != (value >>> 4)) return -1;
            for (byte item : bytes) if (Byte.toUnsignedInt(item) != value) return -1; return value & 15;
        }
        private static int nibble(byte[] light, int index) { return light.length == 0 ? 0 : light[index >> 1] >> ((index & 1)*4) & 15; }
        private static long reduce(long[] values) {
            boolean uniform = true;
            for (int i = 1; i < values.length; i++) if (values[i] != values[0]) { uniform = false; break; }
            if (uniform) return values[0];
            int state = 0, stateCount = 0, biome = 0, biomeCount = 0, block = 0, sky = 0;
            for (long value : values) {
                int candidate = (int)value, name = (int)(value >>> 32 & 65535), a = 0, b = 0;
                for (long sample : values) { if ((int)sample == candidate) a++; if ((sample >>> 32 & 65535) == name) b++; }
                if (!Block.stateById(candidate).isAir() && a > stateCount) { state = candidate; stateCount = a; }
                if (b > biomeCount) { biome = name; biomeCount = b; }
                block = Math.max(block, (int)(value >>> 48 & 15)); sky = Math.max(sky, (int)(value >>> 52 & 15));
            } return Integer.toUnsignedLong(state) | (long)biome << 32 | (long)(block | sky << 4) << 48;
        }
        public void close() {
            closed = true; endpoint = null; publisher.interrupt(); supervisor.interrupt(); Process child = process;
            if (child != null) { child.destroy(); Thread.ofVirtual().start(() -> { try { if (!child.waitFor(10, TimeUnit.SECONDS)) child.destroyForcibly(); } catch (InterruptedException stopped) { child.destroyForcibly(); } }); }
        }
    }
}
