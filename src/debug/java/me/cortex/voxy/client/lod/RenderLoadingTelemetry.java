package me.cortex.voxy.client.lod;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.Arrays;

/** Process-wide bounded counters. They retain no renderer, session, model, or thread objects.
 * Software model baking runs on the model processor; only names and uploads run on the
 * render thread. These wall times overlap worker waits and must not be added to them. */
final class RenderLoadingTelemetry {
    static final int STAGES = 4, EVENTS = 13;
    static final String STAGE_ORDER = "NAME_RESOLVE,MODEL_BAKE,BIOME_BUILD,MODEL_UPLOAD";
    static final String EVENT_ORDER = "NAME_SERVICED,NAME_NEW,NAME_REUSED,NAME_BUDGET_EXIT,NAME_FAILURE,MODEL_BAKED,MODEL_ALIASED,MODEL_NEW,BIOME_BUILT,UPLOAD_MODEL,UPLOAD_BIOME,MODEL_REQUEST_QUEUED,BIOME_REQUEST_QUEUED";
    private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();
    private static final Counter[] COUNTERS = new Counter[STAGES];
    private static final ThreadLocal<Cursor[]> CURRENT = ThreadLocal.withInitial(() -> new Cursor[STAGES]);
    static { for (int stage = 0; stage < STAGES; stage++) COUNTERS[stage] = new Counter(); }

    private static final class Cursor {
        final int stage;
        final long thread = Thread.currentThread().threadId();
        final long[] events = new long[EVENTS];
        long started, cpuStarted;
        boolean active;
        Cursor(int stage) { this.stage = stage; }
    }

    private static final class Counter {
        long calls, wall, cpu, cpuCalls, maxWall, lastThread, liveStarted;
        int active;
        final long[] events = new long[EVENTS];
        synchronized void begin(Cursor cursor) {
            this.active++;
            this.lastThread = cursor.thread;
            this.liveStarted = cursor.started;
        }
        synchronized void end(Cursor cursor, long wall, long cpu) {
            this.calls++; this.wall += wall; this.maxWall = Math.max(this.maxWall, wall);
            if (cpu >= 0) { this.cpu += cpu; this.cpuCalls++; }
            for (int event = 0; event < EVENTS; event++) this.events[event] += cursor.events[event];
            this.active--;
        }
    }

    static Object begin(int stage) {
        if (stage < 0 || stage >= STAGES) return null;
        Cursor[] current = CURRENT.get();
        Cursor cursor = current[stage];
        if (cursor == null) current[stage] = cursor = new Cursor(stage);
        // Nested calls cannot overwrite an outer interval. No allocation on repeated calls.
        if (cursor.active) return null;
        Arrays.fill(cursor.events, 0);
        cursor.started = System.nanoTime(); cursor.cpuStarted = cpu(); cursor.active = true;
        COUNTERS[stage].begin(cursor);
        return cursor;
    }

    static Object current(int stage) {
        if (stage < 0 || stage >= STAGES) return null;
        Cursor cursor = CURRENT.get()[stage];
        return cursor != null && cursor.active ? cursor : null;
    }

    static void event(Object state, int event, long count) {
        if (state instanceof Cursor cursor && cursor.active && cursor.thread == Thread.currentThread().threadId()
                && event >= 0 && event < EVENTS && count > 0) cursor.events[event] += count;
    }

    static void queued(int stage) {
        if (stage != 1 && stage != 2) return;
        Counter counter = COUNTERS[stage];
        synchronized (counter) { counter.events[stage == 1 ? 11 : 12]++; }
    }

    static void end(Object state) {
        if (!(state instanceof Cursor cursor) || !cursor.active || cursor.thread != Thread.currentThread().threadId()) return;
        long cpuEnded = cpu(), ended = System.nanoTime();
        long cpu = cpuEnded >= 0 && cursor.cpuStarted >= 0 && cpuEnded >= cursor.cpuStarted
                ? cpuEnded - cursor.cpuStarted : -1;
        COUNTERS[cursor.stage].end(cursor, Math.max(0, ended - cursor.started), cpu);
        cursor.active = false;
    }

    private static long cpu() {
        try {
            if (THREADS.isCurrentThreadCpuTimeSupported() && THREADS.isThreadCpuTimeEnabled())
                return THREADS.getCurrentThreadCpuTime();
        } catch (UnsupportedOperationException | SecurityException ignored) { }
        return -1;
    }

    static String snapshot() {
        long now = System.nanoTime();
        long[] calls = new long[STAGES], wall = calls.clone(), cpu = calls.clone(), cpuCalls = calls.clone();
        long[] maxWall = calls.clone(), active = calls.clone(), liveWall = calls.clone(), threads = calls.clone();
        long[] threadCpu = calls.clone();
        long[] events = new long[EVENTS];
        for (int stage = 0; stage < STAGES; stage++) {
            Counter counter = COUNTERS[stage];
            synchronized (counter) {
                calls[stage] = counter.calls; wall[stage] = counter.wall; cpu[stage] = counter.cpu;
                cpuCalls[stage] = counter.cpuCalls; maxWall[stage] = counter.maxWall;
                active[stage] = counter.active; threads[stage] = counter.lastThread;
                liveWall[stage] = counter.active == 0 ? 0 : counter.active == 1
                        ? Math.max(0, now - counter.liveStarted) : -1;
                for (int event = 0; event < EVENTS; event++) events[event] += counter.events[event];
            }
            threadCpu[stage] = -1;
            try {
                if (threads[stage] != 0 && THREADS.isThreadCpuTimeSupported() && THREADS.isThreadCpuTimeEnabled())
                    threadCpu[stage] = THREADS.getThreadCpuTime(threads[stage]);
            } catch (UnsupportedOperationException | SecurityException ignored) { }
        }
        return " renderLoadingScope=PROCESS renderLoadingStageOrder=" + STAGE_ORDER
                + " renderLoadingCalls=" + Arrays.toString(calls)
                + " renderLoadingWallNs=" + Arrays.toString(wall)
                + " renderLoadingCpuNs=" + Arrays.toString(cpu)
                + " renderLoadingCpuCalls=" + Arrays.toString(cpuCalls)
                + " renderLoadingMaxWallNs=" + Arrays.toString(maxWall)
                + " renderLoadingActive=" + Arrays.toString(active)
                + " renderLoadingLiveWallNs=" + Arrays.toString(liveWall)
                + " renderLoadingLastThreads=" + Arrays.toString(threads)
                + " renderLoadingThreadCpuNs=" + Arrays.toString(threadCpu)
                + " renderLoadingEventOrder=" + EVENT_ORDER
                + " renderLoadingEventCounts=" + Arrays.toString(events);
    }
}
