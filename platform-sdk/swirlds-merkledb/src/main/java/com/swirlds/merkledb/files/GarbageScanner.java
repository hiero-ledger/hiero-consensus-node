// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.files;

import static com.swirlds.base.units.UnitConstants.KIBIBYTES_TO_BYTES;
import static com.swirlds.logging.legacy.LogMarker.MERKLE_DB;

import com.swirlds.merkledb.collections.CASableLongIndex;
import com.swirlds.merkledb.files.hashmap.HalfDiskHashMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
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
public class GarbageScanner {

    private static final Logger logger = LogManager.getLogger(GarbageScanner.class);

    private final CASableLongIndex index;
    private final DataFileCollection dataFileCollection;
    private final String storeName;

    public GarbageScanner(
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

        final Map<Integer, GarbageFileStats> statsByFileIndex = createStatsByFileIndexMap();

        try {
            index.forEach(
                    (key, dataLocation) -> {
                        final int fileIndex = DataFileCommon.fileIndexFromDataLocation(dataLocation);
                        final GarbageFileStats fileStats = statsByFileIndex.get(fileIndex);
                        assert fileStats != null;
                        fileStats.incrementAliveItems();
                    },
                    null);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn(MERKLE_DB.getMarker(), "[{}] Garbage scan was interrupted", storeName);
            return Map.of();
        }

        logLevelStats(statsByFileIndex);

        final long tookMillis = System.currentTimeMillis() - start;
        logger.info(MERKLE_DB.getMarker(), "[{}] Garbage scan finished in {} ms", storeName, tookMillis);

        return statsByFileIndex;
    }

    private void logLevelStats(Map<Integer, GarbageFileStats> statsByFileIndex) {
        final Map<Integer, long[]> totalsByLevel = new TreeMap<>();
        for (final GarbageFileStats stats : statsByFileIndex.values()) {
            final long[] levelTotals = totalsByLevel.computeIfAbsent(stats.compactionLevel(), level -> new long[3]);
            levelTotals[0]++;
            levelTotals[1] += stats.totalItems();
            levelTotals[2] += stats.aliveItems();
        }

        totalsByLevel.forEach((level, totals) -> {
            final long levelFilesCount = totals[0];
            final long levelTotalItems = totals[1];
            final long levelAliveItems = totals[2];
            final double levelGarbageRatio =
                    levelTotalItems == 0 ? 1.0 : 1.0 - ((double) levelAliveItems / levelTotalItems);

            logger.info(
                    MERKLE_DB.getMarker(),
                    "[{}] Garbage scan level {}: files={}, totalItems={}, aliveItems={}, garbageRatio={}",
                    storeName,
                    level,
                    levelFilesCount,
                    levelTotalItems,
                    levelAliveItems,
                    levelGarbageRatio);
        });
    }

    private Map<Integer, GarbageFileStats> createStatsByFileIndexMap() {
        final List<DataFileReader> allFiles = dataFileCollection.getAllCompletedFiles();
        final Map<Integer, GarbageFileStats> statsByFileIndex = new HashMap<>(allFiles.size());
        for (final DataFileReader file : allFiles) {
            final DataFileMetadata metadata = file.getMetadata();
            final GarbageFileStats stats =
                    new GarbageFileStats(file.getIndex(), metadata.getCompactionLevel(), metadata.getItemsCount());
            statsByFileIndex.put(stats.fileIndex(), stats);
        }
        return statsByFileIndex;
    }

    /**
     * Evaluates which levels have enough garbage to warrant compaction and returns the files
     * to compact for each level.
     *
     * <p>A file is included in the compaction set if its garbage ratio exceeds {@code garbageThreshold}.
     * Only levels with at least one such file are included in the result.
     *
     * @param scanResult       per-file garbage statistics from a recent scan
     * @param allFiles         current list of completed files in the collection
     * @param garbageThreshold garbage ratio that triggers compaction for a file
     * @param maxCompactionDataPerLevelInKB maximum total size in KB of files to compact per level; non-positive
     *                                       value means no limit
     * @return map from level to list of files to compact; multiple levels may be eligible
     */
    public static Map<Integer, List<DataFileReader>> evaluateCompactionCandidates(
            final Map<Integer, GarbageFileStats> scanResult,
            final List<DataFileReader> allFiles,
            final double garbageThreshold,
            final long maxCompactionDataPerLevelInKB) {

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
            final long maxCompactionDataPerLevelInBytes =
                    maxCompactionDataPerLevelInKB <= 0 ? Long.MAX_VALUE : maxCompactionDataPerLevelInKB * KIBIBYTES_TO_BYTES;

            long totalCompactionDataInBytes = 0;
            final List<DataFileReader> filesToCompact = new ArrayList<>();
            for (final DataFileReader file : levelFiles) {
                final GarbageFileStats stats = scanResult.get(file.getIndex());
                if (stats == null || stats.garbageRatio() <= garbageThreshold) {
                    continue;
                }
                final long fileSize = file.getSize();

                if (!filesToCompact.isEmpty() && // we add at least one file to the compaction list
                        totalCompactionDataInBytes + fileSize > maxCompactionDataPerLevelInBytes) {
                    continue;
                }
                filesToCompact.add(file);
                totalCompactionDataInBytes += fileSize;
            }
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
         *
         * Returns 1.0 for empty files (totalItems == 0). This value may be in two cases:
         * 1) the file is truly empty, in which case the compaction task will be no-op which is acceptable, or
         * 2) the file metadata is of the previous version which didn't support the item count. In this case we need to do the full compaction.
         */
        public double garbageRatio() {
            if (totalItems == 0) {
                return 1.0;
            }
            return 1.0 - ((double) aliveItems / totalItems);
        }
    }
}
