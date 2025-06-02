// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.files;

import static com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils.CONFIGURATION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.swirlds.merkledb.collections.CASableLongIndex;
import com.swirlds.merkledb.collections.LongList;
import com.swirlds.merkledb.collections.LongListOffHeap;
import com.swirlds.merkledb.config.MerkleDbConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@SuppressWarnings("unused")
class DataFileCollectionCompactionTest {

    // Would be nice to add a test to make sure files get deleted
    private static final MerkleDbConfig MERKLE_DB_CONFIG = CONFIGURATION.getConfigData(MerkleDbConfig.class);

    /** Temporary directory provided by JUnit */
    @TempDir
    Path tempFileDir;

    private static final long APPLE = 1001;
    private static final long BANANA = 1002;
    private static final long CHERRY = 1003;
    private static final long DATE = 1004;
    private static final long EGGPLANT = 1005;
    private static final long FIG = 1006;
    private static final long GRAPE = 1007;
    private static final long AARDVARK = 2001;
    private static final long CUTTLEFISH = 2003;
    private static final long FOX = 1006;

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
        final var compactor =
                new DataFileCompactor(MERKLE_DB_CONFIG, storeName, coll, indexUpdater, null, null, null, null) {
                    @Override
                    int getMinNumberOfFilesToCompact() {
                        return 2;
                    }
                };
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

    @Test
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
        new Thread(() -> {
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

                    final DataFileCompactor compactor =
                            new DataFileCompactor(
                                    MERKLE_DB_CONFIG, storeName, store, indexUpdater, null, null, null, null) {
                                @Override
                                int getMinNumberOfFilesToCompact() {
                                    return 2;
                                }
                            };

                    try {
                        compactor.compactFiles(indexUpdater, filesToMerge, 1);
                        store.close();
                    } catch (Exception ex) {
                        ex.printStackTrace();
                    }
                })
                .start();

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

            final DataFileCompactor compactor =
                    new DataFileCompactor(MERKLE_DB_CONFIG, storeName, store, indexUpdater, null, null, null, null) {
                        @Override
                        int getMinNumberOfFilesToCompact() {
                            return 2;
                        }
                    };

            compactor.compactFiles(indexUpdater, filesToMerge, 1);
        } finally {
            store2.close();
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
                    final DataFileCompactor compactor = new DataFileCompactor(
                            MERKLE_DB_CONFIG, storeName, store, indexUpdater, null, null, null, null);
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
                    final DataFileCompactor compactor = new DataFileCompactor(
                            MERKLE_DB_CONFIG, storeName, store, indexUpdater, null, null, null, null);
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
        final LongListOffHeap index = new LongListOffHeap(numValues, numFiles * numValues, 0);
        index.updateValidRange(0, numFiles * numValues - 1);
        final DataFileCollection store = new DataFileCollection(MERKLE_DB_CONFIG, testDir, storeName, null);
        final DataFileCompactor compactor =
                new DataFileCompactor(MERKLE_DB_CONFIG, storeName, store, index, null, null, null, null);
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
                assertTrue(List.of(2, 3).contains(store.getAllCompletedFiles().size()));
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
                final long dataLocation = storeDataItem(store, new long[] {i * numValues + j, i * numValues + j + 1});
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
        final LongListOffHeap index2 =
                new LongListOffHeap(snapshotDir.resolve("index.ll"), numValues, numFiles * numValues, 0, CONFIGURATION);
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

    @Test
    @DisplayName("Restore with inconsistent index")
    void testInconsistentIndex() throws Exception {
        final int MAXKEYS = 100;
        final LongList index = new LongListOffHeap(MAXKEYS / 10, MAXKEYS, 0);
        String storeName = "testInconsistentIndex";
        final Path testDir = tempFileDir.resolve(storeName);
        final DataFileCollection store = new DataFileCollection(MERKLE_DB_CONFIG, testDir, storeName, null);
        final DataFileCompactor compactor =
                new DataFileCompactor(MERKLE_DB_CONFIG, storeName, store, index, null, null, null, null);

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

    private static List<DataFileReader> getFilesToMerge(DataFileCollection store) {
        return store.getAllCompletedFiles();
    }
}
