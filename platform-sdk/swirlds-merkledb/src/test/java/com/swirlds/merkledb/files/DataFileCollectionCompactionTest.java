// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.files;

import static com.swirlds.merkledb.files.DataFileCommon.getSizeOfFiles;
import static com.swirlds.merkledb.files.DataFileCommon.getSizeOfFilesByPath;
import static com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils.CONFIGURATION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.swirlds.merkledb.collections.CASableLongIndex;
import com.swirlds.merkledb.collections.LongList;
import com.swirlds.merkledb.collections.LongListHeap;
import com.swirlds.merkledb.collections.LongListOffHeap;
import com.swirlds.merkledb.collections.LongListSegment;
import com.swirlds.merkledb.config.MerkleDbConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DataFileCollectionCompactionTest {

    // Would be nice to add a test to make sure files get deleted
    private static final MerkleDbConfig MERKLE_DB_CONFIG = CONFIGURATION.getConfigData(MerkleDbConfig.class);

    /**
     * Temporary directory provided by JUnit
     */
    @TempDir
    Path tempFileDir;

    private static final long APPLE = 1001;
    private static final long BANANA = 1002;
    private static final long CHERRY = 1003;
    private static final long DATE = 1004;
    private static final long EGGPLANT = 1005;
    private static final long FIG = 1006;
    private static final long CUTTLEFISH = 2003;

    private static long storeDataItem(final DataFileCollection coll, long[] values) throws IOException {
        return coll.storeDataItem(
                o -> {
                    for (final long value : values) {
                        o.writeLong(value);
                    }
                },
                values.length * Long.BYTES);
    }

    private static long[] readDataItem(final DataFileCollection coll, final long location) throws IOException {
        final BufferedData data = coll.readDataItem(location);
        if (data == null) {
            return null;
        }
        final long[] items = new long[Math.toIntExact(data.remaining() / Long.BYTES)];
        for (int i = 0; i < items.length; i++) {
            items[i] = data.readLong();
        }
        return items;
    }

    @Test
    void testMerge() throws Exception {
        final Map<Long, Long> index = new HashMap<>();
        String storeName = "mergeTest";
        final var coll = new DataFileCollection(MERKLE_DB_CONFIG, tempFileDir.resolve(storeName), storeName, null);

        coll.startWriting();
        index.put(1L, storeDataItem(coll, new long[] {1, APPLE}));
        index.put(2L, storeDataItem(coll, new long[] {2, BANANA}));
        coll.updateValidKeyRange(1, 2);
        coll.endWriting();

        coll.startWriting();
        index.put(3L, storeDataItem(coll, new long[] {3, APPLE}));
        index.put(4L, storeDataItem(coll, new long[] {4, CHERRY}));
        coll.updateValidKeyRange(2, 4);
        coll.endWriting();

        coll.startWriting();
        index.put(4L, storeDataItem(coll, new long[] {4, CUTTLEFISH}));
        index.put(5L, storeDataItem(coll, new long[] {5, BANANA}));
        index.put(6L, storeDataItem(coll, new long[] {6, DATE}));
        coll.updateValidKeyRange(3, 6);
        coll.endWriting();

        coll.startWriting();
        index.put(7L, storeDataItem(coll, new long[] {7, APPLE}));
        index.put(8L, storeDataItem(coll, new long[] {8, EGGPLANT}));
        index.put(9L, storeDataItem(coll, new long[] {9, CUTTLEFISH}));
        index.put(10L, storeDataItem(coll, new long[] {10, FIG}));
        coll.updateValidKeyRange(5, 10);
        coll.endWriting();

        final CASableLongIndex indexUpdater = new CASableLongIndex() {
            public long get(long key) {
                return index.get(key);
            }

            public boolean putIfEqual(long key, long oldValue, long newValue) {
                assertTrue(key >= 5, "We should not update below firstLeafPath");

                if (index.containsKey(key) && index.get(key).equals(oldValue)) {
                    index.put(key, newValue);
                    return true;
                }
                return false;
            }

            public <T extends Throwable> boolean forEach(final LongAction<T> action, BooleanSupplier cond)
                    throws InterruptedException, T {
                for (final Map.Entry<Long, Long> e : index.entrySet()) {
                    action.handle(e.getKey(), e.getValue());
                }
                return true;
            }
        };
        final var compactor = new DataFileCompactor(coll, indexUpdater, null, null, null, null);
        compactor.compactFiles(indexUpdater, getFilesToMerge(coll), 1);

        long prevKey = -1;
        for (int i = 5; i < 10; i++) {
            Long location = index.get((long) i);
            assertNotNull(location, "failed on " + i);

            long[] data = readDataItem(coll, location);
            assertNotNull(data);
            final var key = data[0];
            final var value = data[1];
            assertTrue(key > prevKey, "failed on " + i + " key=" + key + ", prev=" + prevKey + ", value=" + value);
            prevKey = key;
        }

        assertEquals(BANANA, readDataItem(coll, index.get(5L))[1], "Not a BANANA");
        assertEquals(DATE, readDataItem(coll, index.get(6L))[1], "Not a DATE");
        assertEquals(APPLE, readDataItem(coll, index.get(7L))[1], "Not a APPLE");
        assertEquals(EGGPLANT, readDataItem(coll, index.get(8L))[1], "Not a EGGPLANT");
        assertEquals(CUTTLEFISH, readDataItem(coll, index.get(9L))[1], "Not a CUTTLEFISH");
        assertEquals(FIG, readDataItem(coll, index.get(10L))[1], "Not a FIG");

        assertEquals(1, coll.getAllCompletedFiles().size(), "Too many files left over");

        final var dataFileReader = coll.getAllCompletedFiles().getFirst();
        final var itr = dataFileReader.createIterator();
        prevKey = -1;
        while (itr.next()) {
            final long key = itr.getDataItemData().readLong();
            assertTrue(key > prevKey, "Keys must be sorted in ascending order");
            assertTrue(key >= 5, "We should not update below firstLeafPath");
            prevKey = key;
        }
    }

    // using RepeatedTest to increase a chance of discovering a thread race, as this test is timing-sensitive
    @RepeatedTest(10)
    @DisplayName("Re-merge files without deletion")
    void testDoubleMerge() throws Exception {
        final int MAXKEYS = 100;
        final long[] index = new long[MAXKEYS];
        String storeName = "testDoubleMerge";
        final Path testDir = tempFileDir.resolve(storeName);
        final DataFileCollection store = new DataFileCollection(MERKLE_DB_CONFIG, testDir, storeName, null);

        final int numFiles = 2;
        for (long i = 0; i < numFiles; i++) {
            store.startWriting();
            for (int j = 0; j < MAXKEYS; ++j) {
                index[j] = storeDataItem(store, new long[] {j, i * j});
            }
            store.updateValidKeyRange(0, index.length);
            store.endWriting();
        }

        final CountDownLatch compactionAboutComplete = new CountDownLatch(1);
        final CountDownLatch snapshotComplete = new CountDownLatch(1);

        // Do merge in a separate thread but pause before files are deleted
        final Thread compactionThread = new Thread(() -> {
            final AtomicInteger updateCount = new AtomicInteger(0);
            final List<DataFileReader> filesToMerge = getFilesToMerge(store);
            final CASableLongIndex indexUpdater = new CASableLongIndex() {
                public long get(long key) {
                    return index[(int) key];
                }

                public boolean putIfEqual(long key, long oldValue, long newValue) {
                    assertEquals(index[(int) key], oldValue, "Index value does not match");
                    index[(int) key] = newValue;
                    if (updateCount.incrementAndGet() == MAXKEYS) {
                        compactionAboutComplete.countDown();
                        try {
                            snapshotComplete.await();
                        } catch (final InterruptedException e) {
                            throw new RuntimeException(e);
                        }
                    }
                    return true;
                }

                public <T extends Throwable> boolean forEach(final LongAction<T> action, BooleanSupplier cond)
                        throws InterruptedException, T {
                    for (int i = 0; i < MAXKEYS; i++) {
                        action.handle(i, index[i]);
                    }
                    return true;
                }
            };

            final DataFileCompactor compactor = new DataFileCompactor(store, indexUpdater, null, null, null, null);

            try {
                compactor.compactFiles(indexUpdater, filesToMerge, 1);
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        });
        compactionThread.start();

        compactionAboutComplete.await();

        // Create a snapshot that includes files being merged and their resulting file
        final Path snapshot = testDir.resolve("snapshot");
        store.snapshot(snapshot);
        snapshotComplete.countDown();

        // Create a new data collection from the snapshot
        final String[] index2 = new String[MAXKEYS];
        final DataFileCollection store2 = new DataFileCollection(
                MERKLE_DB_CONFIG,
                snapshot,
                storeName,
                (dataLocation, data) ->
                        index2[(int) data.readLong()] = DataFileCommon.dataLocationToString(dataLocation));

        // Merge all files with redundant records
        final List<DataFileReader> filesToMerge = getFilesToMerge(store2);
        try {
            final CASableLongIndex indexUpdater = new CASableLongIndex() {
                public long get(long key) {
                    return index[(int) key];
                }

                public boolean putIfEqual(long key, long oldValue, long newValue) {
                    final String oldDataLocation = DataFileCommon.dataLocationToString(oldValue);
                    assertEquals(index2[(int) key], oldDataLocation, "Index value does not match");
                    index2[(int) key] = DataFileCommon.dataLocationToString(newValue);
                    return true;
                }

                public <T extends Throwable> boolean forEach(final LongAction<T> action, BooleanSupplier cond)
                        throws InterruptedException, T {
                    for (int i = 0; i < MAXKEYS; i++) {
                        action.handle(i, index[i]);
                    }
                    return true;
                }
            };

            final DataFileCompactor compactor = new DataFileCompactor(store2, indexUpdater, null, null, null, null);

            if (filesToMerge.size() > 1) {
                compactor.compactFiles(indexUpdater, filesToMerge, 1);
            }
        } finally {
            compactionThread.join();
        }
    }

    @Test
    @DisplayName("Merge files concurrently with writing new files")
    void testMergeAndFlush() throws Exception {
        final int MAXKEYS = 100;
        final int NUM_UPDATES = 5;
        final AtomicLongArray index = new AtomicLongArray(MAXKEYS);
        String storeName = "testMergeAndFlush";
        final Path testDir = tempFileDir.resolve(storeName);

        final DataFileCollection store = new DataFileCollection(MERKLE_DB_CONFIG, testDir, storeName, null);

        try {
            for (long i = 0; i < 2 * NUM_UPDATES; i++) {
                // Start writing a new copy
                store.startWriting();
                for (int j = 0; j < MAXKEYS; ++j) {
                    // Update half the keys
                    if ((j + i) % 2 == 0) continue;
                    long[] dataItem = readDataItem(store, index.get(j));
                    if (dataItem == null) {
                        dataItem = new long[] {j, 0};
                    }
                    dataItem[1] += j;
                    index.set(j, storeDataItem(store, dataItem));
                }

                // Intervene with merging earlier copies to disrupt file index order
                final List<DataFileReader> filesToMerge = getFilesToMerge(store);
                final CASableLongIndex indexUpdater = new CASableLongIndex() {
                    public long get(long key) {
                        return index.get((int) key);
                    }

                    public boolean putIfEqual(long key, long oldValue, long newValue) {
                        return index.compareAndSet((int) key, oldValue, newValue);
                    }

                    public <T extends Throwable> boolean forEach(final LongAction<T> action, BooleanSupplier cond)
                            throws InterruptedException, T {
                        for (int i = 0; i < index.length(); i++) {
                            action.handle(i, index.get(i));
                        }
                        return true;
                    }
                };

                if (filesToMerge.size() > 1) {
                    final DataFileCompactor compactor =
                            new DataFileCompactor(store, indexUpdater, null, null, null, null);
                    try {
                        compactor.compactFiles(indexUpdater, filesToMerge, 1);
                    } catch (Exception ex) {
                        ex.printStackTrace();
                    }
                }

                // Finish writing the current copy, which has newer data but an older index than
                // the merged file
                store.updateValidKeyRange(0, index.length());
                store.endWriting();
            }

            // Validate the result
            for (int j = 0; j < MAXKEYS; ++j) {
                final long[] dataItem = readDataItem(store, index.get(j));
                assertEquals(j, dataItem[0]);
                assertEquals(NUM_UPDATES * j, dataItem[1]);
            }
        } finally {
            store.close();
        }
    }

    @Test
    @DisplayName("Restore from disrupted index order")
    void testRestore() throws Exception {
        final int MAX_KEYS = 100;
        final int NUM_UPDATES = 3;
        final AtomicLongArray index = new AtomicLongArray(MAX_KEYS);
        String storeName = "testRestore";
        final Path testDir = tempFileDir.resolve(storeName);

        final DataFileCollection store = new DataFileCollection(MERKLE_DB_CONFIG, testDir, storeName, null);
        try {
            // Initial values
            store.startWriting();
            for (int j = 0; j < MAX_KEYS; ++j) {
                index.set(j, storeDataItem(store, new long[] {j, j}));
            }
            store.updateValidKeyRange(0, index.length());
            store.endWriting();

            // Write new copies
            for (long i = 1; i < NUM_UPDATES; i++) {
                store.startWriting();
                for (int j = 0; j < MAX_KEYS; ++j) {
                    long[] dataItem = readDataItem(store, index.get(j));
                    assertNotNull(dataItem);
                    dataItem[1] += j;
                    index.set(j, storeDataItem(store, dataItem));
                }

                // Intervene with merging earlier copies to disrupt file index order
                final List<DataFileReader> filesToMerge = getFilesToMerge(store);
                final CASableLongIndex indexUpdater = new CASableLongIndex() {
                    public long get(long key) {
                        return index.get((int) key);
                    }

                    public boolean putIfEqual(long key, long oldValue, long newValue) {
                        return index.compareAndSet((int) key, oldValue, newValue);
                    }

                    public <T extends Throwable> boolean forEach(final LongAction<T> action, BooleanSupplier cond)
                            throws InterruptedException, T {
                        for (int i = 0; i < index.length(); i++) {
                            action.handle(i, index.get(i));
                        }
                        return true;
                    }
                };

                if (filesToMerge.size() > 1) {
                    final DataFileCompactor compactor =
                            new DataFileCompactor(store, indexUpdater, null, null, null, null);
                    try {
                        compactor.compactFiles(indexUpdater, filesToMerge, 1);
                    } catch (Exception ex) {
                        ex.printStackTrace();
                    }
                }

                // Finish writing the current copy, which has newer data but an older index than
                // the merged file
                store.updateValidKeyRange(0, index.length());
                store.endWriting();
            }

            // Restore from all files
            final AtomicLongArray reindex = new AtomicLongArray(MAX_KEYS);
            final DataFileCollection restore = new DataFileCollection(
                    MERKLE_DB_CONFIG,
                    testDir,
                    storeName,
                    (dataLocation, data) -> reindex.set((int) data.readLong(), dataLocation));

            // Validate the result
            try {
                for (int j = 0; j < MAX_KEYS; ++j) {
                    final long[] dataItem = readDataItem(store, reindex.get(j));
                    assertNotNull(dataItem);
                    assertEquals(j, dataItem[0]);
                    assertEquals(NUM_UPDATES * j, dataItem[1]);
                }
            } finally {
                restore.close();
            }
        } finally {
            store.close();
        }
    }

    @ParameterizedTest
    @DisplayName("Snapshot + update in parallel with compaction")
    @ValueSource(ints = {0, 1, 2, 3})
    void testMergeUpdateSnapshotRestore(final int testParam) throws Throwable {
        final int numFiles = 10;
        final int numValues = 1000;
        String storeName = "testMergeSnapshotRestore";
        final Path testDir = tempFileDir.resolve(storeName);
        Files.createDirectories(testDir);
        try (final LongListSegment index = new LongListSegment(numValues, numFiles * numValues, 0)) {
            index.updateValidRange(0, numFiles * numValues - 1);
            final DataFileCollection store = new DataFileCollection(MERKLE_DB_CONFIG, testDir, storeName, null);
            final DataFileCompactor compactor = new DataFileCompactor(store, index, null, null, null, null);
            // Create a few files initially
            for (int i = 0; i < numFiles; i++) {
                store.startWriting();
                for (int j = 0; j < numValues; j++) {
                    final long dataLocation = storeDataItem(store, new long[] {i * numValues + j, i * numValues + j});
                    index.put(i * numValues + j, dataLocation);
                }
                store.updateValidKeyRange(0, index.size());
                store.endWriting();
            }
            // Start compaction
            // Test scenario 0: start merging with mergingPaused semaphore locked, so merging
            // won't proceed more than to the first index update
            if (testParam == 0) {
                compactor.pauseCompaction();
            }
            final int filesCountBeforeMerge = store.getAllCompletedFiles().size();
            assertEquals(numFiles, filesCountBeforeMerge);
            final CountDownLatch mergeCompleteLatch = new CountDownLatch(1);
            final CountDownLatch newFileWriteCompleteLatch = new CountDownLatch(1);
            final ExecutorService exec = Executors.newSingleThreadExecutor();
            final Future<?> f = exec.submit(() -> {
                try {
                    final List<DataFileReader> filesToMerge = getFilesToMerge(store);
                    // Data file collection may create a new file before the compaction starts
                    assertTrue(filesToMerge.size() == numFiles || filesToMerge.size() == numFiles + 1);
                    compactor.compactFiles(index, filesToMerge, 1);
                    // Wait for the new file to be available. Without this wait, there
                    // may be 1 or 2
                    // files available for merge, as this thread may be complete before
                    // endWriting()
                    // below is called
                    newFileWriteCompleteLatch.await();
                    // 2 = new file with updated values below + one or two files created
                    // during merge,
                    // it depends on where pauseCompaction() happens inside
                    // compactFiles() above
                    // depending on the test scenario there may be 1, 2 or 3 files
                    assertTrue(
                            List.of(1, 2, 3)
                                    .contains(store.getAllCompletedFiles().size()),
                            "Unexpected files after compaction: " + store.getAllCompletedFiles());
                } catch (final Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    // Make sure the main thread is unblocked regardless. If any
                    // exceptions are thrown
                    // above, they will be checked in the end of the test
                    mergeCompleteLatch.countDown();
                }
            });
            // Test scenario 1: let compaction and update run in parallel for a while
            if (testParam == 1) {
                compactor.pauseCompaction();
            }
            // Update values as if it was a flush before a snapshot. It will create a new file in the
            // store
            // in parallel to writing to another file during compaction
            store.startWriting();
            for (int i = 0; i < numFiles; i++) {
                for (int j = 0; j < numValues; j++) {
                    final long dataLocation =
                            storeDataItem(store, new long[] {i * numValues + j, i * numValues + j + 1});
                    index.put(i * numValues + j, dataLocation);
                }
            }
            store.updateValidKeyRange(0, index.size());
            store.endWriting();
            newFileWriteCompleteLatch.countDown();
            // Test scenario 2: lock the semaphore just before taking a snapshot. Compaction may still
            // be
            // in progress or may be completed
            if (testParam == 2) {
                compactor.pauseCompaction();
            }
            // Test scenario 3: wait for compaction to complete, then take a snapshot
            if (testParam == 3) {
                mergeCompleteLatch.await();
                compactor.pauseCompaction();
            }
            // Snapshot. It's in the middle of compaction, as compaction should be stopped at this point
            // waiting
            // to acquire mergingPaused semaphore
            final Path snapshotDir = tempFileDir.resolve("testMergeSnapshotRestore-snapshot");
            Files.createDirectories(snapshotDir);
            index.writeToFile(snapshotDir.resolve("index.ll"));
            store.snapshot(snapshotDir);
            // Release the semaphore to unpause merging and wait for it to complete
            compactor.resumeCompaction();
            if (testParam != 3) {
                mergeCompleteLatch.await();
            }
            // Close the store
            store.close();

            // Restore
            final LongListOffHeap index2 = new LongListOffHeap(
                    snapshotDir.resolve("index.ll"), numValues, numFiles * numValues, 0, CONFIGURATION);
            final DataFileCollection store2 = new DataFileCollection(MERKLE_DB_CONFIG, snapshotDir, storeName, null);
            // Check index size
            assertEquals(numFiles * numValues, index2.size());
            // Check the values
            for (int i = 0; i < index2.size(); i++) {
                final long dataLocation = index2.get(i);
                final long[] value = readDataItem(store2, dataLocation);
                assertNotNull(value);
                assertEquals(i, value[0]);
                assertEquals(i + 1, value[1]);
            }
            store2.close();

            // Check exceptions from the compaction thread
            f.get();
        }
    }

    @Test
    @DisplayName("Restore with inconsistent index")
    void testInconsistentIndex() throws Exception {
        final int MAXKEYS = 100;
        final LongList index = new LongListOffHeap(MAXKEYS / 10, MAXKEYS, 0);
        String storeName = "testInconsistentIndex";
        final Path testDir = tempFileDir.resolve(storeName);
        final DataFileCollection store = new DataFileCollection(MERKLE_DB_CONFIG, testDir, storeName, null);
        final DataFileCompactor compactor = new DataFileCompactor(store, index, null, null, null, null);

        final int numFiles = 10; // should be greater than min number of files to compact
        index.updateValidRange(0, MAXKEYS - 1);
        for (long i = 0; i < numFiles; i++) {
            store.startWriting();
            for (int j = 0; j < MAXKEYS; ++j) {
                index.put(j, storeDataItem(store, new long[] {j, i * j}));
            }
            store.updateValidKeyRange(0, index.size());
            store.endWriting();
        }

        final Path snapshot = testDir.resolve("snapshot");
        final Path savedIndex = testDir.resolve("index.ll");

        final AtomicInteger updateCount = new AtomicInteger(0);
        final List<DataFileReader> filesToMerge = getFilesToMerge(store);
        final CASableLongIndex indexUpdater = new CASableLongIndex() {
            public long get(long key) {
                return index.get(key);
            }

            public boolean putIfEqual(long key, long oldValue, long newValue) {
                assertTrue(
                        index.putIfEqual(key, oldValue, newValue),
                        String.format(
                                "Index values for key %d do not match: expected 0x%x actual 0x%x",
                                key, oldValue, index.get(key)));
                if (updateCount.incrementAndGet() == MAXKEYS / 2) {
                    // Start a snapshot while the index is being updated
                    try {
                        System.err.println("SAVED");
                        index.writeToFile(savedIndex);
                        store.snapshot(snapshot);
                    } catch (IOException ex) {
                        ex.printStackTrace();
                    }
                }
                return true;
            }

            public <T extends Throwable> boolean forEach(final LongAction<T> action, BooleanSupplier cond)
                    throws InterruptedException, T {
                return index.forEach(action, cond);
            }
        };

        try {
            compactor.compactFiles(indexUpdater, filesToMerge, 1);
            store.close();
        } catch (Exception ex) {
            ex.printStackTrace();
        }

        // Create a new data collection from the snapshot
        LongList index2 = new LongListOffHeap(savedIndex, MAXKEYS / 10, MAXKEYS, 0, CONFIGURATION);
        final DataFileCollection store2 = new DataFileCollection(MERKLE_DB_CONFIG, snapshot, storeName, null);

        // Merge all files with redundant records
        final List<DataFileReader> filesToMerge2 = getFilesToMerge(store2);
        final CASableLongIndex indexUpdater2 = new CASableLongIndex() {
            public long get(long key) {
                return index2.get(key);
            }

            public boolean putIfEqual(long key, long oldValue, long newValue) {
                assertTrue(
                        index2.putIfEqual(key, oldValue, newValue),
                        String.format(
                                "Index values for key %d do not match: expected 0x%x actual 0x%x",
                                key, oldValue, index2.get(key)));
                return true;
            }

            public <T extends Throwable> boolean forEach(final LongAction<T> action, BooleanSupplier cond)
                    throws InterruptedException, T {
                return index2.forEach(action, cond);
            }
        };

        try {
            compactor.compactFiles(indexUpdater2, filesToMerge2, 1);
        } finally {
            store2.close();
        }
    }

    // ========================================================================
    // HDHM bucket deduplication during compaction
    // ========================================================================

    @Test
    @DisplayName("Compaction with dedup: mirrored entries produce a single copy")
    void testCompactionDeduplicatesMirroredEntries() throws Exception {
        final int bucketCount = 8;
        final int half = bucketCount / 2;
        final String storeName = "testDedup";
        final Path testDir = tempFileDir.resolve(storeName);
        final int metadataSizeDiff = 4;
        final DataFileCollection store = new DataFileCollection(MERKLE_DB_CONFIG, testDir, storeName, null);

        // Create a LongList to act as bucket index
        final LongListHeap index = new LongListHeap(
                MERKLE_DB_CONFIG.longListChunkSize(), bucketCount, MERKLE_DB_CONFIG.longListReservedBufferSize());
        index.updateValidRange(0, bucketCount - 1);

        // Write one file with 4 buckets
        store.startWriting();
        final long[] locations = new long[half];
        for (int i = 0; i < half; i++) {
            locations[i] = storeDataItem(store, new long[] {i, i * 100});
        }
        store.updateValidKeyRange(0, bucketCount);
        store.endWriting();

        // Set up index: lower half points to data, upper half mirrors lower (unsanitized doubling)
        for (int i = 0; i < half; i++) {
            index.put(i, locations[i]);
            index.put(i + half, locations[i]); // mirrored
        }

        // Compact with dedup enabled
        final DataFileCompactor compactor =
                new DataFileCompactor(store, index, null, null, null, null, true, bucketCount);

        final List<DataFileReader> filesToCompact = store.getAllCompletedFiles();
        final List<Path> newFiles = compactor.compactFiles(index, filesToCompact, 1);

        // Verify: output should not be larger than input
        final long inputSize = getSizeOfFiles(filesToCompact);
        final long outputSize = getSizeOfFilesByPath(newFiles);
        assertTrue(
                outputSize <= inputSize + metadataSizeDiff,
                "Output (" + outputSize + ") should not exceed input (" + inputSize + ") with dedup");

        // Verify: both halves of the index point to the SAME new location
        for (int i = 0; i < half; i++) {
            final long low = index.get(i, 0);
            final long high = index.get(i + half, 0);
            assertEquals(
                    low, high, "Mirrored entries at " + i + " and " + (i + half) + " should point to same location");
            assertTrue(low != 0, "Entry should not be zero after compaction");
            // Verify the new location points to the new file, not the old one
            assertTrue(low != locations[i], "Entry should point to new file after compaction");
        }

        store.close();
    }

    @Test
    @DisplayName("Compaction with dedup: sanitized entries are both preserved")
    void testCompactionPreservesSanitizedEntries() throws Exception {
        final int bucketCount = 8;
        final int half = bucketCount / 2;
        final String storeName = "testDedupSanitized";
        final Path testDir = tempFileDir.resolve(storeName);
        final DataFileCollection store = new DataFileCollection(MERKLE_DB_CONFIG, testDir, storeName, null);

        final LongListHeap index = new LongListHeap(
                MERKLE_DB_CONFIG.longListChunkSize(), bucketCount, MERKLE_DB_CONFIG.longListReservedBufferSize());
        index.updateValidRange(0, bucketCount - 1);

        // Write one file with 8 distinct buckets (all sanitized — no mirrors)
        store.startWriting();
        final long[] locations = new long[bucketCount];
        for (int i = 0; i < bucketCount; i++) {
            locations[i] = storeDataItem(store, new long[] {i, i * 200});
        }
        store.updateValidKeyRange(0, bucketCount);
        store.endWriting();

        // All entries are distinct — no mirroring
        for (int i = 0; i < bucketCount; i++) {
            index.put(i, locations[i]);
        }

        final DataFileCompactor compactor =
                new DataFileCompactor(store, index, null, null, null, null, true, bucketCount);

        final List<DataFileReader> filesToCompact = store.getAllCompletedFiles();
        compactor.compactFiles(index, filesToCompact, 1);

        // Verify: all 8 entries should point to distinct new locations
        final Set<Long> newLocations = new HashSet<>();
        for (int i = 0; i < bucketCount; i++) {
            final long loc = index.get(i, 0);
            assertTrue(loc != 0, "Entry " + i + " should not be zero");
            assertTrue(loc != locations[i], "Entry " + i + " should point to new file");
            newLocations.add(loc);
        }
        assertEquals(
                bucketCount,
                newLocations.size(),
                "All entries should have distinct locations (no dedup for sanitized entries)");

        store.close();
    }

    @Test
    @DisplayName("Compaction with dedup: mixed mirrored and sanitized entries")
    void testCompactionDedupMixedEntries() throws Exception {
        final int bucketCount = 8;
        final int half = bucketCount / 2;
        final int metadataSizeDiff = 4;
        String storeName = "testDedupMixed";
        final Path testDir = tempFileDir.resolve(storeName);
        final DataFileCollection store = new DataFileCollection(MERKLE_DB_CONFIG, testDir, storeName, null);

        final LongListHeap index = new LongListHeap(
                MERKLE_DB_CONFIG.longListChunkSize(), bucketCount, MERKLE_DB_CONFIG.longListReservedBufferSize());
        index.updateValidRange(0, bucketCount - 1);

        // Write one file with 6 data items
        store.startWriting();
        final long[] locations = new long[6];
        for (int i = 0; i < 6; i++) {
            locations[i] = storeDataItem(store, new long[] {i, i * 300});
        }
        store.updateValidKeyRange(0, bucketCount);
        store.endWriting();

        // Entries 0,1: mirrored (unsanitized)
        index.put(0, locations[0]);
        index.put(half, locations[0]); // mirror of 0
        index.put(1, locations[1]);
        index.put(1 + half, locations[1]); // mirror of 1

        // Entries 2,3: sanitized (distinct locations)
        index.put(2, locations[2]);
        index.put(2 + half, locations[3]); // different from entry 2
        index.put(3, locations[4]);
        index.put(3 + half, locations[5]); // different from entry 3

        final DataFileCompactor compactor =
                new DataFileCompactor(store, index, null, null, null, null, true, bucketCount);

        final List<DataFileReader> filesToCompact = store.getAllCompletedFiles();
        final List<Path> newFiles = compactor.compactFiles(index, filesToCompact, 1);

        // Mirrored entries should point to same new location
        assertEquals(index.get(0, 0), index.get(half, 0), "Mirrored pair 0 should share location");
        assertEquals(index.get(1, 0), index.get(1 + half, 0), "Mirrored pair 1 should share location");

        // Sanitized entries should point to distinct new locations
        assertNotEquals(index.get(2, 0), index.get(2 + half, 0), "Sanitized pair 2 should have distinct locations");
        assertNotEquals(index.get(3, 0), index.get(3 + half, 0), "Sanitized pair 3 should have distinct locations");

        // Output should be smaller than without dedup (6 items written instead of 8)
        final long inputSize = getSizeOfFiles(filesToCompact);
        final long outputSize = getSizeOfFilesByPath(newFiles);
        assertTrue(
                outputSize <= inputSize + metadataSizeDiff,
                "Output should not exceed input with dedup on mixed entries");

        store.close();
    }

    private static List<DataFileReader> getFilesToMerge(DataFileCollection store) {
        return store.getAllCompletedFiles();
    }
}
