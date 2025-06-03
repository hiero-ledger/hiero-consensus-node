// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.files.hashmap;

import static com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils.CONFIGURATION;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.merkledb.collections.LongList;
import com.swirlds.merkledb.collections.LongListHeap;
import com.swirlds.merkledb.config.MerkleDbConfig;
import com.swirlds.merkledb.files.DataFileCompactor;
import com.swirlds.merkledb.files.MemoryIndexDiskKeyValueStore;
import com.swirlds.merkledb.test.fixtures.ExampleLongKeyFixedSize;
import com.swirlds.merkledb.test.fixtures.files.FilesTestType;
import com.swirlds.virtualmap.VirtualKey;
import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.hiero.base.io.streams.SerializableDataInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

@SuppressWarnings({"SameParameterValue", "unchecked"})
class HalfDiskHashMapTest {

    /** Temporary directory provided by JUnit */
    @SuppressWarnings("unused")
    @TempDir
    Path tempDirPath;

    // =================================================================================================================
    // Helper Methods
    private HalfDiskHashMap createNewTempMap(final String name, final long count) throws IOException {
        // create map
        HalfDiskHashMap map = new HalfDiskHashMap(
                CONFIGURATION, count, tempDirPath.resolve(name), "HalfDiskHashMapTest", null, false);
        map.printStats();
        return map;
    }

    private MemoryIndexDiskKeyValueStore createNewTempKV(final String name, final int count) throws IOException {
        final MerkleDbConfig merkleDbConfig = CONFIGURATION.getConfigData(MerkleDbConfig.class);
        final LongList index = new LongListHeap(count, CONFIGURATION);
        return new MemoryIndexDiskKeyValueStore(
                merkleDbConfig, tempDirPath.resolve(name + "_kv"), "HalfDiskHashMapTestKV", null, null, index);
    }

    private static void createSomeData(
            final FilesTestType testType,
            final HalfDiskHashMap map,
            final int start,
            final int count,
            final long dataMultiplier)
            throws IOException {
        map.startWriting();
        for (int i = start; i < (start + count); i++) {
            final VirtualKey key = testType.createVirtualLongKey(i);
            map.put(testType.keySerializer.toBytes(key), key.hashCode(), i * dataMultiplier);
        }
        //        map.debugDumpTransactionCache();
        long START = System.currentTimeMillis();
        map.endWriting();
        printTestUpdate(START, count, "Written");
    }

    private static void checkData(
            final FilesTestType testType,
            final HalfDiskHashMap map,
            final int start,
            final int count,
            final long dataMultiplier)
            throws IOException {
        long START = System.currentTimeMillis();
        for (int i = start; i < (start + count); i++) {
            final var key = testType.createVirtualLongKey(i);
            long result = map.get(testType.keySerializer.toBytes(key), key.hashCode(), -1);
            assertEquals(
                    i * dataMultiplier,
                    result,
                    "Failed to read key=" + testType.createVirtualLongKey(i) + " dataMultiplier=" + dataMultiplier);
        }
        printTestUpdate(START, count, "Read");
    }

    // =================================================================================================================
    // Tests

