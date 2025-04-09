// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.files.hashmap;

import static com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils.CONFIGURATION;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

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
import java.util.Random;
import org.hiero.consensus.model.io.streams.SerializableDataInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@SuppressWarnings({"SameParameterValue", "unchecked"})
class HalfDiskHashMapTest {

    /** Temporary directory provided by JUnit */
    @SuppressWarnings("unused")
    @TempDir
    Path tempDirPath;

    // =================================================================================================================
    // Helper Methods
    private HalfDiskHashMap createNewTempMap(FilesTestType testType, int count) throws IOException {
        // create map
        HalfDiskHashMap map = new HalfDiskHashMap(
                CONFIGURATION, count, tempDirPath.resolve(testType.name()), "HalfDiskHashMapTest", null, false);
        map.printStats();
        return map;
    }

    private MemoryIndexDiskKeyValueStore createNewTempKV(FilesTestType testType, int count) throws IOException {
        final MerkleDbConfig merkleDbConfig = CONFIGURATION.getConfigData(MerkleDbConfig.class);
        final LongList index = new LongListHeap(count, CONFIGURATION);
        return new MemoryIndexDiskKeyValueStore(
                merkleDbConfig,
                tempDirPath.resolve(testType.name() + "_kv"),
                "HalfDiskHashMapTestKV",
                null,
                null,
                index);
    }

    private static void createSomeData(
            FilesTestType testType, HalfDiskHashMap map, int start, int count, long dataMultiplier) throws IOException {
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
            FilesTestType testType, HalfDiskHashMap map, int start, int count, long dataMultiplier) throws IOException {
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
        final HalfDiskHashMap map = createNewTempMap(testType, count);
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
        assertEquals(-1, map.get(testType.keySerializer.toBytes(key50), key50.hashCode(), -1), "Expect not to exist");
        assertEquals(-1, map.get(testType.keySerializer.toBytes(key500), key500.hashCode(), -1), "Expect not to exist");
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

    @ParameterizedTest
    @EnumSource(FilesTestType.class)
    void multipleWriteBatchesAndMerge(FilesTestType testType) throws Exception {
        // create map
        final HalfDiskHashMap map = createNewTempMap(testType, 10_000);
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

    @ParameterizedTest
    @EnumSource(FilesTestType.class)
    void updateData(FilesTestType testType) throws Exception {
        // create map
        final HalfDiskHashMap map = createNewTempMap(testType, 1000);
        // create some data
        createSomeData(testType, map, 0, 1000, 1);
        checkData(testType, map, 0, 1000, 1);
        // update some data
        createSomeData(testType, map, 200, 400, 2);
        checkData(testType, map, 0, 200, 1);
        checkData(testType, map, 200, 400, 2);
        checkData(testType, map, 600, 400, 1);
    }

    @Test
    void testOverwritesWithCollision() throws IOException {
        final FilesTestType testType = FilesTestType.fixed;
        try (final HalfDiskHashMap map = createNewTempMap(testType, 1000)) {
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
        final HalfDiskHashMap map = createNewTempMap(testType, 100);

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

        final MemoryIndexDiskKeyValueStore kv = createNewTempKV(testType, 100);
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
