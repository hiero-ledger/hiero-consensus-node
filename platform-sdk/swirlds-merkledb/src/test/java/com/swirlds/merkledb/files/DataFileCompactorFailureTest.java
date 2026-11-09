// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.files;

import static com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils.DEFAULT_MERKLE_DB_CONFIG;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.swirlds.merkledb.collections.CASableLongIndex;
import com.swirlds.merkledb.collections.LongList;
import com.swirlds.merkledb.collections.LongListOffHeap;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests that a compaction stays consistent when a snapshot fails to pause or resume it: no index
 * entry may point to a file the collection can't read, and the input files must be kept.
 */
class DataFileCompactorFailureTest {

    private static final int NUM_FILES = 3;
    private static final int KEYS_PER_FILE = 100;
    private static final int NUM_KEYS = NUM_FILES * KEYS_PER_FILE;
    // The compaction is paused after this many items are copied
    private static final int PAUSE_AT = KEYS_PER_FILE;

    @TempDir
    Path tempDir;

    private Path storeDir;
    private FailingDataFileCollection store;
    private LongList index;
    private DataFileCompactor compactor;
    private List<DataFileReader> filesToCompact;
    private ExecutorService executor;

    @BeforeEach
    void setUp() throws IOException {
        storeDir = tempDir.resolve("store");
        store = new FailingDataFileCollection(storeDir, "store");
        index = new LongListOffHeap(KEYS_PER_FILE, NUM_KEYS, 0);
        index.updateValidRange(0, NUM_KEYS - 1);
        for (int file = 0; file < NUM_FILES; file++) {
            store.startWriting();
            for (long key = (long) file * KEYS_PER_FILE; key < (file + 1L) * KEYS_PER_FILE; key++) {
                index.put(key, storeDataItem(store, key));
            }
            store.updateValidKeyRange(0, NUM_KEYS - 1);
            store.endWriting();
        }
        filesToCompact = store.getAllCompletedFiles();
        assertEquals(NUM_FILES, filesToCompact.size());
        compactor = new DataFileCompactor(store, index, null, null, null, null);
        executor = Executors.newSingleThreadExecutor();
    }

    @AfterEach
    void tearDown() throws IOException {
        executor.shutdownNow();
        store.close();
        index.close();
    }

    @Test
    void resumeAbortsCompactionWhenNewFileReaderFails() throws Exception {
        final IOException readerFailure = new IOException("reader failed");
        final Future<?> compaction = startCompactionAndPause();
        store.failNextAddReader(readerFailure);

        assertSame(readerFailure, assertThrows(IOException.class, compactor::resumeCompaction));

        assertCompactionAborted(compaction);
        assertAllKeysReadable();
        assertTrue(store.getAllCompletedFiles().containsAll(filesToCompact), "Input files must be kept");
        assertEquals(completedFilePaths(), dataFilePathsOnDisk(), "The file without a reader must be deleted");
    }

    @Test
    void resumeAbortsCompactionWhenNewFileCantBeCreated() throws Exception {
        final IOException writerFailure = new IOException("writer failed");
        final Future<?> compaction = startCompactionAndPause();
        store.failNextNewDataFile(writerFailure);

        assertSame(writerFailure, assertThrows(IOException.class, compactor::resumeCompaction));

        assertCompactionAborted(compaction);
        assertAllKeysReadable();
        assertTrue(store.getAllCompletedFiles().containsAll(filesToCompact), "Input files must be kept");
        assertEquals(completedFilePaths(), dataFilePathsOnDisk());
    }

    @Test
    void compactionStartLeavesNoFileWhenReaderFails() throws Exception {
        final IOException readerFailure = new IOException("reader failed");
        store.failNextAddReader(readerFailure);

        assertSame(
                readerFailure, assertThrows(IOException.class, () -> compactor.compactFiles(index, filesToCompact, 1)));

        assertEquals(completedFilePaths(), dataFilePathsOnDisk(), "The file without a reader must be deleted");
        // No writer is left behind, so pausing and resuming work as if no compaction was running
        compactor.pauseCompaction();
        compactor.resumeCompaction();
        assertAllKeysReadable();
    }

    @Test
    void resumeAbortsCompactionWhenPauseFailsToFinishFile() throws Exception {
        final Future<?> compaction = startCompactionAndPause(() -> {
            // An interrupt makes the file channel fail when the writer rewrites the file header
            Thread.currentThread().interrupt();
            try {
                assertThrows(IOException.class, compactor::pauseCompaction);
            } finally {
                Thread.interrupted();
            }
        });

        assertThrows(IllegalStateException.class, compactor::resumeCompaction);

        assertCompactionAborted(compaction);
        assertAllKeysReadable();
        final List<DataFileReader> completedFiles = store.getAllCompletedFiles();
        assertTrue(completedFiles.containsAll(filesToCompact), "Input files must be kept");
        // The file whose close failed is finished by the compaction thread, so snapshots include it
        assertEquals(NUM_FILES + 1, completedFiles.size(), "Unexpected files: " + completedFiles);
        assertEquals(completedFilePaths(), dataFilePathsOnDisk());
    }