    @ParameterizedTest
    @EnumSource(FilesTestType.class)
    void createDataAndCheck(FilesTestType testType) throws Exception {
        final Path tempSnapshotDir = tempDirPath.resolve("DataFileTestSnapshot_" + testType.name());
        final int count = 10_000;
        // create map
        try (HalfDiskHashMap map = createNewTempMap("createDataAndCheck", count)) {
            // create some data
            createSomeData(testType, map, 1, count, 1);
            // sequentially check data
            checkData(testType, map, 1, count, 1);
            // randomly check data
            Random random = new Random(1234);
            for (int j = 1; j < (count * 2); j++) {
                int i = 1 + random.nextInt(count);
                final VirtualKey key = testType.createVirtualLongKey(i);
                long result = map.get(testType.keySerializer.toBytes(key), key.hashCode(), 0);
                assertEquals(i, result, "unexpected value of newVirtualLongKey");
            }
            // create snapshot
            map.snapshot(tempSnapshotDir);
            // open snapshot and check data
            HalfDiskHashMap mapFromSnapshot =
                    new HalfDiskHashMap(CONFIGURATION, count, tempSnapshotDir, "HalfDiskHashMapTest", null, false);
            mapFromSnapshot.printStats();
            checkData(testType, mapFromSnapshot, 1, count, 1);
            // check deletion
            map.startWriting();
            final VirtualKey key5 = testType.createVirtualLongKey(5);
            final VirtualKey key50 = testType.createVirtualLongKey(50);
            final VirtualKey key500 = testType.createVirtualLongKey(500);
            map.delete(testType.keySerializer.toBytes(key5), key5.hashCode());
            map.delete(testType.keySerializer.toBytes(key50), key50.hashCode());
            map.delete(testType.keySerializer.toBytes(key500), key500.hashCode());
            map.endWriting();
            assertEquals(-1, map.get(testType.keySerializer.toBytes(key5), key5.hashCode(), -1), "Expect not to exist");
            assertEquals(
                    -1, map.get(testType.keySerializer.toBytes(key50), key50.hashCode(), -1), "Expect not to exist");
            assertEquals(
                    -1, map.get(testType.keySerializer.toBytes(key500), key500.hashCode(), -1), "Expect not to exist");
            checkData(testType, map, 1, 4, 1);
            checkData(testType, map, 6, 43, 1);
            checkData(testType, map, 51, 448, 1);
            checkData(testType, map, 501, 9499, 1);
            // check close and try read after
            map.close();
            assertEquals(
                    -1,
                    map.get(testType.keySerializer.toBytes(key5), key5.hashCode(), -1),
                    "Expect not found result as just closed the map!");
        }
    }

    @ParameterizedTest
    @EnumSource(FilesTestType.class)
    void multipleWriteBatchesAndMerge(FilesTestType testType) throws Exception {
        // create map
        try (HalfDiskHashMap map = createNewTempMap("multipleWriteBatchesAndMerge", 10_000)) {
            final DataFileCompactor dataFileCompactor = new DataFileCompactor(
                    CONFIGURATION.getConfigData(MerkleDbConfig.class),
                    "HalfDiskHashMapTest",
                    map.getFileCollection(),
                    map.getBucketIndexToBucketLocation(),
                    null,
                    null,
                    null,
                    null);
            // create some data
            createSomeData(testType, map, 1, 1111, 1);
            checkData(testType, map, 1, 1111, 1);
            // create some more data
            createSomeData(testType, map, 1111, 3333, 1);
            checkData(testType, map, 1, 3333, 1);
            // create some more data
            createSomeData(testType, map, 1111, 10_000, 1);
            checkData(testType, map, 1, 10_000, 1);
            // do a merge
            dataFileCompactor.compact();
            // check all data after
            checkData(testType, map, 1, 10_000, 1);
        }
    }

    @ParameterizedTest
    @EnumSource(FilesTestType.class)
    void updateData(FilesTestType testType) throws Exception {
        // create map
        try (HalfDiskHashMap map = createNewTempMap("updateData", 1000)) {
            // create some data
            createSomeData(testType, map, 0, 1000, 1);
            checkData(testType, map, 0, 1000, 1);
            // update some data
            createSomeData(testType, map, 200, 400, 2);
            checkData(testType, map, 0, 200, 1);
            checkData(testType, map, 200, 400, 2);
            checkData(testType, map, 600, 400, 1);
        }
    }

    @Test
    void testOverwritesWithCollision() throws IOException {
        final FilesTestType testType = FilesTestType.fixed;
        try (HalfDiskHashMap map = createNewTempMap("testOverwritesWithCollision", 1000)) {
            map.startWriting();
            for (int i = 100; i < 300; i++) {
                final VirtualKey key = new CollidableFixedLongKey(i);
                map.put(testType.keySerializer.toBytes(key), key.hashCode(), i);
            }
            assertDoesNotThrow(map::endWriting);
        }
    }

