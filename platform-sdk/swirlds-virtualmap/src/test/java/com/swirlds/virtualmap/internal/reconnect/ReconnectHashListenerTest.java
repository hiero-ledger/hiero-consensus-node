// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal.reconnect;

import static com.swirlds.virtualmap.test.fixtures.VirtualMapTestUtils.CONFIGURATION;
import static com.swirlds.virtualmap.test.fixtures.VirtualMapTestUtils.VIRTUAL_MAP_CONFIG;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.virtualmap.config.VirtualMapConfig;
import com.swirlds.virtualmap.datasource.VirtualDataSource;
import com.swirlds.virtualmap.datasource.VirtualHashRecord;
import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import com.swirlds.virtualmap.internal.hash.VirtualHasher;
import com.swirlds.virtualmap.internal.merkle.VirtualMapStatistics;
import com.swirlds.virtualmap.test.fixtures.InMemoryBuilder;
import com.swirlds.virtualmap.test.fixtures.TestKey;
import com.swirlds.virtualmap.test.fixtures.TestValue;
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
import org.hiero.base.crypto.Cryptography;
import org.hiero.base.crypto.CryptographyProvider;
import org.hiero.base.crypto.Hash;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ReconnectHashListenerTest {

    private static final Cryptography CRYPTO = CryptographyProvider.getInstance();

    @Test
    @DisplayName("Null flusher throws")
    void nullFlusherThrows() {
        assertThrows(
                NullPointerException.class,
                () -> new ReconnectHashListener(null),
                "A null flusher should produce an NPE");
    }

    @Test
    @DisplayName("Valid configurations create an instance")
    void goodLeafPaths() {
        final ReconnectHashLeafFlusher flusher = mock(ReconnectHashLeafFlusher.class);
        try {
            new ReconnectHashListener(flusher);
        } catch (Exception e) {
            fail("Should have been able to create the instance", e);
        }
    }

    @SuppressWarnings("unchecked")
    @ParameterizedTest
    @ValueSource(ints = {1, 2, 10, 100, 1000, 10_000, 100_000, 1_000_000})
    @DisplayName("Flushed data is always done in the right order")
    void flushOrder(int size) {
        final VirtualDataSourceSpy ds =
                new VirtualDataSourceSpy(new InMemoryBuilder().build("flushOrder", null, true, false));

        final VirtualMapStatistics statistics = mock(VirtualMapStatistics.class);
        final ReconnectHashLeafFlusher flusher =
                new ReconnectHashLeafFlusher(ds, VIRTUAL_MAP_CONFIG.reconnectFlushInterval(), statistics);

        // 100 leaves would have firstLeafPath = 99, lastLeafPath = 198
        final long last = size + size;
        final ReconnectHashListener listener = new ReconnectHashListener(flusher);
        final VirtualHasher hasher = new VirtualHasher();
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

    private VirtualLeafBytes leaf(long path) {
        return new VirtualLeafBytes(path, TestKey.longToKey(path), new TestValue(path).toBytes());
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
        public VirtualLeafBytes loadLeafRecord(final Bytes key) throws IOException {
            return delegate.loadLeafRecord(key);
        }

        @Override
        public VirtualLeafBytes loadLeafRecord(final long path) throws IOException {
            return delegate.loadLeafRecord(path);
        }

        @Override
        public long findKey(final Bytes key) throws IOException {
            return delegate.findKey(key);
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
    }
}
