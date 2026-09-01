package me.cortex.voxy.client.core.rendering.building;

import me.cortex.voxy.client.lod.CompiledGeometryCache;
import me.cortex.voxy.client.lod.ContentPipeline.ActivationGroup;
import me.cortex.voxy.client.lod.ContentPipeline.PreparedMicrotile;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Production dispatch for GPU-safe opaque/templates plus CPU complex geometry. */
public final class HybridMeshingDispatcher implements AutoCloseable {
    @FunctionalInterface
    public interface Backend {
        /** Returns caller-owned geometry for exactly the supplied typed microtiles. */
        BuiltSection mesh(FragmentRequest request) throws Exception;

        default void close() {}
    }

    @FunctionalInterface
    public interface GeometryMerger {
        /** Returns new caller-owned geometry without taking ownership of either input. */
        BuiltSection merge(long sectionPosition, long sourceRevision,
                           BuiltSection gpuGeometry, BuiltSection cpuGeometry) throws Exception;

        default void close() {}
    }

    public record FragmentRequest(long sectionPosition, long sourceRevision,
                                  ActivationGroup activation,
                                  List<PreparedMicrotile> microtiles) {
        public FragmentRequest {
            Objects.requireNonNull(activation, "activation");
            microtiles = List.copyOf(Objects.requireNonNull(microtiles, "microtiles"));
            if (microtiles.isEmpty()) throw new IllegalArgumentException("empty meshing fragment");
        }
    }

    private final Backend gpuOpaqueTemplates;
    private final Backend cpuComplex;
    private final GeometryMerger merger;
    private final CompiledGeometryCache cache;
    private final ExecutorService overlapExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private boolean closed;

    public HybridMeshingDispatcher(Backend gpuOpaqueTemplates, Backend cpuComplex,
                                     GeometryMerger merger,
                                     CompiledGeometryCache cache) {
        this.gpuOpaqueTemplates = Objects.requireNonNull(
                gpuOpaqueTemplates, "gpuOpaqueTemplates");
        this.cpuComplex = Objects.requireNonNull(cpuComplex, "cpuComplex");
        this.merger = Objects.requireNonNull(merger, "merger");
        this.cache = Objects.requireNonNull(cache, "cache");
    }

