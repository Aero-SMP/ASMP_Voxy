package me.cortex.voxy.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import me.cortex.voxy.client.VoxyClient;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.network.QuicEndpointPayload;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** User-selected download and disk policies belong to a Minecraft server, across dimensions. */
public final class ServerDownloadSettings {
    public static final int MIN_KBPS = 100, MAX_KBPS = 10_000, DEFAULT_KBPS = 1_000;
    public static final long MIN_STORAGE_BYTES = 100_000_000, DEFAULT_STORAGE_BYTES = 500_000_000;
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Object LOCK = new Object();
    private static Store store;
    private static boolean writable = true;
    private final String serverId, rawAddress;
    private final Policy policy;

    public record Anchor(int x, int z) {}
    private static final class Policy {
        int downloadKbps = DEFAULT_KBPS;
        long storageBytes = DEFAULT_STORAGE_BYTES, estimatedWorldBytes;
        Map<String, Anchor> anchors = new HashMap<>();
    }
    private static final class Store { Map<String, Policy> servers = new HashMap<>(); }

    private ServerDownloadSettings(String serverId, String rawAddress, Policy policy) {
        this.serverId = serverId;
        this.rawAddress = rawAddress;
        this.policy = policy;
    }

    public static ServerDownloadSettings forServer(String rawAddress) {
        String key = normalize(rawAddress);
        synchronized (LOCK) {
            load();
            return new ServerDownloadSettings(key, rawAddress,
                    store.servers.computeIfAbsent(key, ignored -> new Policy()));
        }
    }

    /** No mutable server policy is selected on the main menu or for an integrated world. */
    public static ServerDownloadSettings current() {
        var server = Minecraft.getInstance().getCurrentServer();
        return server == null ? null : forServer(server.ip);
    }

    public static String normalize(String address) {
        Objects.requireNonNull(address, "server address");
        String value = address.trim(), host;
        int port = 25565;
        if (value.startsWith("[")) {
            int end = value.indexOf(']');
            if (end < 0) throw new IllegalArgumentException("invalid server IPv6 address");
            host = value.substring(1, end);
            if (end + 1 < value.length()) {
                if (value.charAt(end + 1) != ':') throw new IllegalArgumentException("invalid server port");
                port = Integer.parseInt(value.substring(end + 2));
            }
        } else {
            int colon = value.indexOf(':');
            if (colon >= 0 && colon == value.lastIndexOf(':')) {
                host = value.substring(0, colon);
                port = Integer.parseInt(value.substring(colon + 1));
            } else host = value;
        }
        host = QuicEndpointPayload.canonicalHost(host);
        if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        if (host.isEmpty() || port < 1 || port > 65535) throw new IllegalArgumentException("invalid server address");
        return (host.indexOf(':') >= 0 ? '[' + host + ']' : host) + ':' + port;
    }

    public String serverId() { return this.serverId; }
    public String rawAddress() { return this.rawAddress; }
    public int downloadKbps() { synchronized (LOCK) { return this.policy.downloadKbps; } }
    public void setDownloadKbps(int kbps) {
        if (kbps < MIN_KBPS || kbps > MAX_KBPS) throw new IllegalArgumentException("download bandwidth outside 100–10000 kbps");
        synchronized (LOCK) { this.policy.downloadKbps = kbps; }
    }
    public long storageBytes() { synchronized (LOCK) { return this.policy.storageBytes; } }
    public void setStorageBytes(long bytes) {
        if (bytes < MIN_STORAGE_BYTES) throw new IllegalArgumentException("storage allowance below 100 MB");
        synchronized (LOCK) { this.policy.storageBytes = bytes; }
    }
    public boolean entireWorld() { return storageBytes() == Long.MAX_VALUE; }
    public long estimatedWorldBytes() { synchronized (LOCK) { return this.policy.estimatedWorldBytes; } }
    public void setEstimatedWorldBytes(long bytes) {
        if (bytes < 0) throw new IllegalArgumentException("negative world estimate");
        synchronized (LOCK) { this.policy.estimatedWorldBytes = bytes; }
    }
    public Anchor anchor(String dimension, int fallbackX, int fallbackZ) {
        synchronized (LOCK) {
            Anchor saved = this.policy.anchors.get(dimension);
            return saved == null ? new Anchor(fallbackX, fallbackZ) : saved;
        }
    }
    public void rememberDimension(String dimension, int blockX, int blockZ) {
        Objects.requireNonNull(dimension, "dimension");
        if (Math.abs((long) blockX) > 30_000_000 || Math.abs((long) blockZ) > 30_000_000)
            throw new IllegalArgumentException("dimension anchor outside Minecraft coordinates");
        synchronized (LOCK) { this.policy.anchors.put(dimension, new Anchor(blockX, blockZ)); }
    }

    public void save() {
        synchronized (LOCK) {
            load();
            if (!writable) return;
            Path file = path(), temporary = file.resolveSibling(file.getFileName() + ".part");
            try {
                Files.createDirectories(file.getParent());
                Files.writeString(temporary, JSON.toJson(store));
                try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                catch (AtomicMoveNotSupportedException unsupported) {
                    Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException failure) { Logger.error("Could not save Voxy server download settings", failure); }
        }
    }

    private static Path path() { return VoxyClient.getConfigDir().resolve("voxy-servers.json"); }
    private static void load() {
        if (store != null) return;
        store = new Store();
        Path file = path();
        if (!Files.exists(file)) return;
        try {
            Store saved = JSON.fromJson(Files.readString(file), Store.class);
            if (saved == null || saved.servers == null) throw new IOException("missing server policies");
            for (var entry : saved.servers.entrySet()) {
                Policy policy = entry.getValue();
                if (!normalize(entry.getKey()).equals(entry.getKey()) || policy == null
                        || policy.downloadKbps < MIN_KBPS || policy.downloadKbps > MAX_KBPS
                        || policy.storageBytes < MIN_STORAGE_BYTES || policy.estimatedWorldBytes < 0
                        || policy.anchors == null) throw new IOException("invalid server download policy");
                for (Anchor anchor : policy.anchors.values())
                    if (anchor == null || Math.abs((long) anchor.x) > 30_000_000 || Math.abs((long) anchor.z) > 30_000_000)
                        throw new IOException("invalid saved dimension anchor");
            }
            store = saved;
        } catch (Exception failure) {
            writable = false;
            Logger.error("Could not read Voxy server policies; preserving the settings file", failure);
        }
    }
}
