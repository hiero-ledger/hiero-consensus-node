// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.files;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.logging.legacy.LogMarker.MERKLE_DB;
import static com.swirlds.merkledb.files.DataFileCommon.formatSizeBytes;
import static com.swirlds.merkledb.files.DataFileCommon.getSizeOfFiles;
import static com.swirlds.merkledb.files.DataFileCommon.getSizeOfFilesByPath;
import static com.swirlds.merkledb.files.DataFileCommon.logCompactStats;

import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.swirlds.base.units.UnitConstants;
import com.swirlds.merkledb.KeyRange;
import com.swirlds.merkledb.collections.CASableLongIndex;
import com.swirlds.merkledb.config.MerkleDbConfig;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * This class is responsible performing compaction of data files in a {@link DataFileCollection}.
 * The compaction is supposed to happen in the background and can be paused and resumed with {@link #pauseCompaction()}
 * and {@link #resumeCompaction()} to prevent compaction from interfering with snapshots.
 */
public class DataFileCompactor {

    private static final Logger logger = LogManager.getLogger(DataFileCompactor.class);

    public static final String HASH_STORE_DISK = "HashStoreDisk";
    public static final String OBJECT_KEY_TO_PATH = "ObjectKeyToPath";
    public static final String PATH_TO_KEY_VALUE = "PathToKeyValue";

    /**
     * This is the compaction level that non-compacted files have.
     */
    public static final int INITIAL_COMPACTION_LEVEL = 0;

    private final MerkleDbConfig dbConfig;

    /**
     * Name of the file store to compact. This is used for logging and metrics.
     */
    private final String storeName;

    /**
     * The data file collection to compact.
     */
    private final DataFileCollection dataFileCollection;

    /**
     * Index to update during compaction.
     */
    private final CASableLongIndex index;

    /**
     * A function that will be called to report the duration of the compaction.
     */
    @Nullable
    private final BiConsumer<Integer, Long> reportDurationMetricFunction;

    /**
     * A function that will be called to report the amount of space saved by the compaction.
     */
    @Nullable
    private final BiConsumer<Integer, Double> reportSavedSpaceMetricFunction;

    private final BiConsumer<Integer, Double> reportFileSizeByLevelMetricFunction;

    /**
     * A function that updates statistics of total usage of disk space and off-heap space.
     */
    @Nullable
    private final Runnable updateTotalStatsFunction;

    /**
     * A lock used for synchronization between snapshots and compactions. While a compaction is in
     * progress, it runs on its own without any synchronization. However, a few critical sections
     * are protected with this lock: to create a new compaction writer/reader when compaction is
     * started, to copy data items to the current writer and update the corresponding index item,
     * and to close the compaction writer. This mechanism allows snapshots to effectively put
     * compaction on hold, which is critical as snapshots should be as fast as possible, while
     * compactions are just background processes.
     */
    private final Lock snapshotCompactionLock = new ReentrantLock();

    /**
     * Start time of the current compaction, or null if compaction isn't running
     */
    private final AtomicReference<Instant> currentCompactionStartTime = new AtomicReference<>();

    /**
     * Current data file writer during compaction, or null if compaction isn't running. The writer
     * is created at compaction start. If compaction is interrupted by a snapshot, the writer is
     * closed before the snapshot, and then a new writer / new file is created after the snapshot is
     * taken.
     */
    private final AtomicReference<DataFileWriter> currentWriter = new AtomicReference<>();
    /**
     * Current data file reader for the compaction writer above.
     */
    private final AtomicReference<DataFileReader> currentReader = new AtomicReference<>();
    /**
     * The list of new files created during compaction. Usually, all files to process are compacted
     * to a single new file, but if compaction is interrupted by a snapshot, there may be more than
     * one file created.
     */
    private final List<Path> newCompactedFiles = new ArrayList<>();

    /**
     * Indicates whether compaction is in progress at the time when {@link #pauseCompaction()}
     * is called. This flag is then checked in {@link DataFileCompactor#resumeCompaction()} )} to start a new
     * compacted file or not. This field is synchronized using {@link #snapshotCompactionLock}.
     */
    private boolean compactionWasInProgress = false;

    /**
     * This variable keeps track of the compaction level that was in progress at the time when it was suspended.
     * Once the compaction is resumed, this level is used to start a new compacted file, and then it's reset to
     * 0. This field is synchronized using {@link #snapshotCompactionLock}.
     */
    private int compactionLevelInProgress = 0;

    /**
     * A flag used to interrupt this compaction task rather than using {@link Thread#interrupt()}.
     * This flag is set in {@link #interruptCompaction()} and checked periodically in the main
     * compaction loop.
     */
    private volatile boolean interruptFlag = false;

    /**
     * @param dbConfig                       MerkleDb config
     * @param storeName                      name of the store to compact
     * @param dataFileCollection             data file collection to compact
     * @param index                          index to update during compaction
     * @param reportDurationMetricFunction   function to report how long compaction took, in ms
     * @param reportSavedSpaceMetricFunction function to report how much space was compacted, in Mb
     * @param reportFileSizeByLevelMetricFunction function to report how much space is used by the store by compaction level, in Mb
     * @param updateTotalStatsFunction       A function that updates statistics of total usage of disk space and off-heap space
     */
    public DataFileCompactor(
            final MerkleDbConfig dbConfig,
            final String storeName,
            final DataFileCollection dataFileCollection,
            CASableLongIndex index,
            @Nullable final BiConsumer<Integer, Long> reportDurationMetricFunction,
            @Nullable final BiConsumer<Integer, Double> reportSavedSpaceMetricFunction,
            @Nullable final BiConsumer<Integer, Double> reportFileSizeByLevelMetricFunction,
            @Nullable Runnable updateTotalStatsFunction) {
        this.dbConfig = dbConfig;
        this.storeName = storeName;
        this.dataFileCollection = dataFileCollection;
        this.index = index;
        this.reportDurationMetricFunction = reportDurationMetricFunction;
        this.reportSavedSpaceMetricFunction = reportSavedSpaceMetricFunction;
        this.reportFileSizeByLevelMetricFunction = reportFileSizeByLevelMetricFunction;
        this.updateTotalStatsFunction = updateTotalStatsFunction;
    }

    /**
     * Compacts all files in compactionPlan.
     *
     * @param index          takes a map of moves from old location to new location. Once it is finished and
     *                       returns it is assumed all readers will no longer be looking in old location, so old files
     *                       can be safely deleted.
     * @param filesToCompact list of files to compact
     * @param targetCompactionLevel target compaction level
     * @return list of files created during the compaction
     * @throws IOException          If there was a problem with the compaction
     * @throws InterruptedException If the compaction thread was interrupted
     */
    List<Path> compactFiles(
            final CASableLongIndex index,
            final List<? extends DataFileReader> filesToCompact,
            final int targetCompactionLevel)
            throws IOException, InterruptedException {
        if (filesToCompact.size() < getMinNumberOfFilesToCompact()) {
            // nothing to do we have merged since the last data update
            logger.debug(MERKLE_DB.getMarker(), "No files were available for merging [{}]", storeName);
            return Collections.emptyList();
        }

        if (interruptFlag) {
            return Collections.emptyList();
        }

        // create a merge time stamp, this timestamp is the newest time of the set of files we are
        // merging
        final Instant startTime = filesToCompact.stream()
                .map(file -> file.getMetadata().getCreationDate())
                .max(Instant::compareTo)
                .orElseGet(Instant::now);
        snapshotCompactionLock.lock();
        try {
            currentCompactionStartTime.set(startTime);
            newCompactedFiles.clear();
            startNewCompactionFile(targetCompactionLevel);
        } finally {
            snapshotCompactionLock.unlock();
        }

        // We need a map to find readers by file index below. It doesn't have to be synchronized
        // as it will be accessed in this thread only, so it can be a simple HashMap or alike.
        // However, standard Java maps can only work with Integer, not int (yet), so auto-boxing
        // will put significant load on GC. Let's do something different
        int minFileIndex = Integer.MAX_VALUE;
        int maxFileIndex = 0;
        for (final DataFileReader r : filesToCompact) {
            minFileIndex = Math.min(minFileIndex, r.getIndex());
            maxFileIndex = Math.max(maxFileIndex, r.getIndex());
        }
        final int firstIndexInc = minFileIndex;
        final int lastIndexExc = maxFileIndex + 1;
        final DataFileReader[] readers = new DataFileReader[lastIndexExc - firstIndexInc];
        for (DataFileReader r : filesToCompact) {
            readers[r.getIndex() - firstIndexInc] = r;
        }

        boolean allDataItemsProcessed = false;
        try {
            final KeyRange keyRange = dataFileCollection.getValidKeyRange();
            allDataItemsProcessed = index.forEach(
                    (path, dataLocation) -> {
                        if (!keyRange.withinRange(path)) {
                            return;
                        }
                        final int fileIndex = DataFileCommon.fileIndexFromDataLocation(dataLocation);
                        if ((fileIndex < firstIndexInc) || (fileIndex >= lastIndexExc)) {
                            return;
                        }
                        final DataFileReader reader = readers[fileIndex - firstIndexInc];
                        if (reader == null) {
                            return;
                        }
                        final long fileOffset = DataFileCommon.byteOffsetFromDataLocation(dataLocation);
                        // Take the lock. If a snapshot is started in a different thread, this call
                        // will block until the snapshot is done. The current file will be flushed,
                        // and current data file writer and reader will point to a new file
                        snapshotCompactionLock.lock();
                        try {
                            final DataFileWriter newFileWriter = currentWriter.get();
                            final BufferedData itemBytes = reader.readDataItem(fileOffset);
                            assert itemBytes != null;
                            long newLocation = newFileWriter.storeDataItem(itemBytes);
                            // update the index
                            index.putIfEqual(path, dataLocation, newLocation);
                        } catch (final IOException z) {
                            logger.error(
                                    EXCEPTION.getMarker(),
                                    "[{}] Failed to copy data item {} / {}",
                                    storeName,
                                    fileIndex,
                                    fileOffset,
                                    z);
                            throw z;
                        } finally {
                            snapshotCompactionLock.unlock();
                        }
                    },
                    this::notInterrupted);
        } finally {
            // Even if the thread is interrupted, make sure the new compacted file is properly closed
            // and is included to future compactions
            snapshotCompactionLock.lock();
            try {
                // Finish writing the last file. In rare cases, it may be an empty file
                finishCurrentCompactionFile();
                // Clear compaction start time
                currentCompactionStartTime.set(null);
                if (allDataItemsProcessed) {
                    logger.info(
                            MERKLE_DB.getMarker(), "All files to compact have been processed, they will be deleted");
                    // Close the readers and delete compacted files
                    dataFileCollection.deleteFiles(filesToCompact);
                } else {
                    logger.info(
                            MERKLE_DB.getMarker(),
                            "Some files to compact haven't been processed, they will be compacted later");
                }
            } finally {
                snapshotCompactionLock.unlock();
            }
        }

        return newCompactedFiles;
    }

    // visible for testing
    int getMinNumberOfFilesToCompact() {
        return dbConfig.minNumberOfFilesInCompaction();
    }

    /**
     * Opens a new file for writing during compaction. This method is called, when compaction is
     * started. If compaction is interrupted and resumed by data source snapshot using {@link
     * #pauseCompaction()} and {@link #resumeCompaction()}, a new file is created for writing using
     * this method before compaction is resumed.
     * <p>
     * This method must be called under snapshot/compaction lock.
     *
     * @throws IOException If an I/O error occurs
     */
    private void startNewCompactionFile(int compactionLevel) throws IOException {
        final Instant startTime = currentCompactionStartTime.get();
        assert startTime != null;
        final DataFileWriter newFileWriter = dataFileCollection.newDataFile(startTime, compactionLevel);
        currentWriter.set(newFileWriter);
        final Path newFileCreated = newFileWriter.getPath();
        newCompactedFiles.add(newFileCreated);
        final DataFileMetadata newFileMetadata = newFileWriter.getMetadata();
        final DataFileReader newFileReader = dataFileCollection.addNewDataFileReader(newFileCreated, newFileMetadata);
        currentReader.set(newFileReader);
        logger.info(MERKLE_DB.getMarker(), "[{}] New compaction file, newFile={}", storeName, newFileReader.getIndex());
    }

    /**
     * Closes the current compaction file. This method is called in the end of compaction process,
     * and also before a snapshot is taken to make sure the current file is fully written and safe
     * to include to snapshots.
     * <p>
     * This method must be called under snapshot/compaction lock.
     *
     * @throws IOException If an I/O error occurs
     */
    private void finishCurrentCompactionFile() throws IOException {
        currentWriter.get().close();
        currentWriter.set(null);
        // Now include the file in future compactions
        currentReader.get().setFileCompleted();
        logger.info(
                MERKLE_DB.getMarker(),
                "[{}] Compaction file written, fileNum={}",
                storeName,
                currentReader.get().getIndex());
        currentReader.set(null);
    }

    /**
     * Puts file compaction on hold, if it's currently in progress. If not in progress, it will
     * prevent compaction from starting until {@link #resumeCompaction()} is called. The most
     * important thing this method does is it makes data files consistent and read only, so they can
     * be included to snapshots as easily as to create hard links. In particular, if compaction is
     * in progress, and a new data file is being written to, this file is flushed to disk, no files
     * are created and no index entries are updated until compaction is resumed.
     * <p>
     * This method should not be called on the compaction thread.
     * <p>
     * <b>This method must be always balanced with and called before {@link DataFileCompactor#resumeCompaction()}. If
     * there are more / less calls to resume compactions than to pause, or if they are called in a
     * wrong order, it will result in deadlocks.</b>
     *
     * @throws IOException If an I/O error occurs
     * @see #resumeCompaction()
     */
    public void pauseCompaction() throws IOException {
        snapshotCompactionLock.lock();
        // Check if compaction is currently in progress. If so, flush and close the current file, so
        // it's included to the snapshot
        final DataFileWriter compactionWriter = currentWriter.get();
        if (compactionWriter != null) {
            compactionWasInProgress = true;
            compactionLevelInProgress = compactionWriter.getMetadata().getCompactionLevel();
            finishCurrentCompactionFile();
            // Don't start a new compaction file here, as it would be included to snapshots, but
            // it shouldn't, as it isn't fully written yet. Instead, a new file will be started
            // right after snapshot is taken, in resumeCompaction()
        }
        // Don't release the lock here, it will be done later in resumeCompaction(). If there is no
        // compaction currently running, the lock will prevent starting a new one until snapshot is
        // done
    }

    /**
     * Resumes compaction previously put on hold with {@link #pauseCompaction()}. If there was no
     * compaction running at that moment, but new compaction was started (and blocked) since {@link
     * #pauseCompaction()}, this new compaction is resumed.
     *
     * <p><b>This method must be always balanced with and called after {@link #pauseCompaction()} on
     * the same thread. If there are more or less calls to resume compactions than to pause, or if
     * they are called in a wrong order, it will result in deadlocks.</b>
     *
     * @throws IOException If an I/O error occurs
     * @throws IllegalMonitorStateException If this method is called on a different thread than
     *      {@link #pauseCompaction()}
     */
    public void resumeCompaction() throws IOException {
        try {
            if (compactionWasInProgress) {
                compactionWasInProgress = false;
                assert currentWriter.get() == null;
                assert currentReader.get() == null;
                startNewCompactionFile(compactionLevelInProgress);
                compactionLevelInProgress = 0;
            }
        } finally {
            snapshotCompactionLock.unlock();
        }
    }

    /**
     * Interrupts this compaction task. This is a less invasive way to stop the task than
     * {@link Thread#interrupt()}, which has side effects like interrupt exceptions and
     * closed file channels.
     *
     * <p>There is no guarantee that the task, if currently running, is stopped immediately
     * after this method is called, but it's stopped in reasonable time.
     */
    public void interruptCompaction() {
        interruptFlag = true;
    }

    // A helper method to avoid using a lambda in compactFiles()
    public boolean notInterrupted() {
        return !interruptFlag;
    }

    /**
     * @return true if compaction is currently running, false otherwise.
     */
    public boolean isCompactionRunning() {
        snapshotCompactionLock.lock();
        try {
            return currentCompactionStartTime.get() != null;
        } finally {
            snapshotCompactionLock.unlock();
        }
    }

    /**
     * @return true if compaction was started and now is complete (successfully or
     * exceptionally), false otherwise.
     */
    public boolean isCompactionComplete() {
        snapshotCompactionLock.lock();
        try {
            return (currentCompactionStartTime.get() == null) && !newCompactedFiles.isEmpty();
        } finally {
            snapshotCompactionLock.unlock();
        }
    }

    /**
     * Compact data files in the collection according to the compaction algorithm.
     *
     * @throws IOException          if there was a problem merging
     * @throws InterruptedException if the merge thread was interrupted
     * @return true if compaction was performed, false otherwise
     */
    public boolean compact() throws IOException, InterruptedException {
        final List<DataFileReader> completedFiles = dataFileCollection.getAllCompletedFiles();
        reportFileSizeByLevel(completedFiles);
        final List<DataFileReader> filesToCompact =
                compactionPlan(completedFiles, getMinNumberOfFilesToCompact(), dbConfig.maxCompactionLevel());
        if (filesToCompact.isEmpty()) {
            logger.debug(MERKLE_DB.getMarker(), "[{}] No need to compact, as the compaction plan is empty", storeName);
            return false;
        }

        final int filesCount = filesToCompact.size();
        logger.info(MERKLE_DB.getMarker(), "[{}] Starting compaction", storeName);

        final int targetCompactionLevel = getTargetCompactionLevel(filesToCompact, filesCount);

        final long start = System.currentTimeMillis();

        final long filesToCompactSize = getSizeOfFiles(filesToCompact);
        logger.debug(
                MERKLE_DB.getMarker(),
                "[{}] Starting merging {} files / {}",
                storeName,
                filesCount,
                formatSizeBytes(filesToCompactSize));

        final List<Path> newFilesCreated = compactFiles(index, filesToCompact, targetCompactionLevel);

        final long end = System.currentTimeMillis();
        final long tookMillis = end - start;
        if (reportDurationMetricFunction != null) {
            reportDurationMetricFunction.accept(targetCompactionLevel, tookMillis);
        }

        final long compactedFilesSize = getSizeOfFilesByPath(newFilesCreated);
        if (reportSavedSpaceMetricFunction != null) {
            reportSavedSpaceMetricFunction.accept(
                    targetCompactionLevel,
                    (filesToCompactSize - compactedFilesSize) * UnitConstants.BYTES_TO_MEBIBYTES);
        }

        reportFileSizeByLevel(dataFileCollection.getAllCompletedFiles());

        logCompactStats(
                storeName,
                tookMillis,
                filesToCompact,
                filesToCompactSize,
                newFilesCreated,
                targetCompactionLevel,
                dataFileCollection);
        logger.info(
                MERKLE_DB.getMarker(),
                "[{}] Finished compaction {} files / {} in {} ms",
                storeName,
                filesCount,
                formatSizeBytes(filesToCompactSize),
                tookMillis);

        if (updateTotalStatsFunction != null) {
            updateTotalStatsFunction.run();
        }

        return true;
    }

    private void reportFileSizeByLevel(List<DataFileReader> allCompletedFiles) {
        if (reportFileSizeByLevelMetricFunction != null) {
            final Map<Integer, List<DataFileReader>> readersByLevel = getReadersByLevel(allCompletedFiles);
            for (int i = 0; i < readersByLevel.size(); i++) {
                final List<DataFileReader> readers = readersByLevel.get(i);
                if (readers != null) {
                    reportFileSizeByLevelMetricFunction.accept(
                            i, getSizeOfFiles(readers) * UnitConstants.BYTES_TO_MEBIBYTES);
                }
            }
        }
    }

    /**
     * The target compaction level should not exceed the maxCompactionLevel configuration parameter.
     * We need a limit on compaction levels for two reasons:
     *  - To ensure a reasonably predictable frequency for full compactions, even for data that changes infrequently.
     *  - We maintain metrics for each level, and there should be a cap on the number of these metrics.
     */
    private int getTargetCompactionLevel(List<? extends DataFileReader> filesToCompact, int filesCount) {
        int highestExistingCompactionLevel =
                filesToCompact.get(filesCount - 1).getMetadata().getCompactionLevel();

        return Math.min(highestExistingCompactionLevel + 1, dbConfig.maxCompactionLevel());
    }

    /**
     * This method creates a compaction plan (a set of files to be compacted). The plan is organized by compaction levels
     * in ascending order. If there are not enough files to compact, then no files are compacted and the plan will be empty.
     * If the current level doesn't reach minNumberOfFilesToCompact threshold,
     * then this level and the levels above it are not included in the plan.
     * @return filter creating a compaction plan
     */
    static List<DataFileReader> compactionPlan(
            List<DataFileReader> dataFileReaders, int minNumberOfFilesToCompact, int maxCompactionLevel) {
        if (dataFileReaders.isEmpty()) {
            return dataFileReaders;
        }

        final Map<Integer, List<DataFileReader>> readersByLevel = getReadersByLevel(dataFileReaders);

        final List<DataFileReader> nonCompactedReaders = readersByLevel.get(INITIAL_COMPACTION_LEVEL);
        if (nonCompactedReaders == null || nonCompactedReaders.size() < minNumberOfFilesToCompact) {
            return Collections.emptyList();
        }

        // we always compact files from level 0 if we have enough files
        final List<DataFileReader> readersToCompact = new ArrayList<>(nonCompactedReaders);

        for (int i = 1; i <= maxCompactionLevel; i++) {
            final List<DataFileReader> readers = readersByLevel.get(i);
            // Presumably, one file comes from the compaction of the previous level.
            // If, counting this file in, it still doesn't have enough, then it stops collecting.
            if (readers == null || readers.size() < minNumberOfFilesToCompact - 1) {
                break;
            }
            readersToCompact.addAll(readers);
        }
        return readersToCompact;
    }

    private static Map<Integer, List<DataFileReader>> getReadersByLevel(final List<DataFileReader> dataFileReaders) {
        return dataFileReaders.stream()
                .collect(Collectors.groupingBy(r -> r.getMetadata().getCompactionLevel()));
    }
}
