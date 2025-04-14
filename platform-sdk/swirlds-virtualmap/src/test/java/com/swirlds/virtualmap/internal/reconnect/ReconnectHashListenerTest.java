// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal.reconnect;

import static com.swirlds.virtualmap.test.fixtures.VirtualMapTestUtils.CONFIGURATION;
import static com.swirlds.virtualmap.test.fixtures.VirtualMapTestUtils.VIRTUAL_MAP_CONFIG;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.crypto.Cryptography;
import com.swirlds.common.crypto.CryptographyProvider;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.virtualmap.config.VirtualMapConfig;
import com.swirlds.virtualmap.datasource.VirtualDataSource;
import com.swirlds.virtualmap.datasource.VirtualHashRecord;
import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import com.swirlds.virtualmap.datasource.VirtualLeafRecord;
import com.swirlds.virtualmap.internal.hash.VirtualHasher;
import com.swirlds.virtualmap.internal.merkle.VirtualMapStatistics;
import com.swirlds.virtualmap.serialize.KeySerializer;
import com.swirlds.virtualmap.serialize.ValueSerializer;
import com.swirlds.virtualmap.test.fixtures.InMemoryBuilder;
import com.swirlds.virtualmap.test.fixtures.TestKey;
import com.swirlds.virtualmap.test.fixtures.TestKeySerializer;
import com.swirlds.virtualmap.test.fixtures.TestValue;
import com.swirlds.virtualmap.test.fixtures.TestValueSerializer;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import org.hiero.consensus.model.crypto.Hash;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ReconnectHashListenerTest {

    private static final Cryptography CRYPTO = CryptographyProvider.getInstance();

    @Test
    @DisplayName("Null datasource throws")
    void nullDataSourceThrows() {
        final VirtualMapStatistics statistics = mock(VirtualMapStatistics.class);
        final ReconnectNodeRemover<TestKey, TestValue> nodeRemover = mock(ReconnectNodeRemover.class);
        assertThrows(
                NullPointerException.class,
                () -> new ReconnectHashListener<TestKey, TestValue>(
                        1,
                        1,
                        TestKeySerializer.INSTANCE,
                        TestValueSerializer.INSTANCE,
                        null,
                        VIRTUAL_MAP_CONFIG.reconnectFlushInterval(),
                        statistics,
                        nodeRemover),
                "A null data source should produce an NPE");
    }

    @Test
    @DisplayName("Null statistics throws")
    void nullStatisticsThrows() {
        final VirtualDataSource ds = new InMemoryBuilder().build("nullStatisticsThrows", false);
        final ReconnectNodeRemover<TestKey, TestValue> nodeRemover = mock(ReconnectNodeRemover.class);
        assertThrows(
                NullPointerException.class,
                () -> new ReconnectHashListener<>(
                        1,
                        1,
                        TestKeySerializer.INSTANCE,
                        TestValueSerializer.INSTANCE,
                        ds,
                        VIRTUAL_MAP_CONFIG.reconnectFlushInterval(),
                        null,
                        nodeRemover),
                "A null statistics should produce an NPE");
    }

    @Test
    @DisplayName("Null node remover throws")
    void nullNodeRemoverThrows() {
        final VirtualDataSource ds = new InMemoryBuilder().build("nullStatisticsThrows", false);
        final VirtualMapStatistics statistics = mock(VirtualMapStatistics.class);
        assertThrows(
                NullPointerException.class,
                () -> new ReconnectHashListener<>(
                        1,
                        1,
                        TestKeySerializer.INSTANCE,
                        TestValueSerializer.INSTANCE,
                        ds,
                        VIRTUAL_MAP_CONFIG.reconnectFlushInterval(),
                        statistics,
                        null),
                "A null node remover should produce an NPE");
    }

    // Future: We should also check for first/last leaf path being equal when not 1. That really should never happen.
    // That check should be laced through everything, including VirtualMapState.
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
    void badLeafPaths(long firstLeafPath, long lastLeafPath) {
        final VirtualDataSource ds = new InMemoryBuilder().build("badLeafPaths", false);
        final VirtualMapStatistics statistics = mock(VirtualMapStatistics.class);
        final ReconnectNodeRemover<TestKey, TestValue> nodeRemover = mock(ReconnectNodeRemover.class);
        assertThrows(
                IllegalArgumentException.class,
                () -> new ReconnectHashListener<>(
                        firstLeafPath,
                        lastLeafPath,
                        TestKeySerializer.INSTANCE,
                        TestValueSerializer.INSTANCE,
                        ds,
                        VIRTUAL_MAP_CONFIG.reconnectFlushInterval(),
                        statistics,
                        nodeRemover),
                "Should have thrown IllegalArgumentException");
    }

    @ParameterizedTest
    @CsvSource({"-1, -1", " 1,  1", " 1,  2", " 4,  8"})
    @DisplayName("Valid configurations create an instance")
    void goodLeafPaths(long firstLeafPath, long lastLeafPath) {
        final VirtualDataSource ds = new InMemoryBuilder().build("goodLeafPaths", true);
        final VirtualMapStatistics statistics = mock(VirtualMapStatistics.class);
        final ReconnectNodeRemover<TestKey, TestValue> nodeRemover = mock(ReconnectNodeRemover.class);
        try {
            new ReconnectHashListener<>(
                    firstLeafPath,
                    lastLeafPath,
                    TestKeySerializer.INSTANCE,
                    TestValueSerializer.INSTANCE,
                    ds,
                    VIRTUAL_MAP_CONFIG.reconnectFlushInterval(),
                    statistics,
                    nodeRemover);
        } catch (Exception e) {
            fail("Should have been able to create the instance", e);
        }
    }

    @SuppressWarnings("unchecked")
    @ParameterizedTest
    @ValueSource(ints = {1, 2, 10, 100, 1000, 10_000, 100_000, 1_000_000})
    @DisplayName("Flushed data is always done in the right order")
    void flushOrder(int size) {
        final VirtualDataSourceSpy ds = new VirtualDataSourceSpy(new InMemoryBuilder().build("flushOrder", true));

        final VirtualMapStatistics statistics = mock(VirtualMapStatistics.class);
        final ReconnectNodeRemover<TestKey, TestValue> nodeRemover = mock(ReconnectNodeRemover.class);

        // 100 leaves would have firstLeafPath = 99, lastLeafPath = 198
        final long last = size + size;
        final ReconnectHashListener<TestKey, TestValue> listener = new ReconnectHashListener<>(
                size,
                last,
                TestKeySerializer.INSTANCE,
                TestValueSerializer.INSTANCE,
                ds,
                VIRTUAL_MAP_CONFIG.reconnectFlushInterval(),
                statistics,
                nodeRemover);
        final VirtualHasher<TestKey, TestValue> hasher = new VirtualHasher<>();
        hasher.hash(
                this::hash,
                LongStream.range(size, last).mapToObj(this::leaf).iterator(),
                size,
                last,
                listener,
                CONFIGURATION.getConfigData(VirtualMapConfig.class));

        // Now validate that everything showed up the data source in ordered chunks
        final TreeSet<VirtualHashRecord> allInternalRecords =
                new TreeSet<>(Comparator.comparingLong(VirtualHashRecord::path));
        for (List<VirtualHashRecord> internalRecords : ds.internalRecords) {
            allInternalRecords.addAll(internalRecords);
        }

        assertEquals(size + size, allInternalRecords.size(), "Some internal records were not written!");
        long expected = 0;
        for (VirtualHashRecord rec : allInternalRecords) {
            final long path = rec.path();
            assertEquals(expected, path, "Path did not match expectation. path=" + path + ", expected=" + expected);
            expected++;
        }

        final TreeSet<VirtualLeafBytes> allLeafRecords =
                new TreeSet<>(Comparator.comparingLong(VirtualLeafBytes::path));

        for (List<VirtualLeafBytes> leafRecords : ds.leafRecords) {
            allLeafRecords.addAll(leafRecords);
        }

        assertEquals(size, allLeafRecords.size(), "Some leaf records were not written!");
        expected = size;
        for (VirtualLeafBytes rec : allLeafRecords) {
            final long path = rec.path();
            assertEquals(expected, path, "Path did not match expectation. path=" + path + ", expected=" + expected);
            expected++;
        }
    }

    private VirtualLeafRecord<TestKey, TestValue> leaf(long path) {
        return new VirtualLeafRecord<>(path, new TestKey(path), new TestValue(path));
    }

    private Hash hash(long path) {
        return CRYPTO.digestSync(("" + path).getBytes(StandardCharsets.UTF_8));
    }

    private static final class VirtualDataSourceSpy implements VirtualDataSource {

        private final VirtualDataSource delegate;

        private final List<List<VirtualHashRecord>> internalRecords = new ArrayList<>();
        private final List<List<VirtualLeafBytes>> leafRecords = new ArrayList<>();

        VirtualDataSourceSpy(VirtualDataSource delegate) {
            this.delegate = delegate;
        }

        @Override
        public void close(final boolean keepData) throws IOException {
            delegate.close(keepData);
        }

        @Override
        public void saveRecords(
                final long firstLeafPath,
                final long lastLeafPath,
                @NonNull final Stream<VirtualHashRecord> pathHashRecordsToUpdate,
                @NonNull final Stream<VirtualLeafBytes> leafRecordsToAddOrUpdate,
                @NonNull final Stream<VirtualLeafBytes> leafRecordsToDelete,
                final boolean isReconnectContext)
                throws IOException {
            final var ir = pathHashRecordsToUpdate.toList();
            this.internalRecords.add(ir);
            final var lr = leafRecordsToAddOrUpdate.toList();
            this.leafRecords.add(lr);
            delegate.saveRecords(
                    firstLeafPath, lastLeafPath, ir.stream(), lr.stream(), leafRecordsToDelete, isReconnectContext);
        }

        @Override
        public void saveRecords(
                final long firstLeafPath,
                final long lastLeafPath,
                @NonNull final Stream<VirtualHashRecord> pathHashRecordsToUpdate,
                @NonNull final Stream<VirtualLeafBytes> leafRecordsToAddOrUpdate,
                @NonNull final Stream<VirtualLeafBytes> leafRecordsToDelete)
                throws IOException {

            saveRecords(
                    firstLeafPath,
                    lastLeafPath,
                    pathHashRecordsToUpdate,
                    leafRecordsToAddOrUpdate,
                    leafRecordsToDelete,
                    true);
        }

        @Override
        public VirtualLeafBytes loadLeafRecord(final Bytes key, final int keyHashCode) throws IOException {
            return delegate.loadLeafRecord(key, keyHashCode);
        }

        @Override
        public VirtualLeafBytes loadLeafRecord(final long path) throws IOException {
            return delegate.loadLeafRecord(path);
        }

        @Override
        public long findKey(final Bytes key, final int keyHashCode) throws IOException {
            return delegate.findKey(key, keyHashCode);
        }

        @Override
        public Hash loadHash(final long path) throws IOException {
            return delegate.loadHash(path);
        }

        @Override
        public void snapshot(final Path snapshotDirectory) throws IOException {
            delegate.snapshot(snapshotDirectory);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void copyStatisticsFrom(final VirtualDataSource that) {
            // this database has no statistics
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void registerMetrics(final Metrics metrics) {
            // this database has no statistics
        }

        @Override
        public long getFirstLeafPath() {
            return delegate.getFirstLeafPath();
        }

        @Override
        public long getLastLeafPath() {
            return delegate.getLastLeafPath();
        }

        @Override
        public void enableBackgroundCompaction() {
            // no op
        }

        @Override
        public void stopAndDisableBackgroundCompaction() {
            // no op
        }

        @Override
        @SuppressWarnings("rawtypes")
        public KeySerializer getKeySerializer() {
            throw new UnsupportedOperationException("This method should never be called");
        }

        @Override
        @SuppressWarnings("rawtypes")
        public ValueSerializer getValueSerializer() {
            throw new UnsupportedOperationException("This method should never be called");
        }
    }
}
