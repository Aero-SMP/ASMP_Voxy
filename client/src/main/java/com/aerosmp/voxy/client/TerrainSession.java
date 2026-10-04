package com.aerosmp.voxy.client;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL43C.*;

import com.aerosmp.voxy.Common;
import com.aerosmp.voxy.Common.*;
import com.mojang.blaze3d.vertex.*;

import net.caffeinemc.mods.sodium.api.config.*;
import net.caffeinemc.mods.sodium.api.config.option.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.*;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.renderer.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.*;
import net.minecraft.nbt.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.material.*;
import net.neoforged.neoforge.client.event.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Cache loading runs before endpoint discovery; connection state never gates local visibility. */
final class TerrainSession implements AutoCloseable {
    final TerrainRenderer renderer;
    private final Cache cache;
    private final String address, dimension;
    private final Path statusFile;
    private final Thread local, remote;
    volatile Endpoint endpoint;
    private volatile Link link;
    private volatile boolean closed, network = true, connected;
    private volatile long downloads, cacheLoads, receivedBytes, networkFailures, localFailures;
    private volatile long obsoleteJobs;
    private volatile String error = "";
    private long nextStatus;
    private volatile byte[] status;

    private record Requested(TerrainRenderer.Section owner, byte[] known) {}

    TerrainSession(Path root, String address, String dimension, Runnable identityChanged)
            throws IOException {
        this.address = address;
        this.dimension = dimension;
        cache = new Cache(root, address, dimension);
        statusFile = root.resolve("status.json");
        renderer = new TerrainRenderer();
        local = worker("Voxy cache", this::local);
        remote = worker("Voxy connection", () -> remote(identityChanged));
    }