    @Test
    void testRebuildMap() throws Exception {
        final FilesTestType testType = FilesTestType.variable;
        try (HalfDiskHashMap map = createNewTempMap("testRebuildMap", 100)) {
            map.startWriting();
            final VirtualKey key1 = testType.createVirtualLongKey(1);
            map.put(testType.keySerializer.toBytes(key1), key1.hashCode(), 1);
            final VirtualKey key2 = testType.createVirtualLongKey(2);
            map.put(testType.keySerializer.toBytes(key2), key2.hashCode(), 2);
            map.endWriting();
            map.startWriting();
            final VirtualKey key3 = testType.createVirtualLongKey(3);
            map.put(testType.keySerializer.toBytes(key3), key3.hashCode(), 3);
            final VirtualKey key4 = testType.createVirtualLongKey(4);
            map.put(testType.keySerializer.toBytes(key4), key4.hashCode(), 4);
            map.endWriting();

            assertEquals(1, map.get(testType.keySerializer.toBytes(key1), key1.hashCode(), -1));
            assertEquals(2, map.get(testType.keySerializer.toBytes(key2), key2.hashCode(), -1));
            assertEquals(3, map.get(testType.keySerializer.toBytes(key3), key3.hashCode(), -1));
            assertEquals(4, map.get(testType.keySerializer.toBytes(key4), key4.hashCode(), -1));

            final MemoryIndexDiskKeyValueStore kv = createNewTempKV("testRebuildMap", 100);
            kv.startWriting();
            kv.updateValidKeyRange(2, 4);
            final VirtualLeafBytes rec2 =
                    new VirtualLeafBytes(2, testType.keySerializer.toBytes(key2), key2.hashCode(), Bytes.wrap("12"));
            kv.put(2, rec2::writeTo, rec2.getSizeInBytes());
            final VirtualLeafBytes rec3 =
                    new VirtualLeafBytes(3, testType.keySerializer.toBytes(key3), key3.hashCode(), Bytes.wrap("13"));
            kv.put(3, rec3::writeTo, rec3.getSizeInBytes());
            final VirtualLeafBytes rec4 =
                    new VirtualLeafBytes(4, testType.keySerializer.toBytes(key4), key4.hashCode(), Bytes.wrap("14"));
            kv.put(4, rec4::writeTo, rec4.getSizeInBytes());
            kv.endWriting();

            map.repair(2, 4, kv);

            assertEquals(-1, map.get(testType.keySerializer.toBytes(key1), key1.hashCode(), -1));
            assertEquals(2, map.get(testType.keySerializer.toBytes(key2), key2.hashCode(), -1));
            assertEquals(3, map.get(testType.keySerializer.toBytes(key3), key3.hashCode(), -1));
            assertEquals(4, map.get(testType.keySerializer.toBytes(key4), key4.hashCode(), -1));
        }
    }

    @Test
    void testRebuildIncompleteMap() throws Exception {
        final FilesTestType testType = FilesTestType.variable;
        try (HalfDiskHashMap map = createNewTempMap("testRebuildIncompleteMap", 100)) {
            map.startWriting();
            final VirtualKey key1 = testType.createVirtualLongKey(1);
            map.put(testType.keySerializer.toBytes(key1), key1.hashCode(), 1);
            final VirtualKey key2 = testType.createVirtualLongKey(2);
            map.put(testType.keySerializer.toBytes(key2), key2.hashCode(), 2);
            final VirtualKey key3 = testType.createVirtualLongKey(3);
            map.put(testType.keySerializer.toBytes(key3), key3.hashCode(), 3);
            final VirtualKey key4 = testType.createVirtualLongKey(4);
            // No entry for key 4
            map.endWriting();

            assertEquals(1, map.get(testType.keySerializer.toBytes(key1), key1.hashCode(), -1));
            assertEquals(2, map.get(testType.keySerializer.toBytes(key2), key2.hashCode(), -1));
            assertEquals(3, map.get(testType.keySerializer.toBytes(key3), key3.hashCode(), -1));

            final MemoryIndexDiskKeyValueStore kv = createNewTempKV("testRebuildIncompleteMap", 100);
            kv.startWriting();
            kv.updateValidKeyRange(2, 4);
            final VirtualLeafBytes rec2 =
                    new VirtualLeafBytes(2, testType.keySerializer.toBytes(key2), key2.hashCode(), Bytes.wrap("12"));
            kv.put(2, rec2::writeTo, rec2.getSizeInBytes());
            final VirtualLeafBytes rec3 =
                    new VirtualLeafBytes(3, testType.keySerializer.toBytes(key3), key3.hashCode(), Bytes.wrap("13"));
            kv.put(3, rec3::writeTo, rec3.getSizeInBytes());
            final VirtualLeafBytes rec4 =
                    new VirtualLeafBytes(4, testType.keySerializer.toBytes(key4), key4.hashCode(), Bytes.wrap("14"));
            kv.put(4, rec4::writeTo, rec4.getSizeInBytes());
            kv.endWriting();

            // key4 is missing in the map, it cannot be restored from pathToKeyValue store
            assertThrows(IOException.class, () -> map.repair(2, 4, kv));
        }
    }

