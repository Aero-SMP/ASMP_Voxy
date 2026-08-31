package me.cortex.voxy.debug;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.world.WorldEngine;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4fc;
import org.joml.Matrix4f;
import org.joml.FrustumIntersection;
import org.lwjgl.system.MemoryUtil;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Debug-JAR-only proof-oriented telemetry for LOD detail, coverage and visibility. */
public final class LodAudit {
    public static final int COUNTER_COUNT = 64;
    public static final int ANOMALY_CAPACITY = 128;
    public static final int FRONTIER_CAPACITY = 2048;
    public static final int MISSING_ROOT_CAPACITY = 16_384;
    public static final int TRAVERSAL_AUDIT_BYTES = COUNTER_COUNT * Integer.BYTES
            + ANOMALY_CAPACITY * 4 * Integer.BYTES + FRONTIER_CAPACITY * Long.BYTES;
    public static final int DRAW_AUDIT_BYTES = COUNTER_COUNT * Integer.BYTES
            + FRONTIER_CAPACITY * Long.BYTES;

    private static final int C_VISITED = 0;
    private static final int C_VISIBLE = 4;
    private static final int C_BLOCKED_DESCENT = 7;
    private static final int C_MISSING_MESH = 11;
    private static final int C_COARSE_FALLBACK = 12;
    private static final int C_COVERAGE_HOLE = 13;
    private static final int C_RENDER_OVERFLOW = 14;
    private static final int C_TRAVERSAL_OVERFLOW = 15;
    private static final int C_FRONTIER_COUNT = 30;
    private static final int C_ANOMALY_COUNT = 31;
    private static final int C_MISSING_VISIBLE_ROOTS = 28;
    private static final int C_REQUEST_OVERFLOW = 25;
    private static final int C_FORENSIC = 63;

    private static final int MAX_PENDING_FRAMES = 96;
    private static final int MAX_LIFECYCLES = 16_384;
    private static final long TRACE_LIMIT = 64L * 1024 * 1024;
    private static final long REMOTE_EVENT_INTERVAL = TimeUnit.SECONDS.toNanos(1);

    public enum Mode {
        OFF, METRICS, FORENSIC;

        static Mode configured() {
            String value = System.getProperty("voxy.debug.lodAudit", "metrics");
            try {
                return valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                Logger.warn("Unknown voxy.debug.lodAudit mode '" + value + "', using metrics");
                return METRICS;
            }
        }
    }

    public record FrameTicket(long sequence, int viewportFrame, long submittedNanos,
                              long viewSignature, long mutationEpoch,
                              boolean viewportValid, boolean taa, int expectedRoots,
                              int activeRoots, long[] missingRoots, boolean rootListOverflow,
                              boolean forensic) {}

    private record Anomaly(long key, int lod, int reason, float ratio) {}

    private record TraversalAudit(long[] counters, List<Anomaly> anomalies,
                                  long[] frontier, boolean overflow) {}

    private record DrawAudit(long[] counters, long[] frontier, boolean overflow) {}
    private record DeliveryView(FrustumIntersection frustum) {}

    private static final class PendingFrame {
        final FrameTicket ticket;
        TraversalAudit traversal;
        DrawAudit draw;

        PendingFrame(FrameTicket ticket) {
            this.ticket = ticket;
        }
    }

    private static final class Lifecycle {
        final long key;
        long revision = -1;
        long contentHash;
        long lastEventNanos;
        long visibleNanos;
        long invalidatedNanos;
        boolean invalidatedVisible;
        long eventEpoch;
        boolean visible;

        Lifecycle(long key) {
            this.key = key;
        }
    }

    private static final Mode MODE = Mode.configured();
    private static final Object LOCK = new Object();
    private static final DetailStateMachine DETAIL = new DetailStateMachine();
    private static final AtomicLong FRAME_SEQUENCE = new AtomicLong();
    private static final AtomicLong TRACE_DROPS = new AtomicLong();
    private static final Map<Integer, Long> ROOT_BY_NODE = new HashMap<>();
    private static final Set<Long> ACTIVE_ROOTS = new HashSet<>();
    private static final WeakHashMap<Viewport, FrameTicket> VIEWPORT_TICKETS = new WeakHashMap<>();
    private static final LinkedHashMap<Long, PendingFrame> PENDING = new LinkedHashMap<>();
    private static final LinkedHashMap<Long, Lifecycle> LIFECYCLES =
            new LinkedHashMap<>(1024, 0.75f, true);
    private static final Map<String, Long> LAST_REMOTE_EVENT = new HashMap<>();
    private static final TraceWriter TRACE = new TraceWriter();

    private static Consumer<String> remoteSink = ignored -> {};
    private static long currentSession;
    private static String dimension = "unknown";
    private static long serverInstance;
    private static long helloCandidateNanos = -1;
    private static long mutationEpoch;
    private static long lastViewSignature;
    private static long lastMutationEpoch;
    private static long lastVisibleHash;
    private static int visibilityOscillationCount;
    private static long frames;
    private static long fullFrames;
    private static long inconclusiveFrames;
    private static long coverageHoleFrames;
    private static long detailErrorFrames;
    private static long lastMissingRoots;
    private static long lastBlockedDescents;
    private static long lastMissingMeshes;
    private static long lastRasterVisible;
    private static long lastMetricsTraceNanos;
    private static DeliveryView deliveryView;
    private static long deliveredSectionBytes;
    private static long offscreenSectionBytes;
    private static long deliveredSections;
    private static long offscreenSections;
    private static long lastDeliveryTraceNanos;

