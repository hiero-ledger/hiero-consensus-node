// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.files;

import static com.swirlds.logging.legacy.LogMarker.MERKLE_DB;

import com.swirlds.merkledb.collections.CASableLongIndex;
import com.swirlds.merkledb.files.hashmap.HalfDiskHashMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Traverses the in-memory index for a file collection and computes per-file garbage statistics.
 *
 * <p>The scanner counts the number of index entries that point to each data file and compares
 * this to the total number of items recorded in the file's metadata. The ratio of dead items
 * to total items is the garbage ratio.
 *
 * <p>For {@link MemoryIndexDiskKeyValueStore}-backed stores (HashStoreDisk, PathToKeyValue),
 * each index entry corresponds to a single data item, so the garbage ratio reflects individual
 * item liveness. For {@link HalfDiskHashMap}-backed store (ObjectKeyToPath), each index entry
 * corresponds to a bucket that may contain multiple keys. In this case, the garbage ratio
 * reflects bucket-level liveness, which is a reasonable approximation of file-level garbage
 * since bucket sizes are roughly uniform. This may slightly underestimate garbage (an alive
 * bucket can contain stale entries internally), which is a safe direction for compaction
 * decisions.
 */
public class GarbageScannerTask {

    private static final Logger logger = LogManager.getLogger(GarbageScannerTask.class);

    private final CASableLongIndex index;
    private final DataFileCollection dataFileCollection;
    private final String storeName;

    public GarbageScannerTask(
            final CASableLongIndex index, final DataFileCollection dataFileCollection, final String storeName) {
        this.index = index;
        this.dataFileCollection = dataFileCollection;
        this.storeName = storeName;
    }

    /**
     * Traverse the index and produce per-file stats with total and alive item counts.
     *
     * <p>This method is intended to be called from a single background thread. It is read-only
     * with respect to both the index and the data files — no data is copied or modified.
     *
     * @return a map from file index to garbage statistics for that file
     */
    public Map<Integer, GarbageFileStats> scan() {
        final long start = System.currentTimeMillis();

        final List<DataFileReader> allFiles = dataFileCollection.getAllCompletedFiles();
        final Map<Integer, GarbageFileStats> statsByFileIndex = new HashMap<>(allFiles.size());
        long totalItems = 0;
        for (final DataFileReader file : allFiles) {
            final DataFileMetadata metadata = file.getMetadata();
            final GarbageFileStats stats =
                    new GarbageFileStats(file.getIndex(), metadata.getCompactionLevel(), metadata.getItemsCount());
            statsByFileIndex.put(stats.fileIndex(), stats);
            totalItems += stats.totalItems();
        }

        final long[] aliveTotal = {0};
        try {
            index.forEach(
                    (key, dataLocation) -> {
                        final int fileIndex = DataFileCommon.fileIndexFromDataLocation(dataLocation);
                        final GarbageFileStats fileStats = statsByFileIndex.get(fileIndex);
                        if (fileStats != null) {
                            fileStats.incrementAliveItems();
                            aliveTotal[0]++;
                        }
                    },
                    null);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn(MERKLE_DB.getMarker(), "[{}] Garbage scan was interrupted", storeName);
            return Map.of();
        }

        final long tookMillis = System.currentTimeMillis() - start;
        logger.info(
                MERKLE_DB.getMarker(),
                "[{}] Garbage scan finished in {} ms, files={}, totalItems={}, aliveItems={}",
                storeName,
                tookMillis,
                statsByFileIndex.size(),
                totalItems,
                aliveTotal[0]);

        return statsByFileIndex;
    }

    /**
     * Evaluates which levels have enough garbage to warrant compaction and returns the files
     * to compact for each eligible level.
     *
     * <p>A level is eligible if at least one file exceeds {@code maxGarbageThreshold}. If no
     * file at a level exceeds the max threshold, the level is skipped entirely — even if some
     * files exceed {@code minGarbageThreshold}. For eligible levels, all files exceeding
     * {@code minGarbageThreshold} are included in the compaction set.
     *
     * @param scanResult          per-file garbage statistics from a recent scan
     * @param allFiles            current list of completed files in the collection
     * @param minGarbageThreshold minimum garbage ratio to include a file in compaction
     * @param maxGarbageThreshold garbage ratio that triggers compaction for a level
     * @return map from level to list of files to compact; multiple levels may be eligible
     */
    public static Map<Integer, List<DataFileReader>> evaluateCompactionCandidates(
            final Map<Integer, GarbageFileStats> scanResult,
            final List<DataFileReader> allFiles,
            final double minGarbageThreshold,
            final double maxGarbageThreshold) {

        // Group files by compaction level
        final Map<Integer, List<DataFileReader>> readersByLevel = new HashMap<>();
        for (final DataFileReader reader : allFiles) {
            readersByLevel
                    .computeIfAbsent(reader.getMetadata().getCompactionLevel(), ignored -> new ArrayList<>())
                    .add(reader);
        }

        final Map<Integer, List<DataFileReader>> result = new HashMap<>();
        for (final Map.Entry<Integer, List<DataFileReader>> entry : readersByLevel.entrySet()) {
            final int level = entry.getKey();
            final List<DataFileReader> levelFiles = entry.getValue();

            // A level is only eligible if at least one file exceeds the max threshold
            final boolean triggered = levelFiles.stream()
                    .map(file -> scanResult.get(file.getIndex()))
                    .anyMatch(stats -> stats != null && stats.garbageRatio() > maxGarbageThreshold);

            if (!triggered) {
                continue;
            }

            // For an eligible level, include all files above the min threshold
            final List<DataFileReader> filesToCompact = levelFiles.stream()
                    .filter(file -> {
                        final GarbageFileStats stats = scanResult.get(file.getIndex());
                        return stats != null && stats.garbageRatio() > minGarbageThreshold;
                    })
                    .toList();
            if (!filesToCompact.isEmpty()) {
                result.put(level, filesToCompact);
            }
        }

        return result;
    }

    /**
     * Per-file garbage statistics. Tracks total items (from file metadata) and alive items
     * (counted during index traversal). Not thread-safe — intended for use within a single
     * scanner thread.
     */
    public static final class GarbageFileStats {

        private final int fileIndex;
        private final int compactionLevel;
        private final long totalItems;
        private long aliveItems;

        /**
         * Creates stats with zero alive items. Used by the scanner during index traversal;
         * alive items are incremented as entries are encountered.
         */
        public GarbageFileStats(final int fileIndex, final int compactionLevel, final long totalItems) {
            this(fileIndex, compactionLevel, totalItems, 0);
        }

        /**
         * Creates stats with a known alive item count. Useful for testing and for constructing
         * stats from pre-computed data.
         */
        public GarbageFileStats(
                final int fileIndex, final int compactionLevel, final long totalItems, final long aliveItems) {
            this.fileIndex = fileIndex;
            this.compactionLevel = compactionLevel;
            this.totalItems = totalItems;
            this.aliveItems = aliveItems;
        }

        public int fileIndex() {
            return fileIndex;
        }

        public int compactionLevel() {
            return compactionLevel;
        }

        public long totalItems() {
            return totalItems;
        }

        public long aliveItems() {
            return aliveItems;
        }

        void incrementAliveItems() {
            aliveItems++;
        }

        /**
         * Returns the fraction of items in this file that are no longer referenced by the index.
         * Returns 0.0 for empty files (totalItems == 0) to avoid division by zero and to
         * prevent empty files from triggering compaction.
         */
        public double garbageRatio() {
            if (totalItems == 0) {
                return 0.0;
            }
            return 1.0 - ((double) aliveItems / totalItems);
        }
    }
}
