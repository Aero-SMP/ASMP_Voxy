package me.cortex.voxy.client.lod;

import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Offline accounting check: overlapping producers, scoped ownership and bounded events. */
public final class RenderLoadingTelemetryBehaviorTest {
    private static long[] array(String text, String key) {
        int at = text.indexOf(key + "=[");
        if (at < 0) throw new AssertionError(key + " missing");
        int start = at + key.length() + 2, end = text.indexOf(']', start);
        return Arrays.stream(text.substring(start, end).split(", ")).mapToLong(Long::parseLong).toArray();
    }
    private static void equal(long actual, long expected, String label) {
        if (actual != expected) throw new AssertionError(label + ": " + actual + " != " + expected);
    }
    public static void main(String[] args) throws Exception {
        Object names = RenderLoadingTelemetry.begin(0);
        if (RenderLoadingTelemetry.current(0) != names || RenderLoadingTelemetry.begin(0) != null)
            throw new AssertionError("same-thread nested begin overwrote outer scope");
        RenderLoadingTelemetry.end(null);
        equal(array(RenderLoadingTelemetry.snapshot(), "renderLoadingActive")[0], 1, "nested null end kept outer");

        Thread foreign = Thread.ofPlatform().start(() -> {
            RenderLoadingTelemetry.event(names, 0, 99);
            RenderLoadingTelemetry.end(names);
        });
        foreign.join(5_000);
        if (foreign.isAlive()) throw new AssertionError("foreign-thread check did not end");
        equal(array(RenderLoadingTelemetry.snapshot(), "renderLoadingActive")[0], 1, "foreign end kept outer");
        RenderLoadingTelemetry.event(names, 0, 3);
        RenderLoadingTelemetry.event(names, 1, 1);
        RenderLoadingTelemetry.event(names, 2, 2);
        RenderLoadingTelemetry.event(names, -1, 8);
        RenderLoadingTelemetry.event(names, RenderLoadingTelemetry.EVENTS, 8);
        RenderLoadingTelemetry.event(names, 0, -2);
        RenderLoadingTelemetry.end(names);
        RenderLoadingTelemetry.end(names);
        if (RenderLoadingTelemetry.current(0) != null) throw new AssertionError("ended scope still active");

        CountDownLatch started = new CountDownLatch(2), finish = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Runnable bake = () -> {
            RenderLoadingTelemetry.queued(1);
            Object work = RenderLoadingTelemetry.begin(1);
            try {
                RenderLoadingTelemetry.event(work, 5, 1); started.countDown();
                if (!finish.await(5, TimeUnit.SECONDS)) throw new AssertionError("overlap release timed out");
            } catch (Throwable problem) { failure.compareAndSet(null, problem); }
            finally { RenderLoadingTelemetry.end(work); }
        };
        Thread first = Thread.ofPlatform().start(bake), second = Thread.ofPlatform().start(bake);
        try {
            if (!started.await(5, TimeUnit.SECONDS)) throw new AssertionError("overlap producers did not start");
            String live = RenderLoadingTelemetry.snapshot();
            equal(array(live, "renderLoadingActive")[1], 2, "two concurrent scopes");
            equal(array(live, "renderLoadingLiveWallNs")[1], -1, "ambiguous overlapping live age");
            equal(array(live, "renderLoadingEventCounts")[11], 2, "queued requests visible while running");
        } finally { finish.countDown(); first.join(5_000); second.join(5_000); }
        if (first.isAlive() || second.isAlive()) throw new AssertionError("overlap producers did not end");
        if (failure.get() != null) throw new AssertionError(failure.get());

        Object upload = RenderLoadingTelemetry.begin(3); RenderLoadingTelemetry.end(upload);
        RenderLoadingTelemetry.queued(2); RenderLoadingTelemetry.queued(2);
        RenderLoadingTelemetry.queued(0); RenderLoadingTelemetry.queued(-1); RenderLoadingTelemetry.queued(4);
        if (RenderLoadingTelemetry.begin(-1) != null || RenderLoadingTelemetry.begin(4) != null
                || RenderLoadingTelemetry.current(-1) != null || RenderLoadingTelemetry.current(4) != null)
            throw new AssertionError("invalid stage accepted");
        String result = RenderLoadingTelemetry.snapshot();
        long[] calls = array(result, "renderLoadingCalls"), events = array(result, "renderLoadingEventCounts");
        equal(calls[0], 1, "name calls"); equal(calls[1], 2, "bake calls"); equal(calls[2], 0, "biome calls");
        equal(calls[3], 1, "empty upload calls");
        equal(events[0], 3, "names serviced"); equal(events[1], 1, "new mappings");
        equal(events[2], 2, "reused mappings"); equal(events[5], 2, "bakes");
        equal(events[11], 2, "queued models"); equal(events[12], 2, "queued biomes");
        for (long active : array(result, "renderLoadingActive")) equal(active, 0, "all scopes ended");
        long[] cpuCalls = array(result, "renderLoadingCpuCalls"), cpu = array(result, "renderLoadingCpuNs");
        long[] wall = array(result, "renderLoadingWallNs"), max = array(result, "renderLoadingMaxWallNs");
        for (int stage = 0; stage < calls.length; stage++) {
            if (cpuCalls[stage] < 0 || cpuCalls[stage] > calls[stage] || cpu[stage] < 0
                    || wall[stage] < max[stage] || max[stage] < 0)
                throw new AssertionError("invalid CPU availability or elapsed accounting");
        }
        System.out.println("RenderLoadingTelemetryBehaviorTest PASS: " + result);
    }
}
