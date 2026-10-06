package me.cortex.voxy.client.lod;

import me.cortex.voxy.client.core.rendering.hierarchical.HierarchicalOcclusionTraverser;
import org.lwjgl.system.MemoryUtil;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Exercises the production bounded reader and mailbox, without GL, networking or Minecraft. */
public final class DetailBatchBehaviorTest {
    private static final int BUCKETS = HierarchicalOcclusionTraverser.DETAIL_BUCKET_COUNT;
    private static final int RECORDS = HierarchicalOcclusionTraverser.ACTIONS_PER_BUCKET;
    private static final long BYTES = BUCKETS * 4L + BUCKETS * RECORDS * 16L;

    public static void main(String[] args) throws Exception { run(); }

    public static void run() throws Exception {
        mailboxEquivalence();
        mailboxScopeAndPrefix();
        concurrentTake();
        borrowedReadback();
        System.out.println("production detail batch epoch/scope/prefix/concurrency/native-lifetime checks passed");
    }

    private static SectionDemandTable<SectionDemandTable.Demand> table() {
        return new SectionDemandTable<>(BUCKETS);
    }

    private static Map<Long, SectionDemandTable.DetailUpdate> drain(
            SectionDemandTable<SectionDemandTable.Demand> table) {
        Map<Long, SectionDemandTable.DetailUpdate> result = new HashMap<>();
        table.drainDetail(result::put);
        return result;
    }

    private static void mailboxEquivalence() {
        var sequential = table();
        var batch = table();
        var merged = new SectionDemandTable.DetailBatchMerge();
        HierarchicalOcclusionTraverser.DetailActionReader reader = consumer -> {
            for (int index = 0; index < 8192; index++) {
                long key = index % 53;
                int action = index % 5;
                int bucket = index % 36 - 2;
                int epoch = index % 7 == 0 ? -1 : index;
                consumer.accept(key, action, bucket, epoch);
            }
        };
        reader.read(sequential::offerDetail);
        batch.offerDetailBatch(batch.detailInputGeneration(), () -> true, reader, merged);
        check(merged.inspected == 8192 && merged.accepted > 0, "full mailbox batch not consumed");
        check(sequential.overwrittenInputCount() == batch.overwrittenInputCount(), "overwrite accounting changed");
        check(drain(sequential).equals(drain(batch)), "sequential/batch latest values differ");

        batch.offerDetail(1, 0, 10, Integer.MAX_VALUE);
        batch.offerDetail(1, 1, 20, Integer.MIN_VALUE);
        batch.offerDetail(1, 2, 30, Integer.MIN_VALUE);
        batch.offerDetail(1, 0, 0, Integer.MAX_VALUE);
        check(drain(batch).get(1L).equals(new SectionDemandTable.DetailUpdate(1, 20, Integer.MIN_VALUE)),
                "unsigned epoch or equal-epoch rejection changed");
        var empty = new SectionDemandTable.DetailBatchMerge();
        batch.offerDetailBatch(batch.detailInputGeneration(), () -> true, consumer -> {}, empty);
        check(empty.inspected == 0 && empty.accepted == 0, "empty batch accepted an action");
    }

    private static void mailboxScopeAndPrefix() {
        var table = table();
        long beforeReset = table.detailInputGeneration();
        table.offerDetail(1, 0, 0, 1);
        table.invalidateDetailInput();
        check(drain(table).isEmpty(), "reset retained queued feedback");
        AtomicInteger reads = new AtomicInteger();
        HierarchicalOcclusionTraverser.DetailActionReader reader = consumer -> {
            reads.incrementAndGet();
            consumer.accept(1, 0, 0, 2);
        };
        var stale = new SectionDemandTable.DetailBatchMerge();
        table.offerDetailBatch(beforeReset, () -> true, reader, stale);
        table.offerDetailBatch(table.detailInputGeneration(), () -> false, reader, stale);
        check(reads.get() == 0 && stale.accepted == 0, "stale reset/scope reader was invoked");
        long beforeClear = table.detailInputGeneration();
        table.clear();
        table.offerDetailBatch(beforeClear, () -> true, reader, stale);
        check(reads.get() == 0, "clear did not invalidate captured feedback");

        var prefix = new SectionDemandTable.DetailBatchMerge();
        RuntimeException failure = new RuntimeException("injected detail-reader failure");
        try {
            table.offerDetailBatch(table.detailInputGeneration(), () -> true, consumer -> {
                consumer.accept(1, 0, 0, 10);
                consumer.accept(2, 1, 1, 10);
                consumer.accept(3, 17, 1, 10);
                throw failure;
            }, prefix);
            throw new AssertionError("reader failure was swallowed");
        } catch (RuntimeException actual) {
            check(actual == failure, "reader failure identity changed");
        }
        check(prefix.inspected == 3 && prefix.accepted == 2 && drain(table).size() == 2,
                "accepted prefix was lost after reader failure");
    }

