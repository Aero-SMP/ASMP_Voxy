package me.cortex.voxy.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import me.cortex.voxy.client.VoxyClient;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.network.QuicEndpointPayload;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** User-selected download and disk policies belong to a Minecraft server, across dimensions. */
public final class ServerDownloadSettings {
    public static final int MIN_KBPS = 100, MAX_KBPS = 20_000, DEFAULT_KBPS = 5_000;
    public static final long MIN_STORAGE_BYTES = 100_000_000, DEFAULT_STORAGE_BYTES = 500_000_000;
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Object LOCK = new Object();
    private static Store store;
    private static boolean loaded;
    private static String failureReason = "";
    private final String serverId, rawAddress;

    public record Anchor(int x, int z) {}
    private static final class Policy {
        int downloadKbps = DEFAULT_KBPS;
        long storageBytes = DEFAULT_STORAGE_BYTES, estimatedWorldBytes;
        Map<String, Anchor> anchors = new HashMap<>();
        transient boolean storageSelected, storageResolved;
    }
    private static final class Store { Map<String, Policy> servers = new HashMap<>(); }

    private ServerDownloadSettings(String serverId, String rawAddress) {
        this.serverId = serverId;
        this.rawAddress = rawAddress;
    }

    public static ServerDownloadSettings forServer(String rawAddress) {
        String key = normalize(rawAddress);
        synchronized (LOCK) {
            load();
            if (store != null) store.servers.computeIfAbsent(key, ignored -> new Policy());
            return new ServerDownloadSettings(key, rawAddress);
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
    public static boolean policiesAvailable() { synchronized (LOCK) { load(); return store != null; } }
    public boolean available() { return policiesAvailable(); }
    public String failureReason() { synchronized (LOCK) { load(); return failureReason; } }
    /** Explicit correction/rejoin may retry a failed read; ordinary getters never retry it. */
    public static void reloadUnavailable() {
        synchronized (LOCK) { if (store == null) { loaded = false; load(); } }
    }
    private Policy policy() {
        load();
        if (store == null) throw new IllegalStateException(failureReason);
        return store.servers.computeIfAbsent(this.serverId, ignored -> new Policy());
    }
    public int downloadKbps() { synchronized (LOCK) { return store == null ? DEFAULT_KBPS : policy().downloadKbps; } }
    public void setDownloadKbps(int kbps) {
        if (kbps < MIN_KBPS || kbps > MAX_KBPS) throw new IllegalArgumentException("download bandwidth outside 100–20000 kbps");
        synchronized (LOCK) { policy().downloadKbps = kbps; }
    }
    public long storageBytes() { synchronized (LOCK) { return store == null ? -1 : policy().storageBytes; } }
    public boolean storageSelected() { synchronized (LOCK) { return store != null && policy().storageSelected; } }
    /** A default placeholder cannot reduce a known allowance; an explicit choice always wins. */
    public long retainStorageBytes(long persisted, boolean inventoryComplete) {
        synchronized (LOCK) {
            if (store == null) return -1;
            var selected = policy();
            if (!selected.storageSelected && persisted >= MIN_STORAGE_BYTES) selected.storageBytes = persisted;
            if (persisted >= MIN_STORAGE_BYTES || inventoryComplete) selected.storageResolved = true;
            return selected.storageBytes;
        }
    }
    public void setStorageBytes(long bytes) {
        if (bytes < MIN_STORAGE_BYTES) throw new IllegalArgumentException("storage allowance below 100 MB");
        synchronized (LOCK) {
            var selected = policy(); selected.storageBytes = bytes;
            selected.storageSelected = selected.storageResolved = true;
        }
    }
    public boolean entireWorld() { return storageBytes() == Long.MAX_VALUE; }
    public long estimatedWorldBytes() { synchronized (LOCK) { return store == null ? 0 : policy().estimatedWorldBytes; } }
    public void setEstimatedWorldBytes(long bytes) {
        if (bytes < 0) throw new IllegalArgumentException("negative world estimate");
        synchronized (LOCK) { if (store != null) policy().estimatedWorldBytes = bytes; }
    }
    public Anchor anchor(String dimension, int fallbackX, int fallbackZ) {
        synchronized (LOCK) {
            Anchor saved = store == null ? null : policy().anchors.get(dimension);
            return saved == null ? new Anchor(fallbackX, fallbackZ) : saved;
        }
    }
    public void rememberDimension(String dimension, int blockX, int blockZ) {
        Objects.requireNonNull(dimension, "dimension");
        if (Math.abs((long) blockX) > 30_000_000 || Math.abs((long) blockZ) > 30_000_000)
            throw new IllegalArgumentException("dimension anchor outside Minecraft coordinates");
        synchronized (LOCK) { if (store != null) policy().anchors.put(dimension, new Anchor(blockX, blockZ)); }
    }

    public void save() {
        synchronized (LOCK) {
            load();
            if (store == null) return;
            Path file = path(), temporary = file.resolveSibling(file.getFileName() + ".part");
            try {
                if (Files.isSymbolicLink(file)) throw new IOException("symlink in server settings path");
                Files.createDirectories(file.getParent());
                var saved = new Store();
                store.servers.forEach((key, policy) -> { if (policy.storageResolved) saved.servers.put(key, policy); });
                Files.writeString(temporary, JSON.toJson(saved), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                catch (AtomicMoveNotSupportedException unsupported) {
                    Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException failure) { Logger.error("Could not save Voxy server download settings", failure); }
        }
    }

    private static Path path() { return VoxyClient.getConfigDir().resolve("voxy-servers.json"); }
    private static void load() {
        if (loaded) return;
        loaded = true;
        failureReason = "";
        store = new Store();
        Path file = path();
        try {
            var attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile()) throw new IOException("server settings are not a regular file");
            com.google.gson.JsonObject document;
            try (var reader = new InputStreamReader(Files.newInputStream(file, StandardOpenOption.READ,
                    LinkOption.NOFOLLOW_LINKS), StandardCharsets.UTF_8)) {
                var parsed = JsonParser.parseReader(reader);
                if (!parsed.isJsonObject()) throw new IOException("missing server policies");
                document = parsed.getAsJsonObject();
            }
            if (!document.has("servers") || !document.get("servers").isJsonObject()) throw new IOException("missing server policies");
            for (var entry : document.getAsJsonObject("servers").entrySet()) {
                if (!entry.getValue().isJsonObject()) throw new IOException("invalid server download policy");
                var policy = entry.getValue().getAsJsonObject();
                for (String field : new String[]{"downloadKbps", "storageBytes", "estimatedWorldBytes"}) {
                    var value = policy.get(field);
                    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
                        throw new IOException("incomplete server download policy");
                    if (field.equals("downloadKbps")) value.getAsBigDecimal().intValueExact();
                    else value.getAsBigDecimal().longValueExact();
                }
                if (!policy.has("anchors") || !policy.get("anchors").isJsonObject()) throw new IOException("invalid saved dimension anchors");
            }
            Store saved = JSON.fromJson(document, Store.class);
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
                policy.storageSelected = policy.storageResolved = true;
            }
            store = saved;
        } catch (NoSuchFileException missing) {
            // Only an actually absent file authorizes new-server defaults.
        } catch (Exception failure) {
            store = null;
            failureReason = "Unable to read voxy-servers.json; cache downloads paused";
            Logger.error("Could not read Voxy server policies; preserving the settings file", failure);
        }
    }
}
