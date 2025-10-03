// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal.merkle;

import static com.swirlds.virtualmap.internal.Path.INVALID_PATH;
import static com.swirlds.virtualmap.test.fixtures.VirtualMapTestUtils.CONFIGURATION;
import static com.swirlds.virtualmap.test.fixtures.VirtualMapTestUtils.VM_LABEL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.virtualmap.config.VirtualMapConfig;
import com.swirlds.virtualmap.datasource.VirtualDataSource;
import com.swirlds.virtualmap.datasource.VirtualHashRecord;
import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import com.swirlds.virtualmap.internal.RecordAccessor;
import com.swirlds.virtualmap.internal.cache.VirtualNodeCache;
import com.swirlds.virtualmap.test.fixtures.InMemoryBuilder;
import com.swirlds.virtualmap.test.fixtures.InMemoryDataSource;
import com.swirlds.virtualmap.test.fixtures.TestKey;
import com.swirlds.virtualmap.test.fixtures.TestValue;
import com.swirlds.virtualmap.test.fixtures.TestValueCodec;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.hiero.base.crypto.Cryptography;
import org.hiero.base.crypto.CryptographyProvider;
import org.hiero.base.crypto.Hash;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

public class RecordAccessorTest {

    private static final int MAX_PATH = 12;
    private static final Cryptography CRYPTO = CryptographyProvider.getInstance();

    private static final long UNCHANGED_INTERNAL_PATH = 1;
    private static final long CHANGED_INTERNAL_PATH = 2;
    private static final long CHANGED_LEAF_PATH = 5;
    private static final long CHANGED_LEAF_KEY = 11;
    private static final long UNCHANGED_LEAF_PATH = 7;
    private static final long DELETED_INTERNAL_PATH = 5;
    private static final long OLD_DELETED_INTERNAL_PATH = 13; // This is bogus in the real world but OK for this test
    private static final long DELETED_LEAF_PATH = 12;
    private static final long BOGUS_LEAF_PATH = 22;

    private BreakableDataSource dataSource;
    private RecordAccessor records;
    private RecordAccessor mutableRecords;

    @BeforeEach
    void setUp() throws IOException {
        VirtualMapMetadata state = new VirtualMapMetadata(VM_LABEL);
        VirtualNodeCache cache = new VirtualNodeCache(CONFIGURATION.getConfigData(VirtualMapConfig.class));
        dataSource = new BreakableDataSource();
        records = new RecordAccessor(state, cache, dataSource);

        // Prepopulate the database with some records
        final VirtualHashRecord root = internal(0);
        final VirtualHashRecord left = internal(1);
        final VirtualHashRecord right = internal(2);
        final VirtualHashRecord leftLeft = internal(3);
        final VirtualHashRecord leftRight = internal(4);
        final VirtualHashRecord rightLeft = internal(5);
        final VirtualLeafBytes firstLeaf = leaf(6);
        final VirtualLeafBytes secondLeaf = leaf(7);
        final VirtualLeafBytes thirdLeaf = leaf(8);
        final VirtualLeafBytes fourthLeaf = leaf(9);
        final VirtualLeafBytes fifthLeaf = leaf(10);
        final VirtualLeafBytes sixthLeaf = leaf(11);
        final VirtualLeafBytes seventhLeaf = leaf(12);

        dataSource.saveRecords(
                6,
                12,
                Stream.of(root, left, right, leftLeft, leftRight, rightLeft),
                Stream.of(firstLeaf, secondLeaf, thirdLeaf, fourthLeaf, fifthLeaf, sixthLeaf, seventhLeaf),
                Stream.empty(),
                false);

        // Prepopulate the cache with some of those records. Some will be deleted, some will be modified, some will
        // not be in the cache.
        final var rootChanged =
                new VirtualHashRecord(0, CRYPTO.digestSync("0 changed".getBytes(StandardCharsets.UTF_8)));
        final var rightChanged = new VirtualHashRecord(
                CHANGED_INTERNAL_PATH, CRYPTO.digestSync("2 changed".getBytes(StandardCharsets.UTF_8)));
        var sixthLeafMoved = leaf(11);
        sixthLeafMoved = sixthLeafMoved.withPath(CHANGED_LEAF_PATH);
        final var seventhLeafGone = leaf(DELETED_LEAF_PATH);

        cache.putLeaf(sixthLeafMoved);
        cache.deleteLeaf(seventhLeafGone);
        mutableRecords = new RecordAccessor(state, cache.copy(), dataSource);
        cache.prepareForHashing();
        cache.putHash(rootChanged);
        cache.putHash(rightChanged);

        // Set up the state for a 6 leaf in memory tree
        state.setLastLeafPath(10);
        state.setFirstLeafPath(5);
    }

