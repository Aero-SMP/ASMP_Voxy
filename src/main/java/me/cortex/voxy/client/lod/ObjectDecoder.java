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
import java.util.concurrent.Executor;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.lwjgl.util.zstd.Zstd.ZSTD_createDCtx;
import static org.lwjgl.util.zstd.Zstd.ZSTD_decompressDCtx;
import static org.lwjgl.util.zstd.Zstd.ZSTD_decompress_usingDict;
import static org.lwjgl.util.zstd.Zstd.ZSTD_freeDCtx;
import static org.lwjgl.util.zstd.Zstd.ZSTD_getErrorName;
import static org.lwjgl.util.zstd.Zstd.ZSTD_isError;

/** Bounded off-thread decompression, authentication, and canonical typed decoding. */
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
        int decompress(ByteBuffer compressed, byte[] canonical, byte[] dictionary)
                throws IOException;

        @Override
        default void close() {}
    }

    public enum Failure {
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
    private final Set<DecodeTask> pending = ConcurrentHashMap.newKeySet();
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
    public CompletableFuture<DecodedObject> decode(EncodedObject encoded) {
        Objects.requireNonNull(encoded, "encoded");
        if (this.closed) throw new IllegalStateException("object decoder is closed");
        EncodedObject retained = encoded.retain();
        DecodeTask task = new DecodeTask(retained);
        this.pending.add(task);
        try {
            this.worker.execute(task);
        } catch (RuntimeException | Error failure) {
            task.cancel();
            throw failure;
        }
        if (this.closed) task.cancel();
        return task.result;
    }

    private DecodedObject decodeOnWorker(EncodedObject encoded) throws DecodeException {
        if (this.closed) throw new DecodeException(Failure.MALFORMED_COMPRESSION,
                "object decoder is closed");
        ByteBuffer compressed = encoded.compressedBufferInternal();

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

        if (!ObjectHash.verifies(encoded.hash(), encoded.kind(), canonical)) {
            throw new DecodeException(Failure.HASH_MISMATCH,
                    "canonical object BLAKE3 identity mismatch");
        }

        try {
            Object decoded = switch (encoded.kind()) {
                case ROOT_DIRECTORY -> ManifestCodec.decodeRootDirectory(canonical);
                case MANIFEST_SUBTREE -> ManifestCodec.decodeManifestSubtree(canonical);
                case MANIFEST_DESCRIPTOR_PAGE -> ManifestCodec.decodeDescriptorPage(canonical);
                case CATALOG -> CatalogCodec.decode(canonical);
                case DICTIONARY_SET -> DictionaryCodec.decodeSet(canonical);
                case EXTERIOR_MICROTILE, INTERIOR_MICROTILE, COMPLEX_MICROTILE ->
                        MicrotileCodec.decodeCanonical(canonical, encoded.kind());
                case COMPRESSION_DICTIONARY -> DictionaryCodec.decodeDictionary(canonical);
                case VISIBILITY_DIRECTORY, VISIBILITY_PAGE, VISIBILITY_SUMMARY_PAGE ->
                        throw new IllegalArgumentException(
                                "server-owned visibility graphs are not client content");
            };
            return new DecodedObject(encoded.hash(), encoded.kind(), canonical.length, decoded);
        } catch (ManifestCodec.DecodeException | CatalogCodec.DecodeException
                 | DictionaryCodec.DecodeException
                 | MicrotileCodec.DecodeException
                 | IllegalArgumentException exception) {
            throw new DecodeException(Failure.MALFORMED_CANONICAL,
                    "canonical " + encoded.kind() + " structure is invalid", exception);
        }
    }

    @Override
    public void close() {
        if (this.closed) return;
        this.closed = true;
        for (DecodeTask task : this.pending) task.cancel();
        this.zstd.close();
    }

    /** Retains the body until the actual worker stops, even if its result is cancelled early. */
    private final class DecodeTask implements Runnable {
        private static final int QUEUED = 0;
        private static final int RUNNING = 1;
        private static final int DONE = 2;
        private static final int CANCELLED = 3;

        private final EncodedObject encoded;
        private final CompletableFuture<DecodedObject> result = new CompletableFuture<>();
        private final AtomicInteger state = new AtomicInteger(QUEUED);

        private DecodeTask(EncodedObject encoded) {
            this.encoded = encoded;
        }

        @Override
        public void run() {
            if (!this.state.compareAndSet(QUEUED, RUNNING)) return;
            try {
                this.result.complete(decodeOnWorker(this.encoded));
            } catch (Throwable failure) {
                this.result.completeExceptionally(failure);
            } finally {
                this.encoded.close();
                this.state.set(DONE);
                pending.remove(this);
            }
        }

        private void cancel() {
            this.result.cancel(false);
            if (this.state.compareAndSet(QUEUED, CANCELLED)) {
                this.encoded.close();
                pending.remove(this);
            }
        }
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
        public int decompress(ByteBuffer compressed, byte[] canonical, byte[] dictionary)
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
            ByteBuffer allocatedSource = null;
            ByteBuffer destination = null;
            ByteBuffer dictionaryBuffer = null;
            try {
                ByteBuffer source;
                if (compressed.isDirect()) {
                    source = compressed.duplicate();
                } else {
                    allocatedSource = MemoryUtil.memAlloc(compressed.remaining());
                    allocatedSource.put(compressed.duplicate()).flip();
                    source = allocatedSource;
                }
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
                if (allocatedSource != null) MemoryUtil.memFree(allocatedSource);
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
