// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal;

import static com.swirlds.logging.legacy.LogMarker.VIRTUAL_MERKLE_STATS;
import static com.swirlds.virtualmap.test.fixtures.TestKey.longToKey;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hiero.base.utility.test.fixtures.assertions.AssertionUtils.assertEventuallyTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.swirlds.virtualmap.datasource.VirtualDataSource;
import com.swirlds.virtualmap.datasource.VirtualHashChunk;
import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import com.swirlds.virtualmap.test.fixtures.TestValue;
import com.swirlds.virtualmap.test.fixtures.datasource.InMemoryDataSource;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.RecursiveAction;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.hiero.base.crypto.Cryptography;
import org.hiero.base.crypto.Hash;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

public class HashLeafFlusherTest {

    private static final int DEFAULT_FLUSH_INTERVAL = 500_000;

    @Test
    void testNullDataSourceThrows() {
        assertThrows(
                NullPointerException.class, () -> new HashLeafFlusher(null, 100, mock(VirtualMapStatistics.class)));
    }

    @Test
    void testNullStatsThrows() {
        assertThrows(NullPointerException.class, () -> new HashLeafFlusher(mock(VirtualDataSource.class), 100, null));
    }

    // Simulates MerkleDbDataSource, which restores the interrupted flag and returns normally
    private static InMemoryDataSource interruptingDataSource(final String name) {
        return new InMemoryDataSource(name) {
            @Override
            @SuppressWarnings("rawtypes")
            public void saveRecords(
                    final long firstLeafPath,
                    final long lastLeafPath,
                    final Stream<VirtualHashChunk> hashChunksToUpdate,
                    final Stream<VirtualLeafBytes> leafRecordsToAddOrUpdate,
                    final Stream<VirtualLeafBytes> leafRecordsToDelete,
                    final boolean isReconnectContext)
                    throws IOException {
                super.saveRecords(
                        firstLeafPath,
                        lastLeafPath,
                        hashChunksToUpdate,
                        leafRecordsToAddOrUpdate,
                        leafRecordsToDelete,
                        isReconnectContext);
                Thread.currentThread().interrupt();
            }
        };
    }