    @Test
    @DisplayName("findHash of invalid path throws")
    void findHashInvalidPathThrows() {
        assertThrows(AssertionError.class, () -> records.findHash(INVALID_PATH), "Should throw");
    }

    @Test
    @DisplayName("findHash of bad path returns null")
    void findHashBadPath() {
        assertNull(records.findHash(MAX_PATH + 1), "Should have been null");
    }

    @Test
    @DisplayName("findHash in cache returns same instance")
    void findHashInCacheReturnsSameInstance() {
        final var hash = records.findHash(CHANGED_INTERNAL_PATH);
        assertNotNull(hash, "Did not find record");
        assertSame(hash, records.findHash(CHANGED_INTERNAL_PATH), "Did not find the same in memory instance!");
    }

    @Test
    @DisplayName("findHash of record on disk works")
    void findHashOnDiskReturns() {
        final var hash = records.findHash(UNCHANGED_INTERNAL_PATH);
        assertNotNull(hash, "Did not find record");
        assertEquals(hash, records.findHash(UNCHANGED_INTERNAL_PATH), "Did not find the same hash on disk");
    }

    @Test
    @DisplayName("findHash of record with broken data source throws")
    void findHashOnDiskWhenBrokenThrows() {
        dataSource.throwExceptionOnLoadHashByPath = true;
        assertThrows(
                UncheckedIOException.class,
                () -> records.findHash(UNCHANGED_INTERNAL_PATH),
                " Should have thrown UncheckedIOException");
    }

    @Test
    @DisplayName("findHash of deleted record returns null")
    void findHashWhenDeletedIsNull() {
        assertNull(records.findHash(OLD_DELETED_INTERNAL_PATH), "Deleted records should be null");
    }

    @Test
    @DisplayName("findLeafRecord of invalid path throws")
    void findLeafRecordInvalidPathThrows() {
        assertThrows(AssertionError.class, () -> records.findLeafRecord(INVALID_PATH), "Should throw");
    }

    // findLeafRecord by key with "true" puts it in the cache
    // findLeafRecord by path with "true" puts it in the cache

    @Test
    @DisplayName("findLeafRecord by key of bogus key returns null")
    void findLeafRecordBogusKey() {
        assertNull(records.findLeafRecord(TestKey.longToKey(BOGUS_LEAF_PATH)), "Should be null");
    }

    @Test
    @DisplayName("findLeafRecord by key in cache returns same instance")
    void findLeafRecordByKeyInCacheReturnsSameInstance() {
        final var leaf = records.findLeafRecord(TestKey.longToKey(CHANGED_LEAF_KEY));
        assertNotNull(leaf, "Did not find record");
        assertEquals(CHANGED_LEAF_PATH, leaf.path(), "Unexpected path in record");
        assertEquals(TestKey.longToKey(CHANGED_LEAF_KEY), leaf.keyBytes(), "Unexpected key in record");
        assertSame(
                leaf,
                records.findLeafRecord(TestKey.longToKey(CHANGED_LEAF_KEY)),
                "Did not find the same in memory instance!");
    }

    @Test
    @DisplayName("findLeafRecord by key of record on disk works")
    void findLeafRecordByKeyOnDiskReturns() {
        final var leaf = records.findLeafRecord(TestKey.longToKey(UNCHANGED_LEAF_PATH));
        assertNotNull(leaf, "Did not find record");
        assertEquals(UNCHANGED_LEAF_PATH, leaf.path(), "Unexpected path in record");
        assertSame(
                leaf,
                records.findLeafRecord(TestKey.longToKey(UNCHANGED_LEAF_PATH)),
                "Found the same instance on disk? Shouldn't happen!");
    }

    @Test
    @DisplayName("findLeafRecord by key of record with broken data source throws")
    void findLeafRecordByKeyOnDiskWhenBrokenThrows() {
        dataSource.throwExceptionOnLoadLeafRecordByKey = true;
        final Bytes key = TestKey.longToKey(UNCHANGED_LEAF_PATH);
        assertThrows(
                UncheckedIOException.class,
                () -> records.findLeafRecord(key),
                "Should have thrown UncheckedIOException");
    }

    @Test
    @DisplayName("findLeafRecord by key of deleted record returns null")
    void findLeafRecordByKeyWhenDeletedIsNull() {
        assertNull(records.findLeafRecord(TestKey.longToKey(DELETED_LEAF_PATH)), "Deleted records should be null");
    }

    @Test
    @DisplayName("findLeafRecord of bad path returns null")
    void findLeafRecordBadPath() {
        assertNull(records.findLeafRecord(BOGUS_LEAF_PATH), "Should be null");
    }

