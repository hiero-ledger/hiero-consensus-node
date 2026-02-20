// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb;

import static com.swirlds.common.test.fixtures.AssertionUtils.assertEventuallyFalse;
import static com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils.*;
import static com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils.createMetrics;
import static com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils.getMetric;
import static com.swirlds.merkledb.test.fixtures.TestType.long_fixed;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.swirlds.common.io.utility.LegacyTemporaryFileBuilder;
import com.swirlds.merkledb.config.MerkleDbConfig;
import com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils;
import com.swirlds.merkledb.test.fixtures.TestType;
import com.swirlds.metrics.api.Metric;
import com.swirlds.metrics.api.Metrics;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.hiero.base.constructable.ConstructableRegistry;
import org.hiero.base.utility.test.fixtures.tags.TestComponentTags;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

class MerkleDbDataSourceMetricsTest {

    public static final String TABLE_NAME = "test";
    // default number of longs per chunk
    private static final int COUNT = 1_048_576;
    private static Path testDirectory;
    private MerkleDbDataSource dataSource;
    private Metrics metrics;

    @BeforeAll
    static void setup() throws Exception {
        testDirectory = LegacyTemporaryFileBuilder.buildTemporaryFile(
                "MerkleDbDataSourceMetricsTest", MerkleDbTestUtils.CONFIGURATION);
        ConstructableRegistry.getInstance().registerConstructables("com.swirlds.merkledb");
    }

    @BeforeEach
    public void beforeEach() throws IOException {
        // check db count
        MerkleDbTestUtils.assertAllDatabasesClosed();
        // create db
        dataSource = createDataSource(testDirectory, TABLE_NAME, long_fixed, COUNT * 10);

        metrics = createMetrics();
        dataSource.registerMetrics(metrics);

        // check db count
        MerkleDbTestUtils.assertSomeDatabasesStillOpen(1L);
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
        assertMetricValue("ds_offheap_hashesIndexMb_" + TABLE_NAME, 8);
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
        assertMetricValue("ds_offheap_hashesIndexMb_" + TABLE_NAME, expectedHashesIndexSize);
        assertMetricValue("ds_offheap_dataSourceMb_" + TABLE_NAME, expectedHashesIndexSize);
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
        assertMetricValue("ds_offheap_leavesIndexMb_" + TABLE_NAME, 8);
        assertMetricValue("ds_offheap_objectKeyBucketsIndexMb_" + TABLE_NAME, 8);
        assertMetricValue("ds_offheap_dataSourceMb_" + TABLE_NAME, 16);
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
        assertMetricValue("ds_offheap_leavesIndexMb_" + TABLE_NAME, 16);
        assertMetricValue("ds_offheap_objectKeyBucketsIndexMb_" + TABLE_NAME, 8);
        assertMetricValue("ds_offheap_dataSourceMb_" + TABLE_NAME, 24);
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
        assertMetricValue("ds_offheap_leavesIndexMb_" + TABLE_NAME, 8);

        assertMetricValue("ds_offheap_objectKeyBucketsIndexMb_" + TABLE_NAME, 8);
        assertMetricValue("ds_offheap_dataSourceMb_" + TABLE_NAME, 16);
        assertNoMemoryForInternalList();
    }

    @AfterEach
    public void afterEach() throws IOException {
        dataSource.close();
        MerkleDbTestUtils.assertAllDatabasesClosed();
        // check the database was deleted
        assertEventuallyFalse(
                () -> Files.exists(testDirectory.resolve(TABLE_NAME)),
                Duration.ofSeconds(1),
                "Database should have been deleted by closeAndDelete()");
    }

    // =================================================================================================================
    // Helper Methods

    private void assertNoMemoryForInternalList() {
        assertMetricValue("ds_offheap_hashesIndexMb_" + TABLE_NAME, 0);
    }

    private void assertNoMemoryForLeafAndKeyToPathLists() {
        assertMetricValue("ds_offheap_leavesIndexMb_" + TABLE_NAME, 0);
        assertMetricValue("ds_offheap_objectKeyBucketsIndexMb_" + TABLE_NAME, 0);
    }

    private void assertMetricValue(final String metricPattern, final int expectedValue) {
        final Metric metric = getMetric(metrics, dataSource, metricPattern);
        assertEquals(
                expectedValue,
                Integer.valueOf(metric.get(Metric.ValueType.VALUE).toString()));
    }

    public static MerkleDbDataSource createDataSource(
            final Path testDirectory, final String name, final TestType testType, final int size) throws IOException {
        return testType.dataType().createDataSource(CONFIGURATION, testDirectory, name, size, false, false);
    }
}