    @Test
    void testInterruptedFlushFails() {
        final HashLeafFlusher flusher = new HashLeafFlusher(
                interruptingDataSource("testInterruptedFlushFails"),
                0,
                new VirtualMapStatistics("testInterruptedFlushFails"));
        flusher.init(1, 2);
        try {
            assertThrows(IllegalStateException.class, flusher::finish);
            assertTrue(Thread.currentThread().isInterrupted(), "Interrupted flag must be preserved");
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void testInterruptedIntermediateFlushFails() throws InterruptedException {
        final HashLeafFlusher flusher = new HashLeafFlusher(
                interruptingDataSource("testInterruptedIntermediateFlushFails"),
                1,
                new VirtualMapStatistics("testInterruptedIntermediateFlushFails"));
        flusher.init(1, 2);
        // The batch is due, so it's flushed in the flusher thread, which is interrupted by the data source
        flusher.updateLeaf(leaf(1, 1, 1));
        // The flush must not be treated as a success, the failure is sticky. Calls before the flush completes
        // may succeed, they only add to the next batch
        final AtomicReference<IllegalStateException> update = new AtomicReference<>();
        assertEventuallyTrue(
                () -> {
                    try {
                        flusher.updateLeaf(leaf(2, 2, 2));
                        return false;
                    } catch (final IllegalStateException e) {
                        update.set(e);
                        return true;
                    }
                },
                Duration.ofSeconds(10),
                "Calls after a failed flush must fail");
        assertInstanceOf(IllegalStateException.class, update.get().getCause());
        final IllegalStateException finish = assertThrows(IllegalStateException.class, flusher::finish);
        assertSame(update.get().getCause(), finish.getCause());
        assertTrue(flusher.awaitTermination(Duration.ofSeconds(10)), "Flusher thread must exit after finish()");
        assertFalse(Thread.currentThread().isInterrupted(), "Only the flusher thread must be interrupted");
    }

    @Test
    void testFinishStopsFlusherThread() throws InterruptedException {
        final HashLeafFlusher flusher = new HashLeafFlusher(
                new InMemoryDataSource("testFinishStopsFlusherThread"),
                DEFAULT_FLUSH_INTERVAL,
                new VirtualMapStatistics("testFinishStopsFlusherThread"));
        flusher.init(1, 2);
        flusher.finish();
        assertTrue(flusher.awaitTermination(Duration.ofSeconds(10)), "Flusher thread must exit after finish()");
    }

    @Test
    void testCancelStopsIdleFlusherThread() throws InterruptedException {
        final HashLeafFlusher flusher = new HashLeafFlusher(
                new InMemoryDataSource("testCancelStopsIdleFlusherThread"),
                DEFAULT_FLUSH_INTERVAL,
                new VirtualMapStatistics("testCancelStopsIdleFlusherThread"));
        flusher.init(1, 2);
        flusher.cancel();
        assertTrue(flusher.awaitTermination(Duration.ofSeconds(10)), "Flusher thread must exit after cancel()");
    }

    @Test
    void testCancelBeforeInit() throws InterruptedException {
        final HashLeafFlusher flusher = new HashLeafFlusher(
                new InMemoryDataSource("testCancelBeforeInit"),
                DEFAULT_FLUSH_INTERVAL,
                new VirtualMapStatistics("testCancelBeforeInit"));
        // Reconnect may be aborted before the flusher is initialized
        flusher.cancel();
        flusher.init(1, 2);
        assertTrue(flusher.awaitTermination(Duration.ofSeconds(10)), "Flusher thread of a cancelled flusher must exit");
        final IllegalStateException update =
                assertThrows(IllegalStateException.class, () -> flusher.updateLeaf(leaf(1, 1, 1)));
        assertInstanceOf(CancellationException.class, update.getCause());
        final IllegalStateException finish = assertThrows(IllegalStateException.class, flusher::finish);
        assertInstanceOf(CancellationException.class, finish.getCause());
    }

    @ParameterizedTest
    @CsvSource({
        "-2,  1", // Invalid negative first, good last
        " 1, -2", // Good first, invalid negative last
        " 0,  1", // Invalid zero first, good last
        " 1,  0", // Good first, invalid zero last
        " 0,  0", // Both invalid
        " 9, 8"
    }) // Invalid (both should be equal only if == 1
    @DisplayName("Illegal first and last leaf path combinations throw")
    void testInvalidLeafPaths(long firstLeafPath, long lastLeafPath) {
        final VirtualDataSource ds = new InMemoryDataSource("testNadLeafPaths");
        final VirtualMapStatistics stats = new VirtualMapStatistics("testNadLeafPaths");
        final HashLeafFlusher flusher = new HashLeafFlusher(ds, DEFAULT_FLUSH_INTERVAL, stats);
        assertThrows(IllegalArgumentException.class, () -> flusher.init(firstLeafPath, lastLeafPath));
    }

    @ParameterizedTest
    @CsvSource({
        " 1,  2", // Non-empty range
        "-1, -1" // Empty range
    })
    void testInitTwiceFails(final long firstLeafPath, final long lastLeafPath) {
        final HashLeafFlusher flusher = new HashLeafFlusher(
                new InMemoryDataSource("testInitTwiceFails"),
                DEFAULT_FLUSH_INTERVAL,
                new VirtualMapStatistics("testInitTwiceFails"));
        flusher.init(firstLeafPath, lastLeafPath);
        assertThrows(IllegalArgumentException.class, () -> flusher.init(firstLeafPath, lastLeafPath));
        assertThrows(IllegalArgumentException.class, () -> flusher.init(3, 6));
        flusher.cancel();
    }

    @Test
    void testFinishTwiceFails() {
        final HashLeafFlusher flusher = new HashLeafFlusher(
                new InMemoryDataSource("testFinishTwiceFails"),
                DEFAULT_FLUSH_INTERVAL,
                new VirtualMapStatistics("testFinishTwiceFails"));
        flusher.init(1, 2);
        flusher.finish();
        assertThrows(IllegalStateException.class, flusher::finish);
    }

    @Test
    void testUpdateWithoutInitOrAfterFinishFails() {
        final HashLeafFlusher flusher = new HashLeafFlusher(
                new InMemoryDataSource("testUpdateWithoutInitOrAfterFinishFails"),
                DEFAULT_FLUSH_INTERVAL,
                new VirtualMapStatistics("testUpdateWithoutInitOrAfterFinishFails"));
        final IllegalStateException beforeInit =
                assertThrows(IllegalStateException.class, () -> flusher.updateLeaf(leaf(1, 1, 1)));
        assertNull(beforeInit.getCause());
        flusher.init(1, 2);
        flusher.finish();
        // The executor is shut down by finish(), but it must not be reported as cancelled
        final IllegalStateException afterFinish =
                assertThrows(IllegalStateException.class, () -> flusher.updateLeaf(leaf(1, 1, 1)));
        assertNull(afterFinish.getCause());
    }

    @Test
    void testFinishWithoutInitFails() {
        final HashLeafFlusher flusher = new HashLeafFlusher(
                new InMemoryDataSource("testFinishWithoutInitFails"),
                DEFAULT_FLUSH_INTERVAL,
                new VirtualMapStatistics("testFinishWithoutInitFails"));
        assertThrows(IllegalStateException.class, flusher::finish);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 10, 31})
    void testHashesFlushed(final int flushInterval) throws Exception {
        final VirtualDataSource ds = new InMemoryDataSource("testHashesFlushed");
        final int hashChunkHeight = ds.getHashChunkHeight();
        final VirtualMapStatistics stats = new VirtualMapStatistics("testHashesFlushed");
        final HashLeafFlusher flusher = new HashLeafFlusher(ds, flushInterval, stats);
        final int COUNT = 500;
        flusher.init(COUNT - 1, COUNT * 2 - 2);
        final long minHashChunkId = VirtualHashChunk.lastChunkIdForPaths(COUNT * 2 - 2, hashChunkHeight);
        for (int i = 0; i <= minHashChunkId; i++) {
            final long chunkPath = VirtualHashChunk.chunkIdToChunkPath(i, hashChunkHeight);
            final VirtualHashChunk chunk = new VirtualHashChunk(chunkPath, hashChunkHeight);
            for (int j = 0; j < chunk.getChunkSize(); j++) {
                final long path = VirtualHashChunk.getPathInChunk(j, chunkPath, hashChunkHeight);
                chunk.setHashAtPath(path, hash((int) (path + 2)));
            }
            flusher.updateHashChunk(chunk);
        }
        flusher.finish();
        assertEquals(COUNT - 1, ds.getFirstLeafPath());
        assertEquals(COUNT * 2 - 2, ds.getLastLeafPath());
        for (int i = 0; i <= minHashChunkId; i++) {
            final VirtualHashChunk chunk = ds.loadHashChunk(i);
            assertNotNull(chunk);
            for (int j = 0; j < chunk.getChunkSize(); j++) {
                final long path = chunk.getPath(j);
                final Hash hash = chunk.getHashAtPath(path);
                assertEquals(hash((int) path + 2), hash);
            }
        }
        assertNull(ds.loadHashChunk(minHashChunkId + 1));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 10, 31})
    void testLeavesFlushed(final int flushInterval) throws Exception {
        final VirtualDataSource ds = new InMemoryDataSource("testLeavesFlushed");
        final VirtualMapStatistics stats = new VirtualMapStatistics("testLeavesFlushed");
        final HashLeafFlusher flusher = new HashLeafFlusher(ds, flushInterval, stats);
        final int COUNT = 500;
        flusher.init(COUNT - 1, COUNT * 2 - 2);
        for (int i = COUNT - 1; i < COUNT * 2 - 1; i++) {
            flusher.updateLeaf(leaf(i, i + 2, i * 2));
        }
        flusher.finish();
        assertEquals(COUNT - 1, ds.getFirstLeafPath());
        assertEquals(COUNT * 2 - 2, ds.getLastLeafPath());
        for (int i = COUNT - 1; i < COUNT * 2 - 1; i++) {
            VirtualLeafBytes bytes = ds.loadLeafRecord(i);
            assertNotNull(bytes);
            assertEquals(leaf(i, i + 2, i * 2), bytes);
            bytes = ds.loadLeafRecord(bytes.keyBytes());
            assertNotNull(bytes);
            assertEquals(i, bytes.path());
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 10, 31})
    void testLeavesDeleted(final int flushInterval) throws Exception {
        final VirtualDataSource ds = new InMemoryDataSource("testLeavesDeleted");
        final int COUNT = 500;
        ds.saveRecords(
                COUNT / 2 + 99,
                COUNT + 198,
                Stream.of(),
                IntStream.range(COUNT / 2 + 99, COUNT + 199).mapToObj(i -> leaf(i, i, i)),
                Stream.of(),
                false);
        final VirtualMapStatistics stats = new VirtualMapStatistics("testLeavesDeleted");
        final HashLeafFlusher flusher = new HashLeafFlusher(ds, flushInterval, stats);
        flusher.init(COUNT - 1, COUNT * 2 - 2);
        for (int i = COUNT / 2 + 99; i < COUNT - 1; i++) {
            flusher.deleteLeaf(leaf(i, i, i));
        }
        for (int i = COUNT - 1; i < COUNT * 2 - 1; i++) {
            flusher.updateLeaf(leaf(i, i + 2, i * 2));
        }
        flusher.finish();
        // I can't call loadLeafRecord(COUNT / 2 + 99), since it's outside the current ds path range. Let's
        // adjust the path range first, then check that the leaves are actually deleted
        ds.saveRecords(COUNT / 2 + 99, COUNT + 198, Stream.of(), Stream.of(), Stream.of(), false);
        for (int i = COUNT / 2 + 99; i < COUNT - 1; i++) {
            final int fi = i;
            // InMemoryDataSource.loadLeafRecord() throws an assertion error, when the record doesn't exist
            assertThrows(AssertionError.class, () -> ds.loadLeafRecord(fi));
        }
        for (int i = COUNT - 1; i < COUNT + 199; i++) {
            VirtualLeafBytes bytes = ds.loadLeafRecord(i);
            assertNotNull(bytes);
            assertEquals(leaf(i, i + 2, i * 2), bytes);
            bytes = ds.loadLeafRecord(bytes.keyBytes());
            assertNotNull(bytes);
            assertEquals(i, bytes.path());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void testDeleteMovedLeaf(final boolean reconnectContext) throws Exception {
        final VirtualDataSource ds = new InMemoryDataSource("testDeleteMovedLeaf");
        // Key 100 has already been moved from path 6 to path 7
        ds.saveRecords(5, 10, Stream.of(), Stream.of(leaf(7, 100, 100)), Stream.of(), false);
        final HashLeafFlusher flusher = new HashLeafFlusher(
                ds,
                0,
                new VirtualMapStatistics("testDeleteMovedLeaf"),
                VIRTUAL_MERKLE_STATS.getMarker(),
                reconnectContext);
        flusher.init(5, 10);
        flusher.deleteLeaf(leaf(6, 100, 100));
        flusher.finish();
        final VirtualLeafBytes<?> loaded = ds.loadLeafRecord(longToKey(100));
        if (reconnectContext) {
            // The key is mapped to a different path, it must not be deleted
            assertNotNull(loaded);
            assertEquals(7, loaded.path());
        } else {
            assertNull(loaded);
        }
    }

    @Nested
    class Backpressure {

        private static final int INTERVAL = 10;

        // Gated data sources, flushers, and producers created by a test, released and stopped after the test,
        // even if it fails
        private final List<GatedDataSource> gatedSources = new ArrayList<>();
        private final List<HashLeafFlusher> flushers = new ArrayList<>();
        private final List<Producer> producers = new ArrayList<>();

        @AfterEach
        void releaseBlockedThreads() throws InterruptedException {
            gatedSources.forEach(GatedDataSource::open);
            flushers.forEach(HashLeafFlusher::cancel);
            for (final Producer producer : producers) {
                producer.thread().interrupt();
                producer.thread().join(SECONDS.toMillis(10));
            }
        }

        /** Data source, whose saveRecords() blocks until the gate is opened, and optionally fails once. */
        private static class GatedDataSource extends InMemoryDataSource {
            final CountDownLatch gate = new CountDownLatch(1);
            final AtomicInteger saves = new AtomicInteger();
            final AtomicInteger maxLeavesPerSave = new AtomicInteger();
            // Threads that called saveRecords()
            final Set<Thread> saveThreads = ConcurrentHashMap.newKeySet();
            volatile boolean gated = true;
            volatile long delayMs = 0;
            volatile RuntimeException failWith;
            // Called in every saveRecords(), after the gate and the delay
            volatile Runnable onSave;

            GatedDataSource(final String name) {
                super(name);
            }

            void open() {
                gated = false;
                gate.countDown();
            }

            @Override
            @SuppressWarnings("rawtypes")
            public void saveRecords(
                    final long firstLeafPath,
                    final long lastLeafPath,
                    final Stream<VirtualHashChunk> hashChunksToUpdate,
                    final Stream<VirtualLeafBytes> leafRecordsToAddOrUpdate,
                    final Stream<VirtualLeafBytes> leafRecordsToDelete,
                    final boolean isReconnectContext)
                    throws IOException {
                saves.incrementAndGet();
                saveThreads.add(Thread.currentThread());
                final List<VirtualLeafBytes> leaves = leafRecordsToAddOrUpdate.toList();
                maxLeavesPerSave.accumulateAndGet(leaves.size(), Math::max);
                try {
                    if (gated) {
                        gate.await();
                    }
                    if (delayMs > 0) {
                        Thread.sleep(delayMs);
                    }
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                final Runnable hook = onSave;
                if (hook != null) {
                    hook.run();
                }
                final RuntimeException failure = failWith;
                if (failure != null) {
                    failWith = null;
                    throw failure;
                }
                super.saveRecords(
                        firstLeafPath,
                        lastLeafPath,
                        hashChunksToUpdate,
                        leaves.stream(),
                        leafRecordsToDelete,
                        isReconnectContext);
            }
        }

        /** A thread that calls the flusher, and the outcome of the call. */
        private record Producer(Thread thread, CompletableFuture<Void> result) {

            void await() throws Exception {
                result.get(10, SECONDS);
            }

            // Waits till the thread is blocked in the flusher. Stops waiting early, if the thread completes instead.
            // Producers and finish() wait in Future.get()
            void awaitBlocked() {
                assertEventuallyTrue(
                        () -> result.isDone()
                                || thread.getState() == Thread.State.WAITING
                                || thread.getState() == Thread.State.TIMED_WAITING,
                        Duration.ofSeconds(10),
                        "Producer didn't block in time");
                assertFalse(result.isDone(), "Producer must block");
            }
        }

        private Producer start(final Runnable action) {
            final CompletableFuture<Void> result = new CompletableFuture<>();
            final Thread thread = new Thread(() -> {
                try {
                    action.run();
                    result.complete(null);
                } catch (final Throwable e) {
                    result.completeExceptionally(e);
                }
            });
            thread.setDaemon(true);
            final Producer producer = new Producer(thread, result);
            producers.add(producer);
            thread.start();
            return producer;
        }

        private Producer addLeaves(final HashLeafFlusher flusher, final int from, final int count) {
            return start(() -> {
                for (int i = from; i < from + count; i++) {
                    flusher.updateLeaf(leaf(i, i, i));
                }
            });
        }

        private GatedDataSource gatedDataSource(final String name) {
            final GatedDataSource ds = new GatedDataSource(name);
            gatedSources.add(ds);
            return ds;
        }

        private HashLeafFlusher flusher(final VirtualDataSource ds, final int flushInterval) {
            final HashLeafFlusher flusher =
                    new HashLeafFlusher(ds, flushInterval, new VirtualMapStatistics("backpressure"));
            flusher.init(1, 10_000);
            flushers.add(flusher);
            return flusher;
        }

        // Starts a flush of leaves 1 to INTERVAL that blocks in the data source
        private void startBlockedFlush(final HashLeafFlusher flusher, final GatedDataSource ds) throws Exception {
            // Producers never flush, so the first one returns while the flush is blocked in the flusher thread
            addLeaves(flusher, 1, INTERVAL).await();
            awaitSaves(ds, 1);
        }

        private static void awaitSaves(final GatedDataSource ds, final int count) {
            assertEventuallyTrue(
                    () -> ds.saves.get() == count, Duration.ofSeconds(10), "Expected " + count + " flushes");
        }

        private static void assertLeavesPresent(final VirtualDataSource ds, final int from, final int count)
                throws IOException {
            for (int i = from; i < from + count; i++) {
                assertEquals(leaf(i, i, i), ds.loadLeafRecord(i), "Leaf at path " + i);
            }
        }

        @Test
        void testProducerBlocksWhenNextBatchFull() throws Exception {
            final GatedDataSource ds = gatedDataSource("blocks");
            final HashLeafFlusher flusher = flusher(ds, INTERVAL);
            startBlockedFlush(flusher, ds);
            // The next batch can be filled up to the threshold without blocking
            addLeaves(flusher, 100, INTERVAL).await();
            // One more producer blocks
            final Producer blocked = addLeaves(flusher, 200, 1);
            blocked.awaitBlocked();
            assertEquals(1, ds.saves.get());
            ds.open();
            blocked.await();
            flusher.finish();
            // The first batch, the next batch, and the final flush
            assertEquals(3, ds.saves.get());
            assertLeavesPresent(ds, 1, INTERVAL);
            assertLeavesPresent(ds, 100, INTERVAL);
            assertLeavesPresent(ds, 200, 1);
        }

        @Test
        void testDueBatchFlushedByNextProducer() throws Exception {
            final GatedDataSource ds = gatedDataSource("dueBatchNextProducer");
            final HashLeafFlusher flusher = flusher(ds, INTERVAL);
            startBlockedFlush(flusher, ds);
            // The next batch becomes due while the first flush is running
            addLeaves(flusher, 100, INTERVAL).await();
            ds.open();
            // The next call, once the first flush is complete, submits the due batch, and adds to a new one
            addLeaves(flusher, 200, 1).await();
            // Only confirms that the flush has started, so its leaves are checked after finish(), which waits for it
            awaitSaves(ds, 2);
            flusher.finish();
            // The first batch, the due batch, and the final flush
            assertEquals(3, ds.saves.get());
            assertLeavesPresent(ds, 100, INTERVAL);
            assertLeavesPresent(ds, 200, 1);
        }

        @Test
        void testDueBatchFlushedInFinish() throws Exception {
            final GatedDataSource ds = gatedDataSource("dueBatchFinish");
            final HashLeafFlusher flusher = flusher(ds, INTERVAL);
            startBlockedFlush(flusher, ds);
            // The next batch becomes due while the first flush is running. No more producer calls after that
            addLeaves(flusher, 100, INTERVAL).await();
            ds.open();
            // finish() waits for the first flush, then flushes the due batch in the final flush
            flusher.finish();
            assertEquals(2, ds.saves.get());
            assertTrue(ds.saveThreads.contains(Thread.currentThread()), "The final flush must run in finish()");
            assertLeavesPresent(ds, 1, INTERVAL);
            assertLeavesPresent(ds, 100, INTERVAL);
        }

        @Test
        void testFinishWaitsForRunningFlush() throws Exception {
            final GatedDataSource ds = gatedDataSource("finishWaits");
            final HashLeafFlusher flusher = flusher(ds, INTERVAL);
            startBlockedFlush(flusher, ds);
            // Not due, left for the final flush
            flusher.updateLeaf(leaf(100, 100, 100));
            final Producer finish = start(flusher::finish);
            // The final flush isn't started, till the running flush is complete
            finish.awaitBlocked();
            assertEquals(1, ds.saves.get());
            ds.open();
            finish.await();
            assertEquals(2, ds.saves.get());
            assertTrue(ds.saveThreads.contains(finish.thread()), "The final flush must run in finish()");
            assertLeavesPresent(ds, 1, INTERVAL);
            assertLeavesPresent(ds, 100, 1);
        }

        @Test
        void testLastWriteWinsAcrossBatches() throws Exception {
            final GatedDataSource ds = gatedDataSource("lastWriteWins");
            final HashLeafFlusher flusher = flusher(ds, INTERVAL);
            // The first batch: leaves 1 to INTERVAL, values equal to paths
            startBlockedFlush(flusher, ds);
            // The second batch, due while the first flush is running: the same leaves with new values
            start(() -> {
                        for (int i = 1; i <= INTERVAL; i++) {
                            flusher.updateLeaf(leaf(i, i, i + 1000));
                        }
                    })
                    .await();
            // The final batch: the first leaf with yet another value. Blocks till the first flush is complete
            final Producer last = start(() -> flusher.updateLeaf(leaf(1, 1, 2000)));
            last.awaitBlocked();
            ds.open();
            last.await();
            flusher.finish();
            assertEquals(3, ds.saves.get());
            // Batches are flushed in the order they are filled
            assertEquals(leaf(1, 1, 2000), ds.loadLeafRecord(1));
            for (int i = 2; i <= INTERVAL; i++) {
                assertEquals(leaf(i, i, i + 1000), ds.loadLeafRecord(i), "Leaf at path " + i);
            }
        }

        @Test
        void testNextBatchDueBlocksAllProducerKinds() throws Exception {
            final GatedDataSource ds = gatedDataSource("crossCategory");
            final HashLeafFlusher flusher = flusher(ds, INTERVAL);
            final int chunkHeight = ds.getHashChunkHeight();
            startBlockedFlush(flusher, ds);
            // The next batch is due because of updated leaves only
            addLeaves(flusher, 100, INTERVAL).await();
            final Producer delete = start(() -> flusher.deleteLeaf(leaf(300, 300, 300)));
            final Producer hashes = start(() -> flusher.updateHashChunk(chunk(0, chunkHeight)));
            delete.awaitBlocked();
            hashes.awaitBlocked();
            ds.open();
            delete.await();
            hashes.await();
            flusher.finish();
            assertNotNull(ds.loadHashChunk(0));
        }

        @Test
        void testHashChunkBackpressureCountsHashSlots() throws Exception {
            final GatedDataSource ds = gatedDataSource("hashSlots");
            final int chunkHeight = ds.getHashChunkHeight();
            // A flush is due once two chunks are collected
            final HashLeafFlusher flusher = flusher(ds, 2 * VirtualHashChunk.getChunkSize(chunkHeight));
            final Runnable addTwoChunks = () -> {
                flusher.updateHashChunk(chunk(0, chunkHeight));
                flusher.updateHashChunk(chunk(1, chunkHeight));
            };
            start(addTwoChunks).await();
            awaitSaves(ds, 1);
            // The next batch can be filled with two chunks without blocking
            start(addTwoChunks).await();
            final Producer blocked = start(() -> flusher.updateHashChunk(chunk(2, chunkHeight)));
            blocked.awaitBlocked();
            ds.open();
            blocked.await();
            flusher.finish();
            assertNotNull(ds.loadHashChunk(2));
        }

        @Test
        void testNoBackpressureWithZeroInterval() throws Exception {
            final GatedDataSource ds = gatedDataSource("zeroInterval");
            final HashLeafFlusher flusher = flusher(ds, 0);
            // All data is kept in memory till finish(), producers never block
            addLeaves(flusher, 1, 100).await();
            addLeaves(flusher, 101, 100).await();
            assertEquals(0, ds.saves.get());
            ds.open();
            flusher.finish();
            assertEquals(1, ds.saves.get());
            assertLeavesPresent(ds, 1, 200);
        }

        @Test
        void testFlushFailureReleasesBlockedProducers() throws Exception {
            final GatedDataSource ds = gatedDataSource("failure");
            final RuntimeException failure = new RuntimeException("disk failure");
            ds.failWith = failure;
            final HashLeafFlusher flusher = flusher(ds, INTERVAL);
            startBlockedFlush(flusher, ds);
            addLeaves(flusher, 100, INTERVAL).await();
            final Producer blocked = addLeaves(flusher, 200, 1);
            blocked.awaitBlocked();
            ds.open();
            // The blocked producer is released with the original exception as the cause
            final ExecutionException released = assertThrows(ExecutionException.class, blocked::await);
            assertTrue(released.getCause() instanceof IllegalStateException);
            assertSame(failure, released.getCause().getCause());
            // The failure is sticky: no new flushes are started, all subsequent calls fail
            final IllegalStateException update =
                    assertThrows(IllegalStateException.class, () -> flusher.updateLeaf(leaf(300, 300, 300)));
            assertSame(failure, update.getCause());
            final IllegalStateException finish = assertThrows(IllegalStateException.class, flusher::finish);
            assertSame(failure, finish.getCause());
            assertEquals(1, ds.saves.get());
        }

        @Test
        void testFinishThrowsFailureOfRunningFlush() throws Exception {
            final GatedDataSource ds = gatedDataSource("finishFailure");
            final RuntimeException failure = new RuntimeException("disk failure");
            ds.failWith = failure;
            final HashLeafFlusher flusher = flusher(ds, INTERVAL);
            startBlockedFlush(flusher, ds);
            // finish() waits for the running flush, which fails, so the final flush is never run
            final Producer finish = start(flusher::finish);
            finish.awaitBlocked();
            ds.open();
            final ExecutionException e = assertThrows(ExecutionException.class, finish::await);
            assertInstanceOf(IllegalStateException.class, e.getCause());
            assertSame(failure, e.getCause().getCause());
            assertEquals(1, ds.saves.get());
        }

        @Test
        void testFlushFailureWithoutWaiters() throws Exception {
            final GatedDataSource ds = gatedDataSource("failureNoWaiters");
            final RuntimeException failure = new RuntimeException("disk failure");
            ds.failWith = failure;
            final HashLeafFlusher flusher = flusher(ds, INTERVAL);
            startBlockedFlush(flusher, ds);
            // One leaf short of a due batch, so the next call makes it due, while the flush is failing
            addLeaves(flusher, 100, INTERVAL - 1).await();
            ds.open();
            // Calls may succeed till the failed flush is complete, but they never start a new flush
            final AtomicInteger path = new AtomicInteger(200);
            final AtomicReference<IllegalStateException> update = new AtomicReference<>();
            assertEventuallyTrue(
                    () -> {
                        try {
                            final int p = path.getAndIncrement();
                            flusher.updateLeaf(leaf(p, p, p));
                            return false;
                        } catch (final IllegalStateException e) {
                            update.set(e);
                            return true;
                        }
                    },
                    Duration.ofSeconds(10),
                    "Calls after a failed flush must fail");
            assertSame(failure, update.get().getCause());
            final IllegalStateException finish = assertThrows(IllegalStateException.class, flusher::finish);
            assertSame(failure, finish.getCause());
            assertEquals(1, ds.saves.get());
        }

        @Test
        void testCancelReleasesFinishWaitingForRunningFlush() throws Exception {
            final GatedDataSource ds = gatedDataSource("cancelFinish");
            final HashLeafFlusher flusher = flusher(ds, INTERVAL);
            startBlockedFlush(flusher, ds);
            // Not due, left for the final flush
            flusher.updateLeaf(leaf(100, 100, 100));
            final Producer finish = start(flusher::finish);
            // finish() waits for the running flush
            finish.awaitBlocked();
            flusher.cancel();
            // Released without waiting for the running flush
            final ExecutionException e = assertThrows(ExecutionException.class, finish::await);
            assertInstanceOf(IllegalStateException.class, e.getCause());
            assertInstanceOf(CancellationException.class, e.getCause().getCause());
            // The running flush isn't interrupted and completes normally, then the flusher thread exits
            ds.open();
            assertTrue(flusher.awaitTermination(Duration.ofSeconds(10)), "Flusher thread must exit after cancel()");
            assertLeavesPresent(ds, 1, INTERVAL);
            // The final flush is never run
            assertEquals(1, ds.saves.get());
        }

        @Test
        void testInterruptWhileFinishWaitsForRunningFlush() throws Exception {
            final GatedDataSource ds = gatedDataSource("interruptFinish");
            final HashLeafFlusher flusher = flusher(ds, INTERVAL);
            startBlockedFlush(flusher, ds);
            // Not due, left for the final flush
            flusher.updateLeaf(leaf(100, 100, 100));
            final CompletableFuture<Boolean> interruptedFlag = new CompletableFuture<>();
            final Producer finish = start(() -> {
                try {
                    flusher.finish();
                } finally {
                    interruptedFlag.complete(Thread.currentThread().isInterrupted());
                }
            });
            // finish() waits for the running flush
            finish.awaitBlocked();
            finish.thread().interrupt();
            final ExecutionException e = assertThrows(ExecutionException.class, finish::await);
            assertInstanceOf(IllegalStateException.class, e.getCause());
            assertInstanceOf(InterruptedException.class, e.getCause().getCause());
            assertTrue(interruptedFlag.get(10, SECONDS), "Interrupted flag must be preserved");
            // The running flush isn't interrupted and completes normally, then the flusher thread exits
            ds.open();
            assertTrue(flusher.awaitTermination(Duration.ofSeconds(10)), "Flusher thread must exit after finish()");
            assertLeavesPresent(ds, 1, INTERVAL);
            // The final flush is never run, and the flusher can't be finished again
            assertEquals(1, ds.saves.get());
            assertThrows(IllegalStateException.class, flusher::finish);
        }

        @Test
        void testInterruptWhileBlocked() throws Exception {
            final GatedDataSource ds = gatedDataSource("interruptBlocked");
            final HashLeafFlusher flusher = flusher(ds, INTERVAL);
            startBlockedFlush(flusher, ds);
            addLeaves(flusher, 100, INTERVAL).await();
            final CompletableFuture<Boolean> interruptedFlag = new CompletableFuture<>();
            final Producer blocked = start(() -> {
                try {
                    flusher.updateLeaf(leaf(200, 200, 200));
                } finally {
                    interruptedFlag.complete(Thread.currentThread().isInterrupted());
                }
            });
            blocked.awaitBlocked();
            blocked.thread().interrupt();
            final ExecutionException e = assertThrows(ExecutionException.class, blocked::await);
            assertInstanceOf(IllegalStateException.class, e.getCause());
            assertTrue(interruptedFlag.get(10, SECONDS), "Interrupted flag must be preserved");
            // Interrupting a waiting producer isn't a flush failure, the flusher remains usable
            ds.open();
            flusher.finish();
            assertLeavesPresent(ds, 1, INTERVAL);
            assertLeavesPresent(ds, 100, INTERVAL);
        }

        // A producer that is never interrupted (in reconnect, the hasher or the leaf-deleter) waits for the running
        // flush, and another producer (a reconnect receiver) blocks too. An interrupt must release the second one
        // while the first one still waits, since reconnect only cancels the flusher after its receivers have exited
        @Test
        void testInterruptReleasesProducerWhileAnotherWaits() throws Exception {
            final GatedDataSource ds = gatedDataSource("interruptSecondWaiter");
            final HashLeafFlusher flusher = flusher(ds, INTERVAL);
            startBlockedFlush(flusher, ds);
            addLeaves(flusher, 100, INTERVAL).await();
            final Producer notInterrupted = addLeaves(flusher, 200, 1);
            notInterrupted.awaitBlocked();
            final CompletableFuture<Boolean> interruptedFlag = new CompletableFuture<>();
            final Producer interrupted = start(() -> {
                try {
                    flusher.updateLeaf(leaf(300, 300, 300));
                } finally {
                    interruptedFlag.complete(Thread.currentThread().isInterrupted());
                }
            });
            // Waiting either for the flush or for the lock, both must be released by an interrupt
            assertEventuallyTrue(
                    () -> switch (interrupted.thread().getState()) {
                        case BLOCKED, WAITING, TIMED_WAITING -> true;
                        default -> false;
                    },
                    Duration.ofSeconds(10),
                    "Producer didn't block in time");
            interrupted.thread().interrupt();
            final ExecutionException e = assertThrows(
                    ExecutionException.class,
                    interrupted::await,
                    "An interrupt must release a producer, while another producer waits for the running flush");
            assertInstanceOf(IllegalStateException.class, e.getCause());
            assertTrue(interruptedFlag.get(10, SECONDS), "Interrupted flag must be preserved");
            // The producer that isn't interrupted still waits, till the flusher is cancelled
            assertFalse(notInterrupted.result().isDone(), "Producer that isn't interrupted must still wait");
            flusher.cancel();
            final ExecutionException released = assertThrows(ExecutionException.class, notInterrupted::await);
            assertInstanceOf(IllegalStateException.class, released.getCause());
            assertInstanceOf(CancellationException.class, released.getCause().getCause());
            ds.open();
            assertTrue(flusher.awaitTermination(Duration.ofSeconds(10)), "Flusher thread must exit after cancel()");
        }

        @Test
        void testCancelReleasesBlockedProducers() throws Exception {
            final GatedDataSource ds = gatedDataSource("cancel");
            final HashLeafFlusher flusher = flusher(ds, INTERVAL);
            startBlockedFlush(flusher, ds);
            addLeaves(flusher, 100, INTERVAL).await();
            final Producer blocked = addLeaves(flusher, 200, 1);
            blocked.awaitBlocked();
            flusher.cancel();
            // Released without waiting for the running flush
            final ExecutionException released = assertThrows(ExecutionException.class, blocked::await);
            assertInstanceOf(IllegalStateException.class, released.getCause());
            assertInstanceOf(CancellationException.class, released.getCause().getCause());
            // The running flush isn't interrupted and completes normally, then the flusher thread exits
            ds.open();
            assertTrue(flusher.awaitTermination(Duration.ofSeconds(10)), "Flusher thread must exit after cancel()");
            assertLeavesPresent(ds, 1, INTERVAL);
            // No new flushes are started, all subsequent calls fail
            assertThrows(IllegalStateException.class, () -> flusher.updateLeaf(leaf(300, 300, 300)));
            final IllegalStateException finish = assertThrows(IllegalStateException.class, flusher::finish);
            assertInstanceOf(CancellationException.class, finish.getCause());
            assertEquals(1, ds.saves.get());
        }

        @Test
        void testAwaitTerminationWaitsForRunningFlushAfterCancel() throws Exception {
            final GatedDataSource ds = gatedDataSource("awaitTermination");
            final HashLeafFlusher flusher = flusher(ds, INTERVAL);
            startBlockedFlush(flusher, ds);
            flusher.cancel();
            // The running flush still writes to the data source, so the flusher thread doesn't exit yet
            assertFalse(
                    flusher.awaitTermination(Duration.ofMillis(200)),
                    "Flusher thread must not exit while a flush is running");
            ds.open();
            assertTrue(
                    flusher.awaitTermination(Duration.ofSeconds(10)),
                    "Flusher thread must exit once the running flush is complete");
            assertLeavesPresent(ds, 1, INTERVAL);
            assertEquals(1, ds.saves.get());
        }

        @Test
        void testCancelDuringFinalFlushDoesNotBlock() throws Exception {
            final GatedDataSource ds = gatedDataSource("cancelFinalFlush");
            final HashLeafFlusher flusher = flusher(ds, INTERVAL);
            flusher.updateLeaf(leaf(1, 1, 1));
            final Producer finish = start(flusher::finish);
            // finish() stops the idle flusher thread, then blocks in its own final flush
            awaitSaves(ds, 1);
            assertTrue(ds.saveThreads.contains(finish.thread()), "The final flush must run in finish()");
            // Must not wait for the final flush to complete
            start(flusher::cancel).await();
            // The running flush isn't interrupted and completes normally
            ds.open();
            finish.await();
            assertEquals(1, ds.saves.get());
            assertLeavesPresent(ds, 1, 1);
        }

        @Test
        void testConcurrentProducersBatchSizeBounded() throws Exception {
            final GatedDataSource ds = gatedDataSource("concurrent");
            // Flushes are slower than producers, so producers are throttled
            ds.delayMs = 2;
            ds.open();
            final HashLeafFlusher flusher = flusher(ds, INTERVAL);
            final int producerCount = 8;
            final int perProducer = 200;
            final List<Producer> started = new ArrayList<>();
            for (int p = 0; p < producerCount; p++) {
                started.add(addLeaves(flusher, 1 + p * perProducer, perProducer));
            }
            for (final Producer producer : started) {
                producer.await();
            }
            flusher.finish();
            // Producers never add to a batch that is already due
            assertTrue(
                    ds.maxLeavesPerSave.get() <= INTERVAL,
                    "Batch size must be bounded, but was " + ds.maxLeavesPerSave.get());
            assertLeavesPresent(ds, 1, producerCount * perProducer);
        }

        @Test
        void testForkJoinProducersDoNotDeadlock() throws Exception {
            // Reproduces the reconnect and full rehash setup: producers are fork join tasks, and every flush waits
            // for a fork join task in a different pool, like MerkleDB does in HalfDiskHashMap.endWriting(). If a
            // flush ran on a producer worker, the worker could run another producer task while waiting (FJ helping),
            // and that task could block on the flush its own thread is running
            final ForkJoinPool producerPool = new ForkJoinPool(4);
            final ForkJoinPool dataSourcePool = new ForkJoinPool(2);
            try {
                for (int iteration = 0; iteration < 5; iteration++) {
                    final GatedDataSource ds = gatedDataSource("forkJoin" + iteration);
                    ds.open();
                    ds.onSave = () -> dataSourcePool
                            .submit(() -> {
                                try {
                                    Thread.sleep(1);
                                } catch (final InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                }
                            })
                            .join();
                    final HashLeafFlusher flusher = flusher(ds, INTERVAL);
                    final int count = 2_000;
                    producerPool.submit(new AddLeavesTask(flusher, 1, count)).get(60, SECONDS);
                    flusher.finish();
                    assertLeavesPresent(ds, 1, count);
                    // Flushes run on the flusher thread, and the final one in the thread that calls finish(),
                    // never on producer workers
                    assertEquals(2, ds.saveThreads.size(), "Flushes must run on two threads");
                    assertTrue(ds.saveThreads.contains(Thread.currentThread()), "The final flush must run in finish()");
                    assertTrue(
                            ds.saveThreads.stream().noneMatch(ForkJoinWorkerThread.class::isInstance),
                            "Flushes must not run on producer workers");
                }
            } finally {
                producerPool.shutdownNow();
                dataSourcePool.shutdownNow();
            }
        }

        /** Adds leaves in a fork join pool, splitting the range into subtasks, like the hasher does. */
        private static class AddLeavesTask extends RecursiveAction {
            private final HashLeafFlusher flusher;
            private final int from;
            private final int count;

            AddLeavesTask(final HashLeafFlusher flusher, final int from, final int count) {
                this.flusher = flusher;
                this.from = from;
                this.count = count;
            }

            @Override
            protected void compute() {
                if (count <= 4) {
                    for (int i = from; i < from + count; i++) {
                        flusher.updateLeaf(leaf(i, i, i));
                    }
                } else {
                    final int half = count / 2;
                    invokeAll(
                            new AddLeavesTask(flusher, from, half),
                            new AddLeavesTask(flusher, from + half, count - half));
                }
            }
        }

        private static VirtualHashChunk chunk(final long chunkId, final int chunkHeight) {
            final long chunkPath = VirtualHashChunk.chunkIdToChunkPath(chunkId, chunkHeight);
            final VirtualHashChunk chunk = new VirtualHashChunk(chunkPath, chunkHeight);
            for (int j = 0; j < chunk.getChunkSize(); j++) {
                final long path = VirtualHashChunk.getPathInChunk(j, chunkPath, chunkHeight);
                chunk.setHashAtPath(path, hash((int) (path + 2)));
            }
            return chunk;
        }
    }

    private static Hash hash(final int h) {
        final int len = Cryptography.DEFAULT_DIGEST_TYPE.digestLength();
        final byte[] bytes = new byte[len];
        final ByteBuffer buf = ByteBuffer.wrap(bytes);
        for (int i = 0; i < len; i += Integer.BYTES) { // assuming len % Integer.BYTES == 0
            buf.putInt(h);
        }
        return new Hash(bytes, Cryptography.DEFAULT_DIGEST_TYPE);
    }

    private static VirtualLeafBytes leaf(final int path, final int k, final int v) {
        return new VirtualLeafBytes<>(path, longToKey(k), new TestValue(v).toBytes());
    }
}
