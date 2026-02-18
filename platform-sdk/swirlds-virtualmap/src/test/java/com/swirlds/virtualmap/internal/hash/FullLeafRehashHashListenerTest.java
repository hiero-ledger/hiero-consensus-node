// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal.hash;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
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
    private VirtualMapStatistics statistics;
    private FullLeafRehashHashListener listener;
    private int flushInterval = 1000;

    @BeforeEach
    void setUp() {
        dataSource = new InMemoryDataSource("test");
        statistics = new VirtualMapStatistics("test");
        // Use a range that will allow us to test the flush interval
        listener = new FullLeafRehashHashListener(1, 1000000, dataSource, statistics, flushInterval);
    }

    @Test
    @DisplayName("Test basic hashing lifecycle")
    void testBasicLifecycle() throws IOException {
        listener.onHashingStarted(1, 10);

        VirtualLeafBytes<TestValue> leaf1 = appleLeaf(1);
        Hash hash1 = hash(leaf1);

        listener.onLeafHashed(leaf1);
        listener.onNodeHashed(1, hash1);

        listener.onHashingCompleted();

        // Verify that the record was saved to the data source
        assertEquals(hash1, dataSource.loadHash(1), "Hash should be saved to data source");
        assertEquals(leaf1, dataSource.loadLeafRecord(1), "Leaf should be saved to data source");
    }

    @Test
    @DisplayName("Test multiple records and completion flush")
    void testMultipleRecords() throws IOException {
        listener.onHashingStarted(1, 2);

        VirtualLeafBytes<TestValue> leaf1 = appleLeaf(1);
        Hash hash1 = hash(leaf1);
        VirtualLeafBytes<TestValue> leaf2 = bananaLeaf(2);
        Hash hash2 = hash(leaf2);

        listener.onLeafHashed(leaf1);
        listener.onNodeHashed(1, hash1);
        listener.onLeafHashed(leaf2);
        listener.onNodeHashed(2, hash2);

        listener.onHashingCompleted();

        assertEquals(hash1, dataSource.loadHash(1));
        assertEquals(leaf1, dataSource.loadLeafRecord(1));
        assertEquals(hash2, dataSource.loadHash(2));
        assertEquals(leaf2, dataSource.loadLeafRecord(2));
    }

    @Test
    @DisplayName("Test flush when interval is reached")
    void testFlushInterval() throws IOException {
        // Let's try 500,001 records to trigger at least one intermediate flush.
        int count = flushInterval + 1;
        listener.onHashingStarted(1, count);
        for (int i = 1; i <= count; i++) {
            VirtualLeafBytes<TestValue> leaf = leaf(i, i, i);
            listener.onLeafHashed(leaf);
            listener.onNodeHashed(i, hash(leaf));
        }

        // At least one flush should have happened by now for the first 500,000 records.
        assertNotNull(dataSource.loadHash(1), "First record should be flushed by interval");
        assertNotNull(dataSource.loadHash(flushInterval), "500,000th record should be flushed by interval");
        assertNull(dataSource.loadHash(count), "500,001st record should not be flushed yet");
        listener.onHashingCompleted();
        assertNotNull(dataSource.loadHash(count), "500,001st record should be flushed on completion");
    }
}
