package com.aerosmp.voxy;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.level.block.state.BlockState;

import tech.kwik.core.QuicClientConnection;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.cert.*;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;
import java.util.zip.*;

import javax.net.ssl.X509TrustManager;

/** The same independent paletted record is stored, transported, and decoded once. */
public final class Common {
    private static final System.Logger LOG = System.getLogger("Voxy cache");
    public static final int SIDE = 34, CELLS = SIDE * SIDE * SIDE;

    public record Key(int level, int x, int y, int z) {
        public Key {
            if (level < 0 || level > 4) throw new IllegalArgumentException("Invalid LOD");
        }

        public int size() {
            return 32 << level;
        }

        public Key parent() {
            return level == 4 ? null : new Key(level + 1, x >> 1, y >> 1, z >> 1);
        }

        public Key root() {
            return level == 4
                    ? this
                    : new Key(4, x >> (4 - level), y >> (4 - level), z >> (4 - level));
        }

        public Key child(int i) {
            return new Key(level - 1, x * 2 + (i & 1), y * 2 + (i >> 2 & 1), z * 2 + (i >> 1 & 1));
        }

        public String filename() {
            return level + "_" + x + "_" + y + "_" + z + ".vxs";
        }
    }

    public static Path record(Path directory, Key key) {
        Key root = key.root();
        return directory
                .resolve("r_" + root.x + "_" + root.y + "_" + root.z)
                .resolve(key.filename());
    }

    public record Frame(
            Key key,
            int children,
            BlockState[] states,
            String[] biomes,
            byte[] light,
            SimpleBitStorage data,
            ListTag entities) {
        public int at(int x, int y, int z) {
            return x < -1 || y < -1 || z < -1 || x > 32 || y > 32 || z > 32
                    ? -1
                    : data.get(x + 1 + SIDE * (z + 1) + SIDE * SIDE * (y + 1));
        }
    }

