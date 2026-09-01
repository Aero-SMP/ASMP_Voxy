package me.cortex.voxy.client.lod;

import me.cortex.voxy.client.lod.WireMessage.EncodedObject;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.Set;

import static org.lwjgl.util.zstd.Zstd.ZSTD_createDCtx;
import static org.lwjgl.util.zstd.Zstd.ZSTD_decompressDCtx;
import static org.lwjgl.util.zstd.Zstd.ZSTD_decompress_usingDict;
import static org.lwjgl.util.zstd.Zstd.ZSTD_freeDCtx;
import static org.lwjgl.util.zstd.Zstd.ZSTD_getErrorName;
import static org.lwjgl.util.zstd.Zstd.ZSTD_isError;

/** Bounded off-thread decode, authentication, durable-cache, and residency admission pipeline. */
public final class ObjectDecoder implements AutoCloseable {

    @FunctionalInterface
    public interface DictionaryResolver {
        DictionaryResolver NONE = ignored -> Optional.empty();

        /** Resolves the unsigned dictionary ID from the active announced dictionary set. */
        Optional<DictionaryCodec.Dictionary> resolve(int dictionaryId);
    }

    @FunctionalInterface
    public interface ZstdBackend extends AutoCloseable {
        /** Returns the exact number of canonical bytes written. */
        int decompress(byte[] compressed, byte[] canonical, byte[] dictionary) throws IOException;

        @Override
        default void close() {}
    }

    public enum Failure {
        WIRE_CHECKSUM,
        MISSING_DICTIONARY,
        DICTIONARY_MISMATCH,
        MALFORMED_COMPRESSION,
        LENGTH_MISMATCH,
        HASH_MISMATCH,
        MALFORMED_CANONICAL
    }

    private final Executor worker;
    private final DictionaryResolver dictionaries;
    private final ZstdBackend zstd;
    private final Set<CompletableFuture<?>> pending = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    public ObjectDecoder(Executor worker, DictionaryResolver dictionaries,
                           ZstdBackend zstd) {
        this.worker = Objects.requireNonNull(worker, "worker");
        this.dictionaries = Objects.requireNonNull(dictionaries, "dictionaries");
        this.zstd = Objects.requireNonNull(zstd, "zstd");
    }

    public static ObjectDecoder withNativeZstd(Executor worker,
                                                 DictionaryResolver dictionaries) {
        return new ObjectDecoder(worker, dictionaries, new NativeZstdBackend());
    }

    /** Decodes and authenticates on the configured worker executor. */
    public CompletableFuture<CanonicalObject> decode(EncodedObject encoded) {
        Objects.requireNonNull(encoded, "encoded");
        if (this.closed) throw new IllegalStateException("object decoder is closed");
        return track(CompletableFuture.supplyAsync(() -> {
            try {
                return decodeOnWorker(encoded);
            } catch (DecodeException exception) {
                throw new CompletionException(exception);
            }
        }, this.worker));
    }

    /**
     * Offers the verified envelope to the disposable disk cache. A full pinned cache is
     * backpressure for persistence only; it must never reject otherwise valid terrain that can
     * still enter the separately bounded in-memory residency manager.
     */
    public CompletableFuture<CanonicalObject> decodeAndStore(EncodedObject encoded,
                                                                ObjectCache cache) {
        Objects.requireNonNull(cache, "cache");
        return track(decode(encoded).thenApplyAsync(object -> {
            try {
                cache.put(encoded, object);
            } catch (IOException ignored) {
                // Disk persistence is disposable; verified live content continues to residency.
            }
            return object;
        }, this.worker));
    }