    private Thread worker(String name, Runnable action) {
        Thread thread = new Thread(action, name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    void network(boolean enabled) {
        network = enabled;
        Link connection = link;
        if (!enabled && connection != null) connection.close();
    }

    void tick() {
        if (System.nanoTime() < nextStatus) return;
        nextStatus = System.nanoTime() + 1_000_000_000L;
        Map<String, Object> value = renderer.status();
        value.put("world", cache.identity());
        value.put("dimension", dimension);
        value.put("connected", connected);
        value.put("downloads", downloads);
        value.put("cacheLoads", cacheLoads);
        value.put("receivedBytes", receivedBytes);
        value.put("networkFailures", networkFailures);
        value.put("localFailures", localFailures);
        value.put("obsoleteJobs", obsoleteJobs);
        value.put("lastError", error);
        value.put("timeMillis", System.currentTimeMillis());
        var player = Minecraft.getInstance().player;
        if (player != null) {
            value.put("position", List.of(player.getX(), player.getY(), player.getZ()));
            value.put("rotation", List.of(player.getYRot(), player.getXRot()));
        }
        status = new com.google.gson.Gson().toJson(value).getBytes(StandardCharsets.UTF_8);
        renderer.wakeup();
    }

    private void local() {
        List<Key> retained = List.of();
        while (!closed) {
            TerrainRenderer.Section section = null;
            TerrainRenderer.Revision attempt = null;
            boolean validated = false;
            try {
                section = renderer.job();
                flushStatus();
                List<Key> roots = renderer.roots();
                if (roots != retained) {
                    cache.retain(roots);
                    retained = roots;
                    for (Key root : roots) {
                        var owner = renderer.sections.get(root);
                        if (owner != null && owner.children < 0) renderer.enqueue(owner);
                    }
                }
                if (section == null || !section.active || !renderer.workVisible(section)) continue;
                if (!renderer.retry(section)) {
                    renderer.deferred(section);
                    continue;
                }
                attempt = renderer.attempt(section, section.cacheHash);
                Key key = section.key;
                Loaded loaded;
                synchronized (section) {
                    loaded = section.local;
                    section.local = null;
                }
                if (loaded == null
                        && !renderer.loaded(section, section.cacheHash)
                        && !section.checked) {
                    var fallback = section.fallback;
                    loaded =
                            fallback == null
                                    ? cache.load(key)
                                    : new Loaded(
                                            fallback.hash(),
                                            Common.decode(key, fallback.payload()));
                    synchronized (section) {
                        if (!renderer.valid(section, attempt)) continue;
                        if (loaded != null) {
                            if (section.cacheHash != null
                                    && !Arrays.equals(section.cacheHash, loaded.hash())) continue;
                            section.cacheHash = loaded.hash();
                            attempt =
                                    new TerrainRenderer.Revision(
                                            loaded.hash(), attempt.models(), attempt.purpose());
                        }
                        section.checked = true;
                    }
                    if (loaded == null) renderer.requestChanged();
                }
                if (loaded != null) {
                    validated = true;
                    synchronized (section) {
                        if (!renderer.valid(section, attempt)
                                || !Arrays.equals(attempt.hash(), loaded.hash())) continue;
                        section.corrupt = false;
                        renderer.available(section, loaded.data().children());
                    }
                    renderer.submit(section, loaded.data(), attempt);
                    cacheLoads++;
                } else if (section.children < 0 && key.level() > 0) {
                    int children = cache.children(key);
                    if (children > 0 && renderer.valid(section, attempt))
                        renderer.available(section, children);
                }
            } catch (InterruptedException stopped) {
                return;
            } catch (CancellationException obsolete) {
                if (closed
                        || section != null
                                && attempt != null
                                && !renderer.valid(section, attempt)) {
                    obsoleteJobs++;
                } else {
                    if (section != null && attempt != null) renderer.failed(section, attempt);
                    localFailures++;
                    error = obsolete.toString();
                }
            } catch (IOException failure) {
                if (section != null && attempt != null)
                    synchronized (section) {
                        if (renderer.valid(section, attempt)) {
                            if (validated) renderer.failed(section, attempt);
                            else {
                                section.corrupt = true;
                                section.checked = true;
                                renderer.requestChanged();
                            }
                        }
                    }
                localFailures++;
                error = failure.toString();
            } catch (Exception failure) {
                if (section != null && attempt != null)
                    synchronized (section) {
                        if (renderer.valid(section, attempt)) renderer.failed(section, attempt);
                    }
                localFailures++;
                error = failure.toString();
            }
        }
    }

    private void flushStatus() {
        byte[] snapshot = status;
        status = null;
        if (snapshot != null)
            try {
                Common.atomic(statusFile, snapshot);
            } catch (IOException failure) {
                error = failure.toString();
            }
    }

    private void remote(Runnable identityChanged) {
        while (!closed) {
            Endpoint ready = endpoint;
            if (ready == null || !network) {
                pause(100);
                continue;
            }
            String host =
                    ready.host().isEmpty()
                            ? ServerAddress.parseString(address).getHost()
                            : ready.host();
            try (Link connection = new Link(host, ready.port(), ready.certificate(), dimension)) {
                link = connection;
                connected = true;
                if (cache.associate(connection.world())) {
                    Minecraft.getInstance().execute(identityChanged);
                    return;
                }
                long nextRefresh = 0;
                while (!closed && network && endpoint == ready) {
                    long revision = renderer.view();
                    List<Request> requests = new ArrayList<>();
                    List<Requested> requested = new ArrayList<>();
                    long now = System.nanoTime();
                    for (TerrainRenderer.Section section : renderer.wanted()) {
                        synchronized (section) {
                            byte[] hash = section.cacheHash;
                            boolean missing = section.checked && hash == null || section.corrupt;
                            boolean probe =
                                    section.incomplete
                                            && hash != null
                                            && !Arrays.equals(hash, section.serverVerifiedHash);
                            if (section.needed
                                    && renderer.workVisible(section)
                                    && (missing || probe)
                                    && renderer.sections.get(section.key) == section
                                    && (!section.unavailable || now >= nextRefresh)) {
                                byte[] known = missing ? null : hash;
                                requests.add(new Request(section.key, known));
                                requested.add(new Requested(section, known));
                            }
                        }
                    }
                    if (!requests.isEmpty()) {
                        // Link delivers replies in request order; retain the original owner and
                        // hash.
                        Iterator<Requested> replies = requested.iterator();
                        connection.sections(
                                requests,
                                (key, reply) -> {
                                    Requested sent = replies.next();
                                    receive(sent.owner(), sent.known(), reply);
                                });
                    } else if (now >= nextRefresh)
                        for (TerrainRenderer.Section section : renderer.wanted()) {
                            if (closed || !network || renderer.view() != revision) break;
                            byte[] known = section.cacheHash;
                            if (section.needed && renderer.workVisible(section) && known != null)
                                receive(section, known, connection.get(section.key, known));
                        }
                    if (now >= nextRefresh) nextRefresh = System.nanoTime() + 5_000_000_000L;
                    pause(100);
                }
            } catch (Exception failure) {
                if (!closed && network && endpoint == ready) {
                    networkFailures++;
                    error = failure.toString();
                    pause(1000);
                }
            } finally {
                connected = false;
                link = null;
            }
        }
    }

    private void receive(TerrainRenderer.Section section, byte[] known, Reply reply)
            throws IOException {
        Key key = section.key;
        synchronized (section) {
            if (closed || renderer.sections.get(key) != section) return;
            section.unavailable = reply.status() == 0;
            if (reply.status() == 1 && known != null && Arrays.equals(section.cacheHash, known))
                section.serverVerifiedHash = known;
        }
        if (reply.status() != 2) return;
        if (cache.save(
                key,
                reply.payload(),
                loaded -> {
                    synchronized (section) {
                        if (closed || renderer.sections.get(key) != section) return;
                        section.cacheHash = loaded.hash();
                        section.serverVerifiedHash = loaded.hash();
                        section.local =
                                section.active && renderer.workVisible(section) ? loaded : null;
                        section.fallback =
                                new TerrainRenderer.Fallback(loaded.hash(), reply.payload());
                        section.corrupt = false;
                        section.checked = false;
                    }
                    renderer.available(section, loaded.data().children());
                    renderer.enqueue(section);
                }))
            synchronized (section) {
                if (section.fallback != null && section.fallback.payload() == reply.payload())
                    section.fallback = null;
            }
        downloads++;
        receivedBytes += reply.payload().length;
    }

    private void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
        }
    }

    public void close() {
        closed = true;
        Link connection = link;
        if (connection != null) connection.close();
        local.interrupt();
        remote.interrupt();
        renderer.close();
    }
}
