package me.cortex.voxy.client.lod;

import me.cortex.voxy.debugtest.DebugTestProtocol;
import java.lang.reflect.Field;
import java.util.UUID;

/** Real input scope and harness terminal cleanup; FOV samples stand in for rendered frames. */
public final class DebugZoomBehaviorTest {
    public static void run() {
        rejected(DebugZoomControl::begin, "missing zoom integration accepted");
        check(!DebugZoomControl.zoomSignal(false) && DebugZoomControl.zoomSignal(true), "normal zoom input altered");
        DebugZoomControl.zoomSignal(false);
        DebugZoomControl.recordWorldFov(70);
        var first = DebugZoomControl.begin();
        rejected(DebugZoomControl::begin, "another run stole zoom control");
        first.select(true);
        check(!first.observed(), "stale observation acknowledged zoom");
        check(DebugZoomControl.zoomSignal(false), "zoom-in did not reach mod signal");
        DebugZoomControl.recordWorldFov(70);
        check(!first.observed(), "unchanged camera acknowledged zoom-in");
        DebugZoomControl.recordWorldFov(17.5);
        check(first.observed(), "rendered zoom not acknowledged");
        first.select(false);
        check(!DebugZoomControl.zoomSignal(true), "zoom-out did not override signal");
        DebugZoomControl.recordWorldFov(70);
        check(first.observed(), "zoom-out observation lost");
        first.close(); first.close();
        check(DebugZoomControl.zoomSignal(true), "cleanup did not restore normal input");
        var next = DebugZoomControl.begin(); next.select(false); first.close();
        check(!DebugZoomControl.zoomSignal(true), "old cleanup erased successor override");
        rejected(() -> first.select(true), "expired controller mutated successor");
        try {
            int[] restored = {0};
            Field scrollRestore = DebugZoomControl.class.getDeclaredField("restoreScroll");
            scrollRestore.setAccessible(true);
            scrollRestore.set(next, (Runnable) () -> restored[0]++);
            var run = new LiveClientTestHarness.Run(UUID.randomUUID(), 1, null);
            run.zoomControl = next; run.zoomPending = true;
            Field field = LiveClientTestHarness.class.getDeclaredField("run"); field.setAccessible(true); field.set(null, run);
            var restore = LiveClientTestHarness.class.getDeclaredMethod("restoreSettings", LiveClientTestHarness.Run.class);
            restore.setAccessible(true);
            LiveClientTestHarness.failRun(DebugTestProtocol.Failure.ZOOM_TIMEOUT,
                    (ignored, reason) -> { throw new IllegalStateException("injected disconnected channel"); },
                    old -> {
                        try { restore.invoke(null, old); }
                        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
                    });
            check(run.zoomControl == null && !run.zoomPending && DebugZoomControl.zoomSignal(true),
                    "terminal delivery failure left zoom held");
            next.close();
            check(restored[0] == 1, "maximum scroll state was not restored exactly once");
            // The same cleanup used by normal END_RUN must also be idempotent.
            var ending = new LiveClientTestHarness.Run(UUID.randomUUID(), 2, null);
            ending.zoomControl = DebugZoomControl.begin(); ending.zoomControl.select(false);
            restore.invoke(null, ending); restore.invoke(null, ending);
            check(DebugZoomControl.zoomSignal(true), "normal/repeated cleanup left zoom overridden");
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        DebugZoomControl.recordWorldFov(Double.NaN);
        check(DebugZoomControl.worldFov() == 70, "invalid projection polluted telemetry");
        System.out.println("debug zoom signal, rendered acknowledgement, scope and terminal cleanup tests passed");
    }

    private static void rejected(Runnable task, String message) {
        try { task.run(); } catch (IllegalStateException expected) { return; }
        throw new AssertionError(message);
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