    @Test
    @DisplayName("findLeafRecord by path in cache returns same instance")
    void findLeafRecordByPathInCacheReturnsSameInstance() {
        final var leaf = records.findLeafRecord(CHANGED_LEAF_PATH);
        assertNotNull(leaf, "Did not find record");
        assertEquals(CHANGED_LEAF_PATH, leaf.path(), "Unexpected path in record");
        assertSame(leaf, records.findLeafRecord(CHANGED_LEAF_PATH), "Did not find the same in memory instance!");
    }

    @Test
    @DisplayName("findLeafRecord by path of record on disk works")
    void findLeafRecordByPathOnDiskReturns() {
        final var leaf = records.findLeafRecord(UNCHANGED_LEAF_PATH);
        assertNotNull(leaf, "Did not find record");
        assertEquals(UNCHANGED_LEAF_PATH, leaf.path(), "Unexpected path in record");
        assertSame(
                leaf,
                records.findLeafRecord(UNCHANGED_LEAF_PATH),
                "Found the same instance on disk? Shouldn't happen!");
    }

    @Test
    @DisplayName("findLeafRecord by key on disk 'copy' true returns a record that is now in the cache")
    void findLeafRecordOnDiskPathCopy() {
        final var record = mutableRecords.findLeafRecord(UNCHANGED_LEAF_PATH);
        assertNotNull(record, "Should not be null");
        assertSame(record, mutableRecords.findLeafRecord(UNCHANGED_LEAF_PATH), "Should have been the same");
    }

    @Test
    @DisplayName("findLeafRecord by path of record with broken data source throws")
    void findLeafRecordByPathOnDiskWhenBrokenThrows() {
        dataSource.throwExceptionOnLoadLeafRecordByPath = true;
        assertThrows(
                UncheckedIOException.class,
                () -> records.findLeafRecord(UNCHANGED_LEAF_PATH),
                "Should have thrown UncheckedIOException");
    }

    @Test
    @DisplayName("findLeafRecord by path of deleted record returns null")
    void findLeafRecordByPathWhenDeletedIsNull() {
        assertNull(records.findLeafRecord(DELETED_LEAF_PATH), "Deleted records should be null");
    }

    @Test
    @DisplayName("findKey consistent with findLeafRecord by path")
    void findLeafRecordByKeyByPath() {
        final Bytes key = TestKey.longToKey(UNCHANGED_LEAF_PATH);
        final long path = records.findKey(key);
        final VirtualLeafBytes<?> record = records.findLeafRecord(path);
        assertEquals(key, record.keyBytes());
    }

    private static final class BreakableDataSource implements VirtualDataSource {

        private final InMemoryDataSource delegate = new InMemoryBuilder().build("delegate", null, true, false);
        boolean throwExceptionOnLoadLeafRecordByKey = false;
        boolean throwExceptionOnLoadLeafRecordByPath = false;
        boolean throwExceptionOnLoadHashByPath = false;

        @Override
        public VirtualLeafBytes<?> loadLeafRecord(final Bytes key) throws IOException {
            if (throwExceptionOnLoadLeafRecordByKey) {
                throw new IOException("Thrown by loadLeafRecord by key");
            }
            return delegate.loadLeafRecord(key);
        }

        @Override
        public VirtualLeafBytes<?> loadLeafRecord(final long path) throws IOException {
            if (throwExceptionOnLoadLeafRecordByPath) {
                throw new IOException("Thrown by loadLeafRecord by path");
            }
            return delegate.loadLeafRecord(path);
        }

        @Override
        public long findKey(final Bytes key) throws IOException {
            return delegate.findKey(key);
        }

        @Override
        public Hash loadHash(final long path) throws IOException {
            if (throwExceptionOnLoadHashByPath) {
                throw new IOException("Thrown by loadInternalRecord");
            }
            return delegate.loadHash(path);
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
            delegate.saveRecords(
                    firstLeafPath,
                    lastLeafPath,
                    pathHashRecordsToUpdate,
                    leafRecordsToAddOrUpdate,
                    leafRecordsToDelete,
                    isReconnectContext);
        }

        @Override
        public void close(final boolean keepData) throws IOException {
            throw new UnsupportedOperationException("Not implemented by these tests");
        }

        @Override
        public void snapshot(final Path snapshotDirectory) throws IOException {
            throw new UnsupportedOperationException("Not implemented for these tests");
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
            delegate.enableBackgroundCompaction();
        }

        @Override
        public void stopAndDisableBackgroundCompaction() {
            delegate.stopAndDisableBackgroundCompaction();
        }
    }

    private static VirtualHashRecord internal(long num) {
        return new VirtualHashRecord(num, CRYPTO.digestSync(("" + num).getBytes(StandardCharsets.UTF_8)));
    }

    private static VirtualLeafBytes<TestValue> leaf(long num) {
        return new VirtualLeafBytes<>(num, TestKey.longToKey(num), new TestValue(num), TestValueCodec.INSTANCE);
    }
}