    private static void concurrentTake() throws Exception {
        var table = table();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var taking = new CountDownLatch(1);
        var taken = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<Map<Long, SectionDemandTable.DetailUpdate>> values = new AtomicReference<>();
        Thread producer = new Thread(() -> {
            try {
                table.offerDetailBatch(table.detailInputGeneration(), () -> true, consumer -> {
                    consumer.accept(1, 0, 0, 1);
                    entered.countDown();
                    await(release);
                    consumer.accept(2, 0, 0, 1);
                }, new SectionDemandTable.DetailBatchMerge());
            } catch (Throwable problem) { failure.set(problem); }
        }, "detail-batch-fixture-producer");
        Thread taker = new Thread(() -> {
            try {
                await(entered);
                taking.countDown();
                values.set(drain(table));
                taken.countDown();
            } catch (Throwable problem) { failure.set(problem); }
        }, "detail-batch-fixture-taker");
        producer.start(); taker.start();
        try {
            await(entered); await(taking);
            check(!taken.await(30, TimeUnit.MILLISECONDS), "take observed an in-progress batch prefix");
        } finally {
            release.countDown();
            producer.join(3000); taker.join(3000);
        }
        check(!producer.isAlive() && !taker.isAlive(), "mailbox workers did not finish");
        if (failure.get() != null) throw new AssertionError("mailbox fixture worker failed", failure.get());
        check(values.get().size() == 2, "atomic take lost accepted batch records");
    }

    private static void borrowedReadback() throws Exception {
        var readback = new HierarchicalOcclusionTraverser.DetailReadback();
        long memory = MemoryUtil.nmemCalloc(1, BYTES);
        check(memory != 0, "native fixture allocation failed");
        try {
            AtomicInteger calls = new AtomicInteger();
            readback.setListener(() -> reader -> reader.read((key, action, bucket, epoch) -> {
                int expected = calls.getAndIncrement();
                check(key == 0xfedcba9800000000L + expected, "record key/order changed");
                check(bucket == expected / RECORDS, "bucket order changed");
                check(action == expected % 3 && epoch == Integer.MIN_VALUE + expected,
                        "record action/epoch changed");
            }));
            readback.capture().consume(memory, BYTES);
            check(calls.get() == 0, "zero counts produced actions");
            for (int bucket = 0; bucket < BUCKETS; bucket++) {
                // Both signed-negative and oversized unsigned counters must clamp to 256.
                MemoryUtil.memPutInt(memory + bucket * 4L, bucket % 2 == 0 ? -1 : RECORDS + 100);
                for (int index = 0; index < RECORDS; index++) {
                    int ordinal = bucket * RECORDS + index;
                    put(memory, bucket, index, 0xfedcba9800000000L + ordinal,
                            ordinal % 3, Integer.MIN_VALUE + ordinal);
                }
            }
            readback.capture().consume(memory, BYTES);
            check(calls.get() == 8192, "bounded readback count changed");
            calls.set(0);
            readback.setListener(() -> reader -> reader.read((key, action, bucket, epoch) -> calls.incrementAndGet()));
            put(memory, 0, 0, 1, 999, 2);
            readback.capture().consume(memory, BYTES);
            check(calls.get() == 8191, "unsupported action was delivered");
            expect(IllegalArgumentException.class, () -> readback.capture().consume(memory, BYTES - 1));

            var scheduled = readback.capture();
            readback.setListener(() -> reader -> { throw new AssertionError("old registration was redirected"); });
            scheduled.consume(memory, BYTES);
            readback.stop();
            readback.capture().consume(0, 0); // No target must not inspect native memory.
            readback.setListener(() -> null);
            readback.capture().consume(0, 0);

            AtomicReference<HierarchicalOcclusionTraverser.DetailActionReader> escaped = new AtomicReference<>();
            readback.setListener(() -> reader -> {
                escaped.set(reader);
                AtomicReference<Throwable> crossThread = new AtomicReference<>();
                Thread thread = new Thread(() -> {
                    try { reader.read((key, action, bucket, epoch) -> {}); }
                    catch (Throwable problem) { crossThread.set(problem); }
                });
                thread.start();
                try { thread.join(3000); }
                catch (InterruptedException problem) { throw new AssertionError(problem); }
                check(!thread.isAlive() && crossThread.get() instanceof IllegalStateException,
                        "borrowed reader escaped to another thread");
            });
            readback.capture().consume(memory, BYTES);
            expect(IllegalStateException.class, () -> escaped.get().read((key, action, bucket, epoch) -> {}));

            RuntimeException failure = new RuntimeException("injected callback failure");
            readback.setListener(() -> reader -> { escaped.set(reader); throw failure; });
            try { readback.capture().consume(memory, BYTES); throw new AssertionError("callback exception lost"); }
            catch (RuntimeException actual) { check(actual == failure, "callback exception changed"); }
            expect(IllegalStateException.class, () -> escaped.get().read((key, action, bucket, epoch) -> {}));

            readback.setListener(() -> reader -> {
                readback.stop();
                reader.read((key, action, bucket, epoch) -> { throw new AssertionError("replaced registration read"); });
            });
            readback.capture().consume(memory, BYTES);
        } finally { MemoryUtil.nmemFree(memory); }
    }

    private static void put(long memory, int bucket, int index, long key, int action, int epoch) {
        long address = memory + BUCKETS * 4L + ((long) bucket * RECORDS + index) * 16L;
        MemoryUtil.memPutInt(address, (int) (key >>> 32));
        MemoryUtil.memPutInt(address + 4, (int) key);
        MemoryUtil.memPutInt(address + 8, action);
        MemoryUtil.memPutInt(address + 12, epoch);
    }

    private static void await(CountDownLatch latch) {
        try { check(latch.await(3, TimeUnit.SECONDS), "fixture latch timed out"); }
        catch (InterruptedException problem) { Thread.currentThread().interrupt(); throw new AssertionError(problem); }
    }

    private static void expect(Class<? extends Throwable> type, Runnable operation) {
        try { operation.run(); }
        catch (Throwable problem) { if (type.isInstance(problem)) return; throw new AssertionError(problem); }
        throw new AssertionError("expected " + type.getSimpleName());
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
