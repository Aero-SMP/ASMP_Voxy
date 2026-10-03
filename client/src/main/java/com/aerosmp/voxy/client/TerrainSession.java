package com.aerosmp.voxy.client;

import com.aerosmp.voxy.client.render.TerrainRenderer;
import com.aerosmp.voxy.network.Endpoint;
import com.aerosmp.voxy.terrain.*;
import net.minecraft.client.Minecraft;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Local loading and reconciliation have independent workers and never wait for each other. */
final class TerrainSession implements AutoCloseable {
    private final TerrainStore store;
    private final TerrainRenderer renderer = new TerrainRenderer();
    private static final class SectionState {
        volatile byte[] meshHash, frameHash;
        volatile boolean invalid;
        long revision;
    }
    private final Map<SectionKey, SectionState> sections = new ConcurrentHashMap<>();
    private final String dimension, address;
    private final Path statusFile;
    private final Runnable identityChanged;
    private final Thread local, remote;
    private volatile List<SectionKey> wanted = List.of();
    private volatile Set<SectionKey> wantedKeys = Set.of();
    private volatile Endpoint endpoint;
    private volatile QuicTerrain connection;
    private volatile boolean closed, connected;
    private volatile boolean networkEnabled = true;
    private volatile long cacheLoads, downloads, receivedBytes, localFailures, networkFailures;
    private volatile long refinementRequests, refreshRequests;
    private volatile String lastError = "";
    private long nextStatus;
    private long modelRevision;
    private volatile byte[] status;

