package me.cortex.voxy.client.lod;

import me.cortex.voxy.network.DebugPayload;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Low-rate diagnostics compiled only into the debug client JAR. */
final class ClientLodDebug {
    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static final AtomicLong BRIDGE_IN_BYTES = new AtomicLong();
    private static final AtomicLong BRIDGE_IN_PACKETS = new AtomicLong();
    private static final AtomicLong BRIDGE_OUT_BYTES = new AtomicLong();
    private static final AtomicLong BRIDGE_OUT_PACKETS = new AtomicLong();
    private static final AtomicLong RUST_BYTES = new AtomicLong();
    private static final AtomicLong RUST_FRAMES = new AtomicLong();
    private static final AtomicLong SECTION_FRAMES = new AtomicLong();
    private static final AtomicLong SECTIONS_APPLIED = new AtomicLong();
    private static final AtomicLong INVALIDATIONS = new AtomicLong();
    private static final AtomicLong DROPPED_UNSUBSCRIBED = new AtomicLong();
    private static final AtomicLong DROPPED_REVISION = new AtomicLong();
    private static final AtomicLong SUBSCRIBED = new AtomicLong();
    private static final AtomicLong UNSUBSCRIBED = new AtomicLong();
    private static final AtomicLong CREDIT_KIB = new AtomicLong();
    private static final AtomicLong LAST_RTT_MS = new AtomicLong(-1);
    private static final AtomicLong MAX_RTT_MS = new AtomicLong(-1);

    private static long lastTick;
    private static long lastSample;
    private static long maxTickGap;
    private static int ticks;
    private static boolean announced;

    private ClientLodDebug() {}

    static void register(PayloadRegistrar registrar) {
        registrar.playBidirectional(DebugPayload.TYPE, DebugPayload.CODEC,
                (payload, context) -> acknowledge(payload));
    }

    static void tick() {
        long now = System.nanoTime();
        if (lastTick != 0) maxTickGap = Math.max(maxTickGap, now - lastTick);
        lastTick = now;
        ticks++;
        if (lastSample != 0 && now - lastSample < 1_000_000_000L) return;
        lastSample = now;

        Minecraft minecraft = Minecraft.getInstance();
        var listener = minecraft.getConnection();
        if (listener == null || !listener.hasChannel(DebugPayload.TYPE)) return;
        if (!announced) {
            announced = true;
            send("debug-client-start protocol=4");
        }

        int ping = -1;
        if (minecraft.player != null) {
            var playerInfo = listener.getPlayerInfo(minecraft.player.getUUID());
            if (playerInfo != null) ping = playerInfo.getLatency();
        }
        var channel = listener.getConnection().channel();
        send("sample ticks=" + ticks
                + " tickGapMaxMs=" + nanosToMillis(maxTickGap)
                + " fps=" + minecraft.getFps()
                + " mcPingMs=" + ping
                + " debugRttMs=" + LAST_RTT_MS.get()
                + " debugRttMaxMs=" + MAX_RTT_MS.getAndSet(LAST_RTT_MS.get())
                + " mcWritable=" + channel.isWritable()
                + " mcBytesBeforeWritable=" + channel.bytesBeforeWritable()
                + " mcBytesBeforeUnwritable=" + channel.bytesBeforeUnwritable()
                + " bridgeInPackets=" + BRIDGE_IN_PACKETS.getAndSet(0)
                + " bridgeInKiB=" + kib(BRIDGE_IN_BYTES.getAndSet(0))
                + " bridgeOutPackets=" + BRIDGE_OUT_PACKETS.getAndSet(0)
                + " bridgeOutKiB=" + kib(BRIDGE_OUT_BYTES.getAndSet(0))
                + " rustFrames=" + RUST_FRAMES.getAndSet(0)
                + " rustKiB=" + kib(RUST_BYTES.getAndSet(0))
                + " sectionFrames=" + SECTION_FRAMES.getAndSet(0)
                + " sectionsApplied=" + SECTIONS_APPLIED.getAndSet(0)
                + " invalidations=" + INVALIDATIONS.getAndSet(0)
                + " droppedUnsubscribed=" + DROPPED_UNSUBSCRIBED.getAndSet(0)
                + " droppedRevision=" + DROPPED_REVISION.getAndSet(0)
                + " subscribed=" + SUBSCRIBED.getAndSet(0)
                + " unsubscribed=" + UNSUBSCRIBED.getAndSet(0)
                + " creditKiB=" + CREDIT_KIB.getAndSet(0)
                + " desiredSections=" + ClientLodNetwork.debugDesiredSections()
                + " inboundFrames=" + ClientLodNetwork.debugInboundFrames()
                + " inboundKiB=" + ClientLodNetwork.debugInboundKiB());
        ticks = 0;
        maxTickGap = 0;
    }

