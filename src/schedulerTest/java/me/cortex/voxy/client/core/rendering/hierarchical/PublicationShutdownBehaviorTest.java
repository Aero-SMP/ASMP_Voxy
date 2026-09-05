package me.cortex.voxy.client.core.rendering.hierarchical;

import me.cortex.voxy.client.core.VoxyRenderSystem.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Exercises the production publication state used by renderer teardown, without an OpenGL context. */
public final class PublicationShutdownBehaviorTest {
    public static void run() {
        concurrentRetirementAndRendererInspection();
        admissionInterleavings();
        for (boolean closeBeforeUpload : new boolean[]{false, true}) {
            var state = new State();
            if (closeBeforeUpload) state.close();
            state.completeUpload(new UploadOutcome(UploadStatus.ACTIVATED, null, null));
            state.close(); state.close();
            check(state.requests == 1 && !state.retirementFencePassed(), "live retirement bypassed its fence");
            state.markRetired(); state.close();
            check(state.requests == 1 && state.retirementFencePassed(), "retirement repeated");
        }
        for (boolean active : new boolean[]{false, true}) {
            for (int i = 0; i < 6886; i++) {
                var state = new State();
                if (active) state.completeUpload(new UploadOutcome(UploadStatus.ACTIVATED, null, null));
                state.rendererStopped(); state.close(); state.rendererStopped();
                check(state.requests == 0 && state.retirementFencePassed() && !state.acceptsUpload(),
                        "destroyed renderer scheduled a per-surface rollback/retirement");
                var result = state.takeUploadOutcome().orElseThrow();
                check(result.status() == (active ? UploadStatus.ACTIVATED : UploadStatus.CANCELLED), "terminal result overwritten");
                check(state.takeUploadOutcome().isEmpty(), "terminal ownership delivered twice");
            }
        }
        for (boolean abandonedFirst : new boolean[]{false, true}) {
            var disposed = new AtomicInteger(); var released = new AtomicInteger();
            @SuppressWarnings("unchecked") PublicationOutcome<Integer>[] box = new PublicationOutcome[1];
            box[0] = new PublicationOutcome<>(value -> {
                check(!Thread.holdsLock(box[0]), "native disposal ran inside outcome monitor");
                disposed.incrementAndGet();
            });
            Runnable resolved = () -> {
                check(!Thread.holdsLock(box[0]), "worker release ran inside outcome monitor");
                released.incrementAndGet();
            };
            if (abandonedFirst) box[0].abandon(resolved);
            box[0].complete(1); box[0].abandon(resolved); box[0].abandon(resolved);
            check(disposed.get() == 1 && released.get() == 1 && box[0].claim() == null,
                    "abandoned upload released ownership more than once");
        }
        System.out.println("renderer shutdown and publication callback lock-order tests passed");
    }

    private static void admissionInterleavings() {
        for (boolean admittedFirst : new boolean[]{false, true}) {
            State state = new State();
            if (admittedFirst) state.markRendererAdmitted();
            state.rendererStopped();
            state.markRendererAdmitted(); state.markRendererAdmitted();
            check(state.rendererAdmitted() && !state.activationFencePassed()
                    && state.retirementFencePassed(), "shutdown erased admission or activated geometry");
            check(state.takeUploadOutcome().orElseThrow().status() == UploadStatus.CANCELLED,
                    "late admission changed terminal outcome");
        }
        for (boolean admissionFirst : new boolean[]{false, true}) {
            State state = new State();
            if (admissionFirst) state.markRendererAdmitted();
            else state.completeUpload(new UploadOutcome(UploadStatus.RETURNED, null, null));
            boolean rejected = false;
            try {
                if (admissionFirst) state.completeUpload(new UploadOutcome(UploadStatus.RETURNED, null, null));
                else state.markRendererAdmitted();
            } catch (IllegalStateException expected) { rejected = true; }
            check(rejected, "RETURNED and admitted were allowed on the same attempt");
        }
        for (int iteration = 0; iteration < 128; iteration++) {
            State state = new State();
            AtomicReference<Throwable> error = new AtomicReference<>();
            CountDownLatch start = new CountDownLatch(1);
            Thread admitting = Thread.ofPlatform().daemon().unstarted(() -> {
                try { start.await(); state.markRendererAdmitted(); state.markRendererAdmitted(); }
                catch (Throwable failure) { error.set(failure); }
            });
            Thread stopping = Thread.ofPlatform().daemon().unstarted(() -> {
                try { start.await(); state.rendererStopped(); state.close(); }
                catch (Throwable failure) { error.set(failure); }
            });
            admitting.start(); stopping.start(); start.countDown();
            try { admitting.join(5_000); stopping.join(5_000); }
            catch (InterruptedException failure) { throw new AssertionError(failure); }
            check(!admitting.isAlive() && !stopping.isAlive(), "admission/shutdown deadlocked");
            if (error.get() != null) throw new AssertionError(error.get());
            check(state.rendererAdmitted() && state.retirementFencePassed() && state.requests == 0,
                    "racing admission/shutdown lost ownership");
        }
    }

    private static void concurrentRetirementAndRendererInspection() {
        var submissionLock = new Object();
        var rendererEntered = new CountDownLatch(1);
        var retirementEntered = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        var state = new SectionPublicationState() {
            @Override protected void requestRetirement() {
                retirementEntered.countDown();
                synchronized (submissionLock) { markRetired(); }
            }
            @Override protected void stateChanged() {}
        };
        state.completeUpload(new UploadOutcome(UploadStatus.ACTIVATED, null, null));
        Thread renderer = Thread.ofPlatform().daemon().unstarted(() -> {
            try {
                synchronized (submissionLock) {
                    rendererEntered.countDown();
                    check(retirementEntered.await(5, TimeUnit.SECONDS), "retirement never started");
                    check(state.activationFencePassed(), "activated publication changed");
                }
            } catch (Throwable error) { failure.set(error); }
        });
        Thread owner = Thread.ofPlatform().daemon().unstarted(() -> {
            try {
                check(rendererEntered.await(5, TimeUnit.SECONDS), "renderer never started");
                state.close();
            } catch (Throwable error) { failure.set(error); }
        });
        renderer.start(); owner.start();
        try { renderer.join(6_000); owner.join(6_000); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
        check(!renderer.isAlive() && !owner.isAlive(), "publication/renderer monitor deadlock");
        if (failure.get() != null) throw new AssertionError(failure.get());
        check(state.retirementFencePassed(), "retirement lost after concurrent renderer inspection");
    }

    private static final class State extends SectionPublicationState {
        int requests;
        @Override protected void requestRetirement() {
            check(!Thread.holdsLock(this), "publication-to-renderer lock inversion");
            requests++;
        }
        @Override protected void stateChanged() {
            check(!Thread.holdsLock(this), "publication callback retained monitor");
        }
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