    public static byte[] encode(CompoundTag tag) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Deflater compressor = new Deflater(1);
        try (DataOutputStream out =
                new DataOutputStream(
                        new BufferedOutputStream(new DeflaterOutputStream(bytes, compressor)))) {
            NbtIo.write(tag, out);
        } finally {
            compressor.end();
        }
        return bytes.toByteArray();
    }

    public static Frame decode(Key key, byte[] payload) throws IOException {
        try (DataInputStream input =
                new DataInputStream(
                        new BufferedInputStream(
                                new InflaterInputStream(new ByteArrayInputStream(payload))))) {
            CompoundTag tag = NbtIo.read(input, NbtAccounter.unlimitedHeap());
            if (tag == null || input.read() != -1) throw new IOException("Invalid terrain record");
            ListTag palette = tag.getList("palette", Tag.TAG_COMPOUND);
            int count = palette.size();
            if (count < 1 || count > CELLS) throw new IOException("Invalid terrain palette");
            BlockState[] states = new BlockState[count];
            String[] biomes = new String[count];
            byte[] light = new byte[count];
            for (int i = 0; i < count; i++) {
                CompoundTag item = palette.getCompound(i), state = item.getCompound("state");
                biomes[i] = item.getString("biome");
                light[i] = item.getByte("light");
                ResourceLocation name = ResourceLocation.tryParse(state.getString("Name"));
                if (name == null
                        || !BuiltInRegistries.BLOCK.containsKey(name)
                        || biomes[i].isEmpty())
                    throw new IOException("Unknown or incomplete terrain palette");
                states[i] = NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), state);
            }
            SimpleBitStorage data =
                    new SimpleBitStorage(
                            Math.max(1, 32 - Integer.numberOfLeadingZeros(count - 1)),
                            CELLS,
                            tag.getLongArray("data"));
            for (int i = 0; i < CELLS; i++)
                if (data.get(i) >= count) throw new IOException("Invalid palette index");
            return new Frame(
                    key,
                    Byte.toUnsignedInt(tag.getByte("children")),
                    states,
                    biomes,
                    light,
                    data,
                    tag.getList("entities", Tag.TAG_COMPOUND));
        } catch (RuntimeException malformed) {
            throw new IOException("Malformed terrain record", malformed);
        }
    }

    public static byte[] hash(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (Exception impossible) {
            throw new AssertionError(impossible);
        }
    }

    public static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    public static void atomic(Path file, byte[]... pieces) throws IOException {
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), ".voxy-", ".part");
        try {
            try (OutputStream output = Files.newOutputStream(temporary)) {
                for (byte[] piece : pieces) output.write(piece);
            }
            Files.move(
                    temporary,
                    file,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public record Loaded(byte[] hash, Frame data) {}

    public static final class Cache {
        private final Path base, association;
        private final String dimension;
        private volatile Path world;
        private Set<Key> roots = Set.of();
        private final Map<Key, Map<Key, Integer>> indexes = new HashMap<>();

        public Cache(Path root, String server, String dimension) throws IOException {
            base =
                    root.resolve("cache")
                            .resolve(hex(hash(server.getBytes(StandardCharsets.UTF_8))));
            this.dimension = hex(hash(dimension.getBytes(StandardCharsets.UTF_8)));
            association = base.resolve("world");
            if (Files.isRegularFile(association)) {
                String id = Files.readString(association).strip();
                if (id.matches("[0-9a-f]{32}")) world = base.resolve(id).resolve(this.dimension);
            }
        }

        public boolean associate(byte[] id) throws IOException {
            if (id.length != 16 || Arrays.equals(id, new byte[16]))
                throw new IOException("Invalid world identity");
            Path target = base.resolve(hex(id)).resolve(dimension), previous;
            synchronized (this) {
                previous = world;
                world = target;
                if (!target.equals(previous)) indexes.clear();
            }
            try {
                atomic(association, hex(id).getBytes(StandardCharsets.US_ASCII));
            } catch (IOException failure) {
                LOG.log(
                        System.Logger.Level.WARNING,
                        "Cannot persist terrain world identity",
                        failure);
            }
            return previous != null && !previous.equals(target);
        }

        public String identity() {
            return world == null ? "" : world.getParent().getFileName().toString();
        }

        public synchronized void retain(Collection<Key> visibleRoots) {
            roots = new HashSet<>(visibleRoots);
            indexes.keySet().retainAll(roots);
        }

        public synchronized int children(Key key) throws IOException {
            Key root = key.root();
            if (world == null || key.level == 0 || !roots.contains(root)) return -1;
            Map<Key, Integer> masks = indexes.get(root);
            if (masks == null) {
                masks = new HashMap<>();
                Path directory = record(world, root).getParent();
                try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*.vxs")) {
                    for (Path file : files) {
                        String name = file.getFileName().toString();
                        String[] fields = name.substring(0, name.length() - 4).split("_");
                        if (fields.length != 4) continue;
                        try {
                            Key found =
                                    new Key(
                                            Integer.parseInt(fields[0]),
                                            Integer.parseInt(fields[1]),
                                            Integer.parseInt(fields[2]),
                                            Integer.parseInt(fields[3]));
                            if (found.root().equals(root) && found.filename().equals(name))
                                index(found, masks);
                        } catch (IllegalArgumentException malformed) {
                            /* Unrecognized files cannot imply terrain. */
                        }
                    }
                } catch (NoSuchFileException absent) {
                    /* Saves add hints when this root first appears. */
                } catch (DirectoryIteratorException failure) {
                    throw failure.getCause();
                }
                indexes.put(root, masks);
            }
            return masks.getOrDefault(key, -1);
        }

        private static void index(Key key, Map<Key, Integer> masks) {
            while (key.level < 4) {
                int bit = 1 << ((key.x & 1) | (key.z & 1) << 1 | (key.y & 1) << 2);
                key = key.parent();
                masks.merge(key, bit, (previous, added) -> previous | added);
            }
        }

        public Loaded load(Key key) throws IOException {
            Path directory = world;
            if (directory == null) return null;
            try (InputStream input = Files.newInputStream(record(directory, key))) {
                byte[] expected = input.readNBytes(32), payload = input.readAllBytes();
                if (!MessageDigest.isEqual(expected, hash(payload)))
                    throw new IOException("Corrupt cached section");
                return new Loaded(expected, decode(key, payload));
            } catch (NoSuchFileException absent) {
                return null;
            }
        }

        public boolean save(Key key, byte[] payload, Consumer<Loaded> usable) throws IOException {
            Frame decoded = decode(key, payload);
            byte[] digest = hash(payload);
            Path directory = world;
            if (directory == null) throw new IOException("World not associated");
            usable.accept(new Loaded(digest, decoded));
            try {
                atomic(record(directory, key), digest, payload);
                synchronized (this) {
                    Map<Key, Integer> masks = indexes.get(key.root());
                    if (directory.equals(world) && masks != null) index(key, masks);
                }
                return true;
            } catch (IOException failure) {
                LOG.log(System.Logger.Level.WARNING, "Cannot persist terrain " + key, failure);
                return false;
            }
        }
    }

    public record Endpoint(String host, int port, byte[] certificate)
            implements CustomPacketPayload {
        public static final Type<Endpoint> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath("voxy", "endpoint"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Endpoint> CODEC =
                new StreamCodec<>() {
                    public Endpoint decode(RegistryFriendlyByteBuf in) {
                        return new Endpoint(
                                in.readUtf(255), in.readUnsignedShort(), in.readByteArray(32));
                    }

                    public void encode(RegistryFriendlyByteBuf out, Endpoint value) {
                        out.writeUtf(value.host, 255);
                        out.writeShort(value.port);
                        out.writeByteArray(value.certificate);
                    }
                };

        public Endpoint {
            if (port < 0 || port > 65535 || certificate.length != 32)
                throw new IllegalArgumentException("Invalid endpoint");
        }

        public Type<Endpoint> type() {
            return TYPE;
        }

        public static Endpoint request() {
            return new Endpoint("", 0, new byte[32]);
        }
    }

    public record Request(Key key, byte[] known) {}

    public record Reply(int status, byte[] payload) {}

    @FunctionalInterface
    public interface Receiver {
        void received(Key key, Reply reply) throws IOException;
    }

    public static final class Link implements AutoCloseable {
        private static final byte[] UNKNOWN = new byte[32];
        private static final Reply[] NO_PAYLOAD = {new Reply(0, null), new Reply(1, null)};
        private final QuicClientConnection connection;
        private final DataInputStream input;
        private final OutputStream output;
        private final byte[] world;
        private final ByteBuffer command = ByteBuffer.allocate(46).order(ByteOrder.LITTLE_ENDIAN);

        public Link(String address, int port, byte[] certificate, String dimension)
                throws IOException {
            connection =
                    QuicClientConnection.newBuilder()
                            .host("voxy.local")
                            .proxy(address)
                            .port(port)
                            .applicationProtocol("voxy")
                            .connectTimeout(Duration.ofSeconds(120))
                            .maxIdleTimeout(Duration.ofSeconds(300))
                            .maxOpenPeerInitiatedBidirectionalStreams(0)
                            .maxOpenPeerInitiatedUnidirectionalStreams(0)
                            .customTrustManager(new Pinned(certificate))
                            .build();
            try {
                connection.connect();
                var stream = connection.createStream(true);
                input = new DataInputStream(stream.getInputStream());
                output = stream.getOutputStream();
                byte[] name = dimension.getBytes(StandardCharsets.UTF_8);
                output.write(name.length);
                output.write(name.length >>> 8);
                output.write(name);
                output.flush();
                world = read(16);
            } catch (IOException | RuntimeException failure) {
                connection.close();
                throw failure;
            }
        }

        public byte[] world() {
            return world.clone();
        }

        private void send(Key key, byte[] known) throws IOException {
            command.clear();
            command.put((byte) 0)
                    .put((byte) key.level)
                    .putInt(key.x)
                    .putInt(key.y)
                    .putInt(key.z)
                    .put(known == null ? UNKNOWN : known);
            output.write(command.array());
        }

        public Reply get(Key key, byte[] known) throws IOException {
            send(key, known);
            output.flush();
            return receive();
        }

        public void sections(List<Request> requests, Receiver receiver) throws IOException {
            Thread sender =
                    Thread.ofVirtual()
                            .name("Voxy requests")
                            .start(
                                    () -> {
                                        try {
                                            for (Request item : requests)
                                                send(item.key, item.known);
                                            output.flush();
                                        } catch (IOException failure) {
                                            connection.close();
                                        }
                                    });
            boolean complete = false;
            try {
                for (Request item : requests) receiver.received(item.key, receive());
                complete = true;
            } finally {
                if (!complete)
                    close(); // Release a flow-controlled sender when decoding or delivery fails.
                try {
                    sender.join();
                } catch (InterruptedException stopped) {
                    close();
                    Thread.currentThread().interrupt();
                    throw new IOException(stopped);
                }
            }
        }

        private Reply receive() throws IOException {
            int status = input.read();
            if (status == 0 || status == 1) return NO_PAYLOAD[status];
            if (status != 2) throw new IOException("Invalid terrain reply " + status);
            int length = Integer.reverseBytes(input.readInt());
            if (length < 1) throw new IOException("Invalid terrain record length");
            return new Reply(status, read(length));
        }

        private byte[] read(int count) throws IOException {
            byte[] bytes = new byte[count];
            input.readFully(bytes);
            return bytes;
        }

        public void close() {
            connection.close();
        }
    }

    private record Pinned(byte[] certificate) implements X509TrustManager {
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }

        public void checkClientTrusted(X509Certificate[] chain, String auth)
                throws CertificateException {
            throw new CertificateException("Server trust only");
        }

        public void checkServerTrusted(X509Certificate[] chain, String auth)
                throws CertificateException {
            if (chain.length == 0
                    || !MessageDigest.isEqual(certificate, hash(chain[0].getEncoded())))
                throw new CertificateException("Terrain certificate mismatch");
        }
    }
}
