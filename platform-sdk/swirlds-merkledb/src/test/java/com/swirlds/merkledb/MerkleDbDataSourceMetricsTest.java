// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb;

import static com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils.CONFIGURATION;
import static com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils.assertAllDatabasesClosed;
import static com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils.assertDatabaseFolderDeleted;
import static com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils.assertSomeDatabasesStillOpen;
import static com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils.createDataSource;
import static com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils.createHashChunkStream;
import static com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils.createMetrics;
import static com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils.getMetric;
import static com.swirlds.merkledb.test.fixtures.TestType.long_fixed;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.swirlds.merkledb.config.MerkleDbConfig;
import com.swirlds.metrics.api.Metric;
import com.swirlds.metrics.api.Metrics;
import java.io.IOException;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.hiero.base.utility.test.fixtures.file.AbstractFileManagerAwareTest;
import org.hiero.base.utility.test.fixtures.tags.TestComponentTags;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

class MerkleDbDataSourceMetricsTest extends AbstractFileManagerAwareTest {

    // default number of longs per chunk
    private static final int COUNT = 1_048_576;

    private MerkleDbDataSource dataSource;
    private Metrics metrics;

    @BeforeEach
    public void beforeEach() {
        // check db count
        assertAllDatabasesClosed();
        // create db
        dataSource = createDataSource(fileSystemManager, COUNT * 10, false, false);

        metrics = createMetrics();
        dataSource.registerMetrics(metrics);

        // check db count
        assertSomeDatabasesStillOpen(1L);
    }

    @AfterEach
    public void afterEach() throws IOException {
        dataSource.close();
        assertAllDatabasesClosed();
        assertDatabaseFolderDeleted(dataSource);
    }

    @Tag(TestComponentTags.VMAP)
    @Test
    void createInternalNodeHashesAndCheckMemoryConsumption() throws IOException {
        // no memory consumption in an empty DB
        assertNoMemoryForInternalList();
        assertNoMemoryForLeafAndKeyToPathLists();

        // create some internal nodes
        dataSource.saveRecords(
                COUNT,
                COUNT * 2,
                createHashChunkStream(COUNT * 2, dataSource.getHashChunkHeight()),
                Stream.empty(),
                Stream.empty(),
                false);

        // one 8 MB memory chunk; this value may need to be adjusted if the default chunk height
        // is changed
        assertMetricValue("ds_offheap_hashesIndexMb_" + dataSource.getTableName(), 8);
        assertNoMemoryForLeafAndKeyToPathLists();

        // create more internal nodes
        dataSource.saveRecords(
                COUNT * 2,
                COUNT * 4,
                createHashChunkStream(COUNT * 2, dataSource.getHashChunkHeight()),
                Stream.empty(),
                Stream.empty(),
                false);

        // one 8 MB memory chunk
        final int expectedHashesIndexSize = 8;
        assertMetricValue("ds_offheap_hashesIndexMb_" + dataSource.getTableName(), expectedHashesIndexSize);
        assertMetricValue("ds_offheap_dataSourceMb_" + dataSource.getTableName(), expectedHashesIndexSize);
        assertNoMemoryForLeafAndKeyToPathLists();
    }

    @Tag(TestComponentTags.VMAP)
    @Test
    void createAndCheckLeaves() throws IOException {
        assertNoMemoryForInternalList();
        assertNoMemoryForLeafAndKeyToPathLists();
        // create some leaves
        final int firstLeafIndex = COUNT;
        final int lastLeafIndex = COUNT * 2;
        dataSource.saveRecords(
                firstLeafIndex,
                lastLeafIndex,
                Stream.empty(),
                IntStream.range(firstLeafIndex, lastLeafIndex)
                        .mapToObj(i -> long_fixed.dataType().createVirtualLeafRecord(i)),
                Stream.empty(),
                false);

        // only one 8 MB memory is reserved despite the fact that leaves reside in [COUNT, COUNT * 2] interval
        assertMetricValue("ds_offheap_leavesIndexMb_" + dataSource.getTableName(), 8);
        assertMetricValue("ds_offheap_objectKeyBucketsIndexMb_" + dataSource.getTableName(), 8);
        assertMetricValue("ds_offheap_dataSourceMb_" + dataSource.getTableName(), 16);
        assertNoMemoryForInternalList();

        final MerkleDbConfig merkleDbConfig = CONFIGURATION.getConfigData(MerkleDbConfig.class);

        dataSource.saveRecords(
                firstLeafIndex,
                lastLeafIndex + merkleDbConfig.longListReservedBufferSize() + 1,
                Stream.empty(),
                IntStream.range(firstLeafIndex, lastLeafIndex + merkleDbConfig.longListReservedBufferSize() + 1)
                        .mapToObj(i -> long_fixed.dataType().createVirtualLeafRecord(i)),
                Stream.empty(),
                false);

        // reserved additional memory chunk for a value that didn't fit into the previous chunk
        assertMetricValue("ds_offheap_leavesIndexMb_" + dataSource.getTableName(), 16);
        assertMetricValue("ds_offheap_objectKeyBucketsIndexMb_" + dataSource.getTableName(), 8);
        assertMetricValue("ds_offheap_dataSourceMb_" + dataSource.getTableName(), 24);
        assertNoMemoryForInternalList();

        dataSource.saveRecords(
                lastLeafIndex + merkleDbConfig.longListReservedBufferSize(),
                lastLeafIndex + merkleDbConfig.longListReservedBufferSize() + 1,
                Stream.empty(),
                // valid leaf index
                IntStream.of(lastLeafIndex + merkleDbConfig.longListReservedBufferSize())
                        .mapToObj(i -> long_fixed.dataType().createVirtualLeafRecord(i)),
                Stream.empty(),
                false);

        // shrink the list by one chunk
        assertMetricValue("ds_offheap_leavesIndexMb_" + dataSource.getTableName(), 8);

        assertMetricValue("ds_offheap_objectKeyBucketsIndexMb_" + dataSource.getTableName(), 8);
        assertMetricValue("ds_offheap_dataSourceMb_" + dataSource.getTableName(), 16);
        assertNoMemoryForInternalList();
    }

    // =================================================================================================================
    // Helper Methods

    private void assertNoMemoryForInternalList() {
        assertMetricValue("ds_offheap_hashesIndexMb_" + dataSource.getTableName(), 0);
    }

    private void assertNoMemoryForLeafAndKeyToPathLists() {
        assertMetricValue("ds_offheap_leavesIndexMb_" + dataSource.getTableName(), 0);
        assertMetricValue("ds_offheap_objectKeyBucketsIndexMb_" + dataSource.getTableName(), 0);
    }

    private void assertMetricValue(final String metricPattern, final int expectedValue) {
        final Metric metric = getMetric(metrics, dataSource, metricPattern);
        assertEquals(
                expectedValue,
                Integer.valueOf(metric.get(Metric.ValueType.VALUE).toString()));
    }
}