    @ParameterizedTest
    @ValueSource(longs = {100, 1000, 2000, 1_000_000, 1_000_000_000})
    void testDefaultNumOfBuckets(final long count) throws Exception {
        try (HalfDiskHashMap map = createNewTempMap("testDefaultNumOfBuckets", count)) {
            assertEquals(calcExpectedNumOfBuckets(count), map.getNumOfBuckets());
        }
    }

    @Test
    void testResizeBasic() throws Exception {
        try (HalfDiskHashMap map = createNewTempMap("testResizeBasic", 1000)) {
            final int initialNumOfBuckets = calcExpectedNumOfBuckets(1000);
            assertEquals(initialNumOfBuckets, map.getNumOfBuckets());
            map.resizeIfNeeded(99, 198); // map size: 100, no resize needed
            assertEquals(initialNumOfBuckets, map.getNumOfBuckets());
            map.resizeIfNeeded(999, 1998); // map size: 1000, buckets should be doubled
            assertEquals(initialNumOfBuckets * 2, map.getNumOfBuckets());
        }
    }

    @Test
    void checkValuesAfterResize() throws Exception {
        try (HalfDiskHashMap map = createNewTempMap("checkValuesAfterResize", 200)) {
            final int initialNumOfBuckets = calcExpectedNumOfBuckets(200);
            assertEquals(initialNumOfBuckets, map.getNumOfBuckets());
            map.startWriting();
            for (int i = 0; i < 100; i++) {
                map.put(Bytes.wrap(new byte[] {(byte) i, 10}), 1000 + i, i * 2);
            }
            map.endWriting();
            map.resizeIfNeeded(499, 998);
            for (int i = 0; i < 100; i++) {
                final long path = map.get(Bytes.wrap(new byte[] {(byte) i, 10}), 1000 + i, -1);
                assertEquals(i * 2, path);
            }
            for (int i = 100; i < 200; i++) {
                final long path = map.get(Bytes.wrap(new byte[] {(byte) i, 10}), 1000 + i, -1);
                assertEquals(-1, path);
            }
        }
    }

    @Test
    void checkBucketIndexAfterResize() throws Exception {
        try (HalfDiskHashMap map = createNewTempMap("checkBucketIndexAfterResize", 200)) {
            final int initialNumOfBuckets = calcExpectedNumOfBuckets(200);
            assertEquals(initialNumOfBuckets, map.getNumOfBuckets()); // should be 8
            map.startWriting();
            for (int i = 0; i < 100; i++) {
                map.put(Bytes.wrap(new byte[] {(byte) i, 10}), 1000 + i, i * 2);
            }
            map.endWriting();
            final List<Long> bucketIndexValues = new ArrayList<>();
            for (int i = 0; i < initialNumOfBuckets; i++) {
                bucketIndexValues.add(map.getBucketIndexToBucketLocation().get(i));
            }
            assertEquals(initialNumOfBuckets, bucketIndexValues.size());
            map.resizeIfNeeded(499, 998);
            assertEquals(initialNumOfBuckets * 2, map.getNumOfBuckets());
            for (int i = 0; i < initialNumOfBuckets; i++) {
                assertEquals(
                        bucketIndexValues.get(i),
                        map.getBucketIndexToBucketLocation().get(i));
                assertEquals(
                        bucketIndexValues.get(i),
                        map.getBucketIndexToBucketLocation().get(initialNumOfBuckets + i));
            }
        }
    }

