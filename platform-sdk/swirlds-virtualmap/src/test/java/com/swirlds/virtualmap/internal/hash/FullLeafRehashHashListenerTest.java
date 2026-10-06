// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal.hash;

import static com.swirlds.virtualmap.test.fixtures.VirtualMapTestUtils.hash;
import static com.swirlds.virtualmap.test.fixtures.VirtualMapTestUtils.loadHash;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
import java.util.concurrent.ForkJoinPool;
import java.util.function.LongFunction;
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
        listener = new FullLeafRehashHashListener(1, 1000000, dataSource, statistics, flushInterval);
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
        listener.onHashingStarted(1, (long) chunksToFlush * chunkSize);
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

        // At least one flush should have happened by now for the first 500,000 records.
        assertNotNull(loadHash(dataSource, 1, hashChunkHeight), "First record should be flushed by interval");
        final long toCheck = MerklePathUtils.getLeftChildPath(
                VirtualHashChunk.chunkIdToChunkPath(chunksToFlush - 1, hashChunkHeight));
        assertNotNull(loadHash(dataSource, toCheck, hashChunkHeight), "500,000th record should be flushed by interval");
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
        final FullLeafRehashHashListener chunkListener = new FullLeafRehashHashListener(
                firstLeafPath, lastLeafPath, dataSource, new VirtualMapStatistics("test"), 1);
        final HashChunkCollector collector = new HashChunkCollector(hashChunkHeight, chunkListener);
        final ForkJoinPool pool = new ForkJoinPool(8);
        final Hash rootHash;
        try {
            collector.onHashingStarted(firstLeafPath, lastLeafPath);
            rootHash = new TaskPerNodeFullRehasher(pool).hash(firstLeafPath, lastLeafPath, reader, collector, 60_000);
            collector.onHashingCompleted();
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
                new FullLeafRehashHashListener(999, 1998, dataSource, new VirtualMapStatistics("test"), 1);
        final HashChunkCollector collector = new HashChunkCollector(dataSource.getHashChunkHeight(), chunkListener);
        // Closed data source throws an IOException on save
        dataSource.close();
        final ForkJoinPool pool = new ForkJoinPool(4);
        try {
            collector.onHashingStarted(999, 1998);
            final RuntimeException e = assertThrows(RuntimeException.class, () -> new TaskPerNodeFullRehasher(pool)
                    .hash(999, 1998, reader, collector, 60_000));
            assertInstanceOf(UncheckedIOException.class, e.getCause());
        } finally {
            pool.shutdownNow();
        }
    }
}