    private LodAudit() {}

    public static boolean enabled() {
        return MODE != Mode.OFF;
    }

    public static boolean forensic() {
        return MODE == Mode.FORENSIC;
    }

    public static void setRemoteSink(Consumer<String> sink) {
        remoteSink = sink == null ? ignored -> {} : sink;
    }

    public static void networkStart(long session, String newDimension) {
        if (!enabled()) return;
        synchronized (LOCK) {
            currentSession = session;
            dimension = newDimension;
            helloCandidateNanos = -1;
            clearSessionState();
            trace("network-start", "\"session\":" + session + ",\"dimension\":" + quote(newDimension));
        }
    }

    public static void rustFrame(short type) {
        if (!enabled()) return;
        if (type == (short) 0x8001) {
            synchronized (LOCK) {
                helloCandidateNanos = System.nanoTime();
                trace("hello-frame", "\"candidateNanos\":" + helloCandidateNanos);
            }
        }
    }

    public static void serverHello(long id) {
        if (!enabled()) return;
        synchronized (LOCK) {
            serverInstance = id;
            long start = helloCandidateNanos >= 0 ? helloCandidateNanos : System.nanoTime();
            DETAIL.handshake(start);
            trace("ttfd-start", "\"startNanos\":" + start + ",\"serverInstance\":"
                    + quote(Long.toUnsignedString(id)));
            remote("ttfd-start", "ttfd-start session=" + currentSession + " dimension=" + dimension
                    + " server=" + Long.toUnsignedString(id));
        }
    }

    public static void networkFailure(Throwable failure) {
        if (!enabled()) return;
        synchronized (LOCK) {
            trace("network-failure", "\"type\":" + quote(failure.getClass().getName())
                    + ",\"message\":" + quote(String.valueOf(failure.getMessage())));
        }
    }

    public static void reset() {
        if (!enabled()) return;
        synchronized (LOCK) {
            trace("session-reset", "\"session\":" + currentSession);
            clearSessionState();
            DETAIL.reset();
            TRACE.closeSession();
        }
    }

    private static void clearSessionState() {
        VIEWPORT_TICKETS.clear();
        PENDING.clear();
        LIFECYCLES.clear();
        mutationEpoch++;
        lastViewSignature = 0;
        lastMutationEpoch = 0;
        lastVisibleHash = 0;
        visibilityOscillationCount = 0;
        lastMetricsTraceNanos = 0;
        deliveryView = null;
        deliveredSectionBytes = 0;
        offscreenSectionBytes = 0;
        deliveredSections = 0;
        offscreenSections = 0;
        lastDeliveryTraceNanos = 0;
    }

    public static void rendererCreated() {
        if (!enabled()) return;
        synchronized (LOCK) {
            ROOT_BY_NODE.clear();
            ACTIVE_ROOTS.clear();
            mutationEpoch++;
            trace("renderer-created", null);
        }
    }

    public static void rendererFreed() {
        if (!enabled()) return;
        synchronized (LOCK) {
            ROOT_BY_NODE.clear();
            ACTIVE_ROOTS.clear();
            VIEWPORT_TICKETS.clear();
            PENDING.clear();
            mutationEpoch++;
            trace("renderer-freed", null);
        }
    }

    public static FrameTicket beginTraversal(Viewport viewport, boolean taa) {
        if (!enabled()) return null;
        long now = System.nanoTime();
        synchronized (LOCK) {
            RootCoverage roots = expectedRoots(viewport);
            long sequence = FRAME_SEQUENCE.incrementAndGet();
            boolean rootOverflow = roots.missing.length > MISSING_ROOT_CAPACITY;
            long[] missing = rootOverflow ? Arrays.copyOf(roots.missing, MISSING_ROOT_CAPACITY) : roots.missing;
            FrameTicket ticket = new FrameTicket(sequence, viewport.frameId, now,
                    viewSignature(viewport, taa), mutationEpoch,
                    viewport.width > 0 && viewport.height > 0, taa, roots.expected,
                    roots.active, missing, rootOverflow, forensic());
            deliveryView = new DeliveryView(new FrustumIntersection(new Matrix4f(viewport.MVP)));
            VIEWPORT_TICKETS.put(viewport, ticket);
            PENDING.put(sequence, new PendingFrame(ticket));
            while (PENDING.size() > MAX_PENDING_FRAMES) {
                PendingFrame dropped = PENDING.remove(PENDING.keySet().iterator().next());
                inconclusiveFrames++;
                trace("audit-frame-dropped", "\"frame\":" + dropped.ticket.sequence);
            }
            return ticket;
        }
    }

    public static FrameTicket currentTicket(Viewport viewport) {
        synchronized (LOCK) {
            FrameTicket ticket = VIEWPORT_TICKETS.get(viewport);
            return ticket != null && ticket.viewportFrame == viewport.frameId ? ticket : null;
        }
    }

    private record RootCoverage(int expected, int active, long[] missing) {}