    @Test
    void getAfterResize() throws Exception {
        try (HalfDiskHashMap map = createNewTempMap("getAfterResize", 200)) {
            final int initialNumOfBuckets = calcExpectedNumOfBuckets(200);
            map.startWriting();
            for (int i = 0; i < 100; i++) {
                // These two entries should end up in the same bucket, but they will be in
                // different buckets after resize
                map.put(Bytes.wrap(new byte[] {(byte) i, (byte) i}), i, i * 2);
                map.put(Bytes.wrap(new byte[] {(byte) (i + 100), 11}), i + initialNumOfBuckets, i + 2);
            }
            map.endWriting();
            map.resizeIfNeeded(499, 998);
            assertEquals(initialNumOfBuckets * 2, map.getNumOfBuckets());
            for (int i = 0; i < 100; i++) {
                // These two entries should end up in the same bucket, but they will be in
                // different buckets after resize
                assertEquals(i * 2, map.get(Bytes.wrap(new byte[] {(byte) i, (byte) i}), i, -1));
                assertEquals(-1, map.get(Bytes.wrap(new byte[] {(byte) i, -1}), i, -1));
                assertEquals(
                        i + 2, map.get(Bytes.wrap(new byte[] {(byte) (i + 100), 11}), i + initialNumOfBuckets, -1));
            }
        }
    }

    @Test
    void checkBucketsAfterPut() throws Exception {
        try (HalfDiskHashMap map = createNewTempMap("checkBucketsAfterPut", 200)) {
            final int initialNumOfBuckets = calcExpectedNumOfBuckets(200);
            map.startWriting();
            for (int i = 0; i < initialNumOfBuckets; i++) {
                // These two entries should end up in the same bucket, but they will be in
                // different buckets after resize
                map.put(Bytes.wrap(new byte[] {(byte) i, (byte) i}), i, i * 2L);
                map.put(Bytes.wrap(new byte[] {(byte) (i + 100), 11}), i + initialNumOfBuckets, i + 2L);
            }
            map.endWriting();
            final LongList bucketIndex = (LongList) map.getBucketIndexToBucketLocation();
            for (int i = 0; i < initialNumOfBuckets; i++) {
                final BufferedData bucketData = map.getFileCollection().readDataItemUsingIndex(bucketIndex, i);
                assertNotNull(bucketData);
                try (ParsedBucket bucket = new ParsedBucket()) {
                    bucket.readFrom(bucketData);
                    assertEquals(2, bucket.getBucketEntryCount());
                    assertEquals(i * 2L, bucket.findValue(i, Bytes.wrap(new byte[] {(byte) i, (byte) i}), -1));
                    assertEquals(-1, bucket.findValue(i, Bytes.wrap(new byte[] {(byte) i, -1}), -1));
                    assertEquals(
                            i + 2L,
                            bucket.findValue(
                                    i + initialNumOfBuckets, Bytes.wrap(new byte[] {(byte) (i + 100), 11}), -1));
                    assertEquals(
                            -1, bucket.findValue(i + initialNumOfBuckets, Bytes.wrap(new byte[] {(byte) i, 11}), -1));
                }
            }
        }
    }

