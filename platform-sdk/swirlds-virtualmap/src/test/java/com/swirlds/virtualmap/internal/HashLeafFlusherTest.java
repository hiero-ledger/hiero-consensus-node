// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal;

import static com.swirlds.logging.legacy.LogMarker.VIRTUAL_MERKLE_STATS;
import static com.swirlds.virtualmap.test.fixtures.TestKey.longToKey;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hiero.base.utility.test.fixtures.assertions.AssertionUtils.assertEventuallyTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
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

    @Test
    void testInterruptedFlushFails() {
        // Simulates MerkleDbDataSource, which restores the interrupted flag and returns normally
        final InMemoryDataSource ds = new InMemoryDataSource("testInterruptedFlushFails") {
            @Override
            @SuppressWarnings("rawtypes")
            public void saveRecords(
                    final long firstLeafPath,
                    final long lastLeafPath,
                    final Stream<VirtualHashChunk> hashChunksToUpdate,
                    final Stream<VirtualLeafBytes> leafRecordsToAddOrUpdate,
                    final Stream<VirtualLeafBytes> leafRecordsToDelete,
                    final boolean isReconnectContext)
                    throws java.io.IOException {
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
        final HashLeafFlusher flusher =
                new HashLeafFlusher(ds, 0, new VirtualMapStatistics("testInterruptedFlushFails"));
        flusher.init(1, 2);
        try {
            assertThrows(IllegalStateException.class, flusher::finish);
            assertTrue(Thread.currentThread().isInterrupted(), "Interrupted flag must be preserved");
        } finally {
            Thread.interrupted();
        }
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

        // Gated data sources and producers created by a test, released and joined after the test, even if it fails
        private final List<GatedDataSource> gatedSources = new ArrayList<>();
        private final List<Producer> producers = new ArrayList<>();

        @AfterEach
        void releaseBlockedThreads() throws InterruptedException {
            gatedSources.forEach(GatedDataSource::open);
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
            volatile boolean gated = true;
            volatile long delayMs = 0;
            volatile RuntimeException failWith;

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

            // Waits till the thread is blocked, either in the flusher or in a gated flush. Stops waiting
            // early, if the thread completes instead
            void awaitBlocked() {
                assertEventuallyTrue(
                        () -> result.isDone() || thread.getState() == Thread.State.WAITING,
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

        private static HashLeafFlusher flusher(final VirtualDataSource ds, final int flushInterval) {
            final HashLeafFlusher flusher =
                    new HashLeafFlusher(ds, flushInterval, new VirtualMapStatistics("backpressure"));
            flusher.init(1, 10_000);
            return flusher;
        }

        // Starts a flush of leaves 1 to INTERVAL that blocks in the data source
        private Producer startBlockedFlush(final HashLeafFlusher flusher, final GatedDataSource ds) throws Exception {
            final Producer first = addLeaves(flusher, 1, INTERVAL);
            // No other flush is running, so the first producer may only block in its own flush
            first.awaitBlocked();
            assertEquals(1, ds.saves.get());
            return first;
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
            final Producer first = startBlockedFlush(flusher, ds);
            // The next batch can be filled up to the threshold without blocking
            addLeaves(flusher, 100, INTERVAL).await();
            // One more producer blocks
            final Producer blocked = addLeaves(flusher, 200, 1);
            blocked.awaitBlocked();
            assertEquals(1, ds.saves.get());
            ds.open();
            first.await();
            blocked.await();
            flusher.finish();
            // The first batch, the next batch flushed by the released producer, and the final flush
            assertEquals(3, ds.saves.get());
            assertLeavesPresent(ds, 1, INTERVAL);
            assertLeavesPresent(ds, 100, INTERVAL);
            assertLeavesPresent(ds, 200, 1);
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
            final Producer first = start(addTwoChunks);
            first.awaitBlocked();
            assertEquals(1, ds.saves.get());
            // The next batch can be filled with two chunks without blocking
            start(addTwoChunks).await();
            final Producer blocked = start(() -> flusher.updateHashChunk(chunk(2, chunkHeight)));
            blocked.awaitBlocked();
            ds.open();
            first.await();
            blocked.await();
            flusher.finish();
            assertNotNull(ds.loadHashChunk(2));
        }

        @Test
        void testNoBackpressureWithZeroInterval() throws Exception {
            final GatedDataSource ds = gatedDataSource("zeroInterval");
            final HashLeafFlusher flusher = flusher(ds, 0);
            // All data is kept in memory till finish(), producers never flush or block
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
            final Producer first = startBlockedFlush(flusher, ds);
            addLeaves(flusher, 100, INTERVAL).await();
            final Producer blocked = addLeaves(flusher, 200, 1);
            blocked.awaitBlocked();
            ds.open();
            // The original exception is thrown to the flushing thread
            final ExecutionException flushing = assertThrows(ExecutionException.class, first::await);
            assertSame(failure, flushing.getCause());
            // The blocked producer is released, and flushes the next batch, which is already due
            blocked.await();
            flusher.finish();
            assertEquals(3, ds.saves.get());
            assertLeavesPresent(ds, 100, INTERVAL);
            assertLeavesPresent(ds, 200, 1);
        }

        @Test
        void testInterruptWhileBlocked() throws Exception {
            final GatedDataSource ds = gatedDataSource("interruptBlocked");
            final HashLeafFlusher flusher = flusher(ds, INTERVAL);
            final Producer first = startBlockedFlush(flusher, ds);
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
            assertTrue(e.getCause() instanceof IllegalStateException);
            assertTrue(interruptedFlag.get(10, SECONDS), "Interrupted flag must be preserved");
            // Interrupting a waiting producer isn't a flush failure, the flusher remains usable
            ds.open();
            first.await();
            flusher.finish();
            assertLeavesPresent(ds, 1, INTERVAL);
            assertLeavesPresent(ds, 100, INTERVAL);
        }

        @Test
        void testInterruptBeforeFlushFails() throws Exception {
            final GatedDataSource ds = gatedDataSource("interruptFlush");
            ds.open();
            final HashLeafFlusher flusher = flusher(ds, INTERVAL);
            final CompletableFuture<Boolean> interruptedFlag = new CompletableFuture<>();
            final Producer producer = start(() -> {
                try {
                    for (int i = 1; i < INTERVAL; i++) {
                        flusher.updateLeaf(leaf(i, i, i));
                    }
                    Thread.currentThread().interrupt();
                    // Triggers a flush, which fails because of the interrupt
                    flusher.updateLeaf(leaf(INTERVAL, INTERVAL, INTERVAL));
                } finally {
                    interruptedFlag.complete(Thread.currentThread().isInterrupted());
                }
            });
            final ExecutionException e = assertThrows(ExecutionException.class, producer::await);
            assertTrue(e.getCause() instanceof IllegalStateException);
            assertTrue(interruptedFlag.get(10, SECONDS), "Interrupted flag must be preserved");
            assertEquals(0, ds.saves.get());
        }

        @Test
        void testCancelReleasesBlockedProducers() throws Exception {
            final GatedDataSource ds = gatedDataSource("cancel");
            final HashLeafFlusher flusher = flusher(ds, INTERVAL);
            final Producer first = startBlockedFlush(flusher, ds);
            addLeaves(flusher, 100, INTERVAL).await();
            final Producer blocked = addLeaves(flusher, 200, 1);
            blocked.awaitBlocked();
            flusher.cancel();
            // Released without waiting for the running flush
            final ExecutionException released = assertThrows(ExecutionException.class, blocked::await);
            assertTrue(released.getCause() instanceof IllegalStateException);
            assertTrue(released.getCause().getCause() instanceof CancellationException);
            // The running flush isn't interrupted and completes normally
            ds.open();
            first.await();
            // No new flushes are started, all subsequent calls fail
            assertThrows(IllegalStateException.class, () -> flusher.updateLeaf(leaf(300, 300, 300)));
            final IllegalStateException finish = assertThrows(IllegalStateException.class, flusher::finish);
            assertTrue(finish.getCause() instanceof CancellationException);
            assertEquals(1, ds.saves.get());
        }

        @Test
        void testCancelDuringFinalFlushDoesNotBlock() throws Exception {
            final GatedDataSource ds = gatedDataSource("cancelFinalFlush");
            final HashLeafFlusher flusher = flusher(ds, INTERVAL);
            flusher.updateLeaf(leaf(1, 1, 1));
            final Producer finish = start(flusher::finish);
            // No other flush is running, so finish() may only block in its own final flush
            finish.awaitBlocked();
            assertEquals(1, ds.saves.get());
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
            // The thread released after a flush adds one more leaf to the next batch, which is already due
            assertTrue(
                    ds.maxLeavesPerSave.get() <= INTERVAL + 1,
                    "Batch size must be bounded, but was " + ds.maxLeavesPerSave.get());
            assertLeavesPresent(ds, 1, producerCount * perProducer);
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
