// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.internal;

import static com.swirlds.virtualmap.internal.Path.INVALID_PATH;
import static com.swirlds.virtualmap.test.fixtures.VirtualMapTestUtils.DEFAULT_VIRTUAL_MAP_CONFIG;
import static com.swirlds.virtualmap.test.fixtures.VirtualMapTestUtils.createHashChunkStream;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.virtualmap.datasource.VirtualHashChunk;
import com.swirlds.virtualmap.datasource.VirtualHashRecord;
import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import com.swirlds.virtualmap.internal.cache.VirtualNodeCache;
import com.swirlds.virtualmap.internal.merkle.VirtualMapMetadata;
import com.swirlds.virtualmap.test.fixtures.TestKey;
import com.swirlds.virtualmap.test.fixtures.TestValue;
import com.swirlds.virtualmap.test.fixtures.TestValueCodec;
import com.swirlds.virtualmap.test.fixtures.datasource.DelegateVirtualDataSource;
import com.swirlds.virtualmap.test.fixtures.datasource.InMemoryBuilder;
import com.swirlds.virtualmap.test.fixtures.datasource.InMemoryDataSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;
import org.hiero.base.crypto.Cryptography;
import org.hiero.base.crypto.CryptographyProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

public class RecordAccessorTest {

    private static final int MAX_PATH = 12;
    private static final Cryptography CRYPTO = CryptographyProvider.getInstance();

    private static final long UNCHANGED_INTERNAL_PATH = 1;
    private static final long CHANGED_LEAF_PATH = 5;
    private static final long CHANGED_LEAF_KEY = 11;
    private static final long UNCHANGED_LEAF_PATH = 7;
    private static final long OLD_DELETED_INTERNAL_PATH = 13; // This is bogus in the real world but OK for this test
    private static final long DELETED_LEAF_PATH = 12;
    private static final long BOGUS_LEAF_PATH = 22;

    private BreakableDataSource dataSource;
    private RecordAccessor records;
    private RecordAccessor mutableRecords;

    @BeforeEach
    void setUp() throws IOException {
        final VirtualMapMetadata state = new VirtualMapMetadata();
        dataSource = new BreakableDataSource();
        final int hashChunkHeight = dataSource.getHashChunkHeight();
        final VirtualNodeCache cache =
                new VirtualNodeCache(DEFAULT_VIRTUAL_MAP_CONFIG, hashChunkHeight, dataSource::loadHashChunk);
        records = new RecordAccessor(state, hashChunkHeight, cache, dataSource);

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
                createHashChunkStream(hashChunkHeight, left, right, leftLeft, leftRight, rightLeft),
                Stream.of(firstLeaf, secondLeaf, thirdLeaf, fourthLeaf, fifthLeaf, sixthLeaf, seventhLeaf),
                Stream.empty(),
                false);

        // Prepopulate the cache with some of those records. Some will be deleted, some will be modified, some will
        // not be in the cache.
        var sixthLeafMoved = leaf(11);
        sixthLeafMoved = sixthLeafMoved.withPath(CHANGED_LEAF_PATH);
        final var seventhLeafGone = leaf(DELETED_LEAF_PATH);

        cache.putLeaf(sixthLeafMoved);
        cache.deleteLeaf(seventhLeafGone);
        mutableRecords = new RecordAccessor(state, dataSource.getHashChunkHeight(), cache.copy(), dataSource);
        cache.prepareForHashing();

        // Set up the state for a 6 leaf in memory tree
        state.setLastLeafPath(10);
        state.setFirstLeafPath(5);
    }

    @Test
    @DisplayName("isLeaf method works correctly")
    void isLeafMethodWorks() {
        assertTrue(records.isLeaf(5));
        assertTrue(records.isLeaf(7));
        assertTrue(records.isLeaf(10));

        assertFalse(records.isLeaf(-1));
        assertFalse(records.isLeaf(0));
        assertFalse(records.isLeaf(4));
        assertFalse(records.isLeaf(11));
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
    @DisplayName("findHash of record on disk works")
    void findHashOnDiskReturns() {
        final var hash = records.findHash(UNCHANGED_INTERNAL_PATH);
        assertNotNull(hash, "Did not find record");
        assertEquals(hash, records.findHash(UNCHANGED_INTERNAL_PATH), "Did not find the same hash on disk");
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
    @DisplayName("findPath consistent with findLeafRecord by path")
    void findLeafRecordByKeyByPath() {
        final Bytes key = TestKey.longToKey(UNCHANGED_LEAF_PATH);
        final long path = records.findPath(key);
        final VirtualLeafBytes<?> record = records.findLeafRecord(path);
        assertEquals(key, record.keyBytes());
    }

    @Test
    @DisplayName("close() closes the data source")
    void closeClosesDataSource() throws Exception {
        final VirtualMapMetadata state = new VirtualMapMetadata();
        state.setLastLeafPath(2);
        state.setFirstLeafPath(1);

        final InMemoryDataSource ds = new InMemoryDataSource("closeClosesDataSource");
        final int hashChunkHeight = ds.getHashChunkHeight();

        final VirtualNodeCache cache =
                new VirtualNodeCache(DEFAULT_VIRTUAL_MAP_CONFIG, hashChunkHeight, ds::loadHashChunk);
        cache.putLeaf(leaf(1));
        cache.copy();

        // Take a snapshot that inherits the parent's pool
        final VirtualNodeCache snapshot = cache.snapshot();

        // Wrap in RecordAccessor and close
        final RecordAccessor accessor = new RecordAccessor(state, hashChunkHeight, snapshot, ds);
        accessor.close();

        assertTrue(ds.isClosed(), "Data source should be closed after RecordAccessor.close()");
        cache.shutdown();
    }

    private static final class BreakableDataSource extends DelegateVirtualDataSource {

        boolean throwExceptionOnLoadLeafRecordByKey = false;
        boolean throwExceptionOnLoadLeafRecordByPath = false;
        boolean throwExceptionOnLoadHashChunk = false;

        public BreakableDataSource() {
            super(new InMemoryBuilder().build("delegate", null, true, false));
        }

        @Override
        public VirtualLeafBytes<?> loadLeafRecord(final Bytes key) throws IOException {
            if (throwExceptionOnLoadLeafRecordByKey) {
                throw new IOException("Thrown by loadLeafRecord by key");
            }
            return super.loadLeafRecord(key);
        }

        @Override
        public VirtualLeafBytes<?> loadLeafRecord(final long path) throws IOException {
            if (throwExceptionOnLoadLeafRecordByPath) {
                throw new IOException("Thrown by loadLeafRecord by path");
            }
            return super.loadLeafRecord(path);
        }

        @Override
        public VirtualHashChunk loadHashChunk(final long chunkId) throws IOException {
            if (throwExceptionOnLoadHashChunk) {
                throw new IOException("Thrown by loadHashChunk");
            }
            return super.loadHashChunk(chunkId);
        }
    }

    private static VirtualHashRecord internal(long num) {
        return new VirtualHashRecord(num, CRYPTO.digestSync(("" + num).getBytes(StandardCharsets.UTF_8)));
    }

    private static VirtualLeafBytes<TestValue> leaf(long num) {
        return new VirtualLeafBytes<>(num, TestKey.longToKey(num), new TestValue(num), TestValueCodec.INSTANCE);
    }
}