    @Test
    void checkBucketsAfterResize() throws Exception {
        try (HalfDiskHashMap map = createNewTempMap("checkBucketsAfterResize", 200)) {
            final int initialNumOfBuckets = calcExpectedNumOfBuckets(200);
            map.startWriting();
            for (int i = 0; i < initialNumOfBuckets; i++) {
                // These two entries should end up in the same bucket, but they will be in
                // different buckets after resize
                map.put(Bytes.wrap(new byte[] {(byte) i}), i, i * 3L);
                map.put(Bytes.wrap(new byte[] {(byte) (i + 100)}), i + initialNumOfBuckets, i + 3L);
            }
            map.endWriting();
            map.resizeIfNeeded(499, 998);
            final LongList bucketIndex = (LongList) map.getBucketIndexToBucketLocation();
            for (int i = 0; i < initialNumOfBuckets; i++) {
                final BufferedData bucketData = map.getFileCollection().readDataItemUsingIndex(bucketIndex, i);
                assertNotNull(bucketData);
                try (ParsedBucket bucket = new ParsedBucket()) {
                    bucket.readFrom(bucketData);
                    assertEquals(i, bucket.getBucketIndex());
                    // Both i and i+initialNumOfBuckets entries are still there
                    assertEquals(2, bucket.getBucketEntryCount());
                    assertEquals(i * 3L, bucket.findValue(i, Bytes.wrap(new byte[] {(byte) i}), -1));
                }
            }
            for (int i = initialNumOfBuckets; i < initialNumOfBuckets * 2; i++) {
                // The old index (before resize)
                final int ei = i - initialNumOfBuckets;
                final BufferedData bucketData = map.getFileCollection().readDataItemUsingIndex(bucketIndex, i);
                assertNotNull(bucketData);
                try (ParsedBucket bucket = new ParsedBucket()) {
                    bucket.readFrom(bucketData);
                    // The bucket still has the old index
                    assertEquals(ei, bucket.getBucketIndex());
                    // Both i and i+initialNumOfBuckets entries are still there
                    assertEquals(2, bucket.getBucketEntryCount());
                    assertEquals(
                            ei + 3L,
                            bucket.findValue(ei + initialNumOfBuckets, Bytes.wrap(new byte[] {(byte) (ei + 100)}), -1));
                }
            }
        }
    }

    @Test
    void checkBucketsAfterResizeAndUpdate() throws Exception {
        try (HalfDiskHashMap map = createNewTempMap("checkBucketsAfterResizeAndUpdate", 200)) {
            final int initialNumOfBuckets = calcExpectedNumOfBuckets(200);
            map.startWriting();
            for (int i = 0; i < initialNumOfBuckets; i++) {
                // These two entries should end up in the same bucket, but they will be in
                // different buckets after resize
                map.put(Bytes.wrap(new byte[] {(byte) i}), i, i * 3L);
                map.put(Bytes.wrap(new byte[] {(byte) (i + 100)}), i + initialNumOfBuckets, i + 3L);
            }
            map.endWriting();
            map.resizeIfNeeded(499, 998);
            // Update all values, now they will be put to different buckets, and old buckets must be sanitized
            map.startWriting();
            for (int i = 0; i < initialNumOfBuckets; i++) {
                // These two entries should end up in the same bucket, but they will be in
                // different buckets after resize
                map.put(Bytes.wrap(new byte[] {(byte) i}), i, i * 4L);
                map.put(Bytes.wrap(new byte[] {(byte) (i + 100)}), i + initialNumOfBuckets, i + 4L);
            }
            map.endWriting();
            final LongList bucketIndex = (LongList) map.getBucketIndexToBucketLocation();
            for (int i = 0; i < initialNumOfBuckets; i++) {
                final BufferedData bucketData = map.getFileCollection().readDataItemUsingIndex(bucketIndex, i);
                assertNotNull(bucketData);
                try (ParsedBucket bucket = new ParsedBucket()) {
                    bucket.readFrom(bucketData);
                    assertEquals(i, bucket.getBucketIndex());
                    assertEquals(1, bucket.getBucketEntryCount());
                    assertEquals(i * 4L, bucket.findValue(i, Bytes.wrap(new byte[] {(byte) i}), -1));
                    assertEquals(
                            -1,
                            bucket.findValue(i + initialNumOfBuckets, Bytes.wrap(new byte[] {(byte) (i + 100)}), -1));
                }
            }
            for (int i = initialNumOfBuckets; i < initialNumOfBuckets * 2; i++) {
                // The old index (before resize)
                final int ei = i - initialNumOfBuckets;
                final BufferedData bucketData = map.getFileCollection().readDataItemUsingIndex(bucketIndex, i);
                assertNotNull(bucketData);
                try (ParsedBucket bucket = new ParsedBucket()) {
                    bucket.readFrom(bucketData);
                    // The bucket now should have the new index
                    assertEquals(i, bucket.getBucketIndex());
                    assertEquals(1, bucket.getBucketEntryCount());
                    assertEquals(-1, bucket.findValue(ei, Bytes.wrap(new byte[] {(byte) ei}), -1));
                    assertEquals(
                            ei + 4L,
                            bucket.findValue(ei + initialNumOfBuckets, Bytes.wrap(new byte[] {(byte) (ei + 100)}), -1));
                }
            }
        }
    }

