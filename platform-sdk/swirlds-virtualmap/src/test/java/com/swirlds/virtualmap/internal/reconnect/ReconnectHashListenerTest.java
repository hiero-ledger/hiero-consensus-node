// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal.reconnect;

import static com.swirlds.virtualmap.test.fixtures.VirtualMapTestUtils.CONFIGURATION;
import static com.swirlds.virtualmap.test.fixtures.VirtualMapTestUtils.VIRTUAL_MAP_CONFIG;
import static com.swirlds.virtualmap.test.fixtures.VirtualMapTestUtils.hashChunkStreamSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.virtualmap.config.VirtualMapConfig;
import com.swirlds.virtualmap.datasource.VirtualDataSource;
import com.swirlds.virtualmap.datasource.VirtualHashChunk;
import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import com.swirlds.virtualmap.internal.hash.VirtualHasher;
import com.swirlds.virtualmap.internal.merkle.VirtualMapStatistics;
import com.swirlds.virtualmap.test.fixtures.InMemoryBuilder;
import com.swirlds.virtualmap.test.fixtures.TestKey;
import com.swirlds.virtualmap.test.fixtures.TestValue;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;
import java.util.function.LongFunction;
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
    @ValueSource(ints = {2, 10, 100, 1000, 10_000, 100_000, 1_000_000})
    @DisplayName("Flushed data is always done in the right order")
    void flushOrder(int size) {
        final VirtualDataSourceSpy ds =
                new VirtualDataSourceSpy(new InMemoryBuilder().build("flushOrder", null, true, false));

        final VirtualMapStatistics statistics = mock(VirtualMapStatistics.class);
        final int hashChunkHeight = ds.getHashChunkHeight();
        final ReconnectHashLeafFlusher flusher =
                new ReconnectHashLeafFlusher(ds, VIRTUAL_MAP_CONFIG.reconnectFlushInterval(), statistics);

        // 100 leaves would have firstLeafPath = 99, lastLeafPath = 198
        final int first = size - 1;
        final int last = 2 * size - 2;
        final ReconnectHashListener listener = new ReconnectHashListener(flusher);
        final VirtualHasher hasher = new VirtualHasher();
        final LongFunction<VirtualHashChunk> chunkPreloader = path -> {
            final long chunkId = VirtualHashChunk.chunkPathToChunkId(path, hashChunkHeight);
            final long chunkPath = VirtualHashChunk.chunkIdToChunkPath(chunkId, hashChunkHeight);
            return new VirtualHashChunk(chunkPath, hashChunkHeight);
        };
        hasher.hash(
                hashChunkHeight,
                chunkPreloader,
                LongStream.range(first, last + 1).mapToObj(this::leaf).iterator(),
                first,
                last,
                listener,
                CONFIGURATION.getConfigData(VirtualMapConfig.class));

        // Now validate that everything showed up the data source in ordered chunks
        final TreeSet<VirtualHashChunk> allFlushedChunks =
                new TreeSet<>(Comparator.comparingLong(VirtualHashChunk::path));
        for (List<VirtualHashChunk> internalRecords : ds.internalRecords) {
            allFlushedChunks.addAll(internalRecords);
        }

        final long expectedFlushedChunkCount = hashChunkStreamSize(hashChunkHeight, 1, last + 1);

        assertEquals(expectedFlushedChunkCount, allFlushedChunks.size(), "Some internal records were not written!");
        int chunkId = 0;
        for (VirtualHashChunk rec : allFlushedChunks) {
            final long path = rec.path();
            final long expectedPath = VirtualHashChunk.chunkIdToChunkPath(chunkId, hashChunkHeight);
            assertEquals(
                    expectedPath, path, "Path did not match expectation. path=" + path + ", expected=" + expectedPath);
            chunkId++;
        }

        final TreeSet<VirtualLeafBytes> allLeafRecords =
                new TreeSet<>(Comparator.comparingLong(VirtualLeafBytes::path));

        for (List<VirtualLeafBytes> leafRecords : ds.leafRecords) {
            allLeafRecords.addAll(leafRecords);
        }

        assertEquals(size, allLeafRecords.size(), "Some leaf records were not written!");
        long expected = first;
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

        private final List<List<VirtualHashChunk>> internalRecords = new ArrayList<>();
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
                @NonNull final Stream<VirtualHashChunk> hashChunksToUpdate,
                @NonNull final Stream<VirtualLeafBytes> leafRecordsToAddOrUpdate,
                @NonNull final Stream<VirtualLeafBytes> leafRecordsToDelete,
                final boolean isReconnectContext)
                throws IOException {
            final var ir = hashChunksToUpdate.toList();
            this.internalRecords.add(ir);
            final var lr = leafRecordsToAddOrUpdate.toList();
            this.leafRecords.add(lr);
            delegate.saveRecords(
                    firstLeafPath, lastLeafPath, ir.stream(), lr.stream(), leafRecordsToDelete, isReconnectContext);
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
        public VirtualHashChunk loadHashChunk(final long chunkId) {
            try {
                return delegate.loadHashChunk(chunkId);
            } catch (final IOException e) {
                throw new UncheckedIOException(e);
            }
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
        public int getHashChunkHeight() {
            return delegate.getHashChunkHeight();
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
