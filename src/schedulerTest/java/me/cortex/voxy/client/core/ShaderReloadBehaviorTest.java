package me.cortex.voxy.client.core;

import me.cortex.voxy.client.core.model.MaterialCompatibility;
import me.cortex.voxy.common.util.MemoryBuffer;
import org.lwjgl.system.MemoryUtil;
import java.util.ArrayList;
import java.util.List;

/** Production coordinator and resource scopes, with only the GL/lifecycle boundary doubled. */
public final class ShaderReloadBehaviorTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static final class Resource {
        int frees;
        void free() { check(++frees == 1, "shader object freed twice"); }
    }

    private static final class Group implements AutoCloseable {
        final ShaderResourceScope scope = new ShaderResourceScope();
        final Owner owner;
        final Object pipeline = new Object();
        final boolean taa;
        Group(Owner owner) {
            this.owner = owner; this.taa = owner.taa;
            try {
                // Pipeline, opaque/translucent, traversal, bounds and framebuffer stages.
                for (int stage = 0; stage < 6; stage++) {
                    Resource resource = new Resource();
                    owner.allocations.add(resource);
                    this.scope.own(resource, Resource::free);
                    if (owner.failStage == stage) throw new IllegalStateException("create " + stage);
                }
            } catch (RuntimeException failure) {
                this.scope.cleanupAfter(failure);
                throw failure;
            }
        }
        @Override public void close() {
            if (this.owner.sampler == this.pipeline) this.owner.sampler = null;
            if (this.owner.taaPipeline == this.pipeline) this.owner.taaPipeline = null;
            this.scope.close();
        }
    }

    private static final class Owner implements ShaderReloadCoordinator.Owner<Group> {
        final Object renderer = new Object(), session = new Object(), publisher = new Object();
        final Object geometry = new Object(), rootWindow = new Object(), installedHandle = new Object();
        final List<Resource> allocations = new ArrayList<>();
        final List<Integer> feedback = new ArrayList<>(List.of(7, 8, 9));
        final List<Integer> delivered = new ArrayList<>();
        final ShaderReloadCoordinator<Group> coordinator = new ShaderReloadCoordinator<>(this);
        Object sampler, taaPipeline;
        boolean current = true, taa, destroyDuringPrepare, incompatible;
        int failStage = -1, suspends, commits, histories, failures, rebuilds;
        int frameSequence = 419, selectionEpoch = 823, dormantBits = 0x80000007;
        @Override public boolean current() { return this.current; }
        @Override public void suspend() {
            this.suspends++;
            this.delivered.addAll(this.feedback);
            this.feedback.clear();
        }
        @Override public Group prepare() {
            if (this.incompatible) throw new ShaderReloadCoordinator.Incompatible("shared material split");
            Group group = new Group(this);
            if (this.destroyDuringPrepare) { this.current = false; this.coordinator.close(); }
            return group;
        }
        @Override public void commit(Group group) {
            this.sampler = group.pipeline;
            this.taaPipeline = group.taa ? group.pipeline : null;
            if (this.failStage == 6) throw new IllegalStateException("bind");
            this.histories++;
            this.commits++;
        }
        @Override public void failed(Throwable failure) { this.failures++; }
        @Override public void incompatible(String reason) { this.rebuilds++; }
        void reload() { this.coordinator.begin("test").finish(null); }
        void noLeaks() {
            this.coordinator.close();
            for (Resource resource : this.allocations) check(resource.frees == 1, "partial/group resource leaked");
        }
    }

    public static void run() {
        compatibleNestedAndRepeated();
        failuresAndRecovery();
        staleWorldCallbacks();
        sharedMaterialsAndLateUpload();
        cleanupContinuesAfterFailure();
        System.out.println("shader reload ownership and material behavior tests passed");
    }

    private static void compatibleNestedAndRepeated() {
        Owner owner = new Owner();
        Object[] terrain = {owner.renderer, owner.session, owner.publisher, owner.geometry,
                owner.rootWindow, owner.installedHandle};
        owner.reload();
        Object oldSampler = owner.sampler;
        var outer = owner.coordinator.begin("Iris.reload");
        var nested = owner.coordinator.begin("nested callback");
        check(owner.sampler == null && !owner.coordinator.drawable(), "old Iris targets still drawable");
        nested.finish(null);
        check(owner.commits == 1 && owner.coordinator.nestedReload(), "nested callback committed early");
        owner.taa = true;
        outer.finish(null);
        outer.finish(null);
        check(owner.commits == 2 && owner.sampler != oldSampler, "reload did not replace bindings exactly once");
        check(owner.taaPipeline == owner.sampler, "TAA off-to-on used stale pipeline");
        owner.taa = false;
        owner.reload();
        check(owner.taaPipeline == null, "TAA on-to-off retained old reference");
        for (int cycle = 0; cycle < 64; cycle++) owner.reload();
        check(owner.histories == owner.commits && owner.frameSequence == 419
                && owner.selectionEpoch == 823 && owner.dormantBits == 0x80000007,
                "shader history reset destroyed persistent selection state");
        check(owner.delivered.equals(List.of(7, 8, 9)), "committed feedback lost or reordered");
        check(terrain[0] == owner.renderer && terrain[1] == owner.session && terrain[2] == owner.publisher
                && terrain[3] == owner.geometry && terrain[4] == owner.rootWindow
                && terrain[5] == owner.installedHandle && owner.rebuilds == 0, "compatible reload replaced terrain");
        check(owner.coordinator.lastPauseNanos() > 0, "reload pause not measured");
        owner.noLeaks();
    }

    private static void failuresAndRecovery() {
        for (int stage = 0; stage <= 6; stage++) {
            Owner owner = new Owner();
            owner.reload();
            owner.failStage = stage;
            owner.reload();
            check(owner.coordinator.status() == ShaderReloadCoordinator.Status.FAILED
                    && owner.sampler == null && owner.failures == 1 && owner.rebuilds == 0,
                    "failed shader creation/binding did not pause cleanly");
            for (Resource resource : owner.allocations) check(resource.frees == 1, "failure leaked resource");
            owner.failStage = -1;
            owner.reload();
            check(owner.coordinator.drawable(), "successful retry did not recover");
            owner.noLeaks();
        }
        Owner owner = new Owner();
        owner.reload();
        owner.coordinator.begin("external failure").finish(new IllegalStateException("Iris failed"));
        check(owner.coordinator.status() == ShaderReloadCoordinator.Status.FAILED, "Iris failure ignored");
        owner.incompatible = true;
        owner.reload();
        check(owner.rebuilds == 1 && owner.coordinator.reason().contains("split"), "incompatible mapping not explicit");
        owner.noLeaks();
    }

    private static void staleWorldCallbacks() {
        Owner old = new Owner();
        old.reload();
        var late = old.coordinator.begin("world replaced");
        old.current = false;
        old.coordinator.close();
        Owner replacement = new Owner();
        replacement.reload();
        Object binding = replacement.sampler;
        late.finish(null);
        check(old.commits == 1 && replacement.sampler == binding, "late callback attached to new renderer");
        old.noLeaks(); replacement.noLeaks();

        Owner during = new Owner();
        during.destroyDuringPrepare = true;
        during.reload();
        check(during.commits == 0 && during.coordinator.status() == ShaderReloadCoordinator.Status.CLOSED,
                "prepared resources attached after logout");
        during.noLeaks();
    }

    private static void sharedMaterialsAndLateUpload() {
        int[] aliases = {0, 0, 1, -1};
        int[] values = {5, 5, 9, 99};
        int[] materials = MaterialCompatibility.resolve(4, 2, i -> aliases[i], i -> values[i]);
        check(materials[0] == 5 && materials[1] == 9, "compatible alias materials wrong");
        MemoryBuffer pending = new MemoryBuffer(64).zero();
        try {
            MemoryUtil.memPutInt(pending.address + 32, 1); // Captured by the old asynchronous bake.
            MaterialCompatibility.patchUpload(pending.address, materials[0]);
            check(MemoryUtil.memGetInt(pending.address + 32) == 5, "late upload restored stale material");
        } finally { pending.free(); }
        values[1] = 6;
        boolean rejected = false;
        try { MaterialCompatibility.resolve(4, 2, i -> aliases[i], i -> values[i]); }
        catch (ShaderReloadCoordinator.Incompatible expected) { rejected = true; }
        check(rejected, "shared model split accepted as compatible");
    }

    private static void cleanupContinuesAfterFailure() {
        ShaderResourceScope scope = new ShaderResourceScope();
        Resource survivor = new Resource();
        scope.own(survivor, Resource::free);
        scope.own(new Object(), ignored -> { throw new IllegalStateException("injected delete failure"); });
        Throwable construction = new IllegalStateException("partial construction");
        scope.cleanupAfter(construction);
        scope.close();
        check(survivor.frees == 1 && construction.getSuppressed().length == 1,
                "cleanup failure stopped remaining resource cleanup");
    }
}