    @Test
    void repairAfterResize() throws Exception {
        // Map size is N * 2
        final int N = 250;
        try (HalfDiskHashMap map = createNewTempMap("repairAfterResize", N);
                final MemoryIndexDiskKeyValueStore kvStore = createNewTempKV("repairAfterResize", N * 4)) {
            final int first = N * 2 - 1;
            final int last = N * 4 - 2;
            kvStore.updateValidKeyRange(first, last);
            final int initialNumOfBuckets = calcExpectedNumOfBuckets(N);
            map.startWriting();
            kvStore.startWriting();
            // Add N * 2 entities. Entities in each pair have hash codes with only one (high) bit different
            for (int i = 0; i < N; i++) {
                // KV 1
                final Bytes key1 = Bytes.wrap(intToByteArray(i + 1000));
                final int hash1 = i;
                final long path1 = first + i;
                map.put(key1, hash1, path1);
                final VirtualLeafBytes rec1 = new VirtualLeafBytes(path1, key1, hash1, Bytes.wrap("" + i));
                kvStore.put(path1, rec1::writeTo, rec1.getSizeInBytes());
                // KV 2
                final Bytes key2 = Bytes.wrap(intToByteArray(i + 1001));
                final int hash2 = i + initialNumOfBuckets;
                final long path2 = first + N + i;
                map.put(key2, hash2, path2);
                final VirtualLeafBytes rec2 = new VirtualLeafBytes(path2, key2, hash2, Bytes.wrap("" + i));
                kvStore.put(path2, rec2::writeTo, rec2.getSizeInBytes());
            }
            map.endWriting();
            kvStore.endWriting();
            map.resizeIfNeeded(first, last);
            map.repair(first, last, kvStore);
            // Check that all data is still in the map
            for (int i = 0; i < initialNumOfBuckets; i++) {
                final Bytes key1 = Bytes.wrap(intToByteArray(i + 1000));
                final int hash1 = i;
                final long path1 = first + i;
                assertEquals(path1, map.get(key1, hash1, -1));
                final Bytes key2 = Bytes.wrap(intToByteArray(i + 1001));
                final int hash2 = i + initialNumOfBuckets;
                final long path2 = first + N + i;
                assertEquals(path2, map.get(key2, hash2, -1));
            }
        }
    }

    private int calcExpectedNumOfBuckets(final long mapSizeHint) {
        int goodAverageBucketEntryCount =
                CONFIGURATION.getConfigData(MerkleDbConfig.class).goodAverageBucketEntryCount();
        return Integer.highestOneBit(Math.toIntExact(mapSizeHint / goodAverageBucketEntryCount)) * 2;
    }

    private byte[] intToByteArray(final int i) {
        return new byte[] {(byte) (i & 255), (byte) ((i >> 8) & 255), (byte) ((i >> 16) & 255), (byte) ((i >> 24) & 255)
        };
    }

    private static void printTestUpdate(long start, long count, String msg) {
        long took = System.currentTimeMillis() - start;
        double timeSeconds = (double) took / 1000d;
        double perSecond = (double) count / timeSeconds;
        System.out.printf("%s : [%,d] at %,.0f per/sec, took %,.2f seconds\n", msg, count, perSecond, timeSeconds);
    }

    public static class CollidableFixedLongKey extends ExampleLongKeyFixedSize {
        private static long CLASS_ID = 0x7b305246cffbf8efL;

        public CollidableFixedLongKey() {
            super();
        }

        public CollidableFixedLongKey(final long value) {
            super(value);
        }

        @Override
        public int hashCode() {
            return (int) getValue() % 100;
        }

        @Override
        public long getClassId() {
            return CLASS_ID;
        }

        @Override
        public void deserialize(final SerializableDataInputStream in, final int dataVersion) throws IOException {
            assertEquals(getVersion(), dataVersion);
            super.deserialize(in, dataVersion);
        }
    }
}