    private CanonicalObject decodeOnWorker(EncodedObject encoded) throws DecodeException {
        if (this.closed) throw new DecodeException(Failure.MALFORMED_COMPRESSION,
                "object decoder is closed");
        byte[] compressed = encoded.compressedBytes();
        if (WireMessage.checksum(compressed) != encoded.compressedChecksum()) {
            throw new DecodeException(Failure.WIRE_CHECKSUM, "compressed object CRC32C mismatch");
        }

        byte[] dictionary = new byte[0];
        boolean microtile = encoded.kind() == WireMessage.ObjectKind.EXTERIOR_MICROTILE
                || encoded.kind() == WireMessage.ObjectKind.INTERIOR_MICROTILE
                || encoded.kind() == WireMessage.ObjectKind.COMPLEX_MICROTILE;
        if (microtile && (encoded.dictionaryId() == 0
                || Integer.compareUnsigned(encoded.dictionaryId(),
                DictionaryCodec.DICTIONARY_COUNT) > 0)) {
            throw new DecodeException(Failure.DICTIONARY_MISMATCH,
                    "compressed microtile lacks its typed production dictionary");
        }
        if (!microtile && encoded.dictionaryId() != 0) {
            throw new DecodeException(Failure.DICTIONARY_MISMATCH,
                    "structural production objects cannot reference a dictionary");
        }
        if (encoded.dictionaryId() != 0) {
            Optional<DictionaryCodec.Dictionary> resolved =
                    this.dictionaries.resolve(encoded.dictionaryId());
            if (resolved == null || resolved.isEmpty()) {
                throw new DecodeException(Failure.MISSING_DICTIONARY,
                        "required Zstd dictionary is unavailable");
            }
            DictionaryCodec.Dictionary typed = Objects.requireNonNull(
                    resolved.orElseThrow(), "dictionary");
            ManifestCodec.ContentClass expectedClass = switch (encoded.kind()) {
                case EXTERIOR_MICROTILE -> ManifestCodec.ContentClass.EXTERIOR;
                case INTERIOR_MICROTILE -> ManifestCodec.ContentClass.INTERIOR;
                case COMPLEX_MICROTILE -> ManifestCodec.ContentClass.COMPLEX;
                default -> throw new DecodeException(Failure.DICTIONARY_MISMATCH,
                        "only typed microtiles may reference a production dictionary");
            };
            if (typed.contentClass() != expectedClass) {
                throw new DecodeException(Failure.DICTIONARY_MISMATCH,
                        "Zstd dictionary content class disagrees with the object kind");
            }
            dictionary = typed.rawBytesInternal();
        }
        byte[] canonical = new byte[encoded.canonicalLength()];
        int written;
        try {
            written = this.zstd.decompress(compressed, canonical, dictionary);
        } catch (IOException | RuntimeException exception) {
            throw new DecodeException(Failure.MALFORMED_COMPRESSION,
                    "invalid compressed canonical object", exception);
        }
        if (written != canonical.length) {
            throw new DecodeException(Failure.LENGTH_MISMATCH,
                    "Zstd output length does not match canonical length");
        }

        CanonicalObject object;
        try {
            object = new CanonicalObject(encoded.hash(), encoded.kind(), canonical);
        } catch (IllegalArgumentException exception) {
            throw new DecodeException(Failure.HASH_MISMATCH,
                    "canonical object BLAKE3 identity mismatch", exception);
        }

        try {
            switch (encoded.kind()) {
                case ROOT_DIRECTORY -> ManifestCodec.decodeRootDirectory(canonical);
                case MANIFEST_SUBTREE -> ManifestCodec.decodeManifestSubtree(canonical);
                case MANIFEST_DESCRIPTOR_PAGE -> ManifestCodec.decodeDescriptorPage(canonical);
                case CATALOG -> CatalogCodec.decode(canonical);
                case DICTIONARY_SET -> DictionaryCodec.decodeSet(canonical);
                case EXTERIOR_MICROTILE, INTERIOR_MICROTILE, COMPLEX_MICROTILE ->
                        MicrotileCodec.inspect(canonical, encoded.kind());
                case COMPRESSION_DICTIONARY -> DictionaryCodec.decodeDictionary(canonical);
                case VISIBILITY_DIRECTORY, VISIBILITY_PAGE, VISIBILITY_SUMMARY_PAGE ->
                        throw new IllegalArgumentException(
                                "server-owned visibility graphs are not client content");
            }
        } catch (ManifestCodec.DecodeException | CatalogCodec.DecodeException
                 | DictionaryCodec.DecodeException
                 | MicrotileCodec.DecodeException
                 | IllegalArgumentException exception) {
            throw new DecodeException(Failure.MALFORMED_CANONICAL,
                    "canonical " + encoded.kind() + " structure is invalid", exception);
        }
        return object;
    }

    @Override
    public void close() {
        if (this.closed) return;
        this.closed = true;
        for (CompletableFuture<?> operation : this.pending) operation.cancel(true);
        this.zstd.close();
    }

