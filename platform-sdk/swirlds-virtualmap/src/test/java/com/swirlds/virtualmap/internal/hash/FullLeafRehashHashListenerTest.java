// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal.hash;

import static com.swirlds.virtualmap.test.fixtures.VirtualMapTestUtils.hash;
import static com.swirlds.virtualmap.test.fixtures.VirtualMapTestUtils.loadHash;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hiero.base.utility.test.fixtures.assertions.AssertionUtils.assertEventuallyTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.virtualmap.MerklePathUtils;
import com.swirlds.virtualmap.TaskPerNodeFullRehasher;
import com.swirlds.virtualmap.VirtualTestBase;
import com.swirlds.virtualmap.datasource.VirtualHashChunk;
import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import com.swirlds.virtualmap.internal.VirtualMapStatistics;
import com.swirlds.virtualmap.test.fixtures.TestValue;
import com.swirlds.virtualmap.test.fixtures.datasource.InMemoryDataSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongFunction;
import java.util.stream.Stream;
import org.hiero.base.crypto.Cryptography;
import org.hiero.base.crypto.Hash;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FullLeafRehashHashListenerTest extends VirtualTestBase {

    private InMemoryDataSource dataSource;
    private FullLeafRehashHashListener listener;
    private final int flushInterval = 1000;

    @BeforeEach
    void setUp() {
        dataSource = new InMemoryDataSource("test");
        VirtualMapStatistics statistics = new VirtualMapStatistics("test");
        // Use a range that will allow us to test the flush interval
        listener = new FullLeafRehashHashListener(dataSource, statistics, flushInterval);
    }

    @Test
    @DisplayName("Test basic hashing lifecycle")
    void testBasicLifecycle() throws IOException {
        final int hashChunkHeight = dataSource.getHashChunkHeight();
        listener.onHashingStarted(1, 10);

        VirtualLeafBytes<TestValue> leaf1 = appleLeaf(1);
        Hash hash1 = hash(leaf1);

        final VirtualHashChunk chunk0 = new VirtualHashChunk(0, hashChunkHeight);
        chunk0.setHashAtPath(1, hash1);
        listener.onHashChunkHashed(chunk0);

        listener.onHashingCompleted();

        // Verify that the record was saved to the data source
        assertEquals(hash1, loadHash(dataSource, 1, hashChunkHeight), "Hash should be saved to data source");
    }

    @Test
    @DisplayName("Test multiple records and completion flush")
    void testMultipleRecords() throws IOException {
        final int hashChunkHeight = dataSource.getHashChunkHeight();
        listener.onHashingStarted(1, 2);

        VirtualLeafBytes<TestValue> leaf1 = appleLeaf(1);
        Hash hash1 = hash(leaf1);
        VirtualLeafBytes<TestValue> leaf2 = bananaLeaf(2);
        Hash hash2 = hash(leaf2);

        final VirtualHashChunk chunk0 = new VirtualHashChunk(0, hashChunkHeight);
        chunk0.setHashAtPath(2, hash2);
        chunk0.setHashAtPath(1, hash1);
        listener.onHashChunkHashed(chunk0);

        listener.onHashingCompleted();

        assertEquals(hash1, loadHash(dataSource, 1, hashChunkHeight));
        assertEquals(hash2, loadHash(dataSource, 2, hashChunkHeight));
    }

    @Test
    @DisplayName("Test flush when interval is reached")
    void testFlushInterval() throws IOException {
        final int hashChunkHeight = dataSource.getHashChunkHeight();
        final int chunkSize = VirtualHashChunk.getChunkSize(hashChunkHeight);
        // Let's try a number of hash chunks to trigger at least one intermediate flush.
        final int chunksToFlush = flushInterval / chunkSize;
        // Wide path range, so that all hashes are within the data source range
        listener.onHashingStarted(1, 1_000_000);
        for (int i = 0; i < chunksToFlush + 1; i++) {
            final long chunkPath = VirtualHashChunk.chunkIdToChunkPath(i, hashChunkHeight);
            final VirtualHashChunk chunk = new VirtualHashChunk(chunkPath, hashChunkHeight);
            for (int j = 0; j < chunkSize; j++) {
                final long path = chunk.getPath(j);
                VirtualLeafBytes<TestValue> leaf = leaf(path, path, path);
                chunk.setHashAtPath(path, hash(leaf));
            }
            listener.onHashChunkHashed(chunk);
        }

        // At least one flush should have happened for the first 500,000 records. Flushes run on the flusher
        // thread, so wait for it
        final long toCheck = MerklePathUtils.getLeftChildPath(
                VirtualHashChunk.chunkIdToChunkPath(chunksToFlush - 1, hashChunkHeight));
        assertEventuallyTrue(
                () -> loadHashUnchecked(toCheck, hashChunkHeight) != null,
                Duration.ofSeconds(10),
                "500,000th record should be flushed by interval");
        assertNotNull(loadHash(dataSource, 1, hashChunkHeight), "First record should be flushed by interval");
        assertNull(loadHash(dataSource, toCheck + 2, hashChunkHeight), "500,001st record should not be flushed yet");
        listener.onHashingCompleted();
        assertNotNull(
                loadHash(dataSource, toCheck + 2, hashChunkHeight), "500,001st record should be flushed on completion");
    }

    @ParameterizedTest(name = "leaves={0}")
    @ValueSource(ints = {1, 2, 3, 100, 5000})
    @DisplayName("Full rehash with flush interval 1 flushes chunks concurrently and stores all hashes")
    void fullRehashWithFlushIntervalOne(final int leafCount) throws IOException {
        final long firstLeafPath = leafCount == 1 ? 1 : leafCount - 1L;
        final long lastLeafPath = leafCount == 1 ? 1 : 2L * leafCount - 2;
        final LongFunction<VirtualLeafBytes<?>> reader = path -> leaf(path, path, path * 3);
        final int hashChunkHeight = dataSource.getHashChunkHeight();
        // Every chunk triggers a flush, unless another flush is in progress
        final FullLeafRehashHashListener chunkListener =
                new FullLeafRehashHashListener(dataSource, new VirtualMapStatistics("test"), 1);
        final HashChunkCollector collector =
                new HashChunkCollector(hashChunkHeight, firstLeafPath, lastLeafPath, chunkListener);
        final ForkJoinPool pool = new ForkJoinPool(8);
        final Hash rootHash;
        try {
            rootHash = TaskPerNodeFullRehasher.hash(pool, firstLeafPath, lastLeafPath, reader, collector, 60_000);
        } finally {
            pool.shutdownNow();
        }

        final byte[][] expected = referenceHashes(firstLeafPath, lastLeafPath, reader);
        assertEquals(new Hash(expected[0], Cryptography.DEFAULT_DIGEST_TYPE), rootHash, "Root hash mismatch");
        for (long path = 1; path <= lastLeafPath; path++) {
            assertEquals(
                    new Hash(expected[(int) path], Cryptography.DEFAULT_DIGEST_TYPE),
                    loadHash(dataSource, path, hashChunkHeight),
                    "Hash mismatch, path = " + path);
        }
    }

    @Test
    @DisplayName("Flush failure fails full rehash")
    void flushFailureFailsFullRehash() {
        final LongFunction<VirtualLeafBytes<?>> reader = path -> leaf(path, path, path);
        final FullLeafRehashHashListener chunkListener =
                new FullLeafRehashHashListener(dataSource, new VirtualMapStatistics("test"), 1);
        final HashChunkCollector collector =
                new HashChunkCollector(dataSource.getHashChunkHeight(), 999, 1998, chunkListener);
        // Closed data source throws an IOException on save
        dataSource.close();
        final ForkJoinPool pool = new ForkJoinPool(4);
        try {
            final RuntimeException e = assertThrows(
                    RuntimeException.class,
                    () -> TaskPerNodeFullRehasher.hash(pool, 999, 1998, reader, collector, 60_000));
            // Depending on timing, the failure is either thrown by the final flush directly, or by an earlier flush
            // in the flusher thread, then rethrown to hashing threads and onHashingCompleted() as the cause
            Throwable cause = e.getCause();
            while ((cause != null) && !(cause instanceof UncheckedIOException)) {
                cause = cause.getCause();
            }
            assertNotNull(cause, "The flush failure must be the cause");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("Interrupt during flush fails the flush")
    void interruptDuringFlushFailsFlush() {
        // Simulates MerkleDbDataSource, which restores the interrupted flag and returns normally
        final InterruptingDataSource interruptingDataSource = new InterruptingDataSource();
        final FullLeafRehashHashListener chunkListener =
                new FullLeafRehashHashListener(interruptingDataSource, new VirtualMapStatistics("test"), flushInterval);
        chunkListener.onHashingStarted(1, 2);
        chunkListener.onHashChunkHashed(new VirtualHashChunk(0, interruptingDataSource.getHashChunkHeight()));
        try {
            assertThrows(IllegalStateException.class, chunkListener::onHashingCompleted);
            assertTrue(Thread.currentThread().isInterrupted(), "Interrupted flag must be preserved");
            assertEquals(1, interruptingDataSource.saveCount.get(), "Records must be saved once");
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    @DisplayName("Flush is not started if the thread is interrupted")
    void flushNotStartedIfInterrupted() {
        final InterruptingDataSource interruptingDataSource = new InterruptingDataSource();
        final FullLeafRehashHashListener chunkListener =
                new FullLeafRehashHashListener(interruptingDataSource, new VirtualMapStatistics("test"), flushInterval);
        chunkListener.onHashingStarted(1, 2);
        Thread.currentThread().interrupt();
        try {
            assertThrows(IllegalStateException.class, chunkListener::onHashingCompleted);
            assertTrue(Thread.currentThread().isInterrupted(), "Interrupted flag must be preserved");
            assertEquals(0, interruptingDataSource.saveCount.get(), "Records must not be saved");
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    @DisplayName("Interrupt during final flush fails full rehash")
    void interruptDuringFinalFlushFailsFullRehash() {
        final LongFunction<VirtualLeafBytes<?>> reader = path -> leaf(path, path, path);
        final InterruptingDataSource interruptingDataSource = new InterruptingDataSource();
        // Large flush interval, so the final flush is the only flush
        final FullLeafRehashHashListener chunkListener = new FullLeafRehashHashListener(
                interruptingDataSource, new VirtualMapStatistics("test"), Integer.MAX_VALUE);
        final HashChunkCollector collector =
                new HashChunkCollector(interruptingDataSource.getHashChunkHeight(), 999, 1998, chunkListener);
        final ForkJoinPool pool = new ForkJoinPool(4);
        try {
            final RuntimeException e = assertThrows(
                    RuntimeException.class,
                    () -> TaskPerNodeFullRehasher.hash(pool, 999, 1998, reader, collector, 60_000));
            assertInstanceOf(IllegalStateException.class, e.getCause());
            assertEquals(1, interruptingDataSource.saveCount.get(), "Only the final flush must be started");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("Cancel releases hashing threads blocked on backpressure")
    void cancelReleasesBlockedHashingThreads() throws Exception {
        final CountDownLatch saveStarted = new CountDownLatch(1);
        final CountDownLatch gate = new CountDownLatch(1);
        final InMemoryDataSource gatedDataSource = new InMemoryDataSource("gated") {
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
                saveStarted.countDown();
                try {
                    gate.await();
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                super.saveRecords(
                        firstLeafPath,
                        lastLeafPath,
                        hashChunksToUpdate,
                        leafRecordsToAddOrUpdate,
                        leafRecordsToDelete,
                        isReconnectContext);
            }
        };
        final int hashChunkHeight = gatedDataSource.getHashChunkHeight();
        // A flush is due for every chunk
        final FullLeafRehashHashListener chunkListener = new FullLeafRehashHashListener(
                gatedDataSource, new VirtualMapStatistics("test"), VirtualHashChunk.getChunkSize(hashChunkHeight));
        chunkListener.onHashingStarted(1, 1_000_000);
        try {
            // The first chunk is flushed, and the flush blocks in the data source. The second chunk fills the
            // next batch
            chunkListener.onHashChunkHashed(fullChunk(0, hashChunkHeight));
            assertTrue(saveStarted.await(10, SECONDS), "The first flush must start");
            chunkListener.onHashChunkHashed(fullChunk(1, hashChunkHeight));
            // The third chunk blocks till the running flush is complete
            final CompletableFuture<Void> result = new CompletableFuture<>();
            final Thread hashingThread = new Thread(() -> {
                try {
                    chunkListener.onHashChunkHashed(fullChunk(2, hashChunkHeight));
                    result.complete(null);
                } catch (final Throwable t) {
                    result.completeExceptionally(t);
                }
            });
            hashingThread.setDaemon(true);
            hashingThread.start();
            assertEventuallyTrue(
                    () -> result.isDone() || hashingThread.getState() == Thread.State.WAITING,
                    Duration.ofSeconds(10),
                    "Hashing thread didn't block in time");
            assertFalse(result.isDone(), "Hashing thread must block");
            chunkListener.cancel();
            // Released without waiting for the running flush
            final ExecutionException e = assertThrows(ExecutionException.class, () -> result.get(10, SECONDS));
            assertInstanceOf(IllegalStateException.class, e.getCause());
            assertInstanceOf(CancellationException.class, e.getCause().getCause());
            assertThrows(IllegalStateException.class, chunkListener::onHashingCompleted);
        } finally {
            gate.countDown();
        }
    }

    private VirtualHashChunk fullChunk(final long chunkId, final int hashChunkHeight) {
        final long chunkPath = VirtualHashChunk.chunkIdToChunkPath(chunkId, hashChunkHeight);
        final VirtualHashChunk chunk = new VirtualHashChunk(chunkPath, hashChunkHeight);
        for (int j = 0; j < chunk.getChunkSize(); j++) {
            final long path = chunk.getPath(j);
            chunk.setHashAtPath(path, hash(leaf(path, path, path)));
        }
        return chunk;
    }

    private Hash loadHashUnchecked(final long path, final int hashChunkHeight) {
        try {
            return loadHash(dataSource, path, hashChunkHeight);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /// A data source that interrupts the current thread on every save, like if the thread
    /// was interrupted while MerkleDbDataSource was waiting for data to be written.
    private static final class InterruptingDataSource extends InMemoryDataSource {

        final AtomicInteger saveCount = new AtomicInteger();

        InterruptingDataSource() {
            super("interrupting");
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
            saveCount.incrementAndGet();
            super.saveRecords(
                    firstLeafPath,
                    lastLeafPath,
                    hashChunksToUpdate,
                    leafRecordsToAddOrUpdate,
                    leafRecordsToDelete,
                    isReconnectContext);
            Thread.currentThread().interrupt();
        }
    }
}