    static long timer() {
        return System.nanoTime();
    }

    static void minecraftDisconnect() {
        event("minecraft-disconnect");
    }

    static void networkStart(long session, String dimension) {
        event("network-start session=" + session + " dimension=" + dimension);
    }

    static void networkFailure(Throwable failure) {
        event("network-failure type=" + failure.getClass().getSimpleName()
                + " message=" + failure.getMessage());
    }

    static void serverHello(long id, boolean restart, boolean resetSections, int blockEpoch, int biomeEpoch) {
        event("server-hello id=" + Long.toUnsignedString(id)
                + " restart=" + restart + " resetSections=" + resetSections
                + " blockEpoch=" + blockEpoch + " biomeEpoch=" + biomeEpoch);
    }

    static void mappingDelta(int blocks, int biomes, long startedNanos) {
        event("mapping-delta blocks=" + blocks + " biomes=" + biomes
                + " applyMs=" + elapsedMillis(startedNanos));
    }

    static void transportResponse(long startedNanos, byte mode, int protocol) {
        event("transport-response elapsedMs=" + elapsedMillis(startedNanos)
                + " mode=" + mode + " protocol=" + protocol);
    }

    static void transportOpen(boolean direct, String description) {
        event("transport-open direct=" + direct + " description=" + description);
    }

    static void bridgeInputOverflow(int queued) {
        event("bridge-input-overflow queued=" + queued);
    }

    private static void event(String message) {
        send("event " + message);
    }

    private static void send(String message) {
        var listener = Minecraft.getInstance().getConnection();
        if (listener == null || !listener.hasChannel(DebugPayload.TYPE)) return;
        String safe = message.replace('\n', ' ').replace('\r', ' ');
        if (safe.length() > DebugPayload.MAX_MESSAGE_LENGTH) {
            safe = safe.substring(0, DebugPayload.MAX_MESSAGE_LENGTH);
        }
        listener.send(new DebugPayload(SEQUENCE.incrementAndGet(), System.nanoTime(), safe));
    }

    private static void acknowledge(DebugPayload payload) {
        if (!payload.message().isEmpty()) return;
        long elapsed = System.nanoTime() - payload.sentNanos();
        if (elapsed < 0) return;
        long millis = nanosToMillis(elapsed);
        LAST_RTT_MS.set(millis);
        MAX_RTT_MS.accumulateAndGet(millis, Math::max);
    }

    static void bridgeIn(int bytes) {
        BRIDGE_IN_PACKETS.incrementAndGet();
        BRIDGE_IN_BYTES.addAndGet(bytes);
    }

    static void bridgeOut(int bytes) {
        BRIDGE_OUT_PACKETS.incrementAndGet();
        BRIDGE_OUT_BYTES.addAndGet(bytes);
    }

    static void rustFrame(short type, int bytes) {
        RUST_FRAMES.incrementAndGet();
        RUST_BYTES.addAndGet(bytes + 16L);
        if (type == (short) 0x8003) SECTION_FRAMES.incrementAndGet();
    }

    static void sectionApplied() {
        SECTIONS_APPLIED.incrementAndGet();
    }

    static void invalidationApplied() {
        INVALIDATIONS.incrementAndGet();
    }

    static void droppedUnsubscribed() {
        DROPPED_UNSUBSCRIBED.incrementAndGet();
    }

    static void droppedRevision() {
        DROPPED_REVISION.incrementAndGet();
    }

    static void subscriptionBatch(int additions, int removals) {
        SUBSCRIBED.addAndGet(additions);
        UNSUBSCRIBED.addAndGet(removals);
    }

    static void credit(long bytes) {
        CREDIT_KIB.addAndGet((bytes + 1023) >>> 10);
    }

    static void reset() {
        announced = false;
        lastTick = 0;
        lastSample = 0;
        maxTickGap = 0;
        ticks = 0;
    }

    private static long nanosToMillis(long nanos) {
        return (nanos + 500_000L) / 1_000_000L;
    }

    private static long elapsedMillis(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }

    private static long kib(long bytes) {
        return (bytes + 512L) / 1024L;
    }
}
