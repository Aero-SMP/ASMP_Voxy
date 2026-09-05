package me.cortex.voxy.client.lod;

import java.lang.reflect.Field;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** Actual terminal method, with packet transport and shader restoration as controlled boundaries. */
final class HarnessTerminalBehaviorTest {
    static void run() throws Exception {
        Class<?> type = Class.forName("me.cortex.voxy.client.lod.LiveClientTestHarness");
        Class<?> failureType = Class.forName("me.cortex.voxy.debugtest.DebugTestProtocol$Failure");
        Object replaced = failureType.getField("RENDERER_REPLACED").get(null);
        Object aborted = failureType.getField("ABORTED").get(null);
        Object disconnected = failureType.getField("DISCONNECTED").get(null);
        Class<?> runType = Class.forName(type.getName() + "$Run");
        var ctor = runType.getDeclaredConstructors()[0]; ctor.setAccessible(true);
        var fail = type.getDeclaredMethod("failRun", failureType,
                java.util.function.BiConsumer.class, java.util.function.Consumer.class);
        fail.setAccessible(true);
        Field run = field(type, "run"), pending = field(type, "snapshotPending"), token = field(type, "snapshotToken");
        AtomicInteger sent = new AtomicInteger(), restored = new AtomicInteger();
        Object first = ctor.newInstance(UUID.randomUUID(), 7L, null);
        run.set(null, first); pending.setBoolean(null, true); token.setLong(null, 100L);
        var dying = new ClientSession.Session(991, "test", null, new CacheStartupBehaviorTest.Publisher(), null, 0);
        Field activeSession = field(ClientSession.class, "active");
        Object previousSession = activeSession.get(null);
        AtomicInteger callbacks = new AtomicInteger();
        try {
            activeSession.set(null, dying);
            check(ClientSession.requestDebugSnapshot(ignored -> callbacks.incrementAndGet()), "observation not accepted");
            dying.open.set(false);
            dying.release();
            check(callbacks.get() == 0, "test did not discard the dying session observation");
        } finally { activeSession.set(null, previousSession); }
        java.util.function.BiConsumer<Object, Object> deliver = (identity, reason) -> {
            check(identity == first && reason == replaced, "terminal identity changed");
            sent.incrementAndGet();
        };
        java.util.function.Consumer<Object> restore = identity -> {
            restored.incrementAndGet();
            try { fail.invoke(null, replaced, deliver, (java.util.function.Consumer<Object>) ignored -> {
                throw new AssertionError("restoration caused a second terminal");
            }); } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
        };
        fail.invoke(null, replaced, deliver, restore);
        check(sent.get() == 1 && restored.get() == 1 && run.get(null) == null
                && !pending.getBoolean(null) && token.getLong(null) != 100, "discarded observation gated terminal");
        // Exactly the token predicate used by late callbacks: the previous owner cannot deliver.
        check(token.getLong(null) != 100, "late callback still current");
        Object next = ctor.newInstance(UUID.randomUUID(), 0L, null); run.set(null, next);
        pending.setBoolean(null, true);
        fail.invoke(null, aborted, (java.util.function.BiConsumer<Object, Object>) (identity, reason) -> {
            check(identity == next && reason == aborted, "successor aborted with old identity");
            sent.incrementAndGet();
        }, (java.util.function.Consumer<Object>) identity -> { restored.incrementAndGet(); throw new IllegalStateException("injected restoration failure"); });
        check(sent.get() == 2 && restored.get() == 2 && !pending.getBoolean(null), "restoration suppressed terminal");
        run.set(null, ctor.newInstance(UUID.randomUUID(), 3L, null));
        fail.invoke(null, disconnected, (java.util.function.BiConsumer<Object, Object>) (identity, reason) -> {
            throw new IllegalStateException("injected unavailable Minecraft channel");
        }, (java.util.function.Consumer<Object>) identity -> restored.incrementAndGet());
        check(restored.get() == 3 && run.get(null) == null && !pending.getBoolean(null), "disconnect leaked run");
        System.out.println("direct harness terminal, late-token, reentrant restore and channel-failure tests passed");
    }
    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name); field.setAccessible(true); return field;
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