    TerrainSession(Path root, String address, String dimension, Runnable identityChanged) throws java.io.IOException {
        this.address = address; this.dimension = dimension; this.identityChanged = identityChanged;
        store = new TerrainStore(root.resolve("cache"), address, dimension);
        statusFile = root.resolve("status.json");
        local = worker("Voxy local terrain", this::loadLocal);
        remote = worker("Voxy terrain reconciliation", this::reconcile);
    }
    private Thread worker(String name, Runnable work) {
        Thread thread = new Thread(work, name); thread.setDaemon(true); thread.start(); return thread;
    }
    void endpoint(Endpoint value) {
        Endpoint old = endpoint;
        if (value.port() != 0 && (old == null || old.port() != value.port() || !old.host().equals(value.host())
                || !Arrays.equals(old.certificate(), value.certificate()))) endpoint = value;
    }
    void network(boolean enabled) {
        if (networkEnabled == enabled) return;
        networkEnabled = enabled;
        QuicTerrain active = connection; if (!enabled && active != null) active.close();
    }
    void render(boolean enabled) { renderer.enabled(enabled); }
    void clipping(boolean enabled) { renderer.clipping(enabled); }
    void pixels(float value) { renderer.pixels(value); }
    void tick() {
        List<SectionKey> next = renderer.wanted();
        if (next != wanted) { wantedKeys = Set.copyOf(next); wanted = next; }
        long revision = renderer.modelRevision();
        if (modelRevision != revision) { modelRevision = revision; sections.clear(); }
        sections.keySet().removeAll(renderer.drainFailedPublications());
        sections.keySet().retainAll(wantedKeys);
        if (System.nanoTime() >= nextStatus) {
            nextStatus = System.nanoTime() + 1_000_000_000L;
            Map<String, Object> status = new LinkedHashMap<>(renderer.status());
            status.put("world", store.identity()); status.put("dimension", dimension);
            status.put("connected", connected); status.put("cacheLoads", cacheLoads);
            status.put("downloads", downloads); status.put("receivedBytes", receivedBytes);
            status.put("localFailures", localFailures); status.put("networkFailures", networkFailures);
            status.put("lastError", lastError); status.put("depthError", renderer.depthError());
            status.put("refinementRequests", refinementRequests); status.put("refreshRequests", refreshRequests); status.put("timeMillis", System.currentTimeMillis());
            var player = Minecraft.getInstance().player;
            if (player != null) {
                status.put("position", List.of(player.getX(), player.getY(), player.getZ()));
                status.put("rotation", List.of(player.getYRot(), player.getXRot()));
            }
            this.status = new com.google.gson.Gson().toJson(status).getBytes(StandardCharsets.UTF_8);
        }
    }
    private void loadLocal() {
        CacheLoader loader = new CacheLoader(store);
        while (!closed) {
            for (SectionKey key : wanted) {
                if (closed) return;
                if (!wantedKeys.contains(key)) continue;
                flushStatus();
                SectionState state = sections.computeIfAbsent(key, ignored -> new SectionState());
                long ticket;
                synchronized (state) { if (state.meshHash != null) continue; ticket = state.revision; }
                try {
                    CacheLoader.Loaded loaded = loader.load(key, null);
                    if (loaded != null) {
                        synchronized (state) { if (state.revision == ticket) { state.frameHash = loaded.hash(); state.invalid = false; } }
                        long revision = renderer.modelRevision();
                        renderer.submit(loaded.data());
                        synchronized (state) {
                            if (!closed && wantedKeys.contains(key) && revision == renderer.modelRevision()
                                    && state.revision == ticket) state.meshHash = loaded.hash();
                        }
                        cacheLoads++;
                    }
                } catch (Exception failure) { state.invalid = true; localFailures++; lastError = failure.toString(); }
            }
            flushStatus();
            pause(100);
        }
    }
    private void flushStatus() {
        byte[] snapshot = status;
        if (snapshot == null) return;
        status = null;
        try { TerrainStore.atomic(statusFile, snapshot); }
        catch (java.io.IOException failure) { lastError = failure.toString(); }
    }
    private void reconcile() {
        while (!closed) {
            Endpoint ready = endpoint;
            if (ready == null || !networkEnabled) { pause(100); continue; }
            try (QuicTerrain quic = new QuicTerrain(ready.host().isEmpty() ? addressHost() : ready.host(),
                    ready.port(), ready.certificate())) {
                connection = quic;
                quic.connect(dimension); connected = true;
                if (store.associate(quic.world())) {
                    Minecraft.getInstance().execute(identityChanged); return;
                }
                SectionCodec.Catalog[] catalog = {null};
                long nextRefresh = 0;
                while (!closed && networkEnabled && endpoint == ready) {
                    List<SectionKey> demand = wanted;
                    Set<SectionKey> keys = wantedKeys;
                    // Hierarchy levels are natural coverage/refinement stages, not arbitrary work quotas.
                    boolean missing = false;
                    for (int level = 4; level >= 0 && !closed && wanted == demand; level--) {
                        List<QuicTerrain.Request> requests = new ArrayList<>();
                        for (SectionKey key : demand) if (key.level() == level && !locallyCovered(key, keys))
                            requests.add(new QuicTerrain.Request(key, null));
                        if (requests.isEmpty()) continue;
                        missing = true; refinementRequests += requests.size();
                        quic.sections(requests, (key, frame) -> receive(quic, catalog, key, frame));
                    }
                    if (!missing && System.nanoTime() >= nextRefresh) {
                        // Only one freshness request is in flight: a changed view can immediately take priority.
                        for (SectionKey key : demand) {
                            if (closed || !networkEnabled || wanted != demand) break;
                            SectionState state = sections.get(key);
                            if (state == null || state.frameHash == null || state.invalid) continue;
                            refreshRequests++;
                            receive(quic, catalog, key, quic.section(key, state.frameHash));
                        }
                        nextRefresh = System.nanoTime() + 5_000_000_000L;
                    }
                    pause(100);
                }
            } catch (Exception failure) { networkFailures++; lastError = failure.toString(); pause(1000); }
            finally { connected = false; connection = null; }
        }
    }
    private boolean locallyCovered(SectionKey key, Set<SectionKey> keys) {
        SectionState state = sections.get(key);
        if ((state == null || !state.invalid) && store.hasFrame(key)) return true;
        if (key.level() == 0) return false;
        boolean children = false;
        for (int i = 0; i < 8; i++) {
            SectionKey child = key.child(i);
            if (!keys.contains(child)) continue;
            children = true;
            if (!locallyCovered(child, keys)) return false;
        }
        return children;
    }
    private void receive(QuicTerrain quic, SectionCodec.Catalog[] catalog, SectionKey key, byte[] frame) throws java.io.IOException {
        if (frame == null || closed) return;
        byte[] id = SectionCodec.catalogHash(frame);
        if (catalog[0] == null || !Arrays.equals(id, catalog[0].hash())) {
            try { catalog[0] = store.catalog(id); }
            catch (java.io.IOException missing) {
                byte[] bytes = quic.catalog(id); store.saveCatalog(bytes, id);
                catalog[0] = SectionCodec.catalog(bytes, id);
            }
        }
        SectionCodec.decode(key, frame, catalog[0]);
        store.saveFrame(key, frame);
        SectionState state = sections.computeIfAbsent(key, ignored -> new SectionState());
        synchronized (state) {
            state.frameHash = SectionCodec.hash(frame); state.invalid = false;
            state.revision++; state.meshHash = null;
        }
        downloads++; receivedBytes += frame.length;
    }
    private String addressHost() {
        return net.minecraft.client.multiplayer.resolver.ServerAddress.parseString(address).getHost();
    }
    private void pause(long millis) {
        try { Thread.sleep(millis); } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
    }
    @Override public void close() {
        closed = true;
        QuicTerrain active = connection; if (active != null) active.close();
        local.interrupt(); remote.interrupt(); renderer.close();
    }
}