    /**
     * Starts a compaction of all files and pauses it after {@link #PAUSE_AT} items are copied.
     */
    private Future<?> startCompactionAndPause() throws Exception {
        return startCompactionAndPause(compactor::pauseCompaction);
    }

    private Future<?> startCompactionAndPause(final ThrowingRunnable pause) throws Exception {
        final CountDownLatch reached = new CountDownLatch(1);
        final CountDownLatch paused = new CountDownLatch(1);
        final CASableLongIndex pausingIndex = new PausingIndex(index, reached, paused);
        final Future<?> compaction = executor.submit(() -> {
            compactor.compactFiles(pausingIndex, filesToCompact, 1);
            return null;
        });
        assertTrue(reached.await(10, TimeUnit.SECONDS), "Compaction didn't start");
        try {
            pause.run();
        } finally {
            paused.countDown();
        }
        return compaction;
    }

    private static void assertCompactionAborted(final Future<?> compaction) throws Exception {
        final ExecutionException e = assertThrows(ExecutionException.class, () -> compaction.get(10, TimeUnit.SECONDS));
        final IOException cause = assertInstanceOf(IOException.class, e.getCause());
        assertTrue(cause.getMessage().contains("Compaction is aborted"), "Unexpected failure: " + cause);
    }

    private void assertAllKeysReadable() throws IOException {
        for (long key = 0; key < NUM_KEYS; key++) {
            final BufferedData data = store.readDataItem(index.get(key));
            assertNotNull(data, "No data for key " + key);
            assertEquals(key, data.readLong());
            assertEquals(key * 10, data.readLong());
        }
    }

    private Set<Path> completedFilePaths() {
        return store.getAllCompletedFiles().stream()
                .map(DataFileReader::getPath)
                .collect(Collectors.toSet());
    }

    private Set<Path> dataFilePathsOnDisk() throws IOException {
        try (final Stream<Path> files = Files.list(storeDir)) {
            return files.filter(p -> p.toString().endsWith(DataFileCommon.FILE_EXTENSION))
                    .collect(Collectors.toSet());
        }
    }

    private static long storeDataItem(final DataFileCollection store, final long key) throws IOException {
        return store.storeDataItem(
                out -> {
                    out.writeLong(key);
                    out.writeLong(key * 10);
                },
                2 * Long.BYTES);
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    /**
     * Blocks the compaction thread before it copies item {@link #PAUSE_AT}, outside the compactor's
     * lock, until the test has paused the compaction.
     */
    private record PausingIndex(LongList index, CountDownLatch reached, CountDownLatch paused)
            implements CASableLongIndex {

        @Override
        public long get(final long key) {
            return index.get(key);
        }

        @Override
        public boolean putIfEqual(final long key, final long oldValue, final long newValue) {
            return index.putIfEqual(key, oldValue, newValue);
        }

        @Override
        public <T extends Throwable> boolean forEach(final LongAction<T> action, final BooleanSupplier cond)
                throws InterruptedException, T {
            final AtomicLong count = new AtomicLong();
            return index.forEach(
                    (key, value) -> {
                        if (count.incrementAndGet() == PAUSE_AT + 1) {
                            reached.countDown();
                            assertTrue(paused.await(10, TimeUnit.SECONDS), "Test didn't pause the compaction");
                        }
                        action.handle(key, value);
                    },
                    cond);
        }
    }

    /**
     * A data file collection that fails to create the next data file or its reader on request.
     */
    private static final class FailingDataFileCollection extends DataFileCollection {

        private volatile IOException newDataFileFailure;
        private volatile IOException addReaderFailure;

        FailingDataFileCollection(final Path storeDir, final String storeName) throws IOException {
            super(DEFAULT_MERKLE_DB_CONFIG, storeDir, storeName, null);
        }

        void failNextNewDataFile(final IOException failure) {
            newDataFileFailure = failure;
        }

        void failNextAddReader(final IOException failure) {
            addReaderFailure = failure;
        }

        @Override
        DataFileWriter newDataFile(final Instant creationTime, final int compactionLevel) throws IOException {
            final IOException failure = newDataFileFailure;
            if (failure != null) {
                newDataFileFailure = null;
                throw failure;
            }
            return super.newDataFile(creationTime, compactionLevel);
        }

        @Override
        DataFileReader addNewDataFileReader(final Path filePath, final DataFileMetadata metadata) throws IOException {
            final IOException failure = addReaderFailure;
            if (failure != null) {
                addReaderFailure = null;
                throw failure;
            }
            return super.addNewDataFileReader(filePath, metadata);
        }
    }
}