    private <T> CompletableFuture<T> track(CompletableFuture<T> operation) {
        this.pending.add(operation);
        operation.whenComplete((ignored, failure) -> this.pending.remove(operation));
        // Covers a close racing between scheduling and registration after close() traversed the
        // concurrent set. Cancellation completes queued stages even if executor shutdown removes
        // their Runnable before it begins.
        if (this.closed) operation.cancel(true);
        return operation;
    }

    public static final class DecodeException extends Exception {
        private final Failure failure;

        public DecodeException(Failure failure, String message) {
            super(message);
            this.failure = Objects.requireNonNull(failure, "failure");
        }

        public DecodeException(Failure failure, String message, Throwable cause) {
            super(message, cause);
            this.failure = Objects.requireNonNull(failure, "failure");
        }

        public Failure failure() {
            return this.failure;
        }
    }

    /** One native decompression context per decoder worker thread, all released on close. */
    private static final class NativeZstdBackend implements ZstdBackend {
        private final ConcurrentLinkedQueue<Context> contexts = new ConcurrentLinkedQueue<>();
        private final ThreadLocal<Context> local = ThreadLocal.withInitial(() -> {
            Context context = new Context();
            this.contexts.add(context);
            return context;
        });
        private volatile boolean closed;
        private int activeCalls;

        @Override
        public int decompress(byte[] compressed, byte[] canonical, byte[] dictionary)
                throws IOException {
            Objects.requireNonNull(compressed, "compressed");
            Objects.requireNonNull(canonical, "canonical");
            Objects.requireNonNull(dictionary, "dictionary");
            Context context;
            synchronized (this) {
                if (this.closed) throw new IOException("native Zstd backend is closed");
                context = this.local.get();
                this.activeCalls++;
            }
            ByteBuffer source = null;
            ByteBuffer destination = null;
            ByteBuffer dictionaryBuffer = null;
            try {
                source = MemoryUtil.memAlloc(compressed.length);
                source.put(compressed).flip();
                destination = MemoryUtil.memAlloc(canonical.length);
                if (dictionary.length != 0) {
                    dictionaryBuffer = MemoryUtil.memAlloc(dictionary.length);
                    dictionaryBuffer.put(dictionary).flip();
                }
                long result = dictionaryBuffer == null
                        ? ZSTD_decompressDCtx(context.address, destination, source)
                        : ZSTD_decompress_usingDict(context.address, destination, source,
                        dictionaryBuffer);
                if (ZSTD_isError(result)) {
                    throw new IOException("Zstd decompression failed: " + ZSTD_getErrorName(result));
                }
                if (result != canonical.length) {
                    throw new IOException("Zstd output length " + result
                            + " does not match canonical length " + canonical.length);
                }
                destination.limit(canonical.length);
                destination.position(0);
                destination.get(canonical);
                return canonical.length;
            } finally {
                if (dictionaryBuffer != null) MemoryUtil.memFree(dictionaryBuffer);
                if (destination != null) MemoryUtil.memFree(destination);
                if (source != null) MemoryUtil.memFree(source);
                releaseCall();
            }
        }

        @Override
        public synchronized void close() {
            if (this.closed) return;
            this.closed = true;
            if (this.activeCalls == 0) freeContexts();
        }

        private synchronized void releaseCall() {
            if (--this.activeCalls < 0) {
                throw new IllegalStateException("native Zstd active-call underflow");
            }
            if (this.closed && this.activeCalls == 0) freeContexts();
        }

        private void freeContexts() {
            Context context;
            while ((context = this.contexts.poll()) != null) context.close();
            this.local.remove();
        }
    }

    private static final class Context implements AutoCloseable {
        private long address = ZSTD_createDCtx();

        private Context() {
            if (this.address == MemoryUtil.NULL) {
                throw new IllegalStateException("unable to allocate native Zstd context");
            }
        }

        @Override
        public void close() {
            long value = this.address;
            this.address = MemoryUtil.NULL;
            if (value == MemoryUtil.NULL) return;
            long result = ZSTD_freeDCtx(value);
            if (ZSTD_isError(result)) {
                throw new IllegalStateException(
                        "unable to free native Zstd context: " + ZSTD_getErrorName(result));
            }
        }
    }
}