    public BuiltSection mesh(long sectionPosition, long sourceRevision,
                             ActivationGroup activation)
            throws Exception {
        if (this.closed) throw new IllegalStateException("hybrid meshing dispatcher is closed");
        Objects.requireNonNull(activation, "activation");
        CompiledGeometryCache.Key key = CompiledGeometryCache.Key.create(
                activation.terrainIdentity(), activation.rendererIdentity());
        Optional<BuiltSection> cached = this.cache.lookup(key, sectionPosition, sourceRevision,
                (byte) activation.childMask());
        if (cached.isPresent()) return cached.orElseThrow();

        List<PreparedMicrotile> gpu = activation.gpuMicrotiles();
        List<PreparedMicrotile> cpu = activation.cpuMicrotiles();
        BuiltSection gpuGeometry = null;
        BuiltSection cpuGeometry = null;
        BuiltSection result = null;
        try {
            FragmentRequest gpuRequest = gpu.isEmpty() ? null
                    : new FragmentRequest(sectionPosition, sourceRevision, activation, gpu);
            FragmentRequest cpuRequest = cpu.isEmpty() ? null
                    : new FragmentRequest(sectionPosition, sourceRevision, activation, cpu);
            if (gpuRequest != null && cpuRequest != null) {
                // GPU dispatch/readback and complex CPU geometry are independent fragments of
                // the same immutable activation. Run them concurrently, then merge only after
                // both complete; neither backend can expose partial renderer state.
                CompletableFuture<BuiltSection> gpuFuture = CompletableFuture.supplyAsync(
                        () -> meshUnchecked(this.gpuOpaqueTemplates, gpuRequest),
                        this.overlapExecutor);
                Throwable cpuFailure = null;
                try {
                    cpuGeometry = requireGeometry(this.cpuComplex.mesh(cpuRequest),
                            "CPU mesher result");
                } catch (Throwable failure) {
                    cpuFailure = failure;
                }
                try {
                    gpuGeometry = requireGeometry(gpuFuture.get(), "GPU mesher result");
                } catch (InterruptedException interrupted) {
                    gpuFuture.cancel(true);
                    if (cpuFailure != null) interrupted.addSuppressed(cpuFailure);
                    Thread.currentThread().interrupt();
                    throw interrupted;
                } catch (ExecutionException wrapped) {
                    Throwable gpuFailure = unwrapCompletion(wrapped.getCause());
                    if (cpuFailure != null) gpuFailure.addSuppressed(cpuFailure);
                    throw rethrow(gpuFailure);
                }
                if (cpuFailure != null) throw rethrow(cpuFailure);
            } else if (gpuRequest != null) {
                gpuGeometry = requireGeometry(this.gpuOpaqueTemplates.mesh(gpuRequest),
                        "GPU mesher result");
            } else if (cpuRequest != null) {
                cpuGeometry = requireGeometry(this.cpuComplex.mesh(cpuRequest),
                        "CPU mesher result");
            }
            if (gpuGeometry != null && cpuGeometry != null) {
                result = Objects.requireNonNull(this.merger.merge(sectionPosition, sourceRevision,
                        gpuGeometry, cpuGeometry), "hybrid geometry merge result");
                if (result == gpuGeometry || result == cpuGeometry) {
                    throw new IllegalStateException("geometry merger must return independent output");
                }
            } else {
                result = gpuGeometry != null ? gpuGeometry : cpuGeometry;
                if (result == null) throw new IllegalStateException("activation produced no geometry");
                if (result == gpuGeometry) gpuGeometry = null;
                else cpuGeometry = null;
            }
            this.cache.put(key, result);
            return result;
        } catch (Exception | Error failure) {
            if (result != null && result != gpuGeometry && result != cpuGeometry) result.free();
            throw failure;
        } finally {
            if (gpuGeometry != null) gpuGeometry.free();
            if (cpuGeometry != null) cpuGeometry.free();
        }
    }

    @Override
    public void close() {
        if (this.closed) return;
        this.closed = true;
        RuntimeException failure = null;
        try {
            this.gpuOpaqueTemplates.close();
        } catch (RuntimeException exception) {
            failure = exception;
        }
        if (this.cpuComplex != this.gpuOpaqueTemplates) {
            try {
                this.cpuComplex.close();
            } catch (RuntimeException exception) {
                if (failure == null) failure = exception;
                else failure.addSuppressed(exception);
            }
        }
        try {
            this.merger.close();
        } catch (RuntimeException exception) {
            if (failure == null) failure = exception;
            else failure.addSuppressed(exception);
        }
        try {
            this.cache.close();
        } catch (RuntimeException exception) {
            if (failure == null) failure = exception;
            else failure.addSuppressed(exception);
        }
        this.overlapExecutor.shutdownNow();
        if (failure != null) throw failure;
    }

    private static BuiltSection meshUnchecked(Backend backend, FragmentRequest request) {
        try {
            return requireGeometry(backend.mesh(request), "mesher result");
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Exception failure) {
            throw new CompletionException(failure);
        }
    }

    private static BuiltSection requireGeometry(BuiltSection geometry, String name) {
        return Objects.requireNonNull(geometry, name);
    }

    private static Exception rethrow(Throwable failure) throws Exception {
        if (failure instanceof Exception exception) return exception;
        if (failure instanceof Error error) throw error;
        return new IllegalStateException("hybrid meshing failed", failure);
    }

    private static Throwable unwrapCompletion(Throwable failure) {
        while (failure instanceof CompletionException completion
                && completion.getCause() != null) {
            failure = completion.getCause();
        }
        return failure;
    }
}