    private static RootCoverage expectedRoots(Viewport viewport) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) return new RootCoverage(0, 0, new long[0]);
        int radius = Math.max(0, (int) Math.ceil(VoxyConfig.CONFIG.sectionRenderDistance + 1));
        int centerX = ((int) viewport.cameraX) >> 9;
        int centerZ = ((int) viewport.cameraZ) >> 9;
        int minY = minecraft.level.getMinSection() >> 5;
        int maxY = (minecraft.level.getMaxSection() - 1) >> 5;
        int vertical = Math.max(0, maxY - minY + 1);
        long estimated = (long) (radius * 2 + 1) * (radius * 2 + 1) * vertical;
        ArrayList<Long> missing = new ArrayList<>((int) Math.min(estimated, MISSING_ROOT_CAPACITY + 1L));
        int expected = 0;
        int active = 0;
        long radiusSquared = (long) radius * radius;
        for (int dx = -radius; dx <= radius; dx++) {
            int zDistance = (int) Math.sqrt(radiusSquared - (long) dx * dx);
            for (int dz = -zDistance; dz <= zDistance; dz++) {
                for (int y = minY; y <= maxY; y++) {
                    long key = WorldEngine.getWorldSectionId(4, centerX + dx, y, centerZ + dz);
                    expected++;
                    if (ACTIVE_ROOTS.contains(key)) active++;
                    else missing.add(key);
                }
            }
        }
        long[] values = new long[missing.size()];
        for (int i = 0; i < values.length; i++) values[i] = missing.get(i);
        return new RootCoverage(expected, active, values);
    }

    public static void traversalResult(FrameTicket ticket, long pointer) {
        if (ticket == null || !enabled()) return;
        long[] counters = readCounters(pointer);
        int anomalyCount = unsignedBound(counters[C_ANOMALY_COUNT], ANOMALY_CAPACITY);
        ArrayList<Anomaly> anomalies = new ArrayList<>(anomalyCount);
        long anomalyBase = pointer + COUNTER_COUNT * Integer.BYTES;
        for (int i = 0; i < anomalyCount; i++) {
            long base = anomalyBase + i * 16L;
            long key = rawKey(base);
            int packed = MemoryUtil.memGetInt(base + 8);
            anomalies.add(new Anomaly(key, packed >>> 16, packed & 0xffff,
                    Float.intBitsToFloat(MemoryUtil.memGetInt(base + 12))));
        }
        long frontierBase = anomalyBase + ANOMALY_CAPACITY * 16L;
        long[] frontier = ticket.forensic ? readKeys(frontierBase, counters[C_FRONTIER_COUNT]) : new long[0];
        boolean overflow = counters[C_ANOMALY_COUNT] > ANOMALY_CAPACITY
                || (ticket.forensic && counters[C_FRONTIER_COUNT] > FRONTIER_CAPACITY);
        synchronized (LOCK) {
            PendingFrame pending = PENDING.get(ticket.sequence);
            if (pending == null) return;
            pending.traversal = new TraversalAudit(counters, anomalies, frontier, overflow);
            complete(pending);
        }
    }

    public static void drawResult(FrameTicket ticket, long pointer) {
        if (ticket == null || !enabled()) return;
        long[] counters = readCounters(pointer);
        long[] frontier = ticket.forensic
                ? readKeys(pointer + COUNTER_COUNT * Integer.BYTES, counters[C_FRONTIER_COUNT]) : new long[0];
        boolean overflow = ticket.forensic && counters[C_FRONTIER_COUNT] > FRONTIER_CAPACITY;
        synchronized (LOCK) {
            PendingFrame pending = PENDING.get(ticket.sequence);
            if (pending == null) return;
            pending.draw = new DrawAudit(counters, frontier, overflow);
            complete(pending);
        }
    }

    private static long[] readCounters(long pointer) {
        long[] counters = new long[COUNTER_COUNT];
        for (int i = 0; i < counters.length; i++) {
            counters[i] = Integer.toUnsignedLong(MemoryUtil.memGetInt(pointer + i * 4L));
        }
        return counters;
    }

    private static long[] readKeys(long pointer, long rawCount) {
        int count = unsignedBound(rawCount, FRONTIER_CAPACITY);
        long[] keys = new long[count];
        for (int i = 0; i < count; i++) keys[i] = rawKey(pointer + i * 8L);
        return keys;
    }

    private static int unsignedBound(long value, int maximum) {
        return (int) Math.min(Math.max(0, value), maximum);
    }

    private static long rawKey(long pointer) {
        return Integer.toUnsignedLong(MemoryUtil.memGetInt(pointer)) << 32
                | Integer.toUnsignedLong(MemoryUtil.memGetInt(pointer + 4));
    }

    private static void complete(PendingFrame pending) {
        if (pending.traversal == null || pending.draw == null) return;
        PENDING.remove(pending.ticket.sequence);
        frames++;
        FrameTicket ticket = pending.ticket;
        TraversalAudit traversal = pending.traversal;
        DrawAudit draw = pending.draw;
        long[] tc = traversal.counters;
        long[] dc = draw.counters;
        long coverageHoles = tc[C_COVERAGE_HOLE]
                + (tc[C_VISIBLE] > 0 && selectedCount(tc) == 0 ? 1 : 0);
        DetailDecision.Result decision = DetailDecision.evaluate(new DetailDecision.Input(
                ticket.viewportValid, tc[C_VISITED], tc[C_MISSING_VISIBLE_ROOTS],
                tc[C_BLOCKED_DESCENT], tc[C_MISSING_MESH], coverageHoles,
                tc[C_REQUEST_OVERFLOW], tc[C_RENDER_OVERFLOW], tc[C_TRAVERSAL_OVERFLOW],
                ticket.rootListOverflow,
                traversal.overflow || draw.overflow));
        boolean conclusive = decision.conclusive();
        boolean full = decision.fullDetail();
        String blockers = decision.blockers();

        if (full) fullFrames++;
        if (!conclusive) inconclusiveFrames++;
        if (tc[C_MISSING_VISIBLE_ROOTS] > 0 || coverageHoles > 0) {
            coverageHoleFrames++;
            remote("coverage-hole", "coverage-hole frame=" + ticket.sequence
                    + " missingRoots=" + tc[C_MISSING_VISIBLE_ROOTS]
                    + " visibleNodes=" + tc[C_VISIBLE] + " selected=" + selectedCount(tc)
                    + " uncoveredBranches=" + coverageHoles);
        }
        if (tc[C_BLOCKED_DESCENT] > 0 || tc[C_MISSING_MESH] > 0) detailErrorFrames++;

        lastMissingRoots = tc[C_MISSING_VISIBLE_ROOTS];
        lastBlockedDescents = tc[C_BLOCKED_DESCENT];
        lastMissingMeshes = tc[C_MISSING_MESH];
        lastRasterVisible = dc[1];

        DetailStateMachine.Transition transition = DETAIL.accept(new DetailStateMachine.Frame(
                ticket.sequence, ticket.submittedNanos, full, conclusive, blockers));
        if (transition.initialTtfdNanos() >= 0) {
            long millis = TimeUnit.NANOSECONDS.toMillis(transition.initialTtfdNanos());
            trace("ttfd-complete", "\"frame\":" + ticket.sequence + ",\"milliseconds\":" + millis);
            remote("ttfd-complete", "ttfd-complete ms=" + millis + " frame=" + ticket.sequence);
        }
        if (transition.before() == DetailStateMachine.State.FULL_DETAIL
                && transition.after() == DetailStateMachine.State.DEGRADED) {
            trace("full-detail-lost", "\"frame\":" + ticket.sequence + ",\"blockers\":" + quote(blockers));
            remote("full-detail-lost", "full-detail-lost frame=" + ticket.sequence + " " + blockers);
        }
        if (transition.degradedDurationNanos() >= 0) {
            long millis = TimeUnit.NANOSECONDS.toMillis(transition.degradedDurationNanos());
            long fullToFullMillis = TimeUnit.NANOSECONDS.toMillis(transition.lastFullToFullNanos());
            trace("full-detail-restored", "\"frame\":" + ticket.sequence
                    + ",\"degradedMilliseconds\":" + millis
                    + ",\"lastFullToFullMilliseconds\":" + fullToFullMillis);
            remote("full-detail-restored", "full-detail-restored degradedMs=" + millis
                    + " lastFullToFullMs=" + fullToFullMillis + " frame=" + ticket.sequence);
        }
        if (transition.stableFullDetail()) {
            trace("stable-full-detail", "\"frame\":" + ticket.sequence + ",\"frames\":30");
            remote("stable-full-detail", "stable-full-detail frame=" + ticket.sequence + " frames=30");
        }

        detectVisibilityOscillation(ticket, traversal, draw);
        if (ticket.forensic) updateVisibleLifecycles(ticket, draw.frontier);
        if (ticket.forensic || ticket.submittedNanos - lastMetricsTraceNanos >= TimeUnit.SECONDS.toNanos(1)) {
            lastMetricsTraceNanos = ticket.submittedNanos;
            traceFrame(ticket, traversal, draw, conclusive, full, blockers);
        }
    }

    private static long selectedCount(long[] counters) {
        long total = 0;
        for (int i = 16; i <= 20; i++) total += counters[i];
        return total;
    }

    private static void detectVisibilityOscillation(FrameTicket ticket, TraversalAudit traversal,
                                                    DrawAudit draw) {
        long hash = draw.counters[24] << 32 ^ draw.counters[25]
                ^ Long.rotateLeft(draw.counters[26] << 32 ^ draw.counters[27], 17);
        // A stable camera does not make the raster frontier immutable: HiZ, raster rejection,
        // temporal drawing, and newly completed geometry can all change it legitimately.
        boolean unexplained = traversal.counters[3] == 0
                && draw.counters[2] == 0 && draw.counters[3] == 0;
        if (ticket.viewSignature == lastViewSignature && ticket.mutationEpoch == lastMutationEpoch
                && unexplained && lastVisibleHash != 0 && hash != lastVisibleHash) {
            visibilityOscillationCount++;
            if (visibilityOscillationCount >= 3) {
                trace("visibility-oscillation", "\"frame\":" + ticket.sequence
                        + ",\"previousHash\":" + quote(Long.toUnsignedString(lastVisibleHash))
                        + ",\"currentHash\":" + quote(Long.toUnsignedString(hash)));
                remote("visibility-oscillation", "visibility-oscillation frame=" + ticket.sequence
                        + " stableView=true mutation=false");
                visibilityOscillationCount = 0;
            }
        } else {
            visibilityOscillationCount = 0;
        }
        lastViewSignature = ticket.viewSignature;
        lastMutationEpoch = ticket.mutationEpoch;
        lastVisibleHash = hash;
    }

    private static void updateVisibleLifecycles(FrameTicket ticket, long[] visibleKeys) {
        HashSet<Long> visible = new HashSet<>(visibleKeys.length * 2);
        for (long key : visibleKeys) {
            visible.add(key);
            Lifecycle lifecycle = lifecycle(key);
            lifecycle.visible = true;
            lifecycle.visibleNanos = ticket.submittedNanos;
            lifecycle("raster-visible", key, lifecycle.revision, "\"frame\":" + ticket.sequence);
        }
        for (Lifecycle lifecycle : LIFECYCLES.values()) {
            if (lifecycle.visible && !visible.contains(lifecycle.key)) {
                lifecycle.visible = false;
                lifecycle("raster-hidden", lifecycle.key, lifecycle.revision,
                        "\"frame\":" + ticket.sequence);
            }
        }
    }

    private static void traceFrame(FrameTicket ticket, TraversalAudit traversal, DrawAudit draw,
                                   boolean conclusive, boolean full, String blockers) {
        long[] c = traversal.counters;
        long[] d = draw.counters;
        StringBuilder json = new StringBuilder(1024)
                .append("\"frame\":").append(ticket.sequence)
                .append(",\"viewportFrame\":").append(ticket.viewportFrame)
                .append(",\"submittedNanos\":").append(ticket.submittedNanos)
                .append(",\"viewSignature\":").append(quote(Long.toUnsignedString(ticket.viewSignature)))
                .append(",\"state\":").append(quote(DETAIL.state().name()))
                .append(",\"conclusive\":").append(conclusive)
                .append(",\"fullDetail\":").append(full)
                .append(",\"blockers\":").append(quote(blockers))
                .append(",\"expectedRoots\":").append(ticket.expectedRoots)
                .append(",\"activeRoots\":").append(ticket.activeRoots)
                .append(",\"missingRootCandidates\":").append(ticket.missingRoots.length)
                .append(",\"missingVisibleRoots\":").append(c[C_MISSING_VISIBLE_ROOTS])
                .append(",\"visited\":").append(c[C_VISITED])
                .append(",\"visibleNodes\":").append(c[C_VISIBLE])
                .append(",\"blockedDescent\":").append(c[C_BLOCKED_DESCENT])
                .append(",\"missingMesh\":").append(c[C_MISSING_MESH])
                .append(",\"coarseFallbacks\":").append(c[C_COARSE_FALLBACK])
                .append(",\"coverageHoles\":").append(c[C_COVERAGE_HOLE])
                .append(",\"requestsInFlight\":").append(c[8])
                .append(",\"newRequests\":").append(c[9])
                .append(",\"requestSoftLimit\":").append(c[10])
                .append(",\"requestOverflow\":").append(c[C_REQUEST_OVERFLOW])
                .append(",\"renderOverflow\":").append(c[C_RENDER_OVERFLOW])
                .append(",\"traversalOverflow\":").append(c[C_TRAVERSAL_OVERFLOW])
                .append(",\"selected\":").append(selectedCount(c))
                .append(",\"rasterVisible\":").append(d[1])
                .append(",\"rasterRejected\":").append(d[2])
                .append(",\"temporal\":").append(d[3])
                .append(",\"opaqueCommands\":").append(d[6])
                .append(",\"translucentCommands\":").append(d[7])
                .append(",\"taa\":").append(ticket.taa);
        if (!traversal.anomalies.isEmpty()) {
            json.append(",\"anomalies\":[");
            for (int i = 0; i < traversal.anomalies.size(); i++) {
                if (i != 0) json.append(',');
                Anomaly anomaly = traversal.anomalies.get(i);
                json.append("{\"key\":").append(quote(Long.toUnsignedString(anomaly.key)))
                        .append(",\"lod\":").append(anomaly.lod)
                        .append(",\"reason\":").append(anomaly.reason)
                        .append(",\"ratio\":").append(anomaly.ratio).append('}');
            }
            json.append(']');
        }
        trace("frame", json.toString());
    }

    public static void rootCpuReady(long key, int nodeId) {
        if (!enabled()) return;
        synchronized (LOCK) {
            ROOT_BY_NODE.put(nodeId, key);
            if (forensic()) lifecycle("root-cpu-ready", key, -1, "\"nodeId\":" + nodeId);
        }
    }

    public static void rootGpuActive(int nodeId) {
        if (!enabled()) return;
        synchronized (LOCK) {
            Long key = ROOT_BY_NODE.get(nodeId);
            if (key == null) {
                trace("root-mapping-missing", "\"nodeId\":" + nodeId + ",\"operation\":\"add\"");
                return;
            }
            ACTIVE_ROOTS.add(key);
            mutationEpoch++;
            if (forensic()) lifecycle("root-gpu-active", key, -1, "\"nodeId\":" + nodeId);
        }
    }

    public static void rootGpuRemoved(int nodeId) {
        if (!enabled()) return;
        synchronized (LOCK) {
            Long key = ROOT_BY_NODE.remove(nodeId);
            if (key == null) {
                trace("root-mapping-missing", "\"nodeId\":" + nodeId + ",\"operation\":\"remove\"");
                return;
            }
            ACTIVE_ROOTS.remove(key);
            mutationEpoch++;
            if (forensic()) lifecycle("root-gpu-removed", key, -1, "\"nodeId\":" + nodeId);
        }
    }

    public static void watched(long key) {
        if (forensic()) lifecycleEvent("watch", key, -1, 0, null);
    }

    public static void unwatched(long key) {
        if (forensic()) lifecycleEvent("unwatch", key, -1, 0, null);
    }

    public static void sectionPrepared(long key, long revision, long[] data) {
        if (!forensic()) return;
        long hash = hashSection(data);
        lifecycleEvent("section-received", key, revision, hash, null);
        lifecycleEvent("prepared", key, revision, hash, null);
    }

    /** Measures compressed section bytes delivered outside a one-section-expanded view frustum. */
    public static void sectionDelivered(long key, int wireBytes) {
        if (!enabled()) return;
        synchronized (LOCK) {
            deliveredSections++;
            deliveredSectionBytes += wireBytes;
            DeliveryView view = deliveryView;
            if (view != null) {
                int level = WorldEngine.getLevel(key);
                float width = 32 << level;
                float minX = WorldEngine.getX(key) * width - width;
                float minY = WorldEngine.getY(key) * width - width;
                float minZ = WorldEngine.getZ(key) * width - width;
                boolean visible = view.frustum.testAab(minX, minY, minZ,
                        minX + width * 3, minY + width * 3, minZ + width * 3);
                if (!visible) {
                    offscreenSections++;
                    offscreenSectionBytes += wireBytes;
                }
            }
            long now = System.nanoTime();
            if (lastDeliveryTraceNanos == 0) lastDeliveryTraceNanos = now;
            if (now - lastDeliveryTraceNanos >= TimeUnit.SECONDS.toNanos(1)) {
                double percent = deliveredSectionBytes == 0 ? 0
                        : offscreenSectionBytes * 100.0 / deliveredSectionBytes;
                trace("section-delivery", "\"sections\":" + deliveredSections
                        + ",\"bytes\":" + deliveredSectionBytes
                        + ",\"offscreenSections\":" + offscreenSections
                        + ",\"offscreenBytes\":" + offscreenSectionBytes
                        + ",\"offscreenPercent\":" + String.format(Locale.ROOT, "%.2f", percent));
                deliveredSectionBytes = 0;
                offscreenSectionBytes = 0;
                deliveredSections = 0;
                offscreenSections = 0;
                lastDeliveryTraceNanos = now;
            }
        }
    }

    public static void sectionApplyAttempt(long key, long revision) {
        if (forensic()) lifecycleEvent("apply-attempt", key, revision, 0, null);
    }

    public static void sectionInstalled(long key, long revision, long[] data) {
        long now = System.nanoTime();
        synchronized (LOCK) {
            Lifecycle lifecycle = forensic() ? lifecycle(key) : LIFECYCLES.get(key);
            if (lifecycle == null) {
                mutationEpoch++;
                return;
            }
            long gap = lifecycle.invalidatedNanos == 0 || !lifecycle.invalidatedVisible
                    ? -1 : now - lifecycle.invalidatedNanos;
            lifecycle.revision = revision;
            lifecycle.contentHash = forensic() ? hashSection(data) : 0;
            lifecycle.invalidatedNanos = 0;
            lifecycle.invalidatedVisible = false;
            lifecycle.lastEventNanos = now;
            lifecycle.eventEpoch = ++mutationEpoch;
            if (forensic()) {
                lifecycle("installed", key, revision, "\"contentHash\":"
                        + quote(Long.toUnsignedString(lifecycle.contentHash)));
            }
            if (gap >= 0) {
                long millis = TimeUnit.NANOSECONDS.toMillis(gap);
                trace("invalidation-replacement", "\"key\":" + quote(Long.toUnsignedString(key))
                        + ",\"revision\":" + quote(Long.toUnsignedString(revision))
                        + ",\"gapMilliseconds\":" + millis);
                remote("invalidation-replacement", "invalidation-replacement key="
                        + Long.toUnsignedString(key) + " gapMs=" + millis);
            }
        }
    }

    public static void sectionInvalidated(long key, long revision) {
        long now = System.nanoTime();
        synchronized (LOCK) {
            Lifecycle lifecycle = lifecycle(key);
            lifecycle.revision = revision;
            lifecycle.invalidatedNanos = now;
            lifecycle.invalidatedVisible = lifecycle.visible;
            lifecycle.lastEventNanos = now;
            lifecycle.eventEpoch = ++mutationEpoch;
            lifecycle("invalidated", key, revision, "\"wasVisible\":" + lifecycle.visible);
            if (lifecycle.visible) {
                remote("visible-invalidation", "visible-invalidation key=" + Long.toUnsignedString(key)
                        + " revision=" + Long.toUnsignedString(revision));
            }
        }
    }

    public static void geometryRequested(long key) {
        if (forensic()) lifecycleEvent("geometry-requested", key, -1, 0, null);
    }

    public static void geometryBuilt(long key, boolean empty) {
        if (!enabled()) return;
        if (forensic()) {
            lifecycleEvent("geometry-built", key, -1, 0, "\"empty\":" + empty);
        } else synchronized (LOCK) {
            mutationEpoch++;
        }
    }

    public static void sectionDropped(long key, long revision, String reason) {
        if (forensic()) lifecycleEvent("dropped-obsolete", key, revision, 0,
                "\"reason\":" + quote(reason));
    }

    /** Records the exact keys in a generated C_SUBSCRIBE frame, without touching production. */
    public static void subscriptionFrame(byte[] payload) {
        if (!forensic()) return;
        try {
            ByteBuffer input = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
            if (input.remaining() < 6) throw new IllegalArgumentException("short subscription frame");
            int dimensionLength = Short.toUnsignedInt(input.getShort());
            if (dimensionLength > input.remaining() - 4) throw new IllegalArgumentException("bad dimension length");
            input.position(input.position() + dimensionLength);
            int additions = Short.toUnsignedInt(input.getShort());
            int removals = Short.toUnsignedInt(input.getShort());
            if ((long) additions * 16 + (long) removals * 8 != input.remaining()) {
                throw new IllegalArgumentException("bad subscription payload length");
            }
            for (int i = 0; i < additions; i++) {
                long key = input.getLong();
                long revision = input.getLong();
                lifecycleEvent("subscription-sent", key, revision, 0, "\"operation\":\"add\"");
            }
            for (int i = 0; i < removals; i++) {
                lifecycleEvent("subscription-sent", input.getLong(), -1, 0,
                        "\"operation\":\"remove\"");
            }
        } catch (RuntimeException failure) {
            synchronized (LOCK) {
                trace("subscription-trace-error", "\"message\":" + quote(failure.getMessage()));
            }
        }
    }

    private static void lifecycleEvent(String event, long key, long revision, long hash, String fields) {
        if (!enabled()) return;
        synchronized (LOCK) {
            Lifecycle lifecycle = lifecycle(key);
            if (revision >= 0) lifecycle.revision = revision;
            if (hash != 0) lifecycle.contentHash = hash;
            lifecycle.lastEventNanos = System.nanoTime();
            lifecycle.eventEpoch = ++mutationEpoch;
            lifecycle(event, key, revision, fields);
        }
    }

    private static Lifecycle lifecycle(long key) {
        Lifecycle lifecycle = LIFECYCLES.computeIfAbsent(key, Lifecycle::new);
        while (LIFECYCLES.size() > MAX_LIFECYCLES) {
            Long eldest = LIFECYCLES.keySet().iterator().next();
            LIFECYCLES.remove(eldest);
        }
        return lifecycle;
    }

    private static void lifecycle(String event, long key, long revision, String fields) {
        StringBuilder values = new StringBuilder()
                .append("\"key\":").append(quote(Long.toUnsignedString(key)));
        if (revision >= 0) values.append(",\"revision\":").append(quote(Long.toUnsignedString(revision)));
        if (fields != null && !fields.isEmpty()) values.append(',').append(fields);
        trace(event, values.toString());
    }

    public static String summary() {
        if (!enabled()) return "lodAudit=off";
        synchronized (LOCK) {
            return "lodAudit=" + MODE.name().toLowerCase(Locale.ROOT)
                    + " lodState=" + DETAIL.state()
                    + " auditFrames=" + frames
                    + " auditFullFrames=" + fullFrames
                    + " auditInconclusive=" + inconclusiveFrames
                    + " coverageHoleFrames=" + coverageHoleFrames
                    + " detailErrorFrames=" + detailErrorFrames
                    + " missingVisibleRoots=" + lastMissingRoots
                    + " blockedDescents=" + lastBlockedDescents
                    + " missingMeshes=" + lastMissingMeshes
                    + " rasterVisible=" + lastRasterVisible
                    + " traceDrops=" + TRACE_DROPS.get();
        }
    }

    private static long viewSignature(Viewport viewport, boolean taa) {
        long hash = 0xcbf29ce484222325L;
        hash = mix(hash, quantize(viewport.cameraX, 1000));
        hash = mix(hash, quantize(viewport.cameraY, 1000));
        hash = mix(hash, quantize(viewport.cameraZ, 1000));
        Matrix4fc matrix = viewport.MVP;
        hash = mix(hash, matrixHash(matrix));
        hash = mix(hash, viewport.width);
        hash = mix(hash, viewport.height);
        hash = mix(hash, Float.floatToRawIntBits(VoxyConfig.CONFIG.subDivisionSize));
        hash = mix(hash, Float.floatToRawIntBits(VoxyConfig.CONFIG.sectionRenderDistance));
        return mix(hash, taa ? 1 : 0);
    }

    private static long matrixHash(Matrix4fc matrix) {
        long hash = 0x9e3779b97f4a7c15L;
        hash = mix(hash, quantize(matrix.m00(), 100_000));
        hash = mix(hash, quantize(matrix.m01(), 100_000));
        hash = mix(hash, quantize(matrix.m02(), 100_000));
        hash = mix(hash, quantize(matrix.m03(), 100_000));
        hash = mix(hash, quantize(matrix.m10(), 100_000));
        hash = mix(hash, quantize(matrix.m11(), 100_000));
        hash = mix(hash, quantize(matrix.m12(), 100_000));
        hash = mix(hash, quantize(matrix.m13(), 100_000));
        hash = mix(hash, quantize(matrix.m20(), 100_000));
        hash = mix(hash, quantize(matrix.m21(), 100_000));
        hash = mix(hash, quantize(matrix.m22(), 100_000));
        hash = mix(hash, quantize(matrix.m23(), 100_000));
        hash = mix(hash, quantize(matrix.m30(), 100_000));
        hash = mix(hash, quantize(matrix.m31(), 100_000));
        hash = mix(hash, quantize(matrix.m32(), 100_000));
        return mix(hash, quantize(matrix.m33(), 100_000));
    }

    private static long quantize(double value, double scale) {
        return Math.round(value * scale);
    }

    private static long mix(long hash, long value) {
        hash ^= value;
        hash *= 0x100000001b3L;
        return hash;
    }

    private static long hashSection(long[] data) {
        long hash = 0x9e3779b97f4a7c15L;
        for (long value : data) {
            value ^= value >>> 33;
            value *= 0xff51afd7ed558ccdL;
            value ^= value >>> 33;
            hash ^= value;
            hash = Long.rotateLeft(hash, 27) * 5 + 0x52dce729;
        }
        return hash;
    }

    private static void remote(String category, String message) {
        long now = System.nanoTime();
        long previous = LAST_REMOTE_EVENT.getOrDefault(category, 0L);
        if (now - previous < REMOTE_EVENT_INTERVAL) return;
        LAST_REMOTE_EVENT.put(category, now);
        try {
            remoteSink.accept(message);
        } catch (RuntimeException failure) {
            Logger.warn("LOD audit could not send remote milestone", failure);
        }
    }

    private static void trace(String event, String fields) {
        if (!enabled()) return;
        StringBuilder line = new StringBuilder((fields == null ? 0 : fields.length()) + 192)
                .append("{\"event\":").append(quote(event))
                .append(",\"time\":").append(quote(Instant.now().toString()))
                .append(",\"nanoTime\":").append(System.nanoTime())
                .append(",\"session\":").append(currentSession)
                .append(",\"dimension\":").append(quote(dimension))
                .append(",\"serverInstance\":").append(quote(Long.toUnsignedString(serverInstance)));
        if (fields != null && !fields.isEmpty()) line.append(',').append(fields);
        line.append('}');
        if (!TRACE.offer(currentSession, line.toString())) TRACE_DROPS.incrementAndGet();
    }

    private static String quote(String input) {
        if (input == null) return "null";
        StringBuilder result = new StringBuilder(input.length() + 8).append('"');
        for (int i = 0; i < input.length(); i++) {
            char value = input.charAt(i);
            switch (value) {
                case '"' -> result.append("\\\"");
                case '\\' -> result.append("\\\\");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> {
                    if (value < 0x20) result.append(String.format("\\u%04x", (int) value));
                    else result.append(value);
                }
            }
        }
        return result.append('"').toString();
    }

    private static final class TraceWriter {
        private record TraceRecord(long session, String line, boolean close) {}
        private final ArrayBlockingQueue<TraceRecord> queue = new ArrayBlockingQueue<>(8192);
        private final Thread thread;

        TraceWriter() {
            thread = new Thread(this::run, "Voxy LOD forensic trace");
            thread.setDaemon(true);
            thread.start();
        }

        boolean offer(long session, String line) {
            return queue.offer(new TraceRecord(session, line, false));
        }

        void closeSession() {
            queue.offer(new TraceRecord(currentSession, null, true));
        }

        private void run() {
            BufferedWriter writer = null;
            long openSession = Long.MIN_VALUE;
            long bytes = 0;
            try {
                while (true) {
                    TraceRecord record = queue.take();
                    if (record.close) {
                        if (writer != null && openSession == record.session) {
                            writer.close();
                            writer = null;
                            openSession = Long.MIN_VALUE;
                            bytes = 0;
                        }
                        continue;
                    }
                    long session = record.session;
                    if (writer == null || openSession != session) {
                        if (writer != null) writer.close();
                        Path logs = Minecraft.getInstance().gameDirectory.toPath().resolve("logs");
                        Files.createDirectories(logs);
                        Path path = logs.resolve("voxy-lod-debug-" + session + ".jsonl");
                        writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                                StandardOpenOption.WRITE);
                        openSession = session;
                        bytes = 0;
                    }
                    int encodedBytes = record.line.getBytes(StandardCharsets.UTF_8).length + 1;
                    if (bytes + encodedBytes > TRACE_LIMIT) {
                        TRACE_DROPS.incrementAndGet();
                        continue;
                    }
                    writer.write(record.line);
                    writer.newLine();
                    bytes += encodedBytes;
                    if ((bytes & 0xffff) < encodedBytes) writer.flush();
                }
            } catch (InterruptedException interruption) {
                Thread.currentThread().interrupt();
            } catch (IOException | RuntimeException failure) {
                TRACE_DROPS.incrementAndGet();
                Logger.warn("Voxy LOD trace writer stopped", failure);
            } finally {
                if (writer != null) {
                    try { writer.close(); } catch (IOException ignored) {}
                }
            }
        }
    }
}
