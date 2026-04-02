// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal.hash;

import static com.swirlds.virtualmap.test.fixtures.VirtualMapTestUtils.hash;
import static com.swirlds.virtualmap.test.fixtures.VirtualMapTestUtils.loadHash;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.swirlds.virtualmap.datasource.VirtualHashChunk;
import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import com.swirlds.virtualmap.internal.Path;
import com.swirlds.virtualmap.internal.merkle.VirtualMapStatistics;
import com.swirlds.virtualmap.test.fixtures.InMemoryDataSource;
import com.swirlds.virtualmap.test.fixtures.TestValue;
import com.swirlds.virtualmap.test.fixtures.VirtualTestBase;
import java.io.IOException;
import org.hiero.base.crypto.Hash;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
        final long toCheck =
                Path.getLeftChildPath(VirtualHashChunk.chunkIdToChunkPath(chunksToFlush - 1, hashChunkHeight));
        assertNotNull(loadHash(dataSource, toCheck, hashChunkHeight), "500,000th record should be flushed by interval");
        assertNull(loadHash(dataSource, toCheck + 2, hashChunkHeight), "500,001st record should not be flushed yet");
        listener.onHashingCompleted();
        assertNotNull(
                loadHash(dataSource, toCheck + 2, hashChunkHeight), "500,001st record should be flushed on completion");
    }
}
